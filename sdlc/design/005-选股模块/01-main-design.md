# 005 选股模块 — 研发主设计方案

> 参考资料索引：
> - 需求 PRD：`sdlc/prd/005-选股模块/005-选股模块PRD.md`
> - 原型图：
>   - `sdlc/prd/005-选股模块/prototype/index.html` — 模块入口（选股中心卡片）
>   - `sdlc/prd/005-选股模块/prototype/screening.html` — 选股主页面（快照模式）
>   - `sdlc/prd/005-选股模块/prototype/screening-flow.html` — 数据流向与交互逻辑说明
>   - `sdlc/prd/005-选股模块/prototype/screening-range.html` — 区间模式页面
>
> 项目：Stock Watcher（Java Spring Boot + SQLite + Thymeleaf + ECharts）
> 匹配过程：用户输入「005」→ 匹配到目录 `sdlc/prd/005-选股模块/`

---

## 1. 需求摘要

本模块是 Stock Watcher 量化体系的**选股执行层**，把"策略规则"翻译成"可执行的 SQL 查询"，从预计算的技术指标表中筛选出今日或某个区间内命中条件的股票。核心目标是：

- 提供**选股查询页**（快照模式 + 区间模式），前端配置可视化规则树，返回命中股票列表 + 关键行情数据（收盘价、涨跌幅、成交额）+ SQL 预览；
- 在 Java 端实现**技术指标预计算表** `stock_indicator_daily`，与 Python 计算服务通过 HTTP/JSON 协作完成指标批量写入；
- 实现**条件 → SQL 翻译引擎** `ConditionSqlBuilder`，递归遍历规则树（AND/OR 嵌套、因子引用、算术表达式、cross_up/cross_down）动态生成 SELECT 语句；
- 通过 `FactorColumnMapper` 实现因子引用与预计算列的映射（MA(5) → ma_5、MACD_DIF → macd_dif），支持非标准参数降级（如 MA(8) → ma_10 的提示）；
- 规则树 JSON Schema 与**完全复用** 003-策略管理模块的 buy_rules JSON 结构（logic/compare/expression 三态节点）。

本模块与 003 策略管理 / 004 回测中心 的关系：

| 模块 | 关系 |
|------|------|
| 002 因子库 | 复用列命名规则（factorKey_paramValue / factorKey_outputLabel），Python 计算服务复用同一套 |
| 003 策略管理 | 规则树 JSON Schema 完全一致；策略管理中的 buy_rules 树可零修改粘贴到选股查询页运行 |
| 004 回测中心 | 选股表 stock_indicator_daily 同时作为回测的数据源之一（回测核心仍走 Python 计算） |

---

## 2. 原型图分析（页面结构与交互流程）

| 页面（原型图） | 核心功能 | 关键表单字段 | 主要操作 | 与其他页面的关联 |
|---------------|---------|-------------|---------|-----------------|
| `prototype/index.html` | 模块入口卡片，提供「选股主页面」「区间模式」「数据流向说明」三个入口 + 预计算状态提示 | 无表单 | 点击卡片跳转对应子页面 | 跳转到 screening / screening-flow / screening-range |
| `prototype/screening.html` | **核心页面**：左侧规则树编辑器（AND/OR 嵌套 + compare 节点 + 表达式三态），右侧查询选项 + 结果表格 + SQL 预览 | 因子选择（下拉）、参数输入、比较符选择（>/</≥/≤/=/cross_up/cross_down）、查询模式、查询日期、前置过滤（剔 ST、次新股天数、最小成交额）、结果分页 | 「执行选股」调用查询接口；「导出 CSV」下载；「保存策略草稿」→ 写入 003 策略管理 quant_strategy 表（status=DRAFT, category=SCREEN_DRAFT）；「查看 SQL」显示生成的 SQL | 跳转到 screening-flow 查看数据流向；点击个股详情跳转到 K线分析页（001-K线计算与复权方案） |
| `prototype/screening-range.html` | 区间模式：一段时间内任一天命中条件的股票列表 + 每只股票首次命中日期 + 命中天数 + 11 日热力图 | 起始日期、结束日期、排序方式（首次命中日期倒序/命中天数倒序） | 「执行选股」调用查询接口；「导出 CSV」；展示每日命中统计表 | 回到 screening.html 快照模式；点击个股查看详情 |
| `prototype/screening-flow.html` | 数据流向说明：规则树 → SQL → SQLite → 结果表格的完整链路，附带 8 个阶段说明卡片 + 完整 SQL 示例 + cURL 调用示例 | 无表单，纯文档 + 代码示例 | 阅读文档 → 复制 cURL → 手动验证 | 作为前端/后端开发参考 |

