# Swagger 接口文档引入计划

## 1. 项目调研结论

### 1.1 项目基本信息
- **项目名称**: stock-watcher
- **框架**: Spring Boot 4.0.6
- **Java 版本**: 21
- **构建工具**: Maven
- **服务端口**: 8080
- **认证方式**: 基于 Session 的自定义拦截器（`AuthInterceptor`）
- **API 风格**: REST API + Thymeleaf 页面混合

### 1.2 现有接口梳理
项目共有 **16 个 Controller**，其中 **14 个 REST API Controller** 需要添加 Swagger 文档，2 个页面 Controller（`PageController`、`AuthController`）无需添加。

| Controller | 路径前缀 | 接口数 | 说明 |
|---|---|---|---|
| FactorController | `/api/factors` | 8 | 因子库管理与计算 |
| WatchlistController | `/api/watchlist` | 3 | 自选股管理 |
| UserApiController | `/api/users` | 4 | 用户管理（需管理员） |
| TushareApiController | `/api/tushare/daily` | 2 | Tushare 日线行情 |
| TradeCalController | `/api/tushare/trade-cal` | 2 | 交易日历 |
| StockBasicController | `/api/tushare/stock-basic` | 2 | 股票基础信息 |
| SearchController | `/api/search` | 2 | 股票搜索 |
| MarketController | `/api/market` | 2 | 市场行情 |
| KlineController | `/api/kline` | 1 | K线数据 |
| DividendController | `/api/tushare/dividend` | 2 | 分红送股 |
| AdjFactorController | `/api/tushare/adj-factor` | 2 | 复权因子 |
| DataInitController | `/api/tushare/data-init` | 2 | 数据初始化 |
| ConstantController | `/api/constants` | 1 | 常量枚举 |
| AuthController | `/login`, `/logout` | - | 认证页面（跳过） |
| PageController | `/`, `/stock-list` 等 | - | 页面路由（跳过） |

**总计**: 约 33 个 REST API 接口需要添加 Swagger 注解。

---

## 2. 技术选型

### 2.1 选用 springdoc-openapi
- **依赖**: `springdoc-openapi-starter-webmvc-ui`
- **版本**: `2.8.6`（兼容 Spring Boot 4.x / Spring Framework 7）
- **理由**:
  - Spring Boot 3.x+ 官方推荐的 OpenAPI 3.0 实现
  - 替代已停止维护的 Springfox（Swagger 2）
  - 支持 OpenAPI 3.0 规范，功能更强大
  - 与 Spring Boot 4.x 完全兼容

### 2.2 访问地址
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`

---

## 3. 实施步骤

### 步骤 1: 添加 Maven 依赖
**文件**: [pom.xml](file:///d:/lcProject/stock-pulse/stock-watcher/pom.xml)

在 `<dependencies>` 中添加：
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.6</version>
</dependency>
```

### 步骤 2: 创建 Swagger 配置类
**新建文件**: `stock-watcher/src/main/java/com/arthur/stock/config/OpenApiConfig.java`

配置内容：
- API 基本信息（标题、描述、版本）
- 接口扫描范围（`/api/**`）
- 按业务模块分组（因子库、市场行情、Tushare 数据、系统管理等）
- Session 认证说明

### 步骤 3: 配置拦截器白名单
**文件**: [WebConfig.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/WebConfig.java)

在 `addInterceptors` 的 `excludePathPatterns` 中添加 Swagger 相关路径：
- `/swagger-ui/**`
- `/swagger-ui.html`
- `/v3/api-docs/**`
- `/webjars/**`

**同时文件**: [AuthInterceptor.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/interceptor/AuthInterceptor.java)

在 `isPublicPath` 方法中添加 Swagger 路径判断（双重保险）。

### 步骤 4: 为 REST Controller 添加 Swagger 注解
为 14 个 REST API Controller 逐一添加注解：

