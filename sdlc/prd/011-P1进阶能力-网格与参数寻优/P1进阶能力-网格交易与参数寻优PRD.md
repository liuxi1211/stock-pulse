# P1 进阶能力 PRD：网格交易策略 + GRID 参数寻优

> **模块**：策略管理（004）+ 回测中心（005）
> **状态**：规划中，按优先级排队；本文拆分自 [010-策略模板能力扩展](./../010-策略模板能力扩展/策略模板能力扩展PRD.md) §2 的 P1 项（#1、#6），独立成稿
> **来源**：010 PRD §3（网格交易）/ §6.1 + 005 回测中心 PRD §6.1-6.2（第二波预告）
> **原则**：能力缺口登记 + 方案建议 + 落地前置条件；本文仅做"需求级"定稿，**具体 Schema 字段定义、错误码、compiler/compiler 改造、前端交互稿待各自落地 spec 展开**。

## 0. 拆分说明（为什么单独立项）

010 把六项能力混在一个登记表里，P1（网格交易）/P2（可转债、行业轮动）/P3（风格、PIT）混排，没有共同的"作战节奏"。本 PRD 把**两项 P1 能力**单独拎出，因为：

- **两者都不依赖外部新数据**：网格交易只需 Schema + compiler；GRID 寻优只需 005 第二波 + OQ-3。**无 watcher 数据采集负担**，可在同一波次交付。
- **两者都无前置外部 spec 阻塞**：可转债依赖 Tushare `cb_*` 数据采集（大块工作）；行业轮动依赖行业 ETF 池预置（需 watcher 改动）；PIT 依赖 010 滚动快照。这两项 P1 一旦本 PRD 定稿即可排期。
- **价值密度高**：网格是 A 股震荡市散户刚需；GRID 寻优是回测中心"参数可信度"的核心拼图，二者组合即可形成"震荡市策略 + 参数可信度验证"的最小闭环。

> **不在本 PRD 范围**：可转债双低（P2）、行业轮动（P2）、风格择时（P3，已合并）、严格 PIT（P3）——仍登记在 010，各自独立排期。

---

## 1. 背景与目标

### 1.1 业务背景
- **网格交易**：A 股 2016-2018、2022-2023 长期震荡市，网格交易在震荡市是利器，散户圈"网格"、可转债网格是显学。当前统一策略配置 Schema（004）的 `position_sizing` 单组 method/target 只能表达"单格抄底逃顶"，无法多档建仓/平仓，**做不了真网格**。
- **GRID 参数寻优**：当��回测中心（005）只能跑单参数回测，用户无法回答"哪组参数最优"——这是策略研发的基本功。akquant 0.2.47 已内置 `run_grid_search` / `run_walk_forward`，但 engine/watcher/前端三层未接入。

### 1.2 总体目标
- **能力一**：让用户能在前端配置「中枢 + 间距 + 每格数量 + 最大格数」四要素，端到端跑通网格策略回测，且 A 股 T+1 / 涨跌停 / 手数 100 规则正确生效。
- **能力二**：让用户能在前端选「可调参数 + 候选值网格」，跑 GRID 寻优得到 Top-N 参数组合 + 指标排序；进一步可选 WALK-FORWARD 滚动验证防过拟合。
- **质量准绳**：`.trae/rules/系统设计理念(用法建议).md` + A 股实战派认可（不接受"纸面能跑、实战不能用"）。

### 1.2.1 目标用户画像（新增，对齐产品视角评审 P-01）

| 画像 | 特征 | 本能力诉求 | 默认值取向 |
|---|---|---|---|
| **P0 散户** | 1-3 年经验、能看懂网格四要素但不会写代码 | 保姆式网格编辑器 + 成本预估前置 | 「防亏优先」：`max_position_value_pct` 默认 0.6、`unfilled_retry_bars` 默认 1、UI 强警告 |
| **P1 小私募研究员** | 会用 Excel 做参数扫描 | GRID 全维指标 + WF 验证 + 结果导出 | 「可信度优先」���`max_position_value_pct` 默认 0.9、6 维过拟合指标全开 |
| **非目标用户** | 日内高频打板、量化机构实盘 | — | 已在 §1.3 排除 |

> 默认值在不同画像下不同；一期前端不做「画像切换」开关，**统一按 P0 散户默认值**（防亏优先），P1 用户自行调高阈值。

### 1.2.2 竞品对标与差异化（新增，对齐 P-09）

| 维度 | 聚宽 | 米筐 | 掘金 | 迅投 QMT | **本项目差异点** |
|---|---|---|---|---|---|
| 网格交易 | 可视化 + SDK | SDK | SDK 为主 | 实盘强 | ✅ **A 股 T+1/涨跌停/手数 100 的网格语义正确性**（竞品多用美股语义） |
| GRID 寻优 | API 化偏多 | 完整、UI 重 | API | 弱 | ✅ **保姆式过拟合防背书**（应用按钮置灰 + 置信度徽标） |
| 成本预估 | 回测后 | 回测后 | 回测后 | — | ✅ **成本预估前置**（FR-G4 每格净收益 vs 成本占比，配置阶段即预警） |
| 参数复用 | 复制粘贴 | 复制粘贴 | 复制粘贴 | — | ✅ **参数方案收藏夹**（FR-O6b，带时间戳与 WF 通过段数） |

**差异化战略**：「**散户也能用的可信参数验证**」——所有 FR 必须强化上述 4 个差异点，不得为简化实现而弱化（如 FR-G4 成本预估是 P0 必做，非可选）。

### 1.2.3 合规边界声明（新增，对齐 P-11）

> ⚠️ 网格交易 + 参数寻优本质是辅助用户做交易决策的工具，存在被解读为「投资建议」的合规风险。本节为产品级硬约束，所有 FR 与 UI 必须遵守。

1. **工具性声明**：全产品页脚固定文案「本工具为量化研究辅助，不构成任何投资建议；历史回测结果不代表未来收益」；
2. **应用按钮二次确认**：FR-O6「应用」按钮即使可点，用户首次应用必须勾选「我已理解历史最优不代表未来」复选框，单次会话有效；
3. **真实数据沙盘结果禁止截图传播**：FR-G5 沙盘结果页加水印「仅供研究」，且导出文件名含「research_only_」前缀；
4. **合规评审**：上线前由法务/合规过一遍（§13 老股民评审之外另立流程）；
5. **不构成投资建议的 UI 元素**：所有「最优参数」「推荐」措辞改为「历史 Top-N」「样本内最优」，避免暗示性表述。

### 1.3 非目标（明确不做）
- 不做日内高频/T+0 打板（设计理念禁做）；
- 不做 akquant 的 `db_path` 断点续传（engine 禁 sqlite3 写盘，见 010 §3 与 005 NFR-2）；
- 不做"GRID 跑完自动创建策略版本"（避免过拟合背书，见 005 §6.1）；
- 不做可转债网格（可转债整体是 P2，数据未到位）；
- **不做融券/空头网格**：A 股散户无融券，本能力仅做多（跌买涨卖），grid_level 取值 `[0, max_grids]`，不支持负值；
- **一期仅支持日线网格**：分钟/小时级别网格（日内多次网格）在二期评估，一期不交付；
- **一期网格为对称等量网格**：不自称网格（底部密顶部疏、金字塔加仓 `[100,200,400,800]`）在 Schema 预留 `step_mode`/`qty_mode` 字段位但一期不实现（见 FR-G1）。

