---
alwaysApply: false
description: "当用户涉及数据库设计、表结构设计、SQL 编写、索引优化、MyBatis-Plus 使用等场景时触发。适用于设计数据库表、编写 SQL 查询、优化数据库性能、使用 MyBatis-Plus 进行数据操作等任务。仅适用于 stock-watcher Java 后端项目。关键词：数据库, SQL, MySQL, MyBatis-Plus, 表设计, 索引, ORM, 数据持久化"
# 数据库设计规范

> 适用于 stock-watcher（MySQL + MyBatis-Plus）数据库开发。

---

## 一、表设计规范

### 1.1 表命名 ✅ MUST

- 使用 **小写 + 下划线（snake_case）**
- 表名使用名词，见名知意
- 前缀按模块划分（可选）

```
sys_user              系统用户
stock_basic           股票基础信息
daily_quote           日线行情
trade_cal             交易日历
adj_factor            复权因子
dividend              分红送股
watchlist_item        自选股
```

### 1.2 字段命名 ✅ MUST

- 小写 + 下划线（snake_case）
- 布尔字段用 `is_` 前缀
- 时间字段用 `_time` / `_date` 后缀
- 避免使用保留字

```java
ts_code               股票代码
trade_date            交易日期
open / high / low     OHLC 价格
close                 收盘价
vol                   成交量
is_open               是否开市
created_at            创建时间
updated_at            更新时间
```

### 1.3 字段类型 ✅ MUST

> ⚠️ **项目使用 MySQL 数据库**，表结构维护在 `schema-mysql.sql`。
> 新增/修改字段时需同步更新 `schema-mysql.sql`。

#### 1.3.1 通用类型对照表

| Java 类型 | MySQL 类型 | 说明 |
|----------|-----------|------|
| `Long` / `Integer` | `BIGINT` / `INT` | 主键用 `BIGINT AUTO_INCREMENT` |
| `String` | `VARCHAR(N)` | 可限定长度，不强制长度校验 |
| `BigDecimal` | `DECIMAL(M,N)` | 金额类推荐 DECIMAL 避免浮点误差 |
| `Boolean` | **`TINYINT`** | **布尔字段务必用 TINYINT + Boolean 实体类，不要用 VARCHAR** |
| `LocalDate` / `String`(date) | `DATE` 或 `VARCHAR(8)` | 项目惯例用字符串 `yyyyMMdd` 存日期（varchar） |
| `LocalDateTime` / `String`(datetime) | `DATETIME` 或 `VARCHAR(19)` | 项目惯例用字符串存时间 |
| 枚举 Enum | `VARCHAR` | 存枚举的 name/字符串值 |

#### 1.3.2 Boolean 字段规范（重要！）

**绝对不要用 `VARCHAR(4)` 存 `"1"`/`"0"` 表示布尔值。** 正确做法：

- **数据库**：MySQL 用 `TINYINT DEFAULT 0`
- **Java 实体**：用 `private Boolean` 类型（不是 `String`）
- **MyBatis-Plus**：自动映射 `Boolean` ↔ `TINYINT`，无需额外配置
- **JSON 序列化**：Jackson 默认输出 `true`/`false`，下游消费方需能识别

```sql
-- MySQL
ALTER TABLE trade_cal ADD COLUMN is_first_of_month TINYINT DEFAULT 0 COMMENT '是否本月首交易日：1=是，0=否';
```

```java
// 实体类
private Boolean isFirstOfMonth;  // ✅ 正确
// private String isFirstOfMonth;  // ❌ 错误，不要用 String
```

> **历史教训**：项目早期曾错误地用 `VARCHAR(4)` + `String` 存布尔字段
> （如 trade_cal 的 is_first_of_* 系列），导致 Java 代码类型不优雅、
> 存浪费空间、比较易错。现已全部改造为 `TINYINT` + `Boolean`。
> 新增布尔字段请直接走正确路线，不要再走弯路。

### 1.4 必备字段 💡 SHOULD

每张表建议包含：

