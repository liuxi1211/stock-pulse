# Tasks

> **change-id**：014-preset-strategy-templates
> **依赖**：Task 0 前置；Task 1-8 依赖 Task 0；Task 9 依赖全部。
> **并行**：Task 1-8 相互独立，可并行（Task 0 完成后）。

- [x] Task 0: 同步 `constants.py` 因子白名单与 `factors.default.json` 对齐（前置）
  - 文件：`stock-engine/services/strategy/constants.py`
  - `TECHNICAL_FACTOR_KEYS` 补齐 18 个：`WMA, DEMA, TEMA, TRIMA, KAMA, T3, MAMA, ROC, MOM, APO, PPO, TRIX, NATR, TRANGE, STDDEV, AD, ADOSC, OPEN`
  - `FUNDAMENTAL_FACTOR_KEYS` 补齐 9 个 + 清理 3 个僵尸：
    - 补齐：`PS_TTM, DV_RATIO, CIRC_MV, ROA_TTM, NETPROFIT_MARGIN, REVENUE_YOY, PROFIT_YOY, EPS_YOY, DEBT_TO_ASSETS`
    - 清理：删除 `REVENUE_GROWTH`（→`REVENUE_YOY`）、`NET_PROFIT_GROWTH`（→`PROFIT_YOY`）、`NORTHBOUND_NET_INFLOW`（无定义）
  - `MULTI_OUTPUT_FACTORS` 增补 `"MAMA": ["MAMA", "FAMA"]`
  - 验证：`pytest stock-engine/tests/services/strategy/test_validator.py` + `test_models.py` 全绿（核对无测试用例引用 3 个僵尸因子；若有同步改名）
  - 验证：新增一个测试用例覆盖 `DV_RATIO`/`ROC`/`DEBT_TO_ASSETS` 通过 validator

- [x] Task 1: 创建「红利低波」轮动模板 `dividend_low_volatility.json`（FUNDAMENTAL）
  - 范式：portfolio（rebalance）
  - universe: `csi300`
  - factor: `composite` weights `{"DV_RATIO": 0.6, "NATR": -0.4}`（股息率越高越好 + 波动越低越好）
  - filter.conditions: AND[
      `DV_RATIO > 3`（股息率>3%）,
      `DEBT_TO_ASSETS < 60`（财务安全）,
      `TOTAL_MV > 10000000000`（>100 亿，流动性）,
      `PROFIT_YOY > 0`（盈利正增长，避免价值陷阱）
    ]
  - filter 静态：exclude_st/suspended/limit_up=true / min_list_days=750（3 年以上）
  - portfolio: top_n=20 / max_weight_per_symbol=0.08 / cash_reserve_pct=0.05 / buffer_n=2
  - rebalance: frequency=quarterly / trigger=first / weight_mode=equal / min_holding_bars=60 / reject_limit_up_on_buy=true / reject_limit_down_on_sell=true
  - exit.bracket: stop_loss_pct=0.12 / take_profit_pct=0.40
  - backtest_config: initial_cash=1000000 / broker_profile=cn_stock_miniqmt / t_plus_one=true / slippage={"type":"percent","value":0.001} / lot_size=100 / warmup_period=14（NATR 默认周期）/ history_depth=60 / volume_limit_pct=0.2
  - description 显式标"防御型策略，牛市跑输属正常，适合熊市/震荡市"
  - tags=["红利","低波","防御","Smart-Beta"]

