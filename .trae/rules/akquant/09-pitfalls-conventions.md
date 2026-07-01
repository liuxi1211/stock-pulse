# 09 · 坑与约定（AI 防错清单）

> **面向 AI**：把分散在各分册的"坑"汇成一页清单。写 akquant 代码前扫一遍，避免踩。每条都经 0.2.47 源码核实。

## 入口与对象

- ✅ 回测入口是**函数** `aq.run_backtest(data=..., strategy=..., ...)`。**没有** `aq.Backtest` 类（`strategy_runner.py` 旧注释里的 `aq.Backtest(df)` 是错的）。
- ✅ 返回 `BacktestResult`。取报告用 `result.report(...)`（便捷方法），不是 `aq.plot.plot_report`（也能用，但前者更简）。
- ✅ `buy`/`sell`/`submit_order`/`place_trailing_stop*`/`place_bracket_order` 返回 `order_id`(str)；`order_target*` 返回 `Optional[str]`/`List[str]`；`buy_all`/`close_position`/`short`/`cover`/`stop_buy`/`stop_sell` 返回 `None`。
- ✅ `get_history(count, field=...)` 返回的 np.ndarray **含当前 bar**，长度 = `count`（预热不足时更短）。

## 指标名映射（业务名 ≠ 函数名）

| 业务叫法 | akquant.talib 函数 | 备注 |
|---|---|---|
| BOLL 布林带 | `BBANDS` | 返回 (upper, mid, lower) |
| WR 威廉 | `WILLR` | |
| KDJ | `STOCH` | 返回 (K, D)；**J = 3\*K − 2\*D 自算** |
| DMI 趋向 | `ADX` + `PLUS_DI` + `MINUS_DI` | 三个函数组合，没有单一 DMI |
| MA | `MA`（`matype=0`）/ `SMA` | **MA 的 `matype` 仅支持 0（SMA）**；WMA/DEMA/TEMA 是各自独立函数 |

> 选股/因子侧（重写后 `indicator_provider.py`）用的是同一套映射，回测信号与选股信号口径一致。详见 [07](./07-talib-indicators.md) §5。

## A 股实盘规则

- ✅ `t_plus_one=True` 必须显式传。**`broker_profile` 三个模板都不含 T+1**。
- ✅ `lot_size=100`（A 股每手 100 股）。**默认是 1**，不设会按 1 股下单。`broker_profile` 已含 100。
- ✅ `stamp_tax_rate`（印花税）**只在卖出时扣**，A 股 0.001。
- ✅ 佣金 `commission_rate` 万三（0.0003），`min_commission` 5 元，`broker_profile` 已含。
- ✅ T+1 下卖出前看**可用持仓**：`self.get_available_position()`（不是 `get_position()`）。
- ✅ 时区 `timezone="Asia/Shanghai"`（默认即是，显式更稳）。

## 费用与滑点

- ✅ `slippage` **一律用 dict**：`{"type":"percent|fixed|ticks|zero","value":...}`。
  - 裸 `float`（如 `slippage=0.2`）**已弃用**且按 percent 解析 → `0.2` 会被当成 **20% 滑点**，不是 0.2 元。
  - 要固定价格偏移用 `{"type":"fixed","value":0.2}`。
- ✅ `commission_policy` 三种 type：`percent`/`fixed`/`per_unit`。优先级高于 `commission_rate`。

## 成交语义

- ✅ 用 `fill_policy` 控制"哪根 bar、什么价成交"：`{"price_basis":"open|close|ohlc4|hl2","temporal":"same_cycle|next_event","bar_offset":0|1}`。`open`→offset 必须 1，`close`→0 或 1，`ohlc4`/`hl2`→必须 1。
- ✅ 旧参数 `execution_mode` / `timer_execution_policy` **已移除**，传了会报错，统一用 `fill_policy`。
- ✅ 便捷构造：`aq.make_fill_policy(price_basis="open", temporal="next_event")`（关键字参数）。

## 历史/预热（get_history 拿不到数据的头号原因）

- ✅ `get_history(count)` 能取多少，取决于 `max(warmup_period, history_depth)`。
- ✅ 最简做法：策略 `__init__` 里 `self.warmup_period = 你要的最大窗口`，不用管 `history_depth`。
- ✅ `count` 必须 ≤ 实际深度，否则数组短于预期（用 `len(closes) < n` 兜底）。

## 指标单位与序列化

- ✅ 带 `_pct` 的指标（`total_return_pct`/`max_drawdown_pct`/`win_rate`…）是**原始百分数**（15.0=15%），转小数 ÷100。
- ✅ `max_drawdown_pct`/`max_drawdown_value` 以**正数**存储。
- ✅ `metrics_df` 列名：单回测是 `value`，优化场景可能是 `Backtest`。序列化用 `metrics_df.iloc[:, 0]` 或先判列名兼容。
- ✅ 序列化 JSON 前，所有数值过 `_num()`：`NaN`/`Inf` → `None`，`Timestamp` → isoformat，`Timedelta` → total_seconds。talib 指标前置 `NaN` 尤其注意。
- ✅ 模板见 [05](./05-result-metrics.md) §5。

## 多进程优化（grid_search）

- ✅ 策略类**必须定义在可 import 的模块**里（不能在 `__main__` 或 notebook 内联），否则多进程 pickle 失败。
- ✅ `max_workers>1` 时所有参数须可 pickle：**不要 lambda/局部函数回调**，用 `fill_policy` dict 而非闭包。
- ✅ `param_grid` 的 key 必须是策略 `__init__` 形参名（`strict_strategy_params=True` 校验）。
- ✅ `db_path`（断点续传）会让 akquant 内部用 sqlite3 落库。**engine 硬约束禁止 sqlite3/sqlalchemy**——本项目**默认不传 `db_path`**；确需续跑只用临时目录并跑完即删。

## engine 硬约束（不可违背）

- ✅ engine 侧**禁止** `sqlite3` / `sqlalchemy` / 直连 `.db` 的代码。数据单源性在 watcher。
- ✅ 行情数据一律由 watcher 经 HTTP 传入 → 在 engine 转 DataFrame → 喂回测（见 [02](./02-data-input.md) §6、[08](./08-recipes-stock-engine.md) §7）。
- ✅ 结果序列化成 JSON 回传 watcher，engine 不落业务库。

## 版本

- ✅ 锁定 **akquant 0.2.47**（`stock-engine/requirements.txt`；源码 `akquant-0.2.47/`）。
- ✅ `indicator_provider.py` 随 engine 废案清空、待重写；旧文件头注释写的 "akquant 0.2.34" 已随之失效，重写时锁定 0.2.47。

## 相关分册

逐条展开见：[01 总览](./01-overview.md) · [02 数据](./02-data-input.md) · [03 策略](./03-strategy-api.md) · [04 回测](./04-backtest-run.md) · [05 结果](./05-result-metrics.md) · [06 优化](./06-optimization.md) · [07 指标](./07-talib-indicators.md) · [08 配方](./08-recipes-stock-engine.md)。
