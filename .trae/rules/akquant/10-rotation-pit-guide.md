# 10 · 轮动范式 point-in-time 防坑指南

> **面向 AI**：轮动范式（rebalance）回测的数据正确性要点。source：spec 010-rotation-data-governance。

## 1. 三类典型 Pit

### 1.1 成分股 point-in-time 泄露（缺陷 A）

**现象**：universe=csi300，回测区间 2022-01 ~ 2024-12，股票 X 于 2024-06 入选。若不做 point-in-time 过滤，X 会进入整个回测的候选池，2022 年就能买入——lookahead bias。

**修复**：每个调仓日调 `WatcherClient.get_constituents_at` 过滤。

### 1.2 基本面因子静默退化为 NaN（缺陷 B）

**现象**：watcher 下发的 bar 只含 OHLCV，引用 PE_TTM/ROE_TTM 的因子全部算出 NaN，含 NaN 的 symbol 被 `_filter_valid_symbols` 剔除，极端情况下整个 universe 被过滤光，rebalance 静默变「不调仓」。

**修复**：watcher `buildKlineData` 补齐 daily_basic 字段；engine 解析时放入 Bar 的 `extra` dict；`_compute_factor` 从 `candidate["extra"]` 取值。

### 1.3 选股模型分层混淆（缺陷 C）

**现象**：「因子打分公式」（连续数值计算）与「硬性筛选阈值」（离散布尔判断）混在 screen_config.conditions 一个字段。

**修复**：screen_config 重构为 4 层（universe/factor/filter/portfolio），对齐 akquant 的 rebalance_to_topn 执行链路。

## 2. akquant 时序保证（已具备，无需改）

| 环节 | 保证 | 机制 |
|---|---|---|
| `on_daily_rebalance` 取数 | 只见 T-1 及更早 | `_framework_history_cutoff_ns` 截断 |
| `on_daily_rebalance` 时机 | 在 `on_bar` 之前，当日 bar 不可见 | `hide_current_event=True` |
| 订单成交 | T+1 开盘 | 默认 `fill_policy: open + bar_offset=1` |

## 3. engine → watcher 参考数据查询边界

仅限 `/api/internal/*` 只读端点，幂等无副作用：
- `POST /api/internal/constituents/query`：成分股 point-in-time 快照

**禁止**：engine 反向拉行情/基本面（强约束）。

## 4. screen_config 4 层与 akquant 链路对应

| 层 | screen_config 字段 | akquant 执行点 |
|---|---|---|
| ① Universe | `universe.pool` + `point_in_time` | 决定 `get_history_map` 的 symbols + WatcherClient 过滤 |
| ② Factor Scoring | `factor.weights` | 构造 scores 字典 |
| ③ Filter | `filter.conditions` + `exclude_*` | scores 前置过滤 |
| ④ Portfolio | `portfolio.top_n` + `trading_config.rebalance.weight_mode` | `rebalance_to_topn(top_n, weight_mode)` |

## 5. 相关分册
- 策略 API 与范式边界 → [03-strategy-api.md](./03-strategy-api.md) §12
- 数据输入（含 extra） → [02-data-input.md](./02-data-input.md) §4
- 回测入口 → [04-backtest-run.md](./04-backtest-run.md)
