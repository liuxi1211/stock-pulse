# 策略模板能力扩展 PRD

> **模块**：策略管理（004）+ 选股中心（003）+ 回测中心（005）
> **状态**：规划中，按优先级排队，逐项独立 spec 跟进
> **来源**：spec 014-preset-strategy-templates「老股民视角评审」推荐但当前 Schema/数据/akquant 不支持的策略能力
> **原则**：本 PRD 只做"能力缺口登记 + 方案建议"，不直接落地；每项能力落地前需另起 spec，走完整的 Schema 设计 → validator/compiler 改造 → 模板补录 → 回测验证 流程。

## 1. 背景

spec 014 预置策略模板时，经「20 年 A 股实战老股民视角」评审，识别出一批 **A 股业界主流、实战有效，但当前统一策略配置 Schema / 因子库 / akquant 封装层不支持** 的策略能力。spec 014 采用保守模式，只交付当前能跑通端到端回测的 8 个模板；本 PRD 把这些"有意义但做不了"的能力统一登记，供后续按优先级单独开发。

**核心质量准绳**（与 spec 014 一致）：`.trae/rules/系统设计理念(用法建议).md` + A 股实战派认可。

## 2. 能力缺口总览

| # | 能力 | 业务价值 | 当前限制 | 建议优先级 | 依赖 |
|---|---|---|---|---|---|
| 1 | 网格交易策略 | A 股震荡市利器，散户最爱 | Schema position_sizing 单组 method/target，无法多档建仓/平仓 | P1 | Schema 扩展 + compiler |
| 2 | 可转债双低策略 | A 股独有利器，下有底上不封顶，10 年年化 15%+ | 无可转债数据、下修/强赎逻辑、双低因子 | P2 | 因子库 + watcher 数据采集 |
| 3 | 行业动量轮动 | A 股最主流玩法之一，吃行业风口 | 无行业 ETF universe 池，行业分类数据未在 universe 层暴露 | P2 | universe 扩展 + 行业数据 |
| 4 | 大小盘风格择时 | 吃风格切换红利 | 与 ETF 动量轮动同质，可合并（spec 014 已合并） | P3 | 无（已部分覆盖） |
| 5 | 严格 point-in-time 成分股 | 防幸存者偏差 | watcher 已做 union+in_date 近似，严格滚动快照未做 | P3 | spec 010 滚动快照 |
| 6 | GRID 参数寻优 + Walk-Forward | 给策略找最优参数 | 属回测中心，spec 008 第三波已规划 | P1 | spec 008 第三波 |

> 注：#4 已在 spec 014 用 ETF 动量轮动模板（universe 含大小盘 ETF）覆盖；#5 由 spec 010 主导；#6 属回测中心 spec 008。本 PRD 重点展开 #1/#2/#3。

## 3. 能力一：网格交易策略（grid trading）

### 3.1 业务价值
A 股 2016-2018、2022-2023 是长期震荡市，网格交易在震荡市是利器：在价格区间内等间距挂买卖单，每格赚固定差价，无需判断方向。散户圈"网格"、可转债网格是显学。

### 3.2 真网格的语义（必须支持）
假设中枢 10 元、间距 2%、每格 200 股：

| 价格触发 | 动作 | 累计持仓 | 关键约束 |
|---|---|---|---|
| 跌到 9.8 | 买第 1 格（200 股） | 200 | 同一格不重复成交 |
| 跌到 9.6 | 买第 2 格（200 股） | 400 | 持仓层数 +1 |
| 跌到 9.4 | 买第 3 格（200 股） | 600 | 持仓层数 +1 |
| 反弹到 9.6 | 卖第 3 格（200 股） | 400 | 持仓层数 -1 |
| 反弹到 9.8 | 卖第 2 格（200 股） | 200 | 持仓层数 -1 |
| 涨到 10.2 | 卖第 1 格（200 股） | 0 | 持仓层数 -1 |

### 3.3 当前为什么做不了（已核实）
经源码级核实，当前 Schema 用 `signals + 固定阈值（CLOSE<9.8 / CLOSE>10.2）+ position_sizing` **能过 validator**，但只能表达"单格抄底逃顶"，无法实现真网格：

