# 前端 UI 整体改造计划

> **项目**: StockPulse 量化交易分析平台  
> **版本**: V2.0 视觉升级  
> **方向**: 专业交易终端 × 现代金融科技美学

---

## 1. 现状分析

### 1.1 当前技术栈
- **模板引擎**: Thymeleaf (Spring Boot)
- **CSS 框架**: Bootstrap 5.3.3
- **图标**: Bootstrap Icons 1.11.3
- **图表**: ECharts 5.5.0 + lightweight-charts 4.2.1
- **JS**: 原生 JavaScript (无框架)

### 1.2 当前页面清单 (共 8 个页面)

| 页面 | 文件路径 | 类型 |
|------|---------|------|
| 仪表盘 | `pages/dashboard.html` | 主业务页 |
| 行情中心 | `pages/stock-list.html` | 主业务页 |
| 自选股 | `pages/watchlist.html` | 主业务页 |
| 用户管理 | `pages/user-management.html` | 管理页 |
| 系统设置 | `pages/settings.html` | 管理页 |
| 登录页 | `pages/login.html` | 认证页 |
| 2FA 登录 | `pages/login-2fa.html` | 认证页 |
| TOTP 绑定 | `pages/setup-totp.html` | 认证页 |

### 1.3 公共组件
- **布局碎片**: `fragments/common.html` (head / sidebar / navbar / scripts)
- **全局样式**: `static/css/custom.css` (~326 行)
- **全局 JS**: `static/js/common.js` (StockApp 工具对象)
- **搜索建议**: `static/js/search-suggest.js`
- **仪表盘 JS**: `static/js/dashboard.js`
- **用户管理 JS**: `static/js/user-management.js`

### 1.4 存在的问题
1. 风格偏通用后台管理系统，缺乏金融产品专业感
2. 浅色主题单调，视觉层次弱
3. 动效交互少，体验偏平
4. 组件样式散落在 Bootstrap 默认值上，定制化不足
5. 无主题切换机制

---

## 2. 改造目标

### 2.1 核心目标
1. **视觉升级**: 从通用后台 → 专业金融交易终端风格
2. **双主题支持**: 深色 / 浅色一键切换，默认深色
3. **风格统一**: 建立设计系统(Design System)，方便后续扩展
4. **体验提升**: 增加动效、微交互、视觉层次感

### 2.2 设计方向
- **主基调**: 深色主题(默认) + 玻璃拟态 + 科技渐变
- **字体**: Space Grotesk (标题) + Noto Sans SC (正文) + JetBrains Mono (数据)
- **主色调**: 蓝紫青渐变 (`#3b82f6` → `#06b6d4` → `#8b5cf6`)
- **涨跌色**: 红涨绿跌 (柔和版: `#ef4444` / `#22c55e`)
- **卡片**: 半透明毛玻璃 + 细边框 + 悬浮发光效果

---

## 3. 改造范围与文件清单

### 3.1 新增文件

| 文件 | 说明 |
|------|------|
| `static/css/theme.css` | 主题变量系统 (深色/浅色 CSS 变量) |
| `static/css/components.css` | 通用组件样式 (卡片/按钮/表格/表单等) |
| `static/js/theme.js` | 主题切换管理器 + localStorage 持久化 |

### 3.2 修改文件

| 文件 | 修改内容 |
|------|---------|
| `fragments/common.html` | 重写 sidebar、navbar、引入新字体/新 CSS/新 JS |
| `static/css/custom.css` | 大幅重构，迁移到新的主题系统 |
| `static/js/common.js` | 新增主题切换相关功能，优化 toast/modal 样式 |
| `pages/dashboard.html` | 按新设计全面重构 |
| `pages/stock-list.html` | 按新设计全面重构 |
| `pages/watchlist.html` | 按新设计全面重构 |
| `pages/user-management.html` | 按新设计全面重构 |
| `pages/settings.html` | 按新设计全面重构 |
| `pages/login.html` | 按新设计全面重构 |
| `pages/login-2fa.html` | 按新设计全面重构 |
| `pages/setup-totp.html` | 按新设计全面重构 |

