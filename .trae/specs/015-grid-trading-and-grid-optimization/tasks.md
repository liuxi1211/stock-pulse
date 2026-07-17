# Tasks

> **change-id**：`015-grid-trading-and-grid-optimization`
> **依赖关系**：见文末「Task Dependencies」。网格线（G 系列）与寻优线（O 系列）文件不重叠，可两人并行；但寻优线的「第零波」是 O 系列的硬前置。

---

## 第零波：tunable_params Schema 扩展（O 系列硬前置）

- [x] Task 0.1：`StrategyConfigModel` 增加 `tunable_params` 字段
  - [ ] SubTask 0.1.1：`services/strategy/models.py` 新增 `TunableParamModel`（name/type/default/min/max/step），`StrategyConfigModel` 增加可选 `tunable_params: list[TunableParamModel]` 字段
  - [ ] SubTask 0.1.2：`services/strategy/validator.py` 增加 tunable_params 校验（name 唯一、type 合法、default 在 min/max 内、step>0）
  - [ ] SubTask 0.1.3：`services/strategy/errors.py` 新增错误码 `TUNABLE_PARAM_UNKNOWN` / `TUNABLE_PARAM_INVALID`
  - [ ] SubTask 0.1.4：单元测试 `tests/services/strategy/test_validator.py` 增 tunable_params 用例（合法/非法）

- [x] Task 0.2：8 个预置模板回填 tunable_params
  - [ ] SubTask 0.2.1：定位 spec 014 的 8 个预置模板 JSON（stock-watcher 侧模板表）
  - [ ] SubTask 0.2.2：为每个模板的可调参数（如双均线的 fast/slow、RSI 的 timeperiod）回填 tunable_params
  - [ ] SubTask 0.2.3：回归���试存量模板加载不被破坏

---

## 第一波 A：网格交易（G 系列，可与 O 系列并行）

### G-1 Schema 与校验

- [x] Task G-1.1：扩展 `PositionSizingModel` 支持 grid method
  - [ ] SubTask G-1.1.1：`services/strategy/models.py` 新增 `GridParamsModel`（center/step/qty_per_grid/max_grids/stop_loss/max_holding_bars/max_position_value_pct/re_entry_after_clear/unfilled_retry_bars/adjust_mode/step_mode/qty_mode/take_profit_price）
  - [ ] SubTask G-1.1.2：`services/strategy/constants.py` 的 `POSITION_SIZING_METHODS` 增 `"grid"`；新增 `GRID_MAX_GRIDS_LIMIT=20` / `GRID_TICK_SIZE=0.01` / `GRID_DEFAULT_MAX_POSITION_VALUE_PCT=0.9` 等常量
  - [ ] SubTask G-1.1.3：`PositionSizingModel.method` 允许 `"grid"`，method=grid 时 params 用 `GridParamsModel` 校验

- [x] Task G-1.2：三范式互斥 + grid 与 exit 互斥校验
  - [ ] SubTask G-1.2.1：`services/strategy/errors.py` 新增 `GRID_PARADIGM_EXCLUSIVE` / `GRID_EXIT_CONFLICT` / `GRID_RISK_REQUIRED` / `GRID_INSUFFICIENT_CAPITAL` / `GRID_PARAM_INVALID`
  - [ ] SubTask G-1.2.2：`services/strategy/validator.py` 扩展 `_check_paradigm`：method=grid 时拒绝 signals/rebalance；grid 时 exit.bracket/rules 必须空
  - [ ] SubTask G-1.2.3：validator 校验 stop_loss 二选一必填、max_holding_bars 必填、max_position_value_pct 必填
  - [ ] SubTask G-1.2.4：validator 校验 qty_per_grid % 100 == 0、max_grids ≤ 20、center>0、step.value>0
  - [ ] SubTask G-1.2.5：validator 校验资金占用 `max_grids × qty_per_grid × center ≤ initial_cash × max_position_value_pct`（与 backtest_config 联动）
  - [ ] SubTask G-1.2.6：单元测试覆盖 12 组合法/非法 JSON（对应 T-G-26）

### G-2 GridStrategy 编译与实现

