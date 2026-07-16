# 轮动进阶收口（P2-9 + 三项遗留）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。每个 Task 用 checkbox（`- [ ]`）追踪，完成后勾选。任务间依赖见末尾「Task Dependencies」。

**Goal:** 一次性完成 009 PRD 的 P2-9（分批调仓 + 冲击成本）、遗留#1（watcher 5 字段元数据）、遗留#2（前端结果页展示）、遗留#3（行业 PIT），闭环 008/009 全部待办。

**Architecture:**
- **P2-9（engine + 前端）**：RebalanceModel 加 `execution`（schema）→ compiler `on_daily_rebalance` 加分批冻结状态机（`_pending_split`）+ 冲击成本 `price_map` 注入 → result_serializer 加 `execution_diagnosis` → validator 加 `INVALID_EXECUTION_CONFIG` / `EXECUTION_REQUIRES_REBALANCE` → 前端 editor 加 execution 控件。
- **遗留#1（watcher）**：3 个 Tushare 接口端到端（Enum → DTO → DO → Mapper → Service → Client → InitStep → 定时 → 限流），参照 P0-0 申万行业范例；buildKlineData 注入 5 字段。
- **遗留#2（前端）**：backtest-report.js 展示三个已回传字段。
- **遗留#3（watcher）**：SwIndustryService 加 `getL1IndustriesPit` 批量接口；buildKlineData 改用 PIT。

**Tech Stack:** engine: Python 3.12 · Pydantic v2 · pytest · akquant 0.2.47（锁定）；watcher: Java 17 · Spring Boot · MyBatis · Tushare Pro API；前端: 原生 JS + Thymeleaf 模板。

## Global Constraints

- **engine 不触库**：源码禁止 `sqlite3` / `sqlalchemy` / 直连 `.db`（CLAUDE.md 硬约束）。P2-9 全部内存计算。
- **execution 仅轮动范式**：has_rebalance=False 时报 `EXECUTION_REQUIRES_REBALANCE`。
- **Pydantic `extra="forbid"`**：`ExecutionConfig` 保持 forbid。
- **engine Python 环境**：conda `stock` 环境，直接用 `D:/javaApp/miniforge/envs/stock/python.exe`（不用系统 python / venv / `conda run -n stock`）。测试命令：`cd stock-engine && "D:/javaApp/miniforge/envs/stock/python.exe" -m pytest <path> -v`（下文简写 `pytest <path> -v`）。
- **watcher 三处 DDL 同步**：`schema-mysql.sql` + `schema-sqlite.sql` + `DataInitServiceImpl` Map 必须三处同步（切 sqlite 不缺表）。
- **watcher 批量预查**：buildKlineData 注入元数据/行业时，循环外一次性建索引（参照现有 `swL1Map` / `rebalanceFlags` 模式），禁止逐 bar 逐股查表。
- **Tushare 限流**：namechange / suspend_d 各 200/min，stk_limit 500/min；幂等 delete-then-insert；分页处理（namechange 单次 5000 行、suspend_d 10000 行）。
- **DRY**：分批状态机逻辑单点实现；冲击成本计算单点实现。

## 关键架构事实（已核校代码）

1. **`on_daily_rebalance` 在 `compiler._attach_rebalance_method`（L1053）闭包内**：触发日 `_compute_target_weights` 算 plan → `order_target_weights(plan)`。分批要把 plan 存 `self._pending_split`，非触发日先检查状态机。
2. **`_is_rebalance_day`（L1255）查 bar trade_cal 标记**：分批日是非频率触发日，**不能走 `_is_rebalance_day`**（会 return False），需在 `on_daily_rebalance` 入口先检查 `_pending_split`。
3. **`order_target_weights` 支持 `price_map`**：见 `compiler.py:398-401` 的 method 分派。需核校 akquant 0.2.47 `order_target_weights` 的 `price_map` 形参是否支持（若不支持，改用 `slippage={"type":"percent","value":...}` 近似 + 文档说明限制）。
4. **`result_serializer.serialize_result`（L120-194）**：诊断字段注入点在 L170-194 区间，加 `execution_diagnosis` 旁路。
5. **`RebalanceModel` 在 `models.py`**：需定位其定义追加 `execution` 字段。
6. **watcher `buildKlineData`（BacktestServiceImpl L554）**：L571 `getLatestL1Industries`、L623 TODO、L629 `appendBasicFields` 是改动锚点。
7. **watcher P0-0 范例**：`SwIndustryServiceImpl` + `SwIndustryMapper` + `SwIndustryTask` + `IndexClassifyDTO`/`IndexMemberDTO` + `InitStep.SW_INDUSTRY` 是三接口的端到端模板。
8. **engine `_META_FIELDS`（data_adapter L27-34）已含 5 字段**：watcher 一旦下发即生效，engine 无改动（仅联调验证字段名/类型）。
9. **前端**：`strategy-editor.js` 有 collect/refill 模式；`backtest-report.js` 是结果页主 JS。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `stock-engine/services/strategy/models.py` | ExecutionConfig + RebalanceModel.execution | 新增 |
| `stock-engine/services/strategy/errors.py` | 错误码 | 加 2 个 execution 错误码 |
| `stock-engine/services/strategy/validator.py` | execution 校验 | 范围/范式校验 |
| `stock-engine/services/backtest/compiler.py` | 分批状态机 + 冲击成本 | 改 `_attach_rebalance_method` |
| `stock-engine/services/backtest/result_serializer.py` | execution_diagnosis | 加诊断字段 |
| watcher 3 接口全套（Enum/DTO/DO/Mapper/Service/Client/InitStep/Task/yml） | namechange/suspend_d/stk_limit 落库 | 新增端到端 |
| `stock-watcher/.../BacktestServiceImpl.java` | buildKlineData | 注入 5 字段 + 改 PIT |
| `stock-watcher/.../SwIndustryService(.java/Impl)` | getL1IndustriesPit | 新增批量 PIT |
| watcher schema ×2 + DataInitServiceImpl | DDL | 三处同步 |
| `stock-watcher/.../static/js/strategy-editor.js` | execution 控件 | 新增 |
| `stock-watcher/.../static/js/backtest-report.js` | 结果展示 | 加 3 字段 |
| `stock-watcher/.../templates/quant/strategies/editor.html` | execution 控件 | 新增 |
| 测试（engine 新建/扩展） | TDD | 见各 Task |

---

