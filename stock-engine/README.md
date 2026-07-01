# 股票分析系统 - Python计算服务 (`stock-engine`)

本模块是**个人本地化股票分析系统**的 Python 计算服务，基于 **Java + Python 混合架构**设计。
Java 业务服务位于 `stock-watcher/`，两个项目合并在同一仓库中，**共享根目录统一知识库**。

## 项目定位

本 Python 服务专注于**量化计算**，充分发挥 Python 在金融数据领域的生态优势：
- 🧮 **量化计算**：基于 AKQuant 高性能引擎进行技术指标计算与策略回测
- 🔌 **API 服务**：通过 FastAPI 提供标准化 REST 接口，供 Java 业务服务（`stock-watcher`）调用
- ⚡ **轻量高效**：独立部署，无需重型中间件，一键启动即可使用

> **核心原则**：优先复用 AKQuant 等成熟开源工具，避免重复造轮子。
> **开发时必读**：所有规范文档已统一迁移到**项目根目录**，详见 README 底部「统一知识库」章节。

## 架构说明

```
┌─────────────────────────────────────────────────┐
│  Java业务服务 (SpringBoot)                       │
│  - 用户交互、任务调度、数据存储、数据采集(Tushare) │
│  - 通过HTTP调用Python服务                        │
└──────────────────┬──────────────────────────────┘
                   │ HTTP/JSON
┌──────────────────▼──────────────────────────────┐
│  Python计算服务 (本项目)                         │
│  - FastAPI + AKQuant                             │
│  - 技术指标计算、因子计算、策略回测              │
└─────────────────────────────────────────────────┘
```

### 职责边界

| 功能 | Python服务 | Java服务 |
|------|-----------|----------|
| 数据获取 | ❌ | ✅ 通过 Tushare 采集 |
| 技术指标计算 | ✅ 基于 AKQuant | ❌ |
| 因子计算 | ✅ 基于新协议 /python/v1/compute | ❌ |
| 策略回测 | ✅ 基于 AKQuant 引擎 | ❌ |
| 数据库写入 | ❌ 仅返回数据 | ✅ 独占写入 SQLite |
| 缓存管理 | ❌ | ✅ Caffeine 本地缓存 |
| 定时任务 | ❌ | ✅ Quartz 调度 |
| 用户界面 | ❌ | ✅ Thymeleaf 前端 |

## 技术栈

| 技术 | 版本 | 核心作用 |
|------|------|----------|
| **FastAPI** | 0.115.7 | 高性能 Web 框架，自动生成交互式 API 文档 |
| **Uvicorn** | 0.41.0 | ASGI 服务器 |
| **AKQuant** | 0.2.34 | 量化计算（指标计算/回测），其 `akquant.talib` 与 `talib` API 对齐 |
| **Pandas** | 3.0.3 | 数据处理与转换 |
| **NumPy** | 2.4.5 | 数值计算基础 |
| **Pydantic** | 2.13.4 | 数据验证与序列化 |

## 核心功能模块

### 1. 因子计算模块 (`services/factor/`) — 新协议

Java 持有因子定义，Python 做无状态数值计算。统一入口 `/python/v1/compute`。

**设计原则**：
- Java 端定义因子（factorKey、参数、输出列），Python 按名计算数组
- 支持 20+ 标准因子：MA/EMA/MACD/RSI/BOLL/KDJ/ADX/ATR/OBV 等
- 统一使用 `PythonApiResponse` 返回，`code=0` 成功

### 2. 技术指标计算模块 (`services/indicator/`)

接收 K 线数据作为输入，使用 AKQuant 内置指标函数计算：
- MACD、KDJ、RSI 等常用技术指标
- 不手动实现数学公式，直接调用封装好的计算函数
- 返回带技术指标的完整 K 线数据

### 3. 策略回测模块 (`services/backtest/`)

接收 K 线历史数据作为输入，执行策略回测：
- 支持自定义策略回测（双均线、MACD 等）
- 输出回测结果（年化收益、最大回撤、夏普比率等）

