"""Strategy 源码落盘生成器（spec 015 §S4 / Task O-2，pickle spike）。

GRID 寻优场景需要多进程 pickle 策略类。本模块把 tunable_params 化的策略
生成为**无闭包、显式形参**的 Strategy 子类源码字符串，落盘到
``services/backtest/_generated/strat_{hash}.py``，再 ``importlib.import_module``
取类——这样 ``__module__`` 可定位、可 pickle。

约束：

- 落盘文件按 hash 缓存命中（同 config_json 不重复生成）；
- 生成的类 ``__init__`` 签名为显式 ``POSITIONAL_OR_KEYWORD`` 形参（来自
  ``tunable_params``），无 ``**kwargs``（保证 akquant
  ``_validate_strategy_param_grid_keys`` 校验生效）；
- 生成的源码禁用 ``eval`` / ``exec``（仅 import + class 定义 + on_bar 调因子计算）。

.. note::
    ``__module__ == "__main__"`` 检测（``PICKLE_IMPORT_MAIN_FORBIDDEN``，
    spec T-O-9）不在本模块做：本模块生成的类 ``__module__`` 固定为
    ``services.backtest._generated.strat_{hash}``，天然满足约束。该检测放在
    optimizer 调用 compiler 时统一处理（O-3 任务）。
"""
from __future__ import annotations

import hashlib
import importlib
import json
import logging
import os
import time
from typing import Any, Optional, Type

from akquant import Strategy

logger = logging.getLogger(__name__)

# 落盘目录的模块前缀（与 services/backtest/__init__.py 的包路径对齐）
_GENERATED_MODULE_PREFIX = "services.backtest._generated"

# 落盘文件名前缀（与 hash 拼接：strat_{hash}.py）
_FILE_PREFIX = "strat_"


# ============================================================
# 公开 API：config hash
# ============================================================

def compute_config_hash(config_json: dict) -> str:
    """对 config_json 计算稳定 hash（sha256 前 16 位 hex + ``v1`` 前缀）。

    规范化：``json.dumps(..., sort_keys=True, ensure_ascii=False)``，
    保证 dict key 顺序无关。返回形如 ``"strat_a1b2c3d4e5f6a7b8"``。

    :param config_json: 任意可 JSON 序列化的策略配置 dict。
    :return: ``"strat_" + sha256_hex[:16]``。
    """
    canonical = json.dumps(config_json, sort_keys=True, ensure_ascii=False)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
    return f"{_FILE_PREFIX}{digest}"


# ============================================================
# 公开 API：StrategySourceGenerator
# ============================================================

