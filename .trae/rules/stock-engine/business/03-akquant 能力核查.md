# akquant 能力核查报告

## 核查目标

确认 akquant 回测引擎在以下层面的能力边界，用于决定因子计算架构：
- 如果 akquant 内置了技术指标计算 → 全部使用 akquant 内置的计算能力，不写任何指标计算
- 如果 akquant 不内置 → 需要自行实现因子计算函数

**结论：akquant 内置了全部 12 个所需技术指标，且提供完整的回测/优化/报告体系，无需自行实现任何指标计算。**

---

## 1. 技术指标计算能力

akquant 通过 `akquant.talib` 模块提供 TA-Lib 兼容的指标计算接口，底层由 Rust 实现（高性能），共计103 个指标函数。

**提供方式：b) 单独的指标模块 `akquant.talib`**，同时也在 Strategy 内支持增量/预计算两种指标注册模式。

所有指标函数签名统一为：`func(close, ..., backend="auto", as_series=True)`

### 逐项确认

| 指标 | 是否内置 | 调用方式 | 备注 |
|---|---|---|---|
| MA（简单移动均线） | **是** | `akquant.talib.MA(close, timeperiod=5)` 或 `SMA(close, timeperiod=5)` | `funcs.py:1275` (SMA), `funcs.py:1295` (MA) |
| EMA（指数移动均线） | **是** | `akquant.talib.EMA(close, timeperiod=12)` | `funcs.py:1316` |
| MACD | **是** | `akquant.talib.MACD(close, fastperiod=12, slowperiod=26, signalperiod=9)` → 返回 `(dif, dea, hist)` | `funcs.py:2597` |
| RSI | **是** | `akquant.talib.RSI(close, timeperiod=14)` | `funcs.py:1243` |
| KDJ | **是** | `akquant.talib.STOCH(high, low, close, ...)` → ���回 `(K, D)`；J = `3*K - 2*D` | 以 STOCH（随机振荡器）形式提供，`funcs.py:2843` |
| BOLL（布林带） | **是** | `akquant.talib.BBANDS(close, timeperiod=20, nbdevup=2, nbdevdn=2)` → 返回 `(upper, middle, lower)` | 函数名为 BBANDS，`funcs.py:2731` |
| ATR | **是** | `akquant.talib.ATR(high, low, close, timeperiod=14)` | `funcs.py:2401` |
| OBV | **是** | `akquant.talib.OBV(close, volume)` | `funcs.py:2938` |
| WR（威廉指标） | **是** | `akquant.talib.WILLR(high, low, close, timeperiod=14)` | 函数名为 WILLR，`funcs.py:462` |
| CCI | **是** | `akquant.talib.CCI(high, low, close, timeperiod=14)` | `funcs.py:494` |
| DMI | **是** | `akquant.talib.ADX(high, low, close, timeperiod=14)` + `PLUS_DI(...)` + `MINUS_DI(...)` | 分为三个函数组合使用，`funcs.py:532/681/730` |
| SAR | **是** | `akquant.talib.SAR(high, low, acceleration=0.02, maximum=0.2)` | `funcs.py:3335` |

### 使用示例

```python
import akquant as aq
import akquant.talib as talib
import numpy as np

# 在 Strategy.on_bar 中使用
class MyStrategy(aq.Strategy):
    def on_bar(self, bar):
        closes = self.get_history(30, field="close")  # numpy array
        ma5 = talib.MA(closes, timeperiod=5)
        ema12 = talib.EMA(closes, timeperiod=12)
        dif, dea, hist = talib.MACD(closes)
        rsi = talib.RSI(closes, timeperiod=14)
        upper, mid, lower = talib.BBANDS(closes, timeperiod=20)
```

### 指标提供方式的三个层次

- **a) Strategy 内注册模式** — `self.register_indicator("ma5", indicator)` 在 on_bar 中通过 `self.ma5.value` 访问
- **b) 独立 talib 模块** — `akquant.talib.SMA(close, 5)` 直接对 numpy array 计算
- **c) 不依赖第三方库** — 所有指标由 Rust 原生实现，无需安装 ta-lib / pandas-ta

---

## 2. Strategy 类的 on_bar 上下文

Strategy 基类定义于 `python/akquant/strategy.py:287`。

### bar 对象字段

