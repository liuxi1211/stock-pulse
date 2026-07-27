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
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.DividendMapper;
import com.arthur.stock.mapper.StockNamechangeMapper;
import com.arthur.stock.mapper.StockSuspendDMapper;
import com.arthur.stock.mapper.IncomeMapper;
import com.arthur.stock.mapper.BalancesheetMapper;
import com.arthur.stock.mapper.CashflowMapper;
import com.arthur.stock.mapper.ForecastMapper;
import com.arthur.stock.mapper.ExpressMapper;
import com.arthur.stock.mapper.FinaIndicatorMapper;
import com.arthur.stock.mapper.TradeCalMapper;
import com.arthur.stock.mapper.IndexWeightMapper;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.service.*;
import com.arthur.stock.util.SensitiveDataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A 股市场起始日期（1990-12-19 上交所开市），全量拉取的统一起始时间 */
    private static final String FULL_START_DATE = "19901219";

    /** I/O 密集型任务使用虚拟线程，避免占用 ForkJoinPool.commonPool */
    private static final Executor IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final JdbcTemplate jdbcTemplate;
    private final StockBasicService stockBasicService;
    private final TradeCalService tradeCalService;
    private final IndexWeightService indexWeightService;
    private final SwIndustryService swIndustryService;
    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final IndexDailyMapper indexDailyMapper;
    private final DividendMapper dividendMapper;
    private final StockNamechangeMapper stockNamechangeMapper;
    private final StockSuspendDMapper stockSuspendDMapper;
    private final IncomeMapper incomeMapper;
    private final BalancesheetMapper balancesheetMapper;
    private final CashflowMapper cashflowMapper;
    private final ForecastMapper forecastMapper;
    private final ExpressMapper expressMapper;
    private final FinaIndicatorMapper finaIndicatorMapper;
    private final TradeCalMapper tradeCalMapper;
    private final IndexWeightMapper indexWeightMapper;
    private final DividendService dividendService;
    private final StockNamechangeService stockNamechangeService;
    private final StockSuspendDService stockSuspendDService;
    private final StockStkLimitService stockStkLimitService;
    private final IncomeService incomeService;
    private final BalancesheetService balancesheetService;
    private final CashflowService cashflowService;
    private final ForecastService forecastService;
    private final ExpressService expressService;
    private final FinaIndicatorService finaIndicatorService;
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
            updateTaskRunning(taskId, "增量拉取: " + step.getLabel());
            stats = executeSingleStep(step, taskId, false);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, "用户取消", null, stats);
                updateTaskCancelled(taskId, "用户取消");
                log.info("Incremental update cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, null, null, stats);
            runQualityCheck(step);
            updateTaskSuccess(taskId);
            log.info("Incremental update completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Throwable e) {
            log.error("Incremental update failed: {} (taskId={})", step.getLabel(), taskId, e);
            try {
                finishPullLog(taskId, "FAILED", startMs,
                        SensitiveDataUtil.mask(e.getMessage()),
                        SensitiveDataUtil.mask(getStackTrace(e)), stats);
            } catch (Throwable logEx) {
                log.error("Failed to write pull log for FAILED task", logEx);
            }
            updateTaskFailed(taskId, SensitiveDataUtil.mask(e.getMessage()));
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private void doFullRebuild(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        StepStats stats = StepStats.empty();
        try {
            // 非 D 类日频快照表：全量重建前清空表（D 类逐日覆盖，不 truncate 防数据丢失）
            if (!DAILY_SNAPSHOT_STEPS.contains(step)) {
                List<String> tables = collectRebuildTables(step);
                for (String table : tables) {
                    jdbcTemplate.execute("TRUNCATE TABLE " + table);
                    log.info("Truncated table: {}", table);
                }
            }

            updateTaskRunning(taskId, "全量拉取: " + step.getLabel());
            stats = executeSingleStep(step, taskId, true);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, "用户取消", null, stats);
                updateTaskCancelled(taskId, "用户取消");
                log.info("Full rebuild cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, null, null, stats);
            runQualityCheck(step);
            updateTaskSuccess(taskId);
            log.info("Full rebuild completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Throwable e) {
            log.error("Full rebuild failed: {} (taskId={})", step.getLabel(), taskId, e);
            try {
                finishPullLog(taskId, "FAILED", startMs,
                        SensitiveDataUtil.mask(e.getMessage()),
                        SensitiveDataUtil.mask(getStackTrace(e)), stats);
            } catch (Throwable logEx) {
                log.error("Failed to write pull log for FAILED task", logEx);
            }
            updateTaskFailed(taskId, SensitiveDataUtil.mask(e.getMessage()));
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private StepStats executeSingleStep(InitStep step, String taskId, boolean isFull) {
        String today = LocalDate.now().format(DATE_FMT);
        String fullStart = FULL_START_DATE;
        String indexWeightStart = LocalDate.now().minusYears(5).format(DATE_FMT);

        switch (step) {
            case STOCK_BASIC -> {
                stockBasicService.fetchAndSaveStockBasic();
                return StepStats.single();
            }
            case TRADE_CAL -> {
                String calStart;
                if (isFull) {
                    calStart = fullStart;
                } else {
                    String maxCalDate = tradeCalMapper.selectMaxCalDate();
                    calStart = maxCalDate != null ? maxCalDate : fullStart;
                }
                int ok = 0;
                for (ExchangeEnum ex : List.of(ExchangeEnum.SSE, ExchangeEnum.SZSE)) {
                    try {
                        tradeCalService.fetchAndSaveTradeCal(ex.getCode(), calStart, today);
                        ok++;
                    } catch (Exception e) {
                        log.warn("Trade cal failed for {}: {}", ex.getCode(), e.getMessage());
                    }
                }
                return new StepStats(2, ok, 2 - ok);
            }
            case INDEX_WEIGHT -> {
                int success = 0;
                List<String> codes = IndexConstants.INDEX_WEIGHT_CODES;
                Map<String, String> lastDateMap = isFull ? Collections.emptyMap() :
                        preloadLastDateMap(indexWeightMapper::selectMaxTradeDatePerIndex);
                for (String code : codes) {
                    try {
                        String start = isFull ? indexWeightStart :
                                lastDateMap.getOrDefault(code, indexWeightStart);
                        indexWeightService.fetchAndSaveRange(code, start, today);
                        success++;
                    } catch (Exception e) {
                        log.warn("Index weight failed for {}: {}", code, e.getMessage());
                    }
                }
                return new StepStats(codes.size(), success, codes.size() - success);
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
                return new StepStats(2, swOk, 2 - swOk);
            }
            case NAMECHANGE -> {
                if (isFull) {
                    stockNamechangeService.fetchAndSaveAll();
                    return StepStats.single();
                }
                // 更名是稀疏事件（非每个交易日都有更名），按日期区间 + 分页 5000 一次性拉取，
                // 替代按交易日逐日拉取（会产生大量空请求，受 Tushare 限流，耗时极长）
                String maxDate = stockNamechangeMapper.selectMaxStartDate();
                String startDate = maxDate != null ? maxDate : FULL_START_DATE;
                stockNamechangeService.fetchAndSaveByRange(startDate, today);
                return StepStats.single();
            }
            case SUSPEND_D -> {
                if (isFull) {
                    stockSuspendDService.fetchAndSaveAll();
                    return StepStats.single();
                }
                // 停复牌事件稀疏（非每个交易日都有事件），按日期区间 + 分页 5000 一次性拉取，
                // 替代按交易日逐日拉取（会产生大量空请求，受 Tushare 限流，耗时极长）
                String maxDate = stockSuspendDMapper.selectMaxTradeDate();
                String startDate = maxDate != null ? maxDate : FULL_START_DATE;
                stockSuspendDService.fetchAndSaveByRange(startDate, today);
                return StepStats.single();
            }
            case STK_LIMIT -> {
                // 按月迭代拉取全市场涨跌停价，每月一次 start_date/end_date 范围查询 + offset/limit 分页 5000。
                // 涨跌停价每日数据稀疏（非每只股票每天都有），按日查询会产生大量空请求，
                // 改为按月聚合查询大幅减少 API 调用次数。
                // 全量：从 19901219 起按月迭代；增量：从 MAX(trade_date) 起按月补充。
                // 按日期范围查询 + upsert（先删后插），即使中途失败，下次仍从同一月份继续，不丢数据。
                return executeMonthlySnapshotStep(step, taskId, isFull, FULL_START_DATE,
                        (start, end) -> stockStkLimitService.fetchAndSaveByRange(start, end));
            }
            case DIVIDEND -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            dividendService.fetchAndSaveDividend(tsCode));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(dividendMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        dividendService.fetchAndSaveDividendByRange(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, FULL_START_DATE),
                                today));
            }
            case DAILY -> {
                // 按交易日迭代拉取全市场行情（每日约 5000 只股票，1-2 页完成），
                // 替代原按股票逐一拉取（5000+ 次 API 调用）。
                // 全量：从 19901219 起逐日拉取；增量：从全局 MAX(trade_date) 起逐日补充。
                // 增量按日期查询 + upsert（先删后插），即使中途失败，下次仍从同一日期继续，不丢数据。
                return executeDailySnapshotStep(step, taskId, isFull, FULL_START_DATE,
                        date -> dailyQuoteService.fetchAndSaveByTradeDate(date));
            }
            case ADJ_FACTOR -> {
                // 按交易日迭代拉取全市场复权因子（每日约 5000 只股票，1-2 页完成），
                // 替代原按 10 天窗口范围拉取。
                // 全量：从 19901219 起逐日拉取；增量：从全局 MAX(trade_date) 起逐日补充。
                // 按日期查询 + upsert（先删后插），即使中途失败，下次仍从同一日期继续，不丢数据。
                return executeDailySnapshotStep(step, taskId, isFull, FULL_START_DATE,
                        date -> adjFactorService.fetchAndSaveByTradeDate(date));
            }
            case INCOME -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            incomeService.fetchAndSaveIncome(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(incomeMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        incomeService.fetchAndSaveIncome(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case BALANCESHEET -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            balancesheetService.fetchAndSaveBalancesheet(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(balancesheetMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        balancesheetService.fetchAndSaveBalancesheet(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case CASHFLOW -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            cashflowService.fetchAndSaveCashflow(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(cashflowMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        cashflowService.fetchAndSaveCashflow(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case FORECAST -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            forecastService.fetchAndSaveForecast(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(forecastMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        forecastService.fetchAndSaveForecast(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case EXPRESS -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            expressService.fetchAndSaveExpress(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(expressMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        expressService.fetchAndSaveExpress(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case DAILY_BASIC -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> basicDataService.fetchAndSaveDailyBasic(date));
            }
            case MONEYFLOW -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> moneyflowService.fetchAndSave(date));
            }
            case TOP_LIST -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> topListService.fetchAndSaveTopList(date));
            }
            case TOP_INST -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> topListService.fetchAndSaveTopInst(date));
            }
            case BLOCK_TRADE -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> blockTradeService.fetchAndSave(date));
            }
            case HK_HOLD -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> hkHoldService.fetchAndSave(date));
            }
            case MARGIN -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> marginService.fetchAndSaveMargin(date));
            }
            case MARGIN_DETAIL -> {
                return executeDailySnapshotStep(step, taskId, isFull,
                        date -> marginService.fetchAndSaveMarginDetail(date));
            }
            case FINA_INDICATOR -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            finaIndicatorService.fetchAndSaveFinaIndicator(tsCode, fullStart, today));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(finaIndicatorMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        finaIndicatorService.fetchAndSaveFinaIndicator(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(1).format(DATE_FMT)),
                                today));
            }
            case INDEX_DAILY -> {
                List<String> codes = IndexConstants.CORE_BROAD_INDEX_CODES;
                if (isFull) {
                    int success = 0;
                    for (String code : codes) {
                        try {
                            indexDailyFetchService.fetchAndSaveIndexDaily(code, fullStart, today);
                            success++;
                        } catch (Exception e) {
                            log.warn("Index daily failed for {}: {}", code, e.getMessage());
                        }
                    }
                    return new StepStats(codes.size(), success, codes.size() - success);
                } else {
                    Map<String, String> lastDateMap = preloadLastDateMap(indexDailyMapper::selectMaxTradeDatePerIndex);
                    int success = 0;
                    for (String code : codes) {
                        try {
                            String lastDate = lastDateMap.get(code);
                            String start = lastDate != null ? lastDate : fullStart;
                            indexDailyFetchService.fetchAndSaveIndexDaily(code, start, today);
                            success++;
                        } catch (Exception e) {
                            log.warn("Index daily failed for {}: {}", code, e.getMessage());
                        }
                    }
                    return new StepStats(codes.size(), success, codes.size() - success);
                }
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
            return StepStats.empty();
        }

        int concurrency = Math.min(20, Math.max(4, total / 50));
        ExecutorService executor = Executors.newFixedThreadPool(
                concurrency, Thread.ofVirtual().name("data-init-", 0).factory());
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

        int success = successCount.get();
        int fail = failCount.get();
        log.info("{} completed: success={}, fail={}, total={}", step.getLabel(), success, fail, total);

        // 全部失败时抛异常，让外层 catch 将任务标记为 FAILED，避免前端持续显示「执行中」
        if (success == 0 && fail > 0) {
            throw new RuntimeException(String.format(
                    "%s: 全部 %d 只股票拉取失败", step.getLabel(), fail));
        }
        return new StepStats(total, success, fail);
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
                .status("RUNNING")
                .currentStep("准备中")
                .errorMessage(null)
                .cancelled(false)
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    private void updateTaskRunning(String taskId, String currentStep) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(existing != null ? existing.getTableCode() : null)
                .status("RUNNING")
                .currentStep(currentStep)
                .errorMessage(null)
                .cancelled(existing != null && existing.isCancelled())
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
        taskProgressCache.heartbeat(taskId);
    }

    private void updateTaskSuccess(String taskId) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(existing != null ? existing.getTableCode() : null)
                .status("SUCCESS")
                .currentStep("完成")
                .errorMessage(null)
                .cancelled(false)
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    private void updateTaskFailed(String taskId, String errorMessage) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(existing != null ? existing.getTableCode() : null)
                .status("FAILED")
                .currentStep("失败")
                .errorMessage(errorMessage)
                .cancelled(false)
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    private void updateTaskCancelled(String taskId, String reason) {
        TaskProgress existing = taskProgressCache.getProgress(taskId);
        TaskProgress progress = TaskProgress.builder()
                .taskId(taskId)
                .tableCode(existing != null ? existing.getTableCode() : null)
                .status("CANCELLED")
                .currentStep("已取消")
                .errorMessage(reason)
                .cancelled(true)
                .lastUpdated(LocalDateTime.now().format(DATETIME_FMT))
                .build();
        taskProgressCache.putProgress(taskId, progress);
    }

    /** 全量重建需要处理的表列表（SW_INDUSTRY 额外包含成员表） */
    private List<String> collectRebuildTables(InitStep step) {
        List<String> tables = new ArrayList<>();
        tables.add(step.getTableName());
        if (step == InitStep.SW_INDUSTRY) {
            tables.add("sw_industry_member");
        }
        return tables;
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
            if (latestDate == null) {
                latestDate = (String) row.get("latest_ann_date");
            }
            if (tsCode != null && latestDate != null) {
                result.put(tsCode, latestDate);
            }
        }
        return result;
    }

    /** D 类日频快照表的日期范围补全：从 MAX(trade_date) 或回溯期开始，逐日拉取 */
    private StepStats executeDailySnapshotStep(InitStep step, String taskId, boolean isFull,
                                               java.util.function.Consumer<String> fetchFn) {
        return executeDailySnapshotStep(step, taskId, isFull,
                LocalDate.now().minusYears(3).format(DATE_FMT), fetchFn);
    }

    /**
     * D 类日频快照表的日期范围补全（自定义全量起始日期）。
     * <p>
     * 全量更新使用传入的 {@code fullStartDate} 作为起始日期（如日线行情从 19901219 起）；
     * 增量更新从 {@code MAX(trade_date)} 起逐日补充。按日期查询 + upsert 语义，
     * 即使中途失败，下次增量仍从同一日期继续，不丢数据。
     *
     * @param step           步骤枚举
     * @param taskId         任务 ID
     * @param isFull         是否全量更新
     * @param fullStartDate  全量更新的起始日期（yyyyMMdd）
     * @param fetchFn        每个交易日的拉取函数
     */
    private StepStats executeDailySnapshotStep(InitStep step, String taskId, boolean isFull,
                                               String fullStartDate,
                                               java.util.function.Consumer<String> fetchFn) {
        String today = LocalDate.now().format(DATE_FMT);
        String startDate;
        if (isFull) {
            startDate = fullStartDate;
        } else {
            String maxDate = queryMaxTradeDate(step.getTableName());
            startDate = maxDate != null ? maxDate : fullStartDate;
        }

        List<String> tradeDates = queryTradeDates(startDate, today);
        if (tradeDates.isEmpty()) {
            log.warn("No trade dates between {} and {} for {}", startDate, today, step.getLabel());
            return StepStats.empty();
        }

        int total = tradeDates.size();
        int success = 0;
        int fail = 0;
        for (int i = 0; i < tradeDates.size(); i++) {
            String tradeDate = tradeDates.get(i);
            if (taskProgressCache.isCancelled(taskId)) {
                log.info("Task cancelled during {} date iteration at {}", step.getLabel(), tradeDate);
                break;
            }
            // 每 20 个交易日刷新一次进度缓存（续期 30 分钟 TTL），避免长任务缓存过期导致前端 404
            if (i > 0 && i % 20 == 0) {
                updateTaskRunning(taskId, step.getLabel() + ": " + i + "/" + total);
            }
            try {
                fetchFn.accept(tradeDate);
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("{} failed for date {}: {}", step.getLabel(), tradeDate, e.getMessage());
            }
        }
        log.info("{} completed: success={}, fail={}, total dates={}",
                step.getLabel(), success, fail, total);

        // 全部失败时抛异常，让外层 catch 将任务标记为 FAILED
        if (success == 0 && fail > 0) {
            throw new RuntimeException(String.format(
                    "%s: 全部 %d 个交易日拉取失败", step.getLabel(), fail));
        }
        return new StepStats(total, success, fail);
    }

    /**
     * 按月迭代拉取数据（适合每日数据稀疏的表，如涨跌停价）。
     * <p>
     * 将 [startDate, today] 按自然月切分为若干区间，每月一次范围查询 + 分页落库。
     * 全量使用传入的 {@code fullStartDate}；增量从 {@code MAX(trade_date)} 起。
     * 按日期范围查询 + upsert 语义，即使中途失败，下次增量仍从同一月份继续，不丢数据。
     *
     * @param step          步骤枚举
     * @param taskId        任务 ID
     * @param isFull        是否全量更新
     * @param fullStartDate 全量更新的起始日期（yyyyMMdd）
     * @param fetchFn       每个月份区间的拉取函数 (startDate, endDate)
     */
    private StepStats executeMonthlySnapshotStep(InitStep step, String taskId, boolean isFull,
                                                 String fullStartDate,
                                                 java.util.function.BiConsumer<String, String> fetchFn) {
        String today = LocalDate.now().format(DATE_FMT);
        String startDate;
        if (isFull) {
            startDate = fullStartDate;
        } else {
            String maxDate = queryMaxTradeDate(step.getTableName());
            startDate = maxDate != null ? maxDate : fullStartDate;
        }

        List<String[]> monthRanges = splitByMonth(startDate, today);
        if (monthRanges.isEmpty()) {
            log.warn("No month ranges between {} and {} for {}", startDate, today, step.getLabel());
            return StepStats.empty();
        }

        int total = monthRanges.size();
        int success = 0;
        int fail = 0;
        for (int i = 0; i < monthRanges.size(); i++) {
            String[] range = monthRanges.get(i);
            if (taskProgressCache.isCancelled(taskId)) {
                log.info("Task cancelled during {} month iteration at {}~{}", step.getLabel(), range[0], range[1]);
                break;
            }
            // 每 20 个月刷新一次进度缓存（续期 30 分钟 TTL），避免长任务缓存过期导致前端 404
            if (i > 0 && i % 20 == 0) {
                updateTaskRunning(taskId, step.getLabel() + ": " + i + "/" + total);
            }
            try {
                fetchFn.accept(range[0], range[1]);
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("{} failed for month {}~{}: {}", step.getLabel(), range[0], range[1], e.getMessage());
            }
        }
        log.info("{} completed: success={}, fail={}, total months={}",
                step.getLabel(), success, fail, total);

        // 全部失败时抛异常，让外层 catch 将任务标记为 FAILED
        if (success == 0 && fail > 0) {
            throw new RuntimeException(String.format(
                    "%s: 全部 %d 个月拉取失败", step.getLabel(), fail));
        }
        return new StepStats(total, success, fail);
    }

    /**
     * 将 [startDate, endDate] 按自然月切分为若干区间。
     * <p>
     * 每个区间为 [月初或startDate, 月末或endDate]，确保覆盖完整日期范围且不重叠。
     * 例如 startDate=19901219, endDate=20260727 → [19901219,19901231], [19910101,19910131], ..., [20260701,20260727]
     *
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 每个元素为 String[]{月初yyyyMMdd, 月末yyyyMMdd}
     */
    private List<String[]> splitByMonth(String startDate, String endDate) {
        List<String[]> result = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        if (start.isAfter(end)) {
            return result;
        }
        LocalDate monthStart = start;
        while (!monthStart.isAfter(end)) {
            LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
            if (monthEnd.isAfter(end)) {
                monthEnd = end;
            }
            result.add(new String[]{
                    monthStart.format(DATE_FMT),
                    monthEnd.format(DATE_FMT)
            });
            monthStart = monthEnd.plusDays(1);
        }
        return result;
    }

    private String queryMaxTradeDate(String tableName) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(trade_date) FROM " + tableName, String.class);
        } catch (Exception e) {
            log.warn("Failed to query MAX(trade_date) from {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private List<String> queryTradeDates(String startDate, String endDate) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT cal_date FROM trade_cal WHERE cal_date >= ? AND cal_date <= ? AND is_open = '1' ORDER BY cal_date",
                    String.class, startDate, endDate);
        } catch (Exception e) {
            log.warn("Failed to query trade dates: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String queryMaxAnnDate(String tableName) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(ann_date) FROM " + tableName, String.class);
        } catch (Exception e) {
            log.warn("Failed to query MAX(ann_date) from {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private static String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** D 类日频快照表：全量重建时不应 truncate，改为逐日拉取 */
    private static final Set<InitStep> DAILY_SNAPSHOT_STEPS = Set.of(
            InitStep.DAILY_BASIC, InitStep.MONEYFLOW, InitStep.TOP_LIST, InitStep.TOP_INST,
            InitStep.BLOCK_TRADE, InitStep.HK_HOLD, InitStep.MARGIN, InitStep.MARGIN_DETAIL
    );
}
