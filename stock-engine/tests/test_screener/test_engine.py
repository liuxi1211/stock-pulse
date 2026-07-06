"""ConditionEngine / validate_cross_section / factor_signature 单元测试。

覆盖 spec 003 阶段 3 Task 12：
- 12.1 AC-1 基本求值（AND/OR 嵌套 + 算术节点）
- 12.1 AC-2 NaN 安全（缺失因子键 → 比较返回 False）
- 12.1 算术除零降级（/0 → 0.0）
- 12.1 ExpressionNode 4 形态（value/factor/arith/ref）
- 12.2 AC-3 截面禁用项（cross_up/cross_down 与 {ref} → ScreenTimeSeriesForbiddenError）
- 12.2 factor_signature 稳定性（params 顺序无关）
"""
import math

import pytest

from core.exceptions import ScreenTimeSeriesForbiddenError
from models.schemas.condition import ExpressionNode
from services.screener.engine import (
    ConditionEngine,
    EvalContext,
    factor_signature,
    validate_cross_section,
)


# ============================================================
# AC-1 基本求值：AND/OR 嵌套 + 算术节点
# ============================================================

def test_evaluate_and_or_nested_with_arith():
    """AC-1：构造 (PE < 20) AND (ROE > 10 OR ROE > 5)，预填基本面，断言求值与手算一致。"""
    engine = ConditionEngine()
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM"},
                "comparator": "<",
                "right": {"value": 20},
            },
            {
                "operator": "OR",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "ROE_TTM"},
                        "comparator": ">",
                        "right": {"value": 10},
                    },
                    {
                        "type": "compare",
                        "left": {"factor": "ROE_TTM"},
                        "comparator": ">",
                        "right": {"value": 5},
                    },
                ],
            },
        ],
    }

    # PE=8 / ROE=18 → (8<20=True) AND (18>10=True OR ...) = True
    ctx_hit = EvalContext(
        symbol="S1", fundamentals={"PE_TTM": 8.0, "ROE_TTM": 18.0}
    )
    assert engine.evaluate(tree, ctx_hit) is True

    # PE=25 / ROE=18 → (25<20=False) AND ... = False
    ctx_miss_pe = EvalContext(
        symbol="S2", fundamentals={"PE_TTM": 25.0, "ROE_TTM": 18.0}
    )
    assert engine.evaluate(tree, ctx_miss_pe) is False

    # PE=8 / ROE=3 → (8<20=True) AND (3>10=False OR 3>5=False) = False
    ctx_miss_roe = EvalContext(
        symbol="S3", fundamentals={"PE_TTM": 8.0, "ROE_TTM": 3.0}
    )
    assert engine.evaluate(tree, ctx_miss_roe) is False


def test_evaluate_arithmetic_node():
    """AC-1：算术节点 (PE + PB) < 30，验证 + - * / 正确。"""
    engine = ConditionEngine()
    tree = {
        "type": "compare",
        "left": {
            "op": "+",
            "left": {"factor": "PE_TTM"},
            "right": {"factor": "PB"},
        },
        "comparator": "<",
        "right": {"value": 30},
    }
    ctx = EvalContext(
        symbol="S", fundamentals={"PE_TTM": 10.0, "PB": 15.0}
    )
    assert engine.evaluate(tree, ctx) is True  # 10+15=25 < 30


def test_evaluate_empty_logic_defaults():
    """AC-1：空 AND → True；空 OR → False（与引擎 docstring 约定一致）。"""
    engine = ConditionEngine()
    assert engine.evaluate({"operator": "AND", "conditions": []}, EvalContext("S")) is True
    assert engine.evaluate({"operator": "OR", "conditions": []}, EvalContext("S")) is False


# ============================================================
# AC-2 NaN 安全
# ============================================================

def test_evaluate_nan_safe_missing_fundamental():
    """AC-2：fundamentals 缺 PE_TTM → 因子取值为 NaN → 比较返回 False（不抛异常）。"""
    engine = ConditionEngine()
    tree = {
        "type": "compare",
        "left": {"factor": "PE_TTM"},
        "comparator": "<",
        "right": {"value": 20},
    }
    ctx = EvalContext(symbol="S", fundamentals={"ROE_TTM": 15.0})  # 缺 PE_TTM
    assert engine.evaluate(tree, ctx) is False


def test_evaluate_nan_safe_missing_technical_factor():
    """AC-2：技术面因子（factor_values）缺失 → NaN → 比较返回 False。"""
    engine = ConditionEngine()
    tree = {
        "type": "compare",
        "left": {"factor": "MA", "params": {"timeperiod": 5}},
        "comparator": ">",
        "right": {"value": 0},
    }
    ctx = EvalContext(symbol="S", factor_values={}, fundamentals={})
    assert engine.evaluate(tree, ctx) is False


# ============================================================
# 算术除零降级
# ============================================================

