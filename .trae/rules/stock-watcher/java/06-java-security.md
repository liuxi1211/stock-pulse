---
alwaysApply: false
description: "当用户涉及 Java 安全、输入验证、SQL 注入防护、认证授权、密码安全、依赖安全、敏感数据保护等 Java 后端安全场景时触发。适用于编写安全的 Java 代码、设计认证授权机制、防护安全漏洞、检查安全风险等任务。仅适用于 stock-watcher Java 后端项目。关键词：Java安全, 安全, SQL注入, 认证, 授权, 密码, 漏洞, XSS, CSRF, 输入验证"
# Java 安全规范

> 适用于 stock-watcher（Java + Spring Boot + MyBatis-Plus）安全开发。

---

## 一、输入验证

### 1.1 验证原则 ✅ MUST

- **所有外部输入都不可信**，必须验证
- 验证包括：用户输入、API 参数、第三方数据、配置、环境变量
- 白名单验证优于黑名单验证
- 服务端验证是最后防线，不能依赖客户端验证

### 1.2 验证内容 ✅ MUST

| 类型 | 说明 |
|-----|------|
| **类型** | 数据类型正确（数字、字符串、日期等） |
| **长度** | 长度在允许范围内 |
| **格式** | 符合预期格式（正则） |
| **范围** | 数值在合法范围内 |
| **业务规则** | 符合业务逻辑约束 |

### 1.3 常用验证方式

- 简单校验：`StringUtils.isBlank()`、断言
- 对象校验：`@Valid` + JSR-303 注解
- 业务校验：Service 层判断后抛出 `BusinessException`

```java
// 参数非空校验
if (StringUtils.isBlank(stockCode)) {
    throw new IllegalArgumentException("stockCode不能为空");
}

// 格式校验
if (!tsCodePattern.matcher(stockCode).matches()) {
    throw new BusinessException(ErrorCode.INVALID_PARAM, "股票代码格式错误");
}
```

### 1.4 股票代码校验 💡 SHOULD

```java
// ts_code: 000001.SZ, 600000.SH
private static final Pattern TS_CODE_PATTERN = Pattern.compile("^\\d{6}\\.(SZ|SH)$");

// symbol: 000001, 600000
private static final Pattern SYMBOL_PATTERN = Pattern.compile("^\\d{6}$");
```

---

## 二、SQL 注入防护

### 2.1 使用参数化查询 ✅ MUST

- 使用 MyBatis-Plus 的方法（`selectById`、`selectList` 等）
- 使用 `#{}` 占位符，不用 `${}` 拼接
- 动态查询用 QueryWrapper / LambdaQueryWrapper

```xml
<!-- 安全：使用 #{} 参数化 -->
<select id="selectByTsCode" resultType="DailyQuoteDO">
    SELECT * FROM daily_quote WHERE ts_code = #{tsCode}
</select>

<!-- 危险 ❌：使用 ${} 直接拼接 -->
<select id="selectByTsCodeUnsafe" resultType="DailyQuoteDO">
    SELECT * FROM daily_quote WHERE ts_code = '${tsCode}'
</select>
```

### 2.2 MyBatis-Plus 使用 ✅ MUST

优先使用 MyBatis-Plus 提供的方法，自动防注入：

```java
// 安全：MyBatis-Plus 参数化
LambdaQueryWrapper<DailyQuoteDO> wrapper = Wrappers.<DailyQuoteDO>lambdaQuery()
        .eq(DailyQuoteDO::getTsCode, tsCode)
        .ge(DailyQuoteDO::getTradeDate, startDate);
List<DailyQuoteDO> list = list(wrapper);
```

### 2.3 动态 SQL 注意事项 💡 SHOULD

- 动态表名/列名时需要特别注意（不能用参数化）
- 必须白名单校验表名/列名
- 禁止用户输入直接拼接到 SQL

---

## 三、认证与授权

### 3.1 密码存储 ✅ MUST

- 使用 BCrypt 哈希存储密码
- 密码强度校验
- 禁止明文存储和传输
- 不在日志中输出密码

```java
// 注册时加密
String hashedPassword = passwordEncoder.encode(rawPassword);

// 登录时验证
boolean matches = passwordEncoder.matches(rawPassword, hashedPassword);
```

### 3.2 Session 管理 💡 SHOULD

- 会话超时时间合理设置
- 用户登出时销毁 session
- 敏感操作重新验证
- 生产环境使用 HTTPS，设置 Secure Cookie

### 3.3 TOTP 两步验证 ✅ MUST（管理员）

- 管理员账户启用 TOTP（Google Authenticator）
- TOTP secret 加密存储
- 开发环境可配置跳过，生产环境必须启用

### 3.4 权限控制 ✅ MUST

