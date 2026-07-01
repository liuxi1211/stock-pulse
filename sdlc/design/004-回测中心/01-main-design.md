# 回测中心 — 研发主设计方案

> 参考资料索引：
> - 需求 PRD：`sdlc/prd/004-回测中心/004-回测中心PRD.md`
> - 原型图：
>   - `sdlc/prd/004-回测中心/prototype/backtest-list.html` — 回测记录列表页
>   - `sdlc/prd/004-回测中心/prototype/backtest-config.html` — 新建回测 / 配置页
>   - `sdlc/prd/004-回测中心/prototype/backtest-report.html` — 单次回测详细报告页
>   - `sdlc/prd/004-回测中心/prototype/backtest-grid.html` — 参数优化结果页
>   - `sdlc/prd/004-回测中心/prototype/backtest-walk-forward.html` — Walk-forward 分析页
>
> 项目：Stock Watcher（Java Spring Boot + SQLite + Thymeleaf + ECharts）
> 匹配过程：用户输入「004」→ 匹配到目录「004-回测中心」

---

## 1. 需求摘要

回测中心是量化策略引擎的核心模块之一，用于**验证策略在历史数据上的表现**。核心目标是：

1. **单次回测** — 给定策略 + 股票范围 + 日期区间 + 资金配置，输出完整绩效报告（净值曲线、回撤、胜率、盈亏比等）
2. **参数优化（网格搜索）** — 对策略中的数值型参数进行笛卡尔积组合测试，输出按评估指标排序的结果集，帮助找到最佳参数
3. **Walk-forward 分析** — 通过滚动训练+验证的方式检测策略是否存在过拟合风险，输出各段验证期的组合表现

依赖模块：
- **策略管理** — 读取策略 JSON（`quant_strategy` 表，由其他模块维护）
- **因子库** — Python 计算引擎依赖因子注册表（本模块不直接操作，由 Python 端调用）
- **基础数据** — `daily_quote`（日线行情）、`stock_basic`（股票基础信息）、`trade_cal`（交易日历）

---

## 2. 原型图分析（页面结构与交互流程）

| 页面（原型图） | 核心功能 | 关键表单字段 | 主要操作 | 与其他页面的关联 |
|---------------|---------|-------------|---------|-----------------|
| `backtest-list.html` | 展示所有回测任务列表，支持按模式筛选，显示核心指标摘要 | 搜索框（策略名称）、模式 Tab（全部/单次/参数优化/Walk-forward） | 新建回测（跳转配置页）、点击查看详情（按模式跳转到报告/优化结果/WF结果页） | 入口页 → 跳转到 config.html / report.html / grid.html / walk-forward.html |
| `backtest-config.html` | 创建新回测任务，选择策略 + 回测模式 + 配置参数 | 策略选择（下拉）、模式切换（3 个 Tab）、日期范围（起止日期）、选股范围（沪深300/中证500/自定义）、初始资金、佣金率、滑点、调仓频率、最大持仓数、单票最大仓位、基准指数开关 | 开始回测（提交表单 → 后端创建任务 → Python 计算 → 跳转到报告页）；取消（返回列表页） | 依赖策略管理模块提供策略列表；提交后在列表页显示新任务 |
| `backtest-report.html` | 单次回测的完整报告页 | 无表单（只读展示） | 用此参数再跑一次（预填写配置表单）、参数优化（跳转到 grid 模式配置页） | 从列表页/配置页跳转而来；可反向跳转到配置页 |
| `backtest-grid.html` | 参数优化结果展示与分析 | 无表单（只读展示） | 应用最优参数到策略（更新策略 JSON 为新版本） | 与策略管理模块联动 |
| `backtest-walk-forward.html` | Walk-forward 分段分析结果 | 无表单（只读展示） | 无写操作 | 只读展示页 |

---

## 3. 目标与非目标

| 项 | 说明 |
|---|------|
| **本次做什么** | **P0：单次回测**（创建任务 → Python 计算 → 存储结果 → 前端渲染报告）<br>**P0：参数优化**（网格搜索 → 结果排序 → 最优参数应用）<br>**P0：Walk-forward**（滚动训练验证 → 分段结果 → 组合验证期绩效）<br>**P1：回测记录管理**（列表展示、状态轮询、删除历史记录） |
| **本次不做什么** | 实时交易模拟（实盘/模拟盘下单链路在持仓管理模块）<br>多周期策略（日线+分钟线混合，后续版本支持）<br>蒙特卡洛模拟（PRD 明确不做，Walk-forward 已覆盖过拟合检测）<br>回测对比（两个策略并排对比，留待后续版本优化）<br>全市场回测（PRD 明确限制为沪深300/中证500/自定义≤50只，全市场链路在 V2.2 支持） |

