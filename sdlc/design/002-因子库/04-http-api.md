# 002 因子库 — HTTP API 接口设计

> 面向 Java `FactorGateway` 调用 Python 计算服务、以及前端通过 Java 代理获取因子元数据的两套接口。
>
> 设计依据：
> - PRD：`sdlc/prd/002-因子库/002-因子库PRD.md`
> - 因子清单：`sdlc/prd/002-因子库/20个标准股票因子.json`
> - 策略配置器 UI 位置：`sdlc/prd/003-策略管理/prototype/strategy-editor.html`（对应策略编辑器中"因子选择 + 参数输入"区块）
> - 选股条件编辑器 UI 位置：`sdlc/prd/005-选股模块/prototype/`（对应"指标选择 + 阈值输入"区块）

---

## 0. 现有接口复用检查（基于实时扫描结果）

| 现有 Controller | 方法 | 路径 | 复用决策 | 说明 |
|---|---|---|---|---|
| `KlineController` | `GET` | `/api/kline/{stockCode}` | 参考结构 | 返回风格一致的 `ApiResponse<List<KlineDataVO>>`；FactorController 沿用同样的 `@RestController + @RequestMapping + @RequiredArgsConstructor` 风格 |
| `StockBasicController` | `GET` | `/api/stocks/...` | 直接复用 | Java 侧 FactorController 内部调用 `StockBasicService` 校验 `tsCode` |
| `AuthController` | `POST/GET` | `/api/auth/...` | 直接复用 | 登录态与 `@RequireAdmin` 切面保持一致 |
| `SearchController` | `GET` | `/api/search/...` | 不直接复用 | 但 `/api/factors/preview` 内部可使用 `SearchService` 做股票代码联想提示（前端侧） |
| `PageController` | `GET` | `/pages/...` | 不使用 | 本模块无独立前端页面 |
| **FactorController（新）** | `GET` | `/api/factors/registry` | **新增** | 代理 Python registry 结果，含 1h Caffeine 缓存 |
| **FactorController（新）** | `POST` | `/api/factors/preview` | **新增** | 前端传入 tsCode + 因子引用列表 → Java 查 OHLCV → 调 Python → 返回结果 |
| **FactorController（新）** | `POST` | `/api/factors/registry/refresh` | **新增** | 管理员触发 Python registry 重新拉取，绕过缓存 |

---

## 1. 通用约定

- **根路径**
  - Java（前端调用）：`/api/factors/...`
  - Python（Java 反向调用）：`/api/compute/factors/...`
- **统一返回结构**：`{ "code": 200, "message": "success", "data": { ... } }`
  - `code=200` 表示成功；非 200 与 `ErrorCode.java` 中一致
  - `data` 为业务数据（对象 / 数组 / 分页对象）
- **Content-Type**：`application/json`
- **鉴权**
  - `GET /api/factors/registry`：登录即可（需要有用户上下文，但非管理员也可用；策略配置器普通用户能访问）
  - `POST /api/factors/preview`：登录即可
  - `POST /api/factors/registry/refresh`：需要 `@RequireAdmin`（由切面拦截）
  - Python 内部接口 `/api/compute/factors/*`：**无鉴权**（仅本机 127.0.0.1 访问，不对外暴露）
- **分页**：本模块不涉及分页；查询 `daily_quote` 时在 Java 内部使用 `LIMIT` 控制窗口大小（默认 120，可由前端 size 参数覆盖）
- **JSON 字段强制小驼峰**：`factorKey / displayName / outputLabels / defaultOutputIndex / lookbackHint / lookbackDefault / outputIndex / stockCode / computeMs`；**禁止下划线**
- **Python → Java 通信协议**：沿用 PRD §3.1；外层 `{ taskId, timestamp(秒), payload:{...} }`；内层 `{ code, message, data:{...} }`

---

## 2. 接口总览

| # | 方法 | 路径（Java） | 路径（Python） | 描述 | 权限 | 对应原型图页面的操作 |
|---|---|---|---|---|---|---|
| 1 | `GET` | `/api/factors/registry` | `GET /api/compute/factors/registry` | 获取完整因子元数据（分类、参数、输出列、预热数） | 登录 | 策略编辑器中"因子选择器"渲染；选股条件编辑器中"选择指标"下拉框渲染 |
| 2 | `POST` | `/api/factors/preview` | `POST /api/compute/factors` | 单股 + 多因子 + 时间窗口的批量计算，返回前端可直接绘图的数组 | 登录 | 策略编辑器中的"因子预览图"按钮点击；选股条件编辑器的"曲线预览" |
| 3 | `POST` | `/api/factors/registry/refresh` | 不直接调用（Java 内部调用 Python registry 并写缓存） | 管理员手动刷新因子注册表缓存（绕过 TTL） | `@RequireAdmin` | 系统设置页中的"刷新因子库"按钮 |

