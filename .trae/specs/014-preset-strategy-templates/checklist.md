# 预置策略模板库 - Verification Checklist

> **change-id**：014-preset-strategy-templates
> 验证：人工核对 + dev 启动 validator + 接口抽查 + 单元测试。

## Task 0：constants.py 因子白名单对齐（前置）

### 因子一致性
- [x] `constants.TECHNICAL_FACTOR_KEYS` 与 `factors.default.json` 技术面+价格直通因子**完全一致**（按 factors.default.json 精确清点，避免遗漏/多余）
- [x] `constants.FUNDAMENTAL_FACTOR_KEYS` 与 `factors.default.json` 基本面因子**完全一致**（16 个：PE_TTM/PB/PS_TTM/DV_RATIO/TOTAL_MV/CIRC_MV/TURNOVER_RATE/ROE_TTM/ROA_TTM/GROSS_MARGIN/NETPROFIT_MARGIN/REVENUE_YOY/PROFIT_YOY/EPS_YOY/DEBT_TO_ASSETS/CURRENT_RATIO）
- [x] `constants.MULTI_OUTPUT_FACTORS` 含 4 键：MACD/BOLL/KDJ/MAMA
- [x] **僵尸因子已清理**：`REVENUE_GROWTH` / `NET_PROFIT_GROWTH` / `NORTHBOUND_NET_INFLOW` 不在任何集合
- [x] `ALL_FACTOR_KEYS = TECHNICAL ∪ FUNDAMENTAL`，无交集无遗漏

### 单元测试回归
- [x] `pytest stock-engine/tests/services/strategy/test_validator.py` 全绿
- [x] `pytest stock-engine/tests/services/strategy/test_models.py` 全绿
- [x] 测试用例无引用 3 个僵尸因子（若有已同步改名）
- [ ] 新增测试：`DV_RATIO`/`ROC`/`DEBT_TO_ASSETS` 在策略 config 通过 validator（可选，已通过模板校验间接覆盖）

## 文件落地

- [x] 8 个新 JSON 文件全部在 `stock-watcher/src/main/resources/strategies/templates/`：`dividend_low_volatility` / `profit_momentum` / `value_low_pe_pb` / `growth_garp` / `multi_factor_momentum` / `etf_momentum_rotation` / `dual_ma_trend` / `macd_trend_filtered`
- [x] 现有 5 模板未修改（git diff 仅新增 8 文件 + 改 1 个 constants.py）
- [x] 每个 JSON 合法（无尾逗号/注释/单引号）
- [x] 顶层字段：`strategy_id:null` / `name` / `description` / `category` / `tags` / `screen_config` / `trading_config` / `backtest_config`

## 因子白名单合规（C1）

- [x] 所有模板 factorKey 在 Task 0 对齐后的 `ALL_FACTOR_KEYS` 内
- [x] **未引用**僵尸因子 `REVENUE_GROWTH` / `NET_PROFIT_GROWTH` / `NORTHBOUND_NET_INFLOW`
- [x] 多输出因子 output_index 正确：MACD(0/1/2) / BOLL(0/1/2) / KDJ(0/1/2) / MAMA(0/1)
- [x] 红利低波的 `DV_RATIO`/`NATR`/`DEBT_TO_ASSETS`、景气度的 `PROFIT_YOY`/`REVENUE_YOY`、多因子的 `ROC` 均能通过 validator（Task 0 解锁）

## 范式约束（C2 / C3）

- [x] 6 个轮动模板（①-⑥）：`trading_config.rebalance` 在场，`signals` **不在场**
- [x] 2 个择时模板（⑦-⑧）：`trading_config.signals` 在场，`rebalance` **不在场**
- [x] 择时模板 `universe.pool="manual"` 且 `stocks` ≤ 10
- [x] 择时模板 `screen_config` 仅 `universe`，无 `factor`/`filter`/`portfolio`
- [x] **ETF 动量轮动（Task 6）用 `rebalance` 范式 + `universe.pool="manual"` + ETF stocks**（不是 signals，避资金争抢）
- [x] 所有轮动模板 `weight_mode="equal"`

## backtest_config 齐全（C4 足额成本）

