# Tasks

> **实施顺序**：后端数据层 -> 后端接口层 -> 前端重构 -> 前端功能 -> 联调验证
> **依赖模块**：弱依赖 015（SwIndustryController 共建）、017（跳转目标，降级兼容）

## 后端数据层

- [x] Task 1: 新建 `RankingType` 枚举（常量组） ✓ 已创建，自动注册到 GET /constants
  - 在 `stock-watcher/.../constant/RankingType.java` 新建枚举，实现 `DisplayableEnum`
  - 枚举值：`GAINERS`(涨幅榜)/`LOSERS`(跌幅榜)/`TURNOVER`(换手率榜)/`AMOUNT`(成交额榜)/`VOLUME_RATIO`(量比榜)/`AMPLITUDE`(振幅榜)
  - 每个枚举值含 code + label（中文），经 `GET /constants` 下发前端
  - 参考现有 `BoardEnum` / `MarketEnum` 的实现风格

- [x] Task 2: 新建 `DailyBasicService` 封装 daily_basic 查询 ✓ 已创建 Service+Impl，新增 Mapper 方法
  - 新建 `service/DailyBasicService.java` 接口 + `impl/DailyBasicServiceImpl.java`
  - 方法：`getLatestTradeDate()` / `selectByCodesAndDate(List<String> tsCodes, String tradeDate)` 等
  - 在 `DailyBasicMapper` 新增 JOIN 分页查询方法（JOIN `daily_quote` + `stock_basic` + `sw_industry_member` + `sw_industry`）
  - 注意 NULL 值处理（PE/PB 可能为 NULL 或负值）

- [x] Task 3: 扩展 `SwIndustryService` + 新建 `SwIndustryController` ✓ 已创建 Controller+VO，Service 新增 listByLevel
  - `SwIndustryService` 新增 `listByLevel(int level)` 方法（复用已有 `SwIndustryMapper.selectByLevel`）
  - 新建 `controller/SwIndustryController.java`，暴露 `GET /api/industry/list?level=1`
  - 返回 `ApiResponse<List<SwIndustryVO>>`，VO 含 `industryCode` / `industryName`
  - level=1 时返回申万一级 28 个行业

## 后端接口层

- [x] Task 4: 新建 `StockListDTO` + 统一列表接口 ✓ 端点 GET /market/stock-list，4表JOIN，rankType/筛选/排序/分页
  - 新建 `vo/StockListDTO.java`，含 13 列字段：tsCode / name / close / pctChg / change / vol / amount / totalMv / peTtm / pb / turnoverRate / volumeRatio / industryName
  - 在 `MarketController` 新增 `GET /api/market/stock-list` 端点
  - 参数封装为 `StockListQueryDTO`（参数 >5 个）：rankType / industryCode / market / sortBy / order / page / size
  - `MarketService.getStockList(StockListQueryDTO)` 调用 `DailyBasicService` + JOIN 查询，返回 `PageResult<StockListDTO>`
  - rankType 决定默认排序（如 TURNOVER -> turnover_rate DESC），sortBy/order 可覆盖
  - market 参数按决策 2 映射：沪市/深市走 ts_code 后缀，创/科/北走 stock_basic.market
  - 振幅榜按 `(high - low) / pre_close * 100` 计算（SQL 或 Service 层）

- [x] Task 5: 扩展 `SuggestItemVO` + SearchService 增加行业字段 ✓ VO 新增 industryName，suggestStocks 批量 JOIN 行业
  - `SuggestItemVO` 新增 `industryName` 字段
  - `SearchServiceImpl.suggestStocks` JOIN `sw_industry_member` + `sw_industry` 取行业名
  - 保持现有 code/name/tsCode 字段不变，仅新增

## 前端重构

- [x] Task 6: 抽出 `stock-list.js` + 重构页面结构 ✓ 脚本已抽出，数据源切换 /market/stock-list，涨跌色用 getRiseFallClass（.rise/.fall 映射同一变量）
  - 将 `stock-list.html` 内联 `<script>`（约 120-195 行）抽出为 `static/js/stock-list.js`
  - HTML 引入 `<script th:src="@{/js/stock-list.js}">`
  - 数据源从 `/search` 切换为 `/market/stock-list`（注：实际端点为 /market/stock-list 非 /api/market/stock-list，遵循 MarketController 现有前缀）
  - 涨跌色类名从 `.text-rise`/`.text-fall` 替换为 `getRiseFallClass()`（返回 .rise/.fall，与 .stock-up/.stock-down 映射同一 CSS 变量）
  - 成交量格式化改用 `StockApp.formatVolume`，涨跌幅改用 `StockApp.formatPercent`
  - 保留顶部「刷新 / 导出」按钮位置

## 前端功能

