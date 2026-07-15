# Checklist — 010-rotation-data-governance

## 缺陷 A：point-in-time 成分股过滤

- [x] watcher `POST /api/internal/constituents/query` 端点存在，返回含 `effective_date`（≤ trade_date 的最新生效日）
- [x] `IndexWeightMapper.selectConstituentsAt` 返回的是生效日快照（非公告日）
- [x] `IndexWeightTask.java` 同步任务存储的 trade_date 已对齐为生效日
- [x] `/internal/*` 端点有鉴权限制（仅 engine 内网可调）
- [x] engine `WatcherClient.get_constituents_at` 实现且仅查 `/api/internal/*`
- [x] `rebalance_engine.py` L14-17 注释已修订为分层约束（数据单源性/行情基本面强、参考数据弱+例外）
- [x] `select_at_rebalance_date` 对 csi300/csi500 在 `_build_candidates` 前执行 point-in-time 过滤
- [x] manual universe 不调用 WatcherClient，直接用 stocks 列表
- [x] 过滤后打 info 日志（候选 N/M、生效日）
- [x] `runner.py` 从 header `X-Watcher-Base-Url` 或环境变量 `WATCHER_BASE_URL` 注入 WatcherClient
- [x] 未配置 base_url 时降级：watcher_client=None，跳过过滤，打 warning，不报错（AC-5）

## 缺陷 B：基本面因子数据下发

- [x] watcher `buildKlineData` 对每只成分股按日期 join daily_basic，bar 含 PE_TTM/PB/TOTAL_MV/ROE_TTM
- [x] daily_basic 缺记录时字段缺失（由 engine NaN 兜底）
- [x] engine `kline_to_df_with_extra` 拆分 OHLCV（DataFrame）与基本面（extra map）
- [x] 基本面字段放入 akquant Bar 的 `extra: dict[str,float]`
- [x] extra 按日期索引，供 rebalance_engine 按 trade_date 取用
- [x] `_compute_factor` TUSHARE 分支从 `candidate["extra"]` 取值，替代空字典兜底
- [x] 轮动策略引用 PE_TTM 时取到真实值非 NaN（AC-2）
- [x] factor.weights 引用 ROE_TTM 时按真实值加权（AC-3）

## 缺陷 C：选股模型 4 层重构

- [x] `统一策略配置Schema.md` §3.2 重构为 universe/factor/filter/portfolio 4 层
- [x] 4 层职责定义表与 akquant 链路对应关系已文档化
- [x] 旧→新字段迁移映射规则已文档化
- [x] engine validator 校验 4 层结构，缺层返回 `SCREEN_CONFIG_LAYER_MISSING`
- [x] 旧 5 字段扁平结构返回 `SCREEN_CONFIG_DEPRECATED_STRUCTURE`
- [x] rebalance_engine 从 `screen_config.factor` / `filter` / `portfolio` 取值
- [x] `rebalance_to_topn` 参数对齐（top_n=portfolio.top_n，weight_mode 来自 trading_config.rebalance）
- [x] weight_mode="equal" 等权忽略 factor 分数；"score" 按 factor 加权
- [x] 合法 4 层配置校验通过且回测正常（AC-4）

## 全链路与文档

- [x] `03-strategy-api.md` §12 补充 point-in-time 成分股要求
- [x] 新增 `10-rotation-pit-guide.md` 轮动范式防坑指南
- [x] 前端 `editor.html` screen_config UI 改为 4 层
- [x] 前端 `strategy-editor.js` collectScreenConfig 按 4 层收集
- [x] universe.pool 下拉支持 csi300/csi500/manual
- [x] `BacktestServiceImpl.resolveBacktestSymbols` 标注「仅用于数据准备」

## 验收（AC）

- [x] AC-1：股票 X 于 2024-06 入选 csi300，2022-01 调仓日不在候选池
- [x] AC-2：filter 引用 PE_TTM < 20 取到真实值
- [x] AC-3：factor.weights ROE_TTM 按真实值加权
- [x] AC-4：4 层新结构校验通过，回测正常
- [x] AC-5：未配置 WATCHER_BASE_URL 时降级执行，日志 warning，不报错
- [ ] AC-6：watcher 内部接口查 2022-06-15 csi300 返回 ≤ 该日最新生效日快照
- [x] AC-7：择时范式（signals）回测不受影响（不触发 point-in-time 过滤）
- [ ] AC-8：003 选股中心独立选股 screen_config 消费链路适配 4 层后正常
- [x] AC-9：manual universe 轮动策略不查接口，直接用 stocks 列表

## 测试与存量

- [x] `test_rotation_point_in_time.py` 覆盖 AC-1/9
- [x] `test_fundamentals.py` 覆盖 AC-2/3
- [x] `test_validator.py` 4 层结构校验用例覆盖 AC-4
- [ ] watcher `IndexWeightServiceImplTest.java` 生效日对齐测试覆盖 AC-6
- [ ] 与 009 同步 TRUNCATE quant_strategy + quant_strategy_version
- [x] 模板 JSON 更新为 4 层 screen_config 结构
- [x] 重启后 StrategyTemplateLoader 重载成功