---

## 4. 技术方案

### 4.1 总体架构

```
┌───────────────────── Thymeleaf 前端 ─────────────────────┐
│  回测列表 / 回测配置 / 报告详情 / 参数优化 / WF 分析      │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP JSON（/api/backtest/*）
┌──────────────────────────▼───────────────────────────────┐
│                Java Spring Boot 后端                      │
│                                                           │
│  ┌────────────┐   ┌─────────────┐   ┌───────────────┐    │
│  │ Backtest   │   │ Compute     │   │ Async 状态    │    │
│  │ Controller │←→│ Gateway     │   │ 更新 + 轮询    │    │
│  └─────┬──────┘   └──────┬──────┘   └───────────────┘    │
│        │                 │                                │
│        ▼                 ▼                                │
│  ┌────────────┐   ┌───────────────────────┐              │
│  │ Backtest   │   │ Python FastAPI 计算    │              │
│  │ Service    │   │ 引擎（akquant）         │              │
│  └─────┬──────┘   │  · 规则树解析          │              │
│        │          │  · 事件驱动回测         │              │
│        ▼          │  · 网格搜索 / WF        │              │
│  ┌────────────┐   │  · 指标计算             │              │
│  │ Backtest   │   └───────────┬─────────────┘              │
│  │ Mapper     │               │ HTTP POST（JSON）          │
│  └─────┬──────┘               │                            │
│        │                     ┌┘                            │
│        ▼                                                     │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ SQLite（与 Java 同进程独占读写）                      │   │
│  │  · quant_backtest              ← 回测主记录          │   │
│  │  · quant_backtest_report       ← 单次回测详细数据    │   │
│  │  · quant_backtest_grid_result  ← 参数优化结果        │   │
│  │  · quant_backtest_wf_result    ← WF 分段结果         │   │
│  │  · quant_strategy              ← 策略（依赖其他模块）│   │
│  │  · daily_quote / stock_basic   ← 历史行情基础数据     │   │
│  │  · trade_cal / adj_factor      ← 日历 / 复权因子     │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 4.2 模块划分

| 模块 | 职责 | Java 包路径 | 主要类 | 对应原型图页面 |
|------|------|-------------|--------|---------------|
| **Backtest API 层** | 接收前端回测请求、状态查询、报告获取、参数优化结果查询、WF 结果查询 | `com.arthur.stock.controller` | `BacktestController` | 全部 5 个页面 |
| **Backtest Service 层** | 回测任务管理（创建/状态更新/删除）、调度 Python 计算、写入结果、策略参数应用 | `com.arthur.stock.service` | `BacktestService` | 全部 5 个页面 |
| **Compute Gateway** | Java ↔ Python 通信协议封装（HTTP POST）、任务 ID 生成、超时与重试 | `com.arthur.stock.infra.compute` | `ComputeGateway` | 配置页提交操作的底层依赖 |
| **Backtest Data 层** | MyBatis-Plus Mapper 操作 4 张回测相关表；DO/VO 结构 | `com.arthur.stock.mapper` / `com.arthur.stock.entity` | `Backtest*Mapper` / `*DO` | 报告/优化/WF 页的数据源 |

### 4.3 关键流程

#### 流程 A：单次回测完整链路

```
1. 用户在 backtest-config.html 填写表单（策略 + 模式=SINGLE + 日期范围 + 选股范围 + 资金配置）
2. 点击「开始回测」 → POST /api/backtest/create
3. Java BacktestService.createBacktest()：
   a. 从 quant_strategy 表读取策略 JSON
   b. 写入 quant_backtest 表一条 RUNNING 记录，生成 task_id（UUID）
   c. 从 daily_quote + stock_basic 按选股范围拉取历史数据
   d. 通过 ComputeGateway 异步调用 Python POST /api/compute/backtest
      （body 含：task_id + 策略 JSON + 股票 OHLCV 数据 + 资金配置 + 调仓参数）
