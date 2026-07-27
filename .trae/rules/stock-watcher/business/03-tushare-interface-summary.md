
# Tushare 接口现状与数据管控中心

&gt; **面向 AI 和开发者**：本文档总结 stock-watcher 项目中 Tushare 接口的对接现状、已对接接口清单（按分组）、数据管控中心架构，以及后续规划。
&gt; **与 02/05 的关系**：02 是「怎么做」的操作指南，05 是「数据管控中心」的专题详解，本文是「做了什么」的全局地图。

---

## 一、整体架构

项目采用 **六层架构** 对接与管理 Tushare 数据：

```
┌─────────────────────────────────────────────────────────┐
│  ⑥ 数据管控中心层（Data Governance）                      │
│     DataGovernanceController / Service                   │
│     数据质量检测 · 状态监控 · 拉取日志 · 数据源健康         │
├─────────────────────────────────────────────────────────┤
│  ⑤ 统一更新入口层（DataInitService）                       │
│     增量更新 incrementalUpdate · 全量重建 fullRebuild     │
│     异步执行 · 任务进度追踪 · 并发控制                      │
├─────────────────────────────────────────────────────────┤
│  ④ 服务层（Service）                                      │
│     25 个 Service + 全部实现 DataCheckable 接口            │
│     增量拉取 · 批量保存 · 本地查询 · 数据校验                │
├─────────────────────────────────────────────────────────┤
│  ③ 数据模型层（Model / Mapper）                            │
│     DO 实体 · MyBatis Plus Mapper · XML SQL              │
│     支持 MySQL / SQLite 双数据库                           │
├─────────────────────────────────────────────────────────┤
│  ② 数据传输层（DTO / Client）                              │
│     TushareClient · RateLimiter · TushareApiEnum          │
│     DTO（请求/响应）· 滑动窗口限流                          │
├─────────────────────────────────────────────────────────┤
│  ① 配置层（Config）                                       │
│     TushareConfig · application.yml                       │
│     token · base-url · 各接口限流配置                      │
└─────────────────────────────────────────────────────────┘
```

### 核心组件速查

| 层 | 核心类/文件 | 职责 |
|---|------------|------|
| 配置 | `TushareConfig` | token、地址、限流规则 |
| 客户端 | `TushareClient` | 封装所有 Tushare API 调用 |
| 枚举 | `TushareApiEnum` | 所有已对接接口的 apiName + fields |
| 限流 | `RateLimiter` | 滑动窗口限流器 |
| 元数据 | `InitStep` | 25 张表的元数据（分组/频率/是否日线/对应API） |
| 分组 | `TableGroup` | 5 大分组枚举 |
| 校验接口 | `DataCheckable` | 数据校验统一接口 |
| 校验服务 | `DataGovernanceService` | 数据质量检测与状态查询 |
| 更新服务 | `DataInitService` | 增量更新 + 全量重建统一入口 |
| 进度缓存 | `TaskProgressCache` | 任务进度内存缓存（30分钟过期） |
| 拉取日志 | `DataPullLogMapper` | 数据拉取操作日志 |
| 健康检查 | `DataSourceHealthCache` | 数据源连通性监控 |
| 定时检测 | `DataGovernanceCheckJob` | 每日 22:00 全表质量检测 |

---

## 二、已对接接口清单（25 个，5 大分组）

### 2.1 分组概览

| 分组 | 数量 | 说明 | 代表接口 |
|------|------|------|---------|
| BASIC（基础数据） | 2 | 基础参考数据 | stock_basic、trade_cal |
| MARKET（行情数据） | 5 | 行情与交易数据 | daily、adj_factor、stk_limit、daily_basic、moneyflow |
| FINANCE（财务数据） | 6 | 财务报表与指标 | income、balancesheet、cashflow、fina_indicator、forecast、express |
| EVENT（事件数据） | 6 | 事件驱动类数据 | dividend、namechange、suspend_d、top_list、top_inst、block_trade |
| INDEX（指数与市场） | 6 | 指数、板块、互联互通 | index_weight、sw_industry、hk_hold、margin、margin_detail、index_daily |
| **合计** | **25** | | |

---

### 2.2 各分组明细

#### BASIC · 基础数据（2 个）

| 序号 | InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线表 |
|------|--------------|-------------|------|--------|---------|--------|
| 1 | `stock_basic` | `stock_basic` | `stock_basic` | 股票基础信息 | 每日 16:00 | 否 |
| 2 | `trade_cal` | `trade_cal` | `trade_cal` | 交易日历 | 每日 16:00 | 否 |

---

#### MARKET · 行情数据（5 个）

