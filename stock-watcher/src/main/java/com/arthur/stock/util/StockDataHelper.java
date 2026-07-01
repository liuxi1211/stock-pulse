package com.arthur.stock.util;

import com.arthur.stock.cache.StockCodeCache;
import com.arthur.stock.constant.MarketEnum;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.vo.StockRankVO;
import com.arthur.stock.vo.StockVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票数据组装工具，提供 StockVO / StockRankVO 的构建方法
 */
@Component
@RequiredArgsConstructor
public class StockDataHelper {

    private final StockBasicMapper stockBasicMapper;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final StockCodeCache stockCodeCache;

    /**
     * 根据股票代码列表查询基础信息 + 最新行情，构建 StockVO 列表
     */
    public List<StockVO> buildStockVOListBySymbols(List<String> symbols) {
        List<StockBasicDO> basics = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>()
                        .in(StockBasicDO::getSymbol, symbols));
        if (basics.isEmpty()) return Collections.emptyList();

        Map<String, StockBasicDO> basicByTsCode = basics.stream()
                .collect(Collectors.toMap(StockBasicDO::getTsCode, b -> b));
        Map<String, StockBasicDO> basicBySymbol = basics.stream()
                .collect(Collectors.toMap(StockBasicDO::getSymbol, b -> b, (a, b) -> a));

        String latestDate = dailyQuoteMapper.selectLatestTradeDate();
        Map<String, DailyQuoteDO> quoteByTsCode = Collections.emptyMap();
        if (latestDate != null && !basicByTsCode.isEmpty()) {
            List<DailyQuoteDO> quotes = dailyQuoteMapper.selectList(
                    new LambdaQueryWrapper<DailyQuoteDO>()
                            .eq(DailyQuoteDO::getTradeDate, latestDate)
                            .in(DailyQuoteDO::getTsCode, basicByTsCode.keySet()));
            quoteByTsCode = quotes.stream()
                    .collect(Collectors.toMap(DailyQuoteDO::getTsCode, q -> q, (a, b) -> a));
        }

        Map<String, DailyQuoteDO> finalQuoteMap = quoteByTsCode;
        List<StockVO> result = new ArrayList<>();
        for (String symbol : symbols) {
            StockBasicDO basic = basicBySymbol.get(symbol);
            if (basic == null) continue;
            DailyQuoteDO quote = finalQuoteMap.get(basic.getTsCode());
            result.add(buildStockVO(basic, quote));
        }
        return result;
    }

    /**
     * 根据基础信息列表查询最新行情，构建 StockVO 列表
     */
    public List<StockVO> enrichWithDailyQuote(List<StockBasicDO> basics) {
        if (basics.isEmpty()) return Collections.emptyList();

        String latestDate = dailyQuoteMapper.selectLatestTradeDate();
        Map<String, DailyQuoteDO> quoteByTsCode = Collections.emptyMap();
        if (latestDate != null) {
            List<String> tsCodes = basics.stream().map(StockBasicDO::getTsCode).toList();
            List<DailyQuoteDO> quotes = dailyQuoteMapper.selectList(
                    new LambdaQueryWrapper<DailyQuoteDO>()
                            .eq(DailyQuoteDO::getTradeDate, latestDate)
                            .in(DailyQuoteDO::getTsCode, tsCodes));
            quoteByTsCode = quotes.stream()
                    .collect(Collectors.toMap(DailyQuoteDO::getTsCode, q -> q, (a, b) -> a));
        }

        Map<String, DailyQuoteDO> finalQuoteMap = quoteByTsCode;
        return basics.stream()
                .map(basic -> buildStockVO(basic, finalQuoteMap.get(basic.getTsCode())))
                .toList();
    }

    /**
     * 将单条基础信息 + 行情数据组装为 StockVO
     */
    public StockVO buildStockVO(StockBasicDO basic, DailyQuoteDO quote) {
        StockVO.StockVOBuilder builder = StockVO.builder()
                .code(basic.getSymbol())
                .name(basic.getName())
                .market(MarketEnum.fromSymbol(basic.getSymbol()))
                .industry(basic.getIndustry());

        if (quote != null) {
            builder.currentPrice(quote.getClose())
                    .changeAmount(quote.getChangeAmt())
                    .changePercent(quote.getPctChg())
                    .openPrice(quote.getOpen())
                    .highPrice(quote.getHigh())
                    .lowPrice(quote.getLow())
                    .closePrice(quote.getClose())
                    .prevClose(quote.getPreClose())
                    .volume(quote.getVol() != null ? quote.getVol().longValue() : null)
                    .turnover(formatAmount(quote.getAmount()))
                    .updateTime(LocalDateTime.now());
        }
        return builder.build();
    }

    /**
     * 将行情列表 + 基础信息组装为 StockRankVO 列表
     */
    public List<StockRankVO> toRankVOList(List<DailyQuoteDO> quotes) {
        if (quotes.isEmpty()) return Collections.emptyList();

        List<String> tsCodes = quotes.stream().map(DailyQuoteDO::getTsCode).toList();
        List<StockBasicDO> basics = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>().in(StockBasicDO::getTsCode, tsCodes));
        Map<String, StockBasicDO> basicMap = basics.stream()
                .collect(Collectors.toMap(StockBasicDO::getTsCode, b -> b, (a, b) -> a));

        return quotes.stream()
                .map(q -> {
                    StockBasicDO basic = basicMap.get(q.getTsCode());
                    return StockRankVO.builder()
                            .code(basic != null ? basic.getSymbol() : stockCodeCache.toSymbol(q.getTsCode()))
                            .name(basic != null ? basic.getName() : q.getTsCode())
                            .close(q.getClose())
                            .pctChg(q.getPctChg())
                            .amount(q.getAmount())
                            .build();
                }).toList();
    }

    /**
     * 格式化成交额（千元单位转为万/亿）
     */
    public String formatAmount(BigDecimal amount) {
        if (amount == null) return null;
        BigDecimal wan = amount.divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP);
        if (wan.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            return wan.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP) + "亿";
        }
        return wan.setScale(0, RoundingMode.HALF_UP) + "万";
    }
}