/**
 * Money Flow Page - 资金流向
 * 5 Tab lazy loading: 个股资金 / 北向资金 / 龙虎榜 / 大宗交易 / 融资融券
 */
(function () {
'use strict';

// ==================== State ====================
var state = {
    tradeDate: '',           // yyyyMMdd format
    activeTab: 'stock',
    loadedTabs: { stock: false, hsgt: false, toplist: false, blocktrade: false, margin: false },
    charts: { stockFlow: null, hsgtRatio: null, blockTradePremium: null, marginTrend: null },
    hsgtChannel: 'sh',       // sh / sz / all  (HTML default active = sh)
    marginMarket: 'sh',      // sh / sz / all  (HTML default active = sh)
    hsgtDays: 7,
    marginDays: 7,
    blockTradePage: 1
};

// ==================== Utility Functions ====================

function todayStr() {
    var d = new Date();
    return d.getFullYear()
        + String(d.getMonth() + 1).padStart(2, '0')
        + String(d.getDate()).padStart(2, '0');
}

function todayDashStr() {
    var d = new Date();
    return d.getFullYear()
        + '-' + String(d.getMonth() + 1).padStart(2, '0')
        + '-' + String(d.getDate()).padStart(2, '0');
}

function toYmd(dashStr) {
    return dashStr ? dashStr.replace(/-/g, '') : '';
}

/** Format yyyyMMdd -> yyyy-MM-dd for display */
function fmtDate(s) {
    if (!s) return '--';
    if (s.length === 8) return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8);
    return s;
}

/** Format yuan -> 万/亿 */
function fmtAmount(v) {
    if (v == null || v === '' || isNaN(v)) return '--';
    v = Number(v);
    var abs = Math.abs(v);
    if (abs >= 100000000) return (v / 100000000).toFixed(2) + '亿';
    if (abs >= 10000) return (v / 10000).toFixed(2) + '万';
    return (v / 10000).toFixed(2) + '万';
}

/** Format shares */
function fmtVolume(v) {
    if (v == null || v === '' || isNaN(v)) return '--';
    v = Number(v);
    var abs = Math.abs(v);
    if (abs >= 100000000) return (v / 100000000).toFixed(2) + '亿';
    if (abs >= 10000) return (v / 10000).toFixed(2) + '万';
    return v.toFixed(0);
}

/** Format percentage with sign */
function fmtPct(v) {
    if (v == null || v === '' || isNaN(v)) return '--';
    v = Number(v);
    var sign = v > 0 ? '+' : '';
    return sign + v.toFixed(2) + '%';
}

/** A-stock convention: positive=rise(red), negative=fall(green) */
function flowClass(v) {
    if (v == null || v === '' || isNaN(v)) return '';
    v = Number(v);
    if (v > 0) return 'rise';
    if (v < 0) return 'fall';
    return '';
}

function getTheme() {
    if (typeof ChartsTheme !== 'undefined') {
        return ChartsTheme.getEChartsTheme();
    }
    return {
        textStyle: { color: '#94a3b8' },
        tooltip: {},
        axisLine: { lineStyle: { color: '#1e293b' } },
        axisTick: { lineStyle: { color: '#1e293b' } },
        axisLabel: { color: '#64748b', fontSize: 11 },
        splitLine: { lineStyle: { color: 'rgba(30, 41, 59, 0.6)', type: 'dashed' } },
        legend: { textStyle: { color: '#94a3b8' } }
    };
}

function registerChart(instance) {
    if (instance && typeof ChartsTheme !== 'undefined') {
        ChartsTheme.register(instance, 'echarts');
    }
    return instance;
}

function emptyRow(cols, msg) {
    return '<tr><td colspan="' + cols + '" class="text-center text-muted py-4">' + StockApp.escapeHtml(msg || '暂无数据') + '</td></tr>';
}

function stockLink(tsCode) {
    return '<a href="' + (StockApp.contextPath || '') + '/page/stock-detail/' + encodeURIComponent(tsCode) + '?tab=moneyflow" class="mf-code-link">' + StockApp.escapeHtml(tsCode) + '</a>';
}

function detailBtn(tsCode) {
    return '<a href="' + (StockApp.contextPath || '') + '/page/stock-detail/' + encodeURIComponent(tsCode) + '?tab=moneyflow" class="btn btn-sm btn-outline-primary">查看详情</a>';
}

function riseColor() {
    return getComputedStyle(document.documentElement).getPropertyValue('--rise-color').trim() || '#ef4444';
}

function fallColor() {
    return getComputedStyle(document.documentElement).getPropertyValue('--fall-color').trim() || '#10b981';
}

function sanitizeId(tsCode) {
    return String(tsCode).replace(/\./g, '_').replace(/[^a-zA-Z0-9_]/g, '');
}

function channelToExchangeId(channel) {
    if (channel === 'sh') return 'SH';
    if (channel === 'sz') return 'SZ';
    return 'ALL';
}

function marketToExchangeId(market) {
    if (market === 'sh') return 'SSE';
    if (market === 'sz') return 'SZSE';
    return 'ALL';
}

function exchangeLabel(id) {
    if (id === 'SH') return '沪股通';
    if (id === 'SZ') return '深股通';
    return StockApp.escapeHtml(id || '--');
}

function showChartEmpty(chart, theme) {
    chart.setOption({
        title: {
            text: '暂无数据', left: 'center', top: 'center',
            textStyle: { color: (theme.textStyle || {}).color || '#94a3b8', fontSize: 14 }
        },
        xAxis: { show: false },
        yAxis: { show: false },
        series: []
    }, true);
}

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', function () {
    var dateInput = document.getElementById('mfDate');
    if (dateInput) {
        dateInput.value = todayDashStr();
    }
    state.tradeDate = todayStr();

    bindEvents();
    loadTab('stock');
});

