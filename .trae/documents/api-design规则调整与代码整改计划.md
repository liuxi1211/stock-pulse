# 04-api-design 规则调整 + 代码整改计划

> 目标：①把 stock-watcher 所有 REST 接口的 `/api` 前缀去掉（前后端同步改）；②在规则文件里新增「>5 个参数必须封装对象 / 禁用 Map」的强约束，并把存量违规代码整改到位。
> 作用域：**仅 stock-watcher（Java 后端 + 前端 JS/Thymeleaf）**。stock-engine 的 `/python/v1/factors` 不在本次范围。

---

## 一、当前状态分析（基于 Phase 1 探查）

### 1. `/api` 前缀分布（13 处 Controller）

| Controller | 当前 `@RequestMapping` |
|---|---|
| DataInitController | `/api/tushare/data-init` |
| UserApiController | `/api/users` |
| FactorController | `/api/factors` |
| KlineController | `/api/kline` |
| AdjFactorController | `/api/tushare/adj-factor` |
| DividendController | `/api/tushare/dividend` |
| TushareApiController | `/api/tushare/daily` |
| TradeCalController | `/api/tushare/trade-cal` |
| StockBasicController | `/api/tushare/stock-basic` |
| ConstantController | `/api/constants` |
| SearchController | `/api/search` |
| MarketController | `/api/market` |
| WatchlistController | `/api/watchlist` |

> AuthController 是 `@Controller`（Thymeleaf 页面，`/login`、`/login/2fa` 等），**不走 `/api`，不动**。
> PageController 同理（页面），不动。

### 2. 前端 `/api/` 调用点

- `static/js/factor-library.js`：11 处 `/api/factors`、`/api/kline`
- `static/js/dashboard.js`：6 处 `/api/kline`、`/api/watchlist`、`/api/market`
- `static/js/common.js`：2 处 `/api/watchlist`、`/api/constants`
- `static/js/search-suggest.js`：1 处 `/api/search/suggest`
- `static/js/user-management.js`：4 处 `/api/users`
- `templates/pages/watchlist.html`：2 处 `/api/watchlist`
- `templates/pages/stock-list.html`：1 处 `/api/search`

### 3. 配置/拦截器中的 `/api` 引用

- `WebConfig.addCorsMappings`：`registry.addMapping("/api/**")` → 改成具体模块或 `/**`
- `WebConfig.addInterceptors`：当前是 `addPathPatterns("/**")`，**无需改**
- `AuthInterceptor.isPublicPath`：硬编码 `/api/tushare/data-init`、`/api/tushare/data-init/status` → 同步去前缀

### 4. 现有 Map 使用盘点（需整改）

| 位置 | 类型 | 处置 |
|---|---|---|
| `UserApiController.create` | 返回 `Map<String,Object>`（user/otpAuthUrl/secret） | 新建 `UserCreateResponseDTO` |
| `UserApiController.resetTotp` | 返回 `Map<String,String>`（secret/otpAuthUrl） | 新建 `UserTotpResetResponseDTO` |
| `FactorController.compute` | `@RequestBody Map<String,Object>` 透传 engine | **请求体改 DTO**（`FactorComputeRequestDTO`），返回体 `ResponseEntity<String>` 因透传 engine 原始串保留 |
| `FactorController.batchCompute` | 同上 | 请求体改 DTO（`FactorBatchComputeRequestDTO`），返回体保留 |
| `ConstantController.getAllConstants` | 返回 `Map<String, List<EnumOptionDTO>>` | **属于枚举缓存键值结构例外**（见规则例外清单），保留 |
| `ConstantController.cache` 字段 | 内部 `Map` 缓存 | 内部实现，不属于接口契约，保留 |

### 5. >5 参数盘点

按「仅 `@RequestParam`/`@PathVariable` 计数」口径，**当前所有接口参数个数 ≤ 4**（最多的是 TushareApi.query / StockBasic / Kline / AdjFactor / TradeCal 的 4 个）。**存量无违规**，此规则为新增的前瞻性约束，仅写入规则文件，无需立即改代码。

> 注：`UserApiController.create` 用的 `@RequestBody CreateUserRequestDTO` 是 DTO，不计数；但其 service 调用 `createUser(username,password,email,phone,role)` 是 5 个参数——service 层不在本规则管辖范围（规则仅约束 HTTP 接口）。

---

## 二、命名约定决策（写入规则）

| 用途 | 后缀 | 示例 |
|---|---|---|
| 前端请求体（Controller 入参） | `*RequestDTO` | `FactorComputeRequestDTO`、`CreateUserRequestDTO` |
| 前端返回体（Controller 返回） | `*ResponseDTO` | `UserCreateResponseDTO` |
| Service 内部传输 / 通用结构 | `*VO` | `FactorVO`、`StockVO` |
| 已有 `*QueryDTO`（tushare 模块） | 保留现状 | `DailyQueryDTO` |

