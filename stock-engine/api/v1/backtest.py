"""回测中心 FastAPI 路由（spec 007-backtest-center T2）。

- ``POST /python/v1/backtest/run``：执行回测，返回统一信封 ``{success, message, data}``；
- ``GET /python/v1/backtest/constants``：返回 broker_profile / sort_metric / 支持范式。

约束：engine 不触库，本模块不含任何数据库驱动 import / 连接 / 路径字面量。
"""
from os import getenv
from typing import Any, Optional

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from services.backtest.runner import BacktestError, run_backtest_engine
from services.strategy.constants import BROKER_PROFILES

router = APIRouter(prefix="/python/v1/backtest", tags=["回测中心"])


# ============================================================
# 请求 / 响应 Pydantic 模型
# ============================================================

class BacktestRunRequest(BaseModel):
    """POST /run 请求体。"""

    strategy_config: dict = Field(
        ...,
        description="策略配置 JSON（StrategyConfigModel 形态）",
        examples=[
            {
                "name": "双均线策略",
                "trading_config": {
                    "signals": {
                        "buy": {
                            "operator": "AND",
                            "conditions": [
                                {
                                    "type": "compare",
                                    "left": {"factor": "MA", "params": {"timeperiod": 5}},
                                    "comparator": ">",
                                    "right": {"factor": "MA", "params": {"timeperiod": 20}},
                                }
                            ],
                        }
                    },
                    "position_sizing": {
                        "method": "order_target_percent",
                        "target": 0.95,
                    },
                },
                "backtest_config": {
                    "initial_cash": 100000,
                    "broker_profile": "cn_stock_miniqmt",
                },
            }
        ],
    )
    kline_data: dict = Field(
        ...,
        description="K 线数据 {symbol: list[dict]}，每条含 date + OHLCV",
        examples=[
            {
                "000001.SZ": [
                    {"date": "2024-01-02", "open": 10.0, "high": 10.2, "low": 9.9, "close": 10.1, "volume": 100000},
                    {"date": "2024-01-03", "open": 10.1, "high": 10.4, "low": 10.0, "close": 10.3, "volume": 120000},
                ]
            }
        ],
    )
    benchmark_data: Optional[list[dict]] = Field(
        None,
        description="基准 K 线（可选，归一化到 1.0 叠加）",
        examples=[[{"date": "2024-01-02", "close": 3000}, {"date": "2024-01-03", "close": 3030}]],
    )


class BacktestRunResponse(BaseModel):
    """POST /run 成功响应（统一信封）。"""

    success: bool = Field(..., description="是否成功")
    message: str = Field(..., description="提示信息")
    data: Optional[dict] = Field(None, description="回测结果（metrics/curves/trades/...）")
    errorCode: Optional[str] = Field(None, description="机器可读错误码（仅 success=false 时有值）")
    code: Optional[int] = Field(None, description="业务码（仅 success=false 时有值）")


class BacktestErrorResponse(BaseModel):
    """POST /run 错误响应。"""

    success: bool = Field(False, description="固定 false")
    message: str = Field(..., description="错误描述")
    code: int = Field(..., description="HTTP 状态码")
    errorCode: str = Field(..., description="机器可读错误码")


class ConstantsResponse(BaseModel):
    """GET /constants 响应。"""

    success: bool = Field(True, description="固定 true")
    data: dict = Field(..., description="常量集合")


# ============================================================
# 路由
# ============================================================

@router.post(
    "/run",
    response_model=BacktestRunResponse,
    summary="执行回测",
    description=(
        "传入策略配置 JSON + K 线数据，执行一次 akquant 回测，返回指标 / 权益曲线 / "
        "交易明细等。支持 signals 信号驱动 / rebalance 多因子调仓 / exit.rules 动态出场 / "
        "exit.bracket 静态与 ATR 动态止损，以及 signals + rebalance 混合范式。"
    ),
    responses={
        200: {"description": "回测完成（无论策略是否命中信号，均返回 200 信封）", "model": BacktestRunResponse},
        400: {"description": "配置非法 / 范式不支持 / 数据非法", "model": BacktestErrorResponse},
        500: {"description": "回测引擎内部错误", "model": BacktestErrorResponse},
    },
)
async def run_backtest(request: Request, payload: BacktestRunRequest) -> BacktestRunResponse:
    # 提取 watcher 只读接口基址（spec 010 缺陷 A 修复）：
    # 优先 HTTP header X-Watcher-Base-Url（按调用注入），回退环境变量 WATCHER_BASE_URL。
    # 仅 rebalance 范式的 csi300/csi500 universe 会消费它做 point-in-time 成分股过滤；
    # 缺失时降级为全量候选（保留旧行为）。
    watcher_base_url = (
        request.headers.get("X-Watcher-Base-Url") or getenv("WATCHER_BASE_URL")
    )
    try:
        result = run_backtest_engine(
            strategy_config=payload.strategy_config,
            kline_data=payload.kline_data,
            benchmark_data=payload.benchmark_data,
            watcher_base_url=watcher_base_url,
        )
    except BacktestError as exc:
        # 业务异常 → 200 信封（success=false）， errorCode 机器可读
        return BacktestRunResponse(
            success=False,
            message=exc.message,
            data=None,
            errorCode=exc.error_code,
            code=400,
        )

    return BacktestRunResponse(
        success=True,
        message="回测完成",
        data=result,
    )


@router.get(
    "/constants",
    response_model=ConstantsResponse,
    summary="回测常量",
    description="返回 broker_profile 白名单 / 可用排序指标 / 支持的范式（第二波：signals+bracket / signals+atr_stop / rebalance / exit.rules / mixed）。",
)
async def get_constants() -> ConstantsResponse:
    return ConstantsResponse(
        success=True,
        data={
            "broker_profiles": sorted(BROKER_PROFILES),
            "sort_metrics": [
                "sharpe_ratio",
                "total_return_pct",
                "max_drawdown_pct",
                "sortino_ratio",
                "win_rate",
                "profit_factor",
                "calmar_ratio",
                "cagr",
            ],
            "paradigms_supported": [
                "signals",
                "rebalance",
            ],
        },
    )


__all__ = ["router"]
