package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本回测指标对比 DTO（spec 004 · 版本页回测对比 Tab）。
 * 用于展示 v4 → v5 的核心指标变化。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "版本回测指标对比")
public class StrategyVersionCompareDTO {

    @Schema(description = "对比指标行")
    private List<MetricRow> metrics = new ArrayList<>();

    @Data
    @Schema(description = "单条指标对比")
    public static class MetricRow {
        @Schema(description = "指标标识（total_return_pct / sharpe_ratio / max_drawdown_pct / win_rate / trade_count）")
        private String label;
        @Schema(description = "指标中文名")
        private String labelCn;
        @Schema(description = "起始版本值（可空）")
        private Double fromVal;
        @Schema(description = "目标版本值（可空）")
        private Double toVal;
        @Schema(description = "变化量 = toVal - fromVal（可空）")
        private Double delta;
    }
}
