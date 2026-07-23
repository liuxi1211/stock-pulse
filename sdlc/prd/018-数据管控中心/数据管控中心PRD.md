# 数据管控中心 PRD

> **模块**：021 · 数据管控中心（路由 `/page/data-governance`，侧边栏「系统管理」分组）
> **状态**：规划中
> **面向用户**：系统管理员 / 量化研究员 / 数据运维人员
> **核心价值**：让 tushare 对接的 25+ 张数据表的健康状态、更新时效、数据质量**可视化、可监控、可修复**，告别"黑盒数据"。

---

## 0. 为什么需要数据管控中心

### 0.1 痛点

当前数据层存在以下问题：

| 痛点 | 具体表现 | 风险 |
|---|---|---|
| **数据状态不可见** | 不知道每张表有多少数据、最新数据到哪天 | 回测/选股用到过期数据而不自知 |
| **更新及时性无监控** | 不知道每日定时任务是否成功执行、哪一步失败了 | 某张表断更数天无人发现 |
| **数据质量无校验** | 不知道有没有缺失交易日、有没有重复数据、字段是否有异常值 | 垃圾进垃圾出，回测结果不可信 |
| **问题排查困难** | 数据出问题只能翻日志、查数据库，没有可视化界面 | 定位慢，修复慢 |
| **修复操作繁琐** | 全量重建只能调 API / 写 SQL，没有一键操作 | 运维门槛高，容易误操作 |

### 0.2 目标

打造一个**一站式数据管控面板**，实现：

- **看得见**：25+ 张数据表的状态、量级、新鲜度一屏总览
- **摸得着**：单表增量更新 / 单表全量重建 可视化操作
- **查得到**：数据拉取日志、失败原因、异常历史可追溯
- **信得过**：每张表 3-5 个核心检测指标，覆盖 99% 常见数据问题

### 0.3 非目标

| 非目标项 | 原因 |
|---|---|
| 数据可视化分析（K线图等） | 那是行情中心/个股诊断的事 |
| 业务数据的增删改查 | 数据管控只负责**数据管道的健康度**，不碰业务数据本身 |
| 多数据源管理 | 当前只有 tushare 一个数据源，不做抽象 |
| 实时数据推送监控 | 日线数据，不涉及分钟/实时级监控 |
| 复杂的质量评分体系 | 正常/异常 + 原因说明足够，不需要 0-100 分的得分系统 |

---

## 1. 典型用户场景

### 场景一：管理员早上巡检数据

**时间**：交易日 9:00 开盘前
**用户**：系统管理员

**使用路径**：
1. 登录后进入「数据管控中心」
2. **第一眼**看顶部总览卡片：
   - 已接入数据表：25/25
   - 今日已更新：23/25
   - 异常表数：1 张（红色角标）
3. **第二眼**扫数据表列表的状态灯：
   - 绿色 = 正常
   - 黄色 = 延迟
   - 红色 = 异常
4. 发现「业绩快报 express」是红色，点击进入详情
5. 查看异常原因：「tushare 接口返回 402，积分不足」
6. 确认是 tushare 积分问题，联系管理员充值，点击「增量更新」按钮重试

### 场景二：量化研究员回测前检查数据

**时间**：做回测前
**用户**：量化研究员

**使用路径**：
1. 进入「数据管控中心」
2. 切换到「财务数据」分组
3. 检查「利润表 / 资产负债表 / 现金流量表」三张表的：
   - 最新数据日期（是否覆盖到需要的季度）
   - 数据量级（多少条记录）
   - 状态是否正常
4. 确认数据没问题，再去跑回测
5. 如果发现数据缺了最近一个季度，联系管理员增量更新

### 场景三：数据出问题后全量重建

**时间**：发现数据有问题后
**用户**：系统管理员

**使用路径**：
1. 在数据表列表找到有问题的表（如「日线行情 daily_quote」）
2. 点击「查看详情」，查看异常检测结果：
   - 缺失交易日：3 天（2026-07-18 ~ 2026-07-20）
   - 价格异常记录：5 条（high < low）
3. 确认数据有问题，点击「全量重建」
4. 弹出二次确认对话框：
   - 红色警告：「全量重建将清空 `daily_quote` 表的所有数据（约 500 万条），预计耗时 30-60 分钟，期间该表数据不可用。」
   - 输入框要求输入表名 `daily_quote` 进行二次确认
   - 确认按钮 10 秒倒计时后才可点击
5. 确认后，页面显示实时进度条
6. 重建完成后，状态恢复为正常，所有检测项通过

### 场景四：查看数据更新历史

**时间**：例行检查
**用户**：系统管理员

**使用路径**：
1. 进入「拉取日志」Tab
2. 按时间倒序查看最近 7 天的所有数据拉取任务
3. 可以按表名 / 状态（成功/失败）筛选
4. 点击某条失败记录，查看详细错误堆栈

---

## 2. 页面整体架构

### 2.1 页面布局

```
┌─────────────────────────────────────────────────────────┐
│  面包屑：首页 / 系统管理 / 数据管控中心                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  总览卡片（3 张）                                   │   │
│  │  [已接入表数] [今日已更新] [异常表数]               │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Tab 栏：数据表总览 | 拉取日志 | 数据源             │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  数据表总览（默认 Tab）                             │   │
│  │  ┌────────────────────────────────────────────┐  │   │
│  │  │  筛选栏：分组下拉 | 状态筛选 | 搜索框          │  │   │
│  │  └────────────────────────────────────────────┘  │   │
│  │                                                    │   │
│  │  ┌────────────────────────────────────────────┐  │   │
│  │  │  数据表卡片列表                               │  │   │
│  │  │  每张卡片：状态灯 + 表名 + 数据量 + 最新日期   │  │   │
│  │  │           + 异常原因（如有） + 操作按钮        │  │   │
│  │  └────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 2.2 侧边栏菜单位置

```
主要功能
  ├─ 仪表盘
  ├─ 行情中心
  ├─ 选股中心
  ├─ 策略管理
  ├─ 回测中心
  └─ ...
系统管理（新增分组）
  ├─ 数据管控中心 ← 新增
  ├─ 用户管理
  └─ 系统设置
