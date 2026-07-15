"""策略配置 Pydantic v2 模型（统一策略配置 Schema §3 / §4）。

字段命名严格 snake_case 对齐 Schema §4（``output_index``），与统一策略配置 JSON
一一对应。所有模型 ``extra="forbid"``，拒绝未知字段以严格对齐 Schema。

表达式节点（ExpressionNode）按 Schema §4.3 用字段存在性区分 4 形态：
- :class:`ValueNode`：``{"value": <number|string>}``
- :class:`FactorNode`：``{"factor": ..., "params": {...}, "inputs": {...}, "output_index": ...}``
- :class:`OpNode`：``{"op": "+|-|*|/", "left": <EN>, "right": <EN>}``（递归）
- :class:`RefNode`：``{"ref": "entry_price"}``（仅 trading_config 合法）

条件树（ConditionTree）按 Schema §4.1 用 ``operator``/``conditions`` 字段，叶子是
:class:`CompareLeaf`（``type="compare"``）；二者通过 ``Union`` 在运行时按字段判断。

注意：本模块仅做**结构校验**（字段存在性 / 类型 / 枚举值），业务规则校验
（signals 与 rebalance 至少一个、cross_* 需要 factor 节点、ref 范围、注入防护等）
在后续 Task 的 validator 层完成，使用 :mod:`constants` 白名单与 :mod:`errors` 错误码。
"""
from typing import Any, Dict, List, Literal, Optional, Union

import logging

from pydantic import BaseModel, ConfigDict, Field, model_validator


_log = logging.getLogger(__name__)


# ============================================================
# 4.1 ExpressionNode：表达式节点 discriminated union（4 形态）
# ============================================================

class ValueNode(BaseModel):
    """静态值节点：数字或字符串字面量。

    Schema §4.3 形态 ①。字符串值允许（如行业代码、相对引用），最终求值由
    条件引擎尝试转 float。
    """

    model_config = ConfigDict(extra="forbid")

    value: Union[float, int, str] = Field(..., description="静态值（数字或字符串）")


class FactorNode(BaseModel):
    """因子引用节点：调因子计算模块 ``compute(name=factor, inputs, **params)``。

    Schema §4.3 形态 ② / §4.5。多输出因子（MACD/BOLL/KDJ）必须带 ``output_index``
    降维到标量，否则在 validator 层报 ``MULTI_OUTPUT_REQUIRES_INDEX``。
    """

    model_config = ConfigDict(extra="forbid")

    factor: str = Field(..., description="因子 factorKey（如 RSI / MACD / PE_TTM）")
    params: Optional[Dict[str, Any]] = Field(
        None, description="因子参数（透传 talib / provider）", examples=[{"timeperiod": 14}]
    )
    inputs: Optional[Dict[str, List[str]]] = Field(
        None, description="输入列覆盖（一般留空用因子默认 inputs）"
    )
    output_index: Optional[int] = Field(
        None, description="多输出因子取第几路（MACD/BOLL/KDJ 必填）"
    )


class OpNode(BaseModel):
    """算术运算节点：``left op right``，递归。Schema §4.3 形态 ③。"""

    model_config = ConfigDict(extra="forbid")

    op: Literal["+", "-", "*", "/"] = Field(..., description="算术运算符")
    left: "ExpressionNode" = Field(..., description="左操作数（递归表达式节点）")
    right: "ExpressionNode" = Field(..., description="右操作数（递归表达式节点）")


class RefNode(BaseModel):
    """状态引用节点：持仓/状态引用。Schema §4.3 形态 ④ / §4.6。

    仅 trading_config 内合法（signals / exit.rules），screen_config 内禁止
    （validator 层报 ``SCREEN_REF_FORBIDDEN``）。
    """

    model_config = ConfigDict(extra="forbid")

    ref: str = Field(..., description="状态引用键名（如 entry_price）")


