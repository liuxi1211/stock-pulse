# 回测中心（第一波）Spec

> **权威来源**：本 spec 是 [005 回测中心 PRD v2.0](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) 的开发实现约定。PRD 是产品需求真相（Why + What），本 spec 承载开发实现细节（API 路由签名、Pydantic/Java 模型字段表、akquant 参数映射、模块拆分）。
> **akquant 版本**：0.2.47（参数名以该版本为准）。
> **统一 Schema**：[统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) v1.0 + `backtest_config.benchmark` 新增字段。

---

## Why

回测中心（005）是「策略 → 回测 → 报告 → 对比」决策链的最后一公里。当前仓库：
- engine 侧回测执行能力为废案已清空，需基于 akquant 0.2.47 重写；
- 004 策略管���的 `StrategyServiceImpl.compareVersions`（`stock-watcher/.../StrategyServiceImpl.java:392-394`）仍是 TODO 占位；
- benchmark 对比、横向对比、异步任务管理均缺失。

第一波聚焦**最高频价值场景**：单次回测（SINGLE）+ 信号驱动范式（signals）+ 静态止损止盈（exit.bracket）+ 强制基准对比 + 横向对比。GRID/WF/rebalance/exit.rules 推迟到第二波。

## What Changes

### 新增：watcher 侧（Java）
- **数据库**：新建 `quant_backtest`（主表/任务表）+ `quant_backtest_report`（SINGLE 报告全量 JSON），同步更新 `schema-sqlite.sql` / `schema-mysql.sql`（`CREATE TABLE IF NOT EXISTS` 兼容增量迁移）。
- **DO/Mapper**：`QuantBacktestDO`/`QuantBacktestReportDO` + 对应 `BaseMapper`。
- **枚举**：`BacktestModeEnum`（第一波仅 SINGLE）、`BacktestStatusEnum`（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED），实现 `DisplayableEnum`。
- **Service**：`BacktestService`（`run` 标 `@Async`，编排策略版本读取 → 范式校验 → 数据拼装 → benchmark 拼装 → 调 engine → 落库）、`BacktestQueryService`（列表/报告/对比/重跑）。
- **HTTP Client**：`BacktestClient extends AbstractEngineClient`，`runSingle(config, klineMap, benchmarkData)`，connect 5s / read 300s，连接异常重试 1 次。
- **Controller**：`BacktestController`（`/api/backtest/**`），统一 `ApiResponse<T>`。
- **PageController**：注册 `/quant/backtests`、`/quant/backtests/new`、`/quant/backtests/{backtestId}/report`、`/quant/backtests/compare` 路由；侧边栏新增「回测中心」菜单项。
- **回填**：修改 `StrategyServiceImpl.compareVersions`，联查 `quant_backtest_report` 返回真实回测指标。

### 新增：engine 侧（Python）
- 新建 `stock-engine/services/backtest/` 目录：
  - `__init__.py`：导出 `run_backtest_engine`。
  - `compiler.py`：`compile_strategy(config) -> Type[Strategy]`（第一波仅 signals + exit.bracket）。
  - `trading_engine.py`：`TradingConditionEngine`（时序版，支持 `cross_up`/`cross_down`/`ref`），区别于 003 的截面 `ConditionEngine`。
  - `data_adapter.py`：`kline_to_df`、`kline_to_df_map`、`normalize_benchmark(benchmark_data) -> pd.Series`。
  - `runner.py`：`run_backtest_engine(config, kline_map, benchmark_data) -> dict`、`build_backtest_kwargs(bt_config) -> dict`。
  - `result_serializer.py`：`serialize_result(result, benchmark_series) -> dict`，落地 [05-result-metrics.md §5](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/05-result-metrics.md) 模板（`_num`/`_metric` 安全取值 + Timestamp/Timedelta JSON 编码 + 基准归一化）。
- 新建 `stock-engine/api/v1/backtest.py`：`POST /python/v1/backtest/run`、`GET /python/v1/backtest/constants`，在 `main.py` 注册 router。
- 复用 004 的 `StrategyConfigModel`/`BacktestConfigModel`，不自造模型；复用 003 的 `factor_calculator` 算因子（口径与选股一致）。

### 新增：前端（Thymeleaf + Bootstrap 5 + ECharts 5）
- 4 个页面：列表页 `/quant/backtests`、配置页 `/quant/backtests/new`、报告页 `/quant/backtests/{backtestId}/report`、对比页 `/quant/backtests/compare`。
- JS IIFE 模块（`window.Backtest`），**常量从 `/api/backtest/constants-proxy` 动态拉取**（前端不硬编码 broker_profile/paradigm 列表）。
- 报告页：净值曲线 + 基准叠加（必显示）、回撤、月度热力、交易明细表、持仓快照表。
- 对比页：归一化净值叠加（多条策略曲线 + 1 条基准）、指标对比表、雷达图。

### MODIFIED：统一策略配置 Schema
- **BREAKING（新增字段，向后兼容）**：`backtest_config` 新增 `benchmark` 字段（默认 `"000300.SH"`）。需同步更新 [统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) §3.4 与 004 的 `BacktestConfigModel`。

