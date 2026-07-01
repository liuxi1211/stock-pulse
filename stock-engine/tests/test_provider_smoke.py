"""provider 级别冒烟测试：20 个标准因子全量走一遍。"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import numpy as np

from services.factor.compute.indicator_provider import (
    AkquantIndicatorProvider,
    NumpySimpleProvider,
    get_provider,
)
from services.factor.protocol import FactorComputeError


def _ohlcv(n=80):
    rng = np.random.default_rng(1)
    price = 100.0
    opens, highs, lows, closes, volumes = [], [], [], [], []
    for _ in range(n):
        delta = rng.uniform(-1, 1)
        price = price + delta
        highs.append(price + rng.uniform(0, 0.5))
        lows.append(price - rng.uniform(0, 0.5))
        opens.append(price + rng.uniform(-0.3, 0.3))
        closes.append(price)
        volumes.append(rng.uniform(1_000, 5_000))
    return {
        "open": np.asarray(opens, dtype=np.float64),
        "high": np.asarray(highs, dtype=np.float64),
        "low": np.asarray(lows, dtype=np.float64),
        "close": np.asarray(closes, dtype=np.float64),
        "volume": np.asarray(volumes, dtype=np.float64),
    }


def main():
    inputs = _ohlcv(80)
    n = len(inputs["close"])
    provider = get_provider()
    print("provider =", type(provider).__name__, ", supported =", sorted(provider.supported()))

    single = [
        ("MA", {"timeperiod": 5}),
        ("EMA", {"timeperiod": 12}),
        ("SAR", {}),
        ("RSI", {"timeperiod": 14}),
        ("ADX", {"timeperiod": 14}),
        ("PLUS_DI", {"timeperiod": 14}),
        ("MINUS_DI", {"timeperiod": 14}),
        ("WILLR", {"timeperiod": 14}),
        ("CCI", {"timeperiod": 14}),
        ("ATR", {"timeperiod": 14}),
        ("OBV", {}),
    ]
    multi = [
        ("BOLL", {"timeperiod": 20, "nbdevup": 2.0, "nbdevdn": 2.0, "matype": 0}),
        ("MACD", {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9}),
        ("KDJ", {"fastk_period": 9, "slowk_period": 3, "slowd_period": 3}),
    ]

    for name, params in single:
        out = provider.compute(name, inputs, **params)
        assert len(out) == n, f"{name} 长度不对: {len(out)}"
        print(f"  {name:8s} tail = {float(out[-1]):.4f}")

    for name, params in multi:
        out = provider.compute(name, inputs, **params)
        assert isinstance(out, tuple) and len(out) == 3, f"{name} 输出不是 3 元组"
        for col in out:
            assert len(col) == n
        print(f"  {name:8s} tail = {float(out[0][-1]):.4f}")

    simple_provider = NumpySimpleProvider()
    for name in ("CLOSE", "HIGH", "LOW", "VOLUME"):
        out = simple_provider.compute(name, inputs)
        assert len(out) == n
        print(f"  {name:8s} tail = {float(out[-1]):.4f}")
    for name, params in (("VOL_MA", {"timeperiod": 20}), ("VOL_EMA", {"timeperiod": 20})):
        out = simple_provider.compute(name, inputs, **params)
        assert len(out) == n
        print(f"  {name:8s} tail = {float(out[-1]):.4f}")

    # 异常路径
    try:
        provider.compute("UNKNOWN_IND", inputs)
    except FactorComputeError as exc:
        print(f"  unknown factor -> code={exc.code} msg={exc.message}")

    try:
        provider.compute("MA", inputs, timeperiod="bad")
    except FactorComputeError as exc:
        print(f"  bad param -> code={exc.code} msg={exc.message[:60]}")

    # 直接 akquant provider 验证
    aq = AkquantIndicatorProvider()
    out = aq.compute("MA", inputs, timeperiod=10)
    assert len(out) == n
    print(f"  akquant direct MA(10) tail = {float(out[-1]):.4f}")
    print("ALL OK")


if __name__ == "__main__":
    main()
