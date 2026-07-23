# 个股诊断模块 PRD：4 Tab 下钻详情页

> **模块**：017 · 个股诊断（4 Tab 下钻详情页）
> **状态**：规划中，待 `hk_hold` / `stock_moneyflow` / `top_list` / `top_inst` / `block_trade` / `stk_holdertrade` / `stk_holdernumber` 等表（由 tushare 对应接口采集）落地后进入实施
> **来源**：拆分自 [`主要功能模块整体设计计划.md`](./../../.trae/documents/主要功能模块整体设计计划.md) §3.3
> **原则**：严格基于 tushare ≤2000 积分可用接口；不引入派生聚合数据；前端复用现有组件（StockApp/SearchSuggest/ChartsTheme/renderKline）；本文仅做"需求级"定稿，**具体接口字段、错误码、前端交互稿待落地 spec 展开**。

---

## 0. 拆分说明（为什么单独立项）

总计划文档把 6 个功能模块（数据采集 / 个股诊断（017）/ 板块行情（015）/ 资金流向（016）/ 现有模块补齐（012 仪表盘 / 013 行情中心 / 014 自选股）/ 联调）混排，本文把 **个股诊断模块（017）** 单独拎出独立成稿，因为：

- **它是唯一的下钻详情页**：不在侧边栏菜单，从行情中心（013）、自选股（014）、资金流向（016）、板块行情（015）等页面点击股票代码进入，与各页面正交，需独立定义路由与页面骨架。
- **K 线分析能力的合并宿主**：原 K 线分析占位菜单已移除，K 线渲染能力合并到本模块 Tab 1，需在本 PRD 内明确"周期切换 + 指标副图 + 画线 + 复权 + 十字光标 + 金叉时间轴"的完整范围。
- **数据依赖跨度最大**：同时依赖数据采集任务新增的 `hk_hold` / `stock_moneyflow` / `top_list` / `top_inst` / `block_trade` / `stk_holdertrade` / `stk_holdernumber` 七张表与已有 13 张表（`daily_quote` / `adj_factor` / `daily_basic` / `fina_indicator` / `income` / `balancesheet` / `cashflow` / `forecast` / `express` / `stock_stk_limit` / `stock_suspend_d` / `stock_namechange` / `dividend`），需集中梳理依赖与前置。
- **Tab 裁剪决策集中**：原 5 Tab 砍掉 Tab 4 公司资料 F10（`stock_company` 不存在）、风险提示 Tab 砍掉限售解禁（`share_float` 需 3000 分），裁剪理由需统一记录。

> **不在本 PRD 范围**：板块行情（015）、资金流向页面（016）、现有模块补齐（012 仪表盘 / 013 行情中心 / 014 自选股）--各自独立排期；本 PRD 只描述个股诊断页面本身及其直接依赖的后端 Controller。

---

## 1. 背景与目标

### 1.1 业务背景

- **老股民的使用路径是"列表/排行 -> 点击个股 -> 看详情做诊断"**：行情中心（013）涨幅榜、自选股（014）列表、资金流向（016）Top 10、板块行情（015）成分股展开后，老股民的下一步动作一定是"点开这只票看详细"。当前 stock-pulse 没有这个下钻页面，用户看到代码后只能复制到东方财富/同花顺查，跳出本系统。
- **K 线、基本面、资金面、风险四个维度缺一不可**：老股民看票时一定联动看 K 线 + 基本面 + 资金 + 风险提示，单独做 K 线页面太单薄（这也是原 K 线分析菜单被移除的原因）。把这四个维度合并到个股诊断的 4 Tab 才合理。
- **现有仪表盘的 K 线能力需复用而非重写**：`dashboard.js` 已有 `renderKline` 函数，本次把它抽成可复用模块或复制到 `stock-detail.js`，扩展周期切换与指标副图，避免重复造轮子。

### 1.2 总体目标

- **目标一**：输入 `/page/stock-detail/{code}` 能看到 4 Tab 完整页面（K 线技术面 / 基本面 / 资金面 / 风险提示），顶部摘要条展示股票名/代码/最新价/涨跌额/涨跌幅/行业/市值/PE/PB/换手率，右上角有"加自选""设置价格提醒"按钮。
- **目标二**：K 线 Tab 支持日/周/月/60 分周期切换、MA/EMA/BOLL/SAR 主图叠加 + MACD/KDJ/RSI/WR/CCI/DMI 副图、画线工具（趋势线/水平线/平行通道）、前复权/后复权/不复权切换、十字光标 + OHLCV 浮窗、MA5/10/20/60 金叉死叉时间轴。
- **目标三**：基本面/资金面/风险提示 Tab 各图表数据正确加载，依赖的表（由 tushare 对应接口采集）已落地。
- **质量准绳**：`.trae/rules/系统设计理念(用法建议).md` + A 股实战派认可（不接受"纸面能跑、实战不能用"）。

### 1.3 非目标（明确不做）

| 项 | 原因 | 备注 |
|---|---|---|
| **Tab 4 公司资料 F10** | `stock_company` 接口不在 tushare 接口汇总表，不存在 | 后续 tushare 增加低积分 F10 接口再补；页面由原 5 Tab 顺延为 4 Tab |
| **风险提示 Tab 含限售解禁明细** | `share_float` 需 3000 积分，超 ≤2000 积分约束 | 用 `stk_holdertrade`（股东增减持）部分替代"减持公告"项 |
| **侧边栏菜单入口** | 个股诊断是下钻详情页，从其他页面点击股票代码进入 | 也支持顶栏全局搜索框输入代码直接跳转 |
| **日内/T+0 高频打板** | 设计理念禁做 | 不在本模块范围 |
| **概念板块归属展示** | `concept` / `concept_detail` 接口不存在 | 个股的行业归属用 `stock_basic` 的申万一级行业字段展示，不展开概念板块 |
| **北向每日净买入柱状图** | `moneyflow_hsgt` 接口不存在 | 资金面 Tab 用 `hk_hold` 做持股比例曲线，不做净买入柱状图 |
| **跨市场对比** | 不在本期范围 | 个股诊断只看单只票，不做跨票对比 |

### 1.4 典型用户旅程

**旅程 1：行情中心下钻看票**
行情中心（013）涨幅榜 -> 点击某只票的代码链接 -> 跳转 `/page/stock-detail/{code}` -> 默认 Tab 1 看 K 线 -> 切 60 分周期看日内 -> 切 Tab 2 看基本面 PE 分位 -> 切 Tab 3 看主力净流入 -> 切 Tab 4 看是否有 ST/停牌 -> 点"加自选"

**旅程 2：自选股池下钻诊断**
自选股（014）列表 -> 点击持仓中的票 -> 跳转个股诊断 -> Tab 1 切周线看大趋势 -> 开 MACD 副图看背离 -> 切 Tab 3 看北向持股比例变化曲线 -> 看大宗交易明细是否有机构接盘

