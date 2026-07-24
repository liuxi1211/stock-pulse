# 验收清单

## P0 - SQLite 兼容性修复

- [x] DailyQuoteMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法（非 row-value IN）
- [x] AdjFactorMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] DailyBasicMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] CashflowMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] BalancesheetMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] IncomeMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] FinaIndicatorMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] BlockTradeMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法
- [x] HkHoldMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法（如有 row-value IN）
- [x] DividendMapper.xml 的 deleteBatchByKeys 使用 OR 连接语法（如有 row-value IN）
- [x] 所有 deleteBatchByKeys 在 SQLite profile 下可正常执行

## P0 - D 类表全量重建数据丢失修复

- [x] DataInitServiceImpl 中 D 类表（daily_basic, moneyflow, hk_hold, margin, margin_detail, top_list, top_inst, block_trade）的全量重建不执行 truncateTable
- [x] D 类表增量更新查询 MAX(trade_date) 并从该日期（含当天）逐日补全到 today
- [x] D 类表全量重建从可配置回溯期（默认 3 年）逐日拉取到 today
- [x] D 类表日期补全通过 trade_cal 获取交易日列表，逐日调用 fetchAndSave
- [x] D 类表全量重建中途失败时已有数据不受影响（因未 truncate）

## P1 - index_daily 增量修复

- [x] IndexDailyMapper 新增 selectMaxTradeDatePerIndex 方法
- [x] IndexDailyFetchService 增量时按指数查 MAX(trade_date)
- [x] DataInitServiceImpl 中 INDEX_DAILY 增量的 start 不再是 today，而是 MAX(trade_date)（含当天）
- [x] index_daily 增量能补全缺失的历史日期数据

## P1 - daily_quote/adj_factor 起始日期含当天

- [x] DailyQuoteServiceImpl 增量起始日期从 lastDate+1 改为 lastDate
- [x] AdjFactorServiceImpl 增量起始日期从 lastDate+1 改为 lastDate
- [x] 增量后当天数据被重新拉取并幂等覆盖

## P1 - stk_limit 按股票增量

- [x] StockStkLimitMapper 新增 selectLatestDatePerStock 方法
- [x] StockStkLimitServiceImpl 支持按股票从 MAX(trade_date)（含）增量拉取
- [x] DataInitServiceImpl 中 STK_LIMIT 增量使用 preloadLastDateMap + 逐股票增量

## P1 - dividend 按股票增量

- [x] DividendMapper 新增 selectMaxAnnDatePerStock 方法
- [x] DividendServiceImpl 支持按股票从 MAX(ann_date)（含）增量拉取
- [x] DataInitServiceImpl 中 DIVIDEND 增量使用逐股票 MAX(ann_date) 增量

## P1 - namechange/suspend_d 增量调度修复

- [x] StockNamechangeMapper 新增 selectMaxStartDate 方法
- [x] DataInitServiceImpl 中 NAMECHANGE 增量遍历 [MAX(start_date), today] 交易日调用 fetchAndSaveIncremental
- [x] StockSuspendDMapper 新增 selectMaxTradeDate 方法
- [x] DataInitServiceImpl 中 SUSPEND_D 增量遍历 [MAX(trade_date), today] 交易日调用 fetchAndSaveIncremental

## P1 - 财务报表增量优化

- [x] IncomeMapper 新增 selectMaxAnnDatePerStock；增量从 MAX(ann_date)（含）开始
- [x] BalancesheetMapper/ServiceImpl 同上
- [x] CashflowMapper/ServiceImpl 同上
- [x] ForecastMapper/ServiceImpl 同上
- [x] ExpressMapper/ServiceImpl 同上
- [x] FinaIndicatorMapper/ServiceImpl 同上
- [x] DataInitServiceImpl 中 6 张财务表增量不再使用固定 1 年窗口

## P2 - trade_cal/index_weight 增量优化

- [x] TradeCalMapper 新增 selectMaxCalDate；增量从 MAX(cal_date)（含）开始
- [x] IndexWeightMapper 新增 selectMaxTradeDatePerIndex；增量从 MAX(trade_date)（含）开始

## P2 - 事务安全

- [x] DailyQuoteServiceImpl.saveQuotes 有 @Transactional
- [x] AdjFactorServiceImpl.saveAdjFactors 有 @Transactional
- [x] IncomeServiceImpl.saveBatch 有 @Transactional
- [x] BalancesheetServiceImpl.saveBatch 有 @Transactional
- [x] CashflowServiceImpl.saveBatch 有 @Transactional
- [x] FinaIndicatorServiceImpl.saveBatch 有 @Transactional
- [x] DividendServiceImpl.saveDividends 有 @Transactional
- [x] StockStkLimitServiceImpl.saveBatch 有 @Transactional
- [x] StockNamechangeServiceImpl.saveBatch 有 @Transactional
- [x] StockSuspendDServiceImpl.saveBatch 有 @Transactional
- [x] IndexDailyFetchService.saveBatch 有 @Transactional

## 整体一致性

- [x] 所有有日期字段的表，增量起始 = MAX(日期字段)（含当天，非 +1）
- [x] A 类表（stock_basic, sw_industry）保持全量拉取（合理，不改）
- [x] D 类表全量重建不 truncate
- [x] 编译通过无错误
- [x] 无破坏现有定时任务（DailyUpdateTask, BasicDataTask, IndexDailyFetchService.dailySync）
