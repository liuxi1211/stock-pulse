# Checklist

> **对齐**：[Phase 2 spec.md](./spec.md) ADDED/MODIFIED Requirements + [PRD v2.0](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) §6.3-6.4 / FR-6a。
> **用法**：实施完成后逐条勾选；任一失败���在 `tasks.md` 新增修复任务并重新验证。
> **程序化验证须有可重放命令**；人工验证记录走查结果。

---

## 共享层抽取（T1）✅

- [x] `stock-engine/services/shared/` 目录创建，含 `__init__.py`/`condition_evaluator.py`/`factor_pipeline.py`
- [x] `UnifiedConditionEngine(mode="cross_section"|"time_series")` 统一求值器实现（门面 + 工厂 `create_condition_engine`），委派 003 截面与 005 时序底层引擎
- [x] cross_section mode 拒绝 `cross_up`/`cross_down`/`ref`（抛 `ScreenTimeSeriesForbiddenError`，复用 003 行为）
- [x] time_series mode 支持 `cross_up`/`cross_down` + ref 白名单（entry_price/position_pnl_pct/position_qty/bars_held，复用 005 行为）
- [x] NaN 安全（含 NaN 比较返回 False）+ 除零降级（除零返回 0.0，复用底层引擎）
- [x] `factor_pipeline.precompute`（委派 003 `precompute_factors`���与 `factor_pipeline.compute_latest`（单 bar，从 005 compiler 抽取）同口径（都走 `factor_calculator.compute_single`）
- [x] 裁前导 NaN 逻辑下沉到 factor_pipeline（`trim_leading_nan` 工具）
- [x] `services/screener/engine.py` 保留原实现（向后兼容），docstring 指向共享���
- [x] `services/backtest/trading_engine.py` 保留原实现（向后兼容），docstring 指向共享层
- [x] **AC-P2-4 同口径验证**：共享层两个入口都走 `factor_calculator.compute_single`，保证同口径
- [x] **AC-P2-9 第一波无回归**：`pytest tests/test_screener/ tests/test_factor/` 95 passed
- [x] **AC-P2-7a 共享层不触库**：`grep -rE "sqlite3|sqlalchemy|\.db" stock-engine/services/shared/` 无匹配

## engine 回测引擎扩展（T2）✅

### rebalance 多因子调仓

