"""策略配置业务规则校验器单元测试（spec 004 Task 15 / TR-3.1~TR-3.19）。

覆盖每个 ErrorCode 至少一个正/反用例。校验器非短路——所有错误一次性收集。

用例构造方式：以最小合法 single 信号驱动配置为基底（``_base_config``），
通过 overrides 注入单个违规点，断言 errors 列表含/不含特定 code。
合法基线（TR-3.16）使用 dual_ma 模板的 config 子树。

spec 010 Task 14：screen_config 已从旧 5 字段扁平结构重构为
universe/factor/filter/portfolio 4 层对象结构。本测试文件全部用例
已迁移到 4 层构造方式。
"""
import copy

from services.strategy.errors import ErrorCode
from services.strategy.models import StrategyConfigModel
from services.strategy.validator import StrategyValidator


# ============================================================
# 辅助
# ============================================================

def _manual_universe(stocks=None):
    """构造 4 层 universe 对象（manual 池）。

    spec 010 后 screen_config.universe 是对象，不再是字符串。
    """
    return {"pool": "manual", "point_in_time": None, "stocks": stocks}


def _index_universe(pool="csi300"):
    """构造 4 层 universe 对象（指数池 csi300/csi500）。"""
    return {"pool": pool, "point_in_time": None, "stocks": None}


def _base_config(**overrides) -> dict:
    """最小合法 single 信号驱动配置（MA5 cross_up MA20 买入）。

    spec 009 后：trading_config.symbols 已移除（回测标的由 screen_config
    解析），且 signals 范式要求 screen_config.universe.pool=manual +
    stocks ≤ 10。
    spec 010 后：screen_config 采用 4 层对象结构（universe/factor/filter/portfolio）。
    """
    config = {
        "name": "test",
        "trading_config": {
            "signals": {
                "buy": {
                    "operator": "AND",
                    "conditions": [
                        {
                            "type": "compare",
                            "left": {"factor": "MA", "params": {"timeperiod": 5}},
                            "comparator": "cross_up",
                            "right": {"factor": "MA", "params": {"timeperiod": 20}},
                        }
                    ],
                }
            },
            "position_sizing": {"method": "order_target_percent", "target": 0.95},
        },
        "screen_config": {
            "universe": _manual_universe(["510300.SH"]),
        },
        "backtest_config": {"initial_cash": 100000},
    }
    config.update(overrides)
    return config


def _validate(config_dict: dict):
    """解析 + 业务校验，返回 errors 列表。"""
    cfg = StrategyConfigModel.model_validate(config_dict)
    return StrategyValidator().validate(cfg)


def _assert_has_error(errors, code: str) -> None:
    assert any(e.code == code for e in errors), (
        f"期望错误码 {code},实际 errors: {[(e.code, e.path) for e in errors]}"
    )


def _assert_no_error(config_dict: dict) -> None:
    errors = _validate(config_dict)
    assert errors == [], f"期望无错误,实际: {[(e.code, e.path) for e in errors]}"


def _deep_set(d: dict, path: str, value) -> None:
    """点号路径写值；path 形如 'a.b.c'。"""
    keys = path.split(".")
    cur = d
    for k in keys[:-1]:
        cur = cur[k]
    cur[keys[-1]] = value


def _deep_del(d: dict, path: str) -> None:
    """点号路径删键；path 形如 'a.b.c'。"""
    keys = path.split(".")
    cur = d
    for k in keys[:-1]:
        cur = cur[k]
    del cur[keys[-1]]


# ============================================================
# TR-3.1 MISSING_SIGNALS_OR_REBALANCE
# ============================================================

def test_missing_signals_and_rebalance():
    """TR-3.1：trading_config 缺 signals 和 rebalance → MISSING_SIGNALS_OR_REBALANCE。"""
    cfg = _base_config()
    del cfg["trading_config"]["signals"]
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.MISSING_SIGNALS_OR_REBALANCE[0])


