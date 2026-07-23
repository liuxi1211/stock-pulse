# DataCheckable 接口实现计划

## 概述

为 stock-watcher 模块中 adj_factor、daily_basic、stock_stk_limit、stock_moneyflow 这 4 张表的 Service 实现 `DataCheckable` 接口，参考 `IndexDailyServiceImpl` 的 checkData() 方法写法。

## 确认结果

### 文件存在性确认

✅ **全部存在**：

| 表名 | Service 接口 | Service 实现 | Mapper 接口 | Mapper XML |
|------|-------------|-------------|------------|------------|
| adj_factor | AdjFactorService.java | AdjFactorServiceImpl.java | AdjFactorMapper.java | AdjFactorMapper.xml |
| daily_basic | DailyBasicService.java | DailyBasicServiceImpl.java | DailyBasicMapper.java | DailyBasicMapper.xml |
| stock_stk_limit | StockStkLimitService.java | StockStkLimitServiceImpl.java | StockStkLimitMapper.java | StockStkLimitMapper.xml |
| stock_moneyflow | MoneyflowService.java | MoneyflowServiceImpl.java | MoneyflowMapper.java | MoneyflowMapper.xml |

### InitStep 枚举确认

✅ **全部存在**：

| 枚举值 | code | 表名 |
|--------|------|------|
| `ADJ_FACTOR` | `adj_factor` | `adj_factor` |
| `DAILY_BASIC` | `daily_basic` | `daily_basic` |
| `STK_LIMIT` | `stk_limit` | `stock_stk_limit` |
| `MONEYFLOW` | `moneyflow` | `stock_moneyflow` |

### 现有方法确认

| Mapper | selectLatestTradeDate | 备注 |
|--------|----------------------|------|
| AdjFactorMapper | ❌ 不存在 | 需要新增 |
| DailyBasicMapper | ✅ 已存在 | 直接使用 |
| StockStkLimitMapper | ❌ 不存在 | 需要新增 |
| MoneyflowMapper | ✅ 已存在 | 直接使用 |

> `selectCount(null)` 是 MyBatis-Plus `BaseMapper` 自带方法，所有 Mapper 都有，无需额外添加。

---

## 修改文件清单

共修改 **12 个文件**：

### 1. AdjFactor 相关（3 个文件）
- `AdjFactorMapper.java` - 新增 3 个方法
- `AdjFactorMapper.xml` - 新增 3 个 SQL
- `AdjFactorServiceImpl.java` - 实现 DataCheckable 接口

### 2. DailyBasic 相关（3 个文件）
- `DailyBasicMapper.java` - 新增 2 个方法
- `DailyBasicMapper.xml` - 新增 2 个 SQL
- `DailyBasicServiceImpl.java` - 实现 DataCheckable 接口

### 3. StockStkLimit 相关（3 个文件）
- `StockStkLimitMapper.java` - 新增 3 个方法
- `StockStkLimitMapper.xml` - 新增 3 个 SQL
- `StockStkLimitServiceImpl.java` - 实现 DataCheckable 接口

### 4. Moneyflow 相关（3 个文件）
- `MoneyflowMapper.java` - 新增 2 个方法
- `MoneyflowMapper.xml` - 新增 2 个 SQL
- `MoneyflowServiceImpl.java` - 实现 DataCheckable 接口

---

## 详细实现方案

### 一、AdjFactor（复权因子）

#### 1. AdjFactorMapper.java 新增方法

```java
// 取最新交易日
String selectLatestTradeDate();

// 统计最近30天复权因子异常记录数（adj_factor <= 0 OR adj_factor > 10）
int countInvalidFactor(@Param("startDate") String startDate);

// 统计最近7天 daily_quote 中有但 adj_factor 中没有的 ts_code 数量
int countMissingQuoteCodes(@Param("startDate") String startDate);
```

#### 2. AdjFactorMapper.xml 新增 SQL

