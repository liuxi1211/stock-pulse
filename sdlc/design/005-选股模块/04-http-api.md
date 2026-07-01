# 005 选股模块 — HTTP 接口设计

> 设计依据：
> - 005-选股模块PRD.md §9.1 / §9.3 / §9.6 规则树 JSON Schema
> - prototype/screening.html（快照模式执行选股 / 导出 CSV / 保存策略草稿）
> - prototype/screening-range.html（区间模式执行选股 / 导出 CSV）
> - prototype/screening-flow.html（数据流向与 cURL 示例）
> - 现有 ApiResponse<T> 统一返回结构（`code` + `message` + `data`）
>
> 项目：Stock Watcher（Java Spring Boot + Thymeleaf）
> 约定：请求 JSON 字段小驼峰；响应 JSON 字段小驼峰；时间字段为 YYYYMMDD 字符串；分页字段明确

---

## 0. 现有接口复用分析（基于实时扫描 13 个 Controller）

当前 stock-watcher/src/main/java/com/arthur/stock/controller 已定义的 Controller 及其路径前缀：

| Controller | 路径前缀 | 是否与本模块冲突 | 复用决策 |
|------------|----------|-----------------|---------|
| `WatchlistController` | `/api/watchlists` | 无冲突 | 与本模块无复用 |
| `StockBasicController` | `/api/stocks` | 无冲突 | 本模块不新建 /api/stocks 接口 |
| `KlineController` | `/api/klines` | 无冲突 | 本模块不新建 K 线接口 |
| `UserApiController` | `/api/users` | 无冲突 | 与本模块无复用 |
| `AuthController` | `/api/auth` | 无冲突 | 与本模块无复用 |
| `PageController` | 页面路由（`/`、`/watchlist`、`/klines`、`/data-init`、`/admin` 等） | 无冲突 | **扩展** `/screening`、`/screening/range`、`/screening/flow` 页面路由 |
| `DataInitController` | `/api/data-init` | 无冲突 | 与本模块无复用 |
| 其他（约 6 个） | — | 无冲突 | 与本模块无复用 |

**新建接口**：全部定义于 `ScreeningController`，路径前缀 `/api/screening/*`，与现有接口无冲突。

---

## 1. 接口总览

| 方法 | 路径 | 功能 | 原型图对应页面/操作 | 权限 |
|------|------|------|--------------------|------|
| POST | `/api/screening/query` | 执行选股（快照模式 / 区间模式共用） | prototype/screening.html 「执行选股」按钮 / screening-range.html 「执行选股」 | 需登录 |
| GET | `/api/screening/export/csv` | 导出 CSV（基于最近一次查询的 requestId 或直接请求） | prototype/screening.html 「导出 CSV」/ screening-range.html 「导出 CSV」 | 需登录 |
| POST | `/api/screening/draft/save` | 保存选股条件为策略草稿（category=SCREEN_DRAFT，status=DRAFT） | prototype/screening.html 「保存策略草稿」按钮 | 需登录 |
| POST | `/api/screening/indicators/refresh` | 手动触发指标预计算（管理员专用） | data-init 页面扩展「刷新指标预计算」 | @RequireAdmin |
| GET | `/api/screening/indicators/status` | 查询指标预计算状态（某个 trade_date 的覆盖度） | data-init 页面 / screening.html 页面顶部状态提示 | 需登录 |

---

## 2. 统一返回结构（完全复用现有 ApiResponse）

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

错误示例：

```json
{
  "code": 2001,
  "message": "stock_indicator_daily 暂无该日期指标数据，请先触发指标预计算",
  "data": null
}
```

---

## 3. 逐个接口详细设计

### 3.1 执行选股（快照模式 / 区间模式）

**POST** `/api/screening/query`

**权限**：需登录（登录后可访问）

**请求参数**：

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| rules | `RuleNode[]` | 是 | — | 至少 1 个节点 | 规则树（与 003 策略管理 buy_rules 同结构） |
| option.mode | string | 是 | — | `SNAPSHOT` 或 `RANGE` | 查询模式 |
| option.tradeDate | string | 条件 | — | YYYYMMDD 格式；≤ 当前日期；必须为交易日 | 当 mode=SNAPSHOT 时必填 |
| option.startDate | string | 条件 | — | YYYYMMDD 格式 | 当 mode=RANGE 时必填；区间 ≤ 90 个交易日 |
| option.endDate | string | 条件 | — | YYYYMMDD 格式；≥ startDate | 当 mode=RANGE 时必填 |
| option.excludeSt | boolean | 否 | true | — | 剔除 ST 股 |
| option.minListDays | integer | 否 | 365 | ≥ 0 | 上市天数（天） |
| option.minTurnover10k | number | 否 | 5000 | ≥ 0 | 最低成交额（万元） |
| option.limit | integer | 否 | 200 | 1 ≤ limit ≤ 500 | 最大返回条数 |

