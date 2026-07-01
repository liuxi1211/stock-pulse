# 08 · 可落地配方手册（stock-engine 回测用）

> **面向 AI**：以 `stock-engine/services/backtest/strategy_runner.py` 三个待实现策略（double_ma/macd/rsi）为靶，给可直接落地的代码片段；并补 ATR 突破、动量轮动、目标权重调仓；最后给**端到端全链路**（watcher JSON → DataFrame → 回测 → 序列化 JSON）和**无 DB 冒烟脚本**。每段注明对应 `akquant-0.2.47/examples/` 来源。

## 0. 公共约定

- 所有策略 `__init__` 都 `super().__init__()`，并设 `warmup_period` ≥ on_bar 取的历史长度。
- 回测统一：`broker_profile="cn_stock_miniqmt"` + `t_plus_one=True` + `lot_size`（profile 自带 100）。
- 指标在 on_bar 内 `get_history` + `akquant.talib` 实时算（见 [03](./03-strategy-api.md) §7、[07](./07-talib-indicators.md)）。

---

## 配方 1：双均线（double_ma）— 对应 examples/strategies/01

```python
import numpy as np
import akquant.talib as talib
from akquant import Strategy, Bar

class DoubleMaStrategy(Strategy):
    def __init__(self, fast: int = 5, slow: int = 20):
        super().__init__()
        self.fast, self.slow = fast, slow
        self.warmup_period = slow

    def on_bar(self, bar: Bar):
        closes = self.get_history(self.slow, field="close")
        if len(closes) < self.slow:
            return
        fast_ma = talib.MA(closes, timeperiod=self.fast)[-1]
        slow_ma = talib.MA(closes, timeperiod=self.slow)[-1]
        pos = self.get_position(bar.symbol)
        if fast_ma > slow_ma and pos == 0:
            self.order_target_percent(bar.symbol, 0.95)   # 金叉买入
        elif fast_ma < slow_ma and pos > 0:
            self.close_position(bar.symbol)               # 死叉卖出
```

> 也可不调 talib，直接 `closes[-self.fast:].mean()`（examples/01 就这么写）。talib 版更统一。

## 配方 2：MACD（macd）

```python
import akquant.talib as talib
from akquant import Strategy, Bar

class MacdStrategy(Strategy):
    def __init__(self, fast=12, slow=26, signal=9):
        super().__init__()
        self.fp, self.sp, self.sigp = fast, slow, signal
        self.warmup_period = slow + signal

    def on_bar(self, bar: Bar):
        closes = self.get_history(self.warmup_period, field="close")
        if len(closes) < self.warmup_period:
            return
        dif, dea, hist = talib.MACD(closes, fastperiod=self.fp,
                                    slowperiod=self.sp, signalperiod=self.sigp)
        h_now, h_prev = hist[-1], hist[-2]
        pos = self.get_position(bar.symbol)
        if h_now > 0 and h_prev <= 0 and pos == 0:        # 柱由负转正（金叉）
            self.order_target_percent(bar.symbol, 0.95)
        elif h_now < 0 and h_prev >= 0 and pos > 0:       # 柱由正转负（死叉）
            self.close_position(bar.symbol)
```

## 配方 3：RSI 超买超卖（rsi）

```python
import akquant.talib as talib
from akquant import Strategy, Bar

class RsiStrategy(Strategy):
    def __init__(self, period=14, oversold=30, overbought=70):
        super().__init__()
        self.period, self.lo, self.hi = period, oversold, overbought
        self.warmup_period = period + 1

    def on_bar(self, bar: Bar):
        closes = self.get_history(self.warmup_period, field="close")
        if len(closes) < self.warmup_period:
            return
        rsi = talib.RSI(closes, timeperiod=self.period)[-1]
        pos = self.get_position(bar.symbol)
        if rsi < self.lo and pos == 0:                    # 超卖买入
            self.order_target_percent(bar.symbol, 0.95)
        elif rsi > self.hi and pos > 0:                   # 超买卖出
            self.close_position(bar.symbol)
```

