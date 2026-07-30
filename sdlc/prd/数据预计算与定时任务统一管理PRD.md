# 数据预计算与定时任务统一管理 PRD

| 字段 | 内容 |
|---|---|
| 文档版本 | v2.2 |
| 创建日期 | 2026-07-29 |
| 作者 | 系统架构组 |
| 状态 | Reviewed（已按评审意见修订 P0/P1 阻塞项 + 关键一致性问题） |
| 关联文档 | [数据预计算架构设计](../../.trae/documents/数据预计算架构设计.md) |

> **v2.2 修订记录**（v2.1 一致性问题闭环）：
> 1. 修复 6.1 接口路径参数 `taskName` → `taskClass` 不一致（原 v2.1 修订记录第 11 条未同步到 6.1/7.3 章节）—— 6.1 端点路径、6.2 内部接口 `runTask` 签名、7.3 交互流程图、5.1 查询条件全部统一为 `taskClass`（§6.1/5.1/7.3）
> 2. 手动重跑 `trigger_type` 区分机制补全：新增 `TriggerContext`(ThreadLocal) 设计，AOP 切面入口读取 ThreadLocal 区分 SCHEDULED vs MANUAL + operator，无需 task 类特殊处理（§6.2 + §模块A）
> 3. RUNNING 状态实时感知补全：AOP 切面维护 `ConcurrentHashMap<taskClass, RunningStatus>` 内存表，`ScheduledTaskRegistryService` 读此 Map 填充 `currentStatus` + 实时 `lastDurationMs`（§模块A）
> 4. `runTask` RUNNING 拒绝语义补全：拒绝时抛 `IllegalStateException` + HTTP 409 Conflict（§6.2）
> 5. 历史接口分页参数补全：新增 `page` / `status` / `startDate` 查询参数，返回 `PageResult` 含 `records`/`total`/`page`/`pageSize`（§6.1）
> 6. 5.1 查询区分修正：从 `WHERE task_name = ?` 改为 `WHERE task_class = ? AND task_name IS NOT NULL`，路径参数与查询条件对齐，避免 task_name 重命名导致查询失效（§5.1）
> 7. 11.4 扩展指南修正：taskKey 格式从 `"TaskName"` 改为 `"类名.方法名"`，与 `EXPECTED_TASKS` 严格对齐；新增 EXPECTED_TASKS 维护约束（新增拉取任务默认不进 EXPECTED_TASKS，避免破坏批次判定）（§11.4）
> 8. 验收项编号重排：原 11-22 项顺延为 18-25，新增 11/12/14/15/16/17 共 6 项细化验收（手动重跑 MANUAL 区分、分页、状态/日期筛选、RUNNING 实时刷新）（§8.7）

> **v2.1 修订记录**（评审问题处理）：
> 1. 权限定义统一为"登录可访问"，`task_class`/`method_name` 不在公开 API 返回（§模块F、§4.5）
> 2. 缓存 key 双写：Job 同时 put `{tradeDate}` 和 `latest` 两个 key，解决预计算与查询 key 不一致（§模块C/D）
> 3. Job 失败时主动 evict 缓存，避免 24h TTL 内用户看到昨日数据（§模块C）
> 4. `tradeDate` 捕获时机约束 + 非交易日/跨日/补数场景预期行为定义（§模块B）
> 5. 异常报告完成时事件 `source` 字段区分 + 预计算 Job 数据完整性校验（§模块B/C）
> 6. `DataBatchCompletionTracker` 增加 `fired` 标志位，防止超时兜底后迟到报告重复发布事件（§模块B）
> 7. `EXPECTED_TASKS` 改为 `类名.方法名`，消除 BasicDataTask 7 个方法的歧义（§模块B）
> 8. Spring 调度器显式配置多线程（pool.size≥4）+ Dispatcher 并发提交 7 Job（§模块C + §新增配置）
> 9. `@Cacheable` 自调用约束 + `table_code` 启动 fail-fast 校验（§模块A/D）
> 10. 新增手动重跑接口 + 测试触发入口 + 任务失败告警 + RUNNING 状态（§模块A/F）
> 11. 路径参数 `taskName` → `taskClass`（稳定且唯一，无编码问题）（§模块F）
> 12. 验收标准补充 18 项（清理手动入口/非交易日/跨日/并发/懒兜底全 Job/AOP 性能等）（§八）
> 13. 前端补充三态（loading/空/错误）+ 状态筛选 + 历史分页 + cron 可读化 + errorMessage 脱敏规则（§模块F、§4.5）

---

## 一、背景与目标

### 1.1 背景

stock-pulse 项目当前存在三个突出的架构问题：

**问题 1：实时聚合 SQL 性能瓶颈**

板块行情、市场总览等接口每次请求都执行实时聚合查询。典型如 `SwIndustryServiceImpl.getIndustryRanking`，每次请求执行：
- 一次 5000 参数的 `IN` 查询 `daily_quote` 表
- 28 次循环查询 `index_daily` 表
- 一次全量查询 `stock_basic` 表（5000+ 行）

虽然 Caffeine 缓存有 30 分钟 TTL 兜底，但首屏访问或缓存过期后仍会触发上述重计算。而这些数据在每日 16:00 日线行情更新后就不再变化。

**问题 2：定时任务分散难以管理**

项目当前有 21 处拉取 Tushare 数据的 `@Scheduled` 方法 + 4 处非拉取类维护/检测 `@Scheduled` 方法 + 2 处原 cron 触发的预计算/追踪任务，cron 表达式硬编码在注解中：
- 无法在统一界面查看所有任务的执行计划、cron、上次/下次执行时间
- `InitStep.updateFrequency` 是人工编写的展示字符串，与 `@Scheduled` 注解无运行时绑定，易出现"展示与实际不一致"
- 任务间依赖完全靠 cron 时序错峰，前序任务延迟会导致后续任务读到不完整数据
- `@Scheduled` 任务执行不留痕（除日志文件），失败后只能翻日志排查

**问题 3：多表依赖预计算的触发时序问题**

原设计（按表粒度发事件触发预计算）会导致多表依赖的预计算 Job（如 `SectorRankingPrecomputeJob` 依赖 `daily_quote + index_daily + sw_industry_member + stock_basic`）在某张表刚更新完就跑，从而用到其他表的旧数据，结果错误。需要把触发点从"单表更新完成"改为"整批数据更新完成"。

### 1.2 目标

**目标 1：建立批次完成事件驱动的预计算框架**
- 4 个数据更新任务全部完成后，由 `DataBatchCompletionTracker` 发布统一的 `DataBatchReadyEvent`，预计算订阅事件自动执行
- 把"每日数据更新后就不变"的聚合查询结果预先算好塞进 Caffeine
- 查询时若缓存未命中，自动懒兜底计算

**目标 2：把拉取类定时任务管理整合到数据管控中心**
- **仅覆盖拉取 Tushare 接口数据的 21 个定时任务**（有 `tableCode` 关联的），其他 4 个非拉取类任务不调整
- 拉取类 `@Scheduled` 方法通过 `@ManagedTask` 注解自动注册元信息
- 运行时反射 Spring `ScheduledTaskHolder` 获取真实 cron + 计算下次执行时间
- AOP 切面自动记录每次执行到**复用的 `data_pull_log` 表**（新增 5 个可空字段，不新建表）
- 数据管控中心新增"定时任务"分区，支持查询任务列表/执行历史

**目标 3：现有预计算/追踪任务事件驱动改造**
- `FactorSnapshotTask` 和 `ScreenLockTrackingTask` 两个原本依赖 cron 时序错峰的任务，改为订阅 `DataBatchReadyEvent`，消除"前序任务延迟导致读到不完整数据"的隐患
- 这两个任务不加 `@ManagedTask` 注解（本次只管理 21 个拉取类任务），其执行记录靠自身日志输出

**目标 4：可扩展架构**
- 新增预计算接口：实现 `PrecomputeJob` 接口 + `@Component` 即可自动接入
- 新增数据更新任务：加 `@ManagedTask` 注解 + 末尾向 `DataBatchCompletionTracker` 报告完成即可自动接入管理与批次事件链
- 无需修改框架代码

### 1.3 不在本次范围

- **非拉取类定时任务的管理**：`MetricCleanupJob` / `DataSourceHealthJob` / `DataGovernanceCheckJob` / `DataVerifyTask` 这 4 个非拉取类任务**不加 `@ManagedTask` 注解、不纳入统一管理、不记录执行日志**。本次只覆盖拉取 Tushare 数据的 21 个定时任务
- 持久化预计算表（`factor_snapshot` 模式）：本 PRD 仅做内存预热；`FactorSnapshotTask` 自身的持久化逻辑保持不变，只改触发方式
- 任务编排框架（DAG 依赖图自动调度）：本 PRD 保持 cron 触发 + 批次完成事件弱依赖
- 任务启用/禁用开关：`ScheduledTaskVO.enabled` 字段预留，但 UI 不实现开关
- 选股 ScreenerServiceImpl 预计算：已有 `ScreenerResultCache`（24h TTL），参数是动态 JSON 无法预先知道
- 分页接口预计算（`getIndustryMembers` / `getStockList`）：参数动态，缓存粒度太细

---

## 二、用户故事

### US-1：板块行情首屏零延迟（普通用户）
> 作为访问「板块行情」页面的普通用户，我希望每日首次访问时响应时间在 50ms 以内，而不是等待 5000 只股票的实时聚合，这样我有流畅的浏览体验。

### US-2：定时任务可视化（运维/开发）
> 作为运维或开发人员，我希望在数据管控中心看到所有 21 个被管理的拉取类定时任务的清单，包含 cron、上次执行时间、上次状态、下次执行时间，这样我能快速判断任务是否正常运行，无需翻日志文件。

### US-3：任务失败可追溯（运维）
> 作为运维人员，当某定时任务失败时，我希望在数据管控中心查看该任务的执行历史（最近 30 条），看到失败时间和错误信息，这样我能快速定位问题。

### US-4：批次完成后自动预计算（系统）
> 作为系统，当 `DailyUpdateTask` / `BasicDataTask` / `MoneyflowDataTask` / `IndexDailyFetchService` 这 4 个数据更新任务全部完成后，我希望由 `DataBatchCompletionTracker` 自动发布 `DataBatchReadyEvent`，触发 7 个预计算 Job + `FactorSnapshotTask` + `ScreenLockTrackingTask` 自动执行，把结果塞进 Caffeine 或写入持久化表，这样用户首屏访问零延迟，且不再依赖 cron 时序错峰。

### US-5：缓存未命中自动兜底（系统）
> 作为系统，当应用重启或预计算失败导致 Caffeine 缓存为空时，我希望查询接口能自动同步计算并写入缓存，这样用户仍能拿到正确数据（虽然首次稍慢）。

### US-6：新增定时任务零成本（开发）
> 作为开发人员，当我新增一个拉取类定时任务时，我希望只需在方法上加 `@ManagedTask` 注解 + 末尾向 `DataBatchCompletionTracker` 报告完成，就能自动接入任务管理和批次事件链，无需修改框架代码。

### US-7：cron 与展示一致性保证（开发）
> 作为开发人员，当我修改某任务的 cron 表达式但忘记同步 `InitStep.expectedUpdateTime` 时，我希望应用启动时打 WARN 提醒，避免前端展示与实际调度脱钩。

---

## 三、功能需求

### 模块 A：定时任务统一管理

#### Requirement: ManagedTask 注解定义
系统 SHALL 提供 `@ManagedTask` 注解，用于声明 `@Scheduled` 方法的元信息。

- **注解目标**：方法级别
- **保留策略**：RUNTIME
- **必填属性**：`name`（任务名称）、`group`（分组）
- **可选属性**：`tableCode`（默认空字符串）、`description`（默认空字符串）
- **group 取值枚举**：`DATA_FETCH` / `GOVERNANCE` / `MAINTENANCE` / `PRECOMPUTE` / `VERIFY`

#### Scenario: 给现有任务加注解
- **WHEN** 开发者在 `DailyUpdateTask.dailyUpdate` 方法上加 `@ManagedTask(name="每日数据更新", tableCode="daily", group="DATA_FETCH", description="同步交易日历/股票基础/日线行情/复权因子/分红")`
- **THEN** 应用启动时该任务被 `ScheduledTaskRegistryService` 自动注册
- **AND** 数据管控中心 `/api/data-governance/scheduled-tasks` 接口能查询到该任务

#### Requirement: ScheduledTaskRegistryService 任务元信息解析
系统 SHALL 提供 `ScheduledTaskRegistryService`，在应用启动时通过 Spring `ScheduledTaskHolder` 解析所有已注册的 `@Scheduled` 任务，并关联方法上的 `@ManagedTask` 注解。

- **解析时机**：`@PostConstruct`（应用启动时）
- **解析内容**：taskClass / methodName / cron / tableCode / name / description / group
- **下次执行时间计算**：用 `CronExpression.parse(cron).next(now)` 计算
- **降级**：若 `ScheduledTaskHolder` 反射失败，try-catch + WARN 日志，返回空列表不阻断启动
- **启动校验**（评审修正）：
  - **tableCode 非空校验**：所有 `@ManagedTask` 方法的 `tableCode` 必须非空（`data_pull_log.table_code` NOT NULL），否则启动 fail-fast（AOP 切面异步写库会因 NOT NULL 约束抛 SQLException，日志丢失）
  - **name 唯一性校验**：`@ManagedTask.name` 必须全局唯一（前端路径参数 + 历史查询依赖唯一标识），重名 fail-fast
  - **cron 与 InitStep 一致性校验**：不匹配打 WARN 不阻断（见下条 Requirement）

#### Scenario: 启动时解析所有任务
- **WHEN** 应用启动完成
- **THEN** `ScheduledTaskRegistryService` 已解析全部 21 个被管理的 `@Scheduled` 任务
- **AND** 日志输出 `[TaskRegistry] 已注册 21 个定时任务`
- **AND** 每个任务的 `nextExecutionTime` 字段已计算

