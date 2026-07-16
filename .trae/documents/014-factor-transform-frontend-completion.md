# 014 因子 Transform 前端可视化 + 因子库元数据 + warmup 联动

> **change-id**：014-factor-transform-frontend-completion
> **来源 PRD**：`sdlc/prd/009-轮动策略进阶能力/轮动策略进阶能力与008收口PRD.md` §1.2.5 / §1.4 / §1.5
> **对齐**：akquant 0.2.47 · 统一策略配置 Schema v1.0 · spec 012（P1-6 engine 已交付）· spec 013（正交，不冲突）
> **状态**：待评审
> **类型**：前端为主 + engine 元数据/warmup 增强

---

## 一、Summary（为什么做、做什么）

### 背景
spec 012（P1-6 transform）已经把 engine 侧完整交付：`FactorNode.transform` / `TransformConfig` / `aggregate_series` / `factor_signature` transform 感知 key / `precompute_factors` 双路径应用 / validator 校验（[models.py:80](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/models.py)、[validator.py:684-703](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/validator.py)、[factor_precompute.py](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/factor_precompute.py)）。但 012 把**前端可视化配置**显式切到 Deferred（`tasks.md` 末尾 Deferred 第 1 条），PRD 009 §1.2.5/§1.5 已签字要求但一直没交付。

同时 [condition-tree-builder.js:102-126](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) 的 `normalizeOperand` 在 factor 分支**只保留 factor/params/inputs/output_index，丢弃 transform**——这是**数据丢失 bug**：用户手改 JSON 加了 transform，用编辑器打开保存会被悄悄抹掉。

### 本次交付（4 个工作流）
1. **因子库 transformable 元数据**：`factors.default.json` + `FactorDef` schema 新增 `transformable: bool` 字段，声明哪些因子支持 transform；前端据此决定"滚动窗口"按钮可见性。
2. **前端条件树 transform UI**：`condition-tree-builder.js` 折叠式控件（聚合类型下拉 + 窗口天数），仅 `f-screen-conditions` 区域显示；同步修 `normalizeOperand` 数据丢失 bug。
3. **warmup 联动**（012 Deferred 第 2 条）：`compiler._infer_rebalance_warmup` 把 `transform.window` 纳入推断，避免回测初期大面积 NaN。
4. **多输出因子 + transform 验证**（012 Deferred 第 3 条）：补 e2e 测试证明 MACD/BOLL 经 output_index 降维后 transform 可用。

### 不做（Out of Scope）
- ranking `factor.weights` 的 transform 支持（PRD 009 §1.3 明确不做，validator 已对 trading/ranking 路径报 `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN`）。
- 择时范式（`on_bar`）的 transform（PRD §1.2.0 范围限定，仅轮动范式）。
- 选股中心 `screener.js` 的 transform（它不用 ConditionTreeBuilder，自���逻辑；PRD 未要求）。

---

## 二、Current State Analysis（现状核校）