4. Python akquant 引擎执行事件驱动回测：
   a. 规则树解析 → 动态生成 buy/sell 逻辑
   b. 逐 bar 推进：止损/止盈/普通条件/最大持仓天判断
   c. 输出 equity_curve、trades、positions、metrics
5. Python 返回 HTTP 响应 → ComputeGateway 接收
6. Java BacktestService 处理响应：
   a. 写入 quant_backtest_report（equity_curve_json + trades_json + positions_json + metrics_json + benchmark_json）
   b. 更新 quant_backtest.status = SUCCESS，填充核心指标字段
   c. 异常时 status = FAILED + error_message
7. 前端状态轮询（GET /api/backtest/{id}/status，每 3 秒一次）或直接跳转到报告页
8. 用户在 backtest-report.html 查看：
   - GET /api/backtest/{id} → 回测概要 + 核心指标
   - GET /api/backtest/{id}/report → 完整报告数据（ECharts 渲染净值曲线/回撤/热力图）
   - GET /api/backtest/{id}/trades?page=1&size=20 → 交易流水分页
```

#### 流程 B：参数优化

```
1. 用户在 backtest-config.html 选择模式=GRID_SEARCH
2. 在「待优化参数」区配置：每个参数的候选值列表或起始/结束/步长
3. 选择优化目标指标（sharpe_ratio / total_return_pct / win_rate / calmar_ratio）
4. 提交 → POST /api/backtest/create（mode = GRID_SEARCH，param_grid 字段填充）
5. Java 逻辑同流程 A，但调用 /api/compute/grid-search
6. Python 对参数笛卡尔积逐一执行回测，按目标指标排序
7. Java 接收所有组合结果 → 写入 quant_backtest_grid_result（每条记录 = 一组参数 + 指标）
8. 用户在 backtest-grid.html 查看：
   - GET /api/backtest/{id}/grid-results?page=1&size=50 → 参数优化结果列表（按 metric_value DESC）
   - 点击「应用最优参数到策略」 → PUT /api/backtest/{id}/apply-best-params
     （更新 quant_strategy 的参数 JSON 为新版本，由策略管理模块处理版本逻辑）
```

#### 流程 C：Walk-forward 分析

```
1. 用户在 backtest-config.html 选择模式=WALK_FORWARD
2. 配置：训练窗口大小（交易日，默认 252）、验证窗口（默认 63）、滚动步长（默认 63）、优化目标指标
3. 参数配置同网格搜索（Param Grid 复用）
4. 提交 → POST /api/backtest/create（mode = WALK_FORWARD，walk_forward 配置填充）
5. Java 调用 Python /api/compute/walk-forward
6. Python 按 [训练期→优化参数→验证期回测] 滚动执行，输出每段的 best_params + 训练期/验证期指标
7. Java 接收分段结果 → 写入 quant_backtest_wf_result（每条 = 一段）
8. 同时在 quant_backtest 主表写入组合验证期的核心指标（用于列表页展示）
9. 用户在 backtest-walk-forward.html 查看：
   - GET /api/backtest/{id}/wf-segments → 分段详情
   - GET /api/backtest/{id}/wf-combined → 组合验证期表现
```

### 4.4 类设计 / 接口契约

#### BacktestService 核心方法签名

```java
package com.arthur.stock.service;

public class BacktestService {

    /** 创建回测任务（三种模式通用），返回任务 ID */
    Long createBacktest(BacktestCreateDTO dto);

    /** 查询回测记录详情（含核心指标） */
    BacktestVO getBacktest(Long id);

    /** 分页查询回测列表 */
    PageVO<BacktestListItemVO> listBacktests(String mode, String keyword, int page, int size);

    /** 获取单次回测完整报告（equity/trades/positions/metrics/benchmark） */
    BacktestReportVO getBacktestReport(Long id);

    /** 交易流水分页 */
    PageVO<TradeItemVO> listTrades(Long id, int page, int size);

    /** 参数优化结果分页 */
    PageVO<GridResultItemVO> listGridResults(Long id, int page, int size);

    /** Walk-forward 分段结果 */
    List<WfSegmentVO> getWfSegments(Long id);

    /** Walk-forward 组合验证期表现 */
    WfCombinedVO getWfCombined(Long id);

    /** 应用最优参数到策略（联动策略管理模块） */
    void applyBestParams(Long backtestId);

