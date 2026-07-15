# 03 · Strategy 策略 API

> **面向 AI**：写策略看这篇。`Strategy` 基类的生命周期回调、`bar` 对象、历史/持仓/资金查询、下单方法全家桶。源码：`akquant-0.2.47/python/akquant/strategy.py`。

## 1. 最小策略骨架

```python
from akquant import Strategy, Bar

class MyStrategy(Strategy):
    def __init__(self, fast: int = 5, slow: int = 20):
        super().__init__()
        self.fast = fast
        self.slow = slow
        self.warmup_period = slow      # ⚠️ 必须 ≥ on_bar 里 get_history 的 count

    def on_bar(self, bar: Bar):
        closes = self.get_history(self.slow, field="close")   # np.ndarray，含当前 bar
        if len(closes) < self.slow:
            return
        # ... 指标 + 下单逻辑
```

要点：① 子类 `__init__` 要 `super().__init__()`；② 需要历史数据时务必设 `self.warmup_period`（或 `set_history_depth`），否则 `get_history` 取不到足够数据。

## 2. 生命周期回调（按需重写）

| 回调 | 签名 | 触发时机 |
|---|---|---|
| `on_start()` | 无参 | 策略启动一次 |
| `on_bar(bar)` | **主回调** | 每根 K 线 |
| `on_tick(tick)` | | 每个 tick（tick 回测才触发） |
| `on_order(order)` | | 订单状态变化 |
| `on_trade(trade)` | | 成交 |
| `on_reject(order)` | | 拒单 |
| `on_stop()` | | 策略结束一次 |
| `on_session_start(session, ts)` | | 交易时段开始 |
| `on_session_end(session, ts)` | | 交易时段结束 |
| `on_before_trading(date, ts)` | | 盘前 |
| `on_after_trading(date, ts)` | | 盘后 |
| `on_daily_rebalance(date, ts)` | | 每日再平衡（每天最多一次） |
| `on_daily_rebalance_after_bar(date, ts)` | | 完整时间片后的每日再平衡 |
| `on_pre_open(event)` | | 开盘前（默认 next-open 成交语义） |
| `on_portfolio_update(snapshot)` | | 账户变化 |
| `on_error(error, source, payload=None)` | | 错误 |
| `on_expiry(event)` | | 到期结算（期权/期货，本项目不用） |
| `on_timer(payload)` | | 定时器触发（配合 `schedule`/`add_daily_timer`） |

> A 股日线回测 99% 只用 `on_bar`（必要时加 `on_start`/`on_stop`）。

## 3. `bar` 对象与快捷属性

`bar` 是 Rust 原生对象，字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `timestamp` | int | UTC 纳秒 |
| `timestamp_iso` | str | ISO 时间字符串 |
| `symbol` | str | 标的代码 |
| `open`/`high`/`low`/`close`/`volume` | float | OHLCV |
| `extra` | dict[str,float] | 扩展字段 |

策略内可用快捷属性（代理当前 bar）：`self.symbol`、`self.close`、`self.open`、`self.high`、`self.low`、`self.volume`、`self.now`(本地时间 Timestamp)。

## 4. 历史数据访问

```python
closes = self.get_history(count=20, field="close")                 # np.ndarray，含当前 bar
ohlcv   = self.get_history_df(count=20)                            # DataFrame(O,H,L,C,V)
close_map = self.get_history_map(count=20, symbols=["A","B"])      # Dict[str, np.ndarray]
```

| 方法 | 说明 |
|---|---|
| `get_history(count, symbol=None, field="close")` | 取最近 `count` 根 bar 的某字段（open/high/low/close/volume），返回 np.ndarray。**含当前 bar**。 |
| `get_history_df(count, symbol=None)` | OHLCV DataFrame |
| `get_history_map(count, symbols, field="close")` | 多标的批量 |
| `set_history_depth(depth)` | 设置历史缓冲长度（0=禁用）。**`count` 必须 ≤ depth** |
| `set_rolling_window(train_window, step)` | ML 滚动训练窗口（本项目一般不用） |
| `get_rolling_data(length=None, symbol=None)` | 取滚动训练数据 `(X, y)` |

> ⚠️ **get_history 能否取到足够数据，取决于 `warmup_period`（类属性/构造里设）与 `history_depth`（run_backtest 参数）的较大者**。建议直接在 `__init__` 里 `self.warmup_period = 你需要的最大窗口`。

## 5. 持仓与资金查询

