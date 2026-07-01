---
alwaysApply: false
description: "当用户涉及 FastAPI 开发、API 路由设计、Pydantic 模型、依赖注入、异常处理、异步编程等场景时触发。适用于开发 FastAPI 接口、设计 API 路由、使用 Pydantic 校验、配置 FastAPI 应用等任务。仅适用于 stock-engine Python 计算服务项目。关键词：FastAPI, API, 路由, Pydantic, 依赖注入, 异步, 接口, Python后端"
---
# FastAPI 最佳实践

> 规范级别：SHOULD（建议遵守）
>
> 适用范围：stock-engine 的 API 层

## 一、应用架构

### 1.1 分层原则

MUST 遵循分层架构，职责清晰分离：

```
api/           # 路由层：参数接收 + 响应封装 + 调用 service
services/      # 业务服务层：核心业务逻辑
models/        # 数据模型层：Pydantic Schema + 领域模型
core/          # 核心组件：异常、日志、配置
utils/         # 工具函数
```

**路由层职责**：
- 参数校验（Pydantic）
- 调用 service
- 封装统一响应
- 异常处理与日志

**服务层职责**：
- 核心业务逻辑
- 数据处理与计算
- 不直接操作 HTTP 请求/响应

### 1.2 应用入口 (main.py)

MUST 包含以下标准组件：

```python
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from core.logger import logger
from core.exceptions import StockBaseException
from config import settings

app = FastAPI(
    title="Stock Calculation API",
    version="1.0.0",
    description="...",
    docs_url="/docs",
    redoc_url="/redoc"
)

# CORS 中间件
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
        content={"success": False, "message": exc.message, "code": exc.code}
    )

# 注册路由
app.include_router(data.router, prefix="/api/v1")
```

## 二、路由设计

### 2.1 路由组织

- MUST：使用 `APIRouter` 组织路由，按业务模块分文件
- MUST：路由前缀统一，版本化管理（`/api/v1/xxx`）
- SHOULD：每个模块一个 router 文件，放在 `api/v1/` 下

```python
# api/v1/quote.py
from fastapi import APIRouter

router = APIRouter(prefix="/quote", tags=["技术指标"])

@router.post("/calculate_indicators", summary="计算技术指标")
async def calculate_indicators(request: IndicatorCalculateRequest):
    ...
```

### 2.2 URL 命名

- MUST：使用名词复数形式，RESTful 风格
- MUST：全小写，连字符 `-` 分隔（URL 层面），Python 路由路径用下划线
- SHOULD：避免动词出现在 URL 中（动作由 HTTP Method 表达）

```
GET    /api/v1/quotes          # 获取行情列表
POST   /api/v1/quote/calculate # 计算指标（动作型接口可例外）
GET    /api/v1/factors         # 获取因子列表
```

### 2.3 HTTP 方法使用

| 方法 | 用途 | 幂等性 |
|------|------|--------|
| GET | 查询资源 | 是 |
| POST | 创建资源 / 执行计算 | 否 |
| PUT | 全量更新资源 | 是 |
| PATCH | 部分更新资源 | 否 |
| DELETE | 删除资源 | 是 |

## 三、请求与响应

### 3.1 请求模型

- MUST：使用 Pydantic `BaseModel` 定义请求体
- MUST：使用 `Field` 添加描述和校验规则
- SHOULD：合理设置默认值和可选字段

```python
from pydantic import BaseModel, Field
from typing import List, Dict, Any

class IndicatorCalculateRequest(BaseModel):
    """技术指标计算请求模型"""
    kline_data: List[Dict[str, Any]] = Field(..., description="K线数据列表")
    indicators: List[str] = Field(default=["macd", "kdj"], description="要计算的指标列表")
```

**常用校验**：
- 数值范围：`Field(..., ge=0, le=100)`
- 字符串长度：`Field(..., min_length=1, max_length=100)`
- 列表长度：`Field(..., min_length=1)`
- 正则匹配：`Field(..., pattern=r'^\d{6}$')`

### 3.2 响应模型

- MUST：统一使用标准响应格式
- MUST：使用 `response_model` 指定响应模型
- SHOULD：响应模型与请求模型分离，避免暴露敏感字段

```python
class IndicatorCalculateResponse(BaseModel):
    """技术指标计算响应模型"""
    success: bool
    message: str
    data: List[KlineItem]
```

