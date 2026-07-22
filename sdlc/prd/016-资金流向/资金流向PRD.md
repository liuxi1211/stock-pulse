# 资金流向模块 PRD

> **模块标识**：016
> **状态**：规划中
> **来源**：拆分自 [主要功能模块整体设计计划.md](../../.trae/documents/主要功能模块整体设计计划.md) §3.2
> **原则**：严格基于 Tushare ≤2000 积分接口能力；数据单源性（不引入派生聚合数据）；北向资金用 `hk_hold` 替代原 `moneyflow_hsgt`；本文仅做"需求级"定稿，不写代码实现。

---

## 0. 拆分说明（为什么单独立项）

本 PRD 把资金流向从主要功能模块整体设计计划中拆出独立成稿，因为：

- **数据依赖清晰独立**：本模块依赖 7 张表（`moneyflow` / `hk_hold` / `top_list` / `top_inst` / `block_trade` / `margin` / `margin_detail`），均由 Tushare 对应接口采集落库，本模块只负责"查询 Controller + 前端页面"。
- **页面形态独立**：`/page/moneyflow` 是独立菜单页，不与板块行情 / 个股诊断耦合（个股诊断 Tab 3 资金面会复用本模块的后端查询接口，但页面解耦）。
- **裁剪决策需显式记录**：原 6 Tab 砍 Tab B 板块资金流向（`moneyflow_dc` 不存在）+ 北向资金改用 `hk_hold` 替代（`moneyflow_hsgt` / `hsgt_top10` 不存在），这些裁剪决策影响接口设计与前端组件，需在独立 PRD 里固化。

> **不在本 PRD 范围**：板块行情（015）/ 个股诊断（017）/ 仪表盘北向卡片（已砍掉，见总计划 §2.1 D6）/ 量化研究模块。

---

## 1. 背景与目标

### 1.1 业务背景

A 股老股民做决策时，"看主力"是必备视角。资金流向模块提供四个维度的主力资金追踪：

1. **个股资金流向**：主力 / 大单 / 中单 / 小单 / 特大单净流入排行，回答"今天主力在买谁"。
2. **北向资金**：沪深股通持股比例变化，回答"外资在加仓谁"（原 `moneyflow_hsgt` 接口不存在，改用 `hk_hold` 沪深股通持股明细替代，仅做持股比例曲线，不做净买入柱状图）。
3. **龙虎榜**：当日上榜个股 + 营业部席位明细 + 知名游资/机构跟踪，回答"游资/机构今天在抢什么"。
4. **大宗交易**：当日大宗成交明细 + 溢价率分布，回答"机构大宗接货信号"。
5. **融资融券**：两融余额趋势 + 个股两融明细，回答"杠杆资金方向"。

### 1.2 总体目标

- **目标一**：提供 `/page/moneyflow` 独立菜单页，5 Tab 结构覆盖个股 / 北向 / 龙虎榜 / 大宗 / 两融五个维度，老股民一站式看主力。
- **目标二**：所有数据严格来自 Tushare ≤2000 积分接口，不引入派生聚合数据（如"个股 moneyflow 按行业聚合"不采用）。
- **目标三**：与个股诊断（017）的 Tab 3 资金面复用同一套后端查询接口，保证口径一致。
- **质量准绳**：`.trae/rules/系统设计理念(用法建议).md` + A 股实战派认可。

> **Tab 数量口径说明**：总计划 §1.1 / §3.2 标题写"4 Tab，原 6 Tab 砍 2"，但实际内容列出 5 个 Tab（A 个股 / B 北向 / C 龙虎榜 / D 大宗 / E 两融）。原 6 Tab 砍掉 Tab B 板块资金流向后为 5 Tab，北向资金改用 `hk_hold` 替代但 Tab 数不变。本 PRD 统一按 **5 Tab** 落地，总计划标题"4 Tab"系笔误。

### 1.3 非目标（明确不做）

| 不做项 | 原因 | 来源 |
|---|---|---|
| ❌ 板块资金流向 Tab（原 Tab B） | `moneyflow_dc` 接口不在 Tushare 接口汇总表，不引入派生聚合数据（数据单源性约束） | 总计划 §1.2 / §3.2 / §10.1 |
| ❌ 北向资金每日净买入柱状图 | `moneyflow_hsgt` 接口不存在，`hk_hold` 无净额字段（只有 vol / ratio） | 总计划 §3.2 / §10.1 |
| ❌ 北向资金累计净买入趋势线 | 同上，`hk_hold` 无累计净额字段 | 总计划 §3.2 |
| ❌ 北向十大成交股表格（原 `hsgt_top10`） | `hsgt_top10` 接口不存在，改为从 `hk_hold` 按 vol 排序聚合"十大重仓股" | 总计划 §3.2 / §10.1 |
| ❌ 仪表盘北向资金卡片 | 总计划 §2.1 D6 已砍掉（`moneyflow_hsgt` 不存在） | 总计划 §2.1 |
| ❌ 引入 akshare 或其他数据源补北向净额 | 数据单源性约束，所有数据严格来自 Tushare | 总计划 §7.2 |
| ❌ 分钟级资金流向 | 一期仅支持日线，分钟级在二期评估 | 对齐总计划 §7 前端技术栈约束 |
| ❌ 实时资金流推送 | 一期基于日线 T+1 数据，不做 websocket 实时推送 | 对齐总计划 §7 |