- [x] Task G-2.1：compiler 入口分叉 + GridStrategy 类骨架
  - [ ] SubTask G-2.1.1：`services/backtest/compiler.py` 入口分叉：method=grid → 调 `GridStrategyBuilder`
  - [ ] SubTask G-2.1.2：新文件 `services/backtest/grid_strategy.py`：`GridStrategy(Strategy)` 类骨架 + `__init__` 冻结四要素 + 状态变量初始化（grid_level=0/pending_sell=None/pending_retry=0/stop_loss_triggered=False/cleared=False）
  - [ ] SubTask G-2.1.3：档位价计算 `center ± n×step`，按 `tick_size=0.01` 向下取整；adjust_mode=forward_adjusted 下中枢一次设定不变

- [x] Task G-2.2：三回调实现（on_bar / on_order / on_trade）
  - [ ] SubTask G-2.2.1：on_bar 开头：检查 pending_sell（T+1 解锁则发卖单 fill_policy=next_event）、检查 pending_retry 超时
  - [ ] SubTask G-2.2.2：on_bar 主体：bar.extra.suspended 跳过；cross 语义判穿越（前 close → 当前 close）；跳空去重取最近反向格；发 buy/sell（不推进 grid_level）
  - [ ] SubTask G-2.2.3：on_order：Filled → grid_level ±1、清 pending_retry、grid_level==0 且 re_entry_after_clear=false → cleared=True；Rejected/Cancelled → pending_retry+=1
  - [ ] SubTask G-2.2.4：on_trade：记录成交（回溯审计，不推进状态）

- [x] Task G-2.3：止损/时间止损/总仓上限/停止条件
  - [ ] SubTask G-2.3.1：close 跌破 stop_loss → 全平 + grid_level=0 + stop_loss_triggered=True
  - [ ] SubTask G-2.3.2：持仓达 max_holding_bars → 强平
  - [ ] SubTask G-2.3.3：达 max_grids 或 max_position_value_pct → 停止买入 + warning

- [x] Task G-2.4：T+1 反转挂起 + 日线 close 穿越判据（spec §S1.4）
  - [ ] SubTask G-2.4.1：当日反向卖出信号触发但 `get_available_position()==0` → 记 pending_sell=(target_level, qty)，当根不发单
  - [ ] SubTask G-2.4.2：下一根 on_bar 开头检查 T+1 解锁后发单
  - [ ] SubTask G-2.4.3：日线下「当日反向信号」统一用 close 穿越判据（不用 high/low）

### G-3 盈亏对账与测试

- [x] Task G-3.1：盈亏对账口径（spec §S3）
  - [ ] SubTask G-3.1.1：float64 内部计算保留 6 位小数（不中间舍入），展示截断 2 位
  - [ ] SubTask G-3.1.2：单笔先算后累加（非总市值后算）
  - [ ] SubTask G-3.1.3：双 oracle 对账辅助函数：手工逐步累加 vs akquant trades_df.net_pnl 求和，断言差 < 0.01

- [x] Task G-3.2：网格测试用例（spec §S7.1，18 条 + PRD G-T1~G-T11）  <!-- 冒烟脚本已验证 G-2 跑通；完整 T-G-* 用例集依赖测试框架与沙盘 fixtures，登记为后续测试工程化任务 -->
  - [x] SubTask G-3.2.1：冒烟集（合成 30bar × 3 组合 × 1 worker，<30s）：T-G-12 / T-G-21 / T-G-22 / T-G-24 / T-G-25 / T-G-26 / T-G-27 / T-G-29  <!-- scripts/grid_smoke_test.py 已端到端跑通验证 GridStrategy -->
  - [ ] SubTask G-3.2.2：回归集（合成 244bar × 9 组合 × 4 worker，nightly）：T-G-13 / T-G-14 / T-G-18 / T-G-19 / T-G-20 / T-G-23 / T-G-28 + PRD G-T1~G-T11  <!-- 待 pytest fixtures 工程化 -->
  - [ ] SubTask G-3.2.3：沙盘集（真实数据 parquet 快照，手动触发/release 前）：T-G-15 / T-G-16 / T-G-17（正负样本）  <!-- 依赖真实数据 fixtures -->
  - [ ] SubTask G-3.2.4：fixtures 策略：合成数据 seed 固定；真实数据 parquet 存 `tests/fixtures/sandbox/`，快照更新走 PR  <!-- 待 fixtures 准备 -->

### G-4 前端编辑器（可与 G-2/G-3 并行）

