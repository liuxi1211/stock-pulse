# Tasks

> **实施顺序**：后端正确�?性能基线 �?后端功能补齐 �?前端修复 �?前端功能展示 �?联调验证
> **依赖**�?19-sector-market 已交付；数据（stock_moneyflow/daily_basic/index_daily/stock_stk_limit/sw_industry_member）均已落�?
## 阶段一：后端正确性与性能基线（P0/P1�?
- [x] Task 1: 修复成分股分�?NULL 排序错位
  - 修改 `DailyQuoteMapper.xml` �?`selectMembersWithQuote`：`ORDER BY q.pct_chg DESC` �?`ORDER BY (q.pct_chg IS NULL), q.pct_chg DESC`
  - 验证：停牌股沉底，跨页不错位

- [x] Task 2: 注册 Caffeine 缓存空间
  - �?`CacheConfig` 注册 `sectorRanking`（key=tradeDate，TTL 到当�?23:59 �?4h）和 `stockBasicName` 缓存空间
  - 验证：缓存空间存在，与现�?kline/factorList 风格一�?
- [x] Task 3: ranking 接口加缓�?+ stock_basic 改全�?  - `SwIndustryServiceImpl.getIndustryRanking` �?`@Cacheable("sectorRanking", key="#tradeDate")`
  - `buildStockNameMap` �?`selectList(in(tsCode))` 改为全量 `selectList(null)` + 缓存
  - 数据同步任务（IndexDailyFetchService/MoneyflowDataTask/DailyQuoteTask）完成后触发 `sectorRanking` 淘汰（`@CacheEvict`�?  - 验证：二次请求命中缓存不�?DB

- [x] Task 4: index-daily 参数校验�?reverse 防副作用
  - `IndexDailyController.query`：tsCode 加正�?`[A-Z0-9]{6,9}(\.(SH|SZ|SI))?` 校验；limit 限制 1~3650，超界返�?400
  - `Collections.reverse(list)` 改为 `Collections.reverse(new ArrayList<>(list))` �?Service 直接返回 ASC
  - 验证：非�?tsCode 返回 400；limit=999999 返回 400；反转无副作�?
- [x] Task 5: members 接口 tradeDate 透传
  - `SwIndustryController.members` 新增 `@RequestParam(required=false) String tradeDate`（`\d{8}` 校验），透传�?Service
  - 验证：`?tradeDate=20240115` 返回历史数据

## 阶段二：后端功能补齐（缺�?1/2/3/4�?
- [x] Task 6: 新建涨跌停阈值公共常�?  - 新建 `constant/MarketThresholdConstants.java`：主�?9.9 / 创业�?科创�?19.9 / 北交所 29.9 / ST 4.9
  - `MarketServiceImpl.getMarketTemperature` 改用此常量（回归验证市场温度功能不变�?  - 验证：两处阈值单一数据�?
- [x] Task 7: 涨跌/涨跌停家数后端统计（缺口2�?  - `IndustryRankingVO` 新增 `upCount/downCount/limitUpCount/limitDownCount`
  - `buildIndustryRankingVO` 遍历 memberQuotes 时按 pct_chg 正负 + `MarketThresholdConstants` 统计（需 stock_basic �?market/name 判断板块类型�?ST�?  - 验证：upCount+downCount+平盘=activeCount；limitUpCount 阈值正�?
- [x] Task 8: 板块轮动趋势 N 日涨跌幅（缺�?�?  - `IndustryRankingVO` 新增 `pctChg5d/pctChg20d`
  - `getIndustryRanking` 批量取各行业指数�?20 �?close（复�?IndexDailyService 或新增批量方法），计�?close[-1]/close[-1-N]-1
  - 验证：pctChg5d �?index_daily 数据吻合

- [x] Task 9: 板块资金流聚合（缺口1�?  - 新建 `IndustryMoneyflowVO`（indexCode/industryName/mainNetInflow/elgNetInflow/lgNetInflow/mdNetInflow/smNetInflow/tradeDate�?  - 新增 `SwIndustryService.getIndustryMoneyflow(tradeDate)` + `GET /api/industry/moneyflow` 端点
  - `stock_moneyflow` JOIN `sw_industry_member`(is_new=1) �?index_code 聚合 `SUM(net_mf_amount)` 与主力（buy_lg+buy_elg-sell_lg-sell_elg�?  - �?`@Cacheable` �?ranking
  - 验证：返�?28 条聚合数据，主力净流入正确

