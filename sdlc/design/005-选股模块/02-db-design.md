# 005 选股模块 — 数据库设计

> 设计依据：
> - 005-选股模块PRD.md §8.1 「指标预计算表（stock_indicator_daily）」
> - prototype/screening.html 原型图中使用的因子列（MA5 / MA20 / MACD / KDJ / RSI 等）
> - prototype/screening-range.html 原型图中使用的区间模式列（first_hit_date / hit_days_count 等）
> - 现有 schema.sql 中的表结构习惯（所有表的字段注释一致的 TEXT/REAL/BLOB 类型）
>
> 项目：Stock Watcher（Java Spring Boot + SQLite）
> 数据库约定：字段名 snake_case；数值用 REAL；整数用 INTEGER；日期用 TEXT（YYYYMMDD）；股票代码用 TEXT（格式如 `000001.SZ`）

---

## 0. 现有表复用分析（基于实时扫描 schema.sql）

当前 stock-watcher/src/main/resources/schema.sql 已定义的表：

| 表名 | 主键 | 本模块是否用到 | 说明 |
|------|------|----------------|------|
| sys_user | id | 否 | 用户表（权限体系） |
| sys_watchlist | UNIQUE(user_id, stock_code) | 否 | 自选股（本模块选股结果不由自选股承载） |
| daily_quote | PRIMARY KEY (ts_code, trade_date) | **是**（预计算输入源） | 最近 300 个交易日 OHLCV，供指标预计算读取 |
| stock_basic | id（ts_code UNIQUE） | **是**（JOIN 关联） | 提供 name / ts_code / list_date 用于结果展示 + 剔 ST + 剔次新股 |
| trade_cal | UNIQUE(exchange, cal_date) | **是**（交易日校验） | 判断查询日期是否为交易日；区间模式下计算交易日天数 |
| adj_factor | PRIMARY KEY (ts_code, trade_date) | 否 | 复权因子（001-K线方案管理） |
| dividend | UNIQUE(ts_code, end_date, ann_date) | 否 | 分红数据 |

**不重复建表清单**：以上 7 张表均不新建。本模块仅新建 1 张表：`stock_indicator_daily`。

---

## 1. 新建表：stock_indicator_daily

**存储内容**：每只股票每交易日的技术指标预计算结果（列名与 PRD §8.1 完全一致）

