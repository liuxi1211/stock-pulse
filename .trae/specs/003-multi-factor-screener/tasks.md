# Tasks

## 阶段 0：基础共用层（engine 侧）

- [ ] Task 1: 定义条件表达式 Pydantic 数据模型（对齐统一 Schema §4）
  - [ ] SubTask 1.1: 定义 `ExpressionNode`（union：value/factor/op/ref 四形态）
  - [ ] SubTask 1.2: 定义 `CompareLeaf`（type/left/comparator/right）
  - [ ] SubTask 1.3: 定义 `ConditionTree`（递归 operator + conditions）
  - [ ] SubTask 1.4: 定义 `Comparator` 枚举（通用 + cross_up/cross_down）
  - [ ] SubTask 1.5: 模型放置于 `stock-engine/models/schemas/condition.py`
- [ ] Task 2: 实现 ConditionEngine 核心求值（`stock-engine/services/screener/engine.py`）
  - [ ] SubTask 2.1: 实现 `evaluate(tree, context) -> bool` 主入口，递归 AND/OR
  - [ ] SubTask 2.2: 实现 CompareLeaf 求值（含 6 个通用比较器）
  - [ ] SubTask 2.3: 实现 ExpressionNode 4 形态求值（value/factor/op/ref）
  - [ ] SubTask 2.4: 算术节点除零安全降级（返回 0）
  - [ ] SubTask 2.5: NaN 比较一律返回 False
  - [ ] SubTask 2.6: 截面合法性校验（拒绝 cross_up/cross_down/ref），违禁返回 422 + 路径
- [ ] Task 3: 因子值批量预计算（去重 + 复用 FactorCalculator）
  - [ ] SubTask 3.1: 遍历 condition tree 收集 (factorKey, params, output_index) 去重集合
  - [ ] SubTask 3.2: 对每只候选股票批量调 `FactorCalculator.compute`，结果缓存
  - [ ] SubTask 3.3: 基本面因子从 watcher 传入快照读取（不调 talib）
  - [ ] SubTask 3.4: 缺失 factorKey 返回 400 + `UNKNOWN_FACTOR`

## 阶段 1：排序、过滤与选股 API（engine 侧）

- [ ] Task 4: 实现静态过滤（Filters）
  - [ ] SubTask 4.1: ST/停牌/涨跌停过滤（依据 candidates 标记）
  - [ ] SubTask 4.2: 行业白/黑名单过滤
  - [ ] SubTask 4.3: 上市天数下限过滤
  - [ ] SubTask 4.4: 过滤在条件求值之前执行，产出 excluded 明细（verbose 模式）
- [ ] Task 5: 实现排序与打分（Ranking）
  - [ ] SubTask 5.1: 单因子排序（single，factor + order: asc/desc）
  - [ ] SubTask 5.2: 多因子综合排序（composite，z-score 加权，负权重=越小越好）
  - [ ] SubTask 5.3: NaN 维度剔除策略
  - [ ] SubTask 5.4: 同分按 symbol 升序兜底，rank 从 1 起
  - [ ] SubTask 5.5: top_n 截断（保留 total_count 全量计数）
- [ ] Task 6: 快照选股 HTTP API
  - [ ] SubTask 6.1: 定义请求/响应 Pydantic 模型（`stock-engine/models/schemas/screener.py`）
  - [ ] SubTask 6.2: 实现 `POST /python/v1/screener/snapshot` 路由（`api/v1/screener.py`）
  - [ ] SubTask 6.3: 编排：校验 → 过滤 → 因子预计算 → 条件求值 → 排序 → 截断 → 响应
  - [ ] SubTask 6.4: 在 main.py 注册 screener router
- [ ] Task 7: 区间选股 HTTP API
  - [ ] SubTask 7.1: 定义区间选股请求/响应模型（dates 数组 + 每日 candidates）
  - [ ] SubTask 7.2: 实现 `POST /python/v1/screener/range` 路由
  - [ ] SubTask 7.3: 逐日跑快照逻辑，聚合每只股票的 first_hit/hit_count/consecutive_max/daily_hits
  - [ ] SubTask 7.4: 计算 hit_ratio = hit_count / total_days

## 阶段 2：watcher 侧编排与持久化

- [ ] Task 8: 数据库表设计（watcher 侧 schema.sql）
  - [ ] SubTask 8.1: `screen_plan` 表（方案主表：id/name/screen_config JSON/时间戳）
  - [ ] SubTask 8.2: `screen_result` 表（执行记录：plan_id/执行时间/参数/命中股票 JSON）
  - [ ] SubTask 8.3: `screen_lock` 表（锁定记录：result_id/锁定时刻组合/追踪收益字段）
  - [ ] SubTask 8.4: 对应 MyBatis-Plus 实体 + Mapper
- [ ] Task 9: watcher ScreenerService 编排层
  - [ ] SubTask 9.1: 候选池解析（universe: all_a_shares/csi300/csi500/manual/自定义池）
  - [ ] SubTask 9.2: candidates 拼装（OHLCV 历史窗口 + 基本面快照 + 过滤标记）
  - [ ] SubTask 9.3: 经 HTTP 调 engine snapshot/range 接口
  - [ ] SubTask 9.4: 接收响应落 screen_result 表，返回前端
- [ ] Task 10: 选股方案 CRUD API（watcher Controller）
  - [ ] SubTask 10.1: `POST/GET/PUT/DELETE /api/screener/plans`
  - [ ] SubTask 10.2: `POST /api/screener/plans/{id}/run` 触发执行
  - [ ] SubTask 10.3: 统一 ApiResponse 封装 + 参数校验
- [ ] Task 11: 选股结果锁定与追踪
  - [ ] SubTask 11.1: `POST /api/screener/results/{id}/lock` 锁定接口
  - [ ] SubTask 11.2: 定时任务（每日收盘后）计算 5/10/20 日收益
  - [ ] SubTask 11.3: 追踪明细查询接口（组合累计收益 + 个股贡献 + vs 沪深300）

## 阶段 3：测试与文档

- [ ] Task 12: engine 侧单元测试
  - [ ] SubTask 12.1: ConditionEngine 求值测试（AND/OR 嵌套、算术、边界、NaN）→ `tests/test_screener/test_engine.py`
  - [ ] SubTask 12.2: 截面禁用项校验测试（cross_up/cross_down/ref 拒绝）
  - [ ] SubTask 12.3: 排序测试（single + composite，含负权重）
  - [ ] SubTask 12.4: 过滤测试（ST/停牌/涨跌停/行业/上市天数）
  - [ ] SubTask 12.5: API 集成测试（snapshot + range 端到端）→ `tests/test_screener/test_api.py`
  - [ ] SubTask 12.6: 不触库断言测试（搜索 sqlite3/sqlalchemy/.db 无命中）
- [ ] Task 13: watcher 侧集成测试
  - [ ] SubTask 13.1: 方案 CRUD 测试
  - [ ] SubTask 13.2: 选股执行 → 落库 → 查询链路测试
  - [ ] SubTask 13.3: 锁定 + 追踪收益计算测试
- [ ] Task 14: API 文档与示例
  - [ ] SubTask 14.1: engine FastAPI 自动文档补全（请求/响应示例）
  - [ ] SubTask 14.2: watcher 接口文档（如使用 swagger 引入计划则同步）

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