`bar` 是 Rust/PyO3 原生类型，定义于 `akquant.pyi:356`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `timestamp` | `int` | Unix 时间戳（纳秒） |
| `symbol` | `str` | 标的代码 |
| `open` | `float` | 开盘价 |
| `high` | `float` | 最高价 |
| `low` | `float` | 最低价 |
| `close` | `float` | 收盘价 |
| `volume` | `float` | 成交量 |
| `extra` | `dict[str, float]` | 扩展字段 |
| `timestamp_iso` | `str` | ISO 格式时间字符串 |

Strategy 还提供快捷属性：`self.close`, `self.open`, `self.high`, `self.low`, `self.volume`, `self.symbol`

### 历史数据访问

```python
# 获取最近 N 根 bar 的某字段（返回 numpy array）
closes = self.get_history(30, field="close")

# 获取 OHLCV DataFrame
df = self.get_history_df(30)

# 获取多标的历史数据
history_map = self.get_history_map(30, symbols=["000001", "600000"], field="close")
# → Dict[str, np.ndarray]

# 设置历史深度（默认可能有限，大数据量时需要手动设置）
self.set_history_depth(100)
```

### 持仓查询

```python
pos = self.get_position()              # 当前标的持仓数量（正=多，负=空）
pos = self.get_position("000001")      # 指定标的
available = self.get_available_position()  # 可用持仓（T+1 扣减后）
all_pos = self.get_positions()         # Dict[str, float]，所有持仓
bars = self.hold_bar()                 # 当前持仓已持有多少 bar

# 或使用 Position 辅助对象
self.position.size       # 总持仓
self.position.available  # 可用持仓
```

### 交易方法

```python
# 基本交易
self.buy(quantity=100)                    # 买入
self.sell(quantity=100)                   # 卖出
self.buy_all()                            # 全仓买入
self.close_position()                     # 平仓
self.short(quantity=100)                  # 做空
self.cover(quantity=100)                  # 平空

# 目标仓位
self.order_target(200)                    # 调整到目标数量
self.order_target_value(50000)            # 调整到目标市值
self.order_target_percent(0.5)            # 调整到目标百分比
self.order_target_weights({"A": 0.5, "B": 0.5})  # 多标的权重调仓
self.order_target_positions({"A": 100, "B": -50}) # 多标的数量调仓

# 高级订单
self.stop_buy(trigger_price=10.0, quantity=100)    # 止损买入
self.stop_sell(trigger_price=8.0, quantity=100)    # 止损卖出
self.place_trailing_stop(quantity=100, trail_offset=0.5)  # 追踪止损
self.place_bracket_order(quantity=100, stop_trigger_price=8.0, take_profit_price=12.0)  # 括号单

# 订单管理
order_id = self.buy(quantity=100)
self.cancel_order(order_id)
self.cancel_all_orders()
open_orders = self.get_open_orders()
```

### 在 on_bar 中计算指标

**完全支持。** 两种模式：

1. **实时计算**（推荐）：在 on_bar 中调用 `self.get_history()` 获取 numpy array，再用 `akquant.talib` 计算
2. **预计算/增量模式**：通过 `self.register_indicator()` 注册指标，框架自动管理

### 完整生命周期回调

| 回调 | 说明 |
|---|---|
| `on_start()` | 策略启动 |
| `on_bar(bar)` | **主回调**，每根 bar |
| `on_tick(tick)` | Tick 级别 |
| `on_order(order)` | 订单状态更新 |
| `on_trade(trade)` | 成交回调 |
| `on_session_start(session, ts)` | 交易时段开始 |
| `on_session_end(session, ts)` | 交易时段结束 |
| `on_before_trading(date, ts)` | 盘前 |
| `on_after_trading(date, ts)` | 盘后 |
| `on_daily_rebalance(date, ts)` | 每日再平衡 |
| `on_stop()` | 策略结束 |

---

## 3. 回测 API 调用方式

### run_backtest 完整签名

