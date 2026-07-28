# Checklist

## 规格与兼容性
- [ ] 实现以当前仓库接口为基线，未按旧 PRD 重复创建 Watchlist 或资金流向能力
- [ ] 现有仪表盘、资金流向、行情中心接口和页面行为保持兼容
- [ ] 个股诊断未加入侧边栏，且不存在公司资料 F10、限售解禁、北向净买入区块
- [ ] 前端不直接依赖数据库 DO，新增查询使用显式 DTO/VO 和稳定 `ApiResponse<T>`

## 输入校验与错误契约
- [ ] 所有个股诊断 API 统一接受 `6位数字.SH|SZ|BJ`，非法代码返回 HTTP 400
- [ ] 日期格式、开始结束顺序、limit/page/size 边界均有校验与自动化测试
- [ ] 合法参数无数据返回 HTTP 200 和类型稳定空结果，包含可用的数据截止日期
- [ ] 合法但不存在的股票显示“未找到该股票”，不会继续加载各 Tab

## 数据采集与数据库
- [ ] MySQL 均定义 `stk_holdertrade`、`stk_holdernumber` 表、业务唯一键和查询方向一致的联合索引
- [ ] 两张表的 TushareApiEnum、DTO、DO、Mapper/XML、Service、TushareClient 与采集任务完整
- [ ] 股东数据保存幂等，单接口失败不破坏历史数据、不阻塞其他采集
- [ ] hk_hold、top_list、top_inst、block_trade 存在以 `ts_code` 为首列的单股历史查询索引
- [ ] Mapper 查询按日期稳定排序，分页结果与总数一致

## 后端查询接口
- [ ] DailyBasic 支持单股近五年历史查询并返回 PE TTM、PB、PS TTM
- [ ] FinaIndicator、Income、Balancesheet、Cashflow 支持单股期限/期数查询
- [ ] Forecast、Express、Dividend 提供稳定的单股事件查询，Dividend 旧路径保持兼容
- [ ] StockStkLimit、StockSuspendD、StockNamechange 支持单股风险查询
- [ ] Moneyflow 单股近 N 交易日字段与金额单位明确
- [ ] HkHold 支持单股 3 月、1 年和全部范围
- [ ] TopList/TopInst 支持单股近一年列表与指定交易日席位明细
- [ ] BlockTrade 支持单股近三个月分页查询；缺同日收盘价时溢价率为空
- [ ] 资金流向总览现有接口仍可正常工作

## K 线与技术指标
- [ ] K 线支持 D/W/M 与 QFQ/HFQ/NONE，默认 D+QFQ
- [ ] QFQ 使用 `raw × factor_t / factor_latest`，最新一日价格等于不复权价
- [ ] HFQ 基准口径已在接口与测试中固定，不随请求样本窗口漂移
- [ ] 周/月线先复权后聚合，OHLCV 聚合规则正确
- [ ] 无 60 分数据源时接口明确返回不支持，页面按钮禁用且不回退日线
- [ ] K 线缓存键包含 code、period、adj、startDate、endDate，刷新数据后正确失效
- [ ] 共享 K 线组件支持 create/setData/setIndicators/resize/dispose，多实例互不污染
- [ ] 仪表盘与个股诊断复用同一指标算法，没有复制两套实现
- [ ] MA、EMA、BOLL、SAR、MACD、KDJ、RSI、WR、CCI、DMI 固定样本结果与 AKQuant 对齐
- [ ] 组件销毁后 resize、主题、鼠标事件监听均被释放

## 路由、页面骨架与摘要
- [ ] `/page/stock-detail/000001.SZ` 返回 200，默认激活 K 线技术面
- [ ] `?tab=kline|fundamental|moneyflow|risk` 深链接正确，非法 tab 回退 kline
- [ ] 页面包含固定摘要条、4 Tab、分区级 loading/empty/error/retry 状态
- [ ] Tab 状态按 idle/loading/loaded/error 管理，Tab 2/3/4 首次进入才请求
- [ ] 摘要展示股票名、代码、最新价、涨跌额、涨跌幅、行业、市值、PE、PB、换手率共 10 项
- [ ] 负 PE/PB 和缺失字段显示 `--`，涨跌色使用主题变量
- [ ] 添加/移除自选复用现有接口，重复点击被保护，失败后 UI 状态回滚
- [ ] 价格提醒复用现有设置/清除接口，不显示“即将上线”占位