### 1.4 典型用户旅程（新增，对齐 P-02）

**旅程 1：网格配置 → 回测 → 收藏**（P0 散户主路径）
选/新建策略 → 进入「仓位管理」Tab → 切 method=grid → 填四要素 + 必填风控 → 实时看网格档位示意图 + 资金占用预估（FR-G4）+ 成本预估 → 超阈值则红框警告、禁保存 → 保存 → 跑回测 → 看盈亏对账（FR-G5）→ （可选）收藏为参数方案（FR-O6b）

**旅程 2：GRID + WF 寻优 → 应用**（P1 研究员主路径）
选策略 → 进「参数寻优」Tab → paramGrid 编辑器自动反推形参（FR-O3/FR-O7）→ 填候选值 + constraint/resultFilter DSL（FR-O2）→ 点「开始寻优」→ 前端先弹资源预估（FR-O11）→ GRID 跑完看 Top-N + 置信度徽标 → 点「跑 WF 验证」→ WF 跑完看 6 维过拟合指标 → 若通过判据，「应用」按钮可点 → 跳转新建策略页预填（FR-O6）→ 用户手动保存

**旅程 3：失败与异常**（防流失路径）
- GRID 超时/取消：任务状态机透传 FAILED/CANCELLED，前端提示「已取消，可缩小 param_grid 重试」；
- WF 不通过：「应用」按钮置灰但展示「你比无脑 Top-1 避免了 X% 过拟合风险」（FR-O6 正向反馈），引导用户调整 WF 阈值或扩大数据；
- 资金不足：FR-G4 配置阶段即拦截（禁止保存），避免跑完才发现。

**旅程 4：历史回溯**（FR-O9）
进「寻优历史」Tab → 看历史 GRID 任务列表（时间/参数/Top-1 指标/WF 是否通过）→ 选两次「对比」→ 双列 Top-N 对齐视图。

---

## 第一部分：网格交易策略（能力一）

## 2. 业务语义（必须完整支持）

### 2.1 网格交易的核心语义（A 股 T+1 版）
假设中枢价 10 元、间距 2%、每格 200 股、最大格数 3。**下表已对齐 A 股 T+1 规则**（当日买入次日才可卖）：

| 交易日 | 价格走势 | 触发档位 | 动作 | grid_level | 备注 |
|---|---|---|---|---|---|
| D1 | 收盘 9.8（前日 10.0） | 跌穿 9.8 | 买第 1 格（200 股） | 1 | 订单 filled 后 level+1 |
| D2 | 收盘 9.6 | 跌穿 9.6 | 买第 2 格（200 股） | 2 | |
| D3 | 收盘 9.4 | 跌穿 9.4 | 买第 3 格（200 股） | 3 | 达 max_grids，停止买入 |
| **D4** | **早 9.4 → 午反弹 9.6** | **触及卖第 3 格档位 9.6** | **❌ 当日不可卖（D3 买入受 T+1 锁定）** | **3（不变）** | **反向信号挂起，grid_level 不推进** |
| D5 | 开盘 9.6 | 昨日挂起的卖第 3 格 | 次日开盘卖第 3 格（200 股） | 2 | 挂起单在下一根 bar 开盘成交 |
| D6 | 收盘 10.2 | 穿 9.8、10.0、10.2 三档 | ✅ 仅卖第 2 格（最近反向格） | 1 | **跳空穿越只成交一档**（见 §2.3） |

> ⚠️ **T+1 反转是 A 股网格最高频失败场景**：D4 的"想卖但当日不可卖"在散户网格系统里 90% 会写出"状态推进但订单未成交"的 bug。本表把这一行单独标出，FR-G2/FR-G3 必须实现"挂起单 + 次日成交 + level 不变"语义。

### 2.2 四要素定义（必填，缺一不可）
| 要素 | 含义 | 约束 |
|---|---|---|
| **中枢价 center** | 网格基准价 | > 0；可选手动指定 / 用建仓首根 bar 的 close |
| **间距 step** | 每格价格间距 | > 0；支持百分比（如 0.02=2%）或绝对值（如 0.2 元），二选一 |
| **每格数量 qty_per_grid** | 每格买卖股数 | > 0；A 股需为 100 的整数倍（lot_size 向下取整） |
| **最大格数 max_grids** | 单边最多建仓层数 | ≥ 1；建议 ≤ 10；达到后停止加仓 |

**字段位预留（一期不实现，避免未来 Schema 破坏性变更）**：
- `step_mode: "symmetric" | "asymmetric"`（一期固定 symmetric）；
- `qty_mode: "equal" | "pyramid"`（一期固定 equal，pyramid 支持 `[100,200,400,800]` 金字塔加仓）；
- `adjust_mode: "raw" | "forward_adjusted" | "re_anchor_on_event"`（一期默认 forward_adjusted，见 §2.4）。

**价格档位 tick 对齐**：所有档位价（center ± n×step）必须按 A 股最小变动价 `tick_size=0.01` 向下取整，避免"回测触发、实盘差一分钱不触发"。科创板/创业板/北交所的 tick_size 一期统一按 0.01 处理（与 watcher 清洗口径一致）。

### 2.3 触发去重规则（核心难点）
- **cross 语义**：以"前一根 close → 当前 close 是否穿越档位"为判据，避免盘中抖动反复触发。
- **跳空穿越只成交一档**：单根 bar 内若穿越多档（如 9.6 直接开盘 10.5），**只成交最近反向格**（grid_level 最近的反向档位），不连发多笔。理由：T+1 + 资金/持仓都不允许单 bar 多笔，且跳空多为消息驱动、连发会击穿风控。
- **挂单失败时 grid_level 不推进**（A 股最关键）：订单必须**实际 filled** 后才 ±1。未成交场景包括：
  - 一字涨跌停板（根本买不到/卖不出）；
  - 触及 volume_limit_pct（成交占当根成交量超限被拒）；
  - T+1 锁定（当日买入当日反向卖）；
  - 资金不足（max_grids 打满后再跌）。
- **未成交订单重试**：`unfilled_retry_bars`（默认 1，即下一根 bar 开盘再试一次；超时放弃并 warning）。
- **停牌处理**：bar.extra 标记停牌或 watcher 不下发停牌 bar 时，该段不触发、不复权推进；复牌首根按"跳空穿越只成交一档"规则。

### 2.4 除权除息 / 复权对齐（A 股特有，必修）
A 股每年分红、送转频繁，10 转 10 后股价腰斩，**中枢不重算则网格全错位**。处理策略：

| 模式 | 中枢/档位 | 成本统计 | 适用 |
|---|---|---|---|
| `forward_adjusted`（默认） | 全程用前复权价，中枢一次设定不变 | 佣金/印花税按**前复权价 × 成交量**计算（与 watcher 清洗口径一致，回测与实盘对账一致） | 一期默认，回测精度足够 |
| `re_anchor_on_event` | 除权日按 adj_factor 重算所有档位价 | 按真实价 × 成交量 | 二期评估，精度更高但实现复杂 |
| `raw` | 用真实价，中枢需用户手动重锚 | 按真实价 | 不推荐，实战易错 |

