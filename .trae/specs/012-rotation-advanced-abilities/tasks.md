# P1-6 FactorNode transform（滚动窗口聚合）实现计划 — Engine 核心

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让轮动选股条件（`filter.conditions`）支持对因子值做滚动窗口聚合（ma/std/pct_change/max/min），从而能表达「过去 20 日 PE 均值 < 30」「5 日涨幅 > 10%」「20 日波动率 < 0.3」等条件。

**Architecture:** engine **即时聚合**（非 factor_snapshot 预计算）。因子值序列已在内存（技术面来自 `compute_single` 全序列；基本面来自候选 `extra` 逐 bar），transform 只是把「取末值 `[-1]`」换成「取窗口末 N 日序列再聚合」。改动集中在**共享的选股条件预计算链路**（`services/screener/factor_precompute.py` + `services/screener/engine.py`），轮动回测与选股中心同享；择时范式（`TradingConditionEngine`）不在范围。聚合逻辑抽成共享内核 `aggregate_series`，留择时扩展点。

**Tech Stack:** Python 3.12 · Pydantic v2 · pytest · akquant 0.2.47（锁定）

## Global Constraints

- **engine 不触库**：源码禁止 `sqlite3` / `sqlalchemy` / 直连 `.db`（CLAUDE.md 硬约束）。本计划全部内存计算。
- **transform 仅 `filter.conditions`**：不出现在 `factor.weights`（ranking）；不在择时 `TradingConditionEngine`。违反报错。
- **机制=即时聚合**：不新建/不改 `factor_snapshot` 表，不在回测路径加 HTTP 查表。
- **Pydantic `extra="forbid"`**：config 层 `FactorNode` 保持 forbid；新增字段必须显式声明（runtime `ExpressionNode` 默认 ignore extra，故必须加字段）。
- **Python 环境**：stock-engine 在 conda `stock` 环境（直接用 env python：`D:/javaApp/miniforge/envs/stock/python.exe`）。**不要用** 系统 python、`stock-engine/venv/`、或 `conda run -n stock`（后者对多行/测试不可靠）。运行测试：`cd stock-engine && "D:/javaApp/miniforge/envs/stock/python.exe" -m pytest <path> -v`（下文简写 `pytest <path> -v`，实际须用上述 env python）。
- **DRY**：transform 聚合只有一份实现（`aggregate_series`），技术面/基本面/未来择时复用。

## 关键架构事实（已核校代码，决定改动点）

1. **两套 FactorNode**：`services/strategy/models.py:FactorNode`（config 层，snake_case `output_index`）与 `models/schemas/condition.py:ExpressionNode`（runtime 层，camelCase `outputIndex`，带 `.kind`）。策略 JSON → config FactorNode → `_conditions_to_dict` → dict → runtime ExpressionNode。transform 字段**两套都要加**。
2. **条件因子走 `precompute_factors`（共享）**，不走 `rebalance_engine._compute_one_factor`（后者只算 ranking 额外因子）。`_compute_factors` 调 `precompute_factors(conditions_dict, candidates)` 算 conditions 因子。→ P1-6 主改 `precompute_factors`。
3. `precompute_factors` 技术面：`kline_to_arrays(ohlcv_history)` → `compute_single` → 取 `[-1]`；基本面：从 `candidate["fundamentals"]` 取快照。rebalance 候选的逐 bar 基本面在 `candidate["extra"]`（`{trade_date_str: {pe_ttm,...}}`）。
4. 条件求值查表：`ConditionEngine._resolve_factor` 用 `factor_signature(key, params, outputIndex)` 在 `context.factor_values` 查（基本面先查 `context.fundamentals`）。transform 需用 transform 感知 key，存取两端一致。
5. `factor_signature` 定义在 `services/screener/engine.py:38`。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `stock-engine/services/strategy/models.py` | config 层 FactorNode + TransformConfig | 新增 TransformConfig；FactorNode 加 `transform` |
| `stock-engine/models/schemas/condition.py` | runtime 层 ExpressionNode | 加 `transform: Optional[dict]` 字段 |
| `stock-engine/services/strategy/errors.py` | 错误码 | 加 3 个 transform 错误码 |
| `stock-engine/services/shared/factor_pipeline.py` | 共享内核 | 新增 `aggregate_series(series, transform)` |
| `stock-engine/services/screener/engine.py` | factor_signature + ConditionEngine | factor_signature 加 transform 形参；`_resolve_factor` transform 查表 |
| `stock-engine/services/screener/factor_precompute.py` | 条件因子预计算 | `collect_factor_refs` 捕获 transform；`precompute_factors` 应用 transform（技术面+基本面） |
| `stock-engine/services/strategy/validator.py` | 结构校验 | transform 范围/枚举/window 校验 |
| 测试（新建/扩展） | TDD | 见各 Task |

---

## Task 1: TransformConfig 模型 + FactorNode.transform（config 层）+ 错误码

**Files:**
- Modify: `stock-engine/services/strategy/models.py:45-63`（FactorNode）
- Modify: `stock-engine/services/strategy/errors.py`（ErrorCode 末尾）
- Test: `stock-engine/tests/services/strategy/test_models.py`

**Interfaces:**
- Produces: `TransformConfig`（`type: Literal["ma","std","pct_change","max","min"]`, `window: int ≥1`）；`FactorNode.transform: Optional[TransformConfig]`；错误码 `INVALID_TRANSFORM_TYPE` / `INVALID_TRANSFORM_WINDOW` / `TRANSFORM_NOT_ALLOWED_IN_RANKING`。

- [ ] **Step 1: 写失败测试（追加到 test_models.py 末尾）**