# Union 顺序：先尝试更具体的形态（factor/op/ref），最后 value；Pydantic 按顺序匹配
ExpressionNode = Union[FactorNode, OpNode, ValueNode, RefNode]

# 前向引用解析：OpNode.left/right 引用了尚在定义的 ExpressionNode
OpNode.model_rebuild()


# ============================================================
# 4.2 CompareLeaf：比较叶子节点
# ============================================================

class CompareLeaf(BaseModel):
    """比较叶子：``left <comparator> right``。Schema §4.2。

    ``comparator`` 此处仅做字符串结构承载；具体取值合法性（screen 禁 cross_*）
    在 validator 层按上下文（screen vs trading）校验。
    """

    model_config = ConfigDict(extra="forbid")

    type: Literal["compare"] = Field("compare", description="节点类型标识，固定为 compare")
    left: ExpressionNode = Field(..., description="左操作数（表达式节点）")
    comparator: str = Field(..., description="比较器（> < >= <= == != cross_up cross_down）")
    right: ExpressionNode = Field(..., description="右操作数（表达式节点）")


# ============================================================
# 4.3 ConditionTree：递归逻辑组
# ============================================================

class ConditionTree(BaseModel):
    """逻辑组节点：``operator(conditions...)``。Schema §4.1。

    注意：按 Schema §4.1 用 ``operator``/``conditions`` 字段（不是 type/children）。
    ``conditions`` 元素可为 :class:`ConditionTree`（逻辑组）或 :class:`CompareLeaf`
    （叶子），通过 Union 在运行时按字段判断（含 ``operator`` → 逻辑组，
    含 ``type="compare"`` → 叶子）。
    """

    model_config = ConfigDict(extra="forbid")

    operator: Literal["AND", "OR"] = Field(..., description="逻辑运算符 AND / OR")
    conditions: List[Union["ConditionTree", CompareLeaf]] = Field(
        default_factory=list,
        description="子条件列表（元素可为 ConditionTree 或 CompareLeaf，递归）",
    )


# 前向引用解析：ConditionTree.conditions 引用了尚在定义的 ConditionTree
ConditionTree.model_rebuild()


# ============================================================
# §3.2 screen_config 子模型
# ============================================================

class RankingModel(BaseModel):
    """排序规则。Schema §3.2.1。

    - ``method="composite"`` → ``weights`` 必填（validator 层校验）
    - ``method="single"`` → ``factor`` + ``order`` 必填（validator 层校验）
    - ``method="disabled"`` → 不排序（仅过滤）
    """

    model_config = ConfigDict(extra="forbid")

    method: Literal["disabled", "single", "composite"] = Field(
        ..., description="排序方法：disabled / single / composite"
    )
    weights: Optional[Dict[str, float]] = Field(
        None, description="composite 时的 {factorKey: 权重}，负权重=越小越好"
    )
    factor: Optional[str] = Field(None, description="single 时的单因子 factorKey")
    order: Optional[Literal["asc", "desc"]] = Field(
        None, description="single 时的排序方向（asc 升序 / desc 降序）"
    )


class StaticFiltersModel(BaseModel):
    """静态过滤规则。Schema §3.2.2。

    默认值按 Schema §3.2.2 表（exclude_st/exclude_suspended 默认 True，
    exclude_limit_up/exclude_limit_down 默认 False）。
    """

    model_config = ConfigDict(extra="forbid")

    exclude_st: bool = Field(True, description="排除 ST/*ST")
    exclude_suspended: bool = Field(True, description="排除停牌")
    exclude_limit_up: bool = Field(False, description="排除涨停（无法买入）")
    exclude_limit_down: bool = Field(False, description="排除跌停")
    industries: Optional[List[str]] = Field(None, description="行业白名单（仅保留）")
    exclude_industries: Optional[List[str]] = Field(None, description="行业黑名单（排除）")
    min_list_days: Optional[int] = Field(None, description="上市天数下限（过滤次新）")