- **硬限制 1**：`position_sizing` 全局只有一组 method/target（`PositionSizingModel` 单实例）。`order_target(200)` 在已持仓 200 时目标不变、不再买；无法实现"每跌一格买一格"。
- **硬限制 2**：`exit.rules` 只能平仓（action=close_position/sell），不能加仓。无法用 exit 表达"跌一格加仓"。
- **硬限制 3**：无"网格触发后去重"机制。价格在 9.7-10.3 间来回时，`<` / `>` 非 cross 语义会反复触发，且 T+1 下当日反转信号无法成交。
- **硬限制 4**：无"持仓层数"状态变量（ALLOWED_REFS 只有 entry_price/position_pnl_pct/position_qty/bars_held，无 grid_level）。

### 3.4 实现方案建议（三选一）

**方案 A：扩展 position_sizing 支持多档（推荐）**
- `PositionSizingModel` 新增 `ladder: List[{trigger, action, qty}]`，按价格档位触发不同动作。
- compiler 在 `on_bar` 内逐档判断，配合"已成交档位"状态去重。
- 改动：Schema + validator + compiler + 前端编辑器（多档表格）。

**方案 B：新增 grid 订单类型**
- `position_sizing.method = "grid"`，params 传 `{center, step, qty_per_grid, max_grids}`。
- compiler 生成专门的 `on_bar` 网格逻辑，内部维护 grid_level 状态。
- 改动同方案 A，但语义更内聚。

**方案 C：用 rebalance + manual + 多个虚拟标的**（不推荐，hack）
- 把每"格"拆成独立 symbol，绕开单 position_sizing 限制。语义混乱，不采纳。

### 3.5 验收标准
- 能表达"中枢 + 间距 + 每格数量 + 最大格数"四要素；
- 价格触发某格后，该格在反向触发前不重复成交；
- T+1 + 涨跌停约束生效；
- 回测能正确计算每格盈亏与总持仓。

### 3.6 风险
- akquant 撮合层是否支持"条件挂单 + 去重"，需核实 `place_bracket_order` / OCO 语义是否可复用。
- 网格在单边行情下风险大（单边下跌一路套牢、单边上涨一路卖飞），需强制 `max_grids` 上限 + 止损线。

## 4. 能力二：可转债双低策略

### 4.1 业务价值
A 股独有利器：可转债下有债底保护、上可转股博弹性，"双低"（低价格 + 低溢价率）是公认的可转债策略，过去 10 年年化 15%+，回撤可控。散户圈"可转债摊大饼"是显学。

### 4.2 双低的数学定义
```
双低值 = 转债价格 + 转股溢价率 × 100
选双低值最小的 N 只，等权持有，定期调仓
```
例：价格 105 元、溢价率 10% → 双低 = 105 + 10 = 115。

### 4.3 当前为什么做不了
- **数据缺失**：watcher 未采集可转债行情（转债代码、转债价格、转股溢价率、正股价、转股价）。Tushare 有 `cb_daily`/`cb_basic` 接口但未接入。
- **因子缺失**：因子库 `factors.default.json` 无可转债因子（转债价格、溢价率、双低值）。
- **Schema 缺失**：universe.pool 无 `convertible_bond` 类型；回测链路假定标的是股票（涨跌停 ±10%、T+1、手数 100 等规则对转债不同：转债涨跌幅 ±20% 临停、T+0、手数 10）。
- **下修/强赎逻辑**：可转债有下修（转股价下调）、强制赎回（正股价持续高于转股价 130% 触发）等特殊事件，影响策略收益，需事件驱动处理。

### 4.4 实现方案建议
1. **watcher 数据采集**：按 `.trae/rules/stock-watcher/business/02-tushare-integration-guide.md` 接入 Tushare `cb_basic`（转债基础信息）+ `cb_daily`（日线）+ `cb_share`（转股价），落库 `cb_basic`/`cb_daily` 表。
2. **因子库扩展**：`factors.default.json` 新增 `CB_PRICE`（转债价格）、`CB_PREMIUM`（转股溢价率）、`CB_DOUBLE_LOW`（双低值，derived）。
3. **universe 扩展**：`UniverseModel.pool` 新增 `convertible_bond` 取值；watcher `resolveBacktestSymbols` 支持转债代码。
4. **broker_profile 扩展**：新增 `cn_convertible_bond` 费率模板（T+0、涨跌幅 ±20% 临停、手数 10、无印花税、佣金万2）。
5. **事件处理**：watcher 下发下修/强赎事件，engine 在 `on_bar` 前剔除强赎标的。

