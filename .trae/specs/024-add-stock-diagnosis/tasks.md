# Tasks

> 实施原则：先冻结契约并补齐阻塞数据，再交付页面骨架；各 Tab 在骨架完成后按依赖并行开发。不得复制 `dashboard.js` 形成第二套 K 线或指标算法。

## 阶段一：接口契约与数据前置

- [x] Task 1: 冻结个股诊断查询契约与公共校验
  - [x] SubTask 1.1: 盘点并确定 K 线、摘要、基本面、资金面、风险面实际端点，保留现有调用兼容，新增接口统一返回显式 DTO/VO
  - [x] SubTask 1.2: 新增或复用统一股票代码校验器，支持 `^\d{6}\.(SH|SZ|BJ)$`，非法格式映射 HTTP 400
  - [x] SubTask 1.3: 统一日期范围、limit、page、size 校验和空数据响应，避免非法参数被转换为成功空列表
  - [x] SubTask 1.4: 为个股诊断响应补充 `symbol`、`dataAsOf`、周期/复权等必要元信息
  - [x] SubTask 1.5: 增加 Controller 参数校验测试和错误响应测试

- [x] Task 2: 落地 `stk_holdertrade` 数据链路
  - [x] SubTask 2.1: 在 MySQL schema 新增表、业务唯一键及 `(ts_code, ann_date)` 查询索引
  - [x] SubTask 2.2: 新增 Tushare 枚举、DTO、DO、Mapper/XML、Service/Impl 和 TushareClient 方法
  - [x] SubTask 2.3: 接入现有数据采集任务或独立错峰任务，支持幂等保存和单接口失败隔离
  - [x] SubTask 2.4: 暴露按股票代码和日期范围查询的 Controller，默认近一年
  - [x] SubTask 2.5: 增加映射、幂等写入、日期排序与参数校验测试

- [x] Task 3: 落地 `stk_holdernumber` 数据链路
  - [x] SubTask 3.1: 在 MySQL schema 新增表、业务唯一键及 `(ts_code, end_date)` 查询索引
  - [x] SubTask 3.2: 新增 Tushare 枚举、DTO、DO、Mapper/XML、Service/Impl 和 TushareClient 方法
  - [x] SubTask 3.3: 接入采集任务并保证接口失败不破坏历史数据
  - [x] SubTask 3.4: 暴露按股票代码查询最近 N 期的 Controller，限制 limit 合法范围
  - [x] SubTask 3.5: 增加映射、幂等写入、日期排序与参数校验测试

- [x] Task 4: 补齐基本面与风险面查询接口
  - [x] SubTask 4.1: 扩展 DailyBasic 历史范围查询并提供 PE/PB/PS 数据截止日期
  - [x] SubTask 4.2: 为 FinaIndicator、Income、Balancesheet、Cashflow 提供单股期限/期数查询 DTO/VO
  - [x] SubTask 4.3: 为 Forecast、Express、Dividend 提供稳定的单股事件查询契约，兼容已有 Dividend 路径
  - [x] SubTask 4.4: 为 StockStkLimit、StockSuspendD、StockNamechange 提供单股风险查询契约
  - [x] SubTask 4.5: 为各接口补正常、空数据、非法代码、非法日期/limit 测试

- [x] Task 5: 扩展资金面单股历史接口与索引
  - [x] SubTask 5.1: 为 hk_hold 补 `(ts_code, trade_date)` 索引并支持 3 月/1 年/全部范围
  - [x] SubTask 5.2: 为 top_list、top_inst 补按 `ts_code + trade_date` 查询方向的索引和近一年列表/席位查询
  - [x] SubTask 5.3: 为 block_trade 补 `(ts_code, trade_date)` 索引和近三个月分页查询
  - [x] SubTask 5.4: 复用 MoneyflowController 单股近 N 交易日能力，并冻结金额单位和各档净额字段
  - [x] SubTask 5.5: 计算大宗交易溢价率时关联同日收盘价，缺价格返回空值
  - [x] SubTask 5.6: 保证资金流向总览现有端点兼容，并增加双页面调用测试

## 阶段二：K 线基础能力