// ==================== Event Binding ====================

function bindEvents() {
    // Tab switch - lazy loading
    var tabMap = {
        '#tab-stock': 'stock',
        '#tab-hsgt': 'hsgt',
        '#tab-toplist': 'toplist',
        '#tab-blocktrade': 'blocktrade',
        '#tab-margin': 'margin'
    };

    document.querySelectorAll('.moneyflow-tabs .nav-link').forEach(function (tab) {
        tab.addEventListener('shown.bs.tab', function () {
            var target = tab.getAttribute('data-bs-target');
            var tabName = tabMap[target];
            if (!tabName) return;
            state.activeTab = tabName;

            // Resize charts when tab becomes visible
            setTimeout(function () { resizeTabCharts(tabName); }, 50);

            if (!state.loadedTabs[tabName]) {
                loadTab(tabName);
            }
        });
    });

    // Date change -> reload current tab
    var dateInput = document.getElementById('mfDate');
    if (dateInput) {
        dateInput.addEventListener('change', function () {
            state.tradeDate = toYmd(dateInput.value);
            state.loadedTabs = { stock: false, hsgt: false, toplist: false, blocktrade: false, margin: false };
            state.blockTradePage = 1;
            loadTab(state.activeTab);
        });
    }

    // Refresh button -> reload current tab
    var refreshBtn = document.getElementById('mfRefreshBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', function () {
            state.blockTradePage = 1;
            loadTab(state.activeTab);
        });
    }

    // Tab B sub-nav: 沪股通/深股通/北向合计
    document.querySelectorAll('[data-mf-channel]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var channel = btn.getAttribute('data-mf-channel');
            document.querySelectorAll('[data-mf-channel]').forEach(function (b) {
                b.classList.remove('active');
            });
            btn.classList.add('active');
            state.hsgtChannel = channel;
            renderTabB();
        });
    });

    // Tab E sub-nav: 沪市/深市/两市合计
    document.querySelectorAll('[data-mf-market]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var market = btn.getAttribute('data-mf-market');
            document.querySelectorAll('[data-mf-market]').forEach(function (b) {
                b.classList.remove('active');
            });
            btn.classList.add('active');
            state.marginMarket = market;
            renderTabE();
        });
    });

    // Range buttons (Tab B and Tab E share .mf-range-btn class)
    document.querySelectorAll('.mf-range-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var range = parseInt(btn.getAttribute('data-range'), 10);
            var tabPane = btn.closest('.tab-pane');
            if (!tabPane) return;

            // Update active state within the same button group
            var group = btn.closest('.btn-group');
            if (group) {
                group.querySelectorAll('.mf-range-btn').forEach(function (b) {
                    b.classList.remove('active');
                });
                btn.classList.add('active');
            }

            if (tabPane.id === 'tab-hsgt') {
                state.hsgtDays = range;
                renderTabB();
            } else if (tabPane.id === 'tab-margin') {
                state.marginDays = range;
                renderTabE();
            }
        });
    });
}

function resizeTabCharts(tabName) {
    switch (tabName) {
        case 'stock':
            if (state.charts.stockFlow) state.charts.stockFlow.resize();
            break;
        case 'hsgt':
            if (state.charts.hsgtRatio) state.charts.hsgtRatio.resize();
            break;
        case 'blocktrade':
            if (state.charts.blockTradePremium) state.charts.blockTradePremium.resize();
            break;
        case 'margin':
            if (state.charts.marginTrend) state.charts.marginTrend.resize();
            break;
    }
}

// ==================== Tab Loading ====================

function loadTab(tabName) {
    switch (tabName) {
        case 'stock': renderTabA(); break;
        case 'hsgt': renderTabB(); break;
        case 'toplist': renderTabC(); break;
        case 'blocktrade': renderTabD(); break;
        case 'margin': renderTabE(); break;
    }
    state.loadedTabs[tabName] = true;
}

// ============================================================
//  Tab A: 个股资金
// ============================================================

function renderTabA() {
    var tbody = document.getElementById('stockFlowBody');
    if (tbody) tbody.innerHTML = emptyRow(11, '加载中...');

    StockApp.get('/api/moneyflow/top', {
        tradeDate: state.tradeDate,
        limit: 50,
        sortBy: 'net_mf_amount',
        order: 'desc'
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderStockFlowChart(resp.data);
            renderStockFlowTable(resp.data);
        } else {
            renderStockFlowChart([]);
            if (tbody) tbody.innerHTML = emptyRow(11, resp.message || '暂无数据');
        }
    });
}