class UniverseModel(BaseModel):
    """① Universe 层：选股范围 + point-in-time 成分股过滤。Schema §3.2.2。

    - ``pool="manual"`` 时 ``stocks`` 必填（validator 层校验，错误码 MANUAL_SYMBOL_REQUIRED）；
    - ``point_in_time`` 字段已 **deprecated**（spec 011 P1-1）：所有 universe 类型现在
      **强制** 执行 point-in-time 成分股过滤，由 ``rebalance_engine._apply_universe_filter``
      无条件查 watcher。字段保留仅为向后兼容旧 JSON（避免 ``extra="forbid"`` 拒绝），
      在场时会打 deprecation warning。
    """

    model_config = ConfigDict(extra="forbid")

    pool: str = Field(
        ..., description="股票池：all_a_shares / csi300 / csi500 / manual / 自定义池 ID"
    )
    point_in_time: Optional[bool] = Field(
        None,
        description="[deprecated] 已废弃。所有 universe 强制 point-in-time 过滤，"
        "字段保留仅为向后兼容旧 JSON，在场时会打 warning。",
    )
    stocks: Optional[List[str]] = Field(
        None, description="pool=manual 时必填的标的列表"
    )

    @model_validator(mode="after")
    def _warn_deprecated_point_in_time(self) -> "UniverseModel":
        """检测到 point_in_time 字段显式在场时打 deprecation warning。

        spec 011 P1-1：point-in-time 已从可选收紧为强制，该字段不再有语义作用，
        但为兼容旧 JSON 保留（不报错，仅 warning）。
        """
        if self.point_in_time is not None:
            _log.warning(
                "point_in_time 字段已废弃，所有 universe 强制 point-in-time 过滤"
                "（spec 011 P1-1）；该字段保留仅为向后兼容，请从配置中移除。"
            )
        return self


class FactorModel(BaseModel):
    """② Factor Scoring 层：因子打分公式。Schema §3.2.2。

    平移自旧 ``RankingModel``（保留旧类名兼容性，但 ScreenConfigModel 不再引用）：

    - ``method="composite"`` → ``weights`` 必填（validator 层校验）；
    - ``method="single"`` → ``factor`` + ``order`` 必填（validator 层校验）；
    - ``method="disabled"`` → 不排序（仅过滤）。
    """

    model_config = ConfigDict(extra="forbid")

    method: Literal["disabled", "single", "composite"] = Field(
        ..., description="排序方法：disabled / single / composite"
    )
    weights: Optional[Dict[str, float]] = Field(
        None, description="composite 时的 {factorKey: 权重}，负权重=越小越好"
    )
    factor: Optional[str] = Field(None, description="single 时的单因子 factorKey")
    order: Optional[Literal["asc", "desc"]] = Field(
        None, description="single 时的排序方向（asc 升序 / desc 降序）"
    )


class FilterModel(BaseModel):
    """③ Filter 层：硬性筛选（布尔判断）。Schema §3.2.2。

    合并自旧 ``conditions``（截面布尔过滤）+ ``StaticFiltersModel``（静态过滤）。
    ``conditions`` 内禁止 cross_up/cross_down 与 ref（Schema §7.2，validator 层校验）。
    """

    model_config = ConfigDict(extra="forbid")

    conditions: Optional[ConditionTree] = Field(
        None, description="截面布尔过滤条件树（禁 cross_*/ref）"
    )
    exclude_st: bool = Field(True, description="排除 ST/*ST")
    exclude_suspended: bool = Field(True, description="排除停牌")
    exclude_limit_up: bool = Field(True, description="排除涨停（无法买入）")
    exclude_limit_down: bool = Field(False, description="排除跌停")
    industries: Optional[List[str]] = Field(None, description="行业白名单（仅保留）")
    exclude_industries: Optional[List[str]] = Field(None, description="行业黑名单（排除）")
    min_list_days: Optional[int] = Field(None, description="上市天数下限（过滤次新）")