- [x] `stock-engine/services/backtest/rebalance_engine.py` 新建，`RebalanceEngine.select_at_rebalance_date` 实现
- [x] select 编排：factor_pipeline.compute_latest + ConditionEngine(cross_section) + rank_stocks
- [x] 无 conditions 仅 ranking；无 ranking 返回 `{symbol: 0.0}`
- [x] 不回调 watcher（仅依赖入参 kline_map + screen_config）
- [x] `compiler.py` 删除 rebalance 拒绝分支（原 L64-67），`_check_phase1_paradigm` 改名 `_check_paradigm`
- [x] `compile_strategy` rebalance 分支：通过 `_attach_rebalance_method` 生成 `on_daily_rebalance(trading_date, timestamp)` 方法
- [x] 调仓频率触发判断：daily/weekly(monthly/quarterly 用 day<=7 首周启发式 + day_of_period 精确日，spec 接受）
- [x] 命中调仓日 → RebalanceEngine.select → `self.rebalance_to_topn(scores, top_n, weight_mode=, long_only=, liquidate_unmentioned=)`
- [x] 参数映射：weight_mode ← rebalance.weight_mode（默认 equal）；long_only ← rebalance.long_only（默认 true）；liquidate_unmentioned ← replace_method=="full"
- [x] top_n ← screen_config.top_n（必填校验）
- [x] warmup 合并：`_infer_rebalance_warmup` 扫描 screen_config.conditions + ranking 因子窗口取 max

### exit.rules 动态出场

- [x] `compiler.py` 删除 exit.rules 拒绝分支（原 L69-72）
- [x] `on_bar` 新增 exit.rules 评估分支（signals 求值后、更新 prev_snapshot 前）
- [x] 逐条评估 `rules[].condition`（TradingConditionEngine，支持 ref）；OR 短路（`_eval_exit_rules`）
- [x] 命中 → 调 `action`（默认 close_position；sell 按 position_sizing.target，`_dispatch_exit_action`）
- [x] 仅对当前有持仓的 symbol 评估（无持仓跳过）

### use_atr_stop ATR 动态止损

- [x] `compiler.py` 删除 use_atr_stop 拒绝分支（原 L73-76）
- [x] `on_before_trading` ATR 止损分支：`stop_trigger = entry_price − atr_multiplier × ATR(atr_period)`
- [x] ATR 计算：`talib.ATR(high, low, close, timeperiod=atr_period)[-1]`
- [x] use_atr_stop 与 stop_loss_pct 互斥（use_atr_stop 优先）
- [x] take_profit 仍用静态 `entry_price × (1 + take_profit_pct)`
- [x] atr_multiplier 必填校验（编译期，错误码 ATR_MULTIPLIER_REQUIRED）

### 混合范式 + runner + 常量

- [x] signals + rebalance 混合范式编译成功（on_bar 与 on_daily_rebalance 共存，T5 冒烟 (f)）
- [x] `build_backtest_kwargs`：symbols 推断已在 runner.run_backtest_engine L164-165 正确处理多标的，无需改
- [x] 多标的 warmup_period 取 max（rebalance 因子窗口 + signals 窗口，compiler 侧合并）
- [x] `GET /python/v1/backtest/constants` 的 `paradigms_supported` 扩展为 5 项：`["signals+bracket","signals+atr_stop","rebalance","exit.rules","mixed"]`（T5 冒烟 (g) TestClient 实测）
- [x] **AC-P2-1 rebalance 编译不抛范式错**：T5 冒烟 (a)
- [x] **AC-P2-2 exit.rules 编译不抛范式错**：T5 冒烟 (b)
- [x] **AC-P2-3 use_atr_stop 编译不抛范式错 + ATR_MULTIPLIER_REQUIRED 校验**：T5 冒烟 (c)(d)
- [x] **AC-P2-7b engine 不触库 + 无 eval**：`grep -rE "sqlite3|sqlalchemy|\.db|\beval\b|\bexec\b|__import__" stock-engine/services/backtest/ stock-engine/services/shared/` 无匹配（T5 grep 0 匹配）
- [x] **AC-P2-8 常量扩展**：T5 冒烟 (g) TestClient 实测 5 项 paradigm

## watcher 编排放宽（T3）✅

- [x] `BacktestServiceImpl.checkParadigmSupported` 删除 rebalance 拒绝（原 L737-740）
- [x] `BacktestServiceImpl.checkParadigmSupported` 删除 exit.rules 拒绝（原 L742-745）
- [x] 保留 GRID/WF 模式拒绝（第三波，由 run() 入口 mode 字段处理）
- [x] `resolveBacktestSymbols` 新增 universe 规模校验：`screen_config.universe=="all_a_shares"` → `BACKTEST_UNIVERSE_TOO_LARGE`（位于所有 DB 查询前）
- [x] csi300/csi500 复用 `resolveBacktestSymbols`（成分股并集，防幸存者偏差）
- [x] manual 用 `screen_config.stocks`
- [x] `BacktestErrorCodes.java` 新增 `BACKTEST_UNIVERSE_TOO_LARGE = 40016`（40012 已被 BACKTEST_MODE_NOT_SUPPORTED 占用，spec 仅规定语义未规定数值）
- [x] **AC-P2-5 watcher 不再拒绝 rebalance/exit.rules**：T3 删除拒绝分支 + 源码零引用验证
- [x] **AC-P2-6 all_a_shares 拒绝**：T3 resolveBacktestSymbols guard
- [x] **mvnw compile BUILD SUCCESS**（T3 离线编译通过）

## 前端编辑器解锁（T4）✅

- [x] editor.html Tab 7 fieldset 移除 `disabled` + 移除 alert-info「即将支持」
- [x] editor.html Tab 6 `f-use-atr-stop`/`f-atr-period`/`f-atr-multiplier` 移除 `disabled` + Phase 2 badge
- [x] editor.html Tab 6 `f-exit-rules-fieldset` 移除 `display:none` + alert-secondary
- [x] strategy-editor.js 删除 `delete s.trading_config.rebalance`，新增 `collectRebalance()` 采集器
- [x] strategy-editor.js 恢复 ATR 三字段采集（勾选 use_atr_stop 时写入 bracket）
- [x] exit.rules 规则行可添加（`collectExitRules` 已实现，容器取消隐藏后即可工作）
- [x] 常量动态拉取：原 Phase 1 禁用纯靠 HTML disabled 属性（已移除），无需额外 JS 禁用逻辑
- [x] **AC-P2-10 前端解锁**：`node --check strategy-editor.js` 通过；HTML disabled/display:none 移除 + grep 验证

## 联调与验收（T5/T6）

- [x] **AC-P2-11 rebalance 端到端**（静态验证）：rebalance.monthly + ranking=composite 编译成功；RebalanceEngine 3-symbol 打分正确（T5 冒烟 (a)(h)）。运行时端到端（watcher+engine 启动）待环境
- [x] **AC-P2-12a exit.rules 端到端**（静态验证）：含 exit.rules config 编译成功（T5 冒烟 (b)）。运行时待环境
- [x] **AC-P2-12b use_atr_stop 端到端**（静态验证）：编译成功 + ATR_MULTIPLIER_REQUIRED 校验（T5 冒烟 (c)(d)）。运行时待环境
- [x] **AC-P2-9 第一波无回归**：第一波 signals + exit.bracket（静态）策略版本仍可编译（T5 冒烟 (e)）；003+002 单测 95/95 passed；backtest/screener/factor 0 新增失败
- [x] `mvnw compile` BUILD SUCCESS（watcher 主代码编译通过）
- [x] `pytest tests/test_screener/ tests/test_factor/` 95 passed（engine 单测无回归）
- [x] **AC-P2-1~AC-P2-9 程序化验收全通过**（T5 冒烟 (a)-(i) + grep + TestClient）
- [x] **AC-P2-10 前端解锁**（node --check + HTML 验证）
- [ ] **AC-P2-11/AC-P2-12 运行时端到端人工走查**（代码就绪，待 watcher+engine 启动环境跑完整 HTTP 链路）

---

## 一处对 tasks.md 的有意偏离（已记录）

- **`BACKTEST_UNIVERSE_TOO_LARGE` 错误码数值**：tasks.md 原文 `= 40012`，实际取值 `40016`。原因：`40012` 已被 `BACKTEST_MODE_NOT_SUPPORTED` 占用（Phase 1 已交付的 GRID/WF 拒绝使用该码，spec.md 明确规定第二波 GRID/WF 仍返回 `BACKTEST_MODE_NOT_SUPPORTED`，绝不能改号）。spec.md 只规定该码语义（400 + 引导文案），未规定数值，故偏离符合 spec。已记录在 `BacktestErrorCodes.java` javadoc。

---

## 范围外（明确不在本 checklist）

以下能力属第三波，本 checklist **不验证**：
- GRID 网格寻优 / WALK_FORWARD 滚动验证（含 OQ-3 paramGrid schema 反推 + constraint/resultFilter 结构化 DSL）
- HTML 报告导出
- RUNNING 任务真终止
- DRAFT 版本回测（OQ-4 未决）
- all_a_shares 全市场回测（本 spec 限定 universe 规模，返回 BACKTEST_UNIVERSE_TOO_LARGE）
- 组合层风控（exit.rules portfolio scope + portfolio_drawdown ref 扩展）
