"""回测编排入口（spec 007-backtest-center T2）。

把 HTTP 传入的 ``{strategy_config, kline_data, benchmark_data}`` 编排成一次完整回测：

    StrategyConfigModel 解析 → 范式校验 → kline_to_df_map
    → compile_strategy → build_backtest_kwargs → aq.run_backtest → serialize_result

强制 A 股规则（``t_plus_one=True`` / ``lot_size=100`` / ``broker_profile`` / 滑点用 dict）。
异常包成 ``{success: False, errorCode: ...}`` 由上层 HTTP 处理器转信封。

约束：本模块不触库。
"""
from __future__ import annotations

from typing import Any, Optional

import akquant as aq
import pandas as pd

from services.backtest.compiler import CompilerError, compile_strategy
from services.backtest.data_adapter import (
    kline_to_df_map,
    kline_to_extra_map,
    normalize_benchmark,
)
from services.backtest.result_serializer import serialize_result
from services.backtest.watcher_client import build_watcher_client
from services.strategy.models import BacktestConfigModel, StrategyConfigModel


# ============================================================
# 构建 aq.run_backtest 的 kwargs（强制 A 股规则）
# ============================================================

def build_backtest_kwargs(bt_config: BacktestConfigModel) -> dict:
    """从 :class:`BacktestConfigModel` 构建 ``aq.run_backtest`` 的 kwargs。

    强制规则：
    - ``t_plus_one=True``（A 股 T+1）；
    - ``lot_size=100``；
    - ``broker_profile`` 默认 ``cn_stock_miniqmt``；
    - ``slippage`` 裸 float → dict（``{"type":"percent","value":<float>}``）；
    - ``show_progress=False``。

    返回的 dict 仅含**非 None** 的键，避免覆盖 akquant 默认值。
    """
    kwargs: dict[str, Any] = {
        # 强制 A 股规则
        # 注：t_plus_one 故意硬编码 True（A 股 T+1 强约束），忽略 schema backtest_config.t_plus_one；
        # 若未来支持非 A 股市场再开放为可配置。详见「策略配置项排查报告」D1。
        "t_plus_one": True,
        "lot_size": bt_config.lot_size if bt_config.lot_size else 100,
        "show_progress": False if bt_config.show_progress is None else bool(bt_config.show_progress),
    }

    # 初始资金
    kwargs["initial_cash"] = float(bt_config.initial_cash)

    # broker_profile（默认 cn_stock_miniqmt）
    broker_profile = bt_config.broker_profile or "cn_stock_miniqmt"
    kwargs["broker_profile"] = broker_profile

    # 费率覆盖（非 None 才透传，优先级高于 profile）
    if bt_config.commission_rate is not None:
        kwargs["commission_rate"] = float(bt_config.commission_rate)
    if bt_config.stamp_tax_rate is not None:
        kwargs["stamp_tax_rate"] = float(bt_config.stamp_tax_rate)
    if bt_config.transfer_fee_rate is not None:
        kwargs["transfer_fee_rate"] = float(bt_config.transfer_fee_rate)
    if bt_config.min_commission is not None:
        kwargs["min_commission"] = float(bt_config.min_commission)

    # 滑点：裸 float → dict（防 0.2 被当 20%）；None 时注入默认 dict（spec AC-2 强制 slippage 为 dict）
    slippage = bt_config.slippage
    if slippage is None:
        kwargs["slippage"] = {"type": "percent", "value": 0.0002}
    elif isinstance(slippage, float):
        kwargs["slippage"] = {"type": "percent", "value": float(slippage)}
    else:
        # SlippageDict pydantic 模型 → dict
        try:
            kwargs["slippage"] = {
                "type": slippage.type,
                "value": float(slippage.value),
            }
        except AttributeError:
            kwargs["slippage"] = slippage

    # volume_limit_pct
    if bt_config.volume_limit_pct is not None:
        kwargs["volume_limit_pct"] = float(bt_config.volume_limit_pct)

    # warmup_period / history_depth
    if bt_config.warmup_period is not None:
        kwargs["warmup_period"] = int(bt_config.warmup_period)
    if bt_config.history_depth is not None:
        kwargs["history_depth"] = int(bt_config.history_depth)

    # 时区
    if bt_config.timezone:
        kwargs["timezone"] = bt_config.timezone

    # 起止时间
    if bt_config.start_date:
        kwargs["start_time"] = bt_config.start_date
    if bt_config.end_date:
        kwargs["end_time"] = bt_config.end_date

    # fill_policy（dict 透传）
    if bt_config.fill_policy:
        kwargs["fill_policy"] = dict(bt_config.fill_policy)

    # risk_config
    if bt_config.risk_config:
        kwargs["risk_config"] = dict(bt_config.risk_config)

    # strict_strategy_params（动态编译的策略构造参数固定，建议 False 避免 akquant 校验）
    # 注：故意硬编码 False（D2）。compile_strategy 动态生成的 Strategy 子类构造参数固定，
    # 若 True 会触发 akquant 严格参数校验误报。schema 默认 True 仅对用户自定义策略类有效。
    kwargs["strict_strategy_params"] = False

    # 注：optimizer.run_grid_optimize 复用此函数保证 GRID 与单次回测 A 股规则一致（spec 015 FR-O1）。
    return kwargs