### MODIFIED：004 策略管理
- `StrategyServiceImpl.compareVersions` 替换 TODO 占位为真实回测指标联查（联查 `quant_backtest_report`）。
- `BacktestConfigModel`（engine）���增 `benchmark: str = "000300.SH"` 字段。

### 明确不做（推迟第二波）
- GRID 网格寻优 / WALK_FORWARD 滚动验证（含 constraint/resultFilter 结构化 DSL）。
- rebalance 多因子调仓驱动（含 engine 共享层抽取）���
- exit.rules 动态出场条件树。
- HTML 报告导出。
- RUNNING 任务真终止（第一波隐藏取消按钮）。
- DRAFT 版本回测（OQ-4 未决，第一波仅 VERIFIED/ACTIVE）。

## Impact

- **Affected specs**：
  - `004-strategy-management`：`BacktestConfigModel` 新增 `benchmark` 字段；`StrategyServiceImpl.compareVersions` 回填。
  - `005-strategy-visual-editor`：策略编辑器的「回测参数」Tab 需新增 benchmark 下拉（数据源 `/api/backtest/benchmarks`）。
  - 统一策略配置 Schema v1.0：`backtest_config.benchmark` 字段新增。
- **Affected code（关键路径）**：
  - watcher：`stock-watcher/src/main/java/com/arthur/stock/`（`controller/BacktestController.java`、`service/impl/BacktestServiceImpl.java`、`client/BacktestClient.java`、`mapper/QuantBacktestMapper.java`、`enums/BacktestStatusEnum.java`、`domain/QuantBacktestDO.java` 等）；`resources/schema-sqlite.sql` / `schema-mysql.sql`；`templates/quant/backtests/*.html`；`static/js/backtest.js`、`static/css/backtest.css`。
  - engine：`stock-engine/services/backtest/`（新建）；`stock-engine/api/v1/backtest.py`（新建）；`stock-engine/services/strategy/models.py`（`BacktestConfigModel` 加 `benchmark` 字段）；`stock-engine/main.py`（注册 router）。
- **依赖上游**：002 因子库（54 因子）、003 `factor_calculator`、004 `StrategyConfigModel`/CRUD 均已就绪。
- **硬约束**：engine 不触库（`grep sqlite3|sqlalchemy|\.db` 在 `services/backtest/` 无匹配）；第一波无 eval 注入面（`grep "\beval\b\|\bexec\b\|__import__"` 无匹配）；akquant 锁定 0.2.47。

---

## ADDED Requirements

### Requirement: 单次回测端到端链路（watcher 编排 + engine 执行）

系统 SHALL 提供 `POST /api/backtest/run`（watcher，异步）与 `POST /python/v1/backtest/run`（engine，同步执行）的双层链路，watcher 编排策略版本读取 → 范式校验 → 数据/benchmark 拼装 → 调 engine → 落库，engine 编排 config 解析 → 编译 Strategy 子类 → `aq.run_backtest` → 序列化结果。

#### Scenario: 单次回测成功
- **GIVEN** 已保存的 VERIFIED 双均线策略版本 v1（signals 范式 + exit.bracket），watcher 与 engine 运行中
- **WHEN** `POST /api/backtest/run`（`mode=SINGLE`, `strategyId`, `versionNo=1`）→ 轮询 task → `GET report`
- **THEN** 任务状态序列 `PENDING→RUNNING→SUCCESS`；报告含 `metrics`（sharpe/return/drawdown 非空）、`equity_curve`（`dates` 长度=交易日数）、`benchmark_curve`（归一化到 1.0）、`trades`；指标与 akquant 直接跑结果一致

#### Scenario: 策略版本状态非法
- **GIVEN** DRAFT 或 ARCHIVED 状态的策略版本
- **WHEN** `POST /api/backtest/run` 指向该版本
- **THEN** watcher 返回 400 + `BACKTEST_STRATEGY_VERSION_INVALID`，不调 engine

#### Scenario: 第一波范式拒绝（双层校验）
- **GIVEN** config 含 `trading_config.rebalance` 或 `exit.rules`
- **WHEN** `POST /api/backtest/run`
- **THEN** watcher 返回 400 + `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`，不调 engine；engine 侧编译阶段同样抛该错误（双层防御）

#### Scenario: 第二波模式拒绝
- **GIVEN** `mode=GRID` 或 `mode=WALK_FORWARD`
- **WHEN** `POST /api/backtest/run`
- **THEN** watcher 返回 400 + `BACKTEST_MODE_NOT_SUPPORTED`

#### Scenario: 数据不足
- **GIVEN** 策略 symbols 在日期范围内无行情，或 kline_data 为空
- **WHEN** watcher 拼装数据时检测到
- **THEN** 返回 400 + `BACKTEST_DATA_INSUFFICIENT`，不调 engine

