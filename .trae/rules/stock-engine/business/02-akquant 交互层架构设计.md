# akquant 交互层架构设计

## 1. 可行性分析

### 核心问题

能否从 JSON 策略配置动态生成 akquant Strategy 并执行回测？

### 结论：完全可行

akquant 提供三条动态策略创建路径：

1. **`FunctionalStrategy`**（`engine.py:1648`）— 将任意 callable 包装为 Strategy 子类，`run_backtest(strategy=on_bar_func)` 直接支持
2. **`strategy_loader`**（`strategy_loader.py`）— 从 Python 源文件动态导入 Strategy 类
3. **Python `type()` 动态类工厂** — 用 `type("DynamicStrategy", (aq.Strategy,), {...})` 在运行时生成 Strategy 类

**推荐方案 3（动态类工厂）**，原因：

- 可在 `on_bar` 内根据 JSON 配置动态调用 `akquant.talib` 的任意指标
- 无需写临时 Python 文件到磁盘
- 比 FunctionalStrategy 更灵活（可注入 `__init__` 参数、注册生命周期回调）

---

## 2. 整体架构

```
前端(可视化策略配置)
    │
    │ HTTP POST (JSON 策略配置)
    ▼
┌─────────────────────────────┐
│  交互层 (Python HTTP 服务)    │
│  ┌─────────┐ ┌────────────┐ │
│  │Strategy │ │ Backtest    │ │
│  │Factory  │ │ Executor    │ │
│  └───┬─────┘ └─────┬──────┘ │
│      │             │        │
│  ┌───▼─────────────▼──────┐ │
│  │ Result Serializer      │ │
│  └────────────────────────┘ │
└─────────────────────────────┘
    │                  │
    │ akquant.talib    │ akquant.run_backtest
    │ akquant.run_grid │ akquant.plot
    ▼                  ▼
  akquant 引擎
```

五大模块职责：

| 模块 | 职责 | 对接 akquant |
|---|---|---|
| **Strategy Factory** | JSON 策略配置 → 动态 Strategy 类 | `akquant.talib` 指标函数 + `aq.Strategy` 交易 API |
| **Backtest Executor** | 策略 + 数据 + 回测参数 → 执行回测 | `akquant.run_backtest()` / `run_grid_search()` / `run_walk_forward()` |
| **Result Serializer** | BacktestResult → JSON 可传输结构 | BacktestResult 的 metrics_df / trades_df / attribution_df 等 |
| **Data Service** | 提供 Dict[str, DataFrame] 格式行情数据 | `run_backtest(data=...)` 的数据输入格式 |
| **Stock Screener** | 条件选股（预计算 + SQL 查询） | Python 计算 TA-Lib 指标，Java 存储和查询（详见 [06-StockScreener选股模块��计.md](06-StockScreener选股模块设计.md)） |

---

## 3. JSON 策略配置 Schema

> 详见 [03-JSON策略配置Schema.md](03-JSON策略配置Schema.md)
>
> 核心设计：统一表达式节点（value / factor / op）替代多类型 target 模型，
> 条件类型从 factor_compare + factor_threshold 合并为单一 compare，
> 支持 `+` `-` `*` `/` 四则运算递归组合，天然覆盖三值比较（A-B > C）、因子比值（A/B > k）等场景。

---

## 4. Strategy Factory 设计

> 详见 [04-StrategyFactory设计.md](04-StrategyFactory设计.md)

---

## 5. 回测执行器设计

### 5.1 核心逻辑

