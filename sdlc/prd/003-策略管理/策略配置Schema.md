# 3. JSON 策略配置 Schema

## 3.1 设计理念

### 核心模型：条件树 + 表达式节点

信号条件采用两层结构：

- **外层**：递归嵌套的 AND/OR 条件树（业内标准范式，与 json-rules-engine、MS RulesEngine 一致）
- **内层**：统一的表达式节点（Expression Node），替代多类型 target 模型

**表达式节点只有 3 种形态**，可递归组合出任意算术逻辑：

```jsonc
// ① 静态值
{ "value": 70 }

// ② 因子引用
{ "factor": "RSI", "params": { "timeperiod": 14 } }

// ③ 算术运算（递归，左右都是表达式节点）
{ "op": "*", "left": { "factor": "VOL_MA", "params": {"timeperiod": 20} }, "right": { "value": 1.5 } }
```


## 3.2 完整结构

```json
{
  "strategy_id": "dual_ma_vol_boost_001",
  "name": "双均线+成交量放大策略",

  "history_depth": 60,

  "signals": {
    "buy": {
      "operator": "AND",
      "conditions": [
        {
          "type": "compare",
          "left": { "factor": "MA", "params": { "timeperiod": 5 } },
          "comparator": "cross_up",
          "right": { "factor": "MA", "params": { "timeperiod": 20 } }
        },
        {
          "type": "compare",
          "left": { "factor": "VOL_MA", "params": { "timeperiod": 5 } },
          "comparator": ">",
          "right": {
            "op": "*",
            "left": { "factor": "VOL_MA", "params": { "timeperiod": 20 } },
            "right": { "value": 1.5 }
          }
        },
        {
          "operator": "OR",
          "conditions": [
            {
              "type": "compare",
              "left": { "factor": "RSI", "params": { "timeperiod": 14 } },
              "comparator": ">",
              "right": { "value": 50 }
            },
            {
              "type": "compare",
              "left": { "factor": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "output_index": 2 },
              "comparator": ">",
              "right": { "value": 0 }
            }
          ]
        }
      ]
    },
    "sell": {
      "operator": "OR",
      "conditions": [
        {
          "type": "compare",
          "left": { "factor": "MA", "params": { "timeperiod": 5 } },
          "comparator": "cross_down",
          "right": { "factor": "MA", "params": { "timeperiod": 20 } }
        },
        {
          "type": "compare",
          "left": { "factor": "CLOSE" },
          "comparator": "<",
          "right": {
            "op": "*",
            "left": { "factor": "BOLL", "params": { "timeperiod": 20, "nbdevup": 2, "nbdevdn": 2 }, "output_index": 2 },
            "right": { "value": 0.95 }
          }
        }
      ]
    }
  },

  "position_sizing": {
    "method": "order_target_percent",
    "buy_percent": 0.95,
    "sell_action": "close_position"
  },

  "backtest_config": {
    "initial_cash": 100000,
    "broker_profile": "cn_stock_miniqmt",
    "t_plus_one": true,
    "history_depth": 60,
    "warmup_period": 20,
    "commission_rate": 0.0003,
    "stamp_tax_rate": 0.001,
    "min_commission": 5.0,
    "slippage": { "type": "percent", "value": 0.001 }
  }
}
```

---

## 3.3 Schema 设计要点

| 字段 | 设计意图 | 说明 |
|---|---|---|
| `signals` | **递归嵌套条件树** | AND/OR 可任意嵌套，叶子节点为 `compare` 条件 |
| `compare` | **统一比较条件** | 左右两侧都是表达式节点，一种类型覆盖所有比较场景 |
| 表达式节点 | **3 种形态递归组合** | `value`（静态值）、`factor`（因子引用）、`op`（算术运算） |
| `output_index` | 多输出指标取值 | MACD 用 `2` 取 hist，KDJ 用 `2` 取 J，BBANDS 用 `0` 取 upper |
| `position_sizing.method` | 映射 Strategy 交易方法 | `"order_target_percent"` / `"buy"` / `"buy_all"` / `"order_target_value"` |
| `backtest_config` | 直接映射 `run_backtest()` 参数 | 1:1 映射，无需转换 |