function renderStockFlowChart(data) {
    var container = document.getElementById('stockFlowChart');
    if (!container) return;
    var theme = getTheme();

    if (!state.charts.stockFlow) {
        state.charts.stockFlow = echarts.init(container);
        registerChart(state.charts.stockFlow);
        window.addEventListener('resize', function () {
            if (state.charts.stockFlow) state.charts.stockFlow.resize();
        });
    }
    var chart = state.charts.stockFlow;

    if (!data || !data.length) {
        showChartEmpty(chart, theme);
        return;
    }

    var top10 = data.slice(0, 10);
    var names = top10.map(function (d) { return d.name || d.tsCode; });
    var values = top10.map(function (d) {
        return Number((Number(d.netMfAmount) || 0) / 10000); // yuan -> 万元
    });
    var tsCodes = top10.map(function (d) { return d.tsCode; });

    chart.off('click');
    chart.on('click', function (params) {
        if (params.dataIndex != null && params.dataIndex >= 0 && tsCodes[params.dataIndex]) {
            window.location.href = (StockApp.contextPath || '') + '/page/stock-detail/' + encodeURIComponent(tsCodes[params.dataIndex]) + '?tab=moneyflow';
        }
    });

    chart.setOption({
        tooltip: Object.assign({}, theme.tooltip || {}, {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: function (params) {
                var p = params[0];
                var val = p.value;
                return StockApp.escapeHtml(p.name) + '<br/>主力净流入: <b style="color:' + (val >= 0 ? riseColor() : fallColor()) + '">' + (val >= 0 ? '+' : '') + val.toFixed(2) + '万元</b>';
            }
        }),
        grid: { left: '3%', right: '5%', bottom: '3%', top: '3%', containLabel: true },
        xAxis: {
            type: 'value',
            axisLine: theme.axisLine,
            axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, {
                formatter: function (val) {
                    if (Math.abs(val) >= 10000) return (val / 10000).toFixed(1) + '亿';
                    return val.toFixed(0);
                }
            }),
            splitLine: theme.splitLine
        },
        yAxis: {
            type: 'category',
            data: names,
            inverse: true,
            axisLine: theme.axisLine,
            axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 11 })
        },
        series: [{
            type: 'bar',
            data: values.map(function (v) {
                return {
                    value: v,
                    itemStyle: { color: v >= 0 ? riseColor() : fallColor() }
                };
            }),
            barMaxWidth: 20,
            label: {
                show: true,
                position: 'right',
                formatter: function (params) {
                    var v = params.value;
                    return (v >= 0 ? '+' : '') + v.toFixed(0);
                },
                color: (theme.textStyle || {}).color || '#94a3b8',
                fontSize: 11
            }
        }]
    }, true);
}

function renderStockFlowTable(data) {
    var tbody = document.getElementById('stockFlowBody');
    if (!tbody) return;

    if (!data || !data.length) {
        tbody.innerHTML = emptyRow(11);
        return;
    }

    var html = '';
    data.forEach(function (s, i) {
        var net = Number(s.netMfAmount) || 0;
        var elg = (Number(s.buyElgAmount) || 0) - (Number(s.sellElgAmount) || 0);
        var lg = (Number(s.buyLgAmount) || 0) - (Number(s.sellLgAmount) || 0);
        var md = (Number(s.buyMdAmount) || 0) - (Number(s.sellMdAmount) || 0);
        var sm = (Number(s.buySmAmount) || 0) - (Number(s.sellSmAmount) || 0);

        html += '<tr>' +
            '<td>' + (i + 1) + '</td>' +
            '<td>' + stockLink(s.tsCode) + '</td>' +
            '<td>' + StockApp.escapeHtml(s.name || '') + '</td>' +
            '<td class="text-end">-</td>' +
            '<td class="text-end">-</td>' +
            '<td class="text-end ' + flowClass(net) + ' fw-medium">' + fmtAmount(net) + '</td>' +
            '<td class="text-end ' + flowClass(elg) + '">' + fmtAmount(elg) + '</td>' +
            '<td class="text-end ' + flowClass(lg) + '">' + fmtAmount(lg) + '</td>' +
            '<td class="text-end ' + flowClass(md) + '">' + fmtAmount(md) + '</td>' +
            '<td class="text-end ' + flowClass(sm) + '">' + fmtAmount(sm) + '</td>' +
            '<td>' + detailBtn(s.tsCode) + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;
}

// ============================================================
//  Tab B: 北向资金
// ============================================================

function renderTabB() {
    var tbody = document.getElementById('hsgtHoldingsBody');
    if (tbody) tbody.innerHTML = emptyRow(7, '加载中...');

    var exchangeId = channelToExchangeId(state.hsgtChannel);
    var days = state.hsgtDays;

    // Load ratio trend chart
    StockApp.get('/api/hk-hold/ratio-trend', {
        days: days,
        exchangeId: exchangeId
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderHsgtRatioChart(resp.data);
        } else {
            renderHsgtRatioChart([]);
        }
    });

    // Load top holdings table
    StockApp.get('/api/hk-hold/top-holdings', {
        tradeDate: state.tradeDate,
        exchangeId: exchangeId,
        limit: 10
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderHsgtHoldingsTable(resp.data);
        } else {
            if (tbody) tbody.innerHTML = emptyRow(7, resp.message || '暂无数据');
        }
    });
}

