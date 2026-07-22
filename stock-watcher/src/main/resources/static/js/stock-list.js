/**
 * 行情中心 (Market Center) - stock-list page logic
 *
 * Features:
 *  - 6 rank tabs (gainers/losers/turnover/amount/volume_ratio/amplitude)
 *  - Industry filter (SW L1) + market filter
 *  - 3-state column sorting (default dir -> reverse -> clear)
 *  - 14-column table with formatted metrics (incl. 振幅, required for sort + 振幅榜 tab)
 *  - Stock code links to /page/stock-detail/{tsCode}
 *  - SearchSuggest integration (jump to stock detail)
 *  - CSV export (UTF-8 BOM, escaped fields)
 */
(function () {
    'use strict';

    // ========== Constants ==========
    const PAGE_SIZE = 50;
    const CSV_EXPORT_SIZE = 500;
    const API_LIST = '/market/stock-list';
    const API_INDUSTRY = '/api/industry/list';

    const RANK_TABS = [
        { key: 'gainers',       label: '涨幅榜' },
        { key: 'losers',        label: '跌幅榜' },
        { key: 'turnover',      label: '换手率榜' },
        { key: 'amount',        label: '成交额榜' },
        { key: 'volume_ratio',  label: '量比榜' },
        { key: 'amplitude',     label: '振幅榜' }
    ];

    const RANK_LABEL = RANK_TABS.reduce((m, t) => { m[t.key] = t.label; return m; }, {});

    // Sortable columns: key -> default direction on first click.
    // PE/PB ascending (lower is cheaper), all others descending.
    const SORTABLE_COLS = {
        pctChg:       { label: '涨跌幅',   defaultDir: 'desc' },
        vol:          { label: '成交量',   defaultDir: 'desc' },
        amount:       { label: '成交额',   defaultDir: 'desc' },
        turnoverRate: { label: '换手率',   defaultDir: 'desc' },
        totalMv:      { label: '总市值',   defaultDir: 'desc' },
        peTtm:        { label: 'PE(TTM)', defaultDir: 'asc'  },
        pb:           { label: 'PB',      defaultDir: 'asc'  },
        volumeRatio:  { label: '量比',    defaultDir: 'desc' },
        amplitude:    { label: '振幅',    defaultDir: 'desc' }
    };

    const MARKET_OPTIONS = [
        { value: '',     label: '全部市场' },
        { value: '沪市',  label: '沪市' },
        { value: '深市',  label: '深市' },
        { value: '创业板', label: '创业板' },
        { value: '科创板', label: '科创板' },
        { value: '北交所', label: '北交所' }
    ];

    // ========== State ==========
    const state = {
        rankType: 'gainers',
        industryCode: '',
        market: '',
        sortBy: null,     // null = use rankType default
        order: null,      // null = use rankType default
        page: 1,
        size: PAGE_SIZE
    };

    // ========== DOM refs (filled in init) ==========
    let els = {};

    function setRankTitle() {
        if (els.rankTitle) {
            els.rankTitle.textContent = RANK_LABEL[state.rankType] || '行情榜单';
        }
    }

    // ========== Helpers ==========
    function buildParams(overrides) {
        const p = Object.assign({}, state, overrides || {});
        const params = {
            rankType: p.rankType,
            page: p.page,
            size: p.size
        };
        if (p.industryCode) params.industryCode = p.industryCode;
        if (p.market)       params.market = p.market;
        if (p.sortBy)       params.sortBy = p.sortBy;
        if (p.order)        params.order = p.order;
        return params;
    }

    function pad2(n) { return n < 10 ? '0' + n : '' + n; }
    function todayStr() {
        const d = new Date();
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
    }
    function todayCompact() {
        const d = new Date();
        return '' + d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate());
    }

    // ========== Init ==========
    document.addEventListener('DOMContentLoaded', function () {
        els = {
            tabs:        document.getElementById('rankTabs'),
            rankTitle:   document.getElementById('rankTitle'),
            industrySel: document.getElementById('industrySelect'),
            marketSel:   document.getElementById('marketSelect'),
            thead:       document.getElementById('stockListHead'),
            tbody:       document.getElementById('stockListBody'),
            pagination:  document.getElementById('pagination'),
            pageInfo:    document.getElementById('pageInfo'),
            pageInfoFooter: document.getElementById('pageInfoFooter'),
            dataDate:    document.getElementById('dataDate'),
            refreshBtn:  document.getElementById('refreshBtn'),
            exportBtn:   document.getElementById('exportBtn'),
            searchInput: document.getElementById('stockListSearchInput')
        };

        renderTabs();
        setRankTitle();
        renderMarketOptions();
        renderTableHead();
        bindEvents();
        initSearchSuggest();

        // Load industries (graceful degradation on failure)
        loadIndustries();

        // First list load
        loadList();
    });

    // ========== Rank tabs ==========
    function renderTabs() {
        if (!els.tabs) return;
        els.tabs.innerHTML = RANK_TABS.map(t =>
            '<li class="nav-item">' +
                '<a class="nav-link' + (t.key === state.rankType ? ' active' : '') + '" ' +
                   'href="javascript:;" data-tab="' + t.key + '">' + t.label + '</a>' +
            '</li>'
        ).join('');
    }

    function switchTab(tabKey) {
        if (state.rankType === tabKey) return;
        state.rankType = tabKey;
        state.sortBy = null;
        state.order = null;
        state.page = 1;
        if (els.tabs) {
            els.tabs.querySelectorAll('.nav-link').forEach(a => {
                a.classList.toggle('active', a.dataset.tab === tabKey);
            });
        }
        setRankTitle();
        renderTableHead();
        loadList();
    }

    // ========== Market filter ==========
    function renderMarketOptions() {
        if (!els.marketSel) return;
        els.marketSel.innerHTML = MARKET_OPTIONS.map(o =>
            '<option value="' + StockApp.escapeHtml(o.value) + '">' + StockApp.escapeHtml(o.label) + '</option>'
        ).join('');
    }

    // ========== Industry filter ==========
    function loadIndustries() {
        if (!els.industrySel) return;
        StockApp.get(API_INDUSTRY, { level: 1 }, function (resp) {
            if (!resp || resp.code !== 200 || !Array.isArray(resp.data)) {
                // graceful degradation: only "全部行业"
                return;
            }
            const options = ['<option value="">全部行业</option>'];
            resp.data.forEach(item => {
                if (!item || !item.industryCode) return;
                options.push(
                    '<option value="' + StockApp.escapeHtml(item.industryCode) + '">' +
                        StockApp.escapeHtml(item.industryName || item.industryCode) +
                    '</option>'
                );
            });
            els.industrySel.innerHTML = options.join('');
        });
    }

    // ========== Table head (with sort icons) ==========
    function renderTableHead() {
        if (!els.thead) return;
        // Column order (14 columns). 振幅 is included so the 振幅榜 tab shows
        // amplitude data and Task 10's "振幅(amplitude) sortable" requirement is met.
        els.thead.innerHTML = [
            '<tr>',
                '<th>代码</th>',
                '<th>名称</th>',
                '<th class="text-end">最新价</th>',
                thSortable('pctChg', '涨跌幅'),
                '<th class="text-end">涨跌额</th>',
                thSortable('amplitude', '振幅'),
                thSortable('vol', '成交量'),
                thSortable('amount', '成交额'),
                thSortable('totalMv', '总市值'),
                thSortable('peTtm', 'PE(TTM)'),
                thSortable('pb', 'PB'),
                thSortable('turnoverRate', '换手率'),
                thSortable('volumeRatio', '量比'),
                '<th>行业</th>',
            '</tr>'
        ].join('');
        updateSortIcons();
    }

    // Build a <th> for a sortable column.
    function thSortable(key, label) {
        return '<th class="text-end sortable-col" data-sort-by="' + key + '" title="点击排序">' +
            label + '<span class="sort-icon text-muted ms-1"></span>' +
        '</th>';
    }

    function updateSortIcons() {
        if (!els.thead) return;
        els.thead.querySelectorAll('.sortable-col').forEach(th => {
            const icon = th.querySelector('.sort-icon');
            if (!icon) return;
            if (state.sortBy && th.dataset.sortBy === state.sortBy) {
                icon.textContent = state.order === 'asc' ? '↑' : '↓';
                icon.classList.remove('text-muted');
            } else {
                icon.textContent = '';
                icon.classList.add('text-muted');
            }
        });
    }

    // 3-state sort click handler
    function handleSortClick(col) {
        const def = SORTABLE_COLS[col];
        if (!def) return;
        if (state.sortBy === col) {
            if (state.order === def.defaultDir) {
                // 2nd click: reverse
                state.order = def.defaultDir === 'asc' ? 'desc' : 'asc';
            } else {
                // 3rd click: clear (back to rankType default)
                state.sortBy = null;
                state.order = null;
            }
        } else {
            // 1st click on this column: default direction
            state.sortBy = col;
            state.order = def.defaultDir;
        }
        state.page = 1;
        updateSortIcons();
        loadList();
    }

    // ========== List loading ==========
    function loadList() {
        const params = buildParams();
        if (els.tbody) {
            els.tbody.innerHTML = '<tr><td colspan="14" class="text-center text-muted py-4">' +
                '<div class="spinner-border spinner-border-sm me-1"></div>加载中...</td></tr>';
        }
        StockApp.get(API_LIST, params, function (resp) {
            if (!resp || resp.code !== 200 || !resp.data) {
                if (els.tbody) {
                    els.tbody.innerHTML = '<tr><td colspan="14" class="text-center text-muted py-4">' +
                        (resp && resp.message ? StockApp.escapeHtml(resp.message) : '加载失败') + '</td></tr>';
                }
                return;
            }
            renderTable(resp.data);
            renderPagination(resp.data);
            updateDataDate(resp.data);
        });
    }

    function updateDataDate(pageResult) {
        if (!els.dataDate) return;
        // Prefer explicit tradeDate on PageResult if backend adds it later
        let dateStr = '';
        if (pageResult && pageResult.tradeDate) {
            dateStr = String(pageResult.tradeDate).substring(0, 10);
        } else if (pageResult && pageResult.list && pageResult.list.length) {
            const first = pageResult.list[0];
            if (first && first.tradeDate) {
                dateStr = String(first.tradeDate).substring(0, 10);
            }
        }
        if (!dateStr) dateStr = todayStr(); // approximation when backend doesn't provide it
        els.dataDate.textContent = '数据日期 ' + dateStr;
    }

    // ========== Table rendering ==========
    function renderTable(pageResult) {
        const list = (pageResult && pageResult.list) || [];
        if (!els.tbody) return;
        if (!list.length) {
            els.tbody.innerHTML = '<tr><td colspan="14" class="text-center text-muted py-4">无数据</td></tr>';
            return;
        }
        const e = StockApp.escapeHtml;
        els.tbody.innerHTML = list.map(s => renderRow(s, e)).join('');
    }

    function renderRow(s, e) {
        const tsCode = s.tsCode || '';
        const name  = s.name || '';
        const close = s.close;
        const pctChg = s.pctChg;
        const change = s.change;

        // Latest price
        const closeStr = StockApp.formatNumber(close, 2);

        // Pct change (colored) - spec 要求统一用 .stock-up/.stock-down
        const pctClass = (pctChg == null || pctChg === 0) ? '' : (pctChg > 0 ? 'stock-up' : 'stock-down');
        const pctStr = StockApp.formatPercent(pctChg, 2);

        // Change amount (colored, with sign)
        const changeClass = (change == null || change === 0) ? '' : (change > 0 ? 'stock-up' : 'stock-down');
        let changeStr = '-';
        if (change != null && !isNaN(change)) {
            changeStr = (change >= 0 ? '+' : '') + StockApp.formatNumber(change, 2);
        }

        // Amplitude (振幅, %) - always >= 0, no sign prefix
        let ampStr = '-';
        if (s.amplitude != null && !isNaN(s.amplitude)) {
            ampStr = Number(s.amplitude).toFixed(2) + '%';
        }

        // Volume (手) and amount (千元) - formatVolume handles 万/亿
        const volStr = StockApp.formatVolume(s.vol);
        const amountStr = StockApp.formatVolume(s.amount);

        // Total market value: totalMv is in 万元 -> show as 亿
        let mvStr = '-';
        if (s.totalMv != null && !isNaN(s.totalMv)) {
            mvStr = (Number(s.totalMv) / 10000).toFixed(2) + ' 亿';
        }

        // PE/PB: null or negative -> '-'
        let peStr = '-';
        if (s.peTtm != null && !isNaN(s.peTtm) && Number(s.peTtm) > 0) {
            peStr = StockApp.formatNumber(s.peTtm, 2);
        }
        let pbStr = '-';
        if (s.pb != null && !isNaN(s.pb) && Number(s.pb) > 0) {
            pbStr = StockApp.formatNumber(s.pb, 2);
        }

        // Turnover rate (already %)
        const turnoverStr = StockApp.formatPercent(s.turnoverRate, 2);

        // Volume ratio (ratio like 1.23)
        let vrStr = '-';
        if (s.volumeRatio != null && !isNaN(s.volumeRatio)) {
            vrStr = StockApp.formatNumber(s.volumeRatio, 2);
        }

        // Industry
        const industryStr = s.industryName ? s.industryName : '-';

        // Stock code link to detail page
        const codeCell = '<a class="stock-code-link" href="' +
            StockApp.contextPath + '/page/stock-detail/' + encodeURIComponent(tsCode) + '">' +
            e(tsCode) + '</a>';

        return '<tr>' +
            '<td><code>' + codeCell + '</code></td>' +
            '<td class="fw-medium">' + e(name) + '</td>' +
            '<td class="text-end">' + closeStr + '</td>' +
            '<td class="text-end ' + pctClass + ' fw-medium">' + pctStr + '</td>' +
            '<td class="text-end ' + changeClass + '">' + changeStr + '</td>' +
            '<td class="text-end">' + ampStr + '</td>' +
            '<td class="text-end">' + volStr + '</td>' +
            '<td class="text-end">' + amountStr + '</td>' +
            '<td class="text-end">' + mvStr + '</td>' +
            '<td class="text-end">' + peStr + '</td>' +
            '<td class="text-end">' + pbStr + '</td>' +
            '<td class="text-end">' + turnoverStr + '</td>' +
            '<td class="text-end">' + vrStr + '</td>' +
            '<td>' + e(industryStr) + '</td>' +
        '</tr>';
    }

    // ========== Pagination ==========
    function renderPagination(pageResult) {
        const total = (pageResult && pageResult.total) || 0;
        const size  = (pageResult && pageResult.size)  || state.size;
        const page  = (pageResult && pageResult.page)  || 1;
        const totalPages = Math.max(1, Math.ceil(total / size));

        const totalText = '共 ' + total + ' 条';
        if (els.pageInfo)        els.pageInfo.textContent = totalText;
        if (els.pageInfoFooter)  els.pageInfoFooter.textContent = totalText;

        if (!els.pagination) return;
        if (totalPages <= 1) { els.pagination.innerHTML = ''; return; }

        // Compact pager: prev, [1..N with ellipsis], next (max 7 visible)
        const maxVisible = 7;
        let pages = [];
        if (totalPages <= maxVisible) {
            for (let i = 1; i <= totalPages; i++) pages.push(i);
        } else {
            pages.push(1);
            const left = Math.max(2, page - 2);
            const right = Math.min(totalPages - 1, page + 2);
            if (left > 2) pages.push('...');
            for (let i = left; i <= right; i++) pages.push(i);
            if (right < totalPages - 1) pages.push('...');
            pages.push(totalPages);
        }

        let html = '';
        html += '<li class="page-item ' + (page <= 1 ? 'disabled' : '') + '">' +
                    '<a class="page-link" href="javascript:;" data-page="' + (page - 1) + '">上一页</a>' +
                '</li>';
        pages.forEach(p => {
            if (p === '...') {
                html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
            } else {
                html += '<li class="page-item ' + (p === page ? 'active' : '') + '">' +
                            '<a class="page-link" href="javascript:;" data-page="' + p + '">' + p + '</a>' +
                        '</li>';
            }
        });
        html += '<li class="page-item ' + (page >= totalPages ? 'disabled' : '') + '">' +
                    '<a class="page-link" href="javascript:;" data-page="' + (page + 1) + '">下一页</a>' +
                '</li>';
        els.pagination.innerHTML = html;
    }

    function goToPage(p) {
        state.page = p;
        loadList();
    }

    // ========== Event binding ==========
    function bindEvents() {
        // Rank tabs
        if (els.tabs) {
            els.tabs.addEventListener('click', function (e) {
                const a = e.target.closest('.nav-link[data-tab]');
                if (a) switchTab(a.dataset.tab);
            });
        }

        // Industry dropdown
        if (els.industrySel) {
            els.industrySel.addEventListener('change', function () {
                state.industryCode = this.value || '';
                state.page = 1;
                loadList();
            });
        }

        // Market dropdown
        if (els.marketSel) {
            els.marketSel.addEventListener('change', function () {
                state.market = this.value || '';
                state.page = 1;
                loadList();
            });
        }

        // Sortable column headers (delegated)
        if (els.thead) {
            els.thead.addEventListener('click', function (e) {
                const th = e.target.closest('.sortable-col[data-sort-by]');
                if (th) handleSortClick(th.dataset.sortBy);
            });
        }

        // Pagination (delegated)
        if (els.pagination) {
            els.pagination.addEventListener('click', function (e) {
                const a = e.target.closest('a.page-link[data-page]');
                if (!a) return;
                const li = a.closest('.page-item');
                if (li && li.classList.contains('disabled')) return;
                const p = parseInt(a.dataset.page, 10);
                if (isNaN(p)) return;
                goToPage(p);
            });
        }

        // Refresh
        if (els.refreshBtn) {
            els.refreshBtn.addEventListener('click', function () {
                loadList();
            });
        }

        // Export
        if (els.exportBtn) {
            els.exportBtn.addEventListener('click', exportCsv);
        }
    }

    // ========== SearchSuggest ==========
    function initSearchSuggest() {
        if (!els.searchInput || typeof SearchSuggest === 'undefined') return;
        new SearchSuggest(els.searchInput, {
            onSelect: function (item) {
                if (item && item.tsCode) {
                    window.location.href = StockApp.contextPath + '/page/stock-detail/' + encodeURIComponent(item.tsCode);
                }
            }
        });
    }

    // ========== CSV export ==========
    function buildCsvFilename() {
        const rank = RANK_LABEL[state.rankType] || state.rankType;
        const industryText = state.industryCode
            ? (currentIndustryName(state.industryCode) || state.industryCode)
            : '全部';
        const marketText = state.market || '全部';
        return '行情中心_' + rank + '_' + industryText + '_' + marketText + '_' + todayCompact() + '.csv';
    }

    // Look up currently-loaded industry name from the dropdown
    function currentIndustryName(code) {
        if (!els.industrySel) return '';
        const opt = els.industrySel.querySelector('option[value="' + code + '"]');
        return opt ? opt.textContent : '';
    }

    function csvField(val) {
        if (val == null) return '';
        const s = String(val);
        if (/[",\n\r]/.test(s)) {
            return '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
    }

    // Format a value for CSV according to its key (mirrors table rendering, but plain numbers)
    function csvValue(s, key) {
        const v = s[key];
        switch (key) {
            case 'close':
                return (v == null || isNaN(v)) ? '' : Number(v).toFixed(2);
            case 'pctChg':
            case 'turnoverRate':
                return (v == null || isNaN(v)) ? '' : Number(v).toFixed(2);
            case 'change':
                if (v == null || isNaN(v)) return '';
                return (v >= 0 ? '+' : '') + Number(v).toFixed(2);
            case 'vol':
            case 'amount':
                return (v == null || isNaN(v)) ? '' : Number(v).toFixed(2);
            case 'totalMv':
                // totalMv is in 万元 -> output 亿元 (2 decimals)
                return (v == null || isNaN(v)) ? '' : (Number(v) / 10000).toFixed(2);
            case 'peTtm':
            case 'pb':
                return (v == null || isNaN(v) || Number(v) <= 0) ? '' : Number(v).toFixed(2);
            case 'volumeRatio':
                return (v == null || isNaN(v)) ? '' : Number(v).toFixed(2);
            case 'amplitude':
                return (v == null || isNaN(v)) ? '' : Number(v).toFixed(2);
            default:
                return v == null ? '' : v;
        }
    }

    function exportCsv() {
        if (!els.exportBtn) return;
        if (els.exportBtn.disabled) return; // prevent double-click

        const btn = els.exportBtn;
        const originalHtml = btn.innerHTML;
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>导出中...';

        let done = false;
        const reset = function () {
            if (done) return;
            done = true;
            btn.disabled = false;
            btn.innerHTML = originalHtml;
        };
        const safety = setTimeout(reset, 30000); // safety net for network errors

        const params = buildParams({ page: 1, size: CSV_EXPORT_SIZE });
        StockApp.get(API_LIST, params, function (resp) {
            clearTimeout(safety);
            if (!resp || resp.code !== 200 || !resp.data || !Array.isArray(resp.data.list)) {
                StockApp.toast((resp && resp.message) || '导出失败，请重试', 'danger');
                reset();
                return;
            }
            try {
                const list = resp.data.list;
                const headers = ['代码','名称','最新价','涨跌幅(%)','涨跌额','振幅(%)','成交量(手)','成交额(千元)','总市值(亿元)','PE(TTM)','PB','换手率(%)','量比','行业'];
                const keys = ['tsCode','name','close','pctChg','change','amplitude','vol','amount','totalMv','peTtm','pb','turnoverRate','volumeRatio','industryName'];
                const headerRow = headers.map(csvField).join(',');
                const rows = list.map(s => keys.map(k => csvField(csvValue(s, k))).join(','));
                const csv = '\uFEFF' + headerRow + '\n' + rows.join('\n');

                const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = buildCsvFilename();
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);

                StockApp.toast('导出成功，共 ' + list.length + ' 条', 'success');
            } catch (err) {
                StockApp.toast('导出失败：' + (err && err.message ? err.message : '未知错误'), 'danger');
            } finally {
                reset();
            }
        });
    }
})();
