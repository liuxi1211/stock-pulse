"""策略配置校验白名单常量（统一策略配置 Schema §4.5 / §3.3.2）。

所有白名单与 Schema 文档保持单一真相源：
- 下单方法 / 卖出方法：对齐 akquant ``Strategy`` 方法名（Schema §3.3.2）
- 比较器：通用比较 + 时序比较（Schema §4.4）
- ref 允许键：对齐 Schema §4.6 取值表（含两个预留扩展）
- broker_profile：对齐 akquant 0.2.47 的三个 A 股模板（04-backtest-run.md §3）
- factorKey：37 个技术面/价格成交量 + 16 个基本面（Schema §4.5 / factors.default.json，spec 014 对齐）
- 多输出因子 output_index 映射：MACD / BOLL / KDJ / MAMA
- 危险字符串黑名单：自由文本字段注入防护
"""

# ============================================================
# 下单方法白名单（对齐 Schema §3.3.2 / akquant Strategy 方法名）
# ============================================================
POSITION_SIZING_METHODS = {
    "order_target_percent",
    "order_target_value",
    "order_target",
    "buy",
    "sell",
    "buy_all",
    "close_position",
    "order_target_weights",
    "grid",
}

# signals（择时）范式 universe 规模上限：universe 必须 manual 且 stocks 数量 <= 此值
# 依据：akquant 官方 on_bar 多标的示例 universe 均 <= 2；留 10 的余量覆盖小池子场景
SIGNALS_MAX_UNIVERSE_SIZE = 10

# sell_method 允许的取值（close_position=默认平仓 / sell=指定数量 / signal_based=按信号）
SELL_METHODS = {"close_position", "sell", "signal_based"}

# ============================================================
# 网格交易常量（spec 015 FR-G1）
# ============================================================
GRID_MAX_GRIDS_LIMIT = 20
GRID_TICK_SIZE = 0.01
GRID_DEFAULT_MAX_POSITION_VALUE_PCT = 0.9
GRID_DEFAULT_UNFILLED_RETRY_BARS = 1
GRID_DEFAULT_RE_ENTRY_AFTER_CLEAR = False
GRID_DEFAULT_MAX_HOLDING_BARS = 60
GRID_QTY_LOT_SIZE = 100  # A 股最小交易单位

# ============================================================
# 可调参数类型白名单（spec 015 FR-O3）
# ============================================================
TUNABLE_PARAM_TYPES = {"int", "float", "bool", "choice"}

# ============================================================
# 比较器白名单（对齐 Schema §4.4）
# ============================================================
# 通用比较器：screen_config + trading_config 通用
SCREEN_COMPARATORS = {">", "<", ">=", "<=", "==", "!="}

# 交易路径允许的全部比较器（通用 + 时序穿越）
TRADING_COMPARATORS = SCREEN_COMPARATORS | {"cross_up", "cross_down"}

# ============================================================
# ref 允许键（对齐 Schema §4.6）
# ============================================================
# 仅 trading_config 合法；screen_config 内禁止（Schema §7.2）
ALLOWED_REFS = {
    "entry_price",
    "position_pnl_pct",
    "position_qty",
    "bars_held",
}

# ============================================================
# broker_profile 白名单（对齐 akquant 0.2.47 / 04-backtest-run.md §3）
# ============================================================
BROKER_PROFILES = {
    "cn_stock_miniqmt",
    "cn_stock_t1_low_fee",
    "cn_stock_sim_high_slippage",
}

# ============================================================
# 因子 factorKey 体系（对齐 Schema §4.5 / factors.default.json）
# ============================================================
# 技术面 + 价格成交量因子（走 akquant.talib 或 NumpySimpleProvider），共 37 个。
# 与 stock-engine/data/factors.default.json 的 OVERLAP/MOMENTUM/VOLATILITY/VOLUME/STATISTIC/PRICE 类严格对齐。
TECHNICAL_FACTOR_KEYS = {
    # OVERLAP 趋势指标
    "MA", "EMA", "WMA", "DEMA", "TEMA", "TRIMA", "KAMA", "T3", "MAMA",
    "BOLL", "SAR",
    # MOMENTUM 动量指标
    "MACD", "RSI", "KDJ", "CCI", "WILLR", "ADX", "PLUS_DI", "MINUS_DI",
    "ROC", "MOM", "APO", "PPO", "TRIX",
    # VOLATILITY 波动率指标
    "ATR", "NATR", "TRANGE",
    # VOLUME 成交量指标
    "OBV", "AD", "ADOSC", "VOL_MA", "VOL_EMA",
    # STATISTIC 统计指标
    "STDDEV",
    # PRICE 价格 / 成交量直通（NumpySimpleProvider）
    "OPEN", "HIGH", "LOW", "CLOSE", "VOLUME",
}

# 基本面因子（走选股侧因子服务，不进 on_bar 实时路径），共 16 个。
# 与 factors.default.json 的 VALUATION/QUALITY/GROWTH/FINANCE 类严格对齐。
# BREAKING（spec 014）：清理 3 个僵尸因子（factors.default.json 无定义、运行时静默 NaN）：
#   REVENUE_GROWTH → 改用 REVENUE_YOY
#   NET_PROFIT_GROWTH → 改用 PROFIT_YOY
#   NORTHBOUND_NET_INFLOW → 删除（无对应字段）
FUNDAMENTAL_FACTOR_KEYS = {
    # VALUATION 估值因子
    "PE_TTM", "PB", "PS_TTM", "DV_RATIO", "TOTAL_MV", "CIRC_MV", "TURNOVER_RATE",
    # QUALITY 质量因子
    "ROE_TTM", "ROA_TTM", "GROSS_MARGIN", "NETPROFIT_MARGIN",
    # GROWTH 成长因子
    "REVENUE_YOY", "PROFIT_YOY", "EPS_YOY",
    # FINANCE 财务结构
    "DEBT_TO_ASSETS", "CURRENT_RATIO",
}

# 全量 factorKey（技术面 + 基本面）
ALL_FACTOR_KEYS = TECHNICAL_FACTOR_KEYS | FUNDAMENTAL_FACTOR_KEYS

# ============================================================
# 多输出因子 output_index 映射（对齐 Schema §4.5 / akquant talib 返回顺序）
# ============================================================
# key=factorKey，value=按顺序的 output 标签列表；output_index 在 [0, len-1] 内。
# 标签仅用于 len() 判断 output_index 范围，大小写不影响逻辑（下游按 index 取值）。
# MAMA 为 spec 014 新增（双输出：MAMA 主线 + FAMA 信号线）。
MULTI_OUTPUT_FACTORS = {
    "MACD": ["dif", "dea", "hist"],
    "BOLL": ["upper", "mid", "lower"],
    "KDJ": ["k", "d", "j"],
    "MAMA": ["mama", "fama"],
}

# ============================================================
# 注入防护黑名单（自由文本字段：name / description / rule name 等）
# ============================================================
# 命中任一即视为疑似注入（Schema §7 / 安全约束）
DANGEROUS_PATTERNS = [
    "__class__",
    "__init__",
    "__reduce__",
    "__import__",
    "exec(",
    "eval(",
    "import os",
    "subprocess",
    "os.system",
    "pickle.loads",
]
