"""选股中心 API 集成测试（spec 003 阶段 3 Task 12.5 端到端）。

用 ``screener_client``（TestClient，factor_precompute 已 patch 到 temp_registry）测：

- AC-4 快照选股端到端：5 只候选 + PE_TTM<20 AND ROE_TTM>10 + single 排序 → 命中 2 只
- AC-6 静态过滤：filters + industries 白名单
- AC-3 截面禁用项：cross_up → 422 SCREEN_TIME_SERIES_FORBIDDEN
- AC-10 缺失 factorKey：UNKNOWN_FX → 400 UNKNOWN_FACTOR
- AC-5 composite 排序：weights z-score 加权
- AC-7 top_n 截断
- AC-8 区间选股：first_hit_date/hit_count/hit_ratio/consecutive_max/daily_hits
"""
import pytest

# 公共条件树：低估值优质（PE_TTM<20 AND ROE_TTM>10）
_LOW_VALUATION_QUALITY = {
    "operator": "AND",
    "conditions": [
        {
            "type": "compare",
            "left": {"factor": "PE_TTM"},
            "comparator": "<",
            "right": {"value": 20},
        },
        {
            "type": "compare",
            "left": {"factor": "ROE_TTM"},
            "comparator": ">",
            "right": {"value": 10},
        },
    ],
}


def _snapshot_payload(candidates, **overrides):
    """构造快照选股请求体。"""
    payload = {
        "universe": "manual",
        "date": "2026-07-03",
        "candidates": candidates,
    }
    payload.update(overrides)
    return payload


# ============================================================
# AC-4 快照选股端到端
# ============================================================

def test_snapshot_end_to_end(screener_client, sample_candidates):
    """AC-4：PE<20 AND ROE>10 + ranking single(TOTAL_MV, asc) → 命中 000001.SZ 与 600000.SH。"""
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions=_LOW_VALUATION_QUALITY,
            ranking={"method": "single", "factor": "TOTAL_MV", "order": "asc"},
        ),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    data = body["data"]
    syms = [s["symbol"] for s in data["stocks"]]
    # 000001.SZ(PE=8,ROE=18) 与 600000.SH(PE=15,ROE=12) 命中
    assert set(syms) == {"000001.SZ", "600000.SH"}
    # TOTAL_MV asc：1.8e10(600000) < 2.0e10(000001)
    assert syms == ["600000.SH", "000001.SZ"]
    # rank 从 1 起
    assert [s["rank"] for s in data["stocks"]] == [1, 2]
    # total_count = 命中数（未 top_n 截断前）
    assert data["total_count"] == 2


# ============================================================
# AC-6 静态过滤
# ============================================================

def test_snapshot_static_filters_industry_whitelist(screener_client, sample_candidates):
    """AC-6：filters 全开 + industries=["银行"] → 仅保留 000001.SZ 与 600000.SH。"""
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions=None,  # 仅做静态过滤
            filters={
                "exclude_st": True,
                "exclude_suspended": True,
                "exclude_limit_up": True,
                "exclude_limit_down": True,
                "industries": ["银行"],
                "exclude_industries": [],
                "min_list_days": 0,
            },
            ranking=None,
            verbose_excluded=True,
        ),
    )
    assert r.status_code == 200
    data = r.json()["data"]
    syms = [s["symbol"] for s in data["stocks"]]
    assert set(syms) == {"000001.SZ", "600000.SH"}
    excluded = data["excluded"]
    # ST(000002)、涨停(600519)、次新(300750) 各自进对应分类
    assert "000002.SZ" in excluded["st"]
    assert "600519.SH" in excluded["limit_up"]


# ============================================================
# AC-3 截面禁用项（API 层）
# ============================================================

def test_snapshot_cross_up_returns_422(screener_client, sample_candidates):
    """AC-3：conditions 含 cross_up → 422 + errorCode SCREEN_TIME_SERIES_FORBIDDEN。"""
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions={
                "operator": "AND",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "PE_TTM"},
                        "comparator": "cross_up",
                        "right": {"value": 10},
                    }
                ],
            },
        ),
    )
    assert r.status_code == 422
    assert r.json()["errorCode"] == "SCREEN_TIME_SERIES_FORBIDDEN"


# ============================================================
# AC-10 缺失 factorKey
# ============================================================

def test_snapshot_unknown_factor_returns_400(screener_client, sample_candidates):
    """AC-10：conditions 引用 UNKNOWN_FX → 400 + errorCode UNKNOWN_FACTOR。

    注：``SnapshotScreenRequest.conditions`` 类型为 ``Optional[ConditionTree]``，
    必须以逻辑组（含 operator）开头，单叶子需包进 AND 树（这是 Schema 的真实要求）。
    """
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions={
                "operator": "AND",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "UNKNOWN_FX"},
                        "comparator": ">",
                        "right": {"value": 0},
                    }
                ],
            },
        ),
    )
    assert r.status_code == 400
    assert r.json()["errorCode"] == "UNKNOWN_FACTOR"


# ============================================================
# AC-5 composite 排序
# ============================================================