---

## 3.4 信号条件模型

### 3.4.1 逻辑组合节点

```json
{
  "operator": "AND",
  "conditions": [
    { /* compare 叶子节点 */ },
    { /* 嵌套逻辑组 */ "operator": "OR", "conditions": [...] }
  ]
}
```

- `operator`: `"AND"`（全部满足）/ `"OR"`（任一满足）
- `conditions`: 子条件数组，元素可以是 `compare` 叶子节点或嵌套逻辑组（递归，无层级限制）

### 3.4.2 叶子条件：`compare`

左右两侧都是表达式节点，通过 `comparator` 比较。

```json
{
  "type": "compare",
  "left": { /* 任意表达式节点 */ },
  "comparator": ">",
  "right": { /* 任意表达式节点 */ }
}
```

| 字段 | 说明 |
|---|---|
| `left` | 左侧表达式节点 |
| `comparator` | 比较操作符（见 3.4.4） |
| `right` | 右侧表达式节点 |

### 3.4.3 表达式节点（Expression Node）

表达式节点是值的统一建模，有 3 种互斥形态：

#### ① 静态值

```json
{ "value": 70 }
```

用于固定阈值，如 RSI > 70、MACD > 0。

#### ② 因子引用

```json
{ "factor": "RSI", "params": { "timeperiod": 14 } }
```

| 字段 | 说明 |
|---|---|
| `factor` | 因子类型名（FACTOR_REGISTRY 中的 key） |
| `params` | 因子参数，透传给 talib 函数 |
| `inputs` | 可选，覆盖默认 inputs（如 `["volume"]`） |
| `output_index` | 可选，多输出指标取第几个返回值 |

#### ③ 算术运算

```json
{
  "op": "*",
  "left": { "factor": "VOL_MA", "params": { "timeperiod": 20 } },
  "right": { "value": 1.5 }
}
```

| 字段 | 说明 |
|---|---|
| `op` | 算术操作符：`"+"` `"-"` `"*"` `"/"` |
| `left` | 左操作数（表达式节点，可递归嵌套） |
| `right` | 右操作数（表达式节点，可递归嵌套） |

除法时若右操作数为 0，安全降级返回 0。

### 3.4.4 比较操作符

| comparator | 含义 | 说明 |
|---|---|---|
| `>` | 大于 | |
| `<` | 小于 | |
| `>=` | 大于等于 | |
| `<=` | 小于等于 | |
| `==` | 等于 | |
| `!=` | 不等于 | |
| `cross_up` | 上穿（金叉） | 当前 left > right 且上一根 bar left <= right |
| `cross_down` | 下穿（死叉） | 当前 left < right 且上一根 bar left >= right |

`cross_up` / `cross_down` 要求 left 和 right 都是因子引用（`{factor: ...}`），不支持算术表达式参与穿越判断。实现需要 Strategy 维护上一根 bar 的因子值快照，见 Strategy Factory 设计文档。

### 3.4.5 表达式示例总览