#### Scenario: engine 不可用
- **GIVEN** engine 服务未启动或连接超时
- **WHEN** watcher 调 `BacktestClient.runSingle`
- **THEN** watcher 抛 `BusinessException(ENGINE_SERVICE_UNAVAILABLE)`，任务落 FAILED + `error_message`

### Requirement: 异步任务状态机

系统 SHALL 维护 `PENDING → RUNNING → SUCCESS/FAILED` 与 `PENDING → CANCELLED` 的状态机，无非法跃迁。RUNNING 任务第一波不可取消（前端隐藏取消按钮），仅 PENDING 可取消。

#### Scenario: 正常状态跃迁
- **GIVEN** 提交回测任务
- **WHEN** watcher 开始调 engine（`@Async`）
- **THEN** 任务 `PENDING → RUNNING`；engine 返回后 `RUNNING → SUCCESS`（含全量报告落库）或 `RUNNING → FAILED`（含 `error_message` 截断 1024 字符）

#### Scenario: PENDING 可取消
- **GIVEN** 处于 PENDING 的任务
- **WHEN** `POST /api/backtest/tasks/{taskId}/cancel`
- **THEN** 任务 `PENDING → CANCELLED`

#### Scenario: RUNNING 不可取消
- **GIVEN** 处于 RUNNING 的任务
- **WHEN** `POST /api/backtest/tasks/{taskId}/cancel`
- **THEN** 返回 409 Conflict + 引导文案（第一波 RUNNING 无法终止 engine 进程）

#### Scenario: 重启后任务恢复
- **GIVEN** watcher 重启时存在 PENDING/RUNNING 任务
- **WHEN** 启动完成
- **THEN** 这些任务重置为 FAILED，`error_message` 标「引擎中断，请重跑」

#### Scenario: 失败重跑
- **GIVEN** FAILED 或 CANCELLED 的任务
- **WHEN** `POST /api/backtest/tasks/{taskId}/rerun`
- **THEN** 复用原 config + benchmark 提交新任务，新任务 PENDING；不删除原任务

### Requirement: 回测数据模型持久化（watcher）

系统 SHALL 新建 `quant_backtest`（主表/任务表）与 `quant_backtest_report`（SINGLE 报告全量 JSON）两张表，字段定义与 PRD FR-3 完全对齐，使用 `CREATE TABLE IF NOT EXISTS` 兼容增量迁移。

#### Scenario: 主表字段完整
- **GIVEN** 新建 `quant_backtest`
- **THEN** 含字段：`id`(PK)/`task_id`(UNIQUE)/`strategy_id`/`version_no`/`mode`(默认 SINGLE)/`status`(默认 PENDING)/`progress`/`error_message`/`override_config`/`benchmark`(默认 `000300.SH`)/`created_by`/`started_at`/`finished_at`/`created_at`；索引 `INDEX(strategy_id, version_no)`、`INDEX(status)`、`INDEX(mode)`

#### Scenario: 报告表全量 JSON
- **GIVEN** 新建 `quant_backtest_report`
- **THEN** 含字段：`id`(PK)/`backtest_id`(FK → `quant_backtest.id`)/`metrics_json`/`equity_curve_json`/`benchmark_curve_json`/`daily_returns_json`/`trades_json`/`orders_json`/`positions_json`/`created_at`；约束 `UNIQUE(backtest_id)`

#### Scenario: SQLite 与 MySQL 双 schema 同步
- **GIVEN** schema 文件
- **WHEN** 检查 `schema-sqlite.sql` 与 `schema-mysql.sql`
- **THEN** 两文件均含两张新表的 DDL，字段类型按方言适配（SQLite TEXT / MySQL VARCHAR/TEXT/JSON）

### Requirement: benchmark 强制对比与归一化

系统 SHALL 强制每次回测有基准（默认沪深300 `000300.SH`），engine 把基准收盘价归一化到初始净值 1.0（`price / price[0]`）与策略净值同坐标系叠加。

#### Scenario: 默认基准
- **GIVEN** 用户未在 `overrideConfig.benchmark` 指定，且 config_json 的 `backtest_config.benchmark` 未设
- **WHEN** 提交回测 → 查看报告
- **THEN** 使用默认 `000300.SH`，报告净值曲线叠加沪深300归一化净值；指标卡片含「超额收益 vs 沪深300」

#### Scenario: 覆盖基准
- **GIVEN** `overrideConfig.benchmark = "000905.SH"`（中证500）
- **WHEN** 提交回测 → 查看报告
- **THEN** 报告叠加中证500归一化净值；优先级：`overrideConfig` > `config_json.backtest_config.benchmark` > 默认

#### Scenario: 基准不存在
- **GIVEN** 用户指定了 SQLite 中不存在的指数代码
- **WHEN** watcher 拼装 benchmark_data
- **THEN** 返回 400 + `BACKTEST_BENCHMARK_NOT_FOUND`

#### Scenario: 基准归一化正确
- **GIVEN** 基准收盘价序列 `[3000, 3030, 2970]`
- **WHEN** engine `normalize_benchmark`
- **THEN** 输出 `[1.0, 1.01, 0.99]`

### Requirement: 任务管理 API（watcher，第一波）

