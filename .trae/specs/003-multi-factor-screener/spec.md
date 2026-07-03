# 多因子选股中心 - Product Requirement Document

## Overview
- **Summary**: 在 StockPulse 双系统架构下落地「多因子选股中心」，覆盖快照选股（截面筛选）与区间选股（首日命中追踪）两种模式。共用层（因子库、条件表达式引擎、因子计算服务）由 stock-engine（Python）统一实现，watcher（Java）侧负责股票池/基本面数据提供、调用编排、结果存储与追踪。条件表达式引擎仅 Python 实现一遍，watcher 选股经 HTTP 调用 engine，保证口径一致、避免重复建设。
- **Purpose**: 打通「研究 → 选股 → 策略 → 回测 → 信号」决策链的关键一环。让用户能以可视化规则树在全市场（或子池）中按技术面+基本面+统计因子组合筛选股票、排序、追踪历史命中，并为策略回测/调仓提供程序化选股入口。
- **Target Users**: 量化研究用户（个人 A 股投资者）、前端选股中心页面、策略回测模块（调仓选股场景）、统一策略配置 Schema 的 `screen_config` 消费方。

## Goals
- 在 engine（Python）侧实现单一**条件表达式引擎（ConditionEngine）**，统一 Schema §4 的 ConditionTree / CompareLeaf / ExpressionNode / Comparator
- 提供快照选股 API：watcher 经 HTTP 把候选池数据 + 条件树传给 engine → engine 跑引擎 → 返回命中股票列表 + 因子值 + 排名
- 提供区间选股能力：对一段日期序列逐日跑快照选股，输出首日命中、命中天数、连续命中分布
- 支持排序/打分：单因子排序（asc/desc）与多因子加权综合排序（composite，负权重=越小越好）
- 支持静态过滤：ST/停牌/涨跌停/行业白/黑名单/上市天数
- 选股结果在 watcher 侧持久化（screen_result / screen_snapshot 表），可锁定结果追踪 N 日表现
- 严格遵守 engine 不触库硬约束：engine 只接受 HTTP 传入数据，不读写 SQLite，不回调 watcher
- 与统一 Schema 的 `screen_config` 一字段对齐，与 002 因子库共用同一份 `FactorCalculator`

## Non-Goals (Out of Scope)
- 策略回测（归 005 回测中心，本模块只输出股票列表供回测消费）
- 买卖信号生成 / 仓位管理 / 止损止盈（属 trading_config，归策略/回测模块）
- 因子的实时计算算法实现（归 002 因子库，本模块只调用 `FactorCalculator.compute`）
- 前端选股中心页面的 UI 实现（由 watcher 前端单独设计落 Thymeleaf）
- 基本面数据的采集（由 watcher 侧 Tushare 任务完成，本模块从 HTTP 输入消费）
- 因子效能分析（IC/IR/分层回测，归 002 因子库扩展，本模块不涉及）
- 调仓日动态选股在回测内的本地集成（归 005 回测中心，本模块只提供可复用的 ConditionEngine + 选股服务）
- 参数寻优 / Walk-forward（归 005 回测中心）

## Background & Context

### 项目架构与硬约束
StockPulse 采用 Java + Python 双系统：
- **stock-watcher（Java）**: 业务 + 数据中台，独占 SQLite 读写，提供股票池/基本面/行情数据，编排选股调用、存储选股结果、前端页面
- **stock-engine（Python）**: 计算服务，实现条件表达式引擎、因子计算、（未来）回测

**硬约束（见 CLAUDE.md）**：
- engine 禁止出现 `sqlite3` / `sqlalchemy` / 直连 `.db` 的代码
- 数据单源性：所有输入数据由 watcher 经 HTTP 传入，engine 只返回 JSON
- 交互单向：watcher → engine，engine 不回调 watcher

### 共用层与边界（见选股与回测边界设计）
选股与回测共用三层：① 因子定义（`标准因子库-v2.json` / `factors.json`）② 条件表达式 Schema（统一 Schema §4）③ 因子计算服务（002 因子库 `FactorCalculator`）。

