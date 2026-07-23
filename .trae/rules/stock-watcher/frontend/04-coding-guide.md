---
alwaysApply: false
description: "StockPulse 前端编码规范与交付自查清单——HTML/CSS/JS 编码约定、CSS 分层原则、响应式断点、交互动效规范、交付前检查项。写完代码、提交前对照本文档检查。"
---

# 编码规范与交付自查清单

> **面向 AI & 开发者**：HTML / CSS / JS 编码约定 + 交付前自查清单。写完代码、提交前对照本文档过一遍。

---

## 一、HTML 编码约定

### 1.1 语义化标签（SHOULD）

优先使用语义化标签，避免全用无语义 `<div>`：

| 标签 | 用途 |
|---|---|
| `<header>` | 页面 / 区块头部（如 `.top-navbar`、`.page-header`） |
| `<nav>` | 导航（如 `.sidebar`） |
| `<main>` | 主要内容（`.content-area`） |
| `<section>` | 区块 |
| `<article>` | 独立内容块 |
| `<aside>` | 侧边栏 / 详情面板 |
| `<footer>` | 页脚（当前项目未使用） |

### 1.2 属性顺序（SHOULD）

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

### 1.3 标签闭合（MUST）

- 自闭合标签：`<br>`、`<hr>`、`<img>`、`<input>`、`<meta>`
- HTML5 中自闭合标签可不加 `/`，但风格保持一致

### 1.4 注释（SHOULD）

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

## 二、CSS 写法规范

### 2.1 分层原则（MUST）

CSS 按四层组织，**不要在页面 CSS 里重定义 components.css 已有的 class**：

| 层 | 文件 | 职责 |
|---|---|---|
| Design Tokens | `theme.css` | 只放 `:root` 变量 + base reset，不动 |
| Base | `theme.css` | reset、body、scrollbar、选区色、工具类 |
| Components | `components.css` | sidebar、navbar、card、btn、form、table、badge、modal、alert |
| Pages | `<page>.css` | **只放**页面专属组件（页面级布局 grid、专属模块） |

### 2.2 命名规范

- 使用 BEM 简化思想：`.block` / `.block-element` / `.block-element--modifier`
- **页面专属 class 使用前缀**避免污染全局，如：
  - 因子库：`fl-`（factor-library）
  - 策略管理：`str-` / `editor-` / `ver-`
  - 数据管控：`dg-`（data-governance）
  - 选股中心：`scr-`（screener）
- 重点是**语义化 + 不冲突**，BEM 不强制严格双下划线/双横线

### 2.3 选择器优先级（MUST）

- 尽量使用 class 选择器，少用 id 选择器
- 避免过深的嵌套（不超过 3 层）
- 避免使用 `!important`（覆盖 Bootstrap 默认样式时除外）

### 2.4 属性书写顺序（SHOULD）

按以下顺序书写 CSS 属性：

1. **定位**：`position`、`top`、`left`、`z-index`
2. **盒模型**：`display`、`width`、`height`、`margin`、`padding`、`border`、`border-radius`
3. **背景**：`background`、`backdrop-filter`
4. **文本**：`font`、`color`、`text-align`、`line-height`
5. **其他**：`transition`、`animation`、`transform`、`box-shadow`

### 2.5 硬编码色值禁令（MUST）

除了 `white` / `#fff` / `transparent` 和 box-shadow 的 `rgba(0,0,0,.*)` 纯黑，**所有颜色必须引用 var()**：

```css
/* ✅ 正确 */
.my-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  color: var(--accent-cyan);
}

/* ❌ 错误 */
.my-card {
  background: #0f1520;
  border: 1px solid #334155;
  color: #06b6d4;
}
```

**透明度色值必须用 `color-mix`**：

```css
/* ✅ 正确 */
background: color-mix(in srgb, var(--accent-blue) 15%, transparent);

/* ❌ 错误 */
background: rgba(55, 48, 163, 0.15);  /* 主题切换时颜色不变 */
```

### 2.6 其他 CSS 禁令（MUST NOT）

- ❌ 不要写超过 3 层的嵌套选择器
- ❌ 不要在页面 CSS 里重置全局标签样式（h1-h6 / a / button / body 等已在 base/components 层设定）
- ❌ 不要写 `transition: all ...`（明确指定过渡属性）
- ❌ 不要在页面 CSS 里定义新的 `[data-theme="xxx"]` 块——主题切换只在 `theme.css` 中定义

