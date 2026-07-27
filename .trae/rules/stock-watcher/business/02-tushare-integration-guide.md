# Tushare 接口对接完整指南

> **面向 AI**：对接新 Tushare 接口时，按本指南的 13 个步骤依次执行。每一步都有可直接复制的代码模板。
> **目标**：新增一个 Tushare 接口（如 `daily_basic`、`income` 等），使其可以：
> 1. 从 Tushare REST API 拉取数据
> 2. 持久化到 SQLite
> 3. 通过 REST API 供前端查询
> 4. 支持定时任务每日更新
> 5. 支持数据管控中心统一管理（数据校验 / 增量更新 / 全量重建 / 拉取日志）

---

## 步骤概览

```
① 定义 DTO           ← XxxDTO + XxxQueryDTO
    ↓
② 注册枚举           ← TushareApiEnum 追加项
    ↓
③ TushareClient 方法  ← public xxx() + private buildXxxParams()
    ↓
④ 配置限流           ← application.yml rate-limit
    ↓
⑤ 数据库层           ← schema.sql + XxxDO + XxxMapper + XML
    ↓
⑥ Service 层（基础）  ← XxxService 接口 + XxxServiceImpl 实现
    ↓
⑦ Service 层（校验）  ← 实现 DataCheckable 接口
    ↓
⑧ Controller 层      ← REST 查询接口
    ↓
⑨ InitStep 注册      ← 表元数据注册（分组/更新频率/是否日线）
    ↓
⑩ 数据管控中心接入   ← DataInitService（增量/全量）+ 拉取日志
    ↓
⑪ 接入定时任务       ← DailyUpdateTask（每日增量）
    ↓
⑫ 配置 Mapper 扫描   ← 检查 Mapper 目录已有 @MapperScan
    ↓
⑬ 测试验证           ← curl 验证 fetch + query + 数据校验
```

---

## Step 1：定义 DTO

### 1.1 响应 DTO（Tushare 返回字段映射）

**位置**：`src/main/java/com/arthur/stock/dto/tushare/XxxDTO.java`

**⚠️ 核心规则：每个 Tushare 返回字段必须加 `@JSONField(name = "实际字段名")`，否则 FastJSON2 解析后字段值为 null。**

```java
package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare xxx 接口返回数据
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=XXX">Tushare xxx 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XxxDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "trade_date")
    private String tradeDate;

    @JSONField(name = "close")
    private BigDecimal close;

    @JSONField(name = "open")
    private BigDecimal open;

    // 其他字段按 Tushare 文档 fields 列表依次添加
}
```

### 1.2 请求 DTO

**位置**：`src/main/java/com/arthur/stock/dto/tushare/XxxQueryDTO.java`

```java
package com.arthur.stock.dto.tushare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tushare xxx 接口请求参数
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=XXX">Tushare xxx 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XxxQueryDTO {

    /** 股票代码，如 000001.SZ（可选） */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd（可选） */
    private String tradeDate;

    /** 开始日期，格式 yyyyMMdd（可选） */
    private String startDate;

    /** 结束日期，格式 yyyyMMdd（可选） */
    private String endDate;

    /** 分页偏移量（可选，TushareClient.fetchAllPages 内部使用） */
    private Integer offset;

    /** 每页条数，最大 6000（可选） */
    private Integer limit;

    // 其他接口特有参数...
}
```

---

## Step 2：注册 TushareApiEnum

**文件**：`src/main/java/com/arthur/stock/constant/TushareApiEnum.java`

在枚举中追加一项，**fields 字符串必须与 Tushare 文档完全一致**（下划线命名，逗号分隔）：

```java
/** xxx 接口 */
XXX("xxx",
        "ts_code,trade_date,field1,field2,field3"),
```

---

## Step 3：添加 TushareClient 方法

**文件**：`src/main/java/com/arthur/stock/client/TushareClient.java`

### 3.1 public 接口方法

```java
/**
 * xxx 接口
 *
 * @param param 查询参数
 * @return xxx 数据列表
 */
public List<XxxDTO> xxx(XxxQueryDTO param) {
    JSONObject params = buildXxxParams(param);
    return query(TushareApiEnum.XXX, params, XxxDTO.class);
}
```

