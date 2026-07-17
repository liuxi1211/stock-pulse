"""网格策略盈亏对账（spec 015 §S3 / FR-G5）。

双 oracle 对账：
- oracle_A：手工逐步累加（每笔成交按公式算净盈亏后累加）
- oracle_B：akquant trades_df.net_pnl 求和
- 断言 abs(总盈亏 - oracle_A) < 0.01 且 abs(总盈亏 - oracle_B) < 0.01 且 abs(oracle_A - oracle_B) < 0.01

成本公式（§S3.3）：
- 买入成本 = max(qty × price × commission_rate, min_commission)（无印花税）
- 卖出成本 = max(qty × price × commission_rate, min_commission) + qty × price × stamp_tax_rate + qty × price × transfer_fee_rate
- 滑点成本 = qty × price × slippage_value（双边）

精度：内部 float64 保留 6 位小数（不中间舍入），展示截断 2 位。
"""
from __future__ import annotations

import math
from collections import deque
from typing import Any, Dict, List

import pandas as pd


# ============================================================
# 精度辅助
# ============================================================

def round6(x: float) -> float:
    """内部计算精度：保留 6 位小数（spec §S3.1）。

    float64 中间计算不做舍入；仅在落盘 / 汇总节点统一 round6 一次，
    把浮点累计尾差收敛到 1e-6 量级，避免 200 格成交累计误差 > 0.01 元。
    """
    try:
        v = float(x)
    except (TypeError, ValueError):
        return 0.0
    if math.isnan(v) or math.isinf(v):
        return 0.0
    return round(v, 6)


def truncate2(x: float) -> float:
    """展示精度：截断 2 位（**截断**，非四舍五入；spec §S3.1）。

    对正负数均向 0 截断：
    - ``3.14159 → 3.14``
    - ``-2.999 → -2.99``（向 0 截断，而非向下取整的 -3.0）

    实现用 ``int(x * 100) / 100``，``int()`` 对负数也向 0 取整。
    """
    try:
        v = float(x)
    except (TypeError, ValueError):
        return 0.0
    if math.isnan(v) or math.isinf(v):
        return 0.0
    return int(v * 100) / 100.0


# ============================================================
# 成本公式（§S3.3）
# ============================================================

def compute_trade_cost(
    qty: float,
    price: float,
    side: str,
    commission_rate: float,
    min_commission: float,
    stamp_tax_rate: float,
    transfer_fee_rate: float,
    slippage_value: float = 0.0,
) -> Dict[str, float]:
    """单笔成交成本（spec §S3.3）。

    - 买入：``commission = max(qty*price*commission_rate, min_commission)``；
      印花税 = 0；过户费 = ``qty*price*transfer_fee_rate``；
      滑点 = ``qty*price*slippage_value``。
    - 卖出：佣金同买入；印花税 = ``qty*price*stamp_tax_rate``（仅卖出扣）；
      过户费同买入；滑点同买入（双边）。

    :param side: ``"buy"`` 或 ``"sell"``（大小写不敏感）。
    :param slippage_value: 滑点值（推荐 percent 比例，如 0.0002；akquant
        ``slippage={"type":"percent","value":0.0002}`` 的 value 字段）。
    :return: ``{commission, stamp_tax, transfer_fee, slippage_cost, total_cost}``，
        每项已 :func:`round6`。
    """
    q = float(qty)
    p = float(price)
    notional = q * p
    s = (side or "").strip().lower()

    commission = max(notional * float(commission_rate), float(min_commission))
    stamp = notional * float(stamp_tax_rate) if s == "sell" else 0.0
    transfer = notional * float(transfer_fee_rate)
    slippage = notional * float(slippage_value or 0.0)
    total = commission + stamp + transfer + slippage

    return {
        "commission": round6(commission),
        "stamp_tax": round6(stamp),
        "transfer_fee": round6(transfer),
        "slippage_cost": round6(slippage),
        "total_cost": round6(total),
    }


