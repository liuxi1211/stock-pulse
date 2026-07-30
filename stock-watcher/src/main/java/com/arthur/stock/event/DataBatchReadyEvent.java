package com.arthur.stock.event;

import org.springframework.context.ApplicationEvent;

/**
 * 数据批次就绪事件：表示「该 tradeDate 的 4 个数据更新任务流程已结束」。
 * <p>
 * 4 个数据更新任务对应 {@code DailyUpdateTask} 的 4 个分组（基础/行情/资金/基本面），
 * 任一分组流程结束（无论成功/失败/超时/部分成功）都应发布本事件，触发下游 PrecomputeJob。
 * <p>
 * <b>事件粒度</b>：本事件只到 tradeDate 级，<b>不携带表级粒度信息</b>。
 * 订阅方（PrecomputeJob）按自身需要读取所需表，不应假定某些表已就绪；
 * Job 内部对读取异常自行降级（如重试/跳过/evict 缓存）。
 * <p>
 * <b>source 字段取值枚举</b>（注释说明，运行时为字符串常量）：
 * <ul>
 *   <li>{@code SCHEDULED}           —— 定时任务正常触发并完成</li>
 *   <li>{@code SCHEDULED_TIMEOUT}   —— 定时任务整体超时（部分表可能未拉成）</li>
 *   <li>{@code SCHEDULED_PARTIAL}   —— 定时任务部分成功（部分表失败）</li>
 *   <li>{@code MANUAL}              —— 手动触发（管理后台/运维接口）</li>
 * </ul>
 *
 * @see com.arthur.stock.service.precompute.PrecomputeJob
 */
public class DataBatchReadyEvent extends ApplicationEvent {

    private final String tradeDate;

    /** 触发来源：SCHEDULED / SCHEDULED_TIMEOUT / SCHEDULED_PARTIAL / MANUAL */
    private final String source;

    /**
     * @param source    事件发布者（Spring ApplicationEvent 的 source，通常为发布 bean 本身）
     * @param tradeDate 本次批次对应的交易日（YYYYMMDD）
     * @param origin    触发来源，取值见类 Javadoc
     */
    public DataBatchReadyEvent(Object source, String tradeDate, String origin) {
        super(source);
        this.tradeDate = tradeDate;
        this.source = origin;
    }

    /** 本次批次对应的交易日（YYYYMMDD）。 */
    public String getTradeDate() {
        return tradeDate;
    }

    /**
     * 触发来源（SCHEDULED / SCHEDULED_TIMEOUT / SCHEDULED_PARTIAL / MANUAL）。
     * <p>
     * 注意：本方法协变覆盖了 {@link ApplicationEvent#getSource()}，返回 String 类型的触发来源，
     * 而非发布者对象。发布者对象仅在构造时传入父类，不通过本 getter 暴露。
     */
    @Override
    public String getSource() {
        return source;
    }
}
