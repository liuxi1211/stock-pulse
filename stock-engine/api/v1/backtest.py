"""回测中心 FastAPI 路由（spec 007-backtest-center T2）。

- ``POST /python/v1/backtest/run``：执行回测，返回统一信封 ``{success, message, data}``；
- ``GET /python/v1/backtest/constants``：返回 broker_profile / sort_metric / 支持范式。

约束：engine 不触库，本模块不含任何数据库驱动 import / 连接 / 路径字面量。
"""
from os import getenv
from typing import Any, Literal, Optional

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from services.backtest.opt_task_manager import (
    OptTaskError,
    TaskStatus,
    TaskType,
    get_task_manager,
)
from services.backtest.optimizer import CgroupCpuDetector
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
# 寻优（Optimize）请求 / 响应 Pydantic 模型（spec 015 FR-O1/O4 / Task O-7）
# ============================================================

class OptimizeRequest(BaseModel):
    """POST /optimize 请求体（GRID 寻优）。"""

    strategy_config: dict = Field(..., description="策略配置 JSON（含 tunable_params）")
    kline_data: dict = Field(..., description="K 线 {symbol: list[dict]}")
    param_grid: dict = Field(..., description="参数网格，如 {'fast':[5,10], 'slow':[20,30]}")
    sort_by: str = Field("sharpe_ratio", description="排序指标")
    max_workers: Optional[int] = Field(None, description="并发数（None=engine 自决 cgroup 感知）")
    constraint: Optional[dict] = Field(None, description="参数约束 DSL")
    result_filter: Optional[dict] = Field(None, description="结果过滤 DSL")
    top_n: int = Field(10, ge=1, le=200, description="返回前 N")
    user_id: Optional[str] = Field(None, description="用户 ID（并发限制用）")


class WalkForwardRequest(OptimizeRequest):
    """POST /walk_forward 请求体。"""

    train_period: int = Field(..., ge=1, description="训练窗口（bar 数或年/季数，配合 window_align）")
    test_period: int = Field(..., ge=1, description="测试窗口")
    metric: str = Field("sharpe_ratio", description="选优指标")
    # 显式枚举校验：与 optimizer._VALID_WINDOW_ALIGN 保持一致，
    # 非法值在 Pydantic 阶段就 422，避免流到 engine 内部再报 OPTIMIZE_CONFIG_INVALID。
    window_align: Literal["bar_count", "year", "quarter"] = Field(
        "bar_count", description="窗口对齐：bar_count / year / quarter"
    )


class OptimizeSubmitResponse(BaseModel):
    """POST /optimize / /walk_forward 提交响应（统一信封）。"""

    success: bool
    message: str
    data: Optional[dict] = Field(None, description="{task_id, status}")
    errorCode: Optional[str] = None
    code: Optional[int] = None


class OptimizeStatusResponse(BaseModel):
    """GET /optimize/{id} / cancel / list 响应（统一信封）。"""

    success: bool
    message: str
    data: Optional[dict] = None
    errorCode: Optional[str] = None
    code: Optional[int] = None


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
                "grid",
            ],
            "optimization_supported": True,
            # classmethod 调用，无需实例化（避免无意义对象创建）
            "max_workers_available": CgroupCpuDetector.available_cpu_count(),
        },
    )


# ============================================================
# 寻优路由（spec 015 FR-O1/O4 / Task O-7）
# ============================================================

@router.post(
    "/optimize",
    response_model=OptimizeSubmitResponse,
    summary="提交 GRID 寻优任务",
    description=(
        "异步提交一个 GRID 参数寻优任务，立即返回 task_id。"
        "通过 GET /optimize/{task_id} 轮询结果。"
        "用户级并发：GRID ≤ 2（达上限返回 errorCode=OPT_TASK_CONCURRENCY_LIMIT）。"
    ),
)
async def submit_optimize(payload: OptimizeRequest) -> OptimizeSubmitResponse:
    mgr = get_task_manager()
    try:
        task_id = mgr.submit_grid(
            strategy_config=payload.strategy_config,
            kline_data=payload.kline_data,
            param_grid=payload.param_grid,
            user_id=payload.user_id,
            sort_by=payload.sort_by,
            max_workers=payload.max_workers,
            constraint=payload.constraint,
            result_filter=payload.result_filter,
            top_n=payload.top_n,
        )
    except OptTaskError as e:
        return OptimizeSubmitResponse(
            success=False,
            message=e.message,
            errorCode=e.error_code,
            code=429,
        )
    return OptimizeSubmitResponse(
        success=True,
        message="寻优任务已提交",
        data={"task_id": task_id, "status": TaskStatus.PENDING.value},
    )


