# 数据管理中心接口优化 Spec

> **来源 PRD**：`sdlc/prd/020-数据管理中心接口优化/PRD-数据管理中心接口优化.md`（v2.0 实证调整版）
> **范围**：stock-watcher 模块数据中台——Tushare 接口分页截断修复、事务范围收窄、财务表并发拉取、分页安全防护、统一分页能力与字段对齐。
> **与已有 spec 的关系**：本 spec 与 `022-tushare-update-logic-fix`（增量/全量日期逻辑）互补——022 解决"起始日期/不丢历史数据"，本 spec 解决"截断/事务范围/并发/分页防护"。两者在事务原子性上有交集：本 spec 的 P2-3（C 类补事务）同时满足 022 对 daily_basic/stock_basic 的原子性要求，且统一采用 `TransactionTemplate` 包裹 saveBatch 的 A 类范式（非方法级 `@Transactional`），避免与 022 的原子性指引冲突。

## Why

stock-watcher 数据中台维护 25 张业务表，数据全部来自 Tushare API。经 26 个接口实测算证，存在四类核心问题：① **静默截断**——Tushare 默认返回 5000 行，daily_basic/moneyflow 单日全市场已超 5000，**每天都在丢数据**；② **事务范围过大**——13 个 Service 用 `@Transactional` 包裹整个方法（含 HTTP 调用），DB 连接被网络持有；③ **查询效率低**——财务四表 ts_code 必传只能逐股票，当前串行循环 5000+ 次，增量拉取约 40 分钟；④ **设计不统一**——分页能力分散、缺乏统一封装与安全防护，部分分页循环无 `MAX_PAGES` 上限有死循环风险。

## What Changes

### P0 紧急修复：单日全市场截断
- `TushareClient.dailyBasic` / `moneyflow` 方法增加 `limit` 参数透传
- `BasicDataServiceImpl` / `MoneyFlowServiceImpl` 调用时显式传 `limit=10000`

### P1 全量重建分页
- `StockBasicServiceImpl`：加分页循环（limit=5000）+ 流式落库（查一页存一页，消除内存累积）
- `TradeCalServiceImpl`：加分页循环（limit=5000，最多 3 页）
- `IndexDailyFetchService`：加分页循环（limit=5000，最多 2 页）

### P2 事务与并发优化
- **P2-1**：13 个 B 类 Service（income/balancesheet/cashflow/fina_indicator/moneyflow/dividend/index_daily/hk_hold/margin/margin_detail/block_trade/top_list/top_inst）移除方法级 `@Transactional`，参照 `ForecastServiceImpl` A 类范式——HTTP 在事务外，仅 `TransactionTemplate` 包裹 saveBatch
- **P2-2**：`TradeCalServiceImpl.fetchAndSaveTradeCal` 拆分——HTTP 在事务外，saveCalendars 短事务，`computeAndSaveRebalanceFlags` 独立事务
- **P2-3**：C 类无事务表（stock_basic/daily_basic/fina_indicator）saveBatch 内补 `TransactionTemplate`
- **P2-4**：财务四表（income/balancesheet/cashflow/fina_indicator）`fetchAndSaveAllByRange` 改虚拟线程并发，复用 `DataInitServiceImpl.IO_EXECUTOR` 模式，信号量控并发度

### P3 可选优化
- **P3-1**：dividend/forecast/express 增量改 ann_date 全市场批量（可选，收益有限）
- **P3-2**：hk_hold / margin_detail 显式传 `limit=10000` 兜底
- **P3-3**：daily_quote / namechange / stk_limit 分页循环加 `MAX_PAGES` 上限防护（参照 `AdjFactorServiceImpl.MAX_PAGES=100`）