function renderHsgtRatioChart(data) {
    var container = document.getElementById('hsgtRatioChart');
    if (!container) return;
    var theme = getTheme();

    if (!state.charts.hsgtRatio) {
        state.charts.hsgtRatio = echarts.init(container);
        registerChart(state.charts.hsgtRatio);
        window.addEventListener('resize', function () {
            if (state.charts.hsgtRatio) state.charts.hsgtRatio.resize();
        });
    }
    var chart = state.charts.hsgtRatio;

    if (!data || !data.length) {
        showChartEmpty(chart, theme);
        return;
    }

    var channel = state.hsgtChannel;
    var colors = (typeof ChartsTheme !== 'undefined') ? ChartsTheme.getChartColors() : ['#3b82f6', '#f97316', '#a855f7'];
    var series = [];
    var legendData = [];
    var dateLabels = [];

    if (channel === 'all') {
        // Split by exchangeId, compute total
        var shMap = {};
        var szMap = {};
        var dateSet = {};

        data.forEach(function (d) {
            dateSet[d.tradeDate] = true;
            if (d.exchangeId === 'SH') {
                shMap[d.tradeDate] = Number(d.ratio) || 0;
            } else if (d.exchangeId === 'SZ') {
                szMap[d.tradeDate] = Number(d.ratio) || 0;
            }
        });

        dateLabels = Object.keys(dateSet).sort().map(function (d) { return fmtDate(d); });
        var dates = Object.keys(dateSet).sort();
        var shRatios = dates.map(function (d) { return shMap[d] != null ? shMap[d] : null; });
        var szRatios = dates.map(function (d) { return szMap[d] != null ? szMap[d] : null; });
        var totalRatios = dates.map(function (d) {
            return (shMap[d] || 0) + (szMap[d] || 0);
        });

        legendData = ['沪股通', '深股通', '北向合计'];
        series.push({
            name: '沪股通', type: 'line', smooth: true, data: shRatios, symbol: 'circle', symbolSize: 4,
            lineStyle: { color: colors[0], width: 2 }, itemStyle: { color: colors[0] }
        });
        series.push({
            name: '深股通', type: 'line', smooth: true, data: szRatios, symbol: 'circle', symbolSize: 4,
            lineStyle: { color: colors[1], width: 2 }, itemStyle: { color: colors[1] }
        });
        series.push({
            name: '北向合计', type: 'line', smooth: true, data: totalRatios, symbol: 'none',
            lineStyle: { color: colors[2], width: 2, type: 'dashed' }, itemStyle: { color: colors[2] }
        });
    } else {
        // Single channel: filter by exchangeId
        var filterId = channel === 'sh' ? 'SH' : 'SZ';
        var filtered = data.filter(function (d) {
            return d.exchangeId === filterId;
        }).sort(function (a, b) {
            return (a.tradeDate || '').localeCompare(b.tradeDate || '');
        });

        dateLabels = filtered.map(function (d) { return fmtDate(d.tradeDate); });
        var ratios = filtered.map(function (d) { return Number(d.ratio) || 0; });
        var label = channel === 'sh' ? '沪股通' : '深股通';

        legendData = [label];
        series.push({
            name: label, type: 'line', smooth: true, data: ratios, symbol: 'circle', symbolSize: 4,
            lineStyle: { color: colors[0], width: 2 }, itemStyle: { color: colors[0] },
            areaStyle: (typeof ChartsTheme !== 'undefined') ? ChartsTheme.getAreaGradient(chart, colors[0]) : {}
        });
    }

    chart.setOption({
        tooltip: Object.assign({}, theme.tooltip || {}, {
            trigger: 'axis',
            formatter: function (params) {
                var html = StockApp.escapeHtml(params[0].axisValueLabel) + '<br/>';
                params.forEach(function (p) {
                    html += p.marker + ' ' + StockApp.escapeHtml(p.seriesName) + ': <b>' + (p.value != null ? Number(p.value).toFixed(2) + '%' : '--') + '</b><br/>';
                });
                return html;
            }
        }),
        legend: { data: legendData, top: 5, textStyle: (theme.legend || {}).textStyle || {} },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
        xAxis: {
            type: 'category', data: dateLabels, boundaryGap: false,
            axisLine: theme.axisLine, axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10 })
        },
        yAxis: {
            type: 'value', name: '%',
            axisLine: theme.axisLine, axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, { formatter: function (v) { return v.toFixed(1) + '%'; } }),
            splitLine: theme.splitLine
        },
        series: series
    }, true);
}

function renderHsgtHoldingsTable(data) {
    var tbody = document.getElementById('hsgtHoldingsBody');
    if (!tbody) return;

    if (!data || !data.length) {
        tbody.innerHTML = emptyRow(7);
        return;
    }

    var html = '';
    data.forEach(function (s, i) {
        html += '<tr>' +
            '<td>' + (i + 1) + '</td>' +
            '<td>' + stockLink(s.tsCode || s.code) + '</td>' +
            '<td>' + StockApp.escapeHtml(s.name || '') + '</td>' +
            '<td>' + exchangeLabel(s.exchangeId) + '</td>' +
            '<td class="text-end">' + fmtVolume(s.vol) + '</td>' +
            '<td class="text-end fw-medium">' + (s.ratio != null ? Number(s.ratio).toFixed(2) + '%' : '--') + '</td>' +
            '<td>' + detailBtn(s.tsCode || s.code) + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;
}

// ============================================================
//  Tab C: 龙虎榜
// ============================================================

function renderTabC() {
    var tbody = document.getElementById('toplistBody');
    if (tbody) tbody.innerHTML = emptyRow(10, '加载中...');

    // Load top-list
    StockApp.get('/api/top-list', {
        tradeDate: state.tradeDate
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderToplistTable(resp.data);
        } else {
            if (tbody) tbody.innerHTML = emptyRow(10, resp.message || '暂无数据');
        }
    });

    // Load notable seats
    StockApp.get('/api/top-list/inst/notable', {
        tradeDate: state.tradeDate
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderNotable(resp.data);
        } else {
            renderNotable([]);
        }
    });
}