---

## 2. 依赖与前置

### 2.1 数据依赖

本模块依赖 7 张表，全部已注册 TushareApiEnum 并由 Tushare 对应接口定时拉取落库：

| # | 表名 | Tushare 接口 | 枚举名 | 积分 | 关键字段 | 本模块用途 |
|---|---|---|---|---|---|---|
| 1 | `stock_moneyflow` | `moneyflow` | `MONEYFLOW` | 2000 | ts_code, trade_date, buy_sm_amount/vol, sell_sm_amount/vol, buy_md_amount/vol, ..., net_mf_amount, net_mf_vol | Tab A 个股资金流向 |
| 2 | `hk_hold` | `hk_hold` | `HK_HOLD` | 2000起 | trade_date, code, name, vol, ratio, ts_code, exchange_id | Tab B 北向资金（替代 `moneyflow_hsgt`） |
| 3 | `top_list` | `top_list` | `TOP_LIST` | 2000 | trade_date, ts_code, name, close, pct_change, amount, l_buy, l_sell, l_buy_amount, l_sell_amount, net_amount, b_amount, s_amount, reason | Tab C 龙虎榜个股列表 |
| 4 | `top_inst` | `top_inst` | `TOP_INST` | 2000 | trade_date, ts_code, exalter, side, buy, buy_rate, sell, sell_rate, net_buy | Tab C 龙虎榜营业部席位明细 |
| 5 | `block_trade` | `block_trade` | `BLOCK_TRADE` | 2000 | trade_date, ts_code, name, price, vol, amount, buyer, seller, buyer_name, seller_name | Tab D 大宗交易 |
| 6 | `margin` | `margin` | `MARGIN` | 2000 | exchange_id, trade_date, rzye, rzmre, rzche, rqye, rqmcl, rzrqye | Tab E 融资融券汇总趋势 |
| 7 | `margin_detail` | `margin_detail` | `MARGIN_DETAIL` | 2000 | trade_date, ts_code, name, rzye, rqye, rzmre, rzche, rqmcl, rzrqye | Tab E 个股融资融券明细 |

> ⚠️ **北向资金替代说明**：原计划用 `moneyflow_hsgt`（北向资金每日净买入）+ `hsgt_top10`（北向十大成交股），两个接口均不在 Tushare 接口汇总表（总计划 §10.1 #6 / #7）。本模块改用 `hk_hold`（沪深股通持股明细，2000 起）替代：
> - `hk_hold` 提供 `vol`（持股量）+ `ratio`（持股比例），**无净额字段**，故不做"每日净买入柱状图"与"累计净买入趋势线"。
> - "十大重仓股"从 `hk_hold` 按 `vol` 降序聚合，非原 `hsgt_top10` 接口。

### 2.2 前端组件复用

| 组件 | 来源 | 复用方式 |
|---|---|---|
| `StockApp.get/post/toast/confirm/alert/prompt` | `static/js/common.js` | 所有 Tab 的接口调用与提示 |
| `ChartsTheme` | `static/js/charts-theme.js` | Tab A TOP 10 柱状图 / Tab B 持股曲线 / Tab D 溢价率分布 / Tab E 余额趋势图 |
| 页面骨架 | `fragments/common.html` 的 `head/sidebar/topNavbar/scripts` | 5 Tab 页面套用 |
| 涨跌色 / 数字格式化 | `theme.css` + `StockApp.formatNumber/formatPercent` | 全局统一 |
| 表格分页组件 | 现有行情中心 / 自选股表格分页 | Tab A / C / D / E 长列表复用 |

### 2.3 跨模块依赖

