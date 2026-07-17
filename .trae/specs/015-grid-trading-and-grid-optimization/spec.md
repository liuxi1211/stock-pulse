# 网格交易策略 + GRID 参数寻优 Spec

> **来源 PRD**：`sdlc/prd/011-P1进阶能力-网格与参数寻优/P1进阶能力-网格交易与参数寻优PRD.md`
> **来源 spec（业务/测试硬化）**：`sdlc/prd/011-P1进阶能力-网格与参数寻优/011-网格交易与参数寻优-spec.md`
> **基线**：akquant 0.2.47（已源码级核实，见 PRD spec §S0）
> **状态**：spec 阶段（待评审 → 进入开发）
> **change-id**：`015-grid-trading-and-grid-optimization`

---

## Why

当前统一策略配置 Schema 的 `position_sizing` 只支持单组 method/target，无法表达「中枢 + 间距 + 每格数量 + 最大格数」的多档网格交易；回测中心只能跑单参数回测，用户无法回答「哪组参数最优」「样本外是否稳健」。两个缺口共同阻塞「震荡市策略 + 参数可信度验证」的最小闭环。

PRD 已通过老股民视角两轮评审 + Phase A/B/C 三阶段硬化，本 spec 在其基础上对齐当前 `stock-engine` 实际代码（`services/strategy/models.py` / `services/backtest/compiler.py` / `services/shared/condition_evaluator.py`），把 PRD 的 FR 落到具体文件与改动点。

---

## What Changes

### 第一部分：网格交易（能力一）

- **Schema 扩展**：`PositionSizingModel.method` 新增枚举值 `"grid"`；`PositionSizingModel.params` 内承载网格四要素 + 必填风控字段；`trading_config` 顶层新增 `grid` 子节点（独立范式，与 signals/rebalance 互斥）。
- **BREAKING（局部）**：method=`"grid"` 时不再走 `_dispatch_buy` 通用链路，compiler 入口分叉生成独立 `GridStrategy(Strategy)` 类。
- **GridStrategy 实现**：自包含状态机（`grid_level` / `pending_sell` / `pending_retry` / `stop_loss_triggered` / `cleared`），重写 `on_bar` / `on_order` / `on_trade`；on_order Filled 为 grid_level 唯一推进点。
- **范式校验扩展**：009 范式互斥（signals/rebalance）扩展为三范式互斥（signals/rebalance/grid），新增错误码 `GRID_PARADIGM_EXCLUSIVE`。
- **与 exit 互斥**：method=grid 时 `exit.bracket` / `exit.rules` 必须为空。
- **盈亏对账口径**：双 oracle（手工逐步累加 vs akquant `trades_df.net_pnl`），误差 < 0.01 元；float64 内部 6 位小数、展示 2 位截断。
- **前端编辑器**：仓位管理 Tab 新增「网格交易」专属控件，含四要素表单 + 必填风控 + 实时档位示意图 + 资金占用预估 + 成本预估（每格净收益 vs 成本占比）。

### 第二部分：GRID 参数寻优 + WALK-FORWARD（能力二）