---

## 3. 逐接口详细设计

---

### 3.1 GET /api/factors/registry（Java → 前端代理）

> 对应原型图：`sdlc/prd/003-策略管理/prototype/strategy-editor.html` 的**左侧因子选择面板**
> 前端触发：页面初始化时自动调用一次，用于渲染「按分类分组的因子下拉列表」和「因子参数表」

#### 请求参数（Query）

无。

#### 响应 `data`

```json
{
  "factors": [
    {
      "factorKey": "MA",
      "displayName": "简单移动均线",
      "category": "TREND",
      "description": "对收盘价取 N 周期简单移动平均值",
      "params": [
        { "name": "timeperiod", "displayName": "周期", "type": "INT", "defaultValue": 5, "min": 1, "max": 500, "step": 1 }
      ],
      "inputs": ["close"],
      "multiOutput": false,
      "outputLabels": [],
      "defaultOutputIndex": 0,
      "lookbackHint": "timeperiod - 1",
      "lookbackDefault": 4
    },
    {
      "factorKey": "MACD",
      "displayName": "MACD 异同移动均线",
      "category": "MOMENTUM",
      "description": "DIF = EMA(12) - EMA(26)，DEA = EMA(DIF, 9)，HIST = DIF - DEA",
      "params": [
        { "name": "fastperiod", "displayName": "快线周期", "type": "INT", "defaultValue": 12, "min": 1, "max": 500, "step": 1 },
        { "name": "slowperiod", "displayName": "慢线周期", "type": "INT", "defaultValue": 26, "min": 1, "max": 500, "step": 1 },
        { "name": "signalperiod", "displayName": "信号线周期", "type": "INT", "defaultValue": 9, "min": 1, "max": 500, "step": 1 }
      ],
      "inputs": ["close"],
      "multiOutput": true,
      "outputLabels": ["DIF", "DEA", "HIST"],
      "defaultOutputIndex": 0,
      "lookbackHint": "slowperiod + signalperiod - 2",
      "lookbackDefault": 33
    }
  ],
  "count": 20,
  "categories": ["TREND", "MOMENTUM", "VOLATILITY", "VOLUME", "PRICE"]
}
```

#### 字段说明

| 字段 | 含义 | 前端使用方式 |
|---|---|---|
| `factorKey` | 全局唯一 ID，全大写 | 作为请求 `factors[i].factor` 的取值来源 |
| `category` | 分类 | 用于分组下拉框 |
| `params[].type` | `INT / FLOAT / ENUM` | 动态生成数字输入或 select 输入 |
| `multiOutput` | 是否多输出 | 决定前端是否需要让用户选择 outputIndex（注：服务端始终返回全部输出列） |
| `lookbackHint` | 预热 bar 估算公式（字符串） | UI 展示"需要至少 N 条历史数据"的提示 |
| `lookbackDefault` | 默认参数下所需历史 bar 数 | 前端预览时拉取的 OHLCV 条数下限 |

#### 错误码

- 200 OK：正常返回
- 401 UNAUTHORIZED：未登录
- 500 INTERNAL_ERROR：Python 服务不可用（fallback 返回空列表 + 错误码）

#### 对应的 Python 接口（供 Java 代理调用）

```
GET http://127.0.0.1:8000/api/compute/factors/registry
Accept: application/json
```