> 约定：当 `*VO` 的属性集与请求/返回完全一致时，可直接复用 `*VO`，不必为复用而强造 DTO。

---

## 三、规则文件修改（`.trae/rules/stock-watcher/java/04-api-design.md`）

### 改动 1：去除 `/api` 前缀的强约束

- **§1.1 / §1.3 / §2.2 / §7.1 / §8.1** 中所有 `/api/xxx` 示例与「所有 REST API 以 `/api/` 开头」改为「**直接以模块名开头**」，示例统一去掉 `/api`：
  - `GET /stocks`、`POST /watchlist/items`、`GET /kline/{stockCode}` 等。
- **§2.2 API 前缀**整段重写：
  - REST API 直接以模块路径开头（如 `/kline`、`/watchlist/items`），**不再使用 `/api/` 前缀**。
  - 页面请求（Thymeleaf）走根路径或 `/login` 等业务路径，不与 REST 模块冲突。
- **§8.1 REST API 接口** 的「前缀：`/api/`」改为「前缀：无（直接模块名）」。

### 改动 2：新增「§十一、参数对象化与类型规范」

新增整节（置于「十、接口文档」之后或之前，按逻辑放 §九 之后）：

```
## 十一、参数对象化与类型规范

### 11.1 参数个数约束 ✅ MUST
- HTTP 接口方法中 @RequestParam + @PathVariable 合计 > 5 个时，必须封装为对象（@RequestBody *RequestDTO 或把 query 参数聚合为 *QueryDTO）。
- 计数口径：仅统计 @RequestParam 与 @PathVariable；@RequestBody DTO、HttpSession、Model、HttpServletRequest 等不计入。

### 11.2 禁止使用 Map ✅ MUST
- 接口的请求体、返回体禁止使用 Map<?,?>（含 Map<String,Object> / Map<String,String>）。
- 必须使用 *RequestDTO / *ResponseDTO / *VO 等显式类型。

### 11.3 允许使用 Map 的例外（白名单）💡 SHOULD
仅以下情况可使用 Map：
1. 跨系统透传：请求体/返回体结构由对端（如 Python engine 的 Pydantic 模型）定义，Java 侧不重复建模，仅做透传（如 compute 接口的 ResponseEntity<String> 原始体透传）。
2. 纯键值缓存返回：返回值是「分组键 → 枚举选项列表」这类天然 Map 语义的聚合结构（如 ConstantController 的枚举常量缓存）。
3. Service 内部实现：非接口契约的内部 Map（缓存字段、临时聚合等）不受约束。

### 11.4 命名规范 ✅ MUST
- 前端请求体：*RequestDTO
- 前端返回体：*ResponseDTO
- Service 内部传输 / 通用结构：*VO（当属性集与请求/返回完全一致时，可直接复用 *VO，无需额外造 DTO）
```

---

## 四、代码整改清单

### A. 去 `/api` 前缀（Java 13 处 + 前端 27 处 + 配置 2 处）

#### A1. Java Controller `@RequestMapping`（13 文件）

去掉每个 `@RequestMapping("/api/...")` 中的 `/api`，结果：

| 文件 | 改为 |
|---|---|
| DataInitController | `/tushare/data-init` |
| UserApiController | `/users` |
| FactorController | `/factors` |
| KlineController | `/kline` |
| AdjFactorController | `/tushare/adj-factor` |
| DividendController | `/tushare/dividend` |
| TushareApiController | `/tushare/daily` |
| TradeCalController | `/tushare/trade-cal` |
| StockBasicController | `/tushare/stock-basic` |
| ConstantController | `/constants` |
| SearchController | `/search` |
| MarketController | `/market` |
| WatchlistController | `/watchlist` |

#### A2. 前端 JS（5 文件 24 处）

逐文件 sed 式替换（去掉路径中的 `/api`）：
- `factor-library.js`：11 处 → `/factors`、`/kline`
- `dashboard.js`：6 处 → `/kline`、`/watchlist`、`/market`
- `common.js`：2 处 → `/watchlist`、`/constants`
- `search-suggest.js`：1 处 → `/search/suggest`
- `user-management.js`：4 处 → `/users`

#### A3. 前端 Thymeleaf（2 文件 3 处）

- `templates/pages/watchlist.html`：2 处 `/api/watchlist` → `/watchlist`
- `templates/pages/stock-list.html`：1 处 `/api/search` → `/search`

#### A4. 配置与拦截器（2 文件）

- `WebConfig.addCorsMappings`：`"/api/**"` → `"/**"`（或显式列出各模块，推荐 `"/**"` 简单）。
- `AuthInterceptor.isPublicPath`：
  - `"/api/tushare/data-init"` → `"/tushare/data-init"`
  - `"/api/tushare/data-init/status"` → `"/tushare/data-init/status"`

### B. Map 整改（4 个接口 + 新建 3 个 DTO）

#### B1. UserApiController（2 处）

