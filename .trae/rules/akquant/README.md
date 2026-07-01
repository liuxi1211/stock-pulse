# akquant 知识库（A 股回测导向）

> **面向 AI**：本目录是 akquant 框架的**权威方法参考**。stock-engine 的回测/策略/指标全部直接调用 akquant，**不自行实现**。写回测代码前先查这里，**不要凭记忆**。
>
> 版本锁定：**akquant 0.2.47**（源码见 `akquant-0.2.47/`）。所有签名已与该版本源码逐项核对。
>
> 校验状态：API 签名已与源码**静态核对一致**（run_backtest 全部参数 / Strategy 全部方法 / BacktestResult 全部属性 / talib 指标函数）。

---

## 文件索引（什么时候查哪里）

| 想做的事 | 看这个文件 |
|---|---|
| 快速理解框架全貌 / 最小调用链 / 公开 API 地图 | [01-overview.md](./01-overview.md) |
| 把行情数据喂给回测（DataFrame / 多标的 / Bar / watcher JSON 转换） | [02-data-input.md](./02-data-input.md) |
| 写策略：on_bar、bar 对象、持仓/资金查询、下单方法 | [03-strategy-api.md](./03-strategy-api.md) |
| 跑回测：`run_backtest` 参数、佣金/印花税/滑点/T+1/broker_profile | [04-backtest-run.md](./04-backtest-run.md) |
| 取结果：指标、权益曲线、trades、序列化成 JSON 返回 watcher | [05-result-metrics.md](./05-result-metrics.md) |
| 参数寻优 / Walk-forward 滚动验证 | [06-optimization.md](./06-optimization.md) |
| 技术指标速查（MA/MACD/RSI/KDJ/BOLL/ATR…）签名 | [07-talib-indicators.md](./07-talib-indicators.md) |
| 可直接落地的策略配方（双均线/MACD/RSI/动量轮动…） | [08-recipes-stock-engine.md](./08-recipes-stock-engine.md) |
| 防坑清单（KDJ、MA、T+1、NaN、印花税方向…） | [09-pitfalls-conventions.md](./09-pitfalls-conventions.md) |

---

## 30 秒上手：A 股回测最小骨架

```python
import akquant as aq
from akquant import Strategy, Bar

class DoubleMa(Strategy):
    def __init__(self, fast: int = 5, slow: int = 20):
        super().__init__()
        self.fast, self.slow = fast, slow
        self.warmup_period = slow          # 必须 ≥ get_history 取的长度

    def on_bar(self, bar: Bar):
        closes = self.get_history(self.slow, field="close")   # np.ndarray，含当前 bar
        if len(closes) < self.slow:
            return
        fast_ma = closes[-self.fast:].mean()
        slow_ma = closes[-self.slow:].mean()
        pos = self.get_position(bar.symbol)
        if fast_ma > slow_ma and pos == 0:
            self.order_target_percent(bar.symbol, 0.95)
        elif fast_ma < slow_ma and pos > 0:
            self.close_position(bar.symbol)

result = aq.run_backtest(
    data=df,                               # DataFrame(DatetimeIndex + OHLCV)
    strategy=DoubleMa,
    symbols="000001.SZ",
    initial_cash=100_000,
    broker_profile="cn_stock_miniqmt",     # 注入 A 股佣金/印花税/滑点/手数
    t_plus_one=True,                       # ⚠️ broker_profile 不会自动开 T+1
    timezone="Asia/Shanghai",
    show_progress=False,
)
print(result)                              # 打印核心指标
print(result.metrics.sharpe_ratio)         # 取单个指标
```

---

## 与 stock-engine 的关系（边界）

- 本库只讲 **akquant 框架本身**的能力与用法（换到别的项目也成立）。
- **本项目如何封装 akquant**（JSON 策略配置 → 动态 Strategy 类、HTTP API、结果序列化对接 watcher）：集成层为废案已清空，待基于「统一策略配置 Schema」（`sdlc/prd/004-策略管理/统一策略配置Schema.md`）重写；本库只覆盖框架本身能力。
- **硬约束**：engine 侧**禁止** `sqlite3`/`sqlalchemy`/直连 `.db`；行情数据一律由 watcher 经 HTTP 传入（见 `../../../CLAUDE.md`「硬约束」段）。本库所有示例的数据来源都按"watcher 传入"描述。

---

## 超纲能力（akquant 有，但本项目不用，AI 别展开）

akquant 还内置下列能力，**stock-engine 当前不使用**，遇到时知道"存在"即可，不要据此设计：

- 期货 / 期权（`futures/`、`option/`、ChinaFutures/Options Config）
- 实盘交易网关（`gateway/`：CTP / miniqmt / ptrade broker、`live.py`）
- 机器学习框架（`ml/`、`strategy_ml.py`、`prepare_features` 自动训练）
- 流式回测 / WebSocket 指标流（`*_streaming_*` examples、`indicator_stream.py`）
- Warm Start 快照恢复（`run_warm_start`、`save_snapshot`，回测续跑）
- 自定义 Analyzer 插件、保证金强平审计

> 本项目范围 = **A 股股票日线回测**：数据输入 + Strategy + `run_backtest` + 结果指标 + grid_search/walk_forward + talib 指标 + 报告。

---

## 参考来源

- 源码：`akquant-0.2.47/python/akquant/`（`backtest/engine.py`、`backtest/result.py`、`strategy.py`、`optimize.py`、`talib/funcs.py`）
- 示例：`akquant-0.2.47/examples/`（`strategies/01_stock_dual_moving_average.py`、`46_broker_profile_demo.py`、`01_quickstart.py` 等）
- 本项目集成设计：待基于统一 Schema 重写（旧 `stock-engine/business/02-akquant 交互层架构设计.md` 已删）
- 本项目指标落地：待重写 `stock-engine/services/factor/compute/indicator_provider.py`（factorKey↔talib 契约见 [07](./07-talib-indicators.md) 与统一 Schema §4.5）
