# 项目专项检测清单（Project Checklist）

> code-review skill 的检测清单之一，配合 [`general-checklist.md`](./general-checklist.md) 使用。
> 本清单来自 StockPulse 项目的**硬约束**与**高频易错点**（CLAUDE.md §2.2 + §二·补 + Tushare 对接铁律），**违反即违规**，级别普遍为严重。
> 每项都指向权威文档：检测到疑似问题时，**去查权威文档确认细节，不要凭记忆判定**。

## 适用范围（智能裁剪入口）

| 变更范围 | 必查分组 |
|---|---|
| 涉及 engine（Python 侧）/ 跨系统调用 | A 架构分层（全查） |
| 涉及 watcher Controller / 接口 | B API 设计（全查） |
| 任何 Java 业务代码 | C 常量与魔法值 |
| 涉及 Mapper / SQL（通用数据库操作） | D 数据库操作（PJ-07） |
| 涉及 Tushare 拉取 / 新增数据接口 | E Tushare 对接铁律（全查，PJ-08~PJ-15） |
| 涉及回测 / 策略 / akquant 调用 | F akquant 回测（全查） |
| 涉及启动脚本 / 服务启动方式 | G 启动与运维 |

## 评级标准
- ❌ 不通过（严重）：违反硬约束，必须修复
- ⚠️ 风险/建议：当前可接受或建议修复

---

## A. 架构硬约束（engine 分层，spec 010 修订）

> 权威文档：[CLAUDE.md §2.2](../../../../CLAUDE.md)。核心：watcher 独占 DB，engine 只接收 watcher HTTP 传入的数据并返回 JSON。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-01 | engine 是否触库 | engine（Python）侧出现数据库驱动 / ORM / 直连 `.db` → ❌。**经 HTTP 调 watcher 只读接口不算触库** | 严重 | [CLAUDE.md §2.2](../../../../CLAUDE.md) |
| PJ-02 | engine 数据来源是否合规 | 行情 / 基本面必须由 watcher 预传，engine 反向拉取 → ❌ | 严重 | [CLAUDE.md §2.2](../../../../CLAUDE.md) |
| PJ-03 | engine 回调 watcher 是否越界 | 仅「参考数据」（成分股身份等）允许查 `/api/internal/*`；其他回调 → ❌。**注意**：参考数据只读查询（同机 localhost、无鉴权，如 `watcher_client.py`）是**合法例外，勿误判为违规** | 严重 | [CLAUDE.md §2.2](../../../../CLAUDE.md) |

## B. API 设计（stock-watcher）

> 权威文档：[04-api-design.md §11](../../../../.trae/rules/stock-watcher/java/04-api-design.md)。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-04 | API 参数是否过度未封装 | `@RequestParam` + `@PathVariable` **合计 > 5** 未封装 DTO（POST→`*RequestDTO` / GET→`*QueryDTO`）→ ❌。**计数口径**：仅统计 `@RequestParam`/`@PathVariable`；`@RequestBody`/`HttpSession`/`Model` 不计入 | 严重 | [04-api-design.md §11.1](../../../../.trae/rules/stock-watcher/java/04-api-design.md) |
| PJ-05 | API 请求/返回体是否用 Map | 请求体 / 返回体用 `Map<?,?>`（含 `Map<String,Object>`）→ ❌。必须用显式类型 `*RequestDTO`/`*ResponseDTO`/`*VO`。**仅 3 种例外**：跨系统透传 / 纯键值缓存返回 / Service 内部传输 | 严重 | [04-api-design.md §11.2](../../../../.trae/rules/stock-watcher/java/04-api-design.md) |

## C. 常量与魔法值

> 权威文档：[08-constants-usage.md](../../../../.trae/rules/stock-watcher/java/08-constants-usage.md)。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-06 | 魔法值是否抽取 | 散落的魔法值未抽到常量类（全大写命名）→ ⚠️；有 code+label 语义的未定义成 `DisplayableEnum` 枚举、未经 `GET /constants` + `StockApp.loadConstants` 下发前端 → ⚠️ | 建议 | [08-constants-usage.md](../../../../.trae/rules/stock-watcher/java/08-constants-usage.md) |

## D. 数据库操作（通用）

