# Tasks

> change-id: `023-data-center-interface-optimization`
> 来源 PRD：`sdlc/prd/020-数据管理中心接口优化/PRD-数据管理中心接口优化.md`
> 排期遵循 PRD §12 依赖图：P0/P1 无依赖可先做；P2-4 依赖 P2-1；P3 无依赖；P4 长期无依赖。
> 状态：全部完成，`mvn compile` 通过（412 文件 BUILD SUCCESS），27 项代码级检查点 PASS。

## P0 紧急修复：单日全市场截断（每天都在丢数据）

- [x] Task 1: TushareClient.dailyBasic / moneyflow 增加 limit 参数透传
  - [x] SubTask 1.1: `dailyBasic` 改 3 参 `Integer limit`，非空注入 params（String.valueOf 风格与既有分页方法一致）
  - [x] SubTask 1.2: `moneyflow` 同理
  - [x] SubTask 1.3: 调用方已更新（BasicDataServiceImpl/MoneyflowServiceImpl）
- [x] Task 2: BasicDataServiceImpl 传 limit=10000 修复 daily_basic 截断
  - [x] SubTask 2.1: `FULL_MARKET_LIMIT=10000` 常量 + 调用透传
- [x] Task 3: MoneyFlowServiceImpl 传 limit=10000 修复 moneyflow 截断
  - [x] SubTask 3.1: `MONEYFLOW_FETCH_LIMIT=10000` 常量 + 调用透传
- [x] Task 4: P0 数据量验证（SQL）— ⚠️ 需运行时验证（需 Tushare token + DB）
  - [ ] SubTask 4.1: 拉取一个交易日，`SELECT COUNT(*) FROM daily_basic WHERE trade_date=?` ≈ 5300（运行时）
  - [ ] SubTask 4.2: `SELECT COUNT(*) FROM moneyflow WHERE trade_date=?` ≈ 5050（运行时）

## P1 全量重建分页（解决全量重建截断）

- [x] Task 5: stock_basic 全量分页 + 流式落库
  - [x] SubTask 5.1: `BATCH_SIZE=5000`、`MAX_PAGES=10`
  - [x] SubTask 5.2: 3 状态（L/D/P）offset/limit 分页循环
  - [x] SubTask 5.3: 每页流式 `TransactionTemplate.execute` 落库（save 路径无 allStocks 累积；返回值累积以保控制器计数语义）
  - [x] SubTask 5.4: MAX_PAGES 告警
- [x] Task 6: trade_cal 全量分页
  - [x] SubTask 6.1: `BATCH_SIZE=5000`、`MAX_PAGES=10`（DB 分片常量改名 `DB_BATCH_SIZE` 腾出名字）
  - [x] SubTask 6.2: `tradeCal(param, offset, limit)` 重载分页循环
  - [x] SubTask 6.3: MAX_PAGES 告警
- [x] Task 7: index_daily 全量分页
  - [x] SubTask 7.1: `BATCH_SIZE=5000`、`MAX_PAGES=10`
  - [x] SubTask 7.2: `fetchIndexDaily(..., offset, limit)` 重载分页循环
  - [x] SubTask 7.3: MAX_PAGES 告警
- [x] Task 8: P1 数据量验证（SQL）— ⚠️ 需运行时验证
  - [ ] SubTask 8.1: stock_basic L≈5533（运行时）
  - [ ] SubTask 8.2: trade_cal SSE+SZSE ≈11300（运行时）
  - [ ] SubTask 8.3: index_daily 上证指数 ≈7527（运行时）

## P2 事务与并发优化

