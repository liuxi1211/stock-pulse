"""策略配置 Pydantic 模型单元测试（spec 004 Task 15 / TR-2.1~TR-2.6）。

覆盖点：
- TR-2.1 Schema §5.1 双均线示例（dual_ma.json 的 config 子树）可被
  ``StrategyConfigModel.model_validate`` 成功解析。
- TR-2.2 Schema §5.2 多因子价值示例（low_pe_value.json 的 config 子树）可被成功解析。
- TR-2.3 缺少必填字段（name 缺失）时 Pydantic 抛出 ``ValidationError``。
- TR-2.4 默认值正确（backtest_config.t_plus_one==True，lot_size==100，
  broker_profile=="cn_stock_miniqmt"，history_depth==60）。
- TR-2.5 ExpressionNode discriminated union 能识别 4 种形态
  （ValueNode/FactorNode/OpNode/RefNode）。
- TR-2.6 ConditionTree 递归嵌套（AND → OR → CompareLeaf）可正确解析。
- 额外：extra="forbid" 生效（未知字段被拒）；多输出因子 output_index 字段存在性。

测试数据来源：读取 ``stock-watcher/src/main/resources/strategies/templates/*.json``
的 config 子树（剔除 watcher 元数据 category/tags/scope），贴近真实场景。

spec 010 Task 14：watcher 模板已全部迁移为 4 层 screen_config 结构
（universe/factor/filter/portfolio）。新增对 4 层对象字段的断言。
"""
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from services.strategy.models import (
    StrategyConfigModel,
    BacktestConfigModel,
    ConditionTree,
    CompareLeaf,
    FactorNode,
    OpNode,
    RefNode,
    ValueNode,
    ScreenConfigModel,
)


# ============================================================
# 辅助：读取 watcher 模板的 config 子树
# ============================================================

def _templates_dir() -> Path:
    """定位 ``stock-watcher/.../templates`` 目录。

    test 文件位于 ``stock-engine/tests/services/strategy/test_models.py``：
    parents[0]=strategy, [1]=services, [2]=tests, [3]=stock-engine,
    [4]=stock-pulse → 拼到 stock-watcher/templates。
    """
    return (
        Path(__file__).resolve().parents[4]
        / "stock-watcher"
        / "src"
        / "main"
        / "resources"
        / "strategies"
        / "templates"
    )


def _load_template_config(name: str) -> dict:
    """读取模板，返回 config 子树（去掉 watcher 元数据 category/tags/scope）。

    StrategyConfigModel 只接受 strategy_id/name/description/screen_config/
    trading_config/backtest_config，多出的 category/tags/scope 会被
    extra="forbid" 拒绝，故先剔除。
    """
    p = _templates_dir() / f"{name}.json"
    data = json.loads(p.read_text(encoding="utf-8"))
    allowed = {
        "name",
        "description",
        "screen_config",
        "trading_config",
        "backtest_config",
    }
    return {k: v for k, v in data.items() if k in allowed}


def _manual_universe(stocks):
    """4 层 universe 对象（manual 池）。"""
    return {"pool": "manual", "point_in_time": None, "stocks": stocks}


# ============================================================
# TR-2.1 / TR-2.2 模板 config 子树可被成功解析
# ============================================================

def test_dual_ma_template_parses():
    """TR-2.1：dual_ma.json 的 config 子树（4 层结构）可被成功解析。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("dual_ma"))
    assert cfg.name == "双均线策略"
    # trading_config.signals 在场
    assert cfg.trading_config is not None
    assert cfg.trading_config.signals is not None
    assert cfg.trading_config.signals.buy is not None
    # screen_config.universe 为 4 层对象
    assert cfg.screen_config is not None
    assert cfg.screen_config.universe.pool == "manual"
    assert cfg.screen_config.universe.stocks == ["510300.SH"]
    # backtest_config 默认值与模板一致
    assert cfg.backtest_config.initial_cash == 100000


def test_low_pe_value_template_parses():
    """TR-2.2：low_pe_value.json 的 config 子树（4 层结构）可被成功解析。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("low_pe_value"))
    assert cfg.name == "低PE价值策略"
    # screen_config 在场（4 层结构）
    assert cfg.screen_config is not None
    # pool=csi500（可回测的宽基池，spec 010 I1：不再用被回测拒绝的 all_a_shares）
    assert cfg.screen_config.universe.pool == "csi500"
    assert cfg.screen_config.universe.point_in_time is True
    # factor 层（原 ranking）
    assert cfg.screen_config.factor is not None
    assert cfg.screen_config.factor.method == "single"
    assert cfg.screen_config.factor.factor == "ROE_TTM"
    # filter 层（conditions + 静态过滤合并）
    assert cfg.screen_config.filter is not None
    assert cfg.screen_config.filter.conditions is not None
    assert cfg.screen_config.filter.exclude_st is True
    # portfolio 层（原 top_n）
    assert cfg.screen_config.portfolio is not None
    assert cfg.screen_config.portfolio.top_n == 30
    # trading_config.rebalance 在场（调仓范式）
    assert cfg.trading_config is not None
    assert cfg.trading_config.rebalance is not None
    assert cfg.trading_config.rebalance.frequency == "monthly"


