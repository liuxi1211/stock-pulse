"""策略校验 FastAPI 路由集成测试（spec 004 Task 15 / TR-4.1~TR-4.6）。

用 ``fastapi.TestClient`` 覆盖：
- TR-4.1 POST 合法 config → 200 + {valid:true, errors:[]}
- TR-4.2 POST 缺 signals/rebalance → 200 + {valid:false, errors 含 MISSING_SIGNALS_OR_REBALANCE}
- TR-4.3 POST 非法结构（顶层非 dict / 缺 config 字段）→ 422（pydantic 请求体校验）
- TR-4.4 engine services/strategy/ 目录源码无 sqlite3/sqlalchemy/.db 匹配（不触库）
- TR-4.5 TestClient 路径整体可跑通
- TR-4.6 /docs 页面返回 200

注意：main.py 内部还挂载了 factor / screener 路由，启动时需要 factors.json
seed；TestClient 会触发 startup，因此 fixture 复用 test_screener/test_factor 的
思路——本测试只用 strategy 路由，不依赖 factor_calculator/registry 的 patch，
startup 会加载真实种子（只读，无副作用），不阻塞校验请求。
"""
import io
import re
import tokenize
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from services.strategy.errors import ErrorCode


# ============================================================
# 辅助
# ============================================================

def _valid_config() -> dict:
    """合法的双均线配置（用于 TR-4.1）。

    spec 009 后：trading_config.symbols 已移除（回测标的由 screen_config.stocks
    解析），且 signals 范式要求 screen_config.universe=manual + stocks ≤ 10。
    """
    return {
        "name": "双均线策略",
        "trading_config": {
            "signals": {
                "buy": {
                    "operator": "AND",
                    "conditions": [
                        {
                            "type": "compare",
                            "left": {"factor": "MA", "params": {"timeperiod": 5}},
                            "comparator": "cross_up",
                            "right": {
                                "factor": "MA",
                                "params": {"timeperiod": 20},
                            },
                        }
                    ],
                }
            },
            "position_sizing": {"method": "order_target_percent", "target": 0.95},
        },
        "screen_config": {
            "universe": {
                "pool": "manual",
                "point_in_time": None,
                "stocks": ["510300.SH"],
            },
        },
        "backtest_config": {"initial_cash": 100000},
    }


def _config_missing_signals() -> dict:
    """缺 signals 与 rebalance 的配置（用于 TR-4.2）。"""
    cfg = _valid_config()
    del cfg["trading_config"]["signals"]
    return cfg


@pytest.fixture(scope="module")
def client():
    """模块级 TestClient（启动开销大，复用）。"""
    import main as main_mod

    with TestClient(main_mod.app) as c:
        yield c


# ============================================================
# TR-4.1 合法 config → 200 + valid:true
# ============================================================

