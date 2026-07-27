# Tushare 知识库改造计划

> **面向 AI 与开发者**：本计划描述了对 stock-watcher 业务知识库中 Tushare 相关文档的改造方案，使其与当前代码实现（数据管控中心模块）保持一致。

---

## 一、现状分析

### 1.1 现有知识库文件

| 文件 | 内容 | 当前状态 |
|------|------|---------|
| `01-auth.md` | 认证相关 | 不涉及 Tushare，无需修改 |
| `02-tushare-integration-guide.md` | Tushare 接口对接 11 步指南 | **需大幅更新** |
| `03-tushare-interface-summary.md` | Tushare 接口现状与规划（已对接 13 个） | **需大幅更新** |
| `04-tushare 接口汇总.md` | Tushare 官网接口汇总（纯参考） | 需重命名优化 |

### 1.2 实际代码实现（已落地）

**已对接接口数量：25 个**（InitStep 枚举），分布在 5 大分组：

| 分组 | 数量 | 代表接口 |
|------|------|---------|
| BASIC（基础数据） | 2 | stock_basic、trade_cal |
| MARKET（行情数据） | 6 | daily、adj_factor、stk_limit、daily_basic、moneyflow、index_daily |
| FINANCE（财务数据） | 6 | income、balancesheet、cashflow、forecast、express、fina_indicator |
| EVENT（事件数据） | 7 | dividend、namechange、suspend_d、top_list、top_inst、block_trade |
| INDEX（指数与市场） | 4 | index_weight、sw_industry、hk_hold、margin、margin_detail |

**数据管控中心模块（DataGovernance）核心能力：**

1. **数据质量校验体系**
   - `DataCheckable` 接口：26 个 Service 全部实现
   - 统一检测入口：`DataGovernanceService.checkTable()` / `checkAll()`
   - 检测项支持 ERROR / WARN / INFO 三级
   - 空表检测、行数变动检测、延迟检测

2. **统一数据更新入口**
   - `DataInitService.incrementalUpdate()` - 增量更新
   - `DataInitService.fullRebuild()` - 全量重建
   - 异步执行 + 任务进度追踪（`TaskProgressCache`）

3. **数据拉取日志**
   - `data_pull_log` 表记录每次拉取详情
   - 支持按表、状态、操作类型、时间范围查询
   - 记录 total_count / success_count / fail_count

4. **定时任务**
   - `DataGovernanceCheckJob`：每日 22:00 全表质量检测
   - 各专项定时任务（DailyUpdateTask 等）

5. **数据源健康检查**
   - `DataSourceHealthCache`：Tushare 连通性监控
   - 支持手动测试连通性

6. **InitStep 丰富元数据**
   - `tableGroup`：表分组
   - `updateFrequency`：更新频率描述
   - `expectedUpdateTime`：期望更新时间
   - `isDaily`：是否为日线表
   - `tushareApi`：对应 Tushare 接口名

### 1.3 知识库与实现的差距

| 差距项 | 知识库现状 | 实际实现 |
|--------|-----------|---------|
| 已对接接口数 | 13 个 | 25 个 |
| 数据管控中心 | 完全未提及 | 完整模块已落地 |
| DataCheckable 校验体系 | 无 | 26 个 Service 全部接入 |
| 统一更新入口 | 分散在各 Controller | DataInitService 统一入口 |
| 数据拉取日志 | 无 | data_pull_log 完整记录 |
| 数据源健康检查 | 无 | DataSourceHealthCache |
| 任务进度追踪 | 无 | TaskProgressCache |
| InitStep 元数据 | 仅 code/label/tableName | 含分组/频率/时间/是否日线/对应API |
| 表分组概念 | 无 | 5 大类（BASIC/MARKET/FINANCE/EVENT/INDEX） |

---

## 二、改造方案

### 2.1 总体思路

1. **新增一篇数据管控中心专题文档**（05-data-governance-center.md）
2. **更新 02 对接指南**：从 11 步扩展到 13 步，加入数据治理接入
3. **更新 03 接口汇总**：更新接口数量、架构描述、分组展示
4. **重命名 04 文件**：去掉空格，统一命名风格

### 2.2 具体改造内容

#### 任务一：新增 `05-data-governance-center.md`

**内容结构：**

