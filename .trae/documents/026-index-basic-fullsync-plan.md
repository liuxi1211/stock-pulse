# 对接指数基本信息表 + 全量指数行情/权重 + 修复板块行情缺数据

> **change-id**: 026-index-basic-fullsync
> **基线**: stock-watcher（Java 21 + Spring Boot 4.0.6）
> **状态**: 规划中

---

## 一、Summary 摘要

板块行情「涨跌幅/成交额/主力净流入」字段为空，根因是 **`index_daily` 表缺失申万行业指数（801xxx.SI）数据**。

排查发现：
- `index_daily` 表只有 12 个宽基指数，申万行业指数（31 个）一条都没有
- 数据初始化中心 `DataInitServiceImpl` 的 `INDEX_DAILY` 分支**硬编码**只拉 `CORE_BROAD_INDEX_CODES`（12 个宽基），从未拉过申万指数
- 定时任务 `IndexDailyFetchService.dailySync()` 虽然会动态拉申万指数（从 `sw_industry` 表读 code），但只拉**当日**，首次初始化未覆盖
- **不存在** `index_basic` 表（指数基本信息），指数代码来源全是写死常量

本计划：
1. **新建 `index_basic` 全链路**（DO/Mapper/Service/Controller/DTO + Tushare 接口对接），拉取**全部市场**的指数基本信息
2. **改造 `index_daily` / `index_weight` 同步逻辑**，指数代码来源从写死常量改为**从 `index_basic` 表动态读取全部指数**，全历史拉取
3. 接入数据初始化中心 + 定时任务双触发
4. 验证板块行情字段恢复

---

## 二、Current State Analysis 现状分析

### 2.1 数据现状（DB 实测 2026-07-29）

| 表 | 最新日期 | 数据量 | 问题 |
|---|---|---|---|
| `index_daily` | 20260728 | 67389 行 | ❌ **仅 12 个宽基指数，0 个申万行业指数（801xxx.SI）** |
| `index_weight` | — | — | ❌ 仅同步 8 个写死指数 |
| `stock_moneyflow` | 20260728 | 371 万 | ✅ 正常（主力净流入数据源 OK） |
| `daily_quote` | 20260728 | 1782 万 | ✅ 正常 |
| `index_basic` | — | — | ❌ **表不存在** |

### 2.2 根因链路

```
板块行情缺涨跌幅/成交额
  └─ IndustryRankingVO.pctChg/amount 来自 index_daily（行业指数日线）
       └─ index_daily 表无 801xxx.SI 数据
            └─ DataInitServiceImpl.INDEX_DAILY 只拉 CORE_BROAD_INDEX_CODES（12个宽基）
                 └─ 指数代码来源写死，未纳入申万行业指数
                      └─ 不存在 index_basic 表来统一管理全部指数代码
```

### 2.3 关键确认

- ✅ Tushare `index_daily` 接口传 `ts_code=801010.SI` **能正确返回数据**（用户已实测）
- ✅ Tushare `index_basic`（doc_id=94）支持 `market=SW` 拉申万指数，也支持不限 market 拉全部
- ✅ 现有架构有成熟的「六层对接模板」（DTO/DO/Mapper/Service/Controller + TushareClient/Enum）
- ✅ 数据初始化中心（DataInitServiceImpl）+ 定时任务双触发机制已就绪

---

## 三、Proposed Changes 改动方案

### 阶段一：新建 index_basic 全链路（核心）

#### 1. Tushare 接口注册

**文件**: `constant/TushareApiEnum.java`

新增枚举项（字段严格按 doc_id=94 文档）：
```java
/** 指数基本信息接口（doc_id=94） */
INDEX_BASIC("index_basic",
        "ts_code,name,fullname,market,publisher,index_type,category,base_date,base_point,list_date,weight_rule"),
```

> 注：`desc`（描述）和 `exp_date`（终止日期）字段较长且当前无业务用途，暂不拉取，减少 payload。

#### 2. DTO 层

**新建** `dto/tushare/IndexBasicDTO.java`（参照 `IndexWeightDTO`）
- 每字段加 `@JSONField(name="snake_case")`
- 字段：tsCode / name / fullname / market / publisher / indexType / category / baseDate / basePoint(Double) / listDate / weightRule