响应外层：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "factors": [...],
    "count": 20,
    "categories": ["TREND","MOMENTUM","VOLATILITY","VOLUME","PRICE"]
  }
}
```

Java `FactorGateway` 需要将 `code: 0` 转换为 `code: 200`，将 Python 的 `data` 原样映射到 Java `ApiResponse.data`。

---

### 3.2 POST /api/factors/preview（Java 前端接口）

> 对应原型图：`sdlc/prd/003-策略管理/prototype/strategy-editor.html` 的**因子预览图表区**
> 前端触发：用户输入 tsCode + 选择因子 + 填写参数 → 点击「预览因子值」按钮，前端 ECharts 渲染

#### 请求体（JSON Body）

```json
{
  "tsCode": "000001.SZ",
  "startDate": "20250101",
  "endDate": "20260620",
  "size": 120,
  "factors": [
    { "factor": "MA",   "params": { "timeperiod": 5 },  "outputIndex": 0 },
    { "factor": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "outputIndex": 0 },
    { "factor": "RSI",  "params": { "timeperiod": 14 }, "outputIndex": 0 }
  ]
}
```

#### 请求字段说明

| 字段 | 类型 | 必填 | 默认值 | 范围/约束 | 原型图中的表单元素 |
|---|---|---|---|---|---|
| `tsCode` | string | 是 | — | 格式 `xxxxxx.SZ / xxxxxx.SH`；必须在 `stock_basic.ts_code` 中存在 | 策略编辑器顶部"股票代码输入框" |
| `startDate` | string | 否 | 自动取 `endDate - size 个交易日` | `YYYYMMDD` | 预览日期范围选择器（可选） |
| `endDate` | string | 否 | 当前最近交易日 | `YYYYMMDD` | 预览日期范围选择器（可选） |
| `size` | int | 否 | 120 | `[20, 500]` | 预览窗口大小（输入框） |
| `factors` | array | 是 | — | 至少一个，每个元素见下 | 左侧因子参数表（动态生成若干行） |
| `factors[i].factor` | string | 是 | — | 必须等于 registry 中的某个 `factorKey` | 因子下拉框选项 |
| `factors[i].params` | object | 条件必填 | `{}`（无参数因子可省略） | key 名与 registry 中 `params[].name` 一致，值按 `type` 解析 | 因子参数表每行一个 `<input>` |
| `factors[i].outputIndex` | int | 否 | `0` | `>=0`；仅用于调用方标识自己关心的输出，**服务端始终返回全部输出列** | 多输出因子时"输出列选择"下拉框 |

#### 响应 `data`

```json
{
  "tsCode": "000001.SZ",
  "stockName": "平安银行",
  "computeMs": 26,
  "dates": ["20260105", "20260106", "20260107", "..."],
  "results": {
    "MA_5":        [10.1, 10.2, 10.25, null, null, ...],
    "MACD_DIF":    [0.12, 0.15, 0.18, null, null, ...],
    "MACD_DEA":    [0.10, 0.12, 0.14, null, null, ...],
    "MACD_HIST":   [0.02, 0.03, 0.04, null, null, ...],
    "RSI_14":      [60.1, 62.3, 63.0, null, null, ...]
  },
  "ohlcv": {
    "open":  [...],
    "high":  [...],
    "low":   [...],
    "close": [...],
    "volume": [...]
  }
}
```

> **结果 key 命名规则**（在 `factor_utils.resultKey` 中统一实现）
> - 单输出：`factorKey_paramValue`（如 `MA_5`, `RSI_14`, `EMA_20`）；当参数为 `timeperiod` 类型且只有一个参数时，key 中直接拼接参数数值
> - 多输出：`factorKey_outputLabel`（如 `MACD_DIF`, `MACD_DEA`, `MACD_HIST`）。每个输出标签对应一个结果 key
> - 无参数因子：`factorKey`（如 `SAR`）
> - 价格直取类（`CLOSE / HIGH / LOW / VOLUME`）：key 直接使用小写列名 `close / high / low / volume`，与 `daily_quote` 中的字段对应

#### 前端后续操作

- 使用 `dates` 做 X 轴
- 使用 `results[*]` 做多条折线（每条折线对应一个因子输出）
- 使用 `ohlcv` 叠加 K 线主图（可选）

#### 错误码

| 场景 | HTTP code | 说明 |
|---|---|---|
| 未登录 | 401 UNAUTHORIZED | 由 `AuthInterceptor` 返回 |
| `tsCode` 格式错 / 不在 stock_basic 中 | 400 BAD_REQUEST | 返回 `message = "tsCode 不合法或不存在"` |
| `factors` 数组为空 | 400 BAD_REQUEST | `message = "factors 数组不能为空"` |
| 某个 `factor` 的 `factorKey` 不在 registry 中 | 404 NOT_FOUND | `message = "未找到因子: {factorKey}"` |
| 某个 `factor.params` 中缺失 registry 中声明的必填参数 | 400 BAD_REQUEST | `message = "因子 {factorKey} 缺少参数 {paramName}"` |
| `size` 超出范围 | 400 BAD_REQUEST | |
| Python 服务超时 / 连接失败 | 500 INTERNAL_ERROR | `message = "计算服务不可用"` |
| Python 返回 code != 0（如 inputs 不足） | 500 INTERNAL_ERROR | 原样透传 message 给前端 |

#### 对应的 Python 接口（供 Java 内部调用）

```
POST http://127.0.0.1:8000/api/compute/factors
Content-Type: application/json
```

请求体（外层 taskId + payload，遵循 PRD §3.1）：

```json
{
  "taskId": "uuid-generated-by-java",
  "timestamp": 1781972679,
  "payload": {
    "stockCode": "000001.SZ",
    "ohlcv": [
      { "date": "20260105", "open": 10.0, "high": 10.3, "low": 9.9,  "close": 10.1, "volume": 520000 },
      { "date": "20260106", "open": 10.1, "high": 10.4, "low": 10.0, "close": 10.2, "volume": 580000 }
    ],
    "factors": [
      { "factor": "MA",   "params": { "timeperiod": 5 },  "outputIndex": 0 },
      { "factor": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "outputIndex": 0 },
      { "factor": "RSI",  "params": { "timeperiod": 14 }, "outputIndex": 0 }
    ]
  }
}
```

响应体：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": "uuid-generated-by-java",
    "computeMs": 26,
    "dates": ["20260105", "20260106", "20260107", "..."],
    "results": {
      "MA_5":        [10.1, 10.2, 10.25, null, null, ...],
      "MACD_DIF":    [0.12, 0.15, 0.18, null, null, ...],
      "MACD_DEA":    [0.10, 0.12, 0.14, null, null, ...],
      "MACD_HIST":   [0.02, 0.03, 0.04, null, null, ...],
      "RSI_14":      [60.1, 62.3, 63.0, null, null, ...]
    }
  }
}
```

