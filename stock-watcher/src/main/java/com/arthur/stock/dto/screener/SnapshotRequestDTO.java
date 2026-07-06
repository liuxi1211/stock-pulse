package com.arthur.stock.dto.screener;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 选股 snapshot 请求体（spec FR-10），透传 engine {@code POST /python/v1/screener/snapshot}。
 * <p>
 * 注意：
 * <ul>
 *   <li>{@code conditions}/{@code ranking} 用 {@code Object} 透传任意 JSON 树（条件树为任意嵌套结构，强类型建模成本高）。</li>
 *   <li>engine Pydantic 期望 snake_case；多词字段（topN/verboseExcluded）用 {@link JSONField} 显式映射为 snake_case，
 *       序列化经 fastjson2 {@code JSON.toJSONString}，故使用 fastjson2 注解（非 Jackson @JsonProperty）。</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "选股 snapshot 请求")
public class SnapshotRequestDTO {

    @Schema(description = "候选池标识：all_a_shares / csi300 / csi500 / manual",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String universe;

    @Schema(description = "选股日，格式 YYYY-MM-DD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String date;

    @Schema(description = "候选股数据：symbol -> 候选数据", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, CandidateStockDTO> candidates;

    @Schema(description = "条件树（任意嵌套 JSON：AND/OR + compare）")
    private Object conditions;

    @Schema(description = "排序规则（method/single/factor/order 任意 JSON）")
    private Object ranking;

    @Schema(description = "过滤选项")
    private FiltersDTO filters;

    @JSONField(name = "top_n")
    @Schema(description = "最终取前 N 只，0/空 表示不截断")
    private Integer topN;

    @JSONField(name = "verbose_excluded")
    @Schema(description = "是否在 excluded 中详细返回被剔除原因")
    private Boolean verboseExcluded;
}
