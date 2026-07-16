/**
 * 策略编辑器（spec 004 Task 11：8 Tab 完整表单 + collect/render + 联动 + 摘要）。
 *
 * 在 Task 10 骨架之上补齐：
 *   1) renderFormFromState / collectStateFromForm 覆盖 8 Tab 全部字段；
 *   2) 联动：universe=manual 显示 stocks；ranking method 切换；bracket/atr 折叠；
 *      scope=single 禁用 Tab7；method 切换 target/weights；自定义费率/滑点折叠；
 *   3) 「插入因子」下拉从 /constants 异步加载；
 *   4) 动态列表（ranking.weights / position.weights / exit.rules）；
 *   5) 保存前 JSON.parse 预校验（FR-8）+ 摘要 buildSummary 按 scope 分支；
 *   6) category/tags 作为 watcher 元数据走 this.meta，保存时合并进请求。
 *
 * 字段命名严格 snake_case 对齐 engine models.py / Schema §3-4。
 *
 * 与后端字段对齐：
 *   - StrategyDTO：config(String JSON)、currentVersion(Integer)、strategyId、category、tags
 *   - StrategyCreateRequest：{name, description, category, scope, tags, configJson, changelog}
 *   - StrategyConfigUpdateRequest：{configJson, changelog, expectedVersion}
 *   - StrategyTemplateDTO：{configJson, ...}
 */
