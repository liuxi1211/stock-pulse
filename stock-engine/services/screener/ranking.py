"""选股中心：排序打分（统一策略配置 Schema §3.2.1 / spec 003 阶段 1 Task 5）。

职责：对条件求值命中的股票池按 ranking 配置做排序（单因子）或综合打分（多因子 z-score 加权），
输出带 rank / score 的结果列表。

支持的 ranking 形态（Schema §3.2.1）：
- ``{"method": "single", "factor": "TOTAL_MV", "order": "asc|desc"}``：
    单因子排序。order 默认 "desc"。NaN 统一排到末尾；同分按 symbol 升序兜底。
- ``{"method": "composite", "weights": {"ROE_TTM": 0.5, "PE_TTM": -0.3, ...}}``：
    多因子综合排序。每个因子维度 z-score 标准化（在非 NaN 子集上算 mean/std），
    综合分 = Σ(weight_k × z_k)，按维度权重绝对值归一化（NaN 维度剔除）。
    负权重 = 该因子值越小越好。综合分降序，NaN 排末尾。
- ranking 为 None：不排序，按 hit_symbols 原顺序，rank 从 1 起，score=None。

关键决策：
- 因子取值：single 的 factorKey 可能是基本面 factorKey（签名即 key），也可能是带 params 的
  技术面因子（签名形如 ``MA(timeperiod=5)#0``）。Schema single 模式只给 factorKey 不给 params，
  采用「精确命中 → 前缀匹配（找以 ``factorKey(`` 开头的签名，取第一个）」的取值策略。
- NaN 安全：所有排序 NaN 排末尾；返回结构 score / factor_values 中的 NaN 一律转 None（JSON 友好）。
- 约束（spec AC-11）：engine 不触库，本模块纯内存计算，无 sqlite3/sqlalchemy。
"""
import math
from typing import Any, Optional

# ============================================================
# 公开入口
# ============================================================

def rank_stocks(
    hit_symbols: list[str],
    factor_values_by_symbol: dict[str, dict[str, float]],
    ranking: Optional[dict],
    top_n: Optional[int] = None,
) -> list[dict]:
    """对命中的股票池按 ranking 排序 / 综合打分。

    :param hit_symbols: 条件命中的 symbol 列表（已通过条件求值）。
    :param factor_values_by_symbol: ``{symbol: {factor_signature: scalar}}``，
        来自 ``precompute_factors`` 的输出（技术面签名 + 基本面 factorKey 作 key）。
    :param ranking: Schema §3.2.1 的排序配置；None 表示不排序。
    :param top_n: 仅保留前 N 个（rank 1..N）；None / ≤0 表示不截断。
        注：total_count 由路由层从 hit_symbols 全量计算，本函数不做返回。
    :return: ``[{symbol, rank, score, factor_values}, ...]``。
        - rank 从 1 起。
        - score：single 模式为该因子原始值（float，NaN→None）；composite 模式为综合分
          （float，NaN→None）；ranking=None 时为 None。
        - factor_values：该股票全部预计算因子值（dict[str,float]，NaN→None，便于前端展示）。
    """
    method = ranking.get("method") if ranking else None

    if method == "single":
        ordered = _rank_single(hit_symbols, factor_values_by_symbol, ranking)
    elif method == "composite":
        ordered = _rank_composite(hit_symbols, factor_values_by_symbol, ranking)
    elif method is None:
        # ranking=None：原序，不排序
        ordered = list(hit_symbols)
    else:
        # 未知 method：降级为原序（保守），不抛异常避免阻断批量选股
        ordered = list(hit_symbols)

    # top_n 截断
    if top_n is not None and top_n > 0:
        ordered = ordered[:top_n]

    # 组装返回结构
    result: list[dict] = []
    for idx, symbol in enumerate(ordered, start=1):
        sym_values = factor_values_by_symbol.get(symbol, {})
        # score 取值
        if method == "single":
            score = _get_factor_value(sym_values, ranking["factor"])
            score_out = None if _is_nan(score) else float(score)
        elif method == "composite":
            score = _COMPOSITE_SCORE_CACHE.get(symbol, float("nan"))
            score_out = None if _is_nan(score) else float(score)
        else:
            score_out = None

        result.append({
            "symbol": symbol,
            "rank": idx,
            "score": score_out,
            "factor_values": _clean_values(sym_values),
        })
    return result


