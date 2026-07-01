# 01 · 框架总览与公开 API 地图

> **面向 AI**：先建立全局认知。本文给"akquant 是什么、导出哪些符号、A 股回测的最小调用链长什么样"。具体签名查后续分册。

## 1. 框架定位

akquant 是一个 **Rust 核心 + Python 绑定** 的量化回测/实盘框架。

| 层 | 实现语言 | 职责 |
|---|---|---|
| 核心（`src/`，Rust/PyO3） | Rust | 事件循环、订单撮合、组合记账、技术指标计算、指标统计 —— **高性能部分** |
| Python 层（`python/akquant/`） | Python | `Strategy` 基类与回调、`run_backtest` 入口、`BacktestResult` 分析、可视化、优化 |

意义：**指标计算和撮合在 Rust 里跑得很快**；策略逻辑、结果分析、画图在 Python 侧，灵活。

## 2. 公开 API 导出地图（源自 `__init__.py`，0.2.47）

`import akquant as aq` 后可用的核心符号（本项目用得到的）：

| 类别 | 符号 | 用途 |
|---|---|---|
| **回测入口** | `run_backtest(...)` | **唯一回测入口**（函数，非类）。详见 [04](./04-backtest-run.md) |
| | `make_fill_policy(*, price_basis, temporal, bar_offset=None)` | 构造成交策略 dict |
| | `run_warm_start(...)` | 快照续跑（**本项目不用**） |
| **结果** | `BacktestResult` | 回测返回对象。详见 [05](./05-result-metrics.md) |
| | `BacktestStreamEvent` | 流式事件 dict（`on_event` 用） |
| **策略** | `Strategy` | 策略基类，子类重写 `on_bar`。详见 [03](./03-strategy-api.md) |
| | `Bar` | 单根 K 线对象（Rust 原生） |
| | `Engine` / `BacktestConfig` / `StrategyConfig` | 引擎与配置对象（一般不直接用） |
| **下单辅助** | `Sizer` / `FixedSize` / `PercentSizer` / `AllInSizer` | 仓位管理器 |
| **指标** | `SMA` `EMA` `MACD` `RSI` `BollingerBands` `ATR` | 顶层快捷指标（少数） |
| | `talib`（子模块） | **103 个 TA-Lib 兼容指标**，主力。详见 [07](./07-talib-indicators.md) |
| | `Indicator` / `IndicatorSet` | 指标框架（register_indicator 用） |
| **优化** | `run_grid_search(...)` | 参数网格寻优。详见 [06](./06-optimization.md) |
| | `run_walk_forward(...)` | 滚动样本外验证 |
| | `OptimizationResult` | 单次优化结果 |
| | `IntParam` `FloatParam` `BoolParam` `ChoiceParam` `ParamModel` | 参数声明 |
| **可视化** | `plot_result` / `plot_indicators` | 画图函数（`result.report()`/`result.plot()` 是更便捷的入口） |
| **数据工具** | `load_bar_from_df` / `prepare_dataframe` / `create_bar` | 数据格式化辅助。详见 [02](./02-data-input.md) |
| | `fetch_akshare_symbol` | akshare 取数（本项目数据由 watcher 传入，一般不用） |

> 完整导出见 `akquant-0.2.47/python/akquant/__init__.py` 的 `__all__`。

## 3. A 股回测最小调用链

```
watcher(HTTP 传入 kline_data, list[dict])
        │  转 DataFrame（DatetimeIndex + OHLCV）   ← 详见 02
        ▼
   pd.DataFrame ──────┐
                      │
   class MyStrat(Strategy): on_bar(bar) 内：     ← 详见 03
        │              │  get_history 取历史 → talib 算指标
        │              │  buy/sell/order_target_percent 下单
        ▼              ▼
   aq.run_backtest(data=df, strategy=MyStrat,    ← 详见 04
                   broker_profile=..., t_plus_one=True, ...)
        │
        ▼
   BacktestResult                                    ← 详见 05
        │  .metrics / .metrics_df   取指标
        │  .equity_curve / .trades_df  取曲线/交易
        │  序列化成 JSON 返回 watcher
        ▼
   （可选）result.report(filename=...)  生成 HTML   ← 详见 05
```

## 4. 三种写策略的方式（都能跑）

| 方式 | 写法 | 适用 |
|---|---|---|
| 类式（**推荐**） | `class S(Strategy): def on_bar(self, bar): ...`，传 `strategy=S` | 正式策略，可带 `__init__` 参数、注册指标 |
| 实例式 | `strategy=S(fast=5)` | 需要预设参数实例时 |
| 函数式 | `def on_bar(ctx, bar): ...`，传 `strategy=on_bar` + `on_start=...`/`context={...}` | 快速原型；内部包成 `FunctionalStrategy` |

> 函数式回调用 `initialize`/`on_start`/`on_stop`/`on_order`/`on_trade`/`on_timer` 等参数传入（都是 `run_backtest` 的形参）。

## 5. 与 stock-engine 的调用边界

- **engine 只算不存**：不读不写 SQLite，行情数据由 watcher 经 HTTP 传入 → 在 engine 里转成 DataFrame → 喂 `run_backtest`。详见 [02](./02-data-input.md) 的"watcher JSON → DataFrame"配方。
- **结果回传**：把 `BacktestResult` 的指标 + 曲线 + trades 序列化成 JSON 返回 watcher。序列化模板见 [05](./05-result-metrics.md)。
- **本项目的封装设计**（JSON 策略配置 → 动态 Strategy 类、HTTP API 路由）不属于框架知识；集成层为废案已清空，待基于「统一策略配置 Schema」重写。

## 6. 相关分册

- 数据怎么准备 → [02-data-input.md](./02-data-input.md)
- 策略怎么写 → [03-strategy-api.md](./03-strategy-api.md)
- 回测怎么跑 → [04-backtest-run.md](./04-backtest-run.md)
- 结果怎么用 → [05-result-metrics.md](./05-result-metrics.md)
