# StockPulse — AI 开发知识库入口

> 每次会话都会加载。只放**导航**与**硬约束**，详细内容指向权威文档，避免漂移。
>
> 关键约定：所有规范都在 `.trae/rules/` 下按分类组织，下面是详细的**何时看哪个文档**的完整索引。

---

## 一、权威文档定位（先看这里）

| 做什么 / 找什么                          | 去哪里                                                                                                 |
|------------------------------------|-----------------------------------------------------------------------------------------------------|
| 产品方向 / 功能模块 / 路线图 / 技术栈 / API / 页面 | [`README.md`](./README.md) — **最高权威，冲突以此为准**                                                        |
| 改数据库 / 写 SQL(**勿凭记忆**)             | [`stock-watcher/src/main/resources/schema.sql`](./stock-watcher/src/main/resources/schema.sql)      |
---

## 二、项目概览

### 2.1 系统架构

面向个人 A 股投资者的**规则化量化辅助决策系统**（规则化 / 可解释 / 全闭环，不用 ML 黑箱）。Java + Python 双系统，HTTP/JSON 通信：

| 系统 | 技术栈 | 端口 | 职责 |
|-----|--------|-----|------|
| **stock-watcher** | Java 21 + Spring Boot 4.0.6 | :8080 | 业务 + 数据中台——数据获取/清洗/存储、认证、前端页面、策略管理、信号、模拟交易、风控。默认账号 `admin` / `admin123` |
| **stock-engine** | Python 3.12 + FastAPI + AKQuant[Rust 核心] | :8085 | 计算服务——技术/基本面/情绪面指标、策略回测、因子库。API 文档 :8085/docs |


### 2.2 ⚠️ 硬约束：engine 绝不读写 SQLite

- **数据单源性**：watcher 独占 SQLite 读写；engine 需要数据时由 watcher 通过 HTTP 传入，engine 只返回 JSON。交互单向（watcher → engine，engine 不回调 watcher）。
- **AI 约束**：engine（Python 侧）**禁止**出现 `sqlite3` / `sqlalchemy` / 直连 `.db` 的代码。
- 运行时闭环：① watcher 每日 16:00 定时任务拉 Tushare → SQLite → ② 前复权清洗（price × adj_factor）/ 剔除停牌涨跌停 ST → ③ watcher 读行情 HTTP 传 engine 算指标 → JSON → Caffeine 缓存 → ④ 策略回测同理（AKQuant 模拟 T+1/涨跌停）→ ⑤ 收盘后跑策略生成买卖信号 + 原因 → 推送（企微/钉钉/邮件/网页）→ ⑥ 模拟交易 / 风控检查。

---

## 三、通用开发规范（所有模块通用）

路径：`.trae/rules/general/`

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| 开始新功能、规划开发流程 | `01-development-workflow.md` | 需求分析→方案设计→编码实现→自测验证→代码提交→Code Review→合并部署；分支策略；环境配置；开发原则（KISS/YAGNI/最小改动/防御式编程） |
| Git 提交、分支管理 | `02-git-commit.md` | 分支类型（feature/fix/refactor/docs/perf/test/chore）；Conventional Commits 格式；提交原则；.gitignore 规范；语义化版本 |
| Code Review、代码质量检查 | `03-code-review.md` | CR 流程；检查清单（功能正确性/代码质量/性能/安全性/可维护性/测试）；反馈分级（🔴阻断/🟡重要/💡建议/❓提问）；反馈原则；项目特定审查重点（量化计算/数据获取/安全/性能） |
| 写单元测试、集成测试 | `04-testing.md` | 测试金字塔；单元测试规范（Given-When-Then/命名/断言/测试数据）；集成测试规范；测试覆盖率；测试最佳实践（独立性/速度/Mock使用/维护）；项目特定测试要求（量化计算/K线计算/Tushare数据处理/Python服务） |
| 性能优化通用原则 | `05-performance-general.md` | 性能优化通用原则 |
| 安全开发通用原则 | `06-security-general.md` | 安全开发通用原则 |

---

## 四、stock-watcher（Java 后端）规范

路径：`.trae/rules/stock-watcher/`

