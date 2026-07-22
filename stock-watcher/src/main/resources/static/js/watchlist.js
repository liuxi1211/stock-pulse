/**
 * 自选股页面逻辑。
 * - 列表加载：GET /watchlist
 * - 分组管理：GET/POST/PUT/DELETE /watchlist/groups
 * - 移除自选股：POST /watchlist/{code}/delete
 * - 批量操作：批量移除、批量移动分组
 * - 排序、搜索联想、价格提醒
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

    function getUpDownClass(changePercent) {
        if (changePercent === null || changePercent === undefined || changePercent === 0) return '';
        return changePercent > 0 ? 'rise' : 'fall';
    }

    function formatVolume(vol) {
        if (vol === null || vol === undefined || isNaN(vol)) return '-';
        const v = Number(vol);
        if (v >= 10000) return (v / 10000).toFixed(1) + '万';
        return v.toString();
    }

    // ===================== 初始化与事件绑定 =====================

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
                loadList();
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
        if (state.order === 'desc') {
            icon.className = 'bi bi-sort-down-alt';
        } else {
            icon.className = 'bi bi-sort-up-alt';
        }
    }

    function bindBatchControls() {
        const batchBtn = document.getElementById('batchModeBtn');
        const batchBtnToolbar = document.getElementById('batchModeBtnToolbar');
        if (batchBtn) batchBtn.addEventListener('click', toggleBatchMode);
        if (batchBtnToolbar) batchBtnToolbar.addEventListener('click', toggleBatchMode);

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
        });
    }

    function renderGroups() {
        const listEl = document.getElementById('groupList');
        if (!listEl) return;

        const totalCount = state.list.length || 0;
        const ungroupedCount = state.list.filter(function (s) {
            return !s.groupId;
        }).length;

        let html = '';

        html += buildGroupItem('all', '全部', totalCount, state.currentGroupId === null);
        html += buildGroupItem('ungrouped', '未分组', ungroupedCount, state.currentGroupId === 'ungrouped');

        if (state.groups.length) {
            html += '<div class="group-divider"><span>我的分组</span></div>';
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

    function buildGroupItem(id, name, count, active) {
        return `
            <div class="group-item ${active ? 'active' : ''}" data-group-id="${e(id)}"
                 onclick="WatchlistPage.selectGroup('${e(id)}')">
                <span class="group-name">
                    <i class="bi ${id === 'all' ? 'bi-star-fill' : 'bi-folder2-open'} group-icon"></i>
                    ${e(name)}
                </span>
                <span class="group-count">${count}</span>
            </div>`;
    }

    function buildCustomGroupItem(group, count) {
        const active = state.currentGroupId && String(state.currentGroupId) === String(group.id);
        return `
            <div class="group-item group-item-custom ${active ? 'active' : ''}" data-group-id="${e(group.id)}"
                 onclick="WatchlistPage.selectGroup('${e(group.id)}')"
                 ondblclick="WatchlistPage.startRenameGroup('${e(group.id)}', event)">
                <span class="group-name">
                    <i class="bi bi-folder2 group-icon"></i>
                    <span class="group-name-text">${e(group.name)}</span>
                </span>
                <span class="group-actions">
                    <span class="group-count">${count}</span>
                    <button class="group-del-btn" title="删除分组"
                            onclick="event.stopPropagation();WatchlistPage.deleteGroup('${e(group.id)}')">
                        <i class="bi bi-trash3"></i>
                    </button>
                </span>
            </div>`;
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
        if (!titleEl) return;
        if (state.currentGroupId === null) {
            titleEl.textContent = '全部自选股';
        } else if (state.currentGroupId === 'ungrouped') {
            titleEl.textContent = '未分组';
        } else {
            const g = state.groups.find(function (x) { return String(x.id) === String(state.currentGroupId); });
            titleEl.textContent = g ? g.name : '自选股';
        }
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
            if (resp.code === 200) {
                loadGroups();
            }
        });
    }

    function startRenameGroup(groupId, event) {
        if (event) event.preventDefault();
        const item = document.querySelector('.group-item[data-group-id="' + groupId + '"]');
        if (!item) return;

        const nameEl = item.querySelector('.group-name-text');
        if (!nameEl) return;

        const currentName = nameEl.textContent;
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'form-control form-control-sm group-rename-input';
        input.value = currentName;

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
                if (resp.code === 200) {
                    loadGroups();
                    updateCurrentGroupTitle();
                } else {
                    loadGroups();
                }
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
            containerEl.innerHTML = '<span class="text-muted">-</span>';
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
            grid: {
                left: 0,
                right: 0,
                top: 2,
                bottom: 2,
                containLabel: false
            },
            xAxis: {
                type: 'category',
                show: false,
                data: data.map(function (_, i) { return i; })
            },
            yAxis: {
                type: 'value',
                show: false,
                scale: true
            },
            tooltip: {
                show: false
            },
            legend: {
                show: false
            },
            series: [{
                type: 'line',
                data: data,
                smooth: true,
                symbol: 'none',
                lineStyle: {
                    color: lineColor,
                    width: 1.5
                },
                areaStyle: {
                    color: areaGradient
                }
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
            const container = document.querySelector('.mini-kline[data-code="' + item.code + '"]');
            if (!container) return;

            if (index >= maxRender) {
                container.innerHTML = '<span class="text-muted">-</span>';
                return;
            }

            setTimeout(function () {
                if (document.querySelector('.mini-kline[data-code="' + item.code + '"]')) {
                    renderMiniKline(container, item);
                }
            }, index * 10);
        });
    }

    function loadList() {
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
            state.list = resp.data || [];
            state.selectedCodes.clear();
            updateSelectedCount();
            renderTable();
            renderGroups();
            updateStockCountBadge();
        });
    }

    function updateStockCountBadge() {
        const badge = document.getElementById('stockCountBadge');
        if (badge) badge.textContent = state.list.length;
    }

    function renderTable() {
        const tbody = document.getElementById('watchlistTable');
        if (!tbody) return;

        disposeAllCharts();

        if (!state.list.length) {
            tbody.innerHTML = '<tr><td colspan="14" class="text-center text-muted py-4">暂无自选股</td></tr>';
            return;
        }

        tbody.innerHTML = state.list.map(function (s) {
            const code = s.code;
            const upDown = getUpDownClass(s.changePercent);
            const changeSign = (s.changeAmount != null && s.changeAmount >= 0) ? '+' : '';
            const hasReminder = (s.targetPriceHigh != null && s.targetPriceHigh > 0) ||
                                (s.targetPriceLow != null && s.targetPriceLow > 0);
            const isSelected = state.selectedCodes.has(code);

            return `
                <tr data-code="${e(code)}" class="${isSelected ? 'row-selected' : ''}">
                    <td class="col-checkbox" style="display: ${state.batchMode ? '' : 'none'};">
                        <input type="checkbox" ${isSelected ? 'checked' : ''}
                               onchange="WatchlistPage.toggleSelectOne('${e(code)}')">
                    </td>
                    <td>
                        <a href="/stock/${e(code)}" class="stock-code-link" target="_blank">
                            <code>${e(code)}</code>
                        </a>
                    </td>
                    <td class="fw-medium">${e(s.name)}</td>
                    <td><span class="text-muted">${e(s.industryName || '-')}</span></td>
                    <td class="text-end stock-price" data-field="currentPrice">${formatNumber(s.currentPrice)}</td>
                    <td class="text-end stock-change ${upDown}" data-field="changeAmount">${changeSign}${formatNumber(s.changeAmount)}</td>
                    <td class="text-end stock-change ${upDown}" data-field="changePercent">${formatPercent(s.changePercent)}</td>
                    <td class="text-end" data-field="totalMv">${formatMarketValue(s.totalMv)}</td>
                    <td class="text-end" data-field="peTtm">${formatPePb(s.peTtm)}</td>
                    <td class="text-end" data-field="pb">${formatPePb(s.pb)}</td>
                    <td class="text-end" data-field="turnoverRate">${formatTurnoverRate(s.turnoverRate)}</td>
                    <td class="text-center">
                        <div class="mini-kline" data-code="${e(code)}"></div>
                    </td>
                    <td class="text-center">
                        <button class="btn btn-sm btn-icon-only reminder-btn ${hasReminder ? 'reminder-active' : ''}"
                                title="${hasReminder ? '已设置提醒' : '设置提醒'}"
                                onclick="WatchlistPage.openReminderModal('${e(code)}', ${s.targetPriceHigh != null ? s.targetPriceHigh : 'null'}, ${s.targetPriceLow != null ? s.targetPriceLow : 'null'})">
                            <i class="bi ${hasReminder ? 'bi-bell-fill' : 'bi-bell'}"></i>
                        </button>
                    </td>
                    <td class="text-center">
                        <div class="dropdown">
                            <button class="btn btn-sm btn-outline-secondary" data-bs-toggle="dropdown">
                                <i class="bi bi-three-dots"></i>
                            </button>
                            <ul class="dropdown-menu dropdown-menu-end">
                                <li><a class="dropdown-item" href="/stock/${e(code)}" target="_blank">
                                    <i class="bi bi-graph-up me-2"></i>查看行情
                                </a></li>
                                <li><a class="dropdown-item" href="javascript:;" onclick="WatchlistPage.openMoveGroupModal('${e(code)}')">
                                    <i class="bi bi-folder2-open me-2"></i>移动分组
                                </a></li>
                                <li><a class="dropdown-item" href="javascript:;" onclick="WatchlistPage.openReminderModal('${e(code)}', ${s.targetPriceHigh != null ? s.targetPriceHigh : 'null'}, ${s.targetPriceLow != null ? s.targetPriceLow : 'null'})">
                                    <i class="bi bi-bell me-2"></i>设置提醒
                                </a></li>
                                <li><hr class="dropdown-divider"></li>
                                <li><a class="dropdown-item text-danger" href="javascript:;" onclick="WatchlistPage.removeStock('${e(code)}')">
                                    <i class="bi bi-trash me-2"></i>移除自选
                                </a></li>
                            </ul>
                        </div>
                    </td>
                </tr>`;
        }).join('');

        requestAnimationFrame(function () {
            initAllMiniKlines();
            bindRowDragEvents();
        });
    }

    // ===================== 添加/移除自选股 =====================

    function addStockToWatchlist(code) {
        StockApp.post('/watchlist/' + code, null, function (resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'warning');
            if (resp.code === 200) {
                loadList();
            }
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
        if (!state.batchMode) {
            state.selectedCodes.clear();
        }

        const batchBar = document.getElementById('batchActionBar');
        if (batchBar) batchBar.style.display = state.batchMode ? '' : 'none';

        const checkboxes = document.querySelectorAll('.col-checkbox');
        checkboxes.forEach(function (el) {
            el.style.display = state.batchMode ? '' : 'none';
        });

        const batchBtn = document.getElementById('batchModeBtn');
        const batchBtnToolbar = document.getElementById('batchModeBtnToolbar');
        if (batchBtn) {
            batchBtn.className = state.batchMode ? 'btn btn-primary btn-sm' : 'btn btn-outline-primary btn-sm';
        }
        if (batchBtnToolbar) {
            batchBtnToolbar.className = state.batchMode ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline-primary';
        }

        renderTable();
        updateSelectedCount();
    }

    function toggleSelectAll() {
        const allChecked = state.selectedCodes.size === state.list.length && state.list.length > 0;
        if (allChecked) {
            state.selectedCodes.clear();
        } else {
            state.list.forEach(function (s) {
                state.selectedCodes.add(s.code);
            });
        }

        const selectAllCb = document.getElementById('selectAllCheckbox');
        const tableSelectAll = document.getElementById('tableSelectAll');
        const checked = state.selectedCodes.size === state.list.length && state.list.length > 0;
        if (selectAllCb) selectAllCb.checked = checked;
        if (tableSelectAll) tableSelectAll.checked = checked;

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
        const allChecked = state.selectedCodes.size === state.list.length && state.list.length > 0;
        if (selectAllCb) selectAllCb.checked = allChecked;
        if (tableSelectAll) tableSelectAll.checked = allChecked;

        const row = document.querySelector('tr[data-code="' + code + '"]');
        if (row) {
            if (state.selectedCodes.has(code)) {
                row.classList.add('row-selected');
            } else {
                row.classList.remove('row-selected');
            }
            const cb = row.querySelector('.col-checkbox input[type="checkbox"]');
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
            if (resp.code === 200) {
                loadList();
            }
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
            html += `<option value="${e(g.id)}">${e(g.name)}</option>`;
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
                        const modal = bootstrap.Modal.getInstance(document.getElementById('moveGroupModal'));
                        if (modal) modal.hide();
                        loadList();
                    }
                })
                .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
        } else {
            const body = {
                stockCodes: codes,
                groupId: targetGroupId
            };
            StockApp.post('/watchlist/batch-move-group', body, function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) {
                    const modal = bootstrap.Modal.getInstance(document.getElementById('moveGroupModal'));
                    if (modal) modal.hide();
                    loadList();
                }
            });
        }
    }

    // ===================== 价格提醒 =====================

    function openReminderModal(stockCode, currentHigh, currentLow) {
        state.currentReminderStock = stockCode;
        document.getElementById('reminderStockCode').value = stockCode;
        document.getElementById('reminderHighPrice').value = currentHigh != null ? currentHigh : '';
        document.getElementById('reminderLowPrice').value = currentLow != null ? currentLow : '';

        const clearBtn = document.getElementById('clearReminderBtn');
        const hasReminder = (currentHigh != null && currentHigh > 0) || (currentLow != null && currentLow > 0);
        if (clearBtn) clearBtn.style.display = hasReminder ? '' : 'none';

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
                const modal = bootstrap.Modal.getInstance(document.getElementById('reminderModal'));
                if (modal) modal.hide();
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
                    const modal = bootstrap.Modal.getInstance(document.getElementById('reminderModal'));
                    if (modal) modal.hide();
                    loadList();
                }
            })
            .catch(err => StockApp.toast('请求失败: ' + err.message, 'danger'));
    }

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
            state.list = newList;

            const tbody = document.getElementById('watchlistTable');
            if (!tbody) return;

            newList.forEach(function (s) {
                const row = tbody.querySelector('tr[data-code="' + s.code + '"]');
                if (!row) return;

                const upDown = getUpDownClass(s.changePercent);
                const changeSign = (s.changeAmount != null && s.changeAmount >= 0) ? '+' : '';

                const priceCell = row.querySelector('[data-field="currentPrice"]');
                if (priceCell) priceCell.textContent = formatNumber(s.currentPrice);

                const changeCell = row.querySelector('[data-field="changeAmount"]');
                if (changeCell) {
                    changeCell.textContent = changeSign + formatNumber(s.changeAmount);
                    changeCell.className = 'text-end stock-change ' + upDown;
                }

                const pctCell = row.querySelector('[data-field="changePercent"]');
                if (pctCell) {
                    pctCell.textContent = formatPercent(s.changePercent);
                    pctCell.className = 'text-end stock-change ' + upDown;
                }

                const mvCell = row.querySelector('[data-field="totalMv"]');
                if (mvCell) mvCell.textContent = formatMarketValue(s.totalMv);

                const peCell = row.querySelector('[data-field="peTtm"]');
                if (peCell) peCell.textContent = formatPePb(s.peTtm);

                const pbCell = row.querySelector('[data-field="pb"]');
                if (pbCell) pbCell.textContent = formatPePb(s.pb);

                const turnoverCell = row.querySelector('[data-field="turnoverRate"]');
                if (turnoverCell) turnoverCell.textContent = formatTurnoverRate(s.turnoverRate);

                const hasReminder = (s.targetPriceHigh != null && s.targetPriceHigh > 0) ||
                                    (s.targetPriceLow != null && s.targetPriceLow > 0);
                const reminderBtn = row.querySelector('.reminder-btn');
                if (reminderBtn) {
                    if (hasReminder) {
                        reminderBtn.classList.add('reminder-active');
                        reminderBtn.title = '已设置提醒';
                        const icon = reminderBtn.querySelector('i');
                        if (icon) {
                            icon.classList.remove('bi-bell');
                            icon.classList.add('bi-bell-fill');
                        }
                    } else {
                        reminderBtn.classList.remove('reminder-active');
                        reminderBtn.title = '设置提醒';
                        const icon = reminderBtn.querySelector('i');
                        if (icon) {
                            icon.classList.remove('bi-bell-fill');
                            icon.classList.add('bi-bell');
                        }
                    }
                }
            });

            checkPriceAlertsFromList();
        });
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
        const code = item.code;
        const name = item.name || code;
        const price = formatNumber(item.currentPrice);

        let message = '';
        if (type === 'high') {
            message = name + ' 突破高位提醒：当前价 ' + price + ' 元';
        } else if (type === 'low') {
            message = name + ' 跌破低位提醒：当前价 ' + price + ' 元';
        }

        if (message) {
            StockApp.toast(message, 'info');
        }

        state.lastReminderTimes.set(code, Date.now());
    }

    function startReminderPolling() {
        stopReminderPolling();

        const visibleInterval = 60 * 1000;
        const hiddenInterval = 300 * 1000;

        function getInterval() {
            return document.hidden ? hiddenInterval : visibleInterval;
        }

        function tick() {
            refreshPrices();
        }

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
        if (state.reminderPollingTimer) {
            startReminderPolling();
        }
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

    function handleDragStart(e) {
        const target = e.target;
        if (target.closest('a') || target.closest('button') || target.closest('input') ||
            target.closest('.dropdown') || target.closest('.reminder-btn')) {
            e.preventDefault();
            return;
        }

        const row = e.currentTarget;
        const code = row.getAttribute('data-code');
        if (!code) return;

        state.dragState.draggingCode = code;
        state.dragState.originalList = state.list.slice();

        row.classList.add('dragging');

        try {
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', code);
            e.dataTransfer.setData('application/x-stock-code', code);
        } catch (err) {}
    }

    function handleDragOver(e) {
        e.preventDefault();
        const row = e.currentTarget;
        const targetCode = row.getAttribute('data-code');
        const draggingCode = state.dragState.draggingCode;

        if (!draggingCode || !targetCode || targetCode === draggingCode) return;

        try {
            e.dataTransfer.dropEffect = 'move';
        } catch (err) {}

        const rect = row.getBoundingClientRect();
        const midY = rect.top + rect.height / 2;
        const isTop = e.clientY < midY;

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

    function handleDragLeave(e) {
        const row = e.currentTarget;
        row.classList.remove('drag-over-top');
        row.classList.remove('drag-over-bottom');
    }

    function handleDrop(e) {
        e.preventDefault();
        e.stopPropagation();

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

        if (position === 'bottom') {
            targetIndex += 1;
        }

        list.splice(targetIndex, 0, draggedItem);

        state.list = list;
        disposeAllCharts();
        renderTable();
        saveSortOrder();
    }

    function handleDragEnd() {
        clearDragOverStyles();
        const rows = document.querySelectorAll('.watchlist-table tbody tr.dragging');
        rows.forEach(function (row) { row.classList.remove('dragging'); });
        state.dragState.draggingCode = null;
        state.dragState.dropTargetCode = null;
        state.dragState.dropPosition = null;
    }

    function clearDragOverStyles() {
        const rows = document.querySelectorAll('.watchlist-table tbody tr');
        rows.forEach(function (row) {
            row.classList.remove('drag-over-top');
            row.classList.remove('drag-over-bottom');
        });
    }

    function saveSortOrder() {
        const codes = state.list.map(function (s, index) {
            return { code: s.code, sortOrder: index };
        });

        let successCount = 0;
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
                    if (resp.code === 200) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                    checkAllDone();
                })
                .catch(function () {
                    completed++;
                    failCount++;
                    checkAllDone();
                });
        });

        function checkAllDone() {
            if (completed >= total) {
                if (failCount > 0) {
                    StockApp.toast('排序更新失败 ' + failCount + ' 项，已恢复原顺序', 'danger');
                    if (originalList) {
                        state.list = originalList;
                        disposeAllCharts();
                        renderTable();
                    }
                }
            }
        }
    }

    // ===================== 跨分组拖拽 =====================

    function bindGroupDropEvents() {
        const groupItems = document.querySelectorAll('.group-item');
        groupItems.forEach(function (item) {
            item.addEventListener('dragover', handleGroupDragOver);
            item.addEventListener('dragleave', handleGroupDragLeave);
            item.addEventListener('drop', handleGroupDrop);
        });
    }

    function handleGroupDragOver(e) {
        e.preventDefault();
        const item = e.currentTarget;

        try {
            const hasStockCode = e.dataTransfer.types &&
                (e.dataTransfer.types.includes('text/plain') ||
                 e.dataTransfer.types.includes('application/x-stock-code'));
            if (!hasStockCode) return;
        } catch (err) {}

        try {
            e.dataTransfer.dropEffect = 'move';
        } catch (err2) {}

        item.classList.add('drag-over');
    }

    function handleGroupDragLeave(e) {
        const item = e.currentTarget;
        item.classList.remove('drag-over');
    }

    function handleGroupDrop(e) {
        e.preventDefault();
        e.stopPropagation();

        const item = e.currentTarget;
        item.classList.remove('drag-over');

        let stockCode = null;
        try {
            stockCode = e.dataTransfer.getData('application/x-stock-code') ||
                        e.dataTransfer.getData('text/plain');
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
        if (groupId === 'all') {
            return;
        } else if (groupId === 'ungrouped') {
            targetGroupId = null;
        } else {
            targetGroupId = groupId;
        }

        const url = '/watchlist/' + stockCode + '/group' +
            (targetGroupId != null ? '?groupId=' + targetGroupId : '?groupId=');

        fetch(StockApp.contextPath + url, {
            method: 'PUT',
            headers: { 'Accept': 'application/json' }
        })
            .then(r => r.json())
            .then(function (resp) {
                StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'danger');
                if (resp.code === 200) {
                    loadList();
                }
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