| 依赖模块 | 关系 |
|---|---|
| **数据采集** | 强依赖：7 张表（见 §2.1）必须先由 Tushare 接口采集落库，本模块才能开发查询 Controller |
| **个股诊断（017）** | 弱依赖：个股诊断 Tab 3 资金面复用本模块的后端查询接口（moneyflow / hk_hold / top_list / block_trade）；本模块 Tab A 点击股票跳转 `/page/stock-detail/{code}?tab=moneyflow`，依赖个股诊断页面已上线 |
| **板块行情（015）** | 无依赖：板块行情独立页面，本模块不做板块维度资金流向 |
| **侧边栏菜单** | 本模块需在 `fragments/common.html` 侧边栏"主要功能"下新增第 5 项"资金流向" |

---

## 3. 功能需求

### FR-1 路由与页面骨架

**需求**：提供 `/page/moneyflow` 独立菜单页，位于侧边栏"主要功能"下第 5 项（仪表盘 / 行情中心 / 自选股 / 板块行情 / **资金流向**），5 Tab 结构。

**实现要点**：
- 后端 `PageController` 新增 `GET /page/moneyflow` 路由，返回 `pages/moneyflow.html` Thymeleaf 模板。
- `fragments/common.html` 侧边栏"主要功能"下新增菜单项：
  - 文案：资金流向
  - 图标：复用现有图标库（建议资金/流向相关 icon）
  - 链接：`th:href="@{/page/moneyflow}"`
  - 位置：板块行情之后，第 5 项
- 页面顶部标题："资金流向"，副标题可选"主力资金动向追踪"。
- 5 Tab 导航栏（Bootstrap nav-tabs）：
  - Tab A：个股资金流向（默认激活）
  - Tab B：北向资金
  - Tab C：龙虎榜
  - Tab D：大宗交易
  - Tab E：融资融券
- Tab 切换采用懒加载：首次切换到某 Tab 时才发起接口请求，切回不重复请求（除非用户主动刷新）。
- 顶部全局区：日期选择器（默认当日，支持回溯历史日期）+ 刷新按钮。

**验收**：访问 `/page/moneyflow` 看到 5 Tab 完整页面骨架，侧边栏第 5 项"资金流向"高亮。

---

### FR-2 Tab A 个股资金流向

**需求**：展示当日主力净流入 TOP 50 个股排行 + 顶部 TOP 10 柱状图 + 点击跳转个股诊断。

**数据来源**：`stock_moneyflow` 表（由 Tushare `moneyflow` 接口，2000 积分采集）。

**页面结构**：

#### 顶部：主力净流入 TOP 10 柱状图
- ECharts 横向柱状图，X 轴为主力净流入金额（`net_mf_amount`），Y 轴为股票名称。
- 红色表示净流入（正），绿色表示净流出（负）。
- 点击柱子跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`。

#### 下方：TOP 50 主力净流入排行表
- 表头列：股票代码 / 股票名称 / 最新价 / 涨跌幅 / 主力净流入 / 大单净流入 / 中单净流入 / 小单净流入 / 特大单净流入 / 操作。
- 字段映射（`stock_moneyflow` 表）：
  - 主力净流入 = `net_mf_amount`
  - 大单净流入 = `buy_md_amount - sell_md_amount`（或表内已有净额字段，以表实际落地为准）
  - 中单净流入 = `buy_sm_amount - sell_sm_amount`（小单中单字段以 moneyflow 接口实际字段为准）
  - 小单净流入 / 特大单净流入：对应 `buy_*_amount - sell_*_amount`
- 默认按 `net_mf_amount` 降序，支持点击表头切换升/降序。
- 涨跌幅按涨跌色（红涨绿跌）显示。
- 金额列用 `StockApp.formatNumber` 格式化（万元 / 亿元单位）。
- 分页：TOP 50 默认一页展示，可选"查看全部"切换分页模式。
- 操作列：「诊断」按钮，点击跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`。
- 股票代码列改为链接，点击同样跳转个股诊断。

**后端接口**（FR-7 详述）：
- `GET /api/moneyflow/top?tradeDate={date}&limit={50}&sortBy={net_mf_amount}&order={desc}` 返回 TOP 50 列表。

**降级**：当日无数据时显示"暂无资金流向数据，请稍后刷新"。

**验收**：Tab A 加载后柱状图与表格同步渲染，点击股票代码 / 「诊断」按钮跳转个股诊断页面。

---

### FR-3 Tab B 北向资金（用 hk_hold 替代）

**需求**：展示沪深股通持股比例变化曲线 + 十大重仓股聚合表格。**明确不做**每日净买入柱状图与累计净买入趋势线。

**数据来源**：`hk_hold` 表（由 Tushare `hk_hold` 接口，2000 起积分采集）。

**页面结构**：

