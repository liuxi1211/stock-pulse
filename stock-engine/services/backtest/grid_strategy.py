"""网格交易策略（spec 015 FR-G2 / FR-G3）。

实现 :class:`GridStrategy`（``akquant.Strategy`` 子类）与
:func:`build_grid_strategy_class` 工厂。``method=grid`` 范式由
:mod:`services.backtest.compiler` 的 ``_compile_grid_strategy`` 入口分叉调用本模块，
不进入 signals/rebalance 编译主链路。

状态机要点（spec §S1）：

- ``grid_level`` 仅在 :meth:`on_order` 收到 ``Filled`` 时推进（加减），
  :meth:`on_bar` 内不发 ``grid_level`` 变更。
- 跳空去重：单根 bar 只对「最近反向格」发一档（买入取最大 n 的命中买档，
  卖出取最小 n 的命中卖档）。
- T+1：A 股 ``t_plus_one=True`` 时当日买入次日才可卖，sell 命中但 ``available==0``
  时记 ``pending_sell``，下一根 bar 开头补单（spec §S1.4）。
- 强平/止损命中后调 ``close_position``，``grid_level`` 由后续 ``on_order`` 的
  Filled 推进到 0。
"""
from __future__ import annotations

import logging
import math
from typing import Any, List, Optional, Tuple, Type

from akquant import Strategy, make_fill_policy

from services.strategy.constants import GRID_TICK_SIZE
from services.strategy.models import GridParamsModel

logger = logging.getLogger(__name__)


# ============================================================
# 工具：价格按 tick_size 向下取整
# ============================================================

def _floor_to_tick(price: float, tick: float = GRID_TICK_SIZE) -> float:
    """按 tick_size 向下取整（如 0.01 → 保留两位小数向下取整）。

    全部盈亏/价格中间计算用 float64，仅在档位价位确定时取整一次
    （由 akquant 撮合层保证最终成交价精度）。
    """
    if not tick or tick <= 0:
        return float(price)
    try:
        return math.floor(float(price) * (1.0 / tick)) * tick
    except (TypeError, ValueError):
        return float(price)


# ============================================================
# Order status / side 归一化（兼容枚举与字符串）
# ============================================================

def _norm_order_field(value: Any) -> str:
    """把 Order.status / Order.side 归一化为小写字符串。

    akquant 0.2.47 的 ``Order.status`` / ``Order.side`` 是 Rust 枚举
    （``OrderStatus.Filled`` / ``OrderSide.Buy``），``str(...)`` 形如
    ``"OrderStatus.Filled"``；这里取末段并小写，兼容 ``"Filled"`` /
    ``"filled"`` / ``"OrderSide.Buy"`` 多种形态。
    """
    if value is None:
        return ""
    if isinstance(value, str):
        s = value.strip()
    else:
        s = str(value)
    # 取最后一个 "." 之后段（兼容 "OrderStatus.Filled" / "Filled"）
    if "." in s:
        s = s.rsplit(".", 1)[-1]
    return s.strip().lower()


# ============================================================
# GridStrategy 基类
# ============================================================

