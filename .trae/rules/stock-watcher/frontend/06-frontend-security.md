---
alwaysApply: false
description: "当用户涉及前端安全、XSS 防护、CSRF 防护、内容安全策略 CSP、敏感数据处理、前端认证、安全存储等前端安全场景时触发。适用于编写安全的前端代码、防护 XSS/CSRF 攻击、保护用户数据、检查前端安全风险等任务。仅适用于 stock-watcher 前端项目。关键词：前端安全, XSS, CSRF, CSP, 安全, 注入防护, 敏感数据, 前端认证"
# 前端安全规范

> 适用于 stock-watcher 前端安全开发。

---

## 一、XSS 防护

### 1.1 Thymeleaf 默认转义 ✅ MUST

- 使用 `th:text` 输出文本（默认 HTML 转义）
- **不使用** `th:utext` 除非内容可信且确实需要 HTML
- 用户输入的内容绝不使用 `th:utext`

```html
<!-- 安全：默认转义 -->
<p th:text="${userInput}"></p>

<!-- 危险 ❌：不转义 -->
<p th:utext="${userInput}"></p>
```

### 1.2 JavaScript DOM 操作 ✅ MUST

- 插入文本使用 `textContent`，不用 `innerHTML`
- 使用 `innerHTML` 时内容必须转义
- 使用 `StockApp.escapeHtml()` 转义用户输入

```javascript
// 安全：textContent
el.textContent = userInput;

// 安全：转义后用 innerHTML
el.innerHTML = StockApp.escapeHtml(userInput);

// 危险 ❌：直接插入用户输入
el.innerHTML = userInput;
```

### 1.3 StockApp.escapeHtml ✅ MUST

项目已提供 HTML 转义方法，必须使用：

```javascript
const safeHtml = StockApp.escapeHtml(userInput);
```

转义规则：
- `&` → `&amp;`
- `<` → `&lt;`
- `>` → `&gt;`
- `"` → `&quot;`
- `'` → `&#39;`

### 1.4 URL 安全 💡 SHOULD

- 不使用 `javascript:` 伪协议
- 链接 `href` 校验协议（白名单：http/https）
- 不要把用户输入直接拼接到 URL 中

```javascript
// 危险 ❌
<a href="javascript:alert(1)">

// 危险 ❌
window.location.href = userInput;
```

### 1.5 eval 与 Function ❌ 禁止

- 禁止使用 `eval()`
- 禁止使用 `new Function()`
- 禁止使用 `setTimeout(string)` / `setInterval(string)`

```javascript
// 危险 ❌
eval(userInput);
setTimeout(userInput, 1000);
```

---

## 二、CSRF 防护

### 2.1 表单 CSRF Token 💡 SHOULD

- 使用 Thymeleaf `th:action` 自动添加 CSRF Token
- 表单 POST 请求必须带 CSRF Token
- Spring Security 配置 CSRF 防护

```html
<form th:action="@{/login}" method="post">
    <!-- Thymeleaf 自动添加 CSRF hidden 字段 -->
    <input type="text" name="username">
    <button type="submit">提交</button>
</form>
```

### 2.2 AJAX 请求 💡 SHOULD

- AJAX POST 请求在 header 中携带 CSRF Token
- 从 meta 标签或 cookie 中获取 Token

```javascript
// 从 meta 标签获取 CSRF Token
const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

// 请求时带上
fetch(url, {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        [csrfHeader]: csrfToken
    },
    body: JSON.stringify(data)
});
```

### 2.3 安全的 HTTP 方法 💡 SHOULD

- GET 请求不做状态变更操作
- 写操作使用 POST / PUT / DELETE
- 关键操作（删除、修改）需二次确认

---

## 三、敏感信息处理

### 3.1 密码安全 ✅ MUST

- 密码输入框使用 `type="password"`
- 不在日志中输出密码
- 不在 localStorage / sessionStorage 中存储密码
- 密码不自动填充（敏感操作）

```html
<input type="password" name="password" class="form-control">
```

### 3.2 Token 安全 ✅ MUST

- 不把 Tushare Token 等敏感信息暴露到前端
- 不在前端存储后端 API 的 token（如有）
- Session 走 Cookie，设置 HttpOnly

### 3.3 日志安全 💡 SHOULD

- console.log 不输出敏感信息
- 生产环境移除调试日志
- 错误提示不暴露系统内部细节

```javascript
// 不好的 ❌
console.log('用户密码:', password);

// 好的
console.log('登录请求已发送');
```

### 3.4 错误信息 💡 SHOULD

- 前端错误提示对用户友好
- 不暴露堆栈、路径、数据库结构等内部信息
- 详细错误信息记录在后端日志

---

## 四、内容安全策略（CSP）📌 MAY

### 4.1 CSP 作用

- 限制资源加载来源
- 禁止内联脚本和 eval
- 防止 XSS 和数据注入

### 4.2 建议配置

```http
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data:;
  font-src 'self' data:;
  connect-src 'self';
```

---

## 五、存储安全

### 5.1 localStorage / sessionStorage 💡 SHOULD

- 不存储敏感信息（密码、token 等）
- 存储的数据做校验，防止篡改
- 注意 XSS 可以窃取 localStorage 数据

```javascript
// 不要这样做 ❌
localStorage.setItem('password', password);
localStorage.setItem('token', token);
```

### 5.2 Cookie 安全 💡 SHOULD

- 敏感 Cookie 设置 `HttpOnly`（后端设置）
- 设置 `Secure`（HTTPS 环境）
- 设置 `SameSite` 防 CSRF
- 设置合理的过期时间

---

## 六、第三方依赖安全

### 6.1 依赖来源 ✅ MUST

- 使用官方版本的第三方库
- 从可信来源下载（官网、npm）
- 不使用不明来源的脚本

### 6.2 依赖版本 💡 SHOULD

- 关注第三方库的安全漏洞
- 及时升级有安全问题的版本
- Bootstrap、ECharts 等定期检查更新

---

## 七、点击劫持防护

### 7.1 X-Frame-Options 💡 SHOULD

- 后端设置 `X-Frame-Options: DENY` 或 `SAMEORIGIN`
- 防止页面被嵌入 iframe 进行点击劫持

---

## 八、安全检查清单

开发完成后，对照以下清单检查：

### 8.1 XSS
- [ ] 所有用户输入输出都做了转义
- [ ] 不使用 th:utext 输出用户输入
- [ ] 不使用 innerHTML 插入用户内容（或已转义）
- [ ] 不使用 eval / new Function
- [ ] URL 参数不直接插入到 DOM 中

### 8.2 CSRF
- [ ] POST 表单有 CSRF Token
- [ ] AJAX POST 请求带 CSRF Token
- [ ] 关键操作有二次确认

### 8.3 敏感信息
- [ ] 密码输入框是 password 类型
- [ ] 不在前端存储密码和 token
- [ ] 日志中不输出敏感信息
- [ ] 错误提示不暴露内部细节

### 8.4 其他
- [ ] 没有硬编码的敏感信息
- [ ] 第三方依赖来自可信来源
- [ ] Cookie 设置了安全属性
