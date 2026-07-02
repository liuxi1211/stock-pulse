# 标准因子库 - Product Requirement Document

## Overview
- **Summary**: 在 stock-engine（Python）侧实现完整的标准因子库服务，包含 49 个标准因子的元数据管理（CRUD）与计算能力。因子定义以 JSON 文件形式存储在 engine 项目内，engine 对外提供元数据查询/修改接口和因子计算接口；stock-watcher（Java）侧通过 Caffeine 缓存因子元数据，修改时同步失效缓存。技术面因子统一走 `akquant.talib`，确保选股与回测的因子口径一致。
- **Purpose**: 建立 StockPulse 量化研究模块的因子基础设施，为多因子选股、策略回测、条件表达式引擎提供统一的因子计算能力，解决"两套实现、口径不一致、重复建设"的问题；同时支持运行时因子管理，为后续前端因子配置页面提供后端能力。
- **Target Users**: 多因子选股模块、策略回测模块、前端因子配置页面、前端条件编辑器、量化研究用户。

## Goals
- 在 engine 项目内以 JSON 文件形式存储 49 个标准因子定义（技术面 32 + 价格直通 5 + 衍生 3 + 基本面 9）
- 提供因子元数据 CRUD API（查询/新增/修改/删除/分类管理）
- 提供单标的/批量标的的因子计算 HTTP API
- 因子计算接口与统一策略配置 Schema 的 `{factor}` 节点完全对齐
- 技术面因子 100% 基于 akquant.talib，确保选股与回测口径一致
- watcher 侧通过 Caffeine 缓存因子元数据，修改时主动失效同步
- 严格遵守 engine 不触库的硬约束，所有输入数据由调用方传入

## Non-Goals (Out of Scope)
- 基本面因子的实际值计算（基本面数据由 watcher 侧 Tushare 采集并存库，engine 只做元数据注册）
- 因子预计算与存储（预计算由 watcher 侧调度，engine 只提供实时计算能力）
- 条件表达式引擎（归 003 多因子选股中心）
- 策略回测（归 005 回测中心）
- 前端因子配置页面 UI（由 watcher 前端单独实现，后续单独设计）
- 因子版本管理与回滚（第一版不做，后续可演进为带版本号的 JSON 历史）

## Background & Context

### 项目架构
StockPulse 采用 Java + Python 双系统架构：
- **stock-watcher（Java）**: 业务 + 数据中台，数据获取/存储、前端、策略管理、因子元数据缓存
- **stock-engine（Python）**: 计算服务，技术指标、因子计算、策略回测、因子元数据管理

硬约束：engine 不读写 SQLite，数据由 watcher 经 HTTP 传入，engine 只返回 JSON。