**新建** `dto/tushare/IndexBasicQueryDTO.java`（参照 `IndexWeightQueryDTO`）
- 字段：market / tsCode / publisher / category（查询维度，均可空）
- 用 `@Builder`

#### 3. TushareClient 方法

**修改** `client/TushareClient.java`

新增方法（参照现有 `indexWeight` / `stockBasic` 模式）：
```java
public List<IndexBasicDTO> indexBasic(IndexBasicQueryDTO param) {
    return query(TushareApiEnum.INDEX_BASIC, buildIndexBasicParams(param), IndexBasicDTO.class);
}

private JSONObject buildIndexBasicParams(IndexBasicQueryDTO param) {
    JSONObject p = new JSONObject();
    if (param.getMarket() != null) p.put("market", param.getMarket());
    if (param.getTsCode() != null) p.put("ts_code", param.getTsCode());
    // ... publisher / category
    return p;
}
```

#### 4. index_basic 建表

**修改** `resources/schema-mysql.sql`

参照 `stock_basic` 结构（自增 id + UNIQUE(ts_code)）：
```sql
CREATE TABLE IF NOT EXISTS index_basic (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts_code     VARCHAR(20)  NOT NULL COMMENT 'TS指数代码',
    name        VARCHAR(64)  COMMENT '指数简称',
    fullname    VARCHAR(128) COMMENT '指数全称',
    market      VARCHAR(16)  COMMENT '市场(SSE/SZSE/CSI/SW/MSCI/CICC/SWHK/OTH)',
    publisher   VARCHAR(64)  COMMENT '发布商',
    index_type  VARCHAR(64)  COMMENT '指数风格',
    category    VARCHAR(64)  COMMENT '指数类别',
    base_date   VARCHAR(8)   COMMENT '基期',
    base_point  DECIMAL(20,4) COMMENT '基点',
    list_date   VARCHAR(8)   COMMENT '发布日期',
    weight_rule VARCHAR(64)  COMMENT '加权方式',
    UNIQUE KEY uk_ts_code (ts_code),
    INDEX idx_market (market),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指数基本信息';
```

#### 5. DO / Mapper / Service / Controller（六层）

**新建** `model/IndexBasicDO.java`
- `@TableName("index_basic")`，参照 `StockBasicDO`
- market 字段用 String（不建枚举，因为 market 值多且部分无业务语义，避免过度设计）

**新建** `mapper/IndexBasicMapper.java` + `resources/mapper/IndexBasicMapper.xml`
- extends `BaseMapper<IndexBasicDO>`
- 自定义：`insertBatch` / `deleteAll`（全量替换用）/ `selectAllTsCodes`（供 index_daily/weight 遍历）/ `selectByMarket`
- 复合唯一键 ts_code，批量操作参照 `StockBasicMapper`

**新建** `service/IndexBasicService.java` + `service/impl/IndexBasicServiceImpl.java`
- 参照 `StockBasicServiceImpl`，实现 `DataCheckable`
- 核心方法：`fetchAndSaveAll()`（全市场全量拉取，按 market 维度循环或一次性）
- 落库策略：**清表重建**（用户明确「不需要考虑历史数据兼容，会清表重建」）→ `deleteAll` + 分批 `insertBatch`

**新建** `controller/IndexBasicController.java`
- `GET /api/index-basic`（分页/按 market 筛选查询）
- `POST /api/index-basic/sync`（手动触发全量同步，可选）

#### 6. 注册到初始化中心

**修改** `constant/InitStep.java`

新增枚举项（放在 INDEX 组）：
```java
INDEX_BASIC("index_basic", "指数基本信息", "index_basic", TableGroup.INDEX, "每日 16:25", "16:25", false, "index_basic"),
```

**修改** `service/impl/DataInitServiceImpl.java`

`doFullInit` / `doIncrementalUpdate` 的 switch 新增 `case INDEX_BASIC`：
- 全量：调 `indexBasicService.fetchAndSaveAll()`
- 增量：同全量（index_basic 是维度表，全量替换）

#### 7. 定时任务

**修改** `service/IndexDailyFetchService.java` 或新建独立 `task/IndexBasicTask.java`