**旅程 3：资金流向 Top 10 下钻**
资金流向（016）页面 Tab A 主力净流入 Top 10 -> 点某只票 -> 跳转 `/page/stock-detail/{code}?tab=moneyflow` -> 直接停在资金面 Tab -> 看个股 30 日主力净流入柱状图 + 龙虎榜上榜次数

**旅程 4：顶栏搜索直接跳转**
顶栏全局搜索框输入"000001" -> SearchSuggest 联想 -> 选中 -> 跳转个股诊断

---

## 2. 依赖与前置

### 2.1 后端数据依赖

#### 2.1.1 新增表（必须先落地）

依赖如下 7 张表（由 tushare 对应接口采集，需完成 schema + DO + Mapper + TushareApiEnum + DTO + Service + 定时拉取任务）：

| # | 表名 | Tushare 接口 | 枚举名 | 积分 | 关键字段 | 本模块用途 |
|---|---|---|---|---|---|---|
| 1 | `hk_hold` | `hk_hold` | `HK_HOLD` | 2000起 | trade_date, code, name, vol, ratio, ts_code, exchange_id | Tab 3 北向持股比例曲线 |
| 2 | `stock_moneyflow` | `moneyflow` | `MONEYFLOW` | 2000 | ts_code, trade_date, buy_sm_amount/vol, sell_sm_amount/vol, ..., net_mf_amount, net_mf_vol | Tab 3 个股 30 日主力净流入柱状图 |
| 3 | `top_list` | `top_list` | `TOP_LIST` | 2000 | trade_date, ts_code, name, close, pct_change, amount, l_buy, l_sell, ..., reason | Tab 3 龙虎榜上榜次数（近 1 年） |
| 4 | `top_inst` | `top_inst` | `TOP_INST` | 2000 | trade_date, ts_code, exalter, side, buy, buy_rate, sell, sell_rate, net_buy | Tab 3 龙虎榜机构席位明细（点击展开营业部用） |
| 5 | `block_trade` | `block_trade` | `BLOCK_TRADE` | 2000 | trade_date, ts_code, name, price, vol, amount, buyer, seller, buyer_name, seller_name | Tab 3 大宗交易明细（近 3 个月） |
| 6 | `stk_holdertrade` | `stk_holdertrade` | `STK_HOLDERTRADE` | 2000 | ts_code, ann_date, end_date, holder_name, holder_type, in_de, change_vol, change_ratio, after_share, after_ratio | Tab 4 减持公告 |
| 7 | `stk_holdernumber` | `stk_holdernumber` | `STK_HOLDERNUMBER` | 2000 | ts_code, ann_date, end_date, holder_num | Tab 2 股东人数变化曲线 |

#### 2.1.2 已有表复用（需补 Service + Controller）

为如下已有表补 Service + Controller 对前端暴露查询接口：

| 表名 | 现状 | 本模块用途 |
|---|---|---|
| `daily_quote` | 已有完整链路 | Tab 1 K 线主数据（日/周/月/60 分） |
| `adj_factor` | 已有完整链路 | Tab 1 复权切换（前复权/后复权/不复权） |
| `daily_basic` | 表+DO+Mapper+DTO 全有，缺 Service + Controller | 顶部摘要条 PE/PB/换手率/市值；Tab 2 PE/PB/PS 历史分位图 |
| `fina_indicator` | 同上 | Tab 2 ROE/ROA/毛利率/净利率趋势图 |
| `income` / `balancesheet` / `cashflow` | 同上 | Tab 2 财务三大表摘要（最近 4 期） |
| `forecast` / `express` | 同上 | Tab 2 业绩预告/快报时间轴 |
| `stock_stk_limit` | 同上 | Tab 4 涨跌停价 |
| `stock_suspend_d` | 同上 | Tab 4 历史停牌记录 |
| `stock_namechange` | 同上 | Tab 4 ST 戴帽/摘帽历史 |
| `dividend` | 已有 `DividendController` | Tab 2 分红送转历史（前端直接调用即可） |
| `stock_basic` | 已有完整链路 | 顶部摘要条行业字段、股票名称 |

> ⚠️ **强制前置**：本 PRD 所有 FR 在数据采集任务未完成前不可启动实施。数据采集任务落地前，前端可先做页面骨架占位（B2），但数据加载必为空状态兜底。

### 2.2 前端组件依赖

| 组件 | 来源 | 复用方式 |
|---|---|---|
| `StockApp.get/post/toast/confirm/alert/prompt/formatNumber/formatPercent` | `static/js/common.js` | 所有 API 调用与数字格式化 |
| `SearchSuggest` | `static/js/search-suggest.js` | 顶栏全局搜索框（已在主框架） |
| `ChartsTheme` | `static/js/charts-theme.js` | Tab 1 K 线图、Tab 2/3 所有 ECharts 实例的统一主题 |
| `renderKline` 函数 | `static/js/dashboard.js` | 抽成可复用模块或复制到 `stock-detail.js`，扩展多周期与指标副图 |
| 页面骨架 | `fragments/common.html` 的 `head/sidebar/topNavbar/scripts` | 套用主框架布局 |
| 涨跌色 / 数字格式化 | `theme.css` + `StockApp.formatNumber/formatPercent` | 全局统一（红涨绿跌或绿涨红跌按主题） |

### 2.3 路由依赖

- `PageController.java` 需新增 `/page/stock-detail/{code}` 路由（FR-1）。
- `fragments/common.html` **不**在侧边栏加菜单项（个股诊断是下钻页，不进菜单）。
- 其他页面（行情中心（013）、自选股（014）、资金流向（016）Tab A 等）的股票代码列需改为链接 `/page/stock-detail/{code}`，这部分在行情中心（013）/ 自选股（014）/ 资金流向（016）各自 PRD 中定义，本 PRD 只定义个股诊断页本身。

---

## 3. 功能需求

### FR-1 路由与页面骨架

**路由定义**：
- URL：`/page/stock-detail/{code}`，其中 `{code}` 为 tushare 6 位代码格式（如 `000001.SZ`、`600000.SH`、`300750.SZ`、`688981.SH`）。
- 支持 query 参数 `?tab=<tab_id>`，直接定位到指定 Tab（如 `?tab=moneyflow` 停在资金面 Tab）。Tab id 取值：`kline` / `fundamental` / `moneyflow` / `risk`。
- 路由在 `PageController.java` 中注册，返回 `pages/stock-detail` 模板。