```python
def test_transform_config_valid():
    from services.strategy.models import FactorNode, TransformConfig

    node = FactorNode(
        factor="PE_TTM",
        transform=TransformConfig(type="ma", window=20),
    )
    assert node.transform is not None
    assert node.transform.type == "ma"
    assert node.transform.window == 20


def test_transform_config_rejects_bad_type():
    import pytest
    from pydantic import ValidationError
    from services.strategy.models import TransformConfig

    with pytest.raises(ValidationError):
        TransformConfig(type="median", window=20)  # type 不在枚举


def test_transform_config_rejects_zero_window():
    import pytest
    from pydantic import ValidationError
    from services.strategy.models import TransformConfig

    with pytest.raises(ValidationError):
        TransformConfig(type="ma", window=0)  # window ≥ 1


def test_factornode_transform_optional():
    from services.strategy.models import FactorNode

    node = FactorNode(factor="RSI")  # 不带 transform，等价现状
    assert node.transform is None
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pytest tests/services/strategy/test_models.py -v`
Expected: FAIL — `ImportError: cannot import name 'TransformConfig'`

- [ ] **Step 3: 在 models.py 的 FactorNode 之前插入 TransformConfig，并给 FactorNode 加 transform 字段**

在 `stock-engine/services/strategy/models.py` 的 `class FactorNode(BaseModel):` 之前插入：

```python
class TransformConfig(BaseModel):
    """对因子值再做滚动窗口聚合（仅选股条件 filter.conditions 用）。

    Schema PRD 009 §1。聚合在 engine 即时完成（非预计算），取候选窗口末 N 日因子序列：
    - ma=均值；std=样本标准差(ddof=1)；pct_change=(末-首)/首；
    - max=最大；min=最小。窗口不足返回 NaN。
    """

    model_config = ConfigDict(extra="forbid")

    type: Literal["ma", "std", "pct_change", "max", "min"] = Field(
        ..., description="聚合类型"
    )
    window: int = Field(..., ge=1, description="窗口天数（交易日，≥1）")
```

并把 FactorNode 的字段块（`output_index` 之后）追加：

```python
    transform: Optional["TransformConfig"] = Field(
        None, description="对因子值再做滚动窗口聚合；仅 filter.conditions 生效"
    )
```

（`Literal` 已在文件顶部 `from typing import ... Literal` 导入；若否则补导入。`TransformConfig` 定义在 FactorNode 之前，无需前向引用引号也可，保留引号无害。）

- [ ] **Step 4: 在 errors.py 的 ErrorCode 末尾（`INVALID_OP` 之后）追加 3 个错误码**

```python
    # ----- 因子滚动窗口聚合 transform（PRD 009 §1 P1-6）-----
    INVALID_TRANSFORM_TYPE = (
        "INVALID_TRANSFORM_TYPE",
        "transform.type 必须是 ma/std/pct_change/max/min 之一",
    )
    INVALID_TRANSFORM_WINDOW = (
        "INVALID_TRANSFORM_WINDOW",
        "transform.window 必须是 1~60 的整数",
    )
    TRANSFORM_NOT_ALLOWED_IN_RANKING = (
        "TRANSFORM_NOT_ALLOWED_IN_RANKING",
        "transform 仅支持 filter.conditions（选股条件），不支持 factor.weights（ranking 打分）",
    )
```

- [ ] **Step 5: 运行测试确认通过**

Run: `pytest tests/services/strategy/test_models.py -v`
Expected: PASS（4 个新测试全过，既有测试不回归）

- [ ] **Step 6: 提交**

```bash
git add stock-engine/services/strategy/models.py stock-engine/services/strategy/errors.py stock-engine/tests/services/strategy/test_models.py
git commit -m "feat(strategy): add TransformConfig + FactorNode.transform (P1-6)"
```

---

## Task 2: ExpressionNode.transform（runtime 层）

runtime `ExpressionNode`（`models/schemas/condition.py`）默认 ignore extra，dict 里的 `transform` 会被丢弃，必须显式加字段。

**Files:**
- Modify: `stock-engine/models/schemas/condition.py:36-101`（ExpressionNode）
- Test: `stock-engine/tests/test_screener/test_engine.py`

**Interfaces:**
- Consumes: Task 1 的 transform dict 形态 `{"type","window"}`。
- Produces: `ExpressionNode.transform: Optional[dict]`（runtime 用 dict，松散；聚合时由 `aggregate_series` 校验）。

- [ ] **Step 1: 写失败测试（追加到 test_engine.py）**

```python
def test_expression_node_accepts_transform():
    from models.schemas.condition import ExpressionNode

    node = ExpressionNode(factor="PE_TTM", transform={"type": "ma", "window": 20})
    assert node.kind == "factor"
    assert node.transform == {"type": "ma", "window": 20}


def test_expression_node_transform_defaults_none():
    from models.schemas.condition import ExpressionNode

    node = ExpressionNode(factor="RSI", params={"timeperiod": 14})
    assert node.transform is None
```

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_engine.py::test_expression_node_accepts_transform -v`
Expected: FAIL — `transform == None`（字段被忽略）或 AttributeError。

- [ ] **Step 3: 给 ExpressionNode 加 transform 字段**

在 `stock-engine/models/schemas/condition.py` 的 `ExpressionNode` 中，`outputIndex` 字段之后插入：

```python
    # 形态 2 扩展：因子滚动窗口聚合（PRD 009 §1 P1-6），仅选股条件用
    transform: Optional[dict] = Field(
        None, description="因子滚动窗口聚合 {type,window}，仅 filter.conditions",
        examples=[{"type": "ma", "window": 20}],
    )