## 配方 4：ATR 突破 — 对应 examples/strategies/03

```python
import akquant.talib as talib
from akquant import Strategy, Bar

class AtrBreakoutStrategy(Strategy):
    def __init__(self, atr_period=14, window=20):
        super().__init__()
        self.atr_period, self.window = atr_period, window
        self.warmup_period = max(atr_period, window) + 1

    def on_bar(self, bar: Bar):
        n = self.warmup_period
        h = self.get_history(n, field="high"); l = self.get_history(n, field="low")
        c = self.get_history(n, field="close")
        if len(c) < n:
            return
        atr = talib.ATR(h, l, c, timeperiod=self.atr_period)[-1]
        upper = talib.MA(c, timeperiod=self.window)[-1] + 2 * atr   # 布林式上轨
        pos = self.get_position(bar.symbol)
        if bar.close > upper and pos == 0:
            self.order_target_percent(bar.symbol, 0.95)
        elif bar.close < talib.MA(c, timeperiod=self.window)[-1] and pos > 0:
            self.close_position(bar.symbol)
```

## 配方 5：动量轮动（多标的）— 对应 examples/strategies/04 + rebalance_to_topn

```python
import akquant.talib as talib
from akquant import Strategy, Bar

class MomentumRotationStrategy(Strategy):
    def __init__(self, n_top=3, period=20):
        super().__init__()
        self.n_top, self.period = n_top, period
        self.warmup_period = period

    def on_bar(self, bar: Bar):
        # 多标的：on_bar 逐标的触发；这里用每日收盘前对所有标的打分调仓
        # 简化：每根 bar 用该标的近 period 涨幅打分，靠 rebalance_to_topn 一次性调仓
        closes = self.get_history(self.period, field="close")
        if len(closes) < self.period:
            return
        score = closes[-1] / closes[0] - 1.0            # 区间涨幅
        self._scores = getattr(self, "_scores", {})
        self._scores[bar.symbol] = score
        # 实战在 on_after_trading 或定时器里统一调 rebalance_to_topn
        # 这里仅在收齐所有 symbol 后调仓（需配合多标的 on_bar 顺序，见 examples/04/05/07）
        if hasattr(self, "_symbols") and set(self._scores) >= set(self._symbols):
            self.rebalance_to_topn(self._scores, self.n_top, weight_mode="equal",
                                   liquidate_unmentioned=True)
            self._scores = {}
```

> `rebalance_to_topn(scores, top_n, weight_mode="equal"|"score", long_only=True, liquidate_unmentioned=True)` 是 Strategy 内置的 TopN 调仓便捷方法（见 [03](./03-strategy-api.md)）。完整多标的轮动范例见 `examples/strategies/04~07` 与 `examples/43_target_weights_rebalance.py`。

## 配方 6：目标权重调仓 — 对应 examples/43

```python
class TargetWeightsStrategy(Strategy):
    def on_bar(self, bar: Bar):
        # 计算各标的目标权重后：
        self.order_target_weights(
            target_weights={"000001.SZ": 0.4, "600000.SH": 0.6},
            liquidate_unmentioned=True,   # 清掉不在目标里的持仓
            allow_leverage=False,
        )
```

---

## 7. ⭐ 端到端全链路（engine 落地蓝本）

把 `strategy_runner.py` 的桩换成真实实现，完整链路如下：