---

## 4. 详细实施方案

### 4.1 阶段一: 设计系统基础 (主题系统 + 通用组件)

#### 4.1.1 主题变量系统 (`theme.css`)
- 定义两套 CSS 变量: `.dark` (默认) 和 `.light`
- 变量覆盖: 背景色、文字色、边框色、卡片色、主色调、涨跌色、阴影等
- 通过 `<html>` 标签的 `data-theme` 属性切换

```css
/* 深色主题 (默认) */
:root, [data-theme="dark"] {
    --bg-primary: #0a0e17;
    --bg-secondary: #0f1520;
    --bg-card: rgba(21, 29, 43, 0.6);
    --text-primary: #f1f5f9;
    --border-color: rgba(71, 85, 105, 0.3);
    --accent-blue: #3b82f6;
    --rise-color: #ef4444;
    --fall-color: #22c55e;
    /* ... 更多变量 */
}

/* 浅色主题 */
[data-theme="light"] {
    --bg-primary: #f8fafc;
    --bg-secondary: #ffffff;
    --bg-card: rgba(255, 255, 255, 0.9);
    --text-primary: #0f172a;
    --border-color: rgba(226, 232, 240, 0.8);
    /* ... 对应浅色变量 */
}
```

#### 4.1.2 通用组件样式 (`components.css`)
按类别组织，所有组件都基于 CSS 变量:
- **卡片组件**: `.card` / `.card-header` / `.card-body` / `.card-footer`
- **按钮组件**: `.btn-primary` / `.btn-secondary` / `.btn-outline` / `.btn-icon`
- **表格组件**: `.data-table` / 表头 / 行 hover / 斑马纹
- **表单组件**: input / select / textarea / label / input-group
- **徽章标签**: `.badge` / `.tag`
- **分页组件**: `.pagination` 定制
- **模态框**: `.modal-content` / `.modal-header` / `.modal-footer`
- **Toast 通知**: 定制样式
- **侧边栏**: sidebar 完整样式
- **顶部导航**: navbar 完整样式
- **动效工具类**: 淡入、上浮、脉冲等

#### 4.1.3 主题切换管理器 (`theme.js`)
```javascript
const ThemeManager = {
    // 初始化：从 localStorage 读取或检测系统偏好
    init() {},
    
    // 切换主题
    toggle() {},
    
    // 设置指定主题
    setTheme(theme) {},
    
    // 获取当前主题
    getCurrentTheme() {},
    
    // 监听主题变化 (供 ECharts 等组件重绘用)
    onChange(callback) {}
};
```

### 4.2 阶段二: 公共布局重构

#### 4.2.1 侧边栏 (Sidebar) 重设计
- Logo 区域: 渐变图标 + 渐变文字 + 副标题
- 菜单分组: 按「市场概览 / 量化分析 / 系统」分组，组名大写小字
- 选中样式: 左侧渐变指示条 + 渐变背景高亮
- 悬浮效果: 轻微右移 + 背景色变化
- 底部用户卡片: 头像渐变 + 用户信息

#### 4.2.2 顶部导航 (Navbar) 重设计
- 左侧: 页面标题 + 交易状态指示灯(呼吸动画)
- 中间: 搜索框(玻璃拟态风格)
- 右侧: 主题切换按钮 + 通知按钮 + 全屏按钮 + 用户下拉
- 毛玻璃背景 + 底部细边框

#### 4.2.3 主题切换按钮
- 点击切换深/浅色
- 图标: 太阳/月亮
- 带过渡动画
- 状态持久化到 localStorage

#### 4.2.4 字体引入
- Google Fonts: Space Grotesk, Noto Sans SC, JetBrains Mono
- font-family  fallback 链完整

