/**
 * KlineRenderer —— 实例化 K 线与指标渲染组件
 *
 * 设计目标（spec 024 Task 7.1）：
 *  - 从 dashboard.js 的页面级全局变量实现中解耦出可创建/更新/销毁的实例化组件
 *  - 仪表盘（dashboard）与个股诊断（stock-detail）共用同一实现，多实例状态隔离
 *  - 指标算法统一调用 window.Indicators（charts/indicators.js），不在本文件重复实现
 *  - 主题跟随 ChartsTheme（charts-theme.js），监听 theme:changed 自动重绘
 *
 * 生命周期：
 *   const r = new KlineRenderer(opts);
 *   r.setData(ohlcv);            // 设置/刷新 K 线
 *   r.setMainIndicator('ma');    // 'ma' | 'ema' | 'boll' | 'sar' | 'none'
 *   r.setSubIndicator('macd');   // 'macd' | 'kdj' | 'rsi' | 'wr' | 'cci' | 'dmi' | 'none'
 *   r.setVisibleBars(120);       // 设置默认可见 K 线根数
 *   r.fitMainRange();            // 把主图可见范围同步给副图
 *   r.dispose();                 // 销毁：解绑监听、清空 series、移除 DOM 图例
 *
 * 依赖：LightweightCharts（全局）、ChartsTheme（全局）、Indicators（全局）
 * 容器：opts.mainContainer / opts.subContainer 均为已存在的 DOM 元素
 */
