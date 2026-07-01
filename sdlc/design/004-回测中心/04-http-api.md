# 回测中心 — HTTP API 接口设计

> 设计依据：
> - PRD：`sdlc/prd/004-回测中心/004-回测中心PRD.md`
> - 原型图：`sdlc/prd/004-回测中心/prototype/` 下的 HTML 页面
> - 主设计：`sdlc/design/004-回测中心/01-main-design.md`
> - 现有代码风格：参考 `stock-watcher/src/main/java/com/arthur/stock/controller/WatchlistController.java`
> - 统一响应格式：所有接口返回 `ApiResponse<T>`（复用现有 dto.ApiResponse）
> - 统一基路径：`/api/backtest`
> - 字段命名：JSON 返回字段使用 camelCase（与 MyBatis-Plus map-underscore-to-camel-case=true 一致）

---

## 0. 接口清单总览

| 方法 | 路径 | 功能 | 对应原型图页面 | P0/P1 |
|------|------|------|---------------|--------|
| POST | `/api/backtest` | 创建回测任务（三种模式共用，body 区分） | backtest-config.html 提交表单 | P0 |
| GET | `/api/backtest` | 分页查询回测列表（支持按模式/关键字筛选） | backtest-list.html 列表 | P0 |
| GET | `/api/backtest/{id}` | 获取回测记录详情（含核心指标） | 所有页面的概要信息区 | P0 |
| GET | `/api/backtest/{id}/status` | 轻量状态查询（前端轮询进度使用） | config.html 提交后的进度检查 | P0 |
| DELETE | `/api/backtest/{id}` | 删除回测记录（级联删除详细数据） | list.html 每行的删除按钮 | P0 |
| GET | `/api/backtest/{id}/report` | 获取单次回测完整报告（净值/回撤/交易/指标） | backtest-report.html 全部图表 | P0 |
| GET | `/api/backtest/{id}/trades` | 交易流水分页（独立接口，避免拉大 JSON） | backtest-report.html 交易流水表格 | P0 |
| GET | `/api/backtest/{id}/grid-results` | 参数优化结果分页（按排名） | backtest-grid.html 参数组合表 | P0 |
| PUT | `/api/backtest/{id}/apply-best-params` | 应用最优参数到策略（联动策略管理模块） | backtest-grid.html 「应用最优参数」按钮 | P1 |
| GET | `/api/backtest/{id}/wf-segments` | Walk-forward 分段详情（按段序号） | backtest-walk-forward.html 分段时间轴 + 参数稳定性表 | P0 |
| GET | `/api/backtest/{id}/wf-combined` | Walk-forward 组合验证期表现（全量指标聚合） | backtest-walk-forward.html 组合验证期绩效卡片 | P0 |

---

## 1. 通用说明

### 1.1 统一响应格式（复用现有 `ApiResponse<T>`）

```jsonc
// 成功响应
{
  "code": 200,
  "message": "ok",
  "data": { /* 各接口的具体返回数据 T */ }
}

// 失败响应
{
  "code": 400,                // HTTP 语义对应的业务错误码
  "message": "初始资金必须大于 0",
  "data": null
}
```

### 1.2 错误码

| HTTP 状态 | code | message 示例 | 场景 | 复用/新增 |
|-----------|------|-------------|------|----------|
| 400 | 400 | `必填字段缺失：strategyId` | 参数校验失败 | 复用现有 `ErrorCode.BAD_REQUEST` |
| 400 | 400 | `自定义选股数量超过限制（≤50）` | 股票代码过多 | 复用现有 `ErrorCode.BAD_REQUEST` |
| 401 | 401 | `请先登录` | 未登录访问 | 复用现有 `ErrorCode.UNAUTHORIZED` |
| 403 | 403 | `无权限访问该回测记录` | 访问其他用户的回测 | 复用现有 `ErrorCode.FORBIDDEN` |
| 404 | 404 | `回测记录不存在` | GET/DELETE 非法 id | 复用现有 `ErrorCode.NOT_FOUND` |
| 404 | 404 | `策略不存在或未激活` | strategyId 无效 | 复用现有 `ErrorCode.NOT_FOUND` |
| 409 | 409 | `运行中的回测不允许删除` | 删除 RUNNING 状态任务 | 复用现有 `ErrorCode.CONFLICT` |
| 500 | 2001 | `计算引擎连接失败` | Python 服务不可达 | 新增（COMPUTE_ENGINE_ERROR） |
| 500 | 2002 | `计算超时，请尝试缩小数据范围` | Python 执行超时（>5min） | 新增（COMPUTE_TIMEOUT） |
| 500 | 2003 | `计算引擎错误：{具体原因}` | Python 返回异常信息 | 新增（COMPUTE_FAILED） |

### 1.3 分页参数与返回结构

```
GET 请求 Query 参数（列表类接口通用）：
  page    Integer  页码，从 1 开始，默认 1
  size    Integer  每页条数，默认 20，最大 100
```