### P4 长期设计优化
- **P4-1**：`TushareClient` 新增统一回调式分页 `queryWithPaging`，各 Service 逐步迁移
- **P4-2**：`TushareApiEnum.fields` 与 DTO `@JSONField` 专项对齐核查，修正不一致字段
- **P4-3**：`DataInitServiceImpl.doFullRebuild` 回滚机制（临时表 + RENAME 或先插后删）；D 类日频快照表已不 truncate（spec 022 已落地），其余类仍需处理

## Impact

- **Affected specs**：
  - `022-tushare-update-logic-fix`——本 spec P2-3 采用 `TransactionTemplate` 范式实现 022 所要求的 daily_basic/stock_basic 原子性，二者不冲突
  - `021-data-governance-center`——数据管控中心 UI 的增量/全量操作依赖底层拉取能力正确，本 spec 修复后管控中心操作才可靠
- **Affected code**（stock-watcher 模块）：
  - `client/TushareClient.java`——query() 不变；dailyBasic/moneyflow 增 limit 参数；P4-1 新增 queryWithPaging
  - `constant/TushareApiEnum.java`——P4-2 字段对齐核查
  - `service/impl/BasicDataServiceImpl.java`、`MoneyFlowServiceImpl.java`——P0 + P2-3
  - `service/impl/StockBasicServiceImpl.java`——P1 分页 + P2-3 事务
  - `service/impl/TradeCalServiceImpl.java`——P1 分页 + P2-2 事务拆分
  - `service/IndexDailyFetchService.java`——P1 分页 + P2-1 事务
  - 13 个 B 类 ServiceImpl——P2-1 事务收窄
  - 4 个财务 ServiceImpl（Income/Balancesheet/Cashflow/FinaIndicator）——P2-4 并发
  - `service/impl/DailyQuoteServiceImpl.java`、`StockNamechangeServiceImpl.java`、`StockStkLimitServiceImpl.java`——P3-3 MAX_PAGES
  - `service/impl/HkHoldServiceImpl.java`、`MarginDetailServiceImpl.java`——P3-2 limit 兜底
  - dividend/forecast/express 三个 ServiceImpl——P3-1（可选）
  - `service/impl/DataInitServiceImpl.java`——P4-3 回滚机制

## ADDED Requirements

### Requirement: P0 单日全市场截断修复

系统 SHALL 在调用 daily_basic 与 moneyflow 接口时显式传递 `limit` 参数以突破 Tushare 默认 5000 行截断。

`TushareClient.dailyBasic(tradeDate, tsCode)` 与 `moneyflow(tradeDate, tsCode)` 方法 SHALL 增加 `limit` 参数（可空），非空时注入 params。`BasicDataServiceImpl` 与 `MoneyFlowServiceImpl` 调用时 SHALL 传 `limit=10000`（单日全市场约 5300/5050 条，10000 留足余量且远低于 Tushare limit 上限 10w）。

#### Scenario: daily_basic 单日全市场不再截断
- **GIVEN** 某交易日全市场约 5300 只在市股票
- **WHEN** `BasicDataServiceImpl.fetchAndSaveDailyBasic(tradeDate)` 执行
- **THEN** TushareClient 调用携带 `limit=10000`
- **AND** 返回行数 ≈ 5300，无截断
- **AND** 落库后 `SELECT COUNT(*) FROM daily_basic WHERE trade_date=?` ≈ 当日在市股票数

#### Scenario: moneyflow 单日全市场不再截断
- **GIVEN** 某交易日全市场约 5050 只股票
- **WHEN** `MoneyFlowServiceImpl.fetchAndSave(tradeDate)` 执行
- **THEN** TushareClient 调用携带 `limit=10000`
- **AND** 返回行数 ≈ 5050，无截断

#### Scenario: limit 为空时保持原行为
- **WHEN** 调用方传 `limit=null`
- **THEN** 不向 params 注入 limit 字段，保持 Tushare 默认行为（向后兼容逐股票等小数据量场景）

### Requirement: P1 全量重建分页拉取

系统 SHALL 对单次查询数据量在 5000~10w 区间的表提供 offset/limit 分页循环，并强制 `MAX_PAGES` 上限防死循环。