#### Scenario: 反射失败降级
- **WHEN** Spring `ScheduledTaskHolder` 反射异常
- **THEN** 打 WARN 日志 `[TaskRegistry] 解析任务失败：{error}`
- **AND** `listScheduledTasks()` 返回空列表
- **AND** 应用正常启动，不抛异常

#### Requirement: InitStep 一致性校验
系统 SHALL 在 `ScheduledTaskRegistryService` 初始化时，对每个带 `tableCode` 的 `@ManagedTask` 任务，校验其 cron 与 `InitStep.fromCode(tableCode).expectedUpdateTime` 是否匹配。

- **校验时机**：`@PostConstruct` 阶段，所有任务解析完成后
- **校验规则**：cron 解析出的执行时间（如 `16:00`）应与 `InitStep.expectedUpdateTime`（如 `"16:00"`）一致
- **不匹配处理**：打 WARN 日志，不阻断启动
- **WARN 格式**：`[TaskRegistry] 任务 {taskName} 的 cron {cron} 与 InitStep.{tableCode}.expectedUpdateTime={expectedTime} 不一致，请同步更新`

#### Scenario: cron 与 InitStep 一致
- **WHEN** `DailyUpdateTask.dailyUpdate` 的 cron 是 `0 0 16 * * ?`，`InitStep.DAILY.expectedUpdateTime` 是 `"16:00"`
- **THEN** 校验通过，不打 WARN

#### Scenario: cron 与 InitStep 不一致
- **WHEN** 开发者把 `DailyUpdateTask.dailyUpdate` 的 cron 改为 `0 30 16 * * ?`，但未同步 `InitStep.DAILY.expectedUpdateTime`
- **THEN** 应用启动时打 WARN：`[TaskRegistry] 任务 每日数据更新 的 cron 0 30 16 * * ? 与 InitStep.daily.expectedUpdateTime=16:00 不一致，请同步更新`

#### Requirement: TaskExecutionLogAspect 任务执行切面
系统 SHALL 提供 `TaskExecutionLogAspect` AOP 切面，拦截所有标注 `@ManagedTask` 的方法，自动记录执行日志到 `data_pull_log` 表（复用现有表，新增的 5 个可空字段填充任务元信息）。

- **切点**：所有标注 `@ManagedTask` 的方法（本次仅 21 个拉取类任务）
- **通知类型**：`@Around` 环绕通知
- **触发来源感知**（评审修正）：切面入口读取 `TriggerContext.get()`（ThreadLocal）：
  - ThreadLocal 为空 → 定时调度触发 → `trigger_type=SCHEDULED` / `operator=SYSTEM`
  - ThreadLocal 有值 → 手动重跑触发 → `trigger_type=MANUAL` / `operator=TriggerContext.operator()`
- **记录字段**：
  - 共用字段：`task_id`(UUID) / `table_code` / `table_name` / `operation_type`="SCHEDULED" / `status` / `start_time` / `end_time` / `duration_ms` / `total_count`=0 / `success_count`=0 / `fail_count`=0 / `error_message` / `operator`（按上述规则）
  - 新增字段：`task_name` / `task_class` / `method_name` / `task_group`="DATA_FETCH" / `trigger_type`（按上述规则）
- **异步写入**：日志写入操作异步执行，不阻塞主流程
- **异常处理**：原方法抛异常时记录 `status=FAILED` + `error_message`（截断 2000 字符），然后重新抛出原异常
- **日志写入容错**（评审修正）：AOP 切面内的日志写入操作必须 try-catch 包住，写入失败（如 DB 异常、字段超长）只打 ERROR 日志，**不影响原方法返回值和异常**。`error_message` 截断至 1024 字符（匹配 VARCHAR(1024)），`error_stack` 截断至 8192 字符
- **RUNNING 状态实时感知**（评审新增）：切面入口在内存 `ConcurrentHashMap<String taskClass, RunningStatus>` 中 set 当前 startTime，切面出口（finally）clear。`ScheduledTaskRegistryService` 查询列表时读此 Map 填充 `currentStatus` + 实时 `lastDurationMs`（now - startTime），供前端轮询展示 RUNNING 状态
- **不覆盖范围**：`FactorSnapshotTask` / `ScreenLockTrackingTask` 不加 `@ManagedTask`，其执行不被此切面记录（详见模块 G）

#### Scenario: 任务正常执行
- **WHEN** `DailyUpdateTask.dailyUpdate` 正常执行完毕（耗时 12 秒）
- **THEN** `data_pull_log` 表新增一条记录：`status=SUCCESS`, `duration_ms=12000`, `trigger_type=SCHEDULED`, `task_name=每日数据更新`, `operation_type=SCHEDULED`
- **AND** 任务主流程不被阻塞

#### Scenario: 任务执行失败
- **WHEN** `DailyUpdateTask.dailyUpdate` 抛出 RuntimeException
- **THEN** `data_pull_log` 表新增一条记录：`status=FAILED`, `error_message` 包含异常消息（最多 2000 字符）
- **AND** 原异常被重新抛出，不影响任务本身的错误处理

#### Requirement: data_pull_log 表自动清理（无需改动）
现有 `MetricCleanupJob` 已在每日凌晨 01:00 清理 `data_pull_log` 表中 3 个月前的记录。本次复用该表后，定时任务执行日志与手动拉取日志一同被清理，**无需改动清理逻辑**。

- **清理时机**：每日 01:00（现有逻辑）
- **保留期**：3 个月
- **影响**：定时任务执行记录（`task_name IS NOT NULL`）和手动拉取记录（`task_name IS NULL`）一同被清理

#### Scenario: 凌晨自动清理
- **WHEN** `MetricCleanupJob.cleanupOldData` 在 01:00 执行
- **THEN** `data_pull_log` 表中 `start_time` 早于 3 个月前的记录被删除（含定时任务和手动拉取两类记录）
- **AND** 清理逻辑无需任何改动

### 模块 B：数据批次完成事件机制

#### Requirement: DataBatchReadyEvent 事件定义
系统 SHALL 提供 `DataBatchReadyEvent`，继承 Spring `ApplicationEvent`，表示"当日所有数据拉取批次已完成"。

```java
public class DataBatchReadyEvent extends ApplicationEvent {
    private final String tradeDate;     // 交易日 yyyyMMdd
    private final String source;        // SCHEDULED / MANUAL

    public DataBatchReadyEvent(Object source, String tradeDate, String source) {
        super(source);
        this.tradeDate = tradeDate;
        this.source = source;
    }
    // getters...
}
```

- **字段**：
  - `tradeDate`（String）：交易日 yyyyMMdd
  - `source`（String）：触发来源 `SCHEDULED` / `SCHEDULED_TIMEOUT` / `SCHEDULED_PARTIAL` / `MANUAL`
    - `SCHEDULED`：4 个任务正常报告完成
    - `SCHEDULED_TIMEOUT`：30 分钟超时兜底强制发布（数据可能不完整）
    - `SCHEDULED_PARTIAL`：4 个任务全部报告完成，但其中部分任务异常退出（finally 块报告）——数据可能不完整
    - `MANUAL`：手动触发
- **语义**：事件表示"该 tradeDate 的 4 个数据更新任务流程已结束"，**不携带表级粒度信息**，预计算 Job 收到事件时可以认为 4 张相关源表的当日数据流程已结束（但**不保证所有数据都更新成功**，Job 需做数据完整性校验）
- **废弃事件**：原 `DataUpdatedEvent(tableCode, tradeDate, source)` 设计已废弃，不再使用

#### Requirement: DataBatchCompletionTracker 批次完成追踪器
系统 SHALL 提供 `DataBatchCompletionTracker`（`@Component`），追踪 4 个数据更新任务的完成状态，全部完成后发布 `DataBatchReadyEvent`。

```java
@Component
@RequiredArgsConstructor
public class DataBatchCompletionTracker {
    private final ApplicationEventPublisher eventPublisher;
    // key=tradeDate, value=BatchEntry(已完成任务集合 + 是否有异常 + 是否已发布事件)
    private final ConcurrentHashMap<String, BatchEntry> completionMap = new ConcurrentHashMap<>();
    private static final Set<String> EXPECTED_TASKS = Set.of(
        "DailyUpdateTask.dailyUpdate",
        "BasicDataTask.fetchDailyBasic",
        "MoneyflowDataTask.fetchDailyMoneyflowData",
        "IndexDailyFetchService.dailySync"
    );

    // 任务正常完成
    public void reportCompletion(String taskKey, String tradeDate) {
        reportCompletion(taskKey, tradeDate, false);
    }

    // 任务异常完成（finally 块调用），hasError=true
    public void reportCompletion(String taskKey, String tradeDate, boolean hasError) {
        BatchEntry entry = completionMap.computeIfAbsent(tradeDate, k -> new BatchEntry());
        entry.completedTasks.add(taskKey);
        if (hasError) entry.hasError = true;
        if (!entry.fired && entry.completedTasks.containsAll(EXPECTED_TASKS)) {
            entry.fired = true;  // 标记已发布，防止超时兜底后迟到报告重复发布
            String source = entry.hasError ? "SCHEDULED_PARTIAL" : "SCHEDULED";
            eventPublisher.publishEvent(new DataBatchReadyEvent(this, tradeDate, source));
            completionMap.remove(tradeDate);  // 清理，避免内存泄漏
            log.info("[BatchTracker] tradeDate={} 收齐 4 个任务报告，发布 DataBatchReadyEvent(source={})", tradeDate, source);
        }
    }

    // 超时兜底调用
    public void forceFireOnTimeout(String tradeDate, Set<String> missingTasks) {
        BatchEntry entry = completionMap.get(tradeDate);
        if (entry != null && !entry.fired) {
            entry.fired = true;
            eventPublisher.publishEvent(new DataBatchReadyEvent(this, tradeDate, "SCHEDULED_TIMEOUT"));
            completionMap.remove(tradeDate);
            log.warn("[BatchTracker] tradeDate={} 超时强制发布（缺失任务：{}）", tradeDate, missingTasks);
        }
    }

    private static class BatchEntry {
        Set<String> completedTasks = ConcurrentHashMap.newKeySet();
        volatile boolean hasError = false;
        volatile boolean fired = false;
    }
}
```

- **追踪目标**：4 个数据更新任务的具体方法（`类名.方法名` 粒度）
- **taskKey 格式**：`类名.方法名`（如 `BasicDataTask.fetchDailyBasic`），与 `EXPECTED_TASKS` 严格对齐，消除 BasicDataTask 7 个 @Scheduled 方法的歧义
- **去重**：每个 tradeDate 一个批次，重复报告同一 taskKey 不会重复计数（Set 去重）
- **防重复发布**（评审修正）：entry 加 `fired` 标志位，事件发布后置 true；超时兜底和迟到报告都检查 `fired`，已发布则丢弃，避免重复触发 Job
- **异常感知**（评审修正）：`reportCompletion` 重载方法接收 `hasError`，4 个任务都报告完成但其中部分异常时，事件 source 标记为 `SCHEDULED_PARTIAL`，预计算 Job 据此加强数据完整性校验
- **批次发布**：4 个任务都报告完成且未 fired 时发布事件，并清理该 tradeDate 的 entry
- **线程安全**：使用 `ConcurrentHashMap` + `ConcurrentHashMap.newKeySet()`，支持 4 个任务并行报告

#### Scenario: 4 个任务依次报告，第 4 个触发事件
- **WHEN** `DailyUpdateTask` 报告完成（tradeDate=20260729）
- **AND** `BasicDataTask` 报告完成
- **AND** `MoneyflowDataTask` 报告完成
- **AND** `IndexDailyFetchService` 报告完成
- **THEN** 第 4 个 `reportCompletion` 调用触发发布 `DataBatchReadyEvent(tradeDate="20260729", source="SCHEDULED")`
- **AND** `completionMap` 中 `20260729` 的 entry 被清理
- **AND** 打 INFO 日志 `[BatchTracker] tradeDate=20260729 收齐 4 个任务报告，发布 DataBatchReadyEvent`

#### Scenario: 重复报告同一任务不重复计数
- **WHEN** `DailyUpdateTask` 对同一 tradeDate 多次调 `reportCompletion`
- **THEN** Set 中只计一次
- **AND** 不会重复发布 `DataBatchReadyEvent`

#### Requirement: 4 个数据更新任务改造
系统 SHALL 改造 4 个数据更新任务，每个任务方法末尾（finally 块中）调 `batchCompletionTracker.reportCompletion`，并移除原 `@CacheEvict` 注解。

| Task 类.方法 | 报告的 taskKey | 原 @CacheEvict |
|---|---|---|
| `DailyUpdateTask.dailyUpdate` | `DailyUpdateTask.dailyUpdate` | `sectorRanking, sectorMoneyflow, sectorValuation` |
| `BasicDataTask.fetchDailyBasic` | `BasicDataTask.fetchDailyBasic` | `sectorValuation` |
| `MoneyflowDataTask.fetchDailyMoneyflowData` | `MoneyflowDataTask.fetchDailyMoneyflowData` | `sectorMoneyflow` |
| `IndexDailyFetchService.dailySync` | `IndexDailyFetchService.dailySync` | `sectorRanking` |

- **依赖注入**：每个 task 类构造器追加 `DataBatchCompletionTracker`
- **tradeDate 捕获时机**（评审修正）：每个任务在方法入口立即捕获 `String tradeDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);`，finally 块用此已捕获变量（避免 finally 内重新取值漂移；统一用 Asia/Shanghai 时区）
- **报告时机**：任务方法 finally 块中调 `reportCompletion(taskKey, tradeDate, hasError)`，`hasError` 标记本次执行是否有异常（确保即使失败也报告完成，不阻塞批次）
- **不再发布**：不再发布任何按表粒度的事件（原 `DataUpdatedEvent` 设计已废弃）
- **不再清缓存**：原 `@CacheEvict` 注解移除，由预计算 Job 主动 put 覆盖旧值
- **非交易日/节假日处理**：4 个任务 cron 触发时若 `trade_cal` 表判定为非交易日，任务方法应早返回（不拉数），finally 仍调 `reportCompletion(taskKey, tradeDate, false)`——预计算 Job 收到 `SCHEDULED` 事件后会执行，`computeXXX` 对空数据返回空 List（`@Cacheable unless` 不缓存空结果，避免脏缓存）

