"""JSON 策略配置 → akquant Strategy 子类编译器（spec 007-backtest-center T2）。

.. note::
    spec 008-backtest-center-phase2 T1 起，本模块的因子计算能力
    （``_compute_factor_values`` / ``_first_valid_index``）经
    :mod:`services.shared.factor_pipeline` 聚合对外暴露（``compute_latest`` /
    ``trim_leading_nan``）。本模块**保持原样**（第一波行为不变，向后兼容）；
    新代码可统一从 ``services.shared.factor_pipeline`` 取用同口径入口。

把 :class:`StrategyConfigModel` 编译成一个 ``akquant.Strategy`` 子类，在 ``on_bar``
内用 :class:`TradingConditionEngine` 求值 signals.buy / signals.sell 条件树，按
position_sizing.method 下单；exit.bracket 在场时在 ``on_before_trading`` 下括号单。

**第二波（Phase 2）支持范式**（spec 008-backtest-center-phase2）：
- ``signals`` 信号驱动（Phase 1 行为，保持不变）；
- ``rebalance`` 多因子调仓：生成 ``on_daily_rebalance``，调 RebalanceEngine 选股 +
  ``rebalance_to_topn`` 调仓；
- ``exit.rules`` 动态出场条件树：在 ``on_bar`` 末尾逐条评估（OR 短路），命中调 action；
- ``exit.bracket.use_atr_stop`` ATR 动态止损：``on_before_trading`` 按 ATR 公式计算 stop_trigger；
- signals + rebalance 混合范式（两者共存合法）。

**禁用动态代码执行**：因子计算走 ``factor_calculator``（受控命名空间），
下单走显式方法分派（白名单），无任何字符串代码执行。"""
from __future__ import annotations

import logging
import math
import os
from typing import TYPE_CHECKING, Any, Optional, Type

import akquant.talib as talib
import numpy as np
import pandas as pd
from akquant import Strategy

from services.backtest.grid_strategy import build_grid_strategy_class
from services.backtest.rebalance_engine import RebalanceEngine
from services.backtest.trading_engine import (
    BarSnapshot,
    ConditionEvalError,
    EvalContext,
    PositionCtx,
    TradingConditionEngine,
    _factor_cache_key,
)
from services.factor.calculator import factor_calculator
from services.factor.registry import factor_registry
from services.strategy.constants import POSITION_SIZING_METHODS
from services.strategy.models import (
    CompareLeaf,
    ConditionTree,
    ExitRuleModel,
    FactorNode,
    GridParamsModel,
    OpNode,
    RebalanceModel,
    RefNode,
    ScreenConfigModel,
    StrategyConfigModel,
    ValueNode,
)

if TYPE_CHECKING:
    # 仅用于类型注解（spec 010 缺陷 A 修复：watcher 只读客户端注入路径）
    from services.backtest.watcher_client import WatcherClient


# ============================================================
# 日志 / feature flag（spec 011 迭代 4）
# ============================================================

logger = logging.getLogger(__name__)

# spec 011 迭代 4：权重体系重构 feature flag。
# - False（默认）：走自托管权重新路径（cash_reserve / 单标的与行业暴露上限 /
#   buffer_n / min_holding_bars / 涨跌停拒单 / score 降序 + 诊断）。
# - True：回滚到旧 ``rebalance_to_topn`` 调用路径。
_USE_LEGACY_REBALANCE = os.environ.get("USE_LEGACY_REBALANCE", "").lower() in (
    "1",
    "true",
    "yes",
)


# ============================================================
# 异常
# ============================================================

class CompilerError(ValueError):
    """编译期错误。"""


# ============================================================
# 范式校验（Phase 2：rebalance / exit.rules / use_atr_stop 已放开）
# ============================================================

def _check_paradigm(config: StrategyConfigModel) -> None:
    """范式校验入口：signals / rebalance 互斥。

    spec 009-strategy-paradigm-exclusive：择时范式（signals）与轮动范式（rebalance）
    互斥，二者只能存在一个。validator 已在校验期拦截，此处为编译期第二道防线，
    防止直接调用 runner 绕过 validator。

    其余范式（rebalance / exit.rules / use_atr_stop）已在 008-backtest-center-phase2 放开；
    GRID / WALK_FORWARD 模式由 watcher 侧按 ``mode`` 字段判定，compiler 侧不处理。
    """
    tc = config.trading_config
    if tc is None:
        return
    if tc.signals is not None and tc.rebalance is not None:
        raise CompilerError(
            "SIGNALS_REBALANCE_EXCLUSIVE: signals 与 rebalance 不能同时在场，"
            "必须二选一（择时范式用 signals，轮动范式用 rebalance）"
        )


# ============================================================
# 因子预扫描：收集条件树里所有 FactorNode 规格
# ============================================================


# ============================================================
# 因子规格（factor + params + output_index）
# ============================================================

class FactorSpec:
    """去重后的因子规格。"""

    __slots__ = ("factor", "params", "output_index", "transform", "cache_key")

    def __init__(self, factor: str, params: Optional[dict], output_index: Optional[int],
                 transform: Optional[dict] = None):
        self.factor = factor
        self.params = dict(params) if params else {}
        self.output_index = output_index
        # transform 形如 {"type":"ma","window":20}（spec 012 P1-6），仅用于 warmup 推断；
        # 带 transform 时追加后缀到 cache_key，使同因子「当日值」与「窗口聚合值」spec 不互相覆盖
        # （trading 路径 transform 恒 None，cache_key 行为完全不变）
        self.transform = dict(transform) if transform else None
        # cache_key 与 trading_engine._factor_cache_key 单一真相源（含 params 区分 MA5/MA20）
        self.cache_key = _factor_cache_key(factor, output_index, self.params or None)
        if self.transform:
            self.cache_key = f"{self.cache_key}__{self.transform.get('type')}{self.transform.get('window')}"

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, FactorSpec):
            return NotImplemented
        return (
            self.factor == other.factor
            and self.params == other.params
            and self.output_index == other.output_index
            and self.transform == other.transform
        )

    def __hash__(self) -> int:
        tf = self.transform or {}
        return hash((self.factor, tuple(sorted(self.params.items())), self.output_index,
                     tf.get("type"), tf.get("window")))


def _collect_factor_specs(
    node: Any, acc: dict[str, FactorSpec]
) -> None:
    """递归收集 ExpressionNode / CompareLeaf / ConditionTree 内所有 FactorNode。"""
    if node is None:
        return

    # dict → 解析为模型
    if isinstance(node, dict):
        node = _parse_expr_or_node(node)

    # ExpressionNode 4 形态
    if isinstance(node, FactorNode):
        spec = FactorSpec(node.factor, node.params, node.output_index, node.transform)
        if spec.cache_key not in acc:
            acc[spec.cache_key] = spec
        return

    if isinstance(node, OpNode):
        _collect_factor_specs(node.left, acc)
        _collect_factor_specs(node.right, acc)
        return

    if isinstance(node, (ValueNode, RefNode)):
        return

    # CompareLeaf
    if isinstance(node, CompareLeaf):
        _collect_factor_specs(node.left, acc)
        _collect_factor_specs(node.right, acc)
        return

    # ConditionTree
    if isinstance(node, ConditionTree):
        for c in node.conditions or []:
            _collect_factor_specs(c, acc)
        return


def _parse_expr_or_node(node: dict) -> Any:
    """dict → ExpressionNode 或 ConditionTree/CompareLeaf（按字段形状）。"""
    if "operator" in node:
        return ConditionTree.model_validate(node)
    if node.get("type") == "compare" or "comparator" in node:
        return CompareLeaf.model_validate(node)
    # 表达式 4 形态
    if "op" in node:
        return OpNode.model_validate(node)
    if "factor" in node:
        return FactorNode.model_validate(node)
    if "ref" in node:
        return RefNode.model_validate(node)
    return ValueNode.model_validate(node)


# ============================================================
# 因子窗口推断（用于 warmup_period）
# ============================================================

def _infer_factor_window(spec: FactorSpec) -> int:
    """从因子定义推断所需最小窗口（返回 bar 数）。

    优先读 factor_registry 里的默认参数；找不到兜底 30。
    MACD/BOLL/KDJ 等多周期组合取最大周期。
    """
    factor = spec.factor
    params = spec.params

    # 直接取 timeperiod 参数（MA/EMA/RSI/ATR/CCI/WILLR/ADX 等）
    if "timeperiod" in params:
        try:
            return int(params["timeperiod"])
        except (TypeError, ValueError):
            pass

    # MACD：max(slowperiod, fastperiod) + signalperiod
    if factor == "MACD":
        slow = params.get("slowperiod", 26)
        signal = params.get("signalperiod", 9)
        try:
            return int(slow) + int(signal)
        except (TypeError, ValueError):
            return 35

    # BOLL：timeperiod
    if factor == "BOLL":
        return int(params.get("timeperiod", 20))

    # KDJ：fastk_period + slowk_period + slowd_period
    if factor == "KDJ":
        fastk = params.get("fastk_period", 9)
        try:
            return int(fastk) * 3
        except (TypeError, ValueError):
            return 27

    # 从 registry 查默认参数
    try:
        if factor_registry.exists(factor):
            fd = factor_registry.get_factor(factor)
            for p in fd.params:
                if p.name == "timeperiod":
                    return int(p.defaultValue)
            # 无 timeperiod 的（如 SAR/OBV）兜底
            return 30
    except Exception:  # noqa: BLE001 - registry 查询失败时兜底
        pass

    return 30


