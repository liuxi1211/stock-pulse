---
alwaysApply: false
description: "当用户涉及 Spring Boot 开发、后端架构设计、依赖注入、配置管理、AOP、Bean 管理等场景时触发。适用于开发 Spring Boot 应用、设计后端分层架构、配置 Spring 容器、使用 Spring 特性等任务。仅适用于 stock-watcher Java 后端项目。关键词：Spring Boot, Spring, 后端, controller, service, 依赖注入, Bean, 配置"
# Spring Boot 最佳实践

> 适用于 stock-watcher（Spring Boot 4 + MyBatis-Plus）后端开发。

---

## 一、分层架构

### 1.1 标准分层 ✅ MUST

```
controller（控制层） → service（业务层） → mapper（数据访问层） → db（数据库）
```

| 层级 | 职责 | 不做什么 |
|-----|------|---------|
| **Controller** | 接收请求、参数校验、调用 Service、返回响应 | 不写业务逻辑、不直接操作数据库 |
| **Service** | 业务逻辑处理、事务管理、数据转换 | 不直接接收 HTTP 请求、不输出 JSON |
| **Mapper** | 数据库 CRUD、SQL 编写 | 不写业务逻辑 |

### 1.2 各层调用规则 ✅ MUST

- Controller → Service：Controller 调用 Service 接口
- Service → Mapper：Service 调用 Mapper
- **禁止**：Controller 直接调用 Mapper
- **禁止**：Service 之间循环依赖
- **禁止**：跨层调用（如 Controller 直接操作数据库）

### 1.3 项目目录结构 💡 SHOULD

```
com.arthur.stock
├── annotation/          自定义注解
├── aspect/              AOP 切面
├── cache/               缓存相关
├── client/              外部客户端（TushareClient）
├── config/              配置类
├── constant/            常量与枚举
├── context/             上下文（ThreadLocal 等）
├── controller/          Controller
├── dto/                 数据传输对象
│   └── tushare/         Tushare 相关 DTO
├── exception/           异常类 + 全局异常处理
├── interceptor/         拦截器
├── mapper/              MyBatis-Plus Mapper
├── model/               数据模型（*DO）
├── service/             Service 接口
│   └── impl/            Service 实现
├── task/                定时任务
├── util/                工具类
└── vo/                  视图对象
```

---

## 二、依赖注入

### 2.1 构造器注入 ✅ MUST

- 使用构造器注入，**禁止**字段注入（`@Autowired` 字段）
- 配合 Lombok `@RequiredArgsConstructor`
- 依赖为 `final`，不可变

```java
// 推荐
@Service
@RequiredArgsConstructor
public class KlineServiceImpl implements KlineService {
    private final KlineMapper klineMapper;
    private final StockCodeCache stockCodeCache;
}

// 不推荐 ❌
@Service
public class KlineServiceImpl implements KlineService {
    @Autowired
    private KlineMapper klineMapper;
}
```

### 2.2 Bean 命名 💡 SHOULD

- 默认使用类名首字母小写
- 多个实现时明确指定 bean 名称
- 避免不必要的自定义命名

---

## 三、配置管理

### 3.1 配置文件 ✅ MUST

| 文件 | 用途 |
|-----|------|
| `application.yml` | 主配置，公共配置 |
| `application-dev.yml` | 开发环境 |
| `application-prod.yml` | 生产环境 |
| `application-secret.properties` | 敏感配置（不提交 Git） |
| `application-secret.properties.template` | 敏感配置模板 |

### 3.2 配置注入 💡 SHOULD

- 使用 `@Value` 注入单个配置
- 使用 `@ConfigurationProperties` 注入一组配置
- 配置类放在 `config/` 目录下

```java
@Configuration
@ConfigurationProperties(prefix = "tushare")
@Data
public class TushareConfig {
    private String baseUrl;
    private String token;
    private RateLimit rateLimit;

    @Data
    public static class RateLimit {
        private int permitsPerMinute;
        private int permitsPerSecond;
    }
}
```

### 3.3 敏感配置 ✅ MUST

- 敏感配置（token、密码、密钥）不提交到 Git
- 使用 `application-secret.properties` 或环境变量
- 提供 `.template` 模板文件
- `@Value` 注入时不硬编码默认值（敏感的）