- 接口权限校验（`@RequireAdmin`）
- 数据权限：用户只能操作自己的数据（自选股等）
- 防止越权访问（IDOR）

```java
// 管理员接口
@RequireAdmin
@DeleteMapping("/users/{id}")
public ApiResponse<?> deleteUser(@PathVariable Long id) {
    userService.deleteUser(id);
    return ApiResponse.success(null);
}
```

### 3.5 拦截器规范

- 认证拦截器：`AuthInterceptor`
- 白名单路径（登录、注册、静态资源）放行
- 未登录返回 401

---

## 四、敏感数据保护

### 4.1 Tushare Token 安全 🔴 重点

- ✅ MUST Token 不得硬编码在代码中
- ✅ MUST Token 配置文件加入 `.gitignore`
- ✅ MUST Token 不得输出到日志
- ✅ MUST 提供配置模板文件，不包含真实值
- 💡 SHOULD 生产环境使用环境变量注入

### 4.2 日志安全 ✅ MUST

- 日志中不得输出密码、token、密钥
- 敏感信息（手机号、邮箱）脱敏输出
- 错误信息不泄露系统内部细节
- 生产环境关闭 debug 级别日志

```java
// 不好的 ❌
log.info("用户登录, username: {}, password: {}", username, password);

// 好的
log.info("用户登录, username: {}", username);
```

### 4.3 错误信息安全 💡 SHOULD

- 生产环境不返回详细错误堆栈
- 统一错误信息，不暴露内部实现
- 详细错误记录在日志中，不返回给前端

```java
// 全局异常处理
@ExceptionHandler(Exception.class)
public ApiResponse<?> handleException(Exception e) {
    log.error("系统异常", e); // 详细日志
    return ApiResponse.error(500, "系统内部错误"); // 友好提示
}
```

---

## 五、XSS 防护

### 5.1 Thymeleaf 默认转义 ✅ MUST

- Thymeleaf 默认会对表达式输出进行 HTML 转义
- 不要使用 `th:utext`（不转义）除非确实需要且内容可信
- 富文本内容使用白名单过滤

```html
<!-- 安全：默认转义 -->
<p th:text="${userInput}"></p>

<!-- 危险 ❌：不转义 -->
<p th:utext="${userInput}"></p>
```

### 5.2 输出转义 💡 SHOULD

- 所有用户输入在输出到页面时都要转义
- JSON 输出时注意 XSS（返回 Content-Type: application/json）
- 前端 JavaScript 操作 DOM 时使用安全的方法

---

## 六、CSRF 防护

### 6.1 CSRF Token 💡 SHOULD

- 表单提交使用 CSRF Token
- Thymeleaf 自动支持（`th:action` 会自动添加）
- AJAX 请求在 header 中携带 CSRF Token

### 6.2 安全配置

- 生产环境启用 CSRF 防护
- 开发环境可根据需要关闭
- 无状态 API（如 engine 调用 watcher 的接口）可酌情考虑

---

## 七、文件上传安全 📌 MAY

如涉及文件上传，需注意：

- 校验文件类型（白名单）
- 限制文件大小
- 随机化文件名
- 存储在 web 根目录之外
- 不执行上传的文件

---

## 八、依赖安全

### 8.1 依赖漏洞扫描 💡 SHOULD

- 定期检查依赖是否有已知漏洞
- 及时升级有安全漏洞的依赖
- 最小化依赖数量

### 8.2 依赖来源 ✅ MUST

- 只从可信源获取依赖（Maven Central 等）
- 不使用不明来源的 jar 包
- 验证依赖完整性（checksum）

---

## 九、项目特定安全清单

开发完成后，对照以下清单检查：

### 9.1 认证授权
- [ ] 密码使用 BCrypt 哈希存储
- [ ] 管理员接口有 @RequireAdmin 注解
- [ ] 用户只能操作自己的数据（自选股等）
- [ ] Session 有合理的过期时间
- [ ] 登出能正确销毁 Session

### 9.2 输入验证
- [ ] 所有 API 参数有校验
- [ ] 股票代码格式校验
- [ ] 分页参数有上限限制
- [ ] 非法输入返回 400 错误

### 9.3 SQL 注入
- [ ] 使用 MyBatis-Plus 参数化查询
- [ ] XML 中使用 #{} 而不是 ${}
- [ ] 动态表名/列名有白名单校验

### 9.4 敏感信息
- [ ] Tushare Token 不硬编码
- [ ] 敏感配置不提交 Git
- [ ] 日志中不输出密码、Token
- [ ] 错误信息不泄露内部细节

### 9.5 其他
- [ ] 生产环境关闭调试模式
- [ ] 生产环境 Actuator 端点限制
- [ ] 定时任务不会并发执行导致数据问题
- [ ] 没有注释掉的危险代码

