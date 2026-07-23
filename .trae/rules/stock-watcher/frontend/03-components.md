---
alwaysApply: false
description: "StockPulse 组件库手册——所有公共组件的使用说明。写 UI、选组件时查本文档。包含：按钮、卡片、统计条、表格、表单、徽章、Tabs、进度条、空状态、Toast、Tooltip、工具类等。"
---

# 组件库手册

> **面向 AI & 开发者**：所有全局组件的使用手册。写 UI 前先翻本文档，**优先用已有组件，不要在页面 CSS 重复造轮子**。

> 源码：[components.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/components.css)

---

## 一、按钮系统（最常用）

### 1.1 选型原则

| 场景 | 选哪个 |
|---|---|
| 主操作（提交 / 确认 / 运行 / 保存） | `.btn-primary` |
| 新增 / 确认 / 完成 | `.btn-success` |
| 删除 / 高危操作 | `.btn-danger` |
| 取消 / 关闭 / 返回 | `.btn-secondary` |
| 表格内操作 / 查看详情 | `.btn-outline-secondary` |
| 工具栏 / 最次级操作 | `.btn-ghost` |
| 行内链接式操作 | `.btn-link` |
| 强决策主操作（运行回测 / 执行选股） | `.seal-btn` |

> 一个页面最多 1 个主按钮（`.btn-primary`），表格内操作统一用描边次按钮（`.btn-outline-secondary`）。

### 1.2 三种尺寸

| 尺寸 | Class | 高度 | 用途 |
|---|---|---|---|
| 小号 | `.btn-sm` | 30px | 表格内 / 紧凑区域 |
| 默认 | — | 36px | 大多数场景 |
| 大号 | `.btn-lg` | 44px | 页头主操作 / 表单底部 |

### 1.3 完整按钮列表

| 类型 | Class | 视觉 | 示例 |
|---|---|---|---|
| **主按钮** | `.btn-primary` | 品牌渐变 + 柔光发光 | `<button class="btn btn-primary">运行回测</button>` |
| **成功按钮** | `.btn-success` | 绿色渐变 + 发光 | `<button class="btn btn-success">确认新增</button>` |
| **危险按钮** | `.btn-danger` | 红色渐变 + 发光 | `<button class="btn btn-danger btn-sm">删除</button>` |
| **次按钮** | `.btn-secondary` | 半透明玻璃态 + 细边框 | `<button class="btn btn-secondary">取消</button>` |
| **描边次按钮** | `.btn-outline-secondary` | 透明底 + 灰边框 | `<button class="btn btn-outline-secondary btn-sm">编辑</button>` |
| **幽灵按钮** | `.btn-ghost` | 完全透明 + 无边框 | `<button class="btn btn-ghost btn-sm">更多</button>` |
| **链接按钮** | `.btn-link` | 下划线文字 | `<button class="btn btn-link">查看详情</button>` |
| **印章按钮** | `.seal-btn` | 半透主题色底 + 主题色边框 | `<button class="seal-btn">运行回测</button>` |
| **印章危险** | `.seal-btn + .seal-btn-danger` | 红透底 + 红边框 | `<button class="seal-btn seal-btn-danger">强制删除</button>` |

### 1.4 按钮辅助类

| Class | 说明 |
|---|---|
| `.btn-icon` | 方形图标按钮（宽高相等），搭配尺寸类使用 |
| `.btn-loading` | 加载中状态（旋转器 + 文字透明） |
| `.btn-group` | 按钮组（圆角合并） |
| `.btn-block` | 100% 宽度 |

### 1.5 图标上色工具类

给按钮内的 `<i>` 图标加色，三主题自动适配。

| 工具类 | 颜色 | 适用场景 | 示例 |
|---|---|---|---|
| `.ico-primary` | 主题蓝色 | 查看 / 详情 / 编辑 | `<i class="bi bi-eye ico-primary"></i>` |
| `.ico-success` | 绿色 | 新增 / 确认 / 增量 | `<i class="bi bi-plus ico-success"></i>` |
| `.ico-danger` | 红色 | 删除 / 取消 / 停用 | `<i class="bi bi-trash ico-danger"></i>` |
| `.ico-warning` | 黄色 | 警告 / 重建 / 全量 | `<i class="bi bi-exclamation ico-warning"></i>` |
| `.ico-info` | 青色 | 刷新 / 检测 / 搜索 | `<i class="bi bi-arrow-clockwise ico-info"></i>` |
| `.ico-muted` | 灰色 | 日志 / 历史 / 关闭 | `<i class="bi bi-clock-history ico-muted"></i>` |
| `.ico-white` | 白色 | 实心按钮上的图标 | `<i class="bi bi-check ico-white"></i>` |

