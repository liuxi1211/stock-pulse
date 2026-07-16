# 轮动策略进阶能力收口（P2-9 + 三项遗留）Spec

> **change-id**：013-rotation-advanced-completion
> **来源 PRD**：`sdlc/prd/009-轮动策略进阶能力/轮动策略进阶能力与008收口PRD.md`（§2 P2-9、§3 遗留#1、§4 遗留#2、§5 遗留#3）
> **对齐**：akquant 0.2.47 · 统一策略配置 Schema v1.0 · 010 轮动范式未来数据治理 · 011 轮动策略配置完整性（一期已交付）· 012 P1-6 transform（已交付）
> **状态**：待评审

---

## Why

009 PRD 的 5 项收口里，**P1-6 transform 已在 spec 012 交付**。本 spec 一次性完成剩余 4 项：

1. **P2-9 分批调仓 + 冲击成本建模**：大资金一次性调仓的冲击成本未建模，回测高估收益；一次性大单对低流动性标的不真实。
2. **遗留#1 watcher 元数据下发**：engine 的 P0-1（静态过滤）/ P0-4（涨跌停拒单）逻辑已就绪但 watcher 未下发 `is_st / is_suspended / is_limit_up / is_limit_down / list_date`，逻辑空转。
3. **遗留#2 前端结果页展示**：`result_serializer` 已回传 `rebalance_diagnosis / effective_config.warmup_period / annual_turnover_ratio`，前端未展示。
4. **遗留#3 行业归属 point-in-time**：`buildKlineData` 用 `getLatestL1Industries`（最新归属），跨年回测有轻微 lookahead bias。

闭环 008/009 全部待办，让一期已就绪的 P0-1 / P0-4 / 行业暴露全部「通电」，并把进阶的分批调仓能力补齐。

## What Changes

### 一、P2-9 分批调仓 + 冲击成本（engine + 前端）

- **schema 扩展**：`RebalanceModel` 加 `execution: Optional[ExecutionConfig]`，含 `split_days ∈ [1,5]` 与 `impact_cost_bps ≥ 0`（`ExecutionConfig` 新模型）。
- **engine 分批冻结状态机**：`compiler.on_daily_rebalance` 在触发日算完整 plan，`split_days>1` 时存 `_pending_split` 状态机，后续 N-1 天执行增量；新触发日撞未完成分批 → 作废重来 + 日志；分批日某标的当日涨停 → 该份增量跳过该标的。
- **engine 冲击成本 volume-linear**：每笔成交在 fill price 上叠加 `impact = impact_cost_bps × 本笔成交额 / 当日成交额`，经 `order_target_weights` 的 `price_map` 注入。
- **结果回传**：`result_serializer` 增加 `execution_diagnosis`（`split_days / splits_completed / splits_interrupted / total_impact_cost / avg_participation`）。
- **validator**：`split_days ∈ [1,5]`、`impact_cost_bps ≥ 0`，否则 `INVALID_EXECUTION_CONFIG`；`execution` 仅轮动范式合法，择时报 `EXECUTION_REQUIRES_REBALANCE`。
- **前端**：editor Rebalance 层加 `execution` 控件（split_days / impact_cost_bps 输入），collect/refill 透传。

### 二、遗留#1 watcher 元数据下发（Tier 2 完整 Tushare，watcher）

端到端对接 3 个 Tushare 接口（参照 P0-0 申万行业 10 处改动范例），每个接口走完整链路：

- **`namechange`（doc_id=160）**：股票更名历史（ST 戴帽摘帽），落 `stock_namechange` 表。
- **`suspend_d`（doc_id=161）**：逐日停牌，落 `stock_suspend_d` 表。
- **`stk_limit`（doc_id=183）**：每日涨跌停价，落 `stock_stk_limit` 表。
- **`list_date`**：从 `stock_basic`（`StockBasicDO.listDate`）取，静态字段，无新接口。
- **`buildKlineData` 注入**：替换现有 `// TODO: is_st / is_suspended / ...`（L623），循环外批量预查建索引（参照 `swL1Map` / `rebalanceFlags` 模式），逐 bar 写入 5 个字段。
- **三处 DDL 同步**：`schema-mysql.sql` + `schema-sqlite.sql` + `DataInitServiceImpl` Map。
- **限流 / 定时**：`application.yml` 三接口限流；定时任务每日增量；首次全量回补。
- **engine 侧无改动**（字段名已在 `_META_FIELDS` 注册，逻辑已消费，watcher 下发即生效）。

### 三、遗留#2 前端结果页展示（前端）

回测结果页（`backtest-report.js` 等）新增展示组件：
- `rebalance_diagnosis`：警示卡片（成交 < 选出时高亮）。
- `effective_config.warmup_period`：配置摘要行（source=user_override 时标注）。
- `annual_turnover_ratio`：metrics 行。

