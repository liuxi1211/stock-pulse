# 预置策略模板库 Spec

> **change-id**：014-preset-strategy-templates
> **对齐**：统一策略配置 Schema v1.0 · akquant 0.2.47 · `.trae/rules/系统设计理念(用法建议).md`
> **核心交付**：新增 8 个策略模板 JSON 文件（放 `stock-watcher/src/main/resources/strategies/templates/`）
> **配套小改**：同步 `stock-engine/services/strategy/constants.py` 因子白名单与 `factors.default.json` 对齐（解锁红利/低波/财务结构等因子）

## Why

策略管理「新增策略」目前门槛高：用户面对 8 个 Tab、几十个可配置参数，很难从零拼出一份科学、能跑、可信的策略。现有 5 个模板（`dual_ma` / `macd_short` / `volume_price` / `low_pe_value` / `small_cap`）偏简单示意，配置不齐全（缺 `slippage` dict、缺 `max_weight_per_symbol` 等关键风控项），未充分体现避坑清单要求，且学术味偏重、缺少 A 股实战主流玩法。

本 spec 预置一批 **A 股多年���淀、老股民/业界公认有效** 的策略模板。清单已经「20 年 A 股实战老股民视角」评审反复打磨（详见 `## 策略选型评审过程`），剔除了学术正确但实战一般的策略（如静态高 ROE 质量因子、布林突破），补入了 A 股真正主流的 ETF 动量轮动、景气度盈利动量等玩法。让用户「一键创建」即得到配置齐全、参数合理、附带实战提醒的完整策略；后续添加新模板只需往 `templates` 目录丢一个 JSON。

**核心质量准绳**：
1. `.trae/rules/系统设计理念(用法建议).md`（个人因子选股避坑清单）—— 足额成本 / 仓位风控 / 逻辑简洁 / 幸存者偏差防护；
2. **A 股实战派认可**（非纯学术）—— 优先选择 2015-2025 周期检验过、机构/老股民真实在用的策略，警惕价值陷阱、风格切换、2024 新国九条后小市值失效等 A 股特有风险。

## 策略选型评审过程

本清单经「20 年 A 股实战老股民视角」子代理评审，对原方案的调整：

| 原方案 | 评审结论 | 处理 |
|---|---|---|
| 静态高 ROE 质量因子（对标 Fama-French 五因子） | ❌ A 股无独立 alpha，2021 抱团瓦解后白马连跌 3 年，本质是"外资流入推升"非质量因子本身。A 股真正有效的是**景气度盈利动量** | **替换**为景气度盈利动量 |
| 布林突破（突破上轨+放量） | ❌ A 股名声差，游资打板/诱多套路，系统化胜率极低，T+1 下追高被套无法当日止损 | **删除** |
| 多因子等权（学术版） | ⚠️ 等权是上个时代做法，A 股因子有效性轮动剧烈，永远拿平均跑不赢当期主流风格 | **改良**：加入动量因子 + 季度调仓 |
| 价值低估值（原版） | ⚠️ 月度调仓太频繁、未剔除周期股价值陷阱（钢铁/化工/航运顶部低 PE）、top20 集中度高 | **改良**：季度调仓 + PE 设下限 + top30 + 行业分散 |
| 红利低波 | ✅ 2022-2024 唯一稳定有效的 Smart Beta，但需标注防御属性、股息率算法需避坑 | **保留+标注**：标"牛市跑输正常"+ TTM 派息逻辑 |
| GARP 成长 | ⚠️ PE<50 太宽松、PROFIT_YOY 单期易骗（低基数/补贴/卖资产）、2024 后中证500 风格受损 | **改良**：PE<30 + 市值>100 亿 + ROE 支撑 |
| 双均线趋势 | ✅ 经典有效 | **保留**：均线改 20/60（A 股老手"生命线"） |
| MACD 趋势 | ⚠️ 与双均线重复，震荡市假信号泛滥 | **保留但强化**：月线趋势过滤 |