```java
// DailyUpdateTask.dailyUpdate 改造示例
public void dailyUpdate() {
    String tradeDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);
    boolean hasError = false;
    try {
        // 5 步数据拉取（每步 try-catch，单步失败不阻塞后续）
        updateTradeCalendar(tradeDate);
        updateStockBasic();
        updateDailyQuotes(tradeDate);
        updateAdjFactor(tradeDate);
        updateDividend(tradeDate);
    } catch (Exception e) {
        hasError = true;
        log.error("[DailyUpdateTask] tradeDate={} 执行失败", tradeDate, e);
    } finally {
        batchCompletionTracker.reportCompletion("DailyUpdateTask.dailyUpdate", tradeDate, hasError);
    }
}
```

#### Scenario: 任务正常完成报告
- **WHEN** `DailyUpdateTask.dailyUpdate` 5 个步骤全部执行完毕
- **THEN** finally 块调 `batchCompletionTracker.reportCompletion("DailyUpdateTask", "20260729")`
- **AND** 原 `@CacheEvict(value = {"sectorRanking", "sectorMoneyflow", "sectorValuation"}, allEntries = true)` 注解已移除

#### Scenario: 单步失败仍报告完成
- **WHEN** `DailyUpdateTask.dailyUpdate` 中 `updateDailyQuotes` 步骤抛异常
- **THEN** 该步骤被 try-catch 捕获，后续步骤继续执行
- **AND** finally 块仍调 `reportCompletion`（不阻塞批次）
- **AND** 预计算 Job 内部对空数据兜底

#### Requirement: 超时兜底（P1，可选）
系统 SHOULD 在 30 分钟内未收齐 4 个任务完成报告时，强制发布 `DataBatchReadyEvent`，避免某任务卡死导致整批不触发。

- **实现方式**：`@Scheduled` 定时检查 `completionMap` 中超过 30 分钟未收齐的 tradeDate entry
- **触发条件**：某 tradeDate 的 entry 创建超过 30 分钟，但未收齐 4 个任务报告
- **行为**：调 `forceFireOnTimeout(tradeDate, missingTasks)`，发布 `DataBatchReadyEvent(tradeDate, "SCHEDULED_TIMEOUT")`，并清理 entry；`fired` 标志置 true 防止迟到报告重复发布
- **告警**：打 WARN 日志，列出未报告的任务名
- **非交易日场景**：非交易日 4 任务若 cron 仍触发（任务方法早返回），仍会 reportCompletion，tracker 正常收齐发布 `SCHEDULED` 事件；若 cron 不触发，tracker 不创建 entry，超时兜底不会触发（无 entry 可检查）

### 模块 C：预计算框架

#### Requirement: PrecomputeJob 接口定义
系统 SHALL 提供 `PrecomputeJob` 接口，作为所有预计算任务的统一抽象。

```java
public interface PrecomputeJob {
    String name();                    // Job 名称
    void precompute(String tradeDate);// 执行预计算，结果塞入 Caffeine
}
```

- **设计说明**：接口不再包含 `dependsOnTables()` 方法。所有 Job 都订阅同一个 `DataBatchReadyEvent`，无需按表路由。Job 的多表依赖关系仅在 Javadoc 和 PRD 文档中注明，供查阅使用，**不参与运行时路由**。

#### Requirement: AbstractPrecomputeJob 抽象基类
系统 SHALL 提供 `AbstractPrecomputeJob` 抽象基类，实现模板方法模式，封装通用逻辑。

- **模板方法 `precompute(tradeDate)`**：
  1. 检查 `(jobName, tradeDate)` 是否正在执行（`ConcurrentHashMap` 去重）
  2. 记录 startTime
  3. 调用子类 `doPrecompute(tradeDate)`
  4. 记录 endTime + 耗时
  5. **异常时打 ERROR 日志 + 主动 evict 对应缓存 key**（避免 24h TTL 内用户看到昨日旧数据，让下次查询 MISS → 触发懒兜底重算）
  6. finally 清除去重标记
- **子类只需实现**：`doPrecompute(tradeDate)` + `cacheName()` + `cacheKeys(tradeDate)`（供异常时 evict）
- **缓存 key 双写**：子类 `doPrecompute` 内部对每个缓存 put 两个 key——`{tradeDate}` 和 `latest`（`latest` 指向最新交易日结果），确保无参查询（key=latest）和带参查询（key={tradeDate}）都能命中预计算结果

> ⚠️ **关键修正**（评审问题）：原设计"Job 失败不清缓存"与"懒兜底保证可用"矛盾——懒兜底只在 MISS 时触发，缓存命中旧值时不会重算。改为：**Job 失败时主动 evict 缓存 key（含 `{tradeDate}` 和 `latest`）**，让下次查询 MISS → 触发懒兜底重算。

#### Scenario: Job 重复触发去重
- **WHEN** 同一 `(jobName, tradeDate)` 的预计算正在执行中
- **AND** 又收到一个相同 tradeDate 的事件
- **THEN** 第二次触发被跳过，打 INFO 日志 `[Precompute][{jobName}] tradeDate={tradeDate} 已在执行，跳过`
- **AND** 不影响第一次执行的进行

#### Requirement: PrecomputeEventDispatcher 事件分发
系统 SHALL 提供 `PrecomputeEventDispatcher`，监听 `DataBatchReadyEvent` 并触发所有 `PrecomputeJob`。

