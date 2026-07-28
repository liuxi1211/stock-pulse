# Tushare 接口对接指南与铁律

> **面向 AI**：本文是新增 Tushare 接口的**唯一操作指南**。整合自原 02（13 步操作）+ 06（5 条铁律）。
> 铁律以 **⚠️ 铁律 N** 形式嵌入对应 Step；每条都是不可违反的红线。
> 现状接口清单见 [03-tushare-interfaces.md](./03-tushare-interfaces.md)；数据治理模块专题见 [05-data-governance-center.md](./05-data-governance-center.md)。

---

## 0. 全局铁律速查（先读）

对接任何 Tushare 接口，落库逻辑必须同时满足以下 5 条红线：

| # | 铁律 | 项目参数 | 标杆 |
|---|---|---|---|
| 1 | 单次返回 ≤5000，**必须 offset+limit 循环分页** | `PAGE_SIZE=5000`, `MAX_PAGES=100` | `TushareClient.queryWithPaging` |
| 2 | `offset ≤ 100000`，超量**按时间/按标的降维拆分** | `OFFSET_LIMIT=100000` | `StockSuspendDServiceImpl` |
| 3 | 所有接口都支持 offset/limit；不绕过 RateLimiter | `limit=5000`, 阻塞式限流 | `TushareClient.query` + `application.yml` |
| 4 | 事务粒度最小，**禁止跨 API 调用的大事务** | `TransactionTemplate`，每页/每批一事务 | `DailyQuoteServiceImpl` |
| 5 | 批量写入 **500 行/批**，禁止循环内逐条 INSERT | `BATCH_SIZE=500`, `Lists.partition` | 所有 `*ServiceImpl` |

> 任一条不满足，禁止合入。

---

## 步骤概览

```
① DTO (XxxDTO + XxxQueryDTO)        @JSONField 必须加
    ↓
② TushareApiEnum 追加项              fields 串与官方文档逐字一致
    ↓
③ TushareClient 方法                 public xxx() + private buildXxxParams()
    ↓
④ application.yml 限流               新接口必配，否则可能 429
    ↓
⑤ 数据库层                           schema.sql + DO + Mapper + XML
    ↓
⑥ Service 层（基础）                 分页拉取 + 批量保存（⚠️ 铁律 1/2/4/5）
    ↓
⑦ Service 层（校验）                 实现 DataCheckable
    ↓
⑧ Controller 层                      REST 查询
    ↓
⑨ InitStep 注册                      表元数据（分组/频率/是否日线）
    ↓
⑩ DataInitService 接入               增量/全量 switch case + 拉取日志
    ↓
⑪ DailyUpdateTask 定时任务           每日增量（如适用）
    ↓
⑫ Mapper 扫描                        自动（已配 @MapperScan）
    ↓
⑬ 测试验证                           curl fetch + query + 数据管控中心检测
```

---

## Step 1：定义 DTO

**位置**：`dto/tushare/XxxDTO.java`（响应）、`dto/tushare/XxxQueryDTO.java`（请求）。

> **⚠️ 命名转换铁律**：Tushare 字段是下划线（`ts_code`），Java DTO 是驼峰（`tsCode`）。**每个响应字段必须加 `@JSONField(name = "tushare字段名")`，否则 FastJSON2 解析后值为 null**。

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class XxxDTO {
    @JSONField(name = "ts_code")    private String tsCode;
    @JSONField(name = "trade_date") private String tradeDate;
    @JSONField(name = "close")      private BigDecimal close;
    // 其他字段按 Tushare 文档 fields 列表依次添加
}
```

`XxxQueryDTO` 字段：`tsCode` / `tradeDate` / `startDate` / `endDate`（可选，`yyyyMMdd`）+ `offset` / `limit`（分页用）。

---

## Step 2：注册 TushareApiEnum

**文件**：`constant/TushareApiEnum.java`

```java
XXX("xxx", "ts_code,trade_date,field1,field2,field3"),
```

> **fields 字符串必须与 Tushare 文档逐字一致**（下划线、逗号分隔）。错一个字符 → 返回全 null。

---

## Step 3：TushareClient 方法

**文件**：`client/TushareClient.java`

```java
public List<XxxDTO> xxx(XxxQueryDTO param) {
    return query(TushareApiEnum.XXX, buildXxxParams(param), XxxDTO.class);
}

