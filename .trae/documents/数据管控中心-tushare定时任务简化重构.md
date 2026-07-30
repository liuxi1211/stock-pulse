# 数据管控中心 · tushare 定时任务简化重构计划

## 一、目标与动机

当前 stock-watcher 的「数据拉取定时更新」设计过度复杂：为了让执行时间可视化，引入了 `@ManagedTask` 注解 + 启动期反射扫描注册中心（`ScheduledTaskRegistryServiceImpl`）+ `InitStep.expectedUpdateTime` 启动一致性校验 + AOP 切面异步写日志 + 运行态 runningStatusMap 等一整套机制。维护成本高，且新增接口时要同时维护注解、枚举、校验三处。

本次简化按用户 6 条要求重塑：**用 Spring 原生 `@Scheduled` + 事件发布订阅 + data_pull_log 持久化 cron 字段**，让执行时间天然落在日志里，列表直接展示，无需额外的注册中心。

## 二、当前状态分析（基于 Phase 1 探索）

### 2.1 现有定时任务全景（task 包 12 个 @ManagedTask 类，约 20+ 个 @Scheduled 方法）

| 类 | 时间 | 职责 |
|---|---|---|
| [DailyUpdateTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/DailyUpdateTask.java) | 16:00 | 交易日历/股票基础/日线/复权/分红 |
| [BasicDataTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java) | 16:10 + 周日批量 | daily_basic/fina_indicator/收入/资产负债/现金流/forecast/express/十大股东/股东户数 |
| [MoneyflowDataTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/MoneyflowDataTask.java) | 16:10 | 资金流/港股通/龙虎榜/大宗/融资融券 |
| [IndexBasicTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/IndexBasicTask.java) | 16:25 | 指数基础 |
| [StockNamechangeTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/StockNamechangeTask.java) | 16:30 | 更名（增量+季度全量） |
| [StockSuspendDTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/StockSuspendDTask.java) | 16:35 | 停复牌（增量+月度全量） |
| [StockStkLimitTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/StockStkLimitTask.java) | 16:40 | 涨跌停（增量+月度全量） |
| [IndexDailyFetchService.dailySync](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/IndexDailyFetchService.java) | 16:30 MON-FRI | 指数日线 |
| [IndexWeightTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/IndexWeightTask.java) | 20:00 | 指数权重 |
| [SwIndustryTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/SwIndustryTask.java) | 半年 22:00 | 申万行业 |
| [DataVerifyTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/DataVerifyTask.java) | 22:00 | 数据校验补数 |
| [DataGovernanceCheckJob](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/DataGovernanceCheckJob.java) | 22:00 | 全表质量检测 |
| [MetricCleanupJob](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/MetricCleanupJob.java) | 01:00 | 清理旧日志 |
| [DataSourceHealthJob](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/DataSourceHealthJob.java) | 每小时 | 数据源连通性 |

### 2.2 现有 data_pull_log 写入路径
- **AOP 切面** [TaskExecutionLogAspect](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/aspect/TaskExecutionLogAspect.java)：拦截所有 `@ManagedTask` 方法，异步写日志（triggerType=SCHEDULED/MANUAL）。
- **DataInitServiceImpl** [createPullLog/finishPullLog](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java)：手动增量/全量入口同步写（不走 AOP）。

### 2.3 现有事件机制（已落地）
- [DataBatchReadyEvent](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/event/DataBatchReadyEvent.java)（tradeDate, source）
- 发布者：[DataBatchCompletionTracker](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/precompute/DataBatchCompletionTracker.java)（4 核心任务聚合 + 30min 超时兜底）、TestAdminController（手动）
- 订阅者：[PrecomputeEventDispatcher](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/event/PrecomputeEventDispatcher.java)、[FactorSnapshotTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/FactorSnapshotTask.java)、[ScreenLockTrackingTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/ScreenLockTrackingTask.java)

### 2.4 现有「执行时间可视化」基础设施（本次要拆/简化的重点）
- [ScheduledTaskRegistryServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/ScheduledTaskRegistryServiceImpl.java)：启动期扫描 `ScheduledTaskHolder` → 反射解包 → 缓存 `ScheduledTaskVO`，提供 list/getNextExecutionTime/runTask/fillRuntimeStatus。
- [InitStep](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)：表元数据枚举，含 `updateFrequency`（展示用）/`expectedUpdateTime`（启动校验用，与 cron 对比 WARN）。
- [@ManagedTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/annotation/ManagedTask.java) 注解：name/group/tableCode/description。
- [ScheduledTaskVO](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/governance/ScheduledTaskVO.java)。
- [DataGovernanceController](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/DataGovernanceController.java)：`/scheduled-tasks`、`/scheduled-tasks/{taskClass}`、`/scheduled-tasks/{taskClass}/run`、`/scheduled-tasks/{taskClass}/history`。

