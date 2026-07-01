import pandas as pd
from core.logger import logger
from core.exceptions import StrategyNotFoundException

try:
    import akquant as aq  # noqa: F401  仅在运行环境中可用
except Exception:  # noqa: BLE001
    aq = None


class StrategyRunner:
    """
    策略回测执行器
    负责加载策略、执行回测、返回回测结果
    Python 服务不直接操作数据库，接收数据作为输入
    """
    
    def __init__(self):
        # 注册支持的策略
        self.strategies = {
            "double_ma": self.run_double_ma,
            "macd": self.run_macd,
            "rsi": self.run_rsi,
            # 后续添加新策略在这里注册
        }
    
    async def run(self, strategy: str, kline_data: list, params: dict = None):
        """
        执行策略回测
        kline_data: K线历史数据列表
        """
        if strategy not in self.strategies:
            raise StrategyNotFoundException(f"不支持的策略: {strategy}")
        
        params = params or {}
        logger.info(f"开始执行 {strategy} 策略回测，数据条数: {len(kline_data)}")
        
        # 1. 转换数据为DataFrame
        df = pd.DataFrame(kline_data)
        if df.empty:
            raise Exception("K线数据为空，无法执行回测")
        
        # 2. 执行对应策略
        strategy_func = self.strategies[strategy]
        result = await strategy_func(df, **params)
        
        logger.info(f"回测完成，年化收益: {result['annual_return']:.2%}, 最大回撤: {result['max_drawdown']:.2%}")
        return result
    
    async def run_double_ma(self, df: pd.DataFrame, fast_window: int = 5, slow_window: int = 20):
        """
        双均线策略回测
        """
        logger.info(f"执行双均线策略，快周期: {fast_window}, 慢周期: {slow_window}")
        
        # TODO: 使用AKQuant实现双均线策略回测
        # backtest = aq.Backtest(df)
        # result = backtest.run(double_ma_strategy, fast_window=fast_window, slow_window=slow_window)
        
        # 临时返回示例数据
        return {
            "annual_return": 0.15,
            "max_drawdown": 0.1,
            "sharpe_ratio": 1.5,
            "total_return": 0.3,
            "win_rate": 0.6,
            "trade_count": 12,
            "equity_curve": []
        }
    
    async def run_macd(self, df: pd.DataFrame, **params):
        """
        MACD策略回测
        """
        logger.info("执行MACD策略回测")
        
        # TODO: 使用AKQuant实现MACD策略回测
        return {
            "annual_return": 0.12,
            "max_drawdown": 0.12,
            "sharpe_ratio": 1.2,
            "total_return": 0.25,
            "win_rate": 0.55,
            "trade_count": 10,
            "equity_curve": []
        }
    
    async def run_rsi(self, df: pd.DataFrame, **params):
        """
        RSI策略回测
        """
        logger.info("执行RSI策略回测")
        
        # TODO: 使用AKQuant实现RSI策略回测
        return {
            "annual_return": 0.18,
            "max_drawdown": 0.08,
            "sharpe_ratio": 1.8,
            "total_return": 0.36,
            "win_rate": 0.65,
            "trade_count": 15,
            "equity_curve": []
        }
