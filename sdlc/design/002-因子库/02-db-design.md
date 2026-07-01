# 002 因子库 — 数据库设计方案

> 设计依据：
> - PRD：`sdlc/prd/002-因子库/002-因子库PRD.md`
> - 因子清单：`sdlc/prd/002-因子库/20个标准股票因子.json`
> - 规则参考：项目知识库 `stock-watcher/.claude/rules/05-database.md`（SQLite 规范）

---

## 0. 现有表复用检查（基于实时扫描结果）

| 现有表 | PRD 是否需要 | 复用决策 | 说明 |
|---|---|---|---|
| `sys_user` | 是（鉴权） | 直接复用 | `@RequireAdmin` 用于刷新 registry 接口 |
| `daily_quote` | **核心输入** | **直接复用** | 因子计算的 OHLCV 数据源；`ts_code + trade_date` 主键提供按股票、按日期的高效查询 |
| `stock_basic` | 是（tsCode 校验） | 直接复用 | 预览接口中根据 `ts_code` 校验股票是否存在，展示中文股票名 |
| `trade_cal` | 是（日期对齐） | 直接复用 | 取最近 N 个交易日作为 K 线窗口边界 |
| `adj_factor` | 是（复权因子） | 间接复用 | 因子计算输入应使用**前复权**数据；`KlineService` 已提供复权逻辑，FactorGateway 读取 OHLCV 前先经复权处理 |
| `dividend` | 否 | 不使用 | 与因子计算无直接关系 |
| `sys_watchlist` | 否 | 不使用 | 本模块不处理自选股 |
| **新增表** | — | **不新增** | 因子计算为 on-the-fly 模式，不做结果持久化；指标预计算与选股条件落表由 `005-选股模块` 承担（但列名规则必须复用 §9.3） |

> 「直接复用」的表不在 `03-schema.sql` 中重复定义；本模块无 ALTER / 扩展字段需求。

---

## 1. 设计原则

- **SQLite 单文件 + WAL 并发**（与 stock-watcher 现有库一致）
- **表名 / 字段名：全小写 + 下划线分词（snake_case）**
- **与现有 `map-underscore-to-camel-case=true` 保持一致**：Java 端自动做小驼峰映射
- **主键策略**：沿用 `daily_quote(ts_code, trade_date)` 的自然主键模式，避免不必要的自增主键
- **所有 `ts_code` 字段统一使用 `6 位代码 + 市场后缀` 格式**（如 `000001.SZ`、`600519.SH`），与 `stock_basic.ts_code`、`daily_quote.ts_code` 一致
- **日期字段**：业务日期使用 `trade_date`（`YYYYMMDD` 字符串），记录时间戳使用 `created_at / updated_at`（ISO 格式，如 `2026-06-20 15:00:00`）
- **不新增表，不扩展字段**：本模块为纯计算服务，所有数据通过 Java 的 daily_quote 表输入，计算结果通过 HTTP JSON 返回

---

## 2. 表清单（复用 + 零新增）

| 表名 | 用途 | 主键 | 预计量级 | 是否需要索引 | 对应原型图页面 |
|---|---|---|---|---|---|
| `daily_quote` | 日线 OHLCV（因子计算的输入数据源） | `PRIMARY KEY (ts_code, trade_date)` | A 股约 5000 只股票 × 250 交易日/年 ≈ 每表年增 125 万行 | 已有主键，无需新增索引 | 策略配置器因子预览 |
| `stock_basic` | 股票基础信息（tsCode 合法性校验 + 名称展示） | `id INTEGER PRIMARY KEY`；`ts_code UNIQUE` | 约 5000 行 | 已有唯一索引，无需新增 | 预览页 tsCode 搜索建议 |
| `trade_cal` | 交易日历（取最近 N 个交易日的边界） | `PRIMARY KEY (exchange, cal_date)` | 约 3000 行/交易所 | 已有，无需新增 | 预览页日期范围选择 |
| `adj_factor` | 复权因子（前复权价输入） | `PRIMARY KEY (ts_code, trade_date)` | 与 daily_quote 同量级 | 已有，无需新增 | — |

