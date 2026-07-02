"""FactorRegistry 单元测试（CRUD + 持久化 + 并发，TR-3.1~3.10）。"""
import json
import threading

import pytest

from core.exceptions import FactorAlreadyExistsError, FactorNotFoundError, ValidationException
from models.schemas.factor import FactorDef


def _make_factor(key: str = "NEW_FACTOR") -> FactorDef:
    return FactorDef(
        factorKey=key, displayName="测试因子", category="MOMENTUM", source="AKQUANT",
        akquantFunc="MA", dataSource="ohlcv", description="测试",
        params=[], inputs=["close"], multiOutput=False, outputLabels=[],
        defaultOutputIndex=0, lookbackHint="0", lookbackDefault=0,
    )


def test_list_factors_count(temp_registry):
    # TR-3.1
    factors = temp_registry.list_factors()
    assert len(factors) == 54
    cats = {f.category for f in factors}
    assert cats <= {"OVERLAP", "MOMENTUM", "VOLATILITY", "VOLUME", "STATISTIC",
                    "PRICE", "VALUATION", "QUALITY", "GROWTH", "FINANCE"}


def test_get_factor(temp_registry):
    # TR-3.2
    ma = temp_registry.get_factor("MA")
    assert ma.factorKey == "MA"
    assert ma.inputs == ["close"]
    assert ma.source == "AKQUANT"


def test_get_factor_not_found(temp_registry):
    # TR-3.3
    with pytest.raises(FactorNotFoundError):
        temp_registry.get_factor("NOT_EXIST")


def test_add_factor_persists(temp_registry):
    # TR-3.4
    temp_registry.add_factor(_make_factor("PERSIST_A"))
    # 重新加载应仍存在
    temp_registry.reload()
    assert temp_registry.exists("PERSIST_A")
    # 文件确实写入
    with open(temp_registry._runtime_file, "r", encoding="utf-8") as f:
        raw = json.load(f)
    assert any(fac["factorKey"] == "PERSIST_A" for fac in raw["factors"])


def test_update_factor(temp_registry):
    # TR-3.5
    temp_registry.add_factor(_make_factor("UPD"))
    temp_registry.update_factor("UPD", {"displayName": "改名后", "description": "new"})
    fd = temp_registry.get_factor("UPD")
    assert fd.displayName == "改名后"
    assert fd.description == "new"


def test_delete_factor(temp_registry):
    # TR-3.6
    temp_registry.add_factor(_make_factor("DEL"))
    temp_registry.delete_factor("DEL")
    with pytest.raises(FactorNotFoundError):
        temp_registry.get_factor("DEL")
    # 文件中也不再存在
    with open(temp_registry._runtime_file, "r", encoding="utf-8") as f:
        raw = json.load(f)
    assert not any(fac["factorKey"] == "DEL" for fac in raw["factors"])


def test_duplicate_add(temp_registry):
    # TR-3.7
    with pytest.raises(FactorAlreadyExistsError):
        temp_registry.add_factor(_make_factor("MA"))  # MA 已存在


def test_concurrent_add(temp_registry):
    # TR-3.8：20 线程各加一个不同因子，结果应全部存在且文件不损坏
    keys = [f"CONC_{i}" for i in range(20)]

    def add(key):
        temp_registry.add_factor(_make_factor(key))

    threads = [threading.Thread(target=add, args=(k,)) for k in keys]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    # 全部成功（key 互不相同，不会冲突）
    for k in keys:
        assert temp_registry.exists(k)
    # 文件可正常解析
    with open(temp_registry._runtime_file, "r", encoding="utf-8") as f:
        json.load(f)


def test_get_lookback(temp_registry):
    # TR-3.9
    assert temp_registry.get_lookback("MA", {"timeperiod": 5}) == 4
    assert temp_registry.get_lookback("MACD") == 33  # 26 + 9 - 2
    assert temp_registry.get_lookback("CLOSE") == 0


def test_validate_rejects_bad_source(temp_registry):
    fd = _make_factor("BAD")
    fd.source = "WEIRD"
    with pytest.raises(ValidationException):
        temp_registry.add_factor(fd)


def test_lookback_hint_fallback(temp_registry):
    # lookbackHint 为非法表达式时回退 lookbackDefault
    temp_registry.add_factor(_make_factor("LB"))
    temp_registry.update_factor("LB", {"lookbackHint": "??? bad", "lookbackDefault": 7})
    assert temp_registry.get_lookback("LB") == 7


def test_corrupt_runtime_recovers(temp_registry):
    # 写入损坏 JSON 后 reload 应从种子恢复
    with open(temp_registry._runtime_file, "w", encoding="utf-8") as f:
        f.write("{not valid json")
    temp_registry.reload()
    assert len(temp_registry.list_factors()) == 54
