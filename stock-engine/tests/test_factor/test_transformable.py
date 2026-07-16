"""transformable 字段测试（spec 014 工作流 A）。

验证：
1. factors.default.json 加载后，仅「强烈合理」的因子标注 transformable=True
   （价格直通 + 估值/市值 + 成交量 + 换手率；技术指标本身已是衍生量，不标）；
2. FactorDef schema 接受 transformable 字段；
3. CRUD（Create/Update Request）能设置 transformable；
4. 缺省值为 False（向后兼容旧 JSON 不带字段）。
"""
from models.schemas.factor import FactorDef, FactorCreateRequest, FactorUpdateRequest

# 仅这些因子支持 transform 滚动窗口聚合（spec 014：只标业务上强烈合理的）
TRANSFORMABLE_KEYS = {
    "CLOSE", "HIGH", "LOW", "OPEN",  # 价格直通
    "PE_TTM", "PB", "PS_TTM", "DV_RATIO",  # 估值
    "TOTAL_MV", "CIRC_MV",  # 市值
    "VOLUME",  # 成交量
    "TURNOVER_RATE",  # 换手率
}


def test_default_seed_factors_transformable(temp_registry):
    """种子库因子应按业务合理性标注 transformable（只标强烈合理子集）。"""
    factors = temp_registry.list_factors()
    assert len(factors) > 0
    wrong_true = [f.factorKey for f in factors if f.transformable and f.factorKey not in TRANSFORMABLE_KEYS]
    wrong_false = [f.factorKey for f in factors if not f.transformable and f.factorKey in TRANSFORMABLE_KEYS]
    assert wrong_true == [], f"以下因子不应标 transformable: {wrong_true}"
    assert wrong_false == [], f"以下因子应标 transformable 但未标: {wrong_false}"


def test_specific_factors_transformable(temp_registry):
    """头号用例因子（PE_TTM / CLOSE / VOLUME）必须支持 transform；技术指标（MA/RSI/MACD）不应支持。"""
    for key in ("PE_TTM", "CLOSE", "VOLUME", "TOTAL_MV", "TURNOVER_RATE"):
        fd = temp_registry.get_factor(key)
        assert fd.transformable is True, f"{key} 应支持 transform"
    for key in ("MA", "RSI", "MACD", "ATR", "STDDEV"):
        fd = temp_registry.get_factor(key)
        assert fd.transformable is False, f"{key} 不应支持 transform（技术指标本身已是衍生量）"


def test_factor_def_accepts_transformable():
    """FactorDef schema 接受 transformable 字段。"""
    fd = FactorDef(
        factorKey="X", displayName="X", category="MOMENTUM", source="AKQUANT",
        akquantFunc="MA", dataSource="ohlcv", description="",
        params=[], inputs=["close"], multiOutput=False, outputLabels=[],
        defaultOutputIndex=0, lookbackHint="0", lookbackDefault=0,
        transformable=True,
    )
    assert fd.transformable is True


def test_factor_def_default_false():
    """缺省 transformable=False（向后兼容旧 JSON）。"""
    fd = FactorDef(
        factorKey="X", displayName="X", category="MOMENTUM", source="AKQUANT",
        akquantFunc="MA", dataSource="ohlcv", description="",
        params=[], inputs=["close"], multiOutput=False, outputLabels=[],
        defaultOutputIndex=0, lookbackHint="0", lookbackDefault=0,
    )
    assert fd.transformable is False


def test_create_request_accepts_transformable():
    """Create Request 能设置 transformable。"""
    req = FactorCreateRequest(
        factorKey="X", displayName="X", category="MOMENTUM", source="AKQUANT",
        transformable=True,
    )
    assert req.transformable is True


def test_update_request_accepts_transformable():
    """Update Request 能更新 transformable。"""
    req = FactorUpdateRequest(transformable=False)
    assert req.transformable is False


def test_update_request_default_none():
    """Update Request 缺省 None（不覆盖既有值）。"""
    req = FactorUpdateRequest()
    assert req.transformable is None