### 4.5 验收标准
- 能选双低值最低的 N 只可转债，等权调仓；
- T+0 + 涨跌幅 ±20% + 手数 10 正确；
- 强赎标的不被买入；
- 回测收益与公开双低策略回测（如集思录数据）口径一致。

### 4.6 风险
- 可转债数据覆盖完整性（Tushare 转债接口的历史回填）；
- 强赎/下修事件的历史回放（point-in-time）；
- 转债流动性差异（小盘转债成交额低，滑点大）。

## 5. 能力三：行业动量轮动

### 5.1 业务价值
A 股行业轮动效应显著（消费/科技/金融/周期/新能源轮动），行业 ETF 动量轮动是机构与散户都用的主流玩法，过去 10 年年化 15-20%。比宽基 ETF 轮动更激进、收益弹性更大。

### 5.2 当前为什么做不了
- **universe 限制**：当前 `universe.pool` 只支持 `all_a_shares/csi300/csi500/manual`，无"行业 ETF 池"概念。手动列行业 ETF 代码虽可（manual 模式），但用户需自己维护代码列表，体验差。
- **行业分类数据未暴露**：虽然 spec 011 落了申万行业分类（`sw_industry`），但仅用于个股行业中性化，未在 universe 层提供"按行业聚合的 ETF 池"。
- **行业动量计算**：需"行业指数涨幅"或"行业内个股等权涨幅"，当前无行业指数因子。

### 5.3 实现方案建议
1. **行业 ETF 池**：watcher 预置主流行业 ETF 列表（消费 159928 / 科技 515000 / 金融 512070 / 医药 512010 / 新能源 516160 / 军工 512660 / 周期 515210 等），universe.pool 新增 `industry_etf` 取值。
2. **行业动量因子**：`factors.default.json` 新增 `INDUSTRY_MOMENTUM`（行业指数 N 日涨幅），或直接用 ETF 自身的 ROC（已在 spec 014 ETF 轮动模板用 CLOSE.transform pct_change 近似）。
3. **模板**：参考 spec 014 的 `etf_momentum_rotation`，把 universe 从宽基 ETF 换成行业 ETF 即可。

### 5.4 验收标准
- universe 能选行业 ETF 池；
- 按行业动量排序选 top N 持有；
- 行业切换时换手率与成本合理。

### 5.5 与 spec 014 的关系
spec 014 的 `etf_momentum_rotation` 已用 manual + 7 只宽基 ETF + ROC 动量实现了"宽基轮动"。行业轮动本质相同，只是 universe 换成行业 ETF。**若用户接受手动维护行业 ETF 代码，其实现在就能用 spec 014 的模板改 universe 实现**，本 PRD 只是把"预置行业 ETF 池 + 一键创建"作为体验优化登记。

## 6. 落地路线图建议

| 波次 | 能力 | 估时 | 前置 |
|---|---|---|---|
| 第一波 | 网格交易（能力一） | 中 | Schema 扩展方案定稿 |
| 第一波 | GRID 参数寻优（能力六，spec 008 第三波） | 中 | spec 008 OQ-3 |
| 第二波 | 可转债双低（能力二） | 大 | watcher 数据采集 + 因子库 + broker_profile |
| 第二波 | 行业动量轮动（能力三） | 小 | 行业 ETF 池预置（可部分用 spec 014 现有模板） |
| 第三波 | 严格 point-in-time（能力五） | 大 | spec 010 滚动快照 |

## 7. 不在本 PRD 范围

- 具体的 Schema 字段定义、错误码、compiler 改造细节（落地时另起 spec）；
- 前端编辑器交互（各能力需独立设计编辑器控件）；
- 北向资金跟随（因子库无该因子，且 2024 后北向数据披露规则变化，价值下降，暂不做）；
- 日内高频/T+0 打板（个人无优势，设计理念明确禁做）。

## 8. 相关文档

- spec 014-preset-strategy-templates（预置模板，保守模式）
- `.trae/rules/系统设计理念(用法建议).md`（避坑清单）
- spec 008-backtest-center-phase2（GRID 参数寻优归属）
- spec 010-rotation-data-governance（point-in-time）
- spec 011-rotation-config-completeness（行业分类已落库）