# ============================================================
# single 模式
# ============================================================

def _rank_single(
    hit_symbols: list[str],
    factor_values_by_symbol: dict[str, dict[str, float]],
    ranking: dict,
) -> list[str]:
    """单因子排序：取每只股票的因子值，按 order 排序，NaN 末尾。

    同分（含同为 NaN）按 symbol 升序兜底；返回有序 symbol 列表。
    """
    factor_key = ranking.get("factor")
    if not factor_key:
        # factor 缺失 → 原序返回（保守）
        return list(hit_symbols)

    order = ranking.get("order", "desc")
    desc = (order != "asc")  # 默认 desc

    # 取每只股票该因子的值
    pairs: list[tuple[float, str]] = []
    for symbol in hit_symbols:
        sym_values = factor_values_by_symbol.get(symbol, {})
        val = _get_factor_value(sym_values, factor_key)
        pairs.append((val, symbol))

    return _sort_with_nan(pairs, desc=desc)


# ============================================================
# composite 模式
# ============================================================

def _rank_composite(
    hit_symbols: list[str],
    factor_values_by_symbol: dict[str, dict[str, float]],
    ranking: dict,
) -> list[str]:
    """多因子综合排序：z-score 标准化 + 加权 + 按维度权重归一化。

    步骤：
    1. 对每个 factorKey 收集所有股票该维度的值（前缀匹配取值）。
    2. 每个维度在「非 NaN 子集」上算 z-score（std=0 或样本<2 → 该维度 z 全 0）。
    3. 综合分 = Σ(weight_k × z_k) / Σ|weight_k|（按维度归一化，NaN 维度剔除）。
    4. 综合分降序；同分 symbol 升序兜底；NaN 排末尾。
    """
    weights: dict[str, float] = ranking.get("weights") or {}
    if not weights:
        # 无权重 → 原序
        return list(hit_symbols)

    factor_keys = list(weights.keys())

    # 步骤 1：收集每个维度每只股票的值，并算该维度 z-score（一次性算完，缓存到 _COMPOSITE_SCORE_CACHE）
    # 维度值矩阵：dim_values[fk] = list[float]，与 hit_symbols 同序
    dim_values: dict[str, list[float]] = {}
    for fk in factor_keys:
        col = []
        for symbol in hit_symbols:
            sym_values = factor_values_by_symbol.get(symbol, {})
            col.append(_get_factor_value(sym_values, fk))
        dim_values[fk] = col

    # 步骤 2：z-score（每维度独立）
    dim_z: dict[str, list[float]] = {}
    for fk in factor_keys:
        dim_z[fk] = _zscore(dim_values[fk])

    # 步骤 3：每只股票综合分（按维度归一化）
    scores: list[float] = []
    for i, _symbol in enumerate(hit_symbols):
        numerator = 0.0
        denom = 0.0
        all_nan = True
        for fk in factor_keys:
            z = dim_z[fk][i]
            w = weights[fk]
            if _is_nan(z):
                continue
            all_nan = False
            numerator += w * z
            denom += abs(w)
        if all_nan or denom == 0.0:
            scores.append(float("nan"))
        else:
            scores.append(numerator / denom)

    # 缓存综合分（供 rank_stocks 取 score 字段）—— 通过模块级临时 dict
    _COMPOSITE_SCORE_CACHE.clear()
    for symbol, score in zip(hit_symbols, scores):
        _COMPOSITE_SCORE_CACHE[symbol] = score

    # 步骤 4：按综合分降序（NaN 末尾，symbol 升序兜底）
    pairs = list(zip(scores, hit_symbols))
    return _sort_with_nan(pairs, desc=True)


