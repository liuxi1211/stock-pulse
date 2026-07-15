"""选股条件表达式 Pydantic Schema（统一策略配置 Schema §4 / spec 003 阶段 0 Task 1）。

字段命名严格对齐统一策略配置 Schema（camelCase）。表达式采用单一 ``ExpressionNode``
模型表达 4 形态 union（静态值 / 因子引用 / 算术 / 状态引用），由 ``kind`` 属性区分：
- 静态值：``{"value": <number|string>}``        → kind = "value"
- 因子引用：``{"factor": "RSI", "params": {...}, "inputs": [...], "outputIndex": 0}`` → kind = "factor"
- 算术：``{"op": "+"|"-"|"*"|"/", "left": <EN>, "right": <EN>}`` → kind = "arith"
- 状态引用：``{"ref": "entry_price"}``（仅 trading 合法，选股路径拒绝）→ kind = "ref"

条件树用 ``operator`` 字段区分逻辑组（``AND``/``OR``），与叶子（``CompareLeaf``，``type="compare"``）
通过 schema 形状互斥；``ConditionNode`` 接受运行时 dict 后由使用方按字段判断类型。
"""
from enum import Enum
from typing import Any, Optional, Union

from pydantic import BaseModel, Field, model_validator


class Comparator(str, Enum):
    """比较器。通用比较与时序比较。

    通用比较在选股与交易路径均可用；``cross_up`` / ``cross_down`` 为时序比较，
    仅交易路径合法，选股路径会被 ``validate_cross_section`` 拒绝。
    """

    GT = ">"
    LT = "<"
    GE = ">="
    LE = "<="
    EQ = "=="
    NE = "!="
    CROSS_UP = "cross_up"
    CROSS_DOWN = "cross_down"


class ExpressionNode(BaseModel):
    """条件表达式节点（4 形态 union，由 ``kind`` 区分，``model_validator`` 校验恰好一种）。

    所有字段均为 Optional：同一时刻只能有一个形态的字段被填充。
    """

    # 形态 1：静态值
    value: Optional[Union[int, float, str]] = Field(None, description="静态值（数值或字符串）")

    # 形态 2：因子引用
    factor: Optional[str] = Field(None, description="因子 factorKey", examples=["RSI"])
    params: Optional[dict[str, Any]] = Field(None, description="因子参数（透传 talib）", examples=[{"timeperiod": 14}])
    inputs: Optional[list[str]] = Field(None, description="输入列覆盖（一般留空用因子默认 inputs）")
    outputIndex: Optional[int] = Field(None, description="多输出因子取第几路（None→defaultOutputIndex）")
    # 形态 2 扩展：因子滚动窗口聚合（PRD 009 §1 P1-6），仅选股条件用
    transform: Optional[dict] = Field(
        None, description="因子滚动窗口聚合 {type,window}，仅 filter.conditions",
        examples=[{"type": "ma", "window": 20}],
    )

    # 形态 3：算术
    op: Optional[str] = Field(None, description="算术运算符", examples=["+"])
    left: Optional["ExpressionNode"] = Field(None, description="左操作数")
    right: Optional["ExpressionNode"] = Field(None, description="右操作数")

    # 形态 4：状态引用
    ref: Optional[str] = Field(None, description="状态引用键名（仅 trading 合法）", examples=["entry_price"])

    # ------------------------------------------------------------------
    # 形态判定
    # ------------------------------------------------------------------

    @property
    def kind(self) -> str:
        """返回当前节点形态：``value`` / ``factor`` / ``arith`` / ``ref``。

        未填充或填充多种时由 ``model_validator`` 兜底校验；此处假设已通过校验。
        """
        if self.value is not None:
            return "value"
        if self.factor is not None:
            return "factor"
        if self.op is not None:
            return "arith"
        if self.ref is not None:
            return "ref"
        return "empty"

    @model_validator(mode="after")
    def _check_single_form(self) -> "ExpressionNode":
        """校验恰好一种形态被填充（防止歧义）。"""
        filled = [
            self.value is not None,
            self.factor is not None,
            self.op is not None,
            self.ref is not None,
        ]
        if sum(filled) == 0:
            raise ValueError("ExpressionNode 必须填充一种形态（value/factor/op/ref）")
        if sum(filled) > 1:
            raise ValueError("ExpressionNode 仅允许一种形态（value/factor/op/ref 互斥）")

        # 算术形态必须有 left 和 right
        if self.op is not None and (self.left is None or self.right is None):
            raise ValueError("算术表达式必须同时提供 left 和 right")

        # 算术运算符合法性
        if self.op is not None and self.op not in ("+", "-", "*", "/"):
            raise ValueError(f"不支持的算术运算符: {self.op}")

        return self


class CompareLeaf(BaseModel):
    """比较叶子节点：``left <comparator> right``。"""

    type: str = Field("compare", description="节点类型标识，固定为 'compare'")
    left: ExpressionNode = Field(..., description="左操作数（表达式节点）")
    comparator: Comparator = Field(..., description="比较器")
    right: ExpressionNode = Field(..., description="右操作数（表达式节点）")

    @model_validator(mode="after")
    def _check_type(self) -> "CompareLeaf":
        if self.type != "compare":
            raise ValueError(f"CompareLeaf.type 必须为 'compare'，收到 '{self.type}'")
        return self


class ConditionTree(BaseModel):
    """逻辑组节点：``operator(conditions...)``。

    递归结构：``conditions`` 元素可能是 ``ConditionTree``（逻辑组）或 ``CompareLeaf``（叶子）。
    通过运行时字段判断类型——含 ``operator`` 字段视为逻辑组，含 ``type='compare'`` 视为叶子。
    """

    operator: str = Field(..., description="逻辑运算符 AND / OR")
    conditions: list["ConditionNode"] = Field(
        default_factory=list,
        description="子条件列表（元素可为 ConditionTree 或 CompareLeaf）",
    )

    @model_validator(mode="after")
    def _check_operator(self) -> "ConditionTree":
        if self.operator not in ("AND", "OR"):
            raise ValueError(f"ConditionTree.operator 必须为 'AND' 或 'OR'，收到 '{self.operator}'")
        return self


# 运行时联合类型：接受 dict 后由使用方按字段判断（discriminated union 在递归 + 多形态下较脆）
ConditionNode = Union[ConditionTree, CompareLeaf]


# 前向引用解析（ConditionTree.conditions 引用了尚在定义的 ConditionNode）
ConditionTree.model_rebuild()
