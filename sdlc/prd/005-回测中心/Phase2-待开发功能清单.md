# Phase 2 待开发功能清单

> **用途**：本文档记录 Phase 1 已设计但暂未实现回测的三个功能模块，供 Phase 2 开发时参考。
>
> **权威来源**：
> - Schema 定义：`sdlc/prd/004-策略管理/统一策略配置Schema.md`
> - 分波规划：`sdlc/prd/005-回测中心/回测中心PRD.md` §3.2 / §6.3-6.4 / §12.1-12.3
> - engine 当前实现：`stock-engine/services/backtest/compiler.py`

---

## 一、三个待开发功能总览

| 功能 | Schema 章节 | PRD 章节 | engine 拒绝位置 | 前端 Tab |
|---|---|---|---|---|
| rebalance 多因子调仓 | §3.3.4 | §6.3 | compiler.py `_check_phase1_paradigm` L64 | Tab 7 调仓 |
| exit.rules 动态出场 | §3.3.3 | §6.4 | compiler.py `_check_phase1_paradigm` L69 | Tab 6 止损止盈（已隐藏） |
| exit.bracket.use_atr_stop ATR 动态止损 | §3.3.3 | §6.4 / FR-6a | compiler.py `_check_phase1_paradigm` L73 | Tab 6 止损止盈（已 disabled） |

**分波理由**（PRD §12.1）：
- rebalance 需先抽共享层（ConditionEngine/factor_calculator → `services/shared/`），避免重写选股内核
- exit.rules 复杂度高，与 rebalance 同期
- use_atr_stop 随 exit.rules 一起进第二波

---

## 二、rebalance 多因子调仓驱动

### 2.1 Schema 字段定义（已定稿）

路径：`trading_config.rebalance`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `frequency` | enum | 是 | — | `"daily"` / `"weekly"` / `"monthly"` / `"quarterly"` → 触发判断 |
| `day_of_period` | int | 否 | `1` | 调仓日序（monthly=每月第几个交易日，weekly=周几） |
| `replace_method` | enum | 否 | `"full"` | `"full"`（全换）/ `"incremental"`（只换差额）→ `rebalance_to_topn(liquidate_unmentioned=)` |
| `weight_mode` | enum | 否 | `"equal"` | `"equal"`（等权）/ `"score"`（按 ranking score 加权） |
| `max_single_position` | number | 否 | `0.1` | 单标的最大仓位占比（风控） |
| `long_only` | bool | 否 | `true` | → `rebalance_to_topn(long_only=)` |

### 2.2 PRD 实现要求（§6.3）

- `on_daily_rebalance` 解析 `screen_config`，本地跑 ConditionEngine + ranking 选股，`rebalance_to_topn` 调仓
- **前置依赖**：先把 ConditionEngine/factor_calculator 抽成 engine 共享模块（`services/shared/`），让选股和回测都 import，保证 AC「同条件同结果」
- **硬约束**（选股与回测边界设计 §场景二 L379）：engine 不回调 watcher，调仓选股须在 engine 本地完成

### 2.3 当前 engine 拒绝逻辑

```python
# compiler.py L64-67
if tc is not None and tc.rebalance is not None:
    raise CompilerError(
        f"{_PHASE1_ERROR_CODE}: trading_config.rebalance 第一波暂不支持"
    )
```

### 2.4 Phase 2 启用时需要改动的位置

1. **engine compiler.py**：删除 L64-67 的 rebalance 拒绝逻辑，新增 `on_daily_rebalance` 编译分支
2. **engine 新增**：ConditionEngine/factor_calculator 抽共享层到 `services/shared/`
3. **watcher BacktestServiceImpl**：删除 `checkParadigmSupported` 里对 rebalance 的拒绝（L737-740）
4. **watcher buildKlineData**：rebalance 策略需喂入全 universe 的 K 线（而非仅 screen_config.stocks）
5. **前端 editor.html**：Tab 7 调仓 fieldset 移除 `disabled`，移除"即将支持"提示
6. **前端 strategy-editor.js**：恢复 collectStateFromForm 里 Tab 7 调仓的收集逻辑（当前被 `delete s.trading_config.rebalance` 替代）

---

## 三、exit.rules 动态出场条件树

### 3.1 Schema 字段定义（已定稿）

路径：`trading_config.exit.rules[]`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | 否 | — | 规则名（展示用） |
| `condition` | ConditionTree | 是 | — | 触发条件（§4，**支持 `ref`**） |
| `action` | enum | 否 | `"close_position"` | `"close_position"` / `"sell"` / 自定义 method |

支持的场景（Schema §场景覆盖度 L456）：
- 动态止损（entry_price − 2×ATR）
- trailing stop（最高价回撤）
- 多级止损

### 3.2 PRD 实现要求（§6.4）

- 支持 `ref`（entry_price / position_pnl_pct / position_qty / bars_held）
- 复杂动态止损用 `rules` 条件树 + `ref`，命中后 Strategy 主动 `close_position()`
- 与 rebalance 同期开发（复杂度高）

