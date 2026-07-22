# 行情中心功能补齐（S1-S8）Spec

> **来源 PRD**：`sdlc/prd/013-行情中心/行情中心PRD.md`
> **参考**：`CLAUDE.md` 硬约束、`.trae/documents/主要功能模块整体设计计划.md` §2.2
> **页面路由**：`/page/stock-list`（侧边栏「主要功能」第 2 项）

## Why

行情中心是项目「全市场列表入口」，定位为老股民浏览 A 股全市场行情的第一站。当前页面骨架已存在但功能单薄：**无任何榜单 Tab**（PRD 误记为已有 3 榜，实际为单一搜索+表格结构）、无行业/市场筛选、表头不可排序、股票代码不可点击、无多维指标列（PE/PB/换手率等）、CSV 导出仅 toast 占位。需补齐 8 项能力（S1-S8），形成「榜单扫描 → 多维筛选 → 指标精选 → 下钻诊断 → 导出离线」的完整使用闭环。

## What Changes

### 前端
- **BREAKING**：`stock-list.html` 页面数据源从 `/search` 切换为新的统一列表接口 `/api/market/stock-list`（`/search` 仅保留给顶部搜索联想）
- 新增顶部 6 榜 Tab 切换（涨幅/跌幅/换手率/成交额/量比/振幅），Tab 本质是「预设排序」
- 新增「行业筛选」下拉（申万一级 28 行业）、「市场筛选」下拉（全部/沪/深/创/科/北）
- 表头 9 列支持点击排序（涨跌幅/成交量/成交额/换手率/市值/PE/PB/量比/振幅），三态切换（默认向→反向→取消）
- 表格新增 6 列多维指标（总市值/PE_TTM/PB/换手率/量比/行业），完整表头 13 列
- 股票代码列改为可点击链接，跳转 `/page/stock-detail/{code}`（017 未上线时降级为纯文本）
- 顶部集成 `SearchSuggest` 组件，选中跳转个股诊断
- CSV 导出从 toast 占位改为真实前端生成（UTF-8 BOM，≤500 条）
- **抽出独立 `static/js/stock-list.js`**（当前脚本内联在 HTML）
- 涨跌色统一用 `.stock-up`/`.stock-down`（当前 stock-list 用 `.text-rise`/`.text-fall`，需统一）
- 列表顶部显示「数据日期 YYYY-MM-DD」时效提示

### 后端
- **新建统一列表接口 `GET /api/market/stock-list`**：支持 `rankType`/`industryCode`/`market`/`sortBy`/`order`/`page`/`size`，JOIN `daily_quote`+`daily_basic`+`sw_industry_member`+`sw_industry`+`stock_basic`，返回 `PageResult<StockListDTO>`（13 列）。**不扩展现有 `/market/ranking`**（该接口服务于仪表盘 top-N 聚合，语义不同）
- **新建 `DailyBasicService`**（当前缺失，仅有 DO/Mapper/DTO）：封装 daily_basic 查询，供 MarketService 调用
- **新建 `SwIndustryController`** + `SwIndustryService.listByLevel(level)` 方法：暴露 `GET /api/industry/list?level=1` 返回申万一级行业列表（`SwIndustryMapper.selectByLevel` SQL 已存在）
- **扩展 `SuggestItemVO`** 增加 `industryName` 字段 + `SearchServiceImpl.suggestStocks` JOIN 行业表
- **新建 `RankingType` 枚举**（常量组，DisplayableEnum）：gainers/losers/turnover/amount/volume_ratio/amplitude，经 `GET /constants` 下发

### 不做（明确排除）
- 概念板块筛选（tushare 无接口）、盘中实时刷新、分钟级 K 线内嵌、财务因子筛选、跨市场对比、加自选按钮（归个股诊断）、行情数据反向写入

## Impact

- **Affected specs**：无上游 spec 依赖；下游弱依赖 015-板块行情（`SwIndustryController` 共建）、017-个股诊断（跳转目标）
- **Affected code（前端）**：
  - `stock-watcher/src/main/resources/templates/pages/stock-list.html`（重写表格结构+工具条，抽出脚本）
  - `stock-watcher/src/main/resources/static/js/stock-list.js`（**新建**，从内联抽出）
  - `stock-watcher/src/main/resources/static/js/search-suggest.js`（渲染增加行业列）
  - `stock-watcher/src/main/resources/static/css/components.css`（涨跌色类已存在，复用）
