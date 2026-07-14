# 005 回测中心 - 产品需求文档（PRD）

> **版本**：v2.0
> **日期**：2026-07-13
> **状态**：设计稿（待编码）
> **本版变更**：基于 v1.0 的需求分析报告重构——聚焦第一波（SINGLE + signals 信号驱动 + exit.bracket），GRID/WF/rebalance 列为第二波预告；修复 eval 注入、benchmark 缺失、四范式复杂度失控、PRD/spec 双源、Open Questions 状态混乱等问题。
> **权威来源**：本 PRD 的字段定义以 [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md)（v1.0）为最高权威；akquant API 映射以 [.trae/rules/akquant/](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/)（0.2.47）为��。
> **文档定位**：本 PRD 是 **产品需求真相**（Why + What），不堆开发实现细节（API 路由、字段表、akquant 映射属开发 spec，由后续 `.trae/specs/` 三件套承载）。

---

## 目录

1. [Overview](#1-overview)
2. [Goals](#2-goals)
3. [Non-Goals](#3-non-goals)
4. [Background & Context](#4-background--context)
5. [Functional Requirements（第一波）](#5-functional-requirements第一波)
6. [第二波预告（不在本版范围）](#6-第二波预告不在本版范围)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Constraints](#8-constraints)
9. [Assumptions](#9-assumptions)
10. [Acceptance Criteria](#10-acceptance-criteria)
11. [Open Questions](#11-open-questions)
12. [附录：分波规划与决策记录](#12-附录分波规划与决策记录)

---

## 1. Overview

### 1.1 Summary

在 StockPulse 双系统架构（watcher · Java + engine · Python）下落地「回测中心」（业务编号 005），打通「策略 → 回测 → 报告 → 对比」决策链的最后一公里。

回测中心消费 004 策略管理已校验的 `config_json`（按 `strategy_id + version_no` 读取），由 watcher 编排行情/基准/候选池数据并 HTTP 调 engine，engine 基于 akquant 0.2.47 执行回测，结果全量持久化供前端报告与横向对比。

### 1.2 Purpose

让用户对**已保存的策略版本**发起回测，回答两个核心问题：

| 问题 | 由谁回答 |
|---|---|
| **「这个策略在历史上表现如何？」** | 单次回测（SINGLE） + 完整绩效报告 |
| **「它跑赢沪深300了吗？」** | 强制默认基准对比（沪深300/中证500/上证50等多选） |

产出完整绩效报告（净值曲线、**基准叠加**、回撤、月度热力、交易明细、核心指标），并支持多次回测横向对比，量化评估策略优劣，为策略激活（信号中心）提供决策依据。

### 1.3 Target Users

- **量化研究用户**（个人 A 股投资者，通过前端页面操作完整研究流程）
- **004 策略管理**（联查回测摘要，回填 `StrategyVersionCompareDTO` 的指标对比，替换当前 [StrategyServiceImpl.java:392-394](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java#L392-L394) 的 TODO 占位）
- **信号中心**（未来读取经回测验证的策略激活生成信号）

### 1.4 第一波 vs 第二波的范围划分（核心决策）

> ⚠️ **本 PRD 第一波聚焦「单次回测 + 信号驱动 + 静态止损止盈」**，把高复杂度/低频场景后置到第二波。理由见 [分析报告 §二 🔴-2、§六 6.1](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md)。

| 能力 | 第一波（本版 PRD） | 第二波（预告，本版不展开 FR） |
|---|---|---|
| 回测模式 | ✅ SINGLE 单次回测 | 🕐 GRID 网格寻优 / WALK_FORWARD 滚动验证 |
| 策略范式 | ✅ signals 信号驱动（单标的/固定池） | 🕐 rebalance 多因子调仓驱动 |
| 出场规则 | ✅ exit.bracket 静态止损止盈（OCO 括号单） | 🕐 exit.rules 动态出场条件树（支持 ref） |
| benchmark | ✅ 强制默认沪深300，支持下拉多选 | ✅（延续） |
| 横向对比 | ✅ 多次回测净值叠加 + 指标对比表 | ✅（延续） |
| 报告导出 | ✅ CSV（交易明细） | 🕐 HTML 报告（落盘策略待定） |
| 任务取消 | ✅ 仅 PENDING 可取消（RUNNING 隐藏取消按钮，避免假期望） | 🕐 RUNNING 真终止（需 engine 协作） |
| 参数寻优约束表达式 | — | 🕐 结构化 DSL（`{left, op, right}`，禁 eval） |

---

## 2. Goals

- **G1**：在 watcher（Java）侧实现**单次回测**任务管理——异步提交、状态轮询、结果查询、横向对比、删除
- **G2**：新建数据库表 `quant_backtest`（主表/任务）+ `quant_backtest_report`（SINGLE 报告全量 JSON）
- **G3**：在 engine（Python）侧实现回测执行引擎：JSON config → akquant `Strategy` 子类动态编译（**第一波仅支持 signals 信号驱动 + exit.bracket**）、`aq.run_backtest` 调用、`BacktestResult` 安全序列化
- **G4**：实现 **benchmark 强制对比**——watcher 默认拼装沪深300行情与策略行情一并传 engine，报告页净值曲线必须叠加基准
- **G5**：在 watcher 侧实现行情/基准/候选池数据编排：按 `trading_config.symbols` + `backtest_config.date range` 从 SQLite 拼装 kline_data + benchmark，HTTP 传 engine
- **G6**：实现 watcher → engine 的 `BacktestClient`（继承 `AbstractEngineClient`）
- **G7**：实现前端回测中心页面——**列表、配置、报告、对比（共 4 页，第一波）**；优化/WF/优化结果页进第二波
- **G8**：回填 004 的 `StrategyVersionCompareDTO` 真实回测指标（替换 [StrategyServiceImpl.java:392-394](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java#L392-L394) 的 TODO 占位）
- **G9**：严格遵守 engine 不触库硬约束；严格对齐 akquant 0.2.47 API；强制 A 股回测规则（T+1、lot_size=100、印花税方向、滑点 dict）

---

## 3. Non-Goals

### 3.1 本模块永远不做（边界）

- **策略配置编辑**（归 004 策略管理）：本模块只消费已校验的 `config_json`，不提供编辑入口
- **选股逻辑实现**（归 003 多因子选股中心）：调仓日选股（第二波）复用 engine 的 `ConditionEngine` / `factor_calculator`，不另写
- **因子效能分析**（IC/IR/分层回测，归 002 因子库扩展）
- **信号推送 / 模拟交易 / 风控执行 / 策略实盘跟踪**（归域 C/D，本模块只做历史回测，不触实盘）
- **期货 / 期权 / 实盘交易网关（CTP/miniqmt/ptrade）/ 机器学习 / 流式回测 / Warm Start 快照恢复**（akquant 超纲能力，本项目明确不用，范围 = A 股股票日线回测）
- **临时未保存配置直接回测**（仅支持已保存的 VERIFIED/ACTIVE 策略版本，防注入且可追溯；DRAFT 版本回测见 [§11 OQ-4](#11-open-questions)）
- **engine 侧写 sqlite3**（硬约束：akquant 内部 `run_grid_search(db_path=...)` 默认不传 `db_path`）

### 3.2 第二波才做（第一波明确不做）

- **GRID 网格参数寻优**（含结构化 constraint/resultFilter DSL）
- **WALK_FORWARD 滚动验证**
- **rebalance 多因子调仓驱动**（含 engine 本地选股复用 003 内核）
- **exit.rules 动态出场条件树**（支持 ref 状态引用）
- **HTML 报告导出**（落盘策略与 engine 硬约束的边界待定）
- **RUNNING 任务真终止**（需 engine 协作，第一波隐藏取消按钮）

---

## 4. Background & Context

### 4.1 项目架构与硬约束

StockPulse 采用 Java + Python 双系统：

- **stock-watcher（Java）**：业务 + 数据中台，独占 SQLite 读写，负责回测任务持久化、行情/基准数据编排、前端页面、HTTP 调用编排
- **stock-engine（Python）**：计算服务，负责 JSON config 动态编译、akquant 回测执行、结果序列化

**三条铁律**（CLAUDE.md）：
1. **engine 禁止 `sqlite3`/`sqlalchemy`/直连 `.db`**——数据单源性在 watcher
2. **交互单向 watcher → engine**——engine 不回调 watcher
3. **前端不直接调 engine**——所有 engine 调用经 watcher 编排

### 4.2 已就绪的上游基建（直接复用，不重复造轮子）

| 上游模块 | 已实现内容 | 本模块如何复用 |
|---|---|---|
| **004 策略管理** | `StrategyConfigModel` / `BacktestConfigModel`（[services/strategy/models.py:347](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/models.py)）1:1 映射 `aq.run_backtest`；`POST /python/v1/strategies/validate`；策略 CRUD + 版本快照 + 5 模板 | 按 `strategy_id + version_no` 读 `quant_strategy_version.config_json`；直接 `from services.strategy.models import StrategyConfigModel` |
| **003 选股中心** | `ConditionEngine`（截面，[services/screener/engine.py](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/engine.py)）；`factor_calculator` / `factor_registry`（54 因子）；前端条件编辑器组件 | 第一波不直接用（signals 走 TradingConditionEngine 时序版）；第二波 rebalance 复用 |
| **002 因子库** | 技术因子注册表（走 `akquant.talib`） | `on_bar` 内 `get_history` + `talib.*` 实时算，口径与选股一致 |
| **watcher 基建** | `AbstractEngineClient` / `ApiResponse<T>` / `GlobalExceptionHandler` / `AuthInterceptor` / Caffeine / 分页 `PageResult` | `BacktestClient` 继承 `AbstractEngineClient`；Controller 风格对齐 `ScreenerController` |

> ⚠️ **第一波需新增 `TradingConditionEngine`**：003 的 `ConditionEngine` 是截面引擎（禁止 `cross_up`/`cross_down`/`ref`）；回测是时序操作，需新增 `TradingConditionEngine`（支持时序穿越判断 + ref 持仓状态引用）。详见统一 Schema §4.4 / §4.6。

### 4.3 与相邻模块的边界

| 模块 | 提供给本模块 | 本模块提供给它 |
|---|---|---|
| 004 策略管理 | 已校验的 `config_json`（按 `strategy_id + version_no` 读取） | 回测摘要回填策略卡片 / 版本对比（替换 TODO） |
| 003 选股中心 | 第二波 rebalance 复用 `ConditionEngine`、前端条件编辑器组件 | 无（只读依赖） |
| 002 因子库 | 因子注册表、`factor_calculator` | 无 |
| 信号中心（未来） | 无 | 经回测验证的策略可激活 |

### 4.4 截面 vs 时序的关键区分（第一波仅触及「时序」一侧）

| 维度 | 选股（003） | 回测（005） |
|---|---|---|
| 本质 | 截面操作（某日全市场快照） | 时序操作（时间序列上逐 bar 判断 + 交易） |
| 条件引擎 | `ConditionEngine`（截面） | `TradingConditionEngine`（时序，新增） |
| 允许的 comparator | `>` `<` `>=` `<=` `==` `!=` | 上述 + `cross_up` `cross_down` |
| 允许的节点 | `{value}` `{factor}` `{op}` | 上述 + `{ref}`（entry_price/position_pnl_pct/position_qty/bars_held） |
| 输出 | 股票列表 + 因子值 + 排名 | 权益曲线 + 基准叠加 + 交易记录 + 绩效指标 |

### 4.5 相关设计文档

- [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) — `backtest_config` / `trading_config.signals` / `exit.bracket` 字段唯一权威
- [选股与回测边界设计](file:///d:/lcProject/stock-pulse/sdlc/prd/003-多因子选股中心/选股与回测边界设计.md) — 职责边界、三层分区
- [004 策略管理 spec](file:///d:/lcProject/stock-pulse/.trae/specs/004-strategy-management/spec.md) — 已实现的策略管理 PRD
- [akquant 知识库](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/) 全套 9 篇 — 回测实现的 API 字典
- [005 回测中心 PRD 与设计文档分析报告](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md) — 本版重构的依据

---

## 5. Functional Requirements（第一波）

### FR-1: 单次回测（核心链路）

#### FR-1a: watcher 侧提交与编排

- **API**：`POST /api/backtest/run`（异步）
- **请求体**：
  ```json
  {
    "strategyId": "str_xxx",
    "versionNo": 1,
    "mode": "SINGLE",
    "overrideConfig": {
      "initial_cash": 200000,
      "start_date": "2023-01-01",
      "end_date": "2024-01-01",
      "benchmark": "000300.SH"
    }
  }
  ```
  - `strategyId` + `versionNo`：必填，指向已 VERIFIED/ACTIVE 策略版本（DRAFT/ARCHIVED 拒绝，返回 `BACKTEST_STRATEGY_VERSION_INVALID`）
  - `mode`：第一波仅 `SINGLE`（GRID/WALK_FORWARD 在请求层返回 `BACKTEST_MODE_NOT_SUPPORTED`，引导用户等第二波）
  - `overrideConfig`：可选，覆盖 `backtest_config` 的部分字段（如临时改资金/日期/benchmark）
  - `benchmark`：可选，覆盖默认基准（沪深300）。基准候选项见 [FR-4](#fr-4-benchmark-基准对比默认与多选)
- **响应**：`{ taskId: "bt_xxx", status: "PENDING" }`（立即返回）

- **watcher 编排**（`BacktestService.run`，`@Async`）：
  1. 按 `strategyId + versionNo` 读 `quant_strategy_version.config_json` + 校验主表 `status ∈ {VERIFIED, ACTIVE}`（DRAFT/ARCHIVED 拒绝）
  2. **范式校验**：第一波仅支持 `trading_config.signals` 在场的信号驱动范式 + `exit.bracket` 静态止损止盈。若 config 含 `trading_config.rebalance` 或 `exit.rules`，返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（引导用户等第二波）
  3. 合并 `overrideConfig` 到 `backtest_config`（含 benchmark 覆盖）
  4. 解析 `trading_config.symbols`（单标的=str，固定池=list）→ 从 SQLite 拼装每只股票的 `kline_data`（`list[dict]`，字段 `date/open/high/low/close/volume`，前复权已由上游完成）
  5. **拼装 benchmark 行情**：按 `backtest_config.benchmark`（默认沪深300）从 SQLite 读基准 OHLCV，独立字段 `benchmark_data` 传入 engine
  6. 若 config 引用基本面因子（如 `PE_TTM`，第一波 signals 范式一般不涉及，但留通道），一并拼装入 `kline_data` 的 `extra` 字段
  7. 调 `BacktestClient.runSingle(config, klineMap, benchmarkData)` → engine
  8. engine 返回序列化结果 → watcher 事务内落 `quant_backtest`（主表，status=SUCCESS）+ `quant_backtest_report`（全量 JSON，含基准叠加曲线）
  9. 异常时落 status=FAILED + `error_message`（截断 1024 字符，全量栈留日志）

#### FR-1b: engine 侧执行

- **API**：`POST /python/v1/backtest/run`
- **请求**：`BacktestRequest { strategy_config: dict, kline_data: dict[str, list[dict]], benchmark_data?: list[dict] }`
- **编排**（`services/backtest/runner.py` 的 `run_backtest_engine`）：
  1. `StrategyConfigModel(**strategy_config)` 解析（复用 004 模型，Pydantic 校验）
  2. **范式校验**：第一波仅接受 `signals` + `exit.bracket`；含 `rebalance`/`exit.rules` 返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`
  3. `data_adapter.kline_to_df_map(kline_data)` → `Dict[str, pd.DataFrame]`（用 [02-data-input.md §6](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/02-data-input.md) 配方）
  4. `compiler.compile(config)` → akquant `Strategy` 子类（第一波仅 signals + bracket）
  5. `build_backtest_kwargs(config.backtest_config)` → `run_backtest` 参数 dict（强制 `t_plus_one=True`、`lot_size=100`、`broker_profile` 默认 `cn_stock_miniqmt`、`slippage` 用 dict）
  6. `aq.run_backtest(data=data_map, strategy=strat_cls, **kwargs)`
  7. `result_serializer.serialize(result, benchmark_data)` → JSON（含基准归一化净值用于叠加）
- **响应**：`BacktestResponse { metrics, equity_curve, benchmark_curve, daily_returns, trades, orders, positions }`

#### FR-1c: 任务状态轮询与报告查询

- `GET /api/backtest/tasks/{taskId}` — 轮询任务状态（`PENDING`/`RUNNING`/`SUCCESS`/`FAILED` + `progress` 0-100 + `error_message`）
- `GET /api/backtest/{backtestId}/report` — 获取完整报告（`metrics` + `equity_curve` + `benchmark_curve` + `trades` + `positions`）

---

### FR-2: 任务状态机与生命周期

| 状态 | 跃迁 | 触发 | 用户可见行为 |
|---|---|---|---|
| `PENDING` → `RUNNING` | watcher 开始调 engine | 后端 | 卡片显示「运行中」+ 进度条 |
| `RUNNING` → `SUCCESS` | engine 返回结果 | 后端 | 卡片显示指标摘要，「查看报告」可点 |
| `RUNNING` → `FAILED` | engine 异常/超时 | 后端 | 卡片显示「失败」+ 错误摘要，「重跑」可点 |
| `PENDING` → `CANCELLED` | 用户点取消（仅 PENDING 可取消） | 用户 | 卡片显示「已取消」 |
| `RUNNING` → `FAILED`（重启） | watcher 重启时 RUNNING 重置为 FAILED，标「引擎中断」 | 系统 | 卡片显示「引擎中断，请重跑」 |

- **第一波取消策略**：前端对 RUNNING 任务 **隐藏取消按钮**（避免给用户「点了能停」的假期望），仅 PENDING 可取消。RUNNING 任务需等完成或超时（read timeout 300s）。详见 [§11 OQ-2](#11-open-questions)。
- **失败重跑**：FAILED/CANCELLED 任务提供「一键重跑」按钮（复用原 config 重新提交新任务），不做断点续跑。详见 [§11 OQ-1](#11-open-questions)。

---

### FR-3: 回测任务数据模型（watcher/Java 侧）

新建数据库表（同步更新 `schema-sqlite.sql` / `schema-mysql.sql`，用 `CREATE TABLE IF NOT EXISTS` 兼容增量迁移）：

#### 表 1：`quant_backtest`（主表/任务表）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT | 自增主键 |
| `task_id` | TEXT | NOT NULL UNIQUE | 业务 ID（UUID 或时间戳） |
| `strategy_id` | TEXT | NOT NULL | FK 业务 ID（对齐 004 的 strategy_id） |
| `version_no` | INTEGER | NOT NULL | 策略版本号 |
| `mode` | TEXT | NOT NULL DEFAULT 'SINGLE' | 第一波仅 `SINGLE`（GRID/WALK_FORWARD 预留） |
| `status` | TEXT | NOT NULL DEFAULT 'PENDING' | `PENDING`/`RUNNING`/`SUCCESS`/`FAILED`/`CANCELLED` |
| `progress` | INTEGER | DEFAULT 0 | 0-100 |
| `error_message` | TEXT | | 失败原因（截断 1024 字符；全量栈留日志） |
| `override_config` | TEXT | | 覆盖 `backtest_config` 的 JSON（含 benchmark 覆盖） |
| `benchmark` | TEXT | NOT NULL DEFAULT '000300.SH' | 本次回测使用的基准代码 |
| `created_by` | TEXT | | 用户名（从 Session 取，非「预留」） |
| `started_at` | TEXT | | UTC ISO8601 |
| `finished_at` | TEXT | | UTC ISO8601 |
| `created_at` | TEXT | | UTC ISO8601 |

索引：`INDEX(strategy_id, version_no)`、`INDEX(status)`、`INDEX(mode)`

#### 表 2：`quant_backtest_report`（SINGLE 模式报告全量表）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT | |
| `backtest_id` | INTEGER | NOT NULL | FK → `quant_backtest.id` |
| `metrics_json` | TEXT | NOT NULL | 核心指标 JSON（total_return_pct/sharpe/max_drawdown_pct/win_rate/...） |
| `equity_curve_json` | TEXT | | 策略净值曲线 JSON（`{dates: [...], values: [...]}`） |
| `benchmark_curve_json` | TEXT | | **基准归一化净值曲线 JSON**（`{dates: [...], values: [...]}`，归一化到初始净值 1.0） |
| `daily_returns_json` | TEXT | | 日收益率数组 |
| `trades_json` | TEXT | | 交易明细 JSON 数组（每笔含 entry/exit/pnl/return_pct） |
| `orders_json` | TEXT | | 订单明细 |
| `positions_json` | TEXT | | 持仓快照 |
| `created_at` | TEXT | | UTC ISO8601 |

约束：`UNIQUE(backtest_id)`

> 第二波新增 `quant_backtest_optimize`（GRID/WF 结果表），第一波不建。

- **DO + Mapper**：`QuantBacktestDO/Mapper`、`QuantBacktestReportDO/Mapper`
- **枚举**（实现 `DisplayableEnum`）：`BacktestModeEnum`（第一波仅 SINGLE；GRID/WALK_FORWARD 预留）、`BacktestStatusEnum`（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）

---

### FR-4: benchmark（基准对比，默认与多选）

> ⚠️ **第一波强制有基准**。A 股回测不对比沪深300，夏普/超额收益无决策价值（见 [分析报告 §三 🟠-1](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md)）。

- **默认基准**：`000300.SH`（沪深300）
- **候选项**（前端下拉，watcher 从 `stock_basic` 读 `is_index=1` 的指数，或维护白名单）：
  - `000300.SH` 沪深300
  - `000905.SH` 中证500
  - `000016.SH` 上证50
  - `000852.SH` 中证1000
  - 用户也可手填其他指数代码（watcher 校验存在性）
- **数据拼装**：watcher 按 `backtest_config.date range` 从 SQLite 读基准 OHLCV，独立字段 `benchmark_data` 传 engine
- **归一化**：engine 把基准收盘价归一化到初始净值 1.0（`price / price[0]`），与策略净值曲线叠加对比
- **覆盖**：用户可在 `overrideConfig.benchmark` 覆盖默认值；config_json 的 `backtest_config.benchmark`（新增字段，见 [§11 OQ-5](#11-open-questions)）优先级高于默认

---

### FR-5: 回测任务管理 API（watcher/Java 侧，第一波）

| API | 方法 | 说明 |
|---|---|---|
| `/api/backtest/run` | POST | 提交任务（第一波 mode 仅 SINGLE） |
| `/api/backtest/tasks` | GET | 任务列表（分页，支持 `strategyId`/`status`/日期范围筛选；第一波 mode 筛选可选） |
| `/api/backtest/tasks/{taskId}` | GET | 任务状态轮询 |
| `/api/backtest/tasks/{taskId}/cancel` | POST | 取消（**第一波仅 PENDING 可靠取消**；RUNNING 返回 409 + 引导等完成） |
| `/api/backtest/tasks/{taskId}/rerun` | POST | **一键重跑**（复用原 config 提交新任务） |
| `/api/backtest/{backtestId}` | GET | 回测主记录 |
| `/api/backtest/{backtestId}/report` | GET | SINGLE 模式报告（含基准曲线） |
| `/api/backtest/compare?ids=b1,b2,b3` | GET | 多次回测对比（净值叠加 + 基准叠加 + 指标对比表） |
| `/api/backtest/{backtestId}` | DELETE | 删除回测记录（连带报告，物理删除；前端 confirm） |
| `/api/backtest/benchmarks` | GET | **可用基准列表**（前端下拉数据源） |

- **统一返回** `ApiResponse<T>`（`success`/`message`/`data`/`code`）
- **错误码**（第一波）：
  - `BACKTEST_NOT_FOUND`
  - `BACKTEST_STRATEGY_VERSION_INVALID`（策略版本 status 为 DRAFT/ARCHIVED）
  - `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（config 含 rebalance/exit.rules，引导等第二波）
  - `BACKTEST_MODE_NOT_SUPPORTED`（mode=GRID/WALK_FORWARD，引导等第二波）
  - `BACKTEST_BENCHMARK_NOT_FOUND`（benchmark 代码在 SQLite 不存在）
  - `BACKTEST_DATA_INSUFFICIENT`（数据为空或日期范围内无交易日）
  - `ENGINE_SERVICE_UNAVAILABLE`
  - `BACKTEST_TIMEOUT`
- **异步任务执行**：watcher 用 `@Async` 或 `CompletableFuture` 执行回测，主线程立即返回 `taskId`

---

### FR-6: 回测执行引擎（engine/Python 侧，第一波）

新建 `stock-engine/services/backtest/` 目录：

| 文件 | 职责 | 关键函数 |
|---|---|---|
| `__init__.py` | `__all__` 导出 | `run_backtest_engine`（第一波仅单次回测） |
| `compiler.py` | JSON config → akquant `Strategy` 子类动态编译（**第一波仅 signals + exit.bracket**） | `compile_strategy(config: StrategyConfigModel) -> Type[Strategy]` |
| `trading_engine.py` | 时序条件引擎（区别于 003 的截面 ConditionEngine） | `TradingConditionEngine`（支持 `cross_up`/`cross_down`/`ref`） |
| `data_adapter.py` | watcher kline_data → akquant DataFrame；benchmark 归一化 | `kline_to_df`、`kline_to_df_map`、`normalize_benchmark(benchmark_data) -> pd.Series` |
| `runner.py` | 核心执行器 | `run_backtest_engine(config, kline_map, benchmark_data) -> dict`、`build_backtest_kwargs(bt_config) -> dict` |
| `result_serializer.py` | `BacktestResult` → JSON | `serialize_result(result, benchmark_series) -> dict`（落地 [05-result-metrics.md §5](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/05-result-metrics.md) 模板，含 `_num`/`_metric` 安全取值 + Timestamp/Timedelta JSON 编码 + 基准归一化） |

#### FR-6a: compiler.py 详细要求（第一波：signals + bracket）

`compile_strategy(config)` 生成的 `Strategy` 子类必须：

- **`__init__`**：调 `super().__init__()`；设置 `self.warmup_period`（由 config 推断，取最大因子窗口）
- **`on_bar`**（信号驱动范式）：
  - 解析 `trading_config.signals.buy`（ConditionTree），用 `TradingConditionEngine` 求值（支持 `cross_up`/`cross_down`/`ref`）
  - 命中买入信号 → 按 `position_sizing.method` 下单（`order_target_percent` / `order_target_value` / `buy` 等）
  - 命中卖出信号 → `close_position` / `sell`
- **`on_before_trading`**（exit.bracket 静态止损止盈）：
  - 若 `exit.bracket` 在场，对持仓调 `place_bracket_order(symbol, quantity, stop_trigger_price, take_profit_price)`
  - `stop_loss_pct` → stop_trigger = entry_price × (1−pct)
  - `take_profit_pct` → take_profit = entry_price × (1+pct)
  - `use_atr_stop` 第一波 **不支持**（与 exit.rules 一起进第二波）
- **范式校验**：若 config 含 `trading_config.rebalance` 或 `exit.rules`，编译阶段抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`

#### FR-6b: TradingConditionEngine 详细要求

区别于 003 的截面 `ConditionEngine`：

- **支持时序 comparator**：`cross_up`（上穿，上一根 bar 不满足、当前满足）/ `cross_down`（下穿）
- **支持 ref 节点**：`entry_price` / `position_pnl_pct` / `position_qty` / `bars_held`（对齐 004 的 `ALLOWED_REFS` 白名单）
- **复用** `factor_calculator` 算因子（与选股口径一致）
- **无状态可并发**（对齐 003 `ConditionEngine` 的设计哲学）

#### FR-6c: build_backtest_kwargs 强制项

```python
def build_backtest_kwargs(bt_config: BacktestConfigModel) -> dict:
    return {
        "initial_cash": bt_config.initial_cash,
        "broker_profile": bt_config.broker_profile or "cn_stock_miniqmt",
        "t_plus_one": True,  # 强制（profile 不含）
        "lot_size": 100,     # 强制（A 股）
        "slippage": bt_config.slippage or {"type": "percent", "value": 0.0002},  # 必须 dict
        "volume_limit_pct": bt_config.volume_limit_pct,
        "warmup_period": bt_config.warmup_period,
        "history_depth": bt_config.history_depth,
        "fill_policy": bt_config.fill_policy,
        "timezone": bt_config.timezone or "Asia/Shanghai",
        "show_progress": False,
        # stamp_tax_rate 由 broker_profile 覆盖（仅卖出扣，akquant 内部处理）
    }
```

---

### FR-7: engine HTTP API（`api/v1/backtest.py`，第一波）

- **路由前缀**：`/python/v1/backtest`，`tags=["回测中心"]`
- **接口**（第一波仅 1 个）：
  - `POST /run` — 单次回测（请求 `BacktestRequest`，响应 `BacktestResponse`）
- **第二波预告**：`POST /optimize`（GRID）、`POST /walk-forward`（WF）——第一波不实现，路由不存在
- **统一响应信封**：`{success: bool, message: str, data: T}`
- **错误响应**：`ErrorResponse {success: false, message, code, errorCode?}` + 机器可读码：
  - `BACKTEST_COMPILE_ERROR`（JSON config 编译失败）
  - `BACKTEST_DATA_INVALID`（kline_data 格式错误/为空）
  - `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（含 rebalance/exit.rules）
  - `BACKTEST_INSUFFICIENT_DATA`（数据为空或日期范围内无交易日）
- **全局异常处理器**：复用 `main.py` 现有机制
- **Swagger 文档**：每个路由写 `summary`/`description`/`responses`，Pydantic 字段带 `examples`
- **常量接口**（第一波新增）：`GET /python/v1/backtest/constants` → 返回 `{broker_profiles, sort_metrics, paradigms_supported: ["signals+bracket"]}`，前端从此接口动态拉取常量，**禁止前端硬编码**（见 [分析报告 §六 6.3](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md)）
- 在 `main.py` 注册 router

---

### FR-8: watcher → engine HTTP 客户端（`BacktestClient`）

- 继承 `AbstractEngineClient`，`@Component`
- 方法（第一波）：
  - `runSingle(config, klineMap, benchmarkData) -> BacktestResultVO`
- **超时**：connect 5s / read **300s**（回测长任务，区别于校验的 30s）
- **重试**：连接异常（`ConnectException`/`SocketTimeout`）重试 1 次（间隔 500ms）；read timeout 不重试（避免请求堆积）
- engine 不可用抛 `BusinessException(ENGINE_SERVICE_UNAVAILABLE)`
- `exchangeData` 统一拆 engine 信封 `{success, data}` → 校验 success → 反序列化；engine 业务错误转 `BusinessException`

---

### FR-9: 回测列表页（watcher/Thymeleaf 前端）

- **页面路径**：`/quant/backtests`（PageController 注册路由）
- **侧边栏**：「量化」分组新增「回测中心」菜单项
- **顶部筛选栏**：策略下拉（GET `/api/strategies` 加载已保存策略）、状态下拉、日期范围（第一波不做 mode Tab，因为只有 SINGLE）
- **任务卡片网格**（Bootstrap `row-cols-1 row-cols-md-2 row-cols-lg-3 g-4`）：
  - 卡片：策略名 + 版本号、状态 badge（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）、核心指标摘要（SUCCESS 时显示收益/夏普/回撤/**超额收益 vs 沪深300**）、基准代码、创建时间
  - **RUNNING 状态**：显示进度条 + 自动轮询（每 3s 调 `GET /tasks/{taskId}`）；**隐藏取消按钮**
  - **FAILED/CANCELLED 状态**：显示「一键重跑」按钮
  - 底部操作：查看报告、对比（多选加入对比篮）、删除（确认弹窗）
- **右上角**：「+ 新建回测」按钮 → 跳配置页
- **分页控件**（底部）

---

### FR-10: 回测配置页（watcher/Thymeleaf 前端）

> 第一波配置页 **简化为 3 步**（原 4 步去掉「选模式」，因为只有 SINGLE）。详见 [分析报告 §三 🟠-5](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md)。

- **页面路径**：`/quant/backtests/new`
- **步骤 1：选择策略版本**
  - 下拉选 `strategy_id` → 自动加载版本列表（`GET /api/strategies/{id}/versions`）→ 选 `version_no`
  - 仅显示 VERIFIED/ACTIVE 状态的版本（DRAFT 见 [§11 OQ-4](#11-open-questions)）
- **步骤 2：配置参数**
  - 可覆盖 `backtest_config`：初始资金、日期范围、`broker_profile` 下拉、T+1 开关等
  - 显示策略摘要（从 `config_json` 前端拼接，复用 004 的摘要模板）
  - **benchmark 下拉**（必填，默认沪深300，数据源 `GET /api/backtest/benchmarks`）
- **步骤 3：提交**（`POST /api/backtest/run`）→ 跳列表页，新任务 PENDING → RUNNING 轮询
- **前端常量动态拉取**（不硬编码）：
  ```javascript
  // 启动时从 engine 拉取常量（权威来源 engine）
  const constants = await fetch('/api/backtest/constants-proxy').then(r => r.json());
  // constants.broker_profiles / constants.paradigms_supported
  ```

---

### FR-11: 回测报告页（watcher/Thymeleaf 前端）

- **页面路径**：`/quant/backtests/{backtestId}/report`
- **图表区**（ECharts 5 + lightweight-charts）：
  - **净值曲线 vs 基准**（必显示；策略净值 + 沪深300归一化净值叠加；从 `equity_curve_json` + `benchmark_curve_json` 取数据）
  - 回撤曲线
  - 月度收益热力图（ECharts heatmap）
- **指标卡片**：总收益、**超额收益（vs 基准）**、年化、夏普、索提诺、卡玛、最大回撤、胜率、盈亏比、交易笔数（单位注意：`total_return_pct`/`max_drawdown_pct` 是原始百分数，前端显示时 ÷100 或保留两位小数加 %）
- **交易明细表**（`trades_df`）：`entry_time`/`exit_time`/`entry_price`/`exit_price`/`quantity`/`side`/`pnl`/`return_pct`，支持分页排序
- **持仓快照表**（`positions_df`）
- **导出按钮**（第一波）：
  - 导出 CSV（`trades`，前端生成）
  - **HTML 报告导出**：第一波不做（见 [§3.2](#32-第二波才做第一波明确不做)）

---

### FR-12: 回测对比页（横向对比）

- **页面路径**：`/quant/backtests/compare?ids=b1,b2,b3`（从列表页对比篮进入）
- **净值叠加图**：多条策略曲线归一化到初始净值 1.0 + **基准曲线**（沪深300）作为参照线
- **指标对比表**：行=指标（含超额收益），列=各回测，高亮每行最优/最差
- **雷达图**（多维度对比：收益/夏普/回撤/胜率/盈亏比）

---

### FR-13: 业务编号派生与展示（S-XX / BT-XX）

> **新增于 v2.1**：为策略与回测提供**用户可见的业务编号**，服务跨情境引用场景（讨论、研究笔记、报 bug）。编号从数据库自增主键派生，**不补零、不复用、不新增字段**。

- **编号格式**：
  | 实体 | 格式 | 派生公式 | 示例 |
  |---|---|---|---|
  | 策略 | `S-{id}` | `"S-" + quant_strategy.id` | `S-1`、`S-7`、`S-12345` |
  | 回测 | `BT-{id}` | `"BT-" + quant_backtest.id` | `BT-1`、`BT-6`、`BT-10000` |
- **派生规则**：
  - **从 id 派生，无独立字段**：编号在 VO/序列化层现算，数据库表结构不变（`displayCode` 是计算属性，不是存储字段）
  - **不补零**：禁用 `String.format("S-%03d", id)`，避免破万后格式漂移；`S-999 → S-1000` 而非 `S-0999 → S-1000`
  - **永久唯一不复用**：删除记录后其编号永远空缺，新记录从下一个 id 继续（与 [004 版本快照不可变](file:///d:/lcProject/stock-pulse/.trae/specs/004-strategy-management/spec.md) 的审计哲学一致）
  - **前缀大小写**：`S-`/`BT-` 固定大写，避免和策略版本号 `v1`/`v2`（小写 v）混淆
- **UI 展示规则**（辅助定位，不抢主信息戏份）：
  - 策略卡片标题旁：`S-7`（11px、muted、mono）
  - 回测行子行：`#BT-6` 替代内部 task_id 展示
  - 报告页面包屑 + 页头副标题：`S-7 v3 · BT-6`
  - 搜索框支持 `S-`/`BT-` 前缀精确匹配
- **URL 路由**：第一波 URL 仍走 `strategyId`/`backtestId`（业务 UUID），编号仅展示；第二波可语义化为 `/quant/strategies/S-7`、`/quant/backtests/BT-6/report`
- **Open Question 关联**：见 [§11 OQ-8](#11-open-questions)

---

### FR-14: 回测中心前端交互行为规范

> **新增于 v2.1**：把原型验证过的交互细节沉淀为开发规范，避免实现阶段遗漏。详细 Scenario 见 [007 spec](file:///d:/lcProject/stock-pulse/.trae/specs/007-backtest-center/spec.md)「回测中心前端交互行为规范」Requirement。

涵盖 6 类规则：

1. **策略分组折叠/展开**
   - 默认仅 RUNNING 策略分组展开，其余折叠
   - 点击分组 head 切换；允许多个同时展开
   - 展开态分组卡 `grid-column: 1 / -1` 横跨整行；箭头 ▾ 旋转表示状态

2. **历史回测分页（核心）**
   - 展开态回测行**每页固定 5 条**（`PAGE_SIZE = 5`）
   - ≤5 条不显示分页控件；>5 条显示「第 X-Y 条 / 共 N 条」+ 页码按钮 + ‹/› 翻页
   - 翻页不刷新整页，仅切换 `display`
   - 默认第 1 页（最新 5 条，按 `created_at DESC`）

3. **回测行勾选 + 对比篮**
   - 勾选回测行 → 自动加入右下角浮动对比篮
   - chip 格式「策略名 v版本 · BT-编号 区间」
   - ✕ 删单条；篮空时 tray 自动隐藏；「清空」一键清空

4. **屏幕自适应与信息密度**
   - 折叠态策略卡 `repeat(auto-fill, minmax(440px, 1fr))` 自适应多列
   - 2K/4K 屏多列铺满；1K 屏单列 + 隐藏部分指标列；移动端单指标 + 卡头竖向堆叠
   - 展开态永远横跨整行

5. **状态优先级排序**
   - RUNNING 任务置顶；其余按 `created_at DESC`

6. **操作按钮区**
   - SUCCESS 行：📄 查看报告 / 📊 加入对比 / 🔄 重跑（3 个 icon-btn）
   - FAILED/CANCELLED 行：🔄 一键重跑（主按钮）+ 🗑 删除
   - RUNNING 行：**隐藏所有操作按钮**，显示「不可取消」灰字
   - 删除操作 confirm 二次确认

---

## 6. 第二波预告（不在本版范围）

> 以下能力在第一波 PRD 中 **不展开 FR**，仅记录设计决策，待第二波独立 PRD 细化。

### 6.1 GRID 网格参数寻优（第二波）

- 调 `aq.run_grid_search(strategy, param_grid, data, sort_by, max_workers, return_df=True, **kwargs)`，**不传 `db_path`**
- **constraint / resultFilter 改结构化 DSL**（不用 eval，见 [分析报告 §二 🔴-1](file:///d:/lcProject/stock-pulse/.trae/documents/005-回测中心PRD与设计文档分析报告.md)）：
  ```json
  "constraint": { "left": "fast", "op": "<", "right": "slow" }
  "resultFilter": { "left": "max_drawdown_pct", "op": "<", "right": 20 }
  ```
  engine 侧用安全求值器（与统一 Schema §4 的 ConditionTree 同模型，复用而非新造）
- **paramGrid 前端编辑器**：必须从策略 config 反推 `__init__` 形参 schema（依赖 004 是否在 config_json 存了「可调参数列表」，见 [§11 OQ-3](#11-open-questions)），不允许用户手填形参名
- **「应用最优参数」降级**：第二波只展示最优参数 + 「复制参数」按钮，**不自动创建策略版本**（避免过拟合背书）；只有当该参数同时通过 WF 验证时才允许「应用」

### 6.2 WALK_FORWARD 滚动验证（第二波）

- 调 `aq.run_walk_forward(strategy, param_grid, data, train_period, test_period, metric, compounding=..., **kwargs)`
- 数据长度必须 ≥ `trainPeriod + testPeriod`，否则 `BACKTEST_INSUFFICIENT_DATA`
- 前端：滚动分段时序图、每段最优参数表、过拟合判定指标（样本内外收益差、参数稳定性变异系数）

### 6.3 rebalance 多因子调仓驱动（第二波）

- `on_daily_rebalance` 解析 `screen_config`，本地跑 ConditionEngine + ranking 选股，`rebalance_to_topn` 调仓
- **前置依赖**：先把 ConditionEngine/factor_calculator 抽成 engine 共享模块（`services/shared/`），让选股和回测都 import，保证 AC「同条件同结果」

### 6.4 exit.rules 动态出场条件树（第二波）

- 支持 `ref`（entry_price/position_pnl_pct 等）+ 复杂动态止损（trailing stop、最高价回撤、多级止损）

### 6.5 HTML 报告导出（第二波）

- 落盘策略待定：engine 生成 HTML 返回 base64 / watcher 临时目录落盘 / 前端直接渲染

---

## 7. Non-Functional Requirements

### NFR-1: 性能（第一波）

| 场景 | 指标 |
|---|---|
| 单次回测（单标的，1 年日线） | < 10s（含 HTTP 往返 + benchmark 拼装） |
| 单次回测（固定池 30 标的，3 年日线） | < 60s |
| 任务状态轮询响应 | < 200ms |
| 报告页加载（含 trades + benchmark 曲线） | < 1s |

### NFR-2: 正确性

- 回测结果指标与 akquant `BacktestResult.metrics` 完全一致（序列化无损）
- **A 股规则强制**：`t_plus_one=True`、`lot_size=100`、`stamp_tax_rate` 仅卖出扣（akquant 内部处理）、`slippage` 用 dict（裸 float 已弃用，`0.2` 会被当成 20%）
- **benchmark 归一化正确**：基准净值曲线必须归一化到初始净值 1.0，与策略净值同坐标系叠加
- 任务状态机：`PENDING→RUNNING→SUCCESS/FAILED/CANCELLED`，无非法跃迁
- 序列化安全：`NaN`/`Inf` → `null`，`Timestamp` → `isoformat`，`Timedelta` → `total_seconds()`（可 JSON 序列化）
- 单位坑：`total_return_pct`/`max_drawdown_pct`/`win_rate` 是**原始百分数**（15.0=15%），前端展示时注意 ÷100 或加 %；`max_drawdown_*` 以**正数**存储
- **范式校验闭环**：含 rebalance/exit.rules 的 config 在 watcher 和 engine 双层拒绝（返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`）

### NFR-3: 可靠性

- engine 不可用时 watcher 返回 503，不写脏数据；任务状态置 FAILED + `error_message`
- 回测异常（编译错误/数据不足）engine 返回结构化错误，watcher 落 FAILED 状态 + 错误详情
- **任务持久化**：watcher 重启后 PENDING/RUNNING 任务重置为 FAILED（标「引擎中断」），第一版不支持断点续跑；提供「一键重跑」
- **长任务超时**：read timeout 300s，超时后任务标 FAILED
- **并发**：单 watcher 实例下任务串行执行（避免 SQLite 写冲突）；未来多实例需引入任务队列（超出本 PRD 范围）

### NFR-4: 可维护性

- engine 侧 `services/backtest/` 按职责拆分（compiler/runner/adapter/serializer），第一波不做 optimizer
- 复用 004 的 `StrategyConfigModel`/`BacktestConfigModel`，不自造模型
- 序列化模板严格对齐 [05-result-metrics.md §5](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/05-result-metrics.md)
- 前端 JS IIFE 模块（`window.Backtest`），**常量从 `/api/backtest/constants` 动态拉取**，禁止硬编码
- watcher 分层 Controller → Service → Mapper，与 `ScreenerServiceImpl` 风格一致

### NFR-5: 安全性

- 所有 `/api/backtest/**` 和页面路由需认证（`AuthInterceptor`）
- **回测请求只接受已保存策略的 `strategyId + versionNo`**，不接受任意 JSON config（防注入）
- **第一波无 eval 注入面**：GRID 的 constraint/resultFilter 改结构化 DSL 在第二波才引入；第一波 engine 代码禁用 `eval`/`exec`/`__import__`
- engine 编译器在受控命名空间执行（白名单 globals，禁用 `builtins` 危险函数）
- 删除回测记录需确认（前端 confirm + 后端物理删除连带报告）

### NFR-6: 兼容性

- akquant 锁定 0.2.47，参数名（`t_plus_one`/`broker_profile`/`fill_policy` 等）以该版本为准
- `config_json` 字段对齐统一 Schema v1.0（snake_case）；**新增 `backtest_config.benchmark` 字段**（见 [§11 OQ-5](#11-open-questions)），需同步 Schema
- 数据库 schema 用 `CREATE TABLE IF NOT EXISTS` / 增量迁移（兼容已有库）
- 前端页面骨架与 003/004 一致（`fragments/common.html` 复用）

---

## 8. Constraints

### 8.1 技术约束

- **engine**：Python 3.12 + FastAPI + Pydantic 2.x + akquant 0.2.47
- **watcher**：Java 21 + Spring Boot 4.0.6 + MyBatis-Plus + Caffeine + SQLite(WAL)
- **前端**：Thymeleaf + Bootstrap 5 + ECharts 5 + lightweight-charts + 原生 JavaScript（与现有页面一致）
- **硬约束**：engine 禁 `sqlite3`/`sqlalchemy`/直连 `.db`；交互单向 watcher→engine；前端不直连 engine
- **akquant 版本锁定**：0.2.47（`stock-engine/requirements.txt`）

### 8.2 业务约束

- 仅 A 股股票日线回测（不含期货/期权/分钟线）
- 仅消费已保存策略版本（`strategyId + versionNo`）
- `t_plus_one=True` / `lot_size=100` 强制（A 股规则）
- config 字段 100% 对齐统一 Schema v1.0，不自造字段（`benchmark` 除外，需同步 Schema）
- 技术面 `factorKey` 白名单严格对齐 Schema §4.5
- **第一波范式约束**：仅支持 `signals` + `exit.bracket`；`rebalance` / `exit.rules` 拒绝

### 8.3 依赖约束

- 002 因子库已提供技术因子注册表（54 因子，走 `akquant.talib`）
- 003 选股中心已实现 `ConditionEngine`（截面）+ `factor_calculator`（第一波需新增时序版 `TradingConditionEngine`；第二波 rebalance 直接复用）
- 004 策略管理已实现 `StrategyConfigModel`/`BacktestConfigModel` + CRUD + 版本快照 + 5 模板
- watcher 已具备：`AuthInterceptor`、统一 `ApiResponse`、`GlobalExceptionHandler`、`AbstractEngineClient`、Caffeine、分页 `PageResult`
- watcher 的 `stock_basic` / `daily_quote` 表已含主流指数（沪深300/中证500等）的 OHLCV（benchmark 数据源）

---

## 9. Assumptions

- 行情数据前复权、清洗由 watcher 上游完成（Phase 0 已实现，price × adj_factor，剔除停牌/涨跌停/ST）
- **基准（沪深300/中证500等）的 OHLCV 已在 watcher 的 SQLite 中**（作为指数代码存于 `stock_basic` + `daily_quote`）
- 回测所需基本面因子（若 config 引用 `PE_TTM` 等，第一波 signals 范式一般不涉及）由 watcher 一并拼装入 `kline_data` 的 `extra` 字段传入 engine
- **单 watcher 实例部署**（第一版任务串行；多实例需引入任务队列，超出本 PRD 范围）
- 5 个内置模板经校验合法，但不保证策略盈利
- **任务恢复**：watcher 重启后 RUNNING 任务第一版重置为 FAILED，不支持断点续跑；提供「一键重跑」

---

## 10. Acceptance Criteria

### AC-1: 单次回测端到端（含 benchmark）

- **Given**：已保存的 VERIFIED 双均线策略版本 v1（signals 范式 + exit.bracket），watcher/engine 运行中
- **When**：`POST /api/backtest/run`（`mode=SINGLE`, `strategyId`, `versionNo=1`）→ 轮询 task → `GET report`
- **Then**：任务 `PENDING→RUNNING→SUCCESS`；报告含 `metrics`（sharpe/return/drawdown 非空）+ `equity_curve`（`dates` 长度=交易日数）+ `benchmark_curve`（归一化到 1.0）+ `trades`；指标与 akquant 直接跑结果一致
- **Verification**：`programmatic`

### AC-2: A 股规则强制

- **Given**：任意回测
- **When**：检查 engine 传给 `run_backtest` 的参数
- **Then**：`t_plus_one=True`、`lot_size=100`、`slippage` 为 dict、`stamp_tax_rate` 仅卖出扣（akquant 内部）
- **Verification**：`programmatic`（engine 单测断言）

### AC-3: benchmark 默认叠加

- **Given**：用户未在 overrideConfig 指定 benchmark
- **When**：提交回测 → 查看报告
- **Then**：报告净值曲线图同时显示策略净值 + 沪深300归一化净值；指标卡片含「超额收益 vs 沪深300」
- **Verification**：`programmatic`

### AC-4: benchmark 多选覆盖

- **Given**：用户在 overrideConfig 指定 `benchmark: "000905.SH"`（中证500）
- **When**：提交回测 → 查看报告
- **Then**：报告叠加的是中证500归一化净值（非沪深300）
- **Verification**：`programmatic`

### AC-5: engine 不触库

- **Given**：engine 代码库 `services/backtest/`
- **When**：`grep sqlite3|sqlalchemy|\.db`
- **Then**：无匹配（akquant 内部 `import sqlite3` 不计入 engine 代码）
- **Verification**：`programmatic`

### AC-6: 异步任务状态机

- **Given**：提交回测任务
- **When**：轮询 task 状态
- **Then**：`PENDING→RUNNING→SUCCESS` 状态序列正确；FAILED 时 `error_message` 非空；CANCELLED 仅在 PENDING 可达
- **Verification**：`programmatic`

### AC-7: 回测对比

- **Given**：3 笔已 SUCCESS 的单次回测
- **When**：`GET /compare?ids=b1,b2,b3`
- **Then**：返回归一化净值叠加数据（3 条策略曲线 + 1 条基准线）+ 指标对比表（含超额收益行）
- **Verification**：`programmatic`

### AC-8: 策略版本对比回填（替换 TODO）

- **Given**：004 的 `compareVersions` 接口
- **When**：调用
- **Then**：返回真实回测指标（联查 `quant_backtest_report`），非空 metrics（替换 [StrategyServiceImpl.java:392-394](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java#L392-L394) 的 TODO）
- **Verification**：`programmatic`

### AC-9: 前端报告页渲染

- **Given**：一笔 SUCCESS 单次回测
- **When**：访�� `/quant/backtests/{id}/report`
- **Then**：净值曲线（含基准叠加）、回撤、月度热力、交易明细表正常渲染
- **Verification**：`human-judgment`

### AC-10: 策略版本状态校验

- **Given**：DRAFT 状态的策略版本
- **When**：`POST /api/backtest/run` 指向该版本
- **Then**：watcher 返回 400 + `BACKTEST_STRATEGY_VERSION_INVALID`；不调 engine
- **Verification**：`programmatic`

### AC-11: 第一波范式拒绝

- **Given**：config 含 `trading_config.rebalance` 或 `exit.rules` 的策略版本
- **When**：`POST /api/backtest/run`
- **Then**：watcher 返回 400 + `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`；不调 engine（双层校验：watcher 侧 + engine 侧）
- **Verification**：`programmatic`

### AC-12: 第二波模式拒绝

- **Given**：`POST /api/backtest/run` 带 `mode=GRID` 或 `mode=WALK_FORWARD`
- **When**：调用
- **Then**：watcher 返回 400 + `BACKTEST_MODE_NOT_SUPPORTED`（引导用户等第二波）
- **Verification**：`programmatic`

### AC-13: 序列化安全

- **Given**：回测结果含 NaN/Inf/Timestamp/Timedelta
- **When**：`serialize_result(result, benchmark_series)`
- **Then**：输出可 `json.dumps` 无异常；NaN/Inf → null，Timestamp → isoformat
- **Verification**：`programmatic`

### AC-14: 导航与权限

- **Given**：watcher 运行
- **When**：未登录访问 `/quant/backtests`；已登录访问侧边栏
- **Then**：未登录重定向到 `/login`；侧边栏「量化」下「回测中心」菜单项显示并可高亮
- **Verification**：`human-judgment`

### AC-15: API 文档完整性

- **Given**：engine 与 watcher 运行
- **When**：访问 engine `/docs` 和 watcher 接口文档
- **Then**：回测 run 接口、任务管理接口、benchmarks 接口、constants 接口均有请求/响应模型和错误码说明
- **Verification**：`human-judgment`

### AC-16: 失败重跑

- **Given**：一笔 FAILED 的回测任务
- **When**：点「一键重跑」
- **Then**：用原 config + benchmark 提交新任务，新任务 PENDING
- **Verification**：`programmatic`

### AC-17: 第一波无 eval 注入面

- **Given**：engine 代码库 `services/backtest/`
- **When**：`grep "\beval\b\|\bexec\b\|__import__"`
- **Then**：无匹配（第一波完全不引入 eval/exec）
- **Verification**：`programmatic`

### AC-18: 业务编号派生正确

- **Given**：`quant_strategy.id = 7` 的策略、`quant_backtest.id = 6` 的回测
- **When**：前端获取策略详情 / 回测列表
- **Then**：响应 VO 含 `displayCode: "S-7"` / `"BT-6"`（从 id 现算）；UI 在策略卡片标题旁、回测行子行、报告页面包屑展示该编号；搜索框输入 `BT-6` 能精确命中该回测
- **Verification**：`programmatic`

### AC-19: 回测中心前端交互行为

- **Given**：回测中心主页、含 RUNNING 任务的策略、12 条历史回测的策略、2K 屏（2560px）
- **When**：进入主页 + 展开含 12 条回测的策略分组 + 翻到第 2 页
- **Then**：
  - 仅有 RUNNING 任务的策略默认展开，其余折叠
  - 2K 屏下 8 个折叠策略自适应排成 4 列 × 2 行，无大片空白
  - 展开的分组横跨整行；12 条回测分页显示，每页 5 条；第 2 页显示第 6-10 条；分页条显示「第 6-10 条 / 共 12 条」
  - RUNNING 任务置顶；RUNNING 行无操作按钮、显示「不可取消」
  - 勾选回测行后对比篮 chip 显示「策略名 v版本 · BT-编号 区间」；✕ 删空后对比篮自动隐藏
- **Verification**：`human-judgment`

---

## 11. Open Questions

> 状态约定：`[未决]` 待讨论 / `[已决：决定]` 已拍板 / `[搁置：原因]` 暂不处理

- [ ] **OQ-1：任务并发模型** `[未决]`：第一版 watcher 单实例串行执行回测任务（避免 SQLite 写冲突），是否够用？多实例需引入任务队列（Redis/RabbitMQ），超出第一版范围。
- [ ] **OQ-2：RUNNING 任务取消** `[已决：第一波隐藏取消按钮]`：RUNNING 状态的任务第一波无法真正终止 engine 进程，前端隐藏取消按钮，仅 PENDING 可取消。第二波需 engine 协作实现真终止。
- [ ] **OQ-3：paramGrid 形参 schema 反推** `[搁置：第二波]`：第二波 GRID 需从策略 config 反推 `__init__` 形参 schema 自动生成编辑器。前置：核查 004 是否在 config_json 存了「可调参数列表」字段。第一波不涉及。
- [ ] **OQ-4：DRAFT 版本回测** `[未决]`：当前设计仅支持 VERIFIED/ACTIVE 版本回测。是否允许 DRAFT 版本回测（报告页标注「草稿版本回测，未经验证」）？倾向允许以提升试错效率，待用户确认。
- [ ] **OQ-5：backtest_config.benchmark 字段同步 Schema** `[已决：新增字段]`：需在统一 Schema §3.4 的 `backtest_config` 表新增 `benchmark` 字段（默认 `"000300.SH"`），与本 PRD 对齐。需同步更新 [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md)。
- [ ] **OQ-6：删除策略** `[未决]`：被回测引用的策略版本能否删除？软删除（ARCHIVED）后回测记录保留还是级联？
- [ ] **OQ-7：benchmark 候选白名单 vs 全指数** `[未决]`：`GET /api/backtest/benchmarks` 返回固定白名单（沪深300/中证500/上证50/中证1000）还是 watcher 所有指数？倾向白名单 + 允许手填。
- [ ] **OQ-8：业务编号展示形态** `[已决：S-XX / BT-XX，从 id 派生]`：见 [FR-13](#fr-13-业务编号派生与展示s-xx--bt-xx)。决策要点：① 从 `quant_strategy.id` / `quant_backtest.id` 派生，无独立字段；② 不补零（破万后 `S-999 → S-1000` 而非 `S-0999 → S-1000`）；③ 删除后空洞不复用；④ 第一波仅展示，URL 路由语义化（`/quant/backtests/BT-6/report`）推迟第二波。**为何不用业务 UUID 做编号**：UUID 不可记忆，无法服务"跨情境引用"的核心场景（讨论/笔记/报 bug）。

---

## 12. 附录：分波规划���决策记录

### 12.1 分波依据（来自分析报告）

| 能力 | 第一波 | 第二波 | 理由 |
|---|---|---|---|
| SINGLE 单次回测 | ✅ | — | 散户最高频场景（90%+） |
| signals 信号驱动 | ✅ | — | 双均线/MACD/RSI 等技术策略的核心范式 |
| exit.bracket 静止损 | ✅ | — | 实现成本中等，用户价值高（60%+） |
| benchmark 对比 | ✅ | — | 无基准的回测报告无决策价值 |
| 横向对比 | ✅ | — | README 列为 🔴 缺口，闭环必需 |
| GRID 网格寻优 | — | 🕐 | 进阶场景（30%），且需 paramGrid 形参反推（OQ-3） |
| WALK_FORWARD | — | 🕐 | 高阶场景（10%），需先有 GRID |
| rebalance 调仓 | — | 🕐 | 需先抽共享层（避免重写选股内核） |
| exit.rules 动态出场 | — | 🕐 | 复杂度高，与 rebalance 同期 |
| HTML 报告导出 | — | 🕐 | 落盘策略与 engine 硬约束边界待定 |

### 12.2 v2.0 相对 v1.0 的关键决策变更

| 变更项 | v1.0 | v2.0 | 依据 |
|---|---|---|---|
| 范围 | 全量三模式 + 四范式 | 第一波 SINGLE + signals + bracket | 分析报告 §二 🔴-2 |
| benchmark | Open Question（可选不传） | 强制默认沪深300，下拉多选 | 分析报告 §三 🟠-1 |
| constraint/resultFilter | eval 字符串 | 第二波改结构化 DSL，第一波无此字段 | 分析报告 §二 🔴-1 |
| 任务取消 | RUNNING 可取消（假期望） | 第一波隐藏取消按钮 | 分析报告 §五 🟡-5 |
| 「应用最优参数」 | 自动创建策略版本 | 第二波降级为复制参数 | 分析报告 §三 🟠-2 |
| paramGrid 编辑器 | 手填形参名 | 第二波反推 schema | 分析报告 §三 🟠-5 |
| HTML 导出 | 第一波做 | 第二波做 | 分析报告 §五 🟡-4 |
| 前端常量 | 硬编码 | 从 `/api/backtest/constants` 动态拉取 | 分析报告 §六 6.3 |
| 配置页步骤 | 4 步（含选模式） | 3 步（去掉选模式） | 分析报告 §三 🟠-5 |
| 文档定位 | PRD 堆开发细节 | PRD 聚焦需求，开发细节归 spec | 分析报告 §二 🔴-3 |

### 12.3 第二波 PRD 启动条件

第二波独立 PRD 启动前需先解决：
1. OQ-3（paramGrid 形参 schema 反推）——核查 004 config_json 结构
2. 共享层抽取——把 ConditionEngine/factor_calculator 抽成 `services/shared/`，保证 AC「同条件同结果」可验证
3. 结构化 DSL 设计——constraint/resultFilter 的 `{left, op, right}` 模型与统一 Schema §4 ConditionTree 的复用关系

---

**文档结束**

> 本 PRD 是产品需求真相。开发实现细节（API 路由签名、Pydantic 模型字段表、Java DO/Mapper 细节、akquant 参数完整映射、测试用例）由后续 `.trae/specs/007-backtest-center/` 三件套（spec.md / checklist.md / tasks.md）承载，待本 PRD 评审通过后生成。
