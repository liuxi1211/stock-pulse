package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.util.StockDataHelper;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 市场行情服务实现
 */
@Service
@RequiredArgsConstructor
public class MarketServiceImpl implements MarketService {

    private final DailyQuoteMapper dailyQuoteMapper;
    private final StockDataHelper stockDataHelper;

    private static final int RANK_LIMIT = 10;

    @Override
    @Cacheable(value = "indices", key = "'all'")
    public List<MarketIndexVO> getMarketIndices() {
        return List.of(
                MarketIndexVO.builder().code("000001").name("上证指数").currentPoint(new BigDecimal("3368.07"))
                        .changeAmount(new BigDecimal("12.35")).changePercent(new BigDecimal("0.37"))
                        .volume(385000000L).turnover("4560亿").build(),
                MarketIndexVO.builder().code("399001").name("深证成指").currentPoint(new BigDecimal("10856.23"))
                        .changeAmount(new BigDecimal("-45.68")).changePercent(new BigDecimal("-0.42"))
                        .volume(420000000L).turnover("5230亿").build(),
                MarketIndexVO.builder().code("399006").name("创业板指").currentPoint(new BigDecimal("2156.89"))
                        .changeAmount(new BigDecimal("18.90")).changePercent(new BigDecimal("0.88"))
                        .volume(165000000L).turnover("2180亿").build(),
                MarketIndexVO.builder().code("000688").name("科创50").currentPoint(new BigDecimal("968.45"))
                        .changeAmount(new BigDecimal("-5.20")).changePercent(new BigDecimal("-0.53"))
                        .volume(85000000L).turnover("890亿").build()
        );
    }

    @Override
    public MarketRankingVO getMarketRanking() {
        String latestDate = dailyQuoteMapper.selectLatestTradeDate();
        if (latestDate == null) {
            return MarketRankingVO.builder()
                    .topGainers(Collections.emptyList())
                    .topLosers(Collections.emptyList())
                    .topAmount(Collections.emptyList())
                    .build();
        }

        return MarketRankingVO.builder()
                .tradeDate(latestDate)
                .topGainers(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopGainers(latestDate, RANK_LIMIT)))
                .topLosers(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopLosers(latestDate, RANK_LIMIT)))
                .topAmount(stockDataHelper.toRankVOList(dailyQuoteMapper.selectTopAmount(latestDate, RANK_LIMIT)))
                .build();
    }
}