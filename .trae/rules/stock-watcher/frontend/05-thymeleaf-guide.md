---
alwaysApply: false
description: "当用户涉及 Thymeleaf 模板开发、模板引擎、页面渲染、表达式语法、片段复用、表单处理等场景时触发。适用于编写 Thymeleaf 模板、设计页面布局、使用 Thymeleaf 特性、模板复用与继承等任务。仅适用于 stock-watcher 前端 Thymeleaf 模板项目。关键词：Thymeleaf, 模板, 页面渲染, th:, 模板引擎, 片段, layout, 表达式"
# Thymeleaf 最佳实践

> 适用于 stock-watcher（Thymeleaf）模板开发。

---

## 一、基本用法

### 1.1 命名空间 ✅ MUST

在 `<html>` 标签引入 Thymeleaf 命名空间：

```html
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
```

### 1.2 表达式类型 💡 SHOULD

| 表达式 | 语法 | 用途 |
|-------|------|------|
| 变量表达式 | `${...}` | 获取变量值 |
| 选择表达式 | `*{...}` | 选择对象（配合 th:object） |
| 消息表达式 | `#{...}` | 国际化消息 |
| 链接表达式 | `@{...}` | URL 链接 |
| 片段表达式 | `~{...}` | 引入片段 |

---

## 二、文本输出

### 2.1 文本输出 ✅ MUST

- 使用 `th:text` 输出文本（默认 HTML 转义，防 XSS）
- **不要**使用 `th:utext` 除非内容可信且需要 HTML

```html
<!-- 安全：HTML 转义 -->
<p th:text="${stock.name}">股票名称</p>
<span th:text="${price}">10.00</span>

<!-- 危险 ❌：不转义，慎用 -->
<p th:utext="${content}"></p>
```

### 2.2 字符串拼接 💡 SHOULD

```html
<!-- 方式 1：|...| 字面量替换（推荐） -->
<span th:text="|股票代码：${code}|">股票代码：000001</span>

<!-- 方式 2：+ 拼接 -->
<span th:text="'股票代码：' + ${code}">股票代码：000001</span>
```

### 2.3 默认值 💡 SHOULD

```html
<!-- 变量为 null 时显示默认值 -->
<span th:text="${name} ?: '未知'">--</span>
```

---

## 三、条件判断

### 3.1 if / unless ✅ MUST

```html
<!-- th:if 条件成立时显示 -->
<div th:if="${stock != null}">
    股票存在
</div>

<!-- th:unless 条件不成立时显示 -->
<div th:unless="${stock != null}">
    股票不存在
</div>
```

### 3.2 switch 💡 SHOULD

```html
<div th:switch="${period}">
    <p th:case="'daily'">日K</p>
    <p th:case="'weekly'">周K</p>
    <p th:case="'monthly'">月K</p>
    <p th:case="*">未知周期</p>
</div>
```

---

## 四、循环遍历

### 4.1 each 循环 ✅ MUST

```html
<tr th:each="item : ${list}">
    <td th:text="${item.name}">名称</td>
    <td th:text="${item.price}">价格</td>
</tr>
```

### 4.2 状态变量 💡 SHOULD

```html
<tr th:each="item, iterStat : ${list}">
    <td th:text="${iterStat.count}">序号</td>
    <td th:text="${item.name}">名称</td>
    <td th:class="${iterStat.odd} ? 'table-active'">奇偶行</td>
</tr>
```

状态变量属性：
- `index` - 索引（从 0 开始）
- `count` - 计数（从 1 开始）
- `size` - 总数
- `current` - 当前元素
- `even` / `odd` - 是否偶数/奇数
- `first` / `last` - 是否第一/最后一个

---

## 五、属性设置

### 5.1 通用属性 ✅ MUST

```html
<!-- 标准属性 -->
<a th:href="@{/stock/{code}(code=${code})}" th:text="${name}">链接</a>
<img th:src="@{/images/logo.png}" alt="logo">
<input th:value="${keyword}" type="text">
```

### 5.2 class 绑定 💡 SHOULD

```html
<!-- 单个条件类 -->
<span th:class="${change >= 0} ? 'text-danger' : 'text-success'">涨跌幅</span>

<!-- 追加类 -->
<button th:classappend="${active} ? 'active'" class="btn">按钮</button>
```

### 5.3 style 绑定 📌 MAY

```html
<div th:style="'color: ' + ${color}">内容</div>
```

---

## 六、URL 链接

### 6.1 链接表达式 ✅ MUST

```html
<!-- 绝对路径 -->
<a th:href="@{/stock-list}">股票列表</a>

<!-- 带参数 -->
<a th:href="@{/kline(code=${code}, period='daily')}">K线</a>
<!-- 生成: /kline?code=000001&period=daily -->

<!-- RESTful 风格 -->
<a th:href="@{/kline/{code}(code=${code})}">K线</a>
<!-- 生成: /kline/000001 -->
```

### 6.2 上下文路径 💡 SHOULD

使用 `@{...}` 会自动加上 context path，不要手动拼接。

```html
<!-- 好的 -->
<a th:href="@{/stock-list}">股票列表</a>

<!-- 不好的 ❌ -->
<a th:href="${#httpServletRequest.getContextPath() + '/stock-list'}">股票列表</a>
```

