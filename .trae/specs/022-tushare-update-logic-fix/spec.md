# Tushare 数据更新逻辑修复 Spec

## Why

`数据管控中心-增量与全量更新逻辑核查报告`（PRD 019）指出了 stock-watcher 模块 25 张业务表在增量/全量更新逻辑上存在多种问题。经代码验证，报告整体准确率约 85%，但**遗漏了最严重的 bug**：8 张日频快照表的全量重建会先 truncate 再仅加载当天数据，导致历史数据全部丢失。

此外，报告中"一刀切"地将"无真增量"视为问题，但实际上不同表的数据特征不同，合理的更新策略也不同。本 spec 按表的**数据轮动特征**将 25 张表分为 6 类，为每类定义合适的增量与全量策略。

### 核查报告准确性评估

| 报告结论 | 准确性 | 说明 |
|----------|--------|------|
| 仅 daily_quote/adj_factor 有真增量 | ✅ 准确 | 代码验证确认，preloadLastDateMap 机制正确 |
| index_daily 增量仅拉当天 | ✅ 准确 | DataInitServiceImpl:315 `start = isFull ? fullStart : today` |
| 8 张日频表增量=全量=当天 | ✅ 准确 | 增量确实只传 today，无日期范围补全 |
| 财务表用固定 1 年窗口 | ✅ 准确 | `LocalDate.now().minusYears(1)`，不查 MAX(ann_date) |
| trade_cal/index_weight 增量=全量 | ✅ 准确 | 始终 30 年/5 年 |
| deleteBatchByKeys SQLite 不兼容 | ✅ 准确 | 8 个 Mapper XML 使用 row-value IN 语法，SQLite 确实不支持 |
| 事务保护不一致 | ✅ 准确 | 仅 6 个服务有 @Transactional，daily_quote/adj_factor 缺失 |
| 全量重建无回滚机制 | ✅ 准确 | truncate + 拉取无事务保护 |
| **全量重建仅当天 = 数据丢失** | ❌ **遗漏** | 报告说"无真正全量"，但未指出这是**数据丢失 bug**：truncate 后只加载当天 = 历史数据全毁 |
| 分组表数量 | ❌ 有误 | INDEX 组写 4 张实际列了 6 张，MARKET 组写 6 张实际列了 5 张 |

## What Changes

### 按数据特征分类的更新策略

#### A 类：参考数据（全量合理，保持不变）
- **表**：stock_basic, sw_industry
- **增量**：全量拉取（数据量小、无按日轮动）
- **全量**：全量拉取 + replace
- **理由**：用户明确指出"有些表没有日期字段，数据量不大，增量也用全量是合理的"

#### B 类：逐股票时间序列（已有真增量，微调起始日期）
- **表**：daily_quote, adj_factor
- **增量**：保持 preloadLastDateMap 机制；**将起始日期从 `MAX(trade_date)+1` 改为 `MAX(trade_date)`**（含当天，防止数据缺失）
- **全量**：从 30 年前拉到今天（保持不变）
- **理由**：用户要求"最后日期是 20260101 当天也要补，防止数据缺失"。delete+insert 幂等，重拉当天安全

#### C 类：逐股票/逐指数时间序列（需实现增量）
- **表**：stk_limit, dividend, index_daily, namechange, suspend_d
- **增量**：查询每只股票/每个指数的 `MAX(日期字段)`，从该日期（含）拉到今天
- **全量**：从 30 年前拉到今天
- **注意**：namechange 和 suspend_d 已有 `fetchAndSaveIncremental(tradeDate)` 方法但未被正确调度，需修复调度逻辑

#### D 类：全市场日频快照（需实现日期补全 + 修复全量数据丢失）
- **表**：daily_basic, moneyflow, hk_hold, margin, margin_detail, top_list, top_inst, block_trade
- **增量**：查询 `MAX(trade_date)`，遍历 trade_cal 中从 `MAX(trade_date)` 到今天的所有交易日，逐日拉取
- **全量**：**不 truncate**，从可配置回溯期（默认 3 年）拉到今天，逐日拉取，delete+insert 幂等覆盖
- **理由**：Tushare API 仅支持单日查询；当前全量先 truncate 再只拉当天 = 历史数据全毁