function renderToplistTable(data) {
    var tbody = document.getElementById('toplistBody');
    if (!tbody) return;

    if (!data || !data.length) {
        tbody.innerHTML = emptyRow(10);
        return;
    }

    var html = '';
    data.forEach(function (s, i) {
        var net = Number(s.netAmount) || 0;
        var lBuy = Number(s.lBuyAmount) || 0;
        var lSell = Number(s.lSellAmount) || 0;
        var pct = s.pctChange;
        var sid = sanitizeId(s.tsCode);

        html += '<tr>' +
            '<td>' + (i + 1) + '</td>' +
            '<td>' + stockLink(s.tsCode) + '</td>' +
            '<td>' + StockApp.escapeHtml(s.name || '') + '</td>' +
            '<td class="text-end">' + (s.close != null ? Number(s.close).toFixed(2) : '--') + '</td>' +
            '<td class="text-end ' + flowClass(pct) + '">' + fmtPct(pct) + '</td>' +
            '<td>' + StockApp.escapeHtml(s.reason || '--') + '</td>' +
            '<td class="text-end ' + flowClass(net) + ' fw-medium">' + fmtAmount(net) + '</td>' +
            '<td class="text-end rise">' + fmtAmount(lBuy) + '</td>' +
            '<td class="text-end fall">' + fmtAmount(lSell) + '</td>' +
            '<td><button class="btn btn-sm btn-outline-info" data-inst-toggle="' + sid + '" onclick="MoneyFlowPage.toggleInst(\'' + s.tsCode + '\')">展开席位</button></td>' +
            '</tr>';
        // Hidden inst row
        html += '<tr id="inst-' + sid + '" style="display:none"></tr>';
    });
    tbody.innerHTML = html;
}

function toggleInst(tsCode) {
    var sid = sanitizeId(tsCode);
    var instRow = document.getElementById('inst-' + sid);
    var btn = document.querySelector('[data-inst-toggle="' + sid + '"]');

    if (!instRow) return;

    if (instRow.style.display === 'none') {
        instRow.style.display = '';
        if (btn) btn.textContent = '收起席位';
        if (!instRow.dataset.loaded) {
            loadInstData(tsCode, instRow);
        }
    } else {
        instRow.style.display = 'none';
        if (btn) btn.textContent = '展开席位';
    }
}

function loadInstData(tsCode, instRow) {
    instRow.innerHTML = '<td colspan="10" class="text-center text-muted py-3">加载中...</td>';

    StockApp.get('/api/top-list/inst', {
        tradeDate: state.tradeDate,
        tsCode: tsCode
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            instRow.innerHTML = renderInstContent(resp.data);
            instRow.dataset.loaded = 'true';
        } else {
            instRow.innerHTML = '<td colspan="10" class="text-center text-muted py-3">暂无席位数据</td>';
        }
    });
}

function renderInstContent(data) {
    if (!data || !data.length) {
        return '<td colspan="10" class="text-center text-muted py-3">暂无席位数据</td>';
    }

    var buySeats = data.filter(function (d) { return (d.side || '').toUpperCase().indexOf('BUY') >= 0 || d.side === 'Buy'; });
    var sellSeats = data.filter(function (d) { return (d.side || '').toUpperCase().indexOf('SELL') >= 0 || d.side === 'Sell'; });

    buySeats.sort(function (a, b) { return (Number(b.buy) || 0) - (Number(a.buy) || 0); });
    sellSeats.sort(function (a, b) { return (Number(b.sell) || 0) - (Number(a.sell) || 0); });

    var html = '<td colspan="10"><div class="row g-2 py-2">';

    // Buy seats
    html += '<div class="col-md-6"><h6 class="small text-muted mb-1">买入席位 TOP 5</h6>';
    html += '<table class="table table-sm table-borderless mb-0"><tbody>';
    buySeats.slice(0, 5).forEach(function (s) {
        html += '<tr>' +
            '<td class="small">' + StockApp.escapeHtml(s.exalter || '') + '</td>' +
            '<td class="text-end small">' + fmtAmount(s.buy) + '</td>' +
            '<td class="text-end small text-muted">' + (s.buyRate != null ? Number(s.buyRate).toFixed(2) + '%' : '--') + '</td>' +
            '<td class="text-end small ' + flowClass(s.netBuy) + '">' + fmtAmount(s.netBuy) + '</td>' +
            '</tr>';
    });
    if (buySeats.length === 0) {
        html += '<tr><td colspan="4" class="text-center text-muted small">暂无数据</td></tr>';
    }
    html += '</tbody></table></div>';

    // Sell seats
    html += '<div class="col-md-6"><h6 class="small text-muted mb-1">卖出席位 TOP 5</h6>';
    html += '<table class="table table-sm table-borderless mb-0"><tbody>';
    sellSeats.slice(0, 5).forEach(function (s) {
        html += '<tr>' +
            '<td class="small">' + StockApp.escapeHtml(s.exalter || '') + '</td>' +
            '<td class="text-end small">' + fmtAmount(s.sell) + '</td>' +
            '<td class="text-end small text-muted">' + (s.sellRate != null ? Number(s.sellRate).toFixed(2) + '%' : '--') + '</td>' +
            '<td class="text-end small ' + flowClass(s.netBuy) + '">' + fmtAmount(s.netBuy) + '</td>' +
            '</tr>';
    });
    if (sellSeats.length === 0) {
        html += '<tr><td colspan="4" class="text-center text-muted small">暂无数据</td></tr>';
    }
    html += '</tbody></table></div>';

    html += '</div></td>';
    return html;
}