### 3.2 private buildXxxParams 方法

**规则**：Java 字段是驼峰（`tsCode`），放到 JSONObject 中时必须写为 Tushare 接口要求的下划线命名（`ts_code`）。非 null 字段才放入 params。

```java
private JSONObject buildXxxParams(XxxQueryDTO param) {
    JSONObject params = new JSONObject();
    if (param.getTsCode() != null) {
        params.put("ts_code", param.getTsCode());
    }
    if (param.getTradeDate() != null) {
        params.put("trade_date", param.getTradeDate());
    }
    if (param.getStartDate() != null) {
        params.put("start_date", param.getStartDate());
    }
    if (param.getEndDate() != null) {
        params.put("end_date", param.getEndDate());
    }
    if (param.getOffset() != null) {
        params.put("offset", String.valueOf(param.getOffset()));
    }
    if (param.getLimit() != null) {
        params.put("limit", String.valueOf(param.getLimit()));
    }
    return params;
}
```

### 3.3 通用 query 方法（已有，不需要改）

通用 `query(TushareApiEnum, JSONObject, Class<T>)` 方法已经存在于 `TushareClient`，做了以下事：
1. `rateLimiter.acquire(apiName)` —— 限流
2. 组装请求体：`{ api_name, token, params, fields }`
3. HTTP POST
4. 解析 `fields[] + items[][]` → Java DTO 列表

---

## Step 4：配置限流

**文件**：`src/main/resources/application.yml`

在 `tushare.rate-limit` 下追加接口名。**新接口必须配置限流，否则 Tushare 可能返回 429（请求过于频繁）。**

```yaml
tushare:
  base-url: http://api.tushare.pro
  timeout: 30000
  rate-limit:
    xxx:                    # 接口名，与 TushareApiEnum 的 apiName 一致
      permits-per-minute: 300   # 每分钟最大请求数
      # 或 permits-per-second: 5   # 每秒最大请求数
```

**各接口参考配置**：

| 接口 | 配置 | 说明 |
|-----|------|------|
| `daily` | `permits-per-minute: 300` | per-stock 拉取量大，保守配置 |
| `stock_basic` | `permits-per-minute: 300` | 全量一次性拉取 |
| `trade_cal` | `permits-per-second: 5` | 单请求少，秒级足够 |
| `adj_factor` | `permits-per-minute: 300` | 同 daily |
| `dividend` | `permits-per-minute: 300` | 同 daily |

---

## Step 5：数据库层

### 5.1 schema.sql 追加表结构

**文件**：`src/main/resources/schema.sql`

根据数据特点选择主键策略：

```sql
-- 【方式 A】股票+日期为自然主键（如 daily_quote, adj_factor）
CREATE TABLE IF NOT EXISTS xxx (
    ts_code    TEXT    NOT NULL,
    trade_date TEXT    NOT NULL,
    field1     REAL,
    field2     TEXT,
    -- 其他字段...
    PRIMARY KEY (ts_code, trade_date)
);

-- 【方式 B】自增主键 + 唯一约束（如 stock_basic）
CREATE TABLE IF NOT EXISTS xxx (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code    TEXT    NOT NULL UNIQUE,
    field1     TEXT,
    -- 其他字段...
);

-- 【方式 C】自增主键 + 复合唯一约束（如 dividend，(ts_code, end_date, ann_date) 唯一）
CREATE TABLE IF NOT EXISTS xxx (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_code    TEXT    NOT NULL,
    end_date   TEXT,
    ann_date   TEXT,
    field1     REAL,
    -- 其他字段...
    UNIQUE(ts_code, end_date, ann_date)
);
```

### 5.2 DO 类

**位置**：`src/main/java/com/arthur/stock/model/XxxDO.java`

```java
package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xxx")   // 与 schema.sql 表名一致
public class XxxDO {

    // 【自然主键】如果表以 (ts_code, trade_date) 为主键，不需要 @TableId 注解，直接写字段即可
    private String tsCode;
    private String tradeDate;

    // 【自增主键】如果用自增主键，取消下一行注释
    // @TableId(type = IdType.AUTO)
    // private Long id;

    private BigDecimal field1;
    private String field2;
    // 其他字段...
}
```