```
# 数据管控中心（Data Governance Center）

> 面向 AI：本文档介绍 stock-watcher 的数据管控中心模块——数据质量校验、统一更新入口、拉取日志、定时任务、数据源健康检查。

## 1. 模块定位与架构图

## 2. 核心概念
   - InitStep 与表元数据（分组/更新频率/是否日线/对应API）
   - TableGroup 五大分组
   - 表状态（NORMAL/DELAYED/ERROR/UPDATING）
   - 检测级别（ERROR/WARN/INFO）

## 3. 核心组件
   3.1 DataCheckable 接口
   3.2 DataGovernanceService（校验服务）
   3.3 DataInitService（统一更新入口：增量/全量）
   3.4 TaskProgressCache（任务进度缓存）
   3.5 DataPullLog（拉取日志）
   3.6 DataSourceHealthCache（数据源健康）

## 4. API 地图（DataGovernanceController）
   - Overview 概览
   - Tables 表状态/详情/历史
   - Update 增量更新/全量重建
   - Check 单表/全表检测
   - Task 进度查询/取消
   - Logs 拉取日志
   - Datasource 数据源状态/测试

## 5. 数据校验体系
   5.1 通用检测项（空表/行数变动/延迟）
   5.2 各表自定义检测项
   5.3 检测结果存储（data_governance_metric 表）

## 6. 增量与全量更新机制
   6.1 增量更新流程
   6.2 全量重建流程
   6.3 并发控制（任务锁）
   6.4 取消机制

## 7. 定时任务体系
   - DataGovernanceCheckJob（每日22:00检测）
   - DailyUpdateTask（每日行情更新）
   - 各专项任务

## 8. 对接新表的 Checklist（数据治理部分）

## 9. 相关分册
   - 对接新接口完整步骤 → 02-tushare-integration-guide.md
   - 接口清单 → 03-tushare-interface-summary.md
```

---

#### 任务二：更新 `02-tushare-integration-guide.md`

**主要改动：**

1. **步骤从 11 步扩展到 13 步**
   - 原 Step 8（接入初始化流程）→ 拆分为「Step 8: InitStep 注册」+「Step 9: DataInitService 接入」
   - 新增 「Step 10: 接入 DataCheckable 数据校验」
   - 原 Step 9（接入定时任务）→ 顺延为 Step 11
   - 原 Step 10/11 → 顺延为 Step 12/13

2. **更新 InitStep 注册部分**
   - 补充新字段说明：`tableGroup` / `updateFrequency` / `expectedUpdateTime` / `isDaily` / `tushareApi`

3. **更新 Service 层模板**
   - 类签名改为 `implements XxxService, DataCheckable`
   - 新增 `checkData()` 和 `getTableCode()` 方法模板
   - 新增通用检测项代码示例

4. **新增 DataInitService 接入章节**
   - 在 `DataInitServiceImpl` 中注册增量/全量更新逻辑
   - 说明 switch case 新增方式

5. **更新 Checklist**
   - 加入 DataCheckable 实现、InitStep 新字段、DataInitService 注册等检查项

6. **更新常见问题排查**
   - 加入数据治理相关的常见问题

---

#### 任务三：更新 `03-tushare-interface-summary.md`

**主要改动：**

1. **更新架构描述**
   - 新增「数据管控中心层」描述
   - 新增核心组件列表

2. **更新已对接接口清单**
   - 从 13 个更新为 25 个
   - 按 TableGroup 分组展示
   - 每个接口补充：对应表名、更新频率、是否日线

3. **更新「现有对接架构」章节**
   - 新增：数据治理层、统一更新层、日志与监控层

4. **更新未对接接口优先级**
   - 重新评估剩余接口的优先级
   - 调整规划建议

5. **下一步建议**
   - 从「对接更多接口」调整为「深化数据治理 + 接口覆盖」并重

---

#### 任务四：重命名并轻量更新 `04-tushare 接口汇总.md`

**改动：**
1. 重命名为 `04-tushare-api-reference.md`（去掉空格，统一命名风格）
2. 开头补充说明：本文为 Tushare 官方接口参考索引，具体对接以 02/03 为准
3. 内容保持不变（纯参考性质）

---

## 三、文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| **新增** | `business/05-data-governance-center.md` | 数据管控中心专题文档 |
| **修改** | `business/02-tushare-integration-guide.md` | 从 11 步扩展到 13 步，加入数据治理接入 |
| **修改** | `business/03-tushare-interface-summary.md` | 更新接口数量、架构、分组展示 |
| **重命名** | `business/04-tushare 接口汇总.md` → `business/04-tushare-api-reference.md` | 统一命名，补充说明 |

---

## 四、改造顺序与依赖

```
1. 先更新 03（接口现状） → 建立全局认知
         ↓
2. 新增 05（数据管控中心） → 完整介绍新模块
         ↓
3. 更新 02（对接指南） → 将新模块融入标准流程
         ↓
4. 重命名 04 → 收尾整理
```

---

## 五、风险与注意事项

1. **保持向后兼容**：02 文档的原有核心步骤（DTO/Enum/Client/Mapper/Service/Controller）保持不变，仅新增数据治理相关步骤
2. **代码引用一致性**：所有文档中的类名、方法名、包路径必须与实际代码完全一致
3. **面向 AI 友好**：每个文档开头明确「面向 AI」的使用指引，提供可复制的代码模板
4. **交叉引用**：各文档之间通过「相关分册」互相链接，形成知识网络

---

## 六、验收标准

- [ ] 03 文档中已对接接口数量与 InitStep 枚举数量一致（25 个）
- [ ] 05 文档完整覆盖 DataGovernance 模块的所有核心能力
- [ ] 02 文档的 13 步流程完整可执行，包含数据治理接入
- [ ] 所有文档中的类名、方法名、包路径与实际代码一致
- [ ] 各文档之间的交叉引用正确无误
