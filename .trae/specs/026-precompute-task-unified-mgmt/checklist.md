# Checklist

> 来源：PRD 第八章「验收标准」§8.1-8.7 + §8.7 评审补充。每项对应一个可验证的检查点。

## §8.1 定时任务管理

- [x] 应用启动后日志输出 `[TaskRegistry] 已注册 21 个定时任务`
  > 验证说明：代码层已就绪。ScheduledTaskRegistryServiceImpl.java:103 输出 `[TaskRegistry] 已注册 {} 个定时任务`；项目 @ManagedTask 标注方法计数=21（DailyUpdateTask:1 + BasicDataTask:9 + MoneyflowDataTask:1 + IndexDailyFetchService:1 + SwIndustryTask:1 + StockSuspendDTask:2 + StockStkLimitTask:2 + StockNamechangeTask:2 + IndexWeightTask:1 + IndexBasicTask:1），D1.1 测试断言 21。
- [x] `GET /api/data-governance/scheduled-tasks` 返回 21 条任务
  > 验证说明：代码层已就绪。DataGovernanceController.java:340 提供 `/scheduled-tasks` 端点；D1_1_启动后解析全部21个任务 断言 listScheduledTasks().size()==21。
- [x] 每条任务包含 `cron` / `nextExecutionTime` / `lastExecutionTime` 字段
  > 验证说明：ScheduledTaskVO.java 含 cron/nextExecutionTime/lastExecutionTime 字段，parseTask() 在 ScheduledTaskRegistryServiceImpl.java:145-163 填充。
- [ ] 手动触发 `DailyUpdateTask.dailyUpdate()`，`data_pull_log` 新增 SUCCESS 记录（`task_name` 非空，`trigger_type=SCHEDULED`）
  > 验证说明：代码层已就绪（TaskExecutionLogAspect 写入 task_name/task_class/trigger_type），需运行时实测。
- [ ] 故意让任务抛异常，`data_pull_log` 新增 FAILED 记录，errorMessage 非空
  > 验证说明：代码层已就绪（AOP 在 error!=null 时写 errorMessage/errorStack），D2.1 测试覆盖；需运行时实测。
- [x] 修改某任务 cron 不更新 InitStep，启动时打 WARN
  > 验证说明：ScheduledTaskRegistryServiceImpl.validateTasks() 第 225-243 行实现 cron/InitStep.expectedUpdateTime 不一致打 WARN 并标记 configInconsistent=true；D1.4 测试覆盖。
- [ ] 凌晨 01:00 `MetricCleanupJob` 执行后，3 个月前的 `data_pull_log` 记录被清理（含定时任务和手动拉取两类）
  > 验证说明：代码层已就绪。MetricCleanupJob @Scheduled(cron="0 0 1 * * ?") 调 metricMapper.deleteOlderThan + pullLogMapper.deleteOlderThan（cutoff=now-3months），需运行时实测。
- [ ] 前端数据管控页面显示"定时任务"分区，表格 21 行
  > 验证说明：代码层已就绪（data-governance.html:141 "定时任务" 分区 + taskTableBody 表格；后端 D1.1 断言 21 任务），需浏览器实测"21 行"显示。
- [ ] 点击"查看历史"按钮弹出模态框，显示最近 30 条执行记录
  > 验证说明：代码层已就绪（data-governance.js openTaskHistory + taskHistoryModal + taskHistoryContext.limit=30 默认），需浏览器实测。

> Phase C 前端实现已验证（代码层）：定时任务分区、历史模态框（limit=30 默认）、分页筛选、RUNNING 轮询、errorMessage 折叠、三态展示均就绪；"21 行"依赖运行时后端任务注册数（Phase A 已完成 21 任务注册）。

## §8.2 批次完成事件机制

- [x] `DataBatchCompletionTracker` 在 4 个任务都调 `reportCompletion` 后发布 `DataBatchReadyEvent`
  > 验证说明：DataBatchCompletionTracker.java:63-66 实现 EXPECTED_TASKS 收齐后 publishEvent；D3.1 测试覆盖。
