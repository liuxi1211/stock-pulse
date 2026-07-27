# 数据管理中心接口优化 PRD

> **版本**：v2.0（实证调整版）
> **日期**：2026-07-27
> **状态**：最终版
> **依据**：26 个 Tushare 接口实测算证 + 25 张业务表现场核查
> **覆盖范围**：stock-watcher 模块数据中台

---

## 目录

1. [项目背景与目标](#一项目背景与目标)
2. [实证验证结论](#二实证验证结论)
3. [问题清单与影响分析](#三问题清单与影响分析)
4. [25 张表现状总览矩阵](#四25-张表现状总览矩阵)
5. [优化方案：P0 紧急修复](#五优化方案p0-紧急修复)
6. [优化方案：P1 全量截断修复](#六优化方案p1-全量截断修复)
7. [优化方案：P2 事务与并发优化](#七优化方案p2-事务与并发优化)
8. [优化方案：P3 可选优化](#八优化方案p3-可选优化)
9. [优化方案：P4 长期设计优化](#九优化方案p4-长期设计优化)
10. [分页改造决策矩阵与规范](#十分页改造决策矩阵与规范)
11. [接口查询参数核查详表](#十一接口查询参数核查详表)
12. [开发排期与依赖](#十二开发排期与依赖)
13. [验收标准与验证方法](#十三验收标准与验证方法)
14. [风险与回滚预案](#十四风险与回滚预案)
15. [附录：26 个接口验证详情](#附录26-个接口验证详情)

---

## 一、项目背景与目标

### 1.1 背景

stock-watcher 数据中台维护 25 张业务表，数据全部来自 Tushare API。近期核查发现以下核心问题：

1. **数据截断**：Tushare 默认返回 5000 条，部分接口全量/单日全市场超 5000 被静默截断，导致数据丢失
2. **事务范围过大**：12+ 个 Service 用 `@Transactional` 包裹整个方法（含 HTTP 调用），DB 连接被网络持有
3. **查询效率低**：财务类接口逐股票串行调用 5000+ 次，未利用接口批量能力
4. **设计不统一**：分页能力、事务模式、字段对齐等分散在各 Service，缺乏统一封装

### 1.2 目标

| 层级 | 目标 |
|---|---|
| **正确性（P0）** | 消灭所有静默截断，保证数据完整性 |
| **安全性（P1）** | 所有表 delete+insert 具备原子性，不丢数据 |
| **性能（P2）** | 财务类增量拉取耗时从 40 分钟降至 2 分钟以内 |
| **健壮性（P3）** | 全量重建有回滚能力，分页有上限防护 |
| **设计（P4）** | 统一分页能力、统一字段对齐、统一事务模式 |

### 1.3 核查范围

| 层 | 涉及文件 | 数量 |
|---|---|---|
| Client 层 | [TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java) | 通用 query() + 21 个业务方法 |
| 调度层 | [DataInitServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java) | 增量/全量分发 |
| Service 层 | 25 张表 ServiceImpl | 拉取+映射+落库 |
| 配置层 | [TushareApiEnum.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/constant/TushareApiEnum.java) | 各接口 fields 定义 |

### 1.4 重要前提（已实证）

> ✅ **Tushare 所有 26 个接口都支持 offset/limit 分页参数**
> - 默认返回最多 5000 行，超过被**静默截断**
> - limit 最大 10w，offset 累加超 10w 报错
> - 只要显式传 limit 就能突破默认 5000 截断

---

## 二、实证验证结论

> 本章节基于对 26 个 Tushare 接口的实测验证，修正了初始假设。

### 2.1 核心发现一：daily_basic 和 moneyflow 每天都在丢数据

**这是最紧急的问题**——不是全量才丢，是**增量每天都在截断**：

| 场景 | 实测条数 | 截断风险 | 影响 |
|---|---|---|---|
| daily_basic 单日全市场 | **5301 条** | 🔴 已超 5000 | 每天丢 ~300 只股票的基本面指标 |
| moneyflow 单日全市场 | **5052 条** | 🔴 已超 5000 | 每天丢 ~50 只股票的资金流向 |
| hk_hold 单日全市场 | 4084 条 | 🟡 临界安全 | 随股票数增长会超 |
| margin_detail 单日全市场 | 3880 条 | 🟡 临界安全 | 随股票数增长会超 |

### 2.2 核心发现二：财务接口 ts_code 必传性只有 4 个

初始假设"财务六表都支持 ann_date 全市场批量"不成立，实测结果：

| 接口 | ts_code 必传？ | 可用 ann_date 全市场？ | 可用日期范围全市场？ | 优化策略 |
|---|---|---|---|---|
| income | ✅ 必传 | ❌ 不行 | ❌ 不行 | 逐股票 + 并发 |
| balancesheet | ✅ 必传 | ❌ 不行 | ❌ 不行 | 逐股票 + 并发 |
| cashflow | ✅ 必传 | ❌ 不行 | ❌ 不行 | 逐股票 + 并发 |
| fina_indicator | ✅ 必传 | ❌ 不行 | ❌ 不行 | 逐股票 + 并发 |
| dividend | ❌ 可选 | ✅ 单日 1347 条 | ✅ 可行 | 可选改 ann_date 批量 |
| forecast | ❌ 可选 | ✅ 可行 | ❌ 需 ann_date 或 ts_code | 可选改 ann_date 批量 |
| express | ❌ 可选 | ✅ 可行 | ✅ 可行（月 86 条） | 可选改 ann_date 批量 |

**结论**：
- 4 个核心财务报表（income/balancesheet/cashflow/fina_indicator）**只能逐股票**，并发是最优解
- 3 个辅助表（dividend/forecast/express）可以全市场批量，但数据量小，收益有限

### 2.3 核心发现三：全量截断有 3 张表

| 场景 | 实测数据量 | 截断风险 | 当前影响 |
|---|---|---|---|
| stock_basic list_status=L 全量 | 5533 条 | 🔴 高 | 全量重建时截断 |
| trade_cal 30 年（SSE+SZSE） | 11323 条 | 🔴 高 | 全量重建时截断 |
| index_daily 单指数 30 年 | 5822~7527 条 | 🔴 高 | 全量重建时截断 |

### 2.4 核心发现四：单股票维度数据量都很小

所有"单只股票 + 全历史"的数据量都远低于 5000 截断线：

| 接口 | 单只股票 5 年 | 单只股票 30 年（估） | 截断风险 |
|---|---|---|---|
| income | 28 条 | ~168 条 | ✅ 无 |
| balancesheet | 31 条 | ~186 条 | ✅ 无 |
| cashflow | 27 条 | ~162 条 | ✅ 无 |
| fina_indicator | 34 条 | ~204 条 | ✅ 无 |
| dividend | 3 条 | ~18 条 | ✅ 无 |
| daily / adj_factor | 242 条 | ~1452 条 | ✅ 无 |
| stk_limit | 242 条 | ~1452 条 | ✅ 无 |
| namechange | 3 条 | ~18 条 | ✅ 无 |

**结论**：逐股票维度不存在截断问题，分页只在"全市场"或"全量长周期"场景需要。

### 2.5 26 个接口汇总表

| 接口 | 基准调用 | 必传参数 | 支持分页 | 截断场景 | 数据量级（单股5年） |
|---|---|---|---|---|---|
| income | ✅ | ts_code | ✅ | 无 | 28 条 |
| balancesheet | ✅ | ts_code | ✅ | 无 | 31 条 |
| cashflow | ✅ | ts_code | ✅ | 无 | 27 条 |
| forecast | ✅ | 无 | ✅ | 无 | 0 条 |
| express | ✅ | 无 | ✅ | 无 | 3 条 |
| dividend | ✅ | 无 | ✅ | 无 | 3 条 |
| fina_indicator | ✅ | ts_code | ✅ | 无 | 34 条 |
| daily | ✅ | 无 | ✅ | 无 | 242 条 |
| adj_factor | ✅ | 无 | ✅ | 无 | 242 条 |
| **daily_basic** | ✅ | 无 | ✅ | **单日全市场 5301** | 242 条 |
| **moneyflow** | ✅ | 无 | ✅ | **单日全市场 5052** | 242 条 |
| stk_limit | ✅ | 无 | ✅ | 无 | 242 条 |
| **stock_basic** | ✅ | 无 | ✅ | **全量 5533** | - |
| **trade_cal** | ✅ | 无 | ✅ | **30年 11323** | - |
| namechange | ✅ | 无 | ✅ | 无 | 3 条 |
| suspend_d | ✅ | 无 | ✅ | 无 | 0 条 |
| **index_daily** | ✅ | 无 | ✅ | **单指数30年 7527** | 242 条 |
| index_weight | ✅ | 无 | ✅ | 无 | - |
| index_member_all | ✅ | 无 | ✅ | 无 | - |
| hk_hold | ✅ | 无 | ✅ | 临界 4084 | 188 条 |
| top_list | ✅ | trade_date | ✅ | 无 | - |
| top_inst | ✅ | trade_date | ✅ | 无 | - |
| block_trade | ✅ | 无 | ✅ | 无 | 15 条 |
| margin | ✅ | 无 | ✅ | 无 | 3 条 |
| margin_detail | ✅ | 无 | ✅ | 临界 3880 | 242 条 |
| index_classify | ✅ | 无 | ✅ | 无 | - |

---

## 三、问题清单与影响分析

### 问题 1：limit/offset 静默截断

- **根因**：[TushareClient.query()](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java#L515) 不自动注入 limit/offset，由各业务方法决定。21 个方法中仅 6 个暴露了分页参数，其余 15 个未透传 → 调用方无法分页 → 默认截断 5000
- **影响范围**：2 张表增量截断（daily_basic/moneyflow）+ 3 张表全量截断（stock_basic/trade_cal/index_daily）+ 2 张表临界（hk_hold/margin_detail）
- **严重程度**：🔴 **最高** — 每天都在丢数据

### 问题 2：事务范围过大

**现状分三类**：

| 类别 | 模式 | 数量 | 问题 |
|---|---|---|---|
| A 类（推荐） | TransactionTemplate 仅包裹 DB 写入，HTTP 在事务外 | 4 | 无 |
| B 类（有问题） | `@Transactional` 包裹整个方法（含 HTTP） | 13 | DB 连接被网络持有，连接池耗尽风险 |
| C 类（有问题） | 无任何事务，delete+insert 非原子 | 3 | delete 成功 insert 失败会丢数据 |

- **最严重**：[TradeCalServiceImpl.fetchAndSaveTradeCal()](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java#L64) 事务包含 HTTP + saveCalendars + computeAndSaveRebalanceFlags（全表 select + 批量 update），事务持续最久
- **严重程度**：🟡 中 — 平时不报错，但高并发/网络慢时会有连接池耗尽风险

### 问题 3：内存堆积

- **现状**：流式拉取的 6 张表（查一页存一页）内存可控
- **风险点**：[StockBasicServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockBasicServiceImpl.java#L50) 三状态数据全累积到 allStocks 再统一保存（~1万条），非"查一批处理一批"
- **严重程度**：🟢 低 — ~1万条数据量不大，不会真的 OOM

### 问题 4：字段错误 / 命名不一致

- **整体良好**，snake_case DB ↔ camelCase Java 一致
- **注意点**：
  - `change` 关键字避让（DTO `change` → DO `changeAmt`）
  - IndexDailyDO 跳过 DTO 层直接 @JSONField 映射
  - TushareApiEnum fields 与 DTO @JSONField 不一致会静默丢字段（需专项核查）
- **严重程度**：🟢 低 — 未发现实际问题，但有潜在风险

### 问题 5：查询参数选择不合理

- **核心**：income/balancesheet/cashflow/fina_indicator 四个接口 ts_code 必传，只能逐股票，但当前**串行 for 循环 5000+ 次**，效率太低
- **次要**：dividend/forecast/express 可以 ann_date 全市场批量，但当前也在逐股票
- **严重程度**：🟡 中 — 不影响正确性，但增量拉取太慢（~40 分钟）

### 问题 6：分页能力分散，缺乏统一封装

- **现状**：每个 Service 自己写分页循环，写法不统一，容易遗漏 MAX_PAGES 防护
- **推荐**：在 TushareClient 通用 query() 层实现回调式分页 queryWithPaging，一次封装所有接口
- **严重程度**：🟢 低 — 设计层面问题，不影响当前功能

---

## 四、25 张表现状总览矩阵

> 图例：分页 ✅已处理/❌未处理/🟠临界 | 事务 A短事务/B包裹HTTP/C无 | 截断风险 🔴高/🟠临界/✅无

| 表 | 分页 | 事务 | 截断风险 | 查询参数 | 核心问题 | 优先级 |
|---|---|---|---|---|---|---|
| **daily_basic** | ❌ | C | 🔴 增量 | 🟠 临界 | **每天丢数据 + 无事务** | **P0** |
| **moneyflow** | ❌ | B | 🔴 增量 | 🟠 临界 | **每天丢数据 + 事务含HTTP** | **P0** |
| **stock_basic** | ❌ | C | 🔴 全量 | 🔴 全量截断 | 无分页 + 无事务 + 内存累积 | P1 |
| **trade_cal** | ❌ | B（最重） | 🔴 全量 | 🔴 全量截断 | 事务含HTTP+computeFlags | P1 |
| **index_daily** | ❌ | B | 🔴 全量 | 🔴 全量截断 | 无分页 + 事务含HTTP | P1 |
| income | ❌ | B | ✅ | 🔴 逐股票 | 事务含HTTP + 串行慢 | P2 |
| balancesheet | ❌ | B | ✅ | 🔴 逐股票 | 事务含HTTP + 串行慢 | P2 |
| cashflow | ❌ | B | ✅ | 🔴 逐股票 | 事务含HTTP + 串行慢 | P2 |
| fina_indicator | ❌ | B | ✅ | 🟡 API限制 | 事务含HTTP + 串行慢 | P2 |
| dividend | ❌ | B | ✅ | 🔴 未用ann_date | 事务含HTTP | P2 |
| hk_hold | ❌ | B | 🟠 临界 | 🟠 临界 | 事务含HTTP | P2 |
| margin_detail | ❌ | B | 🟠 临界 | 🟠 临界 | 事务含HTTP | P2 |
| margin | ❌ | B | ✅ | ✅ | 事务含HTTP（量小） | P2 |
| top_list | ❌ | B | ✅ | ✅ | 事务含HTTP（量小） | P2 |
| top_inst | ❌ | B | ✅ | ✅ | 事务含HTTP（量小） | P2 |
| block_trade | ❌ | B | ✅ | ✅ | 事务含HTTP（量小） | P2 |
| daily_quote | ✅ | A | ✅ | 🟡 可优化 | 无 MAX_PAGES 防护 | P3 |
| stk_limit | ✅ | A | 🟠 | ✅ | 无 MAX_PAGES 防护 | P3 |
| namechange | ✅ | A | 🟠 | ✅ | 无 MAX_PAGES 防护 | P3 |
| adj_factor | ✅ | A | ✅ | ✅ 已优化 | - | - |
| suspend_d | ✅ | A | ✅ | ✅ 最佳实践 | - | - |
| forecast | ❌ | A | 🟠 | 🔴 未用ann_date | 逐股票（量小） | P3 |
| express | ❌ | A | 🟠 | 🔴 未用ann_date | 逐股票（量小） | P3 |
| index_weight | ❌ | - | ✅ | ✅ | 量小 | - |
| sw_industry | ✅ | - | ✅ | ✅ | 量小 | - |

---

## 五、优化方案：P0 紧急修复

> **目标**：解决每天都在丢数据的问题
> **工时**：~1 人天
> **依赖**：无

### 任务 P0-1：修复 daily_basic 单日全市场截断

| 项 | 内容 |
|---|---|
| **问题** | daily_basic 按 trade_date 拉单日全市场返回 5301 条，默认被截断到 5000，每天丢 ~300 只股票 |
| **涉及文件** | [BasicDataServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/BasicDataServiceImpl.java)、[TushareClient.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java) |
| **方案** | 调用时显式传 `limit=10000`（单日全市场最多 ~6000，1w 足够） |
| **改动步骤** | ① TushareClient 的 daily_basic 方法增加 limit 参数透传；② Service 调用时传 limit=10000 |
| **改动量** | ~10 行 |
| **验收标准** | 拉取后当日数量 = 当日在市股票数（~5300），无截断 |

### 任务 P0-2：修复 moneyflow 单日全市场截断

| 项 | 内容 |
|---|---|
| **问题** | moneyflow 单日全市场 5052 条，超 5000 被截断 |
| **涉及文件** | [MoneyFlowServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/MoneyFlowServiceImpl.java)、TushareClient.java |
| **方案** | 同 daily_basic，显式传 limit=10000 |
| **改动量** | ~10 行 |
| **验收标准** | 拉取后当日数量与 daily_basic 接近（~5000+） |

---

## 六、优化方案：P1 全量截断修复

> **目标**：解决全量重建时的数据截断问题
> **工时**：~1 人天
> **依赖**：无

### 任务 P1-1：stock_basic 全量加分页

| 项 | 内容 |
|---|---|
| **问题** | list_status=L 全量 5533 条，被截断到 5000 |
| **涉及文件** | [StockBasicServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockBasicServiceImpl.java) |
| **方案** | 加 offset/limit 分页循环，limit=5000，MAX_PAGES=10（最多 5w 条，够用）；同时改造为流式落库（查一页存一页），消除内存累积 |
| **改动量** | ~40 行 |
| **验收标准** | 全量拉取后 list_status=L 数量 = 实测 5533 条左右 |

### 任务 P1-2：trade_cal 全量加分页

| 项 | 内容 |
|---|---|
| **问题** | 30 年 11323 条，被截断到 5000 |
| **涉及文件** | [TradeCalServiceImpl.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java) |
| **方案** | 加 offset/limit 分页循环，limit=5000，最多 3 页搞定 |
| **方案 B（备选）** | 按年份拆分拉取（每年 ~250 天，30 年 30 次） |
| **推荐方案** | 方案 A（分页），改动更小 |
| **改动量** | ~20 行 |
| **验收标准** | SSE 30 年数据量 ≈ 11323 条 |

### 任务 P1-3：index_daily 全量加分页

| 项 | 内容 |
|---|---|
| **问题** | 单指数 30 年 5822~7527 条，被截断到 5000 |
| **涉及文件** | [IndexDailyFetchService.java](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/IndexDailyFetchService.java) |
| **方案** | 加 offset/limit 分页循环，limit=5000，最多 2 页 |
| **改动量** | ~20 行 |
| **验收标准** | 上证指数 30 年 ≈ 7527 条 |

---

## 七、优化方案：P2 事务与并发优化

> **目标**：解决事务安全问题 + 提升财务类拉取效率
> **工时**：~3 人天
> **依赖**：P2-4 依赖 P2-1 先完成

### 任务 P2-1：B 类表拆事务（13 个 Service）

| 项 | 内容 |
|---|---|
| **问题** | 13 个 Service 的 `@Transactional` 包裹了 Tushare HTTP 调用，DB 连接在网络期间被持有 |
| **涉及文件** | income / balancesheet / cashflow / fina_indicator / moneyflow / dividend / index_daily / hk_hold / margin / margin_detail / block_trade / top_list / top_inst 各 ServiceImpl（共 13 个） |
| **方案** | 参照 [ForecastServiceImpl A 类模式](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/ForecastServiceImpl.java#L49)：移除方法级 `@Transactional`，仅 saveBatch 内用 TransactionTemplate 包裹 delete+insert |
| **模式代码** | `transactionTemplate.execute(status -> { saveBatch(); return null; })` |
| **改动量** | 每个 Service ~10 行，共约 130 行 |
| **验收标准** | 事务期间无 HTTP 调用，DB 连接持有时间 < 1s |

### 任务 P2-2：TradeCalServiceImpl 拆分 computeAndSaveRebalanceFlags

| 项 | 内容 |
|---|---|
| **问题** | [fetchAndSaveTradeCal()](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java#L64) 事务包含 HTTP + saveCalendars + computeAndSaveRebalanceFlags（全表 select + 批量 update），事务最重 |
| **方案** | ① HTTP 在事务外；② saveCalendars 用短事务；③ computeAndSaveRebalanceFlags 拆为独立事务 |
| **涉及文件** | TradeCalServiceImpl.java |
| **改动量** | ~30 行 |
| **验收标准** | 事务范围仅含 DB 写入 |

### 任务 P2-3：C 类无事务表补齐

| 项 | 内容 |
|---|---|
| **问题** | stock_basic / daily_basic / fina_indicator 的 saveBatch 无事务，delete 成功 insert 失败会丢数据 |
| **涉及文件** | [StockBasicServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockBasicServiceImpl.java#L154)、[BasicDataServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/BasicDataServiceImpl.java#L87)、FinaIndicatorServiceImpl |
| **方案** | saveBatch 内部用 TransactionTemplate 包裹 delete+insert |
| **改动量** | 每个 ~10 行，共 ~30 行 |
| **验收标准** | delete 成功 insert 失败时回滚，不丢数据 |

### 任务 P2-4：财务四表改并发（income/balancesheet/cashflow/fina_indicator）

| 项 | 内容 |
|---|---|
| **问题** | 这四个接口 ts_code 必传，只能逐股票。当前串行 for 循环 5000+ 次，耗时太长（~40 分钟） |
| **涉及文件** | IncomeServiceImpl、BalancesheetServiceImpl、CashflowServiceImpl、FinaIndicatorServiceImpl |
| **方案** | fetchAndSaveAllByRange 改成虚拟线程并发（已有 `IO_EXECUTOR`），并发度 20~50（受限于 Tushare 限流 200 次/分） |
| **并发策略** | 使用虚拟线程 + 信号量控制并发度（Semaphore(30)），每秒约 3 次，每分钟 ~180 次，留 20 次余量 |
| **预估收益** | 增量耗时从 40 分钟 → ~2 分钟（减少 95%） |
| **改动量** | 每个 ~15 行，共 ~60 行 |
| **依赖** | P2-1 先完成（事务拆分后，并发才安全） |
| **验收标准** | 增量拉取耗时 < 5 分钟；Tushare 无频繁限流触发 |

---

## 八、优化方案：P3 可选优化

> **目标**：锦上添花，非必须，有时间再做
> **工时**：~1 人天
> **依赖**：无

### 任务 P3-1：dividend/forecast/express 增量改 ann_date 全市场批量

| 项 | 内容 |
|---|---|
| **问题** | 当前也在逐股票拉，但这三个接口支持全市场批量 |
| **方案** | 增量改按 ann_date 或日期范围全市场批量拉取 |
| **收益** | dividend 从 5000 次 → 几十次；forecast/express 同理 |
| **优先级** | 低 — 这三个表数据量小、调用快，逐股票并发也很快，收益有限 |
| **注意** | forecast 需要 ann_date 或 ts_code 至少一个，日期范围方式可能不适用 |

### 任务 P3-2：hk_hold / margin_detail 加 limit 兜底

| 项 | 内容 |
|---|---|
| **问题** | 当前 4000 条左右，随股票数增长会超 5000 |
| **方案** | 显式传 limit=10000，提前规避 |
| **改动量** | 每个 ~3 行，共 ~10 行 |
| **优先级** | 中低 — 提前规避，避免未来出问题 |

### 任务 P3-3：已分页表加 MAX_PAGES 防护

| 项 | 内容 |
|---|---|
| **问题** | daily_quote / namechange / stk_limit 分页循环无 MAX_PAGES 防护，极端情况会死循环 |
| **涉及文件** | [DailyQuoteServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DailyQuoteServiceImpl.java#L266)、[StockNamechangeServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockNamechangeServiceImpl.java#L77)、[StockStkLimitServiceImpl](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockStkLimitServiceImpl.java#L55) |
| **方案** | 参照 [AdjFactorServiceImpl MAX_PAGES=100](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/AdjFactorServiceImpl.java#L220) 与 [StockSuspendDServiceImpl OFFSET_LIMIT=100000](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/StockSuspendDServiceImpl.java#L184) 降级模式，加 offset 累加上限校验 |
| **改动量** | 每个 ~5 行，共 ~15 行 |
| **优先级** | 中 — 防止极端情况死循环 |

---

## 九、优化方案：P4 长期设计优化

> **目标**：架构层面统一，提升可维护性
> **工时**：1~2 周
> **依赖**：无

### 任务 P4-1：TushareClient 统一分页能力 queryWithPaging

| 项 | 内容 |
|---|---|
| **问题** | 分页能力分散在各 Service，写法不统一，容易遗漏 |
| **方案** | 在通用 query() 层实现回调式分页 `queryWithPaging(api, params, clazz, batchSize, handler)`，内部循环 offset+=batchSize，每页回调 handler 处理（如落库），直到返回 < batchSize；强制 limit 上限 100000 校验 |
| **收益** | 新增表不用重复写分页逻辑；统一 MAX_PAGES 防护；代码更简洁 |
| **改动量** | TushareClient 新增 ~50 行；各 Service 逐步迁移 |

### 任务 P4-2：TushareApiEnum fields 与 DTO 专项对齐核查

| 项 | 内容 |
|---|---|
| **问题** | [parseResponse()](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/client/TushareClient.java#L544) 按 fields 数组位置组装 JSONObject，enum fields 与 DTO @JSONField 不一致会静默丢字段 |
| **方案** | 逐一比对 21 个接口的 TushareApiEnum.XXX.fields 与对应 DTO 的 @JSONField 名称 |
| **产出** | 字段对齐核查表 + 修正不一致的字段 |

### 任务 P4-3：全量重建回滚机制

| 项 | 内容 |
|---|---|
| **问题** | [doFullRebuild()](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java#L174) 先 TRUNCATE 再拉取，中途失败表为空 |
| **方案** | 改用"临时表 + RENAME"或"先插后删"策略 |
| **注意** | D 类表（日频行情类）已修复不 truncate，其余类仍需处理 |

---

## 十、分页改造决策矩阵与规范

### 10.1 三档改造规则

| 预估单次查询数据量 | 改造策略 | 说明 |
|---|---|---|
| **≤ 5000** | 无需分页，直接调 query() | Tushare 默认 5000 上限内 |
| **5000 ~ 10w** | 加 offset/limit 分页循环 | limit=5000，循环 offset+=5000 直到返回 < 5000；**必须加 MAX_PAGES 防死循环** |
| **> 10w** | **必须先调整查询参数拆分**，使单次 ≤ 10w，再分页 | 拆分维度：按日期范围 / 按股票 / 按年份 |

### 10.2 25 张表全量场景档位

| 表 | 全量单次查询维度 | 实测/预估数据量 | 档位 | 改造建议 | 当前状态 |
|---|---|---|---|---|---|
| stock_basic | list_status 单状态 | 5533 条（实测） | 5000~10w | 加 offset/limit 分页 | ❌ 未分页 |
| trade_cal | 单交易所 30 年 | 11323 条（实测） | 5000~10w | 分页或按年份拆分 | ❌ 未分页 |
| index_daily | 单指数 30 年 | 7527 条（实测） | 5000~10w | 分页或按年份拆分 | ❌ 未分页 |
| daily_quote | 单股票 30 年 | ~7300 条 | 5000~10w | ✅ 已分页 | ✅ 已分页 |
| adj_factor | 10 天窗口全市场 | ~5w | 5000~10w | ✅ 已分页（MAX_PAGES=100） | ✅ 已优化 |
| namechange | 全市场 30 年 | ~几万 | 5000~10w | ✅ 已分页；加 MAX_PAGES | 🟡 缺 MAX_PAGES |
| suspend_d | 单月全市场 | < 5000 | ≤ 5000 | ✅ 已拆分+降级 | ✅ 最佳实践 |
| stk_limit | 单股票 30 年 | ~7300 | 5000~10w | ✅ 已分页；加 MAX_PAGES | 🟡 缺 MAX_PAGES |
| income/balancesheet/cashflow | 单股票 30 年 | ~120 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| forecast/express | 单股票全历史 | < 100 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| fina_indicator | 单股票全历史 | ~40 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| dividend | 单股票全历史 | ~20 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| daily_basic/moneyflow | 单日全市场 | 5052~5301（实测） | 5000~10w | 加 limit=10000 或分页 | ❌ 未处理 |
| hk_hold/margin_detail | 单日全市场 | 3880~4084（实测） | 临界 | 加 limit 兜底提前规避 | 🟡 临界 |
| top_list/top_inst/block_trade | 单日 | < 500 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| margin | 单日单交易所 | 少量 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| index_weight | 单指数 5 年月频 | ~60 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| sw_industry/index_classify | 全量 | 少量 | ≤ 5000 | 无需分页 | ✅ 无风险 |
| index_member_all | 全量 | ~几千 | 5000~10w | ✅ 已分页 | ✅ 已分页 |

### 10.3 > 10w 场景调参示例

> 当前代码按股票/日期窗口拆分，大多数单次 ≤ 10w。若改造查询维度需注意：

| 改造场景 | 单次数据量 | 调参建议 |
|---|---|---|
| daily_quote 改为按 trade_date 全市场全历史 | 5000只×7300天 = **3650w** | 必须拆分：按年份或按股票 |
| adj_factor 改为全市场全历史不拆窗口 | **3650w** | 保持 10 天窗口拆分 |
| namechange 全量 30 年不按日期范围 | 可能 > 10w | 保持按日期范围 + 分页 |

### 10.4 分页实现规范

所有分页循环必须遵守以下规范：

```java
// 1. 必须有 MAX_PAGES 或 OFFSET_LIMIT 上限（防死循环）
private static final int MAX_PAGES = 100;
private static final int BATCH_SIZE = 5000;

// 2. 循环条件：返回数量 < BATCH_SIZE 时终止
int offset = 0;
int page = 0;
int total = 0;
while (page < MAX_PAGES) {
    List<T> batch = tushareClient.query(api, params, clazz, offset, BATCH_SIZE);
    if (batch.isEmpty()) break;
    // 处理当前页...
    total += batch.size();
    if (batch.size() < BATCH_SIZE) break;
    offset += BATCH_SIZE;
    page++;
}

// 3. 触达上限时打 warning 日志（不要静默）
if (page >= MAX_PAGES) {
    log.warn("分页达到上限 {} 页，可能有数据截断，total={}", MAX_PAGES, total);
}
```

---

## 十一、接口查询参数核查详表

> 覆盖：25 张表（21 个 Tushare 接口）
> 说明：所有 Tushare 接口都支持 offset/limit 分页，"支持的参数"列中省略 offset/limit
> 是否调整：✅ 合理（无需调整）/ ❌ 需调整 / 🟡 可选或 API 限制

| 接口名 | 支持的所有参数 | 增量当前参数 | 全量当前参数 | 增量调整 | 全量调整 | 增量调整原因 | 全量调整原因 |
|---|---|---|---|---|---|---|---|
| **daily_basic** | trade_date, ts_code | trade_date（单日全市场），无 limit | 同增量（逐日循环3年） | ❌ P0 | ❌ P0 | 全市场 5301 超 5000 截断 | 同增量 |
| **moneyflow** | trade_date, ts_code | trade_date（单日全市场），无 limit | 同上 | ❌ P0 | ❌ P0 | 全市场 5052 超 5000 截断 | 同上 |
| **stock_basic** | ts_code, list_status, exchange, market, name, is_hs | list_status 分3次(L/D/P)，无分页 | 同增量 | ❌ P1 | ❌ P1 | L状态 5533 条，无分页截断 | 同增量 |
| **trade_cal** | exchange, start_date, end_date, is_open | exchange+start_date(MAX)+end_date(today) | exchange+start_date(30年前)+end_date | ✅ 合理 | ❌ P1 | 增量续拉量小 | 全量30年 ~11323 截断 |
| **index_daily** | ts_code, start_date, end_date | ts_code+start_date(MAX)+end_date(today) | ts_code+start_date(30年前)+end_date | ✅ 合理 | ❌ P1 | 增量续拉量小 | 全量单指数30年 ~7527 截断 |
| hk_hold | trade_date, ts_code, exchange | trade_date（单日全市场），无 limit | 同上 | 🟡 P3 | 🟡 P3 | 4084 条临界，加 limit 提前规避 | 同上 |
| margin_detail | trade_date, ts_code | trade_date（单日全市场），无 limit | 同上 | 🟡 P3 | 🟡 P3 | 3880 条临界，加 limit 提前规避 | 同上 |
| income | ts_code, ann_date, start_date, end_date, period, report_type, comp_type | ts_code+start_date(MAX)+end_date(today) 逐股票串行 | ts_code+start_date(30年前)+end_date 逐股票串行 | ❌ P2 | 🟡 P3 | 事务含HTTP + 串行慢，改并发 | 全量单股票量小，可选改并发 |
| balancesheet | ts_code, ann_date, start_date, end_date, period, report_type, comp_type | 同 income | 同 income | ❌ P2 | 🟡 P3 | 同 income | 同 income |
| cashflow | ts_code, ann_date, start_date, end_date, period, report_type, comp_type | 同 income | 同 income | ❌ P2 | 🟡 P3 | 同 income | 同 income |
| fina_indicator | ts_code, start_date, end_date（报告期） | ts_code+start_date(MAX)+end_date 逐股票串行 | ts_code+start_date(30年前) 逐股票串行 | ❌ P2 | 🟡 P3 | 事务含HTTP + 串行慢，改并发 | API 限制只能逐股票 |
| dividend | ts_code, ann_date, start_date, end_date, record_date, ex_date, imp_ann_date | ts_code+start_date(MAX)+end_date 逐股票 | ts_code（全历史）逐股票 | ❌ P2 | 🟡 P3 | 事务含HTTP | 可选改 ann_date 批量（P3） |
| margin | trade_date, exchange_id | trade_date+exchange_id（单日单交易所） | 同上 | ❌ P2 | ❌ P2 | 事务含HTTP（量小） | 同左 |
| top_list | trade_date, ts_code | trade_date（单日） | 同上 | ❌ P2 | ❌ P2 | 事务含HTTP（量小） | 同左 |
| top_inst | trade_date, ts_code | trade_date（单日） | 同上 | ❌ P2 | ❌ P2 | 事务含HTTP（量小） | 同左 |
| block_trade | trade_date, ts_code | trade_date（单日） | 同上 | ❌ P2 | ❌ P2 | 事务含HTTP（量小） | 同左 |
| daily | ts_code, trade_date, start_date, end_date | ts_code+start_date(MAX)+end_date+offset/limit | ts_code+start_date(30年前)+end_date+offset/limit | 🟡 P3 | ✅ 合理 | 加 MAX_PAGES | 已分页 |
| stk_limit | ts_code, start_date, end_date | ts_code+start_date(MAX)+end_date+offset/limit | ts_code+start_date(30年前)+end_date+offset/limit | 🟡 P3 | 🟡 P3 | 加 MAX_PAGES | 加 MAX_PAGES |
| namechange | ts_code, start_date, end_date | start_date+end_date+offset/limit | 同增量（30年范围） | 🟡 P3 | 🟡 P3 | 加 MAX_PAGES | 加 MAX_PAGES |
| forecast | ts_code, ann_date, start_date, end_date, period | ts_code+start_date(MAX)+end_date 逐股票 | ts_code+start_date(30年前) 逐股票 | 🟡 P3 | 🟡 P3 | 量小收益有限 | 可选改 ann_date 批量 |
| express | ts_code, ann_date, start_date, end_date, period | 同 forecast | 同 forecast | 🟡 P3 | 🟡 P3 | 量小收益有限 | 可选改 ann_date 批量 |
| adj_factor | ts_code, trade_date, start_date, end_date | start_date+end_date(10天窗口)+offset/limit | start_date(30年前)+end_date+offset/limit | ✅ 合理 | ✅ 合理 | 已优化 + MAX_PAGES=100 | 已分页 |
| suspend_d | ts_code, start_date, end_date | start_date+end_date(按月拆分)+offset/limit | 同增量（30年按月） | ✅ 合理 | ✅ 合理 | 按月拆分+offset 10w降级，最佳实践 | 同左 |
| index_weight | index_code, trade_date, start_date, end_date | index_code+start_date(MAX)+end_date | index_code+start_date(5年前)+end_date | ✅ 合理 | ✅ 合理 | 月频量小 | 5年月频 ~60 条 |
| index_classify | src, level | src=SWS2021 | 同增量 | ✅ 合理 | ✅ 合理 | 量小 | 同左 |
| index_member_all | ts_code, index_code, src | index_code+src，带 offset/limit | 同增量 | ✅ 合理 | ✅ 合理 | 已分页 | 已分页 |

---

## 十二、开发排期与依赖

### 12.1 总体排期

| 阶段 | 任务 | 优先级 | 预估工时 | 依赖 | 交付物 |
|---|---|---|---|---|---|
| **Day 1** | P0-1：daily_basic 截断修复 | P0 | 0.5h | 无 | 代码 + 验证 SQL |
| | P0-2：moneyflow 截断修复 | P0 | 0.5h | 无 | 代码 + 验证 SQL |
| **Day 1** | P1-1：stock_basic 分页 | P1 | 2h | 无 | 代码 + 验证 SQL |
| | P1-2：trade_cal 分页 | P1 | 2h | 无 | 代码 + 验证 SQL |
| | P1-3：index_daily 分页 | P1 | 2h | 无 | 代码 + 验证 SQL |
| **Day 2-3** | P2-1：B 类表拆事务（13个） | P2 | 4h | 无 | 代码 |
| | P2-2：TradeCal 拆分 computeFlags | P2 | 2h | 无 | 代码 |
| | P2-3：C 类表补事务 | P2 | 2h | 无 | 代码 |
| **Day 4** | P2-4：财务四表改并发 | P2 | 4h | P2-1 | 代码 + 性能对比数据 |
| **可选** | P3-1 ~ P3-3 | P3 | 4h | 无 | 代码 |
| **长期** | P4-1 ~ P4-3 | P4 | 1~2 周 | 无 | 设计文档 + 代码 |

### 12.2 总计

- **P0+P1+P2 核心任务**：~5 人天
- **P3 可选任务**：~1 人天
- **P4 长期优化**：1~2 周

### 12.3 依赖关系图

```
P0-1, P0-2  ──┐
P1-1, P1-2, P1-3 ─┤
              ├──> P2-1 (B类拆事务) ──> P2-4 (财务并发)
P2-2, P2-3 ────┘
P3-1, P3-2, P3-3 （无依赖，随时可做）
P4-1, P4-2, P4-3 （长期，无依赖）
```

---

## 十三、验收标准与验证方法

### 13.1 截断修复验证

**SQL 验证脚本**：

```sql
-- ========================================
-- P0：daily_basic / moneyflow 单日数据量验证
-- ========================================
-- 选一个交易日，看条数是否合理（应 ~5000+）
SELECT COUNT(*) FROM daily_basic WHERE trade_date = '2024-04-30';
SELECT COUNT(*) FROM moneyflow WHERE trade_date = '2024-04-30';
-- 预期：5000 ~ 5500 之间

-- ========================================
-- P1：stock_basic 全量验证
-- ========================================
SELECT list_status, COUNT(*) as cnt
FROM stock_basic
GROUP BY list_status
ORDER BY cnt DESC;
-- 预期：L 状态 ~5500 条左右

-- ========================================
-- P1：trade_cal 全量验证
-- ========================================
SELECT exchange, COUNT(*) as cnt
FROM trade_cal
WHERE cal_date BETWEEN '1995-01-01' AND '2025-12-31'
GROUP BY exchange;
-- 预期：SSE ~ 5600，SZSE ~ 5700，合计 ~11300

-- ========================================
-- P1：index_daily 全量验证
-- ========================================
SELECT ts_code, COUNT(*) as cnt
FROM index_daily
WHERE trade_date BETWEEN '1995-01-01' AND '2025-12-31'
GROUP BY ts_code
ORDER BY cnt DESC;
-- 预期：上证指数 ~7500，沪深300 ~5800
```

### 13.2 事务优化验证

| 验证项 | 方法 | 验收标准 |
|---|---|---|
| 事务不含 HTTP | 开启事务日志，检查事务起止时间内是否有 HTTP 调用 | 事务期间无 HTTP 调用 |
| delete+insert 原子性 | 故障注入：模拟 insert 失败（如断网） | delete 回滚，数据不丢失 |
| DB 连接持有时间 | 监控连接池 active connection 时长 | < 1s |

### 13.3 并发优化验证

| 验证项 | 方法 | 验收标准 |
|---|---|---|
| 性能提升 | 对比改造前后增量拉取耗时 | 从 ~40 分钟降至 < 5 分钟 |
| 限流合规 | 监控 Tushare 限流触发次数 | 每分钟 < 200 次，无频繁限流 |
| 数据一致性 | 并发 vs 串行拉取结果对比 | 数据量一致，无重复/丢失 |

### 13.4 分页功能验证

| 验证项 | 方法 | 验收标准 |
|---|---|---|
| 分页正确性 | 分页拉取总数 vs 单页 limit=10w 拉取总数 | 数量一致 |
| MAX_PAGES 防护 | 构造超大数据量场景（或调小 MAX_PAGES） | 触达上限时打 warning 日志，正常退出，不死循环 |
| offset 边界 | offset + limit 正好等于总数时 | 下一页返回空，正确终止 |

---

## 十四、风险与回滚预案

### 14.1 风险清单

| 风险 | 影响 | 概率 | 等级 | 缓解措施 |
|---|---|---|---|---|
| P0 修复后 limit 参数传递错误 | 截断仍存在 | 低 | 中 | 上线前用 SQL 验证数据量 |
| 事务拆分引入新 bug（如漏加事务） | 某些场景丢数据 | 中 | 高 | 严格按 ForecastServiceImpl 模式复制；代码评审；故障注入测试 |
| 并发改造触发 Tushare 限流 | 拉取失败 / 速度更慢 | 中 | 高 | 初始并发度设低（20），逐步调优；加信号量控制 |
| 全量重建期间表不可用 | 数据中台服务中断 | 低 | 中 | 选择业务低峰期执行；P4-3 回滚机制远期解决 |
| 分页循环引入死循环 | 任务挂起、资源耗尽 | 低 | 中 | 所有分页必须加 MAX_PAGES 防护 |

### 14.2 回滚预案

#### P0/P1 截断修复回滚
- 风险低，只是加参数/加循环
- 若出现问题，直接 revert 对应 commit
- 数据影响：截断修复是**纯增量改善**，不会破坏已有数据

#### P2 事务改造回滚
- 每完成一个 Service 验证一个，不批量上线
- 若发现问题，快速回滚：把 TransactionTemplate 换回 @Transactional
- 数据影响：事务从"长"改"短"，最坏情况是回到原来的长事务模式，不会丢数据

#### P2 并发改造回滚
- 先灰度：先开一个表的并发，观察
- 若限流严重或数据不一致，切回串行（保留并发代码，加开关控制）
- 数据影响：并发 vs 串行结果一致，无数据风险

---

## 附录：26 个接口验证详情

> 本节为 26 个接口的实测验证明细，供开发时查阅。

### 必传参数对照表

| 接口 | 必传参数 | 说明 |
|---|---|---|
| income | ts_code | 必须传股票代码 |
| balancesheet | ts_code | 必须传股票代码 |
| cashflow | ts_code | 必须传股票代码 |
| fina_indicator | ts_code | 必须传股票代码 |
| top_list | trade_date | 必须传交易日期 |
| top_inst | trade_date | 必须传交易日期 |
| 其余 20 个 | 无必传参数 | 所有参数都是可选的 |

### 分页支持情况

✅ **全部 26 个接口都支持 offset/limit 分页**

### 各接口实测数据量

**单日全市场维度（最容易超 5000 的场景）**：

| 接口 | 单日全市场条数 | 超 5000？ |
|---|---|---|
| daily_basic | 5301 | ✅ 超 |
| stk_limit | 6716 | ✅ 超（但一般按股票拉） |
| moneyflow | 5088 | ✅ 超 |
| adj_factor | 5364 | ✅ 超（但已用日期窗口拆分） |
| daily | 5329 | ✅ 超（但一般按股票拉） |
| suspend_d | 5000 | ⚠️ 正好 5000，疑似截断（但按月拆分） |
| namechange | 10000 | ⚠️ 正好 10000（但按日期范围分页） |
| hk_hold | 1513 | ❌ 不超 |
| top_inst | 740 | ❌ 不超 |
| margin_detail | 3841 | ❌ 不超（临界） |
| block_trade | 124 | ❌ 不超 |
| top_list | 64 | ❌ 不超 |
| margin | 3 | ❌ 不超 |

**单只股票全历史维度**：

| 接口 | 5 年条数 | 30 年估算 | 超 5000？ |
|---|---|---|---|
| daily/adj_factor/daily_basic/moneyflow/stk_limit | 242 | ~1452 | ❌ |
| hk_hold | 188 | ~1128 | ❌ |
| fina_indicator | 34 | ~204 | ❌ |
| income | 28 | ~168 | ❌ |
| balancesheet | 31 | ~186 | ❌ |
| cashflow | 27 | ~162 | ❌ |
| block_trade | 15 | ~90 | ❌ |
| express/dividend/namechange | 3 | ~18 | ❌ |

### 验证方法

使用 [tushare_interface_validator.py](file:///d:/lcProject/stock-pulse/sdlc/prd/020-%E6%95%B0%E6%8D%AE%E7%AE%A1%E7%90%86%E4%B8%AD%E5%BF%83%E6%8E%A5%E5%8F%A3%E4%BC%98%E5%8C%96/tushare_interface_validator.py) 脚本进行全量验证，原始数据见 [验证结果.json](file:///d:/lcProject/stock-pulse/sdlc/prd/020-%E6%95%B0%E6%8D%AE%E7%AE%A1%E7%90%86%E4%B8%AD%E5%BF%83%E6%8E%A5%E5%8F%A3%E4%BC%98%E5%8C%96/%E9%AA%8C%E8%AF%81%E7%BB%93%E6%9E%9C.json)。

---

**文档结束**

> 本文档为 020-数据管理中心接口优化的唯一权威 PRD，其他中间文档（核查报告、参数核查表、验证报告、实证调整版等）均可删除。
