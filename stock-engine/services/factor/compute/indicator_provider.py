"""Indicator Provider 抽象与注册中心（主力：akquant.talib）。

## 设计意图
- Java 持有「因子定义」，Python 只做无状态数值计算。
- 统一调用 ``provider.compute(name, inputs, **params)`` 返回 ``np.ndarray`` 或 ``tuple[np.ndarray, ...]``。
- 当前主力：**akquant.talib**（akquant 0.2.34；参数名与 C talib 一致，如 ``timeperiod / fastperiod / nbdevup``）。
- 简单直通类（CLOSE / HIGH / LOW / VOLUME）与成交量均线（VOL_MA / VOL_EMA）由内置 NumpySimpleProvider 实现，不依赖任何第三方指标库。

## 20 个标准因子
- MA / EMA / MACD / RSI / BOLL / SAR / KDJ / ADX / PLUS_DI / MINUS_DI / WILLR / CCI / ATR / OBV → ``akquant.talib.*``
- CLOSE / HIGH / LOW / VOLUME / VOL_MA / VOL_EMA → ``NumpySimpleProvider``
"""

from __future__ import annotations

import numpy as np

from services.factor.protocol import FactorComputeError

# ============================================================
# 元数据（factorKey -> 所需输入列 + akquant 函数名）
# ============================================================

_STANDARD_NAMES = {
    "MA", "EMA", "BOLL", "SAR", "MACD", "RSI", "KDJ",
    "ADX", "PLUS_DI", "MINUS_DI", "WILLR", "CCI", "ATR", "OBV",
}

_STANDARD_INPUTS = {
    "MA": ("close",),
    "EMA": ("close",),
    "BOLL": ("close",),
    "SAR": ("high", "low"),
    "MACD": ("close",),
    "RSI": ("close",),
    "KDJ": ("high", "low", "close"),
    "ADX": ("high", "low", "close"),
    "PLUS_DI": ("high", "low", "close"),
    "MINUS_DI": ("high", "low", "close"),
    "WILLR": ("high", "low", "close"),
    "CCI": ("high", "low", "close"),
    "ATR": ("high", "low", "close"),
    "OBV": ("close", "volume"),
}

# factorKey -> akquant 函数名
_AKQUANT_NAME = {
    "MA": "MA", "EMA": "EMA", "BOLL": "BBANDS", "SAR": "SAR",
    "MACD": "MACD", "RSI": "RSI", "KDJ": "STOCH", "ADX": "ADX",
    "PLUS_DI": "PLUS_DI", "MINUS_DI": "MINUS_DI",
    "WILLR": "WILLR", "CCI": "CCI", "ATR": "ATR", "OBV": "OBV",
}


def _coerce_params(params):
    out = {}
    for k, v in params.items():
        if isinstance(v, bool):
            raise FactorComputeError(400001, f"BAD_PARAM:{k}=bool")
        if isinstance(v, int):
            out[k] = int(v)
        elif isinstance(v, float):
            out[k] = float(v)
        else:
            raise FactorComputeError(400001, f"BAD_PARAM:{k} type={type(v).__name__}")
    return out


def _pick_arrays(name, inputs):
    needed = _STANDARD_INPUTS[name]
    missing = [c for c in needed if c not in inputs or inputs[c] is None]
    if missing:
        raise FactorComputeError(400001, f"MISSING_INPUT:{','.join(missing)}")
    return tuple(np.asarray(inputs[c], dtype=np.float64) for c in needed)


def _as_float64(out):
    if isinstance(out, tuple):
        return tuple(np.asarray(x, dtype=np.float64) for x in out)
    return np.asarray(out, dtype=np.float64)


# ============================================================
# Provider 抽象
# ============================================================

class IndicatorProvider:
    """抽象：``compute(name, inputs, **params)`` 返回 array 或 array 元组。"""

    def supported(self):
        raise NotImplementedError

    def compute(self, name, inputs, **params):
        raise NotImplementedError


# ============================================================
# akquant.talib 主力 provider
# ============================================================

