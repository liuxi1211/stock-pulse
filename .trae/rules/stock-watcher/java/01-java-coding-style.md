---
alwaysApply: false
description: "当用户涉及 Java 代码编写、代码风格、命名规范、代码格式、Java 编码规范等场景时触发。适用于编写 Java 代码、重构 Java 代码、检查 Java 代码风格、遵循项目 Java 编码规范等任务。仅适用于 stock-watcher Java 后端项目。关键词：Java, 代码风格, 命名规范, 编码规范, 代码格式, Java代码"
# Java 代码风格规范

> 适用于 stock-watcher（Java + Spring Boot）后端开发。
> 基于项目现有代码风格总结，与当前代码保持一致。

---

## 一、命名规范

### 1.1 包命名 ✅ MUST

- 全部小写，使用点分隔
- 基础包：`com.arthur.stock`
- 模块包：按功能划分，如 `controller`、`service`、`mapper`、`model`

```
com.arthur.stock
├── controller
├── service
│   └── impl
├── mapper
├── model
├── dto
├── vo
├── config
├── util
├── constant
└── exception
```

### 1.2 类/接口命名 ✅ MUST

- **大驼峰（PascalCase）**
- 类名使用名词或名词短语
- 接口名不加 `I` 前缀

| 类型 | 命名规则 | 示例 |
|-----|---------|------|
| 实体类 | `*DO`（Data Object） | `StockBasicDO`、`DailyQuoteDO` |
| DTO | `*DTO` / `*Request` / `*Response` | `KlineQueryDTO`、`ApiResponse` |
| VO | `*VO`（View Object） | `KlineDataVO`、`StockVO` |
| Service 接口 | `*Service` | `KlineService`、`AuthService` |
| Service 实现 | `*ServiceImpl` | `KlineServiceImpl` |
| Controller | `*Controller` | `KlineController`、`AuthController` |
| Mapper | `*Mapper` | `StockBasicMapper` |
| 配置类 | `*Config` | `CacheConfig`、`MyBatisPlusConfig` |
| 异常类 | `*Exception` | `BusinessException` |
| 工具类 | `*Util` / `*Helper` | `KlineCalculator`、`StockDataHelper` |
| 枚举 | 名词，不加 `Enum` 后缀 | `BoardEnum`、`ExchangeEnum` |

### 1.3 方法命名 ✅ MUST

- **小驼峰（camelCase）**
- 动词或动词短语开头
- 布尔值方法用 `is` / `has` / `can` 开头

| 前缀 | 用途 | 示例 |
|-----|------|------|
| `get` | 获取单个值 | `getStockCode()`、`getClosePrice()` |
| `set` | 设置值 | `setStockCode()` |
| `is` / `has` | 布尔判断 | `isOpen()`、`hasData()` |
| `list` / `query` | 查询列表 | `listByDate()`、`queryWatchlist()` |
| `add` / `save` / `insert` | 新增 | `addItem()`、`saveBatch()` |
| `update` | 更新 | `updateById()` |
| `delete` / `remove` | 删除 | `deleteById()`、`removeItem()` |
| `calc` / `calculate` | 计算 | `calcMa()`、`calculateFrontAdjPrice()` |
| `init` | 初始化 | `initCache()` |

### 1.4 变量命名 ✅ MUST

- **小驼峰（camelCase）**
- 有意义，不使用拼音或无意义缩写
- 避免单字母变量（循环变量 `i`、`j` 除外）

```java
// 好的
String stockCode;
BigDecimal closePrice;
List<DailyQuoteDO> quoteList;

// 不好的 ❌
String sc;
BigDecimal cp;
List<DailyQuoteDO> list;
```
---

## 五、异常处理规范

### 5.1 异常类型 ✅ MUST

| 异常类型 | 使用场景 | 示例 |
|---------|---------|------|
| `BusinessException` | 业务异常，用户可理解 | `throw new BusinessException(ErrorCode.STOCK_NOT_FOUND)` |
| `IllegalArgumentException` | 参数非法 | `throw new IllegalArgumentException("stockCode不能为空")` |
| `RuntimeException` | 运行时异常，系统错误 | 特殊情况使用 |