function renderNotable(data) {
    var container = document.getElementById('notableContent');
    if (!container) return;

    if (!data || !data.length) {
        container.innerHTML = '<p class="text-muted small mt-2 mb-0">暂无知名席位数据</p>';
        return;
    }

    var buySeats = data.filter(function (d) { return (d.side || '').toUpperCase().indexOf('BUY') >= 0 || d.side === 'Buy'; });
    var sellSeats = data.filter(function (d) { return (d.side || '').toUpperCase().indexOf('SELL') >= 0 || d.side === 'Sell'; });

    buySeats.sort(function (a, b) { return (Number(b.netBuy) || 0) - (Number(a.netBuy) || 0); });
    sellSeats.sort(function (a, b) { return (Number(b.netBuy) || 0) - (Number(a.netBuy) || 0); });

    var html = '';

    if (buySeats.length > 0) {
        html += '<div class="mt-2"><div class="small text-muted fw-bold mb-1">买入席位</div>';
        buySeats.slice(0, 5).forEach(function (s) {
            html += '<div class="d-flex justify-content-between align-items-center py-1 border-bottom border-secondary border-opacity-25">' +
                '<span class="small text-truncate" style="max-width:160px" title="' + StockApp.escapeHtml(s.exalter || '') + '">' + StockApp.escapeHtml(s.exalter || '') + '</span>' +
                '<span class="small ' + flowClass(s.netBuy) + ' fw-medium">' + fmtAmount(s.netBuy) + '</span>' +
                '</div>';
        });
        html += '</div>';
    }

    if (sellSeats.length > 0) {
        html += '<div class="mt-2"><div class="small text-muted fw-bold mb-1">卖出席位</div>';
        sellSeats.slice(0, 5).forEach(function (s) {
            html += '<div class="d-flex justify-content-between align-items-center py-1 border-bottom border-secondary border-opacity-25">' +
                '<span class="small text-truncate" style="max-width:160px" title="' + StockApp.escapeHtml(s.exalter || '') + '">' + StockApp.escapeHtml(s.exalter || '') + '</span>' +
                '<span class="small ' + flowClass(s.netBuy) + ' fw-medium">' + fmtAmount(s.netBuy) + '</span>' +
                '</div>';
        });
        html += '</div>';
    }

    if (html === '') {
        html = '<p class="text-muted small mt-2 mb-0">暂无知名席位数据</p>';
    }

    container.innerHTML = html;
}

// ============================================================
//  Tab D: 大宗交易
// ============================================================

function renderTabD() {
    var tbody = document.getElementById('blockTradeBody');
    if (tbody) tbody.innerHTML = emptyRow(11, '加载中...');

    // Load premium distribution chart
    StockApp.get('/api/block-trade/premium-distribution', {
        tradeDate: state.tradeDate
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderBlockTradePremiumChart(resp.data);
        } else {
            renderBlockTradePremiumChart([]);
        }
    });

    // Load block trade table
    loadBlockTradeTable();
}

function renderBlockTradePremiumChart(data) {
    var container = document.getElementById('blockTradePremiumChart');
    if (!container) return;
    var theme = getTheme();

    if (!state.charts.blockTradePremium) {
        state.charts.blockTradePremium = echarts.init(container);
        registerChart(state.charts.blockTradePremium);
        window.addEventListener('resize', function () {
            if (state.charts.blockTradePremium) state.charts.blockTradePremium.resize();
        });
    }
    var chart = state.charts.blockTradePremium;

    if (!data || !data.length) {
        showChartEmpty(chart, theme);
        return;
    }

    var ranges = data.map(function (d) { return d.premiumRange; });
    var counts = data.map(function (d) { return d.count; });

    function isPositiveRange(rangeStr) {
        if (!rangeStr) return true;
        return rangeStr.trim().charAt(0) !== '-';
    }

    chart.setOption({
        tooltip: Object.assign({}, theme.tooltip || {}, {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: function (params) {
                var p = params[0];
                return StockApp.escapeHtml(p.name) + '<br/>笔数: <b>' + p.value + '</b>';
            }
        }),
        grid: { left: '3%', right: '4%', bottom: '3%', top: '8%', containLabel: true },
        xAxis: {
            type: 'category', data: ranges,
            axisLine: theme.axisLine, axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, { rotate: 30, fontSize: 10 })
        },
        yAxis: {
            type: 'value', name: '笔数',
            axisLine: theme.axisLine, axisTick: theme.axisTick,
            axisLabel: theme.axisLabel,
            splitLine: theme.splitLine
        },
        series: [{
            type: 'bar',
            data: data.map(function (d) {
                return {
                    value: d.count,
                    itemStyle: { color: isPositiveRange(d.premiumRange) ? riseColor() : fallColor() }
                };
            }),
            barMaxWidth: 30,
            label: {
                show: true,
                position: 'top',
                color: (theme.textStyle || {}).color || '#94a3b8',
                fontSize: 11
            }
        }]
    }, true);
}

