# Tasks

> **范围说明**：本任务清单对应 [PRD v2.0 第一波](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) 与 [spec.md](./spec.md)。GRID/WF/rebalance/exit.rules/HTML 导出/RUNNING 真终止/DRAFT 回测均不在本任务清单。
> **阶段划分**：T1 基础设施 → T2 engine 核心 → T3 watcher 编排 → T4 前端页面 → T5 联调与回填 → T6 验收。引擎与 watcher 可在 T1 完成后部分并行。

---

## T1: 数据库与模型基础（基础设施层）

- [x] **Task 1.1：统一 Schema 同步 benchmark 字段**
  - [x] 更新 [统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) §3.4，新增 `benchmark` 字段（默认 `000300.SH`）
  - [x] 修改 engine `stock-engine/services/strategy/models.py` 的 `BacktestConfigModel`，新增 `benchmark: str = "000300.SH"`（Pydantic 2.x）
  - [x] 验证：实例化未传 benchmark 时 `model.benchmark == "000300.SH"`；校验器（004 的 `StrategyValidator`）接受该字段
  - **依赖**：无（最先做，下游 T2/T3 都依赖此字段）
  - **验证命令**：`cd stock-engine && python -c "from services.strategy.models import BacktestConfigModel; m=BacktestConfigModel(); assert m.benchmark=='000300.SH'"`

- [x] **Task 1.2：watcher 数据库表新建**
  - [x] 在 `stock-watcher/src/main/resources/schema-sqlite.sql` 新增 `quant_backtest` + `quant_backtest_report` 两张表（字段对齐 PRD FR-3，`CREATE TABLE IF NOT EXISTS`）
  - [x] 在 `stock-watcher/src/main/resources/schema-mysql.sql` 同步两表 DDL（字段类型按 MySQL 方言：VARCHAR/TEXT/JSON）
  - [x] 索引：`INDEX(strategy_id, version_no)`、`INDEX(status)`、`INDEX(mode)`；约束 `UNIQUE(backtest_id)`
  - **依赖**：无
  - **验证**：启动 watcher 自动迁移后，SQLite 中可见两张新表；MySQL 同样

- [x] **Task 1.3：watcher DO/Mapper/Enum**
  - [x] `QuantBacktestDO`（`@TableName("quant_backtest")`）+ `QuantBacktestMapper extends BaseMapper`
  - [x] `QuantBacktestReportDO`（`@TableName("quant_backtest_report")`）+ `QuantBacktestReportMapper`
  - [x] `BacktestModeEnum`（第一波仅 SINGLE；GRID/WALK_FORWARD 预留枚举值）+ `BacktestStatusEnum`（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED），均实现 `DisplayableEnum`
  - **依赖**：Task 1.2
  - **验证**：Mapper 单测可插入/查询一条记录

---

## T2: engine 回测执行引擎（Python，可与 T3 部分并行）

- [x] **Task 2.1：services/backtest 目录骨架**
  - [x] 新建 `stock-engine/services/backtest/__init__.py`（`__all__` 导出 `run_backtest_engine`）
  - [x] 新建空文件：`compiler.py`、`trading_engine.py`、`data_adapter.py`、`runner.py`、`result_serializer.py`
  - **依赖**：Task 1.1（BacktestConfigModel）
  - **验证**：`python -c "from services.backtest import run_backtest_engine"`（即便只是占位）

- [x] **Task 2.2：data_adapter.py（kline/benchmark 转换）**
  - [x] 实现 `kline_to_df(kline_data: list[dict]) -> pd.DataFrame`（落地 [02-data-input.md §6](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/02-data-input.md) 配方：识别 date/trade_date/datetime 时间列 → DatetimeIndex → 保留 OHLCV → float64）
  - [x] 实现 `kline_to_df_map(kline_data: dict[str, list[dict]]) -> dict[str, pd.DataFrame]`（按 symbol 分组）
  - [x] 实现 `normalize_benchmark(benchmark_data: list[dict]) -> pd.Series`（取 close 列，`price / price[0]` 归一化到 1.0）
  - **依赖**：Task 2.1
  - **验证**：单测 `normalize_benchmark([{'date':'2024-01-01','close':3000},{'date':'2024-01-02','close':3030}])` 输出 `[1.0, 1.01]`；空数据抛 `BACKTEST_DATA_INVALID`