核心交互链路（单轮）：用户在 screening.html 配置规则树 → 前端序列化为 JSON 提交 POST `/api/screening/query` → Java 端接收后：
1. 校验查询日期 / 区间合法性（调用 trade_cal 表判断是否为交易日）；
2. 检查 stock_indicator_daily 是否有该日期的数据（如无，提示用户前往数据初始化页面触发预计算）；
3. 调用 ConditionSqlBuilder 生成 SQL → JdbcTemplate.query → 返回结果列表 + 结果包装 → 前端渲染表格 + SQL 预览。

---

## 3. 目标与非目标

| 项 | 说明 |
|---|---|
| **本次做什么（P0）** | 1. 新建 `stock_indicator_daily` 表（技术指标列式存储，列名与 PRD §8.1）；2. Java 端指标预计算调度（`@Scheduled` 每日 1:00，手动触发入口）；3. `ConditionSqlBuilder`（递归翻译规则树 → SQL）；4. `FactorColumnMapper`（因子到列名映射 + 非标准参数降级）；5. 选股查询 / 区间查询两个接口；6. 选股主页面（快照模式）和区间模式两个 Thymeleaf 页面；7. CSV 导出；8. 与 003 策略管理共享规则树 JSON Schema；9. 写入选股草稿（category=SCREEN_DRAFT） |
| **本次做什么（P1）** | 1. 区间模式下的"近 N 日首次信号识别（近 3 日新信号高亮）；2. 因子参数降级提示（前端显示降级提示 + Java 端返回 warnings 字段）；3. 结果表格的搜索 / 排序 / 分页；4. 区间模式下的每日命中数统计表 + 强势股 / 机会股智能提示；5. 保存选股条件为策略草稿（复用 003 策略管理 `quant_strategy` 表） |
| **本次不做什么** | 1. 信号中心自动推送（004 后续独立模块）；2. 实时行情盘中选股（依赖 Tushare 实时行情接口可用性）；3. 基本面条件选股（依赖 stock_financial_indicator 表，数据源未确认）；4. 非标准参数的实时计算（需调用 Python 计算服务单独计算某只股票某因子，后续扩展）；5. 多周期策略（日线 + 60 分钟混合）；6. 规则树的可视化拖拽（003 策略管理已实现，本模块复用其 JSON Schema 但不重复实现拖拽 UI） |

---

## 4. 技术方案

### 4.1 总体架构

```
┌────────────────────────────────────────────────────────────────────────────┐
│   stock-watcher (Java Spring Boot, SQLite)                       │
│                                                                │
│  ┌─ ScreeningController ──────────────────────────────────────┐  │
│  │  POST /api/screening/query          (快照 / 区间共用)       │  │
│  │  GET  /api/screening/export/csv  (结果表格导出)       │  │
│  └────────────┬───────────────────────────────────────────┘  │
│               │                                              │
│  ┌─ ScreeningService ────────────────────────────────────┐ │
│  │  · validateRequest()  (参数/交易日期校验)               │ │
│  │  · buildConditionSql()  (调用 ConditionSqlBuilder)     │ │
│  │  · executeQuery()     (JdbcTemplate 查询)             │ │
│  └────────┬──────────────────────────────────────────────┘ │
│           │                                               │
│  ┌─ ConditionSqlBuilder ─────────────────────────────────┐  │
│  │  · buildSql(rules, preFilters, queryDate)      │  │
│  │  · buildSql(rules, preFilters, dateRange)      │  │
│  │  · buildConditionExpr(node)                        │  │
│  │  · needsPrevDayJoin(rules)                          │  │
│  └──────────┬─────────────────────────────────────────┘  │
│             │                                             │
│  ┌─ FactorColumnMapper ────────────────────────────┐  │
│  │  · mapToColumn(factorKey, params, idx)         │  │
│  │  · isSupported(factorKey, params)             │  │
│  │  · getDowngradeWarning(factorKey, params)        │  │
│  └────────────────────────────────────────────┘  │
│             │                                             │
│  ┌─ IndicatorComputeScheduler ────────────────────────┐  │
│  │  · dailyCompute()  (@Scheduled cron=0 0 1 * * ?)    │  │
│  │  · manualTrigger(tsCodes)  (手动触发)          │  │
│  └──────────┬───────────────────────────────────────┘  │
│             │                                         │
│  ┌─ ComputeGateway (Python ↔ Java) ──────────┐           │
│  │  POST /api/compute/indicators      │           │
│  │  POST /api/compute/health            │           │
│  └─────────────────────────────────────┘           │
│                  │                                   │
│  ┌─ SQLite ────────────────────────────────┐          │
│  │  · stock_indicator_daily (新增表)       │          │
│  │  · stock_basic (复用)                  │          │
│  │  · trade_cal (复用)                    │          │
│  │  · daily_quote (复用)                     │          │
│  └───────────────────────────────────────┘          │
└────────────────────────────────────────────────────────────────────────────┘
                    │ HTTP/JSON
                    ▼
┌──────────────────────────────────────────────────┐
│  stock-engine (Python FastAPI + akquant)      │
│  ┌─ /api/compute/indicators ──────┐    │
│  │  接收 { stocks:[{ts_code,ohlcv}] } │    │
│  │  计算所有标准指标值 → 结果字典返回        │    │
│  └──────────────────────────────────────┘    │
└──────────────────────────────────────────────────┘
```

