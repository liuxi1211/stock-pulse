import math

from services.screener.factor_precompute import collect_factor_refs, precompute_factors


def _flat_close_history(closes):
    """构造 ohlcv_history，close=closes[i]，open/high/low=close，volume=1000。"""
    return [
        {"date": f"2024-01-{i + 1:02d}", "open": c, "high": c, "low": c, "close": c, "volume": 1000}
        for i, c in enumerate(closes)
    ]


def test_precompute_technical_transform_ma():
    # CLOSE 序列 [1..10]，MA(window=3) 末 3 个 [8,9,10] 均值 = 9.0
    closes = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "CLOSE", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    # 技术面 CLOSE 用签名作 key（CLOSE 无 params/output_index → CLOSE__ma3）
    val = result["S1"]["CLOSE__ma3"]
    assert val == 9.0


def test_precompute_technical_no_transform_unchanged():
    closes = [1.0, 2.0, 3.0, 4.0]
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "CLOSE"}, "comparator": "<", "right": {"value": 100}}
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["CLOSE"] == 4.0  # 末值


def test_collect_refs_without_transform():
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "RSI"}, "comparator": "<", "right": {"value": 30}}
        ],
    }
    refs = collect_factor_refs(tree)
    assert refs == [{"factorKey": "RSI"}]


def test_collect_refs_with_transform():
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}},
                "comparator": "<",
                "right": {"value": 30},
            }
        ],
    }
    refs = collect_factor_refs(tree)
    assert len(refs) == 1
    assert refs[0]["factorKey"] == "PE_TTM"
    assert refs[0]["transform"] == {"type": "ma", "window": 20}


def test_collect_refs_dedup_keeps_both_transform_and_plain():
    # 同一因子：一个带 transform 一个不带 → 两个不同规格
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "PE_TTM"}, "comparator": "<", "right": {"value": 30}},
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}},
                "comparator": "<",
                "right": {"value": 30},
            },
        ],
    }
    refs = collect_factor_refs(tree)
    assert len(refs) == 2


def test_precompute_fundamental_transform_ma_from_extra():
    # PE_TTM 逐 bar：[10,12,14,16,18,20]，ma(window=3) 末 3 个 [16,18,20] 均值=18.0
    extras = {
        "2024-01-01": {"pe_ttm": 10.0},
        "2024-01-02": {"pe_ttm": 12.0},
        "2024-01-03": {"pe_ttm": 14.0},
        "2024-01-04": {"pe_ttm": 16.0},
        "2024-01-05": {"pe_ttm": 18.0},
        "2024-01-06": {"pe_ttm": 20.0},
    }
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {}, "extra": extras}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["PE_TTM__ma3"] == 18.0


def test_precompute_fundamental_transform_nan_without_extra():
    # 选股中心只有 fundamentals 快照，无逐 bar extra → transform NaN
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {"PE_TTM": 15.0}}}
    result = precompute_factors(tree, candidates)
    import math

    assert math.isnan(result["S1"]["PE_TTM__ma3"])


def test_precompute_fundamental_no_transform_unchanged():
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "PE_TTM"}, "comparator": "<", "right": {"value": 100}}
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {"PE_TTM": 15.0}}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["PE_TTM"] == 15.0


# ============================================================
# spec 014 工作流 D：多输出因子 + transform（spec 012 Deferred#3）
# ============================================================

def test_precompute_multi_output_transform():
    """MACD output_index=0（DIF）+ transform ma(5)：经 output_index 降维后聚合可用。

    证明 compute_single(output_index=0) 已把 MACD 降维成 DIF 单序列，
    aggregate_series 对其末 5 个值取均值。
    """
    # 60 bar 单调递增 close，足够 MACD(12,26,9) 预热（lookback=slowperiod+signalperiod-2=33）
    closes = [10.0 + i * 0.5 for i in range(60)]
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {
                    "factor": "MACD",
                    "params": {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9},
                    "output_index": 0,
                    "transform": {"type": "ma", "window": 5},
                },
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    # key 形如 MACD(fastperiod=12,slowperiod=26,signalperiod=9)#0__ma5
    keys = [k for k in result["S1"] if k.startswith("MACD") and k.endswith("__ma5")]
    assert len(keys) == 1
    val = result["S1"][keys[0]]
    assert not math.isnan(val), "MACD 经 output_index 降维后 transform 应得到有效数值"


def test_precompute_multi_output_transform_distinct_from_plain():
    """多输出因子：当日值（无 transform）与窗口聚合值（有 transform）应并存不冲突。"""
    closes = [10.0 + i * 0.5 for i in range(60)]
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {
                    "factor": "MACD",
                    "params": {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9},
                    "output_index": 0,
                },
                "comparator": "<",
                "right": {"value": 100},
            },
            {
                "type": "compare",
                "left": {
                    "factor": "MACD",
                    "params": {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9},
                    "output_index": 0,
                    "transform": {"type": "ma", "window": 5},
                },
                "comparator": "<",
                "right": {"value": 100},
            },
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    keys = list(result["S1"].keys())
    macd_keys = [k for k in keys if k.startswith("MACD")]
    assert len(macd_keys) == 2, f"应有 2 个 MACD key（当日值 + 聚合值），实际 {macd_keys}"
    # 一个带 __ma5 后缀，一个不带
    assert any(k.endswith("__ma5") for k in macd_keys)
    assert any(not k.endswith("__ma5") for k in macd_keys)
