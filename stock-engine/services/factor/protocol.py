"""Java-Python 因子计算统一协议。

为何单独放到 services/factor/protocol.py：
  - Java 持有「因子定义」（factorKey、参数、输出列），Python 只做「无状态数值计算」。
  - 这样 Java 可以按名按列选取所需列，也便于在其他语言下重写计算实现。
  - Python 不关心这个因子叫 MA 还是在前端怎么渲染，只关心按名字算数组。

对外暴露的核心类型：
  OhlcvItem              一根 K 线
  FactorComputeParam     一个因子调用（factorKey + params）
  FactorComputeRequest   一次批量请求（一个标的 + 一组因子）
  PythonApiResponse      统一响应（code=0 成功）
  FactorComputeError     业务异常（code/message）
"""

from __future__ import annotations

import time
from typing import Any, Dict, Generic, List, Optional, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class OhlcvItem(BaseModel):
    """一根 K 线。字段与 Java 端协议保持一致。"""

    date: str
    open: float
    high: float
    low: float
    close: float
    volume: Optional[float] = None


class FactorComputeParam(BaseModel):
    """一次因子调用参数：factorKey 与 Java 端配置保持一致。

    - requestKey：可选，由 Java 端传入，作为结果 key 的前缀。
        - 单输出时：结果 key = requestKey（如 "MA_5"）
        - 多输出时：结果 key = requestKey + "_" + label 或索引
        - 未提供时：回退为 factorKey
    - outputLabels：可选，由 Java 端传入，多输出时用语义标签替代数字索引。
    """

    factorKey: str
    params: Dict[str, Any] = Field(default_factory=dict)
    requestKey: Optional[str] = None
    outputLabels: Optional[List[str]] = None


class FactorComputeRequest(BaseModel):
    """一次批量计算请求：ohlcv 按时间升序（老在前、新在后）。"""

    requestId: Optional[str] = None
    stockCode: Optional[str] = None
    ohlcv: List[OhlcvItem]
    factors: List[FactorComputeParam]


class PythonApiResponse(BaseModel, Generic[T]):
    """统一响应结构。code=0 表示成功；其它值表示业务错误（详见 FactorComputeError.code）。"""

    code: int
    message: str
    data: Optional[T] = None
    requestId: Optional[str] = None
    computeMs: Optional[float] = None
    durationMs: Optional[float] = None

    @classmethod
    def ok(cls, data: T,
           req: Optional[FactorComputeRequest] = None,
           compute_ms: Optional[float] = None,
           duration_ms: Optional[float] = None) -> "PythonApiResponse[T]":
        return cls(
            code=0, message="OK", data=data,
            requestId=req.requestId if req else None,
            computeMs=compute_ms,
            durationMs=duration_ms,
        )

    @classmethod
    def fail(cls, code: int, message: str,
             req: Optional[FactorComputeRequest] = None) -> "PythonApiResponse[Any]":
        return cls(
            code=code, message=message, data=None,
            requestId=req.requestId if req else None,
        )


class FactorComputeError(Exception):
    """Python 端因子计算业务错误，用于向上抛出。"""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message


def now_ms() -> float:
    """以毫秒为单位的高精度时间戳，用于 computeMs/durationMs。"""
    return time.perf_counter() * 1000.0
