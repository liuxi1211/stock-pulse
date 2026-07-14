"""选股条件表达式求值引擎（统一策略配置 Schema §4 / spec 003 阶段 0 Task 2）。

.. note::
    spec 008-backtest-center-phase2 T1 起，本模块的求值能力同时经
    :mod:`services.shared.condition_evaluator` 聚合对外暴露（截面 mode）。
    本模块**保持原样**（行为单一真相源），向后兼容的
    ``from services.screener.engine import ConditionEngine`` 不变；
    新代码可统一从 ``services.shared`` 取用。

职责：
- 把 ``ConditionTree``（AND/OR 递归）求值为布尔结果。
- 把 ``ExpressionNode``（value/factor/arith/ref 4 形态）求值为浮点数。
- 静态校验：``validate_cross_section`` 扫描选股条件树，拒绝时序节点（cross_up/cross_down/ref）。

设计要点：
- NaN 安全：任何含 NaN 的比较一律返回 False（spec AC：单位 NaN 安全）。
- 除零降级：算术除零返回 0.0，不抛异常（避免阻断批量选股）。
- 截面语义：选股路径无状态、无时序，``ref``（状态引用）与时序比较器（cross_*）在求值期
  与静态校验期均拒绝，抛 ``ScreenTimeSeriesForbiddenError``。
- factor 签名：``factor_values`` 的 key 由 ``factor_signature`` 生成（如 ``MA(timeperiod=5)#0``），
  基本面因子（TUSHARE）签名即 factorKey 本身，无 params。
"""
import math
from dataclasses import dataclass, field
from typing import Optional, Union

from core.exceptions import ScreenTimeSeriesForbiddenError
from models.schemas.condition import Comparator, ConditionTree, CompareLeaf, ExpressionNode

# 时序比较器：仅交易路径合法，选股路径拒绝
_TIME_SERIES_COMPARATORS = {Comparator.CROSS_UP, Comparator.CROSS_DOWN}


# ============================================================
# 因子签名
# ============================================================

def factor_signature(
    factor_key: str,
    params: Optional[dict] = None,
    output_index: Optional[int] = None,
) -> str:
    """生成 factor 缓存 key，如 ``MA(timeperiod=5)#0``。

    基本面因子（TUSHARE）由调用方直接以 factorKey 作为签名（无 params / output_index）。
    本函数对参数按 key 排序，保证不同顺序的同参数等价。
    """
    if params:
        kv = ",".join(f"{k}={v}" for k, v in sorted(params.items()))
        sig = f"{factor_key}({kv})"
    else:
        sig = factor_key
    if output_index is not None:
        sig = f"{sig}#{output_index}"
    return sig


# ============================================================
# 求值上下文
# ============================================================

@dataclass
class EvalContext:
    """单股单时刻的求值上下文。

    - ``factor_values``：技术面/价格因子的标量值，key=factor_signature（如 ``MA(timeperiod=5)#0``）。
    - ``fundamentals``：基本面因子（TUSHARE）的标量值，key=factorKey（如 ``PE_TTM``）。
    - ``symbol``：当前股票代码，用于日志/错误信息。
    """

    symbol: str
    factor_values: dict[str, float] = field(default_factory=dict)
    fundamentals: dict[str, float] = field(default_factory=dict)


# ============================================================
# 条件引擎
# ============================================================

