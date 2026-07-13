---
alwaysApply: true
description: "StockPulse 前端开发唯一规范——所有 stock-watcher 前端页面（Thymeleaf 模板 + CSS/JS）MUST 遵循。当用户新建页面、编写 Thymeleaf 模板、添加 CSS 样式、使用 Bootstrap 组件、设计 UI 原型时强制触发。覆盖：整体视觉风格（三主题可切换：azure 云隙蔚蓝默认/mist 晨雾青瓷/cyber 深空赛博，tokens 在 theme.css 的 :root + [data-theme] 中定义，顶栏按钮循环切换、localStorage 持久化、theme.js 管理、图表颜色走 charts-theme.js 读 CSS 变量）、页面骨架与公共片段、HTML 编码约定（语义化/属性顺序/注释）、Design Tokens（颜色/字体/圆角/阴影/动效，禁止硬编码色值、透明度色值用 color-mix）、组件标准样式（卡片/按钮/统计条/表格/表单/徽章/模态框/JSON/筛选栏/图标）、Bootstrap 5 使用边界、布局模式（三段式/三栏指挥台/主从/Tab编辑器）、交互动效、CSS 写法规范（分层/BEM/响应式）、交付前自查清单。标杆参考：factor-library.html 与 003/004 原型。"
---

# StockPulse 前端开发规范

