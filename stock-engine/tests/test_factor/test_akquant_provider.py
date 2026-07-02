"""AkquantTalibProvider 正确性测试（TR-5.1~5.6）。

结果须与 ``akquant.talib`` 原生调用 ``np.allclose``（口径一致，NFR-2）。
"""
import akquant.talib as talib
import numpy as np

from core.exceptions import UnknownFactorError
from services.factor.providers.akquant_provider import AkquantTalibProvider
from services.factor.providers.derived_provider import DerivedProvider
from services.factor.providers.raw_provider import RawDataProvider


def _inputs(ohlcv_data):
    d = {k: np.asarray([r[k] for r in ohlcv_data], dtype=np.float64)
         for k in ("open", "high", "low", "close", "volume")}
    return d


def test_ma_matches_talib(ohlcv_data):
    # TR-5.1
    inp = _inputs(ohlcv_data)
    out = AkquantTalibProvider().compute("MA", inp, ["close"], {"timeperiod": 5})
    ref = np.asarray(talib.MA(inp["close"], timeperiod=5), dtype=np.float64)
    assert np.allclose(out, ref, equal_nan=True)


def test_macd_three_outputs(ohlcv_data):
    # TR-5.2
    inp = _inputs(ohlcv_data)
    out = AkquantTalibProvider().compute("MACD", inp, ["close"],
                                         {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9})
    assert isinstance(out, tuple) and len(out) == 3
    dif_ref, dea_ref, hist_ref = talib.MACD(inp["close"])
    assert np.allclose(out[0], np.asarray(dif_ref, dtype=np.float64), equal_nan=True)
    assert np.allclose(out[2], np.asarray(hist_ref, dtype=np.float64), equal_nan=True)


def test_kdj_j_synthesis(ohlcv_data):
    # TR-5.3：J = 3K − 2D
    inp = _inputs(ohlcv_data)
    out = AkquantTalibProvider().compute("STOCH", inp, ["high", "low", "close"],
                                         {"fastk_period": 9, "slowk_period": 3, "slowk_matype": 0,
                                          "slowd_period": 3, "slowd_matype": 0})
    assert isinstance(out, tuple) and len(out) == 3
    k, d = out[0], out[1]
    assert np.allclose(out[2], 3 * k - 2 * d, equal_nan=True)


def test_boll_three_outputs(ohlcv_data):
    # TR-5.4：upper / mid / lower
    inp = _inputs(ohlcv_data)
    out = AkquantTalibProvider().compute("BBANDS", inp, ["close"],
                                         {"timeperiod": 20, "nbdevup": 2.0, "nbdevdn": 2.0})
    assert isinstance(out, tuple) and len(out) == 3
    up_ref, mid_ref, lo_ref = talib.BBANDS(inp["close"], timeperiod=20, nbdevup=2.0, nbdevdn=2.0)
    assert np.allclose(out[0], np.asarray(up_ref, dtype=np.float64), equal_nan=True)


def test_insufficient_data_nan_prefix(ohlcv_data):
    # TR-5.5：数据不足前补 NaN，不报错
    inp = _inputs(ohlcv_data[:10])
    out = AkquantTalibProvider().compute("MA", inp, ["close"], {"timeperiod": 20})
    assert np.isnan(out).all()  # 10 根 < 20 周期，全部 NaN


def test_raw_passthrough(ohlcv_data):
    inp = _inputs(ohlcv_data)
    out = RawDataProvider().compute(None, inp, ["close"], {})
    assert np.array_equal(out, inp["close"])


def test_derived_vol_ma(ohlcv_data):
    inp = _inputs(ohlcv_data)
    out = DerivedProvider().compute("MA", inp, ["volume"], {"timeperiod": 20})
    ref = np.asarray(talib.MA(inp["volume"], timeperiod=20), dtype=np.float64)
    assert np.allclose(out, ref, equal_nan=True)


def test_unknown_func_raises(ohlcv_data):
    # TR-5.6：非法函数名抛 UnknownFactorError
    inp = _inputs(ohlcv_data)
    try:
        AkquantTalibProvider().compute("NO_SUCH_FUNC", inp, ["close"], {})
        assert False, "应抛异常"
    except UnknownFactorError:
        pass