| 共用层 | 谁实现 | 选股怎么用 | 回测怎么用 |
|---|---|---|---|
| 因子定义 | engine 持 JSON 真相源 | 选股条件引用 factorKey | 调仓信号引用 factorKey |
| 条件表达式引擎 | **engine（Python 唯一实现）** | watcher 经 HTTP 调用 | on_bar / on_daily_rebalance 本地调 |
| 因子计算服务 | engine（akquant.talib） | 选股批量预计算（截面） | 回测实时计算（时序） |

> 不维护 Java 版条件引擎——避免双实现对齐维护负担，符合 §1.1 设计目标。

### 选股的截面语义（与回测时序的关键区分）
- 选股是**截面操作**：某日全市场快照横向比较，无时序上下文
- 选股条件树 `screen_config.conditions` **禁止** `cross_up`/`cross_down`/`ref`（统一 Schema §7.2）——这些需要"上一根 bar"或持仓状态，在截面无定义
- 选股允许的比较器：`>` `<` `>=` `<=` `==` `!=`；允许的节点：`{value}` / `{factor}` / `{op}`
- 基本面因子（`PE_TTM`/`ROE_TTM`/`TOTAL_MV` 等）在选股截面场景合法；技术面因子（`MA`/`RSI`/`MACD`）需 watcher 预传该日所需的 OHLCV 历史窗口

