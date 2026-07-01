from fastapi import APIRouter, HTTPException
from models.schemas.quote import (
    IndicatorCalculateRequest,
    IndicatorCalculateResponse
)
from services.indicator.tech_indicator import TechIndicatorService
from core.logger import logger

router = APIRouter(prefix="/quote", tags=["技术指标"])


@router.post("/calculate_indicators", response_model=IndicatorCalculateResponse, summary="计算技术指标")
async def calculate_indicators(request: IndicatorCalculateRequest):
    """
    为K线数据计算技术指标
    
    - 接收 K 线数据作为输入
    - 计算 MACD、KDJ、RSI 等常用指标
    - Python 服务不操作数据库
    """
    try:
        service = TechIndicatorService()
        data = await service.calculate_indicators_for_kline(request.kline_data)
        
        return IndicatorCalculateResponse(
            success=True,
            message="技术指标计算完成",
            data=data
        )
    except Exception as e:
        logger.error(f"计算技术指标失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"计算技术指标失败: {str(e)}")
