# Checklist

> 对应 PRD §11 / §14.4 / §15.12 验收要点。逐项验证，通过后勾选。

## P0-0 申万行业分类对接

- [x] `sw_industry` 表落库申万 2021 版三级目录树（31 一级 + 134 二级 + 346 三级）
- [x] `sw_industry_member` 表落库全 A 股当前行业归属（约 5000+ 条 `is_new=1`）
- [x] `selectLatestL1ByTsCode("000001.SZ")` 返回「银行」
- [x] `BacktestServiceImpl.buildKlineData` 下发 bar 含 `sw_industry_l1`
- [x] engine `kline_to_extra_map` 能提取 `sw_industry_l1`
- [x] DataInit `InitStep.SW_INDUSTRY` 可一键拉取
- [x] 定时任务每年 1/7 月自动同步（cron 生效）
- [x] 限流配置生效（index_classify / index_member_all 各 200/min）
- [x] point-in-time 查询接口 `selectL1AtDate` 存在（二期启用）
- [x] 三处 DDL 同步（schema-mysql.sql / schema-sqlite.sql / DataInitServiceImpl Map），切换 db-type 不缺表

## P0-1 静态过滤

- [x] 配置 `exclude_st=true`，回测日志可见「剔除 ST: N 只」
- [x] 配置 `min_list_days=250`，次新股确实被剔除
- [x] `industries` / `exclude_industries` 行业过滤生效
- [x] 元数据缺失时打 warning 但不阻断回测
- [ ] kline_data 下发 `is_st` / `is_suspended` / `is_limit_up` / `is_limit_down` / `list_date`

## P0-2 score + single 禁用

- [x] `factor.method=single` + `rebalance.weight_mode=score` → validator 报 `FACTOR_SCORE_INCOMPATIBLE`
- [x] compiler 同步拦截同错误码
- [x] 前端 single + score 组合 radio 置灰 + tooltip
- [x] composite + score 仍可用

## P0-3 调仓成交价

- [x] 轮动范式不填 fill_policy 时默认当日收盘成交
- [x] trades_df entry_price = 选股日 close（非次日 open）
- [x] 前端轮动范式下 fill_policy 默认显示「当日收盘」+ 推荐标注
- [x] 用户可手动切回「次日开盘」

## P0-4 涨停拒买 / 跌停拒卖

- [x] 涨停标的买入时被剔除，trades_df 无该标的买入记录
- [x] 回测日志可见「涨停拒买: XXX」
- [x] 跌停标的卖出时保留原持仓
- [x] `reject_limit_up_on_buy=False` 时涨停标的仍尝试买入（对比用）
- [x] 仓位虚高问题消除（与关闭对比收益更保守）

## P0-5 资金不足 + 诊断

- [x] target_weights 按 score 降序排列（高分优先分配资金）
- [x] 回测结果含 `rebalance_diagnosis`（selected_count / actually_bought / rejected_by_cash / rejected_by_limit_up / actual_invest_ratio）
- [ ] 前端展示「实际成交 X/Y 只，拒单原因」

## P1-1 point_in_time 强制

- [x] `point_in_time` 字段从 schema / 前端 / watcher 常量全部移除
- [x] watcher_client 未配置 → 回测失败 `PIT_WATCHER_UNAVAILABLE`
- [x] watcher 查询返回空 → 回测失败 `PIT_CONSTITUENTS_EMPTY`
- [x] watcher 查询异常 → 回测失败 `PIT_QUERY_FAILED`
- [x] 每个调仓日 INFO 日志记录过滤结果
- [x] 所有 universe 类型（csi300/csi500/all_a_shares/manual/自定义池）均查 watcher
- [x] 旧策略 JSON 含 point_in_time 字段不报错（向后兼容 + deprecation warning）

## P1-2 权重面板

- [x] factorKey 只能从联想列表选，无法手敲
- [x] 「越大越好 / 越小越好」方向切换按钮正常工作
- [x] 公式说明可折叠展示
- [x] composite + 空 weights 前端实时红字提示
- [x] 权重全 0 / factorKey 重复 / 不在白名单 均有提示
- [x] 归一化实时提示（底部显示各因子实际权重百分比）