#### Scenario: stock_basic 全量分页 + 流式落库
- **GIVEN** list_status=L 全量约 5533 条，超 5000 截断线
- **WHEN** `StockBasicServiceImpl.fetchAndSaveStockBasic()` 执行
- **THEN** 对每个上市状态（L/D/P）按 `limit=5000` 分页循环拉取
- **AND** 每页拉取后立即落库（流式，不累积到内存 allStocks）
- **AND** 循环含 `MAX_PAGES` 上限（默认 10），触达上限打 warn 日志不静默
- **AND** 落库后 `SELECT COUNT(*) FROM stock_basic WHERE list_status='L'` ≈ 5533

#### Scenario: trade_cal 全量分页
- **GIVEN** 单交易所 30 年约 11323 条
- **WHEN** `TradeCalServiceImpl.fetchAndSaveTradeCal(exchange, startDate, endDate)` 执行
- **THEN** 按 `limit=5000` 分页循环，offset 累加直到返回 < 5000
- **AND** 含 `MAX_PAGES` 上限，触达打 warn
- **AND** 落库后 SSE+SZSE 合计 ≈ 11300 条

#### Scenario: index_daily 全量分页
- **GIVEN** 单指数 30 年约 5822~7527 条
- **WHEN** `IndexDailyFetchService.fetchAndSaveIndexDaily(tsCode, startDate, endDate)` 执行
- **THEN** 按 `limit=5000` 分页循环
- **AND** 含 `MAX_PAGES` 上限
- **AND** 落库后上证指数 ≈ 7527 条

#### Scenario: 分页终止条件正确
- **GIVEN** 某查询总数据量正好为 BATCH_SIZE 整数倍
- **WHEN** 最后一页返回满页（==BATCH_SIZE）后下一页返回空
- **THEN** 循环在空页时正确终止，不死循环

### Requirement: P2-1 B 类事务收窄（TransactionTemplate 范式）

系统 SHALL 将 13 个 B 类 Service 的方法级 `@Transactional`（包裹 HTTP 调用）重构为 A 类范式：HTTP 调用在事务外，仅 `saveBatch` 内部用 `TransactionTemplate.execute` 包裹 delete+insert。

涉及 13 个 Service：income / balancesheet / cashflow / fina_indicator / moneyflow / dividend / index_daily / hk_hold / margin / margin_detail / block_trade / top_list / top_inst。

#### Scenario: HTTP 在事务外
- **WHEN** 任一 B 类 Service 的 fetchAndSave 方法执行
- **THEN** Tushare HTTP 调用不处于任何活动数据库事务中
- **AND** DB 连接在 HTTP 期间不被持有

#### Scenario: saveBatch 原子性
- **WHEN** saveBatch 内 delete 成功但 insert 失败
- **THEN** TransactionTemplate 回滚 delete，数据不丢失
- **AND** 不出现"已删未插"的中间状态

#### Scenario: DB 连接持有时间短
- **WHEN** 监控 saveBatch 期间 DB 连接 active 时长
- **THEN** 持有时间 < 1s（事务仅含 DB 写入，不含网络）

### Requirement: P2-2 TradeCal 事务拆分

系统 SHALL 将 `TradeCalServiceImpl.fetchAndSaveTradeCal` 拆分为三段独立事务：HTTP 拉取（事务外）→ saveCalendars（短事务）→ `computeAndSaveRebalanceFlags`（独立事务）。

#### Scenario: computeAndSaveRebalanceFlags 独立事务
- **WHEN** `fetchAndSaveTradeCal` 执行
- **THEN** `computeAndSaveRebalanceFlags` 在独立 TransactionTemplate 中执行
- **AND** 不与 saveCalendars 共享事务，也不与 HTTP 调用共享事务
- **AND** 任一段失败不影响已提交的前序段（saveCalendars 已落库则保留）

