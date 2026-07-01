# 回测中心 — 数据库设计方案

> 设计依据：
> - PRD：`sdlc/prd/004-回测中心/004-回测中心PRD.md`
> - 原型图：`sdlc/prd/004-回测中心/prototype/` 下的 HTML 页面（表单字段 → 表字段映射）
> - 现有 schema：`stock-watcher/src/main/resources/schema.sql`

---

## 0. 现有表复用检查（基于实时扫描结果）

| 现有表（来自实时扫描） | PRD 是否需要 | 复用决策 | 说明 |
|---------------------|-------------|---------|------|
| `sys_user` | 是 | **直接复用** | 回测记录关联 user_id 实现用户隔离，不修改表结构 |
| `daily_quote` | 是 | **直接复用（只读）** | 按 ts_code + trade_date 读取历史 OHLCV 数据构建 Python 入参 |
| `stock_basic` | 是 | **直接复用（只读）** | 选股范围代码有效性校验 + 股票名称映射 |
| `trade_cal` | 是 | **直接复用（只读）** | 日期范围的交易日有效性校验 |
| `adj_factor` | 是 | **直接复用（只读）** | 复权处理（Java 端数据清洗时已处理，回测直接使用清洗后数据） |
| `dividend` | 否 | **不使用** | 回测暂不考虑分红再投的复杂场景 |
| `quant_strategy` | 是 | **直接复用（只读+有限回写）** | 由策略管理模块维护，本模块读取策略 JSON、应用最优参数时回写参数；不修改表结构 |
| `sys_watchlist` | 否 | **不使用** | 选股范围不从自选股读取 |

> 「直接复用」的表不在 03-schema.sql 中重复定义；本模块新增 4 张回测相关表，以 CREATE TABLE 新增。

---

## 1. 设计原则

- **SQLite 单文件 + WAL 并发**（与现有库一致）
- **表名/字段名：全小写 + 下划线分词（snake_case）**，如 `quant_backtest`、`total_return_pct`
- **与现有 map-underscore-to-camel-case=true 保持一致**，Java 端自动映射为小驼峰字段（`totalReturnPct`）
- **主键策略**：业务主表使用自增主键（`INTEGER PRIMARY KEY AUTOINCREMENT`）；明细表通过外键关联主表 ID
- **所有日期字段**：业务日期用 `YYYY-MM-DD` 文本格式存储于 `start_date` / `end_date` 等字段（与 backtest-config.html 的日期选择器对齐）；记录时间戳用 `created_at` / `updated_at` / `completed_at`（ISO 格式 `YYYY-MM-DD HH:MM:SS`）
- **用户隔离**：所有回测表包含 `user_id` 字段，关联 `sys_user.id`，保证数据按用户隔离

---

## 2. 表清单

| 表名 | 用途 | 主键 | 预计量级 | 是否需要索引 | 对应原型图页面 |
|------|------|------|---------|------------|---------------|
| `quant_backtest` | 回测任务主表，记录每次回测的基本配置 + 核心指标 + 状态 | `id` (INTEGER AUTOINCREMENT) | 低：每个用户约 10~500 条 | ✅ 多字段索引 | backtest-list.html（列表展示）/ backtest-config.html（创建）|
| `quant_backtest_report` | 单次回测详细数据，净值曲线/交易流水/持仓/全量指标/基准曲线 | `backtest_id`（与主表 1:1） | 中等：每条主记录对应 1 条，JSON 字段较大 | ⚠️ 依赖主表外键，无需额外索引 | backtest-report.html（全部图表区块） |
| `quant_backtest_grid_result` | 参数优化每一组参数的指标结果 | `id` (INTEGER AUTOINCREMENT) | 高：每个优化任务可能产生 100~1000 条 | ✅ 按 backtest_id + metric_value 索引 | backtest-grid.html（参数组合表 / 散点图 / 热力图） |
| `quant_backtest_wf_result` | Walk-forward 每一段的训练期 + 验证期结果 | `id` (INTEGER AUTOINCREMENT) | 低：每个 WF 任务约 5~20 条 | ✅ 按 backtest_id + segment_index 索引 | backtest-walk-forward.html（分段时间轴 + 参数稳定性表） |

