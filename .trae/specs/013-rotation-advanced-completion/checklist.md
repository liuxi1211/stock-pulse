# 轮动进阶收口（P2-9 + 三项遗留）验收 Checklist

> 对应 spec `013-rotation-advanced-completion`。每个 checkpoint 满足后勾选；不满足则在 tasks.md 新增修复任务。
>
> **验收状态**：代码层全部实现并通过测试/编译（2026-07-16）。仅「跨年回测实际数据联调」「watcher 启动后 DataInit 三步骤落库」「端到端真实回测联调」需启动服务+Tushare 环境跑一次（代码已就绪，留待集成环境验收）。

## P2-9 分批调仓 + 冲击成本

- [x] `ExecutionConfig` 模型存在，`split_days ∈ [1,5]` 默认 1、`impact_cost_bps ≥ 0` 默认 None；Pydantic `extra="forbid"`。（models.py:426）
- [x] `RebalanceModel.execution: Optional[ExecutionConfig]` 字段存在；缺省 None 等价现状。（models.py:491）
- [x] 错误码 `INVALID_EXECUTION_CONFIG` / `EXECUTION_REQUIRES_REBALANCE` 已加到 `errors.py`。（errors.py:202-209）
- [x] validator：非轮动范式（has_rebalance=False）配 execution 报 `EXECUTION_REQUIRES_REBALANCE`。（validator.py `_validate_rebalance_execution`）
- [x] `compute_impact_price` 内核：买入加价 / 卖出折价 / participation 封顶 1.0 / 除零保护 / bps=None 返回原价。（compiler.py:1401，6 单测全过）
- [x] `SplitState` 状态机：next_increment 按 total_days 切分；耗尽 exhausted=True；interrupt 作废。（compiler.py:1443，4 单测全过）
- [x] `on_daily_rebalance`：split_days=1 行为不变；split_days>1 触发日存 _pending_split + 执行首份；非触发日有 pending 时执行增量。（compiler.py on_daily_rebalance 改造，e2e 测试 9 passed）
- [x] 新触发日撞未完成分批 → 作废重来 + 日志 + splits_interrupted +=1。（_bump_exec("interrupted") + logger.info）
- [x] 分批日某标的当日 is_limit_up="1" → 该份增量跳过该标的。（_filter_limit_up_today，trade_date_str 格式 `%Y-%m-%d` 与 data_adapter 一致）
- [x] 冲击成本经 price_map 注入（akquant 0.2.47 order_target_weights 支持 price_map，已核校签名）；低成交量标的冲击更高。（build_impact_price_map）
- [x] `result_serializer` 输出 `execution_diagnosis`（split_days / splits_completed / splits_interrupted / total_impact_cost / avg_participation）。（result_serializer.py:183, _extract_execution_diagnosis）
- [x] 前端 editor Rebalance 层有 split_days / impact_cost_bps 控件，collect/refill 透传 execution。（editor.html + strategy-editor.js）
- [x] e2e 测试：split_days=3 order_target_weights 调 3 次每次 plan/3；impact_cost_bps=10 price_map 非空（S1→10.01）。（test_execution_e2e.py 9 passed）

## 遗留#1 watcher 元数据下发

