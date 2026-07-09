# 策略管理模块 - Product Requirement Document

## Overview
- **Summary**: 在 StockPulse 双系统架构下落地「策略管理模块」（004），覆盖策略配置的完整生命周期管理：**持久化存储、版本快照与回滚、Schema 校验、内置模板向导、可视化编辑器**。策略配置以「统一策略配置 Schema v1.0」为唯一真相源，校验通过的 JSON 配置存储在 watcher 侧数据库，供回测中心（005）和信号中心（未来）直接消费。
- **Purpose**: 打通「选股 → 策略 → 回测」决策链的中间管理环节。让用户通过可视化 Tab 编辑器（而非手写代码/Python）定义完整的策略配置——包括选股范围、买卖信号、仓位管理、止损止盈、调仓规则、回测参数——并通过版本管理追踪策略演进历史。策略保存前做完整的 Schema 校验，确保下游回测模块拿到的是合法配置。
- **Target Users**: 量化研究用户（个人 A 股投资者，通过前端页面操作）、回测中心 005（直接读取数据库中已校验的 config_json）、信号中心（未来读取激活策略的 trading_config.signals）。

## Goals
- 在 watcher（Java）侧实现策略 CRUD 持久化与**不可变版本快照**管理（主表 `quant_strategy` + 版本表 `quant_strategy_version`）
- **对外 API 路径 `/api/strategies/{id}` 的 `{id}` 一律指 `strategy_id`（TEXT 业务 ID），数据库 INTEGER 自增 PK 不外泄**（避免暴露自增序列；所有 Service/Controller 方法签名以 strategy_id 为入参）
- 在 watcher 侧实现**策略编辑器页面**（8 个 Tab：基本/选股范围/买入信号/卖出信号/仓位管理/止损止盈/调仓/回测参数；右侧实时 JSON 预览）
- 在 watcher 侧实现**策略列表页**（卡片网格、分类/状态/scope 筛选、关键字搜索、关联回测摘要占位）
- 在 watcher 侧实现**版本时间线页**（垂直时间线、JSON 结构化 diff、一键回滚）
- 在 watcher 侧预置 **5 个内置策略模板**（双均线/低PE价值/MACD短线/小市值/量价跟随），支持从模板一键创建
- 在 engine（Python）侧实现**策略配置校验 API**：完整覆盖「统一策略配置 Schema §7」的所有结构约束、条件模型约束、因子节点约束，返回结构化错误列表
- engine 侧提供 Pydantic 模型作为配置的类型化描述（供校验器和未来 005 编译器共用）
- 严格遵守 engine 不触库硬约束；严格对齐统一策略配置 Schema v1.0 的所有字段定义和校验规则

## Non-Goals (Out of Scope)
- **策略编译与回测执行**（归 005 回测中心）：将 JSON config 动态编译为 akquant Strategy 子类、`aq.run_backtest()` 调用、绩效报告生成——这些是回测中心的职责；本模块只产出合法的 config_json
- **TradingConditionEngine 时序条件运行时引擎**（归 005）：cross_up/cross_down 的运行时求值、ref 节点解析、on_bar 内的因子实时计算——这些是回测执行期的事
- 因子效能分析（IC/IR/分层回测，归 002 因子库扩展）
- 选股逻辑实现（归 003 多因子选股中心，本模块 screen_config 与其共用 ConditionTree JSON 结构和条件编辑器前端组件）
- 策略实盘跟踪、信号推送、模拟交易、风控执行（归域 C/D）
- 前端条件树可视化编辑器组件的底层实现（由通用前端组件库/003 选股中心提供，本模块消费其产出的 JSON）
- 参数寻优 / Walk-forward 配置界面（归 005）
- 自然语言生成（NLP）：右侧摘要为前端字符串模板拼接
- 用户级权限隔离（第一版所有登录用户可操作所有策略，后续加 user_id 字段）

## Background & Context

### 项目架构与硬约束
StockPulse 采用 Java + Python 双系统：
- **stock-watcher（Java）**: 业务 + 数据中台，独占 SQLite 读写，负责策略持久化、版本管理、前端页面、调用编排
- **stock-engine（Python）**: 计算服务，本模块内负责 Schema 校验；005 回测中心将在此模块基础上增加编译和执行能力

**硬约束（CLAUDE.md）**：
- engine 禁止 `sqlite3`/`sqlalchemy`/直连 `.db` 代码
- 数据单源性：watcher 经 HTTP 传数据给 engine，engine 只返回 JSON
- 交互单向：watcher → engine，engine 不回调 watcher

### 统一策略配置 Schema v1.0（本模块配置的唯一真相源）
权威文档：[统一策略配置Schema.md](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md)（§1-§9，已定稿）。三段分区：
- `screen_config`：选股层（universe/conditions/ranking/filters/top_n），JSON 结构与 003 选股中心完全一致
- `trading_config`：交易层（signals 买卖信号 / position_sizing 仓位 / exit 出场 / rebalance 调仓 / symbols 标的）
- `backtest_config`：回测层（initial_cash/date range/broker_profile/t_plus_one/佣金滑点等，直接映射 `aq.run_backtest` 参数）

两范式通过字段在场推断（Schema §2.2）：`signals` 在场=信号驱动；`rebalance` 在场=选股调仓驱动；可混合。

