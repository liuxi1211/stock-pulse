package com.arthur.stock.service.impl;

import com.arthur.stock.cache.StockCodeCache;
import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.service.AdjFactorService;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.KlineService;
import com.arthur.stock.util.KlineCalculator;
import com.arthur.stock.vo.KlineDataVO;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KlineServiceImpl implements KlineService {

    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final StockCodeCache stockCodeCache;

    @Override
    @Cacheable(value = "kline", key = "#stockCode + '::' + #period + '::' + #adjustment + '::' + #startDate + '::' + #endDate")
    public List<KlineDataVO> getKlineData(String stockCode, String period, String adjustment,
                                           String startDate, String endDate) {
        String normalizedPeriod = normalizePeriod(period);
        String normalizedAdjustment = normalizeAdjustment(adjustment);
        if ("60MIN".equals(normalizedPeriod)) {
            throw new IllegalArgumentException("当前数据源不支持60MIN周期");
        }

        String tsCode = stockCodeCache.toTsCode(stockCode);
        List<DailyQuoteDO> dailies = dailyQuoteService.queryLocalByTsCode(tsCode);
        if (dailies.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, BigDecimal> adjMap = Collections.emptyMap();
        if (!"NONE".equals(normalizedAdjustment)) {
            List<AdjFactorDO> adjFactors = adjFactorService.queryLocalByTsCode(tsCode);
            if (adjFactors.isEmpty()) {
                return Collections.emptyList();
            }
            adjMap = KlineCalculator.buildAdjMap(adjFactors);
        }

        List<KlineDataVO> bars = switch (normalizedPeriod) {
            case "W" -> KlineCalculator.buildWeeklyKline(dailies, adjMap, normalizedAdjustment);
            case "M" -> KlineCalculator.buildMonthlyKline(dailies, adjMap, normalizedAdjustment);
            default -> KlineCalculator.buildDailyKline(dailies, adjMap, normalizedAdjustment);
        };
        List<KlineDataVO> filtered = bars.stream()
                .filter(bar -> bar.getDate().compareTo(startDate) >= 0 && bar.getDate().compareTo(endDate) <= 0)
                .toList();
        return Collections.unmodifiableList(filtered);
    }

    private String normalizePeriod(String period) {
        String normalized = period == null ? "" : period.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "D", "DAILY" -> "D";
            case "W", "WEEKLY" -> "W";
            case "M", "MONTHLY" -> "M";
            case "60MIN" -> "60MIN";
            default -> throw new IllegalArgumentException("period必须为D、W、M或60MIN");
        };
    }

    private String normalizeAdjustment(String adjustment) {
        String normalized = adjustment == null ? "" : adjustment.trim().toUpperCase(Locale.ROOT);
        if (!"QFQ".equals(normalized) && !"HFQ".equals(normalized) && !"NONE".equals(normalized)) {
            throw new IllegalArgumentException("adj必须为QFQ、HFQ或NONE");
        }
        return normalized;
    }
}
