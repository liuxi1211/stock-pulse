"""调仓日选股引擎（spec 008-backtest-center-phase2 T2.1 / PRD §6.3）。

职责：在 rebalance 范式回测的调仓日，对全 universe 的 K 线本地跑「因子预计算
+ 截面条件过滤 + ranking 打分」，输出 ``{symbol: score}``，供
``Strategy.on_daily_rebalance`` 调 ``rebalance_to_topn`` 使用。

设计要点（与 003 选股中心同口径）：
- 因子计算：复用 :mod:`services.shared.factor_pipeline`（底层走
  ``factor_calculator.compute_single``），保证回测算与选股算口径一致。
- 条件求值：复用 :mod:`services.screener.engine.ConditionEngine`（截面 mode，
  禁 cross_*/ref），与 003 选股同语义。
- 排序打分：复用 :func:`services.screener.ranking.rank_stocks`。

硬约束（spec AC-P2-7 / RebalanceEngine Scenario「engine 不回调 watcher」）：
- 不触库（源码不含任何数据库驱动 import / 连接 / 路径字面量）；
- 禁用动态代码解释 / 编译执行 / 动态模块装载；
- 不回调 watcher：仅依赖入参 ``kline_map`` + ``screen_config``，无 HTTP 调用。
"""
import math
from typing import Any, Optional, Union

import numpy as np
import pandas as pd

from core.logger import logger
from services.factor.calculator import factor_calculator
from services.factor.data_utils import kline_to_arrays
from services.factor.registry import factor_registry
from services.screener.engine import (
    ConditionEngine,
    EvalContext,
    factor_signature,
)
from services.screener.factor_precompute import collect_factor_refs, precompute_factors
from services.screener.ranking import rank_stocks
from services.strategy.models import ScreenConfigModel

# 技术面/价格因子来源：走 factor_calculator（与 factor_precompute._TECH_SOURCES 对齐）
_TECH_SOURCES = {"AKQUANT", "RAW", "DERIVED"}


