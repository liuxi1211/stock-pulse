# 流式拉取改造实施计划（Tushare 分页数据 拉一批处理一批）

> 范围：stock-watcher 模块。目标：将"先全量累积到内存再统一处理"改为"拉一页 → 转换+落库这一页 → 拉下一页"，节省内存、避免长事务、降低 Tushare 限流风险。
> 不涉及 spec 022（增量/全量逻辑正确性修复），不改数据库 schema。

---

## 一、概述

### 1.1 问题本质

当前 4 个 Service 存在"分页累积"模式：用 `while(true)` 循环拉取所有分页，`all.addAll(page)` 累积成一个大 List，循环结束后再统一 `toEntity + saveBatch`。这导致：

1. **内存峰值高**：所有页的 DTO + 所有页的 Entity 同时驻留内存。per-stock 30 年日线（~7500 条/2 页）在 20 并发线程下峰值达 20 × 15000 对象；
2. **长事务锁表**：`@Transactional` 包裹整个分页循环，API 限流等待期间数据库连接被占用、事务长时间持有锁；
3. **Tushare 限流风险**：所有 API 调用集中在短时间内密集发出（攒完再处理 → 攒完再调），容易触发限流。

### 1.2 改造核心思路

参照已有的 `SwIndustryServiceImpl.fetchAndSaveAllMembers`（流式参考实现）和 `ForecastServiceImpl`/`ExpressServiceImpl`（TransactionTemplate 模式）：

- **每拉一页立即处理**：`while(true)` 循环内，拉一页 DTO → 立即 `toEntity` + `saveBatch`（页内仍按 `BATCH_SIZE=500` 分批 delete+insert）→ 拉下一页；
- **每页独立事务**：用 `TransactionTemplate.execute()` 包裹每页的 DB 写入，API 调用在事务外（避免限流等待时占用连接）；
- **移除方法级 `@Transactional`**：原 `@Transactional` 包裹整个分页循环 → 改为每页一个 `TransactionTemplate` 小事务；
- **幂等保证安全**：所有 persist 方法均为"先删后插"（按业务键 delete+insert），页内部分成功后失败，重跑时会覆盖已写入的数据，不会产生重复。

---

## 二、现状分析

### 2.1 问题清单表（需改造的 4 个文件）

| # | 文件 | 累积位置（方法:行号） | 累积内容 | saveBatch 是否分批 | 当前 @Transactional | 返回值类型 | 返回值是否被使用 |
|---|------|----------------------|----------|-------------------|-------------------|-----------|----------------|
| 1 | `DailyQuoteServiceImpl.java` | `fetchAllPages`:L224-250 (`allRows.addAll(page)` @L242) | 单股票多页 / 单日全市场多页 | 是（BATCH_SIZE=500） | 是（L78,L85,L133）包裹整个 fetch+save | `List<DailyQuoteDTO>` | 是（`TushareApiController.initStockData` L39-40 返回给前端） |
| 2 | `StockStkLimitServiceImpl.java` | `fetchAndSaveAll`:L54-74 (`all.addAll(page)` @L64) | 全市场全日期多页（无过滤全表拉取） | 是（BATCH_SIZE=500） | 是（L53）包裹整个分页+save | `int`（计数） | 是（Task 日志） |
| 3 | `StockSuspendDServiceImpl.java` | `fetchAndSaveAll`:L57-77 (`all.addAll(page)` @L67) | 全市场全日期多页（无过滤全表拉取） | 是（BATCH_SIZE=500） | 是（L56）包裹整个分页+save | `int`（计数） | 是（Task 日志） |
| 4 | `StockNamechangeServiceImpl.java` | `fetchAndSaveAll`:L55-75 (`all.addAll(page)` @L65) | 全市场全日期多页（无过滤全表拉取） | 是（BATCH_SIZE=500） | 是（L54）包裹整个分页+save | `int`（计数） | 是（Task 日志） |

