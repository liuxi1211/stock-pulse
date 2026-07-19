/**
 * 参数寻优页（spec 015 FR-O1/O4/O6/O7）。
 *
 * 数据流：
 *   - GET /api/strategies                     策略列表
 *   - GET /api/strategies/{id}/versions       版本列表（过滤 VERIFIED/ACTIVE）
 *   - GET /api/strategies/{id}/versions/{n}   取 configJson（提取 tunable_params 反推 paramGrid）
 *   - POST /api/optimize/grid                 提交 GRID 寻优（异步，返回 task_id）
 *   - POST /api/optimize/walk-forward         提交 WF 验证
 *   - GET  /api/optimize/{taskId}             轮询任务状态/结果
 *   - POST /api/optimize/{taskId}/cancel      取消
 *
 * 合规约束（spec 015 PRD §1.2.3）：
 *   - 「应用」按钮需用户勾选「我已理解历史最优不代表未来」方可跳转；
 *   - Top-N 标题用「历史 Top-N」，不出现「最优参数」「推荐」字样。
 */
(function () {
    'use strict';

    const ALLOWED_VERSION_STATUS = ['VERIFIED', 'ACTIVE'];
    // 6 维过拟合指标默认判据（与 engine overfit_metrics.py 对齐）
    const WF_THRESHOLDS = { max_return_gap: 0.3, max_dd_ratio: 2.0, max_param_cv: 0.5, max_peak_gap: 0.5, max_segment_diversity: 0.4 };

    const state = {
        strategies: [],
        versions: [],
        selectedUuid: null,
        selectedVersionNo: null,
        selectedConfig: null,        // 完整 configJson
        tunableParams: [],           // config.tunable_params
        currentTaskId: null,         // GRID/WF 任务 id
        pollTimer: null,
        top1Params: null,            // GRID Top-1 参数（应用按钮用）
        wfPassed: false,             // WF 是否通过五项判据
    };

    // ===================== 初始化 =====================
    function init() {
        // URL 预选（从策略管理页跳转）
        const params = new URLSearchParams(location.search);
        const preSid = params.get('uuid');
        const preVer = params.get('versionNo');

        loadStrategies(preSid, preVer);

        document.getElementById('btnRunGrid').addEventListener('click', onClickRunGrid);
        document.getElementById('btnRunWf').addEventListener('click', onClickRunWf);
        document.getElementById('btnCancel').addEventListener('click', onClickCancel);
        document.getElementById('btnApply').addEventListener('click', onClickApply);
        document.getElementById('btnConfirmCancel').addEventListener('click', closeConfirm);
        document.getElementById('confirmCheckbox').addEventListener('change', function () {
            document.getElementById('btnConfirmApply').disabled = !this.checked;
        });
        document.getElementById('btnConfirmApply').addEventListener('click', onConfirmApply);
    }

    // ===================== 策略 / 版本加载 =====================
    function loadStrategies(preSid, preVer) {
        StockApp.get('/api/strategies', { page: 1, size: 200 }, function (resp) {
            const sel = document.getElementById('selStrategy');
            if (resp.code === 200 && resp.data) {
                state.strategies = resp.data.records || resp.data || [];
            } else {
                state.strategies = [];
            }
            sel.innerHTML = '<option value="">请选择策略</option>' + state.strategies.map(function (s) {
                return '<option value="' + esc(s.uuid) + '">' + esc(s.name) + '</option>';
            }).join('');
            if (preSid) {
                sel.value = preSid;
                onStrategyChange(preVer);
            }
        });
        document.getElementById('selStrategy').addEventListener('change', function () { onStrategyChange(); });
    }

    function onStrategyChange(preVer) {
        const sid = document.getElementById('selStrategy').value;
        state.selectedUuid = sid;
        const verSel = document.getElementById('selVersion');
        verSel.disabled = true;
        verSel.innerHTML = '<option value="">加载中…</option>';
        resetParamGrid();
        if (!sid) return;

        StockApp.get('/api/strategies/' + sid + '/versions', null, function (resp) {
            if (resp.code === 200 && Array.isArray(resp.data)) {
                state.versions = resp.data.filter(function (v) { return ALLOWED_VERSION_STATUS.indexOf(v.status) >= 0; });
            } else {
                state.versions = [];
            }
            verSel.innerHTML = '<option value="">请选择版本</option>' + state.versions.map(function (v) {
                const n = v.versionNo != null ? v.versionNo : v.version;
                return '<option value="' + esc(n) + '">v' + esc(n) + ' · ' + esc(v.status) + '</option>';
            }).join('');
            verSel.disabled = false;
            if (preVer) {
                verSel.value = preVer;
                onVersionChange();
            }
        });
        verSel.onchange = onVersionChange;
    }

    function onVersionChange() {
        const verNo = document.getElementById('selVersion').value;
        state.selectedVersionNo = verNo;
        if (!verNo || !state.selectedUuid) return;
        // 拉版本详情取 configJson（含 tunable_params）
        StockApp.get('/api/strategies/' + state.selectedUuid + '/versions/' + verNo, null, function (resp) {
            if (resp.code === 200 && resp.data && resp.data.configJson) {
                let cfg = resp.data.configJson;
                if (typeof cfg === 'string') { try { cfg = JSON.parse(cfg); } catch (e) { cfg = {}; } }
                state.selectedConfig = cfg;
                state.tunableParams = Array.isArray(cfg.tunable_params) ? cfg.tunable_params : [];
                renderParamGrid();
                renderStrategyInfo();
            } else {
                state.selectedConfig = null;
                state.tunableParams = [];
                renderParamGrid();
                renderStrategyInfo();
                StockApp.toast('该版本无 configJson 或加载失败', 'danger');
            }
        });
    }

    function renderStrategyInfo() {
        const el = document.getElementById('strategyInfo');
        if (!state.tunableParams.length) {
            el.innerHTML = '<i class="bi bi-exclamation-triangle" style="color:var(--accent-orange);"></i> 该策略未声明 tunable_params，无法寻优。请在策略编辑器为技术因子参数声明 tunable_params。';
            return;
        }
        el.innerHTML = '<i class="bi bi-check-circle" style="color:var(--accent-green);"></i> 可调参数：'
            + state.tunableParams.map(function (p) { return '<code>' + esc(p.name) + '</code>'; }).join(', ');
    }

    // ===================== paramGrid 编辑器 =====================
    function renderParamGrid() {
        const area = document.getElementById('paramGridArea');
        if (!state.tunableParams.length) {
            area.innerHTML = '<div class="opt-empty">该策略未声明 tunable_params，无法寻优</div>';
            updateResourceEstimate();
            return;
        }
        let html = '<div style="font-size:11px;color:var(--text-muted);margin-bottom:6px;">候选值用逗号分隔，如 <code>5,10,20</code>；留空则用 default。</div>';
        state.tunableParams.forEach(function (p) {
            const dv = p.default != null ? p.default : '';
            html += '<div class="opt-param-row">'
                + '<span class="pname">' + esc(p.name) + '</span>'
                + '<input type="text" id="pg_' + esc(p.name) + '" placeholder="' + esc(String(dv)) + '" data-default="' + esc(String(dv)) + '">'
                + '<span class="phint">' + esc(p.type) + (p.min != null ? ' [' + p.min + '..' + (p.max != null ? p.max : '') + ']' : '') + '</span>'
                + '</div>';
        });
        area.innerHTML = html;
        area.querySelectorAll('input').forEach(function (inp) {
            inp.addEventListener('input', updateResourceEstimate);
        });
        updateResourceEstimate();
    }

    function resetParamGrid() {
        state.tunableParams = [];
        document.getElementById('paramGridArea').innerHTML = '<div class="opt-empty">选择策略版本后，根据 tunable_params 自动展开参数行</div>';
        updateResourceEstimate();
    }

    function collectParamGrid() {
        const grid = {};
        let total = 1;
        let hasAny = false;
        state.tunableParams.forEach(function (p) {
            const inp = document.getElementById('pg_' + p.name);
            const raw = inp ? inp.value.trim() : '';
            let vals;
            if (!raw) {
                vals = [p.default];
            } else {
                vals = raw.split(',').map(function (s) {
                    s = s.trim();
                    if (p.type === 'int') return parseInt(s, 10);
                    if (p.type === 'float') return parseFloat(s);
                    return s;
                }).filter(function (v) { return v !== '' && !isNaN(v); });
            }
            if (vals.length > 0) {
                grid[p.name] = vals;
                total *= vals.length;
                if (vals.length > 1) hasAny = true;
            }
        });
        return { grid: grid, total: total, hasVariation: hasAny };
    }

    function updateResourceEstimate() {
        const el = document.getElementById('resourceEstimate');
        if (!state.tunableParams.length) {
            el.textContent = '组合数：— · 预估耗时：—';
            return;
        }
        const r = collectParamGrid();
        // 预估耗时：单组合 ~0.8s × 组合数 / max_workers（粗估，首次默认值由 spec FR-O11 定）
        const workers = parseInt(document.getElementById('inpWorkers').value, 10) || 4;
        const sec = Math.max(1, Math.round(r.total * 0.8 / workers));
        const min = (sec / 60).toFixed(1);
        el.textContent = '组合数：' + r.total + ' · 预估耗时：≈' + sec + 's (' + min + 'min) @' + workers + ' workers';
    }

    // ===================== 提交 GRID =====================
    function onClickRunGrid() {
        if (!state.selectedUuid || !state.selectedVersionNo) {
            StockApp.toast('请先选择策略版本', 'warning'); return;
        }
        if (!state.tunableParams.length) {
            StockApp.toast('该策略无可调参数，无法寻优', 'warning'); return;
        }
        const r = collectParamGrid();
        if (!r.hasVariation) {
            StockApp.toast('至少一个参数需填多个候选值才有寻优意义', 'warning'); return;
        }
        const body = {
            uuid: state.selectedUuid,
            versionNo: parseInt(state.selectedVersionNo, 10),
            param_grid: r.grid,
            sort_by: document.getElementById('selSortBy').value,
            top_n: parseInt(document.getElementById('inpTopN').value, 10) || 10,
        };
        const w = document.getElementById('inpWorkers').value;
        if (w) body.max_workers = parseInt(w, 10);
        const c = parseDslField('inpConstraint'); if (c) body.constraint = c;
        const f = parseDslField('inpResultFilter'); if (f) body.result_filter = f;

        document.getElementById('btnRunGrid').disabled = true;
        StockApp.post('/api/optimize/grid', body, function (resp) {
            document.getElementById('btnRunGrid').disabled = false;
            if (resp.code === 200 && resp.data && resp.data.task_id) {
                state.currentTaskId = resp.data.task_id;
                StockApp.toast('GRID 寻优已提交：' + state.currentTaskId, 'success');
                startPolling('grid');
            } else {
                StockApp.toast('提交失败：' + (resp.message || '未知错误'), 'danger');
            }
        });
    }

    // ===================== 提交 WF =====================
    function onClickRunWf() {
        if (!state.currentTaskId || !state.top1Params) {
            StockApp.toast('请先跑完 GRID 寻优得到 Top-1', 'warning'); return;
        }
        // WF 用 GRID 的 param_grid（top1 必在其中）
        const r = collectParamGrid();
        const train = prompt('训练窗口（bar 数）', '120');
        if (!train) return;
        const test = prompt('测试窗口（bar 数）', '30');
        if (!test) return;
        const body = {
            uuid: state.selectedUuid,
            versionNo: parseInt(state.selectedVersionNo, 10),
            param_grid: r.grid,
            train_period: parseInt(train, 10),
            test_period: parseInt(test, 10),
            metric: document.getElementById('selSortBy').value,
            window_align: 'bar_count',
        };
        document.getElementById('btnRunWf').disabled = true;
        StockApp.post('/api/optimize/walk-forward', body, function (resp) {
            document.getElementById('btnRunWf').disabled = false;
            if (resp.code === 200 && resp.data && resp.data.task_id) {
                state.currentTaskId = resp.data.task_id;
                StockApp.toast('WF 任务已提交：' + state.currentTaskId, 'success');
                startPolling('wf');
            } else {
                StockApp.toast('提交失败：' + (resp.message || '未知错误'), 'danger');
            }
        });
    }

    // ===================== 轮询任务 =====================
    function startPolling(type) {
        stopPolling();
        document.getElementById('btnCancel').disabled = false;
        const poll = function () {
            if (!state.currentTaskId) return;
            StockApp.get('/api/optimize/' + state.currentTaskId, null, function (resp) {
                if (resp.code !== 200 || !resp.data) {
                    setTaskStatus('查询失败', null);
                    return;
                }
                const t = resp.data;
                renderTaskStatus(t);
                if (t.status === 'PENDING' || t.status === 'RUNNING') {
                    state.pollTimer = setTimeout(poll, 2000);
                } else {
                    // 终态
                    stopPolling();
                    document.getElementById('btnCancel').disabled = true;
                    if (t.status === 'SUCCESS') {
                        if (type === 'grid' || (t.result && t.result.top_n)) {
                            renderTopN(t.result);
                            // GRID 成功后允许跑 WF
                            document.getElementById('btnRunWf').disabled = false;
                        }
                        if (t.result && t.result.wf_summary) {
                            renderWfSummary(t.result.wf_summary);
                        }
                    } else if (t.status === 'FAILED') {
                        StockApp.toast('任务失败：' + (t.error_message || ''), 'danger');
                    }
                }
            });
        };
        poll();
    }

    function stopPolling() {
        if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
    }

    function renderTaskStatus(t) {
        setTaskStatus(t.status, t);
    }

    function setTaskStatus(text, t) {
        const el = document.getElementById('taskStatus');
        if (!t) { el.textContent = '状态：' + text; return; }
        el.innerHTML = '任务：' + esc(t.task_id) + ' · 类型：' + esc(t.task_type || '') + ' · 状态：<span class="opt-status-badge opt-status-' + esc(t.status) + '">' + esc(t.status) + '</span>'
            + (t.error_message ? ' · <span style="color:#dc3545;">' + esc(t.error_message) + '</span>' : '');
    }

    // ===================== Top-N 渲染 =====================
    function renderTopN(result) {
        const area = document.getElementById('topNArea');
        if (!result || !result.top_n || !result.top_n.length) {
            area.innerHTML = '<div class="opt-empty">无寻优结果</div>';
            return;
        }
        const sortBy = result.sort_by || 'sharpe_ratio';
        // 收集所有指标列名
        const metricKeys = new Set();
        result.top_n.forEach(function (r) { if (r.metrics) Object.keys(r.metrics).forEach(function (k) { metricKeys.add(k); }); });
        const mk = Array.from(metricKeys);
        state.top1Params = result.top_n[0].params;

        let html = '<div class="opt-task-info" style="margin-bottom:8px;">共 ' + (result.total_combinations || result.top_n.length) + ' 组合，按 ' + esc(sortBy) + ' 降序，展示 Top-' + result.top_n.length + '</div>';
        html += '<table class="opt-table"><thead><tr><th>排名</th>';
        Object.keys(state.top1Params || {}).forEach(function (k) { html += '<th>' + esc(k) + '</th>'; });
        mk.forEach(function (k) { html += '<th>' + esc(k) + (k === sortBy ? ' ↓' : '') + '</th>'; });
        html += '<th>耗时(s)</th><th>操作</th></tr></thead><tbody>';
        result.top_n.forEach(function (r, i) {
            const isTop1 = i === 0;
            html += '<tr class="' + (isTop1 ? 'top1' : '') + '"><td>' + (i + 1) + '</td>';
            Object.keys(state.top1Params || {}).forEach(function (k) {
                html += '<td>' + esc(r.params ? r.params[k] : '') + '</td>';
            });
            mk.forEach(function (k) {
                let v = r.metrics ? r.metrics[k] : null;
                if (v != null && typeof v === 'number') {
                    // 单位归一化展示（_pct 字段是原始百分数，÷100 显示为小数更直观）
                    if (/_pct$/.test(k)) v = (v / 100).toFixed(4);
                    else v = v.toFixed(4);
                }
                html += '<td>' + esc(v == null ? '—' : v) + '</td>';
            });
            html += '<td>' + esc(r._duration != null ? r._duration.toFixed(1) : '—') + '</td>';
            html += '<td><button class="btn-opt btn-opt-secondary" onclick="window.__copyParams(' + i + ')">复制</button></td>';
            html += '</tr>';
        });
        html += '</tbody></table>';
        // 暴露复制函数
        window.__copyParams = function (idx) {
            const p = result.top_n[idx].params;
            navigator.clipboard.writeText(JSON.stringify(p)).then(function () {
                StockApp.toast('参数已复制：' + JSON.stringify(p), 'success');
            });
        };
        area.innerHTML = html;
    }

    // ===================== WF 6 维过拟合指标 =====================
    function renderWfSummary(wf) {
        const card = document.getElementById('wfCard');
        card.style.display = '';
        const el = document.getElementById('wfSummary');
        if (!wf) { el.innerHTML = '<div class="opt-empty">WF 无结果</div>'; return; }

        // 判定是否通过（engine 已返回 passed 字段；前端兜底再算一次）
        const passed = wf.passed != null ? wf.passed : checkWfPassed(wf);
        state.wfPassed = passed;

        const badge = function (label, val, threshold, higherBad) {
            if (val == null) return '<span class="opt-badge opt-badge-warn">' + label + ': N/A</span>';
            const bad = higherBad ? (val > threshold) : (val < threshold);
            return '<span class="opt-badge ' + (bad ? 'opt-badge-fail' : 'opt-badge-pass') + '">' + label + ': ' + Number(val).toFixed(3) + '</span>';
        };
        const score = wf.confidence_score != null ? wf.confidence_score : 0;
        const scoreBadge = '<span class="opt-badge ' + (score >= 60 ? 'opt-badge-pass' : (score >= 30 ? 'opt-badge-warn' : 'opt-badge-fail')) + '">可信度: ' + score + '/100</span>';
        const passBadge = '<span class="opt-badge ' + (passed ? 'opt-badge-pass' : 'opt-badge-fail') + '">' + (passed ? '通过判据' : '未通过') + '</span>';

        el.innerHTML = '<div>样本段数：<span>' + (wf.segments || 0) + '</span> · '
            + passBadge + ' ' + scoreBadge + '</div>'
            + '<div class="opt-confidence" style="margin-top:6px;">'
            + badge('收益差', wf.return_gap, WF_THRESHOLDS.max_return_gap, true)
            + badge('回撤比', wf.dd_ratio, WF_THRESHOLDS.max_dd_ratio, true)
            + badge('参数CV', wf.cv, WF_THRESHOLDS.max_param_cv, true)
            + badge('孤峰差', wf.peak_gap, WF_THRESHOLDS.max_peak_gap, true)
            + badge('段多样性', wf.diversity, WF_THRESHOLDS.max_segment_diversity, true)
            + badge('笔数比', wf.trade_ratio, 1, false)
            + '</div>';

        // 应用按钮置灰逻辑（FR-O6）
        const btnApply = document.getElementById('btnApply');
        const hint = document.getElementById('applyHint');
        if (passed) {
            btnApply.disabled = false;
            hint.textContent = '历史 Top-1 通过 WF 五项判据，可应用（需二次确认）。';
        } else {
            btnApply.disabled = true;
            // 正向反馈：量化"避免了多少过拟合风险"
            const avoided = Math.max(0, 100 - score);
            hint.textContent = '未通过 WF 判据，应用按钮置灰。你比无脑 Top-1 避免了约 ' + avoided + '% 的过拟合风险。'
                + '如何让它可点：调整 WF 阈值 / 增加数据 / 更换参数空间。';
        }
    }

    function checkWfPassed(wf) {
        // 与 engine overfit_metrics.py 的 passed 判据一致
        if (wf.return_gap != null && wf.return_gap > WF_THRESHOLDS.max_return_gap) return false;
        if (wf.dd_ratio != null && wf.dd_ratio > WF_THRESHOLDS.max_dd_ratio) return false;
        if (wf.cv != null && wf.cv > WF_THRESHOLDS.max_param_cv) return false;
        if (wf.diversity != null && wf.diversity > WF_THRESHOLDS.max_segment_diversity) return false;
        return true;
    }

    // ===================== 取消 =====================
    function onClickCancel() {
        if (!state.currentTaskId) return;
        if (!confirm('确认取消任务 ' + state.currentTaskId + ' ？')) return;
        StockApp.post('/api/optimize/' + state.currentTaskId + '/cancel', {}, function (resp) {
            if (resp.code === 200) {
                StockApp.toast('取消请求已提交', 'success');
                stopPolling();
                document.getElementById('btnCancel').disabled = true;
            } else {
                StockApp.toast('取消失败：' + (resp.message || ''), 'warning');
            }
        });
    }

    // ===================== 应用历史 Top-1（FR-O6 二次确认���=====================
    function onClickApply() {
        if (!state.top1Params) { StockApp.toast('尚无历史 Top-1 参数', 'warning'); return; }
        document.getElementById('applyConfirmMask').classList.add('show');
        document.getElementById('confirmCheckbox').checked = false;
        document.getElementById('btnConfirmApply').disabled = true;
    }

    function closeConfirm() {
        document.getElementById('applyConfirmMask').classList.remove('show');
    }

    function onConfirmApply() {
        // 跳转新建策略页，预填参数（通过 URL hash 携带，新建页读取后注入）
        const params = state.top1Params;
        const sid = state.selectedUuid;
        const ver = state.selectedVersionNo;
        const carry = btoa(unescape(encodeURIComponent(JSON.stringify({ uuid: sid, versionNo: ver, params: params }))));
        // spec FR-O6：跳转新建策略页预填，用户手动保存；GRID 永不写策略表
        location.href = '/quant/strategies/new?from=optimize&payload=' + carry;
    }

    // ===================== 工具 =====================
    function parseDslField(id) {
        const v = document.getElementById(id).value.trim();
        if (!v) return null;
        try { return JSON.parse(v); } catch (e) {
            StockApp.toast(id + ' 不是合法 JSON（应为 {"left":"...","op":"<","right":"..."}）', 'warning');
            return null;
        }
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // ===================== 启动 =====================
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
