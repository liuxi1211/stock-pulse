"""GRID/WF 寻优任务状态机 + 并发限制（spec 015 FR-O8 / Task O-6）。

任务状态：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED。
- 用户级并发：GRID ≤ 2、WF ≤ 1（可配）；
- 单任务 timeout 默认 600s（超时 FAILED）；
- 取消信号：通过 threading.Event 在主线程标记，子进程取消信号穿透由 akquant
  max_tasks_per_child=1 + timeout 兜底（一期不实现真正的子进程 SIGINT 穿透，
  spec §S7 T-O-6/T-O-7 用 timeout+Event 模拟）。

一期采用**同步执行 + 内存态任务表**（不做异步队列；watcher 侧 HTTP 同步等待或
自行包装异步）。任务执行在独立线程，主线程立即返回 task_id，前端轮询
GET /optimize/{task_id}。
"""
from __future__ import annotations

import logging
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Optional

from services.backtest.optimizer import (
    OptimizeError,
    run_grid_optimize,
    run_walk_forward_optimize,
)

logger = logging.getLogger(__name__)


# ============================================================
# 枚举
# ============================================================

class TaskStatus(str, Enum):
    """寻优任务状态机。"""

    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class TaskType(str, Enum):
    """寻优任务类型。"""

    GRID = "GRID"
    WALK_FORWARD = "WALK_FORWARD"


# 已终态：不会再变迁的状态（不可取消）
_TERMINAL_STATES = frozenset(
    {TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELLED}
)


# ============================================================
# 异常
# ============================================================

class OptTaskError(Exception):
    """任务管理器层错误（含 error_code）。

    error_code 取稳定字符串：``OPT_TASK_CONCURRENCY_LIMIT`` /
    ``OPT_TASK_NOT_FOUND`` / ``OPT_TASK_ALREADY_DONE`` 等，HTTP 层据此转信封。
    """

    def __init__(self, message: str, error_code: str = "OPT_TASK_FAILED") -> None:
        self.message = message
        self.error_code = error_code
        super().__init__(message)


# ============================================================
# 任务记录
# ============================================================

@dataclass
class OptTask:
    """单个寻优任务的状态记录（内存态，不持久化）。"""

    task_id: str
    task_type: TaskType
    status: TaskStatus = TaskStatus.PENDING
    user_id: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    result: Optional[dict] = None
    error_message: Optional[str] = None
    error_code: Optional[str] = None
    cancel_event: threading.Event = field(default_factory=threading.Event)
    progress: float = 0.0
    param_grid_summary: dict = field(default_factory=dict)


# ============================================================
# 任务管理器
# ============================================================