class StrategySourceGenerator:
    """把 tunable_params 化的策略生成为无闭包源码并落盘。

    典型用法（GRID 寻优场景）::

        gen = StrategySourceGenerator()
        cls = gen.generate(config_json, tunable_params)
        # cls.__module__ == "services.backtest._generated.strat_xxx"，可 pickle
        result_df = aq.run_grid_search(strategy=cls, param_grid=..., ...)

    落盘目录默认 ``services/backtest/_generated/``，文件名 ``strat_{hash}.py``。
    同 ``config_json`` 二次调用 ``generate`` 直接 importlib 命中缓存，不重复写盘。
    """

    def __init__(self, generated_dir: Optional[str] = None):
        """初始化落盘目录。

        :param generated_dir: 落盘目录绝对路径；None 时默认
            ``<本文件所在目录>/_generated/``。
        """
        if generated_dir is None:
            generated_dir = os.path.join(os.path.dirname(__file__), "_generated")
        self.generated_dir: str = generated_dir

    # ------------------------------------------------------------------
    # generate
    # ------------------------------------------------------------------

    def generate(
        self,
        config_json: dict,
        tunable_params: list[dict],
    ) -> Type[Strategy]:
        """生成（或从缓存命中）一个 pickle 友好的 Strategy 子类。

        流程：

        1. ``compute_config_hash`` 算 hash → ``module_name = "strat_{hash}"``；
        2. 若落盘文件已存在 → ``importlib.import_module`` 取 ``GeneratedStrategy``；
        3. 否则 ``_render_source`` 生成源码 → 落盘 → importlib 取类。

        :param config_json: 策略配置 dict（仅用于 hash，不参与源码 body）。
        :param tunable_params: ``[{"name":"fast","type":"int","default":5}, ...]``。
            ``name`` 必须是合法 Python 标识符（``str.isidentifier()``）。
        :return: ``GeneratedStrategy`` 子类。
        :raises ValueError: tunable_params 含非法标识符 name。
        """
        config_hash = compute_config_hash(config_json)
        module_name = config_hash  # "strat_xxx"
        file_path = os.path.join(self.generated_dir, f"{module_name}.py")
        full_module = f"{_GENERATED_MODULE_PREFIX}.{module_name}"

        # 缓存命中：先尝试直接 import（已加载或已落盘）
        if os.path.exists(file_path):
            try:
                mod = importlib.import_module(full_module)
                cls = getattr(mod, "GeneratedStrategy", None)
                if cls is not None and isinstance(cls, type) and issubclass(cls, Strategy):
                    return cls
                logger.warning(
                    "strat source cache hit but GeneratedStrategy missing/invalid: %s; regenerating",
                    full_module,
                )
            except Exception:  # noqa: BLE001 - 缓存模块损坏则重生成
                logger.exception(
                    "strat source cache import failed: %s; regenerating", full_module
                )

        # 生成源码 + 落盘
        self._validate_tunable_params(tunable_params)
        source = self._render_source(
            config_json=config_json,
            tunable_params=tunable_params,
            class_name="GeneratedStrategy",
            config_hash=config_hash,
        )

        os.makedirs(self.generated_dir, exist_ok=True)
        tmp_path = f"{file_path}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            f.write(source)
        os.replace(tmp_path, file_path)

        logger.info("strat source generated: %s (%s)", full_module, file_path)

        # 刷新 import：若旧模块已加载（理论上前面命中失败才会走到这里），先移除再 import
        import sys
        if full_module in sys.modules:
            del sys.modules[full_module]
        mod = importlib.import_module(full_module)
        cls = getattr(mod, "GeneratedStrategy")
        return cls

    # ------------------------------------------------------------------
    # _render_source：源码字符串生成
    # ------------------------------------------------------------------

    def _render_source(
        self,
        config_json: dict,
        tunable_params: list[dict],
        class_name: str = "GeneratedStrategy",
        config_hash: Optional[str] = None,
    ) -> str:
        """生成无闭包、形参化的 Strategy 子类源码字符串。

        ``__init__`` 形参严格来自 ``tunable_params``（``name + default``），无 ``**kwargs``。
        ``on_bar`` body 由 :func:`_select_on_bar_template` 按 name 集合匹配预置模板：

        - ``{fast, slow}`` → 双均线（金叉买、死叉卖）；
        - ``{fastperiod, slowperiod, signalperiod}`` → MACD（hist 翻正买、翻负卖）；
        - ``{timeperiod}``（且无 fast/slow/fastperiod） → RSI（30 上穿买、70 下穿卖）；
        - ``{timeperiod}`` 且 bind_to 含 BOLL → 布林带突破（突上轨买、跌破中轨卖）；
        - 其余 → 抛 :class:`StrategyNotSupportedError`（避免寻优跑空结果）。

        :return: 完整 Python 源码字符串（含 module docstring + import + class）。
        :raises StrategyNotSupportedError: tunable_params 不匹配任何预置模板。
        """
        if config_hash is None:
            config_hash = compute_config_hash(config_json)

        # __init__ 形参列表 + self.<name> 赋值
        init_params: list[str] = []
        init_assigns: list[str] = []
        param_names: list[str] = []
        for p in tunable_params:
            name = str(p["name"])
            default = p.get("default")
            py_default = _py_default_literal(default, p.get("type"))
            init_params.append(f"{name}={py_default}")
            init_assigns.append(f"        self.{name} = {name}")
            param_names.append(name)

        init_signature = ", ".join(init_params)
        init_assigns_block = "\n".join(init_assigns) if init_assigns else "        pass"

        name_set = {n for n in param_names}
        on_bar_body, warmup_expr = _select_on_bar_template(name_set, tunable_params)

        param_doc = ", ".join(param_names) if param_names else "(none)"

        return _SOURCE_TEMPLATE.format(
            config_hash=config_hash,
            param_doc=param_doc,
            class_name=class_name,
            init_signature=init_signature,
            init_assigns_block=init_assigns_block,
            warmup_expr=warmup_expr,
            on_bar_body=on_bar_body,
        )

    # ------------------------------------------------------------------
    # cleanup：清理过期落盘文件
    # ------------------------------------------------------------------

    def cleanup(self, max_age_days: int = 7) -> int:
        """清理超过 ``max_age_days`` 天的 ``strat_*.py`` 落盘文件。

        仅按文件名前缀 ``strat_`` 与修改时间判定，保留 ``__init__.py`` 等
        非 ``strat_`` 前缀文件。

        :param max_age_days: 文件最大保留天数（按 mtime 计算）。
        :return: 实际删除的文件数。
        """
        if not os.path.isdir(self.generated_dir):
            return 0
        now = time.time()
        threshold = now - max_age_days * 86400.0
        removed = 0
        import sys
        for fname in os.listdir(self.generated_dir):
            if not fname.startswith(_FILE_PREFIX) or not fname.endswith(".py"):
                continue
            fpath = os.path.join(self.generated_dir, fname)
            if not os.path.isfile(fpath):
                continue
            try:
                mtime = os.path.getmtime(fpath)
            except OSError:
                continue
            if mtime < threshold:
                module_name = fname[:-3]  # 去掉 .py
                full_module = f"{_GENERATED_MODULE_PREFIX}.{module_name}"
                try:
                    os.remove(fpath)
                    removed += 1
                    if full_module in sys.modules:
                        del sys.modules[full_module]
                except OSError:
                    logger.exception("cleanup remove failed: %s", fpath)
        if removed:
            logger.info("strat source cleanup: removed %d file(s) older than %d day(s)",
                        removed, max_age_days)
        return removed

    # ------------------------------------------------------------------
    # _validate_tunable_params：name 合法性
    # ------------------------------------------------------------------

    @staticmethod
    def _validate_tunable_params(tunable_params: list[dict]) -> None:
        """校验 tunable_params 的 name 是合法 Python 标识符且不与保留字冲突。

        :raises ValueError: name 非 str / 非合法标识符 / 是 Python 关键字。
        """
        import keyword

        if not isinstance(tunable_params, list):
            raise ValueError(
                "tunable_params must be a list[dict]"
            )
        seen: set[str] = set()
        for p in tunable_params:
            if not isinstance(p, dict) or "name" not in p:
                raise ValueError(
                    f"tunable_params entry must be dict with 'name' key; got {p!r}"
                )
            name = p["name"]
            if not isinstance(name, str) or not name.isidentifier():
                raise ValueError(
                    f"tunable_params name must be a valid Python identifier; got {name!r}"
                )
            if keyword.iskeyword(name):
                raise ValueError(
                    f"tunable_params name must not be a Python keyword; got {name!r}"
                )
            if name in seen:
                raise ValueError(
                    f"tunable_params name duplicated: {name!r}"
                )
            seen.add(name)