# ============================================================
# 回测编排主入口
# ============================================================

def run_backtest_engine(
    strategy_config: dict,
    kline_data: dict,
    benchmark_data: Optional[list] = None,
    watcher_base_url: Optional[str] = None,
) -> dict:
    """编排一次完整回测，返回序列化结果 dict。

    入参：
    - ``strategy_config``：原始 JSON dict（``StrategyConfigModel`` 形态）；
    - ``kline_data``：``{symbol: list[dict]}``，watcher 传入的 K 线；
    - ``benchmark_data``：可选的基准 K 线 list[dict]，归一化后叠加到结果；
    - ``watcher_base_url``：可选的 watcher 只读接口基址，用于 rebalance 范式的
      point-in-time 成分股过滤（spec 010 缺陷 A 修复）。None 时降级为全量候选。
      watcher 与 engine 同机部署，通常为 ``http://localhost:<port>``（端口可变）。

    异常：抛 :class:`BacktestError`（含 errorCode），由 HTTP 层捕获转信封。
    成功返回：``serialize_result(...)`` 的 dict（无 success 包装）。
    """
    # 1. 解析策略配置
    try:
        config = StrategyConfigModel.model_validate(strategy_config)
    except Exception as exc:  # noqa: BLE001 - Pydantic 校验失败
        raise BacktestError(
            f"策略配置解析失败: {exc}",
            error_code="BACKTEST_CONFIG_INVALID",
        ) from exc

    # 2. 回测配置兜底
    bt_config = config.backtest_config if config.backtest_config is not None else BacktestConfigModel()

    # 3. K 线 → DataFrame map + 提取基本面 extra（缺陷 B 修复）
    try:
        data_map = kline_to_df_map(kline_data)
    except ValueError as exc:
        # data_adapter 抛的 ValueError 已含 BACKTEST_DATA_INVALID 前缀
        raise BacktestError(str(exc), error_code=_extract_error_code(str(exc), "BACKTEST_DATA_INVALID")) from exc

    # 从 K 线中提取基本面字段（pe_ttm/pb/...），供 rebalance_engine 按调仓日取用；
    # 非 rebalance 范式下不会消费，提取代价低（纯内存遍历）。
    extra_map = kline_to_extra_map(kline_data)

    # 3.5 构造 watcher 只读客户端（point-in-time 成分股过滤，缺陷 A 修复）
    watcher_client = build_watcher_client(watcher_base_url)

    # 4. 编译策略（rebalance 范式需传入 universe_symbols 以发现全池候选；
    #    watcher_client / extra_map 透传给 RebalanceEngine）
    try:
        strategy_cls = compile_strategy(
            config,
            universe_symbols=list(data_map.keys()) if data_map else None,
            watcher_client=watcher_client,
            extra_map=extra_map,
        )
    except CompilerError as exc:
        raise BacktestError(str(exc), error_code=_extract_error_code(str(exc), "BACKTEST_COMPILE_FAILED")) from exc

    # 5. 构建 run_backtest kwargs
    kwargs = build_backtest_kwargs(bt_config)

    # 5.1 轮动范式默认当日收盘成交（实盘调仓在尾盘抓收盘价）：
    #     has_rebalance 且用户未显式配置 fill_policy 时，覆盖 akquant 默认的 next-open。
    #     择时范式（signals）保持 akquant 默认行为，不注入。
    has_rebalance = config.trading_config is not None and config.trading_config.rebalance is not None
    if has_rebalance and not kwargs.get("fill_policy"):
        kwargs["fill_policy"] = aq.make_fill_policy(
            price_basis="close", temporal="same_cycle", bar_offset=0
        )

    # 6. 推断 symbols
    symbols = list(data_map.keys())
    kwargs["symbols"] = symbols if len(symbols) > 1 else symbols[0]

    # 7. 跑回测
    try:
        result = aq.run_backtest(
            data=data_map,
            strategy=strategy_cls,
            **kwargs,
        )
    except Exception as exc:  # noqa: BLE001 - akquant 内部异常
        raise BacktestError(f"回测执行失败: {exc}", error_code="BACKTEST_ENGINE_FAILED") from exc

    # 8. 基准归一化
    benchmark_series: Optional[pd.Series] = None
    if benchmark_data:
        try:
            benchmark_series = normalize_benchmark(benchmark_data)
        except ValueError as exc:
            # 基准失败不阻断，仅记录 None
            benchmark_series = None

    # 9. 序列化
    # spec 011 P2-5：透传 effective warmup 元信息（compiler 在策略类上挂的类属性）
    effective_config = _build_effective_config(strategy_cls, bt_config)
    return serialize_result(
        result,
        benchmark_series=benchmark_series,
        effective_config=effective_config,
    )