## Task 1: ExecutionConfig 模型 + RebalanceModel.execution + 错误码 ✅

**Files:**
- Modify: `stock-engine/services/strategy/models.py`（RebalanceModel 定义处）
- Modify: `stock-engine/services/strategy/errors.py`（ErrorCode 末尾）
- Test: `stock-engine/tests/services/strategy/test_models.py`

**Interfaces:**
- Produces: `ExecutionConfig`（`split_days: int = Field(1, ge=1, le=5)`, `impact_cost_bps: Optional[float] = Field(None, ge=0.0)`）；`RebalanceModel.execution: Optional[ExecutionConfig]`；错误码 `INVALID_EXECUTION_CONFIG` / `EXECUTION_REQUIRES_REBALANCE`。

- [ ] **Step 1: 定位 RebalanceModel**

Run: `grep -n "class RebalanceModel" stock-engine/services/strategy/models.py`
确认 RebalanceModel 定义行号与字段块位置，用于插入 `execution` 字段。

- [ ] **Step 2: 写失败测试（追加到 test_models.py）**

```python
def test_execution_config_defaults():
    from services.strategy.models import ExecutionConfig

    ec = ExecutionConfig()
    assert ec.split_days == 1
    assert ec.impact_cost_bps is None


def test_execution_config_valid():
    from services.strategy.models import ExecutionConfig

    ec = ExecutionConfig(split_days=3, impact_cost_bps=10.0)
    assert ec.split_days == 3
    assert ec.impact_cost_bps == 10.0


def test_execution_config_rejects_bad_split_days():
    import pytest
    from pydantic import ValidationError
    from services.strategy.models import ExecutionConfig

    with pytest.raises(ValidationError):
        ExecutionConfig(split_days=0)   # ge=1
    with pytest.raises(ValidationError):
        ExecutionConfig(split_days=6)   # le=5


def test_execution_config_rejects_negative_impact():
    import pytest
    from pydantic import ValidationError
    from services.strategy.models import ExecutionConfig

    with pytest.raises(ValidationError):
        ExecutionConfig(impact_cost_bps=-0.1)


def test_rebalance_model_execution_optional():
    from services.strategy.models import RebalanceModel

    rb = RebalanceModel(frequency="monthly")  # 最小合法形态（按实际必填字段校准）
    assert rb.execution is None
```

> **校准**：`RebalanceModel` 的必填字段（frequency 等）以 Step 1 grep 结果为准；测试构造按真实必填字段补齐。

- [ ] **Step 3: 运行确认失败**

Run: `pytest tests/services/strategy/test_models.py -v`
Expected: FAIL — `ImportError: cannot import name 'ExecutionConfig'`

- [ ] **Step 4: 在 models.py 的 RebalanceModel 之前插入 ExecutionConfig，并给 RebalanceModel 加 execution 字段**

在 `stock-engine/services/strategy/models.py` 的 `class RebalanceModel(BaseModel):` 之前插入：

```python
class ExecutionConfig(BaseModel):
    """分批调仓 + 冲击成本配置（PRD 009 §2 P2-9）。

    - ``split_days``：分批天数（1=一次性，3=分 3 天）；冻结法——触发日算完整 plan，
      后续 N-1 天执行增量。
    - ``impact_cost_bps``：冲击成本(bps)，按成交量线性建模
      ``impact = impact_cost_bps × 本笔成交额 / 当日成交额``；空=不建模。
    """

    model_config = ConfigDict(extra="forbid")

    split_days: int = Field(1, ge=1, le=5, description="分批天数（1=一次性）")
    impact_cost_bps: Optional[float] = Field(
        None, ge=0.0, description="冲击成本(bps)，按成交量线性建模；空=不建模"
    )
```

并在 `RebalanceModel` 字段块末尾追加：

```python
    execution: Optional["ExecutionConfig"] = Field(
        None, description="分批调仓 + 冲击成本；仅轮动范式合法"
    )
```

- [ ] **Step 5: 在 errors.py ErrorCode 末尾追加 2 个错误码**

```python
    # ----- 分批调仓 + 冲击成本（PRD 009 §2 P2-9）-----
    INVALID_EXECUTION_CONFIG = (
        "INVALID_EXECUTION_CONFIG",
        "execution.split_days 必须是 1~5 的整数，impact_cost_bps 必须 ≥ 0",
    )
    EXECUTION_REQUIRES_REBALANCE = (
        "EXECUTION_REQUIRES_REBALANCE",
        "execution 仅轮动范式（rebalance）支持，择时范式不可用",
    )
```

- [ ] **Step 6: 运行确认通过**

Run: `pytest tests/services/strategy/test_models.py -v`
Expected: PASS（新测试过，既有不回归）

- [ ] **Step 7: 提交**

```bash
git add stock-engine/services/strategy/models.py stock-engine/services/strategy/errors.py stock-engine/tests/services/strategy/test_models.py
git commit -m "feat(strategy): add ExecutionConfig + RebalanceModel.execution (P2-9)"
```

---

## Task 2: validator — execution 范围与范式校验 ✅

**Files:**
- Modify: `stock-engine/services/strategy/validator.py`（既有 RebalanceModel 校验处）
- Test: `stock-engine/tests/services/strategy/test_validator.py`

**Interfaces:**
- Consumes: Task 1 ExecutionConfig + 错误码。
- Produces: 非法 execution 在保存策略时被拦截（422）；非轮动范式报 `EXECUTION_REQUIRES_REBALANCE`。

- [ ] **Step 1: 定位 RebalanceModel / 范式判定既有入口**

Run: `grep -n "has_rebalance\|rebalance\|RebalanceModel" stock-engine/services/strategy/validator.py | head -30`
确认 validator 如何判断 has_rebalance（signals 与 rebalance 互斥的范式判定），以及 RebalanceModel 校验位置。找到既有 422 错误返回模式。

- [ ] **Step 2: 写失败测试（追加 test_validator.py）**

