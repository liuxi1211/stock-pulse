# stock_suspend_d 表数据获取逻辑修复计划

## 一、现状问题诊断

### 1.1 核心问题：表结构与 Tushare suspend_d 接口完全不匹配

**当前表结构（错误）**：
```
stock_suspend_d:
  ts_code       VARCHAR(16)   -- 股票代码
  trade_date    VARCHAR(8)    -- 停牌日期
  susp_reason   VARCHAR(128)  -- 停牌原因
  resump_date   VARCHAR(8)    -- 复牌日期
  PRIMARY KEY (ts_code, trade_date)
```

**Tushare 实际数据（正确，用户提供样例）**：
```
ts_code     trade_date  suspend_timing  suspend_type
002199.SZ   20260722                    S           -- 全天停牌
301234.SZ   20260722                    R           -- 复牌
920079.BJ   20260722    10:09-10:19     S           -- 盘中临时停牌
```

**字段语义错误**：
- 错误模型：一条记录 = 一次停牌事件（有开始日期 trade_date、结束日期 resump_date）
- 正确模型：一条记录 = 一个每日事件（S=停牌 / R=复牌），事件可带时段

### 1.2 逐层问题清单

| 层级 | 文件 | 问题 |
|------|------|------|
| DTO | `SuspendDDTO.java` | 字段为 susp_reason/resump_date，与 Tushare 返回不符 |
| DO | `StockSuspendDDO.java` | 同上，字段与表不匹配 |
| Mapper | `StockSuspendDMapper.java` | existsSuspendedAt / selectByTsCodesAndRange 基于旧语义；countDateLogicErrors/countDateOverlap 基于区间模型 |
| Mapper XML | `StockSuspendDMapper.xml` | insertBatch / deleteBatchByKeys 字段错误；校验 SQL 语义错误 |
| Service | `StockSuspendDServiceImpl.java` | toEntity 映射错误；listSuspendDates 语义需重算（事件→状态）；checkData 检测项全错 |
| 表 DDL | `schema-mysql.sql` / `schema-sqlite.sql` | 列定义错误 |

### 1.3 listSuspendDates 语义问题

当前 `listSuspendDates` 直接返回所有 trade_date 作为停牌日期。但事件模型下：
- 表中存的是 S/R 事件，不是"停牌日期清单"
- 一次停牌持续 3 天 → 只有 1 条 S 记录 + 1 条 R 记录，中间 2 天在表里没有记录
- 必须通过"状态机"推导：追踪最近一次事件是 S 还是 R 来判定某日是否停牌

## 二、修复方案

### 2.1 表结构调整

**新表结构**：
```sql
CREATE TABLE stock_suspend_d (
    ts_code         VARCHAR(16)  NOT NULL COMMENT '股票代码',
    trade_date      VARCHAR(8)   NOT NULL COMMENT '交易日期（YYYYMMDD）',
    suspend_timing  VARCHAR(32)  COMMENT '停牌时段（空=全天，如 10:09-10:19）',
    suspend_type    VARCHAR(4)   NOT NULL COMMENT '类型：S=停牌，R=复牌',
    PRIMARY KEY (ts_code, trade_date),
    INDEX idx_suspend_tscode_date (ts_code, trade_date)
) COMMENT='停复牌表（每日事件）';
```

> 注：PK 用 (ts_code, trade_date)。若同一股票同日有多条盘中临时停牌事件，需将 suspend_timing 加入 PK；但日线回测场景下全天事件为主，暂保持此 PK。

### 2.2 DTO / DO 调整

- `SuspendDDTO`：`susp_reason` → `suspend_timing`，`resump_date` → `suspend_type`
- `StockSuspendDDO`：同步字段变更
- `SuspendDQueryDTO`：保持不变（ts_code/start_date/end_date 仍是 Tushare 标准参数）

### 2.3 Mapper 层调整

**移除的方法**：
- `countDateLogicErrors()` — 旧语义（停牌日期 > 复牌日期），作废
- `countDateOverlap()` — 旧语义（停牌区间重叠），作废

**新增/修改的方法**：
- `insertBatch` / `deleteBatchByKeys`：字段更新为 `suspend_timing`, `suspend_type`
- `selectByTsCodesAndRange`：保留，但字段映射更新
- `existsSuspendedAt`：语义改为"该日是否有 S 事件且为全天停牌"（简化版，后续由 listSuspendDates 统一处理）
- 新增 `selectAllEventsByTsCodes(List<String> tsCodes)`：取指定股票的全部历史事件（用于状态机计算）

### 2.4 Service 层调整

