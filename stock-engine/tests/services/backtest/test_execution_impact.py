import math

from services.backtest.compiler import compute_impact_price


def test_no_impact_when_bps_none():
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, None, sign=1)
    assert price == 10.0
    assert part == 0.0


def test_buy_impact_raises_price():
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, 10.0, sign=1)
    assert math.isclose(price, 10.001, abs_tol=1e-9)
    assert math.isclose(part, 0.1, abs_tol=1e-9)


def test_sell_impact_lowers_price():
    price, part = compute_impact_price(10.0, 10000.0, 100000.0, 10.0, sign=-1)
    assert math.isclose(price, 10.0 * (1 - 0.0001), abs_tol=1e-9)


def test_participation_capped():
    price, part = compute_impact_price(10.0, 1_000_000.0, 1000.0, 10.0, sign=1)
    assert part == 1.0
    assert math.isclose(price, 10.0 * (1 + 10 / 10000), abs_tol=1e-9)


def test_zero_bar_volume_capped():
    price, part = compute_impact_price(10.0, 1000.0, 0.0, 10.0, sign=1)
    assert part == 1.0


def test_negative_bps_no_impact():
    price, part = compute_impact_price(10.0, 1000.0, 1000.0, -5.0, sign=1)
    assert price == 10.0
    assert part == 0.0
