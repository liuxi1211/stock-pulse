"""FactorComputeService —— Java 持有定义、Python 做无状态数值计算。

核心职责：
  - 把 FactorComputeRequest（ohlcv + factors）映射到 IndicatorProvider.compute 的调用；
  - 多输出（tuple）展开为多个列：默认命名 <factorKey>_<i>；
  - 将 NaN 统一替换为 None 后返回；
  - 捕获参数异常并包装成 FactorComputeError(code=400001 / 500001)。
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple, Union

import numpy as np

from services.factor.compute.indicator_provider import IndicatorProvider, get_provider
from services.factor.protocol import (
    FactorComputeError,
    FactorComputeRequest,
    OhlcvItem,
)

_VALID_INPUTS = {"open", "high", "low", "close", "volume"}


def _to_array(ohlcv: List[OhlcvItem], input_name: str) -> np.ndarray:
    """按 input_name 从 ohlcv 列表里取出 numpy 列。"""
    key = (input_name or "").strip().lower()
    if key not in _VALID_INPUTS:
        raise FactorComputeError(
            code=400001,
            message=f"BAD_PARAM: 输入列名 '{input_name}' 不在允许集 {_VALID_INPUTS}",
        )
    if key == "volume":
        values: List[float] = [
            float(item.volume) if item.volume is not None else float("nan")
            for item in ohlcv
        ]
    else:
        values = [float(getattr(item, key)) for item in ohlcv]
    return np.asarray(values, dtype=np.float64)


def _build_inputs(ohlcv: List[OhlcvItem]) -> Dict[str, np.ndarray]:
    """构建 provider 需要的 inputs dict。"""
    return {name: _to_array(ohlcv, name) for name in _VALID_INPUTS}


def _extract_dates(ohlcv: List[OhlcvItem]) -> List[str]:
    return [item.date for item in ohlcv]


def _nan_to_none(arr: np.ndarray) -> List[Optional[float]]:
    out: List[Optional[float]] = []
    for v in np.asarray(arr, dtype=np.float64).tolist():
        if v is None:
            out.append(None)
        else:
            try:
                if np.isnan(v):  # type: ignore[arg-type]
                    out.append(None)
                else:
                    out.append(float(v))
            except (TypeError, ValueError):
                out.append(None)
    return out


def _output_key(base_key: str, index: int, labels: Optional[List[str]]) -> str:
    """生成多输出因子的列名。

    规则：
      - 若传入 outputLabels 且索引在范围内 → 使用 <baseKey>_<label>
      - 否则回退为 <baseKey>_<index>
    """
    if labels and 0 <= index < len(labels) and labels[index]:
        return f"{base_key}_{labels[index]}"
    return f"{base_key}_{index}"


class FactorComputeService:
    """核心计算服务。"""

    def __init__(self, provider: Optional[IndicatorProvider] = None) -> None:
        self._provider = provider or get_provider()

    @property
    def provider(self) -> IndicatorProvider:
        return self._provider

    def compute_many(self, request: FactorComputeRequest) -> Dict[str, Any]:
        """对外入口：返回 dict，包含 dates + results。"""
        if not request.ohlcv:
            raise FactorComputeError(code=400001, message="BAD_PARAM: ohlcv 不能为空")
        if not request.factors:
            raise FactorComputeError(code=400001, message="BAD_PARAM: factors 不能为空")

        inputs = _build_inputs(request.ohlcv)
        dates = _extract_dates(request.ohlcv)
        length = len(request.ohlcv)

        results: Dict[str, List[Optional[float]]] = {}

        for param in request.factors:
            key = param.factorKey or ""
            if not key:
                raise FactorComputeError(
                    code=400001,
                    message="BAD_PARAM: 存在空 factorKey",
                )

            base_key = param.requestKey or key
            labels = param.outputLabels

            try:
                out = self._provider.compute(
                    key, inputs, **(param.params or {})
                )
            except FactorComputeError:
                # 未知 indicator / 输入缺失直接上抛
                raise
            except (TypeError, ValueError, KeyError) as exc:
                raise FactorComputeError(
                    code=400001,
                    message=f"BAD_PARAM: 调用 {key} 失败: {exc}",
                ) from exc
            except Exception as exc:  # noqa: BLE001
                raise FactorComputeError(
                    code=500001,
                    message=f"INTERNAL_ERROR: {exc}",
                ) from exc

            if isinstance(out, tuple):
                for i, arr in enumerate(out):
                    col = np.asarray(arr, dtype=np.float64)
                    if col.size != length:
                        raise FactorComputeError(
                            code=500001,
                            message=f"INTERNAL_ERROR: {key} 输出长度 {col.size} != ohlcv 长度 {length}",
                        )
                    results[_output_key(base_key, i, labels)] = _nan_to_none(col)
            else:
                col = np.asarray(out, dtype=np.float64)
                if col.size != length:
                    raise FactorComputeError(
                        code=500001,
                        message=f"INTERNAL_ERROR: {key} 输出长度 {col.size} != ohlcv 长度 {length}",
                    )
                results[base_key] = _nan_to_none(col)

        return {"dates": dates, "results": results}