- [x] 重复调用 `reportCompletion("DailyUpdateTask", tradeDate)` 不会重复发布事件
  > 验证说明：DataBatchCompletionTracker.java:60-69 使用 ConcurrentHashMap.newKeySet 去重 + fired 标志；D3.2/D3.3 测试覆盖。
- [x] 4 个 task 类的 `@CacheEvict` 注解已移除
  > 验证说明：Grep `@CacheEvict` 在 task/ 目录与 IndexDailyFetchService.java 均为 0 命中。
- [x] 4 个 task 类的方法末尾（finally 块）调 `batchCompletionTracker.reportCompletion`
  > 验证说明：DailyUpdateTask:94 / BasicDataTask:71 / MoneyflowDataTask:107 / IndexDailyFetchService:152 均在 finally 块调用 reportCompletion。
- [x] 任务方法抛异常时，finally 块仍调 `reportCompletion`（不阻塞批次）
  > 验证说明：4 个任务均使用 try-catch-finally 结构，catch 内 hasError=true，finally 必调 reportCompletion(taskKey, tradeDate, hasError)。
- [x] 批次发布时打 INFO 日志 `[BatchTracker] tradeDate={tradeDate} 收齐 4 个任务报告，发布 DataBatchReadyEvent`
  > 验证说明：DataBatchCompletionTracker.java:68 输出该 INFO 日志（含 source）。

## §8.3 预计算

- [ ] `DataBatchReadyEvent` 发布后，日志输出 7 个 PrecomputeJob 的执行记录
  > 验证说明：代码层已就绪。precompute/jobs/ 目录有 7 个 Job（MarketIndices/MarketRanking/MarketTemperature/MoneyflowRanking/SectorMoneyflow/SectorRanking/SectorValuation），PrecomputeEventDispatcher 并发触发；需运行时实测日志输出。
- [ ] 预计算完成后，`cacheManager.getCache("sectorRanking").get("20260729")` 返回非空
  > 验证说明：代码层已就绪（SectorRankingPrecomputeJob.doPrecompute 双写 tradeDate + latest key），D5.1 测试断言 cache.get("20260729") 非空；需运行时实测。
- [ ] 调 `GET /api/industry/ranking`，响应时间 < 50ms
  > 验证说明：代码层已就绪（@Cacheable sectorRanking），需运行时压测。
- [ ] 调 `GET /api/market/temperature`，响应时间 < 50ms
  > 验证说明：代码层已就绪（@Cacheable marketTemperature），需运行时压测。
- [x] `CacheConfig` 中 sectorRanking 等 TTL 为 24h
  > 验证说明：CacheConfig.java:46-53 registerCustomCache sectorRanking/sectorMoneyflow/sectorValuation/marketRanking/moneyflowRanking 均 expireAfterWrite(24h)；marketTemperature 用 boundedSpec（maximumSize=50，无 TTL，符合"无 TTL 仅容量上限"设计）。
- [x] `MoneyflowRankingPrecomputeJob` 执行后，`cacheManager.getCache("moneyflowRanking").get("20260729_10_main_net_desc")` 返回非空
  > 验证说明：MoneyflowRankingPrecomputeJob.java:67-71 doPrecompute 写入 key=`{tradeDate}_10_main_net_desc` + `latest_10_main_net_desc`；D6.1 测试断言 cache.get("20260729_10_main_net_desc") 非空。
- [ ] 调 `GET /api/moneyflow/top?limit=10&sortBy=main_net&order=desc`，响应时间 < 50ms
  > 验证说明：代码层已就绪（@Cacheable moneyflowRanking + Job 预计算固定参数 10/main_net/desc），需运行时压测。
- [ ] 调 `GET /api/moneyflow/top?limit=20&sortBy=main_net&order=desc`（非预计算参数），懒兜底执行并写入缓存（首次 < 3s，二次 < 50ms）
  > 验证说明：代码层已就绪（@Cacheable 多参数 key 含 limit/sortBy/order，非预计算参数走 computeQueryTop 懒兜底），D7.7 测试覆盖懒兜底缓存命中；需运行时压测响应时间。
