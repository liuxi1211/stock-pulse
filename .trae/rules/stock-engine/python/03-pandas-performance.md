---
alwaysApply: false
description: "当用户涉及 Pandas 数据处理、DataFrame 操作、数据清洗、分组聚合、向量化计算、内存优化等 Pandas 相关场景时触发。适用于处理表格数据、优化 Pandas 代码性能、使用向量化操作、减少内存占用等任务。仅适用于 stock-engine Python 计算服务项目。关键词：Pandas, DataFrame, 数据处理, 向量化, 性能优化, 分组聚合, 数据清洗, numpy"
---
# Pandas 性能优化规范

> 规范级别：SHOULD（建议遵守）
>
> 适用范围：stock-engine 中使用 Pandas 的数据处理代码

## 一、核心原则

### 1.1 优先选择

在量化计算场景下，性能优先级：
1. **akquant.talib**（C 实现，最快）
2. **numpy 向量化**（次之）
3. **pandas 向量化**（再次之）
4. **pandas apply**（慢）
5. **Python 循环**（最慢，避免使用）

### 1.2 基本规则

- MUST：优先使用向量化操作，避免 `for` 循环和 `apply`
- MUST：大数据量（>10万行）必须考虑内存占用
- SHOULD：复用中间计算结果，避免重复计算
- SHOULD：使用合适的数据类型减少内存占用

## 二、DataFrame 创建与读取

### 2.1 数据读取优化

```python
# 指定数据类型，减少内存占用
dtype = {
    'ts_code': 'category',
    'trade_date': 'str',
    'open': 'float32',
    'high': 'float32',
    'low': 'float32',
    'close': 'float32',
    'volume': 'float32'
}

df = pd.read_csv('data.csv', dtype=dtype)

# 只读取需要的列
df = pd.read_csv('data.csv', usecols=['ts_code', 'trade_date', 'close'])
```

### 2.2 内存优化

```python
def reduce_mem_usage(df):
    """减少 DataFrame 内存占用"""
    start_mem = df.memory_usage().sum() / 1024**2
    
    for col in df.columns:
        col_type = df[col].dtype
        
        if col_type != object:
            c_min = df[col].min()
            c_max = df[col].max()
            if str(col_type)[:3] == 'int':
                if c_min > np.iinfo(np.int8).min and c_max < np.iinfo(np.int8).max:
                    df[col] = df[col].astype(np.int8)
                elif c_min > np.iinfo(np.int16).min and c_max < np.iinfo(np.int16).max:
                    df[col] = df[col].astype(np.int16)
                elif c_min > np.iinfo(np.int32).min and c_max < np.iinfo(np.int32).max:
                    df[col] = df[col].astype(np.int32)
            else:
                if c_min > np.finfo(np.float32).min and c_max < np.finfo(np.float32).max:
                    df[col] = df[col].astype(np.float32)
        else:
            df[col] = df[col].astype('category')
    
    end_mem = df.memory_usage().sum() / 1024**2
    logger.info(f'内存减少: {start_mem:.2f} MB -> {end_mem:.2f} MB ({100*(start_mem-end_mem)/start_mem:.1f}%)')
    return df
```

## 三、向量化操作

### 3.1 避免循环

❌ **错误做法**（Python 循环）：
```python
# 慢！避免使用
result = []
for i in range(len(df)):
    result.append(df.loc[i, 'close'] - df.loc[i, 'open'])
df['change'] = result
```

✅ **正确做法**（向量化）：
```python
# 快！推荐使用
df['change'] = df['close'] - df['open']
```

### 3.2 条件赋值

❌ **错误做法**（apply + lambda）：
```python
# 慢
df['signal'] = df['close'].apply(lambda x: 1 if x > 10 else 0)
```

✅ **正确做法**（`np.where`）：
```python
# 快
df['signal'] = np.where(df['close'] > 10, 1, 0)
```

✅ **多条件**（`np.select`）：
```python
conditions = [
    (df['close'] > df['ma20']) & (df['volume'] > df['vol_ma5']),
    df['close'] < df['ma20'],
]
choices = [1, -1]
df['signal'] = np.select(conditions, choices, default=0)
```

### 3.3 分箱操作

```python
# 使用 pd.cut 代替循环判断
df['price_level'] = pd.cut(
    df['close'],
    bins=[0, 10, 50, 100, float('inf')],
    labels=['low', 'mid', 'high', 'ultra']
)
```

## 四、分组与聚合

### 4.1 分组优化

❌ **错误做法**（循环分组）：
```python
# 慢
results = {}
for code in df['ts_code'].unique():
    subset = df[df['ts_code'] == code]
    results[code] = subset['close'].mean()
```

✅ **正确做法**（groupby）：
```python
# 快
results = df.groupby('ts_code')['close'].mean()
```

### 4.2 多列聚合

```python
# 一次性聚合多个指标，避免多次 groupby
result = df.groupby('ts_code').agg({
    'close': ['mean', 'std', 'min', 'max'],
    'volume': ['sum', 'mean'],
    'trade_date': 'count'
})
```

