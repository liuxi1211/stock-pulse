package com.arthur.stock.constant;

/**
 * 涨跌停判定阈值常量（百分比 pct_chg）。
 * 与 DailyQuoteMapper.xml selectMarketTemperature 的阈值口径一致。
 * 供板块级内存统计复用，避免硬编码漂移。
 */
public final class MarketThresholdConstants {
    private MarketThresholdConstants() {}

    /** 主板（非ST）涨跌停阈值 */
    public static final double MAIN_BOARD = 9.9;
    /** 创业板/科创板（非ST）涨跌停阈值 */
    public static final double GEM_STAR = 19.9;
    /** 北交所（非ST）涨跌停阈值 */
    public static final double BSE = 29.9;
    /** ST 股涨跌停阈值 */
    public static final double ST = 4.9;

    public static final String MARKET_MAIN = "主板";
    public static final String MARKET_GEM = "创业板";
    public static final String MARKET_STAR = "科创板";
    public static final String MARKET_BSE = "北交所";
}