**新增**（评审强烈推荐 A 股实战主流玩法）：
- **ETF 动量轮动**：A 股最主流玩法之一，宽基 ETF 间选最强，吃每段主升浪，过去 10 年年化 15-20%。

**评审推荐但本 spec 不做**（与预置模板正交或超出当前能力，统一整理到独立 PRD `sdlc/prd/010-策略模板能力扩展/`，稍后单独开发）：
- **GRID 网格参数寻优 / Walk-Forward**：用户提到的"网格"指此（`aq.run_grid_search` 参数寻优），属于**回测中心**能力（spec 008 第三波，前置 OQ-3 paramGrid schema 反推 + 结构化 DSL），是给已创建策略做参数优化的，与"预置模板快速创建策略"是正交的两件事——不在本 spec 范围。
- **网格交易策略**（grid trading，震荡市区间挂单赚差价）：经核实，当前 Schema 用 `signals + 固定阈值（CLOSE<9.8 / CLOSE>10.2）+ position_sizing` 虽然能过 validator，但只能表达"单格抄底逃顶"，无法实现真网格的"分多格建仓/分多格平仓/网格触发后去重"（position_sizing 全局只有一组 method/target，且 exit.rules 只能平仓不能加仓）。需扩展 Schema（多档 position_sizing 或 grid 订单类型），独立 PRD 跟进。
- **可转债双低策略**：需可转债数据、下修/强赎逻辑、双低=价格+溢价率×100 计算，当前因子库与 Schema 无支持，独立 PRD 跟进。
- **大小盘风格择时**：可做但与 ETF 动量轮动高度同质（均为多标的动量 rebalance），合并到 ETF 轮动模板（universe 含大小盘 ETF）。

> **保守模式**：本批 8 个模板只做当前 Schema 完整支持、能跑通端到端回测的策略。上述"有意义但当前不支持"的能力统一整理到 `sdlc/prd/010-策略模板能力扩展/策略模板能力扩展PRD.md`，稍后单独开发，不在本 spec 范围。

## 关键发现：因子白名单不一致（必须在模板前修复）

策略 validator（`constants.ALL_FACTOR_KEYS`，30 个）与因子库（`factors.default.json`，54 个）存在**双向不一致**：

1. **factors.default.json 有、constants.py 缺失**（24 个）：`DV_RATIO`(股息率) / `DEBT_TO_ASSETS`(资产负债率) / `NATR`(归一化波动) / `STDDEV` / `ROC`(变动率) / `CIRC_MV`(流通市值) / `PS_TTM` / `ROA_TTM` / `NETPROFIT_MARGIN` / `REVENUE_YOY` / `PROFIT_YOY` / `EPS_YOY` / `WMA` / `DEMA` / `TEMA` / `TRIMA` / `KAMA` / `T3` / `MAMA` / `MOM` / `APO` / `PPO` / `TRIX` / `TRANGE` / `AD` / `ADOSC` / `OPEN`。运行时能算，但 validator 以 `UNKNOWN_FACTOR` 拦截。
2. **constants.py 有、factors.default.json 缺失**（3 个僵尸因子）：`REVENUE_GROWTH` / `NET_PROFIT_GROWTH` / `NORTHBOUND_NET_INFLOW`。validator 放行，但运行时 `factor_registry.exists()` 返回 `False`，因子值静默回退 `NaN`，**策略静默失效**。
3. **transformable 能力**：12 个因子 `transformable:true`（OHLCV + daily_basic 类估值因子），支持 `transform:{type:"ma/std/pct_change/max/min", window:N}` 滚动窗口聚合，可表达"动量""均值回归""稳定性"等进阶逻辑。

