package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 策略主表数据对象，对应 quant_strategy 表。
 * <p>
 * createdAt/updatedAt 存 UTC ISO8601 字符串，由 Service 层手动赋值（Instant.now().toString()），
 * 不使用 MyBatis-Plus 自动填充，避免与 spec 要求的字符串时间格式冲突。
 */
@Data
@TableName("quant_strategy")
public class QuantStrategyDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略业务标识（UUID/业务编码，全局唯一） */
    private String strategyId;

    /** 策略名称 */
    private String name;

    /** 策略描述 */
    private String description;

    /** 策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM） */
    private String category;

    /** 适用范围（single/portfolio/mixed） */
    private String scope;

    /** 状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED） */
    private String status;

    /** 标签（逗号分隔） */
    private String tags;

    /** 当前生效版本号 */
    private Integer currentVersion;

    /** 创建时间（UTC ISO8601 字符串） */
    private String createdAt;

    /** 更新时间（UTC ISO8601 字符串） */
    private String updatedAt;
}