> ⚠️ **回测成本必须与档位价同坐标系**：watcher 传前复权价则成本按前复权算；若混用前复权档位 + 真实价成本，会导致小单价（前复权后 0.5 元）佣金严重失真。本能力锁定 `forward_adjusted` 全链路一致。

## 3. 当前为什么做不了（已源码级核实���转引自 010 §3.3）

1. **`position_sizing` 全局只有一组 method/target**（`PositionSizingModel` 单实例）。`order_target(200)` 在已持仓 200 时目标不变、不再买；无法"每跌一格买一格"。
2. **`exit.rules` 只能平仓**（action=close_position/sell），不能加仓。无法用 exit 表达"跌一格加仓"。
3. **无"网格触发后去重"机制**：价格在 9.7-10.3 间来回时，`<`/`>` 非 cross 语义会反复触发，且 T+1 下当日反转信号无法成交。
4. **无"持仓层数"状态变量**：ALLOWED_REFS 只有 entry_price/position_pnl_pct/position_qty/bars_held，无 grid_level。

## 4. 方案选型（本 PRD 推荐方案 B，待 spec 定稿）

> 010 §3.4 给了 A/B/C 三案，本 PRD 落地推荐意见，最终以独立 spec 为准。

| 方案 | 概要 | 优点 | 缺点 | 推荐 |
|---|---|---|---|---|
| A | 扩展 `position_sizing.ladder: List[{trigger, action, qty}]` 多档 | 与现有 Schema 同构 | 语义分散在 position_sizing，"网格"概念不内聚 | ⚠️ 备选 |
| **B** | 新增 `position_sizing.method = "grid"`，params 传 `{center, step, qty_per_grid, max_grids}` | 语义内聚，compiler 生成专门 `on_bar` 网格逻辑 | method 表新增一种，前端编辑器需独立控件 | ✅ **推荐** |
| C | rebalance + manual + 多虚拟标的 | 无 | 语义混乱，T+1/手续费错乱 | ❌ 不采纳 |

**推荐方案 B 的理由**：网格是一种**自包含的交易范式**（自带触发去重、层数状态、���边风控），不适合塞进通用的 position_sizing 多档结构。用独立 method + params 内聚度最高，compiler 改造边界最清晰，前端编辑器也能做"网格四要素表单"这种专属控件。

## 5. 功能需求（FR）

### FR-G1：网格策略 Schema 扩展（方案 B）
- `position_sizing.method` 新增枚举值 `"grid"`；
- `position_sizing.params` 新增子字段（method=grid 时必填）：
  - `center`：number | `"first_bar_close"`，中枢价（**若手动指定数值，前端提示"手动中枢可能引入前瞻偏差，建议用 first_bar_close"**）；
  - `step`：object `{type: "percent"|"absolute", value: number}`，间距；
  - `qty_per_grid`：int，每格股数；
  - `max_grids`：int，最大格数（建议 ≤ 10，硬上限 20）；
  - `re_entry_after_clear`（可选，默认 `false`）：grid_level 归零（全部卖飞）后是否在价格���次跌破中枢时重启网格。一期固定 false（清仓后不再建仓，防牛市踏空后再被熊市套牢），语义在 FR-G2 注明；
  - **`stop_loss`（必填，二选一）**：单边下跌止损，至少配一个——
    - `stop_loss_price`：绝对止损价（相对中枢）；
    - 或 `stop_loss_pct`：相对中枢的止损百分比（如 0.15=跌破中枢 15% 全平）；
  - **`max_holding_bars`（必填）**：时间止损，持仓超 N 根 bar 强平（防长期套牢占用资金，默认 60 个交易日）；
  - **`max_position_value_pct`（必填）**：总仓位占初始资金比例上限（默认 0.9，预留 10% 缓冲佣金/滑点/印花税），触达后停止加仓；
  - `take_profit_price`（可选）：单边上涨止盈线（防卖飞，非必填，因 A 股上涨本就是收益）；
  - `unfilled_retry_bars`（可选，默认 1）：未成交订单重试 bar 数；
  - `adjust_mode`（可选，默认 `"forward_adjusted"`）：复权模式，见 §2.4。
- **范式兼容**：grid 仅与 `signals` 范式兼容，**禁止与 `rebalance` 同框**（沿用 006 的 SIGNALS_REBALANCE_EXCLUSIVE 约束）。
- **与 exit 互斥**：method=grid 时，`exit.bracket` / `exit.rules` 必须为空（grid 自带止损止盈语义，避免双重平仓冲突）。
- **预留字段位**：`step_mode` / `qty_mode` 字段位预留，一期固定 `symmetric` / `equal`。

### FR-G2：网格 compiler 改造（四段式，剥离实现细节）

> **产品口径**（不含研发实现路径；研发架构详见 spec §S2 GridStrategy 独立类设计）。

- **输入**：含 `position_sizing.method="grid"` 的策略 config + bar 流（含 `bar.extra` 停牌标记）。
- **处理口径**：
  1. **状态变量**：跨 bar 维护 `grid_level ∈ [0, max_grids]`、`pending_retry`（未成交重试计数）、`pending_sell`（T+1 挂起卖单），均挂在 Strategy 实例；
  2. **档位价计算**：`center ± n×step`，按 `tick_size=0.01` 向下取整；复权模式 `forward_adjusted` 下中枢一次设定不变；
  3. **三回调职责**（研发硬约束，详见 spec §S1 状态机）：
     - `on_bar` 开头：检查 `pending_sell`（若 T+1 已解锁则发卖单）、检查 `pending_retry` 是否超时；
     - `on_bar` 主体：cross 语义判穿越 → 跳空去重取最近反向格 → 发 buy/sell → **不推进 grid_level**；
     - `on_order`：订单状态 `Filled` → **才**推进 grid_level（买+1/卖-1）；`Rejected`/`Cancelled` → 不推进，`pending_retry+=1`；
  4. **T+1 反转挂起**：当日反向卖出信号触发但 `get_available_position()==0` 时，记 `pending_sell=(target_level, qty)`，当根 bar 不发单，下一根 bar `on_bar` 开头检查 T+1 解锁后发单（配 `fill_policy=next_event`）；
  5. **跳空去重**：单 bar 穿越多档，只对 `grid_level` 最近反向档位发单；
  6. **停止条件**：达 `max_grids` / `max_position_value_pct` 停止买入；触发 `stop_loss` 全平并 `grid_level=0`；持仓达 `max_holding_bars` 强平；
  7. **停牌**：`bar.extra` 标记停牌或 watcher 未下发该 bar 时，跳过触发判断；复牌首根按跳空规则；
  8. **A 股规则强制**：T+1、lot_size=100 向下取整、涨跌停 ±10%（涨停不买/跌停不卖视为挂单失败走重试）、手数最小 100。
- **输出**：trades_df + 持仓曲线 + grid_level 时序（用于回溯审计）。
- **验收**：G-T1~G-T11 + 补充用例 T-G-12~T-G-29 全部通过（见 §FR-G5 与 spec §S7 测试矩阵）。
- **默认值产品理由**：`unfilled_retry_bars=1`（默认只重试 1 次）——涨跌停板次日大概率仍停板，多次重试浪费资源且给用户虚假希望；P1 用户可自行调高。

