/**
 * 策略版本历史页逻辑（左右双栏 + 4 Tab 详情区）。
 *
 * 数据源（统一 ApiResponse: {code, message, data}）：
 *   - GET  /api/strategies/{id}                          详情：StrategyDTO（name, currentVersion）
 *   - GET  /api/strategies/{id}/versions                 版本列表：StrategyVersionDTO[]（version_no DESC）
 *   - GET  /api/strategies/{id}/versions/{versionNo}     指定版本 configJson（懒加载）
 *   - GET  /api/strategies/{id}/versions/diff?from=&to=  结构化 diff：StrategyDiffDTO[]
 *       StrategyDiffDTO: { path, changeType(added|removed|modified), oldValue, newValue }
 *   - GET  /api/strategies/{id}/versions/compare?from=&to= 回测对比 metrics（可能为空）
 *   - POST /api/strategies/{id}/versions/rollback        body: { targetVersion, changelog }
 *
 * 布局：
 *   - 左栏 #verList：版本列表 .str-vdiff-item，点击切换详情
 *   - 右栏 #verDetail：4 Tab 详情区（summary/diff/backtest/config）
 *   - 顶部 #verHeader：版本头部卡（#detailTitle / #detailMeta / #detailActions）
 *
 * 设计要点：
 *   - selectVersion(versionNo) 是核心：切换版本时重置所有 Tab 的 loaded 标记并懒加载。
 *   - Tab 内容懒加载：第一次激活某 Tab 时才请求对应数据。
 *   - v1 不调 diff/compare（无对比基准），展示"初始版本，无对比基准"。
 *   - 当前版本（currentVersion）不显示回滚按钮。
 *   - 回滚成功后：清空缓存 → loadDetail + loadVersions + renderVersionList + 选中最新版本。
 *   - 所有 DOM 操作做 null 检查；用 StockApp.escapeHtml 防 XSS。
 */
