package com.arthur.stock.dto.backtest;

import lombok.Data;

import java.util.List;

/**
 * 回测中心常量 VO（spec 007 T3）。代理 engine {@code /constants} 的结构化版本。
 * <p>
 * 当 engine 可达时优先取 engine 的；不可达时 watcher 可用本类的静态默认值兜底。
 */
@Data
public class BacktestConstantsVO {

    /** A 股 broker_profile 候选 */
    private List<String> brokerProfiles;

    /** 排序/选优指标候选（sharpe_ratio / total_return_pct / max_drawdown_pct / sortino_ratio 等） */
    private List<String> sortMetrics;

    /** 第一波支持的范式标识（如 SINGLE） */
    private List<String> paradigmsSupported;
}
