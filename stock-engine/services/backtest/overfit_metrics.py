"""6 维过拟合指标（spec 015 §S5 / FR-O5）。

WALK-FORWARD 滚动样本外验证后，把 N 段的训练/测试统计量喂给本模块，得到
6 个维度的过拟合判别指标与综合可信度评分。所有除零保护用 ``abs(x) < 1e-12``
判定，**绝不抛 ZeroDivisionError，除零时返回 None**。

维度（精确公式见 spec §S5.1）：

- ``return_gap``  = ``(mean(r_in) - mean(r_out)) / abs(mean(r_in))``，``mean(r_in)==0 → None``
- ``dd_ratio``    = ``mean(dd_out) / mean(dd_in)``，``mean(dd_in)==0 → None``（dd 正数存）
- ``cv``          = ``mean(std(p_j)/abs(mean(p_j)))``，单分量 ``mean==0`` 跳过
- ``peak_gap``    = ``s_1 - s_2``，``Top-1==Top-2`` 自然为 0
- ``diversity``   = ``len(set(p_i*)) / N``，``N==1 → 0``
- ``trade_ratio`` = ``mean(n_in) / mean(n_out)``，``mean(n_out)==0 → None``

综合评分 ``confidence_score`` 见 §S5.2：score=100 减各项阈值扣分。
"""
from __future__ import annotations

import json
import math
from statistics import mean, pstdev
from typing import Optional


# 除零判定阈值：绝对值小于此值认为分母为 0（保护 ZeroDivisionError）
_EPS = 1e-12

# 综合 score 默认阈值（spec §S5.2）
_DEFAULT_THRESHOLDS: dict[str, float] = {
    "max_return_gap": 0.3,
    "max_dd_ratio": 2.0,
    "max_param_cv": 0.5,
    "max_peak_gap": 0.5,
    "max_segment_diversity": 0.4,
}

# passed 硬判据阈值（与 thresholds 同步）
_PASSED_THRESHOLDS: dict[str, float] = {
    "max_return_gap": 0.3,
    "max_dd_ratio": 2.0,
    "max_param_cv": 0.5,
    "max_segment_diversity": 0.4,
}


# ============================================================
# 单维度指标
# ============================================================

def compute_return_gap(r_in: list[float], r_out: list[float]) -> Optional[float]:
    """收益落差：``(mean(r_in) - mean(r_out)) / abs(mean(r_in))``。

    - ``r_in``/``r_out`` 为空或 ``mean(r_in)==0`` → None；
    - ``mean(r_in) < 0``（训练集本就亏损）→ None（spec 015 修复 / 老股民审查 P1：
      双负收益场景下公式语义反转——训练亏 0.1、测试亏 0.15 算出 0.5 判过拟合，
      但实际是"策略本身无效"而非过拟合，应跳过该维度判定）；
    - 数值为正表示训练集显著好于样本外（过拟合信号）。
    """
    if not r_in or not r_out:
        return None
    mean_in = mean(r_in)
    if abs(mean_in) < _EPS:
        return None
    if mean_in < 0:
        # 训练集亏损：过拟合判定无意义（真正过拟合是训练赚、测试亏）
        return None
    return (mean_in - mean(r_out)) / abs(mean_in)


def compute_dd_ratio(dd_in: list[float], dd_out: list[float]) -> Optional[float]:
    """回撤比：``mean(dd_out) / mean(dd_in)``（dd 以正数存）。

    - ``dd_in``/``dd_out`` 为空或 ``mean(dd_in)==0`` → None；
    - 数值 > 1 表示样本外回撤放大（过拟合信号）。
    """
    if not dd_in or not dd_out:
        return None
    mean_in = mean(dd_in)
    if abs(mean_in) < _EPS:
        return None
    return mean(dd_out) / mean_in