const StrategyVersions = (function () {
    'use strict';

    const e = StockApp.escapeHtml;

    const state = {
        strategyId: null,
        strategyName: '',
        currentVersion: null,
        versions: [],
        selectedVersion: null,
        // 缓存：key = versionNo（configJson）/ "diff_from_to" / "compare_from_to"
        cache: {},
        // 当前选中版本各 Tab 是否已加载
        tabLoaded: { summary: false, diff: false, backtest: false, config: false },
    };

    // ===================== 工具 =====================

    function apiBase() {
        return '/api/strategies/' + encodeURIComponent(state.strategyId);
    }

    // 任意值转可展示字符串：对象/数组 JSON.stringify，过长截断
    function valToStr(v) {
        if (v === null || v === undefined) return '';
        let s;
        if (typeof v === 'object') {
            try { s = JSON.stringify(v); } catch (_) { s = String(v); }
        } else {
            s = String(v);
        }
        if (s.length > 80) s = s.slice(0, 77) + '…';
        return s;
    }

    // 格式化时间：兼容 "2026-07-02 14:32:00" / ISO / 纯日期
    function fmtTime(t) {
        if (!t) return '-';
        return String(t).replace('T', ' ').replace(/\.\d+$/, '').slice(0, 19);
    }

    // 美化 JSON 字符串（configJson 在后端是 String）
    function prettyJson(configJson) {
        if (!configJson) return '';
        try {
            return JSON.stringify(JSON.parse(configJson), null, 2);
        } catch (_) {
            return configJson;
        }
    }

    // 按 label 格式化指标值
    function fmtMetric(label, val) {
        if (val === null || val === undefined || val === '') return '—';
        const n = Number(val);
        if (!isFinite(n)) return '—';
        const key = String(label || '').toLowerCase();
        if (key.indexOf('return_pct') >= 0 || key.indexOf('total_return') >= 0 ||
            key.indexOf('max_drawdown_pct') >= 0 || key.indexOf('win_rate') >= 0) {
            return n.toFixed(2) + '%';
        }
        if (key.indexOf('sharpe') >= 0 || key.indexOf('sortino') >= 0 ||
            key.indexOf('calmar') >= 0 || key.indexOf('profit_factor') >= 0 ||
            key.indexOf('volatility') >= 0 || key.indexOf('annualized') >= 0 ||
            key.indexOf('cagr') >= 0) {
            return n.toFixed(2);
        }
        if (key.indexOf('trade_count') >= 0 || key.indexOf('count') >= 0) {
            return String(Math.round(n));
        }
        return String(n);
    }

    // 格式化变化量（带符号），按 label
    function fmtDelta(label, delta) {
        if (delta === null || delta === undefined || delta === '') return '—';
        const n = Number(delta);
        if (!isFinite(n)) return '—';
        const sign = n > 0 ? '+' : '';
        const key = String(label || '').toLowerCase();
        if (key.indexOf('return_pct') >= 0 || key.indexOf('total_return') >= 0 ||
            key.indexOf('max_drawdown_pct') >= 0 || key.indexOf('win_rate') >= 0) {
            return sign + n.toFixed(2) + '%';
        }
        if (key.indexOf('trade_count') >= 0 || key.indexOf('count') >= 0) {
            return sign + Math.round(n);
        }
        return sign + n.toFixed(2);
    }

    // 简易 JSON 语法高亮（基于 escape 后的文本做替换，避免 XSS）
    function highlightJson(jsonStr) {
        if (!jsonStr) return '';
        let safe = e(jsonStr);
        safe = safe.replace(/("(?:\\.|[^"\\])*")(\s*:)/g, '<span class="jk-key">$1</span>$2');
        safe = safe.replace(/:\s*("(?:\\.|[^"\\])*")/g, ': <span class="jk-str">$1</span>');
        safe = safe.replace(/:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g, ': <span class="jk-num">$1</span>');
        safe = safe.replace(/:\s*(true|false|null)/g, ': <span class="jk-bool">$1</span>');
        return safe;
    }

    // ===================== 初始化 =====================

    async function init() {
        const bootEl = document.getElementById('versionsBootstrap');
        if (!bootEl) {
            StockApp.toast('缺少版本页初始化数据', 'danger');
            return;
        }
        let boot;
        try { boot = JSON.parse(bootEl.textContent); }
        catch (err) { StockApp.toast('初始化数据解析失败: ' + err.message, 'danger'); return; }

        state.strategyId = boot.strategyId;
        if (!state.strategyId) {
            StockApp.toast('缺少策略 ID', 'danger');
            return;
        }

        // 返回编辑按钮
        const editLink = document.querySelector('.sv-edit-link');
        if (editLink) {
            editLink.href = StockApp.contextPath + '/quant/strategies/' + encodeURIComponent(state.strategyId) + '/edit';
        }

        setLoading(true);
        await loadDetail();
        await loadVersions();
        setLoading(false);

        renderVersionList();
        bindEvents();

        // 默认选中最新版本（版本列表已 DESC）
        if (state.versions.length) {
            selectVersion(state.versions[0].versionNo);
        } else {
            renderEmptyDetail();
        }
    }

    function setLoading(on) {
        const nameEl = document.querySelector('.sv-strategy-name');
        if (nameEl) nameEl.classList.toggle('sv-loading', on);
    }

    // ===================== 数据加载 =====================

    function loadDetail() {
        return new Promise(resolve => {
            StockApp.get(apiBase(), null, function (resp) {
                if (resp.code === 200 && resp.data) {
                    state.strategyName = resp.data.name || '';
                    state.currentVersion = resp.data.currentVersion;
                    renderHeader();
                } else {
                    StockApp.toast(resp.message || '加载策略详情失败', 'danger');
                }
                resolve();
            });
        });
    }

    function loadVersions() {
        return new Promise(resolve => {
            StockApp.get(apiBase() + '/versions', null, function (resp) {
                if (resp.code === 200 && Array.isArray(resp.data)) {
                    state.versions = resp.data;
                } else {
                    state.versions = [];
                    StockApp.toast(resp.message || '加载版本列表失败', 'danger');
                }
                resolve();
            });
        });
    }

    // 懒加载：指定版本完整 configJson
    function loadVersionJson(versionNo) {
        if (state.cache[versionNo] && state.cache[versionNo].configJson !== undefined) {
            return Promise.resolve(state.cache[versionNo].configJson);
        }
        return new Promise(resolve => {
            StockApp.get(apiBase() + '/versions/' + versionNo, null, function (resp) {
                let json = '';
                if (resp.code === 200 && resp.data) {
                    json = resp.data.configJson || '';
                } else {
                    StockApp.toast(resp.message || '加载版本配置失败', 'danger');
                }
                state.cache[versionNo] = Object.assign(state.cache[versionNo] || {}, { configJson: json });
                resolve(json);
            });
        });
    }

    // 懒加载：版本间结构化 diff
    function loadDiff(from, to) {
        const key = 'diff_' + from + '_' + to;
        if (state.cache[key]) return Promise.resolve(state.cache[key]);
        return new Promise(resolve => {
            StockApp.get(apiBase() + '/versions/diff', { from: from, to: to }, function (resp) {
                let diffs = [];
                if (resp.code === 200 && Array.isArray(resp.data)) {
                    diffs = resp.data;
                } else {
                    StockApp.toast(resp.message || '加载差异失败', 'danger');
                }
                state.cache[key] = diffs;
                resolve(diffs);
            });
        });
    }

    // 懒加载：回测对比
    function loadCompare(from, to) {
        const key = 'compare_' + from + '_' + to;
        if (state.cache[key]) return Promise.resolve(state.cache[key]);
        return new Promise(resolve => {
            StockApp.get(apiBase() + '/versions/compare', { from: from, to: to }, function (resp) {
                let metrics = [];
                if (resp.code === 200 && Array.isArray(resp.data)) {
                    metrics = resp.data;
                }
                state.cache[key] = metrics;
                resolve(metrics);
            });
        });
    }

    // ===================== 渲染：列表 =====================

    function renderHeader() {
        const nameEl = document.querySelector('.sv-strategy-name');
        if (nameEl) {
            nameEl.innerHTML = e(state.strategyName || '未命名策略') +
                ' <span class="text-muted" style="font-size:13px;font-weight:400;">· 版本历史</span>';
        }
        const metaEl = document.querySelector('.sv-header-meta');
        if (metaEl) {
            const n = state.versions.length;
            metaEl.textContent = '共 ' + n + ' 个版本' +
                (state.currentVersion != null ? ' · 当前版本 v' + state.currentVersion : '');
        }
    }

    function renderVersionList() {
        const wrap = document.getElementById('verList');
        if (!wrap) return;

        renderHeader();

        if (!state.versions.length) {
            wrap.innerHTML = '<div class="text-muted" style="padding:40px;text-align:center;">暂无版本记录</div>';
            return;
        }

        const list = state.versions.slice().sort((a, b) => (b.versionNo - a.versionNo));
        const activeVn = state.selectedVersion;

        wrap.innerHTML = list.map(v => {
            const isCurrent = state.currentVersion != null && v.versionNo === state.currentVersion;
            const isV1 = Number(v.versionNo) <= 1;
            const isActive = activeVn != null && v.versionNo === activeVn;
            return '' +
                '<div class="str-vdiff-item' + (isActive ? ' active' : '') + '" data-version-no="' + v.versionNo + '">' +
                    '<div class="str-vdiff-head">' +
                        '<span class="str-vdiff-ver">v' + e(v.versionNo) + '</span>' +
                        (isCurrent ? '<span class="str-vdiff-badge">CURRENT</span>' : '') +
                        (isV1 ? '<span class="str-vdiff-badge initial">INITIAL</span>' : '') +
                        '<span class="str-vdiff-changelog" title="' + e(v.changelog || '') + '">' +
                            e(v.changelog || '(无备注)') +
                        '</span>' +
                    '</div>' +
                    '<div class="str-vdiff-meta">' +
                        '<span>' + e(fmtTime(v.createdAt)) + '</span>' +
                    '</div>' +
                '</div>';
        }).join('');
    }

    function renderEmptyDetail() {
        const detail = document.getElementById('verDetail');
        if (detail) {
            detail.innerHTML = '<div class="text-muted" style="padding:40px;text-align:center;">暂无版本数据</div>';
        }
    }

    // ===================== 选择版本 + Tab =====================

    function selectVersion(versionNo) {
        const vn = Number(versionNo);
        state.selectedVersion = vn;

        // 更新列表 active 态
        const items = document.querySelectorAll('#verList .str-vdiff-item');
        items.forEach(function (it) {
            it.classList.toggle('active', Number(it.dataset.versionNo) === vn);
        });

        // 重置 Tab loaded 标记（切换版本后需要重新懒加载）
        state.tabLoaded = { summary: false, diff: false, backtest: false, config: false };

        renderDetailHeader(vn);
        resetTabs();
        // 默认激活 summary Tab 并加载
        activateTab('summary');
    }

    function renderDetailHeader(vn) {
        const v = findVersion(vn);
        const isV1 = vn <= 1;
        const isCurrent = state.currentVersion != null && vn === state.currentVersion;

        const titleEl = document.getElementById('detailTitle');
        if (titleEl) {
            titleEl.textContent = isV1
                ? ('v' + vn + ' 初始版本')
                : ('v' + (vn - 1) + ' → v' + vn);
        }

        const metaEl = document.getElementById('detailMeta');
        if (metaEl) {
            const parts = [];
            if (v) parts.push(fmtTime(v.createdAt));
            if (v && v.createdBy) parts.push(e(v.createdBy));
            metaEl.innerHTML = parts.map(function (p) { return '<span>' + p + '</span>'; }).join('<span class="sep">·</span>');
        }

        const actionsEl = document.getElementById('detailActions');
        if (actionsEl) {
            if (isCurrent) {
                actionsEl.innerHTML = '<span class="text-muted" style="font-size:12px;">当前版本，无需回滚</span>';
            } else {
                actionsEl.innerHTML =
                    '<button class="btn btn-outline-secondary btn-sm" data-action="rollback" data-version-no="' + vn + '">' +
                        '<i class="bi bi-arrow-counterclockwise"></i> 回滚至此版本' +
                    '</button>';
            }
        }
    }

    function resetTabs() {
        // Tab 按钮：默认全部移除 active，后面 activateTab 会加上
        const tabs = document.querySelectorAll('.str-vdiff-tab');
        tabs.forEach(function (t) { t.classList.remove('active'); });

        // 详情面板全部置空待加载
        const panes = {
            summary: 'paneSummary',
            diff: 'paneDiff',
            backtest: 'paneBacktest',
            config: 'paneConfig',
        };
        Object.keys(panes).forEach(function (k) {
            const p = document.getElementById(panes[k]);
            if (p) {
                p.style.display = 'none';
                p.classList.remove('active');
                p.innerHTML = '<div class="text-muted" style="padding:16px;">加载中…</div>';
            }
        });
    }

    function activateTab(tabName) {
        const tabBtn = document.querySelector('.str-vdiff-tab[data-tab="' + tabName + '"]');
        if (!tabBtn) return;

        // 切换按钮 active
        document.querySelectorAll('.str-vdiff-tab').forEach(function (t) { t.classList.remove('active'); });
        tabBtn.classList.add('active');

        // 切换面板显示
        const panes = { summary: 'paneSummary', diff: 'paneDiff', backtest: 'paneBacktest', config: 'paneConfig' };
        Object.keys(panes).forEach(function (k) {
            const p = document.getElementById(panes[k]);
            if (!p) return;
            const on = (k === tabName);
            p.style.display = on ? 'block' : 'none';
            p.classList.toggle('active', on);
        });

        // 懒加载：首次激活才请求数据
        if (!state.tabLoaded[tabName]) {
            state.tabLoaded[tabName] = true;
            loadTabContent(tabName);
        }
    }

    async function loadTabContent(tabName) {
        const vn = state.selectedVersion;
        if (vn == null) return;
        const isV1 = vn <= 1;

        if (tabName === 'summary') {
            await renderSummaryTab(vn, isV1);
        } else if (tabName === 'diff') {
            await renderDiffTab(vn, isV1);
        } else if (tabName === 'backtest') {
            await renderBacktestTab(vn, isV1);
        } else if (tabName === 'config') {
            await renderConfigTab(vn);
        }
    }

    // ===================== Tab: 变更摘要 =====================

    async function renderSummaryTab(vn, isV1) {
        const pane = document.getElementById('paneSummary');
        if (!pane) return;

        if (isV1) {
            pane.innerHTML = '<div class="str-rollback-tip">初始版本，无变更摘要。</div>';
            return;
        }

        const from = vn - 1;
        const to = vn;

        // 并行加载 diff 与 configJson（算大小）
        const diffs = await loadDiff(from, to);
        const json = await loadVersionJson(vn);

        let added = 0, removed = 0;
        (diffs || []).forEach(function (d) {
            const t = (d.changeType || '').toLowerCase();
            if (t === 'added') added++;
            else if (t === 'removed') removed++;
        });
        const changed = (diffs || []).length;
        const sizeBytes = json ? new Blob([json]).size : 0;
        const sizeKb = (sizeBytes / 1024).toFixed(2);

        const statsHtml =
            '<div class="str-vsum-grid">' +
                '<div class="str-vsum-cell added">' +
                    '<div class="str-vsum-num">' + added + '</div>' +
                    '<div class="str-vsum-label">新增字段</div>' +
                '</div>' +
                '<div class="str-vsum-cell removed">' +
                    '<div class="str-vsum-num">' + removed + '</div>' +
                    '<div class="str-vsum-label">删除字段</div>' +
                '</div>' +
                '<div class="str-vsum-cell modified">' +
                    '<div class="str-vsum-num">' + changed + '</div>' +
                    '<div class="str-vsum-label">变更字段</div>' +
                '</div>' +
                '<div class="str-vsum-cell">' +
                    '<div class="str-vsum-num">' + sizeKb + '</div>' +
                    '<div class="str-vsum-label">配置大小(KB)</div>' +
                '</div>' +
            '</div>';

        let changesHtml = '';
        if (!diffs || !diffs.length) {
            changesHtml = '<div class="text-muted" style="padding:8px 0;">无差异（与上一版本配置一致）</div>';
        } else {
            changesHtml = '<div class="str-change-list">' +
                diffs.map(function (d) { return renderChangeItem(d); }).join('') +
            '</div>';
        }

        const tipHtml =
            '<div class="str-rollback-tip">' +
                '⏪ 回滚说明：点击“回滚至此版本”会将配置恢复到 v' + vn + ' 状态并创建新版本，历史版本不会被删除。' +
            '</div>';

        pane.innerHTML = statsHtml + changesHtml + tipHtml;
    }

    function renderChangeItem(d) {
        const type = (d.changeType || 'modified').toLowerCase();
        const oldVal = valToStr(d.oldValue);
        const newVal = valToStr(d.newValue);
        let body = '';
        if (type === 'modified') {
            body =
                '<span class="str-change-old">' + e(oldVal) + '</span>' +
                '<span class="str-change-arrow">→</span>' +
                '<span class="str-change-new">' + e(newVal) + '</span>';
        } else if (type === 'added') {
            body = '<span class="str-change-new">' + e(newVal) + '</span>';
        } else if (type === 'removed') {
            body = '<span class="str-change-old">' + e(oldVal) + '</span>';
        }
        return '' +
            '<div class="str-change-item ' + type + '">' +
                '<span class="str-change-chip ' + type + '">' + type + '</span>' +
                '<span class="str-change-path">' + e(d.path || '') + '</span>' +
                body +
            '</div>';
    }

    // ===================== Tab: JSON Diff =====================

    async function renderDiffTab(vn, isV1) {
        const pane = document.getElementById('paneDiff');
        if (!pane) return;

        if (isV1) {
            pane.innerHTML = '<div class="text-muted" style="padding:16px;">初始版本，无 diff 基准。</div>';
            return;
        }

        const diffs = await loadDiff(vn - 1, vn);
        if (!diffs || !diffs.length) {
            pane.innerHTML = '<div class="text-muted" style="padding:16px;">无差异（与上一版本配置一致）</div>';
            return;
        }

        const html = diffs.map(function (d) { return renderDiffHunk(d); }).join('');
        pane.innerHTML = '<div class="str-diff-hunks">' + html + '</div>';
    }

    function renderDiffHunk(d) {
        const type = (d.changeType || 'modified').toLowerCase();
        let lines = '';
        if (type === 'added') {
            lines += '<div class="str-diff-line add"><span class="sign">+</span><span>' + e(valToStr(d.newValue)) + '</span></div>';
        } else if (type === 'removed') {
            lines += '<div class="str-diff-line del"><span class="sign">-</span><span>' + e(valToStr(d.oldValue)) + '</span></div>';
        } else {
            lines += '<div class="str-diff-line del"><span class="sign">-</span><span>' + e(valToStr(d.oldValue)) + '</span></div>';
            lines += '<div class="str-diff-line add"><span class="sign">+</span><span>' + e(valToStr(d.newValue)) + '</span></div>';
        }
        return '' +
            '<div class="str-diff-hunk">' +
                '<div class="str-diff-path">@@ ' + e(d.path || '') + ' @@</div>' +
                lines +
            '</div>';
    }

    // ===================== Tab: 回测对比 =====================

    async function renderBacktestTab(vn, isV1) {
        const pane = document.getElementById('paneBacktest');
        if (!pane) return;

        if (isV1) {
            pane.innerHTML = '<div class="text-muted" style="padding:16px;">初始版本，无对比基准。</div>';
            return;
        }

        const from = vn - 1;
        const to = vn;
        const metrics = await loadCompare(from, to);

        if (!metrics || !metrics.length) {
            pane.innerHTML = '<div class="text-muted" style="padding:16px;">暂无回测对比数据（回测中心对接后可用）。</div>';
            return;
        }

        const bodyHtml = metrics.map(function (m) {
            const label = m.labelCn || m.label;
            const fromVal = m.fromVal != null ? fmtMetric(m.label, m.fromVal) : '—';
            const toVal = m.toVal != null ? fmtMetric(m.label, m.toVal) : '—';
            const delta = m.delta != null ? fmtDelta(m.label, m.delta) : '—';
            const deltaCls = (Number(m.delta) || 0) >= 0 ? 'up' : 'down';
            return '' +
                '<tr>' +
                    '<td>' + e(label) + '</td>' +
                    '<td>' + e(String(fromVal)) + '</td>' +
                    '<td>' + e(String(toVal)) + '</td>' +
                    '<td class="' + deltaCls + '">' + e(String(delta)) + '</td>' +
                '</tr>';
        }).join('');

        pane.innerHTML =
            '<table class="str-compare-table">' +
                '<thead><tr><th>指标</th><th>v' + from + '</th><th>v' + to + '</th><th>变化</th></tr></thead>' +
                '<tbody>' + bodyHtml + '</tbody>' +
            '</table>';
    }

    // ===================== Tab: 完整配置 =====================

    async function renderConfigTab(vn) {
        const pane = document.getElementById('paneConfig');
        if (!pane) return;

        const json = await loadVersionJson(vn);
        const pretty = prettyJson(json);
        pane.innerHTML =
            '<pre class="str-mono str-config-pre">' +
                '<code>' + highlightJson(pretty || '(空配置)') + '</code>' +
            '</pre>';
    }

    // ===================== 回滚 =====================

    async function confirmRollback(versionNo) {
        const vn = Number(versionNo);
        const isCurrent = state.currentVersion != null && vn === state.currentVersion;
        if (isCurrent) {
            StockApp.toast('当前版本无需回滚', 'info');
            return;
        }

        const changelog = await StockApp.prompt({
            title: '回滚到 v' + vn,
            message: '将以 v' + vn + ' 的配置创建一个新版本，历史版本不会被删除。请输入本次回滚备注：',
            placeholder: '如：回滚到 v' + vn + '（修复配置错误）',
            confirmText: '确认回滚',
            cancelText: '取消',
            confirmClass: 'btn-warning',
            required: true,
            icon: 'bi-arrow-counterclockwise',
            validate: function (v) {
                if (!v || !v.trim()) return '请输入回滚备注';
                if (v.length > 200) return '备注不超过 200 字';
                return null;
            }
        });

        if (changelog == null) return;

        StockApp.post(apiBase() + '/versions/rollback',
            { targetVersion: vn, changelog: changelog.trim() },
            async function (resp) {
                if (resp.code === 200) {
                    StockApp.toast('已回滚到 v' + vn + '，新版本 v' +
                        ((resp.data && resp.data.versionNo) || '?') + ' 已生成', 'success');
                    // 清空缓存 + 重新加载 + 选中最新版本
                    state.cache = {};
                    state.selectedVersion = null;
                    await loadDetail();
                    await loadVersions();
                    renderVersionList();
                    if (state.versions.length) {
                        selectVersion(state.versions[0].versionNo);
                    } else {
                        renderEmptyDetail();
                    }
                } else {
                    let msg = resp.message || '回滚失败';
                    if (resp.data && Array.isArray(resp.data)) {
                        msg += '：' + resp.data.map(function (er) {
                            return (er.field || '') + ' ' + (er.message || '');
                        }).join('；');
                    }
                    StockApp.toast(msg, 'danger');
                }
            }
        );
    }

    // ===================== 辅助 =====================

    function findVersion(versionNo) {
        const vn = Number(versionNo);
        for (let i = 0; i < state.versions.length; i++) {
            if (Number(state.versions[i].versionNo) === vn) return state.versions[i];
        }
        return null;
    }

    // ===================== 事件绑定（委托） =====================

    function bindEvents() {
        // 版本列表点击切换
        const listEl = document.getElementById('verList');
        if (listEl) {
            listEl.addEventListener('click', function (ev) {
                const item = ev.target.closest('.str-vdiff-item');
                if (!item) return;
                const vn = item.dataset.versionNo;
                if (vn == null) return;
                selectVersion(Number(vn));
            });
        }

        // Tab 按钮 + 回滚按钮（委托在右栏容器）
        const detailEl = document.getElementById('verDetail');
        const headerEl = document.getElementById('verHeader');
        const root = detailEl || headerEl || document;

        root.addEventListener('click', function (ev) {
            const tabBtn = ev.target.closest('.str-vdiff-tab[data-tab]');
            if (tabBtn) {
                activateTab(tabBtn.dataset.tab);
                return;
            }
            const actionEl = ev.target.closest('[data-action]');
            if (actionEl) {
                const action = actionEl.dataset.action;
                const vn = actionEl.dataset.versionNo;
                if (action === 'rollback' && vn != null) {
                    confirmRollback(vn);
                }
            }
        });
    }

    return { init: init };
})();

document.addEventListener('DOMContentLoaded', () => StrategyVersions.init());