```python
import akquant as aq


def run_backtest_from_config(config: dict, data: dict) -> dict:
    """接收 JSON 配置 + 市场数据，执行回测，返回结果"""

    strategy_cls = build_strategy_class(config)
    bt_cfg = config.get("backtest_config", {})

    # 直接映射到 run_backtest 参数
    result = aq.run_backtest(
        data=data,
        strategy=strategy_cls,
        initial_cash=bt_cfg.get("initial_cash", 100000),
        broker_profile=bt_cfg.get("broker_profile"),
        t_plus_one=bt_cfg.get("t_plus_one", False),
        commission_rate=bt_cfg.get("commission_rate"),
        stamp_tax_rate=bt_cfg.get("stamp_tax_rate"),
        min_commission=bt_cfg.get("min_commission"),
        slippage=bt_cfg.get("slippage"),
        history_depth=bt_cfg.get("history_depth"),
        warmup_period=bt_cfg.get("warmup_period", 0),
        start_time=bt_cfg.get("start_time"),
        end_time=bt_cfg.get("end_time"),
        show_progress=False,
    )

    return serialize_result(result)


def run_grid_search_from_config(config: dict, data: dict, param_grid: dict, options: dict = None) -> dict:
    """参数优化"""
    strategy_cls = build_strategy_class(config)
    bt_cfg = config.get("backtest_config", {})
    opts = options or {}

    result_df = aq.run_grid_search(
        strategy=strategy_cls,
        param_grid=param_grid,
        data=data,
        initial_cash=bt_cfg.get("initial_cash", 100000),
        sort_by=opts.get("sort_by", "sharpe_ratio"),
        max_workers=opts.get("max_workers"),
        db_path=opts.get("db_path"),
        return_df=True,
        # ... 其他 run_backtest 参数透传
    )

    return {
        "optimization_results": result_df.to_dict(orient="records"),
        "best_params": result_df.iloc[0].to_dict() if len(result_df) > 0 else None,
    }


def run_walk_forward_from_config(config: dict, data: dict, param_grid: dict, wf_options: dict) -> dict:
    """Walk-forward 滚动验证"""
    strategy_cls = build_strategy_class(config)
    bt_cfg = config.get("backtest_config", {})

    wf_df = aq.run_walk_forward(
        strategy=strategy_cls,
        param_grid=param_grid,
        data=data,
        train_period=wf_options["train_period"],
        test_period=wf_options["test_period"],
        metric=wf_options.get("metric", "sharpe_ratio"),
        initial_cash=bt_cfg.get("initial_cash", 100000),
        warmup_period=bt_cfg.get("warmup_period", 0),
    )

    return {
        "walk_forward_equity": wf_df.to_dict(orient="records"),
    }
```

### 5.2 参数优化中的策略参数注入

`run_grid_search` 需要策略类接受可变参数。设计两种方式：

**方式 A：策略类 __init__ 参数**

JSON 配置中的因子参数用变量名标记，Strategy Factory 生成带 `__init__` 的类：

```python
# config 中标记可优化参数
{
  "factors": [
    { "id": "ma_fast", "type": "MA", "params": { "timeperiod": "$fast_period" }, "inputs": ["close"] }
  ]
}

# Factory 生成的 Strategy 类带有 __init__ 参数
class DynamicStrategy(aq.Strategy):
    def __init__(self, fast_period=5, slow_period=20):
        self.fast_period = fast_period
        self.slow_period = slow_period
    def on_bar(self, bar):
        ma_fast = talib.MA(closes, timeperiod=self.fast_period)
        ...
```

**方式 B：param_overrides 注入**

每次 grid_search 生成新配置副本，调用 `build_strategy_class(config, param_overrides)`。

推荐方式 A（更符合 `run_grid_search` 的 param_grid 设计），但需要 Factory 识别 `$variable` 标记并生成 `__init__`。

---

## 6. 选股模块设计

> 详见 [06-StockScreener选股模块设计.md](06-StockScreener选股模块设计.md)
>
> 核心设计：Python 只负责指标计算（无状态），Java 负责预计算存储和 SQL 查询。
> 每日收盘后批量计算全市场指标存入 `stock_indicator_daily` 表，选股时条件树直接转为 SQL WHERE，
> 无需实时调用 Python。条件 JSON 复用策略配置的表达式节点和 compare 结构，前端条件编辑器共用一套 UI。