**核心方法**：
- `fetchAndSaveAll()`：逻辑不变（分页拉全量），字段映射更新
- `fetchAndSaveIncremental(tradeDate)`：逻辑不变（按日拉取），字段映射更新

**listSuspendDates 重写**：
- 输入：tsCodes, startDate, endDate
- 实现：
  1. 取这些股票的**全部历史事件**（到 endDate 为止）
  2. 按 ts_code 分组，每组内按 trade_date 升序
  3. 借助 trade_cal 取 [startDate, endDate] 内所有交易日
  4. 对每只股票，用状态机遍历交易日：
     - 初始状态 = 未停牌
     - 遇到事件时切换状态（S→停牌，R→复牌；盘中临时停牌忽略，因为仍可交易半天且日线有成交）
     - 记录状态为"停牌"的日期
  5. 返回 `Map<tsCode, Set<tradeDate>>`
- 依赖：注入 `TradeCalService`

**简化判断规则**：
- 仅全天停牌（suspend_timing 为空 或 NULL）才计入"停牌日期"
- 盘中临时停牌（有具体时段）不计入（日线级别仍有成交）

### 2.5 数据校验（DataCheckable）重写

新的检测项（替换旧的 2 项）：

| 检测项 | 级别 | 说明 |
|--------|------|------|
| `empty_check` | ERROR | 表空检测（空表跳过后续检测） |
| `type_validity` | ERROR | suspend_type 仅含 S/R，NULL 或其他值算异常 |
| `event_sequence` | WARN | 同一股票事件序列合理性：连续两次 S（中间无 R）、或首次事件为 R 等异常模式 |
| `latest_date_freshness` | WARN | 最新数据日期是否在合理范围内（与当前日期差 > 7 天告警） |

### 2.6 调用方适配

**BacktestServiceImpl.buildKlineData**：
- 现有 `suspendSet` 使用方式不变（`suspDates.contains(td)` 判断 is_suspended）
- 底层 listSuspendDates 返回的语义已修正为"实际处于停牌状态的日期"，调用方无需改动

**DataInitServiceImpl**：
- SUSPEND_D 的增量逻辑（按交易日循环调用 fetchAndSaveIncremental）已符合事件模型，无需改动
- 全量逻辑（fetchAndSaveAll）无需改动

### 2.7 Task 定时任务

- `StockSuspendDTask`：每日 16:35 增量、每月 1 号全量 — 逻辑无需改动，仅底层字段变更

## 三、涉及文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `stock-watcher/src/main/resources/schema-mysql.sql` | 修改 | stock_suspend_d 表结构 |
| `stock-watcher/src/main/resources/schema-sqlite.sql` | 修改 | stock_suspend_d 表结构 |
| `stock-watcher/src/main/java/.../dto/tushare/SuspendDDTO.java` | 修改 | 字段替换 |
| `stock-watcher/src/main/java/.../model/StockSuspendDDO.java` | 修改 | 字段替换 |
| `stock-watcher/src/main/java/.../mapper/StockSuspendDMapper.java` | 修改 | 方法增减 |
| `stock-watcher/src/main/resources/mapper/StockSuspendDMapper.xml` | 修改 | SQL 全部重写 |
| `stock-watcher/src/main/java/.../service/StockSuspendDService.java` | 修改 | 接口注释更新 |
| `stock-watcher/src/main/java/.../service/impl/StockSuspendDServiceImpl.java` | 修改 | 核心逻辑重写 |
| `stock-watcher/src/main/java/.../service/impl/BacktestServiceImpl.java` | 核对 | 调用方语义兼容性确认 |
| `stock-watcher/src/main/java/.../service/impl/DataInitServiceImpl.java` | 核对 | 增量/全量调用逻辑确认 |

## 四、风险与注意事项

1. **历史数据迁移**：表结构变更后，旧数据（susp_reason/resump_date）无法直接映射到新结构。建议全表清空后重新全量拉取。
2. **listSuspendDates 性能**：状态机计算需遍历每只股票的历史事件 + 区间交易日。停牌事件数量少（每天几十只），性能可接受。
3. **盘中临时停牌**：当前方案忽略盘中临时停牌（suspend_timing 非空的 S 事件）。若后续需精细处理，可扩展 is_suspended 的取值（如 "0"=正常 "1"=全天停牌 "2"=盘中临时停牌）。
4. **SQLite 兼容性**：新增 SQL 需保证 MySQL/SQLite 双兼容（避免 MySQL 专属语法）。
5. **首次全量拉取**：修复后首次全量重建会清空旧数据并重新拉取，期间查询可能返回空结果。