- 推荐：新建 `task/IndexBasicTask.java`，`@Scheduled(cron = "0 25 16 * * MON-FRI")`（16:25，先于 index_daily 的 16:30）
- 逻辑：`indexBasicService.fetchAndSaveAll()`

#### 8. 限流配置

**修改** `resources/application.yml`

```yaml
tushare:
  rate-limit:
    index_basic:
      permits-per-minute: 500   # 参照 stock_basic
```

---

### 阶段二：改造 index_daily 全量拉取

#### 9. 改造指数代码来源

**修改** `service/impl/DataInitServiceImpl.java` 的 `case INDEX_DAILY`

当前问题（L449-450）：
```java
case INDEX_DAILY -> {
    List<String> codes = IndexConstants.CORE_BROAD_INDEX_CODES;  // ❌ 写死 12 个
```

改为从 `index_basic` 表动态读取**全部指数**：
```java
case INDEX_DAILY -> {
    List<String> codes = indexBasicMapper.selectAllTsCodes();  // ✅ 全部指数
    if (codes.isEmpty()) {
        log.warn("index_basic 表为空，请先初始化 INDEX_BASIC");
        return StepStats.empty();
    }
    // ... 遍历 codes 全历史拉取
```

#### 10. 全历史拉取起始时间

**修改** `DataInitServiceImpl`

每个指数的起始时间从 `index_basic.base_date`（基期）开始：
```java
Map<String, String> baseDateMap = indexBasicMapper.selectAllBaseDateMap(); // ts_code -> base_date
for (String code : codes) {
    String start = isFull ? baseDateMap.getOrDefault(code, DEFAULT_START) : /*增量取 lastDate*/;
    indexDailyFetchService.fetchAndSaveIndexDaily(code, start, today);
}
```

> `DEFAULT_START` 兜底用 `"19901219"`（A 股最早），无 base_date 的指数从此开始。

#### 11. 定时任务改造

**修改** `service/IndexDailyFetchService.java` 的 `dailySync()`

当前 L119-121 已经会拉申万指数（从 sw_industry 表），但改为从 index_basic 读取更统一：
```java
List<String> codes = indexBasicMapper.selectAllTsCodes();  // 替代 DEFAULT_INDEX_CODES + listSwL1IndexCodes
```

> 保留 `listSwL1IndexCodes()` 方法不删（sw_industry 仍需它），仅 dailySync 的数据源切换。

---

### 阶段三：改造 index_weight 全量拉取

#### 12. 改造指数代码来源

**修改** `service/impl/DataInitServiceImpl.java` 的 `case INDEX_WEIGHT`

当前（L240-242）：
```java
case INDEX_WEIGHT -> {
    List<String> codes = IndexConstants.INDEX_WEIGHT_CODES;  // ❌ 写死 8 个
```

改为从 `index_basic` 读取全部指数（或按 category 筛选有权重的指数）：
```java
case INDEX_WEIGHT -> {
    List<String> codes = indexBasicMapper.selectAllTsCodes();  // 全部
```

#### 13. 全历史权重拉取

**修改** `IndexWeightServiceImpl.fetchAndSaveRange`

