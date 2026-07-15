import math
import numpy as np

from services.shared.factor_pipeline import aggregate_series


def _nan(x):
    return isinstance(x, float) and math.isnan(x)


def test_no_transform_returns_last():
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], None) == 4.0


def test_ma_window3():
    # 末 3 个 [2,3,4] 均值 = 3.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "ma", "window": 3}) == 3.0


def test_std_window3():
    # 末 3 个 [2,3,4] 样本标准差 = 1.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "std", "window": 3}) == 1.0


def test_pct_change_window3():
    # 末 3 个 [2,3,4]：(4-2)/2 = 1.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "pct_change", "window": 3}) == 1.0


def test_max_min_window3():
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "max", "window": 3}) == 4.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "min", "window": 3}) == 2.0


def test_window_insufficient_returns_nan():
    assert _nan(aggregate_series([1.0, 2.0], {"type": "ma", "window": 5}))


def test_skips_nan_in_window():
    # 末 3 个含一个 NaN → 跳过，剩 [3,4] 均值=3.5（仍要求跳过后≥2 个有效点；不足则 NaN）
    assert aggregate_series([1.0, float("nan"), 3.0, 4.0], {"type": "ma", "window": 3}) == 3.5


def test_empty_series_nan():
    assert _nan(aggregate_series([], {"type": "ma", "window": 3}))


def test_pct_change_zero_base_nan():
    # window=3 → 末 3 个 [0,1,2]，首=0 → 除零 → NaN
    assert _nan(aggregate_series([0.0, 1.0, 2.0], {"type": "pct_change", "window": 3}))


def test_accepts_ndarray():
    assert aggregate_series(np.array([1.0, 2.0, 3.0, 4.0]), {"type": "ma", "window": 2}) == 3.5