**配套小改（Task 0）**：将 `constants.py` 三集合与 `factors.default.json` 对齐（**BREAKING 仅限僵尸因子清理**），validator 逻辑无需改（本就读 `ALL_FACTOR_KEYS`）。这是模板能用红利/低波/动量/财务结构等因子的前提。

## What Changes

### 一、配套小改：constants.py 因子白名单对齐（Task 0，前置）

修改 `stock-engine/services/strategy/constants.py`：
1. `TECHNICAL_FACTOR_KEYS` 补齐 18 个技术/价格因子（`WMA, DEMA, TEMA, TRIMA, KAMA, T3, MAMA, ROC, MOM, APO, PPO, TRIX, NATR, TRANGE, STDDEV, AD, ADOSC, OPEN`）
2. `FUNDAMENTAL_FACTOR_KEYS` 补齐 9 个 + 清理 3 个僵尸：
   - 补齐：`PS_TTM, DV_RATIO, CIRC_MV, ROA_TTM, NETPROFIT_MARGIN, REVENUE_YOY, PROFIT_YOY, EPS_YOY, DEBT_TO_ASSETS`
   - **BREAKING** 清理：`REVENUE_GROWTH`→`REVENUE_YOY`、`NET_PROFIT_GROWTH`→`PROFIT_YOY`、删除 `NORTHBOUND_NET_INFLOW`
3. `MULTI_OUTPUT_FACTORS` 增补 `MAMA: ["MAMA","FAMA"]`

向后兼容：现有 5 模板未使用被清理的僵尸因子（已核对），不受影响。

### 二、新增 8 个预置策略模板（Task 1-8）

**轮动范式（portfolio，6 个）—— 截面选股 + 定期调仓**

| # | 文件名 | 中文名 | 核心逻辑 | 实战依据 |
|---|---|---|---|---|
| ① | `dividend_low_volatility.json` | 红利低波 | 高 DV_RATIO + 低 NATR + 低 DEBT_TO_ASSETS + 大市值 | Smart Beta 黄金组合，2022-2024 A 股唯一持续有效（依赖 Task 0） |
| ② | `profit_momentum.json` | 景气度盈利动量 | 高 PROFIT_YOY + 高 REVENUE_YOY + 合理 PE + ROE 支撑 | A 股成长投资真功夫，机构核心打法（替代静态高 ROE 质量因子） |
| ③ | `value_low_pe_pb.json` | 价值低估值（改良） | 低 PE + 低 PB + 高 ROE + PE 设下限避周期顶 | Fama-French HML，邱国鹭《投资中最简单的事》流派 |
| ④ | `growth_garp.json` | GARP 成长（改良） | 高利润增 + 高收入增 + PE<30 + 市值>100 亿 | 彼得·林奇 GARP，规避微盘风险 |
| ⑤ | `multi_factor_momentum.json` | 多因子含动量（改良） | composite：PE(-) + ROE(+) + PROFIT_YOY(+) + ROC(动量,+) + NATR(-)，季度调仓 | 机构量化黄金标准，加 A 股稳定因子 ROC |
| ⑥ | `etf_momentum_rotation.json` | ETF 动量轮动 | universe=7 只主流宽基 ETF，按 ROC(20日涨幅) 动量打分选 top2 | A 股最主流玩法，吃每段主升浪 |

**择时范式（single，2 个）—— 单标的趋势跟踪**

| # | 文件名 | 中文名 | 核心逻辑 | 实战依据 |
|---|---|---|---|---|
| ⑦ | `dual_ma_trend.json` | 双均线趋势（改良） | MA20/MA60 金叉 + ADX>25 + ATR 止损 | A 股老手"生命线"组合，大行情利器 |
| ⑧ | `macd_trend_filtered.json` | MACD 趋势（强化） | 周线 MACD 金叉 + 日线柱状图转正 + RSI<70 | MACD 全球最广泛动量指标，加周线过滤降假信号 |

### 三、充分利用 transformable 能力

