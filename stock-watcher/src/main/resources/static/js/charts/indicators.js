/**
 * 技术指标纯函数库（TA-Lib / akquant.talib 兼容口径）
 *
 * 设计原则：
 *  - 纯函数、无 DOM 依赖、无副作用，可在任意上下文（含 Worker）调用
 *  - 输入为 number[]（或 OHLCV 分项数组），输出为 number[] 或对象，前置预热段为 NaN
 *  - 与 akquant.talib（Rust 实现）输出对齐，确保回测信号与前端展示口径一致
 *  - 口径依据：.trae/rules/akquant/07-talib-indicators.md
 *
 * 对外 API（挂 window.Indicators）：
 *  MA / EMA / MACD / KDJ / RSI / BOLL / SAR / WR / CCI / ATR / OBV
 *  ADX / PLUS_DI / MINUS_DI
 *  辅助：toTimeSeries / toHistSeries / fmtDate / fmtTime
 *
 * 注意：本文件只做计算，不创建图表 series；series 组装由调用方或 kline-renderer 负责。
 */
(function () {
    'use strict';

    // ========== 通用辅助 ==========

    function fmtDate(s) {
        if (s && typeof s === 'string' && s.length === 8) {
            return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8);
        }
        return s;
    }

    function fmtTime(t) {
        if (typeof t === 'string') return t;
        if (typeof t === 'object' && t !== null && t.year != null) {
            return t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0');
        }
        return String(t);
    }

    function toTimeSeries(values, dates) {
        const result = [];
        for (let i = 0; i < values.length; i++) {
            const v = values[i];
            if (!isNaN(v) && isFinite(v)) {
                result.push({ time: fmtDate(dates[i]), value: parseFloat(v.toFixed(4)) });
            }
        }
        return result;
    }

    function toHistSeries(values, dates, upColor, downColor) {
        const result = [];
        for (let i = 0; i < values.length; i++) {
            const v = values[i];
            if (!isNaN(v) && isFinite(v)) {
                result.push({
                    time: fmtDate(dates[i]),
                    value: parseFloat(v.toFixed(4)),
                    color: v >= 0 ? upColor : downColor,
                });
            }
        }
        return result;
    }

    // ========== 均线类 ==========

    /**
     * 简单移动平均 SMA（对应 akquant.talib.MA / SMA，matype=0）
     * @param {number[]} data
     * @param {number} timeperiod 默认 30
     * @returns {number[]} 前 timeperiod-1 个为 NaN
     */
    function MA(data, timeperiod) {
        timeperiod = timeperiod || 30;
        const n = data.length;
        const result = new Array(n).fill(NaN);
        if (timeperiod < 1 || n < timeperiod) return result;
        let sum = 0;
        for (let i = 0; i < n; i++) {
            sum += data[i];
            if (i >= timeperiod) sum -= data[i - timeperiod];
            if (i >= timeperiod - 1) result[i] = sum / timeperiod;
        }
        return result;
    }
    var SMA = MA;

    /**
     * 指数移动平均 EMA（对应 akquant.talib.EMA）
     * TA-Lib 口径：首值用前 period 个值的 SMA 作为种子，后续 EMA 递推
     * @param {number[]} data
     * @param {number} period 默认 30
     * @returns {number[]} 前 period-1 个为 NaN
     */
    function EMA(data, period) {
        period = period || 30;
        const n = data.length;
        const result = new Array(n).fill(NaN);
        if (n < period || period < 1) return result;

        let sum = 0;
        for (let i = 0; i < period; i++) sum += data[i];
        let ema = sum / period;
        result[period - 1] = ema;

        const k = 2 / (period + 1);
        for (let i = period; i < n; i++) {
            ema = data[i] * k + ema * (1 - k);
            result[i] = ema;
        }
        return result;
    }

    /**
     * 布林带 BOLL（对应 akquant.talib.BBANDS，函数名 BBANDS）
     * middle = SMA(close, timeperiod)
     * std = 总体标准差（除以 N，非 N-1），与 TA-Lib STDDEV 一致
     * upper = middle + nbdevup * std；lower = middle - nbdevdn * std
     * @returns {{upper, middle, lower}}
     */
    function BOLL(closeData, timeperiod, nbdevup, nbdevdn) {
        timeperiod = timeperiod || 20;
        nbdevup = nbdevup == null ? 2.0 : nbdevup;
        nbdevdn = nbdevdn == null ? 2.0 : nbdevdn;
        const n = closeData.length;
        const upper = new Array(n).fill(NaN);
        const middle = new Array(n).fill(NaN);
        const lower = new Array(n).fill(NaN);

        for (let i = timeperiod - 1; i < n; i++) {
            let sum = 0;
            for (let p = 0; p < timeperiod; p++) sum += closeData[i - p];
            const mean = sum / timeperiod;

            let variance = 0;
            for (let p = 0; p < timeperiod; p++) {
                const diff = closeData[i - p] - mean;
                variance += diff * diff;
            }
            const std = Math.sqrt(variance / timeperiod);

            middle[i] = mean;
            upper[i] = mean + nbdevup * std;
            lower[i] = mean - nbdevdn * std;
        }
        return { upper: upper, middle: middle, lower: lower };
    }
    var BBANDS = BOLL;

    // ========== 动量振荡类 ==========

    /**
     * MACD（对应 akquant.talib.MACD，fastperiod=12, slowperiod=26, signalperiod=9）
     * DIF = EMA(close,12) - EMA(close,26)
     * DEA = EMA(DIF,9)
     * HIST = DIF - DEA（TA-Lib 标准公式，非 2*(DIF-DEA)）
     * @returns {{dif, dea, hist}}
     */
    function MACD(closeData, fastperiod, slowperiod, signalperiod) {
        fastperiod = fastperiod || 12;
        slowperiod = slowperiod || 26;
        signalperiod = signalperiod || 9;
        const n = closeData.length;
        const emaFast = EMA(closeData, fastperiod);
        const emaSlow = EMA(closeData, slowperiod);

        const dif = new Array(n).fill(NaN);
        for (let i = slowperiod - 1; i < n; i++) {
            if (!isNaN(emaFast[i]) && !isNaN(emaSlow[i])) {
                dif[i] = emaFast[i] - emaSlow[i];
            }
        }

        const firstValidIdx = slowperiod - 1;
        const validDif = dif.slice(firstValidIdx);
        const deaValid = EMA(validDif, signalperiod);

        const dea = new Array(n).fill(NaN);
        for (let i = 0; i < deaValid.length; i++) {
            dea[firstValidIdx + i] = deaValid[i];
        }

        const hist = new Array(n).fill(NaN);
        for (let i = 0; i < n; i++) {
            if (!isNaN(dif[i]) && !isNaN(dea[i])) {
                hist[i] = dif[i] - dea[i];
            }
        }
        return { dif: dif, dea: dea, hist: hist };
    }

    /**
     * RSI（对应 akquant.talib.RSI，Wilder 平滑）
     * 首值用前 period 个差值的简单平均作为种子，后续 Wilder 平滑
     * @param {number[]} closeData
     * @param {number[]} [periods=[6,12,24]] 周期数组
     * @returns {{rsi6, rsi12, rsi24}}（key 随 periods 动态生成）
     */
    function RSI(closeData, periods) {
        periods = periods || [6, 12, 24];
        const n = closeData.length;
        const result = {};

        for (const period of periods) {
            const key = 'rsi' + period;
            const rsi = new Array(n).fill(NaN);
            if (n < period + 1) { result[key] = rsi; continue; }

            const gains = new Array(n).fill(0);
            const losses = new Array(n).fill(0);
            for (let i = 1; i < n; i++) {
                const diff = closeData[i] - closeData[i - 1];
                gains[i] = diff > 0 ? diff : 0;
                losses[i] = diff < 0 ? -diff : 0;
            }

            let avgGain = 0, avgLoss = 0;
            for (let i = 1; i <= period; i++) {
                avgGain += gains[i];
                avgLoss += losses[i];
            }
            avgGain /= period;
            avgLoss /= period;

            if (avgLoss === 0) {
                rsi[period] = 100;
            } else {
                const rs = avgGain / avgLoss;
                rsi[period] = 100 - 100 / (1 + rs);
            }

            for (let i = period + 1; i < n; i++) {
                avgGain = (avgGain * (period - 1) + gains[i]) / period;
                avgLoss = (avgLoss * (period - 1) + losses[i]) / period;
                if (avgLoss === 0) {
                    rsi[i] = 100;
                } else {
                    const rs = avgGain / avgLoss;
                    rsi[i] = 100 - 100 / (1 + rs);
                }
            }
            result[key] = rsi;
        }
        return result;
    }

    /**
     * KDJ（对应 akquant.talib.STOCH + J 自算，fastk_period=9, slowk_period=3, slowd_period=3）
     * RSV = (close - lowest_low(9)) / (highest_high(9) - lowest_low(9)) * 100
     * K = SMA(RSV, 3)；D = SMA(K, 3)；J = 3*K - 2*D
     * @returns {{k, d, j}}
     */
    function KDJ(highData, lowData, closeData, fastk_period, slowk_period, slowd_period) {
        fastk_period = fastk_period || 9;
        slowk_period = slowk_period || 3;
        slowd_period = slowd_period || 3;
        const n = closeData.length;
        const k = new Array(n).fill(NaN);
        const d = new Array(n).fill(NaN);
        const j = new Array(n).fill(NaN);

        const rsv = new Array(n).fill(NaN);
        for (let i = fastk_period - 1; i < n; i++) {
            let highest = -Infinity, lowest = Infinity;
            for (let p = 0; p < fastk_period; p++) {
                const h = highData[i - p];
                const l = lowData[i - p];
                if (h > highest) highest = h;
                if (l < lowest) lowest = l;
            }
            if (highest === lowest) {
                rsv[i] = 0;
            } else {
                rsv[i] = (closeData[i] - lowest) / (highest - lowest) * 100;
            }
        }

        for (let i = fastk_period - 1 + slowk_period - 1; i < n; i++) {
            let sum = 0;
            for (let p = 0; p < slowk_period; p++) sum += rsv[i - p];
            k[i] = sum / slowk_period;
        }

        for (let i = fastk_period - 1 + slowk_period - 1 + slowd_period - 1; i < n; i++) {
            let sum = 0;
            for (let p = 0; p < slowd_period; p++) sum += k[i - p];
            d[i] = sum / slowd_period;
        }

        for (let i = 0; i < n; i++) {
            if (!isNaN(k[i]) && !isNaN(d[i])) {
                j[i] = 3 * k[i] - 2 * d[i];
            }
        }
        return { k: k, d: d, j: j };
    }
    var STOCH = KDJ;

    /**
     * WR 威廉指标（对应 akquant.talib.WILLR，timeperiod=14）
     * WR = (highest_high - close) / (highest_high - lowest_low) * (-100)
     * TA-Lib 返回负值区间 [-100, 0]
     */
    function WR(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        for (let i = timeperiod - 1; i < n; i++) {
            let highest = -Infinity, lowest = Infinity;
            for (let p = 0; p < timeperiod; p++) {
                if (highData[i - p] > highest) highest = highData[i - p];
                if (lowData[i - p] < lowest) lowest = lowData[i - p];
            }
            if (highest === lowest) {
                result[i] = 0;
            } else {
                result[i] = (highest - closeData[i]) / (highest - lowest) * (-100);
            }
        }
        return result;
    }
    var WILLR = WR;

    /**
     * CCI 顺势指标（对应 akquant.talib.CCI，timeperiod=14）
     * TP = (high + low + close) / 3
     * MD = TP 与其均值的平均绝对偏差（TA-Lib 口径，除以 N）
     * CCI = (TP - MA(TP)) / (0.015 * MD)
     */
    function CCI(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        const tp = new Array(n);
        for (let i = 0; i < n; i++) {
            tp[i] = (highData[i] + lowData[i] + closeData[i]) / 3;
        }
        for (let i = timeperiod - 1; i < n; i++) {
            let sum = 0;
            for (let p = 0; p < timeperiod; p++) sum += tp[i - p];
            const mean = sum / timeperiod;

            let absSum = 0;
            for (let p = 0; p < timeperiod; p++) absSum += Math.abs(tp[i - p] - mean);
            const md = absSum / timeperiod;

            if (md === 0) {
                result[i] = 0;
            } else {
                result[i] = (tp[i] - mean) / (0.015 * md);
            }
        }
        return result;
    }

    // ========== 趋势方向类（DMI 家族）==========

    /**
     * 计算单根 bar 的 +DM / -DM / TR（DMI 家族公用）
     */
    function calcDmTr(highData, lowData, closeData, i) {
        const up = highData[i] - highData[i - 1];
        const down = lowData[i - 1] - lowData[i];
        const plusDM = (up > down && up > 0) ? up : 0;
        const minusDM = (down > up && down > 0) ? down : 0;
        const tr = Math.max(
            highData[i] - lowData[i],
            Math.abs(highData[i] - closeData[i - 1]),
            Math.abs(lowData[i] - closeData[i - 1])
        );
        return { plusDM: plusDM, minusDM: minusDM, tr: tr };
    }

    /**
     * +DI（对应 akquant.talib.PLUS_DI，timeperiod=14）
     */
    function PLUS_DI(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        if (n < timeperiod + 1) return result;

        let plusDMWild = 0, trWild = 0;
        for (let i = 1; i <= timeperiod; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            plusDMWild += d.plusDM;
            trWild += d.tr;
        }
        result[timeperiod] = trWild === 0 ? 0 : (plusDMWild / trWild) * 100;

        for (let i = timeperiod + 1; i < n; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            plusDMWild = plusDMWild - plusDMWild / timeperiod + d.plusDM;
            trWild = trWild - trWild / timeperiod + d.tr;
            result[i] = trWild === 0 ? 0 : (plusDMWild / trWild) * 100;
        }
        return result;
    }

    /**
     * -DI（对应 akquant.talib.MINUS_DI，timeperiod=14）
     */
    function MINUS_DI(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        if (n < timeperiod + 1) return result;

        let minusDMWild = 0, trWild = 0;
        for (let i = 1; i <= timeperiod; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            minusDMWild += d.minusDM;
            trWild += d.tr;
        }
        result[timeperiod] = trWild === 0 ? 0 : (minusDMWild / trWild) * 100;

        for (let i = timeperiod + 1; i < n; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            minusDMWild = minusDMWild - minusDMWild / timeperiod + d.minusDM;
            trWild = trWild - trWild / timeperiod + d.tr;
            result[i] = trWild === 0 ? 0 : (minusDMWild / trWild) * 100;
        }
        return result;
    }

    /**
     * ADX（对应 akquant.talib.ADX，timeperiod=14）
     * 先算 DX = |+DI - -DI| / (+DI + -DI) * 100，再 Wilder 平滑 ADX
     */
    function ADX(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        if (n < 2 * timeperiod + 1) return result;

        const plusDI = PLUS_DI(highData, lowData, closeData, timeperiod);
        const minusDI = MINUS_DI(highData, lowData, closeData, timeperiod);

        const dxArr = new Array(n).fill(NaN);
        for (let i = 0; i < n; i++) {
            if (!isNaN(plusDI[i]) && !isNaN(minusDI[i])) {
                const sum = plusDI[i] + minusDI[i];
                dxArr[i] = sum === 0 ? 0 : Math.abs(plusDI[i] - minusDI[i]) / sum * 100;
            }
        }

        let adxWild = 0;
        let count = 0;
        const startIdx = 2 * timeperiod;
        for (let i = timeperiod; i <= startIdx; i++) {
            if (!isNaN(dxArr[i])) { adxWild += dxArr[i]; count++; }
        }
        if (count > 0) adxWild /= count;
        result[startIdx] = adxWild;

        for (let i = startIdx + 1; i < n; i++) {
            if (!isNaN(dxArr[i])) {
                adxWild = (adxWild * (timeperiod - 1) + dxArr[i]) / timeperiod;
                result[i] = adxWild;
            }
        }
        return result;
    }

    // ========== 波动/能量类 ==========

    /**
     * ATR 真实波幅（对应 akquant.talib.ATR，timeperiod=14，Wilder 平滑）
     */
    function ATR(highData, lowData, closeData, timeperiod) {
        timeperiod = timeperiod || 14;
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        if (n < timeperiod + 1) return result;

        let trWild = 0;
        for (let i = 1; i <= timeperiod; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            trWild += d.tr;
        }
        result[timeperiod] = trWild / timeperiod;

        for (let i = timeperiod + 1; i < n; i++) {
            const d = calcDmTr(highData, lowData, closeData, i);
            result[i] = (result[i - 1] * (timeperiod - 1) + d.tr) / timeperiod;
        }
        return result;
    }

    /**
     * OBV 能量潮（对应 akquant.talib.OBV）
     * 收盘上涨则累加量，下跌则减，平则持平
     */
    function OBV(closeData, volume) {
        const n = closeData.length;
        const result = new Array(n).fill(NaN);
        if (n === 0) return result;
        result[0] = 0;
        for (let i = 1; i < n; i++) {
            if (closeData[i] > closeData[i - 1]) {
                result[i] = result[i - 1] + volume[i];
            } else if (closeData[i] < closeData[i - 1]) {
                result[i] = result[i - 1] - volume[i];
            } else {
                result[i] = result[i - 1];
            }
        }
        return result;
    }

    /**
     * SAR 抛物线（对应 akquant.talib.SAR，acceleration=0.02, maximum=0.2）
     * 经典递推：多头下 EP 加速因子累加，空头反之；翻转到反向时重置 AF。
     */
    function SAR(highData, lowData, acceleration, maximum) {
        acceleration = acceleration || 0.02;
        maximum = maximum || 0.2;
        const n = highData.length;
        const result = new Array(n).fill(NaN);
        if (n < 2) return result;

        let isLong = highData[1] >= highData[0];
        let sar = isLong ? lowData[0] : highData[0];
        let ep = isLong ? highData[1] : lowData[1];
        let af = acceleration;
        result[0] = sar;

        for (let i = 1; i < n; i++) {
            sar = sar + af * (ep - sar);
            if (isLong) {
                if (lowData[i] < sar) {
                    isLong = false;
                    sar = ep;
                    ep = lowData[i];
                    af = acceleration;
                } else {
                    if (highData[i] > ep) { ep = highData[i]; af = Math.min(af + acceleration, maximum); }
                }
            } else {
                if (highData[i] > sar) {
                    isLong = true;
                    sar = ep;
                    ep = highData[i];
                    af = acceleration;
                } else {
                    if (lowData[i] < ep) { ep = lowData[i]; af = Math.min(af + acceleration, maximum); }
                }
            }
            result[i] = sar;
        }
        return result;
    }

    // ========== 导出 ==========
    const api = {
        MA: MA, SMA: SMA, EMA: EMA, BOLL: BOLL, BBANDS: BBANDS,
        MACD: MACD, RSI: RSI, KDJ: KDJ, STOCH: STOCH,
        WR: WR, WILLR: WILLR, CCI: CCI,
        PLUS_DI: PLUS_DI, MINUS_DI: MINUS_DI, ADX: ADX,
        ATR: ATR, OBV: OBV, SAR: SAR,
        fmtDate: fmtDate, fmtTime: fmtTime,
        toTimeSeries: toTimeSeries, toHistSeries: toHistSeries,
    };
    window.Indicators = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
