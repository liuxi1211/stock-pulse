# 个股诊断模块 Spec

## Why

行情中心、资金流向等页面已经提供个股下钻入口，但 `/page/stock-detail/{code}` 尚未落地，用户无法在系统内完成从行情发现到技术面、基本面、资金面和风险面的完整诊断。本变更基于 `sdlc/prd/017-个股诊断/个股诊断PRD.md`，并按仓库当前实现校准过时的前置状态与接口契约。

## What Changes

- 新增不进入侧边栏的个股诊断详情页 `/page/stock-detail/{code}`，提供顶部摘要条和 K 线技术面、基本面、资金面、风险提示 4 个 Tab。
- 统一股票代码格式校验、日期范围、分页和错误响应，格式非法返回 HTTP 400，合法但无数据返回稳定空结果。
- 补齐 `stk_holdertrade`、`stk_holdernumber` 两条 Tushare ≤2000 积分数据链路及单股历史查询索引。
- 复用已落地的 `stock_moneyflow`、`hk_hold`、`top_list`、`top_inst`、`block_trade` 数据链路，扩展单股日期区间查询能力。
- 为已有基本面与风险数据 Service 补齐面向个股诊断的查询 Controller，并使用显式 DTO/VO，避免前端依赖数据库 DO。
- 扩展现有 K 线能力，支持 D/W/M 周期与 qfq/hfq/none 复权；无分钟数据源时明确禁用 60 分周期，不静默回退。
- 将 K 线渲染和浏览器端指标算法抽为可复用、实例隔离的前端模块，供仪表盘与个股诊断共享。
- 接入现有自选股添加、移除和价格提醒能力，不重复创建 PRD 中已过时的占位接口。
- 收口顶栏搜索、自选股和板块行情的个股诊断跳转；保留行情中心与资金流向已有入口。
- 所有图表复用 `ChartsTheme`，支持 Tab/卡片级独立加载、失败重试、空状态和主题切换。
- 不新增公司资料 F10、限售解禁、北向每日净买入、概念板块、跨股票对比或侧边栏菜单。

## Impact

- **Affected specs**:
  - `017-market-center-completion`：已有个股诊断链接从降级目标转为正式页面。
  - `018-watchlist-completion`：自选股列表和价格提醒能力被复用并补充详情页入口。
  - `019-sector-market`：板块成分股跳转目标改为个股诊断。
  - `020-moneyflow-module`：复用资金流向数据链路并扩展单股历史接口。
  - Dashboard K 线能力：从页面私有实现调整为共享组件，但保持现有展示行为。
