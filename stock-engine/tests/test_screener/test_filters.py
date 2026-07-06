"""apply_filters 静态过滤单元测试（spec 003 阶段 3 Task 12.4）。

覆盖 Schema §3.2.2 的全部过滤维度：ST / 停牌 / 涨停 / 跌停（默认值） /
行业白名单 / 行业黑名单 / 最小上市天数 / verbose 明细 / filters=None 全默认。
"""
from services.screener.filters import apply_filters


def _mk(meta: dict) -> dict:
    """构造单只候选股票 dict（只关心 meta）。"""
    return {"ohlcv_history": [], "fundamentals": {}, "meta": meta}


def test_exclude_st():
    """exclude_st=True（默认）：is_st=True 的股票被排除。"""
    candidates = {
        "A": _mk({"is_st": False, "industry": "银行"}),
        "B": _mk({"is_st": True, "industry": "银行"}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["A"]


def test_exclude_suspended_default_true():
    """exclude_suspended 默认 True：停牌股被排除。"""
    candidates = {
        "A": _mk({"is_suspended": False}),
        "B": _mk({"is_suspended": True}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["A"]


def test_exclude_limit_up_default_true():
    """exclude_limit_up 默认 True：涨停股被排除。"""
    candidates = {
        "A": _mk({"is_limit_up": False}),
        "B": _mk({"is_limit_up": True}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["A"]


def test_exclude_limit_down_default_false():
    """exclude_limit_down 默认 False：跌停股默认不被排除（仍保留）。"""
    candidates = {
        "A": _mk({"is_limit_down": False}),
        "B": _mk({"is_limit_down": True}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["A", "B"]


def test_industries_whitelist():
    """行业白名单：仅保留指定行业。"""
    candidates = {
        "A": _mk({"industry": "银行"}),
        "B": _mk({"industry": "房地产"}),
        "C": _mk({"industry": "银行"}),
    }
    passed, _ = apply_filters(candidates, filters={"industries": ["银行"]})
    assert passed == ["A", "C"]


def test_exclude_industries_blacklist():
    """行业黑名单：排除指定行业。"""
    candidates = {
        "A": _mk({"industry": "银行"}),
        "B": _mk({"industry": "房地产"}),
        "C": _mk({"industry": "白酒"}),
    }
    passed, _ = apply_filters(
        candidates, filters={"exclude_industries": ["房地产", "白酒"]}
    )
    assert passed == ["A"]


def test_min_list_days_excludes_recent_listing():
    """min_list_days：list_date 距离 screen_date 不足 → 排除（次新股过滤）。"""
    candidates = {
        "OLD": _mk({"list_date": "2010-01-01"}),
        "NEW": _mk({"list_date": "2026-05-01"}),
    }
    # 选股日 2026-07-03：OLD 约 6000+ 天，NEW 约 63 天
    passed, _ = apply_filters(
        candidates, filters={"min_list_days": 365}, screen_date="2026-07-03"
    )
    assert passed == ["OLD"]


def test_min_list_days_excludes_missing_list_date():
    """min_list_days 保守策略：list_date 缺失 → 排除。"""
    candidates = {
        "WITH_DATE": _mk({"list_date": "2010-01-01"}),
        "NO_DATE": _mk({}),  # 无 list_date
    }
    passed, _ = apply_filters(
        candidates, filters={"min_list_days": 365}, screen_date="2026-07-03"
    )
    assert passed == ["WITH_DATE"]


def test_verbose_excluded_detail():
    """verbose=True：excluded dict 各分类非空数组（含被排除的 symbol）。"""
    candidates = {
        "ST_STOCK": _mk({"is_st": True}),
        "SUSPENDED": _mk({"is_suspended": True}),
        "LIMIT_UP": _mk({"is_limit_up": True}),
        "GOOD": _mk({"is_st": False, "is_suspended": False, "is_limit_up": False}),
    }
    passed, excluded = apply_filters(candidates, filters=None, verbose=True)
    assert passed == ["GOOD"]
    assert "ST_STOCK" in excluded["st"]
    assert "SUSPENDED" in excluded["suspended"]
    assert "LIMIT_UP" in excluded["limit_up"]


def test_verbose_false_empty_excluded():
    """verbose=False（默认）：excluded 为空 dict（减少开销）。"""
    candidates = {"A": _mk({"is_st": True}), "B": _mk({})}
    passed, excluded = apply_filters(candidates, filters=None, verbose=False)
    assert passed == ["B"]
    assert excluded == {}


def test_filters_none_uses_all_defaults():
    """filters=None → 全默认（exclude_st/suspended/limit_up=True，limit_down=False，
    industries 空，min_list_days=0）。
    """
    candidates = {
        "ST": _mk({"is_st": True}),
        "OK": _mk({"is_st": False}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["OK"]


def test_filters_partial_override_keeps_other_defaults():
    """filters 部分字段覆盖：其余字段保持默认（_merge_defaults 行为）。"""
    candidates = {
        "ST": _mk({"is_st": True}),
        "LIMIT_UP": _mk({"is_limit_up": True}),
        "OK": _mk({"is_st": False, "is_limit_up": False}),
    }
    # 显式关闭 exclude_st，但仍保留 exclude_limit_up 默认 True
    passed, _ = apply_filters(candidates, filters={"exclude_st": False})
    assert "ST" in passed
    assert "LIMIT_UP" not in passed
    assert "OK" in passed


def test_passed_preserves_candidate_order():
    """passed_symbols 保留 candidates 的迭代顺序。"""
    candidates = {
        "Z": _mk({}),
        "A": _mk({}),
        "M": _mk({}),
    }
    passed, _ = apply_filters(candidates, filters=None)
    assert passed == ["Z", "A", "M"]
