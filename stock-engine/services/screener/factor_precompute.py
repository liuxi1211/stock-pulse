"""因子批量预计算（统一策略配置 Schema §4 / spec 003 阶段 0 Task 3）。

.. note::
    spec 008-backtest-center-phase2 T1 起，本模块的批量预计算能力同时经
    :mod:`services.shared.factor_pipeline` 聚合对外暴露
    （``precompute_factors_batch`` / ``precompute``）。本模块**保持原样**
    （行为单一真相源），向后兼容的
    ``from services.screener.factor_precompute import precompute_factors`` 不变；
    新代码可统一从 ``services.shared.factor_pipeline`` 取用。

职责：在条件求值前，把每只候选股票的 ``{factor}`` 节点统一预计算为标量值，
使 ``ConditionEngine`` 的求值成为纯查表 + 算术操作。

数据流：
    candidates = {
        symbol: {
            "ohlcv_history": [...],          # OHLCV list[dict]
            "fundamentals": {"PE_TTM": 12.3, ...}  # 基本面快照（watcher 传入）
        }
    }
                │
                │  collect_factor_refs(tree)  → 去重因子规格
                ▼
    precompute_factors(tree, candidates) → {
        symbol: {factor_signature: scalar_value}
    }

路由策略（按 source）：
- TUSHARE：基本面，从 ``candidate["fundamentals"]`` 取，缺失→NaN（不阻断）。
- AKQUANT / RAW / DERIVED：技术面/价格，``kline_to_arrays`` → ``factor_calculator.compute_single``
  → 取 ``[-1]``（当日值，NaN 安全）。
- 计算异常 / 缺失历史 → 该因子 NaN + logger.warning（不阻断批量）。
- factorKey 未知（registry.exists False）→ 抛 ``UnknownFactorError``（400）。

约束（spec AC-11）：engine 不触库，本模块只用内存计算，无 sqlite3/sqlalchemy。
"""
import math
from typing import Any, Optional, Union

from core.exceptions import UnknownFactorError
from core.logger import logger
from models.schemas.condition import CompareLeaf, ConditionTree, ExpressionNode
from services.factor.calculator import factor_calculator
from services.factor.data_utils import kline_to_arrays
from services.factor.registry import factor_registry
from services.screener.engine import factor_signature

# 技术面/价格因子来源：走 factor_calculator
_TECH_SOURCES = {"AKQUANT", "RAW", "DERIVED"}


# ============================================================
# 收集因子引用
# ============================================================

def collect_factor_refs(
    tree: Union[ConditionTree, CompareLeaf, ExpressionNode, dict],
    _acc: Optional[list[dict]] = None,
    _seen: Optional[set[str]] = None,
) -> list[dict]:
    """递归收集条件树中所有 ``{factor}`` 节点的因子规格，按签名去重。

    :return: 因子规格列表，元素形如
        ``{"factorKey": "RSI", "params": {"timeperiod": 14}, "outputIndex": 0}``。
        params/outputIndex 为可选键（缺失视为 None）。
    """
    if _acc is None:
        _acc = []
    if _seen is None:
        _seen = set()

    if tree is None:
        return _acc

    # dict 归一化
    if isinstance(tree, dict):
        if "operator" in tree:
            tree = ConditionTree(**tree)
        elif "comparator" in tree:
            tree = CompareLeaf(**tree)
        elif _is_expression_dict(tree):
            tree = ExpressionNode(**tree)
        else:
            return _acc

    # 逻辑组
    if isinstance(tree, ConditionTree):
        for child in tree.conditions:
            collect_factor_refs(child, _acc, _seen)
        return _acc

    # 比较叶子
    if isinstance(tree, CompareLeaf):
        collect_factor_refs(tree.left, _acc, _seen)
        collect_factor_refs(tree.right, _acc, _seen)
        return _acc

    # 表达式节点
    if isinstance(tree, ExpressionNode):
        kind = tree.kind
        if kind == "factor":
            sig = factor_signature(tree.factor, tree.params, tree.outputIndex)
            if sig not in _seen:
                _seen.add(sig)
                ref = {"factorKey": tree.factor}
                if tree.params:
                    ref["params"] = tree.params
                if tree.outputIndex is not None:
                    ref["outputIndex"] = tree.outputIndex
                _acc.append(ref)
        elif kind == "arith":
            collect_factor_refs(tree.left, _acc, _seen)
            collect_factor_refs(tree.right, _acc, _seen)
        # value / ref 无因子引用
        return _acc

    return _acc