### 4.2 模块划分

| 模块 | 职责 | Java 包路径 | 主要类 | 对应原型图页面 |
|------|------|-------------|--------|---------------|
| 选股查询（Controller） | 处理 `/api/screening/*` REST 请求，参数解析，统一 ApiResponse 包装 | `com.arthur.stock.controller` | `ScreeningController` | `screening.html` `screening-range.html` |
| 选股查询（Service） | 规则树校验、SQL 生成、查询执行、结果包装；前置过滤（剔 ST、次新股、最低成交额） | `com.arthur.stock.service` | `ScreeningService` | 同上 |
| SQL 翻译引擎 | 递归翻译规则树 → SQL；判断是否需要自 JOIN（cross_up/cross_down） | `com.arthur.stock.screening` | `ConditionSqlBuilder` | `screening-flow.html` |
| 因子列映射器 | 因子（factorKey + params + outputIndex） → stock_indicator_daily 的列名；非标准参数降级提示 | `com.arthur.stock.screening` | `FactorColumnMapper` | 同上 |
| 指标预计算调度 | 从 daily_quote 取最新 OHLCV → 分批调用 Python /api/compute/indicators → 批量 UPSERT 到 stock_indicator_daily | `com.arthur.stock.schedule` | `IndicatorComputeScheduler` | `screening-flow.html` 数据流向 |
| Python 计算网关（Java 侧） | 封装对 Python 计算服务的 HTTP 调用；统一请求/响应格式；超时/失败重试（与现有 ComputeGateway 复用） | `com.arthur.stock.client` | `ComputeGateway`（复用 003 的计算网关） | `screening-flow.html` |
| 页面（Thymeleaf） | 选股主页面（快照 + 区间）；数据流向说明；结果表格 + SQL 预览 | `resources/templates/screening/` | `screening.html` `screening-range.html` `screening-flow.html` | `prototype/screening.html` `prototype/screening-range.html` `prototype/screening-flow.html` |

### 4.3 关键流程

#### 4.3.1 指标预计算流程（每日 1:00 自动执行）

```
@Scheduled(cron = "0 0 1 * * ?")
    ↓
1. 从 trade_cal 取当前日期 → 判断是否为交易日；如非交易日则跳过
    ↓
2. 从 stock_basic 取全部 ts_code 列表
    ↓
3. 对每只股票：
   从 daily_quote 取最近 300 个交易日的 OHLCV（保证 MA250 够用）
    ↓
4. 按 50 只一批分组调用 Python /api/compute/indicators
    ↓
5. 收到 {ts_code → [{trade_date, ma_5, ...}]} 后，
   批量 UPSERT 到 stock_indicator_daily
    ↓
6. 记录耗时和成功/失败计数到日志
```

**手动触发**：GET `/api/screening/indicators/refresh`（`@RequireAdmin`，仅管理员）

#### 4.3.2 选股查询流程（快照模式）

```
POST /api/screening/query  { option.mode = SNAPSHOT }
    ↓
1. 参数校验：
   - rules 非空、option 合法、trade_date 格式为 YYYYMMDD
   - trade_cal.is_open = '1' 且 ≤ 当前日期
    ↓
2. 检查 stock_indicator_daily 中该 trade_date 是否有数据
   (SELECT COUNT(*) FROM stock_indicator_daily WHERE trade_date = '...')
   → 0 条 → 返回 404 NOT_FOUND "请先触发指标预计算"
    ↓
3. FactorColumnMapper.scan(rules) → 所有引用的列名集合 + 降级警告
    ↓
4. ConditionSqlBuilder.buildSql(rules, preFilters, trade_date)
   → 返回完整 SQL 字符串
    ↓
5. JdbcTemplate.query(SQL, RowMapper)
    ↓
6. 包装 ApiResponse.success({ totalHit, stocks, warnings, generatedSql })
```