```python
def test_validator_rejects_execution_in_timing():
    from services.strategy.validator import validate  # 校准真实入口名

    # 择时范式（只有 signals，无 rebalance）配 execution → 报错
    cfg = {
        "universe": {"pool": "manual", "stocks": ["000001.SZ"]},
        "trading_config": {"signals": [...]},  # 校准为择时最小合法形态
        "rebalance": {"frequency": "monthly", "execution": {"split_days": 3}},
    }
    # 此处 rebalance 在场但...实际择时范式判定以 has_rebalance 为准；按 Step 1 真实范式判定构造
    errors = validate(cfg)
    codes = [e.code for e in errors]
    assert "EXECUTION_REQUIRES_REBALANCE" in codes


def test_validator_accepts_execution_in_rotation():
    from services.strategy.validator import validate

    cfg = {
        "universe": {"pool": "csi300"},
        "factor": {"method": "disabled"},
        "filter": {"conditions": [...]},  # 校准
        "portfolio": {"top_n": 10},
        "rebalance": {"frequency": "monthly", "execution": {"split_days": 3}},
    }
    errors = validate(cfg)
    assert "EXECUTION_REQUIRES_REBALANCE" not in [e.code for e in errors]
    assert "INVALID_EXECUTION_CONFIG" not in [e.code for e in errors]
```

> **校准**：`validate` 入口名、`has_rebalance` 判定逻辑、screen_config / trading_config 最小合法形态、errors 返回类型（`list[StrategyValidationError]`，字段 `.code`）。按 Step 1 结果与既有 test_validator.py 用例对齐。`split_days / impact_cost_bps` 的范围校验已由 Pydantic 在 model 解析层拦截，validator 层主要做范式（has_rebalance）校验。

- [ ] **Step 3: 运行确认失败**

Run: `pytest tests/services/strategy/test_validator.py -v`
Expected: FAIL — 非轮动范式配 execution 未被拦截。

- [ ] **Step 4: 实现 validator execution 范式校验**

在 validator 中既有范式判定（has_rebalance）处，追加：

```python
        # PRD 009 §2.2.5 execution 仅轮动范式合法
        if rebalance_execution is not None and not has_rebalance:
            errors.append(_err("rebalance.execution",
                               ErrorCode.EXECUTION_REQUIRES_REBALANCE))
```

（`rebalance_execution` 从 RebalanceModel.execution 取；`has_rebalance` 沿用既有范式判定变量；`_err` / `errors` 沿用既有辅助。）

> Pydantic 已在 model 解析层拦截 `split_days ∉ [1,5]` 与 `impact_cost_bps < 0`，validator 层无需重复；若需在 422 响应里明确 `INVALID_EXECUTION_CONFIG` code，可在 validator 加一条「model 解析失败 → INVALID_EXECUTION_CONFIG」的映射，但优先依赖 Pydantic 默认 ValidationError。

- [ ] **Step 5: 运行确认通过**

Run: `pytest tests/services/strategy/test_validator.py -v`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add stock-engine/services/strategy/validator.py stock-engine/tests/services/strategy/test_validator.py
git commit -m "feat(strategy): validate execution requires rebalance (P2-9)"
```

---

## Task 3: 冲击成本计算内核 + price_map 注入辅助 ✅

纯函数 + 辅助：`compute_impact_price(price, order_value, bar_volume, impact_cost_bps, sign)`，供分批/非分批调仓统一用。

**Files:**
- Modify: `stock-engine/services/backtest/compiler.py`（顶部或 _compute_target_weights 附近新增辅助）
- Test: `stock-engine/tests/services/backtest/test_execution_impact.py`（新建）

**Interfaces:**
- Produces: `compute_impact_price(...) -> tuple[float, float]`（返回 `(adj_price, participation)`）；`build_impact_price_map(strategy, target_weights, impact_cost_bps, trading_date) -> dict[str, float]`。

- [ ] **Step 1: 核校 order_target_weights 是否支持 price_map**

Run: `grep -n "def order_target_weights\|price_map" "D:/javaApp/miniforge/envs/stock/Lib/site-packages/akquant/strategy.py"` （或 akquant 包内 strategy.py）
确认 akquant 0.2.47 的 `order_target_weights` 是否接受 `price_map` 形参。若不支持，记录限制，冲击成本改用 `slippage={"type":"percent","value":...}` 近似（但需说明 slippage 是固定比例、无法精确表达线性；在文档与诊断里标注「近似」）。

- [ ] **Step 2: 写失败测试（新建）**

```python
import math

from services.backtest.compiler import compute_impact_price


def test_no_impact_when_bps_none():
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, None, sign=1)
    assert price == 10.0
    assert part == 0.0


def test_buy_impact_raises_price():
    # order_value=10000, bar_volume=100000 → participation=0.1；bps=10 → impact=1bps
    # adj = 10 * (1 + 1 * 10 / 10000) = 10 * 1.001 = 10.01
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, 10.0, sign=1)
    assert math.isclose(price, 10.01, abs_tol=1e-9)
    assert math.isclose(part, 0.1, abs_tol=1e-9)


def test_sell_impact_lowers_price():
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, 10.0, sign=-1)
    assert math.isclose(price, 10.0 * (1 - 0.001), abs_tol=1e-9)


def test_participation_capped():
    # order_value >> bar_volume → participation 封顶 1.0
    price, part = compute_impact_price(10.0, 1_000_000.0, 1000.0, 10.0, sign=1)
    assert part == 1.0
    assert math.isclose(price, 10.0 * (1 + 10 / 10000), abs_tol=1e-9)


def test_zero_bar_volume_capped():
    price, part = compute_impact_price(10.0, 1000.0, 0.0, 10.0, sign=1)
    assert part == 1.0  # 除零保护
```

- [ ] **Step 3: 运行确认失败**

Run: `pytest tests/services/backtest/test_execution_impact.py -v`
Expected: FAIL — `ImportError: cannot import name 'compute_impact_price'`

- [ ] **Step 4: 实现 compute_impact_price**

在 `compiler.py` 顶部辅助函数区（或 `_compute_target_weights` 之前）新增：

```python
_PARTICIPATION_CAP = 1.0


def compute_impact_price(price, order_value, bar_volume, impact_cost_bps, sign):
    """冲击成本 volume-linear 建模（PRD 009 §2.2.3）。

    :param price: 原成交价（fill price，如下一根开盘 / 当日收盘）。
    :param order_value: 本笔成交额 = 目标股数 × 价格（正数）。
    :param bar_volume: 当日成交额（volume × close，或直接用 volume 近似）。
    :param impact_cost_bps: 冲击成本(bps)；None → 不建模，返回原价。
    :param sign: +1 买入加价 / -1 卖出折价。
    :return: (adj_price, participation)；participation 为实际参与率（封顶后）。
    """
    if impact_cost_bps is None or impact_cost_bps <= 0:
        return float(price), 0.0
    if bar_volume and bar_volume > 0:
        participation = min(order_value / bar_volume, _PARTICIPATION_CAP)
    else:
        participation = _PARTICIPATION_CAP
    impact_bps = impact_cost_bps * participation
    adj = float(price) * (1 + sign * impact_bps / 10000.0)
    return adj, participation
