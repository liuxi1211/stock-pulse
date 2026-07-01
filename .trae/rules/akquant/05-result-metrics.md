# 05 · BacktestResult 结果与指标

> **面向 AI**：回测返回的 `BacktestResult` 能取到什么、核心指标有哪些、怎么序列化成 JSON 回传 watcher、怎么出报告。源码：`akquant-0.2.47/python/akquant/backtest/result.py`。

## 1. 曲线类属性（pd.Series，时区感知）

| 属性 | 说明 |
|---|---|
| `equity_curve` | 权益曲线（总权益，含初始资金） |
| `equity_curve_daily` | 日频末值 |
| `daily_returns` | 日收益率（百分比小数，如 0.01=1%） |
| `cash_curve` / `cash_curve_daily` | 现金曲线 |
| `margin_curve` / `margin_curve_daily` | 保证金曲线（股票一般 0） |

> `equity_curve` 的 index 是带时区的 DatetimeIndex；序列化时按需 `strftime`。

## 2. 明细 DataFrame（`.to_dict('records')` 可转 JSON）

| 属性 | 关键列 |
|---|---|
| `trades_df` | symbol / entry_time / exit_time / entry_price / exit_price / quantity / side / pnl / net_pnl / return_pct / commission / duration_bars / duration / mae / mfe / entry_tag / exit_tag / max_drawdown_pct |
| `orders_df` | id / symbol / side / order_type / quantity / filled_quantity / limit_price / stop_price / avg_price / commission / status / created_at / updated_at / reject_reason |
| `positions_df` | date / symbol / long_shares / short_shares / close / equity / market_value / margin / unrealized_pnl / entry_price |
| `executions_df` | 每笔成交明细（fill） |
| `liquidation_audit_df` | 强平审计（股票一般空） |

原始对象：`result.trades`(List[ClosedTrade])、`result.orders`(List[Order])。

## 3. 指标：`metrics` 与 `metrics_df`

```python
result.metrics.sharpe_ratio          # 单个指标（属性访问）
result.metrics.total_return_pct      # ⚠️ 原始百分数，15.0 表示 15%
result.metrics_df                    # DataFrame：index=指标名，列='value'
```

**核心指标**（属性名 / 含义 / 单位注意）：

| 指标 | 含义 | 单位 |
|---|---|---|
| `total_return_pct` | 总收益率 | 原始百分数（15.0=15%），÷100 得小数 |
| `annualized_return` | 年化收益 | 小数 |
| `cagr` | 复合年化 | 小数 |
| `volatility` | 年化波动 | 小数 |
| `sharpe_ratio` | 夏普 | 比值 |
| `sortino_ratio` | 索提诺 | 比值 |
| `calmar_ratio` | 年化/最大回撤 | 比值 |
| `max_drawdown_pct` | 最大回撤 | 原始百分数（正数存），÷100 得小数 |
| `max_drawdown_value` | 最大回撤金额 | 正数存 |
| `win_rate` | 胜率 | 原始百分数 or 小数（取值时核对） |
| `profit_factor` | 盈亏比 | 比值 |
| `equity_r2` | 权益曲线线性拟合 R² | 0~1 |
| `std_error` | 估计标准误 | 金额 |
| `trade_count` | 交易笔数 | 整数 |
| `initial_market_value` / `end_market_value` | 初始/最终权益 | 金额 |
| `duration` | 回测时长 | timedelta 或纳秒 int |
| `start_time` / `end_time` | 起止时间 | datetime |

> ⚠️ **单位坑**：`total_return_pct`、`max_drawdown_pct`、`win_rate` 等带 `_pct` 的字段是**原始百分数**（不是小数）。回传前端展示时注意是否要 ÷100。`max_drawdown_*` 以**正数**存储。
> ⚠️ **列名坑**：`metrics_df` 单回测列名是 `value`；但优化场景下可能是 `Backtest`。序列化时优先 `.iloc[:, 0]` 做兼容。

## 4. 分析方法（按需调用）

```python
result.exposure_df(freq="D")                      # 暴露度时序：long/short/net/gross exposure, leverage
result.attribution_df(by="symbol")                # 归因：group/trade_count/total_pnl/avg_return_pct/contribution_pct
result.attribution_df(by="entry_tag")             # 也支持 entry_tag / exit_tag / tag
result.capacity_df(freq="D")                      # 成交容量代理指标
result.top_reject_reasons(top_n=10)               # 拒单原因 Top-N
result.top_reject_reason_types(top_n=10)          # 拒单分类
result.risk_rejections_by_strategy()              # 风控拒单按策略汇总（多策略用）
```

