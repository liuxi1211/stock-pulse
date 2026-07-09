package com.arthur.stock.exception;

import com.arthur.stock.constant.StrategyErrorCodes;
import com.arthur.stock.dto.strategy.StrategyValidationError;
import lombok.Getter;

import java.util.List;

/**
 * 策略配置校验失败异常（spec 004 Task 6）。
 * <p>
 * 当 {@code engineClient.validate(configJson)} 返回非空 errors 时抛出。
 * 与 {@link BusinessException} 不同：本异常携带 {@code errors} 列表，
 * 由 {@link GlobalExceptionHandler} 转成带 errors 数组的 400 响应，便于前端精确定位每个字段错误。
 * <p>
 * code 固定为 {@link StrategyErrorCodes#STRATEGY_VALIDATION_FAILED}。
 */
@Getter
public class StrategyValidationException extends RuntimeException {

    private final int code = StrategyErrorCodes.STRATEGY_VALIDATION_FAILED;
    private final List<StrategyValidationError> errors;

    public StrategyValidationException(List<StrategyValidationError> errors) {
        super(buildMessage(errors));
        this.errors = errors;
    }

    public StrategyValidationException(String message, List<StrategyValidationError> errors) {
        super(message);
        this.errors = errors;
    }

    private static String buildMessage(List<StrategyValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "策略配置校验失败";
        }
        StringBuilder sb = new StringBuilder("策略配置校验失败：");
        int n = Math.min(errors.size(), 3);
        for (int i = 0; i < n; i++) {
            StrategyValidationError e = errors.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(e.getPath() == null || e.getPath().isEmpty() ? "(root)" : e.getPath())
                    .append(": ").append(e.getMessage());
        }
        if (errors.size() > n) {
            sb.append("; 等 ").append(errors.size()).append(" 项");
        }
        return sb.toString();
    }
}
