# Settings 页面改造计划

## 目标
根据新的设计系统改造 settings.html 页面，使其与 dashboard.html 保持一致的视觉风格和布局结构。

## 改造内容

### 1. 布局结构调整
- **旧结构**: `body > d-flex > sidebar + main-wrapper > navbar + content-area`
- **新结构**: `body > app-container > sidebar + sidebarOverlay + main-content > topNavbar + content-area`
- 替换旧的 `navbar` fragment 为 `topNavbar` fragment
- 添加 `sidebarOverlay` fragment（移动端遮罩）

### 2. 页面头部改造
- 使用 `page-header` 容器包裹标题区域
- 使用 `page-title` 样式的 h1 标题（替换原有的 h5）
- 添加副标题描述文字
- 保留齿轮图标，使用 `card-title-icon` 样式风格

### 3. 设置卡片改造
- 保留两个设置卡片：数据源配置、定时任务
- 为卡片添加 `card-glow` 发光效果
- 卡片标题使用 `card-title-icon` 图标样式
  - 数据源配置：使用蓝色/青色图标（bi-database）
  - 定时任务：使用紫色图标（bi-clock-history）
- 保留原有的表格内容和所有配置项说明
- 使用 `row g-4` 替换 `row g-3` 以符合新设计间距

### 4. 入场动画
- 为第一个卡片添加 `animate-in delay-1`
- 为第二个卡片添加 `animate-in delay-2`

### 5. 保留内容
- 所有 Thymeleaf 模板变量（pageTitle 等）
- 所有配置项表格内容（数据库地址、用户、API Key、定时任务等）
- scripts fragment 引用
- 页面的功能逻辑不变

## 文件修改
- **修改文件**: `src/main/resources/templates/pages/settings.html`
- 完整重写该文件，参考 dashboard.html 的结构模式
