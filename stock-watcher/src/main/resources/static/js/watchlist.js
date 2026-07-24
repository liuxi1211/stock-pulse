/**
 * 自选股页面逻辑（重构版）。
 *
 * 与旧版的主要差异：
 *  1. 渲染目标结构改为 .wl- 命名空间，匹配 watchlist.css
 *  2. 修复批量按钮重复 bug：统一一个 #batchModeBtn + 新增 #batchExitBtn
 *  3. 修复全选混乱：#selectAllCheckbox（batch bar）与 #tableSelectAll（表头）始终联动
 *  4. refreshPrices 时不再整体 renderTable（不销毁 mini-kline），
 *     仅就地更新数字 + 加 .flash 微动 + 同步脉冲指示器强度档
 *  5. 新增统计条数据更新（自选总数 / 今日上涨 / 今日下跌 / 价格提醒数）
 *  6. 新增「盘口脉冲指示器」强度档计算（strong / medium / weak / flat）
 *
 * 接口契约不变：
 *  - GET    /watchlist
 *  - GET/POST/PUT/DELETE /watchlist/groups
 *  - POST   /watchlist/{code}
 *  - POST   /watchlist/{code}/delete
 *  - POST   /watchlist/batch-delete
 *  - PUT    /watchlist/{code}/group?groupId=
 *  - POST   /watchlist/batch-move-group
 *  - POST   /watchlist/{code}/reminder
 *  - DELETE /watchlist/{code}/reminder
 *  - PUT    /watchlist/{code}/sort?sortOrder=
 */