> **面向 AI**：所有 stock-watcher 前端页面（Thymeleaf 模板 + 自定义 CSS/JS）**必须**遵循本规范。本文是前端相关的**唯一权威文档**，整合了视觉设计系统与 HTML/CSS/Bootstrap 编码约定，无需再查其他规则文件。
>
> 规范基于以下实际代码与原型提炼：
> - 设计 Tokens 与基础样式：[theme.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/theme.css)
> - 组件覆盖层（Bootstrap 深色化 + 自定义组件）：[components.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/components.css)
> - 标杆页面：[factor-library.html](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/pages/factor-library.html) + [factor-library.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/factor-library.css)
> - 公共片段：[fragments/common.html](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/fragments/common.html)
> - 视觉原型标杆：[003 多因子选股中心 styles.css](file:///d:/lcProject/stock-pulse/.trae/specs/003-multi-factor-screener/prototype/styles.css) + [003 design.md](file:///d:/lcProject/stock-pulse/.trae/specs/003-multi-factor-screener/prototype/design.md) + [004 策略管理 prototype.css](file:///d:/lcProject/stock-pulse/.trae/specs/004-strategy-management/prototype/prototype.css)
>
> **核心原则**：Bootstrap 仅作为栅格/弹窗/flex 工具类底层使用，所有**视觉样式**（颜色/字体/圆角/阴影/渐变/毛玻璃）统一走 `theme.css` 的 `:root` CSS 变量体系，**禁止硬编码色值、禁止依赖 Bootstrap 默认浅色样式**。

---

## 一、整体视觉风格（MUST — 全站风格统一基准）

本系统的视觉设计以 **因子库**、**多因子选股中心（003）**、**策略管理（004）** 三个页面/原型为基准，所有新页面 MUST 保持一致的视觉气质。

### 1.1 设计气质

- **三主题可切换**：系统提供三套主题，顶栏图标按钮循环切换（`ThemeManager.cycle()`），`localStorage(sp-theme)` 持久化：
  - **azure 云隙蔚蓝**（默认）：浅蓝灰底 + 靛蓝主色 + 青光氛围，明亮护眼。
  - **mist 晨雾青瓷**：雾白底 + 青瓷绿主色 + 墨蓝字，低饱和、长时盯盘护眼。
  - **cyber 深空赛博**：近黑深蓝底 + 霓虹青/品红高光 + 扫描线动效，最强科技感。
  - 所有主题相关 token 定义在 `theme.css` 的 `[data-theme="xxx"]` 选择器中；主题无关 token（radius/layout/transition/font）在 `:root` 中定义。详见 §二。
- **科技感 + 数据密集**：玻璃态卡片、毛玻璃模糊、径向渐变氛围光、等宽字体强化数字识别——三套主题都延续这些特征。
- **A 股语义**：涨红跌绿（与海外相反，见 §五 3.3），三套主题均遵守，但具体色值不同（见 theme.css）。

### 1.2 七大核心视觉特征（从基准页面提炼，新页面 MUST 延续）

| # | 特征 | 实现要点 |
|---|---|---|
| 1 | **玻璃态卡片** | `background: var(--bg-card)`（半透明）+ `backdrop-filter: var(--blur-card)`（blur 10px）+ `border: 1px solid var(--border-color)` + `border-radius: var(--radius-lg)`（003 `.rail-section`/`.editor-canvas`/`.result-panel`、004 `.strategy-card`/`.summary-card` 均如此） |
| 2 | **径向渐变氛围光** | 全局 `body::before` 已注入三处径向渐变，颜色由 `var(--ambient-1/2/3)` 驱动（azure 靛蓝/青/紫，mist 青瓷/雾蓝/远紫，cyber 霓虹青/品红/荧光绿）。**页面不要再自画背景**，透明卡片会自然透出氛围光。cyber 主题额外有 `body::after` 扫描线动效 |
| 3 | **顶部光带** | 卡片 `::before` 一条 1px 横向渐显光条（`linear-gradient(90deg, transparent, var(--stat-accent, var(--accent-blue)), transparent)`），用 `--stat-accent` 自定义属性按卡片定色（004 `.stat-card::before`、`.summary-card::before`）。浅色主题（azure/mist）用柔和投影，cyber 用霓虹 glow |
| 4 | **主渐变** | `var(--gradient-primary)` 是品牌色渐变（azure 靛蓝、mist 青瓷绿、cyber 霓虹青），用于主按钮 / Logo / 激活态 / 标题图标 / 印章式主操作按钮。各主题具体色值见 theme.css，**不要硬编码**。 |
| 5 | **mono 字体强制** | 凡数字 / 股票代码 / 百分比 / 金额 / 指标值 / ID / 时间戳 / JSON key / 配置 key，MUST 用 `var(--font-mono)` 或 `.font-mono`（JetBrains Mono） |
| 6 | **涨红跌绿** | 正数 / 盈利 / 涨用 `.rise` / `var(--rise-light)`，负数 / 亏损 / 跌用 `.fall` / `var(--fall-light)`，严格 A 股习惯 |
| 7 | **微动效** | 可交互卡片 hover `translateY(-3px ~ -4px)` + `box-shadow` 增强，过渡时长用 `var(--transition-base)`（0.25s），避免突兀或夸张缓动；主操作按钮可用印章式 `stamping` 动效（见 003 `.seal-btn`） |

### 1.3 视觉一致性硬约束（MUST NOT）

- **不要**在页面 CSS 里自画页面级背景（径向渐变 / 纯色），复用全局 `body::before`。
- **不要**用 Bootstrap 原生浅色卡片 / 默认蓝色按钮，用项目的 `.card.card-glow` / `.btn.btn-primary`。
- **不要**混用海外涨跌色（绿涨红跌）。
- **不要**在页面 CSS 里定义新的 `[data-theme="xxx"]` 块或硬编码主题色——主题切换只在 `theme.css` 的 `[data-theme]` 中定义，详见 §二。

---

## 二、多主题系统（MUST — 三主题可切换）

本系统提供**三套可切换主题**，由 `theme.js` 的 `ThemeManager` 管理：

| key | 名称 | 定位 | 默认 |
|---|---|---|---|
| `azure` | 云隙蔚蓝 | 浅蓝灰底 + 靛蓝主色，明亮护眼 | ✅ |
| `mist` | 晨雾青瓷 | 雾白底 + 青瓷绿主色，低饱和长时盯盘 | |
| `cyber` | 深空赛博 | 近黑深蓝 + 霓虹青/品红 + 扫描线，最强科技感 | |

### 2.1 token 组织规则（MUST）

- **主题无关 token**（`--radius-*` / `--sidebar-width` / `--navbar-height` / `--content-padding` / `--transition-*` / `--font-*`）定义在 `theme.css` 的 `:root` 中，所有主题共享。
- **主题相关 token**（`--bg-*` / `--text-*` / `--border-*` / `--accent-*` / `--rise-*` / `--fall-*` / `--shadow-*` / `--gradient-*` / `--blur-*` / `--ambient-*` / `--chart-*`）定义在 `[data-theme="azure|mist|cyber"]` 选择器中，每套主题各一组值。
- 图表专用 token（`--chart-text` / `--chart-grid` / `--chart-axis` / `--chart-tooltip-*` / `--chart-crosshair*` / `--chart-split-line`）也按主题定义，供 `charts-theme.js` 运行时读取。
- **页面 CSS MUST NOT**：定义新的 `[data-theme="xxx"]` 块、重新定义 `:root` 或 `[data-theme]` 中已有 token、硬编码 `#xxx` 主题色。
- 页面专属颜色（如因子来源色）通过在页面 CSS **新增专属 token**（如 `--src-akquant`、`--stat-accent`、`--card-accent`）定义，这些业务语义色不随主题变，赋单一值即可。

### 2.2 主题切换机制

- **切换入口**：顶栏 `#themeToggle` 图标按钮（已由 `fragments/common :: topNavbar` 注入），点击循环 `azure → mist → cyber → azure`，图标随主题变（`bi-sun` / `bi-droplet-half` / `bi-lightning-charge`）。
- **持久化**：`localStorage(sp-theme)`，由 `theme.js` 读写。
- **防首屏闪烁**：`fragments/common :: head` 最前面有一段内联阻塞脚本，在 CSS 加载前同步设置 `<html data-theme="...">`，**页面模板不需手动加 `data-theme`**。此外 `theme.css` 的 `:root` 同时绑定 azure 值作为兜底，即使未设 `data-theme`（如独立布局的登录页）也能正常显示 azure 主题。
- **切换 API**：`ThemeManager.cycle()`（循环切换）、`ThemeManager.get()`（取当前 key）、`ThemeManager.set(key)`（指定切换）。
- **主题变更事件**：切换时派发 `window` 的 `theme:changed` 事件（`detail.theme` 为新 key），`charts-theme.js` 等模块监听后自动重绘。

### 2.3 新页面开发指引（MUST）

- **颜色只用 `var()`**：所有颜色引用全局 token，禁止硬编码 `#xxx`（`#fff`/纯黑阴影/`transparent` 中性色除外）。
- **透明度色值用 `color-mix`**：需要带透明度的主题色，用 `color-mix(in srgb, var(--accent-blue) 15%, transparent)`，**不要写 `rgba(主题RGB, 0.x)`**——那样无法跟随主题。
- **图表颜色走 `ChartsTheme`**：新增 ECharts/Lightweight Charts 实例后，MUST 调用 `ChartsTheme.register(instance, type)` 注册（type 为 `'echarts'` 或 `'lightweight'`），主题切换时自动重绘；图表内所有颜色通过 `ChartsTheme.getEChartsTheme()` / `getKlineTheme()` / `getChartColors()` / `getBarGradient()` 取，**禁止在页面 JS 里硬编码图表色值**。
- **新增主题**：未来若要加主题，在 `theme.css` 新增 `[data-theme="新key"]` 块并补全所有 token 即可，架构已支持。

> 历史背景：系统曾经历"单一深色主题（无切换）"阶段，该阶段的 `data-theme` 禁令已废止。现在 `data-theme` 由 `theme.js` + head 内联脚本管理，是正式的多主题机制。

---

## 三、页面骨架与公共片段（MUST）

### 1.1 标准模板（复制即用）

所有页面统一使用以下结构，通过 Thymeleaf fragment 从 `fragments/common.html` 引入公共片段（head/sidebar/topNavbar 等已封装，**不要自己重写**）：

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/common :: head(${pageTitle})}"></head>
<body>
<div class="app-container">
    <!-- 侧边栏（fragment: sidebar，无参数） -->
    <nav th:replace="~{fragments/common :: sidebar}"></nav>
    <!-- 移动端侧边栏遮罩（fragment: sidebarOverlay） -->
    <div th:replace="~{fragments/common :: sidebarOverlay}"></div>

    <!-- 主区 -->
    <div class="main-content">
        <!-- 顶部栏（fragment: topNavbar） -->
        <header th:replace="~{fragments/common :: topNavbar}"></header>
        <!-- 内容 -->
        <main class="content-area">
            <!-- 页面内容 -->
        </main>
    </div>
