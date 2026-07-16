/**
 * 回测报告页逻辑（spec 007 T4.4）。
 *
 * 数据源：
 *   - GET /api/backtest/{backtestId}            基本信息 BacktestTaskVO
 *   - GET /api/backtest/{backtestId}/report     BacktestReportVO
 *       {metrics, equityCurve{dates,values}, benchmarkCurve{dates,values}|null,
 *        dailyReturns[], trades[], orders[], positions[]}
 *   - GET /api/backtest/tasks?strategyId=&size=50  同策略其他区间（用于报告间导航）
 *   - POST /api/backtest/tasks/{taskId}/rerun   重跑
 *
 * 单位规则：
 *   - totalReturnPct / maxDrawdownPct：原始百分数（15.0 = 15%），直接 value + '%' 显示
 *   - maxDrawdownPct 正数展示（8.40% 不加负号）
 *   - equityCurve.values 绝对金额；benchmarkCurve.values 归一化净值（1.0 起）
 *   - 超额收益 = totalReturnPct - 基准同期收益（后端 report.metrics.excessReturnPct 已算）
 */
(function () {
    'use strict';

    const Backtest = window.Backtest || (window.Backtest = {});
    const e = StockApp.escapeHtml;

    const state = {
        backtestId: null,
        basic: null,
        report: null,
        siblingTasks: [],
        charts: {}
    };

    // ============ 解析 backtestId ============
    function parseBacktestId() {
        const m = window.location.pathname.match(/\/quant\/backtests\/(\d+)\/report/);
        if (m) return m[1];
        const q = new URLSearchParams(window.location.search);
        return q.get('id') || q.get('backtestId');
    }

    // ============ 加载基本 + 报告 ============
    function loadAll() {
        state.backtestId = parseBacktestId();
        if (!state.backtestId) {
            StockApp.toast('缺少 backtestId', 'danger');
            return;
        }

        StockApp.get('/api/backtest/' + state.backtestId, null, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.basic = resp.data;
                renderHeader();
                loadSiblings();
            }
        });

        StockApp.get('/api/backtest/' + state.backtestId + '/report', null, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.report = resp.data;
                renderMetrics();
                renderEquityChart();
                renderDrawdownChart();
                renderHeatmap();
                renderTrades();
                renderPositions();
                renderDiagnosis();
            } else {
                document.getElementById('metricGrid').innerHTML = '<div class="bt-empty-state"><i class="bi bi-exclamation-triangle"></i><div class="bt-empty-state-title">报告加载失败</div><div class="bt-empty-state-sub">' + e(resp.message || '') + '</div></div>';
            }
        });
    }

    function loadSiblings() {
        if (!state.basic || state.basic.strategyId == null) return;
        StockApp.get('/api/backtest/tasks', { strategyId: state.basic.strategyId, page: 1, size: 50 }, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.siblingTasks = (resp.data.list || resp.data.records || []).filter(function (t) {
                    return t.status === 'SUCCESS' && String(t.backtestId || t.id) !== String(state.backtestId);
                });
                renderSiblingNav();
            }
        });
    }

    // ============ 渲染页头 ============
    function renderHeader() {
        const b = state.basic;
        const stratName = b.strategyName || ('S-' + b.strategyId);
        const ver = b.versionNo || '?';
        const status = b.status || 'SUCCESS';
        const statusCls = 'bt-' + status.toLowerCase();
        const start = b.startDate || (b.config && b.config.startDate) || '';
        const end = b.endDate || (b.config && b.config.endDate) || '';
        const benchmark = b.benchmark || (b.config && b.config.benchmark) || '沪深300';
        const cash = b.initialCash || (b.config && b.config.initialCash) || 100000;

        document.getElementById('reportTitle').innerHTML = ''
            + '<span>' + e(stratName) + '</span>'
            + '<span style="font-size:14px;color:var(--text-muted);font-weight:400;">v' + e(ver) + '</span>'
            + '<span class="mono" style="font-size:12px;color:var(--text-muted);font-weight:400;margin-left:6px;">S-' + e(b.strategyId != null ? b.strategyId : '?') + ' · BT-' + e(state.backtestId) + '</span>'
            + '<span class="bt-status ' + statusCls + '" style="margin-left:8px;">' + e(status) + '</span>';
        document.getElementById('reportSubtitle').innerHTML = ''
            + '<span class="mono" style="color:var(--text-secondary);margin-right:8px;">' + e(start) + ' → ' + e(end) + '</span>'
            + '· 基准 <span class="mono" style="color:var(--accent-yellow);">' + e(benchmark) + '</span>'
            + '· 初始资金 <span class="mono">¥' + e(formatNumber(cash, 0)) + '</span>';
        document.title = 'BT-' + state.backtestId + ' 报告 · ' + stratName;

        document.getElementById('crumbStrategy').textContent = stratName + ' v' + ver;
        document.getElementById('crumbCurrent').textContent = 'BT-' + state.backtestId + ' 报告';
    }

    // ============ 渲染同策略其他区间 ============
    function renderSiblingNav() {
        if (state.siblingTasks.length === 0) return;
        document.getElementById('reportNav').style.display = '';
        const html = state.siblingTasks.slice(0, 10).map(function (t) {
            const id = t.backtestId || t.id;
            const ret = t.metrics && t.metrics.totalReturnPct;
            const cls = (ret != null && Number(ret) >= 0) ? 'up' : 'down';
            const start = t.startDate || (t.config && t.config.startDate) || '';
            const end = t.endDate || (t.config && t.config.endDate) || '';
            return '<a class="bt-report-nav-chip ' + cls + '" href="' + StockApp.contextPath + '/quant/backtests/' + id + '/report">' + e(shortRange(start, end)) + ' · ' + formatPct(ret) + '</a>';
        }).join('');
        document.getElementById('reportNavChips').innerHTML = html;
    }

    // ============ 渲染指标卡 ============
    function renderMetrics() {
        const m = state.report.metrics || {};
        const b = state.basic;
        const cash = b.initialCash || (b.config && b.config.initialCash) || 100000;
        const endVal = state.report.equityCurve && state.report.equityCurve.values ? state.report.equityCurve.values.slice(-1)[0] : null;
        const benchmarkName = b.benchmark || (b.config && b.config.benchmark) || '沪深300';
        const benchmarkReturn = m.benchmarkReturnPct != null ? m.benchmarkReturnPct : null;

        const cards = [
            { lbl: '总收益', icon: 'bi-graph-up', accent: 'var(--rise-color)', val: formatPct(m.totalReturnPct), cls: pctClass(m.totalReturnPct), sub: '¥' + formatNumber(cash, 0) + ' → ¥' + formatNumber(endVal, 0) },
            { lbl: '超额收益 vs ' + benchmarkName, icon: 'bi-trophy', accent: 'var(--accent-yellow)', val: formatPct(m.excessReturnPct), cls: pctClass(m.excessReturnPct), sub: benchmarkReturn != null ? '基准同期 ' + formatPct(benchmarkReturn) : '基准归一化对比' },
            { lbl: '年化收益', icon: 'bi-bar-chart', accent: 'var(--accent-blue)', val: formatRatio(m.annualizedReturn, '%'), cls: ratioClass(m.annualizedReturn), sub: '复合年化' },
            { lbl: '夏普比率', icon: 'bi-lightning', accent: 'var(--accent-cyan)', val: formatRatio(m.sharpeRatio), cls: '', sub: 'Sharpe Ratio' },
            { lbl: '索提诺', icon: 'bi-bullseye', accent: 'var(--accent-purple)', val: formatRatio(m.sortinoRatio), cls: '', sub: 'Sortino Ratio' },
            { lbl: '卡玛比率', icon: 'bi-shield', accent: 'var(--accent-pink)', val: formatRatio(m.calmarRatio), cls: '', sub: '年化 / 最大回撤' },
            { lbl: '最大回撤', icon: 'bi-graph-down', accent: 'var(--fall-color)', val: formatPct(m.maxDrawdownPct), cls: 'down', sub: '正数展示' },
            { lbl: '胜率', icon: 'bi-check-circle', accent: 'var(--accent-green)', val: formatRatio(m.winRate, '%'), cls: '', sub: '已平仓交易' },
            { lbl: '盈亏比', icon: 'bi-scales', accent: 'var(--accent-orange)', val: formatRatio(m.profitFactor), cls: '', sub: 'Profit Factor' },
            { lbl: '交易笔数', icon: 'bi-arrow-left-right', accent: 'var(--accent-blue-light)', val: String(m.tradeCount != null ? m.tradeCount : (state.report.trades ? state.report.trades.length : 0)), cls: '', sub: '已完成平仓' },
            { lbl: '年化换手率', icon: 'bi-arrow-repeat', accent: 'var(--accent-cyan-light)', val: formatRatio(m.annual_turnover_ratio != null ? m.annual_turnover_ratio : m.annualTurnoverRatio), cls: '', sub: 'Annual Turnover' }
        ];

        document.getElementById('metricGrid').innerHTML = cards.map(function (c) {
            return ''
                + '<div class="bt-mcard" style="--mc-accent: ' + c.accent + ';">'
                + '  <div class="bt-mcard-lbl"><i class="bi ' + c.icon + '"></i>' + e(c.lbl) + '</div>'
                + '  <div class="bt-mcard-val ' + c.cls + '">' + c.val + '</div>'
                + '  <div class="bt-mcard-sub">' + e(c.sub) + '</div>'
                + '</div>';
        }).join('');
    }

    // ============ spec 013 遗留#2：调仓诊断 + warmup 配置摘要 ============
    // 字段命名：report 顶层 VO 为 camelCase（rebalanceDiagnosis/effectiveConfig），
    //          但内部 Map key 来自 engine serializer（snake_case），故双命名兼容。
    function pick(obj, camel, snake) {
        if (!obj) return undefined;
        return obj[camel] != null ? obj[camel] : obj[snake];
    }

    function renderDiagnosis() {
        const row = document.getElementById('reportDiagnosisRow');
        if (!row) return;
        const r = state.report || {};
        const diag = r.rebalanceDiagnosis || r.rebalance_diagnosis;
        const ec = r.effectiveConfig || r.effective_config;
        let html = '';

        // ---- 调仓诊断卡片（轮动范式才有；择时范式 diag 为空 → 跳过）----
        const selected = diag ? pick(diag, 'selectedCount', 'selected_count') : null;
        if (diag && selected != null) {
            const bought = pick(diag, 'actuallyBought', 'actually_bought') || 0;
            const rejCash = pick(diag, 'rejectedByCash', 'rejected_by_cash') || 0;
            const rejLimit = pick(diag, 'rejectedByLimitUp', 'rejected_by_limit_up') || 0;
            const investRatio = pick(diag, 'actualInvestRatio', 'actual_invest_ratio') || 0;
            const highlight = bought < selected ? 'bt-diag-warn' : '';
            html += '<div class="bt-chart-card ' + highlight + '" style="margin-bottom:0;">'
                + '<div class="bt-chart-head"><div class="bt-chart-title"><i class="bi bi-clipboard-data"></i>调仓诊断</div></div>'
                + '<div class="bt-chart-body" style="padding:12px 16px;">'
                + '<div>实际成交 <b>' + e(String(bought)) + '/' + e(String(selected)) + '</b> 只</div>'
                + '<div class="bt-mcard-sub">资金不足拒单 ' + rejCash + ' 只 · 涨停拒买 ' + rejLimit + ' 只 · 实际仓位 ' + formatRatio(investRatio, '%') + '</div>'
                + (highlight ? '<div class="bt-mcard-sub" style="color:var(--fall-color);">⚠ 实际成交少于选出，请检查资金/流动性</div>' : '')
                + '</div></div>';
        }

        // ---- warmup 配置摘要 ----
        if (ec) {
            const wp = pick(ec, 'warmupPeriod', 'warmup_period');
            const wsrc = pick(ec, 'warmupSource', 'warmup_source') || 'auto_inferred';
            if (wp != null) {
                const srcLabel = (wsrc === 'user_override' || wsrc === 'user-override')
                    ? '（用户设置）'
                    : '（基于因子窗口自动推断）';
                html += '<div class="bt-chart-card" style="margin-top:12px; margin-bottom:0;">'
                    + '<div class="bt-chart-body" style="padding:10px 16px;">'
                    + '<span class="bt-mcard-lbl"><i class="bi bi-info-circle"></i> 系统建议 warmup: <b>' + e(String(wp)) + '</b>' + srcLabel + '</span>'
                    + '</div></div>';
            }
        }

        // ---- spec 013 P2-9：执行诊断（分批调仓 + 冲击成本）----
        // 仅当启用 split_days>1 或 impact_cost_bps 时 engine 才输出；否则 executionDiagnosis 为空 → 跳过。
        const ed = r.executionDiagnosis || r.execution_diagnosis;
        if (ed) {
            const splitDays = pick(ed, 'splitDays', 'split_days');
            const completed = pick(ed, 'splitsCompleted', 'splits_completed');
            const interrupted = pick(ed, 'splitsInterrupted', 'splits_interrupted');
            const totalImpact = pick(ed, 'totalImpactCost', 'total_impact_cost');
            const avgPart = pick(ed, 'avgParticipation', 'avg_participation');
            if (splitDays != null || completed != null || totalImpact != null) {
                const lines = [];
                if (splitDays != null && splitDays > 1) {
                    lines.push('分批 <b>' + e(String(completed != null ? completed : 0)) + '/' + e(String(splitDays)) + '</b> 天执行' +
                        (interrupted ? ' · 打断 ' + interrupted + ' 次' : ''));
                }
                if (totalImpact != null && totalImpact > 0) {
                    lines.push('累计冲击成本 ' + e(String(Number(totalImpact).toFixed(2))) +
                        ' · 平均参与率 ' + formatRatio(avgPart, '%'));
                }
                if (lines.length) {
                    html += '<div class="bt-chart-card" style="margin-top:12px; margin-bottom:0;">'
                        + '<div class="bt-chart-head"><div class="bt-chart-title"><i class="bi bi-lightning-charge"></i>执行诊断</div></div>'
                        + '<div class="bt-chart-body" style="padding:12px 16px;">'
                        + lines.map(function (l) { return '<div class="bt-mcard-sub">' + l + '</div>'; }).join('')
                        + '</div></div>';
                }
            }
        }

        if (html) {
            row.innerHTML = html;
            row.style.display = '';
        } else {
            row.style.display = 'none';
        }
    }

    // ============ 净值 vs 基准 ============
    function renderEquityChart() {
        const el = document.getElementById('equityChart');
        if (!el || typeof echarts === 'undefined') return;
        const eq = state.report.equityCurve || {};
        const bm = state.report.benchmarkCurve;
        const dates = eq.dates || [];
        const values = eq.values || [];

        if (values.length === 0) {
            el.innerHTML = '<div class="bt-empty-state"><i class="bi bi-inboxes"></i><div class="bt-empty-state-sub">无权益曲线数据</div></div>';
            return;
        }

        const initial = values[0] || 1;
        const normalized = values.map(function (v) { return v / initial; });

        const series = [{
            name: '策略净值',
            type: 'line',
            data: normalized,
            smooth: true,
            symbol: 'none',
            lineStyle: { width: 2.5, color: getCssVar('--accent-blue') },
            areaStyle: {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: 'rgba(59,130,246,0.25)' },
                    { offset: 1, color: 'rgba(59,130,246,0.02)' }
                ])
            }
        }];

        const legend = document.getElementById('equityLegend');
        if (bm && bm.values && bm.values.length > 0) {
            series.push({
                name: '基准归一化',
                type: 'line',
                data: bm.values,
                smooth: true,
                symbol: 'none',
                lineStyle: { width: 1.5, color: getCssVar('--accent-yellow'), type: 'dashed' }
            });
        } else if (legend) {
            legend.innerHTML += '<span class="bt-chart-legend-item" style="color:var(--text-muted);">无基准数据</span>';
        }

        if (state.charts.equity) state.charts.equity.dispose();
        state.charts.equity = echarts.init(el);
        state.charts.equity.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
            legend: { show: false },
            grid: { left: 50, right: 30, top: 20, bottom: 40 },
            xAxis: {
                type: 'category',
                data: dates,
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

    // ============ 回撤曲线 ============
    function renderDrawdownChart() {
        const el = document.getElementById('drawdownChart');
        if (!el || typeof echarts === 'undefined') return;
        const daily = state.report.dailyReturns || [];
        const eq = state.report.equityCurve || {};
        const dates = eq.dates || [];
        const values = eq.values || [];
        if (values.length === 0) return;

        const dd = [];
        let peak = values[0];
        for (let i = 0; i < values.length; i++) {
            if (values[i] > peak) peak = values[i];
            const r = peak > 0 ? (values[i] - peak) / peak : 0;
            dd.push(Number((r * 100).toFixed(3)));
        }

        if (state.charts.dd) state.charts.dd.dispose();
        state.charts.dd = echarts.init(el);
        state.charts.dd.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis', formatter: function (p) { return p[0].axisValue + '<br/>回撤: ' + p[0].data + '%'; } },
            grid: { left: 50, right: 20, top: 20, bottom: 30 },
            xAxis: {
                type: 'category',
                data: dates,
                axisLabel: { color: getCssVar('--chart-axis'), fontSize: 10 },
                axisLine: { lineStyle: { color: getCssVar('--chart-grid') } }
            },
            yAxis: {
                type: 'value',
                axisLabel: { color: getCssVar('--chart-axis'), fontSize: 10, formatter: '{value}%' },
                splitLine: { lineStyle: { color: getCssVar('--chart-split-line'), type: 'dashed' } }
            },
            series: [{
                name: '回撤',
                type: 'line',
                data: dd,
                symbol: 'none',
                lineStyle: { width: 1.5, color: getCssVar('--rise-color') },
                areaStyle: {
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: 'rgba(239,68,68,0.02)' },
                        { offset: 1, color: 'rgba(239,68,68,0.35)' }
                    ])
                }
            }]
        });
    }

    // ============ 月度收益热力 ============
    function renderHeatmap() {
        const el = document.getElementById('heatmap');
        if (!el) return;
        const daily = state.report.dailyReturns || [];
        const eq = state.report.equityCurve || {};
        const dates = eq.dates || [];
        const values = eq.values || [];
        if (dates.length === 0 || values.length === 0) { el.innerHTML = '<div style="grid-column:1/-1;text-align:center;color:var(--text-muted);padding:20px;">无数据</div>'; return; }

        const monthly = {};
        for (let i = 0; i < values.length; i++) {
            const d = new Date(dates[i]);
            if (isNaN(d.getTime())) continue;
            const key = d.getFullYear() + '-' + (d.getMonth() + 1);
            if (!monthly[key]) monthly[key] = values[i];
            else monthly[key] = values[i];
        }
        const keys = Object.keys(monthly).sort();
        if (keys.length < 2) { el.innerHTML = '<div style="grid-column:1/-1;text-align:center;color:var(--text-muted);padding:20px;">数据不足以计算月度收益</div>'; return; }

        const monthReturn = {};
        for (let i = 1; i < keys.length; i++) {
            const prev = monthly[keys[i - 1]];
            const cur = monthly[keys[i]];
            monthReturn[keys[i]] = prev > 0 ? ((cur - prev) / prev) * 100 : 0;
        }

        const years = [];
        keys.forEach(function (k) { const y = parseInt(k.split('-')[0], 10); if (years.indexOf(y) < 0) years.push(y); });
        years.sort();

        let html = '<div class="bt-heatmap-cell head"></div>';
        for (let mo = 1; mo <= 12; mo++) html += '<div class="bt-heatmap-cell head">' + mo + '</div>';
        years.forEach(function (y) {
            html += '<div class="bt-heatmap-cell year">' + y + '</div>';
            for (let mo = 1; mo <= 12; mo++) {
                const k = y + '-' + mo;
                if (monthReturn[k] != null) {
                    const v = monthReturn[k];
                    const isUp = v >= 0;
                    const intensity = Math.min(Math.abs(v) / 5, 0.7) + 0.15;
                    const color = isUp
                        ? 'rgba(239,68,68,' + intensity + ')'
                        : 'rgba(34,197,94,' + intensity + ')';
                    html += '<div class="bt-heatmap-cell" style="background:' + color + ';" title="' + k + ': ' + v.toFixed(2) + '%">' + (v > 0 ? '+' : '') + v.toFixed(1) + '</div>';
                } else {
                    html += '<div class="bt-heatmap-cell empty">—</div>';
                }
            }
        });
        el.innerHTML = html;
    }

    // ============ 交易明细 ============
    function renderTrades() {
        const body = document.getElementById('tradesBody');
        const trades = state.report.trades || [];
        document.getElementById('tradesCount').textContent = trades.length + ' 笔';
        if (trades.length === 0) {
            body.innerHTML = '<tr><td colspan="11" class="center" style="padding:30px;color:var(--text-muted);">无交易记录</td></tr>';
            return;
        }
        body.innerHTML = trades.map(function (t) {
            const pnl = t.pnl != null ? t.pnl : t.netPnl;
            const ret = t.returnPct != null ? t.returnPct : t.return_pct;
            const side = t.side || (t.quantity >= 0 ? '买入' : '卖出');
            const sideCls = side === '买入' || (side || '').toUpperCase() === 'LONG' ? 'side-buy' : 'side-sell';
            const exitTag = t.exitTag || t.exit_tag || '';
            const tagColor = exitTag.indexOf('止盈') >= 0 || exitTag.indexOf('信号') >= 0 ? { bg: 'color-mix(in srgb, var(--rise-color) 12%, transparent)', c: 'var(--rise-light)', b: 'color-mix(in srgb, var(--rise-color) 25%, transparent)' }
                : { bg: 'color-mix(in srgb, var(--accent-orange) 12%, transparent)', c: 'var(--accent-orange)', b: 'color-mix(in srgb, var(--accent-orange) 25%, transparent)' };
            return ''
                + '<tr>'
                + '<td class="mono">' + e(t.symbol || '') + '</td>'
                + '<td><span class="' + sideCls + '">' + e(side) + '</span></td>'
                + '<td class="num">' + e(fmtNum(t.entryPrice)) + '</td>'
                + '<td class="num">' + e(fmtNum(t.exitPrice)) + '</td>'
                + '<td class="num">' + e(formatNumber(t.quantity, 0)) + '</td>'
                + '<td class="mono">' + e(formatDate(t.entryTime)) + '</td>'
                + '<td class="mono">' + e(formatDate(t.exitTime)) + '</td>'
                + '<td class="num">' + e(t.durationBars != null ? t.durationBars : (t.duration_bars != null ? t.duration_bars : '—')) + '</td>'
                + '<td class="num ' + numClass(pnl) + '">' + fmtSigned(pnl, 0) + '</td>'
                + '<td class="num ' + pctClass(ret) + '">' + formatPct(ret) + '</td>'
                + '<td>' + (exitTag ? '<span class="cat-badge" style="background:' + tagColor.bg + ';color:' + tagColor.c + ';border:1px solid ' + tagColor.b + ';">' + e(exitTag) + '</span>' : '') + '</td>'
                + '</tr>';
        }).join('');
    }

    // ============ 持仓快照 ============
    function renderPositions() {
        const body = document.getElementById('positionsBody');
        const positions = state.report.positions || [];
        if (positions.length === 0) {
            body.innerHTML = '<tr><td colspan="9" class="center" style="padding:30px;color:var(--text-muted);">回测期末无持仓</td></tr>';
            return;
        }
        body.innerHTML = positions.map(function (p) {
            const qty = p.longShares != null ? p.longShares : (p.quantity != null ? p.quantity : 0);
            const avail = p.available != null ? p.available : (qty);
            const entryPx = p.entryPrice;
            const curPx = p.close != null ? p.close : p.currentPrice;
            const mv = p.marketValue != null ? p.marketValue : (curPx != null && qty != null ? curPx * qty : null);
            const upnl = p.unrealizedPnl;
            const ret = (entryPx && upnl != null && entryPx > 0) ? (upnl / (entryPx * Math.abs(qty))) * 100 : null;
            return ''
                + '<tr>'
                + '<td class="mono">' + e(p.symbol || '') + '</td>'
                + '<td class="num">' + e(formatNumber(qty, 0)) + '</td>'
                + '<td class="num"><span style="color:var(--text-muted);">' + e(formatNumber(avail, 0)) + '</span></td>'
                + '<td class="num">' + e(fmtNum(entryPx)) + '</td>'
                + '<td class="num">' + e(fmtNum(curPx)) + '</td>'
                + '<td class="num">¥' + e(formatNumber(mv, 0)) + '</td>'
                + '<td class="num ' + numClass(upnl) + '">' + fmtSigned(upnl, 0) + '</td>'
                + '<td class="num ' + pctClass(ret) + '">' + formatPct(ret) + '</td>'
                + '<td class="mono">' + e(p.holdDays != null ? p.holdDays + ' 天' : '—') + '</td>'
                + '</tr>';
        }).join('');
    }

    // ============ CSV 导出 ============
    function exportCsv() {
        const trades = state.report ? (state.report.trades || []) : [];
        if (trades.length === 0) { StockApp.toast('无交易记录可导出', 'warning'); return; }
        const headers = ['标的', '方向', '入场价', '出场价', '数量', '入场时间', '出场时间', '持仓bar', '盈亏', '收益率(%)', '出场标签'];
        const rows = trades.map(function (t) {
            const pnl = t.pnl != null ? t.pnl : t.netPnl;
            const ret = t.returnPct != null ? t.returnPct : t.return_pct;
            return [
                t.symbol || '',
                t.side || '',
                t.entryPrice != null ? t.entryPrice : '',
                t.exitPrice != null ? t.exitPrice : '',
                t.quantity != null ? t.quantity : '',
                formatDate(t.entryTime),
                formatDate(t.exitTime),
                t.durationBars != null ? t.durationBars : '',
                pnl != null ? pnl : '',
                ret != null ? ret : '',
                t.exitTag || t.exit_tag || ''
            ];
        });
        const csv = [headers].concat(rows).map(function (r) {
            return r.map(function (c) {
                const s = String(c == null ? '' : c);
                return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
            }).join(',');
        }).join('\n');
        const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'trades_BT-' + state.backtestId + '.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    // ============ 重跑 / 加入对比 ============
    function rerun() {
        if (!state.basic) return;
        const taskId = state.basic.taskId || state.basic.id || state.backtestId;
        StockApp.post('/api/backtest/tasks/' + taskId + '/rerun', null, function (resp) {
            if (resp.code === 200) {
                StockApp.toast('已发起重跑', 'success');
                setTimeout(function () { window.location.href = StockApp.contextPath + '/quant/backtests'; }, 800);
            } else {
                StockApp.toast(resp.message || '重跑失败', 'danger');
            }
        });
    }

    function addToCompare() {
        const id = state.backtestId;
        let ids = [];
        try { ids = JSON.parse(localStorage.getItem('bt_compare_ids') || '[]'); } catch (err) { ids = []; }
        if (ids.indexOf(id) < 0) ids.push(id);
        localStorage.setItem('bt_compare_ids', JSON.stringify(ids));
        StockApp.toast('已加入对比篮，共 ' + ids.length + ' 个', 'success');
    }

    // ============ 工具 ============
    function getCssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }
    function formatNumber(v, d) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
    }
    function fmtNum(v) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toFixed(2);
    }
    function fmtSigned(v, d) {
        if (v == null || isNaN(v)) return '—';
        const n = Number(v);
        const sign = n > 0 ? '+' : '';
        return sign + n.toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
    }
    function formatPct(v) {
        if (v == null || isNaN(v)) return '—';
        const n = Number(v);
        const sign = n > 0 ? '+' : '';
        return sign + n.toFixed(2) + '%';
    }
    function formatRatio(v, suffix) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toFixed(2) + (suffix || '');
    }
    function pctClass(v) {
        if (v == null || isNaN(v)) return '';
        return Number(v) > 0 ? 'up' : (Number(v) < 0 ? 'down' : '');
    }
    function ratioClass(v) {
        if (v == null || isNaN(v)) return '';
        return Number(v) > 0 ? 'up' : (Number(v) < 0 ? 'down' : '');
    }
    function numClass(v) {
        if (v == null || isNaN(v)) return '';
        return Number(v) > 0 ? 'up' : (Number(v) < 0 ? 'down' : '');
    }
    function formatDate(s) {
        if (!s) return '';
        const d = new Date(s);
        if (isNaN(d.getTime())) return String(s).slice(0, 10);
        return d.toISOString().slice(0, 10);
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
    Backtest.initReport = function () {
        document.getElementById('btnExportCsv').addEventListener('click', exportCsv);
        document.getElementById('btnExportCsv2').addEventListener('click', exportCsv);
        document.getElementById('btnRerun').addEventListener('click', rerun);
        document.getElementById('btnCompareAdd').addEventListener('click', addToCompare);

        window.addEventListener('resize', function () {
            Object.keys(state.charts).forEach(function (k) { if (state.charts[k]) state.charts[k].resize(); });
        });

        loadAll();
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (document.getElementById('backtestReportPage')) Backtest.initReport();
    });
})();
