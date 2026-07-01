# 04 · run_backtest 回测入口

> **面向 AI**：`aq.run_backtest(...)` 是**唯一的回测入口**（函数，非类）。本文给完整参数表、A 股实盘规则组合、broker_profile、fill_policy。源码：`akquant-0.2.47/python/akquant/backtest/engine.py`（`run_backtest`）。


## 1. 策略传入方式

| 方式 | 传法 |
|---|---|
| 类式（推荐） | `strategy=MyStrategy`（也可 `strategy=MyStrategy(fast=5)` 实例式） |
| 函数式 | `strategy=on_bar_func`，回调（`on_start`/`on_stop`/`on_order`/`on_trade`/`on_timer`/`initialize`/`context`）作为 `run_backtest` 的形参传入 |
| 动态加载 | `strategy_source="path/to/strategy.py"` + `strategy_loader="python_plain"`（本项目用 JSON 配置动态生成；集成层待基于统一 Schema 重写） |

## 2. 核心参数表（A 股回测常用的标 ★）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| ★ `data` | DataFrame / Dict[str,DataFrame] / List[Bar] | — | 行情数据，见 [02](./02-data-input.md) |
| ★ `strategy` | Strategy 类/实例/函数 | — | 策略，见 [03](./03-strategy-api.md) |
| `symbols` | str / List[str] | `"BENCHMARK"` | 标的代码；多标的传列表 |
| ★ `initial_cash` | float | 100000 | 初始资金（**始终显式设**） |
| `broker_profile` | str | None | A 股费率模板，见 §3 |
| `commission_rate` | float | 0.0 | 佣金率（如 0.0003=万三），按百分比 |
| `commission_policy` | dict | None | `{"type":"percent\|fixed\|per_unit","value":...}`，优先级高于 `commission_rate` |
| `stamp_tax_rate` | float | 0.0 | **印花税（仅卖出）**，A 股 0.001 |
| `transfer_fee_rate` | float | 0.0 | 过户费率 |
| `min_commission` | float | 0.0 | 单笔最低佣金（A 股 5 元） |
| `slippage` | float / dict | None | 滑点；**推荐 dict**，见 §4 |
| `volume_limit_pct` | float | 0.25 | 单 bar 最大成交占当根成交量比例 |
| ★ `t_plus_one` | bool | False | **A 股务必 True**（不能当日卖） |
| ★ `lot_size` | int / Dict[str,int] | 1 | **A 股务必 100** |
| `timezone` | str | `"Asia/Shanghai"` | 时区 |
| `warmup_period` | int | 0 | 预热期 bar 数（≥ 策略 get_history 长度） |
| `history_depth` | int | 0 | 历史缓冲深度（0=禁用） |
| `fill_policy` | dict | None | 成交语义，见 §5 |
| `start_time` / `end_time` | str/Timestamp | None | 回测起止时间（naive 按 timezone 解释） |
| `show_progress` | bool | True | 进度条（生产建议 False） |
| `risk_config` | dict / RiskConfig | None | 风控（max_position_pct 等） |
| `strict_strategy_params` | bool | True | 严格校验策略构造参数（未知参数报错） |

> 其余（`strategies_by_slot`/`strategy_max_*`/`portfolio_risk_budget`/`analyzer_plugins`/`on_event`/`custom_matchers` 等）属多策略/风控/流式高级能力，本项目一般不用。

## 3. `broker_profile`：A 股费率一键模板

源码 `_BROKER_PROFILE_TEMPLATES`（engine.py）。注入佣金/印花税/过户费/最低佣金/滑点/成交量限制/手数：

| profile | 佣金率 | 印花税 | 过户费 | 最低佣金 | 滑点 | 量限 | 手数 |
|---|---|---|---|---|---|---|---|
| `cn_stock_miniqmt` | 0.0003 | 0.001 | 0.00001 | 5.0 | percent 0.0002 | 0.2 | 100 |
| `cn_stock_t1_low_fee` | 0.0002 | 0.001 | 0.000005 | 3.0 | percent 0.0001 | 0.25 | 100 |
| `cn_stock_sim_high_slippage` | 0.0003 | 0.001 | 0.00001 | 5.0 | percent 0.001 | 0.1 | 100 |