---

## 3. 逐表字段设计

### 3.1 quant_backtest — 回测任务主表

> 字段来源：
> - `sdlc/prd/004-回测中心/prototype/backtest-config.html` 表单：策略选择器、模式 Tab、日期范围 input、选股范围 option、初始资金 input、佣金率/滑点、调仓频率、最大持仓数、单票仓位
> - `sdlc/prd/004-回测中心/prototype/backtest-list.html` 列表列：策略名称 · 模式 · 回测区间 · 选股范围 · 总收益 · 夏普 · 最大回撤 · 胜率 · 状态 · 创建时间
> - PRD §9.1 定义的核心表结构

> 字段映射原则：原型图 HTML 表单 name/id → DB 表 snake_case 字段（config.html 中的「初始资金」→ `initial_cash`，「调仓频率(交易日)」→ `rebalance_frequency_days`）

| 字段名 | 类型 | 约束 | 说明 | 原型图中的来源 |
|--------|------|------|------|---------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | 主键 | — |
| `user_id` | INTEGER | NOT NULL | 创建用户，关联 `sys_user.id` | 隐含（多用户系统） |
| `strategy_id` | INTEGER | NOT NULL | 策略 ID，关联 `quant_strategy.id` | config.html 的策略选择下拉框 |
| `strategy_version` | INTEGER | NOT NULL DEFAULT 1 | 策略版本号 | 同策略下拉框旁的版本信息 |
| `task_id` | TEXT | NOT NULL UNIQUE | Java 生成的 UUID，用于 Python 计算的任务追踪 | PRD §3.1 Java↔Python 通信协议 |
| `mode` | TEXT | NOT NULL DEFAULT 'SINGLE' | 回测模式：`SINGLE` 单次 / `GRID_SEARCH` 参数优化 / `WALK_FORWARD` Walk-forward | config.html 的 3 个模式 Tab |
| `start_date` | TEXT | NOT NULL | 回测开始日期（YYYY-MM-DD） | config.html 的 `input type="date" start_date` |
| `end_date` | TEXT | NOT NULL | 回测结束日期（YYYY-MM-DD） | config.html 的 `input type="date" end_date` |
| `universe_type` | TEXT | NOT NULL | 选股范围类型：`INDEX_300` 沪深300 / `INDEX_500` 中证500 / `CUSTOM` 自定义 | config.html 的「选股范围」option 卡片 |
| `universe_codes` | TEXT | — | JSON 字符串，自定义模式下的股票代码数组，如 `["600519.SH", "000001.SZ"]` | config.html 中 universeType=CUSTOM 时的股票多选框 |
| `universe_count` | INTEGER | — | 实际股票数量（冗余字段，列表页直接展示，免去解析 JSON） | list.html 的股票数量列 |
| `initial_cash` | REAL | NOT NULL | 初始资金（人民币） | config.html 的「初始资金」输入框 |
| `commission_pct` | REAL | NOT NULL DEFAULT 0.0003 | 佣金率（默认万三 = 0.03%） | config.html 的「佣金率(%)」输入框 |
| `slippage_pct` | REAL | NOT NULL DEFAULT 0.001 | 滑点（默认 0.1%） | config.html 的「滑点(%)」输入框 |
| `rebalance_frequency_days` | INTEGER | NOT NULL DEFAULT 5 | 调仓频率（交易日） | config.html 的「调仓频率(交易日)」输入框 |
| `max_positions` | INTEGER | — | 最大持仓数（NULL 时使用策略默认值） | config.html 的「最大持仓数」输入框 |
| `max_single_position_pct` | REAL | — | 单票最大仓位比例（0.10 = 10%）（NULL 时使用策略默认值） | config.html 的「单票最大仓位(%)」输入框 |
| `benchmark_enabled` | INTEGER | NOT NULL DEFAULT 1 | 是否启用基准对比（1=是，0=否）。启用时在 report 表写入 benchmark_json | config.html 的「对比基准指数」checkbox |
| `param_grid_json` | TEXT | — | JSON 字符串，网格搜索/WF 的参数范围定义。仅 mode=GRID_SEARCH 或 WALK_FORWARD 时非空 | config.html Grid/WF Tab 中的参数候选值输入区 |
| `optimization_metric` | TEXT | — | 优化目标指标：`sharpe_ratio` / `total_return_pct` / `win_rate` / `calmar_ratio`。GRID_SEARCH/WALK_FORWARD 模式必填 | config.html 的「优化目标指标」选择区 |
| `walk_forward_json` | TEXT | — | JSON 字符串，WF 专属配置（训练窗口/验证窗口/步长）。仅 mode=WALK_FORWARD 时非空 | config.html WF Tab 中的「训练窗口/验证窗口/滚动步长」输入框 |
| `status` | TEXT | NOT NULL DEFAULT 'RUNNING' | 任务状态：`RUNNING` / `SUCCESS` / `FAILED` | list.html 的状态列（带脉冲动画的运行中 / 成功 / 失败标签） |
| `error_message` | TEXT | — | 失败原因，仅 status=FAILED 时非空 | list.html 的失败状态旁的 hover 提示 |
| `total_return_pct` | REAL | — | 核心指标：总收益率（小数，如 0.245 = +24.5%） | list.html 的「总收益」列 + report.html 顶部摘要卡片 |
| `sharpe_ratio` | REAL | — | 核心指标：夏普比率 | list.html 的「夏普」列 |
| `max_drawdown_pct` | REAL | — | 核心指标：最大回撤（负数，如 -0.123 = -12.3%） | list.html 的「最大回撤」列 |
| `win_rate` | REAL | — | 核心指标：胜率（0~1，如 0.58 = 58%） | list.html 的「胜率」列 |
| `trades_count` | INTEGER | — | 交易总次数 | report.html 顶部摘要卡片中的「共 N 笔交易」 |
| `compute_duration_ms` | INTEGER | — | Python 计算耗时（毫秒），用于性能监控 | report.html 顶部的「耗时 N秒」 |
| `created_at` | TEXT | NOT NULL | 创建时间（ISO 格式） | list.html 的「创建时间」列 |
| `updated_at` | TEXT | — | 更新时间（ISO 格式），用于状态变更记录 | 隐含 |
| `completed_at` | TEXT | — | 完成时间（ISO 格式），成功/失败时写入 | list.html 中可用于计算耗时 |