Java FactorGateway 需要：
1. 将 `code: 0` 映射为 `ApiResponse.code = 200`
2. 将 Python `data.results` 原样透传到前端 `data.results`
3. 将 Python `data.dates` 原样透传
4. **额外填充** `tsCode / stockName / ohlcv`（Java 从 SQLite 读，Python 返回中不含此三者）

---

### 3.3 POST /api/factors/registry/refresh（Java 管理员接口）

> 对应原型图：管理员设置页（非本次 UI 范围，可作为隐藏按钮）
> 前端触发：当 Python 端新增/调整因子后，管理员点击「刷新因子库」手动拉取最新 registry

#### 请求参数

无 body。

#### 响应 `data`

```json
{
  "refreshedAt": "2026-06-20 15:30:00",
  "factorCount": 20,
  "categories": ["TREND", "MOMENTUM", "VOLATILITY", "VOLUME", "PRICE"]
}
```

#### 错误码

| 场景 | HTTP code | 说明 |
|---|---|---|
| 未登录 | 401 UNAUTHORIZED | |
| 非管理员 | 403 FORBIDDEN | 由 `@RequireAdmin` 切面拦截 |
| Python 服务不可用 | 500 INTERNAL_ERROR | 保留缓存原值，返回错误消息 |

---

## 4. 错误码汇总（Java 侧）

| HTTP 状态码 | `ErrorCode.java` | message | 触发场景 | 复用/新增 |
|---|---|---|---|---|
| 400 | BAD_REQUEST | tsCode 不合法 / factors 数组为空 / 参数缺失 / size 超范围 | 预览接口入参不合法 | 复用 |
| 401 | UNAUTHORIZED | 未登录 | 未登录访问 `/api/factors/*` | 复用 |
| 403 | FORBIDDEN | 无权限 | 非管理员调用 `/registry/refresh` | 复用 |
| 404 | NOT_FOUND | 未找到因子 {factorKey} / 股票不存在 | 请求的 factorKey 不在 registry 中 | 复用 |
| 500 | INTERNAL_ERROR（沿用 Spring 默认） | 计算服务不可用 / 远程调用超时 | Python 服务宕机或网络异常 | **复用现有全局异常**，不新增 ErrorCode |

> **Python 侧错误码规范**（与 Java 对齐但独立维护）：
> - `code=0` 成功
> - `code=400` 参数错误（factorKey 不合法 / 参数缺失 / inputs 不足）
> - `code=500` 计算引擎异常（akquant 抛错 / 数据格式异常）

---

## 5. 验收相关

- [ ] `GET /api/factors/registry` 能在浏览器 / curl 中返回 20 个因子，字段结构符合 §3.1
- [ ] `POST /api/factors/preview` 能返回前端可直接绘图的 `{dates, results}` 结构，且结果 key 命名符合 PRD §9.3
- [ ] 未登录调用 `/api/factors/preview` 返回 401
- [ ] 非管理员调用 `/api/factors/registry/refresh` 返回 403
- [ ] 所有 JSON 字段均为小驼峰命名（无下划线）；Python 侧内部字段 `taskId / computeMs / outputIndex` 亦一律小驼峰
- [ ] 同一请求中不同因子返回的数组长度一致，且等于 `dates` 长度；预热不足位置为 `null`
- [ ] Java `FactorGateway` 对 Python 返回的 `code != 0` 场景能正常抛业务异常并被 `GlobalExceptionHandler` 处理
- [ ] `/api/factors/registry` 结果命中 Caffeine 缓存：1 小时内重复调用不触发对 Python 的 HTTP
- [ ] 调用 `/registry/refresh` 后，缓存中的 factor 清单被最新结果替换
- [ ] 关键验证：假设自己是前端开发者，仅阅读本接口设计 + 原型图，能否直接写出正确的 fetch 调用？如果有任何一个字段的含义 / 必填 / 类型 / 默认值不明确 → 不合格，必须补充后通过
