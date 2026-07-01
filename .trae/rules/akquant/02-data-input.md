# 02 · 数据输入格式

> **面向 AI**：`run_backtest(data=...)` 接受哪几种数据形态、列有什么要求、以及**最关键的衔接点**——把 watcher 经 HTTP 传入的 `kline_data`(list[dict]) 转成 akquant 能吃的 DataFrame。

源码依据：`backtest/engine.py` 的 `BacktestDataInput` 类型别名、`utils.prepare_dataframe`、`__init__.create_bar`。

---

## 1. `data` 接受的五种形态

```python
BacktestDataInput = Union[
    pd.DataFrame,            # 单标的
    Dict[str, pd.DataFrame], # 多标的，key=symbol
    List[Bar],               # Bar 对象列表
    DataFeed,                # 高级：数据流（本项目不用）
    DataFeedAdapter,         # 高级：CSV/Parquet 适配器（本项目不用）
]
```

本项目只用前三种，**绝大多数场景用 `pd.DataFrame`（单标的）或 `Dict[str, pd.DataFrame]`（多标的）**。

## 2. DataFrame 的硬性要求

| 要求 | 说明 |
|---|---|
| **索引** | 必须是 `DatetimeIndex`（日期或日期时间） |
| **必备列** | `open` `high` `low` `close` `volume`（小写） |
| **可选列** | `symbol`（多标的 DataFrame 合并时用） |
| **排序** | 按时间升序（akquant 内部会处理，但建议先排好） |
| **频率** | 日线最常见；分钟线也支持 |

```python
import pandas as pd

df = pd.DataFrame(
    {
        "open":   [...],
        "high":   [...],
        "low":    [...],
        "close":  [...],
        "volume": [...],
    },
    index=pd.DatetimeIndex(["2024-01-02", "2024-01-03", ...]),
)
```

> 若 DataFrame 没有DatetimeIndex，但有 `date`/`timestamp`/`datetime` 列，优化模块 `optimize.py` 的 `_ensure_dataframe_time_index` 会自动认这些列名设为索引；但**回测主路径最稳妥是自己设好 DatetimeIndex**。

## 3. 单标的 vs 多标的

```python
# 单标的：直接传 DataFrame
aq.run_backtest(data=df, strategy=S, symbols="000001.SZ", ...)

# 多标的：传 Dict[str, DataFrame]，symbols 传列表
data = {"000001.SZ": df1, "600000.SH": df2}
aq.run_backtest(data=data, strategy=S, symbols=["000001.SZ", "600000.SH"], ...)
```

> 多标的时，`on_bar(bar)` 会被每个标的的每根 bar 依次触发，`bar.symbol` 区分。`get_history(count, symbol=...)` 按 symbol 取历史。

## 4. `List[Bar]` 形态（合成/测试数据用）

适合造合成数据做冒烟测试（见 [08](./08-recipes-stock-engine.md) 的冒烟脚本）：

```python
from akquant import create_bar   # 或 aq.Bar(...)
import pandas as pd

bars = [
    create_bar(
        timestamp=int(pd.Timestamp("2024-01-02 15:00", tz="UTC").value),  # 纳秒
        open_px=10.0, high_px=10.1, low_px=9.9, close_px=10.0,
        volume=10000.0, symbol="TEST",
    ),
    # ...
]
aq.run_backtest(data=bars, strategy=S, symbols="TEST", ...)
```

- `create_bar(timestamp, open_px, high_px, low_px, close_px, volume, symbol)` 是 `__init__.py` 的便捷函数。
- `timestamp` 是 **UTC 纳秒**整数（`pd.Timestamp(...).value`）。
- `Bar` 字段：`timestamp`(int 纳秒) / `symbol`(str) / `open`/`high`/`low`/`close`/`volume`(float) / `extra`(dict) / `timestamp_iso`(str)。

## 5. 数据辅助函数

| 函数 | 作用 |
|---|---|
| `akquant.prepare_dataframe(df)` | 规范化 DataFrame（索引/列/排序），内部被 `run_backtest` 调用；手动构造数据时可先用它兜底 |
| `akquant.load_bar_from_df(...)` | DataFrame → Bar 序列 |
| `akquant.create_bar(...)` | 构造单个 Bar |

一般无需手动调，传 DataFrame 即可。

---

## 6. ⭐ watcher `kline_data`(list[dict]) → akquant DataFrame（核心衔接）

stock-watcher 通过 HTTP 把 K 线传给 engine，载荷形如（字段名以 watcher 实际返回为准，典型为 `date` + OHLCV）：

```json
[
  {"date": "2024-01-02", "open": 10.0, "high": 10.2, "low": 9.9,  "close": 10.1, "volume": 100000},
  {"date": "2024-01-03", "open": 10.1, "high": 10.4, "low": 10.0, "close": 10.3, "volume": 120000}
]
```

转换配方（**engine 侧标准做法**）：

```python
import pandas as pd

def kline_to_df(kline_data: list[dict]) -> pd.DataFrame:
    """watcher 传入的 K 线 list[dict] → akquant 可用的 DataFrame。"""
    df = pd.DataFrame(kline_data)
    if df.empty:
        raise ValueError("K线数据为空")

    # 1. 时间列转 DatetimeIndex（注意核对 watcher 实际字段名：date / trade_date / datetime）
    time_col = next((c for c in ("date", "trade_date", "datetime", "timestamp") if c in df.columns), None)
    if time_col is None:
        raise ValueError("缺少时间列(date/trade_date/datetime/timestamp)")
    df[time_col] = pd.to_datetime(df[time_col])
    df = df.set_index(time_col).sort_index()

    # 2. 只保留 akquant 需要的列，并确保数值类型
    keep = ["open", "high", "low", "close", "volume"]
    df = df[[c for c in keep if c in df.columns]]
    df = df.astype("float64")
    return df
```

> ⚠️ **落地前核对 watcher 真实字段名**：watcher 的回测请求 schema 在 `stock-engine/models/schemas/backtest.py`，对照确认 `date`/`trade_date` 与 OHLCV 的确切 key 后再定 `time_col` 和列名映射。`strategy_runner.py` 现在直接 `pd.DataFrame(kline_data)` 后没有做索引/类型规范化——**这正是后续要补的**，本配方即为其蓝本。

多标的时，按 symbol 分组后构造 `Dict[str, pd.DataFrame]`：

```python
def kline_list_to_map(kline_data: list[dict]) -> dict[str, pd.DataFrame]:
    df = pd.DataFrame(kline_data)
    out = {}
    for sym, g in df.groupby("symbol"):        # 前提：每条 dict 带 symbol 字段
        out[str(sym)] = kline_to_df(g.to_dict("records"))
    return out
```

## 7. 数据预处理注意（与 watcher 清洗对齐）

watcher 侧已做前复权（`price × adj_factor`）、剔除停牌/涨跌停/ST（见 `CLAUDE.md` 硬约束段）。**engine 拿到的数据默认已是清洗后的前复权日线**，无需在 engine 侧再复权。只需保证：

- 时间升序、无重复索引；
- `volume` 为正数；
- 缺失值处理：`NaN` 在 talib 指标里会传播，建议上游补齐或剔除。

## 8. 相关分册

- 拿到 DataFrame 后怎么写策略 → [03-strategy-api.md](./03-strategy-api.md)
- 怎么跑回测 → [04-backtest-run.md](./04-backtest-run.md)
- 完整端到端配方（含转换+回测+取结果）→ [08-recipes-stock-engine.md](./08-recipes-stock-engine.md)