### 因子库定义文件位置
- **设计文档（源）**: [sdlc/prd/002-因子库/标准因子库-v2.json](file:///d:/lcProject/stock-pulse/sdlc/prd/002-%E5%9B%A0%E5%AD%90%E5%BA%93/%E6%A0%87%E5%87%86%E5%9B%A0%E5%AD%90%E5%BA%93-v2.json) —— 仅作设计稿参考，不作为运行时代码
- **运行时文件**: `stock-engine/data/factors.json` —— engine 启动时加载，CRUD 修改后写入此文件
- **初始化**: 首次部署时从设计稿拷贝一份到运行时目录

### 因子分类（10 大类，共 49 个）
| 类别 | 数量 | 来源 | 说明 |
|---|---|---|---|
| OVERLAP 趋势指标 | 11 | AKQUANT | MA/EMA/WMA/DEMA/TEMA/TRIMA/KAMA/T3/MAMA/BOLL/SAR |
| MOMENTUM 动量指标 | 14 | AKQUANT | MACD/RSI/KDJ/STOCHRSI/CCI/WILLR/ADX/PLUS_DI/MINUS_DI/ROC/MOM/APO/PPO/TRIX |
| VOLATILITY 波动率 | 3 | AKQUANT | ATR/NATR/TRANGE |
| VOLUME 成交量指标 | 5 | AKQUANT+DERIVED | OBV/AD/ADOSC/VOL_MA/VOL_EMA |
| STATISTIC 统计指标 | 1 | AKQUANT | STDDEV |
| PRICE 价格直通 | 5 | RAW | OPEN/HIGH/LOW/CLOSE/VOLUME |
| VALUATION 估值因子 | 7 | TUSHARE | PE_TTM/PB/PS_TTM/DV_RATIO/TOTAL_MV/CIRC_MV/TURNOVER_RATE |
| QUALITY 质量因子 | 4 | TUSHARE | ROE_TTM/ROA_TTM/GROSS_MARGIN/NETPROFIT_MARGIN |
| GROWTH 成长因子 | 3 | TUSHARE | REVENUE_YOY/PROFIT_YOY/EPS_YOY |
| FINANCE 财务结构 | 2 | TUSHARE | DEBT_TO_ASSETS/CURRENT_RATIO |

### Watcher 侧缓存机制
- watcher 在 `FactorCacheService` 中用 Caffeine 缓存因子元数据（全量列表 + 单因子详情）
- 首次访问时调 engine 的 `GET /python/v1/factors` 拉取全量并缓存
- watcher 前端发起新增/修改/删除操作时：
  1. watcher 调 engine 的对应写接口
  2. engine 写入 JSON 文件并返回成功
  3. watcher 主动失效本地缓存（或直接更新缓存）
- 缓存 TTL：5 分钟（兜底，防止遗漏失效场景）

### 相关设计文档
- [选股与回测边界设计](file:///d:/lcProject/stock-pulse/sdlc/prd/003-%E5%A4%9A%E5%9B%A0%E5%AD%90%E9%80%89%E8%82%A1%E4%B8%AD%E5%BF%83/%E9%80%89%E8%82%A1%E4%B8%8E%E5%9B%9E%E6%B5%8B%E8%BE%B9%E7%95%8C%E8%AE%BE%E8%AE%A1.md) - 因子库是共用层
- [统一策略配置 Schema](file:///d:/lcProject/stock-pulse/sdlc/prd/004-%E7%AD%96%E7%95%A5%E7%AE%A1%E7%90%86/%E7%BB%9F%E4%B8%80%E7%AD%96%E7%95%A5%E9%85%8D%E7%BD%AESchema.md) - §4.5 factorKey 体系、§4.3 因子节点签名
- [akquant 知识库](file:///d:/lcProject/stock-pulse/.trae/rules/akquant/07-talib-indicators.md) - 技术指标函数签名

## Functional Requirements

### FR-1: 因子库定义加载与持久化
- engine 启动时从 `stock-engine/data/factors.json` 加载因子定义到内存
- 若文件不存在，从内置默认定义初始化（即 49 个标准因子）并写入文件
- 提供因子元数据查询：按分类、按 factorKey、按来源（AKQUANT/TUSHARE/RAW/DERIVED）
- 校验 factorKey 唯一性、分类有效性、参数完整性
- 因子定义修改后原子写入 JSON 文件（写临时文件 + rename，防止半写损坏）
- 内存与文件始终保持一致（内存是唯一读写入口，文件是持久化镜像）

### FR-2: 因子元数据 CRUD API
- `GET /python/v1/factors/categories` - 获取所有因子分类
- `GET /python/v1/factors` - 分页/按分类/按来源查询因子列表
- `GET /python/v1/factors/{factorKey}` - 获取单个因子详情
- `POST /python/v1/factors` - 新增因子（factorKey 唯一校验）
- `PUT /python/v1/factors/{factorKey}` - 修改因子定义
- `DELETE /python/v1/factors/{factorKey}` - 删除因子
- 响应包含：factorKey, displayName, category, source, description, params, inputs, multiOutput, outputLabels, defaultOutputIndex, lookbackHint, lookbackDefault

### FR-3: 技术面因子计算（AKQUANT 来源）
- 实现 `FactorCalculator.compute(factorKey, inputs, **params)` 接口
- 输入：OHLCV 数据（dict of np.ndarray）+ 因子参数
- 输出：np.ndarray（单输出）或 tuple[np.ndarray, ...]（多输出）
- 覆盖 32 个 AKQUANT 来源因子：
  - OVERLAP: MA, EMA, WMA, DEMA, TEMA, TRIMA, KAMA, T3, MAMA, BOLL(BBANDS), SAR
  - MOMENTUM: MACD, RSI, KDJ(STOCH+J合成), STOCHRSI, CCI, WILLR, ADX, PLUS_DI, MINUS_DI, ROC, MOM, APO, PPO, TRIX
  - VOLATILITY: ATR, NATR, TRANGE
  - VOLUME: OBV, AD, ADOSC
  - STATISTIC: STDDEV
- KDJ 特殊处理：`STOCH` 原生返回 (K,D)，计算层合成 J=3K−2D，对外输出三元组

### FR-4: 价格直通与衍生因子计算
- PRICE 类（5个）：OPEN/HIGH/LOW/CLOSE/VOLUME 直接返回对应列
- DERIVED 类（2个）：VOL_MA（SMA on volume）、VOL_EMA（EMA on volume）—— 复用 akquant MA/EMA 函数，输入列换为 volume

### FR-5: 基本面因子元数据注册
- TUSHARE 来源的 16 个基本面因子只注册元数据（参数、描述、分类）
- 不提供实时计算能力（基本面数据由 watcher 侧从数据库读取）
- 计算接口对 TUSHARE 因子返回明确的错误码：`FACTOR_NOT_COMPUTABLE`

### FR-6: 单标的因子计算 API
- `POST /python/v1/factors/compute` - 计算单个标的的单个或多个因子
- 请求体：
  - `data`: OHLCV 数据（list[dict]，含 date/open/high/low/close/volume）
  - `factors`: 要计算的因子列表 `[{factorKey, params, outputIndex?}]`
- 响应：每个因子的计算结果（list[float]，与输入数据等长）
- 支持批量因子单次请求计算，减少网络往返

### FR-7: 多标的批量因子计算 API
- `POST /python/v1/factors/batch-compute` - 批量计算多只股票的因子
- 请求体：
  - `data`: `{symbol: [ohlcv records]}` 映射
  - `factors`: 因子列表
- 响应：`{symbol: {factorKey: [...]}}` 嵌套结构
- 用于选股场景下的批量因子值计算

### FR-8: 因子预热期/回看长度计算
- 给定因子参数，计算所需的最小回看 bar 数（lookback）
- 用于上游判断数据是否充足、设置 warmup_period
- 支持 `get_lookback(factorKey, params) -> int`

### FR-9: Watcher 侧因子缓存与同步
- watcher 侧实现 `FactorCacheService`（Java），使用 Caffeine 缓存
- 缓存内容：全量因子列表、单因子详情、分类列表
- 缓存策略：首次访问懒加载 + 写操作后主动失效
- 写操作（新增/修改/删除）流程：
  1. watcher 接收前端请求
  2. watcher 调 engine 对应写接口
  3. engine 写入 JSON 文件，返回成功
  4. watcher 清除本地因子缓存（下次读取时重新拉取）
- 兜底 TTL：5 分钟（防止遗漏失效场景导致长期不一致）

## Non-Functional Requirements

### NFR-1: 性能
- 单标的单因子计算（250 根日线）响应时间 < 10ms
- 单标的 10 个因子计算（250 根日线）响应时间 < 50ms
- 50 只股票 5 个因子批量计算（250 根日线）响应时间 < 500ms
- 因子元数据查询（全量列表）响应时间 < 5ms
- 因子新增/修改操作响应时间 < 50ms（文件写入开销）

### NFR-2: 正确性
- 技术面因子计算结果与 akquant.talib 原生调用完全一致（不自行实现算法）
- 多输出因子的 output_index 取值正确
- NaN 传播行为与 talib 一致（预热期为 NaN）
- JSON 文件写入原子性：进程崩溃不会导致文件损坏

### NFR-3: 可靠性
- 参数校验：非法 factorKey 返回 400 + 明确错误码
- 参数越界：超出 min/max 返回校验错误
- 数据不足：输入数据长度 < lookback 时，结果前补 NaN，不报错
- 所有异常捕获并返回结构化错误响应（success: false + code + message）
- JSON 文件损坏时，从内置默认因子恢复并告警

### NFR-4: 并发安全
- 读操作（查询/计算）无锁，天然并发安全
- 写操作（新增/修改/删除）用线程锁串行化，防止并发写入文件损坏
- 写操作期间读操作返回旧值（最终一致性，可接受）

### NFR-5: 可维护性
- 因子注册采用声明式配置驱动，新增因子只需加 JSON 定义 + 映射
- 计算层与 akquant.talib 解耦，通过 provider 模式适配
- 代码符合 `.trae/rules/stock-engine/python/` 编码规范

### NFR-6: 兼容性
- 因子节点签名与统一 Schema §4.3 完全对齐：`compute(name, inputs, **params)`
- factorKey 命名与 `标准因子库-v2.json` 完全一致
- 多输出因子的 output_index 顺序与 Schema §4.5 对齐

## Constraints

### 技术约束
- **语言**: Python 3.12+（engine 侧）、Java 21（watcher 侧缓存）
- **框架**: FastAPI 0.115.7 + Pydantic 2.x（engine 侧）、Spring Boot + Caffeine（watcher 侧）
- **计算引擎**: akquant 0.2.47（`akquant.talib` 子模块）
- **数据处理**: pandas 3.0.x + numpy 2.4.x
- **存储**: JSON 文件（engine 项目内 `data/factors.json`），不使用数据库
- **硬约束**: engine 侧禁止 `sqlite3`/`sqlalchemy`/直连 `.db`，所有数据由调用方传入

### 业务约束
- 技术面因子必须走 `akquant.talib`，禁止自行实现算法
- 运行时因子定义的唯一真相源是 engine 的 `data/factors.json`
- 选股与回测必须共用同一套因子计算代码

### 依赖约束
- akquant 0.2.47 的 talib 子模块提供的函数集
- watcher 侧需提供 OHLCV 数据（前复权、清洗后）

## Assumptions
- `标准因子库-v2.json` 中的 akquantFunc 映射正确（MA→MA, BOLL→BBANDS, KDJ→STOCH 等）
- watcher 传入的 OHLCV 数据已经过前复权、剔除停牌/涨跌停/ST 的清洗
- 输入数据的时间列字段名为 `date`（watcher 标准格式）
- 基本面因子的实际值由 watcher 侧从数据库读取，engine 只负责技术面计算
- akquant 0.2.47 的 `talib` 子模块包含 JSON 中引用的所有函数（WMA, DEMA, TEMA, TRIMA, KAMA, T3, MAMA, STOCHRSI, NATR, TRANGE, STDDEV, AD, ADOSC, APO, PPO, TRIX 等）
- 因子 CRUD 的并发量很低（管理后台操作），单线程锁足够

## Acceptance Criteria

### AC-1: 因子元数据查询
- **Given**: 因子库服务已启动，JSON 文件已加载
- **When**: 调用 `GET /python/v1/factors`
- **Then**: 返回 49 个因子，分类、参数、描述与初始定义一致
- **Verification**: `programmatic`

### AC-2: 单因子计算正确性
- **Given**: 有 250 根标准日线数据
- **When**: 计算 MA(timeperiod=5)、RSI(timeperiod=14)、MACD 等常用因子
- **Then**: 结果与直接调用 `akquant.talib.MA/RSI/MACD` 的输出完全一致（np.allclose）
- **Verification**: `programmatic`

### AC-3: KDJ 三输出正确性
- **Given**: 有 100 根日线数据
- **When**: 计算 KDJ(fastk_period=9, slowk_period=3, slowd_period=3)
- **Then**: 返回三元组 (K, D, J)，其中 K、D 与 STOCH 输出一致，J = 3*K - 2*D
- **Verification**: `programmatic`

### AC-4: 多输出因子 output_index 取值
- **Given**: MACD/BOLL/KDJ 等多输出因子
- **When**: 指定 output_index = 0/1/2
- **Then**: 返回对应输出序列，索引越界返回 400 错误
- **Verification**: `programmatic`

### AC-5: 价格直通因子
- **Given**: 输入 OHLCV 数据
- **When**: 计算 CLOSE/HIGH/LOW/OPEN/VOLUME
- **Then**: 返回值与输入对应列完全相同
- **Verification**: `programmatic`

### AC-6: 衍生因子 VOL_MA/VOL_EMA
- **Given**: 输入含 volume 列的 OHLCV 数据
- **When**: 计算 VOL_MA(timeperiod=20)、VOL_EMA(timeperiod=20)
- **Then**: 结果等于对 volume 列调用 MA/EMA 的结果
- **Verification**: `programmatic`

### AC-7: 批量因子计算
- **Given**: 30 只股票的 OHLCV 数据 + 5 个因子
- **When**: 调用批量计算接口
- **Then**: 每只股票每个因子都有正确结果，总响应时间 < 500ms
- **Verification**: `programmatic`

### AC-8: 数据不足时的 NaN 处理
- **Given**: 只有 10 根日线，计算 MA(timeperiod=20)
- **When**: 调用计算接口
- **Then**: 不报错，结果前 19 个为 NaN，第 20 个起为有效值（不足时全为 NaN）
- **Verification**: `programmatic`

### AC-9: 非法 factorKey 处理
- **Given**: 因子库已加载
- **When**: 使用不存在的 factorKey 调用计算
- **Then**: 返回 400 状态码 + 错误码 `UNKNOWN_FACTOR`
- **Verification**: `programmatic`

### AC-10: 基本面因子不可计算
- **Given**: 因子库已加载
- **When**: 尝试计算 PE_TTM、ROE_TTM 等 TUSHARE 来源因子
- **Then**: 返回 400 状态码 + 错误码 `FACTOR_NOT_COMPUTABLE`，提示基本面因子由 watcher 侧提供
- **Verification**: `programmatic`

### AC-11: 参数校验
- **Given**: 因子参数有 min/max 约束
- **When**: 传入超出范围的参数值
- **Then**: 返回 400 状态码 + 错误码 `INVALID_PARAM` + 具体字段提示
- **Verification**: `programmatic`

### AC-12: 不触库硬约束
- **Given**: engine 服务代码库
- **When**: 搜索 `sqlite3`/`sqlalchemy`/`.db` 相关代码
- **Then**: 因子库模块中不出现任何数据库连接或操作代码
- **Verification**: `programmatic`

### AC-13: 因子新增
- **Given**: 因子库已加载，factorKey="NEW_FACTOR" 不存在
- **When**: 调用 `POST /python/v1/factors` 新增一个因子
- **Then**: 返回 201，随后 `GET /python/v1/factors/NEW_FACTOR` 能查到；重启服务后仍然存在（持久化到文件）
- **Verification**: `programmatic`

### AC-14: 因子修改
- **Given**: 因子库已加载，存在 factorKey="MA"
- **When**: 调用 `PUT /python/v1/factors/MA` 修改 displayName
- **Then**: 返回 200，随后查询 displayName 已更新；重启服务后仍然生效
- **Verification**: `programmatic`

### AC-15: 因子删除
- **Given**: 因子库已加载，存在自定义 factorKey="MY_FACTOR"
- **When**: 调用 `DELETE /python/v1/factors/MY_FACTOR`
- **Then**: 返回 200，随后查询返回 404；重启服务后仍然删除
- **Verification**: `programmatic`

### AC-16: factorKey 唯一约束
- **Given**: 因子库已存在 factorKey="MA"
- **When**: 调用 `POST /python/v1/factors` 再新增一个 factorKey="MA" 的因子
- **Then**: 返回 400 + 错误码 `FACTOR_ALREADY_EXISTS`
- **Verification**: `programmatic`

### AC-17: JSON 文件写入原子性
- **Given**: 正常运行的因子库
- **When**: 写入过程中模拟进程崩溃（可通过测试验证原子写入逻辑）
- **Then**: JSON 文件不会损坏，要么是旧版本要么是新版本，不会出现半写状态
- **Verification**: `programmatic`

### AC-18: Watcher 侧缓存一致性
- **Given**: watcher 已缓存因子列表
- **When**: 通过 watcher 调用新增因子接口
- **Then**: watcher 本地缓存被清除，下次查询时从 engine 重新拉取并获得最新数据
- **Verification**: `programmatic`

### AC-19: API 文档完整性
- **Given**: FastAPI 服务运行中
- **When**: 访问 `/docs`
- **Then**: 所有因子相关接口（查询/新增/修改/删除/计算/批量计算）都有完整的请求/响应模型、描述、示例
- **Verification**: `human-judgment`

### AC-20: 代码结构清晰
- **Given**: 因子库模块代码
- **When**: 代码审查
- **Then**: 遵循「定义加载 → Provider 层 → Service 层 → API 层」四层架构，职责清晰
- **Verification**: `human-judgment`

## Open Questions
- [ ] akquant 0.2.47 的 talib 模块是否完整支持 WMA/DEMA/TEMA/TRIMA/KAMA/T3/MAMA/STOCHRSI/NATR/TRANGE/STDDEV/AD/ADOSC/APO/PPO/TRIX 这些函数？需实际验证后再决定是否需要降级或自实现
- [ ] 因子删除的安全策略——是否禁止删除系统内置的 49 个标准因子？还是全部允许删除，靠初始化机制兜底？
- [ ] 批量计算接口的数据传输格式——是传 list[dict] 还是传 base64 编码的 numpy 数组以提升性能？第一版先用 list[dict] 保证兼容性
- [ ] 是否需要因子变更日志/操作审计？第一版不做，后续有需求再加
- [ ] watcher 侧缓存失效的实现方式——是 engine 主动回调 watcher 通知失效，还是 watcher 写操作后自己清缓存？第一版采用 watcher 自清理模式（更简单，不破坏 engine 不回调的单向约束）
