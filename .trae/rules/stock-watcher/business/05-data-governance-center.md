
# 数据管控中心（Data Governance Center）

&gt; **面向 AI**：本文档详细介绍 stock-watcher 的数据管控中心模块——数据质量校验体系、统一更新入口、拉取日志、定时任务、数据源健康检查。新增 Tushare 接口时，数据治理相关接入步骤以本文为准。
&gt; **与 02/03 的关系**：03 是全局地图，02 是完整对接步骤（含数据治理），本文是数据治理模块的专题详解。

---

## 一、模块定位与架构

### 1.1 模块定位

数据管控中心是 stock-watcher 的**数据质量与运维中枢**，负责：

- 全量 25 张业务表的数据质量监控
- 统一的增量更新 / 全量重入口
- 数据拉取操作的可观测性（日志、进度）
- 数据源（Tushare）连通性健康检查

### 1.2 模块架构图

```
┌──────────────────────────────────────────────────────────────┐
│                   DataGovernanceController                    │
│  (REST API：概览/表状态/更新/检测/任务/日志/数据源)            │
└────────────┬──────────────────────────────┬──────────────────┘
             │                              │
┌────────────▼────────────┐    ┌────────────▼────────────┐
│  DataGovernanceService  │    │    DataInitService      │
│  (数据质量检测服务)      │    │  (统一数据更新服务)      │
│  - 单表/全表检测         │    │  - 增量更新             │
│  - 状态查询              │    │  - 全量重建             │
│  - 历史记录              │    │  - 异步执行             │
└────────────┬────────────┘    └────────────┬────────────┘
             │                              │
┌────────────▼────────────┐    ┌────────────▼────────────┐
│    DataCheckable        │    │   TaskProgressCache     │
│  (26个Service实现)       │    │  (内存缓存 + 全局锁)     │
│  - checkData()          │    │  - 进度存储(30min过期)  │
│  - getTableCode()       │    │  - 任务锁(2h超时)       │
└────────────┬────────────┘    └────────────┬────────────┘
             │                              │
┌────────────▼────────────┐    ┌────────────▼────────────┐
│ DataGovernanceMetricDO  │    │     DataPullLogDO       │
│  (检测历史表)            │    │  (拉取日志表)            │
│  data_governance_metric │    │    data_pull_log        │
│  (保留3个月，自动清理)   │    │  (操作记录/审计)        │
└─────────────────────────┘    └─────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│                   定时任务层                                 │
│  DataGovernanceCheckJob (每日22:00全表检测)                  │
│  DailyUpdateTask / 各专项任务 (数据更新)                     │
│  DataSourceHealthJob (数据源健康检测，可选)                  │
└────────────────────────────────────────────────────────────┘

┌─────────────────────────┐
│  DataSourceHealthCache  │
│  (数据源连通性缓存)      │
│  - 最新状态/响应时间     │
│  - 手动测试接口          │
└─────────────────────────┘
```

---

## 二、核心概念

### 2.1 InitStep 与表元数据

`InitStep` 枚举是数据管控中心的**表注册表**，每张业务表对应一个枚举值，包含丰富的元数据：

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | String | 表代码（唯一标识，全小写下划线） |
| `label` | String | 表中文名（展示用） |
| `tableName` | String | 数据库表名 |
| `group` | TableGroup | 表分组（BASIC/MARKET/FINANCE/EVENT/INDEX） |
| `updateFrequency` | String | 更新频率描述（如"每个交易日 16:00"） |
| `expectedUpdateTime` | String | 期望更新时间点（如"16:00"） |
| `isDaily` | boolean | 是否为日线表（影响延迟检测逻辑） |
| `tushareApi` | String | 对应 Tushare 接口名 |

**枚举定义位置**：`src/main/java/com/arthur/stock/constant/InitStep.java`

---

### 2.2 TableGroup 五大分组

`TableGroup` 枚举定义了 5 个数据分组，用于前端分类展示和批量操作：