private JSONObject buildXxxParams(XxxQueryDTO param) {
    JSONObject p = new JSONObject();
    if (param.getTsCode()    != null) p.put("ts_code",    param.getTsCode());
    if (param.getTradeDate() != null) p.put("trade_date", param.getTradeDate());
    if (param.getStartDate() != null) p.put("start_date", param.getStartDate());
    if (param.getEndDate()   != null) p.put("end_date",   param.getEndDate());
    if (param.getOffset()    != null) p.put("offset", String.valueOf(param.getOffset()));
    if (param.getLimit()     != null) p.put("limit",  String.valueOf(param.getLimit()));
    return p;
}
```

通用 `query(api, params, clazz)` 已实现：限流 → 组装请求体 → POST → 解析 `fields[]+items[][]` → DTO 列表。**不要绕过它直接拼 HTTP**。

**优先用通用分页回调**（⚠️ 铁律 1 标杆）：

```java
public <T> int queryWithPaging(TushareApiEnum api, JSONObject params, Class<T> clazz,
                               int batchSize, Consumer<List<T>> handler) {
    int offset = 0, page = 0;
    while (page < MAX_PAGES) {                 // MAX_PAGES = 100
        JSONObject pp = new JSONObject(); pp.putAll(params);
        pp.put("offset", offset); pp.put("limit", batchSize);  // batchSize = 5000
        List<T> rows = query(api, pp, clazz);
        if (rows.isEmpty()) break;
        handler.accept(rows);                  // 每页回调（落库），不累积全量
        if (rows.size() < batchSize) break;
        offset += batchSize; page++;
    }
}
```

---

## Step 4：配置限流（⚠️ 铁律 3）

**文件**：`application.yml` 的 `tushare.rate-limit`。**新接口必须配置，否则可能触发 Tushare 端 429**。

```yaml
tushare:
  rate-limit:
    xxx:
      permits-per-minute: 300     # 或 permits-per-second: 5
```

参考配置：`daily`/`stock_basic`/`trade_cal`/`index_daily` = 500；`adj_factor`/`index_weight`/财务 = 200；`dividend`/`stk_limit`/`moneyflow` = 180。

> RateLimiter 是**阻塞式**（超限 `Thread.sleep` 等待，调用方无感），按接口名独立计数。`limit=5000` 既满足铁律 1，又把 API 次数压到最低——**不要为分页把 limit 设更小**。

---

## Step 5：数据库层

### 5.1 schema.sql 主键策略

| 策略 | 适用 | 示例 |
|---|---|---|
| 自然主键 (ts_code, trade_date) | 行情类，按股票+日期唯一 | `daily_quote`、`adj_factor` |
| 自增主键 + UNIQUE(ts_code) | 单标的单条 | `stock_basic` |
| 自增主键 + 复合 UNIQUE | 多条同标的 | `dividend(ts_code,end_date,ann_date)` |

### 5.2 DO 类

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("xxx")
public class XxxDO {
    // 自然主键无需 @TableId
    private String tsCode;
    private String tradeDate;
    private BigDecimal close;
}
```

> MyBatis-Plus 自动 `map-underscore-to-camel-case=true`：`tsCode` ↔ `ts_code`。

### 5.3 Mapper 接口（用 XML，不用注解）

```java
public interface XxxMapper extends BaseMapper<XxxDO> {
    void insertOrReplaceBatch(List<XxxDO> list);   // 全量覆盖用
    void insertOrIgnoreBatch(List<XxxDO> list);    // 增量更新用
}
```

### 5.4 Mapper XML

> **⚠️ OGNL 陷阱**：`#{item.tsCode}` 写的是 **Java 驼峰字段名**（OGNL 表达式不受驼峰映射影响），DB 列名在 SQL 文本里写���划线。

```xml
<insert id="insertOrIgnoreBatch" parameterType="com.arthur.stock.model.XxxDO">
    INSERT OR IGNORE INTO xxx (ts_code, trade_date, close) VALUES
    <foreach collection="list" item="item" separator=",">
        (#{item.tsCode}, #{item.tradeDate}, #{item.close})
    </foreach>
</insert>
```

- `INSERT OR REPLACE`：主键冲突替换旧数据 → 全量覆盖
- `INSERT OR IGNORE`：主键冲突忽略 → 增量更新（保留已有）

---

## Step 6：Service 层（基础，⚠️ 铁律 1/2/4/5 全在此落地）

**接口**：`service/XxxService.java`

