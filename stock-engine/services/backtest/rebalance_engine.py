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

硬约束（分层，spec 010-rotation-data-governance §5.1.2）：
- 数据单源性（强）：engine 永不直接读写业务数据库（SQLite/MySQL）；源码不含任何数据库驱动 import / 连接 / 路径字面量。
- 行情/基本面（强）：行情与基本面数据由 watcher 预传，engine 不反向拉取。
- 参考数据（弱，例外）：成分股身份等「参考数据」允许 engine 在回测期间按需查询 watcher 的只读内部接口（/api/internal/*，幂等无副作用）。
  **spec 011 P1-1**：point-in-time 成分股过滤已从「可选+降级」收紧为「强制+失败即报错」，
  所有 universe 类型在每个调仓日必须成功查到成分股快照（watcher_client=None / 空集 / 异常均抛 BacktestError）。
- 禁用动态代码解释 / 编译执行 / 动态模块装载（强，无例外）。
"""
import math
from typing import TYPE_CHECKING, Any, Optional, Union

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

if TYPE_CHECKING:
    # 仅用于类型注解（spec 010 缺陷 A 修复：watcher 只读客户端注入路径）
    from services.backtest.watcher_client import WatcherClient

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
        watcher_client: Optional["WatcherClient"] = None,
        extra_map: Optional[dict[str, dict[str, dict[str, float]]]] = None,
    ) -> dict[str, float]:
        """调仓日选股：对全 universe 截面过滤 + ranking 打分。

        :param screen_config: 选股配置（``ScreenConfigModel`` 或等价 dict）。
        :param kline_map: ``{symbol: [{date, open, high, low, close, volume}, ...]}``，
            每个标的的 K 线历史（由 watcher 经 HTTP 传入回测区间全量数据）。
        :param trading_date: 调仓日（``pd.Timestamp`` 或可识别的日期字符串）。
        :param history_window: 每个 symbol 截至调仓日保留的最近 K 线根数（因子计算窗口）。
        :param watcher_client: watcher 只读客户端（**必填**，spec 011 P1-1）。
            所有 universe 类型在每个调仓日强制查询 point-in-time 成分股快照过滤候选池，
            消除 lookahead bias。None 时抛 ``BacktestError("PIT_WATCHER_UNAVAILABLE")``。
        :param extra_map: 可选的 ``{symbol: {trade_date_str: {pe_ttm, pb, ...}}}``，
            由 ``kline_to_extra_map`` 从 watcher K 线提取。用于 TUSHARE 基本面因子补算
            （spec 010 缺陷 B 修复）。None 时基本面因子回退为 NaN（保留旧行为）。
        :return: ``{symbol: score}``。仅含通过 conditions 的标的；score 为 ranking
            综合分（ranking=None 时为 0.0，配合 weight_mode=equal）。
        """
        # 1) 归一化 screen_config
        cfg = self._normalize_config(screen_config)

        # 2) 归一化 trading_date
        ts = self._normalize_date(trading_date)

        # 2.5) point-in-time 成分股过滤（spec 011 P1-1：强制开启，失败即报错）
        kline_map = self._apply_universe_filter(cfg, kline_map, ts, watcher_client)

        # 3) 构建 candidates：截至 trading_date 的历史窗口（注入 extra）
        candidates = self._build_candidates(kline_map, ts, history_window, extra_map)

        if not candidates:
            return {}

        # 4) 收集因子引用（conditions + ranking）
        factor_refs = self._collect_all_factor_refs(cfg)

        # 5) 预计算因子值（per symbol；传 trading_date 供基本面按日取值）
        factor_values_by_symbol = self._compute_factors(cfg, candidates, factor_refs, trading_date=ts)

        # 6) NaN 安全：剔除因子计算失败（全 NaN）的 symbol
        factor_values_by_symbol = self._filter_valid_symbols(factor_values_by_symbol)

        if not factor_values_by_symbol:
            return {}

        # 6.5) 静态过滤（spec 011 P0-1）：ST/停牌/涨跌停/行业/上市天数
        factor_values_by_symbol = self._filter_by_static_rules(
            cfg, factor_values_by_symbol, candidates, ts
        )

        if not factor_values_by_symbol:
            return {}

        # 7) 截面条件过滤（ConditionEngine cross_section mode）
        hit_symbols = self._filter_by_conditions(cfg, factor_values_by_symbol)

        if not hit_symbols:
            return {}

        # 8) ranking 打分（4 层结构：top_n 位于 portfolio 层）
        ranking_dict = self._ranking_to_dict(cfg)
        portfolio_layer = getattr(cfg, "portfolio", None)
        top_n = getattr(portfolio_layer, "top_n", None) if portfolio_layer else None
        ranked = rank_stocks(
            hit_symbols=hit_symbols,
            factor_values_by_symbol=factor_values_by_symbol,
            ranking=ranking_dict,
            top_n=top_n,
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
        """把 cfg.filter.conditions（pydantic 模型）归一化为 dict。

        4 层结构下 conditions 位于 filter 层；filter 为 None 时返回 None。

        ``collect_factor_refs`` / ``precompute_factors`` / ``ConditionEngine.evaluate``
        内部用 ``isinstance(tree, models.schemas.condition.ConditionTree)`` 判型，
        而 ``FilterModel.conditions`` 是 ``services.strategy.models.ConditionTree``
        （不同类），直接传会判型失败、整树不被遍历。统一 model_dump 成 dict，
        三处消费方都按 dict 字段（operator/comparator/value/factor）归一化。
        """
        filter_layer = getattr(cfg, "filter", None)
        conditions = getattr(filter_layer, "conditions", None) if filter_layer else None
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

    # ============================================================
    # point-in-time 成分股过滤（spec 011 P1-1：强制开启，失败即报错）
    # ============================================================

    # universe pool 字符串 → watcher 查询用的指数代码。
    # - csi300 / csi500：直接用对应宽基指数成分股
    # - all_a_shares / manual / 自定义池：watcher 无「全 A 可交易标的」专用接口，
    #   用中证全 A（000985.SH）作为可交易性 proxy，剔除当日停牌/退市（spec 011 P1-1）
    _INDEX_CODE_MAP: dict[str, str] = {
        "csi300": "000300.SH",
        "csi500": "000905.SH",
        "all_a_shares": "000985.SH",
        "manual": "000985.SH",
    }

    @staticmethod
    def _get_universe_pool(cfg: ScreenConfigModel) -> str:
        """从 screen_config 取 universe pool（兼容旧 string 与新 object 结构）。

        - 旧结构（Task 9/10 前）：``cfg.universe`` 是 ``str``，直接返回（如 "csi300"/"manual"）；
        - 新结构（Task 9/10 后）：``cfg.universe`` 可能是含 ``pool`` 字段的对象/dict；
        - 其它形态（含 None）返回空串，视为 manual 不走 point-in-time。
        """
        universe = getattr(cfg, "universe", None)
        if isinstance(universe, str):
            return universe
        if isinstance(universe, dict):
            return str(universe.get("pool", "") or "")
        pool = getattr(universe, "pool", None)
        if pool is not None:
            return str(pool)
        return ""

    @staticmethod
    def _apply_universe_filter(
        cfg: ScreenConfigModel,
        kline_map: dict[str, list[dict]],
        trading_date: pd.Timestamp,
        watcher_client: Optional["WatcherClient"],
    ) -> dict[str, list[dict]]:
        """point-in-time 成分股过滤（spec 011 P1-1：**强制开启**，失败即报错）。

        所有 universe 类型无条件执行成分股过滤（不再判断 ``point_in_time`` 字段，
        该字段已 deprecated）：

        - **csi300** → 查 ``000300.SH`` 成分股，按交集过滤候选池；
        - **csi500** → 查 ``000905.SH`` 成分股，按交集过滤候选池；
        - **all_a_shares / manual / 自定义池** → watcher 无「全 A 可交易标的」专用接口，
          用中证全 A（``000985.SH``）作为可交易性 proxy，剔除当日停牌/退市的标的。

        失败处理（**不再降级**，spec 011 P1-1）：

        - ``watcher_client=None`` → 抛 ``BacktestError("PIT_WATCHER_UNAVAILABLE: ...")``；
        - 查询返回空集 → 抛 ``BacktestError("PIT_CONSTITUENTS_EMPTY: ...")``；
        - 查询抛异常 → 抛 ``BacktestError("PIT_QUERY_FAILED: ...")``。

        每个调仓日成功过滤后打 INFO 日志。

        :raises BacktestError: 任一失败场景（错误码见上）。
        """
        # 延迟导入避免循环依赖
        from services.backtest.runner import BacktestError

        pool = RebalanceEngine._get_universe_pool(cfg)
        trade_date_str = trading_date.strftime("%Y-%m-%d")

        # universe 类型 → watcher 查询用的指数代码；未知 pool 视为自定义池，用全 A proxy
        index_code = RebalanceEngine._INDEX_CODE_MAP.get(pool, "000985.SH")

        if watcher_client is None:
            raise BacktestError(
                f"PIT_WATCHER_UNAVAILABLE: point-in-time 成分股过滤无法执行"
                f"（universe={pool}, date={trade_date_str}）。"
                f"请确认 stock-watcher 服务已启动，并通过 HTTP header X-Watcher-Base-Url "
                f"或环境变量 WATCHER_BASE_URL 配置服务地址。",
                error_code="PIT_WATCHER_UNAVAILABLE",
            )

        try:
            eligible = watcher_client.get_constituents_at(index_code, trade_date_str)
        except Exception as exc:  # noqa: BLE001 - 查询异常即报错（不再降级）
            raise BacktestError(
                f"PIT_QUERY_FAILED: point-in-time 成分股查询失败"
                f"（universe={pool}, date={trade_date_str}, index={index_code}）：{exc}。"
                f"请检查 stock-watcher 服务状态、成分股数据是否已同步。",
                error_code="PIT_QUERY_FAILED",
            ) from exc

        if not eligible:
            raise BacktestError(
                f"PIT_CONSTITUENTS_EMPTY: point-in-time 成分股查询返回空集"
                f"（universe={pool}, date={trade_date_str}, index={index_code}）。"
                f"请确认该日期对应的指数成分股数据已同步到 stock-watcher。",
                error_code="PIT_CONSTITUENTS_EMPTY",
            )

        orig_count = len(kline_map)
        filtered = {sym: df for sym, df in kline_map.items() if sym in eligible}
        excluded = orig_count - len(filtered)
        logger.info(
            "PIT 过滤: universe=%s date=%s 候选 %d/%d（剔除 %d 只）",
            pool, trade_date_str, len(filtered), orig_count, excluded,
        )
        return filtered

    @staticmethod
    def _build_candidates(
        kline_map: dict[str, list[dict]],
        trading_date: pd.Timestamp,
        history_window: int,
        extra_map: Optional[dict[str, dict[str, dict[str, float]]]] = None,
    ) -> dict[str, dict[str, Any]]:
        """构建 candidates ``{symbol: {"ohlcv_history": [...], "extra": {...}}}``。

        对每个 symbol 取 ``date <= trading_date`` 的行，保留最后 ``history_window`` 根。
        若 ``extra_map`` 在场，把该 symbol 的基本面 ``{trade_date_str: {field: float}}``
        注入 candidate["extra"]，供 :meth:`_compute_one_factor` 按 trading_date 取用
        （缺陷 B 修复）。单 symbol 解析失败时跳过（NaN 安全，不阻断批量）。
        """
        candidates: dict[str, dict[str, Any]] = {}
        for symbol, rows in kline_map.items():
            try:
                if not rows:
                    continue
                history = RebalanceEngine._slice_history(rows, trading_date, history_window)
                if not history:
                    continue
                # 注入基本面 extra（watcher 经 kline 下发，按 trade_date 索引）
                extras = extra_map.get(symbol, {}) if extra_map else {}
                candidates[symbol] = {
                    "ohlcv_history": history,
                    "fundamentals": {},
                    "extra": extras,
                }
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
        4 层结构下排序配置位于 factor 层（原 ranking 平移而来）；因子按 factorKey
        形态加入（无 params，与 003 ranking 取值策略一致）。
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

        # factor 打分需要的因子（4 层结构：原 ranking 平移到 factor 层）
        factor = cfg.factor
        if factor is not None:
            method = factor.method
            if method == "single" and factor.factor:
                fk = factor.factor
                if fk not in seen:
                    seen.add(fk)
                    refs.append({"factorKey": fk})
            elif method == "composite" and factor.weights:
                for fk in factor.weights.keys():
                    if fk not in seen:
                        seen.add(fk)
                        refs.append({"factorKey": fk})

        return refs

    @staticmethod
    def _compute_factors(
        cfg: ScreenConfigModel,
        candidates: dict[str, dict[str, Any]],
        factor_refs: list[dict],
        trading_date: Optional[pd.Timestamp] = None,
    ) -> dict[str, dict[str, float]]:
        """预计算每个 symbol 的全部因子值。

        :param trading_date: 调仓日，传给 :meth:`_compute_one_factor` 用于按日取
            基本面 extra（缺陷 B 修复）。None 时基本面因子回退为 NaN（旧行为）。

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
                    val = RebalanceEngine._compute_one_factor(
                        fk, ref, candidate, trading_date
                    )
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
        """找出 factor 打分需要但 conditions（base）未覆盖的因子。

        4 层结构下排序配置位于 factor 层（原 ranking 平移而来）。
        对每个 factor factorKey，检查 base 的任一 symbol 中是否有「精确命中或前缀命中」；
        都没有则需补算。
        """
        factor = cfg.factor
        if factor is None:
            return []

        keys: list[str] = []
        if factor.method == "single" and factor.factor:
            keys = [factor.factor]
        elif factor.method == "composite" and factor.weights:
            keys = list(factor.weights.keys())
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
        trading_date: Optional[pd.Timestamp] = None,
    ) -> float:
        """单个技术面/价格因子补算：kline_to_arrays → compute_single → [-1]。

        基本面因子（TUSHARE）取值优先级（缺陷 B 修复，spec 010）：
        1. ``candidate["extra"]`` 按 ``trading_date`` 取当日基本面
           （watcher 经 kline 下发的 point-in-time 值）；
        2. 回退到 ``candidate["fundamentals"]``（旧行为，回测路径默认空）；
        3. 均缺失返回 NaN。

        技术面/价格因子仍走 ``kline_to_arrays`` + ``factor_calculator.compute_single``。
        """
        if not factor_registry.exists(factor_key):
            return float("nan")
        fd = factor_registry.get_factor(factor_key)
        if fd.source == "TUSHARE":
            # 基本面：优先从 extra（watcher 下发，按调仓日 trade_date 索引）取
            extras = candidate.get("extra") or {}
            day_extras: dict[str, float] = {}
            if trading_date is not None and extras:
                td_str = pd.Timestamp(trading_date).strftime("%Y-%m-%d")
                day_extras = extras.get(td_str) or {}
            if day_extras:
                # factor_key 大小写不敏感匹配 extra 字段
                return RebalanceEngine._to_float(
                    RebalanceEngine._extra_lookup(day_extras, factor_key)
                )
            # 回退到旧 fundamentals（向后兼容）
            fundamentals = candidate.get("fundamentals") or {}
            return RebalanceEngine._to_float(
                RebalanceEngine._extra_lookup(fundamentals, factor_key)
            )
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
    def _extra_lookup(values: dict[str, Any], key: str) -> Any:
        """大小写不敏感地从 extra/fundamentals dict 取值。

        watcher 下发的字段为小写（``pe_ttm``/``pb``/...），factorKey 可能是任意大小写。
        """
        if not values:
            return None
        if key in values:
            return values[key]
        key_lower = key.lower()
        if key_lower in values:
            return values[key_lower]
        # 遍历小写匹配兜底
        for k, v in values.items():
            if isinstance(k, str) and k.lower() == key_lower:
                return v
        return None

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

    # ============================================================
    # 静态过滤（spec 011 P0-1：回测路径接通 7 项静态过滤）
    # ============================================================

    # FilterModel 的 7 个静态字段名（与 services.strategy.models.FilterModel 对齐）
    _STATIC_FILTER_FIELDS: tuple[str, ...] = (
        "exclude_st",
        "exclude_suspended",
        "exclude_limit_up",
        "exclude_limit_down",
        "industries",
        "exclude_industries",
        "min_list_days",
    )

    @staticmethod
    def _filter_by_static_rules(
        cfg: ScreenConfigModel,
        factor_values_by_symbol: dict[str, dict[str, float]],
        candidates: dict[str, dict[str, Any]],
        trading_date: pd.Timestamp,
    ) -> dict[str, dict[str, float]]:
        """静态过滤：按 filter 层的 7 个静态字段剔除候选（spec 011 P0-1）。

        与 ``services.screener.filters.apply_filters`` 判定逻辑等价，但在回测路径
        独立实现（避免跨模块耦合）。元数据来源：``candidates[symbol]["extra"]`` 按
        ``trading_date`` 取当日 meta（由 watcher 经 kline 下发，data_adapter
        ``kline_to_extra_map`` 提取）。

        判定规则（命中即剔除）：
        - ``exclude_st=True`` 且 ``is_st`` 真（"1"/1/True）→ 剔除
        - ``exclude_suspended=True`` 且 ``is_suspended`` 真 → 剔除
        - ``exclude_limit_up=True`` 且 ``is_limit_up`` 真 → 剔除
        - ``exclude_limit_down=True`` 且 ``is_limit_down`` 真 → 剔除
        - ``industries`` 白名单非空且行业不在白名单 → 剔除
        - ``exclude_industries`` 黑名单非空且行业在黑名单 → 剔除
        - ``min_list_days`` 非空：上市天数 < min_list_days → 剔除

        元数据缺失某字段：对应过滤项静默跳过（不报错不剔除）+ warning。
        行业字段优先取 ``sw_industry_l1``，兜底取 ``industry``。

        :return: 剔除后的 ``factor_values_by_symbol`` 子集（保留原 dict 引用）。
        """
        filter_layer = getattr(cfg, "filter", None)
        if filter_layer is None:
            return factor_values_by_symbol

        exclude_st = bool(getattr(filter_layer, "exclude_st", False))
        exclude_suspended = bool(getattr(filter_layer, "exclude_suspended", False))
        exclude_limit_up = bool(getattr(filter_layer, "exclude_limit_up", False))
        exclude_limit_down = bool(getattr(filter_layer, "exclude_limit_down", False))
        industries_whitelist = RebalanceEngine._normalize_str_set(
            getattr(filter_layer, "industries", None)
        )
        industries_blacklist = RebalanceEngine._normalize_str_set(
            getattr(filter_layer, "exclude_industries", None)
        )
        min_list_days = RebalanceEngine._to_nonneg_int(
            getattr(filter_layer, "min_list_days", None)
        )

        # 7 项全关闭 → 无需过滤
        if not (
            exclude_st
            or exclude_suspended
            or exclude_limit_up
            or exclude_limit_down
            or industries_whitelist
            or industries_blacklist
            or min_list_days > 0
        ):
            return factor_values_by_symbol

        td_str = pd.Timestamp(trading_date).strftime("%Y-%m-%d")
        td_normalized = pd.Timestamp(trading_date).normalize()

        # 各剔除项计数（汇总日志用）
        cnt_st = cnt_suspended = cnt_limit_up = cnt_limit_down = 0
        cnt_industry = cnt_list_days = 0
        warned_missing_meta = False

        out: dict[str, dict[str, float]] = {}
        for symbol, values in factor_values_by_symbol.items():
            meta = RebalanceEngine._get_meta_for_day(
                candidates.get(symbol, {}), td_str
            )

            # 任一布尔过滤项开启但 meta 缺失 → 跳过该项（不剔除）+ 首次 warning
            if meta is None:
                if not warned_missing_meta:
                    logger.warning(
                        "rebalance 静态过滤: symbol=%s date=%s 缺少 extra 元数据，"
                        "跳过静态过滤项（不剔除）",
                        symbol, td_str,
                    )
                    warned_missing_meta = True
                out[symbol] = values
                continue

            # 1) ST
            if exclude_st and RebalanceEngine._meta_bool(meta, "is_st"):
                cnt_st += 1
                continue
            # 2) 停牌
            if exclude_suspended and RebalanceEngine._meta_bool(meta, "is_suspended"):
                cnt_suspended += 1
                continue
            # 3) 涨停
            if exclude_limit_up and RebalanceEngine._meta_bool(meta, "is_limit_up"):
                cnt_limit_up += 1
                continue
            # 4) 跌停
            if exclude_limit_down and RebalanceEngine._meta_bool(meta, "is_limit_down"):
                cnt_limit_down += 1
                continue
            # 5) 行业白/黑名单
            industry = RebalanceEngine._get_industry(meta)
            if industries_whitelist and industry not in industries_whitelist:
                cnt_industry += 1
                continue
            if industries_blacklist and industry in industries_blacklist:
                cnt_industry += 1
                continue
            # 6) 上市天数
            if min_list_days > 0:
                list_d = RebalanceEngine._parse_meta_date(meta.get("list_date"))
                if list_d is None:
                    # list_date 缺失：保守剔除（与 screener apply_filters 一致，次新股过滤场景）
                    cnt_list_days += 1
                    continue
                list_days = (td_normalized - pd.Timestamp(list_d)).days
                if list_days < min_list_days:
                    cnt_list_days += 1
                    continue

            out[symbol] = values

        # 汇总日志（任一有剔除才打 INFO）
        total_excluded = (
            cnt_st + cnt_suspended + cnt_limit_up + cnt_limit_down
            + cnt_industry + cnt_list_days
        )
        if total_excluded > 0:
            logger.info(
                "rebalance 静态过滤 date=%s: 剔除 ST=%d 停牌=%d 涨停=%d 跌停=%d "
                "行业=%d 上市天数=%d（共 %d，剩余 %d）",
                td_str, cnt_st, cnt_suspended, cnt_limit_up, cnt_limit_down,
                cnt_industry, cnt_list_days, total_excluded, len(out),
            )
        return out

    @staticmethod
    def _get_meta_for_day(
        candidate: dict[str, Any], td_str: str
    ) -> Optional[dict[str, Any]]:
        """从 candidate["extra"][td_str] 取当日 meta 字段。

        ``_build_candidates`` 把 ``extra_map[symbol]``（``{trade_date_str: {field}}``）
        整体注入 ``candidate["extra"]``。当日无记录返回 None。
        """
        extras = candidate.get("extra") if isinstance(candidate, dict) else None
        if not isinstance(extras, dict) or not extras:
            return None
        day_meta = extras.get(td_str)
        return day_meta if isinstance(day_meta, dict) else None

    @staticmethod
    def _meta_bool(meta: dict[str, Any], key: str) -> bool:
        """meta 布尔字段真值判定（兼容 "1"/1/True/"true"）。"""
        v = meta.get(key)
        if isinstance(v, bool):
            return v
        if isinstance(v, (int, float)):
            return v != 0
        if isinstance(v, str):
            return v.strip().lower() in ("1", "true", "yes", "y", "t")
        return False

    @staticmethod
    def _get_industry(meta: dict[str, Any]) -> str:
        """行业字段：优先 sw_industry_l1，兜底 industry；归一为非空 str。"""
        for key in ("sw_industry_l1", "industry"):
            v = meta.get(key)
            if v is not None:
                s = str(v).strip()
                if s:
                    return s
        return ""

    @staticmethod
    def _normalize_str_set(raw: Any) -> set[str]:
        """行业列表归一为非空 str 集合（None/非可迭代/全空 → 空集合）。"""
        if raw is None:
            return set()
        if isinstance(raw, (str, bytes)):
            raw = [raw]
        try:
            return {str(x).strip() for x in raw if str(x).strip()}
        except TypeError:
            return set()

    @staticmethod
    def _to_nonneg_int(value: Any) -> int:
        """转非负整数；失败/None/负数 → 0。"""
        if value is None:
            return 0
        try:
            n = int(value)
        except (TypeError, ValueError):
            return 0
        return n if n > 0 else 0

    @staticmethod
    def _parse_meta_date(value: Any) -> Optional[pd.Timestamp]:
        """解析 meta 日期字段（list_date 等）；失败 → None。"""
        if value is None:
            return None
        try:
            ts = pd.Timestamp(value)
            if pd.isna(ts):
                return None
            return ts.normalize()
        except (ValueError, TypeError):
            return None

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
        """FactorModel → dict（rank_stocks 接受 dict 或 None）。

        4 层结构下排序配置位于 factor 层（原 RankingModel 平移而来）。
        method="disabled" 或 factor 为 None 时视为不排序（返回 None）。
        """
        factor = cfg.factor
        if factor is None:
            return None
        if factor.method == "disabled":
            return None
        return factor.model_dump(exclude_none=True)

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
