package com.arthur.stock.constant;

/**
 * 基本面因子 key 常量类。
 * <p>
 * 这些 key 是 engine Python Schema 的 Literal，watcher 仅作为 fundamentals
 * Map 的 key 透传给 engine，无需 code↔label 双值映射，故用常量类而非 DisplayableEnum。
 * 对齐 {@code .trae/rules/akquant/07-talib-indicators.md} 的 factorKey 表。
 */
public final class FactorKey {

    private FactorKey() {
    }

    // —— daily_basic 估值类 ——
    public static final String PE_TTM = "PE_TTM";
    public static final String PB = "PB";
    public static final String PS_TTM = "PS_TTM";
    public static final String DV_RATIO = "DV_RATIO";
    public static final String TOTAL_MV = "TOTAL_MV";
    public static final String CIRC_MV = "CIRC_MV";
    public static final String TURNOVER_RATE = "TURNOVER_RATE";

    // —— fina_indicator 财务类 ——
    public static final String ROE_TTM = "ROE_TTM";
    public static final String ROA_TTM = "ROA_TTM";
    public static final String GROSS_MARGIN = "GROSS_MARGIN";
    public static final String NETPROFIT_MARGIN = "NETPROFIT_MARGIN";
    public static final String REVENUE_YOY = "REVENUE_YOY";
    public static final String PROFIT_YOY = "PROFIT_YOY";
    public static final String DEBT_TO_ASSETS = "DEBT_TO_ASSETS";
    public static final String EPS_YOY = "EPS_YOY";
}
