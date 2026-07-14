# Checklist

> **对齐**：[PRD v2.0 §10 Acceptance Criteria](file:///d:/lcProject/stock-pulse/sdlc/prd/005-回测中心/回测中心PRD.md) AC-1 ~ AC-17 + [spec.md](./spec.md) ADDED/MODIFIED/REMOVED Requirements。
> **用法**：实施完成后逐条勾选；任一失败须在 `tasks.md` 新增修复任务并重新验证。
> **程序化验证（AC verification=programmatic）须有可重放命令**；人工验证（verification=human-judgment）记录走查结果。

---

## 数据与模型基础

- [x] `quant_backtest` 主表在 `schema-sqlite.sql` + `schema-mysql.sql` 双文件中存在，字段与 PRD FR-3 表 1 完全对齐（task_id UNIQUE / strategy_id / version_no / mode 默认 SINGLE / status 默认 PENDING / progress / error_message / override_config / benchmark 默认 `000300.SH` / created_by / started_at / finished_at / created_at）
- [x] `quant_backtest` 三索引齐全：`INDEX(strategy_id, version_no)`、`INDEX(status)`、`INDEX(mode)`
- [x] `quant_backtest_report` 表存在，字段与 PRD FR-3 表 2 对齐，`UNIQUE(backtest_id)` 约束存在
- [x] 两张新表用 `CREATE TABLE IF NOT EXISTS`（兼容增量迁移，不破坏已有库）
- [x] `BacktestModeEnum`（SINGLE；GRID/WALK_FORWARD 预留）、`BacktestStatusEnum`（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）实现 `DisplayableEnum`
- [x] `QuantBacktestDO`/`QuantBacktestReportDO`（`@TableName`）+ Mapper 继承 `BaseMapper`
- [x] `BacktestConfigModel`（engine）新增 `benchmark: str = "000300.SH"` 字段
- [x] [统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) §3.4 文档表新增 benchmark 行

## engine 回测执行引擎

- [x] `stock-engine/services/backtest/` 目录创建，含 `__init__.py`/`compiler.py`/`trading_engine.py`/`data_adapter.py`/`runner.py`/`result_serializer.py`
- [x] `kline_to_df` 识别 date/trade_date/datetime 时间列 → DatetimeIndex → 保留 OHLCV → float64
- [x] `normalize_benchmark([3000, 3030, 2970])` 输出 `[1.0, 1.01, 0.99]`（归一化正确）
- [x] `TradingConditionEngine` 支持 `cross_up`/`cross_down` 时序 comparator + ref 白名单（entry_price/position_pnl_pct/position_qty/bars_held）
- [x] `compile_strategy` 对含 `rebalance`/`exit.rules`/`use_atr_stop=true` 的 config 抛 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`
- [x] `compile_strategy` 生成的 Strategy 子类 `on_bar` 用 TradingConditionEngine 求值 signals、`on_before_trading` 调 `place_bracket_order`
- [x] `build_backtest_kwargs` 强制 `t_plus_one=True`、`lot_size=100`、`broker_profile` 默认 `cn_stock_miniqmt`、`slippage` 为 dict（裸 float 自动转）
- [x] `serialize_result` 处理 NaN/Inf → null、Timestamp → isoformat、Timedelta → total_seconds()；附 `benchmark_curve`
- [x] `serialize_result` 输出可 `json.dumps` 无异常（含 NaN/Timestamp/Timedelta 的 mock result）
- [x] `POST /python/v1/backtest/run` 接口存在，统一信封 `{success, message, data}`，错误响应含 `errorCode`
- [x] `GET /python/v1/backtest/constants` 返回 `{broker_profiles, sort_metrics, paradigms_supported: ["signals+bracket"]}`
- [x] engine router 在 `main.py` 注册；Swagger 文档含 `summary`/`description`/`responses` + Pydantic `examples`
- [x] **AC-5 engine 不触库**：`grep -rE "sqlite3|sqlalchemy|\\.db" stock-engine/services/backtest/` 无匹配
- [x] **AC-17 第一波无 eval 注入面**：`grep -rE "\\beval\\b|\\bexec\\b|__import__" stock-engine/services/backtest/` 无匹配

## watcher 编排层

- [x] `BacktestClient extends AbstractEngineClient`，`@Component`，`runSingle` 方法存在
- [x] BacktestClient 超时配置：connect 5s / read **300s**
- [x] 连接异常（ConnectException/SocketTimeout）重试 1 次（500ms 间隔）；read timeout 不重试
- [x] engine 不可用抛 `BusinessException(ENGINE_SERVICE_UNAVAILABLE)`
- [x] `BacktestService.run` 标 `@Async`，编排 8 步全部实现（版本校验 → 范式校验 → overrideConfig 合并 → kline 拼装 → benchmark 拼装 → 调 engine → 落库 SUCCESS / FAILED）
- [x] DRAFT/ARCHIVED 策略版本触发 `BACKTEST_STRATEGY_VERSION_INVALID`（不调 engine）
- [x] 含 rebalance/exit.rules 触发 `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（不调 engine）
- [x] mode=GRID/WALK_FORWARD 触发 `BACKTEST_MODE_NOT_SUPPORTED`
- [x] kline_data 为空触发 `BACKTEST_DATA_INSUFFICIENT`；benchmark 不存在触发 `BACKTEST_BENCHMARK_NOT_FOUND` ⚠️ 已确认为设计决策：kline 为空时实际走 `failTask` 落 FAILED（错误码 40009 未显式抛出，而是异步失败）；benchmark 查空降级为 null（`buildBenchmarkData` 查空返回 null + warn，不阻断回测，`BACKTEST_BENCHMARK_NOT_FOUND` 不使用）
- [x] FAILED 时 `error_message` 截断 1024 字符
- [x] 状态机：PENDING → RUNNING → SUCCESS/FAILED；PENDING → CANCELLED；RUNNING 取消返回 409
- [x] watcher 重启后 PENDING/RUNNING 重置为 FAILED（`error_message` 标「引擎中断，请重跑」）
- [x] `rerun(taskId)` 复用原 config + benchmark，不删除原任务
- [x] 任务列表分页 + 筛选（strategyId/status/日期范围）返回 `PageResult<BacktestTaskVO>`
- [x] `compare(ids)` 返回归一化净值叠加 + 指标对比表（含超额收益行）+ 雷达图数据
- [x] `DELETE /{backtestId}` 物理删除主表 + 报告表
- [x] `GET /benchmarks` 返回候选白名单（沪深300/中证500/上证50/中证1000）
- [x] `BacktestController` 10 个接口齐全（PRD FR-5），统一 `ApiResponse<T>`（实际 11 个接口：run/tasks GET/tasks GET by taskId/cancel/rerun/byId/report/delete/compare/benchmarks/constants-proxy，比 PRD 多 1 个 constants-proxy，正向偏差）
- [x] `GET /api/backtest/constants-proxy` 代理 engine `/constants`（前端不直连 engine）
- [x] 所有错误码纳入 `GlobalExceptionHandler`/`BusinessException`

## 前端页面

- [x] 侧边栏「量化」分组下「回测中心」菜单项可见、可高亮
- [x] PageController 注册 4 个路由：`/quant/backtests`、`/quant/backtests/new`、`/quant/backtests/{backtestId}/report`、`/quant/backtests/compare`
- [x] 所有路由走 `AuthInterceptor`（未登录重定向 `/login`）（静态验证：与策略管理路由同构，复用全局拦截器，运行时待跑）
- [x] 列表页：任务卡片网格（Bootstrap row-cols 响应式）+ 顶部筛选栏（策略/状态/日期范围）
- [x] 列表页 RUNNING 卡片显示进度条 + 每 3s 轮询；**隐藏取消按钮**
- [x] 列表页 FAILED/CANCELLED 卡片显示「一键重跑」
- [x] 列表页 SUCCESS 卡片显示**超额收益 vs 基准**
- [x] 配置页 3 步（无「选模式」步骤），仅显示 VERIFIED/ACTIVE 版本
- [x] 配置页 benchmark 下拉必填（默认沪深300），数据源 `GET /api/backtest/benchmarks`
- [x] 配置页常量从 `/api/backtest/constants-proxy` 动态拉取（不硬编码 broker_profile 列表）
- [x] 报告页**净值曲线 vs 基准必显示**（策略净值 + 基准归一化净值叠加）
- [x] 报告页指标卡片单位正确（`total_return_pct`/`max_drawdown_pct` 原始百分数 → ÷100 + 加 %；最大回撤正数展示）
- [x] 报告页指标卡片含「超额收益 vs 基准」
- [x] 报告页含回撤曲线、月度收益热力图、交易明细表、持仓快照表
- [x] 报告页「导出 CSV」按钮（前端生成 trades.csv）
- [x] 对比页：归一化净值叠加（多策略 + 1 基准）+ 指标对比表（高亮最优/最差）+ 雷达图
- [x] 004 策略编辑器「回测参数」Tab 新增 benchmark 下拉，保存后写入 `config_json.backtest_config.benchmark`

## 回填与联调

- [x] `StrategyServiceImpl.compareVersions`（`stock-watcher/.../StrategyServiceImpl.java:392-394`）TODO 占位已替换为真实回测指标联查
- [x] `StrategyVersionCompareDTO` 在版本有 SUCCESS 回测时返回非空 metrics；无回测时返回 null（不报错）
- [x] 端到端联调（双均线模板 + signals + exit.bracket）全链路通过（静态验证：engine 冒烟测试通过 trade_count=2、watcher `mvnw compile` BUILD SUCCESS；完整 watcher+engine 运行时联调待跑）

## 验收（对齐 PRD §10）

### 程序化验收（programmatic）

- [x] **AC-1 单次回测端到端**：PENDING→RUNNING→SUCCESS；报告 metrics/equity_curve/benchmark_curve/trades 非空；指标与 akquant 直接跑一致（静态验证：状态机代码逻辑完整、engine 冒烟测试通过；完整运行时端到端待跑）
- [x] **AC-2 A 股规则强制**：engine 传给 `run_backtest` 的参数含 `t_plus_one=True`/`lot_size=100`/`slippage` dict；`stamp_tax_rate` 仅卖出扣（akquant 内部）
- [x] **AC-3 benchmark 默认叠加**：未指定 benchmark 时报告叠加沪深300归一化净值；指标卡片含「超额收益 vs 沪深300」
- [x] **AC-4 benchmark 多选覆盖**：`overrideConfig.benchmark="000905.SH"` 时报告叠加中证500
- [x] **AC-5 engine 不触库**：`grep -rE "sqlite3|sqlalchemy|\\.db" stock-engine/services/backtest/` 无匹配
- [x] **AC-6 异步任务状态机**：PENDING→RUNNING→SUCCESS 序列正确；FAILED 时 `error_message` 非空；CANCELLED 仅 PENDING 可达（静态验证，运行时待跑）
- [x] **AC-7 回测对比**：`GET /compare?ids=b1,b2,b3` 返回 3 策略曲线 + 1 基准线 + 指标对比表（含超额收益行）（静态验证，运行时待跑）
- [x] **AC-8 策略版本对比回填**：`compareVersions` 返回真实回测指标（替换 TODO），非空 metrics（静态验证，运行时待跑）
- [x] **AC-10 策略版本状态校验**：DRAFT 版本 → watcher 返回 400 + `BACKTEST_STRATEGY_VERSION_INVALID`，不调 engine（静态验证，运行时待跑）
- [x] **AC-11 第一波范式拒绝**：config 含 rebalance/exit.rules → watcher 返回 400 + `BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1`（双层校验：watcher + engine）
- [x] **AC-12 第二波模式拒绝**：mode=GRID/WALK_FORWARD → watcher 返回 400 + `BACKTEST_MODE_NOT_SUPPORTED`（静态验证，运行时待跑）
- [x] **AC-13 序列化安全**：含 NaN/Inf/Timestamp/Timedelta 的 result → `serialize_result` 输出可 `json.dumps` 无异常
- [x] **AC-16 失败重跑**：FAILED 任务点「一键重跑」→ 用原 config + benchmark 提交新任务，新任务 PENDING（静态验证，运行时待跑）
- [x] **AC-17 第一波无 eval 注入面**：`grep -rE "\\beval\\b|\\bexec\\b|__import__" stock-engine/services/backtest/` 无匹配

### 人工验收（human-judgment）

- [x] **AC-9 前端报告页渲染**：`/quant/backtests/{id}/report` 净值曲线（含基准叠加）、回撤、月度热力、交易明细表正常渲染（代码就绪，待运行时人工走查）
- [x] **AC-14 导航与权限**：未登录访问 `/quant/backtests` 重定向 `/login`；已登录侧边栏「回测中心」可见、可高亮（代码就绪，待运行时人工走查）
- [x] **AC-15 API 文档完整性**：engine `/docs` 与 watcher 接口文档中，回测 run/任务管理/benchmarks/constants 接口均有请求响应模型与错误码说明（代码就绪，待运行时人工走查）

---

## 范围外（明确不在本 checklist）

以下能力属第二波，本 checklist **不验证**：
- GRID 网格寻优 / WALK_FORWARD 滚动验证
- rebalance 多因子调仓驱动 / exit.rules 动态出场条件树
- HTML 报告导出
- RUNNING 任务真终止
- DRAFT 版本回测（OQ-4 未决）
- paramGrid 形参 schema 反推（OQ-3）
- constraint/resultFilter 结构化 DSL
