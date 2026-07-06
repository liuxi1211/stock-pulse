"""选股中心 pytest 共享夹具（spec 003 阶段 3 Task 12）。

复用 ``tests/test_factor/conftest.py`` 的 ``ohlcv_data`` 与 ``temp_registry`` 思路，
为选股中心测试构造：

- ``screener_client``：FastAPI TestClient，把 ``services.screener.factor_precompute``
  里模块级 ``factor_calculator`` / ``factor_registry`` 替换为 temp 版本，
  使选股链路（precompute_factors）不触真实 factors.json。
- ``sample_candidates``：5 只设计好的候选股票 dict，便于断言
  「低估值优质（PE<20 AND ROE>10）命中 000001.SZ 与 600000.SH；
   ST/涨停/次新被静态过滤」。
"""
import pytest
from fastapi.testclient import TestClient

from config import settings
from services.factor.calculator import FactorCalculatorService
from services.factor.registry import FactorRegistry


@pytest.fixture
def ohlcv_data() -> list[dict]:
    """250 根日线 OHLCV 记录（确定性伪随机，与 test_factor 同算法独立构造）。"""
    import numpy as np

    rng = np.random.default_rng(42)
    n = 250
    close = 10.0 + np.cumsum(rng.normal(0, 0.5, n))
    open_ = close + rng.normal(0, 0.2, n)
    high = np.maximum(open_, close) + rng.uniform(0, 0.5, n)
    low = np.minimum(open_, close) - rng.uniform(0, 0.5, n)
    volume = rng.uniform(1e6, 1e7, n)
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
def screener_client(temp_registry):
    """TestClient，patch ``services.screener.factor_precompute`` 的模块级
    ``factor_calculator`` / ``factor_registry`` 到 temp 版本。

    选股链路里只有 ``factor_precompute`` 模块级 import 了这两个符号；
    ``ranking`` / ``filters`` / ``engine`` 均不直接引用，无需 patch。
    """
    import main as main_mod
    import services.screener.factor_precompute as fp

    calc = FactorCalculatorService(registry=temp_registry)
    orig_calc = fp.factor_calculator
    orig_reg = fp.factor_registry
    fp.factor_calculator = calc
    fp.factor_registry = temp_registry
    with TestClient(main_mod.app) as c:
        yield c
    fp.factor_calculator = orig_calc
    fp.factor_registry = orig_reg


@pytest.fixture
def sample_candidates(ohlcv_data) -> dict[str, dict]:
    """5 只设计好的候选股票，便于断言。

    设计（基本面 fundamentals）：
      - 000001.SZ：PE=8 / ROE=18 / 银行 / 正常股      → 应被「PE<20 AND ROE>10」命中
      - 000002.SZ：PE=25 / ROE=8  / 房地产 / ST        → 静态过滤排除(ST)；条件不命中
      - 600000.SH：PE=15 / ROE=12 / 银行 / 正常股      → 应被「PE<20 AND ROE>10」命中
      - 600519.SH：PE=40 / ROE=30 / 白酒 / 涨停        → 静态过滤排除(涨停)；条件不命中
      - 300750.SZ：PE=30 / ROE=5  / 电池  / 次新(<365天) → 静态过滤排除(次新)；条件不命中

    TOTAL_MV 用于排序断言（设不同量级）。
    """
    return {
        "000001.SZ": {
            "ohlcv_history": ohlcv_data,
            "fundamentals": {"PE_TTM": 8.0, "ROE_TTM": 18.0, "TOTAL_MV": 2.0e10},
            "meta": {
                "is_st": False,
                "is_suspended": False,
                "is_limit_up": False,
                "is_limit_down": False,
                "industry": "银行",
                "list_date": "2010-01-01",
            },
        },
        "000002.SZ": {
            "ohlcv_history": ohlcv_data,
            "fundamentals": {"PE_TTM": 25.0, "ROE_TTM": 8.0, "TOTAL_MV": 1.5e10},
            "meta": {
                "is_st": True,
                "is_suspended": False,
                "is_limit_up": False,
                "is_limit_down": False,
                "industry": "房地产",
                "list_date": "2011-06-15",
            },
        },
        "600000.SH": {
            "ohlcv_history": ohlcv_data,
            "fundamentals": {"PE_TTM": 15.0, "ROE_TTM": 12.0, "TOTAL_MV": 1.8e10},
            "meta": {
                "is_st": False,
                "is_suspended": False,
                "is_limit_up": False,
                "is_limit_down": False,
                "industry": "银行",
                "list_date": "2009-11-26",
            },
        },
        "600519.SH": {
            "ohlcv_history": ohlcv_data,
            "fundamentals": {"PE_TTM": 40.0, "ROE_TTM": 30.0, "TOTAL_MV": 2.0e11},
            "meta": {
                "is_st": False,
                "is_suspended": False,
                "is_limit_up": True,
                "is_limit_down": False,
                "industry": "白酒",
                "list_date": "2001-08-27",
            },
        },
        "300750.SZ": {
            "ohlcv_history": ohlcv_data,
            "fundamentals": {"PE_TTM": 30.0, "ROE_TTM": 5.0, "TOTAL_MV": 9.0e9},
            "meta": {
                "is_st": False,
                "is_suspended": False,
                "is_limit_up": False,
                "is_limit_down": False,
                "industry": "电池",
                # 距离选股日 2026-07-03 仅约 60 天 → 次新股
                "list_date": "2026-05-01",
            },
        },
    }