</div>

<!-- Toast 容器（fragment: toastContainer） -->
<div th:replace="~{fragments/common :: toastContainer}"></div>
<!-- 公共 JS（fragment: scripts：Bootstrap/ECharts/lightweight-charts/common.js） -->
<div th:replace="~{fragments/common :: scripts}"></div>
<!-- 页面专属 CSS（放在 body 末尾，确保覆盖公共样式） -->
<link rel="stylesheet" th:href="@{/css/<page>.css}">
<!-- 页面专属 JS -->
<script th:src="@{/js/<page>.js}"></script>
</body>
</html>
```

### 1.2 硬性要求

- `<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">`——**不要手动设置 `data-theme` 属性**（由 `fragments/common :: head` 的内联脚本根据 `localStorage` 自动设置，详见 §二）。
- **基础 HTML 要求**：`<!DOCTYPE html>` 声明、`lang="zh-CN"`、引入 Thymeleaf 命名空间 `xmlns:th`、UTF-8 编码（head fragment 已处理）、viewport 适配移动端（head fragment 已处理）。
- **CSS/JS 加载顺序**（由 head fragment 保证，不要自己改）：Bootstrap CDN → Bootstrap Icons CDN → Google Fonts → theme.css → components.css → custom.css；页面专属 CSS/JS 放在 body 末尾（`scripts` fragment 之后）以确保覆盖优先级。
- **Layout 尺寸常量**：侧边栏 `--sidebar-width: 260px`，顶部栏 `--navbar-height: 68px`，内容区 padding `--content-padding: 28px`。
- **Body 背景**：不要自行设置纯色背景。theme.css 已通过 `body::before` 注入三处径向渐变氛围光（蓝左上、紫右上、青底部），直接复用。
- 页面标题格式：`页面名 · StockPulse`，通过 `pageTitle` 变量传给 head fragment。

### 1.3 公共片段清单（MUST 从 fragments/common.html 引入）

| Fragment 名 | 用途 | 参数 |
|---|---|---|
| `head(title)` | `<head>` 标签（meta/title/CSS/字体） | `title`：页面标题字符串 |
| `sidebar` | 左侧导航栏 | 无 |
| `sidebarOverlay` | 移动端侧边栏遮罩 | 无 |
| `topNavbar` | 顶部导航栏（面包屑、搜索、通知、用户） | 无 |
| `scripts` | 公共 JS（Bootstrap/ECharts/lightweight-charts/common.js） | 无 |
| `toastContainer` | Toast 消息容器 | 无 |

- 不要在每个页面重复写 `<link>`/`<script>` 清单，统一通过 fragment 引入。
- 当前**没有** footer fragment，不要引用不存在的 `:: footer`。
- 顶部栏内已内置主题切换按钮（`#themeToggle`，由 `topNavbar` fragment 渲染、`theme.js` 绑定），**不要再自行添加切换按钮**，也不要引用已废弃的 `#themeToggleBtn`/`toggleTheme()`（现用 `ThemeManager.cycle()`）。

---

## 四、HTML 编码约定

### 2.1 语义化标签（SHOULD）

优先使用语义化标签，避免全用无语义 `<div>`：

| 标签 | 用途 |
|---|---|
| `<header>` | 页面/区块头部（如 `.top-navbar`、`.page-header`） |
| `<nav>` | 导航（如 `.sidebar`） |
| `<main>` | 主要内容（`.content-area`） |
| `<section>` | 区块 |
| `<article>` | 独立内容块 |
| `<aside>` | 侧边栏/详情面板 |
| `<footer>` | 页脚（当前项目未使用） |

```html
<!-- ✅ 好的 -->
<header class="top-navbar">...</header>
<main class="content-area">...</main>
<nav class="sidebar">...</nav>

<!-- ❌ 不推荐 -->
<div class="header">...</div>
<div class="main">...</div>
```

### 2.2 标签闭合（MUST）

- 自闭合标签正确使用：`<br>`、`<hr>`、`<img>`、`<input>`、`<meta>`
- HTML5 中自闭合标签可不加 `/`，但风格保持一致

### 2.3 属性顺序（SHOULD）

建议按以下顺序排列属性，便于阅读：

1. `id`
2. `class`
3. `name` / `type`
4. `data-*`
5. `src` / `href` / `action` / `placeholder`
6. `title` / `alt`
7. `aria-*` / `role`
8. 事件（`onclick` 等）
9. Thymeleaf 属性（`th:*`）

```html
<input
    id="searchInput"
    class="form-control font-mono"
    type="text"
    data-bs-toggle="tooltip"
    placeholder="输入股票代码"
    th:value="${keyword}"
    onclick="handleSearch()"
>
```

### 2.4 注释（SHOULD）

- 复杂结构加注释说明
- 模块之间用分隔注释
- 不写无意义的注释

```html
<!-- ============ 行情图表区域 ============ -->
<div class="card card-glow">
    ...
</div>
```

---

## 五、Design Tokens（MUST — 只用变量，不写硬编码色值）

所有颜色、圆角、阴影、字体、间距 MUST 使用下列 CSS 变量。**禁止**在页面 CSS 中硬编码 `#0a0e17`/`#f1f5f9`/`16px` 这类字面量（唯一例外：`white`/`#fff`/`transparent` 和 box-shadow 的 `rgba(0,0,0,.*)` 纯黑）。需要带透明度的主题色用 `color-mix(in srgb, var(--token) N%, transparent)`，不要写 `rgba(主题RGB, 0.x)`。

> 下列 token 在 `theme.css` 中定义：主题无关的（`--radius-*`/`--sidebar-width`/`--font-*`/`--transition-*`）在 `:root`，主题相关的颜色/阴影/渐变/模糊/氛围光/图表色在三套 `[data-theme="azure|mist|cyber"]` 中各有一组值。**具体色值见 theme.css，本表只列 token 名与用途**，不在规范里硬编码（避免与 theme.css 漂移）。

### 3.1 颜色色板