**页面骨架**：
- 套用 `fragments/common.html` 的 `head/sidebar/topNavbar/scripts` 主框架布局。
- 侧边栏不显示"个股诊断"菜单项（与总计划 §1.3 一致）。
- 主体结构：顶部摘要条（固定）+ Tab 切换栏 + Tab 内容区（懒加载）。
- Tab 切换栏 4 项：`K 线技术面` / `基本面` / `资金面` / `风险提示`（原 Tab 4 公司资料 F10 已砍掉，原 Tab 5 风险提示顺延为 Tab 4）。
- 默认展示 Tab 1（K 线技术面）；若 URL 带 `?tab=` 参数则切到指定 Tab。

**下钻入口**（其他页面的改造，本 PRD 只声明预期入口，不负责实现）：
- 行情中心（013）涨幅榜/榜单的股票代码列 -> `/page/stock-detail/{code}`
- 自选股（014）列表的股票代码列 -> `/page/stock-detail/{code}`
- 资金流向（016）Tab A 主力净流入 Top 10 的股票链接 -> `/page/stock-detail/{code}?tab=moneyflow`
- 板块行情（015）成分股展开后的股票代码 -> `/page/stock-detail/{code}`
- 顶栏全局搜索框 SearchSuggest 选中后 -> `/page/stock-detail/{code}`（已有组件，需配 onSelect 跳转）

**股票代码合法性校验**：
- 进入页面时，前端先调 `/api/stock-basic?tsCode={code}` 校验代码存在；不存在则展示 404 占位"未找到该股票"。
- 后端 Controller 接收 `{code}` 后，需校验格式（6 位数字 + `.` + `SZ/SH/BJ`），非法格式直接返回 400。

---

### FR-2 顶部摘要条

**位置**：页面顶部固定一条横向摘要条，位于 Tab 切换栏之上。

**展示字段**（一行布局，左右分栏）：

| 字段 | 来源表 | 来源字段 | 格式 |
|---|---|---|---|
| 股票名 | `stock_basic` | `name` | 原文 |
| 股票代码 | URL 参数 | `{code}` | 原文 |
| 最新价 | `daily_quote` | `close`（最新交易日） | 2 位小数 |
| 涨跌额 | `daily_quote` | `close - pre_close` | 2 位小数，带 +/- 号 |
| 涨跌幅 | `daily_quote` 或 `daily_basic` | `pct_chg` | 百分比，2 位小数，带 +/- 号 |
| 行业 | `stock_basic` | `industry`（申万一级行业） | 原文，空则显示"--" |
| 市值 | `daily_basic` | `total_mv`（总市值，单位万元） | 亿元单位，2 位小数 |
| PE (TTM) | `daily_basic` | `pe_ttm` | 2 位小数，负数显示"--" |
| PB | `daily_basic` | `pb` | 2 位小数，负数显示"--" |
| 换手率 | `daily_basic` | `turnover_rate` | 百分比，2 位小数 |

**右上角按钮**：
- **加自选**：点击调 `POST /api/watchlist`（已有 `WatchlistController`），入参 `{ tsCode, groupId?, note? }`。已加自选的票按钮置灰显示"已加自选"，点击可弹确认移除。
- **设置价格提醒**：点击弹模态框，让用户设置目标价/涨跌幅阈值（与自选股（014）价格提醒共用接口，自选股（014）价格提醒落地后生效；本模块阶段按钮可先占位 toast"价格提醒功能即将上线"）。

**涨跌色规则**：
- 涨跌额、涨跌幅按主题涨跌色规则着色（`theme.css` 定义红涨绿跌或绿涨红跌）。
- 涨跌幅 > 0 显示红色/绿色（按主题），< 0 显示反向色，= 0 显示中性灰。

**数据加载时机**：
- 页面进入即加载（不懒加载），与 Tab 内容解耦。
- 加载中展示骨架占位，加载失败展示"数据加载失败，请刷新重试"+ 重试按钮。

---

### FR-3 Tab 1 K 线技术面

**Tab id**：`kline`。默认展示此 Tab。

#### 3.1 主图：大 K 线图

- **渲染引擎**：复用 `dashboard.js` 的 `renderKline` 函数（LightweightCharts），抽成可复用模块或复制到 `stock-detail.js`。
- **K 线类型**：蜡烛图（默认）+ 成交量副图（柱状，主图下方对齐）。
- **数据来源**：`daily_quote` 表，按 `{code}` 与周期参数查询。

#### 3.2 周期切换

- 支持四种周期：**日 / 周 / 月 / 60 分**。
- 周期切换通过 Tab 上方的周期按钮组（4 个按钮，单选）。
- 后端 `/api/daily-quote` 需支持 `period` 参数（`D`/`W`/`M`/`60min`），默认 `D`。
- 周/月线由后端聚合日线生成（按 trade_date 周期边界聚合 OHLCV），60 分线直接查 `daily_quote` 的 60 分周期数据（若 `daily_quote` 表未存 60 分数据，则此周期按钮置灰并 tooltip 提示"60 分数据未接入"）。
- 切换周期时保留当前主图指标与画线工具状态。

#### 3.3 主图叠加指标

- **MA**：MA5 / MA10 / MA20 / MA60（默认全开，可单独切换显隐）。
- **EMA**：EMA12 / EMA26（默认关）。
- **BOLL**：布林带（timeperiod=20, nbdevup=2.0, nbdevdn=2.0），返回 upper/mid/lower 三线叠加主图（默认关）。
- **SAR**：抛物线指标点（acceleration=0.02, maximum=0.2），以圆点叠加主图（默认关）。
- 指标计算在前端用 `akquant.talib`（参考 `.trae/rules/akquant/07-talib-indicators.md` 的签名表）；或在后端计算后返回，二选一在落地 spec 决定。
- 指标显隐切换不重新拉数据，仅前端重算/重渲染。

#### 3.4 副图指标

- 副图位于主图下方，独立一个小图区域，通过下拉选择切换（单选）：
  - **MACD**：dif / dea / hist（fastperiod=12, slowperiod=26, signalperiod=9）
  - **KDJ**：K / D / J（J = 3K - 2D 自算，参考 07 §3）
  - **RSI**：RSI6 / RSI12 / RSI24（timeperiod 可配）
  - **WR**：威廉指标（WILLR，timeperiod=14）
  - **CCI**：（timeperiod=14）
  - **DMI**：ADX / +DI / -DI 三线（timeperiod=14，三个 talib 函数组合，参考 07 §3）
  - **无**（默认）
- 副图切换不重新拉 K 线数据，仅前端重算。
- 副图与主图 X 轴对齐，十字光标联动。

#### 3.5 画线工具

- 基础版画线工具，工具栏位于主图左上角：
  - **趋势线**：两点连线
  - **水平线**：水平价位线
  - **平行通道**：两条平行线
- 画线状态保存在前端（localStorage 或 sessionStorage），按 `{code}` + 周期 key 隔离，刷新页面不丢失。
- 不做更复杂的画线（斐波那契、波浪标注等），二期评估。

#### 3.6 复权切换