- **Schema 扩展（第零波前置）**：`StrategyConfigModel` 顶层新增可选字段 `tunable_params: list[TunableParamModel]`；8 个预置模板回填 tunable_params。
- **compiler 形参绑定**：config 含 `tunable_params` 时，编译生成的 Strategy 类 `__init__` 展开为显式 POSITIONAL_OR_KEYWORD 形参（**禁 `**kwargs`**），绑定到 on_bar 内具体用法。
- **共享 DSL 层**：新增 `services/shared/condition_dsl.py`，提供 `compile_constraint(dsl) -> Callable[[dict], bool]`，返回 pickle 安全 callable（落盘类 `__call__` 或 `functools.partial`，禁 lambda/闭包）。
- **pickle 可行性 spike（硬前置）**：compiler 把生成的 Strategy 源码落盘到 `services/backtest/_generated/strat_{hash}.py` 再 `importlib.import_module` 取类，验证 `run_grid_search max_workers=4` 通过。
- **GRID engine 接入**：`services/backtest/optimizer.py` 封装 `aq.run_grid_search`，**db_path 硬编码不传**（engine 禁 sqlite3 写盘）。
- **WF engine 接入**：`run_walk_forward` 不返回每段最优参数表，engine 自写切窗循环（等距 bar 数），每段调 `run_grid_search` 拿 best_params 累积段表。
- **6 维过拟合指标**：engine 自实现收益差 / 回撤比 / 参数 CV / 孤峰差 / 段一致性 / 笔数比 + 综合可信度评分（0-100）。
- **max_workers cgroup 感知**：读 `/sys/fs/cgroup/cpu.max` 算实际可用核数，向下取整。
- **任务状态机扩展**：GRID/WF 任务纳入回测中心任务状态机（PENDING→RUNNING→SUCCESS/FAILED/CANCELLED），支持取消信号穿透子进程。
- **watcher 协议**：engine → watcher HTTP 响应 schema 见 PRD spec §S6.2；engine 不直连业务���。
- **前端 paramGrid 编辑器**：回测中心新增「参数寻优」Tab，从 tunable_params 反推形参 + 候选值编辑 + constraint/resultFilter 结构化编辑器 + Top-N 结果表 + WF 验证 + 「应用」按钮（通过 WF 才可点）。

### 不在本 spec 范围（沿用 PRD §1.3 / spec §S9）

- 实盘续跑（grid_level 跨会话持久化，二期）；
- 不对称网格 / 金字塔加仓（step_mode/qty_mode 字段位预留，一期固定 symmetric/equal）；
- WF 自然年/季初切窗（一期等距 bar 切窗，二期）；
- 跨寻优结果对比 FR-O10（第二波）；
- 分钟/小时级别网格（二期）；
- 可转债网格（P2）。

---

## Impact

### Affected specs

- **004 统一策略配置 Schema**：`tunable_params` 字段扩展（第零波前置）；`position_sizing.method` 增加 grid 枚举。
- **005 回测中心**：新增「参数寻优」Tab；任务状态机扩展 GRID/WF 任务类型。
- **006 / 009 范式治理**：互斥规则从两范式扩为三范式（signals/rebalance/grid）。
- **014 预置模板**：8 个模板回填 `tunable_params`。

### Affected code（stock-engine）

| 文件 | 改动 |
|---|---|
| `services/strategy/models.py` | 新增 `GridParamsModel` / `TunableParamModel`；`PositionSizingModel.method` 允许 `"grid"`；`TradingConfigModel` 新增 `grid` 字段；`StrategyConfigModel` 新增 `tunable_params` |
| `services/strategy/constants.py` | `POSITION_SIZING_METHODS` 增 `"grid"`；新增 grid 相关常量（max_grids 上限 20、tick_size 0.01 等） |
| `services/strategy/validator.py` | 新增 grid 范式互斥校验、grid 风控字段必填校验、grid+exit 互斥校验、tunable_params 校验、param_grid key 校验 |
| `services/strategy/errors.py` | 新增错误码：`GRID_PARADIGM_EXCLUSIVE` / `GRID_RISK_REQUIRED` / `GRID_EXIT_CONFLICT` / `GRID_PARAM_INVALID` / `TUNABLE_PARAM_UNKNOWN` / `PICKLE_IMPORT_MAIN_FORBIDDEN` / `OPTIMIZATION_INSUFFICIENT_DATA` 等 |
| `services/backtest/compiler.py` | 入口分叉：method=grid → 生成 `GridStrategy`；含 tunable_params → 展开 `__init__` 形参 |
| `services/backtest/grid_strategy.py` | **新文件**：`GridStrategy(Strategy)` 类 + 状态机 + cross 穿越 + 跳空去重 + T+1 挂起 |
| `services/backtest/_generated/` | **新目录**：落盘 `strat_{hash}.py`（pickle 友好） |
| `services/backtest/optimizer.py` | **新文件**：封装 `run_grid_search` / 自写 WF 切窗 / 6 维指标计算 / cgroup 感知 max_workers |
| `services/shared/condition_dsl.py` | **新文件**：`compile_constraint(dsl) -> Callable`（pickle 安全，复用 ConditionTree 模型） |
| `services/backtest/result_serializer.py` | 扩展：GRID Top-N 序列化 + WF 6 维指标序列化（NaN→None、单位归一化） |
| `api/v1/backtest.py` | 新增 `POST /python/v1/backtest/optimize`（GRID）+ `/walk_forward`（WF）路由；任务状态机查询/取消 |
| `services/backtest/runner.py` | 任务状态机扩展 GRID/WF 任务（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）+ 取消信号穿透 |

