# 003 策略管理 — HTTP API 接口设计

> 参考资料索引：
> - 01-main-design.md（本模块主设计方案）
> - 02-db-design.md（数据库设计）
> - PRD：`sdlc/prd/003-策略管理/003-策略管理PRD.md`
> - Schema 参考：`sdlc/prd/003-策略管理/策略配置Schema.md`
> - 原型图：
>   - `sdlc/prd/003-策略管理/prototype/strategy-list.html`（列表 + 筛选 + 快捷动作）
>   - `sdlc/prd/003-策略管理/prototype/strategy-editor.html`（核心编辑器）
>   - `sdlc/prd/003-策略管理/prototype/strategy-versions.html`（版本时间线 + 对比 + 回滚）
>   - `sdlc/prd/003-策略管理/prototype/universe-config.html`（选股范围 + 预览数量）
>   - `sdlc/prd/003-策略管理/prototype/rule-tree-debug.html`（JSON 调试面板）

---

## 0. 现有接口复用检查（基于实时扫描结果）

| 现有 Controller | 方法 | 路径 | 复用决策 | 说明 |
|----------------|------|------|---------|------|
| `KlineController` | `GET` | `/api/kline/{stockCode}` | 参考结构 | 返回风格一致的 `ApiResponse<List<KlineDataVO>>`；本模块 StrategyController 沿用同样的 `@RestController + @RequestMapping + @RequiredArgsConstructor` 风格 |
| `StockBasicController` | `GET` | `/api/stocks/*` | 内部调用 | `StrategyService.estimateUniverseCount()` 内部调用 `StockBasicService` 查询 stock_basic |
| `WatchlistController` | `GET` | `/api/watchlist` | 内部调用 | 当 `universe=WATCHLIST` 时，StrategyService 内部使用 WatchlistService 读取用户自选股 |
| `SearchController` | `GET` | `/api/search/*` | 不直接复用 | 前端使用 SearchController 联想股票代码，但不直接复用策略 API |
| `AuthController` | `POST/GET` | `/api/auth/...` | 直接复用 | 登录态与 `@RequireAdmin` 切面保持一致；所有写接口需 `@RequireAdmin` |
| `PageController` | `GET` | `/pages/*` | 扩展路由 | 新增 5 个页面路由 `/pages/strategy/list`, `/pages/strategy/editor`, `/pages/strategy/versions`, `/pages/strategy/universe-config`, `/pages/strategy/rule-tree-debug` |
| **StrategyController（新增）** | 多方法 | `/api/strategies/*` | **新增** | 策略 REST 接口（CRUD + 版本 + 状态 + 校验 + 解析 + 模拟 + 对比） |
| **StrategyDebugController（新增）** | 多方法 | `/api/strategies/debug/*` | **新增** | 调试面板专用接口（parse/simulate/diff）；由 rule-tree-debug.html 调用 |
| **FactorController（来自 002 因子库）** | `GET` | `/api/factors/registry` | **复用** | StrategyService.validateRuleTreeJson() 通过 FactorRegistryCache 校验因子合法性 |

---

## 1. 通用约定

- **根路径**：
  - Java（前端调用）：`/api/strategies`, `/api/strategies/debug/*`
  - Python（Java 反向调用）：`/api/compute/strategy/*`
- **统一返回结构**：
  ```json
  { "code": 200, "message": "success", "data": { ... } }
  ```
  - `code=200` 表示成功；非 200 与 `ErrorCode.java` 中一致
  - `data` 为业务数据（对象 / 数组 / 分页对象）
- **Content-Type**：`application/json`
- **鉴权**：
  - `GET /api/strategies`, `GET /api/strategies/{id}`：登录即可（需要 `@RequireLogin`）
  - `GET /api/strategies/{id}/versions`, `GET /api/strategies/{id}/versions/{v}`：登录即可
  - `POST /api/strategies/debug/*`（parse/simulate/diff）：登录即可
  - `POST /api/strategies`（创建）/`PUT /api/strategies/{id}`（更新草稿）/`POST /api/strategies/{id}/versions`（保存版本）/`POST /api/strategies/{id}/versions/{v}/rollback`（回滚）/`POST /api/strategies/{id}/status`（更新状态）/`DELETE /api/strategies/{id}`（删除）：**需 `@RequireAdmin`**
  - Python 内部接口 `/api/compute/strategy/*`：无鉴权（仅本机 127.0.0.1 访问，不对外暴露）
