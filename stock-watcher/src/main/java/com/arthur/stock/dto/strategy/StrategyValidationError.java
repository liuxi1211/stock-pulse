package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略配置校验错误项。
 * <p>
 * 对应 engine {@code POST /python/v1/strategies/validate} 返回 {@code {valid, errors}} 中的 errors 元素。
 * engine 不可达时由 {@link com.arthur.stock.client.StrategyEngineClient} 抛
 * {@code BusinessException(StrategyErrorCodes.ENGINE_SERVICE_UNAVAILABLE)}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略配置校验错误项")
public class StrategyValidationError {

    @Schema(description = "错误路径（点号分隔）", example = "trading_config.signals.buy.conditions[0].left")
    private String path;

    @Schema(description = "错误码（engine 定义）", example = "INVALID_FACTOR")
    private String code;

    @Schema(description = "错误消息", example = "未知因子: NOT_A_FACTOR")
    private String message;
}