```

---

## 3. 功能需求

### FR-1：总览卡片

#### 3.1.1 功能描述

页面顶部 3 张关键指标卡片，让用户一眼掌握全局数据健康状况：

| 卡片 | 指标 | 颜色规则 |
|---|---|---|
| **已接入数据表** | X / Y（当前已配置 / 总表数） | 蓝色 |
| **今日已更新** | X / Y（今日成功 / 应更新表数） | 全部成功绿色，有失败红色 |
| **异常表数** | N 张 | 0 张灰色，>0 张红色（仅统计 ERROR 级，WARN 级不计入） |

#### 3.1.2 交互细节

- 点击「今日已更新」卡片 → 跳转到「拉取日志」Tab，筛选今日
- 点击「异常表数」卡片 → 数据表列表自动筛选状态=异常

#### 3.1.3 用户价值

- 真正实现"数据状态一屏总览"，不用挨个表去查
- 简单直接，没有复杂的评分系统

---

### FR-2：数据表总览（列表视图）

#### 3.2.1 功能描述

以卡片列表形式展示所有数据表，每张卡片包含核心信息和操作按钮。

**卡片内容**：

```
┌─────────────────────────────────────────────┐
│ ● 日线行情                    daily_quote  │ ← 状态灯 + 中文名 + 表名
│ ─────────────────────────────────────────  │
│ 数据量级：约 500 万条                       │
│ 最新数据：2026-07-21（1 天前）              │ ← 新鲜度
│ 更新频率：每个交易日 15:00-17:00            │
│ ─────────────────────────────────────────  │
│ ⚠️ 异常：缺失 3 个交易日（红色小字，仅异常时）│ ← 异常摘要
│ ─────────────────────────────────────────  │
│ [查看详情] [增量更新] [全量重建]            │ ← 操作按钮
└─────────────────────────────────────────────┘
```

**状态灯定义**：

| 状态 | 颜色 | 含义 |
|---|---|---|
| ✅ 正常 | 绿色 | 所有检测项通过，数据在预期更新时间内 |
| ⚠️ 延迟 | 黄色 | 最新数据晚于预期更新时间 1 个交易日 |
| ❌ 异常 | 红色 | 至少一项检测不通过 / 拉取失败 / 数据严重缺失 |
| ⏳ 更新中 | 蓝色旋转 | 正在拉取数据 |

#### 3.2.2 筛选与搜索

- **分组筛选**：全部 / 基础数据 / 行情数据 / 财务数据 / 事件数据 / 指数与市场
  - 基础数据（BASIC）：stock_basic, trade_cal
  - 行情数据（MARKET）：daily_quote, adj_factor, daily_basic, stock_stk_limit, stock_moneyflow
  - 财务数据（FINANCE）：income, balancesheet, cashflow, fina_indicator, forecast, express
  - 事件数据（EVENT）：dividend, stock_namechange, stock_suspend_d, top_list, top_inst, block_trade
  - 指数与市场（INDEX）：index_daily, hk_hold, margin, margin_detail, index_weight, sw_industry
- **状态筛选**：全部 / 正常 / 延迟 / 异常 / 更新中
- **搜索框**：按中文名 / 表名模糊搜索

**分组设计说明**：
- 行情数据 = 个股日频全覆盖数据（每只股票每天都有）
- 事件数据 = 不规律发生的事件型数据（不是每天都有，也不是每只股票都有）
- 指数与市场 = 指数行情 + 跨市场/宏观数据（沪深港通、两融、指数权重、行业分类）

---

### FR-3：数据表详情面板

#### 3.3.1 功能描述

点击数据表卡片的「查看详情」，从右侧滑出详情抽屉，包含 3 个 Tab：

| Tab | 内容 |
|---|---|
| 基本信息 | 表名、中文名、对应 tushare 接口、数据量级、最新/最早数据日期、更新频率、字段列表 |
| 检测结果 | 该表所有检测项的通过/不通过状态，不通过的显示具体原因和示例数据 |
| 更新历史 | 最近 30 天的更新记录（时间、状态、耗时、新增/更新条数、操作人） |

#### 3.3.2 检测结果展示

检测结果以「检查项列表」形式展示，**全部检测项都展示**（通过的打勾，不通过的标红），每项一行：

```
✅ 新鲜度检测         通过，最新数据 2026-07-21（1 天前）
✅ 数据量异常检测     通过，最近 7 天日均 5,120 条，稳定
❌ 价格逻辑检测       不通过（ERROR），最近 30 天 3 条异常：high < low
⚠️ 跨表一致性校验     不通过（WARN），有 2 只股票有行情但无复权因子
✅ 数据量变动检测     通过，较上次 +0.3%（+15,360 条）
```

**展示规则**：
- 每项左侧有状态图标：✅ 通过 / ❌ ERROR 级不通过 / ⚠️ WARN 级不通过
- 每项右侧显示检测项名称 + 结果摘要（通过时显示数值，不通过时显示原因和数量）
- 不通过的检测项可以点击展开，查看详情（具体哪些日期、哪些股票、多少条记录等）
- 底部显示「共 5 项检测，3 项通过，1 项错误，1 项警告」的汇总文字

**设计说明**：
- 全量展示所有检测项，让用户清楚"这张表到底检测了哪些维度"，更有安全感
- 通过的项也带具体数值（如"最新数据 2026-07-21"），不只是干巴巴的"通过"
- 每张表只有 3-5 个检测项，全展示不占空间，信息密度更高

---

### FR-4：增量更新与全量重建

#### 3.4.1 功能描述

每张数据表都支持两种数据修复操作：

| 操作 | 说明 | 适用场景 |
|---|---|---|
| **增量更新** | 从最新数据日期的下一天开始，拉取到今天；使用 UPSERT 写入，已存在的数据覆盖更新 | 缺了最近几天数据，补数据 |
| **全量重建** | 清空表，从头开始拉取全部历史数据；采用批量写入 + 索引优化策略（详见 §7 性能优化） | 数据严重错乱 / 怀疑历史数据有问题 |

**全量重建二次确认**：
- 弹出对话框，红色警告文字：
  ```
  ⚠️ 全量重建将清空 daily_quote 表的所有数据（约 500 万条）
  预计耗时：30-60 分钟
  期间该表数据不可用，请确认不会影响线上业务

  请输入表名 daily_quote 确认操作
  ```
- 输入框要求用户输入表名进行二次确认（防误操作）
- 确认按钮 10 秒倒计时后才可点击

#### 3.4.2 实时进度展示

执行更新/重建时，显示实时进度：

- 整体进度条（百分比）
- 当前步骤描述（如「正在拉取 2024 年数据...」）
- 已处理股票数 / 总股票数（或已处理天数 / 总天数）
- 已写入数据量：XX 万条
- 已用时间 / 预计剩余时间
- 实时日志（可展开/收起，显示最近 100 行）

#### 3.4.3 并发控制

- 同一时间只能有一个数据操作在执行（无论哪张表）
- 如果已有任务在运行，新操作按钮置灰，提示「有任务正在执行，请稍后再试」
- 任务状态持久化到数据库，刷新页面不丢失

---

### FR-5：拉取日志

#### 3.5.1 功能描述

独立 Tab，展示所有数据拉取任务的历史记录，支持筛选和查询。

**列表字段**：

| 字段 | 说明 |
|---|---|
| 任务 ID | 唯一标识 |
| 数据表 | 对应的数据表名 |
| 操作类型 | 定时更新 / 手动增量 / 手动全量 |
| 状态 | 成功 / 失败 / 进行中 |
| 开始时间 | 任务开始时间 |
| 耗时 | 执行总时长 |
| 处理条数 | 新增/更新了多少条记录 |
| 操作人 | 谁触发的（定时任务显示"系统"） |

**筛选条件**：
- 数据表筛选
- 状态筛选
- 时间范围选择
- 操作类型筛选

**详情查看**：
- 点击某条记录，右侧滑出详情
- 成功：显示统计信息（新增 N 条，更新 M 条，耗时 XX）
- 失败：显示错误信息、错误堆栈（可复制）

---

### FR-6：数据源状态

#### 3.6.1 功能描述

独立 Tab，展示 tushare 数据源的**连通性状态**（只读，不可修改 Token）。

**内容**：

| 项目 | 说明 |
|---|---|
| 数据源名称 | Tushare Pro |
| 连接状态 | ✅ 已连接 / ❌ 连接失败 |
| 响应耗时 | 最近一次测试的响应时间（毫秒） |
| 测试接口 | `trade_cal（交易日历）`——轻量接口，消耗积分少 |
| 最后检测时间 | 上次连通性检测时间 |
| 检测频率 | 每小时自动检测一次 |

**操作按钮**：
- 「重新测试」：手动立即触发一次连通性测试（仅管理员可见）

> **说明**：Tushare Token 通过 `application.yml` 配置，不允许在页面上查看或修改。如需更换 Token，需修改配置文件后重启服务。

---

## 4. 各表校验项明细（核心）

> **设计原则**：全量透明，校验自由。
> 
> - 每个检测项有稳定标识（name）+ 展示名（displayName）+ 是否通过（passed）+ 级别（level）+ 描述（message）
> - 每次检测返回**全部**检测项结果（含通过的），用户能清楚"检测了哪些维度"
> - 每张表的 Service 自己实现 `checkData()` 方法，想校验什么、怎么校验、message 怎么写，全自己定
> - 框架只负责：调度执行 + 汇总结果 + 存到 `data_governance_metric` 表 + 推导表状态

### 4.1 接口契约

```java
/**
 * 数据校验接口。每张业务表的 Service 想接入数据管控，就实现这个接口。
 */
public interface DataCheckable {
    /** 执行校验，返回所有检测项的结果（含通过的和不通过的） */
    DataCheckResult checkData();
    /** 表代码，对应 InitStep.code */
    String getTableCode();
}

/** 单条检测项结果 */
@Data
@Builder
public class DataCheckItem {
    private String name;          // 检测项标识（英文，如 "freshness", "price_logic"）
    private String displayName;   // 展示名称（中文，如 "新鲜度检测", "价格逻辑检测"）
    private boolean passed;       // 是否通过
    private CheckLevel level;     // 严重级别：ERROR / WARN（仅 passed=false 时有意义）
    private String message;       // 详细说明：不通过时描述原因和数量；通过时可写"通过"或具体数值
}

public enum CheckLevel { ERROR, WARN }

/** 整表校验结果 */
@Data
@Builder
public class DataCheckResult {
    private String tableCode;
    private String tableName;
    private long totalRows;           // 总记录数
    private String latestDate;        // 最新数据日期
    private List<DataCheckItem> items;  // 所有检测项结果（含通过和不通过）
}
```

**设计说明**：
- **全量返回**：所有检测项都返回（通过 + 不通过），用户能看到"这张表到底检测了哪些东西"，更透明可信
- **name + displayName 分离**：name 是稳定的英文标识（前端可用 icon 映射），displayName 是给人看的中文
- **passed 字段**：明确布尔值，不用靠 level == null 判断"是否通过"，语义更清晰
- **每张表 3-5 个检测项**，数据量极小，全量返回不影响性能
- 列表页的"异常摘要"只取前 2 个不通过的 message 拼接（和之前一样）

**关于 `latestDate` 取值来源**：
- `latestDate` 由**业务层 Service 的 `checkData()` 方法自己赋值**（框架层不查数据库，只拿这个值用）
- 不同类型的表，"最新数据日期"取不同的字段：

| 表类型 | 代表表 | latestDate 取哪个字段 | 说明 |
|---|---|---|---|
| 日频行情类 | daily_quote, adj_factor, daily_basic, stock_stk_limit, stock_moneyflow, index_daily, hk_hold, margin, margin_detail | `max(trade_date)` | 交易日维度 |
| 财务报表类 | income, balancesheet, cashflow, fina_indicator | `max(ann_date)` | 用**公告日**，不用 end_date（报告期） |
| 业绩预告/快报 | forecast, express | `max(ann_date)` | 公告日 |
| 事件类（有日期的） | dividend, stock_suspend_d, top_list, top_inst, block_trade | `max(ex_date / trade_date / ann_date)` | 取各自的主日期字段 |
| 事件类（区间型） | stock_namechange | `max(start_date)` | 取最近一条的开始日期 |
| 基础/分类/权重 | stock_basic, trade_cal, index_weight, sw_industry | `null` 或空字符串 | 没有"最新数据日期"概念，框架层跳过延迟判断 |

- 日频表的 `latestDate` 不能为空（空了框架层直接跳过延迟判断）
- 非日频表的 `latestDate` 可以为空，框架层本来就不会用它判断延迟

### 4.2 框架调度方式

Spring 自动发现所有实现了 `DataCheckable` 的 Bean，按 `tableCode` 建索引：

```java
@Service
public class DataGovernanceService {

