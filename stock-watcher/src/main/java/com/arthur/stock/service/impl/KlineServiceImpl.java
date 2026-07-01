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
import java.util.Map;

/**
 * K线服务实现
 */
@Service
@RequiredArgsConstructor
public class KlineServiceImpl implements KlineService {

    private final DailyQuoteService dailyQuoteService;
    private final AdjFactorService adjFactorService;
    private final StockCodeCache stockCodeCache;

    @Override
    @Cacheable(value = "kline", key = "#stockCode + '::' + #period")
    public List<KlineDataVO> getKlineData(String stockCode, String period) {
        String tsCode = stockCodeCache.toTsCode(stockCode);

        List<DailyQuoteDO> dailies = dailyQuoteService.queryLocalByTsCode(tsCode);
        List<AdjFactorDO> adjFactors = adjFactorService.queryLocalByTsCode(tsCode);

        if (dailies.isEmpty() || adjFactors.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, BigDecimal> adjMap = KlineCalculator.buildAdjMap(adjFactors);

        List<KlineDataVO> klineData = switch (period) {
            case "weekly" -> KlineCalculator.buildWeeklyKline(dailies, adjMap);
            case "monthly" -> KlineCalculator.buildMonthlyKline(dailies, adjMap);
            default -> KlineCalculator.buildDailyKline(dailies, adjMap);
        };

        return Collections.unmodifiableList(klineData);
    }
}