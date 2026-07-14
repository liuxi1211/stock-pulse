"""engine 共享层（spec 008-backtest-center-phase2 T1）。

把 003 选股与 005 回测共用的「条件求值」+「因子计算」逻辑抽成共享模块，保证
AC「同条件同结果」可验证。本包**只做聚合 + 委派**，不复制底层实现：
- 条件求值：003 的 ``services.screener.engine.ConditionEngine``（截面，禁 cross_*/ref）
  与 005 的 ``services.backtest.trading_engine.TradingConditionEngine``（时序，允许）
  在此统一为 :class:`~services.shared.condition_evaluator.UnifiedConditionEngine`
  （按 ``mode`` 委派到对应实现）。
- 因子管线：把 005 ``compiler._compute_factor_values``（单 bar 实时算）与
  003 ``factor_precompute.precompute_factors``（批量预计算）统一为
  :mod:`services.shared.factor_pipeline` 的两个入口，二者都走
  ``factor_calculator.compute_single``（同口径）。

硬约束（spec AC-P2-7a）：
- 本包不触库（源码不含任何数据库驱动 import / 连接 / 路径字面量）；
- 不使用动态代码执行（禁用任意代码字符串解释 / 编译执行 / 动态模块装载）；
- 不修改 003/005 底层引擎行为，仅在其上叠加薄壳聚合，保持向后兼容。
"""
from services.shared import condition_evaluator, factor_pipeline
from services.shared.condition_evaluator import (
    UnifiedConditionEngine,
    create_condition_engine,
    ConditionEngine,
    EvalContext as CrossSectionEvalContext,
)
from services.shared.factor_pipeline import (
    compute_latest,
    precompute,
    precompute_factors_batch,
    trim_leading_nan,
)

__all__ = [
    "condition_evaluator",
    "factor_pipeline",
    "UnifiedConditionEngine",
    "create_condition_engine",
    "ConditionEngine",
    "CrossSectionEvalContext",
    "compute_latest",
    "precompute",
    "precompute_factors_batch",
    "trim_leading_nan",
]
