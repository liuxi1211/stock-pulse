/**
 * Sector Market Page - 板块行情
 * Heatmap + Industry Ranking + K-line + Members
 */
(function () {
'use strict';

// ==================== State ====================
var rankingData = [];          // 28 industries ranking data
var currentIndustry = null;    // currently selected industry {code, name, indexCode, ...}
var memberPage = 1;
var memberSize = 20;
var memberKeyword = '';
var klineChart = null;
var candleSeries = null;
var volumeSeries = null;
var maSeries = { 5: null, 10: null, 20: null, 60: null };
var maVisible = { 5: true, 10: true, 20: true, 60: false };
var currentRange = 250;         // default 250 days
var klineDataCache = [];        // cached K-line data for MA toggle
var sortState = { field: 'pctChg', desc: true };
var tooltipEl = null;

// ==================== Helpers ====================
/** Convert "yyyyMMdd" -> "yyyy-MM-dd" for LightweightCharts time axis */
function fmtDate(s) {
    if (s && s.length === 8) return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8);
    return s;
}

/** Format crosshair time (string or business-day object) */
function fmtTime(t) {
    if (typeof t === 'string') return t;
    if (typeof t === 'object' && t.year != null)
        return t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0');
    return String(t);
}

// ==================== Init ====================
document.addEventListener('DOMContentLoaded', function () {
    loadRankingData();
    bindEvents();
});

// ==================== Event Binding ====================
function bindEvents() {
    // Refresh button
    var btnRefresh = document.getElementById('btnRefresh');
    if (btnRefresh) btnRefresh.addEventListener('click', loadRankingData);

    // Sortable headers
    document.querySelectorAll('.sector-table th.sortable').forEach(function (th) {
        th.addEventListener('click', function () {
            var field = th.getAttribute('data-sort');
            if (sortState.field === field) {
                sortState.desc = !sortState.desc;
            } else {
                sortState.field = field;
                sortState.desc = true;
            }
            renderRankingTable();
        });
    });

    // MA toggle buttons
    document.querySelectorAll('.ma-toggle').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var ma = parseInt(btn.getAttribute('data-ma'), 10);
            btn.classList.toggle('active');
            maVisible[ma] = btn.classList.contains('active');
            applyMaVisibility();
        });
    });

    // Time range buttons
    document.querySelectorAll('.range-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.range-btn').forEach(function (b) {
                b.classList.remove('active');
            });
            btn.classList.add('active');
            var range = btn.getAttribute('data-range');
            currentRange = range === 'all' ? 9999 : parseInt(range, 10);
            if (currentIndustry) loadKline(currentIndustry.indexCode);
        });
    });

    // Member search (debounce 300ms)
    var searchInput = document.getElementById('memberSearch');
    if (searchInput) {
        var searchTimer = null;
        searchInput.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                memberKeyword = searchInput.value.trim();
                memberPage = 1;
                if (currentIndustry) loadMembers(currentIndustry.industryCode);
            }, 300);
        });
    }

    // Member pagination
    var prevBtn = document.getElementById('memberPrevPage');
    var nextBtn = document.getElementById('memberNextPage');
    if (prevBtn) prevBtn.addEventListener('click', function () {
        if (memberPage > 1 && currentIndustry) {
            memberPage--;
            loadMembers(currentIndustry.industryCode);
        }
    });
    if (nextBtn) nextBtn.addEventListener('click', function () {
        if (currentIndustry) {
            memberPage++;
            loadMembers(currentIndustry.industryCode);
        }
    });

    // Page size change
    var pageSizeSelect = document.getElementById('memberPageSize');
    if (pageSizeSelect) pageSizeSelect.addEventListener('change', function () {
        memberSize = parseInt(pageSizeSelect.value, 10);
        memberPage = 1;
        if (currentIndustry) loadMembers(currentIndustry.industryCode);
    });
}

