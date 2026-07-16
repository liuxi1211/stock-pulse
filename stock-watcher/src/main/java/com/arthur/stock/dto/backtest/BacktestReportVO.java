package com.arthur.stock.dto.backtest;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 回测报告 VO（spec 007 T3）。对应 quant_backtest_report 单条记录反序列化后的���构。
 * <p>
 * 字段为动态结构（metrics / 曲线 / 明细列表），用 Map / List 承载，序列化时透传给前端。
 * 缺失的部分允许为 null（如 benchmark_curve 在 watcher 查空指数行情时降级为 null）。
 */
@Data
public class BacktestReportVO {

    /** 指标集合（sharpe / total_return_pct / max_drawdown_pct 等） */
    private Map<String, Object> metrics;

    /** 权益曲线 {dates:[...], values:[...]} */
    private Map<String, Object> equityCurve;

    /** 基准归一化净值曲线 {dates:[...], values:[...]}，可空 */
    private Map<String, Object> benchmarkCurve;

    /** 日收益率序列 */
    private List<Object> dailyReturns;

    /** 交易明细列表 */
    private List<Object> trades;

    /** 订单列表 */
    private List<Object> orders;

    /** 持仓快照列表 */
    private List<Object> positions;

    /** spec 011 P0-5：轮动调仓诊断（selected_count/actually_bought/rejected_by_cash/...），可空 */
    private Map<String, Object> rebalanceDiagnosis;

    /** spec 011 P2-5：实际生效配置（warmup_period 等），可空 */
    private Map<String, Object> effectiveConfig;

    /** spec 013 P2-9：执行诊断（split_days/splits_completed/splits_interrupted/total_impact_cost/avg_participation），可空 */
    private Map<String, Object> executionDiagnosis;
}
