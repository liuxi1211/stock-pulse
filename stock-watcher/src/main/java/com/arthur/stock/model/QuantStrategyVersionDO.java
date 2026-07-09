package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 策略版本快照数据对象，对应 quant_strategy_version 表。
 * <p>
 * 每次策略配置变更生成一份不可变快照，strategyId 为 quant_strategy.id（Long）。
 * createdAt 存 UTC ISO8601 字符串，由 Service 层手动赋值。
 */
@Data
@TableName("quant_strategy_version")
public class QuantStrategyVersionDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略主表ID（quant_strategy.id） */
    private Long strategyId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本配置（统一策略 Schema JSON 文本） */
    private String configJson;

    /** 版本变更说明 */
    private String changelog;

    /** 创建时间（UTC ISO8601 字符串） */
    private String createdAt;
}
