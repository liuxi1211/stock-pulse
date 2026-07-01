"""/python/v1 路由 —— 新的 Java-Python 协议入口。

这里的约定：
  - Java 持有「因子定义」，Python 只做「无状态数值计算」；
  - 统一使用 PythonApiResponse 返回，code=0 成功，其它为业务错误；
  - 暴露 /health 和 /registry 便于运维排错。
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict

from fastapi import APIRouter

from services.factor.compute.compute_service import FactorComputeService
from services.factor.compute.indicator_provider import (
    get_provider,
    current_provider_name,
)
from services.factor.protocol import (
    FactorComputeError,
    FactorComputeRequest,
    PythonApiResponse,
)

router = APIRouter(prefix="/python/v1", tags=["python-v1"])

logger = logging.getLogger(__name__)

# 注：此处不预构建 service，避免启动时与旧服务耦合。
_SERVICE = None


def _service() -> FactorComputeService:
    global _SERVICE
    if _SERVICE is None:
        _SERVICE = FactorComputeService()
    return _SERVICE


_DEFAULT_INDICATORS: list[str] = [
    "MA", "EMA", "MACD", "RSI", "BOLL", "CLOSE", "HIGH", "LOW", "VOLUME",
]


@router.get("/health", summary="Python 健康检查")
async def health() -> Dict[str, Any]:
    return {
        "status": "healthy",
        "provider": (
            # 安全地拿到当前 provider 的注册名
            _current_provider_name()
        ),
        "indicators": list(_DEFAULT_INDICATORS),
    }


def _current_provider_name() -> str:
    return current_provider_name()


@router.get("/registry", summary="Python 当前支持的 indicator 清单（运维用）")
async def registry() -> Dict[str, Any]:
    pv = get_provider()
    return {
        "provider": current_provider_name(),
        "providers": [type(pv).__name__],
        "indicators": sorted(pv.supported()),
    }


@router.post("/compute", summary="批量计算一组因子")
async def compute(request: FactorComputeRequest) -> PythonApiResponse[Any]:
    start = time.perf_counter()
    try:
        data = _service().compute_many(request)
        compute_ms = (time.perf_counter() - start) * 1000.0
        return PythonApiResponse.ok(
            data,
            req=request,
            compute_ms=round(compute_ms, 3),
            duration_ms=round(compute_ms, 3),
        )
    except FactorComputeError as exc:
        duration_ms = (time.perf_counter() - start) * 1000.0
        resp = PythonApiResponse.fail(exc.code, exc.message, req=request)
        resp.durationMs = round(duration_ms, 3)
        return resp
    except Exception as exc:  # noqa: BLE001
        logger.exception("未知异常")
        duration_ms = (time.perf_counter() - start) * 1000.0
        resp = PythonApiResponse.fail(500000, f"INTERNAL_ERROR: {exc}", req=request)
        resp.durationMs = round(duration_ms, 3)
        return resp
