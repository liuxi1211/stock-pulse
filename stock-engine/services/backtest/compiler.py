"""JSON 策略配置 → akquant Strategy 子类编译器（spec 007-backtest-center T2）。

把 :class:`StrategyConfigModel` 编译成一个 ``akquant.Strategy`` 子类，在 ``on_bar``
内用 :class:`TradingConditionEngine` 求值 signals.buy / signals.sell 条件树，按
position_sizing.method 下单；exit.bracket 在场时在 ``on_before_trading`` 下括号单。

**第一波（Phase 1）仅支持 signals + exit.bracket**：
- ``trading_config.rebalance`` 非 None → ``BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1``；
- ``exit_config.rules`` 非 None → 同上；
- ``exit.bracket.use_atr_stop=True`` → 同上（ATR 动态止损暂不支持）。

**禁用动态代码执行**：因子计算走 ``factor_calculator``（受控命名空间），
下单走显式方法分派（白名单），无任何字符串代码执行。
"""
from __future__ import annotations

import math
from typing import Any, Optional, Type

import akquant.talib as talib
import numpy as np
from akquant import Strategy

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
    FactorNode,
    OpNode,
    RefNode,
    StrategyConfigModel,
    ValueNode,
)


# ============================================================
# 异常
# ============================================================

class CompilerError(ValueError):
    """编译期错误。"""


_PHASE1_ERROR_CODE = "BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1"


# ============================================================
# 范式校验（Phase 1 仅支持 signals + exit.bracket）
# ============================================================

def _check_phase1_paradigm(config: StrategyConfigModel) -> None:
    """Phase 1 范式校验：rebalance / exit.rules / use_atr_stop 均不支持。"""
    tc = config.trading_config
    if tc is not None and tc.rebalance is not None:
        raise CompilerError(
            f"{_PHASE1_ERROR_CODE}: trading_config.rebalance 第一波暂不支持"
        )
    if tc is not None and tc.exit is not None:
        if tc.exit.rules is not None:
            raise CompilerError(
                f"{_PHASE1_ERROR_CODE}: exit.rules 复杂出场规则第一波暂不支持"
            )
        if tc.exit.bracket is not None and tc.exit.bracket.use_atr_stop:
            raise CompilerError(
                f"{_PHASE1_ERROR_CODE}: exit.bracket.use_atr_stop ATR 动态止损第一波暂不支持"
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

def compile_strategy(config: StrategyConfigModel) -> Type[Strategy]:
    """JSON config → akquant Strategy 子类。

    Phase 1 仅支持 ``signals + exit.bracket`` 范式。

    返回一个动态生成的 ``Strategy`` 子类（用 ``type()`` 构造，无动态代码执行）。
    """
    # 1. 范式校验
    _check_phase1_paradigm(config)

    tc = config.trading_config
    if tc is None or tc.signals is None:
        raise CompilerError(
            "BACKTEST_PARADIGM_NOT_SUPPORTED_PHASE_1: 第一波要求 trading_config.signals 在场"
        )

    buy_tree = tc.signals.buy
    sell_tree = tc.signals.sell
    sizing = tc.position_sizing
    if sizing is None:
        raise CompilerError(
            "BACKTEST_CONFIG_INVALID: trading_config.position_sizing 缺失"
        )

    # 校验下单方法白名单
    if sizing.method not in POSITION_SIZING_METHODS:
        raise CompilerError(
            f"BACKTEST_INVALID_POSITION_SIZING: position_sizing.method='{sizing.method}' "
            f"不在白名单 {sorted(POSITION_SIZING_METHODS)}"
        )

    # 2. 预扫描因子规格
    factor_specs: dict[str, FactorSpec] = {}
    if buy_tree is not None:
        _collect_factor_specs(buy_tree, factor_specs)
    if sell_tree is not None:
        _collect_factor_specs(sell_tree, factor_specs)

    # 3. 推断 warmup_period
    max_window = 60  # 兜底
    for spec in factor_specs.values():
        w = _infer_factor_window(spec)
        if w > max_window:
            max_window = w
    # cross_* 需要额外一根历史
    warmup = max_window + 2

    # 4. exit.bracket（可选）
    bracket = tc.exit.bracket if (tc.exit is not None) else None
    stop_loss_pct = bracket.stop_loss_pct if bracket else None
    take_profit_pct = bracket.take_profit_pct if bracket else None

    # 5. 预捕获的条件求值引擎（无状态，闭包共享；不存为实例属性 _engine 以免与 akquant 内核注入冲突）
    cond_engine = TradingConditionEngine()

    # 6. 动态构造 Strategy 子类（闭包绑定配置）
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

            # 更新 prev snapshot（供下一根 cross_*）
            self._prev_snapshot = cur_snap

        def on_before_trading(self, date, ts):
            """exit.bracket 在场时对持仓下括号单。"""
            if self._bracket_stop is None and self._bracket_take is None:
                return
            try:
                symbols = self.get_positions() or {}
            except Exception:  # noqa: BLE001
                symbols = {}

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

    # 重命名类（便于日志/调试）
    safe_name = (config.name or "CompiledStrategy").replace(" ", "_")
    _CompiledStrategy.__name__ = f"Strategy_{safe_name}"
    _CompiledStrategy.__qualname__ = _CompiledStrategy.__name__
    return _CompiledStrategy


__all__ = ["compile_strategy", "CompilerError"]