- 三档切换：**前复权 / 后复权 / 不复权**（默认前复权）。
- 通过主图右上角的单选按钮组切换。
- 后端 `/api/daily-quote` 需支持 `adj` 参数（`qfq`/`hfq`/`none`），默认 `qfq`。
- 复权计算依赖 `adj_factor` 表：前复权 = `price × adj_factor / 最新 adj_factor`，后复权 = `price × adj_factor`，不复权 = `price` 原值。
- 切换复权时主图与所有主图指标重新计算（因 MA/BOLL 等指标基于复权后价格）。

#### 3.7 十字光标 + OHLCV 浮窗

- 鼠标移动到 K 线图任意位置，显示十字光标（垂直线 + 水平线）。
- 浮窗显示当前光标对应的 K 线 OHLCV：
  - 日期：`YYYY-MM-DD`（日/周/月）或 `YYYY-MM-DD HH:MM`（60 分）
  - 开 / 高 / 低 / 收：2 位小数
  - 成交量：手数（除以 100，tushare vol 单位为手）或股数，按落地 spec 确认
  - 涨跌幅：相对前一根收盘价的百分比
- 十字光标与副图联动（主图光标位置对应副图同 X 轴位置的指标值）。

#### 3.8 MA 金叉死叉时间轴

- 主图下方（副图之上或之下，落地 spec 确认）加一个横向时间轴。
- 标记 MA5/10/20/60 的金叉（短周期上穿长周期）与死叉（短周期下穿长周期）事件点。
- 金叉用向上箭头 + 红色（按主题），死叉用向下箭头 + 绿色（按主题）。
- 鼠标悬浮事件点显示：`YYYY-MM-DD MA5 上穿 MA20 金叉`。
- 时间轴范围与主图 K 线范围一致，周期切换时同步刷新。
- 此项合并自原"K 线分析"菜单的"MA 金叉死叉"功能，不再单独做菜单。

---

### FR-4 Tab 2 基本面

**Tab id**：`fundamental`。懒加载，首次切到 Tab 时拉数据。

#### 4.1 PE / PB / PS 历史分位图（5 年）

- **图表类型**：ECharts 折线图 + 当前分位标注线。
- **数据来源**：`daily_basic` 表，按 `{code}` 查询近 5 年的 `pe_ttm` / `pb` / `ps_ttm`。
- **展示**：
  - 三条折线：PE TTM / PB / PS TTM（可单独切换显隐）。
  - 当前值用圆点高亮，旁边标注"当前 PE = X，处于 5 年 Y% 分位"。
  - 分位计算：(当前值 - 5 年最小值) / (5 年最大值 - 5 年最小值)，负值跳过。
- **空数据兜底**：5 年内数据不足时显示"数据不足，无法计算分位"。

#### 4.2 ROE / ROA / 毛利率 / 净利率 趋势图

- **图表类型**：ECharts 折线图。
- **数据来源**：`fina_indicator` 表，按 `{code}` 查询近 5 年（20 期季报）的 `roe` / `roa` / `grossprofit_margin` / `netprofit_margin`。
- **展示**：
  - 四条折线，Y 轴为百分比。
  - X 轴为财报期 `end_date`（YYYY-MM-DD）。
  - 鼠标悬浮显示各指标当期值。

#### 4.3 财务三大表摘要（最近 4 期）

- **展示形式**：3 个卡片或 3 个折叠区块（利润表 / 资产负债表 / 现金流量表），每表展示最近 4 期数据。
- **数据来源**：
  - 利润表 `income`：营收（`total_revenue`）、净利润（`n_income`）、归母净利润（`n_income_attr_parent`）
  - 资产负债表 `balancesheet`：总资产（`total_assets`）、总负债（`total_liab`）、负债率（计算值 = total_liab / total_assets）
  - 现金流量表 `cashflow`：经营现金流（`n_cashflow_act`）、投资现金流（`n_cashflow_inv_act`）、筹资现金流（`n_cash_flows_fnc_act`）
- **表格列**：指标名 / 4 期数值（最近 4 期 end_date 列） / 同比变化（最近一期相对去年同期）。
- 同比为空（去年同期数据缺失）时显示"--"。

#### 4.4 业绩预告 / 快报时间轴

- **图表类型**：横向时间轴（ECharts 或自定义 div 布局）。
- **数据来源**：
  - 业绩预告 `forecast`：`ann_date` / `end_date` / `type`（预增/预减/扭亏/续亏/略增/略减） / `p_change_min` / `p_change_max`
  - 业绩快报 `express`：`ann_date` / `end_date` / `revenue` / `n_income` / `yoy_net_profit`
- **展示**：
  - 时间轴按 `ann_date` 排序，每个事件点显示类型图标 + 涨跌幅区间。
  - 鼠标悬浮显示详情卡片。
  - 预增/略增用红色（按主题），预减/略减用绿色，扭亏用黄色，续亏用灰色。

#### 4.5 分红送转历史

- **展示形式**：表格（复用已有 `DividendController` 接口，前端直接调）。
- **列**：公告日 / 股权登记日 / 除权除息日 / 方案（每 10 股派 X 元转 X 股送 X 股）/ 分红总额。
- 按公告日倒序，展示全部历史记录（不分页或分页 20 条/页）。

#### 4.6 股东人数变化曲线（新增 `stk_holdernumber`）

- **图表类型**：ECharts 折线图 + 柱状图组合。
- **数据来源**：`stk_holdernumber` 表（由 tushare `stk_holdernumber` 接口采集），按 `{code}` 查询 `end_date` / `holder_num`。
- **展示**：
  - 折线：股东人数变化（Y 轴左）。
  - 柱状：环比变化率（Y 轴右，%）。
  - 鼠标悬浮显示 `end_date` / `holder_num` / 环比变化。
- **解读提示**：股东人数下降通常意味着筹码集中，上升意味着筹码分散，在图表下方加一行小字提示。

---

### FR-5 Tab 3 资金面

**Tab id**：`moneyflow`。懒加载。支持 URL `?tab=moneyflow` 直接定位。

#### 5.1 个股资金流向图（30 日主力净流入）

- **图表类型**：ECharts 柱状图。
- **数据来源**：`stock_moneyflow` 表（由 tushare `moneyflow` 接口采集），按 `{code}` 查询近 30 日的 `trade_date` / `net_mf_amount`。
- **展示**：
  - 柱状图：每日主力净流入金额（红正绿负，按主题）。
  - X 轴：trade_date（YYYY-MM-DD）。
  - Y 轴：金额（万元）。
  - 鼠标悬浮显示当日：主力净流入 / 大单 / 中单 / 小单 / 特大单净额。
- 顶部加一行汇总："近 30 日累计主力净流入 X 亿元，N 日净流入 / (30-N) 日净流出"。

