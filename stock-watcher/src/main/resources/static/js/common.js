/**
 * StockPulse - Common utilities
 * Provides: AJAX helpers, toast notifications, sidebar toggle, global search
 */
const StockApp = {
    contextPath: '',
    _enumCache: null,

    escapeHtml(str) {
        if (str == null) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },

    // ========== AJAX GET ==========
    get(url, params, callback) {
        const query = params ? '?' + new URLSearchParams(
            Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== '')
        ).toString() : '';
        fetch(this.contextPath + url + query, {
            headers: {'Accept': 'application/json'}
        })
            .then(r => r.json())
            .then(data => callback(data))
            .catch(err => this.toast('请求失败: ' + err.message, 'danger'));
    },

    // ========== AJAX POST ==========
    post(url, body, callback) {
        fetch(this.contextPath + url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: typeof body === 'string' ? body : JSON.stringify(body)
        })
            .then(r => r.json())
            .then(data => callback(data))
            .catch(err => this.toast('请求失败: ' + err.message, 'danger'));
    },

    // ========== Toast Notification ==========
    toast(message, type = 'success') {
        const container = document.getElementById('toastContainer');
        if (!container) return;

        const icons = {
            success: 'bi-check-circle-fill',
            danger: 'bi-exclamation-triangle-fill',
            warning: 'bi-exclamation-circle-fill',
            info: 'bi-info-circle-fill'
        };
        const id = 'toast_' + Date.now();
        const html = `
            <div id="${id}" class="toast align-items-center border-0 animate-scale-in" role="alert">
                <div class="d-flex">
                    <div class="toast-body">
                        <i class="bi ${icons[type] || icons.info} me-2"></i>${message}
                    </div>
                    <button type="button" class="btn-close me-2 m-auto" data-bs-dismiss="toast"></button>
                </div>
            </div>`;
        container.insertAdjacentHTML('beforeend', html);
        const el = document.getElementById(id);
        const toast = new bootstrap.Toast(el, {delay: 3000});
        toast.show();
        el.addEventListener('hidden.bs.toast', () => el.remove());
    },

    // ========== Add to Watchlist (reusable) ==========
    addWatchlist(code, btn) {
        this.post('/watchlist/' + code, null, function(resp) {
            StockApp.toast(resp.message, resp.code === 200 ? 'success' : 'warning');
        });
    },

    // ========== Enum Constants ==========
    loadConstants(callback) {
        if (this._enumCache) {
            if (callback) callback(this._enumCache);
            return;
        }
        this.get('/constants', null, function(resp) {
            if (resp.code === 200) {
                StockApp._enumCache = resp.data;
            }
            if (callback) callback(StockApp._enumCache);
        });
    },

    getEnumLabel(enumName, code) {
        if (!this._enumCache || !this._enumCache[enumName]) return code || '';
        const item = this._enumCache[enumName].find(o => o.code === code);
        return item ? item.label : (code || '');
    },

    // ========== Sidebar Toggle ==========
    toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        const overlay = document.getElementById('sidebarOverlay');
        if (sidebar) {
            if (window.innerWidth < 992) {
                sidebar.classList.toggle('mobile-open');
                if (overlay) overlay.classList.toggle('active');
            } else {
                sidebar.classList.toggle('collapsed');
                document.body.classList.toggle('sidebar-collapsed');
            }
        }
    },

    // ========== Format Number ==========
    formatNumber(num, decimals = 2) {
        if (num === null || num === undefined || isNaN(num)) return '-';
        return Number(num).toLocaleString('zh-CN', {
            minimumFractionDigits: decimals,
            maximumFractionDigits: decimals
        });
    },

    formatPercent(num, decimals = 2) {
        if (num === null || num === undefined || isNaN(num)) return '-';
        const val = Number(num);
        const sign = val > 0 ? '+' : '';
        return sign + val.toFixed(decimals) + '%';
    },

    formatVolume(vol) {
        if (vol === null || vol === undefined || isNaN(vol)) return '-';
        const v = Number(vol);
        if (v >= 100000000) return (v / 100000000).toFixed(2) + '亿';
        if (v >= 10000) return (v / 10000).toFixed(2) + '万';
        return v.toString();
    },

    // ========== Rise / Fall Class ==========
    getRiseFallClass(val) {
        if (val === null || val === undefined || val === 0) return '';
        return val > 0 ? 'rise' : 'fall';
    },

    getRiseFallIcon(val) {
        if (val === null || val === undefined || val === 0) return 'bi-dash';
        return val > 0 ? 'bi-triangle-fill' : 'bi-triangle-fill';
    },

    // ========== Confirm Dialog ==========
    confirm({
        title = '确认',
        message = '',
        confirmText = '确认',
        cancelText = '取消',
        confirmClass = 'btn btn-danger',
        icon = 'bi-exclamation-triangle-fill',
        backdrop = true
    } = {}) {
        return new Promise(resolve => {
            let modalEl = document.getElementById('stockAppConfirmModal');
            if (!modalEl) {
                modalEl = document.createElement('div');
                modalEl.id = 'stockAppConfirmModal';
                modalEl.className = 'modal fade';
                modalEl.tabIndex = -1;
                modalEl.setAttribute('aria-hidden', 'true');
                modalEl.innerHTML = `
                    <div class="modal-dialog modal-dialog-centered modal-sm">
                        <div class="modal-content">
                            <div class="modal-header">
                                <h6 class="modal-title">
                                    <i class="bi sa-confirm-icon me-2"></i>
                                    <span class="sa-confirm-title"></span>
                                </h6>
                                <button type="button" class="btn-close"
                                        data-bs-dismiss="modal" aria-label="Close"></button>
                            </div>
                            <div class="modal-body sa-confirm-message"></div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-secondary btn-sm"
                                        data-bs-dismiss="modal" id="saConfirmCancel"></button>
                                <button type="button" class="btn btn-sm" id="saConfirmOk"></button>
                            </div>
                        </div>
                    </div>`;
                document.body.appendChild(modalEl);
            }

            modalEl.querySelector('.sa-confirm-title').textContent = title;
            modalEl.querySelector('.sa-confirm-icon').className = 'bi sa-confirm-icon ' + icon + ' me-2';
            modalEl.querySelector('.sa-confirm-message').textContent = message;
            document.getElementById('saConfirmCancel').textContent = cancelText;

            const okBtn = document.getElementById('saConfirmOk');
            okBtn.textContent = confirmText;
            okBtn.className = 'btn btn-sm ' + confirmClass;

            let resolved = false;
            const bsModal = new bootstrap.Modal(modalEl, {backdrop, keyboard: true});

            const cleanup = () => {
                okBtn.removeEventListener('click', onConfirm);
            };

            const onConfirm = () => {
                resolved = true;
                bsModal.hide();
            };

            okBtn.addEventListener('click', onConfirm);
            modalEl.addEventListener('hidden.bs.modal', () => {
                resolve(resolved);
                cleanup();
            }, {once: true});

            bsModal.show();
        });
    },

    // ========== Alert Dialog ==========
    alert({
        title = '提示',
        message = '',
        type = 'info',
        buttonText = '确定'
    } = {}) {
        return new Promise(resolve => {
            const icons = {
                success: 'bi-check-circle-fill',
                danger: 'bi-x-circle-fill',
                warning: 'bi-exclamation-circle-fill',
                info: 'bi-info-circle-fill'
            };
            const iconColors = {
                success: 'var(--fall-color)',
                danger: 'var(--rise-color)',
                warning: 'var(--accent-yellow)',
                info: 'var(--accent-blue)'
            };

            let modalEl = document.getElementById('stockAppAlertModal');
            if (!modalEl) {
                modalEl = document.createElement('div');
                modalEl.id = 'stockAppAlertModal';
                modalEl.className = 'modal fade';
                modalEl.tabIndex = -1;
                modalEl.setAttribute('aria-hidden', 'true');
                modalEl.innerHTML = `
                    <div class="modal-dialog modal-dialog-centered modal-sm">
                        <div class="modal-content">
                            <div class="modal-header">
                                <h6 class="modal-title">
                                    <i class="bi sa-alert-icon me-2"></i>
                                    <span class="sa-alert-title"></span>
                                </h6>
                                <button type="button" class="btn-close"
                                        data-bs-dismiss="modal" aria-label="Close"></button>
                            </div>
                            <div class="modal-body sa-alert-message"></div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-primary btn-sm" id="saAlertOk"></button>
                            </div>
                        </div>
                    </div>`;
                document.body.appendChild(modalEl);
            }

            modalEl.querySelector('.sa-alert-title').textContent = title;
            const iconEl = modalEl.querySelector('.sa-alert-icon');
            iconEl.className = 'bi sa-alert-icon ' + (icons[type] || icons.info) + ' me-2';
            iconEl.style.color = iconColors[type] || iconColors.info;
            modalEl.querySelector('.sa-alert-message').textContent = message;
            document.getElementById('saAlertOk').textContent = buttonText;

            const bsModal = new bootstrap.Modal(modalEl, {backdrop: true, keyboard: true});
            modalEl.addEventListener('hidden.bs.modal', () => resolve(), {once: true});
            bsModal.show();
        });
    },

    // ========== Prompt Dialog (输入弹窗，Promise<string|null>) ==========
    prompt({
        title = '输入',
        message = '',
        placeholder = '',
        defaultValue = '',
        confirmText = '确定',
        cancelText = '取消',
        confirmClass = 'btn-primary',
        required = false,
        validate = null,
        icon = 'bi-pencil-square',
        backdrop = true
    } = {}) {
        return new Promise(resolve => {
            let modalEl = document.getElementById('stockAppPromptModal');
            if (!modalEl) {
                modalEl = document.createElement('div');
                modalEl.id = 'stockAppPromptModal';
                modalEl.className = 'modal fade';
                modalEl.tabIndex = -1;
                modalEl.setAttribute('aria-hidden', 'true');
                modalEl.innerHTML = `
                    <div class="modal-dialog modal-dialog-centered modal-sm">
                        <div class="modal-content">
                            <div class="modal-header">
                                <h6 class="modal-title">
                                    <i class="bi sa-prompt-icon me-2"></i>
                                    <span class="sa-prompt-title"></span>
                                </h6>
                                <button type="button" class="btn-close"
                                        data-bs-dismiss="modal" aria-label="Close"></button>
                            </div>
                            <div class="modal-body">
                                <div class="sa-prompt-message" style="font-size:13px;color:var(--text-muted);margin-bottom:8px;"></div>
                                <input type="text" class="form-control sa-prompt-input" autocomplete="off">
                                <div class="sa-prompt-error invalid-feedback" style="display:none;font-size:12px;"></div>
                            </div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-secondary btn-sm"
                                        data-bs-dismiss="modal" id="saPromptCancel"></button>
                                <button type="button" class="btn btn-sm" id="saPromptOk"></button>
                            </div>
                        </div>
                    </div>`;
                document.body.appendChild(modalEl);
            }

            const titleEl = modalEl.querySelector('.sa-prompt-title');
            const iconEl = modalEl.querySelector('.sa-prompt-icon');
            const msgEl = modalEl.querySelector('.sa-prompt-message');
            const inputEl = modalEl.querySelector('.sa-prompt-input');
            const errEl = modalEl.querySelector('.sa-prompt-error');
            const cancelBtn = document.getElementById('saPromptCancel');
            const okBtn = document.getElementById('saPromptOk');

            titleEl.textContent = title;
            iconEl.className = 'bi sa-prompt-icon ' + icon + ' me-2';
            msgEl.textContent = message;
            msgEl.style.display = message ? '' : 'none';
            inputEl.placeholder = placeholder;
            inputEl.value = defaultValue;
            cancelBtn.textContent = cancelText;
            okBtn.textContent = confirmText;
            okBtn.className = 'btn btn-sm ' + confirmClass;

            let resolved = false;
            const bsModal = new bootstrap.Modal(modalEl, {backdrop, keyboard: true});

            const showErr = (msg) => {
                if (msg) {
                    errEl.textContent = msg;
                    errEl.style.display = 'block';
                    inputEl.classList.add('is-invalid');
                } else {
                    errEl.style.display = 'none';
                    inputEl.classList.remove('is-invalid');
                }
            };

            const refreshOk = () => {
                const v = inputEl.value;
                let disabled = false;
                if (required && !v.trim()) disabled = true;
                if (!disabled && validate) {
                    const r = validate(v);
                    if (r) disabled = true;
                }
                okBtn.disabled = disabled;
            };

            const onInput = () => {
                if (validate) showErr(validate(inputEl.value));
                else showErr('');
                refreshOk();
            };

            const submit = () => {
                if (okBtn.disabled) return;
                const v = inputEl.value;
                if (validate) {
                    const r = validate(v);
                    if (r) { showErr(r); return; }
                }
                resolved = true;
                bsModal.hide();
            };

            const onKeydown = (e) => {
                if (e.key === 'Enter') { e.preventDefault(); submit(); }
            };

            const cleanup = () => {
                okBtn.removeEventListener('click', submit);
                inputEl.removeEventListener('input', onInput);
                inputEl.removeEventListener('keydown', onKeydown);
                inputEl.classList.remove('is-invalid');
                errEl.style.display = 'none';
            };

            okBtn.addEventListener('click', submit);
            inputEl.addEventListener('input', onInput);
            inputEl.addEventListener('keydown', onKeydown);

            modalEl.addEventListener('hidden.bs.modal', () => {
                resolve(resolved ? inputEl.value : null);
                cleanup();
            }, {once: true});

            // 重置初始态后显示并聚焦
            showErr('');
            refreshOk();
            bsModal.show();
            setTimeout(() => {
                inputEl.focus();
                inputEl.select();
            }, 200);
        });
    }
};