```python
akquant.run_backtest(
    # 数据输入
    data=None,                    # DataFrame / Dict[str, DataFrame] / List[Bar] / DataFeed
    # 策略
    strategy=None,                # Strategy 类 / 实例 / on_bar 函数
    strategy_source=None,         # 策略文件路径
    # 标的
    symbols="BENCHMARK",          # str / List[str]
    # 资金与费用
    initial_cash=None,            # 初始资金
    commission_rate=None,         # 佣金率
    stamp_tax_rate=None,          # 印花税率（仅卖出）
    transfer_fee_rate=None,       # 过户费率
    min_commission=None,          # 最低佣金
    slippage=None,                # 滑点 {"type": "percent/fixed/ticks/zero", "value": 0.001}
    # 市场规则
    t_plus_one=False,             # T+1 限制
    lot_size=None,                # 手数
    # 时间范围
    start_time=None,              # 开始时间
    end_time=None,                # 结束时间
    # 历史数据
    history_depth=None,           # 历史数据深度
    warmup_period=0,              # 预热期
    # Broker 模板
    broker_profile=None,          # "cn_stock_miniqmt" / "cn_stock_t1_low_fee" 等
    # 事件回调（函数式策略用）
    on_start=None, on_stop=None, on_order=None, on_trade=None, ...
    # 高级
    config=None,                  # BacktestConfig 对象
    risk_config=None,             # 风控配置
    show_progress=None,           # 进度条
    **kwargs,
) -> BacktestResult
```

### 数据输入格式

```python
# 单标的 DataFrame（必须包含 datetime index + OHLCV 列）
df = pd.DataFrame({
    'open': [...], 'high': [...], 'low': [...],
    'close': [...], 'volume': [...]
}, index=pd.DatetimeIndex([...]))

# 多标的
data = {
    "000001": df_000001,
    "600000": df_600000,
}
```

### 策略传入方式

```python
# 方式1：类引用（推荐）
result = aq.run_backtest(data=df, strategy=MyStrategy, initial_cash=100000)

# 方式2：类实例
result = aq.run_backtest(data=df, strategy=MyStrategy(param1=5), initial_cash=100000)

# 方式3：函数式（快速原型）
def on_bar(ctx, bar):
    ctx.buy(quantity=100)
result = aq.run_backtest(data=df, strategy=on_bar, initial_cash=100000)
```

### 佣金/滑点/印花税

```python
# 方式1：直接指定
result = aq.run_backtest(
    data=df, strategy=MyStrategy,
    commission_rate=0.0003,    # 佣金率万三
    stamp_tax_rate=0.001,      # 印花税千一（仅卖出）
    transfer_fee_rate=0.00001, # 过户费
    min_commission=5.0,        # 最低佣金5元
    slippage={"type": "percent", "value": 0.001},  # 千一滑点
)

# 方式2：使用 broker 模板
result = aq.run_backtest(
    data=df, strategy=MyStrategy,
    broker_profile="cn_stock_miniqmt",  # 预设中国A股费率
)
```

### 多标的回测

**支持。** 传入 `Dict[str, DataFrame]` 即可：

```python
result = aq.run_backtest(
    data={"000001": df1, "600000": df2},
    strategy=MyStrategy,
    initial_cash=100000,
)
```

### 基准对比

**支持。** 在报告生成时指定：

```python
aq.plot.plot_report(result, benchmark="000300")  # 沪深300为基准
```

### BacktestResult 返回格式

返回 `BacktestResult` 对象（`backtest/result.py:20`），主要属性：

```python
result.equity_curve       # 权益曲线 pd.Series
result.equity_curve_daily # 日频权益曲线
result.daily_returns      # 日收益率
result.cash_curve         # 现金曲线
result.trades             # 成交列表
result.orders             # 订单列表
result.metrics            # 指标字典（total_return, sharpe_ratio, max_drawdown_pct 等）
result.positions_df       # 持仓明细 DataFrame
result.orders_df          # 订单 DataFrame
result.trades_df          # 成交 DataFrame
result.metrics_df         # 指标 DataFrame
result.indicator_outputs  # 指标输出
```

---

## 4. 参数优化能力

**存在。** `akquant.run_grid_search()` 定义于 `python/akquant/optimize.py:522`。

### 完整签名

