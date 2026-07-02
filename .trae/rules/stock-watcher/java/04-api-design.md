---
alwaysApply: false
description: "当用户涉及 API 设计、接口设计、RESTful API、统一返回格式、错误码、分页查询等场景时触发。适用于设计后端接口、定义 API 响应格式、设计错误码体系、编写 API 文档等任务。仅适用于 stock-watcher Java 后端项目。关键词：API, 接口, RESTful, 接口设计, 返回格式, 错误码, 响应, 接口文档"
# API 设计规范

> 适用于 stock-watcher（Spring Boot）REST API 设计。

---

## 一、RESTful 设计原则

### 1.1 资源导向 ✅ MUST

- API 围绕**资源**设计，而不是动作
- 使用名词表示资源，不用动词
- URL 路径使用 **小写 + 连字符（kebab-case）**

| 好的 ✅ | 不好的 ❌ |
|--------|----------|
| `GET /stocks` | `GET /getStocks` |
| `POST /watchlist/items` | `POST /addWatchlist` |
| `GET /kline/{stockCode}` | `GET /getKline?code=xxx` |

### 1.2 HTTP 方法语义 ✅ MUST

| 方法 | 用途 | 幂等 | 示例 |
|-----|------|------|------|
| `GET` | 查询资源 | ✅ | `GET /stocks?page=1` |
| `POST` | 创建资源 | ❌ | `POST /watchlist/items` |
| `PUT` | 全量更新资源 | ✅ | `PUT /watchlist/items/{id}` |
| `PATCH` | 部分更新资源 | ❌ | `PATCH /watchlist/items/{id}` |
| `DELETE` | 删除资源 | ✅ | `DELETE /watchlist/items/{id}` |

### 1.3 URL 层级 💡 SHOULD

```
/{模块}/{资源}/{资源ID}/{子资源}
```

示例：
```
/kline/{stockCode}
/watchlist/items
/watchlist/items/{id}
/stock-basic/{tsCode}
```

---

## 二、URL 命名规范

### 2.1 命名约定 ✅ MUST

- 全部小写，使用连字符 `-` 分隔
- 名词复数形式表示集合
- 资源 ID 放在路径中
- 过滤/分页/排序等参数放在 query string

```
/daily-quotes?tradeDate=20240101&page=1
/watchlist/items/123
/stock-basic/000001.SZ
```

### 2.2 API 前缀 ✅ MUST

- **REST API 直接以模块名开头，不再使用 `/api/` 前缀**（无意义的统一前缀已废弃）
  - 示例：`/kline`、`/watchlist/items`、`/factors`、`/tushare/daily`
- 页面请求（Thymeleaf，`@Controller`）走根路径或业务路径（如 `/login`、`/login/2fa`），不与 REST 模块名冲突

### 2.3 版本号 📌 MAY

- 小版本迭代可不加版本号
- 大版本变更时加版本号：`/v1/...`（注意：不加 `/api`，直接 `/v1/模块/...`）

---

## 三、统一返回格式

### 3.1 ApiResponse 结构 ✅ MUST

所有 API 统一返回 `ApiResponse<T>`：

```json
{
    "code": 200,
    "message": "success",
    "data": {}
}
```

| 字段 | 类型 | 说明 |
|-----|------|------|
| `code` | int | 状态码，200 表示成功 |
| `message` | string | 提示信息 |
| `data` | T | 返回数据，可为 null / 对象 / 数组 |

