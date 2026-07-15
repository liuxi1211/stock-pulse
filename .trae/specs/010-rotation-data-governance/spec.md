# 轮动范式未来数据治理与选股模型重构 Spec

> 对齐 PRD：`sdlc/prd/007-轮动范式治理/轮动范式未来数据治理与选股模型重构.md` v1.0
> change-id：`010-rotation-data-governance`
> akquant 0.2.47 · 统一策略配置 Schema v1.0 · 与 009-strategy-paradigm-exclusive 互补同批落地

## Why

对「策略管理 - 轮动范式」进行未来数据核查，发现 009（范式互斥治理）未覆盖的三类系统性缺陷。akquant 0.2.47 框架本身设计完备（`on_daily_rebalance` 时序严格无未来、`get_history` 有 cutoff、`rebalance_to_topn` 签名完整），问题全部出在 stock-pulse 封装层：

- **缺陷 A（成分股 point-in-time 泄露）** 🔴：watcher `resolveBacktestSymbols` 用 `getConstituentsInRange` 取回测区间成分股**并集**，导致回测早期可用「未来才入选」的股票做决策（lookahead bias）。讽刺的是 watcher **已有** point-in-time 查询能力（`index_weight` 表 + `selectConstituentsAt`），只是回测路径未启用。
- **缺陷 B（基本面因子静默退化为 NaN）** 🟡：watcher `buildKlineData` 下发的 bar 只含 OHLCV，不含 PE/PB/ROE/市值。engine `_compute_factor` 取不到值返回 NaN，含 NaN 的 symbol 被 `_filter_valid_symbols` 剔除，极端情况下整个 universe 被过滤光，rebalance 静默变「不调仓」，仅日志 warning。
- **缺陷 C（选股模型分层不足）** 🟡：004 Schema 的 `screen_config.conditions` 语义过载，把「因子打分公式」（连续数值计算）与「硬性筛选阈值」（离散布尔判断）混在一个字段，与 akquant 的 `rebalance_to_topn` 执行链路不对齐。

本次只动 stock-engine + stock-watcher + Schema + 前端封装层，不改 akquant 源码。

## What Changes

### 缺陷 A 修复（point-in-time 成分股过滤）

- **新增 watcher 内部只读端点**：`POST /api/internal/constituents/query`，复用 `selectConstituentsAt` 返回 `≤ trade_date` 的最新**生效日**成分股快照。
- **放松 engine 边界约束**：从「engine 不触库、不回调 watcher」修订为分层约束——数据单源性/行情基本面仍强约束；新增「参考数据弱约束 + 例外」，允许 engine 在回测期间按需查询 watcher 的 `/api/internal/*` 只读端点（幂等、无副作用）。
- **新增 engine WatcherClient**：`stock-engine/services/backtest/watcher_client.py`，封装对 watcher 内部接口的只读查询。
- **rebalance_engine 集成 point-in-time 过滤**：`select_at_rebalance_date` 在 `_build_candidates` 之前，对 `universe ∈ {csi300, csi500}` 调用 `WatcherClient.get_constituents_at` 过滤 kline_map。
- **WatcherClient 注入与降级**：watcher HTTP 请求 header 携带 `X-Watcher-Base-Url`（或读环境变量 `WATCHER_BASE_URL`），未配置则 `watcher_client=None`，跳过过滤并打 warning（向后兼容）。

### 缺陷 B 修复（基本面因子数据下发）

- **watcher `buildKlineData` 补齐基本面**：对每只成分股，除 OHLCV 外按日期 join `daily_basic` 表（PE_TTM/PB/TOTAL_MV/ROE_TTM 等）。
- **engine kline 解析提取 extra**：`kline_to_df_with_extra` 把 OHLCV 放 DataFrame，基本面字段放入 akquant Bar 的 `extra: dict[str,float]`（依 akquant rules 02-data-input.md §4）。
- **rebalance_engine `_compute_factor` 取用 extra**：从 `candidate["extra"]` 按 trade_date 取基本面字段，替代当前的 `candidate.get("fundamentals")` 空字典兜底。

### 缺陷 C 修复（选股模型重构为 4 层）

- **screen_config 重构**：从「universe/conditions/ranking/filters/top_n」5 字段扁平结构，改为 `universe / factor / filter / portfolio` 4 层对象结构，对齐 akquant 执行链路。
  - `universe.pool` + `point_in_time` + `stocks`
  - `factor.method` + `weights`（连续打分 → scores 字典）
  - `filter.conditions` + `exclude_st` / `exclude_suspended` / `exclude_limit_up` / `min_list_days`（离散布尔剔除）
  - `portfolio.top_n`（TopN + 权重模式）