#### E 类：财务报表（需优化增量窗口）
- **表**：income, balancesheet, cashflow, forecast, express, fina_indicator
- **增量**：查询每只股票的 `MAX(ann_date)`，从该日期（含）拉到今天
- **全量**：从 30 年前拉到今天（保持不变）
- **理由**：当前固定 1 年窗口重复拉取已有数据，浪费 API 额度

#### F 类：日历/权重（低频，可优化）
- **表**：trade_cal, index_weight
- **增量**：查询 `MAX(日期字段)`，从该日期（含）拉到今天
- **全量**：全量拉取（保持不变）
- **理由**：trade_cal 数据量小可不做（低优先级）；index_weight 按指数查 MAX(trade_date)

### 横切修复

1. **修复 deleteBatchByKeys 的 SQLite 兼容性**：将 row-value IN 语法改为 OR 连接条件（ExpressMapper 已是正确范式）
2. **统一增量起始日期原则**：所有有日期字段的表，增量起始 = `MAX(日期字段)`（含当天），非 `+1`
3. **事务安全**：为核心表（daily_quote, adj_factor, income 等）的 saveBatch 添加 @Transactional

### 不在本次范围

- 全量重建的"临时表 + 原子交换"零停机策略（Phase 3）
- 邮件告警、智能修复
- API 限流自适应调度

## Impact

- Affected specs: 021-data-governance-center（数据管控中心 UI 依赖增量/全量能力可用）
- Affected code:
  - `DataInitServiceImpl.java` - 核心分发逻辑，每张表的增量/全量 start 日期计算
  - `DailyQuoteServiceImpl.java` / `AdjFactorServiceImpl.java` - 起始日期从 +1 改为含当天
  - `DailyBasicServiceImpl.java`(BasicDataServiceImpl) / `MoneyflowServiceImpl.java` / `HkHoldServiceImpl.java` / `MarginServiceImpl.java` / `TopListServiceImpl.java` / `BlockTradeServiceImpl.java` - 新增日期范围补全方法
  - `IndexDailyFetchService.java` - 修复增量 bug
  - `StockStkLimitServiceImpl.java` / `DividendServiceImpl.java` - 新增按股增量方法
  - `StockNamechangeServiceImpl.java` / `StockSuspendDServiceImpl.java` - 修复增量调度
  - `IncomeServiceImpl.java` / `BalancesheetServiceImpl.java` / `CashflowServiceImpl.java` / `ForecastServiceImpl.java` / `ExpressServiceImpl.java` / `FinaIndicatorServiceImpl.java` - 增量改为 MAX(ann_date)
  - `TradeCalServiceImpl.java` / `IndexWeightServiceImpl.java` - 增量优化
  - 8 个 Mapper XML 文件 - deleteBatchByKeys 改 OR 连接
  - 各 Mapper 接口 - 新增 selectMaxDate / selectMaxDatePerStock 方法

## ADDED Requirements

### Requirement: 表分类与更新策略定义

系统 SHALL 按 25 张表的数据轮动特征分为 6 类（A-F），每类有明确的增量与全量更新策略，而非一刀切。

#### Scenario: A 类参考数据表
- **WHEN** 对 stock_basic 或 sw_industry 执行增量更新
- **THEN** 全量拉取所有数据（不分增量/全量），使用 delete+replace 写入
- **AND** 不需要查 MAX(日期)，因为数据量小且无按日轮动

#### Scenario: B 类逐股票时间序列表
- **WHEN** 对 daily_quote 或 adj_factor 执行增量更新
- **THEN** 预加载所有股票的 MAX(trade_date)
- **AND** 对每只股票，从 `MAX(trade_date)`（含当天）拉到 today
- **AND** delete+insert 幂等覆盖，确保当天数据完整

#### Scenario: C 类逐股票/逐指数时间序列表
- **WHEN** 对 stk_limit / dividend / index_daily / namechange / suspend_d 执行增量更新
- **THEN** 查询每只股票（或每个指数）的 MAX(日期字段)
- **AND** 从 MAX(日期)（含当天）拉到 today
- **AND** 首次无数据时从 30 年前开始

