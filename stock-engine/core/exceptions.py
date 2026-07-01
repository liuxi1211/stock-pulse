class StockBaseException(Exception):
    """基础异常类，所有自定义异常继承此类"""
    code: int = 500
    message: str = "系统内部错误"
    
    def __init__(self, message: str = None, code: int = None):
        if message:
            self.message = message
        if code:
            self.code = code
        super().__init__(self.message)


class DataFetchException(StockBaseException):
    """数据拉取异常"""
    code = 500
    message = "数据拉取失败，请检查网络或数据源"


class DataNotFoundException(StockBaseException):
    """数据不存在异常"""
    code = 404
    message = "请求的数据不存在"


class ValidationException(StockBaseException):
    """参数验证异常"""
    code = 400
    message = "参数验证失败"


class StrategyNotFoundException(StockBaseException):
    """策略不存在异常"""
    code = 404
    message = "指定的策略不存在"
