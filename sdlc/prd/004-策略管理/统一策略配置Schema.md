# 统一策略配置 Schema

> **版本**：v1.0
> **日期**：2026-07-01
> **对齐**：akquant 0.2.47（API 已与源码静态核对） · 因子计算模块（**待重写**，须按本 Schema §4.5 factorKey 体系实现）

---

## §1 文档定位与从属关系

### 1.1 这是策略配置的唯一真相源

本文定义 stock-pulse **策略配置 JSON 的权威结构**。所有字段定义权收归本文

## §2 设计原则与范式区分

### 2.1 三段分区

```
strategy_config
├── screen_config      ← 选股模块只读这层（POST /api/screener/run 只接收它）
├── trading_config     ← 信号 + 仓位 + 出场 + 调仓（重构重点）
└── backtest_config    ← 直接映射 aq.run_backtest 参数
```

保留 `screen_config` 独立分区的理由：**选股模块的职责边界**。选股服务无需感知交易/回测语义，只消费 `screen_config`。

### 2.2 两范式共存：用可选字段区分

| 字段在场 | 推断范式 | 含义 |
|---|---|---|
| `trading_config.signals` 在场 | **信号驱动**（单标的/固定池） | 每根 bar 评估买卖条件树，触发即下单 |
| `trading_config.rebalance` 在场 | **选股调仓驱动**（多因子） | 调仓日评估 screen + ranking，组合层换仓 |
| 二者均在场 | **混合范式** | 调仓日外用信号管理出入场（合法） |
| 二者均不在场 | **非法** | 校验报错（见 §7） |

### 2.3 能力对齐立场

Schema 字段**深度对齐 akquant 原生 API**，而非自造语义：

- `position_sizing.method` = akquant `Strategy` 下单方法名
- `rebalance` → `on_daily_rebalance` + `rebalance_to_topn(...)`
- `exit.bracket` → `place_bracket_order(...)`；`exit.rules` → 条件树（支持 `ref`）
- `backtest_config` → `run_backtest()` 参数 1:1 映射

---

## §3 完整字段树

### 3.1 顶层

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `strategy_id` | string | 是 | — | 策略唯一 ID（业务层） |
| `name` | string | 是 | — | 展示名 |
| `description` | string | 否 | `""` | 描述 |
| `scope` | enum | 否 | `"single"` | `"single"` / `"portfolio"` / `"mixed"`；**仅提示**，不驱动分支（真分支看 trading_config 字段在场） |
| `screen_config` | object | 否 | — | 选股配置（见 §3.2）；单标的场景可省略 |
| `trading_config` | object | 是 | — | 交易配置（见 §3.3）；至少含 `signals` 或 `rebalance` 之一 |
| `backtest_config` | object | 否 | — | 回测配置（见 §3.4）；实盘/纯选股可省略 |

### 3.2 `screen_config`（选股层）

| 字段 | 类型 | 必填 | 默认 | 映射 / 说明 |
|---|---|---|---|---|
| `universe` | enum\|string | 是 | — | `"all_a_shares"` / `"csi300"` / `"csi500"` / `"manual"` / 自定义池 ID |
| `stocks` | string[] | 条件必填 | — | `universe=="manual"` 时必填，如 `["510300.SH"]` |
| `top_n` | int | 否 | `null` | 选出的标的数量 → `rebalance_to_topn(top_n=)` |
| `conditions` | ConditionTree | 否 | `null` | 选股过滤条件树（§4）；**禁 `cross_up`/`cross_down`/`ref`**（§7.2） |
| `ranking` | Ranking | 否 | `null` | 排序规则（§3.2.1） |
| `filters` | Filters | 否 | 见 §3.2.2 | 静态过滤（ST/涨跌停/行业等） |

#### 3.2.1 `Ranking`

| 字段 | 类型 | 必填 | 默认 | 映射 / 说明 |
|---|---|---|---|---|
| `method` | enum | 是 | — | `"composite"`（加权综合，配合 weights）/ `"single"`（单因子排序） |
| `weights` | map<string,number> | composite 时必填 | — | `{factorKey: 权重}`，负权重=越小越好（如 `PE_TTM: -0.3`）→ 参与 `rebalance_to_topn(weight_mode="score")` 的 score 计算 |
| `factor` | string | single 时必填 | — | 单因子排序的 factorKey |
| `order` | enum | single 时必填 | `"desc"` | `"asc"` / `"desc"` |