class ConditionEngine:
    """条件表达式求值引擎（无状态，可并发使用）。"""

    # ------------------------------------------------------------------
    # 公开入口
    # ------------------------------------------------------------------

    def evaluate(
        self,
        tree: Union[ConditionTree, CompareLeaf, dict],
        context: EvalContext,
    ) -> bool:
        """对条件树（或单叶子）求值，返回布尔结果。

        :param tree: ``ConditionTree`` / ``CompareLeaf`` / 等价 dict。
        :param context: 求值上下文（含 factor_values / fundamentals）。
        :return: 布尔结果。AND 空 conditions → True；OR 空 conditions → False。
        """
        return self._eval_node(tree, context)

    # ------------------------------------------------------------------
    # 节点分发
    # ------------------------------------------------------------------

    def _eval_node(
        self,
        node: Union[ConditionTree, CompareLeaf, dict],
        context: EvalContext,
    ) -> bool:
        """区分逻辑组 / 叶子。dict 先按字段判断类型再转模型。"""
        # dict 形态：按字段判断
        if isinstance(node, dict):
            if "operator" in node:
                node = ConditionTree(**node)
            else:
                node = CompareLeaf(**node)

        if isinstance(node, ConditionTree):
            return self._eval_logic(node, context)
        if isinstance(node, CompareLeaf):
            return self._eval_compare(node, context)
        # 不应到达
        raise TypeError(f"不支持的条件节点类型: {type(node).__name__}")

    def _eval_logic(self, tree: ConditionTree, context: EvalContext) -> bool:
        """递归 AND/OR；空 conditions 按 AND→True / OR→False。"""
        if not tree.conditions:
            # 空集：AND 视为恒真（all([])=True），OR 视为恒假（any([])=False）
            return tree.operator == "AND"

        results = (self._eval_node(child, context) for child in tree.conditions)
        if tree.operator == "AND":
            return all(results)
        return any(results)

    # ------------------------------------------------------------------
    # 比较叶子
    # ------------------------------------------------------------------

    def _eval_compare(self, leaf: CompareLeaf, context: EvalContext) -> bool:
        """比较左右操作数；含 NaN 一律 False；时序比较器在选股路径拒绝。"""
        cmp = leaf.comparator
        if cmp in _TIME_SERIES_COMPARATORS:
            # 选股路径不支持时序穿越信号（需要历史序列）
            raise ScreenTimeSeriesForbiddenError(
                forbidden_paths=[],
                message=f"选股路径不支持时序比较器 '{cmp.value}'",
            )

        left = self._eval_expression(leaf.left, context)
        right = self._eval_expression(leaf.right, context)

        # NaN 安全：任一为 NaN 直接 False
        if _is_nan(left) or _is_nan(right):
            return False

        if cmp == Comparator.GT:
            return left > right
        if cmp == Comparator.LT:
            return left < right
        if cmp == Comparator.GE:
            return left >= right
        if cmp == Comparator.LE:
            return left <= right
        if cmp == Comparator.EQ:
            return left == right
        if cmp == Comparator.NE:
            return left != right
        # 不应到达
        raise TypeError(f"不支持的比较器: {cmp}")

    # ------------------------------------------------------------------
    # 表达式求值
    # ------------------------------------------------------------------

    def _eval_expression(
        self,
        node: Union[ExpressionNode, dict],
        context: EvalContext,
    ) -> float:
        """把表达式节点求值为浮点数。

        - value → float（数值直接转，字符串尝试转 float，失败→NaN）
        - factor → 技术面从 ``context.factor_values`` 取（key=factor_signature），
          基本面从 ``context.fundamentals`` 取（key=factorKey），缺失→NaN
        - op → 递归左右再算术，除零降级返回 0.0
        - ref → 选股路径拒绝（抛 ScreenTimeSeriesForbiddenError）
        """
        if isinstance(node, dict):
            node = ExpressionNode(**node)

        kind = node.kind

        if kind == "value":
            return _value_to_float(node.value)

        if kind == "factor":
            return self._resolve_factor(node, context)

        if kind == "arith":
            return self._eval_arith(node, context)

        if kind == "ref":
            # 状态引用仅在交易路径��法；选股路径拒绝
            raise ScreenTimeSeriesForbiddenError(
                forbidden_paths=[],
                message=f"选股路径不支持状态引用 '{{ref}}': {node.ref}",
            )

        # 不应到达（model_validator 已校验）
        raise TypeError(f"不支持的表达式形态: {kind}")

    def _resolve_factor(self, node: ExpressionNode, context: EvalContext) -> float:
        """解析因子引用：技术面用签名查 factor_values，基本面用 factorKey 查 fundamentals。

        优先查 fundamentals（基本面签名即 factorKey 本身），未命中再查 factor_values（技术面签名）。
        缺失→NaN（不阻断，由比较层兜底为 False）。
        """
        key = node.factor
        # 基本面：直接以 factorKey 作为 key（无 params / output_index）
        if key in context.fundamentals:
            return _to_float(context.fundamentals[key])
        # 技术面：用签名查找
        sig = factor_signature(key, node.params, node.outputIndex)
        if sig in context.factor_values:
            return _to_float(context.factor_values[sig])
        # 缺失 → NaN
        return float("nan")

    def _eval_arith(self, node: ExpressionNode, context: EvalContext) -> float:
        """算术运算；含 NaN 传播（IEEE754），除零降级 0.0。"""
        left = self._eval_expression(node.left, context)
        right = self._eval_expression(node.right, context)

        if node.op == "+":
            return left + right
        if node.op == "-":
            return left - right
        if node.op == "*":
            return left * right
        if node.op == "/":
            # 除零降级（spec AC：算术除零返回 0.0 不抛异常）
            if right == 0.0 or _is_nan(right):
                return 0.0
            return left / right
        # 不应到达（model_validator 已校验）
        raise TypeError(f"不支持的算术运算符: {node.op}")


