"""P2-9 分批调仓 + 冲击成本 端到端集成测试（spec 013 Task 6）。

验证 compile_strategy 编译链路里与分批/冲击成本相关的几条关键路径：

1. ``SplitState`` 跨日状态机（不依赖 compile_strategy 全链路）；
2. ``build_impact_price_map`` 在 impact_cost_bps>0 时产出调整价（用 fake strategy）；
3. ``build_impact_price_map`` 在 bps=None 时返回空 dict；
4. ``_finalize_exec_diagnosis`` 把内部 _participations 折算为 avg_participation；
5. 全链路：``_attach_rebalance_method`` 挂载的 ``on_daily_rebalance`` 在 split_days=3 时，
   触发日 + 后续 2 个分批日（共 3 次）调用 ``order_target_weights``，每次权重约为
   完整 plan 的 1/3；``_pending_split`` 推进到 exhausted 后被清空；
   ``_exec_diagnosis.splits_completed`` 累计到 3。

第 5 条不依赖 akquant 真实回测环境：构造一个 bare class，调
``_attach_rebalance_method`` 挂上 ``on_daily_rebalance``，再用 MagicMock 实例化
（mock 掉 get_portfolio_value/order_target_weights 等），并 monkeypatch
``_is_rebalance_day``（控制触发日 vs 分批日）、``_compute_target_weights``
（返回固定 plan）、``rebalance_engine.select_at_rebalance_date``（返回固定 scores）
来稳定驱动。
"""
from unittest.mock import MagicMock

import pandas as pd
import pytest

from services.backtest import compiler as compiler_mod
from services.backtest.compiler import (
    SplitState,
    _attach_rebalance_method,
    _finalize_exec_diagnosis,
    build_impact_price_map,
    compute_impact_price,
)


# --------------------------------------------------------------------
# 1. SplitState 跨日状态机
# --------------------------------------------------------------------

def test_split_state_e2e_via_on_daily_rebalance():
    """通过 SplitState 直接验证跨日累计目标行为（不依赖 compile_strategy 全链路）。

    累计目标语义（修正后）：第 k 天的 target = plan × k / N。
    akquant ``order_target_weights`` 是目标语义，传 plan×k/N 才能让仓位在 N 天后
    真正建到完整 plan；每日差额 = plan/N 非零 → trades 分布在 N 天。
    """
    plan = {"S1": 0.6, "S2": 0.4}
    st = SplitState(plan=plan, total_days=3)
    targets = []
    while not st.exhausted:
        t = st.next_target()
        if t is None:
            break
        targets.append(t)
    # 3 天 3 份累计目标
    assert len(targets) == 3
    # 第 k 天累计目标 = plan × k / 3
    assert pytest.approx(targets[0]["S1"], rel=1e-9) == 0.6 * 1 / 3
    assert pytest.approx(targets[1]["S1"], rel=1e-9) == 0.6 * 2 / 3
    # 最后一份严格等于 plan（消除浮点漂移）
    assert targets[2] == plan
    # 状态机耗尽
    assert st.exhausted
    # 耗尽后再取返回 None
    assert st.next_target() is None


# --------------------------------------------------------------------
# 2. build_impact_price_map：冲击成本生效路径
# --------------------------------------------------------------------

def test_build_impact_price_map_e2e_with_fake_strategy():
    """构造 fake strategy 验证 price_map 生��（冲击成本生效路径）。"""
    fake_df = pd.DataFrame({"close": [10.0], "volume": [1000.0]})
    strategy = MagicMock()
    strategy.get_portfolio_value.return_value = 100000.0
    strategy.get_history_df.return_value = fake_df

    weights = {"S1": 0.5}
    # order_value = 100000 * 0.5 = 50000
    # bar_volume_amount = 1000 * 10 = 10000
    # participation = min(50000/10000, 1.0) = 1.0（封顶）
    # impact_bps = 10 * 1.0 = 10bps；买入 sign=+1
    # adj = 10 * (1 + 10/10000) = 10.01
    price_map = build_impact_price_map(
        strategy, weights, impact_cost_bps=10.0,
        trading_date=pd.Timestamp("2024-01-15"), extra_map={},
    )
    assert "S1" in price_map
    assert pytest.approx(price_map["S1"], rel=1e-9) == 10.01