### FR-G3：网格触发去重与状态机
- **同一格反向触发前不重复成交**：用 grid_level 隐式表达（层数已满不再加、为 0 不再减）；
- **cross 语义**：以前一根 close → 当前 close 是否穿越档位为判据；**日线下"当日反向信号"判据明确为「close 是否穿越反向档位」**（不用 high/low，因日线无盘中时序，详见 spec §S1.3 日线 T+1 判据）；
- **跳空去重**：单根 bar 穿越多档时，只对 grid_level 最近反向档位发单；
- **状态推进以订单 filled 为准**：grid_level 仅在 `on_order` 收到 Filled 后 ±1，挂单失败/挂起不推进；
- **范式隔离**：method=grid 的策略**禁止进入 GRID/WF 的 param_grid**（validator 拒绝），因 grid_level 跨段重置与实盘续跑语义冲突；
- **持久化登记（二期）**：grid_level 跨会话/跨天持久化与 005 `run_warm_start` 对齐，一期回测单段跑无需，spec 须登记避免漏写。

### FR-G4：网格前端编辑器
- 在策略编辑器 Tab「仓位管理」新增"网格交易"专属控件（method=grid 时切换显示）：
  - 四要素表单：中枢价（输入框 / "首根收盘"开关）、间距（百分比/绝对单选 + 数值）、每格数量、最大格数；
  - 必填风控：止损（绝对价 / 百分比二选一）、时间止损（max_holding_bars）、总仓上限（max_position_value_pct）；
  - 可选：止盈线、未成交重试 bar 数、复权模式（一期固定 forward_adjusted，只读展示）。
  - 实时预览：根据当前输入画出"网格档位示意图"（横轴价格、纵轴档位，标出每格买卖动作）。
- **资金占用预估**（必修）：按 `max_grids × qty_per_grid × center_price` 实时计算并显示占用资金与初始资金占比，超 50% 强警告（震荡市不建议单票超半仓）、超 90% 禁止保存。
- **成本预估**（必修）：按 broker_profile（默认 cn_stock_miniqmt）估算"每格净收益"：
  - 每格毛收益 = `qty_per_grid × step_value × center_price`（percent 模式）或 `qty_per_grid × step.value`（absolute 模式）；
  - 每格成本 = 买入佣金 + 卖出佣金 + 卖出印花税 + 双边滑点；
  - 显示"每格净收益"与"成本占毛收益比例"，若成本占比 > 50% 强警告（网格在费用下可能负期望）。
- **初始资金校验**（必修，对齐 P-15）：用户初始资金 < `max_grids × qty_per_grid × center × 1.1` 时，禁止保存并提示"当前资金不足，建议降低 max_grids 或 qty_per_grid"。

### FR-G5：网格回测验证（硬验收用例，不通过即不验收）
基础用例：中枢 10 / 间距 2% / 每格 200 / max_grids 3，在 9.0-11.0 震荡 1 年的合成数据上验证盈亏对账。**必须额外覆盖以下 A 股实战陷阱用例**（G-T1~G-T11 为 PRD 原始用例，T-G-12~T-G-29 为 spec 阶段补充，详见 spec §S7 测试矩阵）：

| # | 用例 | 构造 | 期望 |
|---|---|---|---|
| G-T1 | 跳空穿越 | 一根 bar 从 9.6 直接开盘 10.5 | 只成交一档（最近反向格），grid_level 只 ±1 |
| G-T2 | T+1 反转 | D3 close=9.4（买第 3 格）/ D4 O=9.4 H=9.7 C=9.6（close 穿 9.6 卖档） | 卖单挂起、grid_level 不变、D5 开盘成交 |
| G-T3 | 一字跌停 | 连续 3 根跌停板 bar | 买单未成交、grid_level 不推进、按 unfilled_retry_bars 重试后放弃 |
| G-T4 | 除权日 | 中途 10 转 10（adj_factor=0.5） | forward_adjusted 下中枢不变、档位不漂移、成本按前复权价 |
| G-T5 | 单边下跌止损 | 连续阴线触达 stop_loss_pct | 全平仓、grid_level=0、不再加仓 |
| G-T6 | 时间止损 | 持仓达 max_holding_bars | 强平、释放资金 |
| G-T7 | 资金不足 | max_grids 打满后再跌 | 不爆 Reject、优雅停止加仓 + warning |
| G-T8 | 涨跌停风控 | 涨停板试图买入 | 不成交（视为挂单失败走重试） |
| G-T9 | 复权成本对账 | 同段数据前复权 vs 真实价回测 | 档位触发一致、成本统计坐标系一致（前复权价 × 量） |
| G-T10 | 停牌复牌 | 中段停牌 5 根后复牌跳空 | 停牌段不触发、复牌首根按跳空规则 |
| G-T11 | 网格密度 vs 佣金临界 | 间距 0.3% + 万三佣金 + 中枢 10 | 成本占比 > 50% 强警告、净收益为负（对齐 FR-G4 成本预估） |
| T-G-12~T-G-29 | 状态机/费用/复权/正负样本沙盘 | 详见 spec §S7 测试矩阵 | 见 spec |

**成本对账**：总盈亏 = 各格成交盈亏之和 − 买入佣金 − 卖出佣金 − 卖出印花税（仅卖） − 滑点成本。手工核算误差 < 0.01 元。**对账口径详见 spec §S3（6 位内部计算 + 2 位展示截断 + 双 oracle：手工逐步口径 vs akquant trades_df.net_pnl 口径，二者允许差 < 0.01）**。

**真实数据沙盘（spec 前置必修）**：覆盖**正负两类样本**——
- **负样本（应死）**：2015-06 股灾、2024-01 小盘股崩盘、2020-03~07 单边上涨卖飞、2024-09-24~10-08 反转；网格不应跑出"虚假正收益"；
- **正样本（应活）**：2017-2018、2022-05~08 震荡市；网格应跑出正收益或显著跑赢 buy-and-hold，否则说明逻辑虽无 bug 但无价值（对齐 P-14）。

## 6. 风险与边界

- **akquant 撮合层是否支持"条件挂单 + 去重"**：落地 spec 前需核实 `place_bracket_order` / OCO 语义是否可复用，或必须在 compiler 层用 `on_bar` 内 Python 逻辑模拟（预期后者，因 akquant 未暴露通用 grid 订单类型）。
- **单边行情风险**：单边下跌一路套牢、单边上涨一路卖飞。**强制 max_grids ≤ 20**、止损必填、时间止损必填、总仓上限 ≤ 90%（留 10% 缓冲佣金/滑点/印花税，防理论 100% 实际跑出 102% 爆 Reject）。
- **资金占用**：max_grids × qty_per_grid × center_price 不可超过初始资金 × `max_position_value_pct`（默认 0.9），validator 做预校验；前端超 50% 强警告。
- **网格与 exit.bracket 冲突**：method=grid 时，exit.bracket / exit.rules 必须为空（grid 自带止损止盈语义，避免双重平仓），validator 强校验。
- **印花税方向**（A 股特有）：stamp_tax_rate 仅卖出扣（见 akquant 04 §2）。网格买卖频繁，**卖单累计印花税可能吃掉所有网格利润**——FR-G4 成本预估必须显式展示印花税项，避免用户误以为网格无成本。
- **数据频率**：一期仅支持日线（见 §1.3 非目标）；分钟线网格在二期���估。
- **最小交易单位**：qty_per_grid 最小 100 股，max_grids 上限 20 → 单票最大 2000 股，10 元票需 2 万；小资金用户须靠 FR-G4 资金占用预估提前预警。

