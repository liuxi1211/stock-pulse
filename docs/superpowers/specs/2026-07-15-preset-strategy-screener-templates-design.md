# 预置策略与选股模板库设计

> 日期：2026-07-15　·　模块：���股中心（003）+ 策略管理（004）
>　·　状态：待用户复核

## 1. 背景与目标

选股中心和策略管理两个模块目前若全靠手动录入因子/条件/参数，门槛高、易出错，且难以保证科学性。本设计预置一批**A 股经过多年沉淀、公认有效**的策���与选股模板，让用户一键创建即可得到专业、科学的配置。

**核心质量准绳**：`.trae/rules/系统设计理念(用法建议).md`（个人因子选股避坑清单）。模板必须内嵌该文档的全部科学原则——不是在现有能力上拼凑，而是**以金标准为准绳识别系统能力缺口，缺什么补什么**，杜绝半成品。

**成功标准**：
1. 用户在选股中心、策略管理各能从一批预置模板一键创建，得到符合设计理念的完整配置。
2. 每个模板附带科学说明卡（设计依据/风险/红线），让用户���其然更知其所以然。
3. 系统补齐阻碍科学性的 5 项能力缺口（行业中性、幸存者偏差防护、成交额因子、冲击成本、行业上限）。

---

## 2. 设计理念金标准梳理（需求基线）

将设计理念文档的精华提炼为可落地的检查项，逐条对照系统能力。这是模板与功能补强的共同准绳。

### A. 回测真实性（避免自欺）