## 三、假设与决策（已在用户 6 条要求内锁定）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **拆分粒度**：仅对 tushare 数据拉取类任务按时间合并 | 非数据任务（MetricCleanup/DataVerify/GovernanceCheck/DataSourceHealth）保留独立，不受影响。合并后形成 5 个数据批次 task：`DailyDataFetchTask1600`、`DailyDataFetchTask1630`、`DailyDataFetchTask1640`、`IndexDataTask2000`、`SwIndustryTaskHalfYear`。其中 16:00/16:30 为核心批次（驱动 DataBatchReadyEvent）。 |
| D2 | **事件触发**：task 方法体只做 `applicationEventPublisher.publishEvent(...)` | task 不再持有具体业务依赖。16:00 与 16:30 两个核心批次 task 在执行完拉取后发布 `DataBatchReadyEvent`；其他批次 task 发布各自的 `DataBatchCompletedEvent`（按需）。 |
| D3 | **消费者位置**：新建 `event/listener/` 包集中存放 | 拉取逻辑（原 DailyUpdateTask/BasicDataTask/MoneyflowDataTask/IndexDailyFetchService 的实际拉取代码）下沉为 `service/datafetch/` 下的业务 Service，由 listener 调用。task 完全无业务依赖。 |
| D4 | **cron 字段**：data_pull_log 新增 `cron_expression` 列，AOP 切面解析 `@Scheduled` 注解写入 | 列表查询时直接展示该字段，无需再实时算。`InitStep` 不再参与时间展示。 |
| D5 | **data_pull_log 删字段**：移除 `task_name`/`task_class`/`method_name`/`task_group`/`trigger_type` 中「为动态注册中心服务」的冗余语义 | 实际上这些字段本身就是日志属性，不全是「定时任务配置」字段。保留 `task_class`（用于按任务查历史）、`trigger_type`（区分定时/手动）、`operation_type`、`status`、时间、计数、错误等。新增 `cron_expression`。`@ManagedTask` 注解移除后，taskName/tableCode/taskGroup 改为在切面里从方法签名或新建的轻量标记（如直接读方法名/参数）解析，或退化为常量。 |
| D6 | **InitStep 简化**：移除 `expectedUpdateTime` 字段 | `updateFrequency` 保留作纯展示文案（或一并移除，由列表的 cron_expression 替代）。倾向移除 `expectedUpdateTime`，`updateFrequency` 保留作为人类可读备注。 |
| D7 | **移除组件**：删除 `@ManagedTask` 注解、`ScheduledTaskRegistryServiceImpl/Service`、`ScheduledTaskVO`、`TriggerContext`、启动校验 `validateTasks`、`fillRuntimeStatus` 中的 runningStatusMap 机制 | 简化后不再需要「统一注册中心」。手动触发改为通过 task bean 反射调用 + AOP 记日志（triggerType=MANUAL），无需 TriggerContext（改用方法参数或新的轻量 ThreadLocal，或直接让手动入口走 DataInitService 路径）。 |
| D8 | **批次聚合 DataBatchCompletionTracker 保留** | 它是 16:00/16:30 两个核心批次完成后发 `DataBatchReadyEvent` 的关键解耦组件，符合「事件发布订阅」精神。其 4 个 EXPECTED_TASKS 调用方从「原 task finally」改为「新 listener 完成拉取后」调用 `reportCompletion`。 |

## 四、目标架构

```
Spring @Scheduled 触发
        │
        ▼
  XxxDataTask.yyyTime()           ← 只做 publishEvent，无业务依赖
        │ publishEvent(new DataFetchTriggerEvent(batchKey="1600", tradeDate, triggerType))
        ▼
  DataFetchTriggerEventListener   ← event/listener/ 包，@EventListener @Async
        │ 调用 service/datafetch/* 完成实际拉取
        │ finally: reportCompletion(...) → DataBatchCompletionTracker
        ▼
  (核心批次) DataBatchReadyEvent
        │
        ├─► PrecomputeEventDispatcher  (原 PrecomputeJob 并发)
        ├─► FactorSnapshotListener     (从 task 包迁到 event/listener/)
        └─► ScreenLockTrackingListener (从 task 包迁到 event/listener/)

AOP 切面（保留并简化）
  TaskExecutionLogAspect
     ├─ 解析被调方法 @Scheduled.cron → 写入 data_pull_log.cron_expression
     ├─ triggerType = SCHEDULED（定时入口）/ MANUAL（管理后台手动触发）
     └─ 异步 insert data_pull_log
```

