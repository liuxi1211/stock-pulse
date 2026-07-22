# Tasks

## 阶段一：数据采集层（7 张表，可并行）

- [x] Task 1: 新增 stock_moneyflow 表数据层
  - [x] SubTask 1.1: `schema-sqlite.sql` + `schema-mysql.sql` 新增 `stock_moneyflow` 表 DDL（复合主键 ts_code+trade_date，按 net_mf_amount 等字段建索引）
  - [x] SubTask 1.2: `TushareApiEnum` 新增 `MONEYFLOW("moneyflow", "ts_code,trade_date,buy_sm_amount,sell_sm_amount,buy_sm_vol,sell_sm_vol,buy_md_amount,sell_md_amount,buy_md_vol,sell_md_vol,buy_lg_amount,sell_lg_amount,buy_lg_vol,sell_lg_vol,buy_elg_amount,sell_elg_amount,buy_elg_vol,sell_elg_vol,net_mf_amount,net_mf_vol")`
  - [x] SubTask 1.3: 新建 `MoneyflowDTO`（`@JSONField` 映射 snake_case）
  - [x] SubTask 1.4: 新建 `MoneyflowDO`（`@TableName("stock_moneyflow")`）
  - [x] SubTask 1.5: 新建 `MoneyflowMapper` + `MoneyflowMapper.xml`（insertBatch / deleteBatchByKeys / 查询方法）
  - [x] SubTask 1.6: 新建 `MoneyflowService` + `MoneyflowServiceImpl`（fetchAndSave + queryTop + queryDetail）
  - [x] SubTask 1.7: `TushareClient` 新增 `moneyflow(tradeDate, tsCode)` 方法

- [x] Task 2: 新增 hk_hold 表数据层
  - [x] SubTask 2.1: 两个 schema 文件新增 `hk_hold` 表 DDL（复合主键 trade_date+code，含 vol/ratio/exchange_id 字段）
  - [x] SubTask 2.2: `TushareApiEnum` 新增 `HK_HOLD("hk_hold", "trade_date,code,name,vol,ratio,ts_code,exchange_id")`
  - [x] SubTask 2.3: 新建 `HkHoldDTO`
  - [x] SubTask 2.4: 新建 `HkHoldDO`（`@TableName("hk_hold")`）
  - [x] SubTask 2.5: 新建 `HkHoldMapper` + XML（insertBatch / deleteBatchByKeys / ratioTrend / topHoldings / detailByCode）
  - [x] SubTask 2.6: 新建 `HkHoldService` + Impl（fetchAndSave + queryRatioTrend + queryTopHoldings + queryDetail）
  - [x] SubTask 2.7: `TushareClient` 新增 `hkHold(tradeDate, tsCode, exchangeId)` 方法

- [x] Task 3: 新增 top_list + top_inst 表数据层
  - [x] SubTask 3.1: 两个 schema 文件新增 `top_list` 表 DDL（复合主键 trade_date+ts_code+reason）
  - [x] SubTask 3.2: 两个 schema 文件新增 `top_inst` 表 DDL（复合主键 trade_date+ts_code+exalter+side）
  - [x] SubTask 3.3: `TushareApiEnum` 新增 `TOP_LIST("top_list", "trade_date,ts_code,name,close,pct_change,turnover_rate,amount,l_buy,l_sell,l_buy_amount,l_sell_amount,net_amount,b_amount,s_amount,reason")` 和 `TOP_INST("top_inst", "trade_date,ts_code,exalter,side,buy,buy_rate,sell,sell_rate,net_buy")`
  - [x] SubTask 3.4: 新建 `TopListDTO` + `TopInstDTO`
  - [x] SubTask 3.5: 新建 `TopListDO` + `TopInstDO`
  - [x] SubTask 3.6: 新建 `TopListMapper` + `TopInstMapper` + 对应 XML（insertBatch / deleteBatchByKeys / 查询）
  - [x] SubTask 3.7: 新建 `TopListService` + Impl（fetchAndSaveTopList + fetchAndSaveTopInst + queryList + queryInst + queryNotable）
  - [x] SubTask 3.8: `TushareClient` 新增 `topList(tradeDate, tsCode)` 和 `topInst(tradeDate, tsCode)` 方法

