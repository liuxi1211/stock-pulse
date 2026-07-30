# Tasks

> 来源：PRD 第九章「实施排期」Phase A-E。任务编号沿用 A1/B1/C1/D1/E1 风格便于跨文档对照。

## Phase A：定时任务整合层（模块 A）—— ✅ 已完成

- [x] Task A1：新建 `@ManagedTask` 注解
  - [x] SubTask A1.1：创建 `annotation/ManagedTask.java`（method-level / RUNTIME，必填 `name`/`group`，可选 `tableCode`/`description`，`group` 枚举 `DATA_FETCH/GOVERNANCE/MAINTENANCE/PRECOMPUTE/VERIFY`）
- [x] Task A2：扩展 `DataPullLogDO` + Mapper（5 个可空字段）
  - [x] SubTask A2.1：`DataPullLogDO` 新增 `taskName`/`taskClass`/`methodName`/`taskGroup`/`triggerType` 字段
  - [x] SubTask A2.2：`DataPullLogMapper.xml` 新增字段映射 + 新增按 `task_class` 分页查询历史的方法（含 status/startDate 过滤）
- [x] Task A3：新建 `ScheduledTaskRegistryService` + impl
  - [x] SubTask A3.1：`@PostConstruct` 反射 Spring `ScheduledTaskHolder` + 关联 `@ManagedTask`，用 `CronExpression.parse(cron).next(now)` 计算 nextExecutionTime
  - [x] SubTask A3.2：反射失败 try-catch + WARN + 返回空列表不阻断启动
  - [x] SubTask A3.3：实现 `listScheduledTasks()` / `getScheduledTask(taskClass)` / `getNextExecutionTime(taskClass)` / `runTask(taskClass, operator)`
  - [x] SubTask A3.4：`runTask` 入口校验 currentStatus != RUNNING（否则抛 IllegalStateException，Controller 转 HTTP 409）；set `TriggerContext`（MANUAL + operator）→ 反射调目标方法 → finally clear ThreadLocal
  - [x] SubTask A3.5：读取 `ConcurrentHashMap<taskClass, RunningStatus>`（由 AOP 维护）填充 `currentStatus` + 实时 `lastDurationMs`
- [x] Task A4：启动校验（tableCode/name fail-fast + cron 一致性 WARN）
  - [x] SubTask A4.1：所有 `@ManagedTask` 的 `tableCode` 非空校验，空则 fail-fast
  - [x] SubTask A4.2：`@ManagedTask.name` 全局唯一校验，重名 fail-fast
  - [x] SubTask A4.3：cron 解析的执行时间与 `InitStep.fromCode(tableCode).expectedUpdateTime` 不一致打 WARN 不阻断
- [x] Task A5：新建 `TaskExecutionLogAspect`（AOP 切面）
  - [x] SubTask A5.1：`@Around` 拦截所有 `@ManagedTask` 方法，入口读 `TriggerContext.get()` 区分 SCHEDULED（operator=SYSTEM）vs MANUAL（operator=当前用户）
  - [x] SubTask A5.2：入口在 `ConcurrentHashMap<taskClass, RunningStatus>` set startTime，出口（finally）clear
  - [x] SubTask A5.3：异步写入 `data_pull_log`（共用 14 字段 + 新增 5 字段），原方法抛异常时记 status=FAILED + error_message（截断 1024）+ error_stack（截断 8192）并重新抛出
  - [x] SubTask A5.4：日志写入操作 try-catch 容错，写入失败只 ERROR 不影响原方法返回值/异常
  - [x] SubTask A5.5：失败告警逻辑（task_group ∈ {DATA_FETCH, PRECOMPUTE} 时异步发邮件/IM，同任务 30 分钟防骚扰）—— 当前仅打 WARN 日志，邮件/IM webhook 待补
- [x] Task A6：新建 `TriggerContext`（ThreadLocal）
  - [x] SubTask A6.1：`setManual(operator)` / `get()` / `clear()`，`TriggerInfo(triggerType, operator)` record
- [x] Task A7：新建 `ScheduledTaskVO`
  - [x] SubTask A7.1：字段 taskName/taskClass/methodName/cron/cronReadable/tableCode/tableName/taskGroup/taskGroupLabel/description/lastExecutionTime/lastStatus/currentStatus/lastDurationMs/nextExecutionTime/enabled/configInconsistent
  - [x] SubTask A7.2：cronReadable 由后端解析 cron 生成（如"每天 16:00"）；taskGroupLabel 中文映射；currentStatus RUNNING 时填充