#### 5.2 北向持股比例曲线

- **图表类型**：ECharts 折线图。
- **数据来源**：`hk_hold` 表（由 tushare `hk_hold` 接口采集），按 `{code}` 查询 `trade_date` / `vol` / `ratio`。
- **展示**：
  - 折线：持股比例（Y 轴，%，保留 3 位小数因北向持股比例通常很小）。
  - X 轴：trade_date。
  - 鼠标悬浮显示当日：持股量（股）/ 持股比例 / 占总股本比例。
- **范围**：默认近 1 年，可切换近 3 月 / 近 1 年 / 全部。
- 不做"每日净买入柱状图"（`moneyflow_hsgt` 不存在）。

#### 5.3 龙虎榜上榜次数（近 1 年）

- **展示形式**：上方汇总卡片 + 下方明细表。
- **数据来源**：`top_list` + `top_inst` 表（均由 tushare 对应接口采集）。
- **汇总卡片**：
  - 近 1 年上榜次数（计数）。
  - 最近一次上榜日期 + 原因（`reason` 字段）。
  - 近 1 年净买入额汇总（`net_amount` 累加）。
- **明细表**：
  - 列：上榜日 / 收盘价 / 涨跌幅 / 净买入额 / 上榜原因。
  - 按上榜日倒序，分页 20 条/页。
  - 点击行展开营业部明细（`top_inst` 数据）：买入席位 / 卖出席位 / 净买入额 / 席位类型（机构/营业部）。

#### 5.4 大宗交易明细（近 3 个月）

- **展示形式**：表格。
- **数据来源**：`block_trade` 表（由 tushare `block_trade` 接口采集），按 `{code}` 查询近 3 个月的 `trade_date` / `price` / `vol` / `amount` / `buyer_name` / `seller_name`。
- **列**：成交日 / 成交价 / 收盘价 / 溢价率（计算值 = (成交价 - 收盘价) / 收盘价 × 100%） / 成交量（手）/ 成交额（万元）/ 买方 / 卖方。
- 溢价率 > 0 红色，< 0 绿色（按主题）。
- 按成交日倒序，分页 20 条/页。
- 顶部加汇总："近 3 个月共 N 笔大宗交易，累计成交额 X 亿元"。

---

### FR-6 Tab 4 风险提示（原 Tab 5 顺延）

**Tab id**：`risk`。懒加载。

#### 6.1 涨跌停价（今日）

- **展示形式**：横向卡片，三列：涨停价 / 现价 / 跌停价。
- **数据来源**：`stock_stk_limit` 表，按 `{code}` + 今日 `trade_date` 查询 `up_limit` / `down_limit`。
- 现价取 `daily_quote` 最新收盘价。
- 涨停价红色，跌停价绿色（按主题）。
- 若今日无数据（非交易日或停牌），显示"今日非交易日/停牌"。

#### 6.2 历史停牌记录

- **展示形式**：表格。
- **数据来源**：`stock_suspend_d` 表，按 `{code}` 查询 `suspend_date` / `resume_date` / `ann_date` / `suspend_reason` / `reason_type`。
- **列**：停牌日 / 复牌日 / 停牌天数（计算值）/ 停牌原因 / 原因类型。
- 按停牌日倒序，分页 20 条/页。
- 顶部加汇总："历史停牌 N 次，累计停牌 X 天"。

#### 6.3 ST 戴帽 / 摘帽历史

- **展示形式**：时间轴 + 表格。
- **数据来源**：`stock_namechange` 表，按 `{code}` 查询 `start_date` / `end_date` / `name` / `change_reason`。
- **筛选**：仅展示 `name` 含 "ST" 或 `change_reason` 含 "ST"/"退市风险" 的记录。
- **展示**：
  - 时间轴：每个名称变更事件点，标注"戴帽 ST"/"摘帽"/"名称变更"。
  - 表格：起始日 / 结束日 / 变更后名称 / 变更原因。
- 若股票从未 ST，显示"该股票无 ST 历史"。

#### 6.4 减持公告（基于 `stk_holdertrade`）

- **展示形式**：表格。
- **数据来源**：`stk_holdertrade` 表（由 tushare `stk_holdertrade` 接口采集），按 `{code}` 查询 `ann_date` / `end_date` / `holder_name` / `holder_type` / `in_de` / `change_vol` / `change_ratio` / `after_share` / `after_ratio`。
- **列**：公告日 / 变动截止日 / 股东名 / 股东类型（高管/股东）/ 变动方向（增持/减持）/ 变动数量（股）/ 变动比例 / 变动后持股 / 变动后比例。
- 减持用红色（按主题），增持用绿色。
- 按公告日倒序，分页 20 条/页。
- 顶部加汇总："近 1 年共 N 笔减持公告，累计减持 X 万股"。

#### 6.5 ❌ 限售解禁明细（明确砍掉）

- **不做原因**：`share_float` 接口需 3000 积分，超出本项目 ≤2000 积分约束。
- **替代方案**：用 `stk_holdertrade`（股东增减持，FR-6.4）部分替代"减持公告"维度，但无法展示未来解禁明细。
- 后续若 tushare 降低 `share_float` 积分要求或项目升级积分，再补此项。

---

### FR-7 后端 Controller

#### 7.1 HkHoldController

- **路径**：`controller/HkHoldController.java`
- **查询接口**：
  - `GET /api/hk-hold?tsCode={code}&startDate={startDate}&endDate={endDate}`：查个股持股明细列表。
  - 入参：`tsCode`（必填）、`startDate` / `endDate`（可选，默认近 1 年）。
  - 出参：`ApiResponse<List<HkHoldDTO>>`，字段含 `tradeDate` / `code` / `name` / `vol` / `ratio` / `tsCode` / `exchangeId`。
- **Service**：复用已落地的 `HkHoldService`（含 Tushare `hk_hold` 接口拉取 + 落库），本 Controller 只暴露查询接口，不重复实现拉取。
- **校验**：`tsCode` 格式校验，非法返回 400。

#### 7.2 StkHoldertradeController

- **路径**：`controller/StkHoldertradeController.java`
- **查询接口**：
  - `GET /api/stk-holdertrade?tsCode={code}&startDate={startDate}&endDate={endDate}`：查股东增减持列表。
  - 入参：`tsCode`（必填）、`startDate` / `endDate`（可选，默认近 1 年）。
  - 出参：`ApiResponse<List<StkHoldertradeDTO>>`，字段含 `tsCode` / `annDate` / `endDate` / `holderName` / `holderType` / `inDe` / `changeVol` / `changeRatio` / `afterShare` / `afterRatio`。
- **Service**：复用已落地的 `StkHoldertradeService`（由 tushare `stk_holdertrade` 接口拉取落库）。

#### 7.3 StkHoldernumberController