#### 顶部：持股比例变化曲线
- ECharts 折线图，X 轴为 `trade_date`，Y 轴为持股比例（`ratio`，单位 %）。
- 三条曲线：
  - 沪股通持股比例（`exchange_id='SH'` 按 trade_date 聚合 sum(ratio)）
  - 深股通持股比例（`exchange_id='SZ'` 按 trade_date 聚合 sum(ratio)）
  - 北向合计（沪 + 深）
- 时间范围：默认近 30 个交易日，支持切换 7 日 / 30 日 / 90 日。
- 曲线 tooltip 显示当日具体数值。

#### 下方：十大重仓股表格
- 表头列：股票代码 / 股票名称 / 沪深股通 / 持股量（股）/ 持股比例（%）/ 持股市值（元，若 hk_hold 无此字段则不展示）。
- 数据来源：从 `hk_hold` 按 `vol` 降序取 TOP 10。
- 支持切换"沪股通 TOP 10"/"深股通 TOP 10"/"北向合计 TOP 10"三个子视图（通过 `exchange_id` 筛选）。
- 点击股票代码跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`。

**后端接口**（FR-7 详述）：
- `GET /api/hk-hold/ratio-trend?days={30}&exchangeId={SH|SZ|ALL}` 返回持股比例时序数据。
- `GET /api/hk-hold/top-holdings?tradeDate={date}&exchangeId={SH|SZ|ALL}&limit={10}` 返回十大重仓股。

**明确砍掉的组件**：
- ❌ 每日净买入柱状图：`hk_hold` 无净额字段，`moneyflow_hsgt` 接口不存在。
- ❌ 累计净买入趋势线：同上。
- ❌ 北向十大成交股表格（原 `hsgt_top10`）：接口不存在，改为从 `hk_hold` 聚合。

**降级提示**：`hk_hold` 数据未拉取到时，显示"北向资金持股数据暂未就绪（hk_hold 接口需 2000+ 积分），请检查后端调度任务"；并在 Tab 顶部加一行说明"本 Tab 基于 hk_hold 沪深股通持股明细，仅展示持股比例变化，不含净买入数据"。

**验收**：Tab B 加载后曲线渲染三条线，十大重仓股表格按 vol 降序展示，切换沪/深/合计子视图正确。

---

### FR-4 Tab C 龙虎榜

**需求**：当日龙虎榜个股列表 + 点击展开营业部明细 + 知名游资/机构席位跟踪。

**数据来源**：`top_list` 表（龙虎榜个股明细，由 Tushare `top_list` 接口采集）+ `top_inst` 表（机构席位明细，由 Tushare `top_inst` 接口采集）。

**页面结构**：

#### 顶部：当日龙虎榜个股列表
- 表头列：股票代码 / 股票名称 / 收盘价 / 涨跌幅 / 上榜原因（`reason`）/ 净买入额（`net_amount`）/ 买入额（`l_buy_amount`）/ 卖出额（`l_sell_amount`）/ 操作。
- 默认按 `net_amount` 降序。
- 「上榜原因」列展示 `reason` 字段（如"日涨幅偏离值达 7% 的证券"）。
- 操作列：「展开席位」按钮，点击展开下方营业部明细子表。

#### 展开子表：营业部明细
- 展开行后显示两个子表：
  - **买入席位 TOP 5**：营业部名称（`exalter`）/ 买入额（`buy`）/ 买入占比（`buy_rate`）/ 净买入额（`net_buy`）。
  - **卖出席位 TOP 5**：营业部名称（`exalter`）/ 卖出额（`sell`）/ 卖出占比（`sell_rate`）/ 净买入额（`net_buy`）。
- 数据来自 `top_inst` 表，按 `ts_code + trade_date` 关联。

#### 侧边卡片：知名游资/机构席位跟踪
- 右侧固定卡片，展示当日上榜的知名游资营业部（名称模糊匹配，如"东方财富拉萨"、"机构专用"）。
- 点击游资名称 -> 跳转该游资当日上榜个股列表（筛选 `exalter` 包含关键词）。
- 知名游资名单一期可硬编码在前端配置（后续可扩展为后端维护）。

**后端接口**（FR-7 详述）：
- `GET /api/top-list?tradeDate={date}` 返回当日龙虎榜个股列表。
- `GET /api/top-inst?tradeDate={date}&tsCode={code}` 返回指定个股的营业部席位明细。
- `GET /api/top-inst/notable?tradeDate={date}` 返回当日知名游资/机构席位汇总。

**降级**：当日无龙虎榜数据（非交易日）时显示"今日休市，无龙虎榜数据"。

**验收**：Tab C 加载后个股列表渲染，点击「展开席位」展开买入/卖出席位子表，知名游资卡片展示当日上榜游资。

---

### FR-5 Tab D 大宗交易

**需求**：当日大宗交易明细表 + 溢价率分布柱状图。

**数据来源**：`block_trade` 表（由 Tushare `block_trade` 接口，2000 积分采集）。

**页面结构**：

#### 顶部：溢价率分布柱状图
- ECharts 柱状图，X 轴为溢价率区间（如 <-5% / -5%~-3% / -3%~-1% / -1%~0% / 0%~1% / 1%~3% / 3%~5% / >5%），Y 轴为笔数。
- 溢价率计算：`(price - close) / close * 100%`，其中 `close` 为当日收盘价（需 join `daily_quote` 或由后端预计算）。
- 红色表示溢价（正），绿色表示折价（负）。

#### 下方：大宗交易明细表
- 表头列：股票代码 / 股票名称 / 成交价（`price`）/ 当日收盘价 / 溢价率（%）/ 成交量（`vol`）/ 成交额（`amount`）/ 买方营业部（`buyer_name`）/ 卖方营业部（`seller_name`）/ 操作。
- 默认按 `amount` 降序，支持点击表头排序。
- 溢价率按涨跌色显示。
- 成交额用 `StockApp.formatNumber` 格式化（万元 / 亿元）。
- 分页：默认 20 条/页，支持翻页。
- 操作列：「诊断」按钮，点击跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`。