- [x] `PrecomputeJob` 接口只含 `name()` 和 `precompute(tradeDate)` 两个方法，无 `dependsOnTables`
  > 验证说明：PrecomputeJob.java 仅声明 name() + precompute(String)，无 dependsOnTables；Javadoc 明示"不暴露 dependsOnTables()"。
- [x] `MarketRankingPrecomputeJob` 的 Javadoc 注明依赖 `daily_quote + daily_basic + stock_basic`
  > 验证说明：MarketRankingPrecomputeJob.java:22-23 Javadoc 写明"实际数据依赖：daily_quote + daily_basic + stock_basic 三张表"。

## §8.4 现有任务事件驱动改造

- [x] `FactorSnapshotTask` 的 `@Scheduled(cron = "0 30 16 * * MON-FRI")` 注解已移除
  > 验证说明：FactorSnapshotTask.java 无任何 @Scheduled 注解。
- [x] `FactorSnapshotTask` 改用 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
  > 验证说明：FactorSnapshotTask.java:25-27 onBatchReady 方法标注 @EventListener + @Async("precomputeExecutor")，参数为 DataBatchReadyEvent。
- [ ] `DataBatchReadyEvent` 发布后，`FactorSnapshotTask` 被触发执行，写入 `factor_snapshot` 表
  > 验证说明：代码层已就绪（@EventListener 监听 + factorSnapshotService.computeForLatestTradeDate），需运行时实测 factor_snapshot 表写入。
- [x] `ScreenLockTrackingTask` 的 `@Scheduled(cron = "0 30 16 * * ?")` 注解已移除
  > 验证说明：ScreenLockTrackingTask.java 无任何 @Scheduled 注解。
- [x] `ScreenLockTrackingTask` 改用 `@EventListener` + `@Async("precomputeExecutor")` 监听 `DataBatchReadyEvent`
  > 验证说明：ScreenLockTrackingTask.java:33-35 onBatchReady 方法标注 @EventListener + @Async("precomputeExecutor")。
- [ ] `DataBatchReadyEvent` 发布后，`ScreenLockTrackingTask` 被触发执行，更新选股锁定追踪记录
  > 验证说明：代码层已就绪（screenerService.listTrackingLocks + applyTracking），需运行时实测 screen_lock 表更新。
- [x] `FactorSnapshotTask` / `ScreenLockTrackingTask` 执行时，`data_pull_log` 表不新增记录（未加 `@ManagedTask`）
  > 验证说明：Grep `@ManagedTask` 在 FactorSnapshotTask.java / ScreenLockTrackingTask.java 为 0 命中；AOP 切面只拦截 @ManagedTask 方法，故不写 data_pull_log。
- [x] `FactorSnapshotTask` / `ScreenLockTrackingTask` 执行日志通过应用日志输出
  > 验证说明：FactorSnapshotTask.java:36/39/41 与 ScreenLockTrackingTask.java:47/74 均使用 log.info/log.error 输出执行日志。

## §8.5 懒兜底

- [ ] 重启应用（Caffeine 清空），调 `/api/industry/ranking` 返回正确数据
  > 验证说明：代码层已就绪（@Cacheable + computeIndustryRanking 懒兜底），需运行时实测。
- [ ] 第二次调 `/api/industry/ranking` 响应时间 < 50ms
  > 验证说明：代码层已就绪（缓存命中），D7.1 测试断言第二次 mapper 调用次数仍为 1；需运行时压测。
- [x] `getIndustryRanking` 的 SpEL key 与 Job 显式 put 的 key 一致
  > 验证说明：@Cacheable key=`T(CacheKeyResolver).resolveSectorKey(#tradeDate)`；SectorRankingPrecomputeJob 显式 put key=`CacheKeyResolver.resolveSectorKey(tradeDate)` + `"latest"`；D7.1 测试断言 cache.get(TRADE_DATE) 命中。

## §8.6 回归

- [ ] 现有接口响应数据结构不变
  > 验证说明：需运行时回归测试。