    private final Map<String, DataCheckable> checkableMap;

    public DataGovernanceService(List<DataCheckable> checkables) {
        this.checkableMap = checkables.stream()
                .collect(Collectors.toMap(DataCheckable::getTableCode, Function.identity()));
    }

    /** 校验单张表 */
    public DataCheckResult checkTable(String tableCode) {
        DataCheckable checkable = checkableMap.get(tableCode);
        DataCheckResult result = checkable.checkData();
        saveMetric(result);  // 存到 data_governance_metric 表
        return result;
    }

    /** 校验所有表 */
    public List<DataCheckResult> checkAll() { ... }
}
```

> 新增一张表的校验 = Service 实现 `DataCheckable` 接口，加个 `checkData()` 方法。框架代码不用改。

---

### 4.3 各表校验项明细

> **设计原则**：务实、精准、不凑数。
> - 砍掉无意义检测项：退市股占比、预告区间宽度、同期对比差异、买卖差额平衡、"超过N天未拉取"（与拉取日志重复）等
> - 核心思路转向：**跨表一致性校验** + **日期/金额逻辑校验** + **最近N天数据质量**（而非全表抽样）
> - 空表检测降级为隐性兜底（表为空直接判定 ERROR），不再占一个编号

下面 25 张表，每张表列出：
- **校验项**：不通过时返回的 message 文案
- **核查逻辑**：大概怎么查（SQL 思路）
- **级别**：ERROR / WARN

---

#### 4.3.1 基础数据类

##### stock_basic（股票基础信息）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 有 {n} 只股票在行情中出现但基础信息缺失 | 最近 7 天 daily_quote 中出现过的 ts_code，不在 stock_basic 中的数量 | ERROR | **跨表一致性**：行情表有数据但基础信息表没有，回测/选股会出问题 |
| 2 | 上市股票数量与行情数据差异过大（{pct}%） | `stock_basic 中 list_status='L' 的数量` 与 `最近7天 daily_quote 的 distinct ts_code 数` 对比，差异 > 5% | WARN | 用行情数据当参照系，比拍脑袋阈值靠谱 |
| 3 | 关键字段为空的股票有 {n} 只 | `list_status IS NULL OR market IS NULL OR exchange IS NULL` 的数量 | WARN | 基础表关键字段不能空 |

##### trade_cal（交易日历）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 交易日历未覆盖到未来 30 天 | `max(cal_date) < CURDATE() + 30天` | ERROR | 定时任务要取下一交易日，必须有未来数据 |
| 2 | 缺少 {exchange} 交易所的日历数据 | 按 exchange 分组，任一组 count = 0 | ERROR | SSE/SZSE 必须都有 |
| 3 | 最近 30 天沪深交易日标记不一致（{n} 天） | 最近 30 天内，SSE 和 SZSE 的 is_open 不一致的天数 > 1 | WARN | 沪深交易日几乎总是一致的，不一致可能是数据错了 |
| 4 | 存在周末被标记为交易日 | `DAYOFWEEK(cal_date) IN (1,7) AND is_open='1'` 的记录数 | ERROR | 周末不可能是交易日（特殊安排极少） |

---

#### 4.3.2 行情数据类（全覆盖型）

##### daily_quote（日线行情）⭐ 最核心

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日`（从 trade_cal 取） | ERROR | 核心新鲜度检测 |
| 2 | 最近 7 天日均数据量仅 {count} 条，较前 20 天下降 {pct}% | 最近 7 天日均记录数 vs 前 20 天日均记录数，下降 > 30% | ERROR | **数据量异常检测**：比"缺失交易日"更准——量不对就是有问题 |
| 3 | 最近 30 天价格异常记录 {n} 条 | 最近 30 天，`high < low OR close <= 0 OR open <= 0 OR pre_close <= 0` 的记录数 | ERROR | 不抽样，直接查最近 30 天，既快又准 |
| 4 | 最近 30 天收盘价超出涨跌停 {n} 条 | 关联 stock_stk_limit，`close > up_limit * 1.005 OR close < down_limit * 0.995` 的记录数 | WARN | **跨表校验**：收盘价应在涨跌停范围内（允许 0.5% 浮点误差） |
| 5 | 当日股票覆盖度仅 {pct}% | `当日 distinct ts_code 数 / 当日 stock_basic 中 list_status='L' 的数量 < 0.9` | WARN | 分母用"当前上市股票数"更合理 |

##### adj_factor（复权因子）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 最近 30 天复权因子异常 {n} 条 | 最近 30 天，`adj_factor <= 0 OR adj_factor > 10` 的记录数 | ERROR | 复权因子一般在 0.5~10 之间，太大太小都不对 |
| 3 | 有 {n} 只股票有行情但无复权因子 | 最近 7 天 daily_quote 中出现的 ts_code，在 adj_factor 同日不存在的数量 | WARN | **跨表一致性**：正常应一一对应 |
| 4 | 复权因子突变股票 {n} 只 | 最近 30 天，某只股票相邻两日 adj_factor 变化幅度 > 55% 的数量 | WARN | 除权除息日会有变化，但 55% 以上的跳变少见（10 送 10 约 50%） |

##### daily_basic（每日基本面/估值）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 最近 30 天总市值为空/为 0 记录 {n} 条 | 最近 30 天，`total_mv IS NULL OR total_mv <= 0` | ERROR | 市值是最核心的，空 = 数据有问题 |
| 3 | 最近 30 天换手率/量比异常 {n} 条 | 最近 30 天，`turnover_rate < 0 OR volume_ratio <= 0` 的记录数 | WARN | 辅助值域校验 |
| 4 | 有行情但无估值数据的股票 {n} 只 | 最近 7 天 daily_quote 中有、但 daily_basic 中没有的 ts_code 数量 | WARN | **跨表一致性** |

##### stock_stk_limit（涨跌停价）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 最近 30 天涨跌停价异常 {n} 条 | 最近 30 天，`up_limit <= down_limit OR up_limit <= 0 OR down_limit <= 0` | ERROR | 核心逻辑校验 |
| 3 | 最近 30 天收盘价超出涨跌停 {n} 条 | 关联 daily_quote，`close > up_limit * 1.005 OR close < down_limit * 0.995` | WARN | **跨表校验**（允许 0.5% 浮点误差） |

##### stock_moneyflow（个股资金流向）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 最近 30 天金额为负/为 0 记录 {n} 条 | 最近 30 天，`buy_sm_amount < 0 OR sell_sm_amount < 0 OR net_mf_amount IS NULL` | ERROR | 值域校验 |
| 3 | 最近 30 天净流入计算异常 {n} 条 | 最近 30 天，`abs(net_mf_amount - (buy_elg_amount + buy_lg_amount + buy_md_amount + buy_sm_amount - sell_elg_amount - sell_lg_amount - sell_md_amount - sell_sm_amount)) / (abs(net_mf_amount) + 1) > 0.1` | WARN | 净流入应约等于各档位买入 - 卖出，差太多说明数据有问题 |
| 4 | 有行情但无资金流向 {n} 只 | 最近 7 天有行情但无 moneyflow 数据的股票数 | WARN | 覆盖率参考 |

---

#### 4.3.3 指数/特殊行情类

##### index_daily（指数日线）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 缺少核心指数：{names} | 最近 30 天，`ts_code in ('000001.SH', '399001.SZ', '399006.SZ', '000300.SH', '000905.SH', '000852.SH')` 中有几只无数据 | ERROR | 沪深300/中证500/中证1000 也是核心指数 |
| 3 | 最近 30 天价格异常 {n} 条 | 最近 30 天，`high < low OR close <= 0 OR open <= 0` | ERROR | 指数不多，全查很快 |

##### hk_hold（沪深港通持股）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天（T+1） | `max(trade_date) < 上一交易日 - 1天` | ERROR | 沪深港通是 T+1 公布，多容忍 1 天 |
| 2 | 最近 30 天持股数为负 {n} 条 | 最近 30 天，`vol < 0` | ERROR | 持股数不能为负，0 是正常的 |
| 3 | 最近 30 天持股占比异常 {n} 条 | 最近 30 天，`ratio < 0 OR ratio > 30` | WARN | 沪深港通持股比例上限约 28%，超过 30% 基本不可能 |

##### margin（融资融券-汇总）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 缺少 {exchange} 市场融资融券数据 | 按 exchange_id 分组，任一组最近 7 天无数据 | ERROR | 沪深两市都应有 |
| 3 | 最近 30 天余额异常 {n} 条 | 最近 30 天，`rzye <= 0 OR rqye < 0 OR rzrqye <= 0` | ERROR | 全市场融资余额不可能为 0 |

##### margin_detail（融资融券-明细）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新交易日为 {date}，已延迟 {n} 天 | `max(trade_date) < 上一交易日` | ERROR | 新鲜度 |
| 2 | 最近 30 天余额异常 {n} 条 | 最近 30 天，`rzye < 0 OR rqye < 0` | ERROR | 余额不能为负 |
| 3 | 融资明细合计与汇总差异过大 | 最近 1 天，按市场分组 sum(margin_detail.rzye) 与 margin.rzye 对比，差异 > 0.5% | WARN | **跨表一致性校验**：明细合计应约等于汇总 |

---

