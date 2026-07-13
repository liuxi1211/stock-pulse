# Tasks

- [x] Task 1: 重写前端知识库 `01-frontend.md`，锁定单一深色风格
  - [x] SubTask 1.1: 以 003/004 原型为标杆提炼视觉风格（玻璃态卡片、蓝→青渐变、径向氛围光、mono 字体、涨红跌绿、印章按钮、三栏/编辑器布局）
  - [x] SubTask 1.2: 删除全部主题切换/dark-light/`data-theme`/light 一等公民相关章节与自查项
  - [x] SubTask 1.3: 更新交付前自查清单（移除主题切换验证项，保留"无硬编码色值""涨红跌绿""mono 字体"等）

- [x] Task 2: 收敛 `theme.css` 为单一深色主题
  - [x] SubTask 2.1: 将 `:root, [data-theme="dark"]` 合并为单一 `:root`（保留原 dark 值）
  - [x] SubTask 2.2: 删除 `[data-theme="light"]` token 块
  - [x] SubTask 2.3: 把 `[data-theme="dark"] body::before` 与 `[data-theme="light"] body::before` 合并为单一 `body::before`（取深色氛围光参数）
  - [x] SubTask 2.4: 删除 `html.theme-transition` 过渡类规则
  - [x] SubTask 2.5: 移除 `body` 上的 `transition: background-color ...` 主题切换过渡

- [x] Task 3: 清理 `components.css` 中的主题分支
  - [x] SubTask 3.1: `[data-theme="dark"] .btn-close` → `.btn-close`（去前缀，值不变）

- [x] Task 4: 简化 `factor-library.css` 页面 token 定义
  - [x] SubTask 4.1: `:root, [data-theme="dark"]` → `:root`

- [x] Task 5: 删除 `theme.js` 并清理引用
  - [x] SubTask 5.1: 删除 `static/js/theme.js` 文件
  - [x] SubTask 5.2: `fragments/common.html` 移除 `<script th:src="@{/js/theme.js}">` 引用

- [x] Task 6: 移除模板中的主题切换 UI 与脚本
  - [x] SubTask 6.1: `fragments/common.html` 移除顶部栏 `#themeToggleBtn` 按钮
  - [x] SubTask 6.2: `login.html` 移除 `data-theme="dark"`、主题切换按钮、`updateLoginThemeIcon()` 及其 DOMContentLoaded 调用
  - [x] SubTask 6.3: `login-2fa.html` 同上清理
  - [x] SubTask 6.4: `setup-totp.html` 同上清理
  - [x] SubTask 6.5: `<html>` 标签移除 `data-theme` 属性（保留 lang/xmlns:th）
  - [x] SubTask 6.6: 清理三个登录页残留的 `.theme-toggle-btn` dead CSS

- [x] Task 7: 固定图表为深色主题
  - [x] SubTask 7.1: `charts-theme.js` 的 `getEChartsTheme()` / `getKlineTheme()` 不再读 `data-theme`，直接返回深色配置
  - [x] SubTask 7.2: `dashboard.js` 移除 `themechange` 监听、`onThemeChange()`、`updateChartsTheme()` 重绘；图表初始化即用深色

- [x] Task 8: 003 多因子选股中心对齐原型
  - [x] SubTask 8.1: 按 003 原型调整 `screener.html` + 其 css/js 的三栏布局、印章式运行按钮、条件树、结果面板视觉
  - [x] SubTask 8.2: 复用全局 token 与组件类，禁止硬编码色值与主题分支

- [x] Task 9: 004 策略管理对齐原型
  - [x] SubTask 9.1: 按 004 原型（list / editor / versions）实现或调整策略管理页面布局、卡片网格、编辑器 Tab、版本时间线
  - [x] SubTask 9.2: 确保风格与 003/002 统一（玻璃态卡片、蓝→青渐变、mono 字体）

- [x] Task 10: 002 因子库对齐原型
  - [x] SubTask 10.1: 对照 `002-standard-factor-library/prototype/factor-library-prototype.html` 校准 `factor-library.html` + `factor-library.css` 的布局与组件密度
  - [x] SubTask 10.2: 保持主从布局、`.stat-strip`、来源彩色标签、JSON 高亮等标志性元素

- [x] Task 11: 其余页面风格一致性收敛
  - [x] SubTask 11.1: dashboard / watchlist / stock-list / settings / user-management 移除主题相关残留，确认深色风格无破绽（无浅色硬编码、无 theme 残留）

- [x] Task 12: 全局回归校验
  - [x] SubTask 12.1: 全仓库 grep 确认 `data-theme` / `toggleTheme` / `ThemeManager` / `themechange` / `stockpulse_theme` 在运行时代码（templates/static）中已清零
  - [x] SubTask 12.2: 浏览器目测登录流 + 主应用各页深色风格统一、图表正常（静态校验：grep 清零、图表初始化路径完好、功能 hook 保留）

# Task Dependencies

- Task 2 → Task 3/4（CSS 收敛先于页面级清理，避免过渡态样式错乱）
- Task 5 → Task 6（删 theme.js 前先移除 UI 引用，避免控制台 404 与报错）
- Task 7 依赖 Task 5（theme.js 删除后 dashboard.js 不再有 themechange 事件源）
- Task 1（知识库重写）与 Task 2-7（代码清理）可并行
- Task 8/9/10（页面调整）依赖 Task 1-7 完成（风格基线就绪后再对齐原型）
- Task 11/12 依赖 Task 8/9/10
