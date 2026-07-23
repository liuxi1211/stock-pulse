---
alwaysApply: false
description: "StockPulse Design Tokens 设计令牌手册——所有颜色、字体、圆角、阴影、动效等设计变量的唯一来源。选颜色、调样式、主题适配时查本文档。包含三主题色板、token 命名规范、透明度写法、图表色、技术指标色。"
---

# Design Tokens 设计令牌手册

> **面向 AI & 开发者**：所有设计变量的唯一权威来源。写 CSS、选颜色、调样式时查本文档。**禁止硬编码色值，所有颜色必须通过 `var(--token)` 引用。**

> 源码：[theme.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/theme.css)

---

## 一、Token 组织规则（MUST）

### 1.1 两层结构

| 层级 | 定义位置 | 内容 |
|---|---|---|
| **主题无关 token** | `:root` | radius / layout / transition / font / 图表坐标等 |
| **主题相关 token** | `[data-theme="azure\|mist\|cyber"]` | 颜色 / 阴影 / 渐变 / 模糊 / 氛围光 / 涨跌色 |

### 1.2 命名规范

```
--{类别}-{用途}-{变体?}
```

| 类别前缀 | 含义 | 示例 |
|---|---|---|
| `--bg-*` | 背景色 | `--bg-primary` / `--bg-card` / `--bg-input` |
| `--text-*` | 文字色 | `--text-primary` / `--text-muted` |
| `--border-*` | 边框色 | `--border-color` / `--border-light` |
| `--accent-*` | 强调色 | `--accent-blue` / `--accent-cyan-light` |
| `--rise-*` / `--fall-*` | 涨跌色 | `--rise-color` / `--fall-bg` |
| `--shadow-*` | 阴影 | `--shadow-md` / `--shadow-glow-blue` |
| `--gradient-*` | 渐变 | `--gradient-primary` |
| `--radius-*` | 圆角 | `--radius-lg` |
| `--transition-*` | 过渡 | `--transition-base` |
| `--font-*` | 字体 | `--font-mono` |
| `--blur-*` | 模糊 | `--blur-card` |
| `--ambient-*` | 氛围光 | `--ambient-1` |
| `--chart-*` | 图表色 | `--chart-text` / `--chart-grid` |

### 1.3 透明度写法（MUST）

需要带透明度的主题色，**必须用 `color-mix`**，不要写 `rgba(主题RGB, 0.x)`——那样无法跟随主题切换。

```css
/* ✅ 正确 */
background: color-mix(in srgb, var(--accent-blue) 15%, transparent);
border-color: color-mix(in srgb, var(--accent-blue) 30%, transparent);

/* ❌ 错误 */
background: rgba(55, 48, 163, 0.15);  /* 主题切换时颜色不变 */
```

---

## 二、主题无关 Token（所有主题共享）

定义在 `theme.css` 的 `:root` 中。

### 2.1 布局尺寸

| Token | 默认值 | 用途 |
|---|---|---|
| `--sidebar-width` | `260px` | 侧边栏宽度 |
| `--navbar-height` | `68px` | 顶部栏高度 |
| `--content-padding` | `28px` | 内容区内边距 |

### 2.2 圆角（四级）

| Token | 值 | 典型用途 |
|---|---|---|
| `--radius-sm` | `8px` | 按钮 / 输入框 / 图标按钮 / 徽章 |
| `--radius-md` | `12px` | 次级卡片 / 搜索框 / 表单控件 |
| `--radius-lg` | `16px` | **卡片 / 模态框 / 面板（最常用）** |
| `--radius-xl` | `24px` | 极少用（大浮层） |
| `--radius-full` | `9999px` | 胶囊徽章 / 头像 / 开关圆点 |

### 2.3 过渡动效

