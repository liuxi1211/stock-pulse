"""选股中心 FastAPI 路由层（spec FR-4 快照选股 / FR-5 区间选股，阶段 1 Task 7）。

路由前缀 ``/python/v1/screener``。职责：参数接收 → 编排
filters → precompute → engine → rank → 封装统一响应。业务异常由 ``main.py``
全局处理器转为 400（UNKNOWN_FACTOR）/422（SCREEN_TIME_SERIES_FORBIDDEN）。

编排顺序（snapshot 单日）：
1. ``validate_cross_section(conditions)`` —— 截面违禁提前拦截（422）。
2. ``apply_filters(candidates, filters, screen_date, verbose)`` —— 静态过滤。
3. 因子预计算：
   - conditions 不为 None：``precompute_factors(conditions, passed_candidates)``。
   - conditions 为 None 但 ranking 引用因子：构造「伪条件树」包裹 ranking 因子后 precompute，
     使 ``rank_stocks`` 能取到因子值。
   - 两者皆无：``factor_values={}``（无需计算）。
4. 条件求值（conditions 不为 None 时）：构造 ``EvalContext``，调 ``engine.evaluate`` 收集命中。
   conditions 为 None 时 hit_symbols = passed_symbols（仅过滤）。
5. ``rank_stocks(hit_symbols, factor_values, ranking, top_n)`` —— 排序打分 + 截断。

约束（spec AC-11）：engine 不触库，本模块无 sqlite3/sqlalchemy。
"""
from typing import Optional

from fastapi import APIRouter
from pydantic import BaseModel

from models.schemas.condition import ConditionTree
from models.schemas.factor import ErrorResponse
from models.schemas.screener import (
    DailyHit,
    RangeScreenRequest,
    RangeScreenResponse,
    RangeScreenResponseData,
    RangeStockResult,
    Ranking,
    SnapshotScreenRequest,
    SnapshotScreenResponse,
    SnapshotScreenResponseData,
    StockResult,
)
from services.screener.engine import ConditionEngine, EvalContext, validate_cross_section
from services.screener.factor_precompute import precompute_factors
from services.screener.filters import apply_filters
from services.screener.ranking import rank_stocks

router = APIRouter(prefix="/python/v1/screener", tags=["选股中心"])

_ERR = {
    400: {"model": ErrorResponse, "description": "未知因子（UNKNOWN_FACTOR）"},
    422: {"model": ErrorResponse, "description": "条件树含截面禁用时序节点（SCREEN_TIME_SERIES_FORBIDDEN）"},
}

# 模块级无状态引擎实例（ConditionEngine 无状态，可安全并发使用）
_engine = ConditionEngine()


# ============================================================
# 内部编排
# ============================================================

def _build_ranking_pseudo_tree(ranking: dict) -> Optional[dict]:
    """从 ranking 配置构造一棵等价 compare 条件树，用于 precompute ranking 引用的因子。

    - single：``{type:compare, left:{factor: ranking.factor}, comparator:">", right:{value:0}}``。
    - composite：把每个 weight factorKey 包成 compare，用 OR 串联（保证 precompute 收集到全部）。
    - 无 ranking / 无引用因子 → None。
    """
    method = ranking.get("method") if ranking else None
    if method == "single":
        factor_key = ranking.get("factor")
        if not factor_key:
            return None
        return {
            "type": "compare",
            "left": {"factor": factor_key},
            "comparator": ">",
            "right": {"value": 0},
        }
    if method == "composite":
        weights = ranking.get("weights") or {}
        if not weights:
            return None
        leaves = [
            {
                "type": "compare",
                "left": {"factor": fk},
                "comparator": ">",
                "right": {"value": 0},
            }
            for fk in weights.keys()
        ]
        return {"operator": "OR", "conditions": leaves}
    return None


def _to_dict(model) -> Optional[dict]:
    """Pydantic 模型 → dict（exclude_none）；None 透传。业务函数统一接收 dict。"""
    if model is None:
        return None
    if isinstance(model, ConditionTree):
        return model.model_dump(exclude_none=True)
    return model.model_dump(exclude_none=True)