def test_build_impact_price_map_none_when_no_bps():
    """impact_cost_bps=None → 不建模，返回空 dict。"""
    strategy = MagicMock()
    price_map = build_impact_price_map(
        strategy, {"S1": 0.5}, None,
        pd.Timestamp("2024-01-15"), {},
    )
    assert price_map == {}


def test_build_impact_price_map_empty_weights():
    """target_weights 为空 → 空 dict（即使 bps>0）。"""
    strategy = MagicMock()
    price_map = build_impact_price_map(
        strategy, {}, impact_cost_bps=10.0,
        trading_date=pd.Timestamp("2024-01-15"), extra_map={},
    )
    assert price_map == {}


def test_compute_impact_price_unit():
    """compute_impact_price 体积线性建模的单元级 sanity check。"""
    # participation 未封顶：order_value=1000, bar_vol=100000 → 0.01
    adj, part = compute_impact_price(
        price=10.0, order_value=1000.0, bar_volume=100000.0,
        impact_cost_bps=10.0, sign=1,
    )
    # impact_bps = 10 * 0.01 = 0.1bps；adj = 10 * (1 + 0.1/10000)
    assert pytest.approx(part, rel=1e-9) == 0.01
    assert pytest.approx(adj, rel=1e-9) == 10.0 * (1 + 0.1 / 10000.0)

    # 卖出 sign=-1 折价
    adj_s, _ = compute_impact_price(
        price=10.0, order_value=1000.0, bar_volume=100000.0,
        impact_cost_bps=10.0, sign=-1,
    )
    assert pytest.approx(adj_s, rel=1e-9) == 10.0 * (1 - 0.1 / 10000.0)

    # bps=None 不建模
    adj_n, part_n = compute_impact_price(
        price=10.0, order_value=1000.0, bar_volume=100000.0,
        impact_cost_bps=None, sign=1,
    )
    assert adj_n == 10.0
    assert part_n == 0.0


# --------------------------------------------------------------------
# 3. _finalize_exec_diagnosis
# --------------------------------------------------------------------

def test_finalize_exec_diagnosis_computes_avg():
    """_finalize_exec_diagnosis 把 _participations 折算为 avg_participation 并剔除内部字段。"""
    strategy = MagicMock()
    strategy._exec_diagnosis = {
        "split_days": 3, "splits_completed": 3, "splits_interrupted": 0,
        "total_impact_cost": 0.0, "avg_participation": 0.0,
        "_participations": [0.01, 0.02, 0.03],
    }
    out = _finalize_exec_diagnosis(strategy)
    assert out["splits_completed"] == 3
    assert pytest.approx(out["avg_participation"], rel=1e-9) == 0.02
    # 内部累计字段在序列化前被剔除
    assert "_participations" not in out


def test_finalize_exec_diagnosis_none_when_missing():
    """strategy 无 _exec_diagnosis → 返回 None（result_serializer 据此省略字段）。"""
    strategy = MagicMock()
    # MagicMock 默认会让 getattr 返回 truthy MagicMock，需显式 spec 或 del
    # 这里直接用 _exec_diagnosis 不存在语义：用 spec 未声明该属性的对象
    class Bare:
        pass
    out = _finalize_exec_diagnosis(Bare())
    assert out is None


# --------------------------------------------------------------------
# 4. 全链路：_attach_rebalance_method + on_daily_rebalance 驱动 split_days=3
# --------------------------------------------------------------------