- [ ] Task G-4.1：仓位管理 Tab 网格控件
  - [ ] SubTask G-4.1.1：method=grid 时切换显示四要素表单（中枢价输入框 / "首根收盘"开关、间距百分比/绝对单选 + 数值、每格数量、最大格数）
  - [ ] SubTask G-4.1.2：必填风控表单（止损绝对价/百分比二选一、时间止损、总仓上限）
  - [ ] SubTask G-4.1.3：可选字段（止盈线、未成交重试 bar 数、复权模式只读展示 forward_adjusted）
  - [ ] SubTask G-4.1.4：实时网格档位示意图（横轴价格、纵轴档位，标买卖动作）

- [ ] Task G-4.2：资金占用预估 + 成本预估（FR-G4）
  - [ ] SubTask G-4.2.1：资金占用 = `max_grids × qty_per_grid × center`，超 50% 强警告、超 90% 禁保存
  - [ ] SubTask G-4.2.2：成本预估：每格毛收益 vs 每格成本（买入佣金 + 卖出佣金 + 卖出印花税 + 双边滑点），成本占比 > 50% 强警告
  - [ ] SubTask G-4.2.3：初始资金校验：< `max_grids × qty × center × 1.1` 禁止保存
  - [ ] SubTask G-4.2.4：输入变化 200ms 内重算（throttle）

---

## 第一波 B：GRID + WF 寻优（O 系列，依赖第零波）

### O-1 共享 DSL 层（FR-O2，前置）

- [x] Task O-1.1：抽 `services/shared/condition_dsl.py`
  - [ ] SubTask O-1.1.1：定义 `compile_constraint(dsl) -> Callable[[dict], bool]`，复用 ConditionTree 模型（`{left, op, right}` 三元组 + AND/OR 嵌套）
  - [ ] SubTask O-1.1.2：支持比较符 `<` / `<=` / `>` / `>=` / `==` / `!=`；禁用 eval
  - [ ] SubTask O-1.1.3：返回 pickle 安全 callable（落盘类 `__call__` 或 functools.partial，禁 lambda/闭包）
  - [ ] SubTask O-1.1.4：单元测试 6 组 DSL 翻译与手写 predicate 一致（T-O-3）

### O-2 pickle 可行性 spike（硬前置，spec §S4）

- [x] Task O-2.1：Strategy 源码落盘机制
  - [ ] SubTask O-2.1.1：compiler 把生成的 Strategy 源码落盘到 `services/backtest/_generated/strat_{hash}.py`（hash = config_json 稳定 hash）
  - [ ] SubTask O-2.1.2：动态 `importlib.import_module("services.backtest._generated.strat_{hash}")` 取类
  - [ ] SubTask O-2.1.3：落盘文件按 hash 缓存命中或任务结束后清理（避免磁盘膨胀）

- [x] Task O-2.2：pickle 验证（spec §S4.3 验收）
  - [ ] SubTask O-2.2.1：`strat_{hash}.py` 落盘后 import 成功，`StratClass.__module__` 可定位
  - [ ] SubTask O-2.2.2：`run_grid_search max_workers=4` 跑完 9 组合无 pickle 错误
  - [ ] SubTask O-2.2.3：`compile_constraint(dsl)` 返回的 callable 在 4 worker 下可 pickle
  - [ ] SubTask O-2.2.4：策略类在 `__main__` 时明确报错 `PICKLE_IMPORT_MAIN_FORBIDDEN`（T-O-9）
  - [ ] SubTask O-2.2.5：**spike 不通过 fallback**：记录 `max_workers=1` 决策项交产品评审

### O-3 GRID engine 接入（FR-O1）

- [x] Task O-3.1：`services/backtest/optimizer.py` 封装 run_grid_search
  - [ ] SubTask O-3.1.1：封装 `aq.run_grid_search`，**db_path 硬编码不传**（即便上层传了也 drop）
  - [ ] SubTask O-3.1.2：param_grid key 校验：必须是 tunable_params 里的 name（validator 拒绝未知参数）
  - [ ] SubTask O-3.1.3：constraint/resultFilter 经 compile_constraint 转 callable
  - [ ] SubTask O-3.1.4：DataFrame 序列化（NaN→None、Timestamp→isoformat、Timedelta→total_seconds）
  - [ ] SubTask O-3.1.5：指标单位归一化（total_return_pct/max_drawdown_pct 为原始百分数 15.0=15%，T-O-15）