- **ETF 动量轮动**：`CLOSE` 挂 `transform:{type:"pct_change", window:20}`（20 日涨幅=动量，transformable:true 的 CLOSE 合法）
- **景气度盈利动量**：`PROFIT_YOY` 不挂 transform（fina_indicator 季度披露，transformable:false，日频滚动无意义）
- **红利低波**：`DV_RATIO` 不挂 transform（评审指出 60 日均值会选到崩盘股，改用 TTM 派息逻辑，由 watcher 数据侧保证）

> validator 不检查 factorKey 是否 transformable，但模板只在 factors.default.json 标注 `transformable:true` 的因子上挂 transform。

## Impact

- **Affected specs**：009-strategy-paradigm-exclusive（模板严格遵守 signals/rebalance 互斥）；011-rotation-config-completeness（轮动模板用 `portfolio.max_weight_per_symbol` / `cash_reserve_pct` / `buffer_n` / `max_industry_exposure`）；统一策略配置 Schema v1.0（constants.py 因子集合扩展）
- **Affected code**：`stock-engine/services/strategy/constants.py`（Task 0，唯一代码改动）；模板加载链路（`StrategyTemplateLoader` / `QuantStrategyController`）**无需改动**
- **Affected files**：修改 1 个 `constants.py` + 新增 8 个 templates JSON

## 关键约束（实现必须遵守）

### C1. 因子白名单（Task 0 完成后）

模板**只能用** Task 0 对齐后的 `ALL_FACTOR_KEYS`（= factors.default.json 全集 54 个）。**禁止用**僵尸因子 `REVENUE_GROWTH` / `NET_PROFIT_GROWTH` / `NORTHBOUND_NET_INFLOW`（运行时回退 NaN）。

### C2. signals 范式 universe 约束

- `trading_config.signals` 在场时，`screen_config.universe.pool` **必须** = `"manual"` 且 `stocks` ≤ 10。
- signals 范式下 `screen_config` 仅允许 `universe`，**禁止** `factor` / `filter` / `portfolio`（报 `SIGNALS_SCREEN_CONFIG_FORBIDDEN`）。

### C3. rebalance 范式约束（ETF 轮动/多标的轮动的关键技术点）

- **ETF 动量轮动 / 多标的轮动 用 `rebalance` + `universe.pool="manual"` + `stocks=[ETF 列表]`**，**不用 signals**（signals 多标的会资金争抢、不可复现，spec 009 缺陷 B）。
- validator 不限制 rebalance 范式的 universe.pool 取值（manual/csi300/csi500/all_a_shares 均可），故 rebalance+manual+ETF 列表合法。
- `factor.method="single"` + `rebalance.weight_mode="score"` 互斥；本 spec 统一 `weight_mode="equal"`。
- `rebalance.execution`（分批调仓+冲击成本）仅轮动合法。

### C4. backtest_config 必须齐全（足额成本）

每个模板显式：`broker_profile="cn_stock_miniqmt"` / `t_plus_one=true` / `slippage` dict（大盘 0.001 / 中盘 0.002 / 小市值 0.003）/ `lot_size=100` / `warmup_period` ≥ 最大历史窗口 / `history_depth` ≥ warmup / `volume_limit_pct=0.2`（小市值 0.15）/ `initial_cash`（轮动 ≥ 500000，多因子 ≥ 2000000，ETF 轮动 100000）。

### C5. 模板 JSON 顶层结构

```json
{
  "strategy_id": null,
  "name": "<展示名>",
  "description": "<一句话描述，含核心逻辑与适用市况>",
  "category": "TECHNICAL | FUNDAMENTAL",
  "tags": ["<逻辑标签>", "<风格标签>"],
  "screen_config": {...},
  "trading_config": {...},
  "backtest_config": {...}
}
```

> loader 从顶层提取 `name/description/category`；`tags`/`strategy_id` 不进 `configJson`；scope 从 trading_config 派生。