```jsonc
// 分页返回结构（复用现有 PageVO<T> 或按此格式设计）
{
  "code": 200,
  "message": "ok",
  "data": {
    "total": 128,        // 总记录数
    "page": 1,           // 当前页
    "size": 20,          // 每页条数
    "items": [ ... ]     // 当前页数据列表（T[]）
  }
}
```

---

## 2. 接口详细设计

### 2.1 创建回测任务

> **对应原型图**：backtest-config.html（点击「开始回测」按钮触发）
> **前端交互**：
>   1. 用户在 config.html 填写表单（策略 + 模式 Tab + 日期 + 选股范围 + 资金配置）
>   2. 根据模式 Tab 展示不同的参数配置区（SINGLE 无额外参数，GRID/WF 显示 paramGrid）
>   3. 提交表单 → POST /api/backtest
>   4. 后端返回 id → 前端跳转至对应报告页（SINGLE→report.html，GRID→grid.html，WF→walk-forward.html）
>   5. 报告页通过 GET /api/backtest/{id}/status 轮询检查进度

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `POST` |
| **路径** | `/api/backtest` |
| **鉴权** | 需要登录（从 UserContext 读取 user_id） |
| **请求体** | BacktestCreateDTO（JSON） |
| **成功响应** | `{id: Long, status: String, taskId: String}` |

#### 请求体字段

| 字段 | 类型 | 必填 | 说明 | 原型图中的来源 |
|------|------|------|------|---------------|
| strategyId | Long | ✅ | 策略 ID | config.html 策略选择下拉框（option value） |
| mode | String | ✅ | `SINGLE` 单次 / `GRID_SEARCH` 参数优化 / `WALK_FORWARD` Walk-forward | config.html 3 个模式 Tab（active tab 对应值） |
| startDate | String | ✅ | 回测开始日期 `YYYY-MM-DD` | config.html `input type="date" start_date` |
| endDate | String | ✅ | 回测结束日期 `YYYY-MM-DD` | config.html `input type="date" end_date` |
| universeType | String | ✅ | `INDEX_300` 沪深300 / `INDEX_500` 中证500 / `CUSTOM` 自定义 | config.html 「选股范围」option 卡片的 data-value |
| universeCodes | String[] | 条件 | 自定义模式下的股票代码数组，`universeType=CUSTOM` 时必填，`≤50` 个 | config.html 中 universeType=CUSTOM 时的股票多选框 |
| initialCash | BigDecimal | ✅ | 初始资金，`>0` | config.html 「初始资金」输入框 |
| commissionPct | BigDecimal | ✅ | 佣金率，`0.0001 ~ 0.01`（万分之一到百分之一） | config.html 「佣金率(%)」输入框（前端显示 %，后端存小数） |
| slippagePct | BigDecimal | ✅ | 滑点，`0 ~ 0.05`（0% ~ 5%） | config.html 「滑点(%)」输入框（同上） |
| rebalanceFrequencyDays | Integer | ✅ | 调仓频率（交易日），`1 ~ 30` | config.html 「调仓频率(交易日)」输入框 |
| maxPositions | Integer | — | 最大持仓数，不填时使用策略默认值 | config.html 「最大持仓数」输入框 |
| maxSinglePositionPct | BigDecimal | — | 单票最大仓位比例（`0.10 = 10%`），不填使用策略默认值 | config.html 「单票最大仓位(%)」输入框 |
| benchmarkEnabled | Boolean | ✅ | 是否启用基准对比（沪深300） | config.html 「对比基准指数」checkbox |
| paramGrid | Object | 条件 | 参数范围定义。`mode=GRID_SEARCH` 或 `WALK_FORWARD` 时必填 | config.html Grid/WF Tab 中的参数候选值输入区 |
| optimizationMetric | String | 条件 | 优化目标指标：`sharpe_ratio` / `total_return_pct` / `win_rate` / `calmar_ratio`。`mode=GRID_SEARCH` 或 `WALK_FORWARD` 时必填 | config.html 的「优化目标指标」选择区 |
| walkForwardConfig | Object | 条件 | Walk-forward 专属配置。`mode=WALK_FORWARD` 时必填 | config.html WF Tab 中的训练/验证窗口输入框 |

**paramGrid 结构示例**（网格搜索/WF 的参数范围定义）：
```jsonc
{
  "buy_rules.conditions[0].left.params.period": [5, 10, 15, 20, 30],
  "sell_rules.stop_loss.percent": [0.05, 0.07, 0.09, 0.11, 0.13],
  "sell_rules.take_profit.percent": [0.10, 0.15, 0.20, 0.25]
}
```

**walkForwardConfig 结构示例**：
```jsonc
{
  "trainingWindowDays": 252,      // 训练窗口（交易日）
  "validationWindowDays": 63,     // 验证窗口
  "stepDays": 63                  // 滚动步长
}
```

#### 请求示例