```xml
<!-- 取最新交易日 -->
<select id="selectLatestTradeDate" resultType="string">
    SELECT MAX(trade_date) FROM adj_factor
</select>

<!-- 复权因子异常记录（adj_factor <= 0 OR adj_factor > 10） -->
<select id="countInvalidFactor" resultType="int">
    SELECT COUNT(*) FROM adj_factor
    WHERE trade_date >= #{startDate}
    AND (adj_factor <= 0 OR adj_factor > 10)
</select>

<!-- 最近7天 daily_quote 中有但 adj_factor 中没有的 ts_code 数量 -->
<select id="countMissingQuoteCodes" resultType="int">
    SELECT COUNT(DISTINCT dq.ts_code) FROM daily_quote dq
    WHERE dq.trade_date >= #{startDate}
    AND dq.ts_code NOT IN (
        SELECT DISTINCT af.ts_code FROM adj_factor af
        WHERE af.trade_date = dq.trade_date
    )
</select>
```

#### 3. AdjFactorServiceImpl.java 修改

- 添加 `implements DataCheckable`
- 实现 `getTableCode()` → 返回 `InitStep.ADJ_FACTOR.getCode()`
- 实现 `checkData()` 方法，包含 3 个检测项：
  1. **freshness**（ERROR）：max(trade_date) < 上一交易日
  2. **factor_validity**（ERROR）：最近30天，adj_factor <= 0 OR adj_factor > 10 的记录数
  3. **quote_coverage**（WARN）：最近7天 daily_quote 中有、但 adj_factor 中没有的 ts_code 数量

---

### 二、DailyBasic（每日基本面）

#### 1. DailyBasicMapper.java 新增方法

```java
// 统计最近30天市值异常记录数（total_mv IS NULL OR total_mv <= 0）
int countInvalidMv(@Param("startDate") String startDate);

// 统计最近30天换手率/量比异常记录数（turnover_rate < 0 OR volume_ratio <= 0）
int countInvalidTurnover(@Param("startDate") String startDate);
```

> `selectLatestTradeDate()` 已存在，无需新增。

#### 2. DailyBasicMapper.xml 新增 SQL

```xml
<!-- 市值异常记录（total_mv IS NULL OR total_mv <= 0） -->
<select id="countInvalidMv" resultType="int">
    SELECT COUNT(*) FROM daily_basic
    WHERE trade_date >= #{startDate}
    AND (total_mv IS NULL OR total_mv <= 0)
</select>

<!-- 换手率/量比异常记录（turnover_rate < 0 OR volume_ratio <= 0） -->
<select id="countInvalidTurnover" resultType="int">
    SELECT COUNT(*) FROM daily_basic
    WHERE trade_date >= #{startDate}
    AND (turnover_rate < 0 OR volume_ratio <= 0)
</select>
```

#### 3. DailyBasicServiceImpl.java 修改

- 添加 `implements DataCheckable`
- 实现 `getTableCode()` → 返回 `InitStep.DAILY_BASIC.getCode()`
- 实现 `checkData()` 方法，包含 3 个检测项：
  1. **freshness**（ERROR）：max(trade_date) < 上一交易日
  2. **mv_validity**（ERROR）：最近30天，total_mv IS NULL OR total_mv <= 0
  3. **turnover_validity**（WARN）：最近30天，turnover_rate < 0 OR volume_ratio <= 0

---

### 三、StockStkLimit（涨跌停价）

#### 1. StockStkLimitMapper.java 新增方法

```java
// 取最新交易日
String selectLatestTradeDate();

// 统计最近30天价格逻辑异常记录数（up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0）
int countPriceLogicError(@Param("startDate") String startDate);
```

#### 2. StockStkLimitMapper.xml 新增 SQL

```xml
<!-- 取最新交易日 -->
<select id="selectLatestTradeDate" resultType="string">
    SELECT MAX(trade_date) FROM stock_stk_limit
</select>

<!-- 价格逻辑异常记录（up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0） -->
<select id="countPriceLogicError" resultType="int">
    SELECT COUNT(*) FROM stock_stk_limit
    WHERE trade_date >= #{startDate}
    AND (up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0)
</select>
```

#### 3. StockStkLimitServiceImpl.java 修改

- 添加 `implements DataCheckable`
- 实现 `getTableCode()` → 返回 `InitStep.STK_LIMIT.getCode()`
- 实现 `checkData()` 方法，包含 2 个检测项：
  1. **freshness**（ERROR）：max(trade_date) < 上一交易日
  2. **price_logic**（ERROR）：最近30天，up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0

