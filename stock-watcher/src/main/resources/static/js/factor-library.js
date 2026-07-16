/**
 * 因子库页面逻辑（API 驱动）。
 * - 元数据 / CRUD：GET/POST/PUT/DELETE /factors（watcher Caffeine 缓存）
 * - 试算台：GET /kline/{code}?startDate=...&endDate=... 取最近 1 年 OHLCV →
 *   POST /factors/compute 走 engine akquant.talib 真实计算
 *   （无 DB 行情时回退合成数据，仍走真实计算）
 */
const FactorLib = (function () {
    'use strict';

    // ===================== 常量（与后端 Schema 对齐，详见 rules/frontend/07-constants-usage.md）=====================
    // B 类：因子来源 source —— 权威来源 engine models/schemas/factor.py FactorDef.source: Literal["AKQUANT","TUSHARE","RAW","DERIVED"]
    // computable 与 engine services/screener/factor_precompute.py 的 _TECH_SOURCES={"AKQUANT","RAW","DERIVED"} 对齐（TUSHARE 不可计算）
    const FACTOR_SOURCES = {
        AKQUANT: { label: 'AKQUANT', color: 'var(--src-akquant)', computable: true },
        TUSHARE: { label: 'TUSHARE', color: 'var(--src-tushare)', computable: false },
        RAW:     { label: 'RAW',     color: 'var(--src-raw)',     computable: true },
        DERIVED: { label: 'DERIVED', color: 'var(--src-derived)', computable: true },
    };
    // 单点比较用 sentinel（避免 'TUSHARE' 字面量散落多处）
    const SOURCE_TUSHARE = 'TUSHARE';
    const SOURCE_DEFAULT_NEW = 'AKQUANT';
    // B 类：因子输入列 —— 权威来源 engine models/schemas/factor.py FactorDef.inputs（OHLCV 五列）
    const OHLCV_COLUMNS = ['open', 'high', 'low', 'close', 'volume'];
    // C 类：分类图标（前端私有展示，无后端对应）
    const CAT_ICONS = {
        OVERLAP: 'bi-activity', MOMENTUM: 'bi-speedometer', VOLATILITY: 'bi-wave',
        VOLUME: 'bi-bar-chart-fill', STATISTIC: 'bi-calculator', PRICE: 'bi-cash-coin',
        VALUATION: 'bi-piggy-bank', QUALITY: 'bi-award', GROWTH: 'bi-graph-up-arrow', FINANCE: 'bi-bank',
    };
    // B 类：因子数据源 dataSource —— 权威来源 engine models/schemas/factor.py FactorDef.dataSource
    // 展示 label 为前端私有文案（"OHLCV K 线"等不属后端 Schema）
    const FACTOR_DATA_SOURCES = [
        { code: 'ohlcv',         label: 'ohlcv — OHLCV K 线' },
        { code: 'daily_basic',   label: 'daily_basic — 每日基本面' },
        { code: 'fina_indicator', label: 'fina_indicator — 财务指标' },
    ];
    // B 类：因子来源 source 的展示用列表（label 含说明文案，前端私有）—— code 与 FACTOR_SOURCES 同源
    const FACTOR_SOURCE_OPTIONS = [
        { code: 'AKQUANT', label: 'AKQUANT — 走 akquant.talib' },
        { code: 'DERIVED', label: 'DERIVED — 复用基础函数衍生' },
        { code: 'RAW',     label: 'RAW — 价格/量直通' },
        { code: 'TUSHARE', label: 'TUSHARE — 仅元数据' },
    ];
    // C 类：分类/来源过滤的"全部"哨位（前端状态机私有，无后端对应）
    const ALL_SENTINEL = 'ALL';
    // C 类：试算台内置股票（前端演示样本）
    const STOCKS = [
        { code: '600519', name: '贵州茅台' },
        { code: '300750', name: '宁德时代' },
        { code: '000001', name: '平安银行' },
    ];

    let FACTORS = [];
    let FACTOR_MAP = {};
    let CATEGORIES = [];
    const state = { cat: ALL_SENTINEL, src: ALL_SENTINEL, q: '', onlyCompute: false, sort: 'cat', selected: null, pgParams: {}, pgOutputIdx: null };
    const PG = { ohlcv: null, code: '600519', synthetic: false, _loadedCode: null };
    let detailKey = null; // 当前已渲染的因子 key，用于避免切换输出时误重置参数/输出索引

    const e = StockApp.escapeHtml;

    // ===================== API =====================
    async function fetchJson(url) {
        const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
        return r.json();
    }

    async function loadAll() {
        const [catResp, facResp] = await Promise.all([
            fetchJson('/factors/categories'),
            fetchJson('/factors'),
        ]);
        CATEGORIES = catResp.data || [];
        FACTORS = facResp.data || [];
        FACTOR_MAP = Object.fromEntries(FACTORS.map(f => [f.factorKey, f]));
        if (!state.selected) {
            const first = FACTOR_MAP['MACD'] ? 'MACD'
                : (FACTORS.find(f => FACTOR_SOURCES[f.source] && FACTOR_SOURCES[f.source].computable) || FACTORS[0] || {}).factorKey;
            state.selected = first || null;
        }
    }

    async function refresh() {
        StockApp.toast('正在刷新…', 'info');
        await loadAll();
        renderAll();
        StockApp.toast('已刷新（缓存已在写操作时失效）', 'success');
    }

    // ===================== 渲染 =====================
    function renderAll() { renderStats(); renderLegend(); renderRail(); renderTable(); renderDetail(); }

    function renderStats() {
        const total = FACTORS.length;
        const compute = FACTORS.filter(f => FACTOR_SOURCES[f.source] && FACTOR_SOURCES[f.source].computable).length;
        const fund = FACTORS.filter(f => f.source === SOURCE_TUSHARE).length;
        const multi = FACTORS.filter(f => f.multiOutput).length;
        document.getElementById('topCount').textContent = total + ' factors';
        document.getElementById('statTotal').innerHTML = total + '<span class="unit">个</span>';
        document.getElementById('statTotalFoot').textContent = CATEGORIES.length + ' 个分类 · 唯一 factorKey';
        document.getElementById('statCompute').innerHTML = compute + '<span class="unit">个</span>';
        document.getElementById('statFund').innerHTML = fund + '<span class="unit">个</span>';
        document.getElementById('statMulti').innerHTML = multi + '<span class="unit">个</span>';
    }

    function renderLegend() {
        const wrap = document.getElementById('legendChips');
        const counts = {};
        FACTORS.forEach(f => { counts[f.source] = (counts[f.source] || 0) + 1; });
        let html = `<div class="src-chip ${state.src === ALL_SENTINEL ? 'active' : ''}" style="--cc: var(--text-muted);" data-src="${ALL_SENTINEL}">
            <span class="swatch" style="background: var(--text-muted); box-shadow:none;"></span>全部<span class="num">${FACTORS.length}</span></div>`;
        Object.keys(FACTOR_SOURCES).forEach(s => {
            const m = FACTOR_SOURCES[s];
            html += `<div class="src-chip ${state.src === s ? 'active' : ''}" style="--cc: ${m.color};" data-src="${s}">
                <span class="swatch"></span>${m.label}<span class="num">${counts[s] || 0}</span></div>`;
        });
        wrap.innerHTML = html;
        wrap.querySelectorAll('.src-chip').forEach(el => el.onclick = () => { state.src = el.dataset.src; renderLegend(); renderTable(); });
    }

    function renderRail() {
        const list = document.getElementById('catList');
        const counts = {};
        FACTORS.forEach(f => { counts[f.category] = (counts[f.category] || 0) + 1; });
        const cats = [{ key: ALL_SENTINEL, name: '全部因子', icon: 'bi-grid-3x3-gap-fill' }]
            .concat(CATEGORIES.map(c => ({ key: c.key, name: c.name, icon: CAT_ICONS[c.key] || 'bi-tag' })));
        list.innerHTML = cats.map(c => `
            <div class="cat-item ${state.cat === c.key ? 'active' : ''}" data-cat="${c.key}">
                <span class="cat-name"><i class="bi ${c.icon} ico"></i>${e(c.name)}</span>
                <span class="cat-count">${c.key === ALL_SENTINEL ? FACTORS.length : (counts[c.key] || 0)}</span>
            </div>`).join('');
        list.querySelectorAll('.cat-item').forEach(el => el.onclick = () => { state.cat = el.dataset.cat; renderRail(); renderTable(); });
    }

    function getFiltered() {
        let arr = FACTORS.slice();
        if (state.cat !== ALL_SENTINEL) arr = arr.filter(f => f.category === state.cat);
        if (state.src !== ALL_SENTINEL) arr = arr.filter(f => f.source === state.src);
        if (state.onlyCompute) arr = arr.filter(f => FACTOR_SOURCES[f.source] && FACTOR_SOURCES[f.source].computable);
        if (state.q) {
            const q = state.q.toLowerCase();
            arr = arr.filter(f => f.factorKey.toLowerCase().includes(q)
                || (f.displayName || '').toLowerCase().includes(q)
                || (f.akquantFunc || '').toLowerCase().includes(q));
        }
        if (state.sort === 'cat') {
            const order = CATEGORIES.map(c => c.key);
            arr.sort((a, b) => order.indexOf(a.category) - order.indexOf(b.category) || a.factorKey.localeCompare(b.factorKey));
        } else if (state.sort === 'key') arr.sort((a, b) => a.factorKey.localeCompare(b.factorKey));
        else if (state.sort === 'lookback') arr.sort((a, b) => (b.lookbackDefault || 0) - (a.lookbackDefault || 0));
        return arr;
    }

    function renderTable() {
        const arr = getFiltered();
        document.getElementById('resultCount').textContent = arr.length + ' 项';
        const body = document.getElementById('factorBody');
        if (!arr.length) {
            body.innerHTML = `<tr><td colspan="6" class="empty-row"><i class="bi bi-search"></i>没有匹配的因子</td></tr>`;
            return;
        }
        body.innerHTML = arr.map(f => {
            const sm = FACTOR_SOURCES[f.source] || { label: f.source, color: 'var(--text-muted)', computable: false };
            const paramStr = (f.params && f.params.length)
                ? f.params.map(p => `<span class="pk">${e(p.name)}</span>=<span class="pv">${p.defaultValue}</span>`).join(' · ')
                : '<span style="color:var(--text-muted);">无参数</span>';
            const allInputs = OHLCV_COLUMNS;
            const inDots = (f.inputs && f.inputs.length)
                ? allInputs.map(i => `<span class="id ${f.inputs.includes(i) ? 'on' : ''}">${i[0].toUpperCase()}</span>`).join('')
                : '<span style="font-size:10px;color:var(--text-muted);">基本面</span>';
            const multiBadge = f.multiOutput ? `<span class="multi-pill">multi ×${(f.outputLabels || []).length}</span>` : '';
            const tfBadge = f.transformable ? `<span class="tf-pill" title="支持滚动窗口聚合（ma/std/pct_change/max/min）"><i class="bi bi-gear"></i> transform</span>` : '';
            const computable = sm.computable;
            return `<tr class="${state.selected === f.factorKey ? 'selected' : ''}" data-key="${e(f.factorKey)}" style="--src-bar: ${sm.color};">
                <td><span class="src-rail"></span><span class="fkey">${e(f.factorKey)}</span>${multiBadge}${tfBadge}
                    <div class="fname">${e(f.displayName || '')}</div></td>
                <td><span class="src-tag">${sm.label}</span></td>
                <td><div class="params-mini">${paramStr}</div></td>
                <td><div class="input-dots">${inDots}</div></td>
                <td><div class="lookback-cell">${f.lookbackDefault || 0}<div class="warm">bars 预热</div></div></td>
                <td><div class="row-actions">
                    <button class="mini-btn ${computable ? '' : 'disabled'}" data-act="try" title="${computable ? '试算' : 'engine 不可计算基本面因子'}" ${computable ? '' : 'disabled'}><i class="bi bi-${computable ? 'play-fill' : 'lock'}"></i></button>
                    <button class="mini-btn" data-act="edit" title="编辑"><i class="bi bi-pencil"></i></button>
                    <button class="mini-btn" data-act="del" title="删除"><i class="bi bi-trash3"></i></button>
                </div></td>
            </tr>`;
        }).join('');
        body.querySelectorAll('tr[data-key]').forEach(tr => {
            tr.addEventListener('click', ev => {
                if (ev.target.closest('button[data-act="del"]')) { confirmDelete(tr.dataset.key); return; }
                if (ev.target.closest('button[data-act="edit"]')) { openEditModal(tr.dataset.key); return; }
                if (ev.target.closest('button[data-act="try"]')) { state.selected = tr.dataset.key; renderTable(); renderDetail(); return; }
                state.selected = tr.dataset.key;
                renderTable(); renderDetail();
            });
        });
    }

    function renderDetail() {
        const f = FACTOR_MAP[state.selected];
        const panel = document.getElementById('detailPanel');
        if (!f) { panel.innerHTML = '<div class="empty-row">未选中因子</div>'; return; }
        const sm = FACTOR_SOURCES[f.source] || { label: f.source, color: 'var(--text-muted)', computable: false };
        panel.style.setProperty('--src-bar', sm.color);
        // 仅在切换因子时初始化参数/输出索引；否则切换输出或重算会保留用户已选的状态
        if (detailKey !== f.factorKey) {
            detailKey = f.factorKey;
            state.pgParams = {};
            (f.params || []).forEach(p => { state.pgParams[p.name] = p.defaultValue; });
            state.pgOutputIdx = f.defaultOutputIndex || 0;
        }

        const metaItems = [
            ['factorKey', e(f.factorKey), true],
            ['来源', sm.label, false],
            [f.source === SOURCE_TUSHARE ? 'tushareField' : 'akquantFunc', e(f.source === SOURCE_TUSHARE ? (f.tushareField || '—') : (f.akquantFunc || '—')), true],
            ['dataSource', e(f.dataSource || 'ohlcv'), false],
            ['multiOutput', f.multiOutput ? '是' : '否', false],
            ['transformable', f.transformable ? '是 · 支持滚动窗口聚合' : '否', false],
            ['defaultOutputIndex', f.defaultOutputIndex || 0, true],
            ['lookbackDefault', (f.lookbackDefault || 0) + ' bars', true],
            ['lookbackHint', e(f.lookbackHint || '0'), false],
        ];
        const inputsCell = (f.inputs && f.inputs.length) ? f.inputs.join(' · ') : '—（基本面，无 OHLCV 输入）';

        const paramsBlock = (f.params && f.params.length)
            ? `<div class="dp-section"><div class="dp-section-title"><i class="bi bi-sliders"></i>参数 · 拖动即时重算（真实计算）</div>
                ${f.params.map(p => `
                    <div class="param-row" data-pname="${e(p.name)}">
                        <div class="param-head">
                            <div class="param-name"><span class="pn">${e(p.name)}</span><span style="color:var(--text-muted);"> · ${e(p.displayName || '')}</span><span class="ptype">${e(p.type)}</span></div>
                            <div class="param-val" id="pv_${e(p.name)}">${state.pgParams[p.name] ?? p.defaultValue}</div>
                        </div>
                        <input type="range" class="slider" min="${p.min}" max="${p.max}" step="${p.step}" value="${state.pgParams[p.name] ?? p.defaultValue}" data-pname="${e(p.name)}">
                        <div class="param-range"><span>min ${p.min}</span><span>max ${p.max} · step ${p.step}</span></div>
                    </div>`).join('')}</div>`
            : `<div class="dp-section"><div class="dp-section-title"><i class="bi bi-sliders"></i>参数</div>
                <div class="no-params"><i class="bi bi-check-circle"></i>该因子无需参数，直接取 ${(f.inputs || []).join(' / ')} 计算</div></div>`;

        const outputBlock = f.multiOutput ? `<div class="dp-section">
            <div class="dp-section-title"><i class="bi bi-signpost-split"></i>输出（${(f.outputLabels || []).length} 路 · 点击切换试算）</div>
            <div class="output-tags">${(f.outputLabels || []).map((l, i) =>
                `<span class="out-tag ${i === state.pgOutputIdx ? 'default' : ''}" data-out="${i}">${e(l)}<span class="idx">[${i}]</span></span>`).join('')}</div>
        </div>` : '';

        const playground = sm.computable
            ? `<div class="dp-section">
                <div class="dp-section-title"><i class="bi bi-code-square"></i>因子试算台 · engine akquant.talib</div>
                <div class="pg-stock">
                    <select id="pgStock">${STOCKS.map(s => `<option value="${s.code}" ${s.code === PG.code ? 'selected' : ''}>${e(s.name)} · ${s.code}</option>`).join('')}</select>
                    <div class="pg-status" id="pgStatus"><span class="idle"><i class="bi bi-info-circle"></i> 点击「重算」按钮计算</span></div>
                </div>
                <div class="pg-actions">
                    <button class="btn btn-sm btn-primary" id="pgTryBtn"><i class="bi bi-play-fill"></i> 重算</button>
                    <button class="btn btn-sm btn-outline-secondary" id="pgCopyBtn"><i class="bi bi-clipboard"></i> 复制 JSON</button>
                    <button class="btn btn-sm btn-outline-secondary" id="pgExpandBtn"><i class="bi bi-arrows-fullscreen"></i> 大屏展示</button>
                </div>
                <div class="pg-json-wrap">
                    <pre class="pg-json" id="pgJson"></pre>
                </div>
                <div class="pg-footnote"><i class="bi bi-info-circle"></i><span id="pgFoot">watcher 从本地取最近 1 年前复权日线 · 调 <code>POST /factors/compute</code> 真实计算</span></div>
            </div>`
            : renderFundCard(f);

        panel.innerHTML = `
            <div class="dp-head">
                <div class="dp-keyrow"><span class="dp-key">${e(f.factorKey)}</span><span class="src-tag">${sm.label}</span></div>
                <div class="dp-name">${e(f.displayName || '')}</div>
                <div class="dp-desc">${e(f.description || '')}</div>
            </div>
            <div class="dp-body">
                <div class="dp-section"><div class="dp-section-title"><i class="bi bi-card-list"></i>元数据</div>
                    <div class="meta-grid">
                        ${metaItems.map(([k, v, accent]) => `<div class="meta-item"><div class="k">${k}</div><div class="v ${accent ? 'mono-accent' : ''}">${v}</div></div>`).join('')}
                        <div class="meta-item full"><div class="k">inputs</div><div class="v">${e(inputsCell)}</div></div>
                    </div>
                </div>
                ${outputBlock}
                ${paramsBlock}
                ${playground}
            </div>
            <div class="dp-actions">
                <button class="btn btn-sm btn-outline-secondary" onclick="FactorLib.openEditModal('${e(f.factorKey)}')"><i class="bi bi-pencil"></i> 编辑</button>
            </div>`;

        // 绑定参数滑块
        panel.querySelectorAll('input.slider').forEach(sl => {
            sl.oninput = () => {
                const v = parseFloat(sl.value);
                state.pgParams[sl.dataset.pname] = v;
                const pv = document.getElementById('pv_' + sl.dataset.pname);
                if (pv) pv.textContent = Number.isInteger(v) ? v : v.toFixed(2);
            };
        });
        // 输出切换：只切换高亮 + 立即按新输出重算，不整体重渲染（避免 innerHTML 重建导致滚动位置跳顶）
        panel.querySelectorAll('.out-tag[data-out]').forEach(t => t.onclick = () => {
            state.pgOutputIdx = parseInt(t.dataset.out, 10);
            panel.querySelectorAll('.out-tag[data-out]').forEach(x => x.classList.toggle('default', parseInt(x.dataset.out, 10) === state.pgOutputIdx));
            renderPlayground();
        });
        const stockSel = document.getElementById('pgStock');
        if (stockSel) stockSel.onchange = () => { PG.code = stockSel.value; PG.ohlcv = null; };
        const tryBtn = document.getElementById('pgTryBtn');
        if (tryBtn && sm.computable) tryBtn.onclick = () => renderPlayground();
        const copyBtn = document.getElementById('pgCopyBtn');
        if (copyBtn) copyBtn.onclick = () => {
            const jsonEl = document.getElementById('pgJson');
            if (jsonEl && jsonEl.textContent) {
                navigator.clipboard.writeText(jsonEl.textContent).then(() => {
                    StockApp.toast('JSON 已复制', 'success');
                }).catch(() => {
                    StockApp.toast('复制失败', 'danger');
                });
            }
        };
        const expandBtn = document.getElementById('pgExpandBtn');
        if (expandBtn) expandBtn.onclick = () => openJsonViewer();
    }

    // 保存当前 JSON 数据
    let currentJsonData = null;

    // 打开大屏 JSON 查看器
    function openJsonViewer() {
        if (!currentJsonData) {
            StockApp.toast('请先计算因子', 'warning');
            return;
        }
        const modal = document.getElementById('jsonViewerModal');
        if (modal) {
            document.getElementById('jsonViewerContent').innerHTML = syntaxHighlight(currentJsonData);
            modal.classList.add('open');
        }
    }

    // 关闭大屏 JSON 查看器
    function closeJsonViewer() {
        const modal = document.getElementById('jsonViewerModal');
        if (modal) modal.classList.remove('open');
    }

    // 复制大屏 JSON 查看器中的内容
    function copyViewerJson() {
        if (!currentJsonData) {
            StockApp.toast('没有数据可复制', 'warning');
            return;
        }
        const text = typeof currentJsonData === 'string' ? currentJsonData : JSON.stringify(currentJsonData, null, 2);
        navigator.clipboard.writeText(text).then(() => {
            StockApp.toast('JSON 已复制', 'success');
        }).catch(() => {
            StockApp.toast('复制失败', 'danger');
        });
    }

    function renderFundCard(f) {
        return `<div class="dp-section">
            <div class="dp-section-title"><i class="bi bi-database-exclamation"></i>数据来源说明</div>
            <div class="fund-card">
                <div class="fh"><i class="bi bi-building-fill-exclamation"></i>${e(f.factorKey)} 由 watcher 提供，engine 不计算</div>
                <div class="fb">
                    ${e(f.factorKey)} 属于基本面因子，由 <strong style="color:var(--text-primary);">stock-watcher</strong> 从 SQLite 的
                    <code>${e(f.dataSource || 'daily_basic')}</code> 表读取后经 HTTP 传入。engine 仅注册其元数据（参数、描述、分类），不提供实时计算能力。
                    <div class="err-code"><i class="bi bi-x-octagon"></i>调用计算接口 → 400 FACTOR_NOT_COMPUTABLE</div>
                </div>
            </div>
        </div>`;
    }

    // ===================== 试算（真实计算）=====================
    let recomputeTimer = null;
    function scheduleRecompute() {
        clearTimeout(recomputeTimer);
        recomputeTimer = setTimeout(renderPlayground, 250);
    }

    // 清理资源的函数
    function cleanup() {
        if (recomputeTimer) {
            clearTimeout(recomputeTimer);
            recomputeTimer = null;
        }
    }

    function genOhlcv(seed) {
        let s = parseInt(seed, 10) || 600519;
        const rnd = () => { s = (s * 9301 + 49297) % 233280; return s / 233280; };
        const bars = [];
        let price = 25 + rnd() * 30;
        for (let i = 0; i < 250; i++) {
            const open = price;
            const drift = (rnd() - 0.48) * 1.6;
            const close = Math.max(2, open + drift + (rnd() - 0.5) * 1.2);
            const high = Math.max(open, close) + rnd() * 1.0;
            const low = Math.min(open, close) - rnd() * 1.0;
            const volume = Math.round(5000 + rnd() * 20000 + Math.abs(drift) * 8000);
            const d = new Date();
            d.setDate(d.getDate() - (250 - i));
            const ds = d.toISOString().slice(0, 10);
            bars.push({ date: ds, open, high, low: Math.max(1, low), close, volume });
            price = close;
        }
        return bars;
    }

    function formatDate(date) {
        return date.toISOString().slice(0, 10);
    }

    async function ensureOhlcv() {
        if (PG.ohlcv && PG.code === PG._loadedCode) return PG.ohlcv;
        PG.synthetic = false;
        let bars = null;
        try {
            // 只取最近 1 年的日线数据
            const end = new Date();
            const start = new Date();
            start.setFullYear(start.getFullYear() - 1);
            const url = '/kline/' + PG.code + '?period=daily&startDate=' + formatDate(start) + '&endDate=' + formatDate(end);
            const resp = await fetchJson(url);
            if (resp && resp.code === 200 && Array.isArray(resp.data) && resp.data.length >= 5) {
                bars = resp.data.map(r => ({
                    date: r.date, open: +r.open, high: +r.high, low: +r.low, close: +r.close, volume: +r.volume,
                }));
            }
        } catch (_) { /* 落到合成数据 */ }
        if (!bars) {
            bars = genOhlcv(PG.code);
            PG.synthetic = true;
        }
        PG.ohlcv = bars;
        PG._loadedCode = PG.code;
        return bars;
    }

    // JSON 语法高亮
    function syntaxHighlight(json) {
        if (typeof json !== 'string') json = JSON.stringify(json, null, 2);
        json = json.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        return json.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, function (match) {
            let cls = 'json-num';
            if (/^"/.test(match)) {
                cls = /:$/.test(match) ? 'json-key' : 'json-str';
            } else if (/true|false/.test(match)) {
                cls = 'json-bool';
            } else if (/null/.test(match)) {
                cls = 'json-null';
            }
            return '<span class="' + cls + '">' + match + '</span>';
        });
    }

    async function renderPlayground() {
        const f = FACTOR_MAP[state.selected];
        if (!f) return;
        const sm = FACTOR_SOURCES[f.source];
        if (!sm || !sm.computable) return;
        const jsonEl = document.getElementById('pgJson');
        const statusEl = document.getElementById('pgStatus');
        const footEl = document.getElementById('pgFoot');
        if (!jsonEl) return;

        // 显示加载状态
        statusEl.innerHTML = '<span class="loading"><i class="bi bi-hourglass-split"></i> 计算中...</span>';
        jsonEl.innerHTML = '';

        const bars = await ensureOhlcv();

        // 只计算选中的输出（多输出时只计算一个，提升性能）
        const outputIdx = f.multiOutput ? state.pgOutputIdx : 0;

        // 构建完整请求和响应用于展示
        const requestBody = {
            data: bars.map(b => ({ date: b.date, open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume })),
            factors: [{ factorKey: f.factorKey, params: state.pgParams, outputIndex: outputIdx }],
        };

        try {
            const r = await fetch('/factors/compute', {
                method: 'POST', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify(requestBody),
            });
            const j = await r.json();

            if (j.code !== 200) throw new Error(j.message || '计算失败');

            // 构建展示用的完整对象
            const result = {
                request: requestBody,
                response: j,
                summary: {
                    status: 'success',
                    factor: f.factorKey,
                    outputIndex: outputIdx,
                    dataPoints: (j.data[f.factorKey] || []).length,
                    lastValue: (j.data[f.factorKey] || []).filter(v => v !== null && v !== undefined).slice(-1)[0],
                }
            };

            // 展示结果
            const dataCount = (j.data[f.factorKey] || []).length;
            const validCount = (j.data[f.factorKey] || []).filter(v => v !== null && v !== undefined).length;
            statusEl.innerHTML = `<span class="success"><i class="bi bi-check-circle-fill"></i> 计算成功 · ${validCount}/${dataCount} 个有效值</span>`;
            jsonEl.innerHTML = syntaxHighlight(result);
            currentJsonData = result;

            // 更新说明
            if (footEl) footEl.innerHTML = PG.synthetic
                ? '<i class="bi bi-info-circle"></i> watcher 暂无该标的行情，已用合成数据演示（计算仍走真实 engine）'
                : '<i class="bi bi-info-circle"></i> watcher 从本地取最近 1 年前复权日线 · 调 <code>POST /factors/compute</code> 真实计算';

        } catch (err) {
            // 展示错误
            statusEl.innerHTML = `<span class="error"><i class="bi bi-exclamation-triangle-fill"></i> 计算失败：${e(err.message || '')}</span>`;
            jsonEl.innerHTML = `<div class="json-error">${e(err.stack || err.message)}</div>`;
        }
    }

    // ===================== CRUD 模态框 =====================
    function populateCategorySelect() {
        document.getElementById('f_cat').innerHTML = CATEGORIES
            .map(c => `<option value="${c.key}">${c.name} · ${c.key}</option>`).join('');
        // source / dataSource 下拉由常量注入，避免 HTML 硬编码 Schema 字面量
        const src = document.getElementById('f_source');
        if (src && !src.options.length) {
            src.innerHTML = FACTOR_SOURCE_OPTIONS.map(o => `<option value="${o.code}">${o.label}</option>`).join('');
        }
        const ds = document.getElementById('f_ds');
        if (ds && !ds.options.length) {
            ds.innerHTML = FACTOR_DATA_SOURCES.map(o => `<option value="${o.code}">${o.label}</option>`).join('');
        }
    }

    function openCreateModal() {
        document.getElementById('modalTitle').textContent = '新建因子';
        document.getElementById('submitBtnText').textContent = '创建因子';
        document.getElementById('f_key').value = 'NEW_FACTOR';
        document.getElementById('f_key').disabled = false;
        document.getElementById('f_name').value = '';
        document.getElementById('f_func').value = '';
        document.getElementById('f_desc').value = '';
        document.getElementById('f_source').value = SOURCE_DEFAULT_NEW;
        document.getElementById('f_ds').value = 'ohlcv';
        document.getElementById('f_params').value = JSON.stringify([{
            "name": "timeperiod",
            "displayName": "周期",
            "type": "INT",
            "defaultValue": 5,
            "min": 1,
            "max": 500,
            "step": 1
        }], null, 2);
        document.querySelectorAll('#createModal .inputs-check input').forEach(c => { c.checked = (c.value === 'close'); });
        document.getElementById('f_transformable').checked = true;
        document.getElementById('f_key_err').style.display = 'none';
        document.getElementById('createModal').classList.add('open');
    }

    function openEditModal(key) {
        const f = FACTOR_MAP[key];
        if (!f) return;
        document.getElementById('modalTitle').textContent = '编辑因子 · ' + key;
        document.getElementById('submitBtnText').textContent = '保存修改';
        document.getElementById('f_key').value = f.factorKey;
        document.getElementById('f_key').disabled = true;
        document.getElementById('f_name').value = f.displayName || '';
        document.getElementById('f_func').value = f.source === SOURCE_TUSHARE ? (f.tushareField || '') : (f.akquantFunc || '');
        document.getElementById('f_desc').value = f.description || '';
        document.getElementById('f_source').value = f.source;
        document.getElementById('f_ds').value = f.dataSource || 'ohlcv';
        document.getElementById('f_params').value = (f.params && f.params.length) ? JSON.stringify(f.params, null, 2) : '[]';
        document.querySelectorAll('#createModal .inputs-check input').forEach(c => { c.checked = (f.inputs || []).includes(c.value); });
        document.getElementById('f_transformable').checked = !!f.transformable;
        document.getElementById('f_key_err').style.display = 'none';
        document.getElementById('createModal').classList.add('open');
    }

    function submitFactor() {
        const key = document.getElementById('f_key').value.trim();
        const isEdit = document.getElementById('f_key').disabled;
        if (!key) { showKeyErr('factorKey 不能为空'); return; }
        let params;
        try { params = JSON.parse(document.getElementById('f_params').value || '[]'); }
        catch (err) { StockApp.toast('params 不是合法 JSON: ' + err.message, 'danger'); return; }
        const inputs = Array.from(document.querySelectorAll('#createModal .inputs-check input:checked')).map(c => c.value);
        const payload = {
            factorKey: key,
            displayName: document.getElementById('f_name').value.trim(),
            category: document.getElementById('f_cat').value,
            source: document.getElementById('f_source').value,
            dataSource: document.getElementById('f_ds').value,
            description: document.getElementById('f_desc').value.trim(),
            params, inputs,
            multiOutput: false, outputLabels: [], defaultOutputIndex: 0,
            lookbackHint: '0', lookbackDefault: 0,
            transformable: document.getElementById('f_transformable').checked,
        };
        const func = document.getElementById('f_func').value.trim();
        if (payload.source === SOURCE_TUSHARE) payload.tushareField = func; else payload.akquantFunc = func || null;

        const done = async () => { closeModal('createModal'); await loadAll(); renderAll(); };
        if (isEdit) {
            const { factorKey, ...updates } = payload;
            StockApp.post('/factors/' + encodeURIComponent(key) + '/update', updates, function (resp) {
                if (resp.code === 200) { StockApp.toast('修改成功', 'success'); done(); }
                else StockApp.toast(resp.message || '修改失败', 'danger');
            });
        } else {
            StockApp.post('/factors', payload, function (resp) {
                if (resp.code === 200 || resp.code === 201) { StockApp.toast('新增成功', 'success'); done(); }
                else {
                    if ((resp.message || '').indexOf('ALREADY_EXISTS') >= 0) showKeyErr('FACTOR_ALREADY_EXISTS · 该 key 已存在');
                    else StockApp.toast(resp.message || '新增失败', 'danger');
                }
            });
        }
    }

    function showKeyErr(msg) {
        const el = document.getElementById('f_key_err');
        document.getElementById('f_key_err_msg').textContent = msg;
        el.style.display = 'flex';
    }

    function confirmDelete(key) {
        StockApp.confirm({
            title: '删除因子', message: '确认删除因子 ' + key + ' ？该操作会持久化到 engine data/factors.json。',
            confirmText: '删除', confirmClass: 'btn btn-danger btn-sm',
        }).then(ok => { if (ok) doDelete(key); });
    }

    function doDelete(key) {
        StockApp.post('/factors/' + encodeURIComponent(key) + '/delete', null, async function (resp) {
            if (resp.code === 200) {
                StockApp.toast('已删除 ' + key, 'success');
                if (state.selected === key) state.selected = null;
                await loadAll(); renderAll();
            } else StockApp.toast(resp.message || '删除失败', 'danger');
        });
    }

    function openBatchModal() { document.getElementById('batchModal').classList.add('open'); }
    function closeModal(id) { document.getElementById(id).classList.remove('open'); }

    // ===================== 事件 / 初始化 =====================
    function bindEvents() {
        document.getElementById('railSearch').addEventListener('input', ev => { state.q = ev.target.value; renderTable(); });
        document.getElementById('onlyCompute').addEventListener('change', ev => { state.onlyCompute = ev.target.checked; renderTable(); });
        document.querySelectorAll('.sort-btn').forEach(b => b.addEventListener('click', () => {
            document.querySelectorAll('.sort-btn').forEach(x => x.classList.remove('active'));
            b.classList.add('active'); state.sort = b.dataset.sort; renderTable();
        }));
        document.querySelectorAll('.fl-modal-mask').forEach(m => m.addEventListener('click', ev => { if (ev.target === m) m.classList.remove('open'); }));
    }

    async function init() {
        try {
            await loadAll();
            populateCategorySelect();
            renderAll();
            bindEvents();
        } catch (err) {
            StockApp.toast('加载因子库失败：' + err.message, 'danger');
        }
    }

    document.addEventListener('DOMContentLoaded', init);

    return {
        openCreateModal, openEditModal, submitFactor, confirmDelete, openBatchModal, closeModal, refresh,
        openJsonViewer, closeJsonViewer, copyViewerJson,
    };
})();

// 页面卸载时清理资源
window.addEventListener('beforeunload', cleanup);