- **weight_mode 归属**：留在 `trading_config.rebalance.weight_mode`（`equal` 忽略 factor 分数；`score` 按 factor 加权），`portfolio.top_n` 只决定选几只。
- **旧 Schema 不兼容迁移**：`conditions → filter.conditions`、`ranking → factor`、`filters → filter`（合并）、`top_n → portfolio.top_n`、`universe(string) → universe.pool`。**BREAKING**。

### 全链路与存量

- **边界约束注释修订**：`rebalance_engine.py` L14-17 改为分层约束说明。
- **akquant rules 补充**：`03-strategy-api.md` §12 补 point-in-time 成分股要求；新增 `10-rotation-pit-guide.md` 轮动防坑指南。
- **validator 新增 4 层结构校验**：`services/strategy/validator.py`。
- **前端适配**：`strategy-editor.js` + `editor.html` screen_config UI 适配 4 层。
- **存量清零**：与 009 同步执行 `TRUNCATE quant_strategy + quant_strategy_version`，重新加载模板。**BREAKING**。

## Impact

- **Affected specs**：
  - 统一策略配置 Schema（§3.2 screen_config 重构为 4 层）
  - 009-strategy-paradigm-exclusive（互补，建议同批落地）
  - akquant rules `03-strategy-api.md`（§12 补充）、新增 `10-rotation-pit-guide.md`
- **Affected code**（按 PRD §7）：
  - watcher：`BacktestServiceImpl.java`（buildKlineData 补基本面 / resolveBacktestSymbols 标注仅用于数据准备）、新增 `InternalController.java`、`IndexWeightMapper.java`（确认 selectConstituentsAt 返回生效日）、`IndexWeightTask.java`（确认 trade_date 存生效日）
  - engine：新增 `services/backtest/watcher_client.py`、`services/backtest/rebalance_engine.py`（point-in-time 过滤 + 取 extra 基本面 + 边界注释）、`services/backtest/runner.py`（注入 WatcherClient）、`services/backtest/strategy_runner.py`（kline_to_df_with_extra）、`services/strategy/validator.py`（4 层结构校验）
  - Schema：`sdlc/prd/004-策略管理/统一策略配置Schema.md` §3.2
  - 前端：`strategy-editor.js` + `editor.html`
- **不影响**：akquant 源码；003 选股中心独立选股 API（`POST /api/screener/run` 消费 screen_config 的链路仅字段调整）；择时范式（signals）链路（universe ≤ 10，无成分股泄露）。

## ADDED Requirements

### Requirement: watcher point-in-time 成分股查询内部接口

系统 SHALL 在 watcher 提供 `POST /api/internal/constituents/query` 只读端点，接收 `{index_code, trade_date}`，返回 `{index_code, trade_date, effective_date, constituents[]}`，其中 `effective_date` 为 `≤ trade_date` 的最新**生效日**快照。

#### Scenario: 查询历史日期的成分股
- **WHEN** 调用方传入 `{index_code:"000300.SH", trade_date:"2022-06-15"}`
- **THEN** 返回 `effective_date ≤ 2022-06-15` 的最新成分股快照，`constituents` 为该日实际入选成分股列表

#### Scenario: 查询日早于最早快照
- **WHEN** 传入的 trade_date 早于 index_weight 表中该指数的最早记录
- **THEN** 返回空 constituents 或最近可用快照，并附 warning 标识（降级策略）

### Requirement: engine WatcherClient 只读内部接口客户端

系统 SHALL 在 engine 提供 `WatcherClient`，仅限查询 watcher 的 `/api/internal/*` 只读端点，不得用于行情/基本面拉取。

#### Scenario: 配置了 WATCHER_BASE_URL
- **WHEN** 回测请求 header 或环境变量提供 `X-Watcher-Base-Url` / `WATCHER_BASE_URL`
- **THEN** 构造 `WatcherClient` 注入 `RebalanceEngine`，回测期间可查询成分股

#### Scenario: 未配置 base_url（降级）
- **WHEN** 既无 header 也无环境变量
- **THEN** `watcher_client=None`，rebalance_engine 跳过 point-in-time 过滤并打 warning 日志，回测不报错（向后兼容）

### Requirement: 轮动范式 point-in-time 成分股过滤

系统 SHALL 在轮动范式（rebalance）的每个调仓日，对 `universe ∈ {csi300, csi500}` 的候选池执行 point-in-time 过滤——仅保留「该调仓日实际入选成分股」。

#### Scenario: 股票 X 在回测期间入选
- **WHEN** 回测区间 2022-01 ~ 2024-12，universe=csi300，股票 X 于 2024-06 入选
- **THEN** 2022-01 的调仓日，X 不在候选池，不可被买入（AC-1）

#### Scenario: manual universe 不查接口
- **WHEN** universe=manual
- **THEN** 不调用 WatcherClient，直接用 screen_config.universe.stocks 列表（AC-9）

