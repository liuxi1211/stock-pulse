"""统一因子管线（spec 008-backtest-center-phase2 T1 / PRD §12.3）。

把 003 选股的批量预计算（``services.screener.factor_precompute.precompute_factors``）
与 005 回测的单 bar 实时计算（``services.backtest.compiler._compute_factor_values``）
统一为两个入口，**两者都走 ``factor_calculator.compute_single``**，保证 AC「同口径」：
同一组 factor_specs + OHLCV，``precompute`` 最后一根与 ``compute_latest`` 结果一致。

设计要点：
- ``compute_latest``：单 bar 实时算。接收 OHLCV 序列（np.ndarray 或 list），裁前导 NaN，
  对每个 spec 调 ``factor_calculator.compute_single`` 取 ``[-1]``，NaN 安全。
- ``precompute`` / ``precompute_factors_batch``：批量预计算。委派给 003
  ``precompute_factors``（已含基本面/技术面分流 + UnknownFactor 校验）。
- ``trim_leading_nan``：裁各列共同的前导 NaN 段（从 005 ``compiler._first_valid_index``
  下沉而来），避免 003/005 各写一份。

specs 参数形态（与 003/005 解耦）：
- ``compute_latest`` 接收 ``dict[str, spec]``，spec 可以是：
  - 任意带 ``.factor`` / ``.params`` / ``.output_index`` / ``.cache_key`` 属性的对象
    （如 005 的 ``FactorSpec``）；
  - 或 dict：``{"factor":..., "params":..., "output_index":..., "cache_key":...}``。
- key 用 ``cache_key``（若提供），否则用 factor+params+output_index 构造的签名。

硬约束（spec AC-P2-7a）：
- 不触库（源码不含任何数据库驱动 import / 连接 / 路径字面量）；
- 禁用动态代码执行（禁用任意代码字符串解释 / 编译执行 / 动态模块装载）。
"""
import math
from typing import Any, Optional, Union

import numpy as np

from services.factor.calculator import factor_calculator
from services.factor.registry import factor_registry
from services.screener.engine import factor_signature
from services.screener.factor_precompute import (
    aggregate_series,
    collect_factor_refs,
    precompute_factors,
)


# ============================================================
# spec 归一化
# ============================================================

def _spec_field(spec: Any, name: str, default: Any = None) -> Any:
    """从 spec（对象 or dict）取字段。"""
    if isinstance(spec, dict):
        return spec.get(name, default)
    return getattr(spec, name, default)


def _spec_cache_key(spec: Any) -> str:
    """从 spec 取 cache_key；缺失则按 factor_signature 构造。"""
    ck = _spec_field(spec, "cache_key")
    if isinstance(ck, str) and ck:
        return ck
    factor = _spec_field(spec, "factor")
    params = _spec_field(spec, "params") or None
    output_index = _spec_field(spec, "output_index")
    return factor_signature(factor, params, output_index)


# ============================================================
# 裁前导 NaN（从 005 compiler._first_valid_index 下沉）
# ============================================================

def trim_leading_nan(*arrays: np.ndarray) -> tuple[np.ndarray, ...]:
    """裁掉各列共同的前导 NaN/Inf 段，返回同序的裁剪后数组元组。

    - 取所有数组共同长度（按最短对齐）；
    - 从前往后找第一个「所有列都有效」的位置 ``first_valid``；
    - 各列从 ``first_valid`` 起截断；
    - 若全 NaN/Inf，保留最后 1 个元素（避免空数组让 talib 抛错）；
    - 无数组传入返回空元组。

    与 005 ``compiler._first_valid_index`` 行为等价，下沉到共享层避免重复实现。
    """
    if not arrays:
        return tuple()
    arrs = [np.asarray(a, dtype=np.float64) for a in arrays]
    min_len = min(a.size for a in arrs)
    first_valid = 0
    found = False
    for i in range(min_len):
        if all(not (math.isnan(a[i]) or math.isinf(a[i])) for a in arrs):
            first_valid = i
            found = True
            break
    if not found:
        first_valid = max(0, min_len - 1)
    return tuple(a[first_valid:] for a in arrs)