class AkquantIndicatorProvider(IndicatorProvider):
    """基于 `akquant.talib`。"""

    def __init__(self):
        import akquant.talib
        self._aq = akquant.talib

    def supported(self):
        return set(_STANDARD_NAMES)

    def compute(self, name, inputs, **params):
        if name not in _STANDARD_NAMES:
            raise FactorComputeError(404001, f"UNKNOWN_INDICATOR:{name}")
        params = _coerce_params(params)
        arrays = _pick_arrays(name, inputs)
        fn = getattr(self._aq, _AKQUANT_NAME[name])
        try:
            out = fn(*arrays, **params)
        except (TypeError, ValueError) as exc:
            raise FactorComputeError(400001, f"BAD_PARAM:{exc}") from exc
        except Exception as exc:  # noqa: BLE001
            raise FactorComputeError(500001, f"COMPUTE_ERROR:{exc}") from exc

        out = _as_float64(out)

        if name == "KDJ":
            slowk, slowd = out[0], out[1]
            j = 3.0 * slowk - 2.0 * slowd
            return slowk, slowd, j
        return out


# ============================================================
# Numpy 直通 / 成交量均线 provider
# ============================================================

class NumpySimpleProvider(IndicatorProvider):
    """CLOSE / HIGH / LOW / VOLUME / VOL_MA / VOL_EMA。"""

    _SUPPORTED = {"CLOSE", "HIGH", "LOW", "VOLUME", "VOL_MA", "VOL_EMA"}
    _INPUTS = {
        "CLOSE": "close", "HIGH": "high", "LOW": "low", "VOLUME": "volume",
        "VOL_MA": "volume", "VOL_EMA": "volume",
    }

    def supported(self):
        return self._SUPPORTED

    def compute(self, name, inputs, **params):
        if name not in self._SUPPORTED:
            raise FactorComputeError(404001, f"UNKNOWN_INDICATOR:{name}")
        col = self._INPUTS[name]
        if col not in inputs or inputs[col] is None:
            raise FactorComputeError(400001, f"MISSING_INPUT:{col}")
        arr = np.asarray(inputs[col], dtype=np.float64)

        if name in ("CLOSE", "HIGH", "LOW", "VOLUME"):
            return arr.copy()

        period = params.get("timeperiod")
        if not isinstance(period, int) or isinstance(period, bool) or period < 1:
            raise FactorComputeError(400001, f"BAD_PARAM:timeperiod={period!r}")

        if name == "VOL_MA":
            return _simple_sma(arr, period)
        return _simple_ema(arr, period)


def _simple_sma(arr, period):
    n = len(arr)
    out = np.full(n, np.nan, dtype=np.float64)
    if period > n or period <= 0:
        return out
    csum = np.concatenate([[0.0], np.cumsum(np.where(np.isnan(arr), 0.0, arr))])
    raw = (csum[period:] - csum[:-period]) / period
    valid = np.isfinite(arr).astype(np.float64)
    csum_v = np.concatenate([[0.0], np.cumsum(valid)])
    counts = csum_v[period:] - csum_v[:-period]
    raw = np.where(counts == period, raw, np.nan)
    out[period - 1:] = raw
    return out


def _simple_ema(arr, period):
    n = len(arr)
    out = np.full(n, np.nan, dtype=np.float64)
    if period > n:
        return out
    alpha = 2.0 / (period + 1.0)
    first_idx = period - 1
    seed_window = arr[:period]
    seed = float(np.nanmean(seed_window)) if np.isfinite(seed_window).any() else np.nan
    prev = seed
    out[first_idx] = prev
    for i in range(first_idx + 1, n):
        if np.isnan(arr[i]):
            out[i] = prev
        else:
            prev = alpha * arr[i] + (1.0 - alpha) * prev
            out[i] = prev
    return out


# ============================================================
# ComboProvider：akquant 主 + numpy 直通，作为唯一对外 provider
# ============================================================

class _ComboProvider(IndicatorProvider):
    def __init__(self, primary, simple):
        self._primary = primary
        self._simple = simple

    def supported(self):
        return self._primary.supported() | self._simple.supported()

    def compute(self, name, inputs, **params):
        if name in self._primary.supported():
            return self._primary.compute(name, inputs, **params)
        return self._simple.compute(name, inputs, **params)


# ============================================================
# 全局单例：进程加载时初始化一次即可
# ============================================================

_PROVIDER = _ComboProvider(AkquantIndicatorProvider(), NumpySimpleProvider())


def get_provider():
    return _PROVIDER


def current_provider_name():
    return "akquant+numpy"