| 序号 | InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线表 |
|------|--------------|-------------|------|--------|---------|--------|
| 3 | `daily` | `daily` | `daily_quote` | 日线行情 | 每个交易日 16:00 | 是 |
| 4 | `adj_factor` | `adj_factor` | `adj_factor` | 复权因子 | 每个交易日 16:00 | 是 |
| 5 | `stk_limit` | `stk_limit` | `stock_stk_limit` | 涨跌停价 | 每个交易日 16:40 | 是 |
| 6 | `daily_basic` | `daily_basic` | `daily_basic` | 每日基本面/估值 | 每个交易日 16:10 | 是 |
| 7 | `moneyflow` | `moneyflow` | `stock_moneyflow` | 个股资金流向 | 每个交易日 16:10 | 是 |

---

#### FINANCE · 财务数据（6 个）

| 序号 | InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线表 |
|------|--------------|-------------|------|--------|---------|--------|
| 8 | `fina_indicator` | `fina_indicator` | `fina_indicator` | 财务指标 | 每周日 17:00 | 否 |
| 9 | `income` | `income` | `income` | 利润表 | 每周日 17:30 | 否 |
| 10 | `balancesheet` | `balancesheet` | `balancesheet` | 资产负债表 | 每周日 18:00 | 否 |
| 11 | `cashflow` | `cashflow` | `cashflow` | 现金流量表 | 每周日 18:30 | 否 |
| 12 | `forecast` | `forecast` | `forecast` | 业绩预告 | 每周日 19:00 | 否 |
| 13 | `express` | `express` | `express` | 业绩快报 | 每周日 19:30 | 否 |

---

#### EVENT · 事件数据（6 个）

| 序号 | InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线表 |
|------|--------------|-------------|------|--------|---------|--------|
| 14 | `dividend` | `dividend` | `dividend` | 分红送股 | 每日 16:00 | 否 |
| 15 | `namechange` | `namechange` | `stock_namechange` | 股票更名历史(ST) | 每日 16:30 | 否 |
| 16 | `suspend_d` | `suspend_d` | `stock_suspend_d` | 停复牌信息 | 每日 16:35 | 否 |
| 17 | `top_list` | `top_list` | `top_list` | 龙虎榜-每日榜单 | 每个交易日 16:10 | 是 |
| 18 | `top_inst` | `top_inst` | `top_inst` | 龙虎榜-机构席位 | 每个交易日 16:10 | 是 |
| 19 | `block_trade` | `block_trade` | `block_trade` | 大宗交易 | 每个交易日 16:10 | 是 |

---

#### INDEX · 指数与市场（6 个）

| 序号 | InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线表 |
|------|--------------|-------------|------|--------|---------|--------|
| 20 | `index_weight` | `index_weight` | `index_weight` | 指数成分权重 | 每日 20:00 | 否 |
| 21 | `sw_industry` | `index_classify` | `sw_industry` | 申万行业分类 | 每半年 | 否 |
| 22 | `hk_hold` | `hk_hold` | `hk_hold` | 沪深港通持股 | 每个交易日 16:10 (T+1) | 是 |
| 23 | `margin` | `margin` | `margin` | 融资融券-汇总 | 每个交易日 16:10 | 是 |
| 24 | `margin_detail` | `margin_detail` | `margin_detail` | 融资融券-明细 | 每个交易日 16:10 | 是 |
| 25 | `index_daily` | `index_daily` | `index_daily` | 指数日线 | 每个交易日 16:30 | 是 |

---

## 三、数据管控中心核心能力

&gt; **详细专题** → 见 [05-data-governance-center.md](./05-data-governance-center.md)

### 3.1 数据质量校验

- **统一接口**：所有 25 个 Service 全部实现 `DataCheckable` 接口
- **三级检测项**：ERROR（错误）/ WARN（警告）/ INFO（提示）
- **通用检测**：空表检测、行数变动检测、数据延迟检测
- **自定义检测**：各表可根据业务特点添加专属校验项
- **检测结果存储**：`data_governance_metric` 表，保留 3 个月

### 3.2 统一更新入口

- **增量更新** (`incrementalUpdate`)：从最新数据日期的下一天开始拉取
- **全量重建** (`fullRebuild`)：清空表后从头拉取全部历史数据
- **异步执行**：虚拟线程池，不阻塞主线程
- **任务进度**：内存缓存实时进度，前端可轮询
- **并发控制**：全局任务锁，同一时间只能有一个更新任务

### 3.3 拉取日志

- 每次数据拉取（定时/手动增量/手动全量）都记录日志
- 字段：taskId、表名、操作类型、状态、耗时、总数/成功数/失败数、操作人、错误信息
- 支持按表、状态、操作类型、时间范围分页查询

