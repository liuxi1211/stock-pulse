# 认证与权限（Session / TOTP / @RequireAdmin）

> 理解用户体系与权限控制模式。

---

## 1. 认证流程总览

```
用户发起请求
    │
    ▼
AuthInterceptor.preHandle()
    │
    ├─ 路径在【公开路径列表】中 → ✅ 放行
    │
    ├─ Session 中无 AUTH_USER → ⏭  重定向 /login
    │
    └─ Session 中有 AUTH_USER → 设置 UserContext
          │
          ├─ 用户 totpSecret 已设置（开启了两步验证）
          │    ├─ TOTP_VERIFIED = true  → ✅ 放行
          │    └─ TOTP_VERIFIED = false → ⏭  重定向 /login/2fa
          │
          └─ 用户 totpSecret 未设置（未开启两步验证）→ ✅ 放行
```

**开发环境例外**：当 `auth.admin-skip-totp: true` 时，管理员（`role=ADMIN`）即使设置了 totpSecret 也跳过第二步 TOTP 验证。

**AOP 权限检查**：请求被放行后，若 Controller / 方法上有 `@RequireAdmin`，由 `AdminCheckAspect` 二次拦截检查角色。

---

## 2. 公开路径（`AuthInterceptor.isPublicPath` 放行）

### 2.1 路径列表

| 路径模式 | 匹配方式 | 说明 |
|---------|---------|------|
| `/login` | `startsWith` | 登录页 / TOTP 设置 / 登录接口（含 `/login/2fa`、`/login/totp-setup`） |
| `/logout` | 精确 `equals` | 登出 |
| `/css/`, `/js/`, `/static/` | `startsWith` | Bootstrap / ECharts / 自定义脚本 |
| `/actuator/` | `startsWith` | Spring Boot Actuator（健康检查、监控端点） |
| `/.well-known/` | `startsWith` | 标准元数据 |
| `/favicon.ico` | 精确 `equals` | 图标 |
| `/api/tushare/data-init` | `startsWith` | 数据初始化接口（`/api/tushare/data-init` + `/api/tushare/data-init/status`） |
| `/error/` | `startsWith` | 错误页 |

### 2.2 匹配逻辑（新增公开路径时参考）

- **精确匹配**：`requestURI.equals("/favicon.ico")` — 路径完全一致
- **前缀匹配**：`requestURI.startsWith("/login")` — 路径以此开头即匹配（包含 `/login`、`/login/2fa`、`/login/totp-setup` 等）

### 2.3 新增公开路径的步骤

1. 打开 `AuthInterceptor`，在 `isPublicPath` 方法中加一条判断
2. 如果是前缀匹配，用 `startsWith`；如果是精确匹配，用 `equals`
3. 测试匿名访问该路径应放行；测试不应该被放行的路径不要误放
4. 在文档的路径列表中补充记录

---

## 3. Session 键常量（`SessionKeys`）

| key | 类型 | 含义 |
|-----|------|------|
| `AUTH_USER` | UserDO |  已登录用户对象 |
| `TOTP_VERIFIED` | Boolean | 是否已通过 TOTP 第二步验证 |
| `TARGET_URL` | String | 登录后回跳地址 |

---

## 4. 用户与角色

### 4.1 数据模型

```java
// UserDO (sys_user 表)
├── id          Long        主键
├── username    String    登录名
├── password    String    BCrypt 哈希
├── totpSecret  String    TOTP 密钥（Base32）
├── enabled     Boolean   启用/禁用
├── email       String
├── phone       String
├── role        Role      ADMIN / USER（枚举，存 code 值到 DB）
├── createdAt   LocalDateTime
└── updatedAt   LocalDateTime
```

### 4.2 Role 枚举
- `Role.ADMIN`：管理员（用户管理、系统配置）
- `Role.USER`：普通用户（仅行情与自选）

---

## 5. `@RequireAdmin` 权限注解

- **使用场景**：类/方法级
- **工作原理**：`AdminCheckAspect` AOP 切面拦截，通过 `UserContext.isAdmin()` 判断
- **失败响应**：抛出 `BusinessException(ErrorCode.FORBIDDEN)` → `ApiResponse.error(403,...)`
- **注意**：只对已登录用户生效；未登录用户由 `AuthInterceptor` 先拦截

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@RequireAdmin   // 整个 Controller 都需要管理员
public class UserApiController { ... }
```

```java
// 或方法级
@DeleteMapping("/{id}")
@RequireAdmin
public ApiResponse<Void> delete(@PathVariable Long id) { ... }
```

---

## 6. `UserContext` 使用注意事项

- `UserContext` 基于 `ThreadLocal<UserDO>`，在 `AuthInterceptor` 中设置/清理：

```java
UserDO user = UserContext.get();
Long userId = UserContext.getUserId();
boolean isAdmin = UserContext.isAdmin();
```

⚠️ **只在请求线程中有效；不要在异步线程/定时任务中使用。

---

## 7. TOTP 双因素认证

- **库**：`com.warrenstrange:googleauth:1.5.0`
- **流程**：
  1. 用户首次登录（或管理员重置后） → 生成密钥 → 展示 QR 码 → 用户扫描并设置
  2. 后续每次登录 → 输入 6 位动态码 → `TotpUtil.verify(totpSecret, code)`
- **密钥存储**：`UserDO.totpSecret`（Base32 字符串）
- **跳过开关**（仅开发环境）：
  ```yaml
  auth:
    admin-skip-totp: true
  ```

---

## 8. 密码存储

- **加密**：`PasswordEncoderFactories.createDelegatingPasswordEncoder()` → BCrypt
- 默认管理员密码：schema.sql 中写入 BCrypt 哈希 `$2a$10$pfuIlLGBbNZqO5xXa9oRKeEFABc4FIxs2SVY46UUG1xpA7o9tGn9u` → 明文是 `admin123`
- 新用户：AuthService.create → `passwordEncoder.encode(rawPassword)`

---

## 9. 新增权限控制的 Checklist

- [ ] 确定接口是否应限制为管理员
- [ ] 类/方法上添加 `@RequireAdmin` 注解
- [ ] 检查 `AuthInterceptor` 公开路径不要包含新管理员路径
- [ ] 若新增角色值 → 在 `Role` 枚举中加项
- [ ] 若新增公开接口 → 测试匿名访问
- [ ] 前端页面按钮用 `th:if="${#authorization.expression('...')}"` 控制
