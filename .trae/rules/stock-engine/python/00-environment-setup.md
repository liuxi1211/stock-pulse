---
alwaysApply: false
description: "stock-engine Python 计算服务的环境配置与启动：Conda/stock 环境、依赖安装、服务启动、与 Java 联调。关键词：环境, conda, miniforge, 启动, 依赖, 安装, stock环境, 联调, uvicorn, 跨平台"
---
# Python 环境配置与项目启动指南

> 规范级别：MUST　|　适用：stock-engine（Python 计算服务）　|　平台：Windows / macOS / Linux

## 1. 环境约定

| 约定项 | 值 | 说明 |
|--------|-----|------|
| 环境管理器 | Miniforge / Miniconda / Anaconda | 任一 Conda 发行版 |
| 项目环境名 | `stock` | 所有机器统一用此名（不写死路径） |
| Python 版本 | 3.12+ | 推荐 3.12（akquant 兼容性优先） |
| 项目目录 | `stock-engine/` | 仓库根目录下 |

**设计原则**：约定环境名而非路径，实现跨平台兼容。

> Conda 未加入 PATH 时：优先用 `conda run -n stock ...` 免激活执行；或先初始化——`source ~/miniforge3/etc/profile.d/conda.sh`（macOS/Linux）、`& "$env:USERPROFILE\miniforge3\shell\condabin\conda-hook.ps1"`（Windows PowerShell）。

## 2. 环境搭建

```bash
# 创建环境（首次）
conda create -n stock python=3.12 -y
conda activate stock

# 安装依赖（清单与版本以 requirements.txt 为准，不在本文维护）
cd stock-engine
pip install -r requirements.txt

# 验证核心依赖
python -c "import fastapi,uvicorn,pandas,numpy,akshare; print('OK')"
python -c "import akquant; print('akquant', akquant.__version__)"
```

> 安装新依赖后，务必更新并提交 `requirements.txt`（`pip freeze > requirements.txt`），保持团队环境一致。

## 3. 启动服务

```bash
conda activate stock
cd stock-engine
uvicorn main:app --host 127.0.0.1 --port 8000          # 默认端口
uvicorn main:app --host 127.0.0.1 --port 8000 --reload  # 开发热重载
```

启动后验证：
- http://127.0.0.1:8000/docs — Swagger API 文档
- http://127.0.0.1:8000/health — 健康检查

> 端口被占用时换端口（如 8001），并同步修改 Java 侧 `python.compute.url`（见 §4）。

## 4. 与 Java 服务联调

**启动顺序**：先 Python（stock-engine），再 Java（stock-watcher）。

Java 通过 `PythonComputeClient` / `FactorGateway` 调用，配置项 `python.compute.url`，默认 `http://127.0.0.1:8000`：

```yaml
python:
  compute:
    url: http://127.0.0.1:8000
```

**联调检查**：Python 已起且 `/docs` 可访问 → Java 的 `python.compute.url` 与实际端口一致 → Java 日志无连接错误 → 触发一次因子计算验证链路。

## 5. 环境变量

通过 `.env` 管理（模板 `.env.example`，首次复制一份）。可用变量：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `HOST` | 127.0.0.1 | 监听地址 |
| `PORT` | 8000 | 监听端口 |
| `LOG_LEVEL` | INFO | 日志级别（DEBUG/INFO/WARNING/ERROR） |
| `DATA_HISTORY_YEARS` | 5 | 数据采集默认历史年数 |

## 6. 常见问题

- **`ModuleNotFoundError`**：未激活 `stock` 或依赖未装 → `conda activate stock` → `pip install -r requirements.txt`。
- **端口占用**（`Address already in use`）：换端口启动并同步改 Java 配置。
- **`conda: command not found`**：Conda 未入 PATH，按 §1 注释初始化，或用 `conda run -n stock ...`。
- **akquant 安装失败**：确认 Python 3.12；必要时装 Rust 工具链后 `pip install akquant==0.2.34`。

## 7. 本机配置参考（Windows，仅本机）

> ⚠️ 仅本台开发机器适用，其他机器忽略本节。

- Miniforge：`D:\javaApp\miniforge`（conda.exe：`D:\javaApp\miniforge\Scripts\conda.exe`）
- stock 环境：`D:\javaApp\miniforge\envs\stock`，Python 3.14.4
- **端口**：8000 被 C-Lodop 打印服务占用，本机用 **8001**，Java 配置改为 `http://127.0.0.1:8001`。
- 本机启动：`cd d:\lcProject\stock-pulse\stock-engine` → `uvicorn main:app --host 127.0.0.1 --port 8001`，验证 http://127.0.0.1:8001/docs。
