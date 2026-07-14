"""交易时序条件求值引擎（spec 007-backtest-center T2）。

区别于 003 选股的截面 ``ConditionEngine``，本引擎是**时序版**，在 ``on_bar`` 内逐 bar
求值策略 JSON 的 signals.buy / signals.sell 条件树：

- 支持比较器：``> < >= <= == !=`` + 时序专用 ``cross_up`` / ``cross_down``；
- ``cross_up``：上一根 bar 不满足、当前满足（需历史两根）；
- ``cross_down``：上一根 bar 满足相反、当前满足；
- 支持 ``ref`` 节点白名单：``entry_price`` / ``position_pnl_pct`` / ``position_qty`` / ``bars_held``。

设计：**无状态**（每次 evaluate 传入完整 ctx），可并发复用同一实例。
**禁用动态代码执行**：所有运算走显式分支，不构造任何代码字符串。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Optional, Union

from services.strategy.constants import ALLOWED_REFS, TRADING_COMPARATORS

# services.strategy.models 的 4 形态表达式节点（value/factor/op/ref）
from services.strategy.models import CompareLeaf, ConditionTree


# ============================================================
# 求值上下文
# ============================================================

@dataclass
class BarSnapshot:
    """单根 bar 的快照：bar 字段 + 预计算因子值。

    ``factor_values`` 是 compiler 在 on_bar 内预先算好、按因子规格键（含 output_index）
    缓存的标量值。键格式 ``"MACD#0"``（factorKey + "#" + output_index，单输出用 "None"）。
    """

    bar: Any = None
    factor_values: dict[str, float] = field(default_factory=dict)


@dataclass
class PositionCtx:
    """持仓上下文（ref 节点读取）。无持仓时各字段为零值/None。"""

    entry_price: Optional[float] = None
    position_pnl_pct: float = 0.0
    position_qty: float = 0.0
    bars_held: int = 0


@dataclass
class EvalContext:
    """evaluate 的运行时上下文。

    - ``current``：当前 bar 快照（含因子值）；
    - ``prev``：上一根 bar 快照（cross_* 比较所需，None 表示无历史）；
    - ``position``：持仓状态（ref 节点读取）。
    """

    current: BarSnapshot
    prev: Optional[BarSnapshot] = None
    position: PositionCtx = field(default_factory=PositionCtx)


# ============================================================
# 异常
# ============================================================

class ConditionEvalError(ValueError):
    """条件求值错误（errorCode=BACKTEST_CONDITION_EVAL_FAILED）。"""


# ============================================================
# 引擎
# ============================================================

# 比较器 → Python 运算符函数（纯算术，显式分支）
_COMPARE_OPS = {
    ">":  lambda a, b: a > b,
    "<":  lambda a, b: a < b,
    ">=": lambda a, b: a >= b,
    "<=": lambda a, b: a <= b,
    "==": lambda a, b: a == b,
    "!=": lambda a, b: a != b,
}

# 算术 op → 函数
_ARITH_OPS = {
    "+": lambda a, b: a + b,
    "-": lambda a, b: a - b,
    "*": lambda a, b: a * b,
    "/": lambda a, b: a / b if b != 0 else math.nan,
}


def _factor_cache_key(
    factor: str,
    output_index: Optional[int],
    params: Optional[dict] = None,
) -> str:
    """构造因子缓存键。

    - 不带 params：``"MA#None"`` / ``"MACD#0"``（向后兼容）；
    - 带 params：``"MA#None|timeperiod=5"``（区分同因子不同参数，如 MA5 / MA20）。
    """
    base = f"{factor}#{output_index}"
    if params:
        params_sig = ",".join(f"{k}={v}" for k, v in sorted(params.items()))
        return f"{base}|{params_sig}"
    return base


class TradingConditionEngine:
    """交易时序条件求值引擎（无状态、线程安全）。

    用法（compiler.on_bar 内）::

        engine = TradingConditionEngine()
        hit = engine.evaluate_tree(buy_tree, ctx)
    """

    def __init__(self) -> None:
        # 无实例状态，所有数据从 ctx 读
        pass

    # ------------------------------------------------------------------
    # 单叶子求值
    # ------------------------------------------------------------------

    def evaluate(self, condition: CompareLeaf, ctx: EvalContext) -> bool:
        """求值单个 CompareLeaf：``left <comparator> right``。

        对 ``cross_up`` / ``cross_down``，需要 ctx.prev（上一根 bar 的快照）。
        """
        comparator = condition.comparator
        if comparator not in TRADING_COMPARATORS:
            raise ConditionEvalError(
                f"BACKTEST_CONDITION_EVAL_FAILED: 不支持的比较器 '{comparator}'"
            )

        # 时序穿越：需要历史两根
        if comparator == "cross_up":
            return self._eval_cross(condition, ctx, direction="up")
        if comparator == "cross_down":
            return self._eval_cross(condition, ctx, direction="down")

        # 通用比较：当前 bar 的左右值
        left = self._eval_expr(condition.left, ctx, snapshot=ctx.current)
        right = self._eval_expr(condition.right, ctx, snapshot=ctx.current)
        if _is_nan(left) or _is_nan(right):
            return False
        return _COMPARE_OPS[comparator](left, right)

    # ------------------------------------------------------------------
    # 递归逻辑组
    # ------------------------------------------------------------------

    def evaluate_tree(
        self,
        tree: Union[ConditionTree, CompareLeaf, dict, None],
        ctx: EvalContext,
    ) -> bool:
        """递归求值 AND/OR 逻辑组。``None`` 视为无条件命中（便于可选字段）。"""
        if tree is None:
            return True

        # dict → 解析回模型（兼容从 JSON 透传未解析的场景）
        if isinstance(tree, dict):
            tree = _parse_node(tree)

        # CompareLeaf 叶子
        if isinstance(tree, CompareLeaf):
            return self.evaluate(tree, ctx)

        # ConditionTree 逻辑组
        if isinstance(tree, ConditionTree):
            op = tree.operator
            conditions = tree.conditions or []
            if not conditions:
                return True
            if op == "AND":
                return all(self.evaluate_tree(c, ctx) for c in conditions)
            if op == "OR":
                return any(self.evaluate_tree(c, ctx) for c in conditions)
            raise ConditionEvalError(
                f"BACKTEST_CONDITION_EVAL_FAILED: 未知逻辑运算符 '{op}'"
            )

        raise ConditionEvalError(
            f"BACKTEST_CONDITION_EVAL_FAILED: 未知条件节点类型 {type(tree).__name__}"
        )

    # ------------------------------------------------------------------
    # 表达式求值（4 形态）
    # ------------------------------------------------------------------

    def _eval_expr(self, node: Any, ctx: EvalContext, snapshot: BarSnapshot) -> float:
        """求值 ExpressionNode（value/factor/op/ref 4 形态）。

        ``snapshot`` 指定从哪根 bar 取因子值（current 或 prev），供 cross_* 复用。
        """
        if isinstance(node, dict):
            node = _parse_expr_node(node)

        # 形态 1：静态值
        if getattr(node, "value", None) is not None:
            return _to_float(node.value)

        # 形态 2：因子引用
        if getattr(node, "factor", None) is not None:
            return self._eval_factor(node, snapshot)

        # 形态 3：算术
        if getattr(node, "op", None) is not None:
            left = self._eval_expr(node.left, ctx, snapshot)
            right = self._eval_expr(node.right, ctx, snapshot)
            if _is_nan(left) or _is_nan(right):
                return math.nan
            return _ARITH_OPS[node.op](left, right)

        # 形态 4：状态引用
        if getattr(node, "ref", None) is not None:
            return self._eval_ref(node.ref, ctx)

        raise ConditionEvalError(
            "BACKTEST_CONDITION_EVAL_FAILED: 表达式节点无任何形态(value/factor/op/ref)"
        )

    # ------------------------------------------------------------------
    # 因子值读取
    # ------------------------------------------------------------------

    def _eval_factor(self, node: Any, snapshot: BarSnapshot) -> float:
        """从 snapshot.factor_values 取预计算的因子标量值。

        cache_key 与 compiler 的 :class:`FactorSpec` 一致：``factor#output_index|params``。
        """
        factor: str = node.factor
        output_index: Optional[int] = getattr(node, "output_index", None)
        params: Optional[dict] = getattr(node, "params", None)
        key = _factor_cache_key(factor, output_index, params)
        if key not in snapshot.factor_values:
            raise ConditionEvalError(
                f"BACKTEST_CONDITION_EVAL_FAILED: 因子值未预计算 '{key}'"
            )
        return snapshot.factor_values[key]

    # ------------------------------------------------------------------
    # ref 读取
    # ------------------------------------------------------------------

    def _eval_ref(self, ref: str, ctx: EvalContext) -> float:
        """从持仓上下文读取 ref 值（白名单约束）。"""
        if ref not in ALLOWED_REFS:
            raise ConditionEvalError(
                f"BACKTEST_CONDITION_EVAL_FAILED: ref '{ref}' 不在白名单 {sorted(ALLOWED_REFS)}"
            )
        pos = ctx.position
        if ref == "entry_price":
            return pos.entry_price if pos.entry_price is not None else math.nan
        if ref == "position_pnl_pct":
            return pos.position_pnl_pct
        if ref == "position_qty":
            return pos.position_qty
        if ref == "bars_held":
            return float(pos.bars_held)
        return math.nan  # 理论不可达（白名单已校验）

    # ------------------------------------------------------------------
    # 穿越信号
    # ------------------------------------------------------------------

    def _eval_cross(
        self, condition: CompareLeaf, ctx: EvalContext, direction: str
    ) -> bool:
        """cross_up / cross_down 求值。

        - ``cross_up``：``left`` 从 ``<= right``（上一根）变为 ``> right``（当前）；
        - ``cross_down``：``left`` 从 ``>= right``（上一根）变为 ``< right``（当前）。

        需要 ctx.prev 非 None，否则返回 False（数据不足）。
        """
        if ctx.prev is None:
            return False

        # 当前值
        cur_left = self._eval_expr(condition.left, ctx, snapshot=ctx.current)
        cur_right = self._eval_expr(condition.right, ctx, snapshot=ctx.current)
        # 上一根值
        prev_left = self._eval_expr(condition.left, ctx, snapshot=ctx.prev)
        prev_right = self._eval_expr(condition.right, ctx, snapshot=ctx.prev)

        if any(_is_nan(v) for v in (cur_left, cur_right, prev_left, prev_right)):
            return False

        if direction == "up":
            # 上一根 left <= right，当前 left > right
            return (prev_left <= prev_right) and (cur_left > cur_right)
        else:  # down
            # 上一根 left >= right，当前 left < right
            return (prev_left >= prev_right) and (cur_left < cur_right)


# ============================================================
# 工具函数
# ============================================================

def _to_float(v: Any) -> float:
    """值节点 → float。字符串值尝试解析（如 "10.5"）。"""
    if isinstance(v, (int, float)):
        return float(v)
    try:
        return float(str(v))
    except (TypeError, ValueError) as exc:
        raise ConditionEvalError(
            f"BACKTEST_CONDITION_EVAL_FAILED: 无法转数值 '{v}'"
        ) from exc


def _is_nan(v: float) -> bool:
    """NaN/Inf 判定。"""
    if v is None:
        return True
    try:
        f = float(v)
    except (TypeError, ValueError):
        return True
    return math.isnan(f) or math.isinf(f)


def _parse_node(node: dict) -> Union[ConditionTree, CompareLeaf]:
    """dict → ConditionTree / CompareLeaf（按字段形状判断）。"""
    if "operator" in node:
        return ConditionTree.model_validate(node)
    return CompareLeaf.model_validate(node)


def _parse_expr_node(node: dict) -> Any:
    """dict → ExpressionNode（services.strategy.models 的 Union 形态，Pydantic 自动判别）。"""
    from services.strategy.models import OpNode, ValueNode, FactorNode, RefNode

    if "op" in node:
        return OpNode.model_validate(node)
    if "factor" in node:
        return FactorNode.model_validate(node)
    if "ref" in node:
        return RefNode.model_validate(node)
    return ValueNode.model_validate(node)


__all__ = [
    "TradingConditionEngine",
    "BarSnapshot",
    "PositionCtx",
    "EvalContext",
    "ConditionEvalError",
]
