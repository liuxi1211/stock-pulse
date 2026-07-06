package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 交易日历实现：distinct trade_date 升序 + 最新交易日，结果走 Caffeine 缓存。
 * <p>
 * 缓存策略见 {@code CacheConfig}：缓存名 {@code tradeCalendar} / {@code latestTradeDate}，
 * 写入后 1 天过期；每日 {@code DailyUpdateTask} 拉���后可主动 evict（如需）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCalendarServiceImpl implements TradeCalendarService {

    private final DailyQuoteMapper dailyQuoteMapper;

    @Override
    @Cacheable(value = "tradeCalendar", key = "'sortedDates'")
    public List<String> getSortedTradeDates() {
        List<String> dates = dailyQuoteMapper.selectDistinctTradeDatesAsc();
        return dates == null ? Collections.emptyList() : dates;
    }

    @Override
    @Cacheable(value = "latestTradeDate", key = "'latest'")
    public String getLatestTradeDate() {
        return dailyQuoteMapper.selectLatestTradeDate();
    }

    @Override
    public String findNthTradeDateAfter(String baseDate, int n) {
        if (baseDate == null || n < 1) {
            return null;
        }
        List<String> tradeDates = getSortedTradeDates();
        if (tradeDates.isEmpty()) {
            return null;
        }
        int idx = Collections.binarySearch(tradeDates, baseDate);
        int start = idx >= 0 ? idx + 1 : -(idx + 1);
        int target = start + n - 1;
        if (target < 0 || target >= tradeDates.size()) {
            return null;
        }
        return tradeDates.get(target);
    }
}
