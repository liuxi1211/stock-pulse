"""rank_stocks 排序打分单元测试（spec 003 阶段 3 Task 12.3）。

覆盖：
- single 模式：升序 / 降序 / rank 从 1 起
- single NaN 末尾：因子值缺失 → 排末尾
- single 同分 symbol 升序兜底
- composite 模式：正权重（越大越好）/ 负权重（越小越好）
- composite 全 NaN 维度剔除
- top_n 截断
- 返回结构：symbol/rank/score/factor_values 键；NaN → None
"""
import math

from services.screener.ranking import rank_stocks


# ============================================================
# single 模式
# ============================================================

def test_single_rank_ascending():
    """single 升序：3 只股票按 PE_TTM 升序，rank 从 1 起。"""
    hits = ["A", "B", "C"]
    fv = {
        "A": {"PE_TTM": 30.0},
        "B": {"PE_TTM": 10.0},
        "C": {"PE_TTM": 20.0},
    }
    ranking = {"method": "single", "factor": "PE_TTM", "order": "asc"}
    out = rank_stocks(hits, fv, ranking)
    assert [r["symbol"] for r in out] == ["B", "C", "A"]
    assert [r["rank"] for r in out] == [1, 2, 3]
    assert [r["score"] for r in out] == [10.0, 20.0, 30.0]


def test_single_rank_descending():
    """single 降序（默认 order=desc）：高分在前。"""
    hits = ["A", "B", "C"]
    fv = {
        "A": {"PE_TTM": 30.0},
        "B": {"PE_TTM": 10.0},
        "C": {"PE_TTM": 20.0},
    }
    ranking = {"method": "single", "factor": "PE_TTM"}  # 默认 desc
    out = rank_stocks(hits, fv, ranking)
    assert [r["symbol"] for r in out] == ["A", "C", "B"]


def test_single_nan_goes_to_tail():
    """single NaN：因子值缺失（NaN）排到末尾。"""
    hits = ["A", "B", "C"]
    fv = {
        "A": {"PE_TTM": 20.0},
        "B": {},  # 缺失 → NaN
        "C": {"PE_TTM": 10.0},
    }
    ranking = {"method": "single", "factor": "PE_TTM", "order": "asc"}
    out = rank_stocks(hits, fv, ranking)
    syms = [r["symbol"] for r in out]
    # 非 NaN 段升序：C(10) < A(20)；NaN 末尾：B
    assert syms == ["C", "A", "B"]
    # B 的 score 应为 None（NaN 转 None）
    b = next(r for r in out if r["symbol"] == "B")
    assert b["score"] is None


def test_single_same_score_symbol_asc_tiebreak():
    """single 同分：按 symbol 升序兜底。"""
    hits = ["BBB", "AAA", "CCC"]
    fv = {
        "AAA": {"PE_TTM": 10.0},
        "BBB": {"PE_TTM": 10.0},
        "CCC": {"PE_TTM": 10.0},
    }
    ranking = {"method": "single", "factor": "PE_TTM", "order": "asc"}
    out = rank_stocks(hits, fv, ranking)
    assert [r["symbol"] for r in out] == ["AAA", "BBB", "CCC"]


# ============================================================
# composite 模式
# ============================================================

def test_composite_positive_weight_descending():
    """composite 正权重：weights={ROE_TTM:1.0}（越大越好）→ 综合分降序。"""
    hits = ["A", "B", "C"]
    fv = {
        "A": {"ROE_TTM": 5.0},
        "B": {"ROE_TTM": 30.0},
        "C": {"ROE_TTM": 20.0},
    }
    ranking = {"method": "composite", "weights": {"ROE_TTM": 1.0}}
    out = rank_stocks(hits, fv, ranking)
    # ROE 越大综合分越高 → B > C > A
    assert [r["symbol"] for r in out] == ["B", "C", "A"]
    # 综合分非空
    assert all(r["score"] is not None for r in out)


def test_composite_negative_weight_reverses_order():
    """composite 负权重：weights={PE_TTM:-1.0}（越小越好）→ 反序。"""
    hits = ["A", "B", "C"]
    fv = {
        "A": {"PE_TTM": 30.0},
        "B": {"PE_TTM": 10.0},
        "C": {"PE_TTM": 20.0},
    }
    ranking = {"method": "composite", "weights": {"PE_TTM": -1.0}}
    out = rank_stocks(hits, fv, ranking)
    # PE 越小综合分越高 → B(10) > C(20) > A(30)
    assert [r["symbol"] for r in out] == ["B", "C", "A"]


