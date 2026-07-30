# 数据预计算与定时任务统一管理 Spec

> **来源 PRD**：`sdlc/prd/数据预计算与定时任务统一管理PRD.md`（v2.2，已 Reviewed）
> **关联设计文档**：`.trae/documents/数据预计算架构设计.md`（PRD 中引用，当前不存在，本 spec 不依赖该文档存在）

## Why

stock-pulse 当前存在三个突出架构问题，本变更旨在系统性解决：

1. **实时聚合 SQL 性能瓶颈**：板块行情、市场总览等接口首屏或缓存过期后触发 5000 参数 `IN` 查询 + 28 次循环查 `index_daily`，虽 Caffeine 30min TTL 兜底但首屏慢；这些数据每日 16:00 后不变，应预计算。
2. **定时任务分散难管理**：21 处拉取 Tushare 的 `@Scheduled` 方法 cron 硬编码在注解中，无统一视图查 cron/上次/下次执行，`InitStep.updateFrequency` 是人工字符串易与实际脱钩，失败只能翻日志。
3. **多表依赖预计算触发时序问题**：原"按表粒度发事件"设计会让多表依赖的 Job 在某张表刚更新完就跑、用到其他表旧数据。需改为"整批数据更新完成"触发。

## What Changes

### 模块 A：定时任务统一管理
- 新增 `@ManagedTask` 注解 + `ScheduledTaskRegistryService`（启动反射 Spring `ScheduledTaskHolder` + `@ManagedTask` 元信息，计算 nextExecutionTime）
- 新增 `TaskExecutionLogAspect`（`@Around` 切面，复用 `data_pull_log` 表新增 5 个可空字段异步写入；维护 `ConcurrentHashMap<taskClass, RunningStatus>` 实时感知 RUNNING）
- 新增 `TriggerContext`（ThreadLocal）区分 SCHEDULED vs MANUAL + operator
- 启动校验：`tableCode` 非空 + `name` 唯一性 fail-fast；cron 与 `InitStep.expectedUpdateTime` 不一致 WARN
- 给 21 处拉取类 `@Scheduled` 方法加 `@ManagedTask`（其他 4 个非拉取类任务不动）
- 配置 `spring.task.scheduling.pool.size=4`（默认单线程会导致 4 个 16:00 任务串行排队）
- 复用 `MetricCleanupJob` 清理 `data_pull_log`（3 个月保留期，无需改动）

### 模块 B：数据批次完成事件机制
- 新增 `DataBatchReadyEvent`（`tradeDate` + `source` ∈ {SCHEDULED / SCHEDULED_TIMEOUT / SCHEDULED_PARTIAL / MANUAL}）
- 新增 `DataBatchCompletionTracker`：追踪 4 个核心数据更新任务（`DailyUpdateTask.dailyUpdate` / `BasicDataTask.fetchDailyBasic` / `MoneyflowDataTask.fetchDailyMoneyflowData` / `IndexDailyFetchService.dailySync`），全部完成后发布事件；`fired` 标志防重复；`hasError` 感知 SCHEDULED_PARTIAL；30 分钟超时兜底
- 改造 4 个 task 类：方法入口捕获 tradeDate（Asia/Shanghai）→ finally 块调 `reportCompletion(taskKey, tradeDate, hasError)`；移除原 `@CacheEvict`
- 废弃原 `DataUpdatedEvent(tableCode, ...)` 按表粒度事件设计

### 模块 C：预计算框架
- 新增 `PrecomputeJob` 接口（`name()` + `precompute(tradeDate)`，**无 `dependsOnTables()`**——所有 Job 订阅同一事件，依赖关系只在 Javadoc 注明供查阅）
- 新增 `AbstractPrecomputeJob`（模板方法 + `(jobName, tradeDate)` 去重 + 异常时主动 evict 缓存 + 缓存双写 `{tradeDate}` & `latest`）
- 新增 `PrecomputeEventDispatcher`（`@EventListener` + `@Async("precomputeExecutor")`，`CompletableFuture.allOf` 并发提交 7 Job；source 为 PARTIAL/TIMEOUT 时 Job 内做数据完整性校验）
- 实现 7 个具体 Job：SectorRanking / SectorMoneyflow / SectorValuation / MarketIndices / MarketRanking / MarketTemperature / MoneyflowRanking（固定 limit=10/sortBy=main_net/order=desc）
- 新增 `precomputeExecutor` 线程池（core=4/max=8/queue=20/CallerRunsPolicy）；可选 `factorSnapshotExecutor` 隔离 FactorSnapshot
- 新增 `PrecomputeService`（`precomputeNow` 懒兜底 + `precomputeAll` 运维排错）