---

## 三、Bootstrap 5 使用边界

### 3.1 适用场景（用 Bootstrap 工具类）

Bootstrap 优先用于**布局 / 间距 / 对齐 / 交互底层**：
- **Grid 栅格**：`container` / `row` / `col-*` / `col-lg-*` 做响应式布局
- **Spacing 间距**：`m-*` / `p-*` / `gap-*`
- **Flex 工具**：`d-flex` / `justify-content-*` / `align-items-*`
- **文本工具**：`text-end` / `text-center` / `text-nowrap` / `fw-*`
- **交互底层**：Modal / Collapse / Dropdown 等 JS 组件（视觉已覆盖）

### 3.2 不适用场景（必须用项目自定义类）

| 场景 | ❌ 不要用 Bootstrap | ✅ 应该用 |
|---|---|---|
| 卡片视觉 | 原生 `.card` 浅色样式 | `.card.card-glow`（已主题化 + 毛玻璃） |
| 徽章颜色 | `bg-primary` / `bg-danger` / `bg-success` | `.badge-primary` / `.badge-rise` / `.badge-fall` |
| 表格主题 | `table-light` / `table-dark` | `.data-table` 或 `.table.table-hover`（已主题化） |
| 按钮 | 原生蓝色按钮 | `.btn.btn-primary`（已改为品牌渐变） |

### 3.3 响应式断点

Bootstrap 断点与本项目响应式规则一致：
- `<= 1280px`（xl 以下）：统计条 4→2 列，主从布局缩小侧栏
- `<= 1024px`（lg 以下，991.98px）：侧边栏收起，主从布局变单列，sticky 变 static
- `<= 640px`（md 以下，767.98px）：统计条 1 列，表单栅格 1 列，页头按钮可能折行

---

## 四、布局模式（SHOULD）

### 4.1 标准页面三段式

`.content-area` 内的页面内容推荐按以下顺序组织：

```
<page-header> 标题 + 副标题 + 右侧操作按钮组 </page-header>
<stat-strip>  可选：4 个统计卡片              </stat-strip>
<filter-bar>  可选：搜索 + 筛选 + 视图切换    </filter-bar>
<!-- 主体：卡片网格 / 三栏指挥台 / 主从布局 / 表格 -->
```

- 页标题 22px、字重 700；副标题 12.5px、`color: var(--text-muted)`
- 面包屑在顶部栏内（由 topNavbar fragment 渲染）

### 4.2 三栏指挥台（数据密集型工作流）

参考 003 多因子选股中心范式（`220px | 1fr | 360px`，gap 20px，padding 24px）：

```
+----------+----------------------+------------------+
| 左 rail  |   中 编辑/构建画布    |   右 结果/追踪    |
+----------+----------------------+------------------+
```

- 左右栏 `position: sticky; top: 84px` 常驻，主区滚动
- `<=1280px` 时三栏收为两栏（右栏结果下沉占满），`<=860px` 时单栏堆叠

### 4.3 主从布局（Master-Detail）

参考 factor-library 的三栏 `.md-grid`：`200px 分类 rail | 1fr 主列表 | 440px 详情面板`。
- rail 和 detail 用 `position: sticky; top: 84px` 常驻
- 卡片网格：`grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px`

### 4.4 编辑器 Tab 式布局

参考 004 策略管理原型：
- Tab 栏：横向滚动 flex + gap: 2px，padding 0 14px，底部 1px `--border-light`
- Tab 按钮：padding 12px 16，active 态文字主色 + 底部 2px `var(--gradient-primary)` 渐变条
- Tab 内容 padding：22px 24px；切换面板 `fadeIn .2s`（translateY 4px→0）
- 底部保存条：sticky bottom:0，毛玻璃背景 + `backdrop-filter: blur(16px)`

---

## 五、交互与动效（SHOULD）

1. **Hover 上浮**：可点击卡片默认 `translateY(-3px ~ -4px)` + 边框色变 + 阴影增强，时长 `var(--transition-base)`（0.25s）
2. **按钮反馈**：主按钮 hover `translateY(-1px)` + 阴影增强，active 归位，时长 `var(--transition-fast)`
3. **入场动画**：页面首屏元素可加 `.animate-in` + `.delay-1..6`（fadeInUp .6s）
4. **Modal / 弹出**：scaleIn 从 0.96→1 + fade in，0.2-0.25s
5. **列表激活**：侧边栏 / 分类项激活态用左侧 2-3px `var(--gradient-primary)` 竖条 + 蓝青渐变底色（15%/10% 透明）
6. **开关 / Tab**：过渡 0.15-0.2s，避免突兀切换
7. **减少动效**：`@media (prefers-reduced-motion: reduce)` 下关闭印章动画、渐显、面板切换动画