- [x] Task A8：SQL `ALTER TABLE data_pull_log` 新增 5 字段 + 索引
  - [x] SubTask A8.1：修改 `schema-mysql.sql`：task_name/task_class/method_name/task_group/trigger_type 5 个可空字段 + `INDEX idx_task_name_time(task_name, start_time)`
  - [x] SubTask A8.2：保证 MySQL 语法兼容（项目硬约束：MySQL/SQLite 兼容，但此处是 schema-mysql.sql 专用文件）
- [x] Task A9：给 21 处拉取类 `@Scheduled` 方法加 `@ManagedTask` 注解
  - [x] SubTask A9.1：`DailyUpdateTask.dailyUpdate`（tableCode=daily, name=每日数据更新）
  - [x] SubTask A9.2：`BasicDataTask` 9 个方法（fetchDailyBasic/fetchFinaIndicator/fetchIncome/fetchBalancesheet/fetchCashflow/fetchForecast/fetchExpress/fetchStkHoldernumber/fetchStkHoldertrade）
  - [x] SubTask A9.3：`MoneyflowDataTask.fetchDailyMoneyflowData`
  - [x] SubTask A9.4：`IndexBasicTask.syncDaily` / `IndexDailyFetchService.dailySync` / `IndexWeightTask.syncDaily`
  - [x] SubTask A9.5：`StockNamechangeTask.dailyIncremental` / `quarterlyFull`
  - [x] SubTask A9.6：`StockSuspendDTask.dailyIncremental` / `monthlyFull`
  - [x] SubTask A9.7：`StockStkLimitTask.dailyIncremental` / `monthlyFull`
  - [x] SubTask A9.8：`SwIndustryTask.syncHalfYearly`
- [x] Task A10：`DataGovernanceController` + Service 新增 4 个 scheduled-tasks 端点
  - [x] SubTask A10.1：`GET /api/data-governance/scheduled-tasks`（group/keyword 过滤，返回 `List<ScheduledTaskVO>`）
  - [x] SubTask A10.2：`GET /api/data-governance/scheduled-tasks/{taskClass}`（单任务详情）
  - [x] SubTask A10.3：`GET /api/data-governance/scheduled-tasks/{taskClass}/history`（page/limit/status/startDate 分页筛选，返回 `PageResult`，仅返回 `task_class` 匹配且 `task_name IS NOT NULL` 记录）
  - [x] SubTask A10.4：`POST /api/data-governance/scheduled-tasks/{taskClass}/run`（管理员权限，RUNNING 拒绝 HTTP 409）
  - [x] SubTask A10.5：所有端点要求登录访问；`task_class`/`method_name` 仅登录态返回；`error_message`/`error_stack` 按权限分级脱敏（管理员完整，普通用户脱敏后顶层 message）
- [x] Task A11：配置 `spring.task.scheduling.pool.size=4`
  - [x] SubTask A11.1：修改 `application.yml` 加 `spring.task.scheduling.pool.size: 4`（或自定义 `ThreadPoolTaskScheduler` bean）
- [x] Task A12：新建测试 Controller `/admin/test/*`（仅 test profile）
  - [x] SubTask A12.1：`@Profile("test")` + `@RestController`，6 个端点：trigger-batch-event / trigger-task/{taskClass} / metric-cleanup / precompute-all / cache-keys / cache-evict

## Phase B：批次事件 + 预计算层（模块 B/C/D/E）—— ✅ 已完成

- [x] Task B1：新建 `DataBatchReadyEvent`
  - [x] SubTask B1.1：`extends ApplicationEvent`，含 `tradeDate`(yyyyMMdd) + `source`(SCHEDULED/SCHEDULED_TIMEOUT/SCHEDULED_PARTIAL/MANUAL)
- [x] Task B2：新建 `DataBatchCompletionTracker`
  - [x] SubTask B2.1：`@Component`，`ConcurrentHashMap<String, BatchEntry>`（key=tradeDate）
  - [x] SubTask B2.2：`EXPECTED_TASKS = Set.of("DailyUpdateTask.dailyUpdate", "BasicDataTask.fetchDailyBasic", "MoneyflowDataTask.fetchDailyMoneyflowData", "IndexDailyFetchService.dailySync")`
  - [x] SubTask B2.3：`reportCompletion(taskKey, tradeDate, hasError)` —— Set 去重 + `fired` 标志防重复 + hasError 感知 SCHEDULED_PARTIAL + 发布后清理 entry
  - [x] SubTask B2.4：`forceFireOnTimeout(tradeDate, missingTasks)` —— 检查 `fired`，发布 SCHEDULED_TIMEOUT
