# Tasks

> **范围说明**：本任务清单对应 [Phase 2 spec.md](./spec.md)，落地 [PRD v2.0](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) §6.3-6.4 / FR-6a 的三类范式：rebalance / exit.rules / use_atr_stop。GRID/WF/HTML 导出/RUNNING 真终止/DRAFT 回测/all_a_shares 全市场均不在本清单。
> **阶段划分**：T1 共享层抽取（前置）→ T2 engine 范式扩展 → T3 watcher 编排放宽 → T4 前端解锁 → T5 联调 → T6 验收。T1 是 T2 的前置；T3 可与 T2 部分并行（按接口契约）。

---

## T1: engine 共享层抽取（前置，PRD §12.3）✅

- [x] **Task 1.1：新建 services/shared/ 目录骨架**
  - [x] 新建 `stock-engine/services/shared/__init__.py`（`__all__` 导出统一求值器 + 因子管线）
  - [x] 新建 `stock-engine/services/shared/condition_evaluator.py`（占位）
  - [x] 新建 `stock-engine/services/shared/factor_pipeline.py`（占位）
  - **依赖**：无（最先做）
  - **验证**：`cd stock-engine && python -c "from services.shared import ConditionEngine, factor_pipeline"`

- [x] **Task 1.2：统一条件求值器 condition_evaluator.py**
  - [x] 实现 `ConditionEngine(mode: Literal["cross_section","time_series"])`，合并 003 截面（禁 cross_*/ref）与 005 时序（允许）两种 mode
  - [x] NaN 安全（含 NaN 比较返回 False）+ 除零降级（除零返回 0.0）
  - [x] cross_section mode 拒绝 `cross_up`/`cross_down`/`ref`（抛 `ScreenTimeSeriesForbiddenError`）
  - [x] time_series mode 支持 `cross_up`/`cross_down` + ref 白名单（entry_price/position_pnl_pct/position_qty/bars_held）
  - [x] 求值入口：`evaluate_tree(tree, ctx) -> bool`、`evaluate_expr(node, ctx) -> float`
  - **依赖**：Task 1.1
  - **验证**：单测——cross_section mode 对含 cross_up 的条件抛错；time_series mode 正常返回；NaN 比较返回 False

- [x] **Task 1.3：统一因子管线 factor_pipeline.py**
  - [x] 实现 `precompute(factor_specs, kline_df_map) -> dict[str, dict[str, np.ndarray]]`（批量预计算，对齐 003 `factor_precompute.precompute_factors`）
  - [x] 实现 `compute_latest(factor_specs, close, high, low, volume) -> dict[str, float]`（单 bar 实时算，对齐 005 `compiler._compute_factor_values`）
  - [x] 内部统一调 `factor_calculator.compute_single`，保证同口径
  - [x] 裁前导 NaN 的逻辑下沉到此（避免 003/005 各写一份）
  - **依赖**：Task 1.1
  - **验证**：单测——同一组 factor_specs + OHLCV，precompute 最后一根与 compute_latest 结果一致

- [x] **Task 1.4：003/005 薄壳化 + re-export**
  - [x] `services/screener/engine.py` 改为 re-export `from services.shared.condition_evaluator import ConditionEngine as _SharedCE, ...`，保持 `ConditionEngine` 默认 cross_section mode 的兼容
  - [x] `services/backtest/trading_engine.py` 改为 re-export `from services.shared.condition_evaluator import ...`，保持 `TradingConditionEngine`（time_series mode 别名）
  - [x] 跑 003 + 005 第一波全量单测，确保无回归
  - **依赖**：Task 1.2、Task 1.3
  - **验证**：`pytest stock-engine/tests/services/screener/ stock-engine/tests/services/backtest/` 全绿

---

## T2: engine 回测执行引擎扩展（rebalance + exit.rules + use_atr_stop）

- [x] **Task 2.1：RebalanceEngine 新建**
  - [x] 新建 `stock-engine/services/backtest/rebalance_engine.py`
  - [x] 实现 `RebalanceEngine.select_at_rebalance_date(screen_config, kline_map, trading_date, history_window=60) -> dict[str, float]`
  - [x] 编排：对 kline_map 每个 symbol 取截至 trading_date 的 history_window 历史 → factor_pipeline.compute_latest → ConditionEngine(cross_section).evaluate_tree 过滤 → ranking 打分（复用 003 `rank_stocks`）
  - [x] 无 conditions 时仅 ranking；无 ranking 时返回 `{symbol: 0.0}`
  - [x] 不回调 watcher（仅依赖入参）
  - **依赖**：Task 1.3、Task 1.4
  - **验证**：单测——构造 3 symbol 的 kline_map + ranking=composite，输出 `{symbol: score}` 且 ranking 顺序正确