**RuleNode 结构（与 003 策略管理 buy_rules 一致）**：

```typescript
interface RuleNode {
    type: 'logic' | 'compare';           // 必填
    // --- logic 节点字段 ---
    operator?: 'AND' | 'OR';              // logic 必填
    children?: RuleNode[];                // logic 必填
    // --- compare 节点字段 ---
    left?: ExprNode;                      // compare 必填
    comparator?: '>' | '<' | '>=' | '<=' | '==' | 'cross_up' | 'cross_down';  // compare 必填
    right?: ExprNode;                     // compare 必填
}

interface ExprNode {
    value?: number;                       // 三选一：静态值
    factor?: FactorRef;                   // 三选一：因子引用
    op?: ArithOp;                         // 三选一：算术表达式
}

interface FactorRef {
    factorKey: string;                    // e.g. "MA"
    params?: Record<string, any>;         // e.g. {timeperiod:5}
    outputIndex?: number;                 // 多输出因子使用（MACD/KDJ 默认 0/1/2）
}

interface ArithOp {
    operator: '+' | '-' | '*' | '/';
    left: ExprNode;
    right: ExprNode;
}
```

**请求 JSON 示例（快照模式：MA5 金叉 MA20 + 放量）**：

```json
{
  "rules": [
    {
      "type": "compare",
      "left": {
        "factor": {
          "factorKey": "MA",
          "params": {"timeperiod": 5}
        }
      },
      "comparator": "cross_up",
      "right": {
        "factor": {
          "factorKey": "MA",
          "params": {"timeperiod": 20}
        }
      }
    },
    {
      "type": "compare",
      "left": {
        "factor": {
          "factorKey": "VOLUME"
        }
      },
      "comparator": ">",
      "right": {
        "op": {
          "operator": "*",
          "left": {
            "factor": {
              "factorKey": "VOL_MA",
              "params": {"timeperiod": 20}
            }
          },
          "right": {
            "value": 1.5
          }
        }
      }
    }
  ],
  "option": {
    "mode": "SNAPSHOT",
    "tradeDate": "20260520",
    "excludeSt": true,
    "minListDays": 365,
    "minTurnover10k": 5000,
    "limit": 200
  }
}
```

**请求 JSON 示例（区间模式：MACD 金叉 + 放量，2026-05-01 至 2026-05-20）**：

```json
{
  "rules": [
    {
      "type": "compare",
      "left": {
        "factor": {
          "factorKey": "MACD",
          "params": {"fastperiod": 12, "slowperiod": 26},
          "outputIndex": 0
        }
      },
      "comparator": "cross_up",
      "right": {
        "factor": {
          "factorKey": "MACD",
          "params": {"fastperiod": 12, "slowperiod": 26, "signalperiod": 9},
          "outputIndex": 1
        }
      }
    }
  ],
  "option": {
    "mode": "RANGE",
    "startDate": "20260501",
    "endDate": "20260520",
    "excludeSt": true,
    "minListDays": 365,
    "minTurnover10k": 5000,
    "limit": 300
  }
}
```

**成功响应**（`code=200, message="success"`）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalHit": 42,
    "stocks": [
      {
        "tsCode": "000001.SZ",
        "name": "平安银行",
        "close": 12.58,
        "dailyChangePct": 1.36,
        "turnover10k": 38500.00
      },
      {
        "tsCode": "600036.SH",
        "name": "招商银行",
        "close": 38.60,
        "dailyChangePct": 0.82,
        "turnover10k": 125000.00
      }
    ],
    "warnings": [
      "因子 MA(timeperiod=8) 非预计算标准参数，已降级为 MA(10)"
    ],
    "generatedSql": "SELECT\n    sb.ts_code,\n    sb.name,\n    sid.close AS current_close,\n    ...\n    WHERE sid.trade_date = '20260520'\n    ..."
  }
}
```

**区间模式成功响应**（`data.stocks` 中字段变为 firstHitDate / hitDaysCount）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalHit": 87,
    "stocks": [
      {
        "tsCode": "000001.SZ",
        "name": "平安银行",
        "firstHitDate": "20260505",
        "hitDaysCount": 12
      },
      {
        "tsCode": "600036.SH",
        "name": "招商银行",
        "firstHitDate": "20260511",
        "hitDaysCount": 6
      }
    ],
    "warnings": [],
    "generatedSql": "SELECT ... WHERE sid.trade_date BETWEEN '20260501' AND '20260520' ..."
  }
}
```

**失败响应（错误码说明）**：

