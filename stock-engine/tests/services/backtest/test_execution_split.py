from services.backtest.compiler import SplitState


def test_split_state_advances_and_exhausts():
    """累计目标语义：第 k 天的 target = plan × k / N。"""
    plan = {"S1": 0.6, "S2": 0.4}
    st = SplitState(plan=plan, total_days=3)
    t1 = st.next_target()
    assert st.current_day == 1
    # 第 1 天累计目标 = plan × 1/3
    assert t1 == {"S1": 0.6 / 3, "S2": 0.4 / 3}
    t2 = st.next_target()
    assert st.current_day == 2
    # 第 2 天累计目标 = plan × 2/3
    assert t2["S1"] == 0.6 * 2 / 3
    assert t2["S2"] == 0.4 * 2 / 3
    t3 = st.next_target()
    assert st.current_day == 3
    # 第 3 天（最后一份）= 完整 plan（消除浮点误差）
    assert t3 == {"S1": 0.6, "S2": 0.4}
    assert st.exhausted
    assert st.next_target() is None


def test_split_state_interrupt_resets():
    st = SplitState(plan={"S1": 1.0}, total_days=3)
    st.next_target()
    st.interrupt()
    assert st.exhausted
    assert st.next_target() is None


def test_split_state_total_days_1_exhausted_immediately():
    """split_days=1 不进入分批路径（exhausted 立即 True，next_target 返回 None）。"""
    st = SplitState(plan={"S1": 1.0}, total_days=1)
    assert st.exhausted
    assert st.next_target() is None


def test_split_state_remaining_days():
    st = SplitState(plan={"S1": 1.0}, total_days=4)
    st.next_target()
    assert st.remaining_days == 3
    st.next_target()
    assert st.remaining_days == 2


def test_split_state_final_target_equals_plan_no_float_drift():
    """最后一份 target 必须严格等于 plan（避免 0.1+0.1+0.1≠0.3 之类浮点漂移）。"""
    plan = {"S1": 0.1, "S2": 0.2, "S3": 0.7}
    st = SplitState(plan=plan, total_days=3)
    targets = []
    while not st.exhausted:
        t = st.next_target()
        if t is None:
            break
        targets.append(t)
    # 最后一份严格等于 plan
    assert targets[-1] == plan
