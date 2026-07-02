"""数据输入标准化：watcher 传入的 OHLCV list[dict] → numpy 数组字典（spec NFR-3）。

engine 不触库，所有 OHLCV 数据由调用方（watcher）经 HTTP 传入；本模块负责把它
转成 akquant.talib 所需的 float64 ndarray，并按时间排序、做完整性校验。
"""
from typing import Any

import numpy as np

from core.exceptions import ValidationException

# 可识别的时间列（watcher 标准格式为 date，兼容其它常见命名）
_TIME_COLS = ("date", "trade_date", "datetime", "timestamp")
# engine 计算所需的 5 列
_PRICE_COLS = ("open", "high", "low", "close", "volume")


def kline_to_arrays(records: list[dict[str, Any]]) -> dict[str, np.ndarray]:
    """将 OHLCV 记录列表转为 ``{open, high, low, close, volume}`` 数组字典。

    - 自动识别时间列并按时间升序排序（输入乱序也安全）。
    - 数值列转 float64，5 列等长；缺失值（None）转 NaN，由 talib 按预热期规则传播。
    - 数据为空或缺少必要列时抛 ``ValidationException``。

    :param records: watcher 传入的 OHLCV 记录，如 ``[{"date":..., "open":..., ...}]``。
    :return: ``{"open": ndarray, "high": ndarray, "low": ndarray, "close": ndarray, "volume": ndarray}``。
    """
    if not records:
        raise ValidationException("输入数据为空")

    # 识别时间列并排序（保证 talib 时序正确）
    time_col = next((c for c in _TIME_COLS if c in records[0]), None)
    if time_col:
        records = sorted(records, key=lambda r: r.get(time_col, ""))

    arrays: dict[str, np.ndarray] = {}
    for col in _PRICE_COLS:
        if col not in records[0]:
            raise ValidationException(f"输入数据缺少必要列: {col}")
        try:
            arrays[col] = np.asarray([_to_float(r.get(col)) for r in records], dtype=np.float64)
        except (TypeError, ValueError) as exc:
            raise ValidationException(f"列 {col} 含非数值: {exc}") from exc
    return arrays


def _to_float(value: Any) -> float:
    """None → NaN（talib 预热/缺失约定），其余强转 float。"""
    if value is None:
        return float("nan")
    return float(value)
