"""策略配置校验白名单常量（统一策略配置 Schema §4.5 / §3.3.2）。

所有白名单与 Schema 文档保持单一真相源：
- 下单方法 / 卖出方法：对齐 akquant ``Strategy`` 方法名（Schema §3.3.2）
- 比较器：通用比较 + 时序比较（Schema §4.4）
- ref 允许键：对齐 Schema §4.6 取值表（含两个预留扩展）
- broker_profile：对齐 akquant 0.2.47 的三个 A 股模板（04-backtest-run.md §3）
- factorKey：20 个技术面 + 10 个基本面（Schema §4.5）
- 多输出因子 output_index 映射：MACD / BOLL / KDJ
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
}

# signals（择时）范式 universe 规模上限：universe 必须 manual 且 stocks 数量 <= 此值
# 依据：akquant 官方 on_bar 多标的示例 universe 均 <= 2；留 10 的余量覆盖小池子场景
SIGNALS_MAX_UNIVERSE_SIZE = 10

# sell_method 允许的取值（close_position=默认平仓 / sell=指定数量 / signal_based=按信号）
SELL_METHODS = {"close_position", "sell", "signal_based"}

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
# 因子 factorKey 体系（对齐 Schema §4.5）
# ============================================================
# 20 个技术面 / 价格成交量因子（走 akquant.talib 或 NumpySimpleProvider）
TECHNICAL_FACTOR_KEYS = {
    # 技术面（talib）
    "MA", "EMA", "BOLL", "SAR", "MACD", "RSI", "KDJ", "ADX",
    "PLUS_DI", "MINUS_DI", "WILLR", "CCI", "ATR", "OBV",
    # 价格 / 成交量（NumpySimpleProvider）
    "CLOSE", "HIGH", "LOW", "VOLUME", "VOL_MA", "VOL_EMA",
}

# 10 个基本面因子（走选股侧因子服务，不进 on_bar 实时路径）
FUNDAMENTAL_FACTOR_KEYS = {
    "PE_TTM", "PB", "TOTAL_MV", "ROE_TTM", "REVENUE_GROWTH",
    "NET_PROFIT_GROWTH", "GROSS_MARGIN", "CURRENT_RATIO",
    "TURNOVER_RATE", "NORTHBOUND_NET_INFLOW",
}

# 全量 factorKey（技术面 + 基本面）
ALL_FACTOR_KEYS = TECHNICAL_FACTOR_KEYS | FUNDAMENTAL_FACTOR_KEYS

# ============================================================
# 多输出因子 output_index 映射（对齐 Schema §4.5 / akquant talib 返回顺序）
# ============================================================
# key=factorKey，value=按顺序的 output 标签列表；output_index 在 [0, len-1] 内
MULTI_OUTPUT_FACTORS = {
    "MACD": ["dif", "dea", "hist"],
    "BOLL": ["upper", "mid", "lower"],
    "KDJ": ["k", "d", "j"],
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
