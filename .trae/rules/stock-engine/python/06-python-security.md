---
alwaysApply: false
description: "当用户涉及 Python 安全、输入验证、代码注入防护、路径遍历、敏感数据保护、依赖安全、API 安全等 Python 安全相关场景时触发。适用于编写安全的 Python 代码、防护安全漏洞、检查安全风险、保护敏感数据等任务。仅适用于 stock-engine Python 计算服务项目。关键词：Python安全, 安全, 注入防护, 输入验证, 敏感数据, 依赖安全, API安全, 代码安全"
---
# Python 安全规范

> 规范级别：MUST（必须遵守）
>
> 适用范围：stock-engine 所有 Python 代码

## 一、输入验证

### 1.1 基本规则

- MUST：所有外部输入必须验证，不信任任何来自客户端的数据
- MUST：使用 Pydantic 模型进行请求参数校验
- MUST：验证数据类型、长度、范围、格式

### 1.2 Pydantic 校验

```python
from pydantic import BaseModel, Field, field_validator
from typing import List

class IndicatorCalculateRequest(BaseModel):
    kline_data: List[dict] = Field(..., min_length=1, max_length=10000, description="K线数据")
    indicators: List[str] = Field(default=["macd", "kdj"], description="指标列表")
    
    @field_validator('kline_data')
    @classmethod
    def validate_kline_data(cls, v):
        for item in v:
            if 'close' not in item or 'high' not in item or 'low' not in item:
                raise ValueError('K线数据必须包含 close/high/low 字段')
        return v
```

### 1.3 常用验证规则

| 验证项 | 方法 | 示例 |
|--------|------|------|
| 数值范围 | `ge`, `le`, `gt`, `lt` | `Field(ge=0, le=100)` |
| 字符串长度 | `min_length`, `max_length` | `Field(min_length=6, max_length=6)` |
| 列表长度 | `min_length`, `max_length` | `Field(min_length=1)` |
| 正则匹配 | `pattern` | `Field(pattern=r'^\d{6}$')` |
| 枚举值 | `Enum` 或 `Literal` | `Literal['macd', 'kdj', 'rsi']` |
| 邮箱/URL | `EmailStr`, `HttpUrl` | pydantic 内置类型 |

## 二、代码注入防护

### 2.1 SQL 注入

虽然 Python 服务不直接操作数据库，但通过 API 调用 Java 时仍需注意：

- MUST：禁止拼接 SQL 语句
- MUST：使用参数化查询
- SHOULD：使用 ORM 框架（Java 侧 MyBatis-Plus）

### 2.2 命令注入

```python
import subprocess

# 错误：直接使用字符串拼接
cmd = f"echo {user_input}"
subprocess.call(cmd, shell=True)

# 正确：使用参数列表
subprocess.call(["echo", user_input])
```

- MUST：禁止使用 `shell=True` 执行命令
- MUST：使用参数列表传递命令
- MUST：对用户输入做严格白名单校验

### 2.3 代码注入

- MUST：禁止使用 `eval()` 执行用户输入
- MUST：禁止使用 `exec()` 执行用户输入
- MUST：禁止使用 `compile()` 编译用户输入
- SHOULD：使用 `ast.literal_eval()` 解析字面量（安全）

```python
# 错误：危险！
result = eval(user_input)

# 正确：只解析字面量
import ast
result = ast.literal_eval(user_input)
```

## 三、路径遍历防护

### 3.1 文件操作安全

```python
import os

# 错误：可能导致路径遍历
filepath = os.path.join("/data", user_input)
with open(filepath) as f:
    pass

# 正确：验证路径在允许的目录内
base_dir = os.path.abspath("/data")
filepath = os.path.abspath(os.path.join(base_dir, user_input))

if not filepath.startswith(base_dir):
    raise ValueError("非法路径")
```

- MUST：文件操作前验证路径合法性
- MUST：禁止使用 `..` 等相对路径字符
- SHOULD：使用白名单校验文件名

## 四、敏感数据保护

### 4.1 配置与密钥

- MUST：密钥、Token 等敏感配置从环境变量读取，不硬编码
- MUST：`.env` 文件加入 `.gitignore`，不提交到代码库
- MUST：日志中不得输出敏感信息

```python
# config.py - 正确方式
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    api_key: str  # 从环境变量或 .env 读取
    
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
```

### 4.2 日志脱敏

```python
# 错误：日志中输出敏感信息
logger.info(f"用户登录，token: {token}")

# 正确：脱敏处理
logger.info("用户登录成功")
```

敏感信息清单：
- API Key / Token / 密码
- 身份证号 / 手机号 / 邮箱
- 银行卡号 / 交易密码
- 数据库连接信息

