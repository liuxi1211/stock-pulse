"""GRID 参数寻优 engine 封装（spec 015 FR-O1 / Task O-3）。

封装 akquant.run_grid_search：

- 落盘策略类来自 strategy_source_gen（pickle 友好）；
- constraint / resultFilter 经 condition_dsl 编译为 pickle 安全 callable；
- max_workers 读 cgroup quota 而非宿主机 cpu_count（FR-O8）；
- 返回 DataFrame 序列化（NaN→None、Timestamp→isoformat、Timedelta→total_seconds）。

约束：本模块不触库（不导入也不直接读写任何本地关系数据库）。即便上层透传了
akquant 的断点续传参数也会在调用前被 pop 掉，绝不落盘。
"""
from __future__ import annotations

import logging
import math
import os
from typing import Any, Callable, Optional, Union

import pandas as pd

from services.backtest.data_adapter import kline_to_df_map
from services.backtest.runner import build_backtest_kwargs
from services.backtest.strategy_source_gen import StrategySourceGenerator, StrategyNotSupportedError
from services.shared.condition_dsl import compile_constraint
from services.strategy.errors import ErrorCode
from services.strategy.models import (
    BacktestConfigModel,
    StrategyConfigModel,
    TunableParamModel,
)

logger = logging.getLogger(__name__)


# ============================================================
# 异常
# ============================================================

class OptimizeError(Exception):
    """寻优错误基类（含 errorCode 与 message）。

    errorCode 复用 ``services.strategy.errors.ErrorCode`` 里的稳定码
    （如 TUNABLE_PARAM_UNKNOWN / PICKLE_IMPORT_MAIN_FORBIDDEN），
    由 HTTP 层据此转信封。
    """

    def __init__(self, message: str, error_code: str = "OPTIMIZE_FAILED") -> None:
        self.message = message
        self.error_code = error_code
        super().__init__(message)


# ============================================================
# cgroup 感知 CPU 检测（FR-O8）
# ============================================================

# cgroup v2 / v1 路径常量
_CGROUP_V2_CPU_MAX = "/sys/fs/cgroup/cpu.max"
_CGROUP_V1_QUOTA = "/sys/fs/cgroup/cpu/cpu.cfs_quota_us"
_CGROUP_V1_PERIOD = "/sys/fs/cgroup/cpu/cpu.cfs_period_us"


