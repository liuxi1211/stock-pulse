# Tasks

> change-id: `009-strategy-paradigm-exclusive`
> 优先级 P0 → P1 → P2 → 测试 → 存量数据。无依赖任务可并行。

## P0 engine 侧（阻断缺陷）

- [x] Task 1: 新增 3 个错误码（engine）
  - [x] SubTask 1.1: 在 `stock-engine/services/strategy/errors.py` 的 `ErrorCode` 中新增 `SIGNALS_REBALANCE_EXCLUSIVE` / `SIGNALS_UNIVERSE_TOO_LARGE` / `SIGNALS_UNIVERSE_NOT_MANUAL` 三条 `(code, message)` 元组（message 文案见 PRD §3.1.1，`SIGNALS_UNIVERSE_TOO_LARGE` 用占位 `{max}`/`{actual}` 由校验器 format）
- [x] Task 2: 新增 universe 规模常量（engine）
  - [x] SubTask 2.1: 在 `stock-engine/services/strategy/constants.py` 新增 `SIGNALS_MAX_UNIVERSE_SIZE = 10`
- [x] Task 3: validator 加互斥 + universe 校验（engine）
  - [x] SubTask 3.1: 修改 `_validate_structure_trading`：现有"都缺失"分支后追加 `signals` 与 `rebalance` 同时在场 → `SIGNALS_REBALANCE_EXCLUSIVE`（追加后 return，不再继续 position_sizing/exit 校验）
  - [x] SubTask 3.2: 新增 `_validate_signals_universe(config, errors)`：当 `tc.signals is not None` 时校验 `screen_config.universe == "manual"`（否则 `SIGNALS_UNIVERSE_NOT_MANUAL`）与 `len(screen_config.stocks) <= SIGNALS_MAX_UNIVERSE_SIZE`（否则 `SIGNALS_UNIVERSE_TOO_LARGE`，message 用实际数量 format）
  - [x] SubTask 3.3: 在 `validate` 入口结构校验通过后调用 `_validate_signals_universe`（注意 `screen_config` 为 None 时的兼容）

## P0 watcher 侧

- [x] Task 4: scope 枚举清理（watcher）
  - [x] SubTask 4.1: `StrategyScopeEnum.java` 移除 `MIXED("mixed","混合")` 枚举值
- [x] Task 5: deriveScope 删 mixed 分支（watcher）
  - [x] SubTask 5.1: `StrategyServiceImpl.deriveScope` 删除 `hasSignals && hasRebalance → "mixed"` 分支，改为 signals 优先返回 single，否则 portfolio/single（PRD §3.2.1 代码示例）
- [x] Task 6: BacktestErrorCodes 新增（watcher）
  - [x] SubTask 6.1: `BacktestErrorCodes.java` 新增 `SIGNALS_UNIVERSE_NOT_MANUAL` / `SIGNALS_UNIVERSE_TOO_LARGE` / `SIGNALS_REBALANCE_EXCLUSIVE`（沿用 4xxxx 段，避免与现有码冲突，选 40017/40018/40019）
- [x] Task 7: StrategySchemaConstants 加上限（watcher）
  - [x] SubTask 7.1: `StrategySchemaConstants.java` 新增 `SIGNALS_MAX_UNIVERSE_SIZE = 10`
- [x] Task 8: resolveBacktestSymbols 加 signals 校验（watcher）
  - [x] SubTask 8.1: `BacktestServiceImpl.resolveBacktestSymbols` 在 `all_a_shares` 拒绝之后，新增：signals 在场且 universe != manual → `SIGNALS_UNIVERSE_NOT_MANUAL`；signals 在场且 symbols.size() > SIGNALS_MAX_UNIVERSE_SIZE → `SIGNALS_UNIVERSE_TOO_LARGE`（PRD §3.2.4）
  - [x] SubTask 8.2: 提取 `hasSignals(JSONObject configJson)` 辅助方法（与 `StrategyServiceImpl` 对齐），避免重复实现

## P0 前端

- [x] Task 9: 编辑器范式切换控件（前端）
  - [x] SubTask 9.1: `templates/quant/strategies/editor.html` Tab1 增加 segmented control（择时 signals / 轮动 rebalance），默认择时范式
  - [x] SubTask 9.2: 范式变化时显隐 Tab：signals → 显示 Tab2/3/4/5/6/8 隐藏 Tab7；rebalance → 显示 Tab2/7/8 隐藏 Tab3/4/5/6
  - [x] SubTask 9.3: Tab2 universe 下拉按范式联动：signals 仅 manual 且显示"已选 X/10"；rebalance 可选 csi300/csi500/manual