### 2.2 已确认无需改动的文件（21 个）

| 类别 | 文件 | 原因 |
|------|------|------|
| **per-stock 直接调用（无分页）** | `AdjFactorServiceImpl` | `tushareClient.adjFactor(param)` 单次返回，无分页 |
| | `IncomeServiceImpl` | 同上 |
| | `BalancesheetServiceImpl` | 同上 |
| | `CashflowServiceImpl` | 同上 |
| | `ForecastServiceImpl` | 同上，且已用 TransactionTemplate（参考模式） |
| | `ExpressServiceImpl` | 同上，且已用 TransactionTemplate（参考模式） |
| | `FinaIndicatorServiceImpl` | 同上 |
| | `DividendServiceImpl` | 同上 |
| **per-index 直接调用（无分页）** | `IndexDailyFetchService` | `tushareClient.fetchIndexDaily(tsCode,start,end)` 单次返回 |
| **D 类日频快照（单日单次调用）** | `DailyBasicServiceImpl` | 每日一次 `tushareClient.dailyBasic(date)` 单次返回 |
| | `MoneyflowServiceImpl` | 同上 |
| | `HkHoldServiceImpl` | 同上 |
| | `MarginServiceImpl` | 同上（同时处理 margin + margin_detail） |
| | `TopListServiceImpl` | 同上（同时处理 top_list + top_inst） |
| | `BlockTradeServiceImpl` | 同上 |
| **纯 DataCheckable（无 fetch 方法）** | `MarginDetailServiceImpl` | 仅 checkData()，fetch 逻辑在 MarginServiceImpl |
| | `TopInstServiceImpl` | 仅 checkData()，fetch 逻辑在 TopListServiceImpl |
| **A/F 类参考数据（无分页累积）** | `StockBasicServiceImpl` | 3 次 listStatus 调用合并（非分页累积），总量 ~5500 条，内存影响小 |
| | `TradeCalServiceImpl` | 按交易所直接调用，无分页 |
| | `IndexWeightServiceImpl` | 按指数直接调用，无分页 |
| **已是流式（参考实现）** | `SwIndustryServiceImpl` | `fetchAndSaveAllMembers` 已实现拉一页处理一页 |
| **调度分发（无直接累积）** | `DataInitServiceImpl` | 仅分发，不直接累积数据 |
| | `DataGovernanceServiceImpl` | `addAll` 仅用于合并检查项，非数据累积 |

### 2.3 已有的参考模式

**模式 A：流式分页（SwIndustryServiceImpl.fetchAndSaveAllMembers L120-146）**
```java
// 无 @Transactional，循环内每页立即 persist
int total = 0;
int offset = 0;
while (true) {
    List<IndexMemberDTO> page = tushareClient.indexMemberAll(param, offset, MEMBER_PAGE_SIZE);
    if (page.isEmpty()) break;
    total += persistMembers(page, effectiveSrc, today);  // 立即处理本页
    if (page.size() < MEMBER_PAGE_SIZE) break;
    offset += MEMBER_PAGE_SIZE;
}
```

**模式 B：TransactionTemplate 编程式事务（ForecastServiceImpl L48-70）**
```java
private final TransactionTemplate transactionTemplate;

// API 调用在事务外
List<ForecastDTO> rows = tushareClient.forecast(param);
// DB 写入才开启事务
transactionTemplate.execute(status -> {
    saveBatch(entities);
    return null;
});
```

> TransactionTemplate 由 Spring Boot 自动配置，直接 `@RequiredArgsConstructor` 注入即可，无需额外 Bean 声明（参见 StrategyServiceImpl L78-92 注释说明）。

---

## 三、改造方案

### 3.1 通用改造模式

对每个需改造的文件，统一应用以下模式：