def compute_param_cv(segment_best_params: list[dict]) -> Optional[float]:
    """参数变异系数：每段最优参数在分量级的 ``std/abs(mean)`` 再取均值。

    对每个参数分量 j（取所有段该 key 的值列表）：

    - ``vals = [p[j] for p in segment_best_params if j in p]``；
    - 若 ``len(vals) < 2`` 或 ``abs(mean(vals)) < 1e-12`` → 跳过该分量；
    - ``cv_j = pstdev(vals) / abs(mean(vals))``（总体标准差，ddof=0）；
    - 最后对所有可计算分量取均值；全部被跳过 → None。
    """
    if not segment_best_params:
        return None

    # 收集每个 key 的所有值（仅跨段同时出现的 key）
    keys: set[str] = set()
    for p in segment_best_params:
        if isinstance(p, dict):
            keys.update(p.keys())

    cv_components: list[float] = []
    for j in keys:
        vals = [p[j] for p in segment_best_params if isinstance(p, dict) and j in p]
        if len(vals) < 2:
            continue
        m = mean(vals)
        if abs(m) < _EPS:
            continue
        sd = pstdev(vals)  # 总体标准差（ddof=0）
        cv_components.append(sd / abs(m))

    if not cv_components:
        return None
    return mean(cv_components)


def compute_peak_gap(sorted_scores: list[float]) -> float:
    """峰值落差：Top-1 与 Top-2 metric 值之差。

    - ``len < 2`` → 0.0；
    - 输入应已降序排列（如 sharpe 降序）；
    - 数值大表示头部参数过强、次优差距悬殊（鲁棒性差信号）。
    """
    if not sorted_scores or len(sorted_scores) < 2:
        return 0.0
    top1 = sorted_scores[0]
    top2 = sorted_scores[1]
    try:
        return float(top1) - float(top2)
    except (TypeError, ValueError):
        return 0.0


def compute_diversity(segment_best_params: list[dict]) -> float:
    """段间参数多样性：去重后的参数组合数 / 段数 N。

    - ``N == 0`` → 0.0；
    - ``N == 1`` → 0.0（spec §S5.1：单段无法判断多样性）；
    - 其余 → ``len(unique_param_sets) / N``。
    """
    n = len(segment_best_params)
    if n == 0 or n == 1:
        return 0.0
    unique: set[str] = set()
    for p in segment_best_params:
        try:
            unique.add(json.dumps(p, sort_keys=True, ensure_ascii=False))
        except (TypeError, ValueError):
            # 不可序列化的 dict 用 repr 兜底（不应出现在正常 WF 流程中）
            unique.add(repr(sorted((str(k), repr(v)) for k, v in p.items())))
    return len(unique) / n


def compute_trade_ratio(n_in: list[int], n_out: list[int]) -> Optional[float]:
    """交易笔数比：``mean(n_in) / mean(n_out)``。

    - ``n_in``/``n_out`` 为空或 ``mean(n_out)==0`` → None；
    - 数值偏离 1 表示训练/样本外交易频次不一致（过拟合信号）。
    """
    if not n_in or not n_out:
        return None
    mean_out = mean(n_out)
    if abs(mean_out) < _EPS:
        return None
    return mean(n_in) / mean_out


# ============================================================
# 综合可信度评分
# ============================================================

def confidence_score(
    return_gap: Optional[float],
    dd_ratio: Optional[float],
    cv: Optional[float],
    peak_gap: Optional[float],
    diversity: Optional[float],
    trade_ratio: Optional[float],
    thresholds: Optional[dict] = None,
) -> int:
    """综合可信度评分（spec §S5.2）。

    基准 100 分，逐项超阈值扣分：

    - ``return_gap > max_return_gap(0.3)`` → -30
    - ``dd_ratio > max_dd_ratio(2.0)`` → -30
    - ``cv > max_param_cv(0.5)`` → -20
    - ``peak_gap > max_peak_gap(0.5)`` → -10
    - ``diversity > max_segment_diversity(0.4)`` → -10

    ``None``（除零）视为不判（跳过该项）。最终 clamp 到 [0, 100]。
    """
    th = dict(_DEFAULT_THRESHOLDS)
    if thresholds:
        th.update({k: float(v) for k, v in thresholds.items() if v is not None})

    score = 100
    if return_gap is not None and return_gap > th["max_return_gap"]:
        score -= 30
    if dd_ratio is not None and dd_ratio > th["max_dd_ratio"]:
        score -= 30
    if cv is not None and cv > th["max_param_cv"]:
        score -= 20
    if peak_gap is not None and peak_gap > th["max_peak_gap"]:
        score -= 10
    if diversity is not None and diversity > th["max_segment_diversity"]:
        score -= 10

    # trade_ratio 当前不进扣分（spec §S5.2 列出但未给阈值），保留参数兼容
    _ = trade_ratio

    return int(max(0, min(100, score)))


# ============================================================
# 聚合入口
# ============================================================