#### 4.3.4 财务数据类（部分覆盖型）

##### income（利润表）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 距最近一次财报公告已超过 90 天 | `max(ann_date) < CURDATE() - 90天`，且不在财报季豁免期（1-4月、7-8月、10月） | WARN | **用 ann_date（公告日）判断新鲜度**，不是 end_date |
| 2 | 最近一个季度营收为空/为负 {n} 条 | 最近报告期（按 max(end_date)），`total_revenue IS NULL OR total_revenue <= 0` | ERROR | 核心字段不能空或负 |
| 3 | 净利润超过营收 10 倍 {n} 条 | 最近 3 个季度，`n_income > total_revenue * 10 AND total_revenue > 0` | WARN | 合理性校验：利润超过营收 10 倍极少见 |
| 4 | 上市超 1 年但无任何利润表数据 {n} 只 | stock_basic 中 list_date 早于 1 年前、且 income 表 0 条记录的股票数 | WARN | 正常上市公司至少有年报 |

##### balancesheet（资产负债表）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 距最近一次财报公告已超过 90 天 | `max(ann_date) < CURDATE() - 90天`，且不在财报季豁免期（1-4月、7-8月、10月） | WARN | 公告日判断新鲜度 |
| 2 | 最近一个季度总资产为空/为负 {n} 条 | 最近报告期，`total_assets IS NULL OR total_assets <= 0` | ERROR | 核心字段 |
| 3 | 资产负债表不平 {n} 条 | 最近 3 个季度，`abs(total_liab + total_equity - total_liab_equity) / NULLIF(total_assets, 0) > 0.01`（差异 > 1%） | ERROR | **会计恒等式校验**：资产 = 负债 + 权益，价值极高 |
| 4 | 上市超 1 年但无资产负债数据 {n} 只 | 上市超 1 年、balancesheet 表 0 条记录的股票数 | WARN | 参考 |

##### cashflow（现金流量表）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 距最近一次财报公告已超过 90 天 | `max(ann_date) < CURDATE() - 90天`，且不在财报季豁免期（1-4月、7-8月、10月） | WARN | 公告日判断 |
| 2 | 最近一季度经营现金流为空 {n} 条 | 最近报告期，`n_cashflow_act IS NULL` | ERROR | 核心字段 |
| 3 | 期末现金与资产负债表货币资金差异过大 {n} 条 | 关联 balancesheet，最近 3 个季度 `abs(end_bal_cash - monetary_funds) / NULLIF(monetary_funds, 0) > 0.1` | WARN | **跨表勾稽关系**：现金期末余额应与货币资金接近 |
| 4 | 现金净增加额与期末期初差额不一致 {n} 条 | 最近 3 个季度，`abs(n_cash_equ - (end_bal_cash - beg_bal_cash)) / NULLIF(abs(end_bal_cash), 0) > 0.1` | WARN | 表内勾稽关系校验 |

##### fina_indicator（财务指标）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 距最近一次公告已超过 90 天 | `max(ann_date) < CURDATE() - 90天`，且不在财报季豁免期（1-4月、7-8月、10月） | WARN | 公告日判断 |
| 2 | 最近一季度 ROE/ROA 均为空 {n} 条 | 最近报告期，`roe IS NULL AND roa IS NULL` | ERROR | 核心指标不能为空 |
| 3 | 最近一季度资产负债率异常 {n} 条 | 最近报告期，`debt_to_assets < 0 OR debt_to_assets > 100` | ERROR | 资产负债率应在 0-100 之间 |
| 4 | ROE 与利润/净资产计算值差异过大 {n} 条 | 关联 income + balancesheet，用 `n_income_attr_p / NULLIF(equity_parent_company, 0) * 100` 计算的 ROE 与 roe 字段差异 > 10% | WARN | **跨表勾稽**：验证指标计算正确性 |

##### forecast（业绩预告）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 上一个财报季无任何预告（可能断更） | 上一个 1/4/7/10 月（财报季首月），该月 0 条预告数据 | WARN | 只在财报季检查，非财报季不查 |
| 2 | 变动幅度上下限颠倒 {n} 条 | `p_change_min > p_change_max` 或 `net_profit_min > net_profit_max` | ERROR | 逻辑硬错误 |
| 3 | 预告类型与变动方向矛盾 {n} 条 | `type='预增' AND p_change_max < 0` 或 `type='预减' AND p_change_min > 0` 等明显矛盾 | ERROR | **数据一致性校验** |
| 4 | 净利润上下限与上年同期矛盾 {n} 条 | `last_parent_net > 0 AND net_profit_max < 0 AND type IN ('预增','略增')` | WARN | 辅助校验 |

##### express（业绩快报）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 上一个快报季无任何快报（可能断更） | 上一个 2-3/4-5/8-9/10-11 月（快报季）结束后，该季 0 条数据 | WARN | 只在快报季检查 |
| 2 | 营业收入/总资产为负 {n} 条 | 最近季度，`revenue < 0 OR total_assets < 0` | ERROR | 收入和资产不应为负（净利润可以负） |
| 3 | 净利润增长率与净利润值矛盾 {n} 条 | 最近季度，`growth_yield > 0 AND n_income < yst_net_profit AND yst_net_profit > 0`（正增长但利润更少了） | WARN | 合理性校验 |

---

#### 4.3.5 事件驱动类（不做覆盖度检测）

##### dividend（分红送股）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 分红金额为负 {n} 条 | `cash_div < 0 OR cash_div_tax < 0` | ERROR | 分红不能是负数 |
| 2 | 送转股比例为负 {n} 条 | `stk_div < 0 OR stk_bo_rate < 0 OR stk_co_rate < 0` | ERROR | 不能为负 |
| 3 | 日期逻辑错误 {n} 条 | `ex_date < ann_date OR record_date < ann_date OR (pay_date IS NOT NULL AND pay_date < ex_date)` | ERROR | 时间先后逻辑 |

##### stock_namechange（股票更名/ST 戴帽）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 日期逻辑错误 {n} 条 | `end_date IS NOT NULL AND start_date > end_date` | ERROR | 开始日不能晚于结束日 |
| 2 | 同一股票存在日期重叠 {n} 条 | 按 ts_code 分组，相邻记录的 start_date < 上一条 end_date 的数量 | WARN | 更名历史应连续不重叠 |
| 3 | 名称为空 {n} 条 | `name IS NULL OR name = ''` | ERROR | 名称不能为空 |

##### stock_suspend_d（停复牌）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 停牌日期晚于复牌日期 {n} 条 | `resump_date IS NOT NULL AND trade_date > resump_date` | ERROR | 核心逻辑校验 |
| 2 | 同一股票停牌日期重叠 {n} 条 | 按 ts_code 分组，存在日期重叠的记录数 | WARN | 停牌不应重叠 |

##### top_list（龙虎榜-每日榜单）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 成交金额为负/为 0 {n} 条 | 最近 30 天，`amount <= 0` | ERROR | 金额不能为负或 0 |
| 2 | 涨跌幅异常 {n} 条 | 最近 30 天，`pct_change < -21 OR pct_change > 21` | WARN | 龙虎榜个股涨跌幅一般不超过 20%，超 21% 可能数据错了 |
| 3 | 买卖净额与买入卖出额不一致 {n} 条 | 最近 30 天，`abs(net_amount - (l_buy_amount - l_sell_amount)) / (abs(net_amount) + 1) > 0.1` | WARN | 净额应约等于买入 - 卖出 |

##### top_inst（龙虎榜-机构席位）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 买入额为负 {n} 条 | 最近 30 天，`buy < 0 OR sell < 0` | ERROR | 金额不能为负 |
| 2 | 买卖方向与金额矛盾 {n} 条 | 最近 30 天，`(side='Buy' AND buy = 0) OR (side='Sell' AND sell = 0)` | WARN | 方向和金额应该对应 |
| 3 | 与 top_list 关联不上 {n} 条 | 最近 30 天，top_inst 中的 (trade_date, ts_code) 在 top_list 中不存在的数量 | WARN | **跨表一致性** |

##### block_trade（大宗交易）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 价格/成交量为负或 0 {n} 条 | 最近 30 天，`price <= 0 OR vol <= 0 OR amount <= 0` | ERROR | 核心数据不能为负或 0 |
| 2 | 买卖方营业部相同 {n} 条 | 最近 30 天，`buyer = seller` | WARN | 买卖方不能是同一家（极少情况，值得关注） |
| 3 | 成交额与价格×成交量不符 {n} 条 | 最近 30 天，`abs(amount - price * vol) / (amount + 1) > 0.1` | WARN | 金额 ≈ 价格 × 数量 |

---

#### 4.3.6 分类/权重参考类

##### index_weight（指数成分股权重）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 最新月份数据未更新 | `max(trade_date) 所在月份 < 上月` | ERROR | 月度数据，至少要上月的 |
| 2 | {index} 成分股数量异常：{n} 只 | 最新一期：`000300.SH` 不在 295-305 只之间；`000905.SH` 不在 490-510 只之间；`000852.SH` 不在 980-1020 只之间 | ERROR | 各指数有固定成分股数，偏差应很小 |
| 3 | {index} 权重合计异常：{sum}% | 最新一期，权重总和不在 99-101 之间 | WARN | 权重合计应接近 100% |
| 4 | 权重为负或异常大 {n} 条 | 最新一期，`weight <= 0 OR weight > 20`（单只股权重超过 20% 不太可能） | ERROR | 值域校验 |

