# 移除 SQLite 支持，仅保留 MySQL

## 概述

将 stock-pulse 项目从「MySQL（默认）+ SQLite（可选 profile）双库兼容」简化为「仅 MySQL」。删除所有 SQLite 相关的配置文件、Java 类、schema、依赖，并清理代码中所有引用 SQLite 的注释和方言分支逻辑。

**范围**：stock-watcher（Java/Spring Boot）为主，stock-engine（Python）仅清理遗留配置。akquant 第三方库（`akquant-0.2.47/`）不修改，engine 侧已有 `pop("db_path")` 防护。

## 当前状态分析

项目当前维护双库兼容：
- `application.yml` 默认 `mysql` profile，但通过 `DB_TYPE` 环境变量可切 `sqlite`
- `application-sqlite.yml` 提供 SQLite profile
- 两份 schema 文件（`schema-mysql.sql` / `schema-sqlite.sql`）需同步维护
- Java 代码中 `dbType` 字段 + `switch(databaseType)` 分支处理方言差异
- `SQLiteToMysqlMigrator.java` 一次性迁移工具（已完成使命）
- `pom.xml` 引入 `sqlite-jdbc` 驱动
- `StrategySchemaMigrationTest.java` 用 `jdbc:sqlite::memory:` 做集成测试

## 变更计划

### 第 1 步：删除文件（5 个）

| 文件 | 说明 |
|---|---|
| `stock-watcher/src/main/resources/application-sqlite.yml` | SQLite profile 配置 |
| `stock-watcher/src/main/resources/schema-sqlite.sql` | SQLite 表结构 DDL |
| `stock-watcher/src/main/java/com/arthur/stock/migration/SQLiteToMysqlMigrator.java` | SQLite→MySQL 迁移工具（一次性，已完成使命） |
| `stock-watcher/src/test/java/com/arthur/stock/migration/StrategySchemaMigrationTest.java` | 测试依赖 `jdbc:sqlite::memory:`，SQLite 分支已删，MySQL 分支的迁移是一次性操作且生产已执行 |
| `stock-watcher/data/stock_watcher.db`（若存在） | SQLite 数据库文件 |

### 第 2 步：移除 pom.xml 的 sqlite-jdbc 依赖

**文件**：`stock-watcher/pom.xml`

删除第 103-107 行：
```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.47.2.0</version>
</dependency>
```

### 第 3 步：简化 application.yml

**文件**：`stock-watcher/src/main/resources/application.yml`

- 第 110 行注释 `# ========== App Database Type (mysql / sqlite) ==========` 改为 `# ========== App Database Type ==========` 或直接删除
- 第 111-112 行 `app: db-type: ${DB_TYPE:mysql}` 整段删除（不再需要运行时切换数据库类型）

### 第 4 步：简化 MyBatisPlusConfig.java

**文件**：`stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java`

- 删除 `@Value("${app.db-type:mysql}") private String dbType;`（第 22-23 行）
- `mybatisPlusInterceptor()` 方法中删除方言判断，直接用 `DbType.MYSQL`（第 29-33 行）

改后：
```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

### 第 5 步：简化 StrategySchemaMigration.java

**文件**：`stock-watcher/src/main/java/com/arthur/stock/migration/StrategySchemaMigration.java`

移除所有 SQLite 方言分支，仅保留 MySQL 路径：
- `DatabaseType` 枚举删除 `SQLITE`（第 207 行），或整个枚举简化掉
- `detectDatabaseType()` 删除 SQLite 检测分支（第 165-166 行），或整个方法简化为直接返回 MySQL
- `migrateStrategyUuidColumn()` 的 switch 删除 `case SQLITE`（第 65 行），只保留 MySQL ALTER
- `migrateBacktestStrategyReferences()` 的 switch 删除 `case SQLITE`（第 79 行），只调用 `migrateMysqlBacktestReferences()`
- 删除 `migrateSqliteBacktestReferences()` 方法（第 116-153 行）

### 第 6 步：清理 StockSuspendDServiceImpl.java

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockSuspendDServiceImpl.java`