```

- [ ] **Step 5: 实现 build_impact_price_map**

在 `compute_impact_price` 之后新增（供 on_daily_rebalance 调用，构造传给 `order_target_weights` 的 price_map）：

```python
def build_impact_price_map(strategy, target_weights, impact_cost_bps, trading_date, extra_map):
    """为目标权重构造冲击成本调整后的 price_map（PRD 009 §2.2.3）。

    对每个标的：取当日 close 与 volume（从 extra_map 或 get_history_df），
    按 target_weights 比例估算 order_value（用总权益 × 权重），算调整后价格。
    返回 ``{symbol: adj_price}``；impact_cost_bps 为 None 时返回空 dict（用默认价）。
    """
    if impact_cost_bps is None or not target_weights:
        return {}
    price_map = {}
    try:
        equity = strategy.get_portfolio_value()
    except Exception:
        return {}
    for sym, w in target_weights.items():
        try:
            # 取当日 close 与 volume（优先 extra_map，其次 get_history_df）
            close = _get_bar_close(strategy, sym, trading_date, extra_map)
            volume = _get_bar_volume(strategy, sym, trading_date, extra_map)
            if close is None or volume is None:
                continue
            order_value = abs(equity * w)
            adj, _ = compute_impact_price(close, order_value, volume * close,
                                          impact_cost_bps, sign=1 if w > 0 else -1)
            price_map[sym] = adj
        except Exception:
            continue
    return price_map
```

（`_get_bar_close` / `_get_bar_volume` 用既有 `_fetch_symbol_kline` 或 `get_history_df` 取末 bar；若已有现成取值辅助则复用。）

- [ ] **Step 6: 运行确认通过**

Run: `pytest tests/services/backtest/test_execution_impact.py -v`
Expected: PASS（5 个测试）

- [ ] **Step 7: 提交**

```bash
git add stock-engine/services/backtest/compiler.py stock-engine/tests/services/backtest/test_execution_impact.py
git commit -m "feat(backtest): impact cost volume-linear kernel (P2-9)"
```

---

## Task 4: 分批调仓冻结状态机 — on_daily_rebalance 改造 ✅

**Files:**
- Modify: `stock-engine/services/strategy/compiler.py`（`_attach_rebalance_method` 的 `on_daily_rebalance` 闭包 + 入参表）
- Modify: `stock-engine/services/strategy/compiler.py`（`_attach_rebalance_method` 调用处传 execution 参数）
- Test: `stock-engine/tests/services/backtest/test_execution_split.py`（新建）

**Interfaces:**
- Consumes: Task 1 execution 配置；Task 3 build_impact_price_map。
- Produces: `_pending_split` 状态机；分批日执行增量；新触发日打断重启；`_exec_diagnosis` 累计诊断。

- [ ] **Step 1: 核校 on_daily_rebalance 调用链与 order_target_weights price_map 支持**

Run:
- `grep -n "_attach_rebalance_method\|on_daily_rebalance\|_compute_target_weights" stock-engine/services/strategy/compiler.py`
- 确认 Task 3 Step 1 的 price_map 结论（若不支持 price_map，分批仍可做，冲击成本改 slippage 近似）。

- [ ] **Step 2: 写失败测试（新建，用 mock strategy 验证状态机）**

```python
import pandas as pd

from services.backtest.compiler import SplitState  # 状态机对象（Step 4 实现）


def test_split_state_advances_and_exhausts():
    plan = {"S1": 0.5, "S2": 0.5}
    st = SplitState(plan=plan, total_days=3)
    # 第 1 天：取 1/3 增量
    d1 = st.next_increment()
    assert st.current_day == 1
    assert d1 == {"S1": 0.5 / 3, "S2": 0.5 / 3}
    # 第 2 天
    d2 = st.next_increment()
    assert st.current_day == 2
    # 第 3 天 → 耗尽
    d3 = st.next_increment()
    assert st.current_day == 3
    assert st.exhausted
    # 再取 → None
    assert st.next_increment() is None


def test_split_state_interrupt_resets():
    st = SplitState(plan={"S1": 1.0}, total_days=3)
    st.next_increment()
    st.interrupt()  # 作废
    assert st.exhausted  # 打断后状态机清空，等价耗尽
```

> **校准**：`SplitState` 的 API（next_increment / interrupt / exhausted / current_day）由 Step 4 定义，测试按此对齐。状态机纯逻辑单测，不依赖 akquant。

- [ ] **Step 3: 运行确认失败**

Run: `pytest tests/services/backtest/test_execution_split.py -v`
Expected: FAIL — `ImportError: cannot import name 'SplitState'`

- [ ] **Step 4: 实现 SplitState 状态机**

在 `compiler.py` 新增（on_daily_rebalance 之外，模块级）：

```python
class SplitState:
    """分批调仓冻结状态机（PRD 009 §2.2.2 冻结法）。

    触发日算完整 plan 后，按 total_days 切分；每天 next_increment 返回当日增量
    （plan / total_days）；耗尽或被打断后 exhausted=True。
    """

    def __init__(self, plan: dict, total_days: int):
        self.plan = plan
        self.total_days = total_days
        self.current_day = 0
        self._exhausted = total_days <= 1

    def next_increment(self):
        if self._exhausted:
            return None
        self.current_day += 1
        inc = {sym: w / self.total_days for sym, w in self.plan.items()}
        if self.current_day >= self.total_days:
            self._exhausted = True
        return inc

    def interrupt(self):
        """新触发日打断：作废剩余分批。"""
        self._exhausted = True

    @property
    def exhausted(self) -> bool:
        return self._exhausted

    @property
    def remaining_days(self) -> int:
        return max(0, self.total_days - self.current_day)
```

- [ ] **Step 5: 改造 on_daily_rebalance 接入状态机与 price_map**

在 `_attach_rebalance_method` 入参表追加：

```python
    split_days: int = 1,
    impact_cost_bps: Optional[float] = None,