# ============================================================
# 源码模板与 on_bar body 片段
# ============================================================

class StrategyNotSupportedError(Exception):
    """tunable_params 不匹配任何预置 on_bar 模板（spec 015 修复：避免寻优跑空）。

    optimizer 捕获后转 ``OPTIMIZE_STRATEGY_NOT_SUPPORTED``，前端提示用户
    "当前寻优仅支持双均线 / MACD / RSI / 布林带"。
    """


def _select_on_bar_template(name_set: set, tunable_params: list[dict]) -> tuple:
    """按 tunable_params 的 name 集合**精确匹配**预置 on_bar 模板。

    .. note::
        spec 015 修复（评审问题 2）：早期实现用 ``issubset`` 匹配，会导致
        ``macd_trend_filtered`` 这类多因子策略（tunable_params 含 7 个 name）
        命中 MACD 模板，但 ``on_bar`` 只用前 3 个参数，其余参数（如
        trend_*、rsi_*）被静默忽略，用户寻优这些参数时回测结果对参数变化
        完全不敏感，却拿到"看似最优"的误导性结果。

        改为精确集合匹配（``==``）后，多因子策略会直接抛
        :class:`StrategyNotSupportedError`，让用户在 optimize.js 看到明确
        "当前寻优仅支持…"提示，不再跑空。

    :return: ``(on_bar_body, warmup_expr)``。
    :raises StrategyNotSupportedError: 不精确匹配任何模板。
    """
    # 双均线：{fast, slow}（精确匹配，避免多因子策略部分命中）
    if name_set == {"fast", "slow"}:
        return _DOUBLE_MA_ON_BAR, "max(slow, 60) + 2"
    # MACD：{fastperiod, slowperiod, signalperiod}（精确匹配）
    if name_set == {"fastperiod", "slowperiod", "signalperiod"}:
        return _MACD_ON_BAR, "max(slowperiod, signalperiod) + 5"
    # bind_to 含 BOLL 的 timeperiod → 布林带（精确匹配单个 timeperiod）
    bind_tos = {str(p.get("bind_to") or "") for p in tunable_params}
    if name_set == {"timeperiod"} and any("BOLL" in b.upper() for b in bind_tos):
        return _BOLL_ON_BAR, "max(timeperiod, 60) + 2"
    # 仅 timeperiod → 默认按 RSI（精确匹配单个 timeperiod）
    if name_set == {"timeperiod"}:
        return _RSI_ON_BAR, "max(timeperiod, 60) + 2"
    # 不精确匹配任何模板：明确报错（避免寻优跑空 10 分钟拿回被忽略参数的误导结果）
    supported = (
        "双均线(精确 {fast,slow}) / "
        "MACD(精确 {fastperiod,slowperiod,signalperiod}) / "
        "RSI(精确 {timeperiod}) / "
        "布林带(精确 {timeperiod} 且 bind_to 含 BOLL)"
    )
    raise StrategyNotSupportedError(
        f"当前 tunable_params {sorted(name_set)} 不支持寻优；"
        f"当前寻优仅支持：{supported}。"
        "多因子组合策略（如 MACD+趋势过滤+RSI）暂不支持整 Params 寻优，"
        "请在策略编辑器收缩 tunable_params 到上述精确集合，或拆分为多个独立策略寻优。"
    )