### 4.3 阶段三: 业务页面改造

#### 4.3.1 仪表盘 (dashboard.html)
- **指数卡片区**: 4 列网格，每张卡片含: 指数名/涨跌幅徽章/价格/迷你走势图/涨跌额&成交额
- **K线图 + 自选股**: 2:1 布局，K线图含股票信息头+周期切换+底部指标栏
- **排行榜三列**: 涨幅/跌幅/成交额，前三名金银铜徽章
- **底部双图表**: 自选股涨跌幅对比(横向柱状图) + 行业分布(环形图)
- 入场动画: 错落淡入上浮

#### 4.3.2 行情中心 (stock-list.html)
- 搜索筛选区: 玻璃卡片样式，表单元素统一新风格
- 数据表格: 新设计的表格样式，斑马纹/悬浮高亮
- 分页组件: 底部卡片式分页
- 操作按钮: 图标式加自选按钮

#### 4.3.3 自选股 (watchlist.html)
- 页面标题 + 刷新按钮
- 表格数据展示，新表格样式
- 移除按钮风格统一
- 空状态样式优化

#### 4.3.4 用户管理 (user-management.html)
- 搜索区 + 新增用户按钮
- 用户表格: 头像/用户名/邮箱/角色/2FA状态/状态/操作
- 新增用户模态框: 新风格表单
- TOTP 绑定模态框: 新风格

#### 4.3.5 系统设置 (settings.html)
- 双列卡片布局
- 数据源配置卡片
- 定时任务配置卡片
- 表格展示配置项

#### 4.3.6 登录页 (login.html)
- 全屏渐变背景 + 装饰性光晕
- 居中登录卡片: 毛玻璃 + 边框光效
- Logo 区域: 渐变图标 + 标题
- 表单输入: 新风格 input-group
- 登录按钮: 渐变按钮 + hover 动效

#### 4.3.7 2FA 登录 / TOTP 绑定
- 与登录页风格统一
- 二维码展示区域优化
- 步骤指引样式

### 4.4 阶段四: ECharts 主题适配

#### 4.4.1 图表主题切换
- 封装 ECharts 初始化函数，自动读取当前主题
- 深色/浅色两套图表配色方案
- 监听主题变化，自动重绘所有图表

#### 4.4.2 涉及图表
- K线图 (蜡烛图 + 成交量)
- 迷你走势图 (sparkline)
- 横向柱状图 (涨跌幅对比)
- 环形图 (行业分布)
- 后续新增图表自动适配

### 4.5 阶段五: 交互与动效

#### 4.5.1 页面入场动效
- 卡片错落淡入上浮 (staggered fade-in-up)
- 使用 CSS animation + animation-delay
- 工具类: `.animate-in` / `.delay-1~5`

#### 4.5.2 微交互
- 按钮 hover: 轻微放大 + 阴影增强
- 卡片 hover: 上浮 + 边框发光 + 顶部光边
- 链接/菜单项 hover: 位移 + 变色
- 输入框 focus: 边框发光 + 外发光

#### 4.5.3 状态指示
- 交易状态灯: 呼吸脉冲动画
- 加载状态: 骨架屏或旋转动画
- 数据更新: 数字闪烁过渡

---

## 5. 实施顺序

```
阶段一: 设计系统基础
  ├── theme.css (主题变量)
  ├── components.css (通用组件)
  └── theme.js (主题切换)

阶段二: 公共布局重构
  ├── fragments/common.html (sidebar + navbar)
  ├── custom.css 清理重构
  └── common.js 增强

阶段三: 业务页面改造 (按优先级)
  ├── 1. dashboard.html (主页面，重点改造)
  ├── 2. stock-list.html (高频使用)
  ├── 3. watchlist.html (高频使用)
  ├── 4. login.html (门面)
  ├── 5. user-management.html
  ├── 6. settings.html
  ├── 7. login-2fa.html
  └── 8. setup-totp.html

阶段四: ECharts 主题适配
  └── 图表主题切换 + 深色/浅色配色

阶段五: 交互优化与细节打磨
  ├── 入场动画
  ├── 微交互
  └── 细节微调
```

