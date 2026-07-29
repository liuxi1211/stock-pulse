/**
 * Stock Detail Page —— 个股诊断
 *
 * 实现 spec 024 Task 9-13：
 *  - Task 9  顶部摘要（最新价/涨跌幅/市值/PE/PB 等）+ 自选切换 + 价格提醒
 *  - Task 10 K 线技术面 Tab（KlineRenderer + 周期/复权/指标切换 + 信号时间轴）
 *  - Task 11 基本面 Tab（估值百分位 / 财务趋势 / 三大报表 / 业绩事件 / 股东人数）
 *  - Task 12 资金面 Tab（主力净流入 / 北向持股 / 龙虎榜 / 大宗交易）
 *  - Task 13 风险面 Tab（涨跌停 / 历史停牌 / ST 历史 / 股东增减持）
 *
 * 依赖（页面已注入）：
 *  - StockApp（common.js）：get/post/toast/escapeHtml/confirm
 *  - ChartsTheme（charts-theme.js）：getEChartsTheme/register/getChartColors/getAreaGradient
 *  - KlineRenderer（charts/kline-renderer.js）：K 线组件
 *  - echarts / LightweightCharts / bootstrap 全局可用
 *
 * 后端接口契约（已核对 Controller + DTO）：
 *  - GET  /stocks/{code}/kline?period=D&adj=QFQ&limit=250         → {items:[{date,open,high,low,close,volume}], meta}
 *  - GET  /stocks/{code}/daily-basics?limit=N                      → {items:[{tsCode,tradeDate,close,pe,peTtm,pb,ps,totalMv,circMv,turnoverRate,...}], meta}
 *  - GET  /stocks/{code}/fina-indicators?limit=20                  → {items:[{endDate,roe,roa,grossprofitMargin,netprofitMargin,...}], meta}
 *  - GET  /stocks/{code}/incomes?limit=4                            → {items:[{endDate,nIncome,totalRevenue,nIncomeAttrP,nIncomeYoy,...}], meta}
 *  - GET  /stocks/{code}/balancesheets?limit=4                      → {items:[{endDate,totalAssets,totalLiab,totalEquity,...}], meta}
 *  - GET  /stocks/{code}/cashflows?limit=4                          → {items:[{endDate,nCashflowAct,nCashflowInvAct,nCashFlowsFncAct,freeCashflow,...}], meta}
 *  - GET  /stocks/{code}/forecasts?limit=10                         → {items:[{annDate,endDate,type,pChangeMin,pChangeMax,summary,...}], meta}
 *  - GET  /stocks/{code}/expresses?limit=10                         → {items:[{annDate,endDate,revenue,nIncome,basicEps,...}], meta}
 *  - GET  /stocks/{code}/dividends?limit=10                         → {items:[{annDate,endDate,cashDiv,stkDiv,divProc,...}], meta}
 *  - GET  /stocks/{code}/limits?limit=5                             → {items:[{tsCode,tradeDate,preClose,upLimit,downLimit}], meta}
 *  - GET  /stocks/{code}/suspends?limit=100                         → {items:[{tsCode,tradeDate,suspendTiming,suspendType}], meta}
 *  - GET  /stocks/{code}/namechanges?limit=50                       → {items:[{tsCode,name,startDate,endDate,changeReason}], meta}
 *  - GET  /stocks/{code}/holder-trades                              → {items:[{annDate,holderName,holderType,inDe,changeVol,changeRatio,afterShare,afterRatio,...}], meta}
 *  - GET  /stocks/{code}/moneyflows?days=30                         → {items:[MoneyflowDO 字段，金额单位=万元], meta}
 *  - GET  /stocks/{code}/hk-holds?range=3M|1Y|ALL                   → {items:[{tradeDate,code,name,vol,ratio,tsCode,exchangeId}], meta}
 *  - GET  /stocks/{code}/top-lists?limit=100                        → {items:[TopListDO 字段，金额单位=元], meta}
 *  - GET  /stocks/{code}/top-lists/{tradeDate}/seats                → {items:[TopInstDO 字段], meta}
 *  - GET  /stocks/{code}/block-trades?page=1&size=20                → {items:[{tradeDate,tsCode,name,price,vol,amount,buyer,seller,closePrice,premiumRate}], meta:{total,...}}
 *  - GET  /api/stk-holdernumber?tsCode={code}&limit=20              → [StkHoldernumberDO 字段]   (注意：直接是数组，非 {items,meta})
 *  - GET  /watchlist                                                  → [WatchlistItemVO 字段]
 *  - POST /watchlist/{code} | /watchlist/{code}/delete
 *  - POST /watchlist/{code}/reminder body {targetPriceHigh,targetPriceLow} | DELETE /watchlist/{code}/reminder
 */