class _FakeStrategy:
    """轻量 fake 策略：手写记录 order_target_weights 调用、固定返回行情/权益。

    不用 MagicMock(spec=...) 是因为 on_daily_rebalance 闭包里要
    getattr/setattr 实例属性（_pending_split / _exec_diagnosis / _rb_diagnosis），
    这些不是类声明里的属性，spec 模式会拒绝 setattr。真实类最稳。
    """

    def __init__(self, close=10.0, volume=1000.0, equity=100000.0):
        self._equity = equity
        self._hist_df = pd.DataFrame(
            {"close": [close], "volume": [volume]}
        )
        self._pending_split = None
        self._exec_diagnosis = None
        self._rb_diagnosis = {}
        # 记录所有 order_target_weights 调用：(args, kwargs)
        self.otw_calls: list[tuple[tuple, dict]] = []

    def order_target_weights(self, *args, **kwargs):
        self.otw_calls.append((args, kwargs))

    def get_portfolio_value(self):
        return self._equity

    def get_history_df(self, count=None, symbol=None):
        return self._hist_df


def _attach_daily_rebalance(cls, *, split_days, impact_cost_bps, universe):
    """把 on_daily_rebalance 挂到 cls 上（split_days / impact_cost_bps 可配）。

    rebalance_engine 传真实 RebalanceEngine() 实例（select_at_rebalance_date
    会被测试 monkeypatch 成固定 scores），因为闭包内对它的调用包在
    ``try: ... except Exception: return`` 里，传 None 会静默 return 不下单。
    """
    from services.backtest.rebalance_engine import RebalanceEngine
    _attach_rebalance_method(
        cls,
        frequency="daily",
        trigger=None,
        history_window=60,
        top_n=len(universe),
        weight_mode="equal",
        long_only=True,
        liquidate_unmentioned=True,
        screen_config=None,
        rebalance_engine=RebalanceEngine(),
        universe_symbols=list(universe),
        watcher_client=None,
        extra_map={},
        cash_reserve=0.0,
        max_weight_per_symbol=None,
        max_industry_exposure=None,
        buffer_n=0,
        min_holding_bars=0,
        reject_limit_up_on_buy=True,
        reject_limit_down_on_sell=True,
        use_legacy_rebalance=False,
        split_days=split_days,
        impact_cost_bps=impact_cost_bps,
    )
    assert hasattr(cls, "on_daily_rebalance")


def _patch_rebalance_externals(monkeypatch, plan, scores, trigger_seq):
    """统一 monkeypatch on_daily_rebalance 闭包依赖的 4 个外部符号。

    - compiler._is_rebalance_day：按 trigger_seq 依次返回（True=触发日，False=分批日）；
    - compiler._compute_target_weights：返回固定 plan + 空 diagnosis；
    - compiler._discover_symbols：返回固定 universe（避免依赖真实持仓）；
    - RebalanceEngine.select_at_rebalance_date：返回固定 scores（避免选股/kline）。
    """
    triggers = iter(trigger_seq)
    monkeypatch.setattr(
        compiler_mod, "_is_rebalance_day",
        lambda *a, **k: next(triggers),
    )
    monkeypatch.setattr(
        compiler_mod, "_compute_target_weights",
        lambda **kw: (dict(plan), {}),
    )
    monkeypatch.setattr(
        compiler_mod, "_discover_symbols",
        lambda *a, **k: list(scores.keys()),
    )
    from services.backtest.rebalance_engine import RebalanceEngine
    monkeypatch.setattr(
        RebalanceEngine, "select_at_rebalance_date",
        lambda self, **kw: dict(scores),
    )