**手动触发**：`DataGovernanceController` 的 `/scheduled-tasks/{taskClass}/run` 端点改为：取 task bean → 反射调用对应方法（AOP 仍会记日志，triggerType=MANUAL）。或更简单：手动入口直接走 `DataInitService.incrementalUpdate`（已有路径），删除 `/scheduled-tasks/*/run`。**决策：保留 run 端点，但实现改为反射调用 task 方法**，因为增量/全量是按表，run 是按任务，语义不同。

## 五、具体改动清单

### 5.1 数据库（schema-mysql.sql）
- [schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql) data_pull_log 表：
  - **新增列** `cron_expression VARCHAR(60) NULL COMMENT '触发本次执行的@Scheduled cron表达式（仅定时任务有值）'`（加到表 DDL + 末尾 ALTER 兼容已有库）。
  - **不删列**（向后兼容历史日志）：`task_name`/`task_class`/`method_name`/`task_group`/`trigger_type` 保留，但 `task_class` 改存新 task 类全限定名，其余字段在新写入时若拿不到则置 null。

### 5.2 实体与 Mapper
- [DataPullLogDO.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/model/DataPullLogDO.java)：新增 `private String cronExpression;`
- [DataPullLogMapper.xml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/mapper/DataPullLogMapper.xml)：`insert` 的列清单与 resultMap 加 `cron_expression`；`selectByTaskClass`/`selectLatestByTaskClass` 仍按 `task_class` 查（task_class 仍写入）。