```

把 `on_daily_rebalance` 闭包改造（伪代码，按既有结构嵌入）：

```python
    def on_daily_rebalance(self, trading_date, timestamp):
        is_trigger = _is_rebalance_day(trading_date, frequency, trigger, extra_map)

        # 分批日（非触发日但有未完成分批）：执行增量
        pending = getattr(self, "_pending_split", None)
        if not is_trigger and pending is not None and not pending.exhausted:
            inc = pending.next_increment()
            self._exec_diagnosis = _bump_exec(self, "split_day")
            if inc:
                inc = _filter_limit_up_today(inc, extra_map, trading_date)  # 分批日涨停跳过
                if inc:
                    price_map = build_impact_price_map(self, inc, impact_cost_bps, trading_date, extra_map)
                    try:
                        self.order_target_weights(inc, liquidate_unmentioned=False, **({"price_map": price_map} if price_map else {}))
                    except Exception:
                        logger.exception("分批调仓增量下单失败")
            if pending.exhausted:
                self._pending_split = None
            return

        if not is_trigger:
            return

        # 触发日：算完整 plan
        # ...（既有 _compute_target_weights 逻辑）...
        target_weights, diagnosis = _compute_target_weights(...)

        # 分批配置生效
        if split_days > 1 and target_weights:
            # 撞未完成分批 → 打断
            old = getattr(self, "_pending_split", None)
            if old is not None and not old.exhausted:
                logger.info("分批被打断：原 plan 剩 %d 天未执行", old.remaining_days)
                self._exec_diagnosis = _bump_exec(self, "interrupted")
                old.interrupt()
            state = SplitState(plan=target_weights, total_days=split_days)
            inc = state.next_increment()
            self._pending_split = state
            self._exec_diagnosis = _bump_exec(self, "split_day")
            if inc:
                inc = _filter_limit_up_today(inc, extra_map, trading_date)
                if inc:
                    price_map = build_impact_price_map(self, inc, impact_cost_bps, trading_date, extra_map)
                    try:
                        self.order_target_weights(inc, liquidate_unmentioned=liquidate_unmentioned, **({"price_map": price_map} if price_map else {}))
                    except Exception:
                        logger.exception("分批调仓首份下单失败")
            return

        # split_days == 1：现状一次性（带冲击成本 price_map）
        price_map = build_impact_price_map(self, target_weights, impact_cost_bps, trading_date, extra_map)
        try:
            self.order_target_weights(target_weights, liquidate_unmentioned=liquidate_unmentioned, **({"price_map": price_map} if price_map else {}))
        except Exception:
            logger.exception("on_daily_rebalance 自托管权重调仓失败")
```

（`_bump_exec` / `_filter_limit_up_today` 为新增辅助：`_bump_exec` 累计 `splits_completed/splits_interrupted/total_impact_cost/avg_participation`；`_filter_limit_up_today` 用 extra_map 当日 is_limit_up 过滤买入标的。具体实现按既有诊断模式扩展。）

> **price_map 限制**：若 Task 3 Step 1 核校 akquant 0.2.47 的 `order_target_weights` 不支持 `price_map`，则改用 `slippage={"type":"percent","value": impact_cost_bps/10000}`（固定比例近似，非精确线性）并在 `_exec_diagnosis` 标注 `impact_approx=True`。

- [ ] **Step 6: 改造 _attach_rebalance_method 调用处传 execution 参数**

定位调用 `_attach_rebalance_method(...)` 处（约 L962-988），从 RebalanceModel.execution 解析 split_days / impact_cost_bps 透传：

```python
    exec_cfg = getattr(rebalance_model, "execution", None) if rebalance_model else None
    rb_split_days = exec_cfg.split_days if exec_cfg else 1
    rb_impact_cost_bps = exec_cfg.impact_cost_bps if exec_cfg else None
    # ... 透传给 _attach_rebalance_method(split_days=rb_split_days, impact_cost_bps=rb_impact_cost_bps, ...)
```

- [ ] **Step 7: 运行确认通过**

Run: `pytest tests/services/backtest/test_execution_split.py tests/services/backtest/ -v`
Expected: PASS（状态机单测过；既有 rebalance 测试不回归）

- [ ] **Step 8: 提交**

```bash
git add stock-engine/services/strategy/compiler.py stock-engine/tests/services/backtest/test_execution_split.py
git commit -m "feat(backtest): split rebalance frozen state machine + impact (P2-9)"
```

---

## Task 5: result_serializer 加 execution_diagnosis ✅

**Files:**
- Modify: `stock-engine/services/backtest/result_serializer.py`（L170-194 诊断注入区）
- Test: `stock-engine/tests/services/backtest/test_result_serializer.py`（若不存在则新建）

**Interfaces:**
- Produces: `serialize_result` 输出含 `execution_diagnosis`（取自 `result.strategy._exec_diagnosis`，无则 None）。

- [ ] **Step 1: 写失败测试**

```python
def test_serialize_execution_diagnosis_extracted():
    from services.backtest.result_serializer import serialize_result

    class FakeDiag:
        _exec_diagnosis = {"splits_completed": 3, "splits_interrupted": 0,
                           "total_impact_cost": 100.0, "avg_participation": 0.01}

    class FakeResult:
        strategy = FakeDiag()
        # ... 其余 serialize_result 必需的最小属性（metrics_df / equity_curve_daily 等）

    out = serialize_result(FakeResult())
    assert out["execution_diagnosis"]["splits_completed"] == 3
```

> **校准**：`serialize_result` 的真实入参与 FakeResult 必需属性（参考既有 test_result_serializer 或函数签名 L120-160）。`_exec_diagnosis` 字段名与 Task 4 `_bump_exec` 累计结构对齐。

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/services/backtest/test_result_serializer.py -v`
Expected: FAIL — `execution_diagnosis` 不在输出。

- [ ] **Step 3: 实现 execution_diagnosis 提取**

在 `serialize_result`（L170-194 区间，`rebalance_diagnosis` 注入附近）追加：

```python
    # PRD 009 §2.2.4 execution_diagnosis（P2-9）
    execution_diagnosis: Optional[dict] = None
    try:
        raw_exec = getattr(getattr(result, "strategy", None), "_exec_diagnosis", None)
        if raw_exec:
            execution_diagnosis = dict(raw_exec)
    except Exception:
        pass
    # ... 在返回 dict 里加：
    # "execution_diagnosis": execution_diagnosis,
```

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/services/backtest/test_result_serializer.py -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/backtest/result_serializer.py stock-engine/tests/services/backtest/test_result_serializer.py
git commit -m "feat(backtest): serialize execution_diagnosis (P2-9)"
```

---

## Task 6: P2-9 端到端集成测试 ✅

**Files:**
- Test: `stock-engine/tests/services/backtest/test_execution_e2e.py`（新建）

- [ ] **Step 1: 写集成测试（构造 kline + execution 配置跑选股+分批路径）**

```python
import pandas as pd

