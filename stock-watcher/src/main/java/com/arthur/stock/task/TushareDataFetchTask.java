package com.arthur.stock.task;

import com.arthur.stock.constant.DataFetchBatchEnum;
import com.arthur.stock.constant.DataFetchConstants;
import com.arthur.stock.constant.PullLogOperationTypeEnum;
import com.arthur.stock.event.DataFetchTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Tushare 数据对接定时任务统一入口。
 *
 * <p>所有 Tushare 数据拉取的 {@code @Scheduled} 触发器集中在本类，按时间点 / 频率分方法组织。
 * 每个方法只负责「定时触发 + 发 {@link DataFetchTriggerEvent}」，不持有任何业务依赖；
 * 实际拉取逻辑由 {@code DataFetchTriggerEventListener} 分发到 {@code service/datafetch/} 各服务完成，
 * 并统一写入 data_pull_log。</p>
 *
 * <p>每个方法与 {@link DataFetchBatchEnum} 的枚举项一一对应，batchKey / taskName / cron / tableCode
 * 全部从枚举取值，避免魔法值。{@code @Scheduled} 注解的 cron 必须与枚举中 cron 保持一致。</p>
 *
 * <p><b>调度时间表</b>（交易日 = MON-FRI）：</p>
 * <ul>
 *   <li>16:00 —— 交易日历 / 股票基础 / 日线 / 复权 / 分红 / 指数基础</li>
 *   <li>16:30 —— 每日基本面 / 资金流 / 指数日线 / 更名（核心批次，驱动预计算）</li>
 *   <li>16:40 —— 停复牌 / 涨跌停价增量</li>
 *   <li>20:00 —— 指数成分权重</li>
 *   <li>每周日 17:00-20:30 —— 财务三表 / 业绩预告快报 / 股东类（错峰避免限流）</li>
 *   <li>每月 1 日 22:00 —— 停复牌 / 涨跌停价全量回补</li>
 *   <li>每季度首月 1 日 22:00 —— 更名全量回补</li>
 *   <li>每年 1/7 月 1 日 22:00 —— 申万行业分类</li>
 * </ul>
 *
 * <p>后续新增数据对接的定时器，只需在 {@link DataFetchBatchEnum} 加一个枚举项，
 * 再在本类新增一个 {@code @Scheduled} 方法调用 {@link #fire(DataFetchBatchEnum)} 即可。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TushareDataFetchTask {

    private final ApplicationEventPublisher publisher;

    // ==================== 每日批次（按时间点） ====================

    /** 16:00 批次：交易日历 / 股票基础 / 日线 / 复权 / 分红 / 指数基础 */
    @Scheduled(cron = "0 0 16 * * ?")
    public void fetch1600() {
        log.info("[TushareTask] 触发 16:00 批次, tradeDate={}", currentTradeDate());
        fireWithTradeDate(DataFetchBatchEnum.BATCH_1600);
    }

    /** 16:30 批次（核心）：每日基本面 / 资金流 / 指数日线 / 更名 */
    @Scheduled(cron = "0 30 16 * * MON-FRI")
    public void fetch1630() {
        log.info("[TushareTask] 触发 16:30 批次, tradeDate={}", currentTradeDate());
        fireWithTradeDate(DataFetchBatchEnum.BATCH_1630);
    }

    /** 16:40 批次：停复牌 / 涨跌停价增量 */
    @Scheduled(cron = "0 40 16 * * ?")
    public void fetch1640() {
        log.info("[TushareTask] 触发 16:40 批次");
        fire(DataFetchBatchEnum.BATCH_1640);
    }

    /** 20:00 批次：指数成分权重 */
    @Scheduled(cron = "0 0 20 * * MON-FRI")
    public void fetch2000() {
        log.info("[TushareTask] 触发 20:00 批次");
        fire(DataFetchBatchEnum.BATCH_2000);
    }

    // ==================== 每周日批量（财务/股东，错峰避免限流） ====================

    @Scheduled(cron = "0 0 17 * * SUN")
    public void finaIndicator() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_FINA_INDICATOR);
    }

    @Scheduled(cron = "0 30 17 * * SUN")
    public void income() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_INCOME);
    }

    @Scheduled(cron = "0 0 18 * * SUN")
    public void balancesheet() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_BALANCESHEET);
    }

    @Scheduled(cron = "0 30 18 * * SUN")
    public void cashflow() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_CASHFLOW);
    }

    @Scheduled(cron = "0 0 19 * * SUN")
    public void forecast() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_FORECAST);
    }

    @Scheduled(cron = "0 30 19 * * SUN")
    public void express() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_EXPRESS);
    }

    @Scheduled(cron = "0 0 20 * * SUN")
    public void holdernumber() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_HOLDERNUMBER);
    }

    @Scheduled(cron = "0 30 20 * * SUN")
    public void holdertrade() {
        fireWeekly(DataFetchBatchEnum.WEEKLY_HOLDERTRADE);
    }

    // ==================== 全量回补（低频） ====================

    /** 每季度首月 1 日 22:00：更名全量 */
    @Scheduled(cron = "0 0 22 1 1,4,7,10 *")
    public void namechangeQuarterly() {
        fire(DataFetchBatchEnum.QUARTERLY_NAMECHANGE);
    }

    /** 每月 1 日 22:00：停复牌全量 */
    @Scheduled(cron = "0 0 22 1 * *")
    public void suspendMonthly() {
        fire(DataFetchBatchEnum.MONTHLY_SUSPEND);
    }

    /** 每月 1 日 22:30：涨跌停价全量 */
    @Scheduled(cron = "0 30 22 1 * *")
    public void stkLimitMonthly() {
        fire(DataFetchBatchEnum.MONTHLY_STK_LIMIT);
    }

    /** 每年 1/7 月 1 日 22:00：申万行业分类全量 */
    @Scheduled(cron = "0 0 22 1 1,7 *")
    public void swIndustryHalfYearly() {
        fire(DataFetchBatchEnum.SW_INDUSTRY);
    }

    // ==================== 辅助方法 ====================

    /** 每周日批量统一入口（不带 tradeDate） */
    private void fireWeekly(DataFetchBatchEnum batch) {
        log.info("[TushareTask] 触发周日批量 {}", batch.getStep().getCode());
        fire(batch, null);
    }

    /** 带当前交易日的批次（16:00/16:30 等需要 tradeDate） */
    private void fireWithTradeDate(DataFetchBatchEnum batch) {
        fire(batch, currentTradeDate());
    }

    /** 不带 tradeDate 的批次（全量回补/周日批量等） */
    private void fire(DataFetchBatchEnum batch) {
        fire(batch, null);
    }

    /**
     * 发布数据拉取触发事件，全部元信息从枚举取值。
     * triggerType 固定 SCHEDULED，operator 固定 SYSTEM。
     */
    private void fire(DataFetchBatchEnum batch, String tradeDate) {
        publisher.publishEvent(new DataFetchTriggerEvent(
                this,
                batch.getBatchKey(),
                batch.getTaskName(),
                batch.getStep().getCode(),
                tradeDate,
                batch.getCron(),
                PullLogOperationTypeEnum.SCHEDULED.getCode(),
                DataFetchConstants.OPERATOR_SYSTEM));
    }

    /** 当前交易日（Asia/Shanghai） */
    private String currentTradeDate() {
        return LocalDate.now(DataFetchConstants.ZONE_SHANGHAI).format(DataFetchConstants.COMPACT_DATE);
    }
}
