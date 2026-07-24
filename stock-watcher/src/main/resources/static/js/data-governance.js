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
            tbody.innerHTML = `<tr><td colspan="8"><div class="empty-state-sm">
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
            ['预期更新时间', d.expectedUpdateTime || '-'],
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
            if (this.isAdmin && log.errorStack) {
                html += `<div class="mt-2"><details><summary class="small text-muted">错误堆栈 (仅管理员可见)</summary><pre class="small mt-1 p-2 rounded font-mono" style="background:var(--bg-tertiary);max-height:300px;overflow:auto;">${StockApp.escapeHtml(log.errorStack)}</pre></details></div>`;
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
};

// ==================== Init ====================
DG.refreshAll();

// Clean up polling when page unloads
window.addEventListener('beforeunload', () => DG.stopDatasourcePolling());