```python
akquant.run_grid_search(
    strategy,                     # Strategy 类
    param_grid,                   # 参数网格 {"fast": [5,10,20], "slow": [20,30,60]}
    data=None,                    # 回测数据
    max_workers=None,             # 并行进程数（默认=os.cpu_count()）
    sort_by="sharpe_ratio",       # 排序指标（支持多个）
    ascending=False,              # 升序/降序
    return_df=True,               # True→DataFrame，False→List[OptimizationResult]
    warmup_calc=None,             # 动态 warmup 函数
    constraint=None,              # 参数约束函数
    result_filter=None,           # 结果过滤函数
    timeout=None,                 # 单任务超时（秒）
    max_tasks_per_child=None,     # Worker 重启频率
    db_path=None,                 # SQLite 路径（支持断点续传）
    forward_worker_logs=False,    # 转发子进程日志
    **kwargs,                     # 传递给 run_backtest 的参数
) -> pd.DataFrame | List[OptimizationResult]
```

### 使用示例

```python
result_df = aq.run_grid_search(
    strategy=MyStrategy,
    param_grid={"fast_period": [5, 10, 20], "slow_period": [20, 30, 60]},
    data=df,
    initial_cash=100000,
    sort_by="sharpe_ratio",
    max_workers=4,
    db_path="optimize.db",  # 支持断点续传
)
print(result_df.head())  # 按夏普率排序的结果表
```

### 支持的排序指标

通过 `sort_by` 指定字符串，可以是 BacktestResult.metrics 中的任何指标：
- `sharpe_ratio` — 夏普率
- `total_return` — 总收益率
- `max_drawdown_pct` — 最大回撤
- `sortino_ratio` — 索提诺比率
- `win_rate` — 胜率
- `profit_factor` — 盈亏比
- 或任意 metrics 字段

### 并行支持

**支持。** `max_workers` 参数控制并行进程数，使用 `multiprocessing` 实现跨进程并行。

---

## 5. Walk-forward 分析能力

**内置。** `akquant.run_walk_forward()` 定义于 `python/akquant/optimize.py:835`。

### 完整签名

```python
akquant.run_walk_forward(
    strategy,                     # Strategy 类
    param_grid,                   # 参数网格
    data,                         # 回测数据
    train_period,                 # 训练窗口（bar 数量）
    test_period,                  # 测试窗口（bar 数量）
    metric="sharpe_ratio",        # 选优指标
    ascending=False,              # 选优方向
    initial_cash=100_000.0,       # 初始资金
    warmup_period=0,              # 预热期
    warmup_calc=None,             # 动态 warmup
    constraint=None,              # 参数约束
    result_filter=None,           # 结果过滤
    compounding=False,            # 复利模式
    timeout=None,                 # 超时
    max_tasks_per_child=None,     # Worker 重启频率
    **kwargs,                     # 传递给 run_backtest / run_grid_search
) -> pd.DataFrame                  # 样本外拼接权益曲线
```

### 工作流程

```
|--- train_period ---|- test_period -|--- train_period ---|- test_period -|
    grid_search          backtest         grid_search          backtest
    选最优参数           样本外验证        选最优参数           样本外验证
```

每个窗口：在训练集上 grid_search 选最优参数 → 用最优参数在测试集上回测 → 拼接所有样本外结果。

---

## 6. 报告生成能力

**内置 HTML 报告。** `akquant.plot.plot_report()` 定义于 `python/akquant/plot/report.py:2084`。

### 图表库

**Plotly**（交互式 HTML），不是 Matplotlib。

### 报告内容

1. **摘要信息** — 回测区间、时长、初始/最终权益
2. **核心指标卡片** — 总收益、CAGR、平均盈亏、夏普、索提诺、卡尔玛、最大回撤、波动率、胜率、盈亏比、凯利值、交易数
3. **权益曲线 + 回撤图 + 月度收益热力图**
4. **指标图**（可选）— 自定义指标可视化
5. **收益分析** — 年度收益柱状图、日收益分布（含正态拟合）、滚动夏普/波动率
6. **基准对比** — 策略 vs 基准累计收益、超额收益、跟踪误差、信息比率、Beta、Alpha
7. **交易分析** — 盈亏分布直方图、盈亏 vs 持仓时间散点图
8. **K线买卖点回放** — 在 K 线图上标注买卖点
9. **归因分析** — 按标的归因、持仓暴露度
10. **风控分析** — 风控拒绝比率、原因分布、强平审计

### 使用方式

```python
# 生成 HTML 报告
aq.plot.plot_report(
    result,
    title="双均线策略回测",
    filename="report.html",
    benchmark="000300",              # 基准指数
    market_data=df,                  # 传入行情数据以显示 K 线买卖点
    include_trade_kline=True,        # 包含 K 线买卖点图
    include_indicators=True,         # 包含指标图
)

# 快速仪表盘
aq.plot.plot_dashboard(result, filename="dashboard.html")
```

