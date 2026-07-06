package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多因子选股执行结果数据对象，对应 screen_result 表
 */
@Data
@TableName("screen_result")
public class ScreenResultDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联方案ID（外键逻辑，不强约束） */
    private Long planId;

    /** 选股日期（YYYYMMDD） */
    private String screenDate;

    /** 命中总数 */
    private Integer totalCount;

    /** 命中股票列表 JSON（[{symbol,rank,score,factor_values}, ...]） */
    private String stocksJson;

    /** 本次执行参数快照（universe/conditions/ranking/filters 等） */
    private String paramsJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