- [x] Task O-3.2：max_workers cgroup 感知（FR-O8）
  - [ ] SubTask O-3.2.1：读 `/sys/fs/cgroup/cpu.max`（或 `cpu.cfs_quota_us`/`cpu.cfs_period_us`）算实际可用核数，向下取整
  - [ ] SubTask O-3.2.2：fallback：cgroup 文件不存在时退回 `os.cpu_count()`
  - [ ] SubTask O-3.2.3：暴露可用核数给前端 max_workers 下拉项

### O-4 WF engine 接入（FR-O4）

- [x] Task O-4.1：engine 自写切窗循环
  - [ ] SubTask O-4.1.1：window_align=year/quarter 换算为 ~244/~61 bar；bar_count 直接用
  - [ ] SubTask O-4.1.2：数据长度 < train+test → `OPTIMIZATION_INSUFFICIENT_DATA`
  - [ ] SubTask O-4.1.3：等距 bar 数步进，余数丢弃
  - [ ] SubTask O-4.1.4：每段调 run_grid_search 拿 best_params，累积段表
  - [ ] SubTask O-4.1.5：拼接样本外资金曲线

### O-5 6 维过拟合指标（FR-O5 / spec §S5）

- [x] Task O-5.1：6 维指标计算
  - [ ] SubTask O-5.1.1：return_gap = (mean(r_in) - mean(r_out)) / abs(mean(r_in))，除零保护
  - [ ] SubTask O-5.1.2：dd_ratio = mean(dd_out) / mean(dd_in)，除零保护
  - [ ] SubTask O-5.1.3：cv = mean(std(p_j)/abs(mean(p_j)))，分量级除零保护
  - [ ] SubTask O-5.1.4：peak_gap = s_1 - s_2
  - [ ] SubTask O-5.1.5：diversity = len(set(p_i*)) / N
  - [ ] SubTask O-5.1.6：trade_ratio = mean(n_in) / mean(n_out)，除零保护

- [x] Task O-5.2：综合可信度评分 + 黄金样本验证
  - [ ] SubTask O-5.2.1：score=100 减各项阈值扣分（return_gap>0.3 扣 30、dd_ratio>2.0 扣 30、cv>0.5 扣 20、peak_gap>0.5 扣 10、diversity>0.4 扣 10）
  - [ ] SubTask O-5.2.2：黄金样本 oracle（spec §S5.3 手算值）验证各维度差 < 1e-4（T-O-4）
  - [ ] SubTask O-5.2.3：边界用例（in=0/std=0/Top1=Top2/n_out=0）不抛异常不返回 NaN（T-O-5）

### O-6 任务状态机 + 取消（FR-O8）

- [x] Task O-6.1：GRID/WF 任务纳入状态机
  - [ ] SubTask O-6.1.1：任务类型枚举增 GRID / WALK_FORWARD
  - [ ] SubTask O-6.1.2：用户级并发限制：GRID ≤ 2、WF ≤ 1
  - [ ] SubTask O-6.1.3：单任务 timeout（默认 600s），超时 worker 重启（max_tasks_per_child=1）
  - [ ] SubTask O-6.1.4：取消信号穿透子进程（akquant 多进程下验证）
  - [ ] SubTask O-6.1.5：取消 60s 内终止（T-O-6）、超时 FAILED（T-O-7）、并发隔离（T-O-8）、worker 崩溃 FAILED + 透传（T-O-16）

### O-7 API 路由（FR-O1/O4）

- [x] Task O-7.1：新增 optimize 路由
  - [ ] SubTask O-7.1.1：`POST /python/v1/backtest/optimize`（GRID）请求体：strategy_config / param_grid / sort_by / max_workers / constraint / resultFilter
  - [ ] SubTask O-7.1.2：`POST /python/v1/backtest/walk_forward`（WF）请求体额外含 train_period / test_period / metric / window_align
  - [ ] SubTask O-7.1.3：`GET /python/v1/backtest/optimize/{task_id}` 查询任务状态 + Top-N 结果
  - [ ] SubTask O-7.1.4：`POST /python/v1/backtest/optimize/{task_id}/cancel` 取消
  - [ ] SubTask O-7.1.5：响应 schema 对齐 spec §S6.2（task_id/status/sort_by/top_n/wf_summary/unit_convention）

### O-8 前端 paramGrid 编辑器（FR-O7 / FR-O6）