| 分组 | 中文名 | 说明 | 代表表 |
|------|--------|------|--------|
| `BASIC` | 基础数据 | 参考类基础数据 | stock_basic、trade_cal |
| `MARKET` | 行情数据 | 行情与交易数据 | daily_quote、adj_factor、daily_basic |
| `FINANCE` | 财务数据 | 财务报表与指标 | income、balancesheet、fina_indicator |
| `EVENT` | 事件数据 | 事件驱动类数据 | dividend、top_list、block_trade |
| `INDEX` | 指数与市场 | 指数、板块、互联互通 | index_weight、hk_hold、margin |

**枚举定义位置**：`src/main/java/com/arthur/stock/constant/TableGroup.java`

---

### 2.3 表状态（TableStatus）

每张表有四种状态：

| 状态 | 说明 | 触发条件 |
|------|------|---------|
| `NORMAL` | 正常 | 所有检测项通过，且日线表最新数据不延迟 |
| `DELAYED` | 延迟 | 日线表最新数据日期早于上一交易日 |
| `ERROR` | 异常 | 存在 ERROR 级别的未通过检测项 |
| `UPDATING` | 更新中 | 有正在运行的更新任务或检测任务（实时叠加） |

**注意**：`UPDATING` 是**实时叠加状态**，不存储在数据库中，由 `TaskProgressCache` 中的锁状态动态判断。

**枚举定义位置**：`src/main/java/com/arthur/stock/dto/governance/TableStatus.java`

---

### 2.4 检测级别（CheckLevel）

检测项未通过时的严重级别：

| 级别 | 说明 | 对状态的影响 |
|------|------|-------------|
| `ERROR` | 错误 | 表状态 → ERROR |
| `WARN` | 警告 | 不改变表状态，但在前端展示告警 |

**枚举定义位置**：`src/main/java/com/arthur/stock/dto/governance/CheckLevel.java`

---

## 三、核心组件详解

### 3.1 DataCheckable 接口

数据校验的**统一契约接口**，每张业务表的 Service 都必须实现。

**接口定义**：

```java
public interface DataCheckable {
    /** 执行校验，返回所有检测项结果（含通过的和不通过的） */
    DataCheckResult checkData();
    /** 表代码，对应 InitStep.code */
    String getTableCode();
}
```

**位置**：`src/main/java/com/arthur/stock/service/DataCheckable.java`

**自动发现机制**：`DataGovernanceServiceImpl` 通过 Spring 自动注入所有 `DataCheckable` 实现，按 `tableCode` 建立索引映射，无需手动注册。

```java
// DataGovernanceServiceImpl 中
private final List&lt;DataCheckable&gt; checkables;  // Spring 自动注入所有实现
private Map&lt;String, DataCheckable&gt; checkableMap;  // 按 tableCode 索引

@PostConstruct
public void init() {
    checkableMap = checkables.stream()
            .collect(Collectors.toMap(DataCheckable::getTableCode, c -&gt; c, (a, b) -&gt; a));
}
```

---

### 3.2 DataGovernanceService（数据质量检测服务）

数据质量检测的核心服务，提供表级别的检测与查询能力。

**核心方法**：

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `checkTable(tableCode)` | `DataCheckResult` | 同步检测单张表，保存结果 |
| `checkAll()` | `String` (taskId) | 异步检测全部表，返回 taskId 供轮询进度 |
| `checkAllScheduled()` | `String` (batchId) | 同步检测全部表（供定时任务调用） |
| `getLatestBatch()` | `List&lt;DataGovernanceMetricDO&gt;` | 获取最新一次检测批次的全部记录 |
| `getLatestMetric(tableCode)` | `DataGovernanceMetricDO` | 获取某张表最新的检测记录 |
| `getTableStatus(tableCode)` | `String` | 获取某张表的当前状态（含实时 UPDATING 叠加） |
| `getAllTableStatuses()` | `List&lt;DataGovernanceMetricDO&gt;` | 获取全部表的最新状态（含实时 UPDATING 叠加） |
| `getMetricHistory(tableCode, limit)` | `List&lt;DataGovernanceMetricDO&gt;` | 获取某张表的历史检测记录 |

