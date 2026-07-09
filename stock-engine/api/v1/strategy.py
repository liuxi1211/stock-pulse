"""策略配置校验 FastAPI 路由（spec FR-4）。

POST /python/v1/strategies/validate
两段式校验：① Pydantic 解析（可能短路）→ 失败返回 200+valid:false；
② StrategyValidator 业务规则校验（非短路）→ 200+ValidateResponse。

约束：engine 不触库，本模块无 sqlite3/sqlalchemy。
"""
from fastapi import APIRouter
from pydantic import ValidationError

from services.strategy.models import (
    StrategyConfigModel, ValidateRequest, ValidateResponse,
    StrategyValidationErrorModel,
)
from services.strategy.validator import StrategyValidator

router = APIRouter(prefix="/python/v1/strategies", tags=["策略管理"])

_validator = StrategyValidator()


def _loc_to_path(loc: tuple) -> str:
    """Pydantic ValidationError 的 loc 元组转点号路径。
    例：("trading_config","signals") → "trading_config.signals"
         ("screen_config","conditions",0) → "screen_config.conditions[0]"
    整数元素用 [n] 包裹。
    """
    parts = []
    for item in loc:
        if isinstance(item, int):
            parts.append(f"[{item}]")
        else:
            parts.append(str(item))
    path = ""
    for p in parts:
        if p.startswith("["):
            path += p
        else:
            path = ("." + p) if path else p
    return path


@router.post("/validate", response_model=ValidateResponse, summary="校验策略配置")
async def validate_strategy(request: ValidateRequest) -> ValidateResponse:
    """两段式校验：Pydantic 解析 → 业务规则校验。"""
    try:
        config = StrategyConfigModel.model_validate(request.config)
    except ValidationError as e:
        errors = []
        for err in e.errors():
            path = _loc_to_path(err.get("loc", ()))
            msg = err.get("msg", "字段校验失败")
            code = err.get("type", "INVALID_FIELD")
            errors.append(StrategyValidationErrorModel(path=path, code=code, message=msg))
        return ValidateResponse(valid=False, errors=errors)

    biz_errors = _validator.validate(config)
    error_models = [
        StrategyValidationErrorModel(path=e.path, code=e.code, message=e.message)
        for e in biz_errors
    ]
    return ValidateResponse(valid=len(error_models) == 0, errors=error_models)