```http
POST /api/backtest
Content-Type: application/json

{
  "strategyId": 15,
  "mode": "SINGLE",
  "startDate": "2024-01-01",
  "endDate": "2024-12-31",
  "universeType": "INDEX_300",
  "initialCash": 1000000,
  "commissionPct": 0.0003,
  "slippagePct": 0.001,
  "rebalanceFrequencyDays": 5,
  "maxPositions": 10,
  "maxSinglePositionPct": 0.10,
  "benchmarkEnabled": true
}
```

#### 成功响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "id": 42,
    "status": "RUNNING",
    "taskId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

#### 失败响应示例（参数校验失败）

```jsonc
{
  "code": 400,
  "message": "自定义选股数量超过限制（当前 58，≤50）",
  "data": null
}
```

#### 后端处理流程（摘要）

```
1. 校验：必填字段 / 日期范围 / universeCodes 长度 / 参数网格组合数（>1000 拦截）
2. 检查 strategy 存在且为 ACTIVE 版本（调用策略管理模块的查询接口）
3. 从 daily_quote 拉取股票数据，构造 Python 计算请求的 market data
4. 生成 taskId (UUID)，写入 quant_backtest 一条 RUNNING 记录
5. 异步调用 Python POST /api/compute/{backtest|grid-search|walk-forward}
   （根据 mode 路由到不同 Python 接口）
6. Python 计算完成后，ComputeGateway 回调：
   a. 解析响应中的指标 / equity / trades
   b. 写入 quant_backtest_report（SINGLE 模式）或 grid_result/wf_result（其他模式）
   c. 更新 quant_backtest 主表的 status + 核心指标字段
7. 返回 {id, status, taskId} 给前端
```

---

### 2.2 分页查询回测列表

> **对应原型图**：backtest-list.html（整个页面的列表数据来源）
> **前端交互**：
>   1. 列表页默认展示全部回测（不分模式）
>   2. 顶部 Tab（全部/单次/参数优化/Walk-forward）切换 → 改变 mode Query 参数
>   3. 搜索框输入策略名称关键字 → keyword Query 参数
>   4. 默认按 created_at 倒序（最新在前）
>   5. 列表列：策略名称 · 模式 · 回测区间 · 选股范围 · 股票数 · 总收益 · 夏普 · 最大回撤 · 胜率 · 状态 · 创建时间 · 操作

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest` |
| **鉴权** | 需要登录（只返回当前用户的回测） |
| **Query 参数** | `mode`（选填）/ `keyword`（选填，策略名称模糊搜索）/ `page`（默认1）/ `size`（默认20） |
| **返回** | `PageVO<BacktestListItemVO>` |

**BacktestListItemVO 字段**（与 list.html 表格列一一对应）：

| 字段 | 类型 | 说明 | list.html 中的列 |
|------|------|------|-----------------|
| id | Long | 回测 ID | 第一列隐藏字段（用于点击行跳转） |
| strategyName | String | 策略名称 | 「策略名称」列 |
| strategyVersion | Integer | 策略版本号 | 策略名称旁的小标签（v1） |
| mode | String | `SINGLE` / `GRID_SEARCH` / `WALK_FORWARD` | 「模式」列（显示为「单次」「参数优化」「Walk-forward」中文标签） |
| startDate | String | 回测开始日期 | 「回测区间」列的起始部分 |
| endDate | String | 回测结束日期 | 「回测区间」列的结束部分 |
| universeType | String | 选股范围类型 | 「选股范围」列 |
| universeCount | Integer | 实际股票数量 | 「股票数」列 |
| totalReturnPct | BigDecimal | 总收益率（小数，前端显示为 %） | 「总收益」列（带颜色：绿涨红跌） |
| sharpeRatio | BigDecimal | 夏普比率 | 「夏普」列 |
| maxDrawdownPct | BigDecimal | 最大回撤 | 「最大回撤」列（红色） |
| winRate | BigDecimal | 胜率（0~1） | 「胜率」列 |
| status | String | `RUNNING` / `SUCCESS` / `FAILED` | 「状态」列（RUNNING 带脉冲动画，FAILED 带 hover 查看 errorMessage） |
| createdAt | String | 创建时间 `YYYY-MM-DD HH:MM:SS` | 「创建时间」列 |
| computeDurationMs | Integer | 计算耗时（毫秒） | 可在状态为 SUCCESS 时显示 |

#### 请求示例

```http
GET /api/backtest?mode=SINGLE&keyword=双均线&page=1&size=20
Authorization: Bearer <token>
```

#### 成功响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "total": 36,
    "page": 1,
    "size": 20,
    "items": [
      {
        "id": 42,
        "strategyName": "双均线策略",
        "strategyVersion": 2,
        "mode": "SINGLE",
        "startDate": "2024-01-01",
        "endDate": "2024-12-31",
        "universeType": "INDEX_300",
        "universeCount": 300,
        "totalReturnPct": 0.2453,
        "sharpeRatio": 1.82,
        "maxDrawdownPct": -0.0865,
        "winRate": 0.583,
        "status": "SUCCESS",
        "createdAt": "2025-01-15 14:32:08",
        "computeDurationMs": 3250
      },
      {
        "id": 41,
        "strategyName": "双均线策略",
        "strategyVersion": 1,
        "mode": "GRID_SEARCH",
        "startDate": "2024-01-01",
        "endDate": "2024-12-31",
        "universeType": "INDEX_300",
        "universeCount": 300,
        "totalReturnPct": 0.3125,
        "sharpeRatio": 2.15,
        "maxDrawdownPct": -0.0732,
        "winRate": 0.621,
        "status": "RUNNING",
        "createdAt": "2025-01-15 14:28:15",
        "computeDurationMs": null
      }
    ]
  }
}
```

