# 策略管理模块 - The Implementation Plan

## [ ] Task 1: 数据库 Schema + DO/Mapper/枚举/错误码（watcher 侧）
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在 `schema-sqlite.sql` 和 `schema-mysql.sql` 中追加 `quant_strategy` 和 `quant_strategy_version` 两张表 DDL
  - 新增枚举类（实现 DisplayableEnum）：`StrategyCategoryEnum`（技术面/基本面/混合/自定义）、`StrategyScopeEnum`（single/portfolio/mixed）、`StrategyStatusEnum`（DRAFT/VERIFIED/ACTIVE/ARCHIVED）
  - 新建 DO：`QuantStrategyDO`（@TableName("quant_strategy")）、`QuantStrategyVersionDO`（@TableName("quant_strategy_version")）
  - 新建 Mapper：`QuantStrategyMapper extends BaseMapper<QuantStrategyDO>`、`QuantStrategyVersionMapper extends BaseMapper<QuantStrategyVersionDO>`
  - 在 `ErrorCode` 类追加策略相关错误码：`STRATEGY_NOT_FOUND(4040x)`、`STRATEGY_VALIDATION_FAILED(4000x)`、`ENGINE_SERVICE_UNAVAILABLE(503xx)`、`STRATEGY_INVALID_STATUS_TRANSITION(4000x)`、`STRATEGY_VERSION_NOT_FOUND(4040x)`、`STRATEGY_CONFIG_TOO_LARGE(4000x)`、`STRATEGY_VERSION_CONFLICT(4090x)`（并发更新冲突），确认无重复值
  - DO 类 created_at/updated_at 字段统一存 **UTC ISO8601 字符串**（`Instant.now().toString()` 或 `DateTimeFormatter.ISO_INSTANT`）；tags 字段入库前 trim 且去除含逗号的 tag
  - 对照现有枚举（如 ScreenerCategoryEnum）的实现模式
- **Acceptance Criteria Addressed**: FR-1, AC-1
- **Test Requirements**:
  - `programmatic` TR-1.1: SQLite DDL 可执行（CREATE TABLE IF NOT EXISTS 无语法错误），两张表字段与 spec FR-1 一致
  - `programmatic` TR-1.2: MySQL DDL 同步更新，字段类型与 SQLite 对齐
  - `programmatic` TR-1.3: 枚举类 code/label 正确，DisplayableEnum.getCode() 返回枚举值
  - `programmatic` TR-1.4: DO 字段与表列一一对应，@TableName/@TableId 注解正确
  - `programmatic` TR-1.5: Mapper 可被 Spring 扫描注入（context loads 无错误）
  - `programmatic` TR-1.6: ErrorCode 无重复值（写个简单 test 遍历校验）
- **Notes**: 版本表 strategy_id 字段为 INTEGER（外键到 quant_strategy.id），不是 TEXT（与 quant_strategy 主键类型一致）；主表 strategy_id 是业务 ID（TEXT UNIQUE）用于对外暴露。

## [ ] Task 2: engine 侧 Pydantic 模型（models.py + constants + errors）
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在 stock-engine `services/strategy/` 目录下新建模块：
    - `__init__.py`
    - `constants.py`: 
      - `POSITION_SIZING_METHODS`（order_target_percent/order_target_value/order_target/buy/sell/buy_all/close_position/order_target_weights）
      - `SELL_METHODS`（close_position/sell/signal_based）
      - `SCREEN_COMPARATORS`（`>` `<` `>=` `<=` `==` `!=`，**不含 cross_***）
      - `TRADING_COMPARATORS`（SCREEN_COMPARATORS + `cross_up` + `cross_down`）
      - `ALLOWED_REFS`（entry_price/position_pnl_pct/position_qty/bars_held）
      - `BROKER_PROFILES`（cn_stock_miniqmt/cn_stock_t1_low_fee/cn_stock_sim_high_slippage）
      - `TECHNICAL_FACTOR_KEYS`（20 个，对齐 Schema §4.5：MA/EMA/BOLL/SAR/MACD/RSI/KDJ/ADX/PLUS_DI/MINUS_DI/WILLR/CCI/ATR/OBV/CLOSE/HIGH/LOW/VOLUME/VOL_MA/VOL_EMA）
      - `FUNDAMENTAL_FACTOR_KEYS`（PE_TTM/PB/TOTAL_MV/ROE_TTM/REVENUE_GROWTH/NET_PROFIT_GROWTH/GROSS_MARGIN/CURRENT_RATIO/TURNOVER_RATE/NORTHBOUND_NET_INFLOW，对齐 Schema §4.5 基本面表）
      - `MULTI_OUTPUT_FACTORS`（多输出因子 output_index 映射：`{"MACD": ["dif","dea","hist"], "BOLL": ["upper","mid","lower"], "KDJ": ["k","d","j"]}`，校验 output_index ∈ [0, len(outputs)-1]）
    - `errors.py`: 错误码常量（与 watcher ErrorCode 保持一致的 code 值和中文 message），StrategyValidationError 数据类（path: str, code: str, message: str）
    - `models.py`: Pydantic v2 模型，**严格对齐 Schema §3/§4 字段名**：
      - ExpressionNode discriminated union（**对齐 Schema §4.3**）：ValueNode(`{value}`) / FactorNode(`{factor, params?, inputs?, output_index?}`) / OpNode(`{op: Literal["+","-","*","/"], left, right}`) / RefNode(`{ref}`) — 用 Literal type + discriminator
      - CompareLeaf（`{type:"compare", left, comparator, right}`，对齐 Schema §4.2）
      - ConditionTree（**对齐 Schema §4.1**：`{operator: Literal["AND","OR"], conditions: List[Union[ConditionTree, CompareLeaf]]}`，**不是 type/children 结构**）
      - RankingModel(method, weights?, factor?, order?)
      - StaticFiltersModel（**对齐 Schema §3.2.2 字段名**：exclude_st, exclude_suspended, exclude_limit_up, exclude_limit_down, industries, exclude_industries, min_list_days）
      - ScreenConfigModel(universe, stocks?, top_n?, conditions?, ranking?, filters?)
      - SignalsModel(buy?, sell?, eval_scope?)
      - PositionSizingModel（**对齐 Schema §3.3.2**：method, target?, params?, sell_method?）— target 是统一字段，不分散
      - BracketModel(stop_loss_pct?, take_profit_pct?, use_atr_stop?, atr_period?, atr_multiplier?)
      - ExitRuleModel(name?, condition: ConditionTree, action?)
      - ExitModel(bracket?, rules?)
      - RebalanceModel（**对齐 Schema §3.3.4**：frequency, day_of_period?, replace_method?, weight_mode?, max_single_position?, long_only?）
      - TradingConfigModel(symbols?, signals?, position_sizing?, exit?, rebalance?)
      - SlippageModel（**对齐 Schema §3.4**：支持 number 或 `{type:"percent"|"fixed", value}` 两种形态，用 `Union[float, SlippageDict]` + root_validator）
      - BacktestConfigModel（**对齐 Schema §3.4 字段**：initial_cash, start_date?, end_date?, broker_profile?, t_plus_one?, commission_rate?, stamp_tax_rate?, transfer_fee_rate?, min_commission?, slippage?, volume_limit_pct?, lot_size?, warmup_period?, history_depth?, fill_policy?, timezone?, show_progress?, risk_config?, strict_strategy_params?）
      - 顶层 StrategyConfigModel(strategy_id?, name, description?, scope?, screen_config?, trading_config?, backtest_config?)
      - HTTP: ValidateRequest(config: dict)、ValidateResponse(valid: bool, errors: list[StrategyValidationError])
  - 默认值对齐 Schema：single/portfolio/mixed 默认值、cn_stock_miniqmt 默认、T+1 默认 true、lot_size 默认 100 等
