# 数据管控中心 Spec

## Why

当前系统的 25+ 张 tushare 数据表处于"黑盒"状态：数据量、新鲜度、更新状态、数据质量均不可见。数据出问题只能翻日志查数据库，定位慢、修复繁琐。需要一个一站式数据管控面板，实现"看得见、摸得着、查得到、信得过"。

本 spec 覆盖 PRD Phase 1（MVP · 核心闭环）：总览能看、列表能查、单表能更、检测能跑。

## What Changes

- **新增前端页面** `/page/data-governance`（路由 + 侧边栏菜单），含 3 个 Tab：数据表总览、拉取日志、数据源
- **新增数据模型**：`data_governance_metric`（检测历史）+ `data_pull_log`（拉取日志）两张表，MySQL/SQLite 双 schema
- **新增检测框架**：`DataCheckable` 接口 + `DataGovernanceService` 调度器 + 空表兜底 + 数据骤减自动追加 WARN
- **实现 5 张核心表检测项**：daily_quote、stock_basic、trade_cal、income、index_daily
- **新增操作能力**：增量更新（UPSERT）、全量重建（二次确认 + 倒计时）、进度追踪（内存缓存 + 轮询）、并发控制
- **新增定时任务**：每晚 22:00 全表检测、每小时数据源连通性测试、每天凌晨清理 3 个月旧数据
- **改造 InitStep 枚举**：增加 group、updateFrequency、expectedUpdateTime、isDaily 频率元信息字段
- **改造 DataInitService**：写拉取日志、拉取完成后自动触发质量检测、支持单表增量/全量操作入口
- **权限控制**：写操作 @RequireAdmin、读操作登录即可、error_stack 分级可见

### 不在 Phase 1 范围内

- 其余 20 张表的检测项实现（Phase 2）
- 全量重建索引后置优化、多线程拉取（Phase 2）
- 临时表 + 原子交换零停机重建（Phase 3）
- 邮件告警、数据导出、智能修复（Phase 3）

## Impact

- Affected specs: 无前置 spec 依赖，独立模块
- Affected code:
  - `stock-watcher/src/main/resources/schema-mysql.sql` / `schema-sqlite.sql` — 新增 2 张表
  - `stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java` — 枚举增强
  - `stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java` — 改造
  - `stock-watcher/src/main/java/com/arthur/stock/controller/PageController.java` — 新增路由
  - `stock-watcher/src/main/resources/templates/fragments/common.html` — 侧边栏菜单
  - 新增：DataGovernanceController、DataGovernanceService、DataCheckable 接口、DataCheckResult/DataCheckItem/CheckLevel 模型、DataGovernanceMetricMapper、DataPullLogMapper、5 个核心表检测实现、3 个定时任务、前端页面 + JS

## ADDED Requirements

### Requirement: 数据管控中心总览

系统 SHALL 在数据管控中心页面顶部展示 3 张总览卡片：
1. 已接入数据表（X / 25，蓝色）
2. 今日已更新（X / Y，全成功绿色 / 有失败红色）
3. 异常表数（N 张，0 灰色 / >0 红色，仅统计 ERROR 级）

#### Scenario: 正常加载总览
- **WHEN** 用户访问 `/page/data-governance`
- **THEN** 页面顶部显示 3 张卡片，数字从 `/api/data-governance/overview` 获取
- **AND** 「今日已更新」卡片可点击，跳转到拉取日志 Tab 并筛选今日
- **AND** 「异常表数」卡片可点击，数据表列表自动筛选状态=异常

### Requirement: 数据表总览列表

系统 SHALL 以卡片列表形式展示所有 25 张数据表，每张卡片包含：状态灯 + 中文名 + 表名 + 数据量级 + 最新数据日期 + 更新频率 + 异常摘要（如有） + 操作按钮。

#### Scenario: 展示数据表列表
- **WHEN** 用户进入数据表总览 Tab
- **THEN** 展示全部 25 张表卡片，按分组排列
- **AND** 每张卡片显示状态灯（绿色=正常 / 黄色=延迟 / 红色=异常 / 蓝色旋转=更新中）

#### Scenario: 筛选与搜索
- **WHEN** 用户使用分组筛选（全部/基础/行情/财务/事件/指数）、状态筛选、或关键词搜索
- **THEN** 列表实时过滤，匹配的卡片显示，不匹配的隐藏

#### Scenario: 异常摘要展示
- **WHEN** 某张表有检测项不通过
- **THEN** 卡片底部显示前 2 个不通过项的 message 拼接（最多 200 字），红色小字