---

### 2.3 获取回测记录详情

> **对应原型图**：所有报告页的顶部概要卡片（策略名称、模式、日期、核心指标摘要）

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}` |
| **路径参数** | `id` — 回测 ID |
| **鉴权** | 需要登录（且只能访问自己的回测） |
| **返回** | `BacktestVO`（结构比 list item 更完整，含配置参数） |

**BacktestVO 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 回测 ID |
| strategyId | Long | 策略 ID |
| strategyName | String | 策略名称 |
| strategyVersion | Integer | 策略版本号 |
| mode | String | SINGLE / GRID_SEARCH / WALK_FORWARD |
| status | String | RUNNING / SUCCESS / FAILED |
| startDate / endDate | String | 回测日期范围 |
| universeType | String | 选股范围类型 |
| universeCodes | String[] | 自定义模式的股票代码（数组） |
| universeCount | Integer | 股票数量 |
| initialCash | BigDecimal | 初始资金 |
| commissionPct | BigDecimal | 佣金率 |
| slippagePct | BigDecimal | 滑点 |
| rebalanceFrequencyDays | Integer | 调仓频率 |
| maxPositions | Integer | 最大持仓数 |
| maxSinglePositionPct | BigDecimal | 单票最大仓位 |
| benchmarkEnabled | Boolean | 是否启用基准对比 |
| optimizationMetric | String | 优化目标指标（GRID/WF 模式） |
| totalReturnPct | BigDecimal | 核心指标：总收益率 |
| sharpeRatio | BigDecimal | 核心指标：夏普比率 |
| maxDrawdownPct | BigDecimal | 核心指标：最大回撤 |
| winRate | BigDecimal | 核心指标：胜率 |
| tradesCount | Integer | 总交易次数 |
| errorMessage | String | 失败原因（status=FAILED 时） |
| computeDurationMs | Integer | Python 计算耗时 |
| createdAt | String | 创建时间 |
| completedAt | String | 完成时间 |

---

### 2.4 轻量状态查询（轮询用）

> **对应原型图**：config.html 提交后的「正在计算...」状态提示 / report.html 的加载状态

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/status` |
| **用途** | 前端轮询检查回测任务状态（3 秒间隔），避免拉取完整详情 |
| **返回** | `{status: String, errorMessage: String, completedAt: String}` |

**返回字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| status | String | `RUNNING` / `SUCCESS` / `FAILED` |
| errorMessage | String | 失败原因（仅 FAILED 时） |
| completedAt | String | 完成时间（仅 SUCCESS/FAILED 时） |

**请求示例**：
```http
GET /api/backtest/42/status
```

**响应示例（RUNNING → SUCCESS 两个时间点）**：
```jsonc
// t=0s  初始
{ "code": 200, "message": "ok", "data": { "status": "RUNNING", "errorMessage": null, "completedAt": null } }

// t=8s  完成
{ "code": 200, "message": "ok", "data": { "status": "SUCCESS", "errorMessage": null, "completedAt": "2025-01-15 14:32:16" } }
```

---

### 2.5 删除回测记录

> **对应原型图**：backtest-list.html 每行的「删除」按钮（hover 行时显示）

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `DELETE` |
| **路径** | `/api/backtest/{id}` |
| **鉴权** | 需要登录（只能删除自己的回测） |
| **业务限制** | `status=RUNNING` 的回测不允许删除，返回 409 |
| **级联操作** | 删除 quant_backtest_report / grid_result / wf_result 的关联记录（通过 MyBatis-Plus 手动删除，或依赖 ON DELETE CASCADE） |
| **返回** | `null`（删除成功返回空 data） |

**失败响应示例（删除 RUNNING 任务）**：
```jsonc
{
  "code": 409,
  "message": "运行中的回测不允许删除",
  "data": null
}
```

---

### 2.6 获取单次回测完整报告