def _infer_rebalance_warmup(config: StrategyConfigModel) -> int:
    """推断 rebalance 范式所需的最小 warmup（bar 数）。

    扫描 ``screen_config.filter.conditions`` 条件树 + ``factor`` 因子
    （4 层结构：原 conditions/ranking 平移到 filter/factor 层；
    single.factor 或 composite.weights 的每个 key），用
    :func:`_infer_factor_window` 取最大窗口；再与默认 history_window（60）取 max。
    无 rebalance / 无 screen_config 时返回 0。
    """
    tc = config.trading_config
    if tc is None or tc.rebalance is None:
        return 0

    screen = config.screen_config
    if screen is None:
        return 0

    windows: list[int] = []

    # conditions 条件树里的因子（4 层结构：conditions 位于 filter 层）
    filter_layer = screen.filter
    if filter_layer is not None and filter_layer.conditions is not None:
        specs: dict[str, FactorSpec] = {}
        _collect_factor_specs(filter_layer.conditions, specs)
        for spec in specs.values():
            w = _infer_factor_window(spec)
            # transform.window 叠加：先算因子值（需 N bar 预热）再聚合 window 日序列，
            # 故总 warmup = 因子窗口 + transform.window（spec 014 工作流 C / spec 012 Deferred#2）
            if spec.transform and spec.transform.get("window"):
                try:
                    w = w + int(spec.transform["window"])
                except (TypeError, ValueError):
                    pass
            windows.append(w)

    # factor 打分因子（4 层结构：原 ranking 平移到 factor 层）
    factor = screen.factor
    if factor is not None:
        if factor.method == "single" and factor.factor:
            windows.append(_infer_factor_window(FactorSpec(factor.factor, None, None)))
        elif factor.method == "composite" and factor.weights:
            for fk in factor.weights.keys():
                windows.append(_infer_factor_window(FactorSpec(fk, None, None)))

    return max(windows) if windows else 0


# ============================================================
# 因子实时计算
# ============================================================

def _compute_factor_values(
    specs: dict[str, FactorSpec],
    close: np.ndarray,
    high: np.ndarray,
    low: np.ndarray,
    volume: np.ndarray,
) -> dict[str, float]:
    """对一组 FactorSpec 用 factor_calculator 计算最新一根 bar 的标量值。

    inputs 按 akquant.talib 的输入列约定构造（close/high/low/volume）。

    ⚠️ 关键：``get_history`` 在 warmup 期会用 NaN 填充 buffer 前段，而 akquant.talib
    遇到 NaN 会全程传播（TA-Lib 标准行为）。这里统一**裁掉各列的前导 NaN 段**，
    保证 talib 拿到的是连续有效数据。
    """
    if not specs:
        return {}

    # 裁掉前导 NaN（取所有列共同的有效起始点）
    close_arr = np.asarray(close, dtype=np.float64)
    high_arr = np.asarray(high, dtype=np.float64)
    low_arr = np.asarray(low, dtype=np.float64)
    volume_arr = np.asarray(volume, dtype=np.float64)

    # 各列第一个有效索引
    first_valid = _first_valid_index(close_arr, high_arr, low_arr, volume_arr)
    if first_valid > 0:
        close_arr = close_arr[first_valid:]
        high_arr = high_arr[first_valid:]
        low_arr = low_arr[first_valid:]
        volume_arr = volume_arr[first_valid:]

    inputs: dict[str, np.ndarray] = {
        "close": close_arr,
        "high": high_arr,
        "low": low_arr,
        "volume": volume_arr,
    }

    out: dict[str, float] = {}
    for key, spec in specs.items():
        try:
            arr = factor_calculator.compute_single(
                factor_key=spec.factor,
                inputs=inputs,
                params=spec.params or None,
                output_index=spec.output_index,
            )
            arr = np.asarray(arr, dtype=np.float64).ravel()
            if arr.size == 0:
                out[key] = math.nan
            else:
                v = float(arr[-1])
                out[key] = v if not (math.isnan(v) or math.isinf(v)) else math.nan
        except Exception:  # noqa: BLE001 - 单因子失败用 NaN，不阻断回测
            out[key] = math.nan
    return out


def _first_valid_index(*arrays: np.ndarray) -> int:
    """返回所有数组共同的有效起始索引（跳过任意列的 NaN 前段）。

    若无任何 NaN 返回 0；若全 NaN 返回 len-1（保留至少 1 个元素避免空数组）。
    """
    min_len = min(a.size for a in arrays) if arrays else 0
    for i in range(min_len):
        if all(not (math.isnan(a[i]) or math.isinf(a[i])) for a in arrays):
            return i
    return max(0, min_len - 1)


# ============================================================
# 下单分派（白名单，显式分支）
# ============================================================

def _dispatch_buy(strategy: Strategy, sizing: Any, symbol: str) -> None:
    """按 position_sizing.method 分派买入下单。"""
    method = sizing.method
    target = sizing.target
    params = sizing.params or {}

    if method == "order_target_percent":
        pct = float(target) if target is not None else 0.95
        strategy.order_target_percent(target_percent=pct)
    elif method == "order_target_value":
        val = float(target) if target is not None else strategy.get_cash()
        strategy.order_target_value(target_value=val)
    elif method == "order_target":
        qty = int(target) if target is not None else 100
        strategy.order_target(target=qty)
    elif method == "buy":
        qty = int(target) if target is not None else 100
        strategy.buy(quantity=qty)
    elif method == "buy_all":
        strategy.buy_all()
    elif method == "order_target_weights":
        # target 形如 {"000001.SZ": 0.5, "600000.SH": 0.5}
        weights = target if isinstance(target, dict) else params.get("weights", {})
        strategy.order_target_weights(
            weights, liquidate_unmentioned=params.get("liquidate_unmentioned", True)
        )
    else:
        raise CompilerError(
            f"BACKTEST_INVALID_POSITION_SIZING: 不支持的买入方法 '{method}'"
        )


def _dispatch_sell(strategy: Strategy, sizing: Any, symbol: str) -> None:
    """按 position_sizing.sell_method 分派卖出下单（默认 close_position）。"""
    sell_method = (sizing.sell_method or "close_position") if sizing else "close_position"
    target = sizing.target if sizing else None

    if sell_method == "close_position":
        strategy.close_position()
    elif sell_method == "sell":
        qty = int(target) if target is not None else 0
        if qty > 0:
            strategy.sell(quantity=qty)
        else:
            strategy.close_position()
    elif sell_method == "signal_based":
        strategy.close_position()
    else:
        raise CompilerError(
            f"BACKTEST_INVALID_SELL_METHOD: 不支持的卖出方法 '{sell_method}'"
        )


# ============================================================
# spec 015 FR-G2 / FR-G3：grid 范式编译入口
# ============================================================

def _compile_grid_strategy(config: StrategyConfigModel) -> Type[Strategy]:
    """网格范式编译入口（spec 015 FR-G2 / FR-G3）。

    从 ``trading_config.position_sizing.params`` 取 grid 参数 dict →
    :class:`GridParamsModel`，再调 :func:`build_grid_strategy_class`
    生成闭包绑定的 ``GridStrategy`` 子类。

    grid 范式单标的：symbol 取自 ``screen_config.universe.stocks[0]``
    （validator 已校验 manual 池且非空）。

    warmup 透传由 runner 负责（grid 不需要因子窗口，warmup=1 即可）；
    本函数只返回策略类。

    :param config: 顶层策略配置（已通过 validator）。
    :return: ``Strategy`` 子类（``__name__ == "GridStrategy"``）。
    :raises CompilerError: position_sizing.params 缺失 / universe 无标的。
    """
    tc = config.trading_config
    if tc is None or tc.position_sizing is None:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: grid 范式要求 trading_config.position_sizing 在场"
        )
    sizing = tc.position_sizing
    params = sizing.params
    if not params:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: grid 范式要求 position_sizing.params 在场"
        )
    try:
        grid_params = GridParamsModel.model_validate(params)
    except Exception as exc:  # noqa: BLE001 - 结构错误统一报 CompilerError
        raise CompilerError(
            f"BACKTEST_GRID_PARAMS_INVALID: grid 参数校验失败 - {exc}"
        ) from exc

    # initial_cash（默认 100000）
    initial_cash = 100000.0
    if config.backtest_config is not None:
        try:
            initial_cash = float(config.backtest_config.initial_cash)
        except (TypeError, ValueError):
            initial_cash = 100000.0

    # symbol：grid 范式单标的，取 universe.stocks[0]
    symbol: Optional[str] = None
    if (
        config.screen_config is not None
        and config.screen_config.universe is not None
        and config.screen_config.universe.stocks
    ):
        symbol = str(config.screen_config.universe.stocks[0])
    if not symbol:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: grid 范式要求 screen_config.universe.stocks 非空（单标的）"
        )

    return build_grid_strategy_class(
        grid_params=grid_params,
        initial_cash=initial_cash,
        symbol=symbol,
    )


# ============================================================
# 编译主函数
# ============================================================