```

（`Optional` 已导入；`dict` 作松散承载，校验在 aggregate_series / validator。）

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_engine.py -v`
Expected: PASS（新测试过，既有 `test_expression_node_four_forms` 等不回归）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/models/schemas/condition.py stock-engine/tests/test_screener/test_engine.py
git commit -m "feat(condition): add transform field to ExpressionNode (P1-6)"
```

---

## Task 3: 共享聚合内核 aggregate_series

纯函数，技术面/基本面/未来择时复用。这是 transform 的算法单一真相源。

**Files:**
- Modify: `stock-engine/services/shared/factor_pipeline.py`（末尾追加）
- Test: `stock-engine/tests/test_screener/test_factor_pipeline_transform.py`（新建）

**Interfaces:**
- Consumes: `series`（list[float] 或 np.ndarray，含 NaN）、`transform`（`{"type":str,"window":int}` 或 None）。
- Produces: `aggregate_series(series, transform) -> float`（NaN 安全）。无 transform 时返回末值。

- [ ] **Step 1: 写失败测试（新建测试文件）**

```python
import math
import numpy as np

from services.shared.factor_pipeline import aggregate_series


def _nan(x):
    return isinstance(x, float) and math.isnan(x)


def test_no_transform_returns_last():
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], None) == 4.0


def test_ma_window3():
    # 末 3 个 [2,3,4] 均值 = 3.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "ma", "window": 3}) == 3.0


def test_std_window3():
    # 末 3 个 [2,3,4] 样本标准差 = 1.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "std", "window": 3}) == 1.0


def test_pct_change_window3():
    # 末 3 个 [2,3,4]：(4-2)/2 = 1.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "pct_change", "window": 3}) == 1.0


def test_max_min_window3():
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "max", "window": 3}) == 4.0
    assert aggregate_series([1.0, 2.0, 3.0, 4.0], {"type": "min", "window": 3}) == 2.0


def test_window_insufficient_returns_nan():
    assert _nan(aggregate_series([1.0, 2.0], {"type": "ma", "window": 5}))


def test_skips_nan_in_window():
    # 末 3 个含一个 NaN → 跳过，剩 [3,4] 均值=3.5（仍要求跳过后≥2 个有效点；不足则 NaN）
    assert aggregate_series([1.0, float("nan"), 3.0, 4.0], {"type": "ma", "window": 3}) == 3.5


def test_empty_series_nan():
    assert _nan(aggregate_series([], {"type": "ma", "window": 3}))


def test_pct_change_zero_base_nan():
    # window=3 → 末 3 个 [0,1,2]，首=0 → 除零 → NaN
    assert _nan(aggregate_series([0.0, 1.0, 2.0], {"type": "pct_change", "window": 3}))


def test_accepts_ndarray():
    assert aggregate_series(np.array([1.0, 2.0, 3.0, 4.0]), {"type": "ma", "window": 2}) == 3.5
```

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_factor_pipeline_transform.py -v`
Expected: FAIL — `ImportError: cannot import name 'aggregate_series'`

- [ ] **Step 3: 在 factor_pipeline.py 末尾追加 aggregate_series**

在 `stock-engine/services/shared/factor_pipeline.py` 末尾追加（文件已存在，含 `precompute` / `precompute_factors_batch`）：

```python
def aggregate_series(series, transform):
    """对因子值序列做滚动窗口聚合（PRD 009 §1 P1-6 共享内核）。

    :param series: list[float] / np.ndarray（可能含 NaN），按时间升序。
    :param transform: ``{"type": "ma"|"std"|"pct_change"|"max"|"min", "window": int}``
        或 None。None → 返回末值（与无 transform 行为一致）。
    :return: 聚合后的标量；窗口未满 / 空 / 非法 → NaN。

    语义（与 Step 1 测试一致）：
    - 先要求 ``len(vals) >= window``（窗口被可用历史填满），否则 NaN；
    - 在末 ``window`` 个值中跳过 NaN 得 ``seg``；
    - ``seg`` 为空 → NaN；``std``/``pct_change`` 要求 ``len(seg) >= 2``，否则 NaN；
    - ``ma``=均值；``std``=样本标准差(ddof=1)；``pct_change``=(末-首)/首（首为 0 → NaN）；
      ``max``/``min``=极值。
    """
    import math

    try:
        vals = [float(v) for v in list(series)]
    except TypeError:
        return float("nan")

    if transform is None:
        return vals[-1] if vals else float("nan")

    t = transform.get("type")
    window = int(transform.get("window", 0))
    if t not in ("ma", "std", "pct_change", "max", "min") or window < 1:
        return float("nan")
    if len(vals) < window:
        return float("nan")  # 可用历史不足一个完整窗口

    seg = [v for v in vals[-window:] if not (isinstance(v, float) and math.isnan(v))]
    if len(seg) == 0:
        return float("nan")
    if len(seg) < 2 and t in ("std", "pct_change"):
        return float("nan")

    if t == "ma":
        return sum(seg) / len(seg)
    if t == "std":
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / (len(seg) - 1)
        return math.sqrt(var)
    if t == "pct_change":
        if seg[0] == 0:
            return float("nan")
        return (seg[-1] - seg[0]) / seg[0]
    if t == "max":
        return max(seg)
    if t == "min":
        return min(seg)
    return float("nan")
```

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_factor_pipeline_transform.py -v`
Expected: PASS（全部 10 个测试）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/shared/factor_pipeline.py stock-engine/tests/test_screener/test_factor_pipeline_transform.py
git commit -m "feat(shared): add aggregate_series transform kernel (P1-6)"
```

---

## Task 4: factor_signature 支持 transform 感知 key

`factor_signature` 加可选 `transform` 形参，transform 存在时追加 `__<type><window>`。向后兼容（既有调用不传 transform，行为不变）。

**Files:**
- Modify: `stock-engine/services/screener/engine.py:38-55`（factor_signature）
- Test: `stock-engine/tests/test_screener/test_engine.py`

**Interfaces:**
- Produces: `factor_signature(factor_key, params=None, output_index=None, transform=None) -> str`；transform 存在→`...#__ma20`，无 transform→现状。

- [ ] **Step 1: 写失败测试（追加 test_engine.py）**

