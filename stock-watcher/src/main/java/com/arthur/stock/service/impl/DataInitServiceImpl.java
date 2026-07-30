package com.arthur.stock.service.impl;

import com.arthur.stock.cache.TaskProgressCache;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.IndexConstants;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.SwIndustryConstants;
import com.arthur.stock.dto.governance.TaskProgress;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.mapper.IndexDailyMapper;
import com.arthur.stock.mapper.IndexBasicMapper;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 数据拉取服务实现，负责单表增量更新和全量重建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataInitServiceImpl implements DataInitService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;
    private static final String SCHEDULED_OPERATOR = "SYSTEM";

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
    private final IndexBasicMapper indexBasicMapper;
    private final IndexBasicService indexBasicService;
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
    private final StkHoldertradeService stkHoldertradeService;
    private final StkHoldernumberService stkHoldernumberService;
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
    private final CacheManager cacheManager;

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

    @Override
    public void scheduledIncrementalBatch(String batchName, List<InitStep> steps) {
        executeScheduledBatch(batchName, steps, false);
    }

    @Override
    public void scheduledFullUpdate(String batchName, InitStep step) {
        executeScheduledBatch(batchName, List.of(step), true);
    }

    private void executeScheduledBatch(String batchName, List<InitStep> steps, boolean full) {
        if (!taskProgressCache.tryAcquireLock()) {
            log.warn("Scheduled Tushare batch skipped because another pull is running: {}", batchName);
            return;
        }
        InitStep failedStep = null;
        try {
            log.info("Scheduled Tushare batch started: {}, steps={}", batchName, steps);
            for (InitStep step : steps) {
                if (!executeScheduledStep(step, full)) {
                    failedStep = step;
                    break;
                }
            }
        } finally {
            taskProgressCache.releaseLock();
        }
        if (failedStep == null) {
            log.info("Scheduled Tushare batch completed: {}", batchName);
        } else {
            log.error("Scheduled Tushare batch stopped after step failure: {}, failedStep={}",
                    batchName, failedStep.getCode());
        }
    }

    private boolean executeScheduledStep(InitStep step, boolean full) {
        String taskId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();
        long totalCount = 0L;
        createPullLog(taskId, step, "SCHEDULED", SCHEDULED_OPERATOR);
        try {
            if (full && !DAILY_SNAPSHOT_STEPS.contains(step)) {
                for (String table : collectRebuildTables(step)) {
                    jdbcTemplate.execute("TRUNCATE TABLE " + table);
                }
            }
            totalCount = executeSingleStep(step, taskId, full);
            finishPullLog(taskId, "SUCCESS", startMs, totalCount, null);
            clearCachesAfterSuccess(step);
            runQualityCheck(step);
            return true;
        } catch (Throwable e) {
            totalCount = completedCount(e, totalCount);
            String errorMessage = summarizeError(e);
            log.error("Scheduled Tushare step failed: {} (taskId={})", step.getCode(), taskId, e);
            try {
                finishPullLog(taskId, "FAILED", startMs, totalCount, errorMessage);
            } catch (Throwable logEx) {
                log.error("Failed to write pull log for scheduled FAILED task: {}", step.getCode(), logEx);
            }
            return false;
        }
    }

    private void doIncrementalUpdate(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        long totalCount = 0L;
        try {
            updateTaskRunning(taskId, "增量拉取: " + step.getLabel());
            totalCount = executeSingleStep(step, taskId, false);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, totalCount, "用户取消");
                updateTaskCancelled(taskId, "用户取消");
                log.info("Incremental update cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, totalCount, null);
            clearCachesAfterSuccess(step);
            runQualityCheck(step);
            updateTaskSuccess(taskId);
            log.info("Incremental update completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Throwable e) {
            totalCount = completedCount(e, totalCount);
            log.error("Incremental update failed: {} (taskId={})", step.getLabel(), taskId, e);
            String errorMessage = summarizeError(e);
            try {
                finishPullLog(taskId, "FAILED", startMs, totalCount, errorMessage);
            } catch (Throwable logEx) {
                log.error("Failed to write pull log for FAILED task", logEx);
            }
            updateTaskFailed(taskId, errorMessage);
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private void doFullRebuild(InitStep step, String taskId) {
        long startMs = System.currentTimeMillis();
        long totalCount = 0L;
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
            totalCount = executeSingleStep(step, taskId, true);

            if (taskProgressCache.isCancelled(taskId)) {
                finishPullLog(taskId, "CANCELLED", startMs, totalCount, "用户取消");
                updateTaskCancelled(taskId, "用户取消");
                log.info("Full rebuild cancelled: {} (taskId={})", step.getLabel(), taskId);
                return;
            }
            finishPullLog(taskId, "SUCCESS", startMs, totalCount, null);
            clearCachesAfterSuccess(step);
            runQualityCheck(step);
            updateTaskSuccess(taskId);
            log.info("Full rebuild completed: {} (taskId={})", step.getLabel(), taskId);
        } catch (Throwable e) {
            totalCount = completedCount(e, totalCount);
            log.error("Full rebuild failed: {} (taskId={})", step.getLabel(), taskId, e);
            String errorMessage = summarizeError(e);
            try {
                finishPullLog(taskId, "FAILED", startMs, totalCount, errorMessage);
            } catch (Throwable logEx) {
                log.error("Failed to write pull log for FAILED task", logEx);
            }
            updateTaskFailed(taskId, errorMessage);
        } finally {
            taskProgressCache.releaseLock();
        }
    }

    private long executeSingleStep(InitStep step, String taskId, boolean isFull) {
        String today = LocalDate.now().format(DATE_FMT);
        String fullStart = FULL_START_DATE;
        String indexWeightStart = LocalDate.now().minusYears(5).format(DATE_FMT);

        switch (step) {
            case STOCK_BASIC -> {
                return stockBasicService.fetchAndSaveStockBasic().size();
            }
            case TRADE_CAL -> {
                String calStart;
                if (isFull) {
                    calStart = fullStart;
                } else {
                    String maxCalDate = tradeCalMapper.selectMaxCalDate();
                    calStart = maxCalDate != null ? maxCalDate : fullStart;
                }
                long updatedRows = 0L;
                for (ExchangeEnum ex : List.of(ExchangeEnum.SSE, ExchangeEnum.SZSE)) {
                    try {
                        updatedRows += tradeCalService.fetchAndSaveTradeCal(
                                ex.getCode(), calStart, today).size();
                    } catch (Exception e) {
                        throw new StepExecutionException(
                                step.getLabel() + " 拉取失败: " + ex.getCode(), updatedRows, e);
                    }
                }
                return updatedRows;
            }
            case INDEX_BASIC -> {
                int n = indexBasicService.fetchAndSaveAll();
                log.info("index_basic fetched and saved {} records", n);
                return n;
            }
            case INDEX_WEIGHT -> {
                // 指数代码来源：从 index_basic 表动态读取全部指数（取代写死的 INDEX_WEIGHT_CODES）
                List<String> codes = indexBasicMapper.selectAllTsCodes();
                if (codes.isEmpty()) {
                    log.warn("index_basic 表为空，请先初始化 INDEX_BASIC；回退到 INDEX_WEIGHT_CODES");
                    codes = IndexConstants.INDEX_WEIGHT_CODES;
                }
                long updatedRows = 0L;
                Map<String, String> lastDateMap = isFull ? Collections.emptyMap() :
                        preloadLastDateMap(indexWeightMapper::selectMaxTradeDatePerIndex);
                for (String code : codes) {
                    try {
                        String start = isFull ? indexWeightStart :
                                lastDateMap.getOrDefault(code, indexWeightStart);
                        updatedRows += indexWeightService.fetchAndSaveRange(code, start, today);
                    } catch (Exception e) {
                        throw new StepExecutionException(
                                step.getLabel() + " 拉取失败: " + code, updatedRows, e);
                    }
                }
                return updatedRows;
            }
            case SW_INDUSTRY -> {
                long updatedRows = swIndustryService.fetchAndSaveClassify(SwIndustryConstants.SW_SRC);
                try {
                    updatedRows += swIndustryService.fetchAndSaveAllMembers(SwIndustryConstants.SW_SRC);
                } catch (Exception e) {
                    throw new StepExecutionException(
                            step.getLabel() + "成员拉取失败", updatedRows, e);
                }
                return updatedRows;
            }
            case NAMECHANGE -> {
                if (isFull) {
                    return stockNamechangeService.fetchAndSaveAll();
                }
                // 更名是稀疏事件（非每个交易日都有更名），按日期区间 + 分页 5000 一次性拉取，
                // 替代按交易日逐日拉取（会产生大量空请求，受 Tushare 限流，耗时极长）
                String maxDate = stockNamechangeMapper.selectMaxStartDate();
                String startDate = maxDate != null ? maxDate : FULL_START_DATE;
                return stockNamechangeService.fetchAndSaveByRange(startDate, today);
            }
            case SUSPEND_D -> {
                if (isFull) {
                    return stockSuspendDService.fetchAndSaveAll();
                }
                // 停复牌事件稀疏（非每个交易日都有事件），按日期区间 + 分页 5000 一次性拉取，
                // 替代按交易日逐日拉取（会产生大量空请求，受 Tushare 限流，耗时极长）
                String maxDate = stockSuspendDMapper.selectMaxTradeDate();
                String startDate = maxDate != null ? maxDate : FULL_START_DATE;
                return stockSuspendDService.fetchAndSaveByRange(startDate, today);
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
            case STK_HOLDERTRADE -> {
                // 股东增减持：按股票逐一拉取，带公告日期范围。
                // 全量：从 19901219 起拉取全部历史；增量：从 MAX(ann_date) 起补充近一年。
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            stkHoldertradeService.fetchAndSave(tsCode, fullStart, today));
                }
                String maxAnnDate = queryMaxAnnDate(step.getTableName());
                String startDate = maxAnnDate != null ? maxAnnDate : LocalDate.now().minusYears(1).format(DATE_FMT);
                return executePerStockStep(step, taskId, tsCode ->
                        stkHoldertradeService.fetchAndSave(tsCode, startDate, today));
            }
            case STK_HOLDERNUMBER -> {
                // 股东人数：按股票逐一拉取全量历史（Tushare stk_holdernumber 接口不支持日期范围过滤）。
                // 全量和增量均为全量拉取，通过 upsert 幂等写入保证数据不重复。
                return executePerStockStep(step, taskId, tsCode ->
                        stkHoldernumberService.fetchAndSave(tsCode));
            }
            case DIVIDEND -> {
                if (isFull) {
                    return executePerStockStep(step, taskId, tsCode ->
                            dividendService.fetchAndSaveDividend(tsCode).size());
                }
                Map<String, String> lastAnnDateMap = preloadLastDateMap(dividendMapper::selectMaxAnnDatePerStock);
                return executePerStockStep(step, taskId, tsCode ->
                        dividendService.fetchAndSaveDividendByRange(tsCode,
                                lastAnnDateMap.getOrDefault(tsCode, FULL_START_DATE),
                                today).size());
            }
            case DAILY -> {
                // 按交易日迭代拉取全市场行情（每日约 5000 只股票，1-2 页完成），
                // 替代原按股票逐一拉取（5000+ 次 API 调用）。
                // 全量：从 19901219 起逐日拉取；增量：从全局 MAX(trade_date) 起逐日补充。
                // 增量按日期查询 + upsert（先删后插），即使中途失败，下次仍从同一日期继续，不丢数据。
                return executeDailySnapshotStep(step, taskId, isFull, FULL_START_DATE,
                        date -> dailyQuoteService.fetchAndSaveByTradeDate(date).size());
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
                // 指数代码来源：从 index_basic 表动态读取全部指数（取代写死的 CORE_BROAD_INDEX_CODES）
                List<String> codes = indexBasicMapper.selectAllTsCodes();
                if (codes.isEmpty()) {
                    log.warn("index_basic 表为空，请先初始化 INDEX_BASIC；回退到 CORE_BROAD_INDEX_CODES");
                    codes = IndexConstants.CORE_BROAD_INDEX_CODES;
                }
                // 全量拉取时，每个指数的起始日期从 index_basic.base_date（基期）取，无基期则兜底 FULL_START_DATE
                Map<String, String> baseDateMap = new HashMap<>();
                if (isFull) {
                    for (Map<String, Object> row : indexBasicMapper.selectAllBaseDateMap()) {
                        Object tsCode = row.get("tsCode");
                        Object baseDate = row.get("baseDate");
                        if (tsCode != null && baseDate != null) {
                            baseDateMap.put(tsCode.toString(), baseDate.toString());
                        }
                    }
                }
                long updatedRows = 0L;
                if (isFull) {
                    for (String code : codes) {
                        try {
                            String start = baseDateMap.getOrDefault(code, fullStart);
                            updatedRows += indexDailyFetchService.fetchAndSaveIndexDaily(code, start, today);
                        } catch (Exception e) {
                            throw new StepExecutionException(
                                    step.getLabel() + " 拉取失败: " + code, updatedRows, e);
                        }
                    }
                } else {
                    Map<String, String> lastDateMap = preloadLastDateMap(indexDailyMapper::selectMaxTradeDatePerIndex);
                    for (String code : codes) {
                        try {
                            String lastDate = lastDateMap.get(code);
                            String start = lastDate != null ? lastDate : fullStart;
                            updatedRows += indexDailyFetchService.fetchAndSaveIndexDaily(code, start, today);
                        } catch (Exception e) {
                            throw new StepExecutionException(
                                    step.getLabel() + " 拉取失败: " + code, updatedRows, e);
                        }
                    }
                }
                return updatedRows;
            }
        }
        return 0L;
    }

    @FunctionalInterface
    private interface TsCodeTask {
        long execute(String tsCode) throws Exception;
    }

    private long executePerStockStep(InitStep step, String taskId, TsCodeTask task) {
        List<StockBasicDTO> stocks = resolveStockListForSingleStep();
        long updatedRows = 0L;
        for (int i = 0; i < stocks.size(); i++) {
            if (taskProgressCache.isCancelled(taskId)) {
                return updatedRows;
            }
            if (i > 0 && i % 20 == 0) {
                updateTaskRunning(taskId, step.getLabel() + ": " + i + "/" + stocks.size());
            }
            try {
                updatedRows += task.execute(stocks.get(i).getTsCode());
            } catch (Exception e) {
                throw new StepExecutionException(step.getLabel() + " 拉取失败: " + stocks.get(i).getTsCode(), updatedRows, e);
            }
        }
        return updatedRows;
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
                               long totalCount, String errorMessage) {
        DataPullLogDO update = DataPullLogDO.builder()
                .taskId(taskId)
                .status(status)
                .endTime(LocalDateTime.now().format(DATETIME_FMT))
                .durationMs(System.currentTimeMillis() - startMs)
                .totalCount(totalCount)
                .errorMessage(errorMessage)
                .build();
        dataPullLogMapper.updateStatus(update);
    }

    private void clearCachesAfterSuccess(InitStep step) {
        switch (step) {
            case DAILY, ADJ_FACTOR -> clearCache("kline");
            case INDEX_DAILY, INDEX_BASIC, INDEX_WEIGHT, SW_INDUSTRY -> clearCache("sectorRanking");
            case MONEYFLOW -> clearCache("sectorMoneyflow");
            case DAILY_BASIC -> clearCache("sectorValuation");
            default -> {
                // No cache depends directly on this step.
            }
        }
    }

    private void clearCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
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
    private long executeDailySnapshotStep(InitStep step, String taskId, boolean isFull,
                                               java.util.function.ToLongFunction<String> fetchFn) {
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
    private long executeDailySnapshotStep(InitStep step, String taskId, boolean isFull,
                                               String fullStartDate,
                                               java.util.function.ToLongFunction<String> fetchFn) {
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
            return 0L;
        }

        int total = tradeDates.size();
        long success = 0L;
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
                success += fetchFn.applyAsLong(tradeDate);
            } catch (Exception e) {
                throw new StepExecutionException(step.getLabel() + " 拉取失败: " + tradeDate, success, e);
            }
        }
        log.info("{} completed: updated units={}, total dates={}", step.getLabel(), success, total);
        return success;
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
    private long executeMonthlySnapshotStep(InitStep step, String taskId, boolean isFull,
                                                 String fullStartDate,
                                                 java.util.function.BiFunction<String, String, Integer> fetchFn) {
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
            return 0L;
        }

        int total = monthRanges.size();
        long success = 0L;
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
                success += fetchFn.apply(range[0], range[1]);
            } catch (Exception e) {
                throw new StepExecutionException(step.getLabel() + " 拉取失败: " + range[0] + "~" + range[1], success, e);
            }
        }
        log.info("{} completed: updated units={}, total months={}", step.getLabel(), success, total);
        return success;
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

    private long completedCount(Throwable error, long fallback) {
        return error instanceof StepExecutionException stepError
                ? stepError.completedCount : fallback;
    }

    private String summarizeError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        String masked = SensitiveDataUtil.mask(message);
        return masked.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? masked : masked.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private static final class StepExecutionException extends RuntimeException {
        private final long completedCount;

        private StepExecutionException(String message, long completedCount, Throwable cause) {
            super(message, cause);
            this.completedCount = completedCount;
        }
    }

    /** D 类日频快照表：全量重建时不应 truncate，改为逐日拉取 */
    private static final Set<InitStep> DAILY_SNAPSHOT_STEPS = Set.of(
            InitStep.DAILY_BASIC, InitStep.MONEYFLOW, InitStep.TOP_LIST, InitStep.TOP_INST,
            InitStep.BLOCK_TRADE, InitStep.HK_HOLD, InitStep.MARGIN, InitStep.MARGIN_DETAIL
    );
}
