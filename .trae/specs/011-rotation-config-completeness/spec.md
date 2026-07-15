# 轮动策略配置完整性与交互优化 Spec

> **change-id**：011-rotation-config-completeness
> **来源 PRD**：`sdlc/prd/008-轮动策略配置治理/轮动策略配置完整性与交互优化PRD.md`
> **对齐**：akquant 0.2.47 · 统一策略配置 Schema v1.0 · 007 轮动范式未来数据治理 · 009 策略范式互斥

## Why

对「策略管理 - 轮动范式」全链路做 akquant 源码级核对后发现 **21 类系统性问题**：4 类功能空壳/逻辑断层、3 类回测可信度致命缺陷（回测数字虚高）、若干交互与实战可用性短板。核心矛盾是「用户配置的项被回测静默忽略」与「回测假设的成交在实盘根本做不到」，导致回测结果不可信、老股民不愿用。本 spec 把 PRD 的全部改造项落地为可执行、可验收的工作单元。

## What Changes

### 一期（P0 + P1，本 spec 必交付）

- **P0-0 申万行业分类对接**：watcher 落库 `sw_industry` + `sw_industry_member` 两表（2021 版），经 kline_data 下发 `sw_industry_l1`。
- **P0-1 静态过滤接通**：engine `rebalance_engine` 过滤链路插入 `_filter_by_static_rules`，复用 screener `apply_static_filters`，处理 7 项静态字段。
- **P0-2 score + single 禁用**：compiler + validator 增加 `FACTOR_SCORE_INCOMPATIBLE` 跨字段校验；前端联动置灰。
- **P0-3 调仓成交价默认当日收盘**：runner 轮动范式默认 `fill_policy=close/same_cycle/bar_offset=0`；前端 Tab8 默认对齐。
- **P0-4 涨停拒买 / 跌停拒卖**：自托管权重后剔除当日涨停买入标的、跌停卖出标的；新增 `reject_limit_up_on_buy` / `reject_limit_down_on_sell`。
- **P0-5 资金不足按 score 优先 + 诊断回传**：target_weights 按 score 降序后下单；`result_serializer` 增加 `rebalance_diagnosis`；前端展示。
- **P1-1 point_in_time 强制**：删 `UniverseModel.point_in_time`；所有 universe 强制查 watcher；失败抛 `PIT_*` 错误码；前端移除开关。
- **P1-2 权重面板重设计**：factorKey 联想输入 + 公式说明 + 方向切换 + 组合校验 + 归一化实时提示（含 P2-7）。
- **P1-3 cash_reserve_pct**：PortfolioModel 加字段；engine 切自托管权重（弃 `rebalance_to_topn`，改 `order_target_weights`）。
- **P1-4 单标的上限 / 行业暴露上限**：PortfolioModel 加 `max_weight_per_symbol` / `max_industry_exposure`；engine 权重后处理。
- **P1-5 轮动范式 Tab6 止损止盈可见**：editor.html Tab6 `data-paradigm` 由 `signals` 改 `both`；validator 放开 rebalance+exit。
- **P1-7 换仓缓冲带 buffer_n**：PortfolioModel 加 `buffer_n`；买入门槛 `top_(n-buffer)`、卖出门槛 `top_(n+buffer)`。
- **P2-1 trade_cal 预计算标记 + trigger 语义**：watcher trade_cal 加 6 个 `is_*_of_*` 标记；RebalanceModel `day_of_period` 替换为 `trigger: Literal["first","last"]`；engine 删 `_is_rebalance_trigger_day` 改查 bar 标记（零状态）；前端 trigger 下拉随 frequency 动态显隐。
- **P2-2 / P2-3 fill_policy / risk_config 前端控件**：Tab8 暴露成交语义与风控常用项。
- **P2-4 最小持仓周期**：RebalanceModel 加 `min_holding_bars`；持仓不足周期不卖出。
- **P2-5 warmup 实际值回传**：result_serializer 回传 `effective_config.warmup_period` + source + reason；前端展示。
- **P2-6 条件树模式切换双向同步**：可视化↔JSON 切换前确认框 + 双向同步防丢数据。
- **P2-8 年化换手率指标**：result_serializer 增加 `annual_turnover_ratio`；前端展示。