系统 SHALL 提供 PRD FR-5 表中的 10 个 `/api/backtest/**` 接口，统一 `ApiResponse<T>` 信封，错误码覆盖 `BACKTEST_NOT_FOUND`/`BACKTEST_STRATEGY_VERSION_INVALID`/`BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`/`BACKTEST_MODE_NOT_SUPPORTED`/`BACKTEST_BENCHMARK_NOT_FOUND`/`BACKTEST_DATA_INSUFFICIENT`/`ENGINE_SERVICE_UNAVAILABLE`/`BACKTEST_TIMEOUT`。

#### Scenario: 任务列表分页与筛选
- **GIVEN** 多笔回测记录
- **WHEN** `GET /api/backtest/tasks?page=1&size=20&strategyId=xxx&status=SUCCESS&startDate=...&endDate=...`
- **THEN** 返回 `PageResult<BacktestTaskVO>`，含分页元数据与筛选条件命中

#### Scenario: 报告查询
- **GIVEN** SUCCESS 状态的回测
- **WHEN** `GET /api/backtest/{backtestId}/report`
- **THEN** 返回 `BacktestReportVO`，含 `metrics` + `equity_curve` + `benchmark_curve` + `trades` + `positions`

#### Scenario: 横向对比
- **GIVEN** 3 笔 SUCCESS 单次回测
- **WHEN** `GET /api/backtest/compare?ids=b1,b2,b3`
- **THEN** 返回归一化净值叠加数据（3 条策略曲线 + 1 条基准线）+ 指标对比表（含超额收益行）+ 雷达图数据

#### Scenario: 删除（物理删除连带报告）
- **GIVEN** 已存在的回测记录
- **WHEN** `DELETE /api/backtest/{backtestId}`
- **THEN** 物理删除主表 + 报告表记录

#### Scenario: 可用基准列表
- **WHEN** `GET /api/backtest/benchmarks`
- **THEN** 返回基准候选列表（沪深300/中证500/上证50/中证1000 + 允许手填的提示）

### Requirement: engine HTTP API 与常量接口

系统 SHALL 提供 `POST /python/v1/backtest/run`（单次回测）、`GET /python/v1/backtest/constants`（常量）接口，路由前缀 `/python/v1/backtest`，统一信封 `{success, message, data}`，错误响应含机器可读 `errorCode`。

#### Scenario: 常量动态拉取
- **WHEN** `GET /python/v1/backtest/constants`
- **THEN** 返回 `{broker_profiles: [...], sort_metrics: [...], paradigms_supported: ["signals+bracket"]}`；前端从此接口拉取常量，不硬编码

#### Scenario: 错误信封
- **GIVEN** engine 编译 config 失败 / kline_data 格式错误 / 含不支持范式 / 数据不足
- **WHEN** `POST /python/v1/backtest/run`
- **THEN** 返回 `{success: false, message, code, errorCode}`，errorCode ∈ `BACKTEST_COMPILE_ERROR`/`BACKTEST_DATA_INVALID`/`BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`/`BACKTEST_INSUFFICIENT_DATA`

#### Scenario: Swagger 文档
- **WHEN** 访问 engine `/docs`
- **THEN** 回测 run / constants 接口有 `summary`/`description`/`responses`，Pydantic 字段带 `examples`

### Requirement: compiler + TradingConditionEngine（engine，第一波）

engine SHALL 实现 `compile_strategy(config) -> Type[Strategy]`，动态生成 akquant `Strategy` 子类：`on_bar` 解析 `signals.buy`/`signals.sell`（用 `TradingConditionEngine` 求值，支持 `cross_up`/`cross_down`/`ref`），`on_before_trading` 对持仓调 `place_bracket_order` 实现 exit.bracket 静态止损止盈。

#### Scenario: 信号驱动买入
- **GIVEN** `signals.buy` 命中（如 MA5 cross_up MA20）
- **WHEN** `on_bar` 求值
- **THEN** 按 `position_sizing.method` 下单（`order_target_percent`/`order_target_value`/`buy`）

#### Scenario: 信号驱动卖出
- **GIVEN** `signals.sell` 命中
- **WHEN** `on_bar` 求值
- **THEN** 调 `close_position` 或 `sell`

#### Scenario: 静态止损止盈
- **GIVEN** 持仓 + `exit.bracket` 在场（`stop_loss_pct=0.05`, `take_profit_pct=0.1`）
- **WHEN** `on_before_trading` 触发
- **THEN** 调 `place_bracket_order(symbol, quantity, stop_trigger=entry_price*0.95, take_profit=entry_price*1.1)`

#### Scenario: use_atr_stop 不支持
- **GIVEN** `exit.bracket.use_atr_stop = true`
- **WHEN** 编译
- **THEN** 抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（与 exit.rules 同期进第二波）