> **关键约束：**
> - `UNIQUE(task_id)` — 每个计算任务唯一
> - `FOREIGN KEY(user_id) REFERENCES sys_user(id)`
> - `FOREIGN KEY(strategy_id) REFERENCES quant_strategy(id)`
> - `CHECK(mode IN ('SINGLE', 'GRID_SEARCH', 'WALK_FORWARD'))`
> - `CHECK(status IN ('RUNNING', 'SUCCESS', 'FAILED'))`
> - `CHECK(universe_type IN ('INDEX_300', 'INDEX_500', 'CUSTOM'))`
> - **索引**：
>   - `CREATE INDEX idx_bt_user_mode_status ON quant_backtest(user_id, mode, status)` — 列表页按用户+模式+状态过滤
>   - `CREATE INDEX idx_bt_strategy ON quant_backtest(strategy_id, strategy_version)` — 策略维度回溯查询
>   - `CREATE INDEX idx_bt_created ON quant_backtest(created_at DESC)` — 按时间排序（列表页默认排序）

---

### 3.2 quant_backtest_report — 单次回测详细数据

> 字段来源：
> - `sdlc/prd/004-回测中心/prototype/backtest-report.html` 的全部数据展示区块：净值曲线双折线图、回撤曲线图、月度收益热力图、交易流水表格、详细指标表
> - PRD §10.1 中 Python 返回的数据结构