from services.backtest.rebalance_engine import RebalanceEngine
# 用 compiler 编译策略 + akquant 跑短回测验证分批 trades 分布在 3 天

def test_e2e_split_days_3_distributes_trades(monkeypatch):
    # 屏蔽 PIT watcher 过滤
    monkeypatch.setattr(RebalanceEngine, "_apply_universe_filter",
                        staticmethod(lambda cfg, km, ts, wc: km))
    # 构造 6 日 kline + execution(split_days=3)，跑回测
    # 断言：trades_df 的 entry_time 分布在 3 个不同交易日
    ...

def test_e2e_impact_cost_applied(monkeypatch):
    # execution(impact_cost_bps=10) vs None，断言同标的开仓价更高
    ...
```

> **校准**：参考 `tests/services/backtest/test_rebalance_transform.py` 的 e2e 模式（kline_map + monkeypatch _apply_universe_filter）。具体回测调用以 `runner.run_backtest` 或 akquant `run_backtest` 真实入口为准。

- [ ] **Step 2: 运行测试 + 全量回归**

Run: `pytest tests/services/strategy tests/services/backtest -v`
Expected: PASS（execution e2e 过；既有 strategy/backtest 不回归）

- [ ] **Step 3: 提交**

```bash
git add stock-engine/tests/services/backtest/test_execution_e2e.py
git commit -m "test(backtest): execution split + impact e2e (P2-9)"
```

---

## Task 7: 前端 — strategy-editor execution 控件（P2-9）✅

**Files:**
- Modify: `stock-watcher/src/main/resources/static/js/strategy-editor.js`
- Modify: `stock-watcher/src/main/resources/templates/quant/strategies/editor.html`

- [ ] **Step 1: 定位 Rebalance 配置区与 collect/refill 模式**

Run: `grep -n "rebalance\|frequency\|trigger" stock-watcher/src/main/resources/static/js/strategy-editor.js | head -30`
确认 Rebalance 配置区的 HTML 渲染位置与 collect（DOM→config）/ refill（config→DOM）函数。

- [ ] **Step 2: editor.html 加 execution 控件**

在 Rebalance 层（Tab 对应区域，frequency/trigger 附近）加：

```html
<div class="execution-config" data-paradigm="rebalance">
  <label>分批天数 split_days (1-5)</label>
  <input type="number" id="rb-exec-split-days" min="1" max="5" value="1">
  <label>冲击成本 bps（可选）</label>
  <input type="number" id="rb-exec-impact-bps" min="0" step="0.1" placeholder="留空=不建模">
</div>
```

- [ ] **Step 3: strategy-editor.js collect/refill 透传 execution**

```javascript
// collect: DOM → config.rebalance.execution
function collectRebalance() {
  const split = parseInt(document.getElementById('rb-exec-split-days').value) || 1;
  const impact = document.getElementById('rb-exec-impact-bps').value;
  const rb = { frequency: ..., trigger: ... };
  if (split > 1 || impact !== '') {
    rb.execution = { split_days: split };
    if (impact !== '') rb.execution.impact_cost_bps = parseFloat(impact);
  }
  return rb;
}