### 4.3 分组 transform

```python
# 计算每只股票相对于其均值的偏离
df['close_deviation'] = df.groupby('ts_code')['close'].transform(
    lambda x: (x - x.mean()) / x.std()
)

# 更高效的方式：先计算再合并
stats = df.groupby('ts_code')['close'].agg(['mean', 'std'])
df = df.join(stats, on='ts_code')
df['close_deviation'] = (df['close'] - df['mean']) / df['std']
df = df.drop(columns=['mean', 'std'])
```

## 五、滚动计算

### 5.1 内置滚动函数

```python
# 移动平均
df['ma5'] = df['close'].rolling(window=5).mean()
df['ma10'] = df['close'].rolling(window=10).mean()
df['ma20'] = df['close'].rolling(window=20).mean()

# 滚动标准差
df['volatility'] = df['close'].rolling(window=20).std()

# 滚动最大值/最小值
df['high_20'] = df['high'].rolling(window=20).max()
df['low_20'] = df['low'].rolling(window=20).min()
```

### 5.2 自定义滚动函数

```python
# 简单情况用内置函数，复杂情况才用 apply
# 注意：rolling.apply 较慢，谨慎使用
def custom_indicator(window):
    return window[-1] - window[0]

df['custom'] = df['close'].rolling(window=10).apply(custom_indicator, raw=True)
```

### 5.3 指数加权移动平均

```python
# EMA - 指数加权移动平均
df['ema12'] = df['close'].ewm(span=12, adjust=False).mean()
df['ema26'] = df['close'].ewm(span=26, adjust=False).mean()
```

## 六、合并与连接

### 6.1 选择合适的合并方式

| 场景 | 推荐方式 |
|------|---------|
| 索引相同 | `pd.concat([df1, df2], axis=1)` |
| 按键合并 | `pd.merge(df1, df2, on='key')` |
| 按索引合并 | `df1.join(df2, on='key')` |

### 6.2 合并优化

```python
# 只合并需要的列，减少内存
df_merged = pd.merge(
    df1[['ts_code', 'trade_date', 'close']],
    df2[['ts_code', 'trade_date', 'volume']],
    on=['ts_code', 'trade_date']
)
```

## 七、索引优化

### 7.1 设置合适的索引

```python
# 设置多级索引，加速分组和查找
df = df.set_index(['ts_code', 'trade_date'])
df = df.sort_index()

# 按索引查询（非常快）
df.loc['000001.SZ']
df.loc[('000001.SZ', '20240101'):'20240131']
```

### 7.2 重置索引

```python
# 操作完后重置索引，便于后续处理
df = df.reset_index()
```

## 八、缺失值处理

### 8.1 缺失值检查

```python
# 检查缺失值比例
missing_ratio = df.isnull().sum() / len(df)
```

### 8.2 缺失值填充

```python
# 前向填充（适用于时间序列）
df['close'] = df['close'].ffill()

# 后向填充
df['close'] = df['close'].bfill()

# 用均值/中位数填充
df['value'] = df['value'].fillna(df['value'].mean())

# 线性插值（适用于连续数值）
df['close'] = df['close'].interpolate(method='linear')
```

## 九、性能测试工具

### 9.1 计时装饰器

```python
import time
from functools import wraps

def timing(func):
    @wraps(func)
    def wrapper(*args, **kwargs):
        start = time.time()
        result = func(*args, **kwargs)
        elapsed = time.time() - start
        logger.debug(f"{func.__name__} 耗时: {elapsed:.3f}s")
        return result
    return wrapper
```

### 9.2 Pandas 性能分析

```python
# 查看每列内存占用
print(df.memory_usage(deep=True))

# 查看 DataFrame 信息
df.info(memory_usage='deep')
```

## 十、常见优化模式

### 10.1 按股票分组计算指标

```python
# 方案一：groupby + transform（简洁，中等速度）
df['ma5'] = df.groupby('ts_code')['close'].transform(
    lambda x: x.rolling(5).mean()
)

# 方案二：按股票循环 + 向量化（大数据量更快）
def calculate_ma_grouped(df, window=5):
    result = []
    for code, group in df.groupby('ts_code'):
        group = group.copy()
        group[f'ma{window}'] = group['close'].rolling(window).mean()
        result.append(group)
    return pd.concat(result, ignore_index=True)

# 方案三：akquant.talib（最快，适用于标准指标）
# 直接使用 talib 函数，不需要 pandas
```

### 10.2 日期处理

```python
# 字符串转日期（只转一次）
df['trade_date'] = pd.to_datetime(df['trade_date'], format='%Y%m%d')

# 提取年份/月份
df['year'] = df['trade_date'].dt.year
df['month'] = df['trade_date'].dt.month

# 按日期排序
df = df.sort_values('trade_date')
```

### 10.3 去重与筛选

```python
# 去重
df = df.drop_duplicates(subset=['ts_code', 'trade_date'])

# 条件筛选（向量化）
df_filtered = df[(df['close'] > 0) & (df['volume'] > 0)]
```