当前逻辑已支持区间拉取（[L75-88](file:///d:/lcProject/stock-pulse/stock-watcher/src/main/java/com/arthur/stock/service/impl/IndexWeightServiceImpl.java#L75)），只需 DataInitServiceImpl 传入正确的 start（基期/最早权重日）。

> ⚠️ index_weight 数据量大（每月一期 × 数千指数 × 数百成分股），全历史首次拉取耗时极长。需在日志和进度反馈中体现。

#### 14. 定时任务改造

**修改** `task/IndexWeightTask.java`

当前遍历 `IndexConstants.INDEX_WEIGHT_CODES`（8 个），改为遍历 `index_basic` 全部指数。

---

### 阶段四：清理写死常量 + 验证

#### 15. 清理 IndexConstants（可选，保持兼容）

**修改** `constant/IndexConstants.java`

- `DEFAULT_INDEX_CODES`（4 个，仪表盘首屏）：**保留**（UI 展示用，非数据同步用）
- `CORE_BROAD_INDEX_CODES`（12 个）：**保留**（数据管控检测用 `selectMissingCoreIndices` 依赖它）
- `INDEX_WEIGHT_CODES`（8 个）：**可删除**（已被 index_basic 动态取代）
- `INDEX_NAME_MAP`（12 个）：**保留**（UI 展示兜底，index_basic.name 是权威来源）

> 原则：数据同步类常量改用 index_basic 动态读取；UI 展示类常量保留。

#### 16. 验证板块行情恢复

无需改 `SwIndustryServiceImpl`（板块行情读取逻辑本身正确），数据补齐后自动恢复：
- `pctChg` / `amount`：来自 index_daily 的申万行业指数（801xxx.SI），阶段二完成后有值
- `pctChg5d` / `pctChg20d`：同上（近 5/20 日指数收盘）
- `主力净流入`：来自 stock_moneyflow（已有数据），确认前端并行加载正常

---

## 四、Assumptions & Decisions 假设与决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| index_basic 拉取范围 | **全部市场**（不限 market） | 用户明确要求，不管当前能否用上 |
| index_daily 拉取策略 | **全历史**（从 base_date 基期） | 用户明确要求 |
| index_weight 拉取范围 | **全历史权重** | 用户明确要求 |
| 触发方式 | **初始化中心 + 定时任务** | 用户明确要求，与现有架构一致 |
| index_basic 落库策略 | **清表重建**（deleteAll + insertBatch） | 用户明确「不需要历史兼容，会清表重建」 |
| 板块行情降级兜底 | **不做** | 用户明确，保持单一数据源口径 |
| market 字段类型 | **String（不建枚举）** | market 值多（8种）且部分无业务语义，避免过度设计 |
| Tushare 接口 | **index_daily 直传 ts_code=801010.SI** | 用户实测确认可用，无需特殊接口 |

---

## 五、Verification 验证步骤

### 5.1 编译验证
```bash
cd stock-watcher
.\mvnw.cmd compile -q -DskipTests
```

### 5.2 数据初始化（按顺序）

1. **清表重建 index_basic**：通过初始化中心触发 `INDEX_BASIC` 全量初始化
   - 验证：`SELECT COUNT(*) FROM index_basic` 应有数千条（全部市场指数）
   - 验证：`SELECT * FROM index_basic WHERE market='SW'` 应有申万指数（含 801010.SI）

2. **全量拉取 index_daily**：触发 `INDEX_DAILY` 全量初始化
   - 验证：`SELECT COUNT(DISTINCT ts_code) FROM index_daily` 应远大于 12
   - 验证：`SELECT * FROM index_daily WHERE ts_code='801010.SI' ORDER BY trade_date DESC LIMIT 5` 有数据

3. **全量拉取 index_weight**：触发 `INDEX_WEIGHT` 全量初始化
   - 验证：`SELECT COUNT(DISTINCT ts_code) FROM index_weight` 应大于 8

### 5.3 板块行情端到端验证

- 访问 `/page/sector`，确认热度图和排行表
- 调 `GET /api/industry/ranking`，验证每条行业记录的 `pctChg` / `amount` / `pctChg5d` / `pctChg20d` **非 null**
- 调 `GET /api/industry/moneyflow`，验证 `mainNetInflow` 非 null（此接口数据本就正常）
- 前端确认「涨跌幅 / 主力净流入 / 成交额」列有值显示

### 5.4 定时任务验证

- 确认 `IndexBasicTask`（16:25）、`IndexDailyFetchService.dailySync`（16:30）、`IndexWeightTask`（20:00）的 cron 配置
- 等待/手动触发一次，确认增量同步正常（不重复全量）

---

## 六、风险与注意事项

1. **首次全量拉取耗时长**：index_daily 全历史 × 数千指数，可能数小时。建议在非交易时段执行，初始化中心已有异步 + 进度反馈机制。
2. **index_weight 数据量极大**：全历史权重可能达数千万行。关注 DB 存储容量，必要时分批提交（已有 `Lists.partition(rows, 500)`）。
3. **Tushare 积分限制**：index_weight 需 2000 积分，index_daily 需一定积分。限流配置（permits-per-minute）已就绪，避免触发频控。
4. **清表重建风险**：index_basic 用 deleteAll + insertBatch，执行期间查询会返回空。建议在低峰期操作（初始化中心异步执行，不影响在线查询）。
5. **IndexConstants 兼容**：`INDEX_WEIGHT_CODES` 被删除前需确认无其他引用（grep 全局检查）。
