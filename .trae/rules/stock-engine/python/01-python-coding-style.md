---
alwaysApply: false
description: "当用户涉及 Python 代码编写、Python 代码风格、PEP 8、命名规范、类型注解、Python 编码规范等场景时触发。适用于编写 Python 代码、重构 Python 代码、检查 Python 代码风格、遵循项目 Python 编码规范等任务。仅适用于 stock-engine Python 计算服务项目。关键词：Python, 代码风格, PEP8, 命名规范, 类型注解, 编码规范, Python代码"
---
# Python 编码风格规范

> 规范级别：MUST（必须遵守）
>
> 适用范围：stock-engine 及所有 Python 代码

## 一、命名规范

### 1.1 基本约定

| 类型 | 规范 | 示例 |
|------|------|------|
| 模块/包名 | 全小写，下划线分隔 | `tech_indicator.py`, `data_collector` |
| 类名 | 大驼峰（PascalCase） | `TechIndicatorService`, `StockBaseException` |
| 函数/方法名 | 全小写，下划线分隔 | `calculate_indicators_for_kline` |
| 变量名 | 全小写，下划线分隔 | `kline_data`, `close_prices` |
| 常量名 | 全大写，下划线分隔 | `MAX_RETRY_COUNT`, `DEFAULT_TIME_PERIOD` |
| 私有成员 | 单下划线前缀 | `_internal_state`, `_num()` |

### 1.2 特定命名约定

- **布尔变量**：使用 `is_` / `has_` / `can_` 前缀

  ```python
  is_valid = True
  has_data = False
  can_calculate = True
  ```

- **集合变量**：使用复数形式或 `_list` / `_dict` / `_set` 后缀

  ```python
  kline_list = []
  stock_dict = {}
  symbol_set = set()
  ```

- **函数返回值**：动词开头，清晰表达用途

  ```python
  def get_close_prices(kline_data): ...
  def calculate_macd(closes): ...
  def validate_input(data): ...
  ```

## 二、代码格式

### 2.1 缩进与行宽

- **缩进**：4 个空格，禁止使用 Tab
- **行宽**：最大 120 字符
- **空行**：
  - 顶层函数/类之间空 2 行
  - 类内方法之间空 1 行
  - 逻辑块之间空 1 行

### 2.2 导入规范

**导入顺序**（每组之间空 1 行）：
1. 标准库导入
2. 第三方库导入
3. 本地应用/库导入

```python
import sys
from typing import List, Dict, Optional

import numpy as np
import pandas as pd
from fastapi import APIRouter

from core.logger import logger
from services.indicator.tech_indicator import TechIndicatorService
```

**导入规则**：
- MUST：使用绝对导入，禁止相对导入（`__init__.py` 除外）
- MUST：避免通配符导入（`from module import *`）
- SHOULD：导入较长时使用括号换行
- MAY：使用 `import numpy as np` / `import pandas as pd` 等约定俗成的别名

### 2.3 注释与文档字符串

#### 模块级 docstring

```python
"""技术指标计算服务（主力：akquant.talib）。

为 ``api/v1/quote.py`` 的 `/quote/calculate_indicators` 提供实现。
直接用 akquant 计算 MACD / KDJ / RSI，不再依赖 pandas。
"""
```

#### 类 docstring

```python
class TechIndicatorService:
    """为 K 线列表计算 MACD / KDJ / RSI 并返回同一长度的 dict 列表。"""
```

#### 函数/方法 docstring

```python
async def calculate_indicators_for_kline(self, kline_data):
    """
    为K线数据计算技术指标
    
    - 接收 K 线数据作为输入
    - 计算 MACD、KDJ、RSI 等常用指标
    - Python 服务不操作数据库
    """
```

#### 行内注释

- MUST：`#` 后加一个空格
- SHOULD：注释放在代码上方，而非行尾
- MUST NOT：注释解释"做了什么"，应解释"为什么这么做"

```python
# NaN 用 None 表示，便于 JSON 序列化
if v != v:
    return None
```

## 三、类型注解

### 3.1 基本规则

- MUST：所有公共 API 函数/方法必须添加类型注解
- MUST：使用 `typing` 模块或 Python 3.10+ 内置类型语法
- SHOULD：为复杂数据结构定义 `TypedDict` 或 Pydantic 模型

### 3.2 示例

```python
from typing import List, Dict, Optional, Any
from pydantic import BaseModel, Field

class KlineItem(BaseModel):
    """K线数据项"""
    trade_date: str
    open: float
    close: float
    high: float
    low: float
    volume: float
    amount: Optional[float] = None

def calculate_indicators(kline_data: List[Dict[str, Any]]) -> List[KlineItem]:
    ...
```

### 3.3 特殊类型

- **可选值**：`Optional[T]` 而非 `T | None`（保持与旧版 Python 兼容时）
- **空返回**：显式标注 `-> None`
- **异步函数**：`async def` + 正常返回类型注解

## 四、异常处理

### 4.1 自定义异常

- MUST：所有业务异常继承自 `StockBaseException`
- MUST：异常类放在 `core/exceptions.py` 中统一管理
- SHOULD：每个异常定义默认的 `code` 和 `message`

```python
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
```

### 4.2 异常抛出与捕获

- MUST：使用 `raise XxxException(...)` 抛出业务异常
- MUST：禁止裸 `except:`，必须指定异常类型
- MUST：捕获异常后必须记录日志或重新抛出
- SHOULD：使用 `raise ... from exc` 保留异常链

```python
try:
    closes = np.asarray([float(x["close"]) for x in kline_data], dtype=np.float64)
except Exception as exc:
    raise ValueError(f"输入 K 线缺少 close/high/low: {exc}") from exc
```

## 五、日志规范

### 5.1 日志使用

- MUST：使用 `core.logger.logger`（loguru），禁止 `print()`
- MUST：日志级别选择恰当：
  - `DEBUG`：调试信息，开发用
  - `INFO`：正常业务流程信息
  - `WARNING`：警告信息，不影响主流程
  - `ERROR`：错误信息，影响功能但服务仍可用
  - `CRITICAL`：致命错误，服务不可用

### 5.2 日志格式

- MUST：日志消息简洁明了，包含关键上下文
- SHOULD：使用占位符格式化，避免 f-string 预格式化（性能考虑）
- MUST：异常必须使用 `exc_info=True` 记录完整栈追踪

```python
logger.info("技术指标计算完成，共 %d 条", n)
logger.error(f"计算技术指标失败: {e}", exc_info=True)
```

## 六、文件结构

### 6.1 目录结构约定

```
stock-engine/
├── api/                    # API 路由层
│   ├── v1/                 # v1 版本路由
│   └── dependencies.py     # 路由依赖
├── core/                   # 核心组件
│   ├── exceptions.py       # 自定义异常
│   └── logger.py           # 日志配置
├── models/                 # 数据模型
│   ├── domain/             # 领域模型
│   └── schemas/            # Pydantic Schema
├── services/               # 业务服务层
│   ├── indicator/          # 指标计算服务
│   ├── factor/             # 因子服务
│   └── data_collector/     # 数据采集服务
├── utils/                  # 工具函数
├── tests/                  # 测试
├── config.py               # 配置
└── main.py                 # 入口
```

### 6.2 文件命名

- MUST：模块文件全小写，下划线分隔
- MUST：测试文件以 `test_` 开头
- SHOULD：一个文件一个主要职责，避免文件过大（> 500 行考虑拆分）
