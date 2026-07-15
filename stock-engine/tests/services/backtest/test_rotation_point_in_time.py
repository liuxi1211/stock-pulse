"""轮动范式 point-in-time 成分股过滤单元测试（spec 011 P1-1）。

覆盖 ``RebalanceEngine._apply_universe_filter``（**强制开启**，失败即报错）：

- AC-1：universe=csi300/csi500 时，按调仓日 point-in-time 成分股快照过滤候选池，
  剔除「调入日前 / 调出后」的标的（消除 lookahead bias）。
- PIT 强制：watcher_client=None → 抛 ``BacktestError("PIT_WATCHER_UNAVAILABLE")``。
- PIT 强制：查询返回空集 → 抛 ``BacktestError("PIT_CONSTITUENTS_EMPTY")``。
- PIT 强制：查询抛异常 → 抛 ``BacktestError("PIT_QUERY_FAILED")``。
- 所有 universe 类型（含 manual / all_a_shares）均查 watcher（用 000985.SH 作全 A proxy）。
- 每个调仓日成功过滤后打 INFO 日志。

测试不依赖真实 watcher HTTP：用 ``FakeWatcherClient`` 实现
``get_constituents_at`` 方法，或用 ``unittest.mock`` 拦截调用。
"""
from typing import Optional
from unittest.mock import MagicMock

import pandas as pd
import pytest

from services.backtest.rebalance_engine import RebalanceEngine
from services.backtest.runner import BacktestError
from services.strategy.models import ScreenConfigModel


# ============================================================
# 辅助
# ============================================================

def _screen_config(pool: str, point_in_time: Optional[bool] = None) -> ScreenConfigModel:
    """构造 4 层 screen_config（universe 层）。

    point_in_time 参数保留仅为兼容旧调用方；spec 011 P1-1 后该字段已 deprecated，
    传入非 None 会触发 deprecation warning（测试不关心）。
    """
    return ScreenConfigModel.model_validate({
        "universe": {"pool": pool, "point_in_time": point_in_time, "stocks": None}
    })


def _kline_map(symbols: list[str]) -> dict[str, list[dict]]:
    """构造最小 kline_map（每个 symbol 单根 bar，含 date 列）。"""
    rows = [{
        "date": "2022-06-15",
        "open": 10.0, "high": 10.1, "low": 9.9,
        "close": 10.0, "volume": 10000.0,
    }]
    return {sym: list(rows) for sym in symbols}


class FakeWatcherClient:
    """模拟 WatcherClient，记录调用并返回预设成分股集合。

    ``empty=True`` 时返回空集；``raise_exc`` 非 None 时抛指定异常。
    """

    def __init__(self, constituents: Optional[set[str]] = None,
                 empty: bool = False, raise_exc: Optional[Exception] = None):
        self._constituents = set() if empty else (constituents or set())
        self._raise_exc = raise_exc
        self.calls: list[tuple[str, str]] = []

    def get_constituents_at(self, index_code: str, trade_date: str) -> set[str]:
        self.calls.append((index_code, trade_date))
        if self._raise_exc is not None:
            raise self._raise_exc
        return set(self._constituents)


# ============================================================
# AC-1：csi300 / csi500 point-in-time 过滤
# ============================================================

def test_csi300_point_in_time_filter():
    """AC-1：universe=csi300 时，不在成分股集合的 symbol 被过滤掉。"""
    cfg = _screen_config("csi300")
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])
    eligible = {"000001.SZ", "600000.SH"}
    client = FakeWatcherClient(constituents=eligible)

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == eligible
    assert "600519.SH" not in filtered
    assert client.calls == [("000300.SH", "2022-06-15")]


def test_csi500_point_in_time_filter():
    """AC-1：universe=csi500 时按 000905.SH 查询并过滤。"""
    cfg = _screen_config("csi500")
    kline_map = _kline_map(["000001.SZ", "002001.SZ", "600519.SH"])
    eligible = {"002001.SZ", "600519.SH"}
    client = FakeWatcherClient(constituents=eligible)

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2023-01-03"), client
    )

    assert set(filtered.keys()) == eligible
    assert "000001.SZ" not in filtered
    assert client.calls == [("000905.SH", "2023-01-03")]


# ============================================================
# spec 011 P1-1：所有 universe 类型强制查 watcher
# ============================================================

def test_manual_universe_queries_watcher():
    """spec 011 P1-1：universe=manual 也强制查 watcher（用 000985.SH 全 A proxy）。

    manual 池的候选 = universe.stocks ∩ watcher 当日可交易标的。
    """
    cfg = _screen_config("manual")
    kline_map = _kline_map(["000001.SZ", "600000.SH"])
    # watcher 全 A proxy 返回这两只均可交易
    client = FakeWatcherClient(constituents={"000001.SZ", "600000.SH"})

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == {"000001.SZ", "600000.SH"}
    # manual → 000985.SH（中证全 A proxy）
    assert client.calls == [("000985.SH", "2022-06-15")]


