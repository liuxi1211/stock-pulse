# 板块行情验收问题修复 Spec

> **来源**：`板块行情模块功能验收与代码审核报告.md`（P0/P1/P2/P3 全量问题，概念板块除外）
> **change-id**：`025-sector-audit-fix`
> **基线**：stock-watcher（Java 21 + Spring Boot 4.0.6）
> **页面路由**：`/page/sector`
> **状态**：规划中

---

## Why

019-sector-market 已交付并通过基础验收，但深度审核发现 2 个必须修复的技术债（ranking 接口全量加载 17820 条 + 无缓存、成分股分页 NULL 排序错位）与一批影响体验/健壮性的问题，同时老股民视角缺 4 项高价值功能（资金流、涨跌家数、轮动趋势、估值）。除概念板块（需从零建数据链路，明确不做）外，其余问题所需数据均已落库，且与现有 moneyflow/market/daily_basic 模块不重复���本 spec 集中修复这些问题，补齐板块分析的核心灵魂数据。

---

## What Changes

### 后端（stock-watcher）

#### A. 性能修复（P0）
- **Caffeine 缓存 ranking 结果**：在 `CacheConfig` 注册 `sectorRanking` 缓存空间，key=tradeDate，TTL 到当日收盘后（数据一天一变）；`getIndustryRanking` 包裹 `@Cacheable`，同步淘汰在数据同步任务里触发
- **stock_basic 名称查询改全量缓存**：`buildStockNameMap` 由 `selectList(in(tsCode))` 改为全量 `selectList(null)` 建 Map（5000 行小表，避免超长 IN），并加 Caffeine 缓存

#### B. 正确性修复（P0/P1）
- **成分股分页 NULL 排序**：`selectMembersWithQuote` 的 `ORDER BY q.pct_chg DESC` 改为 `ORDER BY (q.pct_chg IS NULL), q.pct_chg DESC`，让停牌/缺行情股沉底，消除跨页错位
- **index-daily limit 上限**：`/api/index-daily` 的 `limit` 限制 1~3650，超界返回 400
- **index-daily tsCode 校验**：新增正则 `[A-Z0-9]{6,9}(\.(SH|SZ|SI))?`，非法返回 400
- **members tradeDate 透传**：`SwIndustryController.members` 新增 `@RequestParam(required=false) String tradeDate` 透传给 Service（含 `\d{8}` 校验），解除"只能看最新交易日"限制
- **Collections.reverse 副作用**：`IndexDailyController` 改用 `new ArrayList<>(list)` 后反转，或 Service 直接返回 ASC

#### C. 功能补齐（数据均已落库，无需新表/新对接 Tushare）
- **缺口 2 涨跌/涨跌停家数**：`IndustryRankingVO` 新增 `upCount/downCount/limitUpCount/limitDownCount`；`getIndustryRanking` 6 步聚合遍历 `memberQuotes` 时统计；**涨跌停阈值抽取公共常量类**（`MarketThresholdConstants`），`MarketServiceImpl.getMarketTemperature` 与本模块共用，避免硬编码漂移
- **缺口 3 板块轮动趋势**：`IndustryRankingVO` 新增 `pctChg5d/pctChg20d`；`getIndustryRanking` 用 `IndexDailyService.getByCodesAndTradeDate` 或新增批量方法取各行业指数近 5/20 日 close 计算区间涨跌幅
- **缺口 1 板块资金流**：新增 `GET /api/industry/moneyflow?tradeDate=` 端点 + `SwIndustryService.getIndustryMoneyflow(tradeDate)`，`stock_moneyflow` JOIN `sw_industry_member`(is_new=1) 按 `index_code` 聚合 `SUM(net_mf_amount)` 与主力净额（`buy_lg+buy_elg-sell_lg-sell_elg`）；`IndustryRankingVO` 或新 VO 承载
- **缺口 4 板块估值**��新增 `GET /api/industry/valuation?tradeDate=` 端点 + `getIndustryValuation(tradeDate)`，`daily_basic` JOIN `sw_industry_member` 按 `index_code` 做市值加权 PE（`SUM(pe_ttm*total_mv)/SUM(total_mv)`）与算术平均 PB
- **缺口 6 个股反查板块**：个股详情页（017/024）加"所属板块"标签，复用 `SwIndustryMemberMapper.selectCurrentL1ByTsCode`（前端入口，后端无新接口）

#### D. SQL 优化（P2）
- **成分股搜索 LIKE**：保持 `%keyword%`（stock_basic 仅 5000 行，影响小），加注释说明；后续表变大再优化

### 前端（stock-watcher 静态资源）

