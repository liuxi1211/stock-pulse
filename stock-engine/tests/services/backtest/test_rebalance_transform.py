"""轮动选股 transform 条件端到端集成测试（PRD 009 §1 P1-6 Task 10）。

验证完整链路：
    cfg FactorNode.transform
    -> _conditions_to_dict
    -> precompute_factors 基本面 transform（per-bar extra 序列聚合）
    -> ConditionEngine._resolve_factor（transform 感知签名）
    -> 候选 selection。

测试不依赖 watcher / 真实回测：直接构造 kline_map + extra_map，并 monkeypatch
spec 011 P1-1 强制的 point-in-time 成分股过滤为 pass-through。
"""
import pandas as pd
import pytest

from services.backtest.rebalance_engine import RebalanceEngine


def _kline(closes):
    """构造单标的 K 线 list[dict]，close=closes[i]，日期连续。"""
    return [
        {"date": f"2024-01-{i + 1:02d}", "open": c, "high": c, "low": c, "close": c, "volume": 1000}
        for i, c in enumerate(closes)
    ]


def _extra(pe_series):
    """extra_map: {symbol: {trade_date_str: {pe_ttm: v}}}。"""
    return {
        "S1": {f"2024-01-{i + 1:02d}": {"pe_ttm": v} for i, v in enumerate(pe_series)},
    }


def test_rebalance_select_with_fundamental_transform(monkeypatch):
    # 屏蔽 point-in-time universe 过滤（spec 011 P1-1 强制查 watcher）：
    # 让 _apply_universe_filter 原样返回 kline_map，避免依赖 watcher_client。
    monkeypatch.setattr(
        RebalanceEngine, "_apply_universe_filter",
        staticmethod(lambda cfg, km, ts, wc: km),
    )

    # PE_TTM 逐日 [10,12,14,16,18,20]，ma(3) 末值 = (16+18+20)/3 = 18.0；
    # 原始末值 raw=20.0。阈值 19 严格区分两者：
    #   应用 transform 时 18 < 19 → 选中；
    #   若 transform 被静默忽略，20 < 19 为 False → 不选中（回归可检测）。
    pe = [10.0, 12.0, 14.0, 16.0, 18.0, 20.0]
    closes = [10.0] * 6
    kline_map = {"S1": _kline(closes)}
    extra_map = _extra(pe)

    cfg = {
        "universe": {"pool": "manual", "stocks": ["S1"]},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": {
                "operator": "AND",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                        "comparator": "<",
                        "right": {"value": 19},
                    }
                ],
            }
        },
        "portfolio": {"top_n": 10},
    }

    scores = RebalanceEngine().select_at_rebalance_date(
        cfg, kline_map, pd.Timestamp("2024-01-06"), extra_map=extra_map
    )
    assert "S1" in scores  # ma3=18 < 19 命中

    # 反例：阈值改 < 17，ma3=18 不命中（sanity check）
    cfg_fail = {
        "universe": {"pool": "manual", "stocks": ["S1"]},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": {
                "operator": "AND",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                        "comparator": "<",
                        "right": {"value": 17},
                    }
                ],
            }
        },
        "portfolio": {"top_n": 10},
    }
    scores_fail = RebalanceEngine().select_at_rebalance_date(
        cfg_fail, kline_map, pd.Timestamp("2024-01-06"), extra_map=extra_map
    )
    assert "S1" not in scores_fail