> **对应原型图**：backtest-report.html（全页数据）
> **前端交互**：
>   1. 进入 report.html 页面，URL 参数 `?id=42`
>   2. 调用 GET /api/backtest/42 获取概要信息（顶部卡片）
>   3. 调用 GET /api/backtest/42/report 获取图表数据
>   4. 使用 ECharts 渲染：
>      - 净值曲线双折线图（strategy vs benchmark）
>      - 回撤曲线面积图
>      - 月度收益率热力图（color scale）
>   5. 交易流水表格使用独立接口 2.7 分页获取

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/report` |
| **路径参数** | `id` — 回测 ID |
| **鉴权** | 需要登录 |
| **返回** | `BacktestReportVO` |

**BacktestReportVO 字段**：

| 字段 | 类型 | 说明 | report.html 中的使用位置 |
|------|------|------|------------------------|
| backtest | BacktestVO | 回测概要信息（含核心指标） | 页面顶部「概要信息」卡片 |
| equityCurve | PointVO[] | 策略净值曲线点数组 → `[{date, value}]` | 净值曲线双折线图（策略线） |
| benchmarkCurve | PointVO[] | 基准净值曲线点数组 → `[{date, value}]` | 净值曲线双折线图（基准线） |
| drawdownCurve | DrawdownPointVO[] | 回撤曲线点 → `[{date, drawdownPct}]` | 回撤曲线面积图 |
| positions | PositionSnapshotVO[] | 调仓日持仓快照 → `[{date, positions:[{tsCode, name, shares, value, pnl}]}]` | 持仓变化图（如有） |
| metrics | Object | 全量指标（JSON 反序列化后的对象） | 「详细指标」表格区块 |
| monthlyReturns | Object | 月度收益率矩阵 → `{"2024-01": 0.021, ...}` | 月度收益率热力图（色块渲染） |

**PointVO 内嵌结构**：
| 字段 | 类型 |
|------|------|
| date | String |
| value | BigDecimal |

**DrawdownPointVO 内嵌结构**：
| 字段 | 类型 |
|------|------|
| date | String |
| drawdownPct | BigDecimal |

**metrics 对象字段**（来自 Python 计算引擎的输出）：
| 字段 | 类型 | 说明 |
|------|------|------|
| annualizedReturnPct | BigDecimal | 年化收益率 |
| volatility | BigDecimal | 年化波动率 |
| sortinoRatio | BigDecimal | 索提诺比率 |
| calmarRatio | BigDecimal | 卡尔马比率 |
| profitLossRatio | BigDecimal | 盈亏比 |
| maxSingleProfitPct | BigDecimal | 最大单笔盈利 |
| avgHoldingDays | BigDecimal | 平均持仓天数 |
| benchmarkReturnPct | BigDecimal | 基准收益率（沪深300） |
| excessReturnPct | BigDecimal | 超额收益率（策略 - 基准） |

#### 响应示例（节选）

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "backtest": {
      "id": 42,
      "strategyName": "双均线策略",
      "mode": "SINGLE",
      "totalReturnPct": 0.2453,
      "sharpeRatio": 1.82,
      "maxDrawdownPct": -0.0865,
      "winRate": 0.583,
      "tradesCount": 128,
      "..."
    },
    "equityCurve": [
      { "date": "2024-01-02", "value": 1000000 },
      { "date": "2024-01-03", "value": 1002350 },
      { "date": "2024-01-04", "value": 998500 },
      "..."
    ],
    "benchmarkCurve": [
      { "date": "2024-01-02", "value": 3850 },
      "..."
    ],
    "drawdownCurve": [
      { "date": "2024-01-02", "drawdownPct": 0 },
      "..."
    ],
    "metrics": {
      "annualizedReturnPct": 0.265,
      "volatility": 0.145,
      "sortinoRatio": 2.31,
      "calmarRatio": 3.06,
      "profitLossRatio": 1.85,
      "maxSingleProfitPct": 0.123,
      "avgHoldingDays": 18.5,
      "benchmarkReturnPct": 0.128,
      "excessReturnPct": 0.117
    },
    "monthlyReturns": {
      "2024-01": 0.021,
      "2024-02": 0.035,
      "2024-03": -0.015,
      "..."
    }
  }
}
```

---

### 2.7 交易流水分页

> **对应原型图**：backtest-report.html 底部「交易流水」表格（支持分页浏览）
> **说明**：交易流水可能达数百条，不放在 report 接口中返回，独立分页接口降低单次响应大小

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/trades` |
| **Query 参数** | `page`（默认1）/ `size`（默认20，最大100） |
| **返回** | `PageVO<TradeItemVO>` |

**TradeItemVO 字段**（与 report.html 表格列对齐）：

| 字段 | 类型 | 表格列 |
|------|------|--------|
| date | String | 日期 |
| tsCode | String | 股票代码 |
| stockName | String | 股票名称 |
| action | String | `BUY` 买入 / `SELL` 卖出 |
| price | BigDecimal | 成交价 |
| qty | Integer | 数量（股） |
| amount | BigDecimal | 成交金额 |
| pnl | BigDecimal | 盈亏金额（SELL 时有值） |
| pnlPct | BigDecimal | 盈亏比例（SELL 时有值） |
| reason | String | 卖出原因：`STOP_LOSS` / `TAKE_PROFIT` / `MAX_HOLDING_DAYS` / `SIGNAL` / `REBALANCE` |

#### 响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "total": 128,
    "page": 1,
    "size": 20,
    "items": [
      {
        "date": "2024-01-15",
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "action": "BUY",
        "price": 1650.00,
        "qty": 100,
        "amount": 165000.00,
        "pnl": null,
        "pnlPct": null,
        "reason": null
      },
      {
        "date": "2024-01-25",
        "tsCode": "600519.SH",
        "stockName": "贵州茅台",
        "action": "SELL",
        "price": 1720.50,
        "qty": 100,
        "amount": 172050.00,
        "pnl": 7050.00,
        "pnlPct": 0.0427,
        "reason": "TAKE_PROFIT"
      }
    ]
  }
}
```