### Requirement: 数据表详情面板

系统 SHALL 在点击「查看详情」时从右侧滑出抽屉，包含 3 个 Tab：基本信息、检测结果、更新历史。

#### Scenario: 查看基本信息
- **WHEN** 用户点击某张表的「查看详情」
- **THEN** 右侧滑出抽屉，基本信息 Tab 展示：表名、中文名、对应 tushare 接口、数据量级、最新/最早数据日期、更新频率、字段列表

#### Scenario: 查看检测结果
- **WHEN** 用户切换到检测结果 Tab
- **THEN** 展示该表全部检测项（含通过和不通过），每项一行
- **AND** 每项左侧状态图标（✅ 通过 / ❌ ERROR / ⚠️ WARN），右侧显示名称 + 结果摘要
- **AND** 不通过的检测项可点击展开查看详情
- **AND** 底部显示汇总（共 X 项检测，Y 项通过，Z 项错误，W 项警告）

#### Scenario: 查看更新历史
- **WHEN** 用户切换到更新历史 Tab
- **THEN** 展示该表最近 30 天的更新记录（时间、状态、耗时、新增/更新条数、操作人）

### Requirement: 数据质量检测框架

系统 SHALL 提供 `DataCheckable` 接口，每张业务表的 Service 实现 `checkData()` 方法返回全部检测项结果（含通过和不通过）。`DataGovernanceService` 自动发现所有 `DataCheckable` Bean，按 `tableCode` 建索引调度。

#### Scenario: 空表兜底
- **WHEN** 检测某表时发现总记录数为 0
- **THEN** 框架层直接返回 ERROR（"表为空，0 条记录"），不调用业务 Service 的 checkData()

#### Scenario: 数据骤减检测
- **WHEN** 某表当前记录数较上次检测骤减超过 30%
- **AND** 该表最近一次拉取任务不是全量重建（MANUAL_FULL）
- **THEN** 自动追加一条 WARN 级检测项："数据量较上次检测骤降 {pct}%（{上次} -> {当前}），请排查"

#### Scenario: 定时全表检测
- **WHEN** 每天晚上 22:00
- **THEN** 自动执行 25 张表全表检测，生成一个检测批次（共享 check_batch_id）
- **AND** 结果写入 `data_governance_metric` 表

#### Scenario: 手动单表检测
- **WHEN** 管理员点击「单表重跑」
- **THEN** 立即执行该表检测并返回结果，同时写入 metric 表

#### Scenario: 手动全表检测
- **WHEN** 管理员点击「全表重跑」
- **THEN** 异步执行 25 张表检测，返回 batchId

### Requirement: 状态判定逻辑

系统 SHALL 按以下优先级判定表状态：UPDATING > ERROR > DELAYED > NORMAL。

#### Scenario: UPDATING 状态
- **WHEN** 某表当前有正在执行的拉取任务
- **THEN** 该表状态显示为 UPDATING（蓝色旋转），不存入 metric 历史表，列表返回时实时检查

#### Scenario: ERROR 状态
- **WHEN** 某表有任意 ERROR 级检测项不通过
- **THEN** 该表状态为 ERROR（红色）

#### Scenario: DELAYED 状态
- **WHEN** 某日频表（9 张）的最新数据日期 < 上一交易日
- **AND** 当前时间已过预期更新时间
- **AND** 当前为交易日
- **THEN** 该表状态为 DELAYED（黄色）

#### Scenario: NORMAL 状态
- **WHEN** 某表无 ERROR 级不通过项，且不延迟
- **THEN** 该表状态为 NORMAL（绿色），WARN 级不通过项在详情中展示但不影响状态

### Requirement: 增量更新

系统 SHALL 支持单表增量更新：从最新数据日期的下一天开始拉取到今天，使用 UPSERT 写入。

#### Scenario: 执行增量更新
- **WHEN** 管理员点击某表的「增量更新」
- **THEN** 异步启动增量拉取任务，返回 taskId
- **AND** 写入 data_pull_log（状态 RUNNING）
- **AND** 拉取完成后自动触发该表质量检测
- **AND** 更新 data_pull_log 状态为 SUCCESS/FAILED

#### Scenario: 并发控制
- **WHEN** 已有任务正在执行时，用户尝试启动新操作
- **THEN** 新操作按钮置灰，提示「有任务正在执行，请稍后再试」

