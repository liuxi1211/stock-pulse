# 移除主题切换 & 锁定单一深色风格 Spec

## Why

支持 dark/light 双主题切换导致前端设计极其繁琐（每个颜色都要维护两套、图表要监听重绘、页面级 token 重定义会击穿主题），却无法带来更好的页面美感。用户希望全系统统一为**一种**风格，把精力集中在"好看"本身。003 多因子选股中心、004 策略管理两份原型图正是用户满意的视觉标杆，应据此提炼风格并写入知识库 `01-frontend.md`，然后据此调整 002 因子库 / 003 选股中心 / 004 策略管理 三个页面，其余页面保持风格一致即可。

## What Changes

- **BREAKING** 移除主题切换功能：删除 `theme.js`（ThemeManager / 全局 `toggleTheme` / `themechange` 事件 / `stockpulse_theme` localStorage）、`data-theme` 属性切换机制、`[data-theme="light"]` 浅色 token 块、`html.theme-transition` 过渡类、所有页面/模板中的主题切换按钮与本地图标同步脚本。
- `theme.css` 收敛为单一深色主题：保留 `:root` 一套 token（取自原 dark 值），删除 `[data-theme="light"]` 与 `[data-theme="..."] body::before` 的多版本，`body::before` 径向氛围光直接作用于 `body`（不再按 theme 分支）。
- `components.css` 中 `[data-theme="dark"] .btn-close` 改为无前缀的 `.btn-close` 规则。
- `factor-library.css` 的 `:root, [data-theme="dark"]` 选择器简化为 `:root`。
- `fragments/common.html`：移除 `<html data-theme="dark">`、移除顶部栏主题切换按钮、移除 theme.js 引用。
- `login.html` / `login-2fa.html` / `setup-totp.html`：移除 `data-theme` 属性、主题切换按钮、`updateLoginThemeIcon()` 本地脚本。
- `dashboard.js` / `charts-theme.js`：图表主题固定为深色，移除 `themechange` 监听与 `data-theme` 读取分支，`getEChartsTheme()` / `getKlineTheme()` 直接返回深色调色板。
- 重写知识库 `.trae/rules/stock-watcher/frontend/01-frontend.md`：以 003/004 原型为视觉标杆，提炼单一深色风格（玻璃态卡片、蓝→青主渐变、径向氛围光、mono 字体、涨红跌绿、印章按钮等标志性元素），删除所有与"双主题/主题切换/data-theme/light 一等公民"相关的章节与自查项。
- 调整 002 因子库 / 003 选股中心 / 004 策略管理三个页面的展示效果，使其与各自原型图的风格、布局、组件密度对齐；其他页面（dashboard / watchlist / stock-list / settings / user-management / login 流）仅做"移除主题相关代码 + 保持视觉一致"的轻量收敛，不重写布局。

## Impact

- Affected specs: 无（本仓库 `.trae/specs` 下无前端主题相关既有 spec；002/003/004 的 prototype 目录是设计参考，不受代码改动影响）。
- Affected code:
  - `stock-watcher/src/main/resources/static/css/theme.css`（核心收敛）
  - `stock-watcher/src/main/resources/static/css/components.css`（`.btn-close` 去前缀）
  - `stock-watcher/src/main/resources/static/css/factor-library.css`（`:root` 简化）
  - `stock-watcher/src/main/resources/static/js/theme.js`（删除）
  - `stock-watcher/src/main/resources/static/js/charts-theme.js`（固定深色）
  - `stock-watcher/src/main/resources/static/js/dashboard.js`（去 `themechange`）
  - `stock-watcher/src/main/resources/templates/fragments/common.html`（去主题切换按钮 + theme.js 引用）
  - `stock-watcher/src/main/resources/templates/pages/login.html`、`login-2fa.html`、`setup-totp.html`（去切换按钮/脚本）
  - `stock-watcher/src/main/resources/templates/pages/factor-library.html` + `factor-library.css`（按 002 原型调整）
  - `stock-watcher/src/main/resources/templates/pages/screener.html` + 对应 css/js（按 003 原型调整）
  - 策略管理页面（按 004 原型调整；若尚无对应模板则按统一风格新建）
  - 知识库 `.trae/rules/stock-watcher/frontend/01-frontend.md`（重写）