- **Acceptance Criteria Addressed**: FR-4, NFR-6, AC-7
- **Test Requirements**:
  - `programmatic` TR-2.1: Schema §5.1 双均线示例 JSON 可被 StrategyConfigModel.model_validate() 成功解析，无 ValidationError
  - `programmatic` TR-2.2: Schema §5.2 多因子价值示例 JSON 可被成功解析
  - `programmatic` TR-2.3: 缺少必填字段（如 trading_config.signals.buy）时 Pydantic 抛出 ValidationError
  - `programmatic` TR-2.4: 默认值正确（如 backtest_config.t_plus_one == True, lot_size == 100, broker_profile == "cn_stock_miniqmt"）
  - `programmatic` TR-2.5: ExpressionNode discriminated union 能正确识别 4 种 type
  - `programmatic` TR-2.6: ConditionTree 递归嵌套（AND → OR → CompareLeaf）可正确解析
- **Notes**: 注意 Pydantic v2 语法（model_config, Field(default=...)，discriminator 使用 Literal type）。TECHNICAL_FACTOR_KEYS 可先硬编码（对齐 Schema §4.5 的 20 个），后续 005 再接入 002 的 FactorRegistry。

## [ ] Task 3: engine 侧 StrategyValidator（validator.py）
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 新建 `validator.py`，实现 `StrategyValidator` 类，核心方法 `validate(config: StrategyConfigModel) -> list[StrategyValidationError]`：
    - 非短路遍历，所有错误一次性返回
    - **§7.1 结构约束**（_validate_structure）：
      1. trading_config 存在时 signals 和 rebalance 至少一个在场
      2. screen_config 存在且 universe == "manual" 时 stocks 非空
      3. ranking method == "composite" 时 weights 非空；method == "single" 时 factor 和 order 必填
      4. position_sizing method 在 POSITION_SIZING_METHODS 白名单；各 method 对应 target 必填（order_target_*/buy/sell 需要 target，buy_all/close_position 无需 target）
      5. exit.bracket.use_atr_stop == true 时 atr_multiplier 必填
    - **§7.2 条件模型约束**（_validate_conditions，递归遍历 ConditionTree）：
      - 接收 context_path 参数（"screen_config.conditions" / "trading_config.signals.buy.conditions" / "trading_config.signals.sell.conditions" / "trading_config.exit.rules[i].conditions"）
      - 在 screen 路径下：comparator 不得是 cross_up/cross_down；不得出现 RefNode
      - 在 trading 路径下：cross_up/cross_down 左右必须均为 FactorNode
      - ref.key 必须在 ALLOWED_REFS 白名单
      - comparator 在对应 COMPARATORS 集合内
    - **§7.3 因子节点约束**（_validate_factor_nodes，遍历所有 FactorNode）：
      - 技术面 factorKey 必须在 TECHNICAL_FACTOR_KEYS 内
      - 多输出因子（MACD/BOLL/KDJ）必须带 output_index，且 **output_index ∈ [0, len(MULTI_OUTPUT_FACTORS[factor])-1]**（不仅校验存在，还校验范围）
      - 基本面因子（FUNDAMENTAL_FACTOR_KEYS）出现在 trading_config 路径下时报错
    - **注入防护（范围收窄）**：
      - 枚举字段（method/comparator/op/ref.key）用白名单严格匹配，不在白名单即拒绝（天然防注入）
      - 自由文本字段（name/description/changelog/tags）做危险字符串黑名单：正则 `__class__|__init__|__reduce__|exec\(|eval\(|import os|subprocess|os\.system|pickle\.loads` 匹配时报错
    - 错误 path 使用点号路径，数组索引用 [n] 表示（如 `trading_config.signals.buy.conditions[0].left.factor`）
    - **三层递归遍历伪代码骨架**（开发时按此实现，避免漏校验某层）：
      ```python
      def _walk_condition_tree(node, path, ctx, errors):
          """第 1 层：遍历 ConditionTree（AND/OR 逻辑组 + CompareLeaf 叶子）"""
          if isinstance(node, CompareLeaf):
              self._validate_compare_leaf(node, path, ctx, errors)
          else:  # ConditionTree {operator, conditions}
              for i, child in enumerate(node.conditions):
                  self._walk_condition_tree(child, f"{path}[{i}]", ctx, errors)

      def _validate_compare_leaf(self, leaf, path, ctx, errors):
          """第 2 层：校验 comparator + 递归 left/right ExpressionNode"""
          # ctx 决定 screen vs trading 允许的 comparator/ref
          if ctx.is_screen and leaf.comparator in ("cross_up", "cross_down"):
              errors.append(SCREEN_TIME_SERIES_FORBIDDEN)
          if leaf.comparator in ("cross_up", "cross_down"):
              if not (isinstance(leaf.left, FactorNode) and isinstance(leaf.right, FactorNode)):
                  errors.append(CROSS_REQUIRES_FACTOR_NODES)
          self._walk_expression(leaf.left, f"{path}.left", ctx, errors)
          self._walk_expression(leaf.right, f"{path}.right", ctx, errors)

      def _walk_expression(self, node, path, ctx, errors):
          """第 3 层：遍历 ExpressionNode 收集 FactorNode/RefNode"""
          if isinstance(node, FactorNode):
              self._validate_factor_node(node, path, ctx, errors)
          elif isinstance(node, OpNode):
              self._walk_expression(node.left, f"{path}.left", ctx, errors)
              self._walk_expression(node.right, f"{path}.right", ctx, errors)
          elif isinstance(node, RefNode):
              if ctx.is_screen:
                  errors.append(SCREEN_REF_FORBIDDEN)
              elif node.ref not in ALLOWED_REFS:
                  errors.append(UNKNOWN_REF_KEY)
      ```