- [x] Task 9: P2-1 B 类表拆事务（13 个 Service，参照 ForecastServiceImpl A 类范式）
  - [x] SubTask 9.1: IncomeServiceImpl — A 类 + 并发
  - [x] SubTask 9.2: BalancesheetServiceImpl — A 类 + 并发
  - [x] SubTask 9.3: CashflowServiceImpl — A 类 + 并发
  - [x] SubTask 9.4: FinaIndicatorServiceImpl — A 类 + 并发
  - [x] SubTask 9.5: MoneyFlowServiceImpl — A 类 + limit（与 Task 3 合并）
  - [x] SubTask 9.6: DividendServiceImpl — A 类 + ann_date 批量方法
  - [x] SubTask 9.7: IndexDailyFetchService — A 类 + 分页（与 Task 7 合并）
  - [x] SubTask 9.8: HkHoldServiceImpl — A 类 + limit
  - [x] SubTask 9.9: MarginServiceImpl — A 类（margin + margin_detail 双表）
  - [x] SubTask 9.10: MarginDetailServiceImpl — 实际拉取在 MarginServiceImpl，补 limit=10000 修复编译
  - [x] SubTask 9.11: BlockTradeServiceImpl — A 类
  - [x] SubTask 9.12: TopListServiceImpl — A 类（top_list + top_inst 双表）
  - [x] SubTask 9.13: TopInstServiceImpl — 实为纯查询 DataCheckable，top_inst 拉取在 TopListServiceImpl 已 A 类化
- [x] Task 10: P2-2 TradeCalServiceImpl 拆分 computeAndSaveRebalanceFlags
  - [x] SubTask 10.1: 移除方法级 @Transactional
  - [x] SubTask 10.2: HTTP 在事务外
  - [x] SubTask 10.3: saveCalendars 短事务
  - [x] SubTask 10.4: computeAndSaveRebalanceFlags 独立事务
- [x] Task 11: P2-3 C 类无事务表补齐事务
  - [x] SubTask 11.1: StockBasicServiceImpl.saveStocks - TransactionTemplate（与 Task 5 合并）
  - [x] SubTask 11.2: BasicDataServiceImpl.saveDailyBasic - TransactionTemplate（与 Task 2 合并）
  - [x] SubTask 11.3: FinaIndicatorServiceImpl.saveBatch - TransactionTemplate（由 P2-1 B->A 重构覆盖）
  - [x] SubTask 11.4: BasicDataServiceImpl.saveFinaIndicator - TransactionTemplate（漏检项修复：BasicDataTask 每周日 17:00 定时调用此路径，原为 C 类无事务裸 delete+insert，已补齐）
- [x] Task 12: P2-4 财务四表改并发（依赖 Task 9 完成）
  - [x] SubTask 12.1: IncomeServiceImpl.fetchAndSaveAllByRange — IO_EXECUTOR + Semaphore(30)
  - [x] SubTask 12.2: BalancesheetServiceImpl — 同上
  - [x] SubTask 12.3: CashflowServiceImpl — 同上
  - [x] SubTask 12.4: FinaIndicatorServiceImpl — 同上
- [x] Task 13: P2 事务与并发验证 — ⚠️ 部分需运行时验证
  - [x] SubTask 13.1: 事务不含 HTTP（代码级：HTTP 在 transactionTemplate.execute 块之外，已验证）
  - [ ] SubTask 13.2: 原子性故障注入（运行时）
  - [ ] SubTask 13.3: 并发性能 < 5 分钟（运行时）
  - [ ] SubTask 13.4: Tushare 调用频率 < 200 次/分钟（运行时）

## P3 可选优化

- [x] Task 14: P3-1 dividend/forecast/express 增量改 ann_date 全市场批量（保守：新增方法保留 per-stock dispatch）
  - [x] SubTask 14.1: DividendServiceImpl 新增 `fetchAndSaveByAnnDateRange`
  - [x] SubTask 14.2: ForecastServiceImpl 新增 `fetchAndSaveByAnnDateRange`（保留 A 类）
  - [x] SubTask 14.3: ExpressServiceImpl 新增 `fetchAndSaveByAnnDateRange`（保留 A 类）