### Requirement: P2-3 C 类表补齐事务

系统 SHALL 为 stock_basic / daily_basic / fina_indicator 三个 C 类无事务表的 saveBatch 方法补齐 `TransactionTemplate` 包裹 delete+insert。

#### Scenario: C 类表 saveBatch 原子性
- **WHEN** stock_basic / daily_basic / fina_indicator 的 saveBatch 内 delete 成功 insert 失败
- **THEN** 事务回滚，不丢数据

### Requirement: P2-4 财务四表并发拉取

系统 SHALL 将 income / balancesheet / cashflow / fina_indicator 的 `fetchAndSaveAllByRange` 由串行 for 循环改为虚拟线程并发，复用 `DataInitServiceImpl.IO_EXECUTOR`（`Executors.newVirtualThreadPerTaskExecutor()`）模式，并用 `Semaphore` 控制并发度（默认 30，留出 Tushare 200 次/分钟限流余量）。

#### Scenario: 并发拉取加速
- **GIVEN** 财务四表 ts_code 必传，需逐股票拉取约 5000+ 次
- **WHEN** `fetchAndSaveAllByRange(startDate, endDate)` 执行
- **THEN** 各股票拉取任务在虚拟线程上并发执行
- **AND** Semaphore(30) 控制同时进行的 Tushare 调用数 ≤ 30
- **AND** 增量拉取耗时从 ~40 分钟降至 < 5 分钟

#### Scenario: 限流合规
- **WHEN** 并发拉取执行
- **THEN** Tushare 调用频率约每分钟 180 次（≤ 200 限流阈值）
- **AND** 无频繁限流触发

#### Scenario: 数据一致性
- **WHEN** 并发 vs 串行拉取同一区间
- **THEN** 落库数据量一致，无重复无丢失

#### Scenario: 依赖 P2-1 先完成
- **GIVEN** 财务四表仍为 B 类长事务（HTTP 在事务内）
- **THEN** P2-4 并发改造 SHALL 在 P2-1（事务收窄）完成后进行
- **AND** 否则并发下长事务会加剧连接池压力

### Requirement: P3-2 临界表 limit 兜底

系统 SHALL 为 hk_hold / margin_detail 调用显式传 `limit=10000`，提前规避随股票数增长超 5000 的截断风险。

#### Scenario: 临界表预防性兜底
- **WHEN** hk_hold / margin_detail 单日全市场拉取执行
- **THEN** TushareClient 调用携带 `limit=10000`
- **AND** 当前 ~4000 条不截断，未来增长亦有保护

### Requirement: P3-3 分页安全防护（MAX_PAGES）

系统 SHALL 为 daily_quote / namechange / stk_limit 三个已分页但无上限防护的 Service 补齐 `MAX_PAGES` 上限，参照 `AdjFactorServiceImpl.MAX_PAGES_PER_QUERY=100` 与 `StockSuspendDServiceImpl.OFFSET_LIMIT=100000` 模式。

#### Scenario: 触达上限告警不静默
- **GIVEN** 异常场景下 Tushare 持续返回满页数据
- **WHEN** 分页循环页数达到 MAX_PAGES
- **THEN** 打 warn 日志（含已拉取 total 数量）并正常退出
- **AND** 不死循环、不静默截断

### Requirement: P4-1 统一分页能力 queryWithPaging

系统 SHALL 在 `TushareClient` 通用层提供回调式分页方法 `queryWithPaging`，统一封装 offset/limit 循环、MAX_PAGES 防护、流式回调处理。

#### Scenario: 统一分页入口
- **WHEN** 新增数据表需要分页拉取
- **THEN** 调用 `queryWithPaging(api, params, clazz, batchSize, handler)` 即可
- **AND** 内部自动循环 offset+=batchSize，每页回调 handler 处理（如落库）
- **AND** 返回 < batchSize 时终止
- **AND** 强制 limit 上限 100000 校验
- **AND** 含 MAX_PAGES 防护与触达告警