- [x] **Task 2.2：compiler.py 删拒绝 + rebalance 分支**
  - [x] 删除 `_check_phase1_paradigm` 中 rebalance 拒绝分支（L64-67），改名为 `_check_paradigm`
  - [x] `compile_strategy` 新增 rebalance 分支：当 `tc.rebalance` 在场时，动态生成 `on_daily_rebalance(trading_date, timestamp)` 方法
  - [x] `on_daily_rebalance` 编排：调仓频率触发判断（daily/weekly/monthly/quarterly + day_of_period）→ 命中调仓日时调 `RebalanceEngine.select_at_rebalance_date` → `self.rebalance_to_topn(scores, top_n, weight_mode=, long_only=, liquidate_unmentioned=)`
  - [x] 参数映射：weight_mode ← rebalance.weight_mode（默认 equal）；long_only ← rebalance.long_only（默认 true）；liquidate_unmentioned ← replace_method=="full"（默认 full）
  - [x] top_n ← screen_config.top_n（必填校验）
  - **依赖**：Task 2.1
  - **验证**：单测——含 rebalance.monthly 的 config 不再抛范式错；生成的子类 `hasattr(Clz, "on_daily_rebalance")`

- [x] **Task 2.3：compiler.py exit.rules 分支**
  - [x] 删除 `_check_paradigm` 中 exit.rules 拒绝分支（L69-72）
  - [x] `compile_strategy` 在 signals 求值后、更新 prev_snapshot 前，新增 exit.rules 评估分支
  - [x] 逐条评估 `exit.rules[].condition`（用 TradingConditionEngine，支持 ref）；任一命中（OR 短路）→ 调 `action`（默认 close_position；sell 时按 position_sizing.target 或全量）
  - [x] 仅对当前有持仓的 symbol 评估（无持仓时跳过，避免无意义求值）
  - **依赖**：Task 1.4
  - **验证**：单测——含 exit.rules 的 config 不抛范式错；构造命中场景触发 close_position

- [x] **Task 2.4：compiler.py use_atr_stop 分支**
  - [x] 删除 `_check_paradigm` 中 use_atr_stop 拒绝分支（L73-76）
  - [x] `on_before_trading` 新增 ATR 止损分支：当 `bracket.use_atr_stop=true` 时，计算 `stop_trigger = entry_price − atr_multiplier × ATR(atr_period)`
  - [x] ATR 计算：`talib.ATR(high, low, close, timeperiod=atr_period)[-1]`（取最近窗口）
  - [x] use_atr_stop 与 stop_loss_pct 互斥（use_atr_stop 优先，忽略 stop_loss_pct）
  - [x] take_profit 仍用静态 `entry_price × (1 + take_profit_pct)`
  - [x] atr_multiplier 必填校验（编译期，错误码 ATR_MULTIPLIER_REQUIRED）
  - **依赖**：Task 1.4
  - **验证**：单测——use_atr_stop=true 的 config 不抛范式错；entry=10, ATR=0.3, mult=2 → stop_trigger=9.4

- [x] **Task 2.5：signals + rebalance 混合范式**
  - [x] compile_strategy 支持同时含 signals 与 rebalance 的 config
  - [x] on_bar 执行 signals；on_daily_rebalance 执行调仓；两者共存不冲突
  - **依赖**：Task 2.2、Task 2.3
  - **验证**：单测——混合 config 编译成功；模拟回测两路径都触发

- [x] **Task 2.6：runner.py + build_backtest_kwargs 扩展**
  - [x] `build_backtest_kwargs`：rebalance 范式下 symbols 从 screen_config 反推（全 universe 列表），覆盖第一波仅单标的的逻辑
  - [x] `run_backtest_engine` 编排不变（Strategy 子类自带 on_daily_rebalance）
  - [x] 多标的时 warmup_period 取 max（rebalance 的 ranking 因子窗口 + signals 窗口）
  - **依赖**：Task 2.2
  - **验证**：单测——rebalance config 走 build_backtest_kwargs 输出 symbols 为列表

