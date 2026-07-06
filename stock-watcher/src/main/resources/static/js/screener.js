/**
 * 多因子选股中心页面逻辑。
 * - 方案库 CRUD：/screener/plans
 * - 执行选股：/screener/plans/{id}/run
 * - 结果锁定：/screener/results/{resultId}/lock
 * - 因子下拉复用：GET /factors（与因子库同源）
 *
 * 条件规则编辑器支持：
 *   1) 因子 vs 因子 / 因子 vs 数值（左右操作数对称，type 可选 factor|value）
 *   2) 因子参数（按 /factors 元数据 params 动态渲染）+ 多输出因子 outputIndex
 *   3) 任意深度 AND/OR 嵌套分组（递归 ConditionTree）
 * 与 engine Schema（condition.py）字段对齐：分组 {operator, conditions:[]}，叶子 {type:'compare', left, comparator, right}。
 */
const Screener = (function () {
    'use strict';

    const e = StockApp.escapeHtml;

    // ===================== 常量（与后端 Schema 对齐，详见 rules/frontend/07-constants-usage.md）=====================
    // B 类：比较器 comparator —— 权威来源 engine models/schemas/condition.py Comparator（cross_up/cross_down 仅交易路径，选股不可用）
    const COMPARATORS = ['>', '<', '>=', '<=', '==', '!='];
    // B 类：逻辑运算符 operator —— 权威来源 engine models/schemas/condition.py ConditionTree.operator
    const LOGIC_OPERATORS = { AND: 'AND', OR: 'OR' };
    // B 类：表达式形态 kind —— 权威来源 engine models/schemas/condition.py ExpressionNode.kind
    const EXPR_KINDS = { FACTOR: 'factor', VALUE: 'value' };
    // B 类：叶子节点类型 —— 权威来源 engine models/schemas/condition.py CompareLeaf.type（固定 'compare'）
    const LEAF_TYPE = 'compare';
    // B 类：排序方法 —— 权威来源 engine models/schemas/screener.py Ranking.method: Literal["single","composite"]
    const RANKING_METHODS = { SINGLE: 'single', COMPOSITE: 'composite' };
    // B 类：排序方向 —— 权威来源 engine models/schemas/screener.py Ranking.order（默认 'desc'）
    const RANKING_ORDERS = { ASC: 'asc', DESC: 'desc' };
    // B 类：候选池 universe —— 权威来源 watcher ScreenerServiceImpl.resolveUniverse
    const UNIVERSES = {
        ALL_A_SHARES: 'all_a_shares',
        CSI300: 'csi300',
        CSI500: 'csi500',
        MANUAL: 'manual',
    };
    // B 类：过滤开关默认值 —— 权威来源 engine services/screener/filters.py _DEFAULT_FILTERS
    const DEFAULT_FILTERS = {
        excludeSt: true, excludeSuspended: true, excludeLimitUp: true, excludeLimitDown: false,
        industries: [], excludeIndustries: [], minListDays: 0,
    };
    const DEFAULT_TOP_N = 30;
    const MANUAL_MAX = 50;

    const state = {
        factors: [],            // 全量因子（来自 /factors，FactorVO）
        categories: [],         // 因子分类
        plans: [],              // 方案列表
        currentPlanId: null,    // 当前选中方案 id
        lastResult: null,       // 最近一次 ScreenResultVO
        activeTab: 'conditions',
        tree: { operator: LOGIC_OPERATORS.AND, conditions: [] },   // 根 ConditionTree（engine 根节点无 type）
        dirty: false,           // 是否有未保存改动
        lastSavedAt: null,      // 最近保存时间戳（毫秒）
        switching: false,       // 是否正在切换方案（用于抑制脏标记）
        manualStocks: [],       // 方案配置用：[{tsCode, code, name}, ...]
    };

    // ===================== 因子元数据辅助 =====================
    function getFactorDef(key) {
        return state.factors.find(f => f.factorKey === key) || null;
    }

    function getDefaultParams(def) {
        const p = {};
        (def && def.params || []).forEach(x => { p[x.name] = x.defaultValue; });
        return p;
    }

    function buildFactorOptions(selected) {
        const byCat = {};
        state.factors.forEach(f => { (byCat[f.category] = byCat[f.category] || []).push(f); });
        const catOrder = state.categories.map(c => c.key);
        Object.keys(byCat).forEach(k => { if (!catOrder.includes(k)) catOrder.push(k); });
        let html = '<option value="">— 选择因子 —</option>';
        catOrder.forEach(ck => {
            if (!byCat[ck]) return;
            const catName = (state.categories.find(c => c.key === ck) || {}).name || ck;
            const opts = byCat[ck].map(f =>
                `<option value="${e(f.factorKey)}"${f.factorKey === selected ? ' selected' : ''}>${e(f.factorKey)} · ${e(f.displayName || '')}</option>`
            ).join('');
            html += `<optgroup label="${e(catName)}">${opts}</optgroup>`;
        });
        return html;
    }

    // universe / order 的 label 表（与 engine Schema 字面量对齐的展示名）
    const UNIVERSE_OPTIONS = [
        { code: UNIVERSES.ALL_A_SHARES, label: '全部 A 股' },
        { code: UNIVERSES.CSI300,       label: '沪深 300' },
        { code: UNIVERSES.CSI500,       label: '中证 500' },
        { code: UNIVERSES.MANUAL,       label: '手动指定' },
    ];
    const RANKING_ORDER_OPTIONS = [
        { code: RANKING_ORDERS.ASC,  label: '升序 (asc)' },
        { code: RANKING_ORDERS.DESC, label: '降序 (desc)' },
    ];

    function fillFactorSelects() {
        const rs = document.getElementById('rankSingleFactor');
        if (rs) rs.innerHTML = buildFactorOptions(rs.value);
        // universe 与 order 的下拉选项由常量注入，避免 HTML 硬编码 schema 字面量
        const uni = document.getElementById('universeSel');
        if (uni && !uni.options.length) {
            uni.innerHTML = UNIVERSE_OPTIONS.map(o => `<option value="${o.code}">${o.label}</option>`).join('');
        }
        const ord = document.getElementById('rankSingleOrder');
        if (ord && !ord.options.length) {
            ord.innerHTML = RANKING_ORDER_OPTIONS.map(o => `<option value="${o.code}">${o.label}</option>`).join('');
        }
    }

    async function loadFactors() {
        const [catResp, facResp] = await Promise.all([
            fetchJson('/factors/categories'),
            fetchJson('/factors'),
        ]);
        state.categories = catResp.data || [];
        state.factors = facResp.data || [];
    }

    async function fetchJson(url) {
        const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
        return r.json();
    }

    // ===================== 方案库 =====================
    function loadPlans() {
        return new Promise((resolve) => {
            StockApp.get('/screener/plans', { page: 1, size: 50 }, function (resp) {
                if (resp.code === 200) {
                    const data = resp.data || {};
                    state.plans = data.list || data.records || data || [];
                    renderPlanList();
                    updatePlanCount(data.total != null ? data.total : state.plans.length);
                } else {
                    StockApp.toast(resp.message || '加载方案失败', 'danger');
                }
                resolve();
            });
        });
    }

    function updatePlanCount(n) {
        const el = document.getElementById('planCount');
        if (el) el.textContent = '· ' + n;
    }

    function renderPlanList() {
        const wrap = document.getElementById('planList');
        if (!state.plans.length) {
            wrap.innerHTML = '<div class="sw-empty">暂无方案，点击「新建」</div>';
            return;
        }
        wrap.innerHTML = state.plans.map(p => {
            const active = p.id === state.currentPlanId ? ' active' : '';
            const dirty = (active && state.dirty) ? ' dirty' : '';
            const ts = formatTime(p.updatedAt || p.createdAt);
            return `<div class="plan-card${active}${dirty}" data-id="${e(p.id)}" onclick="Screener.selectPlan('${e(p.id)}')">
                <div class="pc-name">
                    <span class="pc-name-text">${e(p.name || '未命名')}</span>
                    <span class="pc-dirty-badge" title="有未保存改动">未保存</span>
                </div>
                <div class="pc-desc">${e(p.description || '—')}</div>
                <div class="pc-foot">
                    <span class="pc-time"><i class="bi bi-clock"></i> ${ts}</span>
                    <button class="pc-del" title="删除" onclick="event.stopPropagation();Screener.deletePlan('${e(p.id)}')">
                        <i class="bi bi-trash3"></i>
                    </button>
                </div>
            </div>`;
        }).join('');
    }

    async function selectPlan(id) {
        // 切换前若有未保存改动，提示先保存或丢弃
        if (state.dirty && state.currentPlanId && String(state.currentPlanId) !== String(id)) {
            const ok = await StockApp.confirm({
                title: '切换方案',
                message: '当前方案有未保存改动，是否先保存？',
                confirmText: '先保存',
                cancelText: '丢弃',
                confirmClass: 'btn-primary',
                icon: 'bi-exclamation-triangle-fill',
            });
            if (ok) {
                const saved = await savePlan();
                if (!saved) return;   // 保存失败（如校验不过）则放弃切换
            }
        }
        state.switching = true;
        StockApp.get('/screener/plans/' + encodeURIComponent(id), null, function (resp) {
            if (resp.code === 200 && resp.data) {
                state.currentPlanId = resp.data.id;
                document.getElementById('planName').value = resp.data.name || '';
                document.getElementById('planDesc').value = resp.data.description || '';
                applyConfig(resp.data.screenConfig || {});
                renderPlanList();
                clearDirty();
                state.lastSavedAt = resp.data.updatedAt
                    ? new Date(resp.data.updatedAt).getTime()
                    : (resp.data.createdAt ? new Date(resp.data.createdAt).getTime() : Date.now());
                updateStatusUI();
            } else {
                StockApp.toast(resp.message || '加载方案失败', 'danger');
            }
            state.switching = false;
        });
    }

    async function createPlan() {
        const name = await StockApp.prompt({
            title: '新建选股方案',
            message: '将为新方案创建空白配置，创建后可在编辑器中配置条件。',
            placeholder: '请输入方案名称',
            defaultValue: '',
            required: true,
            validate: v => v.trim().length > 100 ? '方案名长度不能超过 100' : null,
            confirmText: '创建',
            confirmClass: 'btn-primary',
            icon: 'bi-plus-circle',
        });
        if (name === null) return;   // 用户取消

        const body = {
            name: name.trim(),
            description: '',
            screenConfig: defaultConfig(),   // 空白配置，不再复用当前编辑器内容
        };
        StockApp.post('/screener/plans', body, function (resp) {
            if (resp.code === 200 && resp.data) {
                StockApp.toast('方案已创建', 'success');
                const newId = resp.data.id || resp.data;
                // 先刷新列表（内部会 renderPlanList），完成后再 selectPlan，
                // 避免 selectPlan 的 renderPlanList 用旧 plans 覆盖刚加载的列表（异步竞态）
                StockApp.get('/screener/plans', { page: 1, size: 50 }, function (listResp) {
                    if (listResp.code === 200) {
                        const data = listResp.data || {};
                        state.plans = data.list || data.records || data || [];
                        updatePlanCount(data.total != null ? data.total : state.plans.length);
                    }
                    selectPlan(newId);   // selectPlan 内部会 renderPlanList + clearDirty
                });
            } else {
                StockApp.toast(resp.message || '创建失败', 'danger');
            }
        });
    }

    async function deletePlan(id) {
        const plan = state.plans.find(p => String(p.id) === String(id));
        const ok = await StockApp.confirm({
            title: '删除方案',
            message: '确认删除方案「' + (plan ? (plan.name || '未命名') : '') + '」？此操作不可撤销。',
            confirmText: '删除',
            cancelText: '取消',
            confirmClass: 'btn-danger',
            icon: 'bi-trash3-fill',
        });
        if (!ok) return;
        StockApp.post('/screener/plans/' + encodeURIComponent(id) + '/delete', null, function (resp) {
            if (resp.code === 200) {
                StockApp.toast('已删除', 'success');
                if (String(state.currentPlanId) === String(id)) {
                    state.currentPlanId = null;
                    resetEditor();
                    clearDirty();
                    state.lastSavedAt = null;
                    updateStatusUI();
                }
                loadPlans();
            } else {
                StockApp.toast(resp.message || '删除失败', 'danger');
            }
        });
    }

    // ===================== 条件树：寻址与变更（状态优先） =====================
    function nodeAt(path) {
        let node = state.tree;
        for (const idx of path) { node = node.conditions[idx]; }
        return node;
    }

    function parentAt(path) {
        if (path.length === 0) return null;
        let node = state.tree;
        for (let i = 0; i < path.length - 1; i++) { node = node.conditions[path[i]]; }
        return node;
    }

    function toggleOperator(path) {
        const node = nodeAt(path);
        if (node && 'operator' in node) {
            node.operator = node.operator === LOGIC_OPERATORS.AND ? LOGIC_OPERATORS.OR : LOGIC_OPERATORS.AND;
            renderTree();
        }
    }

    function addLeaf(path) {
        const node = nodeAt(path);
        if (node && 'conditions' in node) {
            node.conditions.push(defaultLeaf());
            renderTree();
        }
    }

    function addGroup(path) {
        const node = nodeAt(path);
        if (node && 'conditions' in node) {
            node.conditions.push({ operator: LOGIC_OPERATORS.AND, conditions: [] });
            renderTree();
        }
    }

    function removeNode(path) {
        if (path.length === 0) return;
        const parent = parentAt(path);
        const idx = path[path.length - 1];
        parent.conditions.splice(idx, 1);
        renderTree();
    }

    function defaultLeaf() {
        return {
            type: LEAF_TYPE,
            left: { kind: EXPR_KINDS.FACTOR, factor: '', params: {}, outputIndex: 0 },
            comparator: COMPARATORS[0],
            right: { kind: EXPR_KINDS.VALUE, value: 0 },
        };
    }

    /**
     * 表单变更写回 state。field 取自 data-field。
     * side: 'left' | 'right'；leaf 节点路径由 path 定位。
     */
    function updateLeaf(path, side, field, value) {
        const node = nodeAt(path);
        if (!node || node.type !== LEAF_TYPE) return;
        const operand = node[side];

        if (field === 'type') {
            // 切换操作数形态：factor <-> value
            if (value === EXPR_KINDS.FACTOR) {
                node[side] = { kind: EXPR_KINDS.FACTOR, factor: '', params: {}, outputIndex: 0 };
            } else {
                node[side] = { kind: EXPR_KINDS.VALUE, value: 0 };
            }
            renderTree();
            return;
        }
        if (field === EXPR_KINDS.FACTOR) {
            operand.factor = value;
            const def = getFactorDef(value);
            operand.params = getDefaultParams(def);
            operand.outputIndex = (def && def.defaultOutputIndex != null) ? def.defaultOutputIndex : 0;
            renderTree();
            return;
        }
        if (field === EXPR_KINDS.VALUE) {
            operand.value = value === '' ? 0 : Number(value);
            return;
        }
        if (field === 'output') {
            operand.outputIndex = Number(value);
            return;
        }
        if (field.indexOf('param-') === 0) {
            const name = field.slice(6);
            operand.params[name] = value === '' ? null : Number(value);
            return;
        }
    }

    // ===================== 条件树：渲染 =====================
    function renderTree() {
        const container = document.getElementById('conditionTree');
        if (!container) return;
        if (!state.tree.conditions || state.tree.conditions.length === 0) {
            container.innerHTML = `
                <div class="sw-tree-empty">
                    <div>当前没有任何条件</div>
                    <div class="tree-hint">点击上方「添加条件」或「添加分组」开始构建规则树</div>
                </div>`;
            return;
        }
        container.innerHTML = renderNode(state.tree, []);
    }

    function renderNode(node, path) {
        if ('operator' in node) {
            const childrenHtml = (node.conditions || []).map((c, i) => renderNode(c, [...path, i])).join('');
            const removeHtml = path.length
                ? `<button class="tree-act-btn danger" data-action="removeNode" data-path="${path.join(',')}" type="button"><i class="bi bi-trash3"></i> 删除分组</button>`
                : '';
            return `
                <div class="group-node" data-path="${path.join(',')}">
                    <div class="group-header">
                        <span class="operator-badge ${node.operator}" data-action="toggleOperator" data-path="${path.join(',')}" role="button" tabindex="0">${node.operator}</span>
                        <div class="tree-actions">
                            <button class="tree-act-btn" data-action="addLeaf" data-path="${path.join(',')}" type="button"><i class="bi bi-plus-lg"></i> 条件</button>
                            <button class="tree-act-btn" data-action="addGroup" data-path="${path.join(',')}" type="button"><i class="bi bi-diagram-3"></i> 分组</button>
                            ${removeHtml}
                        </div>
                    </div>
                    <div class="children">${childrenHtml}</div>
                </div>`;
        }
        return renderLeaf(node, path);
    }

    function renderLeaf(leaf, path) {
        const pathStr = path.join(',');
        return `
            <div class="leaf-node" data-path="${pathStr}">
                ${renderExpression(leaf.left, 'left', pathStr)}
                <div class="comparator-badge">
                    <select class="leaf-cmp" data-path="${pathStr}">
                        ${COMPARATORS.map(c => `<option value="${c}"${c === leaf.comparator ? ' selected' : ''}>${c}</option>`).join('')}
                    </select>
                </div>
                ${renderExpression(leaf.right, 'right', pathStr)}
                <button class="node-remove" data-action="removeNode" data-path="${pathStr}" type="button" aria-label="删除条件"><i class="bi bi-x-lg"></i></button>
            </div>`;
    }

    function renderExpression(operand, side, pathStr) {
        const isFactor = 'factor' in operand;
        const type = isFactor ? EXPR_KINDS.FACTOR : EXPR_KINDS.VALUE;

        const typeSelect = `<select class="form-select form-select-sm op-type" data-field="type" data-path="${pathStr}" data-side="${side}">
            <option value="${EXPR_KINDS.FACTOR}"${isFactor ? ' selected' : ''}>因子</option>
            <option value="${EXPR_KINDS.VALUE}"${!isFactor ? ' selected' : ''}>数值</option>
        </select>`;

        let bodyHtml = '';
        let paramsHtml = '';
        let outputHtml = '';

        if (isFactor) {
            bodyHtml = `<select class="form-select form-select-sm op-factor" data-field="factor" data-path="${pathStr}" data-side="${side}">${buildFactorOptions(operand.factor)}</select>`;
            const def = getFactorDef(operand.factor);
            const params = def && def.params ? def.params : [];
            if (params.length) {
                paramsHtml = `<div class="expr-params">${params.map(p => renderParamInput(p, operand.params && operand.params[p.name], pathStr, side)).join('')}</div>`;
            }
            if (def && def.multiOutput && def.outputLabels && def.outputLabels.length) {
                const cur = operand.outputIndex != null ? operand.outputIndex : (def.defaultOutputIndex || 0);
                outputHtml = `<div class="output-field"><label>输出</label><select class="form-select form-select-sm op-output" data-field="output" data-path="${pathStr}" data-side="${side}">${def.outputLabels.map((label, i) => `<option value="${i}"${i === cur ? ' selected' : ''}>[${i}] ${e(label)}</option>`).join('')}</select></div>`;
            }
        } else {
            const v = operand.value != null ? operand.value : 0;
            bodyHtml = `<input type="number" step="any" class="form-control form-control-sm op-value" data-field="value" data-path="${pathStr}" data-side="${side}" value="${e(String(v))}" placeholder="数值">`;
        }

        return `
            <div class="op-block">
                <div class="expr-head">${typeSelect}${bodyHtml}</div>
                ${paramsHtml}
                ${outputHtml}
            </div>`;
    }

    function renderParamInput(param, value, pathStr, side) {
        const val = value != null ? value : (param.defaultValue != null ? param.defaultValue : '');
        const step = param.step != null ? param.step : (param.type === 'INT' ? 1 : 0.01);
        const min = param.min != null ? `min="${param.min}"` : '';
        const max = param.max != null ? `max="${param.max}"` : '';
        return `
            <div class="param-field">
                <label>${e(param.displayName || param.name)}</label>
                <input type="number" step="${step}" ${min} ${max} class="form-control op-param" data-field="param-${param.name}" data-path="${pathStr}" data-side="${side}" value="${e(String(val))}">
            </div>`;
    }

    // ===================== 编辑器：权重行 =====================
    function addWeight(factor, weight) {
        const list = document.getElementById('weightList');
        const row = document.createElement('div');
        row.className = 'sw-cond-row';
        row.innerHTML = `
            <select class="form-select form-select-sm w-factor" style="min-width:160px;"></select>
            <input type="number" step="any" class="form-control form-control-sm w-weight" placeholder="权重" value="${weight != null ? e(weight) : 1}" style="width:110px;">
            <button class="pc-del" title="删除" onclick="this.parentElement.remove()"><i class="bi bi-x-lg"></i></button>
        `;
        list.appendChild(row);
        row.querySelector('.w-factor').innerHTML = buildFactorOptions(factor || '');
    }

    // ===================== Tab 切换 =====================
    function switchTab(tab) {
        state.activeTab = tab;
        document.querySelectorAll('.sw-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
        document.querySelectorAll('.sw-tab-pane').forEach(p => p.classList.toggle('active', p.id === 'pane-' + tab));
    }

    function bindRankMethod() {
        const single = document.getElementById('rankSingleBlock');
        const comp = document.getElementById('rankCompositeBlock');
        document.querySelectorAll('input[name="rankMethod"]').forEach(r => {
            r.addEventListener('change', () => {
                const isSingle = document.querySelector('input[name="rankMethod"]:checked').value === RANKING_METHODS.SINGLE;
                single.style.display = isSingle ? '' : 'none';
                comp.style.display = isSingle ? 'none' : '';
            });
        });
    }

    // ===================== 收集 / 回填 screenConfig =====================
    function collectConfig() {
        const universe = document.getElementById('universeSel').value;

        // conditions：直接序列化 state.tree（已是 engine ConditionTree 结构）
        const conditions = JSON.parse(JSON.stringify(state.tree));

        // filters
        const filters = {
            excludeSt: document.getElementById('fSt').checked,
            excludeSuspended: document.getElementById('fSuspend').checked,
            excludeLimitUp: document.getElementById('fUp').checked,
            excludeLimitDown: document.getElementById('fDown').checked,
            industries: splitCsv(document.getElementById('fIndustries').value),
            excludeIndustries: splitCsv(document.getElementById('fExIndustries').value),
            minListDays: parseInt(document.getElementById('fMinListDays').value, 10) || 0,
        };

        // ranking
        const method = document.querySelector('input[name="rankMethod"]:checked').value;
        let ranking;
        if (method === RANKING_METHODS.SINGLE) {
            ranking = {
                method: RANKING_METHODS.SINGLE,
                factor: document.getElementById('rankSingleFactor').value,
                order: document.getElementById('rankSingleOrder').value,
            };
        } else {
            const weights = {};
            document.querySelectorAll('#weightList .sw-cond-row').forEach(row => {
                const f = row.querySelector('.w-factor').value;
                const wRaw = row.querySelector('.w-weight').value;
                if (!f || wRaw === '') return;
                const w = parseFloat(wRaw);
                if (!Number.isNaN(w)) weights[f] = w;
            });
            ranking = { method: RANKING_METHODS.COMPOSITE, weights: weights };
        }

        const topN = parseInt(document.getElementById('topN').value, 10) || DEFAULT_TOP_N;

        const cfg = {
            universe: universe,
            conditions: conditions,
            ranking: ranking,
            filters: filters,
            topN: topN,
        };
        if (universe === UNIVERSES.MANUAL) {
            cfg.stocks = state.manualStocks.map(s => s.tsCode).filter(Boolean);
        }
        return cfg;
    }

    function applyConfig(cfg) {
        if (cfg.universe) document.getElementById('universeSel').value = cfg.universe;
        // 同步 manual 面板显隐（universeSel 值变更后立即刷新，避免加载 manual 方案时面板仍隐藏）
        syncManualPanelVisibility();

        // conditions：兼容旧扁平叶子 / {operator, conditions:[...]} 结构
        state.tree = normalizeTree(cfg.conditions);

        // filters
        const f = cfg.filters || {};
        document.getElementById('fSt').checked = !!f.excludeSt;
        document.getElementById('fSuspend').checked = !!f.excludeSuspended;
        document.getElementById('fUp').checked = !!f.excludeLimitUp;
        document.getElementById('fDown').checked = !!f.excludeLimitDown;
        document.getElementById('fIndustries').value = (f.industries || []).join(',');
        document.getElementById('fExIndustries').value = (f.excludeIndustries || []).join(',');
        document.getElementById('fMinListDays').value = f.minListDays || 0;

        // ranking
        const r = cfg.ranking || {};
        if (r.method === RANKING_METHODS.COMPOSITE) {
            document.querySelector('input[name="rankMethod"][value="' + RANKING_METHODS.COMPOSITE + '"]').checked = true;
            document.getElementById('rankSingleBlock').style.display = 'none';
            document.getElementById('rankCompositeBlock').style.display = '';
            document.getElementById('weightList').innerHTML = '';
            Object.entries(r.weights || {}).forEach(([fk, w]) => addWeight(fk, w));
        } else {
            document.querySelector('input[name="rankMethod"][value="' + RANKING_METHODS.SINGLE + '"]').checked = true;
            document.getElementById('rankSingleBlock').style.display = '';
            document.getElementById('rankCompositeBlock').style.display = 'none';
            if (r.factor) document.getElementById('rankSingleFactor').value = r.factor;
            if (r.order) document.getElementById('rankSingleOrder').value = r.order;
        }

        if (cfg.topN != null) document.getElementById('topN').value = cfg.topN;

        // manual stocks 回填（cfg.stocks 是 tsCode 数组，需批量拉名称）
        if (cfg.universe === UNIVERSES.MANUAL && Array.isArray(cfg.stocks) && cfg.stocks.length) {
            loadManualStocksDetail(cfg.stocks);
        } else {
            state.manualStocks = [];
            renderManualCloud();
        }

        renderTree();
    }

    // ===================== manual 候选股管理 =====================
    // 纯显隐逻辑（不触发脏标记），applyConfig 和 onUniverseChange 共用
    function syncManualPanelVisibility() {
        const universe = document.getElementById('universeSel').value;
        const panel = document.getElementById('manualStocksPanel');
        if (panel) panel.style.display = universe === UNIVERSES.MANUAL ? '' : 'none';
    }

    function onUniverseChange() {
        syncManualPanelVisibility();
        markDirty();
    }

    function addManualStock(item) {
        if (!item || !item.tsCode) return;
        if (state.manualStocks.some(s => s.tsCode === item.tsCode)) {
            StockApp.toast('已存在：' + (item.name || item.tsCode), 'info');
            return;
        }
        if (state.manualStocks.length >= MANUAL_MAX) {
            StockApp.toast('已达到上限 ' + MANUAL_MAX + ' 只', 'warning');
            return;
        }
        state.manualStocks.push({ tsCode: item.tsCode, code: item.code, name: item.name });
        renderManualCloud();
        markDirty();
    }

    function removeManualStock(tsCode) {
        state.manualStocks = state.manualStocks.filter(s => s.tsCode !== tsCode);
        renderManualCloud();
        markDirty();
    }

    function renderManualCloud() {
        const cloud = document.getElementById('manualStocksCloud');
        const countEl = document.getElementById('manualCount');
        if (countEl) countEl.textContent = state.manualStocks.length;
        if (!cloud) return;
        if (!state.manualStocks.length) {
            cloud.innerHTML = '<div class="manual-stocks-empty">尚未添加候选股，请在上方搜索框输入代码或名称</div>';
            return;
        }
        cloud.innerHTML = state.manualStocks.map(s =>
            '<span class="stock-tag">' +
            e(s.name || '') + ' <span class="stock-tag-code">' + e(s.tsCode) + '</span>' +
            ' <i class="bi bi-x-lg" onclick="Screener.removeManualStock(\'' + s.tsCode.replace(/'/g, "\\'") + '\')"></i>' +
            '</span>'
        ).join('');
    }

    function loadManualStocksDetail(tsCodes) {
        const codes = (tsCodes || []).filter(Boolean);
        if (!codes.length) {
            state.manualStocks = [];
            renderManualCloud();
            return;
        }
        StockApp.get('/search/batch', { tsCodes: codes.join(',') }, function (resp) {
            if (resp.code === 200 && Array.isArray(resp.data)) {
                // 保留原 stocks 数组顺序，未命中的 tsCode 仍展示（名称缺失时用 tsCode 兜底）
                const map = {};
                resp.data.forEach(it => { if (it && it.tsCode) map[it.tsCode] = it; });
                state.manualStocks = codes.map(tc => map[tc]
                    ? { tsCode: map[tc].tsCode, code: map[tc].code, name: map[tc].name }
                    : { tsCode: tc, code: '', name: '' });
            } else {
                state.manualStocks = codes.map(tc => ({ tsCode: tc, code: '', name: '' }));
            }
            renderManualCloud();
        });
    }

    /**
     * 把任意 conditions 形态归一化为 engine ConditionTree：
     *   - 已是 {operator, conditions} → 规范化子节点
     *   - 旧扁平叶子数组 → 包成 {operator:'AND', conditions:[...]}
     *   - null/空 → 空根树
     */
    function normalizeTree(cond) {
        if (!cond) return { operator: LOGIC_OPERATORS.AND, conditions: [] };
        if (Array.isArray(cond)) {
            return { operator: LOGIC_OPERATORS.AND, conditions: cond.map(normalizeLeaf).filter(Boolean) };
        }
        if ('operator' in cond) {
            return {
                operator: cond.operator === LOGIC_OPERATORS.OR ? LOGIC_OPERATORS.OR : LOGIC_OPERATORS.AND,
                conditions: (cond.conditions || []).map(normalizeNode).filter(Boolean),
            };
        }
        return { operator: LOGIC_OPERATORS.AND, conditions: [] };
    }

    function normalizeNode(node) {
        if (!node) return null;
        if ('operator' in node) return normalizeTree(node);
        return normalizeLeaf(node);
    }

    function normalizeLeaf(leaf) {
        if (!leaf || leaf.type !== LEAF_TYPE) return null;
        return {
            type: LEAF_TYPE,
            left: normalizeOperand(leaf.left),
            comparator: leaf.comparator || COMPARATORS[0],
            right: normalizeOperand(leaf.right),
        };
    }

    function normalizeOperand(op) {
        if (!op) return { kind: EXPR_KINDS.VALUE, value: 0 };
        if ('factor' in op) {
            const def = getFactorDef(op.factor);
            return {
                kind: EXPR_KINDS.FACTOR,
                factor: op.factor || '',
                params: op.params || getDefaultParams(def) || {},
                outputIndex: op.outputIndex != null ? op.outputIndex : (def && def.defaultOutputIndex != null ? def.defaultOutputIndex : 0),
            };
        }
        return { kind: EXPR_KINDS.VALUE, value: op.value != null ? op.value : 0 };
    }

    function resetEditor() {
        document.getElementById('planName').value = '';
        document.getElementById('planDesc').value = '';
        applyConfig(defaultConfig());
        state.lastResult = null;
        renderResult(null);
        document.getElementById('lockBtn').disabled = true;
    }

    // ===================== 配置校验 =====================
    function validateConfig() {
        const errors = [];

        // 1. 条件树递归校验：每个叶子 factor 必填、value 不能 NaN
        function checkNode(node, pathDesc) {
            if ('operator' in node) {
                (node.conditions || []).forEach((c, i) => checkNode(c, pathDesc + '[' + i + ']'));
                return;
            }
            ['left', 'right'].forEach(side => {
                const op = node[side];
                if ('factor' in op) {
                    if (!op.factor) {
                        errors.push('条件 ' + (pathDesc || '根') + ' 的' + (side === 'left' ? '左' : '右') + '操作数未选择因子');
                    }
                } else {
                    if (op.value == null || Number.isNaN(op.value)) {
                        errors.push('条件 ' + (pathDesc || '根') + ' 的' + (side === 'left' ? '左' : '右') + '操作数值无效');
                    }
                }
            });
        }
        if (state.tree.conditions && state.tree.conditions.length) {
            checkNode(state.tree, '');
        }

        // 2. 排序打分校验
        const methodEl = document.querySelector('input[name="rankMethod"]:checked');
        const method = methodEl ? methodEl.value : RANKING_METHODS.SINGLE;
        if (method === RANKING_METHODS.SINGLE) {
            if (!document.getElementById('rankSingleFactor').value) {
                errors.push('排序打分：单因子模式下未选择排序因子');
            }
        } else {
            const rows = document.querySelectorAll('#weightList .sw-cond-row');
            if (!rows.length) {
                errors.push('排序打分：复合加权模式下未添加任何权重因子');
            } else {
                let total = 0;
                let hasEmpty = false;
                rows.forEach(row => {
                    const f = row.querySelector('.w-factor').value;
                    const w = parseFloat(row.querySelector('.w-weight').value);
                    if (!f) hasEmpty = true;
                    if (!Number.isNaN(w)) total += Math.abs(w);
                });
                if (hasEmpty) errors.push('排序打分：存在未选择因子的权重行');
                if (total === 0) errors.push('排序打分：复合权重绝对值之和为 0，无法归一化');
            }
        }

        // 3. topN
        const topN = parseInt(document.getElementById('topN').value, 10);
        if (!topN || topN < 1) errors.push('排序打分：topN 必须为正整数');

        // 4. 上市天数
        const minDays = parseInt(document.getElementById('fMinListDays').value, 10);
        if (Number.isNaN(minDays) || minDays < 0) errors.push('静态过滤：最低上市天数需为非负整数');

        // 5. manual 候选股非空校验
        const universe = document.getElementById('universeSel').value;
        if (universe === UNIVERSES.MANUAL && !state.manualStocks.length) {
            errors.push('候选池：手动指定模式下必须至少添加 1 只候选股');
        }

        return errors;
    }

    function defaultConfig() {
        return {
            universe: UNIVERSES.ALL_A_SHARES,
            conditions: { operator: LOGIC_OPERATORS.AND, conditions: [] },
            ranking: { method: RANKING_METHODS.SINGLE, factor: '', order: RANKING_ORDERS.ASC },
            filters: {
                excludeSt: DEFAULT_FILTERS.excludeSt,
                excludeSuspended: DEFAULT_FILTERS.excludeSuspended,
                excludeLimitUp: DEFAULT_FILTERS.excludeLimitUp,
                excludeLimitDown: DEFAULT_FILTERS.excludeLimitDown,
                industries: DEFAULT_FILTERS.industries.slice(),
                excludeIndustries: DEFAULT_FILTERS.excludeIndustries.slice(),
                minListDays: DEFAULT_FILTERS.minListDays,
            },
            topN: DEFAULT_TOP_N,
        };
    }

    // ===================== 脏数据检测 & 状态条 =====================
    function markDirty() {
        if (state.switching) return;     // 程序化回填期间不标脏
        if (!state.currentPlanId) return; // 未选中方案不标脏
        if (state.dirty) return;          // 已是脏态无需重复刷新
        state.dirty = true;
        updateStatusUI();
    }

    function clearDirty() {
        state.dirty = false;
        updateStatusUI();
    }

    // 顶部状态块已移除：脏态红色提示统一在左侧方案卡片展示（见 updatePlanCardDirty）。
    // 本函数仅维护「保存」按钮的可用/高亮态 + 左侧卡片脏态联动。
    function updateStatusUI() {
        const saveBtn = document.getElementById('saveBtn');
        if (!saveBtn) { updatePlanCardDirty(); return; }

        if (!state.currentPlanId) {
            saveBtn.disabled = true;
            saveBtn.classList.remove('btn-warning');
            saveBtn.classList.add('btn-outline-primary');
            updatePlanCardDirty();
            return;
        }

        if (state.dirty) {
            saveBtn.disabled = false;
            saveBtn.classList.remove('btn-outline-primary');
            saveBtn.classList.add('btn-warning');
        } else {
            saveBtn.disabled = true;
            saveBtn.classList.remove('btn-warning');
            saveBtn.classList.add('btn-outline-primary');
        }
        updatePlanCardDirty();
    }

    // 仅刷新左侧方案卡片的脏态标记，不重渲染整个列表（避免输入框失焦等副作用）
    function updatePlanCardDirty() {
        const cards = document.querySelectorAll('#planList .plan-card');
        cards.forEach(card => {
            if (state.dirty && state.currentPlanId
                    && String(card.dataset.id) === String(state.currentPlanId)) {
                card.classList.add('dirty');
            } else {
                card.classList.remove('dirty');
            }
        });
    }

    // ===================== 保存方案 =====================
    // silent=true 时为「运行前静默保存」，不弹"方案已保���"toast，让运行流程只感知一次完成提示。
    async function savePlan(silent) {
        if (!state.currentPlanId) {
            StockApp.toast('请先选中或新建方案', 'warning');
            return false;
        }
        const errors = validateConfig();
        if (errors.length) {
            await StockApp.alert({
                title: '保存失败',
                message: '请修正以下问题：\n' + errors.map((err, i) => (i + 1) + '. ' + err).join('\n'),
                type: 'warning',
                buttonText: '知道了',
            });
            return false;
        }
        return new Promise(resolve => {
            StockApp.post('/screener/plans/' + encodeURIComponent(state.currentPlanId) + '/update', {
                name: document.getElementById('planName').value.trim() || '未命名方案',
                description: document.getElementById('planDesc').value.trim(),
                screenConfig: collectConfig(),
            }, function (resp) {
                if (resp.code === 200) {
                    clearDirty();
                    state.lastSavedAt = Date.now();
                    updateStatusUI();
                    if (!silent) StockApp.toast('方案已保存', 'success');
                    loadPlans();   // 同步左侧卡片 updatedAt
                    resolve(true);
                } else {
                    StockApp.toast(resp.message || '保存失败', 'danger');
                    resolve(false);
                }
            });
        });
    }

    // ===================== 运行选股 =====================
    // 体验合并：把「保存 + 运行」对用户呈现为单一动作——
    // 全程按钮 loading，中间不弹保存成功提示，只在结束时弹一次结果。
    // 针对长耗时接口（可能 1 分钟+）：渐进式进度反馈，避免"点击后无感"。
    async function runScreen() {
        const runBtn = document.getElementById('runBtn');
        if (runBtn && runBtn.classList.contains('loading')) return; // 防重复点击

        if (!state.currentPlanId) {
            StockApp.toast('请先在左侧新建或选中一个方案', 'warning');
            return;
        }

        setRunLoading(true);
        runProgress.start();   // 启动渐进式进度反馈：按钮文案递进 + 结果面板骨架屏 + 计时器
        try {
            // 静默保存（含校验），失败则终止——校验错误仍会以 alert 形式提示（阻断性）
            const saved = await savePlan(true);
            if (!saved) {
                runProgress.stop();
                setRunLoading(false);
                return;
            }

            const date = document.getElementById('screenDate').value || '';
            const universe = document.getElementById('universeSel').value;
            StockApp.post('/screener/plans/' + encodeURIComponent(state.currentPlanId) + '/run', {
                date: date || undefined,
                universe: universe,
            }, function (resp) {
                runProgress.stop();
                setRunLoading(false);
                if (resp.code === 200 && resp.data) {
                    state.lastResult = resp.data;
                    renderResult(resp.data);
                    document.getElementById('lockBtn').disabled = false;
                    expandResultPanel();
                    StockApp.toast('选股完成，命中 ' + (resp.data.totalCount || 0) + ' 只（耗时 ' + runProgress.lastElapsedSec() + 's）', 'success');
                } else {
                    StockApp.toast(resp.message || '运行选股失败', 'danger');
                }
            });
        } catch (e) {
            runProgress.stop();
            setRunLoading(false);
            StockApp.toast('运行选股失败', 'danger');
        }
    }

    // 渐进式进度反馈模块：让长耗时接口"活着"
    // - 按钮文字随时间递进（运行中 → 正在筛选 → 仍在计算…）
    // - 运行后立即展开结果面板，展示骨架屏 + 计时器 + 超时安抚文案
    const runProgress = (function () {
        const stages = [
            { at: 0,    btn: '运行中…',     tip: '正在保存方案配置' },
            { at: 3,    btn: '运行中…',     tip: '正在解析候选池与条件' },
            { at: 8,    btn: '筛选中…',     tip: '正在拉取行情并对全市场打分' },
            { at: 18,   btn: '计算中…',     tip: '仍在计算，多因子排序较耗时' },
            { at: 30,   btn: '计算中…',     tip: '耗时较长属正常现象，可保留窗口继续等待' },
            { at: 60,   btn: '继续等待…',   tip: '已等待 1 分钟，仍在计算，请稍候' },
        ];
        let timer = null;
        let startedAt = 0;
        let lastElapsed = 0;

        function curStageIdx(elapsedSec) {
            let idx = 0;
            for (let i = 0; i < stages.length; i++) {
                if (elapsedSec >= stages[i].at) idx = i;
            }
            return idx;
        }

        function tick() {
            const elapsedSec = Math.floor((Date.now() - startedAt) / 1000);
            lastElapsed = elapsedSec;
            const idx = curStageIdx(elapsedSec);
            const stage = stages[idx];

            // 1) 更新按钮文字（保留旋转图标）
            const spinner = document.querySelector('#runBtn .btn-spinner');
            if (spinner) spinner.innerHTML = '<i class="bi bi-arrow-repeat"></i> ' + e(stage.btn);

            // 2) 更新结果面板内的进度面板
            const body = document.getElementById('resultBody');
            const summary = document.getElementById('resultSummary');
            if (summary) {
                summary.innerHTML = `
                    <div class="rs-item"><span class="rs-k">已耗时</span><span class="rs-v strong sw-elapsed">${elapsedSec}s</span></div>
                    <div class="rs-item"><span class="rs-k">状态</span><span class="rs-v sw-stage-tip">${e(stage.tip)}</span></div>`;
            }
            if (body) {
                const stageDots = stages.map((s, i) =>
                    `<span class="sw-step-dot${i <= idx ? ' done' : ''}"></span>`).join('');
                body.innerHTML = `
                    <div class="sw-run-progress">
                        <div class="sw-rp-icon"><i class="bi bi-arrow-repeat"></i></div>
                        <div class="sw-rp-steps">${stageDots}</div>
                        <div class="sw-rp-tip">${e(stage.tip)}</div>
                        <div class="sw-rp-skeleton">
                            ${Array.from({length: 6}).map(() => '<div class="sw-sk-row"></div>').join('')}
                        </div>
                    </div>`;
            }
        }

        function start() {
            stop();
            startedAt = Date.now();
            lastElapsed = 0;
            expandResultPanel();   // 确保面板可见，让用户看到工作区
            tick();
            timer = setInterval(tick, 1000);
        }

        function stop() {
            if (timer) { clearInterval(timer); timer = null; }
            if (startedAt) lastElapsed = Math.floor((Date.now() - startedAt) / 1000);
        }

        function lastElapsedSec() { return lastElapsed; }

        return { start, stop, lastElapsedSec };
    })();

    function setRunLoading(loading) {
        const runBtn = document.getElementById('runBtn');
        if (!runBtn) return;
        const label = runBtn.querySelector('.btn-label');
        const spinner = runBtn.querySelector('.btn-spinner');
        if (loading) {
            runBtn.classList.add('loading');
            runBtn.disabled = true;
            if (label) label.hidden = true;
            if (spinner) spinner.hidden = false;
        } else {
            runBtn.classList.remove('loading');
            runBtn.disabled = false;
            if (label) label.hidden = false;
            if (spinner) spinner.hidden = true;
        }
    }

    // ===================== 结果面板：展开 / 折叠 =====================
    function expandResultPanel() {
        const workspace = document.querySelector('.screener-workspace');
        const toggle = document.getElementById('resultPanelToggle');
        if (workspace) workspace.classList.remove('result-collapsed');
        if (toggle) {
            toggle.title = '折叠结果面板';
            const icon = toggle.querySelector('i');
            if (icon) icon.className = 'bi bi-chevron-right';
        }
    }

    function collapseResultPanel() {
        const workspace = document.querySelector('.screener-workspace');
        const toggle = document.getElementById('resultPanelToggle');
        if (workspace) workspace.classList.add('result-collapsed');
        if (toggle) {
            toggle.title = '展开结果面板';
            const icon = toggle.querySelector('i');
            if (icon) icon.className = 'bi bi-chevron-left';
        }
    }

    function bindResultPanelToggle() {
        const showBtn = document.getElementById('resultShowBtn');
        if (showBtn) showBtn.addEventListener('click', expandResultPanel);
    }

    // ===================== 渲染结果 =====================
    function renderResult(result) {
        const body = document.getElementById('resultBody');
        const summary = document.getElementById('resultSummary');
        if (!result) {
            summary.innerHTML = '';
            body.innerHTML = `<div class="sw-empty"><i class="bi bi-search" style="font-size:24px;"></i><div>配置条件后点击「运行选股」</div></div>`;
            return;
        }
        let stocks = [];
        try { stocks = typeof result.stocksJson === 'string' ? JSON.parse(result.stocksJson) : (result.stocksJson || []); }
        catch (_) { stocks = []; }

        summary.innerHTML = `
            <div class="rs-item"><span class="rs-k">选股日期</span><span class="rs-v">${e(result.screenDate || '—')}</span></div>
            <div class="rs-item"><span class="rs-k">命中数</span><span class="rs-v strong">${result.totalCount || 0}</span></div>
        `;

        if (!stocks.length) {
            body.innerHTML = '<div class="sw-empty">无命中股票</div>';
            return;
        }

        const factorKeys = collectFactorKeys(stocks);
        body.innerHTML = `
            <table class="sw-result-table">
                <thead><tr>
                    <th style="width:42px;">#</th>
                    <th>代码</th>
                    <th style="width:64px;">得分</th>
                    ${factorKeys.map(k => `<th>${e(k)}</th>`).join('')}
                </tr></thead>
                <tbody>
                    ${stocks.map(s => {
                        const rank = s.rank != null ? s.rank : '';
                        const score = s.score != null ? s.score : '—';
                        const fvs = (s.factor_values || s.factorValues || {});
                        return `<tr>
                            <td><span class="rank-badge">${rank}</span></td>
                            <td class="mono">${e(s.symbol)}</td>
                            <td class="score-cell">${typeof score === 'number' ? score.toFixed(3) : e(score)}</td>
                            ${factorKeys.map(k => `<td>${fmtNum(fvs[k])}</td>`).join('')}
                        </tr>`;
                    }).join('')}
                </tbody>
            </table>
        `;
    }

    function collectFactorKeys(stocks) {
        const set = new Set();
        stocks.forEach(s => { Object.keys(s.factor_values || s.factorValues || {}).forEach(k => set.add(k)); });
        return Array.from(set);
    }

    function fmtNum(v) {
        if (v == null || v === '') return '—';
        const n = parseFloat(v);
        if (Number.isNaN(n)) return e(String(v));
        return Number.isInteger(n) ? n : n.toFixed(3);
    }

    // ===================== 锁定 =====================
    function lockResult() {
        if (!state.lastResult || !state.lastResult.id) {
            StockApp.toast('没有可锁定的结果', 'warning');
            return;
        }
        StockApp.post('/screener/results/' + encodeURIComponent(state.lastResult.id) + '/lock', null, function (resp) {
            if (resp.code === 200) {
                StockApp.toast('结果已锁定，开始追踪', 'success');
            } else {
                StockApp.toast(resp.message || '锁定失败', 'danger');
            }
        });
    }

    // ===================== 工具 =====================
    function splitCsv(str) {
        return (str || '').split(',').map(s => s.trim()).filter(Boolean);
    }

    function formatTime(t) {
        if (!t) return '—';
        const s = String(t);
        return s.length >= 10 ? s.slice(0, 10) : s;
    }

    // ===================== 条件树事件委托 =====================
    function parsePath(raw) {
        // 根分组的 data-path="" → 空路径 []；其余 "0,1,2" → [0,1,2]
        if (raw == null || raw === '') return [];
        return raw.split(',').map(Number);
    }

    function bindTreeEvents() {
        const treeEl = document.getElementById('conditionTree');
        if (!treeEl) return;
        treeEl.addEventListener('click', ev => {
            const btn = ev.target.closest('[data-action]');
            if (!btn) return;
            const path = parsePath(btn.dataset.path);
            const action = btn.dataset.action;
            if (action === 'toggleOperator') toggleOperator(path);
            else if (action === 'addLeaf') addLeaf(path);
            else if (action === 'addGroup') addGroup(path);
            else if (action === 'removeNode') removeNode(path);
            markDirty();   // 结构性变更
        });
        treeEl.addEventListener('change', ev => {
            // 比较器 <select class="leaf-cmp"> 无 data-field，需在 closest('[data-field]') 之前特判
            const cmpEl = ev.target.closest('.leaf-cmp');
            if (cmpEl) {
                const path = parsePath(cmpEl.dataset.path);
                const node = nodeAt(path);
                if (node && node.type === LEAF_TYPE) node.comparator = cmpEl.value;
                markDirty();
                return;
            }
            const field = ev.target.closest('[data-field]');
            if (!field) return;
            const path = parsePath(field.dataset.path);
            const side = field.dataset.side;
            updateLeaf(path, side, field.dataset.field, field.value);
            markDirty();
        });
    }

    // 编辑器其它输入（名称/备注/过滤/排序/候选池/日期）脏数据监听
    function bindDirtyWatchers() {
        const editor = document.querySelector('.sw-editor');
        if (editor) {
            editor.addEventListener('input', ev => {
                if (ev.target.matches('input, textarea, select')) markDirty();
            });
            editor.addEventListener('change', ev => {
                if (ev.target.matches('input, select')) markDirty();
            });
        }
        // 顶部候选池/日期也算方案内容变化（universe 持久化在 screenConfig）
        const universe = document.getElementById('universeSel');
        if (universe) universe.addEventListener('change', markDirty);
    }

    // 刷新/离开页面拦截（脏数据时）
    window.addEventListener('beforeunload', ev => {
        if (state.dirty) {
            ev.preventDefault();
            ev.returnValue = '';
        }
    });

    // ===================== 初始化 =====================
    function bindEvents() {
        document.querySelectorAll('.sw-tab').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));
        bindRankMethod();
        bindTreeEvents();
        bindDirtyWatchers();
        bindResultPanelToggle();
        const d = new Date();
        document.getElementById('screenDate').value = d.toISOString().slice(0, 10);
    }

    async function init() {
        try {
            await loadFactors();
            fillFactorSelects();
            applyConfig(defaultConfig());
            bindEvents();
            bindManualSearchSuggest();
            updateStatusUI();   // 初始：未选中方案
            await loadPlans();
            if (state.plans.length && !state.currentPlanId) {
                selectPlan(state.plans[0].id);
            }
        } catch (err) {
            StockApp.toast('初始化失败：' + (err.message || err), 'danger');
        }
    }

    // 绑定 manual 候选股搜索提示
    function bindManualSearchSuggest() {
        const planInput = document.getElementById('manualSearchInput');
        if (planInput && typeof SearchSuggest !== 'undefined') {
            new SearchSuggest(planInput, {
                onSelect: function (item) {
                    addManualStock(item);
                    planInput.value = '';
                    planInput.focus();
                }
            });
        }
    }

    document.addEventListener('DOMContentLoaded', init);

    return {
        createPlan, deletePlan, selectPlan,
        addLeaf, addGroup, removeNode, toggleOperator,
        addCondition: function () { addLeaf([]); },   // 向后兼容
        addWeight, runScreen, savePlan, lockResult,
        expandResultPanel, collapseResultPanel,
        // manual 候选股管理
        onUniverseChange, addManualStock, removeManualStock,
    };
})();
