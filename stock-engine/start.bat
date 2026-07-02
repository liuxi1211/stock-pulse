@echo off
REM stock-engine 启动脚本 (Windows)
REM 使用前请确认 Conda 环境路径是否正确

set CONDA_PATH=D:\javaApp\miniforge\Scripts\conda.exe
set CONDA_ENV=stock
set HOST=127.0.0.1
set PORT=8085

echo ========================================
echo   Stock Engine - Python 计算服务
echo ========================================
echo.

REM 检查 conda 是否存在
if not exist "%CONDA_PATH%" (
    echo [ERROR] Conda not found at: %CONDA_PATH%
    echo Please edit this script and set CONDA_PATH correctly.
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

REM 启动服务
"%CONDA_PATH%" run -n %CONDA_ENV% uvicorn main:app --host %HOST% --port %PORT%

pause