- **Acceptance Criteria Addressed**: FR-4, NFR-2, AC-4, AC-5, AC-6, AC-7, AC-8
- **Test Requirements**:
  - `programmatic` TR-3.1: 缺 signals 和 rebalance → MISSING_SIGNALS_OR_REBALANCE（path="trading_config"）
  - `programmatic` TR-3.2: universe=manual 无 stocks → MANUAL_SYMBOL_REQUIRED
  - `programmatic` TR-3.3: ranking.composite 无 weights → RANKING_WEIGHTS_REQUIRED
  - `programmatic` TR-3.4: position_sizing.method="invalid" → INVALID_POSITION_METHOD
  - `programmatic` TR-3.5: atr_stop.enabled=true 无 atr_multiplier → ATR_MULTIPLIER_REQUIRED
  - `programmatic` TR-3.6: screen_config.conditions 含 cross_up → SCREEN_TIME_SERIES_FORBIDDEN
  - `programmatic` TR-3.7: screen_config.conditions 含 RefNode → SCREEN_REF_FORBIDDEN
  - `programmatic` TR-3.8: cross_up 左为 ValueNode → CROSS_REQUIRES_FACTOR_NODES
  - `programmatic` TR-3.9: 引用 factorKey="UNKNOWN_X" → UNKNOWN_FACTOR
  - `programmatic` TR-3.10: MACD 无 output_index → MULTI_OUTPUT_REQUIRES_INDEX
  - `programmatic` TR-3.11: signals.buy 引用 PE_TTM → FUNDAMENTAL_FACTOR_IN_TRADING
  - `programmatic` TR-3.12: ref.key="highest_since_entry" → UNKNOWN_REF_KEY
  - `programmatic` TR-3.13: 含 "__class__" 的 method → INJECTION_FORBIDDEN
  - `programmatic` TR-3.14: Schema §5.1 §5.2 两个合法示例 → valid:true, errors:[]
  - `programmatic` TR-3.15: 同时存在 3 个错误 → errors 数组长度=3（非短路）
  - `programmatic` TR-3.16: 每个错误 path 格式正确（点号路径，数组含 [n]）
- **Notes**: 递归遍历可以用一个 helper 函数，对 ConditionTree 做深度优先遍历，遇到 CompareLeaf 校验 comparator 和左右子树，遇到逻辑节点递归 children。FactorNode 的收集可用另一个递归函数（_walk_expression）统一遍历所有 ExpressionNode。

## [ ] Task 4: engine 侧 validate HTTP API（api.py + 路由注册）
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - 新建 `api.py`，定义 FastAPI APIRouter：
    - POST `/python/v1/strategies/validate`，接收 ValidateRequest（config: dict）
    - **两段式校验**：
      1. 先 `StrategyConfigModel.model_validate(config)` 解析：Pydantic ValidationError → 收集 error 列表（loc 转 path 字符串，如 `("trading_config","signals")` → `"trading_config.signals"`）→ 直接返回 ValidateResponse(valid=False, errors=...)（**注意：此阶段可能短路**，第一个未知字段即抛异常，无法返回所有结构错误）
      2. 解析成功 → 调 `StrategyValidator.validate()`（**非短路**，一次返回所有 §7 错误）→ 返回 ValidateResponse
    - **Pydantic 解析异常时 HTTP 状态码**：返回 200 + valid:false（而非 422），让 watcher 统一处理 errors 数组，前端按 path 标红（与 §7 错误一致体验）
  - 在 FastAPI app 中注册该 router（参考 screener.py 的注册方式）
  - 确保 /docs 页面可见该接口，请求/响应模型有描述
- **Acceptance Criteria Addressed**: FR-4, NFR-3, AC-4~AC-12
- **Test Requirements**:
  - `programmatic` TR-4.1: POST 合法 config（Schema §5.1）→ 200 + {"valid":true,"errors":[]}
  - `programmatic` TR-4.2: POST 缺 signals/rebalance → 200 + {"valid":false, errors:[...MISSING_SIGNALS_OR_REBALANCE...]}
  - `programmatic` TR-4.3: POST 非法 JSON（非 dict/缺顶层层级）→ 422
  - `programmatic` TR-4.4: engine services/strategy/ 目录 grep "sqlite3\|sqlalchemy\|\.db" 无匹配
  - `programmatic` TR-4.5: FastAPI TestClient 测试通过（参考 screener 测试模式）
  - `programmatic` TR-4.6: /docs 页面返回 200，接口可被发现

## [ ] Task 5: 5 个内置策略模板 JSON
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 在 watcher `src/main/resources/strategies/templates/` 下创建 5 个 JSON 文件：
    1. `dual_ma.json`: 单标的双均线信号驱动。screen_config 留空（universe=manual+单symbols），trading_config.signals.buy=MA5 cross_up MA20，sell=MA5 cross_down MA20，position_sizing=order_target_percent(0.95)，exit.bracket 止损8%/止盈20%，trading_config.symbols=["510300.SH"]
    2. `low_pe_value.json`: 选股调仓。screen_config: all_a_shares, conditions=PE_TTM<20 AND ROE_TTM>15 AND TOTAL_MV<20000000000(200亿), ranking=single(ROE_TTM desc), filters=exclude_st+exclude_suspended+exclude_limit_up+min_listing_days=250, top_n=30；trading_config.rebalance: monthly+day_of_period=1+equal_weight+max_single=0.05；exit.bracket 止损10%/止盈30%；backtest_config initial_cash=1000000
    3. `macd_short.json`: 单标的 MACD 短线。signals.buy: MACD.hist>0 AND RSI<70；signals.sell: MACD.hist<0 OR RSI>80；position_sizing=order_target_percent(0.8)；exit.atr_stop enabled=true, atr_period=14, atr_multiplier=2
    4. `small_cap.json`: 选股调仓。screen_config: all_a_shares, TOTAL_MV<5000000000(50亿), ranking=single(TOTAL_MV asc), filters=exclude_st+exclude_suspended+exclude_limit_up, top_n=20；rebalance.monthly 等权
    5. `volume_price.json`: 单标的量价跟随。signals.buy: VOL_MA5>VOL_MA20*1.5 AND MA5>MA20 AND MA20>MA60；signals.sell: MA5 cross_down MA20；position_sizing=order_target_percent(0.9)
  - 每个模板顶层字段：strategy_id=null（占位，创建时生成）、name（中文模板名）、description（中文一句话描述）、category、scope、tags、screen_config、trading_config、backtest_config（合理默认值）
  - 编写简单冒烟脚本（pytest）验证 5 个模板 POST validate 接口均返回 valid:true
