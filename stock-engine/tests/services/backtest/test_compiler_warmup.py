"""compiler warmup 推断测试（spec 014 工作流 C / spec 012 Deferred#2）。

验证 transform.window 被纳入 rebalance warmup 推断：
- 含 transform 的因子，warmup = 因子窗口 + transform.window；
- 无 transform 时行为不变（回归）；
- FactorSpec 的 transform 去重不丢失。
"""
from services.backtest.compiler import (
    FactorSpec,
    _collect_factor_specs,
    _infer_rebalance_warmup,
)
from services.strategy.models import StrategyConfigModel


def _config_with_filter_conditions(conditions):
    """构造最小合法轮动策略 config（rebalance + screen.filter.conditions）。"""
    return StrategyConfigModel.model_validate({
        "name": "test",
        "screen_config": {
            "universe": {"pool": "csi300"},
            "factor": {"method": "disabled"},
            "filter": {"conditions": conditions},
            "portfolio": {"top_n": 10},
        },
        "trading_config": {
            "rebalance": {"frequency": "monthly"},
        },
    })


# ---------- FactorSpec transform 行为 ----------

def test_factor_spec_transform_none_default():
    spec = FactorSpec("MA", {"timeperiod": 5}, 0)
    assert spec.transform is None


def test_factor_spec_transform_captured():
    spec = FactorSpec("MA", {"timeperiod": 5}, 0, {"type": "ma", "window": 20})
    assert spec.transform == {"type": "ma", "window": 20}


def test_factor_spec_cache_key_distinct_with_transform():
    """带 transform 与不带 transform 的同因子应有不同 cache_key（避免互相覆盖）。"""
    s1 = FactorSpec("MA", {"timeperiod": 5}, 0)
    s2 = FactorSpec("MA", {"timeperiod": 5}, 0, {"type": "ma", "window": 20})
    assert s1.cache_key != s2.cache_key
    assert s2.cache_key.endswith("__ma20")


def test_factor_spec_eq_hash_with_transform():
    """eq/hash 含 transform，不同 transform 视为不同 spec。"""
    s1 = FactorSpec("MA", {"timeperiod": 5}, 0, {"type": "ma", "window": 20})
    s2 = FactorSpec("MA", {"timeperiod": 5}, 0, {"type": "ma", "window": 20})
    s3 = FactorSpec("MA", {"timeperiod": 5}, 0, {"type": "std", "window": 20})
    assert s1 == s2
    assert hash(s1) == hash(s2)
    assert s1 != s3


# ---------- _collect_factor_specs 捕获 transform ----------

def test_collect_specs_captures_transform():
    from services.strategy.models import FactorNode
    node = FactorNode(factor="MA", params={"timeperiod": 5},
                      transform={"type": "ma", "window": 20})
    acc = {}
    _collect_factor_specs(node, acc)
    assert len(acc) == 1
    spec = list(acc.values())[0]
    assert spec.transform == {"type": "ma", "window": 20}


def test_collect_specs_dedup_keeps_both_transform_and_plain():
    """同因子一个带 transform 一个不带 → 两个不同 spec（cache_key 不同）。"""
    from services.strategy.models import FactorNode, CompareLeaf
    leaf1 = CompareLeaf(left=FactorNode(factor="MA", params={"timeperiod": 5}),
                        comparator="<", right={"value": 100})
    leaf2 = CompareLeaf(left=FactorNode(factor="MA", params={"timeperiod": 5},
                                        transform={"type": "ma", "window": 20}),
                        comparator="<", right={"value": 100})
    acc = {}
    _collect_factor_specs(leaf1, acc)
    _collect_factor_specs(leaf2, acc)
    assert len(acc) == 2


# ---------- _infer_rebalance_warmup 纳入 transform.window ----------

def test_infer_warmup_without_transform_unchanged():
    """无 transform 时 warmup = 因子窗口（回归）。"""
    cfg = _config_with_filter_conditions({
        "operator": "AND",
        "conditions": [{
            "type": "compare",
            "left": {"factor": "MA", "params": {"timeperiod": 20}},
            "comparator": "<", "right": {"value": 100},
        }],
    })
    w = _infer_rebalance_warmup(cfg)
    # MA(timeperiod=20) → _infer_factor_window 返回 20
    assert w == 20


def test_infer_warmup_with_transform_window():
    """含 transform 时 warmup = 因子窗口 + transform.window。"""
    cfg = _config_with_filter_conditions({
        "operator": "AND",
        "conditions": [{
            "type": "compare",
            "left": {"factor": "MA", "params": {"timeperiod": 20},
                     "transform": {"type": "ma", "window": 20}},
            "comparator": "<", "right": {"value": 100},
        }],
    })
    w = _infer_rebalance_warmup(cfg)
    # MA(20) + transform.window(20) = 40
    assert w == 40


def test_infer_warmup_transform_dominates_max():
    """多个因子时取 max；含大 transform.window 的因子应主导。"""
    cfg = _config_with_filter_conditions({
        "operator": "AND",
        "conditions": [
            {"type": "compare",
             "left": {"factor": "MA", "params": {"timeperiod": 5}},
             "comparator": "<", "right": {"value": 100}},
            {"type": "compare",
             "left": {"factor": "MA", "params": {"timeperiod": 10},
                      "transform": {"type": "std", "window": 30}},
             "comparator": "<", "right": {"value": 100}},
        ],
    })
    w = _infer_rebalance_warmup(cfg)
    # max(MA(5)=5, MA(10)+30=40) = 40
    assert w == 40


def test_infer_warmup_no_rebalance_returns_zero():
    """非轮动范式返回 0。"""
    cfg = StrategyConfigModel.model_validate({
        "name": "test",
        "screen_config": {"universe": {"pool": "manual", "stocks": ["S1"]}},
        "trading_config": {"signals": {"buy": None}},
    })
    assert _infer_rebalance_warmup(cfg) == 0