class OptTaskManager:
    """GRID/WF 寻优任务管理器（spec 015 FR-O8 / Task O-6）。

    - 用户级并发：``max_grid_per_user`` / ``max_wf_per_user``；
    - 单任务 ``task_timeout`` 秒超时 → FAILED；
    - 内存态任务表（``_tasks``），watcher 侧负责持久化（落
      ``optimization_result`` 表）；
    - 所有 ``_tasks`` 读写均经 ``_lock`` 保护。
    """

    def __init__(
        self,
        max_grid_per_user: int = 2,
        max_wf_per_user: int = 1,
        task_timeout: float = 600.0,
    ) -> None:
        self.max_grid_per_user = int(max_grid_per_user)
        self.max_wf_per_user = int(max_wf_per_user)
        self.task_timeout = float(task_timeout)
        self._tasks: dict[str, OptTask] = {}
        self._lock = threading.Lock()

    # --------------------------------------------------------
    # 公开 API
    # --------------------------------------------------------

    def submit_grid(
        self,
        strategy_config: dict,
        kline_data: dict,
        param_grid: dict,
        user_id: Optional[str] = None,
        **optimize_kwargs: Any,
    ) -> str:
        """提交 GRID 寻优任务，立即返回 task_id。

        :raises OptTaskError:
            - ``OPT_TASK_CONCURRENCY_LIMIT``：该用户 GRID 并发达上限。
        """
        task_id = self._gen_task_id()
        summary = self._build_summary(param_grid, "GRID", optimize_kwargs)

        with self._lock:
            if not self._check_concurrency_unlocked(user_id, TaskType.GRID):
                raise OptTaskError(
                    f"GRID 寻优并发已达上限（{self.max_grid_per_user}），请等待已有任务完成",
                    error_code="OPT_TASK_CONCURRENCY_LIMIT",
                )
            task = OptTask(
                task_id=task_id,
                task_type=TaskType.GRID,
                status=TaskStatus.PENDING,
                user_id=user_id,
                param_grid_summary=summary,
            )
            self._tasks[task_id] = task

        thread = threading.Thread(
            target=self._run_grid_thread,
            args=(task_id, strategy_config, kline_data, param_grid, optimize_kwargs),
            name=f"opt-grid-{task_id}",
            daemon=True,
        )
        thread.start()
        logger.info("GRID task submitted: id=%s user=%s", task_id, user_id)
        return task_id

    def submit_wf(
        self,
        strategy_config: dict,
        kline_data: dict,
        param_grid: dict,
        train_period: int,
        test_period: int,
        user_id: Optional[str] = None,
        **wf_kwargs: Any,
    ) -> str:
        """提交 WALK-FORWARD 任务，立即返回 task_id。

        :raises OptTaskError:
            - ``OPT_TASK_CONCURRENCY_LIMIT``：该用户 WF 并发达上限。
        """
        task_id = self._gen_task_id()
        summary = self._build_summary(param_grid, "WALK_FORWARD", wf_kwargs)
        summary["train_period"] = train_period
        summary["test_period"] = test_period

        with self._lock:
            if not self._check_concurrency_unlocked(user_id, TaskType.WALK_FORWARD):
                raise OptTaskError(
                    f"WALK-FORWARD 并发已达上限（{self.max_wf_per_user}），请等待已有任务完成",
                    error_code="OPT_TASK_CONCURRENCY_LIMIT",
                )
            task = OptTask(
                task_id=task_id,
                task_type=TaskType.WALK_FORWARD,
                status=TaskStatus.PENDING,
                user_id=user_id,
                param_grid_summary=summary,
            )
            self._tasks[task_id] = task

        thread = threading.Thread(
            target=self._run_wf_thread,
            args=(
                task_id,
                strategy_config,
                kline_data,
                param_grid,
                train_period,
                test_period,
                wf_kwargs,
            ),
            name=f"opt-wf-{task_id}",
            daemon=True,
        )
        thread.start()
        logger.info("WF task submitted: id=%s user=%s", task_id, user_id)
        return task_id

    def get_task(self, task_id: str) -> Optional[OptTask]:
        """按 id 取任务；不存在返回 None。"""
        with self._lock:
            return self._tasks.get(task_id)

    def list_tasks(
        self,
        user_id: Optional[str] = None,
        task_type: Optional[TaskType] = None,
    ) -> list[OptTask]:
        """列出任务（可按 user_id / task_type 过滤），按创建时间倒序。"""
        with self._lock:
            items = list(self._tasks.values())
        if user_id is not None:
            items = [t for t in items if t.user_id == user_id]
        if task_type is not None:
            items = [t for t in items if t.task_type == task_type]
        items.sort(key=lambda t: t.created_at, reverse=True)
        return items

    def cancel(self, task_id: str) -> bool:
        """请求取消任务。

        - 不存在 / 已终态（SUCCESS/FAILED/CANCELLED）→ 返回 False；
        - 其余 → 设置 ``cancel_event``，置 CANCELLED，返回 True。

        竞态保护：cancel 与执行线程可能并发推进状态。本方法仅在任务仍非终态时
        推进为 CANCELLED；若执行线程已先把它推到 SUCCESS/FAILED，则本次 cancel 失败
        （返回 False），交由 :meth:`_mark` 的终态保护避免覆盖。

        注：一期取消信号仅在 engine 进程内传播（同线程检测 cancel_event）；
        子进程取消靠 akquant ``max_tasks_per_child=1`` + ``timeout`` 兜底。实际终止
        依赖 timeout，调用方应据此设计前端轮询逻辑。
        """
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return False
            if task.status in _TERMINAL_STATES:
                return False
            task.cancel_event.set()
            task.status = TaskStatus.CANCELLED
            task.finished_at = datetime.now()
        logger.info("task cancel requested: id=%s", task_id)
        return True

    def cleanup_old(self, max_age_hours: int = 24) -> int:
        """清理超过 ``max_age_hours`` 的已终态任务，返回清理条数。"""
        threshold = datetime.now() - timedelta(hours=max_age_hours)
        removed = 0
        with self._lock:
            for tid in list(self._tasks.keys()):
                task = self._tasks[tid]
                if task.status in _TERMINAL_STATES and task.finished_at is not None:
                    if task.finished_at < threshold:
                        del self._tasks[tid]
                        removed += 1
        if removed:
            logger.info("cleaned up %d old finished tasks", removed)
        return removed

    # --------------------------------------------------------
    # 内部：任务执行线程
    # --------------------------------------------------------

    def _run_grid_thread(
        self,
        task_id: str,
        strategy_config: dict,
        kline_data: dict,
        param_grid: dict,
        optimize_kwargs: dict,
    ) -> None:
        """GRID 任务执行线程（独立 ThreadPoolExecutor 控制 timeout）。"""
        self._run_thread(
            task_id=task_id,
            runner=run_grid_optimize,
            runner_kwargs=dict(
                strategy_config=strategy_config,
                kline_data=kline_data,
                param_grid=param_grid,
                **optimize_kwargs,
            ),
        )

    def _run_wf_thread(
        self,
        task_id: str,
        strategy_config: dict,
        kline_data: dict,
        param_grid: dict,
        train_period: int,
        test_period: int,
        wf_kwargs: dict,
    ) -> None:
        """WF 任务执行线程。"""
        self._run_thread(
            task_id=task_id,
            runner=run_walk_forward_optimize,
            runner_kwargs=dict(
                strategy_config=strategy_config,
                kline_data=kline_data,
                param_grid=param_grid,
                train_period=train_period,
                test_period=test_period,
                **wf_kwargs,
            ),
        )

    def _run_thread(
        self,
        task_id: str,
        runner: Callable[..., dict],
        runner_kwargs: dict,
    ) -> None:
        """统一的任务执行主循环。

        - 状态置 RUNNING，记录 started_at；
        - 用 ``ThreadPoolExecutor`` 提交 runner，``future.result(timeout=task_timeout)``；
        - 检测 ``cancel_event``：已 set → 优先 CANCELLED；
        - 超时 → FAILED(error_code=OPT_TASK_TIMEOUT)；
        - OptimizeError → FAILED(透传 error_code)；
        - 其它异常 → FAILED(error_code=OPT_TASK_FAILED)；
        - 正常完成 → SUCCESS，result=返回值；
        - finally 记 finished_at。

        竞态保护：状态推进全部走 :meth:`_mark`，由其终态保护兜底。每个分支
        先用 :meth:`_is_cancelled` 判定取消信号是否到达——若已到达，则尝试
        标记 CANCELLED；若 :meth:`_mark` 返回 False（说明状态已被另一路径
        推进到终态），本分支静默退出，不再覆盖。
        """
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                logger.warning("task vanished before run: %s", task_id)
                return
            # 提交到执行前被取消：此时仍持锁且状态为 PENDING，直接推进即可
            # （_mark 内部也会获取 _lock，避免重入死锁）
            if task.cancel_event.is_set():
                task.status = TaskStatus.CANCELLED
                task.finished_at = datetime.now()
                return
            task.status = TaskStatus.RUNNING
            task.started_at = datetime.now()

        executor = ThreadPoolExecutor(max_workers=1)
        try:
            future = executor.submit(runner, **runner_kwargs)
            try:
                result = future.result(timeout=self.task_timeout)
            except FuturesTimeoutError:
                # 超时 → FAILED（子线程仍在跑，靠 daemon 进程退出兜底）
                if self._is_cancelled(task_id):
                    self._mark(task_id, TaskStatus.CANCELLED)
                else:
                    self._mark(
                        task_id,
                        TaskStatus.FAILED,
                        error_message=f"任务超时（{self.task_timeout}s）",
                        error_code="OPT_TASK_TIMEOUT",
                    )
                return

            # 正常返回前再次检查取消信号（竞态容忍）
            if self._is_cancelled(task_id):
                self._mark(task_id, TaskStatus.CANCELLED)
                return

            self._mark(
                task_id,
                TaskStatus.SUCCESS,
                result=result if isinstance(result, dict) else {"data": result},
            )
            task_ref = self.get_task(task_id)
            if task_ref is not None:
                task_ref.progress = 1.0
        except OptimizeError as exc:
            if self._is_cancelled(task_id):
                self._mark(task_id, TaskStatus.CANCELLED)
                return
            self._mark(
                task_id,
                TaskStatus.FAILED,
                error_message=exc.message,
                error_code=exc.error_code,
            )
        except Exception as exc:  # noqa: BLE001 - 兜底
            if self._is_cancelled(task_id):
                self._mark(task_id, TaskStatus.CANCELLED)
                return
            logger.exception("opt task %s unexpected error", task_id)
            self._mark(
                task_id,
                TaskStatus.FAILED,
                error_message=f"任务执行异常: {exc}",
                error_code="OPT_TASK_FAILED",
            )
        finally:
            executor.shutdown(wait=False)

    # --------------------------------------------------------
    # 内部：辅助
    # --------------------------------------------------------

    def _check_concurrency(self, user_id: Optional[str], task_type: TaskType) -> bool:
        """线程安全地检查并发（对外暴露的并发计数入口）。"""
        with self._lock:
            return self._check_concurrency_unlocked(user_id, task_type)

    def _check_concurrency_unlocked(
        self, user_id: Optional[str], task_type: TaskType
    ) -> bool:
        """检查 user_id 当前 RUNNING/PENDING 的同类型任务数是否在上限内。

        调用方必须已持有 ``_lock``。
        """
        limit = (
            self.max_grid_per_user
            if task_type == TaskType.GRID
            else self.max_wf_per_user
        )
        active = 0
        for t in self._tasks.values():
            if t.task_type != task_type:
                continue
            if t.user_id != user_id:
                continue
            if t.status in (TaskStatus.PENDING, TaskStatus.RUNNING):
                active += 1
        return active < limit

    def _is_cancelled(self, task_id: str) -> bool:
        with self._lock:
            task = self._tasks.get(task_id)
            return bool(task is not None and task.cancel_event.is_set())

    def _mark(
        self,
        task_id: str,
        status: TaskStatus,
        result: Optional[dict] = None,
        error_message: Optional[str] = None,
        error_code: Optional[str] = None,
    ) -> bool:
        """推进任务状态。

        :returns: True 表示状态已推进；False 表示因终态保护被跳过。

        终态保护（修复竞态 S1）：若任务已处于 SUCCESS/FAILED/CANCELLED，
        则忽略本次推进。优先级规则：

        - SUCCESS / FAILED 一旦写入即不可被 CANCELLED 覆盖（避免 cancel 晚到
          抹掉真实执行结果）；
        - CANCELLED 已是终态，同样不可被覆盖；
        - RUNNING → 任意终态、PENDING → RUNNING/CANCELLED 均允许。

        这保证了 :meth:`cancel` 与执行线程的 :meth:`_run_thread` 即使并发调用，
        最终状态也只会落在第一个到达的终态上，不会出现 SUCCESS 被 CANCELLED
        反向覆盖的情况。
        """
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return False
            if task.status in _TERMINAL_STATES:
                logger.info(
                    "task %s already terminal (%s), skip mark to %s",
                    task_id, task.status.value, status.value,
                )
                return False
            task.status = status
            task.finished_at = datetime.now()
            if result is not None:
                task.result = result
            if error_message is not None:
                task.error_message = error_message
            if error_code is not None:
                task.error_code = error_code
            return True

    @staticmethod
    def _gen_task_id() -> str:
        short_uuid = uuid.uuid4().hex[:8]
        return f"opt_{int(time.time() * 1000)}_{short_uuid}"

    @staticmethod
    def _build_summary(
        param_grid: dict, task_type: str, extra_kwargs: dict
    ) -> dict:
        """构建任务参数摘要（供列表/详情展示）。"""
        summary: dict[str, Any] = {
            "task_type": task_type,
            "param_grid_keys": list(param_grid.keys()) if isinstance(param_grid, dict) else [],
            "param_grid_combinations": (
                OptTaskManager._grid_combinations(param_grid)
                if isinstance(param_grid, dict) else 0
            ),
        }
        # 摘要性回显排序/选优指标（不含大数据载荷）
        for key in ("sort_by", "metric", "max_workers", "top_n", "window_align"):
            if key in extra_kwargs:
                summary[key] = extra_kwargs[key]
        return summary

    @staticmethod
    def _grid_combinations(param_grid: dict) -> int:
        total = 1
        for vs in param_grid.values():
            try:
                total *= max(1, len(vs))
            except TypeError:
                total *= 1
        return total


# ============================================================
# 全局单例
# ============================================================

_global_manager: Optional[OptTaskManager] = None
_singleton_lock = threading.Lock()


def get_task_manager() -> OptTaskManager:
    """获取全局 :class:`OptTaskManager` 单例（惰性初始化）。"""
    global _global_manager
    if _global_manager is None:
        with _singleton_lock:
            if _global_manager is None:
                _global_manager = OptTaskManager()
    return _global_manager


__all__ = [
    "TaskStatus",
    "TaskType",
    "OptTask",
    "OptTaskError",
    "OptTaskManager",
    "get_task_manager",
]