class GridStrategy(Strategy):
    """网格交易策略基类。

    子类化后由 :func:`build_grid_strategy_class` 闭包绑定
    ``grid_params`` / ``initial_cash`` / ``symbol``，生成的类 ``__init__`` 无参数。

    状态变量见模块 docstring / spec §S1.1。
    """

    # akquant 读 warmup_period 类属性；网格不需要因子窗口，1 即可
    # （cross 判据需要 _prev_close，首根 bar 不触发，warmup=1 保证第二根开始正常决策）
    warmup_period = 1

    def __init__(self, grid_params: GridParamsModel, initial_cash: float, symbol: str):
        super().__init__()
        # 冻结参数（GridParamsModel 实例，不可变）
        self._grid_params: GridParamsModel = grid_params
        self._initial_cash: float = float(initial_cash)
        self._symbol: str = symbol

        # 状态变量（spec §S1.1）
        self.grid_level: int = 0
        self.pending_sell: Optional[Tuple[int, int]] = None  # (target_level, qty)
        self.pending_retry: int = 0
        self.stop_loss_triggered: bool = False
        self.cleared: bool = False
        self._entry_bar_index: Optional[int] = None
        self._bar_index: int = 0
        self._prev_close: Optional[float] = None

        # 档位价位（on_bar 首根 / center 数值时计算）
        self._buy_levels: List[float] = []
        self._sell_levels: List[float] = []
        self._center: Optional[float] = None

        # fill_policy：A 股下一根开盘成交（akquant 04 §5 默认）
        self._buy_fill_policy = make_fill_policy(
            price_basis="open", temporal="next_event"
        )
        self._sell_fill_policy = make_fill_policy(
            price_basis="open", temporal="next_event"
        )

        # 若 center 是数值（非 "first_bar_close"），构造期就能算档位
        if isinstance(grid_params.center, (int, float)):
            self._center = float(grid_params.center)
            self._recompute_levels()

    # ------------------------------------------------------------------
    # 档位价位计算
    # ------------------------------------------------------------------

    def _recompute_levels(self) -> None:
        """根据 ``self._center`` 与 step 重算买/卖档价位。

        - percent 模式：``center × (1 - n × step.value)``（买）/
          ``center × (1 + n × step.value)``（卖）。``step.value=0.02`` 表示 2%。
        - absolute 模式：``center - n × step.value``（买）/
          ``center + n × step.value``（卖）。

        全部按 :data:`GRID_TICK_SIZE` 向下取整。``adjust_mode=forward_adjusted``
        时中枢一次设定不复权推进（一期固定）。
        """
        if self._center is None:
            self._buy_levels = []
            self._sell_levels = []
            return

        gp = self._grid_params
        center = float(self._center)
        step = gp.step
        max_grids = int(gp.max_grids)

        buy_levels: List[float] = []
        sell_levels: List[float] = []
        for n in range(1, max_grids + 1):
            if step.type == "percent":
                raw_buy = center * (1.0 - n * step.value)
                raw_sell = center * (1.0 + n * step.value)
            else:  # absolute
                raw_buy = center - n * step.value
                raw_sell = center + n * step.value
            buy_levels.append(_floor_to_tick(raw_buy))
            sell_levels.append(_floor_to_tick(raw_sell))

        self._buy_levels = buy_levels
        self._sell_levels = sell_levels

    # ------------------------------------------------------------------
    # 资金 / 仓位检查
    # ------------------------------------------------------------------

    def _can_buy(self, qty_per_grid: int, bar_close: float) -> bool:
        """是否允许发买单（grid_level / 锁定 / 资金 / 总仓上限）。

        - ``grid_level < max_grids``；
        - 未 ``cleared`` 且未 ``stop_loss_triggered``；
        - 现金足够：``qty_per_grid × bar_close ≤ get_cash()``；
        - 总仓位上限：``(get_position() + qty_per_grid) × bar_close ≤
          initial_cash × max_position_value_pct``。
        """
        gp = self._grid_params
        if self.grid_level >= int(gp.max_grids):
            return False
        if self.cleared or self.stop_loss_triggered:
            return False
        qty = float(qty_per_grid)
        if qty <= 0:
            return False
        try:
            cash = float(self.get_cash())
        except Exception:  # noqa: BLE001
            cash = 0.0
        if qty * float(bar_close) > cash + 1e-9:
            logger.debug(
                "grid buy skipped: cash not enough (need=%.2f have=%.2f)",
                qty * float(bar_close), cash,
            )
            return False
        try:
            pos = float(self.get_position() or 0.0)
        except Exception:  # noqa: BLE001
            pos = 0.0
        max_value = self._initial_cash * float(gp.max_position_value_pct)
        if (pos + qty) * float(bar_close) > max_value + 1e-9:
            logger.debug(
                "grid buy skipped: max_position_value_pct cap (would=%.2f cap=%.2f)",
                (pos + qty) * float(bar_close), max_value,
            )
            return False
        return True

    # ------------------------------------------------------------------
    # on_bar 主回调
    # ------------------------------------------------------------------

    def on_bar(self, bar: Any) -> None:
        gp = self._grid_params
        qty_per_grid = int(gp.qty_per_grid)

        # 0) 停牌段处理：更新 prev_close 避免复牌跳空误判，本根不触发任何逻辑
        extra = getattr(bar, "extra", None) or {}
        if isinstance(extra, dict) and extra.get("suspended"):
            self._prev_close = float(bar.close)
            return

        # 1) 开头处理 pending_sell（T+1 解锁后发卖单）
        if self.pending_sell is not None:
            try:
                available = float(self.get_available_position() or 0.0)
            except Exception:  # noqa: BLE001
                available = 0.0
            if available > 0:
                _target_level, qty = self.pending_sell
                try:
                    self.sell(
                        symbol=self._symbol,
                        quantity=int(qty),
                        fill_policy=self._sell_fill_policy,
                    )
                except Exception:  # noqa: BLE001 - 下单失败不阻断回测
                    logger.exception("grid pending_sell 下单失败")
                self.pending_sell = None

        # 2) pending_retry 超时检查
        if (
            gp.unfilled_retry_bars > 0
            and self.pending_retry >= int(gp.unfilled_retry_bars)
        ):
            logger.warning("grid order retry exhausted, giving up")
            self.pending_retry = 0

        # 3) 首根 bar 冻结 first_bar_close 中枢
        if self._center is None:
            # center="first_bar_close" 时此处冻结；数值 center 已在 __init__ 设好
            self._center = float(bar.close)
            self._recompute_levels()

        # 4) bar 计数 + max_holding_bars 强平
        self._bar_index += 1
        if (
            self._entry_bar_index is not None
            and self.grid_level > 0
            and (self._bar_index - self._entry_bar_index) >= int(gp.max_holding_bars)
        ):
            try:
                self.close_position(symbol=self._symbol)
            except Exception:  # noqa: BLE001
                logger.exception("grid max_holding_bars 强平失败")
            # grid_level 由后续 on_order Filled 推进到 0

        # 5) 止损检查（close 跌破 stop_loss_pct 或 stop_loss_price）
        if not self.stop_loss_triggered and self.grid_level > 0:
            sl_pct = gp.stop_loss_pct
            sl_price = gp.stop_loss_price
            triggered = False
            if sl_pct is not None and self._center is not None:
                if float(bar.close) <= self._center * (1.0 - float(sl_pct)):
                    triggered = True
            if not triggered and sl_price is not None and self._center is not None:
                if float(bar.close) <= self._center - float(sl_price):
                    triggered = True
            if triggered:
                try:
                    self.close_position(symbol=self._symbol)
                except Exception:  # noqa: BLE001
                    logger.exception("grid stop_loss 强平失败")
                self.stop_loss_triggered = True
                # grid_level 由后续 on_order Filled 推进到 0

        # 6) cleared / stop_loss_triggered 时不再加仓（仍可走 pending_sell 平仓）
        skip_buy = self.cleared or self.stop_loss_triggered

        # 6.1) 一字涨跌停拒单（spec §S1.3 / PRD G-T3 / 老股民审查 P1）：
        #   一字跌停（close≈low≈前收盘×0.9）无对手盘，买单不会成交，回测会变"假抄底"；
        #   一字涨停（close≈high≈前收盘×1.1）同理卖单不成交。读 bar.extra 的 is_limit_down/is_limit_up
        #   命中则跳过对应方向下单（spec §131-133 Scenario）。extra 无此字段时降级为全量触发（旧行为）。
        is_limit_down = bool(extra.get("is_limit_down")) if isinstance(extra, dict) else False
        is_limit_up = bool(extra.get("is_limit_up")) if isinstance(extra, dict) else False

        # 7) 主体：cross 穿越 + 跳空去重（仅当 _prev_close 非 None）
        if self._prev_close is not None:
            prev_close = float(self._prev_close)
            cur_close = float(bar.close)

            # 7a) 买入方向：cross_down 跌穿买档
            #     找所有 buy_levels[i] 满足 prev_close >= buy_levels[i] > cur_close
            #     取 i 最大（最深反向格 = 当前 grid_level 对应的下一格）
            if not skip_buy and not is_limit_down:
                hit_buy_idx = -1
                for i, lvl in enumerate(self._buy_levels):
                    if prev_close >= lvl > cur_close:
                        hit_buy_idx = i
                if hit_buy_idx >= 0:
                    # 该格对应的 level 数 = hit_buy_idx + 1
                    target_level = hit_buy_idx + 1
                    if target_level <= int(gp.max_grids) and self._can_buy(
                        qty_per_grid, cur_close
                    ):
                        try:
                            self.buy(
                                symbol=self._symbol,
                                quantity=qty_per_grid,
                                fill_policy=self._buy_fill_policy,
                            )
                        except Exception:  # noqa: BLE001
                            logger.exception("grid buy 下单失败")

            # 7b) 卖出方向：cross_up 穿卖档
            #     找所有 sell_levels[i] 满足 prev_close <= sell_levels[i] < cur_close
            #     取 i 最小（最近反向格 = 当前 grid_level 对应的卖出格）
            if self.grid_level > 0 and not is_limit_up:
                hit_sell_idx = -1
                for i, lvl in enumerate(self._sell_levels):
                    if prev_close <= lvl < cur_close:
                        hit_sell_idx = i
                        break  # 取最小的 i
                if hit_sell_idx >= 0:
                    try:
                        available = float(self.get_available_position() or 0.0)
                    except Exception:  # noqa: BLE001
                        available = 0.0
                    if available > 0:
                        try:
                            self.sell(
                                symbol=self._symbol,
                                quantity=qty_per_grid,
                                fill_policy=self._sell_fill_policy,
                            )
                        except Exception:  # noqa: BLE001
                            logger.exception("grid sell 下单失败")
                    else:
                        # T+1 锁定：记 pending_sell，下一根 bar 开头补单（spec §S1.4）
                        self.pending_sell = (self.grid_level - 1, qty_per_grid)

        # 8) 更新 _prev_close = bar.close（放最后）
        self._prev_close = float(bar.close)

    # ------------------------------------------------------------------
    # on_order：grid_level 推进
    # ------------------------------------------------------------------

    def on_order(self, order: Any) -> None:
        """订单状态更新：Filled 推进 ``grid_level``，Rejected/Cancelled 累加 retry。"""
        status = _norm_order_field(getattr(order, "status", None))
        side = _norm_order_field(getattr(order, "side", None))

        if status == "filled":
            if side == "buy":
                self.grid_level += 1
                if self._entry_bar_index is None:
                    self._entry_bar_index = self._bar_index
            elif side == "sell":
                self.grid_level -= 1
            self.pending_retry = 0

            # grid_level 归零检查
            if self.grid_level == 0:
                self._entry_bar_index = None
                if not bool(self._grid_params.re_entry_after_clear):
                    self.cleared = True
        elif status in ("rejected", "cancelled"):
            self.pending_retry += 1

    # ------------------------------------------------------------------
    # on_trade：仅审计日志
    # ------------------------------------------------------------------

    def on_trade(self, trade: Any) -> None:
        """成交回调：仅记录日志用于审计，不推进状态。"""
        logger.debug(
            "grid trade: symbol=%s side=%s qty=%s price=%s",
            getattr(trade, "symbol", None),
            getattr(trade, "side", None),
            getattr(trade, "quantity", None),
            getattr(trade, "price", None),
        )


