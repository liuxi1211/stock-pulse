# 4. Strategy Factory 设计

## 4.1 因子函数注册表

JSON `factor` 字段 → akquant.talib 函数 + 输入字段映射。包含基础指标、预置组合指标和常用复合命名：

```python
import akquant.talib as talib
import numpy as np


def _compute_kdj(high, low, close, **kwargs):
    """KDJ 预置封装：STOCH 计算 K/D，再算 J = 3*K - 2*D"""
    k, d = talib.STOCH(high, low, close, **kwargs)
    j = 3 * k - 2 * d
    return (k, d, j)


FACTOR_REGISTRY = {
    # ── 重叠研究（Overlap Studies）── TA-Lib 官方分类 ──
    "MA":       {"func": talib.MA,       "inputs": ["close"],                "multi_output": False},  # 支持 matype 参数，默认 0=SMA
    "EMA":      {"func": talib.EMA,      "inputs": ["close"],                "multi_output": False},
    "BOLL":     {"func": talib.BBANDS,   "inputs": ["close"],                "multi_output": True},   # factorKey=BOLL，底层函数=BBANDS
    "SAR":      {"func": talib.SAR,      "inputs": ["high", "low"],          "multi_output": False},

    # ── 动量指标（Momentum Indicators）── TA-Lib 官方分类 ──
    "MACD":     {"func": talib.MACD,     "inputs": ["close"],                "multi_output": True},
    "RSI":      {"func": talib.RSI,      "inputs": ["close"],                "multi_output": False},
    "KDJ":      {"func": _compute_kdj,   "inputs": ["high", "low", "close"], "multi_output": True},   # 预置封装：STOCH → K/D，J=3K-2D
    "ADX":      {"func": talib.ADX,      "inputs": ["high", "low", "close"], "multi_output": False},
    "PLUS_DI":  {"func": talib.PLUS_DI,  "inputs": ["high", "low", "close"], "multi_output": False},
    "MINUS_DI": {"func": talib.MINUS_DI, "inputs": ["high", "low", "close"], "multi_output": False},
    "WILLR":    {"func": talib.WILLR,    "inputs": ["high", "low", "close"], "multi_output": False},
    "CCI":      {"func": talib.CCI,      "inputs": ["high", "low", "close"], "multi_output": False},

    # ── 波动率指标（Volatility Indicators）── ──
    "ATR":      {"func": talib.ATR,      "inputs": ["high", "low", "close"], "multi_output": False},

    # ── 成交量指标（Volume Indicators）── ──
    "OBV":      {"func": talib.OBV,      "inputs": ["close", "volume"],      "multi_output": False},
    "VOL_MA":   {"func": talib.MA,       "inputs": ["volume"],               "multi_output": False},  # MA 函数输入源改为 volume
    "VOL_EMA":  {"func": talib.EMA,      "inputs": ["volume"],               "multi_output": False},  # EMA 函数输入源改为 volume
    "VOLUME":   {"func": None,           "inputs": ["volume"],               "multi_output": False},

    # ── 价格指标（自定义，非 TA-Lib 标准分类）── ──
    "CLOSE":    {"func": None,           "inputs": ["close"],                "multi_output": False},
    "HIGH":     {"func": None,           "inputs": ["high"],                 "multi_output": False},
    "LOW":      {"func": None,           "inputs": ["low"],                  "multi_output": False},
}
```

新增预置组合指标时只需：1) 写一个封装函数；2) 在 FACTOR_REGISTRY 加一条注册。不修改 Schema，不修改 Factory 逻辑。

**matype 参数透传**：MA 和 BBANDS 等 TA-Lib 函数支持 `matype` 参数（选择均线算法），JSON 配置中的 `{ "factor": "MA", "params": { "timeperiod": 5, "matype": 1 } }` 会通过 `**spec["params"]` 自动透传到 `talib.MA(close, timeperiod=5, matype=1)`，无需 Factory 特殊处理。matype 枚举值：0=SMA, 1=EMA, 2=WMA, 3=DEMA, 4=TEMA, 5=TRIMA, 6=KAMA, 7=MAMA, 8=T3。

**BOLL / BBANDS 映射**：JSON Schema 和 Java 前端使用 factorKey `"BOLL"`，Python FACTOR_REGISTRY 映射到 `talib.BBANDS`。这属于 Python 侧内部映射，Java 和前端不感知。