- **Affected code（后端）**：
  - `stock-watcher/.../controller/MarketController.java`（新增 `/api/market/stock-list` 端点）
  - `stock-watcher/.../service/MarketService.java` + `impl/MarketServiceImpl.java`（新增 `getStockList`）
  - `stock-watcher/.../service/DailyBasicService.java` + `impl/DailyBasicServiceImpl.java`（**新建**）
  - `stock-watcher/.../controller/SwIndustryController.java`（**新建**）
  - `stock-watcher/.../service/SwIndustryService.java`（新增 `listByLevel`）
  - `stock-watcher/.../mapper/DailyBasicMapper.java`（新增 JOIN 分页查询）
  - `stock-watcher/.../vo/StockListDTO.java`（**新建**，13 列）
  - `stock-watcher/.../vo/SuggestItemVO.java`（加 `industryName`）
  - `stock-watcher/.../service/impl/SearchServiceImpl.java`（suggest JOIN 行业）
  - `stock-watcher/.../constant/RankingType.java`（**新建**枚举）
  - `stock-watcher/src/main/resources/schema.sql`（确认索引：`daily_basic(ts_code,trade_date)`、`sw_industry_member(con_code)`）

## 现状校正（PRD 与实际代码的差异）

> PRD 对现状的描述有若干误记，本 spec 按实际代码为准：

| PRD 描述 | 实际代码 | spec 处理 |
|---|---|---|
| 「顶部仅有涨幅榜/跌幅榜/成交额榜 3 个 Tab」 | **无任何 Tab**，页面是单一搜索+表格，数据走 `/search` 接口 | 新建 6 榜 Tab，数据源切换为 `/api/market/stock-list` |
| 「复用 `/market/ranking` 接口，扩展 type 参数」 | `/market/ranking` 无 `type` 参数，一次返回 4 榜聚合 `MarketRankingVO`，`RANK_LIMIT=10`，服务仪表盘 | 不改 `/market/ranking`；新建 `/api/market/stock-list` 统一接口 |
| 「daily_basic 表已有 Service」 | **无 Service、无 Controller**，仅 DO/Mapper/DTO | 新建 `DailyBasicService` |
| 「SwIndustryController 暴露 /api/industry/list」 | **Controller 不存在**；但 `SwIndustryMapper.selectByLevel` SQL 已存在，Service 有数据拉取无列表查询 | 新建 Controller + Service.listByLevel |
| 「涨跌色用 .stock-up/.stock-down」 | stock-list.html 实际用 `.text-rise`/`.text-fall`（components.css 中两套类并存，映射同一变量） | 统一为 `.stock-up`/`.stock-down` |

## 架构决策

### 决策 1：统一列表接口 vs 扩展 /market/ranking

PRD 提到「扩展 `/market/ranking` 或新建 `/market/stock-list`」。本 spec 选择**新建 `GET /api/market/stock-list`**，理由：
- `/market/ranking` 返回 4 榜聚合对象（`MarketRankingVO`），服务仪表盘 top-N 小卡片（limit 10），语义是「多榜一览」
- 行情中心需要的是「单榜 top-50 + 全市场筛选 + 排序 + 分页」的列表视图，语义不同
- 榜单 Tab 本质是「预设排序」：点「换手率榜」= `rankType=turnover`（默认按换手率降序），用户可再叠加行业/市场筛选与自定义排序

### 决策 2：市场筛选的混合语义

PRD 的「沪/深/创/科/北 5 大市场」混合了两个枚举概念，后端过滤逻辑：
- **沪市** → `ts_code LIKE '%.SH'`（含科创板，因科创板在沪市）
- **深市** → `ts_code LIKE '%.SZ'`（含创业板，因创业板在深市）
- **创业板** → `stock_basic.market = '创业板'`（BoardEnum.GEM）
- **科创板** → `stock_basic.market = '科创板'`（BoardEnum.STAR）
- **北交所** → `stock_basic.market = '北交所'`（BoardEnum.BSE）

`market` 参数后端用字符串接收，Service 层按值映射到 `ts_code` 后缀过滤或 `stock_basic.market` 字段过滤。

### 决策 3：是否新建 DailyBasicController

PRD 要求新建 `DailyBasicController`。本 spec **不新建该 Controller**，理由：
- 行情中心的 PE/PB 等指标通过统一接口 `/api/market/stock-list` 的 JOIN 查询返回，不需要独立 `/api/daily-basic` 端点
- `DailyBasicService` 作为内部 Service 封装 daily_basic 查询，供 `MarketService` 调用即可
- 其他模块（自选股 W9、仪表盘）需要 daily_basic 时再各自建 Controller，避免为未到来的需求过度设计

---

## ADDED Requirements

### Requirement: 6 榜 Tab 切换（FR-1 / S1）

行情中心顶部 SHALL 提供 6 个榜单 Tab：涨幅榜 / 跌幅榜 / 换手率榜 / 成交额榜 / 量比榜 / 振幅榜。每个 Tab 默认展示 TOP 50，支持翻页加载更多（每页 50，最多 500）。