- **初始化**：`@PostConstruct` 扫描所有 `PrecomputeJob` Bean，存入列表（无需构建路由表）
- **监听**：`@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
- **执行逻辑**（评审修正）：收到事件后，**并发提交**所有 Job 到 `precomputeExecutor`（用 `CompletableFuture.allOf` 等齐），而非串行遍历——7 Job 并发执行，首屏预计算完成时间从串行累计（可能 60s+）降为单 Job 最长耗时（约 10-15s）
- **数据完整性校验**（评审修正）：当 `event.source` 为 `SCHEDULED_PARTIAL` 或 `SCHEDULED_TIMEOUT` 时，Job 在 `doPrecompute` 内部增加数据完整性校验（如 `daily_quote` 当日记录数 > 0），不完整时跳过预计算并打 WARN（避免写脏缓存）；`source=SCHEDULED` 时正常执行
- **失败隔离**：单个 Job try-catch，失败只打 ERROR 日志 + 主动 evict 缓存，不影响其他 Job

```java
@EventListener
@Async("precomputeExecutor")
public void onBatchReady(DataBatchReadyEvent event) {
    List<CompletableFuture<Void>> futures = jobs.stream()
        .map(job -> CompletableFuture.runAsync(() -> {
            try {
                job.precompute(event.getTradeDate());
            } catch (Exception e) {
                log.error("[Precompute][{}] tradeDate={} 执行失败", job.name(), event.getTradeDate(), e);
            }
        }, precomputeExecutor))
        .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

#### Scenario: 收到批次事件触发全部 Job
- **WHEN** 收到 `DataBatchReadyEvent(tradeDate="20260729")`
- **THEN** 遍历所有 7 个 `PrecomputeJob` Bean
- **AND** 逐个调 `precompute("20260729")`，每个 Job 独立 try-catch
- **AND** 任一 Job 失败不影响其他 Job 执行

#### Scenario: 单 Job 失败隔离
- **WHEN** `MarketRankingPrecomputeJob.precompute` 抛异常
- **THEN** 该 Job 失败被 try-catch 捕获，打 ERROR 日志
- **AND** 其他 6 个 Job 仍正常执行
- **AND** 失败的 Job 在下次查询时由懒兜底保证数据可用

#### Requirement: 7 个具体 PrecomputeJob 实现
系统 SHALL 实现 7 个具体的 `PrecomputeJob`，覆盖所有实时聚合接口。

| Job 类 | 文档依赖（仅供查阅，不参与路由） | 调用的 Service 方法 | 写入的缓存 |
|---|---|---|---|
| `SectorRankingPrecomputeJob` | `daily_quote`, `sw_industry_member`, `index_daily`, `stock_basic` | `swIndustryService.computeIndustryRanking(tradeDate)` | `sectorRanking:{tradeDate}` |
| `SectorMoneyflowPrecomputeJob` | `stock_moneyflow`, `sw_industry_member` | `swIndustryService.computeIndustryMoneyflow(tradeDate)` | `sectorMoneyflow:{tradeDate}` |
| `SectorValuationPrecomputeJob` | `daily_basic`, `sw_industry_member` | `swIndustryService.computeIndustryValuation(tradeDate)` | `sectorValuation:{tradeDate}` |
| `MarketIndicesPrecomputeJob` | `index_daily` | `marketService.computeMarketIndices()` | `indices:{latestTradeDate}` |
| `MarketRankingPrecomputeJob` | `daily_quote`, `daily_basic`, `stock_basic` | `marketService.computeMarketRanking()` | `marketRanking:{latestTradeDate}` |
| `MarketTemperaturePrecomputeJob` | `daily_quote`, `stock_basic` | `marketService.computeMarketTemperature(tradeDate)` | `marketTemperature:{tradeDate}` |
| `MoneyflowRankingPrecomputeJob` | `stock_moneyflow` | `moneyflowService.computeQueryTop(tradeDate, 10, "main_net", "desc")` | `moneyflowRanking:{tradeDate}_10_main_net_desc` |

- **Job 实现要求**：
  - 继承 `AbstractPrecomputeJob`
  - 注入对应 Service + `CacheManager`
  - `doPrecompute` 内部：调 `computeXXX` 拿结果 → 用 `CacheKeyResolver` 算 key → **双写** `cacheManager.getCache(cacheName).put("{tradeDate}", result)` + `put("latest", result)`
  - 实现 `cacheName()` 和 `cacheKeys(tradeDate)` 供异常时 evict（返回 `["{tradeDate}", "latest"]`）
- **关键约束**：Job 调用的是 Service 的 `computeXXX` 方法（无 `@Cacheable` 注解），绕过 AOP 直接执行业务逻辑
- **缓存 key 双写说明**（评审修正）：用户无参查询时 `@Cacheable` SpEL 解析为 `latest`，带参查询解析为 `{tradeDate}`。Job 必须同时 put 两个 key，否则预计算结果无法被无参查询命中。`MarketIndicesPrecomputeJob`/`MarketRankingPrecomputeJob` 的 key 是 `{latestTradeDate}`，双写为 `{latestTradeDate}` + `latest`。
- **依赖说明**：上表"文档依赖"列注明每个 Job 实际涉及的数据表，仅用于代码评审与文档查阅，运行时不再依赖此信息做路由

#### Scenario: SectorRankingPrecomputeJob 执行
- **WHEN** `SectorRankingPrecomputeJob.doPrecompute("20260729")` 被调用
- **THEN** 调用 `swIndustryService.computeIndustryRanking("20260729")` 拿到 `List<IndustryRankingVO>`
- **AND** 用 `CacheKeyResolver.resolveSectorKey("20260729")` 算出 key `"20260729"`
- **AND** 调用 `cacheManager.getCache("sectorRanking").put("20260729", result)` + `put("latest", result)`（双写）
- **AND** 打 INFO 日志 `[Precompute][SectorRanking] tradeDate=20260729 耗时=XXXms 结果=success`

#### Scenario: Job 失败时主动 evict 缓存
- **WHEN** `SectorRankingPrecomputeJob.doPrecompute("20260729")` 抛异常
- **THEN** `AbstractPrecomputeJob` 捕获异常，打 ERROR 日志
- **AND** 主动 `cacheManager.getCache("sectorRanking").evict("20260729")` + `evict("latest")`
- **AND** 下次用户查询 `getIndustryRanking("20260729")` 或无参查询时 MISS → 触发懒兜底重算

#### Scenario: MoneyflowRankingPrecomputeJob 执行
- **WHEN** `MoneyflowRankingPrecomputeJob.doPrecompute("20260729")` 被调用
- **THEN** 调用 `moneyflowService.computeQueryTop("20260729", 10, "main_net", "desc")` 拿到 `List<MoneyflowTopVO>`
- **AND** 用 `CacheKeyResolver.resolveMoneyflowRankingKey("20260729", 10, "main_net", "desc")` 算出 key `"20260729_10_main_net_desc"`
- **AND** 调用 `cacheManager.getCache("moneyflowRanking").put("20260729_10_main_net_desc", result)` + `put("latest_10_main_net_desc", result)`（双写，latest 参数组合供无 tradeDate 查询命中）
- **AND** 打 INFO 日志 `[Precompute][MoneyflowRanking] tradeDate=20260729 耗时=XXXms 结果=success`

#### Requirement: MarketRankingPrecomputeJob 依赖说明
`MarketRankingPrecomputeJob` 的实际数据依赖是 `daily_quote + daily_basic + stock_basic` 三张表。

- **原因**：`MarketServiceImpl.getMarketRanking()` 内部的 `selectTopTurnover` SQL JOIN 了 `daily_basic` 表取 `turnover_rate` 字段，并 JOIN `stock_basic` 取股票基础信息
- **文档标注**：此依赖在 Job 的 Javadoc 和 PRD 的 Job 清单表中注明（原 PRD 错写为仅 `daily_quote`，本次修正）
- **不参与路由**：新设计下接口无 `dependsOnTables()` 方法，此标注仅供代码评审与文档查阅

#### Requirement: MoneyflowRankingPrecomputeJob 参数策略
`MoneyflowRankingPrecomputeJob` 只预计算前端固定的 `limit=10, sortBy=main_net, order=desc` 这一参数组合。

- **预计算参数**：`limit=10, sortBy=main_net, order=desc`（前端"资金流向页面 - 主力净流入排行"固定使用）
- **缓存 key**：`{tradeDate}_10_main_net_desc`
- **其他参数组合**：不预计算，走 `@Cacheable` 懒兜底（首次查询慢，后续命中缓存）

#### Requirement: PrecomputeAsyncConfig 异步线程池
系统 SHALL 提供 `precomputeExecutor` 线程池，供 `PrecomputeEventDispatcher` 异步执行 Job。

- **核心线程数**：4（评审修正：原 2 太小，7 Job 并发提交时排队严重）
- **最大线程数**：8
- **队列容量**：20
- **线程名前缀**：`precompute-`
- **拒绝策略**：`CallerRunsPolicy`（队列满时降级为同步执行，不丢任务）
- **共享说明**：本线程池同时供 7 个 `PrecomputeJob` + `FactorSnapshotTask` + `ScreenLockTrackingTask` 使用
- **隔离建议**（评审修正）：`FactorSnapshotTask` 是 DB 重写（全市场 factor_snapshot 表，IO 密集），建议独立线程池 `factorSnapshotExecutor`（core=1/max=2/queue=10）隔离，避免拖累 7 Job 首屏预计算。若实测 FactorSnapshot 耗时 <15s 可不隔离。

#### Requirement: Spring 调度器多线程配置（评审新增）
系统 SHALL 显式配置 Spring `@Scheduled` 调度器为多线程，避免 4 个数据更新任务串行排队。

- **配置方式**：`application.yml` 加 `spring.task.scheduling.pool.size: 4`，或自定义 `ThreadPoolTaskScheduler` bean
- **原因**：Spring 默认 `TaskScheduler` 是单线程，16:00 的 `DailyUpdateTask` 若卡死，16:10/16:30 任务全部不执行，批次永远收不齐 → 超时兜底成为常态
- **验收**：4 个 @Scheduled 任务可并行执行（无串行阻塞）

#### Requirement: PrecomputeService 手动触发入口
系统 SHALL 提供 `PrecomputeService`，支持手动触发预计算（懒兜底 + 运维排错）。

- **方法**：
  - `precomputeNow(String jobName, String tradeDate)`：同步执行单个 Job（懒兜底用）
  - `precomputeAll(String tradeDate)`：触发全部 Job（运维排错）

#### Scenario: 懒兜底同步执行
- **WHEN** 查询接口发现缓存未命中，调用 `precomputeService.precomputeNow("SectorRanking", "20260729")`
- **THEN** 同步执行 `SectorRankingPrecomputeJob.doPrecompute("20260729")`
- **AND** 执行完成后缓存已写入
- **AND** 调用方拿到结果（或直接从缓存读取）

### 模块 D：Service 层拆分 get/compute

#### Requirement: Service 拆分 getXXX 与 computeXXX
系统 SHALL 在 `SwIndustryServiceImpl` / `MarketServiceImpl` / `MoneyflowServiceImpl` 中，把每个聚合查询方法拆分为 `getXXX`（带 `@Cacheable`）和 `computeXXX`（无注解）两部分。

| Service | 公开查询方法（带 `@Cacheable`） | 内部计算方法（无注解） |
|---|---|---|
| `SwIndustryServiceImpl` | `getIndustryRanking` | `computeIndustryRanking` |
| `SwIndustryServiceImpl` | `getIndustryMoneyflow` | `computeIndustryMoneyflow` |
| `SwIndustryServiceImpl` | `getIndustryValuation` | `computeIndustryValuation` |
| `MarketServiceImpl` | `getMarketIndices` | `computeMarketIndices` |
| `MarketServiceImpl` | `getMarketRanking` | `computeMarketRanking` |
| `MarketServiceImpl` | `getMarketTemperature` | `computeMarketTemperature` |
| `MoneyflowServiceImpl` | `queryTop` | `computeQueryTop` |

- **`getXXX` 实现**：直接 `return computeXXX(tradeDate);`，依赖 `@Cacheable` 注解实现缓存命中跳过 / 未命中执行并写入
- **`computeXXX` 实现**：原 `getXXX` 方法体的全部业务逻辑（无注解，可被 Job 直接调用）
- **`unless` 条件**：`@Cacheable` 加 `unless = "#result == null || #result.isEmpty()"`，避免空结果被缓存
- **自调用约束**（评审修正）：`computeXXX` 必须 `public` 且**不互相调用**（避免 `getXXX` 内部其他地方调 `computeXXX` 时 this 调用绕过 Spring AOP 导致 @Cacheable 失效，懒兜底失效）。Code review 检查点：`getXXX` 方法体只有一行 `return computeXXX(...)`，不包含其他业务逻辑。若未来需要组合调用，拆出独立 `XxxComputeService` bean，get/compute 分属两个 bean。
- **空结果短 TTL 防穿透**（评审建议）：节假日 `computeXXX` 返回空 List 时不缓存（`unless` 生效），每次查询打 DB。建议空结果用 `@Cacheable` 的 `unless` 改为允许缓存空结果但短 TTL（5 分钟），避免节假日 DB 压力。可通过 `CacheConfig` 给 sectorRanking 等缓存配置不同 TTL 的空值包装实现。

#### Requirement: MoneyflowServiceImpl 改造
系统 SHALL 给 `MoneyflowServiceImpl.queryTop` 加 `@Cacheable` 注解，并新增无注解的 `computeQueryTop` 方法。

```java
@Cacheable(
    value = "moneyflowRanking",
    key = "T(com.arthur.stock.util.CacheKeyResolver).resolveMoneyflowRankingKey(#tradeDate, #limit, #sortBy, #order)",
    unless = "#result == null || #result.isEmpty()"
)
public List<MoneyflowTopVO> queryTop(String tradeDate, Integer limit, String sortBy, String order) {
    return computeQueryTop(tradeDate, limit, sortBy, order);
}

public List<MoneyflowTopVO> computeQueryTop(String tradeDate, Integer limit, String sortBy, String order) {
    // 原 queryTop 方法体的全部业务逻辑
}
```

- **接口扩展**：`MoneyflowService` 接口新增 `computeQueryTop` 方法签名
- **预计算固定参数**：`MoneyflowRankingPrecomputeJob` 只预计算 `limit=10, sortBy=main_net, order=desc` 这一组合，其他参数组合走懒兜底

#### Requirement: MarketServiceImpl.getMarketRanking 加缓存
系统 SHALL 给 `MarketServiceImpl.getMarketRanking` 加 `@Cacheable` 注解（当前无缓存）。

```java
@Cacheable(value = "marketRanking", key = "#root.target.getLatestTradeDate()")
public MarketRankingVO getMarketRanking() {
    return computeMarketRanking();
}
```

#### Requirement: CacheKeyResolver 统一 key 解析
系统 SHALL 提供 `CacheKeyResolver` 工具类，供 `@Cacheable` 的 SpEL 和 Job 显式 put 时共用。

```java
public class CacheKeyResolver {
    public static String resolveSectorKey(String tradeDate) {
        return tradeDate != null && !tradeDate.isBlank() ? tradeDate : "latest";
    }
    public static String resolveLatestKey(String latestTradeDate) {
        return latestTradeDate != null ? latestTradeDate : "empty";
    }
    public static String resolveMoneyflowRankingKey(String tradeDate, Integer limit, String sortBy, String order) {
        String prefix = (tradeDate != null && !tradeDate.isBlank()) ? tradeDate : "latest";
        return prefix + "_" + limit + "_" + sortBy + "_" + order;
    }
}
```

> ⚠️ **key 一致性约束**（评审修正）：`@Cacheable` SpEL 调 `CacheKeyResolver.resolveXXXKey(#tradeDate)`，当 `#tradeDate` 为 null 时返回 `latest` 后缀；Job 双写时显式 put `{tradeDate}` 和 `latest` 两个 key，确保无参查询命中 `latest`。

#### Scenario: SpEL 与 Job 使用相同 key
- **WHEN** `@Cacheable` 的 SpEL 是 `T(com.arthur.stock.util.CacheKeyResolver).resolveSectorKey(#tradeDate)`
- **AND** Job 显式 put 时用 `CacheKeyResolver.resolveSectorKey(tradeDate)`
- **THEN** 两者算出的 key 一致（如 `"20260729"` 或 `"latest"`）
- **AND** Job 写入的缓存能被 `@Cacheable` 命中

#### Scenario: 缓存命中直接返回
- **WHEN** 用户调 `getIndustryRanking("20260729")`
- **AND** Caffeine 中 `sectorRanking:20260729` 已有值
- **THEN** Spring AOP 拦截，直接返回缓存值
- **AND** `computeIndustryRanking` 方法体不执行

#### Scenario: 缓存未命中懒兜底
- **WHEN** 用户调 `getIndustryRanking("20260729")`
- **AND** Caffeine 中 `sectorRanking:20260729` 无值（重启后或预计算失败）
- **THEN** Spring AOP 拦截，发现未命中
- **AND** 执行 `computeIndustryRanking("20260729")` 拿到结果
- **AND** 结果被写入 Caffeine（key=`20260729`）
- **AND** 第二次调用直接命中缓存

### 模块 E：缓存配置调整

#### Requirement: Caffeine TTL 调整
系统 SHALL 调整 `CacheConfig.java` 中的缓存 TTL：

| cacheName | 原 TTL | 新 TTL | 备注 |
|---|---|---|---|
| `sectorRanking` | 30 min | 24 h | 数据一天不变 |
| `sectorMoneyflow` | 30 min | 24 h | 数据一天不变 |
| `sectorValuation` | 30 min | 24 h | 数据一天不变 |
| `marketRanking` | （新增） | 24 h | 新增 cacheName |
| `moneyflowRanking` | （新增） | 24 h | 新增 cacheName |
| `marketTemperature` | 无 TTL | 无 TTL + `maximumSize(50)` | 加容量上限防膨胀 |
| `indices` | 无 TTL | 无 TTL + `maximumSize(50)` | 加容量上限防膨胀 |

#### Requirement: 移除 @CacheEvict 注解
系统 SHALL 移除 4 个 task 类上的 `@CacheEvict` 注解，由预计算主动 put 覆盖旧值。

- `DailyUpdateTask.dailyUpdate`（行 46）
- `BasicDataTask.fetchDailyBasic`（行 51）
- `MoneyflowDataTask.fetchDailyMoneyflowData`（行 49）
- `IndexDailyFetchService.dailySync`（行 117-118）

### 模块 F：数据管控中心扩展

#### Requirement: scheduled-tasks API 端点
系统 SHALL 在 `DataGovernanceController` 新增 4 个端点：

| # | 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|---|
| 17 | GET | `/api/data-governance/scheduled-tasks` | 列出所有定时任务元信息 + 上次执行 + 下次执行 | 登录可访问 |
| 18 | GET | `/api/data-governance/scheduled-tasks/{taskClass}` | 单任务详情 | 登录可访问 |
| 19 | GET | `/api/data-governance/scheduled-tasks/{taskClass}/history` | 单任务执行历史（最近 30 条，支持分页） | 登录可访问 |
| 20 | POST | `/api/data-governance/scheduled-tasks/{taskClass}/run` | 手动触发任务执行（运维重跑） | 管理员 |

> ⚠️ **权限统一**：所有 scheduled-tasks 端点要求登录访问（与现有 `/tables` 对齐，若 `/tables` 历史为"公开"应一并复核）。
> **字段脱敏**：`task_class`/`method_name` 属于实现细节，**仅在登录态返回**；未登录请求直接 401。`error_message`/`error_stack` 按现有 `data_pull_log.error_stack` 权限分级（管理员可见完整，普通用户脱敏）。
> **路径参数用 taskClass**：`taskClass` 是类全限定名（稳定且唯一，无中文编码问题），前端展示仍用中文 `taskName`。`@ManagedTask.name` 启动时校验唯一性，重名 fail-fast。

#### Scenario: 查询所有任务
- **WHEN** 客户端调 `GET /api/data-governance/scheduled-tasks`
- **THEN** 返回 `ApiResponse<List<ScheduledTaskVO>>`
- **AND** 列表包含所有 21 个被管理的拉取类任务
- **AND** 每个任务包含 `taskName` / `cron` / `tableCode` / `taskGroup` / `lastExecutionTime` / `lastStatus` / `nextExecutionTime`

#### Scenario: 查询单任务历史
- **WHEN** 客户端调 `GET /api/data-governance/scheduled-tasks/com.arthur.stock.task.DailyUpdateTask/history?limit=30&page=1`
- **THEN** 返回 `ApiResponse<PageResult<TaskExecutionLogDO>>`（含 `records` / `total` / `page` / `pageSize`）
- **AND** 列表按 `start_time DESC` 排序，仅返回 `task_name IS NOT NULL` 的记录（不混入手动拉取日志）
- **AND** 每条记录包含 `startTime` / `endTime` / `durationMs` / `status` / `errorMessage`（脱敏后）

#### Requirement: ScheduledTaskVO 数据结构
系统 SHALL 提供 `ScheduledTaskVO`，包含任务元信息 + 执行状态。

字段：
- `taskName`（String）：任务名称（`@ManagedTask.name`，中文展示用）
- `taskClass`（String）：类全限定名（路径参数用，稳定且唯一）
- `methodName`（String）：方法名
- `cron`（String）：cron 表达式
- `cronReadable`（String）：cron 人类可读描述（如"每天 16:00"，由后端解析 cron 生成，评审新增）
- `tableCode`（String）：关联表代码
- `tableName`（String）：关联表中文名（从 `InitStep.label` 取）
- `taskGroup`（String）：分组（英文枚举 value）
- `taskGroupLabel`（String）：分组中文映射（如"数据拉取"，评审新增）
- `description`（String）：任务描述
- `lastExecutionTime`（String）：最近一次执行开始时间（查 `data_pull_log`）
- `lastStatus`（String）：最近一次执行状态（SUCCESS / FAILED / RUNNING / NEVER_RUN）
- `currentStatus`（String）：当前实时状态（RUNNING 时表示任务正在执行，评审新增）
- `lastDurationMs`（Long）：最近一次执行耗时
- `nextExecutionTime`（String）：下次执行时间（`CronExpression` 计算）
- `enabled`（Boolean）：是否启用（默认 true，预留字段）
- `configInconsistent`（Boolean）：cron 与 InitStep 是否不一致（true 时前端展示"配置异常"徽标，评审新增）

> **状态四态**（评审修正）：SUCCESS(绿) / FAILED(红) / RUNNING(蓝，正在执行) / NEVER_RUN(灰，从未执行)。前端列表"上次状态"列改为"当前状态"，RUNNING 时耗时列实时刷新（前端轮询 5s 或 SSE）。

#### Requirement: TableStatusVO 扩展字段
系统 SHALL 在 `TableStatusVO` 新增 3 个字段（向后兼容，原有字段不变）：

- `cron`（String）：关联任务的 cron（若有多个任务关联同一表，取第一个）
- `nextExecutionTime`（String）：下次执行时间
- `lastExecutionTime`（String）：从 `data_pull_log` 取（替代原 `lastUpdateTime` 来源，更准确）

#### Requirement: 前端数据管控页面新增"定时任务"分区
系统 SHALL 在 `data-governance.html` 页面新增"定时任务"分区，与现有"数据表总览"分区并列。

- **分区位置**：在数据表总览分区下方
- **表格列**：任务名 / 分组(中文) / 关联表 / cron(可读化) / 当前状态 / 耗时 / 下次执行 / 操作（查看历史 / 重跑 / 暂停）
- **状态展示**（评审修正）：SUCCESS 绿色 / FAILED 红色 / RUNNING 蓝色（耗时实时刷新）/ NEVER_RUN 灰色
- **配置异常徽标**：`configInconsistent=true` 时该行展示"配置异常"橙色徽标（cron 与 InitStep 不一致）
- **筛选**（评审修正）：
  - 按分组（DATA_FETCH / GOVERNANCE / MAINTENANCE / PRECOMPUTE / VERIFY，下拉展示中文映射）
  - 按状态（全部 / 成功 / 失败 / 运行中 / 从未执行，评审新增）
  - 按关键字（任务名/关联表模糊搜索）
- **查看历史**：点击"查看历史"按钮弹出执行历史模态框（支持分页 + 按日期/状态筛选，默认 30 条/页，评审修正）
- **手动重跑**（评审新增）：点击"重跑"按钮调 `POST /scheduled-tasks/{taskClass}/run`，RUNNING 时按钮禁用并提示"任务执行中"；重跑记入 `data_pull_log`（`trigger_type=MANUAL`）
- **三态展示**（评审新增）：
  - **loading**：骨架屏（首屏加载时）
  - **空状态**："暂无执行记录"插画 + 文案（系统刚上线/任务从未执行）
  - **错误状态**："加载失败，点击重试"按钮（API 调用失败）
- **errorMessage 展示**（评审修正）：失败记录的 errorMessage 默认折叠（避免撑爆布局），点击展开查看完整脱敏后信息，红色高亮

#### Scenario: 用户查看定时任务分区
- **WHEN** 用户访问 `/page/data-governance`
- **THEN** 页面下方显示"定时任务"分区
- **AND** 表格展示所有 21 个被管理的任务
- **AND** 每行显示任务名 / 分组(中文) / 关联表 / cron(可读化) / 当前状态 / 耗时 / 下次执行 / 操作

#### Scenario: 查看任务执行历史
- **WHEN** 用户点击某任务的"查看历史"按钮
- **THEN** 弹出模态框，展示该任务执行历史（默认 30 条/页，支持分页 + 按日期/状态筛选）
- **AND** 每条记录显示开始时间 / 结束时间 / 耗时 / 状态 / 错误信息（折叠，点击展开）
- **AND** 失败记录的 errorMessage 红色高亮

#### Scenario: 手动重跑任务
- **WHEN** 运维点击某 FAILED 任务的"重跑"按钮
- **THEN** 调 `POST /api/data-governance/scheduled-tasks/{taskClass}/run`
- **AND** 后端校验当前非 RUNNING 状态，触发任务执行
- **AND** `data_pull_log` 新增一条记录（`trigger_type=MANUAL`, `operator=当前用户`）
- **AND** 前端列表该任务状态切换为 RUNNING(蓝)

#### Scenario: RUNNING 状态实时刷新
- **WHEN** 某任务正在执行
- **THEN** 列表该行状态显示 RUNNING(蓝)
- **AND** 耗时列每 5s 刷新（前端轮询）
- **AND** "重跑"按钮禁用并提示"任务执行中"

#### Requirement: 任务失败告警（评审新增）
系统 SHALL 对 `DATA_FETCH` 和 `PRECOMPUTE` 分组的任务失败时主动通知运维。

- **告警触发**：`TaskExecutionLogAspect` 记录 `status=FAILED` 时，若 `task_group` 为 `DATA_FETCH` 或 `PRECOMPUTE`，异步发送告警
- **告警渠道**：邮件 / IM webhook（具体渠道在 `application.yml` 配置）
- **告警内容**：任务名 / 失败时间 / error_message 摘要 / 历史链接（`/page/data-governance`）
- **防骚扰**：同一任务 30 分钟内只告警 1 次（避免连续失败刷屏）
- **验收**：mock DATA_FETCH 任务失败，断言告警已发送

#### Requirement: 数据表总览表格新增"下次执行"列
系统 SHALL 在数据表总览表格新增"下次执行"列，展示该表关联任务的下次执行时间。

- **列位置**：在"更新频率"列后
- **数据来源**：`TableStatusVO.nextExecutionTime`
- **空值处理**：若该表无关联任务，显示 `-`

### 模块 G：现有任务事件驱动改造

#### Requirement: FactorSnapshotTask 事件驱动改造
系统 SHALL 把 `FactorSnapshotTask` 从 cron 触发改造为订阅 `DataBatchReadyEvent` 触发。

- **移除**：`@Scheduled(cron = "0 30 16 * * MON-FRI")` 注解
- **新增**：`@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
- **依赖数据**：`daily_quote`（取最新交易日 + OHLCV 历史）
- **时序 hack 消除**：原 cron 注释"晚于 DailyUpdateTask 16:00 与 BasicDataTask 16:10"的时序错峰依赖被消除，改为事件驱动后保证数据已就绪
- **保持独立**：`FactorSnapshotTask` 不是 `PrecomputeJob` 接口的实现（它走 `factor_snapshot` 持久化表，不走 Caffeine），保持独立实现，仅触发方式改为事件
- **不加 `@ManagedTask`**：本次只管理 21 个拉取类任务，`FactorSnapshotTask` 不加注解、不记录到 `data_pull_log`，其执行记录靠自身日志输出

```java
@Component
@RequiredArgsConstructor
public class FactorSnapshotTask {
    @EventListener
    @Async("precomputeExecutor")
    public void onBatchReady(DataBatchReadyEvent event) {
        try {
            computeDaily(event.getTradeDate());
        } catch (Exception e) {
            log.error("[FactorSnapshot] tradeDate={} 执行失败", event.getTradeDate(), e);
        }
    }
}
```

#### Scenario: FactorSnapshotTask 由事件触发
- **WHEN** `DataBatchReadyEvent(tradeDate="20260729")` 发布
- **THEN** `FactorSnapshotTask.onBatchReady` 被调用（异步）
- **AND** 执行 `computeDaily("20260729")`，写入 `factor_snapshot` 表
- **AND** 原 `@Scheduled(cron = "0 30 16 * * MON-FRI")` 已移除

#### Scenario: FactorSnapshotTask 不被 TaskExecutionLogAspect 记录
- **WHEN** `FactorSnapshotTask.onBatchReady` 执行
- **THEN** `data_pull_log` 表**不新增**记录（因未加 `@ManagedTask` 注解，不在切点范围内）
- **AND** 执行记录通过应用日志输出（`[FactorSnapshot] ...`）

#### Requirement: ScreenLockTrackingTask 事件驱动改造
系统 SHALL 把 `ScreenLockTrackingTask` 从 cron 触发改造为订阅 `DataBatchReadyEvent` 触发。

- **移除**：`@Scheduled(cron = "0 30 16 * * ?")` 注解
- **新增**：`@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
- **依赖数据**：`daily_quote`（取锁定日 + 5/10/20 日后收盘价）
- **时序 hack 消除**：原 cron 注释"与 DailyUpdateTask 的 16:00 错开"的时序错峰依赖被消除
- **保持独立**：`ScreenLockTrackingTask` 不是 `PrecomputeJob` 接口的实现，保持独立实现
- **不加 `@ManagedTask`**：本次只管理 21 个拉取类任务，`ScreenLockTrackingTask` 不加注解、不记录到 `data_pull_log`，其执行记录靠自身日志输出

#### Scenario: ScreenLockTrackingTask 由事件触发
- **WHEN** `DataBatchReadyEvent(tradeDate="20260729")` 发布
- **THEN** `ScreenLockTrackingTask.onBatchReady` 被调用（异步）
- **AND** 执行 `track("20260729")`，更新选股锁定追踪记录
- **AND** 原 `@Scheduled(cron = "0 30 16 * * ?")` 已移除

#### Scenario: ScreenLockTrackingTask 不被 TaskExecutionLogAspect 记录
- **WHEN** `ScreenLockTrackingTask.onBatchReady` 执行
- **THEN** `data_pull_log` 表**不新增**记录（因未加 `@ManagedTask` 注解）
- **AND** 执行记录通过应用日志输出

---

## 四、非功能需求

### 4.1 性能

| 指标 | 目标 |
|---|---|
| 板块行情接口响应时间（缓存命中） | < 50ms |
| 板块行情接口响应时间（缓存未命中，懒兜底） | < 3s（与原实时聚合持平） |
| 预计算 Job 单个执行时间 | < 30s（参照 FactorSnapshotTask 约 10s） |
| AOP 切面同步开销 | < 1ms（仅记录时间，日志异步写入） |
| `/scheduled-tasks` 接口响应时间 | < 200ms（21 个任务 + 查询最近执行） |
| `DataBatchReadyEvent` 发布到 Job 启动的延迟 | < 100ms |

### 4.2 可用性

- 预计算 Job 失败不影响查询（懒兜底保证数据可用）
- 事件分发器异常不影响任务主流程（try-catch + 异步）
- `DataBatchCompletionTracker` 单点故障由超时兜底保护（P1）
- `ScheduledTaskHolder` 反射失败不影响应用启动（降级空列表）
- Caffeine 重启清空后首次查询自动懒兜底

### 4.3 可观测性

- 每个 Job 执行打 INFO 日志：`[Precompute][{jobName}] tradeDate={tradeDate} 耗时={ms}ms 结果={success/fail}`
- Job 失败打 ERROR 日志，包含异常栈
- `FactorSnapshotTask` / `ScreenLockTrackingTask` 自身打 INFO/ERROR 日志（不进 `data_pull_log`）
- 任务执行历史持久化到 `data_pull_log` 表，保留 3 个月
- 启动时校验 InitStep 一致性，不一致打 WARN
- `DataBatchCompletionTracker` 在批次发布时打 INFO 日志：`[BatchTracker] tradeDate={tradeDate} 收齐 4 个任务报告，发布 DataBatchReadyEvent`

### 4.4 兼容性

- 现有接口行为不变（响应数据一致）
- `TableStatusVO` 新增字段向后兼容，原有字段不变
- `InitStep` 枚举结构不变，`updateFrequency` / `expectedUpdateTime` 保留作为兜底展示
- `factor_snapshot` 持久化预计算模式不受影响，与本架构并存（仅触发方式改为事件）

### 4.5 安全性

- `/scheduled-tasks` 所有端点要求登录访问（与现有 `/tables` 对齐，若 `/tables` 历史为"公开"应一并复核其合理性）
- `task_class`/`method_name` 属于实现细节，仅在登录态返回，避免类名/方法名暴露给匿名用户推断系统行为节奏
- `POST /scheduled-tasks/{taskClass}/run` 手动重跑接口要求管理员权限
- `data_pull_log` 中的 `error_message` 可能包含敏感信息（如 SQL/堆栈），按以下脱敏规则处理：
  - **保留**：异常类型 + 顶层 message（如 `NullPointerException: Daily quote is empty`）
  - **过滤**：文件路径、SQL 语句、密码/token、内部 IP
  - **截断**：先脱敏后截断至 2000 字符（`error_message` 字段 VARCHAR(1024) 时截 1024，`error_stack` TEXT 字段截 8192）
  - **管理员**可见完整堆栈；**普通用户**仅见脱敏后的顶层 message

---

## 五、数据设计

### 5.1 复用 `data_pull_log` 表，新增 5 个可空字段

**不新建表**。现有 `data_pull_log` 表已记录 DataInitService 手动触发的拉取日志，本次改造扩展其语义，同时记录 21 个拉取类定时任务的执行历史。

由于本次覆盖的 21 个任务都有 `tableCode`（与 `data_pull_log.table_code` 天然对应），现有 `table_code` / `table_name` 的 NOT NULL 约束**无需修改**。

**新增字段**（均为可空，向后兼容现有 DataInitService 写入路径）：

```sql
ALTER TABLE data_pull_log
  ADD COLUMN task_name    VARCHAR(64)  NULL COMMENT '任务名称（@ManagedTask.name，定时任务专用）',
  ADD COLUMN task_class   VARCHAR(128) NULL COMMENT '任务类全限定名（定时任务专用）',
  ADD COLUMN method_name  VARCHAR(64)  NULL COMMENT '方法名（定时任务专用）',
  ADD COLUMN task_group   VARCHAR(32)  NULL COMMENT '任务分组：DATA_FETCH/PRECOMPUTE/GOVERNANCE/MAINTENANCE/VERIFY',
  ADD COLUMN trigger_type VARCHAR(16)  NULL COMMENT '触发类型：SCHEDULED/EVENT/MANUAL（定时任务专用）',
  ADD INDEX idx_task_name_time (task_name, start_time);
```

**字段语义对照**：

| 字段 | 现有 DataInitService 手动拉取 | 本次新增 定时任务执行 | 备注 |
|---|---|---|---|
| `task_id` | UUID | UUID（AOP 切面生成） | 共用 |
| `table_code` | NOT NULL | NOT NULL（21 个任务都有 tableCode） | 共用，无需改约束 |
| `table_name` | NOT NULL | NOT NULL（从 InitStep.label 取） | 共用 |
| `operation_type` | MANUAL_INCREMENTAL / MANUAL_FULL | SCHEDULED | 枚举值已有 SCHEDULED，无需扩展 |
| `status` | RUNNING/SUCCESS/FAILED/CANCELLED | SUCCESS/FAILED | 共用，定时任务不写 RUNNING |
| `total_count` / `success_count` / `fail_count` | 有值 | 默认 0（定时任务不统计条数） | 共用，定时任务填 0 |
| `error_message` / `error_stack` | 有值（失败时） | 有值（失败时） | 共用 |
| `operator` | 用户名 / SYSTEM | SYSTEM | 共用 |
| `task_name`（新） | NULL | "@ManagedTask.name" | 定时任务专用 |
| `task_class`（新） | NULL | 类全限定名 | 定时任务专用 |
| `method_name`（新） | NULL | 方法名 | 定时任务专用 |
| `task_group`（新） | NULL | DATA_FETCH | 定时任务专用 |
| `trigger_type`（新） | NULL | SCHEDULED | 定时任务专用 |

**查询区分**（评审修正：路径参数为 `taskClass`，按 `task_class` 精确查询比按 `task_name` 更稳定——`task_name` 可能在重命名时变化，`task_class` 唯一且稳定）：
- 查"定时任务执行历史"：`WHERE task_class = ? AND task_name IS NOT NULL`（按路径参数 `taskClass` 过滤）
- 查"手动拉取历史"：`WHERE task_name IS NULL`（或 `trigger_type IS NULL`）
- 查"全部历史"：不加过滤条件

保留期：3 个月，由 `MetricCleanupJob` 每日凌晨 01:00 清理（现有逻辑已覆盖，无需改动）。

### 5.2 不新增其他表

任务元信息（cron / name / tableCode / group / description）通过运行时反射 `@ManagedTask` 注解 + `ScheduledTaskHolder` 获取，**不持久化到 DB**。这样 cron 改了立即生效，无需同步 DB。

---

## 六、接口设计

### 6.1 新增 REST API

#### `GET /api/data-governance/scheduled-tasks`

**请求参数**：
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| group | String | 否 | 按分组过滤（DATA_FETCH / GOVERNANCE / MAINTENANCE / PRECOMPUTE / VERIFY） |
| keyword | String | 否 | 按任务名/关联表关键字模糊搜索 |

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "taskName": "每日数据更新",
      "taskClass": "com.arthur.stock.task.DailyUpdateTask",
      "methodName": "dailyUpdate",
      "cron": "0 0 16 * * ?",
      "tableCode": "daily",
      "tableName": "日线行情",
      "taskGroup": "DATA_FETCH",
      "description": "同步交易日历/股票基础/日线行情/复权因子/分红",
      "lastExecutionTime": "2026-07-29 16:00:12",
      "lastStatus": "SUCCESS",
      "lastDurationMs": 12345,
      "nextExecutionTime": "2026-07-30 16:00:00",
      "enabled": true
    }
  ]
}
```

#### `GET /api/data-governance/scheduled-tasks/{taskClass}`

**路径参数**：`taskClass`（任务类全限定名，如 `com.arthur.stock.task.DailyUpdateTask`）

**响应**：单个 `ScheduledTaskVO` 对象

#### `GET /api/data-governance/scheduled-tasks/{taskClass}/history`

**请求参数**：
| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| limit | int | 否 | 30 | 返回记录数（最大 100） |
| page | int | 否 | 1 | 页码（评审新增，支持分页） |
| status | String | 否 | 全部 | 按状态过滤（SUCCESS/FAILED/RUNNING） |
| startDate | String | 否 | — | 按开始时间过滤（yyyyMMdd） |

**响应**：
```json
{
  "code": 200,
  "data": [
    {
      "id": 123,
      "taskName": "每日数据更新",
      "taskClass": "com.arthur.stock.task.DailyUpdateTask",
      "methodName": "dailyUpdate",
      "tableCode": "daily",
      "taskGroup": "DATA_FETCH",
      "triggerType": "SCHEDULED",
      "startTime": "2026-07-29 16:00:12",
      "endTime": "2026-07-29 16:00:24",
      "durationMs": 12345,
      "status": "SUCCESS",
      "errorMessage": null
    }
  ]
}
```

### 6.2 内部接口（Spring Bean 间调用）

#### `ScheduledTaskRegistryService`
- `List<ScheduledTaskVO> listScheduledTasks()`：返回所有任务元信息
- `ScheduledTaskVO getScheduledTask(String taskClass)`：单任务详情
- `String getNextExecutionTime(String taskClass)`：下次执行时间
- `void runTask(String taskClass, String operator)`：手动触发任务执行（管理员权限，运维重跑用）

> **手动重跑与定时触发的 trigger_type 区分机制**（评审修正）：
> - `TaskExecutionLogAspect` 通过 `TriggerContext`（ThreadLocal）感知触发来源
> - `runTask` 实现内：先在 `TriggerContext` 中 set `triggerType=MANUAL` + `operator=当前用户`，再反射调用目标 `@Scheduled` 方法，方法返回后清理 ThreadLocal
> - AOP 切面在 `@Around` 入口读取 `TriggerContext`：若 ThreadLocal 有值则用 MANUAL + 当前 operator；若 ThreadLocal 为空（定时调度触发）则用 SCHEDULED + SYSTEM
> - 这样手动重跑与定时触发的日志自然区分，无需在 4 个 task 类内做特殊处理
> - **拒绝重跑**：`runTask` 入口校验该任务当前 `currentStatus != RUNNING`，否则抛 `IllegalStateException("任务执行中")`，HTTP 返回 409 Conflict

```java
// TriggerContext（ThreadLocal 实现）
public class TriggerContext {
    private static final ThreadLocal<TriggerInfo> HOLDER = new ThreadLocal<>();
    public static void setManual(String operator) { HOLDER.set(new TriggerInfo("MANUAL", operator)); }
    public static TriggerInfo get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }
    public record TriggerInfo(String triggerType, String operator) {}
}
```

#### `DataBatchCompletionTracker`
- `void reportCompletion(String taskKey, String tradeDate)`：任务正常完成报告
- `void reportCompletion(String taskKey, String tradeDate, boolean hasError)`：任务异常完成报告（finally 块调用）
- `void forceFireOnTimeout(String tradeDate, Set<String> missingTasks)`：超时兜底强制发布事件

#### `PrecomputeService`
- `void precomputeNow(String jobName, String tradeDate)`：同步执行单个 Job
- `void precomputeAll(String tradeDate)`：触发全部 Job

### 6.3 测试触发入口（评审新增）

为支持验收测试（避免等待真实定时触发），系统 SHALL 在测试 Profile（`@Profile("test")`）下提供以下测试 Controller：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/test/trigger-batch-event?tradeDate=20260729&source=SCHEDULED` | 手动发布 `DataBatchReadyEvent`，触发 7 Job + FactorSnapshot + ScreenLock |
| POST | `/admin/test/trigger-task/{taskClass}` | 手动触发某 @Scheduled 任务执行（走 AOP 切面，记 data_pull_log） |
| POST | `/admin/test/metric-cleanup?cutoff=2026-04-29` | 手动触发 MetricCleanupJob 清理 cutoff 之前记录 |
| POST | `/admin/test/precompute-all?tradeDate=20260729` | 手动触发全部预计算 Job |
| GET | `/admin/test/cache-keys?cacheName=sectorRanking` | 查看 Caffeine 缓存 Key 集合（验证命中/未命中） |
| POST | `/admin/test/cache-evict?cacheName=sectorRanking&key=20260729` | 手动 evict 缓存（模拟懒兜底） |