def compile_strategy(
    config: StrategyConfigModel,
    *,
    universe_symbols: Optional[list[str]] = None,
    watcher_client: Optional["WatcherClient"] = None,
    extra_map: Optional[dict[str, dict[str, dict[str, float]]]] = None,
) -> Type[Strategy]:
    """JSON config → akquant Strategy 子类。

    Phase 2 支持 signals / rebalance / exit.rules / use_atr_stop 及混合范式。

    :param config: 策略配置模型。
    :param universe_symbols: 可选，watcher 经 HTTP 传入的全 universe 标的列表
       （即 ``kline_data`` 的 keys）。rebalance 范式下用于发现调仓候选池；
        非 manual universe（csi300/csi500）时 ``screen_config.universe.stocks`` 为空，
        必须靠此参数才能遍历全池，否则 rebalance 会锁死在初始持仓。signals-only
        范式可省略。
    :param watcher_client: 可选的 watcher 只读客户端（spec 010 缺陷 A 修复）。
        rebalance 范式下传给 ``RebalanceEngine.select_at_rebalance_date`` 做
        point-in-time 成分股过滤；None 时降级为全量候选。
    :param extra_map: 可选的 ``{symbol: {trade_date_str: {field: float}}}``
        基本面 map（spec 010 缺陷 B 修复），传给 RebalanceEngine 供 TUSHARE
        因子补算；None 时基本面因子回退为 NaN。

    返回一个动态生成的 ``Strategy`` 子类（用 class 定义 + 闭包绑定，无动态代码执行）。
    """
    # 1. 范式校验（占位，Phase 2 无拒绝项）
    _check_paradigm(config)

    # spec 015 FR-G2：method=grid 走独立 GridStrategy 链路，不进入 signals/rebalance 主编译
    tc = config.trading_config
    if (
        tc is not None
        and tc.position_sizing is not None
        and tc.position_sizing.method == "grid"
    ):
        return _compile_grid_strategy(config)

    if tc is None:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: trading_config 缺失"
        )

    # 2. 解析 signals / rebalance（至少一个在场，否则无交易逻辑）
    signals = tc.signals
    rebalance = tc.rebalance
    # has_signals 口径与 validator._validate_structure_trading 对齐：
    # 只��� signals 对象存在即算"在场"（即便内部 buy/sell 都为 None）。
    # 不再附加 "buy/sell 至少一个非 None" 条件，避免空 signals 对象同时绕过
    # MISSING / EXCLUSIVE / position_sizing 强制校验三道约束。
    has_signals = signals is not None
    has_rebalance = rebalance is not None

    if not has_signals and not has_rebalance:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: trading_config 至少需要 signals 或 rebalance 之一"
        )

    # 互斥校验由入口 _check_paradigm 完成（基于 tc.signals/tc.rebalance 对象存在性），
    # 到此处的配置已通过互斥校验；若绕过 _check_paradigm 直接进入编译，下面的
    # position_sizing / on_bar 生成逻辑对共存配置会产生未定义行为，故不在此重复判断。

    buy_tree = signals.buy if (signals is not None) else None
    sell_tree = signals.sell if (signals is not None) else None
    sizing = tc.position_sizing

    # signals 范式需要 position_sizing（rebalance-only 范式可不填）
    if has_signals:
        if sizing is None:
            raise CompilerError(
                "BACKTEST_CONFIG_INVALID: signals 范式要求 trading_config.position_sizing 在场"
            )
        if sizing.method not in POSITION_SIZING_METHODS:
            raise CompilerError(
                f"BACKTEST_INVALID_POSITION_SIZING: position_sizing.method='{sizing.method}' "
                f"不在白名单 {sorted(POSITION_SIZING_METHODS)}"
            )

    # 3. 预扫描 signals 因子规格
    factor_specs: dict[str, FactorSpec] = {}
    if buy_tree is not None:
        _collect_factor_specs(buy_tree, factor_specs)
    if sell_tree is not None:
        _collect_factor_specs(sell_tree, factor_specs)

    # 4. 推断 warmup_period（signals 窗口 + rebalance 窗口取 max）
    max_window = 60  # 兜底
    for spec in factor_specs.values():
        w = _infer_factor_window(spec)
        if w > max_window:
            max_window = w
    rebalance_warmup = _infer_rebalance_warmup(config)
    if rebalance_warmup > max_window:
        max_window = rebalance_warmup
    # cross_* 需要额外一根历史
    warmup = max_window + 2

    # spec 011 P2-5：warmup 来源判定（透传到 result_serializer.effective_config）
    # - auto_inferred：compiler 自动推断值（max_window + 2）
    # - user_override：用户在 backtest_config.warmup_period 显式指定的值
    # - 最终生效值取二者较大（akquant run_backtest 内部 effective_depth = max(warmup_period, history_depth)）
    user_warmup = (
        int(config.backtest_config.warmup_period)
        if (config.backtest_config is not None
            and config.backtest_config.warmup_period is not None)
        else None
    )
    if user_warmup is not None and user_warmup > warmup:
        warmup_source = "user_override"
        effective_warmup = user_warmup
    else:
        warmup_source = "auto_inferred"
        effective_warmup = warmup
    warmup_reason = f"max(user={user_warmup}, auto_inferred={warmup})"

    # 5. exit 配置（bracket + rules）
    exit_cfg = tc.exit
    bracket = exit_cfg.bracket if (exit_cfg is not None) else None
    stop_loss_pct = bracket.stop_loss_pct if bracket else None
    take_profit_pct = bracket.take_profit_pct if bracket else None
    use_atr_stop = bool(bracket.use_atr_stop) if (bracket and bracket.use_atr_stop) else False
    atr_period = int(bracket.atr_period) if (bracket and bracket.atr_period is not None) else 14
    atr_multiplier = bracket.atr_multiplier if bracket else None

    # T2.4: use_atr_stop=true 时 atr_multiplier 必填（编译期校验）
    if use_atr_stop and atr_multiplier is None:
        raise CompilerError(
            "ATR_MULTIPLIER_REQUIRED: use_atr_stop=true 时 atr_multiplier 必填"
        )

    exit_rules = exit_cfg.rules if (exit_cfg is not None) else None
    has_exit_rules = exit_rules is not None and len(exit_rules) > 0

    # 6. rebalance 预捕获（编译期确定，闭包绑定）
    rebalance_engine: Optional[RebalanceEngine] = None
    screen_config: Optional[ScreenConfigModel] = None
    rb_frequency: str = ""
    # spec 011 P2-1：trigger(first/last) 替代 day_of_period。
    # None 表示"未指定"，下游 _is_rebalance_day 内规约为 "first"。
    rb_trigger: Optional[str] = None
    rb_top_n: int = 0
    rb_weight_mode: str = "equal"
    rb_long_only: bool = True
    rb_liquidate_unmentioned: bool = True
    rb_history_window: int = 60
    # spec 011 迭代 4：权重体系字段（自托管权重路径用）
    rb_cash_reserve: float = 0.0
    rb_max_weight_per_symbol: Optional[float] = None
    rb_max_industry_exposure: Optional[float] = None
    rb_buffer_n: int = 0
    rb_min_holding_bars: int = 0
    rb_reject_limit_up_on_buy: bool = True
    rb_reject_limit_down_on_sell: bool = True
    rb_split_days: int = 1
    rb_impact_cost_bps: Optional[float] = None

    if has_rebalance:
        rebalance_engine = RebalanceEngine()
        screen_config = config.screen_config
        if screen_config is None:
            raise CompilerError(
                "BACKTEST_CONFIG_INVALID: rebalance 范式要求 screen_config 在场"
            )
        # 4 层结构：top_n 位于 portfolio 层
        portfolio_layer = screen_config.portfolio
        sc_top_n = portfolio_layer.top_n if portfolio_layer is not None else None
        if sc_top_n is None or sc_top_n <= 0:
            raise CompilerError(
                "BACKTEST_CONFIG_INVALID: rebalance 范式要求 screen_config.portfolio.top_n 正整数"
            )
        rb_frequency = rebalance.frequency if rebalance else "daily"
        # spec 011 P2-1：优先用 trigger；旧 JSON 的 day_of_period 兼容映射。
        rb_trigger = _resolve_rebalance_trigger(rebalance)
        rb_top_n = int(sc_top_n)
        rb_weight_mode = rebalance.weight_mode if (rebalance and rebalance.weight_mode) else "equal"
        rb_long_only = rebalance.long_only if (rebalance and rebalance.long_only is not None) else True
        # spec 011 P0-2 后端兜底：factor.method=single × weight_mode=score 不兼容
        # （validator 已校验，此处防止绕过 validator 直接调 compiler）
        sc_factor = screen_config.factor
        if (
            rb_weight_mode == "score"
            and sc_factor is not None
            and sc_factor.method == "single"
        ):
            raise CompilerError(
                "FACTOR_SCORE_INCOMPATIBLE: factor.method=single 与 "
                "rebalance.weight_mode=score 不兼容；"
                "请改用 weight_mode=equal 或 factor.method=composite"
            )
        # replace_method: None 或 "full" → 全换（liquidate_unmentioned=True）；"incremental" → 只换差额
        replace_method = rebalance.replace_method if rebalance else None
        rb_liquidate_unmentioned = (replace_method != "incremental")
        rb_history_window = max(rebalance_warmup, 60)
        # spec 011 迭代 4：权重体系字段捕获
        rb_cash_reserve = float(
            portfolio_layer.cash_reserve_pct if portfolio_layer is not None else None
        ) if (portfolio_layer is not None and portfolio_layer.cash_reserve_pct is not None) else 0.0
        rb_max_weight_per_symbol = (
            portfolio_layer.max_weight_per_symbol
            if portfolio_layer is not None
            else None
        )
        rb_max_industry_exposure = (
            portfolio_layer.max_industry_exposure
            if portfolio_layer is not None
            else None
        )
        rb_buffer_n = int(
            portfolio_layer.buffer_n
            if (portfolio_layer is not None and portfolio_layer.buffer_n is not None)
            else 0
        )
        rb_min_holding_bars = int(
            rebalance.min_holding_bars
            if (rebalance and rebalance.min_holding_bars is not None)
            else 0
        )
        rb_reject_limit_up_on_buy = (
            rebalance.reject_limit_up_on_buy if rebalance else True
        )
        rb_reject_limit_down_on_sell = (
            rebalance.reject_limit_down_on_sell if rebalance else True
        )
        rb_split_days = (
            rebalance.execution.split_days
            if (rebalance.execution is not None)
            else 1
        )
        rb_impact_cost_bps = (
            rebalance.execution.impact_cost_bps
            if (rebalance.execution is not None)
            else None
        )
        # universe_symbols：watcher 传入的全池（非 manual universe 时为唯一可靠来源）
        rb_universe_symbols: Optional[list[str]] = (
            list(universe_symbols) if universe_symbols else None
        )

    # 7. 预捕获的条件求值引擎（无状态，闭包共享；不存为实例属性 _engine 以免与 akquant 内核注入冲突）
    cond_engine = TradingConditionEngine()

    # 8. 动态构造 Strategy 子类（闭包绑定配置）
    class _CompiledStrategy(Strategy):
        # 类级配置（akquant 读 warmup_period 类属性）
        warmup_period = warmup
        # spec 011 P2-5：effective warmup 元信息（runner/serializer 读取）
        _akw_effective_warmup = effective_warmup
        _akw_warmup_source = warmup_source
        _akw_warmup_reason = warmup_reason

        def __init__(self):
            super().__init__()
            # 实例级状态（注意：避开 akquant 占用的 _engine / _use_engine_* 等属性名）
            self._factor_specs = factor_specs
            self._buy_tree = buy_tree
            self._sell_tree = sell_tree
            self._sizing = sizing
            self._bracket_stop = stop_loss_pct
            self._bracket_take = take_profit_pct
            self._cond_engine = cond_engine
            # 上一根 bar 的 snapshot 缓存（cross_* 用）
            self._prev_snapshot: Optional[BarSnapshot] = None
            # 已下 bracket 单的 symbol 集合（避免重复下单）
            self._bracket_placed: set[str] = set()
            # exit.rules
            self._exit_rules = exit_rules
            # use_atr_stop
            self._use_atr_stop = use_atr_stop
            self._atr_period = atr_period
            self._atr_multiplier = atr_multiplier
            # ATR 历史 OHLCV 缓存（symbol → {"close":[...],"high":[...],"low":[...]}），
            # on_bar 末尾追加，on_before_trading 读取，避免重复 get_history
            self._atr_cache: dict[str, dict[str, list[float]]] = {}
            # rebalance
            self._rb_engine = rebalance_engine
            self._screen_config = screen_config
            self._rb_frequency = rb_frequency
            self._rb_trigger = rb_trigger
            self._rb_top_n = rb_top_n
            self._rb_weight_mode = rb_weight_mode
            self._rb_long_only = rb_long_only
            self._rb_liquidate_unmentioned = rb_liquidate_unmentioned
            self._rb_history_window = rb_history_window
            # spec 011 迭代 4：权重体系字段
            self._rb_cash_reserve = rb_cash_reserve
            self._rb_max_weight_per_symbol = rb_max_weight_per_symbol
            self._rb_max_industry_exposure = rb_max_industry_exposure
            self._rb_buffer_n = rb_buffer_n
            self._rb_min_holding_bars = rb_min_holding_bars
            self._rb_reject_limit_up_on_buy = rb_reject_limit_up_on_buy
            self._rb_reject_limit_down_on_sell = rb_reject_limit_down_on_sell
            self._rb_split_days = rb_split_days
            self._rb_impact_cost_bps = rb_impact_cost_bps
            # P0-5 诊断容器（最后一次调仓的诊断信息；result_serializer 读取）
            self._rb_diagnosis: dict[str, Any] = {}

        # ------------------------------------------------------------------
        # 因子预计算 → snapshot
        # ------------------------------------------------------------------

        def _build_snapshot(self, bar) -> BarSnapshot:
            """取历史 → 算因子 → 构造当前 bar 的 BarSnapshot。"""
            snap = BarSnapshot(bar=bar, factor_values={})
            if not self._factor_specs:
                return snap

            window = max(
                (_infer_factor_window(s) for s in self._factor_specs.values()),
                default=30,
            ) + 5  # 多取几根避免边界
            try:
                close = self.get_history(window, field="close")
                high = self.get_history(window, field="high")
                low = self.get_history(window, field="low")
                volume = self.get_history(window, field="volume")
            except Exception:  # noqa: BLE001 - 历史不足时跳过
                return snap

            vals = _compute_factor_values(self._factor_specs, close, high, low, volume)
            snap.factor_values = vals
            return snap

        def _build_position_ctx(self, symbol: str) -> PositionCtx:
            """从 akquant 持仓接口读 ref 上下文。

            entry_price 走 akquant ``get_position_entry_price(symbol)``（多标的可用），
            position_pnl_pct 自算 ``(close - entry) / entry``。
            旧实现误从 ``get_account()`` dict 取这两字段，而该 dict 不含它们 → 恒 0.0，
            导致 bracket 与 exit.rules 盈亏条件失效（Bug #1）。
            """
            pos = PositionCtx()
            try:
                qty = float(self.get_position(symbol) or 0.0)
                pos.position_qty = qty
                if qty != 0:
                    pos.bars_held = int((self.hold_bar(symbol) or 0))
                    entry = self._get_position_entry_price(symbol)
                    if entry and entry > 0:
                        pos.entry_price = float(entry)
                        close = self._current_close_for(symbol)
                        if close and close > 0:
                            pos.position_pnl_pct = (close - entry) / entry
            except Exception:  # noqa: BLE001
                pass
            return pos

        def _get_position_entry_price(self, symbol: str) -> Optional[float]:
            """取指定 symbol 的持仓均价（多标的安全）。"""
            ctx = getattr(self, "ctx", None)
            getter = getattr(ctx, "get_position_entry_price", None)
            if getter is None:
                # 仅当前 symbol 可走 self.position
                try:
                    if symbol == self.symbol:
                        return float(self.position.entry_price)
                except Exception:  # noqa: BLE001
                    return None
                return None
            try:
                val = getter(symbol)
                return float(val) if val else None
            except Exception:  # noqa: BLE001
                return None

        def _current_close_for(self, symbol: str) -> Optional[float]:
            """取指定 symbol 当前 bar 收盘价（多标的：从 get_history_df 取末值）。"""
            try:
                if symbol == getattr(self, "symbol", None):
                    return float(self.close)
            except Exception:  # noqa: BLE001
                pass
            try:
                df = self.get_history_df(count=1, symbol=symbol)
                if df is None or len(df) == 0:
                    return None
                return float(df["close"].iloc[-1])
            except Exception:  # noqa: BLE001
                return None

        # ------------------------------------------------------------------
        # 主回调
        # ------------------------------------------------------------------

        def on_bar(self, bar):
            symbol = bar.symbol
            # 构造当前 snapshot
            cur_snap = self._build_snapshot(bar)
            pos_ctx = self._build_position_ctx(symbol)
            ctx = EvalContext(current=cur_snap, prev=self._prev_snapshot, position=pos_ctx)

            # 求值 sell（优先：先平仓再开仓，避免资金占用）
            sell_hit = False
            try:
                if self._sell_tree is not None:
                    sell_hit = self._cond_engine.evaluate_tree(self._sell_tree, ctx)
                    if sell_hit:
                        _dispatch_sell(self, self._sizing, symbol)
                        # 卖出后清 bracket 标记
                        self._bracket_placed.discard(symbol)
            except ConditionEvalError:
                pass

            # 求值 buy
            try:
                if self._buy_tree is not None:
                    buy_hit = self._cond_engine.evaluate_tree(self._buy_tree, ctx)
                    if buy_hit:
                        _dispatch_buy(self, self._sizing, symbol)
            except ConditionEvalError:
                pass

            # exit.rules 评估（仅对当前有持仓的 symbol；OR 短路）
            if self._exit_rules:
                self._eval_exit_rules(symbol, ctx)

            # 更新 prev snapshot（供下一根 cross_*）
            self._prev_snapshot = cur_snap

            # ATR 缓存追加：use_atr_stop 时把当前 bar 的 OHLCV 追加到缓存，
            # 供 on_before_trading 计算下一根的 ATR（避免重复 get_history）
            if self._use_atr_stop:
                self._append_atr_cache(symbol, bar)

        def _append_atr_cache(self, symbol: str, bar) -> None:
            """把当前 bar 的 high/low/close 追加到 ATR 缓存（仅 use_atr_stop 时调用）。"""
            cache = self._atr_cache.get(symbol)
            if cache is None:
                cache = {"close": [], "high": [], "low": []}
                self._atr_cache[symbol] = cache
            cache["close"].append(float(bar.close))
            cache["high"].append(float(bar.high))
            cache["low"].append(float(bar.low))
            # 限制缓存长度到 atr_period + 10（够 ATR 计算即可，避免无限增长）
            max_keep = self._atr_period + 10
            if len(cache["close"]) > max_keep:
                for k in ("close", "high", "low"):
                    cache[k] = cache[k][-max_keep:]

        def _get_atr_history(
            self, symbol: str, window: int
        ) -> tuple[Optional[list], Optional[list], Optional[list]]:
            """取 ATR 计算所需的历史 OHLCV（close/high/low）。

            优先读 ``_atr_cache``（on_bar 末尾追加，按 symbol 存储）；
            缓存长度不足（首日/预热期/未覆盖该 symbol）时 fallback 到
            ``get_history(window, symbol=symbol, field=...)``。

            :return: ``(close_list, high_list, low_list)``，任一不足返回 ``(None, None, None)``。
            """
            cache = self._atr_cache.get(symbol)
            if cache is not None and len(cache["close"]) >= self._atr_period + 1:
                # 缓存足够：取最近 window 根
                n = min(window, len(cache["close"]))
                return (
                    cache["close"][-n:],
                    cache["high"][-n:],
                    cache["low"][-n:],
                )
            # fallback：缓存不足时从 akquant 取
            try:
                close_hist = self.get_history(window, symbol=symbol, field="close")
                high_hist = self.get_history(window, symbol=symbol, field="high")
                low_hist = self.get_history(window, symbol=symbol, field="low")
                if close_hist is None or len(close_hist) < self._atr_period + 1:
                    return None, None, None
                return (
                    list(close_hist),
                    list(high_hist) if high_hist is not None else None,
                    list(low_hist) if low_hist is not None else None,
                )
            except Exception:  # noqa: BLE001
                return None, None, None

        def _eval_exit_rules(self, symbol: str, ctx: EvalContext) -> None:
            """逐条评估 exit.rules，任一命中（OR 短路）即 dispatch action。"""
            # 无持仓时跳过（exit.rules 针对持仓）
            try:
                pos_qty = float(self.get_position(symbol) or 0.0)
            except Exception:  # noqa: BLE001
                pos_qty = 0.0
            if pos_qty <= 0:
                return

            rule: ExitRuleModel
            for rule in self._exit_rules:
                try:
                    hit = self._cond_engine.evaluate_tree(rule.condition, ctx)
                except ConditionEvalError:
                    hit = False
                except Exception:  # noqa: BLE001 - 单条规则异常不阻断
                    hit = False
                if hit:
                    self._dispatch_exit_action(rule.action, symbol)
                    # 命中后不再评估后续（OR 短路）
                    break

        def _dispatch_exit_action(self, action: Optional[str], symbol: str) -> None:
            """exit.rules 命中后的 action 分派。

            action=None 或 "close_position" → ``close_position()``；
            "sell" → 按 position_sizing.target 卖出指定数量，无 target 则全平。
            """
            if action is None or action == "close_position":
                try:
                    self.close_position()
                except Exception:  # noqa: BLE001
                    pass
                return
            if action == "sell":
                sizing = self._sizing
                target = sizing.target if sizing else None
                try:
                    qty = int(target) if target is not None else 0
                    if qty > 0:
                        self.sell(quantity=qty)
                    else:
                        self.close_position()
                except Exception:  # noqa: BLE001
                    pass
                return
            # 其他 action 名暂按 close_position 处理（保守，避免未知动作导致不退出）
            try:
                self.close_position()
            except Exception:  # noqa: BLE001
                pass

        def on_before_trading(self, date, ts):
            """exit.bracket 在场时对持仓下括号单（支持静态 stop_loss_pct 与 ATR 动态止损）。"""
            # 无 bracket 配置时直接返回
            is_bracket = (
                self._use_atr_stop
                or self._bracket_stop is not None
                or self._bracket_take is not None
            )
            if not is_bracket:
                return
            try:
                symbols = self.get_positions() or {}
            except Exception:  # noqa: BLE001
                symbols = {}

            # ATR 动态止损需要的历史窗口
            atr_window = max(self._atr_period + 5, 30) if self._use_atr_stop else 0

            for sym, qty in symbols.items():
                if qty is None or float(qty) <= 0:
                    continue
                if sym in self._bracket_placed:
                    continue
                # 取 entry_price（best effort）
                pos_ctx = self._build_position_ctx(sym)
                entry = pos_ctx.entry_price
                if not entry or entry <= 0:
                    continue

                # 计算 stop_trigger：use_atr_stop 优先，否则用静态 stop_loss_pct
                if self._use_atr_stop:
                    # 优先读 ATR 缓存（on_bar 末尾追加，按 symbol 存储）；
                    # 缓存不足（首日/预热期）fallback 到 get_history(symbol=sym)
                    close_hist, high_hist, low_hist = self._get_atr_history(sym, atr_window)
                    if close_hist is None or high_hist is None or low_hist is None:
                        continue  # 历史不足，跳过
                    try:
                        atr_arr = talib.ATR(
                            np.asarray(high_hist, dtype=np.float64),
                            np.asarray(low_hist, dtype=np.float64),
                            np.asarray(close_hist, dtype=np.float64),
                            timeperiod=self._atr_period,
                        )
                        atr = float(atr_arr[-1])
                    except Exception:  # noqa: BLE001
                        continue
                    if math.isnan(atr) or atr <= 0:
                        continue  # ATR 无效，跳过该 symbol
                    stop_price = entry - float(self._atr_multiplier) * atr
                else:
                    stop_price = entry * (1.0 - (self._bracket_stop or 0.0))

                take_price = entry * (1.0 + (self._bracket_take or 0.0))
                try:
                    self.place_bracket_order(
                        symbol=sym,
                        quantity=int(qty),
                        stop_trigger_price=stop_price,
                        take_profit_price=take_price,
                    )
                    self._bracket_placed.add(sym)
                except Exception:  # noqa: BLE001 - bracket 单失败不阻断
                    pass

    # T2.2 / T2.5: rebalance 在场时动态挂载 on_daily_rebalance 方法
    if has_rebalance:
        _attach_rebalance_method(
            _CompiledStrategy,
            frequency=rb_frequency,
            trigger=rb_trigger,
            history_window=rb_history_window,
            top_n=rb_top_n,
            weight_mode=rb_weight_mode,
            long_only=rb_long_only,
            liquidate_unmentioned=rb_liquidate_unmentioned,
            screen_config=screen_config,
            rebalance_engine=rebalance_engine,
            universe_symbols=rb_universe_symbols,
            watcher_client=watcher_client,
            extra_map=extra_map,
            # spec 011 迭代 4：权重体系字段
            cash_reserve=rb_cash_reserve,
            max_weight_per_symbol=rb_max_weight_per_symbol,
            max_industry_exposure=rb_max_industry_exposure,
            buffer_n=rb_buffer_n,
            min_holding_bars=rb_min_holding_bars,
            reject_limit_up_on_buy=rb_reject_limit_up_on_buy,
            reject_limit_down_on_sell=rb_reject_limit_down_on_sell,
            use_legacy_rebalance=_USE_LEGACY_REBALANCE,
            split_days=rb_split_days,
            impact_cost_bps=rb_impact_cost_bps,
        )

    # 重命名类（便于日志/调试）
    safe_name = (config.name or "CompiledStrategy").replace(" ", "_")
    _CompiledStrategy.__name__ = f"Strategy_{safe_name}"
    _CompiledStrategy.__qualname__ = _CompiledStrategy.__name__
    # 同步更新动态挂载方法的 qualname（_attach_rebalance_method 在重命名前调用，
    # 此处补正，确保异常栈显示 Strategy_xxx.on_daily_rebalance 而非 _CompiledStrategy.<locals>）
    if has_rebalance and hasattr(_CompiledStrategy, "on_daily_rebalance"):
        m = _CompiledStrategy.on_daily_rebalance
        m.__qualname__ = f"{_CompiledStrategy.__name__}.on_daily_rebalance"
        m.__name__ = "on_daily_rebalance"
    return _CompiledStrategy