#### Scenario: 切换榜单 Tab
- **WHEN** 用户点击「换手率榜」Tab
- **THEN** 列表按 `turnover_rate` 降序取 TOP 50，调用 `GET /api/market/stock-list?rankType=turnover&size=50`
- **AND** 表格数据刷新，第一行换手率最高

#### Scenario: 量比榜与振幅榜
- **WHEN** 用户点击「量比榜」Tab
- **THEN** 列表按 `volume_ratio` 降序取 TOP 50（数据来自 `daily_basic` JOIN `daily_quote`）
- **WHEN** 用户点击「振幅榜」Tab
- **THEN** 列表按 `(high - low) / pre_close * 100` 降序取 TOP 50（数据来自 `daily_quote`）

#### Scenario: 榜单数据降级
- **WHEN** `daily_basic` 表无数据
- **THEN** 换手率榜/量比榜 Tab 显示「暂无数据」占位
- **AND** 涨幅榜/跌幅榜/成交额榜/振幅榜正常展示（仅依赖 `daily_quote`）

#### Scenario: 翻页
- **WHEN** 用户点击「加载更多」或翻页器
- **THEN** 调用 `GET /api/market/stock-list?rankType=...&page=2&size=50`，追加下 50 条
- **AND** 翻页响应时间 ≤ 500ms

### Requirement: 行业筛选下拉（FR-2 / S2）

行情中心顶部工具条 SHALL 提供「行业筛选」下拉，选项为申万一级行业（28 个）+「全部行业」（默认）。

#### Scenario: 拉取行业列表
- **WHEN** 页面加载
- **THEN** 调用 `GET /api/industry/list?level=1`，下拉填充 28 个行业中文名 +「全部行业」
- **AND** 选项只显示中文名，不显示行业代码

#### Scenario: 按行业过滤
- **WHEN** 用户选择「银行」行业
- **THEN** 列表带 `industryCode=801780`（银行行业代码）重新查询，仅显示银行股
- **AND** 当前榜单 Tab + 排序条件保持不变

#### Scenario: 行业降级
- **WHEN** 板块行情模块（015）未上线 / `sw_industry` 表无数据
- **THEN** 行业筛选下拉为空或显示「暂无行业数据」，不阻塞其他筛选

### Requirement: 市场筛选下拉（FR-3 / S3）

行情中心顶部工具条 SHALL 提供「市场筛选」下拉：全部市场（默认）/ 沪市 / 深市 / 创业板 / 科创板 / 北交所。

#### Scenario: 按市场过滤
- **WHEN** 用户选择「创业板」
- **THEN** 列表带 `market=创业板` 重新查询，JOIN `stock_basic` 按 `market` 字段过滤，仅显示创业板股票
- **WHEN** 用户选择「沪市」
- **THEN** 列表按 `ts_code LIKE '%.SH'` 过滤，仅显示沪市股票（含科创板）

#### Scenario: 与行业筛选组合
- **WHEN** 用户选「银行」行业 + 选「沪市」市场
- **THEN** 列表显示沪市银行股，榜单 Tab 排序仍生效

### Requirement: 表头列排序（FR-4 / S4）

列表表头 SHALL 支持 9 列点击排序：涨跌幅 / 成交量 / 成交额 / 换手率 / 市值 / PE / PB / 量比 / 振幅。三态切换：默认方向 → 反向 → 取消排序（恢复榜单默认）。

#### Scenario: 点击 PE 排序
- **WHEN** 用户第一次点击「PE」表头
- **THEN** 列表按 `pe_ttm` 升序（低估值优先），调用 `?sortBy=pe_ttm&order=asc`
- **AND** PE 为 NULL 的股票排最后
- **WHEN** 用户第二次点击「PE」表头
- **THEN** 列表按 `pe_ttm` 降序
- **WHEN** 用户第三次点击「PE」表头
- **THEN** 取消排序，恢复榜单默认排序（如当前是涨幅榜则按 `pct_chg` 降序）

#### Scenario: 排序箭头显示
- **THEN** 当前排序列表头显示 ↑（升序）或 ↓（降序），非排序列不显示箭头
- **AND** 代码列、名称列、行业列不可排序（字符串列无排序图标）

#### Scenario: 排序与筛选组合
- **WHEN** 用户选「银行」行业 + 按 PE 升序
- **THEN** 列表显示银行股按 PE 升序排列

### Requirement: 股票代码跳转个股诊断（FR-5 / S5）

列表「股票代码」列 SHALL 渲染为可点击链接，跳转 `/page/stock-detail/{ts_code}`。

#### Scenario: 点击跳转
- **WHEN** 用户点击列表中 `000001.SZ`
- **THEN** 跳转 `/page/stock-detail/000001.SZ`，不弹窗、不二次确认
- **AND** 链接样式为主题蓝，悬停下划线

