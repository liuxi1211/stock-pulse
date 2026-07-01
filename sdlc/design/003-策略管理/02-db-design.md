# 003 策略管理 — 数据库设计

> 参考资料索引：
> - 01-main-design.md（本模块主设计方案）
> - PRD：`sdlc/prd/003-策略管理/003-策略管理PRD.md`
> - Schema 参考：`sdlc/prd/003-策略管理/策略配置Schema.md`
>
> 数据库类型：SQLite
> 文件：`stock-watcher/stock-watcher.sqlite`（与现有 `schema.sql` 指向同一文件）

---

## 1. 现有表与新表

### 1.1 已存在表（**完全不改动其结构，策略模块只读/只读）

| 表名 | 策略模块使用方式 | 复用决策 |
|------|----------------|---------|
| `sys_user` | 预留关联 | 不改动 |
| `sys_watchlist` | 选股范围 `universe=WATCHLIST 时作为股票来源 | 不改动 |
| `stock_basic` | `estimateUniverseCount()` 中读取；方案 A 中需后续扩展 `is_index_300 / is_index_500` 列（**不在本设计表范围内**） | 不改动 |
| `trade_cal` | 不直接使用 | 不改动 |
| `daily_quote` | `simulate()` 读取 OHLCV（120 根 bar） | 不改动 |
| `adj_factor` | 不直接使用 | 不改动 |
| `dividend` | 不直接使用 | 不改动 |

### 1.2 本模块新表

| # | 表名 | 用途 | 是否新建 | 说明 |
|---|------|------|-----------|------|
| 1 | `quant_strategy` | 策略主表（最新规则快照 + 状态） | 新建 | 所有版本的**最新**规则（主表），版本表存储时间线 |
| 2 | `quant_strategy_version` | 策略版本表（时间线） | 新建 | 每次「保存为版本 N+1」时写入的完整 JSON 快照 |

---

## 2. 表结构

### 2.1 `quant_strategy` — 策略主表

> 核心字段：
> - `buy_rules` / `sell_rules` / `position_sizing` = 规则树 JSON（TEXT）
> - `universe` / `pre_filters` = 选股范围（TEXT + JSON）
> - `status` = `DRAFT` / `ACTIVE` / `ARCHIVED`（TEXT 字符串）
> - `strategy_key` = 业务代码（业务唯一，TEXT）
> - `category` = 分类：`TREND` / `MEAN_REVERT` / `MOMENTUM` / `CUSTOM` / `SCREEN_DRAFT`（TEXT）
> - `created_at` / `updated_at` = TEXT 日期，YYYY-MM-DD HH:mm:ss 格式（SQLite 无 DATE 类型）
> - `created_by` = 预留，TEXT，当前 admin 场景为 NULL/空字符串

**字段清单：

| 列名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | 是 | 主键 |
| `strategy_key` | TEXT UNIQUE NOT NULL | 是 | 业务代码（前端 slugify 或用户填写） |
| `name` | TEXT NOT NULL | 是 | 策略名称 |
| `description` | TEXT | 否 | 描述/备注 |
| `category` | TEXT NOT NULL DEFAULT 'CUSTOM' | 否 | 分类（默认 CUSTOM） |
| `status` | TEXT NOT NULL DEFAULT 'DRAFT' | 否 | DRAFT/ACTIVE/ARCHIVED（默认 DRAFT） |
| `buy_rules` | TEXT | 是 | 买入规则树 JSON（TEXT） |
| `sell_rules` | TEXT | 是 | 卖出规则树 JSON（含条件卖出 + 止损/止盈/最大持仓） |
| `position_sizing` | TEXT | 否 | 仓位管理 JSON（TEXT） |
| `universe` | TEXT NOT NULL DEFAULT 'ALL' | 否 | 选股范围代码：ALL / INDEX_300 / INDEX_500 / WATCHLIST / CUSTOM_CODES（默认 ALL） |
| `universe_filter` | TEXT | 否 | JSON：自定义股票代码列表 JSON 数组；只有当 universe=CUSTOM_CODES 时才写入 |
| `pre_filters` | TEXT | 否 | 前置过滤 JSON：`{ "excludeST": true, "excludeNewSharesDays": 60, "minAvgAmount": 50000000 }` |
| `created_at` | TEXT | 是 | 创建时间，TEXT 文本 |
| `updated_at` | TEXT | 是 | 更新时间，TEXT 文本 |
| `created_by` | TEXT | 否 | 预留，创建者标识 |
| `tags` | TEXT | 否 | 预留，标签 JSON 数组 |
| `remark` | TEXT | 否 | 预留，内部备注 |
| `backtest_last_id` | INTEGER | 否 | 最近一次关联的回测ID |
| `backtest_last_sharpe` | REAL | 否 | 最近一次回测夏普比率（REAL，可 NULL） |
| `backtest_last_total_return` | REAL | 否 | 最近一次回测总收益率（REAL，可 NULL） |
| `backtest_last_date` | TEXT | 否 | 最近一次回测时间（TEXT 日期，TEXT） |

> **数据类型说明：
> - 所有 JSON 字段（buy_rules / sell_rules / position_sizing / universe_filter / pre_filters / tags）统一用 TEXT 存储，长度在 SQLite 中无限制；Java 侧使用 `String`；写入/取出由 Java 自动处理；
> - 所有 REAL 用于存储数字类参数（percent/days/percent_return）；
> - `created_at` / `updated_at` / `created_by`/`backtest_last_date` 为 TEXT，符合 SQLite 习惯；
> - `backtest_last_id` 为 INTEGER，指向 004 回测中心的 backtest.id；
> - `status` 不建索引；
> - `category` 不建索引；

### 2.2 `quant_strategy_version` — 策略版本表

> 时间线存储：每次用户点击「保存为版本 N+1」时写入，内容为当前 `quant_strategy` 规则完整 JSON 快照；

**字段清单：

| 列名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | 是 | 主键 |
| `strategy_id` | INTEGER NOT NULL | 是 | 外键到 quant_strategy.id（SQLite 无外键约束，靠 Java 逻辑保证） |
| `version` | INTEGER NOT NULL | 是 | 版本号（1、2、3 …） |
| `buy_rules` | TEXT | 是 | 买入规则快照（TEXT JSON） |
| `sell_rules` | TEXT | 是 | 卖出规则快照（TEXT JSON） |
| `position_sizing` | TEXT | 否 | 仓位管理快照（TEXT JSON） |
| `universe` | TEXT NOT NULL DEFAULT 'ALL' | 否 | 选股范围快照 |
| `universe_filter` | TEXT | 否 | 自定义股票代码列表 JSON |
| `pre_filters` | TEXT | 否 | 前置过滤快照（TEXT JSON） |
| `change_note` | TEXT | 否 | 变更备注 |
| `backtest_id` | INTEGER | 否 | 关联的回测ID（来自 004） |
| `created_at` | TEXT | 是 | TEXT，创建时间 |
| `created_by` | TEXT | 否 | 预留 |
| `is_rollback_from` | INTEGER | 否 | 若是回滚产生，则此字段为原版本号；普通版本为 NULL |

> 唯一约束：`UNIQUE(strategy_id, version)：同一条策略的版本号唯一；