- [x] Task 2: 创建「景气度盈利动量」轮动模板 `profit_momentum.json`（FUNDAMENTAL，替代静态高 ROE）
  - 范式：portfolio
  - universe: `csi500`
  - factor: `composite` weights `{"PROFIT_YOY": 0.5, "REVENUE_YOY": 0.3, "ROE_TTM": 0.2}`（盈利加速为核心 + 收入支撑 + ROE 质量把关）
  - filter.conditions: AND[
      `PROFIT_YOY > 20`（利润高增）,
      `REVENUE_YOY > 10`（收入同步增，排除卖资产/补贴造假）,
      `ROE_TTM > 8`（增长有 ROE 支撑，排除借债增长）,
      `PE_TTM > 0` & `PE_TTM < 60`（合理估值区间）,
      `DEBT_TO_ASSETS < 70`（财务健康）
    ]
  - filter 静态：exclude常规 / min_list_days=250
  - portfolio: top_n=20 / max_weight_per_symbol=0.08 / cash_reserve_pct=0.1
  - rebalance: frequency=monthly / trigger=first / weight_mode=equal（景气度变化快，月度跟踪）
  - exit.bracket: stop_loss_pct=0.10 / take_profit_pct=0.35
  - backtest_config: initial_cash=1000000 / slippage percent 0.002 / warmup_period=0 / history_depth=60 / volume_limit_pct=0.2 / 其余同 Task 1
  - description 标"A 股成长投资核心打法，赚景气度超预期的钱"
  - tags=["景气度","盈利动量","成长"]

- [x] Task 3: 创建「价值低估值（改良）」轮动模板 `value_low_pe_pb.json`（FUNDAMENTAL）
  - 范式：portfolio
  - universe: `csi300`
  - factor: `composite` weights `{"PE_TTM": -0.4, "PB": -0.3, "ROE_TTM": 0.3}`
  - filter.conditions: AND[
      `PE_TTM > 5`（**设下限避周期顶/亏损股**）& `PE_TTM < 30`,
      `PB > 0` & `PB < 3`,
      `ROE_TTM > 10`,
      `DEBT_TO_ASSETS < 70`,
      `PROFIT_YOY > 0`（盈利正增长，规避价值陷阱）
    ]
  - filter 静态：exclude常规 / min_list_days=500 / exclude_industries 可选填强周期（实现期看 schema 是否生效）
  - portfolio: top_n=30（**top30 分散**，降低噪音）/ max_weight_per_symbol=0.06 / cash_reserve_pct=0.05 / max_industry_exposure=0.25（**行业分散**，避免重仓银行地产）
  - rebalance: frequency=quarterly（**季度调仓**，价值慢变量）/ trigger=first / weight_mode=equal / min_holding_bars=60
  - exit.bracket: stop_loss_pct=0.10 / take_profit_pct=0.30
  - backtest_config: initial_cash=1000000 / slippage percent 0.002 / 其余同 Task 1
  - tags=["价值","低估值","Fama-French","改良版"]

- [x] Task 4: 创建「GARP 成长（改良）」轮动模板 `growth_garp.json`（FUNDAMENTAL）
  - 范式：portfolio
  - universe: `csi500`
  - factor: `composite` weights `{"PROFIT_YOY": 0.4, "REVENUE_YOY": 0.3, "PE_TTM": -0.3}`
  - filter.conditions: AND[
      `PROFIT_YOY > 20`,
      `REVENUE_YOY > 15`,
      `PE_TTM > 0` & `PE_TTM < 30`（**收紧到 30**，原 50 太宽松）,
      `ROE_TTM > 10`（增长必须有 ROE 支撑）,
      `TOTAL_MV > 10000000000`（**市值>100 亿**，规避微盘风险，2024 新国九条后必要）
    ]
  - filter 静态：exclude常规 / min_list_days=250
  - portfolio: top_n=20 / max_weight_per_symbol=0.08 / cash_reserve_pct=0.1
  - rebalance: frequency=monthly / trigger=first / weight_mode=equal
  - exit.bracket: stop_loss_pct=0.10 / take_profit_pct=0.35
  - backtest_config: initial_cash=1000000 / slippage percent 0.002 / 其余同 Task 1
  - description 标"彼得·林奇 GARP 流派，合理价格买成长"
  - tags=["成长","GARP","改良版"]