- [x] Task 4: 新增 block_trade 表数据层
  - [x] SubTask 4.1: 两个 schema 文件新增 `block_trade` 表 DDL（复合主键 trade_date+ts_code+buyer+seller）
  - [x] SubTask 4.2: `TushareApiEnum` 新增 `BLOCK_TRADE("block_trade", "trade_date,ts_code,name,price,vol,amount,buyer,seller,buyer_name,seller_name")`
  - [x] SubTask 4.3: 新建 `BlockTradeDTO`
  - [x] SubTask 4.4: 新建 `BlockTradeDO`（`@TableName("block_trade")`）
  - [x] SubTask 4.5: 新建 `BlockTradeMapper` + XML（insertBatch / deleteBatchByKeys / 分页查询 / 溢价率分桶统计）
  - [x] SubTask 4.6: 新建 `BlockTradeService` + Impl（fetchAndSave + queryPage + queryPremiumDistribution，溢价率计算需 join daily_quote 取 close）
  - [x] SubTask 4.7: `TushareClient` 新增 `blockTrade(tradeDate, tsCode)` 方法

- [x] Task 5: 新增 margin + margin_detail 表数据层
  - [x] SubTask 5.1: 两个 schema 文件新增 `margin` 表 DDL（复合主键 exchange_id+trade_date）
  - [x] SubTask 5.2: 两个 schema 文件新增 `margin_detail` 表 DDL（复合主键 trade_date+ts_code）
  - [x] SubTask 5.3: `TushareApiEnum` 新增 `MARGIN("margin", "exchange_id,trade_date,rzye,rzmre,rzche,rqye,rqmcl,rzrqye")` 和 `MARGIN_DETAIL("margin_detail", "trade_date,ts_code,name,rzye,rqye,rzmre,rzche,rqmcl,rzrqye")`
  - [x] SubTask 5.4: 新建 `MarginDTO` + `MarginDetailDTO`
  - [x] SubTask 5.5: 新建 `MarginDO` + `MarginDetailDO`
  - [x] SubTask 5.6: 新建 `MarginMapper` + `MarginDetailMapper` + 对应 XML（insertBatch / deleteBatchByKeys / 趋势查询 / TOP 查询）
  - [x] SubTask 5.7: 新建 `MarginService` + Impl（fetchAndSaveMargin + fetchAndSaveMarginDetail + queryTrend + queryDetailTop）
  - [x] SubTask 5.8: `TushareClient` 新增 `margin(tradeDate, exchangeId)` 和 `marginDetail(tradeDate, tsCode)` 方法

- [x] Task 6: 新增定时任务
  - [x] SubTask 6.1: 新建 `MoneyflowDataTask.java`，`@Scheduled` 每个交易日 16:10 后错峰拉取 7 张表（每个表独立 try-catch，互不影响）
  - [x] SubTask 6.2: 新建 `MoneyflowDataInitController`，`POST /api/moneyflow-data/fetch?tradeDate=` 支持手动触发历史日期补拉

## 阶段二：查询 Controller 层（5 个 Controller，可并行）

- [x] Task 7: MoneyflowController（`/api/moneyflow`）
  - [x] SubTask 7.1: `GET /top?tradeDate=&limit=50&sortBy=net_mf_amount&order=desc` - TOP 50 主力净流入排行
  - [x] SubTask 7.2: `GET /detail?tsCode=&days=30` - 单股近 N 日资金流向（供个股诊断复用）

- [x] Task 8: HkHoldController（`/api/hk-hold`）
  - [x] SubTask 8.1: `GET /ratio-trend?days=30&exchangeId=SH|SZ|ALL` - 持股比例时序（按 trade_date 聚合 sum(ratio)）
  - [x] SubTask 8.2: `GET /top-holdings?tradeDate=&exchangeId=SH|SZ|ALL&limit=10` - 十大重仓股（按 vol 降序）
  - [x] SubTask 8.3: `GET /detail?tsCode=&days=30` - 单股北向持股时序（供个股诊断复用）

- [x] Task 9: TopListController（`/api/top-list`）
  - [x] SubTask 9.1: `GET /?tradeDate=` - 当日龙虎榜个股列表
  - [x] SubTask 9.2: `GET /inst?tradeDate=&tsCode=` - 指定个股营业部席位明细
  - [x] SubTask 9.3: `GET /inst/notable?tradeDate=` - 知名游资/机构席位汇总（按 exalter 模糊匹配）