# ============================================================
# oracle_A：手工逐步累加（FIFO 配对）
# ============================================================

def manual_pnl_oracle(
    trades: List[Dict[str, Any]],
    commission_rate: float,
    min_commission: float,
    stamp_tax_rate: float,
    transfer_fee_rate: float,
    slippage_value: float = 0.0,
) -> Dict[str, Any]:
    """手工逐步累加 oracle_A（spec §S3.2）。

    :param trades: 配对成交列表，按时间升序。每条 dict 至少含
        ``{"side": "buy"/"sell", "price": float, "qty": int, "time": str}``。
        ``qty`` 可缺失（缺省视为 0）；``time`` 仅用于明细审计。
    :return: ``{"oracle_A_total": round6(...), "trade_count": len(trades),
        "matched_trades": 配对数, "details": [每笔明细]}``。

    算法（FIFO 配对）：

    1. 买入按时间顺序入队（``deque`` 维护 (price, remaining_qty)）。
    2. 卖出到来时，从队列头部依次消耗 qty：
       - ``matched_qty = min(sell_qty_remaining, buy_lot.remaining)``
       - 买入成本按本次匹配数量分摊；
       - ``pnl = (sell_price - buy_price) * matched_qty
              - round6(卖出成本) - round6(买入成本分摊)``
       - 累加到 ``oracle_A_total``。
    3. 末尾仍有未平仓的买单不计入 pnl（持仓未实现盈亏不算）。
    """
    if not trades:
        return {
            "oracle_A_total": 0.0,
            "trade_count": 0,
            "matched_trades": 0,
            "details": [],
        }

    # 买入队列：(price, remaining_qty)
    buy_queue: deque = deque()
    oracle_a_total = 0.0
    matched_count = 0
    details: List[Dict[str, Any]] = []

    for idx, t in enumerate(trades):
        side = (t.get("side") or "").strip().lower()
        price = float(t.get("price") or 0.0)
        qty = int(t.get("qty") or 0)
        time_str = t.get("time")

        if qty <= 0 or price <= 0:
            continue

        if side == "buy":
            buy_queue.append([price, qty])
            details.append({
                "idx": idx,
                "side": "buy",
                "price": round6(price),
                "qty": qty,
                "time": time_str,
                "matched_pnl": None,
            })
            continue

        if side != "sell":
            # 非 buy/sell 的成交类型跳过（不参与 oracle_A）
            continue

        # 卖出 → FIFO 配对
        sell_remaining = qty
        sell_cost = compute_trade_cost(
            qty=qty, price=price, side="sell",
            commission_rate=commission_rate, min_commission=min_commission,
            stamp_tax_rate=stamp_tax_rate, transfer_fee_rate=transfer_fee_rate,
            slippage_value=slippage_value,
        )["total_cost"]

        trade_pnl = 0.0
        matched_qty_total = 0

        while sell_remaining > 0 and buy_queue:
            buy_price, buy_remaining = buy_queue[0]
            matched_qty = min(sell_remaining, buy_remaining)
            if matched_qty <= 0:
                break

            # 买入成本分摊（按本次匹配数量）
            buy_cost_allocated = compute_trade_cost(
                qty=matched_qty, price=buy_price, side="buy",
                commission_rate=commission_rate, min_commission=min_commission,
                stamp_tax_rate=stamp_tax_rate, transfer_fee_rate=transfer_fee_rate,
                slippage_value=slippage_value,
            )["total_cost"]
            # 卖出成本分摊（按本次匹配数量占总卖出数量比例）
            sell_cost_allocated = round6(sell_cost * (matched_qty / qty))

            gross = (price - buy_price) * matched_qty
            net = gross - sell_cost_allocated - buy_cost_allocated
            net = round6(net)
            trade_pnl = round6(trade_pnl + net)

            matched_qty_total += matched_qty
            sell_remaining -= matched_qty
            buy_remaining -= matched_qty
            if buy_remaining <= 0:
                buy_queue.popleft()
            else:
                buy_queue[0][1] = buy_remaining

            matched_count += 1

        oracle_a_total = round6(oracle_a_total + trade_pnl)

        details.append({
            "idx": idx,
            "side": "sell",
            "price": round6(price),
            "qty": qty,
            "time": time_str,
            "matched_pnl": trade_pnl,
            "matched_qty": matched_qty_total,
        })

    return {
        "oracle_A_total": round6(oracle_a_total),
        "trade_count": len(trades),
        "matched_trades": matched_count,
        "details": details,
    }


