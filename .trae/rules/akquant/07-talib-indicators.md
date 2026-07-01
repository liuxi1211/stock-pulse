# 07 · akquant.talib 技术指标速查

> **面向 AI**：技术指标函数签名速查表 + KDJ/DMI 的组合 workaround + **与 stock-engine 选股/因子计算的 factorKey 映射对齐**。源码：`akquant-0.2.47/python/akquant/talib/funcs.py`。

## 1. 通用调用约定

```python
import akquant.talib as talib
import numpy as np

arr = np.asarray([10.0, 10.1, ...], dtype=np.float64)
out = talib.MA(arr, timeperiod=5)              # 返回 np.ndarray（同长，前置 NaN 为预热段）
macd, signal, hist = talib.MACD(arr)           # 多输出指标返回 tuple
```

- 输入：`SeriesLike`（np.ndarray 或 list），内部转 float64。
- 参数名与 **C 版 TA-Lib 一致**（`timeperiod`/`fastperiod`/`nbdevup` 等）。
- 输出：单值指标返回 `np.ndarray`；多值指标返回 `tuple[np.ndarray, ...]`。预热段为 `NaN`。
- 默认 `as_series=True`、`backend="auto"`（Rust 实现，无需装第三方 ta-lib）。

## 2. 指标速查表（本项目用得到的 14 个 + 直通类）

| 指标 | 签名（默认参数） | 输入 | 输出 | 备注 |
|---|---|---|---|---|
| **MA** | `MA(close, timeperiod=30, matype=0)` | close | 1 | 简单均线；`matype` **仅支持 0（SMA）** |
| SMA | `SMA(close, timeperiod=30)` | close | 1 | MA 的纯 SMA 别名 |
| **EMA** | `EMA(close, timeperiod=30)` | close | 1 | 指数均线 |
| **MACD** | `MACD(close, fastperiod=12, slowperiod=26, signalperiod=9)` | close | 3：`(dif, dea, hist)` | dif=快慢线差，dea=信号线，hist=柱 |
| **RSI** | `RSI(close, timeperiod=14)` | close | 1 | |
| **BOLL** | `BBANDS(close, timeperiod=5, nbdevup=2.0, nbdevdn=2.0, matype=0)` | close | 3：`(upper, middle, lower)` | 布林带；函数名是 BBANDS |
| **KDJ** | `STOCH(high, low, close, fastk_period=5, slowk_period=3, slowd_period=3, ...)` | H,L,C | 2：`(K, D)` | **J 需自算**：`J = 3*K - 2*D` |
| **ATR** | `ATR(high, low, close, timeperiod=14)` | H,L,C | 1 | 真实波幅 |
| **OBV** | `OBV(close, volume)` | close,volume | 1 | 能量潮 |
| **WR** | `WILLR(high, low, close, timeperiod=14)` | H,L,C | 1 | 威廉指标；函数名 WILLR |
| **CCI** | `CCI(high, low, close, timeperiod=14)` | H,L,C | 1 | |
| **DMI(ADX)** | `ADX(high, low, close, timeperiod=14)` | H,L,C | 1 | 趋势强度线 |
| **DMI(+DI)** | `PLUS_DI(high, low, close, timeperiod=14)` | H,L,C | 1 | +DI |
| **DMI(-DI)** | `MINUS_DI(high, low, close, timeperiod=14)` | H,L,C | 1 | -DI |
| **SAR** | `SAR(high, low, acceleration=0.02, maximum=0.2)` | H,L | 1 | 抛物线 |

> WR/WR：业务叫"WR 威廉指标"，对应函数 `WILLR`。
> 业务叫"DMI 趋向指标"，akquant 没有单一 DMI 函数，需 `ADX + PLUS_DI + MINUS_DI` 三个组合。
> 业务叫"KDJ"，对应 `STOCH`（返回 K、D），J 自算。
> 业务叫"BOLL 布林带"，对应 `BBANDS`。

## 3. KDJ 与 DMI 的组合 workaround