### 二期/三期（本 spec 登记但不交付，单独 spec 跟进）

- **P1-6 FactorNode transform（滚动窗口聚合）**：架构改动较大，二期。
- **P2-9 分批调仓 + 冲击成本建模**：三期。

### 跨字段校验新增（**BREAKING** 风险点）

- `FACTOR_SCORE_INCOMPATIBLE`：`factor.method=single` + `rebalance.weight_mode=score` 禁用。
- `INVALID_REBALANCE_TRIGGER`：`trigger` 非 first/last/None 报错（替代 `REBALANCE_DAY_OUT_OF_RANGE`）。
- `PIT_WATCHER_UNAVAILABLE` / `PIT_CONSTITUENTS_EMPTY` / `PIT_QUERY_FAILED`：point_in_time 强制后的失败码。

### 向后兼容（旧策略 JSON）

- 旧 JSON 含 `point_in_time` 字段：UniverseModel 保留字段但忽略取值 + deprecation warning。
- 旧 JSON 含 `day_of_period` 字段：RebalanceModel 临时保留，`day_of_period<=1 → trigger="first"` 自动映射 + deprecation warning。

## Impact

- **Affected specs**：
  - 007-rotation-data-governance（P1-1 是其 point-in-time 能力的收紧延续）
  - 009-strategy-paradigm-exclusive（rebalance + exit 组合放开，需复核范式互斥边界）
  - 统一策略配置 Schema v1.0（UniverseModel / PortfolioModel / RebalanceModel / FactorNode 字段变更）
- **Affected code**：
  - engine（Python）：`services/strategy/{models,validator,errors,constants}.py`、`services/backtest/{rebalance_engine,compiler,runner,data_adapter,result_serializer}.py`
  - watcher（Java）：`constant/{StrategySchemaConstants,TushareApiEnum,InitStep}.java`、`resources/schema-{mysql,sqlite}.sql`、`service/impl/{DataInitServiceImpl,TradeCalServiceImpl,BacktestServiceImpl,SwIndustryServiceImpl}.java`、`model/SwIndustry*.java`、`mapper/SwIndustry*Mapper.java`、`dto/tushare/IndexClassify*.java`、`client/TushareClient.java`、`task/SwIndustryTask.java`、`application.yml`
  - 前端：`templates/quant/strategies/editor.html`、`static/js/strategy-editor.js`、回测结果展示页

## ADDED Requirements

### Requirement: 申万行业分类数据落库与下发（P0-0）

系统 SHALL 通过 Tushare `index_classify`（doc_id=181）+ `index_member_all`（doc_id=335）接口，按 SWS2021 版本落库 `sw_industry`（目录树）与 `sw_industry_member`（成分归属）两表，并提供全量初始化入口（`InitStep.SW_INDUSTRY`）、半年定时同步（cron `0 0 22 1 1,7 *`）、按 ts_code 查询申万一级行业的接口。回测路径 SHALL 经 kline_data 下发每只标的的 `sw_industry_l1` 字段。

#### Scenario: 一键初始化落库
- **WHEN** 触发 DataInit 的 SW_INDUSTRY 步骤
- **THEN** `sw_industry` 落库 31 一级 + 134 二级 + 346 三级；`sw_industry_member` 落库约 5000+ 条 `is_new=1` 记录

#### Scenario: 查询某股行业
- **WHEN** 调用 `selectLatestL1ByTsCode("000001.SZ")`
- **THEN** 返回「银行」

#### Scenario: 三处 DDL 同步
- **WHEN** 切换 db-type 为 sqlite 或走 schema 文件初始化
- **THEN** `sw_industry` / `sw_industry_member` 两表均存在（schema-mysql.sql / schema-sqlite.sql / DataInitServiceImpl Map 三处同步）