class PortfolioModel(BaseModel):
    """④ Portfolio 层：TopN + 权重体系。Schema §3.2.2 / spec 011 迭代 4。

    spec 011 迭代 4 新增权重体系字段：

    - ``cash_reserve_pct`` (P1-3)：现金保留比例，总持仓权重 = 1 - cash_reserve_pct。
    - ``max_weight_per_symbol`` (P1-4)：单标的最大权重上限，超出截断。
    - ``max_industry_exposure`` (P1-4)：单行业最大暴露上限，超出按比例缩减。
    - ``buffer_n`` (P1-7)：换仓缓冲带，买入取 top_(n-buffer)，卖出取 top_(n+buffer)。
    """

    model_config = ConfigDict(extra="forbid")

    top_n: Optional[int] = Field(
        None, description="选出的标的数量（→ rebalance_to_topn top_n）"
    )
    cash_reserve_pct: Optional[float] = Field(
        None, ge=0.0, le=0.95, description="现金保留比例（0.2=保留 20% 现金）"
    )
    max_weight_per_symbol: Optional[float] = Field(
        None, ge=0.0, le=1.0, description="单标的最大权重上限"
    )
    max_industry_exposure: Optional[float] = Field(
        None, ge=0.0, le=1.0, description="单行业最大暴露上限"
    )
    buffer_n: Optional[int] = Field(
        None, ge=0, description="换仓缓冲带：买入取 top_(n-buffer)，卖出取 top_(n+buffer)"
    )


class ScreenConfigModel(BaseModel):
    """选股配置（4 层结构）。Schema §3.2。

    BREAKING：替换旧 5 字段扁平结构（universe(str)/stocks/top_n/conditions/ranking/filters）
    为 4 层对象结构（universe/factor/filter/portfolio）。旧结构迁移映射：

    - ``universe(string)`` → ``universe.pool``
    - ``stocks`` → ``universe.stocks``
    - ``ranking`` → ``factor``
    - ``conditions`` → ``filter.conditions``
    - ``filters``（StaticFiltersModel）→ ``filter``（其余字段平移）
    - ``top_n`` → ``portfolio.top_n``

    ``universe`` 必填；``factor`` / ``filter`` / ``portfolio`` 可选。
    """

    model_config = ConfigDict(extra="forbid")

    universe: UniverseModel = Field(..., description="① 选股范围层")
    factor: Optional[FactorModel] = Field(None, description="② 因子打分层")
    filter: Optional[FilterModel] = Field(None, description="③ 硬性筛选层")
    portfolio: Optional[PortfolioModel] = Field(None, description="④ 组合构建层")


# ============================================================
# §3.3 trading_config 子模型
# ============================================================

class SignalsModel(BaseModel):
    """买卖信号条件树。Schema §3.3.1。

    至少 ``buy`` 或 ``sell`` 一个在场才有意义（validator 层结合 position_sizing 判断）。
    条件树内允许 ref（与 screen_config 不同）。
    """

    model_config = ConfigDict(extra="forbid")

    buy: Optional[ConditionTree] = Field(None, description="买入条件树（§4）")
    sell: Optional[ConditionTree] = Field(None, description="卖出条件树（§4）")
    eval_scope: Optional[str] = Field(
        None, description="评估范围：per_symbol（默认）/ portfolio（预留）"
    )


class PositionSizingModel(BaseModel):
    """仓位管理（统一为 akquant 下单方法名）。Schema §3.3.2。

    ``method`` 合法性 / ``target`` 是否必填 / sell_method 合法性均在 validator 层
    用 :data:`constants.POSITION_SIZING_METHODS` / :data:`SELL_METHODS` 校验。
    """

    model_config = ConfigDict(extra="forbid")

    method: str = Field(..., description="下单方法名（akquant Strategy 方法）")
    target: Optional[Union[float, int, str]] = Field(
        None, description="目标值（百分比 0~1 / 股数 / 金额），视 method 决定是否必填"
    )
    params: Optional[Dict[str, Any]] = Field(
        None, description="method 专属参数透传（如 order_target_weights 的 weights）"
    )
    sell_method: Optional[str] = Field(
        None, description="sell 信号触发时用的下单方法（默认 close_position）"
    )


