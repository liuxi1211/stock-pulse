# 资金流向模块 Spec

## Why

老股民做决策时"看主力"是必备视角，当前系统缺少个股资金流向、北向资金、龙虎榜、大宗交易、融资融券五个维度的主力资金追踪能力。需新建 `/page/moneyflow` 独立菜单页（5 Tab 结构），所有数据严格来自 Tushare ≤2000 积分接口，不引入派生聚合数据。

> 来源 PRD：`sdlc/prd/016-资金流向/资金流向PRD.md`

## What Changes

### 数据层（7 张新表，从零搭建）
- 新增 7 张数据库表：`stock_moneyflow` / `hk_hold` / `top_list` / `top_inst` / `block_trade` / `margin` / `margin_detail`（SQLite + MySQL 双方言 DDL）
- `TushareApiEnum` 新增 7 个枚举项：`MONEYFLOW` / `HK_HOLD` / `TOP_LIST` / `TOP_INST` / `BLOCK_TRADE` / `MARGIN` / `MARGIN_DETAIL`
- 新增 7 组 DTO -> DO -> Mapper(含 XML) -> Service/ServiceImpl -> TushareClient 方法
- 新增定时任务：每日 16:00 后错峰拉取 7 张表数据

### 查询 Controller 层（5 个 Controller）
- `MoneyflowController`（`/api/moneyflow`）：个股资金流向 TOP 排行 + 单股明细
- `HkHoldController`（`/api/hk-hold`）：北向持股比例趋势 + 十大重仓股（**明确不做净买入接口**）
- `TopListController`（`/api/top-list`）：龙虎榜个股列表 + 营业部席位明细 + 知名游资
- `BlockTradeController`（`/api/block-trade`）：大宗交易明细 + 溢价率分布
- `MarginController`（`/api/margin`）：融资融券余额趋势 + 个股明细 TOP

### 前端页面层
- `PageController` 新增 `GET /page/moneyflow` 路由
- `fragments/common.html` 侧边栏"主要功能"末尾新增第 5 项"资金流向"
- 新增 `templates/pages/moneyflow.html`（5 Tab 骨架）
- 新增 `static/js/moneyflow.js`（5 Tab 懒加载 + ECharts 图表 + 表格渲染）
- 新增 `static/css/moneyflow.css`（Tab 布局 + 表格 + 图表容器样式）

### 明确不做（裁剪决策）
- ❌ 板块资金流向 Tab（`moneyflow_dc` 接口不存在）
- ❌ 北向资金每日净买入柱状图（`moneyflow_hsgt` 接口不存在，`hk_hold` 无净额字段）
- ❌ 北向资金累计净买入趋势线（同上）
- ❌ 仪表盘北向资金卡片（已砍掉）
- ❌ 分钟级资金流向 / 实时推送

## Impact

- **Affected specs**: 无（新建模块，不影响已有 spec）
- **Affected code**:
  - `stock-watcher/src/main/resources/schema-sqlite.sql` / `schema-mysql.sql`：新增 7 张表 DDL
  - `stock-watcher/.../constant/TushareApiEnum.java`：新增 7 个枚举项
  - `stock-watcher/.../client/TushareClient.java`：新增 7 个数据拉取方法
  - `stock-watcher/.../model/`：新增 7 个 DO 类
  - `stock-watcher/.../mapper/` + `resources/mapper/`：新增 7 个 Mapper 接口 + XML
  - `stock-watcher/.../service/` + `service/impl/`：新增 7 个 Service 接口 + 实现
  - `stock-watcher/.../dto/tushare/`：新增 7 个 DTO 类
  - `stock-watcher/.../controller/`：新增 5 个 Controller + 修改 `PageController`
  - `stock-watcher/.../task/`：新增或修改定时任务
  - `stock-watcher/.../templates/fragments/common.html`：侧边栏新增菜单项
  - `stock-watcher/.../templates/pages/moneyflow.html`：新建页面
  - `stock-watcher/.../static/js/moneyflow.js`：新建 JS
  - `stock-watcher/.../static/css/moneyflow.css`：新建 CSS
- **跨模块依赖**：个股诊断（017）Tab 3 资金面将复用本模块后端查询接口

## ADDED Requirements

### Requirement: 资金流向数据采集层

系统 SHALL 通过 Tushare API 采集 7 张表数据并落库，支持定时拉取与手动触发。

#### Scenario: 定时拉取成功
- **WHEN** 每个交易日 16:00 后定时任务触发
- **THEN** 系统调用 Tushare 对应接口拉取当日数据，批量 UPSERT 到本地数据库（先删后插），日志记录拉取条数