---

### 2.8 参数优化结果分页

> **对应原型图**：backtest-grid.html（参数组合表格 / 散点图 / 热力图数据）
> **前端交互**：
>   1. 表格区：按排名顺序展示每组参数 + 指标（前 3 名高亮）
>   2. 散点图：X轴=某参数值，Y轴=优化目标指标（前端可切换参数）
>   3. 热力图：双参数组合的指标值色块（前端从完整数据集自动生成）
>   4. 「应用最优参数到策略」按钮 → 调用 2.9

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/grid-results` |
| **Query 参数** | `page`（默认1）/ `size`（默认20，最大100） |
| **返回** | `PageVO<GridResultItemVO>` |

**GridResultItemVO 字段**（与 grid.html 表格列对齐）：

| 字段 | 类型 | 表格列 / 图表用途 |
|------|------|------------------|
| rank | Integer | 排名（1=最优）→ 表格第一列，前3名高亮 |
| paramValues | Object | 参数值 JSON 对象（key=参数路径，value=该组使用的参数值） → 表格中间多列 |
| metricValue | BigDecimal | 优化目标指标值 → 表格高亮列 + 散点图 Y 轴 |
| totalReturnPct | BigDecimal | 总收益率 → 表格列 |
| sharpeRatio | BigDecimal | 夏普比率 → 表格列 |
| maxDrawdownPct | BigDecimal | 最大回撤 → 表格列 |
| winRate | BigDecimal | 胜率 → 表格列 |
| tradesCount | Integer | 交易次数 → 表格列 |
| isBest | Boolean | 是否最优组合（= rank===1 等价，冗余字段便于前端判断高亮） |

**注意**：散点图/热力图如果需要全量数据（而非分页），前端可一次性请求 `size=1000` 获取全量组合；或在后端加 `?format=all` 参数返回全量数组而不是分页。

#### 响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "total": 125,
    "page": 1,
    "size": 20,
    "items": [
      {
        "rank": 1,
        "paramValues": {
          "buy_rules.conditions[0].left.params.period": 10,
          "sell_rules.stop_loss.percent": 0.08,
          "sell_rules.take_profit.percent": 0.15
        },
        "metricValue": 2.15,
        "totalReturnPct": 0.312,
        "sharpeRatio": 2.15,
        "maxDrawdownPct": -0.073,
        "winRate": 0.621,
        "tradesCount": 145,
        "isBest": true
      },
      {
        "rank": 2,
        "paramValues": {
          "buy_rules.conditions[0].left.params.period": 15,
          "sell_rules.stop_loss.percent": 0.07,
          "sell_rules.take_profit.percent": 0.15
        },
        "metricValue": 2.08,
        "totalReturnPct": 0.298,
        "sharpeRatio": 2.08,
        "maxDrawdownPct": -0.075,
        "winRate": 0.612,
        "tradesCount": 138,
        "isBest": false
      }
    ]
  }
}
```

---

### 2.9 应用最优参数到策略

> **对应原型图**：backtest-grid.html 顶部的「应用最优参数到策略」按钮（需确认弹窗）
> **说明**：联动策略管理模块，将本次优化找到的最优参数写回策略的参数 JSON 为新版本。
> 本模块只负责读取最优参数和发起写回请求，策略版本管理由策略管理模块处理。

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `PUT` |
| **路径** | `/api/backtest/{id}/apply-best-params` |
| **路径参数** | `id` — 回测 ID（必须是 mode=GRID_SEARCH 的任务） |
| **鉴权** | 需要登录 |
| **业务限制** | 回测 status 必须是 SUCCESS 且 mode=GRID_SEARCH |
| **后端流程** | 1. 读取 quant_backtest_grid_result 中 isBest=1 的记录 → 获取 paramValues<br>2. 调用策略管理模块的接口更新策略参数（如 PUT /api/strategy/{strategyId}/params）<br>3. 策略管理模块负责版本递增和历史版本保留 |
| **返回** | `{newVersion: Integer, updatedParams: Object}` |

