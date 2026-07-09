"""策略配置业务规则校验器单元测试（spec 004 Task 15 / TR-3.1~TR-3.19）。

覆盖每个 ErrorCode 至少一个正/反用例。校验器非短路——所有错误一次性收集。

用例构造方式：以最小合法 single 信号驱动配置为基底（``_base_config``），
通过 overrides 注入单个违规点，断言 errors 列表含/不含特定 code。
合法基线（TR-3.16）使用 dual_ma 模板的 config 子树。
"""
import copy

from services.strategy.errors import ErrorCode
from services.strategy.models import StrategyConfigModel
from services.strategy.validator import StrategyValidator


# ============================================================
# 辅助
# ============================================================

def _base_config(**overrides) -> dict:
    """最小合法 single 信号驱动配置（MA5 cross_up MA20 买入）。"""
    config = {
        "name": "test",
        "trading_config": {
            "symbols": ["510300.SH"],
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
    """TR-3.2：screen universe=manual 无 stocks → MANUAL_SYMBOL_REQUIRED。"""
    cfg = _base_config(
        screen_config={"universe": "manual"}
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.MANUAL_SYMBOL_REQUIRED[0])


def test_manual_universe_with_stocks_ok():
    """manual 且提供 stocks 不报 MANUAL_SYMBOL_REQUIRED。"""
    cfg = _base_config(
        screen_config={"universe": "manual", "stocks": ["000001.SZ"]}
    )
    errors = _validate(cfg)
    assert all(e.code != ErrorCode.MANUAL_SYMBOL_REQUIRED[0] for e in errors)


# ============================================================
# TR-3.3 RANKING_WEIGHTS_REQUIRED
# ============================================================

def test_ranking_composite_without_weights():
    """TR-3.3：ranking method=composite 无 weights → RANKING_WEIGHTS_REQUIRED。"""
    cfg = _base_config(
        screen_config={"universe": "csi300", "ranking": {"method": "composite"}}
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.RANKING_WEIGHTS_REQUIRED[0])


# ============================================================
# TR-3.4 RANKING_SINGLE_FIELD_REQUIRED
# ============================================================

def test_ranking_single_missing_factor():
    """TR-3.4：ranking method=single 缺 factor → RANKING_SINGLE_FIELD_REQUIRED。"""
    cfg = _base_config(
        screen_config={
            "universe": "csi300",
            "ranking": {"method": "single", "order": "desc"},
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
# TR-3.8 SCREEN_TIME_SERIES_FORBIDDEN
# ============================================================

def test_screen_cross_up_forbidden():
    """TR-3.8：screen_config.conditions 含 cross_up → SCREEN_TIME_SERIES_FORBIDDEN。"""
    cfg = _base_config(
        screen_config={
            "universe": "csi300",
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
        }
    )
    errors = _validate(cfg)
    _assert_has_error(errors, ErrorCode.SCREEN_TIME_SERIES_FORBIDDEN[0])


# ============================================================
# TR-3.9 SCREEN_REF_FORBIDDEN
# ============================================================

def test_screen_ref_forbidden():
    """TR-3.9：screen_config.conditions 含 RefNode → SCREEN_REF_FORBIDDEN。"""
    cfg = _base_config(
        screen_config={
            "universe": "csi300",
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
    """基本面因子出现在 screen_config 不报 FUNDAMENTAL_FACTOR_IN_TRADING。"""
    cfg = _base_config(
        screen_config={
            "universe": "csi300",
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
# TR-3.16 合法配置无错误（dual_ma 模板 config 子树）
# ============================================================

def test_valid_dual_ma_template_no_errors():
    """TR-3.16：合法配置（dual_ma 模板 config 子树）→ errors 为空。"""
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
        / "dual_ma.json"
    )
    data = json.loads(p.read_text(encoding="utf-8"))
    allowed = {
        "strategy_id",
        "name",
        "description",
        "scope",
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
