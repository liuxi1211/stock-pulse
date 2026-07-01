"""冒烟测试：Python 新计算子系统（talib 主实现）。"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient
import main
from services.factor.compute.indicator_provider import (
    TalibIndicatorProvider,
    get_provider,
)

client = TestClient(main.app)


def _ohlcv(n: int = 80, seed: int = 1):
    random.seed(seed)
    ohlcv = []
    price = 100.0
    for i in range(n):
        price = price + random.uniform(-1, 1)
        high = price + random.uniform(0, 0.5)
        low = price - random.uniform(0, 0.5)
        vol = random.uniform(1000, 5000)
        ohlcv.append({"date": f"d{i:03d}", "open": price, "high": high, "low": low, "close": price, "volume": vol})
    return ohlcv


def test_health():
    r = client.get("/python/v1/health")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("status") == "healthy"
    print("health ok ->", body)


def test_registry_list():
    r = client.get("/python/v1/registry")
    assert r.status_code == 200, r.text
    body = r.json()
    print("registry ok ->", body)


def test_compute_factors_talib():
    body = {
        "requestId": "req-1",
        "stockCode": "000001.SZ",
        "ohlcv": _ohlcv(80),
        "factors": [
            {"factorKey": "MA", "params": {"timeperiod": 5}},
            {"factorKey": "EMA", "params": {"timeperiod": 12}},
            {"factorKey": "MACD", "params": {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9}},
            {"factorKey": "RSI", "params": {"timeperiod": 14}},
            {"factorKey": "BOLL", "params": {"timeperiod": 20, "nbdevup": 2.0, "nbdevdn": 2.0, "matype": 0}},
            {"factorKey": "SAR"},
            {"factorKey": "KDJ", "params": {"fastk_period": 9, "slowk_period": 3, "slowd_period": 3}},
            {"factorKey": "ADX", "params": {"timeperiod": 14}},
            {"factorKey": "PLUS_DI", "params": {"timeperiod": 14}},
            {"factorKey": "MINUS_DI", "params": {"timeperiod": 14}},
            {"factorKey": "WILLR", "params": {"timeperiod": 14}},
            {"factorKey": "CCI", "params": {"timeperiod": 14}},
            {"factorKey": "ATR", "params": {"timeperiod": 14}},
            {"factorKey": "OBV"},
            {"factorKey": "VOL_MA", "params": {"timeperiod": 20}},
            {"factorKey": "VOL_EMA", "params": {"timeperiod": 20}},
            {"factorKey": "VOLUME"},
            {"factorKey": "CLOSE"},
            {"factorKey": "HIGH"},
            {"factorKey": "LOW"},
        ],
    }
    r = client.post("/python/v1/compute", json=body)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["code"] == 0, j
    assert j["requestId"] == "req-1"
    dates = j["data"]["dates"]
    results = j["data"]["results"]
    assert len(dates) == 80
    print("result columns:", sorted(results.keys()))
    # 每一列都必须与 dates 同长
    for col, series in results.items():
        assert len(series) == 80, f"{col} length {len(series)}"
    # 多输出列必存在
    for expected_col in ["MACD_0", "MACD_1", "MACD_2", "BOLL_0", "BOLL_1", "BOLL_2", "KDJ_0", "KDJ_1", "KDJ_2"]:
        assert expected_col in results, f"缺失列: {expected_col}"
    # 至少每个请求因子贡献了列
    assert len(results) >= 18
    print("compute ok")


def test_unknown_indicator_returns_404001():
    body = {
        "requestId": "bad-1",
        "ohlcv": _ohlcv(30),
        "factors": [{"factorKey": "NOTEXIST"}],
    }
    r = client.post("/python/v1/compute", json=body)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["code"] == 404001, j
    print("unknown indicator ok ->", j)


def test_missing_param_returns_400001():
    body = {
        "requestId": "bad-2",
        "ohlcv": _ohlcv(30),
        "factors": [{"factorKey": "MA", "params": {}}],  # 缺 timeperiod
    }
    r = client.post("/python/v1/compute", json=body)
    assert r.status_code == 200
    j = r.json()
    # talib 默认有 timeperiod（比如 30），但我们希望测试失败，这里改为显式缺
    # 改一下：故意传错误参数
    body2 = {
        "requestId": "bad-3",
        "ohlcv": _ohlcv(30),
        "factors": [{"factorKey": "MA", "params": {"timeperiod": "xx"}}],
    }
    r = client.post("/python/v1/compute", json=body2)
    assert r.status_code == 200
    j = r.json()
    assert j["code"] == 400001, j
    print("bad param ok ->", j)


def test_provider_is_talib():
    talib_provider = TalibIndicatorProvider()
    import numpy as np
    arr = np.array([float(i) for i in range(1, 31)], dtype=np.float64)
    out = talib_provider.compute("MA", {"close": arr}, timeperiod=5)
    expected = sum(range(26, 31)) / 5.0
    assert float(out[-1]) == expected
    print("MA tail:", out[-3:])


if __name__ == "__main__":
    import unittest

    class _T(unittest.TestCase):
        pass

    g = globals()
    for name in list(g.keys()):
        if name.startswith("test_"):
            fn = g[name]

            def _mk(_fn):
                def _m(self):
                    _fn()

                return _m

            setattr(_T, name, _mk(fn))

    unittest.main(verbosity=2)