# Tasks

> **change-id**：`016-dashboard-completion`
> **PRD**：`sdlc/prd/012-仪表盘/仪表盘PRD.md`
> **依赖**：index_daily 数据底座是 D1/D5 硬前置；D5 强依赖 015 板块行情模块；D3 榜单跳转弱依赖 017 个股诊断。

---

## 第零波：index_daily 数据底座（D1/D5 硬前置）

- [x] Task 0.1：创建 `index_daily` 表 DDL（SQLite + MySQL 双写）
  - [x] SubTask 0.1.1：在 `schema-sqlite.sql` 加 `index_daily` 表 DDL（字段：ts_code/trade_date/close/open/high/low/pre_close/change/pct_chg/vol/amount，联合主键 ts_code+trade_date，trade_date 索引）
  - [x] SubTask 0.1.2：在 `schema-mysql.sql` 加对应 DDL（数据类型适配 MySQL）

- [x] Task 0.2：创建 IndexDailyDO + IndexDailyMapper + XML
  - [x] SubTask 0.2.1：新建 `model/IndexDailyDO.java`（字段对应表结构，Lombok @Data，changeValue 字段映射 change 列）
  - [x] SubTask 0.2.2：新建 `mapper/IndexDailyMapper.java`（6 个方法：insertBatch / deleteBatchByKeys / selectLatestTradeDate / selectByCodesAndTradeDate / selectLatestByCodes / selectByCodeOrderByTradeDate）
  - [x] SubTask 0.2.3：新建 `resources/mapper/IndexDailyMapper.xml`（SQL 实现，change 反引号转义兼容 SQLite/MySQL）
  - [x] SubTask 0.2.4：`target/classes/mapper/IndexDailyMapper.xml` 旧构建残留已被新构建覆盖

- [x] Task 0.3：创建 IndexDailyService + Impl
  - [x] SubTask 0.3.1：新建 `service/IndexDailyService.java` 接口（getLatestByCodes / getByCodesAndTradeDate / getByCodeOrderByTradeDate）
  - [x] SubTask 0.3.2：新建 `service/impl/IndexDailyServiceImpl.java` 实现，getLatestByCodes 先查 MAX(trade_date) 再取数据

- [x] Task 0.4：注册 Tushare index_daily 接口 + 拉取任务
  - [x] SubTask 0.4.1：在 `constant/TushareApiEnum.java` 注册 `INDEX_DAILY` 枚举值
  - [x] SubTask 0.4.2：在 `client/TushareClient.java` 加 `fetchIndexDaily(tsCode, startDate, endDate)` 方法
  - [x] SubTask 0.4.3：新建 `service/IndexDailyFetchService.java`，实现 `fetchAndSaveIndexDaily(tsCode, startDate, endDate)` -> 调 TushareClient -> 先删后插实现幂等 upsert
  - [x] SubTask 0.4.4：注册定时任务 `@Scheduled(cron="0 30 16 * * MON-FRI")`，拉取 4 大盘指数 + sw_industry level=1 动态读取的申万一级行业指数

- [ ] Task 0.5：初始化历史数据拉取
  - [ ] SubTask 0.5.1：编写一次性初始化脚本或手动触发入口，拉取 4 大盘指数 + 28 申万行业指数的近 1 年历史数据
  - [ ] SubTask 0.5.2：验证数据落库正确（SELECT count(*) / 抽样对比 Tushare 原始数据）

---

## 第一波 A：D1 大盘指数真实数据（依赖第零波）

- [x] Task 1.1：新建 IndexDailyController 查询端点
  - [x] SubTask 1.1.1：新建 `controller/IndexDailyController.java`，暴露 `GET /api/index-daily/latest?codes=000001.SH,399001.SZ,...` 端点
  - [x] SubTask 1.1.2：返回 `ApiResponse<List<IndexDailyDO>>`（直接用 DO 返回，字段对应表结构）
  - [x] SubTask 1.1.3：codes 参数用单个 `@RequestParam String` 按逗号分割，无需封装 DTO

- [x] Task 1.2：MarketServiceImpl 移除 mock，改调 IndexDailyService
  - [x] SubTask 1.2.1：**删除** `MarketServiceImpl.java:31-44` 硬编码 mock 数据
  - [x] SubTask 1.2.2：`getMarketIndices()` 改为调用 `IndexDailyService.getLatestByCodes(["000001.SH","399001.SZ","399006.SZ","000688.SH"])`
  - [x] SubTask 1.2.3：`@Cacheable("indices")` 缓存 key 改为 `#root.target.getLatestTradeDate()`（按 index_daily 最新交易日失效）
  - [x] SubTask 1.2.4：数据为空时返回空列表（不抛异常），让前端显示「暂无数据」