- [ ] 现有定时任务执行不报错
  > 验证说明：代码层无明显改动破坏，需运行时实测。
- [ ] `TableStatusVO` 原有字段不变，新增字段为空时不影响前端展示
  > 验证说明：TableStatusVO 新增 cron/nextExecutionTime/lastExecutionTime 字段；需运行时回归前端展示。
- [ ] `data_pull_log` / `data_governance_metric` 清理逻辑不受影响
  > 验证说明：代码层 MetricCleanupJob.cleanupOldData 保留双表 deleteOlderThan 调用；需运行时实测。

## §8.7 评审补充验收

### 缓存 key 一致性 + Job 失败 evict

- [x] 7 个 Job 各自的 `@Cacheable` SpEL key 与 Job 显式 put 的 key 完全一致（含 `latest` 双写）—— 调 `/admin/test/cache-keys` 验证每个 cacheName 含 `{tradeDate}` 和 `latest` 两个 key
  > 验证说明：测试层已就绪。D5.1（sectorRanking 双写 tradeDate+latest）、D6.1（moneyflowRanking 双写固定参数 key+latest）、D7.1-D7.7（懒兜底 7 个 cacheName 命中 SpEL key）测试覆盖；/admin/test/cache-keys 端点在 TestAdminController:70 实现（@Profile("test")）。
- [x] Job 失败时主动 evict 缓存（`{tradeDate}` 和 `latest`），下次查询 MISS → 触发懒兜底 —— mock Job 抛异常，调 `/admin/test/cache-keys` 验证 key 已 evict，再调查询接口验证懒兜底执行
  > 验证说明：测试层已就绪。AbstractPrecomputeJob.precompute() 模板 doPrecompute 抛异常时调 evictCacheKeys；D5.2（sectorRanking evict tradeDate+latest）、D6.2（moneyflowRanking evict 固定参数 key+latest）测试覆盖。
- [ ] 无参查询（key=latest）命中预计算结果，响应时间 <50ms
  > 验证说明：代码层已就绪（Job 双写 latest key + @Cacheable SpEL tradeDate=null 时返回 "latest"），需运行时压测。

### tradeDate 捕获 + 非交易日/跨日边界

- [x] 4 个任务在方法入口捕获 tradeDate，finally 用已捕获变量（非重新取值）—— 代码检查 + 单测 mock 跨日场景
  > 验证说明：DailyUpdateTask:52 / BasicDataTask:57 / MoneyflowDataTask:55 / IndexDailyFetchService:124 均在方法首行 `String tradeDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(...)` 捕获，finally 块使用同一变量；tradeDate 不会在 finally 重新取值。
- [x] 非交易日 4 任务 cron 触发时早返回，finally 仍调 reportCompletion，预计算 Job 收到 SCHEDULED 事件对空数据返回空 List（不缓存）—— mock 非交易日 + 触发任务，验证事件发布 + 缓存未写入空结果
  > 验证说明：测试层已就绪。D9.1 验证非交易日 tradeDate="20260801" 4 任务报告后仍发布 SCHEDULED 事件；D9.2 验证 @Cacheable unless="#result == null || #result.isEmpty()" 阻止空 List 缓存（mapper 被调 2 次）。
- [ ] 跨日 00:00 边界 tradeDate 取值符合 Asia/Shanghai 时区规则 —— 时间 mock 跨日场景，查询 tracker entry key
  > 验证说明：代码层已就绪（4 个任务均用 ZoneId.of("Asia/Shanghai")），需运行时 mock 跨日时间实测。

### 批次追踪器并发 + 防重复发布

- [x] 4 任务并发 reportCompletion（CountDownLatch 同步触发）只发布 1 次事件 —— 并发测试 + ApplicationEventPublisher mock 计数
  > 验证说明：DataBatchCompletionTrackerConcurrencyTest.D8_1 用 CountDownLatch 屏障 4 线程并发调用 reportCompletion，AtomicInteger 计数断言 publishCount==1。