#### 4.3.3 选股查询流程（区间模式）

```
POST /api/screening/query  { option.mode = RANGE }
    ↓
1. 参数校验 + start_date / end_date 格式合法；end_date - start_date ≤ 90 个交易日
    ↓
2. 检查 stock_indicator_daily 该区间有数据
    ↓
3. buildSql(...)  → SELECT ... WHERE trade_date BETWEEN ? AND ?
   GROUP BY ts_code, name  首  首 首...
    ↓
4. 返回 { totalHit, stocks:[{tsCode, name, firstHitDate, hitDaysCount}], generatedSql, warnings}
```

#### 4.3.4 SQL 生成示例（快照模式，MA5 金叉 MA20 + 放量）

```sql
SELECT
    sb.ts_code,
    sb.name,
    sid.close                                       AS current_close,
    ROUND((sid.close / sid_prev.close - 1) * 100, 2)  AS daily_change_pct,
    ROUND(sid.close * sid.volume / 10000, 2)          AS turnover_10k
FROM stock_indicator_daily sid
LEFT JOIN stock_indicator_daily sid_prev
  ON sid_prev.ts_code = sid.ts_code
 AND sid_prev.trade_date = (
        SELECT MAX(trade_date)
        FROM stock_indicator_daily
        WHERE ts_code = sid.ts_code
          AND trade_date < '20260520'
    )
JOIN stock_basic sb
  ON sb.ts_code = sid.ts_code
WHERE sid.trade_date = '20260520'
  AND sid.ma_5 IS NOT NULL
  AND sid.ma_20 IS NOT NULL
  AND sid.ma_5 > sid.ma_20
  AND sid_prev.ma_5 <= sid_prev.ma_20
  AND (sid.volume > (sid.vol_ma_20 * 1.5))
  AND sb.name NOT LIKE '%ST%'
  AND sb.list_date <= date('2026-05-20', '-365 days')
  AND (sid.close * sid.volume) >= 50000000
ORDER BY (sid.close * sid.volume) DESC
LIMIT 200;
```

### 4.4 类设计 / 接口契约

#### 4.4.1 核心类：`ConditionSqlBuilder`

```java
package com.arthur.stock.screening;

/**
 * 规则树 → SQL 翻译引擎
 * 核心设计：
 * - 所有方法为纯函数，便于单元测试
 * - 通过 needsPrevDayJoin(rules) 扫描整棵树判断是否需要自 JOIN
 * - FactorColumnMapper 负责因子 → 列名映射
 */
@Slf4j
public class ConditionSqlBuilder {

    /** 快照模式：返回单日期 SQL */
    public static BuiltSql buildSnapshotSql(
            List<RuleNode> rules,
            PreFilters preFilters,
            String tradeDate,
            int limit) { ... }

    /** 区间模式：返回区间查询 SQL + GROUP BY first_hit_date / COUNT */
    public static BuiltSql buildRangeSql(
            List<RuleNode> rules,
            PreFilters preFilters,
            String startDate,
            String endDate,
            int limit) { ... }

    /** 逻辑节点翻译 → SQL 片段 */
    private static String buildConditionExpr(RuleNode node) {
        // type = logic →  children.join(" AND " / " OR ")
        // type = compare → buildCompareExpr(node)
    }

    /** 表达式节点翻译 → SQL 片段 */
    private static String buildExpressionExpr(ExprNode expr) {
        // value → String.valueOf(bd)
        // factor → FactorColumnMapper.mapToColumn(...)
        // op → "(" + buildExpr(left) + " " + op + " " + buildExpr(right) + ")"
    }

    /** 比较节点 → SQL 片段；cross_up/cross_down 需要前一日自 JOIN */
    private static String buildCompareExpr(CompareNode compare) { ... }

    /** 是否需要前一日自 JOIN（规则树中含 cross_up / cross_down 节点） */
    public static boolean needsPrevDayJoin(List<RuleNode> rules) { ... }
}
```

#### 4.4.2 核心类：`FactorColumnMapper`