- [x] Task 6: 校准 K 线周期、复权与缓存
  - [x] SubTask 6.1: 在单一 K 线 Service 中实现 `NONE`、`QFQ`、`HFQ` 口径，明确后复权基准并固定响应元信息
  - [x] SubTask 6.2: 按"先复权日线、再聚合"实现 D/W/M OHLCV
  - [x] SubTask 6.3: 无分钟数据源时返回明确不支持状态，禁止 60 分静默回退
  - [x] SubTask 6.4: 缓存键纳入 code、period、adj、startDate、endDate，数据刷新后正确失效
  - [x] SubTask 6.5: 增加复权公式、最新日 QFQ 对齐、D/W/M 聚合、无因子、空数据和缓存隔离单元测试

- [ ] Task 7: 抽取共享 K 线与指标模块
  - [ ] SubTask 7.1: 从 dashboard 页面状态中抽出实例化 K 线组件，定义 create/setData/setIndicators/resize/dispose 生命周期
  - [ ] SubTask 7.2: 抽取 EMA、MACD、KDJ 等现有算法并补 MA、BOLL、SAR、RSI、WR、CCI、DMI，仪表盘和详情页共用同一实现
  - [ ] SubTask 7.3: 使用固定 OHLCV 样本与 AKQuant 输出建立指标 golden test，覆盖预热 NaN 与 KDJ/DMI 组合口径
  - [ ] SubTask 7.4: 保持仪表盘现有 K 线展示兼容，验证多实例状态隔离和 dispose 后监听清理

## 阶段三：页面骨架与首屏

- [ ] Task 8: 新增个股诊断路由与 4 Tab 页面骨架
  - [ ] SubTask 8.1: PageController 新增 `/page/stock-detail/{code}`，注入 code/title，且不设置侧边栏 activeMenu
  - [ ] SubTask 8.2: 新增 stock-detail.html，复用 common fragments，提供摘要条、4 Tab 和分区级加载/空/错误容器
  - [ ] SubTask 8.3: 新增 stock-detail.js，解析并规范化 `tab` 参数，实现每个 Tab 的 idle/loading/loaded/error 状态机
  - [ ] SubTask 8.4: 新增 stock-detail.css，使用主题变量并处理 <1200px 摘要换行、图表自适应和表格横向滚动
  - [ ] SubTask 8.5: 合法代码不存在时显示“未找到该股票”并停止 Tab 请求；增加页面路由测试

- [ ] Task 9: 实现顶部摘要、自选与价格提醒
  - [ ] SubTask 9.1: 聚合或并发加载股票基础信息、最新行情和最新 daily_basic，展示规定的 10 项摘要
  - [ ] SubTask 9.2: 统一数值、亿元、市盈率、市净率、换手率和涨跌色格式，缺失/负估值显示 `--`
  - [ ] SubTask 9.3: 通过单一前端适配函数复用现有 Watchlist 添加、移除、查询状态接口
  - [ ] SubTask 9.4: 复用现有价格提醒设置/清除能力并回显当前提醒
  - [ ] SubTask 9.5: 摘要与操作分别提供 loading/error/retry，防止重复提交并测试回滚 UI 状态

## 阶段四：四个诊断 Tab

- [ ] Task 10: 实现 K 线技术面 Tab
  - [ ] SubTask 10.1: 接入共享 K 线组件和按 code+period+adj+range 的页面缓存，默认近 250 根日线前复权数据
  - [ ] SubTask 10.2: 实现 D/W/M/60 分按钮；后端不支持时禁用 60 分并显示说明
  - [ ] SubTask 10.3: 实现 MA/EMA/BOLL/SAR 主图显隐和 MACD/KDJ/RSI/WR/CCI/DMI 副图单选
  - [ ] SubTask 10.4: 实现主副图可见范围与十字光标联动、OHLCV/涨跌幅/指标浮窗
  - [ ] SubTask 10.5: 实现 MA5/10/20/60 金叉死叉事件时间轴并去重相邻事件
  - [ ] SubTask 10.6: 实现趋势线、水平线、平行通道和按 code+period+adj 隔离的本地持久化
  - [ ] SubTask 10.7: 快速切换时取消或隔离过期请求，主题切换后保留指标、画线与可见范围