- **分页**：`GET /api/strategies` 支持 `page`/`size` 参数；版本列表/其他列表不强制分页（预期版本 < 100）
- **JSON 字段强制小驼峰**：`strategyKey` / `name` / `description` / `category` / `status` / `buyRules` / `sellRules` / `positionSizing` / `universe` / `universeFilter` / `preFilters` / `createdAt` / `updatedAt` / `changeNote` / `backtestId` / `factorSpecs` / `paramPaths` / `leftJson` / `rightJson` / `tsCode` / `tradeDate` / `lookbackHint` / `resultKey`
- **禁止下划线**出现在 JSON 请求/响应字段中
- **分页响应结构**（沿用现有模式）：
  ```json
  { "code": 200, "message": "success", "data": { "total": 12, "page": 1, "size": 20, "list": [...] } }
  ```
- **Java ↔ Python 通信协议**：沿用 PRD §3.1；外层 `{ taskId, timestamp(秒), payload:{...} }`；内层 `{ code, message, data:{...} }`

---

## 2. 接口总览

| # | 方法 | 路径（Java） | 路径（Python） | 描述 | 权限 | 对应原型图页面的操作 |
|---|------|-------------|---------------|------|------|----------------------|
| 1 | `GET` | `/api/strategies?keyword=&status=&category=&page=&size=` | — | 策略列表（卡片 + 搜索 + 状态/分类筛选 + 分页） | 登录 | strategy-list.html |
| 2 | `GET` | `/api/strategies/{id}` | — | 策略详情（含完整 JSON） | 登录 | strategy-editor.html 打开 |
| 3 | `POST` | `/api/strategies` | — | 创建新策略（默认状态 DRAFT） | admin | strategy-editor.html 新建策略 |
| 4 | `PUT` | `/api/strategies/{id}` | — | 更新策略主表（保存草稿） | admin | strategy-editor.html 「保存草稿」按钮 |
| 5 | `POST` | `/api/strategies/{id}/versions` | — | 保存版本（将当前主表的 JSON 快照写入版本表） | admin | strategy-editor.html 「保存为版本 N+1」按钮 |
| 6 | `GET` | `/api/strategies/{id}/versions` | — | 版本时间线（按 created_at DESC 排序） | 登录 | strategy-versions.html |
| 7 | `GET` | `/api/strategies/{id}/versions/{v}` | — | 获取某版本的完整规则快照 | 登录 | strategy-versions.html 版本详情 |
| 8 | `POST` | `/api/strategies/{id}/versions/{v}/rollback` | — | 回滚到指定版本（写主表 + 新建"回滚记录"版本） | admin | strategy-versions.html 「回滚」按钮 |
| 9 | `POST` | `/api/strategies/{id}/status` | — | 更新策略状态（DRAFT / ACTIVE / ARCHIVED） | admin | strategy-list.html 「归档/恢复」按钮 |
| 10 | `DELETE` | `/api/strategies/{id}` | — | 删除策略（仅 DRAFT 且未被回测引用时可删） | admin | strategy-list.html 「删除」按钮 |
| 11 | `POST` | `/api/strategies/debug/parse` | `POST /api/compute/strategy/parse` | 解析规则树 JSON，返回去重的因子规格 + 参数路径 | 登录 | rule-tree-debug.html 「校验并提取」按钮 |
| 12 | `POST` | `/api/strategies/debug/simulate` | `POST /api/compute/strategy/simulate` | 单 bar 信号模拟（tsCode + tradeDate + 策略 JSON → 每一个 compare 节点的布尔值） | 登录 | rule-tree-debug.html 「模拟信号」按钮 |
| 13 | `POST` | `/api/strategies/debug/diff` | — | JSON diff（Java 侧实现，文本行级） | 登录 | strategy-versions.html 双版本对比 |
| 14 | `POST` | `/api/strategies/debug/validate` | — | 策略 JSON 基础校验（factorKey/params/结构），返回 true/false + 错误消息 | 登录 | strategy-editor.html 「实时校验」 |
| 15 | `POST` | `/api/strategies/debug/estimate-universe` | — | 估算选股范围覆盖的股票数量（预览用） | 登录 | universe-config.html 「预览」按钮 |

