# stock_basic 全量股票改造计划

## Summary

将 `stock_basic` 表从仅拉取上市股票（list_status='L'）改为拉取全量股票（上市 L + 退市 D + 暂停上市 P），并对所有依赖"全表皆为上市状态"隐式假设的调用点补上显式 `list_status` 过滤。

## Current State Analysis

### 问题根因

`StockBasicServiceImpl.fetchAndSaveStockBasic()` 硬编码 `listStatus(ListStatusEnum.LISTED.getCode())`，只拉取上市股票。退市股票从表中被删除（`saveStocks` 的 delete-by-ts_code 机制），导致：
- 退市股票的 `daily_quote` 等历史数据变成孤儿记录（JOIN stock_basic 失败）
- 前端无法查询到已退市股票的基础信息

### 全量调用点核查结果

经全代码库扫描，`stock_basic` 的消费方分为三类：

**A. 已正确过滤 list_status（无需改动，9 处）：**

| 文件 | 行 | 过滤方式 |
|---|---|---|
| `StockBasicServiceImpl.java` | 178 | `ListStatusEnum.LISTED` (checkData) |
| `SearchServiceImpl.java` | 38, 58 | `"L"` (searchStocks / suggestStocks) |
| `WatchlistServiceImpl.java` | 223 | `"L"` (addToWatchlist 校验) |
| `ScreenerServiceImpl.java` | 600, 605 | `ListStatusEnum.LISTED` (resolveUniverse) |
| `FactorSnapshotServiceImpl.java` | 78 | `ListStatusEnum.LISTED` |
| `DailyQuoteServiceImpl.java` | 368 | `ListStatusEnum.LISTED` (checkData) |
| `DataVerifyTask.java` | 101 | `ListStatusEnum.LISTED.getCode()` |
| `BasicDataServiceImpl.java` | 59 | `queryLocal(…, "L")` (fetchAndSaveFinaIndicator) |
| `IncomeServiceImpl.java` | 65 | `queryLocal(…, "L")` (fetchAndSaveAllByRange) |
| `BalancesheetServiceImpl.java` | 55 | `queryLocal(…, "L")` |
| `CashflowServiceImpl.java` | 55 | `queryLocal(…, "L")` |
| `ForecastServiceImpl.java` | 58 | `queryLocal(…, "L")` |
| `ExpressServiceImpl.java` | 58 | `queryLocal(…, "L")` |

**B. 隐式依赖"全表=上市"假设（必须补过滤，1 处）：**

| 文件 | 行 | 问题 |
|---|---|---|
| `DataInitServiceImpl.java` | 509 | `resolveStockListForSingleStep()` 调用 `queryLocal(null, null, null, null)` 无过滤，被 `executePerStockStep` 用于逐股拉取 daily_quote/adj_factor/财务数据。全量后表含退市股，会遍历数千只退市股票产生海量 API 调用 |

**C. 无需过滤的查询（身份/名称查找、JOIN 自然过滤，6 处）：**

| 文件 | 行 | 原因 |
|---|---|---|
| `StockCodeCache.java` | 49-51 | symbol⇌ts_code 映射缓存，退市股票也需映射 |
| `SearchServiceImpl.java` | 112-113 | batchByTsCodes 按 ts_code 精确查找，入参来自 daily_quote |
| `WatchlistServiceImpl.java` | 67-68 | getWatchlist 按自选股 symbol 查找，退市股票应展示 |
| `SwIndustryServiceImpl.java` | 257-259 | buildStockNameMap 按 ts_code 查名称 |
| `BacktestServiceImpl.java` | 669-672 | buildKlineData 取 list_date，回测需要退市股票 |
| `StockDataHelper.java` | 35-37, 125 | JOIN daily_quote 最新日期自然过滤退市股（无最新行情则 quote 为 null） |

### Tushare API 行为

Tushare `stock_basic` 接口的 `list_status` 参数：省略时默认返回 L（仅上市）。要获取全量必须分别传 L / D / P 各调一次再合并。G（过会未交易）无交易历史，跳过。

### saveStocks 机制验证

`saveStocks` 按 ts_code 删除再插入（upsert 语义）。合并 L+D+P 后：
- 状态变更的股票（如 L→D）：旧记录按 ts_code 删除，新记录（含 D 状态）插入 ✓
- 状态不变的股票：删除再插入，等价于更新 ✓
- 完全消失的股票：旧记录残留（与当前行为一致，可接受）

## Proposed Changes

### Change 1: `fetchAndSaveStockBasic()` 改为拉取全量

**文件**: `stock-watcher/src/main/java/com/arthur/stock/service/impl/StockBasicServiceImpl.java`
**行**: 46-72

**改什么**: 将单次 `listStatus=L` 调用改为遍历 `[LISTED, DELISTED, SUSPENDED]` 三种状态分别调用 Tushare，合并结果后统一保存。每个状态调用独立 try-catch，单个状态失败不阻断其他状态。

**为什么**: Tushare 省略 list_status 时默认返回 L，无法一次获取全量。必须分状态多次调用。