### Affected code（stock-watcher / 前端）

- watcher 侧新增 `optimization_result` / `param_scheme` 表 schema（由 watcher 团队定，engine 不关心）。
- 前端：策略编辑器仓位管理 Tab 网格控件；回测中心新增「参数寻优」Tab。

---

## ADDED Requirements

### Requirement: 网格策略 Schema（FR-G1）

系统 SHALL 在 `PositionSizingModel.method` 支持 `"grid"` 枚举，且 method=grid 时 `params` 内承载网格四要素与必填风控字段。

#### Scenario: 合法网格配置通过校验
- **WHEN** 用户提交 `position_sizing.method="grid"`，params 含 `center / step / qty_per_grid / max_grids / stop_loss_pct / max_holding_bars / max_position_value_pct`，且 trading_config 无 rebalance、无 exit
- **THEN** validator 通过，compiler 生成 GridStrategy 类

#### Scenario: 缺必填风控字段被拒
- **WHEN** method=grid 但 params 缺 `stop_loss_pct`（且无 `stop_loss_price`）
- **THEN** validator 拒绝，返回错误码 `GRID_RISK_REQUIRED`

#### Scenario: grid 与 rebalance 互斥
- **WHEN** config 同时含 `position_sizing.method="grid"` 与 `trading_config.rebalance`
- **THEN** validator 拒绝，返回错误码 `GRID_PARADIGM_EXCLUSIVE`

#### Scenario: grid 与 exit 互斥
- **WHEN** method=grid 且 `exit.bracket` 或 `exit.rules` 非空
- **THEN** validator 拒绝，返回错误码 `GRID_EXIT_CONFLICT`

#### Scenario: 资金占用超限禁止保存
- **WHEN** `max_grids × qty_per_grid × center > initial_cash × max_position_value_pct`
- **THEN** validator 拒绝，返回错误码 `GRID_INSUFFICIENT_CAPITAL`

### Requirement: GridStrategy 状态机（FR-G2 / FR-G3 / spec §S1）

系统 SHALL 生成独立 `GridStrategy(Strategy)` 类，跨 bar 维护 `grid_level ∈ [0, max_grids]` / `pending_sell` / `pending_retry` / `stop_loss_triggered` / `cleared` 状态变量；grid_level 仅在 `on_order` 收到 `Filled` 后 ±1。

#### Scenario: 跌穿买档买入
- **WHEN** 前 bar close 10.0、当前 close 9.8（跌穿第 1 格 9.8），grid_level=0、未 cleared、未触发止损
- **THEN** on_bar 主体发买单 200 股；订单 Filled 后 on_order 推进 grid_level=1

#### Scenario: T+1 反转挂起
- **WHEN** D3 close=9.4 买第 3 格（grid_level=3），D4 close=9.6 穿卖第 3 格但 `get_available_position()==0`（D3 买入 T+1 锁定）
- **THEN** on_bar 主体记 `pending_sell=(target_level=2, qty=200)`，grid_level 不变；D5 on_bar 开头检查 T+1 解锁后发卖单，Filled 后 grid_level=2

#### Scenario: 跳空穿越只成交一档
- **WHEN** 单 bar 从 9.6 直接开盘 10.5（穿越卖 2/卖 1/中枢三档），grid_level=2
- **THEN** 只对最近反向档位（卖第 2 格 = 10.0）发单，grid_level 仅 -1

#### Scenario: 一字跌停订单未成交不推进
- **WHEN** 连续 3 根跌停板 bar，买单未 Filled
- **THEN** grid_level 不推进；`pending_retry+=1`；超 `unfilled_retry_bars` 后放弃并 warning

#### Scenario: 触达止损全平
- **WHEN** close 跌破 `stop_loss_pct`（相对中枢）
- **THEN** 全平仓 + grid_level=0 + `stop_loss_triggered=true`（停止加仓）