**后端接口**（FR-7 详述）：
- `GET /api/block-trade?tradeDate={date}&page={1}&size={20}&sortBy={amount}&order={desc}` 返回分页明细。
- `GET /api/block-trade/premium-distribution?tradeDate={date}` 返回溢价率分桶统计。

**降级**：当日无大宗交易时显示"今日无大宗交易记录"。

**验收**：Tab D 加载后柱状图与表格同步渲染，溢价率按涨跌色显示，分页翻页正常。

---

### FR-6 Tab E 融资融券

**需求**：融资余额 / 融券余额趋势图 + 个股融资融券明细 TOP 50。

**数据来源**：`margin` 表（融资融券汇总，由 Tushare `margin` 接口采集）+ `margin_detail` 表（个股明细，由 Tushare `margin_detail` 接口采集）。

**页面结构**：

#### 顶部：融资余额 / 融券余额趋势图
- ECharts 双轴折线图，X 轴为 `trade_date`：
  - 左 Y 轴：融资余额（`rzye`，单位亿元）折线。
  - 右 Y 轴：融券余额（`rqye`，单位亿元）折线。
- 支持切换市场：沪市（`exchange_id='SSE'`）/ 深市（`exchange_id='SZSE'`）/ 两市合计。
- 时间范围：默认近 30 个交易日，支持切换 7 日 / 30 日 / 90 日。
- 曲线 tooltip 显示当日数值与环比变化。