const StrategyEditor = {
    // ===================== 模式与上下文 =====================
    mode: 'new',              // new / edit / template
    strategyId: null,         // edit 模式：URL 由 controller 注入到 #editorBootstrap.strategyId
    templateId: null,         // template 模式：?templateId=xxx
    expectedVersion: null,    // edit 模式：乐观锁，来自 StrategyDTO.currentVersion

    // 全局状态（StrategyConfigModel 顶层结构）。加载/新建后由深拷贝 DEFAULT_CONFIG 初始化。
    state: null,

    // 策略范式：'signals'（择时，默认）| 'rebalance'（轮动）。与 #f-paradigm 控件双向绑定，
    // 决定 Tab 显隐与 collectConfig 二选一（spec 009 Task 9/10）。
    paradigm: 'signals',

    // signals 范式下手动标的的软上限（超出给红色提示，最终阻断由后端校验）
    SIGNALS_MANUAL_MAX: 10,

    // watcher 元数据（不在 StrategyConfigModel 内，但保存时需随请求带上）
    meta: { category: '', tags: [] },

    // 从 /constants 缓存的白名单（init 时异步加载）
    constants: null,

    // ConditionTreeBuilder 因子元数据（init 时异步从 /factors + /factors/categories 加载）
    factorMeta: { factors: [], categories: [] },

    // 条件树区域模式状态：{ [targetId]: 'visual' | 'json' }，默认 visual
    _condMode: {},
    // 已挂载的 ConditionTreeBuilder 控制器：{ [targetId]: controller }
    _condBuilders: {},
    // 每个条件树区域的元信息：{ targetId: { statePath, allowRef, allowCross } }
    _condMeta: {},
    // 双向互通防循环标志：可视化→textarea 同步期间置 true，规避 input 事件回流
    _syncing: false,

    // 手动股票池：内部以对象数组维护便于展示名称，保存时只取 tsCode 字符串数组
    _manualStocks: [],
    _manualSuggestBound: false,
    _MANUAL_STOCK_MAX: 50,

    // ===================== 默认配置 =====================
    // 对齐 watcher StrategyServiceImpl.buildDefaultConfig()（signals 用 operator/conditions 树）。
    DEFAULT_CONFIG: {
        strategy_id: null,
        name: '',
        description: '',
        trading_config: {
            signals: {
                buy: { operator: 'AND', conditions: [] },
                sell: { operator: 'AND', conditions: [] },
            },
            position_sizing: { method: 'order_target_percent', target: 0.95 },
        },
        backtest_config: {
            initial_cash: 100000,
            broker_profile: 'cn_stock_miniqmt',
            lot_size: 100,
            warmup_period: 20,
            history_depth: 60,
            benchmark: '000300.SH',
        },
    },

    // 错误 path 前缀 → Tab id 映射（与 #strategyTab 的 data-target 对齐）
    // 注意：screen_config.universe.stocks 在择时范式定位到 tab-pool，其余 screen_config 字段定位到 tab-screen（轮动）
    // spec 010 Task 12 后：4 层路径 universe/factor/filter/portfolio；保留旧顶层路径兼容过渡期后端报错。
    ERROR_TAB_MAP: [
        { prefix: 'name',                                    tab: 'tab-basic' },
        { prefix: 'description',                             tab: 'tab-basic' },
        { prefix: 'screen_config.filter.conditions',         tab: 'tab-screen' },
        { prefix: 'screen_config.filter',                    tab: 'tab-screen' },
        { prefix: 'screen_config.factor',                    tab: 'tab-screen' },
        { prefix: 'screen_config.portfolio',                 tab: 'tab-screen' },
        { prefix: 'screen_config.universe',                  tab: 'tab-screen' },
        { prefix: 'screen_config.universe.stocks',           tab: 'tab-pool' },
        { prefix: 'screen_config.stocks',                    tab: 'tab-pool' },
        // 兼容旧顶层路径（过渡期后端可能仍返回 conditions/ranking/top_n/filters）
        { prefix: 'screen_config.conditions',                tab: 'tab-screen' },
        { prefix: 'screen_config.ranking',                   tab: 'tab-screen' },
        { prefix: 'screen_config.top_n',                     tab: 'tab-screen' },
        { prefix: 'screen_config.filters',                   tab: 'tab-screen' },
        { prefix: 'screen_config',                           tab: 'tab-screen' },
        { prefix: 'trading_config.signals.buy',              tab: 'tab-buy' },
        { prefix: 'trading_config.signals.sell',             tab: 'tab-sell' },
        { prefix: 'trading_config.position_sizing',          tab: 'tab-position' },
        { prefix: 'trading_config.exit',                     tab: 'tab-exit' },
        { prefix: 'trading_config.rebalance',                tab: 'tab-rebalance' },
        { prefix: 'backtest_config',                         tab: 'tab-backtest' },
    ],

    // ===================== 初始化 =====================
    async init() {
        const bootRaw = document.getElementById('editorBootstrap');
        let boot = {};
        if (bootRaw) {
            try {
                boot = JSON.parse(bootRaw.textContent) || {};
            } catch (err) {
                // 引导 JSON 解析失败不应让整个编辑器崩溃：降级为空 boot，编辑器仍可用
                console.error('[StrategyEditor] editorBootstrap JSON 解析失败：', err);
                StockApp.toast('页面引导数据解析失败（模板/分类下拉可能为空）：' + (err && err.message ? err.message : err), 'warning');
            }
        }
        this.strategyId = boot.strategyId || null;

        const params = new URLSearchParams(location.search);
        this.templateId = params.get('templateId');
        // screenSource: 选股中心「作为策略选股范围」跳转携带的选股方案 id，
        // 用于在新建模式下异步拉取方案并把其 screenConfig 导入 screen_config。
        this.screenSourceId = params.get('screenSource');

        // 异步加载白名单常量（不阻塞渲染，加载后用于下拉选项 + 因子菜单）
        this.loadConstantsAsync();

        // 加载 benchmark 下拉选项（数据源 /api/backtest/benchmarks）
        this.loadBenchmarkOptions();

        // 渲染下拉（position method / sell method / broker profile / category）
        this.populateStaticSelects(boot);

        try {
            if (this.strategyId) {
                this.mode = 'edit';
                await this.loadStrategy(this.strategyId);
            } else if (this.templateId) {
                this.mode = 'template';
                await this.loadTemplate(this.templateId);
            } else {
                this.mode = 'new';
                this.state = this.clone(this.DEFAULT_CONFIG);
            }
        } catch (err) {
            StockApp.toast('加载策略失败：' + (err && err.message ? err.message : err), 'danger');
            this.state = this.clone(this.DEFAULT_CONFIG);
        }

        // 选股方案导入（独立异步流程，失败不阻塞编辑器）
        if (this.screenSourceId) {
            this.loadScreenSource(this.screenSourceId);
        }

        this.renderFormFromState();
        this.updateJsonPreview();
        this.bindEvents();
        this.initConditionEditors();

        // 范式控件初始化：依据已加载的 state 推断范式，刷新 Tab 显隐与 universe 选项
        this.initParadigm();

        // 异步加载 ConditionTreeBuilder 因子白名单（不阻塞渲染）
        this.loadFactorMeta();

        // 仓位预览：绑定输入事件并初次计算
        ['f-bt-cash', 'f-ps-target', 'f-bt-lot-size'].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.addEventListener('input', () => this.updatePositionPreview());
        });
        this.updatePositionPreview();
    },

    /**
     * 从选股中心方案导入 screen_config。
     * GET /screener/plans/{id} 返回 {code:200, data:{id,name,screenConfig:{...camelCase}}}，
     * 这里把 screenConfig 的驼峰字段转换为策略 screen_config 的 snake_case 后注入 state，
     * 并刷新选股 Tab。任何失败都优雅回退（toast 提示 + 保留默认空 state），不阻塞编辑器。
     */
    loadScreenSource(planId) {
        if (!planId) return;
        StockApp.get('/screener/plans/' + encodeURIComponent(planId), null, (resp) => {
            if (!resp || resp.code !== 200 || !resp.data) {
                StockApp.toast('导入选股方案失败：' + (resp && resp.message ? resp.message : '方案不存在或网络错误'), 'danger');
                return;
            }
            const plan = resp.data;
            const sc = plan.screenConfig || {};
            try {
                this.state.screen_config = this.convertScreenConfig(sc);
                this.renderFormFromState();
                this.updateJsonPreview();
                StockApp.toast('已从选股方案『' + (plan.name || '') + '』导入选股范围，请在「选股范围」Tab 核对', 'success');
            } catch (e) {
                StockApp.toast('选股方案字段转换失败，请手动核对：' + (e && e.message ? e.message : e), 'warning');
            }
        });
    },

    /**
     * 把任意形态的 screen_config 归一为 4 层结构（universe/factor/filter/portfolio）。
     * 兼容存量旧 5 字段（spec 010 Task 12 迁移期；Task 15 清存量前的过渡编辑场景）：
     *   universe(string)  → universe.pool
     *   stocks            → universe.stocks
     *   conditions        → filter.conditions
     *   ranking           → factor（method/factor/order/weights 透传）
     *   filters           → filter（exclude_st 等开关、industries、min_list_days 合并进 filter）
     *   top_n             → portfolio.top_n
     * 新结构（已是 4 层）原样返回（深拷贝，避免污染原 state）。
     * 传 null/非对象返回 null。
     */
    normalizeScreenConfig(sc) {
        if (!sc || typeof sc !== 'object') return null;
        const out = {};
        // 旧结构判定：顶层 universe 为字符串，或存在顶层 conditions/ranking/filters/top_n
        const isLegacy = (typeof sc.universe === 'string') ||
            ('conditions' in sc) || ('ranking' in sc) || ('filters' in sc) || ('top_n' in sc);

        if (isLegacy) {
            // ---- universe 层 ----
            const pool = typeof sc.universe === 'string' ? sc.universe : 'all_a_shares';
            const universe = { pool: pool };
            universe.stocks = Array.isArray(sc.stocks) ? sc.stocks.slice() : null;
            out.universe = universe;

            // ---- factor 层（原 ranking 平移）----
            if (sc.ranking != null) out.factor = JSON.parse(JSON.stringify(sc.ranking));

            // ---- filter 层（conditions + filters 合并）----
            const filter = {};
            if (sc.conditions != null) filter.conditions = JSON.parse(JSON.stringify(sc.conditions));
            if (sc.filters && typeof sc.filters === 'object') Object.assign(filter, sc.filters);
            if (Object.keys(filter).length) out.filter = filter;

            // ---- portfolio 层 ----
            if (sc.top_n != null) out.portfolio = { top_n: sc.top_n };
            return out;
        }

        // 新 4 层结构：深拷贝透传
        if (sc.universe != null) out.universe = JSON.parse(JSON.stringify(sc.universe));
        if (sc.factor != null) out.factor = JSON.parse(JSON.stringify(sc.factor));
        if (sc.filter != null) out.filter = JSON.parse(JSON.stringify(sc.filter));
        if (sc.portfolio != null) out.portfolio = JSON.parse(JSON.stringify(sc.portfolio));
        return out;
    },

    /**
     * 选股中心 screenConfig（驼峰）→ 策略 4 层 screen_config（snake_case）转换。
     * 字段映射（spec 010 Task 12 后对齐 4 层）：
     *   universe        → universe.pool（spec 011 P1-1：point-in-time 已强制开启，字段移除）
     *   stocks          → universe.stocks
     *   conditions      → filter.conditions
     *   ranking         → factor（method/factor/order/weights 不变）
     *   filters         → filter（exclude_st 等开关、industries、min_list_days 合并）
     *   topN            → portfolio.top_n
     */
    convertScreenConfig(sc) {
        if (!sc || typeof sc !== 'object') return null;
        const legacy = {};
        if (sc.universe != null) legacy.universe = sc.universe;
        if (Array.isArray(sc.stocks) && sc.stocks.length) legacy.stocks = sc.stocks.slice();
        if (sc.topN != null) legacy.top_n = sc.topN;
        if (sc.conditions != null) legacy.conditions = this.convertConditions(sc.conditions);
        if (sc.ranking != null) legacy.ranking = this.convertRanking(sc.ranking);
        if (sc.filters != null) legacy.filters = this.convertFilters(sc.filters);
        return this.normalizeScreenConfig(legacy);
    },

    /**
     * conditions 尽力转换。
     * 选股侧 ConditionTree 形如 {operator, conditions:[...]}，叶子操作数可能是
     * {kind:'factor', factor, ...} / {kind:'value', value} / {value} / {factor,...}。
     * 策略侧 conditions 为 4 形态 discriminated union，这里只做形态归一：
     *   - 含 factor 字段 → 保留 {factor, ...}（factor 形态）
     *   - 含 value 字段  → 保留 {value}（literal 形态）
     * 无法判别的结构原样保留，交由用户在编辑器中修正。
     */
    convertConditions(cond) {
        if (cond == null) return cond;
        return JSON.parse(JSON.stringify(cond, (k, v) => {
            // 仅对「裸 value」对象做形态归一：{kind:'value', value} 或 {value} 已是合法字面量，无需改动。
            return v;
        }));
    },

    convertRanking(ranking) {
        if (!ranking || typeof ranking !== 'object') return ranking;
        const r = {};
        if (ranking.method != null) r.method = ranking.method;
        if (ranking.factor != null) r.factor = ranking.factor;
        if (ranking.order != null) r.order = ranking.order;
        // composite weights 键为 factorKey（字符串），值不变，整体透传
        if (ranking.weights != null) r.weights = JSON.parse(JSON.stringify(ranking.weights));
        return r;
    },

    convertFilters(f) {
        if (!f || typeof f !== 'object') return f;
        return {
            exclude_st: !!f.excludeSt,
            exclude_suspended: !!f.excludeSuspended,
            exclude_limit_up: !!f.excludeLimitUp,
            exclude_limit_down: !!f.excludeLimitDown,
            industries: Array.isArray(f.industries) ? f.industries.slice() : [],
            exclude_industries: Array.isArray(f.excludeIndustries) ? f.excludeIndustries.slice() : [],
            min_list_days: f.minListDays != null ? Number(f.minListDays) : 0,
        };
    },

    /**
     * 异步拉取 /constants（白名单）并缓存。加载完成后回填因子下拉菜单。
     */
    loadConstantsAsync() {
        StockApp.loadConstants((data) => {
            this.constants = data || {};
            this.fillFactorMenus();
            // 若 constants 在 renderFormFromState 之后到达，确保 select 已就绪
            if (this.constants['strategies.positionMethods']) {
                this.rebuildSelectFromList('f-ps-method', this.constants['strategies.positionMethods']);
            }
            if (this.constants['strategies.sellMethods']) {
                this.rebuildSelectFromList('f-ps-sell-method', this.constants['strategies.sellMethods']);
            }
            if (this.constants['strategies.brokerProfiles']) {
                this.rebuildSelectFromList('f-bt-broker', this.constants['strategies.brokerProfiles']);
            }
            if (this.constants['strategies.universe']) {
                this.rebuildSelectFromList('f-universe', this.constants['strategies.universe']);
            }
        });
    },

    /**
     * 加载 benchmark 下拉选项（数据源 /api/backtest/benchmarks）。
     * 返回 [{code, name}]，渲染为 <option value=code>name (code)</option>。
     * 加载失败保留默认的沪深300选项。
     */
    async loadBenchmarkOptions() {
        const sel = document.getElementById('f-bt-benchmark');
        if (!sel) return;
        try {
            const resp = await fetch('/api/backtest/benchmarks');
            const json = await resp.json();
            const list = (json && json.data) || [];
            const cur = sel.value;
            sel.innerHTML = '';
            list.forEach(b => {
                const opt = document.createElement('option');
                opt.value = b.code;
                opt.textContent = (b.name || b.code) + ' (' + b.code + ')';
                sel.appendChild(opt);
            });
            // 恢复之前选中值或默认沪深300
            sel.value = cur || '000300.SH';
            if (!sel.value) sel.value = '000300.SH';
        } catch (e) {
            // 降级：保留默认沪深300
            console.warn('[StrategyEditor] benchmark 下拉加载失败：', e);
        }
    },

    /**
     * 用 boot 注入的 categoryOptions/scopeOptions 填充分类下拉。
     * position/sell/broker 优先用 constants，加载前用静态兜底。
     * 兼容两种格式：二维数组 [[code,label],...] 与对象数组 [{code,label},...]。
     */
    populateStaticSelects(boot) {
        const extractOption = (o) => {
            if (Array.isArray(o)) {
                return { code: o[0], label: o[1] || o[0] };
            }
            return { code: o.code, label: o.label || o.code };
        };

        const catSel = document.getElementById('f-category');
        if (catSel) {
            const opts = (boot && boot.categoryOptions) || [];
            catSel.innerHTML = '<option value="">— 请选择 —</option>' +
                opts.map((o) => {
                    const opt = extractOption(o);
                    return '<option value="' + StockApp.escapeHtml(opt.code) + '">' +
                        StockApp.escapeHtml(opt.label) + '</option>';
                }).join('');
        }
    },

    rebuildSelectFromList(id, list) {
        const sel = document.getElementById(id);
        if (!sel || !Array.isArray(list)) return;
        const cur = sel.value;
        const toOpt = (v) => {
            if (v == null) return { code: '', label: '' };
            if (typeof v === 'string') return { code: v, label: v };
            if (Array.isArray(v)) return { code: String(v[0]), label: String(v[1] ?? v[0]) };
            // {code, label} 对象（EnumOptionDTO）
            if (typeof v === 'object') return { code: String(v.code ?? ''), label: String(v.label ?? v.code ?? '') };
            return { code: String(v), label: String(v) };
        };
        sel.innerHTML = list.map((v) => {
            const opt = toOpt(v);
            return '<option value="' + StockApp.escapeHtml(opt.code) + '">' +
                StockApp.escapeHtml(opt.label) + '</option>';
        }).join('');
        if (cur) sel.value = cur;
    },

    /**
     * 给所有「插入因子」下拉填充技术 + 基本面因子项。
     */
    fillFactorMenus() {
        if (!this.constants) return;
        const tech = this.constants['strategies.technicalFactors'] || [];
        const fund = this.constants['strategies.fundamentalFactors'] || [];
        const buildGroup = (title, arr) => {
            if (!arr.length) return '';
            return '<li><h6 class="dropdown-header">' + StockApp.escapeHtml(title) + '</h6></li>' +
                arr.map((f) => '<li><button class="dropdown-item" type="button" data-factor="' +
                    StockApp.escapeHtml(f) + '">' + StockApp.escapeHtml(f) + '</button></li>').join('') +
                '<li><hr class="dropdown-divider"></li>';
        };
        const html = buildGroup('技术面因子', tech) + buildGroup('基本面因子', fund);
        document.querySelectorAll('.factors-menu').forEach((menu) => {
            const target = menu.getAttribute('data-target') || 'f-screen-conditions';
            const old = menu.getAttribute('data-target');
            menu.innerHTML = html;
            // screen 的菜单 id=f-screen-factors 没写 data-target，默认指向 f-screen-conditions
            menu.setAttribute('data-target', old || target);
        });
        // spec 011 P1-2：同步填充 factorKey 联想输入 datalist（权重面板复用）
        const datalist = document.getElementById('factor-keys-datalist');
        if (datalist) {
            const allKeys = tech.concat(fund);
            datalist.innerHTML = allKeys.map((f) => '<option value="' + StockApp.escapeHtml(f) + '">').join('');
        }
    },

    // ===================== 条件树编辑器（可视化 / JSON 模式）=====================
    /**
     * 4 个区域（screen / buy / sell / exit.rules）的可视化模式注册表。
     * exit.rules 的 targetId 在每次增删行后动态变化（按行序号），不在此静态表里。
     */
    COND_AREAS: [
        { targetId: 'f-screen-conditions', statePath: 'screen_config.filter.conditions', allowRef: false, allowCross: false, allowTransform: true },
        { targetId: 'f-buy-conditions',    statePath: 'trading_config.signals.buy',   allowRef: true,  allowCross: true },
        { targetId: 'f-sell-conditions',   statePath: 'trading_config.signals.sell',  allowRef: true,  allowCross: true },
    ],

    /**
     * 异步加载 ConditionTreeBuilder 所需的因子白名单（/factors + /factors/categories）。
     * 加载完成后回填已挂载的 builder（重新挂载以注入 factors/categories）。
     */
    loadFactorMeta() {
        if (!window.ConditionTreeBuilder || typeof ConditionTreeBuilder.loadFactors !== 'function') return;
        ConditionTreeBuilder.loadFactors().then((meta) => {
            this.factorMeta = meta || { factors: [], categories: [] };
            // 给所有已挂载的 builder 重新挂载以注入最新 factors/categories
            Object.keys(this._condBuilders).forEach((tid) => {
                this.remountConditionBuilder(tid);
            });
        }).catch(() => {
            // 静默失败，可视化组件仍可用（只是因子下拉为空）
        });
    },

    /**
     * 初始化所有静态条件树区域为可视化模式。
     * 在 init 末尾、renderFormFromState 之后调用，此时 textarea 内容已就绪。
     */
    initConditionEditors() {
        this.COND_AREAS.forEach((area) => {
            this.registerCondMeta(area.targetId, area.statePath, { allowRef: area.allowRef, allowCross: area.allowCross, allowTransform: area.allowTransform });
            this._condMode[area.targetId] = 'visual';
            this.applyCondModeUI(area.targetId, 'visual');
            this.mountConditionBuilder(area.targetId);
        });
        // exit.rules 行内的 builder 在 renderExitRules / addExitRuleRow 时按需挂载
    },

    /**
     * 注册某个条件树区域的元信息（statePath / allowRef / allowCross）。
     */
    registerCondMeta(targetId, statePath, opts) {
        this._condMeta[targetId] = {
            statePath: statePath,
            allowRef: !!(opts && opts.allowRef),
            allowCross: !!(opts && opts.allowCross),
            allowTransform: !!(opts && opts.allowTransform),
        };
    },

    /**
     * 按 statePath 从 state 取条件树。路径段支持数组下标，如：
     *   'trading_config.exit.rules[0].condition'  或 'trading_config.exit.rules.0.condition'
     * 取不到返回空树 {operator:'AND',conditions:[]}。
     */
    getCondByPath(statePath) {
        if (!statePath) return { operator: 'AND', conditions: [] };
        // 拆段：a.b[0].c → ['a','b','0','c']
        const parts = statePath.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean);
        let cur = this.state;
        for (const p of parts) {
            if (cur == null) return { operator: 'AND', conditions: [] };
            cur = cur[p];
        }
        if (!cur || (typeof cur === 'object' && !Array.isArray(cur) && !('operator' in cur) && !('conditions' in cur))) {
            return { operator: 'AND', conditions: [] };
        }
        return cur;
    },

    /**
     * 按 statePath 把条件树写回 state（按需创建中间对象/数组）。
     */
    setCondByPath(statePath, tree) {
        if (!statePath) return;
        const parts = statePath.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean);
        if (!parts.length) return;
        let cur = this.state;
        for (let i = 0; i < parts.length - 1; i++) {
            const p = parts[i];
            const nextP = parts[i + 1];
            const isIndex = /^\d+$/.test(nextP);
            if (cur[p] == null) cur[p] = isIndex ? [] : {};
            cur = cur[p];
        }
        cur[parts[parts.length - 1]] = tree;
    },

    /**
     * 挂载 ConditionTreeBuilder 到某区域的可视化容器。
     * @param targetId textarea id（如 'f-screen-conditions' 或 'f-exit-rule-condition-0'）
     */
    mountConditionBuilder(targetId) {
        if (!window.ConditionTreeBuilder) return null;
        const meta = this._condMeta[targetId];
        if (!meta) return null;
        const container = document.querySelector('[data-cond-visual="' + targetId + '"]');
        if (!container) return null;

        // 先销毁旧实例
        if (this._condBuilders[targetId]) {
            try { this._condBuilders[targetId].destroy(); } catch (e) { /* noop */ }
            delete this._condBuilders[targetId];
        }
        container.innerHTML = '';

        const tree = this.getCondByPath(meta.statePath);
        const self = this;
        const ctrl = ConditionTreeBuilder.mount(container, {
            tree: tree,
            factors: this.factorMeta.factors || [],
            categories: this.factorMeta.categories || [],
            allowRef: meta.allowRef,
            allowCross: meta.allowCross,
            allowTransform: meta.allowTransform,
            onChange: function (newTree) {
                self.onCondBuilderChange(targetId, newTree);
            },
        });
        this._condBuilders[targetId] = ctrl;
        return ctrl;
    },

    /**
     * 用最新 factorMeta 重新挂载某个 builder（因子白名单到达后回填）。
     */
    remountConditionBuilder(targetId) {
        const oldMode = this._condMode[targetId] || 'visual';
        if (oldMode !== 'visual') {
            // json 模式下不挂载 builder，但保留元信息，切回 visual 时再挂
            return;
        }
        this.mountConditionBuilder(targetId);
    },

    /**
     * 可视化组件 onChange 回调：把新树写回 state + 同步 textarea（防循环）。
     */
    onCondBuilderChange(targetId, newTree) {
        const meta = this._condMeta[targetId];
        if (!meta) return;
        this.setCondByPath(meta.statePath, newTree);
        this.updateJsonPreview();

        // 可视化 → JSON 同步：写回 textarea（若当前是 json 模式，让用户切回 visual 前能看到最新）
        // 用 _syncing 标志规避循环（textarea input 事件不会再回流到 builder）
        const ta = document.getElementById(targetId);
        if (ta) {
            this._syncing = true;
            try {
                ta.value = (newTree && !this.isEmptyTree(newTree))
                    ? JSON.stringify(newTree, null, 2) : '';
            } finally {
                this._syncing = false;
            }
        }
    },

    /**
     * 切换某区域的录入模式。
     * @param targetId textarea id
     * @param mode 'visual' | 'json' | 'ai'
     * @return boolean 切换是否成功（JSON 解析失败时切 visual 会失败）
     */
    switchCondMode(targetId, mode) {
        const meta = this._condMeta[targetId];
        if (!meta) return false;

        if (mode === 'visual') {
            // 切到可视化前：若当前是 json 模式，需把 textarea 内容 parse 回 state + builder
            if ((this._condMode[targetId] || 'visual') === 'json') {
                const ta = document.getElementById(targetId);
                if (ta) {
                    const str = (ta.value || '').trim();
                    if (str) {
                        let parsed;
                        try {
                            parsed = JSON.parse(str);
                        } catch (e) {
                            StockApp.toast('JSON 解析失败，无法切换到可视化：' + e.message, 'danger');
                            return false;
                        }
                        this.setCondByPath(meta.statePath, parsed);
                    } else {
                        // 空串视为清空树
                        this.setCondByPath(meta.statePath, { operator: 'AND', conditions: [] });
                    }
                }
            }
            // 挂载或刷新 builder
            if (this._condBuilders[targetId]) {
                this._condBuilders[targetId].setTree(this.getCondByPath(meta.statePath));
            } else {
                this.mountConditionBuilder(targetId);
            }
        } else if (mode === 'json') {
            // 切到 JSON：把当前 state 的树写入 textarea
            const ta = document.getElementById(targetId);
            if (ta) {
                const tree = this.getCondByPath(meta.statePath);
                this._syncing = true;
                try {
                    ta.value = (tree && !this.isEmptyTree(tree))
                        ? JSON.stringify(tree, null, 2) : '';
                } finally {
                    this._syncing = false;
                }
            }
        }

        this._condMode[targetId] = mode;
        this.applyCondModeUI(targetId, mode);
        this.updateJsonPreview();
        return true;
    },

    /**
     * 应用某区域模式的 UI（显示/隐藏 textarea 与可视化容器；按钮 active 态）。
     */
    applyCondModeUI(targetId, mode) {
        const ta = document.getElementById(targetId);
        const wrap = document.querySelector('[data-cond-visual="' + targetId + '"]');
        if (ta) ta.style.display = mode === 'json' ? '' : 'none';
        if (wrap) wrap.style.display = mode === 'visual' ? '' : 'none';

        // 按钮高亮
        const switchEl = document.querySelector('.cond-mode-switch[data-cond-target="' + targetId + '"]');
        if (switchEl) {
            switchEl.querySelectorAll('.cond-mode-btn').forEach((btn) => {
                btn.classList.toggle('active', btn.getAttribute('data-mode') === mode);
            });
        }

        // AI 占位（防御性，按钮 disabled）
        if (mode === 'ai' && wrap) {
            wrap.style.display = 'none';
        }
    },

    // ===================== 数据加载 =====================
    /**
     * 编辑模式：GET /api/strategies/{id}
     * 响应 data：StrategyDTO，config(String JSON) + currentVersion + category + tags。
     */
    loadStrategy(id) {
        return new Promise((resolve, reject) => {
            StockApp.get('/api/strategies/' + encodeURIComponent(id), null, (resp) => {
                if (resp.code === 200 && resp.data) {
                    const d = resp.data;
                    this.expectedVersion = d.currentVersion != null ? d.currentVersion : null;
                    this.meta.category = d.category || '';
                    this.meta.tags = Array.isArray(d.tags) ? d.tags : [];
                    try {
                        // config 为 JSON 字符串；为空回退默认
                        this.state = d.config
                            ? JSON.parse(d.config)
                            : this.clone(this.DEFAULT_CONFIG);
                    } catch (e) {
                        this.state = this.clone(this.DEFAULT_CONFIG);
                    }
                    // 保证顶层字段齐全，缺字段用默认补
                    this.state = this.mergeDefaults(this.state);
                    // 填充元数据区 + 页头（edit 模式）
                    this.fillMeta(d);
                    resolve();
                } else {
                    reject(new Error(resp.message || '策略不存在'));
                }
            });
        });
    },

    /**
     * 模板模式：GET /api/strategies/templates/{templateId}
     * 响应 data.configJson 为模板 JSON 字符串。加载后 strategy_id 置 null、name 留空待填。
     */
    loadTemplate(templateId) {
        return new Promise((resolve, reject) => {
            StockApp.get('/api/strategies/templates/' + encodeURIComponent(templateId), null, (resp) => {
                if (resp.code === 200 && resp.data && resp.data.configJson) {
                    try {
                        this.state = JSON.parse(resp.data.configJson);
                    } catch (e) {
                        this.state = this.clone(this.DEFAULT_CONFIG);
                    }
                    this.state = this.mergeDefaults(this.state);
                    // 模板克隆为新策略：清除业务 ID，name 让用户重新填（保留模板名作默认便于改）
                    this.state.strategy_id = null;
                    // 模板的 category/tags 也不带过来（watcher 元数据）
                    this.meta = { category: '', tags: [] };
                    resolve();
                } else {
                    reject(new Error(resp.message || '模板不存在'));
                }
            });
        });
    },

    // ===================== 表单 ↔ state =====================
    /**
     * 填充元数据区 (#f-meta-*) + 页头徽章/标题 (#editorStatusBadge / #editorStrategyName / #editorSubtitle)。
     * @param meta StrategyDTO
     */
    fillMeta(meta) {
        meta = meta || {};
        const metaGrid = document.getElementById('f-meta-grid');
        if (metaGrid && this.mode === 'edit') {
            metaGrid.style.display = 'grid';
            const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v || '—'; };
            setText('f-meta-id', meta.strategyId);
            setText('f-meta-version', meta.currentVersion != null ? 'v' + meta.currentVersion : '—');
            setText('f-meta-created', meta.createdAt ? String(meta.createdAt).slice(0, 10) : '—');
            setText('f-meta-updated', meta.updatedAt ? String(meta.updatedAt).slice(0, 10) : '—');
            if (meta.lastReturnPct != null) {
                const bt = (meta.lastReturnPct >= 0 ? '+' : '') + Number(meta.lastReturnPct).toFixed(1) +
                    '% / 夏普 ' + (meta.lastSharpe != null ? Number(meta.lastSharpe).toFixed(2) : '—');
                setText('f-meta-backtest', bt);
            }
        }
        // 页头状态徽章
        const badge = document.getElementById('editorStatusBadge');
        if (badge) {
            const STATUS_CLS = { DRAFT: 'str-status-draft', VERIFIED: 'str-status-verified', ACTIVE: 'str-status-active', ARCHIVED: 'str-status-archived' };
            badge.textContent = meta.status || 'DRAFT';
            badge.className = 'str-status-badge ' + (STATUS_CLS[meta.status] || 'str-status-draft');
            badge.style.display = 'inline-flex';
        }
        const nameEl = document.getElementById('editorStrategyName');
        if (nameEl && meta.name) nameEl.textContent = meta.name;
        const subEl = document.getElementById('editorSubtitle');
        if (subEl && meta.strategyId) {
            const ver = meta.currentVersion != null ? 'v' + meta.currentVersion : '';
            const t = meta.updatedAt ? String(meta.updatedAt).slice(0, 16).replace('T', ' ') : '';
            subEl.textContent = (meta.strategyId || '') + (ver ? ' · ' + ver : '') + (t ? ' · 最近保存 ' + t : '');
            const backLink = document.createElement('a');
            backLink.href = StockApp.contextPath + '/quant/strategies';
            backLink.style.cssText = 'text-decoration:none;color:inherit;margin-right:8px;';
            backLink.textContent = '← 返回列表';
            subEl.insertBefore(backLink, subEl.firstChild);
        }
    },

    /**
     * 把 state 回填到各 Tab 表单元素（state → 表单）。
     */
    renderFormFromState() {
        const s = this.state || {};
        this.setVal('f-name', s.name || '');
        this.setVal('f-description', s.description || '');
        this.setVal('f-tags', (this.meta.tags || []).join(','));
        this.setVal('f-category', this.meta.category || '');

        // ---- Tab 2 选股 ----
        // 4 层结构（spec 010 Task 12）：universe/factor/filter/portfolio。
        // normalizeScreenConfig 兼容旧 5 字段（顶层 universe/conditions/ranking/filters/top_n）。
        const sc = this.normalizeScreenConfig(s.screen_config || null);
        if (sc) {
            const universe = sc.universe || {};
            const pool = universe.pool || 'all_a_shares';
            this.setVal('f-universe', pool);
            this.applyUniverse(pool);
            this._loadManualStocksDetail(universe.stocks || []);

            const portfolio = sc.portfolio || {};
            this.setVal('f-top-n', portfolio.top_n != null ? portfolio.top_n : '');
            // spec 011 迭代 4：权重体系字段
            this.setVal('f-cash-reserve', portfolio.cash_reserve_pct != null ? portfolio.cash_reserve_pct : '');
            this.setVal('f-buffer-n', portfolio.buffer_n != null ? portfolio.buffer_n : '');
            this.applyOptionalNumber('f-max-weight-enable', 'f-max-weight', portfolio.max_weight_per_symbol);
            this.applyOptionalNumber('f-max-industry-enable', 'f-max-industry', portfolio.max_industry_exposure);

            // filter.conditions（旧结构从顶层 conditions 取，已由 normalizeScreenConfig 归一到 filter.conditions）
            const filter = sc.filter || {};
            this.setJson('f-screen-conditions', filter.conditions);

            // factor 层（原 ranking，single→method/factor/order，composite→method/weights）
            const f = sc.factor || null;
            if (f) {
                this.setVal('f-ranking-method', f.method || 'disabled');
                this.applyRankingMethod(f.method || 'disabled');
                this.setVal('f-ranking-factor', f.factor || '');
                this.setVal('f-ranking-order', f.order || 'desc');
                this.renderWeightRows(f.weights || {});
            } else {
                this.setVal('f-ranking-method', 'disabled');
                this.applyRankingMethod('disabled');
                this.renderWeightRows({});
            }

            this.setChecked('f-filter-exclude-st', filter.exclude_st);
            this.setChecked('f-filter-exclude-suspended', filter.exclude_suspended);
            this.setChecked('f-filter-exclude-limit-up', filter.exclude_limit_up);
            this.setChecked('f-filter-exclude-limit-down', filter.exclude_limit_down);
            this.setChecked('f-filter-use-industries', Array.isArray(filter.industries) && filter.industries.length > 0);
            this.setChecked('f-filter-use-exclude-industries', Array.isArray(filter.exclude_industries) && filter.exclude_industries.length > 0);
            this.setVal('f-filter-industries', (filter.industries || []).join(','));
            this.setVal('f-filter-exclude-industries', (filter.exclude_industries || []).join(','));
            this.setChecked('f-filter-use-min-list-days', filter.min_list_days != null);
            this.setVal('f-filter-min-list-days', filter.min_list_days != null ? filter.min_list_days : '');
        } else {
            this.setVal('f-universe', 'all_a_shares');
            this.applyUniverse('all_a_shares');
            this._manualStocks = [];
            this.renderManualCloud();
            this.setVal('f-ranking-method', 'disabled');
            this.applyRankingMethod('disabled');
            this.renderWeightRows({});
        }
        this.applyFilterToggles();

        // ---- Tab 3/4 买卖信号 ----
        const tc = s.trading_config || {};
        const signals = tc.signals || {};
        this.setJson('f-buy-conditions', signals.buy || null);
        this.setJson('f-sell-conditions', signals.sell || null);

        // ---- Tab 5 仓位 ----
        const ps = tc.position_sizing || {};
        this.setVal('f-ps-method', ps.method || 'order_target_percent');
        this.setVal('f-ps-target', ps.target != null ? ps.target : '');
        this.setVal('f-ps-sell-method', ps.sell_method || 'close_position');
        this.applyPositionMethod(ps.method || 'order_target_percent');
        this.renderPositionWeightRows((ps.params && ps.params.weights) ? ps.params.weights : {});

        // ---- Tab 6 止损止盈 ----
        const ex = tc.exit || {};
        const br = ex.bracket || null;
        this.setChecked('f-use-bracket', !!br);
        this.applyBracketToggle(!!br);
        if (br) {
            this.setVal('f-stop-loss', br.stop_loss_pct != null ? br.stop_loss_pct : '');
            this.setVal('f-take-profit', br.take_profit_pct != null ? br.take_profit_pct : '');
            this.setChecked('f-use-atr-stop', !!br.use_atr_stop);
            this.applyAtrToggle(!!br.use_atr_stop);
            this.setVal('f-atr-period', br.atr_period != null ? br.atr_period : '');
            this.setVal('f-atr-multiplier', br.atr_multiplier != null ? br.atr_multiplier : '');
        }
        this.renderExitRules(ex.rules || []);

        // ---- Tab 7 调仓 ----
        const rb = tc.rebalance || null;
        if (rb) {
            this.setVal('f-reb-frequency', rb.frequency || 'monthly');
            // spec 011 P2-1：trigger 优先；旧数据 day_of_period 兼容映射（<=1→first，否则 last）
            let trig = rb.trigger;
            if (!trig && rb.day_of_period != null) {
                trig = (rb.day_of_period || 0) <= 1 ? 'first' : 'last';
            }
            this.setVal('f-reb-trigger', trig || 'first');
            this.checkRadio('f-reb-replace', rb.replace_method || 'full');
            this.checkRadio('f-reb-weight', rb.weight_mode || 'equal');
            // spec 011 迭代 4：min_holding_bars / 涨跌停拒单
            this.setVal('f-min-holding-bars', rb.min_holding_bars != null ? rb.min_holding_bars : '');
            this.setChecked('f-reject-limit-up', rb.reject_limit_up_on_buy !== false);
            this.setChecked('f-reject-limit-down', rb.reject_limit_down_on_sell !== false);
            // spec 013 P2-9：execution（分批调仓 + 冲击成本）
            const exec = rb.execution || {};
            this.setVal('f-exec-split-days', exec.split_days != null ? exec.split_days : 1);
            this.setVal('f-exec-impact-bps', exec.impact_cost_bps != null ? exec.impact_cost_bps : '');
        } else {
            this.setVal('f-reb-trigger', 'first');
            this.checkRadio('f-reb-replace', 'full');
            this.checkRadio('f-reb-weight', 'equal');
            this.setVal('f-min-holding-bars', '');
            this.setChecked('f-reject-limit-up', true);
            this.setChecked('f-reject-limit-down', true);
            // spec 013 P2-9：execution 默认值重置
            this.setVal('f-exec-split-days', 1);
            this.setVal('f-exec-impact-bps', '');
        }
        this.applyRebTriggerVisibility();

        // ---- Tab 8 回测 ----
        const bt = s.backtest_config || {};
        this.setVal('f-bt-cash', bt.initial_cash != null ? bt.initial_cash : '');
        this.setVal('f-bt-start', bt.start_date || '');
        this.setVal('f-bt-end', bt.end_date || '');
        this.setVal('f-bt-broker', bt.broker_profile || 'cn_stock_miniqmt');
        this.setVal('f-bt-lot-size', bt.lot_size != null ? bt.lot_size : 100);
        this.setVal('f-bt-warmup', bt.warmup_period != null ? bt.warmup_period : '');
        this.setVal('f-bt-history', bt.history_depth != null ? bt.history_depth : '');
        const hasCustomFee = bt.commission_rate != null || bt.stamp_tax_rate != null ||
            bt.transfer_fee_rate != null || bt.min_commission != null;
        this.setChecked('f-bt-use-custom-fee', hasCustomFee);
        this.applyFeeToggle(hasCustomFee);
        this.setVal('f-bt-commission', bt.commission_rate != null ? bt.commission_rate : '');
        this.setVal('f-bt-stamp', bt.stamp_tax_rate != null ? bt.stamp_tax_rate : '');
        this.setVal('f-bt-transfer', bt.transfer_fee_rate != null ? bt.transfer_fee_rate : '');
        this.setVal('f-bt-min-commission', bt.min_commission != null ? bt.min_commission : '');
        const hasSlip = bt.slippage != null;
        this.setChecked('f-bt-use-slippage', hasSlip);
        this.applySlippageToggle(hasSlip);
        if (bt.slippage && typeof bt.slippage === 'object') {
            this.setVal('f-bt-slippage-type', bt.slippage.type || 'percent');
            this.setVal('f-bt-slippage-value', bt.slippage.value != null ? bt.slippage.value : '');
        } else if (typeof bt.slippage === 'number') {
            this.setVal('f-bt-slippage-type', 'percent');
            this.setVal('f-bt-slippage-value', bt.slippage);
        }
        // fill_policy：已有值反向匹配；无值时按范式默认（轮动→当日收盘，择时→空）
        if (bt.fill_policy && typeof bt.fill_policy === 'object') {
            this.setVal('f-bt-fill-policy', JSON.stringify(bt.fill_policy));
        } else {
            const closeFp = '{"price_basis":"close","temporal":"same_cycle","bar_offset":0}';
            this.setVal('f-bt-fill-policy', this.paradigm === 'rebalance' ? closeFp : '');
        }
        // spec 011 P2-2/3：risk_config 回填
        const hasRisk = bt.risk_config && typeof bt.risk_config === 'object';
        this.setChecked('f-bt-use-risk-config', hasRisk);
        this.applyRiskToggle(hasRisk);
        this.setVal('f-bt-risk-max-position', hasRisk && bt.risk_config.max_position_pct != null ? bt.risk_config.max_position_pct : '');
        this.setVal('f-bt-risk-max-dd', hasRisk && bt.risk_config.max_drawdown_pct != null ? bt.risk_config.max_drawdown_pct : '');

        // 同步条件树可视化组件：edit/template/screenSource 导入后 state 已更新，
        // 把新树推给已挂载的 builder（visual 模式）或刷新 textarea（json 模式）。
        this.syncCondBuildersAfterRender();
    },

    /**
     * renderFormFromState 之后调用：把 state 里的最新条件树同步到 4 个区域。
     * - visual 模式：若 builder 已挂载则 setTree，否则（首次/因子未到）挂载。
     * - json 模式：把树写入 textarea。
     * - exit.rules 的 builder 在 renderExitRules 内部已挂载，这里仅处理 3 个静态区域。
     */
    syncCondBuildersAfterRender() {
        this.COND_AREAS.forEach((area) => {
            const tid = area.targetId;
            const mode = this._condMode[tid] || 'visual';
            const tree = this.getCondByPath(area.statePath);
            if (mode === 'visual') {
                if (this._condBuilders[tid]) {
                    this._condBuilders[tid].setTree(tree);
                } else {
                    this.mountConditionBuilder(tid);
                }
            } else if (mode === 'json') {
                const ta = document.getElementById(tid);
                if (ta) {
                    ta.value = (tree && !this.isEmptyTree(tree))
                        ? JSON.stringify(tree, null, 2) : '';
                }
            }
        });
    },

    /**
     * 从各 Tab 表单元素读回 state（表单 → state）。
     * 条件树文本框解析失败时记入 this._parseErrors，由 save() 前置校验消费。
     */
    collectStateFromForm() {
        this._parseErrors = []; // [{tab, field, message}]
        const s = this.state;

        // ---- Tab 1 基本 ----
        s.name = this.getVal('f-name');
        s.description = this.getVal('f-description') || '';

        // watcher 元数据
        this.meta.category = this.getVal('f-category') || '';
        const tagStr = this.getVal('f-tags') || '';
        this.meta.tags = tagStr.split(/[,，\s]+/).map((t) => t.trim()).filter(Boolean);

        // ---- Tab 2 选股（按范式分支：择时仅手动标的，轮动收集全部字段）----
        const stocks = (this._manualStocks || []).map(function (s) { return s.tsCode; }).filter(Boolean);

        if (this.paradigm === 'signals') {
            // 择时范式：screen_config 仅 universe（pool=manual + stocks），禁止 factor/filter/portfolio
            // （与后端 SIGNALS_SCREEN_CONFIG_FORBIDDEN 校验对齐，主动清空防止历史脏数据）
            s.screen_config = {
                universe: {
                    pool: 'manual',
                    stocks: stocks,
                },
            };
        } else {
            // 轮动范式：收集 4 层 screen_config（universe/factor/filter/portfolio），spec 010 Task 12
            const pool = this.getVal('f-universe') || 'all_a_shares';
            const topN = this.getNum('f-top-n');
            const screenConditions = this.parseJsonField('f-screen-conditions', 'tab-screen', 'filter.conditions');
            const factor = this.collectRanking();
            const filters = this.collectFilters();

            const hasScreenContent = pool || stocks.length || topN != null ||
                (screenConditions && !this.isEmptyTree(screenConditions)) ||
                factor || filters;
            if (hasScreenContent) {
                // ---- universe 层（spec 011 P1-1：point-in-time 已强制开启，不再下发字段）----
                const universe = { pool: pool };
                if (pool === 'manual' && stocks.length) universe.stocks = stocks;
                else universe.stocks = null;

                s.screen_config = { universe: universe };

                // ---- factor 层（原 ranking 平移：single→method/factor/order，composite→method/weights）----
                if (factor) s.screen_config.factor = factor;

                // ---- filter 层（合并原 conditions + filters）----
                const filter = {};
                if (screenConditions && !this.isEmptyTree(screenConditions)) filter.conditions = screenConditions;
                if (filters) Object.assign(filter, filters);
                if (Object.keys(filter).length) s.screen_config.filter = filter;

                // ---- portfolio 层（原 top_n 平移 + spec 011 迭代 4 权重体系）----
                const portfolio = {};
                if (topN != null) portfolio.top_n = topN;
                const cashReserve = this.getNum('f-cash-reserve');
                if (cashReserve != null) portfolio.cash_reserve_pct = cashReserve;
                const bufferN = this.getInt('f-buffer-n');
                if (bufferN != null) portfolio.buffer_n = bufferN;
                const maxWeight = this.getOptionalNumber('f-max-weight-enable', 'f-max-weight');
                if (maxWeight != null) portfolio.max_weight_per_symbol = maxWeight;
                const maxIndustry = this.getOptionalNumber('f-max-industry-enable', 'f-max-industry');
                if (maxIndustry != null) portfolio.max_industry_exposure = maxIndustry;
                if (Object.keys(portfolio).length) s.screen_config.portfolio = portfolio;
            } else {
                s.screen_config = null;
            }
        }

        // ---- Tab 3/4/5/6/7 交易配置（按范式二选一，spec 009 Task 10）----
        s.trading_config = s.trading_config || {};
        if (this.paradigm === 'rebalance') {
            // 轮动范式：rebalance + exit（止损止盈，spec 011 P1-5）；不产出 signals/position_sizing
            const rb = this.collectRebalance();
            if (rb) s.trading_config.rebalance = rb; else delete s.trading_config.rebalance;
            delete s.trading_config.signals;
            delete s.trading_config.position_sizing;
            const exitObj = this.collectExit();
            if (exitObj) s.trading_config.exit = exitObj; else delete s.trading_config.exit;
        } else {
            // 择时范式：signals + position_sizing + exit，不产出 rebalance
            s.trading_config.signals = {
                buy: this.parseJsonField('f-buy-conditions', 'tab-buy', 'trading_config.signals.buy') ||
                    { operator: 'AND', conditions: [] },
                sell: this.parseJsonField('f-sell-conditions', 'tab-sell', 'trading_config.signals.sell') ||
                    { operator: 'AND', conditions: [] },
            };

            // ---- Tab 5 仓位 ----
            const psMethod = this.getVal('f-ps-method') || 'order_target_percent';
            const ps = { method: psMethod };
            if (!['buy_all', 'close_position'].includes(psMethod)) {
                const t = this.getRaw('f-ps-target');
                if (t !== '') {
                    const n = Number(t);
                    ps.target = isNaN(n) ? t : n;
                }
            }
            const psWeights = this.collectPositionWeights();
            if (psMethod === 'order_target_weights' && Object.keys(psWeights).length) {
                ps.params = { weights: psWeights };
            }
            const sellMethod = this.getVal('f-ps-sell-method');
            if (sellMethod && sellMethod !== 'close_position') ps.sell_method = sellMethod;
            s.trading_config.position_sizing = ps;

            // ---- Tab 6 止损止盈 ----
            const exitObj = this.collectExit();
            if (exitObj) s.trading_config.exit = exitObj; else delete s.trading_config.exit;
            delete s.trading_config.rebalance;
        }

        // ---- Tab 8 回测 ----
        const bt = {};
        const cash = this.getNum('f-bt-cash');
        if (cash != null) bt.initial_cash = cash;
        if (this.getVal('f-bt-start')) bt.start_date = this.getVal('f-bt-start');
        if (this.getVal('f-bt-end')) bt.end_date = this.getVal('f-bt-end');
        bt.broker_profile = this.getVal('f-bt-broker') || 'cn_stock_miniqmt';
        const lot = this.getNum('f-bt-lot-size');
        if (lot != null) bt.lot_size = lot;
        const warm = this.getNum('f-bt-warmup');
        if (warm != null) bt.warmup_period = warm;
        const hist = this.getNum('f-bt-history');
        if (hist != null) bt.history_depth = hist;
        if (this.getChecked('f-bt-use-custom-fee')) {
            const c = this.getNum('f-bt-commission');
            const st = this.getNum('f-bt-stamp');
            const tf = this.getNum('f-bt-transfer');
            const mc = this.getNum('f-bt-min-commission');
            if (c != null) bt.commission_rate = c;
            if (st != null) bt.stamp_tax_rate = st;
            if (tf != null) bt.transfer_fee_rate = tf;
            if (mc != null) bt.min_commission = mc;
        }
        if (this.getChecked('f-bt-use-slippage')) {
            const st = this.getVal('f-bt-slippage-type') || 'percent';
            const sv = this.getNum('f-bt-slippage-value');
            if (sv != null) bt.slippage = { type: st, value: sv };
        }
        const benchmark = this.getVal('f-bt-benchmark');
        if (benchmark) bt.benchmark = benchmark;
        const fpRaw = this.getVal('f-bt-fill-policy');
        if (fpRaw) {
            try { bt.fill_policy = JSON.parse(fpRaw); } catch (e) { /* 忽略非法值 */ }
        }
        // spec 011 P2-2/3：risk_config（dict，空值不写）
        if (this.getChecked('f-bt-use-risk-config')) {
            const rc = {};
            const mp = this.getNum('f-bt-risk-max-position');
            const md = this.getNum('f-bt-risk-max-dd');
            if (mp != null) rc.max_position_pct = mp;
            if (md != null) rc.max_drawdown_pct = md;
            if (Object.keys(rc).length) bt.risk_config = rc;
        }
        s.backtest_config = bt;

        return this._parseErrors;
    },

    /**
     * 收集 factor 层（原 ranking，spec 010 Task 12 平移到 screen_config.factor）。
     * disabled 返回 null，single/composite 构造结构（method/factor/order 或 method/weights）。
     */
    collectRanking() {
        const m = this.getVal('f-ranking-method');
        if (!m || m === 'disabled') return null;
        if (m === 'single') {
            const f = this.getVal('f-ranking-factor');
            if (!f) return null;
            return { method: 'single', factor: f, order: this.getVal('f-ranking-order') || 'desc' };
        }
        if (m === 'composite') {
            const weights = this.collectWeightRows();
            if (!Object.keys(weights).length) return null;
            return { method: 'composite', weights: weights };
        }
        return null;
    },

    /**
     * 收集 Tab 7 调仓（rebalance）。仅在 rebalance 范式下返回对象，否则返回 null。
     * frequency 必填，无值默认 monthly。
     */
    collectRebalance() {
        if (this.paradigm !== 'rebalance') return null;
        const rb = {
            frequency: this.getVal('f-reb-frequency') || 'monthly',
            trigger: this.getVal('f-reb-trigger') || 'first',
            replace_method: this.getRadio('f-reb-replace') || 'full',
            weight_mode: this.getRadio('f-reb-weight') || 'equal',
        };
        // spec 011 P2-1：trigger 下拉；day_of_period 不主动写（仅旧数据兼容由 refill 处理）
        // spec 011 迭代 4：min_holding_bars / 涨跌停拒单
        const minHolding = this.getInt('f-min-holding-bars');
        if (minHolding != null) rb.min_holding_bars = minHolding;
        rb.reject_limit_up_on_buy = this.getChecked('f-reject-limit-up');
        rb.reject_limit_down_on_sell = this.getChecked('f-reject-limit-down');
        // spec 013 P2-9：execution（分批调仓 + 冲击成本）
        const splitDays = this.getInt('f-exec-split-days');
        const impactBps = this.getNum('f-exec-impact-bps');
        if ((splitDays != null && splitDays > 1) || impactBps != null) {
            rb.execution = { split_days: splitDays != null ? splitDays : 1 };
            if (impactBps != null) rb.execution.impact_cost_bps = impactBps;
        }
        return rb;
    },

    /**
     * 收集 Tab 6 止损止盈（exit）。两范式共用（spec 011 P1-5）。
     * 返回 {bracket?, rules?} 或 null（未启用）。
     */
    collectExit() {
        const useBracket = this.getChecked('f-use-bracket');
        const exitRules = this.collectExitRules();
        if (!useBracket && !exitRules.length) return null;
        const exit = {};
        if (useBracket) {
            const bracket = {};
            const sl = this.getNum('f-stop-loss');
            const tp = this.getNum('f-take-profit');
            if (sl != null) bracket.stop_loss_pct = sl;
            if (tp != null) bracket.take_profit_pct = tp;
            if (this.getChecked('f-use-atr-stop')) {
                bracket.use_atr_stop = true;
                const atrPeriod = this.getNum('f-atr-period');
                if (atrPeriod != null) bracket.atr_period = atrPeriod;
                const atrMult = this.getNum('f-atr-multiplier');
                if (atrMult != null) bracket.atr_multiplier = atrMult;
            }
            exit.bracket = bracket;
        }
        if (exitRules.length) exit.rules = exitRules;
        return exit;
    },

    /**
     * 收集 filters：仅当任一开关或值非默认时返回对象。
     */
    collectFilters() {
        const f = {};
        f.exclude_st = this.getChecked('f-filter-exclude-st');
        f.exclude_suspended = this.getChecked('f-filter-exclude-suspended');
        if (this.getChecked('f-filter-exclude-limit-up')) f.exclude_limit_up = true;
        if (this.getChecked('f-filter-exclude-limit-down')) f.exclude_limit_down = true;
        if (this.getChecked('f-filter-use-industries')) {
            const v = this.getVal('f-filter-industries') || '';
            const arr = v.split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
            if (arr.length) f.industries = arr;
        }
        if (this.getChecked('f-filter-use-exclude-industries')) {
            const v = this.getVal('f-filter-exclude-industries') || '';
            const arr = v.split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
            if (arr.length) f.exclude_industries = arr;
        }
        if (this.getChecked('f-filter-use-min-list-days')) {
            const n = this.getNum('f-filter-min-list-days');
            if (n != null) f.min_list_days = n;
        }
        // 全默认（exclude_st/exclude_suspended 默认 True）视作空
        if (f.exclude_st === true && f.exclude_suspended === true &&
            f.exclude_limit_up !== true && f.exclude_limit_down !== true &&
            !f.industries && !f.exclude_industries && f.min_list_days == null) {
            return null;
        }
        return f;
    },

    // ===================== 动态列表 =====================
    /**
     * ranking composite 权重行渲染。
     */
    renderWeightRows(weights) {
        const wrap = document.getElementById('f-ranking-weights');
        if (!wrap) return;
        const entries = Object.keys(weights || {}).length
            ? Object.entries(weights)
            : [['', 1]];
        wrap.innerHTML = entries.map(([k, v], i) => this.weightRowHtml(k, v, i)).join('');
    },

    weightRowHtml(factor, weight, idx) {
        const direction = (weight == null || Number(weight) >= 0) ? '+' : '-';
        const absVal = weight == null ? 1 : Math.abs(Number(weight));
        const dirLabel = direction === '+' ? '↑ 越大越好' : '↓ 越小越好';
        const dirClass = direction === '+' ? 'btn-outline-success' : 'btn-outline-danger';
        return '<div class="row g-1 align-items-center" data-row="ranking-weight">' +
            '<div class="col"><input type="text" list="factor-keys-datalist" class="form-control form-control-sm" placeholder="factorKey（联想选择）" value="' + StockApp.escapeHtml(factor) + '" data-k="factor"></div>' +
            '<div class="col-auto"><button type="button" class="btn btn-sm ' + dirClass + '" data-k="direction" data-direction="' + direction + '" title="点击切换方向">' + dirLabel + '</button></div>' +
            '<div class="col"><input type="number" class="form-control form-control-sm" step="0.1" min="0" placeholder="权重绝对值" value="' + StockApp.escapeHtml(String(absVal)) + '" data-k="weight"></div>' +
            '<div class="col-auto"><button type="button" class="btn btn-sm btn-outline-danger" data-del="ranking-weight">×</button></div>' +
            '</div>';
    },

    collectWeightRows() {
        const out = {};
        document.querySelectorAll('#f-ranking-weights [data-row="ranking-weight"]').forEach((row) => {
            const k = (row.querySelector('[data-k="factor"]') || {}).value;
            const v = (row.querySelector('[data-k="weight"]') || {}).value;
            const dirBtn = row.querySelector('[data-k="direction"]');
            const direction = dirBtn ? dirBtn.getAttribute('data-direction') : '+';
            if (k && v !== '') {
                const n = Number(v);
                if (isNaN(n)) return;
                const signed = direction === '-' ? -Math.abs(n) : Math.abs(n);
                out[k.trim()] = signed;
            }
        });
        return out;
    },

    /**
     * position_sizing.params.weights（order_target_weights）行渲染。
     */
    renderPositionWeightRows(weights) {
        const wrap = document.getElementById('f-ps-weights');
        if (!wrap) return;
        const entries = Object.keys(weights || {}).length
            ? Object.entries(weights)
            : [['', 0]];
        wrap.innerHTML = entries.map(([k, v]) => this.psWeightRowHtml(k, v)).join('');
    },

    psWeightRowHtml(symbol, weight) {
        return '<div class="row g-1" data-row="ps-weight">' +
            '<div class="col"><input type="text" class="form-control form-control-sm" placeholder="symbol" value="' + StockApp.escapeHtml(symbol) + '" data-k="symbol"></div>' +
            '<div class="col"><input type="number" class="form-control form-control-sm" step="0.01" placeholder="比例" value="' + StockApp.escapeHtml(String(weight)) + '" data-k="weight"></div>' +
            '<div class="col-auto"><button type="button" class="btn btn-sm btn-outline-danger" data-del="ps-weight">×</button></div>' +
            '</div>';
    },

    collectPositionWeights() {
        const out = {};
        document.querySelectorAll('#f-ps-weights [data-row="ps-weight"]').forEach((row) => {
            const k = (row.querySelector('[data-k="symbol"]') || {}).value;
            const v = (row.querySelector('[data-k="weight"]') || {}).value;
            if (k && v !== '') {
                const n = Number(v);
                out[k.trim()] = isNaN(n) ? Number(v) : n;
            }
        });
        return out;
    },

    /**
     * exit.rules 动态行渲染。每行：name + 模式切换器 + condition(可视化/JSON) + action。
     * 每行用唯一 uid 标识（data-uid），可视化 builder 按 uid 挂载/销毁，避免增删行索引错乱。
     * 条件树暂存到 this._exitCondStash[uid]，collectExitRules 按 uid 取（visual）或 parse textarea（json）。
     */
    renderExitRules(rules) {
        const wrap = document.getElementById('f-exit-rules');
        if (!wrap) return;
        // 先销毁所有旧 exit builder，避免内存泄漏
        this.destroyAllExitBuilders();
        const list = Array.isArray(rules) && rules.length ? rules : [];
        wrap.innerHTML = list.map((r, i) => this.exitRuleRowHtml(r, i)).join('');
        // 渲染后挂载每行的 builder + 初始化模式
        list.forEach((r, i) => this.initExitRowBuilder(i, r && r.condition));
    },

    /**
     * 生成 exit rule 行 HTML。idx 仅用于初始回填 textarea，运行时用 uid 寻址。
     */
    exitRuleRowHtml(rule, idx) {
        const uid = 'exit-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
        const name = (rule && rule.name) || '';
        const cond = rule && rule.condition ? JSON.stringify(rule.condition, null, 2) : '';
        const action = (rule && rule.action) || 'close_position';
        const targetId = 'f-exit-rule-condition-' + uid;
        return '<div class="border rounded p-2" data-row="exit-rule" data-uid="' + StockApp.escapeHtml(uid) + '" data-target="' + StockApp.escapeHtml(targetId) + '">' +
            '<div class="d-flex align-items-center gap-2 mb-2 flex-wrap">' +
            '<div class="d-flex align-items-center gap-1">' +
            '<label class="form-label mb-0 text-nowrap small text-muted">规则名 name</label>' +
            '<input type="text" class="form-control form-control-sm" style="max-width:180px;" placeholder="如：跌破均线清仓" value="' + StockApp.escapeHtml(name) + '" data-k="name">' +
            '</div>' +
            '<div class="d-flex align-items-center gap-1">' +
            '<label class="form-label mb-0 text-nowrap small text-muted">触发动作 action</label>' +
            '<div class="d-flex gap-2">' +
            '<div class="form-check"><input class="form-check-input" type="radio" name="f-exit-action-' + StockApp.escapeHtml(uid) + '" data-k="action" value="close_position"' + (action === 'close_position' ? ' checked' : '') + '><label class="form-check-label">平仓 close_position</label></div>' +
            '<div class="form-check"><input class="form-check-input" type="radio" name="f-exit-action-' + StockApp.escapeHtml(uid) + '" data-k="action" value="sell"' + (action === 'sell' ? ' checked' : '') + '><label class="form-check-label">卖出 sell</label></div>' +
            '</div>' +
            '</div>' +
            '<button type="button" class="btn btn-sm btn-outline-danger ms-auto" data-del="exit-rule">×</button>' +
            '</div>' +
            '<div class="cond-mode-switch" data-cond-target="' + StockApp.escapeHtml(targetId) + '">' +
            '<button type="button" class="cond-mode-btn active" data-mode="visual"><i class="bi bi-diagram-3"></i> 可视化</button>' +
            '<button type="button" class="cond-mode-btn" data-mode="json"><i class="bi bi-braces"></i> JSON</button>' +
            '<button type="button" class="cond-mode-btn" data-mode="ai" disabled><i class="bi bi-robot"></i> AI</button>' +
            '</div>' +
            '<div class="cond-visual-wrap" data-cond-visual="' + StockApp.escapeHtml(targetId) + '"></div>' +
            '<textarea class="form-control form-control-sm font-monospace" rows="3" spellcheck="false" style="display:none;" id="' + StockApp.escapeHtml(targetId) + '" placeholder=\'{"operator":"AND","conditions":[...]}\' data-k="condition">' + StockApp.escapeHtml(cond) + '</textarea>' +
            '</div>';
    },

    /**
     * 初始化某个 exit rule 行的可视化 builder。
     * exit 的条件树用 stash（this._exitCondStash[uid]）维护，不写 statePath（idx 易变）。
     */
    initExitRowBuilder(idx, initialCond) {
        const row = document.querySelectorAll('#f-exit-rules [data-row="exit-rule"]')[idx];
        if (!row) return;
        const uid = row.getAttribute('data-uid');
        const targetId = row.getAttribute('data-target');
        if (!uid || !targetId) return;

        this._exitCondStash = this._exitCondStash || {};
        this._exitCondStash[uid] = initialCond || { operator: 'AND', conditions: [] };

        // 注册元信息：exit 用 stash，statePath 留空（onCondBuilderChange 走 stash 分支）
        this.registerCondMeta(targetId, '', { allowRef: true, allowCross: true });
        this._condMode[targetId] = 'visual';
        this.applyCondModeUI(targetId, 'visual');

        const self = this;
        const container = row.querySelector('[data-cond-visual="' + targetId + '"]');
        if (!container || !window.ConditionTreeBuilder) return;
        const ctrl = ConditionTreeBuilder.mount(container, {
            tree: this._exitCondStash[uid],
            factors: this.factorMeta.factors || [],
            categories: this.factorMeta.categories || [],
            allowRef: true,
            allowCross: true,
            onChange: function (newTree) {
                self._exitCondStash[uid] = newTree;
                // 同步到 textarea（防循环）
                const ta = document.getElementById(targetId);
                if (ta) {
                    self._syncing = true;
                    try {
                        ta.value = (newTree && !self.isEmptyTree(newTree))
                            ? JSON.stringify(newTree, null, 2) : '';
                    } finally {
                        self._syncing = false;
                    }
                }
            },
        });
        this._condBuilders[targetId] = ctrl;
    },

    /**
     * 销毁所有 exit rule 行的 builder 并清空 stash（renderExitRules 重建前调用）。
     */
    destroyAllExitBuilders() {
        document.querySelectorAll('#f-exit-rules [data-row="exit-rule"]').forEach((row) => {
            const targetId = row.getAttribute('data-target');
            if (targetId && this._condBuilders[targetId]) {
                try { this._condBuilders[targetId].destroy(); } catch (e) { /* noop */ }
                delete this._condBuilders[targetId];
            }
        });
        this._exitCondStash = {};
    },

    /**
     * 收集 exit.rules。
     * - visual 模式：从 this._exitCondStash[uid] 取条件树。
     * - json 模式：从 textarea parse（失败记 _parseErrors）。
     */
    collectExitRules() {
        const out = [];
        document.querySelectorAll('#f-exit-rules [data-row="exit-rule"]').forEach((row) => {
            const uid = row.getAttribute('data-uid');
            const targetId = row.getAttribute('data-target');
            const name = (row.querySelector('[data-k="name"]') || {}).value || '';
            const actionRadio = row.querySelector('[data-k="action"]:checked');
            const action = (actionRadio && actionRadio.value) || 'close_position';
            const mode = (targetId && this._condMode[targetId]) || 'visual';

            let cond = null;
            if (mode === 'visual') {
                cond = (uid && this._exitCondStash && this._exitCondStash[uid]) || null;
                if (cond && this.isEmptyTree(cond)) cond = null;
            } else {
                const condStr = ((row.querySelector('[data-k="condition"]') || {}).value || '').trim();
                if (condStr) {
                    try {
                        cond = JSON.parse(condStr);
                    } catch (e) {
                        this._parseErrors.push({
                            tab: 'tab-exit',
                            field: 'exit.rules',
                            message: '出场规则「' + (name || '未命名') + '」JSON 格式错误：' + e.message,
                        });
                        return;
                    }
                }
            }
            if (!cond) return;
            const rule = { condition: cond };
            if (name) rule.name = name;
            if (action) rule.action = action;
            out.push(rule);
        });
        return out;
    },

    // ===================== 策略范式（spec 009 Task 9/10） =====================
    /**
     * 初始化范式控件：从 state 推断当前范式，绑定按钮点击，刷新 UI。
     * 推断规则：trading_config.rebalance 存在 → rebalance；否则 → signals。
     */
    initParadigm() {
        const tc = (this.state && this.state.trading_config) || {};
        this.paradigm = tc.rebalance ? 'rebalance' : 'signals';

        const wrap = document.getElementById('f-paradigm');
        if (wrap) {
            wrap.querySelectorAll('.str-paradigm-btn').forEach((btn) => {
                btn.classList.toggle('active', btn.getAttribute('data-paradigm-val') === this.paradigm);
                btn.addEventListener('click', () => {
                    const val = btn.getAttribute('data-paradigm-val');
                    if (val && val !== this.paradigm) this.setParadigm(val);
                });
            });
        }
        this.applyParadigm(this.paradigm);
    },

    /**
     * 设置范式并联动刷新（控件 → state）。
     */
    setParadigm(paradigm) {
        if (paradigm !== 'signals' && paradigm !== 'rebalance') return;
        this.paradigm = paradigm;
        const wrap = document.getElementById('f-paradigm');
        if (wrap) {
            wrap.querySelectorAll('.str-paradigm-btn').forEach((btn) => {
                btn.classList.toggle('active', btn.getAttribute('data-paradigm-val') === paradigm);
            });
        }
        this.applyParadigm(paradigm);
        this.collectStateFromForm();
        this.updateJsonPreview();
    },

    /**
     * 按范式刷新 Tab 显隐 + universe 选项可用性 + 计数文案。
     * signals：显示 Tab3/4/5/6，隐藏 Tab7；universe 仅 manual；手动计数 ≤10。
     * rebalance：隐藏 Tab3/4/5/6，显示 Tab7；universe 可 csi300/csi500/manual；manual 不限数量。
     */
    applyParadigm(paradigm) {
        const tabs = document.querySelectorAll('#strategyTab .str-tab-btn');
        tabs.forEach((btn) => {
            const p = btn.getAttribute('data-paradigm');
            if (!p) return;
            const show = (p === 'both' || p === paradigm);
            btn.style.display = show ? '' : 'none';
            // 若当前激活的 Tab 被隐藏，切回基本信息 Tab
            if (!show && btn.classList.contains('active')) {
                btn.classList.remove('active');
                const basicBtn = document.querySelector('#strategyTab .str-tab-btn[data-bs-target="#tab-basic"]');
                const basicPane = document.getElementById('tab-basic');
                if (basicBtn) basicBtn.classList.add('active');
                if (basicPane) { basicPane.classList.add('show', 'active'); }
                const targetSel = btn.getAttribute('data-bs-target');
                if (targetSel) {
                    const pane = document.querySelector(targetSel);
                    if (pane) pane.classList.remove('show', 'active');
                }
            }
        });

        // panel 显隐（display:none 保留 DOM）
        document.querySelectorAll('#strategyTabContent .tab-pane[data-paradigm]').forEach((pane) => {
            const p = pane.getAttribute('data-paradigm');
            pane.style.display = (p === 'both' || p === paradigm) ? '' : 'none';
        });

        // 提示文案
        const hint = document.getElementById('f-paradigm-hint');
        if (hint) {
            hint.textContent = paradigm === 'signals'
                ? '择时范式：买卖信号 + 仓位 + 出场；仅支持手动指定股票池（≤10 只）。'
                : '轮动范式：调仓规则；股票池可选 沪深300/中证500/手动（不限数量）。';
        }

        this.applyUniverseForParadigm(paradigm);
        this.refreshManualCountForParadigm(paradigm);
        // 确保两套股票池搜索框都绑定（择时用 f-pool-stocks-search，轮动用 f-stocks-search）
        this._ensureManualStockSuggest();
        // fill_policy 范式联动：仅在控件为「默认（空）」时切换默认值，尊重用户显式选择
        const fpSel = document.getElementById('f-bt-fill-policy');
        if (fpSel && fpSel.value === '') {
            const closeFp = '{"price_basis":"close","temporal":"same_cycle","bar_offset":0}';
            fpSel.value = paradigm === 'rebalance' ? closeFp : '';
        }
    },

    /**
     * 按范式调整 universe 下拉的选项可用性，并在不合法时纠正当前值。
     */
    applyUniverseForParadigm(paradigm) {
        const sel = document.getElementById('f-universe');
        if (!sel) return;
        const poolOnly = paradigm === 'signals'; // signals 仅 manual
        ['all_a_shares', 'csi300', 'csi500'].forEach((val) => {
            const opt = sel.querySelector('option[value="' + val + '"]');
            if (opt) opt.disabled = poolOnly;
        });
        // signals 下若当前值不是 manual，强制切到 manual
        if (poolOnly && sel.value !== 'manual') {
            this.setVal('f-universe', 'manual');
            this.applyUniverse('manual');
        } else {
            this.applyUniverse(sel.value);
        }
    },

    /**
     * 按范式刷新手动标的计数文案与软上限红色提示。
     */
    refreshManualCountForParadigm(paradigm) {
        const n = (this._manualStocks || []).length;
        const max = paradigm === 'signals' ? this.SIGNALS_MANUAL_MAX : this._MANUAL_STOCK_MAX;
        const color = n > max ? '#dc3545' : '';
        // 两套计数 DOM 都刷新
        ['f-stocks-count', 'f-pool-stocks-count'].forEach((id) => {
            const el = document.getElementById(id);
            if (!el) return;
            el.textContent = n + '/' + max;
            el.style.color = color;
        });
    },

    applyUniverse(value) {
        const wrap = document.getElementById('f-stocks-wrap');
        if (wrap) wrap.style.display = value === 'manual' ? '' : 'none';
        if (value === 'manual') this._ensureManualStockSuggest();
    },

    // ===================== 手动股票池（搜索建议 + 标签云） =====================
    // 两套 DOM 共用同一份 this._manualStocks：
    //   - f-stocks-*      ：轮动范式（#tab-screen 内）
    //   - f-pool-stocks-* ：择时范式（#tab-pool 内）
    _ensureManualStockSuggest() {
        const self = this;
        const bindInput = (inputId) => {
            const input = document.getElementById(inputId);
            if (!input || typeof SearchSuggest === 'undefined') return false;
            new SearchSuggest(input, {
                onSelect: function (item) {
                    self.addManualStock(item);
                    input.value = '';
                    input.focus();
                },
            });
            return true;
        };
        // 轮动范式的搜索框
        if (!this._manualSuggestBound) {
            this._manualSuggestBound = bindInput('f-stocks-search');
        }
        // 择时范式的搜索框
        if (!this._poolSuggestBound) {
            this._poolSuggestBound = bindInput('f-pool-stocks-search');
        }
    },

    addManualStock(item) {
        if (!item || !item.tsCode) return;
        // 确保两套搜索框都绑定（择时范式可能未经过 applyUniverse）
        this._ensureManualStockSuggest();
        if (this._manualStocks.some(function (s) { return s.tsCode === item.tsCode; })) {
            StockApp.toast('已存在：' + (item.name || item.tsCode), 'info');
            return;
        }
        // signals 范式：软上限 10（超限仅红色提示，不阻断，后端校验）；rebalance：硬上限 50
        const hardMax = this.paradigm === 'signals' ? null : this._MANUAL_STOCK_MAX;
        if (hardMax != null && this._manualStocks.length >= hardMax) {
            StockApp.toast('已达到上限 ' + hardMax + ' 只', 'warning');
            return;
        }
        this._manualStocks.push({ tsCode: item.tsCode, code: item.code, name: item.name });
        this.renderManualCloud();
        if (this.paradigm === 'signals' && this._manualStocks.length > this.SIGNALS_MANUAL_MAX) {
            StockApp.toast('已超过 ' + this.SIGNALS_MANUAL_MAX + ' 只，保存时将由后端校验', 'warning');
        }
    },

    removeManualStock(tsCode) {
        this._manualStocks = this._manualStocks.filter(function (s) { return s.tsCode !== tsCode; });
        this.renderManualCloud();
    },

    renderManualCloud() {
        // 计数文案随范式变化（signals: /10，rebalance: /50），两套计数都刷新
        this.refreshManualCountForParadigm(this.paradigm);
        // 两套 cloud DOM 都渲染（共用同一份 this._manualStocks）
        this._renderManualCloudInto('f-stocks-cloud');
        this._renderManualCloudInto('f-pool-stocks-cloud');
    },

    _renderManualCloudInto(cloudId) {
        const cloud = document.getElementById(cloudId);
        if (!cloud) return;
        if (!this._manualStocks.length) {
            cloud.innerHTML = '<div class="manual-stocks-empty">尚未添加标的，请在上方搜索框输入代码或名称</div>';
            return;
        }
        const self = this;
        cloud.innerHTML = this._manualStocks.map(function (s) {
            const name = StockApp.escapeHtml(s.name || s.tsCode);
            const code = StockApp.escapeHtml(s.tsCode);
            const safe = s.tsCode.replace(/'/g, "\\'");
            return '<span class="stock-tag">' +
                name + ' <span class="stock-tag-code">' + code + '</span>' +
                ' <i class="bi bi-x-lg" data-rm="' + safe + '"></i>' +
                '</span>';
        }).join('');
        cloud.querySelectorAll('.bi-x-lg[data-rm]').forEach(function (el) {
            el.addEventListener('click', function () {
                self.removeManualStock(el.getAttribute('data-rm'));
            });
        });
    },

    // 回填时用 tsCode 数组补全名称（调 /search/batch，失败则用 tsCode 兜底）
    _loadManualStocksDetail(tsCodes) {
        const codes = (tsCodes || []).filter(Boolean);
        if (!codes.length) {
            this._manualStocks = [];
            this.renderManualCloud();
            return;
        }
        const self = this;
        StockApp.get('/search/batch', { tsCodes: codes.join(',') }, function (resp) {
            if (resp && resp.code === 200 && Array.isArray(resp.data)) {
                const map = {};
                resp.data.forEach(function (it) { if (it && it.tsCode) map[it.tsCode] = it; });
                self._manualStocks = codes.map(function (tc) {
                    return map[tc]
                        ? { tsCode: map[tc].tsCode, code: map[tc].code, name: map[tc].name }
                        : { tsCode: tc, code: '', name: tc };
                });
            } else {
                self._manualStocks = codes.map(function (tc) { return { tsCode: tc, code: '', name: tc }; });
            }
            self.renderManualCloud();
        });
    },

    applyRankingMethod(method) {
        const singleWrap = document.getElementById('f-ranking-single-wrap');
        const orderWrap = document.getElementById('f-ranking-order-wrap');
        const weightsWrap = document.getElementById('f-ranking-weights-wrap');
        if (singleWrap) singleWrap.style.display = method === 'single' ? '' : 'none';
        if (orderWrap) orderWrap.style.display = method === 'single' ? '' : 'none';
        if (weightsWrap) weightsWrap.style.display = method === 'composite' ? '' : 'none';
        // spec 011 P0-2：factor.method=single 时置灰 rebalance.weight_mode=score
        // （single 的 score 是因子原始值，量纲不一，加权会导致单只标的独占资金）
        this.applyScoreRadioLock(method === 'single');
        this.refreshWeightsFeedback();
    },

    /**
     * spec 011 P1-2：权重面板实时反馈——组合校验（红字）+ 归一化提示。
     * 仅在 factor.method=composite 且面板可见时有意义；其余情况清空提示。
     */
    refreshWeightsFeedback() {
        const errEl = document.getElementById('f-ranking-weights-err');
        const normEl = document.getElementById('f-ranking-weights-norm');
        const wrap = document.getElementById('f-ranking-weights-wrap');
        if (!errEl || !normEl) return;
        const visible = wrap && wrap.style.display !== 'none';
        if (!visible) {
            errEl.style.display = 'none';
            normEl.textContent = '';
            return;
        }

        // 收集当前行（factorKey, 绝对值）
        const rows = [];
        document.querySelectorAll('#f-ranking-weights [data-row="ranking-weight"]').forEach((row) => {
            const k = ((row.querySelector('[data-k="factor"]') || {}).value || '').trim();
            const v = (row.querySelector('[data-k="weight"]') || {}).value;
            const n = v === '' ? NaN : Number(v);
            rows.push({ key: k, abs: isNaN(n) ? 0 : Math.abs(n) });
        });

        const filled = rows.filter((r) => r.key);
        const errs = [];
        if (filled.length === 0) {
            errs.push('composite 模式必须至少配置一个因子权重');
        }
        const sumAbs = filled.reduce((s, r) => s + r.abs, 0);
        if (filled.length > 0 && sumAbs === 0) {
            errs.push('权重不能全为 0');
        }
        const seen = {};
        let dup = false;
        filled.forEach((r) => {
            if (seen[r.key]) dup = true;
            seen[r.key] = true;
        });
        if (dup) errs.push('同一因子不能配置多次');

        // 白名单校验（从 datalist 取，未加载则跳过）
        const datalist = document.getElementById('factor-keys-datalist');
        if (datalist && datalist.options.length) {
            const whitelist = new Set();
            for (let i = 0; i < datalist.options.length; i++) {
                whitelist.add(datalist.options[i].value);
            }
            const invalid = filled.filter((r) => r.key && !whitelist.has(r.key));
            if (invalid.length) {
                errs.push('存在不在因子白名单内的 factorKey：' + invalid.map((r) => r.key).join(', '));
            }
        }

        if (errs.length) {
            errEl.textContent = errs.join('；');
            errEl.style.display = '';
        } else {
            errEl.style.display = 'none';
        }

        // 归一化提示（按绝对值归一化）
        if (filled.length === 0 || sumAbs === 0) {
            normEl.textContent = '';
        } else {
            const parts = filled.map((r) => {
                const pct = sumAbs > 0 ? (r.abs / sumAbs) * 100 : 0;
                return r.key + ' ' + pct.toFixed(1) + '%';
            });
            normEl.textContent = '归一化后实际权重：' + parts.join(' · ') + '（按绝对值归一化）';
        }
    },

    /**
     * spec 011 P1-2：提交时权重组合校验，返回首个错误文案（无错返回 null）。
     * 与 refreshWeightsFeedback 同源规则，但用于阻断提交。
     */
    validateWeightsOnSubmit() {
        const method = this.getVal('f-ranking-method');
        if (method !== 'composite') return null;
        const weights = this.collectWeightRows();
        const keys = Object.keys(weights);
        if (keys.length === 0) return 'composite 模式必须至少配置一个因子权重';
        const sumAbs = keys.reduce((s, k) => s + Math.abs(weights[k]), 0);
        if (sumAbs === 0) return '权重不能全为 0';
        const datalist = document.getElementById('factor-keys-datalist');
        if (datalist && datalist.options.length) {
            const whitelist = new Set();
            for (let i = 0; i < datalist.options.length; i++) {
                whitelist.add(datalist.options[i].value);
            }
            const invalid = keys.filter((k) => !whitelist.has(k));
            if (invalid.length) return '存在不在因子白名单内的 factorKey：' + invalid.join(', ');
        }
        return null;
    },

    /**
     * 锁定/解锁 weight_mode=score 单选框（spec 011 P0-2）。
     * locked=true 时置灰 score radio 并回退选中到 equal；false 时恢复可选。
     */
    applyScoreRadioLock(locked) {
        const scoreRadio = document.getElementById('f-reb-weight-score');
        if (!scoreRadio) return;
        scoreRadio.disabled = !!locked;
        scoreRadio.title = locked
            ? '排序方式为 single 时不支持「按分加权」（量纲不一，会单只标的独占资金）；请改用等权或 composite 多因子'
            : '';
        if (locked && scoreRadio.checked) {
            scoreRadio.checked = false;
            const equalRadio = document.getElementById('f-reb-weight-equal');
            if (equalRadio) equalRadio.checked = true;
        }
    },

    applyFilterToggles() {
        const ind = document.getElementById('f-filter-use-industries');
        const exInd = document.getElementById('f-filter-use-exclude-industries');
        const minD = document.getElementById('f-filter-use-min-list-days');
        this.toggleWrap('f-filter-industries-wrap', ind && ind.checked);
        this.toggleWrap('f-filter-exclude-industries-wrap', exInd && exInd.checked);
        this.toggleWrap('f-filter-min-list-days-wrap', minD && minD.checked);
    },

    applyBracketToggle(on) {
        const wrap = document.getElementById('f-bracket-wrap');
        if (wrap) wrap.style.display = on ? '' : 'none';
        if (!on) {
            this.setChecked('f-use-atr-stop', false);
            this.applyAtrToggle(false);
        }
    },

    applyAtrToggle(on) {
        this.toggleWrap('f-atr-period-wrap', on);
        this.toggleWrap('f-atr-multiplier-wrap', on);
    },

    applyPositionMethod(method) {
        const targetWrap = document.getElementById('f-ps-target-wrap');
        const weightsWrap = document.getElementById('f-ps-weights-wrap');
        const hideTarget = ['buy_all', 'close_position'].includes(method);
        if (targetWrap) targetWrap.style.display = hideTarget ? 'none' : '';
        if (weightsWrap) weightsWrap.style.display = method === 'order_target_weights' ? '' : 'none';
        // label 提示
        const label = document.getElementById('f-ps-target-label');
        if (label) {
            const map = {
                order_target_percent: 'target（0~1 占比）',
                order_target_value: 'target（金额 元）',
                order_target: 'target（股数）',
                buy: 'quantity（股）',
                sell: 'quantity（股）',
            };
            label.textContent = map[method] || 'target';
        }
    },

    applyFeeToggle(on) { this.toggleWrap('f-bt-fee-wrap', on); },
    applySlippageToggle(on) { this.toggleWrap('f-bt-slippage-wrap', on); },
    applyRiskToggle(on) { this.toggleWrap('f-bt-risk-wrap', on); },

    /**
     * spec 011 P2-1：rebalance.trigger 下拉的显隐 + label 联动。
     * frequency=daily 时隐藏（每日调仓无首末之分）；其余显示，label 随 frequency 变化。
     */
    applyRebTriggerVisibility() {
        const col = document.getElementById('f-reb-trigger-col');
        const label = document.getElementById('f-reb-trigger-label');
        const freqSel = document.getElementById('f-reb-frequency');
        const freq = freqSel ? freqSel.value : 'monthly';
        const show = freq !== 'daily';
        if (col) col.style.display = show ? '' : 'none';
        if (label && show) {
            const map = {
                weekly: '调仓触发 trigger（周一 / 周五）',
                monthly: '调仓触发 trigger（月初 / 月末）',
                quarterly: '调仓触发 trigger（季初 / 季末）',
            };
            label.textContent = map[freq] || '调仓触发 trigger';
        }
    },

    toggleWrap(id, on) {
        const el = document.getElementById(id);
        if (el) el.style.display = on ? '' : 'none';
    },

    updateMaxSingleLabel() {
        // max_single_position 字段已移除（akquant rebalance_to_topn 不支持该风控参数）。
        // 保留空方法占位，避免历史调用点报错；可随调用点一并清理后删除。
    },

    /**
     * 仓位预览：根据初始资金 + 目标仓位 + 手数计算市值占用。
     */
    updatePositionPreview() {
        const cashEl = document.getElementById('f-bt-cash');
        const targetEl = document.getElementById('f-ps-target');
        const lotEl = document.getElementById('f-bt-lot-size');
        if (!cashEl || !targetEl) return;
        const cash = parseFloat(cashEl.value) || 100000;
        let target = parseFloat(targetEl.value);
        if (isNaN(target)) target = 0.95;
        // target 可能是 0.95 或 95，统一处理
        if (target > 1) target = target / 100;
        const market = cash * target;
        const lot = parseInt(lotEl && lotEl.value) || 100;
        const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
        setText('f-pp-cash', '¥' + cash.toLocaleString());
        setText('f-pp-target', (target * 100).toFixed(0) + '%');
        setText('f-pp-market', '¥' + Math.round(market).toLocaleString());
        setText('f-pp-lot', lot + '股');
    },

    // ===================== 预览 =====================
    updateJsonPreview() {
        const jsonStr = JSON.stringify(this.state, null, 2);
        const codeEl = document.querySelector('#jsonPreview code') || document.getElementById('jsonPreview');
        if (codeEl) codeEl.innerHTML = this.highlightJson(jsonStr);
        // 更新保存条字节数
        const sizeEl = document.getElementById('saveHintSize');
        if (sizeEl) sizeEl.textContent = '· config.json ' + (new Blob([jsonStr]).size / 1024).toFixed(1) + 'KB';
        const sum = document.getElementById('strategySummary');
        if (sum) sum.textContent = this.buildSummary();
    },

    /**
     * JSON 语法高亮：对 key/string/number/bool/null 套 span。
     */
    highlightJson(jsonStr) {
        return jsonStr
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/("(\\.|[^"\\])*"(\s*:)?|\b(true|false|null)\b|-?\d+\.?\d*([eE][+-]?\d+)?)\b/g, function(match) {
                if (/^"/.test(match)) {
                    if (/:$/.test(match)) return '<span class="jkey">' + match.replace(/:$/, '') + '</span>:';
                    return '<span class="jstr">' + match + '</span>';
                }
                if (/^(true|false|null)$/.test(match)) return '<span class="jbool">' + match + '</span>';
                return '<span class="jnum">' + match + '</span>';
            });
    },

    /**
     * 摘要字符串：按范式（择时/轮动）拼接自然语言。
     */
    buildSummary() {
        const s = this.state || {};
        const tc = s.trading_config || {};
        const bt = s.backtest_config || {};

        if (this.paradigm === 'rebalance') {
            const sc = this.normalizeScreenConfig(s.screen_config) || {};
            const universe = sc.universe || {};
            const factor = sc.factor || null;
            const portfolio = sc.portfolio || {};
            const rb = tc.rebalance || {};
            const rankDesc = factor ? (factor.method === 'composite' ? 'composite 排序' : ('按 ' + (factor.factor || '?') + ' ' + (factor.order || '') + ' 排序')) : '无排序';
            return ['轮动策略 · ' + (universe.pool || '未指定池'),
                portfolio.top_n != null ? ('top_n=' + portfolio.top_n) : '',
                rankDesc,
                rb.frequency ? (this.rebFreqLabel(rb.frequency) + '调仓') : '',
                rb.weight_mode ? this.weightModeLabel(rb.weight_mode) : ''].filter(Boolean).join(' · ');
        }
        // signals 范式
        const ps = tc.position_sizing || {};
        const exit = tc.exit || {};
        const buyDesc = this.summarizeSignal(tc.signals && tc.signals.buy);
        const exitDesc = this.summarizeExit(exit);
        const psDesc = ps.method ? (ps.target != null ? (ps.method + '=' + ps.target) : ps.method) : '无仓位';
        return ['择时策略', buyDesc || '无信号', psDesc, exitDesc,
            bt.initial_cash != null ? ('¥' + bt.initial_cash) : ''].filter(Boolean).join(' · ');
    },

    rebFreqLabel(code) {
        return ({ daily: '每日', weekly: '每周', monthly: '每月', quarterly: '每季' })[code] || code;
    },

    weightModeLabel(code) {
        return ({ equal: '等权', score: '按分加权' })[code] || code;
    },

    /**
     * 从条件树里取首个比较叶子的简化描述，如 "MA cross_up MA"。
     */
    summarizeSignal(tree) {
        if (!tree || this.isEmptyTree(tree)) return '';
        const cmp = this.firstCompare(tree);
        if (!cmp) return '信号';
        const lf = this.exprShort(cmp.left);
        const rf = this.exprShort(cmp.right);
        const op = cmp.comparator || '';
        return lf + ' ' + op + ' ' + rf;
    },

    firstCompare(node) {
        if (!node || typeof node !== 'object') return null;
        if (node.type === 'compare') return node;
        if (Array.isArray(node.conditions)) {
            for (const c of node.conditions) {
                const hit = this.firstCompare(c);
                if (hit) return hit;
            }
        }
        return null;
    },

    exprShort(en) {
        if (!en) return '?';
        if (en.factor) return en.factor;
        if (en.op) return '(' + this.exprShort(en.left) + ' ' + en.op + ' ' + this.exprShort(en.right) + ')';
        if (en.ref) return 'ref:' + en.ref;
        if (en.value != null) return String(en.value);
        return '?';
    },

    summarizeExit(exit) {
        if (!exit) return '';
        if (exit.bracket) {
            const b = exit.bracket;
            if (b.use_atr_stop) return 'ATR 止损';
            const sl = b.stop_loss_pct != null ? ('止损' + Math.round(b.stop_loss_pct * 100) + '%') : '';
            const tp = b.take_profit_pct != null ? ('止盈' + Math.round(b.take_profit_pct * 100) + '%') : '';
            return [sl, tp].filter(Boolean).join('/') || '括号单';
        }
        if (exit.rules && exit.rules.length) return exit.rules.length + ' 条动态规则';
        return '';
    },

    isEmptyTree(t) {
        if (!t) return true;
        if (Array.isArray(t.conditions) && t.conditions.length === 0) return true;
        return false;
    },

    // ===================== 事件绑定 =====================
    bindEvents() {
        const saveBtn = document.getElementById('saveBtn');
        if (saveBtn) saveBtn.addEventListener('click', () => this.save());

        const draftBtn = document.getElementById('saveDraftBtn');
        if (draftBtn) draftBtn.addEventListener('click', () => this.saveDraft());

        const copyBtn = document.getElementById('copyJsonBtn');
        if (copyBtn) {
            copyBtn.addEventListener('click', () => {
                const text = JSON.stringify(this.state, null, 2);
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(text).then(
                        () => StockApp.toast('已复制到剪贴板', 'success'),
                        () => StockApp.toast('复制失败', 'danger')
                    );
                } else {
                    StockApp.toast('当前浏览器不支持剪贴板 API', 'warning');
                }
            });
        }

        const editor = document.getElementById('strategyEditor');
        if (!editor) return;

        // 通用：任意表单变更 → collect → 刷新预览
        const refresh = () => {
            this.collectStateFromForm();
            this.updateJsonPreview();
            this.refreshWeightsFeedback();
        };
        editor.addEventListener('change', refresh);
        // textarea / 权重面板输入也即时刷新预览（不影响焦点）
        editor.addEventListener('input', (e) => {
            if (!e.target) return;
            if (e.target.matches('textarea')) refresh();
            // spec 011 P1-2：权重面板 factorKey/权重输入实时更新归一化与校验
            if (e.target.closest('#f-ranking-weights')) this.refreshWeightsFeedback();
        });

        // ---- 联动：各 select/checkbox/radio 的即时 UI 切换 ----
        const onUniverse = (e) => { this.applyUniverse(e.target.value); refresh(); };
        const onRanking = (e) => { this.applyRankingMethod(e.target.value); refresh(); };
        // spec 011 P0-2：weight_mode=score 切换时，若 method=single 则拦截并提示
        const onRebWeight = (e) => {
            const method = this.getVal('f-ranking-method');
            if (e.target.value === 'score' && method === 'single') {
                alert('排序方式为 single 时不支持「按分加权」（量纲不一，会单只标的独占资金）。\n请改用等权(equal)，或将排序方式改为 composite 多因子。');
                e.target.checked = false;
                const equalRadio = document.getElementById('f-reb-weight-equal');
                if (equalRadio) equalRadio.checked = true;
                return;
            }
            refresh();
        };
        const onPsMethod = (e) => { this.applyPositionMethod(e.target.value); refresh(); };
        const onBracket = (e) => { this.applyBracketToggle(e.target.checked); refresh(); };
        const onAtr = (e) => { this.applyAtrToggle(e.target.checked); refresh(); };
        const onFee = (e) => { this.applyFeeToggle(e.target.checked); refresh(); };
        const onSlip = (e) => { this.applySlippageToggle(e.target.checked); refresh(); };
        const onRisk = (e) => { this.applyRiskToggle(e.target.checked); refresh(); };
        const onRebFreq = () => { this.applyRebTriggerVisibility(); refresh(); };
        const onFilterToggle = () => { this.applyFilterToggles(); refresh(); };

        this.bindEl('f-universe', 'change', onUniverse);
        this.bindEl('f-ranking-method', 'change', onRanking);
        // spec 011 P0-2：weight_mode radio 组（name=f-reb-weight）绑定 change
        editor.querySelectorAll('input[name="f-reb-weight"]').forEach((r) => {
            r.addEventListener('change', onRebWeight);
        });
        this.bindEl('f-ps-method', 'change', onPsMethod);
        this.bindEl('f-use-bracket', 'change', onBracket);
        this.bindEl('f-use-atr-stop', 'change', onAtr);
        this.bindEl('f-bt-use-custom-fee', 'change', onFee);
        this.bindEl('f-bt-use-slippage', 'change', onSlip);
        this.bindEl('f-bt-use-risk-config', 'change', onRisk);
        this.bindEl('f-reb-frequency', 'change', onRebFreq);
        ['f-filter-use-industries', 'f-filter-use-exclude-industries', 'f-filter-use-min-list-days']
            .forEach((id) => this.bindEl(id, 'change', onFilterToggle));

        // spec 011 迭代 4：单标的/行业暴露上限启用开关
        const onOptionalNumber = (enableId, inputId) => () => {
            const input = document.getElementById(inputId);
            if (input) input.disabled = !this.getChecked(enableId);
        };
        this.bindEl('f-max-weight-enable', 'change', onOptionalNumber('f-max-weight-enable', 'f-max-weight'));
        this.bindEl('f-max-industry-enable', 'change', onOptionalNumber('f-max-industry-enable', 'f-max-industry'));

        // ---- 动态列表：增删行 ----
        editor.addEventListener('click', (e) => {
            const addBtn = e.target.closest('[data-add]');
            const delBtn = e.target.closest('[data-del]');
            if (addBtn) {
                const type = addBtn.getAttribute('data-add');
                if (type === 'ranking-weight') this.addRow('f-ranking-weights', this.weightRowHtml('', 1, 0));
                else if (type === 'ps-weight') this.addRow('f-ps-weights', this.psWeightRowHtml('', 0));
                else if (type === 'exit-rule') this.addExitRuleRow();
            }
            if (delBtn) {
                const type = delBtn.getAttribute('data-del');
                const row = delBtn.closest('[data-row="' + type + '"]');
                if (row) {
                    // exit-rule 删除前销毁对应 builder + stash，避免内存泄漏
                    if (type === 'exit-rule') {
                        const uid = row.getAttribute('data-uid');
                        const targetId = row.getAttribute('data-target');
                        if (targetId && this._condBuilders[targetId]) {
                            try { this._condBuilders[targetId].destroy(); } catch (e) { /* noop */ }
                            delete this._condBuilders[targetId];
                        }
                        if (uid && this._exitCondStash) delete this._exitCondStash[uid];
                    }
                    row.remove();
                }
                refresh();
            }
        });

        // ---- spec 011 P1-2：权重行方向切换（↑越大越好 / ↓越小越好）----
        editor.addEventListener('click', (e) => {
            const dirBtn = e.target.closest('#f-ranking-weights [data-k="direction"]');
            if (!dirBtn) return;
            const cur = dirBtn.getAttribute('data-direction') || '+';
            const next = cur === '+' ? '-' : '+';
            dirBtn.setAttribute('data-direction', next);
            if (next === '+') {
                dirBtn.textContent = '↑ 越大越好';
                dirBtn.classList.remove('btn-outline-danger');
                dirBtn.classList.add('btn-outline-success');
            } else {
                dirBtn.textContent = '↓ 越小越好';
                dirBtn.classList.remove('btn-outline-success');
                dirBtn.classList.add('btn-outline-danger');
            }
            this.refreshWeightsFeedback();
        });

        // ---- 因子下拉：点击在对应 textarea 光标处插入 ----
        editor.addEventListener('click', (e) => {
            const item = e.target.closest('.factors-menu .dropdown-item');
            if (!item) return;
            e.preventDefault();
            const menu = item.closest('.factors-menu');
            const targetId = menu && menu.getAttribute('data-target');
            if (!targetId) return;
            const ta = document.getElementById(targetId);
            if (!ta) return;
            const snippet = '{"factor":"' + item.getAttribute('data-factor') + '"}';
            this.insertAtCursor(ta, snippet);
            refresh();
        });

        // ---- 条件树模式切换器：visual / json / ai ----
        editor.addEventListener('click', (e) => {
            const btn = e.target.closest('.cond-mode-btn');
            if (!btn) return;
            if (btn.disabled) return;
            const switchEl = btn.closest('.cond-mode-switch');
            if (!switchEl) return;
            const targetId = switchEl.getAttribute('data-cond-target');
            const mode = btn.getAttribute('data-mode');
            if (!targetId || !mode) return;
            e.preventDefault();
            // 已是当前模式则不重复切换
            if ((this._condMode[targetId] || 'visual') === mode) return;
            // spec 011 P2-6：切换前确认（切换会同步当前编辑内容）
            if (!window.confirm('切换模式将同步当前编辑内容，是否继续？')) {
                // 用户取消：保持当前模式高亮
                this.applyCondModeUI(targetId, this._condMode[targetId] || 'visual');
                return;
            }
            const ok = this.switchCondMode(targetId, mode);
            if (!ok) {
                // 切换失败（如 JSON 解析错误）：按钮恢复到当前模式的高亮
                this.applyCondModeUI(targetId, this._condMode[targetId] || 'visual');
            }
        });

        // ---- JSON 模式下 textarea 失焦：校验 + 回写 state（不切模式）----
        editor.addEventListener('focusout', (e) => {
            if (this._syncing) return;
            const ta = e.target;
            if (!ta || ta.tagName !== 'TEXTAREA') return;
            const targetId = ta.id;
            // 仅对条件树区域（注册过 _condMeta）且当前是 json 模式的处理
            if (!targetId || !this._condMeta[targetId]) return;
            if ((this._condMode[targetId] || 'visual') !== 'json') return;
            const str = (ta.value || '').trim();
            if (!str) return;
            try {
                const parsed = JSON.parse(str);
                // 写回 state 或 stash
                const meta = this._condMeta[targetId];
                if (meta.statePath) {
                    this.setCondByPath(meta.statePath, parsed);
                } else {
                    // exit rule：从 DOM 行取 uid 回写 stash
                    const row = ta.closest('[data-row="exit-rule"]');
                    const uid = row && row.getAttribute('data-uid');
                    if (uid) {
                        this._exitCondStash = this._exitCondStash || {};
                        this._exitCondStash[uid] = parsed;
                    }
                }
                ta.classList.remove('is-invalid');
                this.updateJsonPreview();
            } catch (err) {
                ta.classList.add('is-invalid');
                StockApp.toast('JSON 格式错误：' + err.message + '（已保留输入，请修正后再切换或保存）', 'warning');
            }
        });
    },

    bindEl(id, evt, handler) {
        const el = document.getElementById(id);
        if (el) el.addEventListener(evt, handler);
    },

    addRow(containerId, html) {
        const c = document.getElementById(containerId);
        if (c) c.insertAdjacentHTML('beforeend', html);
    },

    addExitRuleRow() {
        const c = document.getElementById('f-exit-rules');
        if (!c) return;
        c.insertAdjacentHTML('beforeend', this.exitRuleRowHtml({ name: '', condition: null, action: 'close_position' }, 0));
        // 挂载新行的 builder（新行是最后一个 [data-row="exit-rule"]）
        const rows = c.querySelectorAll('[data-row="exit-rule"]');
        this.initExitRowBuilder(rows.length - 1, null);
    },

    insertAtCursor(ta, text) {
        const start = ta.selectionStart;
        const end = ta.selectionEnd;
        const val = ta.value;
        ta.value = val.slice(0, start) + text + val.slice(end);
        ta.selectionStart = ta.selectionEnd = start + text.length;
        ta.dispatchEvent(new Event('input', { bubbles: true }));
    },

    // ===================== 保存（两个分支） =====================
    /**
     * 保存草稿：复用 save()，但标记 _draftMode，保存成功后尝试把 status 设回 DRAFT。
     * 新建模式因尚无 strategyId，仅提示（草稿语义由后端保存结果决定）。
     */
    async saveDraft() {
        const draftBtn = document.getElementById('saveDraftBtn');
        if (draftBtn) { draftBtn.disabled = true; draftBtn.textContent = '保存中…'; }
        try {
            this._draftMode = true;
            await this.save();
        } finally {
            this._draftMode = false;
            if (draftBtn) { draftBtn.disabled = false; draftBtn.textContent = '保存草稿'; }
        }
    },

    async save() {
        this.clearErrorTabs();
        this.collectStateFromForm();

        // 前端预校验 1：name 非空
        if (!this.state.name || !String(this.state.name).trim()) {
            StockApp.toast('策略名不能为空', 'danger');
            this.activateTab('tab-basic');
            this.markErrorTabs([{ path: 'name', code: 'REQUIRED', message: '策略名不能为空' }]);
            return;
        }

        // 前端预校验 2：所有条件 JSON 必须解析成功（FR-8）
        if (this._parseErrors && this._parseErrors.length) {
            const first = this._parseErrors[0];
            this.activateTab(first.tab);
            this._parseErrors.forEach((e) => this.markErrorTabs([{ path: e.field, code: 'JSON_PARSE', message: e.message }]));
            StockApp.toast('条件 JSON 格式错误：' + first.message, 'danger');
            return;
        }

        // 前端预校验 3：spec 011 P1-2 composite 权重组合校验
        const weightErr = this.validateWeightsOnSubmit();
        if (weightErr) {
            this.activateTab('tab-screen');
            StockApp.toast(weightErr, 'danger');
            return;
        }

        const configJson = JSON.stringify(this.cleanState(this.state));
        const saveBtn = document.getElementById('saveBtn');
        this.setLoading(saveBtn, true);

        try {
            if (this.mode === 'edit') {
                await this.saveEdit(configJson);
            } else {
                await this.saveCreate(configJson);
            }
        } catch (err) {
            StockApp.toast('保存失败：' + (err && err.message ? err.message : err), 'danger');
        } finally {
            this.setLoading(saveBtn, false);
        }
    },

    /**
     * 序列化前清理：剔除 engine models.py 中 extra="forbid" 不接受的字段。
     * 注：backtest_config.benchmark 已由 007 spec 在 BacktestConfigModel 声明，不再剔除。
     * 去掉空字典/空数组的可选字段，避免无意义传输。
     */
    cleanState(s) {
        const out = JSON.parse(JSON.stringify(s));
        return out;
    },

    /**
     * 编辑分支：PUT /api/strategies/{id}/config
     * body：{configJson, changelog, expectedVersion}
     * 成功 → toast + 跳转版本历史页；
     * code=400 且含 errors → 标红 Tab + toast；
     * code=409 → 版本冲突提示刷新。
     */
    saveEdit(configJson) {
        return new Promise((resolve, reject) => {
            StockApp.post('/api/strategies/' + encodeURIComponent(this.strategyId) + '/config', {
                configJson: configJson,
                changelog: '编辑器保存',
                expectedVersion: this.expectedVersion,
            }, (resp) => {
                if (resp.code === 200) {
                    StockApp.toast('配置已保存为新版本', 'success');
                    this.hideValidationErrorCard();
                    // 草稿模式：尝试把 status 设回 DRAFT
                    const afterDraft = () => {
                        setTimeout(() => {
                            location.href = StockApp.contextPath + '/quant/strategies/' + encodeURIComponent(this.strategyId) + '/versions';
                        }, 600);
                    };
                    if (this._draftMode && this.strategyId) {
                        fetch(StockApp.contextPath + '/api/strategies/' + encodeURIComponent(this.strategyId) + '/status', {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ status: 'DRAFT' }),
                        }).then(afterDraft).catch(afterDraft);
                    } else {
                        afterDraft();
                    }
                    resolve();
                } else if (resp.code === 409) {
                    StockApp.toast('版本冲突：策略已被他人修改，请刷新后重试', 'warning');
                    reject(new Error('版本冲突'));
                } else if (resp.code === 400 && Array.isArray(resp.data)) {
                    // engine 校验失败：data 为 [{path, code, message}, ...]
                    this.markErrorTabs(resp.data);
                    const first = resp.data[0];
                    if (first) this.activateTabByPath(first.path);
                    StockApp.toast('校验失败：' + (first ? first.message : '请检查标红的 Tab'), 'danger');
                    reject(new Error('校验失败'));
                } else {
                    StockApp.toast(resp.message || '保存失败', 'danger');
                    reject(new Error(resp.message || '保存失败'));
                }
            });
        });
    },

    /**
     * 新建 / 模板分支：POST /api/strategies
     * body：{name, description, category, scope, tags, configJson, changelog}
     * 成功 → toast + 跳转新策略版本历史页。
     */
    saveCreate(configJson) {
        return new Promise((resolve, reject) => {
            const body = {
                name: (this.state.name || '').trim(),
                description: this.state.description || '',
                category: this.meta.category || '',
                scope: this.state.scope || 'single',
                tags: this.meta.tags || [],
                configJson: configJson,
                changelog: this.mode === 'template' ? '从模板创建' : '编辑器新建',
            };
            StockApp.post('/api/strategies', body, (resp) => {
                if (resp.code === 200 && resp.data && resp.data.strategyId) {
                    StockApp.toast('策略已创建', 'success');
                    this.hideValidationErrorCard();
                    const newId = resp.data.strategyId;
                    setTimeout(() => {
                        location.href = StockApp.contextPath + '/quant/strategies/' + encodeURIComponent(newId) + '/versions';
                    }, 600);
                    resolve();
                } else if (resp.code === 400 && Array.isArray(resp.data)) {
                    this.markErrorTabs(resp.data);
                    const first = resp.data[0];
                    if (first) this.activateTabByPath(first.path);
                    StockApp.toast('校验失败：' + (first ? first.message : '请检查标红的 Tab'), 'danger');
                    reject(new Error('校验失败'));
                } else {
                    StockApp.toast(resp.message || '创建失败', 'danger');
                    reject(new Error(resp.message || '创建失败'));
                }
            });
        });
    },

    // ===================== 错误 Tab 标红 =====================
    /**
     * 按 errors[].path 前缀给对应 tab-nav 加 .text-danger。
     * 同时把 errors 渲染到 #validationErrCard。
     * @param errors [{path, code, message}]
     */
    markErrorTabs(errors) {
        this.clearErrorTabs();
        if (!Array.isArray(errors)) return;
        errors.forEach((e) => {
            const hit = this.ERROR_TAB_MAP.find(m => e.path && e.path.startsWith(m.prefix));
            if (hit) {
                const nav = document.querySelector('#strategyTab [data-bs-target="#' + hit.tab + '"]');
                if (nav) nav.classList.add('text-danger');
            }
        });
        // 渲染校验错误卡
        const errCard = document.getElementById('validationErrCard');
        const errList = document.getElementById('validationErrList');
        const errTitle = document.getElementById('validationErrTitle');
        if (errCard && errList && errors.length) {
            if (errTitle) errTitle.textContent = errors.length + ' 个校验错误';
            errList.innerHTML = errors.map((err) => {
                const path = err.path || err.field || '';
                const msg = err.message || err.msg || String(err);
                return '<div class="str-err-item"><span class="epath">' + StockApp.escapeHtml(path) + '</span>' + StockApp.escapeHtml(msg) + '</div>';
            }).join('');
            errCard.style.display = 'block';
        } else if (errCard) {
            errCard.style.display = 'none';
        }
    },

    clearErrorTabs() {
        document.querySelectorAll('#strategyTab .str-tab-btn').forEach((n) => n.classList.remove('text-danger'));
    },

    /**
     * 隐藏校验错误卡（保存成功后调用）。
     */
    hideValidationErrorCard() {
        const errCard = document.getElementById('validationErrCard');
        if (errCard) errCard.style.display = 'none';
    },

    activateTab(tabId) {
        const nav = document.querySelector('#strategyTab [data-bs-target="#' + tabId + '"]');
        if (nav) nav.click();
    },

    activateTabByPath(path) {
        const hit = this.ERROR_TAB_MAP.find(m => path && path.startsWith(m.prefix));
        if (hit) this.activateTab(hit.tab);
    },

    // ===================== DOM 取值工具 =====================
    getVal(id) { const el = document.getElementById(id); return el ? el.value : ''; },
    getRaw(id) { const el = document.getElementById(id); return el ? el.value : ''; },
    getNum(id) {
        const v = this.getVal(id);
        if (v === '' || v == null) return null;
        const n = Number(v);
        return isNaN(n) ? null : n;
    },
    getChecked(id) { const el = document.getElementById(id); return !!(el && el.checked); },
    setVal(id, v) { const el = document.getElementById(id); if (el) el.value = v == null ? '' : v; },
    setChecked(id, v) { const el = document.getElementById(id); if (el) el.checked = !!v; },
    getRadio(name) {
        const checked = document.querySelector('input[name="' + name + '"]:checked');
        return checked ? checked.value : '';
    },
    checkRadio(name, value) {
        const el = document.querySelector('input[name="' + name + '"][value="' + value + '"]');
        if (el) el.checked = true;
    },
    getInt(id) {
        const n = this.getNum(id);
        if (n == null) return null;
        return Math.trunc(n);
    },
    /**
     * spec 011 迭代 4：带启用开关的数值字段回填。
     * 值非空 → 勾选启用开关 + 填值；值为空 → 取消勾选 + 清空。
     */
    applyOptionalNumber(enableId, inputId, value) {
        const hasValue = value != null && value !== '';
        this.setChecked(enableId, hasValue);
        this.setVal(inputId, hasValue ? value : '');
        const input = document.getElementById(inputId);
        if (input) input.disabled = !hasValue;
    },
    /**
     * spec 011 迭代 4：带启用开关的数值字段收集。
     * 开关未勾选 → null（不写入配置）；勾选但值空 → null；否则返回数值。
     */
    getOptionalNumber(enableId, inputId) {
        if (!this.getChecked(enableId)) return null;
        return this.getNum(inputId);
    },

    /**
     * 把条件树 JSON 对象回填到 textarea（pretty print）。
     * null/空树填空字符串，让用户从 placeholder 起步。
     */
    setJson(id, obj) {
        const el = document.getElementById(id);
        if (!el) return;
        if (!obj || this.isEmptyTree(obj)) {
            el.value = '';
        } else {
            el.value = JSON.stringify(obj, null, 2);
        }
    },

    /**
     * 解析条件 textarea。空串视为有效（返回 null）。
     * 解析失败记入 this._parseErrors 并返回 null。
     *
     * 可视化模式（_condMode[targetId]==='visual'）下，textarea 是隐藏的、内容可能不是最新，
     * 此时跳过 textarea 解析，直接从 state 按 statePath 取树（builder 的 onChange 已实时维护）���
     */
    parseJsonField(id, tab, fieldPath) {
        const el = document.getElementById(id);
        if (!el) return null;

        // 可视化模式：state 已由 ConditionTreeBuilder 实时维护，直接取，不碰 textarea
        if (this._condMode[id] === 'visual') {
            const meta = this._condMeta[id];
            if (meta && meta.statePath) {
                const tree = this.getCondByPath(meta.statePath);
                return (tree && !this.isEmptyTree(tree)) ? tree : null;
            }
        }

        const str = (el.value || '').trim();
        if (!str) return null;
        try {
            return JSON.parse(str);
        } catch (e) {
            this._parseErrors = this._parseErrors || [];
            this._parseErrors.push({
                tab: tab,
                field: fieldPath,
                message: e.message,
            });
            el.classList.add('is-invalid');
            return null;
        }
    },

    // ===================== 通用工具 =====================
    setLoading(btn, loading) {
        if (!btn) return;
        if (loading) {
            btn.disabled = true;
            btn.dataset.originHtml = btn.innerHTML;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>保存中…';
        } else {
            btn.disabled = false;
            if (btn.dataset.originHtml) btn.innerHTML = btn.dataset.originHtml;
        }
    },

    clone(obj) {
        return JSON.parse(JSON.stringify(obj));
    },

    /**
     * 把加载到的 state 与 DEFAULT_CONFIG 做顶层字段补齐，避免老配置缺字段导致后续控件异常。
     * 仅做浅层关键字段保证（trading_config / backtest_config 内部结构由 engine 校验兜底）。
     */
    mergeDefaults(loaded) {
        const d = this.clone(this.DEFAULT_CONFIG);
        const out = Object.assign({}, d, loaded || {});
        if (loaded && loaded.trading_config) {
            out.trading_config = Object.assign({}, d.trading_config, loaded.trading_config);
        }
        if (loaded && loaded.backtest_config) {
            out.backtest_config = Object.assign({}, d.backtest_config, loaded.backtest_config);
        }
        // screen_config 归一为 4 层（universe/factor/filter/portfolio），兼容存量旧 5 字段。
        // 这样 COND_AREAS 的 statePath 'screen_config.filter.conditions' 能稳定取到值。
        if (loaded && loaded.screen_config != null) {
            out.screen_config = this.normalizeScreenConfig(loaded.screen_config);
        }
        return out;
    },
};

document.addEventListener('DOMContentLoaded', () => StrategyEditor.init());