# ============================================================
# oracle_B：akquant trades_df.net_pnl 求和
# ============================================================

def akquant_pnl_oracle(trades_df: Any) -> Dict[str, Any]:
    """akquant trades_df.net_pnl 求和（spec §S3.2 / oracle_B）。

    :param trades_df: ``akquant.BacktestResult.trades_df``（pandas DataFrame）。
        取 ``net_pnl`` 列数值求和。若 trades_df 为空或无 ``net_pnl`` 列，
        返回 ``{"oracle_B_total": 0.0, "trade_count": 0}``。
    """
    if trades_df is None:
        return {"oracle_B_total": 0.0, "trade_count": 0}

    try:
        empty = getattr(trades_df, "empty", True)
    except Exception:  # noqa: BLE001
        empty = True
    if empty:
        return {"oracle_B_total": 0.0, "trade_count": 0}

    try:
        columns = list(trades_df.columns)
    except Exception:  # noqa: BLE001
        columns = []
    if "net_pnl" not in columns:
        return {"oracle_B_total": 0.0, "trade_count": 0}

    try:
        net = pd.to_numeric(trades_df["net_pnl"], errors="coerce").fillna(0.0)
        total = float(net.sum())
    except Exception:  # noqa: BLE001
        return {"oracle_B_total": 0.0, "trade_count": 0}

    try:
        n = int(len(trades_df))
    except Exception:  # noqa: BLE001
        n = 0

    return {
        "oracle_B_total": round6(total),
        "trade_count": n,
    }


# ============================================================
# reconcile：双 oracle 对账
# ============================================================

def reconcile(
    expected_total: float,
    oracle_a: Dict[str, Any],
    oracle_b: Dict[str, Any],
    tolerance: float = 0.01,
) -> Dict[str, Any]:
    """双 oracle 对账（spec §S3.2）。

    :param expected_total: 基准总盈亏（如 engine 自记账结果）。
    :param oracle_a: :func:`manual_pnl_oracle` 返回（读 ``oracle_A_total``）。
    :param oracle_b: :func:`akquant_pnl_oracle` 返回（读 ``oracle_B_total``）。
    :param tolerance: 容差（默认 0.01 元，spec §S3.2）。
    :return: ``{diff_vs_oracle_A, diff_vs_oracle_B, diff_A_vs_B, passed, tolerance}``。
        ``passed`` 为三项 diff 同时 < tolerance。
    """
    a_total = float(oracle_a.get("oracle_A_total") or 0.0)
    b_total = float(oracle_b.get("oracle_B_total") or 0.0)
    expected = float(expected_total or 0.0)

    diff_a = abs(expected - a_total)
    diff_b = abs(expected - b_total)
    diff_ab = abs(a_total - b_total)

    passed = (diff_a < tolerance) and (diff_b < tolerance) and (diff_ab < tolerance)

    return {
        "diff_vs_oracle_A": round6(diff_a),
        "diff_vs_oracle_B": round6(diff_b),
        "diff_A_vs_B": round6(diff_ab),
        "passed": passed,
        "tolerance": float(tolerance),
    }


__all__ = [
    "round6",
    "truncate2",
    "compute_trade_cost",
    "manual_pnl_oracle",
    "akquant_pnl_oracle",
    "reconcile",
]