def test_all_a_shares_universe_queries_watcher():
    """spec 011 P1-1：universe=all_a_shares 也强制查 watcher（000985.SH proxy）。"""
    cfg = _screen_config("all_a_shares")
    kline_map = _kline_map(["000001.SZ", "600000.SH"])
    client = FakeWatcherClient(constituents={"000001.SZ", "600000.SH"})

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == {"000001.SZ", "600000.SH"}
    assert client.calls == [("000985.SH", "2022-06-15")]


def test_custom_pool_universe_uses_all_a_proxy():
    """spec 011 P1-1：未知 pool（自定义池 ID）用 000985.SH 兜底 proxy。"""
    cfg = _screen_config("my_custom_pool_42")
    kline_map = _kline_map(["000001.SZ"])
    client = FakeWatcherClient(constituents={"000001.SZ"})

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == {"000001.SZ"}
    assert client.calls == [("000985.SH", "2022-06-15")]


# ============================================================
# spec 011 P1-1：失败即报错（不再降级）
# ============================================================

def test_watcher_client_none_raises():
    """spec 011 P1-1：watcher_client=None 抛 PIT_WATCHER_UNAVAILABLE（不再降级）。"""
    cfg = _screen_config("csi300")
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])

    with pytest.raises(BacktestError) as exc_info:
        RebalanceEngine._apply_universe_filter(
            cfg, kline_map, pd.Timestamp("2022-06-15"), None
        )

    assert "PIT_WATCHER_UNAVAILABLE" in str(exc_info.value)
    assert exc_info.value.error_code == "PIT_WATCHER_UNAVAILABLE"


def test_watcher_client_query_returns_empty_raises():
    """spec 011 P1-1：查询返回空集抛 PIT_CONSTITUENTS_EMPTY（不再降级保留全量）。"""
    cfg = _screen_config("csi300")
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])
    client = FakeWatcherClient(empty=True)

    with pytest.raises(BacktestError) as exc_info:
        RebalanceEngine._apply_universe_filter(
            cfg, kline_map, pd.Timestamp("2010-01-04"), client
        )

    assert "PIT_CONSTITUENTS_EMPTY" in str(exc_info.value)
    assert exc_info.value.error_code == "PIT_CONSTITUENTS_EMPTY"
    assert len(client.calls) == 1  # 确实发起了查询


def test_watcher_client_query_raises_propagates():
    """spec 011 P1-1：查询抛异常时包装为 PIT_QUERY_FAILED（不再降级）。"""
    cfg = _screen_config("csi300")
    kline_map = _kline_map(["000001.SZ"])
    client = FakeWatcherClient(raise_exc=ConnectionError("watcher 连接超时"))

    with pytest.raises(BacktestError) as exc_info:
        RebalanceEngine._apply_universe_filter(
            cfg, kline_map, pd.Timestamp("2022-06-15"), client
        )

    assert "PIT_QUERY_FAILED" in str(exc_info.value)
    assert exc_info.value.error_code == "PIT_QUERY_FAILED"
    assert "watcher 连接超时" in str(exc_info.value)


# ============================================================
# 集成：select_at_rebalance_date 调用 watcher_client
# ============================================================

def test_select_at_rebalance_date_invokes_watcher_for_csi300():
    """集成验证：select_at_rebalance_date 对 csi300 universe 会调 watcher_client。

    通过 MagicMock 拦截 get_constituents_at，确认被调用且参数正确
    （index_code=000300.SH, trade_date=调仓日）。
    """
    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "csi300", "stocks": None},
        "portfolio": {"top_n": 5},
    })
    kline_map = _kline_map(["000001.SZ", "600000.SH"])
    client = MagicMock()
    client.get_constituents_at.return_value = {"000001.SZ", "600000.SH"}

    RebalanceEngine().select_at_rebalance_date(
        screen_config=cfg,
        kline_map=kline_map,
        trading_date="2022-06-15",
        watcher_client=client,
    )

    client.get_constituents_at.assert_called_once_with("000300.SH", "2022-06-15")


def test_select_at_rebalance_date_invokes_watcher_for_manual():
    """集成验证：spec 011 P1-1 后 manual universe 也调 watcher（000985.SH proxy）。"""
    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "manual", "stocks": ["000001.SZ"]},
    })
    kline_map = _kline_map(["000001.SZ"])
    client = MagicMock()
    client.get_constituents_at.return_value = {"000001.SZ"}

    RebalanceEngine().select_at_rebalance_date(
        screen_config=cfg,
        kline_map=kline_map,
        trading_date="2022-06-15",
        watcher_client=client,
    )

    client.get_constituents_at.assert_called_once_with("000985.SH", "2022-06-15")