function loadBlockTradeTable() {
    var tbody = document.getElementById('blockTradeBody');
    if (tbody) tbody.innerHTML = emptyRow(11, '加载中...');

    StockApp.get('/api/block-trade', {
        tradeDate: state.tradeDate,
        page: state.blockTradePage,
        size: 20,
        sortBy: 'amount',
        order: 'desc'
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            var data = resp.data;
            var list, total;
            if (Array.isArray(data)) {
                list = data;
                total = data.length;
            } else if (data.list) {
                list = data.list;
                total = data.total || 0;
            } else {
                list = [];
                total = 0;
            }
            renderBlockTradeTable(list);
            renderBlockTradePagination(total);
        } else {
            if (tbody) tbody.innerHTML = emptyRow(11, resp.message || '暂无数据');
        }
    });
}

function renderBlockTradeTable(data) {
    var tbody = document.getElementById('blockTradeBody');
    if (!tbody) return;

    if (!data || !data.length) {
        tbody.innerHTML = emptyRow(11);
        return;
    }

    var html = '';
    data.forEach(function (t, i) {
        var price = t.price != null ? Number(t.price) : null;
        var closePrice = t.closePrice != null ? Number(t.closePrice) : null;
        var premium = null;
        if (price != null && closePrice != null && closePrice !== 0) {
            premium = (price - closePrice) / closePrice * 100;
        }
        var buyer = t.buyerName || t.buyer || '--';
        var seller = t.sellerName || t.seller || '--';

        html += '<tr>' +
            '<td>' + ((state.blockTradePage - 1) * 20 + i + 1) + '</td>' +
            '<td>' + stockLink(t.tsCode) + '</td>' +
            '<td>' + StockApp.escapeHtml(t.name || '') + '</td>' +
            '<td class="text-end">' + (price != null ? price.toFixed(2) : '--') + '</td>' +
            '<td class="text-end">' + (closePrice != null ? closePrice.toFixed(2) : '--') + '</td>' +
            '<td class="text-end ' + flowClass(premium) + '">' + fmtPct(premium) + '</td>' +
            '<td class="text-end">' + fmtVolume(t.vol) + '</td>' +
            '<td class="text-end">' + fmtAmount(t.amount) + '</td>' +
            '<td class="small" title="' + StockApp.escapeHtml(buyer) + '">' + StockApp.escapeHtml(buyer) + '</td>' +
            '<td class="small" title="' + StockApp.escapeHtml(seller) + '">' + StockApp.escapeHtml(seller) + '</td>' +
            '<td>' + detailBtn(t.tsCode) + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;
}

function renderBlockTradePagination(total) {
    var tableWrap = document.getElementById('blockTradeTable');
    if (!tableWrap) return;

    // Remove existing pagination
    var existingPag = tableWrap.querySelector('.mf-pagination');
    if (existingPag) existingPag.remove();

    var size = 20;
    var totalPages = Math.max(1, Math.ceil(total / size));
    if (totalPages <= 1 && total <= size) return;

    var pag = document.createElement('div');
    pag.className = 'mf-pagination d-flex justify-content-between align-items-center mt-2 px-2';
    pag.innerHTML =
        '<span class="small text-muted">共 ' + total + ' 条 · 第 ' + state.blockTradePage + '/' + totalPages + ' 页</span>' +
        '<div class="btn-group btn-group-sm">' +
        '<button class="btn btn-outline-secondary" id="btPrevPage"' + (state.blockTradePage <= 1 ? ' disabled' : '') + '>上一页</button>' +
        '<button class="btn btn-outline-secondary" id="btNextPage"' + (state.blockTradePage >= totalPages ? ' disabled' : '') + '>下一页</button>' +
        '</div>';
    tableWrap.appendChild(pag);

    var prevBtn = document.getElementById('btPrevPage');
    var nextBtn = document.getElementById('btNextPage');
    if (prevBtn) prevBtn.addEventListener('click', function () {
        if (state.blockTradePage > 1) {
            state.blockTradePage--;
            loadBlockTradeTable();
        }
    });
    if (nextBtn) nextBtn.addEventListener('click', function () {
        if (state.blockTradePage < totalPages) {
            state.blockTradePage++;
            loadBlockTradeTable();
        }
    });
}

// ============================================================
//  Tab E: 融资融券
// ============================================================

function renderTabE() {
    var tbody = document.getElementById('marginDetailBody');
    if (tbody) tbody.innerHTML = emptyRow(10, '加载中...');

    var exchangeId = marketToExchangeId(state.marginMarket);
    var days = state.marginDays;

    // Load margin trend chart
    StockApp.get('/api/margin/trend', {
        days: days,
        exchangeId: exchangeId
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderMarginTrendChart(resp.data);
        } else {
            renderMarginTrendChart([]);
        }
    });

    // Load margin detail table
    StockApp.get('/api/margin/detail/top', {
        tradeDate: state.tradeDate,
        limit: 50,
        sortBy: 'rzrqye',
        order: 'desc'
    }, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderMarginDetailTable(resp.data);
        } else {
            if (tbody) tbody.innerHTML = emptyRow(10, resp.message || '暂无数据');
        }
    });
}

