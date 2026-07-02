"""data_utils 单元测试（TR-4.1~4.4）。"""
import numpy as np
import pytest

from core.exceptions import ValidationException
from services.factor.data_utils import kline_to_arrays


def test_standard_format():
    # TR-4.1
    records = [
        {"date": "D0001", "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 100.0},
        {"date": "D0002", "open": 1.5, "high": 2.5, "low": 1.0, "close": 2.0, "volume": 200.0},
    ]
    arr = kline_to_arrays(records)
    assert set(arr) == {"open", "high", "low", "close", "volume"}
    for v in arr.values():
        assert isinstance(v, np.ndarray)
        assert v.dtype == np.float64
        assert len(v) == 2


def test_sorted_by_date():
    # TR-4.2：乱序输入也按时间升序
    records = [
        {"date": "D0003", "close": 3.0, "open": 0, "high": 0, "low": 0, "volume": 0},
        {"date": "D0001", "close": 1.0, "open": 0, "high": 0, "low": 0, "volume": 0},
        {"date": "D0002", "close": 2.0, "open": 0, "high": 0, "low": 0, "volume": 0},
    ]
    arr = kline_to_arrays(records)
    assert list(arr["close"]) == [1.0, 2.0, 3.0]


def test_missing_close_column():
    # TR-4.3
    records = [{"date": "D0001", "open": 1.0, "high": 2.0, "low": 0.5, "volume": 100.0}]
    with pytest.raises(ValidationException):
        kline_to_arrays(records)


def test_empty_data():
    # TR-4.4
    with pytest.raises(ValidationException):
        kline_to_arrays([])


def test_single_bar():
    # 边界：1 根数据不报错
    arr = kline_to_arrays([
        {"date": "D0001", "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1}
    ])
    assert len(arr["close"]) == 1
