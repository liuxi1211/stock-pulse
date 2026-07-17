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
    # signals 与 rebalance 互斥（择时范式 vs 轮动范式，二选一）
    SIGNALS_REBALANCE_EXCLUSIVE = (
        "SIGNALS_REBALANCE_EXCLUSIVE",
        "signals 与 rebalance 不能同时在场，必须二选一（择时范式用 signals，轮动范式用 rebalance）",
    )
    # signals（择时）范式的 universe 规模约束
    SIGNALS_UNIVERSE_NOT_MANUAL = (
        "SIGNALS_UNIVERSE_NOT_MANUAL",
        "signals（择时）范式的选股范围仅支持 manual（手动指定少量标的），"
        "不支持 csi300/csi500/all_a_shares；多标的请改用 rebalance（轮动）范式",
    )
    SIGNALS_UNIVERSE_TOO_LARGE = (
        "SIGNALS_UNIVERSE_TOO_LARGE",
        "signals（择时）范式的选股范围不得超过 {max} 只，当前 {actual} 只；"
        "多标的请改用 rebalance（轮动）范式",
    )
    # signals（择时）范式下 screen_config 字段禁用约束
    SIGNALS_SCREEN_CONFIG_FORBIDDEN = (
        "SIGNALS_SCREEN_CONFIG_FORBIDDEN",
        "signals（择时）范式下，screen_config 仅允许 universe=manual + stocks 标的列表；"
        "不允许填写选股条件(conditions)、排序(ranking)、Top N(top_n)、静态过滤(filters)。"
        "如需截面选股/排序，请改用 rebalance（轮动）范式。",
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

    # ----- 因子打分 × 权重模式 联动约束（spec 011 P0-2）-----
    # factor.method=single 时 score 是因子原始值（量纲不一），与 rebalance.weight_mode=score
    # 的归一化加权语义不兼容，会导致单只标的独占资金。
    FACTOR_SCORE_INCOMPATIBLE = (
        "FACTOR_SCORE_INCOMPATIBLE",
        "factor.method=single 与 rebalance.weight_mode=score 不兼容"
        "（single 的 score 是因子原始值，量纲不一，加权会导致单只标的独占资金）；"
        "请改用 weight_mode=equal 或 factor.method=composite",
    )

    # ----- screen_config 4 层结构（Schema §3.2，缺陷 C 修复）-----
    SCREEN_CONFIG_LAYER_MISSING = (
        "SCREEN_CONFIG_LAYER_MISSING",
        "screen_config 必须包含 universe 层（4 层结构：universe/factor/filter/portfolio）",
    )
    SCREEN_CONFIG_DEPRECATED_STRUCTURE = (
        "SCREEN_CONFIG_DEPRECATED_STRUCTURE",
        "screen_config 旧 5 字段扁平结构已废弃，请迁移到 4 层结构"
        "（universe/factor/filter/portfolio）。映射：conditions→filter.conditions, "
        "ranking→factor, filters→filter, top_n→portfolio.top_n, universe(string)→universe.pool",
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
    INVALID_SELL_METHOD = (
        "INVALID_SELL_METHOD",
        "position_sizing.sell_method 不在白名单",
    )

    # ----- 调仓触发（spec 011 P2-1）-----
    # rebalance.trigger 取值非法（非 first/last/None）。
    INVALID_REBALANCE_TRIGGER = (
        "INVALID_REBALANCE_TRIGGER",
        "rebalance.trigger 必须为 first 或 last（frequency=daily 时可省略）",
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

    # ----- point-in-time 强制过滤（spec 011 P1-1）-----
    # 所有 universe 类型强制 point-in-time 成分股过滤，失败即报错（不再降级）。
    # 这三个码用于 BacktestError 的 message 前缀（runner._extract_error_code 解析）。
    PIT_WATCHER_UNAVAILABLE = (
        "PIT_WATCHER_UNAVAILABLE",
        "watcher 未配置，point-in-time 成分股过滤无法执行",
    )
    PIT_CONSTITUENTS_EMPTY = (
        "PIT_CONSTITUENTS_EMPTY",
        "watcher 查询返回空成分股集合，point-in-time 过滤无法执行",
    )
    PIT_QUERY_FAILED = (
        "PIT_QUERY_FAILED",
        "watcher 查询成分股抛异常，point-in-time 过滤无法执行",
    )

    # ----- 算术运算符 -----
    INVALID_OP = (
        "INVALID_OP",
        "op 不在允许的操作符集合内",
    )

    # ----- 因子滚动窗口聚合 transform（PRD 009 §1 P1-6）-----
    INVALID_TRANSFORM_WINDOW = (
        "INVALID_TRANSFORM_WINDOW",
        "transform.window 必须是 1~60 的整数",
    )
    TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN = (
        "TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN",
        "transform 仅支持 filter.conditions（选股条件），不允许出现在 trading_config（signals/exit.rules 等）",
    )

    # ----- 分批调仓 + 冲击成本（PRD 009 §2 P2-9）-----
    INVALID_EXECUTION_CONFIG = (
        "INVALID_EXECUTION_CONFIG",
        "execution.split_days 必须是 1~5 的整数，impact_cost_bps 必须 ≥ 0",
    )
    EXECUTION_REQUIRES_REBALANCE = (
        "EXECUTION_REQUIRES_REBALANCE",
        "execution 仅轮动范式（rebalance）支持，择时范式不可用",
    )

    # ----- 网格交易（spec 015 FR-G1/G2/G3）-----
    GRID_PARADIGM_EXCLUSIVE = (
        "GRID_PARADIGM_EXCLUSIVE",
        "网格范式（position_sizing.method=grid）与 signals/rebalance 互斥，三者只能选一",
    )
    GRID_EXIT_CONFLICT = (
        "GRID_EXIT_CONFLICT",
        "method=grid 时 exit.bracket/exit.rules 必须为空（grid 自带止损止盈语义，避免双重平仓）",
    )
    GRID_RISK_REQUIRED = (
        "GRID_RISK_REQUIRED",
        "网格必须配置止损（stop_loss_price 或 stop_loss_pct 二选一）、max_holding_bars、max_position_value_pct",
    )
    GRID_PARAM_INVALID = (
        "GRID_PARAM_INVALID",
        "网格参数非法：qty_per_grid 需为 100 的整数倍、max_grids≤20、center>0、step.value>0",
    )
    GRID_INSUFFICIENT_CAPITAL = (
        "GRID_INSUFFICIENT_CAPITAL",
        "资金占用超限：max_grids × qty_per_grid × center 超过 initial_cash × max_position_value_pct",
    )

    # ----- 可调参数（spec 015 FR-O3）-----
    TUNABLE_PARAM_INVALID = (
        "TUNABLE_PARAM_INVALID",
        "tunable_params 配置非法（name 唯一、type 合法、default 在 [min,max] 内、step>0）",
    )
    TUNABLE_PARAM_UNKNOWN = (
        "TUNABLE_PARAM_UNKNOWN",
        "param_grid 含未知参数名（必须是 tunable_params 内声明的 name）",
    )
    TUNABLE_PARAM_MISSING = (
        "TUNABLE_PARAM_MISSING",
        "策略未声明 tunable_params，GRID/WF 寻优无意义（与 param_grid 名字拼错的 "
        "TUNABLE_PARAM_UNKNOWN 区分）",
    )

    # ----- 寻优（spec 015 FR-O1/O4）-----
    PICKLE_IMPORT_MAIN_FORBIDDEN = (
        "PICKLE_IMPORT_MAIN_FORBIDDEN",
        "策略类定义在 __main__ 内无法多进程 pickle，必须落盘到 importable 模块",
    )
    OPTIMIZATION_INSUFFICIENT_DATA = (
        "OPTIMIZATION_INSUFFICIENT_DATA",
        "寻优数据长度不足（需 ≥ train_period + test_period）",
    )
