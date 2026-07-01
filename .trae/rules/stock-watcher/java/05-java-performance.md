---
alwaysApply: false
description: "当用户涉及 Java 性能优化、JVM 调优、SQL 优化、集合性能、字符串优化、缓存策略、并发性能等 Java 后端性能场景时触发。适用于优化 Java 代码性能、调优 JVM 参数、优化数据库查询、提升接口响应速度等任务。仅适用于 stock-watcher Java 后端项目。关键词：Java性能, 性能优化, JVM, SQL优化, 缓存, 并发, 调优, 响应速度"
# Java 性能优化规范

> 适用于 stock-watcher（Java + Spring Boot + MyBatis-Plus）性能优化。

---

## 一、对象创建优化

### 1.1 避免不必要的对象创建 💡 SHOULD

- 字符串拼接使用 `StringBuilder`，不用 `+`
- 基本类型优先于包装类型，避免自动装箱拆箱
- 复用对象（如 SimpleDateFormat 用 ThreadLocal）
- 避免在循环中创建对象

```java
// 不好的 ❌
String result = "";
for (String s : list) {
    result += s; // 每次都创建新 String 对象
}

// 好的
StringBuilder sb = new StringBuilder();
for (String s : list) {
    sb.append(s);
}
String result = sb.toString();
```

### 1.2 集合初始化容量 💡 SHOULD

- 创建 ArrayList、HashMap 等集合时指定初始容量
- 避免频繁扩容

```java
// 不好的
List<String> list = new ArrayList<>(); // 默认容量 10

// 好的
List<String> list = new ArrayList<>(size); // 预估大小
```

### 1.3 使用 try-with-resources ✅ MUST

- IO 流、连接等资源使用 try-with-resources
- 确保资源正确关闭，避免泄漏

```java
try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
    // ...
} catch (IOException e) {
    // ...
}
```

---

## 二、集合使用优化

### 2.1 选择合适的集合 ✅ MUST

| 场景 | 推荐 | 不推荐 |
|-----|------|--------|
| 随机访问多 | `ArrayList` | `LinkedList` |
| 频繁插入删除中间 | `LinkedList` | `ArrayList` |
| 键值对查找 | `HashMap` | `ArrayList` |
| 有序键值对 | `TreeMap` / `LinkedHashMap` | `HashMap` |
| 去重 | `HashSet` | `ArrayList` |

### 2.2 遍历方式 💡 SHOULD

- 简单遍历：增强 for 循环
- 需要索引：普通 for 循环
- 删除元素：Iterator 或 removeIf
- 大数据量：并行流需谨慎

```java
// 删除元素使用迭代器，避免 ConcurrentModificationException
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    String item = it.next();
    if (shouldRemove(item)) {
        it.remove();
    }
}

// 或使用 removeIf
list.removeIf(this::shouldRemove);
```

### 2.3 Map 优化 💡 SHOULD

- 预估算容量（`initialCapacity = 预期大小 / 0.75 + 1`）
- 使用 `containsKey` 判断键是否存在
- 遍历 entrySet 比 keySet 再 get 高效

```java
// 不好的
for (String key : map.keySet()) {
    String value = map.get(key); // 多一次查找
    // ...
}

// 好的
for (Map.Entry<String, String> entry : map.entrySet()) {
    String key = entry.getKey();
    String value = entry.getValue();
    // ...
}
```

---

## 三、字符串处理

### 3.1 字符串拼接 ✅ MUST

| 场景 | 推荐方式 |
|-----|---------|
| 编译期可确定 | 直接 `+`（编译器优化） |
| 单线程循环内 | `StringBuilder` |
| 多线程 | `StringBuffer`（很少用） |

### 3.2 字符串比较 ✅ MUST

- 比较内容用 `equals()`，不用 `==`
- 常量在前，避免 NPE：`"value".equals(str)`
- 忽略大小写用 `equalsIgnoreCase()`

```java
// 好的
if ("daily".equals(period)) { ... }

// 不好的 ❌
if (period == "daily") { ... } // 引用比较
if (period.equals("daily")) { ... } // period 为 null 时 NPE
```

