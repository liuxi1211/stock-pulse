from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from core.logger import logger
from core.exceptions import StockBaseException
from api.v1 import quote, backtest, compute  # noqa: F401
from config import settings

# 创建FastAPI应用
app = FastAPI(
    title="Stock Calculation API",
    version="1.0.0",
    description="""
本地化股票分析系统 - Python计算服务API

负责量化计算、策略回测等核心能力，
提供REST API供Java业务服务调用。

【重要】Python服务不直接操作数据库，所有数据通过API返回给Java服务处理。
【新协议】/python/v1/* —— Java 持有因子定义，Python 只做无状态数值计算。
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
    return JSONResponse(
        status_code=exc.code,
        content={
            "success": False,
            "message": exc.message,
            "code": exc.code
        }
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

# 注册路由
app.include_router(quote.router, prefix="/api/v1")
app.include_router(backtest.router, prefix="/api/v1")
app.include_router(compute.router)

# 启动事件
@app.on_event("startup")
async def startup_event():
    logger.info("=" * 50)
    logger.info("Python计算服务启动中...")
    logger.info(f"运行环境: Python 3.12")
    logger.info(f"监听地址: {settings.host}:{settings.port}")
    
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