---

## 6. 后续扩展规范

为了方便后续新增页面保持风格统一，建立以下规范：

### 6.1 新页面模板
```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/common :: head(${pageTitle})}"></head>
<body>
<div class="app-container">
    <nav th:replace="~{fragments/common :: sidebar}"></nav>
    <div class="main-content">
        <header th:replace="~{fragments/common :: navbar}"></header>
        <main class="content-area">
            <!-- 页面标题 -->
            <div class="page-header">
                <h1 class="page-title">页面标题</h1>
                <div class="page-actions">
                    <button class="btn btn-primary">操作按钮</button>
                </div>
            </div>
            
            <!-- 页面内容 -->
            <div class="card">
                <div class="card-header">
                    <div class="card-title">
                        <div class="card-title-icon"><i class="bi bi-xxx"></i></div>
                        卡片标题
                    </div>
                </div>
                <div class="card-body">
                    <!-- 内容 -->
                </div>
            </div>
        </main>
    </div>
</div>
<div th:replace="~{fragments/common :: scripts}"></div>
<script>
    // 页面逻辑
</script>
</body>
</html>
```

### 6.2 样式扩展规范
- 新增组件样式写入 `components.css`，不写在页面内联
- 颜色/间距/圆角等统一使用 CSS 变量
- 新页面 JS 单独建文件，如 `xxx.js`
- 通用工具函数往 `StockApp` 对象上加

### 6.3 组件命名约定
- 布局类: `.app-container` / `.main-content` / `.content-area` / `.page-header`
- 卡片类: `.card` / `.card-header` / `.card-title` / `.card-body` / `.card-footer`
- 按钮类: `.btn` / `.btn-primary` / `.btn-secondary` / `.btn-icon` / `.btn-sm`
- 表格类: `.data-table` / 配合 Bootstrap 的 `.table` 使用
- 状态类: `.rise` / `.fall` (涨跌色)

---

## 7. 风险与注意事项

### 7.1 技术风险
| 风险 | 影响 | 应对方案 |
|------|------|---------|
| Bootstrap 与新样式冲突 | 部分组件样式异常 | 使用更高权重的选择器覆盖，必要时用 `:where()` 降低 Bootstrap 权重 |
| ECharts 主题切换闪烁 | 视觉体验差 | 使用 setOption 无缝切换，过渡动画平滑 |
| 浅色主题适配遗漏 | 部分组件在浅色下看不清 | 逐一页面验证，关键色统一走 CSS 变量 |

### 7.2 兼容性
- 现代浏览器 (Chrome/Edge/Firefox/Safari 最新版)
- `backdrop-filter` 需考虑降级方案 (纯色背景 fallback)
- CSS 变量全面支持，无需 polyfill

### 7.3 性能
- 字体使用 `font-display: swap` 避免阻塞渲染
- 动效用 CSS transform/opacity，保证 GPU 加速
- 玻璃拟态控制使用数量，避免过度模糊影响性能

---

## 8. 验证标准

1. **视觉一致性**: 所有页面风格统一，组件样式一致
2. **主题切换**: 深色/浅色切换流畅无闪烁，刷新后保持
3. **功能完整**: 所有原有功能正常工作，无样式导致的功能异常
4. **响应式**: 主要分辨率下布局正常 (1920/1440/1366/移动端)
5. **可扩展性**: 新页面能快速套用模板，风格自动统一

---

## 9. 交付物

- 完整的主题系统 (theme.css + components.css + theme.js)
- 8 个页面全部重构完成
- 公共布局 (sidebar/navbar) 重构完成
- ECharts 深色/浅色主题适配
- 后续扩展规范文档
