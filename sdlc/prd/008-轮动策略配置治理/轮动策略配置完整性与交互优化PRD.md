# 011 轮动策略配置完整性治理与交互优化

> **版本**：v1.0
> **日期**：2026-07-15
> **对齐**：akquant 0.2.47 · 统一策略配置 Schema v1.0 · 007 轮动范式未来数据治理 v1.0
> **状态**：待评审

---

## §0 背景与问题清单

### 0.1 触发原因

对「策略管理 - 轮动范式」的全量配置项做 akquant 源码级核对（核对范围覆盖前端表单 → watcher → engine `models/validator/compiler/runner/rebalance_engine` → akquant `rebalance_to_topn`），发现 **21 类系统性问题**，分两波发现：
- **第一波（§1~§9，原 10 类）**：4 类功能空壳/逻辑断层、3 类交互缺陷、2 类可用性短板、1 类数据治理收紧；
- **第二波（§15，新增 11 类）**：从老股民视角审视「回测可信度」与「实战可用性」，发现 3 类致命（回测数字虚高）、4 类重要（实战刚需缺失）、4 类体验问题。

### 0.2 问题总览

#### 第一波：功能完整性与交互（§1~§9）

| # | 问题 | 严重度 | 类型 | 涉及层 |
|---|---|---|---|---|
| **P0-0** | 申万行业分类数据未对接（行业暴露/行业过滤的前置依赖） | 🔴 高 | 数据对接 | watcher |
| **P0-1** | 7 项静态过滤在回测路径下完全空壳（exclude_st / exclude_suspended / exclude_limit_up / exclude_limit_down / industries / exclude_industries / min_list_days） | 🔴 高 | 功能缺失 | engine |
| **P0-2** | `weight_mode=score` 在 `factor.method=single` 下灾难性配仓（单只股票可独占 30%+ 资金） | 🔴 高 | 逻辑断层 | engine / compiler |
| **P1-1** | point_in_time 应强制开启，关闭=数据错误，不应提供兜底 | 🟠 中高 | 数据治理 | 全链路 |
| **P1-2** | 权重配置面板（Factor 层）交互缺陷：factorKey 裸文本输入、无公式说明、负权重语义不清、无组合校验 | 🟠 中高 | 交互 | 前端 |
| **P1-3** | 缺少现金保留比例（cash_reserve_pct），无法配置「8 成仓位 2 成现金」 | 🟡 中 | 可用性 | engine / 前端 |
| **P1-4** | 缺少单标的最大权重 / 行业暴露上限 | 🟡 中 | 可用性 | engine |
| **P2-1** | day_of_period 用自然日判断导致 off-by-one / 不支持月末调仓（改为 trade_cal 预计算标记 + trigger 语义） | 🟡 中 | 逻辑 | watcher / engine / 前端 |
| **P2-2** | fill_policy 前端无控件 | 🟢 低 | 可用性 | 前端 |
| **P2-3** | risk_config 前端无控件（max_position_pct 等） | 🟢 低 | 可用性 | 前端 |
| **P2-4** | 缺少最小持仓周期（滞回机制），轮动易过度换手 | 🟢 低 | 可用性 | engine |

#### 第二波：回测可信度与实战可用性（§15，老股民视角新增）

| # | 问题 | 老股民关注度 | 类型 | 涉及层 |
|---|---|---|---|---|
| **P0-3** | 调仓成交价时机不清（默认次日开盘 vs 实战尾盘收盘），回测与实盘脱节 | 🔴🔴🔴 | 回测可信度 | engine / 前端 |
| **P0-4** | 涨跌停买入不真实（选股排除≠成交拒单），回测虚高仓位 | 🔴🔴🔴 | 回测可信度 | engine |
| **P0-5** | 资金不足时静默拒单，选出 30 只可能只买到 15 只，用户无感知 | 🔴🔴 | 回测可信度 | engine / 前端 |
| **P1-5** | 轮动范式 Tab6 止损止盈被隐藏（data-paradigm=signals），轮动裸奔无止损 | 🔴🔴 | 实战缺失 | 前端 |
| **P1-6** | 选股条件不支持「N 日均值/滚动窗口聚合」，无法表达均线多头等趋势条件 | 🔴🔴 | 实战缺失 | engine / schema |
| **P1-7** | 换仓无缓冲带（buffer），边缘标的反复进出吃手续费 | 🔴 | 实战缺失 | engine |
| **P2-5** | warmup_period 被自动推断覆盖但用户无感知 | 🟠 | 体验 | engine / 前端 |
| **P2-6** | 条件树可视化/JSON 模式切换可能丢数据 | 🟠 | 体验 | 前端 |
| **P2-7** | composite 权重无归一化提示（用户不知 1.5 实际是 65%） | 🟡 | 体验 | 前端 |
| **P2-8** | 回测结果无「年化换手率」指标 | 🟡 | 体验 | engine |
| **P2-9** | 无分批调仓能力（大资金冲击成本未建模） | 🟡 | 体验 | engine |

### 0.3 与 007 的关系

本 PRD 的 **P1-1（point_in_time 强制）** 是 [007 §1 缺陷 A](../007-轮动范式治理/轮动范式未来数据治理与选股模型重构.md) 的延续：007 把 point-in-time 过滤能力建设好了，本 PRD 把它从「可选 + 兜底降级」收紧为「强制 + 失败即报错」。

---

## §1 P0-1：静态过滤接通（回测路径）

### 1.1 现象

`rebalance_engine.select_at_rebalance_date()` 的过滤链路只有两步：
1. `_filter_valid_symbols` —— 剔除因子值全 NaN 的标的；
2. `_filter_by_conditions` —— 只跑 `filter.conditions` 条件树。

**完全跳过**了 `exclude_st` / `exclude_suspended` / `exclude_limit_up` / `exclude_limit_down` / `industries` / `exclude_industries` / `min_list_days` 这 7 项。grep 整个 `stock-engine/services/backtest/` 对这些字段**零引用**。

过滤逻辑本身存在，在 [`services/screener/filters.py`](../../../stock-engine/services/screener/filters.py)，但**仅被选股中心（screener）消费，未被回测路径消费**。

### 1.2 影响

用户在 Tab 2b 勾选「排除 ST / 排除涨停 / 上市天数 ≥ 250」后：
- 配置写进了 JSON；
- validator 通过（结构合法）；
- 回测执行时**完全忽略**；
- watcher 侧虽做了「全区间一次性剔除」，但**无法处理「中途戴帽 ST / 中途停牌」**的情形。

**等于误导用户**：勾选了以为生效，实际没生效。

### 1.3 改造方案

#### 1.3.1 engine 侧：在 rebalance_engine 过滤链路插入静态过滤步骤