class RebalanceEngine:
    """调仓日选股引擎（无状态，可并发使用）。

    用法::

        engine = RebalanceEngine()
        scores = engine.select_at_rebalance_date(
            screen_config=config,
            kline_map={"000001.SZ": [...], "600000.SH": [...]},
            trading_date=pd.Timestamp("2024-06-03"),
        )
        # scores = {"000001.SZ": 1.23, "600000.SH": -0.45}
    """

    def select_at_rebalance_date(
        self,
        screen_config: Union[ScreenConfigModel, dict],
        kline_map: dict[str, list[dict]],
        trading_date: Union[pd.Timestamp, str, "pd.Timestamp"],
        history_window: int = 60,
    ) -> dict[str, float]:
        """调仓日选股：对全 universe 截面过滤 + ranking 打分。

        :param screen_config: 选股配置（``ScreenConfigModel`` 或等价 dict）。
        :param kline_map: ``{symbol: [{date, open, high, low, close, volume}, ...]}``，
            每个标的的 K 线历史（由 watcher 经 HTTP 传入回测区间全量数据）。
        :param trading_date: 调仓日（``pd.Timestamp`` 或可识别的日期字符串）。
        :param history_window: 每个 symbol 截至调仓日保留的最近 K 线根数（因子计算窗口）。
        :return: ``{symbol: score}``。仅含通过 conditions 的标的；score 为 ranking
            综合分（ranking=None 时为 0.0，配合 weight_mode=equal）。
        """
        # 1) 归一化 screen_config
        cfg = self._normalize_config(screen_config)

        # 2) 归一化 trading_date
        ts = self._normalize_date(trading_date)

        # 3) 构建 candidates：截至 trading_date 的历史窗口
        candidates = self._build_candidates(kline_map, ts, history_window)

        if not candidates:
            return {}

        # 4) 收集因子引用（conditions + ranking）
        factor_refs = self._collect_all_factor_refs(cfg)

        # 5) 预计算因子值（per symbol）
        factor_values_by_symbol = self._compute_factors(cfg, candidates, factor_refs)

        # 6) NaN 安全：剔除因子计算失败（全 NaN）的 symbol
        factor_values_by_symbol = self._filter_valid_symbols(factor_values_by_symbol)

        if not factor_values_by_symbol:
            return {}

        # 7) 截面条件过滤（ConditionEngine cross_section mode）
        hit_symbols = self._filter_by_conditions(cfg, factor_values_by_symbol)

        if not hit_symbols:
            return {}

        # 8) ranking 打分
        ranking_dict = self._ranking_to_dict(cfg)
        ranked = rank_stocks(
            hit_symbols=hit_symbols,
            factor_values_by_symbol=factor_values_by_symbol,
            ranking=ranking_dict,
            top_n=cfg.top_n,
        )

        # 9) 组装 {symbol: score}
        return {
            r["symbol"]: (r["score"] if r["score"] is not None else 0.0)
            for r in ranked
        }

    # ============================================================
    # 步骤实现
    # ============================================================

    @staticmethod
    def _normalize_config(
        screen_config: Union[ScreenConfigModel, dict]
    ) -> ScreenConfigModel:
        """dict → ScreenConfigModel（已是模型则原样返回）。"""
        if isinstance(screen_config, ScreenConfigModel):
            return screen_config
        if isinstance(screen_config, dict):
            return ScreenConfigModel(**screen_config)
        raise TypeError(
            f"screen_config 必须是 ScreenConfigModel 或 dict，收到 {type(screen_config).__name__}"
        )

    @staticmethod
    def _conditions_to_dict(cfg: ScreenConfigModel):
        """把 cfg.conditions（pydantic 模型）归一化为 dict。

        ``collect_factor_refs`` / ``precompute_factors`` / ``ConditionEngine.evaluate``
        内部用 ``isinstance(tree, models.schemas.condition.ConditionTree)`` 判型，
        而 ``ScreenConfigModel.conditions`` 是 ``services.strategy.models.ConditionTree``
        （不同类），直接传会判型失败、整树不被遍历。统一 model_dump 成 dict，
        三处消费方都按 dict 字段（operator/comparator/value/factor）归一化。
        """
        conditions = cfg.conditions
        if conditions is None:
            return None
        if hasattr(conditions, "model_dump"):
            return conditions.model_dump(exclude_none=True)
        return conditions

    @staticmethod
    def _normalize_date(
        trading_date: Union[pd.Timestamp, str]
    ) -> pd.Timestamp:
        """归一化调仓日为 pd.Timestamp（naive，仅取日期部分用于比较）。"""
        ts = pd.Timestamp(trading_date)
        return ts.normalize()  # 截断到 00:00:00，只保留日期

    @staticmethod
    def _build_candidates(
        kline_map: dict[str, list[dict]],
        trading_date: pd.Timestamp,
        history_window: int,
    ) -> dict[str, dict[str, Any]]:
        """构建 candidates ``{symbol: {"ohlcv_history": [...]}}``。

        对每个 symbol 取 ``date <= trading_date`` 的行，保留最后 ``history_window`` 根。
        单 symbol 解析失败时跳过（NaN 安全，不阻断批量）。
        """
        candidates: dict[str, dict[str, Any]] = {}
        for symbol, rows in kline_map.items():
            try:
                if not rows:
                    continue
                history = RebalanceEngine._slice_history(rows, trading_date, history_window)
                if not history:
                    continue
                candidates[symbol] = {"ohlcv_history": history, "fundamentals": {}}
            except Exception as exc:  # noqa: BLE001 - 单 symbol 失败跳过
                logger.warning(
                    "rebalance 选股构建候选失败 symbol=%s: %s", symbol, exc
                )
                continue
        return candidates

    @staticmethod
    def _slice_history(
        rows: list[dict],
        trading_date: pd.Timestamp,
        history_window: int,
    ) -> list[dict]:
        """取 date <= trading_date 的行，保留最后 history_window 根。

        兼容 date / trade_date / datetime / timestamp 列名；行内无时间列时保留全部。
        """
        time_col = next(
            (c for c in ("date", "trade_date", "datetime", "timestamp") if c in rows[0]),
            None,
        )
        if time_col is None:
            # 无时间列：保守保留全部（假设上游已按区间裁剪）
            return rows[-history_window:] if history_window > 0 else list(rows)

        def _parse(r: dict):
            try:
                return pd.Timestamp(r.get(time_col)).normalize()
            except Exception:  # noqa: BLE001
                return None

        filtered: list[dict] = []
        for r in rows:
            v = _parse(r)
            if v is not None and v <= trading_date:
                filtered.append(r)
        if not filtered:
            return []
        if history_window > 0:
            return filtered[-history_window:]
        return filtered

    @staticmethod
    def _collect_all_factor_refs(cfg: ScreenConfigModel) -> list[dict]:
        """收集 conditions 中的因子引用 + ranking 需要的因���。

        ranking=single → 加 ranking.factor；ranking=composite → 加 weights 的每个 key。
        ranking 因子按 factorKey 形态加入（无 params，与 003 ranking 取值策略一致）。
        """
        refs: list[dict] = []
        seen: set[str] = set()

        # conditions 中的因子引用（归一化为 dict 再收集，规避两类 ConditionTree 判型差异）
        conditions_dict = RebalanceEngine._conditions_to_dict(cfg)
        if conditions_dict is not None:
            for ref in collect_factor_refs(conditions_dict):
                sig = factor_signature(
                    ref.get("factorKey"),
                    ref.get("params"),
                    ref.get("outputIndex"),
                )
                if sig not in seen:
                    seen.add(sig)
                    refs.append(ref)

        # ranking 需要的因子
        ranking = cfg.ranking
        if ranking is not None:
            method = ranking.method
            if method == "single" and ranking.factor:
                fk = ranking.factor
                if fk not in seen:
                    seen.add(fk)
                    refs.append({"factorKey": fk})
            elif method == "composite" and ranking.weights:
                for fk in ranking.weights.keys():
                    if fk not in seen:
                        seen.add(fk)
                        refs.append({"factorKey": fk})

        return refs

    @staticmethod
    def _compute_factors(
        cfg: ScreenConfigModel,
        candidates: dict[str, dict[str, Any]],
        factor_refs: list[dict],
    ) -> dict[str, dict[str, float]]:
        """预计算每个 symbol 的全部因子值。

        策略：
        1. 若 conditions 在场，用 ``precompute_factors(conditions, candidates)``
           拿到 conditions 涉及的因子值（复用 003 的基本面/技术面分流）。
        2. 对 ranking 需要但 conditions 未覆盖的因子，逐 symbol 用
           ``factor_calculator.compute_single`` 补算。
        3. 若 conditions 不在场，全部因子直接逐 symbol 算。
        """
        result: dict[str, dict[str, float]] = {}

        conditions_dict = RebalanceEngine._conditions_to_dict(cfg)
        if conditions_dict is not None:
            try:
                base = precompute_factors(conditions_dict, candidates)
            except Exception as exc:  # noqa: BLE001 - 整体失败则降级为空，后续逐个补
                logger.warning("rebalance precompute_factors 整体失败: %s", exc)
                base = {sym: {} for sym in candidates}
        else:
            base = {sym: {} for sym in candidates}

        # 区分 ranking 补算因子（conditions 未覆盖的）
        ranking_extra_refs = RebalanceEngine._ranking_extra_refs(cfg, base)

        for symbol, candidate in candidates.items():
            per_symbol = dict(base.get(symbol, {}))
            # 补算 ranking 额外因子
            for ref in ranking_extra_refs:
                fk = ref["factorKey"]
                sig = factor_signature(fk, ref.get("params"), ref.get("outputIndex"))
                if sig in per_symbol:
                    continue  # conditions 已覆盖
                try:
                    val = RebalanceEngine._compute_one_factor(fk, ref, candidate)
                    per_symbol[sig] = val
                    # 基本面 factorKey 也作 key（与 precompute_factors 一致）
                    if ref.get("params") is None and ref.get("outputIndex") is None:
                        per_symbol[fk] = val
                except Exception as exc:  # noqa: BLE001
                    logger.warning(
                        "rebalance 因子补算失败 symbol=%s factor=%s: %s",
                        symbol, fk, exc,
                    )
                    per_symbol[sig] = float("nan")
            result[symbol] = per_symbol

        return result

    @staticmethod
    def _ranking_extra_refs(
        cfg: ScreenConfigModel,
        base: dict[str, dict[str, float]],
    ) -> list[dict]:
        """找出 ranking 需要但 conditions（base）未覆盖的因子。

        对每个 ranking factorKey，检查 base 的任一 symbol 中是否有「精确命中或前缀命中」；
        都没有则需补算。
        """
        ranking = cfg.ranking
        if ranking is None:
            return []

        keys: list[str] = []
        if ranking.method == "single" and ranking.factor:
            keys = [ranking.factor]
        elif ranking.method == "composite" and ranking.weights:
            keys = list(ranking.weights.keys())
        else:
            return []

        # 收集 base 中所有 symbol 已有的 key 集合（含前缀）
        existing: set[str] = set()
        for sym_values in base.values():
            existing.update(sym_values.keys())

        extra: list[dict] = []
        for fk in keys:
            if fk in existing:
                continue
            prefix = f"{fk}("
            if any(k.startswith(prefix) for k in existing):
                continue
            extra.append({"factorKey": fk})
        return extra

    @staticmethod
    def _compute_one_factor(
        factor_key: str,
        ref: dict,
        candidate: dict[str, Any],
    ) -> float:
        """单个技术面/价格因子补算：kline_to_arrays → compute_single → [-1]。

        基本面因子（TUSHARE）由 watcher 经 fundamentals 传入；回测 rebalance 路径
        fundamentals 默认空，故基本面因子补算返回 NaN（与 003 行为一致）。
        """
        if not factor_registry.exists(factor_key):
            return float("nan")
        fd = factor_registry.get_factor(factor_key)
        if fd.source == "TUSHARE":
            # 基本面：从 fundamentals 取（回测路径默认空 → NaN）
            fundamentals = candidate.get("fundamentals") or {}
            return RebalanceEngine._to_float(fundamentals.get(factor_key))
        if fd.source not in _TECH_SOURCES:
            return float("nan")

        ohlcv_history = candidate.get("ohlcv_history")
        if not ohlcv_history:
            return float("nan")
        arrays = kline_to_arrays(ohlcv_history)
        arr = factor_calculator.compute_single(
            factor_key=factor_key,
            inputs=arrays,
            params=ref.get("params"),
            output_index=ref.get("outputIndex"),
        )
        return RebalanceEngine._last_or_nan(arr)

    @staticmethod
    def _filter_valid_symbols(
        factor_values_by_symbol: dict[str, dict[str, float]],
    ) -> dict[str, dict[str, float]]:
        """剔除因子值全 NaN 的 symbol（计算失败/数据不足）。"""
        out: dict[str, dict[str, float]] = {}
        for symbol, values in factor_values_by_symbol.items():
            if not values:
                continue
            has_valid = any(
                not (v is None or RebalanceEngine._is_nan(v))
                for v in values.values()
            )
            if has_valid:
                out[symbol] = values
        return out

    @staticmethod
    def _filter_by_conditions(
        cfg: ScreenConfigModel,
        factor_values_by_symbol: dict[str, dict[str, float]],
    ) -> list[str]:
        """截面条件过滤：逐 symbol 求值 conditions，收集命中标的。

        conditions=None 时全部命中（无过滤）。单 symbol 求值异常跳过（不阻断）。
        """
        conditions_dict = RebalanceEngine._conditions_to_dict(cfg)
        if conditions_dict is None:
            return list(factor_values_by_symbol.keys())

        engine = ConditionEngine()
        hit: list[str] = []
        for symbol, values in factor_values_by_symbol.items():
            try:
                ctx = EvalContext(symbol=symbol, factor_values=values, fundamentals={})
                passed = engine.evaluate(conditions_dict, ctx)
                if passed:
                    hit.append(symbol)
            except Exception as exc:  # noqa: BLE001 - 单 symbol 求值失败跳过
                logger.warning(
                    "rebalance 条件求值失败 symbol=%s: %s", symbol, exc
                )
                continue
        return hit

    @staticmethod
    def _ranking_to_dict(cfg: ScreenConfigModel) -> Optional[dict]:
        """RankingModel → dict（rank_stocks 接受 dict 或 None）。

        method="disabled" 视为不排序（返回 None）。
        """
        ranking = cfg.ranking
        if ranking is None:
            return None
        if ranking.method == "disabled":
            return None
        return ranking.model_dump(exclude_none=True)

    # ============================================================
    # 数值工具
    # ============================================================

    @staticmethod
    def _last_or_nan(arr: Any) -> float:
        """取数组最后一位；空数组 / NaN / Inf → NaN。"""
        try:
            if arr is None:
                return float("nan")
            a = np.asarray(arr, dtype=np.float64).ravel()
            if a.size == 0:
                return float("nan")
            v = float(a[-1])
            if math.isnan(v) or math.isinf(v):
                return float("nan")
            return v
        except (TypeError, ValueError, IndexError):
            return float("nan")

    @staticmethod
    def _to_float(value: Any) -> float:
        """None / 非数 / Inf → NaN，其余转 float。"""
        if value is None:
            return float("nan")
        try:
            f = float(value)
        except (TypeError, ValueError):
            return float("nan")
        if math.isnan(f) or math.isinf(f):
            return float("nan")
        return f

    @staticmethod
    def _is_nan(x: Any) -> bool:
        """NaN 判断（含 None）。"""
        if x is None:
            return True
        try:
            return math.isnan(float(x))
        except (TypeError, ValueError):
            return True
