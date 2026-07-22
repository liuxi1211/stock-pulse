# 板块行情模块 Spec

> **来源 PRD**：`sdlc/prd/015-板块行情/板块行情PRD.md`
> **change-id**：`019-sector-market`
> **基线**：stock-watcher（Java 21 + Spring Boot 4.0.6）· stock-engine（Python 3.12 + FastAPI + AKQuant 0.2.47）
> **页面路由**：`/page/sector`
> **状态**：规划中

---

## Why

板块行情是老股民看盘的第一视角——A 股结构性行情主导，行业轮动是选股的前置信号。当前 stock-pulse 没有独立的板块行情页面，用户只能跳到东方财富/同花顺看，跳出本系统。

现有 `sw_industry` / `sw_industry_member` Service 完整但仅给 engine 量化层用，未对前端暴露；`index_daily` 表与 Service 已由数据采集任务落地但 Controller 仅暴露 `/latest` 端点。仪表盘（016）已在 `dashboard.js` 中调用 `/api/industry/ranking?limit=5`，但该端点尚未实现，仪表盘板块概览卡片当前显示「板块行情功能开发中」占位。

本 spec 兑现 PRD 的 6 项功能需求（FR-1 ~ FR-7）：路由与页面骨架、行业排行表、成分股展开列表、行业板块 K 线图、板块轮动热度图、后端 Controller、前端三件套，同时补齐仪表盘对 `/api/industry/ranking` 的已有调用。

---

## What Changes

### 后端

- **扩展 `SwIndustryController`**：新增 `GET /api/industry/ranking` 端点，返回申万一级 28 个行业的排行数据（行业名/涨跌幅/成交额/领涨股/领跌股），关联 `index_daily` + `sw_industry_member` + `daily_quote` + `stock_basic` 即时聚合
- **扩展 `SwIndustryController`**：新增 `GET /api/industry/members` 端点，分页返回指定行业的成分股（含股票代码/名称/最新价/涨跌幅/成交量/成交额/市场），JOIN `sw_industry_member` + `stock_basic` + `daily_quote`
- **扩展 `IndexDailyController`**：新增 `GET /api/index-daily` 端点，按 `tsCode` + 可选 `startDate`/`endDate`/`limit` 查询指数日线（按 trade_date 升序返回，供前端直接渲染 K 线）
- **新建 VO**：`IndustryRankingVO`（行业排行视图对象）、`IndustryMemberVO`（行业成分股视图对象）
- **扩展 `SwIndustryService`**：新增 `getIndustryRanking(String tradeDate)` 方法（Java 层聚合 28 行业排行）和 `getIndustryMembers(String industryCode, int page, int size, String keyword)` 方法
- **扩展 `SwIndustryMemberMapper`**：新增 `selectAllCurrentL1Members()` 查询全量当前一级成分股、`selectMembersByIndexCode(String indexCode)` 按行业代码查成分股
- **扩展 `DailyQuoteMapper`**（或新建 XML）：新增按 `ts_code` 列表 + 交易日 JOIN `stock_basic` 查询成分股行情（含股票名称/市场）
- **更新 `dashboard.js`**：`fetchSectorOverview` 适配新的 flat list 返回格式（原期望 `{ topGainers, topLosers }`，改为接收 `List<IndustryRankingVO>` 后在前端排序+截取 top5/bottom5）

### 前端

- **修改 `fragments/common.html`**：侧边栏「主要功能」分区新增「板块行情」菜单项（`/page/sector`，activeMenu=`sector`），位于「自选股」之后
- **修改 `PageController.java`**：新增 `GET /page/sector` 路由，返回 `pages/sector` 视图
- **新建 `templates/pages/sector.html`**：页面模板，套用 `fragments/common.html` 骨架，包含顶部热度图区 + 主体区（排行表 + 成分股展开 + K 线图）
- **新建 `static/js/sector.js`**：页面脚本（API 调用 / 热度图渲染 / 排行表渲染 / 成分股分页+搜索 / K 线图渲染 / 行业切换联动）
- **新建 `static/css/sector.css`**：页面样式（布局栅格 / 热度图格子样式 / 排行表样式 / 成分股列表样式 / K 线图容器样式）

