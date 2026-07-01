"""因子库 - Pydantic 请求/响应模型"""

from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, Field, field_validator

FactorCategory = Literal["TREND", "MOMENTUM", "VOLATILITY", "VOLUME", "PRICE"]


class FactorParamDef(BaseModel):
    name: str
    displayName: str
    type: Literal["INT", "FLOAT", "ENUM"] = "INT"
    defaultValue: Optional[float] = None
    min: Optional[float] = None
    max: Optional[float] = None
    step: Optional[float] = None
    enumValues: Optional[List[Dict[str, Any]]] = None


class FactorMeta(BaseModel):
    factorKey: str
    displayName: str
    category: FactorCategory
    description: str = ""
    params: List[FactorParamDef] = []
    inputs: List[str] = Field(..., description="从 open/high/low/close/volume 中选取")
    multiOutput: bool = False
    outputLabels: List[str] = []
    defaultOutputIndex: int = 0
    lookbackHint: Optional[str] = None
    lookbackDefault: Optional[int] = None

    @field_validator("inputs")
    @classmethod
    def _inputs_in_whitelist(cls, v: List[str]) -> List[str]:
        allowed = {"open", "high", "low", "close", "volume"}
        if not v or not set(v).issubset(allowed):
            raise ValueError(f"inputs 必须是 {allowed} 的子集，当前值: {v}")
        return v


class FactorRegistryResponse(BaseModel):
    factors: List[FactorMeta]
    count: int
    categories: List[str]


class FactorReference(BaseModel):
    factor: str = Field(..., description="注册表中的 factorKey，如 MA / MACD")
    params: Optional[Dict[str, Any]] = Field(default_factory=dict)
    outputIndex: Optional[int] = Field(
        default=None,
        description="仅用于调用方标识自己关心的输出列；"
                    "multiOutput=false 时可省略。"
                    "服务端始终返回所有输出列。",
    )


class OhlcvItem(BaseModel):
    date: str = Field(..., description="YYYYMMDD 或 ISO 字符串，与 Java 端保持一致")
    open: float
    high: float
    low: float
    close: float
    volume: Optional[float] = None


class FactorComputeRequest(BaseModel):
    taskId: Optional[str] = None
    stockCode: Optional[str] = None
    ohlcv: List[OhlcvItem]
    factors: List[FactorReference]


class FactorComputeResponse(BaseModel):
    taskId: Optional[str] = None
    computeMs: Optional[float] = None
    dates: List[str]
    results: Dict[str, List[Optional[float]]]