**位置**：
- 接口：`src/main/java/com/arthur/stock/service/DataGovernanceService.java`
- 实现：`src/main/java/com/arthur/stock/service/impl/DataGovernanceServiceImpl.java`

---

### 3.3 DataInitService（统一数据更新服务）

所有业务表的**统一数据更新入口**，提供增量更新和全量重建两种模式。

**核心方法**：

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `incrementalUpdate(tableCode, operator)` | `String` (taskId) | 增量更新：从最新数据日期的下一天开始拉取 |
| `fullRebuild(tableCode, operator)` | `String` (taskId) | 全量重建：清空表后从头拉取全部历史数据 |

**两种操作类型**：

| 操作类型 | 枚举值 | 说明 |
|---------|--------|------|
| 增量更新 | `MANUAL_INCREMENTAL` | 从已有数据的最新日期 +1 开始拉取，不删除已有数据 |
| 全量重建 | `MANUAL_FULL` | 清空表后从最早可用日期开始完整拉取 |

**位置**：
- 接口：`src/main/java/com/arthur/stock/service/DataInitService.java`
- 实现：`src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`

---

### 3.4 TaskProgressCache（任务进度缓存）

基于 Caffeine 的内存缓存，管理数据拉取/检测任务的实时进度和并发控制。

**核心能力**：

| 能力 | 说明 |
|------|------|
| 进度存储 | 任务进度写入后 30 分钟自动过期 |
| 全局任务锁 | 同一时间只能有一个拉取任务运行（更新锁 + 检测锁，两把独立锁） |
| 锁超时 | 锁自带 2 小时超时，防止任务异常退出导致永久死锁 |
| 心跳续期 | 任务线程可定期调用 heartbeat 续期 |
| 取消机制 | 支持设置取消标志，任务循环中检查并优雅退出 |

**锁的类型**：

| 锁 | 用途 |
|----|------|
| 拉取任务锁 (`taskLock`) | 增量更新/全量重建任务的互斥锁 |
| 检测任务锁 (`checkLock`) | 全表数据质量检测任务的互斥锁 |

**位置**：`src/main/java/com/arthur/stock/cache/TaskProgressCache.java`

---

### 3.5 DataPullLog（数据拉取日志）

记录每次数据拉取操作的详细日志，用于审计和问题排查。

**数据表**：`data_pull_log`

**核心字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 自增主键 |
| `task_id` | String | 任务唯一ID（UUID） |
| `table_code` | String | 表代码 |
| `table_name` | String | 表中文名 |
| `operation_type` | String | 操作类型：SCHEDULED / MANUAL_INCREMENTAL / MANUAL_FULL |
| `status` | String | 状态：RUNNING / SUCCESS / FAILED / CANCELLED |
| `start_time` | String | 开始时间 |
| `end_time` | String | 结束时间 |
| `duration_ms` | Long | 耗时（毫秒） |
| `total_count` | Long | 处理总数 |
| `success_count` | Long | 成功数 |
| `fail_count` | Long | 失败数 |
| `error_message` | String | 错误信息摘要（脱敏后） |
| `error_stack` | String | 错误堆栈详情（脱敏后，仅管理员可见） |
| `operator` | String | 操作人：用户名 / SYSTEM（定时任务） |

**位置**：
- DO：`src/main/java/com/arthur/stock/model/DataPullLogDO.java`
- Mapper：`src/main/java/com/arthur/stock/mapper/DataPullLogMapper.java`

---

### 3.6 DataSourceHealthCache（数据源健康缓存）

内存缓存 Tushare 数据源的连通性检测结果。

**核心字段**：

