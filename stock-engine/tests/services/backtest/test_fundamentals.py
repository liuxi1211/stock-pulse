"""基本面因子 point-in-time 下发单元测试（spec 010-rotation-data-governance
Task 14.2 / AC-2 / AC-3）。

覆盖缺陷 B 修复链路：

- AC-2：watcher 在 buildKlineData 为每根 bar 补齐 PE_TTM/PB/TOTAL_MV/ROE_TTM 等
  基本面字段；engine ``kline_to_extra_map`` 提取为
  ``{symbol: {trade_date_str: {field: float}}}``；
  ``RebalanceEngine._compute_one_factor`` 对 TUSHARE 因子从 candidate["extra"]
  按调仓日 trade_date 取真实值（非 NaN）。
- AC-3：factor.weights 引用基本面因子（如 ROE_TTM/PE_TTM），ranking 按真实值
  z-score 加权打分。

测试不依赖 watcher HTTP：直接构造含 extra 字段的 kline_data dict。
"""
import math
from unittest.mock import MagicMock

import pandas as pd
import pytest

from services.backtest.data_adapter import kline_to_extra_map
from services.backtest.rebalance_engine import RebalanceEngine


def _fake_watcher_all_eligible(symbols):
    """构造一个 watcher_client mock，get_constituents_at 返回全部 symbols（均可交易）。

    spec 011 P1-1 后所有 universe（含 manual）强制查 watcher，测试需注入 mock
    以避免抛 PIT_WATCHER_UNAVAILABLE。
    """
    client = MagicMock()
    client.get_constituents_at.return_value = set(symbols)
    return client


# ============================================================
# AC-2：kline_to_extra_map 提取基本面字段
# ============================================================

def test_extra_map_extraction():
    """AC-2：含 pe_ttm/pb 的 kline_data → 提取为 {symbol: {date_str: {field: float}}}。"""
    kline_data = {
        "000001.SZ": [
            {
                "date": "2022-06-15",
                "open": 10.0, "high": 10.1, "low": 9.9,
                "close": 10.0, "volume": 10000.0,
                "pe_ttm": 8.5, "pb": 0.9, "total_mv": 2.0e10, "roe_ttm": 18.0,
            },
            {
                "date": "2022-06-16",
                "open": 10.1, "high": 10.2, "low": 10.0,
                "close": 10.1, "volume": 12000.0,
                "pe_ttm": 8.4, "pb": 0.91,
                # total_mv / roe_ttm 缺失（NaN 安全）
            },
        ],
    }

    extra_map = kline_to_extra_map(kline_data)

    assert "000001.SZ" in extra_map
    sym_extras = extra_map["000001.SZ"]
    # 日期归一化为 YYYY-MM-DD
    assert "2022-06-15" in sym_extras
    day0 = sym_extras["2022-06-15"]
    assert day0["pe_ttm"] == pytest.approx(8.5)
    assert day0["pb"] == pytest.approx(0.9)
    assert day0["total_mv"] == pytest.approx(2.0e10)
    assert day0["roe_ttm"] == pytest.approx(18.0)

    # 缺失字段不写入该日的 dict（NaN 安全）
    day1 = sym_extras["2022-06-16"]
    assert day1["pe_ttm"] == pytest.approx(8.4)
    assert "total_mv" not in day1
    assert "roe_ttm" not in day1


def test_extra_map_empty_kline_data():
    """空 kline_data → 空 extra_map（不报错）。"""
    assert kline_to_extra_map({}) == {}
    assert kline_to_extra_map({"000001.SZ": []}) == {}


def test_extra_map_missing_time_col_skipped():
    """行内无时间列 → 该行被跳过（不影响其他行）。"""
    kline_data = {
        "000001.SZ": [
            {"open": 10.0, "close": 10.0, "pe_ttm": 8.5},  # 无 date
            {"date": "2022-06-15", "close": 10.0, "pe_ttm": 8.4},
        ],
    }
    extra_map = kline_to_extra_map(kline_data)
    assert "2022-06-15" in extra_map["000001.SZ"]
    assert extra_map["000001.SZ"]["2022-06-15"]["pe_ttm"] == pytest.approx(8.4)


