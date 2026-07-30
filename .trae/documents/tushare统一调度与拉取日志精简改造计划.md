# Tushare 统一调度与拉取日志精简改造计划

## 摘要

本次改造将所有 Tushare 数据采集定时入口合并到唯一调度类 `TushareDataScheduler`，用同一类中的多个 `@Scheduled` 方法保留不同接口现有的执行频率与错峰时间；非 Tushare 的数据检测、健康检查、清理、因子计算等定时任务不合并。

同时精简 `data_pull_log`：删除 `success_count`、`fail_count`、`error_stack`，保留 `total_count` 与 `error_message`。任何执行单元失败都将使该表本次任务标记为 `FAILED`，完整异常堆栈仅通过应用日志输出，数据库只保存脱敏、限长的错误摘要。

## 当前状态分析

### 调度现状

当前 Tushare 更新分散在以下入口：

- `stock-watcher/src/main/java/com/arthur/stock/task/DailyUpdateTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/MoneyflowDataTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/IndexBasicTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/IndexWeightTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/StockNamechangeTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/StockSuspendDTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/StockStkLimitTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/task/SwIndustryTask.java`
- `stock-watcher/src/main/java/com/arthur/stock/service/IndexDailyFetchService.java` 中的 `dailySync()`

这些入口直接调用不同业务 Service，异常处理、缓存清理、拉取日志和质量检测行为不一致。手动增量/全量更新则统一经过 `DataInitServiceImpl.executeSingleStep()`，并写入 `data_pull_log`。

`InitStep` 当前定义 28 个可独立执行步骤，不应继续按“20+”或固定 25 个写死覆盖范围。统一调度测试应以 `InitStep` 中实际属于 Tushare 采集的枚举值为准，检查无遗漏、无重复。

### 日志现状

以下层次仍依赖待删除字段：

- 表结构：`stock-watcher/src/main/resources/schema-mysql.sql`
- 实体：`DataPullLogDO`
- API 模型：`PullLogVO`
- Mapper：`DataPullLogMapper.java`、`DataPullLogMapper.xml`
- Service：`DataInitServiceImpl.finishPullLog()` 与异常堆栈字符串化逻辑
- Controller：`DataGovernanceController.convertPullLogToVO()` 及日志详情描述
- 页面：`data-governance.html`、`data-governance.js`

当前 `executeSingleStep()` 的部分分支会捕获子项失败并返回 `StepStats(total, success, fail)`，只在全部失败时抛异常。这与“没有部分成功场景”的新语义冲突，必须改为只要 `fail > 0`，该步骤最终就是 `FAILED`。

### 数据库迁移现状

`schema-mysql.sql` 使用 `CREATE TABLE IF NOT EXISTS`，只能影响新库，无法删除存量库字段。项目没有 Flyway/Liquibase，但已有 `StrategySchemaMigration` 的启动期幂等迁移模式，因此本次沿用相同机制。

### 锁与执行方式

`DataInitService.incrementalUpdate()`、`fullRebuild()` 是面向前端的异步单表入口，并使用全局任务锁。调度器不能循环调用现有异步方法，否则第一个任务尚未释放锁时，后续任务会被拒绝。需要增加同步的内部调度入口，由一个批次获取一次全局锁并按顺序执行各步骤。

## 改造方案

### 统一调度类

新增 `stock-watcher/src/main/java/com/arthur/stock/task/TushareDataScheduler.java`，作为唯一 Tushare 定时采集组件。

该类只负责：

- 声明不同时间对应的 `@Scheduled` 方法；
- 组织每个时点需要运行的 `InitStep` 列表；
- 调用 `DataInitService` 的同步定时批次入口；
- 输出批次开始、结束、跳过和失败汇总日志；
- 在相关步骤成功后执行原有缓存失效策略。

该类不直接注入 20 多个业务 Service，不直接访问 Mapper、数据库或 `TushareClient`。

首轮改造以保持现有调度行为为原则，不擅自调整接口出数时间。调度方法按现有 cron 合并如下：