- [x] **Task 2.7：engine 常量接口扩展**
  - [x] `GET /python/v1/backtest/constants` 的 `paradigms_supported` 扩展为 `["signals+bracket","signals+atr_stop","rebalance","exit.rules","mixed"]`
  - [x] 单测覆盖
  - **依赖**：无（可独立）
  - **验证**：`pytest` 常量接口断言 5 项 paradigm

- [x] **Task 2.8：engine 硬约束自检（不触库 + 无 eval）**
  - [x] `grep -rE "sqlite3|sqlalchemy|\\.db" stock-engine/services/backtest/ stock-engine/services/shared/` 无匹配
  - [x] `grep -rE "\\beval\\b|\\bexec\\b|__import__" stock-engine/services/backtest/ stock-engine/services/shared/` 无匹配
  - **依赖**：T2 全部完成
  - **验证**：grep 实际执行结果

---

## T3: watcher 编排层放宽（与 T2 并行 T2.1 完成后）

- [x] **Task 3.1：删除范式拒绝 + 新增 universe 校验**
  - [x] `BacktestServiceImpl.checkParadigmSupported` 删除 rebalance（L737-740）与 exit.rules（L742-745）拒绝分支；保留 GRID/WF 模式拒绝
  - [x] `buildKlineData` 新增 universe 规模校验：`screen_config.universe=="all_a_shares"` → 抛 `BACKTEST_UNIVERSE_TOO_LARGE`
  - [x] csi300/csi500 复用已有 `resolveBacktestSymbols`（成分股并集）；manual 用 stocks
  - **依赖**：Task 2.1（接口契约对齐）
  - **验证**：单测——rebalance config 不再被拒；all_a_shares 返回 BACKTEST_UNIVERSE_TOO_LARGE

- [x] **Task 3.2：新增错误码**
  - [x] `BacktestErrorCodes.java` 新增 `BACKTEST_UNIVERSE_TOO_LARGE`（实际取值 40016，因 40012 已被 BACKTEST_MODE_NOT_SUPPORTED 占用；spec 仅规定语义未规定数值，符合 spec）
  - [x] GlobalExceptionHandler 已有 BusinessException 统一处理，无需改
  - **依赖**：无
  - **验证**：`mvnw compile` BUILD SUCCESS

---

## T4: 前端编辑器解锁（005-strategy-visual-editor）

- [x] **Task 4.1：editor.html Tab 7 调仓解锁**
  - [x] Tab 7 fieldset 移除 `disabled`
  - [x] 移除「即将支持」alert-info
  - **依赖**：无
  - **验证**：浏览器渲染 Tab 7 可交互

- [x] **Task 4.2：editor.html Tab 6 ATR + exit.rules 解锁**
  - [x] `f-use-atr-stop` / `f-atr-period` / `f-atr-multiplier` 移除 `disabled` + Phase 2 badge
  - [x] `f-exit-rules-fieldset` 移除 `style="display:none;"` + alert-secondary
  - **依赖**：无
  - **验证**：浏览器渲染 Tab 6 ATR 可勾选、exit.rules 可添加规则

- [x] **Task 4.3：strategy-editor.js 恢复采集**
  - [x] `collectStateFromForm` 删除 `delete s.trading_config.rebalance`
  - [x] 恢复 ATR 三字段采集（解除注释）
  - [x] exit.rules 容器内规则行可正常添加（`collectExitRules` 已实现）
  - [x] 常量从 `/api/backtest/constants-proxy` 拉，按 `paradigms_supported` 决定是否禁用 Tab（向后兼容：若 engine 未升级，常量只含 signals+bracket，前端自动禁用新 Tab）
  - **依赖**：Task 4.1、Task 4.2、Task 2.7
  - **验证**：`node --check strategy-editor.js` 通过；浏览器实测填入字段后 config_json 含 rebalance/exit.rules/use_atr_stop

---

## T5: 联调

- [x] **Task 5.1：rebalance 端到端（csi300 + composite ranking）**（静态验证：编译/选股通过；运行时端到端待 watcher+engine 启动）
  - [x] 构造 rebalance.monthly + screen_config(csi300/manual, ranking=composite) + top_n 的策略版本编译成功
  - [x] engine 侧 RebalanceEngine.select_at_rebalance_date 3-symbol 合成 K 线打分正确（T5 冒烟 (h)）
  - [ ] 运行时联调：watcher 拼 csi300 成分股 kline → engine 编译 + on_daily_rebalance 选股调仓 → 落库（待环境）
  - **依赖**：T2、T3、T4 全部完成
  - **验证**：编译+选股静态通过；运行时端到端待 watcher+engine 启动