class BracketModel(BaseModel):
    """OCO 括号单（静态阈值）。Schema §3.3.3 → akquant ``place_bracket_order``。

    ``use_atr_stop=true`` 时 ``atr_multiplier`` 必填（validator 层校验，
    错误码 ``ATR_MULTIPLIER_REQUIRED``）。
    """

    model_config = ConfigDict(extra="forbid")

    stop_loss_pct: Optional[float] = Field(None, description="止损百分比（0.1=跌 10%）")
    take_profit_pct: Optional[float] = Field(None, description="止盈百分比（0.1=涨 10%）")
    use_atr_stop: Optional[bool] = Field(None, description="是否用 ATR 动态止损")
    atr_period: Optional[int] = Field(None, description="ATR 周期（默认 14）")
    atr_multiplier: Optional[float] = Field(
        None, description="ATR 倍数（use_atr_stop=true 时必填）"
    )


class ExitRuleModel(BaseModel):
    """复杂出场规则（条件树，逐条评估，命中触发 action）。Schema §3.3.3。

    ``condition`` 内允许 ref（exit.rules 针对持仓，有持仓上下文，Schema §7.2）。
    """

    model_config = ConfigDict(extra="forbid")

    name: Optional[str] = Field(None, description="规则名（展示用）")
    condition: ConditionTree = Field(..., description="触发条件（§4，支持 ref）")
    action: Optional[str] = Field(
        None, description="触发动作：close_position / sell / 自定义 method"
    )


class ExitModel(BaseModel):
    """出场规则。Schema §3.3.3。bracket 与 rules 可同时在场。"""

    model_config = ConfigDict(extra="forbid")

    bracket: Optional[BracketModel] = Field(None, description="OCO 括号单（静态阈值）")
    rules: Optional[List[ExitRuleModel]] = Field(
        None, description="复杂出场规则列表（条件树 + ref）"
    )


class RebalanceModel(BaseModel):
    """调仓规则 → akquant ``rebalance_to_topn``。Schema §3.3.4。

    在场=选股调仓范式（与 signals 共存=混合范式，均合法）。
    """

    model_config = ConfigDict(extra="forbid")

    frequency: Literal["daily", "weekly", "monthly", "quarterly"] = Field(
        ..., description="调仓频率（→ on_daily_rebalance 触发判断）"
    )
    trigger: Optional[Literal["first", "last"]] = Field(
        None,
        description=(
            "触发时点：first=周期首个交易日，last=周期末个交易日。"
            "frequency=daily 时忽略。触发判定改查 bar 的 trade_cal 标记"
            "（is_first_of_month / is_last_of_month 等，零状态）。"
            "默认 None 时由 compiler 从 day_of_period 兼容映射，最终都缺省规约为 first。"
        ),
    )
    day_of_period: Optional[int] = Field(
        None,
        description=(
            "[DEPRECATED] 调仓日序（monthly=每月第几个交易日，weekly=周几）。"
            "已被 trigger(first/last) 取代，仅保留以兼容旧 JSON；"
            "validator/compiler 检测到时会映射为 trigger 并打 deprecation warning。"
        ),
    )
    replace_method: Optional[Literal["full", "incremental"]] = Field(
        None, description="换仓方式：full（全换）/ incremental（只换差额）"
    )
    weight_mode: Optional[Literal["equal", "score"]] = Field(
        None, description="权重模式：equal（等权）/ score（按 ranking score 加权）"
    )
    long_only: Optional[bool] = Field(
        True,
        description="是否仅做多（默认 True）。A 股不支持融券做空，固定 True；"
        "前端控件已移除，此处保留字段并固定默认值，待期货标的支持后再开放。",
    )
    min_holding_bars: Optional[int] = Field(
        None, ge=0, description="最小持仓周期 bar 数（未满则不卖）"
    )
    reject_limit_up_on_buy: bool = Field(
        True, description="涨停拒买（当日涨停标的不买入）"
    )
    reject_limit_down_on_sell: bool = Field(
        True, description="跌停拒卖（当日跌停标的不卖出）"
    )


