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
的 config 子树（剔除 watcher 元数据 category/tags），贴近真实场景。
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
    """读取模板，返回 config 子树（去掉 watcher 元数据 category/tags）。

    StrategyConfigModel 只接受 strategy_id/name/description/scope/
    screen_config/trading_config/backtest_config，多出的 category/tags
    会被 extra="forbid" 拒绝，故先剔除。
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


# ============================================================
# TR-2.1 / TR-2.2 模板 config 子树可被成功解析
# ============================================================

def test_dual_ma_template_parses():
    """TR-2.1：dual_ma.json 的 config 子树可被 StrategyConfigModel 成功解析。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("dual_ma"))
    assert cfg.name == "双均线策略"
    # trading_config.signals 在场
    assert cfg.trading_config is not None
    assert cfg.trading_config.signals is not None
    assert cfg.trading_config.signals.buy is not None
    # backtest_config 默认值与模板一致
    assert cfg.backtest_config.initial_cash == 100000


def test_low_pe_value_template_parses():
    """TR-2.2：low_pe_value.json 的 config 子树（多因子价值示例）可被成功解析。"""
    cfg = StrategyConfigModel.model_validate(_load_template_config("low_pe_value"))
    assert cfg.name == "低PE价值策略"
    # screen_config 在场（多因子选股）
    assert cfg.screen_config is not None
    assert cfg.screen_config.universe == "all_a_shares"
    assert cfg.screen_config.ranking.method == "single"
    assert cfg.screen_config.ranking.factor == "ROE_TTM"
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

def test_expression_node_value_form():
    """形态① ValueNode：{"value": ...}。"""
    cfg = StrategyConfigModel.model_validate(
        {
            "name": "t",
            "trading_config": {
                "signals": {
                    "buy": {
                        "operator": "AND",
                        "conditions": [
                            {
                                "type": "compare",
                                "left": {"factor": "RSI"},
                                "comparator": "<",
                                "right": {"value": 30},
                            }
                        ],
                    }
                },
                "position_sizing": {"method": "buy", "target": 100},
            },
        }
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
        {
            "name": "t",
            "trading_config": {
                "signals": {
                    "buy": {
                        "operator": "AND",
                        "conditions": [
                            {
                                "type": "compare",
                                "left": {
                                    "op": "+",
                                    "left": {"factor": "CLOSE"},
                                    "right": {"value": 0.1},
                                },
                                "comparator": ">",
                                "right": {"value": 10},
                            }
                        ],
                    }
                },
                "position_sizing": {"method": "buy", "target": 100},
            },
        }
    )
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf.left, OpNode)
    assert leaf.left.op == "+"
    assert isinstance(leaf.left.left, FactorNode)
    assert isinstance(leaf.left.right, ValueNode)


def test_expression_node_ref_form():
    """形态④ RefNode：{"ref": "entry_price"}（仅 trading_config 合法）。"""
    cfg = StrategyConfigModel.model_validate(
        {
            "name": "t",
            "trading_config": {
                "signals": {
                    "buy": {
                        "operator": "AND",
                        "conditions": [
                            {
                                "type": "compare",
                                "left": {"factor": "CLOSE"},
                                "comparator": ">",
                                "right": {"ref": "entry_price"},
                            }
                        ],
                    }
                },
                "position_sizing": {"method": "buy", "target": 100},
            },
        }
    )
    leaf = cfg.trading_config.signals.buy.conditions[0]
    assert isinstance(leaf.right, RefNode)
    assert leaf.right.ref == "entry_price"


# ============================================================
# TR-2.6 ConditionTree 递归嵌套（AND → OR → CompareLeaf）
# ============================================================

def test_condition_tree_recursive_nesting():
    """TR-2.6：AND(OR(compare, compare), compare) 递归嵌套可正确解析。"""
    cfg = StrategyConfigModel.model_validate(
        {
            "name": "t",
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
    """顶层 StrategyConfigModel 拒绝未知字段（category/tags）。"""
    base = _load_template_config("dual_ma")
    base["category"] = "TECHNICAL"
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate(base)


def test_extra_field_forbidden_in_factor_node():
    """FactorNode 拒绝未知字段。"""
    with pytest.raises(ValidationError):
        FactorNode.model_validate({"factor": "RSI", "unknown_extra": 1})


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