**⚠️ MyBatis-Plus 自动 `map-underscore-to-camel-case=true`，Java 字段 `tsCode` 自动映射为 DB 字段 `ts_code`。**

### 5.3 Mapper 接口

**位置**：`src/main/java/com/arthur/stock/mapper/XxxMapper.java`

**注意**：本项目使用 `src/main/resources/mapper/*.xml` 方式定义 SQL，**不要**使用 `@Insert("<script>")` 注解方式。

```java
package com.arthur.stock.mapper;

import com.arthur.stock.model.XxxDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface XxxMapper extends BaseMapper<XxxDO> {

    /** 批量 INSERT OR REPLACE（主键冲突时替换旧数据，用于全量覆盖） */
    void insertOrReplaceBatch(List<XxxDO> list);

    /** 批量 INSERT OR IGNORE（主键冲突时忽略，用于增量更新，已有数据保留） */
    void insertOrIgnoreBatch(List<XxxDO> list);
}
```

### 5.4 Mapper XML

**位置**：`src/main/resources/mapper/XxxMapper.xml`

**⚠️ 关键提醒：`#{item.tsCode}` 中写的是 Java 字段名（驼峰），因为这是 OGNL 表达式，不受 `map-underscore-to-camel-case` 影响。**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.arthur.stock.mapper.XxxMapper">

    <insert id="insertOrReplaceBatch" parameterType="com.arthur.stock.model.XxxDO">
        INSERT OR REPLACE INTO xxx (ts_code, trade_date, field1, field2)
        VALUES
        <foreach collection="list" item="item" separator=",">
            (#{item.tsCode}, #{item.tradeDate}, #{item.field1}, #{item.field2})
        </foreach>
    </insert>

    <insert id="insertOrIgnoreBatch" parameterType="com.arthur.stock.model.XxxDO">
        INSERT OR IGNORE INTO xxx (ts_code, trade_date, field1, field2)
        VALUES
        <foreach collection="list" item="item" separator=",">
            (#{item.tsCode}, #{item.tradeDate}, #{item.field1}, #{item.field2})
        </foreach>
    </insert>

</mapper>
```

**INSERT OR REPLACE vs INSERT OR IGNORE**：
- `INSERT OR REPLACE`：主键冲突时替换旧数据 → 用于全量覆盖
- `INSERT OR IGNORE`：主键冲突时忽略 → 用于增量更新，已有数据保留

---

## Step 6：Service 层（基础功能）

### 6.1 Service 接口

**位置**：`src/main/java/com/arthur/stock/service/XxxService.java`

```java
package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.XxxDTO;
import com.arthur.stock.model.XxxDO;

import java.util.List;

public interface XxxService {

    /** 从 Tushare 增量拉取指定股票数据并保存（从本地最新日期+1 开始） */
    List<XxxDTO> fetchAndSaveXxx(String tsCode);

    /** 从 Tushare 按日期拉取全市场数据并保存（用于每日更新任务） */
    List<XxxDTO> fetchAndSaveByTradeDate(String tradeDate);

    /** 从本地数据库查询指定股票的全部数据 */
    List<XxxDO> queryLocalByTsCode(String tsCode);
}
```

### 6.2 Service 实现类

**位置**：`src/main/java/com/arthur/stock/service/impl/XxxServiceImpl.java`

```java
package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.mapper.XxxMapper;
import com.arthur.stock.model.XxxDO;
import com.arthur.stock.dto.tushare.XxxQueryDTO;
import com.arthur.stock.dto.tushare.XxxDTO;
import com.arthur.stock.service.XxxService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class XxxServiceImpl implements XxxService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAGE_SIZE = 6000;     // Tushare 单页最大
    private static final int BATCH_SIZE = 500;      // 批量保存大小

    private final TushareClient tushareClient;
    private final XxxMapper xxxMapper;

    @Override
    public List<XxxDTO> fetchAndSaveXxx(String tsCode) {
        // 1. 查询本地最新日期
        String lastDate = getLastDate(tsCode);

        // 2. 计算增量起点
        String startDate;
        if (lastDate != null) {
            LocalDate ld = LocalDate.parse(lastDate, DATE_FMT);
            startDate = ld.plusDays(1).format(DATE_FMT);
        } else {
            startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        }
        String endDate = LocalDate.now().format(DATE_FMT);

        if (startDate.compareTo(endDate) > 0) {
            log.info("Stock {} xxx data is up to date", tsCode);
            return Collections.emptyList();
        }

        log.info("Fetching xxx for {} from {} to {}", tsCode, startDate, endDate);

        // 3. 从 Tushare 分页拉取
        XxxQueryDTO param = XxxQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<XxxDTO> dtos = fetchAllPages(param);

        if (dtos.isEmpty()) {
            log.info("No xxx data returned for {}", tsCode);
            return Collections.emptyList();
        }

        // 4. DTO → DO + 批量保存
        List<XxxDO> entities = dtos.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        saveBatch(entities);
        log.info("Saved {} xxx records for {}", entities.size(), tsCode);
        return dtos;
    }

    @Override
    public List<XxxDTO> fetchAndSaveByTradeDate(String tradeDate) {
        log.info("Fetching xxx for trade_date={}", tradeDate);
        XxxQueryDTO param = XxxQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        List<XxxDTO> dtos = fetchAllPages(param);
        if (dtos.isEmpty()) {
            log.info("No xxx data returned for trade_date={}", tradeDate);
            return Collections.emptyList();
        }
        List<XxxDO> entities = dtos.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        saveBatch(entities);
        log.info("Saved {} xxx records for trade_date={}", entities.size(), tradeDate);
        return dtos;
    }

    @Override
    public List<XxxDO> queryLocalByTsCode(String tsCode) {
        return xxxMapper.selectList(
                new LambdaQueryWrapper<XxxDO>()
                        .eq(XxxDO::getTsCode, tsCode)
                        .orderByAsc(XxxDO::getTradeDate));
    }

    // ==================== 辅助方法 ====================

    /** 分页拉取所有数据（Tushare 单页 6000 条） */
    private List<XxxDTO> fetchAllPages(XxxQueryDTO baseParam) {
        List<XxxDTO> allRows = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            XxxQueryDTO param = XxxQueryDTO.builder()
                    .tsCode(baseParam.getTsCode())
                    .tradeDate(baseParam.getTradeDate())
                    .startDate(baseParam.getStartDate())
                    .endDate(baseParam.getEndDate())
                    .offset(offset)
                    .limit(PAGE_SIZE)
                    .build();
            List<XxxDTO> page = tushareClient.xxx(param);
            if (page.isEmpty()) break;
            allRows.addAll(page);
            if (page.size() < PAGE_SIZE) break;
            offset += PAGE_SIZE;
        }
        return allRows;
    }

    /** 查询本地最新日期 */
    private String getLastDate(String tsCode) {
        XxxDO last = xxxMapper.selectOne(
                new LambdaQueryWrapper<XxxDO>()
                        .eq(XxxDO::getTsCode, tsCode)
                        .orderByDesc(XxxDO::getTradeDate)
                        .last("LIMIT 1"));
        return last != null ? last.getTradeDate() : null;
    }

    /** DTO → DO 转换 */
    private XxxDO toEntity(XxxDTO dto) {
        return XxxDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .field1(dto.getField1())
                .field2(dto.getField2())
                .build();
    }

    /** 批量保存（INSERT OR IGNORE 适合增量，INSERT OR REPLACE 适合全量覆盖） */
    private void saveBatch(List<XxxDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(xxxMapper::insertOrIgnoreBatch);
    }
}
```

---

## Step 7：Service 层（数据校验）

每张业务表的 Service 都必须实现 `DataCheckable` 接口，接入数据管控中心的统一校验体系。

**接口定义**：`src/main/java/com/arthur/stock/service/DataCheckable.java`

```java
public interface DataCheckable {
    /** 执行校验，返回所有检测项结果（含通过的和不通过的） */
    DataCheckResult checkData();
    /** 表代码，对应 InitStep.code */
    String getTableCode();
}
```

### 7.1 修改 Service 接口

```java
public interface XxxService extends DataCheckable {
    // ... 原有方法不变 ...
}
```

### 7.2 在 Service 实现类中添加校验方法

```java
@Override
public String getTableCode() {
    return "xxx";   // 与 InitStep.code 一致
}