class TradingConfigModel(BaseModel):
    """交易配置。Schema §3.3。

    至少 ``signals`` 或 ``rebalance`` 一个在场（validator 层校验，
    错误码 ``MISSING_SIGNALS_OR_REBALANCE``）。
    """

    model_config = ConfigDict(extra="forbid")

    signals: Optional[SignalsModel] = Field(None, description="买卖信号条件树")
    position_sizing: Optional[PositionSizingModel] = Field(None, description="仓位管理")
    exit: Optional[ExitModel] = Field(None, description="出场规则")
    rebalance: Optional[RebalanceModel] = Field(None, description="调仓规则")


# ============================================================
# §3.4 backtest_config 子模型
# ============================================================

class SlippageDict(BaseModel):
    """滑点 dict 形态。Schema §3.4 / akquant 04-backtest-run.md §4。

    裸 float（如 ``0.0002``）已弃用，统一用 dict；与 float 二选一在
    :class:`BacktestConfigModel.slippage` 用 Union 表达。
    """

    model_config = ConfigDict(extra="forbid")

    type: Literal["percent", "fixed"] = Field(..., description="滑点类型")
    value: float = Field(..., description="滑点值（percent=相对价格比例 / fixed=绝对价格）")


class BacktestConfigModel(BaseModel):
    """回测配置，1:1 映射 ``aq.run_backtest`` 参数。Schema §3.4。

    默认值按 Schema §3.4 表（broker_profile 默认 cn_stock_miniqmt；t_plus_one 默认 True；
    volume_limit_pct 默认 0.25；lot_size 默认 100；history_depth 默认 60；
    timezone 默认 Asia/Shanghai；show_progress 默认 False；strict_strategy_params 默认 True）。

    注意：Schema §3.4 表中 strict_strategy_params 默认 False，但任务说明要求 True
    （akquant 原生默认 True，严格校验构造参数）。此处取 True 与 akquant 对齐。
    """

    model_config = ConfigDict(extra="forbid")

    initial_cash: float = Field(100000.0, description="初始资金")
    start_date: Optional[str] = Field(None, description="回测起始日期（YYYY-MM-DD）→ start_time")
    end_date: Optional[str] = Field(None, description="回测结束日期（YYYY-MM-DD）→ end_time")
    benchmark: str = Field(
        "000300.SH",
        description="基准指数代码（如 000300.SH/000905.SH），用于回测报告相对收益比较与净值叠加。"
        "不透传 aq.run_backtest，由 watcher 拼装 benchmark_data 传 engine，engine 归一化到初始净值 1.0 后叠加",
    )
    broker_profile: Optional[str] = Field(
        "cn_stock_miniqmt", description="A 股费率模板（profile 不含 t_plus_one，需单独传）"
    )
    t_plus_one: bool = Field(True, description="A 股 T+1（必须显式传 True）")
    commission_rate: Optional[float] = Field(None, description="佣金率（覆盖 profile）")
    stamp_tax_rate: Optional[float] = Field(None, description="印花税（仅卖出，A 股 0.001）")
    transfer_fee_rate: Optional[float] = Field(None, description="过户费率")
    min_commission: Optional[float] = Field(None, description="单笔最低佣金（A 股 5 元）")
    slippage: Optional[Union[float, SlippageDict]] = Field(
        None, description="滑点（推荐 dict：{type, value}）"
    )
    volume_limit_pct: Optional[float] = Field(
        0.25, description="单 bar 最大成交占当根成交量比例"
    )
    lot_size: int = Field(100, description="手数（A 股 100）")
    warmup_period: Optional[int] = Field(None, description="预热 bar 数（≥ 策略最大历史窗口）")
    history_depth: Optional[int] = Field(60, description="历史缓冲深度（决定 get_history 上限）")
    fill_policy: Optional[Dict[str, Any]] = Field(
        None, description="成交语义（{price_basis, temporal, bar_offset}）"
    )
    timezone: Optional[str] = Field("Asia/Shanghai", description="时区")
    show_progress: Optional[bool] = Field(False, description="是否显示进度条（生产建议 False）")
    risk_config: Optional[Dict[str, Any]] = Field(None, description="风控配置（max_position_pct 等）")
    strict_strategy_params: Optional[bool] = Field(
        True, description="严格校验策略构造参数（动态工厂建议 False）"
    )

    @model_validator(mode="after")
    def _check_broker_profile(self) -> "BacktestConfigModel":
        """broker_profile 合法性（白名单校验放在结构层，避免非法值进入 akquant）。"""
        # 延迟导入避免循环依赖
        from services.strategy.constants import BROKER_PROFILES

        if self.broker_profile is not None and self.broker_profile not in BROKER_PROFILES:
            raise ValueError(
                f"broker_profile 不在白名单 {sorted(BROKER_PROFILES)}，收到 '{self.broker_profile}'"
            )
        return self