### 3.2 成功响应 ✅ MUST

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "stockCode": "000001.SZ",
        "name": "平安银行"
    }
}
```

### 3.3 失败响应 ✅ MUST

```json
{
    "code": 1001,
    "message": "股票代码不存在",
    "data": null
}
```

由 `GlobalExceptionHandler` 统一处理异常并返回错误格式。

---

## 四、错误码设计

### 4.1 错误码分段 ✅ MUST

| 码段 | 含义 | 示例 |
|-----|------|------|
| 200 | 成功 | 200 OK |
| 400-499 | 请求错误（客户端） | 400 参数错误、401 未登录、403 无权限、404 不存在 |
| 500 | 服务器错误 | 500 系统内部错误 |
| 1000-1999 | 业务错误 | 1001 用户名已存在、1004 股票不存在 |

### 4.2 项目现有错误码

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 数据冲突 |
| 1001 | 用户名已存在 |
| 1002 | 用户不存在 |
| 1003 | 不能删除自己 |
| 1004 | 股票不存在 |
| 1005 | 已在自选股中 |
| 1006 | 无效的角色值 |

### 4.3 错误码扩展原则 💡 SHOULD

- 新增错误码按模块分组
- 同模块连续编号
- 错误码一经定义不要轻易修改

---

## 五、分页规范

### 5.1 分页参数 ✅ MUST

| 参数 | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| `page` | int | 1 | 页码，从 1 开始 |
| `size` | int | 20 | 每页数量 |

### 5.2 分页返回 💡 SHOULD

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "list": [...],
        "total": 100,
        "page": 1,
        "size": 20,
        "pages": 5
    }
}
```

或使用 MyBatis-Plus 的 `IPage<T>`。

### 5.3 分页约束 💡 SHOULD

- 最大 page size 限制（如 100/500），防止一次查太多
- 大表深分页考虑其他方案（游标分页）
- 默认排序规则明确

---

## 六、参数校验

### 6.1 校验原则 ✅ MUST

- 所有外部输入都要校验
- Controller 层做基本校验
- Service 层做业务校验
- 校验失败抛出 `BusinessException` 或 `IllegalArgumentException`

### 6.2 常见校验项

| 类型 | 校验内容 |
|-----|---------|
| 必填 | 非空、非空字符串 |
| 长度 | 字符串最小/最大长度 |
| 范围 | 数字最小/最大值 |
| 格式 | 邮箱、手机号、股票代码格式 |
| 枚举 | 是否在合法枚举值内 |

### 6.3 股票代码校验 💡 SHOULD

本项目有两种股票代码格式：

- `ts_code`：`000001.SZ`、`600000.SH`（6位数字 + `.` + 交易所后缀）
- `symbol`：`000001`、`600000`（纯 6 位数字）

根据接口需要校验格式。

---

## 七、Controller 规范

### 7.1 类命名与注解 ✅ MUST

```java
@RestController
@RequestMapping("/kline")
@RequiredArgsConstructor
public class KlineController {

    private final KlineService klineService;

    // ...
}
```

### 7.2 方法命名 💡 SHOULD

- 查询方法：`getXxx` / `listXxx` / `queryXxx`
- 新增方法：`addXxx` / `createXxx`
- 更新方法：`updateXxx`
- 删除方法：`deleteXxx` / `removeXxx`

### 7.3 参数获取 ✅ MUST

| 注解 | 用途 | 示例 |
|-----|------|------|
| `@PathVariable` | 路径参数 | `@PathVariable String stockCode` |
| `@RequestParam` | 查询参数 | `@RequestParam(defaultValue = "1") int page` |
| `@RequestBody` | 请求体 | `@RequestBody UserDTO user` |
| `@RequestHeader` | 请求头 | `@RequestHeader("Authorization") String token` |

---

## 八、接口分类

### 8.1 REST API 接口

- 前缀：无（直接以模块名开头，如 `/kline`、`/factors`）
- 返回：JSON（`ApiResponse`）
- 控制器：`*Controller`（`@RestController`）

### 8.2 页面接口

- 走根路径或业务路径（如 `/login`、`/dashboard`），不与 REST 模块名冲突
- 返回：Thymeleaf 视图
- 控制器：`PageController` / `AuthController`（`@Controller`）

---

## 九、跨系统接口规范

### 9.1 watcher ↔ engine 接口 ✅ MUST

- watcher 调用 engine，通过 HTTP/JSON
- engine 不主动回调 watcher
- 接口契约变更需同步更新双方

### 9.2 engine API 约定