@Override
public DataCheckResult checkData() {
    List<DataCheckItem> items = new ArrayList<>();

    // 1. 查询总记录数
    long total = xxxMapper.selectCount(null);
    if (total == 0) {
        items.add(DataCheckItem.builder()
                .name("empty")
                .displayName("空表检测")
                .passed(false)
                .level(CheckLevel.ERROR)
                .message("表中无数据")
                .build());
        return DataCheckResult.builder()
                .tableCode("xxx")
                .tableName("xxx数据")
                .totalRows(0)
                .latestDate(null)
                .items(items)
                .build();
    }

    // 2. 查询最新日期
    XxxDO latest = xxxMapper.selectOne(
            new LambdaQueryWrapper<XxxDO>()
                    .orderByDesc(XxxDO::getTradeDate)
                    .last("LIMIT 1"));
    String latestDate = latest != null ? latest.getTradeDate() : null;

    // 3. 自定义检测项：价格逻辑检测（行情类表才需要，按需添加）
    // 示例：抽样检查最新 100 条数据的价格逻辑
    // List<XxxDO> recent = xxxMapper.selectList(
    //         new LambdaQueryWrapper<XxxDO>()
    //                 .orderByDesc(XxxDO::getTradeDate)
    //                 .last("LIMIT 100"));
    // boolean priceValid = recent.stream().allMatch(d ->
    //         d.getHigh().compareTo(d.getLow()) >= 0
    //                 && d.getHigh().compareTo(d.getOpen()) >= 0
    //                 && d.getHigh().compareTo(d.getClose()) >= 0
    //                 && d.getLow().compareTo(d.getOpen()) <= 0
    //                 && d.getLow().compareTo(d.getClose()) <= 0);
    // items.add(DataCheckItem.builder()
    //         .name("price_logic")
    //         .displayName("价格逻辑检测")
    //         .passed(priceValid)
    //         .level(CheckLevel.ERROR)
    //         .message(priceValid ? "价格高低关系正常" : "存在价格逻辑异常数据")
    //         .build());

    // 4. 自定义检测项：数据完整性（财务类表可检查关键字段非空率）
    // ...

    // 5. 所有自定义检测通过后，添加一个通用的"自定义检测通过"项（可选）
    items.add(DataCheckItem.builder()
            .name("custom_check")
            .displayName("自定义检测")
            .passed(true)
            .level(CheckLevel.WARN)
            .message("所有自定义检测项通过")
            .build());

    return DataCheckResult.builder()
            .tableCode("xxx")
            .tableName("xxx数据")
            .totalRows(total)
            .latestDate(latestDate)
            .items(items)
            .build();
}
```

### 7.3 注意事项

- **自动发现**：Spring 会自动注入所有 `DataCheckable` 实现，无需手动注册到 `DataGovernanceService`
- **通用检测项**：空表检测、行数变动检测、数据延迟检测等由 `DataGovernanceServiceImpl` 统一处理，Service 中**不需要重复实现**
- **自定义检测项**：Service 的 `checkData()` 只返回业务相关的自定义检测项
- **参考实现**：`DailyQuoteServiceImpl.checkData()` —— 日线行情的完整检测示例

---

## Step 8：Controller 层

**位置**：`src/main/java/com/arthur/stock/controller/XxxController.java`

```java
package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.XxxDTO;
import com.arthur.stock.model.XxxDO;
import com.arthur.stock.service.XxxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/xxx")
@RequiredArgsConstructor
public class XxxController {