修改 [`rebalance_engine.select_at_rebalance_date`](../../../stock-engine/services/backtest/rebalance_engine.py#L61-L134)，在 `_filter_valid_symbols` 之后、`_filter_by_conditions` 之前，插入静态过滤：

```
原流程：universe_filter → build_candidates → compute_factors → filter_valid_symbols → filter_by_conditions → ranking
新流程：universe_filter → build_candidates → compute_factors → filter_valid_symbols → [新增] filter_by_static_rules → filter_by_conditions → ranking
```

新增方法 `_filter_by_static_rules(cfg, factor_values_by_symbol, candidates, trading_date)`：
- 复用 `services/screener/filters.py` 的 `apply_static_filters` 逻辑；
- 输入：`ScreenConfigModel.filter` 的 7 个静态字段 + candidates 的 extra 元数据；
- 输出：剔除 ST / 停牌 / 涨跌停 / 行业不符 / 上市天数不足后的 `factor_values_by_symbol` 子集；
- 元数据来源：candidates[symbol]["extra"] 中需包含 `is_st` / `is_suspended` / `is_limit_up` / `is_limit_down` / `industry` / `list_date` 字段。

#### 1.3.2 watcher 侧：kline_data 下发静态过滤所需元数据

确认 watcher 经 HTTP 下发的 `kline_data` 每根 bar 的 extra 字段包含：
- `is_st: bool` —— 当日是否 ST/*ST；
- `is_suspended: bool` —— 当日是否停牌；
- `is_limit_up: bool` —— 当日是否涨停；
- `is_limit_down: bool` —— 当日是否跌停；
- `industry: str` —— 行业代码（申万二级或其他口径）；
- `list_date: str` —— 上市日期（YYYY-MM-DD）。

若 watcher 当前未下发，需在 [`data_adapter.kline_to_extra_map`](../../../stock-engine/services/backtest/data_adapter.py) 扩展提取这些字段。

#### 1.3.3 校验侧：无需改动

静态字段已在 [`FilterModel`](../../../stock-engine/services/strategy/models.py#L224-L242) 定义，validator 已默认值校验。

### 1.4 边界

- 若 extra 元数据缺失某字段（如 watcher 未下发 `industry`）：对应过滤项**静默跳过**（不报错，不剔除），打 warning 日志；
- `exclude_limit_up=True` 时：涨停股票被剔除（无法买入），符合实盘逻辑；
- `min_list_days` 按 trading_date 计算：`list_days = (trading_date - list_date).days`，<min_list_days 剔除。

---

## §2 P0-2：score 模式在 single 下的逻辑断层

### 2.1 现象

[`rebalance_to_topn`](../../../akquant-0.2.47/python/akquant/strategy.py#L1052-L1067) 的 score 模式公式：

```python
clipped_scores = {symbol: max(score, 0.0) for symbol in selected}
score_sum = sum(clipped_scores.values())
target_weights = {symbol: clipped_scores[symbol] / score_sum for symbol in selected}
```

而 [`rebalance_engine.select_at_rebalance_date`](../../../stock-engine/services/backtest/rebalance_engine.py#L130-L134) 返回的 score 来源：

| factor.method | score 取值 | 量纲 |
|---|---|---|
| `single` | 因子**原始值**（如 PE_TTM=15、TOTAL_MV=50e8） | 量纲不一 |
| `composite` | z-score **综合分**（如 +0.47） | 无量纲 |
| `disabled` / None | 0.0（兜底） | 无意义 |

**三种量纲混用同一归一化公式**，导致：

**灾难场景（single + score）**：选 30 只，single 因子是 `TOTAL_MV`（市值），市值范围 50 亿 ~ 5000 亿。`max(市值,0)/sum(市值)` 会让 5000 亿的那只独占 30%+ 资金，小市值几乎不配仓——完全违背轮动「分散持仓」初衷。

### 2.2 改造方案

#### 2.2.1 compiler 校验：禁止 single + score 组合

在 [`compile_strategy`](../../../stock-engine/services/backtest/compiler.py#L534-L560) 的 rebalance 预捕获段，增加组合校验：

```python
if has_rebalance:
    factor_method = screen_config.factor.method if screen_config and screen_config.factor else "disabled"
    if factor_method == "single" and rebalance.weight_mode == "score":
        raise CompilerError(
            "FACTOR_SCORE_INCOMPATIBLE: factor.method=single 时 score 量纲为因子原始值，"
            "与 rebalance.weight_mode=score 的归一化加权语义不兼容（会导致单只标的独占资金）。"
            "请改用 weight_mode=equal，或 factor.method=composite（综合分已标准化）。"
        )
```

#### 2.2.2 validator 同步校验（更友好的前置拦截）

在 [`validator.py _validate_structure_trading`](../../../stock-engine/services/strategy/validator.py#L145-L207) 增加跨字段联动校验，错误码 `FACTOR_SCORE_INCOMPATIBLE`。

#### 2.2.3 前端联动禁用

`f-ranking-method` ��� `single` 时，`f-reb-weight-score` radio 置灰 + tooltip 提示；切换 `f-reb-weight` 到 score 时，若 method=single 则弹 alert。

### 2.3 错误码

新增 `FACTOR_SCORE_INCOMPATIBLE` 到 [`errors.py ErrorCode`](../../../stock-engine/services/strategy/errors.py)。

---

## §3 P1-1：point_in_time 强制开启

### 3.1 决策

依据用户决策：**point_in_time 不再可选，所有 universe 类型强制开启**，关闭=数据错误（lookahead bias），不应提供兜底降级。watcher 接口必须能查询到成分股，查询不到让回测失败。

### 3.2 改造方案

#### 3.2.1 schema 移除 point_in_time 字段

- [`UniverseModel.point_in_time`](../../../stock-engine/services/strategy/models.py#L192-L194)：**删除该字段**；
- [`rebalance_engine._apply_universe_filter`](../../../stock-engine/services/backtest/rebalance_engine.py#L209-L254)：不再判断 `point_in_time`，**无条件执行**成分股过滤。

#### 3.2.2 所有 universe 强制查询 watcher

当前仅 csi300/csi500 走 `_INDEX_CODE_MAP` 查询，扩展为：

| universe.pool | watcher 查询逻辑 |
|---|---|
| `csi300` | `POST /api/internal/constituents/query` 查 `000300.SH` 当日成分股 |
| `csi500` | 同上，查 `000905.SH` |
| `all_a_shares` | 查询 watcher 确认「该日全 A 可交易标的列表」（剔除当日退市/新上市未满 N 天） |
| `manual` | **不再跳过**，查询 watcher 确认 `universe.stocks` 列表中每只标的在该日**真实存在且可交易**（剔除当日停牌/退市） |
| 自定义池 ID | 查询 watcher 解析池定义并按 point-in-time 过滤 |

#### 3.2.3 watcher_client 缺失或查询失败 → 回测失败

修改 [`_apply_universe_filter`](../../../stock-engine/services/backtest/rebalance_engine.py#L209-L254) 的降级逻辑：

```
原逻辑：watcher_client=None → 打 warning + 返回原 kline_map（降级）
新逻辑：watcher_client=None → 抛 BacktestError("PIT_WATCHER_UNAVAILABLE: ...")

原逻辑：查询返回空 → 打 warning + 返回原 kline_map（降级）
新逻辑：查询返回空 → 抛 BacktestError("PIT_CONSTITUENTS_EMPTY: universe=xxx date=xxx watcher 查询返回空")
```

新增错误码：
- `PIT_WATCHER_UNAVAILABLE` —— watcher 客户端未配置（`WATCHER_BASE_URL` 缺失）；
- `PIT_CONSTITUENTS_EMPTY` —— watcher 查询返回空（数据缺失或接口异常）；
- `PIT_QUERY_FAILED` —— watcher 查询抛异常（网络/超时）。

#### 3.2.4 日志规范

每个调仓日的 point-in-time 过滤结果**必须记录 INFO 日志**：

```
[INFO] PIT 过滤: universe=csi300 date=2024-06-03 候选 280/300（剔除 20 只非当日成分）
[INFO] PIT 过滤: universe=manual date=2024-06-03 候选 8/10（剔除 2 只当日停牌）
```

watcher 查询失败时记录 ERROR 日志（含 universe / date / 异常栈）。

#### 3.2.5 前端移除开关

[`editor.html`](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html#L179-L184) 的 `f-universe-point-in-time-wrap` 整块移除；[`strategy-editor.js`](../../../stock-watcher/src/main/resources/static/js/strategy-editor.js) 中相关的 collect/refill 逻辑同步移除。

watcher 侧 [`StrategySchemaConstants`](../../../stock-watcher/src/main/java/com/arthur/stock/constant/StrategySchemaConstants.java) 同步移除 point_in_time 白名单。

### 3.3 兼容性

- 旧策略 JSON 含 `point_in_time` 字段：`UniverseModel` 删除该字段后，`extra="forbid"` ���报「未知字段」。**需在 models.py 的 UniverseModel 加 `model_config = ConfigDict(extra="ignore")` 或保留字段但忽略取值**（推荐后者，向后兼容 + 打 deprecation warning）。

---

## §4 P1-2：权重配置面板重新设计（Factor 层）

### 4.1 决策

依据用户决策：**激进方案，重新设计权重面板**。包含 factorKey 联想输入 + 公式说明 + 负权重语义可视化 + 组合校验 + 实时预览。

### 4.2 改造方案

#### 4.2.1 factorKey 改联想输入（复用现有因子列表）

现状：[`strategy-editor.js weightRowHtml L1206-L1210`](../../../stock-watcher/src/main/resources/static/js/strategy-editor.js#L1206-L1210) 是裸 `<input type="text">`。

改造：
- 复用 Tab 2b 选股条件树的「插入因子」下拉（[`#f-screen-factors`](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html#L207-L210)）的同一份因子数据源；
- 权重输入框改用 `<input list="factor-keys-datalist">` + `<datalist id="factor-keys-datalist">`，浏览器原生联想，无需额外组件；
- 因子数据源从 `/api/constants` 拉取（[`ConstantController`](../../../stock-watcher/src/main/java/com/arthur/stock/controller/ConstantController.java) 已提供），与 engine [`constants.py ALL_FACTOR_KEYS`](../../../stock-engine/services/strategy/constants.py#L67-L83) 对齐（20 技术 + 10 基本面 = 30 个）。

#### 4.2.2 公式说明 tooltip

权重面板顶部增加可折叠的「公式说明」区块，用老股民能听懂的话写：

```
【打分公式（composite 模式）】
1. 每个调仓日，对候选池里所有股票，分别计算每个因子的值；
2. 每个因子独立做 z-score 标准化：z = (该股因子值 - 候选池均值) / 候选池标准差；
   （z > 0 表示该股在该因子上高于候选池平均，z < 0 表示低于平均）
3. 综合分 = Σ(权重_k × z_k) / Σ|权重_k|，按维度权重绝对值归一化；
4. 综合分降序排序，取 top_n。

【权重正负的含义】
- 正权重（如 ROE_TTM: 1.0）：该因子越大越好；
- 负权重（如 PE_TTM: -0.5）：该因子越小越好（z 取反后乘权重）；
- 权重绝对值代表该因子在综合分中的相对重要程度。
```

#### 4.2.3 负权重语义可视化

每行权重输入增加「方向切换」按钮：

```
[factorKey 联想输入] [↑ 越大越好 / ↓ 越小越好 切换] [权重绝对值 number] [×]
```

- `↑ 越大越好` → 权重为正（如 +1.0）；
- `↓ 越小越好` → 权重为负（如 -1.0）；
- 用户调绝对值，正负号由方向按钮控制，**避免用户直接输负号出错**。

提交时按 `方向 × 绝对值` 计算最终权重写入 `factor.weights`。

#### 4.2.4 实时预览（可选，二期）

权重面板底部增加「预览」按钮，点击后调用 `/api/strategy/preview_ranking`（新增接口），传入当前 screen_config + 一个示例调仓日，返回「当前配置下 top 5 会怎么排名 + score 多少」，让用户配置前心里有数。

> 预览接口依赖 watcher 提供示例数据，一期可不实现，二期再做。

#### 4.2.5 组合校验提示

- `factor.method=composite` 但 `weights` 为空 → 前端实时红字提示「composite 模式必须至少配置一个因子权重」；
- 权重绝对值之和为 0 → 提示「权重不能全为 0」；
- factorKey 重复 → 提示「同一因子不能配置多次」；
- factorKey 不在白名单 → 联想输入时就不让选，提交时再校验一道。

### 4.3 验收标准

- 用户无需查阅文档即可理解权重正负的含义；
- factorKey 无法手敲错误（只能从联想列表选）；
- composite + 空 weights 在前端就被拦截，不需要等后端报错。

---

## §5 P1-3：现金保留比例（cash_reserve_pct）

### 5.1 决策

依据用户决策：**仍加配置项**，用户可控的现金保留比例。

### 5.2 改造方案

#### 5.2.1 schema 扩展

[`PortfolioModel`](../../../stock-engine/services/strategy/models.py#L245-L252) 增加字段：

```python
class PortfolioModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    top_n: Optional[int] = Field(None, description="选出的标的数量")
    cash_reserve_pct: Optional[float] = Field(
        0.0,
        ge=0.0, le=0.95,
        description="现金保留比例（0.0=全仓，0.2=保留20%现金）。"
        "目标权重 = (1 - cash_reserve_pct) / N，按 top_n 等分",
    )
```

#### 5.2.2 compiler 透传

[`compile_strategy`](../../../stock-engine/services/backtest/compiler.py#L534-L560) 增加捕获：

```python
rb_cash_reserve = (
    portfolio_layer.cash_reserve_pct
    if portfolio_layer and portfolio_layer.cash_reserve_pct is not None
    else 0.0
)
```

#### 5.2.3 rebalance_to_topn 调用改造

当前 `rebalance_to_topn` 的 equal 模式固定 `1.0/N`。改造方式二选一：

**方案 A（推荐，改动小）**：在 `on_daily_rebalance` 调 `rebalance_to_topn` 前，对 scores 做缩放。equal 模式下 engine 无法控制权重，改为**调用后用 order_target_weights 二次调整**——复杂，不推荐。

**方案 B（推荐，改 akquant 语义）**：扩展 `rebalance_to_topn` 增加 `total_weight` 参数（akquant 侧改造）。但 akquant 是外部依赖，**不推荐改源码**。

**方案 C（实际采用，engine 侧包装）**：engine 不调 `rebalance_to_topn`，改为自己算 target_weights 后直接调 `order_target_weights`：

```python
def on_daily_rebalance(self, trading_date, timestamp):
    if not _is_rebalance_trigger_day(...):
        return
    scores = rebalance_engine.select_at_rebalance_date(...)
    if not scores:
        if liquidate_unmentioned:
            self.order_target_weights({}, liquidate_unmentioned=True)
        return

    # 自己算权重（替代 rebalance_to_topn）
    investable = 1.0 - rb_cash_reserve  # 可投资比例
    if rb_weight_mode == "equal":
        n = min(len(scores), rb_top_n)
        target_weights = {s: investable / n for s in list(scores.keys())[:n]}
    else:  # score 模式（仅 composite）
        ...  # 复用归一化逻辑，乘以 investable

    self.order_target_weights(
        target_weights=target_weights,
        liquidate_unmentioned=rb_liquidate_unmentioned,
    )
```

> 这意味着 **engine 逐步摆脱 rebalance_to_topn，自托管权重计算**，便于后续扩展（行业暴露、单标的上限等，见 P1-4）。

#### 5.2.4 前端控件

Tab 2b 的 Portfolio 层增加「现金保留比例」输入框：

```html
<div class="col-md-3">
    <label class="form-label">现金保留比例 cash_reserve_pct</label>
    <input type="number" class="form-control" id="f-cash-reserve" min="0" max="0.95" step="0.05" value="0">
    <div class="form-text">0=全仓，0.2=保留20%现金（应对赎回/打新）</div>
</div>
```

---

## §6 P1-4：单标的最大权重 / 行业暴露上限

### 6.1 现状

`rebalance_to_topn` 无行业约束，`risk_config` 前端无控件。20 只股票里 8 只是银行，等于赌一个行业。

### 6.2 改造方案

#### 6.2.1 schema 扩展

`PortfolioModel` 增加约束字段：

```python
class PortfolioModel(BaseModel):
    ...
    max_weight_per_symbol: Optional[float] = Field(
        None, ge=0.0, le=1.0,
        description="单标的最大权重上限（如 0.1=单只不超过10%），超出截断后重新等分",
    )
    max_industry_exposure: Optional[float] = Field(
        None, ge=0.0, le=1.0,
        description="单行业最大暴露上限（如 0.3=单一行业总权重不超过30%）",
    )
```

### 6.2.2 engine 侧权重后处理（含完整算法）

在 P1-3 方案 C 的自托管权重计算之后，增加行业暴露截断后处理。**行业暴露计算逻辑如下**：

#### 6.2.2.1 行业来源

每只标的的行业来自 watcher 经 kline_data 下发的 `sw_industry_l1` 字段（申万一级，见 §14 对接方案）。engine 在 [`data_adapter.kline_to_extra_map`](../../../stock-engine/services/backtest/data_adapter.py) 提取后注入 candidates[symbol]["extra"]。

> 一期降级方案：若 §14 未完成，用 `stock_basic.industry`（Tushare 简化口径）兜底，字段名仍为 `industry`。

#### 6.2.2.2 计算步骤

**第 1 步：按行业聚合权重**
```python
industry_weights = {}
for symbol, weight in target_weights.items():
    ind = industry_map.get(symbol, "未知行业")
    industry_weights[ind] = industry_weights.get(ind, 0) + weight
```

**第 2 步：检测超限行业**
```python
over_limit = {ind: w for ind, w in industry_weights.items() if w > max_industry_exposure}
```

**第 3 步：按比例缩减超限行业**（策略 A，平滑，推荐）
```python
for ind, total in over_limit.items():
    scale = max_industry_exposure / total   # 0.3 / 0.35 ≈ 0.857
    for sym in symbols_in_industry[ind]:
        target_weights[sym] *= scale
```
超限行业的每只股票按比例缩减，释放出的权重不重新分配，自然转为现金缺口（与 cash_reserve 叠加）。

**第 4 步：迭代防连锁超限**

缩减 A 行业后若重新分配给 B 行业，可能引发 B 行业超限。采用迭代算法（释放权重不重新分配则无需迭代，但为稳妥仍加迭代上限）：

```python
def clip_industry_exposure(
    target_weights: dict[str, float],
    industry_map: dict[str, str],
    max_exposure: float,
    max_iterations: int = 10,
) -> dict[str, float]:
    """行业暴露截断：超限行业按比例缩减，释放权重转现金（不重新分配）。"""
    weights = dict(target_weights)
    for _ in range(max_iterations):
        industry_weights = {}
        for sym, w in weights.items():
            if w <= 0:
                continue
            ind = industry_map.get(sym, "未知")
            industry_weights[ind] = industry_weights.get(ind, 0) + w
        over = {ind: w for ind, w in industry_weights.items() if w > max_exposure}
        if not over:
            break
        for ind, total in over.items():
            scale = max_exposure / total
            for sym in weights:
                if industry_map.get(sym) == ind and weights[sym] > 0:
                    weights[sym] *= scale
    return weights
```

#### 6.2.2.3 数字示例

30 只等权（每只 0.033），`max_industry_exposure=0.3`：
- 「银行」8 只，合计 0.267 < 0.3 ✅ 不动
- 「电子」12 只，合计 0.40 > 0.3 ❌ 超限
- scale = 0.3 / 0.40 = 0.75
- 「电子」每只从 0.033 → 0.025，总权重降到 0.3
- 释放 0.10 权重 → 变成现金（总仓位从 1.0 → 0.90）

#### 6.2.3 前端控件

Tab 2b Portfolio 层增加两个输入框 + 启用开关。

### 6.3 依赖

行业暴露上限依赖 **§14 申万行业分类对接**(watcher 落库 `sw_industry` + `sw_industry_member` 表,并经 kline_data 下发 `sw_industry_l1` 字段)。一期若 §14 未完成,可降级用 `stock_basic.industry`(Tushare 简化口径)兜底,但口径不标准(见 §14.1.2)。industry 元数据缺失时该项静默跳过 + warning。

---

## §7 P2-1：调仓日判定改造（trade_cal 预计算标记方案）

### 7.1 现状与根因

当前 [`_is_rebalance_trigger_day`](../../../stock-engine/services/backtest/compiler.py#L978-L1012) 是**无状态函数**，只看单根 bar 的自然日历属性（`weekday()` / `day`）判断是否触发，导致三个问题：

| frequency | 当前实现 | 问题 |
|---|---|---|
| `weekly` | `weekday() == day_of_period` | off-by-one；设置的周几若非交易日该周不触发 |
| `monthly` | `day == day_of_period`（自然日） | 每月 N 号若为周末/假日，**整月不触发** |
| `quarterly` | `month in {1,4,7,10} and day <= 7` | 完全忽略 day_of_period |

**根因**：用「自然日历日」做判断，而自然日与交易日不是一一对应。

### 7.2 真实投研场景：老股民/机构常用的调仓时点

经实际投研场景调研，轮动策略的调仓时点需求分布如下：

| 梯队 | 调仓时点 | 典型场景 | 占比 |
|---|---|---|---|
| **第一梯队** | 每月首个交易日 | 小市值/低 PE 价值轮动（现有模板） | 高频 |
| | 每月末个交易日（月末） | 机构业绩考核驱动、宏观因子驱动 | 高频 |
| **第二梯队** | 每周首个交易日（周一） | 短期动量轮动、ETF 轮动 | 常见 |
| | 每季首个交易日 | 行业轮动（申万一级） | 常见 |
| | 每季末个交易日（季末） | 公募基金季报披露前调仓 | 常见 |
| **第三梯队** | 每日 | 高频统计套利（轮动少用） | 少见 |
| | 每月第 N 个交易日（精确序号） | 几乎无人使用（老股民无此概念） | 极少 |

**关键结论**：真实需求集中在「月初/月末/季初/季末/周一/每日」这 6 种语义化时点，**「第 N 个交易日」这种精确序号几乎无人使用**。老股民脑子里只有「月初」「月末」，没有「第 15 个交易日」。

### 7.3 方案选型：为何放弃「交易日计数器」，改用「trade_cal 预计算标记」

曾考虑两种方案：

| 维度 | 方案 A：交易日计数器（已否决） | 方案 B：trade_cal 预计算标记（采用） |
|---|---|---|
| 支持月末 | ❌ **无法干净支持**（判断「最后一个」需知道本月共几天，engine 回测中不知道未来 → lookahead） | ✅ 天然支持（watcher 用完整日历预计算） |
| engine 状态 | 有状态（维护计数器，周期切换归零） | **零状态**（查标记即可） |
| 语义 | 「第 15 个交易日」（老股民陌生） | 「月末」「月初」（老股民熟悉） |
| 覆盖真实需求 | 部分（漏月末/季末） | **完整覆盖第一/二梯队全部 6 种时点** |
| 依赖 | 无 | `trade_cal` 表（**已存在**，见 §7.4.1） |

**决策依据**：
1. 「月末」是高频需求（第一梯队），计数器方案无法干净支持；
2. 项目已有 `trade_cal` 表（`exchange / cal_date / is_open / pretrade_date`，[schema-mysql.sql L68-75](../../../stock-watcher/src/main/resources/schema-mysql.sql#L68-L75)），watcher 可预计算调仓标记；
3. 「月末」是日历事实（非预测），watcher 预计算下发给 engine 不构成 lookahead bias；
4. engine 零状态查询，比维护计数器简单且无并发/状态污染风险。

### 7.4 改造方案

#### 7.4.1 trade_cal 表扩展（watcher 侧）

现有 `trade_cal` 表已有 `exchange / cal_date / is_open / pretrade_date`。**新增 6 个预计算标记字段**（[schema-mysql.sql L68-75](../../../stock-watcher/src/main/resources/schema-mysql.sql#L68-L75) + [schema-sqlite.sql L68-75](../../../stock-watcher/src/main/resources/schema-sqlite.sql#L68-L75) + [DataInitServiceImpl](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) 内置 DDL Map，**三处同步**）：

```sql
ALTER TABLE trade_cal ADD COLUMN (
    is_first_of_week     VARCHAR(4) DEFAULT '0' COMMENT '本周首个交易日：1=是 0=否',
    is_last_of_week      VARCHAR(4) DEFAULT '0' COMMENT '本周末个交易日：1=是 0=否',
    is_first_of_month    VARCHAR(4) DEFAULT '0' COMMENT '本月首个交易日：1=是 0=否',
    is_last_of_month     VARCHAR(4) DEFAULT '0' COMMENT '本月末个交易日：1=是 0=否',
    is_first_of_quarter  VARCHAR(4) DEFAULT '0' COMMENT '本季首个交易日：1=是 0=否',
    is_last_of_quarter   VARCHAR(4) DEFAULT '0' COMMENT '本季末个交易日：1=是 0=否'
);
```

预计算逻辑（在 [`TradeCalServiceImpl`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java) 同步 trade_cal 时一次性算好，仅对 `is_open=1` 的日子标记）：

```java
// is_first_of_month = 当天 is_open=1 且 同月没有更早的 is_open=1
// is_last_of_month  = 当天 is_open=1 且 同月没有更晚的 is_open=1
// is_first_of_week / is_last_of_week / is_first_of_quarter / is_last_of_quarter 同理
```

> 预计算时机：① trade_cal 全量初始化时算一次；② 每日增量同步 trade_cal 时重算当月/当季的标记（因为增量可能改变「最后一天」归属）。

#### 7.4.2 RebalanceModel schema 改造（engine 侧）

[`RebalanceModel`](../../../stock-engine/services/strategy/models.py#L364-L388) 的 `day_of_period: int` **替换为** `trigger: Literal["first", "last"]`：

```python
class RebalanceModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    frequency: Literal["daily", "weekly", "monthly", "quarterly"] = Field(...)
    # 替代旧 day_of_period（int）
    trigger: Optional[Literal["first", "last"]] = Field(
        "first", description="触发时点：first=周期首个交易日 last=周期末个交易日。"
        "daily 时忽略"
    )
    replace_method: Optional[Literal["full", "incremental"]] = Field(None, ...)
    weight_mode: Optional[Literal["equal", "score"]] = Field(None, ...)
    long_only: Optional[bool] = Field(True, ...)
```

#### 7.4.3 调仓日判定逻辑（engine 侧，零状态）

删除旧的无状态函数 [`_is_rebalance_trigger_day`](../../../stock-engine/services/backtest/compiler.py#L978-L1012)，改为**查 bar 携带的 trade_cal 标记**（零状态、无计数器）：

```python
def on_daily_rebalance(self, trading_date, timestamp):
    if not self._is_rebalance_day(trading_date):
        return
    # ... 后续选股 + 调仓逻辑不变 ...

def _is_rebalance_day(self, trading_date) -> bool:
    """查 trade_cal 预计算标记，零状态判断。"""
    freq = self._rb_frequency
    trig = self._rb_trigger  # "first" or "last"

    if freq == "daily":
        return True

    flag_map = {
        ("weekly", "first"): "is_first_of_week",
        ("weekly", "last"): "is_last_of_week",
        ("monthly", "first"): "is_first_of_month",
        ("monthly", "last"): "is_last_of_month",
        ("quarterly", "first"): "is_first_of_quarter",
        ("quarterly", "last"): "is_last_of_quarter",
    }
    flag = flag_map.get((freq, trig or "first"))
    if flag is None:
        return False
    return self._get_bar_flag(trading_date, flag) == "1"

def _get_bar_flag(self, trading_date, flag_name) -> str:
    """从当前 bar 的 extra 取 trade_cal 标记（watcher 经 kline_data 下发）。"""
    # 标记由 watcher 预计算并塞入 bar，见 §7.4.4
    val = getattr(self.current_bar, "extra", {}).get(flag_name, "0")
    return str(val)
```

#### 7.4.4 watcher 下发标记到 kline_data

修改 [`BacktestServiceImpl.buildKlineData`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java#L544-L593)，构造每根 bar 时注入 trade_cal 标记（按 bar 的 trade_date 查 trade_cal）：

```java
private JSONObject buildKlineData(JSONObject configJson) {
    // ... 解析 symbols ...
    // 新增：批量查回测区间的 trade_cal 标记
    Map<String, TradeCalDO> calMap = tradeCalService.queryFlagsByRange(startDate, endDate);

    for (String code : symbols) {
        for (DailyQuoteDO q : quotes) {
            JSONObject bar = new JSONObject();
            // ... 原有 OHLCV + daily_basic ...
            // 新增：注入调仓标记
            TradeCalDO cal = calMap.get(q.getTradeDate());
            if (cal != null) {
                bar.put("is_first_of_week", cal.getIsFirstOfWeek());
                bar.put("is_last_of_week", cal.getIsLastOfWeek());
                bar.put("is_first_of_month", cal.getIsFirstOfMonth());
                bar.put("is_last_of_month", cal.getIsLastOfMonth());
                bar.put("is_first_of_quarter", cal.getIsFirstOfQuarter());
                bar.put("is_last_of_quarter", cal.getIsLastOfQuarter());
            }
            arr.add(bar);
        }
    }
}
```

#### 7.4.5 前端控件改造

[`editor.html`](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html#L400-L402) 的 `f-reb-day`（number 输入）**替换为** `f-reb-trigger`（select 下拉）：

```html
<div class="col-md-3">
    <label class="form-label">触发时点 trigger</label>
    <select class="form-select" id="f-reb-trigger">
        <option value="first">首个交易日（月初/周一/季初）</option>
        <option value="last">末个交易日（月末/周五/季末）</option>
    </select>
</div>
```

前端 JS 监听 `f-reb-frequency` change 事件：
- `daily` 选中时，隐藏 trigger 下拉（每日触发无需选时点）；
- 其余 frequency，显示 trigger 下拉，label 动态更新（如 monthly 时显示「月初/月末」，weekly 时显示「周一/周五」）。

#### 7.4.6 validator 校验

[`validator.py`](../../../stock-engine/services/strategy/validator.py) 增加联动校验：
- `frequency=daily` 时 `trigger` 可忽略（不报错）；
- `trigger` 非 `first`/`last`/None 时报 `INVALID_REBALANCE_TRIGGER`。

新增错误码 `INVALID_REBALANCE_TRIGGER`（替代原 `REBALANCE_DAY_OUT_OF_RANGE`）。

#### 7.4.7 兼容性

旧策略 JSON 含 `day_of_period` 字段：
- `RebalanceModel` 删除 `day_of_period` 后 `extra="forbid"` 会报未知字段；
- **兼容处理**：`RebalanceModel` 临时保留 `day_of_period` 字段但标记 deprecated，validator 检测到 `day_of_period` 在场而 `trigger` 缺失时，自动映射 `day_of_period <= 1 → trigger="first"`，并打 deprecation warning；
- 一段时间后（如下个版本）彻底删除 `day_of_period`。

### 7.5 调仓时点全表（改造后支持的组合）

| frequency | trigger=first | trigger=last |
|---|---|---|
| `daily` | 每个交易日（忽略 trigger） | 每个交易日（忽略 trigger） |
| `weekly` | 每周一（本周首个交易日） | 每周五（本周末个交易日） |
| `monthly` | 每月首个交易日（月初） | 每月末个交易日（**月末调仓**） |
| `quarterly` | 每季首个交易日（季初） | 每季末个交易日（**季末调仓**） |

**覆盖第一/二梯队全部 6 种高频时点**，满足 90%+ 真实轮动策略需求。

### 7.6 为什么仍用 on_daily_rebalance（对齐 akquant）

akquant 的 `on_daily_rebalance` 提供「每个交易日触发一次」的原子能力（[hooks L251-L265](../../../akquant-0.2.47/python/akquant/strategy_framework_hooks.py#L251-L265) 幂等保护），**频率过滤完全留给策略代码**。本方案在 `on_daily_rebalance` 内查 trade_cal 标记做过滤——**完全对齐 akquant 设计**，不依赖 `schedule` / `add_daily_timer`（那两个无法表达「月末」语义）。

### 7.7 验收 checklist

- [ ] `trade_cal` 表新增 6 个标记字段，watcher 同步时预计算正确；
- [ ] `monthly + trigger=last` → 每月末个交易日触发（如 1 月 31 日、2 月 28/29 日）；
- [ ] `monthly + trigger=first` → 每月首个交易日触发（避开周末）；
- [ ] `quarterly + trigger=last` → 每季末个交易日触发（季末调仓）；
- [ ] `weekly + trigger=first` → 每周一触发；
- [ ] `daily` → 每个交易日触发（忽略 trigger）；
- [ ] kline_data 的 bar 含 6 个 `is_*` 标记字段；
- [ ] engine `_is_rebalance_day` 零状态查询（无计数器）；
- [ ] 前端 trigger 下拉随 frequency 动态显隐 + 更新 label；
- [ ] validator 对非法 trigger 报 `INVALID_REBALANCE_TRIGGER`；
- [ ] 旧策略含 `day_of_period` 字段时自动映射 + deprecation warning。

---

## §8 P2-2 / P2-3：fill_policy / risk_config 前端控件

### 8.1 fill_policy

Tab 8 增加「成交语义」下拉：

```html
<select id="f-bt-fill-policy">
    <option value="">默认（次日开盘成交）</option>
    <option value='{"price_basis":"open","temporal":"next_event","bar_offset":1}'>次日开盘</option>
    <option value='{"price_basis":"close","temporal":"same_cycle","bar_offset":0}'>当日收盘</option>
</select>
```

### 8.2 risk_config

Tab 8 增加「风控」折叠面板，暴露 `max_position_pct`（单标的最大仓位）、`max_drawdown_pct`（最大回撤熔断）等常用项。

---

## §9 P2-4：最小持仓周期（滞回机制）

### 9.1 现状

轮动易过度换手，每月全换 20 只意味着每月交易成本吃掉收益。

### 9.2 改造方案

`RebalanceModel` 增加：

```python
min_holding_bars: Optional[int] = Field(
    None, ge=0,
    description="最小持仓周期（bar 数）。持仓不足此周期的标的，即便未入选 top_n 也不卖出",
)
```

engine 侧在计算 target_weights 后，对「持仓中且 hold_bar() < min_holding_bars」的标的保留原权重（不剔除）。

> 依赖 engine 自托管权重计算（P1-3 方案 C），建议与 P1-3 一起做。

---

## §10 实施优先级与依赖关系

| 优先级 | 编号 | 改造项 | 依赖 | 工作量估算 |
|---|---|---|---|---|
| **P0** | P0-0 | 申万行业分类数据对接 | Tushare 2000 积分（已满足） | watcher 2-3d |
| **P0** | P0-1 | 静态过滤接通 | P0-0（industry 元数据）+ watcher 下发 is_st 等 | engine 1d + watcher 1d |
| **P0** | P0-2 | score + single 禁用 | 无 | engine 0.5d + 前端 0.5d |
| **P0** | P0-3 | 调仓默认当日收盘成交 | 无（runner + 前端） | engine 0.5d + 前端 0.5d |
| **P0** | P0-4 | 涨停拒买 + 跌停拒卖 | P0-1（is_limit_up 元数据）+ P1-3（自托管权重） | engine 1d |
| **P0** | P0-5 | 资金不足按 score 优先 + 诊断回传 | P1-3（自托管权重） | engine 1d + 前端 0.5d |
| **P1** | P1-1 | point_in_time 强制 | watcher 接口完备（007 已做） | 全链路 1d |
| **P1** | P1-2 | 权重面板重设计（含 P2-7 归一化提示） | 无 | 前端 2d |
| **P1** | P1-3 | cash_reserve_pct | engine 自托管权重（方案 C） | engine 1.5d + 前端 0.5d |
| **P1** | P1-4 | 行业暴露 / 单标的上限 | P0-0（申万行业）+ P1-3（自托管权重） | engine 1d + 前端 0.5d |
| **P1** | P1-5 | Tab6 止损止盈对轮动可见 | 无（前端改 paradigm + validator 放开） | 前端 0.5d + engine 0.5d |
| **P1** | P1-6 | FactorNode transform（滚动窗口聚合） | 无（schema + 因子管线） | engine 2-3d |
| **P1** | P1-7 | 换仓缓冲带 buffer_n | P1-3（自托管权重） | engine 1d + 前端 0.5d |
| **P2** | P2-1 | trade_cal 预计算标记 + trigger 语义 | trade_cal 表（已存在） | watcher 0.5d + engine 0.5d + 前端 0.5d |
| **P2** | P2-2 | fill_policy 控件 | 无（与 P0-3 合并） | 前端 0.5d |
| **P2** | P2-3 | risk_config 控件 | 无 | 前端 0.5d |
| **P2** | P2-4 | 最小持仓周期 | P1-3 | engine 0.5d |
| **P2** | P2-5 | warmup 实际值回传 | 无 | engine 0.5d + 前端 0.5d |
| **P2** | P2-6 | 条件树模式切换双向同步 | 无 | 前端 0.5d |
| **P2** | P2-7 | composite 权重归一化提示 | 并入 P1-2 | — |
| **P2** | P2-8 | 年化换手率指标 | 无 | engine 0.5d + 前端 0.5d |
| **P2** | P2-9 | 分批调仓 + 冲击成本 | P1-3（三期） | engine 2d |

### 10.1 建议迭代节奏

- **迭代 0（数据底座）**：P0-0 申万行业分类对接，watcher 侧独立完成，为 P0-1 / P0-4 / P1-4 / 行业轮动铺路；
- **迭代 1（回测可信度硬伤）**：P0-3 + P0-4 + P0-5，解决「回测数字骗人」三大致命问题（成交价/涨停/资金不足），这是老股民最在意的；
- **迭代 2（功能完整性 P0 收口）**：P0-1 + P0-2，解决「功能空壳 + 灾难配仓」；
- **迭代 3（数据治理）**：P1-1，收紧 point_in_time；
- **迭代 4（权重体系重构）**：P1-3 + P1-4 + P1-7 + P2-4 + P0-4/P0-5 收尾，一次性把「engine 自托管权重计算」做掉（P0-4 涨停拒买、P0-5 score 优先、P1-4 行业暴露、P1-7 buffer、P2-4 最小持仓都依赖自托管权重）；
- **迭代 5（交互补全）**：P1-2 + P1-5 + P2-1 + P2-2 + P2-3 + P2-5 + P2-6 + P2-8；
- **迭代 6（进阶能力，二期/三期）**：P1-6（transform 滚动窗口）+ P2-9（分批调仓）。

> **关键路径**：P0-0（数据底座）→ P1-3（自托管权重）是后续 P0-4 / P0-5 / P1-4 / P1-7 / P2-4 的共同依赖，应优先完成。P0-3（成交价）无依赖可随时插入。

---

## §11 验收 checklist

### 11.1 P0-1 静态过滤

- [ ] 配置 `exclude_st=true`，回测日志可见「剔除 ST: N 只」；
- [ ] 配置 `min_list_days=250`，次新股确实被剔除；
- [ ] 元数据缺失时打 warning 但不阻断回测。

### 11.2 P0-2 score + single 禁用

- [ ] `factor.method=single` + `rebalance.weight_mode=score` → validator 报 `FACTOR_SCORE_INCOMPATIBLE`；
- [ ] 前端 single + score 组合 radio 置灰；
- [ ] composite + score 仍可用。

### 11.3 P1-1 point_in_time 强制

- [ ] `point_in_time` 字段从 schema / 前端 / watcher 常量全部移除；
- [ ] watcher_client 未配置 → 回测失败 `PIT_WATCHER_UNAVAILABLE`；
- [ ] watcher 查询返回空 → 回测失败 `PIT_CONSTITUENTS_EMPTY`；
- [ ] 每个调仓日 INFO 日志记录过滤结果；
- [ ] 旧策略 JSON 含 point_in_time 字段不报错（向后兼容）。

### 11.4 P1-2 权重面板

- [ ] factorKey 只能从联想列表选，无法手敲；
- [ ] 「越大越好 / 越小越好」方向切换按钮正常工作；
- [ ] 公式说明可折叠展示；
- [ ] composite + 空 weights 前端实时红字提示。

### 11.5 P1-3 cash_reserve_pct

- [ ] `cash_reserve_pct=0.2` → 30 只股票总权重 0.8，每只约 0.0267；
- [ ] `cash_reserve_pct=0` → 行为与现状一致（除取整损耗）；
- [ ] 前端输入框范围 0-0.95，超出报错。

### 11.6 P1-4 行业暴露 / 单标的上限

- [ ] `max_weight_per_symbol=0.1` → 单只不超过 10%，超出截断后重新等分；
- [ ] `max_industry_exposure=0.3` → 单行业总权重不超过 30%；
- [ ] industry 元数据缺失时该项静默跳过 + warning。

### 11.7 P2-1 调仓日判定（trade_cal 预计算标记）

- [ ] `trade_cal` 表 6 个标记字段预计算正确（含跨月/跨季边界）；
- [ ] `monthly + trigger=last` → 月末最后交易日触发；
- [ ] `quarterly + trigger=last` → 季末最后交易日触发；
- [ ] `daily` 时 trigger 下拉隐藏；
- [ ] engine 零状态查询（无计数器）；
- [ ] 旧策略 `day_of_period` 自动映射 trigger + deprecation warning。

### 11.8 第二波（§15 回测可信度与实战）

- [ ] **P0-3**：轮动范式默认当日收盘成交；trades_df entry_price = 选股日 close；
- [ ] **P0-4**：涨停标的买入剔除；日志「涨停拒买」；跌停标的卖出剔除；
- [ ] **P0-5**：target_weights 按 score 降序；结果含 rebalance_diagnosis；前端展示成交数/选出数；
- [ ] **P1-5**：轮动范式 Tab6 可见；rebalance+exit.bracket 可回测；止损触发；
- [ ] **P1-6**：FactorNode 支持 transform；PE_TTM+ma(20) 可算 20 日均值；
- [ ] **P1-7**：buffer_n 生效；新标的进 top_(n-buffer) 才买；换手率下降；
- [ ] **P2-5**：结果含 effective_config.warmup_period；前端展示实际 warmup；
- [ ] **P2-6**：条件树模式切换双向同步 + 确认框；
- [ ] **P2-7**：权重面板实时显示归一化结果；
- [ ] **P2-8**：metrics 含 annual_turnover_ratio；
- [ ] **P2-9**：split_days>1 分批调仓；impact_cost_bps 建模冲击成本。

---

## §12 风险与回滚

### 12.1 P1-3 engine 自托管权重的风险

从 `rebalance_to_topn` 切换到 `order_target_weights` 自托管，需充分测试：
- equal 模式行为是否与原 `rebalance_to_topn` 完全一致（除 cash_reserve）；
- score 模式归一化逻辑是否与原 akquant 实现一致；
- top_n 截断 + liquidate_unmentioned 组合是否正确。

**回滚方案**：保留 `rebalance_to_topn` 调用路径作为 feature flag（`USE_LEGACY_REBALANCE=true`），出问题可一键回切。

### 12.2 P1-1 point_in_time 强制的风险

watcher 接口若不稳定，会导致**所有轮动回测大面积失败**。

**缓解**：
- 上线前对 watcher `/api/internal/constituents/query` 做压力测试（覆盖 csi300/csi500/all_a_shares/manual 全 universe）；
- 先在测试环境灰度 1 周，监控 `PIT_*` 错误率；
- 错误率 > 1% 时触发告警。

### 12.3 P0-3 成交价默认改为当日收盘的风险

轮动范式默认 fill_policy 从 next-open 改为 same_cycle（当日收盘），会导致**历史回测结果全部变化**（收益/回撤数字与旧版不一致）。

**缓解**：
- 上线前用同一策略配置对比「旧默认 next-open」vs「新默认 same_cycle」的结果差异，量化影响；
- 前端 fill_policy 控件保留「次日开盘」选项，用户可手动切回旧语义；
- 在更新日志/changelog 显著标注「轮动范式默认成交价已变更」。

### 12.4 P0-4 涨停拒买的风险

涨停拒买会让小市值策略（涨停频发）的**实际买入标的数显著少于 top_n**，收益可能下降（与旧版「假装买到」对比）。

**缓解**：
- 这是「回测更真实」的正确方向，收益下降是挤泡沫，不是 bug；
- P0-5 的 rebalance_diagnosis 回传「涨停拒买数」，让用户知道差异原因；
- `reject_limit_up_on_buy` 设为可配置（默认 True），用户可关闭做对比。

---

## §13 附录：涉及的代码文件清单

### 13.1 engine（Python）

| 文件 | 改动 |
|---|---|
| [`services/strategy/models.py`](../../../stock-engine/services/strategy/models.py) | 删 UniverseModel.point_in_time；PortfolioModel 加 cash_reserve_pct / max_weight_per_symbol / max_industry_exposure / buffer_n；RebalanceModel 的 day_of_period 替换为 trigger(first/last) + 加 min_holding_bars / reject_limit_up_on_buy / reject_limit_down_on_sell / execution；FactorNode 加 transform（P1-6 二期） |
| [`services/strategy/validator.py`](../../../stock-engine/services/strategy/validator.py) | 加 FACTOR_SCORE_INCOMPATIBLE / INVALID_REBALANCE_TRIGGER 校验；放开 rebalance+exit 组合（P1-5） |
| [`services/strategy/errors.py`](../../../stock-engine/services/strategy/errors.py) | 加 FACTOR_SCORE_INCOMPATIBLE / PIT_WATCHER_UNAVAILABLE / PIT_CONSTITUENTS_EMPTY / PIT_QUERY_FAILED / INVALID_REBALANCE_TRIGGER |
| [`services/backtest/rebalance_engine.py`](../../../stock-engine/services/backtest/rebalance_engine.py) | _apply_universe_filter 改强制 + 失败抛错；新增 _filter_by_static_rules |
| [`services/backtest/compiler.py`](../../../stock-engine/services/backtest/compiler.py) | 加 single+score 校验；**删除 _is_rebalance_trigger_day**；on_daily_rebalance 改查 trade_cal 标记（零状态）；捕获 trigger / cash_reserve / max_weight / max_industry / min_holding_bars / buffer_n / reject_limit_up；**on_daily_rebalance 改自托管权重**（P1-3 方案 C）；涨停拒买（P0-4）；score 降序优先（P0-5） |
| [`services/backtest/runner.py`](../../../stock-engine/services/backtest/runner.py) | **轮动范式 fill_policy 默认当日收盘成交**（P0-3） |
| [`services/backtest/data_adapter.py`](../../../stock-engine/services/backtest/data_adapter.py) | kline_to_extra_map 提取 is_st / is_suspended / is_limit_up / is_limit_down / industry / list_date / 6 个 is_*_of_* 调仓标记 |
| [`services/backtest/result_serializer.py`](../../../stock-engine/services/backtest/result_serializer.py) | 增加 rebalance_diagnosis（P0-5）/ effective_config.warmup_period（P2-5）/ annual_turnover_ratio（P2-8） |

### 13.2 watcher（Java）

| 文件 | 改动 |
|---|---|
| [`constant/StrategySchemaConstants.java`](../../../stock-watcher/src/main/java/com/arthur/stock/constant/StrategySchemaConstants.java) | 移除 point_in_time 白名单 |
| [`resources/schema-mysql.sql`](../../../stock-watcher/src/main/resources/schema-mysql.sql) + [`schema-sqlite.sql`](../../../stock-watcher/src/main/resources/schema-sqlite.sql) + [`DataInitServiceImpl`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) | trade_cal 表加 6 个 is_*_of_* 标记字段（**三处同步**）+ sw_industry / sw_industry_member 两表（见 §14） |
| `service/impl/TradeCalServiceImpl.java` | 同步 trade_cal 时预计算 6 个调仓标记；新增 queryFlagsByRange 供回测批量查 |
| `service/impl/BacktestServiceImpl.java` | kline_data 下发 is_st / industry / list_date / 6 个 is_*_of_* 标记 / sw_industry_l1 |

### 13.3 前端

| 文件 | 改动 |
|---|---|
| [`templates/quant/strategies/editor.html`](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html) | 移除 point_in_time 开关；Portfolio 层加 cash_reserve / max_weight / max_industry / buffer_n 控件；**f-reb-day 替换为 f-reb-trigger 下拉**；**Tab 6 data-paradigm 改 both**（P1-5）；Tab 8 加 fill_policy（P0-3 默认当日收盘）/ risk_config；条件树模式切换加确认框（P2-6） |
| [`static/js/strategy-editor.js`](../../../stock-watcher/src/main/resources/static/js/strategy-editor.js) | weightRowHtml 改联想输入 + 方向切换 + **归一化实时提示**（P2-7）；single+score 联动禁用；移除 point_in_time；**trigger 下拉随 frequency 动态显隐**；**条件树模式切换双向同步**（P2-6） |
| 回测结果展示页（backtest-result.js 等） | 展示 rebalance_diagnosis（P0-5）/ effective_config.warmup_period（P2-5）/ annual_turnover_ratio（P2-8） |

---

## §14 P0-0 前置依赖：申万行业分类数据对接

### 14.1 背景与决策

#### 14.1.1 触发原因

P0-1（静态过滤）、P1-4（行业暴露上限）、P2-1（行业轮动）都依赖「每只标的的行业归属」。核查现状（见第 5 轮分析）发现：

| 维度 | 现状 | 问题 |
|---|---|---|
| 数据源 | `stock_basic.industry`（Tushare `stock_basic` 接口自带） | Tushare 简化口径，非标准申万分类 |
| 层级 | 无（扁平字符串） | 无法区分一级/二级/三级 |
| 代码 | 无（只有中文名） | 无法做精确匹配与聚合 |
| 回测路径 | kline_data **完全不下发** industry | engine 拿不到行业信息 |

依据用户决策：**Tushare 积分够 2000，按 Tushare 标准对接流程，把申万行业分类数据对接回来**。

#### 14.1.2 对接的 Tushare 接口（两个）

| 接口 | doc_id | 用途 | 权限 | 限量 |
|---|---|---|---|---|
| `index_classify` | [181](https://tushare.pro/document/2?doc_id=181) | 申万行业分类列表（一/二/三级目录树） | 2000 积分 | 单次不限 |
| `index_member_all` | [335](https://tushare.pro/document/2?doc_id=335) | 申万行业成分构成（股票↔行业归属） | 2000 积分 | 单次最大 2000 行，总量不限 |

**对接目标**：落库两张表 + 全量初始化 + 每日增量同步，engine 经 watcher 内部接口按 ts_code 查询任意标的的申万一/二/级行业归属。

#### 14.1.3 申万行业分类版本

`index_classify` 支持 2014 版（28 一级 / 104 二级 / 227 三级）与 2021 版（31 一级 / 134 二级 / 346 三级）。**本项目采用 2021 版（`src=SWS2021`）**，覆��更全，是当前主流。

---

### 14.2 改造方案（对齐 index_weight 端到端范例）

参照第 5 轮核查的 [index_weight 对接范例](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/IndexWeightServiceImpl.java)，申万行业分类按同一套规范对接，共 **10 处改动**。

#### 14.2.1 数据库 DDL（三处同步，防 db-type 切换缺表）

新增两张表：`sw_industry`（行业目录树）+ `sw_industry_member`（股票↔行业归属）。

**MySQL 版**（[`schema-mysql.sql`](../../../stock-watcher/src/main/resources/schema-mysql.sql)）：

```sql
-- 申万行业分类目录树（index_classify 接口）
CREATE TABLE IF NOT EXISTS sw_industry (
    index_code    VARCHAR(16)  NOT NULL COMMENT '行业代码（如 801010.SI 农林牧渔）',
    index_name    VARCHAR(64)  NOT NULL COMMENT '行业名称（如 农林牧渔）',
    level         TINYINT      NOT NULL COMMENT '分类层级：1=一级 2=二级 3=三级',
    parent_code   VARCHAR(16)  COMMENT '父级行业代码（一级为空）',
    src           VARCHAR(16)  NOT NULL DEFAULT 'SWS2021' COMMENT '分类版本',
    PRIMARY KEY (index_code, src),
    INDEX idx_sw_industry_level (level),
    INDEX idx_sw_industry_parent (parent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utfmt4 COMMENT='申万行业分类目录树';

-- 申万行业成分股（index_member_all 接口）
CREATE TABLE IF NOT EXISTS sw_industry_member (
    ts_code       VARCHAR(16)  NOT NULL COMMENT '股票代码（如 000001.SZ）',
    index_code    VARCHAR(16)  NOT NULL COMMENT '归属行业代码（一级，如 801790.SI 银行）',
    index_name    VARCHAR(64)  NOT NULL COMMENT '行业名称（冗余，便于查询）',
    in_date       VARCHAR(8)   COMMENT '纳入日期（YYYYMMDD）',
    out_date      VARCHAR(8)   COMMENT '剔除日期（YYYYMMDD，空=当前在册）',
    is_new        TINYINT      COMMENT '是否最新（1=最新 0=历史）',
    src           VARCHAR(16)  NOT NULL DEFAULT 'SWS2021',
    update_date   VARCHAR(8)   NOT NULL COMMENT '数据快照日期（YYYYMMDD）',
    PRIMARY KEY (ts_code, index_code, update_date),
    INDEX idx_sw_member_tscode (ts_code),
    INDEX idx_sw_member_latest (ts_code, is_new)
) ENGINE=InnoDB DEFAULT CHARSET=utfmt4 COMMENT='申万行业成分股归属';
```

**同步到**：[`schema-sqlite.sql`](../../../stock-watcher/src/main/resources/schema-sqlite.sql) + [`DataInitServiceImpl.java`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) 内置的 `CREATE_TABLE_SQL_MYSQL` / `CREATE_TABLE_SQL_SQLITE` 两个 Map（**三处必须全部同步**，这是范例里的已知坑）。

#### 14.2.2 TushareApiEnum 注册

[`TushareApiEnum.java`](../../../stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) 新增两个枚举：

```java
/** 申万行业分类（index_classify）：一/二/三级目录树 */
INDEX_CLASSIFY("index_classify",
        "index_code,index_name,level,parent_code,price_scope,weight,pe,pb,float_share,total_share"),

/** 申万行业成分（index_member_all）：股票↔行业归属 */
INDEX_MEMBER_ALL("index_member_all",
        "ts_code,index_code,index_name,in_date,out_date,updated,is_new");
```

#### 14.2.3 DTO（请求 + 响应）

新建 `dto/tushare/` 下四个 DTO（参照 [`IndexWeightQueryDTO`](../../../stock-watcher/src/main/java/com/arthur/stock/dto/tushare/IndexWeightQueryDTO.java) / [`IndexWeightDTO`](../../../stock-watcher/src/main/java/com/arthur/stock/dto/tushare/IndexWeightDTO.java)）：

- `IndexClassifyQueryDTO`：`src`（SWS2021/SWS2014，默认 SWS2021）
- `IndexClassifyDTO`：`index_code / index_name / level / parent_code`
- `IndexMemberQueryDTO`：`ts_code`（可选）/ `index_code`（可选）/ `src`
- `IndexMemberDTO`：`ts_code / index_code / index_name / in_date / out_date / is_new`

> 响应 DTO 字段映射规则：snake_case 用 `@JSONField(name="snake_case")`，已是单词的（如 `weight`）可不加。

#### 14.2.4 DO（数据库实体）

新建 `model/` 下两个 DO（参照 [`IndexWeightDO`](../../../stock-watcher/src/main/java/com/arthur/stock/model/IndexWeightDO.java)）：

- `SwIndustryDO`：`@TableName("sw_industry")`，字段 `indexCode / indexName / level / parentCode / src`
- `SwIndustryMemberDO`：`@TableName("sw_industry_member")`，字段 `tsCode / indexCode / indexName / inDate / outDate / isNew / src / updateDate`

> 无自增主键，PK 为复合业务键（见 DDL），不使用 `@TableId`。

#### 14.2.5 Mapper（DAO，注解式，无 XML）

新建 `mapper/` 下两个 Mapper（参照 [`IndexWeightMapper`](../../../stock-watcher/src/main/java/com/arthur/stock/mapper/IndexWeightMapper.java) 的 `@Select` 注解风格）：

`SwIndustryMapper`：
```java
@Select("SELECT * FROM sw_industry WHERE level = #{level} AND src = 'SWS2021'")
List<SwIndustryDO> selectByLevel(@Param("level") int level);
```

`SwIndustryMemberMapper`（核心查询接口）：
```java
/** 查某只股票当前归属的申万一级行业（回测/选股高频调用） */
@Select("SELECT m.index_code, m.index_name FROM sw_industry_member m "
        + "WHERE m.ts_code = #{tsCode} AND m.is_new = 1 AND m.src = 'SWS2021' "
        + "AND m.index_code IN (SELECT index_code FROM sw_industry WHERE level = 1)")
SwIndustryMemberDO selectLatestL1ByTsCode(@Param("tsCode") String tsCode);

/** point-in-time 查询：≤ 指定日期最新归属的申万一级行业（回测防幸存者偏差） */
@Select("SELECT m.index_code, m.index_name FROM sw_industry_member m "
        + "WHERE m.ts_code = #{tsCode} AND m.src = 'SWS2021' "
        + "AND m.update_date <= #{tradeDate} "
        + "AND m.index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
        + "ORDER BY m.update_date DESC LIMIT 1")
SwIndustryMemberDO selectL1AtDate(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
```

> **point-in-time 查询**是关键：股票的行业归属会随申万定期调整（每年 6/12 月）变动，回测早期必须用当时的归属，不能用最新归属，否则又是 lookahead bias。

#### 14.2.6 Service（拉取 + 落库，含幂等）

新建 `service/` 下 `SwIndustryService` 接口 + `SwIndustryServiceImpl`，参照 [`IndexWeightServiceImpl`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/IndexWeightServiceImpl.java)：

```java
public interface SwIndustryService {
    /** 拉取申万行业目录树（全量，一/二/三级） */
    int fetchAndSaveClassify(String src);
    /** 拉取行业成分（按 index_code 或 ts_code） */
    int fetchAndSaveMembers(String indexCode, String tsCode, String src);
    /** 全量初始化所有成分（分页拉取，单次 2000 行） */
    int fetchAndSaveAllMembers(String src);
    /** 查询接口（高频，供 BacktestServiceImpl / ScreenerServiceImpl 调用） */
    String getLatestL1Industry(String tsCode);
    SwIndustryMemberDO getL1IndustryAt(String tsCode, String tradeDate);
}
```

**幂等模式**（与 index_weight 一致）：按业务键先 `delete` 再 `insert`。

**分页拉取**（`index_member_all` 单次 2000 行限制）：循环调 `TushareClient.indexMemberAll`，按 offset 或 ts_code 区间分页，直到返回行数 < 2000。

#### 14.2.7 TushareClient 扩展

[`TushareClient.java`](../../../stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java) 新增两个方法（参照 `indexWeight` 的实现 L147-150 + 参数构建 L326-341）：

```java
public List<IndexClassifyDTO> indexClassify(IndexClassifyQueryDTO param) {
    JSONObject params = new JSONObject();
    params.put("level", param.getLevel());       // 1/2/3 或不传=全部
    params.put("src", param.getSrc());           // SWS2021
    return query(TushareApiEnum.INDEX_CLASSIFY, params, IndexClassifyDTO.class);
}

public List<IndexMemberDTO> indexMemberAll(IndexMemberQueryDTO param) {
    JSONObject params = new JSONObject();
    if (param.getTsCode() != null) params.put("ts_code", param.getTsCode());
    if (param.getIndexCode() != null) params.put("index_code", param.getIndexCode());
    params.put("src", param.getSrc());
    return query(TushareApiEnum.INDEX_MEMBER_ALL, params, IndexMemberDTO.class);
}
```

> 通用 `query()` 方法（限流 + HTTP + 解析）已就绪，无需改动。

#### 14.2.8 InitStep + DataInitServiceImpl 注册

[`InitStep.java`](../../../stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java) 新增：
```java
SW_INDUSTRY("sw_industry", "申万行业分类", "sw_industry")
```

[`DataInitServiceImpl.java`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) 三处同步：
- `EXECUTION_ORDER` 列表：插在 `INDEX_WEIGHT` 之后（行业归属依赖股票基础信息）；
- `doInitialize` switch：新增 `case SW_INDUSTRY -> executeSwIndustry()`；
- DDL Map（MySQL + SQLite 两份）：加 `sw_industry` + `sw_industry_member` 建表语句。

`executeSwIndustry()` 实现：
```java
private void executeSwIndustry() {
    updateStep("拉取申万行业分类");
    String src = "SWS2021";
    // 1. 目录树（一/二/三级，一次性拉全）
    swIndustryService.fetchAndSaveClassify(src);
    // 2. 全量成分股（分页拉取，约 5000+ 股票）
    int n = swIndustryService.fetchAndSaveAllMembers(src);
    log.info("SW industry members synced: {} records", n);
}
```

#### 14.2.9 定时任务（低频）

新建 `task/SwIndustryTask.java`（参照 [`IndexWeightTask`](../../../stock-watcher/src/main/java/com/arthur/stock/task/IndexWeightTask.java)）：

```java
@Scheduled(cron = "0 0 22 1 1,7 *")  // 每年 1 月、7 月 1 号执行（申万半年调一次）
public void syncSemiAnnual() {
    String src = "SWS2021";
    swIndustryService.fetchAndSaveClassify(src);
    swIndustryService.fetchAndSaveAllMembers(src);
}
```

> 申万行业调整频率低（每年 6/12 月生效），cron 设为半年一次，比 index_weight 的每日同步频率低得多。

#### 14.2.10 application.yml 限流配置

[`application.yml`](../../../stock-watcher/src/main/resources/application.yml) 新增：

```yaml
tushare:
  rate-limit:
    index_classify:
      permits-per-minute: 200
    index_member_all:
      permits-per-minute: 200   # 5000+ 股票分页拉取，约 3 分钟完成
```

---

### 14.3 watcher → engine 下发链路

#### 14.3.1 回测路径（kline_data 注入行业）

修改 [`BacktestServiceImpl.buildKlineData`](../../../stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java#L544-L593)，在构造每只标的的 bar 时注入申万一级行业：

```java
private JSONObject buildKlineData(JSONObject configJson) {
    // ... 解析 symbols ...
    // 新增：批量查申万一级行业（一次性查，避免逐 bar 查询）
    Map<String, String> swIndustryMap = new HashMap<>();
    for (String code : symbols) {
        String industry = swIndustryService.getLatestL1Industry(code);
        if (industry != null) swIndustryMap.put(code, industry);
    }
    for (String code : symbols) {
        for (DailyQuoteDO q : quotes) {
            JSONObject bar = new JSONObject();
            // ... 原有 OHLCV + daily_basic 字段 ...
            bar.put("sw_industry_l1", swIndustryMap.get(code));   // 新增
            arr.add(bar);
        }
    }
}
```

> 行业是 stock 级别的半静态字段（半年调一次），每只股票整段 K 线的 `sw_industry_l1` 相同。但若回测跨度 > 半年且股票期间换过行业，需用 point-in-time 查询（见 14.2.5 的 `selectL1AtDate`），按 bar 的 trade_date 查当时的归属——**一期可先用 `getLatestL1Industry` 简化，二期再支持 point-in-time**。

#### 14.3.2 engine 侧消费

[`data_adapter.kline_to_extra_map`](../../../stock-engine/services/backtest/data_adapter.py) 提取 `sw_industry_l1` 字段，注入 `extra_map[symbol][trade_date]["sw_industry_l1"]`。rebalance_engine 取用时：

```python
industry = candidate["extra"][trade_date_str].get("sw_industry_l1") or candidate["extra"][trade_date_str].get("industry")
```

---

### 14.4 验收 checklist

- [ ] `sw_industry` 表落库申万 2021 版三级目录树（31 一级 + 134 二级 + 346 三级）；
- [ ] `sw_industry_member` 表落库全 A 股票的当前行业归属（约 5000+ 条 `is_new=1`）；
- [ ] `SwIndustryMemberMapper.selectLatestL1ByTsCode("000001.SZ")` 返回「银行」；
- [ ] `BacktestServiceImpl.buildKlineData` 下发的 bar 含 `sw_industry_l1` 字段；
- [ ] engine `kline_to_extra_map` 能提取 `sw_industry_l1`；
- [ ] DataInit 初始化入口可一键拉取（`InitStep.SW_INDUSTRY`）；
- [ ] 定时任务每年 1/7 月自动同步；
- [ ] 限流配置生效（`index_classify` / `index_member_all` 各 200/min）；
- [ ] point-in-time 查询接口 `selectL1AtDate` 存在（二期启用）。

---

### 14.5 实施优先级

**P0-0（最高，P0-1 / P1-4 的前置依赖）**：

- 14.2.1 ~ 14.2.10 全部为 watcher 侧独立改造，无 engine 依赖，可先行；
- 完成后 P0-1（静态过滤）和 P1-4（行业暴露）才有标准行业数据可用；
- 一期可用 `getLatestL1Industry`（非 point-in-time）简化，二期再支持 `selectL1AtDate`。

**工作量估算**：watcher 侧 2-3 天（10 处改动 + 联调 Tushare 接口 + 数据校验）。

---

### 14.6 风险

#### 14.6.1 Tushare 接口限流与分页

`index_member_all` 单次 2000 行，5000+ 股票需分 3 页。需在 `SwIndustryServiceImpl.fetchAndSaveAllMembers` 实现分页循环（按 offset 或按 index_code 分批），并处理限流等待。

#### 14.6.2 行业归属的 point-in-time 一致性

若一期用 `getLatestL1Industry`（最新归属）而非 `selectL1AtDate`（当时归属），跨年度回测会产生轻微 lookahead bias（股票期间换行业）。**一期可接受**（行业调整影响有限），二期补 point-in-time。

#### 14.6.3 三处 DDL 同步

`DataInitServiceImpl` 的 MySQL/SQLite Map + 外部 schema-*.sql 文件三处冗余存储，新增表时**必须三处都加**，否则切换 db-type 或走 schema 文件初始化会缺表。这是 index_weight 范例里的已知坑，本次对接需特别注意。

---

## §15 第二波：回测可信度与实战可用性（老股民视角）

> 本章节从「做了 20 年 A 股、管过私募产品」的老股民视角，审视第一波未覆盖的「回测数字可不可信」与「实战能不能用」两类问题。第一波解决的是「功能有没有」，本波解决的是「算出来的数字骗不骗人」与「老股民愿不愿意用」。

---

### §15.1 P0-3：调仓成交价时机（回测与实盘脱节）

#### 15.1.1 现象

akquant 默认成交语义为 **next-open**（[`strategy.py on_pre_open` L1455](../../../akquant-0.2.47/python/akquant/strategy.py#L1455) 注释「默认使用 next-open 成交语义」）。轮动调仓在 `on_daily_rebalance` 选股（用当日收盘数据），订单在**次日开盘**成交。

#### 15.1.2 老股民痛点

| 问题 | 说明 |
|---|---|
| **隔夜风险未体现** | 月末收盘选股 → 次月首个交易日开盘成交，中间隔一夜/一周末。若隔夜出利空，次日低开 5%，回测未体现此风险 |
| **实盘调仓在尾盘** | 老股民实盘调仓都在 14:50-14:57 集合竞价或收盘前几分钟，抓的就是「收盘价」，不是次日开盘 |
| **收益虚高** | 回测用「选股日收盘 → 次日开盘」跨日成交，若标的次日高开（动量效应），回测假设按较低的开盘价买入，收益虚高 |

#### 15.1.3 改造方案

**engine 侧**：轮动范式 `fill_policy` 默认改为**当日收盘成交**（`price_basis=close, temporal=same_cycle, bar_offset=0`），而非 akquant 默认的 next-open。

[`runner.build_backtest_kwargs`](../../../stock-engine/services/backtest/runner.py#L107-L109) 增加：
```python
# 轮动范式默认当日收盘成交（贴合实盘尾盘调仓）
if has_rebalance and not bt_config.fill_policy:
    kwargs["fill_policy"] = aq.make_fill_policy(
        price_basis="close", temporal="same_cycle", bar_offset=0
    )
```

**前端侧**（升级 P2-2）：Tab 8 fill_policy 控件，轮动范式下默认选中「当日收盘」并标注「推荐：贴合实盘尾盘调仓」：
```html
<select id="f-bt-fill-policy">
    <option value='{"price_basis":"close","temporal":"same_cycle","bar_offset":0}' selected>
        当日收盘成交（推荐·尾盘调仓）
    </option>
    <option value='{"price_basis":"open","temporal":"next_event","bar_offset":1}'>
        次日开盘成交
    </option>
</select>
```

#### 15.1.4 验收
- [ ] 轮动范式不填 fill_policy 时，默认当日收盘成交；
- [ ] 前端轮动范式下 fill_policy 默认显示「当日收盘」+ 推荐标注；
- [ ] 回测 trades_df 的 entry_price = 选股日 close（非次日 open）。

---

### §15.2 P0-4：涨跌停买入成交不真实

#### 15.2.1 现象

P0-1 的 `exclude_limit_up=true` 只是**选股阶段排除**涨停标的，不等于**成交阶段处理**。akquant 的 `volume_limit_pct` 只限制成交量占比，**不判断涨停**——若目标买入价 ≥ 当日涨停价，回测仍按涨停价成交（假装买到了）。

#### 15.2.2 老股民痛点

小市值策略尤甚：选出 20 只，次日 3 只涨停封板 → **实盘根本买不进**，但回测假装买到了 → 仓位虚高 → 收益虚高。老股民都知道「涨停板买不入」是铁律。

#### 15.2.3 改造方案

**engine 侧**：在 `order_target_weights` 调用前，对每个目标买入标的判断「当日是否涨停」，涨停的标的**从 target_weights 中剔除**（不买），释放的权重转现金。

依赖 P0-1 的 watcher 下发 `is_limit_up` 元数据。判断逻辑（在 P1-3 自托管权重计算后）：
```python
# 涨停拒买：买入方向标的若当日涨停，从 target_weights 剔除
if self._reject_limit_up_on_buy:
    for sym in list(target_weights.keys()):
        if target_weights[sym] > 0:  # 买入方向
            is_limit_up = self._get_bar_flag(trading_date, "is_limit_up")
            if is_limit_up == "1":
                del target_weights[sym]   # 涨停买不入，剔除
                logger.info("涨停拒买: %s @ %s", sym, trading_date)
```

> 新增配置项 `reject_limit_up_on_buy: bool = True`（默认开启，贴合实盘）。卖出方向同理可配 `reject_limit_down_on_sell`（跌停卖不出）。

#### 15.2.4 验收
- [ ] 涨停标的在买入时被剔除，trades_df 无该标的的买入记录；
- [ ] 回测日志可见「涨停拒买: XXX」；
- [ ] 仓位虚高问题消除（与关闭对比，收益更保守）。

---

### §15.3 P0-5：资金不足时静默拒单

#### 15.3.1 现象

`rebalance_to_topn` → `order_target_weights` 等权分配 30 只，每只 3.33 万。若某只高价股 100 股就要 5 万（如茅台），资金不够买 1 手 → akquant **拒单**。30 只里可能只买到前 15 只（按 symbol 排序，非 score 排序），后 15 只资金耗尽没买。**用户无感知**。

#### 15.3.2 老股民痛点

- 选出 30 只但只买到 15 只，**实际仓位只有 50%**，回测收益基于满仓计算 → 严重虚高；
- 「谁先买到」按 symbol 字母序（`000001` 先于 `600000`），**不是按 score 优先级**，不公平且不可复现。

#### 15.3.3 改造方案

**engine 侧**（依赖 P1-3 自托管权重）：在计算 target_weights 后，**按 score 降序排序后再调 order_target_weights**，确保高分标的优先获得资金：
```python
# 按 score 降序排序（高分优先分配资金）
sorted_symbols = sorted(scores.items(), key=lambda x: -x[1])
target_weights = {sym: w for sym, w in sorted_symbols if sym in selected}
```

**结果回传侧**：`serialize_result` 增加资金分配诊断信息：
```python
"rebalance_diagnosis": {
    "selected_count": 30,           # 选出的标的数
    "actually_bought": 22,          # 实际成交的标的数
    "rejected_by_cash": 8,          # 因资金不足拒单数
    "rejected_by_limit_up": 0,      # 因涨停拒买数
    "actual_invest_ratio": 0.73,    # 实际投资比例（实际市值/总资产）
}
```

**前端侧**：回测结果展示「实际成交 22/30 只，资金不足拒单 8 只」警示。

#### 15.3.4 验收
- [ ] target_weights 按 score 降序排列（高分优先）；
- [ ] 回测结果含 rebalance_diagnosis 字段；
- [ ] 前端展示资金分配诊断（成交数/选出数/拒单原因）。

---

### §15.4 P1-5：轮动范式 Tab6 止损止盈被隐藏

#### 15.4.1 现象

[`editor.html` Tab 6](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html) 的止损止盈 Tab 标注 `data-paradigm="signals"`，**轮动范式下隐藏**。但 engine 的 `exit.bracket` 挂在 `on_before_trading`（[compiler.py L794-L856](../../../stock-engine/services/backtest/compiler.py#L794-L856)），**轮动范式也会触发**—���只是前端不让你配。

#### 15.4.2 老股民痛点

轮动策略月度调仓，中间跌 30% 都不动——**裸奔一个月**。老股民绝对不接受「月度选股但中途跌破 10% 不止损」。

#### 15.4.3 改造方案

**前端侧**：Tab 6 的 `data-paradigm` 从 `signals` 改为 `both`（两种范式都可见）。但需校验：
- `exit.bracket`（静态止损止盈）：轮动可用（挂 on_before_trading，与 rebalance 不冲突）；
- `exit.rules`（动态条件出场）：轮动可用（挂 on_bar，每根 bar 评估）；
- **但 `position_sizing` 在轮动范式下无意义**（轮动用 order_target_weights 不用 position_sizing），Tab 6 里的 position_sizing 相关控件对轮动隐藏。

**schema 侧**：[`validator.py`](../../../stock-engine/services/strategy/validator.py) 放开「轮动 + exit」组合（当前未禁止，但需确认 `exit` 不依赖 `signals`）。

#### 15.4.4 验收
- [ ] 轮动范式下 Tab 6 可见（至少 bracket 部分）；
- [ ] `rebalance + exit.bracket` 组合可保存可回测；
- [ ] 持仓跌破 stop_loss_pct 时触发止损卖出。

---

### §15.5 P1-6：选股条件不支持滚动窗口聚合

#### 15.5.1 现象

filter.conditions 和 factor 的 FactorNode 只取**当日因子快照值**。FactorNode 的 `params: {"timeperiod": 20}` 只是指标计算周期（MA20 = 20 日均线当天的点），**不是「对因子值再做 N 日平均」**。

#### 15.5.2 老股民痛点

老股民选股常用：
- 「过去 20 日均价 > MA60」（均线多头排列）——需要 CLOSE 的 20 日均值 vs MA60；
- 「过去 5 日涨幅 > 10%」——需要 5 日收益率；
- 「过去 20 日波动率 < 0.3」——需要 20 日标准差。

当前**都无法表达**——只能用「当天 MA20 > 当天 MA60」（等价，因为 MA 本身就是滚动窗口），但「过去 20 日 PE 均值 < 30」这种**对基本面因子做滚动平均**就无法表达。

#### 15.5.3 改造方案

FactorNode 扩展 `transform` 字段（二期，架构改动较大）：
```python
class FactorNode(BaseModel):
    factor: str
    params: Optional[Dict[str, Any]]      # 指标参数（如 timeperiod）
    transform: Optional[TransformConfig]  # 新增：对因子值再做聚合
    output_index: Optional[int]

class TransformConfig(BaseModel):
    type: Literal["ma", "std", "pct_change", "max", "min"]  # 聚合类型
    window: int                                               # 窗口天数
```

示例：「过去 20 日 PE 均值 < 30」：
```json
{"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}, "comparator": "<", "right": {"value": 30}}
```

> 此改造影响因子计算管线（需在 rebalance_engine 截面计算时多取 N 天历史），工作量较大，建议二期。

#### 15.5.4 验收
- [ ] FactorNode 支持 transform 字段；
- [ ] `PE_TTM + transform(ma, 20)` 能算出 20 日 PE 均值；
- [ ] 条件树可用滚动窗口聚合条件。

---

### §15.6 P1-7：换仓无缓冲带（buffer）

#### 15.6.1 现象

每月选 top 20，排名第 19/21 的标的会**反复进出组合**——本月第 19（买入）→ 下月第 21（卖出）→ 第三月第 19（再买入），光手续费就亏死。

#### 15.6.2 老股民痛点

「边缘抖动」在因子值接近的标的间非常常见。实盘用**缓冲带（buffer）**解决：买入门槛 `top_(n - buffer)`，卖出门槛 `top_(n + buffer)`。这是指数基金跟踪调仓的标配技术。

#### 15.6.3 改造方案

PortfolioModel 加 `buffer_n`：
```python
class PortfolioModel(BaseModel):
    top_n: Optional[int]
    buffer_n: Optional[int] = Field(None, ge=0, description="缓冲数量。买入取 top_(n-buffer)，卖出取 top_(n+buffer)")
```

engine 侧（P1-3 自托管权重后）调仓逻辑改为：
```python
# 有 buffer 时：新标的需进入 top_(n-buffer) 才买入；持仓标的跌出 top_(n+buffer) 才卖出
buy_threshold = top_n - buffer_n   # 买入门槛（更严）
sell_threshold = top_n + buffer_n  # 卖出门槛（更松）

new_candidates = ranked[:buy_threshold]            # 新买入候选
current_holding = self.get_positions()              # 当前持仓
sell_candidates = [s for s in ranked[sell_threshold:] if s in current_holding]  # 仅跌出 top_(n+buffer) 的持仓才卖
```

#### 15.6.4 验收
- [ ] `buffer_n=5, top_n=20` 时，新标的需进入 top 15 才买入；
- [ ] 持仓标的跌出 top 25 才卖出；
- [ ] 回测换手率显著下降（与 buffer_n=0 对比）。

---

### §15.7 P2-5：warmup_period 被覆盖但用户无感知

#### 15.7.1 现象

[compiler.py L493-L503](../../../stock-engine/services/backtest/compiler.py#L493-L503) warmup 取「用户填的值」与「自动推断值」的**较大者**。用户填 30，系统推断 60（因 MACD），实际用 60，**用户不知道**。

#### 15.7.2 改造方案

`serialize_result` 回传实际生效的 warmup_period：
```python
"effective_config": {
    "warmup_period": 60,              # 实际生效值
    "warmup_source": "auto_inferred", # auto_inferred / user_override
    "warmup_reason": "MACD 需要 slowperiod+signalperiod=35，+2 buffer = 37，兜底 60",
}
```

前端回测结果展示「系统建议 warmup: 60（基于因子窗口自动推断）」。

---

### §15.8 P2-6：条件树可视化/JSON 模式切换丢数据

#### 15.8.1 现象

[`editor.html` L212-L219](../../../stock-watcher/src/main/resources/templates/quant/strategies/editor.html) 的「可视化 / JSON」模式切换，若实现非双向同步，切换可能丢数据。

#### 15.8.2 改造方案

前端确保模式切换时**双向同步**：可视化 → JSON（序列化展示）；JSON → 可视化（反序列化渲染）。切换前弹确认框「切换模式将同步当前编辑内容，是否继续？」。

---

### §15.9 P2-7：composite 权重无归一化提示

#### 15.9.1 现象

用户填 `{"ROE_TTM": 1.5, "PE_TTM": -0.8}`，系统按绝对值归一化（1.5/2.3 ≈ 65%），但前端没提示。

#### 15.9.2 改造方案

权重面板底部实时显示归一化结果：
```
归一化后实际权重：ROE_TTM 65.2% · PE_TTM 34.8%（按绝对值归一化）
```

并入 P1-2 权重面板重设计。

---

### §15.10 P2-8：回测结果无年化换手率

#### 15.10.1 现象

`BacktestResult` 有 trade_count，但无换手率（老股民第三眼看的指标）。

#### 15.10.2 改造方案

`serialize_result` 增加换手率计算：
```python
# 年化换手率 = 期间总成交额(买+卖) / 平均持仓市值 / 年化因子
total_turnover = trades_df["entry_price * quantity"].sum() + trades_df["exit_price * quantity"].sum()
avg_position_value = equity_curve.mean()
annualization = 252 / len(equity_curve)  # 日线年化因子
annual_turnover = total_turnover / avg_position_value * annualization
```

metrics 增加 `annual_turnover_ratio`。

---

### §15.11 P2-9：无分批调仓能力

#### 15.11.1 现状

轮动调仓一次性全量切换，大资金（100 万+）冲击成本未建模，回测高估收益。

#### 15.11.2 改造方案（进阶，三期）

RebalanceModel 加 `execution`：
```python
execution: Optional[ExecutionConfig] = Field(None, description="分批调仓配置")

class ExecutionConfig(BaseModel):
    split_days: int = Field(1, ge=1, le=5, description="分批天数（1=一次性，3=分3天）")
    impact_cost_bps: Optional[float] = Field(None, description="冲击成本(bps)，按成交量线性建模")
```

engine 侧将单次调仓拆为 N 天，每天调 1/N 仓位。akquant 的 `on_daily_rebalance` 每日触发，天然支持分批。

---

### §15.12 本波验收汇总

| 编号 | 验收要点 |
|---|---|
| P0-3 | 轮动默认当日收盘成交；trades_df entry_price = 选股日 close |
| P0-4 | 涨停标的买入时剔除；日志可见「涨停拒买」 |
| P0-5 | target_weights 按 score 降序；结果含 rebalance_diagnosis；前端展示成交数/选出数 |
| P1-5 | 轮动范式 Tab6 可见；rebalance+exit.bracket 可回测；止损触发正常 |
| P1-6 | FactorNode 支持 transform；PE_TTM+ma(20) 可算 |
| P1-7 | buffer_n 生效；新标的进 top_(n-buffer) 才买；换手率下降 |
| P2-5 | 结果含 effective_config.warmup_period；前端展示实际 warmup |
| P2-6 | 模式切换双向同步 + 确认框 |
| P2-7 | 权重面板实时显示归一化结果 |
| P2-8 | metrics 含 annual_turnover_ratio |
| P2-9 | split_days>1 时分批调仓；impact_cost_bps 建模冲击成本 |