> ⚠️ **三个 profile 都不含 `t_plus_one`**。用了 broker_profile 仍要**单独传 `t_plus_one=True`**。
> 显式参数（如 `commission_rate=`）优先级**高于** profile，可覆盖。

## 4. 滑点 `slippage`（推荐显式 dict）

```python
slippage={"type": "percent", "value": 0.0002}   # 相对价格 0.02%
slippage={"type": "fixed",   "value": 0.1}      # 绝对价格 0.1 元
slippage={"type": "ticks",   "value": 1}        # N 个最小变动价位（需 instrument tick_size）
slippage={"type": "zero",    "value": 0.0}      # 无滑点
```

> ⚠️ 裸 `float`（如 `slippage=0.0002`）**已弃用**，按 `percent` 语义解析；`slippage=0.2` 会被当成 **20%**（不是 0.2 元）。**一律用 dict**。

## 5. `fill_policy` 成交语义

控制"信号在哪根 bar、用什么价成交"：

```python
fill_policy={"price_basis": "open",  "temporal": "next_event", "bar_offset": 1}  # 下一根开盘成交（默认）
fill_policy={"price_basis": "close", "temporal": "same_cycle", "bar_offset": 0}  # 当根收盘成交
```

| 字段 | 取值 |
|---|---|
| `price_basis` | `open` / `close` / `ohlc4` / `hl2`（预留未实现：mid_quote/vwap_window/twap_window） |
| `temporal` | `same_cycle` / `next_event` |
| `bar_offset` | `0` 或 `1`（`open`→必须 1，`close`→0 或 1，`ohlc4`/`hl2`→必须 1） |

便捷构造：`aq.make_fill_policy(price_basis="open", temporal="next_event")`（关键字参数）。

> A 股日线最常见就是默认的"下一根开盘成交"。若要"当根收盘成交"用 `make_fill_policy(price_basis="close", temporal="same_cycle", bar_offset=0)`。
> ⚠️ 旧参数 `execution_mode`/`timer_execution_policy` **已移除**，统一用 `fill_policy`。

## 6. A 股实盘规则组合（复制即用）

```python
result = aq.run_backtest(
    data=df,
    strategy=MyStrategy,
    symbols="000001.SZ",
    initial_cash=100_000,

    # 方式 A：用模板（推荐）+ 显式开 T+1
    broker_profile="cn_stock_miniqmt",
    t_plus_one=True,

    # 方式 B：或全手填（等价于 cn_stock_miniqmt）
    # commission_rate=0.0003, stamp_tax_rate=0.001, transfer_fee_rate=0.00001,
    # min_commission=5.0, lot_size=100,
    # slippage={"type": "percent", "value": 0.0002}, volume_limit_pct=0.2,

    timezone="Asia/Shanghai",
    warmup_period=30,            # ≥ 策略需要的最大历史窗口
    show_progress=False,
)
```

## 7. warmup_period vs history_depth（易错）

- `warmup_period`：预热 bar 数，预热期内 `on_bar` 仍被调用但通常策略会因历史不足而 return。
- `history_depth`：历史缓冲长度，决定 `get_history(count)` 能取多少（`count` 必须 ≤ depth）。
- 引擎取 `effective_depth = max(warmup_period, history_depth)`，并自动推断策略的 warmup。
- **最简做法**：在策略 `__init__` 里设 `self.warmup_period = 需要的最大窗口`，不用管 history_depth。

## 8. 多标的回测

```python
data = {"000001.SZ": df1, "600000.SH": df2}
result = aq.run_backtest(
    data=data, strategy=MyStrategy,
    symbols=["000001.SZ", "600000.SH"],
    broker_profile="cn_stock_miniqmt", t_plus_one=True, initial_cash=200_000,
)
```

多标的策略用 `order_target_weights`/`order_target_positions`/`rebalance_to_topn` 调仓（见 [03](./03-strategy-api.md) §6.2、[08](./08-recipes-stock-engine.md) 动量轮动配方）。

## 9. 相关分册

- 结果怎么取 → [05-result-metrics.md](./05-result-metrics.md)
- 参数寻优 → [06-optimization.md](./06-optimization.md)
- 防坑（滑点/印花税方向/T+1…）→ [09-pitfalls-conventions.md](./09-pitfalls-conventions.md)