# ============================================================
# rebalance 方法挂载（T2.2 / T2.5）
# ============================================================

def _attach_rebalance_method(
    strategy_cls: type,
    *,
    frequency: str,
    trigger: Optional[str],
    history_window: int,
    top_n: int,
    weight_mode: str,
    long_only: bool,
    liquidate_unmentioned: bool,
    screen_config: Optional[ScreenConfigModel],
    rebalance_engine: Optional[RebalanceEngine],
    universe_symbols: Optional[list[str]] = None,
    watcher_client: Optional["WatcherClient"] = None,
    extra_map: Optional[dict[str, dict[str, dict[str, float]]]] = None,
    # spec 011 迭代 4：权重体系参数
    cash_reserve: float = 0.0,
    max_weight_per_symbol: Optional[float] = None,
    max_industry_exposure: Optional[float] = None,
    buffer_n: int = 0,
    min_holding_bars: int = 0,
    reject_limit_up_on_buy: bool = True,
    reject_limit_down_on_sell: bool = True,
    use_legacy_rebalance: bool = False,
    split_days: int = 1,
    impact_cost_bps: Optional[float] = None,
) -> None:
    """把 ``on_daily_rebalance`` 方法挂到策略类上。

    akquant 的 ``on_daily_rebalance(trading_date, timestamp)`` 每根 bar 触发一次
    （每天最多一次），在此判断频率是否命中调仓日，命中则调 RebalanceEngine 选股。

    spec 011 迭代 4 后存在两条调仓路径（由 ``use_legacy_rebalance`` 切换）：

    - **新路径（默认）**：engine 自托管 target_weights 计算 → ``order_target_weights``。
      支持 cash_reserve / 单标的与行业暴露上限 / buffer_n / min_holding_bars /
      涨跌停拒单 / score 降序 + 诊断。
    - **旧路径**（``use_legacy_rebalance=True``）：``rebalance_to_topn``（回滚用）。

    频率命中判定（spec 011 P2-1：零状态，查 bar 的 trade_cal 标记）：

    - ``daily``：恒触发；
    - ``weekly`` / ``monthly`` / ``quarterly``：依据 ``trigger(first/last)`` 查询
      ``extra_map`` 中该日的 ``is_first_of_week`` / ``is_last_of_month`` 等标记。
      若 watcher 未下发标记（extra_map 缺失），回退到旧启发式
      ``_is_rebalance_trigger_day``（day<=7 月初首周），并打 warning。
    """

    def on_daily_rebalance(self, trading_date, timestamp):
        """调仓日选股 + 调仓（新路径自托管权重 / 旧路径 rebalance_to_topn）。"""
        # 1) 频率触发判断（spec 011 P2-1：查 bar 的 trade_cal 标记，零状态）
        is_trigger = _is_rebalance_day(trading_date, frequency, trigger, extra_map)

        # 分批日（非触发日但有未完成分批）：执行累计目标（PRD 009 §2.2.2 冻结法）
        pending = getattr(self, "_pending_split", None)
        if not is_trigger and pending is not None and not pending.exhausted:
            target = pending.next_target()
            _bump_exec(self, "split_day")
            if target:
                # 当日累计目标中，仅对「相较昨日新增/增持」的买入标的检查涨停跳过；
                # 这里对目标权重的「正向部分」做涨停过滤（涨停标的买不进，跳过其增量）。
                target = _filter_limit_up_today(target, extra_map, trading_date)
                if target:
                    price_map = build_impact_price_map(self, target, impact_cost_bps, trading_date, extra_map)
                    kw = {"price_map": price_map} if price_map else {}
                    try:
                        self.order_target_weights(target, liquidate_unmentioned=False, **kw)
                    except Exception:  # noqa: BLE001
                        logger.exception("分批调仓累计目标下单失败")
            if pending.exhausted:
                self._pending_split = None
            return

        if not is_trigger:
            return

        # 2) 收集全 universe 的历史 K 线（build kline_map for RebalanceEngine）
        #    用 get_history_df 取每个 symbol 的 OHLCV，转成 list[dict]。
        symbols = _discover_symbols(self, screen_config, universe_symbols)
        if not symbols:
            return

        kline_map: dict[str, list[dict]] = {}
        for sym in symbols:
            rows = _fetch_symbol_kline(self, sym, history_window)
            if rows:
                kline_map[sym] = rows

        if not kline_map:
            return

        # 3) 调 RebalanceEngine 选股打分（透传 watcher_client / extra_map）
        try:
            scores = rebalance_engine.select_at_rebalance_date(
                screen_config=screen_config,
                kline_map=kline_map,
                trading_date=trading_date,
                history_window=history_window,
                watcher_client=watcher_client,
                extra_map=extra_map,
            )
        except Exception as exc:  # noqa: BLE001
            # Bug #5：PIT 强制失败（PIT_CONSTITUENTS_EMPTY / PIT_WATCHER_UNAVAILABLE /
            # PIT_QUERY_FAILED）以 BacktestError 形式抛出，spec 011 P1-1 要求"失败即报错"，
            # 必须向上抛，不能与普通选股异常一起被吞掉。
            from services.backtest.runner import BacktestError
            if isinstance(exc, BacktestError):
                raise
            return

        if not scores:
            return

        # 4) 调仓路径分派
        if use_legacy_rebalance:
            # 旧路径：rebalance_to_topn（回滚用）
            try:
                self.rebalance_to_topn(
                    scores=scores,
                    top_n=top_n,
                    weight_mode=weight_mode,
                    long_only=long_only,
                    liquidate_unmentioned=liquidate_unmentioned,
                )
            except Exception:  # noqa: BLE001 - 调仓失败不阻断回测
                pass
            return

        # 新路径：自托管权重（spec 011 迭代 4 + spec 013 分批/冲击成本）
        try:
            target_weights, diagnosis = _compute_target_weights(
                strategy=self,
                scores=scores,
                top_n=top_n,
                weight_mode=weight_mode,
                cash_reserve=cash_reserve,
                max_weight_per_symbol=max_weight_per_symbol,
                max_industry_exposure=max_industry_exposure,
                buffer_n=buffer_n,
                min_holding_bars=min_holding_bars,
                reject_limit_up_on_buy=reject_limit_up_on_buy,
                reject_limit_down_on_sell=reject_limit_down_on_sell,
                trading_date=trading_date,
                extra_map=extra_map,
            )
            # 记录诊断（最后一次覆盖；result_serializer 由另一任务读取）
            try:
                self._rb_diagnosis = diagnosis
            except Exception:  # noqa: BLE001
                pass

            # 初始化 execution 诊断（首次，且仅当启用分批或冲击成本时）
            if getattr(self, "_exec_diagnosis", None) is None and (split_days > 1 or impact_cost_bps):
                self._exec_diagnosis = _exec_init_diagnosis(split_days)

            if not target_weights:
                # 无目标权重：若 liquidate_unmentioned=True，仍需显式清仓
                if liquidate_unmentioned:
                    try:
                        self.order_target_weights({}, liquidate_unmentioned=True)
                    except Exception:  # noqa: BLE001
                        pass
                return

            # 分批配置生效（split_days > 1）：冻结法切分，触发日下第 1 份累计目标
            if split_days > 1:
                old = getattr(self, "_pending_split", None)
                if old is not None and not old.exhausted:
                    logger.info("分批被打断：原 plan 剩 %d 天未执行", old.remaining_days)
                    _bump_exec(self, "interrupted")
                    old.interrupt()
                state = SplitState(plan=target_weights, total_days=split_days)
                target = state.next_target()  # 第 1 天累计目标 = plan/N
                self._pending_split = state
                _bump_exec(self, "split_day")
                if target:
                    target = _filter_limit_up_today(target, extra_map, trading_date)
                    if target:
                        price_map = build_impact_price_map(self, target, impact_cost_bps, trading_date, extra_map)
                        kw = {"price_map": price_map} if price_map else {}
                        try:
                            self.order_target_weights(target, liquidate_unmentioned=liquidate_unmentioned, **kw)
                        except Exception:  # noqa: BLE001
                            logger.exception("分批调仓首份累计目标下单失败")
                return

            # split_days == 1：现状一次性（带冲击成本 price_map）
            price_map = build_impact_price_map(self, target_weights, impact_cost_bps, trading_date, extra_map)
            kw = {"price_map": price_map} if price_map else {}
            try:
                self.order_target_weights(
                    target_weights,
                    liquidate_unmentioned=liquidate_unmentioned,
                    **kw,
                )
            except Exception:  # noqa: BLE001 - 调仓失败不阻断回测
                logger.exception("on_daily_rebalance 自托管权重调仓失败")
        except Exception:  # noqa: BLE001 - 调仓失败不阻断回测
            logger.exception("on_daily_rebalance 自托管权重调仓失败")

    # 设置 qualname 便于异常栈定位（否则显示 _attach_rebalance_method.<locals>.on_daily_rebalance）
    on_daily_rebalance.__qualname__ = f"{strategy_cls.__name__}.on_daily_rebalance"
    on_daily_rebalance.__name__ = "on_daily_rebalance"
    strategy_cls.on_daily_rebalance = on_daily_rebalance