> 适用于所有 Mapper / SQL 操作（不限数据来源）。权威文档：[03-database-design.md](../../../../.trae/rules/stock-watcher/java/03-database-design.md)。涉及表结构先查 [`schema-mysql.sql`](../../../../stock-watcher/src/main/resources/schema-mysql.sql)。
> Tushare 落库的**分页 / 批量 / 事务**专项铁律见 [E 组](#e-tushare-对接铁律专项)。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-07 | 循环内单条 SQL | 循环体内含 `INSERT`/`UPDATE`/`DELETE` 且循环 > 3 次未批量化（`insertBatch` / `deleteBatchByKeys` / CASE WHEN 批量 UPDATE）→ ❌。单条操作仅限 ≤ 3 次或 < 10 条 | 严重 | [03-database-design.md §3.2](../../../../.trae/rules/stock-watcher/java/03-database-design.md) |

## E. Tushare 对接铁律（专项）

> 仅涉及 Tushare 拉取 / 新增数据接口时检查。权威文档：[02-tushare-integration.md](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md)（§0 铁律速查 + 各 Step）。涉及表结构先查 [`schema-mysql.sql`](../../../../stock-watcher/src/main/resources/schema-mysql.sql)。
> **PJ-08~PJ-12 为 5 条铁律（任一不满足禁止合入）**；PJ-13~PJ-15 为对接高频硬性要求。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-08 | Tushare 未分页（⚠️铁律 1） | 单次可能 > 5000 行未用 `offset`+`limit` 循环分页 → ❌（静默截断丢数据）。标杆 `TushareClient.queryWithPaging`：`PAGE_SIZE=5000`、`MAX_PAGES=100` 安全阀、每页回调落库不累积全量 | 严重 | [02-tushare §0 铁律1 / Step3](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-09 | 超量未降维（⚠️铁律 2） | 全量可能 > 10w 行未按时间 / 按标的拆分，且无 `offset >= 100000` 降级 → ❌。标杆 `StockSuspendDServiceImpl`：按月切（每月 ~2000 行）→ per-stock 拉取 | 严重 | [02-tushare §0 铁律2 / Step6](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-10 | Tushare 未限流 / 绕过通用方法（⚠️铁律 3） | 新接口未在 `application.yml` 配 `tushare.rate-limit.xxx`（触发 Tushare 端 429）→ ❌；绕过通用 `TushareClient.query()` 直接拼 HTTP、或绕过 RateLimiter → ❌ | 严重 | [02-tushare §0 铁律3 / Step3-4](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-11 | Tushare 跨 API 调用大事务（⚠️铁律 4） | `@Transactional` 包住分页拉取全流程（API 调用 + 限流等待全在大事务内，连接被占数分钟）→ ❌。必须用 `TransactionTemplate` 每页 / 每批一个独立短事务 | 严重 | [02-tushare §0 铁律4 / Step6](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-12 | 批量写入非 500 行/批（⚠️铁律 5） | 落库用循环内逐条 `INSERT`、未用 `Lists.partition(entities, 500)` → ❌。`BATCH_SIZE=500`（仅因子快照列少行小用 1000，例外） | 严重 | [02-tushare §0 铁律5 / Step6](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-13 | Tushare DTO 缺 `@JSONField` | 响应 DTO 字段未加 `@JSONField(name = "tushare字段名")` → ❌（FastJSON2 解析后值为 null）。下划线字段（`ts_code`→`tsCode`、`trade_date`）尤其必加 | 严重 | [02-tushare Step1](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-14 | `TushareApiEnum.fields` 不逐字一致 | `fields` 字符串与 Tushare 官方文档不一致（漏字段 / 拼写 / 分隔符 / 大小写）→ ❌（返回全 null，错一个字符即失效） | 严重 | [02-tushare Step2](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |
| PJ-15 | 漏 `DataCheckable` | 业务 Service 未实现 `DataCheckable`（缺 `getTableCode()` / `checkData()`）→ ⚠️。后果：数据管控中心看不到该表、无空表 / 行数变动 / 延迟检测 | 建议 | [02-tushare Step7](../../../../.trae/rules/stock-watcher/business/02-tushare-integration.md) |

## F. akquant 回测专项（stock-engine）

> 权威文档：[09-pitfalls-conventions.md](../../../../.trae/rules/akquant/09-pitfalls-conventions.md)。版本锁定 akquant 0.2.47。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-16 | 滑点是否用裸 float | `slippage` 用裸 float（`0.2` 会被当 **20%** 滑点）→ ❌。**一律用 dict** `{"type":"percent","value":0.0002}` | 严重 | [09-pitfalls-conventions.md](../../../../.trae/rules/akquant/09-pitfalls-conventions.md) |
| PJ-17 | 是否漏 T+1 | `broker_profile` 三个模板**都不含 `t_plus_one`**，未单独传 `t_plus_one=True` → ⚠️（A 股回测通常需要 T+1） | 建议 | [09-pitfalls-conventions.md](../../../../.trae/rules/akquant/09-pitfalls-conventions.md) |

## G. 启动与运维

> 权威文档：[startup.md](../../../../.trae/rules/startup.md)。

| # | 检查项 | 判定标准 | 级别 | 权威文档 |
|---|--------|---------|------|---------|
| PJ-18 | 启动是否绕过 run.js | 用 `mvn spring-boot:run` / 直接 `conda run ... uvicorn` / 新建 `.bat` / `.sh` 启动服务 → ❌。所有启动一律走 `node run.js start`（全栈）或子项目 `run.js` | 严重 | [startup.md](../../../../.trae/rules/startup.md) |
