package com.arthur.stock.constant;

/**
 * 策略模块业务错误码常量（spec FR-2）。
 * <p>
 * 现有 {@link com.arthur.stock.exception.ErrorCode} 是简化版（仅 HTTP 标准码），
 * 本常量类提供策略模块专属的业务错误码（int），配合
 * {@code new BusinessException(StrategyErrorCodes.XXX, "msg")} 使用。
 * <p>
 * 编码约定：前三位复用 HTTP 语义（4xx 客户端 / 5xx 服务端），后两位为模块内序号。
 */
public final class StrategyErrorCodes {

    private StrategyErrorCodes() {
    }

    /** 策略不存在 */
    public static final int STRATEGY_NOT_FOUND = 40401;

    /** 策略校验失败（Schema/参数不合法） */
    public static final int STRATEGY_VALIDATION_FAILED = 40001;

    /** 回测引擎服务不可用 */
    public static final int ENGINE_SERVICE_UNAVAILABLE = 50301;

    /** 策略状态非法流转 */
    public static final int STRATEGY_INVALID_STATUS_TRANSITION = 40002;

    /** 策略版本不存在 */
    public static final int STRATEGY_VERSION_NOT_FOUND = 40402;

    /** 策略配置 JSON 超过大小限制 */
    public static final int STRATEGY_CONFIG_TOO_LARGE = 40003;

    /** 策略版本并发冲突（乐观锁冲突） */
    public static final int STRATEGY_VERSION_CONFLICT = 40901;
}
