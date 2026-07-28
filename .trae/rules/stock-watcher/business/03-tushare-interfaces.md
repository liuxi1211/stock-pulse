# Tushare 接口清单与架构

> **面向 AI**：本文是 stock-watcher 的 Tushare 接口**全局地图**。整合自原 03（已对接现状）+ 04（官方接口大全）。
> 一张表同时覆盖「本项目已对接的 25 个接口」与「Tushare 官方全部可用接口」，按资产类别分组，标注对接状态/积分/更新频率。
> 对接新接口的操作步骤见 [02-tushare-integration.md](./02-tushare-integration.md)；数据治理模块见 [05-data-governance-center.md](./05-data-governance-center.md)。

---

## 一、整体架构（六层）

```
┌─────────────────────────────────────────────────────────┐
│  ⑥ 数据管控中心层（DataGovernanceController/Service）      │
│     数据质量检测 · 状态监控 · 拉取日志 · 数据源健康         │
├─────────────────────────────────────────────────────────┤
│  ⑤ 统一更新入口层（DataInitService）                       │
│     增量 incrementalUpdate · 全量 fullRebuild · 异步 · 锁   │
├─────────────────────────────────────────────────────────┤
│  ④ 服务层（25 个 Service，全部实现 DataCheckable）          │
│     增量拉取 · 批量保存 · 本地查询 · 数据校验                │
├─────────────────────────────────────────────────────────┤
│  ③ 数据模型层（DO · MyBatis-Plus Mapper · XML SQL）        │
├─────────────────────────────────────────────────────────┤
│  ② 数据传输层（TushareClient · RateLimiter · TushareApiEnum）│
│     滑动窗口阻塞式限流                                    │
├─────────────────────────────────────────────────────────┤
│  ① 配置层（TushareConfig · application.yml）               │
└─────────────────────────────────────────────────────────┘
```

### 核心组件速查

| 组件 | 职责 |
|---|---|
| `TushareClient` | 封装所有 Tushare API 调用（query + queryWithPaging） |
| `TushareApiEnum` | 所有已对接接口的 apiName + fields |
| `RateLimiter` | 滑动窗口阻塞式限流器（按接口独立计数） |
| `InitStep` | 25 张表的元数据（分组/频率/是否日线/对应API） |
| `TableGroup` | 5 大分组枚举（BASIC/MARKET/FINANCE/EVENT/INDEX） |
| `DataCheckable` | 数据校验统一接口（25 个 Service 全实现） |
| `DataGovernanceService` | 数据质量检测与状态查询 |
| `DataInitService` | 增量 + 全量统一更新入口 |
| `TaskProgressCache` | 任务进度内存缓存（30min 过期）+ 全局任务锁（2h 超时） |
| `DataPullLogMapper` | 数据拉取操作日志 |
| `DataSourceHealthCache` | Tushare 连通性监控 |
| `DataGovernanceCheckJob` | 定时任务：每日 22:00 全表质量检测 |

---

## 二、已对接接口清单（25 个）

> 五大分组：BASIC（2）/ MARKET（5）/ FINANCE（6）/ EVENT（6）/ INDEX（6）。
> `isDaily=true` 的表参与数据延迟检测。

### BASIC · 基础数据（2）

| InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线 |
|---|---|---|---|---|---|
| `stock_basic` | `stock_basic` | `stock_basic` | 股票基础信息 | 每日 16:00 | 否 |
| `trade_cal` | `trade_cal` | `trade_cal` | 交易日历 | 每日 16:00 | 否 |

### MARKET · 行情数据（5）

| InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线 |
|---|---|---|---|---|---|
| `daily` | `daily` | `daily_quote` | 日线行情 | 每个交易日 16:00 | 是 |
| `adj_factor` | `adj_factor` | `adj_factor` | 复权因子 | 每个交易日 16:00 | 是 |
| `stk_limit` | `stk_limit` | `stock_stk_limit` | 涨跌停价 | 每个交易日 16:40 | 是 |
| `daily_basic` | `daily_basic` | `daily_basic` | 每日基本面/估值 | 每个交易日 16:10 | 是 |
| `moneyflow` | `moneyflow` | `stock_moneyflow` | 个股资金流向 | 每个交易日 16:10 | 是 |

### FINANCE · 财务数据（6）

| InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线 |
|---|---|---|---|---|---|
| `fina_indicator` | `fina_indicator` | `fina_indicator` | 财务指标 | 每周日 17:00 | 否 |
| `income` | `income` | `income` | 利润表 | 每周日 17:30 | 否 |
| `balancesheet` | `balancesheet` | `balancesheet` | 资产负债表 | 每周日 18:00 | 否 |
| `cashflow` | `cashflow` | `cashflow` | 现金流量表 | 每周日 18:30 | 否 |
| `forecast` | `forecast` | `forecast` | 业绩预告 | 每周日 19:00 | 否 |
| `express` | `express` | `express` | 业绩快报 | 每周日 19:30 | 否 |

