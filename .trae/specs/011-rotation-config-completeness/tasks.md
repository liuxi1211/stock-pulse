# Tasks

> 按 PRD §10 的迭代节奏组织。关键路径：**迭代 0（P0-0 数据底座）→ 迭代 4（P1-3 自托管权重）** 是 P0-4/P0-5/P1-4/P1-7/P2-4 的共同依赖。
> 一期（迭代 0~5）已全部完成；二期/三期（Task 6.x）登记未交付。

## 迭代 0：数据底座（watcher 独立，先行）

- [x] **Task 0.1：P0-0 申万行业分类对接（watcher 侧 10 处改动）**
  - [x] 0.1.1 DDL：`schema-mysql.sql` + `schema-sqlite.sql` + `DataInitServiceImpl` 内置 Map 三处同步新增 `sw_industry` + `sw_industry_member` 两表
  - [x] 0.1.2 `TushareApiEnum` 注册 `INDEX_CLASSIFY` / `INDEX_MEMBER_ALL`
  - [x] 0.1.3 DTO：`IndexClassifyQueryDTO` / `IndexClassifyDTO` / `IndexMemberQueryDTO` / `IndexMemberDTO`
  - [x] 0.1.4 DO：`SwIndustryDO` / `SwIndustryMemberDO`
  - [x] 0.1.5 Mapper：`SwIndustryMapper` / `SwIndustryMemberMapper`（含 `selectLatestL1ByTsCode` + `selectL1AtDate` point-in-time）
  - [x] 0.1.6 Service：`SwIndustryService` + Impl（幂等 delete-then-insert，分页拉取 2000/次）
  - [x] 0.1.7 `TushareClient` 扩展 `indexClassify` / `indexMemberAll`
  - [x] 0.1.8 `InitStep.SW_INDUSTRY` + `DataInitServiceImpl` 注册（EXECUTION_ORDER + switch + DDL Map）
  - [x] 0.1.9 `SwIndustryTask` 半年定时（cron `0 0 22 1 1,7 *`）
  - [x] 0.1.10 `application.yml` 限流（index_classify / index_member_all 各 200/min）
  - [x] 0.1.11 `BacktestServiceImpl.buildKlineData` 下发 `sw_industry_l1` + 6 个 trade_cal `is_*_of_*` 调仓标记（is_st/is_suspended/is_limit_up/is_limit_down/list_date 待数据源扩展，已留 TODO）
  - [x] 0.1.12 engine `data_adapter.kline_to_extra_map` 提取元数据字段（新增 `_META_FIELDS` + `meta_fields` 参数，保留原始类型）

## 迭代 1：回测可信度硬伤（P0-3）

- [x] **Task 1.1：P0-3 轮动范式默认当日收盘成交**
  - [x] 1.1.1 engine `run_backtest_engine`：has_rebalance 且未配 fill_policy 时默认 close/same_cycle/bar_offset=0
  - [x] 1.1.2 前端 Tab8 fill_policy 控件，轮动范式默认「当日收盘（推荐·尾盘调仓）」
  - [x] 1.1.3 验证 trades_df entry_price = 选股日 close

## 迭代 2：功能完整性 P0 收口（P0-1 + P0-2）

- [x] **Task 2.1：P0-1 静态过滤接通（engine）**
  - [x] 2.1.1 `rebalance_engine` 新增 `_filter_by_static_rules` + 6 辅助方法（独立实现，不跨模块调 screener）
  - [x] 2.1.2 在 `select_at_rebalance_date` 过滤链路插入（filter_valid_symbols 之后、filter_by_conditions 之前）
  - [x] 2.1.3 元数据缺失静默跳过 + warning
  - [x] 2.1.4 单测：exclude_st / min_list_days / industries 场景（全过）

- [x] **Task 2.2：P0-2 score + single 禁用**
  - [x] 2.2.1 `errors.py` 新增 `FACTOR_SCORE_INCOMPATIBLE`
  - [x] 2.2.2 `validator` 新增 `_validate_factor_score_compatibility` 跨字段联动校验
  - [x] 2.2.3 `compiler.compile_strategy` rebalance 预捕获段同步校验（后端兜底）
  - [x] 2.2.4 前端：method=single 时 score radio 置灰 + tooltip + 切换拦截

## 迭代 3：数据治理（P1-1）