#### 下方：个股融资融券明细 TOP 50
- 表头列：股票代码 / 股票名称 / 融资余额（`rzye`）/ 融资买入额（`rzmre`）/ 融资偿还额（`rzche`）/ 融券余额（`rqye`）/ 融券卖出量（`rqmcl`）/ 融资融券余额（`rzrqye`）/ 操作。
- 默认按 `rzrqye` 降序，支持点击表头排序。
- 金额列用 `StockApp.formatNumber` 格式化。
- 分页：TOP 50 默认一页展示，可选"查看全部"切换分页模式。
- 操作列：「诊断」按钮，点击跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`。

**后端接口**（FR-7 详述）：
- `GET /api/margin/trend?days={30}&exchangeId={SSE|SZSE|ALL}` 返回余额趋势时序数据。
- `GET /api/margin-detail/top?tradeDate={date}&limit={50}&sortBy={rzrqye}&order={desc}` 返回 TOP 50 个股明细。

**降级**：当日无数据时显示"今日无融资融券数据"。

**验收**：Tab E 加载后趋势图渲染两条曲线，切换市场正确，TOP 50 表格按 rzrqye 降序展示。

---

### FR-7 后端 Controller

**需求**：为本模块 5 个 Tab 提供 5 个 Controller，对前端暴露 GET 查询接口，返回 `ApiResponse<T>`。

#### 7.1 StockMoneyflowController（个股资金流向）
- 路径前缀：`/api/moneyflow`
- 接口：
  - `GET /top?tradeDate={date}&limit={50}&sortBy={net_mf_amount}&order={desc}` - TOP 50 主力净流入排行
  - `GET /detail?tsCode={code}&days={30}` - 单股近 N 日资金流向（供个股诊断 Tab 3 复用）
- 数据表：`stock_moneyflow`

#### 7.2 HkHoldController（北向资金，替代原 HsgtController）
- 路径前缀：`/api/hk-hold`
- 接口：
  - `GET /ratio-trend?days={30}&exchangeId={SH|SZ|ALL}` - 持股比例变化曲线（按 trade_date 聚合 sum(ratio)）
  - `GET /top-holdings?tradeDate={date}&exchangeId={SH|SZ|ALL}&limit={10}` - 十大重仓股（按 vol 降序）
  - `GET /detail?tsCode={code}&days={30}` - 单股北向持股时序（供个股诊断 Tab 3 复用）
- 数据表：`hk_hold`
- ⚠️ **明确不做** `/net-buy` 类接口（`hk_hold` 无净额字段，`moneyflow_hsgt` 不存在）

#### 7.3 TopListController（龙虎榜）
- 路径前缀：`/api/top-list`
- 接口：
  - `GET /?tradeDate={date}` - 当日龙虎榜个股列表（top_list）
  - `GET /inst?tradeDate={date}&tsCode={code}` - 指定个股营业部席位明细（top_inst）
  - `GET /inst/notable?tradeDate={date}` - 知名游资/机构席位汇总（top_inst 按 exalter 模糊匹配）
- 数据表：`top_list` + `top_inst`

#### 7.4 BlockTradeController（大宗交易）
- 路径前缀：`/api/block-trade`
- 接口：
  - `GET /?tradeDate={date}&page={1}&size={20}&sortBy={amount}&order={desc}` - 分页明细
  - `GET /premium-distribution?tradeDate={date}` - 溢价率分桶统计
- 数据表：`block_trade`（溢价率计算需 join `daily_quote` 取当日 close，或后端预计算）

#### 7.5 MarginController（融资融券）
- 路径前缀：`/api/margin`
- 接口：
  - `GET /trend?days={30}&exchangeId={SSE|SZSE|ALL}` - 余额趋势时序（margin 表）
  - `GET /detail/top?tradeDate={date}&limit={50}&sortBy={rzrqye}&order={desc}` - TOP 50 个股明细（margin_detail 表）
- 数据表：`margin` + `margin_detail`

**通用要求**：
- 所有接口返回 `ApiResponse<T>`（与现有 Controller 风格一致）。
- `tradeDate` 参数默认当日（若非交易日则取最近交易日，由后端判断）。
- 所有接口需在 `tradeDate` 为空时回退到最近有数据的交易日。
- 接口需做参数校验（日期格式 / limit 上限 / sortBy 白名单）。
- 查询性能：长表需按 `(trade_date, ts_code)` 建复合索引。

---

### FR-8 前端文件

**需求**：新增 3 个前端文件，实现 5 Tab 页面。

#### 8.1 `templates/pages/moneyflow.html`
- 套用 `fragments/common.html` 的 `head/sidebar/topNavbar/scripts` 骨架。
- 页面标题"资金流向"，顶部日期选择器 + 刷新按钮。
- 5 Tab 导航栏（Bootstrap nav-tabs），每个 Tab 一个 `<div>` 容器。
- 每个 Tab 容器内预留图表与表格的 DOM 节点（`<div id="chart-xxx">` / `<table id="table-xxx">`）。
- 引用 `moneyflow.js` + `moneyflow.css`。

#### 8.2 `static/js/moneyflow.js`
- Tab 懒加载逻辑：首次切换到某 Tab 才发请求，缓存已加载的 Tab 数据。
- 5 Tab 各自的渲染函数：
  - `renderTabA()` - 个股资金流向（柱状图 + 表格）
  - `renderTabB()` - 北向资金（折线图 + 重仓股表格）
  - `renderTabC()` - 龙虎榜（列表 + 展开子表 + 游资卡片）
  - `renderTabD()` - 大宗交易（柱状图 + 表格）
  - `renderTabE()` - 融资融券（双轴折线图 + 表格）
- 接口调用统一用 `StockApp.get`。
- 图表实例统一用 `ChartsTheme`，主题切换时同步更新。
- 表格分页 / 排序 / 跳转个股诊断的通用逻辑。
- 日期选择器变更时重新加载当前激活 Tab。

#### 8.3 `static/css/moneyflow.css`
- 5 Tab 容器布局。
- 表格样式（复用现有行情中心表格风格）。
- 图表容器高度（柱状图 300px / 折线图 400px）。
- 龙虎榜展开子表的样式（折叠展开动画）。
- 知名游资卡片的右侧固定样式。

**修改现有文件**：
- `fragments/common.html`：侧边栏"主要功能"下新增第 5 项"资金流向"菜单。
- `PageController.java`：新增 `GET /page/moneyflow` 路由。

---

## 4. 非功能需求

### 4.1 性能
- **Tab 懒加载**：首次切换到某 Tab 才发起请求，避免页面初始化时 5 个 Tab 同时请求导致卡顿。
- **长列表分页**：Tab A / D / E 表格超 50 条时启用分页（默认 20 条/页），Tab C 龙虎榜当日个股通常 ≤ 50 个不分页但展开子表需懒加载。
- **图表渲染性能**：单 Tab 图表节点数 ≤ 100 个（折线图 90 日 × 3 条 = 270 点，可接受）。
- **接口响应时间**：单接口 P95 ≤ 500ms（7 张表需按 `(trade_date, ts_code)` 建复合索引）。

### 4.2 主题与样式统一
- 所有 ECharts 实例使用 `ChartsTheme`，支持三主题（azure / mist / cyber）切换后图表颜色同步。
- 涨跌色全局统一：红涨绿跌（A 股惯例）。
- 数字格式化用 `StockApp.formatNumber` / `StockApp.formatPercent`，金额单位（元 / 万元 / 亿元）自动切换。

### 4.3 数据降级与提示
- **hk_hold 无净额**：Tab B 顶部固定一行说明"本 Tab 基于 hk_hold 沪深股通持股明细，仅展示持股比例变化，不含净买入数据"。
- **非交易日**：当日为非交易日时，各 Tab 显示"今日休市，无 {xxx} 数据"，并自动回退到最近交易日。
- **数据未拉取**：若后端定时任务未拉取到当日数据，显示"数据暂未就绪，请稍后刷新"，并提供"查看最近有数据日期"的快捷链接。
- **接口异常**：接口 500 / 超时时显示"加载失败，请重试"，不影响其他 Tab。

### 4.4 可访问性
- Tab 导航支持键盘左右切换。
- 表格表头可点击排序，排序状态有视觉指示（升序/降序箭头）。
- 图表 tooltip 支持鼠标悬停与移动端触摸。

### 4.5 合规边界
- 全产品页脚固定文案"本工具为量化研究辅助，不构成任何投资建议；历史数据不代表未来收益"。
- 不使用"推荐""最优"等暗示性表述，改为"主力净流入 TOP""持股比例变化"等中性描述。

---

## 5. 验收标准

### 5.1 路由与页面骨架
| 验证项 | 验证方法 |
|---|---|
| 侧边栏菜单项正确 | 浏览器访问 `/`，查看侧边栏"主要功能"下应有 5 项（仪表盘/行情中心/自选股/板块行情/资金流向），资金流向为第 5 项 |
| 页面路由可达 | 访问 `/page/moneyflow`，返回 200 且页面标题为"资金流向" |
| 5 Tab 完整展示 | 页面加载后看到 5 个 Tab（个股资金流向/北向资金/龙虎榜/大宗交易/融资融券），默认激活 Tab A |

### 5.2 各 Tab 数据加载
| 验证项 | 验证方法 |
|---|---|
| Tab A 个股资金流向 | 切换到 Tab A，柱状图渲染 TOP 10，表格渲染 TOP 50，点击股票代码跳转 `/page/stock-detail/{code}?tab=moneyflow` |
| Tab B 北向资金 | 切换到 Tab B，持股比例曲线渲染 3 条线（沪股通/深股通/北向合计），十大重仓股表格按 vol 降序；切换沪/深/合计子视图正确；Tab 顶部有"不含净买入数据"说明 |
| Tab C 龙虎榜 | 切换到 Tab C，个股列表渲染，点击「展开席位」展开买入/卖出席位子表；知名游资卡片展示当日上榜游资 |
| Tab D 大宗交易 | 切换到 Tab D，溢价率分布柱状图渲染，明细表格按 amount 降序，分页翻页正常，溢价率按涨跌色显示 |
| Tab E 融资融券 | 切换到 Tab E，融资余额/融券余额双轴折线图渲染，切换沪/深/合计市场正确，TOP 50 表格按 rzrqye 降序 |

### 5.3 非功能验收
| 验证项 | 验证方法 |
|---|---|
| Tab 懒加载 | 打开浏览器 DevTools Network 面板，首次进入页面只发 Tab A 请求；切换到其他 Tab 才发对应请求 |
| 主题切换 | 切换三主题（azure/mist/cyber），所有图表颜色同步变化，无样式错乱 |
| 数据降级 | 日期选择器切到非交易日，各 Tab 显示"今日休市"提示；切到无数据日期，显示"数据暂未就绪" |
| 接口异常 | 模拟后端 500，对应 Tab 显示"加载失败，请重试"，不影响其他 Tab |

### 5.4 端到端验证
```
老股民完整使用路径：
1. 进入 /page/moneyflow，默认看 Tab A 个股资金流向 TOP 50
2. 看顶部柱状图 TOP 10，点击某只股票 -> 跳转个股诊断页面 moneyflow Tab
3. 切到 Tab B 北向资金，看持股比例曲线，看十大重仓股
4. 切到 Tab C 龙虎榜，展开某只个股看营业部席位，看知名游资卡片
5. 切到 Tab D 大宗交易，看溢价率分布，看明细表
6. 切到 Tab E 融资融券，看两融余额趋势，看个股 TOP 50
7. 切换日期回溯历史，各 Tab 数据正确刷新
```

---

## 6. 实施建议

### 6.1 建议开发顺序

| 步骤 | 任务 | 依赖 | 说明 |
|---|---|---|---|
| D1 | StockMoneyflowController | `stock_moneyflow` 表已落库 | Tab A 后端 |
| D2 | HkHoldController（替代原 HsgtController） | `hk_hold` 表已落库 | Tab B 后端，明确不做净买入接口 |
| D3 | TopListController | `top_list` + `top_inst` 表已落库 | Tab C 后端 |
| D4 | BlockTradeController | `block_trade` 表已落库 | Tab D 后端，溢价率计算需 join `daily_quote` |
| D5 | MarginController | `margin` + `margin_detail` 表已落库 | Tab E 后端 |
| D6 | 前端页面骨架：moneyflow.html + moneyflow.js + moneyflow.css | D1-D5 至少一个 Controller 就绪 | 5 Tab 空骨架 + 侧边栏菜单项 |
| D7 | 前端 Tab A 个股资金流向 | D1 + D6 | 柱状图 + 表格 |
| D8 | 前端 Tab B 北向资金 | D2 + D6 | 持股曲线 + 重仓股聚合 |
| D9 | 前端 Tab C 龙虎榜 | D3 + D6 | 列表 + 展开子表 + 游资卡片 |
| D10 | 前端 Tab D 大宗交易 | D4 + D6 | 柱状图 + 表格 |
| D11 | 前端 Tab E 融资融券 | D5 + D6 | 双轴折线图 + 表格 |

**并行建议**：
- D1-D5 五个后端 Controller 互不依赖，可并行开发。
- D6 页面骨架可等 D1 就绪后开始（用 Tab A 做联调样板）。
- D7-D11 五个前端 Tab 可并行开发（各自独立模块），但需 D6 骨架先行。

### 6.2 与其他模块的关系

| 关联模块 | 关系说明 |
|---|---|
| **数据采集** | 强前置：7 张表（见 §2.1）必须先由 Tushare 接口采集落库，本模块才能开发。若数据采集延期，本模块整体延期。 |
| **个股诊断（017）** | 双向复用：①本模块 Tab A/C/D/E 点击股票跳转 `/page/stock-detail/{code}?tab=moneyflow`，依赖个股诊断页面已上线；②个股诊断 Tab 3 资金面复用本模块的 `StockMoneyflowController` / `HkHoldController` / `TopListController` / `BlockTradeController` 查询接口，保证口径一致。 |
| **板块行情（015）** | 无依赖：板块行情独立页面，本模块不做板块维度资金流向（`moneyflow_dc` 砍掉）。 |
| **现有模块补齐（012 仪表盘 / 013 行情中心 / 014 自选股）** | 无依赖：仪表盘北向卡片已砍掉（总计划 §2.1 D6），与本模块 Tab B 北向资金无重叠。 |
| **联调与优化** | 弱依赖：侧边栏菜单项（FR-1）属于联调阶段的 `fragments/common.html` 改造范围，但本模块需自行落地菜单项。 |

### 6.3 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| `hk_hold` 数据未拉取（积分不足 2000） | Tab B 无法展示 | 降级提示"数据暂未就绪"；后端检查 TushareApiEnum 注册与调度任务 |
| `block_trade` 溢价率计算需 join `daily_quote` | D4 后端复杂度增加 | 后端预计算溢价率字段，或前端取当日 close 后本地计算 |
| 龙虎榜 `top_inst` 知名游资名单硬编码 | 一期可接受，后续维护成本 | 一期硬编码在前端配置；二期评估改为后端维护游资库 |
| Tushare 接口限流 | 数据拉取延迟 | 数据采集调度任务已错峰；本模块查询接口走本地数据库，不受限流影响 |

---

## 7. 相关文档

- 总计划：[主要功能模块整体设计计划.md](../../.trae/documents/主要功能模块整体设计计划.md) §3.2
- 跨模块复用：[017 个股诊断 PRD](../017-个股诊断/个股诊断PRD.md) Tab 3 资金面
- 工程规范：`02-tushare-integration-guide.md`（每张新表 11 步落地流程）