- [x] `TushareApiEnum` 含 NAMECHANGE / SUSPEND_D / STK_LIMIT；`InitStep` 含对应三项。（TushareApiEnum.java / InitStep.java）
- [x] 三表 DDL（stock_namechange / stock_suspend_d / stock_stk_limit）在 schema-mysql.sql / schema-sqlite.sql / DataInitServiceImpl Map 三处同步。
- [x] namechange / suspend_d / stk_limit 三套 DTO/DO/Mapper/Service(Impl) 完整（参照 P0-0 范例）。共 18 个新文件。
- [x] Mapper 含 point-in-time / 区间批量查询接口（selectNameAt / existsSuspendedAt / selectByTsCodesAndRange）。
- [x] TushareClient 扩展三接口调用（分页：namechange 5000/页、suspend_d/stk_limit 10000/页）。
- [x] 三 Service 全量初始化（幂等 delete-then-insert）+ 每日增量定时（cron 16:30/16:35/16:40）。
- [x] application.yml 三接口限流（namechange/suspend_d 200/min，stk_limit 500/min）。
- [x] `buildKlineData` 循环外批量预查建索引（namechangeMap / suspendSet / limitMap / listDateMap），不逐 bar 查表。
- [x] `buildKlineData` 每根 bar 注入 5 字段：is_st（按 namechange 该日生效 name 含 ST）/ is_suspended / is_limit_up / is_limit_down（按 stk_limit 精确判定）/ list_date（stock_basic 静态）。
- [ ] ST 戴帽摘帽日切换正确（跨年回测实际数据验证）— *需集成环境跑一次真实回测*
- [x] 涨停精确判定（close >= up_limit，含一字涨停 close==up_limit，代码 `close >= up`）。
- [x] 缺 stk_limit 数据日 bar 不下发 is_limit_*（`if (lim != null)` 守卫，缺则跳过 → engine 静默跳过 + warning）。
- [ ] engine 联调：exclude_st=true 回测日志「剔除 ST: N 只」；exclude_limit_up 涨停拒买生效 — *需 watcher 下发数据后跑真实回测*

## 遗留#2 前端结果页展示

- [x] 回测结果页展示 `rebalance_diagnosis`（实际成交/选出/拒单/实际仓位）。（backtest-report.js renderDiagnosis）
- [x] actually_bought < selected_count 时诊断卡片高亮警示。（.bt-diag-warn CSS + ⚠ 文案）
- [x] 展示 `effective_config.warmup_period`（source=user_override 时标注「用户设置」）。
- [x] 展示 `annual_turnover_ratio`（metrics 指标卡）。
- [x] **附带修复**：watcher 后端 BacktestReportVO/QuantBacktestReportDO/BacktestServiceImpl 原未透传 rebalance_diagnosis / effective_config，已补齐落库 JSON 列 + toReportVO 透传（否则前端拿不到数据）。

## 遗留#3 行业归属 point-in-time

- [x] `SwIndustryService.getL1IndustriesPit(tsCodes, startDate, endDate)` 接口存在，返回 Map<tsCode, Map<tradeDate, indexCode>>。
- [x] Impl：一次性查区间 sw_industry_member 全历史，按 update_date forward-fill（双指针推进）。
- [x] `buildKlineData` 用 getL1IndustriesPit 替换 getLatestL1Industries。
- [ ] 跨年回测：换行业标的按当时归属（非全程最新归属）— *需集成环境跑一次真实跨年回测*
- [x] 早于最早 update_date 的标的：bar 不下发 sw_industry_l1（`perCodePit.get(td) == null` → 不 put），engine 行业暴露静默跳过 + warning。

## 全局 / 回归

- [x] engine 全量测试 PASS：**263 passed**（`pytest tests/ -v`，conda stock 环境 python）。
- [x] watcher 编译通过：`mvn compile -q -DskipTests` exit 0（DataInit 三步骤落库需启动服务+真实 Tushare 验证）。
- [x] engine 不触库硬约束未违反（无 sqlite3/sqlalchemy 新增；test_no_db.py 2 passed；grep services/backtest 无 import）。
- [x] 旧策略 JSON（无 execution 字段）回测行为不变（execution 缺省 None 等价现状；ExecutionConfig 默认 split_days=1；前端默认值 1/空不写 execution）。
- [ ] 端到端联调：execution + 元数据 + PIT + 前端展示全链路通 — *需启动 watcher+engine+Tushare 跑一次真实回测*

## 待集成环境验收的 3 项

以下 3 项代码已就绪，但需要启动 watcher（含 Tushare token）+ engine 跑真实回测才能验证（本机无 Tushare 凭据/真实数据库环境）：
1. ST 戴帽摘帽跨年切换（依赖 namechange 真实数据）。
2. engine exclude_st/exclude_limit_up 联调日志（依赖 watcher 下发数据）。
3. 跨年行业 PIT 归属（依赖 sw_industry_member 真实数据）。

建议在集成环境执行 Task 17 Step 3 的端到端联调脚本完成这 3 项验收。