_DOUBLE_MA_ON_BAR = '''        closes = self.get_history(self.slow + 2, field="close")
        if closes is None or len(closes) < self.slow + 1:
            return
        ma_fast = talib.MA(closes, timeperiod=self.fast)
        ma_slow = talib.MA(closes, timeperiod=self.slow)
        if ma_fast is None or ma_slow is None or len(ma_fast) < 2 or len(ma_slow) < 2:
            return
        prev_diff = ma_fast[-2] - ma_slow[-2]
        curr_diff = ma_fast[-1] - ma_slow[-1]
        if prev_diff <= 0 and curr_diff > 0:
            self.order_target_percent(target_percent=0.95)
        elif prev_diff >= 0 and curr_diff < 0:
            self.close_position()'''


# spec 015 修复（老股民审查 Top1）：补齐 MACD / RSI / BOLL 模板，
# 避免 strategy_source_gen 只支持双均线、其它策略寻优全是 stub on_bar 导致空结果。
# 模板选择由 _select_on_bar_template 按 tunable_params 的 name 集合匹配，
# 不匹配时抛 StrategyNotSupportedError（optimizer 捕获后报 OPTIMIZE_STRATEGY_NOT_SUPPORTED）。
_MACD_ON_BAR = '''        closes = self.get_history(max(self.slowperiod, self.signalperiod) + 5, field="close")
        if closes is None or len(closes) < self.slowperiod + self.signalperiod:
            return
        macd, signal, hist = talib.MACD(closes, fastperiod=self.fastperiod, slowperiod=self.slowperiod, signalperiod=self.signalperiod)
        if macd is None or signal is None or len(macd) < 2:
            return
        prev_hist = hist[-2]
        curr_hist = hist[-1]
        if prev_hist <= 0 and curr_hist > 0:
            self.order_target_percent(target_percent=0.95)
        elif prev_hist >= 0 and curr_hist < 0:
            self.close_position()'''