```python
pos        = self.get_position()                 # 当前标的持仓数量（正多/负空），默认当前 symbol
pos        = self.get_position("000001.SZ")      # 指定标的
available  = self.get_available_position()       # 可用持仓（T+1 扣减后）⚠️ A 股卖出前看这个
all_pos    = self.get_positions()                # Dict[symbol, qty]
held_bars  = self.hold_bar()                     # 当前持仓已持有多少根 bar
cash       = self.get_cash()                     # 可用现金
equity     = self.get_portfolio_value()          # 总权益 = 现金 + 持仓市值（等同 self.equity）
account    = self.get_account()                  # 账户详情 dict（cash/equity/market_value/margin/...）
```

`self.position` 返回 `Position` 辅助对象：`self.position.size`（总持仓）、`self.position.available`（可用）。

## 6. 下单方法（核心）

返回值：`buy`/`sell`/`submit_order`/`place_trailing_stop*`/`place_bracket_order` 返回 `order_id`(str)；`order_target*` 返回 `Optional[str]` 或 `List[str]`；`buy_all`/`close_position`/`short`/`cover`/`stop_buy`/`stop_sell` 返回 `None`。

### 6.1 基础买卖

```python
self.buy(symbol=None, quantity=100, price=None, ...)       # 买入；quantity 不填用 Sizer
self.sell(symbol=None, quantity=100, price=None, ...)      # 卖出；quantity 不填默认清当前标的持仓
self.buy_all(symbol=None)                                  # 全仓买入（用尽可用资金）
self.close_position(symbol=None)                           # 平仓
self.short(symbol=None, quantity=100, ...)                 # 卖出开空（A股不可用）
self.cover(symbol=None, quantity=100, ...)                 # 买入平空
```

可选参数：`price`(限价，None=市价)、`time_in_force`、`trigger_price`(触发价)、`tag`(标签)、`fill_policy`/`slippage`/`commission`(订单级覆盖)。

### 6.2 目标仓位（**最常用**，自动算差额下单）

```python
self.order_target(target=200)                              # 调到目标数量
self.order_target_value(target_value=50000)                # 调到目标市值
self.order_target_percent(target_percent=0.95)             # 调到目标百分比（0.95=95%）
self.order_target_weights({"A": 0.5, "B": 0.5},            # 多标的目标权重
                          liquidate_unmentioned=True, allow_leverage=False)
self.order_target_positions({"A": 100, "B": -50})          # 多标的目标数量（支持负=空）
```

- `symbol` 均可省略（默认当前 bar 的 symbol）。
- A 股 `order_target_percent` 会自动按 `lot_size=100` 向下取整。

### 6.3 止损/止盈/追踪/括号单

```python
self.stop_buy(trigger_price=10.0, quantity=100)            # 突破触发买入
self.stop_sell(trigger_price=8.0, quantity=100)            # 跌破触发卖出
self.place_trailing_stop(symbol, quantity=100, trail_offset=0.5)              # 追踪止损
self.place_bracket_order(symbol, quantity=100,
                         stop_trigger_price=8.0, take_profit_price=12.0)      # 括号单（止损+止盈+OCO）
```

### 6.4 订单管理

```python
self.get_open_orders(symbol=None)     # 未完成订单
self.get_order(order_id)              # 订单详情
self.cancel_order(order_id)
self.cancel_all_orders(symbol=None)
self.get_trades()                     # 已平仓交易列表
```

## 7. 指标：实时算 vs 注册

### 方式 A：实时计算（**推荐，最直观**）

```python
import akquant.talib as talib

def on_bar(self, bar):
    closes = self.get_history(30, field="close")
    ma5  = talib.MA(closes, timeperiod=5)
    macd, signal, hist = talib.MACD(closes)
```

### 方式 B：注册指标（框架托管，precompute/incremental）

```python
def on_start(self):
    self.register_indicator("ma5", MyIndicator(...))   # 等同 register_precomputed_indicator

def on_bar(self, bar):
    v = self.ma5.value
```

- `register_indicator(name, indicator)` / `register_precomputed_indicator(...)`：预计算模式（`indicator_mode="precompute"`，默认）。
- `register_incremental_indicator(name, indicator, source="close", symbols=None, warmup_bars=0, indicator_factory=None, input_mode="source")`：增量模式（需 `indicator_mode="incremental"`）。
- `record_indicator(name, value, ...)`：记录指标点用于可视化/导出（不参与决策）。

> **本项目策略绝大多数用方式 A**（on_bar 内 get_history + talib）。指标函数签名见 [07-talib-indicators.md](./07-talib-indicators.md)。

## 8. 时间与定时器（日内/多日策略才用）

```python
self.schedule("2024-03-01 14:55:00", "close_all")     # 单次定时任务
self.add_daily_timer("14:55:00", "rebalance")         # 每日定时
self.to_local_time(timestamp_ns)                       # UTC 纳秒 → 本地 Timestamp
self.format_time(timestamp_ns, fmt="%Y-%m-%d %H:%M:%S")
```

## 9. 运行时配置（类属性可覆盖）