function renderMarginTrendChart(data) {
    var container = document.getElementById('marginTrendChart');
    if (!container) return;
    var theme = getTheme();

    if (!state.charts.marginTrend) {
        state.charts.marginTrend = echarts.init(container);
        registerChart(state.charts.marginTrend);
        window.addEventListener('resize', function () {
            if (state.charts.marginTrend) state.charts.marginTrend.resize();
        });
    }
    var chart = state.charts.marginTrend;

    if (!data || !data.length) {
        showChartEmpty(chart, theme);
        return;
    }

    // Aggregate by date (handles both single and multi-exchange data)
    var dateMap = {};
    data.forEach(function (d) {
        var key = d.tradeDate;
        if (!dateMap[key]) {
            dateMap[key] = { rzye: 0, rqye: 0 };
        }
        dateMap[key].rzye += Number(d.rzye) || 0;
        dateMap[key].rqye += Number(d.rqye) || 0;
    });

    var dates = Object.keys(dateMap).sort();
    var dateLabels = dates.map(function (d) { return fmtDate(d); });
    var rzyeArr = dates.map(function (d) { return Number((dateMap[d].rzye / 100000000).toFixed(4)); }); // 亿元
    var rqyeArr = dates.map(function (d) { return Number((dateMap[d].rqye / 100000000).toFixed(4)); }); // 亿元

    var colors = (typeof ChartsTheme !== 'undefined') ? ChartsTheme.getChartColors() : ['#3b82f6', '#f97316'];

    chart.setOption({
        tooltip: Object.assign({}, theme.tooltip || {}, {
            trigger: 'axis',
            formatter: function (params) {
                var html = StockApp.escapeHtml(params[0].axisValueLabel) + '<br/>';
                params.forEach(function (p) {
                    html += p.marker + ' ' + StockApp.escapeHtml(p.seriesName) + ': <b>' + (p.value != null ? Number(p.value).toFixed(2) + '亿' : '--') + '</b><br/>';
                });
                return html;
            }
        }),
        legend: { data: ['融资余额', '融券余额'], top: 5, textStyle: (theme.legend || {}).textStyle || {} },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
        xAxis: {
            type: 'category', data: dateLabels, boundaryGap: false,
            axisLine: theme.axisLine, axisTick: theme.axisTick,
            axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10 })
        },
        yAxis: [
            {
                type: 'value', name: '融资余额(亿)',
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, {
                    formatter: function (v) { return v.toFixed(0); }
                }),
                splitLine: theme.splitLine
            },
            {
                type: 'value', name: '融券余额(亿)',
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, {
                    formatter: function (v) { return v.toFixed(0); }
                }),
                splitLine: { show: false }
            }
        ],
        series: [
            {
                name: '融资余额', type: 'line', smooth: true, data: rzyeArr,
                yAxisIndex: 0, symbol: 'circle', symbolSize: 4,
                lineStyle: { color: colors[0], width: 2 }, itemStyle: { color: colors[0] },
                areaStyle: (typeof ChartsTheme !== 'undefined') ? ChartsTheme.getAreaGradient(chart, colors[0]) : {}
            },
            {
                name: '融券余额', type: 'line', smooth: true, data: rqyeArr,
                yAxisIndex: 1, symbol: 'circle', symbolSize: 4,
                lineStyle: { color: colors[1], width: 2 }, itemStyle: { color: colors[1] }
            }
        ]
    }, true);
}

function renderMarginDetailTable(data) {
    var tbody = document.getElementById('marginDetailBody');
    if (!tbody) return;

    if (!data || !data.length) {
        tbody.innerHTML = emptyRow(10);
        return;
    }

    var html = '';
    data.forEach(function (m, i) {
        html += '<tr>' +
            '<td>' + (i + 1) + '</td>' +
            '<td>' + stockLink(m.tsCode) + '</td>' +
            '<td>' + StockApp.escapeHtml(m.name || '') + '</td>' +
            '<td class="text-end">' + fmtAmount(m.rzye) + '</td>' +
            '<td class="text-end">' + fmtAmount(m.rzmre) + '</td>' +
            '<td class="text-end">' + fmtAmount(m.rzche) + '</td>' +
            '<td class="text-end">' + fmtAmount(m.rqye) + '</td>' +
            '<td class="text-end">' + fmtVolume(m.rqmcl) + '</td>' +
            '<td class="text-end fw-medium">' + fmtAmount(m.rzrqye) + '</td>' +
            '<td>' + detailBtn(m.tsCode) + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;
}

// ==================== Public API ====================

window.MoneyFlowPage = {
    toggleInst: toggleInst
};

})();
