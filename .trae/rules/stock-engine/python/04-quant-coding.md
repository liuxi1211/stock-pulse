---
alwaysApply: false
description: "当用户涉及量化计算、技术指标、因子计算、策略回测、akquant、talib、MACD、KDJ、RSI 等量化金融相关场景时触发。适用于开发量化指标、实现因子计算、编写回测策略、处理K线数据、使用 akquant 等任务。仅适用于 stock-engine Python 计算服务项目。关键词：量化, 指标, 因子, 回测, akquant, talib, MACD, KDJ, RSI, K线, 技术指标"
---
# 量化计算编码规范

> 规范级别：SHOULD（建议遵守）
>
> 适用范围：stock-engine 中所有指标计算、因子计算、回测相关代码

## 一、核心设计原则

### 1.1 Java-Python 职责分离

MUST 严格遵循以下分工：

| 职责 | Java (watcher) | Python (engine) |
|------|---------------|-----------------|
| 数据存储 | ✅ 负责 | ❌ 不直接操作数据库 |
| 因子定义 | ✅ 持有因子配置 | ❌ 不关心因子名称和渲染 |
| 数值计算 | ❌ 不计算 | ✅ 无状态数值计算 |
| 策略回测 | ❌ 不计算 | ✅ akquant 回测引擎 |
| 数据采集 | ✅ Tushare 主数据 | ✅ AKShare 补充数据 |

**核心约定**：Java 持有因子定义（factorKey、参数、输出列），Python 只做无状态数值计算。

### 1.2 因子计算协议

MUST 使用统一的因子计算协议（`services/factor/protocol.py`）：

```python
# 请求结构
FactorComputeRequest:
    requestId: Optional[str]        # 请求ID，用于追踪
    stockCode: Optional[str]        # 股票代码
    ohlcv: List[OhlcvItem]          # K线数据，按时间升序
    factors: List[FactorComputeParam]  # 要计算的因子列表

# 因子参数
FactorComputeParam:
    factorKey: str                  # 因子标识，与Java端一致
    params: Dict[str, Any]          # 因子参数
    requestKey: Optional[str]       # 结果key前缀
    outputLabels: Optional[List[str]] # 多输出时的语义标签

# 统一响应
PythonApiResponse:
    code: int                       # 0=成功，其它=错误
    message: str                    # 消息
    data: Optional[T]               # 数据
    computeMs: Optional[float]      # 计算耗时（毫秒）
    durationMs: Optional[float]     # 总耗时（毫秒）
```

## 二、指标计算规范

### 2.1 技术指标实现

MUST 优先使用 `akquant.talib`（C 实现，性能最优）：

```python
import akquant.talib as _aq
import numpy as np

# 正确：使用 akquant.talib
dif, dea, hist = _aq.MACD(closes, fastperiod=12, slowperiod=26, signalperiod=9)
slowk, slowd = _aq.STOCH(highs, lows, closes, fastk_period=9, slowk_period=3, slowd_period=3)
rsi6 = _aq.RSI(closes, timeperiod=6)
```

❌ **避免使用**：
- pandas rolling 计算（慢）
- Python 循环实现（最慢）
- 自己从头实现标准指标

### 2.2 输入数据要求

- MUST：输入数据按时间升序排列（老在前、新在后）
- MUST：输入数据为 numpy 数组，`dtype=np.float64`
- SHOULD：输入前进行有效性校验

```python
# 数据转换与校验
try:
    closes = np.asarray([float(x["close"]) for x in kline_data], dtype=np.float64)
    highs = np.asarray([float(x["high"]) for x in kline_data], dtype=np.float64)
    lows = np.asarray([float(x["low"]) for x in kline_data], dtype=np.float64)
except Exception as exc:
    raise ValueError(f"输入 K 线缺少 close/high/low: {exc}") from exc
```

### 2.3 输出数据格式

- MUST：输出长度与输入一致，一一对应
- MUST：NaN 值转换为 `None`，便于 JSON 序列化
- SHOULD：使用辅助函数统一处理

```python
def _num(x):
    """把 numpy 标量 / NaN 转成可序列化的 float（NaN 用 None 表示）。"""
    if x is None:
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    if v != v:  # NaN
        return None
    return v
```

## 三、因子注册与发现

### 3.1 因子定义方式

因子以函数形式注册，通过 `factorKey` 映射到具体实现：

```python
# 因子注册表
FACTOR_REGISTRY = {
    "MA": ma_factor,
    "EMA": ema_factor,
    "MACD": macd_factor,
    "KDJ": kdj_factor,
    "RSI": rsi_factor,
    # ...
}
```

### 3.2 因子函数签名

