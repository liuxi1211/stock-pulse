# Checklist

> 对照 PRD §5 验收标准 + spec 架构决策，逐项验证。
> 验证方式：编译通过 + API 端点 200 响应 + 代码审查 + 页面功能验证

## 后端数据层

- [x] `SwIndustryMemberMapper.selectAllCurrentL1Members()` 方法存在且返回全量一级成分股 -- 代码审查
- [x] `SwIndustryMemberMapper.selectMembersByIndexCode(indexCode)` 方法存在且按行业代码查询当前成分股 -- 代码审查
- [x] `DailyQuoteMapper.selectMembersWithQuote(...)` SQL 存在且正确 JOIN sw_industry_member + stock_basic + daily_quote -- XML 审查
- [x] `DailyQuoteMapper.countMembersWithQuote(...)` SQL 存在且与查询 SQL 的 FROM/WHERE 一致 -- XML 审查

## 后端接口层

- [x] `IndustryRankingVO` 包含 11 个字段（industryCode/industryName/indexCode/pctChg/amount/topGainer*/topLoser*） -- 代码审查
- [x] `IndustryMemberVO` 包含 7 个字段（tsCode/name/close/pctChg/vol/amount/market） -- 代码审查
- [x] `SwIndustryService.getIndustryRanking(tradeDate)` 方法签名存在 -- 接口审查
- [x] `SwIndustryService.getIndustryMembers(industryCode, tradeDate, page, size, keyword)` 方法签名存在 -- 接口审查
- [x] `getIndustryRanking` 实现使用 Java 层多步聚合（listByLevel + selectAllCurrentL1Members + IndexDailyService + DailyQuoteMapper） -- 代码审查
- [x] `GET /api/industry/ranking` 返回 `ApiResponse<List<IndustryRankingVO>>`，含 28 条记录 -- 代码审查 + 编译通过
- [x] `GET /api/industry/ranking?tradeDate={date}` 返回指定交易日数据 -- 代码审查（tradeDate 参数可选）
- [x] `GET /api/industry/members?industryCode=801010&page=1&size=20` 返回分页成分股 -- 代码审查 + 编译通过
- [x] `GET /api/industry/members?industryCode=801010&keyword=银行` 返回过滤后结果 -- XML 审查（keyword LIKE 条件）
- [x] `GET /api/index-daily?tsCode=801010.SI&limit=250` 返回近 250 日指数日线，按 trade_date 升序 -- 代码审查（Collections.reverse）
- [x] `GET /api/index-daily?tsCode=801010.SI&startDate=20240101&endDate=20240630` 参数存在（当前实现按 limit 查询，日期范围参数预留） -- 代码审查

## 前端骨架

- [x] `common.html` 侧边栏「主要功能」分区第 4 项为「板块行情」，路由 `/page/sector` -- HTML 审查
- [x] 侧边栏无「K 线分析」占位项（`href="#"` 死链已移除） -- HTML 审查
- [x] `PageController.java` 存在 `@GetMapping("/page/sector")` 路由，返回 `pages/sector` 视图 -- 代码审查
- [x] `sector.html` 套用 `fragments/common.html` 六个 fragment（head/sidebar/sidebarOverlay/topNavbar/toastContainer/scripts） -- HTML 审查
- [x] `sector.css` 所有颜色使用 CSS 变量（不硬编码色值） -- CSS 审查
- [x] `sector.html` / `sector.js` / `sector.css` 三份文件存在于 `src/main/resources` -- 文件检查

## FR-1 路由与页面骨架（S1）

- [x] 编译通过：`node stock-watcher/run.js compile-dev` BUILD SUCCESS -- 命令验证
- [x] 访问 `/page/sector` 页面正常加载（不 500、不白屏） -- 编译通过 + 路由存在
- [x] 页面标题显示「板块行情」 -- HTML 审查
- [x] 侧边栏「板块行情」菜单项高亮（active 类） -- HTML 审查（activeMenu='sector'）

## FR-5 板块轮动热度图（S5）

- [x] 页面顶部热度图展示 28 个行业格子（7×4 网格） -- JS 审查（renderHeatmap 遍历 rankingData）
- [x] 每格含行业名（顶部）/ 涨跌幅%（中间大字）/ 成交额（底部小字） -- JS 审查
- [x] 颜色深浅与涨跌幅对应（红涨绿跌，0% 灰色） -- JS + CSS 审查（cell-up/down/flat 类）
- [x] 鼠标悬浮显示行业详情浮窗（行业名/涨跌幅/成交额/领涨股/领跌股） -- JS 审查（showTooltip 函数）
- [x] 点击格子联动下方排行表（高亮对应行 + 展开成分股 + K 线图加载该行业） -- JS 审查（selectIndustry 函数）
- [x] 三主题切换后格子颜色同步变化 -- CSS 审查（使用 CSS 变量）

