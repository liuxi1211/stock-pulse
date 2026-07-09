package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略统计聚合 DTO（spec 004 · 列表页统计条）。
 * 按状态分组计数,用于列表页顶部 4 张统计卡。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略统计聚合")
public class StrategyStatsDTO {

    @Schema(description = "策略总数（不含 ARCHIVED）")
    private long total;

    @Schema(description = "已验证数量")
    private long verified;

    @Schema(description = "激活中数量")
    private long active;

    @Schema(description = "草稿数量")
    private long draft;

    @Schema(description = "已归档数量")
    private long archived;
}