### 模块 D：Service 层拆分 get/compute
- `SwIndustryServiceImpl` / `MarketServiceImpl` / `MoneyflowServiceImpl` 各聚合方法拆为 `getXXX`（带 `@Cacheable`）+ `computeXXX`（无注解，public，不互相调用避免 AOP 失效）
- `@Cacheable unless = "#result == null || #result.isEmpty()"`（空结果不缓存）
- `MarketServiceImpl.getMarketRanking` 新增 `@Cacheable`（当前无缓存）
- `MoneyflowService` 接口扩展 `computeQueryTop`
- 新增 `CacheKeyResolver` 工具类（SpEL 与 Job 显式 put 共用，保证 key 一致 + latest 双写）

### 模块 E：缓存配置调整
- `CacheConfig` TTL：sectorRanking/sectorMoneyflow/sectorValuation/marketRanking/moneyflowRanking → 24h；marketTemperature/indices 加 `maximumSize(50)`
- 移除 4 个 task 类上的 `@CacheEvict`（由预计算主动 put 覆盖）

### 模块 F：数据管控中心扩展
- `DataGovernanceController` 新增 4 个端点：
  - `GET /api/data-governance/scheduled-tasks`（列表 + group/keyword 过滤）
  - `GET /api/data-governance/scheduled-tasks/{taskClass}`（详情）
  - `GET /api/data-governance/scheduled-tasks/{taskClass}/history`（历史，支持 page/limit/status/startDate 分页筛选）
  - `POST /api/data-governance/scheduled-tasks/{taskClass}/run`（管理员手动重跑，RUNNING 拒绝 HTTP 409）
- 新增 `ScheduledTaskVO`（含 cronReadable / taskGroupLabel / currentStatus / configInconsistent）
- `TableStatusVO` 新增 cron / nextExecutionTime / lastExecutionTime 字段（向后兼容）
- 前端 `data-governance.html` 新增"定时任务"分区：表格列 + 三态（loading/空/错误）+ 状态筛选 + 历史分页模态框 + cron 可读化 + 重跑按钮（RUNNING 禁用）+ 配置异常徽标 + RUNNING 蓝色实时刷新（轮询 5s）+ errorMessage 折叠脱敏
- 数据表总览表格新增"下次执行"列
- 任务失败告警（DATA_FETCH/PRECOMPUTE 组，邮件/IM，30 分钟防骚扰）
- 测试 Profile 下 `/admin/test/*` Controller（trigger-batch-event / trigger-task / metric-cleanup / precompute-all / cache-keys / cache-evict）

