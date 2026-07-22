# Checklist

## 数据采集层
- [x] 7 张表 DDL 已在 `schema-sqlite.sql` 和 `schema-mysql.sql` 中定义（复合主键 + 索引）
- [x] `TushareApiEnum` 新增 7 个枚举项，apiName 和 fields 与 Tushare 官方文档一致
- [x] 7 个 DTO 类使用 `@JSONField` 正确映射 snake_case 字段
- [x] 7 个 DO 类使用 `@TableName` 正确映射表名，字段类型用 BigDecimal/String
- [x] 7 个 Mapper 接口继承 BaseMapper，含 insertBatch + deleteBatchByKeys 自定义方法
- [x] 7 个 Mapper XML 含 resultMap + insertBatch foreach + deleteBatchByKeys foreach
- [x] 7 个 ServiceImpl 实现 fetchAndSave（分批先删后插）+ 查询方法
- [x] `TushareClient` 新增 7 个方法，正确构建 params 并调用 query 通用方法
- [x] 定时任务每个表独立 try-catch，一个失败不影响其他
- [x] 手动初始化接口可触发指定日期补拉（`POST /api/moneyflow-data/fetch?tradeDate=`）

## 查询 Controller 层
- [x] 5 个 Controller 使用 `@RequestMapping("/api/xxx")` 前缀，返回 `ApiResponse<T>`
- [x] tradeDate 参数为空时回退到最近交易日
- [x] 接口做参数校验（日期格式 / limit 上限 / sortBy 白名单）
- [x] API 参数 >5 个时封装为 DTO 对象
- [x] 接口请求/返回体不用 Map，用显式 VO/DTO 类型
- [x] MoneyflowController 含 GET /top + GET /detail
- [x] HkHoldController 含 GET /ratio-trend + GET /top-holdings + GET /detail，不含净买入接口
- [x] TopListController 含 GET / + GET /inst + GET /inst/notable
- [x] BlockTradeController 含 GET /（分页）+ GET /premium-distribution
- [x] MarginController 含 GET /trend + GET /detail/top

## 前端页面层
- [x] `PageController` 新增 `GET /page/moneyflow` 路由（pageTitle + activeMenu 设置正确）
- [x] 侧边栏"主要功能"末尾新增"资金流向"菜单项，位于板块行情之后
- [x] `moneyflow.html` 套用 fragments 骨架，5 Tab 导航（不含板块资金 Tab）
- [x] Tab 懒加载：首次切换某 Tab 才发请求，缓存已加载 Tab
- [x] Tab A：TOP 10 柱状图（红涨绿跌，点击跳转）+ TOP 50 表格（表头排序，金额格式化）
- [x] Tab B：持股比例 3 条折线图 + 十大重仓股表格 + 沪/深/合计子视图切换 + 顶部"不含净买入"说明
- [x] Tab C：龙虎榜列表 + 展开席位子表（买入/卖出 TOP 5）+ 知名游资卡片
- [x] Tab D：溢价率分布柱状图（8 档分桶）+ 大宗交易明细表（分页，溢价率涨跌色）
- [x] Tab E：融资/融券双轴折线图 + 沪/深/合计切换 + TOP 50 个股明细表
- [x] 日期选择器变更时重新加载当前激活 Tab
- [x] 所有 ECharts 实例用 ChartsTheme，主题切换（azure/mist/cyber）后颜色同步
- [x] 窗口 resize 时图表自适应
- [x] 非交易日显示"今日休市"提示
- [x] 无数据显示"暂无数据"提示
- [x] 接口异常显示"加载失败，请重试"，不影响其他 Tab
- [x] 点击股票代码/诊断按钮跳转 `/page/stock-detail/{ts_code}?tab=moneyflow`
- [x] 涨跌色全局统一（红涨绿跌）
- [x] 金额用 StockApp.formatNumber 格式化（万/亿自动切换）

## 编译与集成
- [x] `node stock-watcher/run.js compile-dev` 编译通过
- [x] 访问 `/page/moneyflow` 返回 200，页面标题为"资金流向"
- [x] 侧边栏第 5 项"资金流向"高亮正确
- [x] 5 Tab 完整展示，默认激活 Tab A