```java
package com.arthur.stock.screening;

/**
 * 因子 → 列名映射器
 *
 * 标准参数对照表（来自 PRD §8.4 + §9.6）：
 * - MA(timeperiod=5/10/20/60/120/250) → ma_5 / ma_10 / ...
 * - EMA(timeperiod=5/10/20/60) → ema_5 / ...
 * - SAR → sar
 * - RSI(timeperiod=6/14/28) → rsi_6 / rsi_14 / rsi_28
 * - MACD 标准参数 → outputIndex=0/1/2 → macd_dif / macd_dea / macd_hist
 * - KDJ 标准参数 → outputIndex=0/1/2 → kdj_k / kdj_d / kdj_j
 * - ADX(timeperiod=14) → adx_14
 * - PLUS_DI(timeperiod=14) → plus_di_14
 * - MINUS_DI(timeperiod=14) → minus_di_14
 * - WILLR(timeperiod=14) → willr_14
 * - CCI(timeperiod=14) → cci_14
 * - ATR(timeperiod=14) → atr_14
 * - VOL_MA(timeperiod=5/20/60) → vol_ma_5 / vol_ma_20 / vol_ma_60
 * - CLOSE → close; HIGH → high; LOW → low; VOLUME → volume
 *
 * 非标准参数降级规则：如 MA(8) → 四舍五入到最近标准参数 → MA(10)，并收集 warning
 */
@Slf4j
public class FactorColumnMapper {

    public static MappingResult mapToColumn(String factorKey, Map<String, Object> params, Integer outputIndex) {
        // 返回 MappingResult(columnName, downgradeWarning  // 若 null 则无降级)
        // 完全无法匹配时抛 UnsupportedFactorException
    }

    public static boolean isSupported(String factorKey, Map<String, Object> params) { ... }

    /** 返回所有已知列名（供 WHERE 中 IS NOT NULL 检查使用） */
    public static List<String> getAllReferencedColumns(List<RuleNode> rules) { ... }
}
```

#### 4.4.3 DTO：请求 / 响应

```java
// 请求 DTO
@Data
public class ScreeningQueryRequest {
    private List<RuleNode> rules;       // 规则树（与 003 策略管理 buy_rules 同结构）
    private ScreeningOption option;    // 查询选项
}

@Data
public class ScreeningOption {
    private String mode;                // SNAPSHOT / RANGE（必填）
    private String tradeDate;         // 快照模式：YYYYMMDD
    private String startDate;         // 区间模式：起始日期 YYYYMMDD
    private String endDate;           // 区间模式：结束日期 YYYYMMDD
    private Boolean excludeSt;      // 剔除 ST（默认 true）
    private Integer minListDays;      // 剔除上市未满 N 天（默认 365）
    private BigDecimal minTurnover10k; // 最低成交额（万元，默认 5000）
    private Integer limit;           // 最大条数（默认 200，最大 500）
}

// 规则树节点（与 003 策略管理 buy_rules 完全一致）
@Data
public class RuleNode {
    private String type;              // logic / compare
    // logic 节点字段：
    private String operator;        // AND / OR
    private List<RuleNode> children;
    // compare 节点字段：
    private ExprNode left;
    private String comparator;      // > / < / >= / <= / == / cross_up / cross_down
    private ExprNode right;
}

@Data
public class ExprNode {
    // 三选一：
    private BigDecimal value;                  // 静态值
    private FactorRef factor;                 // 因子引用
    private ArithOp op;                     // 算术运算
}

@Data
public class FactorRef {
    private String factorKey;                // 如 "MA"
    private Map<String, Object> params;       // 如 {timeperiod:5}
    private Integer outputIndex;            // 多输出因子的输出下标（MACD/KDJ 使用）
}

@Data
public class ArithOp {
    private String operator;                // + / - / * / /
    private ExprNode left;
    private ExprNode right;
}

// 响应 VO
@Data
public class ScreeningResultVO {
    private Integer totalHit;                // 命中股票总数
    private List<StockHitItemVO> stocks;    // 命中股票列表
    private List<String> warnings;            // 因子参数降级提示等
    private String generatedSql;             // 生成的 SQL（调试用）
}

@Data
public class StockHitItemVO {
    private String tsCode;                 // 股票代码
    private String name;                     // 股票名称
    private BigDecimal close;                // 收盘价（快照模式）
    private BigDecimal dailyChangePct;     // 日涨跌幅 %（快照模式）
    private BigDecimal turnover10k;        // 成交额（万元，快照模式）
    private String firstHitDate;           // 首次命中日期（区间模式）
    private Integer hitDaysCount;         // 命中天数（区间模式）
}
```

### 4.5 与现有系统的集成点