# ============================================================
# TR-2.3 必填字段缺失抛 ValidationError
# ============================================================

def test_missing_name_raises_validation_error():
    """TR-2.3：缺少必填字段 name 时 Pydantic 抛 ValidationError。"""
    base = _load_template_config("dual_ma")
    base.pop("name")
    with pytest.raises(ValidationError) as ei:
        StrategyConfigModel.model_validate(base)
    # 确认错误定位在 name
    locs = [err["loc"] for err in ei.value.errors()]
    assert ("name",) in locs


# ============================================================
# TR-2.4 默认值正确
# ============================================================

def test_backtest_config_defaults():
    """TR-2.4：backtest_config 默认值（t_plus_one/lot_size/broker_profile/history_depth）。"""
    bc = BacktestConfigModel()
    assert bc.t_plus_one is True
    assert bc.lot_size == 100
    assert bc.broker_profile == "cn_stock_miniqmt"
    assert bc.history_depth == 60
    # 其他默认
    assert bc.volume_limit_pct == 0.25
    assert bc.timezone == "Asia/Shanghai"
    assert bc.show_progress is False
    assert bc.strict_strategy_params is True


def test_defaults_via_top_level_when_backtest_config_omitted():
    """顶层模型 backtest_config 可缺省（Optional）。"""
    cfg = StrategyConfigModel.model_validate({"name": "x"})
    assert cfg.backtest_config is None


# ============================================================
# TR-2.5 ExpressionNode discriminated union 4 形态识别
# ============================================================

def _signals_config(condition):
    """构造含单个 buy condition 的最小配置（4 层 screen_config）。

    ``condition`` 可以是 CompareLeaf dict 或嵌套 ConditionTree dict，
    直接作为 buy 根条件树的唯一子项。
    """
    return {
        "name": "t",
        "screen_config": {"universe": _manual_universe(["000001.SZ"])},
        "trading_config": {
            "signals": {
                "buy": {
                    "operator": "AND",
                    "conditions": [condition],
                }
            },
            "position_sizing": {"method": "buy", "target": 100},
        },
    }


def test_expression_node_value_form():
    """形态① ValueNode：{"value": ...}。"""
    cfg = StrategyConfigModel.model_validate(
        _signals_config({
            "type": "compare",
            "left": {"factor": "RSI"},
            "comparator": "<",
            "right": {"value": 30},
        })
    )
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf, CompareLeaf)
    assert isinstance(leaf.right, ValueNode)
    assert leaf.right.value == 30