### 3.3 字符串性能 💡 SHOULD

- 频繁子串操作考虑用 `StringBuilder` 或 `char[]`
- 正则表达式预编译 Pattern
- 大量字符串处理考虑 intern（谨慎使用）

```java
// 预编译正则（放在常量或静态块中）
private static final Pattern PATTERN = Pattern.compile("^\\d{6}\\.(SZ|SH)$");

// 使用
Matcher matcher = PATTERN.matcher(tsCode);
```

---

## 四、IO 与 NIO

### 4.1 流操作 💡 SHOULD

- 使用缓冲流（BufferedReader / BufferedWriter）
- 批量读写，减少 IO 次数
- 大文件使用 NIO 或流式处理

### 4.2 文件操作 💡 SHOULD

- 小文件直接读取
- 大文件使用流式读取，不要全部加载到内存
- 注意编码，显式指定 UTF-8

---

## 五、并发编程

### 5.1 线程安全 ✅ MUST

- 多线程环境下确保线程安全
- 共享变量使用 `volatile` 或原子类
- 集合使用并发集合（`ConcurrentHashMap`、`CopyOnWriteArrayList`）
- 避免死锁（注意加锁顺序）

### 5.2 线程池 💡 SHOULD

- 使用线程池，不手动 new Thread
- 合理配置核心线程数和最大线程数
- 给线程池和线程命名，便于排查

```java
@Bean
public ExecutorService taskExecutor() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("data-init-%d")
            .build();
    return new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
}
```

### 5.3 异步任务 💡 SHOULD

- 使用 `@Async` 或 `CompletableFuture`
- 注意事务在异步中不生效
- 异常处理要完善

---

## 六、缓存策略

### 6.1 缓存技术栈 ✅ MUST

- Spring Cache + Caffeine（本地缓存）
- `@Cacheable` / `@CacheEvict` / `@CachePut`

### 6.2 缓存适用场景 💡 SHOULD

**适合缓存：**
- K 线计算结果（计算成本高，读多写少）
- 股票基础信息（变化少）
- 交易日历（基本不变）
- 配置数据

**不适合缓存：**
- 实时行情（变化快）
- 用户特定数据（量大）
- 敏感数据（视情况）

### 6.3 缓存设计 💡 SHOULD

- 合理的过期时间（TTL）
- 合理的最大容量（防止 OOM）
- 缓存穿透：布隆过滤器或缓存空值
- 缓存击穿：互斥锁或永不过期
- 缓存雪崩：过期时间加随机偏移

### 6.4 项目缓存示例

```java
@Cacheable(value = "kline", key = "#stockCode + '_' + #period")
public List<KlineDataVO> getKlineData(String stockCode, String period) {
    // 计算 K 线...
}
```

---

## 七、外部接口调用优化

### 7.1 避免循环中调用接口 ✅ MUST

**反模式：循环内单次调用**

```java
// 不好的 ❌ 循环中逐个调用接口，N 次网络往返
for (String code : stockCodes) {
    StockInfo info = tushareClient.getStockInfo(code); // 每次都发 HTTP 请求
    result.add(info);
}
```

**危害：**
- N 次网络往返，延迟 = N × 单次延迟
- 容易触发对方限流
- 连接池容易耗尽

**解决方案：批量接口 + 内存组装**

```java
// 好的 ✅ 一次批量查询，一次网络往返
List<StockInfo> allInfo = tushareClient.getStockInfoBatch(stockCodes);
Map<String, StockInfo> infoMap = allInfo.stream()
    .collect(Collectors.toMap(StockInfo::getTsCode, Function.identity()));

for (String code : stockCodes) {
    StockInfo info = infoMap.get(code); // 内存查找，无 IO
    result.add(info);
}
```

### 7.2 没有批量接口时的优化策略 💡 SHOULD

如果对方没有提供批量接口，考虑以下方案：

