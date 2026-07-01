---
alwaysApply: false
description: "当用户涉及测试相关工作时触发，包括单元测试、集成测试、测试用例编写、测试覆盖率、自动化测试等场景。适用于编写测试代码、讨论测试策略、验证功能正确性等任务。关键词：测试, test, 单元测试, 集成测试, 用例, 覆盖率"
---
# 测试规范

> 适用于 Stock 项目所有子系统的测试编写与执行规范。

---

## 一、测试分层

### 1.1 测试金字塔 💡 SHOULD

```
        /\
       /  \        E2E 测试（端到端）
      /----\
     /      \      集成测试
    /--------\
   /          \    单元测试
  /------------\
```

- **单元测试**：数量最多，覆盖单个函数/类的逻辑
- **集成测试**：数量中等，验证模块间协作
- **端到端测试**：数量最少，验证核心业务流程

### 1.2 各层定义

| 层级 | 测试对象 | 工具 | 执行速度 | 编写成本 |
|-----|---------|------|---------|---------|
| **单元测试** | 单个类/函数/方法 | JUnit 5 / pytest | 快 | 低 |
| **集成测试** | 模块间协作、数据库、API | Spring Boot Test / httpx | 中 | 中 |
| **端到端测试** | 完整业务流程 | Selenium / 手动 | 慢 | 高 |

---

## 二、单元测试规范

### 2.1 测试范围 ✅ MUST

- 核心业务逻辑必须有单元测试
- 工具类、计算类必须有单元测试
- 复杂条件分支必须有单元测试
- 边界条件必须有单元测试

### 2.2 命名规范 💡 SHOULD

**Java（JUnit 5）**：
```
测试类：<被测类名>Test
测试方法：<被测方法名>_<场景>_<预期结果>

示例：
KlineCalculatorTest.java
  - calculateFrontAdjPrice_normalCase_returnsCorrectPrice()
  - calculateFrontAdjPrice_nullFactor_returnsOriginal()
  - calculateWeekKline_emptyList_returnsEmpty()
```

**Python（pytest）**：
```
测试文件：test_<被测模块名>.py
测试函数：test_<被测函数名>_<场景>_<预期结果>

示例：
test_tech_indicator.py
  - test_calc_ma_normal_case_returns_correct_values()
  - test_calc_macd_insufficient_data_returns_empty()
```

### 2.3 测试结构 💡 SHOULD

采用 **Given-When-Then** 三段式结构：

```java
@Test
void calculateFrontAdjPrice_normalCase_returnsCorrectPrice() {
    // Given - 准备测试数据
    BigDecimal closePrice = new BigDecimal("10.00");
    BigDecimal adjFactor = new BigDecimal("1.5");

    // When - 执行被测逻辑
    BigDecimal result = KlineCalculator.calculateFrontAdjPrice(closePrice, adjFactor);

    // Then - 验证结果
    assertEquals(new BigDecimal("15.00"), result);
}
```

### 2.4 断言原则 ✅ MUST

- 每个测试用例应有明确的断言
- 断言信息清晰，失败时能快速定位问题
- 避免只断言 `assertNotNull` 或 `assertTrue(true)`
- 浮点计算使用带精度的断言

```java
// 不好的
assertTrue(result > 0);

// 好的
assertEquals(new BigDecimal("15.00"), result);
```

### 2.5 测试数据 💡 SHOULD

- 使用有意义的测试数据，避免 magic number
- 边界值：0、null、空集合、最大值、最小值
- 异常值：非法输入、格式错误
- 测试数据与测试逻辑分离

---

## 三、集成测试规范

### 3.1 测试范围 💡 SHOULD

- API 接口的请求/响应
- 数据库 CRUD 操作
- 第三方服务调用（可用 mock）
- 跨模块业务流程

### 3.2 数据库测试 ✅ MUST

- 使用独立的测试数据库，不污染开发/生产数据
- 测试前后清理测试数据
- SQLite 项目可使用内存数据库或临时文件

### 3.3 API 测试 💡 SHOULD

- 验证 HTTP 状态码
- 验证响应体结构和字段
- 验证正常情况和异常情况
- 验证权限控制

---

## 四、测试覆盖率

### 4.1 覆盖率目标 💡 SHOULD

| 层级 | 建议覆盖率 | 说明 |
|-----|-----------|------|
| 核心业务逻辑 | ≥ 80% | 必须覆盖主要分支 |
| 工具类/计算类 | ≥ 90% | 纯逻辑代码应高覆盖 |
| 整体项目 | ≥ 60% | 包含所有代码 |

### 4.2 覆盖率工具 📌 MAY

- **Java**：JaCoCo
- **Python**：pytest-cov / coverage.py

---

## 五、测试最佳实践

### 5.1 测试独立性 ✅ MUST

- 每个测试用例独立运行，不依赖其他测试的执行结果
- 测试之间不共享状态
- 测试执行顺序不影响测试结果

### 5.2 测试速度 💡 SHOULD

- 单元测试应快速执行（单个测试 < 100ms）
- 慢的测试打上标记，单独执行
- 避免不必要的 IO 操作

### 5.3 Mock 使用原则 💡 SHOULD

- 外部依赖（第三方 API、数据库、文件系统）可 mock
- 核心业务逻辑不要 mock
- 不要 mock 被测类本身

### 5.4 测试维护 ✅ MUST

- 代码变更时同步更新测试
- 删除代码时同步删除对应测试
- 失败的测试及时修复，不允许长期存在失败测试

---

## 六、项目特定测试要求

### 6.1 量化计算代码 🔴 重点

- 指标计算（MA、MACD、RSI、KDJ、BOLL 等）必须有测试
- 用已知数据验证计算结果正确性
- 测试边界情况（数据不足、停牌、涨跌停）
- 浮点精度使用合适的误差范围

### 6.2 K 线计算 🔴 重点

- 前复权/后复权计算必须有测试
- 周 K 线/月 K 线聚合必须有测试
- 边界情况：单根K线、空数据、除权除息日

### 6.3 Tushare 数据处理 🟡 关注

- 数据清洗逻辑应有测试
- 异常数据处理应有测试
- 批量 upsert 逻辑应有测试

### 6.4 Python 服务 🟡 关注

- Pydantic 模型验证应有测试
- API 端点应有基本的集成测试
- Pandas 数据处理应有单元测试
