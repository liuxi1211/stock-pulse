package com.arthur.stock.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选股执行结果视图对象。
 * <p>
 * {@code stocksJson} 为落库的 JSON 文本（engine 原样数组），前端按需自行解析。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenResultVO {

    /** 主键ID */
    private Long id;

    /** 关联方案ID */
    private Long planId;

    /** 选股日期（YYYYMMDD） */
    private String screenDate;

    /** 命中总数 */
    private Integer totalCount;

    /** 命中股票列表 JSON 原文（[{symbol,rank,score,factor_values}, ...]） */
    private String stocksJson;

    /** 本次执行参数快照（universe/conditions/ranking/filters/topN 等） */
    private String paramsJson;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
