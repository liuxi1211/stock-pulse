"""回测数据适配层：watcher K 线 list[dict] → akquant DataFrame（spec 007-backtest-center T2）。

衔接点：watcher 通过 HTTP 把 ``kline_data``(list[dict]) 传给 engine，本模块负责
转成 akquant ``run_backtest`` 能消费的形态（DatetimeIndex + OHLCV float64）。

约束：本模块不触库，仅做内存转换。
"""
from typing import Any, Optional

import pandas as pd

# watcher 实际可能用到的时间列名（按命中顺序匹配）
_TIME_COLS: tuple[str, ...] = ("date", "trade_date", "datetime", "timestamp")

# akquant 需要的标准 OHLCV 列（小写）
_OHLCV_COLS: tuple[str, ...] = ("open", "high", "low", "close", "volume")

# 基准序列候选收盘价列名
_BENCHMARK_CLOSE_COLS: tuple[str, ...] = ("close", "CLOSE", "price")

# watcher 在 buildKlineData 里补齐的基本面字段（与 watcher 侧字段名对齐）。
# rebalance_engine 在调仓日按 trade_date 从 extra_map 取用，用于 TUSHARE 基本面因子补算。
_EXTRA_FIELDS: tuple[str, ...] = ("pe_ttm", "pb", "total_mv", "roe_ttm")

# spec 011：watcher 经 kline_data 下发的元数据字段（用于静态过滤/行业暴露/涨跌停拒单）。
# 这些字段可能为字符串（如 sw_industry_l1）或布尔/数值，统一按原始类型保留，不做 float 强转。
_META_FIELDS: tuple[str, ...] = (
    "sw_industry_l1",      # 申万一级行业代码（P1-4 行业暴露 / P0-1 行业过滤）
    "industry",            # 兜底：Tushare stock_basic 简化口径行业
    "is_st",               # 当日是否 ST（P0-1 exclude_st）
    "is_suspended",        # 当日是否停牌（P0-1 exclude_suspended）
    "is_limit_up",         # 当日是否涨停（P0-1 exclude_limit_up / P0-4 涨停拒买）
    "is_limit_down",       # 当日是否跌停（P0-1 exclude_limit_down / P0-4 跌停拒卖）
    "list_date",           # 上市日期 YYYY-MM-DD（P0-1 min_list_days）
    # spec 011 P2-1：trade_cal 预计算调仓标记（watcher 预计算后下发）
    "is_first_of_week",
    "is_last_of_week",
    "is_first_of_month",
    "is_last_of_month",
    "is_first_of_quarter",
    "is_last_of_quarter",
)


def kline_to_df(kline_data: list[dict]) -> pd.DataFrame:
    """watcher K 线 ``list[dict]`` → akquant 可用的 DataFrame。

    步骤：
    1. ``pd.DataFrame(kline_data)``；
    2. 识别 ``date``/``trade_date``/``datetime``/``timestamp`` 时间列 → ``DatetimeIndex``；
    3. 仅保留 ``open``/``high``/``low``/``close``/``volume``；
    4. ``astype("float64")`` + 升序排序。

    空数据或缺时间列抛 ``ValueError``（含 errorCode=BACKTEST_DATA_INVALID 提示）。
    """
    if not kline_data:
        raise ValueError("BACKTEST_DATA_INVALID: K线数据为空")

    df = pd.DataFrame(kline_data)
    if df.empty:
        raise ValueError("BACKTEST_DATA_INVALID: K线数据为空")

    # 1. 识别时间列
    time_col: str | None = next((c for c in _TIME_COLS if c in df.columns), None)
    if time_col is None:
        raise ValueError(
            "BACKTEST_DATA_INVALID: 缺少时间列(date/trade_date/datetime/timestamp)"
        )

    df[time_col] = pd.to_datetime(df[time_col], errors="raise")
    df = df.set_index(time_col).sort_index()

    # 去重索引（保留最后一条）
    if df.index.has_duplicates:
        df = df[~df.index.duplicated(keep="last")]

    # 2. 仅保留 OHLCV
    keep = [c for c in _OHLCV_COLS if c in df.columns]
    if not keep:
        raise ValueError(
            "BACKTEST_DATA_INVALID: 缺少 OHLCV 列(open/high/low/close/volume)"
        )
    df = df[keep]

    # 3. 数值化（非法值 → NaN，由上游/akquant 处理）
    df = df.astype("float64")
    return df


def kline_to_df_map(kline_data: dict[str, list[dict]]) -> dict[str, pd.DataFrame]:
    """多标的 K 线分组转换。

    入参形如 ``{"000001.SZ": [...], "600000.SH": [...]}``，对每个 symbol 调
    :func:`kline_to_df`，返回 ``{symbol: DataFrame}``。
    """
    if not kline_data:
        raise ValueError("BACKTEST_DATA_INVALID: kline_data map 为空")

    out: dict[str, pd.DataFrame] = {}
    for symbol, rows in kline_data.items():
        out[str(symbol)] = kline_to_df(rows)
    return out


