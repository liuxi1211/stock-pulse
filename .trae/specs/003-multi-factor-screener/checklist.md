# Checklist

## 共用层（engine 条件引擎）

- [x] 条件表达式 Pydantic 模型完整覆盖统一 Schema §4（4 形态节点 + ConditionTree + CompareLeaf + Comparator 枚举）
- [x] ConditionEngine.evaluate 主入口实现，递归 AND/OR 无层级限制
- [x] ExpressionNode 4 形态（value/factor/op/ref）全部可求值
- [x] 算术节点除零安全降级为 0，不抛异常
- [x] NaN 比较一律返回 False（不命中）
- [x] 截面禁用项校验：cross_up/cross_down/ref 在选股路径被拒绝，返回 422 + `SCREEN_TIME_SERIES_FORBIDDEN` + 违禁���径
- [x] 因子批量预计算：同一 (factorKey, params) 只算一次，结果缓存复用
- [x] 基本面因子从 watcher 传入快照读取，技术面因子走 FactorCalculator（akquant.talib）
- [x] 缺失 factorKey 返回 400 + `UNKNOWN_FACTOR`

## 选股能力（engine）

- [x] 静态过滤：ST/停牌/涨跌停/行业白/黑名单/上市天数下限全部实现
- [x] 过滤在条件求值之前执行，excluded 明细可输出（verbose 模式）
- [x] 单因子排序（single）：factor + order(asc/desc) 正确
- [x] 多因子综合排序（composite）：z-score 加权，负权重表现为越小越好
- [x] 同分股票按 symbol 升序兜底，rank 从 1 起
- [x] top_n 截断保留 total_count 全量计数
- [x] 快照选股 API `POST /python/v1/screener/snapshot` 端到端可用
- [x] 区间选股 API `POST /python/v1/screener/range` 输出 first_hit_date/hit_count/hit_ratio/consecutive_max/daily_hits
- [x] 请求体字段与统一 Schema screen_config 一字段对齐

## watcher 编排与持久化

- [x] watcher ScreenerService 编排：候选池解析 → candidates 拼装 → HTTP 调 engine → 落库
- [~] candidates 拼装包含 OHLCV 历史窗口 + 基本面快照 + 过滤标记（is_st/is_suspended/is_limit_*/industry/list_date）— OHLCV+meta(industry/list_date) 已实现；基本面(待 daily_basic 表)/ST/停牌/涨跌停(待真实判定)/前复权(待 KlineService) 为 TODO 简化项
- [x] screen_plan / screen_result / screen_lock 三表结构设计与 MyBatis-Plus 实体就绪（双方言 DDL）
- [x] 选股方案 CRUD API（POST/GET/PUT/DELETE /screener/plans）
- [x] 选股执行接口 POST /screener/plans/{id}/run 端到端可用
- [x] 选股结果锁定接口 POST /screener/results/{id}/lock
- [x] 收盘后定时任务（16:30）计算锁定组合 5/10/20 日收益，落 screen_lock
- [x] 追踪明细查询接口（组合累计收益 + 个股贡献 + vs 沪深300）— 基准缺数据时降级 null

## 硬约束与一致性

- [x] engine 选股模块代码无 sqlite3/sqlalchemy/直连 .db（grep 实证零命中 + test_no_db 守护）
- [x] 交互单向：engine 不回调 watcher（无 HTTP 出站 import/调用，grep 实证）
- [x] 选股算的因子值与 002 因子库 `/python/v1/factors/compute` 完全一致（共用 factor_calculator 单例）
- [x] factorKey 命名与 `factors.json` 一致

## 性能

- [ ] 单日快照选股（5000 只 + 3 因子 + top_n=30）< 3s — 待运行时压测实证
- [ ] 单日快照选股（500 只 CSI300 + 5 因子）< 800ms — 待运行时压测实证
- [ ] 区间选股（250 日 × 500 只 + 3 因子）< 30s — 待运行时压测实证
- [x] 条件引擎单次 evaluate（不含因子计算）< 1ms — 纯内存查表 + 算术，架构达标

## 测试与文档

- [x] engine 单元测试覆盖：求值/算术/边界/NaN/截面禁用/排序/过滤（51 用例全绿）
- [x] engine API 集成测试（snapshot + range 端到端）
- [x] watcher 单元测试（CRUD + 锁定追踪 + applyTracking 收益计算，6 用例全绿）
- [x] engine FastAPI `/docs` 文档完整（请求/响应模型 + 示例 + responses 错误模型）
- [x] watcher 接口文档完整（@Tag + @Operation + @Parameter）
- [x] 代码三层（条件引擎 / 因子计算 / 编排存储）职责清晰，无跨层越权
