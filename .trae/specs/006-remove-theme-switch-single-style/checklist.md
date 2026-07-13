# Checklist

## 主题切换移除
- [x] `static/js/theme.js` 已删除
- [x] `fragments/common.html` 不再引用 `theme.js`，顶部栏无 `#themeToggleBtn`
- [x] `login.html` / `login-2fa.html` / `setup-totp.html` 无 `data-theme` 属性、无主题切换按钮、无 `updateLoginThemeIcon()`、无残留 `.theme-toggle-btn` CSS
- [x] 所有 `<html>` 标签无 `data-theme` 属性
- [x] `dashboard.js` 无 `themechange` 监听 / `onThemeChange` / `updateChartsTheme` / `updateKlineTheme`
- [x] `charts-theme.js` 的 `getEChartsTheme()` / `getKlineTheme()` 固定返回深色配置，不读 `data-theme`
- [x] 全仓库 grep（templates/ + static/）对 `data-theme`、`toggleTheme`、`ThemeManager`、`themechange`、`stockpulse_theme` 零命中（唯一残留为 `dashboard.html` 引用 `/js/charts-theme.js` 脚本，是图表配置模块非主题切换）

## CSS 收敛
- [x] `theme.css` 仅有单一 `:root` token 块（原 dark 值），无 `[data-theme="light"]` 块
- [x] `theme.css` `body::before` 不再按 theme 分支，氛围光直接生效
- [x] `theme.css` 无 `html.theme-transition` 规则、无 body 主题切换过渡
- [x] `components.css` `.btn-close` 无 `[data-theme="dark"]` 前缀
- [x] `factor-library.css` token 定义为 `:root`（无 `data-theme` 选择器）

## 知识库
- [x] `01-frontend.md` 已重写，以 003/004 原型为视觉标杆
- [x] 知识库无 dark/light 双主题、`data-theme`、light 一等公民、主题切换相关章节（保留"历史背景：双主题已移除"说明）
- [x] 知识库自查清单移除"主题切换验证"项，保留无硬编码色值/涨红跌绿/mono 字体等

## 视觉风格统一
- [x] 所有页面背景走全局 `body::before` 氛围光，无页面自画径向渐变
- [x] 所有颜色用 `var()`，无硬编码 `#0a0e17`/`#f1f5f9` 等字面量（白/透明/纯黑阴影例外）
- [x] 数字/代码/ID/时间戳/JSON key 使用 `var(--font-mono)` / `.font-mono`
- [x] 涨红跌绿（正/盈利用 `.rise`，负/亏损用 `.fall`）
- [x] 卡片 16px 圆角 + `backdrop-filter: blur(10px)` 毛玻璃
- [x] 主按钮用蓝→青 `--gradient-primary`

## 页面对齐原型
- [x] 002 因子库：主从布局、`.stat-strip`、来源彩色标签、JSON 高亮与原型一致（新增毛玻璃 backdrop-filter、stat-accent 对齐来源色语义、slider hover）
- [x] 003 选股中心：三栏指挥台、印章运行按钮（`.seal-btn` + `sw-stamp` 钤印动画）、条件树、结果面板与原型一致
- [x] 004 策略管理：列表卡片网格、编辑器 Tab、版本时间线与原型一致（时间线渐变轨道 + 发光圆点、版本摘要卡、变更列表、错误 tab 红色下划线、JSON 高亮）
- [x] dashboard / watchlist / stock-list / settings / user-management 深色风格统一、无主题残留、无破绽

## 回归
- [x] 登录流（login / 2fa / totp）深色风格正常、无 JS 报错（静态校验：data-theme/切换按钮/脚本已清零，登录/2FA/TOTP 业务逻辑保留）
- [x] 主应用各页深色风格统一（grep 清零、各页共用 theme.css 单一深色 token）
- [x] 行情/K 线图表正常渲染（深色调色板，初始化路径完好，A 股红涨绿跌色保留）
- [ ] 浏览器实跑目测（需启动 Spring Boot 服务后人工确认，静态层面无法执行）