def _safe_metric(value: Optional[float]) -> Optional[float]:
    """NaN/Inf → None。"""
    if value is None:
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(v) or math.isinf(v):
        return None
    return v


def compute_overfit_metrics(
    wf_segments: list[dict],
    grid_scores: Optional[list[float]] = None,
) -> dict:
    """聚合 N 段 WF 结果 → 6 维指标 + 综合评分（spec §S5）。

    :param wf_segments: 每段结果 dict，含字段：
        ``best_params``(dict) / ``in_return``(float) / ``out_return``(float) /
        ``in_max_dd``(float) / ``out_max_dd``(float) /
        ``in_trades``(int) / ``out_trades``(int)。
    :param grid_scores: 可选，GRID 全量结果按 ``metric`` 降序排列的得分列表，
        用于计算 ``peak_gap``（Top-1 - Top-2）。None 时 peak_gap=0。
    :return: ``{"segments": N, "return_gap": ..., "dd_ratio": ..., "cv": ...,
        "peak_gap": ..., "diversity": ..., "trade_ratio": ...,
        "confidence_score": int, "passed": bool}``。
    """
    n = len(wf_segments)

    r_in: list[float] = []
    r_out: list[float] = []
    dd_in: list[float] = []
    dd_out: list[float] = []
    n_in: list[int] = []
    n_out: list[int] = []
    best_params_list: list[dict] = []

    for seg in wf_segments:
        if not isinstance(seg, dict):
            continue
        bp = seg.get("best_params")
        if isinstance(bp, dict):
            best_params_list.append(bp)
        else:
            best_params_list.append({})

        rin = _safe_metric(seg.get("in_return"))
        rout = _safe_metric(seg.get("out_return"))
        ddin = _safe_metric(seg.get("in_max_dd"))
        ddout = _safe_metric(seg.get("out_max_dd"))
        if rin is not None:
            r_in.append(rin)
        if rout is not None:
            r_out.append(rout)
        if ddin is not None:
            dd_in.append(ddin)
        if ddout is not None:
            dd_out.append(ddout)

        try:
            ni = int(seg.get("in_trades") or 0)
        except (TypeError, ValueError):
            ni = 0
        try:
            no = int(seg.get("out_trades") or 0)
        except (TypeError, ValueError):
            no = 0
        n_in.append(ni)
        n_out.append(no)

    return_gap = compute_return_gap(r_in, r_out)
    dd_ratio = compute_dd_ratio(dd_in, dd_out)
    cv = compute_param_cv(best_params_list)

    if grid_scores:
        # 输入可能是任意顺序，统一降序后取 Top-1/Top-2
        try:
            sorted_desc = sorted(
                [float(s) for s in grid_scores if s is not None],
                reverse=True,
            )
            peak_gap: float = compute_peak_gap(sorted_desc)
        except (TypeError, ValueError):
            peak_gap = 0.0
    else:
        peak_gap = 0.0

    diversity = compute_diversity(best_params_list)
    trade_ratio = compute_trade_ratio(n_in, n_out)

    score = confidence_score(
        return_gap=return_gap,
        dd_ratio=dd_ratio,
        cv=cv,
        peak_gap=peak_gap,
        diversity=diversity,
        trade_ratio=trade_ratio,
    )

    # passed 硬判据：None 视为不判（跳过该项）
    passed = True
    if return_gap is not None and return_gap >= _PASSED_THRESHOLDS["max_return_gap"]:
        passed = False
    if dd_ratio is not None and dd_ratio >= _PASSED_THRESHOLDS["max_dd_ratio"]:
        passed = False
    if cv is not None and cv >= _PASSED_THRESHOLDS["max_param_cv"]:
        passed = False
    if diversity is not None and diversity >= _PASSED_THRESHOLDS["max_segment_diversity"]:
        passed = False

    return {
        "segments": n,
        "return_gap": _safe_metric(return_gap),
        "dd_ratio": _safe_metric(dd_ratio),
        "cv": _safe_metric(cv),
        "peak_gap": _safe_metric(peak_gap),
        "diversity": _safe_metric(diversity),
        "trade_ratio": _safe_metric(trade_ratio),
        "confidence_score": int(score),
        "passed": bool(passed),
    }


__all__ = [
    "compute_return_gap",
    "compute_dd_ratio",
    "compute_param_cv",
    "compute_peak_gap",
    "compute_diversity",
    "compute_trade_ratio",
    "confidence_score",
    "compute_overfit_metrics",
]
