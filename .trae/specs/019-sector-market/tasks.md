# Tasks

> **实施顺序**：后端数据层 -> 后端接口层 -> 前端骨架 -> 前端功能 -> 联调验证
> **依赖**：`index_daily` 表 + Service 已存在（数据采集任务已交付）；`sw_industry` / `sw_industry_member` Service 已存在

## 后端数据层

- [x] Task 1: 扩展 SwIndustryMemberMapper 新增成分股查询方法
  - 新增 `selectAllCurrentL1Members()` 方法：查询全量 `is_new='1'` 且 `index_code` 属于 level=1 的成分股记录（返回 `List<SwIndustryMemberDO>`，含 `ts_code` / `index_code` / `index_name`）
  - 新增 `selectMembersByIndexCode(String indexCode)` 方法：按行业代码查询当前成分股（`is_new='1'`），返回 `List<SwIndustryMemberDO>`
  - 参考现有 `selectLatestL1ByTsCodes` 的 `@Select` 注解风格

- [x] Task 2: 扩展 DailyQuoteMapper 新增成分股行情 JOIN 查询
  - 新增 `selectMembersWithQuote(indexCode, tradeDate, keyword, size, offset)` 方法：JOIN `sw_industry_member` + `stock_basic` + `daily_quote`，返回 `List<IndustryMemberVO>`（含 ts_code/name/close/pct_chg/vol/amount/market）
  - 新增 `countMembersWithQuote(indexCode, tradeDate, keyword)` 方法：同 FROM/WHERE 的 COUNT 查询
  - keyword 支持 `stock_basic.name` LIKE 或 `ts_code` LIKE 模糊匹配
  - 参考现有 `selectStockList` / `selectStockListCount` 的 JOIN + 分页模式，SQL 放 XML 映射文件

## 后端接口层

- [x] Task 3: 新建 IndustryRankingVO 和 IndustryMemberVO
  - `vo/IndustryRankingVO.java`：字段 `industryCode` / `industryName` / `indexCode` / `pctChg` / `amount` / `topGainerCode` / `topGainerName` / `topGainerPctChg` / `topLoserCode` / `topLoserName` / `topLoserPctChg`
  - `vo/IndustryMemberVO.java`：字段 `tsCode` / `name` / `close` / `pctChg` / `vol` / `amount` / `market`
  - 使用 `@Data @Builder @NoArgsConstructor @AllArgsConstructor`（与现有 VO 风格一致）

- [x] Task 4: 扩展 SwIndustryService 新增排行和成分股查询方法
  - 新增 `getIndustryRanking(String tradeDate)` 方法签名：返回 `List<IndustryRankingVO>`
  - 新增 `getIndustryMembers(String industryCode, String tradeDate, int page, int size, String keyword)` 方法签名：返回 `PageResult<IndustryMemberVO>`
  - tradeDate 为 null 时默认取 `daily_quote` 最新交易日

- [x] Task 5: 实现 SwIndustryServiceImpl 的 getIndustryRanking 方法
  - 步骤 1：`listByLevel(1)` 取 28 个一级行业（含 `index_code`）
  - 步骤 2：`SwIndustryMemberMapper.selectAllCurrentL1Members()` 取全量当前一级成分股
  - 步骤 3：tradeDate 为 null 时 `DailyQuoteMapper.selectLatestTradeDate()` 取最新交易日
  - 步骤 4：`IndexDailyService.getByCodesAndTradeDate(28个行业指数代码, tradeDate)` 批量取行业指数行情
  - 步骤 5：`DailyQuoteMapper.selectByCodesAndTradeDate(全部成分股ts_code列表, tradeDate)` 批量取成分股行情
  - 步骤 6：Java 层按 `index_code` 分组成分股，每组按 `pct_chg` 排序取 top1（领涨）/ bottom1（领跌），拼装 `IndustryRankingVO`
  - 注入 `SwIndustryMemberMapper` / `DailyQuoteMapper` / `IndexDailyService` / `StockBasicMapper`

- [x] Task 6: 实现 SwIndustryServiceImpl 的 getIndustryMembers 方法
  - 调用 `DailyQuoteMapper.selectMembersWithQuote` 和 `countMembersWithQuote`
  - tradeDate 为 null 时默认取最新交易日
  - 返回 `PageResult.of(list, total, page, size)`

- [x] Task 7: 扩展 SwIndustryController 新增 ranking 和 members 端点
  - `GET /api/industry/ranking`：参数 `tradeDate`（可选），返回 `ApiResponse<List<IndustryRankingVO>>`
  - `GET /api/industry/members`：参数 `industryCode`（必填）/ `page`（默认 1）/ `size`（默认 20）/ `keyword`（可选），返回 `ApiResponse<PageResult<IndustryMemberVO>>`
  - 参数数量 ≤ 5 个，可用 `@RequestParam`（符合 API 设计规范）

- [x] Task 8: 扩展 IndexDailyController 新增指数日线查询端点
  - `GET /api/index-daily`：参数 `tsCode`（必填）/ `startDate`（可选）/ `endDate`（可选）/ `limit`（可选，默认 250），返回 `ApiResponse<List<IndexDailyDO>>`
  - 逻辑：按 limit 取近 N 日；返回结果按 `trade_date` 升序排列
  - 复用 `IndexDailyService.getByCodeOrderByTradeDate(tsCode, limit)`，在 Controller 层反转排序

## 前端骨架