- **Acceptance Criteria Addressed**: FR-5, AC-9
- **Test Requirements**:
  - `programmatic` TR-5.1: 5 个文件存在且为合法 JSON
  - `programmatic` TR-5.2: 5 个模板经 engine validate 接口均返回 {valid:true, errors:[]}
  - `programmatic` TR-5.3: 每个模板含 name/description/category/scope 字段
  - `human-judgment` TR-5.4: 模板配置与 spec FR-5 描述的策略逻辑一致（人工复核每个模板的因子组合）

## [ ] Task 6: watcher 侧 StrategyService 与 CRUD 实现
- **Priority**: high
- **Depends On**: Task 1, Task 4
- **Description**:
  - DTO 类：
    - `StrategyDTO`（列表/详情响应，含 id/strategyId/name/description/category/scope/status/tags/currentVersion/createdAt/updatedAt + config?:String）
    - `StrategyCreateRequest`（name 必填, description/category/scope/tags 可选, **configJson 可选**, changelog 可选）— 一步创建支持携带 configJson
    - `StrategyUpdateRequest`（name/description/category/tags）
    - `StrategyConfigUpdateRequest`（configJson: String, changelog?, **expectedVersion: Integer** — 乐观锁字段）
    - `StrategyVersionDTO`（versionNo/configJson/changelog/createdAt）
    - `StrategyDiffDTO`（path/changeType/oldValue/newValue）
    - `StrategyTemplateDTO`（id/name/description/category/scope/configJson）
    - `StrategyStatusUpdateRequest`（status）
    - `StrategyRollbackRequest`（targetVersion: Integer, changelog?）
    - 分页：`StrategyPageRequest extends PageRequest`
  - `StrategyEngineClient`（继承 AbstractEngineClient 参考 ScreenerEngineClient）：
    - `validate(configJson: String) -> List<StrategyValidationError>`：POST 到 engine `/python/v1/strategies/validate`，解析响应，valid=false 时返回 errors 列表；连接异常抛 EngineUnavailableException；超时 5s connect / 30s read
    - **重试策略**：连接异常（ConnectException/SocketTimeout）重试 1 次（间隔 500ms）；read timeout 不重试
  - `JsonDiffUtil`：两个 Map/List/嵌套结构的结构化 diff，输出 List<DiffEntry>（path/changeType/oldValue/newValue）
    - **实现策略**：递归对比 Map（按 key 遍历，key 仅在 from=removed，仅在 to=added，都有则递归）；**List 按索引逐一比较**（不用 LCS）：同长度逐元素递归；长度差异部分标 added（to 多出）/removed（from 多出）
    - 输出 path 点号分隔，数组索引用 `[n]`
  - `StrategyServiceImpl`（@Service）：**所有方法以 strategyId（TEXT 业务 ID）为入参**，内部先查主表拿 INTEGER PK 再关联版本表
    - createStrategy(req)：
      - 不携带 configJson → 生成 UUID strategyId，INSERT 主表 + INSERT v1（configJson=buildDefaultConfig()，status=DRAFT）
      - 携带 configJson → 长度校验 → JSON parse → engine validate（事务外）→ 有错抛异常（主表不落库）→ 无错 INSERT 主表 + INSERT v1（合法 configJson，status=VERIFIED）
    - getStrategiesPage(req)：构建 LambdaQueryWrapper，支持 keyword LIKE、eq category/status/scope、FIND_IN_SET tag、按 updated_at DESC、分页；**默认 status != ARCHIVED**（仅当显式传 status=ARCHIVED 才返回）
    - getStrategyDetail(strategyId)：查主表 + 查 current_version 版本的 configJson
    - updateStrategy(strategyId, req)：UPDATE name/description/category/tags + updated_at
    - updateStrategyConfig(strategyId, req)：
      1. configJson 长度 ≤ 1MB 校验
      2. JSON parse 为 Map
      3. **乐观锁校验**：查主表 current_version，比对 expectedVersion != current_version → 抛 STRATEGY_VERSION_CONFLICT
      4. 调 engineClient.validate（**事务外**）
      5. 有错抛 BusinessException(STRATEGY_VALIDATION_FAILED, errors)
      6. 事务内：INSERT 新版本（version_no=current_version+1, configJson, changelog）+ UPDATE current_version/updated_at
      7. **状态机自动转换**：若 status==DRAFT 则置 VERIFIED；ACTIVE/VERIFIED 保持
    - deleteStrategy(strategyId)：UPDATE status=ARCHIVED + updated_at（软删）
    - updateStatus(strategyId, req)：校验状态机（DRAFT↔VERIFIED→ACTIVE→ARCHIVED，非法转换抛 STRATEGY_INVALID_STATUS_TRANSITION）
    - listVersions(strategyId)：按 version_no DESC 查版本表（不含 configJson 列表）
    - getVersion(strategyId, versionNo)：查指定版本
    - diffVersions(strategyId, fromVer, toVer)：分别查两个版本的 configJson（parse 成 Map）→ JsonDiffUtil.diff → 返回
    - rollbackVersion(strategyId, req)：取目标版本 configJson → 调用 updateStrategyConfig（传目标 configJson + changelog="回滚到 vX"）（**复用 update 逻辑，确保事务、校验、状态机转换都生效**）
    - 内部 helper：buildDefaultConfig() 返回一个**最小合法默认配置**（单标的 DRAFT 空白策略），示例如下：
      ```json
      {
        "strategy_id": null,
        "name": "",
        "description": "",
        "scope": "single",
        "trading_config": {
          "symbols": [],
          "signals": {
            "buy": {"operator": "AND", "conditions": []},
            "sell": {"operator": "AND", "conditions": []}
          },
          "position_sizing": {"method": "order_target_percent", "target": 0.95}
        },
        "backtest_config": {
          "initial_cash": 100000,
          "broker_profile": "cn_stock_miniqmt",
          "t_plus_one": true,
          "lot_size": 100,
          "warmup_period": 20,
          "history_depth": 60
        }
      }
      ```
      > 注：buildDefaultConfig() 产出的 config **不要求通过 engine validate**（signals.conditions 为空数组是合法的"占位"），仅作为 DRAFT 起点；用户在编辑器填完真实条件后保存才校验
  - 异常处理：在 GlobalExceptionHandler 新增：
    - STRATEGY_VALIDATION_FAILED 处理（返回 400，body 含 errors 数组）
    - STRATEGY_VERSION_CONFLICT 处理（返回 409，body 含当前 currentVersion）
