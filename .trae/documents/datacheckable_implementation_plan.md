# DataCheckable 接口实现计划

为 daily_basic、stk_limit、moneyflow 三张表实现 DataCheckable 接口，严格仿照 AdjFactorServiceImpl 的代码风格。

## 一、daily_basic 表（3 个检测项）

### 修改文件：
1. **DailyBasicMapper.java** - 添加 2 个方法
2. **DailyBasicMapper.xml** - 添加 2 个 SQL
3. **DailyBasicServiceImpl.java** - implements DataCheckable + checkData()

### 检测项：
- **freshness（ERROR）**：同 adj_factor 模式（工作日检查最新日期是否为今天）
- **mv_validity（ERROR）**：最近30天 total_mv IS NULL OR total_mv <= 0
- **turnover_validity（WARN）**：最近30天 turnover_rate < 0 OR volume_ratio <= 0

### Mapper 方法（已确认 selectLatestTradeDate 已存在）：
- `int countInvalidMv(@Param("startDate") String startDate);`
- `int countInvalidTurnover(@Param("startDate") String startDate);`

---

## 二、stk_limit 表（2 个检测项）

### 修改文件：
1. **StockStkLimitMapper.java** - 添加 2 个方法
2. **StockStkLimitMapper.xml** - 添加 2 个 SQL
3. **StockStkLimitServiceImpl.java** - implements DataCheckable + checkData()

### 检测项：
- **freshness（ERROR）**：同模式
- **price_logic（ERROR）**：最近30天 up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0

### Mapper 方法（需新增 selectLatestTradeDate）：
- `String selectLatestTradeDate();`
- `int countPriceLogicErrors(@Param("startDate") String startDate);`

---

## 三、moneyflow 表（3 个检测项）

### 修改文件：
1. **MoneyflowMapper.java** - 添加 2 个方法
2. **MoneyflowMapper.xml** - 添加 2 个 SQL
3. **MoneyflowServiceImpl.java** - implements DataCheckable + checkData()

### 检测项：
- **freshness（ERROR）**：同模式
- **amount_validity（ERROR）**：最近30天 buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL
- **net_amount_consistency（WARN）**：最近30天，abs(net_mf_amount - (buy_lg_amount + buy_elg_amount - sell_lg_amount - sell_elg_amount)) / NULLIF(abs(net_mf_amount), 0) > 0.1

### Mapper 方法（已确认 selectLatestTradeDate 已存在）：
- `int countInvalidAmount(@Param("startDate") String startDate);`
- `int countNetAmountInconsistency(@Param("startDate") String startDate);`

---

## 四、通用代码风格（严格对齐 AdjFactorServiceImpl）

1. **import 列表**：
   - `com.arthur.stock.constant.InitStep`
   - `com.arthur.stock.dto.governance.CheckLevel`
   - `com.arthur.stock.dto.governance.DataCheckItem`
   - `com.arthur.stock.dto.governance.DataCheckResult`
   - `com.arthur.stock.service.DataCheckable`
   - `java.time.LocalDate`
   - `java.time.format.DateTimeFormatter`
   - `java.util.ArrayList`
   - `java.util.List`

2. **DATE_FMT 常量**：`DateTimeFormatter.ofPattern("yyyyMMdd")`（如类中已有则复用）

3. **checkData() 结构**：
   - try-catch 包裹
   - 先查 totalRows 和 latestDate
   - freshness 检测（工作日判断）
   - 业务检测项：空表时 passed=true, message="表为空，跳过检测"
   - 异常时返回 error 检测项

4. **getTableCode()**：返回对应 InitStep.XXX.getCode()