> 字段映射原则：report.html 中每个 ECharts 图所需的数据 → 对应一个 JSON 字段

| 字段名 | 类型 | 约束 | 说明 | 原型图中的来源 |
|--------|------|------|------|---------------|
| `backtest_id` | INTEGER | PRIMARY KEY | 与 `quant_backtest.id` 1:1 关联，不使用自增主键 | — |
| `equity_curve_json` | TEXT | NOT NULL | JSON 数组：策略净值曲线 `[{date: "2024-01-02", value: 1000000}, ...]` | report.html 顶部「净值曲线对比」ECharts 双折线图（策略线） |
| `benchmark_curve_json` | TEXT | — | JSON 数组：基准（沪深300）净值曲线。`benchmark_enabled=0` 时为 NULL | report.html 「净值曲线对比」双折线图（基准线） |
| `drawdown_curve_json` | TEXT | NOT NULL | JSON 数组：回撤曲线 `[{date, drawdownPct}]` | report.html 「回撤曲线」面积图 |
| `trades_json` | TEXT | NOT NULL | JSON 数组：全部交易流水。每条结构：`{date, tsCode, action(BUY/SELL), price, qty, amount, pnl, reason}` | report.html 底部「交易流水」表格（分页查询从此 JSON 中截取或由独立接口解析） |
| `positions_json` | TEXT | — | JSON 数组：调仓日的持仓快照 `[{date, positions:[{tsCode, shares, cost, value, pnlPct}]}]` | report.html 中持仓变化图（如有） |
| `metrics_json` | TEXT | NOT NULL | JSON 对象：全量指标，包含主表冗余字段之外的详细指标：年化收益率、波动率、索提诺比率、卡尔马比率、盈亏比、最大单笔盈利、平均持仓天数、基准收益率、超额收益率 等 | report.html 「详细指标」表格区块 |
| `monthly_returns_json` | TEXT | — | JSON 对象：月度收益率矩阵 `{"2024-01": 0.021, "2024-02": 0.035, ...}` | report.html 「月度收益率热力图」色块渲染 |
| `created_at` | TEXT | NOT NULL | 创建时间 | 隐含 |

> **关键约束：**
> - `PRIMARY KEY(backtest_id)` — 1:1 关系，不新增独立主键
> - `FOREIGN KEY(backtest_id) REFERENCES quant_backtest(id) ON DELETE CASCADE` — 删除主记录时级联删除
> - `CHECK(json_valid(equity_curve_json))` — （SQLite 3.38+ 支持，如不支持则在应用层校验）

> **设计说明**：`trades_json` 完整存储所有交易。当 report.html 的交易流水表格需要分页时，接口层从该 JSON 中按需解析并截取分页（或在 Python 端/Java 端直接返回分页结构）。典型交易数在 100~1000 条，直接 JSON 存储不构成性能问题。

---

### 3.3 quant_backtest_grid_result — 参数优化结果

> 字段来源：
> - `sdlc/prd/004-回测中心/prototype/backtest-grid.html` 的参数组合表格 + 散点图 + 热力图
> - PRD §10.2 网格搜索响应结构

> 字段映射原则：grid.html 中每组参数的展示列 → 表字段