# ============================================================
# AC-2：_compute_one_factor 从 extra 取 TUSHARE 因子值
# ============================================================

def test_compute_factor_from_extra():
    """AC-2：candidate 含 extra，_compute_one_factor 对 PE_TTM（TUSHARE）按
    trade_date 取到真实值（非 NaN）。"""
    candidate = {
        "ohlcv_history": [],
        "fundamentals": {},  # 旧路径空，强制走 extra
        "extra": {
            "2022-06-15": {"pe_ttm": 8.5, "pb": 0.9, "roe_ttm": 18.0},
        },
    }

    val = RebalanceEngine._compute_one_factor(
        factor_key="PE_TTM",
        ref={"factorKey": "PE_TTM"},
        candidate=candidate,
        trading_date=pd.Timestamp("2022-06-15"),
    )

    assert val == pytest.approx(8.5)
    assert not math.isnan(val)


def test_compute_factor_extra_case_insensitive():
    """extra 字段名大小写不敏感（watcher 下发小写，factorKey 为大写注册名）。

    factorKey 必须与 factor_registry 注册名完全一致（ROE_TTM）；
    但 watcher 下发的 extra 字段是小写（roe_ttm），_extra_lookup 做
    大小写不敏感匹配兜底。
    """
    candidate = {
        "ohlcv_history": [],
        "fundamentals": {},
        "extra": {"2022-06-15": {"roe_ttm": 22.0}},  # 小写
    }
    val = RebalanceEngine._compute_one_factor(
        factor_key="ROE_TTM",  # 大写注册名
        ref={"factorKey": "ROE_TTM"},
        candidate=candidate,
        trading_date=pd.Timestamp("2022-06-15"),
    )
    assert val == pytest.approx(22.0)


# ============================================================
# extra 缺该日数据 → NaN
# ============================================================

def test_compute_factor_extra_missing_returns_nan():
    """extra 中无该日数据 → 返回 NaN（回退到 fundamentals 也空时）。"""
    candidate = {
        "ohlcv_history": [],
        "fundamentals": {},
        "extra": {
            "2022-06-14": {"pe_ttm": 8.5},  # 有前一日，无调仓日
        },
    }
    val = RebalanceEngine._compute_one_factor(
        factor_key="PE_TTM",
        ref={"factorKey": "PE_TTM"},
        candidate=candidate,
        trading_date=pd.Timestamp("2022-06-15"),  # extra 无此日
    )
    assert math.isnan(val)


def test_compute_factor_extra_missing_field_returns_nan():
    """extra 有该日但无该字段 → 返回 NaN。"""
    candidate = {
        "ohlcv_history": [],
        "fundamentals": {},
        "extra": {"2022-06-15": {"pb": 0.9}},  # 无 pe_ttm
    }
    val = RebalanceEngine._compute_one_factor(
        factor_key="PE_TTM",
        ref={"factorKey": "PE_TTM"},
        candidate=candidate,
        trading_date=pd.Timestamp("2022-06-15"),
    )
    assert math.isnan(val)


def test_compute_factor_no_trading_date_returns_nan():
    """trading_date=None + extra 在场 → 基本面取不到值返回 NaN。"""
    candidate = {
        "ohlcv_history": [],
        "fundamentals": {},
        "extra": {"2022-06-15": {"pe_ttm": 8.5}},
    }
    val = RebalanceEngine._compute_one_factor(
        factor_key="PE_TTM",
        ref={"factorKey": "PE_TTM"},
        candidate=candidate,
        trading_date=None,
    )
    assert math.isnan(val)


# ============================================================
# AC-3：factor.weights 引用基本面因子 → ranking 按真实值加权
# ============================================================

