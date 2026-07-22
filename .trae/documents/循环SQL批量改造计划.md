# 循环内单条 SQL 操作排查与批量改造计划

## 一、总结

排查 stock-pulse 项目中所有"循环内逐条执行 SQL（INSERT/UPDATE/DELETE）"的场景，将循环次数 >3 的逐一改造为批量 SQL。共发现 **7 处问题点，分布在 6 个 Service 文件中**，涉及 6 个 Mapper（5 个需新建 XML 文件，1 个需追加方法）。同时补全知识库中缺失的"循环批量处理铁律"。

## 二、现状分析

### 2.1 项目已有的正确批量模式（参考基准）

项目中 13 个 Service 已统一采用标准批量模式：
```java
Lists.partition(list, BATCH_SIZE).forEach(batch -> {
    mapper.deleteBatchByKeys(batch);  // 批量删
    mapper.insertBatch(batch);         // 批量插
});
```
- BATCH_SIZE 统一为 500
- Mapper XML 用 `<foreach collection="list" item="item" separator=",">` 构造多值 INSERT
- 行值 IN 语法 `(col1, col2) IN ((...), (...))` 在 MySQL 和 SQLite 均可用（DailyQuoteMapper.xml 已验证）

### 2.2 发现的 7 处 N+1 SQL 问题

| # | 文件 | 方法 | 行号 | 问题类型 | 循环规模 |
|---|------|------|------|----------|----------|
| 1 | IndexWeightServiceImpl.java | `saveBatch` | 142-149 | 分批后逐条 `insert(row)` | 每批 500 条 × N 日期 |
| 2 | TradeCalServiceImpl.java | `computeAndSaveRebalanceFlags` | 278-284 | 分批后逐条 `updateRebalanceFlags(day)` | 每批 500 条 × 2 交易所 |
| 3 | StockNamechangeServiceImpl.java | `persistByTsCode` | 99-111 | 逐条 `delete` + 逐条 `insert` | 全量更名记录（数千条） |
| 4 | StockNamechangeServiceImpl.java | `persistByBizKey` | 122-134 | 逐条 `delete` + 逐条 `insert` | 增量更名记录 |
| 5 | StockStkLimitServiceImpl.java | `persistByBizKey` | 92-108 | 逐条 `delete` + 逐条 `insert` | 涨跌停价（上万条） |
| 6 | StockSuspendDServiceImpl.java | `persistByBizKey` | 93-109 | 逐条 `delete` + 逐条 `insert` | 停复牌记录（上万条） |
| 7a | SwIndustryServiceImpl.java | `fetchAndSaveClassify` | 73-81 | 逐条 `insert(entity)` | 行业分类（~100 条） |
| 7b | SwIndustryServiceImpl.java | `persistMembers` | 230-237 | 分批后逐条 `saveMember`（delete+insert） | 行业成分股（数千条） |

### 2.3 知识库现状

`.trae/rules/stock-watcher/java/03-database-design.md` 中：
- §3.2（💡 SHOULD）：提到"避免循环中单条插入"，但级别太低、无阈值
- §3.3（✅ MUST）：只覆盖"循环内查询"（N+1 query），不覆盖写入
- §4.5（✅ MUST）：提到"批量插入使用 saveBatch"，但无"循环 >3 次必须批量"的铁律

CLAUDE.md "高频易错点"表中无此条目。

## 三、改造方案

### 3.1 通用改造模式

所有问题统一采用项目已有的标准批量模式：
```java
// 批量删除 + 批量插入（先删后插，幂等 upsert）
Lists.partition(list, BATCH_SIZE).forEach(batch -> {
    mapper.deleteBatchByKeys(batch);
    mapper.insertBatch(batch);
});
```
- 批量 UPDATE（TradeCal 场景）用 CASE WHEN 构造单 SQL
- 每个 Mapper 新增 `insertBatch` + `deleteBatchByKeys` 方法声明
- 为没有 XML 的 Mapper 新建 XML 文件

