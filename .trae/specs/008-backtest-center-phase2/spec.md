# 回测中心（第二波 / Phase 2）Spec

> **权威来源**：本 spec 是 [005 回测中心 PRD v2.0](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) §6.3-6.4 / §3.2 / §12.1-12.3 与 [Phase2-待开发功能清单](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/Phase2-待开发功能清单.md) 的开发实现约定。
> **第一波 spec**：[007-backtest-center](file:///d:/lcProject/stock-pulse/.trae/specs/007-backtest-center/spec.md)（已全部交付，本 spec 是其增量）。
> **akquant 版本**：0.2.47。
> **统一 Schema**：[统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) §3.3.3 / §3.3.4 / §4（字段已定稿，本 spec 仅做实现）。

---

## Why

第一波（007-backtest-center）已交付 SINGLE 单次回测 + signals 信号驱动 + exit.bracket 静态止损止盈。但有三类高复杂度范式被刻意推迟：

- **rebalance 多因子调仓驱动**（PRD §6.3）：多因子选股 + 定期调仓的 Portfolio 策略，目前 engine `compiler.py` 与 watcher `BacktestServiceImpl.checkParadigmSupported` 双层拒绝。
- **exit.rules 动态出场条件树**（PRD §6.4）：支持 `ref`（entry_price / position_pnl_pct 等）+ 复杂动态止损（trailing stop / 最高价回撤 / 多级止损），目前编译期拒绝。
- **exit.bracket.use_atr_stop ATR 动态止损**（PRD FR-6a）：ATR 倍数动态止损价，目前编译期拒绝。

第二波要把这三类范式落地到回测引擎。**GRID/WALK_FORWARD 参数寻优仍是第三波**（前置 OQ-3 paramGrid schema 反推 + 结构化 DSL），不在本 spec 范围。

## What Changes

### 新增：engine 共享层抽取（PRD §12.3 前置条件）

把 003 选股与 005 回测共用的条件求值 + 因子计算逻辑抽成共享模块，保证 AC「同条件同结果」可验证：

- **新建 `stock-engine/services/shared/`**：
  - `__init__.py`：导出共享符号。
  - `condition_evaluator.py`：把 `services/screener/engine.py` 的 `ConditionEngine`（截面）+ `services/backtest/trading_engine.py` 的 `TradingConditionEngine`（时序）合并为统一求值器，区分 `cross_section` / `time_series` 两种 mode；NaN 安全 / 除零降级 / 时序 comparator / ref 白名单集中在此。
  - `factor_pipeline.py`：把 `services/screener/factor_precompute.py`（批量预计算）+ `services/backtest/compiler.py` 的 `_compute_factor_values`（单 bar 实时算）统一为「同口径」入口，统一调 `factor_calculator`。
- **MODIFIED**：`services/screener/engine.py` 与 `services/backtest/trading_engine.py` 改为薄壳，re-export 共享层符号（保持向后兼容，不破坏 003/005 第一波代码）。

### 新增：engine 回测执行引擎扩展（rebalance + exit.rules + use_atr_stop）

**MODIFIED `stock-engine/services/backtest/compiler.py`**：

- 删除 `_check_phase1_paradigm` 中对 `rebalance` / `exit.rules` / `use_atr_stop` 的拒绝分支（L64-76）。
- 改名为 `_check_paradigm`（保留 GRID/WF 模式拒绝，第三波）。
- `compile_strategy` 新增范式分支：
  - **rebalance 在场**：动态生成 `on_daily_rebalance(trading_date, timestamp)` 方法，调「选股 + ranking + `rebalance_to_topn`」流程；保留 signals 分支（混合范式合法）。
  - **exit.rules 在场**：在 `on_bar` 末尾逐条评估 `exit.rules[].condition`（用 `TradingConditionEngine`，支持 ref），命中调 `action`（默认 `close_position`，支持 `sell`）。
  - **exit.bracket.use_atr_stop=true**：`on_before_trading` 计算 `stop_trigger = entry_price − atr_multiplier × ATR(atr_period)`，覆盖静态 stop_loss_pct 分支（互斥：use_atr_stop 优先）。

**新建 `stock-engine/services/backtest/rebalance_engine.py`**：

- `RebalanceEngine`：封装「调仓日选股」逻辑。`select_at_rebalance_date(screen_config, kline_map, trading_date) -> dict[str, float]`（返回 `{symbol: score}`），内部调共享层 `factor_pipeline` + `condition_evaluator(cross_section mode)` + `rank_stocks`。
- **硬约束**：engine 不回调 watcher（[选股与回测边界设计 §场景二](file:///d:/lcProject/stock-pulse/sdlc/prd/003-多因子选股中心/选股与回测边界设计.md) L379），调仓选股在 engine 本地完成，输入仅 kline_map。

**MODIFIED `stock-engine/services/backtest/runner.py`**：

- `run_backtest_engine` 在 `compile_strategy` 后无需改动（Strategy 子类自带 `on_daily_rebalance`）；但要扩展 `build_backtest_kwargs`：rebalance 范式下 `symbols` 传全 universe 列表（来自 `screen_config`）。

### 新增：watcher 编排层扩展

**MODIFIED `BacktestServiceImpl.java`**：

- 删除 `checkParadigmSupported`（L732-745）对 `rebalance` / `exit.rules` 的拒绝；保留 GRID/WF 模式拒绝（第三波）。
- 删除 `use_atr_stop` 的拒绝（如有；当前 watcher 侧仅校验 rebalance/exit.rules，ATR 拒绝在 engine 侧，watcher 透传即可）。
- **MODIFIED `buildKlineData`**：rebalance 范式（config 含 `trading_config.rebalance`）需喂入全 universe 的 K 线：
  - `screen_config.universe="manual"` → 用 `screen_config.stocks`（已有逻辑）。
  - `screen_config.universe="csi300"/"csi500"/"all_a_shares"` → watcher 从 SQLite 取回测区间内所有曾入选该指数的成分股并集（防幸存者偏差，已有 `resolveBacktestSymbols` 逻辑覆盖 csi300/csi500）。
  - **`all_a_shares` 全市场**：本 spec **限定为「watcher 维护的股票池白名单」**（避免传全市场 5000+ 标的的 K 线导致 HTTP 载荷爆炸 + engine 回测超时）；如 config 要求 `all_a_shares`，watcher 返回 `BACKTEST_UNIVERSE_TOO_LARGE`（400 + 引导文案「全市场回测暂不支持，请用 csi300/csi500 或 manual 池」）。

### MODIFIED：前端策略编辑器（005-strategy-visual-editor）

- **editor.html Tab 7 调仓**：`<fieldset>` 移除 `disabled`，移除「即将支持」alert-info；恢复交互。
- **editor.html Tab 6 ATR 动态止损**：`f-use-atr-stop` / `f-atr-period` / `f-atr-multiplier` 移除 `disabled` + badge。
- **editor.html Tab 6 exit.rules**：`f-exit-rules-fieldset` 移除 `style="display:none;"`，移除 alert-secondary。
- **strategy-editor.js**：
  - 恢复 `collectStateFromForm` 中 Tab 7 rebalance 的收集逻辑（删除 `delete s.trading_config.rebalance`）。
  - 恢复 ATR 三字段采集（解除注释）。
  - exit.rules 容器内规则行可正常添加（`collectExitRules` 已实现，只是容器被隐藏）。

### MODIFIED：engine HTTP API 常量

- `GET /python/v1/backtest/constants` 的 `paradigms_supported` 扩展为 `["signals+bracket", "signals+atr_stop", "rebalance", "exit.rules", "mixed"]`（前端权威源，动态拉取）。

### 明确不做（推迟第三波）

- GRID 网格寻优 / WALK_FORWARD 滚动验证（含 OQ-3 paramGrid schema 反推 + constraint/resultFilter 结构化 DSL）。
- HTML 报告导出。
- RUNNING 任务真终止。
- DRAFT 版本回测（OQ-4 未决）。
- `all_a_shares` 全市场回测（HTTP 载荷 + 性能边界，本 spec 限定 universe 规模）。
- 组合层风控（`exit.rules` 当前仅 per_symbol；`eval_scope:"portfolio"` + ref 扩展 `portfolio_drawdown` 属第三波）。

## Impact

- **Affected specs**：
  - `007-backtest-center`（第一波）：本 spec 是其增量；第一波 spec.md 的 REMOVED Requirements 在本 spec 中转为 ADDED。
  - `005-strategy-visual-editor`：编辑器 Tab 6/7 解锁。
  - `003-multi-factor-screener`：共享层抽取会改 `services/screener/engine.py` 为薄壳（向后兼容 re-export）。
- **Affected code（关键路径）**：
  - engine：`stock-engine/services/shared/`（新建）；`stock-engine/services/backtest/compiler.py`（删拒绝 + 加分支）；`stock-engine/services/backtest/rebalance_engine.py`（新建）；`stock-engine/services/backtest/runner.py`（build_backtest_kwargs 扩展）；`stock-engine/services/backtest/trading_engine.py`（薄壳化）；`stock-engine/services/screener/engine.py`（薄壳化）；`stock-engine/api/v1/backtest.py`（constants 扩展）。
  - watcher：`BacktestServiceImpl.java`（删拒绝 + buildKlineData universe 校验 + all_a_shares 拒绝）；`BacktestErrorCodes.java`（新增 `BACKTEST_UNIVERSE_TOO_LARGE`）。
  - 前端：`templates/quant/strategies/editor.html`（Tab 6/7 解锁）；`static/js/strategy-editor.js`（恢复采集）。
- **依赖上游**：002 因子库（54 因子）、003 `factor_calculator` / `rank_stocks` / `apply_filters`、004 `StrategyConfigModel`（RebalanceModel/ExitRuleModel/BracketModel 已就绪）。
- **硬约束**：engine 不触库（`grep sqlite3|sqlalchemy|\.db` 在 `services/backtest/` + `services/shared/` 无匹配）；第二波仍无 eval 注入面（`grep "\beval\b\|\bexec\b\|__import__"` 无匹配）；akquant 锁定 0.2.47；engine 不回调 watcher（rebalance 选股在 engine 本地）。

---

## ADDED Requirements

### Requirement: engine 共享层抽取（services/shared/）

系统 SHALL 把条件求值与因子计算逻辑抽成 `stock-engine/services/shared/` 共享模块，让选股（003）与回测（005 rebalance）共用同一份求值器与因子管线，保证 AC「同条件同结果」。

#### Scenario: 统一求值器双 mode
- **GIVEN** `services/shared/condition_evaluator.py` 的统一 `ConditionEngine`
- **WHEN** 用 `mode="cross_section"` 求值含 `cross_up` 的条件
- **THEN** 抛 `ScreenTimeSeriesForbiddenError`（与原 003 行为一致）；用 `mode="time_series"` 求值同条件则正常返回布尔

#### Scenario: 因子管线同口径
- **GIVEN** 同一组 factorKey + params + OHLCV 输入
- **WHEN** 分别走 `factor_pipeline.precompute`（批量）与 `factor_pipeline.compute_latest`（单 bar）
- **THEN** 最新一根 bar 的因子值一致（差异仅在前置 NaN 预热段）

#### Scenario: 向后兼容
- **GIVEN** 003 选股代码 `from services.screener.engine import ConditionEngine`
- **WHEN** 抽取共享层后
- **THEN** 该 import 仍可用（`services/screener/engine.py` re-export 共享层符号）；005 第一波 `from services.backtest.trading_engine import TradingConditionEngine` 同样仍可用

#### Scenario: 共享层不触库
- **GIVEN** `services/shared/` 目录
- **WHEN** `grep -rE "sqlite3|sqlalchemy|\\.db" services/shared/`
- **THEN** 无匹配

### Requirement: rebalance 多因子调仓驱动（engine + watcher）

系统 SHALL 支持 `trading_config.rebalance` 在场的策略版本回测：watcher 拼装全 universe K 线 → engine `compile_strategy` 生成带 `on_daily_rebalance` 的 Strategy 子类 → 调仓日跑本地选股 + ranking → `rebalance_to_topn` 调仓。

#### Scenario: rebalance 编译生成 on_daily_rebalance
- **GIVEN** config 含 `trading_config.rebalance.frequency="monthly"` + `screen_config`（universe=csi300, ranking=composite）
- **WHEN** `compile_strategy(config)`
- **THEN** 生成的 Strategy 子类含 `on_daily_rebalance(trading_date, timestamp)` 方法；不再抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`

#### Scenario: 调仓日选股在 engine 本地
- **GIVEN** rebalance 范式的回测，调仓日到达
- **WHEN** `on_daily_rebalance` 触发
- **THEN** engine 用 `RebalanceEngine.select_at_rebalance_date` 在本地对 kline_map 跑 ConditionEngine（cross_section）+ ranking，输出 `{symbol: score}`；不回调 watcher

#### Scenario: rebalance_to_topn 调仓
- **GIVEN** 调仓日选出 TopN 标的 + score
- **WHEN** 调仓执行
- **THEN** 调 `self.rebalance_to_topn(scores, top_n, weight_mode=, long_only=, liquidate_unmentioned=)`，参数从 `RebalanceModel` 映射：`weight_mode` ← rebalance.weight_mode（默认 equal）；`long_only` ← rebalance.long_only（默认 true）；`liquidate_unmentioned` ← replace_method=="full" ? True : False

#### Scenario: 调仓频率触发判断
- **GIVEN** `rebalance.frequency="monthly"` + `day_of_period=1`
- **WHEN** `on_daily_rebalance` 被每根 bar 调用
- **THEN** 仅在每月第 1 个交易日执行调仓逻辑（其余日期 return）；`weekly` 按 `day_of_period` 周几（0=周一）；`daily` 每日；`quarterly` 每季首日

#### Scenario: signals + rebalance 混合范式
- **GIVEN** config 同时含 `signals` 与 `rebalance`
- **WHEN** 编译 + 回测
- **THEN** `on_bar` 执行 signals 信号驱动；`on_daily_rebalance` 执行调仓；两者共存不冲突（合法混合范式）

#### Scenario: watcher 不再拒绝 rebalance
- **GIVEN** config 含 `trading_config.rebalance`
- **WHEN** `POST /api/backtest/run`
- **THEN** watcher 不返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`；正常拼装全 universe kline_data → 调 engine

#### Scenario: all_a_shares 全市场拒绝
- **GIVEN** config 的 `screen_config.universe="all_a_shares"`
- **WHEN** watcher `buildKlineData` 解析 universe
- **THEN** 返回 400 + `BACKTEST_UNIVERSE_TOO_LARGE`（引导用 csi300/csi500/manual），不调 engine

#### Scenario: csi300 universe 拼装（防幸存者偏差）
- **GIVEN** `screen_config.universe="csi300"` + 回测区间 2023-01-01 ~ 2024-01-01
- **WHEN** watcher `resolveBacktestSymbols`
- **THEN** 取回测区间内所有曾入选沪深300的成分股并集（已有逻辑），作为 kline_data 的 symbol 列表

### Requirement: RebalanceEngine（engine，新增）

系统 SHALL 新建 `stock-engine/services/backtest/rebalance_engine.py`，封装调仓日选股逻辑：`select_at_rebalance_date(screen_config, kline_map, trading_date) -> dict[str, float]`。

#### Scenario: 选股 + ranking 输出 score
- **GIVEN** screen_config（conditions + ranking=composite + weights）
- **WHEN** `select_at_rebalance_date`
- **THEN** 对 kline_map 的每个 symbol 取截至 trading_date 的历史窗口 → 算因子 → ConditionEngine(cross_section) 过滤 → ranking 打分 → 返回 `{symbol: score}`（仅含通过 conditions 的标的）

#### Scenario: 无 conditions 仅 ranking
- **GIVEN** screen_config.ranking 在场但 conditions=null
- **WHEN** select
- **THEN** 对全 universe ranking 打分，返回 `{symbol: score}`（不过滤）

#### Scenario: 无 ranking 仅 conditions
- **GIVEN** screen_config.conditions 在场但 ranking=null
- **WHEN** select
- **THEN** 过滤后返回 `{symbol: 0.0}`（默认 score，配合 weight_mode=equal）

#### Scenario: engine 不回调 watcher
- **GIVEN** RebalanceEngine 调用
- **WHEN** select
- **THEN** 仅依赖入参 kline_map + screen_config；无 HTTP 调用 / 无 watcher 回调

### Requirement: exit.rules 动态出场条件树（engine）

系统 SHALL 支持 `trading_config.exit.rules[]` 在场的策略版本回测：`on_bar` 末尾逐条评估 `rules[].condition`（用 `TradingConditionEngine`，支持 ref），命中调 `action`。

#### Scenario: exit.rules 编译不拒绝
- **GIVEN** config 含 `exit.rules=[{name:"止损", condition:{...}, action:"close_position"}]`
- **WHEN** `compile_strategy`
- **THEN** 不抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`；生成的 `on_bar` 含 exit.rules 评估分支

#### Scenario: 动态止损命中
- **GIVEN** 持仓 + `exit.rules[0].condition = {left:{ref:"entry_price"}, op:"-", right:{op:"*", left:{factor:"ATR",params:{timeperiod:14}}, right:{value:2}}}` 包装为 `{left: <entry-2*ATR>, comparator:">", right:{ref:"CLOSE"}}`（即 CLOSE < entry − 2×ATR）
- **WHEN** `on_bar` 求值
- **THEN** 命中时调 `close_position()`；未命中保持持仓

#### Scenario: action 分派
- **GIVEN** `exit.rules[0].action="sell"` + 命中
- **WHEN** on_bar
- **THEN** 调 `self.sell(quantity=...)`（按 position_sizing.target 或全量）；`action="close_position"`（默认）调 `close_position()`

#### Scenario: ref 持仓上下文
- **GIVEN** exit.rules 的 condition 含 `{ref:"position_pnl_pct"}`
- **WHEN** 求值
- **THEN** 从 `_build_position_ctx` 取值（与 signals 的 ref 白名单一致：entry_price/position_pnl_pct/position_qty/bars_held）

#### Scenario: 多条 rules 短路
- **GIVEN** exit.rules 含 3 条
- **WHEN** on_bar 逐条评估
- **THEN** 任一条命中即触发 action（OR 语义）；命中后不再评估后续（短路）

### Requirement: exit.bracket.use_atr_stop ATR 动态止损（engine）

系统 SHALL 支持 `exit.bracket.use_atr_stop=true` 的策略版本回测：`on_before_trading` 计算 `stop_trigger = entry_price − atr_multiplier × ATR(atr_period)`，下动态止损 bracket 单。

#### Scenario: ATR 止损编译不拒绝
- **GIVEN** config 含 `exit.bracket={use_atr_stop:true, atr_period:14, atr_multiplier:2, take_profit_pct:0.1}`
- **WHEN** `compile_strategy`
- **THEN** 不抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`

#### Scenario: ATR 止损价计算
- **GIVEN** 持仓 entry_price=10.0 + `atr_multiplier=2` + 当前 ATR(14)=0.3
- **WHEN** `on_before_trading`
- **THEN** stop_trigger = 10.0 − 2 × 0.3 = 9.4；take_profit 仍用静态 `entry_price × (1 + take_profit_pct)`

#### Scenario: use_atr_stop 与 stop_loss_pct 互斥
- **GIVEN** `use_atr_stop=true`（优先）
- **WHEN** 计算 stop_trigger
- **THEN** 用 ATR 公式；忽略 stop_loss_pct（即使同时设了）

#### Scenario: atr_multiplier 必填校验（编译期）
- **GIVEN** `use_atr_stop=true` 但 `atr_multiplier=null`
- **WHEN** `compile_strategy`（或 validator 层已拦截）
- **THEN** 抛 `ATR_MULTIPLIER_REQUIRED`（错误码已存在）

### Requirement: 前端编辑器解锁（005-strategy-visual-editor MODIFIED）

系统 SHALL 解锁 005 策略编辑器 Tab 6/7 的 Phase 1 占位，恢复 rebalance / exit.rules / use_atr_stop 的编辑交互。

#### Scenario: Tab 7 调仓解锁
- **GIVEN** editor.html Tab 7
- **WHEN** 渲染
- **THEN** `<fieldset>` 无 `disabled`；无「即将支持」alert；可编辑 frequency/replace_method/weight_mode/max_single_position/long_only

#### Scenario: Tab 6 ATR 解锁
- **GIVEN** editor.html Tab 6 ATR 区域
- **WHEN** 渲染
- **THEN** `f-use-atr-stop` 可勾选；勾选后 `f-atr-period` / `f-atr-multiplier` 可填；无 Phase 2 badge

#### Scenario: Tab 6 exit.rules 显示
- **GIVEN** editor.html Tab 6 exit.rules 区域
- **WHEN** 渲染
- **THEN** `f-exit-rules-fieldset` 可见（无 `display:none`）；可添加规则行（name + action + condition 树）

#### Scenario: JS 采集恢复
- **GIVEN** 用户在 Tab 6/7 填入 rebalance / ATR / exit.rules
- **WHEN** `collectStateFromForm`
- **THEN** 写入 `s.trading_config.rebalance` / `s.trading_config.exit.bracket.use_atr_stop` / `s.trading_config.exit.rules`（不再 delete / 注释）

#### Scenario: 常量动态拉取扩展
- **GIVEN** 前端初始化
- **WHEN** 从 `/api/backtest/constants-proxy` 拉取
- **THEN** `paradigms_supported` 含 `["signals+bracket","signals+atr_stop","rebalance","exit.rules","mixed"]`；前端据此决定是否解锁 Tab（不再硬编码 Phase 1 限制）

---

## MODIFIED Requirements

### Requirement: engine 常量接口 paradigms_supported

`GET /python/v1/backtest/constants` 的 `paradigms_supported` SHALL 从第一波的 `["signals+bracket"]` 扩展为 `["signals+bracket", "signals+atr_stop", "rebalance", "exit.rules", "mixed"]`，作为前端编辑器解锁的权威源。

#### Scenario: 常量扩展
- **WHEN** `GET /python/v1/backtest/constants`
- **THEN** `paradigms_supported` 含 5 项（signals+bracket / signals+atr_stop / rebalance / exit.rules / mixed）

### Requirement: 第一波范式校验放宽

watcher `BacktestServiceImpl.checkParadigmSupported` 与 engine `compiler._check_phase1_paradigm` SHALL 删除对 `rebalance` / `exit.rules` / `use_atr_stop` 的拒绝分支，仅保留 GRID/WF 模式拒绝（第三波）。

#### Scenario: rebalance 不再拒绝
- **GIVEN** config 含 `trading_config.rebalance`
- **WHEN** watcher + engine 双层校验
- **THEN** 均不抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`；正常进入回测流程

---

## REMOVED Requirements

### Requirement: GRID 网格寻优第二波实现
**Reason**：第三波范围。前置 OQ-3（paramGrid schema 反推）+ 结构化 DSL 设计未完成。
**Migration**：第二波 `mode=GRID` 仍返回 `BACKTEST_MODE_NOT_SUPPORTED`。

### Requirement: WALK_FORWARD 滚动验证第二波实现
**Reason**：第三波范围，需先有 GRID。
**Migration**：第二波 `mode=WALK_FORWARD` 仍返回 `BACKTEST_MODE_NOT_SUPPORTED`。

### Requirement: all_a_shares 全市场回测
**Reason**：HTTP 载荷（5000+ 标的 × N 日 OHLCV）+ engine 回测超时风险；性能边界待定。
**Migration**：第二波 `universe="all_a_shares"` 返回 `BACKTEST_UNIVERSE_TOO_LARGE`，引导用 csi300/csi500/manual。

### Requirement: 组合层风控（portfolio scope exit.rules）
**Reason**：`exit.rules` 当前仅 per_symbol；`eval_scope:"portfolio"` + ref 扩展 `portfolio_drawdown` 需 akquant 组合层 API 协作，第三波。
**Migration**：第二波 `exit.rules` 仅针对单标的持仓；组合层风控推迟。