### 禁止项（MUST NOT）

- ❌ 不要引入 `bounce` / `elastic` 等夸张缓动
- ❌ 不要给关键元素做超过 300ms 的动画
- ❌ 不要让 hover 效果改变文档流（避免引起其他元素位移）
- ❌ 不要写 `transition: all 0.3s`（明确指定过渡属性）

---

## 六、JavaScript 编码约定

### 6.1 主题相关

- 取当前主题：`ThemeManager.get()`
- 切换主题：`ThemeManager.set('cyber')` 或 `ThemeManager.cycle()`
- 监听主题变化：
  ```javascript
  window.addEventListener('theme:changed', (e) => {
    console.log(e.detail.theme);  // 'azure' | 'mist' | 'cyber'
  });
  ```

### 6.2 图表相关

- 新增图表实例后 MUST 注册：`ChartsTheme.register(instance, 'echarts' | 'lightweight')`
- 图表颜色通过 `ChartsTheme.getEChartsTheme()` / `getKlineTheme()` / `getChartColors()` 取
- **禁止在页面 JS 里硬编码图表色值**

### 6.3 通用工具

- Toast 通知：`showToast(message, type)`，type = `success` / `danger` / `warning` / `info`
- 公共 JS 在 `common.js` 中

---

## 七、交付前自查清单（MUST）

- [ ] `<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">` 已设置，**未手动设置 `data-theme` 属性**（由 head 内联脚本自动设置）
- [ ] 页面骨架使用标准模板（`app-container` / `main-content` / `content-area`），公共部分通过 `fragments/common :: head/sidebar/sidebarOverlay/topNavbar/scripts/toastContainer` 引入
- [ ] 页面专属 CSS / JS 放在 body 末尾、`scripts` fragment 之后
- [ ] **所有颜色用 `var()`**，无硬编码 `#xxx` 字面量颜色（白 / 透明 / 纯黑阴影除外）
- [ ] **透明度色值用 `color-mix(in srgb, var(--token) N%, transparent)`**，无 `rgba(主题RGB, 0.x)` 硬编码
- [ ] **未自画页面级背景**（无径向渐变 / 纯色铺底），复用全局 `body::before` 氛围光
- [ ] 涨红跌绿：正数 / 盈利 / 涨用 `.rise` / `var(--rise-light)`，负数 / 亏损 / 跌用 `.fall` / `var(--fall-light)`，未沿用海外绿涨红跌
- [ ] 数字 / 股票代码 / 百分比 / 金额 / ID / 时间戳 / JSON key 使用 `font-family: var(--font-mono)`（`.font-mono` class）
- [ ] 卡片圆角 16px（`--radius-lg`），按钮 / 输入框 12px（`--radius-md`），图标按钮 / 小标签 8px（`--radius-sm`）
- [ ] 卡片带 `backdrop-filter` 毛玻璃效果（用 `var(--blur-card)`）
- [ ] 主按钮用 `--gradient-primary` 渐变，自带投影与 hover 上浮；强决策动作可用印章式按钮
- [ ] 表头 uppercase + 11px + `--text-muted` + letter-spacing: .5px，未使用 `.table-light` / `.table-dark`
- [ ] 表单控件 focus 态有 `0 0 0 3px` 主色外发光（颜色随主题）
- [ ] 徽章使用项目语义类（`.badge-rise` / `.badge-fall` 等），不是 Bootstrap 的 `bg-*`
- [ ] hover / 过渡动效使用 `--transition-fast` / `--transition-base` 变量，无 `transition: all`、无夸张 bouncy 缓动
- [ ] 所有可交互元素（卡片 / 按钮 / 导航项）有明确 hover 态
- [ ] 响应式断点已处理（1280 / 1024 / 640 三档）
- [ ] **页面 CSS 未定义新的 `[data-theme="xxx"]` 块**，未重定义 `:root` 或 `[data-theme]` 基础 token
- [ ] **图表（ECharts / Lightweight Charts）颜色走 `ChartsTheme`**，实例已 `ChartsTheme.register()` 注册，未在页面 JS 硬编码图表色值