---

## 四、Controller 规范

### 4.1 注解使用 ✅ MUST

- `@RestController`：REST API 控制器
- `@RequestMapping`：类级别统一前缀
- HTTP 方法：`@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping`

```java
@RestController
@RequestMapping("/api/kline")
@RequiredArgsConstructor
public class KlineController {

    private final KlineService klineService;

    @GetMapping("/{stockCode}")
    public ApiResponse<List<KlineDataVO>> getKlineData(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "daily") String period) {
        return ApiResponse.success(klineService.getKlineData(stockCode, period));
    }
}
```

### 4.2 统一返回 ✅ MUST

- 所有 API 返回 `ApiResponse<T>`
- `code=200` 表示成功
- 失败时通过 `GlobalExceptionHandler` 统一处理

```java
@Data
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> error(int code, String message) { ... }
}
```

### 4.3 参数校验 💡 SHOULD

- 简单参数：方法内校验
- 复杂对象：使用 `@Valid` + JSR-303 注解
- 校验失败抛出 `BusinessException`

### 4.4 Controller 职责边界 ✅ MUST

**Controller 只做：**
- 接收 HTTP 请求
- 参数基本校验
- 调用 Service
- 封装返回结果

**Controller 不做：**
- 业务逻辑
- 数据库操作
- 复杂的数据转换

---

## 五、Service 规范

### 5.1 接口 + 实现 ✅ MUST

- Service 定义接口 + `impl/` 目录下的实现类
- 接口在 `service/`，实现在 `service/impl/`
- 其他层依赖接口，不依赖实现

```java
// 接口
public interface KlineService {
    List<KlineDataVO> getKlineData(String stockCode, String period);
}

// 实现
@Service
@RequiredArgsConstructor
@Slf4j
public class KlineServiceImpl implements KlineService {
    // ...
}
```

### 5.2 事务管理 💡 SHOULD

- 使用 `@Transactional` 注解
- 只读操作加 `readOnly = true`
- 事务范围尽量小
- 明确指定 rollbackFor

```java
@Transactional(rollbackFor = Exception.class)
public void batchUpdate(List<DailyQuoteDO> list) {
    // ...
}

@Transactional(readOnly = true)
public List<DailyQuoteDO> listByDate(String tradeDate) {
    // ...
}
```

### 5.3 Service 方法粒度 💡 SHOULD

- 一个方法一个职责
- 方法名清晰表达功能
- 避免过于庞大的方法（超过 100 行考虑拆分）

---

## 六、AOP 使用规范

### 6.1 适用场景 💡 SHOULD

AOP 适合处理横切关注点：

- 日志记录（请求日志、方法耗时）
- 权限检查（`@RequireAdmin`）
- 性能监控
- 事务管理（Spring 已提供）

### 6.2 不适用场景 ❌

- 核心业务逻辑不要用 AOP
- 不要过度使用 AOP 导致代码难以理解

### 6.3 项目现有 AOP

| 切面 | 用途 |
|-----|------|
| `AdminCheckAspect` | `@RequireAdmin` 权限检查 |
| `RequestLogAspect` | 请求日志记录 |

---

## 七、缓存规范

### 7.1 缓存技术 ✅ MUST

- Spring Cache + Caffeine
- 使用 `@Cacheable`、`@CacheEvict`、`@CachePut` 注解

### 7.2 缓存命名 💡 SHOULD

- 缓存名称有意义，按功能分组
- key 要唯一，包含必要参数

```java
@Cacheable(value = "kline", key = "#stockCode + '_' + #period")
public List<KlineDataVO> getKlineData(String stockCode, String period) {
    // ...
}
```

### 7.3 缓存使用场景 💡 SHOULD

**适合缓存：**
- 读取频繁、写入少的数据
- 计算成本高的结果
- 允许一定延迟的数据

**不适合缓存：**
- 实时性要求高的数据
- 频繁变化的数据
- 敏感数据（视情况而定）

### 7.4 缓存失效 ✅ MUST

- 数据更新时清除缓存
- 设置合理的过期时间
- 注意缓存穿透、缓存击穿、缓存雪崩

---

## 八、定时任务规范

### 8.1 任务位置 ✅ MUST