| 字段 | 类型 | 说明 |
|-----|------|------|
| `id` | BIGINT PK AUTO_INCREMENT | 主键 |
| `created_at` | VARCHAR(19) | 创建时间 |
| `updated_at` | VARCHAR(19) | 更新时间 |

### 1.5 主键策略 ✅ MUST

- 使用自增主键 `id`
- 业务唯一键单独建唯一索引
- 不使用业务字段作为主键

---

## 二、索引设计规范

### 2.1 索引设计原则 ✅ MUST

1. **高频查询字段建索引**：where、join、order by 字段
2. **联合索引最左前缀**：按区分度从高到低排列
3. **避免过多索引**：索引越多写入越慢
4. **唯一索引**：业务唯一键建唯一索引
5. **索引不是越多越好**：权衡读写比

### 2.2 索引类型

| 索引类型 | 用途 | 示例 |
|---------|------|------|
| 主键索引 | 主键自动创建 | `id` |
| 唯一索引 | 保证字段唯一 | `idx_unique_ts_code` |
| 普通索引 | 加速查询 | `idx_trade_date` |
| 联合索引 | 多字段查询 | `idx_ts_code_trade_date` |

### 2.3 索引命名 💡 SHOULD

```
idx_<表名缩写>_<字段1>_<字段2>

示例：
idx_dq_ts_code_trade_date        daily_quote 表的 ts_code + trade_date 联合索引
idx_sb_symbol                    stock_basic 表的 symbol 索引
```

### 2.4 项目常用索引示例

```sql
-- daily_quote 表
CREATE INDEX idx_dq_ts_code_trade_date ON daily_quote (ts_code, trade_date);
CREATE INDEX idx_dq_trade_date ON daily_quote (trade_date);

-- stock_basic 表
CREATE UNIQUE INDEX idx_sb_ts_code ON stock_basic (ts_code);
CREATE INDEX idx_sb_symbol ON stock_basic (symbol);

-- trade_cal 表
CREATE UNIQUE INDEX idx_tc_exchange_cal_date ON trade_cal (exchange, cal_date);
```

---

## 三、SQL 编写规范

### 3.1 查询规范 ✅ MUST

- 明确指定字段，不使用 `SELECT *`
- 分页查询使用 `LIMIT`
- 避免全表扫描，确保查询走索引
- 大表查询必须有 where 条件

```sql
-- 好的
SELECT ts_code, trade_date, open, high, low, close, vol
FROM daily_quote
WHERE ts_code = ? AND trade_date >= ?
ORDER BY trade_date
LIMIT ? OFFSET ?;

-- 不好的 ❌
SELECT * FROM daily_quote; -- 全表 + 所有字段
```

### 3.2 插入/更新/删除规范 ✅ MUST

- 批量操作使用批量插入/更新
- **禁止循环内单条 SQL 操作**：当循环体内包含 INSERT/UPDATE/DELETE 且循环次数 >3 时，
  必须改造为批量 SQL（`insertBatch` / `deleteBatchByKeys` / CASE WHEN 批量 UPDATE）
- 单条操作仅允许在循环次数 ≤3 或确认数据量极小（<10 条）的场景使用
- MyBatis-Plus 使用自定义批量 SQL（`<foreach>` 构造多值 INSERT）
- 批量大小统一使用 `BATCH_SIZE = 500`，用 `Lists.partition(list, BATCH_SIZE)` 分批

```java
// 禁止 ❌ 循环逐条 INSERT
for (Item item : items) {
    mapper.insert(item);
}

// 正确 ✅ 批量 INSERT（先删后插，幂等 upsert）
Lists.partition(entities, BATCH_SIZE).forEach(batch -> {
    mapper.deleteBatchByKeys(batch);
    mapper.insertBatch(batch);
});
```

### 3.3 避免 N+1 查询 ✅ MUST

- 不要在循环中查询数据库
- 批量查询后在内存中组装
- 使用 join 查询或 in 查询

