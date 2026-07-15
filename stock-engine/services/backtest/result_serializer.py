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

def serialize_result(
    result: Any,
    benchmark_series: Optional[pd.Series] = None,
    *,
    effective_config: Optional[dict] = None,
    rebalance_diagnosis: Optional[dict] = None,
) -> dict:
    """``BacktestResult`` → 可 ``json.dumps`` 的 dict。

    - ``metrics``：核心指标（保留原始百分数单位，``total_return_pct=15.0`` 表示 15%）；
    - ``equity_curve`` / ``benchmark_curve``：``{dates, values}``；
    - ``daily_returns``：list[float]；
    - ``trades`` / ``orders`` / ``positions``：清洗后的 list[dict]；
    - ``benchmark_series``：基准已归一化的 pd.Series（见 :func:`data_adapter.normalize_benchmark`）；
    - ``effective_config``（spec 011 P2-5）：实际生效配置（warmup_period 等），由 runner 透传；
    - ``rebalance_diagnosis``（spec 011 P0-5）：轮动调仓诊断。

    spec 011 新增字段：

    - ``metrics.annual_turnover_ratio``（P2-8）：年化换手率；
    - ``rebalance_diagnosis``（P0-5）：优先取显式入参，否则从 ``result.strategy._rb_diagnosis``
      读取最后一次调仓诊断；择时范式无此字段 → 为 ``None``；
    - ``effective_config``（P2-5）：warmup 实际值 + 来源 + reason。
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

    # ---- spec 011 P2-8：年化换手率 ----
    metrics["annual_turnover_ratio"] = _compute_annual_turnover(result)

    # ---- spec 011 P0-5：rebalance 诊断（优先显式入参，否则从 result.strategy 取） ----
    diagnosis = rebalance_diagnosis if rebalance_diagnosis is not None else _extract_rebalance_diagnosis(result)
    rebalance_diagnosis_out: Optional[dict] = None
    if diagnosis:
        rebalance_diagnosis_out = dict(diagnosis)
        # 补 actual_invest_ratio（若诊断无此字段，用 positions/initial_cash 近似）
        if "actual_invest_ratio" not in rebalance_diagnosis_out:
            ratio = _compute_actual_invest_ratio(rebalance_diagnosis_out, result)
            if ratio is not None:
                rebalance_diagnosis_out["actual_invest_ratio"] = ratio

    # ---- spec 011 P2-5：effective_config ----
    effective_config_out = effective_config if effective_config is not None else {}

    return {
        "metrics": metrics,
        "equity_curve": equity_curve,
        "benchmark_curve": benchmark_curve,
        "daily_returns": daily_returns,
        "trades": trades,
        "orders": orders,
        "positions": positions,
        "rebalance_diagnosis": rebalance_diagnosis_out,
        "effective_config": effective_config_out,
    }


# ============================================================
# spec 011 诊断 / effective_config / 换手率
# ============================================================

def _extract_rebalance_diagnosis(result: Any) -> Optional[dict]:
    """从 ``result.strategy._rb_diagnosis`` 取最后一次调仓诊断（P0-5）。

    - akquant 0.2.47 的 :class:`BacktestResult` 保留策略实例于 ``result.strategy``；
    - 编译产物 ``_CompiledStrategy`` 在每次调仓后把诊断写入实例的 ``_rb_diagnosis``；
    - 择时范式（signals-only）无 ``on_daily_rebalance``，``_rb_diagnosis`` 为空 dict → 返回 ``None``；
    - 容错：``strategy`` 为 None / 缺属性 / 异常时返回 ``None``。

    诊断字段（取最后一次调仓）：
    ``selected_count`` / ``actually_bought`` / ``rejected_by_cash`` /
    ``rejected_by_limit_up`` / ``actual_invest_ratio`` 等。
    """
    strategy = getattr(result, "strategy", None)
    if strategy is None:
        return None
    try:
        diag = getattr(strategy, "_rb_diagnosis", None)
    except Exception:  # noqa: BLE001
        return None
    if not isinstance(diag, dict) or not diag:
        return None
    return _clean_record(diag)


def _compute_actual_invest_ratio(diag: dict, result: Any) -> Optional[float]:
    """补算 ``actual_invest_ratio``（实际投入资金占比）。

    诊断里若无此字段，则用 ``positions_df`` 当前持仓市值 / ``initial_cash`` 近似。
    """
    if "actual_invest_ratio" in diag:
        return _num(diag["actual_invest_ratio"])
    try:
        positions_df = getattr(result, "positions_df", None)
        initial_cash = getattr(result, "initial_cash", None) or 0.0
        if positions_df is None or positions_df.empty or not initial_cash:
            return None
        if "market_value" in positions_df.columns:
            mv = float(positions_df["market_value"].iloc[-1])
        elif "equity" in positions_df.columns:
            mv = float(positions_df["equity"].iloc[-1])
        else:
            return None
        return _num(mv / initial_cash) if initial_cash else None
    except Exception:  # noqa: BLE001
        return None


def _compute_annual_turnover(result: Any) -> Optional[float]:
    """P2-8 年化换手率。

    公式（spec 011 P2-8）::

        total_turnover = Σ(entry_price × quantity) + Σ(exit_price × quantity)   # 已平仓交易
                       + 未平仓持仓的当前市值（positions_df.market_value）       # 买入未卖近似
        avg_equity     = mean(equity_curve_daily)
        annualization  = 252 / len(equity_curve_daily)                          # 日线
        annual_turnover = total_turnover / avg_equity × annualization

    所有除零 / NaN / Inf 由 :func:`_num` 兜底为 ``None``。
    """
    # ---- 分子：已平仓交易成交额 ----
    trades_df = getattr(result, "trades_df", None)
    closed_turnover = 0.0
    has_closed = False
    if trades_df is not None and not trades_df.empty:
        for col_pair in (("entry_price", "quantity"), ("exit_price", "quantity")):
            ep_c, q_c = col_pair
            if ep_c in trades_df.columns and q_c in trades_df.columns:
                try:
                    ep = pd.to_numeric(trades_df[ep_c], errors="coerce")
                    q = pd.to_numeric(trades_df[q_c], errors="coerce")
                    closed_turnover += float((ep * q).fillna(0.0).sum())
                    has_closed = True
                except Exception:  # noqa: BLE001
                    pass

    # ---- 分子补充：未平仓持仓当前市值 ----
    positions_df = getattr(result, "positions_df", None)
    open_market_value = 0.0
    if positions_df is not None and not positions_df.empty:
        mv_col = "market_value" if "market_value" in positions_df.columns else (
            "equity" if "equity" in positions_df.columns else None
        )
        if mv_col is not None:
            try:
                last_row = positions_df.iloc[-1]
                open_market_value = float(pd.to_numeric(last_row[mv_col], errors="coerce") or 0.0)
            except Exception:  # noqa: BLE001
                pass

    total_turnover = closed_turnover + open_market_value

    # ---- 分母：平均权益 + 年化系数 ----
    eq_daily = getattr(result, "equity_curve_daily", None)
    if eq_daily is None or len(eq_daily) == 0:
        return None
    try:
        avg_equity = float(pd.to_numeric(eq_daily, errors="coerce").mean())
    except Exception:  # noqa: BLE001
        return None
    n_bars = len(eq_daily)
    if avg_equity <= 0 or n_bars <= 0:
        return None
    annualization = 252.0 / float(n_bars)
    ratio = total_turnover / avg_equity * annualization
    return _num(ratio)


__all__ = ["serialize_result"]