// refill: config.rebalance.execution → DOM
function refillRebalance(rb) {
  const exec = rb.execution || {};
  document.getElementById('rb-exec-split-days').value = exec.split_days || 1;
  document.getElementById('rb-exec-impact-bps').value =
    exec.impact_cost_bps != null ? exec.impact_cost_bps : '';
}
```

- [ ] **Step 4: 手动联调（浏览器开 editor，配 execution 保存后 reload 看 refill）**

- [ ] **Step 5: 提交**

```bash
git add stock-watcher/src/main/resources/static/js/strategy-editor.js stock-watcher/src/main/resources/templates/quant/strategies/editor.html
git commit -m "feat(editor): execution controls for split + impact (P2-9)"
```

---

## Task 8: watcher — TushareApiEnum + InitStep 注册三接口 ✅

**Files:**
- Modify: `stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java`
- Modify: `stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java`

- [ ] **Step 1: 定位既有 Enum 注册模式**

Run: `grep -n "INDEX_CLASSIFY\|INDEX_MEMBER\|SW_INDUSTRY" stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java`

- [ ] **Step 2: TushareApiEnum 加三项**

```java
NAMECHANGE("namechange", "股票更名历史", "https://tushare.pro/document/2?doc_id=160"),
SUSPEND_D("suspend_d", "停复牌信息", "https://tushare.pro/document/2?doc_id=161"),
STK_LIMIT("stk_limit", "涨跌停价", "https://tushare.pro/document/2?doc_id=183"),
```

- [ ] **Step 3: InitStep 加三项**

```java
NAMECHANGE,
SUSPEND_D,
STK_LIMIT,
```

- [ ] **Step 4: 提交**

```bash
git add stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java
git commit -m "feat(watcher): register namechange/suspend_d/stk_limit enum+step (legacy#1)"
```

---

## Task 9: watcher — DDL 三表（三处同步）✅

**Files:**
- Modify: `stock-watcher/src/main/resources/schema-mysql.sql`
- Modify: `stock-watcher/src/main/resources/schema-sqlite.sql`
- Modify: `stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`（表 Map）

- [ ] **Step 1: schema-mysql.sql 加三表**（见 PRD §3.2.2 DDL，含 PRIMARY KEY + INDEX）

- [ ] **Step 2: schema-sqlite.sql 加三表**（sqlite 语法：去 ENGINE，INDEX 语法适配）

- [ ] **Step 3: DataInitServiceImpl 表 Map 注册三表**

Run: `grep -n "sw_industry\|sw_industry_member" stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`
按既有表 Map 模式追加三表。

- [ ] **Step 4: 提交**

```bash
git add stock-watcher/src/main/resources/schema-mysql.sql stock-watcher/src/main/resources/schema-sqlite.sql stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java
git commit -m "feat(watcher): DDL for namechange/suspend_d/stk_limit (legacy#1)"
```

---

## Task 10: watcher — namechange 端到端（DTO/DO/Mapper/Service/Client）✅

**Files:**
- New: `dto/tushare/NamechangeDTO.java`, `NamechangeQueryDTO.java`
- New: `model/StockNamechangeDO.java`
- New: `mapper/StockNamechangeMapper.java`
- New: `service/StockNamechangeService.java` + `service/impl/StockNamechangeServiceImpl.java`
- Modify: `client/TushareClient.java`（加 namechange 调用）

> **参照范例**：`SwIndustryServiceImpl` + `IndexMemberDTO` + `SwIndustryMemberMapper` 全套（P0-0）。

- [ ] **Step 1: DTO/DO/Mapper/Service 套用 P0-0 范例实现**

关键字段：ts_code / name / start_date / end_date / change_reason。Mapper 含 `selectNameAt(tsCode, tradeDate)`（point-in-time：start_date <= td AND (end_date IS NULL OR end_date >= td) ORDER BY start_date DESC LIMIT 1）。

- [ ] **Step 2: TushareClient 加 namechange 调用（分页，单次 5000 行）**

- [ ] **Step 3: Service 全量初始化（幂等 delete-then-insert）+ 每日增量**

- [ ] **Step 4: 提交**

```bash
git add stock-watcher/src/main/java/com/arthur/stock/{dto/tushare/Namechange*,model/StockNamechangeDO,mapper/StockNamechangeMapper,service/StockNamechangeService,service/impl/StockNamechangeServiceImpl,client/TushareClient}.java
git commit -m "feat(watcher): namechange end-to-end (legacy#1)"
```

---

## Task 11: watcher — suspend_d 端到端 ✅

**Files:** 同 Task 10 模式（`SuspendD*` / `StockSuspendDDO` / `StockSuspendDMapper` / `StockSuspendDService(Impl)`）

- [ ] **Step 1: 套用范例**（关键字段：ts_code / trade_date / susp_reason / resump_date；单次 10000 行分页；Mapper `isSuspendedAt(tsCode, tradeDate)` + 区间批量 `selectByRange`）

- [ ] **Step 2: TushareClient 加 suspendD 调用**

- [ ] **Step 3: 提交**

```bash
git add stock-watcher/...
git commit -m "feat(watcher): suspend_d end-to-end (legacy#1)"
```

---

## Task 12: watcher — stk_limit 端到端 ✅

**Files:** 同 Task 10 模式（`StkLimit*` / `StockStkLimitDO` / `StockStkLimitMapper` / `StockStkLimitService(Impl)`）

- [ ] **Step 1: 套用范例**（关键字段：ts_code / trade_date / pre_close / up_limit / down_limit；单次不限；Mapper 区间批量 `selectLimitByRange(tsCode, startDate, endDate)`）

- [ ] **Step 2: TushareClient 加 stkLimit 调用**

- [ ] **Step 3: 提交**

```bash
git add stock-watcher/...
git commit -m "feat(watcher): stk_limit end-to-end (legacy#1)"
```

---

## Task 13: watcher — 定时任务 + application.yml 限流 ✅

**Files:**
- New: `task/StockNamechangeTask.java`, `StockSuspendDTask.java`, `StockStkLimitTask.java`
- Modify: `stock-watcher/src/main/resources/application.yml`（三接口限流）

- [ ] **Step 1: 三个 Task 每日增量 cron**（参照 `SwIndustryTask` / `DailyUpdateTask`）

- [ ] **Step 2: application.yml 加限流**

```yaml
tushare:
  rate-limit:
    namechange: 200
    suspend_d: 200
    stk_limit: 500
```

- [ ] **Step 3: DataInitController 暴露三步骤触发入口（若需）**

- [ ] **Step 4: 提交**

```bash
git add stock-watcher/src/main/java/com/arthur/stock/task/Stock*Task.java stock-watcher/src/main/resources/application.yml
git commit -m "feat(watcher): schedule + rate-limit for 3 apis (legacy#1)"
```

---

## Task 14: watcher — buildKlineData 注入 5 元数据字段 ✅

**Files:**
- Modify: `stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java`（buildKlineData L554-640）

- [ ] **Step 1: 循环外批量预查建索引**

```java
// 在 buildKlineData 循环外（L571 swL1Map 附近）加：
Map<String, List<StockNamechangeDO>> namechangeMap = stockNamechangeService.listByTsCodes(symbols);
Map<String, Set<String>> suspendSet = stockSuspendDService.listSuspendDates(symbols, startDate, endDate);
Map<String, Map<String, StockStkLimitDO>> limitMap = stockStkLimitService.listByRange(symbols, startDate, endDate);
Map<String, String> listDateMap = stockBasicService.listDateMap(symbols);  // stock_basic.list_date
```

- [ ] **Step 2: 循环内逐 bar 注入（替换 L623 TODO）**

```java
String td = q.getTradeDate();
String name = activeNameAt(namechangeMap.get(code), td);
bar.put("is_st", (name != null && name.contains("ST")) ? "1" : "0");
bar.put("is_suspended", suspendSet.getOrDefault(code, Set.of()).contains(td) ? "1" : "0");
StockStkLimitDO lim = limitMap.getOrDefault(code, Map.of()).get(td);
if (lim != null) {
    double close = toDouble(q.getClose());
    bar.put("is_limit_up",   close >= lim.getUpLimit()   ? "1" : "0");
    bar.put("is_limit_down", close <= lim.getDownLimit() ? "1" : "0");
}
bar.put("list_date", listDateMap.get(code));
```

（`activeNameAt(list, td)` 辅助：取 start_date <= td 且 (end_date 空 或 >= td) 的最后一条 name。）

- [ ] **Step 3: 联调 — 跑一次小回测，检查 bar JSON 含 5 字段且逐日准确**

- [ ] **Step 4: 提交**

```bash
git add stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java
git commit -m "feat(watcher): inject 5 metadata fields in buildKlineData (legacy#1)"
```

---

## Task 15: watcher — 行业 PIT（SwIndustryService.getL1IndustriesPit + buildKlineData 改用）✅

**Files:**
- Modify: `stock-watcher/src/main/java/com/arthur/stock/service/SwIndustryService.java`
- Modify: `stock-watcher/src/main/java/com/arthur/stock/service/impl/SwIndustryServiceImpl.java`
- Modify: `stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java`（L571）

- [ ] **Step 1: SwIndustryService 接口加批量 PIT 方法**

```java
/**
 * 批量 point-in-time：取多只股票在区间内每日生效的一级行业。
 * @return key=tsCode, value={trade_date(yyyyMMdd) -> index_code}
 */