- [x] Task 10: collectConfig 二选一（前端）
  - [x] SubTask 10.1: `strategy-editor.js` `state` 增加 `paradigm` 字段，与控件双向绑定
  - [x] SubTask 10.2: `collectConfig` 按范式分支：signals 仅生成 signals/position_sizing/exit；rebalance 仅生成 rebalance（PRD §3.3.2 代码示例）
  - [x] SubTask 10.3: `collectRebalance` 改为仅在轮动范式���回对象，否则 null
  - [x] SubTask 10.4: `buildSummary` 删除 `hasSignals && hasRebalance → "混合策略"` 分支

## P1 清理残留

- [x] Task 11: compiler 编译期兜底（engine）
  - [x] SubTask 11.1: `compiler.py` `compile_strategy` 在解析 signals/rebalance（L433-441）后追加 `if has_signals and has_rebalance: raise CompilerError("SIGNALS_REBALANCE_EXCLUSIVE...")`
  - [x] SubTask 11.2: `_check_paradigm` 从 no-op 升级为实际校验入口（调用上述逻辑），保留签名兼容
- [x] Task 12: 回测路由常量清理（engine）
  - [x] SubTask 12.1: `api/v1/backtest.py` `paradigms_supported` 改为 `["signals","rebalance"]`
- [x] Task 13: 模板加载器同步（watcher）
  - [x] SubTask 13.1: `StrategyTemplateLoader.deriveScopeFromConfig` 删 mixed 分支，逻辑与 Task 5 一致
- [x] Task 14: 常量下发清理（watcher）
  - [x] SubTask 14.1: `ConstantController.registerStrategyConstants`：`strategies.scopes` 自然不含 mixed（因 Task 4 删了枚举）；新增下发 `strategies.signalsMaxUniverseSize = 10`
- [x] Task 15: 列表页清理（前端）
  - [x] SubTask 15.1: `strategy-list.js` `SCOPE_LABEL` 移除 `mixed: 'MIXED'`
- [x] Task 16: 回测新建页清理（前端）
  - [x] SubTask 16.1: `backtest-new.js` L167-173 `paradigmsSupported` 处理移除 mixed
  - [x] SubTask 16.2: L277 `mode: 'signals'` 硬编码改为按策略 config 的范式动态填充

## P2 注释/展示清理

- [x] Task 17: DTO 注释清理（watcher）
  - [x] SubTask 17.1: `StrategyDTO.java` L36 scope 注释更新（去掉"混合"）
  - [x] SubTask 17.2: `StrategyTemplateDTO.java` 同步

## 文档

- [x] Task 18: Schema 文档对齐
  - [x] SubTask 18.1: `sdlc/prd/004-策略管理/统一策略配置Schema.md` §2.2 表格改为互斥（PRD §3.4.1）
  - [x] SubTask 18.2: §3.3 追加 signals 范式 universe=manual 且 stocks ≤ 10 的说明
- [x] Task 19: akquant rules 补充
  - [x] SubTask 19.1: `.trae/rules/akquant/03-strategy-api.md` 在 §11 后追加"范式选型约束"小节（PRD §3.4.2）

## 测试

- [x] Task 20: engine validator 单测
  - [x] SubTask 20.1: `tests/services/strategy/test_validator.py` 新增：`test_signals_and_rebalance_both_present_rejected` / `test_signals_universe_csi300_rejected` / `test_signals_universe_manual_over_limit_rejected` / `test_signals_universe_manual_within_limit_ok`
- [x] Task 21: engine 端到端单测
  - [x] SubTask 21.1: `tests/services/strategy/test_api.py` 新增 signals+rebalance 共存 → 422 `SIGNALS_REBALANCE_EXCLUSIVE` 用例
- [x] Task 22: watcher 单测
  - [x] SubTask 22.1: `StrategyServiceImplTest.java` 新增 `deriveScope` 单测（signals-only → single；rebalance-only → portfolio；都不在 → single；共存 → single（防御性））

## 存量数据

- [ ] Task 23: 存量清零（部署动作，不在代码内）
  - [ ] SubTask 23.1: 部署前执行 `TRUNCATE TABLE quant_strategy; TRUNCATE TABLE quant_strategy_version;`（或 DELETE），重启 watcher 触发模板重载

# Task Dependencies

- Task 1 → Task 3（validator 引用新错误码）
- Task 2 → Task 3
- Task 4 → Task 5 / Task 13 / Task 14（删枚举后下游清理）
- Task 6 / Task 7 → Task 8（resolveBacktestSymbols 引用新码与常量）
- Task 9 → Task 10（前端 collectConfig 依赖范式 state）
- Task 3 → Task 20 / Task 21（测试依赖校验逻辑落地）
- Task 5 → Task 22
- P0 全部完成并回归通过（AC-10/11/12）后再执行 Task 23 存量清零