> **安全约束**：测试 Controller 仅在 `spring.profiles.active=test` 时启用，生产环境不加载。

#### `SwIndustryService` / `MarketService` / `MoneyflowService` 新增方法
- `List<IndustryRankingVO> computeIndustryRanking(String tradeDate)`（包可见，无注解）
- `List<IndustryMoneyflowVO> computeIndustryMoneyflow(String tradeDate)`
- `List<IndustryValuationVO> computeIndustryValuation(String tradeDate)`
- `List<MarketIndexVO> computeMarketIndices()`
- `MarketRankingVO computeMarketRanking()`
- `MarketTemperatureVO computeMarketTemperature(String tradeDate)`
- `List<MoneyflowTopVO> computeQueryTop(String tradeDate, Integer limit, String sortBy, String order)`

---

## 七、交互流程

### 7.1 每日数据更新 → 批次完成 → 预计算流程

```
16:00:00  DailyUpdateTask.dailyUpdate() 触发                BasicDataTask.fetchDailyBasic() 触发
          │ @ManagedTask 切面记录 startTime                  │ @ManagedTask 切面记录 startTime
          │ 执行 5 步数据拉取                                │ 执行基本面数据拉取
          │ finally: reportCompletion("DailyUpdateTask")    │ finally: reportCompletion("BasicDataTask")
          │                                                  │
16:00:10  MoneyflowDataTask.fetchDailyMoneyflowData() 触发  IndexDailyFetchService.dailySync() 触发
          │ @ManagedTask 切面记录 startTime                  │ @ManagedTask 切面记录 startTime
          │ 执行资金流数据拉取                                │ 执行指数日线同步
          │ finally: reportCompletion("MoneyflowDataTask")  │ finally: reportCompletion("IndexDailyFetchService")
          │                                                  │
          ▼                                                  ▼
          DataBatchCompletionTracker
          │ 4 个任务都调 reportCompletion 后
          │ completionMap[tradeDate].size() == 4
          │ 触发发布事件 + 清理 entry
          │ 日志：[BatchTracker] tradeDate=20260729 收齐 4 个任务报告，发布 DataBatchReadyEvent
          │
          ▼
          publishEvent(DataBatchReadyEvent(tradeDate="20260729", source="SCHEDULED"))
          │
          ▼
          PrecomputeEventDispatcher 接收事件（@Async("precomputeExecutor")）
          │ 遍历所有 7 个 PrecomputeJob Bean
          │ 逐个调 precompute("20260729")，每个 Job 独立 try-catch
          │
          ├── SectorRankingPrecomputeJob.precompute("20260729")
          │   │ 调 swIndustryService.computeIndustryRanking("20260729")
          │   │ cacheManager.getCache("sectorRanking").put("20260729", result)
          │
          ├── SectorMoneyflowPrecomputeJob.precompute("20260729")
          ├── SectorValuationPrecomputeJob.precompute("20260729")
          ├── MarketIndicesPrecomputeJob.precompute("20260729")
          ├── MarketRankingPrecomputeJob.precompute("20260729")
          ├── MarketTemperaturePrecomputeJob.precompute("20260729")
          ├── MoneyflowRankingPrecomputeJob.precompute("20260729")
          │   │ 调 moneyflowService.computeQueryTop("20260729", 10, "main_net", "desc")
          │   │ cacheManager.getCache("moneyflowRanking").put("20260729_10_main_net_desc", result)
          │
          ▼
          同时（@Async 并行）：
          ├── FactorSnapshotTask.onBatchReady(event)  ← 不是 PrecomputeJob，独立监听
          │   │ 调 computeDaily("20260729")，写入 factor_snapshot 表
          │
          └── ScreenLockTrackingTask.onBatchReady(event)  ← 不是 PrecomputeJob，独立监听
              │ 调 track("20260729")，更新选股锁定追踪记录

16:01:00  预计算全部完成，Caffeine + factor_snapshot 已填充
          │
          ▼
用户访问 /api/industry/ranking
          │ @Cacheable 命中 sectorRanking:20260729
          │ 直接返回缓存值
          │ 响应时间 < 50ms

用户访问 /api/moneyflow/top?limit=10&sortBy=main_net&order=desc
          │ @Cacheable 命中 moneyflowRanking:20260729_10_main_net_desc
          │ 直接返回缓存值
          │ 响应时间 < 50ms
```

