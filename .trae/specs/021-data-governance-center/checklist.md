# Checklist

## 数据模型与基础设施
- [x] `data_governance_metric` 表已在 schema-mysql.sql 和 schema-sqlite.sql 中定义，字段与 PRD §5.2 一致
- [x] `data_pull_log` 表已在 schema-mysql.sql 和 schema-sqlite.sql 中定义，字段与 PRD §5.3 一致
- [x] DataGovernanceMetricMapper 含 insertBatch、selectLatestBatch、selectByTableCodeLatest、selectPreviousByTableCode、deleteOlderThan 方法
- [x] DataPullLogMapper 含 insert、updateStatus、selectByTableCode、selectPageList、selectById、deleteOlderThan 方法
- [x] InitStep 枚举已增强：新增 group/updateFrequency/expectedUpdateTime/isDaily/tushareApi 字段
- [x] InitStep 补充至 25 张表条目（原 15 + 新增 10：daily_basic/fina_indicator/stock_moneyflow/top_list/top_inst/block_trade/hk_hold/margin/margin_detail/index_daily）
- [x] TableGroup 枚举已创建（BASIC/MARKET/FINANCE/EVENT/INDEX）

## 检测框架
- [x] DataCheckable 接口已定义（checkData() + getTableCode()）
- [x] CheckLevel 枚举已定义（ERROR/WARN）
- [x] DataCheckItem 模型类已定义（name/displayName/passed/level/message）
- [x] DataCheckResult 模型类已定义（tableCode/tableName/totalRows/latestDate/items）
- [x] DataGovernanceService 自动发现所有 DataCheckable Bean 并建 checkableMap
- [x] 空表兜底：表 count=0 时直接返回 ERROR，不调用 checkData()
- [x] 数据骤减检测：当前记录数 vs 上次记录数骤减 >30% 时自动追加 WARN
- [x] 数据骤减检测豁免：最近拉取为 MANUAL_FULL 时跳过
- [x] checkAll() 生成共享 check_batch_id
- [x] 状态推导逻辑：UPDATING > ERROR > DELAYED > NORMAL
- [x] 延迟判定：仅日频表（9 张）判定，非交易日不判定
- [x] TaskProgressCache 基于 Caffeine，TTL=30min，含 isCancelled 标记
- [x] 全局并发锁：同一时间只允许一个数据任务

## 5 张核心表检测
- [x] daily_quote：5 个检测项（新鲜度/数据量异常/价格逻辑/收盘价超涨跌停/覆盖度），latestDate=max(trade_date)
- [x] stock_basic：3 个检测项（行情缺失/上市数差异/关键字段为空），latestDate=null
- [x] trade_cal：4 个检测项（未来30天/交易所完整/沪深一致/周末交易日），latestDate=null
- [x] income：4 个检测项（公告新鲜度/营收为空/净利润超营收/上市超1年无数据），latestDate=max(ann_date)
- [x] index_daily：3 个检测项（新鲜度/核心指数缺失/价格异常），latestDate=max(trade_date)

## 数据操作
- [x] DataInitService.incrementalUpdate(tableCode, operator) 方法已实现
- [x] DataInitService.fullRebuild(tableCode, operator) 方法已实现
- [x] 拉取过程中更新 TaskProgressCache（进度百分比/当前步骤/已处理/总数）
- [x] 协作式取消：每批检查 isCancelled 标记，标记为 true 则退出
- [x] 拉取完成后自动触发质量检测（调用 checkTable）
- [x] 拉取完成后写入 data_pull_log（状态/耗时/条数）
- [ ] 现有定时任务改造：执行完成后写 data_pull_log（基础设施已就绪，现有任务尚未改造）