---

## 4.2 因子规格收集

从信号条件树中递归遍历所有表达式节点，提取唯一的因子引用规格。同一规格只计算一次，结果按缓存 key 复用。

```python
def _make_factor_key(factor_type: str, params: dict, inputs: list, output_index: int = None) -> str:
    """生成因子缓存 key，保证同一规格的因子只计算一次"""
    params_str = "_".join(f"{k}={v}" for k, v in sorted(params.items()))
    inputs_str = "_".join(inputs)
    key = f"{factor_type}_{inputs_str}_{params_str}"
    if output_index is not None:
        key += f"_out{output_index}"
    return key


def _collect_factor_specs(signals: dict) -> dict:
    """从信号条件树中递归提取所有唯一的因子引用

    Returns: {factor_key: {"type": ..., "params": ..., "inputs": [...], "output_index": ...}}
    """
    specs = {}

    def _walk_expression(node):
        """递归遍历表达式节点，收集 { "factor": ... } 引用"""
        if node is None:
            return
        if "factor" in node:
            ftype = node["factor"]
            if ftype in FACTOR_REGISTRY:
                reg = FACTOR_REGISTRY[ftype]
                inputs = node.get("inputs", reg["inputs"])
                params = node.get("params", {})
                output_index = node.get("output_index")
                key = _make_factor_key(ftype, params, inputs, output_index)
                if key not in specs:
                    specs[key] = {
                        "type": ftype, "params": params,
                        "inputs": inputs, "output_index": output_index,
                    }
        if "op" in node:
            _walk_expression(node.get("left"))
            _walk_expression(node.get("right"))

    def _walk_conditions(cond_node):
        if cond_node is None:
            return
        operator = cond_node.get("operator")
        if operator in ("AND", "OR"):
            for c in cond_node.get("conditions", []):
                _walk_conditions(c)
            return
        if cond_node.get("type") == "compare":
            _walk_expression(cond_node.get("left"))
            _walk_expression(cond_node.get("right"))

    for signal_group in signals.values():
        _walk_conditions(signal_group)

    return specs
```

---

## 4.3 因子计算

按规格表批量计算因子，返回 `{factor_key: 当前值(float)}`。

```python
def _compute_all_factors(strategy, factor_specs: dict, depth: int) -> dict:
    computed = {}

    for key, spec in factor_specs.items():
        reg = FACTOR_REGISTRY[spec["type"]]

        # 价格直取（CLOSE / HIGH / LOW / VOLUME）
        if reg["func"] is None:
            arr = strategy.get_history(1, field=spec["inputs"][0])
            computed[key] = float(arr[-1])
            continue

        # talib 计算
        input_arrays = {}
        for field in spec["inputs"]:
            input_arrays[field] = strategy.get_history(depth, field=field)

        result = reg["func"](**input_arrays, **spec["params"])

        if reg["multi_output"] and spec.get("output_index") is not None:
            computed[key] = float(result[spec["output_index"]][-1])
        elif reg["multi_output"]:
            computed[key] = float(result[0][-1])  # 默认取第一个输出
        else:
            computed[key] = float(result[-1])

    return computed
```

---

## 4.4 表达式求值

统一求值 3 种表达式节点形态（`value` / `factor` / `op`），递归处理算术运算。

```python
ARITH_OPS = {
    "+": lambda a, b: a + b,
    "-": lambda a, b: a - b,
    "*": lambda a, b: a * b,
    "/": lambda a, b: a / b if b != 0 else 0.0,
}


def _eval_expression(node: dict, computed: dict) -> float:
    """求值表达式节点，返回当前数值

    支持 3 种形态:
      - { "value": 70 }              → 静态值
      - { "factor": "RSI", ... }     → 因子引用，从 computed 中查找
      - { "op": "*", "left": ..., "right": ... } → 算术运算，递归求值
    """
    if "value" in node:
        return node["value"]

    if "factor" in node:
        ftype = node["factor"]
        reg = FACTOR_REGISTRY.get(ftype, {})
        inputs = node.get("inputs", reg.get("inputs", ["close"]))
        params = node.get("params", {})
        output_index = node.get("output_index")
        key = _make_factor_key(ftype, params, inputs, output_index)
        return computed.get(key, 0.0)

    if "op" in node:
        left_val = _eval_expression(node["left"], computed)
        right_val = _eval_expression(node["right"], computed)
        return ARITH_OPS[node["op"]](left_val, right_val)

    return 0.0
```