def _build_effective_config(strategy_cls: Any, bt_config: BacktestConfigModel) -> dict:
    """构造 ``effective_config``（spec 011 P2-5）。

    从编译产物 ``strategy_cls`` 的类属性读 warmup 元信息：

    - ``_akw_effective_warmup``：实际生效 warmup（用户值与自动推断值的较大者）；
    - ``_akw_warmup_source``：``auto_inferred`` / ``user_override``；
    - ``_akw_warmup_reason``：``max(user=<u>, auto_inferred=<a>)``。

    缺失时（strategy_cls 无类属性，如绕过 compiler 的场景）降级为只回传用户配置值。
    """
    eff_warmup = getattr(strategy_cls, "_akw_effective_warmup", None)
    warmup_source = getattr(strategy_cls, "_akw_warmup_source", None)
    warmup_reason = getattr(strategy_cls, "_akw_warmup_reason", None)

    out: dict[str, Any] = {}
    if eff_warmup is not None:
        out["warmup_period"] = int(eff_warmup)
    elif bt_config.warmup_period is not None:
        out["warmup_period"] = int(bt_config.warmup_period)
    if warmup_source is not None:
        out["warmup_source"] = warmup_source
    if warmup_reason is not None:
        out["warmup_reason"] = warmup_reason
    return out


# ============================================================
# 异常
# ============================================================

class BacktestError(Exception):
    """回测编排异常（带 errorCode）。"""

    def __init__(self, message: str, error_code: str = "BACKTEST_FAILED") -> None:
        self.message = message
        self.error_code = error_code
        super().__init__(message)


def _extract_error_code(text: str, default: str) -> str:
    """从 ``"CODE: ..."`` 形态的 message 提取 errorCode，失败用 default。"""
    if ":" in text:
        head = text.split(":", 1)[0].strip()
        if head and head.replace("_", "").replace("BACKTEST", "").isalnum():
            return head
    return default


__all__ = ["build_backtest_kwargs", "run_backtest_engine", "BacktestError"]
