# 仪表盘功能补齐（D1-D5）Spec

> **来源 PRD**：`sdlc/prd/012-仪表盘/仪表盘PRD.md`
> **change-id**：`016-dashboard-completion`
> **基线**：stock-watcher（Java 21 + Spring Boot 4.0.6）· stock-engine（Python 3.12 + FastAPI + AKQuant 0.2.47）
> **状态**：规划中

---

## Why

仪表盘（路由 `/`）是项目总览入口，老股民开盘第一眼必看。当前页面骨架已存在但存在 5 处功能缺口：大盘指数用硬编码 mock（`3368.07` 等假数据）、无市场温度卡片、榜单缺换手率榜、K 线图仅 MA 三线无副图指标、无板块概览卡片。本 spec 补齐这 5 项功能，让仪表盘成为「可信的一站式总览面板」。

**核心驱动力**：消除硬编码 mock 恢复用户信任；让老股民在仪表盘就能完成「大盘 / 情绪 / 榜单 / 技术面 / 板块」五项总览，不用跳转多个页面。

---

## What Changes

### 第一部分：index_daily 数据底座（D1/D5 前置）

- 新建 `index_daily` 表（SQLite + MySQL 双 schema），存储指数日线数据
- 新建 `IndexDailyDO` / `IndexDailyMapper`（含 XML）/ `IndexDailyService` 全套数据访问层
- 注册 Tushare `index_daily` 接口到 `TushareApiEnum`，新建定时拉取任务（每日收盘后执行）
- 初始化拉取 4 大盘指数（`000001.SH` / `399001.SZ` / `399006.SZ` / `000688.SH`）+ 28 个申万一级行业指数（`801xxx.SI`）历史数据

### 第二部分：D1 大盘指数真实数据

- **移除** `MarketServiceImpl.java:31-44` 的硬编码 mock 大盘指数数据
- `MarketServiceImpl.getMarketIndices()` 改为查询 `index_daily` 表取最新交易日 4 个大盘指数
- 新建 `IndexDailyController` 暴露 `GET /api/index-daily/latest` 查询接口
- 前端 `dashboard.html` / `dashboard.js` 适配真实数据，数据缺失时显示「暂无数据」占位
- `@Cacheable("indices")` 缓存加时效控制（每日收盘后失效）

### 第三部分：D2 市场温度卡片

- 新建 `MarketTemperatureVO`（up_count / down_count / flat_count / limit_up_count / limit_down_count）
- `MarketService.getMarketTemperature(tradeDate)` 聚合 `daily_quote` JOIN `stock_basic`，按板块区分涨跌停规则
- `MarketController` 加 `GET /market/temperature` 端点
- `DailyQuoteMapper` 加 5 个聚合统计方法（涨/跌/平/涨停/跌停）
- 前端 `dashboard.html` 加市场温度卡片（横向 5 格布局），`dashboard.js` 渲染 5 个数字 + 涨跌色

### 第四部分：D3 换手率榜 TOP 10

- `MarketRankingVO` 加 `topTurnover` 字段（保持一次返回 4 榜，向后兼容）
- `MarketService.getMarketRanking()` 加换手率分支，JOIN `daily_quote` + `daily_basic` 按 `turnover_rate` 降序取 TOP 10
- `DailyQuoteMapper` 加 `selectTopTurnover` 方法
- **前端 Row 3 结构重构**：3 张并排独立卡片 -> 4 Tab 切换（涨幅榜 / 跌幅榜 / 成交额榜 / 换手率榜）

### 第五部分：D4 K 线技术指标切换

- 前端 `dashboard.js` 实现 MACD / KDJ / RSI / BOLL 算法（纯 JS，与 `akquant.talib` 口径对齐）
- `dashboard.html` 加主图指标切换（MA / BOLL）+ 副图指标切换（MACD / KDJ / RSI）UI
- `dashboard.js` `renderKline` 扩展：副图渲染（LightweightCharts HistogramStyle / LineSeries）+ BOLL 主图叠加
- `theme.css` 加指标颜色 CSS 变量（`--macd-dif` / `--macd-dea` / `--macd-hist` / `--kdj-k` / `--kdj-d` / `--kdj-j` / `--rsi-line` / `--boll-upper` / `--boll-lower`）
- `charts-theme.js` `getKlineTheme()` 扩展读取指标颜色变量

### 第六部分：D5 板块概览快照卡片

- 前端 `dashboard.html` 加板块概览卡片（领涨 TOP 5 + 领跌 TOP 5 + 查看全部链接）
- `dashboard.js` 调用 015 模块的 `GET /api/industry/ranking` 接口渲染
- **降级**：015 模块未上线时，卡片显示「板块行情功能开发中」占位
- 点击「查看全部」跳转 `/page/sector`（依赖 015 上线）

---

## Impact

### Affected specs