def test_signals_present_is_ok():
    """基线：仅 signals 在场不报 MISSING_SIGNALS_OR_REBALANCE。"""
    errors = _validate(_base_config())
    assert all(
        e.code != ErrorCode.MISSING_SIGNALS_OR_REBALANCE[0] for e in errors
    )


# ============================================================
# TR-3.2 MANUAL_SYMBOL_REQUIRED
# ============================================================

def test_manual_universe_without_stocks():
    """TR-3.2：screen universe.pool=manual 无 stocks → MANUAL_SYMBOL_REQUIRED。"""
    cfg = _base_config(
        screen_config={"universe": _manual_universe(None)}
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.MANUAL_SYMBOL_REQUIRED[0])


def test_manual_universe_with_stocks_ok():
    """manual 且提供 stocks 不报 MANUAL_SYMBOL_REQUIRED。"""
    cfg = _base_config(
        screen_config={"universe": _manual_universe(["000001.SZ"])}
    )
    errors = _validate(cfg)
    assert all(e.code != ErrorCode.MANUAL_SYMBOL_REQUIRED[0] for e in errors)


# ============================================================
# TR-3.3 RANKING_WEIGHTS_REQUIRED（4 层：factor 层）
# ============================================================

def test_ranking_composite_without_weights():
    """TR-3.3：factor method=composite 无 weights → RANKING_WEIGHTS_REQUIRED。"""
    cfg = _base_config(
        screen_config={
            "universe": _index_universe("csi300"),
            "factor": {"method": "composite"},
        }
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.RANKING_WEIGHTS_REQUIRED[0])


# ============================================================
# TR-3.4 RANKING_SINGLE_FIELD_REQUIRED（4 层：factor 层）
# ============================================================

def test_ranking_single_missing_factor():
    """TR-3.4：factor method=single 缺 factor → RANKING_SINGLE_FIELD_REQUIRED。"""
    cfg = _base_config(
        screen_config={
            "universe": _index_universe("csi300"),
            "factor": {"method": "single", "order": "desc"},
        }
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.RANKING_SINGLE_FIELD_REQUIRED[0])


# ============================================================
# TR-3.5 INVALID_POSITION_METHOD
# ============================================================

def test_invalid_position_method():
    """TR-3.5：position_sizing.method='invalid_method' → INVALID_POSITION_METHOD。"""
    cfg = _base_config()
    cfg["trading_config"]["position_sizing"] = {"method": "invalid_method"}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.INVALID_POSITION_METHOD[0])


# ============================================================
# TR-3.6 POSITION_TARGET_REQUIRED
# ============================================================

def test_position_buy_without_target():
    """TR-3.6：position_sizing method=buy 无 target → POSITION_TARGET_REQUIRED。"""
    cfg = _base_config()
    cfg["trading_config"]["position_sizing"] = {"method": "buy"}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.POSITION_TARGET_REQUIRED[0])


def test_position_buy_all_without_target_ok():
    """buy_all/close_position 不需要 target。"""
    cfg = _base_config()
    cfg["trading_config"]["position_sizing"] = {"method": "buy_all"}
    errors = _validate(cfg)
    assert all(e.code != ErrorCode.POSITION_TARGET_REQUIRED[0] for e in errors)


# ============================================================
# TR-3.7 ATR_MULTIPLIER_REQUIRED
# ============================================================

def test_atr_stop_without_multiplier():
    """TR-3.7：exit.bracket.use_atr_stop=true 无 atr_multiplier → ATR_MULTIPLIER_REQUIRED。"""
    cfg = _base_config()
    cfg["trading_config"]["exit"] = {
        "bracket": {"use_atr_stop": True, "atr_period": 14}
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.ATR_MULTIPLIER_REQUIRED[0])