def _first_valid_index(*arrays: np.ndarray) -> int:
    """返回所有数组共同的有效起始索引（trim_leading_nan 的索引版，保留以兼容旧调用）。

    .. deprecated::
        内部兼容���数，新代码请直接用 :func:`trim_leading_nan`。未纳入 ``__all__``，
        仅为平滑旧 import 保留；后续可随调用点清理后移除。
    """
    if not arrays:
        return 0
    arrs = [np.asarray(a, dtype=np.float64) for a in arrays]
    min_len = min(a.size for a in arrs)
    for i in range(min_len):
        if all(not (math.isnan(a[i]) or math.isinf(a[i])) for a in arrs):
            return i
    return max(0, min_len - 1)


# ============================================================
# 单 bar 实时算（对齐 005 compiler._compute_factor_values）
# ============================================================

def compute_latest(
    factor_specs: dict[str, Any],
    close: Union[np.ndarray, list],
    high: Union[np.ndarray, list],
    low: Union[np.ndarray, list],
    volume: Union[np.ndarray, list],
) -> dict[str, float]:
    """对一组因子规格，用 ``factor_calculator.compute_single`` 计算最新一根 bar 的标量值。

    与 005 ``compiler._compute_factor_values`` 同口径（含前导 NaN 裁剪 + NaN/Inf 安全）。

    :param factor_specs: ``{cache_key: spec}``，spec 为带 ``.factor``/``.params``/
        ``.output_index`` 的对象或 dict。空 dict 返回 ``{}``。
    :param close/high/low/volume: OHLCV 序列（np.ndarray 或 list）。
    :return: ``{cache_key: scalar_value}``，单因子失败/NaN 落到 ``nan``（不阻断）。
    """
    if not factor_specs:
        return {}

    close_arr, high_arr, low_arr, volume_arr = trim_leading_nan(close, high, low, volume)

    inputs: dict[str, np.ndarray] = {
        "close": close_arr,
        "high": high_arr,
        "low": low_arr,
        "volume": volume_arr,
    }

    out: dict[str, float] = {}
    for key, spec in factor_specs.items():
        factor = _spec_field(spec, "factor")
        params = _spec_field(spec, "params") or None
        output_index = _spec_field(spec, "output_index")
        try:
            arr = factor_calculator.compute_single(
                factor_key=factor,
                inputs=inputs,
                params=params,
                output_index=output_index,
            )
            arr = np.asarray(arr, dtype=np.float64).ravel()
            if arr.size == 0:
                out[key] = math.nan
            else:
                v = float(arr[-1])
                out[key] = v if not (math.isnan(v) or math.isinf(v)) else math.nan
        except Exception:  # noqa: BLE001 - 单因子失败用 NaN，不阻断批量
            out[key] = math.nan
    return out


# ============================================================
# 批量预计算（对齐 003 factor_precompute.precompute_factors）
# ============================================================

def precompute_factors_batch(
    tree: Any,
    candidates: dict[str, dict[str, Any]],
) -> dict[str, dict[str, float]]:
    """批量预计算（委派 003 ``precompute_factors``）。

    与 :func:`compute_latest` 同口径：技术面因子都走
    ``factor_calculator.compute_single``，基本面走 fundamentals 快照。

    :param tree: 条件树（含 factor 节点）。
    :param candidates: ``{symbol: {"ohlcv_history": [...], "fundamentals": {...}}}``。
    :return: ``{symbol: {factor_signature: scalar_value}}``。
    """
    return precompute_factors(tree, candidates)


# 别名（spec/tasks 里命名为 precompute）
def precompute(
    tree: Any,
    candidates: dict[str, dict[str, Any]],
) -> dict[str, dict[str, float]]:
    """:func:`precompute_factors_batch` 的短别名。"""
    return precompute_factors(tree, candidates)


# aggregate_series 定义在 services.screener.factor_precompute（其唯一内部调用方
# precompute_factors 旁），此处经上方 import re-export 对外暴露——避免本模块对
# factor_precompute 的顶层 import 与 aggregate_series 反向 import 形成循环依赖。
# 详见 services.screener.factor_precompute.aggregate_series。


__all__ = [
    "compute_latest",
    "precompute",
    "precompute_factors_batch",
    "precompute_factors",
    "collect_factor_refs",
    "trim_leading_nan",
    "aggregate_series",
]