- 每日 16:00：`TRADE_CAL`、`STOCK_BASIC`、`DAILY`、`ADJ_FACTOR`、`DIVIDEND`。
- 工作日 16:10：`DAILY_BASIC`、`MONEYFLOW`、`HK_HOLD`、`TOP_LIST`、`TOP_INST`、`BLOCK_TRADE`、`MARGIN`、`MARGIN_DETAIL`。
- 工作日 16:25：`INDEX_BASIC`。
- 工作日 16:30：`INDEX_DAILY`；每日 16:30：`NAMECHANGE`。
- 每日 16:35：`SUSPEND_D`。
- 每日 16:40：`STK_LIMIT`。
- 工作日 20:00：`INDEX_WEIGHT`。
- 周日 17:00 至 20:30：按当前半小时间隔分别执行 `FINA_INDICATOR`、`INCOME`、`BALANCESHEET`、`CASHFLOW`、`FORECAST`、`EXPRESS`、`STK_HOLDERNUMBER`、`STK_HOLDERTRADE`。
- 每月 1 日 22:00：`SUSPEND_D` 定时全量；每月 1 日 22:30：`STK_LIMIT` 定时全量。
- 每季度首月 1 日 22:00：`NAMECHANGE` 定时全量。
- 每年 1 月、7 月 1 日 22:00：`SW_INDUSTRY` 定时同步。

同一时点且同为增量的步骤放入同一个方法顺序执行；不同频率或全量语义使用不同方法。若多个现有任务恰好同一时刻触发，统一类内明确调用顺序，避免依赖 Spring 对同刻任务的非确定调度顺序。

`DataGovernanceCheckJob`、`DataSourceHealthJob`、`MetricCleanupJob`、`DataVerifyTask`、`FactorSnapshotTask`、`ScreenLockTrackingTask`、`StockCodeCache` 不属于 Tushare 数据采集，继续独立保留。

### 统一执行入口

修改 `stock-watcher/src/main/java/com/arthur/stock/service/DataInitService.java`，新增面向内部调度的同步入口，采用显式操作模式：

- `scheduledIncrementalBatch(String batchName, List<InitStep> steps)`
- `scheduledFullUpdate(String batchName, InitStep step)`

修改 `DataInitServiceImpl`：

- 批次开始时尝试获取现有全局拉取锁；获取失败则整批跳过并打印警告，不创建悬空的 `RUNNING` 日志。
- 获取锁后按列表顺序同步执行，每个 `InitStep` 单独生成 taskId，并单独写一条 `operation_type = SCHEDULED` 的拉取日志。
- 单表失败不阻断批次内其他无依赖步骤；失败步骤写 `FAILED`，批次结束日志汇总失败表代码。
- 存在前置依赖的步骤按固定顺序执行。若前置步骤失败，依赖步骤不执行，并以 `FAILED` 记录 `error_message = 前置步骤 xxx 失败，已跳过`，避免使用旧数据伪装为成功。
- 批次结束或异常时在 `finally` 释放全局锁。
- 手动异步增量、手动异步全量与取消功能保持现有接口契约。

首轮继续采用全局锁，不引入表级锁，避免扩大并发与一致性改造范围。

### 失败语义收敛

保留 `StepStats` 作为 Service 内部统计工具，但不再持久化成功数和失败数。

所有步骤执行完成后统一判定：

- `fail == 0`：`SUCCESS`。
- `fail > 0`：抛出带步骤摘要的业务异常，最终记为 `FAILED`。
- 用户取消：`CANCELLED`。
- 未捕获异常：`FAILED`。

调整 `TRADE_CAL`、`INDEX_WEIGHT`、`SW_INDUSTRY`、逐股票拉取、逐交易日拉取等会吞掉子项异常的分支。子项异常若被捕获后继续处理，捕获处必须打印包含异常对象的完整堆栈；如果异常继续向外抛，则只由最外层打印一次，避免重复堆栈。

### 错误日志策略

修改 `DataInitServiceImpl`：

- 删除 `PrintWriter`、`StringWriter` 和 `getStackTrace(Throwable)`。
- 所有任务级失败使用 `log.error("...", e)` 输出完整堆栈。
- 所有被内部捕获并继续执行的子项失败使用 `log.warn("...", e)` 输出完整堆栈。
- 数据库 `error_message` 仅保存异常摘要：优先使用异常 message；message 为空时使用异常类名；经过 `SensitiveDataUtil.mask()` 脱敏；截断到 1024 字符。
- 将 1024 抽取为具名常量，避免超长错误导致日志状态二次写入失败。
- `finishPullLog()` 删除 `errorStack` 参数，只更新状态、结束时间、耗时、总数和错误摘要。

