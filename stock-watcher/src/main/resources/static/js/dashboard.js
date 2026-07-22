/**
 * Dashboard page JavaScript
 * K-line + Watchlist linkage, Market Rankings, Dynamic Charts
 */

// ========== K-Line Chart (TradingView Lightweight Charts) ==========
let klineChart = null;
let candleSeries = null;
let volumeSeries = null;
let ma5Series = null;
let ma10Series = null;
let ma20Series = null;
let klineLegendEl = null;
let currentStockCode = '600519';
let currentStockName = '';
let currentPeriod = 'daily';

// 技术指标状态
let bollUpperSeries = null;
let bollMidSeries = null;
let bollLowerSeries = null;
let subIndicatorChart = null;
let subIndicatorSeries = {};
let subIndicatorLegendEl = null;
let currentMainIndicator = 'ma';      // 'ma' | 'boll'
let currentSubIndicator = 'macd';      // 'macd' | 'kdj' | 'rsi' | 'none'
let klineDataCache = null;            // 缓存原始 K 线数据,供指标切换复用
let syncingTimeScale = false;         // 防止主/副图时间轴联动死循环

// Watchlist data cache
let watchlistData = [];

function loadKline(stockCode, stockName, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    currentStockCode = stockCode;
    if (stockName) currentStockName = stockName;
    highlightWatchlistRow(stockCode);
    updateKlineTitle();

    StockApp.get('/kline/' + stockCode, { period: currentPeriod }, function(resp) {
        if (resp.code !== 200) return;
        renderKline(resp.data, stockCode);
    });
}

function changePeriod(period, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    currentPeriod = period;
    updateKlineTitle();

    StockApp.get('/kline/' + currentStockCode, { period: period }, function(resp) {
        if (resp.code !== 200) return;
        renderKline(resp.data, currentStockCode);
    });
}

function searchKline() {
    const input = document.getElementById('klineSearchInput');
    const code = input.value.trim();
    if (!code) {
        StockApp.toast('请输入股票代码', 'warning');
        return;
    }
    const stock = watchlistData.find(s => s.code === code);
    loadKline(code, stock ? stock.name : code);
    input.value = '';
}

function updateKlineTitle() {
    const periodNames = { 'daily': '日K', 'weekly': '周K', 'monthly': '月K' };
    const titleEl = document.getElementById('klineTitle');
    if (titleEl) {
        titleEl.textContent = (currentStockName || currentStockCode) + ' - ' + (periodNames[currentPeriod] || '日K');
    }
}

function fmtDate(s) {
    if (s && s.length === 8) return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8);
    return s;
}

function fmtTime(t) {
    if (typeof t === 'string') return t;
    if (typeof t === 'object' && t.year != null)
        return t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0');
    return String(t);
}

// ========== 技术指标计算 (TA-Lib 兼容) ==========
// 以下算法与 akquant.talib (TA-Lib) 输出对齐,确保回测信号与前端展示口径一致

/**
 * EMA 指数移动平均
 * TA-Lib: 首值用前 period 个值的 SMA 作为种子,后续用 EMA 递推
 * @param {number[]} data - 输入数据
 * @param {number} period - 周期
 * @returns {number[]} EMA 值数组(前面 period-1 个为 NaN)
 */
function calcEMA(data, period) {
    const n = data.length;
    const result = new Array(n).fill(NaN);
    if (n < period || period < 1) return result;

    // 种子: 前 period 个值的 SMA
    let sum = 0;
    for (let i = 0; i < period; i++) sum += data[i];
    let ema = sum / period;
    result[period - 1] = ema;

    // EMA 递推: EMA_today = close * k + EMA_yesterday * (1 - k)
    const k = 2 / (period + 1);
    for (let i = period; i < n; i++) {
        ema = data[i] * k + ema * (1 - k);
        result[i] = ema;
    }
    return result;
}

/**
 * MACD 指标 (12, 26, 9)
 * DIF = EMA(close, 12) - EMA(close, 26)
 * DEA = EMA(DIF, 9)
 * HIST = DIF - DEA (TA-Lib 标准公式, 非 2*(DIF-DEA))
 * @param {number[]} closeData - 收盘价数组
 * @returns {{dif: number[], dea: number[], hist: number[]}}
 */
function calcMACD(closeData) {
    const fastperiod = 12, slowperiod = 26, signalperiod = 9;
    const n = closeData.length;
    const emaFast = calcEMA(closeData, fastperiod);
    const emaSlow = calcEMA(closeData, slowperiod);

    // DIF: 从 slowperiod - 1 开始有效
    const dif = new Array(n).fill(NaN);
    for (let i = slowperiod - 1; i < n; i++) {
        if (!isNaN(emaFast[i]) && !isNaN(emaSlow[i])) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
    }

    // DEA = EMA(DIF, 9), 在 DIF 有效段上计算
    const firstValidIdx = slowperiod - 1;
    const validDif = dif.slice(firstValidIdx);
    const deaValid = calcEMA(validDif, signalperiod);

    const dea = new Array(n).fill(NaN);
    for (let i = 0; i < deaValid.length; i++) {
        dea[firstValidIdx + i] = deaValid[i];
    }

    // HIST = DIF - DEA
    const hist = new Array(n).fill(NaN);
    for (let i = 0; i < n; i++) {
        if (!isNaN(dif[i]) && !isNaN(dea[i])) {
            hist[i] = dif[i] - dea[i];
        }
    }
    return { dif, dea, hist };
}

/**
 * KDJ 指标 (9, 3, 3) - 对应 TA-Lib STOCH + J 自算
 * RSV = (close - lowest_low(9)) / (highest_high(9) - lowest_low(9)) * 100
 * K = SMA(RSV, 3)  (slow K, TA-Lib STOCH 用 SMA 平滑, 非 EMA)
 * D = SMA(K, 3)    (slow D)
 * J = 3*K - 2*D
 * @param {number[]} highData
 * @param {number[]} lowData
 * @param {number[]} closeData
 * @returns {{k: number[], d: number[], j: number[]}}
 */