| 场景 | code | message |
|------|------|---------|
| rules 为空 | 400 | "rules 不能为空" |
| tradeDate 格式错误 | 400 | "tradeDate 格式错误，要求 YYYYMMDD" |
| tradeDate 非交易日 | 400 | "查询日期非交易日" |
| 指标表中无该日期数据 | 2001 | "stock_indicator_daily 暂无该日期指标数据，请先触发指标预计算" |
| 区间模式跨度超过 90 个交易日 | 2002 | "区间模式最大跨度为 90 个交易日" |
| 因子参数完全不支持（如 MA(2)） | 2003 | "因子 MA(timeperiod=2) 非预计算标准参数，请改用 MA(5)/MA(10)/MA(20)/MA(60)/MA(120)/MA(250)" |
| 未登录 | 401 | "未登录" |
| limit 超出最大 500 | 400 | "limit 必须 ≤ 500" |

**对应前端交互**：

- prototype/screening.html 的「执行选股」按钮 → 调用本接口（mode=SNAPSHOT）
- prototype/screening-range.html 的「执行选股」按钮 → 调用本接口（mode=RANGE）
- prototype/screening.html 右侧显示命中结果表格 + SQL 预览
- prototype/screening-range.html 右侧显示区间模式结果表格 + 每日命中数统计表 + SQL 预览

---

### 3.2 导出 CSV

**GET** `/api/screening/export/csv`

**权限**：需登录（登录后可访问）

**请求参数**：

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| mode | string | 是 | — | `SNAPSHOT` 或 `RANGE` | 查询模式 |
| tradeDate | string | 条件 | — | YYYYMMDD 格式 | mode=SNAPSHOT 时必填 |
| startDate | string | 条件 | — | YYYYMMDD 格式 | mode=RANGE 时必填 |
| endDate | string | 条件 | — | YYYYMMDD 格式；≥ startDate | mode=RANGE 时必填 |
| json | string | 否 | — | Base64 编码的 rules JSON 串 | 与 query 接口的 rules 参数一致 |
| excludeSt | boolean | 否 | true | — | 是否排除 ST 股 |
| minListDays | integer | 否 | 365 | — | 最小上市天数 |
| minTurnover10k | number | 否 | 5000 | — | 最低成交额（万元） |

**说明**：由于浏览器 GET 请求无法携带复杂 JSON body，本接口采用「json=Base64(rulesJson)」方案。前端调用示例：

```javascript
const rulesJson = JSON.stringify(rulesArray);
const b64 = btoa(unescape(encodeURIComponent(rulesJson)));
const csv = await fetch(`/api/screening/export/csv?mode=SNAPSHOT&tradeDate=20260520&json=${b64}&limit=500`);
```

**响应**：`text/csv; charset=utf-8`，文件下载。

CSV 内容示例（快照模式）：

```csv
ts_code,name,close,daily_change_pct,turnover_10k
000001.SZ,平安银行,12.58,1.36,38500.00
600036.SH,招商银行,38.60,0.82,125000.00
```

**对应前端交互**：

- prototype/screening.html 的「导出 CSV」按钮 → 导出当前查询结果的 CSV 文件
- prototype/screening-range.html 的「导出 CSV」按钮 → 导出区间模式结果的 CSV 文件

---

### 3.3 保存策略草稿

**POST** `/api/screening/draft/save`

**权限**：需登录

**请求参数**：

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| strategyName | string | 是 | — | 长度 1~100 字符 | 策略草稿名称（前端让用户输入） |
| rules | `RuleNode[]` | 是 | — | 至少 1 个节点 | 选股条件的规则树（与 query 接口的 rules 一致） |
| option.mode | string | 否 | SNAPSHOT | SNAPSHOT 或 RANGE | 保存查询模式（用于草稿恢复） |
| option.excludeSt | boolean | 否 | true | — | 同上 |
| option.minListDays | integer | 否 | 365 | — | 同上 |
| option.minTurnover10k | number | 否 | 5000 | — | 同上 |

**JSON 示例**：

```json
{
  "strategyName": "MA5金叉MA20+放量（2026-05-20）",
  "rules": [
    {
      "type": "compare",
      "left": {"factor": {"factorKey": "MA", "params": {"timeperiod": 5}}},
      "comparator": "cross_up",
      "right": {"factor": {"factorKey": "MA", "params": {"timeperiod": 20}}}
    }
  ],
  "option": {
    "mode": "SNAPSHOT",
    "excludeSt": true,
    "minListDays": 365,
    "minTurnover10k": 5000
  }
}
```