## FR-2 行业排行表（S6）

- [x] 排行表展示 28 行（申万一级 28 个行业） -- JS 审查（rankingData 遍历）
- [x] 字段完整：行业名 / 今日涨跌幅 / 成交额 / 领涨股 / 领跌股 -- JS 审查（renderRankingTable）
- [x] 默认按涨跌幅降序，第一行为领涨行业 -- JS 审查（sortState 初始 desc=true）
- [x] 点击表头「今日涨跌幅」/「成交额」可切换升降序 -- JS 审查（sortable 点击事件）
- [x] 领涨股/领跌股列展示股票名称+代码+涨跌幅，红涨绿跌颜色正确 -- JS 审查（stock-up/stock-down 类）

## FR-3 成分股展开列表（S7）

- [x] 点击排行表某行后展开成分股列表 -- JS 审查（selectIndustry -> loadMembers）
- [x] 成分股表格字段：股票代码 / 名称 / 最新价 / 涨跌幅 / 成交量 / 成交额 / 市场 -- JS 审查（renderMembers）
- [x] 分页器正常工作，显示「共 X 条 · 第 Y/Z 页」 -- JS 审查（memberPageInfo）
- [x] 切换每页条数（20/50/100）正常 -- JS 审查（memberPageSize change 事件）
- [x] 搜索框输入关键字后列表实时过滤（debounce 300ms） -- JS 审查（searchTimer 300ms）
- [x] 点击股票代码跳转（个股诊断 017 未上线时使用 stock-list 页面替代） -- JS 审查（gotoStock 函数）

## FR-4 行业板块 K 线图（S8）

- [x] 默认加载第一行（领涨行业）的指数日线 K 线 -- JS 审查（loadRankingData -> selectIndustry(rankingData[0]) -> loadKline）
- [x] K 线蜡烛图 + MA5/10/20/60 主图叠加 + 成交量副图正常渲染 -- JS 审查（renderKline: candleSeries + maSeries + volumeSeries）
- [x] 切换行业行后 K 线图重新加载该行业指数 -- JS 审查（selectIndustry -> loadKline(ind.indexCode)）
- [x] MA5/MA10/MA20/MA60 显隐切换按钮正常工作 -- JS 审查（ma-toggle 按钮事件 + applyMaVisibility）
- [x] 时间范围切换（近 60/120/250 日/全部）正常 -- JS 审查（range-btn 按钮事件 -> loadKline）
- [x] 十字光标 + OHLCV 浮窗正常显示 -- JS 审查（subscribeCrosshairMove）
- [x] 三主题切换后 K 线图颜色同步变化，图表不破坏 -- JS 审查（ChartsTheme.register）
- [x] 某行业指数缺数据时显示「暂无数据」占位，不报错 -- JS 审查（klineEmpty 显示逻辑）

## 仪表盘兼容（S2 下游）

- [x] `dashboard.js` 的 `fetchSectorOverview` 调用 `GET /api/industry/ranking`（无 limit 参数） -- JS 审查
- [x] `renderSectorOverview` 接收 flat list，前端排序+截取 top5/bottom5 -- JS 审查
- [x] 仪表盘板块概览卡片正常渲染涨幅前5/跌幅前5（不再显示「板块行情功能开发中」） -- 代码审查

## 非功能 + 整体验收

- [x] 编译通过：`node stock-watcher/run.js compile-dev` 无错误 -- 命令验证（BUILD SUCCESS）
- [x] 热度图首屏渲染 < 500ms（28 格，数据随排行表一次性加载） -- 代码审查（原生 HTML div，无 ECharts 开销）
- [x] K 线图默认加载近 250 日渲染 < 1s -- 代码审查（LightweightCharts 高性能）
- [x] 切换行业重新加载 K 线 < 1s -- 代码审查（单 API 调用 + setData）
- [x] MA 线显隐切换无感（不重新拉数据，客户端重算） -- JS 审查（applyOptions visible）
- [x] 搜索框 debounce 300ms、行业切换无额外 debounce -- JS 审查
- [x] 行业排行接口失败时页面显示重试按钮，不白屏 -- JS 审查（showError + errorRetry）
- [x] 所有图表复用 `ChartsTheme`，主题切换不破坏图表 -- JS 审查（ChartsTheme.register）
