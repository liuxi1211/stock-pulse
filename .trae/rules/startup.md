---
alwaysApply: false
description: "项目启动手册：一键启动 / 单服务启动 / 端口冲突排查 / 环境前置 / 常见报错。关键词：启动, run.js, 端口, 编译, watcher, engine"
---
# 项目启动手册

> 统一入口：3 个 `run.js` 跨平台脚本（替代旧的 .bat/.sh）。**所有命令在 Windows / macOS / Linux 通用**。
>
> 适用：stock-pulse 全栈（stock-watcher Java + stock-engine Python）

---

## TL;DR - 30 秒一键启动

```bash
node run.js start
```

这一条命令会：① 先编译 + 后台启动 stock-engine（端口 8085）-> 端口探活通过 -> ② 再编译 + 后台启动 stock-watcher（端口 8080）-> 探活通过 -> 退出，两个服务留在后台跑。

启动完成后：
- stock-engine  :  http://127.0.0.1:8085  (API docs: `/docs`, health: `/health`)
- stock-watcher :  http://127.0.0.1:8080  (Swagger: `/swagger-ui.html`, 默认账号 `admin` / `admin123`)

> 💡 **AI 注意**：并不是每次任务都要启动服务。Java 改代码后大部分时候只需要验证编译：
> ```bash
> cd stock-watcher && node run.js compile-dev   # ⚡ 增量编译，比 start 快很多
> ```
> 详见下方 [stock-watcher 命令总表](#单服务stock-watcherrunjs)。

---

## 服务概览

| 服务 | 端口 | 技术栈 | 启动脚本 | 配置端口的位置 |
|---|---|---|---|---|
| stock-engine | 8085 | Python 3.12 / FastAPI / Conda env `stock` | [stock-engine/run.js](../../stock-engine/run.js) | `stock-engine/.env` 的 `PORT` |
| stock-watcher | 8080 | Java 21 / Spring Boot 4.0.6 / Maven | [stock-watcher/run.js](../../stock-watcher/run.js) | `stock-watcher/src/main/resources/application.yml` 的 `server.port` |
| 全栈编排 | - | Node.js | [run.js](../../run.js) | 透传 `--engine-port` / `--watcher-port` 给子脚本 |

**启动顺序**：engine 先 -> watcher 后（watcher 通过 `python.compute.url=http://127.0.0.1:8085` 调 engine）。

---

## 命令总表

### 全栈编排：`stock-pulse/run.js`（根）

```bash
node run.js <command>
```

| 命令 | 作用 |
|---|---|
| `start` | 一键启动两个服务（engine -> watcher，后台 + 日志） |
| `start-dev` | 一键启动两个服务（dev 模式，两个都开热重载） |
| `start-engine` | 仅启动 stock-engine |
| `start-watcher` | 仅启动 stock-watcher |
| `status` | 查看两个服务的端口 / PID / 健康状态 |
| `stop` | 停止两个服务 |
| `check-env` | 两个服务的环境检查（JDK / Conda / Maven / secret / 端口） |
| `logs watcher` \| `logs engine` | tail 某个服务的日志 |
| `help` | 显示帮助 |

选项：`--engine-port=N` / `--watcher-port=N` 临时覆盖端口。

### 单服务：`stock-watcher/run.js`

> ⭐ **Java 开发高频操作是编译，不是启动**。改完代码先用 `compile-dev` 验证编译，比启动快得多。

```bash
cd stock-watcher
node run.js <command>
```

#### 编译相关命令（最高频）

| 命令 | 作用 | 何时用 |
|---|---|---|
| `compile-dev` | ⚡ **增量编译**（跳过 clean，只编译改动），**最快** | 日常开发改完代码，验证是否能编译通过 |
| `compile` | 全量 `clean compile`，重置 target 目录 | 打包前 / 怀疑缓存问题 / CI 验证 |
| `test` | 运行单元测试 | 改了业务逻辑、写完新测试 |
| `package` | `clean package -DskipTests`，打 jar | 部署 / 交付 |
| `package-all` | `clean package`（含测试） | 发版前完整验证 |

#### 启动相关命令

| 命令 | 作用 |
|---|---|
| `start` | 编译 + 后台启动（默认）+ 日志文件 |
| `start-dev` | 编译 + 开发模式（dev profile + 热重载） |
| `start-foreground` | 编译 + 前台启动（阻塞终端，Ctrl+C 停止） |
| `status` | 查看端口 / PID / 健康 |
| `stop` | 停止服务 |
| `check-env` | 环境检查（JDK21 / Maven Wrapper / secret / 端口） |
| `logs` | tail 日志文件 |
| `help` | 帮助 |

选项：`--port=N` 临时覆盖端口，`--foreground` 前台运行，`--clean`/`--full` 强制 start 前做 clean compile（默认增量）。

> 💡 **AI 工作流建议**：改 Java 代码后第一步 `node run.js compile-dev`，编译通过再考虑 `start-dev` 启动验证。不要每次改代码都走 `start`（含启动，慢）。

### 单服务：`stock-engine/run.js`

```bash
cd stock-engine
node run.js <command>
```

| 命令 | 作用 |
|---|---|
| `start` | 后台启动 + 日志文件 |
| `start-dev` | 开发模式（uvicorn --reload 热重载） |
| `start-foreground` | 前台启动 |
| `install` | `pip install -r requirements.txt` |
| `test` | 运行 pytest |
| `shell` | 进入 conda env `stock` 的子 shell |
| `status` | 查看端口 / PID / 健康 |
| `stop` | 停止服务 |
| `check-env` | 环境检查（Conda / env / 依赖 / 端口） |
| `logs` | tail 日志文件 |
| `help` | 帮助 |

选项：`--port=N` 临时覆盖端口，`--foreground` 前台运行。

---

## 端口配置与冲突排查

### 端口配置的「单一入口」

| 想改的端口 | 改这里 | run.js 行为 |
|---|---|---|
| watcher 8080 | [stock-watcher/src/main/resources/application.yml](../../stock-watcher/src/main/resources/application.yml) 的 `server.port` | 自动读取，不硬编码 |
| engine 8085 | [stock-engine/.env](../../stock-engine/.env) 的 `PORT`（不存在时回退到 `config.py` 默认 8085） | 自动读取，不硬编码 |
| 临时覆盖 | 命令行 `--port=N` | 优先级最高，**不改配置文件** |

> ⚠️ **不要**在 `run.js` 里改端口常量，那是默认回退值，实际端口从配置文件读。

### 端口冲突的标准排查流程

当出现 `[ERROR] Port :8080 is already in use (PID=1234)` 时：

```bash
# 1. 看是谁在用
node stock-watcher/run.js status
# 输出示例：
# [status] stock-watcher running on :8080  PID=1234
#   health: HTTP 200  {"components":...}

# 2a. 如果是上次没退干净的本服务，直接 stop
node stock-watcher/run.js stop

# 2b. 如果是别的进程占着，换端口启动
node stock-watcher/run.js start --port=8090
```

全栈脚本同理：

```bash
node run.js start --engine-port=8095 --watcher-port=8090
```

### 手动排查（备选）

```bash
# Windows
netstat -ano | findstr :8080
# macOS / Linux
lsof -i :8080
```

---

## 环境前置

### 必装工具

| 工具 | 版本 | 用途 | 检查方式 |
|---|---|---|---|
| JDK | **21** | stock-watcher 编译运行 | `java -version` |
| Node.js | 16+ | 跑 run.js（跨平台编排） | `node -v` |
| Conda | Miniforge / Miniconda / Anaconda | stock-engine 用 conda env `stock` | `conda --version` |
| MySQL | 8+ | watcher 默认数据源（或用 sqlite profile） | - |
| Tushare Pro | - | 行情数据 token | 注册 https://tushare.pro |

> Maven **无需全局安装**，项目自带 `mvnw` / `mvnw.cmd` Wrapper。

### 首次搭建

```bash
# 1. 创建 conda env 并装 engine 依赖
conda create -n stock python=3.12 -y
node stock-engine/run.js install

# 2. 配置 watcher secret
cd stock-watcher/src/main/resources
cp application-secret.properties.template application-secret.properties
#   编辑 application-secret.properties，填：
#     tushare.token=你的_tushare_pro_token
#     db.url=jdbc:mysql://localhost:3306/stock?...
#     db.username=...
#     db.password=...

# 3. 全栈环境自检
cd ../../..
node run.js check-env
```

`check-env` 全部 `[OK]` 即可启动。

---

## 常见报错速查

| 报错 | 原因 | 解决 |
|---|---|---|
| `Port :8080 is already in use (PID=1234)` | 上次服务没退干净 / 别的进程占用 | `node run.js stop` 或 `--port=` 换端口 |
| `[ERROR] Cannot find javac` | JDK 没装 / 不在 PATH | 装 JDK 21，重开终端 |
| `Java 21 required. Found: ... 17 ...` | JDK 版本不对 | 切到 JDK 21 |
| `Maven wrapper not found` | `mvnw.cmd` 缺失 | 检查 stock-watcher 目录完整 |
| `application-secret.properties missing` | 没配置 secret | 从 template 拷贝并编辑 |
| `Cannot find Conda` | Conda 没装 / 不在 PATH | 装 Miniforge，重开终端 |
| `Conda env 'stock' missing` | 没创建 conda env | `conda create -n stock python=3.12 -y` 后 `node stock-engine/run.js install` |
| `stock-engine did not start within timeout` | uvicorn 启动失败 | `node stock-engine/run.js logs` 看日志 |
| `stock-watcher did not start within timeout` | Spring Boot 启动失败 | `node stock-watcher/run.js logs` 看日志 |
| MySQL 连接失败 | `db.url` / `db.username` / `db.password` 没配 | 检查 `application-secret.properties` |
| Conda 启动很慢 | 首次 `conda run` 要初始化环境 | 正常现象，后续启动会快 |

---

## 日志与进程文件

| 文件 | 内容 |
|---|---|
| `stock-pulse/.logs/watcher.log` | watcher stdout/stderr |
| `stock-pulse/.logs/engine.log` | engine stdout/stderr |
| `stock-pulse/.logs/watcher.pid` | watcher 后台进程 PID |
| `stock-pulse/.logs/engine.pid` | engine 后台进程 PID |

实时 tail 日志：

```bash
node run.js logs watcher    # 实时 tail watcher 日志
node run.js logs engine     # 实时 tail engine 日志
```

---

## run.js 工作原理（给 AI 看，避免重复造轮子）

3 个 `run.js` 都是 Node.js 脚本，跨平台（Windows / macOS / Linux 同一套命令）：

1. **路径自洽**：用 `__dirname` 定位各自项目根，不再依赖 5 层相对路径
2. **配置读取**：watcher run.js 解析 `application.yml` 的 `server.port`；engine run.js 解析 `.env` 的 `HOST`/`PORT`，不硬编码
3. **后台运行**：默认 `spawn(detached: true) + unref()`，子进程脱离父进程独立运行，stdout/stderr 写入 `stock-pulse/.logs/*.log`
4. **端口探活**：启动后轮询 TCP 端口直到监听（超时 engine 60s / watcher 120s），监听后才返回 exit 0
5. **健康探活**：status 命令依次尝试 `/actuator/health` -> `/health` -> `/`
6. **PID 查找**：Windows `netstat -ano | findstr :PORT`，Unix `lsof -i :PORT -t`
7. **JDK 发现**：`where javac` -> `java -XshowSettings:properties` -> Windows 注册表 `HKLM\SOFTWARE\JavaSoft\JDK`（绕开 Oracle javapath 重定向器）
8. **Conda 发现**：`CONDA_EXE` 环境变量 -> `where conda` -> 常见安装路径扫描
9. **全栈编排**：根 run.js 用 `spawnSync('node', [子脚本, ...])` 调子 run.js，等子脚本 exit 0 后再启动下一个

### ⚠️ 不要做

- 不要再用 `mvn spring-boot:run` 直接跑（绕过端口检测、日志归档、健康探活、JDK 自动发现）
- 不要再用 `conda run -n stock python -m uvicorn ...` 直接跑（同上）
- 不要新建 .bat/.sh 脚本（已有 run.js 跨平台方案，避免平台重复实现）
- 不要硬编码 8080 / 8085 端口（改 `application.yml` / `.env` 即可，run.js 自动读）
- 不要把启动相关文档拆到两份 00-environment-setup.md（已合并到本 startup.md）

---

## 相关文档

- 项目总览 / 业务模块 / 路线图 -> [根 README.md](../../README.md)
- AI 知识库入口 / 硬约束 -> [根 CLAUDE.md](../../CLAUDE.md)
- Java 编码规范 -> [.trae/rules/stock-watcher/java/01-java-coding-style.md](./stock-watcher/java/01-java-coding-style.md)
- Python 编码规范 -> [.trae/rules/stock-engine/python/01-python-coding-style.md](./stock-engine/python/01-python-coding-style.md)
- akquant 框架用法 -> [.trae/rules/akquant/README.md](./akquant/README.md)