---

## 7. 结果序列化设计

```python
def serialize_result(result) -> dict:
    """将 BacktestResult 序列化为 JSON 可传输结构"""

    metrics = result.metrics_df
    equity = result.equity_curve_daily

    return {
        # 核心指标
        "metrics": {
            "total_return": metrics.loc["total_return", "value"],
            "cagr": metrics.loc["cagr", "value"],
            "sharpe_ratio": metrics.loc["sharpe_ratio", "value"],
            "sortino_ratio": metrics.loc["sortino_ratio", "value"],
            "max_drawdown_pct": metrics.loc["max_drawdown_pct", "value"],
            "volatility": metrics.loc["volatility", "value"],
            "win_rate": metrics.loc["win_rate", "value"],
            "profit_factor": metrics.loc["profit_factor", "value"],
            "calmar_ratio": metrics.loc["calmar_ratio", "value"],
            "trade_count": metrics.loc["trade_count", "value"],
            # 可按需扩展
        },

        # 权益曲线（轻量化：日频）
        "equity_curve": {
            "dates": equity.index.strftime("%Y-%m-%d").tolist(),
            "values": [round(v, 2) for v in equity.values.tolist()],
        },

        # 日收益率
        "daily_returns": [round(v, 6) for v in result.daily_returns.values.tolist()],
        "daily_returns_dates": result.daily_returns.index.strftime("%Y-%m-%d").tolist(),

        # 交易记录
        "trades": result.trades_df.to_dict(orient="records"),

        # 持仓明细
        "positions": result.positions_df.to_dict(orient="records"),

        # 归因分析（按标的）
        "attribution": result.attribution_df(by="symbol").to_dict(orient="records"),

        # 暴露度分析
        "exposure": result.exposure_df().to_dict(orient="records"),

        # 订单记录
        "orders": result.orders_df.to_dict(orient="records"),
    }


def generate_report(result, config: dict, data: dict) -> str:
    """生成 HTML 报告，返回文件路径"""
    report_cfg = config.get("report_config", {})
    filename = report_cfg.get("filename", f"report_{config['strategy_id']}.html")

    result.report(
        title=config.get("name", "回测报告"),
        filename=filename,
        show=False,
        benchmark=report_cfg.get("benchmark"),
        market_data=data,
        include_trade_kline=report_cfg.get("include_trade_kline", True),
        include_indicators=report_cfg.get("include_indicators", True),
    )

    return filename
```

### 利用 akquant 的 BacktestResult 能力

| BacktestResult 属性/方法 | 序列化用途 |
|---|---|
| `metrics_df` | 核心指标指标（total_return, sharpe, max_drawdown 等） |
| `equity_curve_daily` | 权益曲线（日频） |
| `daily_returns` | 日收益率序列 |
| `trades_df` | 交易明细 |
| `positions_df` | 持仓明细 |
| `orders_df` | 订单明细 |
| `attribution_df(by="symbol")` | 按标的归因分析 |
| `exposure_df()` | 暙露度分析 |
| `report()` | HTML 报告生成 |

---

## 8. HTTP API 设计

基于 FastAPI（比 stdlib http.server 更适合生产环境，akquant 项目中的示例用的是 stdlib，但生产建议 FastAPI）。

### 8.1 API 端点