# ============================================================
# §3.1 顶层 StrategyConfigModel
# ============================================================

class StrategyConfigModel(BaseModel):
    """顶层策略配置。Schema §3.1。

    ``strategy_id`` Schema 标必填，此处放宽为 Optional（HTTP 创建场景由后端生成 ID）；
    ``name`` 是唯一必填字段。``trading_config`` 内部约束（signals/rebalance 至少一个）
    在 validator 层校验。
    """

    model_config = ConfigDict(extra="forbid")

    strategy_id: Optional[str] = Field(None, description="策略唯一 ID（业务层）")
    name: str = Field(..., description="展示名（注入防护在 validator 层）")
    description: Optional[str] = Field(None, description="描述")
    screen_config: Optional[ScreenConfigModel] = Field(None, description="选股配置（§3.2）")
    trading_config: Optional[TradingConfigModel] = Field(
        None, description="交易配置（§3.3）；至少含 signals 或 rebalance 之一"
    )
    backtest_config: Optional[BacktestConfigModel] = Field(None, description="回测配置（§3.4）")


# ============================================================
# HTTP 请求/响应模型
# ============================================================

class StrategyValidationErrorModel(BaseModel):
    """校验错误 pydantic 版本（用于 HTTP 响应序列化）。

    与 :class:`errors.StrategyValidationError`（dataclass）字段一一对应；
    序列化时由 validator 层把 dataclass 列表转成此模型列表。
    """

    model_config = ConfigDict(extra="forbid")

    path: str = Field(..., description="错误字段的 JSON path")
    code: str = Field(..., description="稳定错误码（见 ErrorCode）")
    message: str = Field(..., description="中文默认提示信息")


class ValidateRequest(BaseModel):
    """POST /api/strategy/validate 请求体。

    ``config`` 是原始 JSON dict（未结构化），由路由层调
    :class:`StrategyConfigModel` 解析后再走业务规则 validator。
    """

    model_config = ConfigDict(extra="forbid")

    config: Dict[str, Any] = Field(..., description="待校验的策略配置 JSON（原始 dict）")


class ValidateResponse(BaseModel):
    """POST /api/strategy/validate 响应体。

    ``valid=True`` 时 ``errors`` 为空；``valid=False`` 时 ``errors`` 含所有校验错误
    （结构错误 + 业务规则错误合并）。
    """

    model_config = ConfigDict(extra="forbid")

    valid: bool = Field(..., description="是否通过校验")
    errors: List[StrategyValidationErrorModel] = Field(
        default_factory=list, description="校验错误列表（空=通过）"
    )