### 2.1 后端已就绪（本次不改，仅复用）
| 能力 | 位置 | 状态 |
|---|---|---|
| `TransformConfig` / `FactorNode.transform` | [models.py:45-82](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/models.py) | ✅ |
| validator 作用域 + window 校验 | [validator.py:684-703](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/validator.py) | ✅（screen 合法 / trading 报 `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN` / window>60 报 `INVALID_TRANSFORM_WINDOW`） |
| `aggregate_series` 共享内核 | [factor_precompute.py:227-281](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/factor_precompute.py) | ✅ |
| `factor_signature` transform 感知 key | [engine.py](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/engine.py)（`__ma20` 后缀） | ✅ |
| `collect_factor_refs` 透传 transform | [factor_precompute.py:99-119](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/factor_precompute.py) | ✅ |
| `precompute_factors` 技术面/基本面双路径 | [factor_precompute.py:162-220](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/factor_precompute.py) | ✅ |
| 错误码 | [errors.py:196-201](file:///d:/lcProject/stock-pulse/stock-engine/services/strategy/errors.py) | ✅（`INVALID_TRANSFORM_WINDOW` / `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN`） |

### 2.2 前端缺口（本次要补）
| 位置 | 问题 |
|---|---|
| [condition-tree-builder.js:102-126](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) `normalizeOperand` | factor 分支只取 4 字段，**丢弃 transform**（数据丢失 bug） |
| [condition-tree-builder.js:332-370](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) `renderExpression` | 无 transform 控件 |
| [condition-tree-builder.js:233-280](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) `updateLeaf` | 不处理 transform 字段变更 |
| [condition-tree-builder.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/condition-tree-builder.css) | 无 transform 折叠区样式 |

### 2.3 因子库缺口（本次要补）
| 位置 | 问题 |
|---|---|
| [factors.default.json](file:///d:/lcProject/stock-pulse/stock-engine/data/factors.default.json) | 无 `transformable` 字段，前端无法判断哪些因子支持 transform |
| [models/schemas/factor.py:33-49](file:///d:/lcProject/stock-pulse/stock-engine/models/schemas/factor.py) `FactorDef` | 无 `transformable` 字段 |
| [models/schemas/factor.py:56-72](file:///d:/lcProject/stock-pulse/stock-engine/models/schemas/factor.py) `FactorCreateRequest` / `FactorUpdateRequest` | 无 `transformable` 字段（CRUD 无法设置） |

### 2.4 warmup 缺口（本次要补）
| 位置 | 问题 |
|---|---|
| [compiler.py:124-146](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py) `FactorSpec` | 不含 transform |
| [compiler.py:149-174](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py) `_collect_factor_specs` | 不捕获 transform |
| [compiler.py:260-296](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py) `_infer_rebalance_warmup` | 算窗口时不算 `transform.window` |

### 2.5 影响面与边界
- **前端 ConditionTreeBuilder 是共享组件**，被 3 个静态条件树区域使用：
  - `f-screen-conditions`（screen，allowRef=false, allowCross=false）← **唯一显示 transform UI**
  - `f-buy-conditions` / `f-sell-conditions`（trading，allowRef=true, allowCross=true）← 不显示 UI，但 `normalizeOperand` 仍保留 transform（防数据丢失）
  - `exit.rules[*].condition`（动态挂载，trading）← 同上
- **选股中心 `screener.js` 不用 ConditionTreeBuilder**（grep 确认 0 命中），本次不影响选股中心。
- **后端 validator 已对 trading 路径报错**，前端 UI 不显示 + 后端兜底 = 双保险。

---

## 三、Proposed Changes

### 工作流 A：因子库 transformable 元数据（engine Python + 因子库前端页面）

#### A1. `models/schemas/factor.py` 新增字段
- `FactorDef` 加 `transformable: bool = Field(False, description="是否支持 transform 滚动窗口聚合")`
- `FactorCreateRequest` / `FactorUpdateRequest` 加 `transformable: Optional[bool] = None`
- **理由**：前端据 `factor.transformable` 决定"滚动窗口"按钮可见性；用户自定义因子也能声明该能力。

#### A2. `data/factors.default.json` 标注 transformable
- **只标「业务上强烈合理」的因子**（spec 014 复核后调整，非全 true）：
  - **价格直通（RAW）**：CLOSE / HIGH / LOW / OPEN / VOLUME
  - **估值因子（TUSHARE）**：PE_TTM / PB / PS_TTM / DV_RATIO（"过去20日PE均值"是 PRD 头号用例）
  - **市值因子（TUSHARE）**：TOTAL_MV / CIRC_MV
  - **换手率（TUSHARE）**：TURNOVER_RATE
- **不标（false）的**：所有技术面指标（AKQUANT/DERIVED，如 MA/RSI/MACD/ATR）。理由：技术指标本身已是衍生量（均线/动量/波动率），对它们再做滚动聚合语义重复或荒谬（如 STDDEV 的 std、ROC 的 pct_change 是"变化的变化"）。用户若确有需求，可在因子库页面手动开启该因子的 transformable。
- **多输出因子**（MACD/BOLL/KDJ）：false（经 output_index 降维后计算仍可用，但默认不暴露，避免误导）。
- **向后兼容**：transformable 缺省 false，自定义因子可在因子库页面 CRUD 勾选开启。

#### A3. 因子库前端页面展示 + CRUD（factor-library.js / html）
用户补充要求"因子库本身也要调整，在因子库模块也能看出来这个因子支持的参数"。`transformable` 是因子能力声明，需在因子库页面可见可编辑：

- **详情面板**（[factor-library.js:213-222](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/factor-library.js) `metaItems`）：追加一行 `['transformable', f.transformable ? '是 · 支持滚动窗口聚合' : '否', false]`，让用户在因子详情页看到该因子是否支持 transform。
- **列表徽章**（[factor-library.js:172](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/factor-library.js) `multiBadge` 旁）：transformable 因子追加一个小徽章 `<span class="tf-pill">⚙ transform</span>`，与 `multi-pill` 视觉对齐。
- **新建/编辑表单**（[factor-library.html:143-200](file:///d:\lcProject\stock-pulse\stock-watcher\src\main\resources\templates\pages\factor-library.html)）：在 `multiOutput` 区域附近加一个 checkbox `id="f_transformable"`，让自定义因子能声明该能力。
- **submitFactor**（[factor-library.js:583-593](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/factor-library.js)）：payload 追加 `transformable: document.getElementById('f_transformable').checked`。
- **openEditModal**：回填时 `document.getElementById('f_transformable').checked = !!f.transformable`。
- **CSS**（factor-library.css）：追加 `.tf-pill` 徽章样式（类似 `.multi-pill`，用 `--accent-purple` 或新色）。

#### A4. 测试
- `tests/test_factor/test_registry.py`（已存在，[test_registry.py:15](file:///d:/lcProject/stock-pulse/stock-engine/tests/test_factor/test_registry.py)）追加：加载 factors.default.json 后，PE_TTM / MA / MACD 的 `transformable` 字段符合预期。
- 若需独立用例，在 `tests/test_factor/` 新建 `test_transformable.py`。

#### A5. 不改 registry.py 的 `_validate`
- `transformable` 是 bool，无需校验逻辑。

---

### 工作流 B：前端 ConditionTreeBuilder transform UI + 数据丢失修复

#### B1. `normalizeOperand` 修复数据丢失 bug（紧急）
[condition-tree-builder.js:102-126](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) factor 分支追加：
```javascript
if ('factor' in op) {
    const def = getFactorDef(op.factor);
    const norm = {
        factor: op.factor || '',
        params: op.params || getDefaultParams(def) || {},
        inputs: op.inputs || undefined,
        output_index: op.output_index != null
            ? op.output_index
            : (def && def.defaultOutputIndex != null ? def.defaultOutputIndex : 0),
    };
    if (op.transform) norm.transform = op.transform;  // ← 新增：透传 transform
    return norm;
}
```
**作用**：所有条件树（含 trading 的 buy/sell/exit）refill 时不再丢 transform，即使 UI 不显示。trading 路径的非法用法由后端 validator 兜底拦截。

#### B2. 渲染 transform 折叠控件
[condition-tree-builder.js:332-370](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) `renderExpression` 的 `kind === OP_KINDS.FACTOR` 分支，在 `outputHtml` 之后追加：
```javascript
let transformHtml = '';
if (allowTransform(operand.factor)) {  // 见 B3：检查因子是否支持 + 是否仅 screen
    const tf = operand.transform;
    const hasTf = tf && tf.type;
    transformHtml = renderTransformRow(tf, pathStr, side, hasTf);
}
```
并入模板：
```javascript
return `
    <div class="ctb-op-block">
        <div class="ctb-expr-head">${typeSelect}${bodyHtml}</div>
        ${paramsHtml}
        ${outputHtml}
        ${transformHtml}
    </div>`;
```

`renderTransformRow` 设计（折叠式）：
- **未启用**：渲染一个"⚙ 滚动窗口"按钮（`data-action="toggleTransform"`）。
- **已启用**：渲染一行：聚合类型下拉（ma/std/pct_change/max/min，中文标签）+ 窗口天数 input（min=1 max=60）+ "移除"按钮。
- 点击按钮切换展开/收起；收起时清空 `operand.transform = undefined`。

#### B3. 可见性控制 `allowTransform(factorKey)`
新增工厂内辅助：
```javascript
function allowTransform(factorKey) {
    // 仅 screen 条件树显示（allowTransform 标志由 mount 时按区域传入）
    if (!options.allowTransform) return false;
    const def = getFactorDef(factorKey);
    return !!(def && def.transformable);
}
```
- `mount(container, options)` 的 `options` 加 `allowTransform: boolean`（默认 false）。
- [strategy-editor.js:451-453](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-editor.js) `COND_AREAS`：
  - `f-screen-conditions`：`allowTransform: true`
  - `f-buy-conditions` / `f-sell-conditions`：不传（默认 false）
- [strategy-editor.js:556-561](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-editor.js) `mountConditionBuilder` 透传 `allowTransform: meta.allowTransform`。
- exit.rules 动态挂载（[strategy-editor.js:1406-1418](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/strategy-editor.js)）：不传 allowTransform（默认 false，exit 属 trading）。

#### B4. `updateLeaf` 处理 transform 变更
[condition-tree-builder.js:233-280](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/js/condition-tree-builder.js) 追加分支：
```javascript
if (field === 'toggleTransform') {
    if (operand.transform) {
        delete operand.transform;
    } else {
        operand.transform = { type: 'ma', window: 20 };
    }
    render(); notify();
    return;
}
if (field === 'transformType') {
    operand.transform = operand.transform || { type: 'ma', window: 20 };
    operand.transform.type = value;
    notify();
    return;
}
if (field === 'transformWindow') {
    operand.transform = operand.transform || { type: 'ma', window: 20 };
    operand.transform.window = Math.max(1, Math.min(60, Number(value) || 20));
    notify();
    return;
}
```

#### B5. CSS 样式
[condition-tree-builder.css](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/static/css/condition-tree-builder.css) 追加 `.ctb-transform-row` / `.ctb-transform-toggle` / `.ctb-transform-field` 样式，复用既有 `.ctb-param-field` / `.ctb-output-field` 的视觉风格（label + 小尺寸 input/select，12px 字号，高度 26px）。

#### B6. 事件绑定
- 折叠按钮的 click 走既有 `onClick` → `data-action="toggleTransform"` 分发。
- 下拉/window input 的 change 走既有 `onChange` → `data-field` 分发（`transformType` / `transformWindow`）。
- **无需新增事件监听器**，复用既有 `rootEl.addEventListener('click', onClick)` / `('change', onChange)`。

#### B7. 不改 editor.html
- 条件树容器已存在（`data-cond-visual="f-screen-conditions"`），无需改 HTML。
- `condition-tree-builder.js` / `.css` 已在 [editor.html:5,620](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/templates/quant/strategies/editor.html) 引入。

---

### 工作流 C：warmup 联动（engine Python）

#### C1. `FactorSpec` 加 transform
[compiler.py:124-146](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py)：
```python
class FactorSpec:
    __slots__ = ("factor", "params", "output_index", "transform", "cache_key")

    def __init__(self, factor, params, output_index, transform=None):
        ...
        self.transform = dict(transform) if transform else None
        # cache_key 追加 transform 后缀，与 screener.factor_signature 单一真相源
        self.cache_key = _factor_cache_key(factor, output_index, self.params or None)
        if self.transform:
            self.cache_key = f"{self.cache_key}__{self.transform['type']}{self.transform['window']}"

    def __eq__/__hash__ 同步加 transform
```

#### C2. `_collect_factor_specs` 捕获 transform
[compiler.py:149-174](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py)：
```python
if isinstance(node, FactorNode):
    spec = FactorSpec(node.factor, node.params, node.output_index, node.transform)
    ...
```

#### C3. `_infer_rebalance_warmup` 纳入 transform.window
[compiler.py:260-296](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py)：
```python
for spec in specs.values():
    w = _infer_factor_window(spec)
    # transform.window 叠加（先算因子值再聚合 window 日）
    if spec.transform and spec.transform.get("window"):
        w = w + int(spec.transform["window"])
    windows.append(w)
```
**语义**：因子本身需要 N bar 预热（如 MA20 需要 20 bar），transform.window=M 需要再额外 M bar 的因子序列来聚合 → 总 warmup = N + M。与 [factor_precompute.aggregate_series](file:///d:/lcProject/stock-pulse/stock-engine/services/screener/factor_precompute.py) 的"窗口不足返回 NaN"语义一致。

#### C4. 测试
- `tests/services/backtest/test_compiler_warmup.py`（新建或追加）：
  - 条件树含 `{factor:"MA", params:{timeperiod:20}, transform:{type:"ma",window:20}}` → 推断 warmup ≥ 40（20 因子 + 20 聚合）。
  - 无 transform 时行为不变（回归）。

---

### 工作流 D：多输出因子 + transform 验证（测试补全）

#### D1. e2e 测试
`tests/test_screener/test_factor_precompute.py` 追加：
```python
def test_precompute_multi_output_transform():
    # MACD output_index=0（DIF），transform ma(5) → 末 5 个 DIF 值的均值
    closes = [10.0 + i * 0.5 for i in range(60)]  # 60 bar，足够 MACD(12,26,9) 预热
    tree = {
        "operator": "AND",
        "conditions": [{
            "type": "compare",
            "left": {"factor": "MACD", "params": {"fastperiod":12,"slowperiod":26,"signalperiod":9},
                     "output_index": 0, "transform": {"type": "ma", "window": 5}},
            "comparator": "<", "right": {"value": 100},
        }],
    }
    candidates = {"S1": {"ohlcv_history": _flat_close_history(closes), "fundamentals": {}}}
    result = precompute_factors(tree, candidates)
    # key 形如 MACD(fastperiod=12,slowperiod=26,signalperiod=9)#0__ma5
    keys = [k for k in result["S1"] if k.startswith("MACD") and k.endswith("__ma5")]
    assert len(keys) == 1
    assert not math.isnan(result["S1"][keys[0]])
```
**证明**：`compute_single(output_index=0)` 已把 MACD 降维成 DIF 单序列，`aggregate_series` 对其聚合即可。

#### D2. PRD §1.2.2 勘误登记
012 tasks.md Self-Review 已登记：PRD §1.2.2 写"改 `_compute_one_factor`"，实际改的是共享 `precompute_factors`（ranking 不支持 transform，`_compute_one_factor` 只服务 ranking）。本计划不改 PRD 文档（PRD 不可变），但在本计划记录此勘误，避免后续混淆。

---

## 四、Assumptions & Decisions

### 决策（已与用户确认）
| # | 决策 | 选定 | 理由 |
|---|---|---|---|
| 1 | 范围 | transform UI + 全部 012 Deferred（warmup / 多输出验证 / 元数据） | 一次性闭环 012 留下的全部债务 |
| 2 | transform UI 交互 | 折叠式（默认收起，点按钮展开） | 不打扰不需要 transform 的用户；视觉最干净 |
| 3 | 控件可见性 | 仅 `f-screen-conditions` 显示 | validator 已对 trading 路径报错，前端同步收口 |
| 4 | refill 数据丢失修复 | `normalizeOperand` 一律保留 transform | trading 路径 UI 不显示但不丢字段，后端 validator 兜底 |
| 5 | 因子库元数据 | 新增 `transformable: bool` 字段 | 用户补充意见：transform 是因子的能力维度，应在因子库声明 |

### 假设
- 所有单输出因子的 transformable 标注为 true（技术面 + 基本面 + 价格直通）。多输出因子（MACD/BOLL/KDJ）也标 true（经 output_index 降维后可 transform，工作流 D 验证）。
- `transform.window` 上限 60 与 `history_window` 默认值 60 对齐（validator 已校验）；warmup 推断后 `rb_history_window = max(rebalance_warmup, 60)`（[compiler.py:619](file:///d:/lcProject/stock-pulse/stock-engine/services/backtest/compiler.py)）会自动放大，无需额外改 history_window 逻辑。
- 前端 `factors.default.json` 经 `/api/factors` 接口下发到前端（既有链路），`transformable` 字段随 FactorDef 序列化自动下发，无需新接口。

---

## 五、Verification（验证步骤）

### 5.1 后端（engine）
```bash
cd stock-engine
"D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/test_factor/test_transformable.py -v
"D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/services/backtest/test_compiler_warmup.py -v
"D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/test_screener/test_factor_precompute.py -v
"D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/services/strategy/test_validator.py -v
"D:/javaApp/miniforge/envs/stock/python.exe" -m pytest tests/services/backtest/test_rebalance_transform.py -v
```

### 5.2 前端（watcher）
- 启动应用，打开策略编辑器，新建轮动范式策略。
- 在"选股配置"Tab 的条件树添加一个条件，选因子 PE_TTM → 确认出现"⚙ 滚动窗口"按钮。
- 点击按钮 → 展开聚合类型下拉 + 窗口天数 input；填 ma / 20 → 保存。
- 重新打开该策略 → 确认 transform 字段被正确回填（refill 无丢失）。
- 切到"买入信号"Tab 添加条件 → 确认**无**滚动窗口按钮（trading 路径不显示）。
- 手改 JSON 在 buy 条件加 transform → 用编辑器打开保存 → 确认 transform 字段**保留**（不丢）但保存时后端报 `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN`（兜底）。

### 5.3 回归
- 既有策略（无 transform）打开/保存行为不变。
- 选股中心 `screener.js` 不受影响（不用 ConditionTreeBuilder）。
- 因子库页面 `/factors` 展示新字段 `transformable`。

### 5.4 验收对齐 PRD 009 §1.5
- [x] `FactorNode` 支持 `transform`（012 已交付）
- [x] 条件树可用 transform 表达「过去 N 日 X 聚合 < 阈值」（012 已交付）
- [x] 同一因子当日值与窗口聚合值共存（012 已交付）
- [x] window>60 报错（012 已交付）
- [x] transform 出现在 ranking/trading 报错（012 已交付）
- [ ] **前端条件树可配滚动窗口，collect/refill 正确**（本次交付）

---

## 六、File Change Map

| 文件 | 动作 | 工作流 |
|---|---|---|
| `stock-engine/models/schemas/factor.py` | 改：FactorDef / Create / Update 加 `transformable` | A |
| `stock-engine/data/factors.default.json` | 改：单输出 + 多输出因子标 `transformable` | A |
| `stock-engine/services/backtest/compiler.py` | 改：FactorSpec / _collect_factor_specs / _infer_rebalance_warmup | C |
| `stock-engine/tests/test_factor/test_transformable.py` | 新建 | A |
| `stock-engine/tests/services/backtest/test_compiler_warmup.py` | 新建/追加 | C |
| `stock-engine/tests/test_screener/test_factor_precompute.py` | 改：追加多输出 transform 用例 | D |
| `stock-watcher/src/main/resources/static/js/condition-tree-builder.js` | 改：normalizeOperand / renderExpression / updateLeaf / allowTransform | B |
| `stock-watcher/src/main/resources/static/css/condition-tree-builder.css` | 改：追加 transform 折叠区样式 | B |
| `stock-watcher/src/main/resources/static/js/strategy-editor.js` | 改：COND_AREAS 加 allowTransform / mountConditionBuilder 透传 | B |
| `stock-watcher/src/main/resources/static/js/factor-library.js` | 改：详情 metaItems / 列表徽章 / submitFactor / openEditModal | A |
| `stock-watcher/src/main/resources/templates/pages/factor-library.html` | 改：新建/编辑表单加 transformable checkbox | A |
| `stock-watcher/src/main/resources/static/css/factor-library.css` | 改：追加 .tf-pill 徽章样式 | A |
| `stock-watcher/src/main/java/com/arthur/stock/vo/FactorVO.java` | 改：加 transformable 字段（透传 engine → 前端，**关键**） | A |
| `stock-watcher/src/main/java/com/arthur/stock/dto/factor/FactorCreateRequestDTO.java` | 改：加 transformable 字段（前端新建透传 engine） | A |
| `stock-watcher/src/main/java/com/arthur/stock/dto/factor/FactorUpdateRequestDTO.java` | 改：加 transformable 字段（前端编辑透传 engine） | A |

**不改**：editor.html（条件树容器已存在）、screener.js（选股中心不用 ConditionTreeBuilder）、validator.py / models.py（012 已交付）、registry.py 的 _validate（transformable 无需校验）、PRD 文档（不可变）。

---

## 七、实施顺序与依赖

```
A（因子库元数据 + 因子库页面）──┐
                                ├─→ B（前端条件树 UI，依赖 A 的 transformable 字段下发）
C（warmup）─────────────────────┘    D（多输出验证，独立）
```

- **A 与 C 可并行**（不同文件，无冲突）。
- **B 依赖 A**（前端 `allowTransform` 读 `def.transformable`，需 A 先下发字段）。
- **D 独立**（纯测试补全）。
- 建议顺序：A → C → D → B（engine 先行，前端最后接）。

---

## 八、Risk & Rollback

- **风险 1**：`factors.default.json` 改动影响所有用户的因子加载。→ 字段缺省 `false`（FactorDef 默认值），旧 JSON 不带字段也兼容；运行时文件 `factors.json` 会从种子恢复或保持旧值（缺省 false）。
- **风险 2**：前端 `normalizeOperand` 改动影响所有条件树。→ 仅追加一个 `if (op.transform)` 分支，无 transform 时行为完全不变。
- **风险 3**：warmup 推断放大可能让回测变慢。→ `rb_history_window` 取 `max(rebalance_warmup, 60)`，仅当 transform.window 较大时才放大，正常用例（window≤20）影响可忽略。
- **回滚**：每个工作流独立 commit，可按工作流单独 revert。

---

## 九、前后端对接完整性分析（重点核查）

### 9.1 字段下发链路（transformable）
```
factors.default.json (A2 标注)
    ↓ FactorRegistry.reload (registry.py:81-100，既有链路)
FactorDef(transformable=True)  (A1 schema 加字段)
    ↓ /api/factors 响应（FactorListResponse[FactorDef]，既有接口）
前端 factorMeta.factors[].transformable  (strategy-editor.js:463 loadFactorMeta)
    ↓ ConditionTreeBuilder.mount(options.factors)  (condition-tree-builder.js:488)
allowTransform(factorKey) 查 def.transformable  (B3)
```
**核查结论**：链路全程既有，无需新接口。`FactorDef` 是 Pydantic 模型，加字段后 `/api/factors` 自动序列化下发；前端 `factorMeta` 已加载全量 FactorDef，`transformable` 随之下发。

### 9.2 transform 字段往返链路（前端配置 → 后端消费 → 前端回填）
```
前端 ConditionTreeBuilder.collect (B4 updateLeaf 写入 operand.transform)
    ↓ onChange 回调 → setCondByPath → state (strategy-editor.js:562)
策略 JSON screen_config.filter.conditions[].left.transform
    ↓ HTTP 保存（既有 /api/strategy/* 链路）
engine FactorNode(transform=TransformConfig)  (012 已交付 models.py:80)
    ↓ validator 校验作用域+window  (012 已交付 validator.py:684-703)
    ↓ precompute_factors 应用 aggregate_series  (012 已交付)
回测执行
    ↓ 重新打开策略 → /api/strategy/{id} → JSON
前端 getCondByPath → ConditionTreeBuilder.setTree → normalizeOperand  (B1 修复后保留 transform)
    ↓ renderExpression 渲染 transform 控件  (B2)
```
**核查结论**：往返链路完整。**关键修复点在 B1**——当前 `normalizeOperand` 在 factor 分支丢 transform，导致回填断链；B1 修复后整个闭环打通。

### 9.3 错误码对接
| 场景 | 后端错误码（012 已交付） | 前端处理 |
|---|---|---|
| trading 路径配 transform | `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN` | UI 不显示控件（B3 allowTransform=false）；手改 JSON 走后端 422 兜底 |
| window > 60 | `INVALID_TRANSFORM_WINDOW` | input max=60 前端拦截（B4），后端兜底 |
| ranking 配 transform | `TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN` | ranking 权重值是数字非 FactorNode，schema 天然禁止 |
| type 非法 | Pydantic Literal 拦截 | 下拉只列 5 个合法值，前端天然禁止 |

**核查结论**：前端 UI 约束 + 后端 validator 双保险，无漏洞。

### 9.4 warmup 联动链路（C 工作流）
```
条件树含 transform → _collect_factor_specs 捕获 (C2)
    ↓ FactorSpec.transform (C1)
_infer_rebalance_warmup 算 max(因子窗口 + transform.window) (C3)
    ↓ compiler.py:519-520
max_window → warmup = max_window + 2 (既有逻辑)
    ↓ rb_history_window = max(rebalance_warmup, 60) (compiler.py:619 既有)
akquant run_backtest(warmup_period=warmup, history_depth=...)
```
**核查结论**：仅 C1/C2/C3 三处改动，下游 history_window / warmup 推断既有逻辑自动生效。

---

## 十、UI 易用性论证（transform 控件）

### 10.1 视觉布局（对齐既有 param/output 风格）
transform 控件复用既有 `.ctb-param-field` / `.ctb-output-field` 的视觉语言（11px label、26px 高 input/select、12px 字号、column flex），保证与因子参数（timeperiod）、输出选择（outputLabels）视觉一致：

```
┌─ 因子节点（ctb-op-block）─────────────────────────┐
│ [类型▾] [因子▾]                                   │  ← expr-head（既有）
│ ┌ 周期 ──────┐                                    │  ← params（既有，如 timeperiod）
│ └────────────┘                                    │
│ ┌ 输出 [DIF▾] ┐                                   │  ← output（既有，多输出因子）
│ └────────────┘                                    │
│ [⚙ 滚动窗口]  ← 收起态按钮（B2 新增）              │
└──────────────────────────────────────────────────┘
```
展开后：
```
│ [⚙ 滚动窗口] ─ 聚合[均值▾] 窗口[20] ✕            │
```

### 10.2 交互细节
- **默认收起**：未启用 transform 的因子（绝大多数场景）只看到一个按钮，零干扰。
- **一键展开**：点"⚙ 滚动窗口"立即填默认值 `{type:"ma", window:20}`（ma=均值是最常用聚合；20 是最常用窗口，与 MA20 对齐），用户无需从空白配置。
- **即时生效**：下拉/窗口变更走既有 `onChange` 分发，不重渲染整树（只 notify），无闪烁。
- **移除即清空**：点 ✕ 删除 `operand.transform`，按钮回到收起态。
- **约束校验**：window input 设 `min=1 max=60`，与后端 `INVALID_TRANSFORM_WINDOW` 阈值一致；前端先拦，后端兜底。

### 10.3 中文标签映射（聚合类型下拉）
| value | 中文 label |
|---|---|
| ma | 均值（MA） |
| std | 标准差（波动率） |
| pct_change | 涨跌幅 |
| max | 最大值 |
| min | 最小值 |
与 PRD 009 §1.2.5 的"均值/标准差/涨跌幅/最大/最小"完全对齐。

### 10.4 因子库页面易用性
- **列表徽章** ⚙ transform：一眼看出该因子支持滚动窗口聚合，无需进详情。
- **详情面板**：明确标注"是 · 支持滚动窗口聚合"，避免用户猜。
- **CRUD 表单** checkbox：自定义因子也能声明该能力，与 multiOutput 的 checkbox 形态一致。

### 10.5 与既有功能的兼容性
- **无 transform 的策略**：`normalizeOperand` 修复仅追加一个 `if (op.transform)` 分支，无 transform 时 `op.transform` 为 undefined，行为完全不变。
- **既有因子参数（timeperiod 等）**：transform 是独立的字段，不与 params 冲突。
- **JSON 模式**：用户仍可直接编辑 JSON，transform 字段在 JSON 模式下自由编辑，切回可视化模式时被 `normalizeOperand` 保留。

### 10.6 功能完美支持核查（PRD 009 §1.5 验收逐条）
- [x] PE_TTM + transform(ma,20) → 因子库 PE_TTM 标 transformable，前端显示按钮，后端 aggregate_series 算 20 日均值（012 测试覆盖）。
- [x] 5 日涨幅 = CLOSE + transform(pct_change,5) → CLOSE transformable，下拉选"涨跌幅"，窗口填 5。
- [x] 20 日波动率 = CLOSE + transform(std,20) → CLOSE transformable，下拉选"标准差"。
- [x] 同一因子当日值与窗口值共存 → factor_signature transform 感知 key（`CLOSE` vs `CLOSE__ma20`，012 已交付）。
- [x] window>60 报错 → input max=60 前端拦 + 后端 validator 兜底。
- [x] ranking/trading 报错 → UI 不显示 + schema/validator 兜底。
- [x] **前端 collect/refill 正确** → B1+B2+B4 修复后，本计划交付。