def test_full_on_daily_rebalance_split_days_3(monkeypatch):
    """全链路：on_daily_rebalance 跨日驱动，验证分批 3 次 + 状态机 + 诊断累计。

    驱动方式见 _patch_rebalance_externals。触发日 + 后续 2 个分批日，
    共调用 order_target_weights 3 次；累计目标语义下第 k 次传入权重 = plan×k/3
    （最后第 3 次 = plan）；_pending_split 推进到 exhausted 后被清空；
    _exec_diagnosis.splits_completed 累计到 3。

    本测试仍 mock ``order_target_weights``（不验证真实撮合），只校验 compiler 层
    下发的目标权重序列符合「累计目标推进」。真实撮合累计到 plan 的验证见
    ``test_full_on_daily_rebalance_split_days_3_real_engine``。
    """
    plan = {"S1": 0.6, "S2": 0.4}
    scores = {"S1": 1.0, "S2": 0.5}

    class FakeStrategy(_FakeStrategy):
        pass

    _attach_daily_rebalance(
        FakeStrategy, split_days=3, impact_cost_bps=None,
        universe=list(plan.keys()),
    )
    _patch_rebalance_externals(
        monkeypatch, plan=plan, scores=scores,
        trigger_seq=[True, False, False],
    )

    inst = FakeStrategy()

    # Day 1（触发日）：算 plan + 下首份累计目标 + 建 _pending_split/_exec_diagnosis
    FakeStrategy.on_daily_rebalance(inst, pd.Timestamp("2024-01-15"), None)
    assert len(inst.otw_calls) == 1
    first_weights = inst.otw_calls[0][0][0]
    # 第 1 天累计目标 = plan × 1/3
    assert pytest.approx(first_weights["S1"], rel=1e-9) == 0.6 * 1 / 3
    assert pytest.approx(first_weights["S2"], rel=1e-9) == 0.4 * 1 / 3
    # 触发日（split_days>1 分支）用外层 liquidate_unmentioned（True），
    # 与分批日增量分支（False）区分
    assert inst.otw_calls[0][1].get("liquidate_unmentioned") is True
    # 状态机已建且未耗尽
    assert inst._pending_split is not None
    assert not inst._pending_split.exhausted
    # execution 诊断已初始化（split_days>1）
    assert inst._exec_diagnosis is not None
    assert inst._exec_diagnosis["splits_completed"] == 1

    # Day 2（分批日）：累计目标下单，状态机推进
    FakeStrategy.on_daily_rebalance(inst, pd.Timestamp("2024-01-16"), None)
    assert len(inst.otw_calls) == 2
    second_weights = inst.otw_calls[1][0][0]
    # 第 2 天累计目标 = plan × 2/3
    assert pytest.approx(second_weights["S1"], rel=1e-9) == 0.6 * 2 / 3
    assert pytest.approx(second_weights["S2"], rel=1e-9) == 0.4 * 2 / 3
    # 分批日累计目标分支 liquidate_unmentioned=False（不清理未提及标的）
    assert inst.otw_calls[1][1].get("liquidate_unmentioned") is False
    assert not inst._pending_split.exhausted
    assert inst._exec_diagnosis["splits_completed"] == 2

    # Day 3（分批日）：最后一份累计目标 = plan，状态机耗尽后被清空
    FakeStrategy.on_daily_rebalance(inst, pd.Timestamp("2024-01-17"), None)
    assert len(inst.otw_calls) == 3
    third_weights = inst.otw_calls[2][0][0]
    # 第 3 天累计目标 = plan（严格相等，消除浮点漂移）
    assert third_weights == plan
    assert inst.otw_calls[2][1].get("liquidate_unmentioned") is False
    assert inst._pending_split is None  # exhausted 后被清空
    assert inst._exec_diagnosis["splits_completed"] == 3


def test_full_on_daily_rebalance_split_days_1_with_impact(monkeypatch):
    """split_days=1 + impact_cost_bps>0 时，order_target_weights 收到非空 price_map。

    验证冲击成本在一次性调仓路径生效（不走分批分支）。
    """
    plan = {"S1": 0.5}
    scores = {"S1": 1.0}

    class FakeStrategy(_FakeStrategy):
        pass

    _attach_daily_rebalance(
        FakeStrategy, split_days=1, impact_cost_bps=10.0,
        universe=list(plan.keys()),
    )
    _patch_rebalance_externals(
        monkeypatch, plan=plan, scores=scores, trigger_seq=[True],
    )

    inst = FakeStrategy(close=10.0, volume=1000.0, equity=100000.0)
    FakeStrategy.on_daily_rebalance(inst, pd.Timestamp("2024-01-15"), None)

    assert len(inst.otw_calls) == 1
    args, kwargs = inst.otw_calls[0]
    assert args[0] == {"S1": 0.5}
    assert "price_map" in kwargs
    # order_value = 100000 * 0.5 = 50000
    # bar_volume_amount = 1000 * 10 = 10000
    # participation = min(50000/10000, 1.0) = 1.0（封顶）
    # impact_bps = 10 * 1.0 = 10bps；买入 sign=+1
    # adj = 10 * (1 + 10/10000) = 10.01
    assert pytest.approx(kwargs["price_map"]["S1"], rel=1e-9) == 10.01


