package com.arthur.stock.constant;

import lombok.Getter;

/**
 * 数据拉取批次枚举：每个枚举项对应一个定时任务批次，
 * 收敛 batchKey（事件分发标识）、taskName（日志展示名）、cron（@Scheduled 表达式）、关联的 InitStep（tableCode 来源）。
 *
 * <p>用于 {@code TushareDataFetchTask}（发事件）与 {@code DataFetchTriggerEventListener}（分发）之间的一致性契约，
 * 避免两端各自硬编码字符串导致不一致。</p>
 *
 * <p>注意：本枚举仅作内部常量契约，不实现 DisplayableEnum（无需前端下拉）。</p>
 */
@Getter
public enum DataFetchBatchEnum {

    /** 16:00 批次：交易日历/股票基础/日线/复权/分红/指数基础 */
    BATCH_1600("1600", "16:00数据批次", "0 0 16 * * ?", InitStep.DAILY),
    /** 16:30 批次（核心）：每日基本面/资金流/指数日线/更名 */
    BATCH_1630("1630", "16:30数据批次", "0 30 16 * * MON-FRI", InitStep.DAILY_BASIC),
    /** 16:40 批次：停复牌/涨跌停价增量 */
    BATCH_1640("1640", "16:40数据批次", "0 40 16 * * ?", InitStep.STK_LIMIT),
    /** 20:00 批次：指数成分权重 */
    BATCH_2000("2000", "20:00指数权重批次", "0 0 20 * * MON-FRI", InitStep.INDEX_WEIGHT),

    /** 每周日 17:00：财务指标 */
    WEEKLY_FINA_INDICATOR("WEEKLY_FINA_INDICATOR", "财务指标更新", "0 0 17 * * SUN", InitStep.FINA_INDICATOR),
    /** 每周日 17:30：利润表 */
    WEEKLY_INCOME("WEEKLY_INCOME", "利润表更新", "0 30 17 * * SUN", InitStep.INCOME),
    /** 每周日 18:00：资产负债表 */
    WEEKLY_BALANCESHEET("WEEKLY_BALANCESHEET", "资产负债表更新", "0 0 18 * * SUN", InitStep.BALANCESHEET),
    /** 每周日 18:30：现金流量表 */
    WEEKLY_CASHFLOW("WEEKLY_CASHFLOW", "现金流量表更新", "0 30 18 * * SUN", InitStep.CASHFLOW),
    /** 每周日 19:00：业绩预告 */
    WEEKLY_FORECAST("WEEKLY_FORECAST", "业绩预告更新", "0 0 19 * * SUN", InitStep.FORECAST),
    /** 每周日 19:30：业绩快报 */
    WEEKLY_EXPRESS("WEEKLY_EXPRESS", "业绩快报更新", "0 30 19 * * SUN", InitStep.EXPRESS),
    /** 每周日 20:00：股东人数 */
    WEEKLY_HOLDERNUMBER("WEEKLY_HOLDERNUMBER", "股东人数更新", "0 0 20 * * SUN", InitStep.STK_HOLDERNUMBER),
    /** 每周日 20:30：股东增减持 */
    WEEKLY_HOLDERTRADE("WEEKLY_HOLDERTRADE", "股东增减持更新", "0 30 20 * * SUN", InitStep.STK_HOLDERTRADE),

    /** 每季度首月 1 日 22:00：更名全量 */
    QUARTERLY_NAMECHANGE("QUARTERLY_NAMECHANGE", "更名季度全量", "0 0 22 1 1,4,7,10 *", InitStep.NAMECHANGE),
    /** 每月 1 日 22:00：停复牌全量 */
    MONTHLY_SUSPEND("MONTHLY_SUSPEND", "停复牌月度全量", "0 0 22 1 * *", InitStep.SUSPEND_D),
    /** 每月 1 日 22:30：涨跌停价全量 */
    MONTHLY_STK_LIMIT("MONTHLY_STK_LIMIT", "涨跌停价月度全量", "0 30 22 1 * *", InitStep.STK_LIMIT),
    /** 每年 1/7 月 1 日 22:00：申万行业分类全量 */
    SW_INDUSTRY("SW", "申万行业半年同步", "0 0 22 1 1,7 *", InitStep.SW_INDUSTRY);

    /** 批次标识（事件分发 key） */
    private final String batchKey;
    /** 任务名称（日志展示用） */
    private final String taskName;
    /** @Scheduled cron 表达式 */
    private final String cron;
    /** 关联的 InitStep（提供 tableCode / tableName） */
    private final InitStep step;

    DataFetchBatchEnum(String batchKey, String taskName, String cron, InitStep step) {
        this.batchKey = batchKey;
        this.taskName = taskName;
        this.cron = cron;
        this.step = step;
    }

    public static DataFetchBatchEnum fromBatchKey(String batchKey) {
        if (batchKey == null) return null;
        for (DataFetchBatchEnum v : values()) {
            if (v.batchKey.equals(batchKey)) return v;
        }
        return null;
    }
}
