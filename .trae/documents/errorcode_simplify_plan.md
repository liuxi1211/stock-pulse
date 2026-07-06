# ErrorCode 简化改造计划

## 1. 现状分析

### 当前 ErrorCode 枚举（共 16 个）
[ErrorCode.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/exception/ErrorCode.java)

| 分类 | 错误码 | 说明 |
|------|--------|------|
| HTTP 标准 | 400 BAD_REQUEST | 请求参数错误 |
| HTTP 标准 | 401 UNAUTHORIZED | 未登录 |
| HTTP 标准 | 403 FORBIDDEN | 无权限 |
| HTTP 标准 | 404 NOT_FOUND | 资源不存在 |
| HTTP 标准 | 409 CONFLICT | 数据冲突 |
| 用户模块 | 1001 USER_EXISTS | 用户名已存在 |
| 用户模块 | 1002 USER_NOT_FOUND | 用户不存在 |
| 用户模块 | 1003 SELF_DELETE | 不能删除自己 |
| 用户模块 | 1004 STOCK_NOT_FOUND | 股票不存在 |
| 用户模块 | 1005 WATCHLIST_EXISTS | 已在自选股中 |
| 用户模块 | 1006 INVALID_ROLE | 无效的角色值 |
| 服务模块 | 2001 PYTHON_SERVICE_UNAVAILABLE | 计算服务不可用 |
| 选股模块 | 1100 SCREEN_PLAN_NOT_FOUND | 选股方案不存在 |
| 选股模块 | 1101 SCREEN_RESULT_NOT_FOUND | 选股结果不存在 |
| 选股模块 | 1102 SCREEN_CONFIG_INVALID | 选股方案配置非法 |
| 选股模块 | 1103 SCREEN_LOCK_NOT_FOUND | 选股锁定记录不存在 |
| 选股模块 | 1104 SCREEN_LOCK_ALREADY_EXISTS | 选股结果已锁定 |

### 使用情况
- 共 8 个文件使用了 ErrorCode，共 44 处引用
- 大部分场景都已使用 `new BusinessException(ErrorCode.XXX, message)` 传递自定义消息
- 现有 [BusinessException.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/exception/BusinessException.java) 已支持 code + message 构造方式

---

## 2. 简化方案

### 保留的通用错误码（5 个）
只保留 HTTP 标准语义的通用错误码，前端可根据 code 做通用处理：

| code | 枚举名 | 说明 | 前端处理方式 |
|------|--------|------|-------------|
| 200 | SUCCESS | 操作成功 | 正常展示数据 |
| 400 | BAD_REQUEST | 请求错误（参数错误/业务校验失败/数据冲突等） | 弹出错误提示 |
| 401 | UNAUTHORIZED | 未登录或登录已过期 | 跳转登录页 |
| 403 | FORBIDDEN | 无权限 | 提示无权限 |
| 404 | NOT_FOUND | 资源不存在 | 提示资源不存在 |
| 500 | INTERNAL_ERROR | 服务器内部错误/服务不可用 | 提示系统错误 |

### 核心原则
- **code 只表示错误类别**，不表示具体业务错误
- **具体业务错误信息全部通过 message 字段传递**，前端直接展示 message 给用户即可
- 删除所有业务自定义错误码（1001-1006、1100-1104、2001、409 CONFLICT）

---

## 3. 需修改的文件清单

### 3.1 核心改造文件

| 文件 | 修改内容 |
|------|---------|
| [ErrorCode.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/exception/ErrorCode.java) | 简化为 6 个通用枚举值 |

### 3.2 业务代码修改（替换 ErrorCode 使用）

| 文件 | 修改点 |
|------|--------|
| [AdminCheckAspect.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/aspect/AdminCheckAspect.java) | FORBIDDEN 保留（已是通用码） |
| [AbstractEngineClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/AbstractEngineClient.java) | PYTHON_SERVICE_UNAVAILABLE → INTERNAL_ERROR，保留 message |
| [UserApiController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/UserApiController.java) | INVALID_ROLE/SELF_DELETE/USER_NOT_FOUND → BAD_REQUEST，保留 message |
| [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) | CONFLICT/STOCK_NOT_FOUND/BAD_REQUEST → BAD_REQUEST，保留 message |
| [ScreenConfigValidator.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/util/ScreenConfigValidator.java) | SCREEN_CONFIG_INVALID → BAD_REQUEST，保留 message |
| [ScreenerServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/ScreenerServiceImpl.java) | SCREEN_RESULT_NOT_FOUND/SCREEN_LOCK_NOT_FOUND/SCREEN_PLAN_NOT_FOUND → NOT_FOUND；SCREEN_LOCK_ALREADY_EXISTS/SCREEN_CONFIG_INVALID → BAD_REQUEST，保留 message |
| [UserServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/UserServiceImpl.java) | USER_EXISTS → BAD_REQUEST，保留 message |
| [WatchlistServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/WatchlistServiceImpl.java) | STOCK_NOT_FOUND/WATCHLIST_EXISTS → BAD_REQUEST 或 NOT_FOUND，保留 message |

### 3.3 测试文件同步修改

| 文件 | 修改内容 |
|------|---------|
| [ScreenerServiceImplTest.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/test/java/com/arthur/stock/service/ScreenerServiceImplTest.java) | 同步更新错误码断言 |

---

## 4. 实施步骤

1. **简化 ErrorCode 枚举**：删除所有业务错误码，只保留 6 个通用码
2. **批量替换业务代码**：将所有使用已删除 ErrorCode 的地方替换为对应的通用码
3. **更新测试用例**：同步修改测试文件中的错误码断言
4. **编译验证**：运行 `mvn compile` 确保没有编译错误
5. **运行测试**：执行单元测试确保功能正常

---

## 5. 风险与注意事项

- **前端兼容**：需要同步告知前端，业务错误信息直接展示 `message` 字段即可，无需根据 `code` 判断具体业务类型
- **向后兼容**：本次修改不改变 API 响应结构（仍是 code/message/data），仅减少 code 取值，前端不会有破坏性影响
- **日志记录**：GlobalExceptionHandler 中已记录 code 和 message，不影响问题排查
- **CONFLICT(409) 合并**：原 409 CONFLICT 场景（如重复初始化）合并到 400 BAD_REQUEST，message 中说明具体冲突原因
