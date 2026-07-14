package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略分页查询请求（spec 004 Task 6）。
 * <p>
 * 项目当前未定义通用 PageRequest 基类，这里自含分页字段（page 从 1 开始，size 默认 20）。
 * 过滤字段全部可选：keyword/name 模糊、category/scope/status/tag 精确匹配；
 * status 不传时默认排除 ARCHIVED，仅当显式传 {@code ARCHIVED} 才返回归档策略。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略分页查询请求")
public class StrategyPageRequest {

    @Schema(description = "页码（从 1 开始）", example = "1")
    private Integer page = 1;

    @Schema(description = "每页条数", example = "20")
    private Integer size = 20;

    @Schema(description = "关键字（按 name / description 模糊匹配）")
    private String keyword;

    @Schema(description = "分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）")
    private String category;

    @Schema(description = "状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED，不传默认排除 ARCHIVED）")
    private String status;

    @Schema(description = "适用范围（single/portfolio）")
    private String scope;

    @Schema(description = "标签（逗号分隔 tags 中精确包含）")
    private String tag;
}