### 不做（明确排除）

- **概念板块 Tab**：`concept` / `concept_detail` 接口不存在，页面为单 Tab（仅行业板块）
- **行业板块资金流向柱状图**：`moneyflow_dc` 接口不存在
- **今日板块资金净流入 TOP 10**：同上
- **板块成分股实时分时**：成分股列表用 `daily_quote` 最新收盘，不做日内分时
- **跨市场板块对比**：只看 A 股申万行业
- **板块内个股加权指数自算**：板块指数统一用 `index_daily` 已有的申万行业指数 ts_code
- **K 线周期切换（日/周/月/分钟）**：本模块只看日线
- **renderKline 函数抽取为公共模块**：优先复制到 `sector.js`，不做抽取（避免影响 dashboard.js 稳定性）

---

## Impact

### Affected specs

- **上游依赖**：无（`index_daily` 表 + Service 已由数据采集任务落地；`sw_industry` / `sw_industry_member` Service 已存在）
- **下游依赖（强）**：`016-dashboard-completion` — 仪表盘板块概览卡片复用本 spec 的 `GET /api/industry/ranking` 端点
- **下游依赖（弱）**：`017-market-center-completion` — 行情中心行业筛选下拉复用 `GET /api/industry/list` 端点（已存在）
- **下游依赖（弱）**：个股诊断（017 PRD）— 成分股点击跳转 `/page/stock-detail/{code}`，017 上线后生效

### Affected code

| 文件 | 改动 |
|---|---|
| `controller/SwIndustryController.java` | **修改**：新增 `/ranking` 和 `/members` 两个端点 |
| `controller/IndexDailyController.java` | **修改**：新增 `GET /` 端点（按 tsCode + date range / limit 查询） |
| `service/SwIndustryService.java` | **修改**：新增 `getIndustryRanking` 和 `getIndustryMembers` 方法签名 |
| `service/impl/SwIndustryServiceImpl.java` | **修改**：实现上述两个方法（Java 层聚合排行数据） |
| `mapper/SwIndustryMemberMapper.java` | **修改**：新增 `selectAllCurrentL1Members` / `selectMembersByIndexCode` 方法 |
| `mapper/IndexDailyMapper.java` | **修改**：新增按 tsCode + 日期范围查询方法（如 Service 层需要） |
| `mapper/DailyQuoteMapper.java` | **修改**：新增按 ts_code 列表 + 交易日 JOIN stock_basic 查询成分股行情 |
| `resources/mapper/DailyQuoteMapper.xml` | **修改**：新增上述 SQL 映射 |
| `resources/mapper/SwIndustryMemberMapper.xml` | **修改**：如使用 XML 则新增 SQL；如用注解则不改 XML |
| `vo/IndustryRankingVO.java` | **新建**：行业排行视图对象 |
| `vo/IndustryMemberVO.java` | **新建**：行业成分股视图对象 |
| `controller/PageController.java` | **修改**：新增 `/page/sector` 路由 |
| `templates/fragments/common.html` | **修改**：侧边栏新增「板块行情」菜单项 |
| `templates/pages/sector.html` | **新建**：页面模板 |
| `static/js/sector.js` | **新建**：页面脚本 |
| `static/css/sector.css` | **新建**：页面样式 |
| `static/js/dashboard.js` | **修改**：`fetchSectorOverview` / `renderSectorOverview` 适配 flat list 返回格式 |

---

## 现状校正（PRD 与实际代码的差异）