### Requirement: 回测路径静态过滤接通（P0-1）

系统 SHALL 在 `rebalance_engine.select_at_rebalance_date` 过滤链路中，于 `_filter_valid_symbols` 之后、`_filter_by_conditions` 之前插入 `_filter_by_static_rules`，复用 screener `apply_static_filters` 处理 7 项静态字段。所需元数据（is_st / is_suspended / is_limit_up / is_limit_down / industry / list_date）SHALL 由 watcher 经 kline_data 的 extra 下发。

#### Scenario: 排除 ST 生效
- **WHEN** 配置 `exclude_st=true` 且候选含 ST 标的
- **THEN** 回测日志输出「剔除 ST: N 只」，ST 标的不进入 target_weights

#### Scenario: 上市天数过滤
- **WHEN** 配置 `min_list_days=250` 且某标的 list_days < 250
- **THEN** 该次新股被剔除

#### Scenario: 元数据缺失降级
- **WHEN** extra 缺失某元数据字段（如 industry）
- **THEN** 对应过滤项静默跳过 + warning，不阻断回测

### Requirement: score + single 组合禁用（P0-2）

系统 SHALL 在 compiler 与 validator 双层拦截 `factor.method=single` + `rebalance.weight_mode=score` 组合，错误码 `FACTOR_SCORE_INCOMPATIBLE`。前端 SHALL 在 method=single 时置灰 score radio 并提示。

#### Scenario: 后端拦截
- **WHEN** 提交 single + score 组合配置
- **THEN** validator/compiler 报 `FACTOR_SCORE_INCOMPATIBLE`

#### Scenario: composite + score 仍可用
- **WHEN** 提交 composite + score 组合
- **THEN** 校验通过

### Requirement: 轮动范式默认当日收盘成交（P0-3）

系统 SHALL 对轮动范式（has_rebalance）且未显式配置 fill_policy 时，默认采用 `price_basis=close, temporal=same_cycle, bar_offset=0`。前端 Tab8 fill_policy 控件 SHALL 在轮动范式下默认选中「当日收盘」并标注「推荐·尾盘调仓」。

#### Scenario: 默认成交价
- **WHEN** 轮动范式不填 fill_policy
- **THEN** trades_df 的 entry_price = 选股日 close（非次日 open）

#### Scenario: 用户可切回
- **WHEN** 用户手动选「次日开盘」
- **THEN** 按次日 open 成交

### Requirement: 涨停拒买 / 跌停拒卖（P0-4）

系统 SHALL 在自托管权重计算后、调用 `order_target_weights` 前，对买入方向且当日 `is_limit_up=1` 的标的从 target_weights 剔除，对卖出方向且当日 `is_limit_down=1` 的标的保留原持仓。新增配置 `reject_limit_up_on_buy: bool = True`、`reject_limit_down_on_sell: bool = True`。

#### Scenario: 涨停拒买
- **WHEN** 目标买入标的当日涨停且 `reject_limit_up_on_buy=True`
- **THEN** 该标的从 target_weights 剔除，日志输出「涨停拒买: XXX」，释放权重转现金

#### Scenario: 关闭对比
- **WHEN** `reject_limit_up_on_buy=False`
- **THEN** 涨停标的仍尝试买入（用于对比回测）

### Requirement: 资金不足按 score 优先 + 诊断回传（P0-5）

系统 SHALL 在计算 target_weights 后按 score 降序排列，确保高分标的优先获得资金。`result_serializer` SHALL 回传 `rebalance_diagnosis`（selected_count / actually_bought / rejected_by_cash / rejected_by_limit_up / actual_invest_ratio）。前端 SHALL 展示「实际成交 X/Y 只，拒单原因」。

#### Scenario: score 降序
- **WHEN** 资金不足以买入全部 top_n
- **THEN** 高分标的优先成交，低分标的先被拒

#### Scenario: 诊断回传
- **THEN** 回测结果含 rebalance_diagnosis 字段且数值合理

### Requirement: point_in_time 强制开启（P1-1）