> **结论**：本模块不需要新增任何表，所有数据库依赖均来自现有表。

---

## 3. 复用表的关键查询模式（给 SQL 读者）

以下列出 FactorGateway 在 Java 侧会对现有表执行的典型查询模式，便于在 005-选股模块 等下游模块中做索引 / 查询规划。

### 3.1 因子预览：取某股票最近 N 条 OHLCV

```sql
-- 对应 Java: DailyQuoteService.listByTsCode(String tsCode, int limit)
SELECT trade_date, open, high, low, close, vol, amount
  FROM daily_quote
 WHERE ts_code = '000001.SZ'
 ORDER BY trade_date DESC
 LIMIT 120;        -- 典型值 60 ~ 250，由前端通过 size 参数控制
```

> 现有主键 `(ts_code, trade_date)` 天然支持此查询（SQLite 主键即为索引）。

### 3.2 tsCode 合法性校验

```sql
-- 对应 Java: StockBasicService.findByTsCode(String tsCode)
SELECT ts_code, name, symbol, list_status
  FROM stock_basic
 WHERE ts_code = '000001.SZ';
```

> `ts_code` 已有 UNIQUE 约束，查询为 O(log n)。

### 3.3 最近交易日边界

```sql
-- 对应 Java: TradeCalService.getRecentTradeDates(int n)
SELECT cal_date, is_open
  FROM trade_cal
 WHERE exchange = 'SSE' AND is_open = '1'
 ORDER BY cal_date DESC
 LIMIT ?;
```

---

## 4. DO 类映射（无新增）

本次不新增 DO 类。Java 侧直接复用：

| 复用 DO | 用法 |
|---|---|
| `DailyQuoteDO` | 作为 FactorGateway#computeFactors 的 OHLCV 输入；字段 `tsCode / tradeDate / open / high / low / close / vol / amount` 由 MyBatis-Plus `map-underscore-to-camel-case=true` 自动映射 |
| `StockBasicDO` | 预览接口校验 tsCode 是否存在 |
| `TradeCalDO` | 取日期范围边界 |

> 若后续 005-选股模块 新增 `stock_indicator_daily` 表用于指标持久化，其列名应遵循：
> - 单输出因子：`ma_5`、`rsi_14`（注意：与策略 JSON 中 `MA_5 / RSI_14` 的大小写不同；数据库列通常统一小写，但**结果 key 的 JSON 命名为 `MA_5`**；两个维度的转换在 Java `FactorKeyUtil.resultKey()` 中统一管理）
> - 多输出因子：`macd_dif`、`macd_dea`、`macd_hist`、`kdj_k`、`kdj_d`、`kdj_j`
> - 价格直取列：直接复用 daily_quote 的 `close / high / low / volume`（不重复存）
>
> 上述约定不写入本模块 `03-schema.sql`，仅作为跨模块约束，供 005 模块引用。

---

## 5. 与现有表的兼容性

| 表 | 兼容状况 | 备注 |
|---|---|---|
| `daily_quote` | ✅ 完全兼容 | `ts_code, trade_date` 主键 + `open/high/low/close/vol` 字段是因子计算的标准输入 |
| `stock_basic` | ✅ 完全兼容 | `ts_code` 唯一值可直接用于因子预览接口的入参校验 |
| `trade_cal` | ✅ 完全兼容 | 提供日期范围对齐 |
| `adj_factor` | ✅ 完全兼容 | 通过 KlineService 计算得到前复权 OHLCV 作为输入 |
| `sys_user` | ✅ 完全兼容 | `FactorController#registry/refresh` 使用 `@RequireAdmin` 切面 |

> **无冲突，无新增表，无 ALTER。**

---

## 6. 验收相关

- [x] 03-schema.sql 不重复定义任何现有表
- [x] 所有设计讨论中对 SQLite 的数据类型严格使用 `INTEGER / TEXT / REAL`
- [x] 所有字段/表名讨论均为 snake_case，所有 JSON 字段讨论均为 camelCase
- [ ] 005-选股模块落地后，在该模块的设计文档中显式引用本文件 §9.3 的命名规则
