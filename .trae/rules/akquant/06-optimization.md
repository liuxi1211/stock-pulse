# 06 · 参数优化：grid_search 与 walk_forward

> **面向 AI**：参数寻优和滚动样本外验证。源码：`akquant-0.2.47/python/akquant/optimize.py`（`run_grid_search` / `run_walk_forward`）。

## 1. run_grid_search 网格寻优

```python
akquant.run_grid_search(
    strategy,                  # Type[Strategy]，策略类（不是实例）
    param_grid,                # {"fast": [5,10], "slow": [20,30]}
    data=None,                 # 同 run_backtest 的 data
    max_workers=None,          # 并行进程数，默认 cpu_count()
    sort_by="sharpe_ratio",    # 排序指标，str 或 List[str]
    ascending=False,           # 升降序，bool 或 List[bool]
    return_df=True,            # True→DataFrame，False→List[OptimizationResult]
    warmup_calc=None,          # f(params)->int，动态预热期
    constraint=None,           # f(params)->bool，True 保留
    result_filter=None,        # f(metrics)->bool，True 保留
    timeout=None,              # 单任务超时秒
    max_tasks_per_child=None,  # worker 重启频率（设 1 清理超时线程）
    db_path=None,              # ⚠️ 见 §3，本项目一般不传
    forward_worker_logs=False,
    **kwargs,                  # 透传 run_backtest（initial_cash/broker_profile/t_plus_one/...）
) -> pd.DataFrame | List[OptimizationResult]
```

### 关键点

- **`param_grid` 的 key 必须是策略 `__init__` 的形参名**（`strict_strategy_params=True` 时会校验）。每组参数被合并进 kwargs 传给策略构造函数。
- 策略类**必须定义在可 import 的模块里**（不能在 `__main__`），否则多进程 pickle 失败。
- `sort_by` 可用 `metrics` 里任意指标：`sharpe_ratio` / `total_return_pct` / `max_drawdown_pct` / `sortino_ratio` / `win_rate` / `profit_factor` / `calmar_ratio` 等。
- `max_workers>1` 时参数必须可 pickle：**避免 lambda/局部回调、用 `fill_policy` dict 而非闭包**。

### 示例

```python
import akquant as aq

result_df = aq.run_grid_search(
    strategy=DoubleMa,
    param_grid={"fast": [5, 10, 20], "slow": [20, 30, 60]},
    data=df,
    symbols="000001.SZ",
    initial_cash=100_000,
    broker_profile="cn_stock_miniqmt",
    t_plus_one=True,
    sort_by="sharpe_ratio",
    max_workers=4,
)
print(result_df.head())                       # 已按 sharpe 降序
best = result_df.iloc[0].to_dict()            # 最优参数 + 指标
```

返回 DataFrame 的列 = 参数列 + 指标列 + `_duration`。

### 约束与过滤

```python
aq.run_grid_search(
    ..., param_grid={"fast":[5,10], "slow":[20,30]},
    constraint=lambda p: p["fast"] < p["slow"],          # 只保留 fast<slow
    result_filter=lambda m: m["max_drawdown_pct"] < 20,  # 回撤>20% 剔除
)
```

## 2. run_walk_forward 滚动样本外验证

把数据切成多个「训练集+测试集」窗口，每窗在训练集 grid_search 选最优参数 → 在测试集样本外回测 → 拼接所有样本外资金曲线。

```python
akquant.run_walk_forward(
    strategy,
    param_grid,
    data,                      # DataFrame 或 Dict[str,DataFrame]
    train_period,              # 训练窗口 bar 数
    test_period,               # 测试窗口 bar 数
    metric="sharpe_ratio",     # 选优指标，str 或 List[str]
    ascending=False,
    initial_cash=100_000.0,
    warmup_period=0,
    warmup_calc=None,
    constraint=None,
    result_filter=None,
    compounding=False,         # True 复利拼接，False 累加盈亏（默认）
    timeout=None,
    max_tasks_per_child=None,
    **kwargs,                  # 透传 run_backtest
) -> pd.DataFrame              # 拼接的样本外资金曲线（含 train_start/train_end/各 best 参数列）
```

### 示例

```python
wf = aq.run_walk_forward(
    strategy=DoubleMa,
    param_grid={"fast":[5,10], "slow":[20,30]},
    data=df,
    train_period=120,          # 训练 120 bar
    test_period=30,            # 测试 30 bar
    metric="sharpe_ratio",
    initial_cash=100_000,
    broker_profile="cn_stock_miniqmt",
    t_plus_one=True,
)
# wf 含 equity 列（样本外拼接）+ 每段最优参数，可画样本外资金曲线
```

> 要求数据长度 ≥ `train_period + test_period`，否则报错。

## 3. ⚠️ `db_path` 断点续传与 engine 硬约束

`db_path` 会让 akquant **内部用 sqlite3** 建一个优化结果检查点库，支持中断续跑。

- **engine 硬约束**（见 `CLAUDE.md`）：engine 侧**禁止出现 `sqlite3`/`sqlalchemy`/直连 `.db` 的代码**，数据单源性在 watcher。
- akquant 内部 `import sqlite3` 是它自己的实现，**不算 engine 写 sqlite3 代码**；但传 `db_path` 会在 engine 机器上落一个 `.db` 文件。
- **本项目建议：默认不传 `db_path`**（短/中规模寻优无需续跑）。确需长任务续跑时，只用**临时目录**（如 `tempfile`）并跑完即删，绝不与 watcher 的业务 SQLite 混淆。

## 4. 参数声明（ParamModel，结构化搜索空间）

`akquant.params` 提供 Pydantic 风格的参数声明，适合策略要暴露结构化参数 schema 的场景：

```python
from akquant import ParamModel, IntParam, FloatParam, ChoiceParam

class MyParams(ParamModel):
    fast: IntParam = IntParam(default=5, min_value=2, max_value=50)
    slow: IntParam = IntParam(default=20, min_value=5, max_value=120)
    mode: ChoiceParam = ChoiceParam(default="gold", choices=["gold","cross"])

# 辅助：
akquant.get_strategy_param_schema(DoubleMa)              # 反推策略 __init__ 参数 schema
akquant.validate_strategy_params(DoubleMa, {"fast":5})   # 校验
akquant.build_param_grid_from_search_space(...)          # 搜索空间 → param_grid
```

> 简单寻优直接用 `param_grid` dict 即可；需要前端可视化编辑参数空间时再上 ParamModel。

## 5. 相关分册

- 回测入口参数 → [04-backtest-run.md](./04-backtest-run.md)
- 指标名（`sort_by`/`metric` 取值）→ [05-result-metrics.md](./05-result-metrics.md)
- 优化多进程可 pickle 注意 → [09-pitfalls-conventions.md](./09-pitfalls-conventions.md)
