/**
 * Data Governance Center
 * Handles: overview stats, table cards, detail drawer, pull logs, datasource status,
 *          incremental update / full rebuild with task progress polling.
 */
const DG = {
    apiBase: '/api/data-governance',
    isAdmin: document.querySelector('meta[name="isAdmin"]')?.content === 'true',
    pollTimer: null,
    pollStartTime: null,
    currentTaskId: null,
    rebuildTableCode: null,
    rebuildTableName: null,
    rebuildCountdownTimer: null,
    logPage: 1,
    logPageSize: 20,
    tableListCache: [],
    keywordSearchTimer: null,

    // ==================== Overview ====================

    refreshOverview() {
        StockApp.get(this.apiBase + '/overview', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载概览失败', 'danger');
                return;
            }
            const d = resp.data || {};
            document.getElementById('ovTotalTables').textContent = d.totalTables ?? '-';
            document.getElementById('ovUpdatedToday').textContent = d.updatedToday ?? '-';
            document.getElementById('ovErrorTables').textContent = d.errorTables ?? '-';
            document.getElementById('ovLastCheck').textContent = '最后检测：' + (d.lastCheckTime || '-');
            document.getElementById('ovUpdatedFoot').textContent =
                d.errorTables > 0 ? `${d.errorTables} 张表异常` : '运行正常';
            const errorFoot = document.getElementById('ovErrorFoot');
            errorFoot.textContent = d.errorTables > 0 ? '需关注' : '无异常';
        });
    },

    // ==================== Table List ====================

    loadTables() {
        const params = {
            group: document.getElementById('filterGroup').value,
            status: document.getElementById('filterStatus').value,
            keyword: document.getElementById('filterKeyword').value,
        };
        StockApp.get(this.apiBase + '/tables', params, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载数据表失败', 'danger');
                return;
            }
            this.tableListCache = resp.data || [];
            this.renderTableCards(this.tableListCache);
            this.populateLogTableDropdown(this.tableListCache);
        });
    },

    renderTableCards(list) {
        const grid = document.getElementById('tableCardGrid');
        if (!list || !list.length) {
            grid.innerHTML = '<div class="col-12 text-center text-muted py-5"><i class="bi bi-inbox"></i> 无匹配的数据表</div>';
            return;
        }
        grid.innerHTML = list.map(t => {
            const status = this.getStatusInfo(t.status);
            const groupLabel = this.getGroupLabel(t.tableGroup);
            const failedBadge = t.failedCount > 0
                ? `<span class="badge bg-danger ms-1">${t.failedCount} 项异常</span>` : '';
            const adminButtons = this.isAdmin ? `
                <button class="btn btn-sm btn-outline-primary" onclick="DG.incrementalUpdate('${t.tableCode}')" title="增量更新">
                    <i class="bi bi-arrow-up-circle"></i>
                </button>
                <button class="btn btn-sm btn-outline-warning" onclick="DG.openRebuildModal('${t.tableCode}', '${StockApp.escapeHtml(t.tableName)}')" title="全量重建">
                    <i class="bi bi-arrow-repeat"></i>
                </button>
            ` : '';
            return `
                <div class="col-md-6 col-xl-4">
                    <div class="card card-glow h-100">
                        <div class="card-body">
                            <div class="d-flex justify-content-between align-items-start mb-2">
                                <div>
                                    <h6 class="card-title mb-0">${StockApp.escapeHtml(t.tableName)}</h6>
                                    <small class="text-muted mono">${t.tableCode}</small>
                                </div>
                                <span class="badge ${status.badgeClass}">${status.label}</span>
                            </div>
                            <div class="row g-2 small text-muted mb-2">
                                <div class="col-6">
                                    <i class="bi bi-tag"></i> ${groupLabel}
                                </div>
                                <div class="col-6">
                                    <i class="bi bi-calendar-event"></i> ${this.formatDate(t.latestDate) || '-'}
                                </div>
                                <div class="col-6">
                                    <i class="bi bi-database"></i> ${this.formatCount(t.totalRows)} 行
                                </div>
                                <div class="col-6">
                                    <i class="bi bi-clock"></i> ${t.updateFrequency || '-'}
                                </div>
                            </div>
                            ${failedBadge}
                            <div class="mt-2 d-flex gap-1">
                                <button class="btn btn-sm btn-outline-secondary" onclick="DG.openDetail('${t.tableCode}')" title="查看详情">
                                    <i class="bi bi-eye"></i> 详情
                                </button>
                                ${adminButtons}
                            </div>
                        </div>
                    </div>
                </div>`;
        }).join('');
    },

    resetTableFilters() {
        document.getElementById('filterGroup').value = '';
        document.getElementById('filterStatus').value = '';
        document.getElementById('filterKeyword').value = '';
        this.loadTables();
    },

    onKeywordSearch(e) {
        clearTimeout(this.keywordSearchTimer);
        this.keywordSearchTimer = setTimeout(() => this.loadTables(), 300);
    },

    // ==================== Detail Drawer ====================

    openDetail(tableCode) {
        document.getElementById('detailDrawerTitle').textContent = '表详情';
        const bsOffcanvas = new bootstrap.Offcanvas(document.getElementById('detailDrawer'));
        bsOffcanvas.show();

        document.getElementById('detailInfoBody').innerHTML = '<div class="text-center text-muted py-3"><div class="spinner-border spinner-border-sm"></div></div>';
        document.getElementById('detailCheckBody').innerHTML = '<div class="text-center text-muted py-3"><div class="spinner-border spinner-border-sm"></div></div>';
        document.getElementById('detailHistoryBody').innerHTML = '<div class="text-center text-muted py-3"><div class="spinner-border spinner-border-sm"></div></div>';

        StockApp.get(this.apiBase + '/tables/' + tableCode, null, (resp) => {
            if (resp.code !== 200) {
                document.getElementById('detailInfoBody').innerHTML = '<p class="text-danger">' + StockApp.escapeHtml(resp.message) + '</p>';
                return;
            }
            const d = resp.data || {};
            document.getElementById('detailDrawerTitle').textContent = d.tableName || tableCode;
            this.renderDetailInfo(d);
            this.renderDetailCheck(d.checkItems || []);
        });

        StockApp.get(this.apiBase + '/tables/' + tableCode + '/pull-history', null, (resp) => {
            if (resp.code !== 200) {
                document.getElementById('detailHistoryBody').innerHTML = '<p class="text-danger">' + StockApp.escapeHtml(resp.message) + '</p>';
                return;
            }
            this.renderPullHistory(resp.data || []);
        });
    },

    renderDetailInfo(d) {
        const status = this.getStatusInfo(d.status);
        const rows = [
            ['表代码', d.tableCode],
            ['表名称', d.tableName],
            ['分组', this.getGroupLabel(d.tableGroup)],
            ['Tushare 接口', d.tushareApi || '-'],
            ['数据总量', this.formatCount(d.totalRows) + ' 行'],
            ['最新数据日期', this.formatDate(d.latestDate) || '-'],
            ['最早数据日期', this.formatDate(d.earliestDate) || '-'],
            ['更新频率', d.updateFrequency || '-'],
            ['预期更新时间', d.expectedUpdateTime || '-'],
            ['是否日频', d.isDaily ? '是' : '否'],
            ['最后检测时间', d.lastCheckTime || '-'],
            ['当前状态', `<span class="badge ${status.badgeClass}">${status.label}</span>`],
        ];
        document.getElementById('detailInfoBody').innerHTML = `
            <table class="table table-sm table-borderless">
                <tbody>
                    ${rows.map(r => `<tr><td class="text-muted" style="width:130px;">${r[0]}</td><td>${r[1] ?? '-'}</td></tr>`).join('')}
                </tbody>
            </table>`;
    },

    renderDetailCheck(items) {
        const body = document.getElementById('detailCheckBody');
        if (!items || !items.length) {
            body.innerHTML = '<p class="text-muted text-center py-3">暂无检测结果</p>';
            return;
        }
        body.innerHTML = items.map(item => {
            const icon = item.passed
                ? '<i class="bi bi-check-circle-fill text-success"></i>'
                : `<i class="bi bi-x-circle-fill ${item.level === 'ERROR' ? 'text-danger' : 'text-warning'}"></i>`;
            const detail = item.message
                ? `<div class="small text-muted mt-1 ps-3">${StockApp.escapeHtml(item.message)}</div>`
                : '';
            return `
                <div class="d-flex align-items-start mb-2 pb-2 border-bottom">
                    <div class="me-2">${icon}</div>
                    <div class="flex-grow-1">
                        <div class="fw-medium">${StockApp.escapeHtml(item.displayName || item.name)}</div>
                        ${detail}
                    </div>
                </div>`;
        }).join('');
    },

    renderPullHistory(logs) {
        const body = document.getElementById('detailHistoryBody');
        if (!logs || !logs.length) {
            body.innerHTML = '<p class="text-muted text-center py-3">暂无更新历史</p>';
            return;
        }
        body.innerHTML = `
            <div class="list-group">
                ${logs.map(log => {
                    const statusBadge = log.status === 'SUCCESS'
                        ? '<span class="badge bg-success">成功</span>'
                        : log.status === 'FAILED'
                        ? '<span class="badge bg-danger">失败</span>'
                        : '<span class="badge bg-info">运行中</span>';
                    return `
                        <div class="list-group-item list-group-item-action py-2" style="cursor:pointer;" onclick="DG.showLogDetail(${log.id})">
                            <div class="d-flex justify-content-between align-items-center">
                                <span class="small">${StockApp.escapeHtml(log.operationType || '-')}</span>
                                ${statusBadge}
                            </div>
                            <div class="small text-muted">
                                ${log.startTime || '-'} ~ ${log.endTime || '-'}
                                ${log.failCount > 0 ? ` · <span class="text-danger">失败 ${log.failCount}</span>` : ''}
                            </div>
                        </div>`;
                }).join('')}
            </div>`;
    },

    // ==================== Incremental Update ====================

    incrementalUpdate(tableCode) {
        StockApp.post(this.apiBase + '/tables/' + tableCode + '/incremental-update', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '增量更新启动失败', 'danger');
                return;
            }
            StockApp.toast('增量更新已启动', 'success');
            this.startProgressPolling(resp.data.taskId, resp.data.operationType, tableCode);
        });
    },

    // ==================== Full Rebuild ====================

    openRebuildModal(tableCode, tableName) {
        this.rebuildTableCode = tableCode;
        this.rebuildTableName = tableName;
        document.getElementById('rebuildTableName').textContent = tableName;
        document.getElementById('rebuildConfirmInput').value = '';
        const btn = document.getElementById('rebuildConfirmBtn');
        btn.disabled = true;
        document.getElementById('rebuildCountdown').textContent = ' (10s)';

        const modal = new bootstrap.Modal(document.getElementById('rebuildModal'));
        modal.show();

        // 10s countdown
        clearInterval(this.rebuildCountdownTimer);
        let count = 10;
        this.rebuildCountdownTimer = setInterval(() => {
            count--;
            if (count > 0) {
                document.getElementById('rebuildCountdown').textContent = ` (${count}s)`;
            } else {
                clearInterval(this.rebuildCountdownTimer);
                document.getElementById('rebuildCountdown').textContent = '';
                this.updateRebuildBtnState();
            }
        }, 1000);
    },

    onRebuildInput() {
        this.updateRebuildBtnState();
    },

    updateRebuildBtnState() {
        const input = document.getElementById('rebuildConfirmInput');
        const btn = document.getElementById('rebuildConfirmBtn');
        const countdownEl = document.getElementById('rebuildCountdown');
        const countdownActive = countdownEl.textContent.includes('s)');
        const nameMatch = input.value.trim() === this.rebuildTableName;
        btn.disabled = countdownActive || !nameMatch;
    },

    executeFullRebuild() {
        const input = document.getElementById('rebuildConfirmInput');
        if (input.value.trim() !== this.rebuildTableName) {
            StockApp.toast('表名不匹配', 'warning');
            return;
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById('rebuildModal'));
        modal.hide();
        clearInterval(this.rebuildCountdownTimer);

        StockApp.post(this.apiBase + '/tables/' + this.rebuildTableCode + '/full-rebuild', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '全量重建启动失败', 'danger');
                return;
            }
            StockApp.toast('全量重建已启动', 'success');
            this.startProgressPolling(resp.data.taskId, resp.data.operationType, this.rebuildTableCode);
        });
    },

    // ==================== Check All ====================

    checkAll() {
        StockApp.post(this.apiBase + '/check/all', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '检测失败', 'danger');
                return;
            }
            StockApp.toast('全量检测完成，批次: ' + (resp.data?.batchId || ''), 'success');
            this.refreshOverview();
            this.loadTables();
        });
    },

    // ==================== Task Progress ====================

    startProgressPolling(taskId, operationType, tableCode) {
        this.currentTaskId = taskId;
        this.pollStartTime = Date.now();
        const tableInfo = this.tableListCache.find(t => t.tableCode === tableCode);
        document.getElementById('progressTableBadge').textContent = tableInfo?.tableName || tableCode;
        document.getElementById('progressOpBadge').textContent = operationType || '-';
        document.getElementById('progressBar').style.width = '0%';
        document.getElementById('progressBar').textContent = '0%';
        document.getElementById('progressStep').textContent = '准备中...';
        document.getElementById('progressProcessed').textContent = '0';
        document.getElementById('progressTotal').textContent = '0';
        document.getElementById('progressElapsed').textContent = '0s';

        const modal = new bootstrap.Modal(document.getElementById('progressModal'));
        modal.show();

        clearInterval(this.pollTimer);
        this.pollTimer = setInterval(() => this.pollOnce(), 1000);
        this.pollOnce();
    },

    pollOnce() {
        if (!this.currentTaskId) return;
        StockApp.get(this.apiBase + '/tasks/' + this.currentTaskId + '/progress', null, (resp) => {
            if (resp.code !== 200) {
                clearInterval(this.pollTimer);
                StockApp.toast(resp.message || '查询进度失败', 'danger');
                return;
            }
            const d = resp.data || {};
            const pct = d.progressPct ?? 0;
            document.getElementById('progressBar').style.width = pct + '%';
            document.getElementById('progressBar').textContent = pct + '%';
            document.getElementById('progressStep').textContent = d.currentStep || '处理中...';
            document.getElementById('progressProcessed').textContent = d.processedItems ?? 0;
            document.getElementById('progressTotal').textContent = d.totalItems ?? 0;
            const elapsed = Math.floor((Date.now() - this.pollStartTime) / 1000);
            document.getElementById('progressElapsed').textContent = elapsed + 's';

            if (d.cancelled) {
                clearInterval(this.pollTimer);
                StockApp.toast('任务已取消', 'warning');
            } else if (pct >= 100) {
                clearInterval(this.pollTimer);
                StockApp.toast('任务完成', 'success');
                this.refreshOverview();
                this.loadTables();
            }
        });
    },

    cancelTask() {
        if (!this.currentTaskId) return;
        StockApp.post(this.apiBase + '/tasks/' + this.currentTaskId + '/cancel', null, (resp) => {
            if (resp.code === 200) {
                StockApp.toast('取消请求已发送', 'info');
            } else {
                StockApp.toast(resp.message || '取消失败', 'danger');
            }
        });
    },

    onProgressModalClose() {
        clearInterval(this.pollTimer);
    },

    // ==================== Pull Logs ====================

    loadLogs(page) {
        this.logPage = page;
        const params = {
            tableCode: document.getElementById('logFilterTable').value,
            status: document.getElementById('logFilterStatus').value,
            operationType: document.getElementById('logFilterOpType').value,
            startDate: this.toDateStr(document.getElementById('logFilterStart').value),
            endDate: this.toDateStr(document.getElementById('logFilterEnd').value),
            page: page,
            size: this.logPageSize,
        };
        StockApp.get(this.apiBase + '/logs', params, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载日志失败', 'danger');
                return;
            }
            this.renderLogTable(resp.data || {});
        });
    },

    renderLogTable(data) {
        const tbody = document.getElementById('logTableBody');
        const records = data.records || [];
        if (!records.length) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-center text-muted py-4">无日志记录</td></tr>';
        } else {
            tbody.innerHTML = records.map(log => {
                const statusBadge = log.status === 'SUCCESS'
                    ? '<span class="badge bg-success">成功</span>'
                    : log.status === 'FAILED'
                    ? '<span class="badge bg-danger">失败</span>'
                    : '<span class="badge bg-info">运行中</span>';
                const duration = log.durationMs != null
                    ? (log.durationMs >= 1000 ? (log.durationMs / 1000).toFixed(1) + 's' : log.durationMs + 'ms')
                    : '-';
                const counts = `${log.successCount ?? 0} / ${log.failCount ?? 0}`;
                return `
                    <tr style="cursor:pointer;" onclick="DG.showLogDetail(${log.id})">
                        <td>${StockApp.escapeHtml(log.tableName || log.tableCode || '-')}</td>
                        <td><small>${StockApp.escapeHtml(log.operationType || '-')}</small></td>
                        <td>${statusBadge}</td>
                        <td><small>${log.startTime || '-'}</small></td>
                        <td><small>${duration}</small></td>
                        <td><small>${counts}</small></td>
                        <td><small>${StockApp.escapeHtml(log.operator || '-')}</small></td>
                        <td class="text-center"><i class="bi bi-chevron-right text-muted"></i></td>
                    </tr>`;
            }).join('');
        }

        const total = data.total || 0;
        const totalPages = Math.ceil(total / this.logPageSize);
        document.getElementById('logPageInfo').textContent = `共 ${total} 条`;
        this.renderLogPagination(totalPages, data.page || 1);
    },

    renderLogPagination(totalPages, current) {
        const ul = document.getElementById('logPagination');
        if (totalPages <= 1) {
            ul.innerHTML = '';
            return;
        }
        let html = `<li class="page-item ${current <= 1 ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="DG.loadLogs(${current - 1});return false;">上一页</a>
        </li>`;
        for (let i = 1; i <= totalPages; i++) {
            if (i === 1 || i === totalPages || Math.abs(i - current) <= 2) {
                html += `<li class="page-item ${i === current ? 'active' : ''}">
                    <a class="page-link" href="#" onclick="DG.loadLogs(${i});return false;">${i}</a>
                </li>`;
            } else if (Math.abs(i - current) === 3) {
                html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
            }
        }
        html += `<li class="page-item ${current >= totalPages ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="DG.loadLogs(${current + 1});return false;">下一页</a>
        </li>`;
        ul.innerHTML = html;
    },

    showLogDetail(logId) {
        StockApp.get(this.apiBase + '/logs/' + logId, null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载详情失败', 'danger');
                return;
            }
            const log = resp.data || {};
            const statusBadge = log.status === 'SUCCESS'
                ? '<span class="badge bg-success">成功</span>'
                : log.status === 'FAILED'
                ? '<span class="badge bg-danger">失败</span>'
                : '<span class="badge bg-info">运行中</span>';
            const rows = [
                ['数据表', log.tableName || log.tableCode],
                ['操作类型', log.operationType],
                ['状态', statusBadge],
                ['开始时间', log.startTime || '-'],
                ['结束时间', log.endTime || '-'],
                ['耗时', log.durationMs != null ? log.durationMs + 'ms' : '-'],
                ['总数', log.totalCount ?? '-'],
                ['成功数', log.successCount ?? '-'],
                ['失败数', log.failCount ?? '-'],
                ['操作人', log.operator || '-'],
                ['任务ID', log.taskId || '-'],
            ];
            let html = `<table class="table table-sm table-borderless">
                <tbody>${rows.map(r => `<tr><td class="text-muted" style="width:100px;">${r[0]}</td><td>${r[1] ?? '-'}</td></tr>`).join('')}</tbody>
            </table>`;
            if (log.errorMessage) {
                html += `<div class="alert alert-danger"><strong>错误信息：</strong>${StockApp.escapeHtml(log.errorMessage)}</div>`;
            }
            if (this.isAdmin && log.errorStack) {
                html += `<div class="mt-2"><details><summary class="small text-muted">错误堆栈 (仅管理员可见)</summary><pre class="small mt-1 p-2 bg-light rounded" style="max-height:300px;overflow:auto;">${StockApp.escapeHtml(log.errorStack)}</pre></details></div>`;
            }
            document.getElementById('logDetailBody').innerHTML = html;
            new bootstrap.Modal(document.getElementById('logDetailModal')).show();
        });
    },

    populateLogTableDropdown(list) {
        const sel = document.getElementById('logFilterTable');
        const current = sel.value;
        const options = list.map(t =>
            `<option value="${t.tableCode}">${StockApp.escapeHtml(t.tableName)}</option>`
        ).join('');
        sel.innerHTML = '<option value="">全部</option>' + options;
        sel.value = current;
    },

    // ==================== Datasource ====================

    loadDatasource() {
        StockApp.get(this.apiBase + '/datasource', null, (resp) => {
            const body = document.getElementById('datasourceBody');
            if (resp.code !== 200) {
                body.innerHTML = '<p class="text-danger">' + StockApp.escapeHtml(resp.message) + '</p>';
                return;
            }
            const d = resp.data || {};
            const ok = d.lastTestOk;
            const statusBadge = d.status === 'ACTIVE'
                ? '<span class="badge bg-success">活跃</span>'
                : d.status === 'INACTIVE'
                ? '<span class="badge bg-danger">不可用</span>'
                : '<span class="badge bg-secondary">未知</span>';
            const testBtn = this.isAdmin
                ? `<button class="btn btn-primary btn-sm mt-2" onclick="DG.testDatasource()"><i class="bi bi-lightning"></i> 重新测试</button>`
                : '';
            body.innerHTML = `
                <div class="d-flex justify-content-between align-items-center mb-3">
                    <div>
                        <h6 class="mb-0">${StockApp.escapeHtml(d.sourceName || '-')}</h6>
                        <small class="text-muted mono">${d.sourceCode || '-'}</small>
                    </div>
                    ${statusBadge}
                </div>
                <table class="table table-sm table-borderless">
                    <tbody>
                        <tr><td class="text-muted" style="width:120px;">连通状态</td><td>${ok ? '<span class="text-success"><i class="bi bi-check-circle-fill"></i> 正常</span>' : '<span class="text-danger"><i class="bi bi-x-circle-fill"></i> 异常</span>'}</td></tr>
                        <tr><td class="text-muted">最后测试</td><td>${d.lastTestTime || '-'}</td></tr>
                        <tr><td class="text-muted">响应时间</td><td>${d.responseTimeMs > 0 ? d.responseTimeMs + 'ms' : '-'}</td></tr>
                        <tr><td class="text-muted">测试接口</td><td>${StockApp.escapeHtml(d.testInterface || '-')}</td></tr>
                    </tbody>
                </table>
                ${testBtn}`;
        });
    },

    testDatasource() {
        StockApp.post(this.apiBase + '/datasource/test', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '测试失败', 'danger');
                return;
            }
            const d = resp.data || {};
            StockApp.toast(d.lastTestOk ? '数据源连通正常 (' + d.responseTimeMs + 'ms)' : '数据源连通失败', d.lastTestOk ? 'success' : 'danger');
            this.loadDatasource();
        });
    },

    // ==================== Utilities ====================

    getStatusInfo(status) {
        const map = {
            NORMAL: { badgeClass: 'bg-success', label: '正常' },
            DELAYED: { badgeClass: 'bg-warning', label: '延迟' },
            ERROR: { badgeClass: 'bg-danger', label: '异常' },
            UPDATING: { badgeClass: 'bg-info', label: '更新中' },
        };
        return map[status] || { badgeClass: 'bg-secondary', label: status || '未知' };
    },

    getGroupLabel(group) {
        const map = {
            BASIC: '基础数据', MARKET: '行情数据', FINANCE: '财务数据',
            EVENT: '事件数据', INDEX: '指数与市场',
        };
        return map[group] || group || '-';
    },

    formatDate(dateStr) {
        if (!dateStr) return '';
        if (dateStr.length === 8 && /^\d{8}$/.test(dateStr)) {
            return dateStr.substring(0, 4) + '-' + dateStr.substring(4, 6) + '-' + dateStr.substring(6, 8);
        }
        return dateStr;
    },

    toDateStr(dateInput) {
        if (!dateInput) return '';
        return dateInput.replace(/-/g, '');
    },

    formatCount(num) {
        if (num == null || isNaN(num)) return '-';
        const n = Number(num);
        if (n >= 100000000) return (n / 100000000).toFixed(2) + '亿';
        if (n >= 10000) return (n / 10000).toFixed(2) + '万';
        return n.toLocaleString('zh-CN');
    },
};

// ==================== Tab Switch Lazy Loading ====================
document.getElementById('tab-logs').addEventListener('shown.bs.tab', () => {
    if (document.getElementById('logTableBody').textContent.includes('请点击搜索')) {
        DG.loadLogs(1);
    }
});
document.getElementById('tab-datasource').addEventListener('shown.bs.tab', () => {
    DG.loadDatasource();
});

// ==================== Init ====================
DG.refreshOverview();
DG.loadTables();