def test_expression_node_factor_form():
    """形态② FactorNode：{"factor": ..., "params": ...}。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("dual_ma"))
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf, CompareLeaf)
    assert isinstance(leaf.left, FactorNode)
    assert leaf.left.factor == "MA"
    assert leaf.left.params == {"timeperiod": 5}
    assert isinstance(leaf.right, FactorNode)
    assert leaf.right.factor == "MA"


def test_expression_node_op_form():
    """形态③ OpNode：{"op": "+", "left": ..., "right": ...}（递归）。"""
    cfg = StrategyConfigModel.model_validate(
        _signals_config({
            "type": "compare",
            "left": {
                "op": "+",
                "left": {"factor": "CLOSE"},
                "right": {"value": 0.1},
            },
            "comparator": ">",
            "right": {"value": 10},
        })
    )
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf.left, OpNode)
    assert leaf.left.op == "+"
    assert isinstance(leaf.left.left, FactorNode)
    assert isinstance(leaf.left.right, ValueNode)


def test_expression_node_ref_form():
    """形态④ RefNode：{"ref": "entry_price"}（仅 trading_config 合法）。"""
    cfg = StrategyConfigModel.model_validate(
        _signals_config({
            "type": "compare",
            "left": {"factor": "CLOSE"},
            "comparator": ">",
            "right": {"ref": "entry_price"},
        })
    )
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf.right, RefNode)
    assert leaf.right.ref == "entry_price"


# ============================================================
# TR-2.6 ConditionTree 递归嵌套（AND → OR → CompareLeaf）
# ============================================================

def test_condition_tree_recursive_nesting():
    """TR-2.6：AND(OR(compare, compare), compare) 递归嵌套可正确解析。

    buy 根 = AND；其 conditions[0] = 嵌套 OR（含两个 compare 叶子），
    conditions[1] = compare 叶子。
    """
    cfg = StrategyConfigModel.model_validate(
        {
            "name": "t",
            "screen_config": {"universe": _manual_universe(["000001.SZ"])},
            "trading_config": {
                "signals": {
                    "buy": {
                        "operator": "AND",
                        "conditions": [
                            {
                                "operator": "OR",
                                "conditions": [
                                    {
                                        "type": "compare",
                                        "left": {"factor": "RSI"},
                                        "comparator": "<",
                                        "right": {"value": 30},
                                    },
                                    {
                                        "type": "compare",
                                        "left": {"factor": "MACD", "output_index": 2},
                                        "comparator": ">",
                                        "right": {"value": 0},
                                    },
                                ],
                            },
                            {
                                "type": "compare",
                                "left": {"factor": "CLOSE"},
                                "comparator": ">",
                                "right": {"value": 5},
                            },
                        ],
                    }
                },
                "position_sizing": {"method": "buy", "target": 100},
            },
        }
    )
    root: ConditionTree = cfg.trading_config.signals.buy
    assert root.operator == "AND"
    # 第一个子节点是嵌套 OR
    sub = root.conditions[0]
    assert isinstance(sub, ConditionTree)
    assert sub.operator == "OR"
    assert isinstance(sub.conditions[0], CompareLeaf)
    # 第二个子节点是叶子
    assert isinstance(root.conditions[1], CompareLeaf)


# ============================================================
# extra="forbid" 生效
# ============================================================

def test_extra_field_forbidden_at_top_level():
    """顶层 StrategyConfigModel 拒绝未知字段（category）。"""
    base = _load_template_config("dual_ma")
    base["category"] = "TECHNICAL"
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate(base)


def test_extra_field_forbidden_in_factor_node():
    """FactorNode 拒绝未知字段。"""
    with pytest.raises(ValidationError):
        FactorNode.model_validate({"factor": "RSI", "unknown_extra": 1})


def test_extra_field_forbidden_in_universe_model():
    """UniverseModel 拒绝未知字段（4 层 universe 对象）。"""
    with pytest.raises(ValidationError):
        ScreenConfigModel.model_validate({
            "universe": {"pool": "manual", "stocks": ["000001.SZ"], "unknown": 1}
        })


def test_extra_field_forbidden_for_old_flat_screen_config():
    """旧 5 字段扁平结构（顶层 ranking/top_n/conditions）→ Pydantic 拒绝。

    spec 010 BREAKING：screen_config 只接受 4 层字段
    （universe/factor/filter/portfolio），旧字段被 extra="forbid" 拒绝。
    """
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate({
            "name": "t",
            "screen_config": {
                "universe": _manual_universe(["000001.SZ"]),
                "ranking": {"method": "single", "factor": "RSI", "order": "desc"},
                "top_n": 5,
            },
        })


# ============================================================
# 多输出因子 output_index 字段存在性
# ============================================================

def test_macd_output_index_present():
    """MACD 多输出因子带 output_index 可被 FactorNode 承载。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("macd_short"))
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf.left, FactorNode)
    assert leaf.left.factor == "MACD"
    assert leaf.left.output_index == 2


def test_factor_node_output_index_optional_for_single_output():
    """单输出因子 output_index 可省（默认 None）。"""
    n = FactorNode.model_validate({"factor": "RSI"})
    assert n.output_index is None