| # | 要求 | 系统能力 | 缺口 |
|---|---|---|---|
| A1 | 足额计入手续费/滑点/**冲击成本**（成交额<5000万+0.3%） | ✅佣金/印花税/过户费/最低佣金/滑点dict | ❌冲击成本未建模 |
| A2 | 涨跌停/停牌真实约束 | ✅filters exclude_limit；akquant 撮合应尊重 | 待核实撮合行为 |
| A3 | **幸存者偏差防护**（禁用当前成分股回测历史） | ⚠️csi300/csi500 为当前成分股 | ❌无历史成分股 |
| A4 | 样本外验证 / Walk-Forward | ⚠️回测中心 WF 设计完成 | 依赖回测中心 |
| A5 | 参数敏感性测试（±20% 波动<30%） | ⚠️网格寻优敏感度热力图 | 模板说明卡披露 |

### B. 因子科学性（避免数据挖矿）

| # | 要求 | 系统能力 | 缺口 |
|---|---|---|---|
| B1 | 3-5 因子、不同大类、每类≤1 | ✅因子库分类清晰 | 模板遵循 |
| B2 | **行业中性** | ⚠️akquant 有 `cs_neutralize`，snapshot 未暴露 | ❌核心缺口 |
| B3 | 市值中性 | ⚠️conditions 区间过滤可近似 | 分层中性待补 |
| B4 | 逻辑优先、条件简洁（≤3-4） | ✅规则树支持 | 模板遵循 |
| B5 | **流动性因子（成交额）** | ⚠️有 VOLUME/VOL_MA，无 AMOUNT | ❌因子库缺 |

### C. 实盘可执行性（避免纸上谈兵）

| # | 要求 | 系统能力 | 缺口 |
|---|---|---|---|
| C1 | 持仓 10-20 只 | ✅top_n 可控 | 模板默认 |
| C2 | 单票≤10%、**单行业≤20%** | ⚠️等权+top_n≥10≈单票10% | ❌行业上限无约束 |
| C3 | 止损写入策略 | ✅exit.bracket/ATR/rules | 模板内嵌 |
| C4 | 实盘偏差跟踪 | 🔴未来模块 | 超出本次范围 |

### D. 迭代纪律（避免优化变自杀）

| # | 要求 | 系统能力 | 缺口 |
|---|---|---|---|
| D1 | 区分风格回撤 vs 策略失效 | ⚠️回测报告有最大回撤 | 说明卡给判定标准 |
| D2 | 禁止频繁调参（≤1年1次） | ✅版本管理可追溯 | 说明卡告诫 |
| D3 | 5 条绝对红线（无未来函数/不杠杆/不主观干预…） | ✅T+1/规则树 ref 语义 | 说明卡显著披露 |

---

## 3. 范围

本次 spec = **8 个科学模板**（自带科学默认值 + 说明卡）+ **5 项系统功能补强**。

**明确不做**（YAGNI）：分步向导；严格 point-in-time 成分股；engine 动态冲击成本建模（首版分档滑点）；实盘偏差跟踪（C4）；AI 生成策略；北向跟随/行业轮动等扩展模板（用户已选精选 8 个）。

---

## 4. 总体架构：统一配方、双库落地

选股方案的 `screen_config` 与策略轮动范式的 `screen_config` **同构**。采用统一配方、物理双库：

- **一张选股配方表**统一定义 5 套轮动选股逻辑（因子组合/中性化/过滤），作为单一信源。
- 物理落两个库，内容对齐：
  - `stock-watcher/src/main/resources/screener/templates/*.json` —— 仅存 `screen_config` 片段（选股中心用，轻量）。
  - `stock-watcher/src/main/resources/strategies/templates/*.json` —— 存完整 config（同段 `screen_config` + `trading_config` + `backtest_config`）。
- **不搞运行时互相引用**（避免模板间依赖），靠配方表 + 构建期一致性校验保证两库对齐。
- 新建 `ScreenerTemplateLoader`（完全仿照现有 `StrategyTemplateLoader` 的���描-解析-缓存模式）。

### 现有 5 模板全部升级重写（不保留旧版，git 可追溯）

| 现有模板 | 处理 | 升级方向 |
|---|---|---|
| `dual_ma` | 升级重写 | → 双均线趋势（择时） |
| `macd_short` | 升级重写 | → MACD 趋势（择时） |
| `volume_price` | 升级重写 | → 量价突破（择时） |
| `low_pe_value` | 升级重写 | → 价值成长 GARP（轮动） |
| `small_cap` | 升级重写 | → 小市值（轮动） |
| — | 新增 | 红利低波 / 高质量 / 多因子行业中性（轮动） |

### 范式与编辑器 Tab 映射

策略编辑器 8 Tab + 范式切换（择时 `signals` / 轮动 `rebalance` 互斥）。模板按范式激活对应 Tab：
- 轮动模板（①-⑤）：选股配置 Tab + 调仓 Tab + 回测 Tab。
- 择时模板（⑥-⑧）：股票池 Tab + 买入/卖出信号 Tab + 仓位/止损 Tab + 回测 Tab。

---

## 5. 8 个模板配方

全部使用已核实可用因子（标准因子库 v2），严格遵循"3-5 因子、不同大类、足额成本、仓位风控"。

### 5.1 轮动/选股 5 个（portfolio 范式）

**① 红利低波**（价值 + 波动率｜防御型）
- universe: `csi300`（union+in_date 过滤）｜conditions: `DV_RATIO>3` & `DEBT_TO_ASSETS<60` & `TOTAL_MV>1000000`（万元，即>100亿）
- ranking: `composite` → `DV_RATIO(+0.6)` + `NATR(-0.4)`（股息高 + 波动低）
- filters: 排除 ST/停牌/涨停, `min_list_days=750` ｜ top_n: 20
- 依据: 价值+波动率两大异象，A 股最稳防御组合。

**② 价值成长 GARP**（价值 + 质量 + 成长）
- universe: `csi500` ｜ conditions: `PE_TTM∈[8,30]` & `ROE_TTM>12` & `PROFIT_YOY>10` & `DEBT_TO_ASSETS<65`
- ranking: `composite` → `ROE_TTM(+)` + `PROFIT_YOY(+)` + `PE_TTM(-)`
- top_n: 30 ｜ 依据: GARP，避免单 LowPE 陷阱（银行/钢铁）。

**③ 高质量**（质量主导）
- universe: `csi300` ｜ conditions: `ROE_TTM>15` & `GROSS_MARGIN>30` & `NETPROFIT_MARGIN>10` & `DEBT_TO_ASSETS<50` & `REVENUE_YOY>5`
- ranking: `composite` → `ROE_TTM(+)` + `GROSS_MARGIN(+)` ｜ top_n: 20
- 依据: 质量因子，赚护城河的钱。

**④ 小市值**（市值因子｜带流动性过滤）
- universe: `all_a_shares` ｜ conditions: `TOTAL_MV∈[200000,1000000]`（20-100亿）& `AMOUNT_MA20` 流动性下限（依赖 P1-3）
- ranking: `single` → `CIRC_MV asc` ｜ filters: 排除 ST/停牌, `min_list_days=500` ｜ top_n: 30
- 滑点档位: 0.003（小盘，依赖 P1-4）｜ 风险标注: 容量有限、2024 年后风格衰减，说明卡显式提示。

**⑤ 多因子行业中性**（黄金标准｜价值+质量+成长+动量+低波）
- universe: `csi500` ｜ ranking: `composite` 5 因子等权 → `PE_TTM(-)` + `ROE_TTM(+)` + `PROFIT_YOY(+)` + `ROC(+)` + `STDDEV(-)`，带 `neutralize:{group:"industry"}`（依赖 P0-1）
- 行业上限: `max_industry_pct=0.20`（依赖 P1-5）
- top_n: 30 ｜ 依据: 设计理念黄金标准，5 因子分属 4 大类控单一风格暴露。

### 5.2 择时 3 个（single 范式）

**⑥ 双均线趋势**
- universe: `manual`（如 510300.SH）｜ buy: `MA5 cross_up MA20` & `ADX>20`（趋势确认，过滤震荡）｜ sell: `MA5 cross_down MA20`
- position: `order_target_percent 0.95` ｜ exit: 止损 8% / 止盈 20% + ATR 动态止损
- 依据: 动量/趋势；加 ADX 过滤是关键升级（纯金叉在震荡市反复打脸）。

**⑦ MACD 趋势**
- buy: `MACD.DIF cross_up MACD.DEA` & `MACD柱>0` ｜ sell: `DIF cross_down DEA` ｜ exit 同上
- 依据: 动量。

**⑧ 量价突破**
- buy: `CLOSE>MA20`（突破）& `VOLUME>VOL_MA20×2`（放量）& `MA5>MA20`（多头）｜ sell: 跌破 MA20 ｜ exit: ATR 止损
- 依据: 量价配合，放量突破是趋势启动确认。

### 5.3 通用科学默认（所有模板内嵌）

**足额成本**（对照 akquant 防坑清单 09）：`broker_profile=cn_stock_miniqmt` + `t_plus_one=true` + 佣金万3 + 印花税千1（卖）+ 滑点 `{"type":"percent","value":0.001}`（小市值模板 0.003，依赖 P1-4 分档）+ 过户费 + 最低佣金 5 元。

**仓位风控**（设计理念第三章）：轮���等权 + top_n≥10（单票自动≤10%）+ 止损写入策略（bracket 10%）+ 行业分散（⑤带硬上限，其余说明卡建议）。

---

## 6. 5 项系统功能补强

### P0-1 行业中性化打分（服务 B2，最核心）
- **路径：改 engine（最专业）**。`CandidateStockDTO.meta` 增加申万一级行业字段；watcher `buildCandidates` 从 `stock_basic` 填入。
- snapshot `ranking` 增加 `neutralize:{group:"industry"}`；engine 内对每因子先行业内排名/中性化再加权，复用 akquant `cs_neutralize` / `rank().over(group)`。
- 策略 `screen_config` 同步支持（走同一 engine 接口）。
- **向后兼容**：旧 `single/composite` 不带 `neutralize` 必须仍正常（全市场排名）。
- 兜底（不采纳）：watcher 按行业分组各调 snapshot 取 topK 合并。

### P0-2 幸存者偏差防护（服务 A3）
- **路径：union + 调入日期过滤**。universe 选 `csi300/csi500` 时，候选池 = 回测期内所有曾入选股票；回测日 t 仅保留 `in_date<=t` 的（消除幸存者偏差 + 大部分前瞻偏差，接近 pit）。
- 数据采集：新增成分股历史表（`stock_index_member`：ts_code/index/in_date/out_date），走 `.trae/rules/stock-watcher/business/02-tushare-integration-guide.md` 标准接入 Tushare `index_member`。
- `current` 模式保留但回测/选股 UI 强警示；`all_a_shares` 作为另一零偏差选择（需核实是否覆盖退市/ST）。
- 严格 pit（每日快照）列后续。

### P1-3 成交额因子 AMOUNT（服务 B5/A1）
- 因子库 v2 增加 `AMOUNT`（source: RAW，dataSource: daily_quote.amount）与 `AMOUNT_MA`（N 日均额）。
- watcher `buildCandidates` 补 amount 序列。走因子接入标准流程。

### P1-4 冲击成本建模（服务 A1）
- **首版：模板层分档滑点**。按市值分档预设（大盘 0.001 / 中盘 0.002 / 小盘 0.003），不同模板预设对应档位，零 engine 改动。
- 后续：engine 按当日成交额/订单占比动态计算冲击成本。

### P1-5 行业集中度上限（服务 C2）
- **路径：watcher 兜底，不改 engine**。选股结果按申万行业分组，��� 20% 的行业缩放/剔除次优股，再等权。
- 配置项：轮动 `rebalance.constraints.max_industry_pct`（默认 0.20）。

---

## 7. 科学说明卡元数据结构

模板 JSON 顶层扩展 watcher 元数据（与 `category/tags` 同级，**不进 configJson**，仅展示）。`StrategyTemplateDTO` / `ScreenerTemplateDTO` 增加对应字段：

| 字段 | 内容 | 对应金标准 |
|---|---|---|
| `rationale` | 设计依据：因子逻辑、赚什么钱、中性化原理 | B1/B2 |
| `factor_categories` | 披露因子大类归属（确认 3-5 因子分属不同大类） | B1 |
| `applicable_scenarios` | 适用市况（牛/熊/震荡）+ 持有周期 | — |
| `param_sensitivity` | 核心参数 ±20% 敏感性区间 + 过拟合自查清单 | A5/D2 |
| `risks` | 主要风险（风格切换/容量/失效场景）+ 失效判定标准 | D1 |
| `red_lines` | 5 条绝对红线 | D3 |
| `data_notes` | 数据依赖（行业字段/成交额/成分股模式） | 透明度 |

前端"从模板创建"从简单下拉升级为**带说明卡的模板选择器**（选中即展开 rationale/risks/red_lines）。

---

## 8. 选股中心模板机制（从零搭建）

- 仿 `StrategyTemplateLoader` 新建 `ScreenerTemplateLoader`，扫描 `classpath:screener/templates/*.json`，启动加载缓存（`@PostConstruct`）。
- `ScreenerTemplateDTO`（轻量）：`id/name/description/screenConfigJson` + 第 7 节说明卡字段。
- `PageController.screener()` 注入模板列表；`screener.html` 加"从模板创建"入口。
- 选股模板与策略轮动模板的 `screen_config` 内容对齐（配方表单一信源），构建期一致性校验。
- 加载策略与现有 `StrategyTemplateLoader` 一致：dev profile 启动经 engine 校验，失败不剔除仅 WARN，强校验留给用户保存时触发。

---

## 9. 验证策略与风险

**验证**：
- 每个模板 dev 启动经 engine `validate`；8 模板各跑样本外回测 + 参数敏感性（±20% 收益波动<30%）。
- 行业中性：对比"全市场排名 vs 行业内排名"结果差异；幸存者偏差：对比 `current` vs `union+in_date` 收益差异（应显著下降，证伪虚高）。
- 单测：`StrategyTemplateLoader`/`ScreenerTemplateLoader` 加载与 schema 校验；engine snapshot neutralize 向后兼容。

**主要风险**：
- engine snapshot 重构 ranking 须向后兼容（旧配置无 `neutralize` 仍工作）。
- `index_member` 历史数据覆盖完整性（Tushare 接口范围）。
- 技术因子（`STDDEV/ROC/VOL_MA`）在 snapshot 截面的实际可用性——candidates 已传 `ohlcvHistory`，理论可行；实现期验证，不可用则动量/波动因子降级为基本面近似并说明卡标注。

---

## 10. 实现任务拆解（供 writing-plans 展开）

按依赖与优先级分批：

**批次 1 · 数据与因子地基**（P1-3、P0-2 数据层）
- T1 因子库扩展 `AMOUNT`/`AMOUNT_MA` + watcher `buildCandidates` 补 amount。
- T2 采集 Tushare `index_member` → `stock_index_member` 表 + universe union+in_date 解析。

**批次 2 · engine 能力补强**（P0-1）
- T3 snapshot `ranking` 增加 `neutralize`，engine 行业内排名（复用 `cs_neutralize`），向后兼容。
- T4 `CandidateStockDTO` 带行业字段，watcher `buildCandidates` 填充。

**批次 3 · watcher 编排增强**（P1-4、P1-5）
- T5 模板层分档滑点（市值档位预设）。
- T6 选股结果行业集中度裁剪（`max_industry_pct`）。

**批次 4 · 模板库与说明卡**
- T7 `StrategyTemplateDTO`/`ScreenerTemplateDTO` 扩展说明卡字段。
- T8 新建 `ScreenerTemplateLoader` + `screener/templates/*.json`（5 选股模板）。
- T9 重写 `strategies/templates/*.json`（8 策略模板）+ 配方表一致性校验。

**批次 5 · 前端入口**
- T10 策略编辑器"从模板创建"升级为说明卡选择器。
- T11 选股中心 `screener.html` 加"从模板创建"入口。

**批次 6 · 验证**
- T12 8 模板 validate + 样本外回测 + 参数敏感性 + 对比实验（行业中性/幸存者偏差）。
