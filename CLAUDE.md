# StockPulse — AI 开发知识库入口

> 每次会话都会加载。只放**导航**与**硬约束**，详细内容指向权威文档，避免漂移。

## 权威文档定位

| 做什么 / 找什么                          | 去哪里 |
|------------------------------------|--------|
| 产品方向 / 功能模块 / 路线图 / 技术栈 / API / 页面 | [`README.md`](./README.md) — **最高权威，冲突以此为准** |
| 改数据库 / 写 SQL(**勿凭记忆**)             | [`stock-watcher/src/main/resources/schema.sql`](./stock-watcher/src/main/resources/schema.sql) |
| 模块研发设计（架构/DB/API/Schema）           | `sdlc/design/`（001 K线复权·002 因子库·003 策略管理·004 回测中心·005 选股模块） |
| 模块需求 / HTML 原型                     | `sdlc/prd/`（同上编号；`旧-量化策略引擎V2.0` 为归档，新需求以 001~005 为准） |
| 写代码前的编码规范                          | `.trae/rules/`（组织方式见下） |
| 理解两系统运行时协作                         | 本文「硬约束」段 |
| 搭 Python 环境 / 启动 engine            | `.trae/rules/stock-engine/python/00-environment-setup.md` |
| 启动 watcher / 配 Tushare Token       | `README.md` §八 |

## 项目是什么

面向个人 A 股投资者的**规则化量化辅助决策系统**（规则化 / 可解释 / 全闭环，不用 ML 黑箱）。Java + Python 双系统，HTTP/JSON 通信：

- **stock-watcher**（Java 21 + Spring Boot 4.0.6，:8080）：业务 + 数据中台——数据获取/清洗/存储、认证、前端页面、策略管理、信号、模拟交易、风控。默认账号 `admin` / `admin123`。
- **stock-engine**（Python 3.12 + FastAPI + AKQuant[Rust 核心]，:8000）：计算服务——技术/基本面/情绪面指标、策略回测、因子库。API 文档 :8000/docs。

## ⚠️ 硬约束：engine 绝不读写 SQLite

- **数据单源性**：watcher 独占 SQLite 读写；engine 需要数据时由 watcher 通过 HTTP 传入，engine 只返回 JSON。交互单向（watcher → engine，engine 不回调 watcher）。
- **AI 约束**：engine（Python）侧**禁止**出现 `sqlite3` / `sqlalchemy` / 直连 `.db` 的代码。
- 运行时闭环：① watcher 每日 16:00 定时任务拉 Tushare → SQLite → ② 前复权清洗（price × adj_factor）/ 剔除停牌涨跌停 ST → ③ watcher 读行情 HTTP 传 engine 算指标 → JSON → Caffeine 缓存 → ④ 策略回测同理（AKQuant 模拟 T+1/涨跌停）→ ⑤ 收盘后跑策略生成买卖信号 + 原因 → 推送（企微/钉钉/邮件/网页）→ ⑥ 模拟交易 / 风控检查。

## 代码结构（放新代码时参考）

- **watcher**（`stock-watcher/src/main/java/com/arthur/stock/`）：`controller`(REST+页面) · `service`(+`impl/`) · `mapper`(MyBatis) · `model`(`*DO`) · `dto`/`vo` · `client`(Tushare+限流) · `task`(定时) · `annotation`+`aspect`+`interceptor`(认证 AOP) · `config`/`cache`/`context`/`constant`/`util`/`exception`
- **engine**（`stock-engine/`）：`main.py`/`config.py` · `api/v1/`(data/quote/backtest) · `services/`(`data_collector` 采集 · `indicator` 技术指标 · `factor` 因子 · `backtest` 回测) · `models/`(domain/schemas) · `core`(异常/日志) · `utils`

## 编码规范（`.trae/rules/`）

两级定位：**通用**看 `general/`；watcher 看 `stock-watcher/{java,frontend,business}/`；engine 看 `stock-engine/{python,business}/`。各目录下文件按 01~07 编号（style/perf/security 等基本自描述），需要时 `ls` 该目录即可。

非自描述、值得主动记忆的特殊文档：
- `stock-engine/business/02-akquant 交互层架构设计.md` / `03-akquant 能力核查.md` — AKQuant 架构与能力
- `stock-watcher/business/02-tushare-integration-guide.md` — Tushare 对接
