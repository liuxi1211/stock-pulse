"""constraint / resultFilter 结构化 DSL（spec 015 FR-O2）。

提供 compile_constraint(dsl) -> Callable[[dict], bool]，用于 GRID 寻优的
constraint（参数约束）与 resultFilter（指标过滤）。

设计要点：
- 禁用 eval：三元组 + AND/OR 嵌套，op 走白名单分派；
- pickle 安全：返回 functools.partial(_eval_compiled, node=_compile_node(dsl))，
  其中 _eval_compiled 是模块级函数、_compile_node 返回纯数据结构（tuple/list/dict），
  保证多进程 run_grid_search 可 pickle；
- 输入字典的 value 既支持数字也支持字符串（按需转 float 比较，失败返回 False）。
"""
import functools
from typing import Any, Callable, Mapping, Union

VALID_OPS = {"<", "<=", ">", ">=", "==", "!="}


class DSLError(ValueError):
    """DSL 解析错误。"""


# ============================================================
# 求值辅助（模块级，pickle 友好）
# ============================================================

def _resolve(value: Any, context: Mapping[str, Any]) -> Any:
    """解析叶子操作数。

    消歧规则（兼顾"字符串=变量名"与"字符串=字面量"两种用法）：
    - 字符串：先尝试作为参数名/metric 名从 context 取值；
      若 context 中**存在该 key**则返回对应值（变量名语义）；
      若**不存在**则把字符串本身当作字面量返回（字面量语义）。
      这样 ``'fast'``（在 context 中）当变量、``'foo'``（不在 context 中）当字面量。
    - 数字 / 其他：原样返回。
    """
    if isinstance(value, str):
        if value in context:
            return context[value]
        return value
    return value


def _compare(left_val: Any, op: str, right_val: Any) -> bool:
    """按 op 比较 left_val / right_val。

    - None 安全：任意一侧为 None 时，``==``/``!=`` 用 ``is None`` 判等，其余 op 返回 False。
    - 数值比较：两侧尝试转 float 比较，转换失败则：
        - ``==``/``!=`` 回退到原始值相等比较（支持字符串）；
        - 其它序比较返回 False。
    """
    if op not in VALID_OPS:
        raise DSLError(f"未知比较符: {op!r}（合法: {sorted(VALID_OPS)}）")

    if left_val is None or right_val is None:
        if op == "==":
            return left_val is None and right_val is None
        if op == "!=":
            return not (left_val is None and right_val is None)
        return False

    try:
        lf = float(left_val)
        rf = float(right_val)
    except (TypeError, ValueError):
        if op == "==":
            return left_val == right_val
        if op == "!=":
            return left_val != right_val
        return False

    if op == "<":
        return lf < rf
    if op == "<=":
        return lf <= rf
    if op == ">":
        return lf > rf
    if op == ">=":
        return lf >= rf
    if op == "==":
        return lf == rf
    if op == "!=":
        return lf != rf
    return False  # 不可达（op 已校验）


# ============================================================
# 编译期：DSL dict -> 纯 tuple 数据结构
# ============================================================

def _compile_node(node: Any) -> tuple:
    """把 DSL dict 编译成纯 tuple 数据结构（避免 lambda / 局部闭包）。

    - 叶子（比较）: ``("leaf", left, op, right)``
    - AND/OR: ``("logic", "and"|"or", [compiled_children])``
    - 非法形态抛 :class:`DSLError`。
    """
    if not isinstance(node, dict):
        raise DSLError(f"DSL 节点必须是 dict，收到 {type(node).__name__}: {node!r}")

    if "operator" in node:
        operator = node["operator"]
        if not isinstance(operator, str):
            raise DSLError(f"operator 必须是 str，收到 {type(operator).__name__}")
        op_lower = operator.lower()
        if op_lower not in ("and", "or"):
            raise DSLError(
                f"逻辑 operator 必须是 'AND'/'OR'（大小写不敏感），收到 {operator!r}"
            )
        conditions = node.get("conditions")
        if not isinstance(conditions, list):
            raise DSLError(
                f"conditions 必须是 list，收到 {type(conditions).__name__}"
            )
        if not conditions:
            raise DSLError("conditions 不能为空列表")
        compiled_children = [_compile_node(c) for c in conditions]
        return ("logic", op_lower, compiled_children)

    if "op" in node:
        left = node.get("left")
        op = node.get("op")
        right = node.get("right")
        if op not in VALID_OPS:
            raise DSLError(
                f"非法 op: {op!r}（合法: {sorted(VALID_OPS)}）"
            )
        if not isinstance(left, (str, int, float)) or isinstance(left, bool):
            raise DSLError(
                f"left 必须是 str/number（bool 除外），收到 {type(left).__name__}: {left!r}"
            )
        if not isinstance(right, (str, int, float)) or isinstance(right, bool):
            raise DSLError(
                f"right 必须是 str/number（bool 除外），收到 {type(right).__name__}: {right!r}"
            )
        return ("leaf", left, op, right)

    raise DSLError(
        f"无法识别的 DSL 节点（需含 'op' 或 'operator' 键）: {node!r}"
    )


# ============================================================
# 求值期：对编译后的纯 tuple 求值
# ============================================================

def _eval_compiled(compiled: tuple, context: Mapping[str, Any]) -> bool:
    """模块级递归求值（pickle 友好：仅引用模块级函数 + 纯数据 tuple）。

    由 :func:`compile_constraint` 返回的 partial 以 ``context`` 为唯一入参调用���
    """
    kind = compiled[0]
    if kind == "leaf":
        _, left, op, right = compiled
        return _compare(_resolve(left, context), op, _resolve(right, context))
    if kind == "logic":
        _, op_lower, children = compiled
        if op_lower == "and":
            return all(_eval_compiled(c, context) for c in children)
        return any(_eval_compiled(c, context) for c in children)
    raise DSLError(f"无法识别的编译节点 kind: {kind!r}")


# ============================================================
# 公开入口
# ============================================================

def compile_constraint(dsl: Union[dict, Mapping[str, Any]]) -> Callable[[Mapping[str, Any]], bool]:
    """编译 DSL 为可 pickle 的谓词 ``Callable[[dict], bool]``。

    实现：``functools.partial(_eval_compiled, _compile_node(dsl))``。
    - 第一个参数绑定为编译后的纯 tuple；
    - 返回的 partial 仅需 ``context`` 一个参数即可调用；
    - partial + 模块级函数 + 纯数据 → 多进程 run_grid_search 可 pickle。
    """
    compiled = _compile_node(dict(dsl))
    return functools.partial(_eval_compiled, compiled)


def validate_dsl(dsl: Union[dict, Mapping[str, Any]]) -> None:
    """编译期校验 DSL 结构合法性。

    递归校验：op 白名单、逻辑 operator 必为 AND/OR、children 必为非空列表、
    叶子操作数类型合法。任何非法形态抛 :class:`DSLError`。

    本函数不返回求值器（与 :func:`compile_constraint` 区分），仅用于早期校验。
    """
    _compile_node(dict(dsl))


__all__ = [
    "VALID_OPS",
    "DSLError",
    "compile_constraint",
    "validate_dsl",
]
