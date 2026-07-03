# SQLite → MySQL/SQLite 双兼容改造计划

> 目标：将 stock-watcher（Java/Spring Boot）从单一 SQLite 改造为**兼容 MySQL 与 SQLite 两种模式，默认使用 MySQL**，并将现有 SQLite 数据迁移到 MySQL。

---

## 一、现状分析（Phase 1 探索结论）

### 1.1 数据库生态全貌
- **数据库只在 stock-watcher（Java）侧使用**；stock-engine（Python）受硬约束禁用数据库（有测试守卫 `test_no_db.py`）。
- ORM：MyBatis-Plus 3.5.15，所有自定义 SQL 用 `@Insert("<script>")` / `@Select` 注解，**无 XML Mapper**。
- 数据源：HikariCP，无自定义 DataSource Bean，靠 `spring.datasource.*` 自动配置。
- 共 7 张表：`sys_user` / `sys_watchlist` / `daily_quote` / `stock_basic` / `trade_cal` / `adj_factor` / `dividend`。

### 1.2 SQLite 专有/不兼容语法清单（必须改造的点）

| # | 位置 | SQLite 专有语法 | 兼容性问题 |
|---|---|---|---|
| 1 | [schema.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema.sql) 全部主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | MySQL 用 `BIGINT AUTO_INCREMENT PRIMARY KEY` |
| 2 | [DailyQuoteMapper.java#L20](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/DailyQuoteMapper.java#L20) | `INSERT OR IGNORE INTO` | MySQL 不识别，需改为"先查后判再插/改"应用层逻辑 |
| 3 | [StockBasicMapper.java#L18](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/StockBasicMapper.java#L18) | `INSERT OR REPLACE INTO` | 同上 |
| 4 | [TradeCalMapper.java#L17](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/TradeCalMapper.java#L17) | `INSERT OR REPLACE INTO` | 同上 |
| 5 | [AdjFactorMapper.java#L17](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/AdjFactorMapper.java#L17) | `INSERT OR IGNORE INTO` | 同上 |
| 6 | [DividendMapper.java#L17](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/DividendMapper.java#L17) | `INSERT OR IGNORE INTO` | 同上 |
| 7 | [MyBatisPlusConfig.java#L27](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java#L27) | `DbType.SQLITE` 写死 | 需根据配置动态切换为 `DbType.MYSQL` |
| 8 | [DataInitServiceImpl.java#L200-L226](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java#L200-L226) | 第二份硬编码 SQLite DDL | 需同步调整 |
| 9 | [pom.xml#L103-L107](file:///d:/lcProject/stock-pulse/stock-watcher/pom.xml#L103-L107) | 只有 `sqlite-jdbc` 依赖 | 需新增 `mysql-connector-j` |

### 1.3 关键事实
- **业务日期字段（trade_date/list_date/cal_date 等）在实体类中均为 `String` 类型，格式 `yyyyMMdd`**（如 [DailyQuoteDO.java#L25](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/model/DailyQuoteDO.java#L25)）。全项目用字符串比较/排序，逻辑无需调整。
- 价格/金额字段实体类用 `BigDecimal`，schema 用 `REAL`（SQLite）。MySQL 应为 `DECIMAL`。
- `@Select` 中其余查询（`MAX`/`COUNT(DISTINCT)`/`GROUP BY`/`LIMIT`/`IN 子查询`/`LIKE`）**MySQL 与 SQLite 语法完全一致，无需改造**。
- 数据量较小（用户确认）→ 迁移可一次性处理。

---

## 二、决策汇总（来自与用户的 Phase 2/3 对话）

| 决策项 | 选择 |
|---|---|
| 兼容策略 | **双方言通用 SQL**，不通用处改造成"先查后判"应用层逻辑 |
| UPSERT 方案 | **不用任何冲突语法**（不写 INSERT OR IGNORE/REPLACE/ON CONFLICT/ON DUPLICATE），改在 Service 层"先查再插或改" |
| **Mapper 写法** | **全部 7 个 Mapper 从注解 SQL 改为 XML Mapper**（提升可读性、统一风格；但不依赖 databaseId，坚持通用 SQL） |
| 数据迁移 | 一次性独立 Java 脚本（复用现有 Mapper） |
| **日期/时间字段存储** | **所有日期字段（trade_date/list_date/delist_date/cal_date/record_date/ex_date/pay_date/div_listdate/imp_ann_date/base_date/end_date/ann_date/pretrade_date 以及 created_at/updated_at 等）一律保持 String，MySQL 用 `VARCHAR`**。最大化减少改动：实体类（DO）全部字段类型不变、Service 业务逻辑不变、MetaObjectHandler 自动填充不变 |
| 自增主键 | 用**两份 schema 文件**（schema-mysql.sql / schema-sqlite.sql），用 profile 指定 |
| 配置隔离 | **默认 MySQL + SQLite profile**（`--spring.profiles.active=sqlite` 可切回 SQLite） |
| 迁移数据量 | 较小，一次性读取批量写入即可 |

---

## 三、改造方案（具体文件 + What/Why/How）

### 3.1 新增 MySQL 依赖（pom.xml）

**文件**：[pom.xml](file:///d:/lcProject/stock-pulse/stock-watcher/pom.xml)

**改动**：在 sqlite-jdbc 依赖之后新增 MySQL 驱动（保留 sqlite-jdbc 以支持双模式）。

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

> 版本由 Spring Boot BOM 管理，无需显式指定。

---

### 3.2 配置文件：默认 MySQL + SQLite profile

#### 3.2.1 主配置 `application.yml`（改为默认 MySQL）

**文件**：[application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)

**改动**：将 `spring.datasource.*` 改为 MySQL 连接；用 `${DB_*:默认值}` 占位符支持环境变量覆盖；通过 `spring.sql.init.schema-locations` 按 profile 选择 schema。

```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:mysql}   # 默认 mysql
  datasource:
    url: ${DB_URL:jdbc:mysql://114.132.166.29:3306/stock?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}
    username: ${DB_USERNAME:stock}
    password: ${DB_PASSWORD:stock12345..}
    driver-class-name: ${DB_DRIVER:com.mysql.cj.jdbc.Driver}
  sql:
    init:
      mode: always
      schema-locations: ${SCHEMA_LOCATION:classpath:schema-mysql.sql}
```

> 说明：通过 `SCHEMA_LOCATION` 环境变量或 profile 切换 schema 文件。

#### 3.2.2 新增 `application-mysql.yml`

**文件（新）**：`stock-watcher/src/main/resources/application-mysql.yml`

显式锁死 MySQL 配置（避免环境变量缺失时误连）：
```yaml
spring:
  datasource:
    url: jdbc:mysql://114.132.166.29:3306/stock?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: stock
    password: stock12345..
    driver-class-name: com.mysql.cj.jdbc.Driver
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-mysql.sql
app:
  db-type: mysql
```

#### 3.2.3 新增 `application-sqlite.yml`

**文件（新）**：`stock-watcher/src/main/resources/application-sqlite.yml`

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./data/stock_watcher.db
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-sqlite.sql
app:
  db-type: sqlite
```

> `app.db-type` 自定义属性供 Java 代码读取（动态分页方言用）。

---

### 3.3 拆分 schema：两份文件

#### 3.3.1 保留/改造 `schema.sql` → `schema-mysql.sql`

**文件（重命名/新建）**：`stock-watcher/src/main/resources/schema-mysql.sql`

**改动要点**：
- 数据类型映射（**最小化改动原则**）：
  - `TEXT` → `VARCHAR(N)`（按字段语义给长度，日期类字段如 trade_date 用 `VARCHAR(8)`，name 用 `VARCHAR(64)`，created_at/updated_at 用 `VARCHAR(32)` 等）
  - `REAL` → `DECIMAL(20,4)`（价格/金额类，如 open/high/low/close/vol/amount/adj_factor/cash_div 等）
  - `INTEGER`（业务标志如 enabled/is_open） → `TINYINT`
- 主键：`INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGINT AUTO_INCREMENT PRIMARY KEY`
- 字符集：每张表末尾加 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`
- 复合主键保持 `(ts_code, trade_date)`
- `UNIQUE(...)` 约束保留
- **所有日期字段一律 VARCHAR**：trade_date / list_date / delist_date / cal_date / pretrade_date / end_date / ann_date / record_date / ex_date / pay_date / div_listdate / imp_ann_date / base_date / created_at / updated_at 全部用 VARCHAR
- 末尾种子 admin 插入语句保留（MySQL 也支持 `INSERT ... SELECT ... WHERE NOT EXISTS`）

示例（sys_user 表）：
```sql
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    totp_secret VARCHAR(64),
    enabled     TINYINT      DEFAULT 1,
    email       VARCHAR(128),
    phone       VARCHAR(32),
    role        VARCHAR(16)  DEFAULT 'USER',
    created_at  VARCHAR(32),
    updated_at  VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

示例（daily_quote 表，复合主键 + 日期为 VARCHAR(8)）：
```sql
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code    VARCHAR(16)    NOT NULL,
    trade_date VARCHAR(8)     NOT NULL,
    open       DECIMAL(20,4),
    high       DECIMAL(20,4),
    low        DECIMAL(20,4),
    close      DECIMAL(20,4),
    pre_close  DECIMAL(20,4),
    change_amt DECIMAL(20,4),
    pct_chg    DECIMAL(20,4),
    vol        DECIMAL(20,4),
    amount     DECIMAL(20,4),
    PRIMARY KEY (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **优势**：日期字段保持 VARCHAR 后，**所有 DO 实体类的字段类型（String/BigDecimal/Long 等）与 Service 业务逻辑完全无需调整**，MetaObjectHandler 自动填充 createdAt/updatedAt（LocalDateTime → 由 JDBC 驱动转字符串）也不受影响。

#### 3.3.2 新建 `schema-sqlite.sql`

**文件（新）**：`stock-watcher/src/main/resources/schema-sqlite.sql`

内容为现有 [schema.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema.sql) 的原样复制（保留 SQLite 的 `INTEGER PRIMARY KEY AUTOINCREMENT` / `TEXT` / `REAL`）。

> 原 `schema.sql` 可保留作为参考，也可删除（避免 Spring Boot 默认加载混淆）。建议**删除**以防止歧义。

---

### 3.4 MyBatis-Plus 分页方言动态化

**文件**：[MyBatisPlusConfig.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java)

**改动**：注入 `app.db-type` 配置，动态选择 `DbType`。

```java
@Value("${app.db-type:mysql}")
private String dbType;

@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    DbType type = "sqlite".equalsIgnoreCase(dbType) ? DbType.SQLITE : DbType.MYSQL;
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(type));
    return interceptor;
}
```

> MetaObjectHandler 自动填充部分**无需改动**（createdAt/updatedAt 在实体里是 LocalDateTime，写入 DB 时驱动会处理；schema 中保持 VARCHAR(32) 即可，或者也可以用 DATETIME，由 HikariCP + 驱动透明转换）。

---

### 3.5 引入 XML Mapper（全部 7 个 Mapper 改造）

**目标**：将所有 7 个 Mapper 从 `@Insert("<script>")` / `@Select` 注解写法迁移到 XML Mapper 文件，统一项目风格、提升复杂 SQL 可读性。

> 注：虽然 XML 原生支持 `databaseId` 多方言选择，但本次改造**仍坚持通用 SQL**（不写两版 UPSERT），XML 仅用于结构化和可读性。

#### 3.5.1 创建 mapper XML 目录

**新建目录**：`stock-watcher/src/main/resources/mapper/`

`application.yml` 中已有配置 `mybatis-plus.mapper-locations: classpath:mapper/*.xml`，无需调整。

#### 3.5.2 新建 7 个 XML Mapper 文件

| Mapper 接口 | XML 文件 |
|---|---|
| UserMapper | `mapper/UserMapper.xml` |
| WatchlistMapper | `mapper/WatchlistMapper.xml` |
| DailyQuoteMapper | `mapper/DailyQuoteMapper.xml` |
| StockBasicMapper | `mapper/StockBasicMapper.xml` |
| TradeCalMapper | `mapper/TradeCalMapper.xml` |
| AdjFactorMapper | `mapper/AdjFactorMapper.xml` |
| DividendMapper | `mapper/DividendMapper.xml` |

#### 3.5.3 Mapper 接口改造

**改造原则**：接口只保留方法签名（去掉 `@Insert` / `@Select` 注解及其 SQL 字符串），由 XML 中的 `<insert>` / `<select>` / `<update>` 提供实现。BaseMapper 继承的 CRUD 不受影响。

示例（[DailyQuoteMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/DailyQuoteMapper.java) 改造后）：

```java
@Mapper
public interface DailyQuoteMapper extends BaseMapper<DailyQuoteDO> {
    // 普通 INSERT（无冲突语法，跨方言通用）
    int insertBatch(@Param("list") List<DailyQuoteDO> list);

    // 查询已存在的主键集合（用于 Service 层"先查后判"）
    List<String> selectExistingKeys(@Param("list") List<DailyQuoteDO> list);

    List<Map<String, Object>> selectTradeDateStockCount(@Param("startDate") String startDate,
                                                        @Param("endDate") String endDate);
    String selectLatestTradeDate();
    List<DailyQuoteDO> selectTopGainers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);
    List<DailyQuoteDO> selectTopLosers(@Param("tradeDate") String tradeDate, @Param("limit") int limit);
    List<DailyQuoteDO> selectTopAmount(@Param("tradeDate") String tradeDate, @Param("limit") int limit);
}
```

#### 3.5.4 XML Mapper 文件示例（DailyQuoteMapper.xml）

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.arthur.stock.mapper.DailyQuoteMapper">

    <!-- 批量插入（普通 INSERT，无冲突语法，跨方言通用） -->
    <insert id="insertBatch">
        INSERT INTO daily_quote (ts_code, trade_date, open, high, low, close, pre_close,
                                 change_amt, pct_chg, vol, amount) VALUES
        <foreach collection="list" item="item" separator=",">
            (#{item.tsCode}, #{item.tradeDate}, #{item.open}, #{item.high}, #{item.low}, #{item.close},
             #{item.preClose}, #{item.changeAmt}, #{item.pctChg}, #{item.vol}, #{item.amount})
        </foreach>
    </insert>

    <!-- 查询已存在的主键（Service 层判断用） -->
    <select id="selectExistingKeys" resultType="string">
        SELECT ts_code FROM daily_quote
        WHERE (ts_code, trade_date) IN
        <foreach collection="list" item="item" open="(" separator="," close=")">
            (#{item.tsCode}, #{item.tradeDate})
        </foreach>
    </select>

    <select id="selectTradeDateStockCount" resultType="map">
        SELECT trade_date, COUNT(DISTINCT ts_code) AS cnt
        FROM daily_quote
        WHERE trade_date &gt;= #{startDate} AND trade_date &lt;= #{endDate}
        GROUP BY trade_date
    </select>

    <select id="selectLatestTradeDate" resultType="string">
        SELECT MAX(trade_date) FROM daily_quote
    </select>

    <select id="selectTopGainers" resultType="com.arthur.stock.model.DailyQuoteDO">
        SELECT * FROM daily_quote WHERE trade_date = #{tradeDate}
        AND ts_code IN (SELECT ts_code FROM stock_basic WHERE list_status = 'L')
        ORDER BY pct_chg DESC LIMIT #{limit}
    </select>

    <!-- selectTopLosers / selectTopAmount 同结构，仅 ORDER BY 不同 -->
</mapper>
```

> **跨方言验证**：上述 SQL 中 `INSERT INTO ... VALUES (...), (...)` / `WHERE (a,b) IN (...)` / `MAX` / `COUNT(DISTINCT)` / `GROUP BY` / `LIMIT` / `IN 子查询` 均为 MySQL 与 SQLite 共通语法。`>=`/`<=` 在 XML 里需用 `&gt;=`/`&lt;=` 转义。

---

### 3.6 移除所有 UPSERT 语义，改为"先查后判"（核心改造）

**原则**：Service 层负责判断，Mapper 只提供通用 INSERT/UPDATE/SELECT，**完全跨方言通用**。

各 Service 改造模式：

以 [DailyQuoteServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DailyQuoteServiceImpl.java) 为例（原语义=INSERT OR IGNORE，即"已存在则跳过"）：

```java
public int saveDailyQuotes(List<DailyQuoteDO> list) {
    if (list.isEmpty()) return 0;
    // 1. 查询已存在的主键
    Set<String> existing = new HashSet<>(dailyQuoteMapper.selectExistingKeys(list));
    // 2. 过滤出新增的
    List<DailyQuoteDO> toInsert = list.stream()
        .filter(d -> !existing.contains(d.getTsCode() + "|" + d.getTradeDate()))
        .collect(Collectors.toList());
    // 3. 仅插入新增
    if (!toInsert.isEmpty()) {
        return dailyQuoteMapper.insertBatch(toInsert);
    }
    return 0;
}
```

各 Service 对应语义：

| Service | 原语义 | 改造后逻辑 |
|---|---|---|
| DailyQuoteServiceImpl | INSERT OR IGNORE | 查已存在 → 过滤掉 → 仅插入新的 |
| AdjFactorServiceImpl | INSERT OR IGNORE | 同上 |
| DividendServiceImpl | INSERT OR IGNORE | 同上 |
| StockBasicServiceImpl | INSERT OR REPLACE | 查已存在 → 拆为新增列表 + 更新列表 → 分别 insertBatch / updateBatch |
| TradeCalServiceImpl | INSERT OR REPLACE | 同 StockBasic |

**批量 update**：XML 中新增 `<update>` 语句，用 `<foreach>` 拼多条 `UPDATE` 或 `CASE WHEN`，跨方言通用。

---

### 3.6 DataInitServiceImpl 的 DDL 同步改造

**文件**：[DataInitServiceImpl.java#L200-L226](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java#L200-L226)

**问题**：这里硬编码了第二份 SQLite DDL，会与 schema-mysql.sql 不一致。

**改动**：根据 `app.db-type` 注入对应版本的 `CREATE_TABLE_SQL` Map，或直接读取 schema 文件解析（更优雅但复杂度更高）。**推荐**：建两个静态 Map（`CREATE_TABLE_SQL_MYSQL` / `CREATE_TABLE_SQL_SQLITE`），运行时按 db-type 选择。

```java
@Value("${app.db-type:mysql}")
private String dbType;

private Map<String, String> getCreateTableSql() {
    return "sqlite".equalsIgnoreCase(dbType) ? CREATE_TABLE_SQL_SQLITE : CREATE_TABLE_SQL_MYSQL;
}
```

> DROP TABLE 语句两边通用，无需改。

---

### 3.7 数据迁移脚本（一次性 Java 脚本）

**文件（新）**：`stock-watcher/src/main/java/com/arthur/stock/migration/SQLiteToMysqlMigrator.java`

**设计**：
- 作为 `CommandLineRunner`，但加 `@ConditionalOnProperty(name="app.migrate", havingValue="true")` 守卫——只有显式配置 `app.migrate=true` 时才执行，正常启动不会误触发。
- 用纯 JDBC 同时连��� SQLite（源）和 MySQL（目标）两个数据源（独立于主 DataSource，避免冲突）。
- 复用项目的 7 个 DO 实体类。

**迁移流程**：
```
1. 连接 SQLite (./data/stock_watcher.db)
2. 连接 MySQL (114.132.166.29:3306/stock)
3. 按表顺序逐表迁移（注意外键依赖：sys_watchlist.user_id → sys_user.id，需先迁 sys_user）：
   迁移顺序：sys_user → sys_watchlist → stock_basic → trade_cal → daily_quote → adj_factor → dividend
4. 每张表：
   a. SELECT * FROM <sqlite_table>
   b. 一次性读取全部（数据量小）
   c. 按 MySQL schema 字段类型转换（String→String，REAL→BigDecimal，INTEGER→Long 等）
   d. 用 JDBC PreparedStatement 批量 INSERT（每批 1000 条 + executeBatch）
5. 日志打印每表迁移行数
6. 迁移完成后打印汇总，不自动删除 SQLite 文件（保留作为备份）
```

**配置示例**（启动迁移时）：
```bash
java -jar stock-watcher.jar --app.migrate=true --spring.profiles.active=mysql
```

或在 `application-mysql.yml` 临时加 `app.migrate: true`，跑完后移除。

> **重要**：MySQL 表需先建好（由 schema-mysql.sql 在启动时自动执行），再跑迁移。所以迁移脚本本身也走 Spring Boot 启动流程（schema 先初始化 → CommandLineRunner 再迁移）。

---

## 四、需新建/修改文件清单

### 修改（9 个）
1. [pom.xml](file:///d:/lcProject/stock-pulse/stock-watcher/pom.xml) — 加 mysql-connector-j
2. [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml) — 默认 MySQL
3. [MyBatisPlusConfig.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java) — 动态分页方言
4. [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) — 双 DDL Map
5. [DailyQuoteMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/DailyQuoteMapper.java) — 去 UPSERT
6. [StockBasicMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/StockBasicMapper.java) — 去 UPSERT
7. [TradeCalMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/TradeCalMapper.java) — 去 UPSERT
8. [AdjFactorMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/AdjFactorMapper.java) — 去 UPSERT
9. [DividendMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/DividendMapper.java) — 去 UPSERT

### 对应 Service 修改（5 个）
10. [DailyQuoteServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DailyQuoteServiceImpl.java)
11. [StockBasicServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockBasicServiceImpl.java)
12. [TradeCalServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java)
13. [AdjFactorServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/AdjFactorServiceImpl.java)
14. [DividendServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DividendServiceImpl.java)

### 新建（12 个）
15. `stock-watcher/src/main/resources/schema-mysql.sql`（MySQL 表结构）
16. `stock-watcher/src/main/resources/schema-sqlite.sql`（SQLite 表结构，即原 schema.sql 内容）
17. `stock-watcher/src/main/resources/application-mysql.yml`
18. `stock-watcher/src/main/resources/application-sqlite.yml`
19. `stock-watcher/src/main/resources/mapper/UserMapper.xml`
20. `stock-watcher/src/main/resources/mapper/WatchlistMapper.xml`
21. `stock-watcher/src/main/resources/mapper/DailyQuoteMapper.xml`
22. `stock-watcher/src/main/resources/mapper/StockBasicMapper.xml`
23. `stock-watcher/src/main/resources/mapper/TradeCalMapper.xml`
24. `stock-watcher/src/main/resources/mapper/AdjFactorMapper.xml`
25. `stock-watcher/src/main/resources/mapper/DividendMapper.xml`
26. `stock-watcher/src/main/java/com/arthur/stock/migration/SQLiteToMysqlMigrator.java`（迁移脚本）

### 删除（1 个）
27. 原 `stock-watcher/src/main/resources/schema.sql`（被 schema-mysql/sqlite 替代，删除避免歧义）

> **注**：原 `application.yml` 的 `mybatis-plus.mapper-locations: classpath:mapper/*.xml` 配置已就绪，新建的 XML 文件放到 `resources/mapper/` 下即可被自动加载，无需改配置。

---

## 五、验证步骤

### 5.1 MySQL 模式（默认）
1. 确认 MySQL 服务器 `114.132.166.29:3306` 可达，`stock` 库存在且 `stock` 用户有 DDL/DML 权限
2. 启动 watcher（默认 profile=mysql）：`mvn spring-boot:run`
3. 验证启动日志：
   - 出现 `HikariPool-1 - Driver ... com.mysql.cj.jdbc.Driver`
   - schema-mysql.sql 执行成功，7 张表建好（用 MySQL 客户端 `SHOW TABLES;` 核对）
   - `sys_user` 表有 admin 种子账号
4. 调用关键 API 冒烟：
   - 登录 admin/admin123 → 成功
   - 查询股票列表、自选股增删 → 成功
   - 触发日线数据拉取（验证去 UPSERT 后的"先查后判"批量插入）
5. 涨跌榜/搜索接口（验证 `@Select` 查询通用）→ 成功

### 5.2 SQLite 模式（回归）
1. 启动：`mvn spring-boot:run -Dspring.profiles.active=sqlite`
2. 验证 `data/stock_watcher.db` 建表正常，原有功能不受影响

### 5.3 数据迁移验证
1. 备份现有 `data/stock_watcher.db`
2. 确保 MySQL 库为空（或先 DROP 全部表）
3. 运行：`java -jar stock-watcher.jar --app.migrate=true --spring.profiles.active=mysql`
4. 核对每张表行数：SQLite 源行数 == MySQL 目标行数
5. 抽样核对数据正确性（如某只股票的若干日行情数据）

### 5.4 引擎侧（无需改动）
- stock-engine 的 `test_no_db.py` 守卫**不受影响**（Python 侧不碰数据库）。

---

## 六、风险与注意事项

1. **两份 schema 必须同步维护**：以后字段变更要同时改 schema-mysql.sql 和 schema-sqlite.sql。可在 schema 文件头注释提醒。
2. **DataInitServiceImpl 的 DDL 与 schema 文件是第三份/第四份定义**：本次改造把 DataInitServiceImpl 也按 db-type 分两套，但仍是独立定义。建议后续考虑统一（本次范围内不强制）。
3. **迁移脚本不影响正常启动**：靠 `@ConditionalOnProperty(name="app.migrate", havingValue="true")` 守卫，默认 false。
4. **密码包含特殊字符**：MySQL 密码 `stock12345..` 末尾两个点，yml 中无需转义；但若放 URL query 段需 URL 编码。
5. **字符集统一 utf8mb4**：MySQL 建表强制 utf8mb4，避免中文股票名称乱码。
6. **时区**：MySQL JDBC URL 带 `serverTimezone=Asia/Shanghai`，避免时间字段时区偏差。
7. **`LIMIT N` 分页**：MyBatis-Plus 分页插件已按 db-type 处理方言，业务代码中 `.last("LIMIT 1")` 在 MySQL/SQLite 都支持，无需改。
8. **XML Mapper 转义**：XML 中 `<`/`>`/`<=`/`>=` 必须转义为 `&lt;`/`&gt;`/`&lt;=`/`&gt;=`，否则解析失败。或在 `<select>` 外包 `<![CDATA[ ... ]]>`。
9. **业务规则文档需同步更新**：原 [02-tushare-integration-guide.md](file:///d:/lcProject/stock-pulse/.trae/rules/stock-watcher/business/02-tushare-integration-guide.md) 中"本项目使用注解方式，不要 XML"的约定已被本次决策推翻，需相应修订（属于文档维护，非代码改动）。
10. **实体类无 @TableId 的复合主键表**（daily_quote/adj_factor）：XML 中 insert/update 不依赖主键自增，无影响；但 MyBatis-Plus 的 selectById/updateById 不可用于这些表（项目本来也没这样用，仅通过自定义 SQL 操作）。