| Token | 值 | 用途 |
|---|---|---|
| `--bg-primary` | `#0a0e17` | 页面最底层背景 |
| `--bg-secondary` | `#0f1520` | 侧边栏/二级容器背景 |
| `--bg-tertiary` | `#151d2b` | 输入框/徽章底色/表头背景 |
| `--bg-card` | `rgba(21,29,43,0.6)` | 卡片主背景（带透明度以透出氛围光） |
| `--bg-card-hover` | `rgba(30,41,59,0.8)` | 卡片 hover 态 |
| `--bg-elevated` | `#1e293b` | 浮层/下拉菜单 |
| `--bg-input` | `#151d2b` | 输入框默认背景 |
| `--bg-input-hover` | `#1e293b` | 输入框 focus 背景 |
| `--text-primary` | `#f1f5f9` | 标题/正文主文字 |
| `--text-secondary` | `#94a3b8` | 正文次要文字/说明 |
| `--text-muted` | `#64748b` | 辅助文字/标签/时间戳 |
| `--text-inverse` | `#0f172a` | 深色按钮上的反白文字 |
| `--border-color` | `rgba(71,85,105,0.3)` | 卡片/输入框主边框 |
| `--border-light` | `rgba(148,163,184,0.1)` | 分隔线/表格行分割 |
| `--border-strong` | `rgba(100,116,139,0.5)` | 强边框（极少用） |

### 3.2 主题强调色

| Token | 色值 | 用途 |
|---|---|---|
| `--accent-blue` / `--accent-blue-light` | `#3b82f6` / `#60a5fa` | 主品牌色 / 链接 / 激活态 |
| `--accent-cyan` / `--accent-cyan-light` | `#06b6d4` / `#22d3ee` | 数据/代码/科技感高亮（JSON key、数值、等宽文字） |
| `--accent-purple` / `--accent-purple-light` | `#8b5cf6` / `#a78bfa` | 衍生/二级强调（tushare 来源、次要 CTA） |
| `--accent-yellow` | `#fbbf24` | 警告/草稿状态/原始数据 |
| `--accent-orange` | `#f97316` | 警告渐变副色 |
| `--accent-green` | `#22c55e` | **成功/激活态语义**（如"已验证"绿点、对勾图标）。色值与 `--fall-color` 相同但语义独立，避免"激活绿=跌"混淆。 |

### 3.3 涨跌色（A股习惯：**涨红跌绿**，MUST 严格遵守）

| Token | 色值 | 语义 |
|---|---|---|
| `--rise-color` / `--rise-light` / `--rise-bg` / `--rise-glow` | `#ef4444` / `#f87171` / `rgba(239,68,68,.15)` / `rgba(239,68,68,.3)` | **涨/正/盈利/买入/红** |
| `--fall-color` / `--fall-light` / `--fall-bg` / `--fall-glow` | `#22c55e` / `#4ade80` / `rgba(34,197,94,.15)` / `rgba(34,197,94,.3)` | **跌/负/亏损/卖出/绿** |

> ⚠️ **易犯错误**：沿用海外习惯（绿涨红跌）。**本系统严格相反**。
> 工具类 `.rise` / `.fall` / `.text-rise` / `.text-fall` / `.bg-rise` / `.bg-fall` 已在 components.css 预置，直接加 class 即可。
> Bootstrap 原生 `text-danger`=红（对应涨）、`text-success`=绿（对应跌）语义上刚好契合，可使用但**优先用项目语义类 `.rise`/`.fall`**。

```html
<!-- ✅ 推荐 -->
<span class="font-mono rise" th:if="${change >= 0}">+1.23%</span>
<span class="font-mono fall" th:if="${change < 0}">-0.45%</span>
```

### 3.4 渐变（用于按钮/Logo/卡片顶部光带/激活态）

| Token | 定义 | 用途 |
|---|---|---|
| `--gradient-primary` | `linear-gradient(135deg, #3b82f6, #06b6d4)` | **主按钮/Logo/激活项/主强调/印章式主操作按钮**（蓝→青，最常用） |
| `--gradient-secondary` | `linear-gradient(135deg, #8b5cf6, #3b82f6)` | 次强调（紫→蓝） |
| `--gradient-rise` | `linear-gradient(135deg, #ef4444, #f97316)` | 危险按钮/涨色强调 |
| `--gradient-fall` | `linear-gradient(135deg, #22c55e, #10b981)` | 成功/盈利态 |
| `--gradient-gold` | `linear-gradient(135deg, #fbbf24, #f59e0b)` | 星星/警告 |

### 3.5 圆角（四级）

| Token | 值 | 典型用途 |
|---|---|---|
| `--radius-sm` | `8px` | 按钮/输入框/图标按钮/徽章容器/小标签 |
| `--radius-md` | `12px` | 次级卡片/搜索框/按钮默认圆角/表单输入框 |
| `--radius-lg` | `16px` | **卡片/模态框/面板主圆角（最常用）** |
| `--radius-xl` | `24px` | 极少用（大浮层） |
| `--radius-full` | `9999px` | 胶囊徽章/头像/开关圆点 |

### 3.6 阴影

> 阴影按主题语义不同：浅色主题（azure/mist）用**柔和投影**（`rgba(深色, 低透明)`，制造层次感），cyber 用**深色投影 + 霓虹 glow**（`rgba(0,0,0,高透明)` + 发光）。具体值见 theme.css。

| Token | 用途 |
|---|---|
| `--shadow-sm` | 小元素轻微阴影 |
| `--shadow-md` | 浮层/下拉 |
| `--shadow-lg` | **卡片/模态框默认阴影** |
| `--shadow-xl` | 模态框/大屏浮层 |
| `--shadow-glow-blue` | Logo/主按钮发光（cyber 下为霓虹 glow） |
| `--shadow-glow-rise` / `--shadow-glow-fall` | 涨跌强调元素发光 |

### 3.7 字体族（三层）

| Token | 栈 | 用途 |
|---|---|---|
| `--font-display` | `'Space Grotesk', 'Noto Sans SC', ...` | 大标题/Logo/品牌文字（h1-h6 默认） |
| `--font-body` | `'Noto Sans SC', -apple-system, ...` | 正文/UI 文案 |
| `--font-mono` | `'JetBrains Mono', 'SF Mono', Consolas, monospace` | **所有数字/代码/ID/时间戳/JSON/参数名**（MUST） |

