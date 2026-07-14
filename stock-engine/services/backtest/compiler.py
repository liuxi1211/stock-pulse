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

import math
from typing import Any, Optional, Type

import akquant.talib as talib
import numpy as np
import pandas as pd
from akquant import Strategy

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
    OpNode,
    RebalanceModel,
    RefNode,
    ScreenConfigModel,
    StrategyConfigModel,
    ValueNode,
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

    __slots__ = ("factor", "params", "output_index", "cache_key")

    def __init__(self, factor: str, params: Optional[dict], output_index: Optional[int]):
        self.factor = factor
        self.params = dict(params) if params else {}
        self.output_index = output_index
        # cache_key 与 trading_engine._factor_cache_key 单一真相源（含 params 区分 MA5/MA20）
        self.cache_key = _factor_cache_key(factor, output_index, self.params or None)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, FactorSpec):
            return NotImplemented
        return (
            self.factor == other.factor
            and self.params == other.params
            and self.output_index == other.output_index
        )

    def __hash__(self) -> int:
        return hash((self.factor, tuple(sorted(self.params.items())), self.output_index))


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
        spec = FactorSpec(node.factor, node.params, node.output_index)
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

    扫描 ``screen_config.conditions`` 条件树 + ``ranking`` 因子（single.factor 或
    composite.weights 的每个 key），用 :func:`_infer_factor_window` 取最大窗口；
    再与默认 history_window（60）取 max。无 rebalance / 无 screen_config 时返回 0。
    """
    tc = config.trading_config
    if tc is None or tc.rebalance is None:
        return 0

    screen = config.screen_config
    if screen is None:
        return 0

    windows: list[int] = []

    # conditions 条件树里的因子
    if screen.conditions is not None:
        specs: dict[str, FactorSpec] = {}
        _collect_factor_specs(screen.conditions, specs)
        for spec in specs.values():
            windows.append(_infer_factor_window(spec))

    # ranking 因子
    ranking = screen.ranking
    if ranking is not None:
        if ranking.method == "single" and ranking.factor:
            windows.append(_infer_factor_window(FactorSpec(ranking.factor, None, None)))
        elif ranking.method == "composite" and ranking.weights:
            for fk in ranking.weights.keys():
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
# 编译主函数
# ============================================================

def compile_strategy(
    config: StrategyConfigModel,
    *,
    universe_symbols: Optional[list[str]] = None,
) -> Type[Strategy]:
    """JSON config → akquant Strategy 子类。

    Phase 2 支持 signals / rebalance / exit.rules / use_atr_stop 及混合范式。

    :param config: 策略配置模型。
    :param universe_symbols: 可选，watcher 经 HTTP 传入的全 universe 标的列表
       （即 ``kline_data`` 的 keys）。rebalance 范式下用于发现调仓候选池；
        非 manual universe（csi300/csi500）时 ``screen_config.stocks`` 为空，
        必须靠此参数才能遍历全池，否则 rebalance 会锁死在初始持仓。signals-only
        范式可省略。

    返回一个动态生成的 ``Strategy`` 子类（用 class 定义 + 闭包绑定，无动态代码执行）。
    """
    # 1. 范式校验（占位，Phase 2 无拒绝项）
    _check_paradigm(config)

    tc = config.trading_config
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
    rb_day_of_period: int = 0
    rb_top_n: int = 0
    rb_weight_mode: str = "equal"
    rb_long_only: bool = True
    rb_liquidate_unmentioned: bool = True
    rb_history_window: int = 60

    if has_rebalance:
        rebalance_engine = RebalanceEngine()
        screen_config = config.screen_config
        if screen_config is None:
            raise CompilerError(
                "BACKTEST_CONFIG_INVALID: rebalance 范式要求 screen_config 在场"
            )
        if screen_config.top_n is None or screen_config.top_n <= 0:
            raise CompilerError(
                "BACKTEST_CONFIG_INVALID: rebalance 范式要求 screen_config.top_n 正整数"
            )
        rb_frequency = rebalance.frequency if rebalance else "daily"
        rb_day_of_period = rebalance.day_of_period if (rebalance and rebalance.day_of_period is not None) else 0
        rb_top_n = int(screen_config.top_n)
        rb_weight_mode = rebalance.weight_mode if (rebalance and rebalance.weight_mode) else "equal"
        rb_long_only = rebalance.long_only if (rebalance and rebalance.long_only is not None) else True
        # replace_method: None 或 "full" → 全换（liquidate_unmentioned=True）；"incremental" → 只换差额
        replace_method = rebalance.replace_method if rebalance else None
        rb_liquidate_unmentioned = (replace_method != "incremental")
        rb_history_window = max(rebalance_warmup, 60)
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
            self._rb_day_of_period = rb_day_of_period
            self._rb_top_n = rb_top_n
            self._rb_weight_mode = rb_weight_mode
            self._rb_long_only = rb_long_only
            self._rb_liquidate_unmentioned = rb_liquidate_unmentioned
            self._rb_history_window = rb_history_window

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
            """从 akquant 持仓接口读 ref 上下文。"""
            pos = PositionCtx()
            try:
                qty = float(self.get_position(symbol) or 0.0)
                pos.position_qty = qty
                if qty != 0:
                    pos.bars_held = int(self.hold_bar() or 0)
                    # entry_price / pnl_pct：akquant 挡位接口可能不可用，尽力取
                    account = self.get_account()
                    # 无统一字段时留默认 0
                    pos.entry_price = float(account.get("entry_price", 0.0) or 0.0) if isinstance(account, dict) else None
                    pos.position_pnl_pct = float(account.get("position_pnl_pct", 0.0) or 0.0) if isinstance(account, dict) else 0.0
            except Exception:  # noqa: BLE001
                pass
            return pos

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
            day_of_period=rb_day_of_period,
            history_window=rb_history_window,
            top_n=rb_top_n,
            weight_mode=rb_weight_mode,
            long_only=rb_long_only,
            liquidate_unmentioned=rb_liquidate_unmentioned,
            screen_config=screen_config,
            rebalance_engine=rebalance_engine,
            universe_symbols=rb_universe_symbols,
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
    day_of_period: int,
    history_window: int,
    top_n: int,
    weight_mode: str,
    long_only: bool,
    liquidate_unmentioned: bool,
    screen_config: Optional[ScreenConfigModel],
    rebalance_engine: Optional[RebalanceEngine],
    universe_symbols: Optional[list[str]] = None,
) -> None:
    """把 ``on_daily_rebalance`` 方法挂到策略类上。

    akquant 的 ``on_daily_rebalance(trading_date, timestamp)`` 每根 bar 触发一次
    （每天最多一次），在此判断频率是否命中调仓日，命中则调 RebalanceEngine 选股 +
    ``rebalance_to_topn`` 调仓。

    频率命中启发式（spec 接受 day<=7 首周启发式）：
    - ``daily``：每日触发；
    - ``weekly``：``trading_date.weekday() == day_of_period``（0=周一，day_of_period 默认 0）；
    - ``monthly``：每月首个交易日（启发式：``trading_date.day <= 7`` 视为月初首周，
      取月份变化后的第一根 bar；day_of_period 非 0 时按 ``trading_date.day == day_of_period``）；
    - ``quarterly``：季度首月（1/4/7/10）的月初首周（``day <= 7``）。
    """

    def on_daily_rebalance(self, trading_date, timestamp):
        """调仓日选股 + rebalance_to_topn 调仓。"""
        # 1) 频率触发判断
        if not _is_rebalance_trigger_day(trading_date, frequency, day_of_period):
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

        # 3) 调 RebalanceEngine 选股打分
        try:
            scores = rebalance_engine.select_at_rebalance_date(
                screen_config=screen_config,
                kline_map=kline_map,
                trading_date=trading_date,
                history_window=history_window,
            )
        except Exception:  # noqa: BLE001 - 选股失败不阻断回测
            return

        if not scores:
            return

        # 4) rebalance_to_topn 调仓
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

    # 设置 qualname 便于异常栈定位（否则显示 _attach_rebalance_method.<locals>.on_daily_rebalance）
    on_daily_rebalance.__qualname__ = f"{strategy_cls.__name__}.on_daily_rebalance"
    on_daily_rebalance.__name__ = "on_daily_rebalance"
    strategy_cls.on_daily_rebalance = on_daily_rebalance


def _is_rebalance_trigger_day(trading_date: Any, frequency: str, day_of_period: int) -> bool:
    """判断 trading_date 是否为调仓频率的触发日。

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


def _discover_symbols(
    strategy: Strategy,
    screen_config: Optional[ScreenConfigModel],
    universe_symbols: Optional[list[str]] = None,
) -> list[str]:
    """发现 rebalance 要遍历的全 universe symbol 列表。

    优先级（从高到低）：

    1. ``universe_symbols``：watcher 经 HTTP 传入的全池（即 ``kline_data`` 的 keys）。
       **非 manual universe（csi300/csi500）时为唯一可靠来源**——此时
       ``screen_config.stocks`` 在 engine 侧为空（由 watcher 解析成分股后裁剪 K 线）。
    2. ``screen_config.stocks``：manual 池（用户显式指定的标的列表）。
    3. 兜底：akquant 已知持仓 + 当前 bar symbol（仅适用于 universe 无法确定的退化场景，
       可能导致「锁死在初始持仓」，仅作最后保障）。
    """
    # 1) universe_symbols（watcher 传入的全池，最可靠）
    if universe_symbols:
        return list(universe_symbols)

    # 2) screen_config.stocks（manual 池）
    if screen_config is not None and screen_config.stocks:
        return list(screen_config.stocks)

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