1. **注入 TransactionTemplate**：新增 `private final TransactionTemplate transactionTemplate;` 字段（`@RequiredArgsConstructor` 自动注入）；
2. **移除方法级 `@Transactional`**：从 `fetchAndSaveAll` / `fetchAndSaveDailyQuotes` / `fetchAndSaveByTradeDate` 移除 `@Transactional` 注解；
3. **循环内流式处理**：`while(true)` 拉一页 → 立即 `toEntity` → `transactionTemplate.execute(saveBatch)` → 拉下一页；
4. **保留页内分批**：`saveBatch` / `persistByBizKey` 内部仍用 `Lists.partition(list, BATCH_SIZE=500)` 分批 delete+insert；
5. **保留幂等语义**：所有 persist 方法均为"先删后插"，部分页成功后失败重跑安全。

### 3.2 文件级改动要点

---

#### 文件 1：DailyQuoteServiceImpl.java

**路径**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/DailyQuoteServiceImpl.java`

**What**:
- 注入 `TransactionTemplate`；
- 将 `doFetchAndSaveDailyQuotes`（L90-127）和 `fetchAndSaveByTradeDate`（L134-155）从"先 fetchAllPages 再统一 save"改为流式：循环内每页立即 toEntity + saveBatch；
- 移除 `fetchAndSaveDailyQuotes`（L78,L85）和 `fetchAndSaveByTradeDate`（L133）的 `@Transactional`；
- 保留 `fetchAllPages` 给纯查询方法（`queryByCodeAndDateRange` L59、`queryByTradeDate` L70）使用——这两个方法不落库，必须返回完整 DTO 列表。

**Why**:
- per-stock 30 年日线在 20 并发线程下内存峰值高；
- `@Transactional` 包裹整个 fetch+save 导致 API 限流等待时数据库连接被占用；
- `fetchAllPages` 累积所有 DTO 再统一 toEntity，峰值同时持有全量 DTO + 全量 Entity。

**How**:
```
// doFetchAndSaveDailyQuotes 改造后伪代码
List<DailyQuoteDTO> allQuotes = new ArrayList<>();  // 仍累积 DTO 供 controller 返回
int offset = 0;
while (true) {
    DailyQueryDTO pageParam = DailyQueryDTO.builder()
            .tsCode(tsCode).startDate(startDate).endDate(endDate)
            .offset(offset).limit(PAGE_SIZE).build();
    List<DailyQuoteDTO> page = tushareClient.daily(pageParam);
    if (page.isEmpty()) break;

    allQuotes.addAll(page);  // 累积 DTO 供返回

    // 立即处理本页：toEntity + saveBatch（TransactionTemplate 小事务）
    List<DailyQuoteDO> entities = page.stream()
            .map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
    transactionTemplate.execute(status -> { saveQuotes(entities); return null; });

    log.info("daily_quote {} page saved: offset={}, size={}", tsCode, offset, entities.size());

    if (page.size() < PAGE_SIZE) break;
    offset += PAGE_SIZE;
}
return allQuotes;
```

**fetchAndSaveByTradeDate 改造**：同理流式，但返回值被所有调用方忽略（DailyUpdateTask L115、DataVerifyTask L147），可返回 `Collections.emptyList()` 或仍累积。建议仍累积 DTO 以保持接口语义一致性，但日志改为按页输出。

**注意**：`queryByCodeAndDateRange` 和 `queryByTradeDate` 不落库，继续用 `fetchAllPages` 累积返回，不改。

---

#### 文件 2：StockStkLimitServiceImpl.java

**路径**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockStkLimitServiceImpl.java`

**What**:
- 注入 `TransactionTemplate`；
- 将 `fetchAndSaveAll`（L54-74）从"累积所有页再 persistByBizKey"改为流式：循环内每页立即 `persistByBizKey(page)`；
- 移除 `fetchAndSaveAll`（L53）的 `@Transactional`；
- `persistByBizKey`（L120-134）本身无需改动——它已是按 (ts_code, trade_date) 先删后插，天然支持流式（同一 ts_code 跨页不会丢数据）。