Map<String, Map<String, String>> getL1IndustriesPit(List<String> tsCodes, String startDate, String endDate);
```

- [ ] **Step 2: Impl 实现（一次性查区间 sw_industry_member，按 ts_code + update_date forward-fill）**

```java
@Override
public Map<String, Map<String, String>> getL1IndustriesPit(List<String> tsCodes, String startDate, String endDate) {
    // 1. 一次性查全部 ts_code 在 [startDate, endDate]（或全部历史）的 member 记录
    // 2. 按 ts_code 分组，按 update_date 升序排序
    // 3. 对区间内每个 trade_date，forward-fill：取 <= trade_date 的最新 update_date 的 index_code
    // 返回 Map<tsCode, Map<tradeDate, indexCode>>
}
```

- [ ] **Step 3: buildKlineData 改用 PIT（替换 L571 getLatestL1Industries）**

```java
Map<String, Map<String, String>> swL1PitMap = swIndustryService.getL1IndustriesPit(symbols, startDate, endDate);
// 循环内：
Map<String, String> perDay = swL1PitMap.get(code);
if (perDay != null) {
    String swL1 = perDay.get(q.getTradeDate());
    if (swL1 != null) bar.put("sw_industry_l1", swL1);
}
```

- [ ] **Step 4: 联调 — 跨年回测验证换行业标的按当时归属**

- [ ] **Step 5: 提交**

```bash
git add stock-watcher/src/main/java/com/arthur/stock/service/SwIndustryService.java stock-watcher/src/main/java/com/arthur/stock/service/impl/SwIndustryServiceImpl.java stock-watcher/src/main/java/com/arthur/stock/service/impl/BacktestServiceImpl.java
git commit -m "feat(watcher): industry point-in-time in buildKlineData (legacy#3)"
```

---

## Task 16: 前端 — 回测结果页展示三字段（遗留#2）✅

**Files:**
- Modify: `stock-watcher/src/main/resources/static/js/backtest-report.js`
- Modify: `stock-watcher/src/main/resources/templates/quant/backtest/report.html`（或对应模板）

- [ ] **Step 1: 定位结果页 metrics 渲染区**

Run: `grep -n "sharpe_ratio\|max_drawdown\|metrics" stock-watcher/src/main/resources/static/js/backtest-report.js | head -20`

- [ ] **Step 2: 加 annual_turnover_ratio 行**

```javascript
// metrics 表加一行
const turnover = result.metrics?.annual_turnover_ratio;
if (turnover != null) {
  renderMetricRow('年化换手率', turnover.toFixed(2));
}
```

- [ ] **Step 3: 加 rebalance_diagnosis 警示卡片**

```javascript
const diag = result.rebalance_diagnosis;
if (diag) {
  const bought = diag.actually_bought || 0;
  const selected = diag.selected_count || 0;
  const highlight = bought < selected ? 'warn' : '';
  renderDiagnosisCard({
    title: '调仓诊断',
    text: `实际成交 ${bought}/${selected} 只，资金不足拒单 ${diag.rejected_insufficient||0} 只，涨停拒买 ${diag.rejected_limit_up||0} 只，实际仓位 ${(diag.actual_invest_ratio*100||0).toFixed(1)}%`,
    highlight,
  });
}
```

- [ ] **Step 4: 加 effective_config.warmup_period 摘要行**

```javascript
const warmup = result.effective_config?.warmup_period;
if (warmup != null) {
  const src = result.effective_config?.warmup_source || 'auto';
  const label = src === 'user_override' ? `系统建议 warmup: ${warmup.value}（用户设置）` : `系统建议 warmup: ${warmup.value}（基于因子窗口自动推断）`;
  renderConfigSummary(label);
}
```

> **校准**：`rebalance_diagnosis` / `effective_config` 的真实字段层级与命名，以 `result_serializer.py` 实际输出为准（grep `_extract_rebalance_diagnosis` 与 `effective_config` 构造处）。

- [ ] **Step 5: 联调 — 跑回测看结果页展示**

- [ ] **Step 6: 提交**

```bash
git add stock-watcher/src/main/resources/static/js/backtest-report.js stock-watcher/src/main/resources/templates/quant/backtest/report.html
git commit -m "feat(ui): show rebalance_diagnosis/warmup/turnover (legacy#2)"
```

---

## Task 17: 全量回归 + 联调验收 ✅

- [ ] **Step 1: engine 全量测试**

Run: `cd stock-engine && "D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/ -v`
Expected: 全 PASS

- [ ] **Step 2: watcher 全量编译 + 启动**

Run: `cd stock-watcher && mvn clean compile`（或既有构建命令）
启动后触发 DataInit 三步骤，确认三表落库。

- [ ] **Step 3: 端到端联调**

- 配 execution(split_days=3, impact_cost_bps=10) 跑回测 → 看 trades 分布 3 天 + execution_diagnosis 回传 + 前端展示。
- 配 exclude_st=true + 跨年 universe 跑回测 → 看「剔除 ST」日志 + 行业 PIT 生效。
- 结果页三字段展示。

- [ ] **Step 4: 更新 spec.md / 文档（如 PRD §1.2.2 勘误等）**

---

## Task Dependencies

- Task 2 depends on Task 1（错误码 + model）
- Task 3 独立（冲击成本内核）
- Task 4 depends on Task 1, Task 3（execution 配置 + impact 内核）
- Task 5 depends on Task 4（_exec_diagnosis 由 Task 4 累计）
- Task 6 depends on Task 1-5（e2e）
- Task 7 depends on Task 1（前端控 execution 字段）
- Task 9 depends on Task 8（Enum/Step 注册）
- Task 10/11/12 互相独立，depends on Task 8, Task 9
- Task 13 depends on Task 10/11/12（定时调 Service）
- Task 14 depends on Task 10/11/12（buildKlineData 调 Service/Mapper）
- Task 15 独立（SwIndustryService 扩展）
- Task 16 depends on Task 5（execution_diagnosis 已回传）+ 既有的 rebalance_diagnosis/warmup/turnover（已就绪）
- Task 17 depends on all

## Parallelizable

- engine 侧（Task 1-7）与 watcher 侧（Task 8-15）与 前端结果页（Task 16）三条线可并行。
- watcher 三接口（Task 10/11/12）可并行。
