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
| 技术指标计算 | ⏳ 待重写（基于 AKQuant） | ❌ |
| 因子计算 | ⏳ 待重写（按统一 Schema factorKey 体系） | ❌ |
| 策略回测 | ⏳ 待重写（基于 AKQuant 引擎） | ❌ |
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

## 核心功能模块（均待重写）

> ⚠️ 原有 `services/factor/`、`services/indicator/`、`services/backtest/` 三个业务模块为废案，**已全部清空**，待基于「统一策略配置 Schema」+ akquant 知识库重写。

| 模块（待重写） | 重写依据 |
|---|---|
| 因子计算（`services/factor/`） | 统一 Schema §4.5 factorKey 体系（20 个标准因子）；走 `akquant.talib`，`compute(name, inputs, **params)` 签名 |
| 技术指标（`services/indicator/`） | akquant 知识库 07-talib-indicators；**禁止 pandas 自行实现** |
| 策略回测（`services/backtest/`） | 统一 Schema 字段 → `aq.run_backtest(...)` 映射（§3.4/§8）；akquant 知识库 04-backtest-run / 08-recipes |

**通用开发原则**（重写时遵循）：
- ✅ 优先调用 AKQuant 封装好的指标计算函数，不自行实现算法
- ✅ 所有功能接收数据作为输入，**不直接访问数据库**（硬约束）

## 项目结构

```
stock-engine/  （业务层待重写，当前仅骨架）
├── main.py                 # 服务入口（路由注册待业务层重写后挂载）
├── config.py               # 全局配置
├── requirements.txt        # 依赖列表
├── .env.example            # 环境变量示例
├── core/                   # 核心模块
│   ├── logger.py           # 日志配置
│   └── exceptions.py       # 自定义异常定义
├── api/                    # API 路由层（仅留 __init__，待重写）
├── services/               # 业务服务层（仅留 __init__，待重写：indicator/factor/backtest）
├── models/                 # 数据模型层（仅留 __init__，待重写）
│   ├── domain/
│   └── schemas/
└── tests/                  # 测试用例（待重写）
```

> **重要**：本项目文档已**不再**分散在 `stock-engine/doc/`，统一迁移到**项目根目录**：
> - AI 开发规则：`../.trae/rules/stock-engine/`
> - 设计文档：`../sdlc/prd/`（003 选股与回测边界设计·004 统一策略配置Schema）+ `../.trae/rules/akquant/`

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
uvicorn main:app --host 127.0.0.1 --port 8085
```

### 3. 验证服务

- **服务状态**：访问 http://127.0.0.1:8085
- **API文档**：访问 http://127.0.0.1:8085/docs
- **健康检查**：访问 http://127.0.0.1:8085/python/v1/health

### 4. 与 Java 服务协同工作

Java（`stock-watcher`）与 Python 计算服务的 HTTP 协作层随业务层清空，待按「统一策略配置 Schema」重写；地址配置项为 `python.compute.url`（Java `application.yml`），默认 `http://127.0.0.1:8085`。

```bash
# 启动顺序
# 1) Python 计算服务
conda activate stock && cd stock-engine && uvicorn main:app --host 127.0.0.1 --port 8085

# 2) Java 业务服务（另一个终端）
cd stock-watcher && mvn spring-boot:run
```

## 开发指南

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
A: 检查端口 8085 是否被占用，确认依赖已正确安装（`pip install -r requirements.txt`）

### Q: 回测速度很慢？
A: AKQuant 基于 Rust 引擎，性能已经优化。如仍较慢，可考虑减少回测时间范围或降低数据粒度

### Q: 如何与 Java 服务联调？
A: 确保 Python 服务先启动，然后在 Java 配置文件中设置正确的 Python 服务地址（默认 `http://127.0.0.1:8085`）

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
| `../sdlc/prd/` | 需求/设计文档（001 系统PRD·002 因子库·003 多因子选股中心·004 策略管理 Schema·005 回测中心） |
| `../.trae/rules/akquant/` | akquant 框架用法知识库（回测/策略/指标，源码核校） |

### Python 项目核心规则文件速查

| 规则文件 | 说明 |
|---------|------|
| 量化计算规范 | `../.trae/rules/akquant/`（akquant 用法/指标/回测）+ 统一策略配置 Schema |
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

> 业务接口（因子计算 `/python/v1/compute`、技术指标 `/quote/*`、策略回测 `/backtest/*`）随业务层清空，待重写后重新发布。当前仅基础端点可用：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 服务状态检查 |
| `/health` | GET | 健康检查 |

重写后接口设计见统一 Schema §8（`../sdlc/prd/004-策略管理/统一策略配置Schema.md`）；旧 `02-akquant 交互层架构设计.md` 已删，集成层待重写。