| PRD 描述 | 实际代码 | spec 处理 |
|---|---|---|
| K 线图「渲染库 ECharts」 | `dashboard.js` 的 `renderKline` 使用 **LightweightCharts**（非 ECharts），已在 `common.html` 全局引入 `lightweight-charts@4.2.1` | **本 spec 使用 LightweightCharts**（与仪表盘一致），复制 `renderKline` 逻辑到 `sector.js` 并适配行业指数数据。热度图使用原生 HTML grid（非 ECharts），避免 28 格场景引入 ECharts 开销 |
| PRD 建议 `GET /api/sector/industry-ranking` 返回 28 行排行数据 | `dashboard.js` 已硬编码调用 `GET /api/industry/ranking?limit=5`，期望返回 `{ topGainers, topLosers }` 结构 | **统一为 `GET /api/industry/ranking` 返回 `List<IndustryRankingVO>` flat list**（全部 28 行），移除 `limit` 参数；`dashboard.js` 的 `fetchSectorOverview` / `renderSectorOverview` 同步修改为前端排序+截取 top5/bottom5 |
| PRD 建议 `GET /api/sector/industry-members?industryCode=...` | 现有 `SwIndustryController` 路径前缀为 `/api/industry` | **统一在 `/api/industry` 下**：`GET /api/industry/members?industryCode=...`（与现有 `/api/industry/list` 同前缀） |
| PRD 建议 `GET /api/index-daily?tsCode=...&limit=...` | 现有 `IndexDailyController` 仅有 `GET /api/index-daily/latest`，路径前缀为 `/api/index-daily` | **在现有 Controller 新增 `GET /api/index-daily`** 根路径端点（与 `/latest` 同前缀），Service 层已有 `getByCodeOrderByTradeDate` 可复用 |
| PRD FR-6.1 返回字段含 `index_code`（对应 index_daily 的 ts_code） | `SwIndustryVO` 仅含 `industryCode` / `industryName`，无 `indexCode` 字段；`sw_industry` 表的 `index_code` 即为 `index_daily` 的 `ts_code`（如 `801010.SI`） | **`IndustryRankingVO` 新增 `indexCode` 字段**（值 = `sw_industry.index_code`），供前端查 K 线用；`SwIndustryVO` 不修改（不影响现有行业筛选下拉） |
| `target/classes` 中存在旧版 `sector.html/js/css`（已从 `src/main/resources` 删除） | 旧版调用 `/api/sw_industry/list`（非 `/api/industry/list`）、`/tushare/block_moneyflow`（接口不存在）、`/tushare/index_daily`（路径不匹配） | **忽略旧版**，以本 spec 定义的 API 路径为准；旧版 build 产物将在下次 `mvn compile` 时覆盖 |

---

## 架构决策

### 决策 1：行业排行数据聚合方式——Java 层聚合 vs 单条 SQL

**选择**：Java 层多步聚合。

**理由**：
- 排行需关联 4 张表（`sw_industry` + `index_daily` + `sw_industry_member` + `daily_quote`），SQLite 的 JOIN + 子查询性能不可控
- 分步查询可复用已有 Mapper 方法（`selectByCodesAndTradeDate` / `selectAllCurrentL1Members`），减少新 SQL
- Java 层分组+排序逻辑清晰，便于维护和调试
- 28 行业 × N 成分股的数据量在 Java 层聚合性能可接受（全市场约 5000 只股票，一次批量查询 + 内存分组）

**实现路径**：
1. `SwIndustryService.listByLevel(1)` 取 28 个一级行业（含 `index_code`）
2. `SwIndustryMemberMapper.selectAllCurrentL1Members()` 取全量当前一级成分股
3. `DailyQuoteMapper.selectLatestTradeDate()` 取最新交易日
4. `IndexDailyService.getLatestByCodes(28个行业指数代码)` 批量取行业指数行情
5. `DailyQuoteMapper.selectByCodesAndTradeDate(全部成分股ts_code列表, 最新交易日)` 批量取成分股行情
6. Java 层：按 `index_code` 分组成分股，每组按 `pct_chg` 排序取 top1（领涨）/ bottom1（领跌），拼装 `IndustryRankingVO`

### 决策 2：热度图渲染——原生 HTML grid vs ECharts

**选择**：原生 HTML grid（CSS Grid 7×4）。