def test_arithmetic_division_by_zero_degrades_to_zero():
    """算术除零 → 返回 0.0（不抛异常）；最终 5/0=0.0，0 < 1 → True。"""
    engine = ConditionEngine()
    tree = {
        "type": "compare",
        "left": {
            "op": "/",
            "left": {"value": 5},
            "right": {"value": 0},
        },
        "comparator": "<",
        "right": {"value": 1},
    }
    assert engine.evaluate(tree, EvalContext("S")) is True


def test_arithmetic_division_by_nan_right_degrades_to_zero():
    """除号右侧为 NaN（来自缺失因子）→ 同样降级 0.0（IEEE754 NaN 不应阻断批量）。"""
    engine = ConditionEngine()
    tree = {
        "type": "compare",
        "left": {
            "op": "/",
            "left": {"value": 5},
            "right": {"factor": "PE_TTM"},  # 缺失 → NaN
        },
        "comparator": "<=",
        "right": {"value": 0},
    }
    # 5/NaN → 0.0，0 <= 0 为 True（验证降级语义）
    assert engine.evaluate(tree, EvalContext("S")) is True


# ============================================================
# ExpressionNode 4 形态
# ============================================================

def test_expression_node_four_forms():
    """ExpressionNode.kind 4 形态：value / factor / arith / ref。
    前 3 形态可正常求值；ref 在选股路径抛 ScreenTimeSeriesForbiddenError。
    """
    engine = ConditionEngine()

    # value
    assert engine._eval_expression({"value": 42}, EvalContext("S")) == 42.0
    # factor
    assert engine._eval_expression(
        {"factor": "PE_TTM"}, EvalContext("S", fundamentals={"PE_TTM": 12.5})
    ) == 12.5
    # arith
    assert engine._eval_expression(
        {"op": "*", "left": {"value": 3}, "right": {"value": 4}},
        EvalContext("S"),
    ) == 12.0

    # ref → 选股路径拒绝
    with pytest.raises(ScreenTimeSeriesForbiddenError):
        engine._eval_expression({"ref": "entry_price"}, EvalContext("S"))


def test_expression_node_model_rejects_ambiguous_form():
    """ExpressionNode model_validator 校验：多形态同时填充 → ValueError。"""
    with pytest.raises(ValueError):
        ExpressionNode(value=1, factor="X")
    with pytest.raises(ValueError):
        ExpressionNode(op="+")  # 算术缺 left/right


# ============================================================
# AC-3 截面禁用项（validate_cross_section）
# ============================================================

def test_validate_cross_section_rejects_cross_up():
    """AC-3：比较器 cross_up → ScreenTimeSeriesForbiddenError，forbidden_paths 非空。"""
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "MA"},
                "comparator": "cross_up",
                "right": {"value": 10},
            }
        ],
    }
    with pytest.raises(ScreenTimeSeriesForbiddenError) as ei:
        validate_cross_section(tree)
    assert ei.value.code == 422
    assert ei.value.error_code == "SCREEN_TIME_SERIES_FORBIDDEN"
    assert ei.value.forbidden_paths  # 非空


def test_validate_cross_section_rejects_cross_down():
    """AC-3：比较器 cross_down 也被拒绝。"""
    tree = {
        "type": "compare",
        "left": {"factor": "MA"},
        "comparator": "cross_down",
        "right": {"value": 10},
    }
    with pytest.raises(ScreenTimeSeriesForbiddenError):
        validate_cross_section(tree)


def test_validate_cross_section_rejects_ref_node():
    """AC-3：{ref} 节点（状态引用）在选股路径被拒绝。"""
    tree = {
        "type": "compare",
        "left": {"ref": "entry_price"},
        "comparator": ">",
        "right": {"value": 10},
    }
    with pytest.raises(ScreenTimeSeriesForbiddenError) as ei:
        validate_cross_section(tree)
    assert ei.value.forbidden_paths


def test_validate_cross_section_passes_normal_tree():
    """AC-3：正常条件树（仅通用比较器 + factor/value/arith）不抛异常。"""
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM"},
                "comparator": "<",
                "right": {"value": 20},
            },
            {
                "type": "compare",
                "left": {"op": "+", "left": {"value": 1}, "right": {"value": 2}},
                "comparator": "==",
                "right": {"value": 3},
            },
        ],
    }
    validate_cross_section(tree)  # 不抛即通过


# ============================================================
# factor_signature 稳定性
# ============================================================

def test_factor_signature_param_order_independent():
    """factor_signature 对 params 按 key 排序，同参数不同顺序应等价。"""
    sig_a = factor_signature("MA", {"b": 2, "a": 1})
    sig_b = factor_signature("MA", {"a": 1, "b": 2})
    assert sig_a == sig_b == "MA(a=1,b=2)"


def test_factor_signature_output_index_suffix():
    """output_index 不为 None 时附加 #N 后缀。"""
    assert factor_signature("MACD", None, 0) == "MACD#0"
    assert factor_signature("MACD") == "MACD"  # 无 params / 无 output_index


def test_factor_signature_no_params_equals_key():
    """基本面因子（无 params）签名即 factorKey 本身。"""
    assert factor_signature("PE_TTM") == "PE_TTM"
    assert factor_signature("PE_TTM", None, None) == "PE_TTM"