## 后端 API
- [x] GET /api/data-governance/overview 返回总览数据（totalTables/updatedToday/errorTables/lastCheckTime）
- [x] GET /api/data-governance/tables 支持 group/status/keyword 筛选参数（封装为 QueryDTO）
- [x] GET /api/data-governance/tables/{tableCode} 返回单表基本信息
- [x] GET /api/data-governance/tables/{tableCode}/check-result 返回检测结果详情
- [x] GET /api/data-governance/tables/{tableCode}/pull-history 返回单表拉取历史
- [x] POST /api/data-governance/tables/{tableCode}/incremental-update 返回 taskId（@RequireAdmin）
- [x] POST /api/data-governance/tables/{tableCode}/full-rebuild 返回 taskId（@RequireAdmin）
- [x] POST /api/data-governance/check/all 返回 batchId（@RequireAdmin）
- [x] POST /api/data-governance/check/{tableCode} 返回检测结果（@RequireAdmin）
- [x] GET /api/data-governance/tasks/{taskId}/progress 返回进度（从内存缓存读取）
- [x] POST /api/data-governance/tasks/{taskId}/cancel 协作式取消（@RequireAdmin）
- [x] GET /api/data-governance/logs 分页 + 筛选（封装 LogQueryDTO）
- [x] GET /api/data-governance/logs/{logId} 单条日志详情（error_stack 仅管理员可见）
- [x] GET /api/data-governance/datasource 只读连通性状态（不返回 Token）
- [x] POST /api/data-governance/datasource/test 手动测试（@RequireAdmin）
- [x] 所有 ResponseDTO/VO 类已定义，禁止用 Map 返回
- [x] 接口参数 >5 个时已封装为 DTO 对象

## 定时任务
- [x] DataGovernanceCheckJob：每天 22:00 执行全表检测
- [x] DataSourceHealthJob：每小时执行连通性测试
- [x] MetricCleanupJob：每天凌晨清理 3 个月前旧数据

## 前端页面
- [x] PageController 新增 /page/data-governance 路由
- [x] 侧边栏「管理中心」分组下新增「数据管控中心」菜单项
- [x] 页面含 3 张总览卡片（已接入/今日已更新/异常表数）
- [x] 数据表总览 Tab：卡片列表 + 状态灯 + 分组/状态/搜索筛选
- [x] 卡片含：状态灯 + 中文名 + 表名 + 数据量 + 最新日期 + 更新频率 + 异常摘要 + 操作按钮
- [x] 详情抽屉（Bootstrap Offcanvas）：基本信息/检测结果/更新历史 3 Tab
- [x] 检测结果全部展示（含通过项），不通过项可展开
- [x] 全量重建二次确认弹窗：红色警告 + 输入表名 + 10 秒倒计时
- [x] 进度条：每秒轮询，展示百分比/步骤/已处理/总数/已用时间
- [x] 拉取日志 Tab：列表 + 分页 + 筛选 + 详情滑出
- [x] 数据源 Tab：连通性状态展示 + 管理员可见「重新测试」按钮
- [x] 按管理员角色控制写操作按钮可见性
- [x] 页面遵循现有模板骨架（Thymeleaf + Bootstrap 5 + 共用 fragment）

## 安全与脱敏
- [x] Tushare Token 仅从 application.yml 读取，任何接口都不返回
- [x] 错误堆栈中 Token/密码/数据库连接串自动替换为 ***（SensitiveDataUtil）
- [x] error_stack 普通用户不可见，管理员可见（已脱敏）
- [x] 所有写操作接口有 @RequireAdmin 注解（6 个写操作已标注）
- [x] 全量重建二次确认机制有效（输入正确表名 + 倒计时结束）
- [x] 所有写操作记录操作人到 data_pull_log

## 性能
- [x] 页面首次加载时间 < 2 秒（25 行最新批次，索引优化）
- [x] 数据表列表查询 < 200ms（取最新批次，25 行）
- [x] 单表检测执行时间 < 5 秒（核心表，最近 30 天窗口查询）
- [x] 全表 25 张检测总耗时 < 2 分钟（5 核心表有检测，其余 20 表无 DataCheckable 实现会跳过）
- [x] 增量更新写入速度 ≥ 3000 条/秒（复用现有 saveBatch 500 条/批模式）
- [x] 并发控制有效：同一时间只允许一个数据任务（TaskProgressCache 全局锁）
