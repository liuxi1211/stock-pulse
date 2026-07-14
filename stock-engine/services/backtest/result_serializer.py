"""BacktestResult → JSON dict 序列化器（spec 007-backtest-center T2）。

把 ``akquant.run_backtest`` 返回的 :class:`BacktestResult` 序列化成 watcher 可收的
JSON dict。已校准指标名与单位（带 ``_pct`` 的字段是**原始百分数**，保留不转小数）。

约束：本模块不触库。
"""
from __future__ import annotations

import math
from typing import Any, Optional

import numpy as np
import pandas as pd


# 要回传的核心指标名（与 akquant 0.2.47 metrics_df index 对齐）
_METRIC_KEYS = (
    "total_return_pct",
    "annualized_return",
    "cagr",
    "sharpe_ratio",
    "sortino_ratio",
    "calmar_ratio",
    "max_drawdown_pct",
    "volatility",
    "win_rate",
    "profit_factor",
    "trade_count",
)


# ============================================================
# 原子工具
# ============================================================

def _num(x: Any) -> Optional[float]:
    """``NaN``/``Inf`` → ``None``，可 JSON 序列化。``None`` 直通。"""
    if x is None:
        return None
    try:
        f = float(x)
    except (TypeError, ValueError):
        return None
    if math.isnan(f) or math.isinf(f):
        return None
    return f


def _metric(metrics_df: pd.DataFrame, name: str) -> Optional[float]:
    """安全取单个指标（兼容 ``value`` / ``Backtest`` 列名）。"""
    if metrics_df is None or name not in metrics_df.index:
        return None
    col = "value" if "value" in metrics_df.columns else metrics_df.columns[0]
    return _num(metrics_df.at[name, col])


def _clean_scalar(v: Any) -> Any:
    """标量清洗：``NaN``/``Inf`` → ``None``；Timestamp → isoformat；Timedelta → 秒。"""
    if v is None:
        return None
    if isinstance(v, (pd.Timestamp,)):
        return v.isoformat()
    if isinstance(v, (pd.Timedelta,)):
        return v.total_seconds()
    if isinstance(v, (np.integer,)):
        return int(v)
    if isinstance(v, (np.floating,)):
        f = float(v)
        return None if (math.isnan(f) or math.isinf(f)) else f
    if isinstance(v, float):
        return None if (math.isnan(v) or math.isinf(v)) else v
    if isinstance(v, (np.bool_,)):
        return bool(v)
    return v


def _clean_record(rec: dict) -> dict:
    """清洗一条 dict（DataFrame.to_dict('records') 的元素）。

    把 Timestamp/Timedelta/NaN/numpy 标量转成 JSON 友好形态。
    """
    out: dict[str, Any] = {}
    for k, v in rec.items():
        if isinstance(v, dict):
            out[k] = _clean_record(v)
        elif isinstance(v, list):
            out[k] = [_clean_scalar(i) if not isinstance(i, dict) else _clean_record(i) for i in v]
        else:
            out[k] = _clean_scalar(v)
    return out


def _df_records(df: Optional[pd.DataFrame]) -> list[dict]:
    """DataFrame → 清洗后的 list[dict]；空则返回 ``[]``。"""
    if df is None or df.empty:
        return []
    return [_clean_record(r) for r in df.to_dict(orient="records")]


def _series_curve(series: Optional[pd.Series]) -> dict:
    """pd.Series → ``{"dates": [...], "values": [...]}``。"""
    if series is None or len(series) == 0:
        return {"dates": [], "values": []}
    dates: list[str] = []
    for idx in series.index:
        if isinstance(idx, (pd.Timestamp,)):
            dates.append(idx.strftime("%Y-%m-%d"))
        else:
            dates.append(str(idx))
    values = [_num(v) for v in series.values.tolist()]
    return {"dates": dates, "values": values}


# ============================================================
# 主序列化函数
# ============================================================

def serialize_result(result: Any, benchmark_series: Optional[pd.Series] = None) -> dict:
    """``BacktestResult`` → 可 ``json.dumps`` 的 dict。

    - ``metrics``：核心指标（保留原始百分数单位，``total_return_pct=15.0`` 表示 15%）；
    - ``equity_curve`` / ``benchmark_curve``：``{dates, values}``；
    - ``daily_returns``：list[float]；
    - ``trades`` / ``orders`` / ``positions``：清洗后的 list[dict]；
    - ``benchmark_series``：基准已归一化的 pd.Series（见 :func:`data_adapter.normalize_benchmark`）。
    """
    # ---- metrics ----
    metrics_df = getattr(result, "metrics_df", None)
    metrics: dict[str, Optional[float]] = {}
    if metrics_df is not None:
        for k in _METRIC_KEYS:
            metrics[k] = _metric(metrics_df, k)

    # ---- equity_curve ----
    eq_daily = getattr(result, "equity_curve_daily", None)
    equity_curve = _series_curve(eq_daily)

    # ---- daily_returns ----
    daily_returns_series = getattr(result, "daily_returns", None)
    if daily_returns_series is not None and len(daily_returns_series) > 0:
        daily_returns = [_num(v) for v in daily_returns_series.values.tolist()]
    else:
        daily_returns = []

    # ---- trades / orders / positions ----
    trades = _df_records(getattr(result, "trades_df", None))
    orders = _df_records(getattr(result, "orders_df", None))
    positions = _df_records(getattr(result, "positions_df", None))

    # ---- benchmark_curve ----
    benchmark_curve = _series_curve(benchmark_series) if benchmark_series is not None else {"dates": [], "values": []}

    return {
        "metrics": metrics,
        "equity_curve": equity_curve,
        "benchmark_curve": benchmark_curve,
        "daily_returns": daily_returns,
        "trades": trades,
        "orders": orders,
        "positions": positions,
    }


__all__ = ["serialize_result"]