```python
class S(Strategy):
    warmup_period = 20                # 预热期
    timezone = "Asia/Shanghai"
    indicator_mode = "precompute"     # 或 "incremental"
    error_mode = "raise"              # raise / continue / legacy
```

`runtime_config`(StrategyRuntimeConfig) 字段：`enable_precise_day_boundary_hooks`、`portfolio_update_eps`、`error_mode`、`re_raise_on_error`、`indicator_mode`。

## 10. 函数式策略（快速原型）

```python
def on_bar(ctx, bar):
    closes = ctx.get_history(20, field="close")
    if closes.mean() > bar.close:
        ctx.order_target_percent(bar.symbol, 0.95)

result = aq.run_backtest(
    data=df, strategy=on_bar,
    on_start=lambda ctx: None,         # 可选回调都从 run_backtest 传
    context={"fast": 5},               # 注入到 ctx 的属性
)
```

> 函数式内部被包成 `FunctionalStrategy`。正式策略建议用类式（可带 `__init__` 参数，便于 [06](./06-optimization.md) 的参数寻优）。

## 11. 相关分册

- 指标函数签名 → [07-talib-indicators.md](./07-talib-indicators.md)
- 跑回测的参数 → [04-backtest-run.md](./04-backtest-run.md)
- 可落地策略配方 → [08-recipes-stock-engine.md](./08-recipes-stock-engine.md)

## 12. 范式选型约束（spec 009-strategy-paradigm-exclusive）

> **面向 AI**：stock-engine 封装层对 akquant 的两类回调有明确边界，写策略/配置时必须遵守。

### 12.1 两类范式互斥

| 范式 | 触发字段 | akquant 回调 | 决策范围 | universe 规模 |
|---|---|---|---|---|
| 择时（timing） | `trading_config.signals` | `on_bar` | 当前 bar 的单个 symbol | ≤ 10 只（manual 池） |
| 轮动（rotation） | `trading_config.rebalance` | `on_daily_rebalance` | 全 universe 截面 | 不限 |

`signals` 与 `rebalance` **不能同时在场**，否则校验报错 `SIGNALS_REBALANCE_EXCLUSIVE`。

### 12.2 为什么互斥

akquant 同一 bar 内 `on_daily_rebalance` 先于 `on_bar` 执行，两者订单进入同一撮合队列合计（非覆盖语义）。若两个回调对同一标的下目标仓位类订单，订单会叠加，目标仓位可能被推到 100% 以上甚至触发杠杆。

### 12.3 为什么 signals 限定 manual ≤ 10

signals 范式对每个命中 symbol 独立 `order_target_percent`，多标的 universe 下首只吃光资金、其余整单 Reject，且回测结果不可复现。多标的横截面场景必须走 rebalance + `rebalance_to_topn` / `order_target_weights`。

### 12.4 选型建议

- 单标的/少量标的（行业龙头组合、ETF 轮动小池）择时 → signals
- csi300/csi500 等宽基横截面选股轮动 → rebalance
- 不要尝试"横截面选股 + 选中标的择时"联动，akquant 当前封装不支持

### 12.5 轮动范式 point-in-time 成分股要求

> **面向 AI**：轮动范式（rebalance）的 universe 为 csi300/csi500 时，**必须**在每个调仓日做 point-in-time 成分股过滤，否则会产生 lookahead bias。

#### 问题
watcher 的 `resolveBacktestSymbols` 取回测区间成分股**并集**（`getConstituentsInRange`），用于一次性准备全量 K 线数据。但 engine 在每个调仓日决策时，候选池必须限定为「该日实际入选成分股」——否则回测早期可用「未来才入选」的股票做决策。

#### 解决方案（spec 010-rotation-data-governance）
1. watcher 提供只读内部接口 `POST /api/internal/constituents/query`，返回 ≤ trade_date 的最新生效日成分股快照。
2. engine 通过 `WatcherClient.get_constituents_at(index_code, trade_date)` 在每个调仓日查询。
3. `rebalance_engine.select_at_rebalance_date` 在 `_build_candidates` 之前过滤 kline_map。

#### 边界约束分层
- 数据单源性（强）：engine 永不直接读写业务数据库。
- 行情/基本面（强）：由 watcher 预传，engine 不反向拉取。
- **参考数据（弱，例外）**：成分股身份允许 engine 查询 watcher 的 `/api/internal/*` 只读端点。

#### 降级
未配置 `WATCHER_BASE_URL` 时，`watcher_client=None`，跳过 point-in-time 过滤并打 warning（向后兼容，但结果可能含 lookahead bias）。

#### manual universe
universe=manual 时不查接口，直接用 `screen_config.universe.stocks` 列表（≤ 10 只，无成分股泄露问题）。
