"""Provider 抽象基类（spec NFR-5：计算层与 akquant.talib 解耦，provider 模式适配）。

Provider 无状态、不依赖 registry：calculator 从 FactorDef 解析出 ``func_name`` /
``input_cols`` / ``params`` 后传入。新增因子来源只需新增 Provider，不改 Service 层。
"""
from abc import ABC, abstractmethod
from typing import Any, Optional, Union

import numpy as np

# 多输出因子的原始返回类型
NDArrayTuple = tuple[np.ndarray, ...]


class FactorProvider(ABC):
    """因子计算提供者：把「底层函数名 + 输入列 + 参数」→ 原始输出序列。"""

    @abstractmethod
    def compute(
        self,
        func_name: Optional[str],
        inputs: dict[str, np.ndarray],
        input_cols: list[str],
        params: dict[str, Any],
    ) -> Union[np.ndarray, NDArrayTuple]:
        """计算因子的原始输出（未做 output_index 降维）。

        :param func_name: 因子定义里的底层函数名（akquantFunc / tushareField），RAW 来源为 None。
        :param inputs: OHLCV 数组字典 {open, high, low, close, volume}。
        :param input_cols: 该因子所需输入列顺序，如 ["high", "low", "close"]。
        :param params: 已合并默认值的参数 dict，键名与底层函数形参一致。
        :return: 单输出返回 ndarray；多输出返回 tuple[ndarray, ...]。
        """
        raise NotImplementedError