_RSI_ON_BAR = '''        closes = self.get_history(self.timeperiod + 2, field="close")
        if closes is None or len(closes) < self.timeperiod + 1:
            return
        rsi = talib.RSI(closes, timeperiod=self.timeperiod)
        if rsi is None or len(rsi) < 2:
            return
        prev_rsi = rsi[-2]
        curr_rsi = rsi[-1]
        if prev_rsi <= 30 and curr_rsi > 30:
            self.order_target_percent(target_percent=0.95)
        elif prev_rsi >= 70 and curr_rsi < 70:
            self.close_position()'''


# 布林带突破：close 突破上轨买入、跌破中轨卖出（常见 BOLL 策略形态）
_BOLL_ON_BAR = '''        closes = self.get_history(self.timeperiod + 2, field="close")
        if closes is None or len(closes) < self.timeperiod + 1:
            return
        upper, middle, lower = talib.BBANDS(closes, timeperiod=self.timeperiod)
        if upper is None or middle is None or len(upper) < 2:
            return
        prev_close = closes[-2]
        curr_close = closes[-1]
        if prev_close <= upper[-2] and curr_close > upper[-1]:
            self.order_target_percent(target_percent=0.95)
        elif prev_close >= middle[-2] and curr_close < middle[-1]:
            self.close_position()'''


_SOURCE_TEMPLATE = '''"""Auto-generated by strategy_source_gen (spec 015 §S4). DO NOT EDIT.

config_hash: {config_hash}
tunable_params: {param_doc}
"""
from akquant import Strategy
from akquant import make_fill_policy
import akquant.talib as talib
import numpy as np


class {class_name}(Strategy):
    def __init__(self, {init_signature}):
        super().__init__()
{init_assigns_block}
        self.warmup_period = {warmup_expr}

    def on_bar(self, bar):
{on_bar_body}
'''


# ============================================================
# 辅助：default → Python 字面量
# ============================================================

def _py_default_literal(default: Any, type_hint: Optional[str]) -> str:
    """把 tunable_params 的 default 值渲染为 Python 源码字面量。

    - ``None`` → ``"None"``；
    - ``int`` / ``bool`` → ``repr``；
    - ``float`` → ``repr``（含 ``int``-like 也按 float 保留，避免被当 int）；
    - ``str`` → ``repr``（带引号）；
    - 其它类型 / 缺省 → 按 type_hint 兜底（int→0、float→0.0、str→""），
      再不行回退 ``None``。

    :param default: 原始默认值。
    :param type_hint: ``"int"`` / ``"float"`` / ``"str"`` / ``"bool"`` / None。
    :return: 可直接嵌入源码的字面量字符串。
    """
    if default is None:
        if type_hint == "int":
            return "0"
        if type_hint == "float":
            return "0.0"
        if type_hint == "str":
            return '""'
        if type_hint == "bool":
            return "False"
        return "None"
    if isinstance(default, bool):
        return repr(default)
    if isinstance(default, (int, float)):
        return repr(default)
    if isinstance(default, str):
        return repr(default)
    # 兜底
    return "None"


__all__ = ["compute_config_hash", "StrategySourceGenerator"]