> 描边按钮（`.btn-outline-*`）推荐给图标上色增加辨识度。实心按钮图标默认随文字色。

### 1.6 设计规范

1. 全部走 CSS 变量，azure / mist / cyber 三主题自动适配
2. 统一动效：hover 上移 1px + 阴影增强，过渡 0.25s
3. 实心按钮用双层柔光 shadow，科技感来源
4. 印章按钮点击有 `stamping` 钤印动画（scale 1→0.92→1.04→1，0.28s），`prefers-reduced-motion` 下关闭

---

## 二、卡片

### 2.1 基础卡片

```html
<div class="card card-glow">
    <div class="card-header">
        <h3 class="card-title">
            <span class="card-title-icon">📊</span>
            标题
        </h3>
        <div class="card-actions">
            <button class="btn btn-sm btn-secondary">操作</button>
        </div>
    </div>
    <div class="card-body">内容</div>
    <div class="card-footer">底部</div>
</div>
```

### 2.2 卡片变体

| Class | 说明 |
|---|---|
| `.card-glow` | hover 上浮 4px + 顶部渐显光条 + 蓝色边框（**推荐，最常用**） |
| `.card-title-icon` | 标题图标容器，默认蓝青色 |

### 2.3 标题图标颜色变体

```html
<span class="card-title-icon">默认</span>
<span class="card-title-icon rise">涨色</span>
<span class="card-title-icon fall">跌色</span>
<span class="card-title-icon purple">紫色</span>
<span class="card-title-icon yellow">金色</span>
```

---

## 三、统计条 Stat Strip（页面顶部数据概览 MUST 使用）

### 3.1 完整用法（复制即用）

```html
<div class="stat-strip">
    <div class="stat-card" style="--stat-accent: var(--accent-blue);">
        <div class="stat-head">
            <span class="stat-label"><i class="bi bi-collection"></i>总数</span>
            <span class="stat-tag">v1.0</span>
        </div>
        <div class="stat-value">128<span class="unit">个</span></div>
        <div class="stat-foot"><span class="dot" style="--c: var(--accent-blue);">说明文字</span></div>
    </div>
    <!-- 重复 4 张 stat-card，--stat-accent 分别用 --accent-blue / --accent-green / --accent-purple / --accent-yellow -->
</div>
```

### 3.2 要点

- 4 列 grid（`<=1280px` 自适应 2 列，`<=640px` 1 列）
- `.stat-card` 是玻璃态卡片（`backdrop-filter: var(--blur-card)` + 半透明 `--bg-card`）
- **`--stat-accent` 自定义属性**：每张卡片通过内联 style 定色，驱动顶部光带（`::before`）和 label 图标颜色
- **`.stat-foot .dot` 的 `--c` 自定义属性**：驱动底部圆点颜色
- `.stat-value` 强制 `var(--font-mono)`（数字识别），30px / 700 字重
- **禁止**在页面 CSS 里重定义 `.stat-strip` / `.stat-card`，直接用全局类

---

## 四、表格

### 4.1 推荐用法

```html
<div class="table-responsive">
    <table class="data-table">
        <thead>
            <tr>
                <th>名称</th>
                <th class="text-end">最新价</th>
                <th class="text-end">涨跌幅</th>
                <th class="text-center">操作</th>
            </tr>
        </thead>
        <tbody>
            <tr>
                <td>平安银行</td>
                <td class="font-mono text-end">12.35</td>
                <td class="font-mono text-end rise">+1.23%</td>
                <td class="text-center"><button class="btn btn-sm btn-outline-secondary">查看</button></td>
            </tr>
        </tbody>
    </table>
</div>
```

### 4.2 要点

- 外层加 `.table-responsive` 响应式
- 表头：`--bg-tertiary` 背景、11px uppercase、letter-spacing: .5px
- 行高 padding 12-14px；行分隔 `--border-light`；hover 行底色 `color-mix(in srgb, var(--accent-blue) 4%, transparent)`
- 数字列右对齐（`.text-end`），操作列居中（`.text-center`）
- 数值一律 `.font-mono`，正红负绿（`.rise` / `.fall`）
- 排名徽章：`.data-table .rank` 圆形 28px，普通用 `--bg-tertiary`，Top 排名用 `var(--gradient-primary)` + 白字

---

## 五、表单控件

### 5.1 输入框 / 下拉框

```html
<div class="mb-3">
    <label class="form-label">
        标签名
        <span class="text-muted font-mono" style="font-size:10px">hint</span>
    </label>
    <input class="form-control" placeholder="...">
    <div class="form-text">辅助说明</div>
</div>

<div class="mb-3">
    <label class="form-label">选择周期</label>
    <select class="form-select">
        <option value="daily">日K</option>
        <option value="weekly">周K</option>
    </select>
</div>
```

