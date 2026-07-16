"""因子库 Pydantic Schema。

字段命名与 ``data/factors.default.json`` 及统一策略配置 Schema 完全对齐（camelCase），
覆盖：因子元数据 / 分类 / CRUD 请求 / 查询响应 / 计算请求响应 / 错误响应。
"""
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


# ============================================================
# 元数据模型
# ============================================================

class FactorParam(BaseModel):
    """因子参数定义（透传给 akquant.talib 函数）"""
    name: str = Field(..., description="参数键名", examples=["timeperiod"])
    displayName: str = Field(..., description="展示名", examples=["周期"])
    type: Literal["INT", "FLOAT"] = Field(..., description="参数类型")
    defaultValue: float = Field(..., description="默认值", examples=[5])
    min: float = Field(..., description="最小值（含）", examples=[1])
    max: float = Field(..., description="最大值（含）", examples=[500])
    step: float = Field(1, description="步长", examples=[1])


class FactorCategory(BaseModel):
    """因子分类"""
    key: str = Field(..., examples=["OVERLAP"])
    name: str = Field(..., examples=["趋势指标"])
    source: str = Field(..., description="该分类默认来源", examples=["AKQUANT"])


class FactorDef(BaseModel):
    """因子完整定义（JSON 文件单条 / API 详情响应）"""
    factorKey: str = Field(..., description="唯一标识，大写蛇形", examples=["MA"])
    displayName: str = Field(..., examples=["MA 简单移动平均线"])
    category: str = Field(..., description="分类 key", examples=["OVERLAP"])
    source: Literal["AKQUANT", "TUSHARE", "RAW", "DERIVED"] = Field(..., description="来源，决定可计算性")
    akquantFunc: Optional[str] = Field(None, description="akquant.talib 函数名（AKQUANT/DERIVED）", examples=["MA"])
    tushareField: Optional[str] = Field(None, description="Tushare 字段名（TUSHARE）", examples=["pe_ttm"])
    dataSource: str = Field("ohlcv", description="数据来源标记", examples=["ohlcv"])
    description: str = Field("", description="一句话语义说明")
    params: list[FactorParam] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list, description="所需 OHLCV 列", examples=[["close"]])
    multiOutput: bool = Field(False, description="是否多输出")
    outputLabels: list[str] = Field(default_factory=list, examples=[["DIF", "DEA", "MACD柱"]])
    defaultOutputIndex: int = Field(0, description="默认输出序列下标")
    lookbackHint: str = Field("0", description="回看长度表达式（可含参数）", examples=["timeperiod - 1"])
    lookbackDefault: int = Field(0, description="默认参数下的回看长度（bars）", examples=[4])
    transformable: bool = Field(
        False,
        description="是否支持 transform 滚动窗口聚合（ma/std/pct_change/max/min）；"
        "前端条件树据此决定是否展示「滚动窗口」配置入口",
    )


# ============================================================
# CRUD 请求
# ============================================================

class FactorCreateRequest(BaseModel):
    """新增因子请求（与 FactorDef 等价，factorKey 必填且须唯一）"""
    factorKey: str = Field(..., examples=["NEW_FACTOR"])
    displayName: str = Field(..., examples=["自定义动量因子"])
    category: str = Field(..., examples=["MOMENTUM"])
    source: Literal["AKQUANT", "TUSHARE", "RAW", "DERIVED"] = Field(...)
    akquantFunc: Optional[str] = None
    tushareField: Optional[str] = None
    dataSource: str = "ohlcv"
    description: str = ""
    params: list[FactorParam] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list)
    multiOutput: bool = False
    outputLabels: list[str] = Field(default_factory=list)
    defaultOutputIndex: int = 0
    lookbackHint: str = "0"
    lookbackDefault: int = 0
    transformable: bool = False


class FactorUpdateRequest(BaseModel):
    """修改因子请求（不允许改 factorKey，其余字段为可选 subset）"""
    displayName: Optional[str] = None
    category: Optional[str] = None
    source: Optional[Literal["AKQUANT", "TUSHARE", "RAW", "DERIVED"]] = None
    akquantFunc: Optional[str] = None
    tushareField: Optional[str] = None
    dataSource: Optional[str] = None
    description: Optional[str] = None
    params: Optional[list[FactorParam]] = None
    inputs: Optional[list[str]] = None
    multiOutput: Optional[bool] = None
    outputLabels: Optional[list[str]] = None
    defaultOutputIndex: Optional[int] = None
    lookbackHint: Optional[str] = None
    lookbackDefault: Optional[int] = None
    transformable: Optional[bool] = None


# ============================================================
# 查询 / 变更响应
# ============================================================

class FactorListResponse(BaseModel):
    success: bool = True
    message: str = "查询成功"
    data: list[FactorDef] = Field(default_factory=list)
    total: int = Field(0, description="本次返回因子数")


class FactorDetailResponse(BaseModel):
    success: bool = True
    message: str = "查询成功"
    data: FactorDef


class FactorCategoryListResponse(BaseModel):
    success: bool = True
    message: str = "查询成功"
    data: list[FactorCategory]


class FactorMutationResponse(BaseModel):
    """新增 / 修改 / 删除统一响应"""
    success: bool = True
    message: str = "操作成功"
    data: Optional[FactorDef] = Field(None, description="新增/修改返回最新定义；删除为 null")


# ============================================================
# 计算请求 / 响应
# ============================================================

class FactorComputeSpec(BaseModel):
    """单个因子的计算规格"""
    factorKey: str = Field(..., examples=["MA"])
    params: dict[str, Any] = Field(
        default_factory=dict,
        description="参数键值，覆盖默认值；为空用因子默认参数",
        examples=[{"timeperiod": 5}]
    )
    outputIndex: Optional[int] = Field(
        None, description="多输出因子取第几路（None→defaultOutputIndex）", examples=[0]
    )


class FactorComputeRequest(BaseModel):
    """单标的多因子计算请求"""
    data: list[dict[str, Any]] = Field(
        ..., description="OHLCV 记录列表（含 date/open/high/low/close/volume）"
    )
    factors: list[FactorComputeSpec] = Field(..., min_length=1)


class FactorComputeResponse(BaseModel):
    success: bool = True
    message: str = "计算完成"
    data: dict[str, list] = Field(
        default_factory=dict, description="{factorKey: [值, ...]}，长度与输入一致，预热段 NaN→null"
    )


class BatchFactorComputeRequest(BaseModel):
    """多标的批量计算请求"""
    data: dict[str, list[dict[str, Any]]] = Field(
        ..., description="{symbol: [ohlcv 记录]}"
    )
    factors: list[FactorComputeSpec] = Field(..., min_length=1)


class BatchFactorComputeResponse(BaseModel):
    success: bool = True
    message: str = "计算完成"
    data: dict[str, dict[str, list]] = Field(
        default_factory=dict, description="{symbol: {factorKey: [值, ...]}}"
    )


# ============================================================
# 错误响应
# ============================================================

class ErrorResponse(BaseModel):
    """统一错误响应（与全局异常处理器输出一致）"""
    success: bool = False
    message: str
    code: int
    errorCode: Optional[str] = Field(None, description="机器可读错误码，如 UNKNOWN_FACTOR")