#### E. 交互修复（P1/P2）
- **刷新按钮**：`btnRefresh` 点击除 `loadRankingData` 外，若有 `currentIndustry` 则同步触发 `selectIndustry(currentIndustry)` 刷新 K线+成分股
- **成分股下一页边界**：`renderMembers` 计算 `totalPages`，到末页禁用 next 按钮
- **排序状态视觉指示**：表头箭头根据 `sortState` 动态切 `bi-arrow-up`/`bi-arrow-down`/`bi-arrow-down-up`
- **热度图 Tooltip 防溢出**：`moveTooltip` 判断 `clientX + tooltipWidth > innerWidth` 时左偏移
- **成交额格式化统一**：成分股成交额由 `formatVolume` 改为与排行榜一致的 `formatNumber(x,1)+'亿'`
- **K线"全部"范围**：`all` 由 `9999` 改为 `3650`（10 年）
- **死按钮修复**：成分股搜索按钮绑定 click 触发搜索（或移除）
- **行点击事件统一**：`onclick` 内联改为 `addEventListener`（移除 window.SectorPage 隐式全局，保留兼容）

#### F. 功能展示（对接后端 C）
- **排行表新增列**：领涨/领跌家数、主力净流入、PE、近5日/20日涨跌幅（可折叠或默认显示关键列）
- **热度图 Tooltip 增强**：增加资金流、家数数据
- **板块资金流/估值可视化**：排行表增加对应列，或独立小卡片

### 不做（明确排除）
- **概念板块（缺口 5）**：无表无对接，需从零建数据链路，本 spec 不处理
- **K线周期切换（日/周/月）**：维持日线
- **renderKline 公共抽取**：维持复制实现，不做重构
- **多板块对比雷达图**：v2 规划

---

## Impact

### Affected specs
- **上游**：`019-sector-market`（本 spec 是其验收修复增量）
- **弱关联**：`017-market-center-completion`（抽取 `MarketThresholdConstants` 共用，需回归 `getMarketTemperature`）

### Affected code

| 文件 | 改动 |
|---|---|
| `config/CacheConfig.java` | **修改**：注册 `sectorRanking`、`stockBasicName` 缓存空间 |
| `service/impl/SwIndustryServiceImpl.java` | **修改**：加 `@Cacheable`；`buildStockNameMap` 改全量；家数/N日趋势统计；新增 moneyflow/valuation 方法 |
| `service/SwIndustryService.java` | **修改**：新增 `getIndustryMoneyflow`/`getIndustryValuation` 签名 |
| `controller/SwIndustryController.java` | **修改**：members 加 tradeDate 参数；新增 moneyflow/valuation 端点 |
| `controller/IndexDailyController.java` | **修改**：limit 上限校验、tsCode 正则校验、reverse 防副作用 |
| `mapper/DailyQuoteMapper.xml` | **修改**：`ORDER BY (q.pct_chg IS NULL), q.pct_chg DESC` |
| `mapper/SwIndustryMemberMapper.java` | **修改**：新增按 index_code 列表批量查（资金流/估值 JOIN 用，若需） |
| `vo/IndustryRankingVO.java` | **修改**：新增 upCount/downCount/limitUpCount/limitDownCount/pctChg5d/pctChg20d 字段 |
| `vo/IndustryMoneyflowVO.java` | **新建**：板块资金流视图对象 |
| `vo/IndustryValuationVO.java` | **新建**：板块估值视图对象 |
| `constant/MarketThresholdConstants.java` | **新建**：涨跌停阈值常量（主板9.9/创科19.9/北交29.9/ST4.9） |
| `service/impl/MarketServiceImpl.java` | **修改**：`getMarketTemperature` 改用 `MarketThresholdConstants`（回归验证） |
| `static/js/sector.js` | **修改**：刷新/分页/排序/Tooltip/格式化/范围修复 + 新数据展示 |
| `static/css/sector.css` | **修改**：新增列/卡片样式（如需） |

---

## ADDED Requirements

### Requirement: 行业排行缓存（P0-性能1）
系统 SHALL 对 `GET /api/industry/ranking` 的结果启用 Caffeine 本地缓存，key 为 tradeDate，TTL 到当日收盘后，避免每次请求全量加载 17820 条成分股 + 大表 IN 查询。

#### Scenario: 首次请求
- **WHEN** 首次调用 `GET /api/industry/ranking`
- **THEN** 执行 6 步聚合，结果写入缓存

#### Scenario: 缓存命中
- **WHEN** 同一 tradeDate 再次调用
- **THEN** 直接返回缓存，不查 DB

#### Scenario: 缓存淘汰
- **WHEN** 数据同步任务（IndexDailyFetchService/MoneyflowDataTask）完成后
- **THEN** 触发对应 tradeDate 的缓存淘汰（或依赖 TTL 自然过期）