- 运行时影响：用户 localStorage 中残留的 `stockpulse_theme` key 不再被读取，无害；已部署页面将统一呈现深色风格。

## ADDED Requirements

### Requirement: 单一深色视觉风格
系统 SHALL 只保留一种视觉风格（深色科技金融终端风），不再支持主题切换。所有颜色 token 在 `theme.css` 的 `:root` 中定义唯一一组值；任何页面、组件、脚本都 MUST NOT 引入 light 主题分支或运行时切换机制。

#### Scenario: 移除主题切换入口
- **WHEN** 用户在任意页面（含登录流程）浏览
- **THEN** 顶部栏与登录页不再出现主题切换按钮，页面始终呈现深色风格

#### Scenario: CSS 无浅色分支
- **WHEN** 在仓库内搜索 `[data-theme="light"]` / `data-theme=` / `toggleTheme` / `ThemeManager` / `themechange` / `stockpulse_theme`
- **THEN** 除知识库中作为"已移除历史"说明外，运行时代码（templates/static）中无任何匹配

### Requirement: 视觉风格以 003/004 原型为标杆
`01-frontend.md` 知识库 SHALL 以 003 多因子选股中心、004 策略管理两份原型为权威视觉参考，提炼并固化：深色背景 + 径向氛围光、玻璃态毛玻璃卡片（`backdrop-filter: blur(10px)` + 半透明背景 + 1px 边框 + 16px 圆角）、蓝→青主渐变（`#3b82f6 → #06b6d4`）作为品牌色、卡片顶部光带、JetBrains Mono 用于所有数字/代码/ID、A 股涨红跌绿、统计条 `.stat-strip`、印章式主操作按钮、三栏指挥台/编辑器 Tab 等布局模式、hover 微动效。

#### Scenario: 知识库成为单一权威
- **WHEN** AI 或开发者查阅前端规范
- **THEN** `01-frontend.md` 提供完整的单一深色风格设计系统（tokens/组件/布局/动效/自查清单），且不含任何主题切换相关内容

### Requirement: 三个核心页面对齐原型
002 因子库、003 选股中心、004 策略管理三个页面 SHALL 按各自原型图的布局、组件、信息密度实现（在已有 Thymeleaf 页面基础上调整），保持与原型一致的视觉气质。

#### Scenario: 003 选股中心
- **WHEN** 用户进入多因子选股中心
- **THEN** 页面呈现原型定义的三栏布局（左侧方案/模式、中间规则构建画布、右侧结果追踪）、印章式"运行选股"按钮、条件树/排序打分/结果表格组件，风格与 003 原型一致

## MODIFIED Requirements

### Requirement: 图表主题
`charts-theme.js` 与依赖它的 `dashboard.js` SHALL 固定使用深色调色板。`getEChartsTheme()` / `getKlineTheme()` 直接返回深色配置，不再读取 `data-theme`；`dashboard.js` 移除 `themechange` 监听与 `updateChartsTheme()` 重绘逻辑，图表在初始化时即定型为深色。

## REMOVED Requirements

### Requirement: dark/light 双主题一等公民
**Reason**: 主题切换使设计成本翻倍且无法提升美感，用户决定全系统统一单一深色风格。
**Migration**: 删除 `theme.js` 与所有切换入口；`theme.css` 删除 `[data-theme="light"]` 块，token 收敛到 `:root`；localStorage `stockpulse_theme` 残留值无害、可忽略；ECharts/lightweight-charts 固定深色调色板。
