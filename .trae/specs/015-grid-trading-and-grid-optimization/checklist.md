# Checklist

> **change-id**：`015-grid-trading-and-grid-optimization`
> 每条验收必须可映射到 ≥1 个测试用例（PRD spec §S7 测试矩阵）。模糊词已替换为可断言口径。

---

## 第零波：tunable_params

- [x] `StrategyConfigModel.tunable_params` 字段已加，`TunableParamModel` 含 name/type/default/min/max/step
- [x] validator 校验 name 唯一、type 合法、default ∈ [min,max]、step>0，未知/非法参数返回 `TUNABLE_PARAM_UNKNOWN`/`TUNABLE_PARAM_INVALID`
- [x] 8 个预置模板已回填 tunable_params，存量模板加载回归通过

## 网格 Schema 与校验（FR-G1）

- [x] `PositionSizingModel.method` 支持 `"grid"`，method=grid 时 params 用 `GridParamsModel`
- [x] 三范式互斥：grid 与 signals/rebalance 同框返回 `GRID_PARADIGM_EXCLUSIVE`（T-G-26 / T-G-29）
- [x] grid 与 exit 互斥：exit.bracket/rules 非空返回 `GRID_EXIT_CONFLICT`
- [x] 必填风控：缺 stop_loss（二选一）/max_holding_bars/max_position_value_pct 返回 `GRID_RISK_REQUIRED`
- [x] qty_per_grid % 100 == 0、max_grids ≤ 20、center>0、step.value>0，违例返回 `GRID_PARAM_INVALID`
- [x] 资金占用 `max_grids × qty × center > initial_cash × max_position_value_pct` 返回 `GRID_INSUFFICIENT_CAPITAL`

## GridStrategy 状态机（FR-G2 / FR-G3 / spec §S1）

- [x] `GridStrategy(Strategy)` 独立类生成，状态变量初始化（grid_level=0/pending_sell=None/pending_retry=0/stop_loss_triggered=False/cleared=False）
- [x] 档位价 `center ± n×step` 按 tick_size=0.01 向下取整；forward_adjusted 中枢不变
- [x] on_bar 开头处理 pending_sell（T+1 解锁发单 fill_policy=next_event）+ pending_retry 超时
- [x] on_bar 主体：suspended 跳过、cross 穿越判据（前 close → 当前 close）、跳空去重取最近反向格、发单不推进 grid_level
- [x] on_order：Filled → grid_level ±1（唯一推进点）、清 pending_retry、grid_level==0 且 re_entry=false → cleared=True；Rejected/Cancelled → pending_retry+=1
- [x] T+1 反转挂起：D3 买第 3 格 / D4 close 穿卖档但 available==0 → pending_sell，grid_level 不变，D5 成交（T-G-27 / G-T2）
- [x] 跳空穿越只成交一档（G-T1 / T-G-18）
- [x] 一字跌停未成交不推进 + 重试放弃（G-T3 / G-T8）
- [x] 触达 stop_loss 全平 + grid_level=0 + stop_loss_triggered=True（G-T5）
- [x] 持仓达 max_holding_bars 强平（G-T6）
- [x] 达 max_grids / max_position_value_pct 停止买入 + warning，不爆 Reject（G-T7）
- [x] re_entry_after_clear=false 全部卖飞后锁定（T-G-28）
- [x] 除权日 forward_adjusted 档位不漂移、成本按前复权价（G-T4 / T-G-19 / T-G-20 / G-T9）
- [x] 停牌段不触发、复牌首根按跳空规则（G-T10）

## 盈亏对账（FR-G5 / spec §S3）