#### Scenario: 范式校验（编译期）
- **GIVEN** config 含 `trading_config.rebalance` 或 `exit.rules`
- **WHEN** `compile_strategy`
- **THEN** 抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`

#### Scenario: 时序 comparator
- **GIVEN** `cross_up`（上穿）条件：上一根 bar 不满足、当前满足
- **WHEN** `TradingConditionEngine` 求值
- **THEN** 正确识别穿越事件

#### Scenario: ref 节点
- **GIVEN** ref 节点 `entry_price` / `position_pnl_pct` / `position_qty` / `bars_held`（白名单内）
- **WHEN** 求值
- **THEN** 从持仓状态正确取值（对齐 004 的 `ALLOWED_REFS`）

### Requirement: A 股回测规则强制（build_backtest_kwargs）

engine SHALL 在 `build_backtest_kwargs` 强制 A 股规则：`t_plus_one=True`、`lot_size=100`、`broker_profile` 默认 `cn_stock_miniqmt`、`slippage` 用 dict（默认 `{"type":"percent","value":0.0002}`），`stamp_tax_rate` 仅卖出扣（由 broker_profile/akquant 内部处理）。

#### Scenario: 强制参数
- **GIVEN** 任意 BacktestConfigModel
- **WHEN** `build_backtest_kwargs`
- **THEN** 返回 dict 含 `t_plus_one=True`、`lot_size=100`、`broker_profile`（默认 cn_stock_miniqmt）、`slippage`（dict 形式）、`show_progress=False`

#### Scenario: 滑点裸 float 拒绝
- **GIVEN** `bt_config.slippage = 0.0002`（裸 float）
- **WHEN** `build_backtest_kwargs`
- **THEN** 转为 `{"type":"percent","value":0.0002}`（裸 float 已弃用，`0.2` 会被当成 20%）

### Requirement: 序列化安全

engine SHALL 通过 `serialize_result(result, benchmark_series)` 把 `BacktestResult` 转成可 `json.dumps` 的 dict，处理 NaN/Inf → null、Timestamp → isoformat、Timedelta → total_seconds()，并附上基准归一化净值曲线。

#### Scenario: NaN/Inf 安全
- **GIVEN** 回测结果含 NaN/Inf
- **WHEN** `serialize_result`
- **THEN** NaN/Inf → None，输出可 `json.dumps` 无异常

#### Scenario: Timestamp/Timedelta 编码
- **GIVEN** trades_df 含 Timestamp/Timedelta 列
- **WHEN** 序列化
- **THEN** Timestamp → isoformat、Timedelta → total_seconds()

#### Scenario: 单位保留
- **GIVEN** `metrics_df` 含 `total_return_pct=15.0`（原始百分数）
- **WHEN** 序列化
- **THEN** 保留为 `15.0`（前端展示时 ÷100 或加 %）；`max_drawdown_*` 以正数存储

### Requirement: BacktestClient（watcher → engine HTTP）

watcher SHALL 实现 `BacktestClient extends AbstractEngineClient`，`@Component`，`runSingle(config, klineMap, benchmarkData) -> BacktestResultVO`，connect 5s / read 300s，连接异常重试 1 次（500ms 间隔），read timeout 不重试。

#### Scenario: engine 业务错误转换
- **GIVEN** engine 返回 `{success: false, errorCode: "BACKTEST_COMPILE_ERROR"}`
- **WHEN** `exchangeData` 拆信封
- **THEN** 抛 `BusinessException`，errorCode 透传

#### Scenario: 连接异常重试
- **GIVEN** 调用时抛 `ConnectException`
- **WHEN** BacktestClient 拦截
- **THEN** 间隔 500ms 重试 1 次；仍失败抛 `ENGINE_SERVICE_UNAVAILABLE`

#### Scenario: read timeout 不重试
- **GIVEN** 调用时 read timeout（>300s）
- **WHEN** BacktestClient 拦截
- **THEN** 直接抛 `BACKTEST_TIMEOUT`，不重试（避免请求堆积）

### Requirement: 前端列表页与配置页（第一波 3 步）

系统 SHALL 提供回测列表页（任务卡片网格 + 顶部筛选 + RUNNING 隐藏取消 + FAILED/CANCELLED 显��重跑）与配置页（3 步：选策略版本 → 配置参数 → 提交），常量从 `/api/backtest/constants-proxy` 动态拉取。

#### Scenario: 列表页 RUNNING 状态
- **GIVEN** 处于 RUNNING 的任务
- **WHEN** 列表页渲染该卡片
- **THEN** 显示进度条 + 每 3s 自动轮询 `GET /tasks/{taskId}`；**隐藏取消按钮**

#### Scenario: 列表页 FAILED/CANCELLED
- **GIVEN** 处于 FAILED/CANCELLED 的任务
- **WHEN** 渲染该卡片
- **THEN** 显示「一键重跑」按钮

#### Scenario: 配置页步骤简化
- **GIVEN** 配置页
- **WHEN** 渲染
- **THEN** 3 步（选策略版本 → 配置参数 → 提交），无「选模式」步骤（第一波只有 SINGLE）

#### Scenario: 常量动态拉取
- **GIVEN** 配置页加载
- **WHEN** 初始化
- **THEN** 从 `/api/backtest/constants-proxy` 拉取 `broker_profiles`/`paradigms_supported`，渲染下拉选项；不硬编码

#### Scenario: benchmark 必填
- **GIVEN** 配置页参数步骤
- **WHEN** 渲染
- **THEN** benchmark 下拉必填，默认沪深300，数据源 `GET /api/backtest/benchmarks`

### Requirement: 报告页与对比页（含基准叠加）

系统 SHALL 提供报告页（净值曲线 + 基准叠加必显示、回撤、月度热力、交易明细表、持仓快照表、CSV 导出）与对比页（归一化净值叠加 + 指标对比表 + 雷达图）。

#### Scenario: 报告页基准叠加必显示
- **GIVEN** SUCCESS 状态的回测
- **WHEN** 访问 `/quant/backtests/{backtestId}/report`
- **THEN** 净值曲线图同时显示策略净值 + 基准归一化净值；指标卡片含「超额收益 vs 基准」

#### Scenario: 单位展示
- **GIVEN** 后端返回 `total_return_pct=15.0`、`max_drawdown_pct=8.5`（原始百分数）
- **WHEN** 报告页渲染指标卡片
- **THEN** 显示为 `15.00%` / `8.50%`（÷100 后加 %）；最大回撤以正数展示

#### Scenario: CSV 导出
- **GIVEN** 报告页的交易明细表
- **WHEN** 点「导出 CSV」
- **THEN** 前端生成 `trades.csv`（含 entry_time/exit_time/entry_price/exit_price/quantity/side/pnl/return_pct）

#### Scenario: 对比页归一化叠加
- **GIVEN** 3 笔 SUCCESS 回测加入对比
- **WHEN** 访问 `/quant/backtests/compare?ids=b1,b2,b3`
- **THEN** 多条策略曲线归一化到 1.0 + 1 条基准线叠加；指标对比表高亮每行最优/最差；雷达图多维对比

### Requirement: 策略版本对比回填

watcher SHALL 修改 `StrategyServiceImpl.compareVersions`，联查 `quant_backtest_report` 返回真实回测指标，替换 `StrategyServiceImpl.java:392-394` 的 TODO 占位。

#### Scenario: 真实指标回填
- **GIVEN** 同一 strategy_id 的两个版本各自有 SUCCESS 回测
- **WHEN** 调 `compareVersions`
- **THEN** 返回的 `StrategyVersionCompareDTO` 含真实 sharpe/return/drawdown（联查 `quant_backtest_report.metrics_json`），非空

#### Scenario: 无回测记录降级
- **GIVEN** 版本无 SUCCESS 回测
- **WHEN** 调 `compareVersions`
- **THEN** 该版本指标字段为 null（前端展示「暂无回测数据」），不报错

### Requirement: 业务编号派生与展示规则（S-XX / BT-XX）

系统 SHALL 为策略与回测提供**用户可见的业务编号**，作为跨情境引用（讨论、笔记、报 bug）的辅助标识。编号**从数据库自增主键 `id` 派生**，不新增独立字段，不补零，删除后空洞不回收（与版本快照不可变的审计哲学一致）。

**派生规则**：

| 实体 | 编号格式 | 派生公式 | 示例 |
|---|---|---|---|
| 策略 | `S-{id}` | `"S-" + quant_strategy.id` | `S-1`、`S-7`、`S-12345` |
| 回测 | `BT-{id}` | `"BT-" + quant_backtest.id` | `BT-1`、`BT-6`、`BT-10000` |

**实现约束**：
- **无独立字段**：编号在 VO/序列化层现算（如 `BacktestTaskVO.getDisplayCode()` 返回 `"BT-" + id`），数据库表结构不变。
- **不补零**：禁用 `String.format("S-%03d", id)` 之类的补零方案，避免编号破万后格式漂移（`S-999` → `S-1000` 而非 `S-0999` → `S-1000`）。
- **永久唯一**：编号一旦随记录生成，**永不复用**——删除策略/回测后，其编号永远空缺，新记录从下一个 id 继续。这与 [004 版本快照不可变](file:///d:/lcProject/stock-pulse/.trae/specs/004-strategy-management/spec.md) 的审计完整性哲学一致。
- **前缀语义**：`S-` = Strategy，`BT-` = BackTest；前缀大小写固定为大写，避免和策略版本号 `v1/v2`（小写 v）混淆。

**UI 展示规则**（小灰字辅助定位，不抢主信息戏份）：

| 位置 | 展示 | 视觉权重 |
|---|---|---|
| 策略卡片标题旁 | `S-7` | 11px、`var(--text-muted)`、mono 字体 |
| 策略编辑器页头副标题 | `S-7 · v3` | 同上 |
| 回测行子行 | `#BT-6` 替代原 `#bt_006` | 11px、`var(--text-muted)`、mono |
| 报告页面包屑 | `…/ BT-6 / 报告` | 同面包屑样式 |
| 报告页页头副标题 | `S-7 v3 · BT-6` | 11.5px、mono |