```python
import akquant as aq
import pandas as pd

# ---- 1. watcher 传入的 kline_data(list[dict]) → DataFrame（见 02-data-input.md §6）----
def kline_to_df(kline_data: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(kline_data)
    time_col = next((c for c in ("date", "trade_date", "datetime", "timestamp") if c in df.columns), None)
    df[time_col] = pd.to_datetime(df[time_col])
    df = df.set_index(time_col).sort_index()
    df = df[["open", "high", "low", "close", "volume"]].astype("float64")
    return df

# ---- 2. 策略注册表（替换现有 TODO 桩）----
STRATEGY_REGISTRY = {
    "double_ma": (DoubleMaStrategy, {"fast": "fast_window", "slow": "slow_window"}),
    "macd":      (MacdStrategy,      {}),
    "rsi":       (RsiStrategy,       {}),
}

# ---- 3. 跑回测 ----
def run_backtest_engine(strategy: str, kline_data: list[dict], params: dict) -> dict:
    strat_cls, _ = STRATEGY_REGISTRY[strategy]
    df = kline_to_df(kline_data)
    symbol = params.pop("symbol", "DEFAULT")        # 由 watcher 指定
    result = aq.run_backtest(
        data=df,
        strategy=strat_cls,
        symbols=symbol,
        initial_cash=params.pop("initial_cash", 100_000),
        broker_profile="cn_stock_miniqmt",
        t_plus_one=True,
        timezone="Asia/Shanghai",
        warmup_period=params.pop("warmup_period", 30),
        show_progress=False,
        strategy_kwargs=params if params else None,  # 注：若 akquant 版本不支持，改传实例
    )
    # ---- 4. 序列化为 JSON 回传 watcher（见 05-result-metrics.md §5）----
    return serialize_result(result)
```

> ⚠️ `run_backtest` 没有 `strategy_kwargs` 形参。**带参策略的正确传法**：① 传**类**+ 让 `param_grid`/构造参数经别的途径；或 ② 传**实例** `strategy=strat_cls(fast=params["fast_window"])`（最直接，推荐 engine 用）。把上面 `strategy_kwargs` 那行换成实例构造即可：
> ```python
> strat = strat_cls(**{STRATEGY_REGISTRY[strategy][1].get(k, k): v for k, v in params.items()})
> result = aq.run_backtest(data=df, strategy=strat, ...)
> ```

## 8. ⭐ 冒烟脚本（合成数据，无 DB，验证 API 不写错）

不依赖 watcher、不依赖 SQLite，用 5 根合成 Bar 验证"回测入口 + 取结果"真的跑通（能抓出 `aq.Backtest` 这类错误）：

```python
import pandas as pd
import akquant as aq
from akquant import Bar, Strategy, create_bar

class SmokeStrategy(Strategy):
    def on_bar(self, bar: Bar):
        if self.get_position() == 0:
            self.order_target_percent(bar.symbol, 0.5)

def make_bars():
    bars = []
    for i in range(10):
        ts = int(pd.Timestamp(f"2024-01-{i+1:02d} 15:00", tz="UTC").value)
        p = 10.0 + i * 0.1
        bars.append(create_bar(ts, p, p+0.1, p-0.1, p, 10000.0, "TEST"))
    return bars

if __name__ == "__main__":
    result = aq.run_backtest(
        data=make_bars(), strategy=SmokeStrategy, symbols="TEST",
        initial_cash=100_000, broker_profile="cn_stock_miniqmt", t_plus_one=True,
        show_progress=False,
    )
    print(type(result).__name__)                     # BacktestResult
    print(result.metrics.sharpe_ratio)               # 能取指标即说明链路通
    print(len(result.equity_curve), len(result.trades_df))
```

> 跑通即证明入口签名正确。若 `import akquant` 失败，先在 stock-engine venv `pip install -r requirements.txt`（含 `akquant==0.2.47`）。
>
> **当前状态**：本脚本**尚未在本机实跑**（`stock-engine/venv` 当前未装 akquant/pandas/numpy）。API 已静态核对一致，待环境就绪后执行此脚本完成动态验证。

## 9. 相关分册

- 数据转换细节 → [02-data-input.md](./02-data-input.md)
- 序列化模板 → [05-result-metrics.md](./05-result-metrics.md) §5
- 防坑 → [09-pitfalls-conventions.md](./09-pitfalls-conventions.md)