def test_snapshot_composite_ranking(screener_client, sample_candidates):
    """AC-5：weights={ROE_TTM:0.7, PE_TTM:-0.3} → 按 z-score 加权降序，返回 score。"""
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions=_LOW_VALUATION_QUALITY,
            ranking={
                "method": "composite",
                "weights": {"ROE_TTM": 0.7, "PE_TTM": -0.3},
            },
        ),
    )
    assert r.status_code == 200
    data = r.json()["data"]
    stocks = data["stocks"]
    assert len(stocks) == 2
    # composite 模式 score 应非空
    assert all(s["score"] is not None for s in stocks)
    # 综合分应降序（rank 1 的 score >= rank 2）
    assert stocks[0]["score"] >= stocks[1]["score"]


# ============================================================
# AC-7 top_n 截断
# ============================================================

def test_snapshot_top_n_truncation(screener_client, sample_candidates):
    """AC-7：100% 命中的小池子 + top_n=1 → stocks 长度=1，total_count=全量命中数。"""
    # 用一个能同时命中两只的条件（PE<20 AND ROE>10 → 000001 + 600000）
    r = screener_client.post(
        "/python/v1/screener/snapshot",
        json=_snapshot_payload(
            sample_candidates,
            conditions=_LOW_VALUATION_QUALITY,
            ranking={"method": "single", "factor": "TOTAL_MV", "order": "asc"},
            top_n=1,
        ),
    )
    assert r.status_code == 200
    data = r.json()["data"]
    assert len(data["stocks"]) == 1
    # total_count 是截断前的命中总数
    assert data["total_count"] == 2


# ============================================================
# AC-8 区间选股
# ============================================================

def test_range_screen_hit_distribution(screener_client, sample_candidates):
    """AC-8：dates=[D1,D2,D3] + conditions → 返回每只股票的命中分布。"""
    # 用 ohlcv 拼三日 candidates_by_date；为区分每日命中差异，
    # 让 600000.SH 在 D2 改成 PE=30（不命中），其他日维持原值。
    candidates_by_date = {}
    for d in ["2026-07-01", "2026-07-02", "2026-07-03"]:
        cands = {sym: {**c} for sym, c in sample_candidates.items()}
        if d == "2026-07-02":
            cands["600000.SH"] = {
                **cands["600000.SH"],
                "fundamentals": {"PE_TTM": 30.0, "ROE_TTM": 12.0, "TOTAL_MV": 1.8e10},
            }
        candidates_by_date[d] = cands

    r = screener_client.post(
        "/python/v1/screener/range",
        json={
            "universe": "manual",
            "dates": ["2026-07-01", "2026-07-02", "2026-07-03"],
            "candidates_by_date": candidates_by_date,
            "conditions": _LOW_VALUATION_QUALITY,
            "filters": {
                "exclude_st": True,
                "exclude_suspended": True,
                "exclude_limit_up": True,
                "exclude_limit_down": True,
                "industries": [],
                "exclude_industries": [],
                "min_list_days": 0,
            },
            "ranking": None,
            "top_n": None,
        },
    )
    assert r.status_code == 200
    data = r.json()["data"]
    assert data["total_days"] == 3

    by_sym = {r["symbol"]: r for r in data["results"]}

    # 000001.SZ 三日全命中
    s = by_sym["000001.SZ"]
    assert s["hit_count"] == 3
    assert s["hit_ratio"] == pytest.approx(1.0)
    assert s["consecutive_max"] == 3
    assert s["first_hit_date"] == "2026-07-01"
    assert len(s["daily_hits"]) == 3
    assert [h["hit"] for h in s["daily_hits"]] == [True, True, True]

    # 600000.SH D1/D3 命中、D2 不命中
    s = by_sym["600000.SH"]
    assert s["hit_count"] == 2
    assert s["hit_ratio"] == pytest.approx(2 / 3)
    assert s["consecutive_max"] == 1  # 中间断开，最大连续 1
    assert s["first_hit_date"] == "2026-07-01"
    assert [h["hit"] for h in s["daily_hits"]] == [True, False, True]

    # ST/涨停/次新 在静态过滤阶段就被排除，不会出现在 results 的命中里
    # （但会以 hit=False 出现在 daily_hits，因为 range 取跨日并集 symbol）
    for sym in ["000002.SZ", "600519.SH", "300750.SZ"]:
        s = by_sym[sym]
        assert s["hit_count"] == 0
        assert s["first_hit_date"] is None


def test_range_screen_cross_up_returns_422(screener_client, sample_candidates):
    """AC-3 区间路径：conditions 含 cross_up → 422。"""
    r = screener_client.post(
        "/python/v1/screener/range",
        json={
            "universe": "manual",
            "dates": ["2026-07-01"],
            "candidates_by_date": {"2026-07-01": sample_candidates},
            "conditions": {
                "operator": "AND",
                "conditions": [
                    {
                        "type": "compare",
                        "left": {"factor": "PE_TTM"},
                        "comparator": "cross_down",
                        "right": {"value": 10},
                    }
                ],
            },
        },
    )
    assert r.status_code == 422
    assert r.json()["errorCode"] == "SCREEN_TIME_SERIES_FORBIDDEN"
