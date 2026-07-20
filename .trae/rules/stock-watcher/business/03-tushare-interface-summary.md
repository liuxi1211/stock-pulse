
# Tushare接口现状与规划

&gt; **面向 AI 和开发者**：本文档总结了 stock-watcher 项目中 Tushare 接口的对接现状、已对接接口清单、未对接重要接口以及优先级建议。
&gt; **与 02 的关系**：02 是「怎么做」的操作指南，03 是「做什么」的规划文档，两者互补。

---

## 一、现有对接架构

项目采用了完善的分层架构对接 Tushare：

### 核心组件层
- **TushareConfig** - 配置类，管理 token、API 地址、限流规则
- **TushareApiEnum** - 枚举所有已对接接口名称和字段
- **RateLimiter** - 滑动窗口限流器，防止超出 Tushare 调用限制
- **TushareClient** - 封装所有 Tushare API 调用

### 数据传输层
- **DTO 类** (`dto/tushare/`) - QueryDTO（请求参数）和 ResultDTO（结果数据）

### 服务层
- **Service 接口** - 定义数据操作契约
- **ServiceImpl** - 实现增量更新、批量保存、本地查询等

### 数据模型层
- **DO 类** (`model/`) - 数据库表实体
- **Mapper 接口** - MyBatis Plus 数据库操作
- 支持 MySQL 和 SQLite 双数据库

### 初始化和定时任务
- **DataInitService** - 全量数据初始化服务
- **DataVerifyTask** - 每日数据校验补全
- **DailyUpdateTask** - 日常数据更新
- **专项任务** - StockStkLimitTask、StockSuspendDTask 等

---

## 二、已对接的 Tushare 接口（13 个）

| 序号 | 接口名 | Enum值 | 数据表 | InitStep | 说明 |
|------|--------|--------|--------|----------|------|
| 1 | daily | DAILY | daily_quote | DAILY | 日线行情 |
| 2 | stock_basic | STOCK_BASIC | stock_basic | STOCK_BASIC | 股票基础信息 |
| 3 | trade_cal | TRADE_CAL | trade_cal | TRADE_CAL | 交易日历 |
| 4 | adj_factor | ADJ_FACTOR | adj_factor | ADJ_FACTOR | 复权因子 |
| 5 | dividend | DIVIDEND | dividend | DIVIDEND | 分红送股 |
| 6 | index_weight | INDEX_WEIGHT | index_weight | INDEX_WEIGHT | 指数成分股权重 |
| 7 | index_classify | INDEX_CLASSIFY | sw_industry | SW_INDUSTRY | 申万行业分类 |
| 8 | index_member_all | INDEX_MEMBER_ALL | sw_industry_member | SW_INDUSTRY | 申万行业成分股 |
| 9 | namechange | NAMECHANGE | stock_namechange | NAMECHANGE | 股票曾用名 |
| 10 | suspend_d | SUSPEND_D | stock_suspend_d | SUSPEND_D | 停复牌信息 |
| 11 | stk_limit | STK_LIMIT | stock_stk_limit | STK_LIMIT | 涨跌停价 |
| 12 | daily_basic | DAILY_BASIC | - | - | 每日基本指标 |
| 13 | fina_indicator | FINA_INDICATOR | fina_indicator | - | 财务指标 |

---

## 三、Tushare 2000积分可对接的重要接口（未对接）

| 优先级 | 接口名 | 积分要求 | 说明 | 应用场景 | Tushare文档 |
|--------|--------|---------|------|----------|------------|
| 🔴 P0 | income | 2000 | 利润表 | 基本面分析、选股 | [doc_id=33](https://tushare.pro/document/2?doc_id=33) |
| 🔴 P0 | balancesheet | 2000 | 资产负债表 | 基本面分析、选股 | [doc_id=36](https://tushare.pro/document/2?doc_id=36) |
| 🔴 P0 | cashflow | 2000 | 现金流量表 | 基本面分析、选股 | [doc_id=44](https://tushare.pro/document/2?doc_id=44) |
| 🟡 P1 | forecast | 2000 | 业绩预告 | 事件驱动策略 | [doc_id=45](https://tushare.pro/document/2?doc_id=45) |
| 🟡 P1 | express | 2000 | 业绩快报 | 基本面分析 | [doc_id=46](https://tushare.pro/document/2?doc_id=46) |
| 🟢 P2 | fina_audit | 2000 | 财务审计意见 | 风险评估 | [doc_id=80](https://tushare.pro/document/2?doc_id=80) |
| 🟢 P2 | fina_mainbz | 2000 | 主营业务构成 | 行业分析、选股 | [doc_id=81](https://tushare.pro/document/2?doc_id=81) |

### 优先级说明

- **P0（财务三大表）** - 基本面分析核心必备，优先对接
- **P1（业绩预告/快报）** - 事件驱动策略重要数据
- **P2（审计意见/主营业务）** - 补充分析，优先级稍低

---

## 四、新增接口对接步骤

如需对接新的 Tushare 接口，请参考 **02-tushare-integration-guide.md** 中的 11 步操作指南。

### 快速回顾

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
⑥ Service 层         ← XxxService 接口 + XxxServiceImpl 实现
    ↓
⑦ Controller 层      ← REST 查询接口
    ↓
⑧ 接入初始化流程     ← InitStep + DataInitServiceImpl
    ↓
⑨ 接入定时任务       ← DailyUpdateTask（每日增量）
    ↓
⑩ 配置 Mapper 扫描   ← 检查 Mapper 目录已有 @MapperScan（自动完成）
    ↓
⑪ 测试验证           ← curl 验证 fetch + query
```

---

## 五、参考实现

| 接口 | 特点 | 可作为哪种模式的参考 |
|------|------|---------------------|
| `daily`（日线行情） | per-stock 增量拉取 + 按日期全市场拉取 + 分页 + 批量保存 | **新接口最佳参考** |
| `stock_basic`（股票基础信息） | 一次性全量拉取，无需分页，无需 per-stock | 参考：一次性全量接口 |
| `fina_indicator`（财务指标） | 按股票、按报告期拉取 | 参考：财务类接口 |

---

## 六、下一步建议

1. **优先对接 P0 接口** - income、balancesheet、cashflow
2. **其次对接 P1 接口** - forecast、express
3. **最后考虑 P2 接口** - fina_audit、fina_mainbz
4. **对接时请参考 02 文档** - 严格按照 11 步操作指南执行