- 无直接关联的既有 spec（本 spec 为独立功能补齐，不触及策略/回测/选股相关 spec）
- PRD 依赖：`015-板块行情PRD.md`（D5 强依赖）、`017-个股诊断PRD.md`（D3 弱依赖，榜单跳转目标）

### Affected code（stock-watcher / Java 后端）

| 文件 | 改动 |
|---|---|
| `schema-sqlite.sql` / `schema-mysql.sql` | **新增** `index_daily` 表 DDL |
| `model/entity/IndexDailyDO.java` | **新文件** |
| `mapper/IndexDailyMapper.java` | **新文件** |
| `resources/mapper/IndexDailyMapper.xml` | **新文件** |
| `service/IndexDailyService.java` + `impl/IndexDailyServiceImpl.java` | **新文件** |
| `controller/IndexDailyController.java` | **新文件** |
| `tushare/TushareApiEnum.java` | 注册 `INDEX_DAILY` 枚举值 |
| `tushare/TushareClient.java` | 加 `index_daily` 接口调用方法 |
| `service/IndexDailyFetchService.java` | **新文件**，定时拉取任务 |
| `config/ScheduleConfig.java` 或既有定时配置 | 注册 index_daily 拉取定时任务 |
| `service/impl/MarketServiceImpl.java` | **删除** L31-44 硬编码 mock；`getMarketIndices()` 改调 `IndexDailyService` |
| `controller/MarketController.java` | 加 `GET /market/temperature` 端点 |
| `service/MarketService.java` + `impl/MarketServiceImpl.java` | 加 `getMarketTemperature(tradeDate)` 方法 |
| `vo/MarketTemperatureVO.java` | **新文件** |
| `vo/MarketRankingVO.java` | 加 `topTurnover` 字段 |
| `mapper/DailyQuoteMapper.java` + XML | 加 `selectTopTurnover`（JOIN daily_basic）+ 5 个聚合统计方法 |

### Affected code（前端）

| 文件 | 改动 |
|---|---|
| `templates/pages/dashboard.html` | Row 1 指数卡片适配；Row 1-2 间加市场温度卡片；Row 3 三卡片改 4 Tab；K 线图加指标切换 UI；右侧加板块概览卡片 |
| `static/js/dashboard.js` | 指数真实数据渲染；市场温度渲染；榜单 Tab 切换 + 换手率渲染；MACD/KDJ/RSI/BOLL 算法 + 副图渲染；板块概览渲染 + 降级 |
| `static/js/charts-theme.js` | `getKlineTheme()` 扩展指标颜色 |
| `static/css/theme.css` | 三主题各加指标颜色变量 |

---

## ADDED Requirements

### Requirement: index_daily 数据底座（D1/D5 前置）

系统 SHALL 提供 `index_daily` 表及完整数据采集链路，存储指数日线数据（大盘指数 + 申万行业指数），供仪表盘 D1 / D5 消费。

#### Scenario: 表结构覆盖指数日线全字段

- **WHEN** `index_daily` 表创建完成
- **THEN** 表包含字段：`ts_code`(TEXT, 指数代码) / `trade_date`(TEXT, 交易日 YYYYMMDD) / `close`(REAL, 收盘价) / `open` / `high` / `low` / `pre_close` / `change` / `pct_chg` / `vol` / `amount`
- **AND** 主键为 `(ts_code, trade_date)` 联合主键
- **AND** `trade_date` 字段有索引

#### Scenario: 定时拉取 4 大盘指数 + 28 申万行业指数

- **WHEN** 每日收盘后定时任务触发
- **THEN** 调用 Tushare `index_daily` 接口拉取 4 个大盘指数（`000001.SH` / `399001.SZ` / `399006.SZ` / `000688.SH`）+ 28 个申万一级行业指数（`801xxx.SI`）的当日数据
- **AND** 数据写入 `index_daily` 表（UPSERT 语义，重复数据覆盖）

#### Scenario: 查询最新交易日指数数据

- **WHEN** 调用 `IndexDailyService.getLatestByCodes(codes)` 传入指数代码列表
- **THEN** 返回每个指数在最新交易日的数据（含 close / pre_close / change / pct_chg）
- **AND** 交易日由 `trade_cal` 表确定，避免周末/节假日返回旧数据

#### Scenario: 数据缺失时返回空而非报错

- **WHEN** `index_daily` 表中无指定指数数据
- **THEN** 查询方法返回空列表，不抛异常

---

### Requirement: FR-1 大盘指数真实数据展示（D1）

仪表盘顶部 4 个大盘指数卡片 SHALL 展示真实 `index_daily` 数据，不再显示硬编码 mock 值。

#### Scenario: 4 个指数卡片显示真实点位