### EVENT · 事件数据（6）

| InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线 |
|---|---|---|---|---|---|
| `dividend` | `dividend` | `dividend` | 分红送股 | 每日 16:00 | 否 |
| `namechange` | `namechange` | `stock_namechange` | 股票更名历史(ST) | 每日 16:30 | 否 |
| `suspend_d` | `suspend_d` | `stock_suspend_d` | 停复牌信息 | 每日 16:35 | 否 |
| `top_list` | `top_list` | `top_list` | 龙虎榜-每日榜单 | 每个交易日 16:10 | 是 |
| `top_inst` | `top_inst` | `top_inst` | 龙虎榜-机构席位 | 每个交易日 16:10 | 是 |
| `block_trade` | `block_trade` | `block_trade` | 大宗交易 | 每个交易日 16:10 | 是 |

### INDEX · 指数与市场（6）

| InitStep.code | Tushare API | 表名 | 中文名 | 更新频率 | 日线 |
|---|---|---|---|---|---|
| `index_weight` | `index_weight` | `index_weight` | 指数成分权重 | 每日 20:00 | 否 |
| `sw_industry` | `index_classify` | `sw_industry` | 申万行业分类 | 每半年 | 否 |
| `hk_hold` | `hk_hold` | `hk_hold` | 沪深港通持股 | 每个交易日 16:10 (T+1) | 是 |
| `margin` | `margin` | `margin` | 融资融券-汇总 | 每个交易日 16:10 | 是 |
| `margin_detail` | `margin_detail` | `margin_detail` | 融资融券-明细 | 每个交易日 16:10 | 是 |
| `index_daily` | `index_daily` | `index_daily` | 指数日线 | 每个交易日 16:30 | 是 |

---

## 三、官方接口大全（含未对接，按资产类别）