### 拉取日志模型

修改以下文件，删除 `successCount`、`failCount`、`errorStack`：

- `stock-watcher/src/main/java/com/arthur/stock/model/DataPullLogDO.java`
- `stock-watcher/src/main/java/com/arthur/stock/dto/governance/PullLogVO.java`

`totalCount` 保留，统一解释为“本次处理单元总数”，不承诺始终等于实际写入数据库行数。

修改 `DataPullLogMapper.java`，将当前 9 参数的 `updateStatus()` 改为接收显式 `DataPullLogDO` 更新对象，减少参数漂移风险。

修改 `DataPullLogMapper.xml`：

- resultMap、INSERT、UPDATE 删除三个旧字段。
- 抽取当前字段的公共显式列清单，替换 `SELECT *`。
- 分页、单表历史、详情、超时恢复、清理和各表最新日志查询行为保持不变。

### 数据库结构与迁移

修改 `stock-watcher/src/main/resources/schema-mysql.sql`，从 `data_pull_log` 建表语句删除：

- `success_count`
- `fail_count`
- `error_stack`

新增 `stock-watcher/src/main/java/com/arthur/stock/migration/DataPullLogSchemaMigration.java`：

- 在数据库初始化完成后执行。
- 先检查 `data_pull_log` 是否存在，不存在则跳过。
- 分别检查三个旧列是否存在，只对存在的列逐个执行 `ALTER TABLE ... DROP COLUMN`。
- 支持旧结构、部分迁移结构和新结构重复启动。
- 删除列失败时抛出启动异常，禁止应用以代码与表结构不一致的状态继续运行。
- 不删除或改写历史日志行的其他字段。

### Controller 与前端

修改 `DataGovernanceController.java`：

- `convertPullLogToVO()` 删除三个旧字段映射。
- 删除管理员专属 `errorStack` 裁剪逻辑。
- 日志详情接口描述改为“返回错误摘要，完整异常堆栈仅记录于服务日志”。
- 现有 URL、分页参数、权限控制和响应包装保持不变。

修改 `data-governance.html`：

- 拉取历史删除“成功/失败”展示，改为单列“处理总数”。
- 同步修正加载、空状态、异常状态对应的 `colspan`。

修改 `data-governance.js`：

- 列表不再拼接 `successCount / failCount`，展示 `totalCount`。
- 详情删除成功数、失败数和管理员错误堆栈区域。
- 保留并继续 HTML 转义 `errorMessage`。
- 不因本次改造删除页面其他功能仍需使用的管理员状态。

### 缓存行为

迁移旧调度类时保留缓存失效语义：

- `DAILY`、`ADJ_FACTOR` 成功后清理 `kline`。
- 日线、指数相关批次成功后清理 `sectorRanking`。
- `MONEYFLOW` 成功后清理 `sectorMoneyflow`。
- `DAILY_BASIC` 成功后清理 `sectorValuation`。

缓存清理应放在统一执行链的步骤成功回调中，不能因批次中其他步骤失败而遗漏已经成功写入数据的缓存失效，也不能在对应步骤失败且未写入有效数据时误报整批更新成功。

### 删除旧入口

确认无源代码、测试、配置或反射引用后，删除以下旧调度类，防止新旧任务双重执行：

- `DailyUpdateTask.java`
- `BasicDataTask.java`
- `MoneyflowDataTask.java`
- `IndexBasicTask.java`
- `IndexWeightTask.java`
- `StockNamechangeTask.java`
- `StockSuspendDTask.java`
- `StockStkLimitTask.java`
- `SwIndustryTask.java`

从 `IndexDailyFetchService.java` 删除 `@Scheduled` 入口 `dailySync()`；保留被 `DataInitServiceImpl` 调用的业务拉取方法，Service 不再承担调度职责。

### 文档同步

修改以下当前权威或活跃文档：

- `README.md`
- `.trae/rules/stock-watcher/business/02-tushare-integration.md`
- `.trae/rules/stock-watcher/business/03-tushare-interfaces.md`
- 若存在 `.trae/rules/stock-watcher/business/05-data-governance-center.md`，同步更新其日志字段与权限说明。

文档需明确：

