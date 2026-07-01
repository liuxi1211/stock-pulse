from fastapi import APIRouter, HTTPException
from models.schemas.backtest import BacktestRequest, BacktestResponse, BacktestResult
from services.backtest.strategy_runner import StrategyRunner
from core.logger import logger
from core.exceptions import StrategyNotFoundException

router = APIRouter(prefix="/backtest", tags=["策略回测"])


@router.post("/run", response_model=BacktestResponse, summary="执行策略回测")
async def run_backtest(request: BacktestRequest):
    """
    执行量化策略回测，支持多种策略：
    
    - `double_ma`: 双均线策略
    - `macd`: MACD策略
    - `rsi`: RSI策略
    
    返回回测结果，包含：
    - 年化收益率、最大回撤、夏普比率等核心指标
    - 权益曲线数据，用于前端绘制收益图
    
    Python 服务不操作数据库，接收 K 线数据作为输入
    """
    try:
        runner = StrategyRunner()
        result_data = await runner.run(
            strategy=request.strategy,
            kline_data=request.kline_data,
            params=request.params
        )
        
        result = BacktestResult(**result_data)
        return BacktestResponse(
            success=True,
            message="回测执行完成",
            result=result
        )
        
    except StrategyNotFoundException as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"执行回测失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"回测执行失败: {str(e)}")