```java
// 不好的 ❌ N+1 问题
for (String code : codeList) {
    StockBasicDO stock = stockBasicMapper.selectByTsCode(code); // 每次都查
    // ...
}

// 好的
List<StockBasicDO> stocks = stockBasicMapper.selectBatchByTsCodes(codeList); // 一次查出
Map<String, StockBasicDO> stockMap = stocks.stream()
    .collect(Collectors.toMap(StockBasicDO::getTsCode, Function.identity()));
```

---

## 四、MyBatis-Plus 使用规范

### 4.1 实体类 ✅ MUST

- 实体类放在 `model/` 目录，后缀 `*DO`
- 使用 `@TableName` 指定表名
- 使用 `@TableId` 指定主键
- 使用 `@TableField` 指定字段映射（字段名一致可省略）

```java
@Data
@TableName("daily_quote")
public class DailyQuoteDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;
    private String tradeDate;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long vol;
    private String createdAt;
    private String updatedAt;
}
```

### 4.2 Mapper 接口 ✅ MUST

- Mapper 接口放在 `mapper/` 目录
- 继承 `BaseMapper<T>`
- 自定义 XML 放在 `resources/mapper/` 目录

```java
public interface DailyQuoteMapper extends BaseMapper<DailyQuoteDO> {

    List<DailyQuoteDO> selectByTsCodeAndDateRange(
            @Param("tsCode") String tsCode,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);
}
```

### 4.3 Service 层 ✅ MUST

- Service 继承 `IService<T>`
- ServiceImpl 继承 `ServiceImpl<Mapper, T>`
- 可直接使用 `save`、`removeById`、`getById`、`list`、`page` 等方法

```java
public interface DailyQuoteService extends IService<DailyQuoteDO> {
    // 自定义方法
}

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyQuoteServiceImpl
        extends ServiceImpl<DailyQuoteMapper, DailyQuoteDO>
        implements DailyQuoteService {
    // 自定义实现
}
```

### 4.4 QueryWrapper 使用 💡 SHOULD

- 简单查询使用 QueryWrapper / LambdaQueryWrapper
- 复杂查询用 XML 写 SQL
- 注意：动态条件查询时，防止所有条件都为空导致全表扫描

```java
// LambdaQueryWrapper（推荐，编译期检查）
LambdaQueryWrapper<DailyQuoteDO> wrapper = Wrappers.<DailyQuoteDO>lambdaQuery()
        .eq(DailyQuoteDO::getTsCode, tsCode)
        .ge(DailyQuoteDO::getTradeDate, startDate)
        .le(DailyQuoteDO::getTradeDate, endDate)
        .orderByAsc(DailyQuoteDO::getTradeDate);

List<DailyQuoteDO> list = list(wrapper);
```

### 4.5 批量操作 ✅ MUST

- 批量插入使用 `saveBatch`
- 批量更新使用 `updateBatchById`
- 批量 upsert 使用先删后插（`deleteBatchByKeys` + `insertBatch`）保证幂等

```java
// 批量插入
saveBatch(quoteList, 1000); // 每 1000 条一批

// 幂等批量 upsert（先删后插）
Lists.partition(entities, BATCH_SIZE).forEach(batch -> {
    mapper.deleteBatchByKeys(batch);
    mapper.insertBatch(batch);
});
```

---

## 五、事务规范

### 5.1 事务使用 💡 SHOULD

- 写操作使用事务
- 读多写少的场景谨慎使用
- 事务范围尽量小
- 使用 `@Transactional(rollbackFor = Exception.class)`

### 5.2 事务注意事项 ❗

- 同类中方法调用不生效（AOP 代理问题）
- 不要在事务中做耗时操作（如调用外部 API）
- 避免大事务

---

## 六、Schema 管理

### 6.1 Schema 管理 💡 SHOULD

- 建表 DDL 放在 `resources/schema-mysql.sql`
- 新增表/字段需同步更新 `schema-mysql.sql`

### 6.2 数据迁移 📌 MAY

- 小项目可手动管理
- 复杂项目考虑 Flyway / Liquibase
- 版本号管理迁移脚本