    private final XxxService xxxService;

    /** 查询本地数据 */
    @GetMapping("/{tsCode}")
    public ApiResponse<List<XxxDO>> queryByTsCode(@PathVariable String tsCode) {
        return ApiResponse.success(xxxService.queryLocalByTsCode(tsCode));
    }

    /** 手动触发从 Tushare 拉取并保存指定股票 */
    @PostMapping("/fetch/{tsCode}")
    public ApiResponse<List<XxxDTO>> fetch(@PathVariable String tsCode) {
        return ApiResponse.success(xxxService.fetchAndSaveXxx(tsCode));
    }

    /** 按交易日期拉取全市场数据 */
    @PostMapping("/fetch/date/{tradeDate}")
    public ApiResponse<List<XxxDTO>> fetchByTradeDate(@PathVariable String tradeDate) {
        return ApiResponse.success(xxxService.fetchAndSaveByTradeDate(tradeDate));
    }
}
```

---

## Step 9：InitStep 表元数据注册

在 `InitStep` 枚举中注册新表的元数据，这是接入数据管控中心的第一步。

**文件**：`src/main/java/com/arthur/stock/constant/InitStep.java`

```java
/**
 * xxx 数据
 */
XXX("xxx",                      // code：表代码（唯一标识，全小写下划线）
    "xxx数据",                   // label：中文名（展示用）
    "xxx",                       // tableName：数据库表名
    TableGroup.MARKET,           // group：所属分组（BASIC/MARKET/FINANCE/EVENT/INDEX）
    "每个交易日 16:00",          // updateFrequency：更新频率描述
    "16:00",                     // expectedUpdateTime：期望更新时间点
    true,                        // isDaily：是否为日线表（影响延迟检测）
    "xxx"),                      // tushareApi：对应 Tushare 接口名