// ========== DOM Ready ==========
document.addEventListener('DOMContentLoaded', function() {
    // Sidebar Toggle
    const toggle = document.getElementById('sidebarToggle');
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebarOverlay');

    if (toggle && sidebar) {
        toggle.addEventListener('click', function() {
            if (window.innerWidth < 992) {
                sidebar.classList.toggle('mobile-open');
                if (overlay) overlay.classList.toggle('active');
            } else {
                sidebar.classList.toggle('collapsed');
                document.body.classList.toggle('sidebar-collapsed');
            }
        });
    }

    // Close sidebar on overlay click (mobile)
    if (overlay && sidebar) {
        overlay.addEventListener('click', function() {
            sidebar.classList.remove('mobile-open');
            overlay.classList.remove('active');
        });
    }

    // ========== Global Search ==========
    const globalSearchInput = document.getElementById('globalSearchInput');
    if (globalSearchInput) {
        globalSearchInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                const keyword = globalSearchInput.value.trim();
                if (keyword) {
                    window.location.href = StockApp.contextPath + '/page/stock-list?keyword=' + encodeURIComponent(keyword);
                }
            }
        });

        // Global search suggest (only if SearchSuggest is loaded)
        if (typeof SearchSuggest !== 'undefined') {
            new SearchSuggest(globalSearchInput, {
                onSelect: function(item) {
                    window.location.href = StockApp.contextPath + '/page/stock-list?keyword=' + encodeURIComponent(item.code);
                }
            });
        }
    }

    // ========== Auto-dismiss alerts ==========
    document.querySelectorAll('.alert[data-auto-dismiss]').forEach(function(el) {
        setTimeout(() => {
            const alert = bootstrap.Alert.getOrCreateInstance(el);
            alert.close();
        }, parseInt(el.dataset.autoDismiss) || 5000);
    });

    // ========== Page Entry Animation ==========
    const animateElements = document.querySelectorAll('.card, .stat-card, .page-header');
    animateElements.forEach((el, index) => {
        el.classList.add('animate-in');
        el.style.animationDelay = (index * 0.05) + 's';
    });

    // ========== Resize handler ==========
    window.addEventListener('resize', function() {
        if (window.innerWidth >= 992) {
            if (sidebar) sidebar.classList.remove('mobile-open');
            if (overlay) overlay.classList.remove('active');
        }
    });
});

window.StockApp = StockApp;