def test_atr_stop_with_multiplier_ok():
    """use_atr_stop=true 且提供 atr_multiplier 不报错。"""
    cfg = _base_config()
    cfg["trading_config"]["exit"] = {
        "bracket": {"use_atr_stop": True, "atr_multiplier": 2.0}
    }
    errors = _validate(cfg)
    assert all(e.code != ErrorCode.ATR_MULTIPLIER_REQUIRED[0] for e in errors)


# ============================================================
# TR-3.8 SCREEN_TIME_SERIES_FORBIDDEN（4 层：filter.conditions）
# ============================================================

def test_screen_cross_up_forbidden():
    """TR-3.8：filter.conditions 含 cross_up → SCREEN_TIME_SERIES_FORBIDDEN。"""
    cfg = _base_config(
        screen_config={
            "universe": _index_universe("csi300"),
            "filter": {
                "conditions": {
                    "operator": "AND",
                    "conditions": [
                        {
                            "type": "compare",
                            "left": {"factor": "PE_TTM"},
                            "comparator": "cross_up",
                            "right": {"value": 10},
                        }
                    ],
                },
            },
        }
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SCREEN_TIME_SERIES_FORBIDDEN[0])


# ============================================================
# TR-3.9 SCREEN_REF_FORBIDDEN（4 层：filter.conditions）
# ============================================================

def test_screen_ref_forbidden():
    """TR-3.9：filter.conditions 含 RefNode → SCREEN_REF_FORBIDDEN。"""
    cfg = _base_config(
        screen_config={
            "universe": _index_universe("csi300"),
            "filter": {
                "conditions": {
                    "operator": "AND",
                    "conditions": [
                        {
                            "type": "compare",
                            "left": {"factor": "PE_TTM"},
                            "comparator": ">",
                            "right": {"ref": "entry_price"},
                        }
                    ],
                },
            },
        }
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SCREEN_REF_FORBIDDEN[0])


# ============================================================
# TR-3.10 CROSS_REQUIRES_FACTOR_NODES
# ============================================================

def test_cross_up_left_is_value_node():
    """TR-3.10：cross_up 左为 ValueNode → CROSS_REQUIRES_FACTOR_NODES。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"value": 5},
        "comparator": "cross_up",
        "right": {"factor": "MA", "params": {"timeperiod": 20}},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.CROSS_REQUIRES_FACTOR_NODES[0])


# ============================================================
# TR-3.11 UNKNOWN_FACTOR
# ============================================================

def test_unknown_factor():
    """TR-3.11：factorKey='UNKNOWN_X' → UNKNOWN_FACTOR。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "UNKNOWN_X"},
        "comparator": ">",
        "right": {"value": 0},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.UNKNOWN_FACTOR[0])


# ============================================================
# TR-3.12 MULTI_OUTPUT_REQUIRES_INDEX
# ============================================================

def test_macd_without_output_index():
    """TR-3.12：MACD 无 output_index → MULTI_OUTPUT_REQUIRES_INDEX。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "MACD"},
        "comparator": ">",
        "right": {"value": 0},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.MULTI_OUTPUT_REQUIRES_INDEX[0])


# ============================================================
# TR-3.13 FUNDAMENTAL_FACTOR_IN_TRADING
# ============================================================

def test_fundamental_factor_in_trading():
    """TR-3.13：signals.buy 引用 PE_TTM（基本面）→ FUNDAMENTAL_FACTOR_IN_TRADING。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "PE_TTM"},
        "comparator": "<",
        "right": {"value": 20},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.FUNDAMENTAL_FACTOR_IN_TRADING[0])


def test_fundamental_factor_in_screen_ok():
    """基本面因子出现在 filter.conditions 不报 FUNDAMENTAL_FACTOR_IN_TRADING。"""
    cfg = _base_config(
        screen_config={
            "universe": _index_universe("csi300"),
            "filter": {
                "conditions": {
                    "operator": "AND",
                    "conditions": [
                        {
                            "type": "compare",
                            "left": {"factor": "PE_TTM"},
                            "comparator": "<",
                            "right": {"value": 20},
                        }
                    ],
                },
            },
        }
    )
    errors = _validate(cfg)
    assert all(
        e.code != ErrorCode.FUNDAMENTAL_FACTOR_IN_TRADING[0] for e in errors
    )