系统 SHALL 删除 `UniverseModel.point_in_time` 字段，对所有 universe 类型强制查询 watcher 成分股并按 point-in-time 过滤。watcher_client 缺失或查询失败/返回空时 SHALL 抛 `PIT_WATCHER_UNAVAILABLE` / `PIT_CONSTITUENTS_EMPTY` / `PIT_QUERY_FAILED` 使回测失败。前端 SHALL 移除 point_in_time 开关。

#### Scenario: watcher 未配置
- **WHEN** watcher_client=None
- **THEN** 回测失败，错误码 `PIT_WATCHER_UNAVAILABLE`

#### Scenario: 查询返回空
- **WHEN** watcher 查询返回空
- **THEN** 回测失败，错误码 `PIT_CONSTITUENTS_EMPTY`

#### Scenario: 旧 JSON 兼容
- **WHEN** 旧策略 JSON 含 point_in_time 字段
- **THEN** 不报错，打 deprecation warning

### Requirement: 权重面板重设计（P1-2 + P2-7）

前端 SHALL 把 Factor 层权重输入改为 factorKey 联想输入（`<datalist>`，数据源与 Tab2b 一致）、增加方向切换按钮（越大越好→正 / 越小越好→负）、公式说明可折叠区块、组合校验（composite+空 weights / 权重全 0 / factorKey 重复 / 不在白名单）、归一化实时提示。

#### Scenario: factorKey 只能选不能敲
- **THEN** 权重输入只能从联想列表选

#### Scenario: 方向切换
- **WHEN** 用户点「越小越好」
- **THEN** 该因子权重为负，提交时按 方向×绝对值 写入

#### Scenario: 归一化提示
- **THEN** 权重面板底部实时显示归一化后实际权重百分比

### Requirement: 现金保留比例 cash_reserve_pct（P1-3）

系统 SHALL 在 PortfolioModel 增加 `cash_reserve_pct: float ∈ [0, 0.95]`，目标权重 = `(1 - cash_reserve_pct) / N`。engine SHALL 切换为自托管权重计算（弃 `rebalance_to_topn`，改 `order_target_weights`），保留 `USE_LEGACY_REBALANCE=true` 作为回滚 feature flag。

#### Scenario: 保留 20% 现金
- **WHEN** `cash_reserve_pct=0.2, top_n=30`
- **THEN** 总权重 0.8，每只约 0.0267

#### Scenario: 零保留行为不变
- **WHEN** `cash_reserve_pct=0`
- **THEN** 行为与现状一致（除取整损耗）

### Requirement: 单标的上限 / 行业暴露上限（P1-4）

系统 SHALL 在 PortfolioModel 增加 `max_weight_per_symbol` / `max_industry_exposure`。engine SHALL 在自托管权重后做后处理：单标的超限截断后重新等分；行业超限按比例缩减（释放权重转现金，不重分配），含迭代防连锁。

#### Scenario: 单标的上限
- **WHEN** `max_weight_per_symbol=0.1` 且某标的权重 > 0.1
- **THEN** 截断到 0.1，超出部分重新等分给其他标的

#### Scenario: 行业暴露上限
- **WHEN** `max_industry_exposure=0.3` 且某行业总权重 0.40
- **THEN** 该行业每只按 scale=0.75 缩减，总权重降到 0.3，释放 0.10 转现金

#### Scenario: industry 元数据缺失
- **THEN** 行业暴露项静默跳过 + warning

### Requirement: 轮动范式 Tab6 止损止盈可见（P1-5）

前端 SHALL 把 editor.html Tab6 的 `data-paradigm` 由 `signals` 改为 `both`（bracket 与 rules 对轮动可见，position_sizing 对轮动隐藏）。validator SHALL 放开 rebalance+exit 组合。

#### Scenario: Tab6 可见
- **THEN** 轮动范式下 Tab6 止损止盈可见可配

#### Scenario: 组合可回测
- **WHEN** rebalance + exit.bracket
- **THEN** 可保存可回测，持仓跌破 stop_loss_pct 触发止损