```python
def test_factor_signature_without_transform_unchanged():
    from services.screener.engine import factor_signature

    assert factor_signature("MA", {"timeperiod": 5}, 0) == "MA(timeperiod=5)#0"
    assert factor_signature("PE_TTM") == "PE_TTM"


def test_factor_signature_with_transform():
    from services.screener.engine import factor_signature

    assert (
        factor_signature("PE_TTM", transform={"type": "ma", "window": 20})
        == "PE_TTM__ma20"
    )
    assert (
        factor_signature("MA", {"timeperiod": 5}, 0, {"type": "std", "window": 10})
        == "MA(timeperiod=5)#0__std10"
    )
```

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_engine.py::test_factor_signature_with_transform -v`
Expected: FAIL — 签名不接受 transform（TypeError）或不带后缀。

- [ ] **Step 3: 修改 factor_signature**

把 `stock-engine/services/screener/engine.py` 的 `factor_signature` 改为：

```python
def factor_signature(
    factor_key: str,
    params: Optional[dict] = None,
    output_index: Optional[int] = None,
    transform: Optional[dict] = None,
) -> str:
    """生成 factor 缓存 key，如 ``MA(timeperiod=5)#0``，transform 追加 ``__ma20``。

    基本面因子（TUSHARE）由调用方直接以 factorKey 作为签名（无 params / output_index）。
    本函数对参数按 key 排序，保证不同顺序的同参数等价。transform 存在时追加
    ``__<type><window>``，使「当日值」与「窗口聚合值」共存不冲突（PRD 009 §1.2.3）。
    """
    if params:
        kv = ",".join(f"{k}={v}" for k, v in sorted(params.items()))
        sig = f"{factor_key}({kv})"
    else:
        sig = factor_key
    if output_index is not None:
        sig = f"{sig}#{output_index}"
    if transform:
        sig = f"{sig}__{transform.get('type')}{transform.get('window')}"
    return sig
```

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_engine.py -v`
Expected: PASS（新测试过，既有不回归）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/screener/engine.py stock-engine/tests/test_screener/test_engine.py
git commit -m "feat(screener): factor_signature transform-aware key (P1-6)"
```

---

## Task 5: collect_factor_refs 捕获 transform

收集条件树因子引用时带上 transform，并用 transform 感知签名去重（PE_TTM 与 PE_TTM__ma20 视为不同规格）。

**Files:**
- Modify: `stock-engine/services/screener/factor_precompute.py:56-117`（collect_factor_refs）
- Test: `stock-engine/tests/test_screener/test_factor_precompute.py`（新建）

**Interfaces:**
- Consumes: Task 2 的 `ExpressionNode.transform`。
- Produces: ref dict 可含 `{"factorKey","params","outputIndex","transform"}`。

- [ ] **Step 1: 写失败测试（新建）**

```python
from services.screener.factor_precompute import collect_factor_refs


def test_collect_refs_without_transform():
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "RSI"}, "comparator": "<", "right": {"value": 30}}
        ],
    }
    refs = collect_factor_refs(tree)
    assert refs == [{"factorKey": "RSI"}]


def test_collect_refs_with_transform():
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}},
                "comparator": "<",
                "right": {"value": 30},
            }
        ],
    }
    refs = collect_factor_refs(tree)
    assert len(refs) == 1
    assert refs[0]["factorKey"] == "PE_TTM"
    assert refs[0]["transform"] == {"type": "ma", "window": 20}


def test_collect_refs_dedup_keeps_both_transform_and_plain():
    # 同一因子：一个带 transform 一个不带 → 两个不同规格
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "PE_TTM"}, "comparator": "<", "right": {"value": 30}},
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}},
                "comparator": "<",
                "right": {"value": 30},
            },
        ],
    }
    refs = collect_factor_refs(tree)
    assert len(refs) == 2
```

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_factor_precompute.py -v`
Expected: FAIL — refs 不含 transform（被忽略）或去重把两个 PE_TTM 合并成 1 个。

- [ ] **Step 3: 修改 collect_factor_refs**

在 `stock-engine/services/screener/factor_precompute.py` 的 `collect_factor_refs` 中，把 `if isinstance(tree, ExpressionNode):` 内 `kind == "factor"` 分支改为：

```python
        if kind == "factor":
            sig = factor_signature(
                tree.factor, tree.params, tree.outputIndex, tree.transform
            )
            if sig not in _seen:
                _seen.add(sig)
                ref = {"factorKey": tree.factor}
                if tree.params:
                    ref["params"] = tree.params
                if tree.outputIndex is not None:
                    ref["outputIndex"] = tree.outputIndex
                if tree.transform:
                    ref["transform"] = tree.transform
                _acc.append(ref)
```

（其余逻辑不变。）

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_factor_precompute.py -v`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/screener/factor_precompute.py stock-engine/tests/test_screener/test_factor_precompute.py
git commit -m "feat(screener): collect_factor_refs captures transform (P1-6)"
```

---

## Task 6: precompute_factors 应用 transform — 技术面

技术面因子：`compute_single` 已返回全序列，transform 时聚合末 window 个，否则维持 `[-1]`。存入 transform 感知 key。

**Files:**
- Modify: `stock-engine/services/screener/factor_precompute.py:124-202`（precompute_factors 技术面分支）
- Test: `stock-engine/tests/test_screener/test_factor_precompute.py`（追加）

**Interfaces:**
- Consumes: Task 3 `aggregate_series`、Task 4 `factor_signature(transform=...)`、Task 5 ref 带 transform。
- Produces: `precompute_factors` 返回的 `per_symbol` 含 transform 感知 key（如 `MA__ma20`）。

- [ ] **Step 1: 写失败测试（追加到 test_factor_precompute.py）**

