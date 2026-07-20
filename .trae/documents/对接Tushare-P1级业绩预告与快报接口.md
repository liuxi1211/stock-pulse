# 对接 Tushare P1 级 业绩预告/业绩快报接口 实施计划

> 参考文档：[02-tushare-integration-guide.md](file:///d:/lcProject/stock-pulse/.trae/rules/stock-watcher/business/02-tushare-integration-guide.md) · [03-tushare-interface-summary.md](file:///d:/lcProject/stock-pulse/.trae/rules/stock-watcher/business/03-tushare-interface-summary.md) · [对接Tushare-P0级三大财务报表接口.md](file:///d:/lcProject/stock-pulse/.trae/documents/对接Tushare-P0级三大财务报表接口.md)

## 一、摘要（Summary）

按 02 集成指南的 11 步法，为 stock-watcher 新增对接 Tushare 两个 P1 级业绩类接口：
- **forecast**（业绩预告，[doc_id=45](https://tushare.pro/document/2?doc_id=45)）
- **express**（业绩快报，[doc_id=46](https://tushare.pro/document/2?doc_id=46)）

P0 阶段的三大财报接口（income / balancesheet / cashflow）已落地，本计划**完全沿用 P0 的架构决策与文件模式**，仅在新接口的特有字段（forecast 的 type/summary、express 的 growth_yield 等）和唯一键策略上做差异化处理。

## 二、用户决策（沿用 P0 已确认方案）

| 决策点 | 选定方案 | 说明 |
|---|---|---|
| 字段范围 | **完整全字段** | DTO/DO/表保留 Tushare 文档全部字段（forecast ~11、express ~17） |
| 架构模式 | **独立 Service 模式** | 每个接口独立 `XxxService` + `XxxServiceImpl`，与 P0 三大报表一致 |
| 初始化+任务 | **定时任务 + 全量初始化** | 接入 `BasicDataTask` 周任务 + `InitStep` + `DataInitServiceImpl`，**不**提供 REST Controller |
| forecast 唯一键 | **UNIQUE(ts_code, end_date, ann_date)** | 同一报告期可能多次预告（首次+修正），按公告日区分，保留全部历史用于 point-in-time 回测 |
| express 唯一键 | **UNIQUE(ts_code, end_date)** | 一个报告期仅一条快报；与 P0 财报 `(ts_code, end_date, report_type)` 同构思路 |
| 定时任务时点 | **周日 19:00 / 19:30** | 紧接 P0 的 17:30/18:00/18:30 之后，避免与三大报表同时触发限流 |
| point-in-time 查询 | **提供 `selectLatestAnnouncedBefore`** | 与 P0 一致，给选股中心防 lookahead bias 使用 |

## 三、现状分析（Current State Analysis）

### 3.1 P0 已落地的事实（探索确认）

| 关键事实 | 来源 | 对本计划的影响 |
|---|---|---|
| P0 三大报表（income/balancesheet/cashflow）已按 [对接Tushare-P0级三大财务报表接口.md](file:///d:/lcProject/stock-pulse/.trae/documents/对接Tushare-P0级三大财务报表接口.md) 落地 | 代码 | P1 直接复制 P0 的代码模板与目录结构，仅替换字段 |
| [InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java) 已有 13 项枚举，最后 3 项为 `INCOME`/`BALANCESHEET`/`CASHFLOW` | 探索 | 在 `CASHFLOW` 之后追加 `FORECAST`/`EXPRESS` 两项 |
| [TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) 已有 16 项枚举 | 探索 | 追加 `FORECAST`/`EXPRESS` 两项 |
| [BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java) 已注入 P0 三个 Service，周日 17:30/18:00/18:30 触发 | 探索 | 追加 forecast @19:00、express @19:30 两个 `@Scheduled` |
| [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) `EXECUTION_ORDER` 末尾为 `CASHFLOW`；`CREATE_TABLE_SQL_MYSQL`/`CREATE_TABLE_SQL_SQLITE` 已含 income/balancesheet/cashflow 三张表 | 探索 | 追加 2 个 InitStep、2 个 case、2 个 executeXxx、4 条建表 SQL（MySQL+SQLite 各 2） |
| [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml) 的 `tushare.rate-limit` 已配置 income/balancesheet/cashflow 各 100/min | 探索 | 追加 forecast/express 各 100/min |
| [IncomeMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/IncomeMapper.java) 提供 `insertBatch`/`deleteBatchByKeys`/`selectLatestAnnouncedBefore` 三方法 | 探索 | forecast/express Mapper 完全复制此结构 |
| [IncomeServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/IncomeServiceImpl.java) 用 `BATCH_SIZE=500` + `Lists.partition` + 先 delete 后 insert 批量 upsert | 探索 | forecast/express ServiceImpl 同构 |

### 3.2 Tushare P1 接口关键参数

| 接口 | 输入参数 | 输出字段数 | 备注 |
|---|---|---|---|
| forecast | ts_code / ann_date / start_date / end_date / period 任选 | ~11 | 同一报告期可能多次预告 |
| express | ts_code / ann_date / start_date / end_date / period 任选 | ~17 | 一个报告期一条快报 |

两个接口均：① 不分页（按股票+日期范围返回量小）；② 2000 积分即可调用；③ 与 P0 财报接口同构（按 ann_date 维度而非 trade_date）。

## 四、变更清单（Proposed Changes）

### 4.1 DTO 层（新建 4 个文件）

| 文件 | 说明 |
|---|---|
| `dto/tushare/ForecastDTO.java` | 业绩预告响应 DTO；每个字段 `@JSONField(name="tushare原名")` |
| `dto/tushare/ForecastQueryDTO.java` | 业绩预告请求 DTO；字段：tsCode / annDate / startDate / endDate / period |
| `dto/tushare/ExpressDTO.java` | 业绩快报响应 DTO |
| `dto/tushare/ExpressQueryDTO.java` | 业绩快报请求 DTO；字段：tsCode / annDate / startDate / endDate / period |

**关键规则**（02 文档强调）：
- 每个 Tushare 返回字段**必须**加 `@JSONField(name = "...")`
- 字段名与 [TushareApiEnum](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) 的 `fields` 字符串严格一致

### 4.2 枚举注册（修改 1 个文件）

**文件**：[TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java)

在 `CASHFLOW` 之后追加 2 项（`fields` 字符串需对照 Tushare 官方文档 doc_id=45/46 列表，**实施时严格核对**）：

```java
/** 业绩预告（doc_id=45） */
FORECAST("forecast",
        "ts_code,ann_date,end_date,type,p_change_min,p_change_max,"
        + "net_profit_min,net_profit_max,last_parent_net,summary,change_reason"),

/** 业绩快报（doc_id=46） */
EXPRESS("express",
        "ts_code,ann_date,end_date,revenue,operate_profit,total_profit,n_income,"
        + "total_assets,total_hldr_eqy_exc_min_int,basic_eps,diluted_eps,"
        + "growth_yield,or_growth_yield,yst_net_profit,bm_net_profit,bm_growth_sales,update_flag");
```

> ⚠️ **实施时严格对照官方文档核对字段名**。如果某字段 Tushare 实际返回名称与上面不一致，会导致 DTO 解析为 null。

### 4.3 TushareClient 方法（修改 1 个文件）

**文件**：[TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java)

参照现有 `income(IncomeQueryDTO)` 方法（[TushareClient.java#L163](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java#L163-L166)）：

```java
/** 业绩预告接口（doc_id=45） */
public List<ForecastDTO> forecast(ForecastQueryDTO param) {
    JSONObject params = buildForecastParams(param);
    return query(TushareApiEnum.FORECAST, params, ForecastDTO.class);
}

/** 业绩快报接口（doc_id=46） */
public List<ExpressDTO> express(ExpressQueryDTO param) {
    JSONObject params = buildExpressParams(param);
    return query(TushareApiEnum.EXPRESS, params, ExpressDTO.class);
}

private JSONObject buildForecastParams(ForecastQueryDTO param) {
    JSONObject params = new JSONObject();
    if (param.getTsCode() != null) params.put("ts_code", param.getTsCode());
    if (param.getAnnDate() != null) params.put("ann_date", param.getAnnDate());
    if (param.getStartDate() != null) params.put("start_date", param.getStartDate());
    if (param.getEndDate() != null) params.put("end_date", param.getEndDate());
    if (param.getPeriod() != null) params.put("period", param.getPeriod());
    return params;
}
// buildExpressParams 同构（字段相同：tsCode/annDate/startDate/endDate/period）
```

### 4.4 限流配置（修改 1 个文件）

**文件**：[application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)

在 `tushare.rate-limit` 下的 `cashflow` 之后追加（2000 积分对业绩类接口限流较紧，保守 100/min）：

```yaml
tushare:
  rate-limit:
    # ... 现有配置 ...
    forecast:
      permits-per-minute: 100
    express:
      permits-per-minute: 100
```

### 4.5 数据库层（修改 3 + 新建 4 个文件）

#### 4.5.1 schema.sql 三个文件（修改）

**文件 1**：[schema-sqlite.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql)
**文件 2**：[schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql)
**文件 3**：[schema-mysql-comments.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql-comments.sql)

在每个文件末尾（cashflow 表之后）新增 2 张表，参照 [income](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql#L343-L390) 的结构：

```sql
-- SQLite 示例：forecast 表（保留多次预告历史，唯一键含 ann_date）
CREATE TABLE IF NOT EXISTS forecast (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code           TEXT    NOT NULL,
    ann_date          TEXT,
    end_date          TEXT    NOT NULL,
    type              TEXT,
    p_change_min      REAL,
    p_change_max      REAL,
    net_profit_min    REAL,
    net_profit_max    REAL,
    last_parent_net  REAL,
    summary           TEXT,
    change_reason     TEXT,
    UNIQUE (ts_code, end_date, ann_date)
);
CREATE INDEX IF NOT EXISTS idx_forecast_tscode ON forecast (ts_code, end_date);

-- express 表（一个报告期一条快报，唯一键 (ts_code, end_date)）
CREATE TABLE IF NOT EXISTS express (
    id                              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code                         TEXT    NOT NULL,
    ann_date                        TEXT,
    end_date                        TEXT    NOT NULL,
    revenue                         REAL,
    operate_profit                  REAL,
    total_profit                    REAL,
    n_income                        REAL,
    total_assets                    REAL,
    total_hldr_eqy_exc_min_int      REAL,
    basic_eps                       REAL,
    diluted_eps                     REAL,
    growth_yield                    REAL,
    or_growth_yield                 REAL,
    yst_net_profit                  REAL,
    bm_net_profit                   REAL,
    bm_growth_sales                 REAL,
    update_flag                     TEXT,
    UNIQUE (ts_code, end_date)
);
CREATE INDEX IF NOT EXISTS idx_express_tscode ON express (ts_code, end_date);
```

> MySQL 版本将 `INTEGER PRIMARY KEY AUTOINCREMENT` 改为 `BIGINT AUTO_INCREMENT PRIMARY KEY`，`TEXT` 改为 `VARCHAR(...)`（summary/change_reason 改为 `VARCHAR(1000)` / `VARCHAR(2000)`），`REAL` 改为 `DECIMAL(20,4)`，并加 `INDEX idx_xxx_tscode (ts_code, end_date)`。

#### 4.5.2 DO 类（新建 2 个文件）

| 文件 | 说明 |
|---|---|
| `model/ForecastDO.java` | `@TableName("forecast")`，`@TableId(type=IdType.AUTO)`，字段与表列一一对应，数值用 BigDecimal |
| `model/ExpressDO.java` | `@TableName("express")`，同构 |

**字段类型映射**：
- forecast: type/updateFlag/summary/change_reason 用 `String`；p_change_min/max、net_profit_min/max、last_parent_net 用 `BigDecimal`
- express: update_flag 用 `String`；其余数值字段均用 `BigDecimal`；basic_eps/diluted_eps 用 `BigDecimal`

#### 4.5.3 Mapper 接口（新建 2 个文件）

参照 [IncomeMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/IncomeMapper.java)：

```java
@Mapper
public interface ForecastMapper extends BaseMapper<ForecastDO> {
    int insertBatch(@Param("list") List<ForecastDO> list);
    int deleteBatchByKeys(@Param("list") List<ForecastDO> list);
    ForecastDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}

@Mapper
public interface ExpressMapper extends BaseMapper<ExpressDO> {
    int insertBatch(@Param("list") List<ExpressDO> list);
    int deleteBatchByKeys(@Param("list") List<ExpressDO> list);
    ExpressDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
```

> forecast 的 `deleteBatchByKeys` 按 `(ts_code, end_date, ann_date)` 删除（含 ann_date）；express 的按 `(ts_code, end_date)` 删除（无 ann_date）。

#### 4.5.4 Mapper XML（新建 2 个文件）

参照 [IncomeMapper.xml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/mapper/IncomeMapper.xml)：

- `resources/mapper/ForecastMapper.xml`
- `resources/mapper/ExpressMapper.xml`

每个 XML 包含 4 个语句：
1. `<resultMap>` 完整字段映射（含驼峰转换）
2. `<insert id="insertBatch">` 全字段批量插入
3. `<delete id="deleteBatchByKeys">` 按唯一键批量删除（forecast 含 ann_date，express 不含）
4. `<select id="selectLatestAnnouncedBefore">` point-in-time 查询（`ann_date <= tradeDate` 且非空，按 `end_date DESC LIMIT 1`）

**关键提醒**（02 文档）：`#{item.tsCode}` 写 Java 驼峰字段名（OGNL 表达式），不受 `map-underscore-to-camel-case` 影响。

**forecast 的 `selectLatestAnnouncedBefore` 特殊处理**：由于同一报告期可能有多条预告（首次+修正），需要取「在 tradeDate 之前公告的、报告期最新的一条」：
```xml
<select id="selectLatestAnnouncedBefore" resultMap="forecastMap">
    SELECT * FROM forecast
    WHERE ts_code = #{tsCode}
      AND ann_date IS NOT NULL
      AND ann_date != ''
      AND ann_date &lt;= #{tradeDate}
    ORDER BY end_date DESC, ann_date DESC
    LIMIT 1
</select>
```
> 取最新报告期的最新公告，与 P0 财报的"取最新报告期"语义一致；若需要保留所有修正历史，可由消费方另外查询。

### 4.6 Service 层（新建 4 个文件）

参照 [IncomeService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/IncomeService.java) + [IncomeServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/IncomeServiceImpl.java)：

| 文件 | 关键方法 |
|---|---|
| `service/ForecastService.java` | `int fetchAndSaveForecast(String tsCode, String startDate, String endDate)` + `int fetchAndSaveAllByRange(String startDate, String endDate)` + `List<ForecastDO> queryLocalByTsCode(String tsCode)` + `ForecastDO selectLatestAnnouncedBefore(String tsCode, String tradeDate)` |
| `service/impl/ForecastServiceImpl.java` | 实现：调用 `tushareClient.forecast(...)`，DTO->DO 映射，`Lists.partition(BATCH_SIZE=500)` 批量先 deleteBatchByKeys 后 insertBatch |
| `service/ExpressService.java` | 同构 |
| `service/impl/ExpressServiceImpl.java` | 同构 |

**与 P0 的差异**：
- 唯一键策略不同：forecast 含 `ann_date`、express 不含（影响 deleteBatchByKeys 的 SQL）
- forecast 的 toEntity 需处理 type/summary/change_reason 等 String 字段
- 其余结构完全沿用 P0 模式（独立 Service、不分页、BATCH_SIZE=500、提供 point-in-time 查询）

### 4.7 Controller 层（**不创建**）

用户已选"定时任务+全量初始化"方案，**不提供 REST Controller**。如需手动触发，可通过 `DataInitService.initialize(...)` 接口（已有 Controller）。

### 4.8 初始化流程（修改 2 个文件）

#### 4.8.1 InitStep 枚举（修改 1 个文件）

**文件**：[InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)

在 `CASHFLOW` 之后追加 2 项：

```java
FORECAST("forecast", "业绩预告", "forecast"),
EXPRESS("express", "业绩快报", "express");
```

#### 4.8.2 DataInitServiceImpl（修改 1 个文件）

**文件**：[DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java)

**修改 1**：`EXECUTION_ORDER` 末尾追加 2 项（放在 CASHFLOW 之后）

```java
private static final List<InitStep> EXECUTION_ORDER = List.of(
        InitStep.STOCK_BASIC, InitStep.TRADE_CAL, InitStep.INDEX_WEIGHT, InitStep.SW_INDUSTRY,
        InitStep.DAILY, InitStep.ADJ_FACTOR, InitStep.DIVIDEND,
        InitStep.NAMECHANGE, InitStep.SUSPEND_D, InitStep.STK_LIMIT,
        InitStep.INCOME, InitStep.BALANCESHEET, InitStep.CASHFLOW,
        InitStep.FORECAST, InitStep.EXPRESS);  // 新增 2 项
```

**修改 2**：注入 2 个新 Service

```java
private final ForecastService forecastService;
private final ExpressService expressService;
```

**修改 3**：`doInitialize` 的 switch 中追加 2 个 case

```java
case FORECAST -> executeForecast(stocks);
case EXPRESS -> executeExpress(stocks);
```

**修改 4**：新增 2 个 `executeXxx` 方法（per-stock 模式，参照 [executeIncome](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java#L303-L317)）

```java
private void executeForecast(List<StockBasicDTO> stocks) {
    updateStep("拉取业绩预告数据");
    progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
    String startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
    String endDate = LocalDate.now().format(DATE_FMT);
    for (int i = 0; i < stocks.size(); i++) {
        String tsCode = stocks.get(i).getTsCode();
        try {
            forecastService.fetchAndSaveForecast(tsCode, startDate, endDate);
        } catch (Exception e) {
            log.warn("Failed to fetch forecast for {}: {}", tsCode, e.getMessage(), e);
        }
        reportProgress("拉取业绩预告数据", i + 1, stocks.size());
    }
}
// executeExpress 同构
```

**修改 5**：在 `CREATE_TABLE_SQL_MYSQL` / `CREATE_TABLE_SQL_SQLITE` 两个 Map 中追加 2 张表的建表 SQL（与 schema-*.sql 中一致）

### 4.9 定时任务（修改 1 个文件）

**文件**：[BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java)

注入 2 个新 Service，新增 2 个定时方法（紧接 P0 的 17:30/18:00/18:30 之后，错峰避免限流）：

```java
/** 每周日 19:00 拉取最近 2 年业绩预告 forecast（doc_id=45） */
@Scheduled(cron = "0 0 19 * * SUN")
public void fetchForecast() {
    String endPeriod = LocalDate.now().format(DATE_FMT);
    String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
    log.info("===== BasicDataTask forecast start, [{}~{}] =====", startPeriod, endPeriod);
    try {
        int n = forecastService.fetchAndSaveAllByRange(startPeriod, endPeriod);
        log.info("===== BasicDataTask forecast done, saved={} =====", n);
    } catch (Exception e) {
        log.error("BasicDataTask forecast 失败", e);
    }
}

/** 每周日 19:30 拉取最近 2 年业绩快报 express（doc_id=46） */
@Scheduled(cron = "0 30 19 * * SUN")
public void fetchExpress() {
    String endPeriod = LocalDate.now().format(DATE_FMT);
    String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
    log.info("===== BasicDataTask express start, [{}~{}] =====", startPeriod, endPeriod);
    try {
        int n = expressService.fetchAndSaveAllByRange(startPeriod, endPeriod);
        log.info("===== BasicDataTask express done, saved={} =====", n);
    } catch (Exception e) {
        log.error("BasicDataTask express 失败", e);
    }
}
```

### 4.10 数据消费（可选，不在本次范围）

参照 P0 决策，**本次不修改 ScreenerServiceImpl**。两个接口的 Mapper 已提供 `selectLatestAnnouncedBefore`，后续消费方接入时再改。

## 五、Tushare 字段映射参考

> ⚠️ 以下字段列表基于 Tushare Pro 文档。**实施时务必打开 [doc_id=45](https://tushare.pro/document/2?doc_id=45) / [doc_id=46](https://tushare.pro/document/2?doc_id=46) 逐一核对**，若与下方列表不一致以官方文档为准。

### 5.1 forecast 业绩预告（doc_id=45，~11 字段）

**基础字段**：
- ts_code: 股票代码
- ann_date: 公告日期
- end_date: 报告期

**业绩预告字段**：
- type: 业绩预告类型（预增/预减/扭亏/续盈/续亏/略增/略减/不确定）
- p_change_min: 预告净利润变动幅度下限（%）
- p_change_max: 预告净利润变动幅度上限（%）
- net_profit_min: 预告净利润下限（万元）
- net_profit_max: 预告净利润上限（万元）
- last_parent_net: 上年同期归属母公司净利润
- summary: 业绩预告内容
- change_reason: 业绩变动原因

### 5.2 express 业绩快报（doc_id=46，~17 字段）

**基础字段**：
- ts_code: 股票代码
- ann_date: 公告日期
- end_date: 报告期

**财务数据字段**：
- revenue: 营业收入
- operate_profit: 营业利润
- total_profit: 利润总额
- n_income: 净利润
- total_assets: 总资产
- total_hldr_eqy_exc_min_int: 股东权益合计-不含少数股东权益
- basic_eps: 每股收益（摊薄）
- diluted_eps: 每股收益（摊薄）（稀释）
- growth_yield: 净利润增长率（%）
- or_growth_yield: 营业收入增长率（%）
- yst_net_profit: 上年三季度净利润
- bm_net_profit: 上年全年净利润
- bm_growth_sales: 上年全年营业收入增长率
- update_flag: 更新标识

## 六、实施顺序（按 02 指南 11 步法 × 2 接口并行推进）

按 11 步逐项推进，每个步骤同时处理 2 个接口（同构工作可批量复制修改）：

1. **DTO 定义**：新建 4 个 DTO 文件（2 响应 + 2 请求），每个字段加 `@JSONField`
2. **TushareApiEnum**：追加 FORECAST / EXPRESS 两项
3. **TushareClient**：追加 2 个 public 方法 + 2 个 private buildXxxParams
4. **application.yml**：追加 2 个限流配置（100/min）
5. **数据库层**：
   - 修改 schema-sqlite.sql / schema-mysql.sql / schema-mysql-comments.sql，新增 2 张表
   - 新建 2 个 DO 类（`@TableName`、`@TableId(AUTO)`）
   - 新建 2 个 Mapper 接口（insertBatch / deleteBatchByKeys / selectLatestAnnouncedBefore）
   - 新建 2 个 Mapper XML（resultMap + 3 个语句）
6. **Service 层**：新建 2 对 Service + ServiceImpl（共 4 文件）
7. **InitStep + DataInitServiceImpl**：
   - InitStep 追加 2 项枚举
   - DataInitServiceImpl 修改 EXECUTION_ORDER、注入 2 个 Service、追加 2 个 case、新增 2 个 executeXxx、追加 4 项建表 SQL（MySQL + SQLite 各 2）
8. **BasicDataTask**：注入 2 个 Service，新增 2 个周定时方法（19:00 / 19:30 错峰）
9. **（Mapper 扫描）**：[MyBatisPlusConfig.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java) 已有 `@MapperScan("com.arthur.stock.mapper")`，新 Mapper 自动扫描，**无需修改**
10. **测试验证**：
    - 启动应用，观察 schema.sql 建表日志
    - 执行 `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=forecast,express"`，查询进度
    - 用 DB 工具查 `forecast` / `express` 表是否有数据
    - 验证 `selectLatestAnnouncedBefore` 返回正确（取某股票某日已公告的最近一期）

## 七、假设与决策（Assumptions & Decisions）

| 假设/决策 | 理由 |
|---|---|
| 假设 forecast/express 的字段名与 Tushare 官方文档 doc_id=45/46 一致 | 必须在实施时打开官方文档逐一核对，避免字段名笔误导致解析为 null |
| 决策：forecast 建表主键用 `UNIQUE(ts_code, end_date, ann_date)` | 同一报告期可能有多次预告（首次+修正），按公告日区分；保留全部历史用于 point-in-time 回测；与 P0 财报的 `(ts_code, end_date, report_type)` 同构思路 |
| 决策：express 建表主键用 `UNIQUE(ts_code, end_date)` | 一个报告期仅一条快报；无 ann_date 维度的多次发布 |
| 决策：forecast 的 `selectLatestAnnouncedBefore` 取「最新报告期 + 最新公告日」 | 与 P0 财报"取最新报告期"语义一致；消费方需要全部修正历史时另行查询 |
| 决策：定时任务错峰（19:00 / 19:30），紧接 P0 三大报表之后 | 避免与 P0 同时触发 Tushare 限流；与现有 17:30/18:00/18:30 节奏一致 |
| 决策：不提供 REST Controller | 用户已选；如需手动触发，通过已有 `POST /api/tushare/data-init?steps=...` |
| 决策：本次不修改 ScreenerServiceImpl | 与 P0 一致；后续消费方接入时再改 |
| 假设：forecast/express 在 2000 积分下限流 100/min 足够 | 与 P0 财报接口同档次；如 429 调小 |

## 八、验证步骤（Verification）

| 验证项 | 命令/方法 | 预期 |
|---|---|---|
| 应用启动 | 启动 Spring Boot | 日志无 SQL 异常，2 张新表成功创建 |
| 表结构 | 检查 SQLite/MySQL 中 forecast/express 表 | 字段与 schema 定义一致，索引存在 |
| 单步初始化 | `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=forecast"` | 进度从 RUNNING -> SUCCESS，forecast 表有数据 |
| 两步初始化 | `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=forecast,express"` | 2 张表都有数据 |
| 进度查询 | `curl http://localhost:8080/api/tushare/data-init/status` | 返回当前步骤和进度 |
| DTO 解析正确性 | 日志中无 "field value is null" 类异常；查表发现 type/p_change_min/revenue/n_income 等核心字段非空 | 字段映射正确 |
| forecast 多次预告保留 | SQL：`SELECT * FROM forecast WHERE ts_code='000001.SZ' AND end_date='20231231' ORDER BY ann_date` | 同一报告期可能返回多条（若 Tushare 返回多次预告） |
| point-in-time 查询 | SQL：`SELECT * FROM forecast WHERE ts_code='000001.SZ' AND ann_date <= '20240101' ORDER BY end_date DESC, ann_date DESC LIMIT 1` | 返回 2023 年报或更早的最近一期预告 |
| 限流验证 | 检查 RateLimiter 日志在大量请求时是否触发等待 | 100/min 限流生效 |
| 定时任务 | 等待周日 19:00-19:30 或手动触发 | 日志输出"BasicDataTask forecast/express start/done" |

## 九、新增/修改文件清单（汇总）

**新建 14 个文件**：
- 4 个 DTO：ForecastDTO / ForecastQueryDTO / ExpressDTO / ExpressQueryDTO
- 2 个 DO：ForecastDO / ExpressDO
- 2 个 Mapper 接口：ForecastMapper / ExpressMapper
- 2 个 Mapper XML：ForecastMapper.xml / ExpressMapper.xml
- 4 个 Service：2 接口 + 2 实现

**修改 7 个文件**：
- [TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java)
- [TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java)
- [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)
- [schema-sqlite.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql) / [schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql) / [schema-mysql-comments.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql-comments.sql)（算 3 个但同源 -> 算 3）
- [InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)
- [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java)
- [BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java)

**总计**：14 新建 + 8 修改 = **22 个文件操作**