---

## 3. JSON Schema（字段的典型内容

### 3.1 `buy_rules` / `sell_rules.conditions` / `sell_rules.conditions` 部分 JSON 结构（来自 PRD §8 + Schema 参考：

```json
{
  "operator": "AND",
  "conditions": [
    {
      "type": "compare",
      "left": { "factor": "MA", "params": { "timeperiod": 5 } },
      "comparator": "cross_up",
      "right": { "factor": "MA", "params": { "timeperiod": 20 } }
    },
    {
      "type": "compare",
      "left": { "factor": "MACD", "params": { "fastperiod": 12, "slowperiod": 26, "signalperiod": 9 }, "output_index": 1 },
      "comparator": ">=",
      "right": { "value": 0 }
    }
  ]
}
```

> `sell_rules` 在 `sell_rules 额外包含特殊节点：

```json
{
  "conditions": [ /* ... 普通 compare 节点 ... */ ],
  "stopLossPercent": 5.0,
  "takeProfitPercent": 15.0,
  "maxHoldingDays": 20
}
```

> **JSON 字段小驼峰（Java 响应）；但存入数据库时保持 JSON 字符串形式，即实际写入 SQL 为 TEXT 列；

### 3.2 `position_sizing` JSON 典型内容：

```json
{
  "singleMaxPosition": 0.2,
  "maxPositions": 5,
  "minHoldingDays": 3
}
```

### 3.3 `pre_filters` JSON 典型内容：

```json
{
  "excludeST": true,
  "excludeNewSharesDays": 60,
  "minAvgAmount": 50000000
}
```

> Java DTO 对象的 getter/setter 按 camelCase；

### 3.4 `universe_filter` JSON（仅当 `universe` = `CUSTOM_CODES` 时使用）典型内容：

```json
[ "600000.SH", "000001.SZ", "300750.SZ" ]
```

### 3.5 `tags` JSON（可选）典型内容：

```json
[ "低波动", "趋势跟踪" ]
```

---

## 4. 索引设计

| 表 | 索引列 | 类型 | 说明 |
|----|--------|------|------|
| `quant_strategy` | `strategy_key` | UNIQUE | 业务代码唯一 |
| `quant_strategy` | `status` | NORMAL（无强制，数据量小时够用） | 按状态筛选列表 |
| `quant_strategy` | `category` | NORMAL | 按分类筛选 |
| `quant_strategy_version` | `strategy_id, version` | UNIQUE | 复合唯一（唯一版本） |
| `quant_strategy_version` | `strategy_id` | NORMAL | 查询时间线（按策略查询） |

> SQLite 中 `CREATE INDEX idx_qstg_status ON quant_strategy(status); 等语句建索引；
> 唯一索引用 `CREATE UNIQUE INDEX idx_qstg_version ON quant_strategy_version(strategy_id, version);

---

## 5. 与现有表的兼容性

- **不修改**现有任何表结构；`quant_strategy` / `quant_strategy_version` 以 `quant_` 前缀命名，避免与后续选股/回测模块冲突。
- `quant_strategy.backtest_last_id` / `quant_strategy_version.backtest_id` 为外键引用 004 回测中心的 backtest.id；但由于 SQLite 无外键约束，由 Java 业务侧在写入前校验（存在则写入，不存在则 404）。
- `universe`/`pre_filters` 字段中使用的 INDEX_300 / INDEX_500，需要 stock_basic 表存在对应列（`is_index_300` / `is_index_500`）；但该列不在本设计文件中声明，由 data-init 模块或 005 选股模块扩展；本设计仅提供方案 B（universe=CUSTOM_CODES + universe_filter 自定义代码列表作为兜底方案；详见 01-main-design.md §6 风险部分。
- SQLite 中 `created_at` / `updated_at` 使用 TEXT 类型，格式 `YYYY-MM-DD HH:mm:ss`；与现有 `schema.sql` 中其他表习惯一致。

---

## 6. 典型 SQL 使用示例

### 6.1 创建策略（INSERT）

```sql
INSERT OR REPLACE INTO quant_strategy (
    strategy_key, name, description, category, status,
    buy_rules, sell_rules, position_sizing,
    universe, universe_filter, pre_filters,
    created_at, updated_at, created_by
) VALUES (
    'demo-ma-crossover',
    'MA双均线交叉',
    '5日上穿20日买入，5日下穿20日卖出',
    'TREND',
    'DRAFT',
    '{"operator":"AND","conditions":[{"type":"compare","left":{"factor":"MA","params":{"timeperiod":5}},"comparator":"cross_up","right":{"factor":"MA","params":{"timeperiod":20}}}]}',
    '{"conditions":[{"type":"compare","left":{"factor":"MA","params":{"timeperiod":5}},"comparator":"cross_down","right":{"factor":"MA","params":{"timeperiod":20}}}],"stopLossPercent":5.0,"takeProfitPercent":15.0,"maxHoldingDays":20}',
    '{"singleMaxPosition":0.2,"maxPositions":5,"minHoldingDays":3}',
    'INDEX_300',
    NULL,
    '{"excludeST":true,"excludeNewSharesDays":60,"minAvgAmount":50000000}',
    '2025-07-10 14:30:00',
    '2025-07-10 14:30:00',
    'admin'
);
```

### 6.2 保存版本（INSERT）

```sql
INSERT INTO quant_strategy_version (
    strategy_id, version, buy_rules, sell_rules, position_sizing,
    universe, universe_filter, pre_filters, change_note, backtest_id,
    created_at, created_by, is_rollback_from
) VALUES (
    1, 1,
    '{"operator":"AND",...}',
    '{"conditions":[...],...}',
    '{"singleMaxPosition":0.2,"maxPositions":5,"minHoldingDays":3}',
    'INDEX_300',
    NULL,
    '{"excludeST":true,"excludeNewSharesDays":60,"minAvgAmount":50000000}',
    '初版',
    NULL,
    '2025-07-10 14:35:00',
    'admin',
    NULL
);
```

### 6.3 回滚到旧版本（UPDATE 主表 + INSERT 新版本（ROLLBACK 版本：

```sql
-- 1. 从版本 v_old 取规则写回主表
UPDATE quant_strategy
SET buy_rules = (SELECT buy_rules FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    sell_rules = (SELECT sell_rules FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    position_sizing = (SELECT position_sizing FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    universe = (SELECT universe FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    universe_filter = (SELECT universe_filter FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    pre_filters = (SELECT pre_filters FROM quant_strategy_version WHERE strategy_id = 1 AND version = 1),
    updated_at = '2025-07-10 15:00:00',
    status = 'DRAFT'
WHERE id = 1;

-- 2. 新建一条「回滚记录"版本：
INSERT INTO quant_strategy_version (strategy_id, version, ..., change_note, is_rollback_from, created_at, ...)
VALUES (1, (SELECT COALESCE(MAX(version), 0) + 1 FROM quant_strategy_version WHERE strategy_id = 1),
    (SELECT buy_rules FROM quant_strategy WHERE id = 1), ..., '回滚到版本 1', 1, '2025-07-10 15:00:00', 'admin', NULL);
```

### 6.4 筛选与分页（LIST）

```sql
SELECT id, strategy_key, name, description, category, status,
       created_at, updated_at
FROM quant_strategy
WHERE status IN ('DRAFT', 'ACTIVE')
  AND (name LIKE '%MA%' OR description LIKE '%MA%' OR strategy_key LIKE '%MA%')
  AND category = 'TREND'
ORDER BY updated_at DESC
LIMIT 20 OFFSET 0;
```

---

## 7. 与 DTO/DO 映射清单（供 Java 层参考）

- **DO 层字段（com.arthur.stock.model.QuantStrategyDO：

| 字段 | Java 类型 | 说明 |
|------|----------|------|
| `id` | Long | 主键 |
| `strategyKey` | String | 业务代码 |
| `name` | String | 策略名称 |
| `description` | String | 描述 |
| `category` | String | 分类 |
| `status` | String | 状态 |
| `buyRules` | String | JSON 字符串 |
| `sellRules` | String | JSON 字符串 |
| `positionSizing` | String | JSON 字符串 |
| `universe` | String | 选股范围代码 |
| `universeFilter` | String | JSON 字符串 |
| `preFilters` | String | JSON 字符串 |
| `createdAt` | String | TEXT 时间字符串 |
| `updatedAt` | String | TEXT 时间字符串 |
| `createdBy` | String | 预留 |
| `tags` | String | JSON 字符串 |
| `remark` | String | 内部备注 |
| `backtestLastId` | Long | 回测ID |
| `backtestLastSharpe` | Double | 夏普比率 |
| `backtestLastTotalReturn` | Double | 总收益率 |
| `backtestLastDate` | String | TEXT 日期字符串 |

- **QuantStrategyVersionDO 对应字段类似：

| 字段 | Java 类型 | 说明 |
|------|----------|------|
| `id` | Long | 主键 |
| `strategyId` | Long | 关联策略ID |
| `version` | Integer | 版本号 |
| `buyRules` | String | JSON 字符串 |
| `sellRules` | String | JSON 字符串 |
| `positionSizing` | String | JSON 字符串 |
| `universe` | String | 选股范围 |
| `universeFilter` | String | JSON 字符串 |
| `preFilters` | String | JSON 字符串 |
| `changeNote` | String | 变更备注 |
| `backtestId` | Long | 回测ID |
| `createdAt` | String | TEXT 时间字符串 |
| `createdBy` | String | 预留 |
| `isRollbackFrom` | Integer | 若是回滚则为原版本号；普通版本NULL |

> Java 层采用下划线；注意：MySQL 字段名/字段名，SQLite 列名 snake_case，数据库端字段名；

---

## 8. 字段类型与约束检查

| 检查项 | 结果 |
|---------|------|
| 字段类型是否使用 SQLite 支持的类型（INTEGER / TEXT / REAL） | ✅ |
| 主键/唯一索引用 INTEGER PRIMARY KEY AUTOINCREMENT 或 TEXT UNIQUE | ✅ |
| 布尔值存 TEXT 小驼峰 JSON 中用 true/false（JSON 原生），SQLite 中用 INTEGER（TRUE/FALSE） | ✅ |
| 时间字段存 TEXT，格式 `YYYY-MM-DD HH:mm:ss` | ✅ |
| 所有 JSON 字段存 TEXT（在 SQLite 中 TEXT 列） | ✅ |
| 是否避免使用 DATE/TIMESTAMP 等 SQLite 不支持的类型 | ✅ |
| 是否避免使用 MySQL 专有类型 | ✅ |
| JSON 字段/参数值/percent/days 等存 REAL/INTEGER | ✅ |
| 回测关联 ID 字段：REAL 字段使用 REAL 类型 | ✅ |
| 唯一约束：strategy_key 唯一；strategy_id + version 复合唯一； | ✅ |
| 新增表名使用 `quant_` 前缀，避免与其他模块冲突 | ✅ |
| `quant_strategy_version.strategy_id` 逻辑关联 `quant_strategy.id` | ✅ |

---

## 9. 表命名规范总结

- 表名：`quant_strategy` / `quant_strategy_version`（`quant_`前缀 + 英文描述）
- 字段名：snake_case（`buy_rules`, `sell_rules`, `position_sizing`, `universe`, `pre_filters`）
- Java 侧 DTO/DO：camelCase（`buyRules`, `positionSizing`, `backtestLastDate`）
- JSON 字段：camelCase（`stopLossPercent`, `maxHoldingDays`, `singleMaxPosition`）
- 统一使用 `INSERT OR REPLACE INTO` 批量 upsert（主表）；版本表只用 INSERT（不允许 UPDATE 历史版本）；

> 注：quant_strategy_version 不提供 UPDATE 操作，版本记录不可变；

