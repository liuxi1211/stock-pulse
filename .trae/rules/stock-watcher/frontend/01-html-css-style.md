---
alwaysApply: false
description: "当用户涉及 HTML/CSS 编写、页面布局、Bootstrap 5 使用、CSS 样式规范、BEM 命名、HTML 语义化等场景时触发。适用于编写前端页面、调整样式布局、使用 Bootstrap 组件、遵循 HTML/CSS 编码规范等任务。仅适用于 stock-watcher 前端 Thymeleaf 模板项目。关键词：HTML, CSS, Bootstrap, 页面布局, 样式, 前端, 语义化, BEM"
# HTML/CSS 代码风格规范

> 适用于 stock-watcher（Thymeleaf + Bootstrap 5）前端开发。
> 基于项目现有代码风格总结，与当前代码保持一致。

---

## 一、HTML 规范

### 1.1 文档结构 ✅ MUST

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>页面标题</title>
</head>
<body>
    <!-- 页面内容 -->
</body>
</html>
```

- 声明 `<!DOCTYPE html>`
- `lang="zh-CN"` 中文页面
- 引入 Thymeleaf 命名空间
- 设置 UTF-8 编码
- 设置 viewport 适配移动端

### 1.2 语义化标签 💡 SHOULD

优先使用语义化标签：

| 标签 | 用途 |
|-----|------|
| `<header>` | 页面头部 |
| `<nav>` | 导航 |
| `<main>` | 主要内容 |
| `<section>` | 区块 |
| `<article>` | 文章/独立内容 |
| `<aside>` | 侧边栏 |
| `<footer>` | 页脚 |

```html
<!-- 好的 -->
<header class="navbar">...</header>
<main class="content-area">...</main>
<nav id="sidebar">...</nav>

<!-- 不推荐 -->
<div class="header">...</div>
<div class="main">...</div>
```

### 1.3 标签闭合 ✅ MUST

- 自闭合标签正确使用：`<br>`、`<hr>`、`<img>`、`<input>`、`<meta>`
- HTML5 中自闭合标签可不加 `/`，但保持一致

### 1.4 属性顺序 💡 SHOULD

建议按以下顺序排列属性，便于阅读：

1. `id`
2. `class`
3. `name`
4. `data-*`
5. `src` / `href` / `action`
6. `title` / `alt`
7. `aria-*` / `role`
8. 事件（`onclick` 等）
9. Thymeleaf 属性（`th:*`）

```html
<input
    id="searchInput"
    class="form-control form-control-sm"
    type="text"
    data-bs-toggle="tooltip"
    placeholder="输入股票代码"
    th:value="${keyword}"
    onclick="handleSearch()"
>
```

### 1.5 注释 💡 SHOULD

- 复杂结构加注释说明
- 模块之间用分隔注释
- 不写无意义的注释

```html
<!-- ============ 行情图表区域 ============ -->
<div class="card">
    ...
</div>
```

---

## 二、CSS 规范

### 2.1 命名规范：BEM 💡 SHOULD

使用 BEM（Block Element Modifier）命名思想，简化版：

```css
/* Block: 组件 */
.index-card { ... }

/* Element: 组成部分 */
.index-card__title { ... }
.index-card__value { ... }

/* Modifier: 状态/变体 */
.index-card--up { ... }
.index-card--down { ... }
```

但项目使用 Bootstrap 为主，自定义样式尽量少，优先使用 Bootstrap 工具类。

### 2.2 选择器优先级 ✅ MUST

- 尽量使用 class 选择器，少用 id 选择器
- 避免过深的嵌套（不超过 3 层）
- 避免使用 `!important`（特殊情况除外）

```css
/* 好的 */
.stock-item { ... }
.stock-item .name { ... }

/* 不好的 ❌ */
#stockList .table tbody tr td.stock-name span { ... }
```

### 2.3 属性顺序 💡 SHOULD

按以下顺序书写 CSS 属性：

1. 定位：`position`、`top`、`left`、`z-index`
2. 盒模型：`display`、`width`、`height`、`margin`、`padding`、`border`
3. 背景：`background`
4. 文本：`font`、`color`、`text-align`、`line-height`
5. 其他：`transition`、`animation`、`transform`

```css
.index-card {
    position: relative;
    display: flex;
    width: 100%;
    padding: 1rem;
    border-radius: 8px;
    background: #fff;
    font-size: 14px;
    color: #333;
    transition: all 0.3s;
}
```

### 2.4 颜色与数值 💡 SHOULD

- 颜色使用十六进制：`#fff`、`#f5f5f5`
- 颜色有语义：涨用红色，跌用绿色（A股习惯）
- 尽量使用 Bootstrap 提供的颜色变量

---

## 三、Bootstrap 5 使用规范

### 3.1 优先使用 Bootstrap 类 ✅ MUST