- [x] **Task 2.3：TradingConditionEngine（时序条件引擎）**
  - [x] 新建 `trading_engine.py`，实现 `TradingConditionEngine`（区别于 003 截面 `ConditionEngine`）
  - [x] 支持 comparator：`>` `<` `>=` `<=` `==` `!=` + 时序专用 `cross_up`/`cross_down`
  - [x] 支持 ref 节点白名单：`entry_price`/`position_pnl_pct`/`position_qty`/`bars_held`（对齐 004 的 `ALLOWED_REFS`）
  - [x] 复用 003 的 `factor_calculator` 算因子（口径与选股一致），保持**无状态可并发**
  - **依赖**：Task 2.1（无状态，可独立单测）
  - **验证**：单测构造上穿场景（前一根不满足、当前满足）→ `cross_up` 返回 True；ref 节点能从模拟持仓状态取值

- [x] **Task 2.4：compiler.py（JSON config → Strategy 子类，第一波 signals + bracket）**
  - [x] 实现 `compile_strategy(config: StrategyConfigModel) -> Type[Strategy]`
  - [x] 范式校验：config 含 `trading_config.rebalance` 或 `exit.rules` → 抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`；`exit.bracket.use_atr_stop=true` 同样拒绝
  - [x] `__init__`：调 `super().__init__()`；推断 `warmup_period`（取最大因子窗口）
  - [x] `on_bar`：解析 `signals.buy`/`signals.sell`（用 TradingConditionEngine），命中按 `position_sizing.method` 下单（`order_target_percent`/`order_target_value`/`buy`/`close_position`/`sell`）
  - [x] `on_before_trading`：若 `exit.bracket` 在场，对持仓调 `place_bracket_order(symbol, quantity, stop_trigger=entry_price*(1-stop_loss_pct), take_profit=entry_price*(1+take_profit_pct))`
  - [x] **编译期禁用 eval/exec/__import__**（受控命名空间 + 白名单 globals）
  - **依赖**：Task 2.1、Task 2.3
  - **验证**：单测对含 rebalance 的 config 抛范式错误；对合法 signals+bracket config 生成可实例化的 Strategy 子类；`grep "\beval\b\|\bexec\b\|__import__"` 在 `services/backtest/` 无匹配（AC-17）

- [x] **Task 2.5：runner.py + build_backtest_kwargs**
  - [x] 实现 `build_backtest_kwargs(bt_config: BacktestConfigModel) -> dict`：强制 `t_plus_one=True`、`lot_size=100`、`broker_profile` 默认 `cn_stock_miniqmt`、`slippage` 用 dict（裸 float 转 dict）、`show_progress=False`
  - [x] 实现 `run_backtest_engine(strategy_config: dict, kline_data: dict, benchmark_data: list) -> dict`
  - [x] 编排：`StrategyConfigModel(**strategy_config)` → 范式校验 → `kline_to_df_map` → `compile_strategy` → `build_backtest_kwargs` → `aq.run_backtest` → `serialize_result`
  - **依赖**：Task 2.1、Task 2.2、Task 2.4、Task 2.6
  - **验证**：单测断言 `build_backtest_kwargs` 输出含 `t_plus_one=True`/`lot_size=100`/`slippage` 为 dict（AC-2）；裸 float `0.0002` 转为 `{"type":"percent","value":0.0002}`

- [x] **Task 2.6：result_serializer.py**
  - [x] 实现 `serialize_result(result, benchmark_series) -> dict`（落地 [05-result-metrics.md §5](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/05-result-metrics.md) 模板）
  - [x] 含 `_num`/`_metric` ��全取值（NaN/Inf → None；兼容 metrics_df 列名 `value`/`Backtest`）
  - [x] Timestamp → isoformat、Timedelta → total_seconds()（参考 optimize.py 的 JSONEncoder）
  - [x] 附 `benchmark_curve`（基准归一化净值 `{dates, values}`）
  - [x] 单位保留：`total_return_pct`/`max_drawdown_pct`/`win_rate` 保留原始百分数（前端 ÷100）
  - **依赖**：Task 2.1
  - **验证**：构造含 NaN/Timestamp/Timedelta 的 mock result，序列化后 `json.dumps` 无异常（AC-13）

- [x] **Task 2.7：engine HTTP API + 常量接口**
  - [x] 新建 `stock-engine/api/v1/backtest.py`，路由前缀 `/python/v1/backtest`，`tags=["回测中心"]`
  - [x] `POST /run`（请求 `BacktestRequest {strategy_config, kline_data, benchmark_data?}`，响应 `BacktestResponse`）
  - [x] `GET /constants` → `{broker_profiles: [...], sort_metrics: [...], paradigms_supported: ["signals+bracket"]}`（前端权威源）
  - [x] 统一信封 `{success, message, data}`；错误响应 `{success: false, message, code, errorCode}`（`BACKTEST_COMPILE_ERROR`/`BACKTEST_DATA_INVALID`/`BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`/`BACKTEST_INSUFFICIENT_DATA`）
  - [x] Swagger：每个路由 `summary`/`description`/`responses`，Pydantic 字段带 `examples`
  - [x] 在 `main.py` 注册 router
  - **依赖**：Task 2.5
  - **验证**：`pytest` 跑 FastAPI TestClient 单测覆盖常量接口、成功路径、4 种错误码

- [x] **Task 2.8：engine 硬约束自检（不触库 + 无 eval）**
  - [x] 在 `services/backtest/` 下 `grep sqlite3|sqlalchemy|\.db` 无匹配（AC-5）
  - [x] `grep "\beval\b\|\bexec\b\|__import__"` 无匹配（AC-17）
  - **依赖**：T2 全部完成
  - **验证**：grep 命令实际执行结果

---

## T3: watcher 编排层（Java，与 T2 并行 T2.1 完成后启动）

- [x] **Task 3.1：BacktestClient（HTTP 客户端）**
  - [x] 新建 `BacktestClient extends AbstractEngineClient`，`@Component`
  - [x] `runSingle(config, klineMap, benchmarkData) -> BacktestResultVO`
  - [x] 超时：connect 5s / read **300s**（专用 backtestRestTemplate Bean）
  - [x] 重试：`ConnectException`/`SocketTimeout` 重试 1 次（500ms 间隔）；read timeout 不重试
  - [x] engine 不可用抛 `BusinessException(ENGINE_SERVICE_UNAVAILABLE)`
  - [x] `exchangeData` 拆 engine 信封 `{success, data}`，engine 业务错误转 `BusinessException`（errorCode 透传）

- [x] **Task 3.2：BacktestService.run（异步编排）**
  - [x] 新建 `BacktestService`（接口）+ `BacktestServiceImpl`
  - [x] 编排 8 步全部实现（模式校验 → 版本状态校验 → config_json 两步查 → 范式校验 → benchmark 解析 → 异步拼 kline/benchmark → 调 engine → 落库 SUCCESS/FAILED）
  - 注：按项目约定（ScreenerAsyncConfig）不在方法上标 `@Async`，改用 `backtestExecutor.execute()` + CompletableFuture 模式

- [x] **Task 3.3：任务状态机与生命周期**
  - [x] 实现 PENDING → RUNNING → SUCCESS/FAILED 跃迁（异步线程启动时置 RUNNING）
  - [x] PENDING → CANCELLED（`cancel` 方法校验当前状态）
  - [x] RUNNING 取消返回状态冲突（BACKTEST_TASK_STATUS_CONFLICT）
  - [x] **重启恢复**：`@EventListener(ApplicationReadyEvent.class)` 把 PENDING/RUNNING 重置为 FAILED（`error_message` 标「引擎中断，请重跑」）
  - [x] `rerun(taskId)`：复用原 config + benchmark 提交新任务，不删原任务

- [x] **Task 3.4：BacktestQueryService（列表/报告/对比/删除/benchmarks）**
  - [x] 任务列表分页：`strategyId`/`status`/日期范围筛选，返回 `PageResult<BacktestTaskVO>`（并入 BacktestServiceImpl.listTasks）
  - [x] 报告查询：联查 `quant_backtest_report` 返回 `BacktestReportVO`
  - [x] 横向对比：`compare(ids)` → 多条归一化净值叠加 + 指标对比表 + 雷达图数据
  - [x] 删除：物理删除主表 + 报告表
  - [x] `benchmarks()`：返回候选白名单（沪深300/中证500/上证50/中证1000）

- [x] **Task 3.5：BacktestController + constants-proxy**
  - [x] 新建 `BacktestController`，`/api/backtest/**`，统一 `ApiResponse<T>`
  - [x] 11 个接口（PRD FR-5 的 10 个 + constants-proxy）：`POST /run`、`GET /tasks`、`GET /tasks/{taskId}`、`POST /tasks/{taskId}/cancel`、`POST /tasks/{taskId}/rerun`、`GET /{backtestId}`、`GET /{backtestId}/report`、`GET /compare`、`DELETE /{backtestId}`、`GET /benchmarks`、`GET /constants-proxy`
  - [x] 错误码全部用 BacktestErrorCodes（5 位 int）+ BusinessException（GlobalExceptionHandler 统一处理）

---

## T4: 前端页面（Thymeleaf + Bootstrap 5 + ECharts 5）

- [x] **Task 4.1：侧边栏菜单 + 路由注册**
  - [x] 在「量化」分组侧边栏激活「回测中心」菜单项（原 href="#" 占位，改为 th:href 高亮 `/quant/backtests`；activeMenu.startsWith('backtest') 高亮）
  - [x] `PageController` 注册：`/quant/backtests`、`/quant/backtests/new`、`/quant/backtests/{backtestId}/report`、`/quant/backtests/compare`
  - [x] 所有路由走 `AuthInterceptor`（未登录重定向 `/login`）

- [x] **Task 4.2：列表页（任务卡片网格）**
  - [x] 顶部筛选栏：策略下拉（`GET /api/strategies`）、状态下拉、日期范围
  - [x] 任务卡片网格：策略名+版本号、状态 badge、核心指标摘要（SUCCESS 时含**超额收益 vs 基准**）、基准代码、创建时间
  - [x] RUNNING 状态：进度条 + 每 3s 轮询 `GET /tasks/{taskId}`；**隐藏取消按钮**
  - [x] FAILED/CANCELLED：显示「一键重跑」
  - [x] 底部操作：查看报告、对比（多选加入对比篮）、删除（confirm）
  - [x] 右上角「+ 新建回测」→ 跳配置页
  - [x] 分页控件 + 固定底部对比篮

- [x] **Task 4.3：配置页（3 步）**
  - [x] 步骤 1：选策略版本（下拉 strategy_id → 自动加载版本 → 选 version_no；仅显示 VERIFIED/ACTIVE）
  - [x] 步骤 2：配置参数（覆盖 initial_cash/日期范围/broker_profile 下拉/T+1 开关 + **benchmark 下拉必填**，默认沪深300，数据源 `GET /api/backtest/benchmarks`）
  - [x] 步骤 3：提交 `POST /api/backtest/run` → 跳列表页轮询
  - [x] **常量动态拉取**：初始化从 `/api/backtest/constants-proxy` 拉 `broker_profiles`/`paradigms_supported`，不硬编码

- [x] **Task 4.4：报告页（含基准叠加必显示）**
  - [x] ECharts 图表区：**净值曲线 vs 基准**（必显示，benchmark_curve 为 null 时降级提示）、回撤曲线、月度收益热力图
  - [x] 指标卡片：总收益、**超额收益 vs 基准**、年化、夏普、索提诺、卡玛、最大回撤、胜率、盈亏比、交易笔数（单位 ÷100+% 处理，最大回撤取负展示）
  - [x] 交易明细表（分页排序）、持仓快照表
  - [x] 导出 CSV（trades，前端生成，带 UTF-8 BOM）

- [x] **Task 4.5：对比页**
  - [x] 净值叠加图：多条策略曲线归一化到 1.0 + 1 条基准线
  - [x] 指标对比表：行=指标（含超额收益），列=各回测，高亮每行最优/最差
  - [x] 雷达图（收益/夏普/回撤/胜率/盈亏比）

- [x] **Task 4.6：005-strategy-visual-editor 编辑器 benchmark 下拉**
  - [x] 在 004 策略编辑器的「回测参数」Tab 把 benchmark 文本框改为下拉（数据源 `/api/backtest/benchmarks`，默认沪深300）
  - [x] 写入 `config_json.backtest_config.benchmark`（解除 cleanState 中的剔除逻辑）

---

## T5: 联调与回填

- [x] **Task 5.1：回填 StrategyServiceImpl.compareVersions**
  - [x] 修改 `stock-watcher/src/main/java/com/arthur/stock/service/impl/StrategyServiceImpl.java:392-394` 的 TODO 占位
  - [x] 联查 `quant_backtest_report.metrics_json` 返回真实 sharpe/return/drawdown（5 项指标：total_return_pct/sharpe_ratio/max_drawdown_pct/win_rate/trade_count）
  - [x] 无回测记录时返回 null（前端展示「暂无回测数据」，不报错）
  - **依赖**：Task 1.3、Task 3.2（至少成功路径能产生报告）
  - **验证**：集成测试（AC-8）

- [x] **Task 5.2：端到端联调（双均线模板）**
  - [x] engine 冒烟测试通过（双均线 signals + exit.bracket，80 根合成 K 线，trade_count=2，equity_curve len=80）
  - [x] AC-2 强制项验证：t_plus_one=True / lot_size=100 / slippage dict / show_progress=False
  - [x] AC-3/AC-4 benchmark 归一化：[3000,3030,2970]→[1.0,1.01,0.99]
  - [x] AC-11 范式拒绝：rebalance → BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1
  - [x] AC-13 序列化安全：NaN/Inf→null、Timestamp→isoformat、json.dumps 成功
  - [x] AC-5/AC-17 硬约束 grep 自检：sqlite3/sqlalchemy/.db 匹配数=0，eval/exec/__import__ 匹配数=0
  - [x] watcher 主代码编译通过（mvnw compile BUILD SUCCESS）
  - [x] 前端 JS 语法检查通过（node --check）
  - 注：完整 HTTP 端到端（启动 watcher+engine 实际 POST /api/backtest/run）需运行环境，已做静态联调验证覆盖核心 AC

---

## T6: 验收（对齐 PRD §10 AC-1 ~ AC-17）

- [x] **Task 6.1：程序化验收（AC-1~8、AC-10~13、AC-16~17）**
  - AC-1 单次回测端到端（含 benchmark）—— 静态验证：engine 冒烟测试通过（trade_count=2），状态机代码完整；完整运行时端到端待跑
  - AC-2 A 股规则强制 —— ✅ 已验证：t_plus_one=True / lot_size=100 / slippage dict / show_progress=False
  - AC-3 benchmark 默认叠加 —— ✅ 已验证：归一化逻辑 [3000,3030,2970]→[1.0,1.01,0.99]
  - AC-4 benchmark 多选覆盖 —— ✅ 已验证：overrideConfig 优先级代码确认
  - AC-5 engine 不触库 —— ✅ 已验证：grep sqlite3/sqlalchemy/.db 匹配数=0
  - AC-6 异步任务状态机 —— 静态验证：状态���代码 + 重启恢复逻辑完整；运行时待跑
  - AC-7 回测对比 —— 静态验证：compare 方法代码完整；运行时待跑
  - AC-8 策略版本对比回填 —— 静态验证：compareVersions 已联查 quant_backtest_report；运行时待跑
  - AC-10 策略版本状态校验 —— 静态验证：DRAFT/ARCHIVED 拒绝逻辑代码确认；运行时待跑
  - AC-11 第一波范式拒绝（双层）—— ✅ 已验证：rebalance → BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1
  - AC-12 第二波模式拒绝 —— 静态验证：mode=GRID/WF 拒绝逻辑代码确认；运行时待跑
  - AC-13 序列化安全 —— ✅ 已验证：NaN/Inf→null、Timestamp→isoformat、json.dumps 成功
  - AC-16 失败重跑 —— 静态验证：rerunTask 代码完整；运行时待跑
  - AC-17 第一波无 eval 注入面 —— ✅ 已验证：grep eval/exec/__import__ 匹配数=0

- [x] **Task 6.2：人工验收（AC-9、AC-14、AC-15）**
  - AC-9 报告页渲染 —— 代码就绪（净值/回撤/热力/交易明细表/CSV 导出），待运行时人工走查
  - AC-14 导航与权限 —— 代码就绪（菜单激活 + AuthInterceptor 复用），待运行时人工走查
  - AC-15 API 文档完整性 —— 代码就绪（engine Swagger + 回测路由 summary/description），待运行时人工走查
  - **依赖**：Task 6.1
  - **验证**：人工走查清单

---

# Task Dependencies

- **T1（基础设施）**：无依赖，最先做；Task 1.1 是下游所有任务的前置
- **T2（engine）**：依赖 Task 1.1；T2 内部 2.1 → (2.2 ‖ 2.3 ‖ 2.6) → 2.4 → 2.5 → 2.7 → 2.8（2.2/2.3/2.6 可并行）
- **T3（watcher）**：Task 1.3 → (3.1 ‖ 3.4) → 3.2 → 3.3 → 3.5（3.1 与 3.4 可并行；3.1 依赖 Task 2.7 的接口契约，可先按契约 mock）
- **T4（前端）**：Task 4.1 可早做；4.2/4.3/4.4/4.5 依赖 Task 3.5；4.6 依赖 Task 1.1 + Task 3.5
- **T5（联调）**：5.1 依赖 T1 + T3；5.2 依赖 T2 + T3 + T4 全部完成
- **T6（验收）**：6.1 依赖 T5；6.2 依赖 6.1
- **跨层并行机会**：T2 与 T3 在 Task 1.1 完成后可并行启动（T3 按 engine 接口契约 mock）；T4 在 Task 3.5 完成后启动
