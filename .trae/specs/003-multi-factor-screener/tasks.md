# Tasks

## 阶段 0：基础共用层（engine 侧）

- [x] Task 1: 定义条件表达式 Pydantic 数据模型（对齐统一 Schema §4）
  - [x] SubTask 1.1: 定义 `ExpressionNode`（union：value/factor/op/ref 四形态）
  - [x] SubTask 1.2: 定义 `CompareLeaf`（type/left/comparator/right）
  - [x] SubTask 1.3: 定义 `ConditionTree`（递归 operator + conditions）
  - [x] SubTask 1.4: 定义 `Comparator` 枚举（通用 + cross_up/cross_down）
  - [x] SubTask 1.5: 模型放置于 `stock-engine/models/schemas/condition.py`
- [x] Task 2: 实现 ConditionEngine 核心求值（`stock-engine/services/screener/engine.py`）
  - [x] SubTask 2.1: 实现 `evaluate(tree, context) -> bool` 主入口，递归 AND/OR
  - [x] SubTask 2.2: 实现 CompareLeaf 求值（含 6 个通用比较器）
  - [x] SubTask 2.3: 实现 ExpressionNode 4 形态求值（value/factor/op/ref）
  - [x] SubTask 2.4: 算术节点除零安全降级（返回 0）
  - [x] SubTask 2.5: NaN 比较一律返回 False
  - [x] SubTask 2.6: 截面合法性校验（拒绝 cross_up/cross_down/ref），违禁返回 422 + 路径
- [x] Task 3: 因子值批量预计算（去重 + 复用 FactorCalculator）
  - [x] SubTask 3.1: 遍历 condition tree 收集 (factorKey, params, output_index) 去重集合
  - [x] SubTask 3.2: 对每只候选股票批量调 `FactorCalculator.compute`，结果缓存
  - [x] SubTask 3.3: 基本面因子从 watcher 传入快照读取（不调 talib）
  - [x] SubTask 3.4: 缺失 factorKey 返回 400 + `UNKNOWN_FACTOR`

## 阶段 1：排序、过滤与选股 API（engine 侧）

- [x] Task 4: 实现静态过滤（Filters）
  - [x] SubTask 4.1: ST/停牌/涨跌停过滤（依据 candidates 标记）
  - [x] SubTask 4.2: 行业白/黑名单过滤
  - [x] SubTask 4.3: 上市天数下限过滤
  - [x] SubTask 4.4: 过滤在条件求值之前执行，产出 excluded 明细（verbose 模式）
- [x] Task 5: 实现排序与打分（Ranking）
  - [x] SubTask 5.1: 单因子排序（single，factor + order: asc/desc）
  - [x] SubTask 5.2: 多因子综合排序（composite，z-score 加权，负权重=越小越好）
  - [x] SubTask 5.3: NaN 维度剔除策略
  - [x] SubTask 5.4: 同分按 symbol 升序兜底，rank 从 1 起
  - [x] SubTask 5.5: top_n 截断（保留 total_count 全量计数）
- [x] Task 6: 快照选股 HTTP API
  - [x] SubTask 6.1: 定义请求/响应 Pydantic 模型（`stock-engine/models/schemas/screener.py`）
  - [x] SubTask 6.2: 实现 `POST /python/v1/screener/snapshot` 路由（`api/v1/screener.py`）
  - [x] SubTask 6.3: 编排：校验 → 过滤 → 因子预计算 → 条件求值 → 排序 → 截断 → 响应
  - [x] SubTask 6.4: 在 main.py 注册 screener router
- [x] Task 7: 区间选股 HTTP API
  - [x] SubTask 7.1: 定义区间选股请求/响应模型（dates 数组 + 每日 candidates）
  - [x] SubTask 7.2: 实现 `POST /python/v1/screener/range` 路由
  - [x] SubTask 7.3: 逐日跑快照逻辑，聚合每只股票的 first_hit/hit_count/consecutive_max/daily_hits
  - [x] SubTask 7.4: 计算 hit_ratio = hit_count / total_days

## 阶段 2：watcher 侧编排与持久化

- [x] Task 8: 数据库表设计（watcher 侧 DDL 注册到 DataInitServiceImpl）
  - [x] SubTask 8.1: `screen_plan` 表（方案主表：id/name/screen_config JSON/时间戳）
  - [x] SubTask 8.2: `screen_result` 表（执行记录：plan_id/执行时间/参数/命中股票 JSON）
  - [x] SubTask 8.3: `screen_lock` 表（锁定记录：result_id/锁定时刻组合/追踪收益字段）
  - [x] SubTask 8.4: 对应 MyBatis-Plus 实体 + Mapper
