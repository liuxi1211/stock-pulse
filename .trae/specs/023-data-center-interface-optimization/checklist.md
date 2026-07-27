# Checklist

> change-id: `023-data-center-interface-optimization`
> 验证对照 PRD §13 验收标准与 spec.md 各 Scenario。
> 状态：`mvn compile` 通过（412 文件 BUILD SUCCESS）；27 项代码级检查点全部 PASS。
> 图例：[x] 代码级已验证 ｜ [ ] 需运行时验证（需 Tushare token + DB + 实际拉取）

## P0 截断修复

- [x] TushareClient.dailyBasic 方法签名含 `Integer limit` 参数，非空时注入 params
- [x] TushareClient.moneyflow 方法签名含 `Integer limit` 参数，非空时注入 params
- [x] limit=null 时不注入 params（向后兼容）
- [x] BasicDataServiceImpl 调用 dailyBasic 传 limit=10000（FULL_MARKET_LIMIT 常量）
- [x] MoneyFlowServiceImpl 调用 moneyflow 传 limit=10000（MONEYFLOW_FETCH_LIMIT 常量）
- [ ] SQL 验证：某交易日 daily_basic ≈ 5300（运行时）
- [ ] SQL 验证：某交易日 moneyflow ≈ 5050（运行时）

## P1 全量重建分页

- [x] StockBasicServiceImpl 含 BATCH_SIZE/MAX_PAGES 常量，3 状态分页循环
- [x] StockBasicServiceImpl 流式落库（save 路径每页独立提交，无 allStocks 累积）
- [x] StockBasicServiceImpl MAX_PAGES 上限 + warn
- [ ] SQL 验证：stock_basic list_status=L ≈ 5533（运行时）
- [x] TradeCalServiceImpl 含 BATCH_SIZE/MAX_PAGES，分页循环
- [ ] SQL 验证：trade_cal SSE+SZSE 30 年 ≈ 11300（运行时）
- [x] IndexDailyFetchService 含 BATCH_SIZE/MAX_PAGES，分页循环
- [ ] SQL 验证：index_daily 上证指数 30 年 ≈ 7527（运行时）
- [x] 分页终止条件正确（空页/不足页/达 MAX_PAGES 三条件终止，代码级已验证）

## P2-1 B 类事务收窄

- [x] 13 个 B 类 Service 均移除方法级 @Transactional（Grep 复核无残留）
- [x] 13 个 B 类 Service 均注入 TransactionTemplate（构造器注入）
- [x] 13 个 B 类 Service 的 saveBatch 用 transactionTemplate.execute 包裹 delete+insert
- [x] 13 个 Service 的 HTTP 调用在事务外（在 execute 块之前）
- [ ] 故障注入验证：delete 回滚（运行时）
- [ ] DB 连接持有时间 < 1s（运行时监控）

## P2-2 TradeCal 事务拆分

- [x] fetchAndSaveTradeCal 移除方法级 @Transactional
- [x] tushareClient.tradeCal HTTP 在事务外
- [x] saveCalendars 用短事务 TransactionTemplate 包裹
- [x] computeAndSaveRebalanceFlags 用独立 TransactionTemplate 包裹
- [x] saveCalendars 与 computeAndSaveRebalanceFlags 不共享事务

## P2-3 C 类表补齐事务

- [x] StockBasicServiceImpl.saveStocks 用 TransactionTemplate 包裹
- [x] BasicDataServiceImpl.saveDailyBasic 用 TransactionTemplate 包裹
- [x] FinaIndicatorServiceImpl.saveBatch 用 TransactionTemplate 包裹（B->A 重构覆盖）
- [x] BasicDataServiceImpl.saveFinaIndicator 用 TransactionTemplate 包裹（漏检项修复：BasicDataTask 每周日 17:00 定时调用此路径，原为 C 类无事务，已补齐与 saveDailyBasic 一致的范式）
- [ ] 故障注入验证：三表 delete 回滚（运行时）

## P2-4 财务四表并发

- [x] P2-4 在 P2-1（Task 9）完成后进行（同 agent 顺序完成）
- [x] Income/Balancesheet/Cashflow/FinaIndicator 的 fetchAndSaveAllByRange 改虚拟线程并发
- [x] 复用 IO_EXECUTOR 模式（各服务 newVirtualThreadPerTaskExecutor）
- [x] Semaphore(30) 控制并发度
- [x] CompletableFuture 汇总 total（mapToInt(join).sum()）
- [ ] 增量拉取耗时 < 5 分钟（运行时）
- [ ] Tushare 调用频率 < 200 次/分钟（运行时）
- [ ] 并发 vs 串行数据一致（运行时）

## P3-1 辅助表 ann_date 批量（可选）

- [x] dividend/forecast/express 新增 fetchAndSaveByAnnDateRange ann_date 批量方法
- [x] forecast/express 保留 A 类事务范式
- [ ] 验证调用次数从 5000+ 降至几十次（运行时，需 dispatch 切换到 ann_date 路径）

## P3-2 临界表 limit 兜底

- [x] TushareClient.hkHold / marginDetail 增加 limit 参数
- [x] HkHoldServiceImpl / MarginServiceImpl 调用传 limit=10000

## P3-3 分页安全防护

- [x] DailyQuoteServiceImpl 分页循环含 MAX_PAGES（fetchAndSavePagesStreaming + fetchAllPages）
- [x] StockNamechangeServiceImpl 迁移到 queryWithPaging（内置 MAX_PAGES）
- [x] StockStkLimitServiceImpl 分页循环含 MAX_PAGES
- [x] 触达 MAX_PAGES 时打 warn 日志（含 total）并正常退出
- [ ] 调小 MAX_PAGES 构造触达场景验证（运行时）

## P4-1 统一分页能力

- [x] TushareClient 新增 queryWithPaging 方法（回调式）
- [x] 内部循环 offset+=batchSize，每页回调 handler
- [x] 返回 < batchSize 时终止
- [x] 强制 limit 上限 100000 校验（LIMIT_CEILING）
- [x] 含 MAX_PAGES=100 防护与触达告警
- [x] StockNamechangeServiceImpl 迁移到 queryWithPaging 作为试点

## P4-2 字段对齐核查

- [x] 26 个接口字段对齐核查表产出（全部对齐，无需修）
- [x] 唯一缺口 IndexDailyDO 4 字段补 @JSONField（ts_code/trade_date/pre_close/pct_chg）
- [x] 修正后无字段静默丢失

## P4-3 全量重建回滚

- [x] 评估采用 rename+recreate+pull+swap（SQLite/MySQL 均兼容）
- [x] DataInitServiceImpl.doFullRebuild 非 D 类走回滚策略
- [x] D 类日频快照表（DAILY_SNAPSHOT_STEPS）保留不 truncate 路径
- [ ] 验证中途失败时原表数据保留（运行时）

## 通用

- [x] 所有改动遵循 CLAUDE.md 硬约束：无魔法值（常量全大写）、API 参数封装
- [x] 编译通过：`node stock-watcher/run.js compile-dev` → BUILD SUCCESS（412 文件）
- [x] 不引入 engine 侧 sqlite3/sqlalchemy（本 spec 仅涉及 watcher Java 侧）
