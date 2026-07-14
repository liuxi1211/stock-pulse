/**
 * 回测对比页逻辑（spec 007 T4.5）。
 *
 * 数据源：
 *   - GET /api/backtest/compare?ids=1,2,3 → BacktestCompareVO
 *       {curves: [{backtestId, strategyName, versionNo, dates, values, color}...],
 *        benchmarkCurve: {dates, values}|null,
 *        metricsTable: [{metricKey, metricLabel, values: [{backtestId, value}], ...}],
 *        radarData: {indicators: [...], series: [{backtestId, name, values: [...]}]},
 *        items: [{backtestId, strategyName, versionNo, startDate, endDate, totalReturnPct, ...}]}
 *
 * 若 URL 无 ids，从 localStorage('bt_compare_ids') 读取。
 */
(function () {
    'use strict';

    const Backtest = window.Backtest || (window.Backtest = {});
    const e = StockApp.escapeHtml;

    const PALETTE = ['#3b82f6', '#8b5cf6', '#ec4899', '#06b6d4', '#f97316', '#22c55e', '#fbbf24', '#a78bfa'];

    const state = {
        ids: [],
        data: null,
        charts: {}
    };

    // ============ 解析 ids ============
    function parseIds() {
        const q = new URLSearchParams(window.location.search);
        const raw = q.get('ids');
        if (raw) {
            return raw.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
        }
        try {
            const ls = JSON.parse(localStorage.getItem('bt_compare_ids') || '[]');
            if (Array.isArray(ls) && ls.length > 0) return ls.map(String);
        } catch (err) {}
        return [];
    }

    // ============ 加载 ============
    function load() {
        state.ids = parseIds();
        if (state.ids.length === 0) {
            document.getElementById('emptyState').style.display = '';
            document.getElementById('compareContent').style.display = 'none';
            return;
        }

        StockApp.get('/api/backtest/compare', { ids: state.ids.join(',') }, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.data = resp.data;
                renderAll();
            } else {
                StockApp.toast(resp.message || '对比数据加载失败', 'danger');
                document.getElementById('emptyState').style.display = '';
                document.getElementById('compareContent').style.display = 'none';
            }
        });
    }

    // ============ 渲染全部 ============
    function renderAll() {
        document.getElementById('emptyState').style.display = 'none';
        document.getElementById('compareContent').style.display = '';
        renderChips();
        renderConsistency();
        renderOverlay();
        renderMetricsTable();
        renderRadar();
    }

    // ============ chip 列表 ============
    function renderChips() {
        const items = state.data.items || [];
        const curves = state.data.curves || [];
        const box = document.getElementById('compareChips');
        const colorMap = {};
        curves.forEach(function (c, i) { colorMap[String(c.backtestId)] = c.color || PALETTE[i % PALETTE.length]; });

        let html = items.map(function (it) {
            const color = colorMap[String(it.backtestId)] || PALETTE[0];
            const ret = it.totalReturnPct;
            return ''
                + '<div class="bt-compare-strat-chip">'
                + '  <span class="swatch" style="background:' + color + ';"></span>'
                + '  <div>'
                + '    <div class="name">' + e(it.strategyName || '?') + ' v' + e(it.versionNo || '?') + ' <span style="color:var(--text-muted);font-size:10px;">· BT-' + e(it.backtestId) + ' ' + e(shortRange(it.startDate, it.endDate)) + '</span></div>'
                + '    <div class="sub">' + e(it.startDate) + ' → ' + e(it.endDate) + ' · ' + formatPct(ret) + '</div>'
                + '  </div>'
                + '  <span class="x" data-id="' + e(it.backtestId) + '" title="移除">✕</span>'
                + '</div>';
        }).join('');

        if (state.data.benchmarkCurve) {
            const bmName = state.data.benchmarkName || '基准';
            const bmRet = state.data.benchmarkReturnPct;
            html += '<div class="bt-compare-strat-chip benchmark">'
                + '<span class="swatch"></span>'
                + '<div>'
                + '  <div class="name">' + e(bmName) + '</div>'
                + '  <div class="sub">' + (bmRet != null ? formatPct(bmRet) : '归一化') + '</div>'
                + '</div>'
                + '</div>';
        }
        box.innerHTML = html;

        box.querySelectorAll('.x').forEach(function (x) {
            x.addEventListener('click', function () {
                const id = x.getAttribute('data-id');
                const remaining = state.ids.filter(function (i) { return String(i) !== String(id); });
                if (remaining.length === 0) {
                    window.location.href = StockApp.contextPath + '/quant/backtests';
                } else {
                    window.location.href = StockApp.contextPath + '/quant/backtests/compare?ids=' + remaining.join(',');
                }
            });
        });
    }

    // ============ 区间一致性提示 ============
    function renderConsistency() {
        const items = state.data.items || [];
        const tip = document.getElementById('consistencyTip');
        if (items.length === 0) { tip.style.display = 'none'; return; }
        const ranges = items.map(function (it) { return (it.startDate || '') + '_' + (it.endDate || ''); });
        const unique = Array.from(new Set(ranges));
        if (unique.length === 1) {
            const it = items[0];
            tip.className = 'bt-consistency-tip ok';
            tip.innerHTML = '<i class="bi bi-check-circle" style="color:var(--fall-light);"></i><span>' + items.length + ' 个回测区间一致（' + e(it.startDate) + ' → ' + e(it.endDate) + '），可比性良好。区间不一致的回测对比会产生误导。</span>';
        } else {
            tip.className = 'bt-consistency-tip warn';
            tip.innerHTML = '<i class="bi bi-exclamation-triangle" style="color:var(--accent-yellow);"></i><span>' + items.length + ' 个回测区间不完全一致，净值叠加与指标对比可能产生误导，请关注每个回测的实际区间。</span>';
        }
    }

    // ============ 归一化净值叠加 ============
    function renderOverlay() {
        const el = document.getElementById('overlayChart');
        const legend = document.getElementById('overlayLegend');
        if (!el || typeof echarts === 'undefined') return;
        const curves = state.data.curves || [];
        if (curves.length === 0) {
            el.innerHTML = '<div class="bt-empty-state"><i class="bi bi-inboxes"></i><div class="bt-empty-state-sub">无曲线数据</div></div>';
            return;
        }

        let xAxis = curves[0].dates || [];
        let maxLen = xAxis.length;
        curves.forEach(function (c) {
            if (c.dates && c.dates.length > maxLen) { maxLen = c.dates.length; xAxis = c.dates; }
        });

        const series = curves.map(function (c, i) {
            const color = c.color || PALETTE[i % PALETTE.length];
            const vals = c.values || [];
            const initial = vals[0] || 1;
            return {
                name: (c.strategyName || 'BT-' + c.backtestId) + ' v' + (c.versionNo || ''),
                type: 'line',
                data: vals.map(function (v) { return initial > 0 ? v / initial : 1; }),
                smooth: true,
                symbol: 'none',
                lineStyle: { width: 2.5, color: color }
            };
        });

        const bm = state.data.benchmarkCurve;
        if (bm && bm.values && bm.values.length > 0) {
            series.push({
                name: state.data.benchmarkName || '基准',
                type: 'line',
                data: bm.values,
                smooth: true,
                symbol: 'none',
                lineStyle: { width: 1.5, color: '#fbbf24', type: 'dashed' }
            });
        }

        legend.innerHTML = curves.map(function (c, i) {
            const color = c.color || PALETTE[i % PALETTE.length];
            return '<span class="bt-chart-legend-item"><span class="swatch" style="background:' + color + ';"></span>' + e(c.strategyName || 'BT-' + c.backtestId) + '</span>';
        }).join('') + (bm ? '<span class="bt-chart-legend-item"><span class="swatch" style="background:#fbbf24;"></span>' + e(state.data.benchmarkName || '基准') + '</span>' : '');

        if (state.charts.overlay) state.charts.overlay.dispose();
        state.charts.overlay = echarts.init(el);
        state.charts.overlay.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
            grid: { left: 50, right: 30, top: 20, bottom: 40 },
            xAxis: {
                type: 'category',
                data: xAxis,
                axisLabel: { color: getCssVar('--chart-axis'), fontSize: 10 },
                axisLine: { lineStyle: { color: getCssVar('--chart-grid') } }
            },
            yAxis: {
                type: 'value',
                scale: true,
                axisLabel: { color: getCssVar('--chart-axis'), fontSize: 10, formatter: function (v) { return v.toFixed(2); } },
                splitLine: { lineStyle: { color: getCssVar('--chart-split-line'), type: 'dashed' } }
            },
            series: series
        });
    }

    // ============ 指标对比表 ============
    function renderMetricsTable() {
        const head = document.getElementById('metricsHead');
        const body = document.getElementById('metricsBody');
        const items = state.data.items || [];
        const table = state.data.metricsTable || [];

        if (table.length === 0) {
            head.innerHTML = '<th>指标</th>';
            body.innerHTML = '<tr><td colspan="' + (items.length + 2) + '" class="center" style="padding:30px;color:var(--text-muted);">无指标数据</td></tr>';
            return;
        }

        const colorMap = {};
        const curves = state.data.curves || [];
        curves.forEach(function (c, i) { colorMap[String(c.backtestId)] = c.color || PALETTE[i % PALETTE.length]; });

        let headHtml = '<th class="row-label" style="text-align:left;">指标</th>';
        items.forEach(function (it) {
            const color = colorMap[String(it.backtestId)] || PALETTE[0];
            headHtml += '<th><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:' + color + ';margin-right:4px;vertical-align:middle;"></span>' + e(it.strategyName || '?') + ' v' + e(it.versionNo || '?') + '<br><span style="font-size:9px;color:var(--text-muted);font-weight:400;">BT-' + e(it.backtestId) + ' ' + e(shortRange(it.startDate, it.endDate)) + '</span></th>';
        });
        if (state.data.benchmarkCurve) headHtml += '<th>' + e(state.data.benchmarkName || '基准') + '</th>';
        head.innerHTML = headHtml;

        body.innerHTML = table.map(function (row) {
            const key = row.metricKey || row.key;
            const label = row.metricLabel || row.label || key;
            const higherBetter = isHigherBetter(key);
            const values = row.values || [];
            const numericVals = values.map(function (v) { return v && v.value != null && !isNaN(v.value) ? Number(v.value) : null; });

            let bestIdx = -1, worstIdx = -1;
            const validIdxs = numericVals.map(function (v, i) { return v != null ? i : -1; }).filter(function (i) { return i >= 0; });
            if (validIdxs.length >= 2) {
                let bv = numericVals[validIdxs[0]], wv = numericVals[validIdxs[0]];
                bestIdx = validIdxs[0]; worstIdx = validIdxs[0];
                validIdxs.forEach(function (i) {
                    const v = numericVals[i];
                    if (higherBetter ? v > bv : v < bv) { bv = v; bestIdx = i; }
                    if (higherBetter ? v < wv : v > wv) { wv = v; worstIdx = i; }
                });
            }

            let rowHtml = '<tr><td class="row-label">' + e(label) + '</td>';
            values.forEach(function (v, i) {
                const formatted = formatMetric(key, v && v.value);
                let cls = '';
                if (i === bestIdx && bestIdx !== worstIdx) cls = 'best';
                else if (i === worstIdx && bestIdx !== worstIdx) cls = 'worst';
                rowHtml += '<td class="num ' + cls + '">' + formatted + '</td>';
            });
            if (state.data.benchmarkCurve) {
                const bmVal = row.benchmarkValue != null ? row.benchmarkValue : (key === 'totalReturnPct' && state.data.benchmarkReturnPct != null ? state.data.benchmarkReturnPct : null);
                rowHtml += '<td class="num" style="color:var(--text-muted);">' + (bmVal != null ? formatMetric(key, bmVal) : '—') + '</td>';
            }
            rowHtml += '</tr>';
            return rowHtml;
        }).join('');
    }

    function isHigherBetter(key) {
        if (key === 'maxDrawdownPct' || key === 'volatility') return false;
        return true;
    }

    function formatMetric(key, v) {
        if (v == null || v === '' || isNaN(v)) return '—';
        const n = Number(v);
        if (key === 'totalReturnPct' || key === 'annualizedReturn' || key === 'excessReturnPct' || key === 'maxDrawdownPct' || key === 'volatility' || key === 'winRate') {
            const sign = (key !== 'maxDrawdownPct' && key !== 'volatility') && n > 0 ? '+' : '';
            return sign + n.toFixed(2) + '%';
        }
        if (key === 'tradeCount' || key === 'transactionCount') return String(Math.round(n));
        return n.toFixed(2);
    }

    // ============ 雷达图 ============
    function renderRadar() {
        const el = document.getElementById('radarChart');
        const legend = document.getElementById('radarLegend');
        if (!el || typeof echarts === 'undefined') return;
        const radar = state.data.radarData;
        if (!radar || !radar.indicators || radar.indicators.length === 0) {
            el.innerHTML = '<div class="bt-empty-state"><i class="bi bi-bullseye"></i><div class="bt-empty-state-sub">无雷达数据</div></div>';
            return;
        }

        const indicators = radar.indicators.map(function (ind) {
            return { name: ind.label || ind.name, max: ind.max != null ? ind.max : 100 };
        });

        const series = (radar.series || []).map(function (s, i) {
            const color = PALETTE[i % PALETTE.length];
            return {
                name: s.name || ('BT-' + s.backtestId),
                type: 'radar',
                data: [{ value: s.values || [], name: s.name || ('BT-' + s.backtestId) }],
                lineStyle: { color: color, width: 2 },
                itemStyle: { color: color },
                areaStyle: { color: color, opacity: 0.15 }
            };
        });

        legend.innerHTML = (radar.series || []).map(function (s, i) {
            const color = PALETTE[i % PALETTE.length];
            return '<span class="bt-chart-legend-item"><span class="dot-sw" style="background:' + color + ';"></span>' + e(s.name || ('BT-' + s.backtestId)) + '</span>';
        }).join('');

        if (state.charts.radar) state.charts.radar.dispose();
        state.charts.radar = echarts.init(el);
        state.charts.radar.setOption({
            backgroundColor: 'transparent',
            tooltip: {},
            radar: {
                indicator: indicators,
                shape: 'polygon',
                splitNumber: 5,
                axisName: { color: getCssVar('--chart-text'), fontSize: 11 },
                splitLine: { lineStyle: { color: getCssVar('--chart-split-line') } },
                splitArea: { areaStyle: { color: ['transparent', 'transparent'] } },
                axisLine: { lineStyle: { color: getCssVar('--chart-grid') } }
            },
            series: series
        });
    }

    // ============ 工具 ============
    function getCssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }
    function formatPct(v) {
        if (v == null || isNaN(v)) return '—';
        const n = Number(v);
        const sign = n > 0 ? '+' : '';
        return sign + n.toFixed(2) + '%';
    }
    function shortRange(start, end) {
        if (!start || !end) return '';
        try {
            const days = Math.round((new Date(end) - new Date(start)) / 86400000);
            if (days >= 700) return '全历史';
            if (days >= 330) return '近1年';
            if (days >= 170) return '近6月';
            return Math.round(days / 30) + '月';
        } catch (err) { return ''; }
    }

    // ============ 入口 ============
    Backtest.initCompare = function () {
        window.addEventListener('resize', function () {
            Object.keys(state.charts).forEach(function (k) { if (state.charts[k]) state.charts[k].resize(); });
        });
        load();
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (document.getElementById('backtestComparePage')) Backtest.initCompare();
    });
})();
