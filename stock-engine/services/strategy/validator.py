"""策略配置业务规则校验器（统一策略配置 Schema §7）。

结构校验（字段存在性 / 类型 / 枚举值）已由 :mod:`services.strategy.models` 的
Pydantic 模型在解析阶段完成；本层只做**业务规则**校验：

- §7.1 结构约束：signals/rebalance 至少一个、manual 池必须 stocks、ranking
  composite/single 必填字段、position_sizing 白名单 + target 必填、ATR 倍数等。
- §7.2 条件模型约束：递归遍历 ConditionTree / ExpressionNode，按上下文
  （screen vs trading）校验 comparator / ref / cross_* 的合法性。
- §7.3 因子节点约束：factorKey 白名单、基本面因子不允许出现在 trading_config、
  多输出因子必须带 output_index 且在范围内。
- 安全约束：对自由文本字段（name / description / ranking weight key /
  exit.rules[].name）做危险字符串黑名单检查（注入防护）。

设计要点：
- **非短路**：所有错误一次性收集后返回，调用方聚合为 422 响应。
- **path 格式**：点号路径，数组索引用 ``[n]``，如
  ``trading_config.signals.buy.conditions[0].left.factor``。
- ErrorCode 是 ``(code, message)`` 元组，取 ``[0]`` / ``[1]`` 构造错误对象。
"""
from typing import List, Optional

import logging

from services.strategy.errors import ErrorCode, StrategyValidationError
from services.strategy import constants
from services.strategy.models import (
    StrategyConfigModel,
    TradingConfigModel,
    ScreenConfigModel,
    ConditionTree,
    CompareLeaf,
    FactorNode,
    OpNode,
    RefNode,
    ValueNode,
    PositionSizingModel,
    FactorModel,
)

logger = logging.getLogger(__name__)


# position_sizing.method 需要 target 的方法集合（与 buy_all/close_position 区分）
# 对齐 constants.POSITION_SIZING_METHODS 中带目标值的下单方法
_METHODS_REQUIRING_TARGET = {
    "order_target_percent",
    "order_target_value",
    "order_target",
    "buy",
    "sell",
    "order_target_weights",
}


class _Ctx:
    """条件树遍历上下文，标记当前在 screen 还是 trading 路径。

    不同路径允许的比较器集合不同（screen 禁 cross_*）。
    """

    __slots__ = ("is_screen",)

    def __init__(self, is_screen: bool):
        self.is_screen = is_screen

    @property
    def allowed_comparators(self) -> set:
        return constants.SCREEN_COMPARATORS if self.is_screen else constants.TRADING_COMPARATORS