const WatchlistPage = (function () {
    'use strict';

    const e = StockApp.escapeHtml;

    const state = {
        list: [],
        groups: [],
        currentGroupId: null,
        sortBy: 'pct_chg',
        order: 'desc',
        selectedCodes: new Set(),
        batchMode: false,
        reminderPollingTimer: null,
        chartInstances: new Map(),
        lastReminderTimes: new Map(),
        searchSuggest: null,
        currentReminderStock: null,
        moveGroupStockCodes: [],
        lastPriceMap: new Map(),
        dragState: {
            draggingCode: null,
            originalList: null,
            dropTargetCode: null,
            dropPosition: null,
        },
    };

    // ===================== 工具方法 =====================

    function formatNumber(num, decimals) {
        if (num === null || num === undefined || isNaN(num)) return '-';
        const d = decimals != null ? decimals : 2;
        return Number(num).toLocaleString('zh-CN', {
            minimumFractionDigits: d,
            maximumFractionDigits: d
        });
    }

    function formatPercent(num) {
        if (num === null || num === undefined || isNaN(num)) return '-';
        const val = Number(num);
        const sign = val > 0 ? '+' : '';
        return sign + val.toFixed(2) + '%';
    }

    function formatMarketValue(totalMv) {
        if (totalMv === null || totalMv === undefined || isNaN(totalMv)) return '-';
        const v = Number(totalMv);
        if (v >= 10000) return (v / 10000).toFixed(2) + '亿';
        return v.toFixed(2) + '万';
    }

    function formatPePb(val) {
        if (val === null || val === undefined || isNaN(val) || val <= 0) return '-';
        return Number(val).toFixed(2);
    }

    function formatTurnoverRate(val) {
        if (val === null || val === undefined || isNaN(val)) return '-';
        return Number(val).toFixed(2) + '%';
    }

    /**
     * 计算盘口脉冲强度档（红涨绿跌）。
     * 返回 { dir, level }：dir ∈ rise/fall/flat，level ∈ strong/medium/weak/flat
     */
    function pulseLevel(changePercent) {
        if (changePercent === null || changePercent === undefined || isNaN(changePercent) || changePercent === 0) {
            return { dir: 'flat', level: 'flat' };
        }
        const abs = Math.abs(Number(changePercent));
        let level = 'weak';
        if (abs >= 5) level = 'strong';
        else if (abs >= 1) level = 'medium';
        return { dir: changePercent > 0 ? 'rise' : 'fall', level: level };
    }

    function pctCellClass(changePercent) {
        if (changePercent === null || changePercent === undefined || isNaN(changePercent) || changePercent === 0) {
            return 'flat';
        }
        return changePercent > 0 ? 'rise' : 'fall';
    }

    // ===================== 初始化 =====================

    function init() {
        bindRefreshBtn();
        bindGroupBtns();
        bindSortControls();
        bindBatchControls();
        bindSearchSuggest();
        bindReminderModal();
        bindMoveGroupModal();
        loadGroups();
        loadList();
        bindBeforeUnload();
        startReminderPolling();
    }

    function bindRefreshBtn() {
        const btn = document.getElementById('refreshWatchlistBtn');
        if (btn) {
            btn.addEventListener('click', function () {
                loadList(true);
            });
        }
    }

    function bindGroupBtns() {
        const addBtn = document.getElementById('addGroupBtn');
        const addBtnTop = document.getElementById('addGroupBtnTop');
        if (addBtn) addBtn.addEventListener('click', createGroup);
        if (addBtnTop) addBtnTop.addEventListener('click', createGroup);
    }

    function bindSortControls() {
        const sortSel = document.getElementById('sortBySelect');
        const orderBtn = document.getElementById('orderToggleBtn');
        if (sortSel) {
            sortSel.value = state.sortBy;
            sortSel.addEventListener('change', function () {
                state.sortBy = this.value;
                loadList();
            });
        }
        if (orderBtn) {
            orderBtn.addEventListener('click', function () {
                state.order = state.order === 'desc' ? 'asc' : 'desc';
                updateOrderIcon();
                loadList();
            });
        }
    }

    function updateOrderIcon() {
        const icon = document.getElementById('orderIcon');
        if (!icon) return;
        icon.className = state.order === 'desc' ? 'bi bi-sort-down-alt' : 'bi bi-sort-up-alt';
    }

    function bindBatchControls() {
        const batchBtn = document.getElementById('batchModeBtn');
        if (batchBtn) batchBtn.addEventListener('click', toggleBatchMode);

        const exitBtn = document.getElementById('batchExitBtn');
        if (exitBtn) exitBtn.addEventListener('click', function () { toggleBatchMode(); });

        const selectAllCb = document.getElementById('selectAllCheckbox');
        const tableSelectAll = document.getElementById('tableSelectAll');
        if (selectAllCb) selectAllCb.addEventListener('change', toggleSelectAll);
        if (tableSelectAll) tableSelectAll.addEventListener('change', toggleSelectAll);

        const batchRemoveBtn = document.getElementById('batchRemoveBtn');
        const batchMoveBtn = document.getElementById('batchMoveBtn');
        if (batchRemoveBtn) batchRemoveBtn.addEventListener('click', batchRemove);
        if (batchMoveBtn) batchMoveBtn.addEventListener('click', openBatchMoveModal);
    }

    function bindSearchSuggest() {
        const input = document.getElementById('searchInput');
        if (!input) return;
        state.searchSuggest = new SearchSuggest(input, {
            onSelect: function (item) {
                addStockToWatchlist(item.code);
                input.value = '';
            }
        });
    }

    function bindReminderModal() {
        const saveBtn = document.getElementById('saveReminderBtn');
        const clearBtn = document.getElementById('clearReminderBtn');
        if (saveBtn) saveBtn.addEventListener('click', saveReminder);
        if (clearBtn) clearBtn.addEventListener('click', clearReminder);
    }

    function bindMoveGroupModal() {
        const confirmBtn = document.getElementById('confirmMoveBtn');
        if (confirmBtn) confirmBtn.addEventListener('click', confirmMoveGroup);
    }

    function bindBeforeUnload() {
        window.addEventListener('beforeunload', function () {
            stopReminderPolling();
            disposeAllCharts();
        });
    }

    // ===================== 分组管理 =====================

    function loadGroups() {
        StockApp.get('/watchlist/groups', null, function (resp) {
            if (resp.code !== 200) return;
            state.groups = resp.data || [];
            renderGroups();
            updateCurrentGroupTitle();
        });
    }

    function renderGroups() {
        const listEl = document.getElementById('groupList');
        if (!listEl) return;

        const totalCount = state.list.length || 0;
        const ungroupedCount = state.list.filter(function (s) { return !s.groupId; }).length;

        let html = '';
        html += buildGroupItem('all', '全部', totalCount, state.currentGroupId === null, 'bi-star-fill');
        html += buildGroupItem('ungrouped', '未分组', ungroupedCount, state.currentGroupId === 'ungrouped', 'bi-folder2-open');

        if (state.groups.length) {
            html += '<div class="wl-group-divider"><span>我的分组</span></div>';
            state.groups.forEach(function (g) {
                const count = state.list.filter(function (s) {
                    return String(s.groupId) === String(g.id);
                }).length;
                html += buildCustomGroupItem(g, count);
            });
        }

        listEl.innerHTML = html;

        requestAnimationFrame(function () {
            bindGroupDropEvents();
        });
    }

    function buildGroupItem(id, name, count, active, iconClass) {
        return '<div class="wl-group-item' + (active ? ' active' : '') + '" data-group-id="' + e(id) + '"' +
            ' role="tab" tabindex="0"' +
            ' onclick="WatchlistPage.selectGroup(\'' + e(id) + '\')"' +
            ' onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();WatchlistPage.selectGroup(\'' + e(id) + '\')}">' +
            '<span class="wl-group-name">' +
                '<i class="bi ' + iconClass + ' wl-group-icon"></i>' +
                '<span>' + e(name) + '</span>' +
            '</span>' +
            '<span class="wl-group-count">' + count + '</span>' +
            '</div>';
    }

    function buildCustomGroupItem(group, count) {
        const active = state.currentGroupId && String(state.currentGroupId) === String(group.id);
        return '<div class="wl-group-item wl-group-item-custom' + (active ? ' active' : '') + '" data-group-id="' + e(group.id) + '"' +
            ' role="tab" tabindex="0"' +
            ' onclick="WatchlistPage.selectGroup(\'' + e(group.id) + '\')"' +
            ' ondblclick="WatchlistPage.startRenameGroup(\'' + e(group.id) + '\', event)"' +
            ' onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();WatchlistPage.selectGroup(\'' + e(group.id) + '\')}">' +
            '<span class="wl-group-name">' +
                '<i class="bi bi-folder2 wl-group-icon"></i>' +
                '<span class="wl-group-name-text">' + e(group.name) + '</span>' +
            '</span>' +
            '<span class="wl-group-actions">' +
                '<span class="wl-group-count">' + count + '</span>' +
                '<button class="wl-group-del" title="删除分组" aria-label="删除分组"' +
                    ' onclick="event.stopPropagation();WatchlistPage.deleteGroup(\'' + e(group.id) + '\')">' +
                    '<i class="bi bi-trash3"></i>' +
                '</button>' +
            '</span>' +
            '</div>';
    }

    function selectGroup(groupId) {
        if (groupId === 'all') {
            state.currentGroupId = null;
        } else if (groupId === 'ungrouped') {
            state.currentGroupId = 'ungrouped';
        } else {
            state.currentGroupId = groupId;
        }
        state.selectedCodes.clear();
        updateCurrentGroupTitle();
        renderGroups();
        loadList();
        resetReminderPolling();
    }

    function updateCurrentGroupTitle() {
        const titleEl = document.getElementById('currentGroupTitle');
        const tagEl = document.getElementById('wlTagGroupName');
        let label = '全部';
        if (state.currentGroupId === null) {
            label = '全部';
        } else if (state.currentGroupId === 'ungrouped') {
            label = '未分组';
        } else {
            const g = state.groups.find(function (x) { return String(x.id) === String(state.currentGroupId); });
            label = g ? g.name : '自选股';
        }
        if (titleEl) titleEl.textContent = label;
        if (tagEl) tagEl.textContent = label;
    }

    async function createGroup() {
        const name = await StockApp.prompt({
            title: '新建分组',
            message: '请输入分组名称：',
            placeholder: '分组名称',
            confirmText: '创建',
            confirmClass: 'btn-primary',
            required: true,
            icon: 'bi-folder-plus',
        });
        if (!name || !name.trim()) return;

        StockApp.post('/watchlist/groups?groupName=' + encodeURIComponent(name.trim()), null, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
            if (resp.code === 200) loadGroups();
        });
    }

    function startRenameGroup(groupId, event) {
        if (event) event.preventDefault();
        const item = document.querySelector('.wl-group-item[data-group-id="' + groupId + '"]');
        if (!item) return;

        const nameEl = item.querySelector('.wl-group-name-text');
        if (!nameEl) return;

        const currentName = nameEl.textContent;
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'form-control form-control-sm wl-group-rename';
        input.value = currentName;
        input.setAttribute('aria-label', '重命名分组');

        nameEl.style.display = 'none';
        nameEl.parentNode.insertBefore(input, nameEl);
        input.focus();
        input.select();

        function finish() {
            const newName = input.value.trim();
            if (newName && newName !== currentName) {
                renameGroup(groupId, newName);
            } else {
                nameEl.style.display = '';
                input.remove();
            }
        }

        input.addEventListener('blur', finish);
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                input.blur();
            } else if (e.key === 'Escape') {
                nameEl.style.display = '';
                input.remove();
            }
        });
    }

    function renameGroup(groupId, newName) {
        fetch(StockApp.contextPath + '/watchlist/groups/' + groupId + '?groupName=' + encodeURIComponent(newName), {
            method: 'PUT',
            headers: {'Accept': 'application/json'}
        })
            .then(r => r.json())
            .then(function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                loadGroups();
            })
            .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
    }

    async function deleteGroup(groupId) {
        if (!await StockApp.confirm({
            title: '删除分组',
            message: '确认删除该分组？分组内的股票不会被删除，将移至未分组。',
            confirmText: '删除',
            confirmClass: 'btn-danger',
            icon: 'bi-trash'
        })) return;

        fetch(StockApp.contextPath + '/watchlist/groups/' + groupId, {
            method: 'DELETE',
            headers: {'Accept': 'application/json'}
        })
            .then(r => r.json())
            .then(function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) {
                    if (state.currentGroupId && String(state.currentGroupId) === String(groupId)) {
                        state.currentGroupId = null;
                        updateCurrentGroupTitle();
                    }
                    loadGroups();
                    loadList();
                }
            })
            .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
    }

    // ===================== 列表加载与渲染 =====================

    function disposeAllCharts() {
        state.chartInstances.forEach(function (chart) {
            try { chart.dispose(); } catch (err) {}
        });
        state.chartInstances.clear();
    }

    function renderMiniKline(containerEl, item) {
        if (!containerEl || !item) return;

        const code = item.code;
        const closeSeries = item.closeSeries;

        if (!closeSeries || !Array.isArray(closeSeries) || closeSeries.length < 2) {
            containerEl.innerHTML = '<span class="wl-spark-placeholder">-</span>';
            return;
        }

        const data = closeSeries.slice(-30);
        const firstVal = data[0];
        const lastVal = data[data.length - 1];
        const isRise = lastVal >= firstVal;

        const riseColor = getComputedStyle(document.documentElement).getPropertyValue('--rise-color').trim() || '#ef4444';
        const fallColor = getComputedStyle(document.documentElement).getPropertyValue('--fall-color').trim() || '#10b981';
        const lineColor = isRise ? riseColor : fallColor;

        if (state.chartInstances.has(code)) {
            try { state.chartInstances.get(code).dispose(); } catch (err) {}
            state.chartInstances.delete(code);
        }

        const chart = echarts.init(containerEl);
        state.chartInstances.set(code, chart);

        const areaGradient = ChartsTheme.getAreaGradient(chart, lineColor);

        chart.setOption({
            backgroundColor: 'transparent',
            grid: { left: 0, right: 0, top: 2, bottom: 2, containLabel: false },
            xAxis: { type: 'category', show: false, data: data.map(function (_, i) { return i; }) },
            yAxis: { type: 'value', show: false, scale: true },
            tooltip: { show: false },
            legend: { show: false },
            series: [{
                type: 'line',
                data: data,
                smooth: true,
                symbol: 'none',
                lineStyle: { color: lineColor, width: 1.5 },
                areaStyle: { color: areaGradient }
            }]
        });

        ChartsTheme.register(chart, 'echarts', function (entry) {
            try {
                const newRise = getComputedStyle(document.documentElement).getPropertyValue('--rise-color').trim() || '#ef4444';
                const newFall = getComputedStyle(document.documentElement).getPropertyValue('--fall-color').trim() || '#10b981';
                const newColor = isRise ? newRise : newFall;
                const newGradient = ChartsTheme.getAreaGradient(entry.instance, newColor);
                entry.instance.setOption({
                    series: [{
                        lineStyle: { color: newColor },
                        areaStyle: { color: newGradient }
                    }]
                });
            } catch (e) {
                console.warn('[ChartsTheme] mini kline repaint failed', e);
            }
        });
    }

    function initAllMiniKlines() {
        if (!state.list || !state.list.length) return;

        const maxRender = 50;

        state.list.forEach(function (item, index) {
            const container = document.querySelector('.wl-spark[data-code="' + item.code + '"]');
            if (!container) return;

            if (index >= maxRender) {
                container.innerHTML = '<span class="wl-spark-placeholder">-</span>';
                return;
            }

            setTimeout(function () {
                if (document.querySelector('.wl-spark[data-code="' + item.code + '"]')) {
                    renderMiniKline(container, item);
                }
            }, index * 10);
        });
    }

    function loadList(manualRefresh) {
        const params = {};
        if (state.currentGroupId && state.currentGroupId !== 'ungrouped') {
            params.groupId = state.currentGroupId;
        } else if (state.currentGroupId === 'ungrouped') {
            params.groupId = '';
        }
        if (state.sortBy) params.sortBy = state.sortBy;
        if (state.order) params.order = state.order;

        if (manualRefresh) {
            const tbody = document.getElementById('watchlistTable');
            if (tbody && !state.list.length) {
                tbody.innerHTML = '<tr><td colspan="9" class="wl-loading">正在拉取行情…</td></tr>';
            }
        }

        StockApp.get('/watchlist', params, function (resp) {
            if (resp.code !== 200) return;
            state.list = resp.data || [];

            // 价格快照，用于刷新时比较与微动效
            state.lastPriceMap.clear();
            state.list.forEach(function (s) {
                state.lastPriceMap.set(s.code, s.currentPrice);
            });

            state.selectedCodes.clear();
            updateSelectedCount();
            renderTable();
            renderGroups();
            updateStatStrip();
        });
    }

    function updateStatStrip() {
        const total = state.list.length;
        const riseCount = state.list.filter(function (s) { return s.changePercent > 0; }).length;
        const fallCount = state.list.filter(function (s) { return s.changePercent < 0; }).length;
        const alertCount = state.list.filter(function (s) {
            return (s.targetPriceHigh != null && s.targetPriceHigh > 0) ||
                   (s.targetPriceLow != null && s.targetPriceLow > 0);
        }).length;

        setStatValue('wlStatTotal', total, '只');
        setStatValue('wlStatRise', riseCount, '只');
        setStatValue('wlStatFall', fallCount, '只');
        setStatValue('wlStatAlert', alertCount, '只');

        const footEl = document.getElementById('wlStatFoot');
        if (footEl) {
            if (!total) {
                footEl.textContent = '尚未添加自选股';
            } else if (riseCount > fallCount) {
                footEl.textContent = '红盘占多';
            } else if (fallCount > riseCount) {
                footEl.textContent = '绿盘占多';
            } else {
                footEl.textContent = '红绿相当';
            }
        }

        const alertFoot = document.getElementById('wlStatAlertFoot');
        if (alertFoot) {
            alertFoot.textContent = alertCount > 0
                ? '已设置高位或低位提醒'
                : '尚无设置任何提醒';
        }
    }

    function setStatValue(id, val, unit) {
        const el = document.getElementById(id);
        if (!el) return;
        el.innerHTML = val + '<span class="unit">' + unit + '</span>';
    }

    function renderTable() {
        const tbody = document.getElementById('watchlistTable');
        if (!tbody) return;

        disposeAllCharts();

        if (!state.list.length) {
            tbody.innerHTML =
                '<tr><td colspan="9">' +
                '<div class="wl-empty">' +
                    '<i class="bi bi-star"></i>' +
                    '<h5>自选股列表为空</h5>' +
                    '<p>在上方搜索框输入股票代码或名称，加入自选</p>' +
                '</div></td></tr>';
            return;
        }

        tbody.innerHTML = state.list.map(function (s) {
            return buildRow(s);
        }).join('');

        requestAnimationFrame(function () {
            initAllMiniKlines();
            bindRowDragEvents();
            syncBatchColumnVisibility();
        });
    }

    function buildRow(s) {
        const code = s.code;
        const pulse = pulseLevel(s.changePercent);
        const pctCls = pctCellClass(s.changePercent);
        const hasReminder = (s.targetPriceHigh != null && s.targetPriceHigh > 0) ||
                            (s.targetPriceLow != null && s.targetPriceLow > 0);
        const isSelected = state.selectedCodes.has(code);

        return '<tr data-code="' + e(code) + '"' + (isSelected ? ' class="row-selected"' : '') + '>' +
            // 批量列（默认隐藏，批量模式下显示）
            '<td class="wl-col-check" ' + (state.batchMode ? '' : 'hidden') + '>' +
                '<input type="checkbox" ' + (isSelected ? 'checked' : '') +
                    ' aria-label="选择 ' + e(code) + '"' +
                    ' onchange="WatchlistPage.toggleSelectOne(\'' + e(code) + '\')">' +
            '</td>' +
            // 股票（脉冲指示器 + 代码 + 名称 + 行业）
            '<td class="wl-col-stock">' +
                '<div class="wl-stock-cell">' +
                    '<span class="wl-pulse ' + pulse.dir + ' ' + pulse.level + '" data-code="' + e(code) + '" aria-hidden="true"></span>' +
                    '<div class="wl-stock-info">' +
                        '<div class="wl-stock-line1">' +
                            '<a href="/stock/' + e(code) + '" class="wl-stock-code" target="_blank" rel="noopener">' + e(code) + '</a>' +
                            '<a href="/stock/' + e(code) + '" class="wl-stock-name" target="_blank" rel="noopener">' + e(s.name) + '</a>' +
                        '</div>' +
                        (s.industryName
                            ? '<span class="wl-stock-industry">' + e(s.industryName) + '</span>'
                            : '') +
                    '</div>' +
                '</div>' +
            '</td>' +
            // 最新价
            '<td class="text-end"><span class="wl-num" data-field="currentPrice">' + formatNumber(s.currentPrice) + '</span></td>' +
            // 涨跌幅（涨跌额并入）
            '<td class="text-end">' +
                '<span class="wl-pct-cell ' + pctCls + '" data-field="changePercent">' +
                    formatPercent(s.changePercent) +
                '</span>' +
                '<div class="wl-change-amount" data-field="changeAmount" style="font-size:11px;color:var(--text-muted);margin-top:2px;">' +
                    (s.changeAmount != null
                        ? (s.changeAmount >= 0 ? '+' : '') + formatNumber(s.changeAmount)
                        : '-') +
                '</div>' +
            '</td>' +
            // 总市值
            '<td class="text-end"><span class="wl-num" data-field="totalMv">' + formatMarketValue(s.totalMv) + '</span></td>' +
            // PE TTM
            '<td class="text-end"><span class="wl-num" data-field="peTtm">' + formatPePb(s.peTtm) + '</span></td>' +
            // 换手率
            '<td class="text-end"><span class="wl-num" data-field="turnoverRate">' + formatTurnoverRate(s.turnoverRate) + '</span></td>' +
            // 7日走势
            '<td class="text-center wl-col-spark">' +
                '<div class="wl-spark" data-code="' + e(code) + '"></div>' +
            '</td>' +
            // 操作
            '<td class="text-center wl-col-ops">' +
                '<div class="wl-row-ops">' +
                    '<button class="wl-reminder-btn' + (hasReminder ? ' active' : '') + '"' +
                        ' title="' + (hasReminder ? '已设置提醒，点击修改' : '设置价格提醒') + '"' +
                        ' aria-label="价格提醒"' +
                        ' onclick="WatchlistPage.openReminderModal(\'' + e(code) + '\',' +
                            (s.targetPriceHigh != null ? s.targetPriceHigh : 'null') + ',' +
                            (s.targetPriceLow != null ? s.targetPriceLow : 'null') + ')">' +
                        '<i class="bi ' + (hasReminder ? 'bi-bell-fill' : 'bi-bell') + '"></i>' +
                    '</button>' +
                    '<button class="btn btn-sm btn-outline-secondary wl-ops-more"' +
                        ' title="更多操作" aria-label="更多操作" data-bs-toggle="dropdown" aria-expanded="false">' +
                        '<i class="bi bi-three-dots"></i>' +
                    '</button>' +
                    '<ul class="dropdown-menu dropdown-menu-end">' +
                        '<li><a class="dropdown-item" href="/stock/' + e(code) + '" target="_blank" rel="noopener">' +
                            '<i class="bi bi-graph-up me-2"></i>查看行情</a></li>' +
                        '<li><a class="dropdown-item" href="javascript:;"' +
                            ' onclick="WatchlistPage.openMoveGroupModal(\'' + e(code) + '\')">' +
                            '<i class="bi bi-folder2-open me-2"></i>移动到分组</a></li>' +
                        '<li><a class="dropdown-item" href="javascript:;"' +
                            ' onclick="WatchlistPage.openReminderModal(\'' + e(code) + '\',' +
                                (s.targetPriceHigh != null ? s.targetPriceHigh : 'null') + ',' +
                                (s.targetPriceLow != null ? s.targetPriceLow : 'null') + ')">' +
                            '<i class="bi bi-bell me-2"></i>设置提醒</a></li>' +
                        '<li><hr class="dropdown-divider"></li>' +
                        '<li><a class="dropdown-item text-danger" href="javascript:;"' +
                            ' onclick="WatchlistPage.removeStock(\'' + e(code) + '\')">' +
                            '<i class="bi bi-trash me-2"></i>移除自选</a></li>' +
                    '</ul>' +
                '</div>' +
            '</td>' +
        '</tr>';
    }

    // ===================== 添加 / 移除自选股 =====================

    function addStockToWatchlist(code) {
        StockApp.post('/watchlist/' + code, null, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'warning');
            if (resp.code === 200) loadList();
        });
    }

    async function removeStock(code) {
        if (!await StockApp.confirm({
            title: '移除自选股',
            message: '确认移除该自选股？',
            confirmText: '移除',
            confirmClass: 'btn-danger',
            icon: 'bi-trash'
        })) return;

        StockApp.post('/watchlist/' + code + '/delete', null, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
            if (resp.code === 200) loadList();
        });
    }

    // ===================== 批量操作 =====================

    function toggleBatchMode() {
        state.batchMode = !state.batchMode;
        if (!state.batchMode) state.selectedCodes.clear();

        const batchBar = document.getElementById('batchActionBar');
        if (batchBar) batchBar.hidden = !state.batchMode;

        syncBatchColumnVisibility();
        renderTable();
        updateSelectedCount();
        syncBatchButton();
    }

    function syncBatchColumnVisibility() {
        const cols = document.querySelectorAll('.wl-col-check');
        cols.forEach(function (el) { el.hidden = !state.batchMode; });
        const tableSelectAll = document.getElementById('tableSelectAll');
        if (tableSelectAll) {
            const th = tableSelectAll.closest('th');
            if (th) th.hidden = !state.batchMode;
        }
    }

    function syncBatchButton() {
        const btn = document.getElementById('batchModeBtn');
        if (!btn) return;
        if (state.batchMode) {
            btn.className = 'btn btn-sm btn-primary';
            btn.innerHTML = '<i class="bi bi-check2-square"></i> 批量管理中';
        } else {
            btn.className = 'btn btn-sm btn-outline-secondary';
            btn.innerHTML = '<i class="bi bi-check2-square"></i> 批量管理';
        }
    }

    function toggleSelectAll() {
        const selectAllCb = document.getElementById('selectAllCheckbox');
        const tableSelectAll = document.getElementById('tableSelectAll');

        // 当前态反推：若已全选则清空，否则全选
        const allChecked = state.list.length > 0 && state.selectedCodes.size === state.list.length;
        if (allChecked) {
            state.selectedCodes.clear();
        } else {
            state.list.forEach(function (s) { state.selectedCodes.add(s.code); });
        }
        const nowAllChecked = state.list.length > 0 && state.selectedCodes.size === state.list.length;
        if (selectAllCb) selectAllCb.checked = nowAllChecked;
        if (tableSelectAll) tableSelectAll.checked = nowAllChecked;

        renderTable();
        updateSelectedCount();
    }

    function toggleSelectOne(code) {
        if (state.selectedCodes.has(code)) {
            state.selectedCodes.delete(code);
        } else {
            state.selectedCodes.add(code);
        }

        const selectAllCb = document.getElementById('selectAllCheckbox');
        const tableSelectAll = document.getElementById('tableSelectAll');
        const allChecked = state.list.length > 0 && state.selectedCodes.size === state.list.length;
        if (selectAllCb) selectAllCb.checked = allChecked;
        if (tableSelectAll) tableSelectAll.checked = allChecked;

        const row = document.querySelector('tr[data-code="' + code + '"]');
        if (row) {
            if (state.selectedCodes.has(code)) row.classList.add('row-selected');
            else row.classList.remove('row-selected');
            const cb = row.querySelector('.wl-col-check input[type="checkbox"]');
            if (cb) cb.checked = state.selectedCodes.has(code);
        }
        updateSelectedCount();
    }

    function updateSelectedCount() {
        const countEl = document.getElementById('selectedCount');
        if (countEl) countEl.textContent = state.selectedCodes.size;
    }

    async function batchRemove() {
        if (!state.selectedCodes.size) {
            StockApp.toast('请先选择要移除的股票', 'warning');
            return;
        }
        if (!await StockApp.confirm({
            title: '批量移除',
            message: '确认移除选中的 ' + state.selectedCodes.size + ' 只股票？',
            confirmText: '移除',
            confirmClass: 'btn-danger',
            icon: 'bi-trash'
        })) return;

        const codes = Array.from(state.selectedCodes);
        StockApp.post('/watchlist/batch-delete', { stockCodes: codes }, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
            if (resp.code === 200) loadList();
        });
    }

    function openBatchMoveModal() {
        if (!state.selectedCodes.size) {
            StockApp.toast('请先选择要移动的股票', 'warning');
            return;
        }
        state.moveGroupStockCodes = Array.from(state.selectedCodes);
        populateMoveGroupSelect();
        const modal = new bootstrap.Modal(document.getElementById('moveGroupModal'));
        modal.show();
    }

    function openMoveGroupModal(code) {
        state.moveGroupStockCodes = [code];
        populateMoveGroupSelect();
        const modal = new bootstrap.Modal(document.getElementById('moveGroupModal'));
        modal.show();
    }

    function populateMoveGroupSelect() {
        const sel = document.getElementById('moveGroupSelect');
        if (!sel) return;
        let html = '<option value="">未分组</option>';
        state.groups.forEach(function (g) {
            html += '<option value="' + e(g.id) + '">' + e(g.name) + '</option>';
        });
        sel.innerHTML = html;
    }

    function confirmMoveGroup() {
        const sel = document.getElementById('moveGroupSelect');
        if (!sel) return;
        const targetGroupId = sel.value || null;
        const codes = state.moveGroupStockCodes;

        if (codes.length === 1) {
            const url = '/watchlist/' + codes[0] + '/group' +
                (targetGroupId ? '?groupId=' + targetGroupId : '?groupId=');
            fetch(StockApp.contextPath + url, {
                method: 'PUT',
                headers: {'Accept': 'application/json'}
            })
                .then(r => r.json())
                .then(function (resp) {
                    StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                    if (resp.code === 200) {
                        hideModal('moveGroupModal');
                        loadList();
                    }
                })
                .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
        } else {
            const body = { stockCodes: codes, groupId: targetGroupId };
            StockApp.post('/watchlist/batch-move-group', body, function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) {
                    hideModal('moveGroupModal');
                    loadList();
                }
            });
        }
    }

    function hideModal(id) {
        const el = document.getElementById(id);
        if (!el) return;
        const inst = bootstrap.Modal.getInstance(el);
        if (inst) inst.hide();
    }

    // ===================== 价格提醒 =====================

    function openReminderModal(stockCode, currentHigh, currentLow) {
        state.currentReminderStock = stockCode;
        const codeInput = document.getElementById('reminderStockCode');
        if (codeInput) codeInput.textContent = stockCode;
        document.getElementById('reminderHighPrice').value = currentHigh != null ? currentHigh : '';
        document.getElementById('reminderLowPrice').value = currentLow != null ? currentLow : '';

        const clearBtn = document.getElementById('clearReminderBtn');
        const hasReminder = (currentHigh != null && currentHigh > 0) || (currentLow != null && currentLow > 0);
        if (clearBtn) clearBtn.hidden = !hasReminder;

        const modal = new bootstrap.Modal(document.getElementById('reminderModal'));
        modal.show();
    }

    function saveReminder() {
        const code = state.currentReminderStock;
        if (!code) return;

        const high = document.getElementById('reminderHighPrice').value;
        const low = document.getElementById('reminderLowPrice').value;

        const body = {
            targetPriceHigh: high ? parseFloat(high) : null,
            targetPriceLow: low ? parseFloat(low) : null,
        };

        StockApp.post('/watchlist/' + code + '/reminder', body, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
            if (resp.code === 200) {
                hideModal('reminderModal');
                loadList();
            }
        });
    }

    function clearReminder() {
        const code = state.currentReminderStock;
        if (!code) return;

        fetch(StockApp.contextPath + '/watchlist/' + code + '/reminder', {
            method: 'DELETE',
            headers: {'Accept': 'application/json'}
        })
            .then(r => r.json())
            .then(function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) {
                    hideModal('reminderModal');
                    loadList();
                }
            })
            .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
    }

    // ===================== 实时刷新：仅就地更新数字 =====================

    function refreshPrices() {
        const params = {};
        if (state.currentGroupId && state.currentGroupId !== 'ungrouped') {
            params.groupId = state.currentGroupId;
        } else if (state.currentGroupId === 'ungrouped') {
            params.groupId = '';
        }
        if (state.sortBy) params.sortBy = state.sortBy;
        if (state.order) params.order = state.order;

        StockApp.get('/watchlist', params, function (resp) {
            if (resp.code !== 200) return;
            const newList = resp.data || [];

            // 顺序变更（外部排序）则整表重渲
            const oldOrder = state.list.map(function (s) { return s.code; }).join('|');
            const newOrder = newList.map(function (s) { return s.code; }).join('|');
            if (oldOrder !== newOrder) {
                state.list = newList;
                renderTable();
                renderGroups();
                updateStatStrip();
                return;
            }

            state.list = newList;
            const tbody = document.getElementById('watchlistTable');
            if (!tbody) return;

            newList.forEach(function (s) {
                const row = tbody.querySelector('tr[data-code="' + s.code + '"]');
                if (!row) return;

                const prevPrice = state.lastPriceMap.get(s.code);
                const priceChanged = prevPrice !== s.currentPrice;

                updateCellText(row, 'currentPrice', formatNumber(s.currentPrice), priceChanged);
                updateCellText(row, 'totalMv', formatMarketValue(s.totalMv));
                updateCellText(row, 'peTtm', formatPePb(s.peTtm));
                updateCellText(row, 'turnoverRate', formatTurnoverRate(s.turnoverRate));

                // 涨跌幅
                const pctCls = pctCellClass(s.changePercent);
                const pctCell = row.querySelector('[data-field="changePercent"]');
                if (pctCell) {
                    pctCell.textContent = formatPercent(s.changePercent);
                    pctCell.className = 'wl-pct-cell ' + pctCls;
                }
                const amtCell = row.querySelector('[data-field="changeAmount"]');
                if (amtCell) {
                    amtCell.textContent = s.changeAmount != null
                        ? (s.changeAmount >= 0 ? '+' : '') + formatNumber(s.changeAmount)
                        : '-';
                }

                // 脉冲指示器强度档同步
                const pulse = pulseLevel(s.changePercent);
                const pulseEl = row.querySelector('.wl-pulse');
                if (pulseEl) {
                    pulseEl.className = 'wl-pulse ' + pulse.dir + ' ' + pulse.level;
                    if (priceChanged) {
                        pulseEl.classList.remove('pulsing');
                        // 强制 reflow 触发动画重启
                        void pulseEl.offsetWidth;
                        pulseEl.classList.add('pulsing');
                        setTimeout(function () { pulseEl.classList.remove('pulsing'); }, 700);
                    }
                }

                // 提醒按钮态
                const hasReminder = (s.targetPriceHigh != null && s.targetPriceHigh > 0) ||
                                    (s.targetPriceLow != null && s.targetPriceLow > 0);
                const reminderBtn = row.querySelector('.wl-reminder-btn');
                if (reminderBtn) {
                    reminderBtn.classList.toggle('active', hasReminder);
                    reminderBtn.title = hasReminder ? '已设置提醒，点击修改' : '设置价格提醒';
                    const icon = reminderBtn.querySelector('i');
                    if (icon) {
                        icon.className = hasReminder ? 'bi bi-bell-fill' : 'bi bi-bell';
                    }
                }

                state.lastPriceMap.set(s.code, s.currentPrice);
            });

            updateStatStrip();
            renderGroups();
            checkPriceAlertsFromList();
        });
    }

    function updateCellText(row, field, text, flash) {
        const cell = row.querySelector('[data-field="' + field + '"]');
        if (!cell) return;
        cell.textContent = text;
        if (flash) {
            cell.classList.remove('flash');
            void cell.offsetWidth;
            cell.classList.add('flash');
            setTimeout(function () { cell.classList.remove('flash'); }, 600);
        }
    }

    function checkPriceAlertsFromList() {
        if (!state.list || !state.list.length) return;

        const now = Date.now();
        const dedupInterval = 5 * 60 * 1000;

        state.list.forEach(function (item) {
            if (!item || item.currentPrice == null) return;

            const code = item.code;
            const lastTime = state.lastReminderTimes.get(code);

            if (item.targetPriceHigh != null && item.targetPriceHigh > 0 &&
                item.currentPrice >= item.targetPriceHigh) {
                if (!lastTime || (now - lastTime) >= dedupInterval) {
                    triggerPriceAlert(item, 'high');
                }
            }

            if (item.targetPriceLow != null && item.targetPriceLow > 0 &&
                item.currentPrice <= item.targetPriceLow) {
                if (!lastTime || (now - lastTime) >= dedupInterval) {
                    triggerPriceAlert(item, 'low');
                }
            }
        });
    }

    function triggerPriceAlert(item, type) {
        if (!item) return;
        const name = item.name || item.code;
        const price = formatNumber(item.currentPrice);
        let message = '';
        if (type === 'high') {
            message = name + ' 价格上穿提醒位：当前 ' + price + ' 元';
        } else if (type === 'low') {
            message = name + ' 价格下穿提醒位：当前 ' + price + ' 元';
        }
        if (message) StockApp.toast(message, 'info');
        state.lastReminderTimes.set(item.code, Date.now());
    }

    function startReminderPolling() {
        stopReminderPolling();
        const visibleInterval = 60 * 1000;
        const hiddenInterval = 300 * 1000;

        function getInterval() { return document.hidden ? hiddenInterval : visibleInterval; }
        function tick() { refreshPrices(); }

        state.reminderPollingTimer = setInterval(tick, getInterval());

        function onVisibilityChange() {
            if (state.reminderPollingTimer == null) return;
            clearInterval(state.reminderPollingTimer);
            state.reminderPollingTimer = setInterval(tick, getInterval());
        }

        document.addEventListener('visibilitychange', onVisibilityChange);
        state._visibilityChangeListener = onVisibilityChange;
        tick();
    }

    function stopReminderPolling() {
        if (state.reminderPollingTimer) {
            clearInterval(state.reminderPollingTimer);
            state.reminderPollingTimer = null;
        }
        if (state._visibilityChangeListener) {
            document.removeEventListener('visibilitychange', state._visibilityChangeListener);
            state._visibilityChangeListener = null;
        }
    }

    function resetReminderPolling() {
        if (state.reminderPollingTimer) startReminderPolling();
    }

    // ===================== 拖拽排序 =====================

    function bindRowDragEvents() {
        const tbody = document.getElementById('watchlistTable');
        if (!tbody) return;
        const rows = tbody.querySelectorAll('tr[data-code]');
        rows.forEach(function (row) {
            row.setAttribute('draggable', 'true');
            row.addEventListener('dragstart', handleDragStart);
            row.addEventListener('dragover', handleDragOver);
            row.addEventListener('dragleave', handleDragLeave);
            row.addEventListener('drop', handleDrop);
            row.addEventListener('dragend', handleDragEnd);
        });
    }

    function handleDragStart(ev) {
        const target = ev.target;
        if (target.closest('a') || target.closest('button') || target.closest('input') ||
            target.closest('.dropdown') || target.closest('.wl-reminder-btn')) {
            ev.preventDefault();
            return;
        }
        const row = ev.currentTarget;
        const code = row.getAttribute('data-code');
        if (!code) return;

        state.dragState.draggingCode = code;
        state.dragState.originalList = state.list.slice();
        row.classList.add('dragging');

        try {
            ev.dataTransfer.effectAllowed = 'move';
            ev.dataTransfer.setData('text/plain', code);
            ev.dataTransfer.setData('application/x-stock-code', code);
        } catch (err) {}
    }

    function handleDragOver(ev) {
        ev.preventDefault();
        const row = ev.currentTarget;
        const targetCode = row.getAttribute('data-code');
        const draggingCode = state.dragState.draggingCode;
        if (!draggingCode || !targetCode || targetCode === draggingCode) return;

        try { ev.dataTransfer.dropEffect = 'move'; } catch (err) {}

        const rect = row.getBoundingClientRect();
        const midY = rect.top + rect.height / 2;
        const isTop = ev.clientY < midY;

        clearDragOverStyles();
        if (isTop) {
            row.classList.add('drag-over-top');
            state.dragState.dropPosition = 'top';
        } else {
            row.classList.add('drag-over-bottom');
            state.dragState.dropPosition = 'bottom';
        }
        state.dragState.dropTargetCode = targetCode;
    }

    function handleDragLeave(ev) {
        const row = ev.currentTarget;
        row.classList.remove('drag-over-top');
        row.classList.remove('drag-over-bottom');
    }

    function handleDrop(ev) {
        ev.preventDefault();
        ev.stopPropagation();

        const targetCode = state.dragState.dropTargetCode;
        const draggingCode = state.dragState.draggingCode;
        const position = state.dragState.dropPosition;
        clearDragOverStyles();

        if (!draggingCode || !targetCode || targetCode === draggingCode) return;
        if (!position) return;

        const list = state.list.slice();
        const dragIndex = list.findIndex(function (s) { return s.code === draggingCode; });
        if (dragIndex === -1) return;
        const [draggedItem] = list.splice(dragIndex, 1);

        let targetIndex = list.findIndex(function (s) { return s.code === targetCode; });
        if (targetIndex === -1) return;
        if (position === 'bottom') targetIndex += 1;
        list.splice(targetIndex, 0, draggedItem);

        state.list = list;
        disposeAllCharts();
        renderTable();
        saveSortOrder();
    }

    function handleDragEnd() {
        clearDragOverStyles();
        const rows = document.querySelectorAll('.wl-table tbody tr.dragging');
        rows.forEach(function (row) { row.classList.remove('dragging'); });
        state.dragState.draggingCode = null;
        state.dragState.dropTargetCode = null;
        state.dragState.dropPosition = null;
    }

    function clearDragOverStyles() {
        const rows = document.querySelectorAll('.wl-table tbody tr');
        rows.forEach(function (row) {
            row.classList.remove('drag-over-top');
            row.classList.remove('drag-over-bottom');
        });
    }

    function saveSortOrder() {
        const codes = state.list.map(function (s, index) {
            return { code: s.code, sortOrder: index };
        });

        let failCount = 0;
        let completed = 0;
        const total = codes.length;
        const originalList = state.dragState.originalList;

        codes.forEach(function (item) {
            fetch(StockApp.contextPath + '/watchlist/' + item.code + '/sort?sortOrder=' + item.sortOrder, {
                method: 'PUT',
                headers: { 'Accept': 'application/json' }
            })
                .then(r => r.json())
                .then(function (resp) {
                    completed++;
                    if (resp.code !== 200) failCount++;
                    checkAllDone();
                })
                .catch(function () {
                    completed++;
                    failCount++;
                    checkAllDone();
                });
        });

        function checkAllDone() {
            if (completed >= total && failCount > 0) {
                StockApp.toast('排序更新失败 ' + failCount + ' 项，已恢复原顺序', 'danger');
                if (originalList) {
                    state.list = originalList;
                    disposeAllCharts();
                    renderTable();
                }
            }
        }
    }

    // ===================== 跨分组拖拽 =====================

    function bindGroupDropEvents() {
        const groupItems = document.querySelectorAll('.wl-group-item');
        groupItems.forEach(function (item) {
            item.addEventListener('dragover', handleGroupDragOver);
            item.addEventListener('dragleave', handleGroupDragLeave);
            item.addEventListener('drop', handleGroupDrop);
        });
    }

    function handleGroupDragOver(ev) {
        ev.preventDefault();
        const item = ev.currentTarget;
        try {
            const hasStockCode = ev.dataTransfer.types &&
                (ev.dataTransfer.types.includes('text/plain') ||
                 ev.dataTransfer.types.includes('application/x-stock-code'));
            if (!hasStockCode) return;
        } catch (err) {}
        try { ev.dataTransfer.dropEffect = 'move'; } catch (err2) {}
        item.classList.add('drag-over');
    }

    function handleGroupDragLeave(ev) {
        ev.currentTarget.classList.remove('drag-over');
    }

    function handleGroupDrop(ev) {
        ev.preventDefault();
        ev.stopPropagation();
        const item = ev.currentTarget;
        item.classList.remove('drag-over');

        let stockCode = null;
        try {
            stockCode = ev.dataTransfer.getData('application/x-stock-code') ||
                        ev.dataTransfer.getData('text/plain');
        } catch (err) {}
        if (!stockCode) return;

        const groupId = item.getAttribute('data-group-id');
        if (!groupId) return;

        const currentGroup = state.currentGroupId;
        if ((groupId === 'all' && currentGroup === null) ||
            (groupId === 'ungrouped' && currentGroup === 'ungrouped') ||
            (groupId !== 'all' && groupId !== 'ungrouped' && currentGroup && String(currentGroup) === String(groupId))) {
            return;
        }
        moveStockToGroup(stockCode, groupId);
    }

    function moveStockToGroup(stockCode, groupId) {
        let targetGroupId = null;
        if (groupId === 'all') return;
        if (groupId === 'ungrouped') targetGroupId = null;
        else targetGroupId = groupId;

        const url = '/watchlist/' + stockCode + '/group' +
            (targetGroupId != null ? '?groupId=' + targetGroupId : '?groupId=');

        fetch(StockApp.contextPath + url, {
            method: 'PUT',
            headers: { 'Accept': 'application/json' }
        })
            .then(r => r.json())
            .then(function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) loadList();
            })
            .catch(function (err) {
                StockApp.toast('移动失败: ' + err.message, 'danger');
            });
    }

    // ===================== 排序 =====================

    function updateSort(sortBy, order) {
        state.sortBy = sortBy;
        if (order) state.order = order;
        const sortSel = document.getElementById('sortBySelect');
        if (sortSel) sortSel.value = sortBy;
        updateOrderIcon();
        loadList();
    }

    // ===================== 公开 API =====================

    return {
        init: init,
        loadList: loadList,
        removeStock: removeStock,
        selectGroup: selectGroup,
        createGroup: createGroup,
        startRenameGroup: startRenameGroup,
        renameGroup: renameGroup,
        deleteGroup: deleteGroup,
        toggleBatchMode: toggleBatchMode,
        toggleSelectAll: toggleSelectAll,
        toggleSelectOne: toggleSelectOne,
        batchRemove: batchRemove,
        openReminderModal: openReminderModal,
        saveReminder: saveReminder,
        clearReminder: clearReminder,
        openMoveGroupModal: openMoveGroupModal,
        openBatchMoveModal: openBatchMoveModal,
        confirmMoveGroup: confirmMoveGroup,
        updateSort: updateSort,
        refreshPrices: refreshPrices,
        startReminderPolling: startReminderPolling,
        stopReminderPolling: stopReminderPolling,
    };
})();
