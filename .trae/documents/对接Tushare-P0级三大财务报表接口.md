# 对接 Tushare P0 级三大财务报表接口 实施计划

> 参考文档：[02-tushare-integration-guide.md](file:///d:/lcProject/stock-pulse/.trae/rules/stock-watcher/business/02-tushare-integration-guide.md) · [03-tushare-interface-summary.md](file:///d:/lcProject/stock-pulse/.trae/rules/stock-watcher/business/03-tushare-interface-summary.md)

## 一、摘要（Summary）

按 02 集成指南的 11 步法，为 stock-watcher 新增对接 Tushare 三个 P0 级财报接口：
- **income**（利润表，[doc_id=33](https://tushare.pro/document/2?doc_id=33)）
- **balancesheet**（资产负债表，[doc_id=36](https://tushare.pro/document/2?doc_id=36)）
- **cashflow**（现金流量表，[doc_id=44](https://tushare.pro/document/2?doc_id=44)）

## 二、用户决策（已确认）

| 决策点 | 选定方案 | 说明 |
|---|---|---|
| 字段范围 | **完整全字段** | DTO/DO/表保留 Tushare 文档全部字段（income ~44、balancesheet ~85、cashflow ~45） |
| 架构模式 | **独立 Service 模式** | 每个接口独立 `XxxService` + `XxxServiceImpl`，与 daily 模式一致 |
| 初始化+任务 | **定时任务 + 全量初始化** | 接入 `BasicDataTask` 周任务 + `InitStep` + `DataInitServiceImpl`，**不**提供 REST Controller |

## 三、现状分析（Current State Analysis）

### 3.1 现有架构关键点（探索发现）

| 关键事实 | 来源 | 对本计划的影响 |
|---|---|---|
| `fina_indicator` 当前**寄居**在 [BasicDataService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/BasicDataService.java) / [BasicDataServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/BasicDataServiceImpl.java) | 探索 | 三大报表改为独立 Service，不沿用寄居模式 |
| `fina_indicator` 没有独立 Controller、不在 `InitStep` 中、由 [BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java) 周日 17:00 触发 | 探索 | 本计划要把三大报表**接入 `InitStep` + `DataInitServiceImpl`**（用户已选），与 fina_indicator 略有差异；定时任务仍放 BasicDataTask |
| `daily` 模式是"新接口最佳参考"：[DailyQuoteService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/DailyQuoteService.java) + impl + [DailyQuoteMapper.xml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/mapper/DailyQuoteMapper.xml) + [DailyQueryDTO.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/dto/tushare/DailyQueryDTO.java) | 探索 | 三大报表的 DTO/Service/Mapper 全部参照 daily 模式（但财报按 `ann_date`/`end_date` 维度，无需分页） |
| 数据库双库支持：[schema-sqlite.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql) + [schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql) + [schema-mysql-comments.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql-comments.sql) | 探索 | 三张表都要在 3 个 schema 文件同步新增 |
| [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) 有 `CREATE_TABLE_SQL_MYSQL` / `CREATE_TABLE_SQL_SQLITE` 两个 Map | 探索 | 需在这两个 Map 中各加 3 张表的建表 SQL |
| [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml) 的 `tushare.rate-limit` 未配置 `fina_indicator`/`daily_basic` | 探索 | 三大报表必须显式配置限流（Tushare 财报接口 2000 积分限流较紧） |
| 现有 [TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) 有 13 项枚举 | 探索 | 追加 INCOME/BALANCESHEET/CASHFLOW 三项 |
| [FinaIndicatorMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/FinaIndicatorMapper.java) 的 `selectLatestAnnouncedBefore` 被 `ScreenerServiceImpl` 消费 | 探索 | 三大报表同样提供 point-in-time 查询方法（防 lookahead bias） |
| 数据初始化使用虚拟线程并发、按 `EXECUTION_ORDER` 顺序执行 | 探索 | 三大报表作为 per-stock 拉取步骤，放在 `STK_LIMIT` 之后 |

### 3.2 Tushare 接口关键参数

三大接口调用方式完全同构：
- 必传：`ts_code`（按股票）或 `period`（按报告期）至少其一
- 可选：`ann_date`、`start_date`、`end_date`、`report_type`（1=合并报表 / 2=单季合并 / 3=调整单季 / 4=调整合并 / 5=调整前 / 6=调整后）、`comp_type`（1=一般工商企业 / 2=证券 / 3=保险 / 4=银行）
- 单次返回上限 6000 行，但按股票+日期范围通常 < 100 条 → **不分页**（与 fina_indicator 一致）

## 四、变更清单（Proposed Changes）

### 4.1 DTO 层（新建 6 个文件）

| 文件 | 说明 |
|---|---|
| `dto/tushare/IncomeDTO.java` | 利润表响应 DTO；每个字段 `@JSONField(name="tushare原名")` |
| `dto/tushare/IncomeQueryDTO.java` | 利润表请求 DTO；字段：tsCode / startDate / endDate / reportType / compType（无分页） |
| `dto/tushare/BalancesheetDTO.java` | 资产负债表响应 DTO |
| `dto/tushare/BalancesheetQueryDTO.java` | 资产负债表请求 DTO |
| `dto/tushare/CashflowDTO.java` | 现金流量表响应 DTO |
| `dto/tushare/CashflowQueryDTO.java` | 现金流量表请求 DTO |

**关键规则**（02 文档强调）：
- 每个 Tushare 返回字段**必须**加 `@JSONField(name = "...")`，否则 FastJSON2 解析为 null
- 字段名与 [TushareApiEnum](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) 的 `fields` 字符串严格一致

### 4.2 枚举注册（修改 1 个文件）

**文件**：[TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java)

追加 3 项（`fields` 字符串需对照 Tushare 官方文档 doc_id=33/36/44 列表，**实施时严格核对**）：

```java
/** 利润表（doc_id=33） */
INCOME("income",
        "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,basic_eps,diluted_eps,"
        + "total_revenue,revenue,total_cogs,operate_cost,operate_profit,non_oper_income,non_oper_exp,"
        + "total_profit,n_income,n_income_attr_p,minority_interest,adjust_profit,income_tax,"
        + "n_income_yoy,dt_profit_yoy,sell_exp,admin_exp,financial_exp,rd_exp,impair_end_invest,"
        + "impair_end_oper,invest_income,invest_income_inc,invest_income_dec,fairvalue_change_income,"
        + "exchange_gain,asset_dispose_income,other_income,operate_n_income,credit_impair_loss,"
        + "asset_impair_loss,bbit,bbit_yoy,operate_profit_income_yoy,update_flag"),

/** 资产负债表（doc_id=36） */
BALANCESHEET("balancesheet",
        "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,monetary_funds,accounts_rece,"
        + "notes_rece,accounts_rece_fin,other_rece,prepayment,dividends_rece,int_rece,inventories,"
        + "non_current_assets_in_1_yr,other_current_assets,total_current_assets,equity_joint_cap,"
        + "lt_receivable,eqt_invest,inv_real_estate,fix_assets_nca,cip,construction_materials,intang_assets,"
        + "goodwill,lt_amort_deferred_exp,defer_tax_assets,other_non_current_assets,total_non_current_assets,"
        + "total_assets,lt_borr,notes_payable,accounts_payable,accounts_payable_fin,prepayment_receivables,"
        + "wage_payable,taxes_surcharges,other_payable,non_current_liab_in_1_yr,other_current_liab,"
        + "total_current_liab,long_term_borr,ppayable_bonds,long_term_payable,specific_payable,"
        + "estimated_liab,defer_tax_liab,defer_inc_non_curr_liab,other_non_current_liab,"
        + "total_non_current_liab,total_liab,share_capital,capital_reserve,treasury_stock,"
        + "specific_reserves,surplus_reserve,general_risk_reserve,undistributed_profit,"
        + "equity_parent_company,minority_interest,total_equity,total_liab_equity,"
        + "accounts_rece_decr,accounts_rece_fin_decr,minority_interest_inc,minority_interest_dec,"
        + "update_flag"),

/** 现金流量表（doc_id=44） */
CASHFLOW("cashflow",
        "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,"
        + "n_cashflow_act,n_cashflow_inv_act,n_cash_flows_fnc_act,free_cashflow,"
        + "c_fr_sale_sg,c_fr_oth_sg,c_paid_goods_s,c_paid_to_for_empl,c_paid_for_taxes,"
        + "c_paid_oth_op_f,c_fr_fnc_loan,c_fr_fnc_oth,c_paid_invest,c_paid_invest_f,"
        + "c_paid_fin_fees,c_pay_dist_dpcp_int_exp,c_pay_acq_const_fiolta,c_pay_acq_int_long_loan,"
        + "proceeds_long_loan,n_invest_loss,disp_fix_assets_oth,c_paid_invest_f,"
        + "end_bal_cash,beg_bal_cash,n_cash_equ,n_increase_incl_child,"
        + "prov_depr_assets,depr_fa_coga_dpba,amort_intang,amort_lt_deferred_exp,loss_disp_fa,"
        + "loss_scr_fa,loss_fair_valu,fin_exp,loss_inv,dec_def_inc_tax_assets,inc_def_inc_tax_liab,"
        + "dec_inv,dec_oper_rece,inc_oper_payable,net_profit,minority_interest,undistributed_profit_in,"
        + "update_flag");
```

> ⚠️ **实施时严格对照官方文档核对字段名**。如果某字段 Tushare 实际返回名称与上面不一致，会导致 DTO 解析为 null。

### 4.3 TushareClient 方法（修改 1 个文件）

**文件**：[TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java)

参照现有 `finaIndicator(...)` 方法签名（直接用方法参数，无 QueryDTO），但为了一致性和后续扩展，**采用 QueryDTO 模式**（参照 daily）：

```java
/** 利润表接口 */
public List<IncomeDTO> income(IncomeQueryDTO param) {
    JSONObject params = buildIncomeParams(param);
    return query(TushareApiEnum.INCOME, params, IncomeDTO.class);
}

/** 资产负债表接口 */
public List<BalancesheetDTO> balancesheet(BalancesheetQueryDTO param) {
    JSONObject params = buildBalancesheetParams(param);
    return query(TushareApiEnum.BALANCESHEET, params, BalancesheetDTO.class);
}

/** 现金流量表接口 */
public List<CashflowDTO> cashflow(CashflowQueryDTO param) {
    JSONObject params = buildCashflowParams(param);
    return query(TushareApiEnum.CASHFLOW, params, CashflowDTO.class);
}

private JSONObject buildIncomeParams(IncomeQueryDTO param) {
    JSONObject params = new JSONObject();
    if (param.getTsCode() != null) params.put("ts_code", param.getTsCode());
    if (param.getStartDate() != null) params.put("start_date", param.getStartDate());
    if (param.getEndDate() != null) params.put("end_date", param.getEndDate());
    if (param.getReportType() != null) params.put("report_type", param.getReportType());
    if (param.getCompType() != null) params.put("comp_type", param.getCompType());
    return params;
}
// buildBalancesheetParams / buildCashflowParams 同构（结构一致，类型相同）
```

### 4.4 限流配置（修改 1 个文件）

**文件**：[application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)

在 `tushare.rate-limit` 下追加（2000 积分对财报接口限流通常 ~120/min，保守配置 100）：

```yaml
tushare:
  rate-limit:
    # ... 现有配置 ...
    income:
      permits-per-minute: 100
    balancesheet:
      permits-per-minute: 100
    cashflow:
      permits-per-minute: 100
```

### 4.5 数据库层（修改 3 + 新建 6 个文件）

#### 4.5.1 schema.sql 三个文件（修改）

**文件 1**：[schema-sqlite.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql)
**文件 2**：[schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql)
**文件 3**：[schema-mysql-comments.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql-comments.sql)

在每个文件末尾新增 3 张表，参照 [fina_indicator](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql) 的结构（自增主键 + UNIQUE(ts_code, end_date, report_type) + 索引）：

```sql
-- SQLite 示例：income 表
CREATE TABLE IF NOT EXISTS income (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code       TEXT    NOT NULL,
    ann_date      TEXT,
    f_ann_date    TEXT,
    end_date      TEXT    NOT NULL,
    report_type   TEXT,
    comp_type     TEXT,
    basic_eps     REAL,
    diluted_eps   REAL,
    -- ... 全部字段 ...
    update_flag   TEXT,
    UNIQUE (ts_code, end_date, report_type)
);
CREATE INDEX IF NOT EXISTS idx_income_tscode ON income (ts_code, end_date);

-- balancesheet / cashflow 同构（balancesheet 字段最多 ~85，cashflow ~45）
```

> MySQL 版本将 `INTEGER PRIMARY KEY AUTOINCREMENT` 改为 `BIGINT AUTO_INCREMENT PRIMARY KEY`，`TEXT` 改为 `VARCHAR(...)`，`REAL` 改为 `DECIMAL(20,4)`，并加 `INDEX idx_xxx_tscode (ts_code, end_date)`。

#### 4.5.2 DO 类（新建 3 个文件）

| 文件 | 说明 |
|---|---|
| `model/IncomeDO.java` | `@TableName("income")`，`@TableId(type=IdType.AUTO)`，字段与表列一一对应，BigDecimal 类型 |
| `model/BalancesheetDO.java` | `@TableName("balancesheet")` |
| `model/CashflowDO.java` | `@TableName("cashflow")` |

#### 4.5.3 Mapper 接口（新建 3 个文件）

参照 [FinaIndicatorMapper.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/mapper/FinaIndicatorMapper.java)：

```java
@Mapper
public interface IncomeMapper extends BaseMapper<IncomeDO> {
    int insertBatch(@Param("list") List<IncomeDO> list);
    int deleteBatchByKeys(@Param("list") List<IncomeDO> list);
    IncomeDO selectLatestAnnouncedBefore(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);
}
```

#### 4.5.4 Mapper XML（新建 3 个文件）

参照 [FinaIndicatorMapper.xml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/mapper/FinaIndicatorMapper.xml)：

- `resources/mapper/IncomeMapper.xml`
- `resources/mapper/BalancesheetMapper.xml`
- `resources/mapper/CashflowMapper.xml`

每个 XML 包含 4 个语句：
1. `<resultMap>` 完整字段映射（含驼峰转换）
2. `<insert id="insertBatch">` 全字段批量插入
3. `<delete id="deleteBatchByKeys">` 按 (ts_code, end_date, report_type) 批量删除
4. `<select id="selectLatestAnnouncedBefore">` point-in-time 查询（ann_date <= tradeDate，按 end_date DESC LIMIT 1）

**关键提醒**（02 文档）：`#{item.tsCode}` 写 Java 驼峰字段名（OGNL 表达式），不受 `map-underscore-to-camel-case` 影响。

### 4.6 Service 层（新建 6 个文件）

参照 [DailyQuoteService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/DailyQuoteService.java) + [DailyQuoteServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DailyQuoteServiceImpl.java)：

| 文件 | 关键方法 |
|---|---|
| `service/IncomeService.java` | `int fetchAndSaveIncome(String tsCode, String startDate, String endDate)` + `int fetchAndSaveAllByRange(String startDate, String endDate)` + `List<IncomeDO> queryLocalByTsCode(String tsCode)` + `IncomeDO selectLatestAnnouncedBefore(String tsCode, String tradeDate)` |
| `service/impl/IncomeServiceImpl.java` | 实现：调用 `tushareClient.income(...)`，DTO→DO 映射，`Lists.partition(BATCH_SIZE=500)` 批量先 deleteBatchByKeys 后 insertBatch |
| `service/BalancesheetService.java` | 同构 |
| `service/impl/BalancesheetServiceImpl.java` | 同构 |
| `service/CashflowService.java` | 同构 |
| `service/impl/CashflowServiceImpl.java` | 同构 |

**与 fina_indicator 的差异**：
- 独立 Service，不寄居在 BasicDataService
- 不分页（财报接口按股票+日期范围返回量小）
- 用 `BATCH_SIZE=500` 批量 upsert（先 delete 后 insert，跨方言通用）
- 提供 `selectLatestAnnouncedBefore` 给选股中心 point-in-time 查询

### 4.7 Controller 层（**不创建**）

用户已选"定时任务+全量初始化"方案，**不提供 REST Controller**。如需手动触发，可通过 `DataInitService.initialize(...)` 接口（已有 Controller）。

### 4.8 初始化流程（修改 2 个文件）

#### 4.8.1 InitStep 枚举（修改 1 个文件）

**文件**：[InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)

在 `STK_LIMIT` 后追加 3 项：

```java
INCOME("income", "利润表", "income"),
BALANCESHEET("balancesheet", "资产负债表", "balancesheet"),
CASHFLOW("cashflow", "现金流量表", "cashflow");
```

#### 4.8.2 DataInitServiceImpl（修改 1 个文件）

**文件**：[DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java)

**修改 1**：`EXECUTION_ORDER` 末尾追加 3 项（放在 STK_LIMIT 之后）

```java
private static final List<InitStep> EXECUTION_ORDER = List.of(
        InitStep.STOCK_BASIC, InitStep.TRADE_CAL, InitStep.INDEX_WEIGHT, InitStep.SW_INDUSTRY,
        InitStep.DAILY, InitStep.ADJ_FACTOR, InitStep.DIVIDEND,
        InitStep.NAMECHANGE, InitStep.SUSPEND_D, InitStep.STK_LIMIT,
        InitStep.INCOME, InitStep.BALANCESHEET, InitStep.CASHFLOW);  // 新增 3 项
```

**修改 2**：注入 3 个新 Service

```java
private final IncomeService incomeService;
private final BalancesheetService balancesheetService;
private final CashflowService cashflowService;
```

**修改 3**：`doInitialize` 的 switch 中追加 3 个 case

```java
case INCOME -> executeIncome(stocks);
case BALANCESHEET -> executeBalancesheet(stocks);
case CASHFLOW -> executeCashflow(stocks);
```

**修改 4**：新增 3 个 `executeXxx` 方法（per-stock 模式，参照 `executeDividend`）

```java
private void executeIncome(List<StockBasicDTO> stocks) {
    updateStep("拉取利润表数据");
    progressRef.updateAndGet(p -> p.toBuilder().totalStocks(stocks.size()).processedStocks(0).build());
    String startPeriod = LocalDate.now().minusYears(30).format(DATE_FMT);
    String endPeriod = LocalDate.now().format(DATE_FMT);
    for (int i = 0; i < stocks.size(); i++) {
        String tsCode = stocks.get(i).getTsCode();
        try {
            incomeService.fetchAndSaveIncome(tsCode, startPeriod, endPeriod);
        } catch (Exception e) {
            log.warn("Failed to fetch income for {}: {}", tsCode, e.getMessage(), e);
        }
        reportProgress("拉取利润表数据", i + 1, stocks.size());
    }
}
// executeBalancesheet / executeCashflow 同构
```

**修改 5**：在 `CREATE_TABLE_SQL_MYSQL` / `CREATE_TABLE_SQL_SQLITE` 两个 Map 中追加 3 张表的建表 SQL（与 schema-*.sql 中一致）

### 4.9 定时任务（修改 1 个文件）

**文件**：[BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java)

注入 3 个新 Service，新增 3 个定时方法（与 `fetchFinaIndicator` 周日 17:00 节奏一致，但错峰避免限流）：

```java
/** 每周日 17:30 拉取最近 2 年利润表 */
@Scheduled(cron = "0 30 17 * * SUN")
public void fetchIncome() {
    String startPeriod = LocalDate.now().minusYears(2).format(DATE_FMT);
    String endPeriod = LocalDate.now().format(DATE_FMT);
    log.info("===== BasicDataTask income start, [{}~{}] =====", startPeriod, endPeriod);
    try {
        int n = incomeService.fetchAndSaveAllByRange(startPeriod, endPeriod);
        log.info("===== BasicDataTask income done, saved={} =====", n);
    } catch (Exception e) {
        log.error("BasicDataTask income 失败", e);
    }
}
// fetchBalancesheet @ 18:00 / fetchCashflow @ 18:30，错峰避免限流
```

### 4.10 数据消费（可选，不在本次范围）

[BasicDataServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/BasicDataServiceImpl.java) 已为 fina_indicator 提供 `selectLatestAnnouncedBefore`。三大报表的相同方法在各自 Mapper 中实现后，**未来可在 `ScreenerServiceImpl.buildFundamentals` 中接入**，以补充利润表/资产负债表/现金流量表的 point-in-time 数据。**本次不修改 ScreenerServiceImpl**，避免引入未经验证的因子键。

## 五、Tushare 字段映射参考

> ⚠️ 以下字段列表基于 Tushare Pro 文档（截至 2025-08）。**实施时务必打开 [doc_id=33](https://tushare.pro/document/2?doc_id=33) / [doc_id=36](https://tushare.pro/document/2?doc_id=36) / [doc_id=44](https://tushare.pro/document/2?doc_id=44) 逐一核对**，若与下方列表不一致以官方文档为准。

### 5.1 income 利润表（doc_id=33，~44 字段）

**基础字段**：ts_code, ann_date, f_ann_date, end_date, report_type, comp_type

**核心财务字段**：
- 每股收益：basic_eps, diluted_eps
- 收入：total_revenue（营业总收入）, revenue（营业收入）
- 成本：total_cogs（营业总成本）, operate_cost（营业成本）
- 利润：operate_profit（营业利润）, non_oper_income（营业外收入）, non_oper_exp（营业外支出）, total_profit（利润总额）, n_income（净利润含少数股东）, n_income_attr_p（归母净利润）, minority_interest（少数股东损益）, adjust_profit（调整后利润）, income_tax（所得税费用）
- 同比：n_income_yoy, dt_profit_yoy（归母净利润同比）
- 费用：sell_exp（销售费用）, admin_exp（管理费用）, financial_exp（财务费用）, rd_exp（研发费用）
- 减值：impair_end_invest（资产减值-投资）, impair_end_oper（资产减值-经营）
- 投资收益：invest_income, invest_income_inc（对联营企业）, invest_income_dec（丧失）
- 其他：fairvalue_change_income（公允价值变动收益）, exchange_gain（汇兑收益）, asset_dispose_income（资产处置收益）, other_income（其他收益）, operate_n_income（营业活动净利润）, credit_impair_loss（信用减值损失）, asset_impair_loss（资产减值损失）, bbit, bbit_yoy, operate_profit_income_yoy
- 更新标记：update_flag

### 5.2 balancesheet 资产负债表（doc_id=36，~85 字段）

**基础字段**：ts_code, ann_date, f_ann_date, end_date, report_type, comp_type

**流动资产**：monetary_funds（货币资金）, accounts_rece（应收票据及应收账款）, notes_rece（应收票据）, accounts_rece_fin（应收账款）, other_rece（其他应收款）, prepayment（预付款项）, dividends_rece（应收股利）, int_rece（应收利息）, inventories（存货）, non_current_assets_in_1_yr（一年内到期的非流动资产）, other_current_assets（其他流动资产）, total_current_assets（流动资产合计）

**非流动资产**：equity_joint_cap（联营企业投资）, lt_receivable（长期应收款）, eqt_invest（长期股权投资）, inv_real_estate（投资性房地产）, fix_assets_nca（固定资产净额）, cip（在建工程）, construction_materials（工程物资）, intang_assets（无形资产）, goodwill（商誉）, lt_amort_deferred_exp（长期待摊费用）, defer_tax_assets（递延所得税资产）, other_non_current_assets（其他非流动资产）, total_non_current_assets（非流动资产合计）

**总资产**：total_assets（资产总计）

**流动负债**：lt_borr（短期借款）, notes_payable（应付票据）, accounts_payable（应付票据及应付账款）, accounts_payable_fin（应付账款）, prepayment_receivables（预收款项）, wage_payable（应付职工薪酬）, taxes_surcharges（应交税费）, other_payable（其他应付款）, non_current_liab_in_1_yr（一年内到期的非流动负债）, other_current_liab（其他流动负债）, total_current_liab（流动负债合计）

**非流动负债**：long_term_borr（长期借款）, ppayable_bonds（应付债券）, long_term_payable（长期应付款）, specific_payable（专项应付款）, estimated_liab（预计负债）, defer_tax_liab（递延所得税负债）, defer_inc_non_curr_liab（递延收益-非流动负债）, other_non_current_liab（其他非流动负债）, total_non_current_liab（非流动负债合计）

**总负债**：total_liab（负债合计）

**所有者权益**：share_capital（实收资本/股本）, capital_reserve（资本公积）, treasury_stock（减:库存股）, specific_reserves（专项储备）, surplus_reserve（盈余公积）, general_risk_reserve（一般风险准备）, undistributed_profit（未分配利润）, equity_parent_company（归母股东权益合计）, minority_interest（少数股东权益）, total_equity（所有者权益合计）, total_liab_equity（负债及所有者权益总计）

**调整项**：accounts_rece_decr, accounts_rece_fin_decr, minority_interest_inc, minority_interest_dec, update_flag

### 5.3 cashflow 现金流量表（doc_id=44，~45 字段）

**基础字段**：ts_code, ann_date, f_ann_date, end_date, report_type, comp_type

**净额**：n_cashflow_act（经营活动净额）, n_cashflow_inv_act（投资活动净额）, n_cash_flows_fnc_act（筹资活动净额）, free_cashflow（自由现金流）

**经营活动**：c_fr_sale_sg（销售商品提供劳务收到的现金）, c_fr_oth_sg（收到的其他与经营活动有关的现金）, c_paid_goods_s（购买商品接受劳务支付的现金）, c_paid_to_for_empl（支付给职工的现金）, c_paid_for_taxes（支付的各项税费）, c_paid_oth_op_f（支付其他与经营活动有关的现金）

**投资活动**：c_paid_invest（投资支付的现金）, c_paid_invest_f（支付其他与投资活动有关的现金）, c_pay_acq_const_fiolta（购建固定资产无形资产支付的现金）, c_pay_acq_int_long_loan（偿还债务支付的现金）, disp_fix_assets_oth（处置固定资产收回的现金净额）, n_invest_loss（投资损失）

**筹资活动**：c_fr_fnc_loan（取得借款收到的现金）, c_fr_fnc_oth（收到其他与筹资活动有关的现金）, proceeds_long_loan（取得长期借款收到的现金）, c_paid_fin_fees（支付其他与筹资活动有关的现金）, c_pay_dist_dpcp_int_exp（分配股利利润偿付利息支付的现金）

**现金等价物**：end_bal_cash（期末现金及现金等价物余额）, beg_bal_cash（期初现金及现金等价物余额）, n_cash_equ（现金及现金等价物净增加额）, n_increase_incl_child（净增加额-含子公司）

**间接法调整项**：prov_depr_assets（资产减值准备）, depr_fa_coga_dpba（固定资产折旧）, amort_intang（无形资产摊销）, amort_lt_deferred_exp（长期待摊费用摊销）, loss_disp_fa（处置固定资产损失）, loss_scr_fa（固定资产报废损失）, loss_fair_valu（公允价值变动损失）, fin_exp（财务费用）, loss_inv（投资损失）, dec_def_inc_tax_assets（递延所得税资产减少）, inc_def_inc_tax_liab（递延所得税负债增加）, dec_inv（存货的减少）, dec_oper_rece（经营性应收项目的减少）, inc_oper_payable（经营性应付项目的增加）, net_profit（净利润）, minority_interest（少数股东损益）, undistributed_profit_in（未分配利润增加）

**更新标记**：update_flag

## 六、实施顺序（按 02 指南 11 步法 × 3 接口并行推进）

按 11 步逐项推进，每个步骤同时处理 3 个接口（同构工作可批量复制修改）：

1. **DTO 定义**：新建 6 个 DTO 文件（3 响应 + 3 请求），每个字段加 `@JSONField`
2. **TushareApiEnum**：追加 INCOME / BALANCESHEET / CASHFLOW 三项
3. **TushareClient**：追加 3 个 public 方法 + 3 个 private buildXxxParams
4. **application.yml**：追加 3 个限流配置（100/min）
5. **数据库层**：
   - 修改 schema-sqlite.sql / schema-mysql.sql / schema-mysql-comments.sql，新增 3 张表
   - 新建 3 个 DO 类（`@TableName`、`@TableId(AUTO)`）
   - 新建 3 个 Mapper 接口（insertBatch / deleteBatchByKeys / selectLatestAnnouncedBefore）
   - 新建 3 个 Mapper XML（resultMap + 3 个语句）
6. **Service 层**：新建 3 对 Service + ServiceImpl（共 6 文件）
7. **InitStep + DataInitServiceImpl**：
   - InitStep 追加 3 项枚举
   - DataInitServiceImpl 修改 EXECUTION_ORDER、注入 3 个 Service、追加 3 个 case、新增 3 个 executeXxx、追加 6 项建表 SQL（MySQL + SQLite 各 3）
8. **BasicDataTask**：注入 3 个 Service，新增 3 个周定时方法（17:30 / 18:00 / 18:30 错峰）
9. **（Mapper 扫描）**：[MyBatisPlusConfig.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java) 已有 `@MapperScan("com.arthur.stock.mapper")`，新 Mapper 自动扫描，**无需修改**
10. **测试验证**：
    - 启动应用，观察 schema.sql 建表日志
    - 执行 `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=income,balancesheet,cashflow"`，查询进度
    - 用 DB 工具查 `income` / `balancesheet` / `cashflow` 表是否有数据
    - 验证 `selectLatestAnnouncedBefore` 返回正确（取某股票某日已公告的最近一期）

## 七、假设与决策（Assumptions & Decisions）

| 假设/决策 | 理由 |
|---|---|
| 假设三大报表的字段名与 Tushare 官方文档 doc_id=33/36/44 一致 | 必须在实施时打开官方文档逐一核对，避免字段名笔误导致解析为 null |
| 决策：建表主键用 `UNIQUE(ts_code, end_date, report_type)` 而非自然主键 | 同一报告期可能有多种 report_type（1=合并 / 4=调整合并等），需区分 |
| 决策：定时任务错峰（17:30 / 18:00 / 18:30），不与 fina_indicator 17:00 同时触发 | 避免对 Tushare 同时发起 4 个接口的 per-stock 调用导致限流 |
| 决策：不提供 REST Controller | 用户已选；如需手动触发，通过已有 `POST /api/tushare/data-init?steps=...` |
| 决策：本次不修改 ScreenerServiceImpl | 避免引入未经验证的因子键；三大报表 Mapper 已提供 `selectLatestAnnouncedBefore`，后续消费方接入时再改 |
| 假设：三大报表在 2000 积分下限流 100/min 足够 | Tushare 文档对财报接口限流较紧，100/min 保守；如 429 调小 |

## 八、验证步骤（Verification）

| 验证项 | 命令/方法 | 预期 |
|---|---|---|
| 应用启动 | 启动 Spring Boot | 日志无 SQL 异常，3 张新表成功创建 |
| 表结构 | 检查 SQLite/MySQL 中 income/balancesheet/cashflow 表 | 字段与 schema 定义一致，索引存在 |
| 单步初始化 | `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=income"` | 进度从 RUNNING → SUCCESS，income 表有数据 |
| 三步初始化 | `curl -X POST "http://localhost:8080/api/tushare/data-init?steps=income,balancesheet,cashflow"` | 3 张表都有数据 |
| 进度查询 | `curl http://localhost:8080/api/tushare/data-init/status` | 返回当前步骤和进度 |
| DTO 解析正确性 | 日志中无 "field value is null" 类异常；查表发现 total_revenue/total_assets/n_cashflow_act 等核心字段非空 | 字段映射正确 |
| point-in-time 查询 | 直接 SQL：`SELECT * FROM income WHERE ts_code='000001.SZ' AND ann_date <= '20240101' ORDER BY end_date DESC LIMIT 1` | 返回 2023 年报或更早的最近一期 |
| 限流验证 | 检查 RateLimiter 日志在大量请求时是否触发等待 | 100/min 限流生效 |
| 定时任务 | 等待周日 17:30-18:30 或手动触发 | 日志输出"BasicDataTask income start/done" |

## 九、新增/修改文件清单（汇总）

**新建 15 个文件**：
- 6 个 DTO：IncomeDTO / IncomeQueryDTO / BalancesheetDTO / BalancesheetQueryDTO / CashflowDTO / CashflowQueryDTO
- 3 个 DO：IncomeDO / BalancesheetDO / CashflowDO
- 3 个 Mapper 接口：IncomeMapper / BalancesheetMapper / CashflowMapper
- 3 个 Mapper XML：IncomeMapper.xml / BalancesheetMapper.xml / CashflowMapper.xml
- 6 个 Service：3 接口 + 3 实现 = 实际为 6 个文件（与上面 15 重复计算，重新统计如下）

**实际新建文件数**：6（DTO）+ 3（DO）+ 3（Mapper 接口）+ 3（Mapper XML）+ 6（Service/Impl）= **21 个新文件**

**修改 6 个文件**：
- [TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java)
- [TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java)
- [application.yml](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/application.yml)
- [schema-sqlite.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-sqlite.sql) / [schema-mysql.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql.sql) / [schema-mysql-comments.sql](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/resources/schema-mysql-comments.sql)（算 3 个但同源 → 算 3）
- [InitStep.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/InitStep.java)
- [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java)
- [BasicDataTask.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/task/BasicDataTask.java)

**总计**：21 新建 + 8 修改 = **29 个文件操作**