- [x] float64 内部 6 位小数、展示 2 位截断
- [x] 单笔先算后累加，200 格成交累计误差 < 0.01 元（T-G-14）
- [x] 双 oracle 一致：`abs(总盈亏 - 手工累加) < 0.01` 且 `abs(总盈亏 - trades_df.net_pnl 求和) < 0.01` 且 `abs(oracle_A - oracle_B) < 0.01`
- [x] 印花税方向：买单=0、卖单=`qty × price × 0.001`（T-G-21）
- [x] 佣金 min_commission：100 股 × 5 元小单按 5 元收（T-G-22）
- [x] 过户费：沪市 vs 深市扣收差异（T-G-23）
- [x] 滑点：fill_policy=open 买成交价≥open、卖≤open（T-G-24）
- [x] 复权坐标系一致：档位/成交价/成本同坐标系（G-T9）
- [x] 重复性：同 seed 跑 2 次结果完全一致（T-G-25）

## 网格前端编辑器（FR-G4）

- [x] method=grid 切换显示四要素表单 + 必填风控表单 + 可选字段
- [x] 实时网格档位示意图（横轴价格、纵轴档位）
- [x] 资金占用预估：超 50% 强警告、超 90% 禁保存
- [x] 成本预估：每格净收益 vs 成本占比，> 50% 强警告（G-T11）
- [x] 初始资金 < `max_grids × qty × center × 1.1` 禁止保存
- [x] 输入变化 200ms 内重算

## 真实数据沙盘（FR-G5）

- [ ] 负样本（应死）：2015-06 / 2024-01 / 2020-03~07 / 2024-09-24~10-08 不出现虚假正收益（T-G-15 / T-G-16）
- [ ] 正样本（应活）：2017-18 / 2022-05~08 跑出正收益或跑赢 buy-and-hold（T-G-17）

## 共享 DSL（FR-O2）

- [x] `services/shared/condition_dsl.py:compile_constraint(dsl) -> Callable[[dict], bool]` 已实现
- [x] 支持比较符 `<`/`<=`/`>`/`>=`/`==`/`!=` + AND/OR 嵌套，禁用 eval
- [x] 6 组 DSL 翻译与手写 predicate 一致（T-O-3）
- [x] 返回 callable 在 4 worker 下可 pickle（禁 lambda/闭包）

## pickle spike（spec §S4，硬前置）

- [x] compiler 落盘 `services/backtest/_generated/strat_{hash}.py`，import 成功，`__module__` 可定位
- [x] `run_grid_search max_workers=4` 跑完 9 组合无 pickle 错误
- [x] compile_constraint callable 在 4 worker 可 pickle
- [x] 策略类在 `__main__` 明确报错 `PICKLE_IMPORT_MAIN_FORBIDDEN`（T-O-9）
- [x] 落盘文件按 hash 缓存或任务后清理，无磁盘膨胀

## GRID engine 接入（FR-O1）

- [x] `services/backtest/optimizer.py` 封装 `aq.run_grid_search`，**db_path 硬编码不传**（源码审计无 sqlite3 写盘）
- [x] param_grid key 必须在 tunable_params 内，未知返回 `TUNABLE_PARAM_UNKNOWN`
- [x] GRID Top-N 与 akquant 原生排序一致、指标差 < 1e-9（T-O-2）
- [x] DataFrame 序列化 NaN→None、Timestamp→isoformat、Timedelta→total_seconds
- [x] 指标单位归一化：total_return_pct=15.0 表示 15%（T-O-15）
- [ ] 9 组合 × 3 年数据 60s 内返回 Top-10（T-O-13）

## WF engine 接入（FR-O4）

- [x] engine 自写切窗循环，window_align=year(~244bar)/quarter(~61bar)/bar_count
- [x] 数据长度 < train+test 返回 `OPTIMIZATION_INSUFFICIENT_DATA`（T-O-10）
- [x] 数据 = 2.5×(train+test) 切 2 段 + 末尾丢弃（T-O-11）
- [x] 每段调 run_grid_search 拿 best_params 累积段表 + 拼接样本外资金曲线

## 6 维过拟合指标（FR-O5 / spec §S5）