### 7.2 缓存未命中懒兜底流程

```
应用重启后，Caffeine 为空
          │
          ▼
用户访问 /api/industry/ranking
          │ @Cacheable 检查 sectorRanking:20260729 → 未命中
          │ 执行方法体：return computeIndustryRanking("20260729")
          │   │ 执行 5k 参数 SQL + 28 次循环查 index_daily + ...
          │   │ 耗时约 2-3 秒
          │   │ 返回 List<IndustryRankingVO>
          │ 结果被写入 Caffeine（key=20260729）
          │
          ▼
返回响应给用户（耗时 2-3s）
          │
          ▼
用户再次访问 /api/industry/ranking
          │ @Cacheable 命中 sectorRanking:20260729
          │ 直接返回缓存值
          │ 响应时间 < 50ms
```

### 7.3 数据管控中心查看定时任务流程

```
用户访问 /page/data-governance
          │
          ▼
前端加载"定时任务"分区
          │ GET /api/data-governance/scheduled-tasks
          │ 后端调 ScheduledTaskRegistryService.listScheduledTasks()
          │   │ 从内存中取 21 个被管理任务元信息
          │   │ 批量查 data_pull_log 取每个任务的最近一条记录
          │   │ 用 CronExpression 计算每个任务的 nextExecutionTime
          │ 返回 List<ScheduledTaskVO>
          │
          ▼
前端渲染表格（21 行）
          │ 每行显示：任务名 / 分组 / 关联表 / cron / 上次执行 / 状态 / 耗时 / 下次执行
          │
          ▼
用户点击"每日数据更新"行的"查看历史"按钮
          │ GET /api/data-governance/scheduled-tasks/com.arthur.stock.task.DailyUpdateTask/history?limit=30&page=1
          │ 后端查 data_pull_log WHERE task_class = 'com.arthur.stock.task.DailyUpdateTask' AND task_name IS NOT NULL ORDER BY start_time DESC LIMIT 30 OFFSET 0
          │
          ▼
前端弹出模态框，展示 30 条执行记录时间线（支持分页）
```