**怎么改**:
```java
@Override
public List<StockBasicDTO> fetchAndSaveStockBasic() {
    log.info("Fetching stock_basic (all statuses) from Tushare");

    List<StockBasicDTO> allStocks = new ArrayList<>();
    for (ListStatusEnum status : List.of(
            ListStatusEnum.LISTED, ListStatusEnum.DELISTED, ListStatusEnum.SUSPENDED)) {
        try {
            StockBasicQueryDTO param = StockBasicQueryDTO.builder()
                    .listStatus(status.getCode())
                    .build();
            List<StockBasicDTO> stocks = tushareClient.stockBasic(param);
            log.info("stock_basic list_status={} returned {} records", status.getCode(), stocks.size());
            allStocks.addAll(stocks);
        } catch (Exception e) {
            log.warn("Failed to fetch stock_basic for list_status={}: {}", status.getCode(), e.getMessage());
        }
    }

    if (allStocks.isEmpty()) {
        log.info("No stock_basic data returned");
        return Collections.emptyList();
    }

    List<StockBasicDO> entities = allStocks.stream()
            .map(this::toEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    saveStocks(entities);
    stockCodeCache.refresh();
    log.info("Saved {} stock_basic records (all statuses)", entities.size());
    return allStocks;
}
```

同步更新方法 Javadoc（行 46-48）从"所有上市股票"改为"全量股票（含上市/退市/暂停上市）"。

需确认 import：`StockBasicServiceImpl.java` 已有 `import com.arthur.stock.constant.*`（通配符，覆盖 ListStatusEnum）和 `import java.util.ArrayList`（行 25），无需新增。

### Change 2: `resolveStockListForSingleStep()` 补 list_status 过滤

**文件**: `stock-watcher/src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`
**行**: 508-515

**改什么**: 将 `queryLocal(null, null, null, null)` 改为 `queryLocal(null, null, null, ListStatusEnum.LISTED.getCode())`。

**为什么**: stock_basic 全量化后表含退市股票。`resolveStockListForSingleStep` 被 `executePerStockStep` 用于逐股初始化 daily_quote/adj_factor/财务数据。遍历退市股会产生数千次无效 Tushare API 调用，极大消耗积分和耗时。用户已确认：初始化仅处理上市股票，退市股票已有历史数据因 stock_basic 包含它们而 JOIN 不再丢失。

**怎么改**:
```java
private List<StockBasicDTO> resolveStockListForSingleStep() {
    List<StockBasicDTO> local = stockBasicService.queryLocal(
            null, null, null, ListStatusEnum.LISTED.getCode());
    if (local.isEmpty()) {
        throw new BusinessException(ErrorCode.NOT_FOUND,
                "本地无在市股票基础信息，请先初始化 stock_basic 步骤");
    }
    return local;
}
```

需补充 import：`DataInitServiceImpl.java` 使用 `import com.arthur.stock.constant.InitStep`（非通配符），需新增 `import com.arthur.stock.constant.ListStatusEnum;`。

## Assumptions & Decisions

1. **跳过 G（过会未交易）状态**：G 状态股票尚未开始交易，无历史数据，不拉取。
2. **逐股初始化仅处理上市股**：用户已确认。退市股的历史数据（此前上市时已拉取）保留在 daily_quote 等表中，因 stock_basic 现包含退市股记录，JOIN 查询不再丢失。
3. **saveStocks 不改**：现有 delete-by-ts_code + insert 的 upsert 机制能正确处理状态变更（L→D 等场景）。
4. **不改动已有 "L" 字符串过滤点**：SearchServiceImpl / WatchlistServiceImpl / 财务服务等已有的 `"L"` 和 `ListStatusEnum.LISTED` 过滤功能正确，无需改动。字符串 `"L"` 与 `@EnumValue` 存储值一致，功能等价。
5. **StockCodeCache 不加过滤**：缓存用途是 symbol⇌ts_code 映射，退市股票也需要映射（如查询退市股票历史 K 线时）。
6. **DailyUpdateTask 不改**：每日定时任务调用 `fetchAndSaveStockBasic()`，改造后自动同步全量状态。

## Verification Steps

1. **编译检查**：`mvn compile -pl stock-watcher` 确认无编译错误。
2. **全量拉取验证**：调用 `POST /tushare/stock-basic/init`，检查日志输出三种状态的记录数，确认 `stock_basic` 表含 L/D/P 三种 list_status。
3. **逐股初始化验证**：触发某个 per-stock 步骤（如 DAILY），确认只处理上市股票（日志中股票数 ≈ 5000 而非 7000+）。
4. **退市股票可见性**：查询 `GET /tushare/stock-basic?listStatus=D`，确认返回退市股票记录。
5. **搜索不受影响**：`GET` 搜索接口确认搜索结果仍只返回上市股票。
6. **自选股校验**：尝试添加退市股票到自选股，确认被正确拒绝。
7. **缓存验证**：StockCodeCache 日志确认加载了全量映射（数量 > 仅上市时的数量）。