**开发原则**：
- ✅ 优先调用 AKQuant 封装好的指标计算函数
- ✅ 充分利用 Pandas 进行数据转换和聚合
- ✅ 所有功能接收数据作为输入，不直接访问数据库

## 项目结构

```
stock-engine/
├── main.py                 # 服务入口文件
├── config.py               # 全局配置
├── requirements.txt        # 依赖列表
├── .env.example            # 环境变量示例
├── core/                   # 核心模块
│   ├── logger.py           # 日志配置
│   └── exceptions.py       # 自定义异常定义
├── api/                    # API 路由层（负责接口暴露与参数验证）
│   └── v1/                 # API v1 版本
│       ├── quote.py        # 技术指标计算相关接口
│       ├── backtest.py     # 策略回测相关接口
│       └── compute.py      # 新因子计算接口 (/python/v1/*)
├── services/               # 业务服务层（核心业务逻辑实现）
│   ├── indicator/          # 技术指标计算服务
│   │   └── tech_indicator.py
│   ├── backtest/           # 策略回测服务
│   │   └── strategy_runner.py
│   └── factor/             # 因子计算服务（新协议）
│       ├── protocol.py     # Java-Python 协议定义
│       └── compute/
│           ├── compute_service.py
│           └── indicator_provider.py
├── models/                 # 数据模型层
│   ├── domain/
│   └── schemas/            # Pydantic 请求/响应模型
└── tests/                  # 测试用例
    ├── test_compute_smoke.py
    └── test_provider_smoke.py
```

> **重要**：本项目文档已**不再**分散在 `stock-engine/doc/`，统一迁移到**项目根目录**：
> - AI 开发规则：`../.trae/rules/stock-engine/`
> - 设计文档：`../sdlc/design/`

## 快速开始

### 环境要求
- **Conda 环境**：`stock`
- **Python**：3.12+
- **依赖**：akquant 0.2.34 / fastapi 0.115.7 / uvicorn 0.41.0 / pandas 3.0.3 / numpy 2.4.5 / pydantic 2.13.4
- JDK 21+（仅用于 Java 业务服务协同工作）

### 1. 激活环境 & 安装依赖
```bash
# 激活本项目专用 conda 环境
conda activate stock

# 进入本项目目录
cd stock-engine

# 安装/核验依赖
pip install -r requirements.txt
```

### 2. 启动服务
```bash
uvicorn main:app --host 127.0.0.1 --port 8000
```

### 3. 验证服务

- **服务状态**：访问 http://127.0.0.1:8000
- **API文档**：访问 http://127.0.0.1:8000/docs
- **健康检查**：访问 http://127.0.0.1:8000/python/v1/health
- **因子计算核验**：`curl -X POST http://127.0.0.1:8000/python/v1/compute ...`

### 4. 与 Java 服务协同工作

Java 服务 `stock-watcher` 通过 `PythonComputeClient` 默认调用 `http://127.0.0.1:8000/python/v1/compute`。如需修改 Python 服务地址，在 Java `application.yml` 中调整 `python.compute.url`。

```bash
# 启动顺序
# 1) Python 计算服务
conda activate stock && cd stock-engine && uvicorn main:app --host 127.0.0.1 --port 8000

# 2) Java 业务服务（另一个终端）
cd stock-watcher && mvn spring-boot:run
```

## 开发指南

### 添加新的技术指标

1. 在 `services/indicator/tech_indicator.py` 中添加计算函数
2. 优先使用 AKQuant 内置指标函数
3. 如需自定义指标，确保经过充分测试

### 添加新的量化策略

1. 在 `services/backtest/` 下实现策略逻辑
2. 在 `strategy_runner.py` 中注册策略名称与实现
3. 返回标准化的回测结果（收益曲线、评价指标等）

### 添加新的 API 接口