### Requirement: 换仓缓冲带 buffer_n（P1-7）

系统 SHALL 在 PortfolioModel 增加 `buffer_n: int ≥ 0`，买入取 `top_(n-buffer)`，卖出取 `top_(n+buffer)`。

#### Scenario: 缓冲生效
- **WHEN** `buffer_n=5, top_n=20`
- **THEN** 新标的需进入 top 15 才买入；持仓标的跌出 top 25 才卖出；换手率下降

### Requirement: trade_cal 预计算标记 + trigger 语义（P2-1）

系统 SHALL 在 watcher trade_cal 表新增 6 个 `is_*_of_*` 标记字段（三处 DDL 同步），同步时预计算；RebalanceModel 的 `day_of_period: int` SHALL 替换为 `trigger: Literal["first","last"]`；engine SHALL 删除 `_is_rebalance_trigger_day` 改查 bar 的 trade_cal 标记（零状态）；前端 trigger 下拉 SHALL 随 frequency 动态显隐。

#### Scenario: 月末调仓
- **WHEN** `monthly + trigger=last`
- **THEN** 每月末个交易日触发（如 1 月 31 日）

#### Scenario: 月初调仓
- **WHEN** `monthly + trigger=first`
- **THEN** 每月首个交易日触发（避开周末）

#### Scenario: daily 忽略 trigger
- **WHEN** `frequency=daily`
- **THEN** 每个交易日触发，trigger 下拉隐藏

#### Scenario: 旧 JSON 兼容
- **WHEN** 旧策略 JSON 含 day_of_period
- **THEN** 自动映射 trigger + deprecation warning

### Requirement: fill_policy / risk_config 前端控件（P2-2 / P2-3）

前端 SHALL 在 Tab8 暴露 fill_policy 下拉与 risk_config 折叠面板（max_position_pct / max_drawdown_pct 等）。

### Requirement: 最小持仓周期（P2-4）

系统 SHALL 在 RebalanceModel 增加 `min_holding_bars: int ≥ 0`，持仓中且 `hold_bar() < min_holding_bars` 的标的保留原权重不卖出。

### Requirement: warmup 实际值回传（P2-5）

`result_serializer` SHALL 回传 `effective_config.warmup_period` + `warmup_source`（auto_inferred/user_override）+ `warmup_reason`。前端 SHALL 展示实际 warmup。

### Requirement: 条件树模式切换双向同步（P2-6）

前端 SHALL 确保可视化↔JSON 模式切换双向同步，切换前弹确认框，防丢数据。

### Requirement: 年化换手率指标（P2-8）

`result_serializer` SHALL 计算 `annual_turnover_ratio = 期间总成交额 / 平均持仓市值 × 年化因子`，加入 metrics。前端 SHALL 展示。

## MODIFIED Requirements

### Requirement: 轮动范式调仓成交语义（受 P0-3 / P0-4 / P0-5 / P1-3 共同影响）

轮动范式调仓 SHALL 由 engine 自托管权重计算（基于 score 降序 + cash_reserve + 单标的/行业上限 + buffer + 涨跌停拒单后处理），调 `order_target_weights` 而非 akquant `rebalance_to_topn`；默认 fill_policy 为当日收盘成交。`USE_LEGACY_REBALANCE=true` 时回切旧路径。

## REMOVED Requirements

### Requirement: point_in_time 可选 + 兜底降级

**Reason**：关闭 point_in_time = lookahead bias = 数据错误，不应提供兜底（007 已建设能力，本 spec 收紧为强���）。
**Migration**：旧 JSON 含 point_in_time 字段保留但忽略 + deprecation warning；前端移除开关；watcher 常量移除白名单。

### Requirement: day_of_period 自然日判定

**Reason**：自然日判定导致 off-by-one、不支持月末调仓。
**Migration**：替换为 trade_cal 预计算标记 + trigger 语义；旧 JSON `day_of_period<=1 → trigger="first"` 自动映射 + deprecation warning。