// ==================== Load Ranking Data ====================
function loadRankingData() {
    var tbody = document.getElementById('industryRankingBody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-4">加载中...</td></tr>';

    StockApp.get('/api/industry/ranking', {}, function (resp) {
        if (resp.code === 200 && resp.data) {
            rankingData = resp.data;
            // Update heatmap date label
            var dateEl = document.getElementById('heatmapDate');
            if (dateEl && rankingData.length > 0 && rankingData[0].tradeDate) {
                var td = rankingData[0].tradeDate;
                dateEl.textContent = td.substring(0, 4) + '-' + td.substring(4, 6) + '-' + td.substring(6, 8);
            }

            renderHeatmap();
            renderRankingTable();

            // Auto-select first industry (highest pctChg)
            if (rankingData.length > 0) {
                selectIndustry(rankingData[0]);
            }
        } else {
            showError();
        }
    });
}

// ==================== Heatmap ====================
function renderHeatmap() {
    var container = document.getElementById('sectorHeatmap');
    if (!container) return;
    container.innerHTML = '';

    rankingData.forEach(function (ind) {
        var cell = document.createElement('div');
        cell.className = 'sector-heatmap-cell';

        var pct = ind.pctChg;
        // Color class
        if (pct == null) {
            cell.classList.add('cell-flat');
        } else if (pct > 3) {
            cell.classList.add('cell-up-strong');
        } else if (pct > 0) {
            cell.classList.add('cell-up');
        } else if (pct < -3) {
            cell.classList.add('cell-down-strong');
        } else if (pct < 0) {
            cell.classList.add('cell-down');
        } else {
            cell.classList.add('cell-flat');
        }

        var pctStr = pct != null ? (pct >= 0 ? '+' : '') + Number(pct).toFixed(2) + '%' : '--';
        var amtStr = ind.amount != null ? StockApp.formatNumber(ind.amount, 1) + '亿' : '--';

        cell.innerHTML =
            '<div class="cell-name">' + StockApp.escapeHtml(ind.industryName) + '</div>' +
            '<div class="cell-pct">' + pctStr + '</div>' +
            '<div class="cell-amount">' + amtStr + '</div>';

        // Tooltip on hover
        cell.addEventListener('mouseenter', function (e) { showTooltip(ind, e); });
        cell.addEventListener('mouseleave', function () { hideTooltip(); });
        cell.addEventListener('mousemove', function (e) { moveTooltip(e); });

        // Click to select industry
        cell.addEventListener('click', function () { selectIndustry(ind); });

        container.appendChild(cell);
    });
}

// --- Tooltip ---
function showTooltip(ind, e) {
    if (!tooltipEl) {
        tooltipEl = document.createElement('div');
        tooltipEl.className = 'sector-tooltip';
        document.body.appendChild(tooltipEl);
    }
    var pct = ind.pctChg;
    var pctStr = pct != null ? (pct >= 0 ? '+' : '') + Number(pct).toFixed(2) + '%' : '--';
    var amtStr = ind.amount != null ? StockApp.formatNumber(ind.amount, 1) + '亿' : '--';
    var gainer = ind.topGainerName
        ? StockApp.escapeHtml(ind.topGainerName) + ' (' + ind.topGainerCode + ') ' + (ind.topGainerPctChg != null ? ((ind.topGainerPctChg >= 0 ? '+' : '') + Number(ind.topGainerPctChg).toFixed(2) + '%') : '--')
        : '--';
    var loser = ind.topLoserName
        ? StockApp.escapeHtml(ind.topLoserName) + ' (' + ind.topLoserCode + ') ' + (ind.topLoserPctChg != null ? ((ind.topLoserPctChg >= 0 ? '+' : '') + Number(ind.topLoserPctChg).toFixed(2) + '%') : '--')
        : '--';

    tooltipEl.innerHTML =
        '<div class="tt-name">' + StockApp.escapeHtml(ind.industryName) + '</div>' +
        '<div class="tt-row"><span>涨跌幅</span><span class="tt-val">' + pctStr + '</span></div>' +
        '<div class="tt-row"><span>成交额</span><span class="tt-val">' + amtStr + '</span></div>' +
        '<div class="tt-row"><span>领涨</span><span class="tt-val">' + gainer + '</span></div>' +
        '<div class="tt-row"><span>领跌</span><span class="tt-val">' + loser + '</span></div>';
    tooltipEl.style.display = 'block';
    moveTooltip(e);
}

function moveTooltip(e) {
    if (!tooltipEl) return;
    tooltipEl.style.left = (e.clientX + 14) + 'px';
    tooltipEl.style.top = (e.clientY + 14) + 'px';
}

function hideTooltip() {
    if (tooltipEl) tooltipEl.style.display = 'none';
}

// ==================== Ranking Table ====================
function renderRankingTable() {
    var tbody = document.getElementById('industryRankingBody');
    if (!tbody) return;

    if (!rankingData || rankingData.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-4">暂无数据</td></tr>';
        return;
    }

    // Sort data (clone to avoid mutating original)
    var sorted = rankingData.slice().sort(function (a, b) {
        var va = a[sortState.field] || 0;
        var vb = b[sortState.field] || 0;
        return sortState.desc ? vb - va : va - vb;
    });

    var html = '';
    sorted.forEach(function (ind) {
        var pct = ind.pctChg;
        var pctClass = pct != null ? (pct >= 0 ? 'stock-up' : 'stock-down') : '';
        var pctStr = pct != null ? (pct >= 0 ? '+' : '') + Number(pct).toFixed(2) + '%' : '--';
        var amtStr = ind.amount != null ? StockApp.formatNumber(ind.amount, 1) + '亿' : '--';

        // Top gainer
        var gainerHtml = '--';
        if (ind.topGainerName) {
            var gPct = ind.topGainerPctChg;
            var gPctClass = gPct != null ? (gPct >= 0 ? 'stock-up' : 'stock-down') : '';
            var gPctStr = gPct != null ? (gPct >= 0 ? '+' : '') + Number(gPct).toFixed(2) + '%' : '--';
            gainerHtml = '<span class="' + gPctClass + '">' + StockApp.escapeHtml(ind.topGainerName) + '</span>' +
                '<br><small class="text-muted">' + ind.topGainerCode + ' ' + gPctStr + '</small>';
        }
        // Top loser
        var loserHtml = '--';
        if (ind.topLoserName) {
            var lPct = ind.topLoserPctChg;
            var lPctClass = lPct != null ? (lPct >= 0 ? 'stock-up' : 'stock-down') : '';
            var lPctStr = lPct != null ? (lPct >= 0 ? '+' : '') + Number(lPct).toFixed(2) + '%' : '--';
            loserHtml = '<span class="' + lPctClass + '">' + StockApp.escapeHtml(ind.topLoserName) + '</span>' +
                '<br><small class="text-muted">' + ind.topLoserCode + ' ' + lPctStr + '</small>';
        }

        html += '<tr data-industry-code="' + ind.industryCode + '" onclick="SectorPage.selectByCode(\'' + ind.industryCode + '\')">' +
            '<td>' + StockApp.escapeHtml(ind.industryName) + '</td>' +
            '<td class="text-end ' + pctClass + '">' + pctStr + '</td>' +
            '<td class="text-end">' + amtStr + '</td>' +
            '<td>' + gainerHtml + '</td>' +
            '<td>' + loserHtml + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;
}

// ==================== Select Industry ====================
function selectIndustry(ind) {
    currentIndustry = ind;

    // Highlight row in table
    document.querySelectorAll('#industryRankingBody tr').forEach(function (tr) {
        var code = tr.getAttribute('data-industry-code');
        if (code === ind.industryCode) {
            tr.classList.add('active-row');
        } else {
            tr.classList.remove('active-row');
        }
    });

    // Update K-line title
    var titleEl = document.getElementById('klineTitle');
    if (titleEl) titleEl.textContent = ind.industryName + ' 行业指数K线';

    // Update member title
    var memberTitleEl = document.getElementById('memberTitle');
    if (memberTitleEl) memberTitleEl.textContent = ind.industryName + ' 成分股';

    // Load K-line
    loadKline(ind.indexCode);

    // Load members (reset search & page)
    memberPage = 1;
    memberKeyword = '';
    var searchInput = document.getElementById('memberSearch');
    if (searchInput) searchInput.value = '';
    loadMembers(ind.industryCode);
}

// Public API for onclick handlers
window.SectorPage = {
    selectByCode: function (code) {
        var ind = null;
        for (var i = 0; i < rankingData.length; i++) {
            if (rankingData[i].industryCode === code) { ind = rankingData[i]; break; }
        }
        if (ind) selectIndustry(ind);
    },
    gotoStock: function (code) {
        window.location.href = (StockApp.contextPath || '') + '/page/stock-list?keyword=' + encodeURIComponent(code);
    }
};

// ==================== Load Members ====================
function loadMembers(industryCode) {
    var tbody = document.getElementById('memberTableBody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4">加载中...</td></tr>';

    var params = { industryCode: industryCode, page: memberPage, size: memberSize };
    if (memberKeyword) params.keyword = memberKeyword;

    StockApp.get('/api/industry/members', params, function (resp) {
        if (resp.code === 200 && resp.data) {
            renderMembers(resp.data);
        } else {
            if (tbody) tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4">加载失败</td></tr>';
        }
    });
}

function renderMembers(pageData) {
    var tbody = document.getElementById('memberTableBody');
    if (!tbody) return;

    var list = pageData.list || [];
    if (list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4">暂无成分股</td></tr>';
        var pagination = document.getElementById('memberPagination');
        if (pagination) pagination.style.setProperty('display', 'none', 'important');
        return;
    }

    var html = '';
    list.forEach(function (m) {
        var pctClass = m.pctChg != null ? (m.pctChg >= 0 ? 'stock-up' : 'stock-down') : '';
        var pctStr = m.pctChg != null ? (m.pctChg >= 0 ? '+' : '') + Number(m.pctChg).toFixed(2) + '%' : '--';
        var closeStr = m.close != null ? Number(m.close).toFixed(2) : '--';
        var volStr = m.vol != null ? StockApp.formatVolume(m.vol) : '--';
        var amtStr = m.amount != null ? StockApp.formatVolume(m.amount) : '--';

        html += '<tr>' +
            '<td><a href="javascript:void(0)" class="text-decoration-none" onclick="SectorPage.gotoStock(\'' + m.tsCode + '\')">' + m.tsCode + '</a></td>' +
            '<td>' + StockApp.escapeHtml(m.name || '') + '</td>' +
            '<td class="text-end">' + closeStr + '</td>' +
            '<td class="text-end ' + pctClass + '">' + pctStr + '</td>' +
            '<td class="text-end">' + volStr + '</td>' +
            '<td class="text-end">' + amtStr + '</td>' +
            '<td>' + StockApp.escapeHtml(m.market || '') + '</td>' +
            '</tr>';
    });
    tbody.innerHTML = html;

    // Pagination
    var pagination = document.getElementById('memberPagination');
    if (pagination) {
        pagination.style.setProperty('display', 'flex', 'important');
        var info = document.getElementById('memberPageInfo');
        var totalPages = Math.ceil(pageData.total / pageData.size);
        if (info) info.textContent = '共 ' + pageData.total + ' 条 · 第 ' + pageData.page + '/' + totalPages + ' 页';
    }
}

// ==================== K-line Chart ====================
function loadKline(indexCode) {
    var emptyEl = document.getElementById('klineEmpty');
    var chartEl = document.getElementById('klineChart');

    StockApp.get('/api/index-daily', { tsCode: indexCode, limit: currentRange }, function (resp) {
        if (resp.code === 200 && resp.data && resp.data.length > 0) {
            if (emptyEl) emptyEl.style.display = 'none';
            if (chartEl) chartEl.style.display = 'block';
            renderKline(resp.data, indexCode);
        } else {
            if (chartEl) chartEl.style.display = 'none';
            if (emptyEl) emptyEl.style.display = 'block';
        }
    });
}

function renderKline(data, tsCode) {
    var container = document.getElementById('klineChart');
    var theme = ChartsTheme.getKlineTheme();
    klineDataCache = data;

    // Map IndexDailyDO to chart data format
    // IndexDailyDO: {tradeDate:"20240115", open, high, low, close, vol, amount}
    var candleData = data.map(function (d) {
        return {
            time: fmtDate(d.tradeDate),
            open: d.open,
            high: d.high,
            low: d.low,
            close: d.close,
        };
    });
    var volumeData = data.map(function (d) {
        return {
            time: fmtDate(d.tradeDate),
            value: d.vol,
            color: d.close >= d.open ? theme.volumeColor.up : theme.volumeColor.down,
        };
    });

    function calcMA(dayCount) {
        var result = [];
        for (var i = dayCount - 1; i < data.length; i++) {
            var sum = 0;
            for (var j = 0; j < dayCount; j++) sum += data[i - j].close;
            result.push({ time: fmtDate(data[i].tradeDate), value: parseFloat((sum / dayCount).toFixed(2)) });
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
                    dateFormat: function (date) {
                        if (typeof date === 'string') return date;
                        if (typeof date === 'object' && date.year != null)
                            return date.year + '-' + String(date.month).padStart(2, '0') + '-' + String(date.day).padStart(2, '0');
                        return String(date);
                    },
                },
            },
        });
        ChartsTheme.register(klineChart, 'lightweight');

        // Legend element for crosshair info
        var legendEl = document.createElement('div');
        legendEl.style.cssText = 'position:absolute;top:8px;left:12px;z-index:1;font-size:12px;line-height:1.6;pointer-events:none;';
        container.appendChild(legendEl);

        candleSeries = klineChart.addCandlestickSeries(theme.candlestick);
        volumeSeries = klineChart.addHistogramSeries({
            priceFormat: { type: 'volume' },
            priceScaleId: 'volume',
        });
        klineChart.priceScale('volume').applyOptions({
            scaleMargins: { top: 0.8, bottom: 0 },
        });

        // MA series
        maSeries[5] = klineChart.addLineSeries({ color: theme.maColors.ma5, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        maSeries[10] = klineChart.addLineSeries({ color: theme.maColors.ma10, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        maSeries[20] = klineChart.addLineSeries({ color: theme.maColors.ma20, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        maSeries[60] = klineChart.addLineSeries({ color: theme.maColors.ma60 || '#9c27b0', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });

        // Crosshair move handler
        klineChart.subscribeCrosshairMove(function (param) {
            if (!param || !param.time || !param.seriesData) {
                legendEl.innerHTML = '';
                return;
            }
            var candle = param.seriesData.get(candleSeries);
            var vol = param.seriesData.get(volumeSeries);
            if (!candle) { legendEl.innerHTML = ''; return; }

            // Re-read theme to stay in sync after theme switch
            var t = ChartsTheme.getKlineTheme();
            var clr = candle.close >= candle.open ? t.candlestick.upColor : t.candlestick.downColor;
            var labelClr = t.legendLabelColor;

            var m5 = param.seriesData.get(maSeries[5]);
            var m10 = param.seriesData.get(maSeries[10]);
            var m20 = param.seriesData.get(maSeries[20]);
            var m60 = param.seriesData.get(maSeries[60]);

            legendEl.innerHTML =
                '<span style="color:' + labelClr + '">' + fmtTime(param.time) + '</span> ' +
                '开 <span style="color:' + clr + '">' + candle.open.toFixed(2) + '</span> ' +
                '高 <span style="color:' + clr + '">' + candle.high.toFixed(2) + '</span> ' +
                '低 <span style="color:' + clr + '">' + candle.low.toFixed(2) + '</span> ' +
                '收 <span style="color:' + clr + '">' + candle.close.toFixed(2) + '</span>' +
                (vol ? ' 量 <span style="color:' + labelClr + '">' + StockApp.formatVolume(vol.value) + '</span>' : '') +
                '<br>' +
                '<span style="color:' + t.maColors.ma5 + '">MA5: ' + (m5 ? m5.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + t.maColors.ma10 + '">MA10: ' + (m10 ? m10.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + t.maColors.ma20 + '">MA20: ' + (m20 ? m20.value.toFixed(2) : '-') + '</span> ' +
                '<span style="color:' + (t.maColors.ma60 || '#9c27b0') + '">MA60: ' + (m60 ? m60.value.toFixed(2) : '-') + '</span>';
        });

        new ResizeObserver(function () {
            klineChart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        }).observe(container);
    }

    // Set data
    candleSeries.setData(candleData);
    volumeSeries.setData(volumeData);
    maSeries[5].setData(calcMA(5));
    maSeries[10].setData(calcMA(10));
    maSeries[20].setData(calcMA(20));
    maSeries[60].setData(calcMA(60));

    // Apply MA visibility
    applyMaVisibility();

    // Set visible range to show last 120 bars by default
    var showCount = Math.min(120, candleData.length);
    var startIdx = Math.max(0, candleData.length - showCount);
    if (candleData.length > 0) {
        klineChart.timeScale().setVisibleRange({
            from: candleData[startIdx].time,
            to: candleData[candleData.length - 1].time,
        });
    }
}

function applyMaVisibility() {
    for (var ma in maSeries) {
        if (maSeries[ma]) {
            maSeries[ma].applyOptions({ visible: maVisible[ma] });
        }
    }
}

// ==================== Error Handling ====================
function showError() {
    var tbody = document.getElementById('industryRankingBody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-4">数据加载失败</td></tr>';
    var errorRetry = document.getElementById('errorRetry');
    if (errorRetry) errorRetry.style.display = 'block';
}

})();