def _is_rebalance_trigger_day(trading_date: Any, frequency: str, day_of_period: int) -> bool:
    """[DEPRECATED FALLBACK] 判断 trading_date 是否为调仓频率的触发日。

    .. deprecated:: spec 011 P2-1
        正式判定改由 :func:`_is_rebalance_day` 查 bar 的 trade_cal 标记（零状态）。
        本函数仅作为 **extra_map 标记缺失时的回退启发式** 保留，不删除。

    启发式说明（spec 接受「每月第 1 个交易日」day<=7 首周判断）：

    - ``daily``：恒真。
    - ``weekly``：``trading_date.weekday() == (day_of_period or 0)``（0=周一）。
    - ``monthly``：
      - ``day_of_period`` 在 1-28 时按 ``trading_date.day == day_of_period``；
      - ``day_of_period`` 为 0/None/空 时用 ``day <= 7``（月初首周，近似每月首个交易日）；
      - ``day_of_period > 28`` 视为非法（2 月无 29/30/31 日会整月不触发），
        回退到 ``day <= 7`` 月初启发式，避免调仓节奏断裂。
    - ``quarterly``：月份在 {1,4,7,10} 且 ``day <= 7``。

    .. note::
        ``day_of_period`` 的有效范围为 1-28（覆盖所有月份都存在的日期）。
        超出范围时回退到月初首周启发式，保证每月至少触发一次。
    """
    try:
        ts = pd.Timestamp(trading_date)
    except Exception:  # noqa: BLE001
        return False

    if frequency == "daily":
        return True
    if frequency == "weekly":
        return ts.weekday() == (day_of_period or 0)
    if frequency == "monthly":
        if day_of_period and 1 <= day_of_period <= 28:
            return ts.day == day_of_period
        # day_of_period 为 0/None 或 > 28（非法）：回退到月初首周
        return ts.day <= 7
    if frequency == "quarterly":
        return ts.month in (1, 4, 7, 10) and ts.day <= 7
    return False


