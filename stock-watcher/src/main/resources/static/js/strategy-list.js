/**
 * 策略管理列表页逻辑（spec 004 · 对齐原型）。
 * - 列表查询：GET /api/strategies（PageResult{list,total,page,size}）
 * - 统计聚合：GET /api/strategies/stats（{total,verified,active,draft,archived}）
 * - 模板下拉：GET /api/strategies/templates
 * - 软删除：  DELETE /api/strategies/{id}（status 置 ARCHIVED）
 *
 * 枚举下拉数据来自 list.html 内联的 #enumOptions（[code,label] 二元组数组）。
 */
const StrategyList = (function () {
    'use strict';

    const e = StockApp.escapeHtml;

    const STATUS_BADGE = {
        DRAFT:    { cls: 'str-status-draft',    label: 'DRAFT' },
        VERIFIED: { cls: 'str-status-verified', label: 'VERIFIED' },
        ACTIVE:   { cls: 'str-status-active',   label: 'ACTIVE' },
        ARCHIVED: { cls: 'str-status-archived', label: 'ARCHIVED' },
    };

    const CAT_BADGE = {
        TECHNICAL:   { cls: 'str-cat-tech',   label: '技术面' },
        FUNDAMENTAL: { cls: 'str-cat-fund',   label: '基本面' },
        MIXED:       { cls: 'str-cat-mixed',  label: '混合' },
        CUSTOM:      { cls: 'str-cat-custom', label: '自定义' },
    };

    const CARD_ACCENT = {
        TECHNICAL:   'linear-gradient(135deg, #3b82f6, #06b6d4)',
        FUNDAMENTAL: 'linear-gradient(135deg, #8b5cf6, #3b82f6)',
        MIXED:       'linear-gradient(135deg, #f97316, #ef4444)',
        CUSTOM:      'linear-gradient(135deg, #22c55e, #06b6d4)',
    };

    const SCOPE_LABEL = {
        single:    'SINGLE',
        portfolio: 'PORTFOLIO',
        mixed:     'MIXED',
    };

    const state = {
        page: 1,
        size: 12,
        total: 0,
        filters: { keyword: '', category: '', status: '', scope: '' },
        templates: [],
        enumOptions: { category: [], scope: [], status: [] },
    };

    function loadEnumOptions() {
        const el = document.getElementById('enumOptions');
        if (!el) return;
        try {
            state.enumOptions = JSON.parse(el.textContent.trim());
        } catch (err) {
            StockApp.toast('枚举配置解析失败', 'danger');
            return;
        }
        fillSelect('filterCategory', state.enumOptions.category);
        fillSelect('filterScope', state.enumOptions.scope);
        fillSelect('filterStatus', state.enumOptions.status);
    }

    function fillSelect(selectId, pairs) {
        const sel = document.getElementById(selectId);
        if (!sel || !Array.isArray(pairs)) return;
        const html = pairs.map(p => {
            const code = e(p[0]);
            const label = e(p[1]);
            return `<option value="${code}">${label}</option>`;
        }).join('');
        sel.insertAdjacentHTML('beforeend', html);
    }

    function enumLabel(kind, code) {
        if (!code) return '';
        const pairs = state.enumOptions[kind] || [];
        const hit = pairs.find(p => p[0] === code);
        return hit ? hit[1] : code;
    }

    function loadTemplates() {
        StockApp.get('/api/strategies/templates', null, function (resp) {
            const menu = document.getElementById('templateMenu');
            if (!menu) return;
            if (resp.code !== 200 || !Array.isArray(resp.data)) {
                menu.innerHTML = '<li class="dropdown-item-text text-muted small">模板加载失败</li>';
                return;
            }
            state.templates = resp.data;
            if (!state.templates.length) {
                menu.innerHTML = '<li class="dropdown-item-text text-muted small">暂无模板</li>';
                return;
            }
            menu.innerHTML = state.templates.map(t => `
                <li>
                    <a class="dropdown-item" href="${StockApp.contextPath}/quant/strategies/new?templateId=${encodeURIComponent(t.id)}">
                        <div class="fw-medium">${e(t.name)}</div>
                        <div class="small text-muted text-truncate" style="max-width:240px;">${e(t.description || '')}</div>
                    </a>
                </li>`).join('');
        });
    }

    function loadStats() {
        StockApp.get('/api/strategies/stats', null, function (resp) {
            if (resp.code !== 200 || !resp.data) return;
            const d = resp.data;
            setText('statTotal', d.total != null ? d.total : '—');
            setText('statVerified', d.verified != null ? d.verified : '—');
            setText('statActive', d.active != null ? d.active : '—');
            setText('statDraft', d.draft != null ? d.draft : '—');
            setText('headerCountBadge', d.total != null ? d.total : '—');
            const badge = document.getElementById('strategyCountBadge');
            if (badge) badge.textContent = d.total != null ? d.total : '—';
        });
    }

    function setText(id, v) {
        const el = document.getElementById(id);
        if (el) el.textContent = v;
    }

    function load() {
        const grid = document.getElementById('strategyGrid');
        if (grid) grid.innerHTML = '<div class="str-empty"><i class="bi bi-hourglass-split"></i><div class="str-empty-t">加载中…</div></div>';
        document.getElementById('strategyPaginationWrap').style.display = 'none';

        const params = Object.assign({ page: state.page, size: state.size }, state.filters);
        StockApp.get('/api/strategies', params, function (resp) {
            if (resp.code !== 200) {
                renderError(resp.message || '加载策略列表失败');
                return;
            }
            const data = resp.data || {};
            const list = data.list || [];
            state.total = data.total != null ? data.total : list.length;
            renderCards(list);
            renderPagination();
        });
    }

    function renderError(msg) {
        const grid = document.getElementById('strategyGrid');
        grid.innerHTML = `<div class="str-empty"><i class="bi bi-exclamation-triangle" style="color:var(--rise-light);opacity:.6;"></i><div class="str-empty-t">${e(msg)}</div></div>`;
        document.getElementById('strategyPaginationWrap').style.display = 'none';
    }

    function renderCards(list) {
        const grid = document.getElementById('strategyGrid');
        if (!list.length) {
            grid.innerHTML = `
                <div class="str-empty">
                    <i class="bi bi-inbox"></i>
                    <div class="str-empty-t">暂无策略</div>
                    <div class="str-empty-s">点击右上角「新建策略」开始创建</div>
                </div>`;
            return;
        }
        grid.innerHTML = list.map(renderCard).join('');
    }

    function renderCard(s) {
        const id = s.strategyId || s.id || '';
        const name = s.name || '未命名策略';
        const desc = s.description || '暂无描述';
        const st = STATUS_BADGE[s.status] || { cls: 'str-status-draft', label: s.status || '—' };
        const cat = CAT_BADGE[s.category] || { cls: 'str-cat-custom', label: enumLabel('category', s.category) || s.category || '—' };
        const accent = CARD_ACCENT[s.category] || CARD_ACCENT.CUSTOM;
        const scopeLabel = SCOPE_LABEL[s.scope] || (s.scope ? s.scope.toUpperCase() : '—');
        const version = s.currentVersion != null ? ('v' + s.currentVersion) : '';
        const updated = formatTime(s.updatedAt || s.createdAt);
        const errCnt = s.validationErrorCount || 0;
        const hasError = errCnt > 0;

        const tagsHtml = (s.tags || []).slice(0, 3)
            .map(t => `<span class="str-card-tag">#${e(t)}</span>`).join('');

        const retPct = num(s.lastReturnPct);
        const sharpe = num(s.lastSharpe);
        const ddPct = num(s.lastMaxDrawdownPct);

        const editHref = `${StockApp.contextPath}/quant/strategies/${encodeURIComponent(id)}/edit`;
        const verHref = `${StockApp.contextPath}/quant/strategies/${encodeURIComponent(id)}/versions`;
        const backtestDisabled = (s.status === 'DRAFT' && hasError) || !s.lastReturnPct;

        return `
            <div class="str-strategy-card${hasError ? ' has-error' : ''}" style="--card-accent: ${accent};"
                 data-id="${e(id)}">
                <div class="str-card-head">
                    <div style="min-width:0;flex:1;">
                        <div class="str-card-title">${e(name)}</div>
                        <div class="str-card-sub">
                            <span style="opacity:.7;">${e(id ? id.slice(0, 12) + '…' : '')}</span>
                            ${version ? `<span class="str-ver-tag">${e(version)}</span>` : ''}
                        </div>
                    </div>
                    <span class="str-status-badge ${st.cls}">${e(st.label)}</span>
                </div>
                <div class="str-card-desc">${e(desc)}</div>
                <div class="str-card-meta">
                    <span class="str-cat-badge ${cat.cls}">${e(cat.label)}</span>
                    <span class="str-scope-badge">${e(scopeLabel)}</span>
                    ${tagsHtml}
                    ${hasError ? `<span class="str-card-tag" style="color:var(--rise-light);background:var(--rise-bg);border:1px solid var(--rise-glow);"><i class="bi bi-exclamation-triangle" style="margin-right:2px;"></i>${errCnt} 校验错误</span>` : ''}
                </div>
                <div class="str-card-stats">
                    <div class="str-cstat">
                        <div class="str-cstat-val ${pctClass(retPct)}">${retPct !== null ? fmtPct(retPct) : '—'}</div>
                        <div class="str-cstat-lbl">累计收益</div>
                    </div>
                    <div class="str-cstat">
                        <div class="str-cstat-val">${sharpe !== null ? sharpe.toFixed(2) : '—'}</div>
                        <div class="str-cstat-lbl">夏普</div>
                    </div>
                    <div class="str-cstat">
                        <div class="str-cstat-val down">${ddPct !== null ? '-' + fmtPct(ddPct) : '—'}</div>
                        <div class="str-cstat-lbl">最大回撤</div>
                    </div>
                </div>
                <div class="str-card-foot">
                    <div class="str-card-time"><i class="bi bi-clock" style="margin-right:3px;opacity:.6;"></i>${e(updated)}</div>
                    <div class="str-card-actions">
                        <a class="str-icon-btn" href="${editHref}" title="编辑"><i class="bi bi-pencil"></i></a>
                        <a class="str-icon-btn" href="${verHref}" title="版本"><i class="bi bi-clock-history"></i></a>
                        <button class="str-icon-btn${backtestDisabled ? ' disabled' : ''}" title="回测"
                                ${backtestDisabled ? 'disabled' : `data-action="backtest" data-id="${e(id)}"`}><i class="bi bi-caret-right-fill"></i></button>
                        <button class="str-icon-btn danger" title="归档" data-action="delete" data-id="${e(id)}" data-name="${e(name)}"><i class="bi bi-trash3"></i></button>
                    </div>
                </div>
            </div>`;
    }

    function num(v) {
        if (v === null || v === undefined || v === '') return null;
        const n = Number(v);
        return isFinite(n) ? n : null;
    }

    function fmtPct(v) {
        if (v === null) return '—';
        return (v >= 0 ? '+' : '') + Number(v).toFixed(1) + '%';
    }

    function pctClass(v) {
        if (v === null) return '';
        return v >= 0 ? 'up' : 'down';
    }

    function renderPagination() {
        const wrap = document.getElementById('strategyPaginationWrap');
        const span = document.getElementById('strategyPagination');
        const totalPages = Math.max(1, Math.ceil(state.total / state.size));
        if (state.total === 0 || totalPages <= 1) {
            wrap.style.display = 'none';
            return;
        }
        wrap.style.display = 'flex';

        const cur = state.page;
        const items = [];
        range(cur, totalPages).forEach(p => {
            if (p === '...') {
                items.push('<button class="str-page-btn disabled">…</button>');
            } else {
                items.push(`<button class="str-page-btn${p === cur ? ' active' : ''}" data-page="${p}">${p}</button>`);
            }
        });
        span.innerHTML = items.join('');

        wrap.querySelectorAll('.str-page-btn[data-rel="prev"]').forEach(b => b.classList.toggle('disabled', cur === 1));
        wrap.querySelectorAll('.str-page-btn[data-rel="next"]').forEach(b => b.classList.toggle('disabled', cur === totalPages));
    }

    function range(cur, total) {
        const out = [];
        const window = 1;
        for (let i = 1; i <= total; i++) {
            if (i === 1 || i === total || Math.abs(i - cur) <= window) {
                out.push(i);
            } else if (out[out.length - 1] !== '...') {
                out.push('...');
            }
        }
        return out;
    }

    function confirmDelete(id, name) {
        StockApp.confirm({
            title: '归档策略',
            message: '确认归档策略「' + name + '」？归档后进入 ARCHIVED 状态，版本历史保留。',
            confirmText: '归档',
            cancelText: '取消',
            confirmClass: 'btn-danger',
            icon: 'bi-trash3-fill',
        }).then(ok => {
            if (!ok) return;
            doDelete(id);
        });
    }

    function doDelete(id) {
        fetch(StockApp.contextPath + '/api/strategies/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: { 'Accept': 'application/json' },
        })
            .then(r => r.json())
            .then(resp => {
                if (resp.code === 200) {
                    StockApp.toast(resp.message || '已归档', 'success');
                    if (state.page > 1 && state.total % state.size === 1) state.page--;
                    load();
                    loadStats();
                } else {
                    StockApp.toast(resp.message || '归档失败', 'danger');
                }
            })
            .catch(err => StockApp.toast('归档失败: ' + err.message, 'danger'));
    }

    function formatTime(t) {
        if (!t) return '—';
        const s = String(t);
        return s.length >= 10 ? s.slice(0, 10) : s;
    }

    function bindEvents() {
        document.getElementById('btnRefresh').addEventListener('click', () => {
            loadStats();
            load();
        });

        document.getElementById('filterKeyword').addEventListener('keydown', ev => {
            if (ev.key === 'Enter') {
                ev.preventDefault();
                collectFilters();
                state.page = 1;
                load();
            }
        });

        ['filterCategory', 'filterScope', 'filterStatus'].forEach(id => {
            document.getElementById(id).addEventListener('change', () => {
                collectFilters();
                state.page = 1;
                load();
            });
        });

        document.getElementById('strategyGrid').addEventListener('click', ev => {
            const card = ev.target.closest('.str-strategy-card');
            const btn = ev.target.closest('[data-action]');
            if (btn) {
                ev.stopPropagation();
                const action = btn.dataset.action;
                if (action === 'delete') {
                    confirmDelete(btn.dataset.id, btn.dataset.name);
                } else if (action === 'backtest') {
                    StockApp.toast('回测中心即将开放，敬请期待', 'info');
                }
                return;
            }
            if (card) {
                const id = card.dataset.id;
                if (id) location.href = StockApp.contextPath + '/quant/strategies/' + encodeURIComponent(id) + '/edit';
            }
        });

        document.getElementById('strategyPaginationWrap').addEventListener('click', ev => {
            const btn = ev.target.closest('.str-page-btn');
            if (!btn || btn.classList.contains('disabled') || btn.classList.contains('active')) return;
            const rel = btn.dataset.rel;
            const totalPages = Math.max(1, Math.ceil(state.total / state.size));
            if (rel === 'prev' && state.page > 1) state.page--;
            else if (rel === 'next' && state.page < totalPages) state.page++;
            else if (btn.dataset.page) state.page = parseInt(btn.dataset.page, 10);
            else return;
            load();
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    }

    function collectFilters() {
        state.filters.keyword = document.getElementById('filterKeyword').value.trim();
        state.filters.category = document.getElementById('filterCategory').value;
        state.filters.scope = document.getElementById('filterScope').value;
        state.filters.status = document.getElementById('filterStatus').value;
    }

    function init() {
        loadEnumOptions();
        loadTemplates();
        loadStats();
        bindEvents();
        load();
    }

    document.addEventListener('DOMContentLoaded', init);

    return { init, load, loadStats };
})();
