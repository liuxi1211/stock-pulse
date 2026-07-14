# 策略范式互斥改造 Spec

> 对齐 PRD：`sdlc/prd/006-策略范式治理/策略范式互斥改造PRD.md` v1.0
> change-id：`009-strategy-paradigm-exclusive`
> akquant 0.2.47 · 统一策略配置 Schema v1.0

## Why

当前 stock-engine / stock-watcher 封装层允许 `trading_config.signals` 与 `trading_config.rebalance` 同时在场（"混合范式"），存在两个阻断缺陷：

- **缺陷 A（订单叠加）**：akquant 同一 bar 内 `on_daily_rebalance` 先于 `on_bar` 执行，两者订单进入同一撮合队列合计，目标仓位类订单会叠加导致仓位失控甚至触发杠杆（akquant 既定撮合机制，非 bug）。
- **缺陷 B（资金争抢）**：signals 范式对每个命中 symbol 独立调用 `order_target_percent(0.95)`，多标的 universe 下首只吃光资金、其余整单 Reject，且回测结果不可复现。

akquant 框架本身设计完备，问题全部在封装层。本次只动 stock-engine + stock-watcher + 前端，不改 akquant 源码。

## What Changes

- **范式互斥**：`signals` 与 `rebalance` 校验期/编译期互斥，二选一。
- **universe 规模约束**：signals 范式 universe 固定为 `manual` 且 ≤ 10 只。
- **scope 枚举清理**：移除 `mixed`，仅保留 `single`（signals）/ `portfolio`（rebalance）。
- **前端范式切换**：编辑器引入范式单选控件，按范式显隐 Tab。
- **全链路清理**：错误码、常量下发、模板加载器、列表/回测页、文档、akquant rules 全部清理 mixed 残留。
- **存量清零**：`quant_strategy` + `quant_strategy_version` 全清，不做兼容迁移。**BREAKING**。

## Impact

- **Affected specs**：统一策略配置 Schema（§2.2/§3.3）；akquant rules 03-strategy-api.md。
- **Affected code**（按 PRD §6）：
  - engine：`services/strategy/{errors,validator,constants}.py`、`services/backtest/compiler.py`、`api/v1/backtest.py`、`tests/services/strategy/test_validator.py`
  - watcher：`StrategyServiceImpl.java`、`StrategyScopeEnum.java`、`BacktestServiceImpl.java`、`BacktestErrorCodes.java`、`StrategySchemaConstants.java`、`StrategyTemplateLoader.java`、`ConstantController.java`、DTO 注释
  - 前端：`editor.html`、`strategy-editor.js`、`strategy-list.js`、`backtest-new.js`
  - 文档：`sdlc/prd/004-策略管理/统一策略配置Schema.md`、`.trae/rules/akquant/03-strategy-api.md`
- 不影响：003 选股中心 `screen_config` 消费链路；rebalance 范式执行逻辑（`rebalance_engine.py` / `_attach_rebalance_method`）。

## ADDED Requirements

### Requirement: 范式互斥校验

系统 SHALL 在策略配置校验阶段拒绝 `signals` 与 `rebalance` 同时在场的配置。

#### Scenario: 两者同时在场
- **WHEN** 提交的配置 `trading_config.signals` 与 `trading_config.rebalance` 均非 null
- **THEN** engine `/validate` 返回 `valid:false`，错误码 `SIGNALS_REBALANCE_EXCLUSIVE`（HTTP 200 + errors 数组；PRD §5 AC-1 所述"422"指校验失败语义，实际路由契约为 200+valid:false）

#### Scenario: 两者均不在场（已存在）
- **WHEN** 两者均缺失
- **THEN** 返回 `MISSING_SIGNALS_OR_REBALANCE`（保持现状）

### Requirement: signals 范式 universe 规模约束

系统 SHALL 限制 signals 范式的 universe 为 `manual` 且标的数 ≤ `SIGNALS_MAX_UNIVERSE_SIZE`（=10）。