- 定时任务放在 `task/` 目录
- 类名以 `Task` 结尾
- 使用 `@Scheduled` 注解

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DailyUpdateTask {

    @Scheduled(cron = "0 0 16 * * ?")
    public void dailyUpdate() {
        log.info("开始执行每日数据更新任务");
        // ...
        log.info("每日数据更新任务完成");
    }
}
```

### 8.2 任务编写规范 ✅ MUST

- 每个步骤独立 try-catch，一步失败不影响其他步骤
- 任务开始和结束打日志
- 加上任务名称，便于排查
- 避免任务重复执行（考虑分布式锁或幂等）
- 长时间任务考虑异步执行

### 8.3 Cron 表达式 💡 SHOULD

- 使用 6 位或 7 位 cron 表达式
- 避开整点（如 16:00:00），选偏移时间（如 16:05:00）
- 任务之间错峰执行

---

## 九、异常处理规范

### 9.1 全局异常处理 ✅ MUST

- 使用 `@RestControllerAdvice` 全局处理异常
- 统一返回 `ApiResponse` 格式
- 不同异常类型不同处理

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.error(500, "系统内部错误");
    }
}
```

### 9.2 业务异常 ✅ MUST

- 业务异常使用 `BusinessException`
- 配合 `ErrorCode` 枚举使用
- 异常信息对用户友好

```java
throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
throw new BusinessException(ErrorCode.INVALID_PARAM, "股票代码格式错误");
```

---

## 十、配置类规范

### 10.1 配置类位置 ✅ MUST

- 配置类放在 `config/` 目录
- 类名以 `Config` 结尾

### 10.2 常用配置类

| 配置类 | 用途 |
|-------|------|
| `MyBatisPlusConfig` | MyBatis-Plus 配置 |
| `CacheConfig` | 缓存配置 |
| `WebConfig` | Web MVC 配置（拦截器、CORS 等） |
| `SecurityConfig` | 安全配置 |
| `TushareConfig` | Tushare 配置 |

---

## 十一、Maven 依赖管理规范

### 11.1 依赖选择原则 ✅ MUST

#### 1. 必要性原则
- 真的需要才引入，不要为了"可能用到"提前加依赖
- JDK / Spring Boot 已提供的功能，不要再引第三方
- 一个小功能为了它引入一个大包，要掂量掂量

```xml
<!-- 反例 ❌ 为了用一个 StringUtils 引入整个 commons-lang3
     Spring 自带的 StringUtils 够用就不要额外引入 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-lang3</artifactId>
</dependency>
```

#### 2. 活跃度原则
- 选社区活跃、持续维护的依赖
- 看最近更新时间（超过 2 年没更新的要谨慎）
- 看 Issue 处理速度

#### 3. 口碑原则
- Star 数多、使用者多的优先（GitHub Stars、Maven 下载量）
- 有没有知名公司/项目在用
- 有没有严重的已知 bug 或安全漏洞

#### 4. 体积原则
- 优先选轻量的，避免依赖膨胀
- 注意传递依赖（一个依赖可能带进来一堆子依赖）
- 用 `mvn dependency:tree` 检查依赖树

#### 5. License 原则
- 许可证要和项目兼容
- 商业项目注意：GPL 系列传染性强，谨慎使用
- Apache 2.0、MIT、BSD 相对友好

> 💡 一句话：一个依赖 = 一份风险。引入容易，移除难。

### 11.2 依赖版本管理 ✅ MUST

#### 用 parent 或 dependencyManagement 统一管理

Spring Boot 项目已通过 parent 管理了大部分依赖版本，不要自己指定版本号：

```xml
<!-- 好的 ✅ 继承 Spring Boot parent，版本由 parent 管理 -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.x.x</version>
</parent>

<dependencies>
    <!-- 不用写 version，继承自 parent -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

#### 自定义依赖版本在 properties 中统一管理

```xml
<properties>
    <mybatis-plus.version>3.5.5</mybatis-plus.version>
    <hutool.version>5.8.25</hutool.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-boot-starter</artifactId>
        <version>${mybatis-plus.version}</version>
    </dependency>