- **Acceptance Criteria Addressed**: FR-2, FR-3, FR-6, NFR-3, AC-1, AC-2, AC-3, AC-10
- **Test Requirements**:
  - `programmatic` TR-6.1: createStrategy（不携带 configJson）后主表和 v1 版本表各一条记录，status=DRAFT，configJson 为默认配置
  - `programmatic` TR-6.1b: createStrategy（携带合法 configJson）后 status=VERIFIED，v1 configJson 为传入的合法 config
  - `programmatic` TR-6.1c: createStrategy（携带非法 configJson）→ 抛 STRATEGY_VALIDATION_FAILED，主表无记录（无脏数据）
  - `programmatic` TR-6.2: updateStrategyConfig 合法 config → 新版本 INSERT，current_version 更新
  - `programmatic` TR-6.3: updateStrategyConfig 非法 config（含 UNKNOWN_FACTOR）→ 抛 STRATEGY_VALIDATION_FAILED，无新版本记录，current_version 不变
  - `programmatic` TR-6.4: 连续 3 次 updateConfig → 版本号到 4，v1 记录未被修改
  - `programmatic` TR-6.5: deleteStrategy → status=ARCHIVED，版本记录保留
  - `programmatic` TR-6.6: 非法状态转换（ARCHIVED→DRAFT）→ STRATEGY_INVALID_STATUS_TRANSITION
  - `programmatic` TR-6.7: diffVersions 两个版本正确返回 added/removed/modified 条目，path 格式正确；数组按索引比较（顺序变化不算 modified）
  - `programmatic` TR-6.8: rollbackVersion(1) → 创建新版本，configJson 与 v1 内容相同，changelog 含"回滚"
  - `programmatic` TR-6.9: configJson > 1MB → STRATEGY_CONFIG_TOO_LARGE
  - `programmatic` TR-6.10: engine 不可用时 updateConfig → ENGINE_SERVICE_UNAVAILABLE，无新版本
  - `programmatic` TR-6.11: 并发更新——expectedVersion 与 current_version 不一致 → STRATEGY_VERSION_CONFLICT (409)
  - `programmatic` TR-6.12: DRAFT 状态 updateConfig 成功 → 自动转 VERIFIED；ACTIVE 状态 updateConfig 成功 → 保持 ACTIVE
  - `human-judgment` TR-6.13: 代码分层清晰，Service/Mapper 职责分明，与 ScreenerServiceImpl 风格一致
- **Notes**: buildDefaultConfig() 产出 DRAFT 起点配置（不强制通过 validate）。注意 @Transactional 在 updateConfig 中需要覆盖 INSERT+UPDATE，且 engine validate 在事务**外**调用（避免长事务持锁）。所有 Service 方法以 strategyId（TEXT 业务 ID）为入参，内部先查主表拿 INTEGER PK。

