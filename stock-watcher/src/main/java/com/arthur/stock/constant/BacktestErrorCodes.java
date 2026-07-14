package com.arthur.stock.constant;

/**
 * 回测模块业务错误码常量（spec FR-5 / FR-7）。
 * <p>
 * 编码约定：前三位复用 HTTP 语义（4xx 客户端 / 5xx 服务端），后两位为模块内序号。
 * 配合 {@code new BusinessException(BacktestErrorCodes.XXX, "msg")} 使用。
 */
public final class BacktestErrorCodes {

    private BacktestErrorCodes() {
    }

    /** 回测任务不存在 */
    public static final int BACKTEST_NOT_FOUND = 40404;

    /** 策略版本状态非法（DRAFT/ARCHIVED 不可回测） */
    public static final int BACKTEST_STRATEGY_VERSION_INVALID = 40010;

    /** 第一波范式不支持（含 rebalance / exit.rules / use_atr_stop） */
    public static final int BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1 = 40011;

    /** 第二波模式不支持（GRID / WALK_FORWARD） */
    public static final int BACKTEST_MODE_NOT_SUPPORTED = 40012;

    /** 基准不存在 */
    public static final int BACKTEST_BENCHMARK_NOT_FOUND = 40013;

    /** 数据不足（kline_data 为空或无行情） */
    public static final int BACKTEST_DATA_INSUFFICIENT = 40014;

    /** 回测任务状态冲突（如 RUNNING 不可取消） */
    public static final int BACKTEST_TASK_STATUS_CONFLICT = 40902;

    /** 回测超时 */
    public static final int BACKTEST_TIMEOUT = 50401;

    /** 回测引擎服务不可用 */
    public static final int ENGINE_SERVICE_UNAVAILABLE = 50301;

    /** 回测对比失败（对比数不足或无报告） */
    public static final int BACKTEST_COMPARE_FAILED = 40015;
}