### 相关设计文档
- [选股与回测边界设计](file:///d:/lcProject/stock-pulse/sdlc/prd/003-%E5%A4%9A%E5%9B%A0%E5%AD%90%E9%80%89%E8%82%A1%E4%B8%AD%E5%BF%83/%E9%80%89%E8%82%A1%E4%B8%8E%E5%9B%9E%E6%B5%8B%E8%BE%B9%E7%95%8C%E8%AE%BE%E8%AE%A1.md) - 职责边界与数据流（本 PRD 的直接上游）
- [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-%E7%AD%96%E7%95%A5%E7%AE%A1%E7%90%86/%E7%BB%9F%E4%B8%80%E7%AD%96%E7%95%A5%E9%85%8D%E7%BD%AESchema.md) - §3.2 screen_config、§4 条件树、§4.5 factorKey、§7.2 截面禁用项
- [标准因子库 v2](file:///d:/lcProject/stock-pulse/sdlc/prd/002-%E5%9B%A0%E5%AD%90%E5%BA%93/%E6%A0%87%E5%87%86%E5%9B%A0%E5%AD%90%E5%BA%93-v2.json) - 因子分类体系
- [002 因子库 spec](file:///d:/lcProject/stock-pulse/.trae/specs/002-standard-factor-library/spec.md) - 已实现的 FactorCalculator 契约
- [akquant talib 速查](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/07-talib-indicators.md) - 指标函数签名

### 选股结果追踪链路
选股结果可被"锁定"，watcher 侧记录每个命中标的在锁定后 5/10/20 交易日的表现，反向验证选股逻辑（属复盘域「选股结果追踪」）。本模块负责产出可锁定的结果数据，追踪收益计算归 watcher。

## Functional Requirements

### FR-1: 条件表达式引擎（ConditionEngine，engine 侧唯一实现）
- 实现 `ConditionEngine.evaluate(tree: ConditionTree, context: EvalContext) -> bool` 主入口
- 递归求值 ConditionTree（`operator: AND|OR` + `conditions: [...]`，无层级限制）
- 求值 CompareLeaf（`left comparator right`），left/right 均为 ExpressionNode
- 求值 4 种 ExpressionNode：
  - `{value}` 静态值（数字/字符串）
  - `{factor, params, inputs, output_index}` 因子引用 → 调 `FactorCalculator.compute`
  - `{op, left, right}` 算术（+−*/，除零安全降级为 0）
  - `{ref}` 状态引用（**仅 trading_config 合法；选股路径内出现即报错**）
- 支持 Comparator：`>` `<` `>=` `<=` `==` `!=`（通用）；`cross_up`/`cross_down`（**选股路径拒绝**，仅 trading 内合法）
- NaN 安全：任何含 NaN 的比较返回 False（不会因 NaN 误命中）
- 单元测试覆盖：基本运算、边界值、NaN 处理、AND/OR 嵌套、算术递归、多输出 output_index

### FR-2: 选股截面语义校验
- 引擎入口对 condition tree 做"截面合法性"校验：
  - 拒绝 `comparator: cross_up|cross_down`
  - 拒绝任何含 `{ref}` 的节点
- 命中违禁节点返回 422 + 错误码 `SCREEN_TIME_SERIES_FORBIDDEN`，附违禁节点路径

### FR-3: 因子值预计算（批量）
- 选股请求触达时，先按条件树收集所有被引用的 factorKey + params 去重集合
- 对每只候选股票一次性批量计算所需因子值（复用 002 `FactorCalculator`），避免重复求值
- 技术面因子：engine 内 akquant.talib 实时算（输入 OHLCV 由 watcher 传入）
- 基本面因子：watcher 传入快照字典 `{symbol: {PE_TTM: ..., ROE_TTM: ...}}`，engine 直接读

### FR-4: 快照选股 HTTP API（engine 侧）
- `POST /python/v1/screener/snapshot` - 单日截面选股
- 请求体（对齐 `screen_config`）：
  - `universe`: 候选池标识或 `manual`
  - `date`: 选股日期（YYYY-MM-DD）
  - `candidates`: 候选股票快照数据 `{symbol: {ohlcv_history: [...], fundamentals: {...}}}`（**由 watcher 预拼装传入**）
  - `conditions`: ConditionTree（可空=仅过滤）
  - `ranking`: Ranking 配置（§3.2.1）
  - `filters`: Filters 配置（§3.2.2）
  - `top_n`: 取前 N（可空=全部返回）
- 响应：
  - `date`, `total_count`, `stocks: [{symbol, rank, score, factor_values}]`
  - `excluded: {st: [...], suspended: [...], limit_up: [...], ...}` 过滤明细（可选 verbose 模式）

### FR-5: 区间选股 HTTP API（engine 侧）
- `POST /python/v1/screener/range` - 区间逐日选股
- 请求体：快照接口的扩展，`dates: [YYYY-MM-DD]` 数组 + 每日 candidates 映射
- 响应（每只股票的命中分布）：
  - `first_hit_date`: 首次命中日期
  - `hit_count`: 命中天数
  - `total_days`: 区间总交易日
  - `hit_ratio`: 命中率 = hit_count / total_days
  - `consecutive_max`: 最大连续命中天数
  - `daily_hits: [{date, hit, rank?}]` 时序明细

### FR-6: 排序与打分
- 单因子排序（`ranking.method="single"`）：按 factorKey + `order: asc|desc` 排序
- 多因子综合排序（`ranking.method="composite"`）：
  - `weights: {factorKey: 权重}`，负权重=因子值越小得分越高（如 `PE_TTM: -0.3`）
  - 各因子值做 z-score 标准化后按权重加权得综合分，按分降序
  - NaN 因子值在标准化时剔除该股票该维度（或赋中性分，默认剔除）
- 排序后取 `top_n` 截断；rank 从 1 起

### FR-7: 静态过滤（Filters）
- `exclude_st`（默认 true）：watcher 传入的 candidates 携带 `is_st` 标记，engine 据此过滤
- `exclude_suspended`（默认 true）：candidates 携带 `is_suspended`
- `exclude_limit_up`（默认 true）/ `exclude_limit_down`（默认 false）：candidates 携带 `is_limit_up`/`is_limit_down`
- `industries` 白名单 / `exclude_industries` 黑名单：candidates 携带 `industry`
- `min_list_days`：candidates 携带 `list_date`，engine 计算 list_days 过滤
- 过滤在条件求值**之前**执行（减少不必要的因子计算开销）

### FR-8: 选股方案管理（watcher 侧）
- watcher 侧维护选股方案表（screen_plan）：方案名、screen_config 快照、创建/更新时间
- `POST /api/screener/plans`（CRUD）+ `POST /api/screener/plans/{id}/run` 触发执行
- 每次执行在 watcher 侧落一条 screen_result 记录（方案 ID、执行时间、参数、命中股票 JSON）

### FR-9: 选股结果追踪（watcher 侧）
- 用户可"锁定"某次选股结果（生成 screen_lock 记录）
- watcher 定时任务（每日收盘后）计算锁定时刻持仓组合在 5/10/20 交易日后的收益
- 追踪明细页展示：组合累计收益、个股贡献、相对基准超额
- 追踪收益计算由 watcher 完成（不触 engine）

### FR-10: watcher → engine 调用编排
- watcher 选股服务（ScreenerService）负责：
  1. 解析 screen_config
  2. 从 SQLite 读候选池（universe 全市场/CSI300/CSI500/manual/自定义池）
  3. 拼装 candidates：每只股票的 OHLCV 历史（满足因子 lookback）+ 基本面快照 + 过滤标记
  4. 经 HTTP POST 调 engine `/python/v1/screener/snapshot` 或 `/range`
  5. 收到 engine 返回后落库（screen_result）、返回前端
- 编排在 watcher，计算在 engine（单向，engine 不回调 watcher）

## Non-Functional Requirements

### NFR-1: 性能
- 单日快照选股（5000 只候选 + 3 因子 + 排序 top_n=30）响应时间 < 3s（含 HTTP）
- 单日快照选股（500 只候选 CSI300 + 5 因子）响应时间 < 800ms
- 区间选股（250 交易日 × 500 只候选 + 3 因子）响应时间 < 30s
- 条件引擎单次 evaluate（不含因子计算）< 1ms
- 同一请求内因子批量预计算去重，避免重复求值

### NFR-2: 正确性
- 条件引擎求值结果与"先算因子值再逐项比较"的等价手算一致
- AND/OR 嵌套语义正确（短路求值可选）
- NaN 比较一律 False（不命中）
- 排序稳定：同分股票按 symbol 升序兜底
- 与 002 因子库的计算口径 100% 一致（共用 FactorCalculator）
- 截面禁用项校验零误报零漏报

### NFR-3: 可靠性
- 非法 condition tree 返回 422 + 结构化错误（success:false + code + message + 违禁路径）
- 缺失 factorKey 返回 400 + `UNKNOWN_FACTOR`
- 基本面字段缺失时按 NaN 处理（参与比较返回 False）
- watcher → engine HTTP 失败时 watcher 返回明确错误码，不写入脏结果
- engine 不依赖 watcher 的任何状态，纯函数式计算

### NFR-4: 并发与无状态
- engine 选股接口完全无状态，可水平扩展
- watcher 侧选股方案/结果读写用 SQLite WAL，写串行化
- 同一方案并发执行允许（每次生成独立 result 记录）

### NFR-5: 可维护性
- 条件引擎、因子计算、选股编排三层解耦
- 新增 Comparator / ExpressionNode 形态只需改引擎 + Schema 同步
- 代码符合 `.trae/rules/stock-engine/python/` 与 `.trae/rules/stock-watcher/java/` 规范
- API 文档（FastAPI /docs + watcher 接口文档）完整覆盖请求/响应/示例

### NFR-6: 兼容性
- 请求体字段命名与统一 Schema `screen_config` 一字段对齐
- factorKey 体系与 002 因子库 `factors.json` 完全一致
- 响应股票列表字段（symbol/rank/score/factor_values）与统一 Schema §4.6 选股结果示例一致

## Constraints

### 技术约束
- **engine 侧**: Python 3.12 + FastAPI + Pydantic 2.x；akquant 0.2.47（talib 子模块）；pandas/numpy
- **watcher 侧**: Java 21 + Spring Boot 4.0.6 + MyBatis-Plus + Caffeine + SQLite(WAL)
- **硬约束**: engine 禁止 `sqlite3`/`sqlalchemy`/直连 `.db`
- **交互单向**: watcher → engine，engine 不回调 watcher

### 业务约束
- 选股条件树禁止时序节点（cross_up/cross_down/ref）
- 技术面因子必须走 akquant.talib（不可自实现）
- 选股与回测共用同一套 ConditionEngine + FactorCalculator
- 候选池数据（OHLCV/基本面/过滤标记）由 watcher 拼装，engine 不触库

### 依赖约束
- 002 因子库（FactorCalculator + providers + registry）已实现或同步交付
- 统一策略配置 Schema §4 条件模型为唯一字段权威
- watcher 侧已具备 universe/股票池/基本面数据（依赖 Phase 0 数据中台）

## Assumptions
- watcher 传入的 OHLCV 数据已经过前复权、剔除停牌/涨跌停/ST 的清洗（engine 收到的是干净数据）
- 候选股票的 candidates 字段结构由 watcher 与 engine 协商固定（见 FR-4 请求体）
- 基本面快照由 watcher 从 `daily_basic` / `fina_indicator` 表读出，按选股日期对齐（取最近一期报表）
- 技术面因子所需的 OHLCV 历史窗口长度由 002 因子库的 `get_lookback` 决定，watcher 拼装时取窗口上限
- 选股方案并发量低（研究操作），watcher 侧无强一致需求
- 区间选股的日期序列已按交易日升序、剔除非交易日
- 选股结果追踪的 5/10/20 日收益计算以 watcher 的日线行情表为准

## Acceptance Criteria

### AC-1: 条件引擎基本求值
- **Given**: 一棵含 AND/OR 嵌套 + 比较 + 算术节点的条件树 + 准备好的因子值上下文
- **When**: 调用 `ConditionEngine.evaluate`
- **Then**: 返回 bool 结果与等价手算一致
- **Verification**: `programmatic`

### AC-2: NaN 安全
- **Given**: 条件树某节点因子值为 NaN
- **When**: 求值该条件
- **Then**: 比较结果为 False，不抛异常；该股票不入选
- **Verification**: `programmatic`

### AC-3: 截面禁用项拒绝
- **Given**: 选股请求的 condition tree 含 `cross_up` 或 `{ref}`
- **When**: 调用 `/python/v1/screener/snapshot`
- **Then**: 返回 422 + 错误码 `SCREEN_TIME_SERIES_FORBIDDEN`，附违禁节点路径
- **Verification**: `programmatic`

### AC-4: 快照选股端到端
- **Given**: 30 只候选股票的 OHLCV + 基本面快照 + 条件 `PE_TTM < 20 AND ROE_TTM > 15`
- **When**: 调用快照选股 API
- **Then**: 返回满足条件的股票列表 + 因子值 + 按 `TOTAL_MV` 升序的 rank
- **Verification**: `programmatic`

### AC-5: 多因子综合排序
- **Given**: composite ranking `weights: {ROE_TTM: 0.5, PE_TTM: -0.3, GROWTH: 0.2}` + 50 只候选
- **When**: 调用快照选股
- **Then**: 返回按 z-score 加权综合分降序排列，负权重（PE_TTM）表现为越小分越高
- **Verification**: `programmatic`

### AC-6: 静态过滤
- **Given**: candidates 含 ST/停牌/涨跌停/不同行业标记
- **When**: filters 全开（exclude_st=true 等）+ industries 白名单 `["银行"]`
- **Then**: ST/停牌/涨停股票被剔除，仅保留银行业股票；excluded 明细正确
- **Verification**: `programmatic`

### AC-7: top_n 截断
- **Given**: 100 只候选满足条件
- **When**: `top_n=30`
- **Then**: 返回 rank 1~30，total_count=100（满足总数），stocks 长度=30
- **Verification**: `programmatic`

### AC-8: 区间选股命中分布
- **Given**: 250 交易日 × 50 只候选 + 固定条件
- **When**: 调用 `/python/v1/screener/range`
- **Then**: 每只股票返回 first_hit_date / hit_count / hit_ratio / consecutive_max / daily_hits 时序明细
- **Verification**: `programmatic`

### AC-9: 因子批量预计算去重
- **Given**: 条件树多次引用同一 factorKey+params（如多处 `MA(timeperiod=5)`）
- **When**: 选股执行
- **Then**: 该因子只计算一次，结果被复用；性能符合 NFR-1
- **Verification**: `programmatic`

### AC-10: 缺失 factorKey
- **Given**: condition tree 引用 `UNKNOWN_FX`
- **When**: 调用选股
- **Then**: 返回 400 + `UNKNOWN_FACTOR`
- **Verification**: `programmatic`

### AC-11: watcher 编排不触库（engine 侧）
- **Given**: engine 代码库
- **When**: 搜索 `sqlite3`/`sqlalchemy`/`.db`
- **Then**: 选股模块不出现任何数据库连接代码
- **Verification**: `programmatic`

### AC-12: watcher 选股方案 CRUD
- **Given**: watcher 已启动 + 已认证
- **When**: 调用 `POST /api/screener/plans` 新建方案 → `GET /api/screener/plans` → `PUT` 修改 → `DELETE`
- **Then**: 各步返回正确状态码，方案持久化到 SQLite
- **Verification**: `programmatic`

### AC-13: 选股结果持久化
- **Given**: 已有方案
- **When**: 调用 `POST /api/screener/plans/{id}/run` 触发执行
- **Then**: watcher 编排调 engine → 落 screen_result 记录 → 返回结果；重新查询能看到该次执行记录
- **Verification**: `programmatic`

### AC-14: 选股结果锁定与追踪
- **Given**: 已有选股结果
- **When**: 用户点击「锁定」→ 收盘后定时任务跑 5/10/20 日收益
- **Then**: screen_lock 表有记录，追踪明细页展示组合累计收益与个股贡献
- **Verification**: `programmatic`

### AC-15: 与因子库口径一致
- **Given**: 同一股票同一日同一因子参数
- **When**: 选股算的因子值 vs 直接调 002 因子库 `/python/v1/factors/compute`
- **Then**: 两者数值完全一致（共用 FactorCalculator）
- **Verification**: `programmatic`

### AC-16: 与统一 Schema screen_config 字段对齐
- **Given**: 统一 Schema §3.2 screen_config 示例
- **When**: 把示例直接作为 `/python/v1/screener/snapshot` 请求体的核心字段（外加 candidates）
- **Then**: 接口正常解析，无字段缺失/不一致
- **Verification**: `human-judgment`

### AC-17: API 文档完整性
- **Given**: engine 与 watcher 服务运行中
- **When**: 访问 engine `/docs` 与 watcher 接口文档
- **Then**: 选股相关接口（snapshot/range/方案 CRUD/执行/锁定/追踪）都有完整请求/响应模型、描述、示例
- **Verification**: `human-judgment`

### AC-18: 代码结构清晰
- **Given**: 选股模块代码（engine 条件引擎 + 选股服务；watcher 编排 + 方案/结果/追踪）
- **When**: 代码审查
- **Then**: 三层（条件引擎 / 因子计算 / 编排存储）职责清晰，无跨层越权
- **Verification**: `human-judgment`

## Open Questions
- [ ] 区间选股的并发与超时策略——是 engine 单次同步处理全区间，还是 watcher 分日调用快照接口聚合？第一版倾向 engine 单次（减少 HTTP 往返），超大区间再演进为分批
- [ ] 多因子综合排序的 NaN 处理——是剔除该维度（默认）还是赋中性分 0？需用户研究偏好确认
- [ ] 选股结果追踪的收益基准——是等权组合 vs 沪深300，还是绝对收益？第一版提供等权组合 + 沪深300 双口径
- [ ] 是否需要选股条件树的"语法糖"预设（如"低估值优质"模板）？归前端模板向导，本模块只提供引擎能力
- [ ] watcher candidates 的 OHLCV 历史窗口长度协商——engine 是否需要返回"所需最大 lookback"提示接口供 watcher 调用？或 watcher 按 `history_depth=60` 默认窗口兜底
- [ ] 选股结果追踪的最小持仓单位——是按 top_n 等权全买，还是仅记录 hit 列表不模拟交易？第一版仅记录 hit 列表，交易模拟归 005 回测