| 序号 | 字段名 | 数据类型 | 约束 | 说明 | 原型图来源（如明确） |
|------|--------|---------|------|------|-------------------|
| 1 | ts_code | TEXT | NOT NULL | 股票代码（000001.SZ 格式） | prototype/screening.html 命中表格第一列 ts_code |
| 2 | trade_date | TEXT | NOT NULL | 交易日期（YYYYMMDD，与 daily_quote.trade_date 一致） | prototype/screening-flow.html 数据流向说明 |
| 3 | close | REAL | | 收盘价（来自 daily_quote.close，保留一份以便选股查询时无需 JOIN daily_quote） | screening.html 结果表格中的「收盘价」列 |
| 4 | high | REAL | | 最高价（来自 daily_quote.high） | — |
| 5 | low | REAL | | 最低价（来自 daily_quote.low） | — |
| 6 | open | REAL | | 开盘价（来自 daily_quote.open） | — |
| 7 | volume | REAL | | 成交量（来自 daily_quote.volume） | 前置过滤中「最低成交额」的基础数据 |
| 8 | ma_5 | REAL | | MA(5)，简单移动平均 | screening.html 左侧因子选择器中 MA 因子（timeperiod=5） |
| 9 | ma_10 | REAL | | MA(10) | screening.html 因子选择器中 MA 因子（timeperiod=10） |
| 10 | ma_20 | REAL | | MA(20) | 金叉/死叉条件中 MA20 使用；screening.html 因子选择器中 MA 因子（timeperiod=20） |
| 11 | ma_60 | REAL | | MA(60) | screening.html 因子选择器中 MA 因子（timeperiod=60） |
| 12 | ma_120 | REAL | | MA(120) | screening.html 因子选择器中 MA 因子（timeperiod=120） |
| 13 | ma_250 | REAL | | MA(250) | screening.html 因子选择器中 MA 因子（timeperiod=250） |
| 14 | ema_5 | REAL | | EMA(5)，指数移动平均 | — |
| 15 | ema_10 | REAL | | EMA(10) | — |
| 16 | ema_20 | REAL | | EMA(20) | — |
| 17 | ema_60 | REAL | | EMA(60) | — |
| 18 | boll_upper | REAL | | BOLL 上轨（默认参数 n=20, k=2） | 未来 BOLL 相关条件 |
| 19 | boll_mid | REAL | | BOLL 中轨（= MA20） | 未来 BOLL 相关条件 |
| 20 | boll_lower | REAL | | BOLL 下轨 | 未来 BOLL 相关条件 |
| 21 | sar | REAL | | SAR（抛物转向） | 未来 SAR 相关条件 |
| 22 | rsi_6 | REAL | | RSI(6) | 超买/超卖条件（screening.html 因子选择器中 RSI 因子） |
| 23 | rsi_14 | REAL | | RSI(14) | screening.html 因子选择器中 RSI 因子（timeperiod=14） |
| 24 | rsi_28 | REAL | | RSI(28) | screening.html 因子选择器中 RSI 因子（timeperiod=28） |
| 25 | macd_dif | REAL | | MACD DIF（默认参数 fast=12, slow=26） | screening.html 因子选择器中 MACD 因子（outputIndex=0） |
| 26 | macd_dea | REAL | | MACD DEA（signal=9） | screening.html 因子选择器中 MACD 因子（outputIndex=1） |
| 27 | macd_hist | REAL | | MACD 柱状 = 2 × (DIF - DEA) | screening.html 因子选择器中 MACD 因子（outputIndex=2） |
| 28 | kdj_k | REAL | | KDJ K 值（默认参数 n=9, m1=3, m2=3） | screening.html 因子选择器中 KDJ 因子（outputIndex=0） |
| 29 | kdj_d | REAL | | KDJ D 值 | screening.html 因子选择器中 KDJ 因子（outputIndex=1） |
| 30 | kdj_j | REAL | | KDJ J 值 | screening.html 因子选择器中 KDJ 因子（outputIndex=2） |
| 31 | adx_14 | REAL | | ADX(14)，平均趋向指数 | 未来趋势判断条件 |
| 32 | plus_di_14 | REAL | | PLUS_DI(14)，正向指标 | 未来趋势判断条件 |
| 33 | minus_di_14 | REAL | | MINUS_DI(14)，负向指标 | 未来趋势判断条件 |
| 34 | willr_14 | REAL | | WILLR(14)，威廉指标 | 未来超买/超卖条件 |
| 35 | cci_14 | REAL | | CCI(14)，顺势指标 | 未来超买/超卖条件 |
| 36 | atr_14 | REAL | | ATR(14)，平均真实波幅 | 未来波动率条件 |
| 37 | vol_ma_5 | REAL | | VOL_MA(5)，成交量 5 日均 | screening.html 前置过滤「成交量判断」条件 |
| 38 | vol_ma_20 | REAL | | VOL_MA(20)，成交量 20 日均 | 放量条件 VOL > 1.5 × VOL_MA20 |
| 39 | vol_ma_60 | REAL | | VOL_MA(60)，成交量 60 日均 | — |
| 40 | obv | REAL | | OBV，能量潮 | 未来量价关系条件 |

**主键**：`PRIMARY KEY (ts_code, trade_date)` —— 自然主键，同一股票同一交易日只能有一条记录。

**索引**：

| 索引名 | 字段 | 说明 |
|--------|------|------|
| idx_sid_trade_date | ts_code, trade_date | 已由主键隐含。但显式建立可加速特定查询；此处依赖主键 B-Tree 即可 |
| idx_trade_date | trade_date | **建议建立**：本模块所有查询均按 trade_date 过滤（`WHERE trade_date = '20260520'`）；此索引对快照模式和区间模式均有显著收益 |

**索引 SQL**：见 03-schema.sql 中的 `CREATE INDEX IF NOT EXISTS idx_stock_indicator_daily_trade_date ON stock_indicator_daily(trade_date);`

**说明**：
- 38 个指标列对应 PRD §8.4 + §9.6 的标准参数组合；每列对应一个具体的因子引用（factorKey + params + outputIndex）
- 非标准参数（如 MA(8)）由 FactorColumnMapper 做降级映射到最近的标准参数列（见 01-main-design.md §4.4.2）
- 所有指标列可为 NULL（新股数据不足时前面的指标值为 NULL）；ConditionSqlBuilder 自动加 `IS NOT NULL` 过滤

---

## 2. 设计风格与约束

### 2.1 字段命名与类型一致性

| 约定 | 本模块遵循情况 |
|------|---------------|
| 字段名 snake_case | 是（`ts_code` / `trade_date` / `ma_5` / `macd_dif` ...） |
| 数值用 REAL | 是（所有指标列、OHLCV、volume 均为 REAL） |
| 整数用 INTEGER | 本模块无整数业务字段；主键由 `ts_code + trade_date` 构成 |
| 日期用 TEXT（YYYYMMDD） | 是（`trade_date` TEXT，与 daily_quote.trade_date 格式一致） |
| 股票代码 TEXT（000001.SZ） | 是（`ts_code` TEXT，与 stock_basic.ts_code 一致） |

### 2.2 主键策略