# ============================================================
# 静态校验：截面禁止时序节点
# ============================================================

def validate_cross_section(
    tree: Union[ConditionTree, CompareLeaf, dict],
    _path: str = "root",
) -> None:
    """递归扫描条件树，发现 cross_up/cross_down 比较器或 {ref} 节点 → 抛异常（附违禁路径）。

    在 API 入口（拿到数据前）调用，提前拦截非法选股表达式。

    :param tree: 条件树根节点。
    :raises ScreenTimeSeriesForbiddenError: 当发现违禁节点。
    """
    forbidden: list[str] = []
    _scan_for_time_series(tree, _path, forbidden)
    if forbidden:
        raise ScreenTimeSeriesForbiddenError(forbidden_paths=forbidden)


def _scan_for_time_series(
    node: Union[ConditionTree, CompareLeaf, ExpressionNode, dict],
    path: str,
    forbidden: list[str],
) -> None:
    """递归收集违禁路径。"""
    if node is None:
        return

    # dict 形态归一化
    if isinstance(node, dict):
        if "operator" in node:
            node = ConditionTree(**node)
        elif "comparator" in node:
            node = CompareLeaf(**node)
        elif _is_expression_dict(node):
            node = ExpressionNode(**node)
        else:
            return

    # 逻辑组
    if isinstance(node, ConditionTree):
        for i, child in enumerate(node.conditions):
            _scan_for_time_series(child, f"{path}.conditions[{i}]", forbidden)
        return

    # 比较叶子
    if isinstance(node, CompareLeaf):
        if node.comparator in _TIME_SERIES_COMPARATORS:
            forbidden.append(f"{path}.comparator({node.comparator.value})")
        _scan_for_time_series(node.left, f"{path}.left", forbidden)
        _scan_for_time_series(node.right, f"{path}.right", forbidden)
        return

    # 表达式节点
    if isinstance(node, ExpressionNode):
        kind = node.kind
        if kind == "ref":
            forbidden.append(f"{path}.ref({node.ref})")
        elif kind == "arith":
            _scan_for_time_series(node.left, f"{path}.left", forbidden)
            _scan_for_time_series(node.right, f"{path}.right", forbidden)
        # value / factor 无子节点，无需继续
        return


def _is_expression_dict(d: dict) -> bool:
    """判断 dict 是否为 ExpressionNode 形态（含 value/factor/op/ref 之一）。"""
    return any(k in d for k in ("value", "factor", "op", "ref", "left"))


# ============================================================
# 数值工具
# ============================================================

def _value_to_float(value: Union[int, float, str, None]) -> float:
    """静态值转 float：数值直接转，字符串尝试转 float，失败/None→NaN。"""
    if value is None:
        return float("nan")
    if isinstance(value, str):
        try:
            return float(value)
        except (TypeError, ValueError):
            return float("nan")
    return float(value)


def _to_float(value) -> float:
    """把任意标量安全转为 float；None/非数→NaN。"""
    if value is None:
        return float("nan")
    try:
        f = float(value)
    except (TypeError, ValueError):
        return float("nan")
    return f


def _is_nan(x: float) -> bool:
    """NaN 判断（math.isnan 对非 float 会抛异常，调用方需保证传入 float）。"""
    return isinstance(x, float) and math.isnan(x)