- [x] Task 1.3：前端适配真实数据 + 降级
  - [x] SubTask 1.3.1：`dashboard.html` Row 1 指数卡片保持 Thymeleaf `${indices}` 服务端渲染结构
  - [x] SubTask 1.3.2：`dashboard.html` 空数据兜底（`th:if="${#lists.isEmpty(indices)}"` 展示 4 个「暂无数据」占位卡片）
  - [x] SubTask 1.3.3：每个卡片显示数据日期字段（MarketIndexVO 加 tradeDate 字段，后端 YYYYMMDD 转 YYYY-MM-DD，前端显示「截至 YYYY-MM-DD」）
  - [x] SubTask 1.3.4：涨跌色用 `.stock-up` / `.stock-down`（components.css 新增，用 CSS 变量 `--rise-light` / `--fall-light`）

---

## 第一波 B：D2 市场温度卡片（可与第一波 A 并行）

- [x] Task 2.1：DailyQuoteMapper 加聚合统计方法
  - [x] SubTask 2.1.1：加 `countUpByTradeDate(tradeDate)` -- 涨家数（pct_chg > 0）
  - [x] SubTask 2.1.2：加 `countDownByTradeDate(tradeDate)` -- 跌家数（pct_chg < 0）
  - [x] SubTask 2.1.3：加 `countFlatByTradeDate(tradeDate)` -- 平家数（pct_chg = 0）
  - [x] SubTask 2.1.4：加 `countLimitUpByTradeDate(tradeDate)` -- 涨停数（JOIN stock_basic 按板块区分规则）
  - [x] SubTask 2.1.5：加 `countLimitDownByTradeDate(tradeDate)` -- 跌停数（JOIN stock_basic 按板块区分规则）
  - [x] SubTask 2.1.6：对应 XML SQL 实现，涨停规则：主板/北交所 pct_chg >= 9.9、创业板/科创板 pct_chg >= 19.9、ST name LIKE 'ST%' pct_chg >= 4.9

- [x] Task 2.2：MarketTemperatureVO + Service 方法
  - [x] SubTask 2.2.1：新建 `vo/MarketTemperatureVO.java`（tradeDate / upCount / downCount / flatCount / limitUpCount / limitDownCount）
  - [x] SubTask 2.2.2：`MarketService` 接口加 `getMarketTemperature(tradeDate)` 方法
  - [x] SubTask 2.2.3：`MarketServiceImpl` 实现，tradeDate 为 null 时取 daily_quote 最新交易日，调用 5 个聚合统计方法组装 VO

- [x] Task 2.3：MarketController 加端点
  - [x] SubTask 2.3.1：加 `GET /market/temperature?tradeDate=<今日>` 端点，返回 `ApiResponse<MarketTemperatureVO>`
  - [x] SubTask 2.3.2：tradeDate 参数可选，默认取最新交易日

- [x] Task 2.4：前端市场温度卡片
  - [x] SubTask 2.4.1：`dashboard.html` 在 Row 1 与 Row 2 之间加市场温度卡片容器（横向 5 格布局）
  - [x] SubTask 2.4.2：`dashboard.js` 加 `fetchMarketTemperature()` 方法，调用 `/market/temperature` 渲染 5 个数字
  - [x] SubTask 2.4.3：涨跌色：涨家数红色、跌家数绿色、平家数灰色、涨停数红色加粗、跌停数绿色加粗
  - [x] SubTask 2.4.4：数据缺失时显示 `--` 占位（`showTemperatureEmpty()`）

---

## 第二波：D3 换手率榜 TOP 10（可与第一波并行）

- [x] Task 3.1：DailyQuoteMapper 加 selectTopTurnover
  - [x] SubTask 3.1.1：加 `selectTopTurnover(tradeDate, limit)` 方法，JOIN `daily_quote` + `daily_basic`（通过 ts_code + trade_date 关联），按 `turnover_rate` 降序取 TOP 10
  - [x] SubTask 3.1.2：返回字段：ts_code / name / close / pct_chg / turnover_rate / amount（直接返回 `StockRankVO`，SQL JOIN stock_basic 取 name，避免 N+1）
  - [x] SubTask 3.1.3：对应 XML SQL 实现（新增 `stockRankMap` resultMap）