# ============================================================
# 批量预计算
# ============================================================

def precompute_factors(
    tree: Union[ConditionTree, CompareLeaf, dict],
    candidates: dict[str, dict[str, Any]],
) -> dict[str, dict[str, float]]:
    """对所有候选股票预计算条件树引用的全部因子（标量值）。

    :param tree: 条件树。
    :param candidates: ``{symbol: {"ohlcv_history": [...], "fundamentals": {...}}}``。
    :return: ``{symbol: {factor_signature: scalar_value}}``；技术面用签名作 key，
        基本面（TUSHARE）直接以 factorKey 作 key（同时与签名等价，便于 engine 解析）。
    :raises UnknownFactorError: 出现未注册的 factorKey。
    """
    refs = collect_factor_refs(tree)

    # 先校验全部 factorKey 已注册（提前失败，避免循环中途抛错）
    for ref in refs:
        key = ref["factorKey"]
        if not factor_registry.exists(key):
            raise UnknownFactorError(f"未知的因子: {key}")

    # 按 source 分流（基本面 vs 技术面）
    refs_by_source: dict[str, list[dict]] = {"fundamental": [], "technical": []}
    for ref in refs:
        fd = factor_registry.get_factor(ref["factorKey"])
        if fd.source == "TUSHARE":
            refs_by_source["fundamental"].append(ref)
        elif fd.source in _TECH_SOURCES:
            refs_by_source["technical"].append(ref)
        else:
            # 防御性：未知 source 走技术面路径（calculator 内部会拒绝）
            refs_by_source["technical"].append(ref)

    result: dict[str, dict[str, float]] = {}

    for symbol, candidate in candidates.items():
        per_symbol: dict[str, float] = {}

        # 1) 基本面因子：从 fundamentals 快照取
        fundamentals = candidate.get("fundamentals") or {}
        for ref in refs_by_source["fundamental"]:
            key = ref["factorKey"]
            val = fundamentals.get(key)
            per_symbol[key] = _to_float(val)
            # 基本面以 factorKey 作 key 即可（engine._resolve_factor 优先查 fundamentals）

        # 2) 技术面/价格因子：kline_to_arrays → compute_single → [-1]
        ohlcv_history = candidate.get("ohlcv_history")
        arrays = None
        for ref in refs_by_source["technical"]:
            sig = factor_signature(
                ref["factorKey"], ref.get("params"), ref.get("outputIndex")
            )
            try:
                if arrays is None:
                    # 懒初始化数组转换（无技术面因子时跳过）
                    if not ohlcv_history:
                        per_symbol[sig] = float("nan")
                        continue
                    arrays = kline_to_arrays(ohlcv_history)

                arr = factor_calculator.compute_single(
                    factor_key=ref["factorKey"],
                    inputs=arrays,
                    params=ref.get("params"),
                    output_index=ref.get("outputIndex"),
                )
                # 取当日值（最后一位）；NaN 安全（空数组/全 NaN 都落到 NaN）
                per_symbol[sig] = _last_or_nan(arr)
            except Exception as exc:
                # 计算异常 → 该因子 NaN（不阻断批量），记录 warning
                logger.warning(
                    "选股因子预计算失败 symbol=%s factor=%s: %s",
                    symbol, ref["factorKey"], exc,
                )
                per_symbol[sig] = float("nan")

        result[symbol] = per_symbol

    return result


# ============================================================
# 数值工具
# ============================================================

def _last_or_nan(arr) -> float:
    """取数组最后一位；空数组 / NaN → NaN。"""
    try:
        if arr is None or len(arr) == 0:
            return float("nan")
        val = float(arr[-1])
        return val
    except (TypeError, ValueError, IndexError):
        return float("nan")


def _to_float(value: Any) -> float:
    """None / 非数 → NaN，其余转 float。"""
    if value is None:
        return float("nan")
    try:
        f = float(value)
    except (TypeError, ValueError):
        return float("nan")
    if math.isnan(f) or math.isinf(f):
        return float("nan")
    return f


def _is_expression_dict(d: dict) -> bool:
    """判断 dict 是否为 ExpressionNode 形态（含 value/factor/op/ref/left 之一）。"""
    return any(k in d for k in ("value", "factor", "op", "ref", "left"))