| 字段名 | 类型 | 约束 | 说明 | 原型图中的来源 |
|--------|------|------|------|---------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | 主键 | — |
| `backtest_id` | INTEGER | NOT NULL | 关联 `quant_backtest.id` | grid.html 页面通过 backtestId 查询 |
| `combination_rank` | INTEGER | NOT NULL | 排名（按 optimization_metric 值从高到低），1=最优 | grid.html 表格第一列「排名」 |
| `param_values_json` | TEXT | NOT NULL | JSON 对象：该组参数的具体值 `{"buy_rules.conditions[0].left.params.period": 5, "sell_rules.stop_loss.percent": 0.08, ...}` | grid.html 中每行的参数值列（多个参数一组） |
| `metric_value` | REAL | NOT NULL | 优化目标指标的值（如 sharpe_ratio=1.45），用于排序 | grid.html 中高亮的指标列 + 散点图 Y 轴 |
| `total_return_pct` | REAL | — | 总收益率 | grid.html 表格列 |
| `sharpe_ratio` | REAL | — | 夏普比率 | grid.html 表格列 |
| `max_drawdown_pct` | REAL | — | 最大回撤 | grid.html 表格列 |
| `win_rate` | REAL | — | 胜率 | grid.html 表格列 |
| `trades_count` | INTEGER | — | 交易次数 | grid.html 表格列 |
| `is_best` | INTEGER | NOT NULL DEFAULT 0 | 是否为最优组合（1=是，0=否），冗余字段便于快速查询最优参数 | grid.html 高亮的「最优参数」行 |
| `created_at` | TEXT | NOT NULL | 创建时间 | 隐含 |

> **关键约束：**
> - `FOREIGN KEY(backtest_id) REFERENCES quant_backtest(id) ON DELETE CASCADE`
> - `UNIQUE(backtest_id, combination_rank)` — 同一任务内排名唯一
> - **索引**：
>   - `CREATE INDEX idx_grid_bt ON quant_backtest_grid_result(backtest_id, combination_rank)` — 按 backtest_id 查询，自动按排名排序（grid.html 默认展示排序结果）
>   - `CREATE INDEX idx_grid_metric ON quant_backtest_grid_result(backtest_id, metric_value DESC)` — 支持按指标值排序/散点图查询

---

### 3.4 quant_backtest_wf_result — Walk-forward 分段结果

> 字段来源：
> - `sdlc/prd/004-回测中心/prototype/backtest-walk-forward.html` 的分段时间轴 + 每段最优参数对比 + 组合验证期绩效
> - PRD §10.3 Walk-forward 响应结构

> 字段映射原则：walk-forward.html 中每段的「训练期/验证期」块 → 一条表记录

| 字段名 | 类型 | 约束 | 说明 | 原型图中的来源 |
|--------|------|------|------|---------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | 主键 | — |
| `backtest_id` | INTEGER | NOT NULL | 关联 `quant_backtest.id` | walk-forward.html 页面通过 backtestId 查询 |
| `segment_index` | INTEGER | NOT NULL | 分段序号（从 0 开始递增） | walk-forward.html 时间轴的第 N 段 |
| `in_sample_start` | TEXT | NOT NULL | 训练期开始日期 | walk-forward.html 中每段的「[训练期] 起始日期 ~ 结束日期」块 |
| `in_sample_end` | TEXT | NOT NULL | 训练期结束日期 | 同上 |
| `out_sample_start` | TEXT | NOT NULL | 验证期开始日期 | walk-forward.html 中每段的「[验证期] 起始日期 ~ 结束日期」块 |
| `out_sample_end` | TEXT | NOT NULL | 验证期结束日期 | 同上 |
| `best_params_json` | TEXT | NOT NULL | JSON 对象：该段训练期选出的最优参数值（与 grid_result 的 param_values_json 结构相同） | walk-forward.html 「每段最优参数」表，用于评估参数稳定性 |
| `in_sample_metrics_json` | TEXT | NOT NULL | JSON 对象：训练期的指标 `{totalReturnPct, sharpeRatio, maxDrawdownPct, winRate, ...}` | walk-forward.html 每段的「训练期表现」 |
| `out_sample_metrics_json` | TEXT | NOT NULL | JSON 对象：验证期的指标（与训练期同结构） | walk-forward.html 每段的「验证期表现」（核心关注点） |
| `created_at` | TEXT | NOT NULL | 创建时间 | 隐含 |