**路由语义化（可选，第二波）**：
- `/quant/strategies/S-7`（替代 `/quant/strategies/{strategyId}`）
- `/quant/backtests/BT-6/report`（替代 `/quant/backtests/{backtestId}/report`）
- 第一波可不实现，URL 仍走 `strategyId`/`backtestId`（业务 UUID），编号仅用于展示。

#### Scenario: 编号派生正确
- **GIVEN** `quant_strategy.id = 7` 的策略
- **WHEN** 前端获取策略详情
- **THEN** 响应 VO 含 `displayCode: "S-7"`（从 id 现算，无独立字段）

#### Scenario: 编号不补零
- **GIVEN** `quant_backtest.id` 分别为 `6`、`100`、`10000` 的三条回测
- **WHEN** 渲染编号
- **THEN** 分别显示为 `BT-6`、`BT-100`、`BT-10000`（无前导零，破万后格式不变）

#### Scenario: 删除后编号空洞
- **GIVEN** 删除 `id=8` 的回测（`BT-8`）
- **WHEN** 新建回测
- **THEN** 新回测 `id=9`，编号 `BT-9`；`BT-8` 永久空缺，不被新记录复用

#### Scenario: 编号跨情境引用
- **GIVEN** 用户在研究笔记里写「S-7 v3 在 BT-6 的回测夏普 1.82」
- **WHEN** 其他用户在系统内搜索 `BT-6`
- **THEN** 命中该回测记录（前端搜索框支持 `S-`/`BT-` 前缀精确匹配）