---

## 第二部分：GRID 参数寻优 + WALK-FORWARD（能力二）

## 7. 业务语义

### 7.1 GRID 网格寻优
用户指定「可调参数 + 候选值网格」，引擎对参数全组合逐一跑回测，返回按指定指标排序的 Top-N 组合。例：双均线策略 `{fast: [5,10,20], slow: [20,30,60]}` 共 9 组合，按 sharpe_ratio 降序。

### 7.2 WALK-FORWARD 滚动样本外验证
把数据切成多个「训练集 + 测试集」窗口，每窗在训练集上跑 GRID 选最优参数 → 在测试集上样本外回测 → 拼接所有样本外资金曲线。**目的是防过拟合**：如果样本内最优参数在样本外也能跑出类似收益，说明参数稳健。

### 7.3 为什么必须配 GRID + WF
- 只跑 GRID：容易选出"恰好在这段数据上最优"的参数（过拟合），换段数据就崩。
- 只跑 WF：不知道哪组参数好，WF 内部仍需 GRID 选优。
- **正确姿势**：GRID 选 Top-N → WF 验证 → 样本内外收益差小、参数稳定（变异系数小）才算"可信参数"。

## 8. 当前为什么做不了（已核实）

- engine/watcher 三层未接入 akquant 的 `run_grid_search` / `run_walk_forward`；
- 005 PRD §6.1-6.2 已预告设计，但未落地；OQ-3（paramGrid 形参 schema 反推）未解决；
- constraint / resultFilter 当前无结构化表达，需设计 DSL（禁 eval）。

## 9. 功能需求（FR）

### FR-O1：GRID 寻优 engine 接入（四段式）
- **输入**：策略 config_json、param_grid、sort_by、max_workers、constraint（结构化 DSL）、resultFilter（结构化 DSL）。
- **处理口径**：
  - engine `services/backtest/optimizer.py` 封装 `aq.run_grid_search`；
  - **db_path 硬编码不传**（即便上层传了也 drop，依据 akquant 规则 06 §3 + engine 禁 sqlite3 写盘，见 005 NFR-2）；
  - **Strategy 类 pickle 链路**（研发硬约束，对齐 P-03）：compiler 生成的 Strategy 类必须落盘到 importable 模块（`services/backtest/_generated/strat_{hash}.py`）再动态 import，确保多进程 pickle 通过；**开工前必须先做 pickle 可行性 spike**（见 spec §S4）；
  - **constraint/resultFilter DSL → callable**（对齐 P-04）：由 `services/shared/condition_dsl.py` 的 `compile_constraint(dsl)` 编译为 pickle 安全的 callable。
- **输出**：DataFrame 序列化（参数列 + 指标列 + `_duration`），NaN→None，Timestamp→isoformat。
- **数据流（研发硬约束，对齐 P-10）**：engine 跑完 → JSON 序列化 → HTTP 响应回 watcher → watcher 决定是否落 `optimization_result` 表（schema 由 watcher 定，engine 不关心、不直连业务库）。
- **验收**：GRID 返回 Top-N 与 akquant 原生 `run_grid_search` 排序一致、指标数值差 < 1e-9（T-O-2）；constraint DSL 翻译与手写 predicate 一致（T-O-3）。

### FR-O2：constraint / resultFilter 结构化 DSL
- 用 `{left, op, right}` 三元组，禁用 eval（与统一 Schema §4 ConditionTree 同模型复用）：
  ```json
  "constraint":    { "left": "fast", "op": "<", "right": "slow" }
  "resultFilter":  { "left": "max_drawdown_pct", "op": "<", "right": 20 }
  ```
- 支持的逻辑运算符：`<` / `<=` / `>` / `>=` / `==` / `!=`；
- 支持嵌套 AND/OR（与 ConditionTree 同构，复用 ConditionEngine）。
- **DSL → callable 转换层**（研发硬约束，对齐 P-04）：`services/shared/condition_dsl.py` 提供 `compile_constraint(dsl) -> Callable[[dict], bool]`，返回的 callable 必须可被多进程 pickle（用 `functools.partial` 或落盘类的 `__call__`，禁闭包捕获局部变量，禁 lambda）。

### FR-O3：paramGrid 形参 schema 反推（OQ-3 落地）
- **核查前置（已核实）**：004 统一策略配置 Schema 的 config_json **当前无**「可调参数列表」字段。本 PRD 要求 004 扩展 config_json 增加 `tunable_params: [{name, type, default, min, max, step}]` 字段，所有预置模板（spec 014 的 8 个）回填（第零波）。
- **compiler 形参绑定**（研发硬约束，对齐 P1）：compiler 生成 Strategy 类时，若 config_json 含 `tunable_params`，须将其展开为 `__init__` 的显式形参（仅 `POSITIONAL_OR_KEYWORD`，**禁 `**kwargs`**——否则 akquant `_validate_strategy_param_grid_keys` 校验被绕过），并把这些参数注入到 on_bar 计算逻辑（如均线的 fast/slow 周期）。**tunable_params 的 name 如何绑定到 on_bar 内具体用法属于模板元数据，spec 须定义绑定规则**。
- **安全保证**：param_grid 的 key 必须是 `tunable_params` 里的 name，validator 校验，拒绝未知参数（akquant `strict_strategy_params=True`）。
- **向后兼容**：`tunable_params` 为可选字段，存量配置不填则 paramGrid 编辑器禁用（用户无法寻优，但不影响单次回测）。

### FR-O4：WALK-FORWARD engine 接入
- engine `optimizer.py` 封装 WF，但 **akquant `run_walk_forward` 不返回每段最优参数表**（核实结论见 §11.1），FR-O5 的段一致性/CV 指标需 engine **自写切窗循环**（参考 akquant optimize.py:905 步进逻辑），每段调 `run_grid_search` 拿 best_params，累积成段表后再算过拟合指标。
- **入参**：train_period、test_period、metric、compounding；
- **数据长度校验**：必须 ≥ `train_period + test_period`，否则 `BACKTEST_INSUFFICIENT_DATA`；
- **返回**：拼接的样本外资金曲线 + 每段最优参数表 + 过拟合判定指标。
- **窗口对齐 A 股周期**（`window_align`，默认 `"year"`）——⚠️ **语义澄清（对齐 P-02）**：akquant `run_walk_forward` 内部是**等距 bar 数步进**，engine 把 `year` 换算为约 244 bar、`quarter` 换算为约 61 bar 后，**切窗点落在的是「等距 bar 数」而非「真正的自然年/季初」**。
  - `"bar_count"`：固定 bar 数（akquant 原生，仅作兼容）；
  - `"year"`（默认）：换算为 ~244 bar/年，等距切窗（非自然年切）；
  - `"quarter"`：换算为 ~61 bar/季，等距切窗。
  - 一期采用「bar 数换算」省事方案；若需真正按自然年/季切，需 engine 层完全自写切窗（不依赖 akquant `run_walk_forward`），二期评估。