> **强制规则**：凡是**股票代码、价格、百分比、金额、指标数值、ID、配置 key、路径、时间戳**，一律加 `.font-mono` class 或在 CSS 中设 `font-family: var(--font-mono)`。这是因子库页建立的强视觉识别。

### 3.8 字号/间距/动效/模糊

**字号基准**：`<html font-size: 14px>`（1rem = 14px）。
- 页面大标题：22px / 20px；卡片标题：15px / 14px；正文：13px / 12.5px；辅助文字：11-12px；极小文字（徽章/表头）：10-11px。

**间距系统**（基于 4px 栅格）：4 / 6 / 8 / 10 / 12 / 14 / 16 / 18 / 20 / 24 / 28 / 32。
- 卡片内边距：16-20px / 24px；表单项间距：14-18px；卡片间距 gap：16px；统计条 gap：14-16px。

**动效曲线**（MUST 用变量，不要写 `all 0.3s`）：
- `--transition-fast: 0.15s cubic-bezier(.4,0,.2,1)` — 按钮/输入框 hover
- `--transition-base: 0.25s cubic-bezier(.4,0,.2,1)` — 卡片/面板显隐
- `--transition-slow: 0.4s cubic-bezier(.4,0,.2,1)` — 大布局切换

**模糊效果**：
- `--blur-card: blur(10px)` — 卡片毛玻璃
- `--blur-navbar: blur(20px)` — 顶部栏/侧边栏毛玻璃

---

## 六、组件规范（SHOULD 复用已有类）

components.css 已预置下列组件，**直接用 class，不要重复定义**。

### 4.1 卡片 `.card` + `.card-glow`

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

- `.card-glow` 提供 hover 上浮 4px + 顶部渐显光条 + 蓝色边框，适用于可点击/数据展示卡片。
- `.card-title-icon` 颜色变体：默认（蓝青）、`.rise`（红橙，涨色）、`.fall`（绿，跌色）、`.purple`（紫）、`.yellow`（金）。
- 页面级卡片（非 Bootstrap 覆盖场景）可按 factor-library / 003 / 004 模式写 `background:var(--bg-card) + border:1px solid var(--border-color) + border-radius:var(--radius-lg) + backdrop-filter:var(--blur-card)`，但优先用 `.card`。

### 4.2 按钮 `.btn`

```html
<button class="btn btn-primary btn-sm">主按钮</button>
<button class="btn btn-secondary">次按钮</button>
<button class="btn btn-outline-primary">幽灵按钮</button>
<button class="btn btn-danger btn-sm">危险</button>
<button class="btn btn-success">成功</button>
<button class="btn btn-icon btn-sm">🔔</button>   <!-- 36/28px 方形图标按钮 -->
```

- 三种尺寸：默认（padding 8/16, font 13, radius-md）、`.btn-sm`（6/12, font 12, radius-sm）、`.btn-lg`（12/24, font 15, radius-lg）。
- 主按钮（`.btn-primary`）自带 `var(--gradient-primary)` 蓝→青渐变 + `0 2px 8px` 蓝色投影 + hover 上浮 1px。
- 图标按钮（`.btn-icon`）宽高相等，默认 36px，`.btn-sm` 后 28px。
- **印章式主操作按钮**（003 `.seal-btn` 风格）：用于"运行/确认"这类强决策动作，高度 40px、`var(--gradient-primary)` 渐变、`font-weight:700`、点击触发 `stamping` 钤印动画（scale 1→0.92→1.04→1，0.28s），`prefers-reduced-motion` 下关闭。

### 4.3 统计条 `.stat-strip`（MUST 用于页面顶部数据概览）

**已在 components.css 提炼为全局标准类**（以 factor-library.css / 004 `.stat-strip` 为蓝本），新页面直接复用，无需自己写一套。

完整用法（复制即用）：

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

要点：
- 4 列 grid（`<=1280px` 自适应 2 列，`<=640px` 1 列）。
- `.stat-card` 是玻璃态卡片（`backdrop-filter: var(--blur-card)` + 半透明 `--bg-card`）。
- **`--stat-accent` 自定义属性**：每张卡片通过内联 `style="--stat-accent: var(--accent-blue);"` 定色，驱动顶部光带（`::before`）和 label 图标颜色。
- **`.stat-foot .dot` 的 `--c` 自定义属性**：驱动底部圆点颜色，同样用 `var(--accent-*)`。
- `.stat-value` 强制 `var(--font-mono)`（数字识别），30px / 700。
- **禁止**在页面 CSS 里重定义 `.stat-strip`/`.stat-card`，直接用全局类。页面专属变体可用带前缀的 class（如策略页 `.str-stat-card`）扩展。

### 4.4 表格

推荐使用项目自定义 `.data-table` class，也可使用 Bootstrap `.table.table-hover`（已深色化）。**禁止使用 `.table-light`/`.table-dark` 等 Bootstrap 主题 class**（与深色主题冲突）。

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
                <td class="text-center"><button class="btn btn-sm btn-primary">查看</button></td>
            </tr>
        </tbody>
    </table>
</div>
```

- 外层加 `.table-responsive` 响应式。
- 表头无需额外 class：components.css 已设 `--bg-tertiary` 背景、11px uppercase、letter-spacing:.5px、sticky top:0 时半透明。
- 行高 padding 12-14px；行分隔 `--border-light`；hover 行底色 `rgba(59,130,246,.04)`。
- 数字列右对齐（`.text-end`），操作列居中（`.text-center`）；数值一律 `.font-mono`，正红负绿。
- 排名徽章（003 `.data-table .rank`）：圆形 28px，普通用 `--bg-tertiary`，Top 排名用 `var(--gradient-primary)` + 白字。

### 4.5 表单控件

`.form-control`/`.form-select` 已被 components.css 覆盖为深色主题，直接使用即可。

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
    <label class="form-label">股票代码</label>
    <input class="form-control font-mono" placeholder="如 000001.SZ">
</div>

<div class="mb-3">
    <label class="form-label">选择周期</label>
    <select class="form-select">
        <option value="daily">日K</option>
        <option value="weekly">周K</option>
    </select>
</div>
```