```

**各字段说明**：

| 字段 | 说明 | 示例 |
|------|------|------|
| `code` | 表代码，全小写下划线，唯一标识 | `daily_quote` |
| `label` | 表中文名，用于前端展示 | "日线行情" |
| `tableName` | 数据库表名 | `daily_quote` |
| `group` | 所属分组，`TableGroup` 枚举 | `TableGroup.MARKET` |
| `updateFrequency` | 更新频率描述（展示用） | "每个交易日 16:00" |
| `expectedUpdateTime` | 期望更新时间点（展示用） | "16:00" |
| `isDaily` | 是否为日线表——**日线表会参与数据延迟检测** | `true` / `false` |
| `tushareApi` | 对应 Tushare 接口名 | `daily` |

**五大分组选择参考**：

| 分组 | 适用场景 |
|------|---------|
| `BASIC` | 基础参考数据（股票列表、交易日历等） |
| `MARKET` | 行情与交易数据（日线、复权、涨跌停、资金流等） |
| `FINANCE` | 财务报表与指标（利润表、资产负债表、财务指标等） |
| `EVENT` | 事件驱动类数据（分红、停复牌、龙虎榜、大宗交易等） |
| `INDEX` | 指数、板块、互联互通（指数权重、行业分类、港股通、融资融券等） |

---

## Step 10：数据管控中心接入

接入 `DataInitService`（统一更新入口）和 `DataPullLog`（拉取日志），使新表支持增量更新、全量重建、操作日志。

### 10.1 DataInitService 接入

**文件**：`src/main/java/com/arthur/stock/service/impl/DataInitServiceImpl.java`

**修改 1**：注入 XxxService（如果还没有）：

```java
@RequiredArgsConstructor
public class DataInitServiceImpl implements DataInitService, DataVerifyService {
    // ... 其他注入 ...
    private final XxxService xxxService;
```

**修改 2**：在增量更新 switch 中追加 case：

```java
case XXX -> {
    updateStep("增量更新 xxx 数据");
    xxxService.fetchAndSaveXxxAll();   // 或 per-stock 模式，根据实际接口特点选择
}
```

**修改 3**：在全量重建 switch 中追加 case：

```java
case XXX -> {
    updateStep("全量重建 xxx 数据");
    xxxMapper.delete(null);  // 清空表
    xxxService.fetchAndSaveXxxAll();
}
```

**⚠️ 注意**：
- per-stock 模式（如 daily、adj_factor）需要遍历股票列表逐只拉取
- 一次性全量模式（如 stock_basic、trade_cal）直接调用全量拉取方法
- 全量重建前**务空清空表**，避免旧数据残留

### 10.2 拉取日志记录

在定时任务或手动更新的入口处记录 `DataPullLog`，用于审计和问题排查。

**参考模式**（以定时任务为例）：

```java
// 定时任务中
String taskId = UUID.randomUUID().toString();
DataPullLogDO log = DataPullLogDO.builder()
        .taskId(taskId)
        .tableCode("xxx")
        .tableName("xxx数据")
        .operationType(OperationTypeEnum.SCHEDULED.name())
        .status(PullStatusEnum.RUNNING.name())
        .startTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .operator("SYSTEM")
        .build();
dataPullLogMapper.insert(log);

try {
    // 执行拉取
    List<XxxDTO> result = xxxService.fetchAndSaveByTradeDate(tradeDate);
    
    // 更新日志（成功）
    log.setStatus(PullStatusEnum.SUCCESS.name());
    log.setEndTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    log.setTotalCount((long) result.size());
    log.setSuccessCount((long) result.size());
    log.setFailCount(0L);
    dataPullLogMapper.updateById(log);
} catch (Exception e) {
    // 更新日志（失败）
    log.setStatus(PullStatusEnum.FAILED.name());
    log.setEndTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    log.setErrorMessage(e.getMessage());
    dataPullLogMapper.updateById(log);
    throw e;
}
```

**位置**：
- DO：`src/main/java/com/arthur/stock/model/DataPullLogDO.java`
- Mapper：`src/main/java/com/arthur/stock/mapper/DataPullLogMapper.java`

### 10.3 建表 SQL（如果需要）

如果新表需要通过数据管控中心动态建表（而不是 schema.sql），在 `DataInitServiceImpl` 的 `CREATE_TABLE_SQL` 中追加：

```java
"xxx", "CREATE TABLE IF NOT EXISTS xxx ("
        + "ts_code TEXT NOT NULL, trade_date TEXT NOT NULL, field1 REAL, field2 TEXT,"
        + "PRIMARY KEY (ts_code, trade_date))",
```

---

## Step 11：接入定时任务

**文件**：`src/main/java/com/arthur/stock/task/DailyUpdateTask.java`

在 `updateDaily()` 方法中追加：

```java
log.info("[Step N] 拉取当日 xxx 数据...");
try {
    // 当日交易日期：如果是交易日，用当日；如果是非交易日，需要先用 trade_cal 判断
    String tradeDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    xxxService.fetchAndSaveByTradeDate(tradeDate);
} catch (Exception e) {
    log.error("Failed to update xxx", e);
}
```

**注意**：定时任务的更新操作建议写入 `DataPullLog`（操作类型 `SCHEDULED`，操作人 `SYSTEM`）。

如果需要数据校验补漏（DailyUpdateTask 之外的专项校验任务），参考 `DataVerifyTask` 的实现模式。

---

## Step 12：验证 Mapper 扫描（自动完成）

**文件**：`src/main/java/com/arthur/stock/config/MyBatisPlusConfig.java`

项目已有 `@MapperScan("com.arthur.stock.mapper")`，所以**新 Mapper 接口放在 `mapper/` 目录下即自动被扫描**，不需要额外配置。

---

## Step 13：测试验证

### 13.1 启动验证
1. 启动应用，观察日志
2. 检查 schema.sql 建表是否成功
3. 检查 DTO 字段解析是否正确（看日志有没有解析异常）
4. 检查 `DataCheckable` 自动注册是否成功（看启动日志有没有 bean 注入异常）

### 13.2 基础功能测试

```bash
# 单只股票拉取（触发 Tushare 请求 + 保存）
curl -X POST http://localhost:8080/api/xxx/fetch/000001.SZ

# 按日期拉取全市场
curl -X POST http://localhost:8080/api/xxx/fetch/date/20240115

# 查询本地数据
curl http://localhost:8080/api/xxx/000001.SZ
```

### 13.3 数据管控中心测试

```bash
# 数据管控概览
curl http://localhost:8080/api/data-governance/overview

# 查询全部表状态（看新表是否在列表中）
curl http://localhost:8080/api/data-governance/tables

# 查询单表详情
curl http://localhost:8080/api/data-governance/tables/xxx

# 同步检测单表（验证 DataCheckable 是否工作）
curl -X POST http://localhost:8080/api/data-governance/check/xxx

# 增量更新单表
curl -X POST http://localhost:8080/api/data-governance/tables/xxx/incremental-update

# 全量重建单表
curl -X POST http://localhost:8080/api/data-governance/tables/xxx/full-rebuild

# 查询拉取日志
curl "http://localhost:8080/api/data-governance/logs?tableCode=xxx"
```

### 13.4 常见问题排查

| 问题 | 可能原因 | 解决方法 |
|------|---------|---------|
| 返回空列表 | `TushareApiEnum` 的 fields 字段名错误，或 DTO 字段缺少 `@JSONField` | 检查 fields 字符串与 Tushare 文档一致，每个 DTO 字段加 `@JSONField(name = "xxx")` |
| 字段值全为 null | 同上，`@JSONField` 的 name 值与 Tushare 返回字段不一致 | 逐一核对 `@JSONField(name = "...")` |
| 限流超时 | permits-per-minute 配置过高或请求太频繁 | 调小限流配置；或在 Service 中添加重试逻辑 |
| 批量保存失败 | schema.sql 字段名与 DO 字段不匹配 | 检查表字段名；注意 Java 侧写驼峰，DB 侧写下划线 |
| SQLite 主键冲突 | 用了 INSERT OR REPLACE 但实际应 INSERT OR IGNORE | 增量更新用 `insertOrIgnoreBatch`，全量覆盖用 `insertOrReplaceBatch` |
| 数据管控中心看不到新表 | `InitStep` 枚举未注册，或 `DataCheckable` 实现未被 Spring 扫描 | 检查 InitStep 是否有对应枚举值；检查 Service 是否加了 `@Service` 注解 |
| 检测一直显示 UPDATING | 任务锁未释放，或 TaskProgressCache 有残留 | 等待 2 小时锁自动超时；或重启应用清空内存缓存 |

---

## 对接 Checklist（每新增一个接口按此勾选）

- [ ] `dto/tushare/XxxDTO.java` —— 每个字段加 `@JSONField(name = "tushare字段名")`
- [ ] `dto/tushare/XxxQueryDTO.java` —— 请求参数对象
- [ ] `constant/TushareApiEnum.java` —— 新增枚举项，`apiName` 和 `fields` 字符串与 Tushare 文档一致
- [ ] `client/TushareClient.java` —— `public List<XxxDTO> xxx(XxxQueryDTO param)` + `private JSONObject buildXxxParams(XxxQueryDTO param)`
- [ ] `application.yml` —— `tushare.rate-limit` 下新增接口限流配置
- [ ] `resources/schema.sql` —— 表结构（根据数据特点选择自然主键/自增主键）
- [ ] `model/XxxDO.java` —— `@TableName("表名")` + 字段与 DB 列对应
- [ ] `mapper/XxxMapper.java` —— `extends BaseMapper<XxxDO>` + `insertOrReplaceBatch` / `insertOrIgnoreBatch`
- [ ] `resources/mapper/XxxMapper.xml` —— SQL 中 `#{item.tsCode}` 写 Java 驼峰字段名（OGNL）
- [ ] `service/XxxService.java` —— 接口定义，**继承 `DataCheckable`**
- [ ] `service/impl/XxxServiceImpl.java` —— `@Service @RequiredArgsConstructor @Slf4j`，实现 `checkData()` + `getTableCode()`
- [ ] `controller/XxxController.java` —— REST 接口
- [ ] `constant/InitStep.java` —— 新增枚举值（8 个字段完整填写）
- [ ] `service/impl/DataInitServiceImpl.java` —— 增量更新 + 全量重建 switch case
- [ ] `task/DailyUpdateTask.java` —— 每日更新任务追加（如适用）
- [ ] **数据治理测试**：数据管控中心列表可见、单表检测通过、增量更新可用
- [ ] 手动测试：`curl` 验证 fetch + query

---

## 参考实现对照

| 现有接口 | 特点 | 可作为哪种模式的参考 |
|---------|------|---------------------|
| `daily`（日线行情） | per-stock 增量拉取 + 按日期全市场拉取 + 分页 + 批量保存 | **新接口最佳参考** |
| `stock_basic`（股票基础信息） | 一次性全量拉取，无需分页，无需 per-stock | 参考：一次性全量接口 |
| `trade_cal`（交易日历） | 按日期范围一次性拉取 | 参考：无股票维度的接口 |
| `adj_factor`（复权因子） | per-stock 增量拉取 + 按日期拉取 | 参考：按日期 + per-stock |
| `dividend`（分红送股） | per-stock 拉取，字段较复杂 | 参考：非标准日期字段接口 |