| 场景 | left | comparator | right |
|---|---|---|---|
| RSI > 70 | `{ "factor": "RSI", "params": {"timeperiod": 14} }` | `>` | `{ "value": 70 }` |
| MA5 上穿 MA20 | `{ "factor": "MA", "params": {"timeperiod": 5} }` | `cross_up` | `{ "factor": "MA", "params": {"timeperiod": 20} }` |
| 成交量 > 均量×1.5 | `{ "factor": "VOL_MA", "params": {"timeperiod": 5} }` | `>` | `{ "op": "*", "left": {"factor":"VOL_MA","params":{"timeperiod":20}}, "right": {"value":1.5} }` |
| 收盘价 < 布林下轨×0.95 | `{ "factor": "CLOSE" }` | `<` | `{ "op": "*", "left": {"factor":"BOLL","params":{"timeperiod":20},"output_index":2}, "right": {"value":0.95} }` |
| 价格/均线比 > 1.05 | `{ "op": "/", "left": {"factor":"CLOSE"}, "right": {"factor":"MA","params":{"timeperiod":20}} }` | `>` | `{ "value": 1.05 }` |
| MA5-MA20 > ATR×2 | `{ "op": "-", "left": {"factor":"MA","params":{"timeperiod":5}}, "right": {"factor":"MA","params":{"timeperiod":20}} }` | `>` | `{ "op": "*", "left": {"factor":"ATR","params":{"timeperiod":14}}, "right": {"value":2} }` |
| 价格 < 入场价-2×ATR | `{ "factor": "CLOSE" }` | `<` | `{ "op": "-", "left": {"ref":"entry_price"}, "right": {"op":"*","left":{"factor":"ATR","params":{"timeperiod":14}},"right":{"value":2}} }` |

最后一条中 `{ "ref": "entry_price" }` 引用持仓信息，属于扩展能力，见 3.6 节。

---

## 3.5 支持的因子类型（映射 akquant.talib）

因子分类对齐 TA-Lib 官方分组（`talib.get_function_groups()`），详见 [05-FactorRegistry设计](05-FactorRegistry前端因子注册表设计.md)。

### 重叠研究（Overlap Studies）

| JSON type | akquant.talib 函数 | inputs | 多输出 | 备注 |
|---|---|---|---|---|
| `MA` | `talib.MA` | `["close"]` | 否 | 支持 `matype` 参数选择均线类型（0=SMA,1=EMA,2=WMA,…），默认 matype=0 即 SMA |
| `EMA` | `talib.EMA` | `["close"]` | 否 | |
| `BOLL` | `talib.BBANDS` | `["close"]` | 是，返回 `(upper, mid, lower)` | `output_index: 0/1/2`；factorKey 为 BOLL 但底层函数为 BBANDS |
| `SAR` | `talib.SAR` | `["high", "low"]` | 否 | |

### 动量指标（Momentum Indicators）

| JSON type | akquant.talib 函数 | inputs | 多输出 | 备注 |
|---|---|---|---|---|
| `MACD` | `talib.MACD` | `["close"]` | 是，返回 `(dif, dea, hist)` | `output_index: 0/1/2` |
| `RSI` | `talib.RSI` | `["close"]` | 否 | |
| `STOCH` | `talib.STOCH` | `["high", "low", "close"]` | 是，返回 `(K, D)` | `output_index: 0/1`；K=slowk, D=slowd；J 值由前端通过表达式组合 `3*K-2*D` 自动生成 |
| `ADX` | `talib.ADX` | `["high", "low", "close"]` | 否 | |
| `PLUS_DI` | `talib.PLUS_DI` | `["high", "low", "close"]` | 否 | |
| `MINUS_DI` | `talib.MINUS_DI` | `["high", "low", "close"]` | 否 | |
| `WILLR` | `talib.WILLR` | `["high", "low", "close"]` | 否 | |
| `CCI` | `talib.CCI` | `["high", "low", "close"]` | 否 | |

### 波动率指标（Volatility Indicators）

| JSON type | akquant.talib 函数 | inputs | 多输出 | 备注 |
|---|---|---|---|---|
| `ATR` | `talib.ATR` | `["high", "low", "close"]` | 否 | |

### 成交量指标（Volume Indicators）

| JSON type | akquant.talib 函数 | inputs | 多输出 | 备注 |
|---|---|---|---|---|
| `OBV` | `talib.OBV` | `["close", "volume"]` | 否 | |
| `VOL_MA` | `talib.MA` | `["volume"]` | 否 | MA 函数输入源改为 volume |
| `VOL_EMA` | `talib.EMA` | `["volume"]` | 否 | EMA 函数输入源改为 volume |
| `VOLUME` | 直接取值 | `["volume"]` | 否 | 当前成交量 |