```python
import math

from services.screener.factor_precompute import precompute_factors


def _flat_close_history(closes):
    """构造 ohlcv_history，close=closes[i]，open/high/low=close，volume=1000。"""
    return [
        {"date": f"2024-01-{i + 1:02d}", "open": c, "high": c, "low": c, "close": c, "volume": 1000}
        for i, c in enumerate(closes)
    ]


def test_precompute_technical_transform_ma():
    # CLOSE 序列 [1..10]，MA(window=3) 末 3 个 [8,9,10] 均值 = 9.0
    closes = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "CLOSE", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    # 技术面 CLOSE 用签名作 key（CLOSE 无 params/output_index → CLOSE__ma3）
    val = result["S1"]["CLOSE__ma3"]
    assert val == 9.0


def test_precompute_technical_no_transform_unchanged():
    closes = [1.0, 2.0, 3.0, 4.0]
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "CLOSE"}, "comparator": "<", "right": {"value": 100}}
        ],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["CLOSE"] == 4.0  # 末值
```

> **前置确认**：`CLOSE` 是否是已注册 factorKey、其 source 是否属 `_TECH_SOURCES`。运行前若 `CLOSE` 未注册或非技术面，改用已注册的技术面因子（如 `MA` 带 `params={"timeperiod":1}` 近似 close，或查 `data/factors.default.json` 取一个 RAW 价格因子）。执行 Step 2 时据此校准测试用的 factorKey。

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_factor_precompute.py::test_precompute_technical_transform_ma -v`
Expected: FAIL — key `CLOSE__ma3` 不存在（仍按 `CLOSE` 存末值），或值=4.0 而非 9.0。

- [ ] **Step 3: 修改 precompute_factors 技术面分支**

在 `stock-engine/services/screener/factor_precompute.py` 顶部补导入：

```python
from services.shared.factor_pipeline import aggregate_series
```

把 `precompute_factors` 内 `# 2) 技术面/价格因子` 循环体改为：

```python
        ohlcv_history = candidate.get("ohlcv_history")
        arrays = None
        for ref in refs_by_source["technical"]:
            transform = ref.get("transform")
            sig = factor_signature(
                ref["factorKey"], ref.get("params"), ref.get("outputIndex"), transform
            )
            try:
                if arrays is None:
                    if not ohlcv_history:
                        per_symbol[sig] = float("nan")
                        continue
                    arrays = kline_to_arrays(ohlcv_history)

                arr = factor_calculator.compute_single(
                    factor_key=ref["factorKey"],
                    inputs=arrays,
                    params=ref.get("params"),
                    output_index=ref.get("outputIndex"),
                )
                if transform is None:
                    per_symbol[sig] = _last_or_nan(arr)
                else:
                    per_symbol[sig] = aggregate_series(arr, transform)
            except Exception as exc:
                logger.warning(
                    "选股因子预计算失败 symbol=%s factor=%s: %s",
                    symbol, ref["factorKey"], exc,
                )
                per_symbol[sig] = float("nan")
```

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_factor_precompute.py -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/screener/factor_precompute.py stock-engine/tests/test_screener/test_factor_precompute.py
git commit -m "feat(screener): precompute_factors applies transform to technical factors (P1-6)"
```

---

## Task 7: precompute_factors 应用 transform — 基本面（逐 bar extra）

基本面 transform 需逐 bar 序列：从候选 `extra`（`{trade_date_str: {pe_ttm,...}}`）按日期升序取该因子值组成序列，再 `aggregate_series`。`extra` 缺失（选股中心仅有快照）→ NaN（优雅降级；P1-6 主场是轮动回测，有逐 bar extra）。基本面**无 transform** 行为不变（从 `fundamentals` 快照取）。

**Files:**
- Modify: `stock-engine/services/screener/factor_precompute.py:161-167`（precompute_factors 基本面分支）
- Test: `stock-engine/tests/test_screener/test_factor_precompute.py`（追加）

**Interfaces:**
- Consumes: Task 3 `aggregate_series`；候选 `extra` 形态 `{trade_date_str: {<tushareField>: float}}`；`factor_registry.get_factor(key).tushareField`（如 `PE_TTM → pe_ttm`）。
- Produces: 基本面 transform 值存入 transform 感知 key（如 `PE_TTM__ma20`）于 `factor_values`。

- [ ] **Step 1: 写失败测试（追加）**

```python
def test_precompute_fundamental_transform_ma_from_extra():
    # PE_TTM 逐 bar：[10,12,14,16,18,20]，ma(window=3) 末 3 个 [14,16,18] 均值=16.0
    extras = {
        "2024-01-01": {"pe_ttm": 10.0},
        "2024-01-02": {"pe_ttm": 12.0},
        "2024-01-03": {"pe_ttm": 14.0},
        "2024-01-04": {"pe_ttm": 16.0},
        "2024-01-05": {"pe_ttm": 18.0},
        "2024-01-06": {"pe_ttm": 20.0},
    }
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {}, "extra": extras}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["PE_TTM__ma3"] == 16.0


def test_precompute_fundamental_transform_nan_without_extra():
    # 选股中心只有 fundamentals 快照，无逐 bar extra → transform NaN
    tree = {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                "comparator": "<",
                "right": {"value": 100},
            }
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {"PE_TTM": 15.0}}}
    result = precompute_factors(tree, candidates)
    import math
    assert math.isnan(result["S1"]["PE_TTM__ma3"])


def test_precompute_fundamental_no_transform_unchanged():
    tree = {
        "operator": "AND",
        "conditions": [
            {"type": "compare", "left": {"factor": "PE_TTM"}, "comparator": "<", "right": {"value": 100}}
        ],
    }
    candidates = {"S1": {"ohlcv_history": [], "fundamentals": {"PE_TTM": 15.0}}}
    result = precompute_factors(tree, candidates)
    assert result["S1"]["PE_TTM"] == 15.0
