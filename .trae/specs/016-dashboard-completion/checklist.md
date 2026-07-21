# Checklist

> **change-id**：`016-dashboard-completion`
> **约定**：每条验收必须可验证。模糊词已替换为可断言口径。验收方法对照 PRD §5 验收标准。
> **验证状态**：代码级验证全部通过（22 项文件/代码检查 + 14 项 grep 检查）。标注 `<!-- 运行时验证 -->` 的项需启动服务后验证。

---

## index_daily 数据底座（D1/D5 前置）

- [x] `index_daily` 表在 `schema-sqlite.sql` 和 `schema-mysql.sql` 中均有 DDL 定义
- [x] 表字段完整：ts_code / trade_date / close / open / high / low / pre_close / change / pct_chg / vol / amount
- [x] 主键为 `(ts_code, trade_date)` 联合主键，`trade_date` 有索引
- [x] `IndexDailyDO` / `IndexDailyMapper` / `IndexDailyMapper.xml` / `IndexDailyService` / `IndexDailyServiceImpl` 全套文件创建完成
- [x] `TushareApiEnum` 注册了 `INDEX_DAILY` 枚举值
- [x] `TushareClient` 有 `fetchIndexDaily(tsCode, startDate, endDate)` 方法
- [x] `IndexDailyFetchService` 定时任务注册完成，`@Scheduled(cron="0 30 16 * * MON-FRI")` 每交易日 16:30 执行
- [x] 定时任务拉取 4 大盘指数（`000001.SH` / `399001.SZ` / `399006.SZ` / `000688.SH`）+ 申万一级行业指数（sw_industry level=1 动态读取）
- [ ] 历史数据初始化完成（近 1 年），SELECT count(*) > 0 且抽样对比 Tushare 原始数据一致 <!-- 运行时验证：需启动服务后手动触发 IndexDailyFetchService 拉取历史数据 -->
- [x] `target/classes/mapper/IndexDailyMapper.xml` 旧构建残留已被新构建覆盖（mvn compile 后生效）
- [x] 数据缺失时查询方法返回空列表，不抛异常（`getLatestByCodes` 先查 MAX(trade_date)，空时返回空列表）

---

## FR-1 大盘指数真实数据（D1）

- [x] `MarketServiceImpl.java:31-44` 硬编码 mock 数据（3368.07 / 10856.23 / 2156.89 / 968.45）已删除
- [x] `getMarketIndices()` 改为调用 `IndexDailyService.getLatestByCodes(["000001.SH","399001.SZ","399006.SZ","000688.SH"])`
- [x] `@Cacheable("indices")` 缓存 key 改为 `#root.target.getLatestTradeDate()`（按 index_daily 最新交易日失效）
- [x] `IndexDailyController` 暴露 `GET /api/index-daily/latest?codes=...` 端点，返回 `ApiResponse<List<IndexDailyDO>>`
- [ ] 4 张指数卡片显示真实点位，对比同花顺/东方财富实时数据一致（误差 ≤ 0.01%） <!-- 运行时验证：需 index_daily 有数据后对比 -->
- [x] 每张卡片显示：指数名 + 最新点位（2 位小数）+ 涨跌额 + 涨跌幅 + 数据日期（YYYY-MM-DD）
- [x] 涨跌色用 `.stock-up` / `.stock-down`（components.css 定义，用 CSS 变量 `--rise-light` / `--fall-light`）
- [x] 模拟 `index_daily` 表无数据时，卡片显示「暂无数据」占位（`th:if="${#lists.isEmpty(indices)}"` 展示 4 个占位卡片）
- [ ] 周末访问仪表盘时，指数卡片显示最近交易日（周五）数据 + 数据日期字段 <!-- 运行时验证：需周末启动服务验证 -->
- [x] 全页面搜索 `3368.07` 无结果（MarketServiceImpl.java 中无匹配）

---

## FR-2 市场温度卡片（D2）

- [x] `MarketTemperatureVO` 创建完成，含 tradeDate / upCount / downCount / flatCount / limitUpCount / limitDownCount 字段
- [x] `DailyQuoteMapper` 有 5 个聚合统计方法（countUpByTradeDate / countDownByTradeDate / countFlatByTradeDate / countLimitUpByTradeDate / countLimitDownByTradeDate）
- [x] 涨停/跌停统计 JOIN `stock_basic` 表按板块区分规则：主板/北交所 9.9%、创业板/科创板 19.9%、ST 4.9%
- [x] `MarketService.getMarketTemperature(tradeDate)` 方法实现完成（tradeDate 为 null 时取 daily_quote 最新交易日）
- [x] `MarketController` 有 `GET /market/temperature?tradeDate=<今日>` 端点
- [x] 市场温度卡片在 Row 1 与 Row 2 之间显示，横向 5 格布局
- [ ] 5 个数字显示正确：涨家数 + 跌家数 + 平家数 = 当日有交易的全部 A 股股票数 <!-- 运行时验证：需有 daily_quote 数据后校验 -->
- [ ] 对比同花顺「涨跌家数」「涨停家数/跌停家数」数据一致 <!-- 运行时验证 -->
- [x] 涨跌色：涨家数红色、跌家数绿色、平家数灰色、涨停数红色加粗、跌停数绿色加粗（dashboard.html + dashboard.js 实现）
- [x] 模拟 `daily_quote` 表无当日数据时，卡片显示 `--` 占位（`showTemperatureEmpty()`）

---

## FR-3 换手率榜 TOP 10（D3）

