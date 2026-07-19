package com.arthur.stock.dto.backtest;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Data;

/**
 * 回测任务 VO（spec 007 T3）。对应 quant_backtest 单条记录。
 * <p>
 * 业务展示码 {@code displayCode} 派生自主键 id（"BT-" + id），便于前端展示与多任务对比引用。
 */
@Data
public class BacktestTaskVO {

    private Long id;

    /** 任务唯一ID（UUID） */
    private String taskId;

    /** 策略主表ID（内部关联用） */
    private Long strategyId;

    /** 策略UUID（前端交互用，由主表 JOIN 填充） */
    private String strategyUuid;

    /** 策略名称（前端展示用，由主表 JOIN 填充） */
    private String strategyName;

    private Integer versionNo;

    /** 回测模式（SINGLE/GRID/WALK_FORWARD） */
    private String mode;

    /** 任务状态（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED） */
    private String status;

    /** 进度百分比（0-100） */
    private Integer progress;

    private String errorMessage;

    private String benchmark;

    private String createdBy;

    private String startedAt;

    private String finishedAt;

    private String createdAt;

    /**
     * 业务展示码：{@code BT-<id>}。前端 / 对比页用它做条目标识。
     */
    @JsonGetter("displayCode")
    public String getDisplayCode() {
        return id == null ? null : "BT-" + id;
    }
}