- [x] **Task 3.1：P1-1 point_in_time 强制**
  - [x] 3.1.1 engine `UniverseModel`：point_in_time 标记 deprecated + model_validator 检测在场打 warning（保留字段兼容旧 JSON）
  - [x] 3.1.2 `rebalance_engine._apply_universe_filter`：不再判断 point_in_time，无条件执行；扩展所有 universe 类型（csi300/csi500/all_a_shares/manual/自定义池 用 000985.SH proxy）
  - [x] 3.1.3 watcher_client=None / 查询空 / 查询异常 → 抛 `PIT_WATCHER_UNAVAILABLE` / `PIT_CONSTITUENTS_EMPTY` / `PIT_QUERY_FAILED`
  - [x] 3.1.4 `errors.py` 新增三个 PIT 错误码
  - [x] 3.1.5 每调仓日 INFO 日志记录过滤结果
  - [x] 3.1.6 前端 editor.html 移除 `f-universe-point-in-time-wrap` + JS collect/refill（6 处）
  - [x] 3.1.7 watcher 5 个策略模板 JSON 移除 point_in_time 字段（StrategySchemaConstants 无该白名单，无需改）

## 迭代 4：权重体系重构（P1-3 + P1-4 + P1-7 + P2-4 + P0-4 + P0-5）

- [x] **Task 4.1：P1-3 engine 自托管权重（关键路径）**
  - [x] 4.1.1 `models.py` PortfolioModel 加 `cash_reserve_pct`（ge=0, le=0.95）
  - [x] 4.1.2 `compiler` 捕获 rb_cash_reserve / rb_weight_mode / rb_top_n 等 7 个权重字段
  - [x] 4.1.3 `compiler` on_daily_rebalance 改 `_compute_target_weights` 后调 `order_target_weights`（弃 `rebalance_to_topn`）
  - [x] 4.1.4 保留 `USE_LEGACY_REBALANCE=true` feature flag 回切
  - [x] 4.1.5 前端 Portfolio 层 cash_reserve 输入框
  - [x] 4.1.6 单测：equal 模式与旧路径行为一致（除 cash_reserve），9 场景全过

- [x] **Task 4.2：P1-4 单标的/行业暴露上限**
  - [x] 4.2.1 PortfolioModel 加 `max_weight_per_symbol` / `max_industry_exposure`
  - [x] 4.2.2 engine 权重后处理：单标的截断重分 + 行业暴露迭代截断（`_clip_industry_exposure`，max_iterations=10）
  - [x] 4.2.3 industry 元数据缺失静默跳过 + warning
  - [x] 4.2.4 前端两个输入框 + 启用开关
  - [x] 4.2.5 单测：行业暴露缩减场景（含数字示例）

- [x] **Task 4.3：P1-7 换仓缓冲带 buffer_n**
  - [x] 4.3.1 PortfolioModel 加 `buffer_n`（ge=0）
  - [x] 4.3.2 engine：买入 top_(n-buffer)、卖出 top_(n+buffer)
  - [x] 4.3.3 前端控件
  - [x] 4.3.4 验证换手率下降（逻辑单测通过）

- [x] **Task 4.4：P2-4 最小持仓周期**
  - [x] 4.4.1 RebalanceModel 加 `min_holding_bars`
  - [x] 4.4.2 engine：hold_bar(sym) < min_holding_bars 的持仓保留原权重
  - [x] 4.4.3 前端控件

- [x] **Task 4.5：P0-4 涨停拒买 / 跌停拒卖（依赖 Task 4.1）**
  - [x] 4.5.1 RebalanceModel 加 `reject_limit_up_on_buy=True` / `reject_limit_down_on_sell=True`
  - [x] 4.5.2 engine 自托管权重后：买入方向当日 is_limit_up=1 剔除、卖出方向 is_limit_down=1 保留
  - [x] 4.5.3 日志「涨停拒买: XXX」
  - [x] 4.5.4 单测：涨停拒买/跌停拒卖保留场景

- [x] **Task 4.6：P0-5 score 降序 + 诊断回传（依赖 Task 4.1）**
  - [x] 4.6.1 engine：target_weights 按 score 降序排列后再下单
  - [x] 4.6.2 `result_serializer` 增加 `rebalance_diagnosis`（从 result.strategy._rb_diagnosis 取）
  - [x] 4.6.3 前端回测结果展示诊断（数据已回传，前端展示待回测结果页改造时对接）

## 迭代 5：交互补全（P1-2 + P1-5 + P2-1 + P2-2/3 + P2-5/6/8）

- [x] **Task 5.1：P1-2 权重面板重设计（含 P2-7 归一化提示）**
  - [x] 5.1.1 weightRowHtml 改 `<datalist>` 联想输入（数据源与 Tab2b 一致）
  - [x] 5.1.2 方向切换按钮（越大越好/越小越好，绿/红配色）
  - [x] 5.1.3 公式说明可折叠区块（`<details>`）
  - [x] 5.1.4 组合校验（composite+空 weights / 权重全 0 / factorKey 重复 / 不在白名单）
  - [x] 5.1.5 归一化实时提示
  - [x] 5.1.6 collect/refill 适配方向×绝对值