def _candidates_to_dict(candidates: dict) -> dict[str, dict]:
    """把 ``{symbol: CandidateStock | dict}`` 归一为 ``{symbol: dict}``。

    业务函数（apply_filters / precompute_factors）按 dict 访问 ``.get(...)``，
    因此 Pydantic 模型必须先转 dict。
    """
    out: dict[str, dict] = {}
    for sym, c in candidates.items():
        if isinstance(c, BaseModel):
            out[sym] = c.model_dump()
        else:
            out[sym] = c
    return out


def _merge_for_precompute(
    conditions: Optional[dict], ranking: Optional[dict]
) -> Optional[dict]:
    """合并 conditions 树与 ranking 伪树，得到一棵覆盖全部所需因子的树用于 precompute。

    - 两者皆无 → None（无需算因子）。
    - 仅其一 → 直接返回该树。
    - 两者都有 → 用 OR 串联（OR 只为收集因子引用，求值时不用这棵合并树）。

    目的：让 precompute_factors 一次性算出 conditions + ranking 引用的全部因子，
    ``rank_stocks`` 取 score/factor_values 时才不会缺值。
    """
    pseudo = _build_ranking_pseudo_tree(ranking) if ranking else None

    if conditions is None and pseudo is None:
        return None
    if conditions is None:
        return pseudo
    if pseudo is None:
        return conditions
    return {"operator": "OR", "conditions": [conditions, pseudo]}


def _run_snapshot(
    date: str,
    candidates: dict,
    conditions: Optional[dict],
    ranking: Optional[dict],
    filters: Optional[dict],
    top_n: Optional[int],
    verbose_excluded: bool,
) -> SnapshotScreenResponseData:
    """快照选股编排核心（snapshot 与 range 单日复用）。

    返回 ``SnapshotScreenResponseData``，其中：
    - ``stocks``：rank_stocks 输出（已 top_n 截断），直接用于快照响应。
    - ``total_count``：条件命中总数（截断前）。
    - ``excluded``：verbose 时填充，否则空 dict。
    """
    # 1) 截面违禁校验（conditions 不为 None 时）
    if conditions is not None:
        validate_cross_section(conditions)

    # candidates 归一化为 dict（业务函数按 dict 访问）
    candidates = _candidates_to_dict(candidates)

    # 2) 静态过滤
    passed_symbols, excluded = apply_filters(
        candidates, filters, screen_date=date, verbose=verbose_excluded
    )

    # 3) 因子预计算（合并 conditions + ranking 引用的全部因子，一次性算出）
    passed_candidates = {sym: candidates[sym] for sym in passed_symbols}
    factor_values: dict[str, dict[str, float]] = {}

    precompute_tree = _merge_for_precompute(conditions, ranking)
    if precompute_tree is not None:
        factor_values = precompute_factors(precompute_tree, passed_candidates)

    # 4) 条件求值
    if conditions is not None:
        hit_symbols: list[str] = []
        for sym in passed_symbols:
            fundamentals = (candidates[sym].get("fundamentals") or {})
            ctx = EvalContext(
                symbol=sym,
                factor_values=factor_values.get(sym, {}),
                fundamentals=fundamentals,
            )
            if _engine.evaluate(conditions, ctx):
                hit_symbols.append(sym)
    else:
        hit_symbols = list(passed_symbols)

    # 5) 排序打分 + 截断
    ranked = rank_stocks(hit_symbols, factor_values, ranking, top_n=top_n)

    # 6) 组装响应
    stocks = [StockResult(**r) for r in ranked]
    return SnapshotScreenResponseData(
        date=date,
        total_count=len(hit_symbols),
        stocks=stocks,
        excluded=excluded if verbose_excluded else {},
    )


# ============================================================
# 快照选股（FR-4）
# ============================================================

@router.post(
    "/snapshot",
    response_model=SnapshotScreenResponse,
    summary="快照选股（单日）",
    description="对单日候选池做 静态过滤 → 条件求值 → 排序打分，返回命中股票列表。"
                "conditions 含截面禁用时序节点（cross_up/cross_down/ref）返回 422；"
                "引用未注册 factorKey 返回 400 UNKNOWN_FACTOR。",
    responses=_ERR,
)
def snapshot(req: SnapshotScreenRequest):
    data = _run_snapshot(
        date=req.date,
        candidates=req.candidates,
        conditions=_to_dict(req.conditions),
        ranking=_to_dict(req.ranking),
        filters=_to_dict(req.filters),
        top_n=req.top_n,
        verbose_excluded=req.verbose_excluded,
    )
    return SnapshotScreenResponse(data=data)