- [x] Task 5: 创建「多因子含动量（改良）」轮动模板 `multi_factor_momentum.json`（FUNDAMENTAL）
  - 范式：portfolio
  - universe: `csi500`
  - factor: `composite` weights `{"PE_TTM": -0.2, "ROE_TTM": 0.2, "PROFIT_YOY": 0.2, "ROC": 0.2, "NATR": -0.2}`（**5 因子分属价值/质量/成长/动量/波动五大类**，加 A 股稳定因子 ROC 动量）
  - filter.conditions: AND[
      `PE_TTM > 0` & `PE_TTM < 35`,
      `ROE_TTM > 10`,
      `PROFIT_YOY > 5`,
      `DEBT_TO_ASSETS < 65`
    ]
  - filter 静态：exclude常规 / min_list_days=500
  - portfolio: top_n=25 / max_weight_per_symbol=0.06 / cash_reserve_pct=0.05 / max_industry_exposure=0.25 / buffer_n=3
  - rebalance: frequency=quarterly（**季度调仓**，降换手）/ trigger=first / weight_mode=equal
  - exit.bracket: stop_loss_pct=0.10 / take_profit_pct=0.30
  - execution: split_days=2 / impact_cost_bps=10
  - backtest_config: initial_cash=2000000 / slippage percent 0.002 / warmup_period=14（NATR/ROC 需要历史）/ history_depth=60 / 其余同 Task 1
  - tags=["多���子","动量","改良版","黄金标准"]

- [x] Task 6: 创建「ETF 动量轮动」模板 `etf_momentum_rotation.json`（TECHNICAL，A 股主流玩法）⚠️ 关键：用 rebalance 范式
  - 范式：portfolio（**rebalance，不是 signals**，避 spec 009 资金争抢）
  - universe: `manual` stocks=`["510300.SH", "510500.SH", "159915.SZ", "512100.SH", "588000.SH", "510050.SH", "159949.SZ"]`（沪深300/中证500/创业板/中证1000/科创50/上证50/创业板50 共 7 只主流宽基 ETF）
  - factor: `composite` weights `{"ROC": 1.0}`（**动量打分**，ROC=20 日涨幅；若需单因子可用 single method）
    - 备选：factor method=`single` factor=`ROC` order=`desc`（更直观，但需确认 single+equal 是否合法——本 spec 统一用 composite）
  - filter.conditions: AND[
      `{factor:"CLOSE", transform:{type:"pct_change", window:20}} > 0`（**仅选正动量**，剔除下跌趋势标的；transformable:true 的 CLOSE 合法）
    ]
    - 注意：ROC 因子本身即动量，无需再 transform；此处 filter 用 CLOSE 的 pct_change 做正动量过滤是辅助
  - filter 静态：exclude_st=false / exclude_suspended=true（ETF 无 ST）/ exclude_limit_up=false（ETF 涨停少）
  - portfolio: top_n=2（**持有最强 2 只**）/ max_weight_per_symbol=0.5 / cash_reserve_pct=0.0
  - rebalance: frequency=weekly / trigger=first / weight_mode=equal / reject_limit_up_on_buy=true / reject_limit_down_on_sell=true
  - exit.bracket: stop_loss_pct=0.08 / take_profit_pct=0.30
  - backtest_config: initial_cash=100000 / broker_profile=cn_stock_miniqmt / t_plus_one=true / slippage={"type":"percent","value":0.001}（大盘 ETF 档）/ lot_size=100 / warmup_period=25（ROC/transform window=20 + 缓冲）/ history_depth=40 / volume_limit_pct=0.3（ETF 流动性好）
  - description 标"A 股最主流玩法之一，宽基 ETF 间动量轮动，吃每段主升浪，震荡市会有假信号"
  - tags=["ETF","动量","轮动","主流"]
  - ⚠️ 实现期验证：rebalance + universe.pool=manual 是否被 watcher 接受（validator 不限制，但 watcher BacktestServiceImpl 解析 universe 时需支持 manual+ETF 代码；若不支持则 fallback 到 csi300 但逻辑会变）

