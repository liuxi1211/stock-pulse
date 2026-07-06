package com.arthur.stock.service;

import java.util.List;

/**
 * 交易日历服务：提供交易日序列与「第 N 个交易日」查询，结果走 Caffeine 缓存。
 * <p>
 * 替代旧 {@code ScreenerServiceImpl#listSortedTradeDates} 的 {@code selectList(null)} 全表加载。
 * 缓存名 {@code tradeCalendar}（在 {@code CacheConfig} 注册，1 天 TTL）。
 */
public interface TradeCalendarService {

    /**
     * 全市场 distinct trade_date，升序。
     */
    List<String> getSortedTradeDates();

    /**
     * 最新交易日（daily_quote 中最大 trade_date）。
     */
    String getLatestTradeDate();

    /**
     * 找 baseDate 之后（不含当日）的第 n 个交易日。
     *
     * @param baseDate YYYYMMDD
     * @param n        交易日数（1 起）
     * @return YYYYMMDD 或 {@code null}（越界）
     */
    String findNthTradeDateAfter(String baseDate, int n);
}