- 不使用自增主键（id INTEGER PRIMARY KEY AUTOINCREMENT）
- 使用**自然主键** `(ts_code, trade_date)`，理由：
  1. 两列组合天然唯一且不为空（同一股票同一交易日只能有一条指标记录）
  2. SQL 查询条件天然依赖这两列（按日期过滤、按 ts_code 关联 stock_basic）
  3. 避免引入冗余的自增 id 字段

### 2.3 与现有表的关联查询

| 查询场景 | 关联方式 | 字段映射 |
|----------|---------|---------|
| 命中股票展示（screening.html 表格） | stock_indicator_daily (ts_code) JOIN stock_basic (ts_code) | name ← stock_basic.name |
| 上市天数判断（前置过滤） | stock_indicator_daily (ts_code) JOIN stock_basic (ts_code) | list_date ← stock_basic.list_date |
| 交易日期校验 | query_date → trade_cal.cal_date | is_open ← trade_cal.is_open |
| 区间模式交易日数 | start_date / end_date → trade_cal.cal_date WHERE is_open='1' AND cal_date BETWEEN ? AND ? | — |

### 2.4 NULL 处理

- 所有指标列 `ma_5 / macd_dif / kdj_k / ...` 可为 NULL
- ConditionSqlBuilder 自动在 WHERE 子句中注入 `IS NOT NULL` 检查
- 规则树中引用了某列但该列为 NULL 的记录被排除在结果之外（即"新股数据不足时不参与选股"）

---

## 3. DO 类（Java 端数据对象）

```java
package com.arthur.stock.repository.screening;

/**
 * stock_indicator_daily 表的数据对象
 * 字段顺序与 02-db-design.md 表 1 的字段顺序完全一致
 */
@Data
public class StockIndicatorDailyDO {
    private String tsCode;           // 1
    private String tradeDate;        // 2
    private BigDecimal close;        // 3
    private BigDecimal high;         // 4
    private BigDecimal low;          // 5
    private BigDecimal open;         // 6
    private BigDecimal volume;       // 7
    private BigDecimal ma5;          // 8
    private BigDecimal ma10;         // 9
    private BigDecimal ma20;         // 10
    private BigDecimal ma60;         // 11
    private BigDecimal ma120;        // 12
    private BigDecimal ma250;        // 13
    private BigDecimal ema5;         // 14
    private BigDecimal ema10;        // 15
    private BigDecimal ema20;        // 16
    private BigDecimal ema60;        // 17
    private BigDecimal bollUpper;    // 18
    private BigDecimal bollMid;      // 19
    private BigDecimal bollLower;    // 20
    private BigDecimal sar;          // 21
    private BigDecimal rsi6;         // 22
    private BigDecimal rsi14;        // 23
    private BigDecimal rsi28;        // 24
    private BigDecimal macdDif;      // 25
    private BigDecimal macdDea;      // 26
    private BigDecimal macdHist;     // 27
    private BigDecimal kdjK;         // 28
    private BigDecimal kdjD;         // 29
    private BigDecimal kdjJ;         // 30
    private BigDecimal adx14;        // 31
    private BigDecimal plusDi14;     // 32
    private BigDecimal minusDi14;    // 33
    private BigDecimal willr14;      // 34
    private BigDecimal cci14;        // 35
    private BigDecimal atr14;        // 36
    private BigDecimal volMa5;       // 37
    private BigDecimal volMa20;      // 38
    private BigDecimal volMa60;      // 39
    private BigDecimal obv;          // 40
}
```

---

## 4. 数据容量估算（仅评估物理存储与查询性能）

| 参数 | 假设值 | 计算 |
|------|-------|------|
| 股票总数 | 约 5000 只（A 股主板 + 创业板 + 科创板） | — |
| 每年交易日 | 约 250 天 | — |
| 指标列数 | 40 列（OHLCV + 34 个指标 + volume + obv） | — |
| 每行平均大小 | 约 30 字节（TEXT(ts_code) 11 字节 + TEXT(trade_date) 8 字节 + 38 列 REAL × 8 字节 ≈ 约 300 字节） | 注：SQLite 对 REAL 的实际存储可变，估算时按每行 200~400 字节范围取中间值 |
| 一年数据量 | 5000 × 250 × 300 字节 ≈ 375 MB | — |
| 三年数据量 | 约 1.1 GB | — |
| 单行查询耗时 | 毫秒级（SQLite B-Tree 索引下，WHERE trade_date = '...' 使用 idx_stock_indicator_daily_trade_date 索引后可快速定位约 5000 行） | — |
| 区间查询（90 天） | 5000 × 90 = 45 万行；SQLite 纯内存操作下可在 2~3 秒内完成 | 需关注内存和磁盘 I/O，后续可拆为按月分区 |

**结论**：单表可承载 A 股三年历史数据（< 2 GB），SQLite 下查询性能可接受。如未来引入多周期（如 60 分钟线），需重新考虑分区策略或迁移到 PostgreSQL。