```java
public interface XxxService {
    List<XxxDTO> fetchAndSaveXxx(String tsCode);            // per-stock 增量
    List<XxxDTO> fetchAndSaveByTradeDate(String tradeDate); // 按日期全市场
    List<XxxDO>  queryLocalByTsCode(String tsCode);
}
```

**实现骨架**（`XxxServiceImpl`，已嵌入铁律）：

```java
@Slf4j @Service @RequiredArgsConstructor
public class XxxServiceImpl implements XxxService {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAGE_SIZE  = 5000;   // ⚠️ 铁律 1
    private static final int BATCH_SIZE = 500;    // ⚠️ 铁律 5
    private static final int OFFSET_LIMIT = 100000; // ⚠️ 铁律 2

    private final TushareClient tushareClient;
    private final XxxMapper xxxMapper;
    private final TransactionTemplate transactionTemplate;  // ⚠️ 铁律 4

    @Override
    public List<XxxDTO> fetchAndSaveByTradeDate(String tradeDate) {
        XxxQueryDTO base = XxxQueryDTO.builder().tradeDate(tradeDate).build();
        // ⚠️ 铁律 1：循环分页；铁律 4：每页一独立事务
        int offset = 0, page = 0;
        while (page < 100) {
            XxxQueryDTO p = XxxQueryDTO.builder()
                    .tradeDate(tradeDate).offset(offset).limit(PAGE_SIZE).build();
            List<XxxDTO> rows = tushareClient.xxx(p);   // ← API 调用在事务外
            if (rows.isEmpty()) break;
            List<XxxDO> entities = rows.stream().map(this::toEntity)
                    .filter(Objects::nonNull).collect(Collectors.toList());

            // ⚠️ 铁律 4：每页一个独立短事务（不是 @Transactional 包全流程）
            transactionTemplate.execute(status -> {
                // ⚠️ 铁律 5：500 行/批，先删同主键再插（幂等）
                for (List<XxxDO> batch : Lists.partition(entities, BATCH_SIZE)) {
                    xxxMapper.insertOrIgnoreBatch(batch);
                }
                return null;
            });

            if (rows.size() < PAGE_SIZE) break;
            offset += PAGE_SIZE;
            // ⚠️ 铁律 2：超量降维（见下方说明）
            if (offset >= OFFSET_LIMIT) { /* 触发按时间/按标的拆分 */ break; }
            page++;
        }
        return Collections.emptyList();
    }
}
```

### ⚠️ 铁律 2 详解：超量降维

当某接口全量数据可能 >10w 行时（如 `suspend_d` 全表 ~70w），**必须先按时间维度拆分**（按月切，每月 ~2000 行），分页时若 `offset >= 100000` 再降级到 per-stock 拉取。标杆：`StockSuspendDServiceImpl.fetchAndSaveByMonth` → `fetchAndSavePerStock`。

### ⚠️ 铁律 4 详解：禁止跨 API 调用的大事务

```java
// ❌ @Transactional 包全流程——API 调用 + 限流等待全在大事务内，连接被占数分钟
@Transactional public int fetchAndSave(...) { fetchAllPages(...); saveBatch(...); }

// ✅ TransactionTemplate 每页/每批一事务
```

> ⚠️ `AdjFactorServiceImpl.fetchAndSaveByTradeDate` 存在此问题（外层 `@Transactional` + 内层 `TransactionTemplate` 因 REQUIRED 合并为大事务），**新接口不要照抄**。

### ⚠️ 铁律 5 详解：500 行/批

全项目统一 `BATCH_SIZE=500`，仅 `FactorSnapshotServiceImpl` 用 1000（因子快照列少行小，例外）。新增**默认 500**。

### per-stock 增量拉取（`fetchAndSaveXxx`）

从本地最新日期 +1 开始拉到今天：

```java
String last = getLastDate(tsCode);                  // 查本地最新 trade_date
String start = (last != null)
        ? LocalDate.parse(last, FMT).plusDays(1).format(FMT)
        : LocalDate.now().minusYears(30).format(FMT);
String end = LocalDate.now().format(FMT);
```

---

## Step 7：Service 层（数据校验 DataCheckable）

每个业务 Service 必须实现 `DataCheckable`，接入数据管控中心校验。

