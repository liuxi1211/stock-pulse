@echo off
REM stock-engine 启动脚本 (Windows)
REM 自动使用当前 shell 中可用的 Conda

set CONDA_ENV=stock
set HOST=127.0.0.1
set PORT=8085

echo ========================================
echo   Stock Engine - Python 计算服务
echo ========================================
echo.

REM 优先使用激活环境提供的 CONDA_EXE，否则从 PATH 查找
if defined CONDA_EXE (
    set "CONDA_PATH=%CONDA_EXE%"
) else (
    for /f "delims=" %%I in ('where conda 2^>nul') do if not defined CONDA_PATH set "CONDA_PATH=%%I"
)

if not defined CONDA_PATH (
    echo [ERROR] Conda was not found. Add Conda to PATH or initialize it first.
    pause
    exit /b 1
)

echo [INFO] Using Conda: %CONDA_PATH%
echo [INFO] Environment: %CONDA_ENV%
echo [INFO] Listening on: %HOST%:%PORT%
echo.

REM 检查并切换到脚本所在目录
cd /d "%~dp0"

echo [INFO] Starting service...
echo.

REM 启动服务；直接输出启动异常
"%CONDA_PATH%" run --no-capture-output -n %CONDA_ENV% python -m uvicorn main:app --host %HOST% --port %PORT%

pause