#### Scenario: re_entry_after_clear=false 锁定
- **WHEN** grid_level 归零（全部卖飞）且 `re_entry_after_clear=false`，价格再次跌破中枢
- **THEN** `cleared=true`，不再建仓

### Requirement: 盈亏对账口径（FR-G5 / spec §S3）

系统 SHALL 在 float64 精度下计算盈亏（内部 6 位小数、展示 2 位截断），并用双 oracle 对账。

#### Scenario: 双 oracle 一致
- **WHEN** 跑 200 格高频震荡合成数据
- **THEN** `abs(总盈亏 - 手工逐步累加) < 0.01` 且 `abs(总盈亏 - trades_df.net_pnl 求和) < 0.01` 且 `abs(oracle_A - oracle_B) < 0.01`

#### Scenario: 印花税方向正确
- **WHEN** 同段数据跑网格
- **THEN** 买单印花税=0；卖单印花税=`qty × price × 0.001`

#### Scenario: 复权坐标系一致
- **WHEN** adjust_mode=`forward_adjusted`
- **THEN** 档位价、成交价、成本三者全用前复权价

### Requirement: 可调参数 Schema（FR-O3 / 第零波）

系统 SHALL 在 `StrategyConfigModel` 顶层支持可选 `tunable_params: list[TunableParamModel]`，每个参数含 `name / type / default / min / max / step`。

#### Scenario: tunable_params 反推 paramGrid
- **WHEN** config_json 含 `tunable_params=[{name:"fast",...},{name:"slow",...}]`
- **THEN** 前端 paramGrid 编辑器自动展示 fast/slow 形参行；compiler 生成的 Strategy `__init__` 形参为 `(fast, slow, ...)`

#### Scenario: 未知 param_grid key 被拒
- **WHEN** param_grid 含 `{"foobar":[1,2]}` 但 tunable_params 无 `foobar`
- **THEN** validator 拒绝，返回 `TUNABLE_PARAM_UNKNOWN`

#### Scenario: 禁用 **kwargs
- **WHEN** compiler 生成含 tunable_params 的 Strategy 类
- **THEN** `__init__` 签名为显式 POSITIONAL_OR_KEYWORD 形参，无 `**kwargs`（保证 akquant `_validate_strategy_param_grid_keys` 校验生效）

### Requirement: GRID 寻优 engine 接入（FR-O1）

系统 SHALL 在 `services/backtest/optimizer.py` 封装 `aq.run_grid_search`，**db_path 硬编码不传**。

#### Scenario: GRID Top-N 与原生一致
- **WHEN** 9 组合 × 3 年数据跑 GRID
- **THEN** engine 封装返回 Top-N 排序与 akquant 原生 `run_grid_search` 一致，指标差 < 1e-9

#### Scenario: db_path 不传
- **WHEN** 上层传入 db_path
- **THEN** optimizer drop 该参数（源码无 sqlite3 写盘）

#### Scenario: pickle 通过
- **WHEN** compiler 落盘 `strat_{hash}.py` 后 import 取类
- **THEN** `run_grid_search max_workers=4` 跑完 9 组合无 pickle 错误；`StratClass.__module__` 可定位

#### Scenario: 策略类在 __main__ 报错
- **WHEN** 用户尝试用 `__main__` 内定义的策略类跑 GRID
- **THEN** 明确报错 `PICKLE_IMPORT_MAIN_FORBIDDEN`（非 silently hang）

### Requirement: constraint/resultFilter DSL（FR-O2）

系统 SHALL 提供 `services/shared/condition_dsl.py:compile_constraint(dsl) -> Callable[[dict], bool]`，禁用 eval，返回 pickle 安全 callable。

#### Scenario: DSL 翻译与手写一致
- **WHEN** 6 组 constraint/resultFilter DSL 经 compile_constraint 编译
- **THEN** 与手写 predicate 在所有 param/metric 输入下结果一致

#### Scenario: callable 可 pickle
- **WHEN** compile_constraint 返回的 callable 在 4 worker 下 pickle
- **THEN** 不抛 PicklingError（用 functools.partial 或落盘类 `__call__`）

### Requirement: WALK-FORWARD engine 接入（FR-O4）

