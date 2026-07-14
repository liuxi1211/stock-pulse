/**
 * 回测中心列表页逻辑（spec 007 T4.2）。
 *
 * 数据源（统一 ApiResponse: {code, message, data}）：
 *   - GET  /api/backtest/tasks?page=1&size=100&strategyId=&status=&startDate=&endDate=
 *         → PageResult<BacktestTaskVO>（一次拉足够多，前端按 strategyId 分组）
 *   - GET  /api/backtest/tasks/{taskId}  轮询单任务进度
 *   - POST /api/backtest/tasks/{taskId}/rerun   一键重跑
 *   - DELETE /api/backtest/{backtestId}          删除
 *
 * 核心交互（spec v2.1 Requirement）：
 *   1. 策略分组折叠/展开：仅 RUNNING 任务的策略分组默认展开。
 *      点击 .bt-strat-group-head 切换（排除 head 内的 a/button）。
 *      展开态 grid-column: 1 / -1 横跨整行；折叠态 1 列。允许多个同时展开。
 *   2. 历史回测分页：展开态每页 5 条（PAGE_SIZE=5）。≤5 条不显示分页。
 *   3. RUNNING 行置顶 + 无操作按钮（���"不可取消"灰字）。
 *   4. FAILED/CANCELLED 行显示"一键重跑"。
 *   5. 对比篮：checkbox 加入浮动 tray，chip 显示"策略名 v版本 · BT-编号 区间"。
 *   6. 轮询：RUNNING/PENDING 每 3s 轮询。
 */
