"""FactorCalculatorService 集成测试（TR-7.1~7.6）。"""
import numpy as np
import pytest

from core.exceptions import (
    FactorNotComputableError,
    InvalidOutputIndexError,
    InvalidParamError,
    UnknownFactorError,
)
from services.factor.calculator import FactorCalculatorService


def _svc(temp_registry):
    return FactorCalculatorService(registry=temp_registry)


def _inputs(ohlcv_data):
    return {k: np.asarray([r[k] for r in ohlcv_data], dtype=np.float64)
            for k in ("open", "high", "low", "close", "volume")}


def test_compute_single_ma(temp_registry, ohlcv_data):
    # TR-7.1
    out = _svc(temp_registry).compute_single("MA", _inputs(ohlcv_data), {"timeperiod": 5})
    assert isinstance(out, np.ndarray)
    assert len(out) == len(ohlcv_data)


def test_compute_single_macd_output_index(temp_registry, ohlcv_data):
    # TR-7.2：output_index=2 → MACD 柱（第三路）
    out = _svc(temp_registry).compute_single("MACD", _inputs(ohlcv_data),
                                             {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9},
                                             output_index=2)
    assert isinstance(out, np.ndarray)
    assert len(out) == len(ohlcv_data)


def test_output_index_out_of_range(temp_registry, ohlcv_data):
    # TR-7.3
    with pytest.raises(InvalidOutputIndexError):
        _svc(temp_registry).compute_single("MACD", _inputs(ohlcv_data), output_index=9)


def test_tushare_not_computable(temp_registry, ohlcv_data):
    # TR-7.4
    with pytest.raises(FactorNotComputableError):
        _svc(temp_registry).compute_single("PE_TTM", _inputs(ohlcv_data))


def test_compute_batch(temp_registry, ohlcv_data):
    # TR-7.5
    out = _svc(temp_registry).compute_batch(
        [{"factorKey": "MA", "params": {"timeperiod": 5}},
         {"factorKey": "RSI", "params": {"timeperiod": 14}},
         {"factorKey": "CLOSE"}],
        _inputs(ohlcv_data),
    )
    assert set(out) == {"MA", "RSI", "CLOSE"}
    for v in out.values():
        assert len(v) == len(ohlcv_data)


def test_invalid_param(temp_registry, ohlcv_data):
    # TR-7.6：参数超出 max
    with pytest.raises(InvalidParamError):
        _svc(temp_registry).compute_single("MA", _inputs(ohlcv_data), {"timeperiod": 99999})


def test_unknown_factor(temp_registry, ohlcv_data):
    with pytest.raises(UnknownFactorError):
        _svc(temp_registry).compute_single("NOPE", _inputs(ohlcv_data))


def test_compute_multi_symbol(temp_registry, ohlcv_data):
    inp = _inputs(ohlcv_data)
    out = _svc(temp_registry).compute_multi_symbol(
        {"600519": inp, "000001": inp},
        [{"factorKey": "CLOSE"}],
    )
    assert set(out) == {"600519", "000001"}
    assert "CLOSE" in out["600519"]
