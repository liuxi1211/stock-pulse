"""DERIVED 来源（衍生）因子提供者（spec FR-4, FR-6 AC-6）。

VOL_MA / VOL_EMA：复用 ``akquant.talib.MA / EMA``，输入列换为 volume。
逻辑与 ``AkquantTalibProvider`` 一致，仅 input_cols 由因子定义决定（["volume"]）。
"""
from typing import Any, Optional, Union

import numpy as np

from services.factor.providers.akquant_provider import AkquantTalibProvider
from services.factor.providers.base import FactorProvider, NDArrayTuple


class DerivedProvider(FactorProvider):
    """衍生因子提供者：委托给 AkquantTalibProvider，差异仅在输入列。"""

    def __init__(self) -> None:
        self._akquant = AkquantTalibProvider()

    def compute(
        self,
        func_name: Optional[str],
        inputs: dict[str, np.ndarray],
        input_cols: list[str],
        params: dict[str, Any],
    ) -> Union[np.ndarray, NDArrayTuple]:
        return self._akquant.compute(func_name, inputs, input_cols, params)