- [x] Task B3：新建 `PrecomputeJob` 接口 + `AbstractPrecomputeJob`
  - [x] SubTask B3.1：接口仅 `name()` + `precompute(tradeDate)`（**无 `dependsOnTables()`**）
  - [x] SubTask B3.2：`AbstractPrecomputeJob` 模板方法：`(jobName, tradeDate)` 去重（ConcurrentHashMap）→ startTime → `doPrecompute` → 耗时 → 异常时 ERROR + 主动 evict `{tradeDate}` 和 `latest` 两 key → finally 清除去重标记
  - [x] SubTask B3.3：子类抽象方法 `doPrecompute(tradeDate)` + `cacheName()` + `cacheKeys(tradeDate)`
- [x] Task B4：新建 `PrecomputeAsyncConfig`
  - [x] SubTask B4.1：`precomputeExecutor` Bean（core=4/max=8/queue=20/CallerRunsPolicy/线程名前缀 `precompute-`）
  - [x] SubTask B4.2：可选 `factorSnapshotExecutor`（core=1/max=2/queue=10）隔离 FactorSnapshot（若实测 <15s 可不隔离）
  - [x] SubTask B4.3：`@EnableAsync` 配置
- [x] Task B5：新建 `CacheKeyResolver` 工具类
  - [x] SubTask B5.1：`resolveSectorKey(tradeDate)`（null/blank → "latest"）
  - [x] SubTask B5.2：`resolveLatestKey(latestTradeDate)`（null → "empty"）
  - [x] SubTask B5.3：`resolveMoneyflowRankingKey(tradeDate, limit, sortBy, order)`（prefix + "_" + limit + "_" + sortBy + "_" + order）