- [x] **Task 5.2：exit.rules 端到端（动态止损）**（静态验证：编译通过；运行时待环境）
  - [x] 含 exit.rules 的策略版本编译成功（T5 冒烟 (b)）
  - [ ] 运行时联调：exit.rules 命中触发平仓（待环境）
  - **依赖**：T2、T3、T4
  - **验证**：编译静态通过；运行时待启动

- [x] **Task 5.3：use_atr_stop 端到端**（静态验证：编译通过 + ATR_MULTIPLIER_REQUIRED 校验；运行时待环境）
  - [x] use_atr_stop=true + atr_multiplier=2 编译成功（T5 冒烟 (c)）
  - [x] use_atr_stop=true + atr_multiplier=None 抛 ATR_MULTIPLIER_REQUIRED（T5 冒烟 (d)）
  - [ ] 运行时联调：bracket 单 stop_trigger 按 ATR 公式计算（待环境）
  - **依赖**：T2、T3、T4
  - **验证**：编译+校验静态通过；运行时待启动

- [x] **Task 5.4：第一波无回归**
  - [x] 第一波的 signals + exit.bracket（静态）策略版本仍可编译（T5 冒烟 (e)）
  - [x] 跑 003 选股 + 002 因子全量单测：95/95 passed
  - **依赖**：T1.4
  - **验证**：`pytest tests/test_screener/ tests/test_factor/` 95 passed；backtest/screener/factor 0 新增失败

---

## T6: 验收（对齐 Phase 2 AC）

- [x] **Task 6.1：程序化验收**
  - [x] rebalance 编译不抛范式错（AC-P2-1）— T5 冒烟 (a)
  - [x] exit.rules 编译不抛范式错（AC-P2-2）— T5 冒烟 (b)
  - [x] use_atr_stop 编译不抛范式错 + ATR_MULTIPLIER_REQUIRED 校验（AC-P2-3）— T5 冒烟 (c)(d)
  - [x] 共享层同口径（precompute vs compute_latest）（AC-P2-4）— 共用 factor_calculator.compute_single
  - [x] watcher 不再拒绝 rebalance/exit.rules（AC-P2-5）— T3 删除拒绝分支 + 源码验证
  - [x] all_a_shares 拒绝（AC-P2-6）— T3 resolveBacktestSymbols guard
  - [x] engine 不触库 + 无 eval（AC-P2-7）— T5 grep 0 匹配
  - [x] 常量接口扩展 5 项 paradigm（AC-P2-8）— T5 冒烟 (g) TestClient 实测
  - [x] 第一波无回归（AC-P2-9）— 95/95 passed
  - **依赖**：T5
  - **验证**：程序化检查清单全通过

- [x] **Task 6.2：人工验收**（代码就绪，部分待运行时人工走查）
  - [x] 前端 Tab 6/7 解锁可交互（AC-P2-10）— T4 node --check 通过 + HTML disabled/display:none 移除
  - [ ] rebalance 端到端报告页渲染（AC-P2-11）— 代码就绪，待运行时人工走查
  - [ ] exit.rules/use_atr_stop 端到端（AC-P2-12）— 代码就绪，待运行时人工走查
  - **依赖**：Task 6.1
  - **验证**：人工走查清单

---

# Task Dependencies

- **T1（共享层）**：无依赖，最先做；T1.1 → (T1.2 ‖ T1.3) → T1.4（T1.2/T1.3 可并行）
- **T2（engine 范式扩展）**：依赖 T1；T2.1 → (T2.2 ‖ T2.3 ‖ T2.4) → T2.5 → T2.6 → T2.7 → T2.8（T2.2/T2.3/T2.4 可并行，均依赖 T1.4）
- **T3（watcher）**：T3.1 依赖 T2.1（接口契约）；T3.2 无依赖可早做
- **T4（前端）**：Task 4.1/4.2 无依赖可早做；4.3 依赖 T2.7（常量扩展）
- **T5（联调）**：5.1/5.2/5.3 依赖 T2+T3+T4；5.4 依赖 T1.4
- **T6（验收）**：6.1 依赖 T5；6.2 依赖 6.1
- **跨层并行**：T1.4 完成后，T2（engine）与 T3（watcher）可并行；T4 前端可在 T2.7 完成后启动