(function () {
    'use strict';

    const Backtest = window.Backtest || (window.Backtest = {});
    const e = StockApp.escapeHtml;

    const PAGE_SIZE = 5;
    const POLL_INTERVAL = 3000;

    const STATUS_LABEL = {
        PENDING: 'PENDING',
        RUNNING: 'RUNNING',
        SUCCESS: 'SUCCESS',
        FAILED: 'FAILED',
        CANCELLED: 'CANCELLED'
    };

    const state = {
        allTasks: [],
        groups: {},
        filters: { keyword: '', status: '' },
        compareSet: new Set(),
        pollTimers: {}
    };

    // ============ 数据加载 ============
    function loadTasks() {
        const params = { page: 1, size: 100 };
        StockApp.get('/api/backtest/tasks', params, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.allTasks = resp.data.list || resp.data.records || [];
            } else {
                state.allTasks = [];
                StockApp.toast(resp.message || '加载失败', 'warning');
            }
            render();
            startPollingForActive();
        });
    }

    // ============ 分组排序与过滤 ============
    function buildGroups() {
        const kw = state.filters.keyword.trim().toLowerCase();
        const st = state.filters.status;
        const filtered = state.allTasks.filter(function (t) {
            if (st && t.status !== st) return false;
            if (kw) {
                const hay = ((t.strategyName || '') + ' #' + (t.backtestId || t.id || '')).toLowerCase();
                if (hay.indexOf(kw) < 0) return false;
            }
            return true;
        });

        const groups = {};
        filtered.forEach(function (t) {
            const key = t.strategyId != null ? String(t.strategyId) : ('s_' + (t.strategyName || 'unknown'));
            if (!groups[key]) {
                groups[key] = {
                    strategyId: t.strategyId,
                    strategyName: t.strategyName || '未命名策略',
                    versionNo: t.versionNo,
                    tasks: []
                };
            }
            groups[key].tasks.push(t);
        });

        Object.keys(groups).forEach(function (key) {
            const g = groups[key];
            g.tasks.sort(function (a, b) {
                const ra = (a.status === 'RUNNING' || a.status === 'PENDING') ? 1 : 0;
                const rb = (b.status === 'RUNNING' || b.status === 'PENDING') ? 1 : 0;
                if (ra !== rb) return rb - ra;
                return (b.createdAt || '').localeCompare(a.createdAt || '');
            });
            g.hasRunning = g.tasks.some(function (t) { return t.status === 'RUNNING' || t.status === 'PENDING'; });
            const successTasks = g.tasks.filter(function (t) { return t.status === 'SUCCESS'; });
            g.latestSuccess = successTasks[0] || null;
            g.defaultExpanded = g.hasRunning;
        });

        state.groups = groups;
        return Object.values(groups);
    }

    // ============ 渲染 ============
    function render() {
        const grid = document.getElementById('stratGrid');
        const groups = buildGroups();

        const total = state.allTasks.length;
        document.getElementById('headerCount').textContent = total + ' 次回测';
        document.getElementById('filterSummary').textContent = groups.length + ' 个策略 · ' + total + ' 次回测';

        if (groups.length === 0) {
            grid.innerHTML = ''
                + '<div class="bt-empty-state">'
                + '  <i class="bi bi-inboxes"></i>'
                + '  <div class="bt-empty-state-title">暂无回测任务</div>'
                + '  <div class="bt-empty-state-sub">去新建一个回测开始验证你的策略</div>'
                + '  <a href="' + StockApp.contextPath + '/quant/backtests/new" class="btn-bt btn-bt-primary btn-bt-sm">'
                + '    <i class="bi bi-plus-lg"></i> 新建回测'
                + '  </a>'
                + '</div>';
            return;
        }

        grid.innerHTML = groups.map(renderGroup).join('');

        bindGroupEvents();
        groups.forEach(function (g) {
            if (g.defaultExpanded) renderGroupPager(g);
        });
    }

    function renderGroup(g) {
        const latest = g.latestSuccess;
        const latestText = latest
            ? '<span class="item">最新：<span class="mono" style="color:' + valColor(latest.metrics && latest.metrics.totalReturnPct) + ';font-weight:600;">' + formatPct(latest.metrics && latest.metrics.totalReturnPct) + '</span></span>'
            : (g.hasRunning ? '<span class="item" style="color:var(--accent-blue-light);">运行中…</span>' : '<span class="item" style="color:var(--accent-orange);">最新：未成功</span>');

        return ''
            + '<div class="bt-strat-group' + (g.defaultExpanded ? ' expanded' : '') + '" data-key="' + e(g.strategyId != null ? g.strategyId : g.strategyName) + '">'
            + '  <div class="bt-strat-group-head">'
            + '    <div class="bt-strat-group-title">'
            + '      <span class="arrow"><i class="bi bi-chevron-down"></i></span>'
            + '      <i class="bi bi-graph-up" style="color:var(--accent-blue);font-size:15px;"></i>'
            + '      <span>' + e(g.strategyName) + '</span>'
            + '      <span class="ver">v' + e(g.versionNo || '1') + '</span>'
            + '      <span class="mono" style="font-size:10.5px;color:var(--text-muted);margin-left:2px;">S-' + e(g.strategyId != null ? g.strategyId : '?') + '</span>'
            + '    </div>'
            + '    <div class="bt-strat-group-meta">'
            + '      <span class="item"><i class="bi bi-flask"></i>' + g.tasks.length + ' 次回测</span>'
            +       latestText
            + '    </div>'
            + '  </div>'
            + '  <div class="bt-strat-group-body" id="body-' + e(g.strategyId != null ? g.strategyId : g.strategyName) + '">'
            +     g.tasks.map(renderRow).join('')
            + '    <div class="bt-pager" id="pager-' + e(g.strategyId != null ? g.strategyId : g.strategyName) + '"></div>'
            + '  </div>'
            + '</div>';
    }

    function renderRow(t) {
        const id = t.backtestId || t.id;
        const status = t.status || 'PENDING';
        const statusCls = 'bt-' + status.toLowerCase();
        const startDate = t.startDate || (t.config && t.config.startDate) || '';
        const endDate = t.endDate || (t.config && t.config.endDate) || '';
        const benchmark = t.benchmark || (t.config && t.config.benchmark) || '沪深300';
        const initialCash = t.initialCash || (t.config && t.config.initialCash) || 100000;
        const createdAt = formatTime(t.createdAt);
        const rangeBadge = rangeBadgeHtml(startDate, endDate);
        const isChecked = state.compareSet.has(String(id));
        const isRunning = (status === 'RUNNING' || status === 'PENDING');
        const isFailed = (status === 'FAILED' || status === 'CANCELLED');

        let metricsHtml = '';
        if (status === 'RUNNING' || status === 'PENDING') {
            const progress = t.progress != null ? Math.round(t.progress) : 0;
            metricsHtml = '<div class="bt-row-metrics" style="opacity:.6;">'
                + '<div class="bt-row-metric"><div class="bt-row-metric-lbl">进度</div><div class="bt-row-metric-val" style="color:var(--accent-blue-light);">' + progress + '%</div></div>'
                + '</div>';
        } else if (status === 'SUCCESS') {
            const m = t.metrics || {};
            metricsHtml = '<div class="bt-row-metrics">'
                + metricCell('收益', formatPct(m.totalReturnPct), valColor(m.totalReturnPct))
                + metricCell('夏普', fmt(m.sharpeRatio, 2), '')
                + metricCell('回撤', formatPct(m.maxDrawdownPct), 'var(--fall-light)')
                + metricCell('超额', formatPct(m.excessReturnPct), valColor(m.excessReturnPct))
                + '</div>';
        } else {
            metricsHtml = '<div class="bt-row-metrics"></div>';
        }

        let actionsHtml = '';
        if (isRunning) {
            actionsHtml = '<div class="bt-row-actions"><span style="font-size:10.5px;color:var(--text-muted);font-family:var(--font-mono);">不可取消</span></div>';
        } else if (isFailed) {
            actionsHtml = '<div class="bt-row-actions">'
                + '<button type="button" class="btn-bt btn-bt-warning btn-bt-sm" data-act="rerun" data-id="' + e(id) + '"><i class="bi bi-arrow-repeat"></i> 一键重跑</button>'
                + '<button type="button" class="bt-icon-btn danger" data-act="delete" data-id="' + e(id) + '" title="删除"><i class="bi bi-trash"></i></button>'
                + '</div>';
        } else {
            actionsHtml = '<div class="bt-row-actions">'
                + '<a href="' + StockApp.contextPath + '/quant/backtests/' + e(id) + '/report" class="bt-icon-btn" title="查看报告"><i class="bi bi-file-earmark-text"></i></a>'
                + '<button type="button" class="bt-icon-btn" data-act="compare-add" data-id="' + e(id) + '" title="加入对比"><i class="bi bi-bar-chart-line"></i></button>'
                + '<button type="button" class="bt-icon-btn warn" data-act="rerun" data-id="' + e(id) + '" title="重跑"><i class="bi bi-arrow-repeat"></i></button>'
                + '</div>';
        }

        const errBanner = '';
        const rowCls = isRunning ? ' is-running' : (isFailed ? ' is-failed' : '');
        const checkboxDisabled = isRunning ? ' disabled' : '';
        const checkboxChecked = isChecked ? ' checked' : '';

        return ''
            + '<div class="bt-row' + rowCls + '" data-id="' + e(id) + '">'
            + '  <div class="bt-row-checkbox' + checkboxChecked + checkboxDisabled + '" data-act="toggle-compare" data-id="' + e(id) + '"></div>'
            + '  <div class="bt-row-main">'
            + '    <div class="bt-row-range">'
            + '      <span>' + e(startDate) + ' → ' + e(endDate) + '</span>'
            +       (rangeBadge ? ' ' + rangeBadge : '')
            + '    </div>'
            + '    <div class="bt-row-sub">#BT-' + e(id) + ' · ' + e(benchmark) + ' · ¥' + e(formatNumber(initialCash, 0)) + ' · ' + e(createdAt) + (status === 'FAILED' && t.errorMessage ? ' 失败' : '') + '</div>'
            +     (status === 'FAILED' && t.errorMessage ? '<div class="bt-error-banner"><strong>错误：</strong>' + e(t.errorMessage) + '</div>' : errBanner)
            + '  </div>'
            +   metricsHtml
            + '  <span class="bt-status ' + statusCls + '">' + (STATUS_LABEL[status] || status) + '</span>'
            +   actionsHtml
            + '</div>';
    }

    function metricCell(lbl, val, colorCls) {
        const cls = colorCls === 'var(--fall-light)' ? 'down' : (colorCls === 'var(--rise-light)' ? 'up' : '');
        return '<div class="bt-row-metric"><div class="bt-row-metric-lbl">' + e(lbl) + '</div><div class="bt-row-metric-val ' + cls + '">' + e(val) + '</div></div>';
    }

    function rangeBadgeHtml(start, end) {
        if (!start || !end) return '';
        const days = dayDiff(start, end);
        if (days >= 700) return '<span class="bt-range-badge full"><i class="bi bi-book"></i>全历史</span>';
        if (days >= 330) return '<span class="bt-range-badge recent"><i class="bi bi-clock"></i>近1年</span>';
        if (days >= 170) return '<span class="bt-range-badge"><i class="bi bi-clock"></i>近6月</span>';
        if (days > 0) return '<span class="bt-range-badge">' + Math.round(days / 30) + ' 个月</span>';
        return '';
    }

    // ============ 分组事件绑定（折叠/分页/操作） ============
    function bindGroupEvents() {
        document.querySelectorAll('#stratGrid .bt-strat-group-head').forEach(function (head) {
            head.addEventListener('click', function (ev) {
                if (ev.target.closest('a, button')) return;
                const group = head.closest('.bt-strat-group');
                const wasExpanded = group.classList.contains('expanded');
                group.classList.toggle('expanded');
                if (!wasExpanded) {
                    const key = group.getAttribute('data-key');
                    const g = findGroupByKey(key);
                    if (g) renderGroupPager(g);
                }
            });
        });

        document.querySelectorAll('#stratGrid .bt-row [data-act]').forEach(function (el) {
            el.addEventListener('click', function (ev) {
                ev.stopPropagation();
                const act = el.getAttribute('data-act');
                const id = el.getAttribute('data-id');
                if (act === 'toggle-compare') {
                    if (el.classList.contains('disabled')) return;
                    toggleCompare(id);
                } else if (act === 'rerun') {
                    rerunTask(id);
                } else if (act === 'delete') {
                    deleteTask(id);
                } else if (act === 'compare-add') {
                    addToCompare(id);
                }
            });
        });
    }

    function findGroupByKey(key) {
        const groups = Object.values(state.groups);
        return groups.find(function (g) { return String(g.strategyId != null ? g.strategyId : g.strategyName) === key; });
    }

    // ============ 分页 ============
    function renderGroupPager(g) {
        const key = (g.strategyId != null ? g.strategyId : g.strategyName);
        const body = document.getElementById('body-' + key);
        const pager = document.getElementById('pager-' + key);
        if (!body || !pager) return;
        const rows = Array.prototype.slice.call(body.querySelectorAll('.bt-row'));
        if (rows.length <= PAGE_SIZE) { pager.style.display = 'none'; rows.forEach(function (r) { r.style.display = ''; }); return; }
        pager.style.display = '';

        const totalPages = Math.ceil(rows.length / PAGE_SIZE);
        let currentPage = parseInt(pager.getAttribute('data-page'), 10) || 1;
        if (currentPage > totalPages) currentPage = 1;

        function renderPage(page) {
            currentPage = page;
            pager.setAttribute('data-page', String(page));
            const start = (page - 1) * PAGE_SIZE;
            const end = start + PAGE_SIZE;
            rows.forEach(function (row, idx) { row.style.display = (idx >= start && idx < end) ? '' : 'none'; });
            let html = '<div class="bt-pager-info">第 ' + (start + 1) + '-' + Math.min(end, rows.length) + ' 条 / 共 ' + rows.length + ' 条</div>';
            html += '<div class="bt-pager-controls">';
            html += '<button class="bt-page-btn ' + (page === 1 ? 'disabled' : '') + '" data-pg="' + (page - 1) + '" ' + (page === 1 ? 'disabled' : '') + '>‹</button>';
            for (let i = 1; i <= totalPages; i++) {
                html += '<button class="bt-page-btn ' + (i === page ? 'active' : '') + '" data-pg="' + i + '">' + i + '</button>';
            }
            html += '<button class="bt-page-btn ' + (page === totalPages ? 'disabled' : '') + '" data-pg="' + (page + 1) + '" ' + (page === totalPages ? 'disabled' : '') + '>›</button>';
            html += '</div>';
            pager.innerHTML = html;
            pager.querySelectorAll('.bt-page-btn:not(.disabled)').forEach(function (btn) {
                btn.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    renderPage(parseInt(btn.getAttribute('data-pg'), 10));
                });
            });
        }
        renderPage(currentPage);
    }

    // ============ 对比篮 ============
    function toggleCompare(id) {
        if (state.compareSet.has(id)) {
            state.compareSet.delete(id);
        } else {
            state.compareSet.add(id);
        }
        refreshCompareTray();
        refreshCheckboxState(id);
    }

    function addToCompare(id) {
        state.compareSet.add(id);
        refreshCompareTray();
        refreshCheckboxState(id);
        StockApp.toast('已加入对比篮', 'success');
    }

    function refreshCheckboxState(id) {
        document.querySelectorAll('#stratGrid .bt-row-checkbox[data-id="' + id + '"]').forEach(function (cb) {
            if (state.compareSet.has(id)) cb.classList.add('checked'); else cb.classList.remove('checked');
        });
    }

    function refreshCompareTray() {
        const tray = document.getElementById('compareTray');
        const chipsBox = document.getElementById('compareChips');
        const ids = Array.from(state.compareSet);
        const badge = document.getElementById('compareCountBadge');

        if (ids.length === 0) {
            tray.style.display = 'none';
            if (badge) { badge.style.display = 'none'; badge.textContent = '0'; }
            return;
        }
        tray.style.display = '';
        tray.classList.add('show');
        if (badge) { badge.style.display = ''; badge.textContent = String(ids.length); }

        const taskMap = {};
        state.allTasks.forEach(function (t) { taskMap[String(t.backtestId || t.id)] = t; });

        chipsBox.innerHTML = ids.map(function (id) {
            const t = taskMap[id];
            if (!t) return '<span class="bt-compare-chip">#BT-' + e(id) + ' <span class="x" data-id="' + e(id) + '">✕</span></span>';
            const name = t.strategyName || '?';
            const ver = t.versionNo || '?';
            const start = t.startDate || (t.config && t.config.startDate) || '';
            const end = t.endDate || (t.config && t.config.endDate) || '';
            return '<span class="bt-compare-chip">' + e(name) + ' v' + e(ver) + ' · BT-' + e(id) + ' ' + e(shortRange(start, end)) + ' <span class="x" data-id="' + e(id) + '">✕</span></span>';
        }).join('');

        chipsBox.querySelectorAll('.x').forEach(function (x) {
            x.addEventListener('click', function (ev) {
                ev.stopPropagation();
                const id = x.getAttribute('data-id');
                state.compareSet.delete(id);
                refreshCompareTray();
                refreshCheckboxState(id);
            });
        });

        document.getElementById('btnStartCompare').setAttribute('href', StockApp.contextPath + '/quant/backtests/compare?ids=' + ids.join(','));
    }

    // ============ 操作 ============
    function rerunTask(id) {
        StockApp.post('/api/backtest/tasks/' + id + '/rerun', null, function (resp) {
            if (resp.code === 200) {
                StockApp.toast('已发起重跑', 'success');
                loadTasks();
            } else {
                StockApp.toast(resp.message || '重跑失败', 'danger');
            }
        });
    }

    function deleteTask(id) {
        StockApp.confirm({
            title: '删除回测',
            message: '确定删除回测 #BT-' + id + ' 吗？此操作不可恢复。',
            confirmText: '删除',
            confirmClass: 'btn-bt btn-bt-danger btn-bt-sm',
            icon: 'bi-trash'
        }).then(function (ok) {
            if (!ok) return;
            fetch(StockApp.contextPath + '/api/backtest/' + id, { method: 'DELETE', headers: { 'Accept': 'application/json' } })
                .then(function (r) { return r.json(); })
                .then(function (resp) {
                    if (resp.code === 200) {
                        StockApp.toast('已删除', 'success');
                        state.compareSet.delete(String(id));
                        loadTasks();
                    } else {
                        StockApp.toast(resp.message || '删除失败', 'danger');
                    }
                })
                .catch(function (err) { StockApp.toast('删除失败: ' + err.message, 'danger'); });
        });
    }

    // ============ 轮询 ============
    function startPollingForActive() {
        Object.keys(state.pollTimers).forEach(function (k) {
            clearInterval(state.pollTimers[k]);
            delete state.pollTimers[k];
        });
        state.allTasks.forEach(function (t) {
            const id = t.backtestId || t.id;
            if (t.status === 'RUNNING' || t.status === 'PENDING') {
                state.pollTimers[id] = setInterval(function () { pollTask(id); }, POLL_INTERVAL);
            }
        });
    }

    function pollTask(id) {
        fetch(StockApp.contextPath + '/api/backtest/tasks/' + id, { headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (resp.code !== 200 || !resp.data) return;
                const t = resp.data;
                const idx = state.allTasks.findIndex(function (x) { return String(x.backtestId || x.id) === String(id); });
                if (idx < 0) return;
                const prevStatus = state.allTasks[idx].status;
                state.allTasks[idx] = Object.assign({}, state.allTasks[idx], t);
                if (t.status !== 'RUNNING' && t.status !== 'PENDING') {
                    clearInterval(state.pollTimers[id]);
                    delete state.pollTimers[id];
                    if (prevStatus !== t.status) {
                        StockApp.toast('#BT-' + id + ' 已' + (STATUS_LABEL[t.status] || t.status), 'info');
                    }
                    render();
                    startPollingForActive();
                } else {
                    render();
                }
            })
            .catch(function () { /* 静默 */ });
    }

    // ============ 工具 ============
    function valColor(v) {
        if (v == null || isNaN(v)) return '';
        return Number(v) > 0 ? 'var(--rise-light)' : (Number(v) < 0 ? 'var(--fall-light)' : '');
    }

    function formatPct(v) {
        if (v == null || isNaN(v)) return '—';
        const n = Number(v);
        const sign = n > 0 ? '+' : '';
        return sign + n.toFixed(2) + '%';
    }

    function fmt(v, d) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toFixed(d);
    }

    function formatNumber(v, d) {
        if (v == null || isNaN(v)) return '—';
        return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
    }

    function formatTime(s) {
        if (!s) return '';
        const d = new Date(s);
        if (isNaN(d.getTime())) return s;
        const now = Date.now();
        const diff = now - d.getTime();
        if (diff < 60000) return '刚刚';
        if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
        if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
        return d.toISOString().slice(0, 10);
    }

    function shortRange(start, end) {
        if (!start || !end) return '';
        const days = dayDiff(start, end);
        if (days >= 700) return '全历史';
        if (days >= 330) return '近1年';
        if (days >= 170) return '近6月';
        return Math.round(days / 30) + '月';
    }

    function dayDiff(start, end) {
        try {
            const a = new Date(start);
            const b = new Date(end);
            return Math.round((b.getTime() - a.getTime()) / 86400000);
        } catch (err) { return 0; }
    }

    // ============ 入口 ============
    Backtest.initList = function () {
        const kw = document.getElementById('filterKeyword');
        const st = document.getElementById('filterStatus');
        let kwTimer = null;
        if (kw) kw.addEventListener('input', function () {
            clearTimeout(kwTimer);
            kwTimer = setTimeout(function () {
                state.filters.keyword = kw.value;
                render();
            }, 200);
        });
        if (st) st.addEventListener('change', function () {
            state.filters.status = st.value;
            render();
        });

        const clearBtn = document.getElementById('btnClearCompare');
        if (clearBtn) clearBtn.addEventListener('click', function () {
            state.compareSet.clear();
            refreshCompareTray();
            document.querySelectorAll('#stratGrid .bt-row-checkbox.checked').forEach(function (cb) { cb.classList.remove('checked'); });
        });

        loadTasks();
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (document.getElementById('backtestListPage')) Backtest.initList();
    });
})();