# --------------------------------------------------------------------
# 5. 真实撮合语义验证：累计目标序列 → 仓位真正建到 plan（非停在 plan/N）
#    不依赖 run_backtest，但精确复现 akquant order_target_weights 的
#    「target_value - current_value 算差额」撮合逻辑（strategy_trading_api.py
#    line 1571/1581-1582），证明「累计目标推进」修正在真实撮合下能让 N 天后
#    仓位 ≈ plan，而旧的「每日增量当目标」会让仓位停在 plan/N。
# --------------------------------------------------------------------

def _simulate_target_weights_matching(target_weights_seq, *, equity, price):
    """模拟 akquant order_target_weights 的撮合语义。

    :param target_weights_seq: 连续多日传入的累计目标权重序列 [{sym: w}, ...]。
    :param equity: 总权益（假设价格不变、无现金流变化，简化）。
    :param price: 每标的成交价（简化为固定价格 dict）。
    :return: (positions_qty, daily_trades) —— 末日持仓数量 + 每日实际成交数量。
    """
    positions = {sym: 0.0 for sym in price}
    daily_trades = []
    for target in target_weights_seq:
        day_trades = {}
        for sym, w in target.items():
            target_value = equity * w
            current_value = positions[sym] * price[sym]
            delta_value = target_value - current_value
            delta_qty = delta_value / price[sym]
            positions[sym] += delta_qty
            day_trades[sym] = delta_qty
        daily_trades.append(day_trades)
    return positions, daily_trades


def test_real_matching_cumulative_target_reaches_plan():
    """真实撮合 + 累计目标：3 天后 S1 持仓 ≈ plan，每日都有非零成交。

    这是 #1 修复的关键回归测试：旧实现传 [plan/3, plan/3, plan/3]（每日增量当目标），
    撮合后仓位会停在 plan/3；修正后传 [plan/3, plan*2/3, plan]（累计目标），
    撮合后仓位真正到 plan。
    """
    plan = {"S1": 0.6, "S2": 0.4}
    equity = 100000.0
    price = {"S1": 10.0, "S2": 20.0}

    # 累计目标序列（修正后 SplitState.next_target 的输出）
    state = SplitState(plan=plan, total_days=3)
    cum_seq = []
    while not state.exhausted:
        t = state.next_target()
        if t is None:
            break
        cum_seq.append(t)

    positions, daily_trades = _simulate_target_weights_matching(
        cum_seq, equity=equity, price=price
    )

    # 3 天后 S1 持仓市值 ≈ equity × 0.6（即仓位到 plan，而非 plan/3）
    assert pytest.approx(positions["S1"] * price["S1"] / equity, rel=1e-9) == 0.6
    assert pytest.approx(positions["S2"] * price["S2"] / equity, rel=1e-9) == 0.4

    # 每日都有非零成交（trades 分布在 3 天，与 spec §93 一致）
    assert all(abs(qty) > 1e-9 for day in daily_trades for qty in day.values())


def test_real_matching_old_increment_semantics_would_stall():
    """反证：旧「每日增量当目标」语义在真实撮合下仓位会停在 plan/N。

    保留此反证测试，防止回退到旧的 next_increment 实现。
    """
    plan = {"S1": 0.6}
    equity = 100000.0
    price = {"S1": 10.0}

    # 旧实现的每日增量序列（plan/N 当目标传入）
    old_inc_seq = [{"S1": 0.6 / 3}] * 3

    positions, _ = _simulate_target_weights_matching(
        old_inc_seq, equity=equity, price=price
    )
    # 旧语义：第 1 天调到 0.2，后续两天 target=0.2、current=0.2 → 差额 0 → 仓位停在 plan/3
    assert pytest.approx(positions["S1"] * price["S1"] / equity, rel=1e-9) == 0.6 / 3