#### 响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": {
    "newVersion": 3,
    "updatedParams": {
      "buy_rules.conditions[0].left.params.period": 10,
      "sell_rules.stop_loss.percent": 0.08,
      "sell_rules.take_profit.percent": 0.15
    }
  }
}
```

**失败响应示例（非 GRID_SEARCH 任务）**：
```jsonc
{
  "code": 400,
  "message": "仅参数优化（GRID_SEARCH）模式的回测支持应用最优参数",
  "data": null
}
```

---

### 2.10 Walk-forward 分段详情

> **对应原型图**：backtest-walk-forward.html 的「分段时间轴」+「每段最优参数对比表」
> **前端交互**：
>   1. 分段时间轴（垂直/水平时间线）：每段显示「训练期→验证期」日期范围
>   2. 每段最优参数对比表：对比各段 best_params 的参数值差异，用于评估参数稳定性
>   3. 每段指标对比：展示训练期 vs 验证期的指标值（如训练期夏普 2.5，验证期 1.2，存在过拟合风险）

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/wf-segments` |
| **路径参数** | `id` — 回测 ID（必须是 mode=WALK_FORWARD 的任务） |
| **返回** | `WfSegmentVO[]`（全部分段，不走分页，通常 5~20 段） |

**WfSegmentVO 字段**：

| 字段 | 类型 | 原型图位置 |
|------|------|-----------|
| segmentIndex | Integer | 分段序号（0 开始）→ 时间轴顺序 |
| inSampleStart | String | 训练期开始日期 → 时间轴「训练期」块 |
| inSampleEnd | String | 训练期结束日期 → 同上 |
| outSampleStart | String | 验证期开始日期 → 时间轴「验证期」块 |
| outSampleEnd | String | 验证期结束日期 → 同上 |
| bestParams | Object | 该段最优参数（与 grid_result.paramValues 同结构） → 参数稳定性对比表 |
| inSampleMetrics | Object | 训练期指标 → 时间轴每段的「训练期表现」 |
| outSampleMetrics | Object | 验证期指标 → 时间轴每段的「验证期表现」（核心关注点） |

**指标对象字段**（inSampleMetrics / outSampleMetrics 共用此结构）：

| 字段 | 类型 |
|------|------|
| totalReturnPct | BigDecimal |
| sharpeRatio | BigDecimal |
| maxDrawdownPct | BigDecimal |
| winRate | BigDecimal |
| tradesCount | Integer |

#### 响应示例

```jsonc
{
  "code": 200,
  "message": "ok",
  "data": [
    {
      "segmentIndex": 0,
      "inSampleStart": "2021-01-01",
      "inSampleEnd": "2021-12-31",
      "outSampleStart": "2022-01-01",
      "outSampleEnd": "2022-03-31",
      "bestParams": {
        "buy_rules.conditions[0].left.params.period": 10,
        "sell_rules.stop_loss.percent": 0.08
      },
      "inSampleMetrics": {
        "totalReturnPct": 0.285,
        "sharpeRatio": 2.15,
        "maxDrawdownPct": -0.065,
        "winRate": 0.621,
        "tradesCount": 52
      },
      "outSampleMetrics": {
        "totalReturnPct": 0.063,
        "sharpeRatio": 1.12,
        "maxDrawdownPct": -0.085,
        "winRate": 0.515,
        "tradesCount": 12
      }
    },
    {
      "segmentIndex": 1,
      "...": "..."
    }
  ]
}
```

---

### 2.11 Walk-forward 组合验证期表现

> **对应原型图**：backtest-walk-forward.html 顶部「组合验证期绩效」卡片
> **说明**：将所有分段的验证期按时间顺序拼接成一条完整的「组合净值曲线」，
> 计算整体指标（代表策略在滚动训练+验证模式下的真实外推能力）。
> 数据由 Python 计算引擎输出，存于 quant_backtest 主表的字段或 report 表的扩展字段中。
> 此处设计为独立接口以便前端单独拉取聚合数据。

| 项 | 说明 |
|----|------|
| **HTTP 方法** | `GET` |
| **路径** | `/api/backtest/{id}/wf-combined` |
| **路径参数** | `id` — 回测 ID（必须是 mode=WALK_FORWARD 的任务） |
| **返回** | `WfCombinedVO` |

**WfCombinedVO 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| totalReturnPct | BigDecimal | 组合验证期的总收益率 |
| sharpeRatio | BigDecimal | 组合验证期的夏普比率 |
| maxDrawdownPct | BigDecimal | 组合验证期的最大回撤 |
| winRate | BigDecimal | 组合验证期的胜率 |
| annualizedReturnPct | BigDecimal | 年化收益率 |
| volatility | BigDecimal | 年化波动率 |
| segmentCount | Integer | 分段总数 |
| avgTrainingReturnPct | BigDecimal | 所有训练期的平均总收益率（用于对比验证期） |
| overfittingWarning | Boolean | 是否存在过拟合风险（验证期指标显著低于训练期） |
| combinedEquityCurve | PointVO[] | 组合验证期的完整净值曲线 → 可用于 ECharts 渲染 |