| Token | 值 | 用途 |
|---|---|---|
| `--transition-fast` | `0.15s cubic-bezier(0.4, 0, 0.2, 1)` | 按钮 / 输入框 hover |
| `--transition-base` | `0.25s cubic-bezier(0.4, 0, 0.2, 1)` | 卡片 / 面板显隐（最常用） |
| `--transition-slow` | `0.4s cubic-bezier(0.4, 0, 0.2, 1)` | 大布局切换 |

> ⚠️ **禁止写 `transition: all 0.3s`**，必须明确指定过渡属性（如 `transition: transform var(--transition-base)`）。

### 2.4 字体族（三层）

| Token | 字体栈 | 用途 |
|---|---|---|
| `--font-display` | `'Space Grotesk', 'Noto Sans SC', ...` | 大标题 / Logo / 品牌文字（h1-h6 默认） |
| `--font-body` | `'Noto Sans SC', -apple-system, ...` | 正文 / UI 文案 |
| `--font-mono` | `'JetBrains Mono', 'SF Mono', Consolas, monospace` | **所有数字 / 股票代码 / 百分比 / 金额 / ID / 时间戳 / JSON** |

> **强制规则**：凡是股票代码、价格、百分比、金额、指标数值、ID、配置 key、路径、时间戳，一律用 `--font-mono`（或 `.font-mono` class）。

### 2.5 字号基准

`<html>` 默认 `font-size: 14px`（1rem = 14px）。

| 层级 | 字号 | 用途 |
|---|---|---|
| 页面大标题 | 22px / 20px | `.page-title` |
| 卡片标题 | 15px / 14px | `.card-title` |
| 正文 | 13px / 12.5px | 普通文案 |
| 辅助文字 | 11-12px | 说明 / 时间戳 / 徽章 |
| 极小文字 | 10-11px | 表头 / 标签 |

### 2.6 模糊效果

| Token | 值 | 用途 |
|---|---|---|
| `--blur-card` | `blur(14px) saturate(140%)` | 卡片毛玻璃 |
| `--blur-navbar` | `blur(22px) saturate(140%)` | 顶部栏 / 侧边栏毛玻璃 |

---

## 三、主题相关 Token（三套主题各一组）

> ⚠️ 下表只列 token 名与用途，**具体色值以 theme.css 为准**。需要看具体颜色直接打开 theme.css 对应主题块。

### 3.1 背景色（六层）

| Token | 用途 |
|---|---|
| `--bg-primary` | 页面最底层背景（氛围光在它之上） |
| `--bg-secondary` | 侧边栏 / 二级容器背景 |
| `--bg-tertiary` | 输入框 / 徽章底色 / 表头背景 |
| `--bg-card` | 卡片主背景（带透明度，透出氛围光） |
| `--bg-card-hover` | 卡片 hover 态 |
| `--bg-elevated` | 浮层 / 下拉菜单 / 模态框 |
| `--bg-input` | 输入框默认背景 |
| `--bg-input-hover` | 输入框 focus 背景 |

### 3.2 文字色（四级）

| Token | 用途 | WCAG 要求 |
|---|---|---|
| `--text-primary` | 标题 / 正文主文字 | ≥ 4.5:1（AA 正文） |
| `--text-secondary` | 正文次要文字 / 说明 | ≥ 4.5:1（AA 正文） |
| `--text-muted` | 辅助文字 / 标签 / 时间戳 / 禁用态 | ≥ 3:1（AA 大字，尽量接近 4.5） |
| `--text-inverse` | 深色按钮上的反白文字 | — |

### 3.3 边框色（三级）

| Token | 用途 |
|---|---|
| `--border-color` | 卡片 / 输入框主边框 |
| `--border-light` | 分隔线 / 表格行分割 |
| `--border-strong` | 强边框（极少用） |

### 3.4 强调色（Accent Colors）