- Tushare 采集只由 `TushareDataScheduler` 调度。
- 不同出数频率对应同一类中的不同方法。
- 定时执行复用 `DataInitService` 的统一步骤逻辑。
- `data_pull_log` 只保存状态、耗时、处理总数和错误摘要。
- 完整异常堆栈只进入 watcher 应用日志。
- 接口数量以 `InitStep` 当前枚举为准，避免固定数字继续漂移。

历史 PRD/spec 不作为本次实现的修改重点；若仍被活跃文档引用，则增加“已被本次设计替代”的说明。

## 假设与决策

- “只保留一个 class”解释为仅合并 Tushare 数据采集调度类，不合并数据检测、健康检查、清理、因子计算等非采集任务。
- 本次保留已有 cron 与错峰频率，不主动改变 Tushare 接口更新时间；统一调度后如需优化出数时点，另行基于实际空跑/延迟监控调整。
- 定时增量和定时全量都使用 `operation_type = SCHEDULED`，不冒充手动任务。
- 任一子执行单元失败即整张表本次拉取失败，不再存在“部分成功”状态。
- 同一批次中无依赖的后续表继续执行；有明确依赖的后续表在前置失败时跳过并记失败原因。
- `total_count` 保留，含义是处理单元数量，不新增替代成功/失败计数的字段。
- 使用现有全局锁保证手动与定时更新互斥，不在本次引入分布式锁或表级并发。
- 存量数据库采用启动期幂等迁移，不要求人工执行一次性 SQL。

## 验证步骤

### 单元测试

新增或补充以下测试：

- `TushareDataSchedulerTest`：直接调用各调度方法，验证传入步骤、顺序、操作模式、全量/增量区分；验证所有 Tushare `InitStep` 无遗漏、无重复。
- `DataInitServiceImplTest`：验证定时批次锁、逐表日志、顺序执行、失败隔离、依赖跳过、质量检测与缓存失效。
- 验证任一子项失败时状态为 `FAILED`，不再出现部分成功落库。
- 验证失败数据库记录只有脱敏且不超过 1024 字符的 `errorMessage`。
- 验证异常对象交给日志框架，而不是转换成字符串传给 Mapper。
- `DataGovernanceControllerTest`：验证日志 JSON 不包含 `successCount`、`failCount`、`errorStack`，并保留 `totalCount`、`errorMessage`。

### 迁移测试

新增 `DataPullLogSchemaMigrationTest`，覆盖：

- 三个旧字段全部存在。
- 仅部分旧字段存在。
- 三个旧字段均不存在。
- `data_pull_log` 不存在。
- 重复执行迁移。
- 迁移后历史行的其他字段仍保留。

### Mapper 与数据库验证

使用 MySQL 测试环境验证：

- 新库可由更新后的 `schema-mysql.sql` 初始化。
- 存量库启动后自动删除三个旧列。
- INSERT、状态更新、分页、详情、单表历史、最新日志、超时恢复与清理 SQL 均不引用旧列。
- 迁移失败会阻止应用启动并给出明确日志。

### 前端回归

验证数据管理中心：

- 拉取历史表头与数据列对齐。
- 处理总数正常展示。
- 日志详情不再显示成功数、失败数和堆栈。
- 失败任务仍显示错误摘要。
- 管理员与普通用户获得相同日志字段结构。
- 手动增量、全量、取消、进度轮询和日志筛选不受影响。

### 静态检查

全库搜索以下旧字段，活跃源代码、SQL、页面和当前规范中不得再出现：

- `success_count`、`fail_count`、`error_stack`
- `successCount`、`failCount`、`errorStack`
- `getStackTrace(`

全库搜索 `@Scheduled`：

- 所有 Tushare 数据采集注解只存在于 `TushareDataScheduler`。
- 其他命中只能是数据检测、健康检查、清理、缓存、因子或业务追踪等非 Tushare 采集任务。

### 构建与回归

- 执行 `stock-watcher` 单元测试与集成测试。
- 执行 Maven 编译，确保删除旧类和字段后无引用残留。
- 在测试库启动 watcher，确认 schema 初始化、迁移和 Spring 调度 Bean 注册成功。
- 手工调用统一调度方法对应的 Service 测试入口或直接运行单元测试，不等待真实 cron 时间。
- 检查失败场景应用日志包含完整堆栈，数据库仅有错误摘要。