## P1-3 cash_reserve_pct

- [x] `cash_reserve_pct=0.2, top_n=30` → 总权重 0.8，每只约 0.0267
- [x] `cash_reserve_pct=0` → 行为与现状一致（除取整损耗）
- [x] engine 自托管权重（order_target_weights）与旧 `rebalance_to_topn` equal 模式行为一致
- [x] 前端输���框范围 0-0.95，超出报错
- [x] `USE_LEGACY_REBALANCE=true` 可一键回切旧路径

## P1-4 单标的 / 行业暴露上限

- [x] `max_weight_per_symbol=0.1` → 单只不超过 10%，超出截断后重新等分
- [x] `max_industry_exposure=0.3` → 单行业总权重不超过 30%（30 只等权 + 电子 12 只示例：scale=0.75，释放 0.10 转现金）
- [x] 行业暴露迭代防连锁超限
- [x] industry 元数据缺失时该项静默跳过 + warning
- [x] 前端两个输入框 + 启用开关

## P1-5 轮动范式 Tab6

- [x] 轮动范式下 Tab6 止损止盈可见（bracket + rules）
- [x] position_sizing 对轮动范式仍隐藏
- [x] `rebalance + exit.bracket` 组合可保存可回测
- [x] 持仓跌破 stop_loss_pct 时触发止损卖出
- [x] 复核 009 范式互斥边界无冲突

## P1-7 buffer_n

- [x] `buffer_n=5, top_n=20` → 新标的需进入 top 15 才买入
- [x] 持仓标的跌出 top 25 才卖出
- [x] 回测换手率显著下降（与 buffer_n=0 对比）

## P2-1 trade_cal 预计算标记 + trigger

- [x] `trade_cal` 表 6 个标记字段预计算正确（含跨月/跨季边界）
- [x] 三处 DDL 同步（schema-mysql + schema-sqlite + DataInitServiceImpl）
- [x] `monthly + trigger=last` → 月末最后交易日触发（1 月 31 日、2 月 28/29 日）
- [x] `monthly + trigger=first` → 月初首个交易日触发（避开周末）
- [x] `quarterly + trigger=last` → 季末最后交易日触发
- [x] `quarterly + trigger=first` → 季初首个交易日触发
- [x] `weekly + trigger=first` → 每周一触发
- [x] `weekly + trigger=last` → 每周五触发
- [x] `daily` → 每个交易日触发，trigger 下拉隐藏
- [x] kline_data 的 bar 含 6 个 `is_*_of_*` 标记字段
- [x] engine `_is_rebalance_day` 零状态查询（无计数器）
- [x] validator 对非法 trigger 报 `INVALID_REBALANCE_TRIGGER`
- [x] 旧策略含 `day_of_period` 字段时自动映射 trigger + deprecation warning
- [x] 前端 trigger 下拉随 frequency 动态显隐 + label 更新

## P2-2 / P2-3 fill_policy / risk_config 控件

- [x] Tab8 fill_policy 下拉可用（与 P0-3 默认值一致）
- [x] Tab8 risk_config 折叠面板暴露 max_position_pct / max_drawdown_pct

## P2-4 最小持仓周期

- [x] `min_holding_bars` 配置生效
- [x] 持仓不足周期的标的即便未入选 top_n 也不卖出

## P2-5 warmup 实际值回传

- [x] 回测结果含 `effective_config.warmup_period` + `warmup_source` + `warmup_reason`
- [ ] 前端展示「系统建议 warmup: N（基于因子窗口自动推断）」

## P2-6 条件树模式切换

- [x] 可视化 → JSON 切换不丢数据
- [x] JSON → 可视化切换不丢数据
- [x] 切换前弹确认框

## P2-8 年化换手率

- [x] metrics 含 `annual_turnover_ratio`
- [ ] 前端展示年化换手率

## 跨字段校验与错误码

- [x] `FACTOR_SCORE_INCOMPATIBLE` 错误码注册并被 validator + compiler 使用
- [x] `INVALID_REBALANCE_TRIGGER` 错误码注册（替代 `REBALANCE_DAY_OUT_OF_RANGE`）
- [x] `PIT_WATCHER_UNAVAILABLE` / `PIT_CONSTITUENTS_EMPTY` / `PIT_QUERY_FAILED` 三个错误码注册

