# 策略范式治理：择时与轮动互斥改造

> **版本**：v1.0
> **日期**：2026-07-14
> **对齐**：akquant 0.2.47 · 统一策略配置 Schema v1.0
> **状态**：待评审

---

## §0 背景与问题定性

### 0.1 现象

当前策略配置允许 `trading_config.signals` 与 `trading_config.rebalance` 同时在场
（[统一策略配置Schema.md §2.2](../../004-策略管理/统一策略配置Schema.md) 称之为"混合范式"），
并在三处实现里显式支持：

- engine [compiler.py:438-441](../../../stock-engine/services/backtest/compiler.py#L438-L441) 仅校验"两者都缺失"，未拒绝"两者都在场"；
- watcher [StrategyServiceImpl.java:582-590](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java#L582-L590) `deriveScope` 派生出 `scope=mixed`；
- 前端 [strategy-editor.js:908-962](../../../stock-watcher/src/main/resources/static/js/strategy-editor.js#L908-L962) `collectConfig` 无条件同时收集 signals 和 rebalance。

### 0.2 根因

经 akquant 0.2.47 源码与官方文档核查，确认**两个底层设计缺陷**：

**缺陷 A：混合范式导致订单叠加，仓位失控。**

akquant 在同一交易日的同一根 bar 内，`on_daily_rebalance` 先于 `on_bar` 执行
（[strategy_events.py:89-103](../../../akquant-0.2.47/python/akquant/strategy_events.py#L89-L103)），
���者产生的订单**全部进入同一根 bar 的待撮合队列，按 fill_policy 成交后合计**，
不存在"后执行覆盖先执行"的语义。若两个回调对同一标的下"目标仓位"类订单，订单会叠加，
目标仓位可能被推到 100% 以上甚至触发杠杆。这是 akquant 的既定撮合机制
（[simulated.rs:406-491](../../../akquant-0.2.47/src/execution/simulated.rs#L406-L491)），
非 bug，但当前 engine 封装未规避。

**缺陷 B：signals 范式对多标的 universe 无规模限制，资金争抢导致大面积拒单。**

[compiler.py:354-356](../../../stock-engine/services/backtest/compiler.py#L354-L356) 的 `_dispatch_buy`
对每个命中信号的 symbol 独立调用 `order_target_percent(target_percent=0.95)`，
而 `order_target_percent` 的语义是"单只目标市值占总权益的 95%"
（[strategy_trading_api.py:1501-1519](../../../akquant-0.2.47/python/akquant/strategy_trading_api.py#L1501-L1519)）。
当 universe 是 csi300（300 只）且有 100 只同时命中买入信号时：

- 默认 fill_policy（下一根开盘）下，100 只基于同一初始权益各算出 95% 的目标数量，全部入队；
- 下一根撮合时 FIFO 成交，第一只吃掉 95% 资金，其余 99 只因资金不足被整单 `Reject`
  （`allow_quantity_auto_resize=False`，[simulated.rs:406-491](../../../akquant-0.2.47/src/execution/simulated.rs#L406-L491)）；
- 且成交的是哪只取决于 `kline_data` 的 dict 迭代序，**回测结果不可复现**。

akquant 官方对多标的横截面场景的标准答案是 `on_daily_rebalance` + `rebalance_to_topn`
（[guide/strategy.md §3.4 横截面策略推荐范式](../../../akquant-0.2.47/docs/zh/guide/strategy.md#L446-L488)），
官方所有 `on_bar` 示例 universe 均为 1-2 只。当前 engine 封装未遵循此边界。

### 0.3 akquant 责任 vs engine 封装责任

核查结论：**akquant 框架本身设计完备**，明确区分了"单事件流逐标的"（`on_bar`）与
"横截面组合调仓"（`on_daily_rebalance`）两类场景，并提供了对应的 API。
问题全部出在 **stock-engine 封装层**：

1. 把"signals / rebalance"错误包装成可共存的两个范式字段；
2. 未对 signals 范式限制 universe 规模；
3. 多标的下单误用单标的的仓位 API。

本次改造**只动 stock-engine + stock-watcher 封装层**，不修改 akquant 源码。

---

## §1 目标与非目标

### 1.1 目标

1. **范式互斥**：`trading_config.signals` 与 `trading_config.rebalance` 编译期/校验期互斥，
   二者只能存在一个。消除订单叠加缺陷。
2. **universe 规模约束**：signals 范式（择时）的 universe 固定上限 ≤ N 只（见 §3.2），
   超过报错。消除资金争抢缺陷。
3. **范式语义对齐官方**：单标的/少量标的择时 → signals（`on_bar`）；多标的横截面选股轮动 →
   rebalance（`on_daily_rebalance`）。术语对齐 akquant 官方。
4. **全链路清理**：scope 枚举、前端 UI、模板、文档、错误码全部清理 mixed 残留。
5. **存量清零**：策略主表存量数据全清，不做兼容迁移。

### 1.2 非目标

- **不支持混合范式**：明确不做"横截面选股 + 选中标的择时"联动。若未来有此需求，
  基于 `on_daily_rebalance_after_bar` 重新设计，不在本次范围。
- **不改 akquant 源码**。
- **不改 rebalance 范式的执行逻辑**：[rebalance_engine.py](../../../stock-engine/services/backtest/rebalance_engine.py)
  与 [_attach_rebalance_method](../../../stock-engine/services/backtest/compiler.py#L861-L939)
  保持现状（调仓日选股 + rebalance_to_topn）。
- **不改选股中心**：003 选股中心的 `screen_config` 消费链路不变。

---

## §2 范式定义（改造后）

### 2.1 两种范式的边界

| 维度 | 择时范式（timing） | 轮动范式（rotation） |
|---|---|---|
| 触发字段 | `trading_config.signals` 在场 | `trading_config.rebalance` 在场 |
| akquant 回调 | `on_bar` | `on_daily_rebalance` |
| 决策范围 | 当前 bar 的单个 symbol | 全 universe 截面 |
| universe 规模 | ≤ N 只（manual 池） | 不限（csi300/csi500/manual） |
| 选股条件（screen_config.conditions） | **不消费**（仅 universe 圈标的） | 调仓日动态过滤 |
| 下单 API | `order_target_percent` / `buy` / `sell` | `rebalance_to_topn` → `order_target_weights` |
| 典型场景 | 双均线交叉、MACD 金叉死叉 | 动量 top10 月度轮动、多因子打分 |

### 2.2 校验规则

| 规则 | 错误码 |
|---|---|
| `signals` 与 `rebalance` 同时在场 | `SIGNALS_REBALANCE_EXCLUSIVE` |
| `signals` 与 `rebalance` 均不在场 | `MISSING_SIGNALS_OR_REBALANCE`（已存在） |
| signals 范式 + universe 规模 > N | `SIGNALS_UNIVERSE_TOO_LARGE` |
| signals 范式 + universe ∈ {csi300, csi500, all_a_shares} | `SIGNALS_UNIVERSE_NOT_MANUAL` |

### 2.3 scope 枚举改造

| 改造前 | 改造后 |
|---|---|
| `single` / `portfolio` / `mixed` | `single`（signals 范式）/ `portfolio`（rebalance 范式） |

移除 `StrategyScopeEnum.MIXED`。scope 由 `deriveScope` 按互斥规则派生：
有 signals → single；有 rebalance → portfolio；都有 → 不可能（校验已拦截）；都没有 → 报错。

---

## §3 详细改造方案

### 3.1 engine 侧（stock-engine，Python）

#### 3.1.1 错误码新增（P0）

**文件**：[services/strategy/errors.py](../../../stock-engine/services/strategy/errors.py)

新增两条错误码：

```python
SIGNALS_REBALANCE_EXCLUSIVE = (
    "SIGNALS_REBALANCE_EXCLUSIVE",
    "signals 与 rebalance 不能同时在场，必须二选一（择时范式用 signals，轮动范式用 rebalance）",
)
SIGNALS_UNIVERSE_TOO_LARGE = (
    "SIGNALS_UNIVERSE_TOO_LARGE",
    "signals（择时）范式的选股范围不得超过 {max} 只，当前 {actual} 只；"
    "多标的请改用 rebalance（轮动）范式",
)
SIGNALS_UNIVERSE_NOT_MANUAL = (
    "SIGNALS_UNIVERSE_NOT_MANUAL",
    "signals（择时）范式的选股范围仅支持 manual（手动指定少量标的），"
    "不支持 csi300/csi500/all_a_shares；多标的请改用 rebalance（轮动）范式",
)
```

#### 3.1.2 校验器加互斥规则（P0）

**文件**：[services/strategy/validator.py](../../../stock-engine/services/strategy/validator.py)

在 `_validate_structure_trading`（L108-117）现有"都缺失"校验后，追加：

```python
def _validate_structure_trading(tc: TradingConfigModel, errors: list) -> None:
    has_signals = tc.signals is not None
    has_rebalance = tc.rebalance is not None

    if not has_signals and not has_rebalance:
        errors.append(_err("trading_config", ErrorCode.MISSING_SIGNALS_OR_REBALANCE))
        return

    if has_signals and has_rebalance:
        errors.append(_err("trading_config", ErrorCode.SIGNALS_REBALANCE_EXCLUSIVE))
        return
```

#### 3.1.3 signals 范式 universe 规模校验（P0）

**文件**：[services/strategy/validator.py](../../../stock-engine/services/strategy/validator.py)

新增联动校验函数 `_validate_signals_universe`，在 `validate` 入口（结构校验通过后）
调用。规则：

- 当 `trading_config.signals` 在场时：
  - `screen_config.universe` 必须为 `"manual"`（否则 `SIGNALS_UNIVERSE_NOT_MANUAL`）；
  - `screen_config.stocks` 的长度 ≤ `SIGNALS_MAX_UNIVERSE_SIZE`（否则 `SIGNALS_UNIVERSE_TOO_LARGE`）。

**文件**：[services/strategy/constants.py](../../../stock-engine/services/strategy/constants.py)

新增常量：

```python
SIGNALS_MAX_UNIVERSE_SIZE = 10
```

> 上限取 10 的依据：akquant 官方所有 `on_bar` 多标的示例 universe 均 ≤ 2；
> 留 10 的余量覆盖"行业龙头组合"" ETF 轮动"等小池子场景。

#### 3.1.4 compiler 编译期兜底（P1）

**文件**：[services/backtest/compiler.py](../../../stock-engine/services/backtest/compiler.py)

在 `compile_strategy`（L433-441）解析 signals/rebalance 后，加编译期兜底
（validator 已拦截，此处为第二道防线，防止直接调 runner 绕过校验）：

```python
if has_signals and has_rebalance:
    raise CompilerError(
        "SIGNALS_REBALANCE_EXCLUSIVE: signals 与 rebalance 不能同时在场"
    )
```

`_check_paradigm`（L72-85）从 no-op 升级为实际校验入口，调用上述逻辑。

#### 3.1.5 回测路由常量清理（P1）

**文件**：[api/v1/backtest.py](../../../stock-engine/api/v1/backtest.py)

`get_constants`（L167-173）的 `paradigms_supported` 返回值，
从 `["signals+bracket","signals+atr_stop","rebalance","exit.rules","mixed"]`
改为 `["signals","rebalance"]`（去掉子范式细分和 mixed）。

#### 3.1.6 测试补充（P0）

**文件**：[tests/services/strategy/test_validator.py](../../../stock-engine/tests/services/strategy/test_validator.py)

新增用例：
- `test_signals_and_rebalance_both_present_rejected`：两者都在场 → `SIGNALS_REBALANCE_EXCLUSIVE`；
- `test_signals_universe_csi300_rejected`：signals + csi300 → `SIGNALS_UNIVERSE_NOT_MANUAL`；
- `test_signals_universe_manual_over_limit_rejected`：signals + manual 11 只 → `SIGNALS_UNIVERSE_TOO_LARGE`；
- `test_signals_universe_manual_within_limit_ok`：signals + manual 10 只 → 通过。

---

### 3.2 watcher 侧（stock-watcher，Java）

#### 3.2.1 scope 派生逻辑改造（P0）

**文件**：[service/impl/StrategyServiceImpl.java](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java)

`deriveScope`（L568-594）删除 mixed 分支：

```java
private String deriveScope(JSONObject configJson) {
    JSONObject trading = configJson.getJSONObject("trading_config");
    if (trading == null) return "single";
    boolean hasSignals = hasSignals(trading);
    boolean hasRebalance = trading.get("rebalance") != null;
    // 互斥：engine validator 已保证不会同时在场；此处防御性取 signals 优先
    if (hasSignals) return "single";
    if (hasRebalance) return "portfolio";
    return "single";
}
```

#### 3.2.2 模板加载器同步改造（P1）

**文件**：[config/StrategyTemplateLoader.java](../../../stock-watcher/src/main/java/com/arthur/stock/config/StrategyTemplateLoader.java)

`deriveScopeFromConfig`（L187-206）同步删除 mixed 分支，逻辑与 §3.2.1 一致。

#### 3.2.3 scope 枚举清理（P0）

**文件**：[constant/StrategyScopeEnum.java](../../../stock-watcher/src/main/java/com/arthur/stock/constant/StrategyScopeEnum.java)

移除 `MIXED("mixed","混合")` 枚举值。

#### 3.2.4 回测 universe 规模校验（P0）

**文件**：[service/impl/BacktestServiceImpl.java](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java)

`resolveBacktestSymbols`（L578-614）在现有 `all_a_shares` 拒绝之后，
对 signals 范式加额外校验：

```java
// signals 范式 + 非 manual universe → 拒绝
if (hasSignals(configJson) && !"manual".equalsIgnoreCase(universe)) {
    throw new BusinessException(BacktestErrorCodes.SIGNALS_UNIVERSE_NOT_MANUAL, ...);
}
// signals 范式 + manual 超限 → 拒绝
if (hasSignals(configJson) && symbols.size() > SIGNALS_MAX_UNIVERSE_SIZE) {
    throw new BusinessException(BacktestErrorCodes.SIGNALS_UNIVERSE_TOO_LARGE, ...);
}
```

`SIGNALS_MAX_UNIVERSE_SIZE` 常量与 engine 侧对齐（=10），放在
[constant/StrategySchemaConstants.java](../../../stock-watcher/src/main/java/com/arthur/stock/constant/StrategySchemaConstants.java)。

#### 3.2.5 回测错误码新增（P0）

**文件**：[constant/BacktestErrorCodes.java](../../../stock-watcher/src/main/java/com/arthur/stock/constant/BacktestErrorCodes.java)

新增：
- `SIGNALS_UNIVERSE_NOT_MANUAL`
- `SIGNALS_UNIVERSE_TOO_LARGE`
- `SIGNALS_REBALANCE_EXCLUSIVE`（watcher 侧兜底，正常情况 engine 先拦截）

#### 3.2.6 常量下发清理（P1）

**文件**：[controller/ConstantController.java](../../../stock-watcher/src/main/java/com/arthur/stock/controller/ConstantController.java)

`registerStrategyConstants`（L96-113）下发的 `strategies.scopes` 移除 MIXED。
新增下发 `signals_max_universe_size = 10` 供前端校验。

#### 3.2.7 DTO 注释清理（P2）

- [dto/strategy/StrategyDTO.java](../../../stock-watcher/src/main/java/com/arthur/stock/dto/strategy/StrategyDTO.java) L36 注释更新；
- [dto/strategy/StrategyTemplateDTO.java](../../../stock-watcher/src/main/java/com/arthur/stock/dto/strategy/StrategyTemplateDTO.java) 同步。

---

### 3.3 前端侧（stock-watcher templates / static）

#### 3.3.1 策略编辑器范式切换（P0，核心 UI 改造）

**文件**：[templates/quant/strategies/editor.html](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html)

在基础信息 Tab（Tab1）增加**范式选择控件**（segmented control 或 radio）：

```
策略范式：[ 择时范式（signals） ]  [ 轮动范式（rebalance） ]
```

- 择时范式选中：显示 Tab2（选股范围，限定 manual，≤10 只）、Tab3（买入信号）、
  Tab4（卖出信号）、Tab5（仓位）、Tab6（出场）、Tab8（回测）；**隐藏 Tab7（调仓）**。
- 轮动范式选中：显示 Tab2（选股范围，全量）、Tab7（调仓）、Tab8（回测）；
  **隐藏 Tab3/4/5/6**。

#### 3.3.2 配置收集逻辑改造（P0）

**文件**：[static/js/strategy-editor.js](../../../stock-watcher/src/main/resources/static/js/strategy-editor.js)

`collectConfig`（L880-996）按范式二选一：

```javascript
function collectConfig() {
    const paradigm = state.paradigm; // 'signals' | 'rebalance'
    const trading = {};
    if (paradigm === 'signals') {
        trading.signals = collectSignals();   // 原逻辑
        trading.position_sizing = collectPositionSizing();
        trading.exit = collectExit();
        // 不生成 trading.rebalance
    } else {
        trading.rebalance = collectRebalance(); // 原逻辑
        // 不生成 trading.signals / position_sizing / exit
    }
    return { trading_config: trading, ... };
}
```

- `collectRebalance`（L1021-1030）从"始终返回非 null"改为"轮动范式才返回对象，否则返回 null"；
- `buildSummary`（L1497-1528）删除 `hasSignals && hasRebalance → "混合策略"` 分支。

#### 3.3.3 选股范围联动（P0）

editor.html Tab2 的 universe 下拉：
- 择时范式：仅可选 `manual`，且标的云上限显示"已选 X/10"；
- 轮动范式：可选 `csi300 / csi500 / manual`，manual 不限数量。

#### 3.3.4 列表页清理（P1）

**文件**：[static/js/strategy-list.js](../../../stock-watcher/src/main/resources/static/js/strategy-list.js)

`SCOPE_LABEL`（L36-40）移除 `mixed: 'MIXED'`。

#### 3.3.5 回测新建页清理（P1）

**文件**：[static/js/backtest-new.js](../../../stock-watcher/src/main/resources/static/js/backtest-new.js)

- L167-173 从 `/constants` 拉取的 `paradigmsSupported` 移除对 mixed 的处理；
- L277 `mode: 'signals'` 硬编码改为按策略 config 的范式动态填充。

---

### 3.4 文档与 Schema 对齐（P1）

#### 3.4.1 统一策略配置 Schema

**文件**：[sdlc/prd/004-策略管理/统一策略配置Schema.md](../004-策略管理/统一策略配置Schema.md)

§2.2 表格更新：

| 字段在场 | 推断范式 | 含义 |
|---|---|---|
| `trading_config.signals` 在场 | **择时范式**（单标的/固定小池） | 每根 bar 评估买卖条件树，触发即下单 |
| `trading_config.rebalance` 在场 | **轮动范式**（多标的横截面） | 调仓日评估 screen + ranking，组合层换仓 |
| 二者均在场 | **非法** | 校验报错 `SIGNALS_REBALANCE_EXCLUSIVE` |
| 二者均不在场 | **非法** | 校验报错 `MISSING_SIGNALS_OR_REBALANCE` |

§3.3 表格追加说明：signals 范式要求 `screen_config.universe = "manual"` 且 stocks ≤ 10。

#### 3.4.2 akquant rules 补充

**文件**：[.trae/rules/akquant/03-strategy-api.md](../../../.trae/rules/akquant/03-strategy-api.md)

在 §11 后追加一节"范式选型约束"，记录本次改造的边界结论，供后续 AI 读取。

---

## §4 存量数据处理

### 4.1 清零策略

**决策**：策略主表 `quant_strategy`（含版本表 `quant_strategy_version`）存量数据全清。

执行方式（二选一，部署时择一）：

- **方式 A（推荐，干净）**：部署前执行 `TRUNCATE TABLE quant_strategy; TRUNCATE TABLE quant_strategy_version;`
- **方式 B（保留表结构）**：`DELETE FROM quant_strategy; DELETE FROM quant_strategy_version;`

### 4.2 模板重载

清表后重启 watcher，[StrategyTemplateLoader](../../../stock-watcher/src/main/java/com/arthur/stock/config/StrategyTemplateLoader.java)
会从 `classpath:strategies/templates/*.json` 重新加载 5 个内置模板
（3 个 signals + 2 个 rebalance，均无 mixed）。无需改模板内容。

### 4.3 不做兼容

- 不保留 `scope=mixed` 枚举值；
- 不做"旧配置自动转换"；
- engine 接收到含 signals+rebalance 共存的请求，直接 422 报错。

---

## §5 验收标准（AC）

### 5.1 功能验收

| AC 编号 | 场景 | 预期 |
|---|---|---|
| AC-1 | 提交 signals+rebalance 共存的策略配置 | engine 422，错误码 `SIGNALS_REBALANCE_EXCLUSIVE` |
| AC-2 | 提交 signals + universe=csi300 的策略 | watcher/engine 拒绝，错误码 `SIGNALS_UNIVERSE_NOT_MANUAL` |
| AC-3 | 提交 signals + manual 11 只的策略 | 拒绝，错误码 `SIGNALS_UNIVERSE_TOO_LARGE` |
| AC-4 | 提交 signals + manual 10 只的策略 | 通过，回测正常执行 |
| AC-5 | 提交 rebalance + universe=csi300 的策略 | 通过，回测正常执行调仓 |
| AC-6 | 前端编辑器选"择时范式" | 调仓 Tab 隐藏，选股范围限定 manual ≤10 |
| AC-7 | 前端编辑���选"轮动范式" | 信号/仓位/出场 Tab 隐藏，选股范围放开 |
| AC-8 | 策略列表 scope 筛选 | 仅剩 single/portfolio 两个选项 |
| AC-9 | 部署后存量清零 | quant_strategy 表为空，重启后只有 5 个模板策略 |

### 5.2 回归验收

| AC 编号 | 场景 | 预期 |
|---|---|---|
| AC-10 | 3 个 signals 模板（dual_ma/macd_short/volume_price）回测 | 结果与改造前一致（单标的，无混合） |
| AC-11 | 2 个 rebalance 模板（small_cap/low_pe_value）回测 | 结果与改造前一致（纯轮动，无混合） |
| AC-12 | 003 选股中心运行选股 | 不受影响（screen_config 消费链路未改） |

---

## §6 受影响文件清单（按优先级）

### P0（必改，阻断缺陷）

| # | 文件 | 改动 |
|---|---|---|
| 1 | stock-engine/services/strategy/errors.py | 新增 3 个错误码 |
| 2 | stock-engine/services/strategy/validator.py | 互斥校验 + universe 规模校验 |
| 3 | stock-engine/services/strategy/constants.py | 新增 SIGNALS_MAX_UNIVERSE_SIZE |
| 4 | stock-engine/services/backtest/compiler.py | 编译期互斥兜底 |
| 5 | stock-watcher StrategyServiceImpl.java | deriveScope 删 mixed |
| 6 | stock-watcher StrategyScopeEnum.java | 删 MIXED 枚举 |
| 7 | stock-watcher BacktestServiceImpl.java | resolveBacktestSymbols 加 signals 校验 |
| 8 | stock-watcher BacktestErrorCodes.java | 新增 3 个错误码 |
| 9 | stock-watcher editor.html | 范式切换控件 |
| 10 | stock-watcher strategy-editor.js | collectConfig 二选一 |

### P1（必改，清理残留）

| # | 文件 | 改动 |
|---|---|---|
| 11 | stock-engine/api/v1/backtest.py | paradigms_supported 去 mixed |
| 12 | stock-watcher StrategyTemplateLoader.java | deriveScopeFromConfig 删 mixed |
| 13 | stock-watcher ConstantController.java | scopes 下发去 mixed，加 max_universe |
| 14 | stock-watcher StrategySchemaConstants.java | 加 SIGNALS_MAX_UNIVERSE_SIZE |
| 15 | stock-watcher strategy-list.js | SCOPE_LABEL 去 mixed |
| 16 | stock-watcher backtest-new.js | paradigms 去 mixed |
| 17 | sdlc/prd/004-策略管理/统一策略配置Schema.md | §2.2 改互斥 |
| 18 | .trae/rules/akquant/03-strategy-api.md | 补范式选型约束 |

### P2（建议改，注释/展示清理）

| # | 文件 | 改动 |
|---|---|---|
| 19 | stock-watcher StrategyDTO.java | scope 注释 |
| 20 | stock-watcher StrategyTemplateDTO.java | scope 注释 |
| 21 | stock-watcher PageController.java | scopeOptions 注入 |
| 22 | stock-watcher PageController.java | editor.html L468 scopeOptions |

### 测试

| # | 文件 | 改动 |
|---|---|---|
| 23 | stock-engine/tests/services/strategy/test_validator.py | 互斥 + universe 用例 |
| 24 | stock-engine/tests/services/strategy/test_api.py | 端到端互斥用例 |
| 25 | stock-watcher StrategyServiceImplTest.java | deriveScope 单测 |

### 存量数据

| # | 操作 |
|---|---|
| 26 | TRUNCATE quant_strategy + quant_strategy_version |

---

## §7 风险与回滚

### 7.1 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 存量策略被清空，用户感知 | 中 | 部署前公告；模板重载保证系统可用 |
| 前端范式切换 UI 改动大，引入新 bug | 中 | 充分测试 AC-6/7/8；保留 Tab DOM 只隐藏不删除 |
| engine validator 改动影响已有 signals 策略 | 低 | 仅 signals+manual 小池不受影响（AC-10 回归） |

### 7.2 回滚

改造按 P0 → P1 → P2 分批合并。若 P0 上线后发现问题：
- engine 侧可通过回滚 validator.py 单文件恢复（放宽互斥校验）；
- watcher 侧回滚 StrategyScopeEnum + deriveScope；
- 前端回滚 editor.html + strategy-editor.js。

存量数据已清零不回滚（重建成本低于兼容成本）。

---

## §8 相关文档

- [统一策略配置Schema.md](../004-策略管理/统一策略配置Schema.md)
- [选股与回测边界设计.md](../003-多因子选股中心/选股与回测边界设计.md)
- [回测中心PRD.md](../005-回测中心/回测中心PRD.md)
- akquant 官方：[guide/strategy.md §3.4 横截面策略推荐范式](../../../akquant-0.2.47/docs/zh/guide/strategy.md)
