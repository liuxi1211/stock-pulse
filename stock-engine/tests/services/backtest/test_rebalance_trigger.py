"""rebalance 调仓日判定单元测试（spec 011 P2-1）。

覆盖 compiler 层零状态判定逻辑（替换原 ``_is_rebalance_trigger_day`` 自然日启发式）：

- ``_is_rebalance_day``：按 ``(frequency, trigger)`` 查 ``extra_map`` 的
  ``is_first_of_month`` / ``is_last_of_month`` 等 trade_cal 标记；
- ``_get_bar_flag``：从 extra_map 按 trade_date 取全局标记（遍历任意 symbol）；
- ``_resolve_rebalance_trigger``：编译期解析 trigger + day_of_period 兼容映射；
- 标记缺失 → 回退旧启发式 ``_is_rebalance_trigger_day``（day<=7）并打 warning；
- ``daily`` 频率恒触发（忽略 trigger）。

不依赖真实回测 / watcher：直接构造 extra_map dict 调函数断言。
"""
import logging

import pandas as pd

from services.backtest.compiler import (
    _get_bar_flag,
    _is_rebalance_day,
    _is_rebalance_trigger_day,
    _resolve_rebalance_trigger,
)


# ============================================================
# _get_bar_flag
# ============================================================

def test_get_bar_flag_returns_value_from_any_symbol():
    """标记是全局的，遍历任意 symbol 当日记录命中即返回（str 化）。"""
    extra_map = {
        "000001.SZ": {"2024-01-31": {"is_last_of_month": "1"}},
        "600000.SH": {"2024-01-31": {}},
    }
    assert _get_bar_flag(extra_map, "2024-01-31", "is_last_of_month") == "1"


def test_get_bar_flag_int_value_str_coerced():
    """watcher 可能下发 int 1 或 bool True，统一 str() 化。"""
    extra_map = {"A": {"2024-02-01": {"is_first_of_month": 1}}}
    assert _get_bar_flag(extra_map, "2024-02-01", "is_first_of_month") == "1"


def test_get_bar_flag_missing_returns_none():
    """extra_map 空 / 该日无记录 / 无该字段 → None。"""
    assert _get_bar_flag(None, "2024-01-31", "is_last_of_month") is None
    assert _get_bar_flag({}, "2024-01-31", "is_last_of_month") is None
    assert _get_bar_flag({"A": {}}, "2024-01-31", "is_last_of_month") is None
    assert _get_bar_flag({"A": {"2024-01-31": {"other": "1"}}}, "2024-01-31", "is_last_of_month") is None


# ============================================================
# _is_rebalance_day
# ============================================================

def test_is_rebalance_day_daily_always_true():
    """daily 频率恒触发，忽略 trigger 与 extra_map。"""
    assert _is_rebalance_day("2024-01-15", "daily", None, None) is True
    assert _is_rebalance_day("2024-01-15", "daily", "last", {}) is True


def test_is_rebalance_day_monthly_first_hits_flag():
    """monthly+first → 仅 is_first_of_month=1 触发。"""
    em = {"A": {
        "2024-02-01": {"is_first_of_month": "1"},
        "2024-02-15": {"is_first_of_month": "0"},
    }}
    assert _is_rebalance_day(pd.Timestamp("2024-02-01"), "monthly", "first", em) is True
    assert _is_rebalance_day(pd.Timestamp("2024-02-15"), "monthly", "first", em) is False


def test_is_rebalance_day_monthly_last_hits_flag():
    """monthly+last → 仅 is_last_of_month=1 触发。"""
    em = {"A": {
        "2024-01-31": {"is_last_of_month": "1"},
        "2024-01-15": {"is_last_of_month": "0"},
    }}
    assert _is_rebalance_day("2024-01-31", "monthly", "last", em) is True
    assert _is_rebalance_day("2024-01-15", "monthly", "last", em) is False


def test_is_rebalance_day_weekly_and_quarterly():
    """weekly/quarterly 各自查对应标记。"""
    em = {"A": {
        "2024-01-02": {"is_first_of_week": "1"},
        "2024-01-03": {"is_first_of_quarter": "1"},
    }}
    assert _is_rebalance_day("2024-01-02", "weekly", "first", em) is True
    assert _is_rebalance_day("2024-01-03", "quarterly", "first", em) is True


def test_is_rebalance_day_trigger_none_defaults_first():
    """trigger=None 时规约为 first。"""
    em = {"A": {"2024-02-01": {"is_first_of_month": "1"}}}
    assert _is_rebalance_day("2024-02-01", "monthly", None, em) is True


def test_is_rebalance_day_missing_flag_falls_back_to_heuristic(caplog):
    """extra_map 缺标记 → 回退旧 day<=7 启发式，并打 warning。"""
    # 2024-02-05 day=5 ≤ 7 → 启发式触发
    with caplog.at_level(logging.WARNING, logger="services.backtest.compiler"):
        result = _is_rebalance_day("2024-02-05", "monthly", "first", None)
    assert result is True
    assert any("缺少 trade_cal 标记" in rec.message for rec in caplog.records), (
        f"期望回退 warning，实际 logs: {[r.message for r in caplog.records]}"
    )
    # 2024-02-15 day=15 > 7 → 启发式不触发
    assert _is_rebalance_day("2024-02-15", "monthly", "first", None) is False


# ============================================================
# _resolve_rebalance_trigger
# ============================================================

class _FakeRb:
    """模拟 RebalanceModel（只需 trigger / day_of_period 两字段）。"""

    def __init__(self, trigger=None, day_of_period=None):
        self.trigger = trigger
        self.day_of_period = day_of_period


def test_resolve_trigger_explicit_first_last():
    assert _resolve_rebalance_trigger(_FakeRb(trigger="first")) == "first"
    assert _resolve_rebalance_trigger(_FakeRb(trigger="last")) == "last"


def test_resolve_trigger_none_rebalance():
    assert _resolve_rebalance_trigger(None) is None


def test_resolve_trigger_day_of_period_maps_and_warns(caplog):
    """day_of_period ≤1 → first；>1 → last；并打 deprecation warning。"""
    with caplog.at_level(logging.WARNING, logger="services.backtest.compiler"):
        assert _resolve_rebalance_trigger(_FakeRb(day_of_period=1)) == "first"
        assert _resolve_rebalance_trigger(_FakeRb(day_of_period=15)) == "last"
    assert any("day_of_period" in rec.message for rec in caplog.records)


def test_resolve_trigger_both_absent_returns_none():
    assert _resolve_rebalance_trigger(_FakeRb()) is None


# ============================================================
# 旧启发式 fallback 仍可用（deprecated 但保留）
# ============================================================

def test_legacy_heuristic_still_works():
    """deprecated fallback 函数保留可用（标记缺失回退依赖它）。"""
    assert _is_rebalance_trigger_day("2024-02-15", "daily", 0) is True
    assert _is_rebalance_trigger_day("2024-02-05", "monthly", 0) is True  # day<=7
    assert _is_rebalance_trigger_day("2024-02-15", "monthly", 0) is False