- engine 返回格式：`{"success": bool, "message": str, "code": int, "data": any}`
- 接口路径：`/python/v1/{模块}/...`（engine 自身前缀，与 watcher 解耦，watcher 通过 `EngineClient` 调用）
- 详见 stock-engine 项目文档

---

## 十、接口文档 📌 MAY

### 10.1 文档方式

- Swagger / OpenAPI（可选）
- 或项目内 Markdown 文档

### 10.2 接口信息

每个接口应包含：
- 接口描述
- 请求 URL、方法
- 请求参数（路径参数、查询参数、请求体）
- 响应示例
- 错误码说明

---

## 十一、参数对象化与类型规范

### 11.1 参数个数约束 ✅ MUST

- HTTP 接口方法中 `@RequestParam` + `@PathVariable` **合计 > 5 个**时，必须封装为对象：
  - 写操作（POST/PUT/PATCH）→ 聚合为 `@RequestBody *RequestDTO`
  - 读操作（GET）→ 可把多个 query 参数聚合为 `*QueryDTO`（用 `@ModelAttribute` 或 Spring 自动绑定）
- **计数口径**：仅统计 `@RequestParam` 与 `@PathVariable`；`@RequestBody DTO`、`HttpSession`、`Model`、`HttpServletRequest/Response` 等不计入。
- 示例（违规）：
  ```java
  // ❌ 6 个 @RequestParam，必须封装
  getList(@RequestParam String a, @RequestParam String b, @RequestParam String c,
          @RequestParam String d, @RequestParam String e, @RequestParam String f)
  ```
  ```java
  // ✅ 封装为 QueryDTO
  getList(@ModelAttribute ListQueryDTO query)
  ```

### 11.2 禁止使用 Map ✅ MUST

- 接口的**请求体、返回体禁止使用 `Map<?,?>`**（含 `Map<String,Object>` / `Map<String,String>` 等）。
- 必须使用显式类型：`*RequestDTO` / `*ResponseDTO` / `*VO`，保证接口契约清晰、可被 Swagger 文档化、前端可类型推导。

### 11.3 允许使用 Map 的例外（白名单）💡 SHOULD

仅以下三种情况可使用 Map，且应在代码注释中标注所属例外：

1. **跨系统透传**：请求体/返回体结构由对端（如 Python engine 的 Pydantic 模型）定义，Java 侧不重复建模，仅做透传。
   - 示例：`FactorController.compute` 返回 `ResponseEntity<String>`，透传 engine 原始 JSON 字符串。
2. **纯键值缓存返回**：返回值是「分组键 → 值列表」这类天然 Map 语义的聚合结构。
   - 示例：`ConstantController.getAllConstants` 返回 `Map<String, List<EnumOptionDTO>>`（枚举分类 → 选项列表）。
3. **Service 内部实现**：非接口契约的内部 Map（缓存字段、临时聚合、给下游 client 的参数 map 等）不受约束。

### 11.4 命名规范 ✅ MUST

| 用途 | 后缀 | 示例 |
|---|---|---|
| 前端请求体（Controller `@RequestBody` 入参） | `*RequestDTO` | `FactorComputeRequestDTO`、`CreateUserRequestDTO` |
| 前端返回体（Controller 返回 `ApiResponse<T>` 的 T） | `*ResponseDTO` | `UserCreateResponseDTO` |
| Service 内部传输 / 通用业务对象 | `*VO` | `FactorVO`、`StockVO` |
| GET 查询参数聚合 | `*QueryDTO` | `DailyQueryDTO` |

> **VO 复用原则**：当某个 `*VO` 的属性集与请求体或返回体完全一致时，可直接复用 `*VO`，无需为复用而额外创建 DTO；只有当两者字段不一致、或需要不同校验规则时，才单独建 `*RequestDTO` / `*ResponseDTO`。

### 11.5 既有 `*QueryDTO` 约定

tushare 模块历史遗留的 `*QueryDTO`（如 `DailyQueryDTO`、`StockBasicQueryDTO`）用于 GET 查询参数聚合，符合本规范，保留命名，不强改为 `*RequestDTO`。