### FR-O5：过拟合判定指标（多维稳健性，分层展示）
> ⚠️ **6 维指标的精确数学公式 + 黄金样本 oracle 见 spec §S5**（PRD 只列业务含义，不断言"缺一不可"，避免过度工程化）。

- **收益维度**：in_sample_return / out_of_sample_return / 收益差（相对值：差 / in_sample_return，含除零保护）；
- **回撤维度**：in_sample_max_dd / out_of_sample_max_dd / 回撤比（out/in，> 2.0 判死刑）；
- **参数稳定性**：各 WF 段最优参数的变异系数（std/mean），越小越稳健；
- **参数梯度（孤峰检测）**：Top-3 参数的指标梯度——最优 sharpe 2.0、第二 0.5 = 孤峰 = 极度过拟合；最优 2.0、第二 1.9、第三 1.8 = 平台 = 稳健。计算 `top1 - top2` 差值，差值越大越可疑；
- **WF 段一致性**：N 段 WF 中各段最优参数的列表（直观判断"5 段全选同一组"还是"5 段选了 5 组"），N 段全相同则高置信；
- **交易笔数稳定性**：in_sample 交易笔数 / out_of_sample 交易笔数比，比例悬殊（如 100:5）= 样本外没机会开仓 = 参数失效。
- **分层展示**（对齐 P-08）：P0 用户默认只看「收益差 + 回撤比」+ 综合「可信度评分 0-100」；P1 用户展开看 CV/孤峰差；专家模式看段一致性/笔数比。

### FR-O6：「应用最优参数」降级处理 + 置信度展示 + 正向反馈
- GRID 跑完只展示 Top-N + 「复制参数」按钮（复制到剪贴板，用户手动粘贴到新策略）；
- **不自动创建策略版本**（避免过拟合背书，见 005 §6.1）；
- **「应用」按钮语义明确**（对齐 P-14）：点击 = 跳转「新建策略」页预填模板 + 应用最优参数，**用户仍需手动点保存**才算创建版本；GRID 永远不直接写策略表。
- 只有当 Top-1 参数同时通过 WF 验证时，才允许「应用」按钮可点。**"通过 WF 验证"判据（默认阈值，可配）**：
  - 收益差相对值 < `max_return_gap`（默认 0.3，即样���外收益不低于样本内 70%）；
  - 回撤比 out/in < `max_dd_ratio`（默认 2.0）；
  - 参数变异系数 < `max_param_cv`（默认 0.5）；
  - WF 通过段数 / 总段数 ≥ `min_wf_pass_ratio`（默认 0.6）；
  - 孤峰差值（top1−top2）< `max_peak_gap`（默认 sharpe 0.5）；
  - **段一致性**：N 段 WF 最优参数组合的去重数 / N ≤ `max_segment_diversity`（默认 0.4，即 5 段里去重后 ≤ 2 组才算一致；5 段选了 5 组 = 无规律 = 不过）。
- **置信度徽标 UI**（FR-O7 展示）：在 Top-1 行显示「WF 通过段数 / 总段数」+「样本内外收益差」+「参数稳定性 CV」+「孤峰差值」四个徽标，让用户自己判断而非黑箱开关。
- **正向反馈设计**（对齐 P-10，防劝退）：
  - 即使未通过 WF，也展示「你比无脑 Top-1 避免了 X% 的过拟合风险」（量化正向反馈）；
  - 通过 WF 时用绿色徽标 + 「该参数组合在 N/M 段样本外稳定」（给成就感）；
  - 「应用」按钮即使置灰也 hover 显示「如何让它可点：调整 WF 阈值 / 增加数据 / 更换参数空间」（引导而非阻断）；
  - 未通过 WF 的结果也可被收藏研究（配合 FR-O6b）。
- **强提示**：「应用」按钮旁永久挂"历史最优不代表未来"警示文案。

### FR-O6b：参数方案收藏夹（新增，对齐 P-03）
- 通过 WF 的参数组合可被用户「收藏为参数方案」，挂在某策略下；
- 收藏夹显式标注「采集时间 / 数据区间 / WF 通过段数 / 当时 sharpe / 当时 6 维指标」，**不抹除时间戳**（防用户把 2023 年最优参数当成永恒真理）；
- **收藏 ≠ 上线实盘**：复用时需重新跑回测确认；
- 存储口径：watcher 侧 `param_scheme` 表（engine 不存）；
- 未通过 WF 的结果也可收藏（标注「未通过 WF，仅供研究」）。

### FR-O7：前端 paramGrid 编辑器
- 在回测中心新建「参数寻优」Tab：
  - 上半：paramGrid 编辑器（从策略 config 反推形参，用户填候选值，支持范围生成如 `5..20 step 5`）；
  - 中间：constraint / resultFilter 结构化编辑器（三元组 + AND/OR 嵌套）；
  - 下半：结果表（参数列 + 指标列，按 sort_by 排序，Top-N 高亮）+ 「复制参数」/「跑 WF 验证」/「收藏为参数方案」按钮。
- **指标单位归一化**（对齐 T-Q14）：GRID 返回 DataFrame 的列单位表须与 akquant 规则 05 §3 对齐（`total_return_pct`/`max_drawdown_pct` 为原始百分数 15.0=15%），前端序列化层正确 ÷100 展示。

### FR-O8：性能与并发
- **max_workers 容器化感知**（对齐 P-08，研发硬约束）：engine 不直接透传 `cpu_count()`（容器内 `multiprocessing.cpu_count()` 返回宿主机核数，会过载），而是读 cgroup quota（`/sys/fs/cgroup/cpu.max` 或 `cpu.cfs_quota_us`）算实际可用核数，向下取整；前端 max_workers 下拉项根据 engine 上报的可用核数动态生成。
- **用户级并发限制**（对齐 P-12）：每用户同时运行 GRID 任务数 ≤ 2、WF 任务数 ≤ 1（可配）；
- 单任务超时 `timeout`（默认 600s），超时 worker 重启（`max_tasks_per_child=1` 清理僵尸）；
- GRID 任务纳入回测中心任务状态机（PENDING→RUNNING→SUCCESS/FAILED/CANCELLED），支持取消；**取消信号须穿透到子进程**（akquant 多进程下需验证）。

### FR-O9：寻优历史与对比（新增，对齐 P-04）
- 寻优任务列表（含时间、参数、Top-1 指标、WF 是否通过），数据存 watcher（保持数据单源性）；
- 支持两次 GRID 结果的「指标对比视图」（双列 Top-N 对齐）；
- 存储口径：engine 不存，watcher 存任务元数据 + 结果快照。

### FR-O10：跨寻优结果对比（新增，对齐 P-05，第二波）
- 同一策略两次 GRID 的 Top-N 对齐表 + 参数变化指示器；
- 第二波交付（依赖 FR-O9）。

### FR-O11：资源预估前置（新增，对齐 P-12）
- 用户点「开始寻优」前，前端预估「本次约耗时 X 分钟、占用 Y 核」，超阈值需二次确认；
- 预估公式：`耗时 ≈ 单组合耗时 × param_grid 组合数 / max_workers`，单组合耗时由历史任务统计（首次默认值由 spec 定）。

