# stock-list.html 页面改造计划

## 改造目标
根据 StockPulse 新设计系统，改造 stock-list.html 页面，使其与 dashboard.html 的设计风格保持一致。

## 改造内容

### 1. 布局结构改造
- **原结构**: `d-flex` > `sidebar` + `main-wrapper` > `navbar` + `content-area`
- **新结构**: `app-container` > `sidebar` + `main-content` > `topNavbar` + `content-area`
- 新增 `sidebarOverlay` 片段（移动端遮罩）
- 将 `navbar` 片段替换为 `topNavbar` 片段

### 2. 页面头部改造
- 新增 `page-header` 容器
- 添加 `page-title` 标题（行情中心）
- 添加副标题描述文字
- 右侧添加 `page-actions` 操作按钮区域（刷新、导出等）

### 3. 筛选区域改造
- 使用 `card card-glow` 包裹筛选表单
- 添加 `card-header`，包含标题图标和标题文字
- 表单样式使用新设计系统的表单样式
- 添加 `animate-in delay-1` 入场动画

### 4. 表格区域改造
- 使用 `card card-glow` 包裹表格
- 添加 `card-header`，包含标题图标和标题文字
- 表格保持原有功能（搜索、分页、添加自选股等）
- 分页区域移至 `card-footer`
- 添加 `animate-in delay-2` 入场动画

### 5. Toast 容器
- 使用公共模板中的 `toastContainer` 片段替换原有的 toast 容器

### 6. 保留内容
- 所有原有的 JavaScript 功能逻辑
- Thymeleaf 模板变量和表达式
- 搜索、分页、表格渲染等功能
- `pageTitle` 变量传递

## 实施步骤
1. 重写 stock-list.html 整体布局结构
2. 添加 page-header 页面头部
3. 改造筛选区域卡片样式
4. 改造表格区域卡片样式
5. 验证所有功能逻辑保持不变