### 3.4 定时任务

| 任务 | 触发时间 | 职责 |
|------|---------|------|
| `DataGovernanceCheckJob` | 每日 22:00 | 全表数据质量检测 |
| `DailyUpdateTask` | 每日盘后 | 日线类数据批量更新 |
| 各专项任务 | 按各自频率 | 财务、事件、指数等专项更新 |

### 3.5 数据源健康检查

- 缓存 Tushare 连通性状态与响应时间
- 支持手动触发测试（调用 trade_cal 接口）
- 前端可实时查看数据源状态

---

## 四、未对接重要接口与优先级

### 2000积分可对接（剩余高价值）

| 优先级 | 接口名 | 积分 | 说明 | 应用场景 |
|--------|--------|------|------|----------|
| 🟡 P1 | `weekly` | 2000 | 周线行情 | 中长期策略 |
| 🟡 P1 | `monthly` | 2000 | 月线行情 | 长期趋势分析 |
| 🟡 P1 | `stk_holdernumber` | 2000 | 股东人数 | 筹码集中度分析 |
| 🟢 P2 | `fina_audit` | 2000 | 财务审计意见 | 风险评估 |
| 🟢 P2 | `fina_mainbz` | 2000 | 主营业务构成 | 行业分析、选股 |
| 🟢 P2 | `disclosure_date` | 2000 | 财报披露计划 | 事件驱动 |
| 🟢 P2 | `share_float` | 3000 | 限售股解禁 | 事件驱动 |
| 🟢 P2 | `stk_holdertrade` | 2000 | 股东增减持 | 事件驱动 |

### 5000积分可对接（特色数据）

| 优先级 | 接口名 | 积分 | 说明 |
|--------|--------|------|------|
| 🟢 P2 | `pro_bar` | 2000 | 复权行情（统一接口） |
| 🔵 P3 | `fund_portfolio` | 5000 | 公募基金持仓 | 机构持仓分析 |
| 🔵 P3 | `index_dailybasic` | 4000 | 大盘指数每日指标 | 市场估值 |

---

## 五、新增接口对接步骤

如需对接新的 Tushare 接口，请参考 **[02-tushare-integration-guide.md](./02-tushare-integration-guide.md)** 中的 13 步操作指南。

### 快速回顾

```
① 定义 DTO (XxxDTO + XxxQueryDTO)
    ↓
② 注册枚举 (TushareApiEnum)
    ↓
③ TushareClient 方法
    ↓
④ 配置限流 (application.yml)
    ↓
⑤ 数据库层 (schema.sql + DO + Mapper + XML)
    ↓
⑥ Service 层（接口 + 实现 + DataCheckable）
    ↓
⑦ Controller 层（REST 查询接口）
    ↓
⑧ InitStep 注册（表元数据）
    ↓
⑨ DataInitService 接入（增量/全量更新）
    ↓
⑩ 接入 DataCheckable 数据校验
    ↓
⑪ 接入定时任务
    ↓
⑫ Mapper 扫描（自动完成）
    ↓
⑬ 测试验证
```

---

## 六、参考实现对照

| 接口 | 特点 | 可作为哪种模式的参考 |
|------|------|---------------------|
| `daily`（日线行情） | per-stock + 按日期、分页、批量保存、DataCheckable 完整实现 | **新接口最佳参考** |
| `stock_basic`（股票基础信息） | 一次性全量拉取，无需分页 | 参考：一次性全量接口 |
| `income`（利润表） | 财务类、按报告期、per-stock 拉取 | 参考：财务类接口 |
| `trade_cal`（交易日历） | 按交易所、日期范围一次性拉取 | 参考：无股票维度的接口 |

---

## 七、下一步建议

### 短期（数据治理深化）

1. 完善各表的自定义检测项，覆盖更多数据质量维度
2. 增加数据修复/补全的自动化能力
3. 优化检测性能，支持更频繁的抽检

### 中期（接口覆盖扩展）

1. 优先对接 P1 级接口（weekly/monthly/股东人数）
2. 补充分钟线数据（如需要更高频策略）
3. 增加基金/期货等多资产类别数据

### 长期（数据价值挖掘）

1. 建立数据质量评分体系
2. 数据血缘追踪
3. 多源数据融合与交叉验证

---

## 八、相关分册

- 新接口完整对接步骤 → [02-tushare-integration-guide.md](./02-tushare-integration-guide.md)
- 数据管控中心详解 → [05-data-governance-center.md](./05-data-governance-center.md)
- Tushare 官方接口参考索引 → [04-tushare-api-reference.md](./04-tushare-api-reference.md)
