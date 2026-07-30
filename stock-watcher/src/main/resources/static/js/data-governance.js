/**
 * Data Governance Center
 * Handles: overview stats, table list, detail modal, pull logs, check history,
 *          datasource status (10s polling), incremental update / full rebuild,
 *          per-table manual check.
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
    tableListCache: [],
    keywordSearchTimer: null,
    _btnLockMap: {},

    // ==================== Scheduled Tasks State ====================
    // 定时任务列表缓存 + 状态轮询 + 历史模态框状态
    taskListCache: [],
    taskListLoadError: false,
    taskPollTimer: null,
    TASK_POLL_INTERVAL: 5000, // RUNNING 行 5s 刷新
    taskKeywordSearchTimer: null,
    pendingRunTaskClass: null,
    pendingRunTaskName: null,
    // 历史模态框当前查询上下文
    taskHistoryContext: { taskClass: null, taskName: null, page: 1, limit: 30 },

    // ==================== Button Debounce Helper ====================

    withBtnLock(key, fn, btnEl) {
        if (this._btnLockMap[key]) return;
        this._btnLockMap[key] = true;
        const originalHtml = btnEl ? btnEl.innerHTML : null;
        const originalDisabled = btnEl ? btnEl.disabled : false;
        if (btnEl) {
            btnEl.disabled = true;
            btnEl.style.opacity = '0.6';
            btnEl.style.pointerEvents = 'none';
        }
        const release = () => {
            this._btnLockMap[key] = false;
            if (btnEl) {
                btnEl.disabled = originalDisabled;
                btnEl.style.opacity = '';
                btnEl.style.pointerEvents = '';
            }
        };
        try {
            const result = fn(release);
            if (result && typeof result.then === 'function') {
                result.then(release, release);
            } else if (result === false) {
                release();
            }
        } catch (e) {
            release();
            throw e;
        }
    },

    // Datasource polling: enter page -> test once -> poll GET every 10s
    dsPollTimer: null,
    DS_POLL_INTERVAL: 10000,

    // Group metadata: label + accent color token
    GROUP_META: {
        BASIC:   { label: '基础数据',   color: 'var(--accent-blue)' },
        MARKET:  { label: '行情数据',   color: 'var(--accent-cyan)' },
        FINANCE: { label: '财务数据',   color: 'var(--accent-purple)' },
        EVENT:   { label: '事件数据',   color: 'var(--accent-yellow)' },
        INDEX:   { label: '指数与市场', color: 'var(--accent-green)' },
    },

    // ==================== Init ====================

    refreshAll() {
        if (this._btnLockMap['refreshAll']) return;
        this._btnLockMap['refreshAll'] = true;
        setTimeout(() => { this._btnLockMap['refreshAll'] = false; }, 500);
        this.refreshOverview();
        this.loadTables();
        this.loadScheduledTasks();
        this.startDatasourcePolling();
    },

    // ==================== Overview ====================

    refreshOverview() {
        StockApp.get(this.apiBase + '/overview', null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载概览失败', 'danger');
                return;
            }
            const d = resp.data || {};
            const total = d.totalTables ?? 0;
            const errors = d.errorTables ?? 0;

            document.getElementById('ovTotalTables').textContent = total || '-';
            document.getElementById('ovUpdatedToday').textContent = d.updatedToday ?? '-';
            document.getElementById('ovErrorTables').textContent = errors;
            document.getElementById('ovLastCheck').textContent = '最后检测：' + (d.lastCheckTime || '-');
            document.getElementById('ovLastCheckTime').textContent = d.lastCheckTime || '-';

            document.getElementById('ovUpdatedFoot').textContent =
                errors > 0 ? `${errors} 张表异常` : '运行正常';
            document.getElementById('ovErrorFoot').textContent = errors > 0 ? '需关注' : '无异常';
        });
    },

    // ==================== Datasource Polling ====================

    startDatasourcePolling() {
        // On enter: trigger a live test (admin) or read cache (non-admin), then poll every 10s
        this.testDatasourceSilent();
        clearInterval(this.dsPollTimer);
        this.dsPollTimer = setInterval(() => this.loadDatasourceStatus(), this.DS_POLL_INTERVAL);
    },

    stopDatasourcePolling() {
        clearInterval(this.dsPollTimer);
    },

    testDatasourceSilent() {
        // Admin: POST test triggers a real connectivity check and updates cache
        if (this.isAdmin) {
            StockApp.post(this.apiBase + '/datasource/test', null, (resp) => {
                if (resp.code === 200) {
                    this.applyDatasourceStatus(resp.data || {});
                }
            });
        } else {
            // Non-admin: just read the cache
            this.loadDatasourceStatus();
        }
    },

    loadDatasourceStatus() {
        StockApp.get(this.apiBase + '/datasource', null, (resp) => {
            if (resp.code !== 200) {
                this.applyDatasourceStatus({});
                return;
            }
            this.applyDatasourceStatus(resp.data || {});
        });
    },

    applyDatasourceStatus(d) {
        const dot = document.getElementById('dsStatusDot');
        const label = document.getElementById('dsLabel');
        const indicator = document.getElementById('dsIndicator');
        if (d.lastTestOk) {
            dot.className = 'dg-ds-dot online';
            label.textContent = 'Tushare';
            indicator.title = '数据源正常 · ' + (d.lastTestTime || '');
        } else if (d.status === 'INACTIVE') {
            dot.className = 'dg-ds-dot offline';
            label.textContent = 'Tushare';
            indicator.title = '数据源异常 · ' + (d.lastTestTime || '');
        } else {
            dot.className = 'dg-ds-dot unknown';
            label.textContent = '数据源';
            indicator.title = '未检测';
        }
    },

    showDatasourceModal() {
        const modal = new bootstrap.Modal(document.getElementById('datasourceModal'));
        modal.show();
        this.loadDatasourceDetail();
    },

    loadDatasourceDetail() {
        const body = document.getElementById('datasourceBody');
        body.innerHTML = '<div class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-1"></div>加载中...</div>';
        StockApp.get(this.apiBase + '/datasource', null, (resp) => {
            if (resp.code !== 200) {
                body.innerHTML = '<p class="text-danger">' + StockApp.escapeHtml(resp.message) + '</p>';
                return;
            }
            const d = resp.data || {};
            const ok = d.lastTestOk;
            const statusBadge = d.status === 'ACTIVE'
                ? '<span class="badge bg-success badge-dot">活跃</span>'
                : d.status === 'INACTIVE'
                ? '<span class="badge bg-danger badge-dot">不可用</span>'
                : '<span class="badge bg-secondary badge-dot">未知</span>';
            body.innerHTML = `
                <div class="d-flex justify-content-between align-items-center mb-3">
                    <div>
                        <h6 class="mb-0">${StockApp.escapeHtml(d.sourceName || '-')}</h6>
                        <small class="text-muted font-mono">${d.sourceCode || '-'}</small>
                    </div>
                    ${statusBadge}
                </div>
                <ul class="dg-detail-list">
                    <li><span class="dl-label">连通状态</span>
                        <span class="dl-value">${ok ? '<span style="color:var(--accent-green)"><i class="bi bi-check-circle-fill"></i> 正常</span>' : '<span style="color:var(--rise-light)"><i class="bi bi-x-circle-fill"></i> 异常</span>'}</span></li>
                    <li><span class="dl-label">最后测试</span><span class="dl-value">${d.lastTestTime || '-'}</span></li>
                    <li><span class="dl-label">响应时间</span><span class="dl-value">${d.responseTimeMs > 0 ? d.responseTimeMs + 'ms' : '-'}</span></li>
                    <li><span class="dl-label">测试接口</span><span class="dl-value">${StockApp.escapeHtml(d.testInterface || '-')}</span></li>
                </ul>`;
        });
    },

    testDatasource() {
        if (this._btnLockMap['testDatasource']) return;
        this._btnLockMap['testDatasource'] = true;
        const btn = event?.target?.closest('button');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>测试中...';
        }
        StockApp.post(this.apiBase + '/datasource/test', null, (resp) => {
            this._btnLockMap['testDatasource'] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '测试失败', 'danger');
                return;
            }
            const d = resp.data || {};
            StockApp.toast(d.lastTestOk ? '数据源连通正常 (' + d.responseTimeMs + 'ms)' : '数据源连通失败', d.lastTestOk ? 'success' : 'danger');
            this.applyDatasourceStatus(d);
            this.loadDatasourceDetail();
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
            this.renderTable(this.tableListCache);
        });
    },

    renderTable(list) {
        document.getElementById('tableCount').textContent = (list?.length || 0) + ' 张';

        const tbody = document.getElementById('tableBody');
        if (!list || !list.length) {
            tbody.innerHTML = `<tr><td colspan="9"><div class="empty-state-sm">
                <i class="bi bi-inbox"></i>
                <p>无匹配的数据表</p>
            </div></td></tr>`;
            return;
        }
        tbody.innerHTML = list.map(t => {
            // Fix: if there are failed checks, display as ERROR regardless of backend status
            // (backend only sets ERROR for ERROR-level failures, not WARN-level)
            const displayStatus = this.getDisplayStatus(t.status, t.checkItems);
            const status = this.getStatusInfo(displayStatus);
            const failedItems = this.getFailedCheckItems(t.checkItems);

            // Build tooltip HTML for failed checks
            let tooltipAttr = '';
            if (failedItems.length > 0) {
                const tipHtml = failedItems.map(i =>
                    `<div>• <strong>${StockApp.escapeHtml(i.displayName || i.name)}</strong>${i.message ? ': ' + StockApp.escapeHtml(i.message) : ''}</div>`
                ).join('');
                tooltipAttr = ` data-bs-toggle="tooltip" data-bs-html="true" data-bs-custom-class="dg-status-tip" data-bs-title="${StockApp.escapeHtml(tipHtml)}"`;
            }

            const groupMeta = this.getGroupMeta(t.tableGroup);
            const failedBadge = t.failedCount > 0
                ? `<span class="badge bg-danger ms-1">${t.failedCount} 项</span>` : '';
            const adminButtons = this.isAdmin ? `
                <button class="btn btn-outline-secondary btn-sm" onclick="DG.incrementalUpdate('${t.tableCode}', event)" title="增量更新">
                    <i class="bi bi-arrow-up-circle ico-success"></i> 增量
                </button>
                <button class="btn btn-outline-secondary btn-sm" onclick="DG.openRebuildModal('${t.tableCode}', '${StockApp.escapeHtml(t.tableName)}')" title="全量重建">
                    <i class="bi bi-arrow-repeat ico-warning"></i> 全量
                </button>
            ` : '';
            return `
                <tr>
                    <td>
                        <div class="dg-table-name">${StockApp.escapeHtml(t.tableName)}</div>
                        <div class="dg-table-code">${t.tableCode}</div>
                    </td>
                    <td><span class="badge badge-pill" style="background: color-mix(in srgb, ${groupMeta.color} 14%, transparent); color: ${groupMeta.color}; border: 1px solid color-mix(in srgb, ${groupMeta.color} 25%, transparent);">${groupMeta.label}</span></td>
                    <td${tooltipAttr}>
                        <span class="dg-status ${status.cls}">
                            <span class="dot"></span>${status.label}
                        </span>
                        ${failedBadge}
                    </td>
                    <td class="text-end font-mono">${this.formatCount(t.totalRows)}</td>
                    <td class="font-mono">${this.formatDate(t.latestDate) || '-'}</td>
                    <td><small class="text-muted font-mono">${t.lastCheckTime || '-'}</small></td>
                    <td><small class="text-muted">${t.updateFrequency || '-'}</small></td>
                    <td><small class="text-muted font-mono">${t.nextExecutionTime || '-'}</small></td>
                    <td>
                        <div class="d-flex flex-wrap gap-1 justify-content-center">
                            <button class="btn btn-outline-secondary btn-sm" onclick="DG.openDetail('${t.tableCode}')" title="查看详情">
                                <i class="bi bi-eye ico-primary"></i> 详情
                            </button>
                            <button class="btn btn-outline-secondary btn-sm" onclick="DG.checkTable('${t.tableCode}', event)" title="手动检测">
                                <i class="bi bi-clipboard-check ico-info"></i> 检测
                            </button>
                            <button class="btn btn-outline-secondary btn-sm" onclick="DG.openPullHistory('${t.tableCode}', '${StockApp.escapeHtml(t.tableName)}')" title="拉取日志">
                                <i class="bi bi-clock-history ico-muted"></i> 日志
                            </button>
                            <button class="btn btn-outline-secondary btn-sm" onclick="DG.openCheckHistory('${t.tableCode}', '${StockApp.escapeHtml(t.tableName)}')" title="检测历史">
                                <i class="bi bi-graph-up ico-muted"></i> 历史
                            </button>
                            ${adminButtons}
                        </div>
                    </td>
                </tr>`;
        }).join('');

        // Initialize Bootstrap tooltips on status cells
        tbody.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
            bootstrap.Tooltip.getOrCreateInstance(el);
        });
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

    // ==================== Per-table Manual Check ====================

    checkTable(tableCode, event) {
        const lockKey = 'check_' + tableCode;
        if (this._btnLockMap[lockKey]) return;
        this._btnLockMap[lockKey] = true;
        const btn = event?.target?.closest('button');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>检测中';
        }
        StockApp.post(this.apiBase + '/check/' + tableCode, null, (resp) => {
            this._btnLockMap[lockKey] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '检测失败', 'danger');
                return;
            }
            const result = resp.data || {};
            const failed = (result.checkItems || []).filter(i => !i.passed).length;
            if (failed > 0) {
                StockApp.toast(`检测完成，发现 ${failed} 项异常`, 'warning');
            } else {
                StockApp.toast('检测完成，全部通过', 'success');
            }
            this.loadTables();
            this.refreshOverview();
        });
    },

    // ==================== Pull History Modal ====================

    openPullHistory(tableCode, tableName) {
        document.getElementById('pullHistoryTableName').textContent = tableName;
        const modal = new bootstrap.Modal(document.getElementById('pullHistoryModal'));
        modal.show();
        const body = document.getElementById('pullHistoryBody');
        body.innerHTML = '<tr><td colspan="8" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-1"></div>加载中...</td></tr>';
        StockApp.get(this.apiBase + '/tables/' + tableCode + '/pull-history', null, (resp) => {
            if (resp.code !== 200) {
                body.innerHTML = '<tr><td colspan="8" class="text-center text-danger py-4">' + StockApp.escapeHtml(resp.message) + '</td></tr>';
                return;
            }
            const logs = resp.data || [];
            if (!logs.length) {
                body.innerHTML = `<tr><td colspan="8"><div class="empty-state-sm"><i class="bi bi-inbox"></i><p>暂无拉取日志</p></div></td></tr>`;
                return;
            }
            body.innerHTML = logs.map(log => {
                const statusBadge = this.getLogStatusBadge(log.status);
                const duration = log.durationMs != null
                    ? (log.durationMs >= 1000 ? (log.durationMs / 1000).toFixed(1) + 's' : log.durationMs + 'ms')
                    : '-';
                const counts = `${log.successCount ?? 0} / ${log.failCount ?? 0}`;
                return `
                    <tr style="cursor:pointer;" onclick="DG.showLogDetail(${log.id})">
                        <td><small>${StockApp.escapeHtml(this.getOperationTypeLabel(log.operationType))}</small></td>
                        <td>${statusBadge}</td>
                        <td><small class="font-mono">${log.startTime || '-'}</small></td>
                        <td><small class="font-mono">${log.endTime || '-'}</small></td>
                        <td class="text-end font-mono">${duration}</td>
                        <td class="text-end font-mono">${counts}</td>
                        <td><small>${StockApp.escapeHtml(log.operator || '-')}</small></td>
                        <td class="text-center"><i class="bi bi-chevron-right text-muted"></i></td>
                    </tr>`;
            }).join('');
        });
    },

    // ==================== Check History Modal ====================

    openCheckHistory(tableCode, tableName) {
        document.getElementById('checkHistoryTableName').textContent = tableName;
        const modal = new bootstrap.Modal(document.getElementById('checkHistoryModal'));
        modal.show();
        const body = document.getElementById('checkHistoryBody');
        body.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-1"></div>加载中...</td></tr>';
        StockApp.get(this.apiBase + '/tables/' + tableCode + '/check-history', null, (resp) => {
            if (resp.code !== 200) {
                body.innerHTML = '<tr><td colspan="7" class="text-center text-danger py-4">' + StockApp.escapeHtml(resp.message) + '</td></tr>';
                return;
            }
            const history = resp.data || [];
            if (!history.length) {
                body.innerHTML = `<tr><td colspan="7"><div class="empty-state-sm"><i class="bi bi-inbox"></i><p>暂无检测记录</p></div></td></tr>`;
                return;
            }
            body.innerHTML = history.map(m => {
                // Fix status: show ERROR when there are failed checks
                const displayStatus = this.getDisplayStatus(m.status, m.checkItems);
                const status = this.getStatusInfo(displayStatus);
                const failedItems = this.getFailedCheckItems(m.checkItems);

                // Build anomaly details cell
                let anomalyCell;
                if (failedItems.length === 0) {
                    anomalyCell = '<span class="dg-anomaly-pass"><i class="bi bi-check-circle-fill"></i> 全部通过</span>';
                } else {
                    anomalyCell = `<div class="dg-anomaly-list">` +
                        failedItems.map(i =>
                            `<div class="dg-anomaly-item">• <strong>${StockApp.escapeHtml(i.displayName || i.name)}</strong>${i.message ? ': ' + StockApp.escapeHtml(i.message) : ''}</div>`
                        ).join('') +
                        `</div>`;
                }

                const delta = m.rowDeltaPct != null
                    ? (m.rowDeltaPct > 0 ? '+' : '') + m.rowDeltaPct + '%'
                    : '-';
                const deltaClass = m.rowDeltaPct != null
                    ? (m.rowDeltaPct >= 0 ? 'rise' : 'fall')
                    : 'text-muted';
                return `
                    <tr>
                        <td><small class="font-mono">${m.checkTime || '-'}</small></td>
                        <td>${m.checkType === 'MANUAL' ? '<span class="badge bg-secondary">手动</span>' : '<span class="badge bg-light">定时</span>'}</td>
                        <td><span class="dg-status ${status.cls}"><span class="dot"></span>${status.label}</span></td>
                        <td class="dg-anomaly-cell">${anomalyCell}</td>
                        <td class="text-end font-mono">${this.formatCount(m.totalRows)}</td>
                        <td class="font-mono">${this.formatDate(m.latestDate) || '-'}</td>
                        <td class="text-end font-mono ${deltaClass}">${delta}</td>
                    </tr>`;
            }).join('');
        });
    },

    // ==================== Detail Modal (basic info + latest check results) ====================

    openDetail(tableCode) {
        document.getElementById('detailModalTitle').textContent = '表详情';
        const modal = new bootstrap.Modal(document.getElementById('detailModal'));
        modal.show();

        document.getElementById('detailInfoBody').innerHTML = '<div class="text-center text-muted py-3"><div class="spinner-border spinner-border-sm"></div></div>';
        document.getElementById('detailCheckBody').innerHTML = '';

        StockApp.get(this.apiBase + '/tables/' + tableCode, null, (resp) => {
            if (resp.code !== 200) {
                document.getElementById('detailInfoBody').innerHTML = '<p class="text-danger">' + StockApp.escapeHtml(resp.message) + '</p>';
                return;
            }
            const d = resp.data || {};
            document.getElementById('detailModalTitle').textContent = d.tableName || tableCode;
            this.renderDetailInfo(d);
            this.renderDetailCheck(d.checkItems || []);
        });
    },

    renderDetailInfo(d) {
        const displayStatus = this.getDisplayStatus(d.status, d.checkItems);
        const status = this.getStatusInfo(displayStatus);
        const groupMeta = this.getGroupMeta(d.tableGroup);
        const rows = [
            ['表代码', d.tableCode],
            ['表名称', d.tableName],
            ['分组', `<span class="badge badge-pill" style="background: color-mix(in srgb, ${groupMeta.color} 14%, transparent); color: ${groupMeta.color}; border: 1px solid color-mix(in srgb, ${groupMeta.color} 25%, transparent);">${groupMeta.label}</span>`],
            ['Tushare 接口', d.tushareApi || '-'],
            ['数据总量', this.formatCount(d.totalRows) + ' 行'],
            ['最新数据日期', this.formatDate(d.latestDate) || '-'],
            ['最早数据日期', this.formatDate(d.earliestDate) || '-'],
            ['更新频率', d.updateFrequency || '-'],
            ['是否日频', d.isDaily ? '是' : '否'],
            ['最后检测时间', d.lastCheckTime || '-'],
            ['当前状态', `<span class="badge bg-${status.cls === 'normal' ? 'success' : status.cls === 'error' ? 'danger' : status.cls === 'delayed' ? 'warning' : 'info'} badge-dot">${status.label}</span>`],
        ];
        document.getElementById('detailInfoBody').innerHTML = `
            <ul class="dg-detail-list two-col">
                ${rows.map(r => `<li><span class="dl-label">${r[0]}</span><span class="dl-value">${r[1] ?? '-'}</span></li>`).join('')}
            </ul>`;
    },

    renderDetailCheck(items) {
        const body = document.getElementById('detailCheckBody');
        if (!items || !items.length) {
            body.innerHTML = `<div class="empty-state-sm"><i class="bi bi-clipboard-check"></i><p>暂无检测结果</p></div>`;
            return;
        }
        body.innerHTML = items.map(item => {
            const iconClass = item.passed ? 'pass' : (item.level === 'ERROR' ? 'fail' : 'warn');
            const icon = item.passed ? 'bi-check' : 'bi-x';
            const detail = item.message
                ? `<div class="dg-check-msg">${StockApp.escapeHtml(item.message)}</div>`
                : '';
            return `
                <div class="dg-check-item">
                    <div class="dg-check-icon ${iconClass}"><i class="bi ${icon}"></i></div>
                    <div class="flex-grow-1">
                        <div class="dg-check-name">${StockApp.escapeHtml(item.displayName || item.name)}</div>
                        ${detail}
                    </div>
                </div>`;
        }).join('');
    },

    // ==================== Incremental Update ====================

    incrementalUpdate(tableCode, event) {
        const lockKey = 'incr_' + tableCode;
        if (this._btnLockMap[lockKey]) return;
        this._btnLockMap[lockKey] = true;
        const btn = event?.target?.closest('button');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>启动中';
        }
        StockApp.post(this.apiBase + '/tables/' + tableCode + '/incremental-update', null, (resp) => {
            this._btnLockMap[lockKey] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
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
        if (this._btnLockMap['executeRebuild']) return;
        this._btnLockMap['executeRebuild'] = true;
        const btn = document.getElementById('rebuildConfirmBtn');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1 ico-white"></span>启动中...';
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById('rebuildModal'));
        modal.hide();
        clearInterval(this.rebuildCountdownTimer);

        StockApp.post(this.apiBase + '/tables/' + this.rebuildTableCode + '/full-rebuild', null, (resp) => {
            this._btnLockMap['executeRebuild'] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '全量重建启动失败', 'danger');
                return;
            }
            StockApp.toast('全量重建已启动', 'success');
            this.startProgressPolling(resp.data.taskId, resp.data.operationType, this.rebuildTableCode);
        });
    },

    // ==================== Check All ====================

    checkAll(event) {
        if (this._btnLockMap['checkAll']) return;
        this._btnLockMap['checkAll'] = true;
        const btn = event?.target?.closest('button');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1 ico-white"></span>检测中...';
        }
        StockApp.post(this.apiBase + '/check/all', null, (resp) => {
            this._btnLockMap['checkAll'] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '检测失败', 'danger');
                return;
            }
            StockApp.toast('全量检测已启动', 'success');
            this.startProgressPolling(resp.data.taskId, 'MANUAL_CHECK_ALL', 'ALL');
        });
    },

    // ==================== Task Loading ====================

    startProgressPolling(taskId, operationType, tableCode) {
        this.currentTaskId = taskId;
        this.pollStartTime = Date.now();
        const tableInfo = this.tableListCache.find(t => t.tableCode === tableCode);
        document.getElementById('progressTableBadge').textContent = tableInfo?.tableName || tableCode;
        document.getElementById('progressOpBadge').textContent = this.getOperationTypeLabel(operationType);
        document.getElementById('progressStep').textContent = '处理中，请稍候...';
        document.getElementById('progressElapsed').textContent = '0s';

        // Reset progress bar visibility
        document.getElementById('progressSpinnerWrap').style.display = '';
        document.getElementById('progressBarWrap').style.display = 'none';
        document.getElementById('progressBar').style.width = '0%';
        document.getElementById('progressCountText').textContent = '0 / 0';
        document.getElementById('progressPercentText').textContent = '0%';

        const modal = new bootstrap.Modal(document.getElementById('progressModal'));
        modal.show();

        clearInterval(this.pollTimer);
        this.pollTimer = setInterval(() => this.pollOnce(), 2000);
        this.pollOnce();
    },

    pollOnce() {
        if (!this.currentTaskId) return;
        StockApp.get(this.apiBase + '/tasks/' + this.currentTaskId + '/progress', null, (resp) => {
            if (resp.code !== 200) {
                clearInterval(this.pollTimer);
                StockApp.toast(resp.message || '查询任务状态失败', 'danger');
                this.closeProgressModal();
                return;
            }
            const d = resp.data || {};
            document.getElementById('progressStep').textContent = d.currentStep || '处理中，请稍候...';
            const elapsed = Math.floor((Date.now() - this.pollStartTime) / 1000);
            document.getElementById('progressElapsed').textContent = elapsed + 's';

            // Update progress bar if totalCount is available
            if (d.totalCount != null && d.totalCount > 0) {
                const idx = d.currentIndex != null ? d.currentIndex : 0;
                const percent = Math.min(100, Math.round((idx / d.totalCount) * 100));
                document.getElementById('progressSpinnerWrap').style.display = 'none';
                document.getElementById('progressBarWrap').style.display = '';
                document.getElementById('progressBar').style.width = percent + '%';
                document.getElementById('progressCountText').textContent = idx + ' / ' + d.totalCount;
                document.getElementById('progressPercentText').textContent = percent + '%';
            } else {
                document.getElementById('progressSpinnerWrap').style.display = '';
                document.getElementById('progressBarWrap').style.display = 'none';
            }

            if (d.cancelled || d.status === 'CANCELLED') {
                clearInterval(this.pollTimer);
                StockApp.toast('任务已取消', 'warning');
                this.refreshAll();
                this.closeProgressModal();
            } else if (d.status === 'SUCCESS') {
                clearInterval(this.pollTimer);
                StockApp.toast('任务完成', 'success');
                this.refreshAll();
                this.closeProgressModal();
            } else if (d.status === 'FAILED') {
                clearInterval(this.pollTimer);
                StockApp.toast('任务失败' + (d.errorMessage ? ': ' + d.errorMessage : ''), 'danger');
                this.refreshAll();
                this.closeProgressModal();
            }
        });
    },

    cancelTask() {
        if (!this.currentTaskId) return;
        if (this._btnLockMap['cancelTask']) return;
        this._btnLockMap['cancelTask'] = true;
        const btn = document.getElementById('cancelTaskBtn');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>取消中...';
        }
        StockApp.post(this.apiBase + '/tasks/' + this.currentTaskId + '/cancel', null, (resp) => {
            this._btnLockMap['cancelTask'] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code === 200) {
                StockApp.toast('取消请求已发送', 'info');
                this.closeProgressModal();
            } else {
                StockApp.toast(resp.message || '取消失败', 'danger');
            }
        });
    },

    closeProgressModal() {
        clearInterval(this.pollTimer);
        this.currentTaskId = null;
        const modalEl = document.getElementById('progressModal');
        if (modalEl) {
            const modal = bootstrap.Modal.getInstance(modalEl);
            if (modal) {
                modal.hide();
            }
        }
    },

    onProgressModalClose() {
        clearInterval(this.pollTimer);
    },

    // ==================== Log Detail ====================

    showLogDetail(logId) {
        StockApp.get(this.apiBase + '/logs/' + logId, null, (resp) => {
            if (resp.code !== 200) {
                StockApp.toast(resp.message || '加载详情失败', 'danger');
                return;
            }
            const log = resp.data || {};
            const statusBadge = this.getLogStatusBadge(log.status);
            const rows = [
                ['数据表', log.tableName || log.tableCode],
                ['操作类型', this.getOperationTypeLabel(log.operationType)],
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
            let html = `<ul class="dg-detail-list">
                ${rows.map(r => `<li><span class="dl-label">${r[0]}</span><span class="dl-value">${r[1] ?? '-'}</span></li>`).join('')}
            </ul>`;
            if (log.errorMessage) {
                html += `<div class="alert alert-danger mt-3"><strong>错误信息：</strong>${StockApp.escapeHtml(log.errorMessage)}</div>`;
            }
            document.getElementById('logDetailBody').innerHTML = html;
            new bootstrap.Modal(document.getElementById('logDetailModal')).show();
        });
    },

    // ==================== Utilities ====================

    /**
     * Parse checkItems from a JSON string (backend stores it as JSON in DataGovernanceMetricDO).
     * If already an array (e.g. from TableStatusVO/TableDetailVO), returns as-is.
     */
    parseCheckItems(checkItems) {
        if (!checkItems) return [];
        if (Array.isArray(checkItems)) return checkItems;
        try {
            return JSON.parse(checkItems);
        } catch (e) {
            return [];
        }
    },

    /**
     * Extract failed (not passed) check items from a checkItems list.
     */
    getFailedCheckItems(checkItems) {
        return this.parseCheckItems(checkItems).filter(i => !i.passed);
    },

    /**
     * Compute the display status based on backend status and check items.
     * Backend only sets ERROR for ERROR-level failures; WARN-level failures
     * leave status as NORMAL, which is misleading. This ensures any failed
     * check item shows as ERROR in the UI.
     */
    getDisplayStatus(status, checkItems) {
        if (status === 'UPDATING') return status;
        const failed = this.getFailedCheckItems(checkItems);
        if (failed.length > 0) return 'ERROR';
        return status;
    },

    /**
     * 操作类型枚举值 -> 中文名称映射。
     */
    getOperationTypeLabel(operationType) {
        const map = {
            MANUAL_INCREMENTAL: '手动增量更新',
            MANUAL_FULL:        '手动全量重建',
            MANUAL_CHECK_ALL:   '手动全量检测',
        };
        return map[operationType] || operationType || '-';
    },

    getStatusInfo(status) {
        const map = {
            NORMAL:   { cls: 'normal',   label: '正常' },
            DELAYED:  { cls: 'delayed',  label: '延迟' },
            ERROR:    { cls: 'error',    label: '异常' },
            UPDATING: { cls: 'updating', label: '更新中' },
        };
        return map[status] || { cls: 'auto', label: status || '未知' };
    },

    getGroupMeta(group) {
        return this.GROUP_META[group] || { label: group || '-', color: 'var(--text-muted)' };
    },

    getLogStatusBadge(status) {
        const map = {
            SUCCESS: '<span class="badge bg-success badge-dot">成功</span>',
            FAILED:  '<span class="badge bg-danger badge-dot">失败</span>',
            RUNNING: '<span class="badge bg-info badge-dot">运行中</span>',
        };
        return map[status] || `<span class="badge bg-secondary">${status || '-'}</span>`;
    },

    formatDate(dateStr) {
        if (!dateStr) return '';
        if (dateStr.length === 8 && /^\d{8}$/.test(dateStr)) {
            return dateStr.substring(0, 4) + '-' + dateStr.substring(4, 6) + '-' + dateStr.substring(6, 8);
        }
        return dateStr;
    },

    formatCount(num) {
        if (num == null || isNaN(num)) return '-';
        const n = Number(num);
        if (n >= 100000000) return (n / 100000000).toFixed(2) + '亿';
        if (n >= 10000) return (n / 10000).toFixed(2) + '万';
        return n.toLocaleString('zh-CN');
    },

    // ==================== Scheduled Tasks List ====================

    // 任务分组元信息（taskGroup 英文 -> 中文标签 + accent color token）
    TASK_GROUP_META: {
        DATA_FETCH:   { label: '数据拉取', color: 'var(--accent-cyan)' },
        GOVERNANCE:   { label: '数据治理', color: 'var(--accent-blue)' },
        MAINTENANCE:  { label: '维护',     color: 'var(--accent-purple)' },
        PRECOMPUTE:   { label: '预计算',   color: 'var(--accent-green)' },
        VERIFY:       { label: '校验',     color: 'var(--accent-yellow)' },
    },

    loadScheduledTasks() {
        const params = {
            group: document.getElementById('taskFilterGroup').value,
            keyword: document.getElementById('taskFilterKeyword').value,
        };
        // 后端不支持 status 过滤，前端按 currentStatus 过滤
        const statusFilter = document.getElementById('taskFilterStatus').value;
        const tbody = document.getElementById('taskTableBody');
        // 仅在首次进入或刷新按钮触发时显示骨架屏；轮询时不覆盖（避免闪动）
        if (!this._taskPollingActive) {
            tbody.innerHTML = this._renderTaskSkeletonRows(5);
        }
        StockApp.get(this.apiBase + '/scheduled-tasks', params, (resp) => {
            if (resp.code !== 200) {
                this.taskListLoadError = true;
                this.taskListCache = [];
                this.renderTaskError(resp.message || '加载定时任务失败');
                this.stopTaskPolling();
                return;
            }
            this.taskListLoadError = false;
            let list = resp.data || [];
            if (statusFilter) {
                list = list.filter(t => (t.currentStatus || t.lastStatus) === statusFilter);
            }
            this.taskListCache = list;
            this.renderScheduledTasks(list);
            this._maybeStartOrStopTaskPolling(list);
        });
    },

    /**
     * 渲染定时任务表格。
     * 三态：列表为空 -> 空状态；正常 -> 行；错误状态由 renderTaskError 处理。
     */
    renderScheduledTasks(list) {
        document.getElementById('taskCount').textContent = (list?.length || 0) + ' 个';
        const tbody = document.getElementById('taskTableBody');
        if (!list || !list.length) {
            tbody.innerHTML = `
                <tr><td colspan="8">
                    <div class="empty-state">
                        <i class="bi bi-clock-history"></i>
                        <h5>暂无定时任务</h5>
                        <p>未匹配到任何任务，请调整筛选条件或刷新重试。</p>
                    </div>
                </td></tr>`;
            return;
        }
        tbody.innerHTML = list.map(t => {
            const status = (t.currentStatus || t.lastStatus || 'NEVER_RUN');
            const statusInfo = this.getTaskStatusInfo(status);
            const groupMeta = this.getTaskGroupMeta(t.taskGroup);
            const inconsistentBadge = '';
            const isRunning = status === 'RUNNING';
            const runBtnDisabled = isRunning || !this.isAdmin;
            const runBtnTitle = isRunning ? '任务执行中' : (!this.isAdmin ? '需要管理员权限' : '手动重跑任务');
            const cronCell = t.cronExpression
                ? `<small class="dg-cron-expr text-muted font-mono">${StockApp.escapeHtml(t.cronExpression)}</small>`
                : `<small class="text-muted">-</small>`;
            const tableCell = t.tableCode
                ? `<div class="dg-table-name">${StockApp.escapeHtml(t.tableName || t.tableCode)}</div><div class="dg-table-code">${t.tableCode}</div>`
                : `<small class="text-muted">-</small>`;
            const duration = this.formatDuration(t.lastDurationMs);
            return `
                <tr class="${isRunning ? 'dg-task-row-running' : ''}" data-task-class="${StockApp.escapeHtml(t.taskClass)}">
                    <td>
                        <div class="dg-task-name">${StockApp.escapeHtml(t.taskName || '-')}</div>
                        ${t.description ? `<small class="dg-task-desc text-muted">${StockApp.escapeHtml(t.description)}</small>` : ''}
                    </td>
                    <td><span class="badge badge-pill" style="background: color-mix(in srgb, ${groupMeta.color} 14%, transparent); color: ${groupMeta.color}; border: 1px solid color-mix(in srgb, ${groupMeta.color} 25%, transparent);">${StockApp.escapeHtml(groupMeta.label)}</span></td>
                    <td>${tableCell}</td>
                    <td>${cronCell}</td>
                    <td>
                        <span class="dg-status dg-task-status ${statusInfo.cls}">
                            <span class="dot"></span>${statusInfo.label}
                        </span>
                        ${inconsistentBadge}
                    </td>
                    <td class="text-end font-mono">${duration}</td>
                    <td><small class="text-muted font-mono">${t.nextExecutionTime || '-'}</small></td>
                    <td>
                        <div class="d-flex flex-wrap gap-1 justify-content-center">
                            <button class="btn btn-outline-secondary btn-sm" onclick="DG.openTaskHistory('${StockApp.escapeHtml(t.taskClass)}', '${StockApp.escapeHtml(t.taskName || '')}')" title="查看执行历史">
                                <i class="bi bi-clock-history ico-info"></i> 历史
                            </button>
                            <button class="btn btn-outline-secondary btn-sm" ${runBtnDisabled ? 'disabled' : ''} onclick="DG.confirmRunTask('${StockApp.escapeHtml(t.taskClass)}', '${StockApp.escapeHtml(t.taskName || '')}')" title="${runBtnTitle}">
                                <i class="bi bi-play-fill ico-success"></i> 重跑
                            </button>
                        </div>
                    </td>
                </tr>`;
        }).join('');
    },

    _renderTaskSkeletonRows(n) {
        let html = '';
        for (let i = 0; i < n; i++) {
            html += `<tr><td colspan="8"><div class="dg-skeleton-row">
                <div class="dg-skel-bar" style="width: ${30 + (i * 7) % 50}%;"></div>
                <div class="dg-skel-bar dg-skel-sm" style="width: ${20 + (i * 5) % 30}%;"></div>
            </div></td></tr>`;
        }
        return html;
    },

    renderTaskError(message) {
        const tbody = document.getElementById('taskTableBody');
        tbody.innerHTML = `
            <tr><td colspan="8">
                <div class="empty-state">
                    <i class="bi bi-exclamation-triangle" style="color: var(--rise-light);"></i>
                    <h5>加载失败</h5>
                    <p>${StockApp.escapeHtml(message)}</p>
                    <button class="btn btn-outline-secondary btn-sm" onclick="DG.loadScheduledTasks()">
                        <i class="bi bi-arrow-clockwise"></i> 重试
                    </button>
                </div>
            </td></tr>`;
    },

    onTaskKeywordSearch(e) {
        clearTimeout(this.taskKeywordSearchTimer);
        this.taskKeywordSearchTimer = setTimeout(() => this.loadScheduledTasks(), 300);
    },

    resetTaskFilters() {
        document.getElementById('taskFilterGroup').value = '';
        document.getElementById('taskFilterStatus').value = '';
        document.getElementById('taskFilterKeyword').value = '';
        this.loadScheduledTasks();
    },

    // ==================== RUNNING 状态实时轮询（5s） ====================

    /**
     * 列表中存在 RUNNING 行 -> 启动 5s 轮询；否则停止。
     * 轮询期间标记 _taskPollingActive 防止骨架屏覆盖当前展示。
     */
    _maybeStartOrStopTaskPolling(list) {
        const hasRunning = (list || []).some(t => (t.currentStatus || t.lastStatus) === 'RUNNING');
        if (hasRunning) {
            this.startTaskPolling();
        } else {
            this.stopTaskPolling();
        }
    },

    startTaskPolling() {
        if (this.taskPollTimer) return;
        this._taskPollingActive = true;
        this.taskPollTimer = setInterval(() => {
            this.loadScheduledTasks();
        }, this.TASK_POLL_INTERVAL);
    },

    stopTaskPolling() {
        this._taskPollingActive = false;
        if (this.taskPollTimer) {
            clearInterval(this.taskPollTimer);
            this.taskPollTimer = null;
        }
    },

    // ==================== Task History Modal ====================

    openTaskHistory(taskClass, taskName) {
        this.taskHistoryContext = {
            taskClass: taskClass,
            taskName: taskName,
            page: 1,
            limit: 30,
        };
        document.getElementById('taskHistoryName').textContent = taskName || taskClass;
        document.getElementById('taskHistoryStatusFilter').value = '';
        document.getElementById('taskHistoryStartDate').value = '';
        document.getElementById('taskHistoryPagination').innerHTML = '';
        const modal = new bootstrap.Modal(document.getElementById('taskHistoryModal'));
        modal.show();
        this.loadTaskHistory();
    },

    loadTaskHistory() {
        const ctx = this.taskHistoryContext;
        if (!ctx || !ctx.taskClass) return;
        const body = document.getElementById('taskHistoryBody');
        body.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-1"></div>加载中...</td></tr>';
        const params = {
            page: ctx.page,
            limit: ctx.limit,
            status: document.getElementById('taskHistoryStatusFilter').value,
            startDate: document.getElementById('taskHistoryStartDate').value,
        };
        StockApp.get(this.apiBase + '/scheduled-tasks/' + encodeURIComponent(ctx.taskClass) + '/history', params, (resp) => {
            if (resp.code !== 200) {
                body.innerHTML = '<tr><td colspan="7" class="text-center text-danger py-4">' + StockApp.escapeHtml(resp.message || '加载失败') + '</td></tr>';
                document.getElementById('taskHistoryTotal').textContent = '共 0 条';
                document.getElementById('taskHistoryPagination').innerHTML = '';
                return;
            }
            const data = resp.data || {};
            const records = data.list || data.records || [];
            const total = data.total || 0;
            const pageSize = data.size || data.pageSize || ctx.limit;
            this.renderTaskHistory(records);
            this.renderTaskHistoryPagination(total, ctx.page, pageSize);
            document.getElementById('taskHistoryTotal').textContent = '共 ' + total + ' 条';
        });
    },

    /**
     * 渲染历史记录。errorMessage 折叠（前 100 字符 + 点击展开），FAILED 行淡红色高亮。
     */
    renderTaskHistory(records) {
        const body = document.getElementById('taskHistoryBody');
        if (!records || !records.length) {
            body.innerHTML = `<tr><td colspan="7"><div class="empty-state-sm"><i class="bi bi-inbox"></i><p>暂无执行记录</p></div></td></tr>`;
            return;
        }
        body.innerHTML = records.map((r, idx) => {
            const statusBadge = this.getTaskHistoryStatusBadge(r.status);
            const duration = this.formatDuration(r.durationMs);
            const isFailed = r.status === 'FAILED';
            const errCell = this._renderErrorCell(r.errorMessage, idx);
            const triggerLabel = this._getTriggerTypeLabel(r.triggerType);
            return `
                <tr class="${isFailed ? 'dg-thistory-row-failed' : ''}">
                    <td><small class="font-mono">${r.startTime || '-'}</small></td>
                    <td><small class="font-mono">${r.endTime || '-'}</small></td>
                    <td>${statusBadge}</td>
                    <td class="text-end font-mono">${duration}</td>
                    <td><small>${triggerLabel}</small></td>
                    <td><small>${StockApp.escapeHtml(r.operator || '-')}</small></td>
                    <td>${errCell}</td>
                </tr>`;
        }).join('');
    },

    _renderErrorCell(errorMessage, idx) {
        if (!errorMessage) {
            return '<span class="text-muted">-</span>';
        }
        const escaped = StockApp.escapeHtml(errorMessage);
        if (errorMessage.length <= 100) {
            return `<span class="dg-thistory-err" title="${escaped}">${escaped}</span>`;
        }
        const short = StockApp.escapeHtml(errorMessage.substring(0, 100));
        return `<span class="dg-thistory-err dg-thistory-err-collapse" data-idx="${idx}">
            <span class="dg-thistory-err-short">${short}<span class="dg-thistory-err-ellipsis">...</span></span>
            <span class="dg-thistory-err-full" style="display:none;">${escaped}</span>
            <a href="javascript:void(0)" class="dg-thistory-toggle" onclick="DG.toggleTaskHistoryError(${idx})">展开</a>
        </span>`;
    },

    toggleTaskHistoryError(idx) {
        const cell = document.querySelector(`.dg-thistory-err-collapse[data-idx="${idx}"]`);
        if (!cell) return;
        const short = cell.querySelector('.dg-thistory-err-short');
        const full = cell.querySelector('.dg-thistory-err-full');
        const toggle = cell.querySelector('.dg-thistory-toggle');
        if (full.style.display === 'none') {
            short.style.display = 'none';
            full.style.display = 'inline';
            toggle.textContent = '收起';
        } else {
            short.style.display = 'inline';
            full.style.display = 'none';
            toggle.textContent = '展开';
        }
    },

    onTaskHistoryFilterChange() {
        this.taskHistoryContext.page = 1;
        this.loadTaskHistory();
    },

    onTaskHistoryPageChange(page) {
        this.taskHistoryContext.page = page;
        this.loadTaskHistory();
    },

    onTaskHistoryModalClose() {
        // 重置上下文，避免下次打开闪烁
        this.taskHistoryContext = { taskClass: null, taskName: null, page: 1, limit: 30 };
    },

    renderTaskHistoryPagination(total, page, pageSize) {
        const container = document.getElementById('taskHistoryPagination');
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        if (totalPages <= 1) {
            container.innerHTML = '';
            return;
        }
        const maxButtons = 7;
        let start = Math.max(1, page - 3);
        let end = Math.min(totalPages, start + maxButtons - 1);
        start = Math.max(1, end - maxButtons + 1);
        let html = '';
        const mkItem = (p, label, active, disabled) => `
            <li class="page-item ${active ? 'active' : ''} ${disabled ? 'disabled' : ''}">
                <a class="page-link" href="javascript:void(0)" ${(!active && !disabled) ? `onclick="DG.onTaskHistoryPageChange(${p})"` : ''}>${label}</a>
            </li>`;
        html += mkItem(page - 1, '«', false, page <= 1);
        for (let p = start; p <= end; p++) {
            html += mkItem(p, String(p), p === page, false);
        }
        html += mkItem(page + 1, '»', false, page >= totalPages);
        container.innerHTML = html;
    },

    _getTriggerTypeLabel(type) {
        const map = { SCHEDULED: '定时', MANUAL: '手动' };
        return map[type] || (type || '-');
    },

    getTaskHistoryStatusBadge(status) {
        const map = {
            SUCCESS: '<span class="badge badge-dot dg-task-badge-success">成功</span>',
            FAILED:  '<span class="badge badge-dot dg-task-badge-failed">失败</span>',
            RUNNING: '<span class="badge badge-dot dg-task-badge-running">运行中</span>',
        };
        return map[status] || `<span class="badge badge-secondary">${status || '-'}</span>`;
    },

    // ==================== Manual Run Task ====================

    confirmRunTask(taskClass, taskName) {
        if (!this.isAdmin) {
            StockApp.toast('需要管理员权限', 'warning');
            return;
        }
        // 检查是否运行中
        const task = (this.taskListCache || []).find(t => t.taskClass === taskClass);
        if (task && (task.currentStatus === 'RUNNING')) {
            StockApp.toast('任务执行中，无法重跑', 'warning');
            return;
        }
        this.pendingRunTaskClass = taskClass;
        this.pendingRunTaskName = taskName;
        document.getElementById('runTaskName').textContent = taskName || taskClass;
        const modal = new bootstrap.Modal(document.getElementById('runTaskConfirmModal'));
        modal.show();
    },

    executeRunTask() {
        if (!this.pendingRunTaskClass) return;
        const lockKey = 'runTask_' + this.pendingRunTaskClass;
        if (this._btnLockMap[lockKey]) return;
        this._btnLockMap[lockKey] = true;
        const btn = document.getElementById('runTaskConfirmBtn');
        const originalHtml = btn ? btn.innerHTML : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>执行中...';
        }
        const modalEl = document.getElementById('runTaskConfirmModal');
        const modal = bootstrap.Modal.getInstance(modalEl);
        if (modal) modal.hide();
        const operator = 'MANUAL';
        const url = this.apiBase + '/scheduled-tasks/' + encodeURIComponent(this.pendingRunTaskClass) + '/run?operator=' + encodeURIComponent(operator);
        StockApp.post(url, null, (resp) => {
            this._btnLockMap[lockKey] = false;
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            }
            if (resp.code === 200) {
                StockApp.toast('任务已触发：' + (this.pendingRunTaskName || ''), 'success');
                // 立即刷新一次列表（后端可能尚未写入 RUNNING 记录，延迟 1s 再刷新）
                setTimeout(() => this.loadScheduledTasks(), 1000);
            } else if (resp.code === 409) {
                StockApp.toast(resp.message || '任务执行中，无法重跑', 'warning');
                this.loadScheduledTasks();
            } else {
                StockApp.toast(resp.message || '触发任务失败', 'danger');
            }
        });
    },

    // ==================== Task Helpers ====================

    getTaskStatusInfo(status) {
        const map = {
            SUCCESS:   { cls: 'dg-task-success',   label: '成功' },
            FAILED:    { cls: 'dg-task-failed',    label: '失败' },
            RUNNING:   { cls: 'dg-task-running',  label: '运行中' },
            NEVER_RUN: { cls: 'dg-task-never',     label: '从未执行' },
        };
        return map[status] || { cls: 'dg-task-never', label: status || '未知' };
    },

    getTaskGroupMeta(group) {
        return this.TASK_GROUP_META[group] || { label: group || '-', color: 'var(--text-muted)' };
    },

    /**
     * 毫秒 -> 人类可读耗时（如 1.2s / 200ms / 1m 30s）。
     */
    formatDuration(ms) {
        if (ms == null || isNaN(ms)) return '-';
        const n = Number(ms);
        if (n < 1000) return n + 'ms';
        if (n < 60000) return (n / 1000).toFixed(1) + 's';
        const m = Math.floor(n / 60000);
        const s = Math.floor((n % 60000) / 1000);
        return m + 'm ' + s + 's';
    },
};

// ==================== Init ====================
DG.refreshAll();

// Clean up polling when page unloads
window.addEventListener('beforeunload', () => {
    DG.stopDatasourcePolling();
    DG.stopTaskPolling();
});