> **关键约束：**
> - `FOREIGN KEY(backtest_id) REFERENCES quant_backtest(id) ON DELETE CASCADE`
> - `UNIQUE(backtest_id, segment_index)` — 同一任务内分段序号唯一
> - **索引**：
>   - `CREATE INDEX idx_wf_bt ON quant_backtest_wf_result(backtest_id, segment_index)` — 按 backtest_id 查询并按段序号排序（时间轴顺序展示）

---

## 4. DO 类映射（新增表对应的 Java DO 类设计）

### 4.1 quant_backtest → BacktestDO

```java
package com.arthur.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 回测任务主记录
 * 对应原型图：
 *  - backtest-config.html 表单提交后写入
 *  - backtest-list.html 列表数据来源
 */
@Data
@TableName("quant_backtest")
public class BacktestDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;              // 关联 sys_user.id（用户隔离）
    private Long strategyId;          // 关联 quant_strategy.id（config.html 策略选择器）
    private Integer strategyVersion;  // 策略版本号
    private String taskId;            // Python 计算任务 UUID
    private String mode;              // SINGLE / GRID_SEARCH / WALK_FORWARD（config.html 模式 Tab）

    private String startDate;         // 回测开始日期（config.html 日期 input）
    private String endDate;           // 回测结束日期
    private String universeType;      // INDEX_300 / INDEX_500 / CUSTOM
    private String universeCodes;     // JSON 数组，CUSTOM 模式的股票代码
    private Integer universeCount;    // 实际股票数量（冗余，用于列表页快速展示）

    private Double initialCash;       // 初始资金（config.html「初始资金」输入框）
    private Double commissionPct;     // 佣金率
    private Double slippagePct;       // 滑点
    private Integer rebalanceFrequencyDays;  // 调仓频率（交易日）
    private Integer maxPositions;     // 最大持仓数
    private Double maxSinglePositionPct;  // 单票最大仓位比例

    private Integer benchmarkEnabled; // 是否启用基准对比（1=是，0=否）

    private String paramGridJson;     // 网格搜索/WF 的参数范围定义（JSON）
    private String optimizationMetric; // 优化目标指标（GRID/WF 模式）
    private String walkForwardJson;   // WF 专属配置（训练窗口/验证窗口/步长，JSON）

    private String status;            // RUNNING / SUCCESS / FAILED（list.html 状态标签）
    private String errorMessage;      // 失败原因

    // 核心指标（列表页直接展示，避免 JOIN 或解析大 JSON）
    private Double totalReturnPct;
    private Double sharpeRatio;
    private Double maxDrawdownPct;
    private Double winRate;
    private Integer tradesCount;

    private Integer computeDurationMs;  // Python 计算耗时（ms）

    private String createdAt;
    private String updatedAt;
    private String completedAt;
}
```

### 4.2 quant_backtest_report → BacktestReportDO

```java
package com.arthur.stock.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 单次回测完整报告数据（1:1 关联 quant_backtest）
 * 对应原型图：backtest-report.html 的所有图表和表格区块
 */
@Data
@TableName("quant_backtest_report")
public class BacktestReportDO {

    private Long backtestId;          // 主键，关联 quant_backtest.id

    private String equityCurveJson;   // 策略净值曲线 → report.html 净值曲线双折线图
    private String benchmarkCurveJson; // 基准净值曲线 → 双折线图的基准线
    private String drawdownCurveJson; // 回撤曲线 → report.html 回撤曲线图
    private String tradesJson;        // 交易流水 → report.html 交易流水表格
    private String positionsJson;     // 持仓快照 → 持仓变化图
    private String metricsJson;       // 全量指标 → report.html 详细指标表
    private String monthlyReturnsJson; // 月度收益率矩阵 → report.html 月度热力图

    private String createdAt;
}
```

### 4.3 quant_backtest_grid_result → BacktestGridResultDO