### 5.2 规格

| 尺寸 | Class |
|---|---|
| 小号 | `.form-control-sm` / `.form-select-sm` |
| 默认 | — |
| 大号 | `.form-control-lg` / `.form-select-lg` |

### 5.3 设计规范

- 输入框：`--bg-input` 背景 + `--border-color` 边框 + `--radius-md` 圆角，padding 10/14
- focus 态：`border-color: var(--accent-blue)` + `0 0 0 3px color-mix(in srgb, var(--accent-blue) 10%, transparent)` 外发光
- 代码 / ID / 参数类输入框加 `.font-mono`

### 5.4 校验状态

| 状态 | Class |
|---|---|
| 校验通过 | `.is-valid` |
| 校验失败 | `.is-invalid` |
| 错误提示 | `.invalid-feedback` |
| 成功提示 | `.valid-feedback` |

### 5.5 Checkbox / Radio / Switch

```html
<div class="form-check">
    <input class="form-check-input" type="checkbox" id="check1">
    <label class="form-check-label" for="check1">选项</label>
</div>

<div class="form-check form-switch">
    <input class="form-check-input" type="checkbox" id="switch1">
    <label class="form-check-label" for="switch1">开关</label>
</div>
```

### 5.6 输入组

```html
<div class="input-group">
    <span class="input-group-text">@</span>
    <input class="form-control" placeholder="用户名">
</div>
```

---

## 六、徽章 / 标签

### 6.1 语义徽章

| Class | 颜色 | 用途 |
|---|---|---|
| `.badge.bg-primary` | 主色 | 主要 / 激活态强调 |
| `.badge.bg-success` | 成功 / 绿 | 完成 / 成功 / 启用 |
| `.badge.bg-danger` | 危险 / 红 | 错误 / 删除 / 异常 |
| `.badge.bg-warning` | 警告 / 黄 | 警告 / 待处理 |
| `.badge.bg-info` | 信息 / 青 | 信息 / 进行中 |
| `.badge.bg-secondary` | 次要 / 灰 | 默认 / 草稿 / 普通 |

### 6.2 徽章辅助类

| Class | 说明 |
|---|---|
| `.badge-pill` | 胶囊圆角（`--radius-full`） |
| `.badge-dot` | 带前置圆点的徽章 |

**示例：**
```html
<span class="badge bg-primary">运行中</span>
<span class="badge bg-success badge-pill">已完成</span>
<span class="badge bg-danger badge-dot">异常</span>
```

### 6.3 涨跌色徽章

| Class | 语义 |
|---|---|
| `.badge-rise` / `.badge-up` | 涨 / 盈利 / 启用（红底） |
| `.badge-fall` / `.badge-down` | 跌 / 亏损 / 停用（绿底） |

> A 股习惯：红涨绿跌，严格遵守。

### 6.4 彩色来源标签（自定义模式）

参考 factor-library 的 `.src-tag` / 004 `.cat-badge` 模式：
- 1px 边框 + 同色 14% 透明底 + 字色用来源色
- 状态徽章前置 6px 圆点（`background: currentColor`）增强识别

---

## 七、Tabs 标签页

### 7.1 下划线式

```html
<ul class="nav nav-tabs">
    <li class="nav-item"><a class="nav-link active">概览</a></li>
    <li class="nav-item"><a class="nav-link">明细</a></li>
</ul>
```

### 7.2 胶囊式

```html
<ul class="nav nav-pills">
    <li class="nav-item"><a class="nav-link active">日K</a></li>
    <li class="nav-item"><a class="nav-link">周K</a></li>
</ul>
```

- `.nav-link.active`：激活态
- 胶囊式选中项用渐变填充

---

## 八、模态框 Modal

使用 Bootstrap `.modal`，视觉已主题化：
- `.modal-content`：深色 + `--radius-lg` + `--shadow-xl`
- 遮罩：`rgba(0,0,0,.6)` + `backdrop-filter: blur(4px)`
- 入场动画：`scaleIn .2s cubic-bezier(.4,0,.2,1)`（从 0.96 缩到 1 + fade in）

> 页面级自定义模态框（避免与 Bootstrap `.modal` class 冲突）用页面前缀（如因子库 `fl-modal`）。

---

## 九、进度条 / Loading

### 9.1 进度条

```html
<div class="progress mb-3">
    <div class="progress-bar" style="width: 75%;">75%</div>
</div>
```

| Class | 说明 |
|---|---|
| `.progress-sm` / `.progress-lg` | 细 / 粗进度条 |
| `.progress-bar-striped` | 条纹进度条 |
| `.progress-bar-animated` | 动画条纹 |