```java
public interface XxxService extends DataCheckable { /* 原方法不变 */ }

// 实现类
@Override public String getTableCode() { return "xxx"; }   // 与 InitStep.code 一致

@Override public DataCheckResult checkData() {
    long total = xxxMapper.selectCount(null);
    if (total == 0) return emptyResult();                  // 空表检测
    // 业务自定义检测（价格逻辑/字段完整性等），通用项（延迟/行数变动）由 DataGovernanceService 统一处理
    return DataCheckResult.builder()
            .tableCode("xxx").tableName("xxx数据").totalRows(total)
            .items(items).build();
}
```

- **自动发现**：Spring 自动注入所有 `DataCheckable`，无需手动注册。
- **通用检测**（空表/行数变动/延迟）由 `DataGovernanceServiceImpl` 统一处理，Service 只写业务专属项。
- 参考实现：`DailyQuoteServiceImpl.checkData()`。

---

## Step 8：Controller 层

```java
@RestController @RequestMapping("/api/xxx") @RequiredArgsConstructor
public class XxxController {
    private final XxxService xxxService;

    @GetMapping("/{tsCode}")
    public ApiResponse<List<XxxDO>> query(@PathVariable String tsCode) {
        return ApiResponse.success(xxxService.queryLocalByTsCode(tsCode));
    }
    @PostMapping("/fetch/{tsCode}")
    public ApiResponse<List<XxxDTO>> fetch(@PathVariable String tsCode) {
        return ApiResponse.success(xxxService.fetchAndSaveXxx(tsCode));
    }
    @PostMapping("/fetch/date/{tradeDate}")
    public ApiResponse<List<XxxDTO>> fetchByDate(@PathVariable String tradeDate) {
        return ApiResponse.success(xxxService.fetchAndSaveByTradeDate(tradeDate));
    }
}
```

---

## Step 9：InitStep 注册（表元数据）

**文件**：`constant/InitStep.java`

```java
XXX("xxx", "xxx数据", "xxx",
    TableGroup.MARKET,        // BASIC/MARKET/FINANCE/EVENT/INDEX
    "每个交易日 16:00",        // updateFrequency（展示用）
    "16:00",                  // expectedUpdateTime（展示用）
    true,                     // isDaily：日线表参与延迟检测
    "xxx");                   // tushareApi
```

| 分组 | 适用 |
|---|---|
| `BASIC` | 股票列表、交易日历等基础参考 |
| `MARKET` | 日线、复权、涨跌停、资金流等行情 |
| `FINANCE` | 利润表、资产负债表、财务指标等报表 |
| `EVENT` | 分红、停复牌、龙虎榜、大宗交易等事件 |
| `INDEX` | 指数权重、行业分类、港股通、融资融券等 |

---

## Step 10：DataInitService 接入 + 拉取日志

**文件**：`service/impl/DataInitServiceImpl.java`

1. 注入 `XxxService`；
2. 增量更新 switch 追加：`case XXX -> { updateStep("增量更新 xxx"); xxxService.fetchAndSaveXxxAll(); }`
3. 全量重建 switch 追加（**必须先清空表**）：
   ```java
   case XXX -> { updateStep("全量重建 xxx"); xxxMapper.delete(null); xxxService.fetchAndSaveXxxAll(); }
   ```

**拉取日志**（定时/手动入口处，参考模式）：

```java
DataPullLogDO log = DataPullLogDO.builder()
        .taskId(UUID.randomUUID().toString()).tableCode("xxx").tableName("xxx数据")
        .operationType(OperationTypeEnum.SCHEDULED.name())
        .status(PullStatusEnum.RUNNING.name())
        .startTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .operator("SYSTEM").build();
dataPullLogMapper.insert(log);
try {
    List<XxxDTO> r = xxxService.fetchAndSaveByTradeDate(tradeDate);
    log.setStatus(PullStatusEnum.SUCCESS.name());
    log.setTotalCount((long) r.size()); log.setSuccessCount((long) r.size()); log.setFailCount(0L);
} catch (Exception e) {
    log.setStatus(PullStatusEnum.FAILED.name());
    log.setErrorMessage(e.getMessage());
    throw e;
} finally {
    log.setEndTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    dataPullLogMapper.updateById(log);
}
```

---

## Step 11：DailyUpdateTask 定时任务

**文件**：`task/DailyUpdateTask.java` 的 `updateDaily()` 追加：

```java
log.info("[Step N] 拉取当日 xxx 数据...");
try {
    String tradeDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    xxxService.fetchAndSaveByTradeDate(tradeDate);
} catch (Exception e) { log.error("Failed to update xxx", e); }
```

> 建议写入 `DataPullLog`（操作类型 `SCHEDULED`，操作人 `SYSTEM`）。