(function () {
    'use strict';

    // ==================== 全局状态 ====================

    var DEFAULT_TAB = 'kline';
    var VALID_TABS = ['kline', 'fundamental', 'moneyflow', 'risk'];

    var state = {
        stockCode: '',
        activeTab: DEFAULT_TAB,
        // 每个 Tab 的加载状态：idle/loading/loaded/error
        tabs: {
            kline: 'idle',
            fundamental: 'idle',
            moneyflow: 'idle',
            risk: 'idle'
        },
        // K 线相关
        kline: {
            period: 'D',
            adj: 'QFQ',
            mainIndicator: 'ma',
            subIndicator: 'macd',
            renderer: null,
            reqId: 0,
            rawItems: []
        },
        // 北向持股范围
        hkRange: '3M',
        // 已注册的 ECharts 实例（卡片级，便于 dispose/resize）
        charts: {},
        // 卡片加载状态（按 data-card 名索引）
        cards: {},
        // 自选状态
        inWatchlist: false,
        watchlistItem: null,
        // 龙虎榜席位缓存：key = tradeDate+'|'+tsCode
        seatsCache: {}
    };

    var e = StockApp.escapeHtml;

    // ==================== 工具方法 ====================

    function num(v) {
        if (v === null || v === undefined || v === '') return null;
        var n = Number(v);
        return isNaN(n) ? null : n;
    }

    /** 金额格式化：v 单位为"元"。>1亿显示亿，>1万显示万，否则原值 */
    function fmtAmount(v) {
        var n = num(v);
        if (n === null) return '--';
        var abs = Math.abs(n);
        if (abs >= 100000000) return (n / 100000000).toFixed(2) + '亿';
        if (abs >= 10000) return (n / 10000).toFixed(2) + '万';
        return n.toFixed(2);
    }

    /** 万元单位金额格式化：v 单位为"万元"。>10000万(=1亿) 显示亿，否则 万 */
    function fmtAmountWan(v) {
        var n = num(v);
        if (n === null) return '--';
        var abs = Math.abs(n);
        if (abs >= 10000) return (n / 10000).toFixed(2) + '亿';
        return n.toFixed(2) + '万';
    }

    /** 总市值格式化：v 单位=万元。>10000万显示亿，>100000000万(=1万亿) 显示万亿 */
    function fmtMarketCap(v) {
        var n = num(v);
        if (n === null) return '--';
        if (Math.abs(n) >= 100000000) return (n / 100000000).toFixed(2) + '万亿';
        if (Math.abs(n) >= 10000) return (n / 10000).toFixed(2) + '亿';
        return n.toFixed(2) + '万';
    }

    function fmtNumber(v, decimals) {
        var n = num(v);
        if (n === null) return '--';
        var d = decimals != null ? decimals : 2;
        return n.toFixed(d);
    }

    function fmtPercent(v, decimals, withSign) {
        var n = num(v);
        if (n === null) return '--';
        var d = decimals != null ? decimals : 2;
        var sign = withSign && n > 0 ? '+' : '';
        return sign + n.toFixed(d) + '%';
    }

    /** yyyyMMdd → yyyy-MM-dd；不合法原样返回 */
    function fmtDate(s) {
        if (!s) return '--';
        s = String(s);
        if (/^\d{8}$/.test(s)) return s.substring(0, 4) + '-' + s.substring(4, 6) + '-' + s.substring(6, 8);
        return s;
    }

    function fmtVolume(v) {
        var n = num(v);
        if (n === null) return '--';
        var abs = Math.abs(n);
        if (abs >= 100000000) return (n / 100000000).toFixed(2) + '亿';
        if (abs >= 10000) return (n / 10000).toFixed(2) + '万';
        return n.toFixed(0);
    }

    function riseFallClass(v) {
        var n = num(v);
        if (n === null || n === 0) return '';
        return n > 0 ? 'sd-rise' : 'sd-fall';
    }

    function todayYmd() {
        var d = new Date();
        return d.getFullYear()
            + String(d.getMonth() + 1).padStart(2, '0')
            + String(d.getDate()).padStart(2, '0');
    }

    function getTheme() {
        return ChartsTheme.getEChartsTheme();
    }

    function getChartColors() {
        return ChartsTheme.getChartColors();
    }

    function riseColor() {
        return getComputedStyle(document.documentElement).getPropertyValue('--rise-color').trim() || '#ef4444';
    }

    function fallColor() {
        return getComputedStyle(document.documentElement).getPropertyValue('--fall-color').trim() || '#10b981';
    }

    /** 创建或复用一个 ECharts 实例，并注册到主题管理器 */
    function ensureChart(key, container) {
        if (!container) return null;
        if (state.charts[key]) {
            try { state.charts[key].resize(); return state.charts[key]; } catch (err) {}
        }
        var chart = echarts.init(container);
        ChartsTheme.register(chart, 'echarts');
        state.charts[key] = chart;
        return chart;
    }

    function disposeChart(key) {
        if (state.charts[key]) {
            try { state.charts[key].dispose(); } catch (err) {}
            delete state.charts[key];
        }
    }

    function showChartEmpty(chart) {
        if (!chart) return;
        var theme = getTheme();
        chart.clear();
        chart.setOption({
            title: {
                text: '暂无数据', left: 'center', top: 'center',
                textStyle: { color: (theme.textStyle || {}).color || '#94a3b8', fontSize: 13 }
            }
        });
    }

    /** 空表格行 */
    function emptyRow(cols, msg) {
        return '<tr><td colspan="' + cols + '" class="text-center text-muted py-3">' + e(msg || '暂无数据') + '</td></tr>';
    }

    // ==================== 卡片三态切换 ====================

    /**
     * 控制某个卡片/分区内的 idle / empty / error 三态显隐。
     * @param {HTMLElement} root - 卡片根元素（article.sd-section-card）或 Tab panel
     * @param {string} status - idle / loading / loaded / empty / error
     * @param {string} [loadingText] - loading 时 idle 区标题
     */
    function setSectionView(root, status, loadingText) {
        if (!root) return;
        var idleView = root.querySelector('[data-section-state]');
        var emptyView = root.querySelector('.sd-section-empty');
        var errorView = root.querySelector('.sd-section-error');
        var body = root.querySelector('[data-card-body]');

        // loaded/empty 时内容区可见，idle/loading/error 时隐藏（让状态层盖住）
        if (body) body.hidden = (status === 'idle' || status === 'loading' || status === 'error');

        if (idleView) {
            var showIdle = (status === 'idle' || status === 'loading');
            idleView.hidden = !showIdle;
            idleView.dataset.sectionState = status;
            var title = idleView.querySelector('strong');
            if (title) title.textContent = loadingText || (status === 'loading' ? '正在加载' : '等待加载');
        }
        if (emptyView) emptyView.hidden = (status !== 'empty');
        if (errorView) errorView.hidden = (status !== 'error');
    }

    /** 拿到卡片元素 */
    function getCard(name) {
        return document.querySelector('[data-card="' + name + '"]');
    }

    /** 标记卡片状态并切换视图 */
    function setCardStatus(name, status, loadingText) {
        state.cards[name] = status;
        setSectionView(getCard(name), status, loadingText);
    }

    // ==================== Tab 调度 ====================

    function normalizeTab(value) {
        var v = String(value || '').trim().toLowerCase();
        return VALID_TABS.indexOf(v) >= 0 ? v : DEFAULT_TAB;
    }

    function getRequestedTab() {
        return normalizeTab(new URLSearchParams(window.location.search).get('tab'));
    }

    function getTabButton(tab) {
        return document.querySelector('[data-tab="' + tab + '"]');
    }

    function getTabPanel(tab) {
        return document.querySelector('[data-panel="' + tab + '"]');
    }

    function setTabState(tab, status) {
        if (VALID_TABS.indexOf(tab) < 0) return;
        state.tabs[tab] = status;
        var panel = getTabPanel(tab);
        if (panel) {
            panel.dataset.loadState = status;
            // Tab 级别的 K 线卡片状态由 loadKlineTab 自管；其余 Tab 用卡片级状态
        }
    }

    function loadTab(tab) {
        // 只在 idle 或 error（重试）时触发
        if (state.tabs[tab] !== 'idle' && state.tabs[tab] !== 'error') return;
        setTabState(tab, 'loading');
        switch (tab) {
            case 'kline': loadKlineTab(); break;
            case 'fundamental': loadFundamentalTab(); break;
            case 'moneyflow': loadMoneyflowTab(); break;
            case 'risk': loadRiskTab(); break;
        }
    }

    function activateTab(tab, updateUrl) {
        var normalized = normalizeTab(tab);
        state.activeTab = normalized;

        VALID_TABS.forEach(function (name) {
            var button = getTabButton(name);
            var panel = getTabPanel(name);
            var isActive = name === normalized;
            if (button) {
                button.classList.toggle('is-active', isActive);
                button.setAttribute('aria-selected', String(isActive));
                button.tabIndex = isActive ? 0 : -1;
            }
            if (panel) {
                panel.classList.toggle('is-active', isActive);
                panel.hidden = !isActive;
            }
        });

        if (updateUrl) {
            var url = new URL(window.location.href);
            if (normalized === DEFAULT_TAB) url.searchParams.delete('tab');
            else url.searchParams.set('tab', normalized);
            window.history.replaceState(null, '', url);
        }

        // Tab 切换后 resize 当前 Tab 的图表
        setTimeout(function () {
            Object.keys(state.charts).forEach(function (key) {
                if (key.indexOf(normalized + ':') === 0) {
                    try { state.charts[key].resize(); } catch (err) {}
                }
            });
        }, 60);

        loadTab(normalized);
    }

    function bindTabEvents() {
        var buttons = Array.from(document.querySelectorAll('[data-tab]'));
        buttons.forEach(function (button, index) {
            button.addEventListener('click', function () {
                activateTab(button.dataset.tab, true);
            });
            button.addEventListener('keydown', function (event) {
                if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
                event.preventDefault();
                var offset = event.key === 'ArrowRight' ? 1 : -1;
                var nextIndex = (index + offset + buttons.length) % buttons.length;
                var nextButton = buttons[nextIndex];
                activateTab(nextButton.dataset.tab, true);
                nextButton.focus();
            });
        });

        // 卡片级重试按钮
        document.querySelectorAll('[data-section-retry]').forEach(function (button) {
            button.addEventListener('click', function () {
                var card = button.closest('[data-card]');
                var panel = button.closest('[data-panel]');
                if (card && card.dataset.card) {
                    // 单卡片重试：只重载该卡片
                    retryCard(card.dataset.card);
                    return;
                }
                if (panel && panel.dataset.panel) {
                    // Tab 级重试（K 线）
                    setTabState(panel.dataset.panel, 'idle');
                    loadTab(panel.dataset.panel);
                }
            });
        });
    }

    /** 单卡片重试调度 */
    function retryCard(name) {
        switch (name) {
            case 'valuation': loadValuationCard(); break;
            case 'trend': loadTrendCard(); break;
            case 'statements': loadStatementsCard(); break;
            case 'events': loadEventsCard(); break;
            case 'holders': loadHoldersCard(); break;
            case 'mainflow': loadMainflowCard(); break;
            case 'hkhold': loadHkholdCard(); break;
            case 'toplist': loadToplistCard(); break;
            case 'blocktrade': loadBlocktradeCard(); break;
            case 'limit': loadLimitCard(); break;
            case 'suspend': loadSuspendCard(); break;
            case 'st': loadStCard(); break;
            case 'holdertrade': loadHoldertradeCard(); break;
        }
    }

    // ==================== Task 9：顶部摘要 ====================

    function setMetric(field, text, cls) {
        var el = document.querySelector('#sdSummaryMetrics [data-field="' + field + '"]');
        if (!el) return;
        el.textContent = text;
        el.classList.remove('sd-rise', 'sd-fall');
        if (cls) el.classList.add(cls);
    }

    function loadSummary() {
        var code = state.stockCode;

        // 并发：K 线最近 2 根 + 每日基本面最近 1 条 + 自选列表
        Promise.all([
            fetchJson('/stocks/' + encodeURIComponent(code) + '/kline?period=D&adj=QFQ&limit=2'),
            fetchJson('/stocks/' + encodeURIComponent(code) + '/daily-basics?limit=1'),
            fetchJson('/watchlist')
        ]).then(function (results) {
            var klineResp = results[0];
            var basicsResp = results[1];
            var watchResp = results[2];

            // —— 价格与涨跌 ——
            var items = (klineResp && klineResp.data && klineResp.data.items) || [];
            if (items.length >= 2) {
                var last = num(items[items.length - 1].close);
                var prev = num(items[items.length - 2].close);
                if (last !== null && prev !== null && prev !== 0) {
                    var change = last - prev;
                    var pct = change / prev * 100;
                    var cls = change >= 0 ? 'sd-rise' : 'sd-fall';
                    setMetric('price', last.toFixed(2), cls);
                    setMetric('change', (change >= 0 ? '+' : '') + change.toFixed(2), cls);
                    setMetric('pctChg', (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%', cls);
                } else if (last !== null) {
                    setMetric('price', last.toFixed(2));
                }
            } else if (items.length === 1) {
                var only = num(items[0].close);
                if (only !== null) setMetric('price', only.toFixed(2));
            }

            // —— 每日基本面 ——
            var basicsItems = (basicsResp && basicsResp.data && basicsResp.data.items) || [];
            var db = basicsItems[0] || {};
            setMetric('totalMv', fmtMarketCap(num(db.totalMv)));
            setMetric('peTtm', num(db.peTtm) !== null && num(db.peTtm) > 0 ? num(db.peTtm).toFixed(2) : '--');
            setMetric('pb', num(db.pb) !== null && num(db.pb) > 0 ? num(db.pb).toFixed(2) : '--');
            setMetric('turnoverRate', num(db.turnoverRate) !== null ? num(db.turnoverRate).toFixed(2) + '%' : '--');
            setMetric('dataDate', db.tradeDate ? fmtDate(db.tradeDate) : '--');

            // —— 行业（watchlist 接口里的 industryName 优先；否则 --） ——
            var watchItems = (watchResp && watchResp.data) || [];
            var matched = null;
            for (var i = 0; i < watchItems.length; i++) {
                if (watchItems[i] && watchItems[i].code === code) { matched = watchItems[i]; break; }
            }
            state.inWatchlist = !!matched;
            state.watchlistItem = matched || null;
            setMetric('industry', (matched && matched.industryName) ? matched.industryName : '--');

            // 行业反查 + 跳转板块页（失败静默，不影响其他功能）
            StockApp.get('/api/industry/by-stock', { tsCode: code }, function (resp) {
                if (resp && resp.code === 200 && resp.data && resp.data.industryCode) {
                    var el = document.querySelector('#sdSummaryMetrics [data-field="industry"]');
                    if (el) {
                        el.style.cursor = 'pointer';
                        el.title = '查看' + resp.data.industryName + '板块行情';
                        el.onclick = function () {
                            window.location.href = (StockApp.contextPath || '') + '/page/sector?industryCode=' + encodeURIComponent(resp.data.industryCode);
                        };
                    }
                }
            });

            // —— 自选按钮态 ——
            syncWatchlistBtn();
            // —— 提醒按钮态（如果已在自选且有提醒，回填弹窗） ——
            syncReminderBtn();
        }).catch(function () {
            // 静默失败，顶部摘要不阻塞页面
        });
    }

    function syncWatchlistBtn() {
        var btn = document.getElementById('sdWatchlistBtn');
        if (!btn) return;
        var icon = btn.querySelector('i');
        var label = btn.querySelector('span');
        if (state.inWatchlist) {
            btn.classList.add('active');
            if (icon) icon.className = 'bi bi-star-fill';
            if (label) label.textContent = '移除自选';
        } else {
            btn.classList.remove('active');
            if (icon) icon.className = 'bi bi-star';
            if (label) label.textContent = '加入自选';
        }
    }

    function syncReminderBtn() {
        var btn = document.getElementById('sdReminderBtn');
        if (!btn) return;
        var has = state.watchlistItem &&
            ((num(state.watchlistItem.targetPriceHigh) || 0) > 0 ||
             (num(state.watchlistItem.targetPriceLow) || 0) > 0);
        var icon = btn.querySelector('i');
        btn.classList.toggle('active', !!has);
        if (icon) icon.className = has ? 'bi bi-bell-fill' : 'bi bi-bell';
    }

    function onToggleWatchlist() {
        var code = state.stockCode;
        var wasIn = state.inWatchlist;
        // 乐观更新
        state.inWatchlist = !wasIn;
        syncWatchlistBtn();

        if (wasIn) {
            StockApp.post('/watchlist/' + encodeURIComponent(code) + '/delete', null, function (resp) {
                if (resp.code === 200) {
                    StockApp.toast('已从自选移除', 'success');
                    state.watchlistItem = null;
                    syncReminderBtn();
                } else {
                    // 回滚
                    state.inWatchlist = true;
                    syncWatchlistBtn();
                    StockApp.toast(resp.message || '移除失败', 'danger');
                }
            });
        } else {
            StockApp.post('/watchlist/' + encodeURIComponent(code), null, function (resp) {
                if (resp.code === 200) {
                    StockApp.toast('已加入自选', 'success');
                } else {
                    state.inWatchlist = false;
                    syncWatchlistBtn();
                    StockApp.toast(resp.message || '添加失败', 'danger');
                }
            });
        }
    }

    function onOpenReminder() {
        if (!state.inWatchlist) {
            StockApp.toast('请先加入自选后再设置价格提醒', 'warning');
            return;
        }
        var highInput = document.getElementById('reminderHighInput');
        var lowInput = document.getElementById('reminderLowInput');
        if (highInput && lowInput && state.watchlistItem) {
            highInput.value = state.watchlistItem.targetPriceHigh || '';
            lowInput.value = state.watchlistItem.targetPriceLow || '';
        } else if (highInput && lowInput) {
            highInput.value = '';
            lowInput.value = '';
        }
        var modalEl = document.getElementById('reminderModal');
        if (modalEl) {
            var modal = bootstrap.Modal.getOrCreateInstance(modalEl);
            modal.show();
        }
    }

    function onSaveReminder() {
        var code = state.stockCode;
        var highInput = document.getElementById('reminderHighInput');
        var lowInput = document.getElementById('reminderLowInput');
        if (!highInput || !lowInput) return;

        var high = highInput.value ? parseFloat(highInput.value) : null;
        var low = lowInput.value ? parseFloat(lowInput.value) : null;

        if ((high !== null && high <= 0) || (low !== null && low <= 0)) {
            StockApp.toast('价格上限/下限必须为正数', 'warning');
            return;
        }

        StockApp.post('/watchlist/' + encodeURIComponent(code) + '/reminder',
            { targetPriceHigh: high, targetPriceLow: low },
            function (resp) {
                if (resp.code === 200) {
                    StockApp.toast('提醒设置成功', 'success');
                    if (state.watchlistItem) {
                        state.watchlistItem.targetPriceHigh = high;
                        state.watchlistItem.targetPriceLow = low;
                    }
                    syncReminderBtn();
                    hideModal('reminderModal');
                } else {
                    StockApp.toast(resp.message || '保存失败', 'danger');
                }
            });
    }

    function onClearReminder() {
        var code = state.stockCode;
        fetch(StockApp.contextPath + '/watchlist/' + encodeURIComponent(code) + '/reminder', {
            method: 'DELETE',
            headers: { 'Accept': 'application/json' }
        }).then(function (r) { return r.json(); }).then(function (resp) {
            if (resp.code === 200) {
                StockApp.toast('提醒已清除', 'success');
                if (state.watchlistItem) {
                    state.watchlistItem.targetPriceHigh = null;
                    state.watchlistItem.targetPriceLow = null;
                }
                syncReminderBtn();
                hideModal('reminderModal');
            } else {
                StockApp.toast(resp.message || '清除失败', 'danger');
            }
        }).catch(function (err) {
            StockApp.toast('请求失败: ' + err.message, 'danger');
        });
    }

    function hideModal(id) {
        var el = document.getElementById(id);
        if (!el) return;
        var inst = bootstrap.Modal.getInstance(el);
        if (inst) inst.hide();
    }

    // ==================== Task 10：K 线技术面 ====================

    function loadKlineTab() {
        var panel = getTabPanel('kline');
        var chartCard = panel.querySelector('.sd-chart-card');
        setSectionView(chartCard, 'loading', '正在加载 K 线');

        // 首次进入创建 KlineRenderer
        if (!state.kline.renderer) {
            var main = document.getElementById('sdKlineMain');
            var sub = document.getElementById('sdKlineSub');
            if (!main || !sub) {
                setSectionView(chartCard, 'error');
                setTabState('kline', 'error');
                return;
            }
            state.kline.renderer = new KlineRenderer({
                mainContainer: main,
                subContainer: sub,
                defaultVisible: 120
            });
        }

        fetchKlineAndRender(function (ok) {
            if (ok) {
                setTabState('kline', 'loaded');
            } else {
                setTabState('kline', 'error');
            }
        });
    }

    function fetchKlineAndRender(callback) {
        var myReqId = ++state.kline.reqId;
        var code = state.stockCode;
        var k = state.kline;
        var url = '/stocks/' + encodeURIComponent(code) + '/kline?period=' + encodeURIComponent(k.period) +
                  '&adj=' + encodeURIComponent(k.adj) + '&limit=250';

        StockApp.get(url, null, function (resp) {
            // 竞态保护：旧响应忽略
            if (myReqId !== state.kline.reqId) return;
            var panel = getTabPanel('kline');
            var chartCard = panel.querySelector('.sd-chart-card');

            if (!resp || resp.code !== 200 || !resp.data || !resp.data.items) {
                setSectionView(chartCard, 'error');
                if (callback) callback(false);
                return;
            }
            var items = resp.data.items || [];
            state.kline.rawItems = items;

            if (!items.length) {
                setSectionView(chartCard, 'empty');
                renderSignalTable([]);
                updateKlineMeta(0);
                if (callback) callback(true);
                return;
            }

            // 渲染 K 线
            setSectionView(chartCard, 'loaded');
            k.renderer.setData(items);
            // 按周期差异化设置可见 K 线根数（日K 120 / 周K 100 / 月K 80），避免数据量差异导致缩放失真
            var visibleByPeriod = { D: 120, W: 100, M: 80, '60MIN': 120 };
            k.renderer.setVisibleBars(visibleByPeriod[k.period] || 120);
            // 应用当前主/副图指标
            k.renderer.setMainIndicator(k.mainIndicator);
            k.renderer.setSubIndicator(k.subIndicator);

            // 信号时间轴
            renderSignalTable(items);
            updateKlineMeta(items.length);
            if (callback) callback(true);
        });
    }

    function updateKlineMeta(count) {
        var el = document.querySelector('[data-kline-meta]');
        if (!el) return;
        var k = state.kline;
        var periodLabel = { D: '日K', W: '周K', M: '月K', '60MIN': '60分' }[k.period] || k.period;
        var adjLabel = { QFQ: '前复权', HFQ: '后复权', NONE: '不复权' }[k.adj] || k.adj;
        el.textContent = periodLabel + ' · ' + adjLabel + ' · ' + count + '根';
    }

    /** 简单算术均线 */
    function maOf(arr, n) {
        if (arr.length < n) return null;
        var sum = 0;
        for (var i = arr.length - n; i < arr.length; i++) sum += arr[i];
        return sum / n;
    }

    /** 计算整段 MA 序列 */
    function maSeries(closes, n) {
        var out = new Array(closes.length).fill(null);
        var sum = 0;
        for (var i = 0; i < closes.length; i++) {
            sum += closes[i];
            if (i >= n) sum -= closes[i - n];
            if (i >= n - 1) out[i] = sum / n;
        }
        return out;
    }

    /** 扫描 MA5/10/20/60 金叉死叉 */
    function renderSignalTable(items) {
        var tbody = document.querySelector('#sdSignalTable tbody');
        if (!tbody) return;
        if (!items || items.length < 60) {
            tbody.innerHTML = emptyRow(3, '数据不足，无法计算信号');
            return;
        }
        var closes = items.map(function (d) { return num(d.close); });
        var ma5 = maSeries(closes, 5);
        var ma10 = maSeries(closes, 10);
        var ma20 = maSeries(closes, 20);
        var ma60 = maSeries(closes, 60);

        var pairs = [
            { fast: ma5, fastN: 5, slow: ma10, slowN: 10 },
            { fast: ma5, fastN: 5, slow: ma20, slowN: 20 },
            { fast: ma5, fastN: 5, slow: ma60, slowN: 60 },
            { fast: ma10, fastN: 10, slow: ma20, slowN: 20 },
            { fast: ma10, fastN: 10, slow: ma60, slowN: 60 },
            { fast: ma20, fastN: 20, slow: ma60, slowN: 60 }
        ];

        var signals = [];
        for (var p = 0; p < pairs.length; p++) {
            var pair = pairs[p];
            for (var i = 1; i < items.length; i++) {
                var f0 = pair.fast[i - 1], f1 = pair.fast[i];
                var s0 = pair.slow[i - 1], s1 = pair.slow[i];
                if (f0 === null || f1 === null || s0 === null || s1 === null) continue;
                if (f0 <= s0 && f1 > s1) {
                    signals.push({
                        date: items[i].date,
                        type: '金叉',
                        desc: 'MA' + pair.fastN + '上穿MA' + pair.slowN
                    });
                } else if (f0 >= s0 && f1 < s1) {
                    signals.push({
                        date: items[i].date,
                        type: '死叉',
                        desc: 'MA' + pair.fastN + '下穿MA' + pair.slowN
                    });
                }
            }
        }

        signals.sort(function (a, b) { return String(b.date).localeCompare(String(a.date)); });
        signals = signals.slice(0, 50);

        if (!signals.length) {
            tbody.innerHTML = emptyRow(3, '近 ' + items.length + ' 根无金叉/死叉信号');
            return;
        }

        var html = '';
        signals.forEach(function (s) {
            var cls = s.type === '金叉' ? 'sd-rise' : 'sd-fall';
            html += '<tr>' +
                '<td>' + e(fmtDate(s.date)) + '</td>' +
                '<td><span class="' + cls + ' fw-medium">' + e(s.type) + '</span></td>' +
                '<td class="text-muted">' + e(s.desc) + '</td>' +
                '</tr>';
        });
        tbody.innerHTML = html;
    }

    function bindKlineToolbar() {
        var toolbar = document.querySelector('#sd-panel-kline .sd-kline-toolbar');
        if (!toolbar) return;

        // 周期
        toolbar.querySelectorAll('[data-control="period"] [data-period]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (btn.disabled) return;
                toolbar.querySelectorAll('[data-control="period"] [data-period]').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                state.kline.period = btn.dataset.period;
                fetchKlineAndRender();
            });
        });

        // 复权
        toolbar.querySelectorAll('[data-control="adj"] [data-adj]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                toolbar.querySelectorAll('[data-control="adj"] [data-adj]').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                state.kline.adj = btn.dataset.adj;
                fetchKlineAndRender();
            });
        });

        // 主图指标
        toolbar.querySelectorAll('[data-control="mainIndicator"] [data-main]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                toolbar.querySelectorAll('[data-control="mainIndicator"] [data-main]').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                state.kline.mainIndicator = btn.dataset.main;
                if (state.kline.renderer) state.kline.renderer.setMainIndicator(state.kline.mainIndicator);
            });
        });

        // 副图指标
        toolbar.querySelectorAll('[data-control="subIndicator"] [data-sub]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                toolbar.querySelectorAll('[data-control="subIndicator"] [data-sub]').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                state.kline.subIndicator = btn.dataset.sub;
                if (state.kline.renderer) state.kline.renderer.setSubIndicator(state.kline.subIndicator);
            });
        });
    }

    // ==================== Task 11：基本面 ====================

    function loadFundamentalTab() {
        // 首次进入并发触发 5 个卡片
        loadValuationCard();
        loadTrendCard();
        loadStatementsCard();
        loadEventsCard();
        loadHoldersCard();
        setTabState('fundamental', 'loaded');
    }

    // ----- 卡片 1：估值百分位 -----
    function loadValuationCard() {
        setCardStatus('valuation', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/daily-basics', { limit: 1300 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('valuation', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('valuation', 'empty'); return; }

            var latest = items[items.length - 1];
            renderValuationCard(items, latest);
            setCardStatus('valuation', 'loaded');
        });
    }

    function percentileOf(series, current) {
        // 有效样本（剔除 null/NaN/<=0），rank/total*100
        var valid = series.filter(function (v) { return v !== null && v !== undefined && !isNaN(v) && v > 0; });
        if (!valid.length || current === null || current === undefined || current <= 0) return null;
        var rank = valid.filter(function (v) { return v <= current; }).length;
        return rank / valid.length * 100;
    }

    function renderValuationCard(items, latest) {
        var body = document.querySelector('[data-card="valuation"] [data-card-body]');
        if (!body) return;
        var peSeries = items.map(function (d) { return num(d.peTtm); });
        var pbSeries = items.map(function (d) { return num(d.pb); });
        var psSeries = items.map(function (d) { return num(d.ps); });

        var peNow = num(latest.peTtm);
        var pbNow = num(latest.pb);
        var psNow = num(latest.ps);
        var pePct = percentileOf(peSeries, peNow);
        var pbPct = percentileOf(pbSeries, pbNow);
        var psPct = percentileOf(psSeries, psNow);

        var firstDate = items[0].tradeDate;
        var lastDate = latest.tradeDate;
        var rangeNote = '样本范围：' + fmtDate(firstDate) + ' 至 ' + fmtDate(lastDate) + '（共 ' + items.length + ' 个交易日）';

        function row(name, value, pct) {
            var valText = (value !== null && value > 0) ? value.toFixed(2) : '--';
            var pctText = pct !== null ? pct.toFixed(1) + '%' : '--';
            var barStyle = pct !== null ? 'left:' + pct.toFixed(1) + '%' : 'left:0%';
            return '<div class="sd-percentile-row">' +
                '<div class="sd-percentile-head"><span>' + e(name) + '</span>' +
                    '<strong>' + valText + '</strong>' +
                    '<span class="sd-percentile-pct">' + pctText + '</span>' +
                '</div>' +
                '<div class="sd-percentile-bar"><i style="' + barStyle + '"></i></div>' +
            '</div>';
        }

        body.innerHTML =
            '<div class="sd-percentile-list">' +
                row('PE(TTM)', peNow, pePct) +
                row('PB', pbNow, pbPct) +
                row('PS', psNow, psPct) +
            '</div>' +
            '<p class="small text-muted mt-2 mb-0">' + e(rangeNote) + '</p>';
    }

    // ----- 卡片 2：财务趋势 -----
    function loadTrendCard() {
        setCardStatus('trend', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/fina-indicators', { limit: 20 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('trend', 'error');
                return;
            }
            var items = (resp.data.items || []).slice().sort(function (a, b) {
                return String(a.endDate || '').localeCompare(String(b.endDate || ''));
            });
            if (!items.length) { setCardStatus('trend', 'empty'); return; }
            renderTrendChart(items);
            setCardStatus('trend', 'loaded');
        });
    }

    function renderTrendChart(items) {
        var body = document.querySelector('[data-card="trend"] [data-card-body]');
        if (!body) return;
        body.innerHTML = '<div class="sd-chart" style="height:240px"></div>';
        var container = body.querySelector('.sd-chart');
        var chart = ensureChart('fundamental:trend', container);
        if (!chart) return;
        var theme = getTheme();
        var colors = getChartColors();

        var dates = items.map(function (d) { return fmtDate(d.endDate); });
        var roe = items.map(function (d) { return num(d.roe); });
        var roa = items.map(function (d) { return num(d.roa); });
        var gpm = items.map(function (d) { return num(d.grossprofitMargin); });
        var npm = items.map(function (d) { return num(d.netprofitMargin); });

        function buildSeries(name, data, color) {
            return {
                name: name, type: 'line', smooth: true, data: data, symbol: 'circle', symbolSize: 5,
                connectNulls: true,
                lineStyle: { color: color, width: 2 }, itemStyle: { color: color }
            };
        }

        chart.setOption({
            tooltip: Object.assign({}, theme.tooltip || {}, {
                trigger: 'axis',
                formatter: function (params) {
                    var html = e(params[0].axisValueLabel) + '<br/>';
                    params.forEach(function (p) {
                        html += p.marker + ' ' + e(p.seriesName) + ': <b>' + (p.value != null ? Number(p.value).toFixed(2) + '%' : '--') + '</b><br/>';
                    });
                    return html;
                }
            }),
            legend: { data: ['ROE', 'ROA', '毛利率', '净利率'], top: 0, textStyle: (theme.legend || {}).textStyle || {} },
            grid: { left: '3%', right: '4%', bottom: '3%', top: '18%', containLabel: true },
            xAxis: {
                type: 'category', data: dates, boundaryGap: false,
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10, rotate: 30 })
            },
            yAxis: {
                type: 'value', name: '%',
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, { formatter: function (v) { return v + '%'; } }),
                splitLine: theme.splitLine
            },
            series: [
                buildSeries('ROE', roe, colors[0]),
                buildSeries('ROA', roa, colors[1]),
                buildSeries('毛利率', gpm, colors[2]),
                buildSeries('净利率', npm, colors[3])
            ]
        }, true);
    }

    // ----- 卡片 3：三大报表 -----
    function loadStatementsCard() {
        setCardStatus('statements', 'loading');
        var code = state.stockCode;
        var base = '/stocks/' + encodeURIComponent(code);
        Promise.all([
            fetchJson(base + '/incomes?limit=4'),
            fetchJson(base + '/balancesheets?limit=4'),
            fetchJson(base + '/cashflows?limit=4')
        ]).then(function (results) {
            var incomes = (results[0].data && results[0].data.items) || [];
            var balances = (results[1].data && results[1].data.items) || [];
            var cashflows = (results[2].data && results[2].data.items) || [];
            if (!incomes.length && !balances.length && !cashflows.length) {
                setCardStatus('statements', 'empty');
                return;
            }
            renderStatementsCard(incomes, balances, cashflows);
            setCardStatus('statements', 'loaded');
        }).catch(function () {
            setCardStatus('statements', 'error');
        });
    }

    function renderStatementsCard(incomes, balances, cashflows) {
        var body = document.querySelector('[data-card="statements"] [data-card-body]');
        if (!body) return;

        function buildYoy(curr, prev) {
            if (curr === null || prev === null || prev === 0) return null;
            return (curr - prev) / Math.abs(prev) * 100;
        }

        function findPrev(arr, endDate) {
            // 简单找上一期：报告期升序后取相邻
            for (var i = 0; i < arr.length; i++) {
                if (String(arr[i].endDate) === String(endDate)) {
                    return i + 1 < arr.length ? arr[i + 1] : null;
                }
            }
            return null;
        }

        // 利润表：营收、归母净利润、同比
        var incomeRows = '';
        incomes.slice(0, 4).forEach(function (it) {
            var prev = findPrev(incomes, it.endDate);
            var rev = num(it.totalRevenue) || num(it.revenue);
            var net = num(it.nIncomeAttrP) || num(it.nIncome);
            var netPrev = prev ? (num(prev.nIncomeAttrP) || num(prev.nIncome)) : null;
            var yoy = buildYoy(net, netPrev);
            incomeRows += '<tr>' +
                '<td>' + e(fmtDate(it.endDate)) + '</td>' +
                '<td class="text-end">' + fmtAmountWan(rev) + '</td>' +
                '<td class="text-end">' + fmtAmountWan(net) + '</td>' +
                '<td class="text-end ' + riseFallClass(yoy) + '">' + (yoy !== null ? fmtPercent(yoy, 2, true) : '--') + '</td>' +
            '</tr>';
        });

        // 资产负债表：总资产、总负债、净资产
        var balanceRows = '';
        balances.slice(0, 4).forEach(function (it) {
            balanceRows += '<tr>' +
                '<td>' + e(fmtDate(it.endDate)) + '</td>' +
                '<td class="text-end">' + fmtAmountWan(num(it.totalAssets)) + '</td>' +
                '<td class="text-end">' + fmtAmountWan(num(it.totalLiab)) + '</td>' +
                '<td class="text-end">' + fmtAmountWan(num(it.totalEquity)) + '</td>' +
            '</tr>';
        });

        // 现金流量表：经营/投资/筹资现金流净额
        var cashflowRows = '';
        cashflows.slice(0, 4).forEach(function (it) {
            cashflowRows += '<tr>' +
                '<td>' + e(fmtDate(it.endDate)) + '</td>' +
                '<td class="text-end ' + riseFallClass(num(it.nCashflowAct)) + '">' + fmtAmountWan(num(it.nCashflowAct)) + '</td>' +
                '<td class="text-end ' + riseFallClass(num(it.nCashflowInvAct)) + '">' + fmtAmountWan(num(it.nCashflowInvAct)) + '</td>' +
                '<td class="text-end ' + riseFallClass(num(it.nCashFlowsFncAct)) + '">' + fmtAmountWan(num(it.nCashFlowsFncAct)) + '</td>' +
            '</tr>';
        });

        body.innerHTML =
            '<h6 class="sd-mini-title">利润表</h6>' +
            '<table class="sd-mini-table"><thead><tr>' +
                '<th>报告期</th><th class="text-end">营收(万)</th><th class="text-end">归母净利(万)</th><th class="text-end">同比</th>' +
            '</tr></thead><tbody>' + (incomeRows || emptyRow(4)) + '</tbody></table>' +
            '<h6 class="sd-mini-title mt-2">资产负债表</h6>' +
            '<table class="sd-mini-table"><thead><tr>' +
                '<th>报告期</th><th class="text-end">总资产(万)</th><th class="text-end">总负债(万)</th><th class="text-end">净资产(万)</th>' +
            '</tr></thead><tbody>' + (balanceRows || emptyRow(4)) + '</tbody></table>' +
            '<h6 class="sd-mini-title mt-2">现金流量表</h6>' +
            '<table class="sd-mini-table"><thead><tr>' +
                '<th>报告期</th><th class="text-end">经营(万)</th><th class="text-end">投资(万)</th><th class="text-end">筹资(万)</th>' +
            '</tr></thead><tbody>' + (cashflowRows || emptyRow(4)) + '</tbody></table>';
    }

    // ----- 卡片 4：业绩事件与分红 -----
    function loadEventsCard() {
        setCardStatus('events', 'loading');
        var code = state.stockCode;
        var base = '/stocks/' + encodeURIComponent(code);
        Promise.all([
            fetchJson(base + '/forecasts?limit=10'),
            fetchJson(base + '/expresses?limit=10'),
            fetchJson(base + '/dividends?limit=10')
        ]).then(function (results) {
            var forecasts = (results[0].data && results[0].data.items) || [];
            var expresses = (results[1].data && results[1].data.items) || [];
            var dividends = (results[2].data && results[2].data.items) || [];
            if (!forecasts.length && !expresses.length && !dividends.length) {
                setCardStatus('events', 'empty');
                return;
            }
            renderEventsCard(forecasts, expresses, dividends);
            setCardStatus('events', 'loaded');
        }).catch(function () {
            setCardStatus('events', 'error');
        });
    }

    function renderEventsCard(forecasts, expresses, dividends) {
        var body = document.querySelector('[data-card="events"] [data-card-body]');
        if (!body) return;
        var rows = '';

        forecasts.forEach(function (d) {
            var pctRange = '';
            if (num(d.pChangeMin) !== null && num(d.pChangeMax) !== null) {
                pctRange = num(d.pChangeMin) + '% ~ ' + num(d.pChangeMax) + '%';
            }
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.annDate)) + '</td>' +
                '<td><span class="sd-tag">预告</span> ' + e(d.type || '--') + '</td>' +
                '<td>报告期 ' + e(fmtDate(d.endDate)) + (pctRange ? '· 变动 ' + e(pctRange) : '') +
                    (d.summary ? ' · ' + e(d.summary) : '') + '</td>' +
            '</tr>';
        });

        expresses.forEach(function (d) {
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.annDate)) + '</td>' +
                '<td><span class="sd-tag">快报</span></td>' +
                '<td>报告期 ' + e(fmtDate(d.endDate)) +
                    ' · 营收 ' + fmtAmountWan(num(d.revenue)) +
                    ' · 净利 ' + fmtAmountWan(num(d.nIncome)) +
                    ' · EPS ' + fmtNumber(d.basicEps) + '</td>' +
            '</tr>';
        });

        dividends.forEach(function (d) {
            var desc = [];
            if (num(d.cashDiv) !== null && num(d.cashDiv) > 0) desc.push('每10股派' + num(d.cashDiv).toFixed(2) + '元');
            if (num(d.stkDiv) !== null && num(d.stkDiv) > 0) desc.push('每10股送' + num(d.stkDiv).toFixed(2) + '股');
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.annDate)) + '</td>' +
                '<td><span class="sd-tag">分红</span> ' + e(d.divProc || '--') + '</td>' +
                '<td>年度 ' + e(fmtDate(d.endDate)) + ' · ' + e(desc.join('；') || '--') + '</td>' +
            '</tr>';
        });

        if (!rows) rows = emptyRow(3);
        body.innerHTML = '<table class="sd-mini-table"><thead><tr>' +
            '<th style="width:110px">公告日</th><th style="width:120px">类型</th><th>摘要</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ----- 卡片 5：股东人数 -----
    function loadHoldersCard() {
        setCardStatus('holders', 'loading');
        var code = state.stockCode;
        // 注意：此接口直接返回数组（非 {items,meta}）
        StockApp.get('/api/stk-holdernumber', { tsCode: code, limit: 20 }, function (resp) {
            if (resp.code !== 200 || !resp.data) {
                setCardStatus('holders', 'error');
                return;
            }
            var items = (resp.data || []).slice().sort(function (a, b) {
                return String(a.endDate || '').localeCompare(String(b.endDate || ''));
            });
            if (!items.length) { setCardStatus('holders', 'empty'); return; }
            renderHoldersChart(items);
            setCardStatus('holders', 'loaded');
        });
    }

    function renderHoldersChart(items) {
        var body = document.querySelector('[data-card="holders"] [data-card-body]');
        if (!body) return;
        body.innerHTML = '<div class="sd-chart" style="height:240px"></div>';
        var container = body.querySelector('.sd-chart');
        var chart = ensureChart('fundamental:holders', container);
        if (!chart) return;
        var theme = getTheme();
        var colors = getChartColors();

        var dates = items.map(function (d) { return fmtDate(d.endDate); });
        var nums = items.map(function (d) { return num(d.holderNum); });
        // 环比变化
        var changes = nums.map(function (v, i) {
            if (i === 0 || v === null || nums[i - 1] === null || nums[i - 1] === 0) return null;
            return (v - nums[i - 1]) / nums[i - 1] * 100;
        });

        chart.setOption({
            tooltip: Object.assign({}, theme.tooltip || {}, {
                trigger: 'axis',
                formatter: function (params) {
                    var html = e(params[0].axisValueLabel) + '<br/>';
                    params.forEach(function (p) {
                        var val = p.value;
                        if (p.seriesName === '股东人数') {
                            html += p.marker + ' 股东人数: <b>' + (val != null ? fmtVolume(val) : '--') + '</b><br/>';
                        } else {
                            html += p.marker + ' 环比变化: <b>' + (val != null ? fmtPercent(val, 2, true) : '--') + '</b><br/>';
                        }
                    });
                    return html;
                }
            }),
            legend: { data: ['股东人数', '环比变化'], top: 0, textStyle: (theme.legend || {}).textStyle || {} },
            grid: { left: '3%', right: '4%', bottom: '3%', top: '18%', containLabel: true },
            xAxis: {
                type: 'category', data: dates, boundaryGap: true,
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10, rotate: 30 })
            },
            yAxis: [
                {
                    type: 'value', name: '人数',
                    axisLine: theme.axisLine, axisTick: theme.axisTick,
                    axisLabel: Object.assign({}, theme.axisLabel || {}, {
                        formatter: function (v) { return fmtVolume(v); }
                    }),
                    splitLine: theme.splitLine
                },
                {
                    type: 'value', name: '环比%',
                    axisLine: theme.axisLine, axisTick: theme.axisTick,
                    axisLabel: Object.assign({}, theme.axisLabel || {}, { formatter: function (v) { return v + '%'; } }),
                    splitLine: { show: false }
                }
            ],
            series: [
                {
                    name: '股东人数', type: 'line', smooth: true, data: nums, yAxisIndex: 0,
                    symbol: 'circle', symbolSize: 5, connectNulls: true,
                    lineStyle: { color: colors[0], width: 2 }, itemStyle: { color: colors[0] },
                    areaStyle: ChartsTheme.getAreaGradient(chart, colors[0])
                },
                {
                    name: '环比变化', type: 'bar', data: changes.map(function (v) {
                        return v === null ? null : {
                            value: v,
                            itemStyle: { color: v >= 0 ? riseColor() : fallColor() }
                        };
                    }), yAxisIndex: 1, barMaxWidth: 16
                }
            ]
        }, true);
    }

    // ==================== Task 12：资金面 ====================

    function loadMoneyflowTab() {
        loadMainflowCard();
        loadHkholdCard();
        loadToplistCard();
        loadBlocktradeCard();
        setTabState('moneyflow', 'loaded');
    }

    // ----- 卡片 1：主力净流入 -----
    function loadMainflowCard() {
        setCardStatus('mainflow', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/moneyflows', { days: 30 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('mainflow', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('mainflow', 'empty'); return; }
            renderMainflowCard(items);
            setCardStatus('mainflow', 'loaded');
        });
    }

    function renderMainflowCard(items) {
        var body = document.querySelector('[data-card="mainflow"] [data-card-body]');
        if (!body) return;
        body.innerHTML =
            '<div class="sd-mf-summary"></div>' +
            '<div class="sd-chart" style="height:220px"></div>';

        // 汇总：金额单位=万元
        var total = 0, inflowDays = 0, outflowDays = 0;
        items.forEach(function (d) {
            var n = num(d.netMfAmount);
            if (n === null) return;
            total += n;
            if (n > 0) inflowDays++; else if (n < 0) outflowDays++;
        });
        var summaryEl = body.querySelector('.sd-mf-summary');
        summaryEl.innerHTML =
            '<span>累计净流入：<strong class="' + riseFallClass(total) + '">' + fmtAmountWan(total) + '</strong></span>' +
            '<span>流入天数：<strong class="sd-rise">' + inflowDays + '</strong></span>' +
            '<span>流出天数：<strong class="sd-fall">' + outflowDays + '</strong></span>';

        var container = body.querySelector('.sd-chart');
        var chart = ensureChart('moneyflow:main', container);
        if (!chart) return;
        var theme = getTheme();

        var dates = items.map(function (d) { return fmtDate(d.tradeDate); });
        var values = items.map(function (d) {
            var v = num(d.netMfAmount);
            return v === null ? null : {
                value: v,
                itemStyle: { color: v >= 0 ? riseColor() : fallColor() }
            };
        });

        chart.setOption({
            tooltip: Object.assign({}, theme.tooltip || {}, {
                trigger: 'axis', axisPointer: { type: 'shadow' },
                formatter: function (params) {
                    var p = params[0];
                    var v = p.value;
                    var val = (v && typeof v === 'object') ? v.value : v;
                    return e(p.axisValueLabel) + '<br/>主力净流入: <b style="color:' + (val >= 0 ? riseColor() : fallColor()) + '">' +
                        (val >= 0 ? '+' : '') + fmtAmountWan(val) + '</b>';
                }
            }),
            grid: { left: '3%', right: '4%', bottom: '3%', top: '5%', containLabel: true },
            xAxis: {
                type: 'category', data: dates,
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10, rotate: 30 })
            },
            yAxis: {
                type: 'value', name: '万元',
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, {
                    formatter: function (v) {
                        if (Math.abs(v) >= 10000) return (v / 10000).toFixed(1) + '亿';
                        return v.toFixed(0);
                    }
                }),
                splitLine: theme.splitLine
            },
            series: [{
                type: 'bar', data: values, barMaxWidth: 20
            }]
        }, true);
    }

    // ----- 卡片 2：北向持股 -----
    function bindHkRangeButtons() {
        var group = document.querySelector('[data-control="hkRange"]');
        if (!group) return;
        group.querySelectorAll('[data-hk-range]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                group.querySelectorAll('[data-hk-range]').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                state.hkRange = btn.dataset.hkRange;
                loadHkholdCard();
            });
        });
    }

    function loadHkholdCard() {
        setCardStatus('hkhold', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/hk-holds', { range: state.hkRange }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('hkhold', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) {
                // 显示"暂无北向持股数据"
                var body = document.querySelector('[data-card="hkhold"] [data-card-body]');
                if (body) body.innerHTML = '<p class="text-muted text-center py-3 mb-0">暂无北向持股数据</p>';
                setCardStatus('hkhold', 'loaded');
                return;
            }
            renderHkholdCard(items);
            setCardStatus('hkhold', 'loaded');
        });
    }

    function renderHkholdCard(items) {
        var body = document.querySelector('[data-card="hkhold"] [data-card-body]');
        if (!body) return;
        body.innerHTML = '<div class="sd-chart" style="height:240px"></div>';
        var container = body.querySelector('.sd-chart');
        var chart = ensureChart('moneyflow:hk', container);
        if (!chart) return;
        var theme = getTheme();
        var colors = getChartColors();

        var dates = items.map(function (d) { return fmtDate(d.tradeDate); });
        var vols = items.map(function (d) { return num(d.vol); });
        var ratios = items.map(function (d) { return num(d.ratio); });

        chart.setOption({
            tooltip: Object.assign({}, theme.tooltip || {}, {
                trigger: 'axis',
                formatter: function (params) {
                    var html = e(params[0].axisValueLabel) + '<br/>';
                    params.forEach(function (p) {
                        var unit = p.seriesName === '持股数量' ? '股' : '%';
                        var val = p.value;
                        html += p.marker + ' ' + e(p.seriesName) + ': <b>' + (val != null ? fmtVolume(val) + unit : '--') + '</b><br/>';
                    });
                    return html;
                }
            }),
            legend: { data: ['持股数量', '持股比例'], top: 0, textStyle: (theme.legend || {}).textStyle || {} },
            grid: { left: '3%', right: '4%', bottom: '3%', top: '18%', containLabel: true },
            xAxis: {
                type: 'category', data: dates, boundaryGap: false,
                axisLine: theme.axisLine, axisTick: theme.axisTick,
                axisLabel: Object.assign({}, theme.axisLabel || {}, { fontSize: 10, rotate: 30 })
            },
            yAxis: [
                {
                    type: 'value', name: '持股(股)',
                    axisLine: theme.axisLine, axisTick: theme.axisTick,
                    axisLabel: Object.assign({}, theme.axisLabel || {}, { formatter: function (v) { return fmtVolume(v); } }),
                    splitLine: theme.splitLine
                },
                {
                    type: 'value', name: '占比(%)',
                    axisLine: theme.axisLine, axisTick: theme.axisTick,
                    axisLabel: Object.assign({}, theme.axisLabel || {}, { formatter: function (v) { return v + '%'; } }),
                    splitLine: { show: false }
                }
            ],
            series: [
                {
                    name: '持股数量', type: 'line', smooth: true, data: vols, yAxisIndex: 0,
                    symbol: 'none', connectNulls: true,
                    lineStyle: { color: colors[0], width: 2 }, itemStyle: { color: colors[0] },
                    areaStyle: ChartsTheme.getAreaGradient(chart, colors[0])
                },
                {
                    name: '持股比例', type: 'line', smooth: true, data: ratios, yAxisIndex: 1,
                    symbol: 'none', connectNulls: true,
                    lineStyle: { color: colors[1], width: 2 }, itemStyle: { color: colors[1] }
                }
            ]
        }, true);
    }

    // ----- 卡片 3：龙虎榜 -----
    function loadToplistCard() {
        setCardStatus('toplist', 'loading');
        var code = state.stockCode;
        // StockMoneyflowController 提供单股接口 /stocks/{code}/top-lists，直接返回该股上榜记录
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/top-lists', { limit: 100 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('toplist', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('toplist', 'empty'); return; }
            renderToplistCard(items);
            setCardStatus('toplist', 'loaded');
        });
    }

    function renderToplistCard(items) {
        var body = document.querySelector('[data-card="toplist"] [data-card-body]');
        if (!body) return;
        var rows = '';
        items.forEach(function (d, i) {
            var net = num(d.netAmount);
            rows += '<tr data-trade-date="' + e(d.tradeDate) + '" data-ts-code="' + e(d.tsCode) + '">' +
                '<td>' + e(fmtDate(d.tradeDate)) + '</td>' +
                '<td class="text-end">' + fmtNumber(d.close) + '</td>' +
                '<td class="text-end ' + riseFallClass(net) + '">' + fmtAmount(net) + '</td>' +
                '<td>' + e(d.reason || '--') + '</td>' +
                '<td><button class="btn btn-sm btn-outline-info sd-seat-toggle">展开席位</button></td>' +
            '</tr>';
            rows += '<tr class="sd-seat-row" hidden><td colspan="5" class="bg-secondary bg-opacity-10"></td></tr>';
        });
        body.innerHTML = '<table class="sd-mini-table"><thead><tr>' +
            '<th style="width:110px">日期</th><th class="text-end" style="width:80px">收盘价</th>' +
            '<th class="text-end" style="width:120px">净额</th><th>上榜原因</th><th style="width:90px">席位</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table>';

        // 绑定席位展开
        body.querySelectorAll('.sd-seat-toggle').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var tr = btn.closest('tr');
                var seatRow = tr.nextElementSibling;
                if (!seatRow || !seatRow.classList.contains('sd-seat-row')) return;
                var tradeDate = tr.dataset.tradeDate;
                var tsCode = tr.dataset.tsCode;
                if (seatRow.hidden) {
                    seatRow.hidden = false;
                    btn.textContent = '收起席位';
                    if (!seatRow.dataset.loaded) loadSeats(tradeDate, tsCode, seatRow);
                } else {
                    seatRow.hidden = true;
                    btn.textContent = '展开席位';
                }
            });
        });
    }

    function loadSeats(tradeDate, tsCode, seatRow) {
        var cacheKey = tradeDate + '|' + tsCode;
        var cell = seatRow.querySelector('td');
        if (state.seatsCache[cacheKey]) {
            cell.innerHTML = renderSeats(state.seatsCache[cacheKey]);
            seatRow.dataset.loaded = 'true';
            return;
        }
        cell.innerHTML = '<div class="text-center text-muted py-2">加载中...</div>';
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/top-lists/' + encodeURIComponent(tradeDate) + '/seats', null, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                cell.innerHTML = '<div class="text-center text-muted py-2">暂无席位数据</div>';
                seatRow.dataset.loaded = 'true';
                return;
            }
            state.seatsCache[cacheKey] = resp.data.items;
            cell.innerHTML = renderSeats(resp.data.items);
            seatRow.dataset.loaded = 'true';
        });
    }

    function renderSeats(data) {
        if (!data || !data.length) return '<div class="text-center text-muted py-2">暂无席位数据</div>';
        // side: "0"=买入前5，"1"=卖出前5
        var buy = data.filter(function (d) { return String(d.side) === '0'; })
                      .sort(function (a, b) { return (num(b.buy) || 0) - (num(a.buy) || 0); });
        var sell = data.filter(function (d) { return String(d.side) === '1'; })
                       .sort(function (a, b) { return (num(b.sell) || 0) - (num(a.sell) || 0); });

        function seatsTable(list, isBuy) {
            if (!list.length) return '<div class="small text-muted">暂无数据</div>';
            var html = '<table class="table table-sm table-borderless mb-0"><tbody>';
            list.slice(0, 5).forEach(function (s) {
                html += '<tr>' +
                    '<td class="small">' + e(s.exalter || '') + '</td>' +
                    '<td class="text-end small">' + (isBuy ? fmtAmount(s.buy) : fmtAmount(s.sell)) + '</td>' +
                    '<td class="text-end small ' + riseFallClass(num(s.netBuy)) + '">' + fmtAmount(s.netBuy) + '</td>' +
                '</tr>';
            });
            html += '</tbody></table>';
            return html;
        }

        return '<div class="row g-2 py-2">' +
            '<div class="col-md-6"><h6 class="small text-muted mb-1">买入席位 TOP 5</h6>' + seatsTable(buy, true) + '</div>' +
            '<div class="col-md-6"><h6 class="small text-muted mb-1">卖出席位 TOP 5</h6>' + seatsTable(sell, false) + '</div>' +
        '</div>';
    }

    // ----- 卡片 4：大宗交易 -----
    function loadBlocktradeCard() {
        setCardStatus('blocktrade', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/block-trades', { page: 1, size: 50 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('blocktrade', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('blocktrade', 'empty'); return; }
            renderBlocktradeCard(items);
            setCardStatus('blocktrade', 'loaded');
        });
    }

    function renderBlocktradeCard(items) {
        var body = document.querySelector('[data-card="blocktrade"] [data-card-body]');
        if (!body) return;
        var rows = '';
        items.forEach(function (d) {
            // premiumRate 后端已计算；若空则前端补算
            var premium = num(d.premiumRate);
            if (premium === null) {
                var price = num(d.price), close = num(d.closePrice);
                if (price !== null && close !== null && close !== 0) premium = (price - close) / close * 100;
            }
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.tradeDate)) + '</td>' +
                '<td class="text-end">' + fmtNumber(d.price) + '</td>' +
                '<td class="text-end">' + fmtVolume(d.vol) + '</td>' +
                '<td class="text-end">' + fmtAmount(d.amount) + '</td>' +
                '<td class="text-end ' + riseFallClass(premium) + '">' + (premium !== null ? fmtPercent(premium, 2, true) : '--') + '</td>' +
            '</tr>';
        });
        body.innerHTML = '<table class="sd-mini-table"><thead><tr>' +
            '<th style="width:110px">日期</th><th class="text-end" style="width:90px">成交价</th>' +
            '<th class="text-end" style="width:110px">成交量</th><th class="text-end" style="width:120px">成交额</th>' +
            '<th class="text-end" style="width:100px">溢价率</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ==================== Task 13：风险面 ====================

    function loadRiskTab() {
        loadLimitCard();
        loadSuspendCard();
        loadStCard();
        loadHoldertradeCard();
        setTabState('risk', 'loaded');
    }

    // ----- 卡片 1：涨跌停 -----
    function loadLimitCard() {
        setCardStatus('limit', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/limits', { limit: 5 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('limit', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('limit', 'empty'); return; }
            renderLimitCard(items);
            setCardStatus('limit', 'loaded');
        });
    }

    function renderLimitCard(items) {
        var body = document.querySelector('[data-card="limit"] [data-card-body]');
        if (!body) return;
        var latest = items[0]; // 已是倒序，第一条最新
        var up = num(latest.upLimit);
        var down = num(latest.downLimit);
        var pre = num(latest.preClose);

        var note = '';
        if (latest.tradeDate) {
            var today = todayYmd();
            if (String(latest.tradeDate) !== today) {
                note = '<p class="small text-muted mt-2 mb-0"><i class="bi bi-info-circle me-1"></i>最近涨跌停数据日期为 ' + e(fmtDate(latest.tradeDate)) + '，今日可能非交易日或停牌。</p>';
            }
        }

        body.innerHTML =
            '<div class="sd-limit-grid">' +
                '<div class="sd-limit-cell"><span>涨停价</span><strong class="sd-rise">' + (up !== null ? up.toFixed(2) : '--') + '</strong></div>' +
                '<div class="sd-limit-cell"><span>昨收</span><strong>' + (pre !== null ? pre.toFixed(2) : '--') + '</strong></div>' +
                '<div class="sd-limit-cell"><span>跌停价</span><strong class="sd-fall">' + (down !== null ? down.toFixed(2) : '--') + '</strong></div>' +
            '</div>' +
            '<p class="small text-muted mt-2 mb-0">数据日期：' + e(fmtDate(latest.tradeDate)) + '</p>' +
            note;
    }

    // ----- 卡片 2：历史停牌 -----
    function loadSuspendCard() {
        setCardStatus('suspend', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/suspends', { limit: 100 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('suspend', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('suspend', 'empty'); return; }
            renderSuspendCard(items);
            setCardStatus('suspend', 'loaded');
        });
    }

    function renderSuspendCard(items) {
        var body = document.querySelector('[data-card="suspend"] [data-card-body]');
        if (!body) return;

        // SuspendDDTO 字段：tradeDate / suspendTiming / suspendType（S=停牌，R=复牌）
        // 汇总：停牌次数（type=S 的条数）
        var suspendCount = items.filter(function (d) { return String(d.suspendType) === 'S'; }).length;

        var rows = '';
        items.slice(0, 50).forEach(function (d) {
            var typeText = String(d.suspendType) === 'S' ? '停牌' : (String(d.suspendType) === 'R' ? '复牌' : (d.suspendType || '--'));
            var typeCls = String(d.suspendType) === 'S' ? 'sd-fall' : (String(d.suspendType) === 'R' ? 'sd-rise' : '');
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.tradeDate)) + '</td>' +
                '<td><span class="' + typeCls + '">' + e(typeText) + '</span></td>' +
                '<td class="text-muted">' + e(d.suspendTiming || '全天') + '</td>' +
            '</tr>';
        });
        if (!rows) rows = emptyRow(3);

        body.innerHTML =
            '<div class="sd-risk-summary">' +
                '<span>停牌事件：<strong>' + suspendCount + '</strong> 次</span>' +
                '<span>记录总数：<strong>' + items.length + '</strong> 条</span>' +
            '</div>' +
            '<table class="sd-mini-table"><thead><tr>' +
                '<th style="width:110px">日期</th><th style="width:80px">类型</th><th>时段</th>' +
            '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ----- 卡片 3：ST 历史 -----
    function loadStCard() {
        setCardStatus('st', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/namechanges', { limit: 50 }, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('st', 'error');
                return;
            }
            var items = resp.data.items || [];
            // 过滤 name 或 changeReason 含 "ST"/"退市"
            var stItems = items.filter(function (d) {
                var name = (d.name || '').toUpperCase();
                var reason = (d.changeReason || '').toUpperCase();
                return name.indexOf('ST') >= 0 || reason.indexOf('ST') >= 0 ||
                       name.indexOf('退市') >= 0 || reason.indexOf('退市') >= 0;
            });
            if (!stItems.length) {
                var body = document.querySelector('[data-card="st"] [data-card-body]');
                if (body) body.innerHTML = '<p class="text-muted text-center py-3 mb-0">该股票无 ST 历史</p>';
                setCardStatus('st', 'loaded');
                return;
            }
            renderStCard(stItems);
            setCardStatus('st', 'loaded');
        });
    }

    function renderStCard(items) {
        var body = document.querySelector('[data-card="st"] [data-card-body]');
        if (!body) return;
        var rows = '';
        items.forEach(function (d) {
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.startDate)) + '</td>' +
                '<td>' + e(d.name || '--') + '</td>' +
                '<td class="text-muted">' + e(d.changeReason || '--') + '</td>' +
            '</tr>';
        });
        body.innerHTML = '<table class="sd-mini-table"><thead><tr>' +
            '<th style="width:110px">生效日</th><th style="width:160px">名称</th><th>原因</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ----- 卡片 4：股东增减持 -----
    function loadHoldertradeCard() {
        setCardStatus('holdertrade', 'loading');
        var code = state.stockCode;
        StockApp.get('/stocks/' + encodeURIComponent(code) + '/holder-trades', null, function (resp) {
            if (resp.code !== 200 || !resp.data || !resp.data.items) {
                setCardStatus('holdertrade', 'error');
                return;
            }
            var items = resp.data.items || [];
            if (!items.length) { setCardStatus('holdertrade', 'empty'); return; }
            renderHoldertradeCard(items);
            setCardStatus('holdertrade', 'loaded');
        });
    }

    function renderHoldertradeCard(items) {
        var body = document.querySelector('[data-card="holdertrade"] [data-card-body]');
        if (!body) return;

        // in_de: DE=减持，IN=增持
        var reduceList = items.filter(function (d) { return String(d.inDe).toUpperCase() === 'DE'; });
        var reduceCount = reduceList.length;
        var reduceTotal = 0;
        reduceList.forEach(function (d) {
            var v = num(Math.abs(d.changeVol));
            if (v !== null) reduceTotal += v;
        });

        var rows = '';
        items.slice(0, 50).forEach(function (d) {
            var inDe = String(d.inDe).toUpperCase();
            var inDeText = inDe === 'DE' ? '减持' : (inDe === 'IN' ? '增持' : (d.inDe || '--'));
            var inDeCls = inDe === 'DE' ? 'sd-fall' : (inDe === 'IN' ? 'sd-rise' : '');
            rows += '<tr>' +
                '<td>' + e(fmtDate(d.annDate)) + '</td>' +
                '<td>' + e(d.holderName || '--') + '</td>' +
                '<td><span class="' + inDeCls + '">' + e(inDeText) + '</span></td>' +
                '<td class="text-end ' + inDeCls + '">' + fmtVolume(d.changeVol) + '</td>' +
                '<td class="text-end">' + fmtVolume(d.afterShare) + '</td>' +
                '<td class="text-end">' + (num(d.afterRatio) !== null ? num(d.afterRatio).toFixed(2) + '%' : '--') + '</td>' +
            '</tr>';
        });

        body.innerHTML =
            '<div class="sd-risk-summary">' +
                '<span>减持笔数：<strong class="sd-fall">' + reduceCount + '</strong></span>' +
                '<span>累计减持量：<strong class="sd-fall">' + fmtVolume(reduceTotal) + '</strong> 股</span>' +
            '</div>' +
            '<table class="sd-mini-table"><thead><tr>' +
                '<th style="width:100px">公告日</th><th>股东</th><th style="width:70px">变动</th>' +
                '<th class="text-end" style="width:100px">变动股数</th><th class="text-end" style="width:110px">变动后持股</th>' +
                '<th class="text-end" style="width:90px">变动后比例</th>' +
            '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ==================== 通用：fetch JSON（带 contextPath） ====================

    function fetchJson(relativeUrl) {
        return new Promise(function (resolve, reject) {
            fetch(StockApp.contextPath + relativeUrl, { headers: { 'Accept': 'application/json' } })
                .then(function (r) { return r.json(); })
                .then(resolve)
                .catch(reject);
        });
    }

    // ==================== 初始化 ====================

    function bindHeaderEvents() {
        var watchBtn = document.getElementById('sdWatchlistBtn');
        if (watchBtn) watchBtn.addEventListener('click', onToggleWatchlist);
        var reminderBtn = document.getElementById('sdReminderBtn');
        if (reminderBtn) reminderBtn.addEventListener('click', onOpenReminder);

        var saveBtn = document.getElementById('reminderSaveBtn');
        if (saveBtn) saveBtn.addEventListener('click', onSaveReminder);
        var clearBtn = document.getElementById('reminderClearBtn');
        if (clearBtn) clearBtn.addEventListener('click', onClearReminder);
    }

    function onBeforeUnload() {
        // 销毁 KlineRenderer
        if (state.kline.renderer) {
            try { state.kline.renderer.dispose(); } catch (err) {}
            state.kline.renderer = null;
        }
        // 销毁所有 ECharts
        Object.keys(state.charts).forEach(function (key) { disposeChart(key); });
    }

    function onWindowResize() {
        Object.keys(state.charts).forEach(function (key) {
            try { state.charts[key].resize(); } catch (err) {}
        });
    }

    function init() {
        var page = document.getElementById('stockDetailPage');
        if (!page || page.dataset.stockExists !== 'true') return;
        state.stockCode = page.dataset.stockCode || '';

        bindHeaderEvents();
        bindTabEvents();
        bindKlineToolbar();
        bindHkRangeButtons();

        window.addEventListener('resize', onWindowResize);
        window.addEventListener('beforeunload', onBeforeUnload);

        // 顶部摘要立即加载（不依赖 Tab）
        loadSummary();

        // 初始 Tab
        activateTab(getRequestedTab(), false);
    }

    // 暴露调试入口
    window.StockDetailPage = {
        state: state,
        normalizeTab: normalizeTab,
        setSectionView: setSectionView,
        loadTab: loadTab,
        loadSummary: loadSummary,
        loadKlineTab: loadKlineTab,
        loadFundamentalTab: loadFundamentalTab,
        loadMoneyflowTab: loadMoneyflowTab,
        loadRiskTab: loadRiskTab
    };

    document.addEventListener('DOMContentLoaded', init);
}());