---

## 八、验收标准

### 8.1 定时任务管理

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | 应用启动后，日志输出 `[TaskRegistry] 已注册 21 个定时任务` | 启动应用观察日志 |
| 2 | `GET /api/data-governance/scheduled-tasks` 返回 21 条任务 | curl 调接口 |
| 3 | 每条任务包含 `cron` / `nextExecutionTime` / `lastExecutionTime` 字段 | 检查响应 JSON |
| 4 | 手动触发 `DailyUpdateTask.dailyUpdate()`，`data_pull_log` 表新增一条 SUCCESS 记录（`task_name` 非空，`trigger_type=SCHEDULED`） | 触发后查表 |
| 5 | 故意让任务抛异常，`data_pull_log` 表新增一条 FAILED 记录，errorMessage 非空 | 触发后查表 |
| 6 | 修改某任务 cron 不更新 InitStep，启动时打 WARN | 改 cron 后重启 |
| 7 | 凌晨 01:00 `MetricCleanupJob` 执行后，3 个月前的 `data_pull_log` 记录被清理（含定时任务和手动拉取两类） | 等到 01:00 后查表 |
| 8 | 前端数据管控页面显示"定时任务"分区，表格 21 行 | 访问 `/page/data-governance` |
| 9 | 点击"查看历史"按钮弹出模态框，显示最近 30 条执行记录 | UI 操作 |

### 8.2 批次完成事件机制

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | `DataBatchCompletionTracker` 在 4 个任务都调 `reportCompletion` 后发布 `DataBatchReadyEvent` | mock 4 个任务，断言事件发布 |
| 2 | 重复调用 `reportCompletion("DailyUpdateTask", tradeDate)` 不会重复发布事件 | 单元测试 |
| 3 | 4 个 task 类的 `@CacheEvict` 注解已移除 | 检查代码 |
| 4 | 4 个 task 类的方法末尾（finally 块）调 `batchCompletionTracker.reportCompletion` | 检查代码 |
| 5 | 任务方法抛异常时，finally 块仍调 `reportCompletion`（不阻塞批次） | mock 异常，断言报告被调用 |
| 6 | 批次发布时打 INFO 日志 `[BatchTracker] tradeDate={tradeDate} 收齐 4 个任务报告，发布 DataBatchReadyEvent` | 触发后观察日志 |

### 8.3 预计算

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | `DataBatchReadyEvent` 发布后，日志输出 7 个 PrecomputeJob 的执行记录 | 触发后观察日志 |
| 2 | 预计算完成后，`cacheManager.getCache("sectorRanking").get("20260729")` 返回非空 | 调试或加临时接口 |
| 3 | 调 `GET /api/industry/ranking`，响应时间 < 50ms | curl 计时 |
| 4 | 调 `GET /api/market/temperature`，响应时间 < 50ms | curl 计时 |
| 5 | `CacheConfig` 中 sectorRanking 等 TTL 为 24h | 检查代码 |
| 6 | `MoneyflowRankingPrecomputeJob` 执行后，`cacheManager.getCache("moneyflowRanking").get("20260729_10_main_net_desc")` 返回非空 | 调试或加临时接口 |
| 7 | 调 `GET /api/moneyflow/top?limit=10&sortBy=main_net&order=desc`，响应时间 < 50ms | curl 计时 |
| 8 | 调 `GET /api/moneyflow/top?limit=20&sortBy=main_net&order=desc`（非预计算参数），懒兜底执行并写入缓存 | curl 计时（首次 < 3s，二次 < 50ms） |
| 9 | `PrecomputeJob` 接口只含 `name()` 和 `precompute(tradeDate)` 两个方法，无 `dependsOnTables` | 检查代码 |
| 10 | `MarketRankingPrecomputeJob` 的 Javadoc 注明依赖 `daily_quote + daily_basic + stock_basic` | 检查代码 |

### 8.4 现有任务事件驱动改造

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | `FactorSnapshotTask` 的 `@Scheduled(cron = "0 30 16 * * MON-FRI")` 注解已移除 | 检查代码 |
| 2 | `FactorSnapshotTask` 改用 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent` | 检查代码 |
| 3 | `DataBatchReadyEvent` 发布后，`FactorSnapshotTask` 被触发执行，写入 `factor_snapshot` 表 | 触发后查表 |
| 4 | `ScreenLockTrackingTask` 的 `@Scheduled(cron = "0 30 16 * * ?")` 注解已移除 | 检查代码 |
| 5 | `ScreenLockTrackingTask` 改用 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent` | 检查代码 |
| 6 | `DataBatchReadyEvent` 发布后，`ScreenLockTrackingTask` 被触发执行，更新选股锁定追踪记录 | 触发后观察日志 |
| 7 | `FactorSnapshotTask` / `ScreenLockTrackingTask` 执行时，`data_pull_log` 表**不新增**记录（未加 `@ManagedTask`） | 触发后查表 |
| 8 | `FactorSnapshotTask` / `ScreenLockTrackingTask` 执行日志通过应用日志输出 | 观察日志 |

### 8.5 懒兜底

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | 重启应用（Caffeine 清空），调 `/api/industry/ranking` 返回正确数据 | 重启后 curl |
| 2 | 第二次调 `/api/industry/ranking` 响应时间 < 50ms | curl 计时 |
| 3 | `getIndustryRanking` 的 SpEL key 与 Job 显式 put 的 key 一致 | 调试日志或反编译 |

### 8.6 回归

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | 现有接口响应数据结构不变 | 对比改造前后响应 JSON |
| 2 | 现有定时任务执行不报错 | 触发各任务观察日志 |
| 3 | `TableStatusVO` 原有字段不变，新增字段为空时不影响前端展示 | 调 `/api/data-governance/tables` |
| 4 | `data_pull_log` / `data_governance_metric` 清理逻辑不受影响 | 凌晨 01:00 观察 |

### 8.7 评审补充验收标准（v2.1 新增）

#### 缓存 key 一致性 + Job 失败 evict

| # | 验收项 | 验证方法 |
|---|---|---|
| 1 | 7 个 Job 各自的 `@Cacheable` SpEL key 与 Job 显式 put 的 key 完全一致（含 `latest` 双写） | 调 `/admin/test/cache-keys` 验证每个 cacheName 含 `{tradeDate}` 和 `latest` 两个 key |
| 2 | Job 失败时主动 evict 缓存（`{tradeDate}` 和 `latest`），下次查询 MISS → 触发懒兜底 | mock Job 抛异常，调 `/admin/test/cache-keys` 验证 key 已 evict，再调查询接口验证懒兜底执行 |
| 3 | 无参查询（key=latest）命中预计算结果，响应时间 <50ms | 预计算完成后 curl 无参查询计时 |

#### tradeDate 捕获 + 非交易日/跨日边界

| # | 验收项 | 验证方法 |
|---|---|---|
| 4 | 4 个任务在方法入口捕获 tradeDate，finally 用已捕获变量（非重新取值） | 代码检查 + 单测 mock 跨日场景 |
| 5 | 非交易日 4 任务 cron 触发时早返回，finally 仍调 reportCompletion，预计算 Job 收到 SCHEDULED 事件对空数据返回空 List（不缓存） | mock 非交易日 + 触发任务，验证事件发布 + 缓存未写入空结果 |
| 6 | 跨日 00:00 边界 tradeDate 取值符合 Asia/Shanghai 时区规则 | 时间 mock 跨日场景，查询 tracker entry key |

#### 批次追踪器并发 + 防重复发布

| # | 验收项 | 验证方法 |
|---|---|---|
| 7 | 4 任务并发 reportCompletion（CountDownLatch 同步触发）只发布 1 次事件 | 并发测试 + ApplicationEventPublisher mock 计数 |
| 8 | 超时兜底发布后，迟到任务调 reportCompletion 不重复发布事件（fired 标志生效） | mock 超时 + 迟到报告，验证事件只发布 1 次 |
| 9 | 4 任务都报告完成但部分异常时，事件 source=SCHEDULED_PARTIAL | mock 1 任务 hasError=true，验证事件 source |

#### 数据完整性校验

| # | 验收项 | 验证方法 |
|---|---|---|
| 10 | source=SCHEDULED_PARTIAL 或 SCHEDULED_TIMEOUT 时，Job 检测数据不完整后跳过预计算并打 WARN | mock 不完整数据 + 触发事件，验证缓存未写入 + WARN 日志 |

#### 手动重跑 + 告警 + RUNNING 状态

| # | 验收项 | 验证方法 |
|---|---|---|
| 11 | `POST /scheduled-tasks/{taskClass}/run` 可触发任务，RUNNING 时拒绝（HTTP 409） | curl 调接口 + 并发触发验证拒绝 |
| 12 | 手动重跑记入 data_pull_log（`trigger_type=MANUAL`, `operator=当前用户名`），与定时触发的 `trigger_type=SCHEDULED` 自然区分（TriggerContext 机制生效） | 触发后查表 + 对比同任务定时触发记录 |
| 13 | DATA_FETCH/PRECOMPUTE 任务失败时触发告警（邮件/IM），同任务 30 分钟内只告警 1 次 | mock 任务失败 + 验证告警发送 + 30 分钟内重复失败验证防骚扰 |
| 14 | 任务执行中列表显示 RUNNING(蓝)，耗时实时刷新（前端轮询 5s） | 触发长任务 + 前端观察状态切换 + `currentStatus` 字段值正确 |
| 15 | `GET /scheduled-tasks/{taskClass}/history?page=2&limit=30` 返回第 2 页 30 条 + `total` 字段正确 | 造 100 条历史 + 翻页验证 |
| 16 | `GET /scheduled-tasks/{taskClass}/history?status=FAILED` 仅返回失败记录 | 造成功+失败各 N 条 + 筛选验证 |
| 17 | `GET /scheduled-tasks/{taskClass}/history?startDate=20260701` 仅返回 7 月后记录 | 造跨月记录 + 筛选验证 |

#### AOP 性能 + 异步落库

| # | 验收项 | 验证方法 |
|---|---|---|
| 18 | AOP 切面同步开销 P99 <1ms（基于 1000 次调用） | JMH 微基准 / Arthas trace |
| 19 | 异步写日志最大延迟 <1s；日志写入失败时不影响原方法（try-catch 兜底） | 异步队列监控 + mock DB 异常验证原方法正常返回 |

#### 启动校验 + 测试入口

| # | 验收项 | 验证方法 |
|---|---|---|
| 20 | `@ManagedTask` 的 tableCode 为空时启动 fail-fast | 删除某任务 tableCode + 启动验证失败 |
| 21 | `@ManagedTask.name` 重名时启动 fail-fast | 两个任务同名 + 启动验证失败 |
| 22 | `/admin/test/*` 测试 Controller 仅在 test profile 启用，生产环境不加载 | 生产 profile 启动验证 404 |
| 23 | `/admin/test/metric-cleanup?cutoff=` 手动触发清理，仅删 cutoff 之前记录 | 造 91 天前 + 89 天前记录 + 手动触发验证 |

#### Spring 调度器多线程

| # | 验收项 | 验证方法 |
|---|---|---|
| 24 | `spring.task.scheduling.pool.size=4` 配置生效，4 个 @Scheduled 任务可并行执行 | 检查配置 + 4 任务同时触发验证并行 |

#### data_pull_log 查询区分

| # | 验收项 | 验证方法 |
|---|---|---|
| 25 | `/scheduled-tasks/{taskClass}/history` 仅返回 `task_class` 匹配且 `task_name IS NOT NULL` 记录，不泄露手动拉取记录 | 造手动 + 定时各 N 条 + 查询验证 |

---

## 九、实施排期

### Phase A：定时任务整合层（Layer 1+2）

| # | 步骤 | 涉及文件 | 优先级 |
|---|---|---|---|
| A1 | 新建 `annotation/ManagedTask.java` | 新增 | P0 |
| A2 | 扩展 `DataPullLogDO` 新增 5 个字段 + 更新 `DataPullLogMapper` XML | 改 | P0 |
| A3 | 新建 `service/ScheduledTaskRegistryService.java` + impl（含 tableCode/name 启动校验） | 新增 | P0 |
| A4 | 新建 `aspect/TaskExecutionLogAspect.java`（复用 `DataPullLogMapper` 写入，try-catch 容错 + 失败告警） | 新增 | P0 |
| A5 | 新建 `dto/governance/ScheduledTaskVO.java`（含 cronReadable/taskGroupLabel/currentStatus/configInconsistent） | 新增 | P0 |
| A6 | SQL: `ALTER TABLE data_pull_log` 新增 5 个可空字段 + 索引 | 改 schema-mysql.sql | P0 |
| A7 | 给 21 处拉取类 `@Scheduled` 方法加 `@ManagedTask` 注解 | 改 7 个 task 类 | P0 |
| A8 | `DataGovernanceController` + Service 新增 4 个 scheduled-tasks 端点（含 POST run） | 改 | P0 |
| A9 | ~~`MetricCleanupJob` 清理逻辑无需改动~~（现有逻辑已覆盖） | 无 | — |
| A10 | 启动时 InitStep 一致性校验（WARN 不阻断）+ tableCode/name fail-fast | 在 ScheduledTaskRegistryServiceImpl 实现 | P0 |
| A11 | 配置 `spring.task.scheduling.pool.size=4`（评审新增） | 改 application.yml | P0 |
| A12 | 新建测试 Controller `/admin/test/*`（仅 test profile） | 新增 | P1 |

### Phase B：批次事件 + 预计算层（Layer 3+4+5）