### 3.2 逐文件改造详情

#### 问题 1：IndexWeightServiceImpl.java — 逐条 INSERT → 批量 INSERT

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/IndexWeightServiceImpl.java`

**改动**（`saveBatch` 方法，行 137-151）：
- 删除内层 `for (IndexWeightDO row : batch) { indexWeightMapper.insert(row); }` 循环
- 改为 `indexWeightMapper.insertBatch(batch)` 直接批量插入
- 外层 `Lists.partition` 分批逻辑保留

**依赖改动**：
- `IndexWeightMapper.java`：新增 `int insertBatch(@Param("list") List<IndexWeightDO> list);` 方法声明
- 新建 `stock-watcher/src/main/resources/mapper/IndexWeightMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO index_weight (ts_code, trade_date, con_code, weight) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.tsCode}, #{item.tradeDate}, #{item.conCode}, #{item.weight})
      </foreach>
  </insert>
  ```
- `IndexWeightServiceImpl.java`：新增 `import com.google.common.collect.Lists;` 和 `import org.apache.ibatis.annotations.Param;`（如需）

#### 问题 2：TradeCalServiceImpl.java — 逐条 UPDATE → 批量 UPDATE

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/TradeCalServiceImpl.java`

**改动**（`computeAndSaveRebalanceFlags` 方法，行 278-284）：
- 删除内层 `for (TradeCalDO day : batch) { updated += tradeCalMapper.updateRebalanceFlags(day); }` 循环
- 改为 `updated += tradeCalMapper.updateRebalanceFlagsBatch(batch);`
- 更新注释（删除"单条 update 跨方言通用"说明）

**依赖改动**：
- `TradeCalMapper.java`：新增 `int updateRebalanceFlagsBatch(@Param("list") List<TradeCalDO> list);` 方法声明
- `TradeCalMapper.xml`：新增 `updateRebalanceFlagsBatch`，用 CASE WHEN 构造批量 UPDATE（跨 MySQL/SQLite 通用）：
  ```xml
  <update id="updateRebalanceFlagsBatch">
      UPDATE trade_cal SET
          is_first_of_week = CASE
              <foreach collection="list" item="item">
                  WHEN exchange = #{item.exchange} AND cal_date = #{item.calDate} THEN #{item.isFirstOfWeek}
              </foreach>
              ELSE is_first_of_week END,
          is_last_of_week = CASE ... END,
          is_first_of_month = CASE ... END,
          is_last_of_month = CASE ... END,
          is_first_of_quarter = CASE ... END,
          is_last_of_quarter = CASE ... END
      WHERE
      <foreach collection="list" item="item" open="" separator=" OR " close="">
          (exchange = #{item.exchange} AND cal_date = #{item.calDate})
      </foreach>
  </update>
  ```