#### 方案 1：并发调用
```java
// 使用线程池并发调用，总耗时 = 最长单次耗时（不是 N 倍）
List<CompletableFuture<StockInfo>> futures = stockCodes.stream()
    .map(code -> CompletableFuture.supplyAsync(
        () -> tushareClient.getStockInfo(code),
        executorService))
    .collect(Collectors.toList());

List<StockInfo> result = futures.stream()
    .map(CompletableFuture::join)
    .collect(Collectors.toList());
```

> ⚠️ 注意：并发调用仍受对方限流约束，不要无脑加并发度

#### 方案 2：分页/分批查询
```java
// 分批查询，每批 50 个，平衡并发数和请求数
int batchSize = 50;
for (int i = 0; i < stockCodes.size(); i += batchSize) {
    List<String> batch = stockCodes.subList(i, Math.min(i + batchSize, stockCodes.size()));
    List<StockInfo> batchResult = tushareClient.getStockInfoBatch(batch);
    result.addAll(batchResult);
}
```

#### 方案 3：本地缓存
```java
// 先查缓存，缓存中没有的再批量查询
List<String> uncachedCodes = stockCodes.stream()
    .filter(code -> !cache.containsKey(code))
    .collect(Collectors.toList());

if (!uncachedCodes.isEmpty()) {
    List<StockInfo> newData = tushareClient.getStockInfoBatch(uncachedCodes);
    // 写入缓存
    newData.forEach(info -> cache.put(info.getTsCode(), info));
}

// 从缓存取
List<StockInfo> result = stockCodes.stream()
    .map(cache::get)
    .filter(Objects::nonNull)
    .collect(Collectors.toList());
```

### 7.3 接口调用重试机制 💡 SHOULD

#### 重试适用场景
- 网络抖动导致的超时
- 对方服务临时不可用
- 限流导致的失败（需退避）

#### 重试注意事项
- **幂等性**：只有幂等接口才能重试（GET 查询可以，POST 创建要谨慎）
- **重试次数**：一般 2-3 次，不要无限重试
- **退避策略**：指数退避（等 1s → 2s → 4s），避免请求风暴
- **超时设置**：每次重试都要有超时

```java
// 推荐使用 Spring Retry 或 Resilience4j
@Retryable(
    retryFor = {IOException.class, TimeoutException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
public StockInfo getStockInfoWithRetry(String code) {
    return tushareClient.getStockInfo(code);
}
```

### 7.4 限流与熔断 💡 SHOULD

#### 限流
- 调用第三方接口要遵守对方的限流规则
- 自己也做一层限流保护，防止异常流量打挂对方

```java
// 使用 Guava RateLimiter
RateLimiter rateLimiter = RateLimiter.create(100); // 每秒 100 次

public StockInfo getStockInfo(String code) {
    rateLimiter.acquire(); // 获取令牌，拿不到就等
    return tushareClient.getStockInfo(code);
}
```

#### 熔断
- 连续失败达到阈值后，暂时不调用（快速失败）
- 避免无效请求拖慢自己、打挂对方
- 过一段时间后尝试恢复（半开状态）

> 推荐：Resilience4j CircuitBreaker 或 Sentinel

### 7.5 客户端配置优化 💡 SHOULD

#### 连接池
- 使用 HTTP 客户端连接池，不要每次新建连接
- 合理配置最大连接数、超时时间

```java
// OkHttp / RestTemplate / WebClient 都要配置连接池
@Bean
public OkHttpClient okHttpClient() {
    return new OkHttpClient.Builder()
        .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build();
}
```

#### 超时设置（必须！）
- **连接超时**：建立 TCP 连接的时间
- **读取超时**：等待响应数据的时间
- **写入超时**：发送请求数据的时间
- 永远不要不设超时（可能永远卡着）

### 7.6 Tushare 调用特定优化 🔴 重点

项目中 Tushare 是主要的外部依赖，重点优化：