### 4.3 异常信息

- MUST：生产环境不返回详细异常栈追踪
- MUST：错误信息不暴露内部实现细节

```python
# 正确：统一错误响应，不暴露内部细节
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"全局异常: {exc}", exc_info=True)  # 服务端日志记录详细信息
    return JSONResponse(
        status_code=500,
        content={"success": False, "message": "系统内部错误", "code": 500}  # 给用户看的模糊信息
    )
```

## 五、依赖安全

### 5.1 依赖管理

- MUST：使用固定版本号，避免 `*` 或 `>=` 导致意外升级
- SHOULD：定期更新依赖，修复已知漏洞
- SHOULD：使用安全扫描工具检查依赖漏洞

```txt
# requirements.txt - 固定版本号
fastapi==0.115.7
uvicorn==0.41.0
pandas==3.0.3
numpy==2.4.5
```

### 5.2 依赖安全检查

推荐工具：
- `pip-audit`：检查 Python 依赖漏洞
- `safety`：依赖安全扫描
- `bandit`：Python 代码安全扫描

## 六、API 安全

### 6.1 认证与授权

- MUST：内部 API 之间调用需要认证（Token 校验）
- SHOULD：使用 API Key 或 JWT 进行服务间认证
- MUST：管理后台接口必须做权限校验

```python
from fastapi import Header, HTTPException

async def verify_api_key(x_api_key: str = Header(...)):
    expected_key = settings.internal_api_key
    if x_api_key != expected_key:
        raise HTTPException(status_code=401, detail="Invalid API Key")
    return x_api_key
```

### 6.2 速率限制

- SHOULD：对 API 接口设置速率限制，防止滥用
- SHOULD：按 IP / 用户进行限流

```python
# 使用 slowapi 等限流库
from slowapi import Limiter
from slowapi.util import get_remote_address

limiter = Limiter(key_func=get_remote_address)

@router.get("/data")
@limiter.limit("100/minute")
async def get_data(request: Request):
    pass
```

### 6.3 CORS 配置

- MUST：生产环境严格配置允许的域名
- MUST NOT：生产环境使用 `allow_origins=["*"]` 且 `allow_credentials=True`

```python
# 开发环境（宽松）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 生产环境（严格）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://yourdomain.com"],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)
```

## 七、数据安全

### 7.1 数据校验

- MUST：输入数据做类型、范围、长度校验
- MUST：输出数据做脱敏处理（如需要）
- SHOULD：对可疑数据进行日志记录

### 7.2 序列化安全

- MUST：使用 Pydantic 的 `response_model` 控制输出字段
- MUST：不返回敏感字段（密码、Token 等）

```python
class UserResponse(BaseModel):
    id: int
    username: str
    # 不返回 password, token 等敏感字段
```

## 八、常见安全漏洞防护

### 8.1 XSS 防护

虽然 Python 主要提供 API，但返回的数据可能被前端渲染：

- MUST：返回的用户输入内容需要转义（由前端处理，后端可辅助）
- SHOULD：设置正确的 Content-Type

### 8.2 CSRF 防护

- MUST：修改数据的接口（POST/PUT/DELETE）需要 CSRF 防护
- SHOULD：使用 CSRF Token 验证

### 8.3 拒绝服务 (DoS) 防护

- MUST：限制请求体大小
- MUST：限制单次请求的数据量
- SHOULD：设置请求超时

```python
# FastAPI 中限制请求体大小
from fastapi import FastAPI
from starlette.middleware.trustedhost import TrustedHostMiddleware

app = FastAPI()

# 还可以在反向代理（Nginx）层面限制
```

## 九、安全编码检查清单

- [ ] 所有外部输入都经过验证了吗？
- [ ] 有没有使用 eval() / exec() 执行用户输入？
- [ ] 敏感信息有没有硬编码在代码里？
- [ ] 日志中有没有输出敏感数据？
- [ ] 文件操作有没有验证路径合法性？
- [ ] 命令执行有没有使用 shell=True？
- [ ] 依赖版本有没有固定？
- [ ] API 接口有没有认证？
- [ ] 错误信息有没有暴露内部细节？
- [ ] 生产环境 CORS 配置是否严格？

## 十、安全响应

### 10.1 漏洞处理流程

1. 发现漏洞 → 立即评估影响范围
2. 紧急修复 → 优先修复高危漏洞
3. 验证修复 → 确保漏洞已修复
4. 复盘总结 → 分析原因，防止类似问题

### 10.2 安全事件日志

- MUST：记录安全相关事件（登录失败、异常请求等）
- MUST：日志保留足够长时间（至少 30 天）
- SHOULD：对异常行为进行告警
