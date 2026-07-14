"""统一条件求值器（spec 008-backtest-center-phase2 T1 / PRD §12.3）。

本模块**不复制**底层求值逻辑，而是聚合两套既有引擎并对外暴露统一入口：

- 截面模式（``mode="cross_section"``）：委派给 003 ``services.screener.engine.ConditionEngine``。
  - 无状态、批量选股语义；**拒绝**时序比较器（``cross_up``/``cross_down``）与
    状态引用（``ref``），抛 ``ScreenTimeSeriesForbiddenError``。
  - NaN 安全（含 NaN 的比较一律 False）；除零降级返回 0.0。
- 时序模式（``mode="time_series"``）：委派给 005 ``services.backtest.trading_engine.TradingConditionEngine``。
  - 支持 ``cross_up``/``cross_down``（需历史两根）+ ``ref`` 白名单
    （``entry_price``/``position_pnl_pct``/``position_qty``/``bars_held``）。
  - NaN 安全；除零降级返回 NaN（与 005 既有语义一致）。

两类上下文形态不同（截面用 :class:`EvalContext`，时序用
:class:`~services.backtest.trading_engine.EvalContext`），因此统一门面
:class:`UnifiedConditionEngine` 仅做「按 mode 选引擎 + 委派」，
**不强求两套上下文合并**（spec 「pragmatic, low-risk」决策：不合并两套节点类型系统）。

设计原则：
- 不触库（源码不含任何数据库驱动 import / 连接 / 路径字面量）；
- 禁用动态代码执行（禁用任意代码字符串解释 / 编译执行 / 动态模块装载）；
- 零行为回归：003 / 005 既有的 import 与求值语义保持不变。

向后兼容：
- ``from services.screener.engine import ConditionEngine`` 仍可用（003 模块保持原样）；
- ``from services.backtest.trading_engine import TradingConditionEngine`` 仍可用（005 模块保持原样）；
- 本模块额外把这两个类在共享层 re-export，便于新代码统一从 ``services.shared`` 取用。
"""
from typing import Literal

# 003 截面引擎（行为单一真相源，re-export 而非复制）
from services.screener.engine import (
    ConditionEngine,
    EvalContext,
    factor_signature,
    validate_cross_section,
)
# 005 时序引擎（行为单一真相源，re-export 而非复制）
from services.backtest.trading_engine import (
    BarSnapshot,
    ConditionEvalError,
    PositionCtx,
    TradingConditionEngine,
)
from services.backtest.trading_engine import EvalContext as TimeSeriesEvalContext

Mode = Literal["cross_section", "time_series"]


# ============================================================
# 统一门面（按 mode 委派）
# ============================================================

class UnifiedConditionEngine:
    """统一条件求值门面：按 ``mode`` 持有一个底层引擎实例并委派求值。

    用法::

        # 截面（选股 / rebalance 选股）
        cs_engine = UnifiedConditionEngine(mode="cross_section")
        cs_engine.evaluate(tree, ctx)          # ctx = services.screener.engine.EvalContext

        # 时序（回测 signals / exit.rules）
        ts_engine = UnifiedConditionEngine(mode="time_series")
        ts_engine.evaluate_tree(tree, ctx)     # ctx = services.backtest.trading_engine.EvalContext

    设计：不合并两套上下文/节点类型系统（pragmatic 决策）。求值入口按 mode 分派到
    对应底层方法：

    - ``cross_section``：调 ``ConditionEngine.evaluate(tree, ctx)``；
    - ``time_series``：调 ``TradingConditionEngine.evaluate_tree(tree, ctx)``。

    截面模式同样暴露 ``evaluate_tree`` 别名（等价于 ``evaluate``），便于调用方统一写法。
    时序模式的 ``evaluate`` 别名等价于 ``evaluate_tree``。

    NaN 安全 / 除零降级 / 时序比较器 / ref 白名单**已在底层引擎实现**，本门面不重复。
    """

    __slots__ = ("mode", "_engine")

    def __init__(self, mode: Mode = "cross_section") -> None:
        if mode not in ("cross_section", "time_series"):
            raise ValueError(
                f"UnifiedConditionEngine: mode 必须是 'cross_section' 或 'time_series'，"
                f"收到 {mode!r}"
            )
        self.mode = mode
        # 底层引擎均无状态，可安全持有一个共享实例
        self._engine = (
            ConditionEngine() if mode == "cross_section" else TradingConditionEngine()
        )

    # ------------------------------------------------------------------
    # 截面模式入口
    # ------------------------------------------------------------------

    def evaluate(self, tree, ctx) -> bool:
        """截面模式主入口（委派 ``ConditionEngine.evaluate``）。

        时序模式下调此方法会委派到 ``TradingConditionEngine.evaluate_tree``
        （时序引擎的递归入口，兼容树/叶子/None）。
        """
        if self.mode == "cross_section":
            return self._engine.evaluate(tree, ctx)
        return self._engine.evaluate_tree(tree, ctx)

    def evaluate_tree(self, tree, ctx) -> bool:
        """统一递归入口别名。

        - 截面模式：等价于 :meth:`evaluate`（003 ``ConditionEngine.evaluate`` 本身就递归）。
        - 时序模式：委派 ``TradingConditionEngine.evaluate_tree``。
        """
        if self.mode == "cross_section":
            return self._engine.evaluate(tree, ctx)
        return self._engine.evaluate_tree(tree, ctx)

    def evaluate_expr(self, node, ctx) -> float:
        """表达式求值（仅截面模式暴露，对应 003 ``_eval_expression``）。

        时序模式的表达式求值耦合在叶子求值内部（带 snapshot 选择），不单独暴露；
        时序模式下调用此方法抛 ``ConditionEvalError``（引导调用方用叶子入口）。
        """
        if self.mode == "cross_section":
            return self._engine._eval_expression(node, ctx)
        raise ConditionEvalError(
            "BACKTEST_CONDITION_NOT_SUPPORTED: 时序模式不支持独立的 evaluate_expr "
            "（请用 evaluate_tree / evaluate 处理整棵条件树）"
        )


# ============================================================
# 工厂
# ============================================================

def create_condition_engine(mode: Mode = "cross_section") -> UnifiedConditionEngine:
    """工厂：按 mode 构造统一求值器。

    :param mode: ``"cross_section"``（选股/rebalance 选股）或
        ``"time_series"``（回测 signals/exit.rules）。
    :return: :class:`UnifiedConditionEngine` 实例。
    """
    return UnifiedConditionEngine(mode=mode)


__all__ = [
    "Mode",
    "UnifiedConditionEngine",
    "create_condition_engine",
    # re-export（便于新代码统一从共享层取用，底层模块仍保持原 import 不变）
    "ConditionEngine",
    "EvalContext",
    "factor_signature",
    "validate_cross_section",
    "TradingConditionEngine",
    "BarSnapshot",
    "PositionCtx",
    "TimeSeriesEvalContext",
    "ConditionEvalError",
]