##### sw_industry（申万行业分类+成分）

| # | message 示例 | 核查逻辑 | 级别 |
|---|---|---|---|
| 1 | 一级行业数量异常：{n} 个 | `sw_industry 中 level=1 的 count` 不在 28-35 之间（申万一级约 31 个） | ERROR | 行业分类数相对固定 |
| 2 | 有行业分类的股票覆盖度仅 {pct}% | `sw_industry_member 中 is_new='1' 的 distinct ts_code 数 / stock_basic 中 list_status='L' 的数量 < 0.9` | WARN | 大多数 A 股都有申万分类 |
| 3 | 行业代码与名称不匹配 {n} 条 | 同一 index_code 对应多个 index_name 的情况 | WARN | 行业代码和名称应一一对应 |
| 4 | 成分股纳入日期晚于剔除日期 {n} 条 | `out_date IS NOT NULL AND in_date > out_date` | ERROR | 日期逻辑 |

---

### 4.4 状态判定逻辑

表的最终状态（NORMAL / DELAYED / ERROR / UPDATING）由以下规则综合判定：

```
当前是否有任务在运行？
  ├─ 是 → status = UPDATING（更新中，蓝色）
  └─ 否 → 继续往下判断
        ├─ 有任意 ERROR 级检测项不通过？
        │     ├─ 是 → status = ERROR（异常，红色）
        │     └─ 否 → 继续往下判断
        │           ├─ 数据是否"延迟"？（见下方延迟判定规则）
        │           │     ├─ 是 → status = DELAYED（延迟，黄色）
        │           │     └─ 否 → 继续往下判断
        │           │           ├─ 有 WARN 级检测项不通过？
        │           │           │     ├─ 是 → status = NORMAL（正常，绿色，但详情里有 WARN 提示）
        │           │           │     └─ 否 → status = NORMAL（正常，绿色）
```

**延迟判定规则**（仅日频表判定延迟，非日频表跳过）：
- 交易日 `expected_update_time`（如 17:00）之后，检查 `latest_date` 是否等于「上一交易日」
- 如果 `latest_date < 上一交易日` → 标记为 DELAYED
- 非交易日不判定延迟（周末/节假日数据本来就不更新）

**日频表清单**（9 张）：daily_quote, adj_factor, daily_basic, stock_stk_limit, stock_moneyflow, index_daily, hk_hold, margin, margin_detail

**非日频表清单**（16 张）：
- 基础数据：stock_basic, trade_cal
- 财务数据：income, balancesheet, cashflow, fina_indicator, forecast, express
- 事件数据：dividend, stock_namechange, stock_suspend_d, top_list, top_inst, block_trade
- 分类/权重：index_weight, sw_industry

**优先级**：UPDATING > ERROR > DELAYED > NORMAL（状态取最严重的那个）

> **关于延迟判断的设计说明**：
> - 延迟判断是**框架层（DataGovernanceService）**的职责，不依赖 `check_items` 中任何检测项的 `name`
> - 判断依据是 `DataCheckResult.latestDate` 这个结构化字段 + `trade_cal` 交易日历 + 表的元信息（是否日频表、`expectedUpdateTime`）
> - 各表 `checkData()` 里的"新鲜度检测"只是**详情面板的展示内容**，写什么 message、叫什么 name 都自由，不参与状态推导
> - 如果某张日频表的新鲜度检测项是 **ERROR 级**（如数据断更很多天），ERROR 优先级高于 DELAYED，表状态直接显示 ERROR（红色），轮不到延迟判断
> - 这样设计的好处：不需要约定 name 常量，不存在各表 name 不一致的问题；框架层状态推导和业务层检测内容解耦

> **关于 UPDATING 状态**：UPDATING 是运行时状态，由「当前是否有正在执行的拉取任务」推导得出，**不存入 `data_governance_metric` 历史表**。列表页返回时，实时检查该表是否有运行中的任务，有则覆盖为 UPDATING。

---

### 4.5 检测执行策略

| 策略 | 说明 |
|---|---|
| **定时执行** | 每天晚上 **22:00** 自动执行全表检测（25 张表全部跑一遍），生成一个检测批次；确保在每日数据拉取任务（17:00-21:00）完成后执行 |
| **手动执行** | 支持「全表重跑」和「单表重跑」两种手动触发方式；单表重跑结果立即返回，同时写入 metric 表 |
| **返回规则** | 返回**所有**检测项（含通过和不通过），每项带 name/displayName/passed/level/message；列表页异常摘要只取前 2 个不通过的 message |
| **时间窗口策略** | 价格逻辑/值域检测优先查最近 30 天数据（量小、价值高），不做全表抽样；小表（< 10 万条）可全表扫描 |
| **空表兜底** | 由 **框架层（DataGovernanceService）**统一处理：检测前先 count，为 0 直接返回 ERROR（"表为空，0 条记录"），不调用业务 Service 的 checkData()，不占检测项编号 |
| **结果存储** | 每次检测结果写入 `data_governance_metric` 表，`check_items` 存全部检测项 JSON；同一次检测的 25 张表共享一个 `check_batch_id` |
| **数据保留** | metric 表保留最近 3 个月，过期自动清理 |
| **异常判定** | 有任意 ERROR 级 → 表状态 = 异常（红）；只有 WARN 级 → 表状态 = 正常（绿，详情里有 WARN 提示）；全通过 → 正常（绿） |
| **异常摘要** | 列表页展示时，从 `check_items` JSON 中取前 2 个不通过项的 message 拼接（最多 200 字），不单独存字段 |

---

### 4.6 数据量骤减检测（自动追加 WARN）

每次检测时，自动计算「当前记录数 vs 上次检测记录数」的变动率，**如果骤减超过 30%，自动追加一条 WARN 级检测项**。

**检测逻辑**：
```
row_delta_pct = (current_total_rows - last_total_rows) / last_total_rows * 100%
如果 row_delta_pct < -30%：
  追加检测项："数据量较上次检测骤降 {pct}%（{上次} → {当前}），请排查"，级别 WARN
```

**设计目的**：
- 防止"全量重建失败只写了一半数据"这种隐蔽问题
- 防止"误删了数据"但没人发现
- 正常情况下数据量只会增加（每天新增），减少一定是有问题的

**豁免情况**：
- 第一次检测（没有上次记录）不计算
- 该表最近一次成功的拉取任务是全量重建（MANUAL_FULL），则跳过本次骤减检测（全量重建可能数据量确实有变化，从 data_pull_log 判断）

---

### 4.7 安全与脱敏

数据管控涉及 API Token、错误堆栈等敏感信息，必须做安全处理：

| 敏感信息 | 处理方式 | 适用场景 |
|---|---|---|
| Tushare API Token | 仅从 `application.yml` 读取，**页面不展示、接口不返回**；错误堆栈中出现的 Token 自动脱敏替换为 `***` | 全系统 |
| 错误堆栈中的密码/密钥 | 正则匹配替换为 `***` | error_stack / error_message 字段 |
| 数据库连接信息 | 不出现在错误堆栈中（全局异常处理器过滤） | 所有错误返回 |
| error_stack 详情 | 普通用户不可见，仅管理员可查看 | 日志详情接口 |
| 全量重建操作 | 二次确认 + 输入表名验证 + 10 秒倒计时 | 前端交互 |
| 所有写操作接口 | @RequireAdmin 注解 + 操作人记录到日志 | 所有 POST/PUT/DELETE 接口 |

---

## 5. 数据模型设计

### 5.1 表清单总览

| 表名 | 用途 | 数据保留 |
|---|---|---|
| `data_governance_metric` | 数据质量检测历史（每晚 22:00 定时执行 + 手动触发，列表页取最新一天） | 最近 3 个月 |
| `data_pull_log` | 数据拉取任务日志（每次执行一条记录） | 最近 3 个月 |

> **设计说明**：
> - 任务进度**不存数据库**，放内存缓存（Caffeine / ConcurrentHashMap），任务执行完即清理
> - 数据源配置**不存数据库**，Token 从 application.yml 读取，连通性状态放内存缓存，定时刷新
> - 去掉独立的 `data_check_history` 表——`data_governance_metric` 本身就是历史表，列表页直接取最新一条即可

> **字段命名约定**：时间类字段统一用 `DATETIME` 类型，日期类用 `DATE` 类型，JSON 用 `JSON` 类型（MySQL 5.7+/8.0 支持）。绝不使用 `VARCHAR` 存日期。

---

### 5.2 新增表：data_governance_metric（数据质量检测历史）

每次质量检测写入一条记录（每晚 22:00 定时 + 手动触发）。列表页取**最新检测批次**的 25 条展示。保留最近 3 个月数据，用于做**数据量变动趋势**核对。

