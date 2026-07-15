# Tasks

## 缺陷 A：point-in-time 成分股过滤

- [x] Task 1: watcher 新增 point-in-time 成分股查询内部接口
  - [x] SubTask 1.1: 新增 `InternalController.java`，提供 `POST /api/internal/constituents/query` 端点（Request: `{index_code, trade_date}`；Response: `{index_code, trade_date, effective_date, constituents[]}`）
  - [x] SubTask 1.2: 复用 `IndexWeightMapper.selectConstituentsAt`，确认返回的是 `≤ trade_date` 的最新**生效日**快照；若 SQL 不足则新增
  - [x] SubTask 1.3: 确认 `IndexWeightTask.java` 同步任务存储的 `trade_date` 是生效日（非公告日）；未对齐则回补历史快照
  - [x] SubTask 1.4: 接口加 `/internal/*` 前缀鉴权（仅允许 engine 内网调用）

- [x] Task 2: engine 边界约束修订与 WatcherClient 新增
  - [x] SubTask 2.1: 修订 `rebalance_engine.py` L14-17 注释为分层约束（数据单源性强 / 行情基本面强 / 参考数据弱+例外）
  - [x] SubTask 2.2: 新建 `stock-engine/services/backtest/watcher_client.py`，实现 `WatcherClient.get_constituents_at(index_code, trade_date) -> set[str]`
  - [x] SubTask 2.3: 限定 WatcherClient 仅查 `/api/internal/*` 只读端点，禁止拉行情/基本面

- [x] Task 3: rebalance_engine 集成 point-in-time 过滤
  - [x] SubTask 3.1: `select_at_rebalance_date` 在 `_build_candidates` 之前插入 universe 过滤逻辑（csi300→000300.SH, csi500→000905.SH）
  - [x] SubTask 3.2: manual universe 跳过接口调用，直接用 stocks 列表
  - [x] SubTask 3.3: 过滤后打 info 日志（候选 N/M、生效日）

- [x] Task 4: WatcherClient 注入路径与降级
  - [x] SubTask 4.1: `runner.py` 从 HTTP header `X-Watcher-Base-Url` 或环境变量 `WATCHER_BASE_URL` 构造 WatcherClient 注入 RebalanceEngine
  - [x] SubTask 4.2: 未配置时 `watcher_client=None`，跳过过滤并打 warning（向后兼容）
  - [x] SubTask 4.3: watcher 侧回测请求发起处补 `X-Watcher-Base-Url` header（指向自己）

## 缺陷 B：基本面因子数据下发

- [x] Task 5: watcher buildKlineData 补齐基本面字段
  - [x] SubTask 5.1: 改造 `BacktestServiceImpl.buildKlineData`（L539-570），对每只成分股按日期 join `daily_basic` 表
  - [x] SubTask 5.2: 补齐字段：PE_TTM / PB / TOTAL_MV / ROE_TTM（具体字段以 daily_basic 表为准）
  - [x] SubTask 5.3: daily_basic 缺记录时字段缺失，由 engine 侧 NaN 兜底

- [x] Task 6: engine kline 解析提取 extra
  - [x] SubTask 6.1: 在 `strategy_runner.py`（或 runner.py kline 解析处）实现 `kline_to_df_with_extra(records) -> (DataFrame, extra_map)`
  - [x] SubTask 6.2: OHLCV 放 DataFrame，基本面字段放入 akquant Bar 的 `extra: dict[str,float]`（依 akquant rules 02 §4）
  - [x] SubTask 6.3: 确认 extra 按日期索引，供 rebalance_engine 按 trade_date 取用

- [x] Task 7: rebalance_engine 取用 extra 基本面
  - [x] SubTask 7.1: 改造 `_compute_factor`（L367-370）TUSHARE 分支，从 `candidate["extra"]` 按 trade_date 取值
  - [x] SubTask 7.2: 替代当前 `candidate.get("fundamentals") or {}` 空字典兜底
  - [x] SubTask 7.3: 字段名小写化对齐（factor_key.lower()）

## 缺陷 C：选股模型 4 层重构

