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
    };

    // 状态流转动作配置：当前状态 -> 可触发的动作列表（label/target/confirmText）
    const STATUS_ACTIONS = {
        VERIFIED: [
            { target: 'ACTIVE',  label: '激活',   icon: 'bi-rocket-takeoff', verb: '激活为 ACTIVE' },
            { target: 'DRAFT',   label: '退回草稿', icon: 'bi-pencil',       verb: '退回草稿 DRAFT' },
        ],
        ACTIVE: [
            { target: 'ARCHIVED', label: '下线', icon: 'bi-archive', verb: '下线归档 ARCHIVED' },
        ],
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

    /**
     * 范式元信息：标签 + 图标 + 色系。
     * - timing（择时/signals）：单标的趋势跟踪，蓝色系
     * - rotation（轮动/rebalance）：多标的截面选股，紫色系
     * - grid（网格）：区间挂单赚差价，青绿色系
     */
    const PARADIGM_META = {
        timing:  { label: '择时',  icon: 'bi-graph-up-arrow',   color: 'var(--accent-blue)' },
        rotation:{ label: '轮动',  icon: 'bi-arrow-left-right', color: 'var(--accent-purple)' },
        grid:    { label: '网格',  icon: 'bi-grid-3x3-gap',     color: 'var(--accent-cyan)' },
    };

    /**
     * 从模板 configJson 派生范式（signals / rebalance / grid）。
     * 与后端 StrategyTemplateLoader.deriveScopeFromConfig 口径一致，grid 识别
     * position_sizing.method=="grid"（spec 015）。
     */
    function deriveParadigm(configJson) {
        if (!configJson) return 'timing';
        try {
            const cfg = typeof configJson === 'string' ? JSON.parse(configJson) : configJson;
            const tc = cfg && cfg.trading_config;
            if (!tc) return 'timing';
            if (tc.position_sizing && tc.position_sizing.method === 'grid') return 'grid';
            if (tc.rebalance) return 'rotation';
            if (tc.signals) return 'timing';
            return 'timing';
        } catch (err) {
            return 'timing';
        }
    }

    // 模板选择器（Modal）状态
    const picker = { modal: null, activeTab: 'all', keyword: '' };

    function initTemplatePicker() {
        const btn = document.getElementById('btnTemplatePicker');
        const modalEl = document.getElementById('templatePickerModal');
        if (!btn || !modalEl) return;
        picker.modal = new bootstrap.Modal(modalEl);
        btn.addEventListener('click', () => {
            picker.modal.show();
            if (!state.templates.length) loadTemplates();
            else renderTemplateGrid();
        });
        // Tab 切换
        document.getElementById('tplTabs').addEventListener('click', (ev) => {
            const tab = ev.target.closest('.tpl-tab');
            if (!tab) return;
            document.querySelectorAll('#tplTabs .tpl-tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            picker.activeTab = tab.dataset.paradigm;
            renderTemplateGrid();
        });
        // 搜索（输入防抖）
        let timer = null;
        document.getElementById('tplSearchInput').addEventListener('input', (ev) => {
            clearTimeout(timer);
            timer = setTimeout(() => {
                picker.keyword = ev.target.value.trim().toLowerCase();
                renderTemplateGrid();
            }, 180);
        });
    }

    function loadTemplates() {
        StockApp.get('/api/strategies/templates', null, function (resp) {
            if (resp.code !== 200 || !Array.isArray(resp.data)) {
                const grid = document.getElementById('tplGrid');
                if (grid) grid.innerHTML = '<div class="tpl-empty"><i class="bi bi-exclamation-triangle"></i><div>模板加载失败</div></div>';
                return;
            }
            state.templates = resp.data.map(t => {
                t._paradigm = deriveParadigm(t.configJson);
                return t;
            });
            // Tab 计数
            const cnt = { all: state.templates.length, timing: 0, rotation: 0, grid: 0 };
            state.templates.forEach(t => { cnt[t._paradigm] = (cnt[t._paradigm] || 0) + 1; });
            setText('cntAll', cnt.all);
            setText('cntTiming', cnt.timing);
            setText('cntRotation', cnt.rotation);
            setText('cntGrid', cnt.grid);
            renderTemplateGrid();
        });
    }

    function renderTemplateGrid() {
        const grid = document.getElementById('tplGrid');
        if (!grid) return;
        let list = state.templates;
        if (picker.activeTab !== 'all') {
            list = list.filter(t => t._paradigm === picker.activeTab);
        }
        if (picker.keyword) {
            const kw = picker.keyword;
            list = list.filter(t =>
                (t.name || '').toLowerCase().includes(kw) ||
                (t.description || '').toLowerCase().includes(kw) ||
                (t.tags || []).some(tag => String(tag).toLowerCase().includes(kw))
            );
        }
        if (!list.length) {
            grid.innerHTML = '<div class="tpl-empty"><i class="bi bi-inbox"></i><div>没有匹配的模板</div></div>';
            return;
        }
        grid.innerHTML = list.map(renderTemplateCard).join('');
    }

    function renderTemplateCard(t) {
        const meta = PARADIGM_META[t._paradigm] || PARADIGM_META.timing;
        const tagsHtml = (t.tags || []).slice(0, 3)
            .map(tag => `<span class="tpl-card-tag">#${e(tag)}</span>`).join('');
        const cat = CAT_BADGE[t.category] || { label: t.category || '—' };
        return `
            <a class="tpl-card" data-paradigm="${t._paradigm}"
               style="--tpl-color:${meta.color};"
               href="${StockApp.contextPath}/quant/strategies/new?templateId=${encodeURIComponent(t.id)}">
                <div class="tpl-card-accent"></div>
                <div class="tpl-card-head">
                    <span class="tpl-card-paradigm"><i class="bi ${meta.icon}"></i>${e(meta.label)}</span>
                    <span class="tpl-card-cat">${e(cat.label)}</span>
                </div>
                <div class="tpl-card-name">${e(t.name)}</div>
                <div class="tpl-card-desc">${e(t.description || '暂无描述')}</div>
                ${tagsHtml ? `<div class="tpl-card-tags">${tagsHtml}</div>` : ''}
            </a>`;
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
        const id = s.uuid || '';
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
        // DRAFT/ARCHIVED 不可回测（与后端 BacktestServiceImpl 口径一致）；去掉对 lastReturnPct 的依赖
        const backtestDisabled = s.status === 'DRAFT' || s.status === 'ARCHIVED';

        // 状态管理下拉按钮：仅 VERIFIED/ACTIVE 显示（DRAFT 需先校验，ARCHIVED 终态）
        const actions = STATUS_ACTIONS[s.status] || [];
        const statusBtnHtml = actions.length === 0 ? '' : `
            <div class="dropdown str-status-dropdown">
                <button type="button" class="str-icon-btn" title="状态管理" data-bs-toggle="dropdown" data-bs-auto-close="true" aria-expanded="false">
                    <i class="bi bi-sliders"></i>
                </button>
                <ul class="dropdown-menu dropdown-menu-end">
                    ${actions.map(a => `
                        <li><a class="dropdown-item" href="javascript:void(0);" data-action="status-change" data-id="${e(id)}" data-name="${e(name)}" data-target="${a.target}" data-verb="${e(a.verb)}"><i class="bi ${a.icon} me-2"></i>${e(a.label)}</a></li>
                    `).join('')}
                </ul>
            </div>`;

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
                        ${statusBtnHtml}
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

    function confirmStatusChange(id, name, target, verb) {
        StockApp.confirm({
            title: '状态变更',
            message: '确认将策略「' + name + '」「' + verb + '」？',
            confirmText: '确认',
            cancelText: '取消',
            confirmClass: 'btn-primary',
            icon: 'bi-arrow-repeat',
        }).then(ok => {
            if (!ok) return;
            doStatusChange(id, target);
        });
    }

    function doStatusChange(id, target) {
        fetch(StockApp.contextPath + '/api/strategies/' + encodeURIComponent(id) + '/status', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ status: target }),
        })
            .then(r => r.json())
            .then(resp => {
                if (resp.code === 200) {
                    StockApp.toast(resp.message || '状态已更新', 'success');
                    load();
                    loadStats();
                } else {
                    StockApp.toast(resp.message || '状态变更失败', 'danger');
                }
            })
            .catch(err => StockApp.toast('状态变更失败: ' + err.message, 'danger'));
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
                    location.href = StockApp.contextPath + '/quant/backtests/new?uuid=' + encodeURIComponent(btn.dataset.id);
                } else if (action === 'status-change') {
                    confirmStatusChange(btn.dataset.id, btn.dataset.name, btn.dataset.target, btn.dataset.verb);
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
        initTemplatePicker();
        loadStats();
        bindEvents();
        load();
    }

    document.addEventListener('DOMContentLoaded', init);

    return { init, load, loadStats };
})();
