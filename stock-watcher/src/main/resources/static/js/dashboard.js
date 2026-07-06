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

function renderKline(data, stockCode) {
    const container = document.getElementById('klineChart');
    const theme = ChartsTheme.getKlineTheme();

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
            const m5 = param.seriesData.get(ma5Series);
            const m10 = param.seriesData.get(ma10Series);
            const m20 = param.seriesData.get(ma20Series);
            if (!candle) { klineLegendEl.innerHTML = ''; return; }

            const clr = candle.close >= candle.open ? '#ef4444' : '#10b981';
            const labelClr = theme.legendLabelColor;
            klineLegendEl.innerHTML =
                `<span style="color:${labelClr}">${fmtTime(param.time)}</span> ` +
                `开 <span style="color:${clr}">${candle.open.toFixed(2)}</span> ` +
                `高 <span style="color:${clr}">${candle.high.toFixed(2)}</span> ` +
                `低 <span style="color:${clr}">${candle.low.toFixed(2)}</span> ` +
                `收 <span style="color:${clr}">${candle.close.toFixed(2)}</span>` +
                (vol ? ` 量 <span style="color:${labelClr}">${(vol.value / 10000).toFixed(1)}万</span>` : '') +
                `<br>` +
                `<span style="color:${theme.maColors.ma5}">MA5: ${m5 ? m5.value.toFixed(2) : '—'}</span> ` +
                `<span style="color:${theme.maColors.ma10}">MA10: ${m10 ? m10.value.toFixed(2) : '—'}</span> ` +
                `<span style="color:${theme.maColors.ma20}">MA20: ${m20 ? m20.value.toFixed(2) : '—'}</span>`;
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
}

function updateKlineTheme() {
    if (!klineChart) return;
    const theme = ChartsTheme.getKlineTheme();
    klineChart.applyOptions({
        layout: theme.layout,
        grid: theme.grid,
        crosshair: theme.crosshair,
        rightPriceScale: theme.rightPriceScale,
        timeScale: theme.timeScale,
    });
    candleSeries.applyOptions(theme.candlestick);
    ma5Series.applyOptions({ color: theme.maColors.ma5 });
    ma10Series.applyOptions({ color: theme.maColors.ma10 });
    ma20Series.applyOptions({ color: theme.maColors.ma20 });
}

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
            <td class="text-end fw-medium">${s.currentPrice != null ? e(String(s.currentPrice)) : '—'}</td>
            <td class="text-end">
                <span class="${upDown}">
                    ${s.changePercent != null ? sign + Number(s.changePercent).toFixed(2) + '%' : '—'}
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

// ========== Market Rankings ==========
function refreshRanking() {
    StockApp.get('/market/ranking', null, function(resp) {
        if (resp.code !== 200 || !resp.data) return;
        renderRankingTable('topGainersBody', resp.data.topGainers, 'pctChg');
        renderRankingTable('topLosersBody', resp.data.topLosers, 'pctChg');
        renderRankingTable('topAmountBody', resp.data.topAmount, 'amount');
    });
}

function renderRankingTable(tbodyId, list, highlight) {
    const tbody = document.getElementById(tbodyId);
    const e = StockApp.escapeHtml;
    if (!list || !list.length) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-3">暂无数据</td></tr>';
        return;
    }
    tbody.innerHTML = list.map((s, i) => {
        const upDown = s.pctChg >= 0 ? 'rise' : 'fall';
        const sign = s.pctChg >= 0 ? '+' : '';
        const rankBadge = i < 3 ? `<span class="badge bg-danger bg-opacity-75">${i + 1}</span>` : (i + 1);
        let thirdCol;
        if (highlight === 'amount') {
            thirdCol = formatAmount(s.amount);
        } else {
            thirdCol = s.close != null ? e(String(s.close)) : '—';
        }
        return `
        <tr>
            <td class="text-center">${rankBadge}</td>
            <td>
                <span class="fw-medium">${e(s.name)}</span>
                <small class="text-muted ms-1">${e(s.code)}</small>
            </td>
            <td class="text-end">${thirdCol}</td>
            <td class="text-end ${upDown} fw-medium">${sign}${Number(s.pctChg).toFixed(2)}%</td>
        </tr>`;
    }).join('');
}

function formatAmount(amount) {
    if (amount == null) return '—';
    const wan = amount / 10;
    if (wan >= 10000) return (wan / 10000).toFixed(2) + '亿';
    return wan.toFixed(0) + '万';
}

// ========== Charts ==========
let trendChartInstance = null;
let pieChartInstance = null;

function renderTrendChart(list) {
    const container = document.getElementById('trendChart');
    const echartsTheme = ChartsTheme.getEChartsTheme();

    if (!trendChartInstance) {
        trendChartInstance = echarts.init(container);
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

    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    const borderColor = isDark ? '#0f1520' : '#ffffff';

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

function updateChartsTheme() {
    if (trendChartInstance) {
        trendChartInstance.dispose();
        trendChartInstance = null;
    }
    if (pieChartInstance) {
        pieChartInstance.dispose();
        pieChartInstance = null;
    }
    renderTrendChart(watchlistData);
    renderPieChart(watchlistData);
}

// ========== Theme Change Handler ==========
function onThemeChange() {
    updateKlineTheme();
    updateChartsTheme();
}

// ========== Init ==========
document.addEventListener('DOMContentLoaded', function() {
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

    // Listen for theme changes
    document.addEventListener('themechange', onThemeChange);

    refreshWatchlist();
    refreshRanking();
});