- [x] Task 3.2：MarketRankingVO 加 topTurnover + Service 扩展
  - [x] SubTask 3.2.1：`MarketRankingVO` 加 `topTurnover` 字段（`List<StockRankVO>`）
  - [x] SubTask 3.2.2：`MarketServiceImpl.getMarketRanking()` 加调 `selectTopTurnover`，组装到 VO
  - [x] SubTask 3.2.3：验证既有 3 榜数据不变（向后兼容，StockRankVO 新增 turnoverRate 字段不影响既有序列化）

- [x] Task 3.3：前端榜单区 3 卡片改 4 Tab
  - [x] SubTask 3.3.1：`dashboard.html` Row 3 重构：3 张并排卡片 -> 1 个 Tab 切换区（`#rankingTabs` 导航 + `#rankingBody` 单表格）
  - [x] SubTask 3.3.2：`dashboard.js` 加 `switchRankingTab(tab)` / `renderRankingTab(tab)` Tab 切换逻辑，默认选中涨幅榜
  - [x] SubTask 3.3.3：`dashboard.js` 加换手率榜渲染（动态表头：7 列含换手率 + 成交额）
  - [x] SubTask 3.3.4：换手率数据缺失时，换手率榜 Tab 显示「暂无数据」占位，其他 3 Tab 正常

---

## 第三波：D4 K 线技术指标切换（前端独立，可与第二波并行）

- [x] Task 4.1：theme.css 加指标颜色变量 + charts-theme.js 扩展
  - [x] SubTask 4.1.1：`theme.css` 三主题（azure / mist / cyber）各加 13 个指标颜色变量：`--macd-dif` / `--macd-dea` / `--macd-hist-up` / `--macd-hist-down` / `--kdj-k` / `--kdj-d` / `--kdj-j` / `--rsi-6` / `--rsi-12` / `--rsi-24` / `--boll-upper` / `--boll-mid` / `--boll-lower`
  - [x] SubTask 4.1.2：`charts-theme.js` `getKlineTheme()` 扩展，读取上述 CSS 变量，返回 indicatorColors 配置
  - [x] SubTask 4.1.3：确保三主题切换后指标颜色同步变化（监听 `theme:changed` 事件 80ms 后更新指标系列颜色）

- [x] Task 4.2：dashboard.js 实现指标算法（与 akquant.talib 口径对齐）
  - [x] SubTask 4.2.1：实现 `calcEMA(data, period)` 指数均线（SMA 种子 + EMA 递推）
  - [x] SubTask 4.2.2：实现 `calcMACD(closeData)` 返回 `{dif, dea, hist}`，参数 fastperiod=12 / slowperiod=26 / signalperiod=9，hist = dif - dea（标准 TA-Lib）
  - [x] SubTask 4.2.3：实现 `calcKDJ(high, low, close)` 返回 `{k, d, j}`，参数 fastk_period=9 / slowk_period=3 / slowd_period=3，J = 3*K - 2*D（STOCH 用 SMA 平滑）
  - [x] SubTask 4.2.4：实现 `calcRSI(closeData, periods=[6,12,24])` 返回三条 RSI 线（Wilder 平滑法）
  - [x] SubTask 4.2.5：实现 `calcBOLL(closeData)` 返回 `{upper, middle, lower}`，参数 timeperiod=20 / nbdevup=2.0 / nbdevdn=2.0（总体标准差）
  - [ ] SubTask 4.2.6：验证算法正确性（对比同花顺 K 线图 MACD/KDJ/RSI/BOLL 数值，误差 ≤ 0.01）<!-- 待运行时验证 -->

- [x] Task 4.3：dashboard.html 加指标切换 UI
  - [x] SubTask 4.3.1：K 线图搜索栏右侧加主图指标切换控件（MA / BOLL 按钮组，ms-auto 推至右端）
  - [x] SubTask 4.3.2：K 线图下方加副图指标切换控件（无 / MACD / KDJ / RSI 按钮组）+ `#subIndicatorChart` 容器（160px 高，默认 display:none）

- [x] Task 4.4：dashboard.js 副图渲染逻辑
  - [x] SubTask 4.4.1：`renderKline` 扩展：`ensureSubChart()` 懒创建独立副图 chart，按指标类型创建对应系列（MACD 含 DIF/DEA 折线 + HIST 直方图正负着色，KDJ/RSI 折线）
  - [x] SubTask 4.4.2：BOLL 主图叠加渲染（上轨 / 下轨 / 中轨 LineSeries 动态 add/remove）
  - [x] SubTask 4.4.3：指标切换时 `switchMainIndicator`/`switchSubIndicator` 清理旧系列创建新系列，主/副图时间轴双向同步（`subscribeVisibleTimeRangeChange` + `syncingTimeScale` 防死循环）
  - [x] SubTask 4.4.4：数据不足时（如 < 26 根）副图显示「数据不足 (需要至少 N 根K线,当前 M 根)」占位
  - [x] SubTask 4.4.5：主题切换后副图指标颜色同步更新（监听 `theme:changed` 80ms 后重建指标系列）

