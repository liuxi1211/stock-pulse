# Tasks

## P0 - 必须立即修复

- [x] Task 1: 修复 deleteBatchByKeys 的 SQLite 兼容性
  - [x] SubTask 1.1: 修复 DailyQuoteMapper.xml - `(ts_code, trade_date) IN` -> OR 连接
  - [x] SubTask 1.2: 修复 AdjFactorMapper.xml - 同上
  - [x] SubTask 1.3: 修复 DailyBasicMapper.xml - `(trade_date, ts_code) IN` -> OR 连接
  - [x] SubTask 1.4: 修复 CashflowMapper.xml - `(ts_code, end_date, report_type) IN` -> OR 连接
  - [x] SubTask 1.5: 修复 BalancesheetMapper.xml - 同上
  - [x] SubTask 1.6: 修复 IncomeMapper.xml - 同上
  - [x] SubTask 1.7: 修复 FinaIndicatorMapper.xml - `(ts_code, end_date) IN` -> OR 连接
  - [x] SubTask 1.8: 修复 BlockTradeMapper.xml - `(trade_date, ts_code, buyer, seller) IN` -> OR 连接
  - [x] SubTask 1.9: 修复 HkHoldMapper.xml - 如有 row-value IN 也修复
  - [x] SubTask 1.10: 修复 DividendMapper.xml - 如有 row-value IN 也修复
  - 参考范式：ExpressMapper.xml 已使用 OR 连接语法

- [x] Task 2: 修复 D 类日频快照表全量重建数据丢失 bug
  - [x] SubTask 2.1: 在 DataInitServiceImpl 中为 D 类表的全量重建跳过 truncateTable
  - [x] SubTask 2.2: 为 D 类表实现日期范围补全方法：查询 MAX(trade_date) -> 遍历 trade_cal 交易日 -> 逐日拉取
  - [x] SubTask 2.3: D 类表全量重建从可配置回溯期（默认 3 年）开始逐日拉取
  - [x] SubTask 2.4: D 类表增量更新也使用日期范围补全（从 MAX(trade_date) 含当天到 today）

## P1 - 高优先级

- [x] Task 3: 修复 index_daily 增量逻辑错误
  - [x] SubTask 3.1: 在 IndexDailyMapper 中新增 selectMaxTradeDatePerIndex 方法
  - [x] SubTask 3.2: 在 IndexDailyFetchService 中实现按指数查 MAX(trade_date) 的增量逻辑
  - [x] SubTask 3.3: 修改 DataInitServiceImpl 中 INDEX_DAILY 的 start 计算：增量时按指数查 MAX(trade_date)（含当天）

- [x] Task 4: 修改 daily_quote/adj_factor 增量起始日期（含当天）
  - [x] SubTask 4.1: DailyQuoteServiceImpl: `startDate = lastDate` 替代 `lastDate.plusDays(1)`
  - [x] SubTask 4.2: AdjFactorServiceImpl: 同上

- [x] Task 5: 实现 stk_limit 按股票增量
  - [x] SubTask 5.1: 在 StockStkLimitMapper 中新增 selectLatestDatePerStock 方法
  - [x] SubTask 5.2: 在 StockStkLimitServiceImpl 中扩展 fetchAndSaveByRange 支持日期范围
  - [x] SubTask 5.3: 在 DataInitServiceImpl 中为 STK_LIMIT 接入 preloadLastDateMap + 逐股票增量

- [x] Task 6: 实现 dividend 按股票增量
  - [x] SubTask 6.1: 在 DividendMapper 中新增 selectMaxAnnDatePerStock 方法
  - [x] SubTask 6.2: 在 DividendServiceImpl 中新增 fetchAndSaveDividendByRange 方法
  - [x] SubTask 6.3: 在 DataInitServiceImpl 中为 DIVIDEND 接入逐股票增量

- [x] Task 7: 修复 namechange/suspend_d 增量调度
  - [x] SubTask 7.1: 在 StockNamechangeMapper 中新增 selectMaxStartDate 方法
  - [x] SubTask 7.2: 在 DataInitServiceImpl 中为 NAMECHANGE 接入日期范围补全
  - [x] SubTask 7.3: 在 StockSuspendDMapper 中新增 selectMaxTradeDate 方法
  - [x] SubTask 7.4: 在 DataInitServiceImpl 中为 SUSPEND_D 接入日期范围补全

- [x] Task 8: 优化财务报表增量（MAX(ann_date) 替代固定 1 年窗口）
  - [x] SubTask 8.1: IncomeMapper 新增 selectMaxAnnDatePerStock；IncomeServiceImpl 加 @Transactional
  - [x] SubTask 8.2: BalancesheetMapper/ServiceImpl 同上
  - [x] SubTask 8.3: CashflowMapper/ServiceImpl 同上
  - [x] SubTask 8.4: ForecastMapper/ServiceImpl 同上
  - [x] SubTask 8.5: ExpressMapper/ServiceImpl 同上
  - [x] SubTask 8.6: FinaIndicatorMapper/ServiceImpl 同上
  - [x] SubTask 8.7: DataInitServiceImpl 中 6 张财务表的 start 计算改为按股票 MAX(ann_date)

## P2 - 中优先级

- [x] Task 9: 优化 trade_cal/index_weight 增量
  - [x] SubTask 9.1: TradeCalMapper 新增 selectMaxCalDate；增量从 MAX(cal_date)（含）开始
  - [x] SubTask 9.2: IndexWeightMapper 新增 selectMaxTradeDatePerIndex；增量从 MAX(trade_date)（含）开始

- [x] Task 10: 为核心表 saveBatch 添加 @Transactional
  - [x] SubTask 10.1: DailyQuoteServiceImpl.saveQuotes 添加 @Transactional
  - [x] SubTask 10.2: AdjFactorServiceImpl.saveAdjFactors 添加 @Transactional
  - [x] SubTask 10.3: IncomeServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.4: BalancesheetServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.5: CashflowServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.6: FinaIndicatorServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.7: DividendServiceImpl.saveDividends 添加 @Transactional
  - [x] SubTask 10.8: StockStkLimitServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.9: StockNamechangeServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.10: StockSuspendDServiceImpl.saveBatch 添加 @Transactional
  - [x] SubTask 10.11: IndexDailyFetchService.saveBatch 添加 @Transactional

# Task Dependencies

- Task 2 依赖 Task 1（修复后的 deleteBatchByKeys 在日期补全写入时使用）
- Task 5/6/7 依赖各自的 Mapper 新增方法（子任务内自带）
- Task 8 依赖各自的 Mapper 新增方法（子任务内自带）
- Task 3/5/6/7/8/9 均需修改 DataInitServiceImpl，建议串行修改避免冲突
- Task 1/10 可与其他任务并行