- [ ] Task 11: 实现基本面 Tab
  - [ ] SubTask 11.1: 首次进入时并行加载估值、财务指标、三大表、业绩事件、分红和股东人数，各卡片独立状态
  - [ ] SubTask 11.2: 展示近五年 PE/PB/PS 历史序列，并按有效样本排名计算当前百分位；不足五年时标注实际范围
  - [ ] SubTask 11.3: 展示 ROE/ROA/毛利率/净利率趋势
  - [ ] SubTask 11.4: 展示三大表最近四期及可计算同比，处理去年同期缺失与除零
  - [ ] SubTask 11.5: 展示预告/快报时间轴和分红送转表格
  - [ ] SubTask 11.6: 展示股东人数折线与环比柱状图，处理缺失前值和零值

- [ ] Task 12: 实现资金面 Tab
  - [ ] SubTask 12.1: 展示近 30 个交易日主力净流入柱状图与累计汇总
  - [ ] SubTask 12.2: 展示北向持股量/比例曲线及 3 月、1 年、全部范围切换，无覆盖时局部空态
  - [ ] SubTask 12.3: 展示近一年龙虎榜汇总和分页明细，按交易日懒加载并缓存席位展开
  - [ ] SubTask 12.4: 展示近三个月大宗交易分页表和汇总，正确处理同日收盘价缺失
  - [ ] SubTask 12.5: 支持 `?tab=moneyflow` 深链接仅加载当前 Tab

- [ ] Task 13: 实现风险提示 Tab
  - [ ] SubTask 13.1: 展示最近有效交易日涨停价、现价、跌停价，并区分非交易日/停牌
  - [ ] SubTask 13.2: 展示历史停牌分页表和停牌次数/天数汇总，处理未复牌记录
  - [ ] SubTask 13.3: 过滤并展示 ST/退市风险名称变更时间轴和表格，无记录时显示专用空态
  - [ ] SubTask 13.4: 展示近一年股东增减持分页表，仅按减持记录计算减持汇总
  - [ ] SubTask 13.5: 确认页面不存在公司资料 F10 和限售解禁区块

## 阶段五：入口收口与验证

- [x] Task 14: 收口个股诊断下钻入口
  - [x] SubTask 14.1: 顶栏 SearchSuggest 选中股票后优先使用 tsCode，兼容 code，缺字段时不跳转
  - [x] SubTask 14.2: 自选股代码列和板块成分股代码列跳转个股诊断
  - [x] SubTask 14.3: 验证行情中心入口和资金流向 `?tab=moneyflow` 入口继续可用
  - [x] SubTask 14.4: 保留无侧边栏菜单约束

- [ ] Task 15: 完成自动化与端到端验证
  - [ ] SubTask 15.1: 执行新增 Java 单元/Controller 测试和前端 JS 语法检查
  - [ ] SubTask 15.2: 执行 `node stock-watcher/run.js test`，修复所有失败
  - [ ] SubTask 15.3: 执行 `node stock-watcher/run.js package-all`，确保完整构建通过
  - [ ] SubTask 15.4: 浏览器验证 4 Tab 懒加载、深链接、分区重试、快速切换竞态、主题切换和窄屏布局
  - [ ] SubTask 15.5: 随机抽取 5 只股票核对 QFQ/HFQ/NONE 与最新日对齐规则，并记录验证结果
  - [ ] SubTask 15.6: 验证所有下钻入口、添加/移除自选、价格提醒和无数据降级路径

# Task Dependencies

- Task 1 是所有新增/扩展 Controller 的契约前置。
- Task 2、Task 3、Task 4、Task 5 在 Task 1 完成后可并行。
- Task 6 可与 Task 2-5 并行；Task 7 依赖 Task 6 冻结数据口径，但可先完成组件生命周期设计。
- Task 8 可与 Task 2-7 并行完成页面骨架，但真实数据接入受对应后端任务约束。
- Task 9 依赖 Task 8 和摘要/Watchlist 现有接口适配。
- Task 10 依赖 Task 6、Task 7、Task 8。
- Task 11 依赖 Task 3、Task 4、Task 8。
- Task 12 依赖 Task 5、Task 8。
- Task 13 依赖 Task 2、Task 4、Task 8。
- Task 10-13 在各自依赖完成后可并行。
- Task 14 依赖 Task 8 的正式页面路由。
- Task 15 依赖 Task 2-14 全部完成。