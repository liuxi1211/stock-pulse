from pydantic import BaseModel, Field
from typing import List, Dict, Optional, Any


class KlineItem(BaseModel):
    """K线数据项"""
    trade_date: str
    open: float
    close: float
    high: float
    low: float
    volume: float
    amount: Optional[float] = None
    # 技术指标
    macd_d: Optional[float] = None
    macd_k: Optional[float] = None
    macd_j: Optional[float] = None
    kdj_k: Optional[float] = None
    kdj_d: Optional[float] = None
    kdj_j: Optional[float] = None
    rsi_6: Optional[float] = None
    rsi_12: Optional[float] = None


class IndicatorCalculateRequest(BaseModel):
    """技术指标计算请求模型"""
    kline_data: List[Dict[str, Any]] = Field(..., description="K线数据列表，需包含open/high/low/close/volume")


class IndicatorCalculateResponse(BaseModel):
    """技术指标计算响应模型"""
    success: bool
    message: str
    data: List[KlineItem]
