package com.arthur.stock.service.impl;

import com.arthur.stock.cache.TaskProgressCache;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.dto.governance.TaskProgress;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.AdjFactorMapper;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.service.*;
import com.arthur.stock.util.SensitiveDataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据拉取服务实现，负责单表增量更新和全量重建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataInitServiceImpl implements DataInitService {

    @Value("${app.db-type:mysql}")
    private String dbType;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** I/O 密集型任务使用虚拟线程，避免占用 ForkJoinPool.commonPool */
    private static final Executor IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final JdbcTemplate jdbcTemplate;
    private final StockBasicService stockBasicService;
    private final TradeCalService tradeCalService;
    private final IndexWeightService indexWeightService;
    private final SwIndustryService swIndustryService;
    private final DailyQuoteService dailyQuoteService;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final AdjFactorService adjFactorService;
    private final AdjFactorMapper adjFactorMapper;
    private final DividendService dividendService;
    private final StockNamechangeService stockNamechangeService;
    private final StockSuspendDService stockSuspendDService;
    private final StockStkLimitService stockStkLimitService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;
    private final TaskProgressCache taskProgressCache;
    private final DataPullLogMapper dataPullLogMapper;
    private final DataGovernanceService dataGovernanceService;
    private final BasicDataService basicDataService;
    private final MoneyflowService moneyflowService;
    private final TopListService topListService;
    private final BlockTradeService blockTradeService;
    private final HkHoldService hkHoldService;
    private final MarginService marginService;
    private final IndexDailyFetchService indexDailyFetchService;

    @Override
    public String incrementalUpdate(String tableCode, String operator) {
        InitStep step = InitStep.fromCode(tableCode);
        if (step == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的表代码: " + tableCode);
        }
        if (!taskProgressCache.tryAcquireLock()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "有任务正在执行，请稍后再试");
        }
        String taskId = UUID.randomUUID().toString();
        createPullLog(taskId, step, "MANUAL_INCREMENTAL", operator);
        putInitialProgress(taskId, tableCode);
        CompletableFuture.runAsync(() -> doIncrementalUpdate(step, taskId), IO_EXECUTOR);
        return taskId;
    }

    @Override
    public String fullRebuild(String tableCode, String operator) {
        InitStep step = InitStep.fromCode(tableCode);
        if (step == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的表代码: " + tableCode);
        }
        if (!taskProgressCache.tryAcquireLock()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "有任务正在执行，请稍后再试");
        }
        String taskId = UUID.randomUUID().toString();
        createPullLog(taskId, step, "MANUAL_FULL", operator);
        putInitialProgress(taskId, tableCode);
        CompletableFuture.runAsync(() -> doFullRebuild(step, taskId), IO_EXECUTOR);
        return taskId;
    }

    private void doIncrementalUpdate(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        StepStats stats = StepStats.empty();
        try {
            updateTaskProgress(taskId, 0, "增量拉取: " + step.getLabel(), 0, 0);
            stats = executeSingleStep(step, taskId, false);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, "用户取消", null, stats);
                log.info("Incremental update cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, null, null, stats);
            runQualityCheck(step);
            updateTaskProgress(taskId, 100, "完成", 0, 0);
            log.info("Incremental update completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Exception e) {
            log.error("Incremental update failed: {} (taskId={})", step.getLabel(), taskId, e);
            finishPullLog(taskId, "FAILED", startMs,
                    SensitiveDataUtil.mask(e.getMessage()),
                    SensitiveDataUtil.mask(getStackTrace(e)), stats);
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private void doFullRebuild(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        StepStats stats = StepStats.empty();
        try {
            updateTaskProgress(taskId, 0, "重建表: " + step.getLabel(), 0, 0);
            rebuildTable(step);

            updateTaskProgress(taskId, 0, "全量拉取: " + step.getLabel(), 0, 0);
            stats = executeSingleStep(step, taskId, true);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, "用户取消", null, stats);
                log.info("Full rebuild cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, null, null, stats);
            runQualityCheck(step);
            updateTaskProgress(taskId, 100, "完成", 0, 0);
            log.info("Full rebuild completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Exception e) {
            log.error("Full rebuild failed: {} (taskId={})", step.getLabel(), taskId, e);
            finishPullLog(taskId, "FAILED", startMs,
                    SensitiveDataUtil.mask(e.getMessage()),
                    SensitiveDataUtil.mask(getStackTrace(e)), stats);
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private StepStats executeSingleStep(InitStep step, String taskId, boolean isFull) {
        String today = LocalDate.now().format(DATE_FMT);
        String fullStart = LocalDate.now().minusYears(30).format(DATE_FMT);
        String indexWeightStart = LocalDate.now().minusYears(5).format(DATE_FMT);

        switch (step) {
            case STOCK_BASIC -> {
                stockBasicService.fetchAndSaveStockBasic();
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case TRADE_CAL -> {
                int ok = 0;
                for (ExchangeEnum ex : List.of(ExchangeEnum.SSE, ExchangeEnum.SZSE)) {
                    try {
                        tradeCalService.fetchAndSaveTradeCal(ex.getCode(), fullStart, today);
                        ok++;
                    } catch (Exception e) {
                        log.warn("Trade cal failed for {}: {}", ex.getCode(), e.getMessage());
                    }
                }
                updateTaskProgress(taskId, 100, "完成", 2, 2);
                return new StepStats(2, ok, 2 - ok);
            }
            case INDEX_WEIGHT -> {
                int success = 0;
                for (String code : INDEX_CODES) {
                    try {
                        indexWeightService.fetchAndSaveRange(code, indexWeightStart, today);
                        success++;
                    } catch (Exception e) {
                        log.warn("Index weight failed for {}: {}", code, e.getMessage());
                    }
                }
                updateTaskProgress(taskId, 100, "完成", INDEX_CODES.size(), INDEX_CODES.size());
                return new StepStats(INDEX_CODES.size(), success, INDEX_CODES.size() - success);
            }
            case SW_INDUSTRY -> {
                int swOk = 0;
                try {
                    swIndustryService.fetchAndSaveClassify("SWS2021");
                    swOk++;
                } catch (Exception e) {
                    log.warn("SW classify failed: {}", e.getMessage());
                }
                try {
                    swIndustryService.fetchAndSaveAllMembers("SWS2021");
                    swOk++;
                } catch (Exception e) {
                    log.warn("SW members failed: {}", e.getMessage());
                }
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return new StepStats(2, swOk, 2 - swOk);
            }
            case NAMECHANGE -> {
                stockNamechangeService.fetchAndSaveAll();
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case SUSPEND_D -> {
                stockSuspendDService.fetchAndSaveAll();
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case STK_LIMIT -> {
                stockStkLimitService.fetchAndSaveAll();
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case DAILY -> {
                Map<String, String> lastDateMap = isFull ? null : preloadLastDateMap(dailyQuoteMapper);
                return executePerStockStep(step, taskId, tsCode ->
                        dailyQuoteService.fetchAndSaveDailyQuotes(tsCode,
                                lastDateMap != null ? lastDateMap.get(tsCode) : null));
            }
            case ADJ_FACTOR -> {
                Map<String, String> lastDateMap = isFull ? null : preloadLastDateMap(adjFactorMapper);
                return executePerStockStep(step, taskId, tsCode ->
                        adjFactorService.fetchAndSaveAdjFactor(tsCode,
                                lastDateMap != null ? lastDateMap.get(tsCode) : null));
            }
            case DIVIDEND -> {
                return executePerStockStep(step, taskId, tsCode ->
                        dividendService.fetchAndSaveDividend(tsCode));
            }
            case INCOME -> {
                String start = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        incomeService.fetchAndSaveIncome(tsCode, start, today));
            }
            case BALANCESHEET -> {
                String start = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        balancesheetService.fetchAndSaveBalancesheet(tsCode, start, today));
            }
            case CASHFLOW -> {
                String start = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        cashflowService.fetchAndSaveCashflow(tsCode, start, today));
            }
            case FORECAST -> {
                String start = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        forecastService.fetchAndSaveForecast(tsCode, start, today));
            }
            case EXPRESS -> {
                String start = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        expressService.fetchAndSaveExpress(tsCode, start, today));
            }
            case DAILY_BASIC -> {
                basicDataService.fetchAndSaveDailyBasic(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case MONEYFLOW -> {
                moneyflowService.fetchAndSave(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case TOP_LIST -> {
                topListService.fetchAndSaveTopList(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case TOP_INST -> {
                topListService.fetchAndSaveTopInst(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case BLOCK_TRADE -> {
                blockTradeService.fetchAndSave(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case HK_HOLD -> {
                hkHoldService.fetchAndSave(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case MARGIN -> {
                marginService.fetchAndSaveMargin(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case MARGIN_DETAIL -> {
                marginService.fetchAndSaveMarginDetail(today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case FINA_INDICATOR -> {
                String startPeriod = isFull ? fullStart : LocalDate.now().minusYears(1).format(DATE_FMT);
                basicDataService.fetchAndSaveFinaIndicator(startPeriod, today);
                updateTaskProgress(taskId, 100, "完成", 1, 1);
                return StepStats.single();
            }
            case INDEX_DAILY -> {
                String start = isFull ? fullStart : today;
                List<String> codes = IndexConstants.DEFAULT_INDEX_CODES;
                int success = 0;
                int i = 0;
                for (String code : codes) {
                    try {
                        indexDailyFetchService.fetchAndSaveIndexDaily(code, start, today);
                        success++;
                    } catch (Exception e) {
                        log.warn("Index daily failed for {}: {}", code, e.getMessage());
                    }
                    i++;
                    updateTaskProgress(taskId, i * 100 / codes.size(),
                            step.getLabel() + " (" + i + "/" + codes.size() + ")", i, codes.size());
                }
                return new StepStats(codes.size(), success, codes.size() - success);
            }
        }
        return StepStats.empty();
    }

    @FunctionalInterface
    private interface TsCodeTask {
        void execute(String tsCode) throws Exception;
    }

    /** 单步骤执行统计 */
    private static class StepStats {
        long total;
        long success;
        long fail;

        StepStats() {}

        StepStats(long total, long success, long fail) {
            this.total = total;
            this.success = success;
            this.fail = fail;
        }

        static StepStats single() {
            return new StepStats(1, 1, 0);
        }

        static StepStats empty() {
            return new StepStats(0, 0, 0);
        }
    }

    private StepStats executePerStockStep(InitStep step, String taskId, TsCodeTask task) {
        List<StockBasicDTO> stocks = resolveStockListForSingleStep();
        int total = stocks.size();
        if (total == 0) {
            updateTaskProgress(taskId, 100, "完成（无股票）", 0, 0);
            return StepStats.empty();
        }

        int concurrency = Math.min(20, Math.max(4, total / 50));
        ExecutorService executor = Executors.newFixedThreadPool(
                concurrency, Thread.ofVirtual().name("data-init-", 0).factory());
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        List<CompletableFuture<Void>> futures = new ArrayList<>(total);

        for (StockBasicDTO stock : stocks) {
            String tsCode = stock.getTsCode();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (cancelled.get() || taskProgressCache.isCancelled(taskId)) {
                    cancelled.set(true);
                    return;
                }
                try {
                    task.execute(tsCode);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.warn("Failed for {}: {}", tsCode, e.getMessage());
                }
                int done = processed.incrementAndGet();
                if (done % 100 == 0 || done == total) {
                    updateTaskProgress(taskId, done * 100 / total,
                            step.getLabel() + " (" + done + "/" + total + ")",
                            done, total);
                }
            }, executor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("executePerStockStep error for {}", step.getCode(), e);
        } finally {
            executor.shutdown();
        }

        int done = processed.get();
        updateTaskProgress(taskId, 100, "完成", done, total);
        log.info("{} completed: success={}, fail={}, total={}",
                step.getLabel(), successCount.get(), failCount.get(), total);
        return new StepStats(total, successCount.get(), failCount.get());
    }

    private void createPullLog(String taskId, InitStep step, String operationType, String operator) {
        DataPullLogDO logEntry = DataPullLogDO.builder()
                .taskId(taskId)
                .tableCode(step.getCode())
                .tableName(step.getLabel())
                .operationType(operationType)
                .status("RUNNING")
                .startTime(LocalDateTime.now().format(DATETIME_FMT))
                .operator(operator)
                .build();
        dataPullLogMapper.insert(logEntry);
    }

    private void finishPullLog(String taskId, String status, long startMs,
                              String errorMessage, String errorStack, StepStats stats) {
        long durationMs = System.currentTimeMillis() - startMs;
        Long total = stats != null && stats.total > 0 ? stats.total : null;
        Long success = stats != null ? stats.success : null;
        Long fail = stats != null ? stats.fail : null;
        dataPullLogMapper.updateStatus(taskId, status,
                LocalDateTime.now().format(DATETIME_FMT), durationMs,
                total, success, fail, errorMessage, errorStack);
    }

    private void runQualityCheck(InitStep step) {
        try {
            dataGovernanceService.checkTable(step.getCode());
        } catch (Exception e) {
            log.warn("Quality check failed for {}: {}", step.getCode(), e.getMessage());
        }
    }

    private void putInitialProgress(String taskId, String tableCode) {
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(tableCode)
                .progressPct(0)
                .currentStep("准备中")
                .processedItems(0)
                .totalItems(0)
                .cancelled(false)
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    private void updateTaskProgress(String taskId, int progressPct, String currentStep,
                                    long processed, long total) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(existing != null ? existing.getTableCode() : null)
                .progressPct(progressPct)
                .currentStep(currentStep)
                .processedItems(processed)
                .totalItems(total)
                .cancelled(existing != null && existing.isCancelled())
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
        taskProgressCache.heartbeat(taskId);
    }

    private void rebuildTable(InitStep step) {
        String table = step.getTableName();
        truncateTable(table);
        if (step == InitStep.SW_INDUSTRY) {
            truncateTable("sw_industry_member");
        }
    }

    private void truncateTable(String table) {
        if ("sqlite".equalsIgnoreCase(dbType)) {
            jdbcTemplate.execute("DELETE FROM " + table);
            try {
                jdbcTemplate.execute("DELETE FROM sqlite_sequence WHERE name = '" + table + "'");
            } catch (Exception e) {
                log.debug("sqlite_sequence not updated for {}: {}", table, e.getMessage());
            }
        } else {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        log.info("Truncated table: {}", table);
    }

    private List<StockBasicDTO> resolveStockListForSingleStep() {
        List<StockBasicDTO> local = stockBasicService.queryLocal(
                null, null, null, ListStatusEnum.LISTED.getCode());
        if (local.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "本地无在市股票基础信息，请先初始化 stock_basic 步骤");
        }
        return local;
    }

    /**
     * 预加载所有股票的最新交易日期，用于增量更新时避免 N+1 查询。
     *
     * @param mapper 对应的 Mapper，需实现 selectLatestDatePerStock 方法
     * @return ts_code -&gt; latest_date (yyyyMMdd) 的映射
     */
    private Map<String, String> preloadLastDateMap(
            java.util.function.Supplier<List<Map<String, Object>>> mapperSupplier) {
        List<Map<String, Object>> rows = mapperSupplier.get();
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            String tsCode = (String) row.get("ts_code");
            String latestDate = (String) row.get("latest_date");
            if (tsCode != null && latestDate != null) {
                result.put(tsCode, latestDate);
            }
        }
        return result;
    }

    /**
     * 适配 DailyQuoteMapper 的 preloadLastDateMap 重载
     */
    private Map<String, String> preloadLastDateMap(DailyQuoteMapper mapper) {
        return preloadLastDateMap(mapper::selectLatestDatePerStock);
    }

    /**
     * 适配 AdjFactorMapper 的 preloadLastDateMap 重载
     */
    private Map<String, String> preloadLastDateMap(AdjFactorMapper mapper) {
        return preloadLastDateMap(mapper::selectLatestDatePerStock);
    }

    private static String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static final List<String> INDEX_CODES = List.of(
            "000300.SH", "000905.SH", "000016.SH", "000852.SH");
}
