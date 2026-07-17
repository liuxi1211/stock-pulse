package com.arthur.stock.constant;

import com.arthur.stock.dto.EnumOptionDTO;
import java.util.Arrays;
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
 *   <li>factorKey：37 个技术面/价格成交量 + 16 个基本面（Schema §4.5 / factors.default.json，spec 014）</li>
 * </ul>
 */
public final class StrategySchemaConstants {

    private StrategySchemaConstants() {}

    /**
     * 下单方法白名单（对齐 Schema §3.3.2 / akquant Strategy 方法名）。
     * <p>由 {@link PositionSizingMethodEnum} 派生，watcher 侧业务代码请直接引用枚举，避免魔法值。
     */
    public static final List<EnumOptionDTO> POSITION_SIZING_METHODS = toOptions(PositionSizingMethodEnum.values());

    /**
     * sell_method 允许取值（close_position=默认平仓 / sell=指定数量 / signal_based=按信号）。
     * <p>由 {@link SellMethodEnum} 派生，watcher 侧业务代码请直接引用枚举，避免魔法值。
     */
    public static final List<EnumOptionDTO> SELL_METHODS = toOptions(SellMethodEnum.values());

    private static List<EnumOptionDTO> toOptions(DisplayableEnum[] enums) {
        return Arrays.stream(enums)
                .map(e -> new EnumOptionDTO(e.getCode(), e.getLabel()))
                .collect(java.util.stream.Collectors.toList());
    }

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

    /**
     * 技术面 + 价格成交量因子 factorKey（走 akquant.talib 或 NumpySimpleProvider），共 37 个。
     * 与 stock-engine/data/factors.default.json 的 OVERLAP/MOMENTUM/VOLATILITY/VOLUME/STATISTIC/PRICE 类严格对齐。
     */
    public static final List<String> TECHNICAL_FACTOR_KEYS = List.of(
            // OVERLAP 趋势指标
            "MA", "EMA", "WMA", "DEMA", "TEMA", "TRIMA", "KAMA", "T3", "MAMA",
            "BOLL", "SAR",
            // MOMENTUM 动量指标
            "MACD", "RSI", "KDJ", "CCI", "WILLR", "ADX", "PLUS_DI", "MINUS_DI",
            "ROC", "MOM", "APO", "PPO", "TRIX",
            // VOLATILITY 波动率指标
            "ATR", "NATR", "TRANGE",
            // VOLUME 成交量指标
            "OBV", "AD", "ADOSC", "VOL_MA", "VOL_EMA",
            // STATISTIC 统计指标
            "STDDEV",
            // PRICE 价格 / 成交量直通（NumpySimpleProvider）
            "OPEN", "HIGH", "LOW", "CLOSE", "VOLUME"
    );

    /**
     * 基本面因子 factorKey（走选股侧因子服务，不进 on_bar 实时路径），共 16 个。
     * 与 factors.default.json 的 VALUATION/QUALITY/GROWTH/FINANCE 类严格对齐。
     * BREAKING（spec 014）：清理 3 个僵尸因子（factors.default.json 无定义、运行时静默 NaN）：
     * REVENUE_GROWTH → 改用 REVENUE_YOY；NET_PROFIT_GROWTH → 改用 PROFIT_YOY；NORTHBOUND_NET_INFLOW → 删除（无对应字段）。
     */
    public static final List<String> FUNDAMENTAL_FACTOR_KEYS = List.of(
            // VALUATION 估值因子
            "PE_TTM", "PB", "PS_TTM", "DV_RATIO", "TOTAL_MV", "CIRC_MV", "TURNOVER_RATE",
            // QUALITY 质量因子
            "ROE_TTM", "ROA_TTM", "GROSS_MARGIN", "NETPROFIT_MARGIN",
            // GROWTH 成长因子
            "REVENUE_YOY", "PROFIT_YOY", "EPS_YOY",
            // FINANCE 财务结构
            "DEBT_TO_ASSETS", "CURRENT_RATIO"
    );

    /** signals（择时）范式 universe 规模上限，与 engine constants.py 对齐（spec 009-strategy-paradigm-exclusive）。 */
    public static final int SIGNALS_MAX_UNIVERSE_SIZE = 10;
}