#### Scenario: D 类全市场日频快照表
- **WHEN** 对 daily_basic / moneyflow / hk_hold / margin / margin_detail / top_list / top_inst / block_trade 执行增量更新
- **THEN** 查询该表的 MAX(trade_date)
- **AND** 从 trade_cal 获取 MAX(trade_date) 到 today 之间的所有交易日列表
- **AND** 逐日调用 Tushare API 拉取全市场数据
- **AND** 含 MAX(trade_date) 当天，防止数据缺失

#### Scenario: D 类全量重建不丢数据
- **WHEN** 对 D 类表执行全量重建
- **THEN** **不执行 truncate**
- **AND** 从可配置回溯期（默认 3 年）拉到 today，逐日拉取
- **AND** 使用 delete+insert 幂等覆盖
- **AND** 即使中途失败，已有数据不受影响

#### Scenario: E 类财务报表增量
- **WHEN** 对 income / balancesheet / cashflow / forecast / express / fina_indicator 执行增量更新
- **THEN** 查询每只股票的 MAX(ann_date)
- **AND** 从 MAX(ann_date)（含当天）拉到 today
- **AND** 不再使用固定 1 年窗口

#### Scenario: F 类日历/权重增量
- **WHEN** 对 trade_cal 执行增量更新
- **THEN** 查询 MAX(cal_date)，从该日期（含）拉到 today
- **WHEN** 对 index_weight 执行增量更新
- **THEN** 查询每个指数的 MAX(trade_date)，从该日期（含）拉到 today

### Requirement: 增量起始日期统一原则

系统 SHALL 对所有有日期字段的表，增量更新的起始日期为 `MAX(日期字段)` 本身（非 +1 天）。

#### Scenario: 含当天补全
- **GIVEN** 某股票在 DB 中最新记录日期为 20260101
- **WHEN** 执行增量更新
- **THEN** 拉取区间为 [20260101, today]
- **AND** 20260101 当天数据被重新拉取并覆盖（幂等）
- **AND** 防止因上次拉取不完整导致的数据缺失

### Requirement: D 类表日期补全方法

系统 SHALL 为 8 张 D 类日频快照表提供统一的日期补全能力。

#### Scenario: 增量补全流程
- **WHEN** 对 D 类表执行增量更新
- **THEN** 查询 `SELECT MAX(trade_date) FROM <表名>`
- **AND** 若结果为 null（空表），回退到可配置回溯期（默认 3 年前）
- **AND** 从 trade_cal 查询 [start, today] 的交易日列表
- **AND** 遍历每个交易日，调用 `fetchAndSave(tradeDate)`
- **AND** 每次调用间添加限流等待（默认 300ms，可配置）

#### Scenario: 全量重建流程
- **WHEN** 对 D 类表执行全量重建
- **THEN** **不执行 truncateTable**
- **AND** 从可配置回溯期（默认 3 年前）开始
- **AND** 遍历所有交易日逐日拉取
- **AND** 使用 delete+insert 幂等覆盖

### Requirement: SQLite 兼容的批量删除

系统 SHALL 将所有 Mapper XML 中的 `deleteBatchByKeys` 从 row-value IN 语法改为 OR 连接条件，确保 SQLite 兼容。

#### Scenario: 修复前（不兼容）
```sql
-- SQLite 不支持此语法
DELETE FROM daily_quote WHERE (ts_code, trade_date) IN ((...), (...))
```

#### Scenario: 修复后（兼容）
```sql
-- SQLite/MySQL 均支持
DELETE FROM daily_quote WHERE (ts_code='A' AND trade_date='1') OR (ts_code='B' AND trade_date='2')
```

#### Scenario: ExpressMapper 已是正确范式
- **GIVEN** ExpressMapper.xml 已使用 OR 连接语法
- **THEN** 以此为模板修复其余 8 个 Mapper XML

### Requirement: C 类表增量实现

系统 SHALL 为 stk_limit、dividend、index_daily 三张表新增按股票/指数的增量能力。

#### Scenario: stk_limit 增量
- **WHEN** 对 stk_limit 执行增量更新
- **THEN** 按股票遍历，查询每只股票的 MAX(trade_date)
- **AND** 调用 `fetchAndSaveIncremental(tsCode, startDate, endDate)`
- **AND** 已有 `fetchAndSaveIncremental(tradeDate)` 方法需扩展为支持日期范围

#### Scenario: dividend 增量
- **WHEN** 对 dividend 执行增量更新
- **THEN** 按股票遍历，查询每只股票的 MAX(ann_date)
- **AND** 从 MAX(ann_date)（含）拉到 today