- 新建 `dto/UserCreateResponseDTO.java`：字段 `UserDO user`、`String otpAuthUrl`、`String secret`（可考虑把 user 也改成不含敏感字段的 UserVO，但本次最小改动保留 UserDO）。
  - 替换 `create` 方法返回类型 `ApiResponse<Map<String,Object>>` → `ApiResponse<UserCreateResponseDTO>`，`Map.of(...)` → `new UserCreateResponseDTO(...)`。
- 新建 `dto/UserTotpResetResponseDTO.java`：字段 `String secret`、`String otpAuthUrl`。
  - 替换 `resetTotp` 方法返回类型 `ApiResponse<Map<String,String>>` → `ApiResponse<UserTotpResetResponseDTO>`。
- 删除 `UserApiController` 顶部 `import java.util.Map;`（若不再使用）。

#### B2. FactorController（2 处，仅请求体改 DTO）

- 新建 `dto/factor/FactorComputeRequestDTO.java`：与 engine 端 `FactorComputeRequest`（Pydantic）字段对齐（`data`、`factors` 列表）。若结构复杂，可暂时用与 engine 同名的简化结构。
- 新建 `dto/factor/FactorBatchComputeRequestDTO.java`：对齐 engine `BatchFactorComputeRequest`。
- 替换：
  - `compute(@RequestBody Map<String,Object> body)` → `compute(@RequestBody FactorComputeRequestDTO body)`
  - `batchCompute(@RequestBody Map<String,Object> body)` → `batchCompute(@RequestBody FactorBatchComputeRequestDTO body)`
- **返回体保留** `ResponseEntity<String>`（透传 engine 原始 JSON 字符串，符合例外 11.3-1）。
- `FactorService.compute/batchCompute` 入���类型同步由 `Map<String,Object>` 改为对应 DTO；若 service 内部需要 Map 给 EngineClient，可在 service 内 `JSON.parseObject` 转。
- 删除 `FactorController` 顶部 `import java.util.Map;`。

#### B3. ConstantController（不动，符合例外 11.3-2）

`getAllConstants` 返回 `Map<String, List<EnumOptionDTO>>`，属于「分组键 → 枚举列表」天然 Map 语义，规则白名单覆盖，保留并在代码注释标注属于例外。

---

## 五、假设与决策

1. **stock-engine 不动**：`/python/v1/factors` 前缀保留，EngineClient.BASE 也保留（用户确认仅改 watcher）。
2. **参数阈值口径**：仅 `@RequestParam`+`@PathVariable` 计数，`@RequestBody DTO` 不计入。当前无违规，规则为前瞻约束。
3. **Map 例外清单**：跨系统透传 / 枚举缓存键值 / Service 内部实现 三类豁免，写入规则 §11.3。
4. **命名沿用**：现有 `*QueryDTO`（tushare 模块）保留，不强改名为 `*RequestDTO`，避免大面积无意义重命名（仅新增接口遵循新规范）。
5. **VO 复用**：当 `*VO` 字段与请求/返回一致时可直接复用，规则中明确允许，避免过度建模。
6. **AuthController/PageController** 是页面控制器，非 REST，不在去前缀范围。

---

## 六、验证步骤

### 6.1 编译与启动
- `mvnw clean compile`（stock-watcher 目录）确认 Java 无编译错误。
- 启动应用，确认无 Bean 装配/路由冲突。

### 6.2 接口冒烟（去前缀后）
- `GET http://localhost:8080/constants`（原 `/api/constants`）
- `GET http://localhost:8080/factors`
- `GET http://localhost:8080/kline/000001.SZ?period=daily`
- `GET http://localhost:8080/users`（需管理员登录）
- `POST http://localhost:8080/factors/compute`（带 FactorComputeRequestDTO body）

### 6.3 前端联调
- 打开 dashboard、watchlist、stock-list、factor-library、user-management、settings 页面，验证所有 AJAX 请求路径正确（浏览器 Network 面板观察）。
- 重点验证 search-suggest 自动补全、watchlist 增删、factor-library 试算。

### 6.4 CORS / 拦截器
- 确认 `WebConfig` 的 CORS 仍生效（改 `/**` 后）。
- 确认未登录访问 `/constants` 等被 AuthInterceptor 重定向或返回 401。
- 确认 `/tushare/data-init` 仍是公开路径（去前缀后 isPublicPath 命中）。

### 6.5 Map 整改回归
- 创建用户接口返回 `UserCreateResponseDTO` 结构（`{user, otpAuthUrl, secret}`），前端 user-management.js 的 `resp.data.user/otpAuthUrl/secret` 字段访问仍可用。
- reset-totp 返回 `UserTotpResetResponseDTO`。
- factor compute 接口透传 engine 响应体不变。

### 6.6 规则文件自检
- 打开 `04-api-design.md`，确认 `/api` 字样已清除、§11 新增章节完整、例外清单清晰。