- **Affected code**:
  - ``schema-mysql.sql`
  - `stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java`
  - `stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java`
  - `stock-watcher/src/main/java/com/arthur/stock/model/`、`dto/`、`vo/`、`mapper/`、`service/`、`controller/`、`task/`
  - `stock-watcher/src/main/java/com/arthur/stock/controller/PageController.java`
  - `stock-watcher/src/main/resources/templates/pages/stock-detail.html`
  - `stock-watcher/src/main/resources/static/js/stock-detail.js`、共享 K 线/指标模块、相关现有页面脚本
  - `stock-watcher/src/main/resources/static/css/stock-detail.css`
  - `stock-watcher/src/test/`
- **Compatibility**: 不删除已有资金流向、K 线、自选股接口；新增或扩展接口须保持现有调用可用。无对外破坏性变更。

## ADDED Requirements

### Requirement: 个股诊断路由与页面状态

系统 SHALL 提供 `/page/stock-detail/{code}` 下钻详情页，其中 `{code}` 为 `6 位数字.SH|SZ|BJ`，页面不出现在侧边栏。

#### Scenario: 合法代码访问
- **WHEN** 用户访问 `/page/stock-detail/000001.SZ`
- **THEN** 页面返回 200，注入股票代码，默认激活 `kline` Tab，并开始加载摘要条与 K 线

#### Scenario: 深链接指定 Tab
- **WHEN** 用户访问 `/page/stock-detail/000001.SZ?tab=moneyflow`
- **THEN** 页面激活资金面 Tab，仅初始化摘要条和资金面所需数据，并将有效 Tab 状态同步到 URL

#### Scenario: 无效 Tab
- **WHEN** `tab` 不是 `kline`、`fundamental`、`moneyflow`、`risk` 之一
- **THEN** 页面回退到 `kline`，且不执行未知初始化逻辑

#### Scenario: 不存在的股票
- **WHEN** 代码格式合法但股票基础信息不存在
- **THEN** 页面显示“未找到该股票”，停止加载各诊断 Tab，不将其误报为服务器异常

### Requirement: 统一查询契约与输入校验

系统 SHALL 对个股诊断新增及扩展接口采用稳定 DTO/VO、`ApiResponse<T>`、统一股票代码与查询参数校验。

#### Scenario: 非法股票代码
- **WHEN** API 收到 `INVALID`、空代码或不支持的交易所后缀
- **THEN** 返回 HTTP 400 和稳定错误信息，不返回成功空列表

#### Scenario: 合法代码无数据
- **WHEN** 参数合法但指定范围没有记录
- **THEN** 返回 HTTP 200 和类型稳定的空列表或空对象，并包含可用的数据截止日期元信息

#### Scenario: 非法范围或分页
- **WHEN** 开始日期晚于结束日期、日期格式非法、limit/page/size 超出边界
- **THEN** 返回 HTTP 400，且不执行数据库查询

### Requirement: 股东数据采集与查询

系统 SHALL 通过 Tushare `stk_holdertrade` 和 `stk_holdernumber` 接口采集股东增减持与股东人数数据，保存到 MySQL 双方言表，并提供单股历史查询。

#### Scenario: 定时或手动采集成功
- **WHEN** 采集任务获得有效响应
- **THEN** 系统按业务唯一键幂等写入数据，保留其他日期记录，并记录成功条数

#### Scenario: 单个接口采集失败
- **WHEN** 任一股东接口超时或返回错误
- **THEN** 记录错误且不破坏历史数据，不阻塞同批次其他采集任务

#### Scenario: 单股历史查询
- **WHEN** 用户查询股东人数或近一年股东增减持
- **THEN** 系统按 `ts_code` 与日期倒序返回，并通过匹配查询方向的联合索引完成查询

### Requirement: 个股 K 线数据与复权

系统 SHALL 提供单股 K 线查询，支持 `period=D|W|M|60MIN` 与 `adj=QFQ|HFQ|NONE`，默认 `D + QFQ`。

#### Scenario: 前复权
- **WHEN** 请求 `adj=QFQ`
- **THEN** OHLC 按 `raw_price × factor_t / factor_latest` 计算，最新交易日复权价等于不复权价

#### Scenario: 后复权
- **WHEN** 请求 `adj=HFQ`
- **THEN** OHLC 按冻结的基准因子口径计算，接口响应明确返回 adjustment 与数据截止日，成交量不参与价格复权

#### Scenario: 周月线聚合
- **WHEN** 请求周线或月线
- **THEN** 系统先按选定口径复权日线，再按周期边界聚合 open=首、high=最高、low=最低、close=末、volume=合计

#### Scenario: 60 分数据未接入
- **WHEN** 当前数据源没有 60 分 K 线
- **THEN** 接口返回明确“不支持该周期”的业务状态，页面禁用 60 分按钮并显示提示，不回退为日线

### Requirement: 可复用 K 线与指标组件

系统 SHALL 将 K 线渲染和技术指标计算从仪表盘页面级状态中解耦为可创建、更新、销毁的实例化组件，不在个股诊断复制另一套算法。

#### Scenario: 多实例隔离
- **WHEN** 仪表盘和个股诊断分别创建图表实例
- **THEN** 周期、指标、十字光标、画线和销毁状态互不污染

#### Scenario: 主图指标切换
- **WHEN** 用户切换 MA5/10/20/60、EMA12/26、BOLL 或 SAR
- **THEN** 页面使用已缓存 OHLCV 重新计算并更新叠加层，不重新请求 K 线

#### Scenario: 副图指标切换
- **WHEN** 用户选择 MACD、KDJ、RSI、WR、CCI、DMI 或无
- **THEN** 副图与主图时间轴、可见范围和十字光标联动，算法口径通过固定样本与 AKQuant 对齐

#### Scenario: 组件销毁
- **WHEN** 页面卸载或图表容器被替换
- **THEN** 组件释放图表、resize、主题和鼠标事件监听，避免重复注册和内存泄漏

### Requirement: K 线交互能力

系统 SHALL 在 K 线 Tab 提供复权切换、OHLCV 浮窗、MA 金叉死叉时间轴和基础画线工具。

#### Scenario: 十字光标联动
- **WHEN** 光标移动到某根 K 线
- **THEN** 页面展示对应日期、OHLC、成交量、相对前收涨跌幅及当前副图指标值

#### Scenario: 金叉死叉事件
- **WHEN** MA5/10/20/60 任意短周期在相邻两根 K 线间穿越长周期
- **THEN** 时间轴生成唯一事件点，并显示日期、均线组合和金叉/死叉说明

#### Scenario: 保存画线
- **WHEN** 用户创建趋势线、水平线或平行通道
- **THEN** 画线按 `股票代码 + 周期 + 复权口径` 隔离保存在本地；刷新后恢复，坏数据被安全忽略

### Requirement: 顶部摘要与自选股操作

系统 SHALL 在页面首屏展示股票名、代码、最新价、涨跌额、涨跌幅、行业、总市值、PE(TTM)、PB、换手率，并复用已有自选股和提醒能力。

#### Scenario: 摘要加载成功
- **WHEN** 股票基础信息、最新行情和最新每日指标可用
- **THEN** 摘要条按统一格式展示 10 项数据，负 PE/PB 显示 `--`，涨跌颜色使用主题变量

#### Scenario: 添加或移除自选
- **WHEN** 用户点击自选按钮并确认操作
- **THEN** 调用现有 Watchlist 能力更新状态；重复添加不会产生重复记录，失败时恢复按钮状态并提示

#### Scenario: 设置价格提醒
- **WHEN** 用户设置目标价格或涨跌幅阈值
- **THEN** 复用已有价格提醒接口完成保存并回显，不展示“即将上线”占位

### Requirement: 基本面 Tab

系统 SHALL 在首次进入基本面 Tab 时独立加载估值、财务指标、三大表摘要、业绩事件、分红和股东人数数据。

#### Scenario: 估值与财务趋势
- **WHEN** 近五年数据可用
- **THEN** 展示 PE/PB/PS 历史序列、当前值与按有效样本排名计算的历史百分位，以及 ROE/ROA/毛利率/净利率趋势

#### Scenario: 历史数据不足
- **WHEN** 股票上市不足五年或有效估值样本不足
- **THEN** 展示实际覆盖区间和“数据不足”说明，不使用 min-max 位置冒充统计百分位

#### Scenario: 财务摘要与事件
- **WHEN** 最近财报、预告、快报和分红数据可用
- **THEN** 三大表展示最近四期及可计算同比，事件按公告日排序；缺失同比显示 `--`

#### Scenario: 股东人数变化
- **WHEN** 至少两期股东人数可用
- **THEN** 展示人数折线和环比柱状图；零值或缺失前值不产生无穷大

### Requirement: 资金面 Tab

系统 SHALL 复用资金流向模块数据链路，展示近 30 日主力净流入、北向持股、近一年龙虎榜和近三个月大宗交易。

#### Scenario: 主力净流入
- **WHEN** 近 30 个有效交易日数据可用
- **THEN** 展示逐日净流入柱状图及累计金额、流入/流出天数汇总，tooltip 展示各档资金净额

#### Scenario: 北向持股无覆盖
- **WHEN** 指定股票没有 `hk_hold` 数据
- **THEN** 仅北向持股卡片显示“暂无北向持股数据”，其他资金卡片继续加载

#### Scenario: 龙虎榜展开
- **WHEN** 用户展开某次上榜记录
- **THEN** 按股票代码与该交易日懒加载席位明细，重复展开使用缓存

#### Scenario: 大宗交易溢价率
- **WHEN** 大宗交易与当日收盘价均可用
- **THEN** 后端返回或前端基于同日收盘价计算溢价率；缺收盘价时显示 `--`，不按零计算

### Requirement: 风险提示 Tab

系统 SHALL 在首次进入风险提示 Tab 时展示最近有效交易日涨跌停价、历史停牌、ST 名称变更和近一年股东增减持。

#### Scenario: 非交易日或停牌
- **WHEN** 今日没有涨跌停记录
- **THEN** 卡片展示最近有效交易日及“今日非交易日或停牌”提示，不伪造今日价格

#### Scenario: ST 历史筛选
- **WHEN** 名称或变更原因包含 ST、退市风险相关信息
- **THEN** 事件进入时间轴与表格；无匹配记录时显示“该股票无 ST 历史”

#### Scenario: 增减持汇总
- **WHEN** 近一年股东增减持记录可用
- **THEN** 表格区分增持和减持，并仅用减持记录计算减持笔数与累计减持数量

### Requirement: 懒加载、缓存与独立降级

系统 SHALL 让摘要条与当前 Tab 优先加载，其他 Tab 首次进入才加载；每个 Tab 和卡片独立维护 `idle/loading/loaded/error` 状态。

#### Scenario: 首屏请求
- **WHEN** 页面首次打开且未指定深链接 Tab
- **THEN** 仅请求股票校验、摘要条和 K 线数据，不请求基本面、资金面和风险面数据

#### Scenario: 快速切换条件
- **WHEN** 用户快速切换周期、复权或时间范围
- **THEN** 旧请求被取消或以请求版本隔离，不得覆盖最新选择的结果

#### Scenario: 单卡片失败
- **WHEN** 一个接口失败
- **THEN** 仅对应卡片显示错误与重试按钮，已成功卡片和其他 Tab 保持可用

#### Scenario: K 线缓存
- **WHEN** 用户返回已经请求过的 `code + period + adj + range`
- **THEN** 使用页面内缓存；切换股票或刷新数据后缓存正确失效

### Requirement: 主题与响应式

系统 SHALL 让 ECharts、Lightweight Charts、表格和涨跌色统一响应 azure/mist/cyber 主题，并在窄屏下保持可读。

#### Scenario: 切换主题
- **WHEN** 用户在任一 Tab 切换主题
- **THEN** 已创建图表使用 `ChartsTheme` 更新或安全重建，画线和当前可见范围不丢失

#### Scenario: 窄屏浏览
- **WHEN** 视口宽度小于 1200px
- **THEN** 摘要字段换行，Tab 与图表容器不横向溢出；复杂表格可在自身容器横向滚动

### Requirement: 下钻入口收口

系统 SHALL 将可识别完整 Tushare 股票代码的全局搜索、自选股和板块成分股入口指向个股诊断，并保持行情中心和资金流向已有入口可用。

#### Scenario: 全局搜索选中股票
- **WHEN** `SearchSuggest` 返回包含 `tsCode` 或兼容 `code` 的股票项并被选中
- **THEN** 跳转 `/page/stock-detail/{tsCode}`；字段缺失时不拼接 `undefined`

#### Scenario: 资金流向下钻
- **WHEN** 用户在资金流向页面点击股票
- **THEN** 跳转 `/page/stock-detail/{tsCode}?tab=moneyflow`

## MODIFIED Requirements

### Requirement: 现有 K 线接口兼容扩展

现有 `/kline/{stockCode}` 调用 SHALL 保持可用；个股诊断可通过兼容扩展后的现有端点或统一的新 `/api/stocks/{tsCode}/kline` 端点获得规范化数据，但实现只能保留一套 K 线业务逻辑与复权算法。

#### Scenario: 旧调用兼容
- **WHEN** 现有仪表盘按旧参数请求 K 线
- **THEN** 返回结构和默认展示不发生破坏性变化

#### Scenario: 新参数缓存隔离
- **WHEN** 相同股票以不同 period、adj 或日期范围查询
- **THEN** 缓存键完整包含这些维度，不会返回另一口径的数据

### Requirement: 现有资金流向接口支持单股历史

`HkHoldController`、`TopListController`、`BlockTradeController` SHALL 在不破坏资金流向页面现有端点的前提下补充单股日期区间能力。

#### Scenario: 页面并存
- **WHEN** 资金流向总览和个股诊断同时调用对应 Controller
- **THEN** 总览端点继续按交易日工作，个股端点按 tsCode 与范围工作，响应 DTO 字段定义一致

### Requirement: 现有自选股能力复用

个股诊断 SHALL 适配仓库中实际 WatchlistController 契约，而不是按旧 PRD 另建重复的 `/api/watchlist` 能力。

#### Scenario: 接口契约变化隔离
- **WHEN** 详情页读取或更新自选状态
- **THEN** 前端通过单一适配函数调用现有接口，UI 不直接散落拼接多个 Watchlist 路径

## REMOVED Requirements

### Requirement: 公司资料 F10 Tab

**Reason**: 当前可用 Tushare ≤2000 积分接口不提供 `stock_company` 所需能力。

**Migration**: 个股诊断固定为 4 Tab；未来数据源满足约束时另立变更，不预留空 Tab。

### Requirement: 限售解禁明细

**Reason**: `share_float` 需要 3000 积分，超出项目约束。

**Migration**: 风险提示使用股东增减持数据补充减持风险，但不宣称可替代未来解禁计划。

### Requirement: 北向每日净买入

**Reason**: 已落地的 `hk_hold` 只提供持股量和持股比例，不能推导可靠的每日净买入金额。

**Migration**: 仅展示北向持股比例与持股量趋势，并明确数据口径。

### Requirement: 价格提醒占位提示

**Reason**: 仓库已经具备价格提醒设置与清除能力，旧 PRD 的“即将上线”描述已过时。

**Migration**: 个股诊断直接复用现有提醒接口与交互。