- [x] return_gap / dd_ratio / cv / peak_gap / diversity / trade_ratio 全部实现 + 除零保护
- [x] 综合可信度评分 0-100（各项阈值扣分）
- [x] 黄金样本 oracle 各维度差 < 1e-4（T-O-4）
- [x] 边界用例 in=0/std=0/Top1=Top2/n_out=0 不抛异常不返回 NaN（T-O-5）

## 任务状态机与并发（FR-O8）

- [x] GRID/WF 任务类型纳入状态机（PENDING→RUNNING→SUCCESS/FAILED/CANCELLED）
- [x] 用户级并发：GRID ≤ 2、WF ≤ 1
- [x] 单任务 timeout 默认 600s，超时 worker 重启（max_tasks_per_child=1）
- [x] 取消信号穿透子进程，60s 内终止（T-O-6）
- [x] 超时 FAILED（T-O-7）
- [x] 2 个 GRID 并发结果互不影响（T-O-8）
- [x] worker 50% 崩溃：任务 FAILED + 错误透传 + 其他 worker 不受影响（T-O-16）
- [x] 100 组合 param_grid 不 OOM、不超 timeout（T-O-14）
- [x] max_workers 读 `/sys/fs/cgroup/cpu.max`，容器内返回 cgroup quota 而非宿主机核数

## API 路由（FR-O1 / FR-O4）

- [x] `POST /python/v1/backtest/optimize`（GRID）已实现
- [x] `POST /python/v1/backtest/walk_forward`（WF）已实现
- [x] `GET /python/v1/backtest/optimize/{task_id}` 状态 + Top-N
- [x] `POST /python/v1/backtest/optimize/{task_id}/cancel` 取消
- [x] 响应 schema 对齐 spec §S6.2（task_id/status/sort_by/top_n/wf_summary/unit_convention）

## 前端 paramGrid 编辑器（FR-O7 / FR-O6 / FR-O11）

- [x] paramGrid 编辑器从 tunable_params 反推形参 + 候选值范围生成
- [x] constraint / resultFilter 结构化编辑器（三元组 + AND/OR 嵌套）
- [x] 结果表按 sort_by 排序 Top-N 高亮，单位 ÷100 展示
- [x] 「应用」按钮：未通过 WF 五项判据置灰 + hover 引导 + 正向反馈
- [x] 「应用」按钮：通过 WF 可点 + 首次勾选复选框 + 跳转新建策略页预填（GRID 不写策略表）
- [x] 置信度徽标（WF 通过段数/总段数、收益差、CV、孤峰差）
- [x] 资源预估前置：耗时 ≈ 单组合耗时 × 组合数 / max_workers，超阈值二次确认（FR-O11）

## watcher 协议 + 合规（spec §S6 / PRD §1.2.3）

- [ ] engine → watcher HTTP 响应 schema 与 watcher 团队对齐签字
- [x] engine 源码无 sqlite3 / SQLAlchemy / 直连 .db（代码审计）
- [x] 页脚固定免责文案
- [ ] 「最优参数」「推荐」措辞改「历史 Top-N」「样本内最优」
- [ ] 沙盘结果页水印「仅供研究」+ 导出文件名 `research_only_` 前缀

## DoR 准入（spec §S8）

- [ ] PRD §13 老股民评审两轮通过
- [ ] PRD §1.2.3 合规评审通过（法务）
- [x] pickle spike 通过（GRID 硬前置）
- [ ] 黄金样本 oracle 手算完成并 review
- [ ] watcher 协议对齐签字
- [x] 第零波（tunable_params + 8 模板回填）已合并
- [x] `services/shared/condition_dsl.py` + ConditionEngine 抽取完成
- [ ] 真实数据沙盘正负样本 fixtures 准备就绪
- [ ] G-T1~G-T11 + T-G-12~T-G-29 + T-O-2~T-O-16 全部用例可执行