#### Scenario: 进度展示
- **WHEN** 任务执行中
- **THEN** 前端每秒轮询 `/api/data-governance/tasks/{taskId}/progress`
- **AND** 展示进度百分比、当前步骤、已处理/总数、已用时间
- **AND** 任务完成后缓存保留 30 分钟自动清理

### Requirement: 全量重建

系统 SHALL 支持单表全量重建：清空表后从头拉取全部历史数据。

#### Scenario: 全量重建二次确认
- **WHEN** 管理员点击某表的「全量重建」
- **THEN** 弹出对话框，红色警告文字说明将清空的数据量、预计耗时、不可用期间
- **AND** 要求用户输入表名进行二次确认
- **AND** 确认按钮 10 秒倒计时后才可点击

#### Scenario: 执行全量重建
- **WHEN** 用户确认全量重建
- **THEN** 异步启动全量重建任务，返回 taskId
- **AND** 写入 data_pull_log（状态 RUNNING，operation_type=MANUAL_FULL）
- **AND** 重建完成后自动触发该表质量检测
- **AND** 更新 data_pull_log 状态

### Requirement: 拉取日志

系统 SHALL 提供独立的拉取日志 Tab，展示所有数据拉取任务历史记录。

#### Scenario: 查看日志列表
- **WHEN** 用户进入拉取日志 Tab
- **THEN** 按时间倒序展示日志列表，支持按表名/状态/时间范围/操作类型筛选
- **AND** 分页展示，每页 20 条

#### Scenario: 查看日志详情
- **WHEN** 用户点击某条日志记录
- **THEN** 右侧滑出详情：成功显示统计信息，失败显示错误信息和堆栈
- **AND** error_stack 字段仅管理员可见，普通用户只返回 error_message

### Requirement: 数据源状态

系统 SHALL 提供数据源 Tab，展示 tushare 连通性状态（只读）。

#### Scenario: 查看数据源状态
- **WHEN** 用户进入数据源 Tab
- **THEN** 展示：数据源名称、连接状态、响应耗时、测试接口、最后检测时间、检测频率
- **AND** 不展示 Token

#### Scenario: 手动测试连通性
- **WHEN** 管理员点击「重新测试」
- **THEN** 立即调用 tushare trade_cal 接口测试连通性，更新状态

#### Scenario: 自动连通性测试
- **WHEN** 每小时
- **THEN** 自动执行一次连通性测试，结果存内存缓存

### Requirement: 权限控制

系统 SHALL 对数据管控功能实行分级权限控制。

#### Scenario: 普通用户权限
- **WHEN** 普通用户访问数据管控中心
- **THEN** 可查看总览、列表、详情、日志、数据源状态
- **AND** 不可见 error_stack 详情
- **AND** 写操作按钮（增量更新、全量重建、手动检测、取消任务、测试连通性）不可见或置灰

#### Scenario: 管理员权限
- **WHEN** 管理员访问数据管控中心
- **THEN** 拥有全部权限，包括写操作和 error_stack 查看

### Requirement: 安全与脱敏

系统 SHALL 对敏感信息进行脱敏处理。

#### Scenario: Token 脱敏
- **WHEN** 任何接口返回或错误堆栈中包含 Tushare Token
- **THEN** Token 自动替换为 `***`

#### Scenario: 数据库连接信息过滤
- **WHEN** 全局异常处理器处理异常
- **THEN** 数据库连接信息不出现在错误堆栈中

### Requirement: 数据清理

系统 SHALL 定期清理过期数据。

#### Scenario: 自动清理
- **WHEN** 每天凌晨
- **THEN** 清理 data_governance_metric 和 data_pull_log 中 3 个月前的旧数据

## MODIFIED Requirements

### Requirement: InitStep 枚举增强

现有 InitStep 枚举仅包含 code/label/tableName 三个字段。需增加：
- `group`（TableGroup 枚举）：BASIC / MARKET / FINANCE / EVENT / INDEX
- `updateFrequency`（String）：更新频率描述，如"每个交易日 15:00-17:00"
- `expectedUpdateTime`（String）：预期更新时间，如"17:00"
- `isDaily`（boolean）：是否日频表（用于延迟判定）
- `tushareApi`（String）：对应 tushare 接口名（展示用）

### Requirement: DataInitService 改造

现有 DataInitService 需增强：
- 每次拉取完成后写入 data_pull_log
- 拉取完成后自动触发对应表的质量检测
- 支持单表增量更新和全量重建入口
- 进度回调更细粒度（按天/按批次）
- 任务状态持久化到 data_pull_log（刷新页面不丢失）