# ============================================================
# TR-3.14 UNKNOWN_REF_KEY
# ============================================================

def test_unknown_ref_key():
    """TR-3.14：ref.key='highest_since_entry'（不在白名单）→ UNKNOWN_REF_KEY。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "CLOSE"},
        "comparator": ">",
        "right": {"ref": "highest_since_entry"},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.UNKNOWN_REF_KEY[0])


def test_allowed_ref_key_ok():
    """ref.key 在白名单（entry_price）不报错。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "CLOSE"},
        "comparator": ">",
        "right": {"ref": "entry_price"},
    }
    errors = _validate(cfg)
    assert all(e.code != ErrorCode.UNKNOWN_REF_KEY[0] for e in errors)


# ============================================================
# TR-3.15 INJECTION_FORBIDDEN
# ============================================================

def test_injection_in_name():
    """TR-3.15：name 含 '__class__' → INJECTION_FORBIDDEN。"""
    cfg = _base_config()
    cfg["name"] = "evil__class__payload"
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.INJECTION_FORBIDDEN[0])


# ============================================================
# TR-3.16 合法配置无错误（macd_short 模板已迁移为 4 层结构）
# ============================================================

def test_valid_dual_ma_template_no_errors():
    """TR-3.16：合法配置（4 层结构）→ errors 为空。

    spec 010 后 watcher 模板迁移分批进行；此处改用 macd_short.json
    （已是 4 层 universe 对象结构）作为合法基线。dual_ma.json 尚未
    迁移时会被 Pydantic 拒绝（旧 5 字段结构），故不在此处断言。
    """
    import json
    from pathlib import Path

    p = (
        Path(__file__).resolve().parents[4]
        / "stock-watcher"
        / "src"
        / "main"
        / "resources"
        / "strategies"
        / "templates"
        / "macd_short.json"
    )
    data = json.loads(p.read_text(encoding="utf-8"))
    allowed = {
        "name",
        "description",
        "screen_config",
        "trading_config",
        "backtest_config",
    }
    cfg_dict = {k: v for k, v in data.items() if k in allowed}
    _assert_no_error(cfg_dict)


# ============================================================
# TR-3.17 多错误非短路
# ============================================================

def test_multiple_errors_non_short_circuit():
    """TR-3.17：同时存在 3 个错误 → errors 长度≥3（非短路）。"""
    cfg = _base_config()
    # 错误1：缺 signals & rebalance
    del cfg["trading_config"]["signals"]
    # 错误2：非法 position_sizing.method
    cfg["trading_config"]["position_sizing"] = {"method": "invalid_x"}
    # 错误3：name 注入
    cfg["name"] = "x__class__y"
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.MISSING_SIGNALS_OR_REBALANCE[0] in codes
    assert ErrorCode.INVALID_POSITION_METHOD[0] in codes
    assert ErrorCode.INJECTION_FORBIDDEN[0] in codes
    assert len(errors) >= 3


# ============================================================
# TR-3.18 MULTI_OUTPUT_INDEX_OUT_OF_RANGE
# ============================================================

def test_macd_output_index_out_of_range():
    """TR-3.18：MACD output_index=5（超范围 0~2）→ MULTI_OUTPUT_INDEX_OUT_OF_RANGE。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "MACD", "output_index": 5},
        "comparator": ">",
        "right": {"value": 0},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.MULTI_OUTPUT_INDEX_OUT_OF_RANGE[0])


def test_macd_output_index_in_range_ok():
    """MACD output_index=2（hist，合法）不报错。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "MACD", "output_index": 2},
        "comparator": ">",
        "right": {"value": 0},
    }
    errors = _validate(cfg)
    assert all(
        e.code != ErrorCode.MULTI_OUTPUT_INDEX_OUT_OF_RANGE[0]
        and e.code != ErrorCode.MULTI_OUTPUT_REQUIRES_INDEX[0]
        for e in errors
    )