**成功响应**（`code=200`）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "strategyId": 42,
    "savedAt": "2026-05-20T10:30:00Z"
  }
}
```

**说明**：本接口复用 003 策略管理 `quant_strategy` 表（category=SCREEN_DRAFT, status=DRAFT），因此本模块不新建独立草稿表。对应 prototype/screening.html 的「保存策略草稿」按钮（页面左上角或右上角操作栏）。

---

### 3.4 手动触发指标预计算

**POST** `/api/screening/indicators/refresh`

**权限**：@RequireAdmin

**请求参数**：

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| tsCodes | string[] | 否 | 所有股票 | 数组元素为合法 ts_code（如 000001.SZ） | 指定刷新哪些股票的指标；留空=刷新全部 |
| limitDays | integer | 否 | 1 | ≥ 1 | 刷新最近多少个交易日的数据 |

**JSON 示例**：

```json
{
  "tsCodes": ["000001.SZ", "600036.SH"],
  "limitDays": 5
}
```

**成功响应**（`code=200`）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "task-20260520-001",
    "startedAt": "2026-05-20T10:30:00Z",
    "estimatedSeconds": 45
  }
}
```

**失败响应**：

| 场景 | code | message |
|------|------|---------|
| 未登录 | 401 | "未登录" |
| 无管理员权限 | 403 | "无权限" |
| Python 计算服务不可用 | 500 | "Python 计算服务不可用，请稍后重试" |

**对应前端交互**：data-init 页面或管理员页面的「刷新指标预计算」操作。

---

### 3.5 查询指标预计算状态

**GET** `/api/screening/indicators/status?tradeDate=YYYYMMDD`

**权限**：需登录

**请求参数**：

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| tradeDate | string | 是 | — | YYYYMMDD 格式 | 查询哪个交易日的指标预计算覆盖度 |

**成功响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tradeDate": "20260520",
    "totalStocks": 5000,
    "calculatedStocks": 4987,
    "coveragePct": 99.74
  }
}
```

**对应前端交互**：

- prototype/screening.html 页面顶部「最近交易日指标预计算状态」提示（显示 coveragePct，若 < 90% 则红色警告，引导用户前往 data-init 页面刷新）
- prototype/screening-flow.html 页面顶部「指标预计算状态」卡片

---

## 4. 错误码汇总（与 ErrorCode.java 中新增/复用项一致）

| 错误码 | 含义 | 对应接口 |
|--------|------|---------|
| 400 | 请求参数错误 | 所有接口 |
| 401 | 未登录 | 所有接口 |
| 403 | 无权限 | 写接口（/indicators/refresh /draft/save） |
| 2001 | stock_indicator_daily 暂无该日期指标数据 | /query |
| 2002 | 区间模式最大跨度为 90 个交易日 | /query (mode=RANGE) |
| 2003 | 因子参数非预计算标准参数 | /query |
| 500 | Python 计算服务不可用 | /indicators/refresh |

## 5. cURL 可运行示例（供 screening-flow.html 参考）

```bash
# 快照模式查询（MA5 金叉 MA20 + 放量）
curl -X POST http://localhost:8080/api/screening/query \
  -H "Content-Type: application/json" \
  -H "Cookie: <登录后获得的 Cookie>" \
  -d '{
    "rules": [
      {
        "type": "compare",
        "left": {"factor": {"factorKey": "MA", "params": {"timeperiod": 5}}},
        "comparator": "cross_up",
        "right": {"factor": {"factorKey": "MA", "params": {"timeperiod": 20}}}
      }
    ],
    "option": {
      "mode": "SNAPSHOT",
      "tradeDate": "20260520",
      "excludeSt": true,
      "minListDays": 365,
      "minTurnover10k": 5000,
      "limit": 200
    }
  }'

# 查询指标预计算状态
curl "http://localhost:8080/api/screening/indicators/status?tradeDate=20260520" \
  -H "Cookie: <登录后获得的 Cookie>"
```

**注意**：以上 cURL 中 `tradeDate=20260520` 仅为示例值，实际运行时请替换为当前系统支持的最近交易日。

---

## 6. 风格约束

1. **响应 data 为 null 时省略**：遵循 `@JsonInclude(JsonInclude.Include.NON_NULL)`，即 ApiResponse 中 data 为 null 时不出现在 JSON 中（由现有 ApiResponse 的注解保证）
2. **字段命名**：请求/响应 JSON 字段一律小驼峰（tsCode / tradeDate / firstHitDate / hitDaysCount / turnover10k / dailyChangePct）；数据库字段一律 snake_case（ts_code / trade_date / first_hit_date）
3. **接口路径**：`/api/screening/*`，REST 风格（POST 查询/写入，GET 读取/下载）；与现有 PageController 的页面路由（`/screening`）无冲突（PageController 使用 `/screening`，REST API 使用 `/api/screening/*`）
4. **分页参数**：limit 明确给出默认值和最大限制（200 默认，最大 500）；结果总数字段名为 totalHit（非 total，避免与通用分页接口混淆）
5. **HTTP 状态码**：所有响应 HTTP 状态码为 200，具体错误由 `code` 字段标识（遵循现有 ApiResponse 约定）
6. **接口标注**：每个接口标注对应原型图页面的操作按钮（见上表），便于前端开发时快速定位