(function () {
    'use strict';

    var I = window.Indicators;

    // 各副图指标至少需要的 K 线根数（首个有效值索引 + 1）
    var SUB_MIN_BARS = {
        macd: 34, kdj: 13, rsi: 25, wr: 15, cci: 15, dmi: 29
    };

    function KlineRenderer(opts) {
        opts = opts || {};
        this.mainContainer = opts.mainContainer;
        this.subContainer = opts.subContainer || null; // 可选；不传则无副图
        this.defaultVisible = opts.defaultVisible || 120;

        // 主图状态
        this.mainChart = null;
        this.candleSeries = null;
        this.volumeSeries = null;
        this.mainLegendEl = null;
        this.maSeries = { ma5: null, ma10: null, ma20: null, ma60: null };
        this.emaSeries = { ema12: null, ema26: null };
        this.bollSeries = { upper: null, mid: null, lower: null };
        this.sarSeries = null;

        // 副图状态
        this.subChart = null;
        this.subLegendEl = null;
        this.subSeries = {};
        this.syncingTimeScale = false;

        // 当前选择 & 数据缓存
        this.currentMain = 'ma';
        this.currentSub = 'macd';
        this.dataCache = null; // 原始 OHLCV 数组 [{date,open,high,low,close,volume}]

        // 监听句柄（dispose 时清理）
        this._resizeObserver = null;
        this._subResizeObserver = null;
        this._themeHandler = null;

        if (this.mainContainer) this._initMain();
        if (this.subContainer) this._initSub();
        this._bindTheme();
    }

    // ========== 主图初始化 ==========
    KlineRenderer.prototype._initMain = function () {
        var self = this;
        var container = this.mainContainer;
        var theme = ChartsTheme.getKlineTheme();

        container.style.position = 'relative';
        this.mainChart = LightweightCharts.createChart(container, {
            layout: theme.layout,
            grid: theme.grid,
            crosshair: theme.crosshair,
            rightPriceScale: theme.rightPriceScale,
            timeScale: {
                ...theme.timeScale,
                localization: {
                    dateFormat: function (date) { return I.fmtTime(date); },
                },
            },
        });
        ChartsTheme.register(this.mainChart, 'lightweight');

        this.mainLegendEl = document.createElement('div');
        this.mainLegendEl.style.cssText =
            'position:absolute;top:8px;left:12px;z-index:1;font-size:12px;line-height:1.6;pointer-events:none;';
        container.appendChild(this.mainLegendEl);

        this.candleSeries = this.mainChart.addCandlestickSeries(theme.candlestick);
        this.volumeSeries = this.mainChart.addHistogramSeries({
            priceFormat: { type: 'volume' },
            priceScaleId: 'volume',
        });
        this.mainChart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });

        // MA 系列（默认创建，按 currentMain 决定可见性）
        var maColors = theme.maColors;
        this.maSeries.ma5 = this._addLine(maColors.ma5);
        this.maSeries.ma10 = this._addLine(maColors.ma10);
        this.maSeries.ma20 = this._addLine(maColors.ma20);
        this.maSeries.ma60 = this._addLine(maColors.ma20);

        this.mainChart.subscribeCrosshairMove(function (param) { self._onMainCrosshair(param); });

        this._resizeObserver = new ResizeObserver(function () {
            if (!self.mainChart) return;
            self.mainChart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        });
        this._resizeObserver.observe(container);
    };

    KlineRenderer.prototype._addLine = function (color, lineStyle, lineWidth) {
        return this.mainChart.addLineSeries({
            color: color,
            lineWidth: lineWidth || 1,
            lineStyle: lineStyle,
            priceLineVisible: false,
            lastValueVisible: false,
        });
    };

    // ========== 副图初始化 ==========
    KlineRenderer.prototype._initSub = function () {
        var self = this;
        var container = this.subContainer;
        var theme = ChartsTheme.getKlineTheme();

        container.style.position = 'relative';
        this.subChart = LightweightCharts.createChart(container, {
            layout: theme.layout,
            grid: theme.grid,
            crosshair: theme.crosshair,
            rightPriceScale: theme.rightPriceScale,
            timeScale: { ...theme.timeScale, visible: true },
        });
        ChartsTheme.register(this.subChart, 'lightweight');

        this.subLegendEl = document.createElement('div');
        this.subLegendEl.style.cssText =
            'position:absolute;top:4px;left:8px;z-index:1;font-size:11px;line-height:1.5;pointer-events:none;';
        container.appendChild(this.subLegendEl);

        this.subChart.subscribeCrosshairMove(function (param) { self._onSubCrosshair(param); });

        // 主/副图可见范围双向联动（用 logical range 基于 bar 索引，跨周期切换更稳健）
        var syncRange = function (from, to) {
            if (self.syncingTimeScale || !from || !to) return;
            self.syncingTimeScale = true;
            try {
                var range = from.timeScale().getVisibleLogicalRange();
                if (range) to.timeScale().setVisibleLogicalRange(range);
            } catch (e) { /* 忽略同步异常 */ }
            self.syncingTimeScale = false;
        };
        if (this.mainChart) {
            this.mainChart.timeScale().subscribeVisibleLogicalRangeChange(function () {
                syncRange(self.mainChart, self.subChart);
            });
            this.subChart.timeScale().subscribeVisibleLogicalRangeChange(function () {
                syncRange(self.subChart, self.mainChart);
            });
        }

        this._subResizeObserver = new ResizeObserver(function () {
            if (self.subChart && container.clientWidth > 0) {
                self.subChart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
            }
        });
        this._subResizeObserver.observe(container);
    };

    // ========== 主题切换 ==========
    KlineRenderer.prototype._bindTheme = function () {
        var self = this;
        this._themeHandler = function () {
            setTimeout(function () { self._applyTheme(); }, 80);
        };
        window.addEventListener('theme:changed', this._themeHandler);
    };

    KlineRenderer.prototype._applyTheme = function () {
        if (!this.mainChart) return;
        var theme = ChartsTheme.getKlineTheme();
        if (this.maSeries.ma5) this.maSeries.ma5.applyOptions({ color: theme.maColors.ma5 });
        if (this.maSeries.ma10) this.maSeries.ma10.applyOptions({ color: theme.maColors.ma10 });
        if (this.maSeries.ma20) this.maSeries.ma20.applyOptions({ color: theme.maColors.ma20 });
        if (this.bollSeries.upper) this.bollSeries.upper.applyOptions({ color: theme.indicatorColors.boll.upper });
        if (this.bollSeries.mid) this.bollSeries.mid.applyOptions({ color: theme.indicatorColors.boll.mid });
        if (this.bollSeries.lower) this.bollSeries.lower.applyOptions({ color: theme.indicatorColors.boll.lower });
        this._applySubIndicator(); // 重建副图（含直方图按正负重新着色）
    };

    // ========== 数据设置 ==========
    KlineRenderer.prototype.setData = function (data) {
        if (!this.mainChart) return;
        this.dataCache = data || [];
        var theme = ChartsTheme.getKlineTheme();
        var fmt = I.fmtDate;

        this.candleSeries.setData(this.dataCache.map(function (d) {
            return { time: fmt(d.date), open: d.open, high: d.high, low: d.low, close: d.close };
        }));
        this.volumeSeries.setData(this.dataCache.map(function (d) {
            return {
                time: fmt(d.date), value: d.volume,
                color: d.close >= d.open ? theme.volumeColor.up : theme.volumeColor.down,
            };
        }));

        // 默认可见范围（用 logical range 基于 bar 索引，避免切换周期时日期密度差异导致缩放失真）
        var showCount = Math.min(this.defaultVisible, this.dataCache.length);
        var fromIdx = Math.max(0, this.dataCache.length - showCount);
        if (this.dataCache.length > 0) {
            this.mainChart.timeScale().setVisibleLogicalRange({
                from: fromIdx,
                to: this.dataCache.length - 1 + 0.5,
            });
        }

        this._applyMainIndicator();
        this._applySubIndicator();
    };

    // ========== 主图指标 ==========
    KlineRenderer.prototype.setMainIndicator = function (type) {
        this.currentMain = type;
        this._applyMainIndicator();
    };

    KlineRenderer.prototype._applyMainIndicator = function () {
        if (!this.mainChart || !this.dataCache) return;
        var theme = ChartsTheme.getKlineTheme();
        var data = this.dataCache;
        var closes = data.map(function (d) { return d.close; });
        var dates = data.map(function (d) { return d.date; });
        var fmt = I.fmtDate;
        var ic = theme.indicatorColors;

        // 移除 BOLL / SAR 系列（每次按需重建）
        this._removeSeries(this.bollSeries.upper); this.bollSeries.upper = null;
        this._removeSeries(this.bollSeries.mid); this.bollSeries.mid = null;
        this._removeSeries(this.bollSeries.lower); this.bollSeries.lower = null;
        this._removeSeries(this.sarSeries); this.sarSeries = null;

        var showMA = this.currentMain === 'ma';
        var showEMA = this.currentMain === 'ema';
        for (var k in this.maSeries) {
            if (this.maSeries[k]) this.maSeries[k].applyOptions({ visible: showMA });
        }
        for (var ke in this.emaSeries) {
            if (this.emaSeries[ke]) this.emaSeries[ke].applyOptions({ visible: showEMA });
        }

        if (this.currentMain === 'ema') {
            // 懒创建 EMA 系列
            if (!this.emaSeries.ema12) {
                this.emaSeries.ema12 = this._addLine(theme.maColors.ma10);
            }
            if (!this.emaSeries.ema26) {
                this.emaSeries.ema26 = this._addLine(theme.maColors.ma20);
            }
            this.emaSeries.ema12.setData(I.toTimeSeries(I.EMA(closes, 12), dates));
            this.emaSeries.ema26.setData(I.toTimeSeries(I.EMA(closes, 26), dates));
        } else if (this.currentMain === 'ma') {
            this.maSeries.ma5.setData(I.toTimeSeries(I.MA(closes, 5), dates));
            this.maSeries.ma10.setData(I.toTimeSeries(I.MA(closes, 10), dates));
            this.maSeries.ma20.setData(I.toTimeSeries(I.MA(closes, 20), dates));
            this.maSeries.ma60.setData(I.toTimeSeries(I.MA(closes, 60), dates));
        } else if (this.currentMain === 'boll') {
            var boll = I.BOLL(closes);
            this.bollSeries.upper = this._addLine(ic.boll.upper);
            this.bollSeries.mid = this._addLine(ic.boll.mid, 2);
            this.bollSeries.lower = this._addLine(ic.boll.lower);
            this.bollSeries.upper.setData(I.toTimeSeries(boll.upper, dates));
            this.bollSeries.mid.setData(I.toTimeSeries(boll.middle, dates));
            this.bollSeries.lower.setData(I.toTimeSeries(boll.lower, dates));
        } else if (this.currentMain === 'sar') {
            var highs = data.map(function (d) { return d.high; });
            var lows = data.map(function (d) { return d.low; });
            this.sarSeries = this.mainChart.addLineSeries({
                color: ic.boll.mid, lineWidth: 1, lineStyle: 1,
                pointMarkersVisible: true,
                priceLineVisible: false, lastValueVisible: false,
            });
            this.sarSeries.setData(I.toTimeSeries(I.SAR(highs, lows), dates));
        }
    };

    // ========== 副图指标 ==========
    KlineRenderer.prototype.setSubIndicator = function (type) {
        this.currentSub = type;
        this._applySubIndicator();
    };

    KlineRenderer.prototype._applySubIndicator = function () {
        if (!this.subContainer || !this.subChart) return;
        var container = this.subContainer;

        if (this.currentSub === 'none' || !this.dataCache || !this.dataCache.length) {
            this._clearSubSeries();
            container.style.display = 'none';
            if (this.subLegendEl) this.subLegendEl.innerHTML = '';
            return;
        }

        var data = this.dataCache;
        var required = SUB_MIN_BARS[this.currentSub] || 0;
        var theme = ChartsTheme.getKlineTheme();
        var ic = theme.indicatorColors;

        if (data.length < required) {
            this._clearSubSeries();
            container.style.display = 'block';
            if (this.subLegendEl) {
                this.subLegendEl.innerHTML =
                    '<span style="color:' + theme.legendLabelColor + '">数据不足 (需要至少 ' +
                    required + ' 根K线,当前 ' + data.length + ' 根)</span>';
            }
            return;
        }

        container.style.display = 'block';
        this._clearSubSeries();

        var closes = data.map(function (d) { return d.close; });
        var highs = data.map(function (d) { return d.high; });
        var lows = data.map(function (d) { return d.low; });
        var vols = data.map(function (d) { return d.volume; });
        var dates = data.map(function (d) { return d.date; });
        var chart = this.subChart;

        if (this.currentSub === 'macd') {
            var macd = I.MACD(closes);
            this.subSeries.dif = chart.addLineSeries({ color: ic.macd.dif, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.dea = chart.addLineSeries({ color: ic.macd.dea, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.hist = chart.addHistogramSeries({ priceFormat: { type: 'price', precision: 2, minMove: 0.01 }, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.dif.setData(I.toTimeSeries(macd.dif, dates));
            this.subSeries.dea.setData(I.toTimeSeries(macd.dea, dates));
            this.subSeries.hist.setData(I.toHistSeries(macd.hist, dates, ic.macd.histUp, ic.macd.histDown));
        } else if (this.currentSub === 'kdj') {
            var kdj = I.KDJ(highs, lows, closes);
            this.subSeries.k = chart.addLineSeries({ color: ic.kdj.k, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.d = chart.addLineSeries({ color: ic.kdj.d, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.j = chart.addLineSeries({ color: ic.kdj.j, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.k.setData(I.toTimeSeries(kdj.k, dates));
            this.subSeries.d.setData(I.toTimeSeries(kdj.d, dates));
            this.subSeries.j.setData(I.toTimeSeries(kdj.j, dates));
        } else if (this.currentSub === 'rsi') {
            var rsi = I.RSI(closes);
            this.subSeries.rsi6 = chart.addLineSeries({ color: ic.rsi.rsi6, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.rsi12 = chart.addLineSeries({ color: ic.rsi.rsi12, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.rsi24 = chart.addLineSeries({ color: ic.rsi.rsi24, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.rsi6.setData(I.toTimeSeries(rsi.rsi6, dates));
            this.subSeries.rsi12.setData(I.toTimeSeries(rsi.rsi12, dates));
            this.subSeries.rsi24.setData(I.toTimeSeries(rsi.rsi24, dates));
        } else if (this.currentSub === 'wr') {
            var wr = I.WR(highs, lows, closes);
            this.subSeries.wr = chart.addLineSeries({ color: ic.kdj.k, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.wr.setData(I.toTimeSeries(wr, dates));
        } else if (this.currentSub === 'cci') {
            var cci = I.CCI(highs, lows, closes);
            this.subSeries.cci = chart.addLineSeries({ color: ic.kdj.j, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.cci.setData(I.toTimeSeries(cci, dates));
        } else if (this.currentSub === 'dmi') {
            this.subSeries.adx = chart.addLineSeries({ color: ic.macd.dif, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.pdi = chart.addLineSeries({ color: ic.macd.histUp, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.mdi = chart.addLineSeries({ color: ic.macd.histDown, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
            this.subSeries.adx.setData(I.toTimeSeries(I.ADX(highs, lows, closes), dates));
            this.subSeries.pdi.setData(I.toTimeSeries(I.PLUS_DI(highs, lows, closes), dates));
            this.subSeries.mdi.setData(I.toTimeSeries(I.MINUS_DI(highs, lows, closes), dates));
        }

        // 同步主图可见范围到副图
        this.fitMainRange();
    };

    KlineRenderer.prototype._clearSubSeries = function () {
        if (!this.subChart) return;
        for (var key in this.subSeries) {
            this._removeSeries(this.subSeries[key]);
        }
        this.subSeries = {};
    };

    KlineRenderer.prototype._removeSeries = function (s) {
        if (!s || !this.mainChart) return;
        try { this.mainChart.removeSeries(s); } catch (e1) {
            try { this.subChart.removeSeries(s); } catch (e2) {}
        }
    };

    // ========== 十字光标图例 ==========
    KlineRenderer.prototype._onMainCrosshair = function (param) {
        if (!this.mainLegendEl) return;
        if (!param || !param.time || !param.seriesData) { this.mainLegendEl.innerHTML = ''; return; }
        var candle = param.seriesData.get(this.candleSeries);
        var vol = param.seriesData.get(this.volumeSeries);
        if (!candle) { this.mainLegendEl.innerHTML = ''; return; }

        var t = ChartsTheme.getKlineTheme();
        var clr = candle.close >= candle.open ? t.candlestick.upColor : t.candlestick.downColor;
        var labelClr = t.legendLabelColor;
        var ic = t.indicatorColors;

        var indicatorLine = '';
        if (this.currentMain === 'boll' && this.bollSeries.upper) {
            var up = param.seriesData.get(this.bollSeries.upper);
            var mid = param.seriesData.get(this.bollSeries.mid);
            var lo = param.seriesData.get(this.bollSeries.lower);
            indicatorLine = '<br>' +
                '<span style="color:' + ic.boll.upper + '">UP: ' + (up ? up.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + ic.boll.mid + '">MID: ' + (mid ? mid.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + ic.boll.lower + '">LOW: ' + (lo ? lo.value.toFixed(2) : '-') + '</span>';
        } else if (this.currentMain === 'ema' && this.emaSeries.ema12) {
            var e12 = param.seriesData.get(this.emaSeries.ema12);
            var e26 = param.seriesData.get(this.emaSeries.ema26);
            indicatorLine = '<br>' +
                '<span style="color:' + t.maColors.ma10 + '">EMA12: ' + (e12 ? e12.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + t.maColors.ma20 + '">EMA26: ' + (e26 ? e26.value.toFixed(2) : '-') + '</span>';
        } else {
            var m5 = param.seriesData.get(this.maSeries.ma5);
            var m10 = param.seriesData.get(this.maSeries.ma10);
            var m20 = param.seriesData.get(this.maSeries.ma20);
            indicatorLine = '<br>' +
                '<span style="color:' + t.maColors.ma5 + '">MA5: ' + (m5 ? m5.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + t.maColors.ma10 + '">MA10: ' + (m10 ? m10.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + t.maColors.ma20 + '">MA20: ' + (m20 ? m20.value.toFixed(2) : '-') + '</span>';
        }

        this.mainLegendEl.innerHTML =
            '<span style="color:' + labelClr + '">' + I.fmtTime(param.time) + '</span> ' +
            '开 <span style="color:' + clr + '">' + candle.open.toFixed(2) + '</span> ' +
            '高 <span style="color:' + clr + '">' + candle.high.toFixed(2) + '</span> ' +
            '低 <span style="color:' + clr + '">' + candle.low.toFixed(2) + '</span> ' +
            '收 <span style="color:' + clr + '">' + candle.close.toFixed(2) + '</span>' +
            (vol ? '  量 <span style="color:' + labelClr + '">' + (vol.value / 10000).toFixed(1) + '万</span>' : '') +
            indicatorLine;
    };

    KlineRenderer.prototype._onSubCrosshair = function (param) {
        if (!this.subLegendEl) return;
        if (!param || !param.time || !param.seriesData) { this.subLegendEl.innerHTML = ''; return; }
        var t = ChartsTheme.getKlineTheme();
        var ic = t.indicatorColors;
        var labelClr = t.legendLabelColor;
        var html = '<span style="color:' + labelClr + '">' + I.fmtTime(param.time) + '</span> ';
        var s = this.subSeries;
        var g = function (series) { var v = param.seriesData.get(series); return v ? v.value.toFixed(2) : '-'; };

        if (this.currentSub === 'macd' && s.dif) {
            html += '<span style="color:' + ic.macd.dif + '">DIF: ' + g(s.dif) + '</span> ' +
                    '<span style="color:' + ic.macd.dea + '">DEA: ' + g(s.dea) + '</span> ' +
                    '<span style="color:' + ic.macd.histUp + '">MACD: ' + g(s.hist) + '</span>';
        } else if (this.currentSub === 'kdj' && s.k) {
            html += '<span style="color:' + ic.kdj.k + '">K: ' + g(s.k) + '</span> ' +
                    '<span style="color:' + ic.kdj.d + '">D: ' + g(s.d) + '</span> ' +
                    '<span style="color:' + ic.kdj.j + '">J: ' + g(s.j) + '</span>';
        } else if (this.currentSub === 'rsi' && s.rsi6) {
            html += '<span style="color:' + ic.rsi.rsi6 + '">RSI6: ' + g(s.rsi6) + '</span> ' +
                    '<span style="color:' + ic.rsi.rsi12 + '">RSI12: ' + g(s.rsi12) + '</span> ' +
                    '<span style="color:' + ic.rsi.rsi24 + '">RSI24: ' + g(s.rsi24) + '</span>';
        } else if (this.currentSub === 'wr' && s.wr) {
            html += '<span style="color:' + ic.kdj.k + '">WR: ' + g(s.wr) + '</span>';
        } else if (this.currentSub === 'cci' && s.cci) {
            html += '<span style="color:' + ic.kdj.j + '">CCI: ' + g(s.cci) + '</span>';
        } else if (this.currentSub === 'dmi' && s.adx) {
            html += '<span style="color:' + ic.macd.dif + '">ADX: ' + g(s.adx) + '</span> ' +
                    '<span style="color:' + ic.macd.histUp + '">+DI: ' + g(s.pdi) + '</span> ' +
                    '<span style="color:' + ic.macd.histDown + '">-DI: ' + g(s.mdi) + '</span>';
        }
        this.subLegendEl.innerHTML = html;
    };

    // ========== 工具方法 ==========
    KlineRenderer.prototype.setVisibleBars = function (count) {
        if (!this.mainChart || !this.dataCache || !this.dataCache.length) return;
        this.defaultVisible = count;
        var effectiveCount = Math.min(count, this.dataCache.length);
        var fromIdx = Math.max(0, this.dataCache.length - effectiveCount);
        this.mainChart.timeScale().setVisibleLogicalRange({
            from: fromIdx,
            to: this.dataCache.length - 1 + 0.5,
        });
        this.fitMainRange();
    };

    KlineRenderer.prototype.fitMainRange = function () {
        if (!this.mainChart || !this.subChart) return;
        try {
            var range = this.mainChart.timeScale().getVisibleLogicalRange();
            if (range) this.subChart.timeScale().setVisibleLogicalRange(range);
        } catch (e) {}
    };

    KlineRenderer.prototype.resize = function () {
        if (this._resizeObserver) this._resizeObserver.disconnect();
        if (this.mainChart && this.mainContainer) {
            this.mainChart.applyOptions({ width: this.mainContainer.clientWidth, height: this.mainContainer.clientHeight });
        }
        if (this._subResizeObserver) this._subResizeObserver.disconnect();
        if (this.subChart && this.subContainer) {
            this.subChart.applyOptions({ width: this.subContainer.clientWidth, height: this.subContainer.clientHeight });
        }
        this._bindResizeObservers();
    };

    KlineRenderer.prototype._bindResizeObservers = function () {
        var self = this;
        if (this.mainContainer) {
            this._resizeObserver = new ResizeObserver(function () {
                if (!self.mainChart) return;
                self.mainChart.applyOptions({ width: self.mainContainer.clientWidth, height: self.mainContainer.clientHeight });
            });
            this._resizeObserver.observe(this.mainContainer);
        }
        if (this.subContainer) {
            this._subResizeObserver = new ResizeObserver(function () {
                if (self.subChart && self.subContainer.clientWidth > 0) {
                    self.subChart.applyOptions({ width: self.subContainer.clientWidth, height: self.subContainer.clientHeight });
                }
            });
            this._subResizeObserver.observe(this.subContainer);
        }
    };

    // ========== 销毁 ==========
    KlineRenderer.prototype.dispose = function () {
        if (this._themeHandler) {
            window.removeEventListener('theme:changed', this._themeHandler);
            this._themeHandler = null;
        }
        if (this._resizeObserver) { this._resizeObserver.disconnect(); this._resizeObserver = null; }
        if (this._subResizeObserver) { this._subResizeObserver.disconnect(); this._subResizeObserver = null; }
        this._clearSubSeries();
        if (this.mainLegendEl && this.mainLegendEl.parentNode) this.mainLegendEl.parentNode.removeChild(this.mainLegendEl);
        if (this.subLegendEl && this.subLegendEl.parentNode) this.subLegendEl.parentNode.removeChild(this.subLegendEl);
        try { if (this.mainChart) this.mainChart.remove(); } catch (e) {}
        try { if (this.subChart) this.subChart.remove(); } catch (e) {}
        this.mainChart = null;
        this.subChart = null;
        this.candleSeries = null;
        this.volumeSeries = null;
        this.dataCache = null;
    };

    window.KlineRenderer = KlineRenderer;
    if (typeof module !== 'undefined' && module.exports) module.exports = KlineRenderer;
})();