```java
package com.arthur.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 参数优化每一组参数的结果
 * 对应原型图：backtest-grid.html 的参数组合表格 + 散点图 + 热力图
 */
@Data
@TableName("quant_backtest_grid_result")
public class BacktestGridResultDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long backtestId;
    private Integer combinationRank; // 排名（1=最优）
    private String paramValuesJson;  // 参数值 JSON → grid.html 表格参数列
    private Double metricValue;      // 目标指标值 → grid.html 高亮列 + 散点图 Y轴

    private Double totalReturnPct;
    private Double sharpeRatio;
    private Double maxDrawdownPct;
    private Double winRate;
    private Integer tradesCount;

    private Integer isBest;          // 1=最优组合（grid.html 高亮行）

    private String createdAt;
}
```

### 4.4 quant_backtest_wf_result → BacktestWfResultDO

```java
package com.arthur.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Walk-forward 每段的训练期 + 验证期结果
 * 对应原型图：backtest-walk-forward.html 的分段时间轴 + 参数稳定性表
 */
@Data
@TableName("quant_backtest_wf_result")
public class BacktestWfResultDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long backtestId;
    private Integer segmentIndex;      // 分段序号（0 开始）

    private String inSampleStart;      // 训练期起始 → walk-forward.html 时间轴
    private String inSampleEnd;        // 训练期结束
    private String outSampleStart;     // 验证期起始
    private String outSampleEnd;       // 验证期结束

    private String bestParamsJson;     // 该段最优参数 → 参数稳定性对比表
    private String inSampleMetricsJson; // 训练期指标
    private String outSampleMetricsJson; // 验证期指标（核心）

    private String createdAt;
}
```

---

## 5. ER 关系

```
sys_user (1) ────< (N) quant_backtest (1) ─┬─── (1) quant_backtest_report
                                            │
                                            ├───< (N) quant_backtest_grid_result
                                            │
                                            └───< (N) quant_backtest_wf_result
                                             
quant_strategy (1) ────< (N) quant_backtest
         │
         └─ 由策略管理模块维护，本模块只读策略 JSON 和有限回写参数

quant_backtest 字段解释：
  ├─ id:                    主键
  ├─ user_id:               关联 sys_user.id（用户隔离，每个用户只能看自己的回测）
  ├─ strategy_id:           关联 quant_strategy.id（策略来源）
  ├─ mode:                  决定哪张子表有数据：
  │                          · SINGLE → quant_backtest_report
  │                          · GRID_SEARCH → quant_backtest_grid_result
  │                          · WALK_FORWARD → quant_backtest_wf_result
  └─ status:                RUNNING/SUCCESS/FAILED（子表数据仅在 SUCCESS 时写入）
```

---

## 6. 索引建议

| 索引语句 | 使用场景 |
|---------|---------|
| `CREATE INDEX IF NOT EXISTS idx_bt_user_mode_status ON quant_backtest(user_id, mode, status);` | backtest-list.html 按用户+模式+状态筛选，如「查看我的所有单次回测」 |
| `CREATE INDEX IF NOT EXISTS idx_bt_strategy ON quant_backtest(strategy_id, strategy_version);` | 策略维度回溯：查询某策略的所有历史回测表现 |
| `CREATE INDEX IF NOT EXISTS idx_bt_created ON quant_backtest(created_at DESC);` | 列表页按创建时间倒序排列（最新在前） |
| `CREATE INDEX IF NOT EXISTS idx_bt_task_id ON quant_backtest(task_id);` | Python 计算完成后通过 task_id 查找并更新状态 |
| `CREATE INDEX IF NOT EXISTS idx_grid_bt_rank ON quant_backtest_grid_result(backtest_id, combination_rank);` | backtest-grid.html 查询某任务的全部参数组合，按排名展示 |
| `CREATE INDEX IF NOT EXISTS idx_grid_bt_metric ON quant_backtest_grid_result(backtest_id, metric_value DESC);` | 查询最优组合 / 散点图按指标值排序 |
| `CREATE INDEX IF NOT EXISTS idx_wf_bt_segment ON quant_backtest_wf_result(backtest_id, segment_index);` | walk-forward.html 查询某任务的全部分段结果，按时间轴顺序展示 |