---

### 四、Moneyflow（个股资金流向）

#### 1. MoneyflowMapper.java 新增方法

```java
// 统计最近30天金额有效性异常记录数（buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL）
int countInvalidAmount(@Param("startDate") String startDate);

// 统计最近30天净流入与各档位买卖差额偏差 > 10% 的记录数
int countNetAmountInconsistency(@Param("startDate") String startDate);
```

> `selectLatestTradeDate()` 已存在，无需新增。

#### 2. MoneyflowMapper.xml 新增 SQL

```xml
<!-- 金额有效性异常记录（buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL） -->
<select id="countInvalidAmount" resultType="int">
    SELECT COUNT(*) FROM stock_moneyflow
    WHERE trade_date >= #{startDate}
    AND (buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL)
</select>

<!-- 净流入与各档位买卖差额偏差 > 10% 的记录数 -->
<!-- 公式：ABS(net_mf_amount - ((buy_sm_amount + buy_md_amount + buy_lg_amount + buy_elg_amount) - (sell_sm_amount + sell_md_amount + sell_lg_amount + sell_elg_amount))) / NULLIF(ABS(net_mf_amount), 0) > 0.1 -->
<select id="countNetAmountInconsistency" resultType="int">
    SELECT COUNT(*) FROM stock_moneyflow
    WHERE trade_date >= #{startDate}
    AND net_mf_amount IS NOT NULL
    AND ABS(net_mf_amount - (
        (buy_sm_amount + buy_md_amount + buy_lg_amount + buy_elg_amount) -
        (sell_sm_amount + sell_md_amount + sell_lg_amount + sell_elg_amount)
    )) / NULLIF(ABS(net_mf_amount), 0) > 0.1
</select>
```

#### 3. MoneyflowServiceImpl.java 修改

- 添加 `implements DataCheckable`
- 实现 `getTableCode()` → 返回 `InitStep.MONEYFLOW.getCode()`
- 实现 `checkData()` 方法，包含 3 个检测项：
  1. **freshness**（ERROR）：max(trade_date) < 上一交易日
  2. **amount_validity**（ERROR）：最近30天，buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL
  3. **net_amount_consistency**（WARN）：最近30天，净流入与各档位买卖差额偏差 > 10% 的记录数

---

## 代码风格约定

严格参考 `IndexDailyServiceImpl` 的写法：

1. **导入包**：
   - `com.arthur.stock.constant.InitStep`
   - `com.arthur.stock.dto.governance.CheckLevel`
   - `com.arthur.stock.dto.governance.DataCheckItem`
   - `com.arthur.stock.dto.governance.DataCheckResult`
   - `com.arthur.stock.service.DataCheckable`
   - `java.time.LocalDate`
   - `java.time.format.DateTimeFormatter`
   - `java.util.ArrayList`
   - `java.util.List`

2. **DATE_FMT**：`DateTimeFormatter.ofPattern("yyyyMMdd")`

3. **checkData() 结构**：
   - 用 `try-catch` 包裹
   - 先查 `totalRows` 和 `latestDate`
   - 每个检测项用 `DataCheckItem.builder()` 构建
   - 空表时业务检测项显示"表为空，跳过检测"，`passed=true`
   - 异常时返回 error 检测项

4. **freshness 检测**：
   - 取 `LocalDate.now()` 格式化为 `todayStr`
   - 判断是否为工作日（`today.getDayOfWeek().getValue() <= 5`）
   - 工作日且 `latestDate < todayStr` 时不通过
   - 非工作日直接通过

---

## 注意事项

1. **MySQL/SQLite 兼容**：SQL 写法需同时兼容 MySQL 和 SQLite，参考现有 Mapper XML 的写法。
2. **NULLIF 函数**：SQLite 和 MySQL 都支持 `NULLIF` 函数，可安全使用。
3. **空表处理**：所有业务检测项（除 freshness 外）在 `totalRows == 0` 时，显示"表为空，跳过检测"，`passed=true`。
4. **日期格式**：统一使用 `yyyyMMdd` 格式。
5. **Service 接口无需修改**：`DataCheckable` 接口在 ServiceImpl 层实现即可，Service 接口保持不变。