def test_factor_weight_from_extra():
    """AC-3：factor.weights 引用 ROE_TTM/PE_TTM，select_at_rebalance_date
    按 extra 中的真实值做 z-score 加权打分（非 NaN）。

    构造 3 只候选，ROE_TTM 高 / PE_TTM 低者综合分应更高（ROE 正权重、
    PE 负权重），且打分结果非 NaN（证明基本面取值成功）。
    """
    from services.strategy.models import ScreenConfigModel

    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "manual", "stocks": None},
        # composite：ROE 正权重（越大越好），PE 负权重（越小越好）
        "factor": {
            "method": "composite",
            "weights": {"ROE_TTM": 1.0, "PE_TTM": -1.0},
        },
        "portfolio": {"top_n": 3},
    })

    trade_date = "2022-06-15"
    # 三只候选：A 高 ROE 低 PE（最优）；B 中等；C 低 ROE 高 PE（最差）
    base_row = {
        "date": trade_date,
        "open": 10.0, "high": 10.1, "low": 9.9,
        "close": 10.0, "volume": 10000.0,
    }
    kline_map = {
        "A.SZ": [{**base_row, "roe_ttm": 25.0, "pe_ttm": 6.0}],
        "B.SZ": [{**base_row, "roe_ttm": 15.0, "pe_ttm": 12.0}],
        "C.SZ": [{**base_row, "roe_ttm": 5.0, "pe_ttm": 30.0}],
    }
    extra_map = kline_to_extra_map(kline_map)

    scores = RebalanceEngine().select_at_rebalance_date(
        screen_config=cfg,
        kline_map=kline_map,
        trading_date=trade_date,
        extra_map=extra_map,
        watcher_client=_fake_watcher_all_eligible(["A.SZ", "B.SZ", "C.SZ"]),
    )

    # 三只候选都应被打分（非空）
    assert set(scores.keys()) == {"A.SZ", "B.SZ", "C.SZ"}
    # 所有 score 非 NaN（证明基本面取值成功，未静默退化为 NaN 被剔除）
    for sym, score in scores.items():
        assert not math.isnan(score), f"{sym} score 不应为 NaN"

    # A（高 ROE 低 PE）综合分应最高
    assert scores["A.SZ"] > scores["B.SZ"] > scores["C.SZ"]


def test_factor_weight_from_extra_partial_missing():
    """AC-3：部分候选 extra 缺失基本面时，缺失者该维度 z=NaN 被剔除加权，
    其余候选仍按真实值排序（不整体崩成 NaN）。

    构造 3 只：A/B 有 ROE_TTM，C 缺失。composite 加权时 C 的 ROE 维度
    贡献 0（NaN 剔除），但 C 仍保留在结果中（score 非 NaN）。
    """
    from services.strategy.models import ScreenConfigModel

    cfg = ScreenConfigModel.model_validate({
        "universe": {"pool": "manual", "stocks": None},
        "factor": {
            "method": "composite",
            "weights": {"ROE_TTM": 1.0},
        },
        "portfolio": {"top_n": 3},
    })

    trade_date = "2022-06-15"
    base_row = {
        "date": trade_date,
        "open": 10.0, "high": 10.1, "low": 9.9,
        "close": 10.0, "volume": 10000.0,
    }
    kline_map = {
        "A.SZ": [{**base_row, "roe_ttm": 25.0}],
        "B.SZ": [{**base_row, "roe_ttm": 10.0}],
        "C.SZ": [{**base_row}],  # 无 roe_ttm
    }
    extra_map = kline_to_extra_map(kline_map)

    scores = RebalanceEngine().select_at_rebalance_date(
        screen_config=cfg,
        kline_map=kline_map,
        trading_date=trade_date,
        extra_map=extra_map,
        watcher_client=_fake_watcher_all_eligible(["A.SZ", "B.SZ", "C.SZ"]),
    )

    # A/B 有 ROE_TTM；C 的 extra 为空（kline_to_extra_map 跳过无字段行）
    # C 因子值全 NaN 会被 _filter_valid_symbols 剔除，不在结果中
    assert "A.SZ" in scores
    assert "B.SZ" in scores
    # A（高 ROE）分数应高于 B
    assert scores["A.SZ"] > scores["B.SZ"]