---

## 3. Controller 代码骨架参考（Java Spring Boot）

> 与现有 WatchlistController 风格一致：@RestController + @RequestMapping + @RequiredArgsConstructor + ApiResponse 统一返回

```java
package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.BacktestService;
import com.arthur.stock.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 回测中心 REST API
 * 基路径: /api/backtest
 * 原型图: backtest-list.html / backtest-config.html / backtest-report.html /
 *        backtest-grid.html / backtest-walk-forward.html
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    // 2.1 创建回测任务
    @PostMapping
    public ApiResponse<BacktestCreateResultVO> createBacktest(@RequestBody BacktestCreateDTO dto) {
        return ApiResponse.success(backtestService.createBacktest(dto));
    }

    // 2.2 分页查询回测列表
    @GetMapping
    public ApiResponse<PageVO<BacktestListItemVO>> listBacktests(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(backtestService.listBacktests(mode, keyword, page, size));
    }

    // 2.3 获取回测详情
    @GetMapping("/{id}")
    public ApiResponse<BacktestVO> getBacktest(@PathVariable Long id) {
        return ApiResponse.success(backtestService.getBacktest(id));
    }

    // 2.4 轻量状态查询（轮询）
    @GetMapping("/{id}/status")
    public ApiResponse<BacktestStatusVO> getBacktestStatus(@PathVariable Long id) {
        return ApiResponse.success(backtestService.getStatus(id));
    }

    // 2.5 删除回测记录
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBacktest(@PathVariable Long id) {
        backtestService.deleteBacktest(id);
        return ApiResponse.success("删除成功", null);
    }

    // 2.6 获取单次回测完整报告
    @GetMapping("/{id}/report")
    public ApiResponse<BacktestReportVO> getBacktestReport(@PathVariable Long id) {
        return ApiResponse.success(backtestService.getReport(id));
    }

    // 2.7 交易流水分页
    @GetMapping("/{id}/trades")
    public ApiResponse<PageVO<TradeItemVO>> listTrades(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(backtestService.listTrades(id, page, size));
    }

    // 2.8 参数优化结果分页
    @GetMapping("/{id}/grid-results")
    public ApiResponse<PageVO<GridResultItemVO>> listGridResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(backtestService.listGridResults(id, page, size));
    }

    // 2.9 应用最优参数到策略
    @PutMapping("/{id}/apply-best-params")
    public ApiResponse<ApplyBestParamsResultVO> applyBestParams(@PathVariable Long id) {
        return ApiResponse.success("已应用最优参数到策略", backtestService.applyBestParams(id));
    }

    // 2.10 Walk-forward 分段详情
    @GetMapping("/{id}/wf-segments")
    public ApiResponse<List<WfSegmentVO>> getWfSegments(@PathVariable Long id) {
        return ApiResponse.success(backtestService.getWfSegments(id));
    }

    // 2.11 Walk-forward 组合验证期表现
    @GetMapping("/{id}/wf-combined")
    public ApiResponse<WfCombinedVO> getWfCombined(@PathVariable Long id) {
        return ApiResponse.success(backtestService.getWfCombined(id));
    }
}
```

---

## 4. 自检记录

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 所有接口基路径统一为 `/api/backtest` | ✅ | 与现有 `/api/watchlist` / `/api/auth` 风格一致 |
| HTTP 方法符合 REST 语义：POST 创建 / GET 查询 / PUT 更新 / DELETE 删除 | ✅ | 统一 |
| 统一返回 `ApiResponse<T>` 包装 | ✅ | 与现有 WatchlistController 一致 |
| JSON 字段命名为 camelCase（totalReturnPct / strategyId 等） | ✅ | 与 MyBatis-Plus 的映射策略一致 |
| 分页参数一致：page 从 1 开始 / size 默认 20 | ✅ | 便于前端复用分页组件 |
| 错误码尽量复用现有（BAD_REQUEST/NOT_FOUND/FORBIDDEN/CONFLICT） | ✅ | 仅新增 3 个计算引擎相关错误码（2001/2002/2003） |
| 与原型图的表单字段一一对应 | ✅ | BacktestCreateDTO 的字段与 config.html 表单一一对齐 |
| 与原型图的表格列一一对应 | ✅ | BacktestListItemVO / GridResultItemVO / TradeItemVO 等与 list.html/report.html/grid.html 的表格列对齐 |
| 敏感数据隔离：每个接口加入 user_id 校验 | ✅ | 在 Service 层统一加入 `UserContext.getUserId()` 校验 |
| 与策略管理模块的联动清晰 | ✅ | 读取策略（只读）和应用最优参数（通过策略管理的接口）两个路径 |
| 接口文档的请求/响应示例完整 | ✅ | 每个 P0 接口都提供了至少 1 个完整 JSON 示例 |
