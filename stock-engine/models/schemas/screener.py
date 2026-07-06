"""选股中心 Pydantic Schema（统一策略配置 Schema §3.2 / spec 003 阶段 1 Task 6）。

字段命名严格对齐统一策略配置 Schema（camelCase），覆盖：
- 静态过滤配置 ``Filters``（§3.2.2）
- 排序打分配置 ``Ranking``（§3.2.1）
- 候选股票结构 ``CandidateStock``（watcher 预拼装传入）
- 快照选股请求/响应（FR-4）
- 区间选股请求/响应（FR-5）

约束（spec AC-11）：engine 不触库，本模块仅做参数建模，无 sqlite3/sqlalchemy。
"""
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field, model_validator

from models.schemas.condition import ConditionTree


# ============================================================
# 过滤配置（§3.2.2）
# ============================================================

class Filters(BaseModel):
    """静态过滤配置。

    各开关默认值与 Schema §3.2.2 / services/screener/filters.py 的 ``_DEFAULT_FILTERS`` 对齐。
    """

    exclude_st: bool = Field(True, description="排除 ST/*ST")
    exclude_suspended: bool = Field(True, description="排除停牌")
    exclude_limit_up: bool = Field(True, description="排除涨停（一字板等买不进的场景）")
    exclude_limit_down: bool = Field(False, description="排除跌停")
    industries: list[str] = Field(default_factory=list, description="行业白名单（空=不限）")
    exclude_industries: list[str] = Field(default_factory=list, description="行业黑名单")
    min_list_days: int = Field(0, description="最小上市天数（0=不限），次新股过滤用")


# ============================================================
# 排序配置（§3.2.1）
# ============================================================

class Ranking(BaseModel):
    """排序打分配置：单因子排序 or 多因子综合打分。

    - ``method="single"``：必须提供 ``factor``（factorKey），可选 ``order``（默认 "desc"）。
    - ``method="composite"``：必须提供 ``weights``（{factorKey: weight}，负权重=越小越好）。
    """

    method: Literal["single", "composite"] = Field(..., description="排序方法")
    weights: Optional[dict[str, float]] = Field(
        None, description="composite 模式：{factorKey: weight}", examples=[{"ROE_TTM": 0.5, "PE_TTM": -0.3}]
    )
    factor: Optional[str] = Field(None, description="single 模式：排序因子 factorKey", examples=["TOTAL_MV"])
    order: str = Field("desc", description="single 模式排序方向：asc / desc（默认 desc）")

    @model_validator(mode="after")
    def _check_method_fields(self) -> "Ranking":
        if self.method == "single":
            if not self.factor:
                raise ValueError("ranking.method='single' 时必须提供 factor")
        elif self.method == "composite":
            if not self.weights:
                raise ValueError("ranking.method='composite' 时必须提供 weights")
        return self


# ============================================================
# 候选股票结构
# ============================================================

class CandidateStock(BaseModel):
    """单只候选股票的输入数据（watcher 预拼装传入）。

    - ``ohlcv_history``：OHLCV K 线记录列表（含 date/open/high/low/close/volume），供技术面因子计算。
    - ``fundamentals``：基本面快照（TUSHARE 因子来源，如 PE_TTM/ROE_TTM/TOTAL_MV）。
    - ``meta``：静态过滤所需标记（is_st/is_suspended/is_limit_up/is_limit_down/industry/list_date）。
    """

    ohlcv_history: list[dict[str, Any]] = Field(
        default_factory=list,
        description="OHLCV 记录列表（含 date/open/high/low/close/volume）",
    )
    fundamentals: dict[str, float] = Field(
        default_factory=dict,
        description="基本面快照，key=factorKey（如 PE_TTM）",
        examples=[{"PE_TTM": 12.3, "ROE_TTM": 15.0, "TOTAL_MV": 1.0e10}],
    )
    meta: dict[str, Any] = Field(
        default_factory=dict,
        description="静态过滤标记（is_st/is_suspended/is_limit_up/is_limit_down/industry/list_date）",
    )


# ============================================================
# 快照选股请求/响应（FR-4）
# ============================================================