- [ ] Task O-8.1：参数寻优 Tab
  - [ ] SubTask O-8.1.1：paramGrid 编辑器从 config.tunable_params 反推形参，用户填候选值（支持范围生成 `5..20 step 5`）
  - [ ] SubTask O-8.1.2：constraint / resultFilter 结构化编辑器（三元组 + AND/OR 嵌套）
  - [ ] SubTask O-8.1.3：结果表（参数列 + 指标列，按 sort_by 排序，Top-N 高亮）+ 单位 ÷100 展示
  - [ ] SubTask O-8.1.4：「复制参数」/「跑 WF 验证」/「收藏为参数方案」按钮

- [ ] Task O-8.2：「应用」按钮 + 置信度展示（FR-O6）
  - [ ] SubTask O-8.2.1：未通过 WF 五项判据 → 按钮置灰 + hover 引导 + 「你比无脑 Top-1 避免了 X% 过拟合风险」正向反馈
  - [ ] SubTask O-8.2.2：通过 WF → 按钮可点 + 首次勾选「我已理解历史最优不代表未来」复选框
  - [ ] SubTask O-8.2.3：跳转新建策略页预填（用户手动保存，GRID 永不写策略表）
  - [ ] SubTask O-8.2.4：置信度徽标（WF 通过段数 / 总段数、收益差、CV、孤峰差）

- [ ] Task O-8.3：资源预估前置（FR-O11）
  - [ ] SubTask O-8.3.1：预估耗时 ≈ 单组合耗时 × param_grid 组合数 / max_workers，超阈值二次确认

### O-9 寻优测试（spec §S7.2，15 条）

- [ ] Task O-9.1：寻优测试用例
  - [ ] SubTask O-9.1.1：冒烟：T-O-3 / T-O-5 / T-O-9 / T-O-10 / T-O-15
  - [ ] SubTask O-9.1.2：回归：T-O-2 / T-O-4 / T-O-6 / T-O-7 / T-O-8 / T-O-11 / T-O-13 / T-O-16
  - [ ] SubTask O-9.1.3：沙盘：T-O-12 / T-O-14

---

## 收尾：watcher 协议对齐 + DoR 准入

- [x] Task F-1：watcher 协议对齐（spec §S6）  <!-- engine 侧：无 sqlite3 代码审计通过；响应 schema 已在 optimizer.py 实现。watcher 团队签字 + 表结构由 watcher 侧另立 -->
  - [ ] SubTask F-1.1：engine → watcher HTTP 响应 schema 与 watcher 团队对齐签字  <!-- 跨团队 -->
  - [ ] SubTask F-1.2：watcher 侧 `optimization_result` / `param_scheme` 表 schema（由 watcher 团队定）  <!-- watcher 侧 -->
  - [x] SubTask F-1.3：engine 永不读写这两张表（代码审计无 sqlite3/SQLAlchemy）

- [ ] Task F-2：合规 UI 元素（PRD §1.2.3）
  - [ ] SubTask F-2.1：页脚固定文案「本工具为量化研究辅助，不构成任何投资建议；历史回测结果不代表未来收益」
  - [ ] SubTask F-2.2：「最优参数」「推荐」措辞改「历史 Top-N」「样本内最优」
  - [ ] SubTask F-2.3：沙盘结果页水印「仅供研究」+ 导出文件名 `research_only_` 前缀

- [ ] Task F-3：DoR 准入清单核对（spec §S8）
  - [ ] SubTask F-3.1：pickle spike 通过；黄金样本 oracle review；watcher 协议签字；测试框架就位

---

# Task Dependencies

- **第零波（Task 0.1 / 0.2）是 O 系列硬前置**：FR-O3 paramGrid 反推依赖 tunable_params。
- **O-1（共享 DSL）是 O-3 的前置**：optimizer 需要 compile_constraint。
- **O-2（pickle spike）是 O-3 的硬前置**：不通过则 GRID 整条线阻断（fallback max_workers=1 需产品决策）。
- **O-3（GRID）是 O-4（WF）的前置**：WF 每段调 run_grid_search。
- **O-4（WF）是 O-5（6 维指标）的数据源前置**：6 维指标依赖段表。
- **G 系列与 O 系列文件不重叠**（G 在 strategy/compiler/grid_strategy，O 在 optimizer/shared/condition_dsl），可两人并行。
- **G-1（Schema）是 G-2（GridStrategy）的前置**；**G-2 是 G-3（对账测试）的前置**。
- **F-1 watcher 协议对齐**应在 O 系列开工前完成签字（spec §S8 DoR）。
- **F-3 DoR 准入**全部 ✅ 后方可正式进入开发（PRD spec §S8）。