- 输入框：`--bg-input` 背景 + `--border-color` 边框 + `--radius-md` 12px 圆角，padding 10/14。
- focus 态：`border-color: var(--accent-blue)` + `0 0 0 3px color-mix(in srgb, var(--accent-blue) 10%, transparent)` 外发光。
- 代码/ID/参数类输入框加 `.font-mono`。
- 下拉 `.form-select` 已深色化，直接用。

**开关**（非 Bootstrap 原生）：参考 004 原型 `.switch` 组件——38×22 圆角胶囊，选中态 `linear-gradient(135deg, rgba(59,130,246,.25), rgba(6,182,212,.15))` 蓝青渐变背景 + cyan 圆点位移 16px。

### 4.6 徽章 / 标签

Bootstrap `.badge` 已被覆盖为深色样式。使用项目自定义类（不是 Bootstrap 的 `bg-primary`）：

| class | 外观 | 用途 |
|---|---|---|
| `.badge-primary` | 蓝底蓝字 | 主要/激活态强调 |
| `.badge-rise` / `.badge-up` | 红底 | 涨/盈利/错误/启用 |
| `.badge-fall` / `.badge-down` | 绿底 | 跌/亏损/成功/停用 |
| `.badge-secondary` | 灰底 | 默认/草稿/普通 |
| `.badge-warning` | 黄底 | 警告 |

胶囊（圆角 `--radius-full`），mono 字体，padding 4/10。

**彩色标签（来源/分类/类型）**：参考 factor-library 的 `.src-tag` / 004 `.cat-badge` / `.status-badge` 模式——1px 边框 + 同色 14% 透明底 + 字色用来源色，是彩色来源标签的标准做法。状态徽章（004 `.status-badge`）前置 6px 圆点（`background: currentColor`）增强识别。

### 4.7 模态框

使用 Bootstrap `.modal` 但注意：
- `.modal-content` 已被覆盖为深色 + `--radius-lg` + `--shadow-xl`。
- 遮罩 `rgba(0,0,0,.6)` + `backdrop-filter: blur(4px)`。
- 入场动画：`scaleIn .2s cubic-bezier(.4,0,.2,1)`（从 0.96 缩到 1 + fade in）。
- 页面级自定义模态框（避免与 Bootstrap `.modal` class 冲突）用页面前缀（如因子库 `fl-modal`）。

### 4.8 JSON 代码展示（MUST 带语法高亮）

所有展示 JSON/配置/代码的区块遵循 factor-library `.pg-json` / `.code-hint` / 004 `.json-card` 配色：
- 背景：`--bg-tertiary`
- Key（`.json-key` / `.jkey`）：`--accent-cyan`
- 字符串（`.json-str` / `.jstr`）：`--accent-green`
- 数字（`.json-num` / `.jnum`）：`--accent-yellow`
- 布尔/null（`.json-bool`/`.jbool`/`.json-null`/`.jnull`）：`--accent-purple` / `--text-muted` 斜体
- 错误（`.jerr`）：`--rise-light`
- 字体：`--font-mono`，11-13px，line-height:1.6-1.7

### 4.9 筛选 / 搜索栏

参考 factor-library、003 `.filter-grid`、004 `.filter-bar`：
- `background: var(--bg-card)` + `border: 1px solid var(--border-color)` + `border-radius: var(--radius-lg)` + `padding: 14px 18px` + `backdrop-filter: var(--blur-card)`
- 搜索框左侧图标绝对定位（left:10-14px, top:50%），输入框 `padding-left: 32-44px`
- 下拉选择：`appearance: none` + 自定义 svg 箭头（颜色 `--text-muted`）
- 布局：`display: flex; gap: 10px; flex-wrap: wrap`，最右 spacer `flex: 1` 把视图切换按钮推到右侧

### 4.10 图标（MUST 使用 Bootstrap Icons）

- 图标库：Bootstrap Icons（已在 head fragment 中通过 CDN 引入）
- class 格式：`bi bi-图标名`
- 按钮/链接前加图标增强识别度；图标与文字之间加间距（`me-1` / `ms-1`）

```html
<i class="bi bi-search me-1"></i>
<i class="bi bi-star-fill text-warning me-1"></i>
<i class="bi bi-candlestick me-1"></i>
```

> 颜色通过 `text-*` 类控制；数据类图标（涨/跌箭头等）优先用 `.rise`/`.fall`。

---

## 七、Bootstrap 5 使用边界

> ⚠️ **重要前提**：Bootstrap 的默认浅色视觉样式（白底、黑字、浅色表头、蓝按钮等）已被 theme.css + components.css 覆盖为深色主题。使用 Bootstrap class 时实际视觉效果由本规范设计系统决定，**不要按 Bootstrap 官网浅色默认来预期效果**。

### 5.1 适用场景（用 Bootstrap 工具类）

Bootstrap 优先用于**布局/间距/对齐/交互底层**：
- **Grid 栅格**：`container`/`row`/`col-*`/`col-lg-*` 做响应式布局
- **Spacing 间距**：`m-*`/`p-*`/`gap-*`
- **Flex 工具**：`d-flex`/`justify-content-*`/`align-items-*`
- **文本工具**：`text-end`/`text-center`/`text-nowrap`/`fw-*`
- **交互底层**：Modal/Collapse/Dropdown 等 JS 组件（视觉已覆盖）

```html
<div class="row g-3 mb-4">
    <div class="col-12 col-lg-8">主内容</div>
    <div class="col-12 col-lg-4">侧边</div>
</div>

<div class="d-flex justify-content-between align-items-center">
    <span>左侧内容</span>
    <button class="btn btn-primary">按钮</button>
</div>
```

### 5.2 不适用场景（必须用项目自定义类）

| 场景 | ❌ 不要用 Bootstrap | ✅ 应该用 |
|---|---|---|
| 卡片视觉 | 原生 `.card` 浅色样式 | `.card.card-glow`（已深色化 + 毛玻璃） |
| 徽章颜色 | `bg-primary`/`bg-danger`/`bg-success` | `.badge-primary`/`.badge-rise`/`.badge-fall` |
| 表格主题 | `table-light`/`table-dark` | `.data-table` 或原生 `.table.table-hover`（已深色化） |
| 颜色文字 | `text-primary`/`text-danger`/`text-success` | `.rise`/`.fall`/`.text-rise`/`.text-fall`（Bootstrap 类语义契合可接受，但项目类优先） |
| 按钮 | 原生蓝色按钮 | `.btn.btn-primary`（已改为蓝→青渐变） |