#### Scenario: signals + 非 manual universe
- **WHEN** signals 在场且 `screen_config.universe ∈ {csi300, csi500, all_a_shares}`
- **THEN** 返回错误码 `SIGNALS_UNIVERSE_NOT_MANUAL`

#### Scenario: signals + manual 超限
- **WHEN** signals 在场、universe=manual、stocks 数量 > 10
- **THEN** 返回错误码 `SIGNALS_UNIVERSE_TOO_LARGE`

#### Scenario: signals + manual 合法
- **WHEN** signals 在场、universe=manual、stocks ≤ 10
- **THEN** 校验通过，回测正常执行

### Requirement: compiler 编译期兜底

系统 SHALL 在 `compile_strategy` 解析 signals/rebalance 后，对"两者同时在场"二次拦截（validator 已拦截，���处为防止绕过 validator 直接调 runner）。

#### Scenario: 绕过 validator 提交
- **WHEN** runner 直接收到 signals+rebalance 共存配置
- **THEN** 抛 `CompilerError("SIGNALS_REBALANCE_EXCLUSIVE...")`

### Requirement: watcher 回测侧 universe 二次校验

系统 SHALL 在 watcher `BacktestServiceImpl.resolveBacktestSymbols` 对 signals 范式独立校验 universe（engine 之外的第二道防线）。

#### Scenario: signals + csi300 经 watcher 发起回测
- **WHEN** watcher 收到 signals + csi300 的回测请求
- **THEN** 抛 `BusinessException(SIGNALS_UNIVERSE_NOT_MANUAL)`

### Requirement: 前端范式切换 UI

系统 SHALL 在策略编辑器提供范式单选（择时 signals / 轮动 rebalance），并按范式显隐 Tab、约束选股范围。

#### Scenario: 选中择时范式
- **WHEN** 用户在编辑器选"择时范式"
- **THEN** 显示 Tab2（限定 manual ≤10）/Tab3/Tab4/Tab5/Tab6/Tab8，隐藏 Tab7（调仓）

#### Scenario: 选中轮动范式
- **WHEN** 用户选"轮动范式"
- **THEN** 显示 Tab2（全量 universe）/Tab7/Tab8，隐藏 Tab3/4/5/6

## MODIFIED Requirements

### Requirement: scope 枚举

`StrategyScopeEnum` 移除 `MIXED`，仅保留 `SINGLE`（signals）/ `PORTFOLIO`（rebalance）。`deriveScope` / `deriveScopeFromConfig` 同步删除 mixed 分支，二者同时在场由 validator 拦截（理论上不可达，watcher 侧防御性取 signals 优先）。

### Requirement: 配置收集（前端）

`collectConfig` 按当前范式二选一生成 `trading_config`：signals 范式仅生成 signals/position_sizing/exit；rebalance 范式仅生成 rebalance。`collectRebalance` 改为仅在轮动范式返回对象。`buildSummary` 删除"混合策略"分支。

### Requirement: 常量下发与路由

- `api/v1/backtest.py` `paradigms_supported` 改为 `["signals","rebalance"]`。
- `ConstantController` 下发 `strategies.scopes` 不含 mixed，新增下发 `signals_max_universe_size=10`。

## REMOVED Requirements

### Requirement: 混合范式（mixed）
**Reason**：akquant 撮合机制下混合范式订单叠加、仓位失控；多标的横截面应走 rebalance 范式。
**Migration**：不做兼容。存量 `quant_strategy` + `quant_strategy_version` 全清（TRUNCATE/DELETE），重启后由模板加载器重载 5 个内置模板（3 signals + 2 rebalance，无 mixed）。

## 验收（摘要，详见 checklist.md）

AC-1~AC-12 见 PRD §5，覆盖：互斥拒绝、universe 三类拒绝、合法 signals/rebalance 通过、前端范式切换、scope 枚举清理、存量清零、模板与选股中心回归。