**标准响应格式**：
```json
{
  "success": true,
  "message": "操作成功",
  "data": {},
  "code": 200
}
```

### 3.3 状态码

- MUST：正确使用 HTTP 状态码
- SHOULD：业务错误也通过标准响应体返回，HTTP 状态码为 200

| 场景 | HTTP 状态码 | 说明 |
|------|------------|------|
| 成功 | 200 | 正常响应 |
| 创建成功 | 201 | POST 创建资源 |
| 参数错误 | 400 | 请求参数不合法 |
| 未认证 | 401 | 需要登录 |
| 无权限 | 403 | 已登录但无权限 |
| 资源不存在 | 404 | 请求的资源不存在 |
| 服务器错误 | 500 | 内部异常 |

## 四、依赖注入

### 4.1 常用依赖

SHOULD 使用 FastAPI 的依赖注入系统管理通用逻辑：

```python
# api/dependencies.py
from fastapi import Header, HTTPException

async def verify_token(x_token: str = Header(...)):
    if x_token != "expected-token":
        raise HTTPException(status_code=401, detail="Invalid token")
    return x_token
```

### 4.2 依赖使用

```python
from fastapi import Depends
from api.dependencies import verify_token

@router.get("/protected")
async def protected_route(token: str = Depends(verify_token)):
    return {"message": "success"}
```

## 五、异常处理

### 5.1 全局异常处理

MUST 在 `main.py` 中注册全局异常处理器：

```python
# 业务异常
@app.exception_handler(StockBaseException)
async def stock_exception_handler(request: Request, exc: StockBaseException):
    return JSONResponse(
        status_code=exc.code,
        content={"success": False, "message": exc.message, "code": exc.code}
    )

# 兜底异常
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"全局异常: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"success": False, "message": f"系统内部错误: {str(exc)}", "code": 500}
    )
```

### 5.2 路由层异常

- MUST：路由层捕获业务异常，转换为标准响应
- MUST：记录异常日志（含堆栈）
- SHOULD：使用 `HTTPException` 或自定义异常

```python
@router.post("/calculate_indicators")
async def calculate_indicators(request: IndicatorCalculateRequest):
    try:
        service = TechIndicatorService()
        data = await service.calculate_indicators_for_kline(request.kline_data)
        return IndicatorCalculateResponse(success=True, message="计算完成", data=data)
    except Exception as e:
        logger.error(f"计算技术指标失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"计算失败: {str(e)}")
```

## 六、配置管理

### 6.1 配置方式

MUST 使用 `pydantic-settings` 管理配置，支持 `.env` 文件：

```python
# config.py
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    host: str = "127.0.0.1"
    port: int = 8085
    log_level: str = "INFO"
    data_history_years: int = 5

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

settings = Settings()
```

### 6.2 配置使用

```python
from config import settings

uvicorn.run("main:app", host=settings.host, port=settings.port)
```

## 七、异步编程

### 7.1 异步使用原则

- MUST：I/O 密集型操作使用 `async/await`
- SHOULD：CPU 密集型计算考虑使用线程池或进程池
- MUST NOT：在 async 函数中执行阻塞调用（如 `time.sleep()`）

```python
import asyncio

# 正确
async def fetch_data():
    await asyncio.sleep(1)
    return data

# 错误
async def bad_example():
    import time
    time.sleep(1)  # 阻塞事件循环
```

### 7.2 并发处理

```python
# 并发执行多个任务
async def process_multiple(symbols):
    tasks = [fetch_one(symbol) for symbol in symbols]
    results = await asyncio.gather(*tasks)
    return results
```

## 八、API 文档

### 8.1 自动文档

FastAPI 自动生成 Swagger UI 和 ReDoc：
- Swagger UI：`/docs`
- ReDoc：`/redoc`

### 8.2 文档增强

SHOULD 为路由添加完整的文档信息：

```python
@router.post(
    "/calculate_indicators",
    response_model=IndicatorCalculateResponse,
    summary="计算技术指标",
    description="为K线数据计算MACD、KDJ、RSI等常用技术指标",
    responses={
        200: {"description": "计算成功"},
        500: {"description": "计算失败"}
    }
)
async def calculate_indicators(request: IndicatorCalculateRequest):
    ...
```
