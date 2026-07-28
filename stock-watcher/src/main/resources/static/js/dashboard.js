/**
 * Dashboard page JavaScript
 * K-line + Watchlist linkage, Market Rankings, Dynamic Charts
 */

// ========== K-Line Chart (TradingView Lightweight Charts) ==========
let klineRenderer = null;
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
        ensureKlineRenderer();
        klineRenderer.setData(resp.data);
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
        ensureKlineRenderer();
        klineRenderer.setData(resp.data);
        // 按周期差异化设置可见 K 线根数，还原原有行为（daily:120/weekly:100/monthly:80）
        var visibleMap = { daily: 120, weekly: 100, monthly: 80 };
        klineRenderer.setVisibleBars(visibleMap[period] || 120);
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

/**
 * 懒创建 KlineRenderer 实例（首次加载 K 线时初始化）
 * 实例状态由组件自身管理，本文件不再持有底层 chart/series 引用
 */
function ensureKlineRenderer() {
    if (klineRenderer) return;
    klineRenderer = new KlineRenderer({
        mainContainer: document.getElementById('klineChart'),
        subContainer: document.getElementById('subIndicatorChart'),
        defaultVisible: 120,
    });
}

// ========== 技术指标切换（委托给 KlineRenderer）==========
function switchMainIndicator(type, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    if (klineRenderer) klineRenderer.setMainIndicator(type);
}

function switchSubIndicator(type, btn) {
    if (btn) {
        btn.closest('.btn-group').querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    }
    if (klineRenderer) klineRenderer.setSubIndicator(type);
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