### 5.3 响应式断点

Bootstrap 断点与本项目响应式规则一致：
- `<= 1280px`（xl 以下）：统计条 4→2 列，主从布局缩小侧栏
- `<= 1024px`（lg 以下，即 991.98px）：侧边栏收起，主从布局变单列，sticky 变 static
- `<= 640px`（md 以下，即 767.98px）：统计条 1 列，表单栅格 1 列，页头按钮可能折行

---

## 八、布局模式（SHOULD）

### 6.1 标准页面三段式

`.content-area` 内的页面内容推荐按以下顺序组织：

```
<page-header> 标题 + 副标题 + 右侧操作按钮组 </page-header>
<stat-strip>  可选：4 个统计卡片              </stat-strip>
<filter-bar>  可选：搜索 + 筛选 + 视图切换    </filter-bar>
<!-- 主体：卡片网格 / 三栏指挥台 / 主从布局 / 表格 -->
```

- 页标题 22px、字重 700；副标题 12.5px、`color: var(--text-muted)`；标题后数量徽章用 mono + `color: var(--accent-cyan)`。
- 面包屑在顶部栏内（由 topNavbar fragment 渲染），格式：`父模块 / 子模块 / 当前页`，分隔符 `/` 透明度 .5。

### 6.2 三栏指挥台（003 多因子选股中心范式）

数据密集型工作流页面推荐采用 003 的三栏指挥台布局（`220px | 1fr | 360px`，gap 20px，padding 24px）：

```
+----------+----------------------+------------------+
| 左 rail  |   中 编辑/构建画布    |   右 结果/追踪    |
| 模式切换 |                       |                  |
| 方案库   |   规则树/条件块       |   命中名单/统计   |
+----------+----------------------+------------------+
```

- 左右栏 `position: sticky; top: 84px` 常驻，主区滚动。
- `<=1280px` 时三栏收为两栏（右栏结果下沉占满），`<=860px` 时单栏堆叠。
- 主操作按钮（如"运行选股"）用 §6.2 印章式按钮。

### 6.3 主从布局（Master-Detail）

参考 factor-library 的三栏 `.md-grid`：`200px 分类 rail | 1fr 主列表 | 440px 详情面板`。
- rail 和 detail 用 `position: sticky; top: 84px`（顶部栏 68px + 间距 16px），滚动时常驻。
- 两栏编辑器布局参考 004 策略管理原型 `.editor-layout`：`1fr | 380px`（编辑主区 + 右侧摘要/JSON 预览）。
- 卡片网格：`grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px`（004 `.strategy-grid`）。

### 6.4 编辑器 Tab 式布局

参考 004 `.editor-tabs` / `.tab-btn`：
- Tab 栏：横向滚动 flex + gap:2px，padding 0 14px，底部 1px `--border-light`，背景 `rgba(15,21,32,.4)`。
- Tab 按钮：padding 12px 16，active 态文字主色 + 底部 2px `var(--gradient-primary)` 渐变条（`::after`，left/right 14px）。
- 含校验错误的 Tab 用 `--rise-light` 文字 + 红色下划条（004 `.tab-btn.error`）。
- Tab 内容 padding：22px 24px；切换面板 `fadeIn .2s`（translateY 4px→0）。
- 底部保存条（004 `.editor-actionbar`）：sticky bottom:0，毛玻璃背景 `rgba(15,21,32,.9)` + `backdrop-filter: blur(16px)`，顶部分割线。

---

## 九、交互与动效（SHOULD）

1. **Hover 上浮**：可点击卡片默认 `translateY(-3px ~ -4px)` + 边框变蓝 + 阴影增强，时长 `var(--transition-base)`（0.25s）。
2. **按钮反馈**：主按钮 hover `translateY(-1px)` + 阴影增强，active 归位，时长 `var(--transition-fast)`；印章式按钮点击 `stamping` 钤印动画。
3. **入场动画**：页面首屏元素可加 `.animate-in` + `.delay-1..6`（fadeInUp .6s）；Tab 面板切换 `fadeIn .2s`。
4. **Modal/弹出**：scaleIn 从 0.96→1 + fade in，0.2-0.25s。
5. **列表激活**：侧边栏/分类项激活态用左侧 2-3px `var(--gradient-primary)` 竖条 + 蓝青渐变底色（15%/10% 透明）（004 `.nav-item.active`、003 `.plan-card.active`）。
6. **开关/Tab**：过渡 0.15-0.2s，避免突兀切换。
7. **减少动效**：`@media (prefers-reduced-motion: reduce)` 下关闭印章动画、渐显、面板切换动画（见 003/004 原型）。
8. **禁止项**（MUST NOT）：
   - 不要引入 `bounce`/`elastic` 等夸张缓动
   - 不要给关键元素做超过 300ms 的动画
   - 不要让 hover 效果改变文档流（避免引起其他元素位移）
   - 不要写 `transition: all 0.3s`（明确指定过渡属性）

---

## 十、CSS 写法规范

### 8.1 分层原则（MUST）

CSS 按四层组织，**不要在页面 CSS 里重定义 components.css 已有的 class**：

| 层 | 文件 | 职责 |
|---|---|---|
| Design Tokens | theme.css | 只放 `:root` 变量，不动 |
| Base | theme.css | reset、body、scrollbar、选区色、工具类 |
| Components | components.css | sidebar、navbar、card、btn、form、table、badge、pagination、modal、alert、animations |
| Pages | `<page>.css` | **只放**页面专属组件（页面级布局 grid、专属模块如因子表 `.ftable`、策略卡片等） |

### 8.2 命名规范

- 使用 BEM（Block Element Modifier）简化思想：`.block` / `.block-element` / `.block-element--modifier`。
- **页面专属 class 使用前缀**避免污染全局，如因子库用 `fl-`（factor-library）前缀自定义模态类，避免与 Bootstrap `.modal` 冲突；策略管理原型用 `editor-`/`ver-`/`tl-`（timeline）前缀。
- 重点是**语义化 + 不冲突**，BEM 不强制严格双下划线/双横线。