**Why**:
- `fetchAndSaveAll` 无过滤全表拉取涨跌停价，数据量大（全市场 ~5000 股 × 数千交易日），多页累积内存高；
- `@Transactional` 包裹整个分页循环，长事务锁表。

**How**:
```
// fetchAndSaveAll 改造后伪代码
public int fetchAndSaveAll() {  // 移除 @Transactional
    int total = 0;
    int offset = 0;
    while (true) {
        List<StkLimitDTO> page = tushareClient.stkLimit(
                StkLimitQueryDTO.builder().build(), offset, PAGE_SIZE);
        if (page.isEmpty()) break;

        int saved = persistByBizKeyPage(page);  // 立即处理本页
        total += saved;
        log.info("stock_stk_limit page saved: offset={}, size={}, total={}", offset, page.size(), total);

        if (page.size() < PAGE_SIZE) break;
        offset += PAGE_SIZE;
    }
    return total;
}

// persistByBizKey 改为用 TransactionTemplate 包裹
private int persistByBizKeyPage(List<StkLimitDTO> rows) {
    if (rows.isEmpty()) return 0;
    List<StockStkLimitDO> entities = rows.stream()
            .map(this::toEntity).filter(e -> e != null).collect(Collectors.toList());
    return transactionTemplate.execute(status -> {
        int count = 0;
        for (List<StockStkLimitDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stockStkLimitMapper.deleteBatchByKeys(batch);
            count += stockStkLimitMapper.insertBatch(batch);
        }
        return count;
    });
}
```

**注意**：`fetchAndSaveIncremental`（L78）和 `fetchAndSaveByRange`（L88）是单次调用（无分页），保留 `@Transactional` 不改。

---

#### 文件 3：StockSuspendDServiceImpl.java

**路径**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockSuspendDServiceImpl.java`

**What**:
- 注入 `TransactionTemplate`；
- 将 `fetchAndSaveAll`（L57-77）从"累积所有页再 persistByBizKey"改为流式；
- 移除 `fetchAndSaveAll`（L56）的 `@Transactional`；
- `persistByBizKey`（L190-204）无需改动——已按 (ts_code, trade_date) 先删后插，支持流式。

**Why/How**：与 StockStkLimitServiceImpl 完全相同模式，不赘述。

**注意**：`fetchAndSaveIncremental`（L80）是单次调用，保留 `@Transactional` 不改。

---

#### 文件 4：StockNamechangeServiceImpl.java

**路径**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockNamechangeServiceImpl.java`

**What**:
- 注入 `TransactionTemplate`；
- 将 `fetchAndSaveAll`（L55-75）从"累积所有页再 persistByTsCode"改为流式；
- 移除 `fetchAndSaveAll`（L54）的 `@Transactional`；
- **关键决策**：流式版本改用 `persistByBizKey`（L129-143，按 ts_code+start_date 先删后插）而非 `persistByTsCode`（L103-124，按 ts_code 批量删除全部再插入）。

**Why 改用 persistByBizKey**:
- `persistByTsCode` 先按 ts_code `IN(...)` 删除该股票全部记录再插入。若流式处理时同一 ts_code 跨多页，第二页的 delete 会删掉第一页刚插入的数据，导致数据丢失；
- `persistByBizKey` 按 (ts_code, start_date) 逐条先删后插，天然幂等，同一 ts_code 跨页安全；
- 全量重建路径下 `DataInitServiceImpl.rebuildTable` 会先 TRUNCATE 表，所以无历史残留需清理；
- 定时任务路径（StockNamechangeTask）直接调 `fetchAndSaveAll`，改用 `persistByBizKey` 后不会删除"源已删除但本地仍有"的过期记录，但 namechange 数据极少从源删除，可接受。

