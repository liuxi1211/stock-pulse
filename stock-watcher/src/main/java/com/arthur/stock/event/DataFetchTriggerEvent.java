package com.arthur.stock.event;

import org.springframework.context.ApplicationEvent;

/**
 * 数据拉取触发事件：由定时 task（如 {@code DailyDataFetchTask1600}）在 {@code @Scheduled} 触发时发布，
 * 交由 {@code DataFetchTriggerEventListener} 异步消费，完成实际数据拉取并写入 data_pull_log。
 *
 * <p><b>设计意图</b>：task 只负责「定时触发 + 发事件（携带日志所需元信息）」，不持有任何业务依赖；
 * 拉取逻辑下沉到 {@code service/datafetch/}，日志写入由 listener 统一完成。
 *
 * <p><b>携带的日志元信息</b>（listener 据此写 data_pull_log）：
 * <ul>
 *   <li>{@link #taskName} / {@link #tableCode}：日志展示用</li>
 *   <li>{@link #cronExpression}：本次触发的 @Scheduled cron，写入 data_pull_log.cron_expression</li>
 *   <li>{@link #triggerType} / {@link #operator}：SCHEDULED+SYSTEM（定时）/ MANUAL+用户名（手动）</li>
 * </ul>
 *
 * <p><b>batchKey 取值</b>（listener 据此分发）：1600 / 1630 / 1640 / 2000 / SW /
 * WEEKLY_FINA_INDICATOR / WEEKLY_INCOME / ... / QUARTERLY_NAMECHANGE / MONTHLY_SUSPEND / MONTHLY_STK_LIMIT
 */
public class DataFetchTriggerEvent extends ApplicationEvent {

    private final String batchKey;
    private final String taskName;
    private final String tableCode;
    private final String tradeDate;
    private final String cronExpression;
    private final String triggerType;
    private final String operator;

    /**
     * @param source          事件发布者
     * @param batchKey        批次标识，listener 据此分发
     * @param taskName        任务名称（中文，写日志用）
     * @param tableCode       关联表代码（写日志用）
     * @param tradeDate       本次批次对应的交易日（YYYYMMDD）
     * @param cronExpression  触发本次执行的 @Scheduled cron
     * @param triggerType     SCHEDULED / MANUAL
     * @param operator        用户名 / SYSTEM
     */
    public DataFetchTriggerEvent(Object source, String batchKey, String taskName, String tableCode,
                                 String tradeDate, String cronExpression, String triggerType, String operator) {
        super(source);
        this.batchKey = batchKey;
        this.taskName = taskName;
        this.tableCode = tableCode;
        this.tradeDate = tradeDate;
        this.cronExpression = cronExpression;
        this.triggerType = triggerType;
        this.operator = operator;
    }

    public String getBatchKey() {
        return batchKey;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getTableCode() {
        return tableCode;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public String getOperator() {
        return operator;
    }
}