    /** 删除回测记录（级联删除详细数据） */
    void deleteBacktest(Long id);
}
```

#### 核心 DTO/VO 字段（与原型图表单对齐）

```
BacktestCreateDTO（提交表单）：
├── strategyId            Long        策略 ID
├── mode                  String      SINGLE / GRID_SEARCH / WALK_FORWARD
├── startDate             String      YYYY-MM-DD
├── endDate               String      YYYY-MM-DD
├── universeType          String      INDEX_300 / INDEX_500 / CUSTOM
├── universeCodes         List<String> CUSTOM 模式下的股票代码列表（如 ["600519.SH", ...]）
├── initialCash           BigDecimal  初始资金
├── commissionPct         BigDecimal  佣金率（0.0003 = 万三）
├── slippagePct           BigDecimal  滑点（0.001 = 0.1%）
├── rebalanceFrequencyDays Integer    调仓频率（交易日）
├── maxPositions          Integer     最大持仓数
├── maxSinglePositionPct  BigDecimal  单票最大仓位（0.10 = 10%）
├── benchmarkEnabled      Boolean     是否启用基准对比
├── paramGrid             JSON        网格搜索/WF 的参数范围（仅模式为 GRID_SEARCH/WALK_FORWARD 时）
├── optimizationMetric    String      优化目标指标（sharpe_ratio/total_return_pct/win_rate/calmar_ratio）
└── walkForwardConfig     JSON        WF 专属配置（训练窗口/验证窗口/步长）

BacktestVO（列表/详情展示）：
├── id                    Long
├── strategyId            Long
├── strategyName          String
├── strategyVersion       Integer
├── mode                  String
├── status                String      RUNNING / SUCCESS / FAILED
├── startDate / endDate   String
├── universeType          String
├── totalReturnPct        BigDecimal  核心指标（列表页直接展示）
├── sharpeRatio           BigDecimal
├── maxDrawdownPct        BigDecimal
├── winRate               BigDecimal
├── tradesCount           Integer
├── errorMessage          String      失败时显示
├── createdAt             String
└── completedAt           String

BacktestReportVO（完整报告）：
├── backtest              BacktestVO  概要信息
├── equityCurve           List<PointVO> [{date, value}]
├── benchmarkCurve        List<PointVO> [{date, value}]
├── trades                List<TradeItemVO>
├── positions             List<PositionItemVO>
└── metrics               Map<String, Object>  全量指标（年化/波动率/卡尔马/索提诺等）
```

### 4.5 与现有系统的集成点

| 集成项 | 说明 |
|--------|------|
| **Python 计算引擎** | 新增 `ComputeGateway` 类统一管理 Java ↔ Python HTTP 通信；地址从 `application.yml` 读取（`python.compute.url`，默认 `http://127.0.0.1:8000`）；超时设置 5 分钟 |
| **quant_strategy 表** | 由策略管理模块维护，本模块**只读**访问：读取策略 JSON 用于提交给 Python，以及「应用最优参数」时回写参数（通过策略管理模块的 Service 接口） |
| **daily_quote / stock_basic** | 本模块读取用于构建 Python 计算所需的 OHLCV 数据；每次回测时按选股范围 + 日期范围从 `daily_quote` 查询 |
| **trade_cal** | 用于验证日期范围的有效性（是否为交易日），以及计算调仓频率时的交易日计数 |
| **UserContext** | 复用现有用户认证体系；回测记录归属用户（`user_id` 字段），用户只能查看自己的回测 |
| **定时任务** | Python 计算通过 HTTP 异步调用，不依赖 Spring @Scheduled；前端通过轮询 `/status` 接口获取进度 |
| **缓存** | 回测结果为一次性写入的历史数据，不需要缓存；列表查询通过 DB 索引（`idx_bt_strategy` / `idx_bt_mode` / `idx_bt_status`）优化 |

### 4.6 异常与错误码

