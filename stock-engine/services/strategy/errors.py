"""策略配置校验错误码与错误对象（统一策略配置 Schema §7）。

错误码统一为 ``(code, message)`` 元组：``code`` 是稳定的字符串标识（前端可据此
做 i18n / 聚合），``message`` 是中文默认提示。校验器返回 ``StrategyValidationError``
列表（含 JSON path），由调用方聚合成 422 响应。

设计要点：
- 错误码集中在 ``ErrorCode`` 类作为常量容器，避免散落字符串字面量。
- ``StrategyValidationError`` 同时提供 dataclass（校验逻辑内部使用）与
  pydantic 模型版本（``StrategyValidationErrorModel``，用于 HTTP 响应序列化）。
- 不与 FastAPI 的 HTTPException 耦合：本层只产出错误描述，HTTP 层负责包装。
"""
from dataclasses import dataclass


@dataclass
class StrategyValidationError:
    """单条校验错误（内部使用，含 JSON path 定位）。

    :param path: 错误字段的 JSON path，如 ``trading_config.position_sizing.method``。
    :param code: 稳定错误码（见 :class:`ErrorCode` 的元组首元素）。
    :param message: 中文默认提示信息。
    """

    path: str
    code: str
    message: str


class ErrorCode:
    """策略配置校验错误码常量集合。

    每个属性是 ``(code, message)`` 二元组：``code`` 用于程序判别（稳定不变），
    ``message`` 是给用户的默认中文提示（可被前端覆盖做 i18n）。
    """

    # ----- 结构约束（Schema §7.1）-----
    MISSING_SIGNALS_OR_REBALANCE = (
        "MISSING_SIGNALS_OR_REBALANCE",
        "trading_config 必须包含 signals 或 rebalance 至少一个",
    )
    MANUAL_SYMBOL_REQUIRED = (
        "MANUAL_SYMBOL_REQUIRED",
        "universe=manual 时必须提供 stocks",
    )
    RANKING_WEIGHTS_REQUIRED = (
        "RANKING_WEIGHTS_REQUIRED",
        "ranking method=composite 时必须提供 weights",
    )
    RANKING_SINGLE_FIELD_REQUIRED = (
        "RANKING_SINGLE_FIELD_REQUIRED",
        "ranking method=single 时必须提供 factor 和 order",
    )

    # ----- 仓位管理（Schema §3.3.2）-----
    INVALID_POSITION_METHOD = (
        "INVALID_POSITION_METHOD",
        "position_sizing.method 不在白名单",
    )
    POSITION_TARGET_REQUIRED = (
        "POSITION_TARGET_REQUIRED",
        "position_sizing 当前 method 需要 target 字段",
    )

    # ----- 出场规则（Schema §3.3.3）-----
    ATR_MULTIPLIER_REQUIRED = (
        "ATR_MULTIPLIER_REQUIRED",
        "use_atr_stop=true 时必须提供 atr_multiplier",
    )

    # ----- 条件模型约束（Schema §7.2）-----
    SCREEN_TIME_SERIES_FORBIDDEN = (
        "SCREEN_TIME_SERIES_FORBIDDEN",
        "screen_config 内禁止使用 cross_up/cross_down 时序比较",
    )
    SCREEN_REF_FORBIDDEN = (
        "SCREEN_REF_FORBIDDEN",
        "screen_config 内禁止使用 ref 引用",
    )
    CROSS_REQUIRES_FACTOR_NODES = (
        "CROSS_REQUIRES_FACTOR_NODES",
        "cross_up/cross_down 左右必须均为 factor 节点",
    )
    UNKNOWN_REF_KEY = (
        "UNKNOWN_REF_KEY",
        "ref.key 不在允许列表",
    )
    INVALID_COMPARATOR = (
        "INVALID_COMPARATOR",
        "comparator 不在当前上下文允许的集合内",
    )

    # ----- 因子节点约束（Schema §7.3）-----
    UNKNOWN_FACTOR = (
        "UNKNOWN_FACTOR",
        "未知的 factorKey",
    )
    MULTI_OUTPUT_REQUIRES_INDEX = (
        "MULTI_OUTPUT_REQUIRES_INDEX",
        "多输出因子必须提供 output_index",
    )
    MULTI_OUTPUT_INDEX_OUT_OF_RANGE = (
        "MULTI_OUTPUT_INDEX_OUT_OF_RANGE",
        "output_index 超出范围",
    )
    FUNDAMENTAL_FACTOR_IN_TRADING = (
        "FUNDAMENTAL_FACTOR_IN_TRADING",
        "基本面因子不允许出现在 trading_config",
    )

    # ----- 安全约束 -----
    INJECTION_FORBIDDEN = (
        "INJECTION_FORBIDDEN",
        "字段含危险字符串，疑似注入",
    )

    # ----- 算术运算符 -----
    INVALID_OP = (
        "INVALID_OP",
        "op 不在允许的操作符集合内",
    )