#### 问题 3+4：StockNamechangeServiceImpl.java — 逐条 DELETE+INSERT → 批量 DELETE+INSERT

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockNamechangeServiceImpl.java`

**改动 1**（`persistByTsCode` 方法，行 91-113）：
- 将逐条 `delete(by ts_code)` 改为：收集所有 ts_code 后用 `IN` 批量删除，或直接用 `deleteBatchByKeys` 批量删除
- 将逐条 `insert(entity)` 改为：先收集有效 entity 到 List，再 `Lists.partition` + `insertBatch`
- 新增 `BATCH_SIZE = 500` 常量

**改动 2**（`persistByBizKey` 方法，行 118-135）：
- 将逐条 `delete + insert` 改为：先收集有效 entity 到 List，再 `Lists.partition` + `deleteBatchByKeys` + `insertBatch`

**依赖改动**：
- `StockNamechangeMapper.java`：新增 `int insertBatch(@Param("list") List<StockNamechangeDO> list);` 和 `int deleteBatchByKeys(@Param("list") List<StockNamechangeDO> list);`
- 新建 `stock-watcher/src/main/resources/mapper/StockNamechangeMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO stock_namechange (ts_code, name, start_date, end_date, change_reason) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.tsCode}, #{item.name}, #{item.startDate}, #{item.endDate}, #{item.changeReason})
      </foreach>
  </insert>
  <delete id="deleteBatchByKeys">
      DELETE FROM stock_namechange WHERE (ts_code, start_date) IN
      <foreach collection="list" item="item" open="(" separator="," close=")">
          (#{item.tsCode}, #{item.startDate})
      </foreach>
  </delete>
  ```
- `StockNamechangeServiceImpl.java`：新增 `import com.google.common.collect.Lists;`

#### 问题 5：StockStkLimitServiceImpl.java — 逐条 DELETE+INSERT → 批量

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockStkLimitServiceImpl.java`

**改动**（`persistByBizKey` 方法，行 92-108）：
- 收集有效 entity 到 List
- `Lists.partition(entities, BATCH_SIZE).forEach(batch -> { mapper.deleteBatchByKeys(batch); mapper.insertBatch(batch); })`
- 新增 `BATCH_SIZE = 500` 常量

**依赖改动**：
- `StockStkLimitMapper.java`：新增 `insertBatch` + `deleteBatchByKeys` 方法声明
- 新建 `stock-watcher/src/main/resources/mapper/StockStkLimitMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO stock_stk_limit (ts_code, trade_date, pre_close, up_limit, down_limit) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.tsCode}, #{item.tradeDate}, #{item.preClose}, #{item.upLimit}, #{item.downLimit})
      </foreach>
  </insert>
  <delete id="deleteBatchByKeys">
      DELETE FROM stock_stk_limit WHERE (ts_code, trade_date) IN
      <foreach collection="list" item="item" open="(" separator="," close=")">
          (#{item.tsCode}, #{item.tradeDate})
      </foreach>
  </delete>
  ```

#### 问题 6：StockSuspendDServiceImpl.java — 逐条 DELETE+INSERT → 批量

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/StockSuspendDServiceImpl.java`

**改动**（`persistByBizKey` 方法，行 93-109）：
- 同问题 5 的改造模式

**依赖改动**：
- `StockSuspendDMapper.java`：新增 `insertBatch` + `deleteBatchByKeys` 方法声明
- 新建 `stock-watcher/src/main/resources/mapper/StockSuspendDMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO stock_suspend_d (ts_code, trade_date, susp_reason, resump_date) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.tsCode}, #{item.tradeDate}, #{item.suspReason}, #{item.resumpDate})
      </foreach>
  </insert>
  <delete id="deleteBatchByKeys">
      DELETE FROM stock_suspend_d WHERE (ts_code, trade_date) IN
      <foreach collection="list" item="item" open="(" separator="," close=")">
          (#{item.tsCode}, #{item.tradeDate})
      </foreach>
  </delete>
  ```

#### 问题 7a：SwIndustryServiceImpl.java `fetchAndSaveClassify` — 逐条 INSERT → 批量

**文件**：`stock-watcher/src/main/java/com/arthur/stock/service/impl/SwIndustryServiceImpl.java`

**改动**（`fetchAndSaveClassify` 方法，行 73-81）：
- 收集有效 entity 到 List
- `Lists.partition(entities, BATCH_SIZE).forEach(batch -> swIndustryMapper.insertBatch(batch))`
- 新增 `BATCH_SIZE = 500` 常量（如不存在）

**依赖改动**：
- `SwIndustryMapper.java`：新增 `int insertBatch(@Param("list") List<SwIndustryDO> list);`
- 新建 `stock-watcher/src/main/resources/mapper/SwIndustryMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO sw_industry (index_code, index_name, level, parent_code, src) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.indexCode}, #{item.indexName}, #{item.level}, #{item.parentCode}, #{item.src})
      </foreach>
  </insert>
  ```

#### 问题 7b：SwIndustryServiceImpl.java `persistMembers` — 逐条 DELETE+INSERT → 批量

**文件**：同上

**改动**（`persistMembers` 方法，行 230-239）：
- 删除 `saveMember(row)` 逐条调用
- 改为：`Lists.partition(entities, BATCH_SIZE).forEach(batch -> { swIndustryMemberMapper.deleteBatchByKeys(batch); swIndustryMemberMapper.insertBatch(batch); })`
- 删除或保留 `saveMember` 私有方法（若无其他调用方则删除）

**依赖改动**：
- `SwIndustryMemberMapper.java`：新增 `insertBatch` + `deleteBatchByKeys` 方法声明
- 新建 `stock-watcher/src/main/resources/mapper/SwIndustryMemberMapper.xml`：
  ```xml
  <insert id="insertBatch">
      INSERT INTO sw_industry_member (ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date) VALUES
      <foreach collection="list" item="item" separator=",">
          (#{item.tsCode}, #{item.indexCode}, #{item.indexName}, #{item.inDate}, #{item.outDate}, #{item.isNew}, #{item.src}, #{item.updateDate})
      </foreach>
  </insert>
  <delete id="deleteBatchByKeys">
      DELETE FROM sw_industry_member WHERE (ts_code, index_code, update_date) IN
      <foreach collection="list" item="item" open="(" separator="," close=")">
          (#{item.tsCode}, #{item.indexCode}, #{item.updateDate})
      </foreach>
  </delete>
  ```

### 3.3 知识库补全

#### 3.3.1 升级 `03-database-design.md` §3.2

将 §3.2 从 💡 SHOULD 升级为 ✅ MUST，并新增"循环 >3 次铁律"：

```markdown
### 3.2 插入/更新/删除规范 ✅ MUST

- 批量操作使用批量插入/更新
- **禁止循环内单条 SQL 操作**：当循环体内包含 INSERT/UPDATE/DELETE 且循环次数 >3 时，
  必须改造为批量 SQL（`insertBatch` / `deleteBatchByKeys` / CASE WHEN 批量 UPDATE）
- 单条操作仅允许在循环次数 ≤3 或确认数据量极小（<10 条）的场景使用
- MyBatis-Plus 使用 `insertBatch` / 自定义批量 SQL（`<foreach>` 构造多值 INSERT）
- 批量大小统一使用 `BATCH_SIZE = 500`，用 `Lists.partition(list, BATCH_SIZE)` 分批

```java
// 禁止 ❌ 循环逐条 INSERT
for (Item item : items) {
    mapper.insert(item);
}

// 正确 ✅ 批量 INSERT
Lists.partition(entities, BATCH_SIZE).forEach(batch -> {
    mapper.deleteBatchByKeys(batch);
    mapper.insertBatch(batch);
});
```
```

#### 3.3.2 CLAUDE.md "高频易错点"表新增一行

在"二·补"节的高频易错点表中新增：

```markdown
| **循环内单条 SQL** | 循环体内包含 INSERT/UPDATE/DELETE 且循环次数 >3 时，必须改造为批量 SQL（`insertBatch`/`deleteBatchByKeys`/CASE WHEN 批量 UPDATE）。单条操作仅限 ≤3 次或 <10 条数据。 | [`03-database-design.md` §3.2](./.trae/rules/stock-watcher/java/03-database-design.md) |
```

## 四、改动文件清单

### Java 文件（6 个 Service + 6 个 Mapper = 12 个）

| 文件 | 改动类型 |
|------|----------|
| `service/impl/IndexWeightServiceImpl.java` | 修改 `saveBatch` 方法 |
| `service/impl/TradeCalServiceImpl.java` | 修改 `computeAndSaveRebalanceFlags` 方法 |
| `service/impl/StockNamechangeServiceImpl.java` | 修改 `persistByTsCode` + `persistByBizKey` 方法 |
| `service/impl/StockStkLimitServiceImpl.java` | 修改 `persistByBizKey` 方法 |
| `service/impl/StockSuspendDServiceImpl.java` | 修改 `persistByBizKey` 方法 |
| `service/impl/SwIndustryServiceImpl.java` | 修改 `fetchAndSaveClassify` + `persistMembers` 方法 |
| `mapper/IndexWeightMapper.java` | 新增 `insertBatch` 方法声明 |
| `mapper/TradeCalMapper.java` | 新增 `updateRebalanceFlagsBatch` 方法声明 |
| `mapper/StockNamechangeMapper.java` | 新增 `insertBatch` + `deleteBatchByKeys` 方法声明 |
| `mapper/StockStkLimitMapper.java` | 新增 `insertBatch` + `deleteBatchByKeys` 方法声明 |
| `mapper/StockSuspendDMapper.java` | 新增 `insertBatch` + `deleteBatchByKeys` 方法声明 |
| `mapper/SwIndustryMapper.java` | 新增 `insertBatch` 方法声明 |
| `mapper/SwIndustryMemberMapper.java` | 新增 `insertBatch` + `deleteBatchByKeys` 方法声明 |

### XML 文件（新建 5 个 + 修改 1 个 = 6 个）

| 文件 | 改动类型 |
|------|----------|
| `resources/mapper/IndexWeightMapper.xml` | **新建**：`insertBatch` |
| `resources/mapper/TradeCalMapper.xml` | **修改**：新增 `updateRebalanceFlagsBatch` |
| `resources/mapper/StockNamechangeMapper.xml` | **新建**：`insertBatch` + `deleteBatchByKeys` |
| `resources/mapper/StockStkLimitMapper.xml` | **新建**：`insertBatch` + `deleteBatchByKeys` |
| `resources/mapper/StockSuspendDMapper.xml` | **新建**：`insertBatch` + `deleteBatchByKeys` |
| `resources/mapper/SwIndustryMapper.xml` | **新建**：`insertBatch` |
| `resources/mapper/SwIndustryMemberMapper.xml` | **新建**：`insertBatch` + `deleteBatchByKeys` |

注：TradeCalMapper.xml 是修改已有文件（新增 `<update>` 节点），其余 6 个为新建。

### 知识库文件（2 个）

| 文件 | 改动类型 |
|------|----------|
| `.trae/rules/stock-watcher/java/03-database-design.md` | 升级 §3.2 为 MUST + 新增铁律 |
| `CLAUDE.md` | 高频易错点表新增一行 |

## 五、验证步骤

1. **编译验证**：`node stock-watcher/run.js compile-dev`（增量编译，确认无语法/类型错误）
2. **功能验证**（如条件允许）：
   - 触发指数成分股权重拉取，确认 `index_weight` 表数据正确写入
   - 触发交易日历初始化，确认 `trade_cal` 6 个调仓标记正确
   - 触发更名/涨跌停/停复牌数据拉取，确认数据正确落库
   - 触发申万行业分类/成分股拉取，确认数据正确落库
3. **日志验证**：检查日志中的 `Saved N records` 数量与改造前一致

## 六、假设与决策

1. **批量 UPDATE 用 CASE WHEN**：TradeCal 场景不能用先删后插（会丢失 `is_open`/`pretrade_date` 字段），用 CASE WHEN 构造单条 SQL 批量更新，跨 MySQL/SQLite 通用。
2. **行值 IN 语法**：`(col1, col2) IN ((...), (...))` 在 MySQL 和 SQLite 3.15.0+ 均支持，项目中 DailyQuoteMapper.xml 已有先例。
3. **BATCH_SIZE 统一 500**：与项目现有模式一致。
4. **保留 `updateRebalanceFlags` 单条方法**：不删除原有单条方法（可能有其他调用方），仅新增批量方法。
5. **`saveMember` 私有方法**：改造后若 `persistMembers` 不再调用，则删除该方法避免死代码。