| 字段 | 说明 |
|------|------|
| `sourceCode` | 数据源代码（TUSHARE） |
| `sourceName` | 数据源名称（Tushare Pro） |
| `status` | 状态：ACTIVE / INACTIVE / UNKNOWN |
| `lastTestTime` | 最后检测时间 |
| `lastTestOk` | 最后检测是否成功 |
| `responseTimeMs` | 响应时间（毫秒） |
| `testInterface` | 测试用接口（trade_cal） |

**位置**：`src/main/java/com/arthur/stock/cache/DataSourceHealthCache.java`

---

## 四、API 地图（DataGovernanceController）

**基础路径**：`/api/data-governance`

### 4.1 Overview 概览

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/overview` | 数据管控概览（总表数/正常表数/异常表数/最后检测时间） | 公开 |

### 4.2 Tables 表管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/tables` | 查询全部表状态（支持按分组/状态/关键字过滤） | 公开 |
| GET | `/tables/{tableCode}` | 查询单表详情（元信息/检测项/最新日期） | 公开 |
| GET | `/tables/{tableCode}/check-result` | 查询单表最新检测结果（所有检测项） | 公开 |
| GET | `/tables/{tableCode}/pull-history` | 查询单表拉取历史（最近30条） | 公开 |
| GET | `/tables/{tableCode}/check-history` | 查询单表检测历史（最近30条） | 公开 |

### 4.3 Update 数据更新

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/tables/{tableCode}/incremental-update` | 增量更新单表 | 管理员 |
| POST | `/tables/{tableCode}/full-rebuild` | 全量重建单表 | 管理员 |

### 4.4 Check 数据检测

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/check/all` | 异步检测全部表（返回taskId） | 管理员 |
| POST | `/check/{tableCode}` | 同步检测单表 | 管理员 |

### 4.5 Task 任务管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/tasks/{taskId}/progress` | 查询任务进度（运行中/成功/失败/已取消） | 公开 |
| POST | `/tasks/{taskId}/cancel` | 取消任务 | 管理员 |

### 4.6 Logs 拉取日志

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/logs` | 分页查询拉取日志（支持按表/状态/操作类型/时间过滤） | 公开 |
| GET | `/logs/{logId}` | 查询单条拉取日志详情 | 公开 |

### 4.7 Datasource 数据源

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/datasource` | 查询数据源状态 | 公开 |
| POST | `/datasource/test` | 手动测试数据源连通性 | 管理员 |

**位置**：`src/main/java/com/arthur/stock/controller/DataGovernanceController.java`

---

## 五、数据校验体系

### 5.1 检测结果数据结构

#### DataCheckResult（检测结果）

```java
public class DataCheckResult {
    private String tableCode;           // 表代码
    private String tableName;           // 表中文名
    private long totalRows;             // 总记录数
    private String latestDate;          // 最新数据日期（yyyyMMdd）
    private List&lt;DataCheckItem&gt; items;  // 所有检测项（含通过和不通过）
}
```

#### DataCheckItem（单项检测）

```java
public class DataCheckItem {
    private String name;          // 检测项标识（英文，如 "freshness"）
    private String displayName;   // 展示名称（中文，如 "新鲜度检测"）
    private boolean passed;       // 是否通过
    private CheckLevel level;     // 严重级别：ERROR / WARN
    private String message;       // 详细说明
}
```

---

### 5.2 通用检测项

所有表的检测都会自动叠加以下通用检测项（由 `DataGovernanceServiceImpl` 统一处理）：

| 检测项 | name | 级别 | 触发条件 |
|--------|------|------|---------|
| 空表检测 | `empty` | ERROR | 表记录数 = 0 |
| 行数变动检测 | `row_delta` | WARN | 记录数较上次检测减少超过 30%（且不是全量重建） |
| 数据延迟检测 | — | — | 日线表最新日期早于上一交易日（状态置为 DELAYED） |
| 校验器注册检测 | `checkable` | ERROR | 未找到对应 DataCheckable 实现 |
| 校验执行错误 | `check_error` | ERROR | checkData() 执行抛出异常 |