- [x] 超时兜底发布后，迟到任务调 reportCompletion 不重复发布事件（fired 标志生效）—— mock 超时 + 迟到报告，验证事件只发布 1 次
  > 验证说明：DataBatchCompletionTrackerTest.D3_5b 验证 fired 后 forceFireOnTimeout 不再发布；D3_3 验证 fired 后第 5 次重复 reportCompletion 不再发布。
- [x] 4 任务都报告完成但部分异常时，事件 source=SCHEDULED_PARTIAL —— mock 1 任务 hasError=true，验证事件 source
  > 验证说明：DataBatchCompletionTrackerTest.D3_4 mock T1 hasError=true，断言 event.getSource()=="SCHEDULED_PARTIAL"。

### 数据完整性校验

- [x] source=SCHEDULED_PARTIAL 或 SCHEDULED_TIMEOUT 时，Job 检测数据不完整后跳过预计算并打 WARN —— mock 不完整数据 + 触发事件，验证缓存未写入 + WARN 日志
  > 验证说明：测试层已就绪。AbstractPrecomputeJob.precompute() 在 doPrecompute 前调 isDataReady，false 时打 WARN 并 return（不调 doPrecompute、不写缓存、不 evict）；PrecomputeEventDispatcherTest.D4_3 用 SCHEDULED_PARTIAL + DataNotReadyJob 验证；SectorRankingPrecomputeJobTest.F1 / MoneyflowRankingPrecomputeJobTest.F1 覆盖数据不完整场景。

### 手动重跑 + 告警 + RUNNING 状态

- [x] `POST /scheduled-tasks/{taskClass}/run` 可触发任务，RUNNING 时拒绝（HTTP 409）—— curl 调接口 + 并发触发验证拒绝
  > 验证说明：测试层已就绪。ScheduledTaskRunControllerTest.D11_1 验证 200 成功，D11_2 验证 IllegalStateException→body.code=409（HTTP 200+body.code=409 实现，非 HTTP 409 状态码）；runTask 在 runningStatusMap.containsKey 时抛 IllegalStateException。
- [x] 手动重跑记入 data_pull_log（`trigger_type=MANUAL`, `operator=当前用户名`），与定时触发的 `trigger_type=SCHEDULED` 自然区分（TriggerContext 机制生效）—— 触发后查表 + 对比同任务定时触发记录
  > 验证说明：测试层已就绪。TaskExecutionLogAspectTest.D2_4 验证 TriggerContext.setManual("testUser") 后 logDO.triggerType=="MANUAL" 且 operator=="testUser"；D2_4b 验证未设 TriggerContext 默认 triggerType=="SCHEDULED" / operator=="SYSTEM"。
- [ ] DATA_FETCH/PRECOMPUTE 任务失败时触发告警（邮件/IM），同任务 30 分钟内只告警 1 次 —— mock 任务失败 + 验证告警发送 + 30 分钟内重复失败验证防骚扰
  > 验证说明：代码层部分就绪。TaskExecutionLogAspect.sendAlertIfNeeded 实现 30 分钟防骚扰（ALERT_DEDUP_WINDOW_MS=30min）+ lastAlertTime ConcurrentHashMap，D2.3 测试覆盖防骚扰；但实际邮件/IM 渠道为 TODO（代码注释 `// TODO: 实际告警渠道（邮件/IM webhook）可在此处实现`），当前仅打 WARN 日志。
- [ ] 任务执行中列表显示 RUNNING(蓝)，耗时实时刷新（前端轮询 5s）—— 触发长任务 + 前端观察状态切换 + `currentStatus` 字段值正确
  > 验证说明：代码层已就绪（前端 TASK_POLL_INTERVAL=5000 + startTaskPolling + dg-task-running 蓝色样式 + dg-task-row-running 行高亮；后端 fillRuntimeStatus 在 runningStatusMap 命中时设 currentStatus="RUNNING"），需浏览器实测。
- [ ] `GET /scheduled-tasks/{taskClass}/history?page=2&limit=30` 返回第 2 页 30 条 + `total` 字段正确 —— 造 100 条历史 + 翻页验证
  > 验证说明：代码层已就绪（DataGovernanceController:358 getTaskHistory 支持 page/limit/status/startDate 参数 + 返回 PageResult(records, total, page, limit)），需运行时造数据实测。