- [x] 每个模板 `broker_profile="cn_stock_miniqmt"`
- [x] 每个模板 `t_plus_one=true`
- [x] 每个模板 `slippage` 为 dict `{"type":"percent","value":...}`
- [x] 滑点档位：大盘(csi300/manual ETF)=0.001 / 中盘(csi500)=0.002
- [x] 每个模板 `lot_size=100`
- [x] 技术面模板 `warmup_period` ≥ 最大窗口且 ≤ `history_depth`（双均线 65/80、MACD 强化 70/90、ETF 轮动 25/40）
- [x] 每个模板 `initial_cash` 合理（轮动 ≥ 500000，多因子 ≥ 2000000，ETF/择时 100000）

## transform 能力（C7）

- [x] ETF 动量轮动：`CLOSE` 挂 `transform:{type:"pct_change", window:20}`（transformable:true 合法）
- [x] transform 仅在 `screen_config.filter.conditions`
- [x] transform `window` 1~60
- [x] 未对 `transformable:false` 的因子（PROFIT_YOY/ROE_TTM/DEBT_TO_ASSETS 等季度因子）挂 transform

## A 股实战风控内嵌（C8）—— 评审硬要求

- [x] 每个模板 `t_plus_one=true`（T+1 适配）
- [x] 轮动模板 `reject_limit_up_on_buy=true` / `reject_limit_down_on_sell=true`（涨跌停约束）
- [x] 轮动模板 `exclude_st=true` / `exclude_suspended=true` / `exclude_limit_up=true`（2024 新国九条）
- [x] 轮动模板 `min_list_days` 过滤次新（红利 ≥ 750，价值/多因子 ≥ 500）
- [x] 轮动模板 `top_n ≥ 10`（单票 ≤ 10%）—— **ETF 动量轮动例外**：universe 仅 7 只宽基 ETF，top_n=2 是 spec Scenario 显式要求（集中持有强势 2 只），不适用股票分散原则
- [x] 轮动模板 `max_weight_per_symbol ≤ 0.10` —— **ETF 动量轮动例外**：top_n=2 + equal 权重下单只权重理论 50%，max_weight_per_symbol=0.5 与策略设计自洽
- [x] 每个模板有止损（exit.bracket：stop_loss_pct 或 use_atr_stop）
- [x] **风格切换警示**：红利低波 description 含"防御型"/"牛市跑输"；ETF 轮动含"震荡市假信号"；双均线含"趋势策略天然代价"

## 设计理念对齐（避坑清单）

- [x] 因子数量 ≤ 5（红利 2 打分 / 多因子 5 等权 / 其他 3-4）
- [x] 因子分属不同大类（价值/质量/成长/动量/波动/收益，不重复叠加）
- [x] 条件数量合理（≤ 5-6）
- [x] 价值低估值 PE 设下限 > 5（避周期顶/亏损股）
- [x] 价值低估值 top30 + max_industry_exposure=0.25（分散，避重仓银行地产）
- [x] GARP/景气度加 ROE 支撑（避借债增长/低基数造假）
- [x] GARP 加市值>100 亿（规避 2024 新国九条后微盘风险）

## 老股民评审采纳核对（关键调整）

- [x] **已替换**：静态高 ROE 质量因子 → 景气度盈利动量（profit_momentum）
- [x] **已删除**：布林突破模板（不在 8 个里）
- [x] **已新增**：ETF 动量轮动（etf_momentum_rotation，用 rebalance 避资金争抢）
- [x] **已改良**：双均线改 20/60（dual_ma_trend）；价值季度调仓+PE 下限+top30；GARP PE<30+市值>100 亿；多因子加 ROC 动量+季度调仓；MACD 加长周期趋势过滤
- [x] **红利低波**：未挂 transform（避崩盘股坑），用 TTM 派息+PROFIT_YOY>0 避价值陷阱
- [x] description 标注适用市况与风险（红利防御型/ETF 震荡市假信号/双均线趋势代价）

## 学术/业界依据（模板策略老股民认可）