- [x] Task 7: 创建「双均线趋势（改良）」择时模板 `dual_ma_trend.json`（TECHNICAL）
  - 范式：single（signals）
  - universe: `manual` stocks=`["510300.SH"]`
  - signals.buy: AND[
      `{factor:"MA", params:{timeperiod:20}} cross_up {factor:"MA", params:{timeperiod:60}}`（**20/60 生命线**，替代 5/20 噪音大的组合）,
      `{factor:"ADX", params:{timeperiod:14}} > 25`（趋势强度确认）
    ]
  - signals.sell: `{factor:"MA", params:{timeperiod:20}} cross_down {factor:"MA", params:{timeperiod:60}}`
  - position_sizing: method=order_target_percent / target=0.95
  - exit.bracket: use_atr_stop=true / atr_period=14 / atr_multiplier=2.5 / stop_loss_pct=0.08（百分比兜底）
  - backtest_config: initial_cash=100000 / broker_profile=cn_stock_miniqmt / t_plus_one=true / slippage={"type":"percent","value":0.001} / lot_size=100 / warmup_period=65（MA60 + 缓冲）/ history_depth=80 / volume_limit_pct=0.2
  - description 标"经典趋势跟踪，20/60 日线为 A 股老手'生命线'，大行情利器，震荡市反复假信号是趋势策略天然代价"
  - tags=["均线","趋势","生命线","ADX过滤"]

- [x] Task 8: 创建「MACD 趋势（强化）」择时模板 `macd_trend_filtered.json`（TECHNICAL）
  - 范式：single
  - universe: `manual` stocks=`["510300.SH"]`
  - signals.buy: AND[
      `{factor:"MACD", params:{fastperiod:12, slowperiod:26, signalperiod:9}, output_index:0} cross_up {factor:"MACD", params:{fastperiod:12, slowperiod:26, signalperiod:9}, output_index:1}`（日线 DIF 上穿 DEA）,
      `{factor:"MACD", params:{fastperiod:12, slowperiod:26, signalperiod:9}, output_index:2} > 0`（柱状图转正）,
      `{factor:"RSI", params:{timeperiod:14}} < 70`（未超买）,
      `{factor:"MACD", params:{fastperiod:24, slowperiod:52, signalperiod:18}, output_index:2} > 0`（**用长周期 MACD(24/52/18) 近似周线趋势过滤**，日线无周线数据，用更长周期替代）
    ]
  - signals.sell: OR[
      `{factor:"MACD", params:{fastperiod:12, slowperiod:26, signalperiod:9}, output_index:0} cross_down {factor:"MACD", params:{fastperiod:12, slowperiod:26, signalperiod:9}, output_index:1}`,
      `{factor:"RSI", params:{timeperiod:14}} > 80`
    ]
  - position_sizing: method=order_target_percent / target=0.90
  - exit.bracket: use_atr_stop=true / atr_period=14 / atr_multiplier=2.5 / take_profit_pct=0.25
  - backtest_config: initial_cash=100000 / broker_profile=cn_stock_miniqmt / t_plus_one=true / slippage={"type":"percent","value":0.001} / lot_size=100 / warmup_period=70（长周期 MACD slowperiod=52 + 缓冲）/ history_depth=90 / volume_limit_pct=0.2
  - description 标"MACD 全球最广泛动量指标，本模板加长周期趋势过滤降低震荡市假信号"
  - tags=["MACD","动量","强化版","趋势过滤"]

- [x] Task 9: 全量 Schema 校验 + 端到端验证
  - 启动 stock-engine + dev profile 启动 stock-watcher
  - 检查日志「策略模板加载完成：共 13 个」
  - 检查无「策略模板 [xxx] 启动校验失败」WARN（engine 可达时）
  - 调 `GET /python/v1/strategies/templates` 确认返回 13 项，含 8 个新 templateId
  - 抽查 `etf_momentum_rotation` 与 `dividend_low_volatility` 的 configJson 字段完整
  - 跑 `pytest stock-engine/tests/services/strategy/`，validator 测试全绿
  - 可选：基于「ETF 动量轮动」模板创建策略 + 触发回测，确认无 UNKNOWN_FACTOR / 无 NaN 空持仓 / 资金争抢

# Task Dependencies

- Task 0 前置，Task 1-8 依赖 Task 0（解锁 DV_RATIO/NATR/ROC/DEBT_TO_ASSETS/PROFIT_YOY/REVENUE_YOY 等）
- Task 1-8 相互独立，可并行
- Task 9 依赖全部
- ⚠️ Task 6（ETF 轮动）实现期需额外验证 watcher 是否支持 rebalance + universe.pool=manual+ETF 代码；若不支持需回退方案