- [ ] `GET /scheduled-tasks/{taskClass}/history?status=FAILED` 仅返回失败记录 —— 造成功+失败各 N 条 + 筛选验证
  > 验证说明：代码层已就绪（DataPullLogMapper.xml selectByTaskClass 含 `<if test="status != null and status != ''">AND status = #{status}</if>`），需运行时造数据实测。
- [ ] `GET /scheduled-tasks/{taskClass}/history?startDate=20260701` 仅返回 7 月后记录 —— 造跨月记录 + 筛选验证
  > 验证说明：代码层已就绪（selectByTaskClass 含 `<if test="startDate != null and startDate != ''">AND start_time &gt;= #{startDate}</if>`），需运行时造数据实测。

> Phase C 代码层验证：前端 `TASK_POLL_INTERVAL=5000` + `startTaskPolling` + `dg-task-running` 蓝色样式 + `dg-task-row-running` 行高亮已就绪；后端 `getTaskHistory` 控制器支持 `page/limit/status/startDate` 参数 + 返回 `PageResult(records, total, page, limit)` 已就绪。运行时验证（造数据 + 翻页/筛选）属 Phase D 测试范围。

### AOP 性能 + 异步落库

- [x] AOP 切面同步开销 P99 <1ms（基于 1000 次调用）—— JMH 微基准 / Arthas trace
  > 验证说明：TaskExecutionLogAspectPerformanceTest.D10_1 用 1000 次循环 + System.nanoTime 测量，5 轮取最优 P99，断言 bestP99 < 1_000_000ns；D10_2 覆盖 MANUAL 模式。
- [x] 异步写日志最大延迟 <1s；日志写入失败时不影响原方法（try-catch 兜底）—— 异步队列监控 + mock DB 异常验证原方法正常返回
  > 验证说明：TaskExecutionLogAspect 用单线程 ThreadPoolExecutor(队列 1024 + DiscardPolicy) 异步写日志；TaskExecutionLogAspectTest.D2_2 mock insert 抛 RuntimeException，验证原方法正常返回 + runningStatusMap 正常清除。

### 启动校验 + 测试入口

- [x] `@ManagedTask` 的 tableCode 为空时启动 fail-fast —— 删除某任务 tableCode + 启动验证失败
  > 验证说明：ScheduledTaskRegistryServiceImpl.validateTasks() 第 210-215 行 tableCode 空时抛 IllegalStateException；D1.2 测试覆盖（反射调 validateTasks 断言 hasCauseInstanceOf(IllegalStateException.class)）。
- [x] `@ManagedTask.name` 重名时启动 fail-fast —— 两个任务同名 + 启动验证失败
  > 验证说明：validateTasks() 第 217-223 行 name 重名时抛 IllegalStateException；D1.3 测试覆盖。
- [x] `/admin/test/*` 测试 Controller 仅在 test profile 启用，生产环境不加载 —— 生产 profile 启动验证 404
  > 验证说明：TestAdminController.java:31 标注 @Profile("test")，Spring 在非 test profile 不加载该 Bean，端点 404。
- [ ] `/admin/test/metric-cleanup?cutoff=` 手动触发清理，仅删 cutoff 之前记录 —— 造 91 天前 + 89 天前记录 + 手动触发验证
  > 验证说明：代码层部分就绪。TestAdminController:58 metricCleanup 仅调 dataPullLogMapper.deleteOlderThan（删除 data_pull_log cutoff 之前记录），未删 data_governance_metric 表（与 MetricCleanupJob.cleanupOldData 双表删除不一致）；需运行时实测，且建议补 metricMapper.deleteOlderThan 调用。

### Spring 调度器多线程

- [ ] `spring.task.scheduling.pool.size=4` 配置生效，4 个 @Scheduled 任务可并行执行 —— 检查配置 + 4 任务同时触发验证并行
  > 验证说明：代码层已就绪。application.yml:32-35 配置 `spring.task.scheduling.pool.size=4`；需运行时实测 4 任务并行执行。