class SnapshotScreenRequest(BaseModel):
    """快照选股请求（FR-4）。

    - ``conditions`` 为 None 时表示仅做静态过滤（不做条件求值）。
    - ``ranking`` 为 None 时按命中原顺序输出（rank 从 1 起，score=None）。
    - ``filters`` 为 None 时使用全默认值（与 Schema §3.2.2 对齐）。
    """

    universe: str = Field(..., description="候选池标识（如 csi300）或 'manual'", examples=["csi300"])
    date: str = Field(..., description="选股日期 YYYY-MM-DD", examples=["2026-07-03"])
    candidates: dict[str, CandidateStock] = Field(
        ..., description="候选股票映射 {symbol: CandidateStock}"
    )
    conditions: Optional[ConditionTree] = Field(
        None, description="条件树（ConditionTree），None=仅做静态过滤"
    )
    ranking: Optional[Ranking] = Field(None, description="排序配置，None=不排序（原序输出）")
    filters: Optional[Filters] = Field(None, description="过滤配置，None=全默认")
    top_n: Optional[int] = Field(None, description="仅保留前 N 只；None/<=0=不截断", examples=[30])
    verbose_excluded: bool = Field(False, description="True 时返回 excluded 排除明细")


class StockResult(BaseModel):
    """单只命中股票的排序结果。"""

    symbol: str = Field(..., description="股票代码")
    rank: int = Field(..., description="排名（从 1 起）")
    score: Optional[float] = Field(None, description="得分（single=因子原值，composite=综合分，无 ranking=None）")
    factor_values: dict[str, Optional[float]] = Field(
        default_factory=dict, description="该股票全部预计算因子值（NaN→null）"
    )


class SnapshotScreenResponseData(BaseModel):
    """快照选股响应数据体。"""

    date: str = Field(..., description="选股日期")
    total_count: int = Field(..., description="满足条件总数（top_n 截断前）")
    stocks: list[StockResult] = Field(default_factory=list, description="排序后的命中股票列表（已 top_n 截断）")
    excluded: dict[str, list[str]] = Field(
        default_factory=dict,
        description="排除明细 {st/suspended/limit_up/limit_down/industry/list_days: [symbol,...]}，仅 verbose 时非空",
    )


class SnapshotScreenResponse(BaseModel):
    """快照选股统一响应。"""

    success: bool = True
    message: str = "选股完成"
    data: SnapshotScreenResponseData


# ============================================================
# 区间选股请求/响应（FR-5）
# ============================================================

class RangeScreenRequest(BaseModel):
    """区间选股请求（FR-5）：跨多个交易日重复跑选股，输出每只股票的命中分布。"""

    universe: str = Field(..., description="候选池标识", examples=["csi300"])
    dates: list[str] = Field(..., description="升序交易日数组 YYYY-MM-DD")
    candidates_by_date: dict[str, dict[str, CandidateStock]] = Field(
        ..., description="每日候选股票映射 {date: {symbol: CandidateStock}}"
    )
    conditions: Optional[ConditionTree] = Field(None, description="条件树，None=仅做静态过滤")
    ranking: Optional[Ranking] = Field(None, description="排序配置")
    filters: Optional[Filters] = Field(None, description="过滤配置，None=全默认")
    top_n: Optional[int] = Field(None, description="每日仅保留前 N 只；None=不截断")


class DailyHit(BaseModel):
    """单日命中记录。"""

    date: str = Field(..., description="交易日")
    hit: bool = Field(..., description="是否命中（进入当日 stocks 列表）")
    rank: Optional[int] = Field(None, description="当日排名（未命中为 null）")


class RangeStockResult(BaseModel):
    """单只股票的区间命中分布。"""

    symbol: str = Field(..., description="股票代码")
    first_hit_date: Optional[str] = Field(None, description="首次命中日期；从未命中为 null")
    hit_count: int = Field(..., description="命中天数")
    hit_ratio: float = Field(..., description="命中率 = hit_count / total_days")
    consecutive_max: int = Field(..., description="最大连续命中天数")
    daily_hits: list[DailyHit] = Field(default_factory=list, description="逐日命中时序")


class RangeScreenResponseData(BaseModel):
    """区间选股响应数据体。"""

    total_days: int = Field(..., description="区间交易日总数")
    results: list[RangeStockResult] = Field(
        default_factory=list,
        description="每只股票的命中分布（按 hit_count 降序、consecutive_max 降序、symbol 升序）",
    )


class RangeScreenResponse(BaseModel):
    """区间选股统一响应。"""

    success: bool = True
    message: str = "区间选股完成"
    data: RangeScreenResponseData
