package com.arthur.stock.exception;

import com.arthur.stock.constant.StrategyErrorCodes;
import lombok.Getter;

/**
 * 策略版本乐观锁冲突异常（spec 004 Task 6）。
 * <p>
 * 当请求 {@code expectedVersion} 与主表 {@code current_version} 不一致时抛出，
 * 携带主表当前 currentVersion 供前端刷新后重试。
 * <p>
 * code 固定为 {@link StrategyErrorCodes#STRATEGY_VERSION_CONFLICT}。
 */
@Getter
public class StrategyVersionConflictException extends RuntimeException {

    private final int code = StrategyErrorCodes.STRATEGY_VERSION_CONFLICT;
    private final Integer currentVersion;

    public StrategyVersionConflictException(Integer currentVersion) {
        super("策略版本已变更（当前版本: " + (currentVersion == null ? "未知" : "v" + currentVersion) + "），请刷新后重试");
        this.currentVersion = currentVersion;
    }
}
