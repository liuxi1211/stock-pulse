#!/bin/bash
# stock-engine 启动脚本 (macOS / Linux)

# 默认 Conda 路径（可根据实际情况修改）
CONDA_PATH="$HOME/miniforge3/bin/conda"
CONDA_ENV="stock"
HOST="127.0.0.1"
PORT="8085"

echo "========================================"
echo "  Stock Engine - Python 计算服务"
echo "========================================"
echo ""

# 检查 conda 是否存在
if [ ! -f "$CONDA_PATH" ]; then
    # 尝试自动查找 conda
    if command -v conda &> /dev/null; then
        CONDA_PATH="conda"
    else
        echo "[ERROR] Conda not found at: $CONDA_PATH"
        echo "Please edit this script and set CONDA_PATH correctly."
        exit 1
    fi
fi

echo "[INFO] Using Conda: $CONDA_PATH"
echo "[INFO] Environment: $CONDA_ENV"
echo "[INFO] Listening on: $HOST:$PORT"
echo ""

# 切换到脚本所在目录
cd "$(dirname "$0")" || exit 1

echo "[INFO] Starting service..."
echo ""

# 启动服务
"$CONDA_PATH" run --no-capture-output -n "$CONDA_ENV" python -m uvicorn main:app --host "$HOST" --port "$PORT"
