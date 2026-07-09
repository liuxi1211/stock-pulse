/**
 * 可复用条件树可视化编辑器（ConditionTreeBuilder）。
 *
 * 与 engine 统一策略配置 Schema §4 对齐，操作数支持 4 种 ExpressionNode 形态：
 *   - ValueNode  : { value: <number|string> }
 *   - FactorNode : { factor, params, inputs, output_index }
 *   - OpNode     : { op:'+'|'-'|'*'|'/', left, right }（递归）
 *   - RefNode    : { ref:'entry_price' }（仅 trading_config 合法）
 *
 * 与 screener.js 的关键差异：策略形态**没有 kind 字段**，形态判断靠字段存在性。
 *
 * 用法：
 *   const ctrl = ConditionTreeBuilder.mount(container, {
 *       tree, onChange, allowRef, allowCross, factors, categories, refWhitelist,
 *   });
 *   ctrl.getTree(); ctrl.setTree(tree); ctrl.destroy();
 */
const ConditionTreeBuilder = (function () {
    'use strict';

    const e = (window.StockApp && StockApp.escapeHtml) || escapeHtmlFallback;

    function escapeHtmlFallback(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // ===================== 常量（策略 Schema §4 对齐）=====================
    const LOGIC_OPERATORS = { AND: 'AND', OR: 'OR' };
    const LEAF_TYPE = 'compare';
    const BASE_COMPARATORS = ['>', '<', '>=', '<=', '==', '!='];
    const CROSS_COMPARATORS = ['cross_up', 'cross_down'];
    const OP_KINDS = { FACTOR: 'factor', VALUE: 'value', REF: 'ref' };

    const DEFAULT_REF_WHITELIST = [
        { ref: 'entry_price', label: '入场价 entry_price' },
        { ref: 'entry_price_pct', label: '入场价百分比 entry_price_pct' },
        { ref: 'position_pnl_pct', label: '持仓盈亏% position_pnl_pct' },
        { ref: 'position_pnl', label: '持仓盈亏 position_pnl' },
        { ref: 'hold_bars', label: '持仓时长 hold_bars' },
        { ref: 'position_value', label: '持仓市值 position_value' },
        { ref: 'cash', label: '可用现金 cash' },
        { ref: 'equity', label: '总权益 equity' },
    ];

    // ===================== 实例工厂 =====================
    function createInstance(opts) {
        const options = opts || {};
        const factors = Array.isArray(options.factors) ? options.factors : [];
        const categories = Array.isArray(options.categories) ? options.categories : [];
        const allowRef = !!options.allowRef;
        const allowCross = !!options.allowCross;
        const onChangeCb = typeof options.onChange === 'function' ? options.onChange : null;
        const refWhitelist = Array.isArray(options.refWhitelist) && options.refWhitelist.length
            ? options.refWhitelist : DEFAULT_REF_WHITELIST;

        let tree = normalizeTree(options.tree);
        let container = null;
        let rootEl = null;
        let destroyed = false;

        function notify() {
            if (onChangeCb) onChangeCb(deepClone(tree));
        }

        // ===================== 归一化（策略 4 形态，无 kind 字段）=====================
        function normalizeTree(cond) {
            if (!cond) return { operator: LOGIC_OPERATORS.AND, conditions: [] };
            if (Array.isArray(cond)) {
                return {
                    operator: LOGIC_OPERATORS.AND,
                    conditions: cond.map(normalizeNode).filter(Boolean),
                };
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
                comparator: leaf.comparator || BASE_COMPARATORS[0],
                right: normalizeOperand(leaf.right),
            };
        }

        function normalizeOperand(op) {
            if (!op) return { value: 0 };
            if ('factor' in op) {
                const def = getFactorDef(op.factor);
                return {
                    factor: op.factor || '',
                    params: op.params || getDefaultParams(def) || {},
                    inputs: op.inputs || undefined,
                    output_index: op.output_index != null
                        ? op.output_index
                        : (def && def.defaultOutputIndex != null ? def.defaultOutputIndex : 0),
                };
            }
            if ('ref' in op) {
                return { ref: op.ref || '' };
            }
            if ('op' in op) {
                return {
                    op: op.op,
                    left: normalizeOperand(op.left),
                    right: normalizeOperand(op.right),
                };
            }
            return { value: op.value != null ? op.value : 0 };
        }

        function operandKind(op) {
            if (!op) return OP_KINDS.VALUE;
            if ('factor' in op) return OP_KINDS.FACTOR;
            if ('ref' in op) return OP_KINDS.REF;
            if ('op' in op) return 'op';
            return OP_KINDS.VALUE;
        }

        // ===================== 因子元数据辅助 =====================
        function getFactorDef(key) {
            return factors.find(f => f.factorKey === key) || null;
        }

        function getDefaultParams(def) {
            const p = {};
            (def && def.params || []).forEach(x => { p[x.name] = x.defaultValue; });
            return p;
        }

        function buildFactorOptions(selected) {
            const byCat = {};
            factors.forEach(f => { (byCat[f.category] = byCat[f.category] || []).push(f); });
            const catOrder = categories.map(c => c.key);
            Object.keys(byCat).forEach(k => { if (!catOrder.includes(k)) catOrder.push(k); });
            let html = '<option value="">— 选择因子 —</option>';
            catOrder.forEach(ck => {
                if (!byCat[ck]) return;
                const catName = (categories.find(c => c.key === ck) || {}).name || ck;
                const opts = byCat[ck].map(f =>
                    `<option value="${e(f.factorKey)}"${f.factorKey === selected ? ' selected' : ''}>${e(f.factorKey)} · ${e(f.displayName || '')}</option>`
                ).join('');
                html += `<optgroup label="${e(catName)}">${opts}</optgroup>`;
            });
            return html;
        }

        function buildRefOptions(selected) {
            return refWhitelist.map(r =>
                `<option value="${e(r.ref)}"${r.ref === selected ? ' selected' : ''}>${e(r.label || r.ref)}</option>`
            ).join('');
        }

        function comparatorList() {
            return allowCross ? BASE_COMPARATORS.concat(CROSS_COMPARATORS) : BASE_COMPARATORS;
        }

        // ===================== 寻址与变更 =====================
        function nodeAt(path) {
            let node = tree;
            for (const idx of path) { node = node.conditions[idx]; }
            return node;
        }

        function parentAt(path) {
            if (path.length === 0) return null;
            let node = tree;
            for (let i = 0; i < path.length - 1; i++) { node = node.conditions[path[i]]; }
            return node;
        }

        function defaultLeaf() {
            return {
                type: LEAF_TYPE,
                left: { factor: '', params: {}, output_index: 0 },
                comparator: BASE_COMPARATORS[0],
                right: { value: 0 },
            };
        }

        function toggleOperator(path) {
            const node = nodeAt(path);
            if (node && 'operator' in node) {
                node.operator = node.operator === LOGIC_OPERATORS.AND ? LOGIC_OPERATORS.OR : LOGIC_OPERATORS.AND;
                render();
                notify();
            }
        }

        function addLeaf(path) {
            const node = nodeAt(path);
            if (node && 'conditions' in node) {
                node.conditions.push(defaultLeaf());
                render();
                notify();
            }
        }

        function addGroup(path) {
            const node = nodeAt(path);
            if (node && 'conditions' in node) {
                node.conditions.push({ operator: LOGIC_OPERATORS.AND, conditions: [] });
                render();
                notify();
            }
        }

        function removeNode(path) {
            if (path.length === 0) return;
            const parent = parentAt(path);
            const idx = path[path.length - 1];
            parent.conditions.splice(idx, 1);
            render();
            notify();
        }

        function updateLeaf(path, side, field, value) {
            const node = nodeAt(path);
            if (!node || node.type !== LEAF_TYPE) return;
            const operand = node[side];

            if (field === 'type') {
                if (value === OP_KINDS.FACTOR) {
                    node[side] = { factor: '', params: {}, output_index: 0 };
                } else if (value === OP_KINDS.REF) {
                    node[side] = { ref: refWhitelist.length ? refWhitelist[0].ref : '' };
                } else {
                    node[side] = { value: 0 };
                }
                render();
                notify();
                return;
            }
            if (field === OP_KINDS.FACTOR) {
                operand.factor = value;
                const def = getFactorDef(value);
                operand.params = getDefaultParams(def);
                operand.output_index = (def && def.defaultOutputIndex != null) ? def.defaultOutputIndex : 0;
                render();
                notify();
                return;
            }
            if (field === OP_KINDS.VALUE) {
                operand.value = value === '' ? 0 : Number(value);
                notify();
                return;
            }
            if (field === OP_KINDS.REF) {
                operand.ref = value;
                notify();
                return;
            }
            if (field === 'output') {
                operand.output_index = Number(value);
                notify();
                return;
            }
            if (field.indexOf('param-') === 0) {
                const name = field.slice(6);
                operand.params[name] = value === '' ? null : Number(value);
                notify();
                return;
            }
        }

        // ===================== 渲染 =====================
        function render() {
            if (!rootEl) return;
            // 始终渲染根分组节点（含「添加条件 / 添加分组」按钮），让空树也能录入。
            // 此前空态只显示提示文字、无操作入口，导致用户新建策略时无法在可视化模式添加条件。
            rootEl.innerHTML = renderNode(tree, []);
        }

        function renderNode(node, path) {
            if ('operator' in node) {
                const conds = node.conditions || [];
                const childrenHtml = conds.map((c, i) => renderNode(c, [...path, i])).join('');
                const removeHtml = path.length
                    ? `<button class="ctb-tree-act-btn danger" data-action="removeNode" data-path="${path.join(',')}" type="button"><i class="bi bi-trash3"></i> 删除分组</button>`
                    : '';
                const emptyHint = conds.length === 0
                    ? `<div class="ctb-children-empty">暂无条件，点击上方「条件」或「分组」开始构建</div>`
                    : '';
                return `
                    <div class="ctb-group-node" data-path="${path.join(',')}">
                        <div class="ctb-group-header">
                            <span class="ctb-operator-badge ${node.operator}" data-action="toggleOperator" data-path="${path.join(',')}" role="button" tabindex="0">${node.operator}</span>
                            <div class="ctb-tree-actions">
                                <button class="ctb-tree-act-btn" data-action="addLeaf" data-path="${path.join(',')}" type="button"><i class="bi bi-plus-lg"></i> 条件</button>
                                <button class="ctb-tree-act-btn" data-action="addGroup" data-path="${path.join(',')}" type="button"><i class="bi bi-diagram-3"></i> 分组</button>
                                ${removeHtml}
                            </div>
                        </div>
                        <div class="ctb-children">${emptyHint}${childrenHtml}</div>
                    </div>`;
            }
            return renderLeaf(node, path);
        }

        function renderLeaf(leaf, path) {
            const pathStr = path.join(',');
            const cmps = comparatorList();
            return `
                <div class="ctb-leaf-node" data-path="${pathStr}">
                    ${renderExpression(leaf.left, 'left', pathStr)}
                    <div class="ctb-comparator-badge">
                        <select class="ctb-leaf-cmp" data-path="${pathStr}">
                            ${cmps.map(c => `<option value="${c}"${c === leaf.comparator ? ' selected' : ''}>${c}</option>`).join('')}
                        </select>
                    </div>
                    ${renderExpression(leaf.right, 'right', pathStr)}
                    <button class="ctb-node-remove" data-action="removeNode" data-path="${pathStr}" type="button" aria-label="删除条件"><i class="bi bi-x-lg"></i></button>
                </div>`;
        }

        function renderExpression(operand, side, pathStr) {
            const kind = operandKind(operand);

            const typeOpts = [`<option value="${OP_KINDS.FACTOR}"${kind === OP_KINDS.FACTOR ? ' selected' : ''}>因子</option>`];
            typeOpts.push(`<option value="${OP_KINDS.VALUE}"${kind === OP_KINDS.VALUE ? ' selected' : ''}>数值</option>`);
            if (allowRef) {
                typeOpts.push(`<option value="${OP_KINDS.REF}"${kind === OP_KINDS.REF ? ' selected' : ''}>引用</option>`);
            }
            const typeSelect = `<select class="form-select form-select-sm ctb-op-type" data-field="type" data-path="${pathStr}" data-side="${side}">${typeOpts.join('')}</select>`;

            let bodyHtml = '';
            let paramsHtml = '';
            let outputHtml = '';

            if (kind === OP_KINDS.FACTOR) {
                bodyHtml = `<select class="form-select form-select-sm ctb-op-factor" data-field="factor" data-path="${pathStr}" data-side="${side}">${buildFactorOptions(operand.factor)}</select>`;
                const def = getFactorDef(operand.factor);
                const params = def && def.params ? def.params : [];
                if (params.length) {
                    paramsHtml = `<div class="ctb-expr-params">${params.map(p => renderParamInput(p, operand.params && operand.params[p.name], pathStr, side)).join('')}</div>`;
                }
                if (def && def.multiOutput && def.outputLabels && def.outputLabels.length) {
                    const cur = operand.output_index != null ? operand.output_index : (def.defaultOutputIndex || 0);
                    outputHtml = `<div class="ctb-output-field"><label>输出</label><select class="form-select form-select-sm ctb-op-output" data-field="output" data-path="${pathStr}" data-side="${side}">${def.outputLabels.map((label, i) => `<option value="${i}"${i === cur ? ' selected' : ''}>[${i}] ${e(label)}</option>`).join('')}</select></div>`;
                }
            } else if (kind === OP_KINDS.REF) {
                bodyHtml = `<select class="form-select form-select-sm ctb-op-ref" data-field="ref" data-path="${pathStr}" data-side="${side}">${buildRefOptions(operand.ref)}</select>`;
            } else {
                const v = operand.value != null ? operand.value : 0;
                bodyHtml = `<input type="number" step="any" class="form-control form-control-sm ctb-op-value" data-field="value" data-path="${pathStr}" data-side="${side}" value="${e(String(v))}" placeholder="数值">`;
            }

            return `
                <div class="ctb-op-block">
                    <div class="ctb-expr-head">${typeSelect}${bodyHtml}</div>
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
                <div class="ctb-param-field">
                    <label>${e(param.displayName || param.name)}</label>
                    <input type="number" step="${step}" ${min} ${max} class="form-control ctb-op-param" data-field="param-${param.name}" data-path="${pathStr}" data-side="${side}" value="${e(String(val))}">
                </div>`;
        }

        // ===================== 事件绑定 ======================
        function parsePath(raw) {
            if (raw == null || raw === '') return [];
            return raw.split(',').map(Number);
        }

        function onClick(ev) {
            const btn = ev.target.closest('[data-action]');
            if (!btn) return;
            const path = parsePath(btn.dataset.path);
            const action = btn.dataset.action;
            if (action === 'toggleOperator') toggleOperator(path);
            else if (action === 'addLeaf') addLeaf(path);
            else if (action === 'addGroup') addGroup(path);
            else if (action === 'removeNode') removeNode(path);
        }

        function onChange(ev) {
            const cmpEl = ev.target.closest('.ctb-leaf-cmp');
            if (cmpEl) {
                const path = parsePath(cmpEl.dataset.path);
                const node = nodeAt(path);
                if (node && node.type === LEAF_TYPE) {
                    node.comparator = cmpEl.value;
                    notify();
                }
                return;
            }
            const field = ev.target.closest('[data-field]');
            if (!field) return;
            const path = parsePath(field.dataset.path);
            const side = field.dataset.side;
            updateLeaf(path, side, field.dataset.field, field.value);
        }

        // ===================== 生命周期 =====================
        function mount(host) {
            container = host;
            rootEl = document.createElement('div');
            rootEl.className = 'ctb-root';
            container.appendChild(rootEl);
            rootEl.addEventListener('click', onClick);
            rootEl.addEventListener('change', onChange);
            render();
        }

        function destroy() {
            if (destroyed) return;
            destroyed = true;
            if (rootEl) {
                rootEl.removeEventListener('click', onClick);
                rootEl.removeEventListener('change', onChange);
                if (rootEl.parentNode) rootEl.parentNode.removeChild(rootEl);
                rootEl = null;
            }
            container = null;
        }

        function getTree() {
            return deepClone(tree);
        }

        function setTree(newTree) {
            tree = normalizeTree(newTree);
            render();
            notify();
        }

        return {
            mount: mount,
            destroy: destroy,
            getTree: getTree,
            setTree: setTree,
        };
    }

    function deepClone(obj) {
        if (obj == null || typeof obj !== 'object') return obj;
        if (Array.isArray(obj)) return obj.map(deepClone);
        const out = {};
        for (const k in obj) {
            if (Object.prototype.hasOwnProperty.call(obj, k)) out[k] = deepClone(obj[k]);
        }
        return out;
    }

    // ===================== 因子白名单加载（Task 3）=====================
    async function fetchJson(url) {
        const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
        return r.json();
    }

    async function loadFactors() {
        const [catResp, facResp] = await Promise.all([
            fetchJson('/factors/categories'),
            fetchJson('/factors'),
        ]);
        return {
            factors: (facResp && facResp.data) || [],
            categories: (catResp && catResp.data) || [],
        };
    }

    // ===================== 公开 API =====================
    function mount(container, options) {
        if (!container) throw new Error('ConditionTreeBuilder.mount: container 不能为空');
        const instance = createInstance(options);
        instance.mount(container);
        return instance;
    }

    return {
        mount: mount,
        loadFactors: loadFactors,
        deepClone: deepClone,
    };
})();

window.ConditionTreeBuilder = ConditionTreeBuilder;