### 模块 G：现有任务事件驱动改造
- `FactorSnapshotTask`：移除 `@Scheduled`，改为 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`；不加 `@ManagedTask`，不进 `data_pull_log`，靠自身日志
- `ScreenLockTrackingTask`：同上

### 数据库变更
- **BREAKING（向后兼容）**：`data_pull_log` 表新增 5 个可空字段 + 1 个索引：
  - `task_name VARCHAR(64) NULL`
  - `task_class VARCHAR(128) NULL`
  - `method_name VARCHAR(64) NULL`
  - `task_group VARCHAR(32) NULL`
  - `trigger_type VARCHAR(16) NULL`
  - `INDEX idx_task_name_time (task_name, start_time)`
- 不新建表，不修改现有字段约束（`table_code`/`table_name` NOT NULL 保持，因 21 个任务都有 tableCode）

## Impact

### Affected specs
- `021-data-governance-center`：本变更在其基础上新增"定时任务"分区与 4 个 API 端点
- `019-sector-market`：板块行情接口由实时聚合改为预计算 + 懒兜底
- `017-market-center-completion`：市场总览接口同上
- `020-moneyflow-module`：资金流向 queryTop 加 `@Cacheable` + 预计算

### Affected code（关键文件清单）
- **新增**：
  - `annotation/ManagedTask.java`
  - `service/ScheduledTaskRegistryService.java` + impl
  - `aspect/TaskExecutionLogAspect.java`
  - `dto/governance/ScheduledTaskVO.java`
  - `util/TriggerContext.java`
  - `event/DataBatchReadyEvent.java`
  - `service/precompute/DataBatchCompletionTracker.java`
  - `service/precompute/PrecomputeJob.java` + `AbstractPrecomputeJob.java`
  - `event/PrecomputeEventDispatcher.java`
  - `service/impl/PrecomputeServiceImpl.java`
  - `config/PrecomputeAsyncConfig.java`
  - `util/CacheKeyResolver.java`
  - 7 个 `PrecomputeJob` 子类（`service/precompute/jobs/`）
  - 测试 Controller `controller/admin/TestAdminController.java`（@Profile("test")）
- **改**：
  - `DataGovernanceController` + Service（4 个新端点）
  - `DataPullLogDO` + `DataPullLogMapper.xml`（5 字段）
  - 7 个 task 类（21 处方法加 `@ManagedTask`）
  - 4 个核心 task 类（finally 调 `reportCompletion`，移除 `@CacheEvict`）
  - `SwIndustryServiceImpl` / `MarketServiceImpl` / `MoneyflowServiceImpl`（get/compute 拆分）
  - `MoneyflowService` / `MarketService` / `SwIndustryService` 接口（新增 computeXXX 签名）
  - `CacheConfig`（TTL + 新 cacheName）
  - `FactorSnapshotTask` / `ScreenLockTrackingTask`（cron → 事件）
  - `application.yml`（`spring.task.scheduling.pool.size: 4`）
  - `schema-mysql.sql`（ALTER TABLE）
  - 前端 `data-governance.html` + js + css
- **不动**：4 个非拉取类任务（`MetricCleanupJob` / `DataSourceHealthJob` / `DataGovernanceCheckJob` / `DataVerifyTask`），`InitStep` 枚举结构，`factor_snapshot` 持久化逻辑

## ADDED Requirements

### Requirement: @ManagedTask 注解与任务元信息注册
系统 SHALL 提供 `@ManagedTask` 注解（method-level / RUNTIME），含必填 `name`/`group` 与可选 `tableCode`/`description`，`group` 取值枚举 `DATA_FETCH/GOVERNANCE/MAINTENANCE/PRECOMPUTE/VERIFY`。`ScheduledTaskRegistryService` SHALL 在 `@PostConstruct` 通过 Spring `ScheduledTaskHolder` 反射所有 `@Scheduled` 任务并关联 `@ManagedTask`，用 `CronExpression.parse(cron).next(now)` 计算 nextExecutionTime。反射失败 SHALL try-catch + WARN + 返回空列表不阻断启动。

#### Scenario: 给现有任务加注解自动注册
- **WHEN** 开发者在 `DailyUpdateTask.dailyUpdate` 加 `@ManagedTask(name="每日数据更新", tableCode="daily", group="DATA_FETCH", description="...")`
- **THEN** 启动时该任务被 `ScheduledTaskRegistryService` 自动注册
- **AND** 日志输出 `[TaskRegistry] 已注册 21 个定时任务`
- **AND** `/api/data-governance/scheduled-tasks` 能查询到该任务

#### Scenario: 反射失败降级
- **WHEN** `ScheduledTaskHolder` 反射异常
- **THEN** 打 WARN 日志，返回空列表，应用正常启动

### Requirement: 启动校验（tableCode/name fail-fast + cron 一致性 WARN）
系统 SHALL 在 `ScheduledTaskRegistryService` 初始化时校验：①所有 `@ManagedTask` 的 `tableCode` 非空（`data_pull_log.table_code` NOT NULL，AOP 异步写库会因约束抛 SQLException 丢日志）→ fail-fast；②`@ManagedTask.name` 全局唯一（前端路径参数 + 历史查询依赖）→ fail-fast；③对带 tableCode 的任务，cron 解析的执行时间与 `InitStep.fromCode(tableCode).expectedUpdateTime` 一致 → 不一致打 WARN 不阻断。

#### Scenario: tableCode 空启动失败
- **WHEN** 某 `@ManagedTask` 的 `tableCode` 为空
- **THEN** 应用启动 fail-fast

#### Scenario: name 重名启动失败
- **WHEN** 两个 `@ManagedTask` 同名
- **THEN** 应用启动 fail-fast

#### Scenario: cron 与 InitStep 不一致
- **WHEN** `DailyUpdateTask.dailyUpdate` cron 改为 `0 30 16 * * ?` 但未同步 `InitStep.DAILY.expectedUpdateTime`
- **THEN** 启动打 WARN `[TaskRegistry] 任务 每日数据更新 的 cron 0 30 16 * * ? 与 InitStep.daily.expectedUpdateTime=16:00 不一致，请同步更新`

### Requirement: TaskExecutionLogAspect 切面与异步日志
系统 SHALL 提供 `TaskExecutionLogAspect`（`@Around` 拦截所有 `@ManagedTask` 方法），切面入口读 `TriggerContext.get()` 区分 SCHEDULED（ThreadLocal 空，operator=SYSTEM）vs MANUAL（ThreadLocal 有值，operator=当前用户）；记录字段含共用 14 项 + 新增 5 项（task_name/task_class/method_name/task_group/trigger_type）；异步写入 `data_pull_log`；原方法抛异常时记 status=FAILED + error_message（截断 1024）+ error_stack（截断 8192）并重新抛出；日志写入操作 try-catch 容错，写入失败只 ERROR 不影响原方法返回值/异常。切面入口在 `ConcurrentHashMap<taskClass, RunningStatus>` 中 set startTime，出口 clear，供 `ScheduledTaskRegistryService` 填充 `currentStatus` + 实时 `lastDurationMs`。

#### Scenario: 任务正常执行
- **WHEN** `DailyUpdateTask.dailyUpdate` 正常完成（12 秒）
- **THEN** `data_pull_log` 新增一条：status=SUCCESS, duration_ms=12000, trigger_type=SCHEDULED, task_name=每日数据更新, operation_type=SCHEDULED
- **AND** 任务主流程不被阻塞

#### Scenario: 任务执行失败
- **WHEN** `DailyUpdateTask.dailyUpdate` 抛 RuntimeException
- **THEN** `data_pull_log` 新增 FAILED 记录（error_message 非空，截断 1024）
- **AND** 原异常被重新抛出

#### Scenario: 日志写入失败容错
- **WHEN** AOP 切面内日志写入 DB 异常
- **THEN** 只打 ERROR 日志，原方法返回值和异常不受影响

### Requirement: DataBatchReadyEvent 与批次完成追踪
系统 SHALL 提供 `DataBatchReadyEvent`（extends ApplicationEvent），含 `tradeDate`(yyyyMMdd) + `source`(SCHEDULED/SCHEDULED_TIMEOUT/SCHEDULED_PARTIAL/MANUAL)。`DataBatchCompletionTracker` SHALL 追踪 4 个核心任务（taskKey 格式 `类名.方法名`：`DailyUpdateTask.dailyUpdate` / `BasicDataTask.fetchDailyBasic` / `MoneyflowDataTask.fetchDailyMoneyflowData` / `IndexDailyFetchService.dailySync`），全部完成后发布事件；`fired` 标志防重复发布；`hasError` 感知 SCHEDULED_PARTIAL；`ConcurrentHashMap` + `ConcurrentHashMap.newKeySet()` 线程安全；发布后清理 entry 防内存泄漏。废弃原按表粒度 `DataUpdatedEvent` 设计。

#### Scenario: 4 任务依次报告触发事件
- **WHEN** 4 个核心任务对同一 tradeDate 都调 `reportCompletion`
- **THEN** 第 4 次调用触发发布 `DataBatchReadyEvent(tradeDate, source)`（无异常 source=SCHEDULED，部分异常 source=SCHEDULED_PARTIAL）
- **AND** entry 被清理，打 INFO 日志

#### Scenario: 重复报告去重
- **WHEN** 同一 taskKey 对同一 tradeDate 多次调 `reportCompletion`
- **THEN** Set 中只计一次，不重复发布事件

#### Scenario: 超时兜底后迟到报告不重复
- **WHEN** 30 分钟超时强制发布后某迟到任务才调 `reportCompletion`
- **THEN** `fired` 标志生效，不重复发布事件

### Requirement: 4 个核心任务改造（tradeDate 入口捕获 + finally 报告 + 移除 @CacheEvict）
系统 SHALL 改造 4 个核心任务：方法入口立即捕获 `String tradeDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);`，finally 块用此已捕获变量调 `reportCompletion(taskKey, tradeDate, hasError)`；移除原 `@CacheEvict` 注解（由预计算主动 put 覆盖）；非交易日 cron 触发时任务方法早返回（不拉数），finally 仍调 reportCompletion（预计算 Job 对空数据返回空 List，`unless` 不缓存）。

#### Scenario: 任务正常完成报告
- **WHEN** `DailyUpdateTask.dailyUpdate` 5 步全部完成
- **THEN** finally 调 `reportCompletion("DailyUpdateTask.dailyUpdate", tradeDate, false)`
- **AND** 原 `@CacheEvict(value={"sectorRanking","sectorMoneyflow","sectorValuation"}, allEntries=true)` 已移除

#### Scenario: 单步失败仍报告完成
- **WHEN** `updateDailyQuotes` 步骤抛异常
- **THEN** 该步 try-catch 捕获，后续步骤继续，finally 仍调 reportCompletion（hasError=true），不阻塞批次

### Requirement: PrecomputeJob 接口与 AbstractPrecomputeJob 模板
系统 SHALL 提供 `PrecomputeJob` 接口（`name()` + `precompute(tradeDate)`，**不含 `dependsOnTables()`**）。`AbstractPrecomputeJob` SHALL 实现模板方法：`(jobName, tradeDate)` 去重（ConcurrentHashMap）→ 记录 startTime → 调子类 `doPrecompute` → 记录耗时 → 异常时打 ERROR + 主动 evict `{tradeDate}` 和 `latest` 两 key → finally 清除去重标记。子类实现 `doPrecompute` + `cacheName()` + `cacheKeys(tradeDate)`，`doPrecompute` 内对每个缓存 put 两个 key（`{tradeDate}` 与 `latest`）。

#### Scenario: Job 重复触发去重
- **WHEN** 同一 (jobName, tradeDate) 正在执行中又收到相同 tradeDate 事件
- **THEN** 第二次触发跳过，打 INFO 日志，不影响第一次执行

#### Scenario: Job 失败主动 evict 缓存
- **WHEN** `SectorRankingPrecomputeJob.doPrecompute("20260729")` 抛异常
- **THEN** `AbstractPrecomputeJob` 捕获 + 打 ERROR + `cacheManager.getCache("sectorRanking").evict("20260729")` + `evict("latest")`
- **AND** 下次查询 MISS → 触发懒兜底重算

### Requirement: PrecomputeEventDispatcher 并发分发与失败隔离
系统 SHALL 提供 `PrecomputeEventDispatcher`（`@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`），收到事件后用 `CompletableFuture.allOf` 并发提交所有 Job 到 `precomputeExecutor`（7 Job 并发，首屏完成时间降为单 Job 最长耗时）；source 为 SCHEDULED_PARTIAL/SCHEDULED_TIMEOUT 时 Job 在 `doPrecompute` 内做数据完整性校验（如 daily_quote 当日记录数 > 0），不完整时跳过预计算打 WARN；单 Job try-catch 失败隔离，不影响其他 Job。

#### Scenario: 批次事件触发全部 Job
- **WHEN** 收到 `DataBatchReadyEvent(tradeDate="20260729")`
- **THEN** 并发触发 7 个 Job，每个独立 try-catch
- **AND** 任一 Job 失败不影响其他 Job

#### Scenario: 数据不完整跳过预计算
- **WHEN** event.source=SCHEDULED_PARTIAL 且 daily_quote 当日记录数为 0
- **THEN** Job 跳过预计算，打 WARN，缓存未写入脏数据

### Requirement: 7 个 PrecomputeJob 实现（缓存双写 + 固定参数）
系统 SHALL 实现 7 个具体 Job：SectorRanking / SectorMoneyflow / SectorValuation / MarketIndices / MarketRanking / MarketTemperature / MoneyflowRanking。每个 Job 继承 `AbstractPrecomputeJob`，注入对应 Service + CacheManager，`doPrecompute` 内调 `computeXXX` → 用 `CacheKeyResolver` 算 key → 双写 `put("{tradeDate}", result)` + `put("latest", result)`；实现 `cacheName()` 与 `cacheKeys(tradeDate)` 供异常 evict。`MoneyflowRankingPrecomputeJob` 只预计算固定参数 `limit=10/sortBy=main_net/order=desc`，其他参数走懒兜底。`MarketRankingPrecomputeJob` 的 Javadoc 注明实际依赖 `daily_quote + daily_basic + stock_basic`（仅供查阅，不参与路由）。

#### Scenario: SectorRankingPrecomputeJob 执行
- **WHEN** `doPrecompute("20260729")` 被调用
- **THEN** 调 `swIndustryService.computeIndustryRanking("20260729")` 拿结果
- **AND** `cacheManager.getCache("sectorRanking").put("20260729", result)` + `put("latest", result)`（双写）
- **AND** 打 INFO 日志 `[Precompute][SectorRanking] tradeDate=20260729 耗时=XXXms 结果=success`

#### Scenario: MoneyflowRankingPrecomputeJob 固定参数
- **WHEN** `doPrecompute("20260729")` 被调用
- **THEN** 调 `moneyflowService.computeQueryTop("20260729", 10, "main_net", "desc")`
- **AND** 缓存 key 为 `20260729_10_main_net_desc` + `latest_10_main_net_desc`（双写）

### Requirement: Service 层 get/compute 拆分与自调用约束
系统 SHALL 在 `SwIndustryServiceImpl`/`MarketServiceImpl`/`MoneyflowServiceImpl` 把每个聚合方法拆为 `getXXX`（带 `@Cacheable`，`unless = "#result == null || #result.isEmpty()"`）+ `computeXXX`（无注解，public，**不互相调用**避免 this 调用绕过 Spring AOP 导致 @Cacheable 失效）。`getXXX` 方法体只有一行 `return computeXXX(...)`。`MarketServiceImpl.getMarketRanking` 新增 `@Cacheable`（当前无缓存）。`MoneyflowService` 接口扩展 `computeQueryTop`。新增 `CacheKeyResolver`（SpEL 与 Job 显式 put 共用，null tradeDate 返回 `latest`）。

#### Scenario: 缓存命中直接返回
- **WHEN** 用户调 `getIndustryRanking("20260729")` 且 Caffeine `sectorRanking:20260729` 已有值
- **THEN** Spring AOP 拦截直接返回缓存值，`computeIndustryRanking` 不执行

#### Scenario: 缓存未命中懒兜底
- **WHEN** 用户调 `getIndustryRanking("20260729")` 且缓存无值
- **THEN** 执行 `computeIndustryRanking("20260729")` 拿结果并写入缓存（key=20260729）
- **AND** 第二次调用直接命中

#### Scenario: SpEL 与 Job 使用相同 key
- **WHEN** `@Cacheable` SpEL 调 `CacheKeyResolver.resolveSectorKey(#tradeDate)` 且 Job 显式 put 用 `CacheKeyResolver.resolveSectorKey(tradeDate)`
- **THEN** 两者算出的 key 一致（"20260729" 或 "latest"）

### Requirement: 缓存配置调整
系统 SHALL 调整 `CacheConfig`：sectorRanking/sectorMoneyflow/sectorValuation/marketRanking/moneyflowRanking TTL=24h（数据一天不变）；marketTemperature/indices 无 TTL + `maximumSize(50)`（防膨胀）；新增 cacheName `marketRanking` 与 `moneyflowRanking`。移除 4 个 task 类上的 `@CacheEvict` 注解（DailyUpdateTask.dailyUpdate 行 46 / BasicDataTask.fetchDailyBasic 行 51 / MoneyflowDataTask.fetchDailyMoneyflowData 行 49 / IndexDailyFetchService.dailySync 行 117-118）。

### Requirement: 数据管控中心 scheduled-tasks API
系统 SHALL 在 `DataGovernanceController` 新增 4 个端点（所有端点要求登录访问；`task_class`/`method_name` 仅登录态返回；`error_message`/`error_stack` 按权限分级脱敏）：
- `GET /api/data-governance/scheduled-tasks`（group/keyword 过滤，返回 `List<ScheduledTaskVO>`）
- `GET /api/data-governance/scheduled-tasks/{taskClass}`（单任务详情）
- `GET /api/data-governance/scheduled-tasks/{taskClass}/history`（page/limit/status/startDate 分页筛选，返回 `PageResult` 含 records/total/page/pageSize，仅返回 `task_class` 匹配且 `task_name IS NOT NULL` 记录）
- `POST /api/data-governance/scheduled-tasks/{taskClass}/run`（管理员权限，RUNNING 拒绝抛 IllegalStateException + HTTP 409）

#### Scenario: 查询所有任务
- **WHEN** 客户端调 `GET /api/data-governance/scheduled-tasks`
- **THEN** 返回 21 条任务，每条含 taskName/cron/tableCode/taskGroup/lastExecutionTime/lastStatus/nextExecutionTime

#### Scenario: 手动重跑拒绝 RUNNING
- **WHEN** 运维对正在执行的任务调 `POST /run`
- **THEN** 后端校验 currentStatus=RUNNING，返回 HTTP 409 Conflict
- **AND** 不重复触发

#### Scenario: 手动重跑记 MANUAL 日志
- **WHEN** 运维调 `POST /run` 触发任务
- **THEN** `runTask` 实现内 set `TriggerContext`（MANUAL + operator），反射调目标方法
- **AND** AOP 切面读 ThreadLocal 记 `trigger_type=MANUAL` + `operator=当前用户`

### Requirement: 前端定时任务分区与三态展示
系统 SHALL 在 `data-governance.html` 新增"定时任务"分区（数据表总览下方），表格列：任务名/分组(中文)/关联表/cron(可读化)/当前状态/耗时/下次执行/操作。状态四态：SUCCESS(绿)/FAILED(红)/RUNNING(蓝，耗时实时刷新 5s 轮询)/NEVER_RUN(灰)。`configInconsistent=true` 时展示"配置异常"橙色徽标。筛选：分组下拉(中文映射)/状态下拉/关键字搜索。查看历史弹出模态框（默认 30 条/页，支持分页 + 按日期/状态筛选，errorMessage 折叠点击展开，失败记录红色高亮）。重跑按钮 RUNNING 时禁用提示"任务执行中"。三态展示：loading 骨架屏 / 空状态插画 / 错误状态重试按钮。前端须遵循 azure/mist/cyber 三主题 CSS 变量规范，WCAG AA 对比度。

#### Scenario: 用户查看定时任务分区
- **WHEN** 用户访问 `/page/data-governance`
- **THEN** 页面下方显示"定时任务"分区，表格 21 行
- **AND** 每行显示任务名/分组(中文)/关联表/cron(可读化)/当前状态/耗时/下次执行/操作

#### Scenario: RUNNING 状态实时刷新
- **WHEN** 某任务正在执行
- **THEN** 该行状态 RUNNING(蓝)，耗时列每 5s 刷新，"重跑"按钮禁用

#### Scenario: 查看历史支持分页筛选
- **WHEN** 用户点击"查看历史" + 翻到第 2 页 + 筛选 status=FAILED
- **THEN** 模态框显示第 2 页 30 条失败记录，total 字段正确

### Requirement: 任务失败告警
系统 SHALL 在 `TaskExecutionLogAspect` 记录 `status=FAILED` 时，若 `task_group` 为 `DATA_FETCH` 或 `PRECOMPUTE`，异步发送告警（邮件/IM webhook，渠道在 application.yml 配置），告警内容含任务名/失败时间/error_message 摘要/历史链接。同一任务 30 分钟内只告警 1 次（防骚扰）。

#### Scenario: 失败触发告警
- **WHEN** DATA_FETCH 任务失败
- **THEN** 异步发送告警，30 分钟内同任务重复失败不再发送

### Requirement: Spring 调度器多线程配置
系统 SHALL 显式配置 Spring `@Scheduled` 调度器为多线程：`application.yml` 加 `spring.task.scheduling.pool.size: 4`，或自定义 `ThreadPoolTaskScheduler` bean。原因：默认单线程会导致 16:00 的 `DailyUpdateTask` 卡死时 16:10/16:30 任务全部不执行，批次永远收不齐 → 超时兜底成为常态。

#### Scenario: 4 任务并行执行
- **WHEN** 4 个 @Scheduled 任务同时触发
- **THEN** 4 任务可并行执行（无串行阻塞）

### Requirement: PrecomputeAsyncConfig 线程池
系统 SHALL 提供 `precomputeExecutor` 线程池（core=4/max=8/queue=20/CallerRunsPolicy/线程名前缀 `precompute-`），供 7 Job + FactorSnapshot + ScreenLock 共享。建议 `FactorSnapshotTask` 独立 `factorSnapshotExecutor`（core=1/max=2/queue=10）隔离（DB 重写 IO 密集避免拖累首屏预计算），若实测 <15s 可不隔离。

### Requirement: 测试触发入口（@Profile("test")）
系统 SHALL 在测试 Profile 下提供 `/admin/test/*` Controller：trigger-batch-event / trigger-task/{taskClass} / metric-cutoff / precompute-all / cache-keys / cache-evict。生产环境不加载。

#### Scenario: 测试入口生产不可访问
- **WHEN** 生产 profile 启动
- **THEN** `/admin/test/*` 返回 404

### Requirement: 现有任务事件驱动改造
系统 SHALL 把 `FactorSnapshotTask`（移除 `@Scheduled(cron="0 30 16 * * MON-FRI")`）与 `ScreenLockTrackingTask`（移除 `@Scheduled(cron="0 30 16 * * ?")`）改为 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`，消除原 cron 时序错峰依赖。两者不加 `@ManagedTask`、不进 `data_pull_log`，靠自身日志输出。`FactorSnapshotTask` 保持独立实现（走 `factor_snapshot` 持久化表，不是 PrecomputeJob）。

#### Scenario: FactorSnapshotTask 由事件触发
- **WHEN** `DataBatchReadyEvent` 发布
- **THEN** `FactorSnapshotTask.onBatchReady` 异步执行 `computeDaily(tradeDate)` 写入 factor_snapshot 表
- **AND** 原 `@Scheduled` 注解已移除

#### Scenario: 不被 AOP 切面记录
- **WHEN** `FactorSnapshotTask.onBatchReady` 执行
- **THEN** `data_pull_log` 不新增记录（未加 `@ManagedTask`）
- **AND** 执行记录通过应用日志输出

## MODIFIED Requirements

### Requirement: data_pull_log 表语义扩展（复用 + 5 字段）
`data_pull_log` 表原仅记录 `DataInitService` 手动触发的拉取日志，本次扩展其语义同时记录 21 个拉取类定时任务的执行历史。新增 5 个可空字段（task_name/task_class/method_name/task_group/trigger_type）+ 索引 `idx_task_name_time(task_name, start_time)`，向后兼容现有 DataInitService 写入路径（5 字段为 NULL）。`table_code`/`table_name` NOT NULL 约束不变（21 任务都有 tableCode）。查询区分：定时任务历史 `WHERE task_class = ? AND task_name IS NOT NULL`；手动拉取历史 `WHERE task_name IS NULL`。保留期 3 个月，由 `MetricCleanupJob` 每日 01:00 清理（无需改动）。

### Requirement: TableStatusVO 扩展（向后兼容）
`TableStatusVO` 新增 3 个字段（向后兼容，原有字段不变）：`cron`（关联任务 cron，多任务取第一个）/ `nextExecutionTime` / `lastExecutionTime`（从 `data_pull_log` 取，替代原 `lastUpdateTime` 来源更准确）。

### Requirement: Caffeine 缓存命名空间调整
sectorRanking/sectorMoneyflow/sectorValuation TTL 30min → 24h；新增 cacheName `marketRanking`(24h) 与 `moneyflowRanking`(24h)；marketTemperature/indices 加 `maximumSize(50)`。kline/factorList/factorDetail/factorCategories/tradeCalendar/latestTradeDate/stockBasicName 保持现状。

## REMOVED Requirements

### Requirement: 4 个核心任务上的 @CacheEvict 注解
**Reason**：原设计由任务执行后 `@CacheEvict` 清缓存，下次查询 MISS 触发懒兜底。新架构改为预计算 Job 主动 put 覆盖旧值，无需清缓存。
**Migration**：移除 `DailyUpdateTask.dailyUpdate`/`BasicDataTask.fetchDailyBasic`/`MoneyflowDataTask.fetchDailyMoneyflowData`/`IndexDailyFetchService.dailySync` 上的 `@CacheEvict` 注解。

### Requirement: DataUpdatedEvent 按表粒度事件设计
**Reason**：原设计按表粒度发事件触发预计算，会导致多表依赖的 Job（如 SectorRanking 依赖 4 张表）在某张表刚更新完就跑用到其他表旧数据。
**Migration**：废弃 `DataUpdatedEvent(tableCode, tradeDate, source)` 设计，改为 `DataBatchReadyEvent` 整批完成事件，所有 Job 订阅同一事件。

### Requirement: FactorSnapshotTask 与 ScreenLockTrackingTask 的 @Scheduled 触发
**Reason**：原 cron 时序错峰（`0 30 16 * * MON-FRI` / `0 30 16 * * ?`）依赖前序任务准时完成，前序延迟会导致读到不完整数据。
**Migration**：改为 `@EventListener` + `@Async("precomputeExecutor")` 订阅 `DataBatchReadyEvent`，事件驱动保证数据已就绪。