### 5.2 异常抛出原则 ✅ MUST

- 尽早抛出异常（Fail Fast）
- 异常信息清晰，说明原因和位置
- 业务异常使用 `BusinessException` + 错误码

```java
public List<KlineDataVO> getKlineData(String stockCode, String period) {
    if (StringUtils.isBlank(stockCode)) {
        throw new IllegalArgumentException("stockCode不能为空");
    }
    if (!PERIODS.contains(period)) {
        throw new BusinessException(ErrorCode.INVALID_PARAM, "无效的周期类型");
    }
    // ...
}
```

### 5.3 异常捕获原则 ✅ MUST

- 不要捕获后不处理（空 catch）
- 不要吞掉异常堆栈
- 捕获后能处理就处理，不能处理就向上抛
- 全局异常由 `GlobalExceptionHandler` 统一处理

```java
// 不好的 ❌
try {
    doSomething();
} catch (Exception e) {
    // 空的 catch
}

// 好的
try {
    doSomething();
} catch (Exception e) {
    log.error("操作失败: {}", e.getMessage(), e);
    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
}
```

### 5.4 日志与异常 ✅ MUST

- 异常必须打日志
- 日志包含异常堆栈（最后一个参数传异常对象）
- 日志中不输出敏感信息

```java
log.error("获取K线数据失败, stockCode: {}", stockCode, e);
```

---

## 六、日志规范

### 6.1 日志框架 ✅ MUST

- 使用 SLF4J + Logback
- 使用 Lombok 的 `@Slf4j` 注解

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class KlineServiceImpl implements KlineService {
    // ...
}
```

### 6.2 日志级别 💡 SHOULD

| 级别 | 使用场景 |
|-----|---------|
| `error` | 错误异常，需要关注和处理 |
| `warn` | 警告，不影响运行但需要注意 |
| `info` | 重要业务信息，正常运行记录 |
| `debug` | 调试信息，开发调试用，生产关闭 |

### 6.3 日志内容 ✅ MUST

- 使用占位符 `{}`，不用字符串拼接
- 关键操作必须打日志（数据更新、任务执行等）
- 异常日志必须包含堆栈
- 日志中不得输出密码、token 等敏感信息

```java
// 好的
log.info("开始更新日线数据, date: {}", tradeDate);
log.error("更新失败, stockCode: {}", stockCode, e);

// 不好的 ❌
log.info("开始更新日线数据, date: " + tradeDate);
log.error("更新失败: " + e.getMessage()); // 没有堆栈
```

---

## 七、其他规范

### 7.1 使用 Lombok ✅ MUST

- 减少样板代码
- 常用注解：`@Data`、`@Slf4j`、`@RequiredArgsConstructor`、`@Builder`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KlineDataVO {
    private String date;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
}
```

### 7.2 构造器注入 ✅ MUST

- 使用构造器注入，不使用字段注入
- 配合 Lombok 的 `@RequiredArgsConstructor`

```java
@Service
@RequiredArgsConstructor
public class KlineServiceImpl implements KlineService {

    private final KlineMapper klineMapper;
    private final StockCodeCache stockCodeCache;
}
```

### 7.3 避免魔法值 ✅ MUST

- 魔法值定义为常量
- 状态、类型等使用枚举

```java
// 好的
private static final int MAX_RETRY = 3;
if (retryCount < MAX_RETRY) { ... }

// 不好的 ❌
if (retryCount < 3) { ... } // 3 是什么？
```

### 7.4 空值处理 💡 SHOULD

- 方法返回集合时，返回空集合而不是 null
- 使用 `Optional` 处理可能为 null 的值
- 字符串判断用 `StringUtils.isBlank()` / `StringUtils.isNotBlank()`

```java
// 好的
public List<StockVO> list() {
    List<StockDO> list = mapper.selectList(null);
    if (CollectionUtils.isEmpty(list)) {
        return Collections.emptyList();
    }
    return convert(list);
}
```