- [x] dividend_low_volatility：Smart Beta 黄金组合，2022-2024 A 股唯一持续有效
- [x] profit_momentum：A 股成长投资核心打法（替代无效的静态质量因子）
- [x] value_low_pe_pb：Fama-French HML + 邱国鹭流派（改良版避周期陷阱）
- [x] growth_garp：彼得·林奇 GARP（改良版避微盘风险）
- [x] multi_factor_momentum：机构量化黄金标准（加 A 股稳定因子 ROC）
- [x] etf_momentum_rotation：A 股最主流玩法，吃每段主升浪
- [x] dual_ma_trend：20/60 生命线，A 股老手经典趋势跟踪
- [x] macd_trend_filtered：MACD 全球最广泛动量指标 + 长周期趋势过滤

## 启动加载验证（Task 9）

- [ ] dev profile 启动 watcher，日志「策略模板加载完成：共 13 个」（engine 侧三段式校验已全 PASS，watcher 启动验证待用户环境确认）
- [ ] 无「策略模板 [xxx] 启动校验失败」WARN（engine 可达时）（待 watcher 环境）
- [ ] `GET /python/v1/strategies/templates` 返回 13 项，含 8 个新 templateId（待 watcher 环境）
- [ ] `GET /python/v1/strategies/templates/etf_momentum_rotation` configJson 完整（待 watcher 环境）
- [ ] configJson 不含 `category`/`tags`/`scope`（loader 剔除）（loader 逻辑已确认，待 watcher 环境）

## 从模板创建策略端到端

- [ ] 前端「从模板创建」下拉显示 8 个新模板（待用户环境端到端验证）
- [ ] 选「ETF 动量轮动」后编辑器各 Tab 填充完整（待用户环境端到端验证）
- [ ] 保存成功（validate 通过，无 UNKNOWN_FACTOR）（待用户环境端到端验证）
- [ ] 触发回测正常返回 BacktestResult（无编译错误、无 NaN 空持仓）（待用户环境端到端验证）
- [ ] **ETF 轮动回测**：确认 rebalance + manual universe 被 watcher 接受（若不接受，记录 fallback 方案）（待用户环境端到端验证）

## 可扩展性验证

- [x] 新增模板仅丢 JSON 即可（无需改 loader/controller/Java 代码）（loader 代码已确认无需改）
- [x] `templateId` = 文件名（不含 .json）

## 实现验证结果（Task 9 已完成）

- engine 侧三段式校验（screen 结构预检 + Pydantic + 业务 validator）：**13 个模板全部 ALL PASS**（8 新 + 5 旧）
- pytest tests/services/strategy/：**86 passed**（含 test_validator / test_models / test_api，Task 0 僵尸因子清理无回归）
- 核验 34 项检查点：30 PASS / 4 FAIL，FAIL 已全部处理：
  - value_low_pe_pb 滑点档位 0.002→0.001（csi300 大盘档）—— 已修复
  - etf_momentum_rotation exclude_st/exclude_limit_up false→true —— 已修复（对 ETF 无实质影响）
  - etf_momentum_rotation top_n=2 / max_weight=0.5 —— 策略设计核心（spec Scenario 显式要求），加 ETF 豁免说明，不改
- watcher 侧启动加载 + 前端端到端：待用户环境验证（loader 逻辑已源码确认无需改 Java 代码）

## 后续跟进（不在本 spec 范围，统一整理到 PRD）

> 详细方案见 `sdlc/prd/010-策略模板能力扩展/策略模板能力扩展PRD.md`，按优先级排队单独开发。

- [ ] **GRID 网格参数寻优**（用户澄清的"网格"指此）：`aq.run_grid_search` 参数寻优，属回测中心 spec 008 第三波（paramGrid schema 反推 + 结构化 DSL），与预置模板正交，独立 spec 跟进
- [ ] **网格交易策略**（grid trading 区间挂单）：经核实当前 Schema 用固定阈值+position_sizing 只能做"单格抄底逃顶"，真网格需扩展 Schema（多档 position_sizing 或 grid 订单类型），独立 PRD 跟进
- [ ] **可转债双低策略**：待可转债数据 + 因子库扩展（下修/强赎逻辑、双低=价格+溢价率×100）
- [ ] **行业动量轮动**：A 股主流玩法之一，待行业 ETF universe 池 + 动量因子扩展
- [ ] 严格 point-in-time 成分股：待 spec 010 滚动快照
