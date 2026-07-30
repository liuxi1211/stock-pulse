package com.arthur.stock.event.listener;

import com.arthur.stock.constant.DataFetchBatchEnum;
import com.arthur.stock.constant.DataFetchConstants;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.constant.PullLogOperationTypeEnum;
import com.arthur.stock.constant.PullLogStatusEnum;
import com.arthur.stock.event.DataFetchTriggerEvent;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.model.DataPullLogDO;
import com.arthur.stock.service.IndexDailyFetchService;
import com.arthur.stock.service.datafetch.BasicDataFetchService;
import com.arthur.stock.service.datafetch.DailyFetchService1600;
import com.arthur.stock.service.datafetch.IndexWeightFetchService;
import com.arthur.stock.service.datafetch.MoneyflowFetchService;
import com.arthur.stock.service.datafetch.StockNamechangeFetchService;
import com.arthur.stock.service.datafetch.StockStkLimitFetchService;
import com.arthur.stock.service.datafetch.StockSuspendDFetchService;
import com.arthur.stock.service.datafetch.SwIndustryFetchService;
import com.arthur.stock.service.precompute.DataBatchCompletionTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 数据拉取触发事件核心消费者：根据 {@link DataFetchBatchEnum} 分发到对应拉取服务，
 * 并在执行完成后统一写入 data_pull_log（成功/失败都写，仅记录增量/定时/全量三类数据更新事件）。
 *
 * <p><b>批次编排</b>：
 * <ul>
 *   <li>{@code BATCH_1600}：调用 {@link DailyFetchService1600}，完成后上报 {@link DataFetchConstants#TRACKER_BATCH_1600}。</li>
 *   <li>{@code BATCH_1630}：依次执行 daily_basic / 资金流 / 指数日线 / 更名，前三项各自上报批次聚合，
 *       全部完成后由 {@link DataBatchCompletionTracker} 在收齐 4 个核心任务时发布 DataBatchReadyEvent。</li>
 *   <li>{@code BATCH_1640}：停复牌 + 涨跌停增量。</li>
 *   <li>{@code BATCH_2000}：指数权重。</li>
 *   <li>{@code SW_INDUSTRY}：申万行业半年。</li>
 *   <li>每周日批量 / 全量回补类：直接调用对应方法。</li>
 * </ul>
 * <p>
 * 本 listener 运行在 {@code dataFetchExecutor} 异步线程池，不阻塞定时调度线程。
 * <p>
 * <b>日志写入</b>：从事件携带的 tableCode/cronExpression/operator 元信息构造日志，
 * 成功记 SUCCESS，失败记 FAILED（errorMessage 存脱敏 msg，堆栈走 log.error 不入库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFetchTriggerEventListener {

    private final DailyFetchService1600 dailyFetchService1600;
    private final BasicDataFetchService basicDataFetchService;
    private final MoneyflowFetchService moneyflowFetchService;
    private final IndexDailyFetchService indexDailyFetchService;
    private final StockNamechangeFetchService stockNamechangeFetchService;
    private final StockSuspendDFetchService stockSuspendDFetchService;
    private final StockStkLimitFetchService stockStkLimitFetchService;
    private final IndexWeightFetchService indexWeightFetchService;
    private final SwIndustryFetchService swIndustryFetchService;
    private final DataBatchCompletionTracker batchCompletionTracker;
    private final DataPullLogMapper dataPullLogMapper;

    @EventListener
    @Async("dataFetchExecutor")
    public void onFetchTrigger(DataFetchTriggerEvent event) {
        DataFetchBatchEnum batch = DataFetchBatchEnum.fromBatchKey(event.getBatchKey());
        if (batch == null) {
            log.warn("[DataFetchListener] 未知 batchKey={}, 忽略", event.getBatchKey());
            return;
        }
        log.info("[DataFetchListener] 收到批次事件 batch={} tradeDate={}", batch.getBatchKey(), event.getTradeDate());

        long startMs = System.currentTimeMillis();
        String startTime = LocalDateTime.now(DataFetchConstants.ZONE_SHANGHAI).format(DataFetchConstants.DATETIME);
        try {
            dispatch(batch, event.getTradeDate());
            writeLog(event, batch, startTime, startMs, PullLogStatusEnum.SUCCESS, null);
        } catch (Exception e) {
            log.error("[DataFetchListener] batch={} 处理失败", batch.getBatchKey(), e);
            String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            writeLog(event, batch, startTime, startMs, PullLogStatusEnum.FAILED,
                    truncate(errMsg, DataFetchConstants.ERROR_MESSAGE_MAX_LEN));
        }
    }

    /** 按批次分发到对应拉取服务 */
    private void dispatch(DataFetchBatchEnum batch, String tradeDate) {
        switch (batch) {
            case BATCH_1600 -> handle1600(tradeDate);
            case BATCH_1630 -> handle1630(tradeDate);
            case BATCH_1640 -> handle1640();
            case BATCH_2000 -> handle2000();
            case SW_INDUSTRY -> swIndustryFetchService.syncHalfYearly();
            case WEEKLY_FINA_INDICATOR -> basicDataFetchService.fetchFinaIndicator();
            case WEEKLY_INCOME -> basicDataFetchService.fetchIncome();
            case WEEKLY_BALANCESHEET -> basicDataFetchService.fetchBalancesheet();
            case WEEKLY_CASHFLOW -> basicDataFetchService.fetchCashflow();
            case WEEKLY_FORECAST -> basicDataFetchService.fetchForecast();
            case WEEKLY_EXPRESS -> basicDataFetchService.fetchExpress();
            case WEEKLY_HOLDERTRADE -> basicDataFetchService.fetchStkHoldertrade();
            case WEEKLY_HOLDERNUMBER -> basicDataFetchService.fetchStkHoldernumber();
            case QUARTERLY_NAMECHANGE -> stockNamechangeFetchService.quarterlyFull();
            case MONTHLY_SUSPEND -> stockSuspendDFetchService.monthlyFull();
            case MONTHLY_STK_LIMIT -> stockStkLimitFetchService.monthlyFull();
        }
    }

    /** 16:00 批次：完成后上报 */
    private void handle1600(String tradeDate) {
        boolean hasError = dailyFetchService1600.fetchAll(tradeDate);
        batchCompletionTracker.reportCompletion(DataFetchConstants.TRACKER_BATCH_1600, tradeDate, hasError);
    }

    /** 16:30 批次：daily_basic / 资金流 / 指数日线 各自上报，再跑更名 */
    private void handle1630(String tradeDate) {
        boolean basicErr = basicDataFetchService.fetchDailyBasic(tradeDate);
        batchCompletionTracker.reportCompletion(DataFetchConstants.TRACKER_BATCH_1630_DAILY_BASIC, tradeDate, basicErr);

        boolean moneyErr = moneyflowFetchService.fetchAll(tradeDate);
        batchCompletionTracker.reportCompletion(DataFetchConstants.TRACKER_BATCH_1630_MONEYFLOW, tradeDate, moneyErr);

        boolean indexErr = indexDailyFetchService.fetchToday(tradeDate);
        batchCompletionTracker.reportCompletion(DataFetchConstants.TRACKER_BATCH_1630_INDEX_DAILY, tradeDate, indexErr);

        stockNamechangeFetchService.dailyIncremental();
    }

    /** 16:40 批次：停复牌 + 涨跌停增量 */
    private void handle1640() {
        stockSuspendDFetchService.dailyIncremental();
        stockStkLimitFetchService.dailyIncremental();
    }

    /** 20:00 批次：指数权重 */
    private void handle2000() {
        indexWeightFetchService.syncDaily();
    }

    /**
     * 写一条 data_pull_log（操作类型固定 SCHEDULED，定时任务触发）。
     * tableName 从 tableCode 经 InitStep 解析；解析不到则回退 tableCode。
     */
    private void writeLog(DataFetchTriggerEvent event, DataFetchBatchEnum batch,
                          String startTime, long startMs, PullLogStatusEnum status, String errorMessage) {
        try {
            long durationMs = System.currentTimeMillis() - startMs;
            String tableCode = event.getTableCode();
            String tableName = resolveTableName(tableCode);
            DataPullLogDO logDO = DataPullLogDO.builder()
                    .taskId(UUID.randomUUID().toString())
                    .tableCode(tableCode)
                    .tableName(tableName)
                    .operationType(PullLogOperationTypeEnum.SCHEDULED.getCode())
                    .status(status.getCode())
                    .startTime(startTime)
                    .endTime(LocalDateTime.now(DataFetchConstants.ZONE_SHANGHAI).format(DataFetchConstants.DATETIME))
                    .durationMs(durationMs)
                    .totalCount(0L)
                    .errorMessage(errorMessage)
                    .operator(event.getOperator())
                    .cronExpression(event.getCronExpression())
                    .build();
            dataPullLogMapper.insert(logDO);
        } catch (Exception ex) {
            log.error("[DataFetchListener] 写入 data_pull_log 失败 batch={}", batch.getBatchKey(), ex);
        }
    }

    private String resolveTableName(String tableCode) {
        if (tableCode == null || tableCode.isEmpty()) {
            return tableCode;
        }
        InitStep step = InitStep.fromCode(tableCode);
        return step != null ? step.getLabel() : tableCode;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