---

## 3. 逐接口详细设计

---

### 3.1 GET /api/strategies

> 对应原型图：strategy-list.html
> 前端触发：页面初始化时调一次，搜索框/筛选 chip 变化时重新调

#### 请求参数（Query）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `keyword` | String | 否 | 搜索关键字（匹配 name / description / strategyKey） |
| `status` | String | 否 | 筛选：`DRAFT` / `ACTIVE` / `ARCHIVED`；不传则全部 |
| `category` | String | 否 | 筛选：`TREND` / `MEAN_REVERT` / `MOMENTUM` / `CUSTOM` / `SCREEN_DRAFT`；不传则全部 |
| `page` | int | 否 | 页码，默认 1 |
| `size` | int | 否 | 每页条数，默认 20 |

#### 响应 `data`

```json
{
  "total": 12,
  "page": 1,
  "size": 20,
  "list": [
    {
      "id": 1,
      "strategyKey": "demo-ma-crossover",
      "name": "MA双均线交叉",
      "description": "5日上穿20日买入",
      "category": "TREND",
      "status": "DRAFT",
      "createdAt": "2025-07-10 14:30:00",
      "updatedAt": "2025-07-10 15:00:00",
      "versionCount": 3,
      "backtestLastSharpe": 1.85,
      "backtestLastTotalReturn": 0.23,
      "backtestLastDate": "2025-07-09 10:12:34"
    }
  ]
}
```

> `versionCount` / `backtestLast*`：从主表直接读取；若无数据则 `null`
> 排序：`updated_at DESC`

#### 错误

| 场景 | code | message |
|------|------|---------|
| page < 1 | 400 | `请求参数错误` |

---

### 3.2 GET /api/strategies/{id}

> 对应原型图：strategy-editor.html（页面加载时拉取详情填充表单）

#### 请求参数（Path）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 策略 ID |

#### 响应 `data`

```json
{
  "id": 1,
  "strategyKey": "demo-ma-crossover",
  "name": "MA双均线交叉",
  "description": "5日上穿20日买入，5日下穿20日卖出",
  "category": "TREND",
  "status": "DRAFT",
  "buyRules": {
    "operator": "AND",
    "conditions": [
      {
        "type": "compare",
        "left": { "factor": "MA", "params": { "timeperiod": 5 } },
        "comparator": "cross_up",
        "right": { "factor": "MA", "params": { "timeperiod": 20 } }
      }
    ]
  },
  "sellRules": {
    "conditions": [
      {
        "type": "compare",
        "left": { "factor": "MA", "params": { "timeperiod": 5 } },
        "comparator": "cross_down",
        "right": { "factor": "MA", "params": { "timeperiod": 20 } }
      }
    ],
    "stopLossPercent": 5.0,
    "takeProfitPercent": 15.0,
    "maxHoldingDays": 20
  },
  "positionSizing": {
    "singleMaxPosition": 0.2,
    "maxPositions": 5,
    "minHoldingDays": 3
  },
  "universe": "INDEX_300",
  "universeFilter": null,
  "preFilters": {
    "excludeST": true,
    "excludeNewSharesDays": 60,
    "minAvgAmount": 50000000
  },
  "createdAt": "2025-07-10 14:30:00",
  "updatedAt": "2025-07-10 15:00:00",
  "createdBy": "admin",
  "tags": [ "低波动", "趋势跟踪" ],
  "remark": null,
  "backtestLastId": 12,
  "backtestLastSharpe": 1.85,
  "backtestLastTotalReturn": 0.23,
  "backtestLastDate": "2025-07-09 10:12:34",
  "versionCount": 3
}
```