**行数变动检测豁免**：最近一次拉取日志的操作类型为 `MANUAL_FULL`（全量重建）时，行数大幅减少属正常现象，自动跳过 WARN。

---

### 5.3 各表自定义检测项

每个 Service 的 `checkData()` 方法可添加业务相关的自定义检测项。常见的自定义检测项包括：

| 检测类型 | 适用表 | 说明 |
|---------|--------|------|
| 价格逻辑检测 | 行情类 | open/high/low/close 之间的大小关系是否合理 |
| 成交量正检测 | 行情类 | volume 是否为正数 |
| 数据完整性 | 财务类 | 关键字段是否缺失 |
| 唯一性检测 | — | 主键/唯一约束是否有重复（一般由 DB 保证） |

**参考实现**：`DailyQuoteServiceImpl.checkData()` —— 日线行情的完整检测示例

---

### 5.4 检测结果存储

检测结果存储在 `data_governance_metric` 表中，保留 3 个月，由 `MetricCleanupJob` 自动清理过期记录。

#### DataGovernanceMetricDO 字段

| 字段 | 说明 |
|------|------|
| `id` | 自增主键 |
| `check_batch_id` | 检测批次ID（同一次全表检测共享一个batch_id） |
| `table_code` | 表代码 |
| `table_name` | 表中文名 |
| `table_group` | 表分组 |
| `total_rows` | 检测时总记录数 |
| `row_delta_pct` | 较上次检测的记录数变动百分比 |
| `latest_date` | 最新数据日期 |
| `earliest_date` | 最早数据日期（预留，当前为 null） |
| `status` | 表状态：NORMAL / DELAYED / ERROR |
| `check_items` | 所有检测项结果（JSON 字符串） |
| `check_time` | 检测执行时间 |
| `check_type` | 检测类型：SCHEDULED / MANUAL |

**位置**：`src/main/java/com/arthur/stock/model/DataGovernanceMetricDO.java`

---

## 六、增量与全量更新机制

### 6.1 增量更新流程

```
用户调用 /api/data-governance/tables/{code}/incremental-update
         │
         ▼
  ① 尝试获取全局任务锁
     ├─ 成功 → 继续
     └─ 失败 → 返回"有任务正在执行"
         │
         ▼
  ② 创建 DataPullLog（状态=RUNNING）
         │
         ▼
  ③ 生成 taskId，放入 TaskProgressCache
         │
         ▼
  ④ 异步提交到虚拟线程池执行
         │
         ▼
  ⑤ 调用对应 Service 的增量拉取方法
     （从最新日期+1 拉取到今天）
         │
         ▼
  ⑥ 更新 DataPullLog
     （状态=SUCCESS/FAILED，记录统计数据）
         │
         ▼
  ⑦ 更新 TaskProgressCache
     （状态=SUCCESS/FAILED）
         │
         ▼
  ⑧ 释放全局任务锁
```

---

### 6.2 全量重建流程

```
用户调用 /api/data-governance/tables/{code}/full-rebuild
         │
         ▼
  ① 尝试获取全局任务锁
         │
         ▼
  ② 创建 DataPullLog（状态=RUNNING，operation_type=MANUAL_FULL）
         │
         ▼
  ③ 生成 taskId，放入 TaskProgressCache
         │
         ▼
  ④ 异步提交到虚拟线程池执行
         │
         ▼
  ⑤ 清空目标表（DELETE 或 TRUNCATE）
         │
         ▼
  ⑥ 调用对应 Service 的全量拉取方法
     （从最早可用日期开始完整拉取）
         │
         ▼
  ⑦ 更新 DataPullLog
         │
         ▼
  ⑧ 更新 TaskProgressCache
         │
         ▼
  ⑨ 释放全局任务锁
```

---

### 6.3 并发控制

**两把独立的全局锁**：

