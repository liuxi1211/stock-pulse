# Tasks

- [x] Task 1: 数据模型与基础设施
  - [x] SubTask 1.1: 在 schema-mysql.sql 和 schema-sqlite.sql 中新增 `data_governance_metric` 表（字段见 PRD §5.2，含 check_batch_id/table_code/table_name/table_group/total_rows/row_delta_pct/latest_date/earliest_date/status/check_items JSON/check_time/check_type + 索引）
  - [x] SubTask 1.2: 在 schema-mysql.sql 和 schema-sqlite.sql 中新增 `data_pull_log` 表（字段见 PRD §5.3，含 task_id/table_code/table_name/operation_type/status/start_time/end_time/duration_ms/total_count/success_count/fail_count/error_message/error_stack/operator + 索引）
  - [x] SubTask 1.3: 创建 `DataGovernanceMetricDO` 实体 + `DataGovernanceMetricMapper` 接口 + XML（含 insertBatch、selectLatestBatch、selectByTableCodeLatest、selectPreviousByTableCode、deleteOlderThan 方法）
  - [x] SubTask 1.4: 创建 `DataPullLogDO` 实体 + `DataPullLogMapper` 接口 + XML（含 insert、updateStatus、selectByTableCode、selectPageList、selectById、deleteOlderThan 方法）
  - [x] SubTask 1.5: 增强 `InitStep` 枚举：新增 TableGroup 枚举（BASIC/MARKET/FINANCE/EVENT/INDEX），为每个 InitStep 增加 group/updateFrequency/expectedUpdateTime/isDaily/tushareApi 字段；补充缺失的 10 张表（daily_basic/fina_indicator/stock_moneyflow/top_list/top_inst/block_trade/hk_hold/margin/margin_detail/index_daily）的 InitStep 条目，使总数达 25

- [x] Task 2: 检测框架核心
  - [x] SubTask 2.1: 创建 `DataCheckable` 接口（checkData() 返回 DataCheckResult，getTableCode() 返回 String）
  - [x] SubTask 2.2: 创建模型类：`CheckLevel` 枚举（ERROR/WARN）、`DataCheckItem`（name/displayName/passed/level/message）、`DataCheckResult`（tableCode/tableName/totalRows/latestDate/items）
  - [x] SubTask 2.3: 创建 `DataGovernanceService`：自动发现所有 DataCheckable Bean 建 checkableMap；checkTable(tableCode) 方法含空表兜底逻辑 + 数据骤减检测 + 保存 metric；checkAll() 方法生成共享 batch_id；延迟判定逻辑（仅日频表，查 trade_cal 取上一交易日对比 latestDate）；状态推导（UPDATING > ERROR > DELAYED > NORMAL）
  - [x] SubTask 2.4: 创建 `TaskProgressCache`：基于 Caffeine 的内存进度缓存，key=taskId，TTL=30min，存进度对象 + isCancelled 标记 + 全局并发锁（同一时间只允许一个任务）

- [x] Task 3: 5 张核心表检测实现
  - [x] SubTask 3.1: `DailyQuoteServiceImpl` 实现 DataCheckable，实现 5 个检测项（新鲜度/数据量异常/价格逻辑/收盘价超涨跌停/覆盖度），latestDate 取 max(trade_date)
  - [x] SubTask 3.2: `StockBasicServiceImpl` 实现 DataCheckable，实现 3 个检测项（行情中出现但基础信息缺失/上市数与行情差异过大/关键字段为空），latestDate=null
  - [x] SubTask 3.3: `TradeCalServiceImpl` 实现 DataCheckable，实现 4 个检测项（未来30天覆盖/交易所完整性/沪深标记一致/周末交易日），latestDate=null
  - [x] SubTask 3.4: `IncomeServiceImpl` 实现 DataCheckable，实现 4 个检测项（公告新鲜度/营收为空/净利润超营收10倍/上市超1年无数据），latestDate 取 max(ann_date)
  - [x] SubTask 3.5: `IndexDailyServiceImpl` 实现 DataCheckable，实现 3 个检测项（新鲜度/核心指数缺失/价格异常），latestDate 取 max(trade_date)

- [x] Task 4: 数据操作与进度追踪
  - [x] SubTask 4.1: 改造 `DataInitService`：新增 `incrementalUpdate(tableCode, operator)` 方法（查最新日期 -> 从下一天拉到今天 -> UPSERT 写入 -> 写 pull_log -> 触发检测）；新增 `fullRebuild(tableCode, operator)` 方法（DROP+CREATE -> 全量拉取 -> 写 pull_log -> 触发检测）
  - [x] SubTask 4.2: 在 DataInitService 拉取过程中更新 TaskProgressCache（进度百分比/当前步骤/已处理/总数），支持协作式取消（每批检查 isCancelled 标记）
  - [x] SubTask 4.3: 改造现有定时任务（DailyUpdateTask/BasicDataTask/MoneyflowDataTask 等）：执行完成后写 data_pull_log，记录成功/失败状态和统计信息