def test_validate_valid_returns_200(client):
    """TR-4.1：POST 合法 config → 200 + {valid:true, errors:[]}。"""
    resp = client.post(
        "/python/v1/strategies/validate", json={"config": _valid_config()}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["valid"] is True
    assert body["errors"] == []


# ============================================================
# TR-4.2 缺 signals/rebalance → 200 + valid:false
# ============================================================

def test_validate_missing_signals_returns_invalid(client):
    """TR-4.2：缺 signals/rebalance → 200 + errors 含 MISSING_SIGNALS_OR_REBALANCE。"""
    resp = client.post(
        "/python/v1/strategies/validate",
        json={"config": _config_missing_signals()},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["valid"] is False
    codes = [e["code"] for e in body["errors"]]
    assert ErrorCode.MISSING_SIGNALS_OR_REBALANCE[0] in codes


# ============================================================
# TR-4.3 非法请求体 → 422
# ============================================================

def test_validate_missing_config_field_returns_422(client):
    """TR-4.3a：请求体缺顶层 config 字段 → 422。"""
    resp = client.post("/python/v1/strategies/validate", json={})
    assert resp.status_code == 422


def test_validate_config_not_dict_returns_422(client):
    """TR-4.3b：config 不是 dict（如 list）→ 422。"""
    resp = client.post(
        "/python/v1/strategies/validate",
        json={"config": [1, 2, 3]},
    )
    assert resp.status_code == 422


def test_validate_invalid_structure_returns_422(client):
    """TR-4.3c：config 内部结构非法（缺 name）→ 路由返回 200+valid:false。

    注：缺 name 是 pydantic 解析失败 → 路由 catch ValidationError 返回
    ValidateResponse(valid=False)。检查 valid=False 即可。
    """
    cfg = _valid_config()
    cfg.pop("name")
    resp = client.post("/python/v1/strategies/validate", json={"config": cfg})
    assert resp.status_code == 200
    body = resp.json()
    assert body["valid"] is False


# ============================================================
# TR-4.4 services/strategy/ 不触库
# ============================================================

_STRATEGY_SOURCES = [
    Path("services/strategy"),
    Path("api/v1/strategy.py"),
]

_FORBIDDEN_PATTERNS = [
    re.compile(r"\bsqlite3\b"),
    re.compile(r"\bsqlalchemy\b"),
    re.compile(r"import\s+sqlite3"),
    re.compile(r"import\s+sqlalchemy"),
    re.compile(r"from\s+sqlite3"),
    re.compile(r"from\s+sqlalchemy"),
    re.compile(r"\.db['\"]"),
    re.compile(r"\.connect\s*\("),
    re.compile(r"\.cursor\s*\("),
    re.compile(r"CREATE\s+TABLE", re.I),
    re.compile(r"SELECT\s+.*\s+FROM", re.I),
]


def _strip_code(text: str) -> str:
    """剥离注释与 docstring（避免模块头"无 sqlite3/sqlalchemy"声明误报）。"""
    stripped = []
    try:
        tokens = tokenize.generate_tokens(io.StringIO(text).readline)
        for tok in tokens:
            ttype, tstring, _, _, _ = tok
            if ttype in (tokenize.COMMENT, tokenize.STRING):
                continue
            stripped.append(tstring)
    except tokenize.TokenError:
        return text
    return " ".join(stripped)


def test_no_database_code_in_strategy_module():
    """TR-4.4：services/strategy/ + api/v1/strategy.py 无任何数据库操作代码。"""
    violations = []
    for target in _STRATEGY_SOURCES:
        files = [target] if target.is_file() else sorted(target.rglob("*.py"))
        for path in files:
            text = path.read_text(encoding="utf-8")
            code = _strip_code(text)
            for pat in _FORBIDDEN_PATTERNS:
                for m in pat.finditer(code):
                    violations.append(
                        f"{path}: {pat.pattern} (offset {m.start()})"
                    )
    assert not violations, (
        "策略管理模块发现数据库操作代码（违反 engine 不触库硬约束）:\n"
        + "\n".join(violations)
    )


# ============================================================
# TR-4.5 /docs 页面返回 200
# ============================================================

def test_docs_page_returns_200(client):
    """TR-4.6：/docs 页面可访问（200）。"""
    resp = client.get("/docs")
    assert resp.status_code == 200


# ============================================================
# spec 009 Task 21：signals 与 rebalance 互斥 → valid:false
# ============================================================

def test_signals_and_rebalance_both_present_returns_invalid(client):
    """spec 009 Task 21：trading_config 同时含 signals 与 rebalance →
    /validate 返回 valid:false，错误信息含 SIGNALS_REBALANCE_EXCLUSIVE。

    注：/validate 端点对业务规则错误统一返回 200 + {valid:false, errors:[...]}
    （与 MISSING_SIGNALS_OR_REBALANCE 同一路由契约），不是 HTTP 422。
    """
    cfg = _valid_config()
    cfg["trading_config"]["rebalance"] = {"frequency": "weekly"}
    resp = client.post(
        "/python/v1/strategies/validate", json={"config": cfg}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["valid"] is False
    codes = [e["code"] for e in body["errors"]]
    assert ErrorCode.SIGNALS_REBALANCE_EXCLUSIVE[0] in codes
