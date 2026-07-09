package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略配置 JSON Diff 单条变更。
 * <p>
 * 由 {@link com.arthur.stock.util.JsonDiffUtil} 递归比较两个配置树产出。
 * path 点号分隔，数组索引用 {@code [n]}，例如
 * {@code trading_config.signals.buy.conditions[0].left.factor}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略配置 Diff 变更项")
public class StrategyDiffDTO {

    @Schema(description = "变更路径（点号分隔，数组索引 [n]）", example = "trading_config.position_sizing.target")
    private String path;

    @Schema(description = "变更类型（added/removed/modified）")
    private String changeType;

    @Schema(description = "旧值（removed/modified 时返回）")
    private Object oldValue;

    @Schema(description = "新值（added/modified 时返回）")
    private Object newValue;
}