**理由**：
- 28 个固定格子，无需 ECharts 的坐标轴/缩放/动画能力
- 原生 div 可完全自定义格内内容（行业名 + 涨跌幅 + 成交额），ECharts heatmap/treemap 的 label formatter 难以实现复杂格内布局
- 性能更优（28 个 div 首屏渲染 < 50ms，满足 NFR-1 < 500ms）
- 颜色梯度在 JS 中基于涨跌幅阈值映射 CSS 变量（`--rise-color` / `--fall-color`），主题切换自动生效
- 悬浮 tooltip 用 Bootstrap Tooltip 或自定义浮窗，点击事件用原生 `addEventListener`

### 决策 3：K 线图渲染——复制 renderKline vs 引用 dashboard.js

**选择**：复制 `renderKline` 核心逻辑到 `sector.js`，适配行业指数数据格式。

**理由**：
- PRD 风险段明确建议「优先复制到 `sector.js`，不做抽取（避免影响 dashboard.js 稳定性）」
- `dashboard.js` 的 `renderKline` 绑定 `#klineChart` 容器和内部状态（`klineDataCache`），直接引用会冲突
- `sector.js` 需要额外的时间范围切换（60/120/250/全部）和行业切换联动，与仪表盘 K 线需求不同
- 后续联调阶段可统一重构为公共模块，当前不做

### 决策 4：`/api/industry/ranking` 返回格式——flat list vs topGainers/topLosers

**选择**：flat list（`List<IndustryRankingVO>`），不带 `limit` 参数。

**理由**：
- 板块行情页面需要全部 28 行业的完整排行
- 仪表盘只需要 top5/bottom5，在前端排序+截取即可（`dashboard.js` 修改量小）
- 统一返回格式避免同一接口两种返回结构的不一致性
- `limit` 参数语义模糊（limit=5 是取前5还是取涨跌各5？），flat list 最清晰

---

## ADDED Requirements

### Requirement: 路由与页面骨架（FR-1 / S1）

系统 SHALL 在侧边栏「主要功能」分区新增「板块行情」菜单项（第 4 项，位于「自选股」之后），路由 `/page/sector`，点击后渲染 `sector.html` 页面。

#### Scenario: 访问板块行情页面
- **WHEN** 用户点击侧边栏「板块行情」菜单项
- **THEN** 浏览器导航到 `/page/sector`
- **AND** 页面正常加载（不 500、不白屏），页面标题显示「板块行情」
- **AND** 侧边栏该菜单项高亮（`active` 类）

#### Scenario: 侧边栏菜单顺序
- **WHEN** 查看侧边栏「主要功能」分区
- **THEN** 菜单顺序为：仪表盘 → 行情中心 → 自选股 → 板块行情 → 资金流向（待落地）
- **AND** 无「K 线分析」占位项（如仍存在则移除）

---

### Requirement: 行业排行接口（FR-6 / S2）

系统 SHALL 提供 `GET /api/industry/ranking` 端点，返回申万一级 28 个行业的排行数据，包含行业名/行业代码/指数代码/今日涨跌幅/成交额/领涨股/领跌股，数据在 Service 层即时聚合（不落库派生数据）。

#### Scenario: 正常获取行业排行
- **WHEN** 调用 `GET /api/industry/ranking`（不带参数）
- **THEN** 返回 `ApiResponse<List<IndustryRankingVO>>`，含 28 条记录
- **AND** 每条记录包含 `industryCode` / `industryName` / `indexCode` / `pctChg` / `amount` / `topGainerCode` / `topGainerName` / `topGainerPctChg` / `topLoserCode` / `topLoserName` / `topLoserPctChg`
- **AND** 领涨股/领跌股为该行业当前成分股中当日涨幅最大/最小的股票

#### Scenario: 指定交易日查询
- **WHEN** 调用 `GET /api/industry/ranking?tradeDate=20240115`
- **THEN** 返回该交易日的行业排行数据

#### Scenario: 行业指数缺数据
- **WHEN** 某行业指数在 `index_daily` 中无当日数据
- **THEN** 该行业记录的 `pctChg` / `amount` 为 null
- **AND** 领涨股/领跌股仍从成分股 `daily_quote` 中聚合