def test_composite_nan_dimension_excluded():
    """composite 全 NaN 维度剔除：某股票某维度缺失，综合分按其非 NaN 维度归一化。

    构造两维 ROE(+0.6) / PE(-0.4)：
      A: ROE=20, PE=8
      B: ROE=10, PE=20
      C: ROE=15, PE 缺失 → 仅按 ROE 维度算分
    """
    hits = ["A", "B", "C"]
    fv = {
        "A": {"ROE_TTM": 20.0, "PE_TTM": 8.0},
        "B": {"ROE_TTM": 10.0, "PE_TTM": 20.0},
        "C": {"ROE_TTM": 15.0},  # PE 缺失
    }
    ranking = {"method": "composite", "weights": {"ROE_TTM": 0.6, "PE_TTM": -0.4}}
    out = rank_stocks(hits, fv, ranking)
    # 三只都应有 score（C 仅按 ROE 维度归一，不会因 PE 缺失变 None）
    assert all(r["score"] is not None for r in out)
    # 至少返回 3 只
    assert len(out) == 3


def test_composite_all_nan_dimension_zero_score():
    """某股票全部维度缺失 → 由于非 NaN 样本数 < 2，该维度 z-score 退化为 0，
    综合分按归一化后 = 0.0（不是 None，因为 z 不是 NaN，是 0）。

    注：rank_stocks 的 _zscore 在样本<2 / std=0 时返回全 0（不返回 NaN），
    所以综合分为有限数 0.0；只有综合分本身为 NaN（如分子分母同时为 0 的极端情形）
    才转 None。这里反映被测模块真实行为：score=0.0，且 B 排在综合分较高的 A 之后。
    """
    hits = ["A", "B"]
    fv = {
        "A": {"ROE_TTM": 20.0},
        "B": {},  # 全缺失 → ROE 维度仅 A 有值（样本数 1 < 2 → z 全 0）
    }
    ranking = {"method": "composite", "weights": {"ROE_TTM": 1.0}}
    out = rank_stocks(hits, fv, ranking)
    b = next(r for r in out if r["symbol"] == "B")
    # 综合分 0.0（z 全 0 → 加权 0 / 归一 1 = 0）
    assert b["score"] == 0.0


# ============================================================
# top_n 截断
# ============================================================

def test_top_n_truncation():
    """top_n 截断：返回长度 = top_n。"""
    hits = ["A", "B", "C", "D"]
    fv = {s: {"PE_TTM": float(v)} for s, v in zip(hits, [40, 10, 30, 20])}
    ranking = {"method": "single", "factor": "PE_TTM", "order": "asc"}
    out = rank_stocks(hits, fv, ranking, top_n=2)
    assert len(out) == 2
    assert [r["symbol"] for r in out] == ["B", "D"]  # 10, 20 最小两只
    assert [r["rank"] for r in out] == [1, 2]


def test_top_n_none_no_truncation():
    """top_n=None 不截断。"""
    hits = ["A", "B"]
    fv = {"A": {"PE_TTM": 1.0}, "B": {"PE_TTM": 2.0}}
    out = rank_stocks(hits, fv, {"method": "single", "factor": "PE_TTM"}, top_n=None)
    assert len(out) == 2


# ============================================================
# 返回结构
# ============================================================

def test_return_structure_keys():
    """返回元素含 symbol / rank / score / factor_values 键。"""
    hits = ["A"]
    fv = {"A": {"PE_TTM": 10.0}}
    out = rank_stocks(hits, fv, {"method": "single", "factor": "PE_TTM"})
    assert len(out) == 1
    r = out[0]
    assert set(r.keys()) >= {"symbol", "rank", "score", "factor_values"}
    assert r["symbol"] == "A"
    assert r["rank"] == 1
    assert r["score"] == 10.0


def test_factor_values_nan_to_none():
    """factor_values 中的 NaN 一律转 None（JSON 友好）。"""
    hits = ["A"]
    fv = {"A": {"PE_TTM": float("nan"), "ROE_TTM": 15.0}}
    out = rank_stocks(hits, fv, {"method": "single", "factor": "ROE_TTM"})
    fvals = out[0]["factor_values"]
    assert fvals["PE_TTM"] is None
    assert fvals["ROE_TTM"] == 15.0


def test_ranking_none_keeps_original_order():
    """ranking=None：原序输出，rank 从 1 起，score=None。"""
    hits = ["C", "A", "B"]
    fv = {s: {} for s in hits}
    out = rank_stocks(hits, fv, None)
    assert [r["symbol"] for r in out] == ["C", "A", "B"]
    assert [r["rank"] for r in out] == [1, 2, 3]
    assert all(r["score"] is None for r in out)