### Requirement: 回测中心前端交互行为规范（v2.1 新增）

回测中心前端 SHALL 遵循以下交互行为规范，确保不同分辨率下的信息密度与操作一致性。所有规则在 [strategy-backtests.html](file:///d:/lcProject/stock-pulse/.trae/specs/007-backtest-center/prototype/strategy-backtests.html) 原型中已落地，开发实现 MUST 与之一致。

**1. 策略分组折叠/展开**：
- 默认状态：仅有 RUNNING 任务的策略分组**默认展开**，其余折叠
- 展开触发：点击策略分组的 `.bt-strat-group-head`（不含其内部的链接/按钮，需 `e.target.closest('a, button')` 排除）
- 展开态视觉：分组卡 `grid-column: 1 / -1` **横跨整行**（展开后回测行需整行展示，多列网格中无法容纳）；箭头旋转 0°（▾ 朝下）
- 折叠态视觉：分组卡占网格中 1 列；箭头旋转 -90°（▾ 朝右）
- 同时只允许 1 个分组展开？**否**—��允许多个同时展开（用户可对比查看），但默认仅 RUNNING 那个展开

**2. 历史回测分页**：
- 展开态的回测行**每页 5 条**（`PAGE_SIZE = 5`）
- ≤5 条时不显示分页控件
- >5 条时在 body 末尾显示分页条：左侧 `第 X-Y 条 / 共 N 条`（mono 字体），右侧页码按钮 + ‹/› 前后翻页
- 翻页不刷新整页，仅切换 `.bt-row` 的 `display`（`none`/`''`）
- 默认显示第 1 页（最新 5 条按 `created_at DESC` 已排序）

**3. 回测行勾选（对比篮）**：
- 点击回测行左侧 checkbox → 切换选中态（蓝青渐变填充 + ✓）
- 选中后自动加入右下角浮动对比篮（`.bt-compare-tray`）
- 对比篮 chip 显示「策略名 v版本 · BT-编号 区间」
- chip 的 `✕` 删除单条；篮空时整个 tray 自动隐藏
- 「清空」按钮一键清空
- 对比篮最多 N 条？**第一波不硬限制**（建议 ≤5，超过时前端 toast 提示「对比建议 ≤5 条以保证可读性」）

**4. 屏幕自适应与信息密度**：
- **2K/4K 屏**：折叠态策略卡用 `grid-template-columns: repeat(auto-fill, minmax(440px, 1fr))` **自适应多列**，整屏铺满（每列最小 440px）
- **1K 屏（≤1280px）**：折叠卡单列；展开的 bt-row 隐藏第 3+ 指标列（仅显示收益/夏普）
- **移动端（≤640px）**：bt-row 仅显示 1 个指标（收益）+ 卡头竖向堆叠（`flex-direction: column`）
- 展开态分组**永远横跨整行**，不受多列网格影响

**5. 状态优先级排序（展开态回测行顺序）**：
- RUNNING 任务**置顶**（用户最关心的进行中状态）
- 之后按 `created_at DESC`（最新优先）
- FAILED/CANCELLED 不置顶，按时间正常排序

**6. 操作按钮区**：
- 每条回测行右侧固定 3 个 icon-btn：📄 查看报告 / 📊 加入对比 / 🔄 重跑
- FAILED 行额外显示「🔄 一键重跑」（主按钮样式）+ 删除按钮
- RUNNING 行**隐藏所有操作按钮**，仅显示「不可取消」灰字提示
- 删除操作 MUST 弹出 confirm 二次确认

#### Scenario: 默认展开规则
- **GIVEN** 双均线金叉策略有 1 个 RUNNING 任务，MACD 策略全部 SUCCESS
- **WHEN** 用户进入回测中心主页
- **THEN** 双均线金叉默认展开（显示回测行 + 分页），MACD 折叠（仅显示 head 一行）

#### Scenario: 分页正确
- **GIVEN** 某策略有 12 条 SUCCESS 回测
- **WHEN** 展开该策略分组
- **THEN** 显示前 5 条 + 分页条「第 1-5 条 / 共 12 条」+ 页码 1/2/3 + › 翻页按钮；点击第 2 页显示第 6-10 条

#### Scenario: 折叠态多列网格
- **GIVEN** 2K 屏（2560px）下 8 个折叠策略
- **WHEN** 渲染主页
- **THEN** 折叠卡按 `minmax(440px, 1fr)` 自适应排成 4 列 × 2 行，整屏铺满无大片空白

#### Scenario: 展开态横跨整行
- **GIVEN** 多列网格中某策略卡处于第 2 列
- **WHEN** 点击其 head 展开
- **THEN** 该卡 `grid-column: 1 / -1` 横跨所有列，回测行以整行宽度展示

#### Scenario: RUNNING 行无操作按钮
- **GIVEN** 处于 RUNNING 的回测行
- **WHEN** 渲染
- **THEN** 隐藏查看报告/加入对比/重跑按钮，仅显示「不可取消」灰字（第一波无法终止 engine 进程）

#### Scenario: 对比篮自动显隐
- **GIVEN** 对比篮有 3 条 chip
- **WHEN** 用户逐个点 `✕` 删除
- **THEN** 删到最后 1 条后整个 `.bt-compare-tray` 自动隐藏（`transform: translateY(120%)`）

---

## MODIFIED Requirements

### Requirement: 统一策略配置 Schema backtest_config 字段

`backtest_config` SHALL 新增 `benchmark: str = "000300.SH"` 字段，与 PRD FR-4 对齐。优先级：`overrideConfig.benchmark` > `config_json.backtest_config.benchmark` > 默认 `000300.SH`。

#### Scenario: 字段默认值
- **GIVEN** 新建策略配置未指定 benchmark
- **WHEN** 保存
- **THEN** `backtest_config.benchmark` 默认 `"000300.SH"`

#### Scenario: Schema 文档同步
- **GIVEN** [统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) §3.4
- **WHEN** 文档更新
- **THEN** 表格新增 benchmark 行（字段名/类型/默认值/说明）

### Requirement: 004 BacktestConfigModel

engine 的 `BacktestConfigModel` SHALL 新增 `benchmark: str = "000300.SH"` 字段（Pydantic 2.x），与 Schema 对齐。

#### Scenario: Pydantic 模型字段
- **GIVEN** `BacktestConfigModel`
- **WHEN** 实例化未传 benchmark
- **THEN** `model.benchmark == "000300.SH"`

---

## REMOVED Requirements

### Requirement: GRID 网格寻优第一波实现
**Reason**：第一波聚焦 SINGLE + signals + bracket。GRID 需 paramGrid 形参 schema 反推（OQ-3 未决），且 constraint/resultFilter 须改结构化 DSL（不用 eval），统一在第二波。
**Migration**：第一波 `mode=GRID` 在 watcher 请求层返回 `BACKTEST_MODE_NOT_SUPPORTED`，引导用户等第二波。第二波独立 PRD 启动前需先解决 OQ-3 + 共享层抽取 + 结构化 DSL 设计。

### Requirement: WALK_FORWARD 滚动验证第一波实现
**Reason**：高阶场景（10%），需先有 GRID。
**Migration**：第一波 `mode=WALK_FORWARD` 返回 `BACKTEST_MODE_NOT_SUPPORTED`。

### Requirement: rebalance 多因子调仓驱动第一波实现
**Reason**：需先抽取共享层（ConditionEngine/factor_calculator → `services/shared/`），避免重写选股内核。
**Migration**：config 含 `trading_config.rebalance` 在 watcher + engine 双层返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`。

### Requirement: exit.rules 动态出场条件树第一波实现
**Reason**：复杂度高（支持 ref + trailing stop + 多级止损），与 rebalance 同期进第二波。
**Migration**：config 含 `exit.rules` 在 watcher + engine 双层返回 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`。

### Requirement: HTML 报告导出第一波实现
**Reason**：落盘策略与 engine 硬约束（不触库）的边界待定。
**Migration**：第一波仅支持 CSV 导出（前端生成）。

### Requirement: RUNNING 任务真终止第一波实现
**Reason**：第一波无法真正终止 engine 进程，避免给用户「点了能停」的假期望。
**Migration**：前端对 RUNNING 任务隐藏取消按钮，仅 PENDING 可取消。第二波需 engine 协作实现真终止。

### Requirement: DRAFT 版本回测第一波支持
**Reason**：OQ-4 未决。第一波仅 VERIFIED/ACTIVE 可回测，防注入且可追溯。
**Migration**：第一波指向 DRAFT/ARCHIVED 版本返回 `BACKTEST_STRATEGY_VERSION_INVALID`。
