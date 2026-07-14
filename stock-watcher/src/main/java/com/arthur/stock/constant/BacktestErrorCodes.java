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

    /** 第二波模式不支持（GRID / WALK_FORWARD，第三波；run() 入口对 req.mode 校验时使用） */
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

    /**
     * 回测标的池规模超限（Phase 2，008-backtest-center-phase2）。
     * <p>
     * universe=all_a_shares 全市场在 watcher 侧直接拒绝，避免 5000+ 标的 K 线撑爆 HTTP 载荷与 engine 超时；
     * 引导改用 csi300 / csi500 / manual 池。
     * <p>
     * 注：tasks.md T3.2 原文记为 40012，但 40012 已被 {@link #BACKTEST_MODE_NOT_SUPPORTED} 占用
     * （第二波 spec 明确 GRID/WF 仍需返回该码），故此处改用下一空闲序号 40016。
     */
    public static final int BACKTEST_UNIVERSE_TOO_LARGE = 40016;

    /** signals（择时）范式 universe 仅支持 manual（spec 009-strategy-paradigm-exclusive） */
    public static final int SIGNALS_UNIVERSE_NOT_MANUAL = 40017;

    /** signals（择时）范式 universe 超 10 只上限（spec 009-strategy-paradigm-exclusive） */
    public static final int SIGNALS_UNIVERSE_TOO_LARGE = 40018;

    /** signals 与 rebalance 范式互斥，watcher 侧兜底校验（spec 009-strategy-paradigm-exclusive） */
    public static final int SIGNALS_REBALANCE_EXCLUSIVE = 40019;
}