#### 3.2.2 `Filters`

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `exclude_st` | bool | `true` | 排除 ST/*ST |
| `exclude_suspended` | bool | `true` | 排除停牌 |
| `exclude_limit_up` | bool | `true` | 排除涨停（无法买入） |
| `exclude_limit_down` | bool | `false` | 排除跌停 |
| `industries` | string[] | `[]` | 行业白名单（仅保留） |
| `exclude_industries` | string[] | `[]` | 行业黑名单（排除） |
| `min_list_days` | int | `0` | 上市天数下限（过滤次新） |

### 3.3 `trading_config`（交易层）

| 字段 | 类型 | 必填 | 默认 | 映射 / 说明 |
|---|---|---|---|---|
| `symbols` | string\|string[] | 否 | 继承 screen | 信号驱动的交易标的；单标的=str，固定池=list → `run_backtest(symbols=)` |
| `signals` | Signals | 条件必填 | — | 买卖信号条件树（§3.3.1）；**在场=信号驱动范式** |
| `position_sizing` | PositionSizing | 否 | 见 §3.3.2 | 仓位管理（统一为 akquant 下单方法名） |
| `exit` | Exit | 否 | `null` | 出场规则（§3.3.3） |
| `rebalance` | Rebalance | 条件必填 | — | 调仓规则（§3.3.4）；**在场=选股调仓范式** → `on_daily_rebalance` + `rebalance_to_topn` |

> **校验**：`signals` 与 `rebalance` 至少一个在场；都不在 → 422。

#### 3.3.1 `Signals`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `buy` | ConditionTree | 否 | `null` | 买入条件树（§4） |
| `sell` | ConditionTree | 否 | `null` | 卖出条件树（§4） |
| `eval_scope` | enum | 否 | `"per_symbol"` | `"per_symbol"`（每标的独立评估，默认）/ `"portfolio"`（组合层评估，预留） |

信号触发后的下单动作由 `position_sizing` 决定（不在 signals 内）。

#### 3.3.2 `PositionSizing`

本文 `method` 一律映射 akquant `Strategy` 的下单方法；组合权重语义移到 `rebalance.weight_mode`（§3.3.4），对齐 `rebalance_to_topn(weight_mode=)`。两层语义彻底解耦。

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `method` | enum | 是 | — | **下单方法名**，见下表 |
| `target` | number | 视 method | — | 目标值（百分比 0~1 / 股数 / 金额） |
| `params` | object | 否 | `{}` | method 专属参数透传 |
| `sell_method` | enum | 否 | `"close_position"` | sell 信号触发时用的下单方法 |

**`method` 枚举与 akquant 映射**：

| method | akquant 方法 | 必填参数 | 适用范式 |
|---|---|---|---|
| `order_target_percent` | `order_target_percent(target)` | `target`(0~1) | 信号驱动·单标的 |
| `order_target_value` | `order_target_value(target)` | `target`(金额) | 信号驱动 |
| `order_target` | `order_target(target)` | `target`(股数) | 信号驱动 |
| `buy` | `buy(quantity)` | `target`(股数) | 信号驱动（buy 信号） |
| `sell` | `sell(quantity)` | `target`(股数) | 信号驱动（sell 信号） |
| `buy_all` | `buy_all()` | 无 | 信号驱动 |
| `close_position` | `close_position()` | 无 | 信号驱动（sell 信号，默认） |
| `order_target_weights` | `order_target_weights(target_weights, liquidate_unmentioned, allow_leverage)` | `params.weights`(map) | 固定池/组合 |

> `order_target_weights` 的 `params.liquidate_unmentioned`（**akquant 默认 `false`**）、`params.allow_leverage`（默认 `false`）透传；注意与 `rebalance_to_topn` 同名参数（默认 `true`）区分。

#### 3.3.3 `Exit`（出场规则）

分两档：

| 字段 | 类型 | 必填 | 默认 | 映射 / 说明 |
|---|---|---|---|---|
| `bracket` | Bracket | 否 | `null` | OCO 括号单（静态阈值）→ `place_bracket_order` |
| `rules` | ExitRule[] | 否 | `[]` | 复杂出场规则（条件树，逐条评估，命中触发 `close_position`） |

**`Bracket`** → `place_bracket_order(symbol, quantity, stop_trigger_price, take_profit_price)`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `stop_loss_pct` | number | 否 | `null` | 止损百分比（0.1=跌 10%）→ stop_trigger = entry_price×(1−pct) |
| `take_profit_pct` | number | 否 | `null` | 止盈百分比 → take_profit = entry_price×(1+pct) |
| `use_atr_stop` | bool | 否 | `false` | 用 ATR 动态止损（与 `stop_loss_pct` 二选一） |
| `atr_period` | int | 否 | `14` | ATR 周期 |
| `atr_multiplier` | number | use_atr_stop=true 时必填 | — | ATR 倍数 → stop_trigger = entry_price − mult×ATR |

**`ExitRule`**：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | 否 | — | 规则名（展示用） |
| `condition` | ConditionTree | 是 | — | 触发条件（§4，**支持 `ref`**） |
| `action` | enum | 否 | `"close_position"` | `"close_position"` / `"sell"` / 自定义 method |

> 复杂动态止损（trailing stop、最高价回撤、多级止损）用 `rules` 条件树 + `ref`（如 `CLOSE < entry_price − 2×ATR`），命中后 Strategy 主动 `close_position()`。

#### 3.3.4 `Rebalance`（调仓，→ `rebalance_to_topn`）

| 字段 | 类型 | 必填 | 默认 | 映射 |
|---|---|---|---|---|
| `frequency` | enum | 是 | — | `"daily"`/`"weekly"`/`"monthly"`/`"quarterly"` → `on_daily_rebalance` 触发判断 |
| `day_of_period` | int | 否 | `1` | 调仓日序（monthly=每月第几个交易日，weekly=周几） |
| `replace_method` | enum | 否 | `"full"` | `"full"`（全换）/ `"incremental"`（只换差额）→ `rebalance_to_topn(liquidate_unmentioned=)` |
| `weight_mode` | enum | 否 | `"equal"` | **`"equal"`（等权）/ `"score"`（按 ranking score 加权）** → `rebalance_to_topn(weight_mode=)` |
| `max_single_position` | number | 否 | `0.1` | 单标的最大仓位占比（风控） |
| `long_only` | bool | 否 | `true` | → `rebalance_to_topn(long_only=)` |

### 3.4 `backtest_config`（回测层，直接映射 `aq.run_backtest`）

| 字段 | 类型 | 必填 | 默认 | 映射 / 说明 |
|---|---|---|---|---|
| `initial_cash` | number | 是 | `100000` | → `initial_cash` |
| `start_date` | string(YYYY-MM-DD) | 否 | `null` | → `start_time` |
| `end_date` | string(YYYY-MM-DD) | 否 | `null` | → `end_time` |
| `benchmark` | string | 否 | `null`（watcher 编排层默认填 `000300.SH` 沪深300） | 基准指数代码（如 `000300.SH`/`000905.SH`），用于回测报告相对收益比较与净值叠加。**不透传 `aq.run_backtest`**，由 watcher 拼装 `benchmark_data` 传 engine，engine 归一化到初始净值 1.0 后与策略净值同坐标系叠加。优先级：`overrideConfig.benchmark` > `config_json.backtest_config.benchmark` > watcher 默认 `000300.SH` |
| `broker_profile` | enum | 否 | `"cn_stock_miniqmt"` | → `broker_profile`；注入：佣金 0.0003 / 印花 0.001 / 过户 0.00001 / 最低 5 / 滑点 0.0002 / 量限 0.2 / 手数 100（**不含 `t_plus_one`**） |
| `t_plus_one` | bool | 否 | `true` | **必须显式传**（profile 不含）→ `t_plus_one` |
| `commission_rate` | number | 否 | 继承 profile | 覆盖佣金率 → `commission_rate` |
| `stamp_tax_rate` | number | 否 | 继承 | 印花税 → `stamp_tax_rate` |
| `transfer_fee_rate` | number | 否 | 继承 | 过户费 → `transfer_fee_rate` |
| `min_commission` | number | 否 | 继承 | 最低佣金 → `min_commission` |
| `slippage` | number\|Slippage | 否 | 继承 | float 或 `{type:"percent"\|"fixed", value}` → `slippage`（akquant 原生支持 float\|dict） |
| `volume_limit_pct` | number | 否 | 继承(0.2) | 量限 → `volume_limit_pct` |
| `lot_size` | int | 否 | 继承(100) | 手数 → `lot_size` |
| `warmup_period` | int | 否 | `0` | 预热 bar 数 → `warmup_period`（也是 Strategy 属性；effective_depth=max(warmup, history_depth)） |
| `history_depth` | int | 否 | `60` | 历史窗口深度 → `history_depth` |
| `fill_policy` | object | 否 | 继承 | → `fill_policy` |
| `timezone` | string | 否 | `"Asia/Shanghai"` | → `timezone` |
| `show_progress` | bool | 否 | `false` | → `show_progress` |
| `risk_config` | object | 否 | `null` | → `risk_config` |
| `strict_strategy_params` | bool | 否 | `false` | **必须显式传**（akquant 原生默认 `true`，严格校验构造参数，会拒绝动态 Strategy 工厂）→ `strict_strategy_params` |

---

## §4 条件树与表达式节点

选股条件（`screen_config.conditions`）与买卖信号（`trading_config.signals`）、出场规则（`exit.rules`）**共用同一套条件模型**。

### 4.1 ConditionTree（递归逻辑组）

```jsonc
{
  "operator": "AND" | "OR",
  "conditions": [ /* ConditionTree | CompareLeaf，递归，无层级限制 */ ]
}
```

- `operator`: `"AND"`（全部满足）/ `"OR"`（任一满足）
- `conditions`: 子条件数组，元素可是逻辑组或 `compare` 叶子

### 4.2 CompareLeaf（叶子比较）

```jsonc
{
  "type": "compare",
  "left":  <ExpressionNode>,
  "comparator": <Comparator>,
  "right": <ExpressionNode>
}
```

### 4.3 ExpressionNode（表达式节点，4 种形态）

**对齐因子计算模块的 `compute(name, inputs, **params)` 签名**：返回 `np.ndarray` 或 `tuple[ndarray, ...]`，tuple 用 `output_index` 取值（重写时按此签名实现）。

| 形态 | 语法 | 说明 |
|---|---|---|
| ① 静态值 | `{ "value": 70 }` | 数字/字符串字面量 |
| ② 因子引用 | `{ "factor": "RSI", "params": {...}, "inputs": [...], "output_index": 0 }` | → `provider.compute(name=factor, inputs, **params)`；多输出用 `output_index`；`inputs` 覆盖默认输入列 |
| ③ 算术运算 | `{ "op": "+\|-\|*\|/", "left": <EN>, "right": <EN> }` | 递归；除零安全降级返回 0 |
| ④ 状态引用 | `{ "ref": "entry_price" }` | 持仓/状态引用；**仅 trading_config 内合法，screen_config 内禁止**（§7.2） |

### 4.4 Comparator

| 值 | 含义 | 时序要求 | 适用范围 |
|---|---|---|---|
| `>` `<` `>=` `<=` `==` `!=` | 比较 | 无 | screen + trading 通用 |
| `cross_up` | 上穿（金叉）：当前 left>right 且上一根 bar left≤right | 需上一根 bar | **仅 trading_config**；要求 left/right 都是 factor |
| `cross_down` | 下穿（死叉）：当前 left<right 且上一根 bar left≥right | 需上一根 bar | **仅 trading_config**；要求 left/right 都是 factor |

### 4.5 因子表（factorKey 体系 ）

> **命名以代码为准**：动量指标用 `KDJ`；`KDJ` 的 J 值由 provider 内部计算（`j = 3*K − 2*D`）直接作为第三个输出，无需前端组合。

#### 技术面因子（走 `akquant.talib`）

| factorKey | akquant 函数 | inputs | 多输出 / output_index | 备注 |
|---|---|---|---|---|
| `MA` | `MA` | `close` | 否 | `matype` 仅支持 0（SMA），默认 0 |
| `EMA` | `EMA` | `close` | 否 | |
| `BOLL` | `BBANDS` | `close` | 是 `(upper, mid, lower)` → 0/1/2 | |
| `SAR` | `SAR` | `high, low` | 否 | |
| `MACD` | `MACD` | `close` | 是 `(dif, dea, hist)` → 0/1/2 | params: `fastperiod, slowperiod, signalperiod` |
| `RSI` | `RSI` | `close` | 否 | |
| `KDJ` | `STOCH` | `high, low, close` | provider 输出 `(K, D, J)` → 0/1/2（STOCH 原生仅返 (K,D)，J=3K−2D 由 provider 合成） | **J 由 provider 算好** |
| `ADX` | `ADX` | `high, low, close` | 否 | |
| `PLUS_DI` | `PLUS_DI` | `high, low, close` | 否 | |
| `MINUS_DI` | `MINUS_DI` | `high, low, close` | 否 | |
| `WILLR` | `WILLR` | `high, low, close` | 否 | |
| `CCI` | `CCI` | `high, low, close` | 否 | |
| `ATR` | `ATR` | `high, low, close` | 否 | |
| `OBV` | `OBV` | `close, volume` | 否 | |

#### 价格 / 成交量因子（走 `NumpySimpleProvider`）

| factorKey | 底层 | inputs | 备注 |
|---|---|---|---|
| `CLOSE` | 直取 | `close` | 当前收盘价 |
| `HIGH` | 直取 | `high` | 当前最高价 |
| `LOW` | 直取 | `low` | 当前最低价 |
| `VOLUME` | 直取 | `volume` | 当前成交量 |
| `VOL_MA` | SMA on volume | `volume` | params: `timeperiod` |
| `VOL_EMA` | EMA on volume | `volume` | params: `timeperiod` |

#### 基本面因子（走选股侧因子服务，非技术面因子库）

| factorKey | 来源 | 备注 |
|---|---|---|
| `PE_TTM` / `PB` / `TOTAL_MV` / `ROE_TTM` / `*_GROWTH` 等 | watcher 的 `daily_basic` / `fina_indicator` | 全大写下划线命名；选股截面场景使用；**不在 trading_config 的 on_bar 实时路径**（基本面无日线时序） |

> **factor 节点路由规则**：同一 `{factor}` 节点形态，按 factorKey 路由——技术面（上表 14 + 价格成交量 6 = 20 个）走因子计算模块（`akquant.talib`），基本面（`*_TTM`/`TOTAL_MV` 等）走选股因子服务。统一了旧 z（只技术面）与旧 003（混用但未说明）。

### 4.6 `ref` 取值表（仅 trading_config 合法）

| ref | 含义 | 实现锚点 |
|---|---|---|
| `entry_price` | 持仓入场均价 | `self.position.entry_price`（或 `.avg_price`；`Position` 对象见 `strategy_position.py`。注：`get_position()` 返回 float，**不可** `.avg_cost`） |
| `position_pnl_pct` | 持仓盈亏百分比 | 由 `self.position.entry_price` 与当前 `bar.close` 计算 |
| `position_qty` | 持仓数量 | `self.position.size`（或直接 `self.get_position(symbol)`，返回 float） |
| `bars_held` | 持有 bar 数 | `self.hold_bar(symbol)`（akquant 原生） |
| `highest_since_entry` | 入场后最高价（trailing stop 用） | Strategy 自维护（**预留扩展**） |
| `lowest_since_entry` | 入场后最低价 | 同上（**预留扩展**） |

---

## §5 完整示例

### 5.1 示例 ①：信号驱动（双均线 + 量能，单标的）

```jsonc
{
  "strategy_id": "dual_ma_vol_boost_001",
  "name": "双均线+成交量放大策略",
  "scope": "single",

  "trading_config": {
    "symbols": "510300.SH",
    "signals": {
      "buy": {
        "operator": "AND",
        "conditions": [
          { "type": "compare",
            "left":  { "factor": "MA", "params": { "timeperiod": 5 } },
            "comparator": "cross_up",
            "right": { "factor": "MA", "params": { "timeperiod": 20 } } },
          { "type": "compare",
            "left":  { "factor": "VOL_MA", "params": { "timeperiod": 5 } },
            "comparator": ">",
            "right": { "op": "*",
              "left":  { "factor": "VOL_MA", "params": { "timeperiod": 20 } },
              "right": { "value": 1.5 } } },
          { "operator": "OR", "conditions": [
            { "type": "compare",
              "left":  { "factor": "RSI", "params": { "timeperiod": 14 } },
              "comparator": ">",
              "right": { "value": 50 } },
            { "type": "compare",
              "left":  { "factor": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "output_index": 2 },
              "comparator": ">",
              "right": { "value": 0 } }
          ]}
        ]
      },
      "sell": {
        "operator": "OR",
        "conditions": [
          { "type": "compare",
            "left":  { "factor": "MA", "params": { "timeperiod": 5 } },
            "comparator": "cross_down",
            "right": { "factor": "MA", "params": { "timeperiod": 20 } } },
          { "type": "compare",
            "left":  { "factor": "CLOSE" },
            "comparator": "<",
            "right": { "op": "*",
              "left":  { "factor": "BOLL", "params": { "timeperiod": 20, "nbdevup": 2, "nbdevdn": 2 }, "output_index": 2 },
              "right": { "value": 0.95 } } }
        ]
      }
    },
    "position_sizing": {
      "method": "order_target_percent",
      "target": 0.95,
      "sell_method": "close_position"
    }
  },

  "backtest_config": {
    "initial_cash": 100000,
    "broker_profile": "cn_stock_miniqmt",
    "t_plus_one": true,
    "warmup_period": 20,
    "history_depth": 60,
    "slippage": { "type": "percent", "value": 0.001 }
  }
}
```

### 5.2 示例 ②：选股调仓驱动（多因子价值策略）

> 即旧 003 §6.2 的等价策略，用新 Schema 重写。注意三处修正：①删除 conditions 里的 `MA5 cross_up MA20`（截面禁 cross）；②`equal_weight` 移到 `rebalance.weight_mode`；③`exit_rules` → `exit.bracket`。

```jsonc
{
  "strategy_id": "multi_factor_value_001",
  "name": "多因子价值策略",
  "description": "低PE + 高ROE + 小市值",
  "scope": "portfolio",

  "screen_config": {
    "universe": "all_a_shares",
    "top_n": 30,
    "conditions": {
      "operator": "AND",
      "conditions": [
        { "type": "compare", "left": { "factor": "PE_TTM" },  "comparator": "<", "right": { "value": 20 } },
        { "type": "compare", "left": { "factor": "ROE_TTM" }, "comparator": ">", "right": { "value": 15 } },
        { "type": "compare", "left": { "factor": "TOTAL_MV" }, "comparator": "<", "right": { "value": 20000000000 } }
      ]
    },
    "ranking": {
      "method": "composite",
      "weights": { "PE_TTM": -0.3, "ROE_TTM": 0.4, "TOTAL_MV": -0.3 }
    },
    "filters": {
      "exclude_st": true, "exclude_suspended": true,
      "exclude_limit_up": true, "exclude_limit_down": false,
      "industries": ["银行", "医药"], "exclude_industries": []
    }
  },

  "trading_config": {
    "rebalance": {
      "frequency": "monthly",
      "day_of_period": 1,
      "replace_method": "full",
      "weight_mode": "equal",
      "max_single_position": 0.1,
      "long_only": true
    },
    "exit": {
      "bracket": {
        "stop_loss_pct": 0.1,
        "take_profit_pct": 0.3,
        "use_atr_stop": false
      }
    }
  },

  "backtest_config": {
    "initial_cash": 100000,
    "start_date": "2020-01-01",
    "end_date": "2024-01-01",
    "broker_profile": "cn_stock_miniqmt",
    "t_plus_one": true,
    "slippage": { "type": "percent", "value": 0.001 }
  }
}
```

---

## §6 场景覆盖度校验

### 6.1 

| 场景 | 统一 Schema 表达 |
|---|---|
| 均线金叉/死叉 | `signals` + `cross_up`/`cross_down` |
| RSI/MACD 阈值 | `signals` + `{factor:RSI}` / `{factor:MACD,output_index:2}` |
| 成交量放量/缩量 | `signals` + `{factor:VOL_MA}` + `op:*` |
| 价格与均线偏离 | `signals` + `op:/`（CLOSE/MA） |
| 布林突破 | `signals` + `{factor:BOLL,output_index:0/2}` |
| 多指标 AND/OR 嵌套 | ConditionTree 递归 |
| 三值比较（MA5−MA20 > ATR×2） | `op:-` / `op:*` 嵌套 |
| 因子比值 | `op:/` |
| 嵌套算术（振幅 (H−L)/C） | `op` 嵌套 |
| 动态止损（entry_price−2×ATR） | `exit.rules[].condition` + `ref:entry_price` + `op` |

### 6.2 

| 场景 | 统一 Schema 表达 |
|---|---|
| **A：单标的回测** | `screen_config` 省略或 `universe:"manual"+stocks:[x]`；`trading_config.signals` 在场；symbols=单标的 |
| **B：固定池 + 技术指标** | `screen_config.universe:"csi300"`（或 manual+stocks）；`signals` 在场；symbols=继承池或显式 list；position_sizing 可用 `order_target_weights` |
| **C：多因子选股 + 调仓** | `screen_config` 全量；`trading_config.rebalance` 在场；`weight_mode:"equal"\|"score"` |

### 6.3 akquant 动量轮动（`rebalance_to_topn`）

| 场景 | 统一 Schema 表达 |
|---|---|
| TopN 动量轮动 | `screen_config.ranking` 按动量因子排序（如 `{factor:"MOM_20D"}` 或 RSI）；`top_n=N`；`trading_config.rebalance.{frequency, weight_mode:"equal"\|"score"}` → `rebalance_to_topn(scores, top_n, weight_mode, long_only, liquidate_unmentioned)` |

### 6.4 未覆盖场景（明确标注，节点扩展即可演进）

| 未覆盖场景 | 原因 | 扩展建议 |
|---|---|---|
| 多周期条件（日线金叉且 60min RSI<30） | factor 节点无 period 维度 | factor 节点加 `period:"1d"\|"60m"`，数据层提供多周期合并 df |
| N 根 bar 内穿越（3 日内金叉） | `cross_up` 只看相邻 bar | comparator 扩展 `cross_up_window:N` 或新增 `within` 聚合节点 |
| 条件计数（近 10 日 RSI<30 出现 3 次） | 无时序聚合 | 新增 `{op:"count", condition, window}` 聚合节点 |
| 组合层风控（总回撤>10% 清仓） | `exit.rules` 当前 per_symbol | 预留 `eval_scope:"portfolio"` + ref 扩展 `portfolio_drawdown` |

---

## §7 字段约束与校验规则汇总

### 7.1 结构约束

| 规则 | 失败行为 |
|---|---|
| `trading_config.signals` 与 `trading_config.rebalance` 至少一个在场 | 422 |
| `screen_config.universe=="manual"` 时 `stocks` 必填且非空 | 422 |
| `ranking.method=="composite"` → `weights` 必填；`=="single"` → `factor`+`order` 必填 | 422 |
| `position_sizing.method` 为 `order_target_*` 时 `target` 必填 | 422 |
| `exit.bracket.use_atr_stop==true` → `atr_multiplier` 必填 | 422 |

### 7.2 条件模型约束（关键）

| 规则 | 原因 |
|---|---|
| `screen_config.conditions` 内**禁止 `cross_up`/`cross_down`** | 选股是截面操作（某日全市场快照），无"上一根 bar"，穿越无定义 |
| `screen_config.conditions` 内**禁止 `ref`** | 选股时无持仓上下文 |
| `cross_up`/`cross_down` 要求 left/right 都是 `{factor}` 节点 | 穿越判断需对比两个因子序列，算术表达式无法维护快照 |
| `exit.rules[].condition` 内**允许 `ref`** | 出场规则针对持仓，有持仓上下文 |
| 因子预热期返回 NaN → 该条件求值为 `false` | 避免预热期误触发 |

### 7.3 因子节点约束

| 规则 | 说明 |
|---|---|
| 技术面 factorKey 必须在 §4.5 的 20 个之内 | 否则因子计算模块抛 `UNKNOWN_INDICATOR` |
| 多输出因子（`MACD`/`BOLL`/`KDJ`）取值必须带 `output_index` | 否则无法降维到标量 |
| 基本面 factorKey 仅在 `screen_config` 内合法 | 基本面无日线时序，不进 `signals`/`exit.rules` 的 on_bar 路径 |

---

## §8 Schema → akquant 映射速查表

| Schema 字段 | akquant API |
|---|---|
| `position_sizing.method` 各值 | `Strategy.order_target_percent` / `order_target_value` / `order_target` / `buy` / `sell` / `buy_all` / `close_position` / `order_target_weights` |
| `trading_config.signals` | `Strategy.on_bar` 内调条件引擎，命中调上表方法 |
| `trading_config.rebalance` | 重写 `Strategy.on_daily_rebalance`，调 `self.rebalance_to_topn(scores, top_n, weight_mode, long_only, liquidate_unmentioned)` |
| `exit.bracket` | `Strategy.on_before_trading` 内对持仓调 `self.place_bracket_order(symbol, quantity, stop_trigger_price, take_profit_price)` |
| `exit.rules` | `Strategy.on_bar` 内评估条件树，命中调 `self.close_position()` |
| `backtest_config.*` | `aq.run_backtest(data=Dict[sym,df], strategy=Factory(json), symbols=, initial_cash=, broker_profile=, t_plus_one=, commission_rate=, stamp_tax_rate=, min_commission=, slippage=, volume_limit_pct=, lot_size=, warmup_period=, history_depth=, start_time=, end_time=, ...)` |
| `{factor}` 节点 | 因子计算模块 `compute(name=factor, inputs={...}, **params)`；多输出按 `output_index` 取 |
| `{ref}` 节点 | `Strategy.get_position()` / 自维护状态 |

---

## §9 关键设计权衡

### 9.1 position_sizing 两层语义解耦

akquant 下单方法天然分两层：`order_target_percent`（per_symbol）vs `order_target_weights`/`rebalance_to_topn`（portfolio）。旧 003 把 `equal_weight` 塞进 `position_sizing.method` 是混淆两层。

**本文决策**：`position_sizing.method` 只保留下单方法名；组合权重（等权/按分）移到 `rebalance.weight_mode`，对齐 `rebalance_to_topn(weight_mode=)`。
**代价**：信号驱动场景多标的等权需显式 `order_target_weights(weights={sym:1/N})`，不能用 `equal_weight` 简写——有意为之，显式比隐式安全。

### 9.2 cross_up/cross_down 的截面陷阱

旧 003 §6.2 把 `MA5 cross_up MA20` 写进 `screen_config.conditions` 是潜在 bug：选股是截面，无"上一根 bar"；若实现层偷偷用标的历史 bar 算 cross，选股结果会依赖隐藏时序状态，对停牌/新股产生诡异结果。

**本文决策**：校验层硬性禁止 `screen_config.conditions` 出现 cross_*/ref。趋势过滤移入 `trading_config.signals`。
**代价**：用户在"选股+调仓"范式下无法在 screen 里直接写趋势过滤，需理解 screen/trading 的时序边界——必要的概念清晰度。

### 9.3 ref 仅 trading 合法

`ref:entry_price` 等节点引用持仓状态，选股时无持仓上下文。若允许其在 screen 出现，条件引擎需"假装"ref 有值或返回 false，二者都引入隐性状态。

**本文决策**：禁止 `screen_config` 出现 ref；ref 只在 `trading_config.signals` 和 `exit.rules` 内合法。
**代价**：选股侧无法表达"相对持仓的过滤"——这本就不是选股职责（调仓范式下已有持仓由 `rebalance`/`exit` 管理）。

---

**相关文档**
- akquant 用法：`.trae/rules/akquant/03-strategy-api.md`、`04-backtest-run.md`