---

## 第四波：D5 板块概览快照卡片（依赖第零波 index_daily）

- [x] Task 5.1：前端板块概览卡片
  - [x] SubTask 5.1.1：`dashboard.html` Row 3 右侧加板块概览卡片容器 `#sectorOverviewContainer`（领涨 TOP 5 + 领跌 TOP 5 + 查看全部链接）
  - [x] SubTask 5.1.2：`dashboard.js` 加 `fetchSectorOverview()` 方法，调用 `GET /api/industry/ranking?limit=5` 接口

- [x] Task 5.2：降级与跳转
  - [x] SubTask 5.2.1：015 模块接口不可用时（HTTP 非 200 或 fetch 抛错），`showSectorDeveloping()` 显示「板块行情功能开发中」占位
  - [x] SubTask 5.2.2：「查看全部」链接指向 `/page/sector`，015 未上线时页面跳转 404（可接受，015 上线后自动生效）
  - [x] SubTask 5.2.3：015 上线后无需改 D5 代码（接口契约已对齐，兼容 topGainers/gainers 两种返回结构）

---

## 收尾：集成验证

- [x] Task 6.1：首屏并行加载优化
  - [x] SubTask 6.1.1：4 个指数卡片 / 市场温度 / 榜单 / K 线图 / 板块概览各自独立异步加载，不互相阻塞（fetchMarketTemperature / fetchSectorOverview / fetchMarketRanking / fetchKlineData 独立调用）
  - [ ] SubTask 6.1.2：验证首屏加载时间 ≤ 2 秒 <!-- 运行时验证：需浏览器实际测量 -->

- [x] Task 6.2：三主题切换验证
  - [x] SubTask 6.2.1：三主题（azure / mist / cyber）切换后，所有卡片与图表颜色同步变化（charts-theme.js 监听 `theme:changed` 事件重绘 + dashboard.js 80ms 后重建指标系列）
  - [x] SubTask 6.2.2：涨跌色用 CSS 变量（components.css `.stock-up` / `.stock-down` 用 `--rise-light` / `--fall-light`），不硬编码颜色值

- [x] Task 6.3：降级场景验证
  - [x] SubTask 6.3.1：`index_daily` 无数据 -> D1 卡片显示「暂无数据」（`th:if="${#lists.isEmpty(indices)}"` 占位卡片）
  - [x] SubTask 6.3.2：`daily_quote` 无数据 -> D2 市场温度显示 `--` 占位（`showTemperatureEmpty()`）
  - [x] SubTask 6.3.3：`daily_basic` 无数据 -> D3 换手率榜显示「暂无数据」占位，其他 3 Tab 正常
  - [x] SubTask 6.3.4：015 接口不可用 -> D5 卡片显示「板块行情功能开发中」（`showSectorDeveloping()` 处理 fetch 异常）
  - [x] SubTask 6.3.5：K 线数据 < 26 根 -> D4 副图显示「数据不足 (需要至少 N 根K线,当前 M 根)」占位

- [x] Task 6.4：无硬编码 mock 验证
  - [x] SubTask 6.4.1：`MarketServiceImpl.java` 搜索 `3368.07` 等历史 mock 值，无结果（grep 验证通过）
  - [x] SubTask 6.4.2：无北向资金相关卡片（D6 已砍掉，未实现任何北向资金卡片）

---

# Task Dependencies

- **第零波**是 D1（第一波 A）和 D5（第四波）的硬前置：`index_daily` 表未落地时 D1/D5 无法接真实数据
- **第一波 A**（D1）和**第一波 B**（D2）可并行
- **第二波**（D3）可与第一波 A/B 并行
- **第三波**（D4）前端独立，可与第二波并行
- **第四波**（D5）依赖第零波的 index_daily 申万行业指数数据；D5 强依赖 015 模块的 `/api/industry/ranking` 接口，015 未上线时降级显示占位
- **收尾**（第六波）依赖前五波全部完成
- D3 榜单股票代码跳转 `/page/stock-detail/{code}` 弱依赖 017 个股诊断模块，017 未上线时股票代码显示纯文本（不可点击）