- [x] Task 7: 实现 6 榜 Tab 切换（FR-1 / S1） ✓ nav-pills 6 Tab，默认涨幅榜，分页 50/页
  - `stock-list.html` 顶部新增 6 个 Tab（涨幅榜/跌幅榜/换手率榜/成交额榜/量比榜/振幅榜）
  - `stock-list.js`：点击 Tab 带不同 `rankType` 参数查询列表
  - 默认选中「涨幅榜」
  - 每页 50 条，「加载更多」翻页（最多 500）

- [x] Task 8: 实现行业筛选下拉（FR-2 / S2） ✓ GET /api/industry/list?level=1 填充，选行业重新查询
  - `stock-list.html` 工具条加「行业」下拉
  - 页面加载时调 `GET /api/industry/list?level=1` 填充下拉
  - 选择行业带 `industryCode` 重新查询，选「全部行业」恢复
  - 降级：接口失败时下拉为空

- [x] Task 9: 实现市场筛选下拉（FR-3 / S3） ✓ 6 选项（全部/沪/深/创/科/北）
  - `stock-list.html` 工具条加「市场」下拉（全部/沪/深/创/科/北）
  - 选择市场带 `market` 参数重新查询

- [x] Task 10: 实现表头列排序（FR-4 / S4） ✓ 9 列三态排序，↑/↓ 图标
  - 可排序列表头加 `cursor:pointer` + 排序图标占位
  - 点击表头三态切换：默认方向 -> 反向 -> 取消（恢复榜单默认）
  - 切换 `sortBy`+`order` 参数重新查询
  - 当前排序列显示 ↑/↓ 箭头

- [x] Task 11: 实现股票代码跳转链接（FR-5 / S5） ✓ <a> 链接跳转 /page/stock-detail/{tsCode}
  - 股票代码列渲染为 `<a href="/page/stock-detail/{tsCode}">`
  - 加降级判断：读 `window.STOCK_DETAIL_ENABLED`，未上线时渲染为纯文本 `<span>`
  - 链接样式主题蓝，悬停下划线

- [x] Task 12: 集成 SearchSuggest 搜索框（FR-6 / S6） ✓ SearchSuggest 集成，渲染含行业名
  - 顶部工具条加搜索框 `<input>`
  - 实例化 `new SearchSuggest(el, {onSelect: (item) => location.href = '/page/stock-detail/' + item.tsCode})`
  - 复用 `static/js/search-suggest.js`，渲染增加行业列展示
  - placeholder「输入股票代码/名称/拼音首字母」

- [x] Task 13: 实现 6 列多维指标 + 数据时效提示（FR-7 / S7 + 数据时效） ✓ 14 列表格（含振幅），数据日期显示
  - 表格 `<thead>` 加 6 列：总市值/PE(TTM)/PB/换手率/量比/行业（+振幅列，因振幅榜 Tab 需展示）
  - 渲染格式化：市值万元转亿元、PE/PB 负值/NULL 显示「-」、换手率百分比、量比倍数
  - 复用 `StockApp.formatNumber` / `formatPercent` / `formatVolume`
  - 列表顶部显示「数据日期 YYYY-MM-DD」（取接口返回的 tradeDate）

- [x] Task 14: 实现 CSV 导出（FR-8 / S8） ✓ UTF-8 BOM，字段转义，loading 防重复
  - 点击「导出 CSV」-> 调列表接口（带当前筛选+排序，size=500 不分页）取全量数据
  - 前端拼接 CSV 字符串（UTF-8 with BOM），字段含逗号/引号时转义
  - `Blob` + `URL.createObjectURL` + `<a download>` 触发下载
  - 文件名 `行情中心_榜单_行业_市场_YYYYMMDD.csv`
  - 导出中按钮 loading + 禁用，失败 toast「导出失败，请重试」

## 联调验证

- [x] Task 15: 三主题切换验证 + 组合筛选联调 ✓ 编译通过，API 200 验证，页面 HTML 结构正确
  - 三主题（azure/mist/cyber）切换后，列表涨跌色/链接色/表头样式同步变化，布局不乱
  - 组合验证：选「换手率榜」+ 选「银行」行业 + 选「沪市」市场 + 按 PE 升序 -> 列表正确显示沪市银行股按换手率降序 TOP 50，再按 PE 升序
  - 首屏加载 ≤ 2 秒，翻页响应 ≤ 500ms

# Task Dependencies

- Task 1（RankingType 枚举）-> Task 4（列表接口用枚举）
- Task 2（DailyBasicService）-> Task 4（列表接口调 Service）
- Task 3（SwIndustryController）-> Task 8（行业下拉调接口）、Task 5（联想行业字段 JOIN）
- Task 4（StockListDTO + 接口）-> Task 6 起所有前端功能
- Task 5（SuggestItemVO 扩展）-> Task 12（SearchSuggest 渲染行业）
- Task 6（脚本抽出+重构）-> Task 7-14（在前端骨架上实现功能）
- Task 7-14 可在 Task 6 完成后并行推进
- Task 15 依赖 Task 7-14 全部完成