```css
/* Block */
.index-card { ... }
/* Element */
.index-card__title { ... }
/* Modifier */
.index-card--up { ... }
```

### 8.3 选择器优先级（MUST）

- 尽量使用 class 选择器，少用 id 选择器
- 避免过深的嵌套（不超过 3 层）
- 避免使用 `!important`（覆盖 Bootstrap 默认样式时除外）

```css
/* ✅ 好的 */
.stock-item { ... }
.stock-item .name { ... }

/* ❌ 不好的 */
#stockList .table tbody tr td.stock-name span { ... }
```

### 8.4 属性书写顺序（SHOULD）

按以下顺序书写 CSS 属性：

1. 定位：`position`、`top`、`left`、`z-index`
2. 盒模型：`display`、`width`、`height`、`margin`、`padding`、`border`、`border-radius`
3. 背景：`background`、`backdrop-filter`
4. 文本：`font`、`color`、`text-align`、`line-height`
5. 其他：`transition`、`animation`、`transform`、`box-shadow`

```css
.index-card {
    position: relative;
    display: flex;
    width: 100%;
    padding: 1rem;
    border-radius: var(--radius-lg);
    background: var(--bg-card);
    backdrop-filter: var(--blur-card);
    border: 1px solid var(--border-color);
    font-size: 14px;
    color: var(--text-primary);
    transition: transform var(--transition-base), box-shadow var(--transition-base);
}
```

### 8.5 硬编码色值禁令（MUST）

除了 `white` / `#fff` / `transparent` 和 box-shadow 的 `rgba(0,0,0,.*)` 纯黑，**所有颜色必须引用 var()**：

```css
/* ✅ 正确 */
.my-card { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--accent-cyan); }

/* ❌ 错误 */
.my-card { background: #0f1520; border: 1px solid #334155; color: #06b6d4; }
```

页面专属颜色（如因子来源色）通过在页面 CSS 新增专属 token 定义，只赋单一深色值：

```css
:root {
    --src-akquant: #06b6d4;
    --src-tushare: #8b5cf6;
}
.factor-card { background: var(--bg-card); color: var(--text-primary); border: 1px solid var(--border-color); }
```

> ⚠️ 禁止重定义 `:root` 或 `[data-theme]` 中已有的基础 token（`--bg-*`/`--text-*`/`--accent-*`/`--rise-*`/`--fall-*`/`--gradient-*`/`--radius-*`/`--shadow-*`/`--font-*`/`--transition-*`/`--ambient-*`/`--chart-*`）。页面 CSS 只允许**新增**专属 token（如 `--src-*`、`--stat-accent`、`--card-accent`）。**透明度色值用 `color-mix(in srgb, var(--token) N%, transparent)`，不要写 `rgba(主题RGB, 0.x)`**——否则无法跟随主题。

### 8.6 其他 CSS 禁令（MUST NOT）

- 不要写超过 3 层的嵌套选择器
- 不要在页面 CSS 里重置全局标签样式（h1-h6 / a / button / body 等已在 base/components 层设定）
- 不要写 `transition: all ...`（明确指定过渡属性，如 `transition: transform var(--transition-base)`）
- 不要在页面 CSS 里定义新的 `[data-theme="xxx"]` 块——主题切换只在 `theme.css` 的 `[data-theme]` 中定义（见 §二）

---

## 十一、交付前自查清单

- [ ] `<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">` 已设置，**未手动设置 `data-theme` 属性**（由 head 内联脚本自动设置）
- [ ] 页面骨架使用标准模板（`app-container` / `main-content` / `content-area`），公共部分通过 `fragments/common :: head/sidebar/sidebarOverlay/topNavbar/scripts/toastContainer` 引入
- [ ] 页面专属 CSS/JS 放在 body 末尾、`scripts` fragment 之后
- [ ] 所有颜色用 `var()`，无硬编码 `#0a0e17`/`#f1f5f9` 等字面量颜色（白/透明/纯黑阴影除外）
- [ ] **透明度色值用 `color-mix(in srgb, var(--token) N%, transparent)`，无 `rgba(主题RGB, 0.x)` 硬编码**
- [ ] **未自画页面级背景**（无径向渐变 / 纯色铺底），复用全局 `body::before` 氛围光
- [ ] 涨红跌绿：正数/盈利/涨用 `.rise`/`var(--rise-light)`，负数/亏损/跌用 `.fall`/`var(--fall-light)`，未沿用海外绿涨红跌
- [ ] 数字/股票代码/百分比/金额/ID/时间戳/JSON key 使用 `font-family: var(--font-mono)`（`.font-mono` class）
- [ ] 卡片圆角 16px（`--radius-lg`），按钮/输入框 12px（`--radius-md`），图标按钮/小标签 8px（`--radius-sm`）
- [ ] 卡片带 `backdrop-filter` 毛玻璃效果（用 `var(--blur-card)`，不同主题的模糊强度已由 token 驱动）
- [ ] 主按钮用 `--gradient-primary` 渐变（各主题品牌色），自带投影与 hover 上浮；强决策动作可用印章式按钮
- [ ] 表头 uppercase + 11px + `--text-muted` + letter-spacing:.5px，未使用 `.table-light`/`.table-dark`
- [ ] 表单控件 focus 态有 `0 0 0 3px` 主色外发光（颜色随主题）
- [ ] 徽章使用 `.badge-primary`/`.badge-rise`/`.badge-fall` 等项目类（不是 Bootstrap 的 `bg-primary`）
- [ ] hover/过渡动效使用 `--transition-fast`/`--transition-base` 变量，无 `transition: all`、无夸张 bouncy 缓动
- [ ] 所有可交互元素（卡片/按钮/导航项）有明确 hover 态
- [ ] 响应式断点已处理（1280/1024/640 三档）
- [ ] **页面 CSS 未定义新的 `[data-theme="xxx"]` 块**，未重定义 `:root` 或 `[data-theme]` 基础 token
- [ ] **图表（ECharts/Lightweight Charts）颜色走 `ChartsTheme`，实例已 `ChartsTheme.register()` 注册，未在页面 JS 硬编码图表色值**