| 场景 | HTTP 状态 | 复用/新增 | 说明 |
|------|-----------|-----------|------|
| 必填字段缺失 / 格式错误 | 400 BAD_REQUEST | 复用现有 `BAD_REQUEST(400)` | strategyId、mode、startDate、endDate、universeType、initialCash 必填 |
| 策略不存在 | 404 NOT_FOUND | 复用现有 `NOT_FOUND(404)` | strategyId 对应的策略未找到或非 ACTIVE 版本 |
| 日期范围无效（开始>结束 或 无有效交易日数据） | 400 BAD_REQUEST | 复用现有 `BAD_REQUEST(400)` | message 给出具体原因 |
| 自定义选股超过 50 只 | 400 BAD_REQUEST | 复用现有 `BAD_REQUEST(400)` | PRD 明确限制自定义模式 ≤ 50 只 |
| 参数网格组合数过大（>1000 组） | 400 BAD_REQUEST | 复用现有 `BAD_REQUEST(400)` | 提示用户缩小参数范围 |
| Python 计算引擎连接失败 | 500 INTERNAL_SERVER_ERROR | 新定义 `COMPUTE_ENGINE_ERROR(2001, "计算引擎连接失败")` | ComputeGateway 捕获异常后回写 status=FAILED |
| Python 计算超时（>5 分钟） | 500 INTERNAL_SERVER_ERROR | 新定义 `COMPUTE_TIMEOUT(2002, "计算超时，请尝试缩小数据范围或调整参数")` | 记录到 error_message |
| Python 返回计算错误（规则解析失败 / 因子缺失等） | 500 INTERNAL_SERVER_ERROR | 新定义 `COMPUTE_FAILED(2003, "计算引擎错误")` | 具体原因写入 error_message 供前端展示 |
| 回测记录不存在 | 404 NOT_FOUND | 复用现有 `NOT_FOUND(404)` | GET/DELETE 非法 id |
| 非回测创建者尝试删除 | 403 FORBIDDEN | 复用现有 `FORBIDDEN(403)` | user_id 校验不通过 |
| 运行中的回测不允许删除 | 409 CONFLICT | 复用现有 `CONFLICT(409)` | 需等待完成或通过中止接口取消 |

### 4.7 复用清单（基于实时扫描结果）

| 类别 | 现有资源（来自实时扫描） | 复用决策 | 说明 |
|------|---------------------|---------|------|
| **表** | `sys_user` | 复用 | 回测记录关联 user_id 字段，实现按用户隔离 |
| **表** | `daily_quote` | 复用 | 读取历史 OHLCV 数据构建 Python 入参 |
| **表** | `stock_basic` | 复用 | 选股范围校验 + 股票名称映射 |
| **表** | `trade_cal` | 复用 | 日期范围有效性校验 |
| **表** | `adj_factor` | 复用 | 复权处理（Python 端或 Java 端已预处理） |
| **表** | `quant_strategy` | 复用（只读 + 有限回写） | 策略表由策略管理模块维护，本模块读取策略 JSON，应用最优参数时回写 |
| **表** | — | **新增 4 张** | quant_backtest / quant_backtest_report / quant_backtest_grid_result / quant_backtest_wf_result |
| **接口** | `/api/watchlist` | 不直接复用 | 风格参考：统一使用 `ApiResponse<T>` 包装返回 |
| **接口** | `/api/auth` 系列 | 复用认证机制 | 回测接口默认需要登录（UserContext.getUserId()） |
| **接口** | — | **新增 Controller** | `BacktestController`（`/api/backtest/*`），URL 风格与现有 Controller 一致 |
| **错误码** | `BAD_REQUEST(400)` / `UNAUTHORIZED(401)` / `FORBIDDEN(403)` / `NOT_FOUND(404)` / `CONFLICT(409)` | **全部复用** | 覆盖大多数场景 |
| **错误码** | — | **新增 3 个** | COMPUTE_ENGINE_ERROR(2001) / COMPUTE_TIMEOUT(2002) / COMPUTE_FAILED(2003) |
| **代码风格** | `@RestController @RequiredArgsConstructor` / `ApiResponse.success()` / Mapper 继承 BaseMapper | **严格复用** | Backtest 模块遵循完全相同的风格 |
| **数据类型** | SQLite 仅使用 INTEGER / TEXT / REAL | **严格复用** | 新表不引入 MySQL 专有类型 |

---

## 5. 验收要点