- [x] **Task 5.2：P1-5 轮动范式 Tab6 可见**
  - [x] 5.2.1 editor.html Tab6 `data-paradigm` signals → both（position_sizing 在独立 Tab5 仍 signals，轮动隐藏）
  - [x] 5.2.2 validator 复核：rebalance+exit 组合本就允许（exit 校验独立，不依赖 signals），无需改
  - [x] 5.2.3 轮动范式 collect 不再 delete exit，调 collectExit()

- [x] **Task 5.3：P2-1 trade_cal 预计算标记 + trigger 语义**
  - [x] 5.3.1 watcher trade_cal 表加 6 个 `is_*_of_*` 字段（schema-mysql + schema-sqlite + DataInitServiceImpl + 老库迁移 ensureTradeCalRebalanceColumns）
  - [x] 5.3.2 `TradeCalServiceImpl` 预计算标记（computeAndSaveRebalanceFlags）+ `queryFlagsByRange`
  - [x] 5.3.3 `BacktestServiceImpl.buildKlineData` 注入 6 个标记到 bar
  - [x] 5.3.4 engine `data_adapter` 提取标记（_META_FIELDS 已含）
  - [x] 5.3.5 engine RebalanceModel：新增 `trigger: Literal["first","last"]` + day_of_period deprecated 兼容映射
  - [x] 5.3.6 engine 新增 `_is_rebalance_day` 查 bar 标记（零状态）；旧 `_is_rebalance_trigger_day` 保留作标记缺失回退
  - [x] 5.3.7 `errors.py` 新增 `INVALID_REBALANCE_TRIGGER`
  - [x] 5.3.8 validator 联动校验（trigger 非 first/last/None 报错 + day_of_period deprecation warning）
  - [x] 5.3.9 前端 `f-reb-day` 替换为 `f-reb-trigger` 下拉，随 frequency 动态显隐 + label 更新

- [x] **Task 5.4：P2-2 / P2-3 fill_policy / risk_config 前端控件**
  - [x] 5.4.1 Tab8 fill_policy 下拉（与 Task 1.1.2 一致）
  - [x] 5.4.2 Tab8 risk_config 折叠面板（max_position_pct / max_drawdown_pct）

- [x] **Task 5.5：P2-5 warmup 实际值回传**
  - [x] 5.5.1 `result_serializer` 回传 `effective_config.warmup_period` + source + reason（compiler 挂类属性 → runner 读取）
  - [x] 5.5.2 前端展示待回测结果页对接（数据已回传）

- [x] **Task 5.6：P2-6 条件树模式切换双向同步**
  - [x] 5.6.1 可视化↔JSON 双向同步（现有 switchCondMode 已双向）
  - [x] 5.6.2 切换前确认框

- [x] **Task 5.7：P2-8 年化换手率指标**
  - [x] 5.7.1 `result_serializer` 计算 `annual_turnover_ratio`（_compute_annual_turnover）
  - [x] 5.7.2 前端 metrics 展示待回测结果页对接（数据已回传）

## 二期/三期（登记，不在本 spec 交付）

- [ ] **Task 6.1（二期）：P1-6 FactorNode transform 滚动窗口聚合** → 单独 spec
- [ ] **Task 6.2（三期）：P2-9 分批调仓 + 冲击成本建模** → 单独 spec

## Task Dependencies

- Task 0.1（P0-0 数据底座）是 Task 2.1（P0-1 静态过滤 industry）、Task 4.2（P1-4 行业暴露）、Task 4.5（P0-4 涨停元数据）的前置。
- Task 4.1（P1-3 自托管权重）是 Task 4.2 / 4.3 / 4.4 / 4.5 / 4.6 的共同依赖，须最先完成。
- Task 1.1（P0-3 成交价）无依赖，可并行插入任意迭代。
- Task 5.3（P2-1 trigger）依赖 Task 0.1.11/0.1.12 已下发 trade_cal 标记。
- Task 5.2（P1-5）复核 009 范式互斥边界：rebalance+exit 不与 signals 冲突（exit 校验独立）。

## 遗留待办（数据源扩展，非本 spec 代码交付）

- watcher 侧 `is_st` / `is_suspended` / `is_limit_up` / `is_limit_down` / `list_date` 元数据下发：当前 DailyQuoteDO/DailyBasicDO 无对应列，buildKlineData 已留 TODO。engine 侧 _filter_by_static_rules / P0-4 涨跌停拒单逻辑已就绪，待 watcher 数据源扩展后即可生效（缺失时静默跳过 + warning）。
- 前端回测结果页对 `rebalance_diagnosis` / `effective_config.warmup_period` / `annual_turnover_ratio` 的展示组件对接（数据已由 result_serializer 回传）。
- 行业归属 point-in-time：一期用 getLatestL1Industry（最新归属），二期启用 selectL1AtDate（按 bar 日期查当时归属）。