---

## 七、表单处理

### 7.1 表单绑定 💡 SHOULD

```html
<form th:action="@{/login}" th:object="${loginForm}" method="post">
    <div class="mb-3">
        <label class="form-label">用户名</label>
        <input type="text" class="form-control" th:field="*{username}">
    </div>
    <div class="mb-3">
        <label class="form-label">密码</label>
        <input type="password" class="form-control" th:field="*{password}">
    </div>
    <button type="submit" class="btn btn-primary">登录</button>
</form>
```

### 7.2 错误显示 💡 SHOULD

```html
<div th:if="${#fields.hasErrors('username')}" class="text-danger">
    <p th:errors="*{username}"></p>
</div>
```

---

## 八、片段复用

### 8.1 定义片段 ✅ MUST

在 `fragments/common.html` 中定义：

```html
<!-- 定义片段 -->
<header th:fragment="navbar">
    <!-- 导航栏内容 -->
</header>
```

### 8.2 引入片段 ✅ MUST

```html
<!-- 引入片段（替换整个标签） -->
<header th:replace="~{fragments/common :: navbar}"></header>

<!-- 引入片段（保留外层标签，插入内容） -->
<header th:insert="~{fragments/common :: navbar}"></header>
```

### 8.3 参数化片段 💡 SHOULD

```html
<!-- 定义带参数的片段 -->
<head th:fragment="head(pageTitle)">
    <meta charset="UTF-8">
    <title th:text="${pageTitle}">标题</title>
    <link rel="stylesheet" th:href="@{/css/bootstrap.min.css}">
</head>

<!-- 使用 -->
<head th:replace="~{fragments/common :: head(${pageTitle})}">
</head>
```

---

## 九、工具对象

### 9.1 常用工具对象 💡 SHOULD

| 对象 | 用途 | 示例 |
|-----|------|------|
| `#strings` | 字符串工具 | `${#strings.toUpperCase(name)}` |
| `#numbers` | 数字工具 | `${#numbers.formatDecimal(price, 1, 2)}` |
| `#dates` | 日期工具 | `${#dates.format(date, 'yyyy-MM-dd')}` |
| `#lists` | 列表工具 | `${#lists.size(list)}` |
| `#objects` | 对象工具 | `${#objects.nullSafe(obj, 'default')}` |

### 9.2 数字格式化 ✅ MUST

```html
<!-- 保留 2 位小数 -->
<span th:text="${#numbers.formatDecimal(price, 1, 2)}">10.00</span>

<!-- 百分比 -->
<span th:text="${#numbers.formatPercent(rate, 1, 2)} + '%'">1.23%</span>
```

### 9.3 涨跌幅颜色示例 ✅ MUST

```html
<span class="badge"
      th:classappend="${changePercent >= 0} ? 'bg-danger' : 'bg-success'"
      th:text="${changePercent >= 0 ? '+' : ''} + ${#numbers.formatDecimal(changePercent, 1, 2)} + '%'">
    +0.37%
</span>
```

---

## 十、布局模板

### 10.1 标准页面结构 💡 SHOULD

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/common :: head(${pageTitle})}">
</head>
<body>
<div class="d-flex">
    <!-- 侧边栏 -->
    <nav th:replace="~{fragments/common :: sidebar}"></nav>

    <!-- 主内容 -->
    <div class="main-wrapper">
        <header th:replace="~{fragments/common :: navbar}"></header>

        <main class="content-area">
            <!-- 页面内容 -->
        </main>
    </div>
</div>

<!-- 公共 JS -->
<script th:src="@{/js/bootstrap.bundle.min.js}"></script>
<script th:src="@{/js/common.js}"></script>

<!-- 页面 JS -->
<th:block layout:fragment="scripts">
</th:block>

<!-- Toast 容器 -->
<div th:replace="~{fragments/common :: toastContainer}"></div>
</body>
</html>
```

---

## 十一、性能与最佳实践

### 11.1 缓存 💡 SHOULD

- 生产环境开启 Thymeleaf 缓存（默认开启）
- 开发环境关闭缓存：`spring.thymeleaf.cache=false`

### 11.2 避免复杂逻辑 ✅ MUST

- 模板中只做展示逻辑
- 复杂业务逻辑放在 Java 代码中
- 不在模板中写复杂计算

### 11.3 安全 ✅ MUST

- `th:text` 默认转义，防止 XSS
- 不用 `th:utext` 输出用户输入的内容
- 表单使用 CSRF（Thymeleaf 默认支持）

### 11.4 代码组织 💡 SHOULD

- 公共片段统一放在 `fragments/` 目录
- 页面模板放在 `pages/` 目录
- 错误页面放在 `error/` 目录
- 按功能模块组织子目录

---

## 十二、项目常用片段

`fragments/common.html` 中已定义的片段：

| 片段名 | 用途 |
|-------|------|
| `head(pageTitle)` | 页面头部，含标题、CSS |
| `sidebar` | 侧边导航栏 |
| `navbar` | 顶部导航栏 |
| `toastContainer` | Toast 提示容器 |
| `footer` | 页脚 |
