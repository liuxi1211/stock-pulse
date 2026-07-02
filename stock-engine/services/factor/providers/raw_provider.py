"""RAW 来源（价格直通）因子提供者（spec FR-4, AC-5）。

OPEN / HIGH / LOW / CLOSE / VOLUME：直接返回输入中对应列，不做任何计算。
"""
from typing import Any, Optional, Union

import numpy as np

from core.exceptions import ValidationException
from services.factor.providers.base import FactorProvider, NDArrayTuple


class RawDataProvider(FactorProvider):
    """价格直通因子提供者。"""

    def compute(
        self,
        func_name: Optional[str],
        inputs: dict[str, np.ndarray],
        input_cols: list[str],
        params: dict[str, Any],
    ) -> Union[np.ndarray, NDArrayTuple]:
        if not input_cols:
            raise ValidationException("价格直通因子缺少 inputs 定义")
        col = input_cols[0]
        if col not in inputs:
            raise ValidationException(f"价格直通因子缺少输入列: {col}")
        return inputs[col]
