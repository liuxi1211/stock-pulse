package com.arthur.stock.dto.backtest;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 多任务回测对比 VO（spec 007 T3）。
 * <p>
 * 把多个回测报告聚合成：
 * <ul>
 *   <li>{@code curves} —— 归一化后的资金曲线集合（同日期轴对齐），供前端折线图叠加。</li>
 *   <li>{@code metricsTable} —— 指标对比表（行=指标，列=任务）。</li>
 *   <li>{@code radarData} —— 雷达图数据（收益/夏普/回撤/胜率/盈亏比/换手）。</li>
 * </ul>
 */
@Data
public class BacktestCompareVO {

    /**
     * 归一化曲线列表。每项形如：
     * <pre>
     * { "label": "BT-1", "strategyId": "abc...", "dates": [...], "values": [...] }
     * </pre>
     */
    private List<Map<String, Object>> curves;

    /**
     * 指标对比表。每项形如：
     * <pre>
     * { "metric": "sharpe_ratio", "label": "夏普", "values": [1.2, 0.8, ...] }
     * </pre>
     * 列顺序与 curves 一致。
     */
    private List<Map<String, Object>> metricsTable;

    /**
     * 雷达图数据。每项形如：
     * <pre>
     * { "label": "BT-1", "values": {"return": 0.3, "sharpe": 1.2, "drawdown": 0.15, ...} }
     * </pre>
     * 各维度按归一化（0~1）后填充，便于多任务雷达对比。
     */
    private List<Map<String, Object>> radarData;
}