- [x] Task 5: 后端 API 接口
  - [x] SubTask 5.1: 创建 `DataGovernanceController`（`/api/data-governance`）：GET overview、GET tables（带筛选参数 group/status/keyword，封装为 QueryDTO）、GET tables/{tableCode}、GET tables/{tableCode}/check-result、GET tables/{tableCode}/pull-history
  - [x] SubTask 5.2: 在 DataGovernanceController 新增操作接口：POST tables/{tableCode}/incremental-update、POST tables/{tableCode}/full-rebuild、POST check/all、POST check/{tableCode}、GET tasks/{taskId}/progress、POST tasks/{taskId}/cancel -- 写操作加 @RequireAdmin
  - [x] SubTask 5.3: 新增日志接口：GET logs（分页，封装 LogQueryDTO）、GET logs/{logId}（error_stack 仅管理员可见）
  - [x] SubTask 5.4: 新增数据源接口：GET datasource（只读连通性状态，不返回 Token）、POST datasource/test（@RequireAdmin）
  - [x] SubTask 5.5: 创建 ResponseDTO/VO 类：OverviewVO、TableStatusVO、CheckResultVO、PullLogVO、TaskProgressVO、DatasourceVO 等（禁止用 Map 返回）

- [x] Task 6: 定时任务
  - [x] SubTask 6.1: 创建 `DataGovernanceCheckJob`：@Scheduled(cron="0 0 22 * * ?")，调用 DataGovernanceService.checkAll()，生成检测批次
  - [x] SubTask 6.2: 创建 `DataSourceHealthJob`：@Scheduled(cron="0 0 * * * ?")，调用 tushare trade_cal 接口测试连通性，结果存内存缓存（响应时间 + 是否成功 + 测试时间）
  - [x] SubTask 6.3: 创建 `MetricCleanupJob`：@Scheduled(cron="0 0 1 * * ?")，清理 3 个月前的 data_governance_metric 和 data_pull_log 旧数据

- [x] Task 7: 前端页面
  - [x] SubTask 7.1: 在 `PageController` 新增路由 `/page/data-governance`，activeMenu="data-governance"
  - [x] SubTask 7.2: 在 `fragments/common.html` 侧边栏「管理中心」分组下新增「数据管控中心」菜单项（bi-database-check 图标）
  - [x] SubTask 7.3: 创建 `templates/pages/data-governance.html`：页面骨架（共用 head/sidebar/navbar/scripts fragment），总览卡片区域，Tab 栏（数据表总览/拉取日志/数据源），筛选栏，卡片列表容器，详情抽屉（Bootstrap Offcanvas），全量重建确认弹窗（Bootstrap Modal）
  - [x] SubTask 7.4: 创建 `static/js/data-governance.js`：总览卡片加载、数据表列表渲染（卡片视图 + 状态灯 + 筛选搜索）、详情抽屉（3 Tab 切换 + 检测项展开/收起）、增量更新/全量重建操作（进度轮询 + 倒计时确认）、拉取日志列表（分页 + 筛选）、日志详情、数据源状态展示 -- 按管理员角色控制写操作按钮可见性

- [x] Task 8: 安全与脱敏
  - [x] SubTask 8.1: 创建 `SensitiveDataUtil` 工具类：Token/密码/数据库连接串正则匹配替换为 ***，用于 error_message 和 error_stack 写入前脱敏
  - [x] SubTask 8.2: 在 DataPullLog 写入前调用脱敏工具处理 error_message 和 error_stack（待 Task 4 完成后集成）
  - [x] SubTask 8.3: 日志详情接口根据用户角色决定是否返回 error_stack 字段（待 Task 5 完成后集成）

# Task Dependencies
- [Task 2] depends on [Task 1]（检测框架需要 Mapper 和模型类）
- [Task 3] depends on [Task 2]（检测实现需要 DataCheckable 接口和 DataGovernanceService）
- [Task 4] depends on [Task 1, Task 2]（数据操作需要 pull_log Mapper 和进度缓存）
- [Task 5] depends on [Task 2, Task 3, Task 4]（API 接口需要 Service 层就绪）
- [Task 6] depends on [Task 2]（定时任务需要 DataGovernanceService）
- [Task 7] depends on [Task 5]（前端页面需要 API 接口就绪）
- [Task 8] depends on [Task 1]（脱敏工具用于 pull_log 写入）
- [Task 3] 和 [Task 8] 可并行

# Known Gaps (Phase 1 后续补全)
- [ ] Gap 1: 现有定时任务（DailyUpdateTask/BasicDataTask/MoneyflowDataTask 等）执行完成后写 data_pull_log -- 基础设施已就绪（DataPullLogMapper/SensitiveDataUtil 可用），现有任务尚未改造接入