- **路径**：`controller/StkHoldernumberController.java`
- **查询接口**：
  - `GET /api/stk-holdernumber?tsCode={code}&limit={limit}`：查股东人数变化列表。
  - 入参：`tsCode`（必填）、`limit`（可选，默认 20，即最近 20 期）。
  - 出参：`ApiResponse<List<StkHoldernumberDTO>>`，字段含 `tsCode` / `annDate` / `endDate` / `holderNum`。
- **Service**：复用已落地的 `StkHoldernumberService`（由 tushare `stk_holdernumber` 接口拉取落库）。

#### 7.4 已有表 Controller（需补齐）

以下 Controller 需补齐（对已有表暴露查询接口），本 PRD 只声明前端依赖，不重复定义：

| Controller | 查询接口 | 用途 |
|---|---|---|
| `DailyBasicController` | `GET /api/daily-basic?tsCode={code}&startDate=...&endDate=...` | 顶部摘要条 PE/PB/换手率/市值；Tab 2 PE/PB/PS 分位图 |
| `FinaIndicatorController` | `GET /api/fina-indicator?tsCode={code}&limit=20` | Tab 2 ROE/ROA/毛利率/净利率趋势图 |
| `IncomeController` / `BalancesheetController` / `CashflowController` | `GET /api/income?tsCode={code}&limit=4` 等 | Tab 2 财务三大表摘要 |
| `ForecastController` / `ExpressController` | `GET /api/forecast?tsCode={code}` 等 | Tab 2 业绩预告/快报时间轴 |
| `StockStkLimitController` | `GET /api/stock-stk-limit?tsCode={code}&tradeDate=...` | Tab 4 涨跌停价 |
| `StockSuspendDController` | `GET /api/stock-suspend-d?tsCode={code}` | Tab 4 历史停牌记录 |
| `StockNamechangeController` | `GET /api/stock-namechange?tsCode={code}` | Tab 4 ST 戴帽/摘帽历史 |
| `DividendController`（已有） | `GET /api/dividend?tsCode={code}` | Tab 2 分红送转历史 |
| `DailyQuoteController`（已有，需扩展 period/adj 参数） | `GET /api/daily-quote?tsCode={code}&period=D&adj=qfq` | Tab 1 K 线主数据 |

---

### FR-8 前端文件

#### 8.1 stock-detail.html

- 路径：`src/main/resources/templates/pages/stock-detail.html`
- Thymeleaf SSR 模板，套用 `fragments/common.html` 主框架。
- 结构：
  - 顶部摘要条容器（`<div id="stock-summary-bar">`）
  - Tab 切换栏（4 个 Tab 按钮）
  - Tab 内容区（4 个 `<div class="tab-pane">`，按懒加载策略初始只渲染 Tab 1）
- 引入 `stock-detail.js` + `stock-detail.css`（通过 `fragments/scripts` 引入）。

#### 8.2 stock-detail.js

- 路径：`src/main/resources/static/js/stock-detail.js`
- 模块化封装（IIFE 或 ES Module，按现有规范）。
- 主要函数：
  - `initStockDetail(code)`：页面入口，拉顶部摘要条数据 + 默认 Tab。
  - `loadSummaryBar(code)`：FR-2 顶部摘要条。
  - `initKlineTab(code)`：FR-3 Tab 1 K 线技术面（复用 `renderKline`，扩展周期/指标/画线/复权/十字光标/金叉时间轴）。
  - `initFundamentalTab(code)`：FR-4 Tab 2 基本面（懒加载）。
  - `initMoneyflowTab(code)`：FR-5 Tab 3 资金面（懒加载）。
  - `initRiskTab(code)`：FR-6 Tab 4 风险提示（懒加载）。
  - Tab 切换事件处理：首次切到某 Tab 时调对应 init 函数。
- URL `?tab=` 参数解析：进入页面时根据参数直接切到指定 Tab 并触发其 init。

#### 8.3 stock-detail.css

- 路径：`src/main/resources/static/css/stock-detail.css`
- 样式范围：
  - 顶部摘要条布局（左右分栏、字段间距、涨跌色）
  - Tab 切换栏样式（与现有 `dashboard.css` 风格一致）
  - K 线图区域尺寸（主图 + 副图 + 时间轴 + 画线工具栏）
  - 各 Tab 卡片/表格/图表容器样式
  - 响应式：窄屏（< 1200px）时顶部摘要条字段换行，Tab 内容区自适应。
- 主题适配：通过 CSS 变量与 `theme.css` 联动，支持 azure/mist/cyber 三主题切换不破图。

---

## 4. 非功能需求

### 4.1 页面性能

- **Tab 懒加载**：Tab 2/3/4 首次切换时才拉数据，避免首屏加载所有数据。
- **顶部摘要条优先**：页面进入先加载摘要条（FR-2），Tab 1 K 线随后并行加载。
- **K 线数据缓存**：周期/复权切换时，若数据已拉过则用前端缓存（按 `{code}_{period}_{adj}` key），避免重复请求；切换其他股票时清空缓存。
- **图表渲染性能**：K 线图默认加载近 250 根（约 1 年日 K），前端虚拟滚动或分页加载更多历史（下拉触底加载前 250 根）。
- **长列表分页**：龙虎榜明细、大宗交易明细、停牌记录、减持公告等表格分页 20 条/页，避免一次渲染大量 DOM。
- **大表查询加索引**：后端 `hk_hold` / `stock_moneyflow` / `top_list` / `block_trade` / `stk_holdertrade` / `stk_holdernumber` 等表需在 `ts_code` + `trade_date` / `ann_date` 上建联合索引（在数据采集任务的 schema 中定义）。

### 4.2 K 线图主题统一

- 所有 ECharts 与 LightweightCharts 实例必须用 `ChartsTheme` 注册的主题，不得硬编码颜色。
- 主题切换（azure/mist/cyber）时，所有图表实例需响应式更新（监听主题切换事件，调 `chart.setOption` 或重建实例）。
- 涨跌色规则按 `theme.css` 定义，不在这三个 Tab 内重复定义。

### 4.3 复权计算正确性

- 前复权公式：`adj_price = price × adj_factor / 最新 adj_factor`。
- 后复权公式：`adj_price = price × adj_factor`。
- 不复权：`adj_price = price`。
- 复权计算可在后端 `/api/daily-quote` 内部完成（推荐，避免前端重复拉 `adj_factor`），或在 `stock-detail.js` 内部完成（需额外拉 `adj_factor` 列表）。落地 spec 二选一。
- **正确性校验**：随机选 5 只票，前复权后最新一日的价格应等于不复权最新一日的价格（因 `最新 adj_factor / 最新 adj_factor = 1`）；后复权最早一日的价格应等于不复权最早一日的价格（因早期 `adj_factor` 通常接近 1）。

### 4.4 数据完整性降级