## 5. ⭐ 序列化模板（回传 watcher）

把回测结果转成 watcher 可收的 JSON。**已校准**指标名与单位（修掉了 `02-交互层架构设计.md` 里 `metrics.loc["total_return","value"]` 这类不存在的键）：

```python
import math
import pandas as pd

def _num(x):
    """NaN/Inf → None，可 JSON 序列化。"""
    if x is None:
        return None
    try:
        x = float(x)
    except (TypeError, ValueError):
        return None
    return None if (math.isnan(x) or math.isinf(x)) else x

def _metric(metrics_df, name):
    """安全取单个指标（兼容 value/Backtest 列名）。"""
    if name not in metrics_df.index:
        return None
    col = "value" if "value" in metrics_df.columns else metrics_df.columns[0]
    return _num(metrics_df.at[name, col])

def serialize_result(result) -> dict:
    m = result.metrics_df
    eq_daily = result.equity_curve_daily

    return {
        "metrics": {
            "total_return_pct":   _metric(m, "total_return_pct"),
            "annualized_return":  _metric(m, "annualized_return"),
            "cagr":               _metric(m, "cagr"),
            "sharpe_ratio":       _metric(m, "sharpe_ratio"),
            "sortino_ratio":      _metric(m, "sortino_ratio"),
            "calmar_ratio":       _metric(m, "calmar_ratio"),
            "max_drawdown_pct":   _metric(m, "max_drawdown_pct"),
            "volatility":         _metric(m, "volatility"),
            "win_rate":           _metric(m, "win_rate"),
            "profit_factor":      _metric(m, "profit_factor"),
            "trade_count":        _metric(m, "trade_count"),
        },
        "equity_curve": {
            "dates":  [str(d.date()) for d in eq_daily.index],
            "values": [_num(v) for v in eq_daily.values.tolist()],
        },
        "daily_returns": [_num(v) for v in result.daily_returns.values.tolist()],
        "trades":  result.trades_df.to_dict(orient="records") if not result.trades_df.empty else [],
        "orders":  result.orders_df.to_dict(orient="records") if not result.orders_df.empty else [],
        "positions": result.positions_df.to_dict(orient="records") if not result.positions_df.empty else [],
    }
```

> `trades_df.to_dict('records')` 里可能含 `Timestamp`/`Timedelta`，回传前需再过一道 JSON 编码（参考 `optimize.py` 的 `JSONEncoder`：Timestamp→isoformat、Timedelta→total_seconds、NaN→None）。
> 字段取舍以 watcher 回测响应 schema（`stock-engine/models/schemas/backtest.py`）为准；本模板给出常用集合，落地时对齐。

## 6. 报告与可视化

```python
# 便捷方法（推荐入口）
result.report(
    title="双均线策略回测",
    filename="report.html",
    show=False,                       # True 则自动打开浏览器
    market_data=df,                   # 传行情以画 K 线买卖点
    include_trade_kline=True,
    benchmark="000300",               # 基准（指数代码或 pd.Series）
)

result.plot(title="...", show=True)              # Plotly 仪表盘
result.plot_indicators(name="ma5")               # 已 record_indicator 的指标图
result.report_quantstats(benchmark="000300", filename="qs.html")  # QuantStats 报告
```

> `result.report(...)` 是 `akquant.plot.report.plot_report` 的便捷包装（`aq.plot.plot_report(result, ...)` 也能用，但 `result.report()` 更简）。需要 `pip install plotly`（或 `akquant[plot]`）。

## 7. 指标记录与导出（register/record_indicator 配合）

```python
result.indicator_df(name="ma5", symbol="000001.SZ")   # 取记录的指标点
result.export_indicators("indicators.json", format="json")   # 或 parquet
```

## 8. 相关分册

- 回测怎么跑 → [04-backtest-run.md](./04-backtest-run.md)
- 端到端配方（含序列化）→ [08-recipes-stock-engine.md](./08-recipes-stock-engine.md)
- 单位/NaN 防坑 → [09-pitfalls-conventions.md](./09-pitfalls-conventions.md)