### 3.3 当前 engine 拒绝逻辑

```python
# compiler.py L69-72
if tc.exit.rules is not None:
    raise CompilerError(
        f"{_PHASE1_ERROR_CODE}: exit.rules 复杂出场规则第一波暂不支持"
    )
```

### 3.4 Phase 2 启用时需要改动的位置

1. **engine compiler.py**：删除 L69-72 的 exit.rules 拒绝逻辑，新增 `on_bar` 内 exit.rules 逐条评估 + `close_position()` 分派
2. **watcher BacktestServiceImpl**：删除 `checkParadigmSupported` 里对 exit.rules 的拒绝（L742-745）
3. **前端 editor.html**：Tab 6 `f-exit-rules-fieldset` 移除 `style="display:none;"`，移除"即将支持"提示
4. **前端 strategy-editor.js**：exitRuleRowHtml 行模板已就绪（name + action radio + condition 树），collectExitRules 已实现

---

## 四、exit.bracket.use_atr_stop ATR 动态止损

### 4.1 Schema 字段定义（已定稿）

路径：`trading_config.exit.bracket`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `stop_loss_pct` | number | 否 | `null` | 止损百分比 → stop_trigger = entry_price×(1−pct) |
| `take_profit_pct` | number | 否 | `null` | 止盈百分比 → take_profit = entry_price×(1+pct) |
| `use_atr_stop` | bool | 否 | `false` | 用 ATR 动态止损（与 stop_loss_pct 二选一） |
| `atr_period` | int | 否 | `14` | ATR 周期 |
| `atr_multiplier` | number | use_atr_stop=true 时必填 | — | ATR 倍数 → stop_trigger = entry_price − mult×ATR |

> 注意：`stop_loss_pct` / `take_profit_pct` 在 Phase 1 **已实现**（静态百分比止损止盈）；仅 `use_atr_stop` 及其关联字段是 Phase 2。

### 4.2 PRD 实现要求（FR-6a L353-357）

- `use_atr_stop` 第一波不支持，与 exit.rules 一起进第二波
- Phase 2 实现：stop_trigger = entry_price − atr_multiplier × ATR(atr_period)

### 4.3 当前 engine 拒绝逻辑

```python
# compiler.py L73-76
if tc.exit.bracket is not None and tc.exit.bracket.use_atr_stop:
    raise CompilerError(
        f"{_PHASE1_ERROR_CODE}: exit.bracket.use_atr_stop ATR 动态止损第一波暂不支持"
    )
```

### 4.4 Phase 2 启用时需要改动的位置

1. **engine compiler.py**：删除 L73-76 的 use_atr_stop 拒绝逻辑，在 `on_before_trading` 里新增 ATR 止损价计算分支
2. **前端 editor.html**：Tab 6 的 `f-use-atr-stop` / `f-atr-period` / `f-atr-multiplier` 移除 `disabled`
3. **前端 strategy-editor.js**：恢复 collectStateFromForm 里 ATR 字段的采集逻辑（当前被注释替代）

---

## 五、Phase 2 启动前置条件（PRD §12.3）

第二波独立 PRD 启动前需先解决：

1. **OQ-3**：paramGrid 形参 schema 反推——核查 004 config_json 结构
2. **共享层抽取**：把 ConditionEngine / factor_calculator 抽成 `services/shared/`，保证 AC「同条件同结果」可验证
3. **结构化 DSL 设计**：constraint / resultFilter 的 `{left, op, right}` 模型与统一 Schema §4 ConditionTree 的复用关系

---

## 六、Phase 1 前端标记状态备忘

以下三个区域在 Phase 1 已通过 disabled / hidden + 提示信息处理，用户不会误填：

| 区域 | 处理方式 | 提示文案 |
|---|---|---|
| Tab 7 调仓（整个 fieldset） | `disabled` + alert-info | "调仓功能将在 Phase 2 支持。多因子调仓驱动需要先抽取选股共享层，当前 Phase 1 暂不可用。" |
| Tab 6 ATR 动态止损（3 字段） | `disabled` + badge | "使用 ATR 动态止损 [Phase 2]" |
| Tab 6 动态出场规则 exit.rules | `display:none` + alert-secondary | "动态出场规则（exit.rules）将在后续版本支持，当前请使用上方的括号单止损止盈。" |

**JS 收集逻辑**（strategy-editor.js collectStateFromForm）：
- rebalance：`delete s.trading_config.rebalance`（无条件删除，不写入）
- ATR：采集逻辑已注释，不写入 `use_atr_stop` / `atr_period` / `atr_multiplier`
- exit.rules：容器内无规则行，`collectExitRules()` 返回空数组，不写入 `exit.rules`

**保证**：Phase 1 用户创建的策略 config_json 不会包含 rebalance / exit.rules / use_atr_stop，回测不会被范式校验拒绝。
