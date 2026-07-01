"""技术指标计算服务（主力：akquant.talib）。

为 ``api/v1/quote.py`` 的 `/quote/calculate_indicators` 提供实现。
直接用 akquant 计算 MACD / KDJ / RSI，不再依赖 pandas。
"""

from __future__ import annotations

import akquant.talib as _aq
import numpy as np

from core.logger import logger


class TechIndicatorService:
    """为 K 线列表计算 MACD / KDJ / RSI 并返回同一长度的 dict 列表。"""

    async def calculate_indicators_for_kline(self, kline_data):
        if not kline_data:
            return []

        try:
            closes = np.asarray([float(x["close"]) for x in kline_data], dtype=np.float64)
            highs = np.asarray([float(x["high"]) for x in kline_data], dtype=np.float64)
            lows = np.asarray([float(x["low"]) for x in kline_data], dtype=np.float64)
        except Exception as exc:
            raise ValueError(f"输入 K 线缺少 close/high/low: {exc}") from exc

        # MACD(fast=12, slow=26, signal=9) -> (dif, dea, hist)
        dif, dea, hist = _aq.MACD(closes, fastperiod=12, slowperiod=26, signalperiod=9)

        # KDJ: STOCH(fastk_period=9, slowk_period=3, slowd_period=3) -> (slowk, slowd)
        slowk, slowd = _aq.STOCH(
            highs, lows, closes,
            fastk_period=9, slowk_period=3, slowd_period=3,
            slowk_matype=0, slowd_matype=0,
        )
        j = 3.0 * np.asarray(slowk, dtype=np.float64) - 2.0 * np.asarray(slowd, dtype=np.float64)

        # RSI(6) / RSI(12)
        rsi6 = _aq.RSI(closes, timeperiod=6)
        rsi12 = _aq.RSI(closes, timeperiod=12)

        out = []
        n = len(kline_data)
        for i in range(n):
            row = dict(kline_data[i])
            row["macd_dif"] = _num(dif[i])
            row["macd_dea"] = _num(dea[i])
            row["macd_hist"] = _num(hist[i])
            row["kdj_k"] = _num(slowk[i])
            row["kdj_d"] = _num(slowd[i])
            row["kdj_j"] = _num(j[i])
            row["rsi_6"] = _num(rsi6[i])
            row["rsi_12"] = _num(rsi12[i])
            out.append(row)

        logger.info("技术指标计算完成，共 %d 条", n)
        return out


def _num(x):
    """把 numpy 标量 / NaN 转成可序列化的 float（NaN 用 None 表示）。"""
    if x is None:
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    if v != v:  # NaN
        return None
    return v