- 删除 `@Value("${app.db-type:mysql}") private String dbType;`（第 66-67 行）
- 删除 `migrateSuspendTimingColumnLength()` 中的 SQLite 早返回（第 78-79 行 `if ("sqlite"...return`）

### 第 7 步：清理 DataInitServiceImpl.java

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`

- 删除 `@Value("${app.db-type:mysql}") private String dbType;`（第 56-57 行）——声明后从未使用，纯死代码

### 第 8 步：清理 stock-engine 遗留配置

**文件**：`stock-engine/.env.example`

- 删除第 5-6 行的 `# 数据库配置` 和 `DB_PATH=./stock_data.db`（engine 不触库，此为遗留废弃配置）

### 第 9 步：清理注释/文案中的 SQLite 引用

以下文件中注释或用户可见文案提到 SQLite，需更新为 MySQL only：

| 文件 | 行号 | 当前内容 | 修改为 |
|---|---|---|---|
| `schema-mysql.sql` | 3 | `请同步修改 schema-sqlite.sql` | 删除该行注释 |
| `static/js/factor-library.js` | 363 | `从 SQLite 的` | `从 MySQL 的` |
| `mapper/DailyQuoteMapper.xml` | 268 | `MySQL/SQLite 均支持` | `MySQL 支持` |
| `mapper/IndexDailyMapper.xml` | 20 | `SQLite 兼容` | 删除括号内 SQLite 相关文字 |
| `mapper/TradeCalMapper.java` | 22 | `SQLite（不支持行值 IN 语法）` | 删除 SQLite 说明，改为 MySQL 说明或删除 |
| `mapper/TradeCalMapper.java` | 32 | `MySQL/SQLite 双方言通用` | `MySQL 通用` |
| `mapper/TradeCalMapper.java` | 43 | `跨 MySQL/SQLite 通用` | `MySQL 通用` |
| `mapper/TradeCalMapper.java` | 48 | `跨 MySQL/SQLite 通用` | `MySQL 通用` |
| `service/impl/TradeCalServiceImpl.java` | 177 | `MySQL/SQLite 双方言兼容` | `MySQL 兼容` |
| `service/impl/TradeCalServiceImpl.java` | 256 | `MySQL/SQLite 方言通用` | `MySQL 通用` |
| `service/impl/MarketServiceImpl.java` | 69 | `MySQL/SQLite 的` | `MySQL 的` |
| `service/IndexDailyFetchService.java` | 28 | `SQLite/MySQL 通用` | `MySQL 通用` |
| `mapper/IndexDailyMapper.java` | 18 | `SQLite/MySQL 通用` | `MySQL 通用` |

### 第 10 步：清理 .gitignore

**文件**：`.gitignore`

删除第 51-53 行的 SQLite 文件忽略规则（不再产生 SQLite 文件）：
```
*.sqlite
*.sqlite3
*.db-journal
```

## 不修改的部分

- **`akquant-0.2.47/`**：第三方库，其内部 `optimize.py` 使用 `sqlite3` 是它自己的实现，不属于项目代码。engine 侧 `optimizer.py` 已有 `pop("db_path", None)` 硬防护。
- **engine 的 `test_no_db.py` 守护测试**：这些测试断言 engine 不使用 sqlite3/sqlalchemy，移除 SQLite 支持后仍然有效，不需修改。
- **engine 的 `optimizer.py`**：`pop("db_path", None)` 防护逻辑保留，防止 akquant 内部 sqlite3 落盘。

## 验证步骤

1. **全局搜索验证**：在 `stock-watcher/src` 和 `stock-engine` 目录下 grep `sqlite`（不区分大小写），确认除 akquant 第三方库外无任何残留
2. **编译验证**：`cd stock-watcher && mvn compile -q` 确认 Java 编译通过
3. **启动验证**：用 MySQL profile 启动 watcher，确认无 `app.db-type` 缺失报错
4. **engine 验证**：`cd stock-engine && python -m pytest tests/ -q` 确认守护测试通过
5. **依赖验证**：`mvn dependency:tree | grep sqlite` 确认 sqlite-jdbc 已从依赖树移除