function calcKDJ(highData, lowData, closeData) {
    const fastk_period = 9, slowk_period = 3, slowd_period = 3;
    const n = closeData.length;
    const k = new Array(n).fill(NaN);
    const d = new Array(n).fill(NaN);
    const j = new Array(n).fill(NaN);

    // RSV
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
            rsv[i] = 0; // TA-Lib: 区间为 0 时返回 0
        } else {
            rsv[i] = (closeData[i] - lowest) / (highest - lowest) * 100;
        }
    }

    // K = SMA(RSV, slowk_period)
    for (let i = fastk_period - 1 + slowk_period - 1; i < n; i++) {
        let sum = 0;
        for (let p = 0; p < slowk_period; p++) sum += rsv[i - p];
        k[i] = sum / slowk_period;
    }

    // D = SMA(K, slowd_period)
    for (let i = fastk_period - 1 + slowk_period - 1 + slowd_period - 1; i < n; i++) {
        let sum = 0;
        for (let p = 0; p < slowd_period; p++) sum += k[i - p];
        d[i] = sum / slowd_period;
    }

    // J = 3*K - 2*D
    for (let i = 0; i < n; i++) {
        if (!isNaN(k[i]) && !isNaN(d[i])) {
            j[i] = 3 * k[i] - 2 * d[i];
        }
    }
    return { k, d, j };
}

/**
 * RSI 指标 (Wilder 平滑)
 * TA-Lib: 首值用前 period 个差值的简单平均作为种子,后续用 Wilder 平滑
 * RSI = 100 - 100/(1+RS), RS = avg_gain / avg_loss
 * @param {number[]} closeData
 * @param {number[]} [periods=[6,12,24]] - 周期数组
 * @returns {{rsi6: number[], rsi12: number[], rsi24: number[]}}
 */
