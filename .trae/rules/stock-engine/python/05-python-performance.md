---
alwaysApply: false
description: "当用户涉及 Python 性能优化、内存优化、并发编程、异步IO、代码调优、profiling 等 Python 性能相关场景时触发。适用于优化 Python 代码性能、提升计算速度、减少内存占用、使用并发/并行编程等任务。仅适用于 stock-engine Python 计算服务项目。关键词：Python性能, 性能优化, 内存优化, 并发, 并行, 异步, 调优, profiling, 速度优化"
---
# Python 性能优化规范

> 规范级别：SHOULD（建议遵守）
>
> 适用范围：stock-engine 所有 Python 代码

## 一、性能优化原则

### 1.1 优化优先级

1. **先正确，再优化**：保证正确性是第一位的
2. **先测量，再优化**：使用性能分析工具定位瓶颈
3. **优先优化热点**：20% 的代码消耗 80% 的时间
4. **可读性优先**：除非性能问题严重，否则优先保持代码清晰

### 1.2 性能指标

- **响应时间**：API 接口响应时间 < 200ms（P95）
- **计算速度**：单只股票 5 年日线指标计算 < 50ms
- **内存使用**：单请求内存占用增量 < 100MB

## 二、选择合适的数据结构

### 2.1 列表 vs 数组

| 场景 | 推荐 | 原因 |
|------|------|------|
| 数值计算 | numpy array | 向量化操作，C 实现 |
| 动态增删 | list | 灵活，O(1) 追加 |
| 成员检查 | set | O(1) 查找 |
| 键值映射 | dict | O(1) 查找 |

### 2.2 Numpy 数组使用

```python
# 正确：预先分配数组，避免动态扩容
result = np.zeros(n, dtype=np.float64)

# 正确：使用向量化操作
result = a + b  # 而不是逐个元素相加

# 正确：指定数据类型，减少内存
arr = np.asarray(data, dtype=np.float32)
```

### 2.3 生成器和迭代器

```python
# 大数据量时使用生成器，节省内存
def process_data(data):
    for item in data:
        yield process_item(item)

# 使用迭代器，避免创建中间列表
result = sum(x for x in big_list if x > 0)
```

## 三、函数与方法优化

### 3.1 避免重复计算

❌ **错误做法**：
```python
# 每次调用都重新计算
def get_average():
    return sum(data) / len(data)

# 在循环中调用
for item in items:
    avg = get_average()  # 重复计算
    process(item, avg)
```

✅ **正确做法**：
```python
# 提前计算，复用结果
avg = sum(data) / len(data)
for item in items:
    process(item, avg)
```

### 3.2 使用局部变量

局部变量访问比全局变量和属性访问更快：

```python
# 将常用属性缓存到局部变量
def process_data(self, data):
    logger = self.logger  # 缓存到局部变量
    threshold = self.threshold
    
    for item in data:
        if item > threshold:
            logger.info("超过阈值: %f", item)
```

### 3.3 合理使用缓存

```python
from functools import lru_cache

# 对于相同输入的纯函数，使用缓存
@lru_cache(maxsize=128)
def expensive_calculation(n):
    # 耗时的计算
    return result
```

注意：
- 仅用于纯函数（相同输入总是产生相同输出）
- 注意缓存大小，避免内存泄漏
- 不可变参数才能缓存

## 四、循环优化

### 4.1 避免 Python 层循环

❌ **最慢**：Python for 循环
```python
result = []
for i in range(len(data)):
    result.append(data[i] * 2 + 1)
```

✅ **快**：列表推导
```python
result = [x * 2 + 1 for x in data]
```

✅ **更快**：numpy 向量化
```python
result = data * 2 + 1  # data 是 numpy array
```

### 4.2 内置函数优先

```python
# 使用内置的 sum, max, min, any, all 等
total = sum(numbers)  # 比 for 循环快
max_val = max(numbers)
```

### 4.3 避免循环内的重复操作

```python
# 错误
for item in data:
    if item in target_list:  # 每次都是 O(n)
        process(item)

# 正确
target_set = set(target_list)  # 转成 set，O(1) 查找
for item in data:
    if item in target_set:
        process(item)
```

## 五、字符串优化

### 5.1 字符串拼接

```python
# 错误：循环中 += 拼接（每次创建新字符串）
result = ""
for s in strings:
    result += s

# 正确：使用 join
result = "".join(strings)
```

### 5.2 字符串格式化

性能从高到低：
1. f-string（Python 3.6+）
2. `%` 格式化
3. `str.format()`
4. `string.Template`

```python
# 推荐 f-string
message = f"处理完成，共 {count} 条数据，耗时 {elapsed:.2f}s"
```

注意：日志格式化使用占位符，避免预格式化：
```python
# 正确：使用日志的占位符
logger.info("处理完成，共 %d 条数据，耗时 %.2fs", count, elapsed)
```

## 六、I/O 优化

### 6.1 异步 I/O

I/O 密集型操作必须使用异步：

```python
# 正确：异步 HTTP 请求
async def fetch_data(url):
    async with aiohttp.ClientSession() as session:
        async with session.get(url) as response:
            return await response.json()

# 并发请求
async def fetch_all(urls):
    tasks = [fetch_data(url) for url in urls]
    results = await asyncio.gather(*tasks)
    return results
```

