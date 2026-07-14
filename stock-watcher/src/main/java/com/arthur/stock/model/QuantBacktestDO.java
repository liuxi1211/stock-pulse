package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 回测主表/任务表数据对象，对应 quant_backtest 表。
 * <p>
 * 记录每次回测任务的元信息与状态。createdAt/startedAt/finishedAt 存 UTC ISO8601 字符串，
 * 由 Service 层手动赋值（Instant.now().toString()）。
 */
@Data
@TableName("quant_backtest")
public class QuantBacktestDO {

    /** 主键ID（业务编号派生：BT-{id}） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一ID（UUID） */
    private String taskId;

    /** 策略业务ID（quant_strategy.strategy_id） */
    private String strategyId;

    /** 策略版本号 */
    private Integer versionNo;

    /** 回测模式（SINGLE/GRID/WALK_FORWARD） */
    private String mode;

    /** 任务状态（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED） */
    private String status;

    /** 进度百分比（0-100） */
    private Integer progress;

    /** 失败原因（截断 1024 字符） */
    private String errorMessage;

    /** 参数覆盖配置（JSON 字符串） */
    private String overrideConfig;

    /** 基准指数代码（默认 000300.SH） */
    private String benchmark;

    /** 创建人 */
    private String createdBy;

    /** 开始执行时间（UTC ISO8601） */
    private String startedAt;

    /** 完成时间（UTC ISO8601） */
    private String finishedAt;

    /** 创建时间（UTC ISO8601） */
    private String createdAt;
}