```

> **前置确认**：`PE_TTM` 的 `fd.source == "TUSHARE"` 且 `fd.tushareField == "pe_ttm"`（查 `factor_registry`）。若字段名不同，校准测试 extras 的 key。

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_factor_precompute.py::test_precompute_fundamental_transform_ma_from_extra -v`
Expected: FAIL — key `PE_TTM__ma3` 不存在。

- [ ] **Step 3: 修改 precompute_factors 基本面分支**

把 `precompute_factors` 内 `# 1) 基本面因子` 循环体改为：

```python
        fundamentals = candidate.get("fundamentals") or {}
        extras = candidate.get("extra") or {}
        for ref in refs_by_source["fundamental"]:
            key = ref["factorKey"]
            transform = ref.get("transform")
            sig = factor_signature(key, ref.get("params"), ref.get("outputIndex"), transform)
            if transform is None:
                # 现状：从 fundamentals 快照取，以 factorKey 作 key
                per_symbol[key] = _to_float(fundamentals.get(key))
            else:
                # transform：从 extra 逐 bar 取 tushareField 序列再聚合
                fd = factor_registry.get_factor(key)
                field = getattr(fd, "tushareField", None) or key.lower()
                series = []
                for d in sorted(extras.keys()):
                    v = extras[d].get(field) if isinstance(extras[d], dict) else None
                    if v is not None:
                        series.append(v)
                per_symbol[sig] = aggregate_series(series, transform)
```

（`aggregate_series` 已在 Task 6 导入。）

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_factor_precompute.py -v`
Expected: PASS（全部）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/screener/factor_precompute.py stock-engine/tests/test_screener/test_factor_precompute.py
git commit -m "feat(screener): precompute_factors applies transform to fundamental factors (P1-6)"
```

---

## Task 8: ConditionEngine._resolve_factor 用 transform key 查表

求值时，若 FactorNode 带 transform，用 transform 感知签名查 `factor_values`（基本面 transform 值也在 factor_values，见 Task 7）；无 transform 维持现状（基本面先查 fundamentals，技术面查签名）。

**Files:**
- Modify: `stock-engine/services/screener/engine.py:212-227`（`_resolve_factor`）
- Test: `stock-engine/tests/test_screener/test_engine.py`（追加）

**Interfaces:**
- Consumes: Task 4 `factor_signature(transform=...)`；`context.factor_values`（含 transform key）。
- Produces: transform 条件正确解析为聚合值。

- [ ] **Step 1: 写失败测试（追加 test_engine.py）**

```python
def test_resolve_factor_with_transform():
    from models.schemas.condition import EvalContext  # 若 EvalContext 在别处，见下
    from services.screener.engine import ConditionEngine, factor_signature

    # 构造上下文：factor_values 含 transform key
    sig = factor_signature("PE_TTM", transform={"type": "ma", "window": 3})
    ctx = EvalContext(factor_values={"S1": {sig: 16.0}}, fundamentals={})
    eng = ConditionEngine()
    leaf = {
        "type": "compare",
        "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
        "comparator": "<",
        "right": {"value": 30},
    }
    assert eng.evaluate(leaf, ctx) is True
```

> **前置确认**：`EvalContext` 的导入路径与构造签名。运行 `pytest ... -k test_resolve_factor_with_transform --collect-only` 失败时，查 `services/screener/engine.py` 里 `EvalContext` 定义（字段名可能是 `factor_values` / `fundamentals`），据此校准测试构造。若 `EvalContext` 不接受这种构造，按其真实字段调整。

- [ ] **Step 2: 运行确认失败**

Run: `pytest tests/test_screener/test_engine.py::test_resolve_factor_with_transform -v`
Expected: FAIL — transform 节点查不到值（返回 NaN → 比较 False）。

- [ ] **Step 3: 修改 _resolve_factor**

把 `stock-engine/services/screener/engine.py` 的 `_resolve_factor` 改为：

```python
    def _resolve_factor(self, node: ExpressionNode, context: EvalContext) -> float:
        """解析因子引用。

        - 带 transform：用 transform 感知签名查 ``factor_values``（技术面/基本面
          的聚合值都存在这里，见 precompute_factors）；缺失→NaN。
        - 无 transform：基本面优先查 ``fundamentals``（key=factorKey），技术面查
          ``factor_values``（factor_signature）；缺失→NaN。
        """
        key = node.factor
        transform = getattr(node, "transform", None)
        if transform:
            sig = factor_signature(key, node.params, node.outputIndex, transform)
            return _to_float(context.factor_values.get(sig, float("nan")))
        # 基本面：直接以 factorKey 作为 key
        if key in context.fundamentals:
            return _to_float(context.fundamentals[key])
        # 技术面：用签名查找
        sig = factor_signature(key, node.params, node.outputIndex)
        if sig in context.factor_values:
            return _to_float(context.factor_values[sig])
        # 缺失 → NaN
        return float("nan")
```

- [ ] **Step 4: 运行确认通过**

Run: `pytest tests/test_screener/test_engine.py -v`
Expected: PASS（新测试过，既有不回归）

- [ ] **Step 5: 提交**

```bash
git add stock-engine/services/screener/engine.py stock-engine/tests/test_screener/test_engine.py
git commit -m "feat(screener): _resolve_factor resolves transform-aware key (P1-6)"
```

---

## Task 9: validator — transform 范围/类型/window 校验

结构校验层拦截非法 transform：`type` 枚举、`window∈[1,60]`、transform 仅在 `filter.conditions` 合法（出现在 `factor.weights` 报 `TRANSFORM_NOT_ALLOWED_IN_RANKING`）。

**Files:**
- Modify: `stock-engine/services/strategy/validator.py`（在既有因子节点校验附近追加）
- Test: `stock-engine/tests/services/strategy/test_validator.py`

**Interfaces:**
- Consumes: Task 1 TransformConfig + 错误码。
- Produces: 非法 transform 在保存策略时被拦截（422）。