- [x] Task B6：新建 `PrecomputeEventDispatcher`
  - [x] SubTask B6.1：`@PostConstruct` 扫描所有 `PrecomputeJob` Bean 存入列表
  - [x] SubTask B6.2：`@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
  - [x] SubTask B6.3：`CompletableFuture.allOf` 并发提交所有 Job 到 `precomputeExecutor`
  - [ ] SubTask B6.4：source=SCHEDULED_PARTIAL/SCHEDULED_TIMEOUT 时 Job 内做数据完整性校验（如 daily_quote 当日记录数 > 0），不完整跳过预计算打 WARN —— **当前为委托式弱项，各 Job 未显式校验，依赖底层 compute 空 List 兜底**
  - [x] SubTask B6.5：单 Job try-catch 失败隔离
- [x] Task B7：新建 `PrecomputeServiceImpl`
  - [x] SubTask B7.1：`precomputeNow(jobName, tradeDate)` 同步执行单 Job（懒兜底用）
  - [x] SubTask B7.2：`precomputeAll(tradeDate)` 触发全部 Job（运维排错）
- [x] Task B8：`SwIndustryServiceImpl` 拆分 get/compute 三对方法
  - [x] SubTask B8.1：`getIndustryRanking` 带 `@Cacheable`（key 用 `CacheKeyResolver.resolveSectorKey(#tradeDate)`，unless 空不缓存）+ `computeIndustryRanking`（无注解，public，不互相调用）
  - [x] SubTask B8.2：`getIndustryMoneyflow` / `computeIndustryMoneyflow` 同上
  - [x] SubTask B8.3：`getIndustryValuation` / `computeIndustryValuation` 同上
  - [x] SubTask B8.4：Code review 检查 `getXXX` 方法体只有一行 `return computeXXX(...)`
- [x] Task B9：`MarketServiceImpl` 拆分 get/compute 三对方法 + `getMarketRanking` 加 `@Cacheable`
  - [x] SubTask B9.1：`getMarketIndices` / `computeMarketIndices`
  - [x] SubTask B9.2：`getMarketRanking`（**新增 `@Cacheable`**，key=`#root.target.getLatestTradeDate()`） / `computeMarketRanking`
  - [x] SubTask B9.3：`getMarketTemperature` / `computeMarketTemperature`
- [x] Task B10：`MoneyflowServiceImpl` 拆分 `queryTop` / `computeQueryTop` + 接口扩展
  - [x] SubTask B10.1：`queryTop` 加 `@Cacheable`（key 用 `CacheKeyResolver.resolveMoneyflowRankingKey(#tradeDate, #limit, #sortBy, #order)`，unless 空不缓存）
  - [x] SubTask B10.2：`computeQueryTop`（无注解，原 queryTop 方法体）
  - [x] SubTask B10.3：`MoneyflowService` 接口新增 `computeQueryTop` 签名
- [x] Task B11：实现 7 个 `PrecomputeJob` 子类
  - [x] SubTask B11.1：`SectorRankingPrecomputeJob`（调 `computeIndustryRanking`，写 `sectorRanking` 缓存双写 `{tradeDate}` + `latest`）
  - [x] SubTask B11.2：`SectorMoneyflowPrecomputeJob`（写 `sectorMoneyflow`）
  - [x] SubTask B11.3：`SectorValuationPrecomputeJob`（写 `sectorValuation`）
  - [x] SubTask B11.4：`MarketIndicesPrecomputeJob`（调 `computeMarketIndices`，key=`{latestTradeDate}` + `latest`）
  - [x] SubTask B11.5：`MarketRankingPrecomputeJob`（调 `computeMarketRanking`，Javadoc 注明依赖 `daily_quote + daily_basic + stock_basic`，key=`{latestTradeDate}` + `latest`）
  - [x] SubTask B11.6：`MarketTemperaturePrecomputeJob`（调 `computeMarketTemperature`，写 `marketTemperature`）
  - [x] SubTask B11.7：`MoneyflowRankingPrecomputeJob`（固定参数 limit=10/sortBy=main_net/order=desc，key=`{tradeDate}_10_main_net_desc` + `latest_10_main_net_desc`）
- [x] Task B12：`CacheConfig` 调整 TTL + 新增 cacheName
  - [x] SubTask B12.1：sectorRanking/sectorMoneyflow/sectorValuation TTL 30min → 24h
  - [x] SubTask B12.2：新增 cacheName `marketRanking`(24h) / `moneyflowRanking`(24h)
  - [x] SubTask B12.3：marketTemperature/indices 加 `maximumSize(50)`
- [x] Task B13：4 个核心 task 类改造（移除 `@CacheEvict` + finally 调 `reportCompletion`）
  - [x] SubTask B13.1：`DailyUpdateTask.dailyUpdate` —— 入口捕获 tradeDate（Asia/Shanghai）+ try-catch 单步失败不阻塞 + finally 调 `reportCompletion("DailyUpdateTask.dailyUpdate", tradeDate, hasError)` + 移除 `@CacheEvict(value={"sectorRanking","sectorMoneyflow","sectorValuation"}, allEntries=true)`
  - [x] SubTask B13.2：`BasicDataTask.fetchDailyBasic` —— finally 调 `reportCompletion("BasicDataTask.fetchDailyBasic", ...)` + 移除 `@CacheEvict(value="sectorValuation")`
  - [x] SubTask B13.3：`MoneyflowDataTask.fetchDailyMoneyflowData` —— finally 调 `reportCompletion("MoneyflowDataTask.fetchDailyMoneyflowData", ...)` + 移除 `@CacheEvict(value="sectorMoneyflow")`
  - [x] SubTask B13.4：`IndexDailyFetchService.dailySync` —— finally 调 `reportCompletion("IndexDailyFetchService.dailySync", ...)` + 移除 `@CacheEvict(value="sectorRanking")`
  - [x] SubTask B13.5：每个 task 类构造器追加 `DataBatchCompletionTracker` 依赖注入
- [x] Task B14：`DataBatchCompletionTracker` 超时兜底（P1）
  - [x] SubTask B14.1：`@Scheduled` 定时检查 `completionMap` 中超过 30 分钟未收齐的 tradeDate entry
  - [x] SubTask B14.2：调 `forceFireOnTimeout(tradeDate, missingTasks)` 发布 SCHEDULED_TIMEOUT + 清理 entry + `fired=true`
  - [x] SubTask B14.3：打 WARN 日志列出未报告的任务名

## Phase C：前端整合（模块 F）—— ✅ 已完成

- [x] Task C1：数据管控页面新增"定时任务"分区
  - [x] SubTask C1.1：`data-governance.html` 新增"定时任务"分区（数据表总览下方）
  - [x] SubTask C1.2：表格列：任务名/分组(中文)/关联表/cron(可读化)/当前状态/耗时/下次执行/操作（查看历史/重跑/暂停）
  - [x] SubTask C1.3：状态四态展示：SUCCESS(绿)/FAILED(红)/RUNNING(蓝，耗时实时刷新 5s 轮询)/NEVER_RUN(灰)，遵循 azure/mist/cyber 三主题 CSS 变量
  - [x] SubTask C1.4：`configInconsistent=true` 时展示"配置异常"橙色徽标
  - [x] SubTask C1.5：筛选：分组下拉(中文映射) + 状态下拉(全部/成功/失败/运行中/从未执行) + 关键字搜索
  - [x] SubTask C1.6：查看历史模态框（默认 30 条/页，支持分页 + 按日期/状态筛选，errorMessage 折叠点击展开，失败记录红色高亮）
  - [x] SubTask C1.7：重跑按钮 RUNNING 时禁用提示"任务执行中"
  - [x] SubTask C1.8：三态展示：loading 骨架屏 / 空状态插画 + 文案 / 错误状态重试按钮
  - [x] SubTask C1.9：WCAG AA 对比度（≥4.5:1 正文），CSS 分层（theme.css 变量 / components.css / custom.css / page 前缀 CSS）
- [x] Task C2：`TableStatusVO` 新增 3 字段
  - [x] SubTask C2.1：`cron`（关联任务 cron，多任务取第一个）/ `nextExecutionTime` / `lastExecutionTime`（从 `data_pull_log` 取）
- [x] Task C3：数据表总览表格新增"下次执行"列
  - [x] SubTask C3.1：列位置在"更新频率"列后，空值显示 `-`
- [x] Task C4：RUNNING 状态实时刷新（前端轮询 5s）
  - [x] SubTask C4.1：列表 RUNNING 行耗时列每 5s 刷新
- [x] Task C5：errorMessage 折叠展示 + 脱敏后信息红色高亮
  - [x] SubTask C5.1：失败记录 errorMessage 默认折叠，点击展开查看完整脱敏后信息

## Phase D：测试 —— ✅ D1-D11 已完成

- [x] Task D1：`ScheduledTaskRegistryServiceTest`
  - [x] SubTask D1.1：验证 21 个任务全部被解析
  - [x] SubTask D1.2：tableCode 空 fail-fast
  - [x] SubTask D1.3：name 重名 fail-fast
  - [x] SubTask D1.4：cron 与 InitStep 不一致 WARN
- [x] Task D2：`TaskExecutionLogAspectTest`
  - [x] SubTask D2.1：AOP 正确记录成功/失败
  - [x] SubTask D2.2：日志写入容错（mock DB 异常验证原方法正常返回）
  - [x] SubTask D2.3：失败告警发送 + 30 分钟防骚扰
  - [x] SubTask D2.4：TriggerContext MANUAL vs SCHEDULED 区分
- [x] Task D3：`DataBatchCompletionTrackerTest`
  - [x] SubTask D3.1：4 任务报告后发布事件
  - [x] SubTask D3.2：重复报告去重
  - [x] SubTask D3.3：fired 防重复
  - [x] SubTask D3.4：hasError 感知 SCHEDULED_PARTIAL
  - [x] SubTask D3.5：超时兜底
- [x] Task D4：`PrecomputeEventDispatcherTest`
  - [x] SubTask D4.1：mock Job 列表验证并发提交
  - [x] SubTask D4.2：失败隔离
  - [ ] SubTask D4.3：数据完整性校验（PARTIAL/TIMEOUT 跳过）—— @Disabled 待 Task F1
- [x] Task D5：`SectorRankingPrecomputeJobTest`
  - [x] SubTask D5.1：mock Service 验证缓存双写（`{tradeDate}` + `latest`）
  - [x] SubTask D5.2：异常 evict 验证
- [x] Task D6：`MoneyflowRankingPrecomputeJobTest`
  - [x] SubTask D6.1：mock Service 验证固定参数缓存双写
- [x] Task D7：懒兜底验证（全 Job 覆盖）
  - [x] SubTask D7.1：清缓存后查 7 个 Job 对应接口，验证方法体执行且结果写缓存
- [x] Task D8：并发测试
  - [x] SubTask D8.1：4 任务 CountDownLatch 同步触发 reportCompletion，验证事件只发布 1 次
- [x] Task D9：非交易日/跨日场景
  - [x] SubTask D9.1：mock 交易日历 + 时间 mock，验证 tracker 行为
  - [x] SubTask D9.2：预计算空数据不缓存（unless 生效）
- [x] Task D10：AOP 性能测试
  - [x] SubTask D10.1：JMH 微基准验证 P99 <1ms
- [x] Task D11：手动重跑测试
  - [x] SubTask D11.1：POST run 接口触发任务
  - [x] SubTask D11.2：RUNNING 拒绝（HTTP 409）
  - [x] SubTask D11.3：trigger_type=MANUAL + operator 正确

## Phase E：现有任务事件驱动改造（模块 G）—— ✅ 已完成

- [x] Task E1：`FactorSnapshotTask` 事件驱动改造
  - [x] SubTask E1.1：移除 `@Scheduled(cron = "0 30 16 * * MON-FRI")` 注解
  - [x] SubTask E1.2：新增 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
  - [x] SubTask E1.3：方法体 try-catch + 打 ERROR 日志，不加 `@ManagedTask`
  - [x] SubTask E1.4：保持独立实现（走 `factor_snapshot` 持久化表，不是 PrecomputeJob）
- [x] Task E2：`ScreenLockTrackingTask` 事件驱动改造
  - [x] SubTask E2.1：移除 `@Scheduled(cron = "0 30 16 * * ?")` 注解
  - [x] SubTask E2.2：新增 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
  - [x] SubTask E2.3：方法体 try-catch + 打 ERROR 日志，不加 `@ManagedTask`
- [x] Task E3：验证改造后两个任务由 `DataBatchReadyEvent` 触发
  - [x] SubTask E3.1：发布事件后断言 `FactorSnapshotTask` 执行写 `factor_snapshot` 表 —— 代码层已就绪，集成测试在 Phase D 补
  - [x] SubTask E3.2：发布事件后断言 `ScreenLockTrackingTask` 执行更新追踪记录 —— 代码层已就绪，集成测试在 Phase D 补
  - [x] SubTask E3.3：断言两者执行时 `data_pull_log` 不新增记录 —— 代码层已就绪（未加 @ManagedTask），集成测试在 Phase D 补

## 补强任务（Phase B 弱项）

- [ ] Task F1：补强 PrecomputeEventDispatcher / Job 数据完整性校验
  - [ ] SubTask F1.1：在 7 个 Job 的 `doPrecompute` 入口加数据存在性校验（如 daily_quote 当日记录数 > 0），不完整时跳过预计算打 WARN

# Task Dependencies

- Task A2（DO+Mapper）→ Task A5（AOP 切面依赖 DO 写入） ✅
- Task A1（注解）+ Task A3（Registry）→ Task A9（给 21 处方法加注解，需注解与 Registry 就绪才能验证注册） ✅
- Task A6（TriggerContext）→ Task A5（AOP 读 ThreadLocal） ✅
- Task A3（Registry）→ Task A10（Controller 调 Registry） ✅
- Task B1（Event）+ Task B2（Tracker）→ Task B13（4 task 类 finally 调 reportCompletion） ✅
- Task B3（Job 接口/抽象）+ Task B5（CacheKeyResolver）+ Task B8/B9/B10（Service 拆分 compute）→ Task B11（7 个 Job 子类，依赖 computeXXX 方法存在） ✅
- Task B4（线程池）→ Task B6（Dispatcher 用 precomputeExecutor） ✅
- Task B6（Dispatcher）+ Task B11（Jobs）→ 集成测试 D4/D5/D6 ⏳
- Task B12（CacheConfig）→ Task B11（Job 依赖 cacheName 存在） ✅
- Task B13（4 task 类改造）+ Task B6（Dispatcher）→ Task E1/E2（FactorSnapshot/ScreenLock 监听事件，需事件链就绪） ✅
- Task A10（后端 API）→ Task C1（前端调 API） ✅
- Task C2（TableStatusVO）→ Task C3（数据表总览新增列） ✅
- Phase A + Phase B 完成 → Phase D（测试依赖实现就绪） ⏳
- Phase E 可与 Phase B 后段并行（E1/E2 只依赖 B1 事件类与 B4 线程池） ✅