- [x] Task 8: Schema 文档重构
  - [x] SubTask 8.1: 更新 `sdlc/prd/004-策略管理/统一策略配置Schema.md` §3.2，screen_config 改为 universe/factor/filter/portfolio 4 层
  - [x] SubTask 8.2: 文档化 4 层职责定义表 + 与 akquant 链路对应关系
  - [x] SubTask 8.3: 文档化旧→新字段迁移映射规则

- [x] Task 9: engine validator 4 层结构校验
  - [x] SubTask 9.1: `services/strategy/validator.py` 新增 screen_config 4 层结构校验
  - [x] SubTask 9.2: 缺失必需层返回 `SCREEN_CONFIG_LAYER_MISSING`
  - [x] SubTask 9.3: 旧 5 字段扁平结构返回 `SCREEN_CONFIG_DEPRECATED_STRUCTURE` 并提示迁移

- [x] Task 10: rebalance_engine 适配 4 层读取
  - [x] SubTask 10.1: `_build_candidates` / `_filter_valid_symbols` / ranking 逻辑改为从 `screen_config.factor` / `screen_config.filter` / `screen_config.portfolio` 取值
  - [x] SubTask 10.2: `rebalance_to_topn` 调用参数对齐：top_n=portfolio.top_n，weight_mode 来自 trading_config.rebalance.weight_mode
  - [x] SubTask 10.3: weight_mode="equal" 忽略 factor 分数等权；"score" 按 factor 加权

## 全链路与文档

- [x] Task 11: akquant rules 与边界文档
  - [x] SubTask 11.1: `.trae/rules/akquant/03-strategy-api.md` §12 补充 point-in-time 成分股要求
  - [x] SubTask 11.2: 新建 `.trae/rules/akquant/10-rotation-pit-guide.md` 轮动范式 point-in-time 防坑指南

- [x] Task 12: 前端适配 4 层 screen_config
  - [x] SubTask 12.1: `editor.html` screen_config 区域 UI 改为 4 层（universe/factor/filter/portfolio）
  - [x] SubTask 12.2: `strategy-editor.js` collectScreenConfig 按 4 层结构收集
  - [x] SubTask 12.3: universe.pool 下拉支持 csi300/csi500/manual；manual 显示 stocks 云

- [x] Task 13: watcher resolveBacktestSymbols 标注
  - [x] SubTask 13.1: `BacktestServiceImpl.resolveBacktestSymbols` 保留区间并集逻辑（仍需全量数据准备）
  - [x] SubTask 13.2: 添加注释「仅用于数据准备，point-in-time 过滤由 engine 在调仓日执行」

## 测试与存量

- [x] Task 14: 单测与端到端测试
  - [x] SubTask 14.1: engine `tests/services/backtest/test_rotation_point_in_time.py`（AC-1/9）
  - [x] SubTask 14.2: engine `tests/services/backtest/test_fundamentals.py`（AC-2/3）
  - [x] SubTask 14.3: engine `tests/services/strategy/test_validator.py` 新增 4 层结构校验用例（AC-4）
  - [ ] SubTask 14.4: watcher `IndexWeightServiceImplTest.java` getConstituentsAt 生效日对齐测试（AC-6）

- [x] Task 15: 存量清零与模板重载
  - [ ] SubTask 15.1: 与 009 同步执行 `TRUNCATE quant_strategy + quant_strategy_version`（部署人员手动执行）
  - [x] SubTask 15.2: 模板 JSON 文件更新为 4 层 screen_config 结构
  - [x] SubTask 15.3: 重启 watcher 由 StrategyTemplateLoader 重载（Pydantic 验证通过）

# Task Dependencies

- Task 2 (WatcherClient) → Task 3 (集成过滤) → Task 4 (注入)
- Task 5 (watcher 补基本面) → Task 6 (engine 提取 extra) → Task 7 (rebalance 取用)
- Task 8 (Schema 文档) → Task 9 (validator) + Task 10 (rebalance 适配) + Task 12 (前端)
- Task 1 (watcher 内部接口) 与 Task 5 可并行（watcher 侧独立改动）
- Task 11 (文档) 可与 Task 8 并行
- Task 14 (测试) 依赖 Task 3/7/9 完成
- Task 15 (存量清零) 必须在 Task 12（前端）+ Task 9/10（engine）全部验证通过后执行
