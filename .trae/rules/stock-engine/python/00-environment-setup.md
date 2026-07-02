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
| 项目环境名 | `stock` | 所有机器统一用此名 |
| Python 版本 | 3.12+ | 推荐 3.12（akquant 兼容性优先） |
| 项目目录 | `stock-engine/` | 仓库根目录下 |

## 2. 环境搭建

```bash
# 创建环境（首次）
conda create -n stock python=3.12 -y

# 安装依赖
cd stock-engine
conda run -n stock pip install -r requirements.txt

# 验证安装
conda run -n stock python -c "import fastapi,uvicorn,akquant; print('✅ 依赖 OK')"
```

> 安装新依赖后，更新 `requirements.txt`：`conda run -n stock pip freeze > requirements.txt`

## 3. 启动服务

### 方式一：使用启动脚本（推荐）

项目提供了开箱即用的启动脚本，首次使用前请编辑脚本中的 `CONDA_PATH` 为你的实际路径：

| 脚本 | 平台 | 说明 |
|------|------|------|
| `start.bat` | Windows | 普通模式启动 |
| `start-dev.bat` | Windows | 开发模式启动（带热重载） |
| `start.sh` | macOS / Linux | 普通模式启动 |

```bash
# Windows (双击或命令行运行)
start.bat
start-dev.bat

# macOS / Linux
chmod +x start.sh
./start.sh
```

### 方式二：手动启动

```bash
cd stock-engine

# Windows (cmd/PowerShell)
D:\javaApp\miniforge\Scripts\conda.exe run -n stock uvicorn main:app --host 127.0.0.1 --port 8085

# Windows (Git Bash)
/d/javaApp/miniforge/Scripts/conda.exe run -n stock uvicorn main:app --host 127.0.0.1 --port 8085

# macOS/Linux
~/miniforge3/bin/conda run -n stock uvicorn main:app --host 127.0.0.1 --port 8085

# 开发模式（带 --reload 热重载）
conda run -n stock uvicorn main:app --host 127.0.0.1 --port 8085 --reload
```

启动后验证：
- http://127.0.0.1:8085/docs — API 文档
- http://127.0.0.1:8085/health — 健康检查

> **Windows Git Bash 提示**：路径格式为 `/d/...` 而非 `D:\...`

## 4. 与 Java 服务联调

**启动顺序**：先 Python（stock-engine），再 Java（stock-watcher）。

Java 配置 `application.properties`：
```yaml
python:
  compute:
    url: http://127.0.0.1:8085
```

## 5. 环境变量

通过 `.env` 管理（模板 `.env.example`）：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `HOST` | 127.0.0.1 | 监听地址 |
| `PORT` | 8085 | 监听端口 |
| `LOG_LEVEL` | INFO | 日志级别 |

## 6. 常见问题

- **`ModuleNotFoundError`**：依赖未装 → `conda run -n stock pip install -r requirements.txt`
- **端口占用**：终止进程，并重试
- **conda 路径不对**：修改启动脚本中的 `CONDA_PATH`

---

## 本机配置参考（Windows）

> ⚠️ 仅本台开发机器适用

- Miniforge：`D:\javaApp\miniforge`
- conda.exe：`D:\javaApp\miniforge\Scripts\conda.exe`
- stock 环境：`D:\javaApp\miniforge\envs\stock`
- Python：3.14.4