| 锁 | 保护的操作 | 获取方法 |
|----|-----------|---------|
| 拉取任务锁 | 增量更新 / 全量重建 | `tryAcquireLock()` |
| 检测任务锁 | 全表数据质量检测 | `tryAcquireCheckLock()` |

**锁参数**：
- 超时时间：2 小时
- 实现：`AtomicReference` + 心跳时间戳
- 自动释放：锁超时后下一次尝试会强制释放并打 WARN 日志

**为什么两把锁分开**：数据质量检测是只读操作，可以与数据拉取并行执行（拉取过程中也可以检测）。

---

### 6.4 取消机制

```
用户 POST /tasks/{taskId}/cancel
         │
         ▼
  TaskProgressCache.setCancelled(taskId, true)
         │
         ▼
  任务线程在循环中检查 isCancelled(taskId)
         │
         ├─ 已取消 → 跳出循环，清理资源
         └─ 未取消 → 继续执行
```

**要点**：
- 取消是**协作式**的，任务需要在每个循环迭代中主动检查
- 取消后 DataPullLog 状态置为 `CANCELLED`
- TaskProgressCache 中的进度标记为取消状态

---

## 七、定时任务体系

### 7.1 数据治理相关定时任务

| 任务类 | 触发时间 | 职责 |
|--------|---------|------|
| `DataGovernanceCheckJob` | 每日 22:00 | 全表数据质量检测（同步执行，生成检测批次） |
| `MetricCleanupJob` | 每日凌晨 | 清理 3 个月前的检测历史记录 |
| `DataSourceHealthJob` | （可选）| 定时检测数据源连通性 |

### 7.2 数据更新类定时任务

| 任务类 | 触发频率 | 职责 |
|--------|---------|------|
| `DailyUpdateTask` | 每个交易日盘后 | 日线类数据批量更新 |
| `BasicDataTask` | 每日 | 基础数据更新（stock_basic 等） |
| `MoneyflowDataTask` | 每个交易日盘后 | 资金流向数据更新 |
| `IndexWeightTask` | 每日 | 指数成分权重更新 |
| `SwIndustryTask` | 每半年 | 申万行业分类更新 |
| `StockStkLimitTask` | 每个交易日 | 涨跌停价更新 |
| `StockSuspendDTask` | 每日 | 停复牌信息更新 |
| `StockNamechangeTask` | 每日 | 股票更名历史更新 |

**注意**：定时任务的更新操作也会写入 `data_pull_log`，操作类型为 `SCHEDULED`，操作人为 `SYSTEM`。

---

## 八、对接新表的 Checklist（数据治理部分）

新增 Tushare 接口时，数据治理相关的检查项：

- [ ] **InitStep 注册**：在 `InitStep` 枚举中添加新表，填写完整元数据（group/updateFrequency/isDaily/tushareApi）
- [ ] **实现 DataCheckable**：Service 类 `implements DataCheckable`，实现 `checkData()` 和 `getTableCode()`
- [ ] **自定义检测项**：在 `checkData()` 中添加业务相关的检测项（价格逻辑、数据完整性等）
- [ ] **DataInitService 接入**：在 `DataInitServiceImpl` 中注册增量更新和全量重建的执行逻辑
- [ ] **拉取日志**：更新操作中记录 `DataPullLog`（成功/失败/统计数据）
- [ ] **定时任务**：如需要定时更新，添加对应的 Task 类或在现有 Task 中追加

&gt; **完整对接步骤** → 见 [02-tushare-integration-guide.md](./02-tushare-integration-guide.md) 的 13 步流程

---

## 九、相关分册

- 新接口完整对接 13 步 → [02-tushare-integration-guide.md](./02-tushare-integration-guide.md)
- 接口清单与全局地图 → [03-tushare-interface-summary.md](./03-tushare-interface-summary.md)
- Tushare 官方接口参考 → [04-tushare-api-reference.md](./04-tushare-api-reference.md)
