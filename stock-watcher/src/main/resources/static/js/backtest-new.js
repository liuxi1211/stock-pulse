/**
 * 回测配置页逻辑（spec 007 T4.3）。
 * 3 步：选策略版本 → 配置参数 → 确认提交。
 *
 * 数据源：
 *   - GET /api/strategies                         策略列表
 *   - GET /api/strategies/{id}/versions           版本列表（过滤 VERIFIED/ACTIVE）
 *   - GET /api/backtest/benchmarks                基准列表 [{code, name}]
 *   - GET /api/backtest/constants-proxy           {brokerProfiles, sortMetrics, paradigmsSupported}
 *   - POST /api/backtest/run                      提交回测
 *     body: {mode, strategyId, versionNo, overrideConfig, benchmark}
 */
(function () {
    'use strict';

    const Backtest = window.Backtest || (window.Backtest = {});
    const e = StockApp.escapeHtml;

    const ALLOWED_VERSION_STATUS = ['VERIFIED', 'ACTIVE'];

    const state = {
        step: 1,
        strategies: [],
        versions: [],
        benchmarks: [],
        brokerProfiles: [],
        paradigmsSupported: [],
        selectedStrategyId: null,
        selectedVersionNo: null,
        selectedVersion: null
    };

    // ============ 初始化加载 ============
    function loadInitialData() {
        StockApp.get('/api/strategies', { page: 1, size: 200 }, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.strategies = resp.data.list || resp.data.records || [];
            }
            renderStrategySelect();
        });

        StockApp.get('/api/backtest/benchmarks', null, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.benchmarks = Array.isArray(resp.data) ? resp.data : [];
            }
            renderBenchmarkSelect();
        });

        StockApp.get('/api/backtest/constants-proxy', null, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.brokerProfiles = resp.data.brokerProfiles || [];
                state.paradigmsSupported = resp.data.paradigmsSupported || [];
            }
            renderBrokerProfileSelect();
        });

        setDefaultDates();
    }

    function setDefaultDates() {
        const today = new Date();
        const oneYearAgo = new Date(today);
        oneYearAgo.setFullYear(today.getFullYear() - 1);
        document.getElementById('inpStartDate').value = oneYearAgo.toISOString().slice(0, 10);
        document.getElementById('inpEndDate').value = today.toISOString().slice(0, 10);
    }

    // ============ 渲染下拉 ============
    function renderStrategySelect() {
        const sel = document.getElementById('selStrategy');
        if (state.strategies.length === 0) {
            sel.innerHTML = '<option value="">暂无可用策略</option>';
            return;
        }
        let html = '<option value="">请选择策略…</option>';
        state.strategies.forEach(function (s) {
            html += '<option value="' + e(s.id) + '">' + e(s.name) + (s.id != null ? ' (S-' + e(s.id) + ')' : '') + '</option>';
        });
        sel.innerHTML = html;
    }

    function renderBenchmarkSelect() {
        const sel = document.getElementById('selBenchmark');
        if (state.benchmarks.length === 0) {
            sel.innerHTML = '<option value="000300.SH">沪深300 · 000300.SH（默认）</option>';
            return;
        }
        let html = '';
        state.benchmarks.forEach(function (b, idx) {
            const isDefault = (b.code === '000300.SH' || idx === 0);
            html += '<option value="' + e(b.code) + '"' + (isDefault ? ' selected' : '') + '>' + e(b.name) + ' · ' + e(b.code) + '</option>';
        });
        sel.innerHTML = html;
    }

    function renderBrokerProfileSelect() {
        const sel = document.getElementById('selBrokerProfile');
        if (state.brokerProfiles.length === 0) {
            sel.innerHTML = '<option value="cn_stock_miniqmt" selected>cn_stock_miniqmt（默认）</option>';
            return;
        }
        let html = '';
        state.brokerProfiles.forEach(function (p, idx) {
            const isDefault = (p === 'cn_stock_miniqmt' || idx === 0);
            html += '<option value="' + e(p) + '"' + (isDefault ? ' selected' : '') + '>' + e(p) + '</option>';
        });
        sel.innerHTML = html;
    }

    // ============ 策略选中 → 加载版本 ============
    function onStrategyChange() {
        const sid = document.getElementById('selStrategy').value;
        state.selectedStrategyId = sid || null;
        state.selectedVersionNo = null;
        state.selectedVersion = null;
        const versionRow = document.getElementById('versionRow');
        const versionList = document.getElementById('versionList');
        const summary = document.getElementById('strategySummary');
        versionList.innerHTML = '';
        summary.style.display = 'none';
        if (!sid) { versionRow.style.display = 'none'; return; }

        versionRow.style.display = '';
        versionList.innerHTML = '<div style="font-size:11.5px;color:var(--text-muted);padding:8px;">加载版本中…</div>';

        StockApp.get('/api/strategies/' + sid + '/versions', null, function (resp) {
            if (resp.code === 200 && Array.isArray(resp.data)) {
                state.versions = resp.data.filter(function (v) {
                    return ALLOWED_VERSION_STATUS.indexOf(v.status) >= 0;
                });
            } else {
                state.versions = [];
            }
            renderVersionList();
        });
    }

    function renderVersionList() {
        const box = document.getElementById('versionList');
        if (state.versions.length === 0) {
            box.innerHTML = '<div style="font-size:11.5px;color:var(--accent-orange);padding:8px;"><i class="bi bi-exclamation-triangle"></i> 该策略没有 VERIFIED/ACTIVE 版本，无法回测。请先在策略管理页校验。</div>';
            return;
        }
        box.innerHTML = state.versions.map(function (v) {
            const ver = v.versionNo != null ? v.versionNo : v.version;
            const statusCls = v.status === 'ACTIVE' ? 'var(--accent-purple-light)' : 'var(--accent-green)';
            return ''
                + '<div class="bt-version-pick" data-ver="' + e(ver) + '">'
                + '  <div class="bt-version-pick-radio"></div>'
                + '  <div class="bt-version-pick-info">'
                + '    <div class="bt-version-pick-name">v' + e(ver) + ' <span style="font-size:10px;color:' + statusCls + ';margin-left:6px;">' + e(v.status) + '</span></div>'
                + '    <div class="bt-version-pick-meta">' + e(v.changelog || v.description || '无变更说明') + ' · ' + e(v.createdAt || '') + '</div>'
                + '  </div>'
                + '</div>';
        }).join('');

        box.querySelectorAll('.bt-version-pick').forEach(function (el) {
            el.addEventListener('click', function () {
                box.querySelectorAll('.bt-version-pick').forEach(function (x) { x.classList.remove('selected'); });
                el.classList.add('selected');
                const ver = el.getAttribute('data-ver');
                state.selectedVersionNo = ver;
                state.selectedVersion = state.versions.find(function (v) { return String(v.versionNo != null ? v.versionNo : v.version) === String(ver); });
                renderStrategySummary();
            });
        });
    }

    function renderStrategySummary() {
        const summary = document.getElementById('strategySummary');
        if (!state.selectedStrategyId || !state.selectedVersionNo) {
            summary.style.display = 'none';
            return;
        }
        const strat = state.strategies.find(function (s) { return String(s.id) === String(state.selectedStrategyId); });
        const name = strat ? strat.name : 'S-' + state.selectedStrategyId;
        summary.style.display = '';
        summary.innerHTML = ''
            + '<div class="bt-form-section" style="margin-bottom:0;">'
            + '  <div class="bt-form-section-title"><i class="bi bi-clipboard"></i>已选策略摘要</div>'
            + '  <div style="font-size:11.5px;color:var(--text-secondary);line-height:1.8;">'
            + '    <div><span class="mono" style="color:var(--text-muted);">策略</span> ' + e(name) + ' <span class="mono" style="color:var(--text-muted);">S-' + e(state.selectedStrategyId) + '</span></div>'
            + '    <div><span class="mono" style="color:var(--text-muted);">版本</span> v' + e(state.selectedVersionNo) + '</div>'
            + '  </div>'
            + '</div>';
    }

    // ============ 区间预设 ============
    function applyPreset(preset) {
        const end = new Date();
        const start = new Date(end);
        if (preset === '1m') start.setMonth(end.getMonth() - 1);
        else if (preset === '3m') start.setMonth(end.getMonth() - 3);
        else if (preset === '6m') start.setMonth(end.getMonth() - 6);
        else if (preset === '1y') start.setFullYear(end.getFullYear() - 1);
        else if (preset === '3y') start.setFullYear(end.getFullYear() - 3);
        else if (preset === 'all') start.setFullYear(end.getFullYear() - 5);
        document.getElementById('inpStartDate').value = start.toISOString().slice(0, 10);
        document.getElementById('inpEndDate').value = end.toISOString().slice(0, 10);
    }

    // ============ 步骤切换 ============
    function goToStep(n) {
        if (n < 1 || n > 3) return;
        if (n === 2 && !validateStep1()) return;
        if (n === 3 && !validateStep2()) return;
        state.step = n;
        for (let i = 1; i <= 3; i++) {
            const panel = document.getElementById('panel' + i);
            const step = document.getElementById('step' + i);
            panel.style.display = (i === n) ? '' : 'none';
            step.classList.remove('active', 'done');
            if (i < n) step.classList.add('done');
            else if (i === n) step.classList.add('active');
        }
        document.getElementById('btnPrev').style.visibility = (n === 1) ? 'hidden' : '';
        document.getElementById('btnNext').style.display = (n === 3) ? 'none' : '';
        document.getElementById('btnSubmit').style.display = (n === 3) ? '' : 'none';
        if (n === 3) renderConfirmSummary();
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    function validateStep1() {
        if (!state.selectedStrategyId) { StockApp.toast('请选择策略', 'warning'); return false; }
        if (!state.selectedVersionNo) { StockApp.toast('请选择版本', 'warning'); return false; }
        return true;
    }

    function validateStep2() {
        const start = document.getElementById('inpStartDate').value;
        const end = document.getElementById('inpEndDate').value;
        const cash = parseFloat(document.getElementById('inpInitialCash').value);
        const benchmark = document.getElementById('selBenchmark').value;
        if (!start || !end) { StockApp.toast('请选择回测区间', 'warning'); return false; }
        if (new Date(start) >= new Date(end)) { StockApp.toast('起始日必须早于结束日', 'warning'); return false; }
        if (!benchmark) { StockApp.toast('请选择基准', 'warning'); return false; }
        if (isNaN(cash) || cash < 10000) { StockApp.toast('初始资金至少 1 万', 'warning'); return false; }
        return true;
    }

    function renderConfirmSummary() {
        const start = document.getElementById('inpStartDate').value;
        const end = document.getElementById('inpEndDate').value;
        const cash = document.getElementById('inpInitialCash').value;
        const benchmark = document.getElementById('selBenchmark');
        const benchmarkTxt = benchmark.options[benchmark.selectedIndex] ? benchmark.options[benchmark.selectedIndex].textContent : benchmark.value;
        const profile = document.getElementById('selBrokerProfile').value;
        const strat = state.strategies.find(function (s) { return String(s.id) === String(state.selectedStrategyId); });
        const name = strat ? strat.name : 'S-' + state.selectedStrategyId;
        const t1 = document.getElementById('swT1').classList.contains('on');

        const row = function (k, v) {
            return '<div><span class="mono" style="color:var(--text-muted);display:inline-block;width:110px;">' + e(k) + '</span> ' + v + '</div>';
        };
        const html = ''
            + row('策略', '<strong style="color:var(--text-primary);">' + e(name) + '</strong> <span class="mono" style="color:var(--text-muted);">S-' + e(state.selectedStrategyId) + '</span>')
            + row('版本', 'v' + e(state.selectedVersionNo))
            + row('区间', '<span class="mono">' + e(start) + ' → ' + e(end) + '</span>')
            + row('基准', '<span class="mono">' + e(benchmarkTxt) + '</span>')
            + row('初始资金', '<span class="mono">¥' + e(formatNumber(cash, 0)) + '</span>')
            + row('费率模板', '<span class="mono">' + e(profile) + '</span>')
            + row('T+1', '<span class="mono">' + (t1 ? '启用' : '关闭') + '</span>');
        document.getElementById('confirmSummary').innerHTML = html;
    }

    // ============ 提交 ============
    function submitBacktest() {
        if (!validateStep1() || !validateStep2()) { goToStep(1); return; }
        const start = document.getElementById('inpStartDate').value;
        const end = document.getElementById('inpEndDate').value;
        const cash = parseFloat(document.getElementById('inpInitialCash').value);
        const benchmark = document.getElementById('selBenchmark').value;
        const profile = document.getElementById('selBrokerProfile').value;
        const t1 = document.getElementById('swT1').classList.contains('on');

        const strat = state.strategies.find(function (s) { return String(s.id) === String(state.selectedStrategyId); });
        const mode = (strat && strat.scope === 'portfolio') ? 'rebalance' : 'signals';

        const body = {
            mode: mode,
            strategyId: Number(state.selectedStrategyId),
            versionNo: Number(state.selectedVersionNo),
            benchmark: benchmark,
            overrideConfig: {
                initialCash: cash,
                startDate: start,
                endDate: end,
                brokerProfile: profile,
                tPlusOne: t1
            }
        };

        const btn = document.getElementById('btnSubmit');
        btn.disabled = true;
        StockApp.post('/api/backtest/run', body, function (resp) {
            btn.disabled = false;
            if (resp.code === 200 && resp.data) {
                const id = resp.data.backtestId || resp.data.id || resp.data.taskId;
                StockApp.toast('回测已提交 #' + (id != null ? id : ''), 'success');
                setTimeout(function () {
                    window.location.href = StockApp.contextPath + '/quant/backtests';
                }, 800);
            } else {
                StockApp.toast(resp.message || '提交失败', 'danger');
            }
        });
    }

    // ============ 工具 ============
    function formatNumber(v, d) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
    }

    // ============ 入口 ============
    Backtest.initNew = function () {
        document.getElementById('selStrategy').addEventListener('change', onStrategyChange);

        document.querySelectorAll('.bt-range-preset').forEach(function (btn) {
            btn.addEventListener('click', function () {
                document.querySelectorAll('.bt-range-preset').forEach(function (x) { x.classList.remove('active'); });
                btn.classList.add('active');
                applyPreset(btn.getAttribute('data-preset'));
            });
        });

        document.getElementById('swT1').addEventListener('click', function () {
            this.classList.toggle('on');
        });

        document.getElementById('btnPrev').addEventListener('click', function () { goToStep(state.step - 1); });
        document.getElementById('btnNext').addEventListener('click', function () { goToStep(state.step + 1); });
        document.getElementById('btnSubmit').addEventListener('click', submitBacktest);

        loadInitialData();
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (document.getElementById('backtestNewPage')) Backtest.initNew();
    });
})();