#### Scenario: 仪表盘兼容
- **WHEN** `dashboard.js` 调用 `GET /api/industry/ranking`
- **THEN** 返回 flat list，`dashboard.js` 在前端按 `pctChg` 降序排序，截取前 5 为涨幅榜、后 5 为跌幅榜
- **AND** 仪表盘板块概览卡片正常渲染（不再显示「板块行情功能开发中」）

---

### Requirement: 行业成分股接口（FR-6 / S3）

系统 SHALL 提供 `GET /api/industry/members` 端点，分页返回指定行业的当前成分股，含股票代码/名称/最新价/涨跌幅/成交量/成交额/市场。

#### Scenario: 正常分页查询
- **WHEN** 调用 `GET /api/industry/members?industryCode=801010&page=1&size=20`
- **THEN** 返回 `ApiResponse<PageResult<IndustryMemberVO>>`
- **AND** 每条记录包含 `tsCode` / `name` / `close` / `pctChg` / `vol` / `amount` / `market`
- **AND** 分页信息含 `total` / `page` / `size`

#### Scenario: 关键字搜索
- **WHEN** 调用 `GET /api/industry/members?industryCode=801010&keyword=银行&page=1&size=20`
- **THEN** 返回名称或代码包含「银行」的成分股列表

#### Scenario: 无成分股
- **WHEN** 某行业在 `sw_industry_member` 中无 `is_new='1'` 的成分股
- **THEN** 返回空列表，`total=0`

---

### Requirement: 指数日线查询接口（FR-6 / S4）

系统 SHALL 提供 `GET /api/index-daily` 端点，按指数代码查询日线行情，支持日期范围和条数限制，按 `trade_date` 升序返回（供前端直接渲染 K 线）。

#### Scenario: 按条数查询
- **WHEN** 调用 `GET /api/index-daily?tsCode=801010.SI&limit=250`
- **THEN** 返回 `ApiResponse<List<IndexDailyDO>>`，含最近 250 个交易日的指数日线
- **AND** 按 `trade_date` 升序排列

#### Scenario: 按日期范围查询
- **WHEN** 调用 `GET /api/index-daily?tsCode=801010.SI&startDate=20240101&endDate=20240630`
- **THEN** 返回该日期范围内的指数日线数据

#### Scenario: 缺数据
- **WHEN** `tsCode` 在 `index_daily` 中无数据
- **THEN** 返回空列表

---

### Requirement: 顶部板块轮动热度图（FR-5 / S5）

系统 SHALL 在页面顶部展示板块轮动热度图（原生 HTML grid），28 个行业按 7×4 网格排列，每格展示行业名/涨跌幅/成交额，颜色深浅表示涨跌幅（红涨绿跌），鼠标悬浮显示详情，点击联动排行表。

#### Scenario: 热度图渲染
- **WHEN** 页面加载完成，行业排行数据返回
- **THEN** 顶部渲染 28 个行业格子（7×4 网格）
- **AND** 每格包含行业名（顶部）、涨跌幅%（中间大字）、成交额（底部小字）
- **AND** 涨幅越大红色越深、跌幅越大绿色越深、0% 灰色

#### Scenario: 悬浮详情
- **WHEN** 鼠标悬浮某格
- **THEN** 显示该行业详情浮窗（行业名/涨跌幅/成交额/领涨股/领跌股）

#### Scenario: 点击联动
- **WHEN** 点击热度图某格
- **THEN** 下方排行表对应行高亮
- **AND** 成分股列表展开为该行业
- **AND** K 线图加载该行业指数

#### Scenario: 主题切换
- **WHEN** 切换 azure/mist/cyber 三主题
- **THEN** 热度图格子颜色同步变化（基于 CSS 变量 `--rise-color` / `--fall-color`）

---

### Requirement: 行业板块排行表（FR-2 / S6）

系统 SHALL 在页面主体区展示行业板块排行表，28 行，含行业名/今日涨跌幅/成交额/领涨股/领跌股，支持表头排序，点击行展开成分股并联动 K 线图。

