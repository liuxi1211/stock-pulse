# StockPulse — 个人 A 股量化分析系统

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Python](https://img.shields.io/badge/Python-3.12+-blue.svg)](https://www.python.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **把交易经验变成可执行的规则，用历史数据验证判断，让机器替你盯盘。**

StockPulse 是一套面向个人 A 股投资者的**规则化量化辅助决策系统**。核心理念：**规则化、可解释、全闭环**——不引入机器学习黑箱，所有策略由透明的因子组合而成；从「看盘 → 研究 → 选股 → 策略 → 回测 → 信号 → 执行 → 复盘」一条龙打通。

系统采用 **Java + Python 混合架构**：
- **stock-watcher（Java 后端）**：业务服务层 + 数据中台——数据获取/清洗/存储、用户认证、前端页面、策略管理、信号推送、风控执行、模拟交易
- **stock-engine（Python 服务）**：计算服务层——技术/基本面/情绪面指标计算、策略回测引擎、因子库管理

> Python 不直连数据库，所有数据由 Java 统一管理、通过 HTTP/JSON 传入 Python，保证数据单源性。

---

## 一、整体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  用户交互层（watcher · Thymeleaf + Bootstrap 5 + ECharts 5）          │
│  市场 / 研究 / 量化 / 交易 / 复盘 / 系统  六大导航域                    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ 页面渲染 + REST API
┌──────────────────────────────────▼───────────────────────────────────┐
│  业务服务层（watcher · Java + Spring Boot + MyBatis-Plus + Caffeine） │
│  数据管理 · 策略管理 · 信号服务 · 模拟交易 · 风控 · 定时任务调度        │
└──────────────┬───────────────────────────────────────┬──────────────┘
               │ HTTP/JSON（调用方）                     │ JDBC（独占读写）
┌──────────────▼─────────────────────────┐  ┌───────────▼───────────────┐
│  计算服务层（engine · Python）           │  │  数据存储层               │
│  FastAPI + AKQuant(Rust 核心) + Pandas   │  │  watcher/data/*.db (SQLite│
│  指标计算 · 策略回测 · 因子库             │  │  WAL)                     │
│  不直接读写 SQLite                        │  └───────────────────────────┘
└──────────────────────────────────────────┘
                    ▲ Tushare Pro REST API（watcher 直接调用）
```

---

## 二、完整功能模块设计（开发对照总表）

按散户真实工作流组织为 **5 大业务域 + 1 个系统域**。**★ 标三个核心锚点**（必须做），🔴 标当前关键缺口，🟡 标已有但需增强。

### 域 A · 市场与行情（看盘入口）

| 模块 | 作用 | 状态 |
|------|------|------|
| 市场总览 / 仪表盘 | 指数卡 + 涨跌/成交额三榜 + 北向资金 + 板块热力 + 当日信号摘要 + 风控状态灯 | 🟡 |
| **个股研究（深度分析）** | 单股统一面板：K线+多指标叠加 / 基本面 / 资金流 / 历史信号回放 / 估值分位 | 🔴 **核心缺口** |
| 行情中心 | 全市场行情表、排序、筛选、一键加自选 | 🟡 |
| 自选股 | 分组管理、当日汇总、相对大盘强弱 | 🟡 |
| 板块行情 | 行业/概念涨跌、成分股、资金轮动矩阵 | 🔴 |
| 市场情绪 | 涨跌停数、连板梯队、炸板率、恐慌贪婪指数 | 🔴 |

### 域 B · 量化研究（核心引擎，三个锚点在此）

| 模块 | 作用 | 状态 |
|------|------|------|
| 因子库 | 因子注册表 + 计算 + **效能分析（IC/IR/分层回测）** | 🟡 缺效能分析 |
| **★ 多因子选股中心** | 规则树选股（快照/区间）+ 方案保存 + 结果追踪 | ★ 设计完成 |
| **★ 策略管理** | 规则树配置、版本管理、仓位/止损止盈、模板向导 | ★ 设计完成 |
| **★ 回测中心** | 单次/网格/Walk-forward + 报告 + 横向对比 | ★ 设计完成 |

### 域 C · 信号与决策（研究 → 当下行动）

| 模块 | 作用 | 状态 |
|------|------|------|
| **信号中心** | 当日全市场信号、历史检索、信号后续表现追踪 | 🔴 **关键闭环** |
| 策略实盘跟踪 | 激活策略实盘净值 vs 回测预期对比、偏差告警 | 🔴 |
| 推送通知 | 企微/钉钉/邮件/网页，规则配置 | 🔴 |

### 域 D · 交易与执行（模拟先行）

| 模块 | 作用 | 状态 |
|------|------|------|
| 持仓与组合管理 | 账户资产、持仓明细、行业暴露、相关性、beta | 🔴 |
| 模拟交易 | T+1/涨跌停/最小单位撮合、每日收益报告 | 🔴 |
| 风控中心 | 单票/总仓位、单日亏损/最大回撤熔断、约束检查 | 🔴 |
| 订单规划 | 目标仓位 → 实际买卖股数、可用资金校验 | 🔴 |

### 域 E · 复盘与成长（个人护城河）

| 模块 | 作用 | 状态 |
|------|------|------|
| 交易日志 / 复盘笔记 | 每笔交易逻辑/情绪标签/复盘评分、按标签统计 | 🔴 |
| 收益分析 | 个股/策略/整体盈亏归因、月度热力图、费后真实收益 | 🔴 |
| 选股结果追踪 | 锁定某次选股结果，跟踪 N 日后表现，反向验证选股逻辑 | 🔴 |

### 域 F · 系统（地基）

| 模块 | 作用 | 状态 |
|------|------|------|
| 用户与认证 | Session + BCrypt + TOTP 双因素、RBAC | ✅ |
| **数据管理 / ETL 监控** | 数据新鲜度、缺失告警、手动补数、交易日历 | 🔴 |
| 基本面数据采集 | 财务指标表（PE/PB/ROE/增速/负债率） | 🔴 |
| 系统设置 | 推送配置、风控参数、数据源 token、引擎健康状态 | 🟡 |

---

## 三、前端页面规划（开发对照清单）

> 侧边栏按 `市场 / 研究 / 量化 / 交易 / 复盘 / 系统` 六大域分组。★ 核心锚点必做；🔴 缺失；🟡 需增强；✅ 已完成。

### 📊 市场

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| 市场总览（仪表盘） | 4 指数卡+迷你走势 / 涨跌成交额三榜 / 北向 / 板块热力 / **当日信号摘要卡** / 风控灯 | 🟡 |
| 行情中心 | 全市场行情表、表头排序、条件筛选、一键加自选 | 🟡 |
| 板块行情 | 行业/概念涨跌、成分股、资金净流入排行、轮动矩阵 | 🔴 |
| 市场情绪 | 涨跌停/连板/炸板率/恐慌贪婪指数/N 日情绪曲线 | 🔴 |

### 🔍 研究

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| **个股研究（深度分析）** | 主图 K线+前复权+多周期+**多指标叠加** / 基本面卡（PE/PB/ROE+估值分位）/ 资金面（主力+北向）/ **历史信号回放** / 加入策略·自选 | 🔴 **核心缺口** |
| 自选股 | 分组、批量管理、当日汇总、相对大盘强弱排序 | 🟡 |
| 股票搜索 | 代码/名称/拼音 + **按条件快筛**（PE<x 且 ROE>y） | 🟡 |

### 🧮 量化

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| 因子库总览 | 分类树、因子卡、参数说明、**效能分析（IC/IR/分层收益）** | 🟡 |
| **★ 选股中心（快照）** | 规则树编辑器 + 结果表 + SQL 预览 + 存为策略草稿 | ★ 待编码 |
| ★ 选股中心（区间） | 首次命中、命中天数、热力图、强势股提示 | ★ 待编码 |
| 🟡 选股方案管理 + 结果追踪 | 方案列表 + 锁定结果跟踪 5/10/20 日表现 | 🔴 |
| **★ 策略列表** | 卡片、状态/分类筛选、关联回测摘要 | ★ 待编码 |
| **★ 策略编辑器** | Tab：基本/买入/卖出/选股范围/仓位；右侧自然语言+JSON 预览 | ★ 待编码 |
| ★ 策略版本与对比 | 版本时间线、JSON diff、回滚 | ★ 待编码 |
| 🟡 策略模板向导 | 5 内置模板向导式填空（双均线/低PE/MACD短线/小市值/北向跟随） | 🔴 |
| **★ 回测列表** | 模式 tab、状态轮询、指标摘要 | ★ 待编码 |
| **★ 回测配置** | 策略+模式+日期+范围+资金+成本，3 模式 tab | ★ 待编码 |
| **★ 回测报告** | 净值vs基准/回撤/月度热力/交易明细/绩效指标 | ★ 待编码 |
| ★ 参数优化结果 | 网格结果表、参数敏感度热力图、应用最优参数 | ★ 待编码 |
| ★ Walk-forward 分析 | 滚动训练/验证分段、过拟合判定 | ★ 待编码 |
| 🟡 回测对比 | 多次回测净值叠加、指标对比表 | 🔴 |

### 💼 交易

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| **信号中心** | 当日买卖信号（含原因）/ 历史检索 / **信号后续表现追踪** | 🔴 |
| 策略实盘跟踪 | 实盘净值 vs 回测预期曲线、偏差告警 | 🔴 |
| 持仓与组合 | 资产/持仓/行业分布饼图/相关性矩阵/组合 beta | 🔴 |
| 模拟交易 | 模拟账户、自动撮合、每日收益报告、交易记录 | 🔴 |
| 风控中心 | 仓位/亏损 vs 阈值、熔断状态、约束检查日志 | 🔴 |

### 📝 复盘

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| 交易日志 / 复盘笔记 | 买卖逻辑/情绪标签/复盘评分/附件、按标签盈亏统计 | 🔴 |
| 收益分析 | 个股/策略/整体盈亏归因、月度热力图、费后收益 | 🔴 |

### ⚙️ 系统

| 页面 | 功能要点 | 状态 |
|------|---------|------|
| 数据管理 / ETL 监控 | 各表新鲜度、缺失告警、手动触发更新、补数进度 | 🔴 |
| 系统设置 | 推送渠道、风控全局参数、数据源 token、引擎健康 | 🟡 |
| 用户管理 | 用户 CRUD、TOTP 重置 | ✅ |
| 登录 / 2FA / TOTP 绑定 | 认证流程 | ✅ |

---

## 四、开发路线图（按优先级分波）

> 原则：先打通「**研究 → 选股 → 策略 → 回测 → 信号**」只读决策链，再补「**模拟交易 → 持仓 → 风控 → 复盘**」执行链。

### 第一波 · 让系统成为「能用的分析软件」（6~8 页）
- [ ] 🔴 **个股研究页**（P0 最高优先，承载所有下钻）
- [ ] ★ 多因子选股中心（设计已完成，落 Thymeleaf）
- [ ] ★ 策略管理三件套：列表/编辑器/版本（设计已完成）
- [ ] ★ 回测中心：列表/配置/报告/优化/WF（设计已完成）
- [ ] 🟡 数据管理 / ETL 监控页
- [ ] 导航信息架构重构（六大域分组定型）

### 第二波 · 补齐决策闭环 + 数据地基
- [ ] 🔴 信号中心（把锚点产出落地为「今日行动」）
- [ ] 🔴 基本面数据采集 + 因子库扩展（解锁价值类策略）
- [ ] 🟡 选股结果追踪 + 回测对比（研究闭环）
- [ ] 🔴 情绪面数据（北向/龙虎榜，A 股特色）

### 第三波 · 执行链，升级为决策系统
- [ ] 持仓与组合管理
- [ ] 模拟交易
- [ ] 风控中心
- [ ] 策略实盘跟踪（回测 vs 实盘偏差）

### 第四波 · 护城河 / 差异化
- [ ] 交易日志 / 复盘笔记
- [ ] 收益分析
- [ ] 板块行情、市场情绪
- [ ] 策略模板向导（新手引导）

---

## 五、当前实现状态

### ✅ 已完成（Phase 0 · 数据中台 + 基础业务）
- **数据获取与存储**：Tushare Pro REST API 全市场数据（股票基础、日线、复权因子、分红送转、交易日历）
- **数据初始化**：一键异步批量导入，5 步流水线 + 实时进度追踪
- **K 线计算**：日/周/月 K 线、前复权、Caffeine 缓存
- **定时任务**：每日 16:00 全量同步 + 22:00 完整性校验补缺
- **自选股 / 搜索 / 市场排行**：增删改查、拼音首字母搜索、涨跌/成交额榜
- **用户认证**：BCrypt + TOTP 双因素（Google Authenticator）+ RBAC
- **因子库起步**：`FactorDefinitionService` / `FactorController`（注册表雏形）

### 📐 设计完成，待编码（Phase 1 · 量化策略引擎）
> 设计文档见 `sdlc/prd/`（003 选股与回测边界设计·004 统一策略配置Schema）与 `.trae/rules/akquant/`

- **002 因子库**：技术面/基本面/情绪面/统计面四类，20+ 标准因子
- **003 策略管理**：可视化规则树配置、JSON Schema、版本管理
- **004 回测中心**：单次/参数优化/Walk-forward，完整绩效报告
- **005 选股模块**：规则树 → SQL 翻译引擎、快照/区间双模式

### 🔴 PRD 提及，待设计
- 信号中心、模拟交易、持仓与组合、风控中心、基本面/情绪面数据采集、数据监控、复盘日志

---

## 六、技术栈

### 后端（stock-watcher）
| 技术 | 版本 | 作用 |
|------|------|------|
| Spring Boot | 4.0.6 | Web 框架、自动配置 |
| MyBatis-Plus | 3.5.15 | ORM 框架 |
| SQLite JDBC | 3.47.2 | 轻量文件数据库（WAL 模式） |
| Caffeine | (Spring 管理) | 高性能本地缓存 |
| Spring Scheduler | (Spring 管理) | 定时任务 |
| Tushare Pro REST API | - | 金融数据源 |
| Spring Security Crypto | (Spring 管理) | 密码加密 |
| GoogleAuth | 1.5.0 | TOTP 双因素认证 |

工具库：FastJSON2 / Hutool / Guava / Apache Commons Lang3 / Lombok

### 计算服务（stock-engine）
| 技术 | 作用 |
|------|------|
| FastAPI + Uvicorn | Python Web 框架，计算 REST API |
| AKQuant | Rust 核心量化框架，策略回测与指标计算 |
| Pandas | 数据处理 |
| AKShare | 辅助数据采集 |

### 前端
| 技术 | 部署方式 | 作用 |
|------|----------|------|
| Thymeleaf | Spring Boot 内嵌 | 服务端模板引擎 |
| Bootstrap 5 | 本地静态资源 | UI 框架 |
| ECharts 5 + lightweight-charts | 本地静态资源 | 图表 / K 线可视化 |
| 原生 JavaScript | 本地静态资源 | 前端交互 |

---

## 七、项目结构

```
stock-pulse/
├── stock-watcher/                     # Java 业务服务层（端口 8080）
│   └── src/main/java/com/arthur/stock/
│       ├── annotation/ aspect/ interceptor/   # @RequireAdmin / AOP / 认证拦截
│       ├── cache/ client/ config/              # 缓存 / Tushare 客户端+限流 / 配置
│       ├── constant/ context/                  # 枚举常量 / 用户上下文
│       ├── controller/                         # REST + 页面控制器
│       ├── dto/ vo/ model/ mapper/             # 传输/视图/数据模型/MyBatis
│       ├── service/ (+impl/)                   # 业务接口与实现
│       ├── task/                               # 定时任务
│       └── util/                               # 工具类（KlineCalculator / TotpUtil）
│
├── stock-engine/                      # Python 计算服务层（端口 8085）
│   ├── main.py config.py                       # FastAPI 入口 / 配置
│   ├── api/v1/                                 # 路由（data / quote / backtest）
│   ├── core/                                   # 异常 / 日志
│   ├── models/ (domain/ schemas/)              # 领域模型 / Pydantic 传输模型
│   ├── services/
│   │   ├── data_collector/                     # AKShare 数据采集
│   │   ├── indicator/                          # AKQuant 技术指标
│   │   ├── factor/                             # 因子计算
│   │   └── backtest/                           # 策略回测引擎
│   └── utils/
│
├── sdlc/                              # 需求 / 设计文档
│   ├── prd/                                    # 产品需求文档（含原型 HTML）
│   └── design/                                 # 研发设计文档（架构/DB/API/Schema）
│
├── .trae/rules/                       # AI 开发规则（general / watcher / engine）
├── CLAUDE.md                          # AI 知识库根入口
└── data/                              # SQLite 数据库文件
```

---

## 八、快速开始

### 环境要求
- JDK 21+、Maven 3.6+
- Python 3.12+（推荐 Conda 环境 `stock`）
- Tushare Pro 账号 + Token

### 启动 Java 后端（stock-watcher）
```bash
cd stock-watcher
mvn spring-boot:run              # 开发模式
# 或 mvn clean package -DskipTests && java -jar target/*.jar
```
访问 http://localhost:8080 · 默认管理员 `admin` / `admin123`（首次登录引导设置 TOTP）

### 启动 Python 计算服务（stock-engine）
```bash
cd stock-engine
conda activate stock
pip install -r requirements.txt  # 首次
uvicorn main:app --host 127.0.0.1 --port 8085
```
API 文档 http://127.0.0.1:8085/docs

> 端口被占用时 engine 可改 8001，同步改 Java 侧 `python.compute.url` 配置。

### 配置 Tushare Token
在 `stock-watcher/src/main/resources/application-secret.properties`：
```properties
tushare.token=your_tushare_pro_token
```

---

## 九、数据库概览

SQLite 单文件（`data/stock_watcher.db`），WAL 模式支持并发读写。

| 表名 | 说明 | 状态 |
|------|------|------|
| `sys_user` | 用户（BCrypt 密码 + TOTP 密钥 + 角色） | ✅ |
| `sys_watchlist` | 自选股 | ✅ |
| `stock_basic` | 股票基础信息（代码/名称/行业/上市状态） | ✅ |
| `daily_quote` | 日线行情（开高低收/量/额） | ✅ |
| `adj_factor` | 复权因子 | ✅ |
| `dividend` | 分红送转 | ✅ |
| `trade_cal` | 交易日历 | ✅ |
| `stock_indicator_daily` | 技术指标预计算（列式） | 待建（005） |
| `stock_financial_indicator` | 基本面财务指标 | 待建 |
| `quant_strategy` / `quant_strategy_version` | 策略主表 / 版本快照 | 待建（003） |
| `quant_backtest*` | 回测主记录/报告/优化/WF 结果 | 待建（004） |

---

## 十、API 概览

### 数据获取（watcher）
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/tushare/data-init` | POST | 触发一键数据初始化 |
| `/api/tushare/data-init/status` | GET | 查询初始化进度 |
| `/api/tushare/{stock-basic,daily,adj-factor,dividend,trade-cal}` | GET | 各类数据查询 |

### 业务查询（watcher）
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/kline/{stockCode}` | GET | K 线（日/周/月，前复权） |
| `/api/market/{indices,ranking}` | GET | 市场指数 / 涨跌幅成交额排行 |
| `/api/watchlist` | GET/POST/DELETE | 自选股管理 |
| `/api/search` / `/api/search/suggest` | GET | 股票搜索 / 自动补全 |

### 量化计算（engine → 被 watcher 调用）
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/compute/indicators` | POST | 指标批量计算 |
| `/api/compute/strategy/{parse,simulate}` | POST | 规则树解析 / 信号模拟 |
| `/api/compute/backtest` | POST | 策略回测 |

> 选股 `/api/screening/*`、策略 `/api/strategies/*`、回测 `/api/backtest/*` 待 003/004/005 编码后补全。

### 认证与用户（watcher）
| 接口 | 方法 | 说明 |
|------|------|------|
| `/login` / `/login/verify-totp` | GET/POST | 登录 / TOTP 验证 |
| `/api/users` | GET/POST | 用户管理（管理员） |
| `/api/users/{id}/reset-totp` | POST | 重置 TOTP（管理员） |

---

## 十一、统一知识库与文档索引

> 项目文档统一在根目录，不再分散到子项目。

### AI 开发规则（`.trae/rules/`）
| 目录 | 说明 |
|------|------|
| `.trae/rules/general/` | 跨项目通用规则（工作流/Git/测试/性能/安全） |
| `.trae/rules/stock-watcher/{java,frontend,business}/` | Java 项目开发规范 |
| `.trae/rules/stock-engine/{python,business}/` | Python 项目开发规范 |
| `CLAUDE.md` | AI 知识库根入口（双项目架构总览） |

### 需求与设计文档（`sdlc/`）
| 文档 | 路径 | 说明 |
|------|------|------|
| 系统 PRD V1.0 | `sdlc/prd/001-A股个人规则化量化分析系统V1.0/` | 整体产品需求定义 |
| 因子库 | `sdlc/prd/002-因子库/标准因子库-v2.json` | 因子分类体系（标准因子库） |
| 多因子选股中心 | `sdlc/prd/003-多因子选股中心/选股与回测边界设计.md` | 选股/回测职责边界与数据流 |
| 策略管理（Schema 唯一权威） | `sdlc/prd/004-策略管理/统一策略配置Schema.md` | 统一策略配置 JSON Schema、akquant 映射 |
| 回测中心 | `sdlc/prd/005-回测中心/` | 待编码 |
| akquant 框架用法 | `.trae/rules/akquant/` | 回测/策略/指标方法知识库（源码核校） |

---

## 十二、部署

```bash
# Docker
docker build -t stockpulse .
docker run -d -p 8080:8080 -v ./data:/app/data stockpulse

# 切换环境
java -jar stock-watcher.jar --spring.profiles.active=prod
```

---

## License

MIT