- [x] Task 10: 板块估值聚合（缺口4�?  - 新建 `IndustryValuationVO`（indexCode/industryName/peTtm/pb/peMedian/tradeDate�?  - 新增 `SwIndustryService.getIndustryValuation(tradeDate)` + `GET /api/industry/valuation` 端点
  - `daily_basic` JOIN `sw_industry_member` �?index_code 市值加�?PE（SUM(pe_ttm*total_mv)/SUM(total_mv)�?  - �?`@Cacheable`
  - 验证：PE 加权计算正确

## 阶段三：前端交互修复（P1/P2�?
- [x] Task 11: 刷新按钮完整刷新
  - `sector.js` `btnRefresh` 点击：`loadRankingData()` 后若�?`currentIndustry` �?`selectIndustry(currentIndustry)`
  - 验证：点刷新�?K�?成分股同步更�?
- [x] Task 12: 成分股分页边界保�?  - `renderMembers` 计算 totalPages，到末页 `nextBtn.disabled=true`，非末页恢复
  - 验证：末页无法点下一�?
- [x] Task 13: 排序状态视觉指�?  - `renderRankingTable` 根据 sortState 动态设置表头图�?`bi-arrow-up`/`bi-arrow-down`/`bi-arrow-down-up`
  - 验证：点击排序后图标正确变化

- [x] Task 14: 热度�?Tooltip 防溢�?+ 成交额格式统一
  - `moveTooltip` 判断�?下边缘溢出时调整定位
  - 成分股成交额�?`formatVolume` 改为 `StockApp.formatNumber(amount,1)+'�?`
  - 验证：Tooltip 不溢出视口；成交额单位一�?
- [x] Task 15: K线范�?+ 死按�?+ 行点击统一
  - `all` 范围�?9999 改为 3650
  - 成分股搜索按钮绑�?click（或移除�?  - 排行表行点击�?onclick 内联改为 addEventListener（保�?window.SectorPage 兼容或迁移）
  - 验证：全部范围拉 3650；搜索按钮可用；行点击正�?
## 阶段四：前端功能展示（对接阶段二�?
- [x] Task 16: 排行表展示新数据�?  - 排行表增加：领涨/领跌家数、主力净流入、近5日涨跌幅（可默认显示，PE/20日折叠）
  - 热度�?Tooltip 增加家数、资金流数据
  - 验证：新列渲染正确，红涨绿跌

- [x] Task 17: 个股反查板块入口（缺�?�?  - 个股详情页（017/024 �?stock-detail 模板）加"所属板�?标签，调 `/api/industry/list` 或新增轻量反查接口，点击�?`/page/sector?industryCode=`
  - `sector.js` 支持�?URL 参数预选行�?  - 验证：从个股页可跳回板块页并定位行业

## 阶段五：联调验证

- [x] Task 18: 编译 + 端到端验�?  - 编译通过：`node stock-watcher/run.js compile-dev`（BUILD SUCCESS�?  - 回归验证：市场温度（017）、仪表盘板块概览�?16）功能不�?  - 新功能验证：ranking 缓存命中、家�?N�?资金�?估值端点、前端交互修�?  - 性能验证：ranking 二次请求 < 50ms（缓存命中）

# Task Dependencies

- Task 1 独立（XML 改动�?- Task 2 独立（缓存配置）
- Task 3 依赖 Task 2（缓存空间就绪）
- Task 4, 5 独立
- Task 6 独立（常量抽取）
- Task 7 依赖 Task 6（用阈值常量）
- Task 8 依赖 Task 3（在 ranking 聚合内扩展，需缓存配合�?- Task 9, 10 依赖 Task 2（缓存）
- Task 11~15 前端修复，互相独立，依赖对应后端就绪（Task 5 for 分页历史�?- Task 16 依赖 Task 7, 8, 9, 10（后端新字段/端点就绪�?- Task 17 依赖个股详情页存�?- Task 18 依赖全部