#### Scenario: 017 未上线降级
- **WHEN** 个股诊断模块（017）未上线
- **THEN** 股票代码显示为纯文本 `<span>`（不可点击），不报错
- **AND** 017 上线后自动变为可点击链接（通过全局配置 `window.STOCK_DETAIL_ENABLED` 判断）

### Requirement: 集成 SearchSuggest 搜索框（FR-6 / S6）

行情中心顶部 SHALL 集成 `SearchSuggest` 组件，输入代码/名称/拼音首字母联想，选中跳转个股诊断。

#### Scenario: 输入联想
- **WHEN** 用户输入「平安」（≥1 字符，debounce 300ms）
- **THEN** 下拉显示 TOP 10 联想项，每项显示「代码 + 名称 + 所属行业」
- **WHEN** 用户输入「payh」
- **THEN** 下拉显示「平安银行 000001.SZ 银行」

#### Scenario: 选中跳转
- **WHEN** 用户点击联想项
- **THEN** 跳转 `/page/stock-detail/{ts_code}`（与 FR-5 跳转目标一致）

#### Scenario: 空结果
- **WHEN** 输入无匹配字符
- **THEN** 下拉显示「无匹配股票」

### Requirement: 表格增加 6 列多维指标（FR-7 / S7）

列表 SHALL 在现有基础行情列上增加 6 列：总市值 / PE(TTM) / PB / 换手率 / 量比 / 行业。完整表头 13 列。

#### Scenario: 新增列展示
- **THEN** 列表表头顺序：代码 / 名称 / 最新价 / 涨跌幅 / 涨跌额 / 成交量 / 成交额 / 总市值 / PE(TTM) / PB / 换手率 / 量比 / 行业

#### Scenario: 指标格式化
- **WHEN** 渲染总市值 `total_mv`（万元）
- **THEN** 显示为「亿元」（÷10000，保留 2 位小数，如 `3456.78 亿`）
- **WHEN** PE/PB 为负值或 NULL
- **THEN** 显示「-」
- **WHEN** 换手率
- **THEN** 显示百分比（如 `3.45%`），复用 `StockApp.formatPercent`
- **WHEN** 量比
- **THEN** 显示倍数（如 `1.23`）

#### Scenario: 数据降级
- **WHEN** `daily_basic` 表无数据
- **THEN** 总市值/PE/PB/换手率/量比 5 列显示「-」，基础行情列（代码/名称/价/涨跌幅/量/额）正常展示

### Requirement: CSV 导出（FR-8 / S8）

「导出 CSV」按钮 SHALL 导出当前筛选+排序条件下的全部数据（≤500 条），前端生成 CSV 文件。

#### Scenario: 导出成功
- **WHEN** 用户点击「导出 CSV」
- **THEN** 调用列表接口（带当前筛选+排序，`size=500` 不分页）取数据
- **AND** 前端拼接 CSV 字符串（UTF-8 with BOM），用 `Blob`+`<a download>` 触发下载
- **AND** 文件名 `行情中心_榜单_行业_市场_YYYYMMDD.csv`（如 `行情中心_换手率榜_银行_沪市_20240115.csv`）

#### Scenario: loading 与防重复
- **THEN** 导出过程中按钮显示 loading，禁止重复点击
- **WHEN** 导出失败
- **THEN** toast 提示「导出失败，请重试」，不阻塞页面其他功能

#### Scenario: 字段转义
- **WHEN** CSV 字段含逗号或引号（如股票名称含特殊字符）
- **THEN** 字段用双引号包裹，内部双引号转义为 `""`

### Requirement: 数据时效提示

列表顶部 SHALL 显示「数据日期 YYYY-MM-DD」，让用户清楚数据是何日的。

#### Scenario: 显示数据日期
- **THEN** 列表顶部显示「数据日期 2024-01-15」
- **WHEN** 盘中访问
- **THEN** 显示「截至 2024-01-15 收盘」（数据为前一日收盘数据）

## MODIFIED Requirements

### Requirement: 前端脚本结构

行情中心页面脚本 SHALL 从内联 `<script>` 抽出为独立 `static/js/stock-list.js`，与 `watchlist.js`（自选股 W10 重构）保持一致的脚本组织方式。

### Requirement: 涨跌色样式统一

行情中心列表所有涨跌色 SHALL 统一使用 `.stock-up`（红）/ `.stock-down`（绿）类名，与仪表盘（dashboard）保持一致。当前 stock-list 使用的 `.text-rise`/`.text-fall` 需替换。

## REMOVED Requirements

### Requirement: 现有 /search 列表数据源

**Reason**：当前 stock-list.html 通过 `GET /search?keyword=...&page=...&size=...` 获取表格数据，该接口设计用于搜索联想分页，不支持榜单排序/行业筛选/市场筛选/多维指标列。
**Migration**：表格数据源切换为 `GET /api/market/stock-list`；`/search` 仅保留给顶部 `SearchSuggest` 联想组件使用。
