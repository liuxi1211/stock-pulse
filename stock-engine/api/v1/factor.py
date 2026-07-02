"""因子库 FastAPI 路由层（spec FR-2, FR-6, FR-7, AC-19）。

路由前缀 ``/python/v1/factors``（与原型/统一网关一致）。职责：参数接收 → 调
registry/calculator → 封装统一响应。业务异常由 ``main.py`` 全局处理器转为
400/404 + ``errorCode``。

注意：``GET /categories`` 必须声明在 ``GET /{factor_key}`` 之前，否则会被路径
参数吞掉（FastAPI 按声明顺序匹配）。
"""
from typing import Optional

import numpy as np
from fastapi import APIRouter

from models.schemas.factor import (
    BatchFactorComputeRequest,
    BatchFactorComputeResponse,
    ErrorResponse,
    FactorCategory,
    FactorCategoryListResponse,
    FactorComputeRequest,
    FactorComputeResponse,
    FactorCreateRequest,
    FactorDef,
    FactorDetailResponse,
    FactorListResponse,
    FactorMutationResponse,
    FactorUpdateRequest,
)
from services.factor import factor_calculator, factor_registry, kline_to_arrays

router = APIRouter(prefix="/python/v1/factors", tags=["因子库"])

_ERR = {400: {"model": ErrorResponse}, 404: {"model": ErrorResponse}}


def _series_to_list(arr: np.ndarray) -> list:
    """ndarray → list[float]，NaN/Inf → None（JSON 序列化安全，预热段对齐 talib）。"""
    result = []
    for value in np.asarray(arr, dtype=np.float64):
        if np.isnan(value) or np.isinf(value):
            result.append(None)
        else:
            result.append(float(value))
    return result


# ============================================================
# 元数据查询
# ============================================================

@router.get(
    "/categories",
    response_model=FactorCategoryListResponse,
    summary="获取因子分类列表",
    description="返回全部因子分类（key / 展示名 / 默认来源）。",
)
def list_categories():
    cats = [FactorCategory(**c) for c in factor_registry.list_categories()]
    return FactorCategoryListResponse(data=cats)


@router.get(
    "",
    response_model=FactorListResponse,
    summary="获取因子列表",
    description="支持按 ``category`` / ``source`` 过滤；默认返回全部因子。",
)
def list_factors(category: Optional[str] = None, source: Optional[str] = None):
    factors = factor_registry.list_factors(category=category, source=source)
    return FactorListResponse(data=factors, total=len(factors))


@router.get(
    "/{factor_key}",
    response_model=FactorDetailResponse,
    summary="获取单个因子详情",
    responses=_ERR,
)
def get_factor(factor_key: str):
    fd = factor_registry.get_factor(factor_key)
    return FactorDetailResponse(data=fd)


# ============================================================
# CRUD
# ============================================================

@router.post(
    "",
    response_model=FactorMutationResponse,
    status_code=201,
    summary="新增因子",
    description="factorKey 须唯一；原子写入 ``data/factors.json``。重复返回 400 FACTOR_ALREADY_EXISTS。",
    responses=_ERR,
)
def create_factor(req: FactorCreateRequest):
    fd = FactorDef(**req.model_dump())
    saved = factor_registry.add_factor(fd)
    return FactorMutationResponse(message="新增成功", data=saved)


@router.put(
    "/{factor_key}",
    response_model=FactorMutationResponse,
    summary="修改因子定义",
    description="不允许修改 factorKey，其余字段为可选 subset。",
    responses=_ERR,
)
def update_factor(factor_key: str, req: FactorUpdateRequest):
    saved = factor_registry.update_factor(factor_key, req.model_dump(exclude_unset=True))
    return FactorMutationResponse(message="修改成功", data=saved)


@router.delete(
    "/{factor_key}",
    response_model=FactorMutationResponse,
    summary="删除因子",
    responses=_ERR,
)
def delete_factor(factor_key: str):
    factor_registry.delete_factor(factor_key)
    return FactorMutationResponse(message="删除成功", data=None)


# ============================================================
# 计算
# ============================================================

@router.post(
    "/compute",
    response_model=FactorComputeResponse,
    summary="单标的多因子计算",
    description="对一段 OHLCV 数据一次性计算多个因子；TUSHARE 因子返回 400 FACTOR_NOT_COMPUTABLE，"
                "未知 factorKey 返回 400 UNKNOWN_FACTOR，参数越界返回 400 INVALID_PARAM。",
    responses=_ERR,
)
def compute(req: FactorComputeRequest):
    inputs = kline_to_arrays(req.data)
    specs = [s.model_dump() for s in req.factors]
    results = factor_calculator.compute_batch(specs, inputs)
    return FactorComputeResponse(
        data={key: _series_to_list(arr) for key, arr in results.items()}
    )


@router.post(
    "/batch-compute",
    response_model=BatchFactorComputeResponse,
    summary="多标的批量因子计算",
    description="选股场景：``{symbol: [ohlcv]}`` × 多因子，单次请求减少网络往返。",
    responses=_ERR,
)
def batch_compute(req: BatchFactorComputeRequest):
    specs = [s.model_dump() for s in req.factors]
    out: dict[str, dict[str, list]] = {}
    for symbol, records in req.data.items():
        inputs = kline_to_arrays(records)
        results = factor_calculator.compute_batch(specs, inputs)
        out[symbol] = {key: _series_to_list(arr) for key, arr in results.items()}
    return BatchFactorComputeResponse(data=out)