# (frequency, trigger) → trade_cal 标记字段名（spec 011 P2-1）
_REBALANCE_FLAG_MAP = {
    ("weekly", "first"): "is_first_of_week",
    ("weekly", "last"): "is_last_of_week",
    ("monthly", "first"): "is_first_of_month",
    ("monthly", "last"): "is_last_of_month",
    ("quarterly", "first"): "is_first_of_quarter",
    ("quarterly", "last"): "is_last_of_quarter",
}


def _resolve_rebalance_trigger(rebalance) -> Optional[str]:
    """编译期解析最终生效的 trigger（spec 011 P2-1）。

    优先级：

    1. ``rebalance.trigger`` 显式指定（first/last）；
    2. 兼容旧 JSON：``rebalance.day_of_period`` 在场时映射
       ``<=1 → first``，否则 → ``last``，并打 deprecation warning；
    3. 都缺省 → 返回 None（下游规约为 "first"）。

    :param rebalance: ``RebalanceModel``（可能为 None，返回 None）。
    :return: "first" / "last" / None。
    """
    if rebalance is None:
        return None
    if rebalance.trigger is not None:
        return rebalance.trigger
    if rebalance.day_of_period is not None:
        mapped = "first" if (rebalance.day_of_period or 0) <= 1 else "last"
        logger.warning(
            "rebalance.day_of_period=%s 已废弃（spec 011 P2-1），"
            "编译期映射为 trigger=%r；请改用 rebalance.trigger(first/last)。",
            rebalance.day_of_period,
            mapped,
        )
        return mapped
    return None


def _get_bar_flag(
    extra_map: Optional[dict[str, dict[str, dict[str, Any]]]],
    trade_date_str: str,
    flag_name: str,
) -> Optional[str]:
    """从 extra_map 按 trade_date 取 trade_cal 标记（spec 011 P2-1）。

    标记是「全局」的（所有 symbol 当日相同），因此遍历任意 symbol 命中即返回。
    值统一 ``str()`` 化（watcher 下发可能是 "1"/1/True）。

    :return: 标记的字符串值；extra_map 为空 / 该日无记录 / 无该字段时返回 None。
    """
    if not extra_map:
        return None
    for _sym, day_map in extra_map.items():
        meta = day_map.get(trade_date_str)
        if not isinstance(meta, dict):
            continue
        val = meta.get(flag_name)
        if val is not None:
            return str(val)
    return None


def _is_rebalance_day(
    trading_date: Any,
    frequency: str,
    trigger: Optional[str],
    extra_map: Optional[dict[str, dict[str, dict[str, Any]]]] = None,
) -> bool:
    """零状态调仓日判定（spec 011 P2-1）。

    - ``daily``：恒触发（忽略 trigger）；
    - ``weekly``/``monthly``/``quarterly``：按 ``(frequency, trigger)`` 查
      ``extra_map`` 中该日的 trade_cal 标记（``is_first_of_month`` 等），命中
      ``"1"`` 视为触发日；
    - extra_map 无标记（watcher 未下发）时：打 warning 并回退到旧启发式
      :func:`_is_rebalance_trigger_day`（day<=7 月初首周），保证向后兼容。
    """
    if frequency == "daily":
        return True

    trig = trigger or "first"
    flag_name = _REBALANCE_FLAG_MAP.get((frequency, trig))
    if flag_name is None:
        # 未覆盖的 (frequency, trigger) 组合：不触发
        return False

    # trade_date 归一化为 YYYY-MM-DD（与 data_adapter.kline_to_extra_map 一致）
    try:
        trade_date_str = pd.Timestamp(trading_date).strftime("%Y-%m-%d")
    except Exception:  # noqa: BLE001
        return False

    flag_val = _get_bar_flag(extra_map, trade_date_str, flag_name)
    if flag_val is None:
        # 标记缺失：回退旧启发式（保守，保证不漏触发）
        logger.warning(
            "extra_map 缺少 trade_cal 标记 %s（trade_date=%s），"
            "回退到旧 day<=7 启发式判定；建议确认 watcher 已下发 is_*_of_* 标记。",
            flag_name,
            trade_date_str,
        )
        return _is_rebalance_trigger_day(trading_date, frequency, 0)
    return _is_truthy_flag(flag_val)


def _is_truthy_flag(val: Any) -> bool:
    """将 trade_cal 标记值归一化为布尔值。

    兼容 watcher 可能下发的多种表示：
    - 字符串：``"1"`` / ``"0"`` / ``"true"`` / ``"false"`` / ``"True"`` / ``"False"``
    - 数值：``1`` / ``0``
    - 布尔：``True`` / ``False``

    统一规则：值为 ``True``、``1``（整数或浮点）、``"1"``、``"true"``（大小写不敏感）
    时返回 ``True``；否则返回 ``False``。
    """
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)):
        return val == 1
    if isinstance(val, str):
        s = val.strip().lower()
        return s in ("1", "true", "yes", "y", "t")
    return False


def _discover_symbols(
    strategy: Strategy,
    screen_config: Optional[ScreenConfigModel],
    universe_symbols: Optional[list[str]] = None,
) -> list[str]:
    """发现 rebalance 要遍历的全 universe symbol 列表。

    优先级（从高到低）：

    1. ``universe_symbols``：watcher 经 HTTP 传入的全池（即 ``kline_data`` 的 keys）。
       **非 manual universe（csi300/csi500）时为唯一可靠来源**——此时
       ``screen_config.universe.stocks`` 在 engine 侧为空（由 watcher 解析成分股后裁剪 K 线）。
    2. ``screen_config.universe.stocks``：manual 池（用户显式指定的标的列表）。
    3. 兜底：akquant 已知持仓 + 当前 bar symbol（仅适用于 universe 无法确定的退化场景，
       可能导致「锁死在初始持仓」，仅作最后保障）。
    """
    # 1) universe_symbols（watcher 传入的全池，最可靠）
    if universe_symbols:
        return list(universe_symbols)

    # 2) screen_config.universe.stocks（manual 池；4 层结构：stocks 位于 universe 层）
    if (
        screen_config is not None
        and screen_config.universe is not None
        and screen_config.universe.stocks
    ):
        return list(screen_config.universe.stocks)

    # 3) 兜底：持仓 + 当前 symbol（退化场景，可能不完整）
    syms: list[str] = []
    try:
        positions = strategy.get_positions() or {}
        syms.extend([s for s, q in positions.items() if q])
    except Exception:  # noqa: BLE001
        pass
    try:
        cur = getattr(strategy, "symbol", None)
        if cur and cur not in syms:
            syms.append(cur)
    except Exception:  # noqa: BLE001
        pass
    return syms