1. **定义模型**：在 `models/schemas/` 下定义请求/响应 Pydantic 模型
2. **实现业务**：在 `services/` 下实现业务逻辑
3. **注册路由**：在 `api/v1/` 下创建或修改路由文件
4. **挂载路由**：在 `main.py` 中注册新路由

## 注意事项

### ⚠️ 重要限制

1. **不操作数据库**：Python 服务不直接读写 SQLite 数据库，所有数据通过 API 返回给 Java 服务处理
2. **数据一致性**：由 Java 服务保证数据库事务一致性和并发控制

### ✅ 最佳实践

1. **复用开源工具**：优先使用 AKQuant 现成功能，避免重复实现
2. **异常处理**：网络请求失败自动重试，保证计算稳定性
3. **日志记录**：关键操作记录详细日志，便于问题排查
4. **参数验证**：使用 Pydantic 模型进行严格的请求参数验证

### 🔧 性能优化

1. **无状态设计**：Python 服务无状态，便于水平扩展
2. **批量操作**：支持批量因子计算，减少网络请求次数
3. **AKQuant 引擎**：基于 Rust 的高性能计算内核

## 常见问题

### Q: Python 服务启动失败？
A: 检查端口 8000 是否被占用，确认依赖已正确安装（`pip install -r requirements.txt`）

### Q: 回测速度很慢？
A: AKQuant 基于 Rust 引擎，性能已经优化。如仍较慢，可考虑减少回测时间范围或降低数据粒度

### Q: 如何与 Java 服务联调？
A: 确保 Python 服务先启动，然后在 Java 配置文件中设置正确的 Python 服务地址（默认 `http://127.0.0.1:8000`）

## 统一知识库（重要！）

> 项目文档已统一迁移到**项目根目录**，不再分散在各子项目中。

### AI 开发规则（`../.trae/rules/`）
| 目录 | 说明 |
|------|------|
| `../.trae/rules/stock-engine/` | **Python 项目开发规范**（FastAPI 路由、Pydantic 模型、AKQuant 量化计算等） |
| `../.trae/rules/stock-watcher/` | Java 项目开发规范（Tushare 集成、MyBatis-Plus、缓存策略等） |
| `../CLAUDE.md` | 项目根目录 AI 总览（双项目架构、命名约定、混合架构指南） |

### 设计与需求文档（`../sdlc/`）
| 目录 | 说明 |
|------|------|
| `../sdlc/design/` | 设计文档（K 线计算、因子库、策略管理、回测中心、选股模块等） |
| `../sdlc/prd/` | 需求文档（V1.0 PRD、因子库 PRD、策略管理 PRD、回测中心 PRD、选股模块 PRD 等） |

### Python 项目核心规则文件速查

| 规则文件 | 说明 |
|---------|------|
| 量化计算规范 | `.trae/rules/stock-engine/business/01-quant-calculation.md` |
| Python 编码风格 | `.trae/rules/stock-engine/python/01-python-coding-style.md` |
| FastAPI 最佳实践 | `.trae/rules/stock-engine/python/02-fastapi-best-practices.md` |
| Pandas 性能优化 | `.trae/rules/stock-engine/python/03-pandas-performance.md` |

---

## 外部参考资源

- [AKQuant 官方文档](https://akquant.akfamily.xyz)
- [AKQuant 源码仓库](https://github.com/akfamily/akquant)
- [FastAPI 官方文档](https://fastapi.tiangolo.com/)

## 许可证

本项目遵循开源协议，仅供个人学习研究使用。

## 核心 API 接口

详细文档见 http://127.0.0.1:8000/docs

### 因子计算接口（新协议）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/python/v1/health` | GET | 健康检查 |
| `/python/v1/registry` | GET | 当前支持的 indicator 清单 |
| `/python/v1/compute` | POST | 批量计算一组因子 |

### 技术指标计算接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/quote/calculate_indicators` | POST | 为 K 线数据计算技术指标 |

### 策略回测接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/backtest/run` | POST | 执行量化策略回测 |
