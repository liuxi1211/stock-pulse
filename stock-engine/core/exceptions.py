class StockBaseException(Exception):
    """基础异常类，所有自定义异常继承此类。

    新增 ``error_code`` 机器可读错误码（可选），用于对外返回结构化错误响应。
    全局异常处理器会在响应体中带上 ``errorCode``（仅当非空）。
    """
    code: int = 500
    message: str = "系统内部错误"
    error_code: str = None

    def __init__(self, message: str = None, code: int = None, error_code: str = None):
        if message:
            self.message = message
        if code:
            self.code = code
        if error_code:
            self.error_code = error_code
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


# ============================================================
# 因子库异常
# error_code 为机器可读错误码，对齐 spec AC-9~11 / AC-16 的结构化错误响应
# ============================================================

class FactorNotFoundError(StockBaseException):
    """因子不存在（GET/PUT/DELETE 目标 factorKey 找不到）"""
    code = 404
    message = "因子不存在"
    error_code = "FACTOR_NOT_FOUND"


class FactorAlreadyExistsError(StockBaseException):
    """新增因子时 factorKey 已存在"""
    code = 400
    message = "因子 factorKey 已存在"
    error_code = "FACTOR_ALREADY_EXISTS"


class UnknownFactorError(StockBaseException):
    """计算接口收到未知 / 未注册的 factorKey"""
    code = 400
    message = "未知的因子 factorKey"
    error_code = "UNKNOWN_FACTOR"


class FactorNotComputableError(StockBaseException):
    """该因子不可由 engine 实时计算（TUSHARE 基本面因子由 watcher 取数）"""
    code = 400
    message = "该因子不可由 engine 实时计算，基本面因子由 watcher 侧提供"
    error_code = "FACTOR_NOT_COMPUTABLE"


class InvalidParamError(StockBaseException):
    """因子参数缺失或超出 min/max 范围"""
    code = 400
    message = "因子参数非法"
    error_code = "INVALID_PARAM"


class InvalidOutputIndexError(StockBaseException):
    """多输出因子的 output_index 越界"""
    code = 400
    message = "output_index 越界"
    error_code = "INVALID_OUTPUT_INDEX"