### 自定义报告

报告模块化，可以单独使用各子图：

```python
aq.plot.plot_trades_distribution(trades_df)
aq.plot.plot_pnl_vs_duration(trades_df)
aq.plot.plot_rolling_metrics(returns, window=126)
aq.plot.plot_returns_distribution(returns)
aq.plot.plot_yearly_returns(returns)
```

---

## 7. 信号生成

akquant 没有独立的"只生成信号不回测"的模式。但有替代方案：

### 方案1：run_backtest 跑到最新日期

```python
def signal_strategy(aq.Strategy):
    def on_bar(self, bar):
        # 只生成信号，不交易
        closes = self.get_history(30, field="close")
        ma5 = aq.talib.MA(closes, 5)
        ma20 = aq.talib.MA(closes, 20)
        if ma5[-1] > ma20[-1]:
            self.record_signal("BUY")
        else:
            self.record_signal("SELL")

result = aq.run_backtest(data=df, strategy=signal_strategy, initial_cash=0)
```

### 方案2：直接使用 talib 模块计算

```python
import akquant.talib as talib
import numpy as np

closes = np.array([...])  # 最新数据
ma5 = talib.MA(closes, timeperiod=5)
rsi = talib.RSI(closes, timeperiod=14)
dif, dea, hist = talib.MACD(closes)
# 根据指标值直接生成信号
```

**推荐方案2** — 如果只需要对最新数据产出信号，直接用 `akquant.talib` 计算即可，无需启动回测引擎。

---

## 总结

### akquant 能做什么

| 能力 | 状态 | 说明 |
|---|---|---|
| 技术指标计算（MA/EMA/MACD/RSI/KDJ/BOLL/ATR/OBV/WR/CCI/DMI/SAR） | **全部内置** | Rust 高性能实现，TA-Lib 兼容 API，约 103 个指标 |
| Strategy on_bar 回调 + bar 对象 | **完整支持** | OHLCV + extra + timestamp，含快捷属性 |
| 历史数据访问 | **完整支持** | numpy array / DataFrame / 多标的批量 |
| 持仓查询 | **完整支持** | 支持多标的、T+1 可用持仓 |
| 交易方法 | **完整支持** | buy/sell/short/cover/stop/trailing/bracket/target 系列等 |
| 回测引擎 | **完整支持** | 单/多标的、类/实例/函数式策略、自定义费用 |
| 参数优化（grid search） | **内置** | 多进程并行、断点续传、约束/过滤 |
| Walk-forward 分析 | **内置** | 滚动训练+样本外验证 |
| HTML 报告 | **内置** | Plotly 交互式，含权益/回撤/交易/K线/基准对比 |
| 信号生成 | **间接支持** | 无独立模式，但 talib 可直接计算，或用 run_backtest 模拟 |
| 佣金/滑点/印花税 | **完整支持** | 自定义或使用 broker 模板（cn_stock_miniqmt 等） |
| 多标的回测 | **支持** | Dict[str, DataFrame] 传入 |
| 基准对比 | **支持** | 报告中内置基准对比分析 |
| T+1 限制 | **支持** | `t_plus_one=True` |
| 风控 | **内置** | 最大持仓/单日亏损/最大回撤/风险预算 |

### akquant 不能做什么（或需要 workaround）

| 限制 | 说明 | workaround |
|---|---|---|
| 无独立信号生成模式 | 没有 `generate_signal(data) -> signals` | 直接用 `akquant.talib` 计算指标生成信号 |
| KDJ 无直接函数 | 只有 STOCH 返回 K/D | J = `3*K - 2*D`，一行代码 |
| DMI 无直接函数 | 分为 ADX/PLUS_DI/MINUS_DI | 组合使用即可 |
| MA 仅支持 SMA 类型 | `matype` 参数目前仅支持 0（SMA） | WMA/DEMA/TEMA 等有独立函数 |

### 架构决策建议

**akquant 内置了全部 12 个所需技术指标，建议全部使用 akquant 内置计算能力，不自行实现指标计算。** 指标调用统一走 `akquant.talib` 模块，既可在 Strategy.on_bar 中实时计算，也可预注册为增量指标。