- [x] `DailyQuoteMapper` 有 `selectTopTurnover(tradeDate, limit)` 方法，JOIN `daily_quote` + `daily_basic` + `stock_basic`
- [x] `MarketRankingVO` 加了 `topTurnover` 字段（`List<StockRankVO>`）
- [x] `MarketServiceImpl.getMarketRanking()` 调用 `selectTopTurnover` 组装到 VO
- [x] 既有 3 榜数据不变（向后兼容，StockRankVO 新增 turnoverRate 字段不影响既有序列化）
- [x] 前端 Row 3 从 3 张并排卡片重构为 4 Tab 切换布局（`#rankingTabs` + `#rankingBody`）
- [x] 4 个 Tab：涨幅榜 / 跌幅榜 / 成交额榜 / 换手率榜，默认选中涨幅榜
- [x] 切到换手率榜 Tab 显示 TOP 10 个股，按换手率（turnover_rate）降序排列
- [x] 每行显示：排名 / 股票代码 / 股票名称 / 最新价 / 涨跌幅 / 换手率 / 成交额（动态表头 7 列）
- [ ] 对比同花顺「换手率排行」TOP 10 数据一致（允许 ±0.01% 误差） <!-- 运行时验证 -->
- [x] 模拟 `daily_basic` 表无当日数据时，换手率榜 Tab 显示「暂无数据」占位，其他 3 Tab 正常

---

## FR-4 K 线技术指标切换（D4）

- [x] `theme.css` 三主题（azure / mist / cyber）各有 13 个指标颜色变量
- [x] `charts-theme.js` `getKlineTheme()` 读取上述 CSS 变量返回 `indicatorColors` 配置
- [x] `dashboard.js` 实现了 `calcEMA` / `calcMACD` / `calcKDJ` / `calcRSI` / `calcBOLL` 算法
- [x] MACD 参数 fastperiod=12 / slowperiod=26 / signalperiod=9，返回 {dif, dea, hist}（hist = dif - dea 标准 TA-Lib）
- [x] KDJ 参数 fastk_period=9 / slowk_period=3 / slowd_period=3，J = 3K - 2D（STOCH 用 SMA 平滑）
- [x] RSI 参数 timeperiod=6/12/24（Wilder 平滑法）
- [x] BOLL 参数 timeperiod=20 / nbdevup=2.0 / nbdevdn=2.0，返回 {upper, middle, lower}（总体标准差）
- [ ] 对比同花顺 K 线图 MACD/KDJ/RSI/BOLL 数值一致（允许 ±0.01 误差） <!-- 运行时验证 -->
- [x] K 线图搜索栏右侧有主图指标切换控件（MA / BOLL 按钮组）
- [x] 切到 BOLL 后主图显示上下轨 + 带状区域（动态 add/remove 三条 LineSeries）
- [x] K 线图下方有副图指标切换控件（无 / MACD / KDJ / RSI 按钮组）
- [x] 切到 MACD 显示 DIF/DEA 线 + 红绿柱状图（HistogramStyle 正负着色）
- [x] 切到 KDJ 显示 K/D/J 三线
- [x] 切到 RSI 显示 RSI6/12/24 三线
- [ ] 指标切换响应时间 ≤ 500ms <!-- 运行时验证：需浏览器实际测试 -->
- [x] 三主题切换后 K 线图与指标线颜色同步变化（监听 `theme:changed` 80ms 后重建指标系列）
- [x] K 线数据不足时（< 26 根），主图正常渲染，副图显示「数据不足 (需要至少 N 根K线,当前 M 根)」占位

---

## FR-5 板块概览快照卡片（D5）

- [x] `dashboard.html` 有板块概览卡片容器 `#sectorOverviewContainer`（领涨 TOP 5 + 领跌 TOP 5 + 查看全部链接）
- [x] `dashboard.js` 有 `fetchSectorOverview()` 方法，调用 `/api/industry/ranking?limit=5` 接口
- [ ] 015 模块上线时，卡片显示 10 个行业条目（5 红 5 绿） <!-- 运行时验证：需 015 模块实现后验证 -->
- [ ] 行业涨跌幅基于 `index_daily` 表申万行业指数（`801xxx.SI`）的 `pct_chg` <!-- 运行时验证：需 015 模块实现后验证 -->
- [ ] 行业名显示申万一级行业中文名（如「银行」「非银金融」），不显示代码 <!-- 运行时验证：需 015 模块实现后验证 -->
- [x] 015 模块未上线时，卡片显示「板块行情功能开发中」占位（`showSectorDeveloping()` 处理 fetch 异常）
- [x] 点击「查看全部」跳转 `/page/sector`（015 上线后生效）
- [x] D5 降级不阻塞其他 4 项功能正常展示（独立异步加载，fetch 异常不影响其他模块）

---

## 整体验收

- [ ] 首屏加载时间 ≤ 2 秒 <!-- 运行时验证：需浏览器实际测量 -->
- [x] 各模块独立异步加载，不互相阻塞（fetchMarketTemperature / fetchSectorOverview / fetchMarketRanking 独立调用）
- [x] 无北向资金相关卡片（D6 已砍掉，未实现北向资金卡片）
- [x] 无硬编码 mock 数据（MarketServiceImpl.java 搜索 3368.07 / 10856.23 / 2156.89 / 968.45 无结果）
- [x] 三主题（azure / mist / cyber）切换后图表颜色用 CSS 变量（charts-theme.js 监听 `theme:changed` 事件重绘）
- [x] 所有指数卡片显示数据日期字段（「截至 YYYY-MM-DD」）
- [ ] 浏览器兼容：Chrome / Edge / Firefox 最新版正常显示 <!-- 运行时验证：需多浏览器测试 -->