#### Scenario: 接口不可用降级
- **WHEN** Tushare 接口返回错误或超时
- **THEN** 记录错误日志，不影响其他表的拉取，不影响已有数据

### Requirement: Tab A 个股资金流向

系统 SHALL 在 Tab A 展示当日主力净流入 TOP 50 个股排行 + 顶部 TOP 10 柱状图。

#### Scenario: 正常加载
- **WHEN** 用户切换到 Tab A
- **THEN** 调用 `GET /api/moneyflow/top` 获取 TOP 50 列表，柱状图渲染 TOP 10，表格渲染 TOP 50，支持表头排序

#### Scenario: 跳转个股诊断
- **WHEN** 用户点击股票代码或「诊断」按钮
- **THEN** 跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`

### Requirement: Tab B 北向资金（hk_hold 替代）

系统 SHALL 在 Tab B 展示沪深股通持股比例变化曲线 + 十大重仓股表格，基于 `hk_hold` 表。

#### Scenario: 持股比例曲线
- **WHEN** 用户切换到 Tab B
- **THEN** 调用 `GET /api/hk-hold/ratio-trend` 获取近 30 日持股比例时序，渲染 3 条折线（沪股通/深股通/北向合计）

#### Scenario: 十大重仓股
- **WHEN** 用户切换沪/深/合计子视图
- **THEN** 调用 `GET /api/hk-hold/top-holdings` 获取按 vol 降序的 TOP 10 重仓股

#### Scenario: 明确不做净买入
- **WHEN** Tab B 加载
- **THEN** 顶部固定显示"本 Tab 基于 hk_hold 沪深股通持股明细，仅展示持股比例变化，不含净买入数据"，不渲染净买入柱状图

### Requirement: Tab C 龙虎榜

系统 SHALL 在 Tab C 展示当日龙虎榜个股列表 + 营业部席位明细展开 + 知名游资卡片。

#### Scenario: 龙虎榜列表与展开
- **WHEN** 用户切换到 Tab C
- **THEN** 调用 `GET /api/top-list` 获取当日龙虎榜个股列表，点击「展开席位」调用 `GET /api/top-list/inst` 获取买入/卖出席位 TOP 5

#### Scenario: 知名游资卡片
- **WHEN** Tab C 加载
- **THEN** 调用 `GET /api/top-list/inst/notable` 获取知名游资/机构席位汇总，右侧卡片展示

### Requirement: Tab D 大宗交易

系统 SHALL 在 Tab D 展示当日大宗交易明细表 + 溢价率分布柱状图。

#### Scenario: 溢价率分布与明细
- **WHEN** 用户切换到 Tab D
- **THEN** 调用 `GET /api/block-trade/premium-distribution` 渲染溢价率分桶柱状图，调用 `GET /api/block-trade` 渲染分页明细表

#### Scenario: 溢价率计算
- **WHEN** 后端返回大宗交易数据
- **THEN** 溢价率 = `(price - close) / close * 100%`，close 取当日收盘价（后端预计算或 join daily_quote）

### Requirement: Tab E 融资融券

系统 SHALL 在 Tab E 展示融资余额/融券余额趋势图 + 个股融资融券明细 TOP 50。

#### Scenario: 余额趋势
- **WHEN** 用户切换到 Tab E
- **THEN** 调用 `GET /api/margin/trend` 获取近 30 日余额时序，渲染双轴折线图（左轴融资余额/右轴融券余额），支持切换沪/深/合计

#### Scenario: 个股明细
- **WHEN** Tab E 加载
- **THEN** 调用 `GET /api/margin/detail/top` 获取 TOP 50 个股明细，按 rzrqye 降序

### Requirement: Tab 懒加载与主题统一

系统 SHALL 实现 Tab 懒加载（首次切换才请求）与全局主题统一。

#### Scenario: 懒加载
- **WHEN** 页面首次加载
- **THEN** 仅请求 Tab A 数据；切换到其他 Tab 时才发对应请求

#### Scenario: 主题切换
- **WHEN** 用户切换 azure/mist/cyber 主题
- **THEN** 所有 ECharts 实例颜色同步更新，涨跌色保持红涨绿跌

### Requirement: 数据降级与提示

系统 SHALL 在数据缺失时提供友好的降级提示。

#### Scenario: 非交易日
- **WHEN** 用户选择非交易日
- **THEN** 各 Tab 显示"今日休市，无 {xxx} 数据"，自动回退到最近交易日

#### Scenario: 接口异常
- **WHEN** 某个接口返回 500 或超时
- **THEN** 对应 Tab 显示"加载失败，请重试"，不影响其他 Tab
