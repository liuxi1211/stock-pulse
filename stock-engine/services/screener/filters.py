"""选股中心：静态过滤（统一策略配置 Schema ��3.2.2 / spec 003 阶段 1 Task 4）。

职责：在条件求值之前，按用户传入的 ``filters``（ST/停牌/涨跌停/行业/上市天数）做静态过滤，
输出通过过滤的 symbol 列表 +（可选）排除明细。

调用约定：
- 本函数只做过滤，不做条件求值；调用顺序由路由层保证（filters → precompute → engine → rank）。
- 过滤用的标记字段统一放在 ``candidate["meta"]`` 字典里（is_st / is_suspended /
  is_limit_up / is_limit_down / industry / list_date），meta 缺失视为该股无该维度标记
  （布尔类默认 False，行业默认空串，上市日期默认 None）。
- 上市天数过滤为保守策略：``list_date`` 缺失/不可解析且 ``min_list_days > 0`` 时排除该股
  （次新股过滤场景下宁可误杀，避免数据缺失的次新股穿透）。
- ``filters`` 为 None 时使用全默认值（与 Schema 默认对齐）。
- 约束（spec AC-11）：engine 不触库，本模块纯内存计算，无 sqlite3/sqlalchemy。
"""
from datetime import date
from typing import Any, Optional

from core.logger import logger

# Schema 默认值（§3.2.2）
_DEFAULT_FILTERS: dict[str, Any] = {
    "exclude_st": True,
    "exclude_suspended": True,
    "exclude_limit_up": True,
    "exclude_limit_down": False,
    "industries": [],
    "exclude_industries": [],
    "min_list_days": 0,
}

# excluded_detail 的 key 列表（顺序与过滤顺序一致）
_EXCLUDE_KEYS = ("st", "suspended", "limit_up", "limit_down", "industry", "list_days")


def apply_filters(
    candidates: dict[str, dict],
    filters: Optional[dict],
    *,
    screen_date: Optional[str] = None,
    verbose: bool = False,
) -> tuple[list[str], dict]:
    """对候选股票池按 filters 做静态过滤。

    :param candidates: ``{symbol: {"ohlcv_history": [...], "fundamentals": {...},
        "meta": {"is_st": bool, "is_suspended": bool, "is_limit_up": bool,
        "is_limit_down": bool, "industry": str, "list_date": "YYYY-MM-DD"}}}``。
    :param filters: Schema §3.2.2 的过滤配置；None 用全默认。
    :param screen_date: 选股日（YYYY-MM-DD），用于上市天数计算；仅当
        ``min_list_days > 0`` 时生效。缺失时若开启了上市天数过滤则按保守策略处理。
    :param verbose: True 时填充非空排除明细；False 返回空 dict 减少开销。
    :return: ``(passed_symbols, excluded_detail)``。
        - passed_symbols：通过过滤的 symbol 列表，保留 candidates 的迭代顺序。
        - excluded_detail：``{"st":[...], "suspended":[...], "limit_up":[...],
          "limit_down":[...], "industry":[...], "list_days":[...]}``，仅 verbose=True
          时填充非空数组。
    """
    # 合并默认值（None → 全默认；部分字段缺失则取默认）
    f = _merge_defaults(filters)

    exclude_st = bool(f["exclude_st"])
    exclude_suspended = bool(f["exclude_suspended"])
    exclude_limit_up = bool(f["exclude_limit_up"])
    exclude_limit_down = bool(f["exclude_limit_down"])
    industries_whitelist = _normalize_industry_list(f.get("industries"))
    industries_blacklist = _normalize_industry_list(f.get("exclude_industries"))
    min_list_days = _to_int_or_zero(f.get("min_list_days"))

    # 解析 screen_date（上市天数过滤开启时才需要）
    screen_d: Optional[date] = None
    if min_list_days > 0 and screen_date:
        screen_d = _parse_date(screen_date)
        if screen_d is None:
            logger.warning("apply_filters: screen_date 解析失败 screen_date=%s", screen_date)

    # 排除明细容器（verbose 时才真正收集）
    excluded: dict[str, list[str]] = (
        {k: [] for k in _EXCLUDE_KEYS} if verbose else {}
    )

    passed: list[str] = []

    for symbol, candidate in candidates.items():
        meta = (candidate.get("meta") or {}) if isinstance(candidate, dict) else {}

        # 1) ST/*ST
        if exclude_st and bool(meta.get("is_st", False)):
            if verbose:
                excluded["st"].append(symbol)
            continue

        # 2) 停牌
        if exclude_suspended and bool(meta.get("is_suspended", False)):
            if verbose:
                excluded["suspended"].append(symbol)
            continue

        # 3) 涨停
        if exclude_limit_up and bool(meta.get("is_limit_up", False)):
            if verbose:
                excluded["limit_up"].append(symbol)
            continue

        # 4) 跌停
        if exclude_limit_down and bool(meta.get("is_limit_down", False)):
            if verbose:
                excluded["limit_down"].append(symbol)
            continue

        # 5) 行业白名单 / 黑名单
        industry = str(meta.get("industry", "") or "")
        if industries_whitelist and industry not in industries_whitelist:
            if verbose:
                excluded["industry"].append(symbol)
            continue
        if industries_blacklist and industry in industries_blacklist:
            if verbose:
                excluded["industry"].append(symbol)
            continue

        # 6) 上市天数
        if min_list_days > 0:
            list_date_raw = meta.get("list_date")
            list_d = _parse_date(list_date_raw) if list_date_raw else None
            if list_d is None or screen_d is None:
                # 保守策略：list_date 缺失/不可解析 或 screen_date 缺失 → 排除（次新股过滤场景）
                if verbose:
                    excluded["list_days"].append(symbol)
                continue
            list_days = (screen_d - list_d).days
            if list_days < min_list_days:
                if verbose:
                    excluded["list_days"].append(symbol)
                continue

        passed.append(symbol)

    return passed, excluded


# ============================================================
# 内部工具
# ============================================================

def _merge_defaults(filters: Optional[dict]) -> dict[str, Any]:
    """合并用户 filters 与默认值（None → 全默认；逐字段缺失补默认）。"""
    if not filters:
        # 返回浅拷贝避免调用方误改模块级默认
        return dict(_DEFAULT_FILTERS)
    merged = dict(_DEFAULT_FILTERS)
    merged.update(filters)
    return merged


def _normalize_industry_list(raw: Any) -> set[str]:
    """行业列表归一为非空 str 集合（None/非可迭代/全空 → 空集合）。"""
    if raw is None:
        return set()
    if isinstance(raw, (str, bytes)):
        # 字符串当成单元素列表（容错）
        raw = [raw]
    try:
        return {str(x).strip() for x in raw if str(x).strip()}
    except TypeError:
        return set()


def _to_int_or_zero(value: Any) -> int:
    """转非负整数；失败/负数 → 0。"""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return 0
    return n if n > 0 else 0


def _parse_date(value: Any) -> Optional[date]:
    """解析 YYYY-MM-DD（兼容 datetime/date/iso str）；失败 → None。"""
    if value is None:
        return None
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        try:
            return date.fromisoformat(value.strip()[:10])
        except (ValueError, TypeError):
            return None
    return None