- [x] Task 15: P3-2 hk_hold / margin_detail 加 limit 兜底
  - [x] SubTask 15.1: TushareClient.hkHold / marginDetail 加 `Integer limit`
  - [x] SubTask 15.2: HkHoldServiceImpl / MarginServiceImpl 调用传 limit=10000
- [x] Task 16: P3-3 已分页表加 MAX_PAGES 防护
  - [x] SubTask 16.1: DailyQuoteServiceImpl.fetchAndSavePagesStreaming + fetchAllPages 加 MAX_PAGES
  - [x] SubTask 16.2: StockNamechangeServiceImpl 迁移到 queryWithPaging（内置 MAX_PAGES）
  - [x] SubTask 16.3: StockStkLimitServiceImpl.fetchAndSaveAll 加 MAX_PAGES
  - [x] SubTask 16.4: 触达上限打 warn（含 total）
- [x] Task 17: P3 分页防护验证 — ⚠️ 触达场景需运行时验证
  - [ ] SubTask 17.1: 调小 MAX_PAGES 构造触达场景（运行时）

## P4 长期设计优化

- [x] Task 18: P4-1 TushareClient 统一分页能力 queryWithPaging
  - [x] SubTask 18.1: `queryWithPaging(api, params, clazz, batchSize, handler)` 已加
  - [x] SubTask 18.2: offset+=batchSize 循环 + 回调 handler
  - [x] SubTask 18.3: LIMIT_CEILING=100000 校验 + MAX_PAGES=100 防护 + 告警
  - [x] SubTask 18.4: StockNamechangeServiceImpl 迁移到 queryWithPaging 作为试点
- [x] Task 19: P4-2 TushareApiEnum.fields 与 DTO @JSONField 专项对齐核查
  - [x] SubTask 19.1: 26 个枚举逐字段比对，全部对齐无需修
  - [x] SubTask 19.2: 唯一缺口 IndexDailyDO（model/）4 字段补 @JSONField
  - [x] SubTask 19.3: 修正后无字段静默丢失
- [x] Task 20: P4-3 全量重建回滚机制
  - [x] SubTask 20.1: 评估后采用 rename+recreate+pull+swap（SQLite/MySQL 均兼容）
  - [x] SubTask 20.2: doFullRebuild 非 D 类走回滚策略；D 类保留不 truncate
  - [ ] SubTask 20.3: 验证中途失败时原表数据保留（运行时）

# Task Dependencies
- Task 4 依赖 Task 1/2/3 ✓
- Task 8 依赖 Task 5/6/7 ✓
- Task 12（P2-4 并发）依赖 Task 9（P2-1 事务收窄）✓（同 agent 顺序完成）
- Task 11 与 Task 5/2 合并实现 ✓
- Task 9.5 与 Task 3 合并 ✓；Task 9.7 与 Task 7 合并 ✓；Task 10 与 Task 6 合并 ✓
- Task 16/17/18/19/20 互相无依赖 ✓

# 遗留/超出本次范围（非 FAIL，建议后续处理）
1. ~~BasicDataServiceImpl.saveFinaIndicator 缺 TransactionTemplate~~ ✅ 已修复（2026-07-27）：原判断"遗留未被调用"有误，实际由 BasicDataTask 每周日 17:00 定时调用；已补齐 TransactionTemplate 包裹 delete+insert，与 saveDailyBasic 一致。
2. Forecast/Express 的 fetchAndSaveAllByRange 仍为串行（P2-4 仅要求财务四表；P3-1 仅要求 ann_date 批量），与同族风格不一致，可酌情统一为虚拟线程并发。
3. StockStkLimitServiceImpl.fetchAndSaveIncremental 与 StockNamechangeServiceImpl.fetchAndSaveIncremental 仍保留方法级 @Transactional（HTTP 在事务内）- 这两个表不在 P2-1 的 13 个 B 类清单内（PRD §4 矩阵将其归为 A 类主路径，增量方法为次要路径），可后续统一。