### 四、遗留#3 行业归属 point-in-time（watcher）

- `SwIndustryService` 新增批量接口 `getL1IndustriesPit(tsCodes, startDate, endDate)`：一次性查区间内 `sw_industry_member` 全部记录，内存按 ts_code + update_date forward-fill 建索引。
- `buildKlineData` 把 `getLatestL1Industries`（L571）替换为 `getL1IndustriesPit`，逐 bar 取当日归属。
- engine 侧无改动（`data_adapter` 仍按 bar 取 `sw_industry_l1`）。

### 跨字段校验新增（**BREAKING** 风险点）

- `INVALID_EXECUTION_CONFIG`：`split_days ∉ [1,5]` 或 `impact_cost_bps < 0`。
- `EXECUTION_REQUIRES_REBALANCE`：`execution` 出现在非轮动范式（has_rebalance=False）。

### 向后兼容（旧策略 JSON）

- `execution` 字段缺省（None）→ 等价现状（一次性调仓、不建模冲击成本）。

## Impact

- **Affected specs**：
  - 011-rotation-config-completeness（P2-9 是其登记的三期待办，本 spec 交付）
  - 012-rotation-advanced-abilities（P1-6 已交付，本 spec 与之正交）
  - 010-rotation-data-governance（遗留#1 / 遗留#3 是其 point-in-time 治理在元数据完整性上的延续）
  - 统一策略配置 Schema v1.0（RebalanceModel 加 `execution`）
- **Affected code**：
  - **engine（Python）**：`services/strategy/{models,validator,errors}.py`、`services/backtest/{compiler,result_serializer}.py`
  - **watcher（Java）**：`constant/{TushareApiEnum,InitStep}.java`、`resources/schema-{mysql,sqlite}.sql`、`DataInitServiceImpl`、`dto/tushare/*`（3 组 DTO）、`model/*DO`（3 个）、`mapper/*`（3 个）、`service/{SwIndustryService,...}`（3 个新 Service + SwIndustryService 扩展）、`client/TushareClient.java`、`service/impl/BacktestServiceImpl.java`（buildKlineData）、`task/*`（3 个定时）、`application.yml`
  - **前端**：`static/js/strategy-editor.js`、`static/js/backtest-report.js`、`templates/quant/strategies/editor.html`

---

## ADDED Requirements

### Requirement: 分批调仓冻结状态机（P2-9 分批）

系统 SHALL 在 `RebalanceModel.execution.split_days > 1` 时，按「冻结法」分批执行调仓：触发日计算完整 plan（ranking + score 排序后的 target_weights，经一期 cash_reserve / 单标的上限 / 行业暴露 / buffer / 涨跌停拒单后处理），切成 N 份存入策略状态机 `_pending_split`，当天执行第 1 份增量；后续 N-1 个调仓日（非频率触发日）各执行一份增量，直到耗尽。

#### Scenario: split_days=1 行为不变
- **WHEN** `execution.split_days == 1`（或缺省）
- **THEN** 触发日一次性 `order_target_weights(plan)`，行为与改造前一致

#### Scenario: split_days=3 分三天执行
- **WHEN** `execution.split_days == 3` 且触发日到来
- **THEN** 触发日执行 plan 的 1/3 增量；后续 2 个交易日各执行 1/3 增量；trades 分布在 3 天

#### Scenario: 新触发日撞未完成分批
- **WHEN** 新调仓日到来时 `_pending_split` 仍在
- **THEN** 作废剩余分批，按新 plan 重新开始；日志记录「分批被打断：原 plan 剩 X 天未执行」；`execution_diagnosis.splits_interrupted += 1`

#### Scenario: 分批日某标的当日涨停
- **WHEN** 分批日某买入标的当日 `is_limit_up == "1"`
- **THEN** 该份增量跳过该标的（涨跌停是逐日事实，不冻结判定）

### Requirement: 冲击成本 volume-linear 建模（P2-9 冲击成本）

系统 SHALL 在 `RebalanceModel.execution.impact_cost_bps` 非空时，对每笔成交在 fill price 上叠加冲击成本：`participation = min(order_value / bar_volume, PARTICIPATION_CAP)`（CAP 默认 1.0），`impact_bps = impact_cost_bps * participation`，`fill_price_adj = fill_price * (1 + sign * impact_bps / 10000)`（买入加价/卖出折价）。注入路径 SHALL 经 `order_target_weights` 的 `price_map`（不用 `slippage`，因 slippage 是固定比例无法表达线性）。

#### Scenario: 低成交量标的冲击成本更高
- **WHEN** `impact_cost_bps=10` 且某标的当日成交量低、order_value 占比高
- **THEN** 该标的成交价叠加更高冲击（participation 接近 CAP 时 impact 接近 10bps）

#### Scenario: 不建模时无冲击
- **WHEN** `impact_cost_bps` 为 None
- **THEN** 成交价不叠加冲击，等价现状