### 5.3 InitStep 简化
- [InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)：
  - 移除字段 `expectedUpdateTime` 及其构造参数（影响 28 个枚举项的构造调用，需逐项删除第 6 个参数）。
  - 保留 `updateFrequency`（人类可读备注）。
  - 删除 [ScheduledTaskRegistryServiceImpl.validateTasks](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/ScheduledTaskRegistryServiceImpl.java#L208-L244) 中对 expectedUpdateTime 的引用。

### 5.4 移除「注册中心」整套机制
- **删除文件**：
  - [annotation/ManagedTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/annotation/ManagedTask.java)
  - [service/ScheduledTaskRegistryService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/ScheduledTaskRegistryService.java)
  - [service/impl/ScheduledTaskRegistryServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/ScheduledTaskRegistryServiceImpl.java)
  - [dto/governance/ScheduledTaskVO.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/governance/ScheduledTaskVO.java)
  - [util/TriggerContext.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/util/TriggerContext.java)（手动触发的"上下文传递"改用其他方式）
- **处理引用**：全局 grep `ManagedTask`/`ScheduledTaskRegistry`/`TriggerContext`/`ScheduledTaskVO` 的所有引用点，逐一改造。

### 5.5 task 包重组（D1/D2/D3）

**新建 5 个数据批次 task**（仅持 `ApplicationEventPublisher` 依赖）：

| 新 task 类 | @Scheduled cron | 发布事件 | 包含原逻辑（迁到 service 后） |
|---|---|---|---|
| `task/DailyDataFetchTask1600.java` | `0 0 16 * * MON-FRI` | DataFetchTriggerEvent("1600") | 交易日历/股票基础/日线/复权/分红 |
| `task/DailyDataFetchTask1630.java` | `0 30 16 * * MON-FRI` | DataFetchTriggerEvent("1630") | daily_basic/fina_indicator/财务三表/forecast/express/十大股东/股东户数/资金流/港股通/龙虎榜/大宗/融资融券/指数日线/更名 |
| `task/DailyDataFetchTask1640.java` | `0 40 16 * * MON-FRI` | DataFetchTriggerEvent("1640") | 停复牌/涨跌停 |
| `task/IndexDataTask2000.java` | `0 0 20 * * MON-FRI` | DataFetchTriggerEvent("2000") | 指数权重（+16:25 指数基础合并到这里或保留 16:25；决策：指数基础并入 16:00 批次，权重单独 20:00） |
| `task/SwIndustryTaskHalfYear.java` | 保留原 cron | DataFetchTriggerEvent("SW") | 申万行业 |

> **时间归并原则**：把原本 16:10 的 BasicData/Moneyflow、16:25 的 IndexBasic、16:30 的 Namechange/IndexDaily 全部并入 16:30 批次；16:35/16:40 并入 16:40 批次。后续新增 tushare 接口按「依赖的交易数据就绪时间」归入对应批次，或新建批次 task。

**每个 task 方法骨架**：
```java
@Component
public class DailyDataFetchTask1600 {
    private final ApplicationEventPublisher publisher;
    public DailyDataFetchTask1600(ApplicationEventPublisher p) { this.publisher = p; }

    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void fetch1600() {
        publisher.publishEvent(new DataFetchTriggerEvent("1600", TradeDateUtil.today(), TriggerType.SCHEDULED));
    }
}
```

**拉取逻辑下沉**：新建 `service/datafetch/` 包，把原 DailyUpdateTask/BasicDataTask/MoneyflowDataTask/IndexBasicTask/IndexDailyFetchService/StockNamechangeTask/StockSuspendDTask/StockStkLimitTask/IndexWeightTask/SwIndustryTask 的**实际拉取方法**抽成独立 Service（如 `DailyBasicFetchService`/`MoneyflowFetchService`/...）。这些 Service 方法由 listener 调用，不再带 @Scheduled。

### 5.6 事件与消费者（D3）

**新增事件类**（event 包）：
- `event/DataFetchTriggerEvent.java`：`batchKey`(String, "1600"/"1630"/"1640"/"2000"/"SW")、`tradeDate`、`triggerType`(SCHEDULED/MANUAL)、`operator`。

**新增 listener 包** `event/listener/`：
- `DataFetchTriggerEventListener.java`：核心分发器。`@EventListener` + `@Async("dataFetchExecutor")`，根据 `batchKey` switch 调用对应 fetch service 组合。在 1600/1630 批次的每个关键子任务 finally 里调 `batchCompletionTracker.reportCompletion(...)`。
- `FactorSnapshotListener.java`：从 [FactorSnapshotTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/FactorSnapshotTask.java) 迁移，订阅 `DataBatchReadyEvent`。
- `ScreenLockTrackingListener.java`：从 [ScreenLockTrackingTask](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/ScreenLockTrackingTask.java) 迁移，订阅 `DataBatchReadyEvent`。
- [PrecomputeEventDispatcher](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/event/PrecomputeEventDispatcher.java) 保持原位（已在 event 包）。

**保留** [DataBatchCompletionTracker](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/precompute/DataBatchCompletionTracker.java)：4 个 EXPECTED_TASKS 的 `reportCompletion` 调用方从原 task 改为新 listener 内的 fetch service 完成回调。

**删除原 task 文件**（被新 task + listener + fetch service 取代）：
- DailyUpdateTask / BasicDataTask / MoneyflowDataTask / IndexBasicTask / StockNamechangeTask / StockSuspendDTask / StockStkLimitTask / IndexWeightTask / SwIndustryTask / FactorSnapshotTask / ScreenLockTrackingTask（共 11 个）。

**保留原 task 文件**（非数据拉取，不动）：
- MetricCleanupJob / DataVerifyTask / DataGovernanceCheckJob / DataSourceHealthJob（但需移除其上的 `@ManagedTask` 注解，否则切面/注册中心找不到会报错）。

### 5.7 AOP 切面改造（D4/D5）
- [TaskExecutionLogAspect.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/aspect/TaskExecutionLogAspect.java)：
  - 切点从 `@annotation(managedTask)` 改为 `@Scheduled`（拦截所有 `@Scheduled` 方法），或自定义新注解 `@LoggedTask`（更可控，**推荐**，避免误伤 Spring 内部 scheduled）。
  - 解析被调方法的 `@Scheduled.cron()` 字符串 → 写入 `logDO.setCronExpression(cron)`。
  - `triggerType`：定时入口=SCHEDULED；手动入口（管理后台反射调用）=MANUAL——手动入口需在调用前设置 ThreadLocal（保留一个极简的 `TaskTriggerContext.setManual(operator)`，比原 TriggerContext 轻量，只存 operator+triggerType）。
  - `taskName`：退化用方法名或 batchKey。
  - 移除对 `runningStatusMap`（原 Registry 提供）的写入；RUNNING 状态改为查 data_pull_log 最新一条 status=RUNNING 的记录（DataGovernanceController 端实现）。

### 5.8 数据管控中心 Controller/列表（第5条）
- [DataGovernanceController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/DataGovernanceController.java)：
  - `/scheduled-tasks` 列表接口：原依赖 `ScheduledTaskRegistryService` 已删。改为查 `data_pull_log` 的「每个 task_class 最新一条记录」聚合，返回包含 `cron_expression`/`task_class`/`last_start_time`/`last_status` 等字段的列表 VO。新增 `DataPullLogMapper.selectLatestPerTaskClass()`（已有 `selectLatestByTaskClass(单)`，扩展为批量）。
  - `/scheduled-tasks/{taskClass}/run`：改为 `applicationContext.getBean(Class.forName(taskClass))` 反射调用对应方法；调用前 `TaskTriggerContext.setManual(operator)`。
  - `/scheduled-tasks/{taskClass}/history`：仍查 data_pull_log，按 task_class。
  - `/logs` 列表返回结果新增 `cronExpression` 字段（DTO 加字段）。
  - 新建 DTO `ScheduledTaskSummaryVO`（替代 ScheduledTaskVO）。

### 5.9 TestAdminController 适配
- [TestAdminController.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/controller/admin/TestAdminController.java) 的 `/trigger-task/{taskClass}`：改为反射调用 task 方法（同上）。

## 六、迁移与兼容

- **数据库**：新增列用 `ALTER TABLE data_pull_log ADD COLUMN cron_expression ...`（schema 末尾），已有库平滑升级；历史日志 cron_expression 为 NULL（列表展示"-"）。
- **代码**：本次为破坏性重构（删除注册中心），无渐进迁移；上线前需本地全量回归。
- **回滚**：git 回滚即可（无数据迁移不可逆操作）。

## 七、验证步骤

1. **编译**：`mvn -pl stock-watcher compile` 通过，无 `ManagedTask`/`ScheduledTaskRegistry`/`TriggerContext`/`ScheduledTaskVO` 残留引用。
2. **启动**：`mvn -pl stock-watcher spring-boot:run`，观察无启动校验报错；日志确认 5 个新 task bean 注册。
3. **定时触发**：手动改 cron 为近 1 分钟，验证：
   - 16:00/16:30 批次 task 触发 → publishEvent → listener 执行拉取 → data_pull_log 新增记录且 `cron_expression` 有值、`trigger_type=SCHEDULED`。
   - 核心批次完成后 `DataBatchReadyEvent` 发布，Precompute/FactorSnapshot/ScreenLock 三个订阅者被触发。
4. **列表接口**：`GET /api/data-governance/scheduled-tasks` 返回每条含 `cronExpression` 字段；`GET /api/data-governance/logs` 返回的记录含 `cronExpression`。
5. **手动触发**：`POST /api/data-governance/scheduled-tasks/{taskClass}/run` → 反射执行 → 日志 `trigger_type=MANUAL`。
6. **非数据任务回归**：MetricCleanupJob/DataVerifyTask/GovernanceCheckJob/DataSourceHealthJob 仍按原 cron 触发并写日志（无 @ManagedTask 后切面用 @LoggedTask 或 @Scheduled 拦截）。
7. **批次聚合**：4 个核心 EXPECTED_TASKS 全到齐或 30min 超时，DataBatchReadyEvent 正常发布（验证 Tracker 逻辑未坏）。

## 八、风险与注意

- **R1 拉取耗时**：16:30 批次合并了原 16:10/16:25/16:30 三批，单批次耗时变长。需确认 `@Async("dataFetchExecutor")` 线程池容量足够，且 DataBatchCompletionTracker 的 30min 超时仍合理。**缓解**：listener 内对独立的 fetch service 用 CompletableFuture 并发，控制总耗时。
- **R2 手动触发 operator 传递**：删 TriggerContext 后，手动入口的 operator 需经新轻量 ThreadLocal 传到 AOP 切面，且必须在 finally 清理。
- **R3 切点选择**：`@annotation(LoggedTask)` 比 `@Scheduled` 更精确，避免误拦截；但要求所有需记日志的 task 方法都加 `@LoggedTask`。**推荐**用 `@LoggedTask`。
- **R4 InitStep 构造改动面大**：28 个枚举项删第 6 参数，易出错。需逐项核对编译。

## 九、实施顺序（建议）

1. DB schema + DataPullLogDO + Mapper（cron_expression）
2. InitStep 删 expectedUpdateTime（编译通过）
3. 新建 service/datafetch/ 抽取拉取逻辑（原 task 方法体平移）
4. 新建 DataFetchTriggerEvent + event/listener/ 包
5. 新建 5 个数据批次 task + @LoggedTask 注解
6. 改造 TaskExecutionLogAspect（切点 + cron 解析 + 新 ThreadLocal）
7. 删除 @ManagedTask/ScheduledTaskRegistry*/ScheduledTaskVO/TriggerContext
8. 改造 DataGovernanceController（列表查 log 聚合 + run 反射）
9. 迁移 FactorSnapshotTask/ScreenLockTrackingTask → listener
10. 适配非数据 task（移除 @ManagedTask，加 @LoggedTask）
11. 全量回归验证