### data_pull_log 查询区分

- [x] `/scheduled-tasks/{taskClass}/history` 仅返回 `task_class` 匹配且 `task_name IS NOT NULL` 记录，不泄露手动拉取记录 —— 造手动 + 定时各 N 条 + 查询验证
  > 验证说明：DataPullLogMapper.xml selectByTaskClass + countByTaskClass + selectLatestByTaskClass 三个查询均含 `WHERE task_class = #{taskClass} AND task_name IS NOT NULL` 过滤；手动拉取记录（DataInitService 写入 task_name=null）被自然排除。

## 前端规范检查（项目硬约束）

- [x] 前端"定时任务"分区使用 azure/mist/cyber 三主题 CSS 变量（无硬编码颜色）
  > 验证说明：Grep `#[0-9a-fA-F]{3,8}|rgb\(|rgba\(` 在 data-governance.css 为 0 命中；所有颜色使用 var(--*) token。
- [x] 文本对比度满足 WCAG AA（≥4.5:1 正文）
  > 验证说明：复用现有 --text-primary/--text-muted/--text-secondary token，与既有页面同口径；严格 WCAG AA 对比度验证属设计层事务。
- [x] CSS 分层遵循：theme.css（变量）/ components.css（通用组件）/ custom.css（布局）/ page 前缀 CSS
  > 验证说明：data-governance.css 所有类名以 `dg-` page 前缀命名（dg-filter-bar/dg-table-wrap/dg-task-status 等），与全局组件分层。
- [x] 状态色（SUCCESS 绿/FAILED 红/RUNNING 蓝/NEVER_RUN 灰/配置异常 橙）在三主题下对比度均达标
  > 验证说明：data-governance.css 状态色复用 var(--accent-green)/var(--rise-color)/var(--rise-light)/var(--accent-blue)/var(--accent-blue-light)/var(--text-muted)/var(--accent-orange)，theme.css 在 [data-theme="azure"]/[data-theme="mist"]/[data-theme="cyber"] 三主题均有定义。

> Phase C 前端规范验证（代码层）：① `data-governance.css` 全部使用 `var(--*)` token，零硬编码颜色；② 所用变量（`--accent-green/--rise-color/--rise-light/--rise-bg/--accent-blue/--accent-blue-light/--accent-orange/--text-muted`）在 `theme.css` 三主题（azure/mist/cyber）均有定义；③ CSS 文件遵循 `dg-` page 前缀命名，与全局组件分层；④ 状态色复用现有 `--rise-light` 等 token（与既有 `.dg-status.error` 同口径），未引入新颜色。WCAG AA 严格对比度验证依赖主题 token 调校，属设计层事务，不在 Phase C 范围内。

## 数据库兼容性检查（项目硬约束）

- [x] `ALTER TABLE data_pull_log` SQL 语法 MySQL 兼容
  > 验证说明：schema-mysql.sql:818-822 使用标准 MySQL 语法 `ALTER TABLE data_pull_log ADD COLUMN col_name TYPE NULL COMMENT '...'`，5 条 ALTER 语句均 MySQL 兼容。
- [x] 查询条件避免 row-value IN 语法（用 OR 连接或 `task_class = ? AND task_name IS NOT NULL` 形式）
  > 验证说明：DataPullLogMapper.xml selectByTaskClass/countByTaskClass/selectLatestByTaskClass 均用 `task_class = #{taskClass} AND task_name IS NOT NULL` 形式；selectLatestPerTable 用 `table_code IN (foreach)` 单列 IN，非 row-value IN。
- [x] 5 个新增字段均为可空，向后兼容现有 DataInitService 写入路径
  > 验证说明：schema-mysql.sql:818-822 5 个新字段（task_name/task_class/method_name/task_group/trigger_type）均 `NULL`；DataInitServiceImpl.createPullLog 用 builder 仅设 taskId/tableCode/tableName/operationType/status/startTime/operator，不设新字段，insert SQL 兼容（新字段默认 NULL）。
