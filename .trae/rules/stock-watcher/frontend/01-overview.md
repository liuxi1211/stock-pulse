---
alwaysApply: true
description: "StockPulse 前端开发总览——新页面开发先看本文，了解文档体系和快速起步方法。包含：4 份前端文档索引、三主题系统说明、核心设计原则、新页面 5 步起步法。"
---

# StockPulse 前端开发总览

> **面向 AI & 开发者**：所有 stock-watcher 前端页面（Thymeleaf 模板 + CSS/JS）的开发总入口。新页面开发先读本文，知道"该看哪份文档、从哪里起步"。

---

## 一、文档体系（9 份，分三层）

### 第一层：基础（每次写页面都用）

| # | 文档 | 内容 | 什么时候看 |
|---|---|---|---|
| 01 | **[01-overview.md](01-overview.md)** | 总览 + 索引 + 新页面 5 步起步法 | 每次新任务先看 |
| 02 | **[02-design-tokens.md](02-design-tokens.md)** | 三主题系统 + 全部 Design Tokens（颜色/字体/圆角/阴影/动效） | 选颜色、调样式、主题适配时 |
| 03 | **[03-components.md](03-components.md)** | 组件库手册（按钮/卡片/表单/表格/徽章/统计条…） | 写 UI、用组件时 |
| 04 | **[04-coding-guide.md](04-coding-guide.md)** | HTML/CSS 编码规范 + 交付自查清单 | 写完代码、提交前检查 |

### 第二层：技术栈（用到再查）

| # | 文档 | 内容 | 什么时候看 |
|---|---|---|---|
| 05 | **[05-thymeleaf-guide.md](05-thymeleaf-guide.md)** | Thymeleaf 模板最佳实践（表达式/片段/表单/布局） | 写模板、用 Thymeleaf 特性时 |
| 06 | **[06-javascript-guide.md](06-javascript-guide.md)** | JavaScript 代码风格 + 项目特定规范（StockApp/命名/异步） | 写 JS、交互逻辑时 |
| 07 | **[07-echarts-guide.md](07-echarts-guide.md)** | ECharts 图表最佳实践（K线/折线/柱图/性能/主题色） | 画图表、做可视化时 |

### 第三层：专项（按需查阅）

| # | 文档 | 内容 | 什么时候看 |
|---|---|---|---|
| 08 | **[08-performance.md](08-performance.md)** | 前端性能优化（资源加载/渲染/DOM/ECharts/网络） | 优化加载速度、渲染性能时 |
| 09 | **[09-security.md](09-security.md)** | 前端安全规范（XSS/CSRF/敏感信息/存储） | 安全检查、防注入时 |

---

## 二、三主题系统（MUST 了解）

本系统支持三套可切换主题，顶栏图标按钮循环切换，`localStorage(sp-theme)` 持久化。

| Key | 名称 | 定位 | 适用场景 |
|---|---|---|---|
| `azure` | 云隙蔚蓝 | 浅蓝灰底 + 靛蓝主色，明亮护眼 | 默认主题，通用场景 |
| `mist` | 晨雾青瓷 | 雾白底 + 青瓷绿主色，低饱和 | 长时盯盘、护眼场景 |
| `cyber` | 深空赛博 | 近黑深蓝 + 霓虹青/品红 + 扫描线 | 科技感、深色模式偏好 |

**主题实现机制：**
- 主题无关 token（radius/layout/transition/font）→ `:root`
- 主题相关 token（颜色/阴影/渐变/氛围光）→ `[data-theme="xxx"]`
- 切换逻辑 → [theme.js](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/theme.js) 的 `ThemeManager`
- 全部 token 定义 → [theme.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/theme.css)

> ⚠️ **硬约束**：所有颜色必须用 `var(--xxx)`，禁止硬编码 `#xxx` 色值。需要透明度用 `color-mix(in srgb, var(--token) N%, transparent)`。

---

## 三、核心设计原则（MUST 遵循）

1. **科技感 + 数据密集**：玻璃态卡片、毛玻璃模糊、径向渐变氛围光、等宽字体强化数字识别
2. **A 股语义**：涨红跌绿（`--rise-color` / `--fall-color`），与海外相反
3. **组件优先**：优先用 components.css 的全局组件，不要在页面 CSS 重复造轮子
4. **主题一致**：三主题自动适配，不要只在一个主题下好看
5. **微动效**：hover 上浮 + 阴影增强，过渡用变量，禁用夸张缓动

---

## 四、新页面 5 步起步法

### Step 1：复制标准模板

从标杆页面复制骨架，推荐以 **factor-library.html** 为模板：
- 路径：[factor-library.html](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/pages/factor-library.html)
- 标准结构：`app-container` → `sidebar` + `main-content` → `topNavbar` + `content-area`

### Step 2：确定页面类型，选布局范式

| 页面类型 | 布局范式 | 参考 |
|---|---|---|
| 列表/数据页 | 页头 + 统计条 + 筛选栏 + 表格 | data-governance / stock-list |
| 详情/配置页 | 页头 + Tab + 表单/配置项 | strategy-editor |
| 工具/工作台 | 三栏指挥台（左 rail + 中画布 + 右结果） | screener (003) |
| 概览/仪表盘 | 卡片网格 + 统计条 + 图表 | dashboard |

### Step 3：选组件，不要自己写

写 UI 前先翻 [03-components.md](03-components.md)，看有没有现成组件：
- 按钮 → 选 `.btn-primary` / `.btn-outline-secondary` 等
- 卡片 → 用 `.card.card-glow`
- 统计条 → 用 `.stat-strip` + `.stat-card`
- 表单 → 用 `.form-control` / `.form-select`
- 徽章 → 用 `.badge` + 项目语义类

### Step 4：颜色只用 token

所有颜色从 [02-design-tokens.md](02-design-tokens.md) 选：
- 背景：`--bg-primary` / `--bg-card` / `--bg-tertiary`
- 文字：`--text-primary` / `--text-secondary` / `--text-muted`
- 强调：`--accent-blue` / `--accent-cyan` / `--accent-purple`
- 涨跌：`--rise-color` / `--fall-color`

### Step 5：交付前自查

对照 [04-coding-guide.md](04-coding-guide.md) 的自查清单过一遍，确保：
- 无硬编码色值
- 组件都用了全局类
- 三主题都能看
- 响应式断点已处理

---

## 五、CSS 文件分层（MUST NOT 越界）

```
第 1 层  theme.css         Design Tokens + base reset（只改 token 值，不加选择器）
第 2 层  components.css    全局组件（.btn/.card/.form-*/.data-table 等）
第 3 层  custom.css        布局骨架（sidebar/navbar/content-area）
第 4 层  <page>.css        页面专属样式（类名必须带页面前缀，如 .dg- / .fl-）
```

**下层不能引用上层的类，上层也不能出现下层的类。**

---

## 六、关键文件锚点

| 类型 | 路径 |
|---|---|
| 主题变量 | [theme.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/theme.css) |
| 组件库 | [components.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/components.css) |
| 主题切换 JS | [theme.js](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/theme.js) |
| 公共片段 | [fragments/common.html](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/fragments/common.html) |
| 标杆页面 | [factor-library.html](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/pages/factor-library.html) |