### Requirement: 基本面因子 point-in-time 下发

系统 SHALL 让 watcher 在 buildKlineData 时为每只成分股的每根 bar 补齐 PE_TTM/PB/TOTAL_MV/ROE_TTM 等基本面字段，engine 解析时放入 akquant Bar 的 `extra` dict。

#### Scenario: 轮动策略引用 PE_TTM
- **WHEN** 轮动策略 `filter.conditions` 或 `factor.weights` 引用 `PE_TTM`
- **THEN** 回测路径 PE_TTM 取到真实值，非 NaN（AC-2）

#### Scenario: 基本面数据缺失
- **WHEN** 某只标的某日 daily_basic 缺记录
- **THEN** 该字段为 NaN，由后续 filter 阶段决定是否剔除，并打 debug 日志

### Requirement: 选股模型 4 层结构

系统 SHALL 将 `screen_config` 重构为 `universe / factor / filter / portfolio` 4 层对象结构，4 层职责清晰对齐 akquant 执行链路。

#### Scenario: 合法 4 层配置
- **WHEN** 提交的 screen_config 含完整的 universe/factor/filter/portfolio 4 层
- **THEN** 校验通过，回测正常执行（AC-4）

#### Scenario: 缺失必需层
- **WHEN** screen_config 缺失 universe 或 portfolio 层
- **THEN** 返回校验错误 `SCREEN_CONFIG_LAYER_MISSING`

#### Scenario: 旧 5 字段扁平结构
- **WHEN** 提交旧结构（顶层 conditions/ranking/filters/top_n）
- **THEN** 返回校验错误 `SCREEN_CONFIG_DEPRECATED_STRUCTURE`，提示迁移到 4 层

### Requirement: weight_mode 与 factor 的关系明确化

系统 SHALL 让 `trading_config.rebalance.weight_mode` 与 `screen_config.factor` 的关系明确：`weight_mode="equal"` 忽略 factor 分数等权分配；`weight_mode="score"` 按 factor 计算的 score 加权。

#### Scenario: equal 模式
- **WHEN** rebalance.weight_mode="equal"
- **THEN** TopN 等权分配，factor 分数仅用于排序选 TopN

#### Scenario: score 模式
- **WHEN** rebalance.weight_mode="score"
- **THEN** TopN 按 factor 计算的 score 加权分配（AC-3）

## MODIFIED Requirements

### Requirement: engine 边界约束（分层化）

`rebalance_engine.py` L14-17 的「engine 不触库、不回调 watcher」注释修订为分层约束：

| 约束层级 | 内容 | 例外 |
|---|---|---|
| 数据单源性（强） | engine 永不直接读写业务数据库（SQLite/MySQL） | 无例外 |
| 行情/基本面（强） | 行情与基本面由 watcher 预传，engine 不反向拉取 | 无例外 |
| 参考数据（弱，新增例外） | 成分股身份等「参考数据」允许 engine 在回测期间按需查询 watcher 的只读内部接口 | 仅限 `/api/internal/*` 只读端点；查询幂等、无副作用 |

### Requirement: screen_config Schema（4 层重构）

`sdlc/prd/004-策略管理/统一策略配置Schema.md` §3.2 重构：

```jsonc
{
  "screen_config": {
    "universe": { "pool": "csi300", "point_in_time": true, "stocks": null },
    "factor":   { "method": "composite", "weights": { "MOM_20D": 0.4, "ROE_TTM": 0.3, "TOTAL_MV": -0.3 } },
    "filter":   { "conditions": {}, "exclude_st": true, "exclude_suspended": true, "exclude_limit_up": true, "min_list_days": 60 },
    "portfolio":{ "top_n": 30 }
  }
}
```

### Requirement: rebalance_engine _compute_factor（取 extra）

`rebalance_engine.py` L367-370 的 `_compute_factor` TUSHARE 分支改为从 `candidate["extra"]` 按 trade_date 取基本面字段，替代当前的 `candidate.get("fundamentals")` 空字典兜底。

## REMOVED Requirements

### Requirement: screen_config 旧 5 字段扁平结构
**Reason**：「选股条件」语义过载，因子打分与硬筛选混淆，与 akquant 执行链路不对齐。
**Migration**：不兼容。映射规则：`conditions → filter.conditions`、`ranking → factor`、`filters → filter`（合并）、`top_n → portfolio.top_n`、`universe(string) → universe.pool`。存量 `quant_strategy + quant_strategy_version` 与 009 同步全清。

## 验收（摘要，详见 checklist.md）

AC-1~AC-9 见 PRD §8，覆盖：point-in-time 过滤正确性、基本面取值真实、factor 加权正确、4 层结构校验、降级安全、watcher 内部接口生效日对齐、signals 不受影响、选股中心回归、manual universe 不查接口。