## [ ] Task 7: watcher 侧 Strategy REST Controller
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - 新建 `QuantStrategyController`（@RestController @RequestMapping("/api/strategies")）：
    - GET /api/strategies（分页查询，@RequestParam keyword/category/status/scope/tag）
    - POST /api/strategies（@RequestBody StrategyCreateRequest）
    - GET /api/strategies/{id}
    - PUT /api/strategies/{id}（@RequestBody StrategyUpdateRequest）
    - PUT /api/strategies/{id}/config（@RequestBody StrategyConfigUpdateRequest）
    - PUT /api/strategies/{id}/status（@RequestBody StrategyStatusUpdateRequest）
    - DELETE /api/strategies/{id}
    - GET /api/strategies/{id}/versions
    - GET /api/strategies/{id}/versions/{versionNo}
    - GET /api/strategies/{id}/versions/diff（@RequestParam from, to）
    - POST /api/strategies/{id}/versions/rollback（@RequestBody StrategyRollbackRequest）
    - GET /api/strategies/templates
    - GET /api/strategies/templates/{templateId}
  - 所有接口通过 AuthInterceptor 鉴权（已存在，确保路径不被 exclude）
  - 统一返回 ApiResponse<T>
  - 模板加载：@PostConstruct 或 @Component StrategyTemplateLoader 从 classpath resources/strategies/templates/*.json 读取并缓存（Caffeine 或简单 ConcurrentHashMap）
- **Acceptance Criteria Addressed**: FR-2, FR-3, FR-5, NFR-5
- **Test Requirements**:
  - `programmatic` TR-7.1: MockMvc 测试：POST → GET detail → PUT basic → PUT config（合法/非法）→ DELETE 全流程 2xx/4xx 正确
  - `programmatic` TR-7.2: 版本 CRUD 接口（list/get/diff/rollback）MockMvc 通过
  - `programmatic` TR-7.3: templates 列表和详情接口返回正确数据
  - `programmatic` TR-7.4: 未登录访问 /api/strategies/** 返回 302 重定向到 /login
  - `programmatic` TR-7.5: 非法 config 的 PUT /config 返回 400 且 body 含 errors 数组

## [ ] Task 8: watcher 侧 PageController + 侧边栏菜单
- **Priority**: high
- **Depends On**: Task 7
- **Description**:
  - 在已有 PageController（如 views/ 下）新增路由：
    - GET /quant/strategies → 列表页（templates/quant/strategies/list.html）
    - GET /quant/strategies/new → 新建编辑器页（templates/quant/strategies/editor.html）
    - GET /quant/strategies/{id}/edit → 编辑编辑器页
    - GET /quant/strategies/{id}/versions → 版本时间线页
  - 侧边栏配置文件（fragments/sidebar.html 或对应位置）在「量化」分组下新增「策略管理」菜单项，链接 `/quant/strategies`，图标建议用 fa-code-branch 或 fa-diagram-project，与「我的自选/行情看板/多因子选股」等菜单风格一致
  - 列表页和编辑器页需要登录（通过 AuthInterceptor 或页面级拦截，参考其他量化页面）
  - 详情页 model.addAttribute("strategy", ...) 供 Thymeleaf 渲染
  - 列表页 model.addAttribute("constants", ...) 注入分类/状态/scope 枚举选项（参考多因子选股页的 GET /constants 模式）
- **Acceptance Criteria Addressed**: FR-7, FR-8, FR-9, AC-17
- **Test Requirements**:
  - `programmatic` TR-8.1: 4 个页面路由 MockMvc 200（已登录）
  - `programmatic` TR-8.2: 未登录访问 302 重定向 /login
  - `human-judgment` TR-8.3: 侧边栏「策略管理」菜单项显示在「量化」分组下，激活态正确
  - `human-judgment` TR-8.4: 页面风格与现有页面一致（导航栏/侧边栏/顶部标题面包屑）

## [ ] Task 9: 策略列表页前端（list.html + list.js）
- **Priority**: high
- **Depends On**: Task 8
- **Description**:
  - 新建 `templates/quant/strategies/list.html`，模板继承 layout（参考 screener 页面布局）
  - 顶部筛选栏：keyword 输入框、category select（枚举选项）、status select、scope select、搜索按钮
  - 主区域：Bootstrap card 网格（row-cols-1 row-cols-md-2 row-cols-lg-3 g-4）
  - 卡片结构：
    - 卡片头：策略名（h5），右上角状态 badge（DRAFT=灰/VERIFIED=蓝/ACTIVE=绿/ARCHIVED=暗）
    - 卡片体：描述文字（text-muted）、分类/scope/tag 小 badge 行、更新时间（text-small）
    - 卡片脚（btn-group）：「编辑」（btn-outline-primary，跳 /{id}/edit）、「版本」（btn-outline-secondary，跳 /{id}/versions）、「回测」（btn-outline-success，暂用 # 或 005 占位链接）、「删除」（btn-outline-danger，点击 confirm 后调 DELETE API）
  - 右上角：「+ 新建策略」按钮（primary）、「从模板创建」下拉按钮（dropdown-menu 列出 5 模板，点击跳 /new?templateId=xxx）
  - 底部：pagination 控件（参考 screener 分页）
  - JS：`list.js` 调 GET /api/strategies?page=&size=&keyword=&category= 渲染卡片，分页切换，筛选搜索，删除 confirm-fetch-刷新
- **Acceptance Criteria Addressed**: FR-7, AC-13
- **Test Requirements**:
  - `human-judgment` TR-9.1: 页面布局在桌面/平板下正常（Bootstrap 响应式），卡片网格美观
  - `human-judgment` TR-9.2: 筛选栏 keyword/category/status 工作正常，搜索后卡片刷新
  - `human-judgment` TR-9.3: 分页切换正常
  - `human-judgment` TR-9.4: 新建按钮跳 /new；模板下拉显示 5 个模板，点击跳 /new?templateId=dual_ma 等
  - `human-judgment` TR-9.5: 删除按钮弹窗确认，确认后卡片消失（ARCHIVED 后从列表过滤？第一版可显示所有状态）
  - `programmatic` TR-9.6: JS 无控制台报错，fetch 调用正确处理错误（toast 提示）

## [ ] Task 10: 策略编辑器页前端（editor.html + editor.js）- 基础布局+Tab切换+JSON预览
- **Priority**: high
- **Depends On**: Task 8
- **Description**:
  - 新建 `templates/quant/strategies/editor.html`（双栏布局 col-lg-8 / col-lg-4）
  - 左侧 Tab 面板：Bootstrap nav-tabs（8 个 Tab：基本信息/选股范围/买入信号/卖出信号/仓位管理/止损止盈/调仓/回测参数），每个 Tab 一个 tab-pane
  - 右侧 sticky 面板：
    - 「策略摘要」卡片：根据 state 动态拼接文字（如"单标的 · MA5/MA20 金叉买入 · 全仓 · 括号单止损8%止盈20%"）
    - 「JSON 预览」：`<pre><code id="jsonPreview">`，实时显示 JSON.stringify(state, null, 2)，复制按钮（navigator.clipboard）
  - 底部固定 action bar：保存（btn-primary）、取消（btn-link 跳 /quant/strategies）
  - editor.html 内定义 JS 全局默认配置对象 `DEFAULT_CONFIG`（与 Task 6 buildDefaultConfig() 一致）
  - editor.js 核心逻辑：
    - 初始化：从 URL 判断模式（new / edit / new?templateId=xxx）→ 分别：空白默认 / GET /api/strategies/{id} 详情回填 / GET /api/strategies/templates/{templateId} 回填
    - 全局 `state` 对象（深拷贝 DEFAULT_CONFIG）
    - `renderFormFromState()` 把 state 写入表单元素
    - `collectStateFromForm()` 从表单元素读取写入 state
    - `updateJsonPreview()` 实时刷新 JSON 和摘要
    - 各 Tab 表单 onchange → collectStateFromForm → updateJsonPreview
    - 保存按钮：组装 state → PUT /api/strategies/{id}/config 或 POST 创建后 PUT config → 成功：toast+跳 /{id}/versions；失败：解析 errors → 按 path 定位对应 Tab 标红（Tab 标题加 text-danger），弹 toast 显示首个错误
- **Acceptance Criteria Addressed**: FR-8, AC-14, AC-15
- **Test Requirements**:
  - `human-judgment` TR-10.1: 8 个 Tab 可正常切换，无样式错乱
  - `human-judgment` TR-10.2: 右侧面板 sticky 固定，滚动不跟跑
  - `human-judgment` TR-10.3: 修改表单字段后 JSON 预览实时更新
  - `human-judgment` TR-10.4: 复制按钮可复制 JSON 到剪贴板
  - `human-judgment` TR-10.5: 取消按钮返回列表
  - `programmatic` TR-10.6: 空白模式 state 为合法默认值（JSON.parse 不报错，且通过后端 validate 结构）
  - `human-judgment` TR-10.7: 编辑模式加载已有策略后表单回填正确
  - `human-judgment` TR-10.8: templateId 模式预填模板配置
- **Notes**: Tab 表单字段较多，本任务先搭建 Tab 容器和 JSON 预览的骨架，具体字段控件由 Task 11 填充。

## [ ] Task 11: 编辑器 Tab 表单控件（8 个 Tab 完整内容）
- **Priority**: high
- **Depends On**: Task 10
- **Description**:
  - 填充 editor.html 每个 tab-pane 的表单字段：
    - **Tab 1 基本信息**：name input（required）、description textarea（rows=3）、category select（选项从页面 model 注入）、scope radio（single/portfolio/mixed，切换时联动 Tab7 调仓启用/禁用）、tags input（逗号分隔）
    - **Tab 2 选股范围（screen_config）**：
      - universe select（all_a_shares/csi300/csi500/manual/etf_pool）→ manual 时显示 stocks textarea（代码逗号/换行分隔）
      - 条件树区域（`<div id="screenConditionTree">`）：第一版用 `<textarea id="screenConditionsJson" rows=8 class=form-control font-monospace>` 占位（可直接粘贴 JSON，**对齐 Schema §4.1 结构 `{operator, conditions}`**），上方加「插入因子」辅助按钮下拉（列出 TECHNICAL_FACTOR_KEYS + 基本面因子前缀，点击插入 `{"factor":"XXX"}` 片段）
      - ranking method select（disabled/single/composite）→ single 时显示 factor select+order asc/desc；composite 时显示 weight 条目列表（factor + weight 数字 input，可添加/删除行，weight 负值=越小越好）
      - static filters（**字段名对齐 Schema §3.2.2**）：exclude_st/exclude_suspended/exclude_limit_up/exclude_limit_down 4 个 checkbox；industries/exclude_industries 勾选时展开 textarea；min_list_days 勾选时显示数字 input
      - top_n number input（min=1）
    - **Tab 3 买入信号（signals.buy）**：单标的模式（scope=single）显示 symbols textarea；条件树区域（同 Tab 2 但提示"此处允许 cross_up/cross_down 时序比较和 ref 引用"，**结构用 `{operator, conditions}`**）
    - **Tab 4 卖出信号（signals.sell）**：同 Tab 3 结构
    - **Tab 5 仓位管理（position_sizing）**：method select（**对齐 Schema §3.3.2 的 8 个 method**：order_target_percent/order_target_value/order_target/buy/sell/buy_all/close_position/order_target_weights），**统一用 target 字段**（根据 method 显示不同 label：percent 0-100%/value 元/shares 股；buy_all/close_position 隐藏 target）；order_target_weights 显示 params.weights 编辑器（map）；sell_method select（close_position/sell）
    - **Tab 6 止损止盈（exit）**（**对齐 Schema §3.3.3**）：
      - bracket 折叠区 → stop_loss_pct/take_profit_pct 数字输入（%）+ use_atr_stop checkbox → 勾选时显示 atr_period/atr_multiplier 数字输入
      - rules 动态出场规则列表：可添加行（name input + condition textarea + action select（close_position/sell）），可删除行
    - **Tab 7 调仓（rebalance，仅 scope≠single 启用）**（**对齐 Schema §3.3.4 字段名**）：frequency select（daily/weekly/monthly/quarterly）、day_of_period number（1-28）、replace_method radio（full/incremental）、weight_mode radio（equal/score）、max_single_position 滑块(0-100%)、long_only checkbox
    - **Tab 8 回测参数（backtest_config）**：initial_cash 数字、start_date/end_date date input、broker_profile select（cn_stock_miniqmt/cn_stock_t1_low_fee/cn_stock_sim_high_slippage）→ 「自定义费率」折叠区（commission_rate/stamp_tax_rate/transfer_fee_rate/min_commission 数字）；slippage 支持 number 或 `{type, value}` 两种输入（折叠区切换）；T+1 checkbox（默认 true）、lot_size number（默认 100）、warmup_period number、history_depth number、benchmark 输入
  - editor.js 更新 collect/render 逻辑覆盖所有字段
  - 策略摘要 render 函数：字符串模板拼接（scope 中文+买卖信号简述+仓位方式+止损止盈方式）
- **Acceptance Criteria Addressed**: FR-8, AC-14
- **Test Requirements**:
  - `human-judgment` TR-11.1: 所有字段 label 清晰、占位提示合理（与 Schema §3 文档对应）
  - `human-judgment` TR-11.2: 联动逻辑工作正常（universe=manual 显示 stocks；method 切换显示对应 target；scope=single 禁用 Tab7；bracket 勾选后才显示百分比输入）
  - `human-judgment` TR-11.3: 填写完整后 JSON 预览字段映射正确（snake_case 键名、数值类型正确）
  - `human-judgment` TR-11.4: 提交一个包含 cross_up、ref.entry_price、bracket、atr_stop 的 config，后端校验通过（v1 双均线模板基础上修改验证）
  - `human-judgment` TR-11.5: 提交一个 UNKNOWN_FACTOR 的 config，后端 400 后对应 Tab 标题标红（如 Tab 3 标红），toast 显示错误信息
- **Notes**: 条件树第一版用 textarea（手写 JSON）是可接受的简化（已在 Open Questions 明确），后续 003 前端编辑器组件就绪后替换为可视化编辑器。插入因子辅助按钮能大幅降低手写 JSON 的门槛。

## [ ] Task 12: 版本时间线页前端（versions.html + versions.js）
- **Priority**: medium
- **Depends On**: Task 8
- **Description**:
  - 新建 `templates/quant/strategies/versions.html`
  - 顶部：策略名 h3、「返回编辑」btn-outline-primary
  - 主体：垂直时间线（Bootstrap 自定义，左侧竖线 + 圆点）：
    - 每个版本一个条目：version_no 大号 badge（最新版本加「当前版本」success badge）、changelog 文字、created_at 小字体
    - 默认折叠；点击展开：
      - **v1 不显示 diff 区域**（显示"初始版本，无对比基准"）；v2+ 显示与上一版本的 diff：调 GET /versions/diff?from=上一版本&to=本版本，渲染为行级 diff（绿色新增/红色删除/橙色修改的 badge 行：`path: old → new`）
      - 「查看完整 JSON」折叠区（`<pre><code>` 显示本版本 configJson）
      - 「回滚到此版本」btn-warning 按钮（**非当前版本显示**）→ modal 输入 changelog → POST rollback → 刷新页面（回滚失败 toast 显示 errors）
  - versions.js：加载时先 GET 策略详情（获取 current_version）→ GET versions 列表 → 依次渲染时间线条目 → 点击展开时懒加载 diff（v1 跳过）和 config
- **Acceptance Criteria Addressed**: FR-9, AC-16
- **Test Requirements**:
  - `human-judgment` TR-12.1: 时间线视觉美观，竖线/圆点对齐，版本号清晰
  - `human-judgment` TR-12.2: 展开后 diff 三色标注清晰（增/删/改）
  - `human-judgment` TR-12.3: 当前版本不显示回滚按钮；历史版本显示
  - `human-judgment` TR-12.4: 回滚确认后新版本出现在时间线顶部，显示为「当前版本」
  - `programmatic` TR-12.5: JS 懒加载逻辑正常（不展开时不发 diff/config 请求）

## [ ] Task 13: 模板加载器 + 模板端点集成验证
- **Priority**: medium
- **Depends On**: Task 5, Task 7
- **Description**:
  - 完善 StrategyTemplateLoader：启动扫描 classpath:strategies/templates/*.json → parse 为 StrategyTemplateDTO（id=文件名不含.json、其余从 JSON 读取）→ 缓存到 Caffeine（或简单 ConcurrentHashMap）
  - GET /templates 返回缓存列表（不含 configJson？第一版包含，方便编辑器一次拉取）
  - GET /templates/{id} 返回完整 configJson
  - 写一个 @PostConstruct 校验：启动时对所有模板调 engineClient.validate（生产可关闭，dev 模式开）——**校验失败的模板不进入缓存**（打 ERROR 日志，GET /templates 不返回），避免用户从无效模板创建；校验通过才缓存
- **Acceptance Criteria Addressed**: FR-5, AC-9, AC-15
- **Test Requirements**:
  - `programmatic` TR-13.1: 启动后 GET /api/strategies/templates 返回 5 个模板
  - `programmatic` TR-13.2: GET /api/strategies/templates/dual_ma 返回合法 configJson
  - `programmatic` TR-13.3: 模板 JSON 缺失文件时启动不崩溃（打 WARN 日志）
  - `programmatic` TR-13.4: 模板 dev 模式启动校验失败有 ERROR 日志

## [ ] Task 14: 常量 GET /constants 接口扩展（含策略枚举）
- **Priority**: medium
- **Depends On**: Task 1, Task 7
- **Description**:
  - 在现有 ConstantsController（或等价端点）中，将 StrategyCategoryEnum/StrategyScopeEnum/StrategyStatusEnum 加入返回，以 `strategies.categories`/`strategies.scopes`/`strategies.statuses` 为 key
  - 确保前端列表页/编辑器页的下拉选项数据来自该接口（与多因子选股页一致）
  - 可选：在 constants 中同时返回 POSITION_SIZING_METHODS/SELL_METHODS/BROKER_PROFILES/ALLOWED_REFS/COMPARATORS/TRADING_COMPARATORS 等 engine 侧白名单，避免前端硬编码（engine 可新增 GET /python/v1/strategies/constants 返回，或 watcher 硬编码一份对齐常量——第一版建议 watcher 硬编码一份与 engine 对齐，减少 HTTP 依赖）
- **Acceptance Criteria Addressed**: NFR-4, NFR-6
- **Test Requirements**:
  - `programmatic` TR-14.1: GET /constants 返回 strategies.categories 数组含 4 项
  - `programmatic` TR-14.2: 前端 list.html/editor.html 下拉选项值与枚举 code 一致

## [ ] Task 15: 引擎校验器单元测试全覆盖
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 在 engine `tests/services/strategy/` 目录下新建：
    - `test_models.py`: Pydantic 模型解析测试（对应 TR-2.1~TR-2.6）
    - `test_validator.py`: 覆盖 TR-3.1~TR-3.16 所有校验用例（每个错误码至少一个正反用例；§5.1 §5.2 合法示例正向用例）
    - `test_api.py`: FastAPI TestClient 测试（对应 TR-4.1~TR-4.6）
  - 运行 pytest 全部通过
- **Acceptance Criteria Addressed**: NFR-2, NFR-3
- **Test Requirements**:
  - `programmatic` TR-15.1: pytest tests/services/strategy/ 全部 passing
  - `programmatic` TR-15.2: validator 测试覆盖 §7 所有 13 条约束
  - `programmatic` TR-15.3: coverage 报告 services/strategy/ 模块行覆盖率 ≥ 90%

## [ ] Task 16: watcher 集成测试 + 端到端冒烟
- **Priority**: high
- **Depends On**: Task 12, Task 13, Task 15
- **Description**:
  - 在 watcher test 目录下新建 StrategyControllerTest（@SpringBootTest + MockMvc 或 MockBean StrategyEngineClient）：
    - 测试完整 CRUD 流程（TR-7.1~TR-7.5）
    - 用 MockBean engine 返回预设 valid=true/false，覆盖成功/失败路径
    - 测试版本 diff/rollback
  - 集成冒烟：同时启动 watcher 和 engine，使用真实网络调用走通：
    1. 浏览器登录 watcher
    2. 策略列表页正常加载
    3. 从模板创建「双均线策略」，改名保存
    4. 在编辑器修改 MA5/MA20 为 MA10/MA30，保存
    5. 进入版本页，看到 v1（模板原始）、v2（改名）、v3（改MA），diff 正确
    6. 回滚到 v2 → 出现 v4
    7. GET /api/strategies/{id} 返回当前版本 configJson 符合 v2 内容
  - 记录冒烟结果
- **Acceptance Criteria Addressed**: AC-1~AC-18
- **Test Requirements**:
  - `programmatic` TR-16.1: watcher 单元/集成测试全部通过
  - `human-judgment` TR-16.2: 端到端冒烟走完流程（列表→新建→编辑→保存→版本→diff→回滚）无阻塞问题
  - `programmatic` TR-16.3: engine 侧 grep sqlite3/sqlalchemy/.db 在 services/strategy/ 无匹配
  - `human-judgment` TR-16.4: 页面无控制台 JS 错误，交互流畅无明显卡顿