> 图例：✅ 已对接（括号为本地表名）/ ❌ 未对接。
> **积分**：Tushare Pro 分级门槛（不消耗，仅分级）；达对应积分才有调用权限。详见 [积分获取](https://tushare.pro/document/1?doc_id=13)、[积分频次表](https://tushare.pro/document/1?doc_id=290)。

### 股票数据

| API | 描述 | 最低积分 | 更新时间 | 状态 |
|---|---|---|---|---|
| `daily` | 日线 | 120 起 | 交易日 15-17 点 | ✅ `daily_quote` |
| `weekly` | 周线 | 2000 | 每周五 15-17 点 | ❌ |
| `monthly` | 月线 | 2000 | 每月 | ❌ |
| `pro_bar` | 复权行情（统一接口） | 2000（分钟/指数/基金/期货除外） | 每月 | ❌（用 `adj_factor` 替代） |
| `daily_basic` | 每日指标 | 2000 起 | 交易日 15-17 点 | ✅ `daily_basic` |
| `adj_factor` | 复权因子 | 120 起 | 交易日每日 | ✅ `adj_factor` |
| `new_share` | IPO 新股 | 120 | 每日 19 点 | ❌ |
| `top_list` | 龙虎榜每日明细 | 2000 | 每日晚 8 点 | ✅ `top_list` |
| `top_inst` | 龙虎榜机构明细 | 2000 | 每日晚 8 点 | ✅ `top_inst` |
| `pledge_detail` | 股权质押明细 | 2000 | 每日晚 9 点 | ❌ |
| `pledge_stat` | 股权质押统计 | 2000 | 每日晚 9 点 | ❌ |
| `margin` | 融资融券汇总 | 2000 | 每日 9 点 | ✅ `margin` |
| `margin_detail` | 融资融券明细 | 2000 | 每日 9 点 | ✅ `margin_detail` |
| `repurchase` | 股票回购 | 2000 | 每日定时 | ❌ |
| `share_float` | 限售股解禁 | 3000 | 定期 | ❌ |
| `block_trade` | 大宗交易 | 2000 | 每日晚 9 点 | ✅ `block_trade` |
| `stk_holdernumber` | 股东人数 | 2000 | 不定期 | ❌ |
| `moneyflow` | 个股资金流向 | 2000 | 交易日 19 点 | ✅ `stock_moneyflow` |
| `stk_holdertrade` | 股东增减持 | 2000 | 交易日 19 点 | ❌ |
| `stk_limit` | 每日涨跌停价 | 2000 起 | 交易日 9 点 | ✅ `stock_stk_limit` |
| `hk_hold` | 沪深股通持股明细 | 2000 起 | 下个交易日 8 点 | ✅ `hk_hold` |
| `stock_basic` | 股票基础信息 | 120 | 每日 | ✅ `stock_basic` |
| `namechange` | 股票名称变更 | 2000 | 每日 | ✅ `stock_namechange` |
| `suspend_d` | 停复牌信息 | 2000 | 每日 | ✅ `stock_suspend_d` |

### 财务数据

| API | 描述 | 最低积分 | 状态 |
|---|---|---|---|
| `income` | 利润表 | 2000 起 | ✅ `income` |
| `balancesheet` | 资产负债表 | 2000 起 | ✅ `balancesheet` |
| `cashflow` | 现金流量表 | 2000 起 | ✅ `cashflow` |
| `forecast` | 业绩预告 | 2000 起 | ✅ `forecast` |
| `express` | 业绩快报 | 2000 起 | ✅ `express` |
| `dividend` | 分红送股 | 2000 起 | ✅ `dividend` |
| `fina_indicator` | 财务指标 | 2000 起 | ✅ `fina_indicator` |
| `fina_audit` | 财务审计意见 | 2000 起 | ❌ |
| `fina_mainbz` | 主营业务构成 | 2000 起 | ❌ |
| `disclosure_date` | 财报披露计划 | 2000 起 | ❌ |

### 指数数据

| API | 描述 | 最低积分 | 状态 |
|---|---|---|---|
| `index_basic` | 指数基本信息 | 2000 | ❌ |
| `index_daily` | 指数日线行情 | 2000 起 | ✅ `index_daily` |
| `index_weekly` | 指数周线 | 2000 起 | ❌ |
| `index_monthly` | 指数月线 | 2000 起 | ❌ |
| `index_weight` | 指数成分和权重 | 2000 | ✅ `index_weight` |
| `index_dailybasic` | 大盘指数每日指标 | 4000 起 | ❌ |
| `index_classify` | 申万行业分类 | 2000 | ✅ `sw_industry` |
| `index_member_all` | 申万行业成分 | 2000 | ❌ |

### 基金 / 期货 / 期权 / 债券 / 外汇 / 港股 / 宏观

> 当前均未对接，仅列出 API 名供选型参考。

| 资产 | API（均 ❌ 未对接） |
|---|---|
| 基金 | `fund_basic` `fund_company` `fund_nav` `fund_daily` `fund_div` `fund_portfolio`(5000 起) `fund_adj`(5000 起) |
| 期货 | `fut_basic` `fut_daily` `fut_holding` `fut_wsr` `fut_settle` |
| 期权 | `opt_basic`(2000 起) `opt_daily`(5000 起) |
| 债券 | `cb_basic` `cb_issue` `cb_daily` |
| 外汇 | `fx_obasic` `fx_daily` |
| 港股 | `hk_basic` `hk_daily` `hk_mins` |
| 宏观 | `shibor` `shibor_quote` `shibor_lpr` `libor` `hibor` `wz_index` `gz_index` |

---

## 四、未对接高价值接口优先级

| 优先级 | API | 积分 | 说明 | 应用场景 |
|---|---|---|---|---|
| 🟡 P1 | `weekly` | 2000 | 周线行情 | 中长期策略 |
| 🟡 P1 | `monthly` | 2000 | 月线行情 | 长期趋势分析 |
| 🟡 P1 | `stk_holdernumber` | 2000 | 股东人数 | 筹码集中度 |
| 🟢 P2 | `fina_audit` | 2000 | 财务审计意见 | 风险评估 |
| 🟢 P2 | `fina_mainbz` | 2000 | 主营业务构成 | 行业分析/选股 |
| 🟢 P2 | `disclosure_date` | 2000 | 财报披露计划 | 事件驱动 |
| 🟢 P2 | `share_float` | 3000 | 限售股解禁 | 事件驱动 |
| 🟢 P2 | `stk_holdertrade` | 2000 | 股东增减持 | 事件驱动 |
| 🟢 P2 | `pro_bar` | 2000 | 复权行情（统一） | — |
| 🔵 P3 | `fund_portfolio` | 5000 | 公募基金持仓 | 机构持仓分析 |
| 🔵 P3 | `index_dailybasic` | 4000 | 大盘指数每日指标 | 市场估值 |

---

## 五、数据管控中心核心能力（速览）

> 详见 [05-data-governance-center.md](./05-data-governance-center.md)

| 能力 | 说明 |
|---|---|
| 数据质量校验 | 25 个 Service 全实现 `DataCheckable`；三级 ERROR/WARN/INFO；通用项（空表/行数/延迟）+ 自定义项 |
| 统一更新入口 | `incrementalUpdate`（从最新日期+1）/ `fullRebuild`（清空重拉）；虚拟线程异步；全局任务锁 |
| 拉取日志 | `data_pull_log` 表记录每次拉取（taskId/表/类型/状态/耗时/计数/操作人/错误） |
| 定时任务 | `DataGovernanceCheckJob`（每日 22:00 全表检测）+ `DailyUpdateTask`（盘后更新）+ 各专项任务 |
| 数据源健康 | `DataSourceHealthCache` 缓存 Tushare 连通性，支持手动测试（调 `trade_cal`） |

---

## 六、相关分册

- 新接口对接步骤 + 铁律 → [02-tushare-integration.md](./02-tushare-integration.md)
- 数据管控中心详解 → [05-data-governance-center.md](./05-data-governance-center.md)
