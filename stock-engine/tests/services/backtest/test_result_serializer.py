"""result_serializer 单元测试（spec 013 Task 5）。

聚焦 ``serialize_result`` 新增的 ``execution_diagnosis`` 输出
（PRD 009 §2.2.4 P2-9：分批调仓 + 冲击成本诊断）：

- 有 ``_exec_diagnosis`` → 经 compiler._finalize_exec_diagnosis 归一化后输出，
  内部字段 ``_participations`` 被剔除，``avg_participation`` 计算正确；
- 无 ``_exec_diagnosis``（择时范式 / 未配置 execution）→ 输出 ``None``。

FakeResult 最小属性集：仅需 ``strategy``。其余 ``metrics_df`` / 曲线 / DataFrame
字段全部缺省（``getattr`` 默认 ``None``）即可被 serialize_result 安全兜底。
"""
import math

from services.backtest.result_serializer import serialize_result


class _Strat:
    """最小策略 stub：按需挂 ``_exec_diagnosis`` / ``_rb_diagnosis``。"""


class _FakeResult:
    """最小 BacktestResult stub。

    serialize_result 对缺失属性全部走 ``getattr(..., None)`` 兜底：
    - ``metrics_df=None`` → ``metrics`` 仅含 ``annual_turnover_ratio``；
    - ``equity_curve_daily=None`` → ``equity_curve={"dates":[],"values":[]}``；
    - ``daily_returns=[]`` → ``[]``；
    - ``*_df=None`` → ``[]``；
    - ``strategy`` 是 ``execution_diagnosis`` / ``rebalance_diagnosis`` 的来源。
    """

    metrics_df = None
    equity_curve_daily = None
    daily_returns = []
    trades_df = None
    orders_df = None
    positions_df = None

    def __init__(self, strategy):
        self.strategy = strategy


def _make_fake_result(exec_diag=None, rb_diag=None):
    s = _Strat()
    if exec_diag is not None:
        s._exec_diagnosis = exec_diag
    if rb_diag is not None:
        s._rb_diagnosis = rb_diag
    return _FakeResult(strategy=s)


# ============================================================
# execution_diagnosis：有数据 → 归一化输出
# ============================================================

def test_serialize_execution_diagnosis_extracted():
    """_exec_diagnosis 经 compiler._finalize_exec_diagnosis 处理后输出。"""
    exec_diag = {
        "split_days": 3,
        "splits_completed": 3,
        "splits_interrupted": 0,
        "total_impact_cost": 100.0,
        "avg_participation": 0.0,
        "_participations": [0.01, 0.02, 0.03],
    }
    result = _make_fake_result(exec_diag=exec_diag)
    out = serialize_result(result)

    ed = out["execution_diagnosis"]
    assert ed is not None
    assert ed["splits_completed"] == 3
    assert ed["split_days"] == 3
    assert ed["total_impact_cost"] == 100.0
    # avg_participation 由 _finalize_exec_diagnosis 用 _participations 重算覆盖
    assert math.isclose(ed["avg_participation"], 0.02, abs_tol=1e-9)
    # 内部字段必须被剔除
    assert "_participations" not in ed


# ============================================================
# execution_diagnosis：无数据 → None
# ============================================================

def test_serialize_execution_diagnosis_none_when_absent():
    """strategy 无 _exec_diagnosis（择时范式 / 未配置 execution）→ None。"""
    result = _make_fake_result()
    out = serialize_result(result)
    assert out["execution_diagnosis"] is None


def test_serialize_execution_diagnosis_none_for_timing_only_strategy():
    """择时范式 strategy 仅挂 _rb_diagnosis 也不应误取 execution。"""
    result = _make_fake_result(rb_diag={"selected_count": 5})
    out = serialize_result(result)
    assert out["execution_diagnosis"] is None


def test_serialize_execution_diagnosis_none_when_strategy_missing():
    """result 无 strategy 属性 → execution_diagnosis 为 None，不抛异常。"""

    class _BareResult:
        metrics_df = None
        equity_curve_daily = None
        daily_returns = []
        trades_df = None
        orders_df = None
        positions_df = None

    out = serialize_result(_BareResult())
    assert out["execution_diagnosis"] is None


# ============================================================
# execution_diagnosis：空 dict → None（归一化后判定为无诊断）
# ============================================================

def test_serialize_execution_diagnosis_none_when_empty_after_finalize():
    """_finalize_exec_diagnosis 对缺 _exec_diagnosis 的 strategy 返回 None → 输出 None。"""

    class _StratNoDiag:
        pass

    result = _FakeResult(strategy=_StratNoDiag())
    out = serialize_result(result)
    assert out["execution_diagnosis"] is None
