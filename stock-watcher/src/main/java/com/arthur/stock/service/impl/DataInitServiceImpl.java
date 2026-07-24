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
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.StockStkLimitMapper;
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
    private final IndexDailyMapper indexDailyMapper;
    private final StockStkLimitMapper stockStkLimitMapper;
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
        } catch (Exception e) {
            log.error("Incremental update failed: {} (taskId={})", step.getLabel(), taskId, e);
            finishPullLog(taskId, "FAILED", startMs,
                    SensitiveDataUtil.mask(e.getMessage()),
                    SensitiveDataUtil.mask(getStackTrace(e)), stats);
            updateTaskFailed(taskId, SensitiveDataUtil.mask(e.getMessage()));
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private void doFullRebuild(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        StepStats stats = StepStats.empty();
        try {
            updateTaskRunning(taskId, "重建表: " + step.getLabel());
            rebuildTable(step);

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
        } catch (Exception e) {
            log.error("Full rebuild failed: {} (taskId={})", step.getLabel(), taskId, e);
            finishPullLog(taskId, "FAILED", startMs,
                    SensitiveDataUtil.mask(e.getMessage()),
                    SensitiveDataUtil.mask(getStackTrace(e)), stats);
            updateTaskFailed(taskId, SensitiveDataUtil.mask(e.getMessage()));
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
                Map<String, String> lastDateMap = isFull ? Collections.emptyMap() :
                        preloadLastDateMap(indexWeightMapper::selectMaxTradeDatePerIndex);
                for (String code : INDEX_CODES) {
                    try {
                        String start = isFull ? indexWeightStart :
                                lastDateMap.getOrDefault(code, indexWeightStart);
                        indexWeightService.fetchAndSaveRange(code, start, today);
                        success++;
                    } catch (Exception e) {
                        log.warn("Index weight failed for {}: {}", code, e.getMessage());
                    }
                }
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
                return new StepStats(2, swOk, 2 - swOk);
            }
            case NAMECHANGE -> {
                if (isFull) {
                    stockNamechangeService.fetchAndSaveAll();
                    return StepStats.single();
                }
                // Incremental: iterate trade dates from MAX(start_date) to today
                String maxDate = stockNamechangeMapper.selectMaxStartDate();
                String startDate = maxDate != null ? maxDate : LocalDate.now().minusYears(30).format(DATE_FMT);
                List<String> tradeDates = queryTradeDates(startDate, today);
                int success = 0;
                int fail = 0;
                for (String tradeDate : tradeDates) {
                    if (taskProgressCache.isCancelled(taskId)) break;
                    try {
                        stockNamechangeService.fetchAndSaveIncremental(tradeDate);
                        success++;
                    } catch (Exception e) {
                        fail++;
                        log.warn("Namechange failed for date {}: {}", tradeDate, e.getMessage());
                    }
                }
                return new StepStats(tradeDates.size(), success, fail);
            }
            case SUSPEND_D -> {
                if (isFull) {
                    stockSuspendDService.fetchAndSaveAll();
                    return StepStats.single();
                }
                String maxDate = stockSuspendDMapper.selectMaxTradeDate();
                String startDate = maxDate != null ? maxDate : LocalDate.now().minusYears(30).format(DATE_FMT);
                List<String> tradeDates = queryTradeDates(startDate, today);
                int success = 0;
                int fail = 0;
                for (String tradeDate : tradeDates) {
                    if (taskProgressCache.isCancelled(taskId)) break;
                    try {
                        stockSuspendDService.fetchAndSaveIncremental(tradeDate);
                        success++;
                    } catch (Exception e) {
                        fail++;
                        log.warn("Suspend_d failed for date {}: {}", tradeDate, e.getMessage());
                    }
                }
                return new StepStats(tradeDates.size(), success, fail);
            }
            case STK_LIMIT -> {
                if (isFull) {
                    stockStkLimitService.fetchAndSaveAll();
                    return StepStats.single();
                }
                Map<String, String> lastDateMap = preloadLastDateMap(stockStkLimitMapper::selectLatestDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        stockStkLimitService.fetchAndSaveByRange(tsCode,
                                lastDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(30).format(DATE_FMT)),
                                today));
            }
            case DIVIDEND -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            dividendService.fetchAndSaveDividend(tsCode));
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(dividendMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        dividendService.fetchAndSaveDividendByRange(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, LocalDate.now().minusYears(30).format(DATE_FMT)),
                                today));
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
                List<String> codes = IndexConstants.DEFAULT_INDEX_CODES;
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

    private void rebuildTable(InitStep step) {
        // D 类日频快照表不 truncate，改为逐日拉取覆盖（防止数据丢失）
        if (DAILY_SNAPSHOT_STEPS.contains(step)) {
            log.info("Skipping truncate for daily snapshot table: {}", step.getTableName());
            return;
        }
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
            if (latestDate == null) {
                latestDate = (String) row.get("latest_ann_date");
            }
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

    /** D 类日频快照表的日期范围补全：从 MAX(trade_date) 或回溯期开始，逐日拉取 */
    private StepStats executeDailySnapshotStep(InitStep step, String taskId, boolean isFull,
                                               java.util.function.Consumer<String> fetchFn) {
        String today = LocalDate.now().format(DATE_FMT);
        String startDate;
        if (isFull) {
            startDate = LocalDate.now().minusYears(3).format(DATE_FMT);
        } else {
            String maxDate = queryMaxTradeDate(step.getTableName());
            startDate = maxDate != null ? maxDate : LocalDate.now().minusYears(3).format(DATE_FMT);
        }

        List<String> tradeDates = queryTradeDates(startDate, today);
        if (tradeDates.isEmpty()) {
            log.warn("No trade dates between {} and {} for {}", startDate, today, step.getLabel());
            return StepStats.empty();
        }

        int success = 0;
        int fail = 0;
        for (String tradeDate : tradeDates) {
            if (taskProgressCache.isCancelled(taskId)) {
                log.info("Task cancelled during {} date iteration at {}", step.getLabel(), tradeDate);
                break;
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
                step.getLabel(), success, fail, tradeDates.size());
        return new StepStats(tradeDates.size(), success, fail);
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

    private static final List<String> INDEX_CODES = List.of(
            "000300.SH", "000905.SH", "000016.SH", "000852.SH");

    /** D 类日频快照表：全量重建时不应 truncate，改为逐日拉取 */
    private static final Set<InitStep> DAILY_SNAPSHOT_STEPS = Set.of(
            InitStep.DAILY_BASIC, InitStep.MONEYFLOW, InitStep.TOP_LIST, InitStep.TOP_INST,
            InitStep.BLOCK_TRADE, InitStep.HK_HOLD, InitStep.MARGIN, InitStep.MARGIN_DETAIL
    );
}