- **WHEN** 用户访问仪表盘 `/` 且 `index_daily` 表有当日数据
- **THEN** 4 张指数卡片分别显示上证指数 / 深证成指 / 创业板指 / 科创50 的：指数名 + 最新点位（2 位小数）+ 涨跌额 + 涨跌幅 + 数据日期
- **AND** 涨跌色正确（红涨绿跌，用 `theme.css` 的 `.stock-up` / `.stock-down`）

#### Scenario: 消除硬编码 mock

- **WHEN** 全页面搜索 `3368.07` 等历史 mock 值
- **THEN** 应无结果（`MarketServiceImpl.java:31-44` 硬编码 mock 已删除）

#### Scenario: 数据缺失降级

- **WHEN** `index_daily` 表无当日数据（如未拉取或拉取失败）
- **THEN** 卡片显示「暂无数据」占位
- **AND** **不显示**硬编码 mock 值

#### Scenario: 周末访问显示周五数据

- **WHEN** 周末访问仪表盘
- **THEN** 指数卡片显示最近交易日（周五）的数据
- **AND** 卡片显示数据日期字段（如 `2024-01-12`），让用户清楚数据时效

---

### Requirement: FR-2 市场温度卡片（D2）

仪表盘首屏 SHALL 展示市场温度卡片，显示当日 A 股市场情绪 5 个数字：涨家数 / 跌家数 / 平家数 / 涨停数 / 跌停数。

#### Scenario: 5 个数字正确显示

- **WHEN** 用户访问仪表盘且 `daily_quote` 表有当日数据
- **THEN** 市场温度卡片显示 5 个数字：涨家数（pct_chg > 0）/ 跌家数（pct_chg < 0）/ 平家数（pct_chg = 0）/ 涨停数 / 跌停数
- **AND** 涨家数 + 跌家数 + 平家数 = 当日有交易的全部 A 股股票数

#### Scenario: 涨跌停按板块区分规则

- **WHEN** 统计涨停数/跌停数
- **THEN** 按板块区分涨跌停规则：主板 10%、创业板/科创板 20%、ST 5%
- **AND** JOIN `stock_basic` 表取 `market`（市场板块）与 `name`（含 ST 判断）字段

#### Scenario: 涨跌色正确

- **WHEN** 市场温度卡片渲染完成
- **THEN** 涨家数红色、跌家数绿色、平家数灰色、涨停数红色加粗、跌停数绿色加粗

#### Scenario: 数据缺失降级

- **WHEN** `daily_quote` 表无当日数据
- **THEN** 市场温度卡片显示「暂无数据」占位

---

### Requirement: FR-3 换手率榜 TOP 10（D3）

仪表盘榜单区 SHALL 从 3 张并排卡片重构为 4 Tab 切换，新增换手率榜 TOP 10。

#### Scenario: 4 个 Tab 切换

- **WHEN** 用户访问仪表盘榜单区
- **THEN** 显示 4 个 Tab：涨幅榜 / 跌幅榜 / 成交额榜 / 换手率榜
- **AND** 默认选中涨幅榜

#### Scenario: 换手率榜降序排列

- **WHEN** 用户切换到换手率榜 Tab
- **THEN** 显示 TOP 10 个股，按换手率（`turnover_rate`）从高到低排列
- **AND** 每行显示：排名 / 股票代码 / 股票名称 / 最新价 / 涨跌幅 / 换手率 / 成交额

#### Scenario: 换手率数据来自 daily_basic JOIN

- **WHEN** 后端查询换手率榜
- **THEN** JOIN `daily_quote` + `daily_basic` 两表（通过 `ts_code + trade_date` 关联）
- **AND** `daily_quote` 提供 `pct_chg` / `close` / `amount`，`daily_basic` 提供 `turnover_rate`

#### Scenario: 向后兼容

- **WHEN** 前端调用 `GET /market/ranking`
- **THEN** 响应包含 `topGainers` / `topLosers` / `topAmount`（既有 3 榜）+ `topTurnover`（新增第 4 榜）
- **AND** 既有 3 榜数据不变，不影响其他消费方

#### Scenario: 换手率数据缺失降级

- **WHEN** `daily_basic` 表无当日数据
- **THEN** 换手率榜 Tab 显示「暂无数据」占位
- **AND** 其他 3 个 Tab 正常显示

---

### Requirement: FR-4 K 线技术指标切换（D4）

仪表盘 K 线图 SHALL 支持主图指标（MA / BOLL）与副图指标（MACD / KDJ / RSI）切换，算法口径与 `akquant.talib` 对齐。

#### Scenario: 主图指标切换 MA / BOLL

- **WHEN** 用户在 K 线图上方选择主图指标
- **THEN** 切到 MA 显示 MA5 / MA10 / MA20 三线主图叠加
- **AND** 切到 BOLL 显示上轨 / 中轨（MA20）/ 下轨三条线 + 价格通道带状区域