@router.post(
    "/walk_forward",
    response_model=OptimizeSubmitResponse,
    summary="提交 WALK-FORWARD 验证任务",
    description=(
        "异步提交一个 WALK-FORWARD 滚动样本外验证任务，立即返回 task_id。"
        "用户级并发：WF ≤ 1（达上限返回 errorCode=OPT_TASK_CONCURRENCY_LIMIT）。"
    ),
)
async def submit_walk_forward(payload: WalkForwardRequest) -> OptimizeSubmitResponse:
    mgr = get_task_manager()
    try:
        task_id = mgr.submit_wf(
            strategy_config=payload.strategy_config,
            kline_data=payload.kline_data,
            param_grid=payload.param_grid,
            train_period=payload.train_period,
            test_period=payload.test_period,
            user_id=payload.user_id,
            metric=payload.metric,
            window_align=payload.window_align,
            max_workers=payload.max_workers,
            constraint=payload.constraint,
            result_filter=payload.result_filter,
        )
    except OptTaskError as e:
        return OptimizeSubmitResponse(
            success=False,
            message=e.message,
            errorCode=e.error_code,
            code=429,
        )
    return OptimizeSubmitResponse(
        success=True,
        message="WF 任务已提交",
        data={"task_id": task_id, "status": TaskStatus.PENDING.value},
    )


@router.get(
    "/optimize/{task_id}",
    response_model=OptimizeStatusResponse,
    summary="查询寻优任务状态/结果",
    description="按 task_id 查询任务当前状态（PENDING/RUNNING/SUCCESS/FAILED/CANCELLED）与结果。",
)
async def get_optimize_status(task_id: str) -> OptimizeStatusResponse:
    mgr = get_task_manager()
    task = mgr.get_task(task_id)
    if task is None:
        return OptimizeStatusResponse(
            success=False,
            message="任务不存在",
            errorCode="OPT_TASK_NOT_FOUND",
            code=404,
        )
    data = {
        "task_id": task.task_id,
        "task_type": task.task_type.value,
        "status": task.status.value,
        "created_at": task.created_at.isoformat(),
        "started_at": task.started_at.isoformat() if task.started_at else None,
        "finished_at": task.finished_at.isoformat() if task.finished_at else None,
        "result": task.result,
        "error_message": task.error_message,
        "error_code": task.error_code,
        "param_grid_summary": task.param_grid_summary,
    }
    return OptimizeStatusResponse(success=True, message="查询成功", data=data)


@router.post(
    "/optimize/{task_id}/cancel",
    response_model=OptimizeStatusResponse,
    summary="取消寻优任务",
    description="对未终态任务发起取消（设置 cancel_event，状态置 CANCELLED）。",
)
async def cancel_optimize(task_id: str) -> OptimizeStatusResponse:
    mgr = get_task_manager()
    cancelled = mgr.cancel(task_id)
    if not cancelled:
        return OptimizeStatusResponse(
            success=False,
            message="任务已完成，无法取消",
            errorCode="OPT_TASK_ALREADY_DONE",
            code=409,
        )
    return OptimizeStatusResponse(
        success=True,
        message="取消请求已提交",
        data={"task_id": task_id, "status": TaskStatus.CANCELLED.value},
    )


@router.get(
    "/optimize",
    response_model=OptimizeStatusResponse,
    summary="列出寻优任务",
    description="列出寻优任务（可按 user_id / task_type 过滤），按创建时间倒序。",
)
async def list_optimize_tasks(
    user_id: Optional[str] = None,
    task_type: Optional[str] = None,
) -> OptimizeStatusResponse:
    mgr = get_task_manager()
    tt = TaskType(task_type) if task_type else None
    tasks = mgr.list_tasks(user_id=user_id, task_type=tt)
    data = {
        "tasks": [
            {
                "task_id": t.task_id,
                "task_type": t.task_type.value,
                "status": t.status.value,
                "created_at": t.created_at.isoformat(),
            }
            for t in tasks
        ]
    }
    return OptimizeStatusResponse(success=True, message="查询成功", data=data)


__all__ = ["router"]