| # | 步骤 | 涉及文件 | 优先级 |
|---|---|---|---|
| B1 | 新建 `event/DataBatchReadyEvent.java`（含 source 四态枚举） | 新增 | P0 |
| B2 | 新建 `service/precompute/DataBatchCompletionTracker.java`（含 fired 标志 + hasError 感知 + forceFireOnTimeout） | 新增 | P0 |
| B3 | 新建 `service/precompute/PrecomputeJob.java` 接口（`name()` + `precompute()` + `cacheName()` + `cacheKeys()`）+ `AbstractPrecomputeJob.java`（含异常 evict + 缓存双写） | 新增 | P0 |
| B4 | 新建 `config/PrecomputeAsyncConfig.java`（core=4/max=8/queue=20）+ 可选 `factorSnapshotExecutor` 隔离 | 新增 | P0 |
| B5 | 新建 `util/CacheKeyResolver.java`（含 `resolveMoneyflowRankingKey` + latest 后缀支持） | 新增 | P0 |
| B6 | 新建 `event/PrecomputeEventDispatcher.java`（监听 `DataBatchReadyEvent`，CompletableFuture.allOf 并发提交 + 数据完整性校验） | 新增 | P0 |
| B7 | 新建 `service/impl/PrecomputeServiceImpl.java`（仅 `precomputeNow` + `precomputeAll`） | 新增 | P0 |
| B8 | `SwIndustryServiceImpl` 拆分 get/compute 三对方法（自调用约束） | 改 | P0 |
| B9 | `MarketServiceImpl` 拆分 get/compute 三对方法 + `getMarketRanking` 加 `@Cacheable` | 改 | P0 |
| B10 | `MoneyflowServiceImpl` 拆分 `queryTop` / `computeQueryTop` + `queryTop` 加 `@Cacheable` + `MoneyflowService` 接口扩展 | 改 | P0 |
| B11 | 实现 7 个 `PrecomputeJob` 子类（含缓存双写 + 数据完整性校验） | 新增 | P0 |
| B12 | `CacheConfig` 调整 TTL（30min → 24h）+ 新增 `marketRanking` / `moneyflowRanking` cacheName | 改 | P0 |
| B13 | 4 个 task 类移除 `@CacheEvict` + finally 块调 `reportCompletion(taskKey, tradeDate, hasError)`（tradeDate 入口捕获） | 改 | P0 |
| B14 | `DataBatchCompletionTracker` 超时兜底（`@Scheduled` 30 分钟检查 + forceFireOnTimeout） | 新增 | P1 |

### Phase C：前端整合

| # | 步骤 | 涉及文件 | 优先级 |
|---|---|---|---|
| C1 | 数据管控页面新增"定时任务"分区（含三态/状态筛选/历史分页/cron 可读化/重跑按钮/配置异常徽标） | 改 html/js/css | P1 |
| C2 | `TableStatusVO` 新增 `nextExecutionTime` / `lastExecutionTime` / `cron` 字段 | 改 | P1 |
| C3 | 数据表总览表格新增"下次执行"列 | 改前端 | P1 |
| C4 | RUNNING 状态实时刷新（前端轮询 5s） | 改前端 | P1 |
| C5 | errorMessage 折叠展示 + 脱敏后信息红色高亮 | 改前端 | P1 |

### Phase D：测试

| # | 步骤 | 优先级 |
|---|---|---|
| D1 | `ScheduledTaskRegistryServiceTest`：验证 21 个任务全部被解析 + tableCode/name fail-fast | P1 |
| D2 | `TaskExecutionLogAspectTest`：验证 AOP 切面正确记录成功/失败 + 日志写入容错 + 失败告警 | P1 |
| D3 | `DataBatchCompletionTrackerTest`：验证 4 任务报告后发布事件、重复报告去重、fired 防重复、hasError 感知、超时兜底 | P1 |
| D4 | `PrecomputeEventDispatcherTest`：mock Job 列表，验证并发提交 + 失败隔离 + 数据完整性校验 | P1 |
| D5 | `SectorRankingPrecomputeJobTest`：mock Service，验证缓存双写 + 异常 evict | P1 |
| D6 | `MoneyflowRankingPrecomputeJobTest`：mock Service，验证固定参数缓存双写 | P1 |
| D7 | 懒兜底验证：清缓存后查 7 个 Job 对应接口，验证方法体执行且结果写缓存（全 Job 覆盖） | P1 |
| D8 | 并发测试：4 任务 CountDownLatch 同步触发 reportCompletion，验证事件只发布 1 次 | P1 |
| D9 | 非交易日/跨日场景：mock 交易日历 + 时间 mock，验证 tracker 行为 + 预计算空数据不缓存 | P1 |
| D10 | AOP 性能测试：JMH 微基准验证 P99 <1ms | P1 |
| D11 | 手动重跑测试：POST run 接口 + RUNNING 拒绝 + trigger_type=MANUAL | P1 |

### Phase E：现有任务事件驱动改造

| # | 步骤 | 涉及文件 | 优先级 |
|---|---|---|---|
| E1 | `FactorSnapshotTask`：移除 `@Scheduled`，改为 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent` | 改 | P0 |
| E2 | `ScreenLockTrackingTask`：移除 `@Scheduled`，改为 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent` | 改 | P0 |
| E3 | 验证改造后两个任务由 `DataBatchReadyEvent` 触发执行，不再受 cron 时序约束 | 测试 | P1 |

---

## 十、风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| AOP 切面影响所有 `@Scheduled` 方法性能 | 低 | 切面只做时间记录 + 异步写日志，同步部分 < 1ms |
| `ScheduledTaskHolder` 反射失败 | 低 | 启动时 try-catch，失败降级为空列表 + WARN 日志 |
| 预计算 Job 失败导致缓存为空 | 中 | 懒兜底机制保证查询时仍能拿到数据 |
| 事件分发器异常导致所有 Job 不触发 | 中 | Dispatcher 加 try-catch；懒兜底兜底 |
| **批次完成追踪器单点故障** | 中 | `DataBatchCompletionTracker` 是单例 Bean，若其内部 `ConcurrentHashMap` 异常或 `ApplicationEventPublisher` 失败，会导致整批预计算不触发。缓解：①超时兜底机制（P1）30 分钟内未收齐则强制发布；②懒兜底保证查询可用；③ Tracker 代码极简（约 30 行），单测覆盖率高 |
| Caffeine 重启后缓存清空 | 低 | 懒兜底自动重算，无需特殊处理 |
| `@Async` 线程池满 | 低 | `CallerRunsPolicy` 降级为同步执行，不丢任务 |
| Job 重复执行（同一 tradeDate 多次事件） | 低 | `AbstractPrecomputeJob` 内部 ConcurrentHashMap 去重 |
| `data_pull_log` 表膨胀（复用后写入量增加） | 低 | 3 个月保留期，现有 `MetricCleanupJob` 已覆盖清理；21 个定时任务每日写入约 21 条，量级可忽略 |
| 任务单步失败仍报告完成，预计算读到不完整数据 | 中 | `computeXXX` 方法对空数据兜底返回空列表；批次事件语义是"4 个任务流程结束"，不保证所有数据都更新成功 |
| `FactorSnapshotTask` / `ScreenLockTrackingTask` 与 7 个 Job 共享线程池导致资源争抢 | 低 | `precomputeExecutor` 队列 50 + `CallerRunsPolicy` 兜底；高峰期降级为同步，不丢任务 |

---

## 十一、附录

### 11.1 本次覆盖的 21 个拉取类 @Scheduled 方法映射表

> 仅这 21 个方法加 `@ManagedTask` 注解、纳入统一管理、记录执行日志。其他 4 个非拉取类任务（`DataGovernanceCheckJob` / `DataVerifyTask` / `MetricCleanupJob` / `DataSourceHealthJob`）不在本次范围；`FactorSnapshotTask` / `ScreenLockTrackingTask` 已改造为事件驱动（详见模块 G），不再走 `@Scheduled`。

| Task 类 | 方法 | tableCode | name | group |
|---|---|---|---|---|
| DailyUpdateTask | dailyUpdate | daily | 每日数据更新 | DATA_FETCH |
| BasicDataTask | fetchDailyBasic | daily_basic | 每日基本面/估值更新 | DATA_FETCH |
| BasicDataTask | fetchFinaIndicator | fina_indicator | 财务指标更新 | DATA_FETCH |
| BasicDataTask | fetchIncome | income | 利润表更新 | DATA_FETCH |
| BasicDataTask | fetchBalancesheet | balancesheet | 资产负债表更新 | DATA_FETCH |
| BasicDataTask | fetchCashflow | cashflow | 现金流量表更新 | DATA_FETCH |
| BasicDataTask | fetchForecast | forecast | 业绩预告更新 | DATA_FETCH |
| BasicDataTask | fetchExpress | express | 业绩快报更新 | DATA_FETCH |
| BasicDataTask | fetchStkHoldernumber | stk_holdernumber | 股东人数更新 | DATA_FETCH |
| BasicDataTask | fetchStkHoldertrade | stk_holdertrade | 股东增减持更新 | DATA_FETCH |
| MoneyflowDataTask | fetchDailyMoneyflowData | moneyflow | 资金流数据更新 | DATA_FETCH |
| IndexBasicTask | syncDaily | index_basic | 指数基础信息同步 | DATA_FETCH |
| IndexDailyFetchService | dailySync | index_daily | 指数日线同步 | DATA_FETCH |
| IndexWeightTask | syncDaily | index_weight | 指数权重同步 | DATA_FETCH |
| StockNamechangeTask | dailyIncremental | namechange | 更名增量同步 | DATA_FETCH |
| StockNamechangeTask | quarterlyFull | namechange | 更名季度全量 | DATA_FETCH |
| StockSuspendDTask | dailyIncremental | suspend_d | 停复牌增量同步 | DATA_FETCH |
| StockSuspendDTask | monthlyFull | suspend_d | 停复牌月度全量 | DATA_FETCH |
| StockStkLimitTask | dailyIncremental | stk_limit | 涨跌停价增量同步 | DATA_FETCH |
| StockStkLimitTask | monthlyFull | stk_limit | 涨跌停价月度全量 | DATA_FETCH |
| SwIndustryTask | syncHalfYearly | sw_industry | 申万行业半年同步 | DATA_FETCH |

### 11.2 不在本次范围的 4 个非拉取类任务

| Task 类 | 方法 | 不纳入原因 |
|---|---|---|
| DataGovernanceCheckJob | executeCheck | 数据检测任务，非拉取 |
| DataVerifyTask | verify | 数据校验任务，非拉取 |
| MetricCleanupJob | cleanupOldData | 清理任务，非拉取 |
| DataSourceHealthJob | testConnectivity | 健康检查任务，非拉取 |

> 说明：`FactorSnapshotTask` 和 `ScreenLockTrackingTask` 原列于此表，本次改造已转为事件驱动（订阅 `DataBatchReadyEvent`），详见模块 G。

### 11.3 现有缓存命名空间一览（调整后）

| cacheName | TTL | key 策略 | 写入方 |
|---|---|---|---|
| `sectorRanking` | 24h | `{tradeDate}` 或 `latest` | SectorRankingPrecomputeJob + 懒兜底 |
| `sectorMoneyflow` | 24h | `{tradeDate}` 或 `latest` | SectorMoneyflowPrecomputeJob + 懒兜底 |
| `sectorValuation` | 24h | `{tradeDate}` 或 `latest` | SectorValuationPrecomputeJob + 懒兜底 |
| `marketRanking` | 24h | `{latestTradeDate}` | MarketRankingPrecomputeJob + 懒兜底 |
| `moneyflowRanking` | 24h | `{tradeDate}_{limit}_{sortBy}_{order}` | MoneyflowRankingPrecomputeJob（固定参数）+ 懒兜底（任意参数） |
| `marketTemperature` | 无 TTL（容量 50） | `{tradeDate}` | MarketTemperaturePrecomputeJob + 懒兜底 |
| `indices` | 无 TTL（容量 50） | `{latestTradeDate}` | MarketIndicesPrecomputeJob + 懒兜底 |
| `kline` | 无 TTL | `{code}::{period}::{adj}::{start}::{end}` | 保持现状（DailyUpdateTask 末尾清空） |
| `factorList` / `factorDetail` / `factorCategories` | 5min | 不变 | 保持现状 |
| `tradeCalendar` / `latestTradeDate` | 1 day | 不变 | 保持现状 |
| `stockBasicName` | 1 day | 不变 | 保持现状 |

### 11.4 扩展指南

#### 新增预计算接口（已有数据源）
1. 在对应 Service 中拆分 `getXXX`（带 `@Cacheable`）和 `computeXXX`（无注解）
2. 实现 `PrecomputeJob` 接口（仅 `name()` + `precompute(tradeDate)`），注册为 `@Component`
3. 在 `doPrecompute()` 中调 `computeXXX` + `cacheManager.put`
4. 在 Job 的 Javadoc 中注明实际依赖的数据表（仅供查阅，不参与路由）
5. 完成：`PrecomputeEventDispatcher` 自动发现并接入，监听 `DataBatchReadyEvent` 触发执行

#### 新增数据更新任务
1. 在 task 类的 `@Scheduled` 方法上加 `@ManagedTask(name, tableCode, group, description)`
2. 在方法入口立即捕获 `String tradeDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);`，方法末尾的 finally 块中调 `batchCompletionTracker.reportCompletion("类名.方法名", tradeDate, hasError)`（taskKey 必须用 `类名.方法名` 格式，与 `EXPECTED_TASKS` 严格对齐）
3. 如需将该任务纳入批次事件链，在 `DataBatchCompletionTracker.EXPECTED_TASKS` 中加入该 `类名.方法名`（保持 4 个核心任务的预期集合不变，新增的拉取任务**只加 @ManagedTask 不进 EXPECTED_TASKS**，否则会破坏批次完成判定）
4. 完成：任务元信息自动注册到数据管控中心；若已加入 `EXPECTED_TASKS`，则批次事件链自动接入

#### 新增"数据更新 + 预计算"组合
1. 实现 task 类拉取数据 + `@ManagedTask` + finally 调 `reportCompletion("类名.方法名", tradeDate, hasError)`
2. 实现 `PrecomputeJob` 订阅 `DataBatchReadyEvent`（仅当任务已加入 `EXPECTED_TASKS` 时事件才会被触发）
3. 无需修改框架代码