**How**:
```
// fetchAndSaveAll 改造后伪代码
public int fetchAndSaveAll() {  // 移除 @Transactional
    int total = 0;
    int offset = 0;
    while (true) {
        List<NamechangeDTO> page = tushareClient.namechange(
                NamechangeQueryDTO.builder().build(), offset, PAGE_SIZE);
        if (page.isEmpty()) break;

        int saved = persistByBizKeyPage(page);  // 用 persistByBizKey（非 persistByTsCode）
        total += saved;
        log.info("stock_namechange page saved: offset={}, size={}, total={}", offset, page.size(), total);

        if (page.size() < PAGE_SIZE) break;
        offset += PAGE_SIZE;
    }
    return total;
}

// 新增 persistByBizKeyPage，用 TransactionTemplate 包裹 persistByBizKey 逻辑
private int persistByBizKeyPage(List<NamechangeDTO> rows) {
    if (rows.isEmpty()) return 0;
    List<StockNamechangeDO> entities = rows.stream()
            .map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
    return transactionTemplate.execute(status -> {
        int count = 0;
        for (List<StockNamechangeDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            stockNamechangeMapper.deleteBatchByKeys(batch);
            count += stockNamechangeMapper.insertBatch(batch);
        }
        return count;
    });
}
```

**注意**：`fetchAndSaveIncremental`（L78）已用 `persistByBizKey`，保留 `@Transactional` 不改。原 `persistByTsCode` 方法保留（可能被其他路径调用），不删除。

---

## 四、假设与决策

### 4.1 关键决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | 用 `TransactionTemplate`（编程式事务）而非 self-injection | 代码库已有先例（ForecastServiceImpl、ExpressServiceImpl、StrategyServiceImpl），且 TransactionTemplate 由 Spring Boot 自动配置，注入零成本；避免 self-injection 的代理陷阱 |
| D2 | 每页一个独立事务，移除方法级 `@Transactional` | 原模式 `@Transactional` 包裹整个分页循环 = 长事务（API 限流等待时持锁）；改为每页小事务，事务持续时间仅 DB 写入（毫秒级） |
| D3 | 部分页成功后失败不回滚已提交页 | 所有 persist 均为幂等"先删后插"，重跑时覆盖已写入数据，不产生重复；全量重建路径先 TRUNCATE 更无风险 |
| D4 | DailyQuoteServiceImpl 的 fetchAndSave 方法仍累积 DTO 返回 | `TushareApiController.initStockData`（L39-40）将 DTO 列表返回给前端，不能破坏接口契约；但 Entity 不再全量累积（每页处理完即可 GC），峰值内存降低 |
| D5 | DailyQuoteServiceImpl 的纯查询方法（queryByCodeAndDateRange、queryByTradeDate）不改 | 它们不落库，必须返回完整 DTO 列表，且无 Entity 累积问题 |
| D6 | StockNamechangeServiceImpl 流式版本改用 persistByBizKey | persistByTsCode 按 ts_code 批量删除，流式时同 ts_code 跨页会丢数据；persistByBizKey 逐条先删后插，天然安全 |
| D7 | 不改 StockBasicServiceImpl | 3 次 listStatus 调用合并非分页累积，总量 ~5500 条内存影响小，且每次调用返回不同状态股票无分页 |
| D8 | 不改 D 类日频快照表 | 每日单次 API 调用返回当日全市场数据（无分页或单页），spec 022 已做逐日分批，无需流式 |

### 4.2 前置假设

1. `TransactionTemplate` Bean 由 Spring Boot 自动配置（DataSource → PlatformTransactionManager → TransactionTemplate），已由 ForecastServiceImpl 验证可行；
2. Tushare 各接口的分页参数（offset/limit）行为不变，`page.size() < PAGE_SIZE` 判断最后一页的逻辑正确；
3. 所有 `deleteBatchByKeys` + `insertBatch` 的 Mapper 方法在事务内执行，跨页独立事务不会相互影响；
4. SQLite 和 MySQL 均支持短事务的 delete+insert，不会因事务隔离级别导致问题。