#### Scenario: index_daily 增量修复
- **WHEN** 对 index_daily 执行增量更新
- **THEN** 按指数遍历，查询每个指数的 MAX(trade_date)
- **AND** 从 MAX(trade_date)（含）拉到 today
- **AND** 不再使用 `start = today` 的错误逻辑

### Requirement: C 类表 namechange/suspend_d 增量调度修复

系统 SHALL 修复 namechange 和 suspend_d 的增量调度逻辑。

#### Scenario: namechange 增量
- **WHEN** 对 namechange 执行增量更新
- **THEN** 查询 MAX(start_date)
- **AND** 遍历 [MAX(start_date), today] 的交易日
- **AND** 对每个交易日调用已有的 `fetchAndSaveIncremental(tradeDate)`

#### Scenario: suspend_d 增量
- **WHEN** 对 suspend_d 执行增量更新
- **THEN** 查询 MAX(trade_date)
- **AND** 遍历 [MAX(trade_date), today] 的交易日
- **AND** 对每个交易日调用已有的 `fetchAndSaveIncremental(tradeDate)`

### Requirement: 事务安全

系统 SHALL 为缺乏事务保护的核心表 saveBatch 方法添加 @Transactional。

#### Scenario: 核心表事务
- **WHEN** daily_quote / adj_factor / income / balancesheet / cashflow / fina_indicator / stk_limit / dividend / namechange / suspend_d / index_daily 执行 saveBatch
- **THEN** delete+insert 在同一事务内
- **AND** 失败时回滚，不出现"已删未插"的中间状态

## MODIFIED Requirements

### Requirement: DataInitServiceImpl 增量/全量分发逻辑

现有 `executeSingleStep` 方法中对 `isFull` 的使用存在 3 类问题：
1. D 类表：`isFull` 完全被忽略，增量/全量都只拉当天；全量时先 truncate 再只拉当天 = 数据丢失
2. index_daily：增量时 `start = today` 而非 `MAX(trade_date)`
3. 财务表：增量用固定 1 年窗口而非 `MAX(ann_date)`

修改后：
- A 类（stock_basic, sw_industry）：`isFull` 无影响，始终全量
- B 类（daily_quote, adj_factor）：`isFull=false` 时从 `MAX(trade_date)`（含）开始；`isFull=true` 时从 30 年前
- C 类（stk_limit, dividend, index_daily, namechange, suspend_d）：`isFull=false` 时按股票/指数查 `MAX(日期)`；`isFull=true` 时从 30 年前
- D 类（8 张日频表）：`isFull=false` 时从 `MAX(trade_date)` 逐日补全；`isFull=true` 时不 truncate，从回溯期逐日补全
- E 类（6 张财务表）：`isFull=false` 时按股票查 `MAX(ann_date)`；`isFull=true` 时从 30 年前
- F 类（trade_cal, index_weight）：`isFull=false` 时从 `MAX(日期)` 开始；`isFull=true` 时全量

### Requirement: 全量重建策略调整

现有全量重建统一使用 `rebuildTable(step)` 先 truncate 再拉取。修改后：
- A 类：保持 truncate + 全量拉取（数据小，可接受）
- B 类：保持 truncate + 30 年拉取
- C 类：保持 truncate + 30 年拉取
- **D 类：不 truncate**，改为从回溯期逐日拉取（防止数据丢失）
- E 类：保持 truncate + 30 年拉取
- F 类：保持 truncate + 全量拉取

### Requirement: DailyQuoteServiceImpl / AdjFactorServiceImpl 起始日期调整

现有增量逻辑：`startDate = lastDate + 1 天`（跳过最新日期）
修改为：`startDate = lastDate`（含最新日期，幂等覆盖）

## REMOVED Requirements

### Requirement: D 类表 truncate 后全量拉取
**Reason**: truncate 后仅加载当天数据导致历史数据全部丢失
**Migration**: 改为不 truncate + 从回溯期逐日拉取，delete+insert 幂等覆盖

### Requirement: 财务表固定 1 年增量窗口
**Reason**: 重复拉取已有数据，浪费 API 额度
**Migration**: 改为按股票 MAX(ann_date) 精准增量