## 10. 风险与边界

- **过拟合风险**：即使用 WF，仍可能过拟合。FR-O6 的「不自动创建版本」是硬约束，UI 必须强提示"历史最优不代表未来"。
- **多进程 pickle**：策略类必须定义在可 import 的模块（不能 `__main__`）；constraint/resultFilter 用结构化 DSL 而非 lambda（lambda 不可 pickle）。
- **资源占用**：9 组合 × 3 年数据 × 4 worker 可能跑几分钟；GRID 任务需独立资源池，不能挤占单次回测的并发槽位。
- **OQ-3 阻塞**：若 004 config_json 未存 tunable_params，FR-O3 需先做 004 扩展，否则 paramGrid 编辑器只能让用户手填形参名（不可接受）。

---

## 11. 落地前置条件（开工前必须解决）

### 11.1 共同前置
1. **本 PRD 评审通过**（含老股民视角评审，见 §13）；
2. **akquant 版本锁定**：确认 `akquant==0.2.47` 的 `run_grid_search` / `run_walk_forward` / `place_bracket_order` 行为与本文假设一致（落地 spec 前做源码级核实）。

### 11.2 网格交易前置
1. **方案 B 定稿**：独立 spec 出 Schema 字段定义 + 错误码 + compiler 改造详案；
2. **撮合层核实**：akquant 是否支持条件挂单 + 去重，或必须 compiler 层 Python 模拟；
3. **实盘续跑持久化（二期登记）**：grid_level 跨会话/跨天持久化，与 005 快照续跑（run_warm_start）对齐；一期回测单段跑无需，spec 须登记避免漏写。

### 11.3 GRID 寻优前置
1. **OQ-3 解决**：核查 004 config_json 是否有 tunable_params；若无，先扩展 004；
2. **结构化 DSL 共享层**：与 Phase 2 的 rebalance/exit.rules 共用 ConditionEngine，需先抽 `services/shared/`（见 005 §12.3 前置）；
3. **任务状态机扩展**：GRID/WF 任务纳入回测中心任务状态机，需扩展 watcher 的 BacktestService。

---

## 12. 验收标准（整体验收，可度量口径）

> 每条验收必须可映射到 ≥1 个测试用例编号（详见 spec §S7 测试矩阵）；模糊词「能配置」「正确生效」一律替换为可断言口径。

### 12.1 网格交易（必须全部通过）
- [ ] **前端配置**（→ T-G-26 Schema 校验）：四要素 + 必填风控，字段缺失/互斥/超范围时 validator 拒绝并返回具体错误码；资金占用 > 90% 或初始资金 < `max_grids×qty×center×1.1` 时禁止保存；
- [ ] **网格档位/资金/成本预估实时联动**：输入变化 200ms 内重算（无 throttling 卡顿）；
- [ ] **状态机正确**（→ T-G-12~T-G-16）：grid_level 仅在 `on_order` Filled 后 ±1，挂单失败/T+1 挂起不推进；
- [ ] **T+1 / 涨跌停 / 手数 100**（→ T-G-2/T-G-3/T-G-8/T-G-21~T-G-24）：按 akquant 04 规则生效，可断言；
- [ ] **单边止损/时间止损/总仓上限**（→ T-G-5/T-G-6/T-G-7）：触发后 grid_level=0、释放资金、停止加仓；
- [ ] **盈亏对账**（→ T-G-14）：总盈亏 = 各格成交盈亏之和 − 佣金 − 卖出印花税 − 滑点，误差 < 0.01 元（双 oracle：手工逐步 vs akquant trades_df.net_pnl）；
- [ ] **G-T1~G-T11 + T-G-12~T-G-29 全部通过**；
- [ ] **真实数据沙盘**：2015-06 / 2024-01 / 2020-03~07 / 2024-09 负样本不出现"虚假正收益"；2017-18 / 2022-05~08 正样本跑出正收益或跑赢 buy-and-hold。

### 12.2 GRID 寻优（必须全部通过）
- [ ] **paramGrid 反推**（→ T-O-2）：从 config_json.tunable_params 自动生成编辑器，未知参数名被 validator 拒绝；
- [ ] **GRID Top-N 排序**（→ T-O-2）：9 组合 × 3 年数据 60s 内返回 Top-10，与 akquant 原生 `run_grid_search` 排序一致、指标差 < 1e-9；
- [ ] **constraint/resultFilter DSL**（→ T-O-3）：6 组 DSL 翻译与手写 predicate 一致，无 eval；
- [ ] **WF**（→ T-O-4/T-O-5）：5 段 × 3 组合 5 分钟内返回，6 维过拟合指标全部有值且非 NaN，公式与 spec §S5 黄金样本一致（差 < 1e-4）；
- [ ] **「应用」按钮**（→ O-T1）：未通过 WF 五项判据时置灰，hover 显示引导；通过时可点且需二次确认勾选；
- [ ] **db_path 硬编码不传**（代码审计）：engine 源码无 sqlite3 写盘；
- [ ] **任务状态机**（→ T-O-6/T-O-7/T-O-8）：GRID 取消 60s 内终止、超时 FAILED、并发隔离；
- [ ] **max_workers cgroup 感知**：容器内返回 cgroup quota 而非宿主机核数；
- [ ] **指标单位归一化**（→ T-O-15）：`total_return_pct=15.0` 表示 15%，前端展示正确 ÷100；
- [ ] **O-T1 过拟合反向用例**：构造已知孤峰参数（fast=5/slow=20 某段 sharpe=3.0、其余 <0.5），期望「应用按钮置灰 + 孤峰徽标告警」。

---

## 13. 老股民视角评审（子代理已执行，两轮）

> 本节由「20 年 A 股实战老股民视角」子代理评审填充。第一轮总评「有条件通过」，提出 11 项 🔴 必须修复 / 5 项 🟠 建议修复 / 3 项 🟢 可选优化；本 PRD 已按评审意见全量修复 🔴 与 🟠（详见各 FR 修订与下方修复对照表）。**第二轮复核总评 ✅ 通过，宣布可进入 spec 阶段**，无新增 🔴。第二轮另提 6 项 🟠 建议已全部补入（center 前瞻提示、re_entry_after_clear、实盘持久化登记、段一致性硬阈值、O-T1/G-T11 用例）。

### 13.1 第一轮评审问题修复对照表