```python
import akquant.talib as talib

# KDJ（K、D 来自 STOCH，J 自算）
slowk, slowd = talib.STOCH(high, low, close, fastk_period=9, slowk_period=3, slowd_period=3)
j = 3.0 * slowk - 2.0 * slowd        # 重写后 engine indicator_provider.py 的做法

# DMI（ADX + PLUS_DI + MINUS_DI 三线）
adx    = talib.ADX(high, low, close, timeperiod=14)
plus_di   = talib.PLUS_DI(high, low, close, timeperiod=14)
minus_di  = talib.MINUS_DI(high, low, close, timeperiod=14)
```

## 4. 在 Strategy.on_bar 内实时算（推荐）

```python
import akquant.talib as talib
from akquant import Strategy, Bar

class Demo(Strategy):
    def __init__(self):
        super().__init__()
        self.warmup_period = 60

    def on_bar(self, bar: Bar):
        # get_history 返回 np.ndarray，含当前 bar
        c = self.get_history(60, field="close")
        h = self.get_history(60, field="high")
        l = self.get_history(60, field="low")
        if len(c) < 60:
            return
        ma5  = talib.MA(c, timeperiod=5)[-1]
        ma20 = talib.MA(c, timeperiod=20)[-1]
        dif, dea, hist = talib.MACD(c)
        rsi = talib.RSI(c, timeperiod=14)[-1]
        upper, mid, lower = talib.BBANDS(c, timeperiod=20, nbdevup=2.0, nbdevdn=2.0)
        # ... 据此下单
```

## 5. ⭐ 与 stock-engine 选股/因子计算的映射对齐

engine 的因子/选股计算（重写后将位于 `stock-engine/services/factor/compute/indicator_provider.py`）的 `factorKey → akquant.talib 函数名` 映射如下（**两处指标必须用同一套 talib，保证回测信号与选股信号口径一致**）：

| factorKey（业务名） | akquant.talib 函数 | 所需输入列 | 特殊处理 |
|---|---|---|---|
| `MA` | `MA` | close | matype=0（SMA） |
| `EMA` | `EMA` | close | |
| `BOLL` | `BBANDS` | close | 返回 upper/mid/lower |
| `SAR` | `SAR` | high, low | |
| `MACD` | `MACD` | close | 返回 dif/dea/hist |
| `RSI` | `RSI` | close | |
| `KDJ` | `STOCH` | high, low, close | STOCH 返回 2 元组 (K,D)；**J=3K−2D 需自算**（与 §2/§3 一致） |
| `ADX` | `ADX` | high, low, close | |
| `PLUS_DI` | `PLUS_DI` | high, low, close | |
| `MINUS_DI` | `MINUS_DI` | high, low, close | |
| `WILLR` | `WILLR` | high, low, close | WR |
| `CCI` | `CCI` | high, low, close | |
| `ATR` | `ATR` | high, low, close | |
| `OBV` | `OBV` | close, volume | |
| `CLOSE`/`HIGH`/`LOW`/`VOLUME` | （直通） | 对应列 | `NumpySimpleProvider`，不调 talib |
| `VOL_MA`/`VOL_EMA` | （自实现 SMA/EMA） | volume | `NumpySimpleProvider`，不调 talib |

> ⚠️ `indicator_provider.py` 随 engine 业务层废案清空，**待基于统一 Schema 重写**；重写时锁定 `akquant==0.2.47`（`stock-engine/requirements.txt`）。参数名（`timeperiod`/`fastperiod`/`nbdevup` 等）以 0.2.47 为准。

## 6. 两层关系：回测算 vs 选股算（同一套 talib）

| 场景 | 入口 | 特点 |
|---|---|---|
| **回测策略内** | `Strategy.on_bar` 内 `get_history` + `talib.*` | 有状态、逐 bar 增量、带历史 |
| **选股/因子预计算** | 重写后 `services/factor/compute/indicator_provider.py` 调 `akquant.talib.*` | **无状态**，对 watcher 传入的整段 OHLCV 一次性算 |

二者共用同一份 `akquant.talib`，**口径天然一致**。本册的签名表对两层都适用。

## 7. 相关分册

- on_bar / get_history → [03-strategy-api.md](./03-strategy-api.md) §4
- NaN 处理（指标前置 NaN）→ [09-pitfalls-conventions.md](./09-pitfalls-conventions.md)