> 注：`buyRules` / `sellRules` / `positionSizing` / `preFilters` / `tags` 内部使用嵌套对象/数组，由 Java 侧 `Jackson` 自动序列化为 JSON；

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |

---

### 3.3 POST /api/strategies

> 对应原型图：strategy-editor.html「新建策略 + 保存草稿」首次点击

#### 请求 `body`

```json
{
  "strategyKey": "demo-ma-crossover",
  "name": "MA双均线交叉",
  "description": "5日上穿20日买入",
  "category": "TREND",
  "status": "DRAFT",
  "buyRules": { "operator": "AND", "conditions": [ ... ] },
  "sellRules": { "conditions": [ ... ], "stopLossPercent": 5.0, "takeProfitPercent": 15.0, "maxHoldingDays": 20 },
  "positionSizing": { "singleMaxPosition": 0.2, "maxPositions": 5, "minHoldingDays": 3 },
  "universe": "INDEX_300",
  "universeFilter": null,
  "preFilters": { "excludeST": true, "excludeNewSharesDays": 60, "minAvgAmount": 50000000 },
  "tags": [ "低波动" ],
  "remark": null
}
```

#### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `strategyKey` | 是 | String | 业务代码，全局唯一；前端自动生成或用户填写 |
| `name` | 是 | String | 策略名称，长度 ≤ 256 |
| `description` | 否 | String | 描述 |
| `category` | 是 | String | `TREND` / `MEAN_REVERT` / `MOMENTUM` / `CUSTOM` / `SCREEN_DRAFT` |
| `status` | 否 | String | 默认 `DRAFT` |
| `buyRules` | 是 | Object | 买入规则树（JSON 对象，将序列化为 TEXT 存储） |
| `sellRules` | 是* | Object | 卖出规则树（*当 category=SCREEN_DRAFT 时可省略，默认空；其他必填） |
| `positionSizing` | 否 | Object | 仓位管理 JSON |
| `universe` | 是 | String | `ALL` / `INDEX_300` / `INDEX_500` / `WATCHLIST` / `CUSTOM_CODES`，默认 ALL |
| `universeFilter` | 否 | Array<String> | 自定义代码列表 JSON，仅 `universe=CUSTOM_CODES` 时生效 |
| `preFilters` | 否 | Object | 前置过滤 JSON：`excludeST / excludeNewSharesDays / minAvgAmount` |
| `tags` | 否 | Array<String> | 标签数组 |
| `remark` | 否 | String | 内部备注 |

#### 响应 `data`

```json
{ "id": 1, "strategyKey": "demo-ma-crossover", "status": "DRAFT", "createdAt": "2025-07-10 14:30:00" }
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| `strategyKey` 已存在 | 409 | `策略代码已存在` |
| `name` 为空 | 400 | `请求参数错误` |
| `category` 不在枚举中 | 400 | `分类不合法` |
| `buyRules` 为空或 JSON 非法 | 400 | `买入规则树非法` |
| `sellRules` 为空（非 SCREEN_DRAFT） | 400 | `卖出规则树不能为空` |
| 引用了不存在的因子 | 400 | `规则树引用了未注册的因子: XXX` |
| `universe` 不在枚举中 | 400 | `选股范围不合法` |

---

### 3.4 PUT /api/strategies/{id}

> 对应原型图：strategy-editor.html「保存草稿」按钮

#### 请求 `body`（字段同 POST 版本，但全部可选；非空字段会更新）

```json
{
  "name": "MA双均线交叉(修正)",
  "buyRules": { ... },
  "status": "DRAFT"
}
```

> 字段缺失时，保留原数据库中的值；空字符串视为「清空」该字段（只对 description/remark/tags/universeFilter 允许）

#### 响应 `data`

```json
{ "id": 1, "strategyKey": "demo-ma-crossover", "status": "DRAFT", "updatedAt": "2025-07-10 15:00:00" }
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |
| strategyKey 与其他策略冲突 | 409 | `策略代码已存在` |
| 引用了不存在的因子 | 400 | `规则树引用了未注册的因子: XXX` |

---

### 3.5 POST /api/strategies/{id}/versions