# ============================================================
# 区间选股（FR-5）
# ============================================================

@router.post(
    "/range",
    response_model=RangeScreenResponse,
    summary="区间选股（多日命中分布）",
    description="跨多个交易日重复跑选股，输出每只股票的命中分布（首命中/命中次数/命中率/最大连续命中/逐日明细）。"
                "同树仅校验一次截面违禁。命中判定：以「是否进入当日 stocks 列表」为准"
                "（top_n 截断会影响命中分布，符合研究意图）。"
                "conditions 含截面禁用时序节点返回 422；引用未注册 factorKey 返回 400 UNKNOWN_FACTOR。",
    responses=_ERR,
)
def range_screen(req: RangeScreenRequest):
    # ---- 入参校验 ----
    if not req.dates:
        from core.exceptions import ValidationException
        raise ValidationException("dates 不能为空")
    missing = [d for d in req.dates if d not in req.candidates_by_date]
    if missing:
        from core.exceptions import ValidationException
        raise ValidationException(f"candidates_by_date 缺少日期: {missing}")

    conditions = _to_dict(req.conditions)
    ranking = _to_dict(req.ranking)
    filters = _to_dict(req.filters)
    top_n = req.top_n

    # 截面违禁校验（同树一次）
    if conditions is not None:
        validate_cross_section(conditions)

    total_days = len(req.dates)

    # 收集跨日并集 symbol（按首次出现顺序，保证结果稳定）
    all_symbols: list[str] = []
    seen: set[str] = set()
    for d in req.dates:
        for sym in req.candidates_by_date[d].keys():
            if sym not in seen:
                seen.add(sym)
                all_symbols.append(sym)

    # per-symbol 逐日命中记录
    daily_by_symbol: dict[str, list[DailyHit]] = {sym: [] for sym in all_symbols}

    for d in req.dates:
        day_candidates = req.candidates_by_date[d]
        # 复用单日编排，但只取 stocks（命中 + rank）
        day_data = _run_snapshot(
            date=d,
            candidates=day_candidates,
            conditions=conditions,
            ranking=ranking,
            filters=filters,
            top_n=top_n,
            verbose_excluded=False,
        )
        # 当日命中集合 + rank 映射
        hit_rank: dict[str, int] = {s.symbol: s.rank for s in day_data.stocks}
        hit_set = set(hit_rank.keys())

        for sym in all_symbols:
            if sym in hit_set:
                daily_by_symbol[sym].append(DailyHit(date=d, hit=True, rank=hit_rank[sym]))
            else:
                daily_by_symbol[sym].append(DailyHit(date=d, hit=False, rank=None))

    # ---- 聚合每只 symbol ----
    results: list[RangeStockResult] = []
    for sym in all_symbols:
        hits = daily_by_symbol[sym]
        hit_count = sum(1 for h in hits if h.hit)
        hit_ratio = (hit_count / total_days) if total_days > 0 else 0.0

        # first_hit_date
        first_hit_date: Optional[str] = None
        for h in hits:
            if h.hit:
                first_hit_date = h.date
                break

        # consecutive_max
        consecutive_max = 0
        cur = 0
        for h in hits:
            if h.hit:
                cur += 1
                if cur > consecutive_max:
                    consecutive_max = cur
            else:
                cur = 0

        results.append(
            RangeStockResult(
                symbol=sym,
                first_hit_date=first_hit_date,
                hit_count=hit_count,
                hit_ratio=hit_ratio,
                consecutive_max=consecutive_max,
                daily_hits=hits,
            )
        )

    # 排序：hit_count 降序 → consecutive_max 降序 → symbol 升序
    results.sort(key=lambda r: r.symbol)
    results.sort(key=lambda r: r.consecutive_max, reverse=True)
    results.sort(key=lambda r: r.hit_count, reverse=True)

    return RangeScreenResponse(
        data=RangeScreenResponseData(total_days=total_days, results=results)
    )