## K 线技术面 Tab
- [ ] 默认加载近 250 根日线前复权数据，已请求组合可复用页面缓存
- [ ] D/W/M 切换可用，60 分按后端能力正确启用或禁用
- [ ] MA5/10/20/60、EMA12/26、BOLL、SAR 可独立显隐且不重新请求数据
- [ ] MACD/KDJ/RSI/WR/CCI/DMI/无 可单选切换，主副图 X 轴和十字光标联动
- [ ] OHLCV 浮窗日期、价格、成交量、相对前收涨跌幅及指标值正确
- [ ] MA5/10/20/60 金叉死叉事件唯一、方向正确、说明完整
- [ ] 趋势线、水平线、平行通道可用并按 code+period+adj 持久化
- [ ] 快速切换周期/复权时旧响应不会覆盖新结果
- [ ] 主题切换后指标、画线和可见范围保持

## 基本面 Tab
- [ ] PE/PB/PS 展示近五年有效样本与按排名计算的当前历史百分位
- [ ] 上市不足五年或样本不足时展示实际覆盖区间和数据不足说明
- [ ] ROE/ROA/毛利率/净利率趋势日期和百分比单位正确
- [ ] 三大表展示最近四期；同比缺失或除零时显示 `--`
- [ ] 预告/快报按公告日展示，分红送转按公告日倒序
- [ ] 股东人数折线和环比柱状图正确，缺失前值/零值不产生 NaN/Infinity 文本
- [ ] 任一卡片失败仅影响自身并可独立重试

## 资金面 Tab
- [ ] 近 30 个交易日主力净流入柱状图、累计金额和流入/流出天数正确
- [ ] 北向持股量/比例曲线支持 3 月、1 年、全部，比例保留合理精度
- [ ] 无北向覆盖时仅对应卡片显示空态
- [ ] 龙虎榜近一年汇总、分页明细和按交易日懒加载席位展开正确
- [ ] 大宗交易近三个月汇总、分页、溢价率和缺收盘价处理正确
- [ ] `?tab=moneyflow` 首屏不会请求其他未激活 Tab

## 风险提示 Tab
- [ ] 涨停价、现价、跌停价使用最近有效交易日，并明确非交易日/停牌状态
- [ ] 历史停牌次数和天数计算正确，未复牌记录处理明确
- [ ] ST 时间轴仅包含 ST/退市风险相关名称变更
- [ ] 从未 ST 的股票显示“该股票无 ST 历史”
- [ ] 股东增减持分页正确，汇总只统计近一年减持记录
- [ ] 风险 Tab 不包含限售解禁区块

## 主题、性能与入口
- [ ] 所有 ECharts/Lightweight Charts 使用 ChartsTheme 或主题变量，无业务硬编码涨跌色
- [ ] azure/mist/cyber 切换后所有已创建图表正确更新
- [ ] <1200px 摘要换行、图表不溢出、复杂表格容器可横向滚动
- [ ] 快速切换使用取消或请求版本隔离，页面卸载释放图表与监听器
- [ ] 顶栏搜索、自选股、板块成分股、行情中心均跳转 `/page/stock-detail/{tsCode}`
- [ ] 资金流向入口跳转 `/page/stock-detail/{tsCode}?tab=moneyflow`
- [ ] SearchSuggest 兼容 tsCode/code 且不会生成含 `undefined` 的 URL

## 自动化与验收
- [ ] 新增 Controller、Service、复权/聚合、指标算法测试全部通过
- [ ] 新增和修改的 JavaScript 文件通过 `node --check`
- [ ] `node stock-watcher/run.js test` 执行通过
- [ ] `node stock-watcher/run.js package-all` 执行通过
- [ ] 浏览器验证 4 Tab 懒加载、深链接、空态、分区重试、快速切换、主题切换和窄屏布局
- [ ] 随机 5 只股票的 QFQ/HFQ/NONE 校验符合冻结口径
- [ ] 完整下钻、自选股和价格提醒使用路径通过验收