---

## Step 12：Mapper 扫描

`config/MyBatisPlusConfig.java` 已配 `@MapperScan("com.arthur.stock.mapper")`，新 Mapper 放 `mapper/` 目录即自动扫描，**无需额外配置**。

---

## Step 13：测试验证

```bash
# 基础功能
curl -X POST http://localhost:8080/api/xxx/fetch/000001.SZ
curl -X POST http://localhost:8080/api/xxx/fetch/date/20240115
curl http://localhost:8080/api/xxx/000001.SZ

# 数据管控中心
curl http://localhost:8080/api/data-governance/overview
curl http://localhost:8080/api/data-governance/tables                 # 新表是否在列
curl -X POST http://localhost:8080/api/data-governance/check/xxx      # 校验是否工作
curl -X POST http://localhost:8080/api/data-governance/tables/xxx/incremental-update
curl -X POST http://localhost:8080/api/data-governance/tables/xxx/full-rebuild
```

**常见问题**：

| 问题 | 原因 | 解决 |
|---|---|---|
| 返回空列表 | `TushareApiEnum.fields` 错 或 DTO 缺 `@JSONField` | 逐字核对 |
| 字段全 null | `@JSONField(name=...)` 与 Tushare 返回不一致 | 逐一核对 |
| 限流超时 | permits 过高 / 请求过频 | 调小或加重试 |
| 主键冲突 | 混用 OR REPLACE / OR IGNORE | 增量用 IGNORE，全量用 REPLACE |
| 管控中心看不到新表 | InitStep 未注册 或 Service 未加 `@Service` | 检查注入 |
| 检测卡 UPDATING | 任务锁未释放 | 等 2h 超时或重启 |

---

## 对接 Checklist（每新增一个接口逐项勾选）

**代码文件**：
- [ ] `dto/tushare/XxxDTO.java` —— 每字段加 `@JSONField`
- [ ] `dto/tushare/XxxQueryDTO.java`
- [ ] `constant/TushareApiEnum.java` —— fields 串逐字一致
- [ ] `client/TushareClient.java` —— public + private params
- [ ] `application.yml` —— `rate-limit.xxx`
- [ ] `schema.sql` + `model/XxxDO.java` + `mapper/XxxMapper.java` + `mapper/XxxMapper.xml`
- [ ] `service/XxxService.java` —— **继承 `DataCheckable`**
- [ ] `service/impl/XxxServiceImpl.java` —— 实现 `checkData()` + `getTableCode()`
- [ ] `controller/XxxController.java`
- [ ] `constant/InitStep.java` —— 8 字段完整
- [ ] `service/impl/DataInitServiceImpl.java` —— 增量 + 全量 switch case
- [ ] `task/DailyUpdateTask.java` —— 每日更新（如适用）

**铁律自查**（⚠️ 任一不满足禁止合入）：
- [ ] **铁律 1**：拉取是否 offset/limit 循环分页？`limit=5000`？有 `MAX_PAGES=100` 安全阀？
- [ ] **铁律 2**：全量是否可能 >10w？若是，是否按时间/标的拆分？有 `offset>=100000` 降级？
- [ ] **铁律 3**：`application.yml` 配了 `permits-per-minute`？`limit=5000`（非更小）？没绕过 RateLimiter？
- [ ] **铁律 4**：落库用 `TransactionTemplate`？事务范围在单页/单批内？**没有** `@Transactional` 包全流程？
- [ ] **铁律 5**：`Lists.partition(entities, 500)`？**没有**循环内逐条 INSERT？

---

## 参考实现对照

| 现有接口 | 特点 | 作参考 |
|---|---|---|
| `daily`（日线） | per-stock + 按日期、分页、批量、DataCheckable 完整 | **新接口最佳参考** |
| `stock_basic` | 一次性全量，无需分页 | 一次性全量接口 |
| `income`（利润表） | 财务类、按报告期、per-stock | 财务类接口 |
| `trade_cal` | 按交易所/日期范围一次性 | 无股票维度接口 |
| `suspend_d`（停复牌） | 按月拆 + per-stock 降级（⚠️ 铁律 2 标杆） | 大数据量接口 |

---

## 相关分册

- 接口清单（已对接 + 官方全量） → [03-tushare-interfaces.md](./03-tushare-interfaces.md)
- 数据管控中心详解 → [05-data-governance-center.md](./05-data-governance-center.md)