### 6.2 文件读写

```python
# 大文件逐行读取，避免一次性加载
with open("large_file.txt", "r") as f:
    for line in f:
        process(line)

# 使用上下文管理器，确保资源释放
```

### 6.3 批量操作

```python
# 批量处理，减少 I/O 次数
def batch_insert(db, records, batch_size=1000):
    for i in range(0, len(records), batch_size):
        batch = records[i:i+batch_size]
        db.insert_many(batch)
```

## 七、内存优化

### 7.1 大数据处理

```python
# 使用生成器/迭代器，避免全量加载
def process_large_file(filepath):
    with open(filepath) as f:
        for line in f:
            yield process_line(line)

# pandas 分块读取
for chunk in pd.read_csv("large.csv", chunksize=10000):
    process(chunk)
```

### 7.2 及时释放内存

```python
# 删除不再使用的大对象
big_data = load_big_data()
result = process(big_data)
del big_data  # 及时释放

# 触发垃圾回收（必要时）
import gc
gc.collect()
```

### 7.3 使用更节省内存的数据类型

```python
# numpy 选择合适的精度
arr = np.array(data, dtype=np.float32)  # 比 float64 省一半

# pandas 降低精度
df['column'] = df['column'].astype('float32')
df['category_col'] = df['category_col'].astype('category')
```

## 八、并发与并行

### 8.1 并发（I/O 密集型）

```python
import asyncio

# 使用 asyncio.gather 并发执行多个 I/O 任务
async def main():
    tasks = [fetch_data(url) for url in urls]
    results = await asyncio.gather(*tasks)
    return results
```

### 8.2 并行（CPU 密集型）

```python
from multiprocessing import Pool

# 使用多进程并行计算
def parallel_process(data_list):
    with Pool(processes=4) as pool:
        results = pool.map(process_func, data_list)
    return results
```

注意：
- CPU 密集型用多进程（GIL 限制）
- I/O 密集型用异步/多线程
- 注意进程间通信的开销

### 8.3 线程池执行阻塞调用

```python
import asyncio

async def compute_async(data):
    loop = asyncio.get_event_loop()
    # 将 CPU 密集型计算放到线程池
    result = await loop.run_in_executor(None, compute_func, data)
    return result
```

## 九、性能分析工具

### 9.1 代码计时

```python
import time

start = time.perf_counter()
# 你的代码
elapsed = time.perf_counter() - start
logger.debug("耗时: %.3f秒", elapsed)
```

### 9.2 装饰器计时

```python
from functools import wraps
import time

def timer(func):
    @wraps(func)
    def wrapper(*args, **kwargs):
        start = time.perf_counter()
        result = func(*args, **kwargs)
        elapsed = time.perf_counter() - start
        logger.debug("%s 耗时: %.3f秒", func.__name__, elapsed)
        return result
    return wrapper

@timer
def slow_function():
    pass
```

### 9.3 cProfile 性能分析

```python
import cProfile

cProfile.run("your_function()", sort="cumulative")
```

### 9.4 内存分析

```python
# 使用 memory_profiler（需安装）
from memory_profiler import profile

@profile
def my_func():
    pass
```

## 十、常见性能陷阱

### 10.1 不必要的类型转换

```python
# 错误：反复转换
for x in data:
    val = float(x)  # 每次都转
    process(val)

# 正确：一次性转换
data_float = [float(x) for x in data]
for val in data_float:
    process(val)
```

### 10.2 滥用异常处理

```python
# 错误：用异常处理正常流程
for key in keys:
    try:
        value = my_dict[key]
    except KeyError:
        value = default
    process(value)

# 正确：使用 get
for key in keys:
    value = my_dict.get(key, default)
    process(value)
```

### 10.3 过度使用装饰器

装饰器会增加函数调用开销，对性能敏感的热点函数谨慎使用。

### 10.4 正则表达式重复编译

```python
# 错误：循环内编译正则
for text in texts:
    if re.match(pattern, text):
        process(text)

# 正确：预编译
regex = re.compile(pattern)
for text in texts:
    if regex.match(text):
        process(text)
```

## 十一、FastAPI 性能优化

### 11.1 响应模型

- MUST：使用 `response_model`，Pydantic 会优化序列化
- SHOULD：只返回需要的字段，减少数据传输

### 11.2 中间件

- SHOULD：减少不必要的中间件
- MUST：中间件逻辑尽量简单，避免阻塞

### 11.3 依赖注入

- SHOULD：对于计算密集的依赖，考虑缓存结果
- MUST：避免在依赖中执行耗时操作

## 十二、性能优化检查清单

- [ ] 是否使用了 numpy 向量化替代 Python 循环？
- [ ] 是否将 O(n) 查找优化为 O(1)（如 list 转 set）？
- [ ] I/O 操作是否使用了异步？
- [ ] 是否有重复计算可以提取到循环外？
- [ ] 字符串拼接是否用了 join？
- [ ] 是否及时释放了大对象？
- [ ] 是否缓存了不变的计算结果？
- [ ] 是否使用了合适的数据类型减少内存？