---

## 五、验证步骤

### 5.1 编译验证
```bash
cd stock-watcher && mvn compile -q
```
确认 4 个文件编译通过，无 import 缺失（`TransactionTemplate`）。

### 5.2 单表全量触发验证（以 StockStkLimit 为例）

1. 触发全量重建：通过数据管控中心 UI 或 API 触发 `stk_limit` 全量重建；
2. 查日志确认按页落库：
   - 预期看到多次 `stock_stk_limit page saved: offset=0, size=5000, total=5000` → `offset=5000, size=5000, total=10000` → ... → `offset=N, size=<5000, total=M`；
   - 不应再看到单条 `Saved N stock_stk_limit records`（旧模式的统一落库日志）；
3. 查数据库确认数据完整：`SELECT COUNT(*) FROM stock_stk_limit` 与日志 total 一致。

### 5.3 单表增量触发验证（以 DailyQuote 为例）

1. 触发单股增量：通过 API `POST /tushare/daily/init/{tsCode}` 或数据管控中心触发 `daily` 增量；
2. 查日志确认按页落库：
   - 预期看到 `daily_quote {tsCode} page saved: offset=0, size=5000` → `offset=5000, size=2500`（示例）；
3. 确认返回值正常：API 调用应返回完整 DTO 列表（controller 路径）。

### 5.4 事务独立性验证

1. 在第 2 页处理时人为制造异常（如临时在 Mapper 中抛异常）；
2. 确认第 1 页数据已落库（独立事务已提交）；
3. 恢复后重跑，确认第 1 页数据被覆盖（幂等），第 2 页正常写入；
4. 最终数据完整无重复。

### 5.5 内存占用验证

1. 对比改造前后 per-stock 全量拉取的 JVM 堆内存峰值：
   - 改造前：全量 DTO + 全量 Entity 同时驻留；
   - 改造后：累积 DTO + 单页 Entity（Entity 可被 GC 回收）；
2. 在 20 并发线程拉取场景下，用 JVM 监控（如 VisualVM 或 `-XX:+PrintGCDetails`）对比 GC 频率和堆峰值。

### 5.6 全量回归验证

逐表触发全量重建，确认 4 张表（daily_quote、stk_limit、suspend_d、namechange）数据完整：
```sql
SELECT 'daily_quote' AS t, COUNT(*) AS cnt FROM daily_quote
UNION ALL SELECT 'stk_limit', COUNT(*) FROM stock_stk_limit
UNION ALL SELECT 'suspend_d', COUNT(*) FROM stock_suspend_d
UNION ALL SELECT 'namechange', COUNT(*) FROM stock_namechange;
```
与改造前记录数对比（应一致或更多，不应减少）。

---

## 六、实现偏离说明

实际实现相较上述计划有两处优化偏离，记录如下：

1. **StockNamechangeServiceImpl 删除了 `persistByTsCode` 方法**
   - 计划（§3.2 文件4 / 决策 D6 备注）写"原 `persistByTsCode` 方法保留（可能被其他路径调用），不删除"；
   - 实现将其删除。核校结论：该方法是 `private`，删后全代码库无残留引用，删除是正确的（避免死代码）。

2. **DailyQuoteServiceImpl `fetchAndSaveByTradeDate` 未累积 DTO**
   - 计划（§3.2 文件1 末尾）建议"仍累积 DTO 以保持接口语义一致性"；
   - 实现选择 `collectResult=false`，返回 `Collections.emptyList()`。核校结论：两个调用方（`DailyUpdateTask:115`、`DataVerifyTask:147`）均不使用返回值，选择不累积更省内存，是更优的工程取舍；已在接口 `DailyQuoteService#fetchAndSaveByTradeDate` Javadoc 中补充返回值说明。