- [ ] **Step 1: 定位既有因子校验入口**

Run: `grep -n "MULTI_OUTPUT_REQUIRES_INDEX\|def _validate.*factor\|collect_factor_refs\|factor" stock-engine/services/strategy/validator.py | head -30`
确认 validator 如何遍历 `filter.conditions` 的 FactorNode（通常复用 `collect_factor_refs` 或自遍历），找到注入点函数名与行号。

- [ ] **Step 2: 写失败测试（追加 test_validator.py）**

```python
def test_validator_rejects_transform_bad_type():
    import pytest
    from services.strategy.validator import validate  # 校准真实入口名（Step 1）
    # 构造一个含 filter.conditions 且 transform.type 非法的 screen_config dict
    cfg = {
        "universe": {"pool": "csi300"},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": [
                {"type": "compare", "left": {"factor": "PE_TTM", "transform": {"type": "median", "window": 3}},
                 "comparator": "<", "right": {"value": 30}}
            ]
        },
        "portfolio": {"top_n": 10},
    }
    errors = validate(cfg)  # 或对应函数名
    codes = [e.code for e in errors]
    assert "INVALID_TRANSFORM_TYPE" in codes


def test_validator_rejects_transform_window_out_of_range():
    from services.strategy.validator import validate
    cfg = {
        "universe": {"pool": "csi300"},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": [
                {"type": "compare", "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 999}},
                 "comparator": "<", "right": {"value": 30}}
            ]
        },
        "portfolio": {"top_n": 10},
    }
    errors = validate(cfg)
    assert "INVALID_TRANSFORM_WINDOW" in [e.code for e in errors]


def test_validator_rejects_transform_in_ranking():
    from services.strategy.validator import validate
    # factor.weights 不应支持 transform；这里若 schema 层已禁止（FactorNode.transform 仅 conditions），
    # 则构造一个 composite weights 带 transform 的非法形态，期望 TRANSFORM_NOT_ALLOWED_IN_RANKING
    # （若 weights 的值是 number 不是 FactorNode，则该场景由 schema 天然禁止——改测：conditions 内 transform 合法通过）
    cfg = {
        "universe": {"pool": "csi300"},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": [
                {"type": "compare", "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 20}},
                 "comparator": "<", "right": {"value": 30}}
            ]
        },
        "portfolio": {"top_n": 10},
    }
    errors = validate(cfg)
    # 合法 transform 不报 transform 相关错
    assert "INVALID_TRANSFORM_TYPE" not in [e.code for e in errors]
    assert "INVALID_TRANSFORM_WINDOW" not in [e.code for e in errors]
```

> **校准**：`validate` 入口名、`screen_config` 4 层结构最小合法形态、errors 返回类型（`list[StrategyValidationError]`，字段 `.code`）。按 Step 1 结果与既有 test_validator.py 用例对齐。

- [ ] **Step 3: 运行确认失败**

Run: `pytest tests/services/strategy/test_validator.py -v`
Expected: FAIL — 非法 transform 未被拦截（errors 不含对应 code）。

- [ ] **Step 4: 实现 validator transform 校验**

在 validator 中既有「遍历 filter.conditions 的 FactorNode」处（Step 1 定位的函数），对每个带 `transform` 的因子节点追加：

```python
            # PRD 009 §1.2.4 transform 校验
            tf = node.transform  # FactorNode.transform: Optional[TransformConfig]
            if tf is not None:
                valid_types = {"ma", "std", "pct_change", "max", "min"}
                if tf.type not in valid_types:
                    errors.append(_err(path + ".transform.type",
                                       ErrorCode.INVALID_TRANSFORM_TYPE))
                if not (1 <= tf.window <= 60):
                    errors.append(_err(path + ".transform.window",
                                       ErrorCode.INVALID_TRANSFORM_WINDOW))
```

（`_err` / `path` / `errors` 沿用既有 validator 的辅助与变量名；`node` 为当前 FactorNode。若 validator 是用 `collect_factor_refs` 拿 dict，则从 ref dict 读 `transform` 同样校验。）

> **ranking 禁 transform**：`factor.weights` 的值是数值（不是 FactorNode），schema 天然无法挂 transform，故无需额外校验；`TRANSFORM_NOT_ALLOWED_IN_RANKING` 错误码保留备用（如未来 ranking 引入因子节点形态时启用）。

- [ ] **Step 5: 运行确认通过**

Run: `pytest tests/services/strategy/test_validator.py -v`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add stock-engine/services/strategy/validator.py stock-engine/tests/services/strategy/test_validator.py
git commit -m "feat(strategy): validate transform type/window (P1-6)"
```

---

## Task 10: 端到端集成测试 — 轮动选股带 transform 条件

在 rebalance 选股入口验证 transform 条件端到端生效（不依赖 watcher / 真实回测，构造 kline_map + extra_map 调 `select_at_rebalance_date`）。

**Files:**
- Test: `stock-engine/tests/services/backtest/test_rebalance_transform.py`（新建）

**Interfaces:**
- Consumes: Task 1-9 全部。
- Produces: 证明 P1-6 在轮动选股路径可用。

- [ ] **Step 1: 写集成测试**

```python
import math

import pandas as pd

from services.backtest.rebalance_engine import RebalanceEngine


def _kline(closes):
    """构造单标的 K 线 list[dict]，close=closes[i]，日期连续。"""
    return [
        {"date": f"2024-01-{i + 1:02d}", "open": c, "high": c, "low": c, "close": c, "volume": 1000}
        for i, c in enumerate(closes)
    ]


def _extra(pe_series):
    """extra_map: {symbol: {trade_date_str: {pe_ttm: v}}}。"""
    return {
        "S1": {f"2024-01-{i + 1:02d}": {"pe_ttm": v} for i, v in enumerate(pe_series)},
    }