| 注解 | 用途 | 位置 |
|---|---|---|
| `@Tag(name = "...", description = "...")` | 接口分组说明 | 类上 |
| `@Operation(summary = "...", description = "...")` | 单个接口说明 | 方法上 |
| `@Parameter(description = "...")` | 参数说明 | 方法参数上 |
| `@ApiResponse(responseCode = "200", description = "...")` | 响应说明 | 方法上 |

**涉及文件**（14 个）:
1. [FactorController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/FactorController.java)
2. [WatchlistController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/WatchlistController.java)
3. [UserApiController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/UserApiController.java)
4. [TushareApiController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/TushareApiController.java)
5. [TradeCalController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/TradeCalController.java)
6. [StockBasicController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/StockBasicController.java)
7. [SearchController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/SearchController.java)
8. [MarketController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/MarketController.java)
9. [KlineController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/KlineController.java)
10. [DividendController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/DividendController.java)
11. [AdjFactorController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/AdjFactorController.java)
12. [DataInitController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/DataInitController.java)
13. [ConstantController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/ConstantController.java)
14. [AuthController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/AuthController.java) — 仅标注跳过/不生成文档

### 步骤 5: 为 DTO/VO 添加 Swagger 注解
为常用的请求/响应模型添加 `@Schema` 注解，便于前端理解字段含义。

**涉及文件**（核心 DTO/VO）:
- [ApiResponse.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/ApiResponse.java)
- `dto/factor/` 下的 FactorCreateRequestDTO、FactorUpdateRequestDTO
- `dto/tushare/` 下的各类 DTO
- `vo/` 下的各类 VO（FactorVO、StockVO、KlineDataVO 等）

### 步骤 6: 配置文件补充（可选）
**文件**: [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)

添加 springdoc 配置（如是否启用、路径自定义等）。

---

## 4. 潜在依赖与注意事项

### 4.1 认证拦截器冲突
- **问题**: `AuthInterceptor` 拦截所有路径，Swagger UI 页面会被重定向到登录页
- **解决**: 在拦截器白名单中添加 Swagger 相关路径（步骤 3 已规划）

### 4.2 接口测试需要登录
- **问题**: Swagger UI 中直接调用需要登录的接口会返回 401
- **说明**: 由于项目使用 Session 认证，用户需先在另一个标签页登录系统，然后 Swagger UI 即可正常调用接口（同域 Cookie 共享）
- **可选优化**: 后续可考虑添加登录接口到 Swagger 或配置 ApiKey 认证方式

### 4.3 版本兼容性
- Spring Boot 4.0.6 与 springdoc-openapi 2.8.x 兼容
- 若遇到兼容性问题，可调整 springdoc 版本

### 4.4 PageController 和 AuthController
- 这两个 Controller 返回的是 Thymeleaf 页面视图，不是 REST API
- 不添加 Swagger 注解，通过配置只扫描 `/api/**` 路径自动排除

---

## 5. 风险处理

| 风险 | 影响 | 应对方案 |
|---|---|---|
| springdoc 版本与 Spring Boot 4.0.6 不兼容 | 启动失败 | 尝试 2.8.x 不同版本，或降级到 2.7.x |
| 拦截器配置遗漏，Swagger 页面无法访问 | 文档不可用 | 双重配置（WebConfig + AuthInterceptor.isPublicPath） |
| DTO/VO 注解缺失导致文档字段不清晰 | 文档可读性差 | 优先为核心 DTO 添加注解，其余逐步完善 |

---

## 6. 验证方式

1. **启动项目**: 运行 `StockWatcherApplication`
2. **访问 Swagger UI**: 浏览器打开 `http://localhost:8080/swagger-ui.html`
3. **检查分组**: 确认各业务模块分组正确显示
4. **检查接口数量**: 确认约 33 个接口全部展示
5. **测试调用**: 登录后在 Swagger UI 中测试几个 GET 接口，确认返回正常
6. **检查模型**: 确认请求/响应模型字段说明清晰