| 集成点 | 说明 |
|--------|------|
| **表 stock_basic** | 已存在；JOIN 查询股票名称、上市日期、name LIKE '%ST%' 剔 ST |
| **表 trade_cal** | 已存在；校验查询日期是否为交易日；计算区间模式的交易日数 |
| **表 daily_quote** | 已存在；预计算调度读取最近 300 个交易日 OHLCV |
| **表 quant_strategy** | 003 策略管理表；选股草稿保存为 category=SCREEN_DRAFT；选股页 "保存策略草稿" 复用 003 策略管理服务 |
| **Python 计算网关** | 与 003 / 004 共用同一 ComputeGateway：`POST /api/compute/indicators`（指标预计算） |
| **定时任务** | `@Scheduled(cron="0 0 1 * * ?")` 每日 1:00 执行指标预计算（与现有 @Scheduled 任务并列） |
| **权限** | 读接口（/api/screening/query）需登录；写接口（/refresh，/export/csv）需 @RequireAdmin |
| **错误码** | 优先复用 BAD_REQUEST / UNAUTHORIZED / FORBIDDEN / NOT_FOUND；新增 2 个业务错误码 |
| **ApiResponse** | 完全复用现有统一返回结构 |

### 4.6 异常与错误码

| 场景 | 复用/新增 | HTTP 状态 | message 建议 |
|------|----------|-----------|-----------|
| rules 为空 | 复用 BAD_REQUEST | 400 | "rules 不能为空" |
| trade_date 格式错误 | 复用 BAD_REQUEST | 400 | "trade_date 格式错误，要求 YYYYMMDD" |
| trade_date 非交易日 | 复用 BAD_REQUEST | 400 | "查询日期非交易日" |
| 指标表中无该日期数据 | 新增 INDICATOR_NOT_AVAILABLE | 404 | "stock_indicator_daily 暂无该日期指标数据，请先触发指标预计算" |
| 区间跨度超过 90 天 | 新增 RANGE_TOO_LARGE | 400 | "区间模式最大跨度为 90 个交易日" |
| 因子参数完全不支持（如 MA(2)） | 新增 UNSUPPORTED_FACTOR_PARAM | 400 | "因子 MA(timeperiod=2) 非预计算标准参数，请改用 MA(5)/MA(10)/MA(20)/MA(60)/MA(120)/MA(250)" |
| 未登录 | 复用 UNAUTHORIZED | 401 | "未登录" |
| 无权限（管理员操作） | 复用 FORBIDDEN | 403 | "无权限" |
| limit 超出最大 500 | 复用 BAD_REQUEST | 400 | "limit 必须 ≤ 500" |

需在 `ErrorCode.java` 新增：

```java
INDICATOR_NOT_AVAILABLE(2001, "指标数据未就绪"),
RANGE_TOO_LARGE(2002, "查询区间过大"),
UNSUPPORTED_FACTOR_PARAM(2003, "因子参数非预计算标准参数");
```

### 4.7 复用清单（基于实时扫描结果）