class StrategyValidator:
    """策略配置业务规则校验器。

    用法::

        validator = StrategyValidator()
        errors = validator.validate(config)
        if errors:
            # 聚合成 422 响应
            ...

    所有 ``_validate_*`` 方法均**原地追加**到 ``errors`` 列表，遇到错误不短路，
    以保证一次性返回所有问题。
    """

    # ========================================================
    # 入口
    # ========================================================
    def validate(self, config: StrategyConfigModel) -> List[StrategyValidationError]:
        """对一份策略配置执行全部业务规则校验，返回错误列表（空=通过）。"""
        errors: List[StrategyValidationError] = []

        # 1. 注入防护（自由文本字段，独立于结构/条件树）
        self._validate_injection(config, errors)

        # 2. trading_config 结构 + 条件树
        if config.trading_config is not None:
            self._validate_structure_trading(config.trading_config, errors)
            self._validate_trading_conditions_and_factors(config.trading_config, errors)

        # 3. screen_config 结构 + 条件树
        if config.screen_config is not None:
            self._validate_structure_screen(config.screen_config, errors)
            self._validate_screen_conditions(config.screen_config, errors)

        # 4. signals 范式 universe 规模约束（跨 trading_config + screen_config 联动）
        self._validate_signals_universe(config, errors)

        # 5. signals 范式下 screen_config 仅允许 universe+stocks，禁止 conditions/ranking/top_n/filters
        self._validate_signals_screen_fields(config, errors)

        # 6. factor.method=single × rebalance.weight_mode=score 互斥（spec 011 P0-2）
        self._validate_factor_score_compatibility(config, errors)

        return errors

    def validate_screen_structure(self, raw_config: dict) -> List[StrategyValidationError]:
        """screen_config 4 层结构预检（spec 010 缺陷 C）。

        在 Pydantic 解析（``StrategyConfigModel.model_validate``）之前对原始 dict 做轻量结构判定，
        把「旧 5 字段扁平结构」「缺失 universe 必需层」映射到专用错误码，给出可迁移的友好提示——
        否则这两类情形会被 ``ScreenConfigModel`` 的 ``extra="forbid"`` / 必填 ``universe`` 拦成
        通用 Pydantic ValidationError，迁移体验差。

        - 旧扁平结构（universe 为字符串，或顶层含 conditions/ranking/top_n/filters）
          → ``SCREEN_CONFIG_DEPRECATED_STRUCTURE``；
        - 缺失 universe 层（screen_config 为对象但无 universe 键 / universe 为 null）
          → ``SCREEN_CONFIG_LAYER_MISSING``；
        - 其余（新 4 层 / 无 screen_config / 非对象）返回空列表，交由 Pydantic + 业务校验继续。

        :param raw_config: 原始策略配置 dict（未解析）。
        """
        if not isinstance(raw_config, dict):
            return []
        sc = raw_config.get("screen_config")
        if not isinstance(sc, dict):
            return []
        universe = sc.get("universe")
        has_legacy_keys = any(k in sc for k in ("conditions", "ranking", "top_n", "filters"))
        if isinstance(universe, str) or has_legacy_keys:
            code, msg = ErrorCode.SCREEN_CONFIG_DEPRECATED_STRUCTURE
            return [StrategyValidationError(path="screen_config", code=code, message=msg)]
        if "universe" not in sc or universe is None:
            code, msg = ErrorCode.SCREEN_CONFIG_LAYER_MISSING
            return [StrategyValidationError(path="screen_config.universe", code=code, message=msg)]
        return []

    # ========================================================
    # §7.1 结构约束：trading_config
    # ========================================================
    def _validate_structure_trading(
        self, tc: TradingConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        # (1) signals 或 rebalance 至少一个在场
        #     signals 对象存在（即便内部 buy/sell 都 None）也算"在场"
        has_signals = tc.signals is not None
        has_rebalance = tc.rebalance is not None

        if not has_signals and not has_rebalance:
            code, msg = ErrorCode.MISSING_SIGNALS_OR_REBALANCE
            errors.append(
                StrategyValidationError(path="trading_config", code=code, message=msg)
            )
            # 不 return：保持非短路语义，继续收集 position_sizing/exit 等错误
            # （与改造前行为一致，test_multiple_errors_non_short_circuit 依赖此）

        # (1b) signals 与 rebalance 互斥（择时范式 vs 轮动范式，二选一）
        # 互斥时后续 position_sizing/exit 校验无意义（配置已非法），return。
        if has_signals and has_rebalance:
            code, msg = ErrorCode.SIGNALS_REBALANCE_EXCLUSIVE
            errors.append(
                StrategyValidationError(path="trading_config", code=code, message=msg)
            )
            return

        # (5) position_sizing.method 白名单
        ps: Optional[PositionSizingModel] = tc.position_sizing
        if ps is not None:
            if ps.method not in constants.POSITION_SIZING_METHODS:
                code, msg = ErrorCode.INVALID_POSITION_METHOD
                errors.append(
                    StrategyValidationError(
                        path="trading_config.position_sizing.method",
                        code=code,
                        message=msg,
                    )
                )
            else:
                # (6) 需要 target 的 method 必须带 target
                if ps.method in _METHODS_REQUIRING_TARGET and ps.target is None:
                    code, msg = ErrorCode.POSITION_TARGET_REQUIRED
                    errors.append(
                        StrategyValidationError(
                            path="trading_config.position_sizing.target",
                            code=code,
                            message=msg,
                        )
                    )

        # (7) exit.bracket.use_atr_stop=True 时 atr_multiplier 必填
        ex = tc.exit
        if ex is not None and ex.bracket is not None:
            br = ex.bracket
            if br.use_atr_stop is True and br.atr_multiplier is None:
                code, msg = ErrorCode.ATR_MULTIPLIER_REQUIRED
                errors.append(
                    StrategyValidationError(
                        path="trading_config.exit.bracket",
                        code=code,
                        message=msg,
                    )
                )

        # (8) rebalance.trigger 校验 + day_of_period 兼容映射（spec 011 P2-1）
        self._validate_rebalance_trigger(tc, errors)

    # ========================================================
    # §7.1 调仓触发（spec 011 P2-1）
    # ========================================================
    def _validate_rebalance_trigger(
        self, tc: TradingConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        """rebalance.trigger 联动校验 + day_of_period 兼容映射。

        - ``trigger`` 非 first/last/None（Pydantic Literal 已拦截，但此处兜底防御）
          → ``INVALID_REBALANCE_TRIGGER``；
        - ``frequency=daily`` 时 trigger 可忽略（不报错）；
        - 检测到 ``day_of_period`` 在场而 ``trigger`` 为 None 时，
          打 deprecation warning（实际映射由 compiler 完成）。

        .. note::
            ``trigger`` 默认值为 ``None``（不是 first），因此可以通过
            ``rb.trigger is None`` 准确判断"用户未显式指定 trigger"。
            真实 trigger 由 ``compiler._resolve_rebalance_trigger`` 在编译期
            做最终解析（day_of_period 映射 + 缺省规约为 first）。
        """
        rb = tc.rebalance
        if rb is None:
            return

        # trigger 合法性兜底（Literal 已拦截，防御双保险）
        if rb.trigger is not None and rb.trigger not in ("first", "last"):
            code, msg = ErrorCode.INVALID_REBALANCE_TRIGGER
            errors.append(
                StrategyValidationError(
                    path="trading_config.rebalance.trigger",
                    code=code,
                    message=msg,
                )
            )
            return

        # day_of_period 兼容：检测到旧字段且 trigger 缺失/默认时打 warning
        if rb.day_of_period is not None:
            mapped = "first" if (rb.day_of_period or 0) <= 1 else "last"
            logger.warning(
                "rebalance.day_of_period=%s 已废弃（spec 011 P2-1），"
                "自动映射为 trigger=%r；建议改用 rebalance.trigger(first/last)。",
                rb.day_of_period,
                mapped,
            )

    # ========================================================
    # §7.1 结构约束：screen_config
    # ========================================================
    def _validate_structure_screen(
        self, sc: ScreenConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        # (2) universe.pool=manual 时 universe.stocks 必须非空
        if sc.universe.pool == "manual":
            if not sc.universe.stocks:  # None 或空列表都算缺失
                code, msg = ErrorCode.MANUAL_SYMBOL_REQUIRED
                errors.append(
                    StrategyValidationError(
                        path="screen_config.universe.stocks", code=code, message=msg
                    )
                )

        # (3)(4) factor 打分规则（4 层结构：原 ranking 平移到 factor）
        factor: Optional[FactorModel] = sc.factor
        if factor is not None:
            if factor.method == "composite":
                if not factor.weights:  # None 或空 dict 都算缺失
                    code, msg = ErrorCode.RANKING_WEIGHTS_REQUIRED
                    errors.append(
                        StrategyValidationError(
                            path="screen_config.factor.weights",
                            code=code,
                            message=msg,
                        )
                    )
            elif factor.method == "single":
                if factor.factor is None or factor.order is None:
                    code, msg = ErrorCode.RANKING_SINGLE_FIELD_REQUIRED
                    errors.append(
                        StrategyValidationError(
                            path="screen_config.factor", code=code, message=msg
                        )
                    )

    # ========================================================
    # §7.1 联动校验：signals 范式 universe 规模约束
    # ========================================================
    def _validate_signals_universe(
        self, config: StrategyConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        """signals（择时）范式的 universe 规模约束。

        - 仅当 trading_config.signals 在场时触发；
        - screen_config.universe.pool 必须为 "manual"（否则 SIGNALS_UNIVERSE_NOT_MANUAL）；
        - screen_config.universe.stocks 数量 <= SIGNALS_MAX_UNIVERSE_SIZE（否则 SIGNALS_UNIVERSE_TOO_LARGE）。

        设计依据：signals 范式对每个命中 symbol 独立 order_target_percent，
        多标的会资金争抢导致大面积拒单且不可复现；故限定为 manual 小池子。
        """
        tc = config.trading_config
        if tc is None or tc.signals is None:
            return

        sc = config.screen_config
        if sc is None:
            # signals 范式必须有 screen_config 圈定 manual 池；缺失直接按 not_manual 报错
            code, msg = ErrorCode.SIGNALS_UNIVERSE_NOT_MANUAL
            errors.append(
                StrategyValidationError(
                    path="screen_config.universe", code=code, message=msg
                )
            )
            return

        if sc.universe.pool != "manual":
            code, msg = ErrorCode.SIGNALS_UNIVERSE_NOT_MANUAL
            errors.append(
                StrategyValidationError(
                    path="screen_config.universe.pool", code=code, message=msg
                )
            )
            return

        stocks = sc.universe.stocks
        stocks_count = len(stocks) if stocks else 0
        max_size = constants.SIGNALS_MAX_UNIVERSE_SIZE
        if stocks_count > max_size:
            code, msg = ErrorCode.SIGNALS_UNIVERSE_TOO_LARGE
            msg = msg.format(max=max_size, actual=stocks_count)
            errors.append(
                StrategyValidationError(
                    path="screen_config.universe.stocks", code=code, message=msg
                )
            )

    def _validate_signals_screen_fields(
        self, config: StrategyConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        """signals（择时）范式下 screen_config 的字段禁用约束。

        signals 范式只消费 screen_config.universe（圈定回测 symbols）；
        factor / filter / portfolio 三层即使填写也不会被 compiler 执行，
        属于"静默忽略"。为防止用户误填产生困惑，升级为错误。
        """
        tc = config.trading_config
        if tc is None or tc.signals is None:
            return
        sc = config.screen_config
        if sc is None:
            return  # screen_config 缺失已由 _validate_signals_universe 处理

        forbidden_paths = []
        if sc.factor is not None:
            forbidden_paths.append("screen_config.factor")
        if sc.filter is not None:
            forbidden_paths.append("screen_config.filter")
        if sc.portfolio is not None:
            forbidden_paths.append("screen_config.portfolio")

        if forbidden_paths:
            code, msg = ErrorCode.SIGNALS_SCREEN_CONFIG_FORBIDDEN
            for path in forbidden_paths:
                errors.append(
                    StrategyValidationError(path=path, code=code, message=msg)
                )

    # ========================================================
    # spec 011 P0-2：factor.method=single × rebalance.weight_mode=score 互斥
    # ========================================================
    def _validate_factor_score_compatibility(
        self, config: StrategyConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        """factor.method=single 与 rebalance.weight_mode=score 不兼容（spec 011 P0-2）。

        - 仅当 rebalance 在场（轮动范式）时触发；
        - ``rebalance.weight_mode == "score"`` 且 ``screen_config.factor.method == "single"``
          → 报 ``FACTOR_SCORE_INCOMPATIBLE``。

        设计依据：``factor.method=single`` 时 score 是因子原始值（量纲不一，如 PE=15、
        换手率=0.03），与 ``weight_mode=score`` 的归一化加权语义不兼容——直接按原始值
        归一化会导致量纲大的因子独占资金。``composite`` 多因子打分已做量纲统一，
        或改用 ``weight_mode=equal`` 等权规避。
        """
        tc = config.trading_config
        if tc is None or tc.rebalance is None:
            return

        weight_mode = tc.rebalance.weight_mode
        if weight_mode != "score":
            return

        sc = config.screen_config
        if sc is None or sc.factor is None:
            return

        if sc.factor.method == "single":
            code, msg = ErrorCode.FACTOR_SCORE_INCOMPATIBLE
            errors.append(
                StrategyValidationError(
                    path="trading_config.rebalance.weight_mode",
                    code=code,
                    message=msg,
                )
            )

    # ========================================================
    # §7.2 条件树遍历：trading_config 入口
    # ========================================================
    def _validate_trading_conditions_and_factors(
        self, tc: TradingConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        ctx = _Ctx(is_screen=False)

        # signals.buy.conditions / signals.sell.conditions
        if tc.signals is not None:
            if tc.signals.buy is not None:
                self._walk_condition_tree(
                    tc.signals.buy, "trading_config.signals.buy", ctx, errors
                )
            if tc.signals.sell is not None:
                self._walk_condition_tree(
                    tc.signals.sell, "trading_config.signals.sell", ctx, errors
                )

        # exit.rules[i].condition
        if tc.exit is not None and tc.exit.rules:
            for i, rule in enumerate(tc.exit.rules):
                base = f"trading_config.exit.rules[{i}].condition"
                # ExitRuleModel.condition 是必填 ConditionTree，不会为 None
                self._walk_condition_tree(rule.condition, base, ctx, errors)

    # ========================================================
    # §7.2 条件树遍历：screen_config 入口
    # ========================================================
    def _validate_screen_conditions(
        self, sc: ScreenConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        # conditions 现在位于 filter 层（4 层结构）；filter 为 None 时无条件树可校验
        if sc.filter is None or sc.filter.conditions is None:
            return
        ctx = _Ctx(is_screen=True)
        self._walk_condition_tree(
            sc.filter.conditions, "screen_config.filter.conditions", ctx, errors
        )

    # ========================================================
    # §7.2 条件树递归
    # ========================================================
    def _walk_condition_tree(
        self,
        node,
        path: str,
        ctx: _Ctx,
        errors: List[StrategyValidationError],
    ) -> None:
        """递归遍历条件树节点（ConditionTree 或 CompareLeaf）。

        - ConditionTree：遍历 conditions 列表，子节点 path 用 ``parent.conditions[i]``
        - CompareLeaf：校验 comparator + 递归 left/right 表达式
        """
        # 空列表/None 不报错（空条件树合法），由调用方控制是否进入
        if node is None:
            return

        if isinstance(node, CompareLeaf):
            # 比较器合法性（screen 禁 cross_*）
            cmp_path = f"{path}.comparator"
            comparator = node.comparator
            if comparator not in ctx.allowed_comparators:
                # screen 上下文下出现 cross_up/cross_down → 专用错误码
                if ctx.is_screen and comparator in ("cross_up", "cross_down"):
                    code, msg = ErrorCode.SCREEN_TIME_SERIES_FORBIDDEN
                else:
                    code, msg = ErrorCode.INVALID_COMPARATOR
                errors.append(
                    StrategyValidationError(path=cmp_path, code=code, message=msg)
                )
            else:
                # trading 上下文下 cross_up/cross_down 要求左右均为 FactorNode
                if not ctx.is_screen and comparator in ("cross_up", "cross_down"):
                    if not isinstance(node.left, FactorNode) or not isinstance(
                        node.right, FactorNode
                    ):
                        code, msg = ErrorCode.CROSS_REQUIRES_FACTOR_NODES
                        errors.append(
                            StrategyValidationError(path=path, code=code, message=msg)
                        )

            # 递归 left / right 表达式
            self._walk_expression(node.left, f"{path}.left", ctx, errors)
            self._walk_expression(node.right, f"{path}.right", ctx, errors)
            return

        if isinstance(node, ConditionTree):
            # 空列表合法，不报错；有元素则递归
            for i, child in enumerate(node.conditions):
                self._walk_condition_tree(
                    child, f"{path}.conditions[{i}]", ctx, errors
                )
            return

        # 理论上不可达（Pydantic Union 已限定类型）；防御性记录
        code, msg = ErrorCode.INVALID_COMPARATOR
        errors.append(
            StrategyValidationError(path=path, code=code, message=f"未知条件节点类型: {msg}")
        )

    # ========================================================
    # §7.2 表达式节点遍历
    # ========================================================
    def _walk_expression(
        self,
        node,
        path: str,
        ctx: _Ctx,
        errors: List[StrategyValidationError],
    ) -> None:
        """递归遍历表达式节点（FactorNode / OpNode / RefNode / ValueNode）。"""
        if isinstance(node, FactorNode):
            self._validate_factor_node(node, path, ctx, errors)
            return

        if isinstance(node, OpNode):
            # op 已被 Pydantic Literal 校验（仅 +-*/），无需再校验
            self._walk_expression(node.left, f"{path}.left", ctx, errors)
            self._walk_expression(node.right, f"{path}.right", ctx, errors)
            return

        if isinstance(node, RefNode):
            # screen 上下文禁止任何 ref
            if ctx.is_screen:
                code, msg = ErrorCode.SCREEN_REF_FORBIDDEN
                errors.append(
                    StrategyValidationError(path=path, code=code, message=msg)
                )
                return
            # trading 上下文：ref 必须在白名单
            if node.ref not in constants.ALLOWED_REFS:
                code, msg = ErrorCode.UNKNOWN_REF_KEY
                errors.append(
                    StrategyValidationError(
                        path=f"{path}.ref", code=code, message=msg
                    )
                )
            return

        if isinstance(node, ValueNode):
            # 静态值节点无需校验
            return

        # 理论上不可达
        code, msg = ErrorCode.INVALID_OP
        errors.append(
            StrategyValidationError(path=path, code=code, message=f"未知表达式节点类型: {msg}")
        )

    # ========================================================
    # §7.3 因子节点约束
    # ========================================================
    def _validate_factor_node(
        self,
        node: FactorNode,
        path: str,
        ctx: _Ctx,
        errors: List[StrategyValidationError],
    ) -> None:
        factor = node.factor

        # (1) factorKey 白名单（技术面 + 基本面并集）
        if factor not in constants.ALL_FACTOR_KEYS:
            code, msg = ErrorCode.UNKNOWN_FACTOR
            errors.append(
                StrategyValidationError(
                    path=f"{path}.factor", code=code, message=msg
                )
            )
            # 未知因子无需再做多输出/基本面检查，直接返回
            return

        # (2) 基本面因子不允许出现在 trading_config（实时路径不支持）
        if factor in constants.FUNDAMENTAL_FACTOR_KEYS and not ctx.is_screen:
            code, msg = ErrorCode.FUNDAMENTAL_FACTOR_IN_TRADING
            errors.append(
                StrategyValidationError(
                    path=f"{path}.factor", code=code, message=msg
                )
            )

        # (3) 多输出因子必须带 output_index 且在范围内
        if factor in constants.MULTI_OUTPUT_FACTORS:
            outputs = constants.MULTI_OUTPUT_FACTORS[factor]
            if node.output_index is None:
                code, msg = ErrorCode.MULTI_OUTPUT_REQUIRES_INDEX
                errors.append(
                    StrategyValidationError(
                        path=f"{path}.output_index", code=code, message=msg
                    )
                )
            else:
                # output_index 必须在 [0, len(outputs)-1]
                max_idx = len(outputs) - 1
                if node.output_index < 0 or node.output_index > max_idx:
                    code, msg = ErrorCode.MULTI_OUTPUT_INDEX_OUT_OF_RANGE
                    errors.append(
                        StrategyValidationError(
                            path=f"{path}.output_index", code=code, message=msg
                        )
                    )

        # (4) transform 作用域约束：仅 screen_config.filter.conditions 合法
        #（ranking 的 factor.weights 值为数字，无法挂载 transform；trading_config 会静默忽略）。
        tf = node.transform
        if tf is not None and not ctx.is_screen:
            code, msg = ErrorCode.TRANSFORM_NOT_ALLOWED_OUTSIDE_SCREEN
            errors.append(
                StrategyValidationError(
                    path=f"{path}.transform", code=code, message=msg
                )
            )

        # (5) transform window 上限校验（PRD 009 §1 P1-6）。
        # type 由 Pydantic Literal 在解析阶段保证；window 下限由 Pydantic ge=1 保证。
        if tf is not None and tf.window > 60:
            code, msg = ErrorCode.INVALID_TRANSFORM_WINDOW
            errors.append(
                StrategyValidationError(
                    path=f"{path}.transform.window", code=code, message=msg
                )
            )

    # ========================================================
    # 注入防护（自由文本字段）
    # ========================================================
    def _validate_injection(
        self, config: StrategyConfigModel, errors: List[StrategyValidationError]
    ) -> None:
        """对自由文本字段做危险字符串黑名单检查。

        枚举字段（method / comparator / op / ref）由 Pydantic Literal 或本层
        白名单天然防护，无需黑名单。
        """
        # 顶层 name / description
        self._check_dangerous_text(config.name, "name", errors)
        if config.description is not None:
            self._check_dangerous_text(config.description, "description", errors)

        # screen_config.factor.weights 的 key（自定义 factorKey 字符串）
        sc = config.screen_config
        if (
            sc is not None
            and sc.factor is not None
            and sc.factor.weights
        ):
            for key in sc.factor.weights.keys():
                self._check_dangerous_text(
                    str(key), "screen_config.factor.weights", errors
                )

        # trading_config.exit.rules[].name（规则展示名）
        tc = config.trading_config
        if tc is not None and tc.exit is not None and tc.exit.rules:
            for i, rule in enumerate(tc.exit.rules):
                if rule.name is not None:
                    self._check_dangerous_text(
                        rule.name, f"trading_config.exit.rules[{i}].name", errors
                    )

    def _check_dangerous_text(
        self, text: str, path: str, errors: List[StrategyValidationError]
    ) -> None:
        """命中任一危险模式即报 INJECTION_FORBIDDEN（非短路，扫全部模式）。"""
        if not isinstance(text, str):
            return
        for pattern in constants.DANGEROUS_PATTERNS:
            if pattern in text:
                code, msg = ErrorCode.INJECTION_FORBIDDEN
                errors.append(
                    StrategyValidationError(path=path, code=code, message=msg)
                )
                # 单字段命中一次即可，避免重复追加
                return