---

## 4.5 条件树评估

递归评估信号条件树。逻辑组合节点（AND/OR）向下递归，叶子节点（`compare`）通过 `_eval_expression` 求值左右表达式后应用比较操作符。

```python
CMP_OPS = {
    ">":  lambda a, b: a > b,
    "<":  lambda a, b: a < b,
    ">=": lambda a, b: a >= b,
    "<=": lambda a, b: a <= b,
    "==": lambda a, b: a == b,
    "!=": lambda a, b: a != b,
}


def _eval_conditions(cond_node: dict, computed: dict, prev: dict) -> bool:
    """递归评估信号条件树

    computed: 当前 bar 的因子值 {factor_key: float}
    prev:     上一根 bar 的因子值（用于 cross_up / cross_down）
    """
    if cond_node is None:
        return False

    operator = cond_node.get("operator")

    # 逻辑组合节点
    if operator in ("AND", "OR"):
        results = [_eval_conditions(c, computed, prev) for c in cond_node.get("conditions", [])]
        return all(results) if operator == "AND" else any(results)

    # 叶子节点：compare
    if cond_node.get("type") != "compare":
        return False

    comparator = cond_node["comparator"]
    left = cond_node["left"]
    right = cond_node["right"]

    # 穿越信号：需要比较当前 bar 和上一根 bar 的值
    if comparator in ("cross_up", "cross_down"):
        if not prev:  # 首根 bar 无历史值，安全降级
            return False
        left_val = _eval_expression(left, computed)
        right_val = _eval_expression(right, computed)
        prev_left = _eval_expression(left, prev)
        prev_right = _eval_expression(right, prev)
        if comparator == "cross_up":
            return left_val > right_val and prev_left <= prev_right
        else:
            return left_val < right_val and prev_left >= prev_right

    # 常规比较
    left_val = _eval_expression(left, computed)
    right_val = _eval_expression(right, computed)
    return CMP_OPS[comparator](left_val, right_val)
```

---

## 4.6 动态类工厂

```python
import akquant as aq


def build_strategy_class(config: dict, param_overrides: dict = None) -> type:
    """从 JSON 配置动态生成 akquant Strategy 子类

    param_overrides: 优化时注入参数，格式如 {"signals.buy.conditions[0].left.params.timeperiod": 10}
    """
    effective_config = _deep_merge(config, param_overrides or {})

    signals = effective_config.get("signals", {})
    pos_cfg = effective_config.get("position_sizing", {})
    depth = effective_config.get("history_depth", 60)

    # 预处理：收集所有唯一因子规格
    factor_specs = _collect_factor_specs(signals)

    # on_bar 方法（闭包捕获配置）
    def on_bar(self, bar):
        # 1. 批量计算所有因子
        computed = _compute_all_factors(self, factor_specs, depth)

        # 2. 保存上一轮因子值（用于 cross_up / cross_down）
        prev = getattr(self, "_prev_factors", {})
        self._prev_factors = computed.copy()

        # 3. 评估买卖信号
        buy_signal = _eval_conditions(signals.get("buy"), computed, prev)
        sell_signal = _eval_conditions(signals.get("sell"), computed, prev)

        # 4. 执行交易
        current_pos = self.get_position()
        if buy_signal and current_pos == 0:
            _execute_buy(self, pos_cfg)
        if sell_signal and current_pos > 0:
            _execute_sell(self, pos_cfg)

    def on_start(self):
        self.set_history_depth(depth)
        self._prev_factors = {}

    strategy_cls = type(
        f"Dynamic_{config['strategy_id']}",
        (aq.Strategy,),
        {"on_bar": on_bar, "on_start": on_start},
    )
    return strategy_cls
```

---

## 4.7 交易执行