# 模块级缓存：composite 模式算出的综合分临时存放，供 rank_stocks 取 score 字段
# （避免重复计算；每次 _rank_composite 调用前 clear）
_COMPOSITE_SCORE_CACHE: dict[str, float] = {}


# ============================================================
# 内部工具
# ============================================================

def _get_factor_value(symbol_values: dict, factor_key: str) -> float:
    """从某股票的因子值字典中取指定 factorKey 的值。

    取值策略：
    1. 精确命中（基本面 factorKey 或无 params 技术面签名）→ 返回。
    2. 前缀匹配：找以 ``factor_key(`` 开头的 key（带 params 技术面签名，如 ``MA(timeperiod=5)#0``），
       取第一个匹配项。
    3. 均未命中 / 值为 NaN → 返回 NaN。
    """
    if not symbol_values or not factor_key:
        return float("nan")

    # 1) 精确命中
    if factor_key in symbol_values:
        return _to_float(symbol_values[factor_key])

    # 2) 前缀匹配（带 params 的技术面签名）
    prefix = f"{factor_key}("
    for k, v in symbol_values.items():
        if isinstance(k, str) and k.startswith(prefix):
            return _to_float(v)

    return float("nan")


def _zscore(values: list[float]) -> list[float]:
    """忽略 NaN 计算 mean/std，返回等长 z 列表。

    - NaN 位置保留 NaN。
    - std=0 或非 NaN 样本数 < 2 → 全 0（避免除零，单点无离散意义）。
    - 输入为空 → 返回空列表。
    """
    n = len(values)
    if n == 0:
        return []

    # 收集非 NaN
    valid = [float(v) for v in values if not _is_nan(v)]
    if len(valid) < 2:
        return [0.0] * n

    mean = sum(valid) / len(valid)
    var = sum((x - mean) ** 2 for x in valid) / len(valid)
    std = math.sqrt(var)
    if std == 0.0:
        return [0.0] * n

    out: list[float] = []
    inv = 1.0 / std
    for v in values:
        if _is_nan(v):
            out.append(float("nan"))
        else:
            out.append((float(v) - mean) * inv)
    return out


def _sort_with_nan(items: list[tuple[float, str]], desc: bool) -> list[str]:
    """稳定排序：NaN 整体排到末尾，同分按 symbol 升序兜底。

    :param items: [(score, symbol), ...]
    :param desc: True 降序，False 升序（仅对非 NaN 段生效）。
    :return: 排序后的 symbol 列表。
    """
    # 拆分非 NaN / NaN 两段
    valid: list[tuple[float, str]] = []
    nan_syms: list[str] = []
    for score, symbol in items:
        if _is_nan(score):
            nan_syms.append(symbol)
        else:
            valid.append((score, symbol))

    # 非 NaN 段：按 score 主排序（desc 控制），同分 symbol 升序兜底
    # 用 (key, symbol) 排序：Python 的 sort 稳定，先按 symbol 升序排一遍，再按 score 排即可保证同分 symbol 升序
    valid.sort(key=lambda x: x[1])  # 先 symbol 升序
    valid.sort(
        key=lambda x: x[0],
        reverse=desc,
    )  # 再 score（稳定，同分保持 symbol 升序）

    # NaN 段：symbol 升序
    nan_syms.sort()

    return [s for _, s in valid] + nan_syms


def _to_float(value: Any) -> float:
    """安全转 float；None/非数/Inf → NaN。"""
    if value is None:
        return float("nan")
    try:
        f = float(value)
    except (TypeError, ValueError):
        return float("nan")
    if math.isnan(f) or math.isinf(f):
        return float("nan")
    return f


def _is_nan(x: float) -> bool:
    """NaN 判断（含 None）。"""
    if x is None:
        return True
    try:
        return math.isnan(float(x))
    except (TypeError, ValueError):
        return True


def _clean_values(values: dict[str, float]) -> dict[str, Optional[float]]:
    """把因子值字典里的 NaN/Inf 转 None（JSON 友好），其余转 float。"""
    out: dict[str, Optional[float]] = {}
    for k, v in values.items():
        f = _to_float(v)
        out[k] = None if _is_nan(f) else f
    return out