### Requirement: 调仓执行诊断回传（P2-9 诊断）

系统 SHALL 在回测结果中回传 `execution_diagnosis`，含 `split_days / splits_completed / splits_interrupted / total_impact_cost / avg_participation`。

#### Scenario: 诊断字段存在
- **WHEN** 回测使用 `execution` 配置
- **THEN** `result_serializer` 输出 `execution_diagnosis` 且字段齐全

### Requirement: watcher 元数据 5 字段下发（遗留#1）

系统 SHALL 经 Tushare `namechange` / `suspend_d` / `stk_limit` 三接口落库（三处 DDL 同步），`buildKlineData` 在每根 bar 注入 5 个元数据字段：`is_st`（当日 ST，按 namechange 该日生效 name 含 "ST"）、`is_suspended`（当日停牌）、`is_limit_up` / `is_limit_down`（按 stk_limit 精确判定，`close >= up_limit` / `close <= down_limit`）、`list_date`（stock_basic 静态）。预查 SHALL 在循环外一次性建索引（namechangeMap / suspendSet / limitMap / listDateMap），不逐 bar 查表。

#### Scenario: ST 戴帽摘帽日切换正确
- **WHEN** 某标的 2024-06-01 起 name 含 "ST"，2025-06-01 摘帽
- **THEN** 2024-06-01 起 bar `is_st="1"`，2025-06-01 起 bar `is_st="0"`

#### Scenario: 涨停精确判定
- **WHEN** 某日 close == stk_limit.up_limit（一字涨停）
- **THEN** 该日 bar `is_limit_up="1"`

#### Scenario: 缺涨跌停数据日
- **WHEN** stk_limit 表无某 (ts_code, trade_date) 记录
- **THEN** 该 bar 不下发 `is_limit_*`（engine 静默跳过涨跌停判定 + warning）

#### Scenario: engine 静态过滤生效
- **WHEN** engine 配置 `exclude_st=true` 且 watcher 已下发 `is_st`
- **THEN** 回测日志显示「剔除 ST: N 只」

### Requirement: 三表落库与定时同步（遗留#1 落库）

系统 SHALL 在 `schema-mysql.sql` / `schema-sqlite.sql` / `DataInitServiceImpl` Map 三处同步新增 `stock_namechange` / `stock_suspend_d` / `stock_stk_limit` 三表；注册 `InitStep.NAMECHANGE / SUSPEND_D / STK_LIMIT`；提供幂等 delete-then-insert 的全量初始化与每日增量定时；`application.yml` 配置限流（namechange/suspend_d 200/min，stk_limit 500/min）。

#### Scenario: 一键初始化三表
- **WHEN** 触发 DataInit 的 NAMECHANGE / SUSPEND_D / STK_LIMIT 步骤
- **THEN** 三表落库且按业务键幂等

#### Scenario: 切 sqlite 不缺表
- **WHEN** 切换 db-type 为 sqlite
- **THEN** 三表均存在（schema-sqlite.sql 同步）

### Requirement: 行业归属 point-in-time（遗留#3）

系统 SHALL 提供 `SwIndustryService.getL1IndustriesPit(tsCodes, startDate, endDate)`，返回 `Map<tsCode, Map<tradeDate, indexCode>>`（按 update_date forward-fill）。`buildKlineData` SHALL 用该接口替换 `getLatestL1Industries`，逐 bar 注入当日归属。

#### Scenario: 跨年回测用当时归属
- **WHEN** 某标的 2024 年属「银行」、2025 年 6 月申万调整后属「非银金融」，回测区间跨两年
- **THEN** 2024 年 bar `sw_industry_l1` 为银行，2025 年调整生效日后为非银金融（非全程最新归属）

#### Scenario: 早于最早 update_date
- **WHEN** 某标的回测起始日早于 `sw_industry_member` 最早 update_date
- **THEN** 该段 bar 不下发 `sw_industry_l1`，engine 行业暴露静默跳过 + warning

---

## MODIFIED Requirements

### Requirement: RebalanceModel 配置（统一策略配置 Schema）

`RebalanceModel` 新增可选字段 `execution: Optional[ExecutionConfig]`。`ExecutionConfig` 含 `split_days: int = Field(1, ge=1, le=5)` 与 `impact_cost_bps: Optional[float] = Field(None, ge=0.0)`，`extra="forbid"`。缺省 `execution=None` 等价现状（一次性调仓、不建模冲击）。

### Requirement: 回测结果序列化（result_serializer）

`serialize_result` 输出新增 `execution_diagnosis`（仅当策略含 execution 配置时非空，否则为 None/空对象）。其余字段（`rebalance_diagnosis / effective_config / metrics.annual_turnover_ratio`）维持现状。

---

## REMOVED Requirements

无（全部为新增/修改，不删除既有能力；`execution=None` 即退回现状）。