- 优先使用 Bootstrap 提供的工具类，少写自定义 CSS
- 布局使用 Grid 系统（container / row / col）
- 间距使用 spacing 工具类（m-* / p-* / gap-*）
- 文本使用 text-* 工具类

```html
<!-- 好的：使用 Bootstrap 工具类 -->
<div class="row g-3 mb-4">
    <div class="col-lg-8">
        <div class="card">
            <div class="card-header d-flex justify-content-between align-items-center">
                <h6 class="mb-0">标题</h6>
                <span class="badge bg-primary">标签</span>
            </div>
            <div class="card-body p-0">
                内容
            </div>
        </div>
    </div>
</div>
```

### 3.2 栅格系统 💡 SHOULD

- 响应式断点：`sm` / `md` / `lg` / `xl` / `xxl`
- 优先使用 `col-lg-*` 适配桌面端
- 移动端单列，桌面端多列

```html
<div class="row g-3">
    <div class="col-12 col-lg-8">主内容</div>
    <div class="col-12 col-lg-4">侧边栏</div>
</div>
```

### 3.3 组件使用 💡 SHOULD

常用组件：
- **Card**：卡片容器
- **Table**：表格（`.table-hover`、`.table-sm`）
- **Form**：表单（`.form-control`、`.form-select`）
- **Button**：按钮（`btn btn-primary`、`btn-sm`）
- **Modal**：弹窗
- **Badge**：徽标
- **Breadcrumb**：面包屑
- **Pagination**：分页

### 3.4 Flex 布局 💡 SHOULD

优先使用 Bootstrap 的 flex 工具类：

```html
<div class="d-flex justify-content-between align-items-center">
    <span>左侧内容</span>
    <button class="btn btn-primary">按钮</button>
</div>
```

---

## 四、图标规范

### 4.1 图标库 ✅ MUST

- 使用 Bootstrap Icons
- class 格式：`bi bi-图标名`

```html
<i class="bi bi-search me-1"></i>
<i class="bi bi-star-fill text-warning me-1"></i>
<i class="bi bi-candlestick me-1"></i>
```

### 4.2 使用原则 💡 SHOULD

- 按钮/链接前加图标增强识别度
- 图标与文字之间加间距（`me-1` / `ms-1`）
- 颜色通过 `text-*` 类控制

---

## 五、表格规范

### 5.1 表格样式 ✅ MUST

```html
<div class="table-responsive">
    <table class="table table-hover table-sm mb-0 align-middle">
        <thead class="table-light">
            <tr>
                <th>名称</th>
                <th class="text-end">最新价</th>
                <th class="text-end">涨跌幅</th>
                <th class="text-center">操作</th>
            </tr>
        </thead>
        <tbody>
            <!-- 数据行 -->
        </tbody>
    </table>
</div>
```

- 外层加 `.table-responsive` 响应式
- 使用 `.table-hover` 悬停效果
- 使用 `.table-sm` 紧凑表格
- 数字列右对齐（`.text-end`）
- 操作列居中（`.text-center`）

---

## 六、表单规范

### 6.1 表单控件 ✅ MUST

```html
<div class="mb-3">
    <label for="username" class="form-label">用户名</label>
    <input type="text" class="form-control" id="username" required>
</div>

<div class="mb-3">
    <label class="form-label">选择周期</label>
    <select class="form-select">
        <option value="daily">日K</option>
        <option value="weekly">周K</option>
        <option value="monthly">月K</option>
    </select>
</div>

<button type="submit" class="btn btn-primary">提交</button>
```

### 6.2 表单布局 💡 SHOULD

- 标签在上，输入框在下
- 表单项之间加 `.mb-3` 间距
- 按钮左对齐或右对齐，保持一致

---

## 七、项目特定规范

### 7.1 涨跌幅颜色 ✅ MUST

A股习惯：**涨红跌绿**

- 上涨/正数：红色（`text-danger` / `bg-danger`）
- 下跌/负数：绿色（`text-success` / `bg-success`）

```html
<span class="text-danger" th:if="${change >= 0}">+1.23%</span>
<span class="text-success" th:if="${change < 0}">-0.45%</span>
```

### 7.2 页面布局结构 💡 SHOULD

标准页面结构：

```html
<body>
<div class="d-flex">
    <!-- 侧边栏 -->
    <nav th:replace="~{fragments/common :: sidebar}"></nav>

    <!-- 主内容区 -->
    <div class="main-wrapper">
        <!-- 顶部导航 -->
        <header th:replace="~{fragments/common :: navbar}"></header>

        <!-- 内容区域 -->
        <main class="content-area">
            <!-- 页面内容 -->
        </main>
    </div>
</div>
</body>
```

### 7.3 公共片段 ✅ MUST

- 公共部分抽取到 `fragments/common.html`
- 使用 `th:replace` 引入
- 公共片段：head、sidebar、navbar、footer、toastContainer