- [x] Task 9: 修改 common.html 侧边栏新增板块行情菜单项
  - 在「主要功能」分区「自选股」之后新增菜单项，图标 `bi-grid-3x3-gap`
  - 移除「K 线分析」占位项和「策略回测」占位项（`href="#"` 死链）

- [x] Task 10: 修改 PageController 新增 /page/sector 路由
  - 新增 `@GetMapping("/page/sector")` 方法，返回 `pages/sector` 视图
  - 设置 `pageTitle="板块行情"` / `activeMenu="sector"`

- [x] Task 11: 新建 sector.html 页面模板
  - 套用 `fragments/common.html` 六个 fragment
  - 页面结构：page-header + 顶部热度图卡片 + 主体区（排行表 + K 线图 + 成分股）
  - 底部引入 `sector.js` 和 `sector.css`

- [x] Task 12: 新建 sector.css 页面样式
  - 热度图区：CSS Grid 7×4 布局
  - 排行表区/成分股展开区/K 线图容器样式
  - 所有颜色用 CSS 变量，三主题兼容，响应式适配

## 前端功能

- [x] Task 13: 实现 sector.js - API 调用与数据加载
  - 页面初始化：调用 `GET /api/industry/ranking` 获取 28 行业排行数据
  - 热度图 + 排行表共享同一份排行数据（不额外发请求）
  - 默认选中第一行，触发 K 线图加载

- [x] Task 14: 实现 sector.js - 板块轮动热度图渲染
  - 原生 HTML grid（7×4），28 个 `<div>` 格子
  - 每格内容：行业名（顶部）+ 涨跌幅%（中间大字）+ 成交额（底部小字）
  - 颜色映射：基于涨跌幅阈值（>3% 深红 / 1~3% 中红 / 0~1% 浅红 / 0% 灰色 / 反向同理绿色），从 CSS 变量读取颜色
  - 悬浮 tooltip：显示行业详情（行业名/涨跌幅/成交额/领涨股/领跌股）
  - 点击联动：触发排行表对应行高亮 + 成分股展开 + K 线图加载

- [x] Task 15: 实现 sector.js - 行业排行表渲染
  - 渲染 28 行表格：行业名 / 今日涨跌幅 / 成交额 / 领涨股（名称+代码+涨幅）/ 领跌股（名称+代码+跌幅）
  - 默认按涨跌幅降序
  - 表头排序：点击「今日涨跌幅」「成交额」切换升降序（客户端排序，不重新请求）
  - 涨跌幅红涨绿跌（`.stock-up` / `.stock-down`）
  - 点击行：高亮该行 + 展开成分股 + 加载 K 线图

- [x] Task 16: 实现 sector.js - 成分股展开列表
  - 点击行业行时调用 `GET /api/industry/members?industryCode={code}&page=1&size=20`
  - 渲染成分股表格：股票代码（链接）/ 名称 / 最新价 / 涨跌幅 / 成交量 / 成交额 / 市场
  - 分页器：支持切换页码 + 每页条数（20/50/100），显示「共 X 条 · 第 Y/Z 页」
  - 搜索框：debounce 300ms，按代码或名称模糊搜索
  - 股票代码点击跳转个股诊断

- [x] Task 17: 实现 sector.js - 行业板块 K 线图
  - 复制 `dashboard.js` 的 `renderKline` 核心逻辑，适配行业指数数据
  - LightweightCharts 蜡烛图 + MA5/10/20/60 主图叠加 + 成交量副图
  - 十字光标 + OHLCV 浮窗
  - MA 显隐切换按钮组（MA5/MA10/MA20/MA60，可多选）
  - 时间范围切换：近 60/120/250 日/全部
  - 主题切换兼容（`ChartsTheme.register(instance, 'lightweight')`）
  - 缺数据时显示「暂无数据」占位

## 联调验证

- [x] Task 18: 更新 dashboard.js 适配 /api/industry/ranking 返回格式
  - `fetchSectorOverview`：移除 `?limit=5` 参数，调用 `GET /api/industry/ranking` 获取全部 28 行业
  - `renderSectorOverview`：接收 flat list，按 `pctChg` 降序排序，截取前 5 为涨幅榜、后 5 为跌幅榜
  - 兼容字段名：`industryName` / `pctChg`（与 `IndustryRankingVO` 字段对齐）

- [x] Task 19: 端到端验证
  - 编译通过：`node stock-watcher/run.js compile-dev`（BUILD SUCCESS）
  - API 端点验证（4 个端点）
  - 页面功能验证（热度图 / 排行表 / 成分股 / K 线图 / 主题切换）
  - 仪表盘板块概览卡片验证

# Task Dependencies

- Task 1, 2 可并行（不同 Mapper）
- Task 3 可并行（无依赖）
- Task 4 依赖 Task 1, 2, 3（方法签名需用到 VO 和 Mapper 方法）
- Task 5, 6 依赖 Task 4（实现 Service 方法）
- Task 7, 8 依赖 Task 5, 6（Controller 调用 Service）
- Task 9, 10, 11, 12 可并行（前端骨架，不依赖后端接口完成，但联调需接口就绪）
- Task 13 依赖 Task 11（JS 文件需存在）+ Task 7（API 端点就绪才能联调）
- Task 14, 15, 16, 17 依赖 Task 13（共用数据加载层）
- Task 18 依赖 Task 7（`/api/industry/ranking` 端点就绪）
- Task 19 依赖全部完成
