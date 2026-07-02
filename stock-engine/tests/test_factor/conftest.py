"""pytest 共享夹具。

- ``ohlcv_data``：250 根确定性日线 OHLCV（list[dict]）。
- ``temp_registry``：每个测试独立的 FactorRegistry（运行时文件落在 tmp_path，互不污染）。
- ``client``：FastAPI TestClient，路由指向 temp_registry（CRUD 测试不污染真实 factors.json）。
"""
import numpy as np
import pytest
from fastapi.testclient import TestClient

from config import settings
from services.factor.calculator import FactorCalculatorService
from services.factor.registry import FactorRegistry


@pytest.fixture
def ohlcv_data() -> list[dict]:
    """250 根日线 OHLCV 记录（确定性伪随机，便于断言）。"""
    rng = np.random.default_rng(42)
    n = 250
    close = 10.0 + np.cumsum(rng.normal(0, 0.5, n))
    open_ = close + rng.normal(0, 0.2, n)
    high = np.maximum(open_, close) + rng.uniform(0, 0.5, n)
    low = np.minimum(open_, close) - rng.uniform(0, 0.5, n)
    volume = rng.uniform(1e6, 1e7, n)
    # date 用零填充计数，保证字典序 = 时间序
    return [
        {
            "date": f"D{i:04d}",
            "open": float(open_[i]),
            "high": float(high[i]),
            "low": float(low[i]),
            "close": float(close[i]),
            "volume": float(volume[i]),
        }
        for i in range(n)
    ]


@pytest.fixture
def temp_registry(tmp_path) -> FactorRegistry:
    """独立运行时文件的 registry（从真实种子加载，CRUD 仅影响 tmp 文件）。"""
    runtime = tmp_path / "factors.json"
    return FactorRegistry(runtime_file=str(runtime), seed_file=settings.factors_seed_file)


@pytest.fixture
def client(temp_registry):
    """TestClient，路由层 registry/calculator 替换为 temp_registry。"""
    import main as main_mod
    import api.v1.factor as factor_api

    calc = FactorCalculatorService(registry=temp_registry)
    orig_reg = factor_api.factor_registry
    orig_calc = factor_api.factor_calculator
    factor_api.factor_registry = temp_registry
    factor_api.factor_calculator = calc
    with TestClient(main_mod.app) as c:
        yield c
    factor_api.factor_registry = orig_reg
    factor_api.factor_calculator = orig_calc
