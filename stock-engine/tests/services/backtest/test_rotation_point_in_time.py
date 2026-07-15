"""轮动范式 point-in-time 成分股过滤单元测试（spec 010-rotation-data-governance
Task 14.1 / AC-1 / AC-5 / AC-9）。

覆盖 ``RebalanceEngine._apply_universe_filter``（缺陷 A 修复）：

- AC-1：universe=csi300/csi500 时，按调仓日 point-in-time 成分股快照过滤候选池，
  剔除「调入日前 / 调出后」的标的（消除 lookahead bias）。
- AC-5：watcher_client=None + 指数 universe 时降级（不过滤、打 warning、不报错）。
- AC-9：universe=manual 时不调用 WatcherClient。
- watcher 查询返回空集时保留全量候选并打 warning。

测试不依赖真实 watcher HTTP：用 ``FakeWatcherClient`` 实现
``get_constituents_at`` 方法，或用 ``unittest.mock`` 拦截调用。
"""
from typing import Optional
from unittest.mock import MagicMock

import pandas as pd
import pytest

from services.backtest.rebalance_engine import RebalanceEngine
from services.strategy.models import ScreenConfigModel


# ============================================================
# 辅助
# ============================================================

def _screen_config(pool: str, point_in_time: Optional[bool] = None) -> ScreenConfigModel:
    """构造 4 层 screen_config（universe 层）。"""
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

    ``return_value`` 为 None 时模拟「未配置」（不应被调用方使用）；
    ``empty=True`` 时返回空集（模拟查询无快照）。
    """

    def __init__(self, constituents: Optional[set[str]] = None,
                 empty: bool = False):
        self._constituents = set() if empty else (constituents or set())
        self.calls: list[tuple[str, str]] = []

    def get_constituents_at(self, index_code: str, trade_date: str) -> set[str]:
        self.calls.append((index_code, trade_date))
        return set(self._constituents)


# ============================================================
# AC-1：csi300 point-in-time 过滤
# ============================================================

def test_csi300_point_in_time_filter():
    """AC-1：universe=csi300 时，不在成分股集合的 symbol 被过滤掉。"""
    cfg = _screen_config("csi300", point_in_time=True)
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])
    # 仅 000001.SZ / 600000.SH 在 2022-06-15 的 csi300 成分股中
    eligible = {"000001.SZ", "600000.SH"}
    client = FakeWatcherClient(constituents=eligible)

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == eligible
    assert "600519.SH" not in filtered
    # 调用参数：csi300 → 000300.SH
    assert client.calls == [("000300.SH", "2022-06-15")]


def test_csi500_point_in_time_filter():
    """AC-1：universe=csi500 时按 000905.SH 查询并过滤。"""
    cfg = _screen_config("csi500", point_in_time=True)
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
# AC-9：manual universe 不查接口
# ============================================================

def test_manual_universe_no_filter():
    """AC-9：universe=manual 时不调用 WatcherClient，候选池原样返回。"""
    cfg = _screen_config("manual")
    kline_map = _kline_map(["000001.SZ", "600000.SH"])
    client = FakeWatcherClient(constituents={"000001.SZ"})  # 即便有数据也不该被用

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == {"000001.SZ", "600000.SH"}
    assert client.calls == []  # 未被调用


def test_all_a_shares_universe_no_filter():
    """universe=all_a_shares（非指数池）也不触发 point-in-time 过滤。"""
    cfg = _screen_config("all_a_shares")
    kline_map = _kline_map(["000001.SZ", "600000.SH"])
    client = FakeWatcherClient(constituents={"000001.SZ"})

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), client
    )

    assert set(filtered.keys()) == {"000001.SZ", "600000.SH"}
    assert client.calls == []


# ============================================================
# AC-5：watcher_client=None 降级
# ============================================================

def test_watcher_client_none_degrade():
    """AC-5：watcher_client=None + universe=csi300 时跳过过滤、不报错（降级）。

    返回原 kline_map（向后兼容），仅依赖日志 warning 提示 lookahead bias 风险。
    """
    cfg = _screen_config("csi300", point_in_time=True)
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])

    # watcher_client=None 不应抛异常
    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2022-06-15"), None
    )

    # 降级：全量候选保留
    assert set(filtered.keys()) == {"000001.SZ", "600000.SH", "600519.SH"}


# ============================================================
# watcher 查询返回空集：保留全量候选
# ============================================================

def test_watcher_client_query_returns_empty():
    """watcher_client.get_constituents_at 返回空集时保留全量候选（降级）。

    场景：查询日早于 index_weight 表最早快照，或 watcher 暂无该指数数据。
    不强制过滤（否则会清空候选池导致回测静默不调仓），打 warning。
    """
    cfg = _screen_config("csi300", point_in_time=True)
    kline_map = _kline_map(["000001.SZ", "600000.SH", "600519.SH"])
    client = FakeWatcherClient(empty=True)

    filtered = RebalanceEngine._apply_universe_filter(
        cfg, kline_map, pd.Timestamp("2010-01-04"), client
    )

    # 空集 → 不过滤，保留全量
    assert set(filtered.keys()) == {"000001.SZ", "600000.SH", "600519.SH"}
    assert len(client.calls) == 1  # 确实发起了查询


# ============================================================
# 集成：select_at_rebalance_date 调用 watcher_client
# ============================================================

def test_select_at_rebalance_date_invokes_watcher_for_csi300():
    """集成验证：select_at_rebalance_date 对 csi300 universe 会调 watcher_client。

    通过 MagicMock 拦截 get_constituents_at，确认被调用且参数正确
    （index_code=000300.SH, trade_date=调仓日）。
    """
    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "csi300", "point_in_time": True, "stocks": None},
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


def test_select_at_rebalance_date_skips_watcher_for_manual():
    """集成验证：select_at_rebalance_date 对 manual universe 不调 watcher_client。"""
    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "manual", "stocks": ["000001.SZ"]},
    })
    kline_map = _kline_map(["000001.SZ"])
    client = MagicMock()

    RebalanceEngine().select_at_rebalance_date(
        screen_config=cfg,
        kline_map=kline_map,
        trading_date="2022-06-15",
        watcher_client=client,
    )

    client.get_constituents_at.assert_not_called()