### 4.1 Java 编码规范（`java/`）

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| 写 Java 代码、代码风格、命名规范 | `01-java-coding-style.md` | 包命名；类/接口命名（*DO/*DTO/*VO/*Service/*Controller/*Mapper/*Config/*Exception/*Util/枚举）；方法命名；变量命名；常量命名；代码格式；导入规范；注释规范；异常处理规范；日志规范；Lombok 使用；构造器注入；避免魔法值；空值处理 |
| **常量与常量组使用（避免魔法值）** | `08-constants-usage.md` | ⭐ 常量 vs 常量组；常量类全大写定义；常量组必须定义成 DisplayableEnum 枚举；`GET /constants` + `StockApp.loadConstants` 用法；engine Schema 字面量前端集中维护；禁止事项与自查清单 |
| Spring Boot 开发、分层架构、依赖注入 | `02-spring-boot-best-practices.md` | 分层架构（Controller→Service→Mapper）；依赖注入（构造器注入+Lombok）；配置管理；Controller 规范（统一返回 ApiResponse）；Service 规范（接口+impl/事务管理）；AOP 使用；缓存规范；定时任务规范；全局异常处理；Maven 依赖管理（starter优先/版本统一/范围管理/排除冲突/不重复造轮子） |
| 数据库设计、SQL、MyBatis-Plus | `03-database-design.md` | 表设计（命名/字段/类型/主键）；索引设计（原则/类型/命名/常用示例）；SQL 编写（查询/插入/避免N+1）；MyBatis-Plus 使用（实体类/Mapper/Service/QueryWrapper/批量操作）；事务规范；数据迁移；SQLite 特定注意事项（WAL模式/分页/UPSERT） |
| API 设计、RESTful、接口规范 | `04-api-design.md` | RESTful 设计原则（资源导向/HTTP方法语义/URL层级）；URL 命名规范（kebab-case/无/api前缀）；统一返回格式（ApiResponse）；错误码设计；分页规范；参数校验；Controller 规范；跨系统接口规范（watcher↔engine）；参数对象化与类型规范（>5个参数必须封装/禁止Map用DTO/命名规范） |
| Java 性能优化 | `05-java-performance.md` | Java 性能优化 |
| Java 安全开发 | `06-java-security.md` | Java 安全开发 |
| Java 代码质量 | `07-java-code-quality.md` | Java 代码质量 |

### 4.2 前端规范（`frontend/`）

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| HTML/CSS 风格 | `01-html-css-style.md` | HTML/CSS 风格 |
| JavaScript 风格 | `02-javascript-style.md` | JavaScript 风格 |
| Thymeleaf 最佳实践 | `03-thymeleaf-best-practices.md` | Thymeleaf 最佳实践 |
| ECharts 最佳实践 | `04-echarts-best-practices.md` | ECharts 最佳实践 |
| 前端性能优化 | `05-frontend-performance.md` | 前端性能优化 |
| 前端安全 | `06-frontend-security.md` | 前端安全 |

### 4.3 业务指南（`business/`）

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| 认证、权限相关 | `01-auth.md` | 认证相关 |
| **对接 Tushare 新接口** | `02-tushare-integration-guide.md` | ⭐ 完整指南：步骤概览→定义DTO→注册枚举→TushareClient方法→配置限流→数据库层→Service层→Controller层→接入初始化流程→接入定时任务→配置Mapper扫描→测试验证；Checklist；参考实现对照 |

---

## 五、stock-engine（Python 计算服务）规范

路径：`.trae/rules/stock-engine/`

### 5.1 Python 编码规范（`python/`）

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| **搭 Python 环境、启动 engine** | `00-environment-setup.md` | ⭐ 必看：环境约定（conda/stock环境/Python3.12）；环境搭建（创建环境/安装依赖）；启动服务（启动脚本/手动启动）；与Java联调；环境变量；常见问题；本机配置参考 |
| 写 Python 代码、代码风格 | `01-python-coding-style.md` | 命名规范（模块/类/函数/变量/常量/私有成员）；代码格式（缩进/行宽/空行/导入）；注释与文档字符串；类型注解；异常处理（自定义异常/抛出与捕获）；日志规范；文件结构 |
| FastAPI 开发、API 设计 | `02-fastapi-best-practices.md` | 应用架构（分层/入口main.py）；路由设计（APIRouter/URL命名/HTTP方法）；请求与响应（Pydantic模型/统一响应格式/状态码）；依赖注入；异常处理；配置管理；异步编程；API文档 |
| Pandas 性能优化 | `03-pandas-performance.md` | Pandas 性能优化 |
| 量化开发 | `04-quant-coding.md` | 量化开发 |
| Python 性能优化 | `05-python-performance.md` | Python 性能优化 |
| Python 安全开发 | `06-python-security.md` | Python 安全开发 |

---

## 六、akquant 框架知识库（回测/策略/指标开发必查）

路径：`.trae/rules/akquant/` —— **akquant 框架用法/方法知识库，回测必查**，经源码核校（版本锁定：akquant 0.2.47）

> 首先看入口：`README.md` —— 30秒上手+最小骨架+与stock-engine关系+超纲能力+参考来源

| 场景 | 文档 | 内容要点 |
|-----|------|---------|
| 快速理解框架全貌、公开API地图 | `01-overview.md` | 框架定位（Rust核心+Python绑定）；公开API导出地图（回测入口/结果/策略/下单辅助/指标/优化/可视化/数据工具）；A股回测最小调用链；三种写策略的方式（类式/实例式/函数式）；与stock-engine的调用边界 |
| **把watcher的K线传给akquant（数据输入）** | `02-data-input.md` | ⭐ 核心衔接：data接受的五种形态；DataFrame的硬性要求（DatetimeIndex/OHLCV列）；单标的vs多标的；List[Bar]形态；数据辅助函数；**watcher kline_data(list[dict]) → akquant DataFrame 转换配方**；数据预处理注意 |
| **写策略（on_bar/下单/持仓/取历史）** | `03-strategy-api.md` | 策略编写 |
| **跑回测（run_backtest参数/佣金/滑点/T+1）** | `04-backtest-run.md` | 回测运行 |
| **取结果（指标/权益曲线/trades/序列化返回watcher）** | `05-result-metrics.md` | 结果指标 |
| 参数寻优、Walk-forward滚动验证 | `06-optimization.md` | 参数优化 |
| **技术指标速查（MA/MACD/RSI/KDJ/BOLL/ATR）** | `07-talib-indicators.md` | talib 指标 |
| **可直接落地的策略配方** | `08-recipes-stock-engine.md` | 策略配方 |
| 防坑清单（KDJ/MA/T+1/NaN/印花税） | `09-pitfalls-conventions.md` | 防坑清单 |

---

## 八、规则目录结构速览

```
.trae/rules/
├── general/                          # 通用规范
│   ├── 01-development-workflow.md
│   ├── 02-git-commit.md
│   ├── 03-code-review.md
│   ├── 04-testing.md
│   ├── 05-performance-general.md
│   └── 06-security-general.md
├── stock-watcher/
│   ├── java/                         # Java 后端规范
│   │   ├── 01-java-coding-style.md
│   │   ├── 02-spring-boot-best-practices.md
│   │   ├── 03-database-design.md
│   │   ├── 04-api-design.md
│   │   ├── 05-java-performance.md
│   │   ├── 06-java-security.md
│   │   ├── 07-java-code-quality.md
│   │   └── 08-constants-usage.md
│   ├── frontend/                     # 前端规范
│   │   ├── 01-html-css-style.md
│   │   ├── 02-javascript-style.md
│   │   ├── 03-thymeleaf-best-practices.md
│   │   ├── 04-echarts-best-practices.md
│   │   ├── 05-frontend-performance.md
│   │   └── 06-frontend-security.md
│   └── business/                     # 业务指南
│       ├── 01-auth.md
│       └── 02-tushare-integration-guide.md
├── stock-engine/
│   └── python/                       # Python 计算服务规范
│       ├── 00-environment-setup.md
│       ├── 01-python-coding-style.md
│       ├── 02-fastapi-best-practices.md
│       ├── 03-pandas-performance.md
│       ├── 04-quant-coding.md
│       ├── 05-python-performance.md
│       └── 06-python-security.md
└── akquant/                          # akquant 框架知识库 ⭐
    ├── README.md                     # 入口必读
    ├── 01-overview.md
    ├── 02-data-input.md
    ├── 03-strategy-api.md
    ├── 04-backtest-run.md
    ├── 05-result-metrics.md
    ├── 06-optimization.md
    ├── 07-talib-indicators.md
    ├── 08-recipes-stock-engine.md
    └── 09-pitfalls-conventions.md
```