def test_rebalance_select_with_fundamental_transform(monkeypatch):
    # 屏蔽 point-in-time universe 过滤（spec 011 P1-1 强制查 watcher）：
    # 让 _apply_universe_filter 原样返回 kline_map，避免依赖 watcher_client。
    monkeypatch.setattr(
        RebalanceEngine, "_apply_universe_filter",
        staticmethod(lambda cfg, km, ts, wc: km),
    )

    # PE_TTM 逐日 [10,12,14,16,18,20]，ma(3) 末值=(14+16+18)/3=16.0 < 30 → 命中
    pe = [10.0, 12.0, 14.0, 16.0, 18.0, 20.0]
    closes = [10.0] * 6
    kline_map = {"S1": _kline(closes)}
    extra_map = _extra(pe)

    cfg = {
        "universe": {"pool": "manual", "stocks": ["S1"]},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": [
                {
                    "type": "compare",
                    "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                    "comparator": "<",
                    "right": {"value": 30},
                }
            ]
        },
        "portfolio": {"top_n": 10},
    }

    scores = RebalanceEngine().select_at_rebalance_date(
        cfg, kline_map, pd.Timestamp("2024-01-06"), extra_map=extra_map
    )
    assert "S1" in scores  # 命中条件 → 进入候选

    # 反例：阈值改 < 15，ma(3)=16 不命中
    cfg_fail = {
        "universe": {"pool": "manual", "stocks": ["S1"]},
        "factor": {"method": "disabled"},
        "filter": {
            "conditions": [
                {
                    "type": "compare",
                    "left": {"factor": "PE_TTM", "transform": {"type": "ma", "window": 3}},
                    "comparator": "<",
                    "right": {"value": 15},
                }
            ]
        },
        "portfolio": {"top_n": 10},
    }
    scores_fail = RebalanceEngine().select_at_rebalance_date(
        cfg_fail, kline_map, pd.Timestamp("2024-01-06"), extra_map=extra_map
    )
    assert "S1" not in scores_fail
```

> **校准**：`select_at_rebalance_date` 的真实签名（`watcher_client` 是否必填、`extra_map` 形态）。spec 011 P1-1 后 watcher_client 必填且 None 抛错——故用 monkeypatch 屏蔽 `_apply_universe_filter`。若签名/形态不同，按 `rebalance_engine.py` 真实定义调整。

- [ ] **Step 2: 运行测试**

Run: `pytest tests/services/backtest/test_rebalance_transform.py -v`
Expected: PASS（两个断言：阈值 30 命中、阈值 15 不命中）

- [ ] **Step 3: 跑全量回归**

Run: `pytest tests/services/strategy tests/test_screener tests/services/backtest -v`
Expected: 全部 PASS（transform 改动不破坏既有选股/条件/rebalance 行为）

- [ ] **Step 4: 提交**

```bash
git add stock-engine/tests/services/backtest/test_rebalance_transform.py
git commit -m "test(backtest): rotation selection with fundamental transform condition (P1-6 e2e)"
```

---

## Deferred（本计划不含，登记后续）

1. **前端条件树 transform 控件**（`strategy-editor.js`）：条件树叶子加「滚动窗口」配置（聚合类型下拉 + 窗口天数），collect/refill 透传 `transform`。需先读 `strategy-editor.js` 条件树渲染与 collect 逻辑，单独出前端计划。
2. **warmup 联动**（`compiler.py` warmup 推断）：含大 window transform 的策略，backtest warmup 应纳入 `transform.window`，避免回测初期候选窗口不足大面积 NaN。需读 `compiler.py` warmup 推断（约 L493-503）后补；当前 `history_window=60` 已覆盖 `window≤60`，短期不阻断，作为正确性增强后续做。
3. **多输出因子 + transform**（MACD/BOLL transform）：当前 `aggregate_series` 已支持任意序列，但多输出因子需先按 `output_index` 降维成单序列再 transform——Task 6 的 `compute_single(output_index=...)` 已降维，理论可用，留专门测试用例验证。
4. **PRD §1.2.2 勘误**：PRD 原写「改 `_compute_one_factor`」，实际 conditions 走共享 `precompute_factors`；本计划已按实际改动点实施。PRD 文案需同步修正为 `precompute_factors`（改 `_compute_one_factor` 仅适用于 ranking，而 ranking 不支持 transform）。

## Self-Review

**Spec 覆盖**：PRD 009 §1 各点 → Task 映射：
- §1.2.1 TransformConfig/FactorNode.transform → Task 1
- ExpressionNode.transform（运行时） → Task 2（PRD 未单列，核校后发现必需）
- §1.2.2 即时聚合（共享内核） → Task 3（aggregate_series）+ Task 6/7（precompute_factors）
- §1.2.3 transform 感知 key 去重 → Task 4（factor_signature）+ Task 5（collect_factor_refs）
- §1.2.4 validator → Task 9
- §1.5 验收 → Task 10（e2e）覆盖核心；前端验收在 Deferred。
- §1.2.0 方案选型（即时聚合非预计算）→ 全计划遵循；factor_snapshot 未改。

**Placeholder 扫描**：无「TBD/TODO」；每个代码步骤含完整代码。部分测试含「前置确认」说明（如 CLOSE/PE_TTM 的注册状态、EvalContext 构造、validate 入口名）——这些是因依赖运行时 registry/既有符号，已给出校准方法（grep/读特定行），非占位空话。

**类型一致性**：`aggregate_series(series, transform)` 在 Task 3 定义、Task 6/7 调用，签名一致；`factor_signature(..., transform=None)` Task 4 定义、Task 5/6/7/8 调用一致；transform key 形态 `__<type><window>`（如 `PE_TTM__ma20`）在 Task 4/6/7/8 一致；`ExpressionNode.transform`（dict）与 `FactorNode.transform`（TransformConfig）分层，aggregate_series 接受 dict 形态。