### 价格指标（Price — 自定义，非 TA-Lib 标准分类）

| JSON type | akquant.talib 函数 | inputs | 多输出 | 备注 |
|---|---|---|---|---|
| `CLOSE` | 直接取值 | `["close"]` | 否 | 当前收盘价 |
| `HIGH` | 直接取值 | `["high"]` | 否 | 当前最高价 |
| `LOW` | 直接取值 | `["low"]` | 否 | 当前最低价 |


---

## 3.6 扩展表达式节点

除核心 3 种形态外，预留以下扩展节点，按需实现：

### `ref` — 持仓/状态引用

引用当前持仓信息，用于动态止损等场景。

```json
{ "ref": "entry_price" }
```

| ref 值 | 含义 |
|---|---|
| `entry_price` | 当前持仓的入场均价 |
| `position_pnl_pct` | 当前持仓盈亏百分比 |
| `position_qty` | 当前持仓数量 |
| `bars_held` | 当前持仓已持有 bar 数 |

示例：ATR 动态止损 — 收盘价跌破入场价 - 2×ATR

```json
{
  "type": "compare",
  "left": { "factor": "CLOSE" },
  "comparator": "<",
  "right": {
    "op": "-",
    "left": { "ref": "entry_price" },
    "right": { "op": "*", "left": { "factor": "ATR", "params": {"timeperiod": 14} }, "right": { "value": 2 } }
  }
}
```

---

## 3.7 支持的仓位管理方法

| method | 映射 Strategy 方法 | 参数 |
|---|---|---|
| `order_target_percent` | `self.order_target_percent(pct)` | `buy_percent` |
| `buy` | `self.buy(quantity=N)` | `buy_quantity` |
| `buy_all` | `self.buy_all()` | 无 |
| `order_target_value` | `self.order_target_value(val)` | `buy_value` |
| `close_position` | `self.close_position()` | 无（sell_action 用） |
| `sell` | `self.sell(quantity=N)` | `sell_quantity` |

---

## 3.8 场景覆盖度

### 已覆盖的常用场景（~95%）

| 场景 | 示例 |
|---|---|
| 均线金叉/死叉 | MA5 cross_up MA20 |
| RSI/MACD 阈值判断 | RSI > 70, MACD柱 > 0 |
| 成交量放量/缩量 | VOL > VOL_MA20 × 1.5 |
| 价格与均线偏离 | CLOSE < MA20 × 0.95 |
| 布林带突破/跌破 | CLOSE > BOLL上轨, CLOSE < BOLL下轨 × 0.95 |
| 多指标组合 | AND/OR 任意嵌套 |
| 三值比较（价差判断） | MA5 - MA20 > ATR × 2 |
| 因子比值 | CLOSE / MA20 > 1.05（价格偏离度） |
| 嵌套算术 | (HIGH - LOW) / CLOSE > 0.03（振幅筛选） |
| 动态止损 | CLOSE < entry_price - 2 × ATR |

### 尚未覆盖的进阶场景（后续迭代）

| 场景 | 说明 | 实现思路 |
|---|---|---|
| 多时段条件 | 日线 MA 金叉且 60 分钟 RSI < 30 | 数据层提供多周期合并 DataFrame，因子加 period 维度 |
| N 根 bar 内穿越 | 3 根 bar 内 MA5 上穿 MA20 | 扩展 cross_up 支持 `window` 参数 |
| 条件计数 | 近 10 根 bar 内 RSI < 30 出现 3 次 | 新增 `count` 聚合节点 |
| 最高/最低回溯 | 自买入后最高价回撤 > 5% 止损 | 新增 `highest_since` / `lowest_since` ref 类型 |
