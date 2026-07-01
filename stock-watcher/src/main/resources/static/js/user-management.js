let currentPage = 1;

function loadUsers(page) {
    currentPage = page;
    const keyword = document.getElementById('searchKeyword').value;
    StockApp.get('/api/users', {keyword, page, size: 10}, function (resp) {
        if (resp.code !== 200) {
            StockApp.toast(resp.message || '加载失败', 'danger');
            return;
        }
        const result = resp.data;
        renderTable(result.records);
        renderPagination(result);
        document.getElementById('pageInfo').textContent = `共 ${result.total} 条`;
    });
}

function renderTable(list) {
    const tbody = document.getElementById('userListBody');
    if (!list || !list.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="text-center text-muted py-4">无数据</td></tr>';
        return;
    }
    tbody.innerHTML = list.map(u => `
        <tr>
            <td>${u.id}</td>
            <td class="fw-medium">${u.username}</td>
            <td>${u.email || '-'}</td>
            <td>${u.phone || '-'}</td>
            <td><span class="badge ${u.role === 'ADMIN' ? 'bg-primary' : 'bg-secondary'}">${StockApp.getEnumLabel('Role', u.role)}</span></td>
            <td>${u.totpSecret ? '<span class="text-success"><i class="bi bi-shield-check"></i> 已绑定</span>' : '<span class="text-muted">未绑定</span>'}</td>
            <td>${u.enabled ? '<span class="badge bg-success">启用</span>' : '<span class="badge bg-danger">禁用</span>'}</td>
            <td>${u.createdAt || '-'}</td>
            <td class="text-center">
                <button class="btn btn-sm btn-outline-warning me-1" onclick="resetTotp(${u.id}, '${u.username}')" title="重置2FA">
                    <i class="bi bi-shield-lock"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger" onclick="confirmDeleteUser(${u.id}, '${u.username}')" title="删除">
                    <i class="bi bi-trash"></i>
                </button>
            </td>
        </tr>
    `).join('');
}

function renderPagination(result) {
    const ul = document.getElementById('pagination');
    const totalPages = result.pages || Math.ceil(result.total / result.size);
    if (totalPages <= 1) {
        ul.innerHTML = '';
        return;
    }

    let html = '';
    html += `<li class="page-item ${result.current <= 1 ? 'disabled' : ''}">
                <a class="page-link" href="#" onclick="loadUsers(${(result.current || currentPage) - 1})">上一页</a>
             </li>`;
    for (let i = 1; i <= totalPages; i++) {
        html += `<li class="page-item ${i === (result.current || currentPage) ? 'active' : ''}">
                    <a class="page-link" href="#" onclick="loadUsers(${i})">${i}</a>
                 </li>`;
    }
    html += `<li class="page-item ${(result.current || currentPage) >= totalPages ? 'disabled' : ''}">
                <a class="page-link" href="#" onclick="loadUsers(${(result.current || currentPage) + 1})">下一页</a>
             </li>`;
    ul.innerHTML = html;
}

function resetSearch() {
    document.getElementById('searchKeyword').value = '';
    loadUsers(1);
}

// ========== Create User ==========

function openCreateModal() {
    document.getElementById('createUserForm').reset();
    bootstrap.Modal.getOrCreateInstance(document.getElementById('createUserModal')).show();
}

function submitCreateUser() {
    const username = document.getElementById('createUsername').value.trim();
    const password = document.getElementById('createPassword').value;
    const email = document.getElementById('createEmail').value.trim();
    const phone = document.getElementById('createPhone').value.trim();
    const role = document.getElementById('createRole').value;

    if (!username || !password) {
        StockApp.toast('用户名和密码不能为空', 'warning');
        return;
    }

    StockApp.post('/api/users', {username, password, email, phone, role}, function (resp) {
        if (resp.code !== 200) {
            StockApp.toast(resp.message || '创建失败', 'danger');
            return;
        }

        bootstrap.Modal.getInstance(document.getElementById('createUserModal')).hide();

        const data = resp.data;
        showTotpSetup(data.otpAuthUrl, data.secret);

        StockApp.toast('用户创建成功，请绑定 2FA', 'success');
        loadUsers(currentPage);
    });
}

// ========== TOTP Setup ==========

function showTotpSetup(otpAuthUrl, secret) {
    const qrDiv = document.getElementById('totpQrcode');
    qrDiv.innerHTML = '';
    document.getElementById('totpSecretDisplay').textContent = secret;

    new QRCode(qrDiv, {
        text: otpAuthUrl,
        width: 200,
        height: 200,
        correctLevel: QRCode.CorrectLevel.M
    });

    bootstrap.Modal.getOrCreateInstance(document.getElementById('totpSetupModal')).show();
}

// ========== Reset TOTP ==========

async function resetTotp(id, username) {
    if (!await StockApp.confirm({
        title: '重置两步验证',
        message: `确定要重置用户 "${username}" 的两步验证吗？重置后需要重新绑定。`,
        confirmText: '重置',
        confirmClass: 'btn-warning',
        icon: 'bi-shield-lock'
    })) return;

    StockApp.post('/api/users/' + id + '/reset-totp', null, function (resp) {
        if (resp.code !== 200) {
            StockApp.toast(resp.message || '重置失败', 'danger');
            return;
        }

        const data = resp.data;
        showTotpSetup(data.otpAuthUrl, data.secret);
        StockApp.toast('2FA 已重置，请重新绑定', 'success');
        loadUsers(currentPage);
    });
}

// ========== Delete User ==========

async function confirmDeleteUser(id, username) {
    if (!await StockApp.confirm({
        title: '删除用户',
        message: `确定要删除用户 "${username}" 吗？此操作不可恢复。`,
        confirmText: '删除',
        confirmClass: 'btn-danger',
        icon: 'bi-trash'
    })) return;

    StockApp.delete('/api/users/' + id, function (resp) {
        if (resp.code !== 200) {
            StockApp.toast(resp.message || '删除失败', 'danger');
            return;
        }
        StockApp.toast('用户已删除', 'success');
        loadUsers(currentPage);
    });
}

// ========== Init ==========
loadUsers(1);