# ============================================================
# 工厂：build_grid_strategy_class
# ============================================================

def build_grid_strategy_class(
    grid_params: GridParamsModel,
    initial_cash: float,
    symbol: str,
) -> Type[Strategy]:
    """生成 GridStrategy 子类，闭包绑定 grid_params / initial_cash / symbol。

    返回的类 ``__init__`` 无参数（grid_params 已闭包绑定），保持 pickle 友好
    （grid 一期不进入 GRID param_grid，pickle 不是硬约束，但仍按规范落盘类实现）。

    :param grid_params: 已校验的 :class:`GridParamsModel`。
    :param initial_cash: 初始资金（用于总仓位上限估算）。
    :param symbol: 单标的代码（grid 范式单标的）。
    :return: ``Strategy`` 子类（``__name__`` 为 ``"GridStrategy"``）。
    """
    bound_grid_params = grid_params
    bound_initial_cash = float(initial_cash)
    bound_symbol = symbol

    class _Grid(GridStrategy):
        def __init__(self):
            super().__init__(
                grid_params=bound_grid_params,
                initial_cash=bound_initial_cash,
                symbol=bound_symbol,
            )

    _Grid.__name__ = "GridStrategy"
    _Grid.__qualname__ = "GridStrategy"
    return _Grid


__all__ = ["GridStrategy", "build_grid_strategy_class"]