function calcRSI(closeData, periods) {
    periods = periods || [6, 12, 24];
    const n = closeData.length;
    const result = {};

    for (const period of periods) {
        const key = 'rsi' + period;
        const rsi = new Array(n).fill(NaN);
        if (n < period + 1) { result[key] = rsi; continue; }

        // 计算涨跌
        const gains = new Array(n).fill(0);
        const losses = new Array(n).fill(0);
        for (let i = 1; i < n; i++) {
            const diff = closeData[i] - closeData[i - 1];
            gains[i] = diff > 0 ? diff : 0;
            losses[i] = diff < 0 ? -diff : 0;
        }

        // 种子: 前 period 个涨跌的简单平均
        let avgGain = 0, avgLoss = 0;
        for (let i = 1; i <= period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        avgGain /= period;
        avgLoss /= period;

        // 首个 RSI 在 index = period
        if (avgLoss === 0) {
            rsi[period] = 100;
        } else {
            const rs = avgGain / avgLoss;
            rsi[period] = 100 - 100 / (1 + rs);
        }

        // Wilder 平滑递推
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
 * BOLL 布林带 (20, 2, 2)
 * middle = SMA(close, 20)
 * std = 总体标准差 (除以 N, 非 N-1) -- 与 TA-Lib STDDEV 一致
 * upper = middle + 2 * std
 * lower = middle - 2 * std
 * @param {number[]} closeData
 * @returns {{upper: number[], middle: number[], lower: number[]}}
 */
function calcBOLL(closeData) {
    const timeperiod = 20, nbdevup = 2.0, nbdevdn = 2.0;
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
        const std = Math.sqrt(variance / timeperiod); // 总体标准差

        middle[i] = mean;
        upper[i] = mean + nbdevup * std;
        lower[i] = mean - nbdevdn * std;
    }
    return { upper, middle, lower };
}

// ========== 指标数据转换辅助 ==========
/**
 * 数值数组 -> {time, value} 对, 跳过 NaN/Inf
 */
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

/**
 * 数值数组 -> {time, value, color} 对 (直方图用, 根据正负着色)
 */
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

function renderKline(data, stockCode) {
    const container = document.getElementById('klineChart');
    const theme = ChartsTheme.getKlineTheme();
    klineDataCache = data; // 缓存原始数据,供指标切换复用

    const candleData = data.map(d => ({
        time: fmtDate(d.date),
        open: d.open,
        high: d.high,
        low: d.low,
        close: d.close,
    }));

    const volumeData = data.map(d => ({
        time: fmtDate(d.date),
        value: d.volume,
        color: d.close >= d.open ? theme.volumeColor.up : theme.volumeColor.down,
    }));

    function calcMA(dayCount) {
        const result = [];
        for (let i = dayCount - 1; i < data.length; i++) {
            let sum = 0;
            for (let j = 0; j < dayCount; j++) sum += data[i - j].close;
            result.push({ time: fmtDate(data[i].date), value: parseFloat((sum / dayCount).toFixed(2)) });
        }
        return result;
    }

    if (!klineChart) {
        container.style.position = 'relative';

        klineChart = LightweightCharts.createChart(container, {
            layout: theme.layout,
            grid: theme.grid,
            crosshair: theme.crosshair,
            rightPriceScale: theme.rightPriceScale,
            timeScale: {
                ...theme.timeScale,
                localization: {
                    dateFormat: date => {
                        if (typeof date === 'string') return date;
                        if (typeof date === 'object' && date.year != null)
                            return date.year + '-' + String(date.month).padStart(2, '0') + '-' + String(date.day).padStart(2, '0');
                        return String(date);
                    },
                },
            },
        });
        ChartsTheme.register(klineChart, 'lightweight');

        klineLegendEl = document.createElement('div');
        klineLegendEl.style.cssText = 'position:absolute;top:8px;left:12px;z-index:1;font-size:12px;line-height:1.6;pointer-events:none;';
        container.appendChild(klineLegendEl);

        candleSeries = klineChart.addCandlestickSeries(theme.candlestick);

        volumeSeries = klineChart.addHistogramSeries({
            priceFormat: { type: 'volume' },
            priceScaleId: 'volume',
        });
        klineChart.priceScale('volume').applyOptions({
            scaleMargins: { top: 0.8, bottom: 0 },
        });

        ma5Series = klineChart.addLineSeries({
            color: theme.maColors.ma5, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        ma10Series = klineChart.addLineSeries({
            color: theme.maColors.ma10, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        ma20Series = klineChart.addLineSeries({
            color: theme.maColors.ma20, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });

        klineChart.subscribeCrosshairMove(function(param) {
            if (!param || !param.time || !param.seriesData) {
                klineLegendEl.innerHTML = '';
                return;
            }
            const candle = param.seriesData.get(candleSeries);
            const vol = param.seriesData.get(volumeSeries);
            if (!candle) { klineLegendEl.innerHTML = ''; return; }

            // 每次重新读取主题,确保主题切换后颜色同步
            const t = ChartsTheme.getKlineTheme();
            const clr = candle.close >= candle.open ? t.candlestick.upColor : t.candlestick.downColor;
            const labelClr = t.legendLabelColor;

            // 主图指标行: MA 或 BOLL
            let indicatorLine = '';
            if (currentMainIndicator === 'boll' && bollUpperSeries) {
                const up = param.seriesData.get(bollUpperSeries);
                const mid = param.seriesData.get(bollMidSeries);
                const low = param.seriesData.get(bollLowerSeries);
                const ic = t.indicatorColors.boll;
                indicatorLine = `<br>` +
                    `<span style="color:${ic.upper}">UP: ${up ? up.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.mid}">MID: ${mid ? mid.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.lower}">LOW: ${low ? low.value.toFixed(2) : '-'}</span>`;
            } else {
                const m5 = param.seriesData.get(ma5Series);
                const m10 = param.seriesData.get(ma10Series);
                const m20 = param.seriesData.get(ma20Series);
                indicatorLine = `<br>` +
                    `<span style="color:${t.maColors.ma5}">MA5: ${m5 ? m5.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${t.maColors.ma10}">MA10: ${m10 ? m10.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${t.maColors.ma20}">MA20: ${m20 ? m20.value.toFixed(2) : '-'}</span>`;
            }

            klineLegendEl.innerHTML =
                `<span style="color:${labelClr}">${fmtTime(param.time)}</span> ` +
                `开 <span style="color:${clr}">${candle.open.toFixed(2)}</span> ` +
                `高 <span style="color:${clr}">${candle.high.toFixed(2)}</span> ` +
                `低 <span style="color:${clr}">${candle.low.toFixed(2)}</span> ` +
                `收 <span style="color:${clr}">${candle.close.toFixed(2)}</span>` +
                (vol ? ` 量 <span style="color:${labelClr}">${(vol.value / 10000).toFixed(1)}万</span>` : '') +
                indicatorLine;
        });

        new ResizeObserver(() => {
            klineChart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        }).observe(container);
    }

    candleSeries.setData(candleData);
    volumeSeries.setData(volumeData);
    ma5Series.setData(calcMA(5));
    ma10Series.setData(calcMA(10));
    ma20Series.setData(calcMA(20));

    const visibleBars = { daily: 120, weekly: 100, monthly: 80 };
    const showCount = visibleBars[currentPeriod] || 120;
    const startIdx = Math.max(0, candleData.length - showCount);
    if (candleData.length > 0) {
        klineChart.timeScale().setVisibleRange({
            from: candleData[startIdx].time,
            to: candleData[candleData.length - 1].time,
        });
    }

    // 应用主图/副图指标(数据更新后重建指标系列)
    applyMainIndicator();
    applySubIndicator();
}

// ========== 技术指标切换 ==========
/**
 * 切换主图指标 (MA / BOLL)
 */
function switchMainIndicator(type, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    if (currentMainIndicator === type) return;
    currentMainIndicator = type;
    applyMainIndicator();
}

/**
 * 切换副图指标 (MACD / KDJ / RSI / None)
 */
function switchSubIndicator(type, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    if (currentSubIndicator === type) return;
    currentSubIndicator = type;
    applySubIndicator();
}

/**
 * 应用主图指标: 切换 MA / BOLL 覆盖层
 */
function applyMainIndicator() {
    if (!klineChart || !klineDataCache) return;
    const theme = ChartsTheme.getKlineTheme();
    const data = klineDataCache;

    // 移除已有 BOLL 系列
    if (bollUpperSeries) { klineChart.removeSeries(bollUpperSeries); bollUpperSeries = null; }
    if (bollMidSeries) { klineChart.removeSeries(bollMidSeries); bollMidSeries = null; }
    if (bollLowerSeries) { klineChart.removeSeries(bollLowerSeries); bollLowerSeries = null; }

    // MA 可见性切换
    const maVisible = currentMainIndicator === 'ma';
    if (ma5Series) ma5Series.applyOptions({ visible: maVisible });
    if (ma10Series) ma10Series.applyOptions({ visible: maVisible });
    if (ma20Series) ma20Series.applyOptions({ visible: maVisible });

    // BOLL: 新增三条线覆盖在主图
    if (currentMainIndicator === 'boll') {
        const closes = data.map(d => d.close);
        const dates = data.map(d => d.date);
        const boll = calcBOLL(closes);
        const ic = theme.indicatorColors.boll;
        bollUpperSeries = klineChart.addLineSeries({
            color: ic.upper, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        bollMidSeries = klineChart.addLineSeries({
            color: ic.mid, lineWidth: 1, lineStyle: 2,
            priceLineVisible: false, lastValueVisible: false,
        });
        bollLowerSeries = klineChart.addLineSeries({
            color: ic.lower, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        bollUpperSeries.setData(toTimeSeries(boll.upper, dates));
        bollMidSeries.setData(toTimeSeries(boll.middle, dates));
        bollLowerSeries.setData(toTimeSeries(boll.lower, dates));
    }
}

/**
 * 确保副图 chart 实例存在(懒创建)
 */
function ensureSubChart() {
    if (subIndicatorChart) return subIndicatorChart;
    const container = document.getElementById('subIndicatorChart');
    if (!container) return null;

    const theme = ChartsTheme.getKlineTheme();
    container.style.position = 'relative';

    subIndicatorChart = LightweightCharts.createChart(container, {
        layout: theme.layout,
        grid: theme.grid,
        crosshair: theme.crosshair,
        rightPriceScale: theme.rightPriceScale,
        timeScale: { ...theme.timeScale, visible: true },
    });
    ChartsTheme.register(subIndicatorChart, 'lightweight');

    subIndicatorLegendEl = document.createElement('div');
    subIndicatorLegendEl.style.cssText = 'position:absolute;top:4px;left:8px;z-index:1;font-size:11px;line-height:1.5;pointer-events:none;';
    container.appendChild(subIndicatorLegendEl);

    // 副图十字叉移动回调
    subIndicatorChart.subscribeCrosshairMove(function(param) {
        if (!subIndicatorLegendEl) return;
        if (!param || !param.time || !param.seriesData) {
            subIndicatorLegendEl.innerHTML = '';
            return;
        }
        const t = ChartsTheme.getKlineTheme();
        const ic = t.indicatorColors;
        const labelClr = t.legendLabelColor;
        let html = `<span style="color:${labelClr}">${fmtTime(param.time)}</span> `;

        if (currentSubIndicator === 'macd' && subIndicatorSeries.dif) {
            const dif = param.seriesData.get(subIndicatorSeries.dif);
            const dea = param.seriesData.get(subIndicatorSeries.dea);
            const hist = param.seriesData.get(subIndicatorSeries.hist);
            html += `<span style="color:${ic.macd.dif}">DIF: ${dif ? dif.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.macd.dea}">DEA: ${dea ? dea.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.macd.histUp}">MACD: ${hist ? hist.value.toFixed(2) : '-'}</span>`;
        } else if (currentSubIndicator === 'kdj' && subIndicatorSeries.k) {
            const kv = param.seriesData.get(subIndicatorSeries.k);
            const dv = param.seriesData.get(subIndicatorSeries.d);
            const jv = param.seriesData.get(subIndicatorSeries.j);
            html += `<span style="color:${ic.kdj.k}">K: ${kv ? kv.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.kdj.d}">D: ${dv ? dv.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.kdj.j}">J: ${jv ? jv.value.toFixed(2) : '-'}</span>`;
        } else if (currentSubIndicator === 'rsi' && subIndicatorSeries.rsi6) {
            const r6 = param.seriesData.get(subIndicatorSeries.rsi6);
            const r12 = param.seriesData.get(subIndicatorSeries.rsi12);
            const r24 = param.seriesData.get(subIndicatorSeries.rsi24);
            html += `<span style="color:${ic.rsi.rsi6}">RSI6: ${r6 ? r6.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.rsi.rsi12}">RSI12: ${r12 ? r12.value.toFixed(2) : '-'}</span> ` +
                    `<span style="color:${ic.rsi.rsi24}">RSI24: ${r24 ? r24.value.toFixed(2) : '-'}</span>`;
        }
        subIndicatorLegendEl.innerHTML = html;
    });

    // 主/副图时间轴联动
    const syncRange = function(from, to) {
        if (syncingTimeScale || !from || !to) return;
        syncingTimeScale = true;
        try {
            const range = from.timeScale().getVisibleRange();
            if (range) to.timeScale().setVisibleRange(range);
        } catch (e) { /* 忽略同步异常 */ }
        syncingTimeScale = false;
    };
    if (klineChart) {
        klineChart.timeScale().subscribeVisibleTimeRangeChange(function() {
            syncRange(klineChart, subIndicatorChart);
        });
        subIndicatorChart.timeScale().subscribeVisibleTimeRangeChange(function() {
            syncRange(subIndicatorChart, klineChart);
        });
    }

    new ResizeObserver(function() {
        if (subIndicatorChart && container.clientWidth > 0) {
            subIndicatorChart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        }
    }).observe(container);

    return subIndicatorChart;
}

/**
 * 移除所有副图指标系列
 */
function clearSubIndicatorSeries() {
    if (!subIndicatorChart) return;
    for (const key in subIndicatorSeries) {
        if (subIndicatorSeries[key]) {
            try { subIndicatorChart.removeSeries(subIndicatorSeries[key]); } catch (e) {}
        }
    }
    subIndicatorSeries = {};
}

/**
 * 应用副图指标: MACD / KDJ / RSI / 无
 */
function applySubIndicator() {
    const container = document.getElementById('subIndicatorChart');
    if (!container) return;

    // 'none' 或无数据: 隐藏副图
    if (currentSubIndicator === 'none' || !klineDataCache || !klineDataCache.length) {
        clearSubIndicatorSeries();
        container.style.display = 'none';
        if (subIndicatorLegendEl) subIndicatorLegendEl.innerHTML = '';
        return;
    }

    const data = klineDataCache;
    const closes = data.map(d => d.close);
    const highs = data.map(d => d.high);
    const lows = data.map(d => d.low);
    const dates = data.map(d => d.date);
    const theme = ChartsTheme.getKlineTheme();
    const ic = theme.indicatorColors;

    // 数据量检查(各指标首个有效值的索引 + 1,确保 DIF/DEA/HIST 或 K/D/J 至少有 1 个有效点)
    const minBars = { macd: 34, kdj: 13, rsi: 25 };
    const required = minBars[currentSubIndicator] || 0;
    if (data.length < required) {
        clearSubIndicatorSeries();
        container.style.display = 'block';
        const chart = ensureSubChart();
        if (chart && subIndicatorLegendEl) {
            subIndicatorLegendEl.innerHTML =
                `<span style="color:${theme.legendLabelColor}">数据不足 (需要至少 ${required} 根K线,当前 ${data.length} 根)</span>`;
        }
        return;
    }

    container.style.display = 'block';
    const chart = ensureSubChart();
    if (!chart) return;

    clearSubIndicatorSeries();

    if (currentSubIndicator === 'macd') {
        const macd = calcMACD(closes);
        subIndicatorSeries.dif = chart.addLineSeries({
            color: ic.macd.dif, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.dea = chart.addLineSeries({
            color: ic.macd.dea, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.hist = chart.addHistogramSeries({
            priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.dif.setData(toTimeSeries(macd.dif, dates));
        subIndicatorSeries.dea.setData(toTimeSeries(macd.dea, dates));
        subIndicatorSeries.hist.setData(toHistSeries(macd.hist, dates, ic.macd.histUp, ic.macd.histDown));
    } else if (currentSubIndicator === 'kdj') {
        const kdj = calcKDJ(highs, lows, closes);
        subIndicatorSeries.k = chart.addLineSeries({
            color: ic.kdj.k, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.d = chart.addLineSeries({
            color: ic.kdj.d, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.j = chart.addLineSeries({
            color: ic.kdj.j, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.k.setData(toTimeSeries(kdj.k, dates));
        subIndicatorSeries.d.setData(toTimeSeries(kdj.d, dates));
        subIndicatorSeries.j.setData(toTimeSeries(kdj.j, dates));
    } else if (currentSubIndicator === 'rsi') {
        const rsi = calcRSI(closes);
        subIndicatorSeries.rsi6 = chart.addLineSeries({
            color: ic.rsi.rsi6, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.rsi12 = chart.addLineSeries({
            color: ic.rsi.rsi12, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.rsi24 = chart.addLineSeries({
            color: ic.rsi.rsi24, lineWidth: 1,
            priceLineVisible: false, lastValueVisible: false,
        });
        subIndicatorSeries.rsi6.setData(toTimeSeries(rsi.rsi6, dates));
        subIndicatorSeries.rsi12.setData(toTimeSeries(rsi.rsi12, dates));
        subIndicatorSeries.rsi24.setData(toTimeSeries(rsi.rsi24, dates));
    }

    // 同步主图可见范围到副图
    if (klineChart) {
        try {
            const range = klineChart.timeScale().getVisibleRange();
            if (range) chart.timeScale().setVisibleRange(range);
        } catch (e) {}
    }
}

// 主题切换时更新指标颜色
window.addEventListener('theme:changed', function() {
    setTimeout(function() {
        if (!klineChart) return;
        const theme = ChartsTheme.getKlineTheme();
        // 主图 MA 系列
        if (ma5Series) ma5Series.applyOptions({ color: theme.maColors.ma5 });
        if (ma10Series) ma10Series.applyOptions({ color: theme.maColors.ma10 });
        if (ma20Series) ma20Series.applyOptions({ color: theme.maColors.ma20 });
        // 主图 BOLL 系列
        if (bollUpperSeries) bollUpperSeries.applyOptions({ color: theme.indicatorColors.boll.upper });
        if (bollMidSeries) bollMidSeries.applyOptions({ color: theme.indicatorColors.boll.mid });
        if (bollLowerSeries) bollLowerSeries.applyOptions({ color: theme.indicatorColors.boll.lower });
        // 副图指标重建(含直方图按正负重新着色)
        applySubIndicator();
    }, 80);
});

// ========== Watchlist (AJAX) ==========
function refreshWatchlist() {
    StockApp.get('/watchlist', null, function(resp) {
        if (resp.code !== 200) return;
        watchlistData = resp.data || [];
        renderWatchlistTable(watchlistData);
        renderKlineButtons(watchlistData);
        renderTrendChart(watchlistData);
        renderPieChart(watchlistData);
    });
}

function renderWatchlistTable(list) {
    const tbody = document.getElementById('watchlistBody');
    const e = StockApp.escapeHtml;
    if (!list.length) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">暂无自选股，点击上方添加</td></tr>';
        return;
    }
    tbody.innerHTML = list.map(s => {
        const upDown = s.changePercent >= 0 ? 'rise' : 'fall';
        const sign = s.changePercent >= 0 ? '+' : '';
        const selected = s.code === currentStockCode ? 'table-active' : '';
        return `
        <tr class="${selected}" style="cursor:pointer" onclick="loadKline('${e(s.code)}', '${e(s.name)}')">
            <td>
                <div class="fw-medium">${e(s.name)}</div>
                <small class="text-muted">${e(s.code)}</small>
            </td>
            <td class="text-end fw-medium">${s.currentPrice != null ? e(String(s.currentPrice)) : '-'}</td>
            <td class="text-end">
                <span class="${upDown}">
                    ${s.changePercent != null ? sign + Number(s.changePercent).toFixed(2) + '%' : '-'}
                </span>
            </td>
            <td class="text-center">
                <button class="btn btn-sm btn-link text-danger" onclick="event.stopPropagation(); removeFromWatchlist('${e(s.code)}', this)" title="移除">
                    <i class="bi bi-trash"></i>
                </button>
            </td>
        </tr>`;
    }).join('');
}

function renderKlineButtons(list) {
    const container = document.getElementById('klineStockBtns');
    if (!list.length) {
        container.innerHTML = '';
        return;
    }
    const e = StockApp.escapeHtml;
    container.innerHTML = list.slice(0, 6).map(s => {
        const active = s.code === currentStockCode ? 'active' : '';
        return `<button class="btn btn-outline-secondary ${active}" onclick="loadKline('${e(s.code)}', '${e(s.name)}', this)">${e(s.name)}</button>`;
    }).join('');
}

function highlightWatchlistRow(code) {
    document.querySelectorAll('#watchlistBody tr').forEach(tr => {
        tr.classList.toggle('table-active', tr.querySelector(`[onclick*="${code}"]`) !== null
            || tr.getAttribute('onclick')?.includes(code));
    });
    document.querySelectorAll('#klineStockBtns .btn').forEach(btn => {
        btn.classList.toggle('active', btn.getAttribute('onclick')?.includes(code));
    });
}

async function removeFromWatchlist(stockCode, btn) {
    if (!await StockApp.confirm({
        title: '移除自选股',
        message: '确认移除该自选股？',
        confirmText: '移除',
        confirmClass: 'btn-danger',
        icon: 'bi-trash'
    })) return;
    StockApp.post('/watchlist/' + stockCode + '/delete', null, function(resp) {
        StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'warning');
        if (resp.code === 200) refreshWatchlist();
    });
}

function submitAddStock() {
    const code = document.getElementById('stockCodeInput').value.trim();
    if (!code) {
        StockApp.toast('请输入股票代码', 'warning');
        return;
    }
    StockApp.post('/watchlist/' + code, null, function(resp) {
        StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'warning');
        if (resp.code === 200) {
            const modal = bootstrap.Modal.getInstance(document.getElementById('addStockModal'));
            if (modal) modal.hide();
            document.getElementById('stockCodeInput').value = '';
            refreshWatchlist();
        }
    });
}

// ========== Market Rankings (Tab 切换) ==========
// 缓存四类排行数据,Tab 切换时无需重新请求
let rankingDataCache = { gainers: [], losers: [], amount: [], turnover: [] };
let currentRankingTab = 'gainers';

function refreshRanking() {
    StockApp.get('/market/ranking', null, function(resp) {
        if (resp.code !== 200 || !resp.data) {
            rankingDataCache = { gainers: [], losers: [], amount: [], turnover: [] };
            renderRankingTab(currentRankingTab);
            return;
        }
        rankingDataCache = {
            gainers: resp.data.topGainers || [],
            losers: resp.data.topLosers || [],
            amount: resp.data.topAmount || [],
            turnover: resp.data.topTurnover || [],
        };
        renderRankingTab(currentRankingTab);
    });
}

/**
 * 切换排行 Tab
 */
function switchRankingTab(tab) {
    if (currentRankingTab === tab) return;
    currentRankingTab = tab;
    document.querySelectorAll('#rankingTabs .nav-link').forEach(function(a) {
        a.classList.toggle('active', a.dataset.tab === tab);
    });
    renderRankingTab(tab);
}

/**
 * 渲染当前激活 Tab 的排行表(表头 + 表体动态切换)
 */
function renderRankingTab(tab) {
    const head = document.getElementById('rankingHead');
    const body = document.getElementById('rankingBody');
    if (!head || !body) return;
    const list = rankingDataCache[tab] || [];
    const e = StockApp.escapeHtml;

    if (tab === 'turnover') {
        // 换手率榜: 排名 | 代码 | 名称 | 最新价 | 涨跌幅 | 换手率 | 成交额
        head.innerHTML = '<tr>' +
            '<th class="text-center" style="width: 50px;">排名</th>' +
            '<th>代码</th>' +
            '<th>名称</th>' +
            '<th class="text-end">最新价</th>' +
            '<th class="text-end">涨跌幅</th>' +
            '<th class="text-end">换手率</th>' +
            '<th class="text-end">成交额</th>' +
            '</tr>';
        if (!list.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-3">暂无数据</td></tr>';
            return;
        }
        body.innerHTML = list.map(function(s, i) {
            const upDown = (s.pctChg != null && s.pctChg >= 0) ? 'stock-up' : 'stock-down';
            const sign = (s.pctChg != null && s.pctChg >= 0) ? '+' : '';
            const rankBadge = i < 3 ? '<span class="badge bg-danger bg-opacity-75">' + (i + 1) + '</span>' : (i + 1);
            return '<tr>' +
                '<td class="text-center">' + rankBadge + '</td>' +
                '<td><small class="text-muted">' + e(s.code) + '</small></td>' +
                '<td><span class="fw-medium">' + e(s.name) + '</span></td>' +
                '<td class="text-end">' + (s.close != null ? e(String(s.close)) : '-') + '</td>' +
                '<td class="text-end ' + upDown + ' fw-medium">' + (s.pctChg != null ? sign + Number(s.pctChg).toFixed(2) + '%' : '-') + '</td>' +
                '<td class="text-end">' + (s.turnoverRate != null ? Number(s.turnoverRate).toFixed(2) + '%' : '-') + '</td>' +
                '<td class="text-end">' + formatAmount(s.amount) + '</td>' +
                '</tr>';
        }).join('');
    } else {
        // 涨幅/跌幅/成交额榜: 排名 | 名称(含代码) | 第三列 | 涨跌幅
        const thirdColName = tab === 'amount' ? '成交额' : '最新价';
        head.innerHTML = '<tr>' +
            '<th class="text-center" style="width: 50px;">排名</th>' +
            '<th>名称</th>' +
            '<th class="text-end">' + thirdColName + '</th>' +
            '<th class="text-end">涨跌幅</th>' +
            '</tr>';
        if (!list.length) {
            body.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-3">暂无数据</td></tr>';
            return;
        }
        body.innerHTML = list.map(function(s, i) {
            const upDown = (s.pctChg != null && s.pctChg >= 0) ? 'stock-up' : 'stock-down';
            const sign = (s.pctChg != null && s.pctChg >= 0) ? '+' : '';
            const rankBadge = i < 3 ? '<span class="badge bg-danger bg-opacity-75">' + (i + 1) + '</span>' : (i + 1);
            let thirdCol;
            if (tab === 'amount') {
                thirdCol = formatAmount(s.amount);
            } else {
                thirdCol = s.close != null ? e(String(s.close)) : '-';
            }
            return '<tr>' +
                '<td class="text-center">' + rankBadge + '</td>' +
                '<td><span class="fw-medium">' + e(s.name) + '</span>' +
                '<small class="text-muted ms-1">' + e(s.code) + '</small></td>' +
                '<td class="text-end">' + thirdCol + '</td>' +
                '<td class="text-end ' + upDown + ' fw-medium">' + (s.pctChg != null ? sign + Number(s.pctChg).toFixed(2) + '%' : '-') + '</td>' +
                '</tr>';
        }).join('');
    }
}

function formatAmount(amount) {
    if (amount == null) return '-';
    const wan = amount / 10;
    if (wan >= 10000) return (wan / 10000).toFixed(2) + '亿';
    return wan.toFixed(0) + '万';
}

// ========== Market Temperature (市场温度) ==========
function fetchMarketTemperature() {
    fetch((StockApp.contextPath || '') + '/market/temperature')
        .then(function(res) { return res.json(); })
        .then(function(res) {
            if (res.code === 200 && res.data) {
                renderMarketTemperature(res.data);
            } else {
                showTemperatureEmpty();
            }
        })
        .catch(function() { showTemperatureEmpty(); });
}

function renderMarketTemperature(data) {
    const setVal = function(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val != null ? val : '--';
    };
    setVal('tempUpCount', data.upCount);
    setVal('tempDownCount', data.downCount);
    setVal('tempFlatCount', data.flatCount);
    setVal('tempLimitUp', data.limitUpCount);
    setVal('tempLimitDown', data.limitDownCount);
}

function showTemperatureEmpty() {
    ['tempUpCount', 'tempDownCount', 'tempFlatCount', 'tempLimitUp', 'tempLimitDown'].forEach(function(id) {
        const el = document.getElementById(id);
        if (el) el.textContent = '--';
    });
}

// ========== Sector Overview (板块概览) ==========
function fetchSectorOverview() {
    fetch((StockApp.contextPath || '') + '/api/industry/ranking')
        .then(function(res) {
            if (!res.ok) throw new Error('Sector module not available');
            return res.json();
        })
        .then(function(res) {
            if (res.code === 200 && res.data) {
                renderSectorOverview(res.data);
            } else {
                showSectorDeveloping();
            }
        })
        .catch(function() { showSectorDeveloping(); });
}

/**
 * 渲染板块概览: 涨幅前5(红) + 跌幅前5(绿)
 * data 为扁平数组(全部行业),按 pctChg 降序排序后取前5/末5
 */
function renderSectorOverview(data) {
    const container = document.getElementById('sectorOverviewContainer');
    if (!container) return;
    const e = StockApp.escapeHtml;

    if (!Array.isArray(data) || data.length === 0) {
        showSectorDeveloping();
        return;
    }

    var sorted = data.slice().sort(function(a, b) {
        var va = a.pctChg != null ? a.pctChg : 0;
        var vb = b.pctChg != null ? b.pctChg : 0;
        return vb - va;
    });
    var gainers = sorted.slice(0, 5);
    var losers = sorted.slice(-5);

    if (!gainers.length && !losers.length) {
        showSectorDeveloping();
        return;
    }

    let html = '';

    // 涨幅前5
    html += '<div class="mb-3">';
    html += '<div class="text-muted small fw-medium mb-2">涨幅前5</div>';
    if (gainers.length) {
        html += '<ul class="list-group list-group-flush">';
        gainers.forEach(function(s, i) {
            const name = s.name || s.industryName || s.industry || '-';
            const pct = s.pctChg != null ? s.pctChg : (s.pct_chg != null ? s.pct_chg : null);
            const sign = (pct != null && pct >= 0) ? '+' : '';
            html += '<li class="list-group-item d-flex justify-content-between align-items-center px-0 py-2">' +
                '<span><small class="text-muted me-1">' + (i + 1) + '</small> ' + e(name) + '</span>' +
                '<span class="stock-up fw-medium">' + (pct != null ? sign + Number(pct).toFixed(2) + '%' : '-') + '</span>' +
                '</li>';
        });
        html += '</ul>';
    } else {
        html += '<div class="text-muted small py-2">暂无数据</div>';
    }
    html += '</div>';

    // 跌幅前5
    html += '<div>';
    html += '<div class="text-muted small fw-medium mb-2">跌幅前5</div>';
    if (losers.length) {
        html += '<ul class="list-group list-group-flush">';
        losers.forEach(function(s, i) {
            const name = s.name || s.industryName || s.industry || '-';
            const pct = s.pctChg != null ? s.pctChg : (s.pct_chg != null ? s.pct_chg : null);
            const sign = (pct != null && pct >= 0) ? '+' : '';
            html += '<li class="list-group-item d-flex justify-content-between align-items-center px-0 py-2">' +
                '<span><small class="text-muted me-1">' + (i + 1) + '</small> ' + e(name) + '</span>' +
                '<span class="stock-down fw-medium">' + (pct != null ? sign + Number(pct).toFixed(2) + '%' : '-') + '</span>' +
                '</li>';
        });
        html += '</ul>';
    } else {
        html += '<div class="text-muted small py-2">暂无数据</div>';
    }
    html += '</div>';

    container.innerHTML = html;
}

function showSectorDeveloping() {
    const container = document.getElementById('sectorOverviewContainer');
    if (container) {
        container.innerHTML = '<div class="text-center text-muted py-4">板块行情功能开发中</div>';
    }
}

// ========== Charts ==========
let trendChartInstance = null;
let pieChartInstance = null;

function renderTrendChart(list) {
    const container = document.getElementById('trendChart');
    const echartsTheme = ChartsTheme.getEChartsTheme();

    if (!trendChartInstance) {
        trendChartInstance = echarts.init(container);
        ChartsTheme.register(trendChartInstance, 'echarts');
        window.addEventListener('resize', function() { trendChartInstance.resize(); });
    }

    if (!list || !list.length) {
        trendChartInstance.setOption({
            title: { text: '暂无自选股数据', left: 'center', top: 'center',
                textStyle: { color: echartsTheme.textStyle.color, fontSize: 14 } },
            xAxis: { show: false }, yAxis: { show: false }, series: []
        });
        return;
    }

    const names = list.map(s => s.name);
    const values = list.map(s => s.changePercent != null ? Number(s.changePercent) : 0);

    trendChartInstance.setOption({
        title: null,
        tooltip: {
            trigger: 'axis',
            formatter: '{b}: {c}%',
            ...echartsTheme.tooltip
        },
        xAxis: {
            type: 'category',
            data: names,
            axisLabel: { ...echartsTheme.axisLabel, rotate: 30 },
            axisLine: echartsTheme.axisLine,
            axisTick: echartsTheme.axisTick,
        },
        yAxis: {
            type: 'value',
            axisLabel: { ...echartsTheme.axisLabel, formatter: '{value}%' },
            axisLine: echartsTheme.axisLine,
            splitLine: echartsTheme.splitLine,
        },
        series: [{
            type: 'bar',
            data: values.map(v => ({
                value: v,
                itemStyle: {
                    color: ChartsTheme.getBarGradient(trendChartInstance, v >= 0),
                    borderRadius: [4, 4, 0, 0],
                }
            })),
            barWidth: '50%',
            label: {
                show: true,
                formatter: function(p) { return p.value.toFixed(2) + '%'; },
                position: 'top',
                fontSize: 10,
                color: echartsTheme.textStyle.color,
            }
        }]
    }, true);
}

function renderPieChart(list) {
    const container = document.getElementById('pieChart');
    const echartsTheme = ChartsTheme.getEChartsTheme();

    if (!pieChartInstance) {
        pieChartInstance = echarts.init(container);
        ChartsTheme.register(pieChartInstance, 'echarts');
        window.addEventListener('resize', function() { pieChartInstance.resize(); });
    }

    if (!list || !list.length) {
        pieChartInstance.setOption({
            title: { text: '暂无自选股数据', left: 'center', top: 'center',
                textStyle: { color: echartsTheme.textStyle.color, fontSize: 14 } },
            series: []
        });
        return;
    }

    const industryMap = {};
    list.forEach(s => {
        const ind = s.industry || '其他';
        if (!industryMap[ind]) industryMap[ind] = 0;
        industryMap[ind]++;
    });
    const pieData = Object.entries(industryMap).map(([name, value]) => ({ name, value }));

    const borderColor = '#0f1520';

    pieChartInstance.setOption({
        title: null,
        tooltip: {
            trigger: 'item',
            formatter: '{b}: {c}只 ({d}%)',
            ...echartsTheme.tooltip
        },
        legend: {
            orient: 'vertical',
            right: 10,
            top: 'center',
            textStyle: echartsTheme.legend.textStyle,
            inactiveColor: echartsTheme.legend.inactiveColor,
        },
        color: ChartsTheme.getChartColors(),
        series: [{
            type: 'pie',
            radius: ['35%', '65%'],
            center: ['40%', '50%'],
            avoidLabelOverlap: false,
            itemStyle: {
                borderRadius: 6,
                borderColor: borderColor,
                borderWidth: 2,
            },
            label: { show: false },
            emphasis: { label: { show: true, fontWeight: 'bold' } },
            data: pieData
        }]
    }, true);
}

// ========== Refresh and Export ==========
function refreshDashboard() {
    refreshWatchlist();
    refreshRanking();
    fetchMarketTemperature();
    fetchSectorOverview();
    StockApp.toast('数据已刷新', 'success');
}

function exportDashboard() {
    const data = {
        watchlist: watchlistData,
        exportTime: new Date().toISOString()
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'dashboard_export_' + new Date().toISOString().slice(0,10) + '.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    StockApp.toast('数据已导出', 'success');
}

// ========== Init ==========
document.addEventListener('DOMContentLoaded', function() {
    // Add click handlers for refresh and export
    document.getElementById('refreshBtn').addEventListener('click', refreshDashboard);
    document.getElementById('exportBtn').addEventListener('click', exportDashboard);

    // 排行榜 Tab 点击切换
    document.querySelectorAll('#rankingTabs .nav-link').forEach(function(a) {
        a.addEventListener('click', function() {
            switchRankingTab(a.dataset.tab);
        });
    });

    // K-line search suggest
    new SearchSuggest(document.getElementById('klineSearchInput'), {
        onSelect: function(item) {
            loadKline(item.code, item.name);
        }
    });

    // Add stock modal suggest
    new SearchSuggest(document.getElementById('stockCodeInput'), {
        onSelect: function(item) {
        }
    });

    refreshWatchlist();
    refreshRanking();
    fetchMarketTemperature();
    fetchSectorOverview();
});