### 9.2 加载器

```html
<div class="spinner-border spinner-border-sm" role="status"></div>
```

| Class | 说明 |
|---|---|
| `.spinner-border-sm` / `.spinner-border-lg` | 小 / 大号加载器 |
| `.page-loading` | 全屏加载遮罩 |

---

## 十、空状态 Empty State

```html
<div class="empty-state">
    <i class="bi bi-inbox"></i>
    <h5>暂无数据</h5>
    <p>请调整筛选条件后再试</p>
    <button class="btn btn-primary btn-sm">刷新</button>
</div>
```

| Class | 说明 |
|---|---|
| `.empty-state` | 默认空状态 |
| `.empty-state-sm` | 紧凑空状态（表格内 / 小区域） |
| `.empty-state-lg` | 大空状态（整页） |

---

## 十一、Toast 通知

Toast 容器已由 `fragments/common :: toastContainer` 注入，直接调用 `showToast()`：

```javascript
showToast('操作成功', 'success');   // success / danger / warning / info
```

| Class | 说明 |
|---|---|
| `.toast-success` | 成功（左侧绿色边条） |
| `.toast-danger` | 危险 / 错误（左侧红色边条） |
| `.toast-warning` | 警告（左侧黄色边条） |
| `.toast-info` | 信息（左侧青色边条） |

---

## 十二、Tooltip / Popover

已全局主题化，无需额外 class。使用 Bootstrap 原生 API：

```html
<button data-bs-toggle="tooltip" title="提示文字">按钮</button>
<button data-bs-toggle="popover" data-bs-content="内容">按钮</button>
```

---

## 十三、通用工具类

| Class | 说明 |
|---|---|
| `.rise` / `.fall` | 涨红跌绿文字色 |
| `.rise-bg` / `.fall-bg` | 涨红跌绿背景 + 文字 |
| `.font-mono` / `.font-display` | 等宽字体 / 标题字体 |
| `.text-truncate-1` / `.text-truncate-2` | 单行 / 双行文本截断 |
| `.flex-center` / `.flex-between` | flex 居中 / 两端对齐 |
| `.flex-start` / `.flex-end` | flex 起点 / 终点对齐 |
| `.gap-4` / `.gap-6` / `.gap-8` / `.gap-12` / `.gap-16` | 间距工具（px） |
| `.w-full` / `.h-full` | 宽 / 高 100% |
| `.min-w-0` / `.min-h-0` | 最小宽 / 高 0 |
| `.cursor-pointer` / `.cursor-not-allowed` | 指针样式 |
| `.select-none` | 禁止文本选择 |
| `.text-xs` / `.text-sm` / `.text-base` / `.text-lg` / `.text-xl` | 字号工具 |
| `.font-medium` / `.font-semibold` / `.font-bold` | 字重工具 |

---

## 十四、布局组件（骨架）

### 14.1 App Container

```html
<div class="app-container">
    <nav class="sidebar">...</nav>
    <div class="main-content">
        <header class="top-navbar">...</header>
        <main class="content-area">
            <!-- 页面内容 -->
        </main>
    </div>
</div>
```

### 14.2 页面头部

```html
<div class="page-header">
    <h2 class="page-title">页面标题</h2>
    <div class="page-actions">
        <button class="btn btn-primary">操作</button>
    </div>
</div>
```

---

## 十五、图标（MUST 使用 Bootstrap Icons）

- 图标库：Bootstrap Icons（已在 head fragment 中通过 CDN 引入）
- class 格式：`bi bi-图标名`
- 按钮 / 链接前加图标增强识别度；图标与文字之间加间距（`me-1` / `ms-1`）

```html
<i class="bi bi-search me-1"></i>
<i class="bi bi-star-fill text-warning me-1"></i>
<i class="bi bi-candlestick me-1"></i>
```

> 颜色通过 `.ico-*` 工具类或 `.text-*` 类控制；数据类图标（涨/跌箭头）优先用 `.rise` / `.fall`。

---

## 十六、JSON 代码展示（MUST 带语法高亮）

所有展示 JSON / 配置 / 代码的区块配色：
- 背景：`--bg-tertiary`
- Key（`.json-key` / `.jkey`）：`--accent-cyan`
- 字符串（`.json-str` / `.jstr`）：`--accent-green`
- 数字（`.json-num` / `.jnum`）：`--accent-yellow`
- 布尔 / null（`.json-bool` / `.jbool` / `.json-null` / `.jnull`）：`--accent-purple` / `--text-muted` 斜体
- 错误（`.jerr`）：`--rise-light`
- 字体：`--font-mono`，11-13px，line-height: 1.6-1.7