### 与相邻模块的边界
| 模块 | 提供给本模块 | 本模块提供给它 |
|---|---|---|
| 002 因子库 | 技术因子注册表（20 个技术面因子清单），校验 factorKey 合法性 | 无（只读依赖） |
| 003 选股中心 | ConditionTree JSON 结构定义、前端条件树编辑器组件（复用） | 无（screen_config 与其完全同构） |
| 005 回测中心 | 无（005 在本模块之后开发） | 已校验通过的 strategy_config JSON + 版本管理能力 |
| 信号中心（未来） | 无 | 激活策略的 trading_config.signals |

### 校验规则（Schema §7）
本模块校验器需完整覆盖：
- **§7.1 结构约束**（5 条）：signals/rebalance 至少一个在场；universe=manual 时 stocks 必填；ranking 对应字段必填；position_sizing method 对应 target 必填；use_atr_stop 时 atr_multiplier 必填
- **§7.2 条件模型约束**（5 条）：screen_config 内禁止 cross_up/cross_down/ref；cross 要求左右均为 factor 节点；exit.rules 内允许 ref
- **§7.3 因子节点约束**（3 条）：技术面 factorKey 须在注册表内；多输出因子须带 output_index；基本面 factorKey 仅在 screen_config 合法

### 相关设计文档
- [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-策略管理/统一策略配置Schema.md) - 字段唯一权威
- [选股与回测边界设计](file:///d:/lcProject/stock-pulse/sdlc/prd/003-多因子选股中心/选股与回测边界设计.md) - 职责边界、三层分区
- [003 多因子选股中心 spec](file:///d:/lcProject/stock-pulse/.trae/specs/003-multi-factor-screener/spec.md) - ConditionEngine 与 screen_config 结构
- [akquant Strategy API](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/03-strategy-api.md) - trading_config 字段语义参考
- [akquant run_backtest](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/04-backtest-run.md) - backtest_config 字段映射

## Functional Requirements

### FR-1: 策略持久化数据模型（watcher/Java 侧）
- 新建数据库表 `quant_strategy`（主表）：
  - `id` INTEGER PK AUTOINCREMENT
  - `strategy_id` TEXT NOT NULL UNIQUE（业务 ID，UUID 或时间戳）
  - `name` VARCHAR(128) NOT NULL
  - `description` VARCHAR(512)
  - `category` VARCHAR(32)（技术面/基本面/混合/自定义）
  - `scope` VARCHAR(16)（single/portfolio/mixed）
  - `status` VARCHAR(16) DEFAULT 'DRAFT'（DRAFT/VERIFIED/ACTIVE/ARCHIVED）
  - `tags` VARCHAR(512)（逗号分隔）
  - `current_version` INTEGER DEFAULT 1
  - `created_at` VARCHAR(32)
  - `updated_at` VARCHAR(32)
  - > 时间字段统一格式：**UTC ISO8601 字符串**（如 `2026-07-07T08:30:00Z`），便于跨时区排序与展示
  - > `tags` 不允许含逗号（前端校验 + 入库前 trim），存入前去除首尾空格
- 新建数据库表 `quant_strategy_version`（版本快照表，**不可变**）：
  - `id` INTEGER PK AUTOINCREMENT
  - `strategy_id` INTEGER NOT NULL（FK → quant_strategy.id，**注意是 INTEGER PK，不是 TEXT 业务 ID**）
  - `version_no` INTEGER NOT NULL
  - `config_json` TEXT NOT NULL（完整 strategy_config JSON）
  - `changelog` VARCHAR(512)
  - `created_at` VARCHAR(32)
  - UNIQUE(strategy_id, version_no)
- 同步更新 `schema-sqlite.sql` 和 `schema-mysql.sql`
- 创建枚举（实现 DisplayableEnum）：`StrategyCategoryEnum`、`StrategyScopeEnum`、`StrategyStatusEnum`
- 创建 DO 类（@TableName）：`QuantStrategyDO`、`QuantStrategyVersionDO`
- 创建 Mapper 接口（继承 BaseMapper）：`QuantStrategyMapper`、`QuantStrategyVersionMapper`

### FR-2: 策略 CRUD API（watcher/Java 侧 REST）
- `GET /api/strategies` - 分页列表，支持 keyword（LIKE name/description）、category、status、scope、tag 筛选；**默认不显示 ARCHIVED**，仅当显式筛选 `status=ARCHIVED` 时返回
- `POST /api/strategies` - 新建策略。**一步创建**：body 可携带可选 `configJson`：
  - 携带 configJson → 先调 engine validate → 通过则事务内创建主表 + 写 v1（合法 config）+ status 自动置 `VERIFIED`；不通过返回 400+结构化错误，**主表不落库**（无脏数据）
  - 不携带 configJson → 创建主表 + 写 v1（`buildDefaultConfig()` 默认空白配置）+ status=`DRAFT`
  - body 必填：name；可选：description/category/scope/tags/configJson/changelog
- `GET /api/strategies/{id}` - 策略详情（含当前版本 config_json）；`{id}` = strategy_id（TEXT 业务 ID）
- `PUT /api/strategies/{id}` - 更新基本信息（name/description/category/tags）
- `DELETE /api/strategies/{id}` - 软删除（status→ARCHIVED），版本快照保留
- `PUT /api/strategies/{id}/config` - 更新配置（保存新版本快照）：先调 engine validate → 通过则事务内 INSERT 新版本 + UPDATE current_version；不通过返回 400+结构化错误。**支持乐观锁**：请求体携带 `expected_version`（当前 current_version），watcher 比对不一致时返回 409 `STRATEGY_VERSION_CONFLICT`（并发更新检测）。**校验成功且 status==DRAFT 时自动转 VERIFIED**；status==ACTIVE/VERIFIED 时改 config 保持原 status
- `PUT /api/strategies/{id}/status` - 更新状态，状态机校验：DRAFT↔VERIFIED→ACTIVE→ARCHIVED（ARCHIVED 为终态）
- 统一返回 `ApiResponse<T>`；错误码：`STRATEGY_NOT_FOUND`、`STRATEGY_VALIDATION_FAILED`、`ENGINE_SERVICE_UNAVAILABLE`、`STRATEGY_INVALID_STATUS_TRANSITION`、`STRATEGY_VERSION_NOT_FOUND`、`STRATEGY_CONFIG_TOO_LARGE`、`STRATEGY_VERSION_CONFLICT`
- config_json 长度限制 ≤ 1MB（Controller 入口 `@Size(max=1048576)` 校验，避免依赖容器默认限制）

### FR-3: 版本管理 API（watcher/Java 侧）
- `GET /api/strategies/{id}/versions` - 版本列表（version_no DESC）
- `GET /api/strategies/{id}/versions/{versionNo}` - 指定版本完整 config_json
- `GET /api/strategies/{id}/versions/diff?from=v1&to=v3` - 两版本结构化 diff（path + change_type: added/removed/modified + oldValue + newValue）
- `POST /api/strategies/{id}/versions/rollback` - 回滚：基于目标版本 config_json 创建**新版本**（version_no 递增，不覆盖旧版本），changelog 标注"回滚到 vX"。**回滚路径仍走 engine validate**（Schema 升级导致旧 config 不再合法时返回 400+errors，不写新版本）；校验在事务外调用，校验通过后事务内写新版本
- 版本快照**写入后不可修改**（无 UPDATE 接口），保证审计完整性
- **JSON diff 工具实现策略**（JsonDiffUtil）：
  - 递归对比 Map/List 嵌套结构，输出字段路径（点号分隔，数组索引用 `[n]`）
  - **数组按索引逐一比较**（不用 LCS 内容比对）：同长度逐元素递归；长度差异部分标 `added`（to 多出）/`removed`（from 多出）
  - 简单可控，避免顺序变化导致的整片 modified
- **v1 版本 diff 基准**：v1 是初始版本无前序，列表页/版本页展开 v1 时**不显示 diff 区域**（或显示"初始版本，无对比基准"），前端懒加载 diff 时跳过 v1 不调 diff 接口

### FR-4: 策略配置校验（engine/Python 侧）
- 新建 engine 模块目录 `services/strategy/`：`__init__.py`、`constants.py`、`errors.py`、`models.py`（Pydantic）、`validator.py`、`api.py`（路由）
- **Pydantic 模型**（`models.py`）：严格对齐 Schema §3 定义所有子模型：
  - ExpressionNode 4 种形态（discriminated union，**严格对齐 Schema §4.3 字段名**）：ValueNode(`{value}`) / FactorNode(`{factor, params?, inputs?, output_index?}`) / OpNode(`{op, left, right}`) / RefNode(`{ref}`)
  - CompareLeaf（`{type:"compare", left, comparator, right}`，**对齐 Schema §4.2**）
  - ConditionTree（递归，**对齐 Schema §4.1**：`{operator:"AND"|"OR", conditions:[ConditionTree|CompareLeaf]}`，不是 type/children 结构）
  - RankingModel(method, weights?, factor?, order?)
  - StaticFiltersModel（**字段名对齐 Schema §3.2.2**：exclude_st, exclude_suspended, exclude_limit_up, exclude_limit_down, industries, exclude_industries, min_list_days）
  - ScreenConfigModel(universe, stocks?, top_n?, conditions?, ranking?, filters?)
  - SignalsModel(buy?, sell?, eval_scope?)
  - PositionSizingModel（**对齐 Schema §3.3.2**：method, target?, params?, sell_method?）—— 注意 `target` 是统一字段（不是 target_percent/target_value 分散字段），按 method 解释语义
  - BracketModel(stop_loss_pct?, take_profit_pct?, use_atr_stop?, atr_period?, atr_multiplier?)
  - ExitRuleModel(name?, condition: ConditionTree, action?)
  - ExitModel(bracket?, rules?)
  - RebalanceModel（**对齐 Schema §3.3.4**：frequency, day_of_period?, replace_method?, weight_mode?, max_single_position?, long_only?）
  - TradingConfigModel(symbols?, signals?, position_sizing?, exit?, rebalance?)
  - SlippageModel（**对齐 Schema §3.4**：支持 number 或 `{type:"percent"|"fixed", value}` 两种形态，用 discriminated union 或 root_validator）
  - BacktestConfigModel（**字段对齐 Schema §3.4**：initial_cash, start_date?, end_date?, broker_profile?, t_plus_one?, commission_rate?, stamp_tax_rate?, transfer_fee_rate?, min_commission?, slippage?, volume_limit_pct?, lot_size?, warmup_period?, history_depth?, fill_policy?, timezone?, show_progress?, risk_config?, strict_strategy_params?）
  - 顶层 StrategyConfigModel(strategy_id?, name, description?, scope?, screen_config?, trading_config?, backtest_config?)
  - HTTP 请求/响应：ValidateRequest、ValidateResponse、StrategyValidationError（path+code+message）
- **白名单常量**（`constants.py`）：
  - `POSITION_SIZING_METHODS`（order_target_percent/order_target_value/order_target/buy/sell/buy_all/close_position/order_target_weights）
  - `SELL_METHODS`（close_position/sell/signal_based）
  - `SCREEN_COMPARATORS`（`>` `<` `>=` `<=` `==` `!=`，**不含 cross_***）
  - `TRADING_COMPARATORS`（SCREEN_COMPARATORS + `cross_up` + `cross_down`）
  - `ALLOWED_REFS`（entry_price/position_pnl_pct/position_qty/bars_held；highest_since_entry/lowest_since_entry 为预留扩展，第一版不在白名单内）
  - `BROKER_PROFILES`（cn_stock_miniqmt/cn_stock_t1_low_fee/cn_stock_sim_high_slippage）
  - `TECHNICAL_FACTOR_KEYS`（20 个，对齐 Schema §4.5：MA/EMA/BOLL/SAR/MACD/RSI/KDJ/ADX/PLUS_DI/MINUS_DI/WILLR/CCI/ATR/OBV/CLOSE/HIGH/LOW/VOLUME/VOL_MA/VOL_EMA）
  - `FUNDAMENTAL_FACTOR_KEYS`（基本面因子清单：PE_TTM/PB/TOTAL_MV/ROE_TTM/REVENUE_GROWTH/NET_PROFIT_GROWTH/GROSS_MARGIN/CURRENT_RATIO/TURNOVER_RATE/NORTHBOUND_NET_INFLOW 等，**对齐 Schema §4.5 基本面表**）
  - `MULTI_OUTPUT_FACTORS`（多输出因子 output_index 映射表，**对齐 Schema §4.5**）：`{"MACD": ["dif","dea","hist"]}` (0/1/2)、`{"BOLL": ["upper","mid","lower"]}` (0/1/2)、`{"KDJ": ["k","d","j"]}` (0/1/2)；校验 output_index 必须在 0~len(outputs)-1 范围内
- **错误码**（`errors.py`）：覆盖 Schema §7 所有约束，与 003 风格一致
- **StrategyValidator**（`validator.py`）：
  - `validate(config: StrategyConfigModel) -> list[StrategyValidationError]`（非短路，一次返回所有错误）
  - §7.1 结构约束校验（5 条）
  - §7.2 条件模型约束校验（递归遍历 ConditionTree，根据所在路径 screen vs trading 决定允许的 comparator/ref）
  - §7.3 因子节点校验（遍历所有 FactorNode，检查 factorKey 合法性、output_index 要求、基本面因子位置限制）
  - **注入防护（范围收窄）**：对**枚举字段**（method/comparator/op/ref.key）用白名单严格匹配（不在白名单即拒绝，天然防注入）；对**自由文本字段**（name/description/changelog/tags）做危险字符串黑名单（`__class__`/`__init__`/`__reduce__`/`exec(` /`eval(` /`import os`/`subprocess`/`os.system`/`pickle.loads`/反引号）拒绝。**不再对枚举字段做黑名单**（已被白名单覆盖）
- **HTTP API**（`api.py`，挂载在 `/python/v1/strategies/validate`）：
    - POST 接收 ValidateRequest（config: dict）
    - **两段式校验**：① Pydantic 解析（字段/类型校验，可能短路，第一个未知字段即抛异常）→ 失败收集 loc 转 path 返回 422 + errors；② 解析成功后调 StrategyValidator（§7 结构/语义校验，**非短路**一次返回所有错误）→ 返回 200 + ValidateResponse
    - 合法：`{valid: true, errors: []}`（200）
    - 不合法：`{valid: false, errors: [{path, code, message}]}`（200，因为校验本身成功执行）
    - **错误 path 风格统一**：Pydantic 阶段用 `loc→path`（如 `trading_config.signals.buy`），§7 阶段用点号路径+`[n]` 数组索引（如 `trading_config.signals.buy.conditions[0].left.factor`）

### FR-5: 5 个内置策略模板
- 模板 JSON 文件存放在 watcher `src/main/resources/strategies/templates/`：
  1. `dual_ma.json` - 双均线（信号驱动单标的：MA5 cross_up MA20 买，cross_down 卖，order_target_percent 0.95，bracket 止损8%/止盈20%）
  2. `low_pe_value.json` - 低PE价值（选股调仓：全A股 PE_TTM<20 AND ROE_TTM>15 AND TOTAL_MV<200亿，top_n=30，monthly 等权，bracket 止损10%/止盈30%）
  3. `macd_short.json` - MACD短线（信号驱动：MACD hist>0 AND RSI<70 买，MACD hist<0 OR RSI>80 卖，ATR 止损 mult=2）
  4. `small_cap.json` - 小市值（选股调仓：TOTAL_MV<50亿，排除ST/停牌/涨停，top_n=20，monthly 等权）
  5. `volume_price.json` - 量价跟随（信号驱动：VOL_MA5 > VOL_MA20*1.5 AND MA5>MA20 AND MA20>MA60 买，MA5 cross_down MA20 卖，order_target_percent 0.9）
- 每个模板包含完整 strategy_config（含 strategy_id/name/description 占位字段，从模板创建时由 watcher 填充真实 ID/name）
- 所有模板必须通过 engine validate（valid:true）
- watcher 侧 `StrategyTemplateLoader`（@Component）：启动时加载所有模板到内存缓存
- API：`GET /api/strategies/templates`（列表）、`GET /api/strategies/templates/{id}`（获取 config_json）

### FR-6: watcher → engine 校验调用编排
- 新建 `StrategyEngineClient`（继承 AbstractEngineClient）：
  - `validate(config: dict) -> list[StrategyValidationError]`：POST 到 engine `/python/v1/strategies/validate`
  - engine 不可用时抛 BusinessException(ENGINE_SERVICE_UNAVAILABLE)
  - 超时：connect 5s / read 30s
  - **重试策略**：连接异常（ConnectException/SocketTimeout）重试 1 次（间隔 500ms）；read timeout 不重试（避免校验请求堆积）；连续失败不引入熔断器（校验是低频写操作，Caffeine 缓存不适用）
- watcher StrategyService 在 `createStrategy`（携带 configJson 时）和 `updateStrategyConfig` 方法内的统一编排：
  1. configJson 长度校验 ≤ 1MB
  2. JSON 解析校验（非空、合法 JSON）
  3. **乐观锁校验**（仅 updateStrategyConfig）：比对 `expected_version == current_version`，不一致抛 STRATEGY_VERSION_CONFLICT
  4. 调 engine validate（**在事务外调用**，避免长事务持锁）
  5. valid=false 时抛 BusinessException(STRATEGY_VALIDATION_FAILED, errors)
  6. valid=true 时事务内写新版本快照（INSERT version + UPDATE current_version/updated_at/status）
  7. **状态机自动转换**：status==DRAFT 且校验通过 → 自动置 VERIFIED；status==ACTIVE/VERIFIED 保持不变
- GlobalExceptionHandler 捕获 STRATEGY_VALIDATION_FAILED，返回 400 + errors 数组给前端
- GlobalExceptionHandler 捕获 STRATEGY_VERSION_CONFLICT，返回 409 + 当前 current_version

### FR-7: 策略列表页（watcher/Thymeleaf 前端）
- 页面路径：`/quant/strategies`（PageController 注册路由）
- 侧边栏「量化」分组新增「策略管理」菜单项
- 筛选栏：分类下拉（GET /constants 加载 StrategyCategoryEnum）、状态下拉、scope 下拉、关键字搜索框
- 卡片网格（Bootstrap row-cols-1 row-cols-md-2 row-cols-lg-3 g-4）：
  - 卡片内容：策略名、描述、分类/状态/scope 彩色 badge、更新时间
  - 底部操作：编辑、回测（跳 005 占位）、版本、删除（确认弹窗）
- 右上角「+ 新建策略」按钮 + 「从模板创建」下拉菜单（列出 5 模板，点击带 templateId 跳编辑器）
- 底部分页控件

### FR-8: 策略编辑器页（watcher/Thymeleaf 前端）
- 页面路径：`/quant/strategies/new`（新建）和 `/quant/strategies/{id}/edit`（编辑）
- 布局：左侧 Tab 面板（col-lg-8），右侧 sticky 预览面板（col-lg-4）
- **左侧 8 个 Tab**（Bootstrap nav-tabs）：
  1. **基本信息**：策略名、描述 textarea、分类 select、scope 单选、标签 input
  2. **选股范围**（screen_config）：股票池 select（all_a_shares/csi300/csi500/manual）→ manual 时显示股票代码 textarea；选股条件树区域（嵌入条件树编辑器组件）；排序规则（method 下拉，composite 时显示权重录入，single 时显示单因子+asc/desc）；静态过滤开关组（ST/停牌/涨跌停/行业白名单/黑名单/上市天数）；top_n 数字输入
  3. **买入信号**（signals.buy）：条件树编辑器（启用时序模式：支持 cross_up/cross_down、ref 下拉、技术因子）；单标的模式显示标的代码 input
  4. **卖出信号**（signals.sell）：同上
  5. **仓位管理**（position_sizing）：method 下拉（选项从 POSITION_SIZING_METHODS 常量加载），不同 method 显示对应的 target 标签（%/股/元）；sell_method 下拉
  6. **止损止盈**（exit）：括号单开关 → 止损%、止盈% 输入；ATR 止损开关 → atr_period/atr_multiplier 输入；动态出场规则列表（可添加多条，每条含 name、条件树、action 下拉）
  7. **调仓**（rebalance，portfolio/mixed scope 时启用）：frequency 下拉（日/周/月/季）、day_of_period、换仓方式 radio（全换/增量）、权重模式 radio（等权/按分）、max_single_position 滑块
  8. **回测参数**（backtest_config）：初始资金、开始/结束日期、broker_profile 下拉、自定义费率折叠区（佣金/印花/过户/最低佣金/滑点类型+值）、warmup_period、history_depth、T+1 开关
- **右侧预览面板**：
  - 上方「策略摘要」卡片：JS 前端根据当前 state 拼接自然语言描述（字符串模板，非 NLP）
  - 下方「JSON 预览」：语法高亮 `<pre><code>`，JSON.stringify(config, null, 2) 实时更新，复制按钮
- **底部按钮**：保存（组装完整 config_json → **new 模式 POST /api/strategies 携带 configJson 一步创建**；**edit 模式 PUT /api/strategies/{id}/config 携带 expected_version** → 成功跳转版本页，失败在对应 Tab 标红错误）、取消（返回列表，new 模式无副作用因未落库）
- **前端保存前预校验**：保存按钮点击时先在浏览器内 JSON.parse 所有条件树 textarea，解析失败直接标红对应 Tab 并 toast 提示，不走 HTTP 请求
- **错误 path → Tab 映射表**（后端返回 errors 后，前端按 path 前缀定位 Tab 标红）：
  - `screen_config.*` → Tab 2 选股范围
  - `trading_config.signals.buy.*` → Tab 3 买入信号
  - `trading_config.signals.sell.*` → Tab 4 卖出信号
  - `trading_config.position_sizing.*` → Tab 5 仓位管理
  - `trading_config.exit.*` → Tab 6 止损止盈
  - `trading_config.rebalance.*` → Tab 7 调仓
  - `backtest_config.*` → Tab 8 回测参数
  - `name`/`description`/`category`/`tags` → Tab 1 基本信息
- **JS 数据模型**：全局 state 对象，Tab 表单 onchange 双向绑定；加载逻辑：id 模式 GET 详情回填；templateId 模式 GET 模板回填（**仅回填编辑器 state，不在服务端落库**，用户点保存才创建）；空白模式用默认 config
- **策略摘要模板**（前端字符串拼接，非 NLP），按 scope 分支：
  - `single`：`{scope中文} · {买入信号简述} · {仓位method+target} · {止损止盈简述}`
  - `portfolio`：`组合策略 · {universe} · top_n={N} · {ranking method} · {frequency}调仓 · {weight_mode}`
  - `mixed`：`混合策略 · 信号+{frequency}调仓`
  - 空 signals 时显示"无信号"，空 exit 时显示"无止损"
- **条件树编辑器**：第一版优先复用 003 选股中心已实现的条件编辑器组件；若 003 前端编辑器未就绪，先用「JSON 文本区 + 插入因子/操作符辅助按钮」简化版过渡（后端能力不受前端简化影响）

### FR-9: 版本时间线页（watcher/Thymeleaf 前端）
- 页面路径：`/quant/strategies/{id}/versions`
- 顶部：策略名 + 返回编辑按钮
- 垂直时间线：最新版本在顶部，标「当前版本」徽章；每条显示 version_no、changelog、created_at
- 点击版本卡片展开：
  - **v1 不显示 diff 区域**（显示"初始版本，无对比基准"）；v2+ 显示与上一版本的 diff（增/删/改三色标注）
  - 「查看完整 JSON」折叠区
  - 「回滚到此版本」按钮（**当前版本不显示**）→ modal 输入 changelog → POST rollback API（回滚路径仍走 engine 校验，失败 toast 显示 errors）
- diff 渲染：调用 watcher diff API 拿结构化结果，前端渲染（绿色新增、红色删除、橙色修改）

## Non-Functional Requirements

### NFR-1: 性能
- 策略配置校验（含 10 个条件节点、5 个因子引用）响应时间 < 200ms（含 HTTP 往返）
- 策略列表页（100 条）加载 < 500ms
- 版本 diff 计算（10KB JSON 级别）< 50ms

### NFR-2: 正确性
- 校验器覆盖 Schema §7 **全部 13 条约束**（5 结构 + 5 条件模型 + 3 因子节点），零漏报零误报
- 所有通过校验的模板 config 均能被 Pydantic 成功解析
- 版本快照不可变：写入后 UPDATE 接口不存在（或 Service 层禁止）
- 回滚操作创建新版本（不覆盖），版本号单调递增
- 错误路径字段（如 `trading_config.signals.buy.conditions[0].left.factor`）前端可直接用于定位到对应 Tab/字段

### NFR-3: 可靠性
- 非法 config 返回结构化错误（path+code+message），不返回 500
- engine 校验接口无状态，可水平扩展
- watcher 版本写入使用 @Transactional，中途失败不留下半截数据
- engine 不可用时 watcher 返回明确 503 提示「引擎服务暂不可用，请稍后重试」，不写入脏数据
- 校验失败（valid:false）返回 400 给前端，HTTP 状态码语义明确
- **并发更新检测**：PUT /config 通过 expected_version 乐观锁防止并发覆盖，冲突返回 409
- **模板启动校验失败的模板不进入缓存**（避免用户从无效模板创建），GET /templates 不返回校验失败的模板
- **回滚失败保护**：回滚路径 engine 校验失败时不写新版本，返回 400 + errors

### NFR-4: 可维护性
- Validator 按 §7.1/§7.2/§7.3 拆分为私有方法，与 Schema 章节对应，便于 Schema 更新时同步
- 白名单常量集中管理，不散落魔法字符串
- Pydantic 模型文件与 Schema 章节结构对应
- watcher 代码分层：Controller → Service → Mapper，与 ScreenerServiceImpl 风格一致
- 前端 JS state 集中管理，不把状态散落在 DOM 中

### NFR-5: 安全性
- 所有 /api/strategies/** 和页面路由需认证登录（AuthInterceptor）
- config_json 长度上限 1MB（application 层校验）
- engine 校验器拒绝含危险字符串（__class__/exec/eval 等）的 config，防注入
- 删除为软删除（ARCHIVED），不可物理删除版本历史

### NFR-6: 兼容性
- config_json 字段命名严格对齐统一 Schema v1.0（snake_case）
- Pydantic 模型默认值与 Schema §3 默认值一致
- 数据库 schema 变更使用 CREATE TABLE IF NOT EXISTS / 增量迁移（兼容已有库）
- 前端条件编辑器产出的 JSON 格式与 Schema §4 完全一致

## Constraints

### 技术约束
- **engine**: Python 3.12 + FastAPI + Pydantic 2.x；akquant 0.2.47（仅用于因子注册表读取，不做运行时回测）
- **watcher**: Java 21 + Spring Boot 4.0.6 + MyBatis-Plus + Caffeine + SQLite(WAL)
- **前端**: Thymeleaf + Bootstrap 5 + 原生 JavaScript（与现有页面一致）
- **硬约束**: engine 禁止 sqlite3/sqlalchemy/直连 .db；交互单向 watcher→engine
- **akquant 版本锁定**: 0.2.47

### 业务约束
- 配置字段 100% 对齐统一策略配置 Schema v1.0，不自造字段
- 技术面 factorKey 白名单（20 个）严格对齐 Schema §4.5
- screen_config 内禁止 cross_up/cross_down/ref（截面语义）
- 版本快照一旦写入不可修改
- scope 仅为 UI 提示，实际范式由 trading_config 字段在场决定（Schema §2.2）

### 依赖约束
- 002 因子库已提供技术因子注册表（TECHNICAL_FACTOR_KEYS 列表可从其 FactorRegistry 获取或硬编码对齐）
- 003 选股中心已定义 ConditionTree JSON 结构和基础 ConditionEngine 模型；前端条件编辑器组件依赖 003 的实现进度（第一版可用简化文本区兜底）
- watcher 已具备：AuthInterceptor、统一 ApiResponse、GlobalExceptionHandler、AbstractEngineClient、Caffeine 缓存、分页 PageResult

## Assumptions
- 行情数据前复权、清洗由 watcher 上游完成（与 003 一致），本模块不涉及
- 5 个内置模板经校验合法，但不保证策略盈利
- A 股默认参数（T+1、lot_size=100、cn_stock_miniqmt 费率）由 backtest_config 默认值覆盖，用户可覆盖
- 策略自然语言摘要由前端 JS 字符串模板拼接（非 NLP），engine 不负责
- ref:highest_since_entry/lowest_since_entry（Schema §4.6 预留扩展）第一版 ALLOWED_REFS 中暂不包含（留作未来扩展，校验器对未知 ref 返回 UNKNOWN_REF_KEY 错误）
- 关联回测摘要（卡片底部的回测次数/夏普率）在 005 回测表就绪后通过关联查询补充，第一版显示「暂无回测」占位
- 混合范式（signals + rebalance 同时存在）第一版允许保存（校验通过），运行时优先级（调仓日是否跳过 signals）由 005 回测编译器定义
- 策略激活状态（ACTIVE）第一版仅为标记，自动信号生成归信号中心模块

## Acceptance Criteria

### AC-1: 策略 CRUD 持久化
- **Given**: watcher 启动、数据库迁移完成
- **When**: POST 创建策略 → GET 详情 → PUT 更新基本信息 → PUT 更新配置 → DELETE 软删除
- **Then**: 数据持久化到 quant_strategy + quant_strategy_version；创建时自动写 v1 快照；更新配置时写 v2 快照并更新 current_version；软删除后 status=ARCHIVED
- **Verification**: `programmatic`

### AC-2: 版本快照不可变与自动递增
- **Given**: 已有策略 v1
- **When**: 连续 3 次更新配置
- **Then**: 产生 v2、v3、v4 三个新版本快照；v1 快照 config_json 未被修改；current_version = 4
- **Verification**: `programmatic`

### AC-3: 版本 diff 与回滚
- **Given**: v1→v2（修改买入条件）→v3（修改仓位）
- **When**: 调 diff(v1, v3) → 调 rollback(to=v1)
- **Then**: diff 返回 trading_config.signals.buy 和 position_sizing 两处 modified 变更；rollback 创建 v4（config_json 与 v1 内容相同），v1/v2/v3 保留
- **Verification**: `programmatic`

### AC-4: 校验器 - 结构约束
- **Given**: engine 运行中
- **When**: POST validate 缺 signals 和 rebalance 的 config
- **Then**: 返回 {valid:false, errors:[{code:"MISSING_SIGNALS_OR_REBALANCE", path:"trading_config"}]}
- **Verification**: `programmatic`

### AC-5: 校验器 - 截面禁用项
- **Given**: engine 运行中
- **When**: POST validate screen_config.conditions 含 cross_up 的 config
- **Then**: 返回 {valid:false, errors:[{code:"SCREEN_TIME_SERIES_FORBIDDEN"}]}
- **Verification**: `programmatic`

### AC-6: 校验器 - 因子合法性
- **Given**: engine 运行中
- **When**: POST validate 引用 UNKNOWN_X factor 的 config
- **Then**: 返回 {valid:false, errors:[{code:"UNKNOWN_FACTOR"}]}
- **Verification**: `programmatic`

### AC-7: 校验器 - 合法配置通过
- **Given**: engine 运行中，使用 Schema §5.1 双均线示例和 §5.2 多因子价值示例
- **When**: POST validate
- **Then**: 两个示例均返回 {valid:true, errors:[]}
- **Verification**: `programmatic`

### AC-8: 校验器 - 多错误一次性返回
- **Given**: config 同时存在：缺 signals/rebalance、screen 含 cross_up、引用未知 factor
- **When**: POST validate
- **Then**: errors 数组包含全部 3 个错误（非短路），每个 error 带正确 path
- **Verification**: `programmatic`

### AC-9: 5 个内置模板全部通过校验
- **Given**: 5 个模板 JSON 文件
- **When**: 逐个 POST validate
- **Then**: 全部 {valid:true}
- **Verification**: `programmatic`

### AC-10: 校验失败时不写入版本
- **Given**: engine 运行中
- **When**: PUT /api/strategies/{id}/config 传一个含 UNKNOWN_FACTOR 的 config
- **Then**: watcher 返回 400 + errors 数组；quant_strategy_version 无新记录；current_version 不变
- **Verification**: `programmatic`

### AC-11: engine 不触库
- **Given**: engine 代码库 services/strategy/
- **When**: grep `sqlite3|sqlalchemy|\.db`
- **Then**: 无匹配
- **Verification**: `programmatic`

### AC-12: 危险字符串注入防护
- **Given**: config 中 position_sizing.method 为 `"__import__('os').system('rm -rf /')"`
- **When**: POST validate
- **Then**: 返回 {valid:false}（或 INVALID_POSITION_METHOD 或注入错误）；编译器不执行该字符串（注：004 无编译器，但校验器在白名单检查时拒绝）
- **Verification**: `programmatic`

### AC-13: 策略列表页加载
- **Given**: watcher 运行、已登录、有 10+ 条策略
- **When**: 浏览器访问 /quant/strategies
- **Then**: 页面正常渲染卡片网格，筛选栏可用，新建/模板下拉按钮可见
- **Verification**: `human-judgment`

### AC-14: 策略编辑器功能完整
- **Given**: watcher 运行、已登录
- **When**: 访问编辑器，切换 8 个 Tab，修改字段，点击保存（合法 config）
- **Then**: Tab 切换正常；右侧 JSON 预览实时更新；保存成功并跳转到版本页（显示 v2）；保存非法 config 时对应 Tab 标红
- **Verification**: `human-judgment`

### AC-15: 从模板创建
- **Given**: watcher 运行、已登录
- **When**: 从模板下拉选择「双均线策略」进入编辑器
- **Then**: 各 Tab 预填模板配置；用户修改 name 后保存可成功创建为新策略
- **Verification**: `human-judgment`

### AC-16: 版本时间线页功能
- **Given**: 有 3+ 版本的策略
- **When**: 访问版本页，展开 diff，执行回滚
- **Then**: 时间线正确显示版本序列；diff 三色标注字段变更；回滚后新版本出现
- **Verification**: `human-judgment`

### AC-17: 导航与权限
- **Given**: watcher 运行
- **When**: 未登录访问 /quant/strategies；已登录访问侧边栏
- **Then**: 未登录重定向到 /login；侧边栏「量化」下「策略管理」菜单项显示并可高亮
- **Verification**: `human-judgment`

### AC-18: API 文档完整性
- **Given**: engine 与 watcher 运行
- **When**: 访问 engine /docs 和 watcher 接口文档
- **Then**: validate 接口和策略 CRUD/版本/模板接口均有请求/响应模型和错误码说明
- **Verification**: `human-judgment`

## Open Questions
- [ ] **条件树编辑器前端组件依赖**：003 选股中心的前端条件树编辑器是否已经实现可用？如果未实现，第一版允许先用 JSON textarea 简化版过渡（已决策：允许简化版兜底，后端能力先行；前端保存前必须 JSON.parse 预校验）。
- [x] **策略分类（category）是否允许用户自定义**？（已决策：第一版固定 4 个枚举：技术面/基本面/混合/自定义，"自定义"作为兜底，不提供分类管理 UI）

## Resolved Decisions（本轮评审确认）
- ✅ **创建路径**：一步创建——POST /api/strategies 支持可选 configJson，携带则创建时校验+写 v1（合法 config，status=VERIFIED），不携带则写默认空白 config（status=DRAFT）
- ✅ **对外 id 语义**：API 路径 {id} 一律指 strategy_id（TEXT 业务 ID），INTEGER 自增 PK 不外泄
- ✅ **回滚是否校验**：回滚路径仍走 engine validate，Schema 升级导致旧 config 非法时返回 400+errors，不写新版本
- ✅ **DRAFT→VERIFIED 自动转换**：PUT /config 校验成功后，若 status==DRAFT 自动置 VERIFIED；ACTIVE/VERIFIED 状态改 config 保持原状态