```sql
CREATE TABLE IF NOT EXISTS data_governance_metric (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_batch_id    VARCHAR(64)    NOT NULL COMMENT '检测批次ID（同一次检测的25条共享同一个batch_id）',
    table_code        VARCHAR(64)    NOT NULL COMMENT '表代码（对应 InitStep.code）',
    table_name        VARCHAR(64)    NOT NULL COMMENT '表中文名',
    table_group       VARCHAR(32)    NOT NULL DEFAULT 'REFERENCE' COMMENT '表分组：BASIC/MARKET/FINANCE/EVENT/INDEX',
    total_rows        BIGINT         DEFAULT 0 COMMENT '检测时总记录数',
    row_delta_pct     DECIMAL(8,2)   DEFAULT 0 COMMENT '较上次检测的记录数变动百分比（正数=增加，负数=减少）',
    latest_date       DATE           COMMENT '最新数据日期',
    earliest_date     DATE           COMMENT '最早数据日期',
    status            VARCHAR(16)    NOT NULL DEFAULT 'NORMAL' COMMENT '状态：NORMAL/DELAYED/ERROR',
    check_items       JSON           COMMENT '所有检测项结果（JSON 数组，含通过和不通过的）',
    check_time        DATETIME       NOT NULL COMMENT '检测执行时间',
    check_type        VARCHAR(16)    DEFAULT 'SCHEDULED' COMMENT '检测类型：SCHEDULED（定时）/ MANUAL（手动）',
    KEY idx_check_batch_id (check_batch_id),
    KEY idx_table_code_check_time (table_code, check_time),
    KEY idx_check_time (check_time),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量检测历史表';
```

**设计说明**：
- 每次检测 25 张表共享同一个 `check_batch_id`，方便回溯"某次检测的全部结果"
- `check_items` 存**全部**检测项（含通过的），JSON 数组格式，每项含 name/displayName/passed/level/message
- `row_delta_pct`：每次检测时，和该表上一次检测的 `total_rows` 对比算变动率；**骤减 > 30% 自动追加一条 WARN 级检测项**
- 列表页查询：取 `check_time` 最大的那个批次的全部记录（只有 25 行，极快）
- **去掉了 `error_summary` 字段**：异常摘要直接从 `check_items` JSON 中取前 2 个不通过项的 message 拼接，不需要冗余存储
- **去掉了 `expected_update_time` 字段**：这是表的静态元信息，放在 `InitStep` 枚举或配置类里就行，不应该每次检测都存到历史表里
- 数据保留策略：定时任务每天清理 3 个月前的旧数据

---

### 5.3 新增表：data_pull_log（数据拉取日志）

记录每次数据拉取任务的详细信息（成功/失败都记录）。保留最近 3 个月。

```sql
CREATE TABLE IF NOT EXISTS data_pull_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64)    NOT NULL COMMENT '任务唯一ID（UUID）',
    table_code      VARCHAR(64)    NOT NULL COMMENT '表代码',
    table_name      VARCHAR(64)    NOT NULL COMMENT '表中文名',
    operation_type  VARCHAR(32)    NOT NULL COMMENT '操作类型：SCHEDULED/MANUAL_INCREMENTAL/MANUAL_FULL',
    status          VARCHAR(16)    NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCESS/FAILED/CANCELLED',
    start_time      DATETIME       NOT NULL COMMENT '开始时间',
    end_time        DATETIME       COMMENT '结束时间',
    duration_ms     BIGINT         DEFAULT 0 COMMENT '耗时（毫秒）',
    total_count     BIGINT         DEFAULT 0 COMMENT '处理总数（条）',
    success_count   BIGINT         DEFAULT 0 COMMENT '成功数（条）',
    fail_count      BIGINT         DEFAULT 0 COMMENT '失败数（条）',
    error_message   VARCHAR(1024)  COMMENT '错误信息摘要（脱敏后）',
    error_stack     TEXT           COMMENT '错误堆栈详情（脱敏后，仅管理员可见）',
    operator        VARCHAR(64)    DEFAULT 'SYSTEM' COMMENT '操作人：用户名 / SYSTEM（定时任务）',
    KEY idx_task_id (task_id),
    KEY idx_table_code (table_code),
    KEY idx_status (status),
    KEY idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据拉取日志表';
```

**设计说明**：
- `task_id` 是全局唯一标识，进度查询、取消操作都用它
- `status` 多了 `CANCELLED`（已取消）状态
- 保留最近 3 个月，过期自动清理

---

## 6. 后端接口设计

> **通用约定**：
> - 所有接口统一返回 `Result<T>` 包装（与项目现有规范一致）
> - 时间字段统一返回 ISO 格式字符串（如 `2026-07-21T15:30:00`），前端自行格式化
> - 写操作接口统一加 `@RequireAdmin` 注解（需管理员权限）
> - 列表接口不带分页（只有 25 张表，全量返回即可）；日志接口带分页

---

### 6.1 总览接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/data-governance/overview` | 登录即可 | 获取总览数据（3 张卡片的数字） |

**响应示例**：
```json
{
  "totalTables": 25,
  "updatedToday": 23,
  "errorTables": 1,
  "lastCheckTime": "2026-07-22T09:15:00"
}
```

---

### 6.2 数据表接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/data-governance/tables` | 登录即可 | 获取数据表列表（全量，带筛选、搜索） |
| GET | `/api/data-governance/tables/{tableCode}` | 登录即可 | 获取单表基本信息 |
| GET | `/api/data-governance/tables/{tableCode}/check-result` | 登录即可 | 获取单表检测结果详情 |
| GET | `/api/data-governance/tables/{tableCode}/pull-history` | 登录即可 | 获取单表拉取历史（最近 30 天，分页） |

**列表请求参数**：
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| group | String | 否 | 分组筛选：BASIC/MARKET/FINANCE/EVENT/INDEX |
| status | String | 否 | 状态筛选：NORMAL/DELAYED/ERROR/UPDATING |
| keyword | String | 否 | 搜索关键词（表名/表代码模糊匹配） |

**列表响应示例**：
```json
[
  {
    "tableCode": "daily_quote",
    "tableName": "日线行情",
    "tableGroup": "MARKET",
    "totalRows": 5234567,
    "rowDeltaPct": 0.32,
    "latestDate": "2026-07-21",
    "status": "NORMAL",
    "failedCount": 0,
    "checkItems": [
      {"name": "freshness", "displayName": "新鲜度检测", "passed": true, "message": "通过，最新数据 2026-07-21"},
      {"name": "price_logic", "displayName": "价格逻辑检测", "passed": true, "message": "通过，最近30天无异常"},
      {"name": "row_delta", "displayName": "数据量变动检测", "passed": true, "message": "通过，较上次 +0.32%"}
    ],
    "lastCheckTime": "2026-07-22T09:15:00",
    "lastUpdateTime": "2026-07-22T06:30:00"
  }
]
```

**列表页展示说明**：
- 异常摘要从 `checkItems` 中取前 2 个不通过项的 `displayName + ": " + message` 拼接
- `failedCount` 用于列表页快速显示"几项不通过"的徽标，不用前端再数一遍

---

### 6.3 操作接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/data-governance/tables/{tableCode}/incremental-update` | 管理员 | 触发增量更新，返回 taskId |
| POST | `/api/data-governance/tables/{tableCode}/full-rebuild` | 管理员 | 触发全量重建，返回 taskId |
| POST | `/api/data-governance/check/all` | 管理员 | 手动触发全表质量检测（25张表全部重跑），返回 batchId |
| POST | `/api/data-governance/check/{tableCode}` | 管理员 | 手动触发单表质量检测，立即返回结果 |
| GET | `/api/data-governance/tasks/{taskId}/progress` | 登录即可 | 查询任务进度（从内存缓存读取，轮询用） |
| POST | `/api/data-governance/tasks/{taskId}/cancel` | 管理员 | 取消任务（协作式取消，不保证立即停止） |

**增量/全量更新响应**：
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "tableCode": "daily_quote",
  "operationType": "MANUAL_INCREMENTAL",
  "status": "RUNNING"
}
```

**进度查询响应**：
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "tableCode": "daily_quote",
  "progressPct": 45,
  "currentStep": "正在拉取 2024 年数据...",
  "processedItems": 2345000,
  "totalItems": 5200000,
  "isCancelled": false,
  "lastUpdated": "2026-07-22T10:25:30"
}
```

**进度查询说明**：
- 进度数据存在**内存缓存**（Caffeine）中，key = taskId
- 任务执行完成后，缓存保留 30 分钟后自动清理
- 任务完成后最终状态以 `data_pull_log` 表为准

**取消任务说明**：
- 采用**协作式取消**：设置内存缓存中的 `isCancelled = true` 标记
- 任务每处理完一批数据（通常 1000 条）检查一次取消标记
- 标记为 true 则主动退出，更新状态为 `CANCELLED`
- 已写入的数据保留（不回滚）

**全表检测响应**：
```json
{
  "batchId": "check_20260722_220000",
  "totalTables": 25,
  "status": "RUNNING"
}
```

---

### 6.4 日志接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/data-governance/logs` | 登录即可 | 拉取日志列表（分页） |
| GET | `/api/data-governance/logs/{logId}` | 登录即可 | 单条日志详情（error_stack 仅管理员可见） |

**列表请求参数**：
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| tableCode | String | 否 | 按表筛选 |
| status | String | 否 | 按状态筛选：RUNNING/SUCCESS/FAILED/CANCELLED |
| operationType | String | 否 | 按操作类型筛选 |
| startDate | String | 否 | 开始日期 |
| endDate | String | 否 | 结束日期 |
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页大小，默认 20 |

**安全说明**：
- `error_stack` 字段对普通用户脱敏（只返回 error_message）
- 管理员可查看完整 error_stack（但仍会脱敏 Token/密码等敏感信息）

---

### 6.5 数据源接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/data-governance/datasource` | 登录即可 | 获取数据源连通性状态（只读，不暴露 Token） |
| POST | `/api/data-governance/datasource/test` | 管理员 | 手动触发连通性测试 |

