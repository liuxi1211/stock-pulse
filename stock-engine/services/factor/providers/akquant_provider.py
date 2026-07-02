"""AKQUANT 来源因子计算提供者：封装 ``akquant.talib`` 调用（spec FR-3, AC-2~4, AC-8）。

- 从因子定义的 ``akquantFunc`` 反射 ``akquant.talib.<func>``。
- 按 ``input_cols`` 顺序取列，透传 params（参数名与 talib 形参一致）。
- KDJ 特殊处理：``STOCH`` 原生返回 (K,D)，本层合成 ``J = 3K − 2D``，对外返回三元组。
- 技术面因子 100% 走 akquant.talib，禁止自行实现算法。
"""
from typing import Any, Optional, Union

import akquant.talib as talib
import numpy as np

from core.exceptions import UnknownFactorError
from services.factor.providers.base import FactorProvider, NDArrayTuple


def _to_ndarray(x: Any) -> np.ndarray:
    """归一化为 float64 ndarray（兼容 akquant 可能返回的 pd.Series）。"""
    return np.asarray(x, dtype=np.float64)


class AkquantTalibProvider(FactorProvider):
    """技术面因子提供者（OVERLAP/MOMENTUM/VOLATILITY/VOLUME(AKVOLUME)/STATISTIC）。"""

    def compute(
        self,
        func_name: Optional[str],
        inputs: dict[str, np.ndarray],
        input_cols: list[str],
        params: dict[str, Any],
    ) -> Union[np.ndarray, NDArrayTuple]:
        if not func_name:
            raise UnknownFactorError("AKQUANT/DERIVED 来源因子缺少 akquantFunc 映射")
        func = getattr(talib, func_name, None)
        if func is None or not callable(func):
            raise UnknownFactorError(f"akquant.talib 不支持函数: {func_name}")

        args = [inputs[col] for col in input_cols]
        result = func(*args, **params)

        # KDJ 合成：STOCH 返回 (K, D)，对外补 J = 3K − 2D（spec AC-3）
        if func_name == "STOCH":
            k, d = result
            k = _to_ndarray(k)
            d = _to_ndarray(d)
            j = 3.0 * k - 2.0 * d
            return (k, d, j)

        if isinstance(result, tuple):
            return tuple(_to_ndarray(x) for x in result)
        return _to_ndarray(result)