系统 SHALL 自写切窗循环（等距 bar 数），每段调 `run_grid_search` 拿 best_params，累积段表后算过拟合指标。

#### Scenario: 数据不足报错
- **WHEN** 数据长度 < `train_period + test_period`
- **THEN** 返回 `OPTIMIZATION_INSUFFICIENT_DATA`

#### Scenario: WF 切窗余数丢弃
- **WHEN** 数据 = 2.5×(train+test)
- **THEN** 切 2 段，末尾 0.5 段丢弃

### Requirement: 6 维过拟合指标（FR-O5 / spec §S5）

系统 SHALL 计算收益差 / 回撤比 / 参数 CV / 孤峰差 / 段一致性 / 笔数比 + 综合可信度评分（0-100），含除零保护。

#### Scenario: 黄金样本一致
- **WHEN** 100 根合成 bar + 已知 param_grid
- **THEN** 各维度与 spec §S5.3 手算期望差 < 1e-4

#### Scenario: 除零保护
- **WHEN** `mean(r_in)==0` 或 `std==0` 或 Top-1==Top-2 或 `mean(n_out)==0`
- **THEN** 对应维度返回 None/0，不抛 ZeroDivisionError、不返回 NaN

### Requirement: 任务状态机与并发（FR-O8）

系统 SHALL 把 GRID/WF 任务纳入回测中心任务状态机，支持取消信号穿透子进程；max_workers 读 cgroup quota 而非宿主机核数。

#### Scenario: 取消 60s 内终止
- **WHEN** GRID 跑到 50% 发 cancel
- **THEN** 60s 内任务状态变 CANCELLED

#### Scenario: 超时 FAILED
- **WHEN** 单组合故意 > 600s
- **THEN** timeout 后状态 FAILED，worker 重启（`max_tasks_per_child=1`）

#### Scenario: max_workers cgroup 感知
- **WHEN** engine 在容器内运行
- **THEN** 返回的可用核数来自 `/sys/fs/cgroup/cpu.max`，而非 `multiprocessing.cpu_count()`

### Requirement: 「应用」按钮 + 置信度展示（FR-O6）

系统 SHALL 仅当 Top-1 参数通过 WF 五项判据时允许「应用」按钮可点；通过 = 跳转新建策略页预填，**用户仍需手动保存**；GRID 永不直接写策略表。

#### Scenario: 未通过 WF 置灰
- **WHEN** Top-1 参数 WF 任一判据不满足
- **THEN** 「应用」按钮置灰；hover 显示「如何让它可点：调整 WF 阈值 / 增加数据 / 更换参数空间」；展示「你比无脑 Top-1 避免了 X% 过拟合风险」正向反馈

#### Scenario: 通过 WF 可点 + 二次确认
- **WHEN** Top-1 通过 WF 五项判据
- **THEN** 「应用」按钮可点；首次应用须勾选「我已理解历史最优不代表未来」复选框（单次会话有效）；跳转新建策略页预填，用户手动保存

---

## MODIFIED Requirements

### Requirement: 范式互斥（spec 009）

原 009 spec 规定 signals / rebalance 二选一。本 spec 扩展为 **signals / rebalance / grid 三选一**：method=grid 时 trading_config 不得含 signals 也不得含 rebalance。validator 与 compiler 两道防线均扩展。

### Requirement: compiler 入口分叉（stock-engine services/backtest/compiler.py）

原 compiler 单链路：config → SignalsStrategy / RebalanceStrategy。本 spec 在入口增加第三分叉：
```
config.position_sizing.method == "grid" → 生成 GridStrategy
config.trading_config.rebalance is not None → 生成 RebalanceStrategy
其他 → 生成 SignalsStrategy
```

### Requirement: 任务状态机（stock-engine services/backtest/runner.py）

原状态机仅支持���次回测任务。本 spec 扩展任务类型枚举增加 `GRID` / `WALK_FORWARD`，复用 PENDING→RUNNING→SUCCESS/FAILED/CANCELLED 流转，新增取消信号穿透子进程能力。

---

## REMOVED Requirements

无移除项。本 spec 为纯增量能力扩展，不破坏存量 signals/rebalance 行为（compiler 入口分叉，旧路径保持原样）。