- 任一接口拉取失败时，对应卡片/图表显示"数据加载失败，请刷新重试"+ 重试按钮，不阻塞其他卡片加载。
- 股票停牌期间无 `daily_quote` 数据时，Tab 1 K 线显示最近一根有效 K 线 + 提示"该股票停牌中"。
- 新股（上市不足 5 年）无足够历史数据时，Tab 2 分位图显示"上市不足 5 年，仅展示 N 年数据"。
- 北向资金未覆盖的股票（如科创板部分票）`hk_hold` 表无数据时，Tab 3 北向持股比例曲线显示"该股票暂无北向持股数据"。

### 4.5 浏览器兼容性

- 与现有页面一致：Chrome 90+ / Edge 90+ / Firefox 90+，不做 IE 兼容。
- 移动端不做主动适配（响应式仅窄屏字段换行，不做完整移动端交互）。

---

## 5. 验收标准

### 5.1 路由与页面骨架

| 验收项 | 验证方法 |
|---|---|
| 路由可访问 | 浏览器访问 `/page/stock-detail/000001.SZ`，返回 200 且页面正常渲染 |
| 不在侧边栏 | 访问 `/`，侧边栏菜单不应有"个股诊断"项 |
| Tab 切换 | 4 个 Tab 切换正常，URL 同步 `?tab=` 参数 |
| 懒加载 | 首次切到 Tab 2/3/4 时才发起对应接口请求（Network 面板验证） |
| 404 兜底 | 访问 `/page/stock-detail/INVALID.CODE`，展示"未找到该股票"占位 |
| 非法代码 | 后端 `/api/daily-quote?tsCode=INVALID` 返回 400 |

### 5.2 顶部摘要条

| 验收项 | 验证方法 |
|---|---|
| 字段完整 | 访问 `/page/stock-detail/000001.SZ`，顶部摘要条展示：股票名/代码/最新价/涨跌额/涨跌幅/行业/市值/PE/PB/换手率 共 10 项 |
| 涨跌色 | 涨跌幅 > 0 显示涨色，< 0 显示跌色（按主题） |
| 加自选 | 点击"加自选"，调 `POST /api/watchlist`，成功后按钮置灰显示"已加自选" |
| 价格提醒 | 本模块阶段按钮点击 toast"价格提醒功能即将上线"（占位）；自选股（014）价格提醒落地后改为弹模态框 |

### 5.3 Tab 1 K 线技术面

| 验收项 | 验证方法 |
|---|---|
| 默认展示 | 进入页面默认停在 Tab 1，渲染日 K 线图 |
| 周期切换 | 点击"日/周/月/60 分"按钮，K 线切换为对应周期 |
| 主图指标 | 切换 MA5/10/20/60、EMA12/26、BOLL、SAR 显隐，主图正确叠加 |
| 副图指标 | 下拉切 MACD/KDJ/RSI/WR/CCI/DMI，副图正确切换 |
| 画线工具 | 画趋势线/水平线/平行通道，刷新页面后画线保留（localStorage） |
| 复权切换 | 切前复权/后复权/不复权，K 线价格正确变化（参考 4.3 校验） |
| 十字光标 | 鼠标移动显示十字光标 + OHLCV 浮窗，与副图联动 |
| 金叉时间轴 | MA5/10/20/60 金叉死叉事件点正确标记 |

### 5.4 Tab 2 基本面

| 验收项 | 验证方法 |
|---|---|
| PE/PB/PS 分位图 | 切到 Tab 2，5 年分位图正常加载，当前值标注正确 |
| ROE/ROA/毛利率/净利率趋势 | 折线图 4 条线正常展示 |
| 财务三大表摘要 | 3 个卡片/折叠区块，各展示最近 4 期数据，同比变化正确 |
| 业绩预告/快报时间轴 | 时间轴正确展示事件点，类型图标与颜色正确 |
| 分红送转历史 | 表格正常加载，按公告日倒序 |
| 股东人数曲线 | 折线 + 柱状组合图正常加载，环比变化率正确 |

### 5.5 Tab 3 资金面

| 验收项 | 验证方法 |
|---|---|
| 个股资金流向图 | 30 日主力净流入柱状图正常加载，红正绿负 |
| 北向持股比例曲线 | 折线图正常加载，Y 轴 3 位小数 |
| 龙虎榜上榜次数 | 汇总卡片 + 明细表正常加载，点击行展开营业部明细 |
| 大宗交易明细 | 表格正常加载，溢价率计算正确，颜色正确 |

### 5.6 Tab 4 风险提示

| 验收项 | 验证方法 |
|---|---|
| 涨跌停价 | 三列卡片正常展示涨停价/现价/跌停价 |
| 历史停牌记录 | 表格正常加载，停牌天数计算正确 |
| ST 戴帽摘帽历史 | 时间轴 + 表格正常加载，筛选正确（仅含 ST/退市风险） |
| 减持公告 | 表格正常加载，增持/减持颜色正确 |
| 无限售解禁 | Tab 4 不应有"限售解禁"区块 |

### 5.7 后端接口

| 验收项 | 验证方法 |
|---|---|
| HkHoldController | `curl GET /api/hk-hold?tsCode=000001.SZ` 返回 200 + 持股明细列表 |
| StkHoldertradeController | `curl GET /api/stk-holdertrade?tsCode=000001.SZ` 返回 200 + 增减持列表 |
| StkHoldernumberController | `curl GET /api/stk-holdernumber?tsCode=000001.SZ&limit=20` 返回 200 + 股东人数列表 |
| 参数校验 | `curl GET /api/hk-hold?tsCode=INVALID` 返回 400 |
| DailyQuote 扩展 | `curl GET /api/daily-quote?tsCode=000001.SZ&period=W&adj=qfq` 返回 200 + 周线前复权数据 |

### 5.8 端到端

```
完整使用路径验证：
1. 行情中心（013）涨幅榜 -> 点击某只票代码 -> 跳转 /page/stock-detail/{code}，默认 Tab 1
2. 顶栏搜索框输入"000001" -> SearchSuggest 联想 -> 选中 -> 跳转个股诊断
3. Tab 1 切 60 分周期 -> 开 MACD 副图 -> 画一条趋势线 -> 刷新页面验证画线保留
4. Tab 2 看 PE 分位图 -> 看 ROE 趋势 -> 看股东人数曲线
5. Tab 3 看主力净流入柱状图 -> 看北向持股比例 -> 点龙虎榜明细展开
6. Tab 4 看涨跌停价 -> 看 ST 历史 -> 看减持公告
7. 点"加自选" -> 跳转自选股（014）列表验证已加入
8. 切 azure/mist/cyber 三主题 -> 所有图表颜色同步变化，无破图
```

---

## 6. 实施建议

### 6.1 建议开发顺序