| 类别 | 现有资源（来自实时扫描） | 复用决策 | 说明 |
|------|-------------------------|----------|------|
| **表** | `sys_user`, `sys_watchlist`, `daily_quote`, `stock_basic`, `trade_cal`, `adj_factor`, `dividend` | **新建** `stock_indicator_daily`；**复用** `stock_basic` / `trade_cal` / `daily_quote` 作为 JOIN 关联 | stock_basic 提供 name/ts_code/list_date；trade_cal 提供 is_open 校验；daily_quote 为预计算输入源 |
| **表** | quant_strategy（003 策略管理表） | **复用** | 选股草稿保存为 category=SCREEN_DRAFT, status=DRAFT | 不需新建独立草稿表 |
| **接口** | `WatchlistController`、`StockBasicController`、`KlineController`、`UserApiController`、`AuthController`、`PageController`、`DataInitController` 等 13 个 Controller | **新建** `ScreeningController`，路径前缀 `/api/screening/*`；与现有 Controller 路径无冲突 | 选股查询接口不与现有接口语义重复 |
| **接口** | `ComputeGateway`（Python 计算网关） | **复用** | `/api/compute/indicators` 预计算调用路径一致 |
| **错误码** | `BAD_REQUEST(400)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `NOT_FOUND(404)`, `CONFLICT(409)` | **复用** BAD_REQUEST/UNAUTHORIZED/FORBIDDEN/NOT_FOUND；**新增** INDICATOR_NOT_AVAILABLE(2001), RANGE_TOO_LARGE(2002), UNSUPPORTED_FACTOR_PARAM(2003) | 业务码区间 [2000-2099] 给本模块使用 |
| **DTO** | `ApiResponse<T>` | **完全复用** | 所有接口统一 `ApiResponse.success(data) / `ApiResponse.error(code, msg) |
| **定时任务** | 项目已启用 `@Scheduled` | **新增** `IndicatorComputeScheduler`（新类），每日 1:00 执行 | 与现有调度任务并列，不影响现有任务执行 |
| **前端页面** | `PageController` 路由所有页面 | **扩展** `PageController`：新增 `/screening`、`/screening/range` 两个页面路由；`/screening/flow` 纯静态页 |

---

## 5. 验收要点

- [ ] **P0 功能**：POST `/api/screening/query` 成功返回命中股票列表（快照模式），参数含规则树 + trade_date + limit（对应 prototype/screening.html 的「执行选股」按钮）
- [ ] **P0 功能**：POST `/api/screening/query` 成功返回区间模式结果（每只股票 firstHitDate + hitDaysCount，对应 prototype/screening-range.html 的「执行选股」按钮）
- [ ] **P0 功能**：GET `/api/screening/export/csv` 成功导出 CSV（对应 screening.html 的「导出 CSV」按钮）
- [ ] **P0 功能**：POST `/api/screening/indicators/refresh` 成功触发指标预计算（@RequireAdmin，对应 data-init 页或管理员页面）
- [ ] **P0 数据一致性**：stock_indicator_daily 的列名与 Python /api/compute/indicators 返回结果 key 一一对应；批量 UPSERT 执行成功，无数据遗漏
- [ ] **P0 SQL 生成**：ConditionSqlBuilder 正确处理 AND/OR 嵌套、算术表达式（+/-/*//）、因子参数映射、cross_up/cross_down 自 JOIN、空因子列 IS NOT NULL 检查；生成的 SQL 可直接在 SQLite 中执行成功并返回正确结果
- [ ] **P0 非标准参数降级**：MA(8) → 降级为 MA(10)，Java 返回 warnings 字段；前端展示降级提示（对应 screening-flow.html 参数降级卡片）
- [ ] **P0 前端页面**：screening.html / screening-range.html / screening-flow.html 三个页面正常渲染，包含完整交互链路（配置规则 → 查询 → 结果表格）
- [ ] **P0 结果正确性**：以平安银行（000001.SZ）为验证样本，手动算 MA5=12.50、MA20=12.30（金叉） + 成交量=3.8 亿、VOL_MA20=2.5 亿（放量），SQL 查询返回该股票
- [ ] **P1 保存策略草稿**：screening.html 「保存策略草稿」成功写入 quant_strategy（category=SCREEN_DRAFT, status=DRAFT），规则树 JSON 与查询时一致
- [ ] **P1 选股范围与前置过滤**：排除 ST 股（name LIKE '%ST%'）、排除上市未满 365 天（list_date 字段判断）、排除成交额 < 5000 万的冷门股
- [ ] **性能**：单日期查询（3000 只股票 × 18 个因子列）P95 < 2 秒；区间查询（90 日）P95 < 5 秒
- [ ] **权限验证**：未登录时访问 /api/screening/query 返回 401；普通用户访问 /indicators/refresh 返回 403
- [ ] **cURL 可运行**：prototype/screening-flow.html 中提供的 cURL 示例可直接运行并返回正确结果（可在项目目录下执行）
- [ ] **Python 健康检查**：POST `/api/compute/health` 在 Python 计算服务返回 success

---

## 6. 风险与 TODO

- **TODO 1**：stock_basic 表中 `name` 字段是否足够区分 ST 股（如股票名称中是否包含 "ST" 字符）。当前设计采用 `name NOT LIKE '%ST%'`，存在理论上的误伤风险（如名称中自然包含 ST 字样但非风险警示股）。PRD §11.2 中也明确提及此风险。缓解方案：在 001-K线计算方案中如有 `is_hs` 字段或其他风险标志可替代时，优先使用正式标志位；若无则以 LIKE 方案继续使用
- **TODO 2**：MA(8) 等非标准参数的降级规则（四舍五入到最近标准参数），是否应按"向上取最接近值"还是"向上取大参数（保守估计）"。当前取四舍五入方案，由前端降级提示中明确告知用户
- **TODO 3**：预计算调度时间窗：PRD 要求 1:00，但实际 Tushare 日线数据通常收盘后几小时才能刷新。需要与 001 数据初始化方案的调度时间协调。当前设计采用 1:00，如实际场景 Tushare 数据未到，可由管理员手动触发 /refresh 接口补足
- **TODO 4**：SQLite `CREATE TABLE IF NOT EXISTS` 幂等，但 `ALTER TABLE ADD COLUMN` 不幂等。需要在 03-schema.sql 中写"如果列已存在则跳过"逻辑。当前设计采用 Java 端启动时检查表结构并补列（`JdbcTemplate` 执行 ALTER TABLE）
- **TODO 5**：非标准参数的实时计算（PRD §11.3 明确标记为本次不做），但当用户配置了 MA(30) 等参数时，前端展示降级提示；Java 端实际以降级后的参数执行。后续版本需考虑"对单个因子做实时计算合并到查询结果"的能力（调用 Python 单独计算某只股票某因子，然后合并 SQL 查询结果）
- **TODO 6**：`stock_indicator_daily` 表主键策略：`PRIMARY KEY (ts_code, trade_date)`，ts_code 与 stock_basic.ts_code 格式一致（000001.SZ）。需要在代码中统一校验格式，避免重复插入时主键冲突
- **TODO 7**：区间模式下 `GROUP BY` 时每日命中数统计需要性能问题：当前 SQL 生成使用 COUNT(*) 的方式，在 90 天 × 3000 只股票的数据量下需关注查询耗时。如果性能不佳，后续可考虑增加分区或者添加索引 `CREATE INDEX IF NOT EXISTS idx_sid_trade_date ON stock_indicator_daily(ts_code, trade_date)` 已在 03-schema.sql 中定义

---

## 7. 自检记录

> 本自检清单基于 §7.1-7.5 的模板检查项逐项执行：

- [x] **全局一致性**：表名/字段名 snake_case；JSON 字段强制小驼峰；SQLite 数据类型正确；无 Controller 路径语义重复；PageController 未作为 REST API 复用；01-main-design.md 头部含参考资料索引；原型图分析存在 4 个原型图页面列全
- [x] **01-main-design.md**：4.7 复用清单基于实时扫描（13 个 Controller + schema.sql + ErrorCode.java）；2. 原型图分析存在；模块划分 Java 包路径与项目一致（`com.arthur.stock.*`）；Service 层遵循 `@Service @RequiredArgsConstructor @Slf4j` 风格；定时任务 cron 写明；无缓存需要；无 Tushare 新接口；权限区分登录接口
- [x] **02-db-design.md**：0. 现有表复用检查基于实时扫描；主键策略为自然主键 `(ts_code, trade_date)`；ts_code 字段命名与 stock_basic.ts_code 一致；日期字段遵循 trade_date（YYYYMMDD 字符串）；DO 类命名遵循 `StockIndicatorDailyDO`；列设计中标注原型图来源（screening.html → stock_indicator_daily）；索引建议与查询场景匹配
- [x] **03-schema.sql**：每条语句以 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` / `ALTER TABLE` 开头；字段顺序与 02-db-design.md 表格顺序完全一致；没有重复定义已有表；文件头部含设计依据注释引用 PRD 和原型图路径
- [x] **04-http-api.md**：0. 现有接口复用检查基于实时扫描（ScreeningController 全新路径 `/api/screening/*`）；每个接口请求参数明确（类型/必填/默认值/约束）；每个接口响应 data 给出完整 JSON 示例；JSON 字段为小驼峰命名（tsCode/tradeDate/firstHitDate/hitDaysCount/turnover10k/dailyChangePct）；分页/limit 参数写明；错误码与 ErrorCode.java 中定义一致；接口标注对应原型图页面操作
- [x] **关键验证**：假设自己是前端开发者，阅读 04-http-api.md + prototype 能否直接写出正确的 fetch 调用？答案：能。请求 JSON 结构（`rules` + `option`）在接口设计中明确；响应 data 结构完整示例给出；每字段含义/必填/默认值/数据类型明确；错误码与 message 文案齐全；每字段可据此写出正确的前端交互

## 8. 风格约束

1. **文档语言**：中文（与 PRD 及现有 design 文档风格一致）
2. **代码块语言标注**：SQL 用 ` ```sql `，JSON 用 ` ```json `，Java 用 ` ```java `，HTTP 用 ` ```http `
3. **与现有架构一致**：任何与 `CLAUDE.md` / `.claude/rules/` / `schema.sql` 冲突的设计，在主设计文档的「6. 风险与 TODO」中显式列出并说明替代方案
4. **不要编造具体 Tushare 接口名**：若 PRD 涉及新的外部数据源但接口未确定，写成 `ComputeGateway.<待定方法>` 并标注 TODO
5. **字段命名遵循现有习惯**：股票代码一律使用 `ts_code`（如 `000001.SZ`）；日期统一使用 `trade_date`（YYYYMMDD 字符串）；JSON 响应字段强制小驼峰（tsCode/tradeDate/createdAt）
6. **原型图引用的一致性**：引用原型图时使用相对项目根目录的完整路径（`sdlc/prd/<目录>/prototype/<文件>.html`）
