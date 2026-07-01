# watchlist.html 页面改造计划

## 改造目标
根据新的 StockPulse 设计系统，将自选股页面从旧的 Bootstrap 布局迁移到新的主题化组件系统。

## 改造内容

### 1. 布局结构改造
- **旧结构**: `body > .d-flex > nav + .main-wrapper > header + main.content-area`
- **新结构**: `body > .app-container > nav + .sidebar-overlay + .main-content > header.top-navbar + main.content-area`
- 参考 dashboard.html 的布局结构

### 2. 页面头部改造
- **旧**: `.d-flex.justify-content-between.align-items-center.mb-3 > h5 + button`
- **新**: `.page-header > div > h1.page-title + 副标题 + .page-actions > button`
- 添加黄色星星图标（card-title-icon yellow 风格）
- 刷新按钮移到 page-actions 中

### 3. 卡片和表格改造
- 外层卡片添加 `card-glow` 类
- 添加 `card-header`，包含带图标的标题
- 表格使用新的主题样式（已通过 components.css 覆盖 Bootstrap table）
- 保留原有表格列结构和数据渲染逻辑

### 4. 入场动画
- 页面头部: `animate-in delay-1`
- 卡片容器: `animate-in delay-2`

### 5. 保留内容
- 所有 Thymeleaf 模板变量（pageTitle 等）
- 所有 JavaScript 函数（refreshWatchlist、removeStock）
- 所有 API 调用逻辑
- Toast 容器
- scripts fragment 引用

## 具体变更清单

### 变更文件
- `src/main/resources/templates/pages/watchlist.html`

### 结构变更点
1. `body` 内第一层改为 `.app-container`
2. sidebar 后添加 `sidebarOverlay` fragment
3. `.main-wrapper` 改为 `.main-content`
4. navbar fragment 从 `navbar` 改为 `topNavbar`
5. 顶部操作栏改为 `.page-header` + `.page-title`
6. 卡片添加 `card-glow` 和 `card-header`
7. 添加 `animate-in` 动画类

### 样式类映射
| 旧类名 | 新类名 |
|--------|--------|
| d-flex (主容器) | app-container |
| main-wrapper | main-content |
| (无 page-header) | page-header |
| h5.mb-0 | h1.page-title |
| btn-outline-secondary | btn-secondary |
| card | card card-glow |
| (无 card-header) | card-header + card-title + card-title-icon yellow |

## 注意事项
- 保留所有 Thymeleaf 的 `th:` 属性和表达式
- 保留所有 JavaScript 函数和逻辑
- 不修改 API 调用地址和数据结构
- 确保表格的交互功能正常