```python
def _execute_buy(strategy, pos_cfg: dict):
    method = pos_cfg.get("method", "order_target_percent")
    if method == "order_target_percent":
        strategy.order_target_percent(pos_cfg.get("buy_percent", 0.95))
    elif method == "buy":
        strategy.buy(quantity=pos_cfg.get("buy_quantity", 100))
    elif method == "buy_all":
        strategy.buy_all()
    elif method == "order_target_value":
        strategy.order_target_value(pos_cfg.get("buy_value", 50000))


def _execute_sell(strategy, pos_cfg: dict):
    action = pos_cfg.get("sell_action", "close_position")
    if action == "close_position":
        strategy.close_position()
    elif action == "sell":
        strategy.sell(quantity=pos_cfg.get("sell_quantity", 100))
    elif action == "order_target_percent":
        strategy.order_target_percent(0)
```

---

## 4.8 cross_up / cross_down 实现原理

穿越信号需要比较**当前 bar** 和**上一根 bar** 的因子值：

```
cross_up(MA5, MA20):
  当前 bar: MA5=10.2, MA20=10.0  → MA5 > MA20 ✓
  上一根 bar: MA5=9.8, MA20=10.0 → MA5 <= MA20 ✓
  → 上穿成立

cross_down(MA5, MA20):
  当前 bar: MA5=9.8, MA20=10.0  → MA5 < MA20 ✓
  上一根 bar: MA5=10.2, MA20=10.0 → MA5 >= MA20 ✓
  → 下穿成立
```

Strategy 在每根 bar 结束时将 `computed` 保存到 `self._prev_factors`，下一根 bar 的 `on_bar` 就能取到上一轮的因子值。首根 bar 时 `prev` 为空 dict，穿越信号直接返回 False（安全降级）。

Schema 约束：cross_up / cross_down 的 left 和 right 必须都是 `{ "factor": ... }` 因子引用节点，不支持算术表达式参与穿越判断。

---

## 4.9 参数优化支持

`build_strategy_class` 接受 `param_overrides` 参数，用于 `run_grid_search` 等优化场景注入不同参数组合：

```python
def _deep_merge(base: dict, overrides: dict) -> dict:
    result = base.copy()
    for key, value in overrides.items():
        if "." in key or "[" in key:
            _set_nested(result, key, value)
        elif isinstance(value, dict) and key in result and isinstance(result[key], dict):
            result[key] = _deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def _set_nested(d: dict, path: str, value):
    import re
    keys = re.split(r'\.|\[|\]', path)
    keys = [k for k in keys if k]
    for i, k in enumerate(keys[:-1]):
        if k.isdigit():
            d = d[int(k)]
        else:
            if k not in d:
                d[k] = {}
            d = d[k]
    last = keys[-1]
    if last.isdigit():
        d[int(last)] = value
    else:
        d[last] = value
```

---

## 4.10 利用 akquant 核心能力清单

| akquant 能力 | Factory 利用方式 |
|---|---|
| `akquant.talib` Rust 指标计算 | FACTOR_REGISTRY → 表达式节点中 `factor` 字段映射 |
| `Strategy.get_history()` | 在 on_bar 中获取 numpy array 直传 talib |
| `Strategy.set_history_depth()` | on_start 中设置历史窗口深度 |
| `Strategy.order_target_percent()` | `position_sizing.method` 映射 |
| `Strategy.close_position()` | `position_sizing.sell_action` 映射 |
| `Strategy.buy()` / `buy_all()` | `position_sizing.method` 映射 |
| Python `type()` 动态类创建 | 核心机制——运行时生成 Strategy 子类 |

---

## 4.11 Schema 映射关系总览

| JSON Schema 概念 | Factory 实现 |
|---|---|
| `{ "value": N }` 静态值 | `_eval_expression` 直接返回 N |
| `{ "factor": "MA", "params": {...} }` 因子引用 | `_make_factor_key` → `_compute_all_factors` 预计算 → `computed[key]` 取值 |
| `{ "op": "*", "left": ..., "right": ... }` 算术运算 | `_eval_expression` 递归求值左右节点，应用 ARITH_OPS |
| `{ "type": "compare", "left": ..., "comparator": ">", "right": ... }` | `_eval_conditions` 求值左右表达式，应用 CMP_OPS |
| `cross_up` / `cross_down` | `_eval_conditions` 比较当前 bar 与 prev bar 的因子值 |
| `{ "operator": "AND/OR", "conditions": [...] }` | `_eval_conditions` 递归评估子条件 |
