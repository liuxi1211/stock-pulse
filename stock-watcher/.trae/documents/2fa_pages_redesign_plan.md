# 2FA 页面改造计划

## 目标
根据新的设计系统，改造 `login-2fa.html` 和 `setup-totp.html` 两个页面，使其风格与 `login.html` 保持一致。

## 设计参考
- 参考页面：`login.html`（深色科技风 + 玻璃拟态）
- 主题变量：`theme.css` 中的 CSS 变量系统

## 改造内容

### 通用改造（两个页面都需要）

1. **页面结构调整**
   - 添加 `data-theme="dark"` 属性到 `<html>` 标签
   - 添加背景光晕层（`.login-bg`）
   - 添加主题切换按钮（右上角）
   - 使用居中卡片布局（类似 login.html 的右侧卡片结构）

2. **样式引入**
   - 引入 Google Fonts（Noto Sans SC + Space Grotesk）
   - 引入 `theme.css`
   - 引入 `components.css`
   - 引入 `theme.js`

3. **视觉风格**
   - 玻璃拟态卡片效果（`backdrop-filter: blur` + 半透明背景）
   - 使用 CSS 变量（`--bg-card`、`--border-color`、`--text-primary` 等）
   - 渐变图标背景（`--gradient-primary`）
   - 阴影效果（`--shadow-xl`）

4. **动画效果**
   - 添加入场动画 `scaleIn`（从下方淡入 + 缩放）

5. **功能保留**
   - 保留所有 Thymeleaf 模板变量（`${error}`、`${message}` 等）
   - 保留所有表单提交逻辑和 action URL
   - 保留 QR 码生成功能（setup-totp.html）

---

### 页面 1：login-2fa.html 改造

**原有功能保留：**
- 两步验证表单（POST 到 `/login/verify-totp`）
- 6 位验证码输入框
- error 消息显示
- 返回登录链接

**新增/改造：**
- 背景光晕效果
- 玻璃拟态卡片
- 主题切换按钮
- 入场动画
- 图标（盾牌锁图标，渐变背景）
- 标题和副标题样式
- 输入框样式优化（使用 input-group + 图标）
- 底部版权信息

---

### 页面 2：setup-totp.html 改造

**原有功能保留：**
- QR 码展示
- 手动密钥显示
- 6 位验证码输入框
- 确认绑定表单（POST 到 `/login/setup-totp`）
- error 消息显示
- QRCode.js 库引入
- Thymeleaf 内联脚本（`${otpAuthUrl}`、`${secret}`）

**新增/改造：**
- 背景光晕效果
- 玻璃拟态卡片
- 主题切换按钮
- 入场动画
- 图标（盾牌对勾图标，渐变背景）
- 标题和副标题样式
- QR 码区域样式美化
- 步骤说明样式优化
- 输入框样式优化
- 底部版权信息

---

## 文件清单

| 文件 | 操作 |
|------|------|
| `templates/pages/login-2fa.html` | 完整重写 |
| `templates/pages/setup-totp.html` | 完整重写 |

## 注意事项
1. 所有 Thymeleaf 表达式必须保留完整
2. 表单 name 属性和 action URL 必须与原页面一致
3. QR 码容器 ID `qrcode` 必须保留
4. 输入框的 `autofocus` 属性保留
5. 响应式布局适配移动端