### C6. 多输出因子 output_index 映射
- MACD：0=dif / 1=dea / 2=hist
- BOLL：0=upper / 1=mid / 2=lower
- KDJ：0=k / 1=d / 2=j
- MAMA（Task 0 后）：0=MAMA / 1=FAMA

### C7. transform 作用域（仅 screen_config.filter.conditions）
- transform 仅在 `screen_config.filter.conditions` 内合法；trading_config 内挂会被报 `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN`。
- `window` 1~60。
- 只对 `transformable:true` 的 12 个因子挂 transform（OHLCV + PE_TTM/PB/PS_TTM/DV_RATIO/TOTAL_MV/CIRC_MV/TURNOVER_RATE）。

### C8. A 股实战风控内嵌（评审硬要求）

每个模板必须体现：
- **T+1 适配**：`t_plus_one=true` + 默认 `fill_policy` 走"下一根开盘成交"（akquant 默认，不显式设置即生效），避免当日反转信号无法成交。
- **涨跌停约束**：轮动 `reject_limit_up_on_buy=true` / `reject_limit_down_on_sell=true`（涨停买不进、跌停卖不出）。
- **2024 新国九条**：universe 用指数池（csi300/csi500）走 PIT 过滤，`exclude_st=true` / `exclude_limit_up=true`，小市值相关模板 `description` 显式提示风险。
- **仓位风控**：`top_n≥10`（单票≤10%）+ `max_weight_per_symbol≤0.10` + 止损写入 `exit.bracket`。
- **风格切换警示**：`description` 字段标注适用市况（如红利低波标"防御型，牛市跑输正常"）。

## ADDED Requirements

### Requirement: 因子白名单单一真相源对齐（Task 0）

系统 SHALL 将 `constants.py` 三集合与 `factors.default.json` 严格对齐：补齐 27 个缺失，清理 3 个僵尸因子，使 validator 与运行时一致。

#### Scenario: validator 放行 factors.default.json 全集
- **WHEN** 策略 config 引用 `DV_RATIO`
- **AND** Task 0 已完成
- **THEN** validator 不报 `UNKNOWN_FACTOR`

#### Scenario: 僵尸因子清理
- **WHEN** 策略 config 引用 `REVENUE_GROWTH`
- **THEN** validator 报 `UNKNOWN_FACTOR`，运行时 `factor_registry.exists()` 返回 `False`

#### Scenario: 向后兼容
- **WHEN** 现有 5 模板重新加载
- **THEN** 全部通过 validator（未使用僵尸因子）

### Requirement: 预置策略模板库（A 股实战派认可）

系统 SHALL 在 `strategies/templates/` 目录下提供 8 个预置模板，覆盖 A 股主流有效策略范式（红利低波 / 景气度盈利动量 / 价值低估 / GARP / 多因子含动量 / ETF 动量轮动 / 双均线趋势 / MACD 趋势），每个模板配置齐全（足额成本+仓位风控+止损+A 股实战约束），清单经「老股民视角」评审剔除学术正确但实战一般的策略。模板由现有 `StrategyTemplateLoader` 自动扫描加载，**无需 loader/controller 代码改动**。

#### Scenario: 启动自动加载
- **WHEN** stock-watcher 启动（Task 0 已合并）
- **THEN** 13 个模板（5 旧 + 8 新）全部加载
- **AND** `GET /python/v1/strategies/templates` 返回 13 项

#### Scenario: ETF 动量轮动避坑 signals 资金争抢
- **WHEN** 加载 `etf_momentum_rotation.json`
- **THEN** 使用 `rebalance` 范式（不是 signals）
- **AND** `universe.pool="manual"` + `stocks=[7 只宽基 ETF]`
- **AND** factor composite weights 含 `ROC`（动量）
- **AND** portfolio `top_n=2`（持有最强 2 只）