def kline_to_extra_map(
    kline_data: dict[str, list[dict]],
    extra_fields: Optional[tuple[str, ...]] = None,
    meta_fields: Optional[tuple[str, ...]] = None,
) -> dict[str, dict[str, dict[str, Any]]]:
    """从 watcher K 线中提取基本面 extra 字段与元数据 meta 字段（spec 010 缺陷 B + spec 011）。

    ``kline_to_df`` 只保留 OHLCV，基本面字段（pe_ttm/pb/total_mv/roe_ttm 等）
    被丢弃。本函数独立抽取，返回按 symbol → 日期 → 字段 的三层 map，
    供 ``RebalanceEngine._compute_one_factor`` 在调仓日按 trade_date 取用。

    - ``extra_fields``：数值型基本面字段，按 ``float()`` 强转（失败跳过）；
    - ``meta_fields``：元数据字段（行业/ST/涨停/调仓标记等），**保留原始类型**（str/bool/int），
      不做 float 强转。spec 011 静态过滤/行业暴露/涨跌停拒单/调仓日判定消费。

    :param kline_data: ``{symbol: [{date, open, ..., pe_ttm, sw_industry_l1, ...}, ...]}``。
    :param extra_fields: 数值型字段名元组，默认 :data:`_EXTRA_FIELDS`。
    :param meta_fields: 元数据字段名元组，默认 :data:`_META_FIELDS`。
    :return: ``{symbol: {trade_date_str: {pe_ttm: 12.3, sw_industry_l1: "801790.SI", ...}}}``，
        日期统一归一化为 ``YYYY-MM-DD``。无字段的 symbol 返回空 dict。
    """
    num_fields = extra_fields if extra_fields is not None else _EXTRA_FIELDS
    str_fields = meta_fields if meta_fields is not None else _META_FIELDS
    out: dict[str, dict[str, dict[str, Any]]] = {}
    if not kline_data:
        return out

    for symbol, rows in kline_data.items():
        sym_key = str(symbol)
        per_day: dict[str, dict[str, Any]] = {}
        if not rows:
            continue
        for row in rows:
            if not isinstance(row, dict):
                continue
            # 取日期（兼容 date/trade_date/datetime/timestamp 列名）
            time_col = next((c for c in _TIME_COLS if c in row), None)
            if time_col is None:
                continue
            try:
                day_str = pd.Timestamp(row[time_col]).strftime("%Y-%m-%d")
            except Exception:  # noqa: BLE001 - 单行日期解析失败跳过
                continue
            day_vals: dict[str, Any] = {}
            # 数值型字段：float 强转
            for fld in num_fields:
                if fld in row and row[fld] is not None:
                    try:
                        day_vals[fld] = float(row[fld])
                    except (TypeError, ValueError):
                        continue
            # 元数据字段：保留原始类型（str/bool/int），仅跳过 None
            for fld in str_fields:
                if fld in row and row[fld] is not None:
                    day_vals[fld] = row[fld]
            if day_vals:
                per_day[day_str] = day_vals
        if per_day:
            out[sym_key] = per_day
    return out


def normalize_benchmark(benchmark_data: list[dict]) -> pd.Series:
    """基准收盘价序列归一化到 1.0（``price / price[0]``）。

    识别 ``close``/``CLOSE``/``price`` 列；空数据抛 ``ValueError``。

    例：``[3000, 3030, 2970]`` → ``[1.0, 1.01, 0.99]``。
    """
    if not benchmark_data:
        raise ValueError("BACKTEST_DATA_INVALID: benchmark_data 为空")

    df = pd.DataFrame(benchmark_data)
    close_col: str | None = next((c for c in _BENCHMARK_CLOSE_COLS if c in df.columns), None)
    if close_col is None:
        raise ValueError(
            "BACKTEST_DATA_INVALID: benchmark_data 缺少收盘价列(close/CLOSE/price)"
        )

    prices = pd.to_numeric(df[close_col], errors="coerce").astype("float64")
    prices = prices.dropna()
    if prices.empty:
        raise ValueError("BACKTEST_DATA_INVALID: benchmark_data 收盘价全为空")

    first = prices.iloc[0]
    if first == 0:
        raise ValueError("BACKTEST_DATA_INVALID: benchmark_data 首个收盘价为 0，无法归一化")

    normalized = prices / first

    # 若有时间列，附带 DatetimeIndex 便于与权益曲线对齐
    time_col: str | None = next((c for c in _TIME_COLS if c in df.columns), None)
    if time_col is not None:
        idx = pd.to_datetime(df.loc[prices.index, time_col], errors="coerce")
        normalized.index = idx

    return normalized


__all__ = ["kline_to_df", "kline_to_df_map", "kline_to_extra_map", "normalize_benchmark"]