```python
def factor_name(ohlcv: dict, params: dict) -> dict:
    """
    因子计算函数
    
    Args:
        ohlcv: 包含 open/high/low/close/volume 的 numpy 数组字典
        params: 参数字典
    
    Returns:
        计算结果字典，key 为输出列名，value 为 numpy 数组
    """
    pass
```

## 四、数据采集规范

### 4.1 采集器基类

MUST 继承 `BaseCollector` 抽象基类：

```python
from services.data_collector.base_collector import BaseCollector

class DailyCollector(BaseCollector):
    """日线数据采集器"""
    
    async def fetch(self, **kwargs):
        """从数据源拉取原始数据"""
        # 具体实现
        pass
```

### 4.2 采集流程

标准采集流程（基类已提供 `run_collect`）：
1. **拉取数据**：从 AKShare / Tushare 获取原始数据
2. **数据清洗**：处理缺失值、异常值、标准化格式
3. **返回结果**：返回清洗后的数据给 Java 服务

### 4.3 重试机制

MUST 使用基类提供的 `_run_with_retry` 处理网络请求：

```python
async def fetch(self, **kwargs):
    return await self._run_with_retry(
        ak.stock_zh_a_hist,
        symbol=symbol,
        period="daily",
        max_retries=3,
        delay=1.0
    )
```

**重试策略**：
- 最大重试次数：3 次
- 初始延迟：1 秒
- 退避策略：指数退避（每次延迟翻倍）
- 触发条件：网络连接错误、超时错误

## 五、回测规范

### 5.1 回测引擎

MUST 使用 `akquant` 回测引擎，禁止自己实现回测逻辑。

### 5.2 回测数据

- MUST：使用前复权价格进行回测
- MUST：考虑交易成本（手续费、滑点）
- MUST：模拟 T+1 交易规则
- SHOULD：考虑涨跌停限制

### 5.3 回测报告

回测结果应包含：
- **收益指标**：累计收益率、年化收益率、基准收益率
- **风险指标**：最大回撤、夏普比率、波动率
- **交易记录**：每笔交易的买卖点、盈亏
- **净值曲线**：每日净值数据（用于 ECharts 绘图）

## 六、数值计算注意事项

### 6.1 浮点数精度

- MUST：使用 `float64` 进行计算，保证精度
- SHOULD：比较浮点数时使用容差

```python
# 错误
if a == b:
    pass

# 正确
if np.isclose(a, b, rtol=1e-5):
    pass
```

### 6.2 空值处理

- MUST：正确处理 NaN、None、inf
- SHOULD：明确空值的业务含义

```python
# NaN 检查
if np.isnan(value):
    # 处理
    pass

# 无穷大检查
if np.isinf(value):
    # 处理
    pass
```

### 6.3 数组长度一致性

- MUST：计算前检查所有输入数组长度一致
- MUST：输出数组长度与输入一致

```python
assert len(closes) == len(highs) == len(lows), "输入数组长度不一致"
```

## 七、性能优化策略

### 7.1 批量计算

SHOULD 批量计算多个因子，避免重复数据转换：

```python
# 一次转换，多次使用
closes = np.asarray(...)
highs = np.asarray(...)
lows = np.asarray(...)

# 计算所有需要的指标
macd_result = calculate_macd(closes)
kdj_result = calculate_kdj(highs, lows, closes)
rsi_result = calculate_rsi(closes)
```

### 7.2 缓存复用

SHOULD 复用中间结果：

```python
# 例如 RSI(6) 和 RSI(12) 不需要重复计算中间值
# MA(5) 和 MA(10) 可以部分复用
```

### 7.3 计算耗时统计

MUST 记录计算耗时，便于性能监控：

```python
import time

start = time.perf_counter()
result = calculate(...)
elapsed_ms = (time.perf_counter() - start) * 1000
logger.debug("计算耗时: %.2fms", elapsed_ms)
```

## 八、测试规范

### 8.1 单元测试

- MUST：每个指标/因子至少有一个测试用例
- MUST：测试用例包含已知正确结果的对照
- SHOULD：测试边界情况（空数据、单条数据、全零等）

### 8.2 精度验证

- MUST：与标准实现（如 talib、同花顺）对比验证
- SHOULD：精度误差不超过 1e-4

```python
def test_macd():
    closes = np.array([1.0, 2.0, 3.0, ...], dtype=np.float64)
    dif, dea, hist = calculate_macd(closes)
    
    # 与标准实现对比
    expected_dif, expected_dea, expected_hist = talib.MACD(closes)
    assert np.allclose(dif, expected_dif, rtol=1e-4)
    assert np.allclose(dea, expected_dea, rtol=1e-4)
```