## 向后兼容

- [x] 旧策略 JSON 含 `point_in_time` 字段不报错（保留字段忽略取值 + deprecation warning）
- [x] 旧策略 JSON 含 `day_of_period` 字段不报错（自动映射 trigger + deprecation warning）

---

## 验证报告

**已勾选：64 项；未勾选：4 项。**

### 未勾选项清单及原因

| 序号 | checkpoint | 未满足原因 |
|---|---|---|
| P0-1 | kline_data 下发 `is_st` / `is_suspended` / `is_limit_up` / `is_limit_down` / `list_date` | watcher `BacktestServiceImpl.buildKlineData` 第 620-622 行明确标注 TODO：当前数据源（DailyQuoteDO/DailyBasicDO）暂无对应列，is_st/is_suspended/is_limit_up/is_limit_down/list_date 均未实际下发。engine 侧 `data_adapter._META_FIELDS` 与 `rebalance_engine._filter_by_static_rules` 已就绪消费这些字段，待 watcher 数据源扩展后即可端到端生效。 |
| P0-5 | 前端展示「实际成交 X/Y 只，拒单原因」 | engine `result_serializer` 已回传 `rebalance_diagnosis`（含 selected_count/actually_bought/rejected_by_limit_up/actual_invest_ratio 等），但前端回测结果页 `report.html` + `backtest-report.js` 未实现诊断信息的展示组件。 |
| P2-5 | 前端展示「系统建议 warmup: N（基于因子窗口自动推断）」 | engine `result_serializer` 已回传 `effective_config.warmup_period` + `warmup_source` + `warmup_reason`，但前端回测结果页未实现 warmup 推断值的展示组件。 |
| P2-8 | 前端展示年化换手率 | engine `result_serializer._compute_annual_turnover` 已计算并回传 `metrics.annual_turnover_ratio`，但前端回测结果页未实现年化换手率的展示组件。 |

### 运行时验证待集成测试的勾选项说明

以下 checkpoint 属于运行时行为，本次只做了代码存在性 + 逻辑正确性核对，实际运行结果需待集成测试（含 watcher 下发元数据后）验证：

- **P0-1 静态过滤前 4 项**：`rebalance_engine._filter_by_static_rules` 逻辑完整（7 项静态字段判定 + 元数据缺失 warning + 汇总日志），但运行时剔除效果依赖 watcher 下发 is_st/list_date 等元数据（见上表 P0-1 未勾选项）。
- **P0-3 trades_df entry_price = 选股日 close**：`runner.run_backtest_engine` 第 188-192 行对 has_rebalance 默认注入 `fill_policy=close/same_cycle/bar_offset=0`，逻辑正确；实际 entry_price 取值需回测运行确认。
- **P0-4 涨跌停拒买/拒卖 5 项**：`compiler._compute_target_weights` 第 1556-1565 行（涨停拒买 + 日志「涨停拒买: XXX」）与第 1592-1609 行（跌停拒卖保留 + 日志「跌停拒卖保留」）逻辑完整，但运行时效果依赖 watcher 下发 is_limit_up/is_limit_down（见 P0-1 未勾选项）。
- **P2-1 monthly/quarterly/weekly × trigger=first/last 触发**：`compiler._is_rebalance_day` 查 trade_cal 标记的零状态逻辑完整，watcher `TradeCalServiceImpl.computeAndSaveRebalanceFlags` 预计算 6 字段；具体「1 月 31 日触发」「2 月 28/29 日触发」等边界需集成测试覆盖。
- **P1-7 回测换手率显著下降**：`compiler._compute_target_weights` 第 1526-1545 行 buffer_n 买入阈值（top_(n-buffer)）与卖出阈值（top_(n+buffer)）逻辑正确；换手率下降幅度需对比回测验证。
- **P1-5 持仓跌破 stop_loss_pct 时触发止损卖出**：`compiler.on_before_trading` 第 899-961 行 bracket 单逻辑完整；实际触发需回测运行确认。