| 问题编号 | 问题 | 修复位置 | 状态 |
|---|---|---|---|
| 🔴 G-1 | 缺不对称网格/金字塔加仓 | §1.3 非目标 + FR-G1 预留 step_mode/qty_mode 字段位 | ✅ 字段位预留 + 一期不做明示 |
| 🔴 G-2 | 缺除权除息中枢漂移 | §2.4 复权对齐 + FR-G1 adjust_mode 字段 | ✅ forward_adjusted 默认锁定 |
| 🔴 G-5 | 涨跌停挂单失败状态推进 bug | FR-G2/FR-G3"订单 filled 后才 ±1"+ unfilled_retry_bars | ✅ |
| 🔴 G-7 | 止损可选、无时间止损/总仓上限 | FR-G1 改为必填（stop_loss/max_holding_bars/max_position_value_pct） | ✅ |
| 🔴 O-3 | 过拟合指标过单薄 | FR-O5 扩展为 6 维（收益/回撤/CV/孤峰/段一致/笔数比） | ✅ |
| 🔴 O-5 | tunable_params 阻塞未在路线图标前置 | §14 新增第零波 + 依赖硬约束 | ✅ |
| 🔴 整-1 | 复权价 vs 真实价成本未对齐 | §2.4 + FR-G5 G-T9 对账用例 | ✅ |
| 🔴 整-2 | T+1 反转示例误导、未覆盖 | §2.1 示例表重做 + FR-G2 挂起语义 + G-T2 用例 | ✅ |
| 🔴 边-1 | 印花税方向/网格成本预估 | FR-G4 成本预估 + §6 印花税方向说明 | ✅ |
| 🔴 边-2 | 数据频率未声明 | §1.3 非目标"一期仅日线" + §6 数据频率 | ✅ |
| 🔴 边-3 | 最小交易单位/资金占用预估 | FR-G4 资金占用预估 + §6 最小交易单位 | ✅ |
| 🟠 G-4 | cross 未处理跳空 | FR-G3 跳空去重 + G-T1 用例 | ✅ |
| 🟠 G-6 | 停牌时段未处理 | FR-G2 停牌处理 + G-T10 用例 | ✅ |
| 🟠 G-8 | 资金 100% 未留缓冲 | §6 总仓上限 ≤ 90% | ✅ |
| 🟠 O-1 | WF 窗口未对齐 A 股周期 | FR-O4 window_align 默认 year | ✅ |
| 🟠 O-4 | "样本内外收益差阈值"未定义 | FR-O6 五项判据默认阈值 | ✅ |
| 🟠 验收 | §12 用例不足 | FR-G5 G-T1~G-T10 + 真实数据沙盘 + §12 | ✅ |
| 🟢 G-9 | 不做空头网格写入非目标 | §1.3 非目标 | ✅ |
| 🟢 G-10 | grid_level 持久化登记 | FR-G3 持久化登记（spec 阶段） | ✅ |
| 🟢 O-2 | WF 置信度多维度徽标 | FR-O6 置信度徽标 UI | ✅ |

---

## 14. 落地路线图（重切分期，对齐 P-07：GRID + WF 合并交付）

| 波次 | 能力 | 估时 | 前置 |
|---|---|---|---|
| **第零波** | **004 config_json 扩展 tunable_params + spec 014 八个预置模板回填** | 小 | OQ-3 核查（FR-O3 阻塞项，GRID 开工命门） |
| **第一波 A** | 网格交易（能力一） | 大 | 方案 B spec 定稿 + GridStrategy 独立类设计（spec §S2）+ 真实数据沙盘（G-T1~T-G-29）+ pickle 可行性不阻塞（网格单段跑无需多进程） |
| **第一波 B** | **GRID + WF 合并交付**（GRID 完整版 + WF 简化版：≥2 段、bar_count 对齐、3 维核心指标） | 中~大 | 第零波完成 + GRID pickle spike 通过（spec §S4）+ 共享 ConditionEngine 抽取 + watcher 协议对齐（spec §S6） |
| **第二波** | WF 增强（year/quarter 对齐 + 6 维指标全开）+ 对比分析（FR-O9/O10）+ 不对称网格 | 中 | 第一波 B 完成 |

> **重切理由（对齐 P-07）**：原方案第一波只交 GRID 时「应用」按钮永远置灰（FR-O6 要求通过 WF 才能应用），用户体验断裂。合并后第一波 B 结束即交付「可信参数 + 可应用」完整闭环。WF 简化版（bar_count + 3 维）足以支撑「应用」按钮可用性；year/quarter 对齐与 6 维指标留第二波增强。
> **依赖硬约束**：
> - GRID 开工前**第零波必须完成**（tunable_params 是 paramGrid 编辑器从 config 反推形参的唯一数据源）；
> - GRID 开工前**pickle 可行性 spike 必须通过**（spec §S4），否则 fallback `max_workers=1`（性能不可接受，需产品决策是否排期）；
> - 网格与 GRID 改动文件不重叠（一个在 strategy compiler、一个在 optimizer），可分两人并行；
> - **网格前置沙盘硬约束**：G-T1~G-T11 + T-G-12~T-G-29 + 真实数据沙盘（正负样本）必须先通过，方可进入开发。

---

## 15. FR 依赖 DAG（新增，对齐 P-16）

```
第零波（004 扩展）
  └─ tunable_params 字段
        │
        ├─→ FR-O3（paramGrid 反推）── 依赖 ──→ compiler 形参绑定（P1）
        │                                      │
        │                                      ↓
        │─→ FR-O1（GRID 接入）── 依赖 ──→ pickle spike（spec §S4, P3）
        │                   └── 依赖 ──→ FR-O2（DSL, P4）── 依赖 ──→ services/shared/condition_dsl.py
        │                                                       └── 依赖 ──→ ConditionEngine 抽取（005 Phase2 前置）
        │                   └── 依赖 ──→ watcher 协议（spec §S6, P10）
        │
        └─→ FR-O4（WF 接入）── 依赖 ──→ FR-O1 + engine 自写切窗循环（P13）
                    └── 依赖 ──→ FR-O5（6 维指标）── 依赖 ──→ spec §S5 黄金样本

网格（独立线）
  └─ FR-G1（Schema grid 枚举）
        └─→ FR-G2（compiler 改造）── 依赖 ──→ GridStrategy 独立类（spec §S2, P12）
                    └── 依赖 ──→ 状态机定义（spec §S1, P5/P6）
        └─→ FR-G4（前端）── 依赖 ──→ cost_estimator 共享（P15）
        └─→ FR-G5（验证）── 依赖 ──→ 对账口径（spec §S3, T-Q2）+ 真实数据沙盘
```

**强依赖链（必须按序）**：
1. 第零波 → FR-O3 → FR-O1 → FR-O4 → FR-O5；
2. pickle spike 是 FR-O1 的硬前置（不通过则 GRID 整条线阻断）；
3. GridStrategy 独立类（spec §S2）是 FR-G2 的硬前置。

---

## 16. 相关文档

- [011 spec：网格与参数寻优技术 spec](./011-网格交易与参数寻优-spec.md)（**本文配套 spec，含 §S1~§S7 六项硬物料 + 测试矩阵**）

- [010-策略模板能力扩展](./../010-策略模板能力扩展/策略模板能力扩展PRD.md)（本 PRD 拆分源）
- [004-统一策略配置 Schema](./../004-策略管理/统一策略配置Schema.md)（position_sizing / tunable_params 扩展点）
- [005-回测中心 PRD](./../005-回测中心/回测中心PRD.md) §6.1-6.2（GRID/WF 设计原案）
- [005-Phase 2 待开发功能清单](./../005-回测中心/Phase2-待开发功能清单.md)（共享层抽取前置）
- [006-策略范式互斥改造](./../006-策略范式治理/策略范式互斥改造PRD.md)（grid 与 rebalance 互斥约束来源）
- `.trae/rules/系统设计理念(用法建议).md`（避坑清单）
- `.trae/rules/akquant/04-backtest-run.md` / `06-optimization.md`（akquant 寻优 API 参考）