```
POST /api/backtest
  Body: { "strategy_config": {...}, "symbols": ["000001"], "start_date": "2024-01-01", "end_date": "2025-01-01" }
  Response: { "metrics": {...}, "equity_curve": {...}, "trades": [...], ... }

POST /api/backtest/report
  Body: 同 /api/backtest + { "report_config": { "benchmark": "000300", "include_trade_kline": true } }
  Response: HTML 文件路径 或 HTML string

POST /api/optimize
  Body: { "strategy_config": {...}, "param_grid": {"fast_period": [5,10,20], "slow_period": [20,30,60]}, "optimize_options": {...} }
  Response: { "results": [{ "params": {...}, "sharpe_ratio": 1.5, "total_return": 0.3, ... }, ...], "best_params": {...} }

POST /api/walk-forward
  Body: { "strategy_config": {...}, "param_grid": {...}, "train_period": 120, "test_period": 30, "wf_options": {...} }
  Response: { "walk_forward_equity": [...], "window_results": [...] }

GET /api/factors
  Response: { "available_factors": ["MA", "EMA", "MACD", ...], "schemas": { "MA": {"params": ["timeperiod"], "inputs": ["close"], "multi_output": false}, ... } }

GET /api/health
  Response: { "status": "ok", "akquant_version": "..." }

POST /api/indicators/compute
  Body: { "stock": "000001", "ohlcvs": [...], "indicators": [...] }
  Response: { "stock": "000001", "values": { "ma_5": 12.5, ... } }
  说明: 选股预计算用，Python 无状态计算指标值
```

### 8.2 FastAPI 服务骨架

```python
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="akquant Backtest Service")


class BacktestRequest(BaseModel):
    strategy_config: dict
    symbols: list[str]
    start_date: str
    end_date: str


class OptimizeRequest(BaseModel):
    strategy_config: dict
    symbols: list[str]
    start_date: str
    end_date: str
    param_grid: dict
    optimize_options: dict = {}


@app.post("/api/backtest")
async def backtest(req: BacktestRequest):
    data = data_service.get_data(req.symbols, req.start_date, req.end_date)
    result = run_backtest_from_config(req.strategy_config, data)
    return result


@app.post("/api/optimize")
async def optimize(req: OptimizeRequest):
    data = data_service.get_data(req.symbols, req.start_date, req.end_date)
    result = run_grid_search_from_config(req.strategy_config, data, req.param_grid, req.optimize_options)
    return result


@app.get("/api/factors")
async def list_factors():
    return {
        "available_factors": list(FACTOR_REGISTRY.keys()),
        "schemas": {
            name: {
                "params": _get_param_schema(reg["func"]),
                "inputs": reg["inputs"],
                "multi_output": reg["multi_output"],
            }
            for name, reg in FACTOR_REGISTRY.items()
        },
    }
```

---

## 9. 数据管理层

### 9.1 职责

提供 `Dict[str, DataFrame]` 格式的行情数据，直接传给 `run_backtest(data=...)`。

### 9.2 设计

```python
import pandas as pd


class DataService:
    """行情数据服务，负责获取并提供 akquant 所需格式数据"""

    def get_data(self, symbols: list[str], start: str, end: str) -> dict[str, pd.DataFrame]:
        """返回 Dict[str, DataFrame]，格式为 DatetimeIndex + OHLCV"""
        result = {}
        for symbol in symbols:
            df = self._fetch(symbol, start, end)
            # 确保 DataFrame 格式正确
            df = df[["open", "high", "low", "close", "volume"]]
            df.index = pd.DatetimeIndex(df.index)
            result[symbol] = df
        return result

    def _fetch(self, symbol: str, start: str, end: str) -> pd.DataFrame:
        """从数据源获取行情（可对接多种数据源）"""
        # 数据源选项：
        # 1. 本地数据库（MySQL / PostgreSQL / SQLite）
        # 2. akshare 接口
        # 3. 文件缓存（Parquet / CSV）
        # 4. 外部 API
        raise NotImplementedError("需要根据实际数据源实现")
```

### 9.3 数据格式要求

akquant `run_backtest(data=...)` 要求：

- 单标的：`DataFrame`，必须含 `DatetimeIndex` + `open/high/low/close/volume` 列
- 多标的：`Dict[str, DataFrame]`，key 为标的代码

---

## 10. akquant 能力利用率总览

