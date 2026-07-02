"""因子库性能基准（spec NFR-1 / AC-7）。

直接运行：``python tests/test_factor/benchmark.py``
不作为 pytest 用例（无 test_ 前缀），仅打印 P50/P95 与目标对比。
"""
import pathlib
import statistics
import sys
import time

import numpy as np

# 支持从任意目录直接运行：把 engine 根加入 sys.path
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))

from services.factor import factor_calculator, factor_registry  # noqa: E402


def _bench(fn, repeats=100):
    samples = []
    fn()  # 预热
    for _ in range(repeats):
        t0 = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - t0) * 1000)  # ms
    samples.sort()
    return {
        "avg": statistics.mean(samples),
        "p50": samples[len(samples) // 2],
        "p95": samples[int(len(samples) * 0.95)],
    }


def main():
    rng = np.random.default_rng(7)
    n = 250
    base_inputs = {
        k: rng.uniform(1, 100, n) if k != "volume" else rng.uniform(1e6, 1e7, n)
        for k in ("open", "high", "low", "close", "volume")
    }

    # 场景 1：单标的单因子
    s1 = _bench(lambda: factor_calculator.compute_single("MA", base_inputs, {"timeperiod": 5}))
    # 场景 2：单标的 10 因子
    ten = [{"factorKey": k} for k in
           ("MA", "EMA", "RSI", "MACD", "KDJ", "BOLL", "ATR", "CLOSE", "ADX", "CCI")]
    s2 = _bench(lambda: factor_calculator.compute_batch(ten, base_inputs))
    # 场景 3：50 标的 × 5 因子
    five = [{"factorKey": k} for k in ("MA", "RSI", "MACD", "CLOSE", "ATR")]
    multi = {f"S{i:03d}": base_inputs for i in range(50)}
    s3 = _bench(lambda: factor_calculator.compute_multi_symbol(multi, five), repeats=20)
    # 场景 4：元数据全量查询
    s4 = _bench(lambda: factor_registry.list_factors())
    # 场景 5：新增（含原子写盘）
    counter = [0]

    def add_once():
        counter[0] += 1
        from models.schemas.factor import FactorDef
        fd = FactorDef(factorKey=f"BENCH_{counter[0]}", displayName="b", category="PRICE",
                       source="RAW", inputs=["close"], params=[])
        try:
            factor_registry.add_factor(fd)
        except Exception:
            pass
    s5 = _bench(add_once, repeats=20)

    targets = {"s1": 10, "s2": 50, "s3": 500, "s4": 5, "s5": 50}
    rows = [
        ("单标的单因子 (250 bar)", s1, 10),
        ("单标的 10 因子 (250 bar)", s2, 50),
        ("50 标的 × 5 因子 (250 bar)", s3, 500),
        ("元数据全量查询", s4, 5),
        ("新增因子 (含写盘)", s5, 50),
    ]
    print(f"{'场景':<28}{'avg(ms)':>10}{'p50(ms)':>10}{'p95(ms)':>10}{'目标(ms)':>10}{'达标':>6}")
    for name, m, target in rows:
        ok = "OK" if m["avg"] < target else "X"
        print(f"{name:<28}{m['avg']:>10.3f}{m['p50']:>10.3f}{m['p95']:>10.3f}{target:>10}{ok:>6}")


if __name__ == "__main__":
    main()