# ============================================================
# TR-3.19 INVALID_COMPARATOR
# ============================================================

def test_invalid_comparator():
    """TR-3.19：comparator='invalid_op' → INVALID_COMPARATOR。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {"factor": "CLOSE"},
        "comparator": "invalid_op",
        "right": {"value": 10},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.INVALID_COMPARATOR[0])


# ============================================================
# spec 009 Task 20：signals/rebalance 范式互斥 + universe 规模约束
# ============================================================

def _signals_buy_config(**overrides) -> dict:
    """含 signals.buy 的配置基底（供范式互斥/universe 用例复用）。

    与 ``_base_config`` 同源，仅显式补上合法的 manual 选股池以满足
    signals 范式 universe 规模约束（避免无关错误干扰断言）。
    """
    cfg = _base_config()
    cfg.setdefault("screen_config", {})
    cfg["screen_config"].update({"universe": _manual_universe(["000001.SZ"])})
    cfg.update(overrides)
    return cfg


def _rebalance_config() -> dict:
    """合法的最小 rebalance 子树（轮动范式）。"""
    return {"frequency": "weekly"}


def test_signals_and_rebalance_both_present_rejected():
    """spec 009 Task 20.1：trading_config 同时含 signals 与 rebalance →
    SIGNALS_REBALANCE_EXCLUSIVE。"""
    cfg = _signals_buy_config()
    cfg["trading_config"]["rebalance"] = _rebalance_config()
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_REBALANCE_EXCLUSIVE[0])


def test_signals_universe_csi300_rejected():
    """spec 009 Task 20.2：signals 范式 + universe.pool='csi300' →
    SIGNALS_UNIVERSE_NOT_MANUAL。"""
    cfg = _base_config()
    cfg["screen_config"] = {"universe": _index_universe("csi300")}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_UNIVERSE_NOT_MANUAL[0])


def test_signals_universe_manual_over_limit_rejected():
    """spec 009 Task 20.3：signals 范式 + manual + 11 只标的 →
    SIGNALS_UNIVERSE_TOO_LARGE（SIGNALS_MAX_UNIVERSE_SIZE=10）。"""
    cfg = _base_config()
    stocks = [f"{i:06d}.SZ" for i in range(11)]  # 11 只，超出上限
    cfg["screen_config"] = {"universe": _manual_universe(stocks)}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_UNIVERSE_TOO_LARGE[0])


def test_signals_universe_manual_within_limit_ok():
    """spec 009 Task 20.4：signals 范式 + manual + 10 只标的（恰好等于上限）→
    不触发 SIGNALS_UNIVERSE_* / SIGNALS_REBALANCE_* 任一错误。"""
    cfg = _base_config()
    stocks = [f"{i:06d}.SZ" for i in range(10)]  # 10 只，恰好不超
    cfg["screen_config"] = {"universe": _manual_universe(stocks)}
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.SIGNALS_UNIVERSE_NOT_MANUAL[0] not in codes
    assert ErrorCode.SIGNALS_UNIVERSE_TOO_LARGE[0] not in codes
    assert ErrorCode.SIGNALS_REBALANCE_EXCLUSIVE[0] not in codes


# ============================================================
# signals 范式下 screen_config 字段禁用约束（4 层：factor/filter/portfolio）
# ============================================================

def test_signals_with_screen_conditions_rejected():
    """signals 范式 + filter.conditions 非空 →
    SIGNALS_SCREEN_CONFIG_FORBIDDEN（选股条件在择时范式下不生效，禁止填写）。"""
    cfg = _base_config()
    cfg["screen_config"]["filter"] = {
        "conditions": {
            "operator": "AND",
            "conditions": [
                {"type": "compare", "left": {"factor": "PE_TTM"},
                 "comparator": "<", "right": {"value": 30}}
            ],
        }
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0])


def test_signals_with_screen_ranking_rejected():
    """signals 范式 + factor 层非空 →
    SIGNALS_SCREEN_CONFIG_FORBIDDEN。"""
    cfg = _base_config()
    cfg["screen_config"]["factor"] = {
        "method": "single", "factor": "RSI", "order": "desc"
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0])


def test_signals_with_screen_top_n_rejected():
    """signals 范式 + portfolio.top_n 非空 →
    SIGNALS_SCREEN_CONFIG_FORBIDDEN。"""
    cfg = _base_config()
    cfg["screen_config"]["portfolio"] = {"top_n": 5}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0])


def test_signals_with_screen_filters_rejected():
    """signals 范式 + filter 层（静态过滤字段）非空 →
    SIGNALS_SCREEN_CONFIG_FORBIDDEN。

    注：filter 层只要有任何字段（含 exclude_st 等静态过滤项）即视为非空。
    """
    cfg = _base_config()
    cfg["screen_config"]["filter"] = {"exclude_st": True}
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0])


def test_signals_with_only_universe_stocks_passes():
    """signals 范式 + screen_config 仅 universe（含 stocks）→
    不触发 SIGNALS_SCREEN_CONFIG_FORBIDDEN（净化后的合法形态）。"""
    cfg = _base_config()
    cfg["screen_config"] = {"universe": _manual_universe(["510300.SH"])}
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0] not in codes


def test_rebalance_with_screen_fields_passes():
    """rebalance 范式 + screen_config 全 4 层（factor/filter/portfolio）→
    不触发 SIGNALS_SCREEN_CONFIG_FORBIDDEN（轮动范式本就需要这些字段）。"""
    cfg = _base_config()
    del cfg["trading_config"]["signals"]
    del cfg["trading_config"]["position_sizing"]
    cfg["trading_config"]["rebalance"] = {"frequency": "weekly"}
    cfg["screen_config"] = {
        "universe": _index_universe("csi300"),
        "portfolio": {"top_n": 10},
        "filter": {
            "conditions": {
                "operator": "AND",
                "conditions": [
                    {"type": "compare", "left": {"factor": "PE_TTM"},
                     "comparator": "<", "right": {"value": 30}}
                ],
            },
        },
        "factor": {"method": "single", "factor": "RSI", "order": "desc"},
    }
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN[0] not in codes


# ============================================================
# spec 010 Task 14：4 层结构校验。
# SCREEN_CONFIG_LAYER_MISSING / SCREEN_CONFIG_DEPRECATED_STRUCTURE 现由
# StrategyValidator.validate_screen_structure 在 Pydantic 解析前识别并返回（友好迁移提示）；
# 直接调 model_validate 仍会抛 ValidationError（Pydantic 兜底，下方两例验证该兜底）。
# ============================================================

def test_screen_config_missing_universe_layer_rejected_by_pydantic():
    """spec 010：screen_config 缺 universe 层 → Pydantic ValidationError。

    4 层结构下 universe 是必填字段；缺失会被 Pydantic 在解析阶段拒绝，
    不会进入 validator 业务校验层（故不产生 SCREEN_CONFIG_LAYER_MISSING）。
    """
    import pytest
    from pydantic import ValidationError

    cfg = _base_config()
    # 构造一个缺 universe 的 screen_config（仅 factor 层）
    cfg["screen_config"] = {"factor": {"method": "disabled"}}
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate(cfg)


def test_screen_config_old_flat_structure_rejected_by_pydantic():
    """spec 010：旧 5 字段扁平结构（顶层 conditions/ranking/top_n）→
    Pydantic ValidationError（extra="forbid" 拒绝未知字段）。

    旧结构迁移到 validator 层的 SCREEN_CONFIG_DEPRECATED_STRUCTURE 错误码
    需要 Pydantic 放宽 extra 限制才能触达；当前 extra="forbid" 下旧字段
    在解析阶段即被拒绝，故用例改为断言 ValidationError。
    """
    import pytest
    from pydantic import ValidationError

    cfg = _base_config()
    # 旧结构：顶层 ranking / top_n（已被 4 层结构取代）
    cfg["screen_config"] = {
        "universe": _index_universe("csi300"),
        "ranking": {"method": "single", "factor": "RSI", "order": "desc"},
        "top_n": 5,
    }
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate(cfg)


# ============================================================
# spec 010 Task 14：validate_screen_structure 预检 → 友好错误码
# （/strategies/validate 入口在 Pydantic 前调用，返回专用码而非通用 ValidationError）
# ============================================================

def test_screen_structure_legacy_string_universe():
    """旧扁平结构（universe 为字符串）→ SCREEN_CONFIG_DEPRECATED_STRUCTURE。"""
    cfg = {"screen_config": {"universe": "csi300", "top_n": 5}}
    errors = StrategyValidator().validate_screen_structure(cfg)
    assert len(errors) == 1
    assert errors[0].code == ErrorCode.SCREEN_CONFIG_DEPRECATED_STRUCTURE[0]


def test_screen_structure_legacy_top_level_keys():
    """旧扁平结构（顶层 conditions/ranking/top_n/filters 任一）→ SCREEN_CONFIG_DEPRECATED_STRUCTURE。"""
    cfg = {
        "screen_config": {
            "universe": _index_universe("csi300"),
            "ranking": {"method": "single", "factor": "RSI", "order": "desc"},
        }
    }
    errors = StrategyValidator().validate_screen_structure(cfg)
    assert len(errors) == 1
    assert errors[0].code == ErrorCode.SCREEN_CONFIG_DEPRECATED_STRUCTURE[0]


def test_screen_structure_missing_universe_layer():
    """缺失 universe 必需层 → SCREEN_CONFIG_LAYER_MISSING。"""
    cfg = {"screen_config": {"factor": {"method": "disabled"}}}
    errors = StrategyValidator().validate_screen_structure(cfg)
    assert len(errors) == 1
    assert errors[0].code == ErrorCode.SCREEN_CONFIG_LAYER_MISSING[0]


def test_screen_structure_valid_4_layer_passes():
    """合法 4 层结构 → 无结构错误（交由后续 Pydantic + 业务校验）。"""
    cfg = {"screen_config": {"universe": _index_universe("csi300"), "portfolio": {"top_n": 10}}}
    assert StrategyValidator().validate_screen_structure(cfg) == []


def test_screen_structure_no_screen_config_passes():
    """无 screen_config（缺失 / 非对象）→ 不报结构错误（由其它校验处理）。"""
    assert StrategyValidator().validate_screen_structure({}) == []
    assert StrategyValidator().validate_screen_structure({"screen_config": None}) == []


# ============================================================
# spec 011 P2-1：rebalance.trigger 校验 + day_of_period 兼容映射
# ============================================================

def _rebalance_only_config(**rb_overrides) -> dict:
    """仅 rebalance 范式（无 signals）的最小配置，screen_config 为 csi300 4 层。"""
    cfg = _base_config()
    del cfg["trading_config"]["signals"]
    del cfg["trading_config"]["position_sizing"]
    rb = {"frequency": "monthly"}
    rb.update(rb_overrides)
    cfg["trading_config"]["rebalance"] = rb
    cfg["screen_config"] = {
        "universe": _index_universe("csi300"),
        "portfolio": {"top_n": 10},
        "factor": {"method": "single", "factor": "RSI", "order": "desc"},
    }
    return cfg


def test_rebalance_trigger_first_last_passes():
    """trigger=first / last 均合法，不报 INVALID_REBALANCE_TRIGGER。"""
    for trig in ("first", "last"):
        cfg = _rebalance_only_config(trigger=trig)
        errors = _validate(cfg)
        codes = [e.code for e in errors]
        assert ErrorCode.INVALID_REBALANCE_TRIGGER[0] not in codes, (
            f"trigger={trig} 应合法，但报错: {[(e.code, e.path) for e in errors]}"
        )


def test_rebalance_trigger_invalid_rejected():
    """trigger 取非法值 → Pydantic Literal 在解析阶段即拒绝（ValidationError）。"""
    import pytest
    from pydantic import ValidationError

    cfg = _rebalance_only_config(trigger="middle")
    with pytest.raises(ValidationError):
        StrategyConfigModel.model_validate(cfg)


def test_rebalance_daily_ignores_trigger():
    """frequency=daily 时 trigger 可任意省略/取值，不报错（daily 恒触发）。"""
    cfg = _rebalance_only_config(frequency="daily")
    # 删 trigger 键也合法
    cfg["trading_config"]["rebalance"] = {"frequency": "daily"}
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.INVALID_REBALANCE_TRIGGER[0] not in codes


def test_rebalance_day_of_period_deprecation_warning(caplog):
    """旧 JSON 带 day_of_period → validator 打 deprecation warning（不报错）。"""
    import logging

    cfg = _rebalance_only_config(day_of_period=5)
    with caplog.at_level(logging.WARNING, logger="services.strategy.validator"):
        errors = _validate(cfg)
    codes = [e.code for e in errors]
    # 不应产生 INVALID_REBALANCE_TRIGGER 错误
    assert ErrorCode.INVALID_REBALANCE_TRIGGER[0] not in codes
    # 应打 deprecation warning
    assert any("day_of_period" in rec.message for rec in caplog.records), (
        f"期望 day_of_period deprecation warning，实际 logs: {[r.message for r in caplog.records]}"
    )


# ============================================================
# PRD 009 §1 P1-6：transform type/window 校验
# ============================================================

def _rotation_screen_with_transform(transform: dict) -> dict:
    """构造轮动范式 + filter.conditions 含 transform 的 screen_config 子树。"""
    return {
        "operator": "AND",
        "conditions": [
            {
                "type": "compare",
                "left": {"factor": "PE_TTM", "transform": transform},
                "comparator": "<",
                "right": {"value": 30},
            }
        ],
    }


def test_transform_window_out_of_range_rejected():
    """filter.conditions 中 FactorNode.transform.window > 60 → INVALID_TRANSFORM_WINDOW。"""
    cfg = _rebalance_only_config()
    cfg["screen_config"]["filter"] = {
        "conditions": _rotation_screen_with_transform({"type": "ma", "window": 999})
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.INVALID_TRANSFORM_WINDOW[0])


def test_transform_valid_no_errors():
    """filter.conditions 中合法 transform 不报 transform 相关错误。"""
    cfg = _rebalance_only_config()
    cfg["screen_config"]["filter"] = {
        "conditions": _rotation_screen_with_transform({"type": "ma", "window": 20})
    }
    errors = _validate(cfg)
    codes = [e.code for e in errors]
    assert ErrorCode.TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN[0] not in codes
    assert ErrorCode.INVALID_TRANSFORM_WINDOW[0] not in codes


def test_transform_in_trading_config_rejected():
    """PRD 009 §1 P1-6：transform 出现在 trading_config（signals.buy）→
    TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN。"""
    cfg = _base_config()
    cfg["trading_config"]["signals"]["buy"]["conditions"][0] = {
        "type": "compare",
        "left": {
            "factor": "MA",
            "params": {"timeperiod": 5},
            "transform": {"type": "ma", "window": 5},
        },
        "comparator": "cross_up",
        "right": {"factor": "MA", "params": {"timeperiod": 20}},
    }
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN[0])