| 优化点 | 具体做法 |
|-------|---------|
| **批量获取** | 能用批量接口就不用单条接口 |
| **增量更新** | 每次只更新新增的，不全量拉取 |
| **本地缓存** | 基础信息、交易日历等基本不变的缓存起来 |
| **错峰执行** | 定时任务避开高峰期（如收盘后 16:00 以后） |
| **限流控制** | 严格遵守每分钟调用次数限制 |
| **失败重试** | 网络问题自动重试，指数退避 |
| **异步化** | 大批量数据获取用异步，不阻塞主线程 |

---

## 八、数据库查询优化

### 8.1 避免 N+1 查询 ✅ MUST

- 不要在循环中查询数据库
- 批量查询后在内存中组装
- 使用 join 查询

```java
// 不好的 ❌ N+1
for (String code : codeList) {
    StockBasicDO stock = stockBasicMapper.selectByTsCode(code);
}

// 好的
List<StockBasicDO> stocks = stockBasicMapper.selectBatchTsCodes(codeList);
Map<String, StockBasicDO> map = stocks.stream()
    .collect(Collectors.toMap(StockBasicDO::getTsCode, Function.identity()));
```

### 8.2 批量操作 ✅ MUST

- 插入/更新使用批量操作
- MyBatis-Plus 的 `saveBatch`、`updateBatchById`
- 每批大小 500-1000 条，防止内存占用过大

```java
// 批量插入
saveBatch(quoteList, 1000);

// 自定义批量 upsert
void insertOrReplaceBatch(@Param("list") List<DailyQuoteDO> list);
```

### 8.3 索引优化 ✅ MUST

- 查询条件字段建索引
- 联合索引注意最左前缀原则
- 用 `EXPLAIN` 分析查询计划
- 避免索引失效的写法

### 8.4 查询优化 💡 SHOULD

- 只查需要的字段，不用 `SELECT *`
- 分页查询大数据集
- 避免在 where 条件中对字段做运算/函数
- 用 `in` 代替多个 `or`

### 8.5 MyBatis-Plus 优化 💡 SHOULD

- 简单查询用 LambdaQueryWrapper
- 复杂查询用 XML
- 注意动态查询时防止所有条件为空导致全表扫描

---

## 九、性能监控与分析

### 9.1 监控手段 💡 SHOULD

- 关键方法打印耗时日志
- Spring Boot Actuator 监控
- 慢 SQL 日志
- 定时任务执行时间监控

```java
long start = System.currentTimeMillis();
// 执行操作
long cost = System.currentTimeMillis() - start;
log.info("更新日线数据完成, 数量: {}, 耗时: {}ms", list.size(), cost);
```

### 9.2 Profiling 工具 📌 MAY

- **JProfiler / YourKit**：商业，功能强大
- **VisualVM**：免费，JDK 自带
- **Arthas**：阿里开源，线上诊断
- **JMH**：微基准测试

---

## 十、项目特定性能关注点

### 10.1 K 线计算 🔴 重点

- K 线计算结果必须缓存
- 前复权计算使用 `BigDecimal` 保证精度（精度优先于性能）
- 周/月 K 线聚合使用高效算法
- 缓存 key 包含股票代码和周期

### 10.2 Tushare 数据获取 🔴 重点

- 注意限流，不要触发频率限制
- 批量获取数据，减少接口调用次数
- 数据入库使用批量 upsert
- 增量更新，不要每次全量

### 10.3 日线数据查询 🟡 关注

- 查询必须走索引（ts_code + trade_date）
- 分页查询
- 缓存热点数据
- 避免全表扫描

### 10.4 定时任务 🟡 关注

- 任务之间错峰执行
- 长时间任务异步执行
- 每步独立 try-catch，一步失败不影响其他
- 记录执行时间和结果

---

## 十一、性能优化流程

```
1. 发现性能问题（监控/反馈）
2. 定位瓶颈（profiling/日志）
3. 分析原因（算法/SQL/IO/内存）
4. 制定优化方案
5. 实施优化（小步迭代）
6. 验证效果（性能对比）
7. 持续监控
```

**记住：先测量后优化，不要凭感觉优化！**