#### Scenario: 排行表渲染
- **WHEN** 页面加载完成，行业排行数据返回
- **THEN** 排行表展示 28 行
- **AND** 默认按涨跌幅降序，第一行为领涨行业
- **AND** 领涨股/领跌股列展示股票名称+代码+涨跌幅，红涨绿跌

#### Scenario: 表头排序
- **WHEN** 点击表头「今日涨跌幅」或「成交额」
- **THEN** 切换升序/降序，列表重排

#### Scenario: 点击行业行
- **WHEN** 点击排行表某行
- **THEN** 该行高亮
- **AND** 成分股列表展开为该行业
- **AND** K 线图加载该行业的指数日线
- **AND** 默认选中第一行（涨跌幅最高行业）

---

### Requirement: 行业成分股展开列表（FR-3 / S7）

系统 SHALL 在点击行业排行表某行后，展开该行业的成分股列表，支持分页（20/50/100 每页）和关键字搜索（debounce 300ms），股票代码可点击跳转个股诊断。

#### Scenario: 展开成分股
- **WHEN** 点击排行表某行
- **THEN** 展开成分股列表区域
- **AND** 加载该行业成分股第一页（20 条）

#### Scenario: 分页
- **WHEN** 切换分页器页码或每页条数
- **THEN** 列表重新加载对应页数据
- **AND** 显示「共 X 条 · 第 Y/Z 页」

#### Scenario: 搜索
- **WHEN** 在搜索框输入股票代码或名称
- **THEN** 列表实时过滤（debounce 300ms）
- **AND** 不清空分页状态

#### Scenario: 跳转个股诊断
- **WHEN** 点击成分股股票代码链接
- **THEN** 跳转 `/page/stock-detail/{code}`
- **AND** 个股诊断（017）未上线时 toast 提示「个股诊断即将上线」

---

### Requirement: 行业板块 K 线图（FR-4 / S8）

系统 SHALL 在页面右侧展示当前选中行业的指数日线 K 线图，含蜡烛图主图 + MA5/10/20/60 叠加 + 成交量副图 + 十字光标，支持 MA 显隐切换和时间范围切换。

#### Scenario: 默认加载
- **WHEN** 页面加载完成
- **THEN** K 线图默认加载第一行（领涨行业）的指数日线
- **AND** 渲染蜡烛图 + MA5/10/20/60 + 成交量副图

#### Scenario: 切换行业
- **WHEN** 点击排行表其他行
- **THEN** K 线图重新加载该行业指数日线

#### Scenario: MA 显隐切换
- **WHEN** 点击顶部 MA5/MA10/MA20/MA60 按钮
- **THEN** 对应 MA 线显隐切换
- **AND** 不重新拉取数据（客户端重算）

#### Scenario: 时间范围切换
- **WHEN** 切换近 60/120/250 日/全部
- **THEN** K 线重新加载对应范围数据

#### Scenario: 十字光标
- **WHEN** 鼠标在 K 线图上移动
- **THEN** 显示十字光标 + OHLCV 浮窗

#### Scenario: 主题切换
- **WHEN** 切换 azure/mist/cyber 三主题
- **THEN** K 线图颜色同步变化，图表不破坏

#### Scenario: 缺数据降级
- **WHEN** 某行业指数在 `index_daily` 无数据
- **THEN** K 线图区域显示「暂无数据」占位，不报错

---

## REMOVED Requirements

### Requirement: 概念板块 Tab
**Reason**：`concept` / `concept_detail` 接口不在 tushare 接口汇总表中，不存在。
**Migration**：页面由原双 Tab 砍为单 Tab（仅行业板块），UI 上不显示 Tab 头，直接展示内容。

### Requirement: 行业板块资金流向柱状图
**Reason**：`moneyflow_dc` 接口不存在。
**Migration**：顶部全局区只保留板块轮动热度图，不引入资金流向图表。

### Requirement: 今日板块资金净流入 TOP 10
**Reason**：`moneyflow_dc` 接口不存在。
**Migration**：同上，资金流向数据归资金流向模块（016）独立排期。