> 对应原型图：strategy-editor.html「保存为版本 N+1」按钮

#### 请求 `body`

```json
{
  "changeNote": "优化入场条件",
  "backtestId": 12
}
```

#### 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `changeNote` | 否 | 变更备注（≤ 256 字符） |
| `backtestId` | 否 | 关联的回测 ID（来自 004 回测中心） |

> 快照数据（buy_rules/sell_rules/position_sizing/universe/universe_filter/pre_filters）来自当前 `quant_strategy` 主表，不由前端传入；

#### 响应 `data`

```json
{
  "id": 101,
  "strategyId": 1,
  "version": 3,
  "createdAt": "2025-07-10 15:10:00"
}
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |
| 当前主表 buy_rules 为空 | 400 | `当前策略没有规则可保存版本` |

---

### 3.6 GET /api/strategies/{id}/versions

> 对应原型图：strategy-versions.html 版本时间线

#### 请求参数（Path）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 策略 ID |

#### 响应 `data`

```json
[
  {
    "id": 101,
    "version": 3,
    "changeNote": "优化入场条件",
    "createdAt": "2025-07-10 15:10:00",
    "backtestId": 12,
    "isRollbackFrom": null
  },
  {
    "id": 100,
    "version": 2,
    "changeNote": "加入止损",
    "createdAt": "2025-07-10 14:50:00",
    "backtestId": null,
    "isRollbackFrom": null
  },
  {
    "id": 99,
    "version": 1,
    "changeNote": "初版",
    "createdAt": "2025-07-10 14:35:00",
    "backtestId": null,
    "isRollbackFrom": null
  }
]
```

> 排序：`version DESC` 或 `created_at DESC`，两者等价；

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |

---

### 3.7 GET /api/strategies/{id}/versions/{v}

> 对应原型图：strategy-versions.html 查看某版本的完整快照 + JSON

#### 请求参数（Path）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 策略 ID |
| `v` | int | 是 | 版本号（1, 2, 3 ...） |

#### 响应 `data`

```json
{
  "id": 101,
  "strategyId": 1,
  "version": 3,
  "buyRules": { "operator": "AND", "conditions": [ ... ] },
  "sellRules": { "conditions": [ ... ], "stopLossPercent": 5.0, "takeProfitPercent": 15.0, "maxHoldingDays": 20 },
  "positionSizing": { "singleMaxPosition": 0.2, "maxPositions": 5, "minHoldingDays": 3 },
  "universe": "INDEX_300",
  "universeFilter": null,
  "preFilters": { "excludeST": true, "excludeNewSharesDays": 60, "minAvgAmount": 50000000 },
  "changeNote": "优化入场条件",
  "backtestId": 12,
  "createdAt": "2025-07-10 15:10:00",
  "createdBy": "admin",
  "isRollbackFrom": null
}
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |
| 版本号不存在 | 404 | `版本不存在` |

---

### 3.8 POST /api/strategies/{id}/versions/{v}/rollback

> 对应原型图：strategy-versions.html「回滚」按钮

#### 请求 `body`（空）

```json
{}
```

#### 响应 `data`

```json
{
  "id": 102,
  "strategyId": 1,
  "version": 4,
  "isRollbackFrom": 2,
  "createdAt": "2025-07-10 16:00:00"
}
```

> 逻辑：将版本 2 的规则回写主表；新建版本 4 作为"回滚记录"；版本 2/3 保留不动；

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 或版本号不存在 | 404 | `策略不存在` / `版本不存在` |
| 回滚版本与当前主表版本相同（由前端校验，后端也检查） | 409 | `不能回滚到当前正在使用的版本` |

---

### 3.9 POST /api/strategies/{id}/status

> 对应原型图：strategy-list.html 卡片「归档/恢复」按钮

#### 请求 `body`

```json
{
  "status": "ARCHIVED"
}
```

#### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `status` | 是 | String | `DRAFT` / `ACTIVE` / `ARCHIVED` |

#### 响应 `data`