| Token | 用途 |
|---|---|
| `--accent-blue` / `--accent-blue-light` / `--accent-blue-dark` | 主品牌色 / 链接 / 激活态 |
| `--accent-cyan` / `--accent-cyan-light` | 数据 / 代码 / 科技感高亮（JSON key、数值、等宽文字） |
| `--accent-purple` / `--accent-purple-light` | 衍生 / 二级强调（tushare 来源、次要 CTA） |
| `--accent-yellow` | 警告 / 草稿状态 / 原始数据 |
| `--accent-orange` | 警告渐变副色 |
| `--accent-green` | **成功 / 激活态语义**（如"已验证"绿点）。色值与 `--fall-color` 相同但语义独立 |

### 3.5 涨跌色（A 股习惯：涨红跌绿）

| Token | 语义 |
|---|---|
| `--rise-color` / `--rise-light` | **涨 / 正 / 盈利 / 买入 / 红** |
| `--rise-bg` / `--rise-strong-bg` | 涨色背景（浅 / 深） |
| `--rise-glow` | 涨色发光阴影 |
| `--fall-color` / `--fall-light` | **跌 / 负 / 亏损 / 卖出 / 绿** |
| `--fall-bg` / `--fall-strong-bg` | 跌色背景（浅 / 深） |
| `--fall-glow` | 跌色发光阴影 |

> 工具类：`.rise` / `.fall` / `.text-rise` / `.text-fall` / `.bg-rise` / `.bg-fall` 已在 components.css 预置，直接加 class 即可。

### 3.6 阴影

> 浅色主题（azure/mist）用**柔和投影**，cyber 用**深色投影 + 霓虹 glow**。

| Token | 用途 |
|---|---|
| `--shadow-sm` | 小元素轻微阴影 |
| `--shadow-md` | 浮层 / 下拉菜单 |
| `--shadow-lg` | **卡片 / 模态框默认阴影** |
| `--shadow-xl` | 模态框 / 大屏浮层 |
| `--shadow-glow-blue` | Logo / 主按钮发光（cyber 下为霓虹 glow） |
| `--shadow-glow-rise` / `--shadow-glow-fall` | 涨跌强调元素发光 |

### 3.7 渐变

| Token | 用途 |
|---|---|
| `--gradient-primary` | **主按钮 / Logo / 激活项 / 印章式主操作按钮**（最常用） |
| `--gradient-secondary` | 次强调 |
| `--gradient-rise` | 危险按钮 / 涨色强调 |
| `--gradient-fall` | 成功 / 盈利态 |
| `--gradient-gold` / `--gradient-silver` / `--gradient-bronze` | 排名 / 星级 |

### 3.8 氛围光

| Token | 用途 |
|---|---|
| `--ambient-1` / `--ambient-2` / `--ambient-3` | 三处径向渐变氛围光颜色（左上 / 右上 / 底部） |
| `--ambient-opacity` | 氛围光整体透明度 |

> 全局 `body::before` 已注入三处径向渐变，**页面不要再自画背景**，透明卡片会自然透出氛围光。

---

## 四、图表专用 Token

供 `charts-theme.js` 运行时读取，主题切换时自动重绘。

| Token | 用途 |
|---|---|
| `--chart-text` | 图表文字颜色 |
| `--chart-axis` | 坐标轴线颜色 |
| `--chart-grid` | 网格线颜色 |
| `--chart-split-line` | 分割线颜色 |
| `--chart-tooltip-bg` | Tooltip 背景 |
| `--chart-tooltip-border` | Tooltip 边框 |
| `--chart-tooltip-text` | Tooltip 文字 |
| `--chart-crosshair` | 十字线颜色 |
| `--chart-crosshair-label` | 十字标签背景 |

---

## 五、技术指标专用 Token

K 线图 / 指标图上的技术指标线颜色。