> 注：`quant_backtest_report` 由于是 1:1 关系且通过 backtest_id 直接查询，PRIMARY KEY(backtest_id) 已足够，不需额外索引。

---

## 7. 数据迁移策略

| 场景 | 方案 |
|------|------|
| **首次部署（全新环境）** | 在 `schema.sql` 末尾追加本模块 4 张表的 CREATE TABLE IF NOT EXISTS 语句，应用启动时自动创建。SQLite 的 `IF NOT EXISTS` 保证幂等性 |
| **现有环境升级（已有用户数据）** | 同上，CREATE TABLE IF NOT EXISTS 是幂等的。由于所有新表均为独立表，不影响现有表结构，无需数据回填 |
| **表结构变更（如后续版本增加字段）** | SQLite 不支持 DROP COLUMN / ALTER COLUMN 的复杂变更，简化方案：<br>1. 新增可空字段用 `ALTER TABLE table ADD COLUMN column type`；<br>2. 如需删除字段或修改类型，需走「CREATE 新表 → INSERT 旧表数据 → DROP 旧表 → RENAME 新表」流程 |
| **历史回测数据清理** | 用户可通过前端界面手动删除（DELETE /api/backtest/{id}）；不需要定时任务自动清理，保留历史数据有利于策略迭代复盘 |

---

## 8. 与现有表的兼容性

| 检查项 | 结论 | 说明 |
|--------|------|------|
| 与 `daily_quote` 主键冲突？ | ✅ 无冲突 | `daily_quote` 主键为 `(ts_code, trade_date)`，本模块仅只读查询，不写入 |
| 与 `stock_basic` 主键冲突？ | ✅ 无冲突 | 仅读取股票名称做前端展示映射 |
| 与 `sys_user` 关联有效？ | ✅ 已设计 | `quant_backtest.user_id` → `sys_user.id`，用于用户隔离 |
| 新表名是否与现有表前缀风格一致？ | ✅ 一致 | 现有表：`sys_user`, `sys_watchlist`, `daily_quote`, `stock_basic`, `trade_cal`, `adj_factor`, `dividend`；新表使用 `quant_` 前缀，与量化相关表区分系统表 |
| 字段类型与现有表一致？ | ✅ 一致 | 统一使用 INTEGER（ID/计数）、TEXT（日期/JSON/字符串/枚举）、REAL（金额/比例/小数） |
| 是否影响现有查询性能？ | ✅ 不影响 | 本模块所有表为独立表，不修改现有表结构；查询仅通过索引字段过滤 |

---

## 9. 验收相关

- [ ] 建表 SQL 执行无报错（SQLite 语法正确，`CREATE TABLE IF NOT EXISTS` 幂等）
- [ ] `quant_backtest` → `quant_backtest_report` 的 1:1 关联正确，通过 `backtest_id` 可 JOIN 查询
- [ ] `quant_backtest` → `quant_backtest_grid_result` 的 1:N 关联正确，级联删除生效
- [ ] `quant_backtest` → `quant_backtest_wf_result` 的 1:N 关联正确，`segment_index` 顺序与时间轴一致
- [ ] 主键 / UNIQUE 约束生效，同一 backtest_id 不允许重复的 `combination_rank` 或 `segment_index`
- [ ] 表字段与原型图中的表单字段一一对应（如 backtest-config.html 的「初始资金」→ `initial_cash`，「调仓频率(交易日)」→ `rebalance_frequency_days`）
- [ ] 索引生效：典型查询 SQL 的 `EXPLAIN QUERY PLAN` 确认使用了对应索引
- [ ] 用户隔离生效：不同 user_id 的回测记录不可交叉查询（在 Service 层强制加入 `user_id = UserContext.getUserId()` 条件）