### Requirement: P4-2 字段对齐核查

系统 SHALL 对 21 个 Tushare 接口的 `TushareApiEnum.XXX.fields` 与对应 DTO 的 `@JSONField` 名称逐一比对，修正不一致字段（`parseResponse` 按 fields 数组位置组装，不一致会静默丢字段）。

#### Scenario: 字段对齐核查表产出
- **WHEN** 执行字段对齐核查
- **THEN** 产出 21 个接口的字段对齐核查表
- **AND** 修正所有 enum fields 与 DTO @JSONField 不一致项
- **AND** 修正后无字段静默丢失

### Requirement: P4-3 全量重建回滚机制

系统 SHALL 为 `DataInitServiceImpl.doFullRebuild` 提供回滚能力，避免中途失败导致表为空。

> D 类日频快照表（daily_basic/moneyflow/top_list/top_inst/block_trade/hk_hold/margin/margin_detail）已不 truncate（spec 022 已落地），本要求针对其余仍 truncate 的表。

#### Scenario: 中途失败表不为空
- **WHEN** doFullRebuild 拉取中途失败
- **THEN** 采用"临时表 + RENAME"或"先插后删"策略
- **AND** 原表数据在重建成功前不被清空

## MODIFIED Requirements

### Requirement: TushareClient.dailyBasic / moneyflow 方法签名

现有：
```java
public List<DailyBasicDTO> dailyBasic(String tradeDate, String tsCode)
public List<MoneyflowDTO> moneyflow(String tradeDate, String tsCode)
```

修改为：
```java
public List<DailyBasicDTO> dailyBasic(String tradeDate, String tsCode, Integer limit)
public List<MoneyflowDTO> moneyflow(String tradeDate, String tsCode, Integer limit)
```
`limit` 为空时不注入 params（向后兼容）。

### Requirement: StockBasicServiceImpl.fetchAndSaveStockBasic 落库方式

现有：累积 3 个上市状态数据到 `allStocks` 列表，最后一次性 `saveStocks`。
修改为：每个上市状态分页拉取，每页拉取后立即 `TransactionTemplate.execute` 包裹 saveBatch 落库（流式），消除内存累积。

### Requirement: TradeCalServiceImpl.fetchAndSaveTradeCal 事务结构

现有：`@Transactional` 包裹 HTTP + saveCalendars + computeAndSaveRebalanceFlags。
修改为：移除方法级 `@Transactional`；HTTP 在事务外；saveCalendars 用短事务；computeAndSaveRebalanceFlags 用独立事务。

### Requirement: 13 个 B 类 Service 的 fetchAndSave 事务模式

现有：方法级 `@Transactional(rollbackFor=Exception.class)` 包裹整个方法（含 Tushare HTTP）。
修改为：移除方法级 `@Transactional`；HTTP 在事务外；仅 saveBatch 用 `TransactionTemplate.execute` 包裹。

### Requirement: 财务四表 fetchAndSaveAllByRange 并发模型

现有：串行 `for (StockBasicDTO s : stocks) { total += fetchAndSave(tsCode, ...); }`。
修改为：虚拟线程并发，`IO_EXECUTOR` + `Semaphore(30)` 控流，`CompletableFuture.allOf` 等待全部完成汇总 total。

## REMOVED Requirements

### Requirement: C 类表无事务的裸 delete+insert
**Reason**：delete 成功 insert 失败会丢数据，无原子性保证。
**Migration**：saveBatch 内用 `TransactionTemplate` 包裹 delete+insert（P2-3）。

### Requirement: 分页循环无 MAX_PAGES 上限
**Reason**：极端情况下 Tushare 持续返回满页数据会导致死循环、资源耗尽。
**Migration**：所有分页循环加 `MAX_PAGES` 或 `OFFSET_LIMIT` 上限，触达时打 warn 日志（P3-3）。
