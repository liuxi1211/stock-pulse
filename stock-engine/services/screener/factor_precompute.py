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
            sig = factor_signature(
                tree.factor, tree.params, tree.outputIndex, tree.transform
            )
            if sig not in _seen:
                _seen.add(sig)
                ref = {"factorKey": tree.factor}
                if tree.params:
                    ref["params"] = tree.params
                if tree.outputIndex is not None:
                    ref["outputIndex"] = tree.outputIndex
                if tree.transform:
                    ref["transform"] = tree.transform
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

        # 1) 基本面因子
        fundamentals = candidate.get("fundamentals") or {}
        extras = candidate.get("extra") or {}
        for ref in refs_by_source["fundamental"]:
            key = ref["factorKey"]
            transform = ref.get("transform")
            sig = factor_signature(key, ref.get("params"), ref.get("outputIndex"), transform)
            if transform is None:
                # 现状：从 fundamentals 快照取，以 factorKey 作 key
                per_symbol[key] = _to_float(fundamentals.get(key))
            else:
                # transform：从 extra 逐 bar 取 tushareField 序列再聚合
                fd = factor_registry.get_factor(key)
                field = getattr(fd, "tushareField", None) or key.lower()
                series = []
                for d in sorted(extras.keys()):
                    v = extras[d].get(field) if isinstance(extras[d], dict) else None
                    if v is not None:
                        series.append(v)
                per_symbol[sig] = aggregate_series(series, transform)

        # 2) 技术面/价格因子：kline_to_arrays → compute_single → [-1]
        ohlcv_history = candidate.get("ohlcv_history")
        arrays = None
        for ref in refs_by_source["technical"]:
            transform = ref.get("transform")
            sig = factor_signature(
                ref["factorKey"], ref.get("params"), ref.get("outputIndex"), transform
            )
            try:
                if arrays is None:
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
                if transform is None:
                    per_symbol[sig] = _last_or_nan(arr)
                else:
                    per_symbol[sig] = aggregate_series(arr, transform)
            except Exception as exc:
                logger.warning(
                    "选股因子预计算失败 symbol=%s factor=%s: %s",
                    symbol, ref["factorKey"], exc,
                )
                per_symbol[sig] = float("nan")

        result[symbol] = per_symbol

    return result


# ============================================================
# 因子序列聚合（transform 滚动窗口，PRD 009 §1 P1-6 共享内核）
# ============================================================

def aggregate_series(series, transform):
    """对因子值序列做滚动窗口聚合（PRD 009 §1 P1-6 共享内核）。

    :param series: list[float] / np.ndarray（可能含 NaN），按时间升序。
    :param transform: ``{"type": "ma"|"std"|"pct_change"|"max"|"min", "window": int}``
        或 None。None → 返回末值（与无 transform 行为一致）。
    :return: 聚合后的标量；窗口未满 / 空 / 非法 → NaN。

    语义（与 Step 1 测试一致）：
    - 先要求 ``len(vals) >= window``（窗口被可用历史填满），否则 NaN；
    - 在末 ``window`` 个值中跳过 NaN 得 ``seg``；
    - ``seg`` 为空 → NaN；``std``/``pct_change`` 要求 ``len(seg) >= 2``，否则 NaN；
    - ``ma``=均值；``std``=样本标准差(ddof=1)；``pct_change``=(末-首)/首（首为 0 → NaN）；
      ``max``/``min``=极值。

    依赖方向：本函数为纯数值工具（零依赖），定义在本模块（其唯一内部调用方
    :func:`precompute_factors` 旁），由 :mod:`services.shared.factor_pipeline`
    re-export 对外暴露，避免 ``factor_pipeline`` ↔ ``factor_precompute`` 循环导入。
    """
    try:
        vals = [float(v) for v in list(series)]
    except TypeError:
        return float("nan")

    if transform is None:
        return vals[-1] if vals else float("nan")

    t = transform.get("type")
    window = int(transform.get("window", 0))
    if t not in ("ma", "std", "pct_change", "max", "min") or window < 1:
        return float("nan")
    if len(vals) < window:
        return float("nan")  # 可用历史不足一个完整窗口

    seg = [v for v in vals[-window:] if not (isinstance(v, float) and math.isnan(v))]
    if len(seg) == 0:
        return float("nan")
    if len(seg) < 2 and t in ("std", "pct_change"):
        return float("nan")

    if t == "ma":
        return sum(seg) / len(seg)
    if t == "std":
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / (len(seg) - 1)
        return math.sqrt(var)
    if t == "pct_change":
        if seg[0] == 0:
            return float("nan")
        return (seg[-1] - seg[0]) / seg[0]
    if t == "max":
        return max(seg)
    if t == "min":
        return min(seg)
    return float("nan")


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