> **注意**：Tushare Token 从 `application.yml` 读取，**不允许在页面上修改**。如需修改 Token，需改配置文件后重启服务。

**GET 响应示例**：
```json
{
  "sourceCode": "TUSHARE",
  "sourceName": "Tushare Pro",
  "status": "ACTIVE",
  "lastTestTime": "2026-07-22T09:00:00",
  "lastTestOk": true,
  "responseTimeMs": 120,
  "testInterface": "trade_cal（交易日历）"
}
```

**连通性测试机制**：
- 调用 tushare 的 `trade_cal` 接口（一个轻量接口，消耗积分少），判断是否能正常返回数据
- 定时任务**每小时自动测试一次**，结果存在内存缓存中
- 页面展示最新测试时间、是否成功、响应耗时
- 管理员可手动点击「测试连通性」立即重测

---

## 7. 数据写入性能优化策略（核心）

> **核心原则**：大数据表绝不能一条条 insert。批量写入、索引后置、事务分批是三大法宝。

### 7.1 写入策略总览

| 策略 | 适用场景 | 预计性能提升 |
|---|---|---|
| **批量写入 + 分批提交** | 所有表 | 5-10 倍 |
| **全量重建：先删索引后建索引** | 全量重建大表 | 3-5 倍 |
| **UPSERT 代替先删后插** | 增量更新 | 2-3 倍 |
| **临时表 + 原子交换** | 全量重建（可选） | 0 停机时间 |
| **多线程拉取 + 串行写入** | 所有表 | 2-3 倍（拉取侧） |

### 7.2 策略详解

#### 策略一：批量写入 + 分批提交

**做法**：
- 从 tushare 拉取的数据先放到内存缓冲区
- 攒够 1000 条（可配置，根据单行大小调整：宽表 500，窄表 2000）后，调用 MyBatis-Plus 的 `saveBatch` 批量插入
- 每批一个独立事务，提交完了再拉下一批

**为什么快**：
- 减少数据库 round-trip：1000 条数据 1 次网络交互 vs 1000 次
- 减少事务开销：1 个事务 vs 1000 个事务
- MySQL 端批量插入有优化

**关键配置**：
- JDBC URL 加 `rewriteBatchedStatements=true`（MySQL 批量写入必加）
- 批次大小：行情类窄表 1000-2000 条/批，财务类宽表 500-1000 条/批

#### 策略二：全量重建——先删索引后建索引

**做法**：
1. 全量重建开始前，先 DROP 掉所有二级索引（保留主键）
2. 数据批量写入完成后，再 CREATE INDEX 一次性建回来

**为什么快**：
- 插入数据时，数据库需要维护每一个索引（B+ 树分裂、重平衡）
- 索引越多，插入越慢；5 个二级索引可能让插入慢 3-5 倍
- 批量建索引比逐条维护快得多（排序后批量构建 B+ 树）

**适用表**：
- 百万级以上的大表（daily_quote, adj_factor, daily_basic, moneyflow 等）
- 小表（几千几万条）不用搞，直接插就行

**注意**：
- 建索引期间表不可用（读可以，写不行），但全量重建本来就是清空重写，无所谓
- 要在事务外建索引（避免长事务）

#### 策略三：增量更新——UPSERT 代替先删后插

**做法**：
- 增量更新不用"先 DELETE 同一天的数据，再 INSERT"
- 用 `INSERT ... ON DUPLICATE KEY UPDATE`（MySQL）或 `INSERT OR REPLACE`（SQLite，开发环境兼容）
- MyBatis-Plus 可通过自定义 mapper 实现批量 UPSERT

> **SQLite 兼容说明**：SQLite 的 `INSERT OR REPLACE` 是先删后插语义，与 MySQL 的 ON DUPLICATE KEY UPDATE 不同（会丢失未在 SQL 中列出的字段）。开发环境用 SQLite 时功能可用但性能稍差，生产环境用 MySQL 享受真正的 UPSERT 性能。

**为什么快**：
- 先删后插 = 1 次 DELETE + 1 次 INSERT = 2 次 IO
- UPSERT = 1 次操作 = 1 次 IO
- 还减少了事务日志（redo/undo）的写入量

#### 策略四：临时表 + 原子交换（可选，追求零停机）

**做法**：
1. 全量重建时，不直接操作正式表，而是写入 `daily_quote_temp`
2. 数据全部写完、索引建好、检测通过后，执行：
   ```sql
   RENAME TABLE daily_quote TO daily_quote_old, daily_quote_temp TO daily_quote;
   ```
3. 确认没问题后，DROP 旧表

**优点**：
- 重建期间正式表完全可用（读不受影响）
- 失败了随时可以放弃，不影响线上
- 切换是原子操作（RENAME 是原子的），0  downtime

**缺点**：
- 需要两倍磁盘空间（新旧两份数据都在）
- 实现稍复杂一点

**适用场景**：
- 核心大表（daily_quote 这种）的全量重建
- 业务高峰期不能停的场景

> **SQLite 兼容说明**：临时表 + RENAME 策略仅在 MySQL 下启用（支持原子 RENAME）。SQLite 开发环境下降级为直接删表重建。

#### 策略五：多线程拉取 + 串行写入

**做法**：
- 生产者-消费者模式
- **生产者**（多线程，IO 密集型）：并发调用 tushare 接口拉取数据，每只股票/每一天一个任务，结果放到阻塞队列
- **消费者**（单线程或按表并行）：从队列取数据，攒批后批量写入数据库

**为什么快**：
- tushare 调用是网络 IO，等待时间长，多线程并发能把 CPU 和网络打满
- 数据库写入如果多线程并发会有锁竞争（尤其是 insert 到同一张表），单线程批量写反而更快
- 解耦了拉取和写入，两边各自最优

**并发控制**：
- 控制 tushare 并发数（如 5-10 个线程），避免触发 tushare 的频率限制
- 队列有界（如容量 100），防止内存爆掉

### 7.3 不同表的性能策略选择

| 表 | 数据量级估算 | 推荐策略 |
|---|---|---|
| daily_quote | 500 万 + | 批量写入 + 先删索引 + 多线程拉取 + 临时表交换 |
| adj_factor | 300 万 + | 批量写入 + 先删索引 + 多线程拉取 |
| daily_basic | 300 万 + | 批量写入 + 先删索引 + 多线程拉取 |
| moneyflow | 200 万 + | 批量写入 + 先删索引 + 多线程拉取 |
| income/balancesheet/cashflow | 10-50 万 | 批量写入 + 多线程拉取 |
| stock_basic | 5000+ | 直接批量插，不用搞索引优化 |
| trade_cal | 几千条 | 随便插 |
| 其他小表 | 几万 ~ 几十万 | 批量写入即可 |

### 7.4 进度估算

全量重建前，先给用户一个大致的时间预估，避免用户焦虑：

| 表 | 预估数据量 | 预估耗时（参考） |
|---|---|---|
| daily_quote | 5000 只股票 × 10 年 ≈ 1200 万条 | 30-60 分钟 |
| adj_factor | 同 daily_quote | 20-40 分钟 |
| daily_basic | 同 daily_quote | 20-40 分钟 |
| 财务三表 | 5000 只 × 40 季度 × 3 表 ≈ 60 万条 | 5-15 分钟 |
| 其他小表 | —— | 1-5 分钟 |

> 实际耗时取决于网络速度、tushare 响应速度、数据库性能。给的是估算范围，不是精确值。

---

## 8. 与现有模块的关系

### 8.1 复用现有能力

| 现有模块 | 复用内容 |
|---|---|
| `DataInitService` | 数据初始化的核心逻辑，需要重构以支持性能优化策略 |
| `DataInitController` | 现有 `/tushare/data-init` 接口，迁移到新的 data-governance 路径下 |
| `InitStep` 枚举 | 数据表定义，补充分组、更新频率等元信息 |
| `TushareClient` | tushare 调用客户端 |

### 8.2 改造点

1. **DataInitService 深度重构**：
   - 支持单表增量更新（UPSERT 写入）
   - 支持全量重建的性能优化（批量写入、索引后置、临时表可选）
   - 写入 data_pull_log 日志
   - 更新 data_governance_metric 指标
   - 拉取完成后自动执行质量检测（调用 DataGovernanceService.checkTable）
   - 进度回调更细粒度（按天 / 按批次进度）

2. **InitStep 枚举增强**：
   - 增加 `group` 字段（基础数据/行情数据/财务数据/参考数据）
   - 增加 `updateFrequency` 字段（每日/每周/每月/不定时）
   - 增加 `expectedUpdateTime` 字段（预期更新时间，如 "17:00"）

3. **定时任务改造**：
   - 现有定时拉取任务执行完成后，写入日志表和指标表
   - 失败时更新状态为 ERROR，记录错误信息
   - 完成后自动触发质量检测

4. **新增 DataCheckable 接口体系**：
   - 定义 `DataCheckable` 接口（见 §4.1），每张业务表的 Service 实现这个接口
   - `DataGovernanceService` 自动发现所有 `DataCheckable` Bean，按 `tableCode` 建索引
   - 新增一张表的校验 = 对应 Service 实现 `checkData()` 方法，框架代码不用改
   - 校验逻辑写在 Service 里，想校验什么、message 怎么写，全自己定

---

## 9. 权限控制