---

### Requirement: 涨跌/涨跌停家数（缺口2）
`IndustryRankingVO` SHALL 新增 `upCount/downCount/limitUpCount/limitDownCount` 字段，由后端在 6 步聚合遍历成分股行情时统计；涨跌停判定 SHALL 使用与市场温度一致的阈值常量。

#### Scenario: 正常统计
- **WHEN** 调用 `GET /api/industry/ranking`
- **THEN** 每个行业记录含 4 个家数字段，upCount+downCount+平盘=activeCount

#### Scenario: 阈值一致性
- **WHEN** 某主板股票 pct_chg=9.95
- **THEN** 计入 limitUpCount（阈值 9.9，与 MarketServiceImpl 一致）

---

### Requirement: 板块资金流聚合（缺口1）
系统 SHALL 提供 `GET /api/industry/moneyflow?tradeDate=` 端点，返回 28 个行业的资金流聚合（主力净流入、超大单/大单/中单/小单净额），由 `stock_moneyflow` JOIN `sw_industry_member` 按 index_code 聚合。

#### Scenario: 正常聚合
- **WHEN** 调用 `GET /api/industry/moneyflow`
- **THEN** 返回 28 条 `IndustryMoneyflowVO`，含主力净流入（大单+特大单净额）

---

### Requirement: 板块估值聚合（缺口4）
系统 SHALL 提供 `GET /api/industry/valuation?tradeDate=` 端点，返回 28 个行业的加权 PE/PB，由 `daily_basic` JOIN `sw_industry_member` 按市值加权计算。

#### Scenario: 市值加权 PE
- **WHEN** 调用 `GET /api/industry/valuation`
- **THEN** 返回 28 条 `IndustryValuationVO`，PE = SUM(pe_ttm*total_mv)/SUM(total_mv)

---

## MODIFIED Requirements

### Requirement: 行业成分股接口（FR-6 / S3）
`GET /api/industry/members` SHALL 新增可选 `tradeDate` 参数（`\d{8}` 校验），透传给 Service，支持查询历史交易日成分股行情。

#### Scenario: 查询历史交易日
- **WHEN** 调用 `GET /api/industry/members?industryCode=801010&tradeDate=20240115`
- **THEN** 返回该交易日的成分股行情

#### Scenario: NULL 排序稳定
- **WHEN** 某成分���当日停牌（pct_chg 为 NULL）
- **THEN** 该股排在涨幅榜末尾（NULL 沉底），跨页不错位

---

### Requirement: 指数日线查询接口（FR-6 / S4）
`GET /api/index-daily` SHALL 对 `tsCode` 做格式校验、对 `limit` 做 1~3650 范围校验，非法返回 400；返回前 SHALL 避免原地反转 Service 结果。

#### Scenario: tsCode 非法
- **WHEN** 调用 `GET /api/index-daily?tsCode='; DROP TABLE--`
- **THEN** 返回 400，不查 DB

#### Scenario: limit 超界
- **WHEN** 调用 `GET /api/index-daily?tsCode=801010.SI&limit=999999`
- **THEN** 返回 400「limit 必须在 1~3650」

---

### Requirement: 板块轮动趋势（缺口3）
`IndustryRankingVO` SHALL 新增 `pctChg5d/pctChg20d` 字段，由行业指数近 5/20 日 close 计算区间涨跌幅。

#### Scenario: 近 5 日涨跌幅
- **WHEN** 调用 `GET /api/industry/ranking`
- **THEN** 每条记录含 pctChg5d（近 5 个交易日区间涨跌幅）

---

## 前端交互 Requirements（汇总）

### Requirement: 刷新按钮完整刷新
点击刷新按钮 SHALL 同时重新加载排行数据、K线图、成分股（当前选中行业）。

### Requirement: 成分股分页边界保护
成分股列表 SHALL 在到达最后一页时禁用"下一页"按钮，防止越界请求空列表。

### Requirement: 排序状态视觉指示
排行表表头 SHALL 根据当前排序状态显示单向/双向箭头图标。

### Requirement: 热度图 Tooltip 防溢出
热度图 Tooltip SHALL 在接近视口右/下边缘时自动调整定位，不产生横向滚动条。

### Requirement: 成交额格式统一
成分股成交额 SHALL 与排行榜成交额使用统一格式化逻辑（亿元）。

---

## REMOVED Requirements

### Requirement: 概念板块维度（缺口5）
**Reason**：`concept`/`concept_detail` 接口未对接、无表无数据，需从零搭建全链路（建表+对接 Tushare+Service+Controller），工作量大，���确不在本 spec 范围。
**Migration**：作为 v2 规划，本 spec 不处理。