| Token | 指标 |
|---|---|
| `--macd-dif` / `--macd-dea` | MACD 快慢线 |
| `--macd-hist-up` / `--macd-hist-down` | MACD 柱（涨 / 跌） |
| `--kdj-k` / `--kdj-d` / `--kdj-j` | KDJ 三线 |
| `--rsi-6` / `--rsi-12` / `--rsi-24` | RSI 三线 |
| `--boll-upper` / `--boll-mid` / `--boll-lower` | 布林带三线 |

---

## 六、三主题概览

| 主题 | 主色调 | 气质 | 最佳场景 |
|---|---|---|---|
| **azure** 云隙蔚蓝 | 靛蓝 + 青 | 明亮、专业、护眼 | 默认主题，大多数页面 |
| **mist** 晨雾青瓷 | 青瓷绿 + 墨蓝 | 低饱和、柔和、长时盯盘 | 行情列表、数据密集页 |
| **cyber** 深空赛博 | 霓虹青 + 品红 | 科技感、未来感、深色 | 图表页、工作台、控制台 |

---

## 七、主题切换机制

### 7.1 切换入口

- 顶栏 `#themeToggle` 图标按钮（已由 `fragments/common :: topNavbar` 注入）
- 点击循环：`azure → mist → cyber → azure`
- 图标随主题变：`bi-sun` / `bi-droplet-half` / `bi-lightning-charge`

### 7.2 持久化

- `localStorage(sp-theme)` 存储
- 由 `theme.js` 的 `ThemeManager` 读写

### 7.3 防首屏闪烁

`fragments/common :: head` 最前面有一段内联阻塞脚本，在 CSS 加载前同步设置 `<html data-theme="...">`。

**页面模板不需要手动加 `data-theme` 属性。**

### 7.4 API

```javascript
ThemeManager.get()           // 取当前主题 key
ThemeManager.set('cyber')    // 指定切换
ThemeManager.cycle()         // 循环切换
```

### 7.5 主题变更事件

切换时派发 `window` 的 `theme:changed` 事件：
```javascript
window.addEventListener('theme:changed', (e) => {
    console.log(e.detail.theme);  // 'azure' | 'mist' | 'cyber'
});
```

`charts-theme.js` 等模块监听此事件后自动重绘。

---

## 八、新页面主题开发指引（MUST）

1. **颜色只用 `var()`**：所有颜色引用全局 token，禁止硬编码 `#xxx`（`#fff` / 纯白 / 纯黑阴影 / `transparent` 除外）
2. **透明度用 `color-mix`**：需要带透明度的主题色用 `color-mix(in srgb, var(--token) N%, transparent)`
3. **图表颜色走 `ChartsTheme`**：新增 ECharts/Lightweight Charts 实例后 MUST 调用 `ChartsTheme.register(instance, type)` 注册
4. **页面专属颜色新增 token**：如因子来源色、卡片强调色，在页面 CSS 新增专属 token（如 `--src-akquant`、`--stat-accent`），只赋单一值即可
5. **禁止重定义基础 token**：页面 CSS MUST NOT 重定义 `--bg-*` / `--text-*` / `--accent-*` 等基础 token
6. **禁止定义新的 `[data-theme]` 块**：主题切换只在 `theme.css` 中定义

---

## 九、对比度说明（2026-07 调整）

### 已知达标的
- 主要文字（primary / secondary）：三套主题全部 ≥ 4.5:1 ✅
- 强调色（blue / purple / green / rise / fall）：深色主题全部过 AA，浅色主题部分仅过 AA 大字（3:1+）

### 已知不足的（浅色主题下）
- `--accent-cyan-light`：作为文字对比度不足（约 2.5:1），**仅用作背景 / 装饰 / hover 态，不要作文字色**
- 图表坐标线 / 网格线（`--chart-axis` / `--chart-grid`）：对比度较低，但属于装饰性元素，可接受

### 调整历史
- 2026-07：`--text-muted` 和 `--accent-cyan` 在两个浅色主题中加深，从 ~2.5:1 提升到 ~4.5:1，达到 WCAG AA 正文标准