| 功能 | 普通用户 | 管理员 |
|---|---|---|
| 查看数据总览 | ✅ | ✅ |
| 查看数据表详情 + 检测结果 | ✅ | ✅ |
| 查看拉取日志 | ✅ | ✅ |
| 查看数据源连通性状态 | ✅ | ✅ |
| 手动触发检测（单表/全表） | ❌ | ✅ |
| 手动测试数据源连通性 | ❌ | ✅ |
| 增量更新 | ❌ | ✅ |
| 全量重建 | ❌ | ✅ |
| 取消任务 | ❌ | ✅ |
| 查看完整错误堆栈（error_stack） | ❌ | ✅ |

- 所有写操作（更新/重建/取消/手动检测）都需要 ADMIN 角色
- 读操作所有登录用户都可以访问
- error_stack 详情仅管理员可见

---

## 10. 技术要点

### 10.1 前端

- 技术栈：Thymeleaf + Bootstrap 5（与现有页面保持一致）
- 进度条：轮询（每秒轮询一次进度接口），简单可靠
- 详情抽屉：Bootstrap Offcanvas 组件
- 图标：Bootstrap Icons（与现有页面一致）

### 10.2 后端

- 与现有 Spring Boot 架构一致
- 异步任务：沿用 `DataInitService` 的 CompletableFuture + 虚拟线程模式
- **进度追踪**：任务进度写入**内存缓存**（Caffeine / ConcurrentHashMap），key = taskId，任务完成后保留 30 分钟自动清理
- **质量检测**：每晚 22:00 定时全表检测；数据拉取完成后也自动触发单表检测；支持手动单表/全表重跑
- 检测框架：`DataCheckable` 接口，每张表的 Service 自己实现 `checkData()`，返回 `level + message`
- **数据源连通性**：每小时自动测试一次 tushare 连通性（调用 trade_cal 轻量接口），结果存内存缓存
- **定时任务列表**：
  - `DataGovernanceCheckJob`：每天 22:00 执行全表质量检测
  - `DataSourceHealthJob`：每小时执行一次数据源连通性测试
  - `MetricCleanupJob`：每天凌晨清理 3 个月前的 metric 旧数据和 pull_log 旧数据

### 10.3 性能考虑

- 列表查询走 `data_governance_metric` 表的索引，极快（只有 25 行数据）
- 检测结果里的明细（如具体哪些天、哪些股票出问题）按需懒加载，不展开不查
- **质量检测的时间窗口策略**：价格逻辑、值域检测等优先查最近 30 天数据（量小、价值高），不做全表扫描；小表（< 10 万条）可全表扫描
- 全量重建的索引操作放在独立线程，不阻塞其他任务
- count 操作：小表现场 count，大表用 `data_governance_metric` 里缓存的 total_rows（每次拉取完成后更新）
- 进度更新：每 1000 条/每 3 秒更新一次进度（取频率低的那个），避免频繁写数据库
- 跨表校验：优先走被驱动表的索引，必要时 force index

---

## 11. 分阶段实施建议

> **原则**：Phase 1 只做"能用"，不追求性能极致；性能优化留到 Phase 2，等核心逻辑稳定了再做。

### Phase 1（MVP · 核心闭环）

**目标**：总览能看、列表能查、单表能更、检测能跑——核心链路打通。

| 模块 | 具体内容 |
|---|---|
| 总览 & 列表 | 总览卡片（3 张）、数据表卡片列表、分组筛选、状态筛选、搜索 |
| 详情面板 | 基本信息 Tab、检测结果 Tab、更新历史 Tab |
| 检测框架 | DataCheckable 接口 + DataGovernanceService 调度 + 空表兜底 + 数据骤减检测 |
| 定时检测 | 每晚 22:00 自动执行全表检测，写入 metric 表 |
| 手动检测 | 支持「全表重跑」和「单表重跑」 |
| 核心表检测 | 5 张核心表的检测项：daily_quote、stock_basic、trade_cal、income、index_daily |
| 数据操作 | 增量更新（批量写入 + UPSERT）、全量重建（直接删+插，先不搞索引优化） |
| 进度展示 | 内存缓存进度 + 轮询进度接口 + 前端进度条 |
| 拉取日志 | 日志列表（分页 + 筛选）、日志详情（error_stack 仅管理员） |
| 数据源 | 只读连通性展示（每小时自动测试 + 手动测试），不允许改 Token |
| 权限控制 | 写操作 @RequireAdmin、读操作登录即可、error_stack 分级可见 |
| 数据清理 | 每天凌晨清理 3 个月前的 metric 和 pull_log 旧数据 |

### Phase 2（性能 & 补全）

**目标**：25 张表全覆盖，性能优化，细节打磨。

| 模块 | 具体内容 |
|---|---|
| 检测项补全 | 其余 20 张表的检测项全部实现 |
| 写入性能优化 | 全量重建先删索引后建索引、多线程拉取 + 串行写入 |
| 进度优化 | 进度更新节流（每 3 秒/每 1000 条更新一次） |
| 表格视图 | 卡片视图 / 表格视图切换 |
| 历史趋势 | 详情页展示最近 30 天数据量变化趋势折线图（用 metric 表历史数据） |
| 异常告警 | 检测出 ERROR 时站内消息通知管理员 |

### Phase 3（进阶 & 高可用）

**目标**：零停机、自动化、更智能。

| 模块 | 具体内容 |
|---|---|
| 零停机重建 | 临时表 + RENAME 原子交换（0 downtime 全量重建） |
| 告警升级 | 邮件通知、连续失败自动重试 |
| 数据导出 / 备份 | 单表导出 CSV、整库备份入口 |
| 智能修复 | 常见问题自动修复（如缺最近几天数据自动触发增量更新） |
| 质量评分 | 可选的 0-100 分数据质量评分（按检测项加权） |

---

## 12. 验收标准

### Phase 1 功能验收

- [ ] 总览卡片正确显示 3 项指标（接入表数 / 今日已更新 / 异常表数）
- [ ] 数据表列表展示所有 25 张表，状态灯颜色正确（绿/黄/红）
- [ ] 分组筛选、状态筛选、关键词搜索功能正常
- [ ] 单表详情 3 个 Tab（基本信息 / 检测结果 / 更新历史）正常显示
- [ ] 检测结果展示清晰，全部检测项（含通过的）都展示，不通过的标红/标黄并显示具体 message
- [ ] 检测项含名称、状态、级别（ERROR/WARN）、描述，底部有汇总统计（X项通过/Y项错误/Z项警告）
- [ ] 不通过的检测项可展开查看详情数据
- [ ] **每晚 22:00 定时执行全表检测**，结果正确写入 metric 表
- [ ] 支持「单表重跑」检测，结果立即返回并更新列表状态
- [ ] 支持「全表重跑」检测，返回 batchId
- [ ] **数据骤减检测**：人为将某表数据删除一半，下一次检测自动触发 WARN
- [ ] 增量更新操作能正确执行，进度实时展示（轮询 < 2 秒延迟）
- [ ] 全量重建有二次确认（输入表名 + 倒计时），能正确执行
- [ ] 任务进度从内存缓存读取，任务完成 30 分钟后缓存自动清理
- [ ] 拉取日志列表分页正确，支持按表名/状态/时间筛选
- [ ] 日志详情中 error_stack 普通用户不可见，管理员可见
- [ ] **数据源页面展示连通性状态**（最新测试时间、是否成功、响应耗时），每小时自动刷新
- [ ] 所有写操作接口有 @RequireAdmin 注解
- [ ] 并发控制有效：同一时间只能有一个数据任务在运行
- [ ] metric 表和 pull_log 表自动清理 3 个月前的旧数据

### Phase 1 性能验收

- [ ] 页面首次加载时间 < 2 秒
- [ ] 数据表列表查询 < 200ms（取最新批次，只有 25 行）
- [ ] 单表检测执行时间 < 5 秒（核心表）
- [ ] 全表 25 张检测总耗时 < 2 分钟
- [ ] 增量更新写入速度 ≥ 3000 条/秒（daily_quote 级别，批量 UPSERT）
- [ ] 全量重建写入速度 ≥ 5000 条/秒（批量 INSERT，暂不做索引优化）
- [ ] 操作反馈及时：按钮点击后 200ms 内出现 loading 状态

### 安全验收

- [ ] Tushare Token 仅从配置文件读取，**任何接口都不返回 Token**
- [ ] 错误堆栈自动脱敏（Token、密码、数据库连接串替换为 ***）
- [ ] 全量重建二次确认机制有效（必须输入正确表名 + 倒计时结束）
- [ ] 所有写操作记录操作人到 data_pull_log
- [ ] 取消任务接口仅管理员可调用
- [ ] 数据源页面不展示 Token，只展示连通性状态

### 数据准确性验收

- [ ] 5 张核心表的检测项，人为制造 5 种常见问题（空表、缺数据、价格异常、跨表不一致、关键字段为空），检测能 100% 发现
- [ ] 状态判定逻辑正确：ERROR > DELAYED > NORMAL 优先级符合预期
- [ ] 延迟判定正确：非交易日不判定延迟，交易日过了预期更新时间但数据没到才判定延迟
- [ ] **数据骤减检测正确**：人为删除 40% 数据后，下次检测自动追加 WARN 级"数据量骤减"检测项
- [ ] 增量更新后，随机抽样 100 条数据与 tushare 官方接口返回一致
- [ ] 全量重建后，随机抽样 100 条数据与 tushare 官方接口返回一致