def _fetch_symbol_kline(
    strategy: Strategy, symbol: str, window: int
) -> list[dict]:
    """从 akquant 取单个 symbol 的历史 OHLCV，转成 RebalanceEngine 需要的 list[dict]。"""
    try:
        df = strategy.get_history_df(count=window, symbol=symbol)
    except Exception:  # noqa: BLE001
        return []

    if df is None or getattr(df, "empty", True):
        return []

    rows: list[dict] = []
    idx = getattr(df, "index", None)
    for i, (_, row) in enumerate(df.iterrows()):
        date_val = None
        if idx is not None:
            date_val = str(idx[i]) if i < len(idx) else None
        rows.append({
            "date": date_val,
            "open": float(row.get("open", 0.0)),
            "high": float(row.get("high", 0.0)),
            "low": float(row.get("low", 0.0)),
            "close": float(row.get("close", 0.0)),
            "volume": float(row.get("volume", 0.0)),
        })
    return rows


__all__ = ["compile_strategy", "CompilerError"]


# ============================================================
# spec 013 P2-9：冲击成本 volume-linear 建模
# ============================================================
_PARTICIPATION_CAP = 1.0


def compute_impact_price(price, order_value, bar_volume, impact_cost_bps, sign):
    """冲击成本 volume-linear 建模（PRD 009 §2.2.3）。

    :param price: 原成交价（fill price）。
    :param order_value: 本笔成交额（正数）。
    :param bar_volume: 当日成交额（= volume × close，或直接用 volume 近似）。
    :param impact_cost_bps: 冲击成本(bps)；None 或 ≤0 → 不建模，返回原价。
    :param sign: +1 买入加价 / -1 卖出折价。
    :return: (adj_price, participation)；participation 为封顶后参与率。
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


def _get_bar_close_volume(strategy, symbol, trading_date, extra_map):
    """取 symbol 当日 close 与 volume（用于冲击成本估算）。

    优先用 get_history_df 取末 bar（trading_date 对应的那根）；返回 (close, volume)，
    任一缺失返回 (None, None)。
    """
    try:
        df = strategy.get_history_df(count=1, symbol=symbol)
    except Exception:  # noqa: BLE001
        return None, None
    if df is None or getattr(df, "empty", True):
        return None, None
    try:
        last = df.iloc[-1]
        close = float(last.get("close", 0.0))
        volume = float(last.get("volume", 0.0))
        return close, volume
    except Exception:  # noqa: BLE001
        return None, None


class SplitState:
    """分批调仓冻结状态机（PRD 009 §2.2.2 冻结法）。

    触发日算完整 plan 后，按 total_days 切分；``next_target`` 返回**当日累计目标
    权重**（第 k 天 = ``plan × k / total_days``），由调用方传给 akquant 的
    ``order_target_weights``（目标语义，框架自算差额下单）。N 天后累计目标 = plan，
    仓位真正建到完整 plan；每日差额非 0 → trades 分布在 N 天，与 spec §85/§93 一致。

    注：早期实现误把"每日增量 plan/N"当目标传入，因 ``order_target_weights`` 是
    target 语义（非 delta），会导致 N 天后仓位停在 plan/N。已修正为累计目标推进。

    耗尽或被打断后 exhausted=True。
    """

    def __init__(self, plan: dict, total_days: int):
        self.plan = dict(plan)
        self.total_days = max(1, int(total_days))
        self.current_day = 0
        self._exhausted = self.total_days <= 1

    def next_target(self):
        """返回下一个分批日的**累计目标权重**（第 k 天 = plan × k / N）。

        耗尽后返回 None。返回值直接作为 ``order_target_weights`` 的 weights 入参。
        """
        if self._exhausted:
            return None
        self.current_day += 1
        # 累计目标：第 k 天的目标 = plan × k / N；最后一天 k=N 时 = plan（消除浮点误差）
        if self.current_day >= self.total_days:
            target = dict(self.plan)  # 最后一份直接用 plan，避免 0.2*3≠0.6 之类误差
            self._exhausted = True
        else:
            k = self.current_day
            n = self.total_days
            target = {sym: w * k / n for sym, w in self.plan.items()}
        return target

    def interrupt(self):
        """新触发日打断：作废剩余分批。"""
        self._exhausted = True

    @property
    def exhausted(self) -> bool:
        return self._exhausted

    @property
    def remaining_days(self) -> int:
        return max(0, self.total_days - self.current_day)


def _exec_init_diagnosis(split_days: int) -> dict:
    return {
        "split_days": split_days,
        "splits_completed": 0,
        "splits_interrupted": 0,
        "total_impact_cost": 0.0,
        "avg_participation": 0.0,
        "_participations": [],  # 内部累计，序列化前剔除
    }


def _bump_exec(strategy, event: str, participation: float = 0.0, impact_cost: float = 0.0):
    """累计 execution_diagnosis（挂在 strategy._exec_diagnosis）。"""
    diag = getattr(strategy, "_exec_diagnosis", None)
    if diag is None:
        return diag
    if event == "split_day":
        diag["splits_completed"] += 1
    elif event == "interrupted":
        diag["splits_interrupted"] += 1
    if participation:
        diag["_participations"].append(participation)
    if impact_cost:
        diag["total_impact_cost"] += impact_cost
    return diag


def _finalize_exec_diagnosis(strategy):
    """序列化前：计算 avg_participation、剔除内部字段。"""
    diag = getattr(strategy, "_exec_diagnosis", None)
    if diag is None:
        return None
    parts = diag.pop("_participations", [])
    diag["avg_participation"] = (sum(parts) / len(parts)) if parts else 0.0
    return diag


def _trade_date_str(trading_date):
    """trading_date → extra_map 的 trade_date key（格式 %Y-%m-%d，与 data_adapter 一致）。"""
    try:
        return str(pd.Timestamp(trading_date).strftime("%Y-%m-%d"))
    except Exception:  # noqa: BLE001
        return None


def _filter_limit_up_today(weights, extra_map, trading_date):
    """剔除当日涨停（is_limit_up=1）的买入标的（PRD 009 §2.2.2 分批日涨停跳过）。"""
    if not weights or not extra_map:
        return dict(weights)
    td_str = _trade_date_str(trading_date)
    if td_str is None:
        return dict(weights)
    out = {}
    for sym, w in weights.items():
        if w > 0:  # 仅买入标的检查
            day_map = extra_map.get(sym) or {}
            meta = day_map.get(td_str) or {}
            if str(meta.get("is_limit_up", "0")) == "1":
                continue  # 当日涨停，跳过
        out[sym] = w
    return out


def build_impact_price_map(strategy, target_weights, impact_cost_bps, trading_date, extra_map):
    """为目标权重构造冲击成本调整后的 price_map（PRD 009 §2.2.3）。

    对每个标的：取当日 close 与 volume，按 target_weights 比例估算 order_value
    （总权益 × 权重），算调整后价格。返回 ``{symbol: adj_price}``；
    impact_cost_bps 为 None 或 target_weights 为空时返回空 dict。
    """
    if impact_cost_bps is None or not target_weights:
        return {}
    price_map: dict[str, float] = {}
    try:
        equity = strategy.get_portfolio_value()
    except Exception:  # noqa: BLE001
        return {}
    for sym, w in target_weights.items():
        try:
            close, volume = _get_bar_close_volume(strategy, sym, trading_date, extra_map)
            if close is None or volume is None:
                continue
            bar_volume_amount = volume * close  # 成交额
            order_value = abs(equity * w)
            adj, _ = compute_impact_price(
                close, order_value, bar_volume_amount, impact_cost_bps,
                sign=1 if w > 0 else -1,
            )
            price_map[sym] = adj
        except Exception:  # noqa: BLE001
            continue
    return price_map


# ============================================================
# spec 011 迭代 4：自托管权重计算辅助
# ============================================================

def _meta_true(val: Any) -> bool:
    """extra_map meta 字段布尔判定（容错：1 / '1' / 'true' / True 均视为真）。"""
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)):
        return val != 0
    if isinstance(val, str):
        return val.strip().lower() in ("1", "true", "yes", "y")
    return False


def _safe_hold_bar(strategy: Strategy, symbol: str) -> int:
    """安全取持仓 bar 数（异常返回 0）。"""
    try:
        v = strategy.hold_bar(symbol)
        return int(v) if v is not None else 0
    except Exception:  # noqa: BLE001
        return 0


def _current_price(strategy: Strategy, symbol: str) -> float:
    """取 symbol 当根收盘价作为当前价（best effort）。"""
    try:
        df = strategy.get_history_df(count=1, symbol=symbol)
        if df is not None and not getattr(df, "empty", True):
            return float(df["close"].iloc[-1])
    except Exception:  # noqa: BLE001
        pass
    return 0.0


def _resolve_industry(
    symbol: str,
    trading_date_str: str,
    extra_map: Optional[dict[str, dict[str, dict[str, Any]]]],
) -> str:
    """从 extra_map 当日 meta 取行业（sw_industry_l1 / industry）。缺失返回 'UNKNOWN'。"""
    if not extra_map:
        return "UNKNOWN"
    sym_meta = extra_map.get(symbol) or {}
    day_meta = sym_meta.get(trading_date_str) or {}
    return str(
        day_meta.get("sw_industry_l1")
        or day_meta.get("industry")
        or "UNKNOWN"
    )


def _clip_industry_exposure(
    target_weights: dict[str, float],
    symbols_by_industry: dict[str, list[str]],
    max_industry_exposure: float,
    max_iterations: int = 10,
) -> set[str]:
    """行业暴露上限后处理（PRD §6.2.2.2 迭代算法）。

    超限行业按比例缩减，释放量转现金不重分配。返回被缩减的 symbol 集合（诊断用）。
    """
    clipped: set[str] = set()
    if max_industry_exposure is None or max_industry_exposure <= 0:
        return clipped

    for _ in range(max_iterations):
        # 重新统计各行业当前总权重
        industry_totals: dict[str, float] = {}
        for ind, syms in symbols_by_industry.items():
            total = sum(
                target_weights.get(s, 0.0) for s in syms
            )
            if total > 0:
                industry_totals[ind] = total

        over_industry = None
        over_total = 0.0
        for ind, total in industry_totals.items():
            if total > max_industry_exposure + 1e-9 and total > over_total:
                over_industry = ind
                over_total = total

        if over_industry is None:
            break

        # 按比例缩减该行业所有持仓
        scale = max_industry_exposure / over_total if over_total > 0 else 0.0
        for s in symbols_by_industry[over_industry]:
            if s in target_weights:
                old = target_weights[s]
                new_w = old * scale
                if old > 0 and new_w < old - 1e-9:
                    clipped.add(s)
                target_weights[s] = new_w
    return clipped


def _compute_target_weights(
    strategy: Strategy,
    scores: dict[str, float],
    top_n: int,
    weight_mode: str,
    cash_reserve: float,
    max_weight_per_symbol: Optional[float],
    max_industry_exposure: Optional[float],
    buffer_n: int,
    min_holding_bars: int,
    reject_limit_up_on_buy: bool,
    reject_limit_down_on_sell: bool,
    trading_date: Any,
    extra_map: Optional[dict[str, dict[str, dict[str, Any]]]],
) -> tuple[dict[str, float], dict[str, Any]]:
    """spec 011 迭代 4 自托管权重计算。

    步骤：

    1. P0-5 score 降序排序候选；
    2. P1-7 buffer_n：买入取 top_(n-buffer)，卖出放宽到 top_(n+buffer)；
    3. P0-4 涨停拒买 / 跌停拒卖；
    4. P2-4 min_holding_bars：未满持仓周期的不卖；
    5. P1-3 cash_reserve：investable = 1 - cash_reserve；
    6. equal / score 权重分配；
    7. P1-4 单标的上限截断 + 行业暴露上限迭代缩减；
    8. P0-5 诊断信息收集。

    返回 ``(target_weights, diagnosis)``。
    """
    diagnosis: dict[str, Any] = {
        "trading_date": str(pd.Timestamp(trading_date).date())
        if trading_date is not None
        else None,
        "selected_count": 0,
        "actually_bought": 0,
        "rejected_by_cash": 0,
        "rejected_by_limit_up": [],
        "rejected_by_limit_down_kept": [],
        "rejected_by_min_holding_kept": [],
        "clipped_by_symbol_cap": [],
        "clipped_by_industry_cap": [],
    }

    if not scores or top_n <= 0:
        return {}, diagnosis

    investable = max(0.0, 1.0 - max(0.0, cash_reserve))

    # 1) P0-5：score 降序排序候选
    sorted_scores = sorted(scores.items(), key=lambda x: -x[1])
    selected = [s for s, _ in sorted_scores]

    # 2) P1-7 buffer_n：买入阈值与卖出阈值
    buy_threshold = max(top_n - buffer_n, 1) if buffer_n else top_n
    sell_threshold = top_n + buffer_n if buffer_n else top_n

    # 当前持仓
    try:
        current_positions = strategy.get_positions() or {}
    except Exception:  # noqa: BLE001
        current_positions = {}

    buy_set = set(selected[:buy_threshold])
    hold_set = {
        s
        for s in selected[:sell_threshold]
        if s in current_positions and (current_positions.get(s, 0) or 0) > 0
    }
    # Bug #6：旧实现 ``[s for s in selected if s in target_symbols_set][:top_n]`` 按全局
    # score 降序截断，会把 buffer 带内低分旧持仓挤出 → buffer_n 失效。
    # 正确语义：buy 取 top_(n-buffer)；hold 放宽到 top_(n+buffer) 且仅含已持仓。
    # 合并时 buy 优先，剩余名额给 hold；hold 不与 buy 竞争 top_n 主名额以外的高分新进标的。
    buy_list = [s for s in selected[:buy_threshold] if s in buy_set]
    hold_candidates = [
        s for s in selected[buy_threshold:sell_threshold]
        if s in hold_set and s not in buy_set
    ]
    target_symbols_ordered = (buy_list + hold_candidates)[: max(top_n, 0)]
    diagnosis["selected_count"] = len(target_symbols_ordered)

    # 3) P0-4：涨跌停拒单
    trading_date_str = ""
    try:
        trading_date_str = pd.Timestamp(trading_date).strftime("%Y-%m-%d")
    except Exception:  # noqa: BLE001
        trading_date_str = ""

    rejected_limit_up: list[str] = []
    if reject_limit_up_on_buy and extra_map and trading_date_str:
        filtered: list[str] = []
        for sym in target_symbols_ordered:
            meta = ((extra_map.get(sym) or {}).get(trading_date_str)) or {}
            if _meta_true(meta.get("is_limit_up")):
                rejected_limit_up.append(sym)
                logger.info("涨停拒买: %s @ %s", sym, trading_date_str)
                continue
            filtered.append(sym)
        target_symbols_ordered = filtered

    diagnosis["rejected_by_limit_up"] = rejected_limit_up

    if not target_symbols_ordered:
        return {}, diagnosis

    # 4) 权重分配（investable 之前算好）
    n = len(target_symbols_ordered)
    target_weights: dict[str, float] = {}
    if weight_mode == "score":
        clipped_scores = {s: max(scores.get(s, 0.0), 0.0) for s in target_symbols_ordered}
        s_sum = sum(clipped_scores.values())
        if s_sum > 0:
            target_weights = {
                s: (clipped_scores[s] / s_sum) * investable
                for s in target_symbols_ordered
            }
        else:
            w = investable / n if n > 0 else 0.0
            target_weights = {s: w for s in target_symbols_ordered}
    else:
        # equal 模式（含 None）
        w = investable / n if n > 0 else 0.0
        target_weights = {s: w for s in target_symbols_ordered}

    # 5) P0-4 跌停拒卖：持仓中不在 target_weights 的，若当日跌停则保留原权重
    # Bug W1/W2：保留权重直接加到 target_weights 会使总和 > investable（杠杆/拒单），
    # 改为收集 kept 权重，随后对非 kept 部分按比例缩放回 investable。
    kept_symbols: set[str] = set()
    if reject_limit_down_on_sell and extra_map and trading_date_str:
        try:
            equity = strategy.get_portfolio_value()
        except Exception:  # noqa: BLE001
            equity = 0.0
        kept_down: list[str] = []
        for sym, qty in current_positions.items():
            if (qty or 0) <= 0 or sym in target_weights:
                continue
            meta = ((extra_map.get(sym) or {}).get(trading_date_str)) or {}
            if _meta_true(meta.get("is_limit_down")):
                price = _current_price(strategy, sym)
                cur_value = (qty or 0) * price
                if equity > 0 and cur_value > 0:
                    target_weights[sym] = cur_value / equity
                    kept_symbols.add(sym)
                    kept_down.append(sym)
                    logger.info("跌停拒卖保留: %s @ %s", sym, trading_date_str)
        diagnosis["rejected_by_limit_down_kept"] = kept_down

    # 6) P2-4 min_holding_bars：未满持仓周期的不卖
    if min_holding_bars and min_holding_bars > 0:
        try:
            equity = strategy.get_portfolio_value()
        except Exception:  # noqa: BLE001
            equity = 0.0
        kept_holding: list[str] = []
        for sym, qty in current_positions.items():
            if (qty or 0) <= 0 or sym in target_weights:
                continue
            held = _safe_hold_bar(strategy, sym)
            if held < min_holding_bars:
                price = _current_price(strategy, sym)
                cur_value = (qty or 0) * price
                if equity > 0 and cur_value > 0:
                    target_weights[sym] = cur_value / equity
                    kept_symbols.add(sym)
                    kept_holding.append(sym)
                    logger.info(
                        "min_holding_bars 保留（held=%d < %d）: %s",
                        held, min_holding_bars, sym,
                    )
        diagnosis["rejected_by_min_holding_kept"] = kept_holding

    # 6.1) Bug W1/W2 归一化：kept 标的保留原权重，非 kept 部分缩放至 (investable - kept_total)
    if kept_symbols:
        kept_total = sum(target_weights.get(s, 0.0) for s in kept_symbols)
        remaining_budget = investable - kept_total
        non_kept = [s for s in target_weights if s not in kept_symbols]
        non_kept_sum = sum(target_weights[s] for s in non_kept)
        if non_kept and non_kept_sum > 0 and remaining_budget >= 0:
            scale = remaining_budget / non_kept_sum
            for s in non_kept:
                target_weights[s] *= scale
        elif non_kept and remaining_budget < 0:
            # kept 已超 investable：非 kept 全部清零，避免杠杆
            for s in non_kept:
                target_weights[s] = 0.0

    # 7) P1-4：单标的上限截断
    clipped_sym: list[str] = []
    if max_weight_per_symbol is not None and max_weight_per_symbol > 0:
        over_total = 0.0
        under: list[str] = []
        for s in list(target_weights.keys()):
            if target_weights[s] > max_weight_per_symbol:
                over_total += target_weights[s] - max_weight_per_symbol
                target_weights[s] = max_weight_per_symbol
                clipped_sym.append(s)
            else:
                under.append(s)
        # 释放量按现有权重比例分配给未超限标的
        if over_total > 0 and under:
            under_sum = sum(target_weights[s] for s in under)
            if under_sum > 0:
                for s in under:
                    target_weights[s] += over_total * (
                        target_weights[s] / under_sum
                    )
    diagnosis["clipped_by_symbol_cap"] = clipped_sym

    # 8) P1-4：行业暴露上限迭代缩减
    clipped_ind: list[str] = []
    if max_industry_exposure is not None and max_industry_exposure > 0 and trading_date_str:
        symbols_by_industry: dict[str, list[str]] = {}
        for sym in list(target_weights.keys()):
            ind = _resolve_industry(sym, trading_date_str, extra_map)
            symbols_by_industry.setdefault(ind, []).append(sym)
        clipped_ind = list(
            _clip_industry_exposure(
                target_weights, symbols_by_industry, max_industry_exposure
            )
        )
    diagnosis["clipped_by_industry_cap"] = clipped_ind

    diagnosis["actually_bought"] = sum(
        1 for v in target_weights.values() if v > 0
    )
    return target_weights, diagnosis