class CgroupCpuDetector:
    """cgroup 感知的可用 CPU 数检测器（spec 015 FR-O8）。

    在容器/云主机里，``os.cpu_count()`` 通常返回**宿主机**核数（远超 cgroup
    quota），用它做 ``max_workers`` 会导致严重上下文切换。本检测器按
    cgroup v2 → v1 → fallback 的顺序解析真实可用核数。
    """

    @staticmethod
    def _read_text(path: str) -> Optional[str]:
        try:
            with open(path, "r", encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            return None

    @classmethod
    def _detect_cgroup_cpus(cls) -> Optional[int]:
        """解析 cgroup 限额，返回核数（失败返回 None）。"""
        # cgroup v2: "quota period" 或 "max period"
        v2 = cls._read_text(_CGROUP_V2_CPU_MAX)
        if v2:
            parts = v2.split()
            if len(parts) == 2:
                quota_str, period_str = parts
                try:
                    period = float(period_str)
                except ValueError:
                    period = None
                if quota_str == "max" or period is None or period <= 0:
                    return None  # 无限制
                try:
                    quota = float(quota_str)
                except ValueError:
                    return None
                if quota <= 0:
                    return None
                # 向下取整：不足 1 个完整核的份额不算 1 核
                return max(1, int(math.floor(quota / period)))

        # cgroup v1: quota / period
        q_str = cls._read_text(_CGROUP_V1_QUOTA)
        p_str = cls._read_text(_CGROUP_V1_PERIOD)
        if q_str and p_str:
            try:
                quota = float(q_str)
                period = float(p_str)
            except ValueError:
                return None
            if quota <= 0 or period <= 0:
                return None
            return max(1, int(math.floor(quota / period)))

        return None

    @classmethod
    def available_cpu_count(cls) -> int:
        """返回当前进程可用的 CPU 核数。

        解析顺序：cgroup v2 → cgroup v1 → ``os.cpu_count()``。最终值取
        ``max(1, min(cgroup_cpus, os.cpu_count()))``（不超过宿主机实际核数）。
        Windows 上无 cgroup，直接 fallback 到 ``os.cpu_count() or 1``。
        """
        host = os.cpu_count() or 1

        # Windows 与无 cgroup 的环境直接走 fallback
        if os.name == "nt":
            return host

        cgroup_cpus = cls._detect_cgroup_cpus()
        if cgroup_cpus is None:
            return host

        # 不超过宿主机实际核数（防止 cgroup 配置异常）
        return max(1, min(cgroup_cpus, host))


# ============================================================
# 序列化：run_grid_search 返回的 DataFrame → dict
# ============================================================

# 参数列集合之外的元信息列名（akquant 在 return_df=True 时附加）
_META_DURATION_COL = "_duration"


def _json_safe_scalar(value: Any) -> Any:
    """把单个标量转成 JSON 安全值。

    - ``NaN`` / ``Inf``（含 pd.NaT 已在前面拦）→ None；
    - ``pd.Timestamp`` / ``datetime`` → ``.isoformat()``；
    - ``pd.Timedelta`` → ``.total_seconds()``；
    - 其它原样返回。
    """
    # 先处理 pandas 类型，避免被 float() 转成纳秒
    if isinstance(value, pd.Timestamp):
        if pd.isna(value):
            return None
        return value.isoformat()
    if hasattr(value, "isoformat") and not isinstance(value, (str, bytes)):
        try:
            return value.isoformat()
        except Exception:  # noqa: BLE001
            pass
    if hasattr(value, "total_seconds") and not isinstance(value, (str, bytes)):
        try:
            return float(value.total_seconds())
        except Exception:  # noqa: BLE001
            pass

    # 数字 / bool / str
    if isinstance(value, bool):
        return value
    if isinstance(value, (int,)):
        return value
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return None
        return value

    # numpy 标量
    try:
        import numpy as np  # 局部导入，避免顶层依赖
    except ImportError:  # pragma: no cover - akquant 必然依赖 numpy
        np = None
    if np is not None and isinstance(value, np.generic):
        try:
            v = value.item()
            return _json_safe_scalar(v)
        except Exception:  # noqa: BLE001
            return None

    # 其它（str / None 等）原样
    return value


def serialize_grid_result(
    df: pd.DataFrame,
    sort_by: str = "sharpe_ratio",
    param_keys: Optional[list[str]] = None,
    top_n: Optional[int] = None,
) -> dict:
    """把 ``run_grid_search`` 返回的 DataFrame 序列化为���端友好的 dict。

    DataFrame 列 = 参数列 + 指标列 + ``_duration``。由于列名无法稳定区分
    参数列与指标列，约定：

    - ``param_keys`` 显式传入时：在集合内的列进 ``params``，其余非元信息列进 ``metrics``；
    - ``param_keys`` 为 None 时（spec 公开签名兼容）：除 ``_duration`` 外**所有**
      列都视为参数列（``metrics`` 留空），仅用于快速冒烟，正式寻优请由
      :func:`run_grid_optimize` 内部传 ``param_keys`` 调用。

    排序：``run_grid_search`` 已按 ``sort_by`` 降序排好。

    :param top_n: 截断前 N 条；None 表示全量。
    :return: ``{"top_n": [...], "sort_by": sort_by}``，每条记录含
        ``rank`` / ``params`` / ``metrics`` / ``_duration``。
    """
    if df is None or len(df) == 0:
        return {"top_n": [], "sort_by": sort_by}

    param_set = set(param_keys or [])
    meta_cols = {_META_DURATION_COL}
    # param_keys 未传时：除 _duration 外全部归入 params（兼容公开签名）
    fallback_all_params = param_keys is None

    rows: list[dict] = []
    sub_df = df if top_n is None or top_n <= 0 else df.head(int(top_n))
    for i, (_, row) in enumerate(sub_df.iterrows()):
        params: dict[str, Any] = {}
        metrics: dict[str, Any] = {}
        duration: Any = None

        for col in df.columns:
            value = _json_safe_scalar(row[col])
            if col in meta_cols:
                if col == _META_DURATION_COL:
                    duration = value
            elif fallback_all_params:
                params[col] = value
            elif col in param_set:
                params[col] = value
            else:
                # spec 015 修复（评审问题 4）：akquant 0.2.47 的 win_rate 与
                # ``_pct`` 后缀字段一样以**原始百分数**存储（55.0 表示 55%）。
                # 前端 optimize.js 的展示规则只对 ``/_pct$/`` 做 ÷100，
                # win_rate 既不带 _pct 后缀又是百分数会被误展示为 5500%。
                # 这里在 engine 序列化侧统一把 win_rate 也 ÷100 归一为小数，
                # 让前端"非 _pct 字段直接展示"的规则正确显示 0.55（=55%）。
                metrics[col] = _normalize_metric_unit(col, value)

        rows.append(
            {
                "rank": i + 1,
                "params": params,
                "metrics": metrics,
                _META_DURATION_COL: duration,
            }
        )

    return {"top_n": rows, "sort_by": sort_by}


# akquant 0.2.47 中**以原始百分数存储**但**列名不带 _pct 后缀**的指标白名单。
# 在序列化层统一 ÷100 归一为小数，避免前端展示歧义（评审问题 4）。
_PERCENT_LIKE_NO_SUFFIX_KEYS: frozenset[str] = frozenset({"win_rate"})


def _normalize_metric_unit(name: str, value: Any) -> Any:
    """对个别单位口径不规范的指标在序列化层做归一。

    - ``win_rate``：akquant 以原始百分数（55.0=55%）存储但列名无 ``_pct`` 后缀，
      前端规则无法识别 → ÷100 归一为小数（0.55=55%）；
    - 其余指标原样返回（``_pct`` 后缀字段由前端 ÷100，已在 optimize.js 处理）。

    None / 非数值原样返回，不做转换。
    """
    if value is None:
        return None
    if name in _PERCENT_LIKE_NO_SUFFIX_KEYS:
        try:
            f = float(value)
        except (TypeError, ValueError):
            return value
        if math.isnan(f) or math.isinf(f):
            return None
        return f / 100.0
    return value


# ============================================================
# 校验：param_grid ↔ tunable_params
# ============================================================

def validate_param_grid(param_grid: dict, tunable_params: list) -> None:
    """校验 param_grid 的 key 都在 tunable_params 声明内。

    :raises OptimizeError:
        - ``TUNABLE_PARAM_INVALID``：param_grid 为空或非 dict（至少一个参数维度）；
        - ``TUNABLE_PARAM_MISSING``：策略本身未声明任何 tunable_params
          （寻优无意义，与下面的"名字拼错"显式区分，便于前端给出不同提示）；
        - ``TUNABLE_PARAM_UNKNOWN``：param_grid 含未知参数名
          （用户填的 name 不在策略声明内）。
    """
    if not isinstance(param_grid, dict) or not param_grid:
        raise OptimizeError(
            "param_grid 不能为空（至少一个参数维度）",
            error_code=ErrorCode.TUNABLE_PARAM_INVALID[0],
        )

    names = set()
    for p in tunable_params or []:
        # 同时兼容 dict 与 pydantic TunableParamModel
        name = getattr(p, "name", None)
        if name is None and isinstance(p, dict):
            name = p.get("name")
        if name is not None:
            names.add(str(name))

    if not names:
        # 策略本身没声明可调参数 → 用 MISSING 而非 INVALID，前端可据此提示
        # "请在策略模板中先声明 tunable_params"，区别于下面 UNKNOWN 的"名字拼错"。
        code, msg = ErrorCode.TUNABLE_PARAM_MISSING
        raise OptimizeError(msg, error_code=code)

    unknown = [k for k in param_grid.keys() if str(k) not in names]
    if unknown:
        code, msg = ErrorCode.TUNABLE_PARAM_UNKNOWN
        raise OptimizeError(
            f"{msg}：未知参数 {unknown}；策略声明参数为 {sorted(names)}",
            error_code=code,
        )


# ============================================================
# 主入口：run_grid_optimize
# ============================================================

def _resolve_tunable_params(config: StrategyConfigModel) -> list[dict]:
    """把 StrategyConfigModel.tunable_params 统一为 list[dict]。"""
    out: list[dict] = []
    raw = config.tunable_params or []
    for p in raw:
        if isinstance(p, TunableParamModel):
            out.append(p.model_dump())
        elif isinstance(p, dict):
            out.append(dict(p))
        else:
            # 兜底：尝试属性反射
            out.append({"name": str(getattr(p, "name", p))})
    return out


def _resolve_data_and_symbols(
    kline_data: dict,
    config: StrategyConfigModel,
) -> tuple[Any, Any]:
    """从 kline_data 解析 data 与 symbols。

    - ``kline_data`` 为 ``{symbol: list[dict]}`` → 用 ``kline_to_df_map``
      转为 ``dict[str, DataFrame]``，symbols 取 keys；
    - 单标的时 ``symbols`` 返回 str，多标的返回 list[str]。
    """
    if not kline_data:
        raise OptimizeError(
            "kline_data 为空，无法寻优",
            error_code=ErrorCode.OPTIMIZATION_INSUFFICIENT_DATA[0],
        )

    # 优先用 kline_data 自身结构推断（与 run_backtest_engine 一致）
    data_map = kline_to_df_map(kline_data)
    syms = list(data_map.keys())
    if not syms:
        # 兜底：尝试从 screen_config.universe.stocks 读
        sc = config.screen_config
        if sc is not None and sc.universe is not None and sc.universe.stocks:
            syms = [str(s) for s in sc.universe.stocks]

    if len(syms) == 1:
        return data_map, syms[0]
    return data_map, syms


def _compile_optional_dsl(
    dsl: Optional[dict],
) -> Optional[Callable[[dict], bool]]:
    """DSL dict 编译为 pickle 安全 callable；None 时返回 None。"""
    if dsl is None:
        return None
    try:
        return compile_constraint(dsl)
    except Exception as exc:  # noqa: BLE001 - DSL 编译失败统一成 OptimizeError
        raise OptimizeError(
            f"constraint/resultFilter DSL 编译失败: {exc}",
            error_code="OPTIMIZE_DSL_INVALID",
        ) from exc


def run_grid_optimize(
    strategy_config: dict,
    kline_data: dict,
    param_grid: dict,
    sort_by: Union[str, list[str]] = "sharpe_ratio",
    max_workers: Optional[int] = None,
    constraint: Optional[dict] = None,
    result_filter: Optional[dict] = None,
    top_n: int = 10,
    ascending: Union[bool, list[bool]] = False,
) -> dict:
    """GRID 参数寻优主入口（spec 015 FR-O1）。

    入参：
    - ``strategy_config``：原始策略配置 JSON（``StrategyConfigModel`` 形态），
      含 ``tunable_params`` 声明与 ``backtest_config``。
    - ``kline_data``：``{symbol: list[dict]}``，watcher 传入的 K 线。
    - ``param_grid``：``{param_name: [v1, v2, ...]}``，key 必须在
      ``tunable_params`` 声明的 name 集合内。
    - ``sort_by``：排序指标（``run_grid_search`` 的 sort_by）。
    - ``max_workers``：None 时用 cgroup 感知核数；用户传值取
      ``min(用户值, available_cpu_count())``。
    - ``constraint`` / ``result_filter``：condition_dsl JSON，None 时不过滤。
    - ``top_n``：返回前 N 条；``<=0`` 表示全量。

    :return: ``{"status": "SUCCESS", "sort_by": ..., "top_n": [...],
        "total_combinations": N, "unit_convention": {...}}``。
    :raises OptimizeError: 任何环节失败（参数校验、DSL 编译、akquant 异常等）。
    """
    # 延迟导入 akquant，避免顶层 import 副作用
    import akquant as aq

    # 1. 解析策略配置
    try:
        config = StrategyConfigModel.model_validate(strategy_config)
    except Exception as exc:  # noqa: BLE001 - Pydantic 校验失败
        raise OptimizeError(
            f"策略配置解析失败: {exc}",
            error_code="OPTIMIZE_CONFIG_INVALID",
        ) from exc

    tunable_params = _resolve_tunable_params(config)

    # 2. 校验 param_grid ↔ tunable_params
    validate_param_grid(param_grid, tunable_params)

    # 3. 生成落盘策略类（pickle 友好）
    try:
        strat_class = StrategySourceGenerator().generate(
            strategy_config, tunable_params
        )
    except StrategyNotSupportedError as exc:
        # spec 015 修复：tunable_params 不匹配任何预置模板，明确报错而非跑空
        raise OptimizeError(
            str(exc),
            error_code="OPTIMIZE_STRATEGY_NOT_SUPPORTED",
        ) from exc
    except Exception as exc:  # noqa: BLE001 - source_gen 失败
        raise OptimizeError(
            f"策略源码生成失败: {exc}",
            error_code="OPTIMIZE_STRATEGY_GEN_FAILED",
        ) from exc

    # 3.1 防御性：策略类不得在 __main__（source_gen 不会产生，但兜底）
    if getattr(strat_class, "__module__", "") == "__main__":
        code, msg = ErrorCode.PICKLE_IMPORT_MAIN_FORBIDDEN
        raise OptimizeError(msg, error_code=code)

    # 4. 编译 constraint / result_filter（pickle 安全）
    compiled_constraint = _compile_optional_dsl(constraint)
    compiled_result_filter = _compile_optional_dsl(result_filter)

    # 5. 解析 data / symbols
    data, symbols = _resolve_data_and_symbols(kline_data, config)

    # 6. 构建 run_backtest kwargs（复用单次回测的 A 股规则）
    bt_config = (
        config.backtest_config if config.backtest_config is not None else BacktestConfigModel()
    )
    backtest_kwargs = build_backtest_kwargs(bt_config)
    backtest_kwargs["symbols"] = symbols

    # 6.1 强约束：即便上层透传了 SQLite 续传参数也必须丢弃（engine 禁触库）
    backtest_kwargs.pop("db_path", None)

    # 7. max_workers：cgroup 感知
    available = CgroupCpuDetector.available_cpu_count()
    if max_workers is None:
        effective_workers = available
    else:
        try:
            user_workers = int(max_workers)
        except (TypeError, ValueError) as exc:
            raise OptimizeError(
                f"max_workers 必须是正整数，收到 {max_workers!r}",
                error_code="OPTIMIZE_MAX_WORKERS_INVALID",
            ) from exc
        if user_workers <= 0:
            raise OptimizeError(
                f"max_workers 必须 > 0，收到 {user_workers}",
                error_code="OPTIMIZE_MAX_WORKERS_INVALID",
            )
        effective_workers = max(1, min(user_workers, available))
    logger.info(
        "grid optimize max_workers=%s (available=%s, user=%s)",
        effective_workers, available, max_workers,
    )

    # 8. sort_by 归一化为 str（用于响应回显；list 时取首项）
    sort_by_str = sort_by[0] if isinstance(sort_by, (list, tuple)) and sort_by else str(sort_by)

    # 9. 调 akquant.run_grid_search
    try:
        result_df = aq.run_grid_search(
            strategy=strat_class,
            param_grid=param_grid,
            data=data,
            sort_by=sort_by,
            ascending=ascending,
            max_workers=effective_workers,
            constraint=compiled_constraint,
            result_filter=compiled_result_filter,
            return_df=True,
            **backtest_kwargs,
        )
    except Exception as exc:  # noqa: BLE001 - akquant 内部异常
        raise OptimizeError(f"GRID 寻优执行失败: {exc}", error_code="OPTIMIZE_ENGINE_FAILED") from exc

    if not isinstance(result_df, pd.DataFrame):
        raise OptimizeError(
            f"run_grid_search 未返回 DataFrame（return_df=True 失效），类型={type(result_df)!r}",
            error_code="OPTIMIZE_UNEXPECTED_RESULT",
        )

    # 10. 序列化（区分参数列/指标列，NaN-safe）
    param_keys = list(param_grid.keys())
    serialized = serialize_grid_result(
        result_df, sort_by=sort_by_str, param_keys=param_keys, top_n=top_n
    )

    total = int(len(result_df))

    return {
        "status": "SUCCESS",
        "sort_by": sort_by_str,
        "top_n": serialized["top_n"],
        "total_combinations": total,
        "unit_convention": {
            "total_return_pct": "原始百分数（15.0 表示 15%，前端按需 ÷100）",
            "max_drawdown_pct": "原始百分数（正数存，15.0 表示回撤 15%）",
            # spec 015 修复：win_rate 在 engine 序列化侧已 ÷100 归一为小数
            # （0.55=55%），前端直接展示无需再 ÷100。
            "win_rate": "小数（engine 已归一：0.55 表示 55%）",
            "annualized_return": "小数（0.15 表示 15%）",
            "cagr": "小数（0.15 表示 15%）",
            "volatility": "小数（0.20 表示 20%）",
            "sharpe_ratio": "比值（无量纲）",
        },
    }


# ============================================================
# WALK-FORWARD 滚动样本外验证（spec 015 FR-O4 / Task O-4）
# ============================================================

from services.backtest.overfit_metrics import compute_overfit_metrics

# 窗口对齐换算（spec 015 FR-O4）：year/quarter 单位 → bar 数
# ``bar_count`` 不参与换算（直接用 period 原值），单独列入白名单。
_WINDOW_BAR_COUNT: dict[str, int] = {"year": 244, "quarter": 61}

# 显式合法 window_align 白名单（含 bar_count）。
# 之前用 ``window_align not in _WINDOW_BAR_COUNT and window_align != "bar_count"``
# 表达"非法则报错"，逻辑耦合脆弱（依赖 _WINDOW_BAR_COUNT 不含 bar_count 的隐式假设）。
# 改为显式白名单后，未来新增 align 类型只需同时更新白名单 + 换算表，校验自动生效。
_VALID_WINDOW_ALIGN: frozenset[str] = frozenset({"bar_count", "year", "quarter"})


def _resolve_window_bars(
    train_period: int,
    test_period: int,
    window_align: str,
) -> tuple[int, int]:
    """把 train_period/test_period 按 window_align 换算为 bar 数。

    - ``bar_count``：直接用 train_period/test_period（已是 bar 数）；
    - ``year``：乘 244（A 股年交易日）；
    - ``quarter``：乘 61。

    :raises OptimizeError:
        - ``OPTIMIZE_CONFIG_INVALID``：``window_align`` 不在
          :data:`_VALID_WINDOW_ALIGN` 白名单内 / period 非正整数。
    """
    # 1) 先校验 window_align（显式白名单，独立于换算表）
    if window_align not in _VALID_WINDOW_ALIGN:
        raise OptimizeError(
            f"window_align 非法: {window_align!r}，"
            f"支持 {sorted(_VALID_WINDOW_ALIGN)}",
            error_code="OPTIMIZE_CONFIG_INVALID",
        )
    # 2) 校验 period 类型与取值
    try:
        tp = int(train_period)
        ep = int(test_period)
    except (TypeError, ValueError) as exc:
        raise OptimizeError(
            f"train_period/test_period 必须是正整数，收到 train={train_period!r} test={test_period!r}",
            error_code="OPTIMIZE_CONFIG_INVALID",
        ) from exc
    if tp <= 0 or ep <= 0:
        raise OptimizeError(
            f"train_period/test_period 必须 > 0，收到 train={tp} test={ep}",
            error_code="OPTIMIZE_CONFIG_INVALID",
        )

    # 3) 换算（window_align 已在白名单内，bar_count 走直用分支，其余查换算表）
    if window_align == "bar_count":
        return tp, ep
    factor = _WINDOW_BAR_COUNT[window_align]
    return tp * factor, ep * factor


def _build_segment_result(
    best_params: dict,
    train_top1_metrics: dict,
    test_metrics: dict,
) -> dict:
    """组装单段 WF 段结果 dict（供 compute_overfit_metrics 消费）。

    入参指标 dict 的 key 取自 akquant metrics_df 的指标名（如 total_return_pct /
    max_drawdown_pct / trade_count）。``total_return_pct``/``max_drawdown_pct`` 是
    原始百分数，统一 ÷100 转小数。
    """
    def _pct_to_decimal(v: Any) -> Optional[float]:
        if v is None:
            return None
        try:
            return float(v) / 100.0
        except (TypeError, ValueError):
            return None

    return {
        "best_params": dict(best_params) if isinstance(best_params, dict) else {},
        "in_return": _pct_to_decimal(train_top1_metrics.get("total_return_pct")),
        "out_return": _pct_to_decimal(test_metrics.get("total_return_pct")),
        "in_max_dd": _pct_to_decimal(train_top1_metrics.get("max_drawdown_pct")),
        "out_max_dd": _pct_to_decimal(test_metrics.get("max_drawdown_pct")),
        "in_trades": int(train_top1_metrics.get("trade_count") or 0),
        "out_trades": int(test_metrics.get("trade_count") or 0),
    }


def _extract_metrics_dict(result: Any) -> dict:
    """从 akquant ``BacktestResult`` 抽取需要的指标 dict。

    优先用 ``result.metrics_df``（index=指标名，列=value/Backtest），
    fallback 到 ``result.metrics``（属性访问）。
    """
    out: dict[str, Any] = {}
    if result is None:
        return out

    metrics_df = getattr(result, "metrics_df", None)
    if metrics_df is not None and hasattr(metrics_df, "index") and len(metrics_df) > 0:
        col = "value" if "value" in getattr(metrics_df, "columns", []) else metrics_df.columns[0]
        for name in metrics_df.index:
            try:
                out[str(name)] = metrics_df.at[name, col]
            except Exception:  # noqa: BLE001
                continue
        if out:
            return out

    metrics_obj = getattr(result, "metrics", None)
    if metrics_obj is not None:
        for k in ("total_return_pct", "max_drawdown_pct", "trade_count", "sharpe_ratio"):
            v = getattr(metrics_obj, k, None)
            if v is not None:
                out[k] = v
    return out


def _slice_window_data(
    data: Any,
    symbols: Any,
    start: int,
    length: int,
) -> tuple[Any, Any]:
    """按 bar 数切片训练/测试数据。

    - 单标的 ``DataFrame`` → ``df[start:start+length]``，symbols 原样返回；
    - 多标的 ``Dict[str, DataFrame]`` → 对每个 symbol 切片，symbols 原样返回。
    """
    if isinstance(data, dict):
        sliced: dict[str, Any] = {}
        for sym, df in data.items():
            sliced[str(sym)] = df.iloc[start : start + length]
        return sliced, symbols
    # DataFrame
    return data.iloc[start : start + length], symbols


def _kline_data_slice(
    kline_data_map: dict,
    start: int,
    length: int,
) -> dict:
    """对原始 ``{symbol: DataFrame}`` 按 bar 数切片（保持 dict 结构，供 run_grid_optimize）。"""
    out: dict[str, Any] = {}
    for sym, df in kline_data_map.items():
        out[str(sym)] = df.iloc[start : start + length]
    return out


def run_walk_forward_optimize(
    strategy_config: dict,
    kline_data: dict,
    param_grid: dict,
    train_period: int,
    test_period: int,
    metric: str = "sharpe_ratio",
    window_align: str = "bar_count",
    max_workers: Optional[int] = None,
    constraint: Optional[dict] = None,
    result_filter: Optional[dict] = None,
    compounding: bool = False,
) -> dict:
    """WALK-FORWARD 滚动样本外验证（spec 015 FR-O4）。

    akquant ``run_walk_forward`` 不返回每段最优参数表（只返回拼接的样本外资金曲线），
    无法直接产出过拟合 6 维指标。engine 自写切窗循环：

    1. ``window_align`` 换算 train/test 为 bar 数（year×244 / quarter×61 / bar_count 直用）；
    2. 数据长度 < ``train_bars + test_bars`` → ``OptimizeError(OPTIMIZATION_INSUFFICIENT_DATA)``；
    3. 等距 ``test_bars`` 步进切窗，余数丢弃（while 条件已保证）；
    4. 每段：在训练集跑 ``run_grid_optimize`` 取 Top-1 参数 → 在测试集样本外回测 → 收集段结果；
    5. 段表 → :func:`compute_overfit_metrics` → 6 维指标 + 综合评分。

    一期简化：

    - 非复利（``compounding=False``）：每段重置为 ``initial_cash``，避免复利干扰；
    - ``compounding=True``：承接前一段最终权益（当前一期同样按非复利处理，仅保留参数语义）；
    - 不实现取消信号穿透（O-6 任务做），WF 函数同步执行。

    :return: ``{"status": "SUCCESS", "segments": N,
        "wf_summary": {6维指标 + confidence_score + passed},
        "segment_details": wf_segments}``。
    :raises OptimizeError: 配置非法 / 数据不足 / GRID 寻优失败 / 测试集回测失败。
    """
    import akquant as aq

    if compounding:
        logger.warning(
            "WF compounding=True 当前一期按非复利处理（每段重置 initial_cash），"
            "后续任务再实现权益承接"
        )

    # 1. 解析策略配置（复用 GRID 的解析逻辑）
    try:
        config = StrategyConfigModel.model_validate(strategy_config)
    except Exception as exc:  # noqa: BLE001
        raise OptimizeError(
            f"策略配置解析失败: {exc}",
            error_code="OPTIMIZE_CONFIG_INVALID",
        ) from exc

    tunable_params = _resolve_tunable_params(config)
    validate_param_grid(param_grid, tunable_params)

    # 2. 生成落盘策略类（每段回测复用）
    try:
        strat_class = StrategySourceGenerator().generate(strategy_config, tunable_params)
    except Exception as exc:  # noqa: BLE001
        raise OptimizeError(
            f"策略源码生成失败: {exc}",
            error_code="OPTIMIZE_STRATEGY_GEN_FAILED",
        ) from exc

    if getattr(strat_class, "__module__", "") == "__main__":
        code, msg = ErrorCode.PICKLE_IMPORT_MAIN_FORBIDDEN
        raise OptimizeError(msg, error_code=code)

    # 3. 窗口换算
    train_bars, test_bars = _resolve_window_bars(
        train_period, test_period, window_align
    )
    required_bars = train_bars + test_bars

    # 4. 解析数据 / symbols
    kline_data_map, symbols = _resolve_data_and_symbols(kline_data, config)

    # 取总长度（单标的取自身；多标的取各 symbol 最小长度，避免某标的越界）
    if isinstance(kline_data_map, dict) and kline_data_map:
        total_len = min(len(df) for df in kline_data_map.values())
    else:
        total_len = len(kline_data_map)

    if total_len < required_bars:
        code, msg = ErrorCode.OPTIMIZATION_INSUFFICIENT_DATA
        raise OptimizeError(
            f"{msg}：总长 {total_len} < train({train_bars}) + test({test_bars}) = {required_bars}",
            error_code=code,
        )

    # 5. 构建 backtest kwargs（复用单次回测 A 股规则）
    bt_config = (
        config.backtest_config if config.backtest_config is not None else BacktestConfigModel()
    )
    base_backtest_kwargs = build_backtest_kwargs(bt_config)
    base_backtest_kwargs.pop("db_path", None)
    initial_cash = float(base_backtest_kwargs.get("initial_cash", 100_000.0))

    # 6. 切窗循环
    wf_segments: list[dict] = []
    step = test_bars
    start = 0
    seg_index = 0
    while start + required_bars <= total_len:
        train_slice = _kline_data_slice(kline_data_map, start, train_bars)
        test_slice = _kline_data_slice(
            kline_data_map, start + train_bars, test_bars
        )

        logger.info(
            "WF seg#%d train=[%d:%d] test=[%d:%d]",
            seg_index, start, start + train_bars,
            start + train_bars, start + train_bars + test_bars,
        )

        # 6.1 训练集：run_grid_optimize 取 Top-1（top_n=1）
        grid_result = run_grid_optimize(
            strategy_config=strategy_config,
            kline_data=train_slice,
            param_grid=param_grid,
            sort_by=metric,
            max_workers=max_workers,
            constraint=constraint,
            result_filter=result_filter,
            top_n=1,
        )
        top_n_rows = grid_result.get("top_n") or []
        if not top_n_rows:
            raise OptimizeError(
                f"WF seg#{seg_index} 训练集 GRID 未产出任何结果（候选全被过滤/拒绝）",
                error_code="OPTIMIZE_ENGINE_FAILED",
            )
        top1 = top_n_rows[0]
        best_params = top1.get("params") or {}
        train_top1_metrics = top1.get("metrics") or {}

        # 6.2 测试集：用 best_params 实例化策略类 → aq.run_backtest
        try:
            # 只保留策略类 __init__ 接受的参数（防御性，避免 akquant 严格校验）
            try:
                strat_instance = strat_class(**best_params)
            except TypeError:
                strat_instance = strat_class()

            test_data, test_symbols = _slice_window_data(
                test_slice, symbols, 0, test_bars
            )
            test_kwargs = dict(base_backtest_kwargs)
            test_kwargs["initial_cash"] = initial_cash  # 非复利：每段重置
            test_kwargs["symbols"] = test_symbols

            test_result = aq.run_backtest(
                strategy=strat_instance,
                data=test_data,
                **test_kwargs,
            )
        except OptimizeError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise OptimizeError(
                f"WF seg#{seg_index} 测试集回测失败: {exc}",
                error_code="OPTIMIZE_ENGINE_FAILED",
            ) from exc

        test_metrics = _extract_metrics_dict(test_result)

        # 6.3 组装段结果
        seg_detail = _build_segment_result(
            best_params=best_params,
            train_top1_metrics=train_top1_metrics,
            test_metrics=test_metrics,
        )
        seg_detail["segment_index"] = seg_index
        seg_detail["train_range"] = [start, start + train_bars]
        seg_detail["test_range"] = [start + train_bars, start + train_bars + test_bars]
        wf_segments.append(seg_detail)

        start += step
        seg_index += 1

    # 7. 聚合 6 维指标（grid_scores 一期不传：训练集每段 GRID 内部 Top-1，无统一全量排序）
    wf_summary = compute_overfit_metrics(wf_segments, grid_scores=None)

    return {
        "status": "SUCCESS",
        "segments": len(wf_segments),
        "metric": metric,
        "window_align": window_align,
        "train_bars": train_bars,
        "test_bars": test_bars,
        "wf_summary": wf_summary,
        "segment_details": wf_segments,
    }


__all__ = [
    "OptimizeError",
    "CgroupCpuDetector",
    "serialize_grid_result",
    "validate_param_grid",
    "run_grid_optimize",
    "run_walk_forward_optimize",
]
