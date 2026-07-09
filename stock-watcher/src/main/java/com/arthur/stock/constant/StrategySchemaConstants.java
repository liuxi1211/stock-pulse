package com.arthur.stock.constant;

import java.util.List;

/**
 * 策略配置 Schema 白名单常量（与 stock-engine {@code services/strategy/constants.py} 对齐）。
 * <p>
 * 作用：供前端策略编辑器下拉选项使用，避免前端硬编码。 watcher 侧只读、不参与引擎校验；
 * 引擎侧的真相源仍在 {@code constants.py}，二者需保持同步。
 * <ul>
 *   <li>下单方法 / 卖出方法：对齐 akquant {@code Strategy} 方法名（Schema §3.3.2）</li>
 *   <li>比较器：通用比较 + 时序比较（Schema §4.4）</li>
 *   <li>ref 允许键：对齐 Schema §4.6 取值表</li>
 *   <li>broker_profile：对齐 akquant 0.2.47 的三个 A 股模板（04-backtest-run.md §3）</li>
 *   <li>factorKey：20 个技术面 + 10 个基本面（Schema §4.5）</li>
 * </ul>
 */
public final class StrategySchemaConstants {

    private StrategySchemaConstants() {}

    /** 下单方法白名单（对齐 Schema §3.3.2 / akquant Strategy 方法名）。 */
    public static final List<String> POSITION_SIZING_METHODS = List.of(
            "order_target_percent",
            "order_target_value",
            "order_target",
            "buy",
            "sell",
            "buy_all",
            "close_position",
            "order_target_weights"
    );

    /** sell_method 允许取值：close_position=默认平仓 / sell=指定数量 / signal_based=按信号。 */
    public static final List<String> SELL_METHODS = List.of(
            "close_position",
            "sell",
            "signal_based"
    );

    /** 通用比较器：screen_config + trading_config 通用。 */
    public static final List<String> SCREEN_COMPARATORS = List.of(
            ">", "<", ">=", "<=", "==", "!="
    );

    /** 交易路径允许的全部比较器（通用 + 时序穿越）。 */
    public static final List<String> TRADING_COMPARATORS = List.of(
            ">", "<", ">=", "<=", "==", "!=", "cross_up", "cross_down"
    );

    /** trading_config 合法的 ref 引用键（screen_config 内禁止）。 */
    public static final List<String> ALLOWED_REFS = List.of(
            "entry_price",
            "position_pnl_pct",
            "position_qty",
            "bars_held"
    );

    /** broker_profile 白名单（对齐 akquant 0.2.47 / 04-backtest-run.md §3）。 */
    public static final List<String> BROKER_PROFILES = List.of(
            "cn_stock_miniqmt",
            "cn_stock_t1_low_fee",
            "cn_stock_sim_high_slippage"
    );

    /** 20 个技术面 / 价格成交量因子 factorKey（走 akquant.talib 或 NumpySimpleProvider）。 */
    public static final List<String> TECHNICAL_FACTOR_KEYS = List.of(
            "MA", "EMA", "BOLL", "SAR", "MACD", "RSI", "KDJ", "ADX",
            "PLUS_DI", "MINUS_DI", "WILLR", "CCI", "ATR", "OBV",
            "CLOSE", "HIGH", "LOW", "VOLUME", "VOL_MA", "VOL_EMA"
    );

    /** 10 个基本面因子 factorKey（走选股侧因子服务，不进 on_bar 实时路径）。 */
    public static final List<String> FUNDAMENTAL_FACTOR_KEYS = List.of(
            "PE_TTM", "PB", "TOTAL_MV", "ROE_TTM", "REVENUE_GROWTH",
            "NET_PROFIT_GROWTH", "GROSS_MARGIN", "CURRENT_RATIO",
            "TURNOVER_RATE", "NORTHBOUND_NET_INFLOW"
    );
}
