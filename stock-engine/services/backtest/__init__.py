"""回测执行引擎（spec 007-backtest-center T2）。

公开 API：
- :func:`run_backtest_engine`：编排入口（StrategyConfigModel + kline_data → 结果 dict）；
- :func:`build_backtest_kwargs`：构建 ``aq.run_backtest`` 的 kwargs（强制 A 股规则）；
- :func:`compile_strategy`：JSON config → akquant Strategy 子类；
- :func:`kline_to_df` / :func:`kline_to_df_map` / :func:`normalize_benchmark`：数据适配；
- :func:`serialize_result`：BacktestResult → JSON dict。

约束：本包不触库，无动态代码执行注入面。
"""
from services.backtest.compiler import compile_strategy
from services.backtest.data_adapter import kline_to_df, kline_to_df_map, normalize_benchmark
from services.backtest.result_serializer import serialize_result
from services.backtest.runner import build_backtest_kwargs, run_backtest_engine

__all__ = [
    "run_backtest_engine",
    "build_backtest_kwargs",
    "compile_strategy",
    "kline_to_df",
    "kline_to_df_map",
    "normalize_benchmark",
    "serialize_result",
]