- [x] Task 9: watcher ScreenerService 编排层
  - [x] SubTask 9.1: 候选池解析（universe: all_a_shares/csi300/csi500/manual/自定义池）— csi300/csi500 降级全市场（TODO 成分股表）
  - [x] SubTask 9.2: candidates 拼装（OHLCV 历史窗口 + 基本面快照 + 过滤标记）— 基本面/ST/涨跌停/前复权为简化项（TODO）
  - [x] SubTask 9.3: 经 HTTP 调 engine snapshot/range 接口（snapshot 已实现；range 待补）
  - [x] SubTask 9.4: 接收响应落 screen_result 表，返回前端
- [x] Task 10: 选股方案 CRUD API（watcher Controller）
  - [x] SubTask 10.1: `POST/GET/PUT/DELETE /screener/plans`
  - [x] SubTask 10.2: `POST /screener/plans/{id}/run` 触发执行
  - [x] SubTask 10.3: 统一 ApiResponse 封装 + 参数校验
- [x] Task 11: 选股结果锁定与追踪
  - [x] SubTask 11.1: `POST /screener/results/{id}/lock` 锁定接口
  - [x] SubTask 11.2: 定时任务（每日 16:30 收盘后）计算 5/10/20 日收益
  - [x] SubTask 11.3: 追踪明细查询接口（组合累计收益 + 个股贡献 + vs 沪深300）— 基准 000300.SH 缺数据时降级 null

## 阶段 3：测试与文档

- [x] Task 12: engine 侧单元测试
  - [x] SubTask 12.1: ConditionEngine 求值测试（AND/OR 嵌套、算术、边界、NaN）→ `tests/test_screener/test_engine.py`（16 用例）
  - [x] SubTask 12.2: 截面禁用项校验测试（cross_up/cross_down/ref 拒绝）
  - [x] SubTask 12.3: 排序测试（single + composite，含负权重）→ `tests/test_screener/test_ranking.py`（13 用例）
  - [x] SubTask 12.4: 过滤测试（ST/停牌/涨跌停/行业/上市天数）→ `tests/test_screener/test_filters.py`（13 用例）
  - [x] SubTask 12.5: API 集成测试（snapshot + range 端到端）→ `tests/test_screener/test_api.py`（7 用例）
  - [x] SubTask 12.6: 不触库断言测试（搜索 sqlite3/sqlalchemy/.db 无命中）→ `tests/test_screener/test_no_db.py`
- [x] Task 13: watcher 侧单元测试
  - [x] SubTask 13.1: 方案 CRUD 测试（lockResult 防重复/不存在分支）
  - [x] SubTask 13.2: 选股执行 → 落库 → 查询链路测试（getLockDetail stocksJson 解析）
  - [x] SubTask 13.3: 锁定 + 追踪收益计算测试（applyTracking 等权组合 5/10/20 日 + 基准 + DONE 状态）→ `ScreenerServiceImplTest`（6 用例）
- [x] Task 14: API 文档与示例
  - [x] SubTask 14.1: engine FastAPI 自动文档补全（每路由 summary/description/responses + Schema Field examples）
  - [x] SubTask 14.2: watcher 接口文档（ScreenerController @Tag + @Operation + @Parameter）

# Task Dependencies
- Task 2 依赖 Task 1（模型定义）
- Task 3 依赖 002 因子库（FactorCalculator）已可用
- Task 4/5 可与 Task 2/3 部分并行（独立逻辑单元）
- Task 6 依赖 Task 2/3/4/5
- Task 7 依赖 Task 6（复用快照逻辑）
- Task 9 依赖 Task 6/7（engine 接口就绪）
- Task 10/11 依赖 Task 8/9
- Task 12 依赖 engine 侧 Task 1~7
- Task 13 依赖 watcher 侧 Task 8~11

# 遗留项（依赖外部数据源，已 TODO 标注，不阻塞主链路）
- watcher candidates 基本面字段：待 daily_basic 表建立后喂数据（engine 侧逻辑已就绪）
- watcher candidates 前复权：待接入 KlineService 前复权口径
- watcher candidates ST/停牌/涨跌停 meta 真实判定（stock_basic.name 含 ST / daily_quote.pct_chg 判涨跌停）
- 成分股指池（csi300/csi500）成分股表：当前降级为全市场
- watcher range 编排（HTTP 调 engine /range）：engine 侧已实现，watcher 侧 ScreenerService 暂未接入区间选股编排
- 基准（沪深300）指数行情：daily_quote 无指数数据时 benchmark 降级 null