#### Scenario: 副图指标切换 MACD / KDJ / RSI

- **WHEN** 用户在 K 线图下方选择副图指标
- **THEN** 切到 MACD 显示 DIF 线 / DEA 线 / MACD 柱（红绿柱）
- **AND** 切到 KDJ 显示 K 线 / D 线 / J 线（J = 3K - 2D 自算）
- **AND** 切到 RSI 显示 RSI6 / RSI12 / RSI24 三条线

#### Scenario: 指标算法与 akquant.talib 口径对齐

- **WHEN** 前端 JS 计算指标值
- **THEN** MACD 参数 `fastperiod=12, slowperiod=26, signalperiod=9`，返回 `(dif, dea, hist)`
- **AND** KDJ 用 `STOCH(fastk_period=9, slowk_period=3, slowd_period=3)` 返回 `(K, D)`，J = 3K - 2D
- **AND** RSI 参数 `timeperiod=6/12/24`
- **AND** BOLL 参数 `timeperiod=20, nbdevup=2.0, nbdevdn=2.0`，返回 `(upper, middle, lower)`
- **AND** 同一输入数据下，前端 JS 算出的值与 `akquant.talib` 算出的值误差 ≤ 0.01

#### Scenario: 指标切换响应快速

- **WHEN** 用户切换指标
- **THEN** K 线图重渲染时间 ≤ 500ms

#### Scenario: 主题切换不破坏图表

- **WHEN** 用户切换三主题（azure / mist / cyber）
- **THEN** K 线图与指标线颜色同步变化
- **AND** 不出现「切换主题后图表颜色不更新」的 bug

#### Scenario: 指标算法异常降级

- **WHEN** K 线数据不足（如 < 26 根，MACD 无法计算）
- **THEN** 主图正常渲染，副图显示「数据不足」占位

---

### Requirement: FR-5 板块概览快照卡片（D5）

仪表盘右侧 SHALL 展示「今日行业板块涨跌 TOP 5」卡片，显示当日申万一级行业板块涨跌排行。

#### Scenario: 卡片显示领涨 TOP 5 + 领跌 TOP 5

- **WHEN** 用户访问仪表盘且 015 模块已上线
- **THEN** 右侧卡片显示 10 个行业条目：领涨 5 个（红色，按涨跌幅降序）+ 领跌 5 个（绿色，按涨跌幅升序）
- **AND** 每条显示行业中文名（如「银行」「非银金融」）+ 今日涨跌幅

#### Scenario: 行业数据来自 index_daily 申万行业指数

- **WHEN** 后端查询行业排行
- **THEN** 取 `index_daily` 表中 `ts_code LIKE '801%.SI' AND trade_date = <今日>` 的数据
- **AND** 通过 `sw_industry` 表映射行业代码到行业中文名

#### Scenario: 015 模块未上线降级

- **WHEN** 015 板块行情模块未上线（`/api/industry/ranking` 接口不可用）
- **THEN** D5 卡片显示「板块行情功能开发中」占位
- **AND** 不阻塞其他 4 项功能正常展示

#### Scenario: 查看全部跳转

- **WHEN** 用户点击「查看全部」链接
- **THEN** 跳转 `/page/sector`（板块行情页）
- **AND** 若 015 模块未上线，链接不可点击或提示「功能开发中」

---

## MODIFIED Requirements

### Requirement: 仪表盘榜单区结构

原仪表盘榜单区为 3 张并排独立卡片（涨幅榜 / 跌幅榜 / 成交额榜），每张卡片独立渲染、无 Tab 切换。

本 spec 扩展为：榜单区重构为 4 Tab 切换布局（涨幅榜 / 跌幅榜 / 成交额榜 / 换手率榜），用户点击 Tab 切换显示对应榜单。后端 `GET /market/ranking` 响应在既有 3 榜基础上加 `topTurnover` 字段，向后兼容。

### Requirement: 大盘指数数据来源

原 `MarketServiceImpl.getMarketIndices()` 返回硬编码 mock 数据（`3368.07` 等固定值），`@Cacheable` 无时效控制。

本 spec 修改为：改为查询 `index_daily` 表取最新交易日 4 个大盘指数真实数据，`@Cacheable` 缓存 key 按交易日区分或每日收盘后显式失效。

---

## REMOVED Requirements

### Requirement: D6 北向资金卡片

**Reason**：原计划用 `moneyflow_hsgt` 接口，该接口不在 `tushare 接口汇总.md` 中，不存在。替代方案 `hk_hold`（沪深股通持股明细）数据粒度是「个股持股明细」非「每日净买入」，不适合做仪表盘总览小卡片。

**Migration**：北向能力改由 016 资金流向模块的「北向 Tab」用 `hk_hold` 接口实现（持股比例曲线 + 十大重仓股聚合），不在仪表盘承担。仪表盘不提供北向资金入口。