| akquant 能力 | 交互层是否利用 | 映射方式 |
|---|---|---|
| `akquant.talib` 103 指标（Rust 实现） | **全部利用** | FACTOR_REGISTRY → JSON `factors[].type` |
| `Strategy.get_history()` 历史数据访问 | **全部利用** | on_bar 中取 numpy array 直传 talib |
| `Strategy.set_history_depth()` | **利用** | on_start 中设置 |
| `Strategy.order_target_percent()` 等交易方法 | **全部利用** | `position_sizing.method` 映射 |
| `Strategy.close_position()` | **利用** | `position_sizing.sell_action` 映射 |
| `run_backtest()` 完整参数 | **全部利用** | `backtest_config` 直接映射 |
| `run_grid_search()` 参数优化 | **利用** | `/api/optimize` + param_grid |
| `run_walk_forward()` 滚动验证 | **利用** | `/api/walk-forward` |
| `BacktestResult.metrics_df` 等分析方法 | **全部利用** | serialize_result 提取 |
| `BacktestResult.report()` HTML 报告 | **利用** | `/api/backtest/report` |
| T+1 / broker_profile | **利用** | `backtest_config.t_plus_one` / `broker_profile` |
| 多标的回测 | **利用** | `Dict[str, DataFrame]` data 传入 |

---

## 11. 需审核确认的决策点

| 决策点 | 当前方案 | 需确认 |
|---|---|---|
| HTTP 框架 | FastAPI | 是否接受？还是用 Flask / stdlib？ |
| 策略动态生成方式 | Python `type()` 类工厂 | 是否接受？是否需要 `strategy_loader` 文件方式？ |
| 信号条件表达方式 | **递归嵌套条件树（AND/OR + factor_compare + factor_threshold）** | 已支持多层嵌套、穿越信号、5 种 target 类型，是否满足需求？ |
| target 值类型 | static / factor / factor_multiplier / factor_offset_pct / factor_offset_abs | 5 种是否足够？是否还有其他场景？ |
| cross_up / cross_down | 基于 `_prev_factors` 快照对比上一根 bar 值 | 是否需要支持 N 根 bar 内穿越（如 3 根内上穿）？ |
| 因子定义方式 | 条件内联定义 + 可选 top-level factors 预声�� | 是否需要全部走内联？还是全部走预声明？ |
| 参数优化方式 | 方式 A（策略类 __init__ 参数 + param_grid） | 是否接受？还是方式 B（param_overrides）？ |
| 结果传输格式 | JSON（核心指标 + 曲线 + trades） | 前端需要哪些字段？是否需要全量传输？ |
| HTML 报告返回方式 | 返回文件路径 | 是否需要直接返回 HTML string？ |
| 数据源 | 未定（DataService 需实现） | 实际数据从哪来？ |
| 项目目录结构 | 待定 | 代码放在 akquant 项目内还是独立项目？ |
| 条件类型扩展性（后续迭代） | 当前条件分发基于 `type` 字段做 if/elif，新增类型只需加分支，不改动已有逻辑 | 第一版不做，后续按需加 `factor_cross_count` 等事件计数类条件 |
---

## 12. 项目目录结构（建议）

```
akquant_service/
├── app/
│   ├── main.py                  # FastAPI 入口
│   ├── strategy_factory.py      # Strategy Factory 核心逻辑
│   ├── factor_registry.py       # 因子注册表
│   ├── backtest_executor.py     # 回测/优化/walk-forward 执行器
│   ├── result_serializer.py     # 结果序列化
│   ├── data_service.py          # 数据管理
│   └── models/
│       │   ├── strategy_config.py  # Pydantic Schema 校验模型
│       │   ├── backtest_request.py
│       │   └── optimize_request.py
├── tests/
│   ├── test_strategy_factory.py
│   ├── test_backtest_executor.py
│   ├── test_result_serializer.py
├── requirements.txt
└── README.md
```