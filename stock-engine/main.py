from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from core.logger import logger
from core.exceptions import StockBaseException
from config import settings
from api.v1 import factor as factor_api
from api.v1 import screener as screener_api

# 创建FastAPI应用
app = FastAPI(
    title="Stock Calculation API",
    version="1.0.0",
    description="""
本地化股票分析系统 - Python 计算服务（骨架）。

业务层（技术指标 / 因子计算 / 策略回测）已清空，待基于
「统一策略配置 Schema」+ akquant 知识库重写后在此挂载。

【硬约束】Python 服务不直接操作数据库，所有数据通过 API 返回给 Java 服务处理。
    """,
    docs_url="/docs",
    redoc_url="/redoc"
)

# 跨域中间件
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 全局异常处理
@app.exception_handler(StockBaseException)
async def stock_exception_handler(request: Request, exc: StockBaseException):
    # 机器可读错误码（如 UNKNOWN_FACTOR）仅当业务异常显式设置时才返回
    content = {
        "success": False,
        "message": exc.message,
        "code": exc.code
    }
    if exc.error_code:
        content["errorCode"] = exc.error_code
    return JSONResponse(
        status_code=exc.code,
        content=content
    )

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"全局异常: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "message": f"系统内部错误: {str(exc)}",
            "code": 500
        }
    )

# 路由注册
app.include_router(factor_api.router)
app.include_router(screener_api.router)

# 启动事件
@app.on_event("startup")
async def startup_event():
    logger.info("=" * 50)
    logger.info("Python计算服务启动中...")
    logger.info(f"运行环境: Python 3.12")
    logger.info(f"监听地址: {settings.host}:{settings.port}")
    logger.info(f"因子库已加载：{len(factor_api.factor_registry.list_factors())} 个因子")

    logger.info("服务启动完成！")
    logger.info(f"API文档地址: http://{settings.host}:{settings.port}/docs")
    logger.info("=" * 50)

# 关闭事件
@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Python计算服务正在关闭...")

# 根路径
@app.get("/", summary="服务状态检查")
async def root():
    return {
        "success": True,
        "message": "Stock Calculation Service is running",
        "version": "1.0.0",
        "docs": "/docs"
    }

# 健康检查
@app.get("/health", summary="健康检查")
async def health_check():
    return {
        "success": True,
        "status": "healthy"
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=True,
        log_level=settings.log_level.lower()
    )