- [ ] **P0 功能：** 通过 POST /api/backtest/create 可成功创建单次回测任务，Python 计算完成后写入 report 表（对应 prototype/backtest-config.html 的「开始回测」按钮）
- [ ] **P0 功能：** GET /api/backtest/list 返回分页列表，列表项字段与 backtest-list.html 中的列一致（策略名称/模式/日期/指标/状态/创建时间）
- [ ] **P0 功能：** GET /api/backtest/{id}/report 返回完整报告数据，ECharts 可渲染净值曲线、回撤曲线、月度热力图（对应 backtest-report.html 的图表区块）
- [ ] **P0 功能：** 参数优化模式可创建任务，grid-results 接口返回按指标降序的参数组合列表（对应 backtest-grid.html）
- [ ] **P0 功能：** Walk-forward 模式可创建任务，wf-segments 接口返回各段训练期/验证期详情（对应 backtest-walk-forward.html）
- [ ] **数据一致性：** quant_backtest 主表的核心指标（totalReturnPct / sharpeRatio / maxDrawdownPct 等）与 quant_backtest_report.metrics_json 中对应字段一致
- [ ] **权限隔离：** 用户 A 不能通过 GET /api/backtest/{B的id} 查看用户 B 的回测记录
- [ ] **异常场景：** Python 引擎不可达时，任务状态标记为 FAILED 并写入 error_message，前端可在列表页看到失败标记
- [ ] **与策略管理联动：** 「应用最优参数」可成功更新策略参数（需策略管理模块的版本更新接口存在）

---

## 6. 风险与 TODO

| 事项 | 状态 | 说明 |
|------|------|------|
| **Python akquant 动态策略类生成** | 待确认 | PRD 提到需从 JSON 动态生成策略类。开发前建议先用 1 个测试策略跑通 akquant 的静态代码路径，再抽象成动态生成。如 akquant API 版本变化需要适配，Python 端独立调试 |
| **Python 服务地址配置** | 已规划 | application.yml 中新增 `python.compute.url=http://127.0.0.1:8000`，生产环境可配置 |
| **超时策略** | 已定义 | HTTP 超时 5 分钟；如参数组合过多，前端可提示用户缩小范围（>1000 组时拦截） |
| **quant_strategy 表的版本管理** | 依赖其他模块 | 「应用最优参数到策略」需要策略管理模块支持版本化。本模块设计时假设策略表有 `version` 字段和版本新增接口 |
| **INDEX_300 / INDEX_500 成分股** | 待确认 | 需要一种方式获取沪深300/中证500的最新成分股。可选择：(a) Tushare index_weight 接口定时拉取存储；(b) 在 stock_basic 表中增加 index_membership 字段。推荐方案 (a) 独立表 `index_weight`，但不属于本模块范围，需在数据中台层面完成。本模块先以自定义模式支持 |
| **benchmark 数据** | 待确认 | 沪深300指数（000300.SH）的日线数据需要在 daily_quote 表中存在。数据拉取模块需同步拉取该指数。本模块启动回测时检查 benchmark 数据可用性，缺失时给出提示并降级为不展示基准曲线 |
| **大数据量报告存储** | 已规划 | equity_curve_json 如果存 3 年日线 ≈ 750 条记录，约 30-50KB；trades_json 约 100-500 条，约 20-100KB。SQLite TEXT 完全能承载。建议对超过 1MB 的 JSON 做压缩存储（可后续优化） |
| **止损/止盈/普通条件/最大持仓天数的判断优先级** | 已定义（PRD §10.1） | 同一根 bar 上按 stop_loss → take_profit → max_holding_days → compare 顺序判断，第一个触发的即执行卖出，其余不再评估 |

---

## 7. 自检记录

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 所有表/字段命名 snake_case | ✅ | 与现有 schema.sql 风格一致 |
| 复用现有 ErrorCode 避免重复 | ✅ | 仅新增 3 个计算引擎相关错误码 |
| JSON 返回字段使用驼峰（camelCase） | ✅ | BacktestVO 等使用 strategyName / startDate / totalReturnPct 等 |
| 数据库类型仅使用 SQLite 支持的 INTEGER/TEXT/REAL | ✅ | 无 MySQL 专有类型 |
| 与 prototype 表单字段对齐 | ✅ | BacktestCreateDTO 的 universeType / initialCash / commissionPct 等与 config.html 表单一一对应 |
| 列表页/报告页/优化页/WF 页的接口设计覆盖了原型图所有展示区块 | ✅ | 净值曲线、回撤、热力图、交易流水、参数组合表、分段表均有对应接口 |
| 与现有 Controller 路径不冲突 | ✅ | 新增路径 `/api/backtest/*`，现有路径无冲突 |
| 不设计超出 PRD 范围的功能 | ✅ | 回测对比/全市场回测/蒙特卡洛等均列入「本次不做」 |
