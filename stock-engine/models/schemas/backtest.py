from pydantic import BaseModel, Field
from typing import Optional, Dict, List, Any


class BacktestRequest(BaseModel):
    """回测请求模型"""
    strategy: str = Field(..., description="策略名称，如 double_ma, macd, rsi")
    kline_data: List[Dict[str, Any]] = Field(..., description="K线历史数据")
    params: Optional[Dict] = Field(default=None, description="策略自定义参数")


class BacktestResult(BaseModel):
    """回测结果模型"""
    annual_return: float = Field(..., description="年化收益率")
    max_drawdown: float = Field(..., description="最大回撤")
    sharpe_ratio: float = Field(..., description="夏普比率")
    total_return: float = Field(..., description="总收益率")
    win_rate: Optional[float] = Field(None, description="胜率")
    trade_count: Optional[int] = Field(None, description="交易次数")
    equity_curve: List[Dict] = Field(..., description="权益曲线数据")


class BacktestResponse(BaseModel):
    """回测响应模型"""
    success: bool
    message: str
    result: BacktestResult