| 子任务 | 内容 | 依赖 | 说明 |
|---|---|---|---|
| **B1** | 后端：HkHoldController + StkHoldertradeController + StkHoldernumberController | `hk_hold` / `stk_holdertrade` / `stk_holdernumber` 表已落库（由数据采集任务完成） | 三个查询接口，复用已落地的 Service，仅暴露 Controller 层 |
| **B2** | 前端：stock-detail.html + stock-detail.js + stock-detail.css 页面骨架 | B1 完成 | 4 Tab 框架 + 路由 + Tab 切换 + 懒加载机制，Tab 内容先占位 |
| **B3** | 前端：Tab 1 K 线技术面 | B2 完成 | 复用 `renderKline`，扩展周期/指标/画线/复权/十字光标/金叉时间轴 |
| **B4** | 前端：Tab 2 基本面 | B2 完成 + DailyBasic/FinaIndicator/Income/Balancesheet/Cashflow/Forecast/Express Controller 已补齐 | PE/PB 分位 + ROE 趋势 + 三大表 + 股东人数曲线 |
| **B5** | 前端：Tab 3 资金面 | B2 完成 + `stock_moneyflow` / `hk_hold` / `top_list` / `block_trade` 表已落库 | 个股资金流向 + 北向持股 + 龙虎榜 + 大宗交易 |
| **B6** | 前端：Tab 4 风险提示 | B2 完成 + StockStkLimit/StockSuspendD/StockNamechange Controller 已补齐 + B1 的 StkHoldertradeController | 涨跌停 + 停牌 + ST + 减持（限售解禁砍掉） |
| **B7** | 前端：顶部摘要条 + 加自选 + 价格提醒按钮 | B2 完成 + DailyBasic Controller 已补齐 | 顶部 10 字段 + 按钮（价格提醒本模块阶段占位） |

> B3-B7 可并行启动（除 B2 必须先完成外），但建议按 B3 -> B4 -> B5 -> B6 -> B7 的顺序，因 Tab 1 是默认 Tab，体验最直观。

### 6.2 与其他模块的关系

| 关联模块 | 关系 |
|---|---|
| **数据采集** | **强前置**：本 PRD 所有 FR 依赖数据采集任务落地的 7 张新表 + 13 张已有表的 Controller 补齐。数据采集任务未完成前，本 PRD 不可启动实施。 |
| **板块行情（015）** | 弱关联：板块行情的成分股展开后，股票代码链接到本模块。本模块不依赖板块行情（015）。 |
| **资金流向（016）** | 弱关联：资金流向（016）页面 Tab A 主力净流入 Top 10 的股票链接到本模块 `?tab=moneyflow`。本模块不依赖资金流向（016），但 Tab 3 数据来源与资金流向（016）重叠（同表不同视图）。 |
| **现有模块补齐（012 仪表盘 / 013 行情中心 / 014 自选股）** | 强关联：行情中心（013）涨幅榜、自选股（014）列表的股票代码链接是本模块的下钻入口；自选股（014）价格提醒落地后，本模块顶部"价格提醒"按钮才能从占位改为真实功能。建议本模块先交付页面本体，下钻入口在现有模块补齐时统一落地。 |
| **联调与优化** | 全局联调：本模块的 K 线图主题统一、性能优化、下钻跳转链路验证在联调与优化阶段统一收口。 |

### 6.3 风险与应对

| 风险 | 应对 |
|---|---|
| 数据采集任务延期导致本模块无法启动 | B2 页面骨架可先于数据采集任务完成（Tab 内容占位），等数据采集任务落地后再填充 B3-B7 |
| `hk_hold` 表数据量大（全市场沪深股通持股明细每日全量），查询性能差 | 后端 Controller 必须按 `ts_code` + `trade_date` 联合索引查询，前端默认只查近 1 年 |
| 60 分 K 线数据未接入 `daily_quote` 表 | Tab 1 周期按钮中"60 分"置灰并 tooltip 提示；二期评估接入分钟数据 |
| 股票停牌期间多 Tab 数据缺失 | 各 Tab 独立降级提示，不阻塞其他 Tab |
| 主题切换导致 ECharts 图表颜色错乱 | 所有图表实例必须通过 `ChartsTheme` 注册主题，监听主题切换事件响应式更新 |

### 6.4 落地前置条件清单

在启动本模块实施前，确认以下前置条件全部满足：

- [ ] 数据采集任务的 7 张新表（`hk_hold` / `stock_moneyflow` / `top_list` / `top_inst` / `block_trade` / `stk_holdertrade` / `stk_holdernumber`）schema + DO + Mapper + TushareApiEnum + DTO + Service + 定时拉取任务全部落地（由 tushare 对应接口采集）。
- [ ] 13 张已有表的 Controller 全部对前端暴露查询接口（`DailyBasicController` / `FinaIndicatorController` / `IncomeController` / `BalancesheetController` / `CashflowController` / `ForecastController` / `ExpressController` / `StockStkLimitController` / `StockSuspendDController` / `StockNamechangeController` 等）。
- [ ] `DailyQuoteController` 已扩展支持 `period`（D/W/M/60min）与 `adj`（qfq/hfq/none）参数。
- [ ] `DividendController` 已有（无需新增）。
- [ ] `WatchlistController` 已有 `POST /api/watchlist` 接口（顶部"加自选"按钮依赖）。
- [ ] 前端 `common.js` / `search-suggest.js` / `charts-theme.js` / `dashboard.js`（含 `renderKline`）已存在且可复用。

---

## 7. 附录：裁剪决策记录

| 原计划项 | 处理 | 原因 | 替代 |
|---|---|---|---|
| Tab 4 公司资料 F10 | ❌ 砍掉 | `stock_company` 接口不在 tushare 接口汇总表，不存在 | 后续 tushare 增加低积分 F10 接口再补；页面顺延为 4 Tab |
| 风险提示 Tab 限售解禁明细 | ❌ 砍掉 | `share_float` 需 3000 积分，超 ≤2000 积分约束 | 用 `stk_holdertrade` 部分替代"减持公告"项 |
| K 线分析独立菜单 | ❌ 移除 | K 线分析不是独立功能，老股民看票时一定联动看 K 线 + 基本面 + 资金 + 板块 | 合并到本模块 Tab 1 |
| 北向每日净买入柱状图 | ❌ 砍掉 | `moneyflow_hsgt` 接口不存在 | Tab 3 用 `hk_hold` 做持股比例曲线 |
| 概念板块归属 | ❌ 不展示 | `concept` / `concept_detail` 接口不存在 | 顶部摘要条行业字段用 `stock_basic` 的申万一级行业 |

> 以上裁剪决策与 [主要功能模块整体设计计划.md](./../../.trae/documents/主要功能模块整体设计计划.md) §1.2、§3.3、§7、§10 保持一致，无新增裁剪。