```json
{ "id": 1, "status": "ARCHIVED", "updatedAt": "2025-07-10 16:05:00" }
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| status 不在枚举中 | 400 | `状态不合法` |
| id 不存在 | 404 | `策略不存在` |

---

### 3.10 DELETE /api/strategies/{id}

> 对应原型图：strategy-list.html 卡片「删除」按钮

#### 请求参数（Path）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | long | 是 | 策略 ID |

#### 响应 `data`

```json
{ "id": 1, "deleted": true }
```

#### 错误

| 场景 | code | message |
|------|------|---------|
| id 不存在 | 404 | `策略不存在` |
| 当前策略已被回测引用（quant_strategy_version 表存在 backtest_id） | 409 | `该策略已被回测引用，无法删除` |
| status != DRAFT | 409 | `仅草稿状态的策略可删除` |

---

### 3.11 POST /api/strategies/debug/parse

> 对应原型图：rule-tree-debug.html「校验并提取」按钮

#### 请求 `body`

```json
{
  "buyRules": { "operator": "AND", "conditions": [ ... ] },
  "sellRules": { "conditions": [ ... ], "stopLossPercent": 5.0, ... }
}
```

#### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `buyRules` | 是 | Object | 买入规则树 JSON |
| `sellRules` | 否 | Object | 卖出规则树 JSON（可为空） |

> Java 侧先做基础校验（结构合法 + factorKey 在 registry 存在），再调用 Python；

#### 响应 `data`

```json
{
  "factorSpecs": [
    { "factorKey": "MA", "params": { "timeperiod": 5 }, "outputIndex": null, "resultKey": "MA_5" },
    { "factorKey": "MA", "params": { "timeperiod": 20 }, "outputIndex": null, "resultKey": "MA_20" },
    { "factorKey": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "outputIndex": 1, "resultKey": "MACD_DIF" }
  ],
  "paramPaths": [
    { "path": "buyRules.conditions[0].left.params.timeperiod", "defaultValue": 5, "paramName": "MA短周期" },
    { "path": "buyRules.conditions[0].right.params.timeperiod", "defaultValue": 20, "paramName": "MA长周期" },
    { "path": "sellRules.stopLossPercent", "defaultValue": 5.0, "paramName": "止损百分比" }
  ],
  "warnings": [ "因子 MACD 的 output_index 不在 registry 输出范围(0..2)内" ],
  "lookbackHint": 30
}
```

> `factorSpecs` 按 factorKey + params 去重后的数组；
> `paramPaths` 为所有可调参数路径；前端可据此自动渲染参数配置表；
> `warnings` 为兼容性/潜在问题；
> `lookbackHint` 为规则树计算所需的最小历史 bar 数（Python 估算）；

#### 错误

| 场景 | code | message |
|------|------|---------|
| buyRules 为空或 JSON 非法 | 400 | `买入规则树结构错误` |
| Python 计算服务不可达 | 500 | `计算服务不可用` |

---

### 3.12 POST /api/strategies/debug/simulate

> 对应原型图：rule-tree-debug.html「模拟信号」按钮

#### 请求 `body`

```json
{
  "tsCode": "600000.SH",
  "tradeDate": "2025-03-15",
  "buyRules": { "operator": "AND", "conditions": [ ... ] },
  "sellRules": { "conditions": [ ... ], "stopLossPercent": 5.0, ... }
}
```

#### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `tsCode` | 是 | String | 股票代码，如 `600000.SH` |
| `tradeDate` | 是 | String | 交易日期，`YYYY-MM-DD` 或 `YYYYMMDD` |
| `buyRules` | 是 | Object | 买入规则树 JSON |
| `sellRules` | 否 | Object | 卖出规则树 JSON |

> Java 从 daily_quote 读取最近 120 根 bar OHLCV（若无数据则返回 400）；传给 Python 计算；

#### 响应 `data`

```json
{
  "tsCode": "600000.SH",
  "tradeDate": "2025-03-15",
  "buySignal": true,
  "sellSignal": false,
  "hitConditions": [
    { "path": "buyRules.conditions[0]", "hit": true, "leftValue": 10.5, "rightValue": 10.3, "comparator": "cross_up" },
    { "path": "buyRules.conditions[1]", "hit": true, "leftValue": 0.015, "rightValue": 0, "comparator": ">=" }
  ],
  "exitSignals": {
    "stopLossHit": false,
    "takeProfitHit": false,
    "maxHoldingDaysHit": false
  },
  "lookbackUsed": 120,
  "computeMs": 234
}
```

> `hitConditions` 为每个 compare 节点的命中情况；
> `buySignal` / `sellSignal` 为综合结果；
> `exitSignals` 为卖出规则的三个特殊节点命中情况；

#### 错误

| 场景 | code | message |
|------|------|---------|
| tsCode 在 daily_quote 无数据 | 400 | `tsCode 无行情数据` |
| buyRules 为空或 JSON 非法 | 400 | `买入规则树结构错误` |
| Python 计算服务不可达 | 500 | `计算服务不可用` |

---

### 3.13 POST /api/strategies/debug/diff

> 对应原型图：strategy-versions.html 双版本对比面板

#### 请求 `body`

```json
{
  "leftJson": "{\"operator\":\"AND\",\"conditions\":[{...}]}",
  "rightJson": "{\"operator\":\"AND\",\"conditions\":[{...}, {...}]}"
}
```

> Java 侧实现：字符串行级 diff（Python difflib 风格 / Java 自实现）；

#### 响应 `data`

```json
{
  "leftSize": 120,
  "rightSize": 180,
  "diffHunks": [
    {
      "lineStartLeft": 5,
      "lineStartRight": 5,
      "lines": [
        { "type": "equal", "leftLine": 5, "rightLine": 5, "text": "  \"conditions\": [" },
        { "type": "insert", "leftLine": null, "rightLine": 6, "text": "    { \"type\": \"compare\", ... }," },
        { "type": "equal", "leftLine": 6, "rightLine": 7, "text": "  ]" }
      ]
    }
  ]
}
```

> `type` ∈ `equal` / `insert` / `delete`

#### 错误

| 场景 | code | message |
|------|------|---------|
| leftJson / rightJson 为空 | 400 | `请求参数错误` |

---

### 3.14 POST /api/strategies/debug/validate

> 对应原型图：strategy-editor.html「实时校验」按钮（表单提交前即时检查）

#### 请求 `body`

```json
{
  "buyRules": { "operator": "AND", "conditions": [ ... ] },
  "sellRules": { "conditions": [ ... ], "stopLossPercent": 5.0, ... },
  "category": "TREND"
}
```

#### 响应 `data`

```json
{
  "valid": true,
  "errors": [],
  "warnings": [ "因子 MACD 参数与 registry 不符" ]
}
```

> 仅做基础校验（结构 + factorKey 合法性 + 必需字段），不调用 Python；

---

### 3.15 POST /api/strategies/debug/estimate-universe

> 对应原型图：universe-config.html「预览符合条件的股票数量」按钮

#### 请求 `body`

```json
{
  "universe": "INDEX_300",
  "universeFilter": null,
  "preFilters": { "excludeST": true, "excludeNewSharesDays": 60, "minAvgAmount": 50000000 }
}
```

#### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `universe` | 是 | String | `ALL` / `INDEX_300` / `INDEX_500` / `WATCHLIST` / `CUSTOM_CODES` |
| `universeFilter` | 否 | Array<String> | 自定义代码列表（仅 universe=CUSTOM_CODES 时使用） |
| `preFilters` | 否 | Object | 前置过滤 |

#### 响应 `data`

```json
{
  "universe": "INDEX_300",
  "totalInUniverse": 300,
  "afterPreFilters": 265,
  "excludedSt": 15,
  "excludedNewShares": 10,
  "excludedLowAmount": 10,
  "details": [
    { "tsCode": "600000.SH", "name": "浦发银行" }
  ]
}
```

> `details` 为前 20 个符合条件的股票（用于前端展示示例）；

#### 错误

| 场景 | code | message |
|------|------|---------|
| universe 不在枚举中 | 400 | `选股范围不合法` |
| universe=CUSTOM_CODES 且 universeFilter 为空 | 400 | `自定义选股范围需要提供代码列表` |
| INDEX_300/INDEX_500 需要 stock_basic 表存在对应列（is_index_300/is_index_500）；若列不存在 | 409 | `数据库缺少 INDEX_300/INDEX_500 标志列，请由管理员补齐` |

---

## 4. Java ↔ Python 协议（调试/解析/模拟）

Java 侧调用 Python 时的外层包格式（沿用 PRD §3.1）：

```json
{
  "taskId": "str-task-uuid",
  "timestamp": 1783820000,
  "payload": {
    "buyRules": { ... },
    "sellRules": { ... }
  }
}
```

Python 返回的内层包格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

---

## 5. Java 接口方法与路径对照（Controller 层速查）

| 方法签名（伪 Java） | HTTP 方法 | 路径 | 权限 |
|-------------------|----------|------|------|
| `list(String keyword, String status, String category, int page, int size)` | GET | `/api/strategies` | login |
| `getDetail(Long id)` | GET | `/api/strategies/{id}` | login |
| `create(StrategyCreateRequest req)` | POST | `/api/strategies` | admin |
| `update(Long id, StrategyUpdateRequest req)` | PUT | `/api/strategies/{id}` | admin |
| `saveVersion(Long id, VersionSaveRequest req)` | POST | `/api/strategies/{id}/versions` | admin |
| `listVersions(Long id)` | GET | `/api/strategies/{id}/versions` | login |
| `getVersion(Long id, int version)` | GET | `/api/strategies/{id}/versions/{v}` | login |
| `rollbackVersion(Long id, int version)` | POST | `/api/strategies/{id}/versions/{v}/rollback` | admin |
| `updateStatus(Long id, StrategyStatusRequest req)` | POST | `/api/strategies/{id}/status` | admin |
| `delete(Long id)` | DELETE | `/api/strategies/{id}` | admin |
| `parse(ParseRequest req)` | POST | `/api/strategies/debug/parse` | login |
| `simulate(SimulateRequest req)` | POST | `/api/strategies/debug/simulate` | login |
| `diff(DiffRequest req)` | POST | `/api/strategies/debug/diff` | login |
| `validate(ValidateRequest req)` | POST | `/api/strategies/debug/validate` | login |
| `estimateUniverse(EstimateUniverseRequest req)` | POST | `/api/strategies/debug/estimate-universe` | login |

---

## 6. PageController 扩展路由（Thymeleaf 模板路径）

| 路径 | 模板文件（相对于 `templates/`） | 说明 |
|------|--------------------------------|------|
| `/pages/strategy/list` | `pages/strategy/list.html` | 策略列表页 |
| `/pages/strategy/editor` | `pages/strategy/editor.html` | 策略编辑器（新建/编辑同页） |
| `/pages/strategy/versions` | `pages/strategy/versions.html` | 版本历史与对比 |
| `/pages/strategy/universe-config` | `pages/strategy/universe-config.html` | 选股范围配置（可内嵌为 editor Tab） |
| `/pages/strategy/rule-tree-debug` | `pages/strategy/rule-tree-debug.html` | JSON 调试面板 |

---

## 7. 接口自检

| 检查项 | 结果 |
|--------|------|
| 所有路径以 `/api/strategies/*` 或 `/api/strategies/debug/*` 开头 | ✅ |
| REST 方法合理（GET 查询 / POST 新建 / PUT 更新 / POST 状态变更 / DELETE 删除） | ✅ |
| JSON 字段全部 camelCase，无下划线 | ✅ |
| 统一返回 `ApiResponse<T>`，包含 `code/message/data` | ✅ |
| 分页响应 `{ total, page, size, list }` | ✅ |
| 错误码复用 BAD_REQUEST / NOT_FOUND / CONFLICT / 500 | ✅ |
| 写接口全部需 `@RequireAdmin` | ✅ |
| 查询接口需登录（`@RequireLogin`） | ✅ |
| 版本号不可变、不可修改 | ✅ |
| universe 枚举与 pre_filters 的字段结构与前端原型一致 | ✅ |
| Python 协议 JSON 字段名与 002 因子库风格一致 | ✅ |