#### Scenario: 红利低波标注防御属性
- **WHEN** 加载 `dividend_low_volatility.json`
- **THEN** description 含"防御型"字样
- **AND** filter 含 `DEBT_TO_ASSETS < 60` + `TOTAL_MV > 100 亿`

#### Scenario: dev 启动校验通过
- **WHEN** dev profile watcher 启动且 engine 可达
- **THEN** 每个新模板经 `/validate` 校验返回 0 错误
- **AND** 校验失败时仅 WARN 不剔除（FR-13）

#### Scenario: 用户从模板创建并回测
- **WHEN** 用户从「ETF 动量轮动」模板创建策略并触发回测
- **THEN** engine 编译为轮动 Strategy，月度选 top2 强势 ETF 持有
- **AND** 回测正常返回 BacktestResult，无 UNKNOWN_FACTOR、无 NaN 空持仓

### Requirement: 模板可扩展性

系统 SHALL 保证新增模板只需往 templates 目录加一个 JSON（符合 C1-C8 约束），无需任何代码/数据库变更。

#### Scenario: 后续新增模板零代码
- **WHEN** 开发者放入 `new_strategy.json`（符合 Schema）+ 重启 watcher
- **THEN** `GET /python/v1/strategies/templates` 列表含 `new_strategy`
- **AND** 无需改 loader/controller/Java 代码

## MODIFIED Requirements

### Requirement: constants.py 因子集合（对齐 factors.default.json）

三个集合 SHALL 与 `factors.default.json` 严格一致：
- `TECHNICAL_FACTOR_KEYS`：32 个（技术面 27 + 价格直通 5）
- `FUNDAMENTAL_FACTOR_KEYS`：16 个（估值 7 + 质量 4 + 成长 3 + 财务 2）
- `MULTI_OUTPUT_FACTORS`：4 个键（MACD / BOLL / KDJ / MAMA）

## REMOVED Requirements

### Requirement: 僵尸因子识别
**Reason**��`REVENUE_GROWTH` / `NET_PROFIT_GROWTH` / `NORTHBOUND_NET_INFLOW` 在 factors.default.json 无定义，运行时 `factor_registry.exists()` 返回 False，因子值静默 NaN，策略逻辑失效。
**Migration**：Task 0 清理，`REVENUE_GROWTH`→`REVENUE_YOY`、`NET_PROFIT_GROWTH`→`PROFIT_YOY`；现有 5 模板已核对未使用。

### Requirement: 静态高 ROE 质量因子模板 / 布林突破模板
**Reason**：经老股民评审，静态高 ROE 在 A 股无独立 alpha（2021 抱团瓦解后白马连跌 3 年）；布林突破在 A 股胜率极低（游资诱多套路、T+1 追高被套无法当日止损）。两者学术正确但实战一般。
**Migration**：分别替换为「景气度盈利动量」（A 股成长真功夫）与「MACD 趋势（周线过滤强化）」。

### 关于"网格"的术语澄清与能力外置（非需求移除）
经与用户确认，存在两个易混淆的"网格"概念，本 spec 均不涉及，已外置到独立 PRD：
- **GRID 网格参数寻优**（`aq.run_grid_search`）：回测中心 spec 008 第三波的功能（参数优化），给已创建策略做参数寻优。与预置模板正交。
- **网格交易策略**（grid trading，震荡市区间挂单赚差价）：经核实当前 Schema 用固定阈值 + position_sizing 只能表达"单格抄底逃顶"，无法实现真网格（需多档 position_sizing 或 grid 订单类型，全局只有一组 method/target 是硬限制）。

上述两项及可转债双低、行业轮动等"有意义但当前 Schema 不支持"的策略能力，统一整理到 `sdlc/prd/010-策略模板能力扩展/策略模板能力扩展PRD.md`，按优先级排队，稍后单独开发。本 spec 采用**保守模式**，只交付当前能跑通端到端回测的 8 个模板。