- [x] Task 10: BlockTradeController（`/api/block-trade`）
  - [x] SubTask 10.1: `GET /?tradeDate=&page=1&size=20&sortBy=amount&order=desc` - 分页明细
  - [x] SubTask 10.2: `GET /premium-distribution?tradeDate=` - 溢价率分桶统计

- [x] Task 11: MarginController（`/api/margin`）
  - [x] SubTask 11.1: `GET /trend?days=30&exchangeId=SSE|SZSE|ALL` - 余额趋势时序
  - [x] SubTask 11.2: `GET /detail/top?tradeDate=&limit=50&sortBy=rzrqye&order=desc` - TOP 50 个股明细

## 阶段三：前端页面层

- [x] Task 12: 页面骨架与路由
  - [x] SubTask 12.1: `PageController` 新增 `GET /page/moneyflow`（pageTitle="资金流向"，activeMenu="moneyflow"）
  - [x] SubTask 12.2: `fragments/common.html` 侧边栏"主要功能"末尾新增"资金流向"菜单项（图标 `bi-cash-coin`）
  - [x] SubTask 12.3: 新建 `templates/pages/moneyflow.html` - 套用 fragments 骨架，5 Tab 导航 + 日期选择器 + 刷新按钮
  - [x] SubTask 12.4: 新建 `static/css/moneyflow.css` - Tab 容器布局 + 表格 + 图表容器高度

- [x] Task 13: 前端 Tab A 个股资金流向
  - [x] SubTask 13.1: 懒加载机制（首次切换才请求，缓存已加载 Tab）
  - [x] SubTask 13.2: 主力净流入 TOP 10 横向柱状图（ECharts，红涨绿跌，点击跳转个股诊断）
  - [x] SubTask 13.3: TOP 50 排行表（表头排序，金额格式化，诊断跳转按钮）

- [x] Task 14: 前端 Tab B 北向资金
  - [x] SubTask 14.1: 持股比例变化曲线（3 条折线：沪股通/深股通/北向合计，时间范围切换 7/30/90 日）
  - [x] SubTask 14.2: 十大重仓股表格（沪/深/合计子视图切换，按 vol 降序，点击跳转）
  - [x] SubTask 14.3: Tab 顶部固定说明"不含净买入数据"

- [x] Task 15: 前端 Tab C 龙虎榜
  - [x] SubTask 15.1: 龙虎榜个股列表表（含上榜原因、净买入额，按 net_amount 降序）
  - [x] SubTask 15.2: 「展开席位」展开买入/卖出席位 TOP 5 子表（懒加载，折叠动画）
  - [x] SubTask 15.3: 右侧知名游资/机构卡片（点击跳转该游资当日上榜个股）

- [x] Task 16: 前端 Tab D 大宗交易
  - [x] SubTask 16.1: 溢价率分布柱状图（8 档分桶，红涨绿跌）
  - [x] SubTask 16.2: 大宗交易明细表（分页 20 条/页，溢价率涨跌色，金额格式化，诊断跳转）

- [x] Task 17: 前端 Tab E 融资融券
  - [x] SubTask 17.1: 融资余额/融券余额双轴折线图（左轴融资/右轴融券，沪/深/合计切换，7/30/90 日）
  - [x] SubTask 17.2: 个股融资融券明细 TOP 50 表（按 rzrqye 降序，表头排序，诊断跳转）

- [x] Task 18: 前端通用逻辑与降级
  - [x] SubTask 18.1: 日期选择器变更时重新加载当前激活 Tab
  - [x] SubTask 18.2: 非交易日/无数据降级提示
  - [x] SubTask 18.3: 接口异常"加载失败"提示，不影响其他 Tab
  - [x] SubTask 18.4: ECharts 实例统一用 ChartsTheme，主题切换同步更新
  - [x] SubTask 18.5: 窗口 resize 时图表自适应

# Task Dependencies

- Task 1-5（数据层）互不依赖，可全部并行
- Task 6（定时任务）依赖 Task 1-5 的 Service 完成
- Task 7-11（Controller）依赖对应数据层 Task 完成（如 Task 7 依赖 Task 1，Task 8 依赖 Task 2，以此类推；Task 9 依赖 Task 3，Task 10 依赖 Task 4，Task 11 依赖 Task 5）
- Task 12（页面骨架）无后端依赖，可独立先行
- Task 13-17（前端 Tab）依赖 Task 12 骨架 + 对应 Controller（如 Task 13 依赖 Task 7+12）
- Task 18（通用逻辑）依赖 Task 13-17 基本可用