</dependencies>
```

### 11.3 依赖分类 💡 SHOULD

#### Starter 优先

Spring Boot 项目优先用 starter，不用手动拼多个依赖：

```xml
<!-- 好的 ✅ 一个 starter 搞定 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- 不好的 ❌ 手动拼 -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webmvc</artifactId>
</dependency>
...
```

#### 常用 starter 清单

| Starter | 用途 |
|---------|------|
| `spring-boot-starter-web` | Web 应用（含 Tomcat、Spring MVC） |
| `spring-boot-starter-data-jpa` | JPA 数据访问 |
| `spring-boot-starter-cache` | 缓存支持 |
| `spring-boot-starter-validation` | 参数校验 |
| `spring-boot-starter-aop` | AOP 支持 |
| `spring-boot-starter-test` | 测试（JUnit、Mockito 等） |
| `spring-boot-starter-actuator` | 应用监控 |

### 11.4 依赖范围 💡 SHOULD

合理使用 `<scope>` 控制依赖的使用范围：

| scope | 说明 | 示例 |
|-------|------|------|
| `compile` | 默认，编译、测试、运行都需要 | Spring Web |
| `provided` | 编译、测试需要，运行时由容器提供 | Lombok、Servlet API |
| `runtime` | 运行时需要，编译不需要 | 数据库驱动 |
| `test` | 只在测试时需要 | JUnit、Mockito |
| `system` | 本地 jar，不推荐 | — |

```xml
<!-- Lombok 只在编译时需要 -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>

<!-- 数据库驱动运行时才需要 -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- 测试依赖 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

### 11.5 可选依赖与排除 💡 SHOULD

#### 可选依赖 optional

某个依赖只有部分功能需要，不希望传递给下游：

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <optional>true</optional>
</dependency>
```

#### 排除传递依赖 exclude

不需要的传递依赖要排除，避免依赖冲突或冗余：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <exclusions>
        <!-- 排除不需要的传递依赖 -->
        <exclusion>
            <groupId>org.mybatis</groupId>
            <artifactId>mybatis-spring</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### 11.6 依赖冲突处理 💡 SHOULD

#### 常见冲突原因
- 不同依赖引入了同一个库的不同版本
- 传递依赖版本和直接依赖版本不一致

#### 排查工具
```bash
# 查看完整依赖树
mvn dependency:tree

# 查看某个依赖的来源
mvn dependency:tree -Dincludes=com.google.guava:guava

# 分析未使用/重复依赖
mvn dependency:analyze
```

#### 解决原则
1. **就近原则**：直接声明的版本优先于传递依赖
2. **路径最短原则**：路径越短优先级越高
3. **声明顺序原则**：同级路径下，先声明的优先
4. **统一版本**：在 dependencyManagement 中锁定版本

```xml
<!-- 在 dependencyManagement 中统一锁定版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>32.0.0-jre</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 11.7 项目现有依赖清单与使用说明

| 依赖 | 用途 | 引入原则 |
|-----|------|---------|
| `spring-boot-starter-web` | Web 框架 | 核心依赖，必需 |
| `mybatis-plus-boot-starter` | ORM 框架 | 数据访问必需 |
| `sqlite-jdbc` | SQLite 驱动 | 运行时依赖 |
| `lombok` | 简化代码 | 编译时依赖，必需 |
| `caffeine` | 本地缓存 | 性能优化必需 |
| `spring-boot-starter-validation` | 参数校验 | 建议引入 |

### 11.8 不要重复造轮子 ✅ MUST

写代码前先想想：这个功能 JDK / Spring / 已有的依赖里有没有？

**JDK 里有的：**
- 集合操作：`Collections`、`Stream API`
- 字符串：`String`、`StringBuilder`、`StringJoiner`
- 对象工具：`Objects`
- 日期时间：`java.time.*`
- 并发工具：`java.util.concurrent.*`
- 正则：`java.util.regex.*`

**Spring 里有的：**
- `StringUtils` — 字符串工具
- `CollectionUtils` — 集合工具
- `BeanUtils` — Bean 拷贝
- `Assert` — 参数校验
- `ReflectionUtils` — 反射工具
- `FileCopyUtils` / `StreamUtils` — IO 工具

> 💡 能不用新依赖就不用，但该用的时候也要大胆用。核心是判断成本收益比。
