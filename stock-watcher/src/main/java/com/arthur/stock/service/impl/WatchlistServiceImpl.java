package com.arthur.stock.service.impl;

import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.DailyBasicMapper;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.mapper.SwIndustryMemberMapper;
import com.arthur.stock.mapper.WatchlistMapper;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.model.SwIndustryMemberDO;
import com.arthur.stock.model.WatchlistItemDO;
import com.arthur.stock.service.WatchlistService;
import com.arthur.stock.util.StockDataHelper;
import com.arthur.stock.vo.WatchlistItemVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自选股服务实现
 */
@Service
@RequiredArgsConstructor
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistMapper watchlistMapper;
    private final StockBasicMapper stockBasicMapper;
    private final DailyQuoteMapper dailyQuoteMapper;
    private final DailyBasicMapper dailyBasicMapper;
    private final SwIndustryMemberMapper swIndustryMemberMapper;
    private final StockDataHelper stockDataHelper;

    @Override
    public List<WatchlistItemVO> getWatchlist(Long userId) {
        return getWatchlist(userId, null, "sort_order", "asc");
    }

    @Override
    public List<WatchlistItemVO> getWatchlist(Long userId, Long groupId, String sortBy, String order) {
        LambdaQueryWrapper<WatchlistItemDO> queryWrapper = new LambdaQueryWrapper<WatchlistItemDO>()
                .eq(WatchlistItemDO::getUserId, userId);
        if (groupId != null) {
            queryWrapper.eq(WatchlistItemDO::getGroupId, groupId);
        }
        List<WatchlistItemDO> items = watchlistMapper.selectList(queryWrapper);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> codes = items.stream().map(WatchlistItemDO::getStockCode).toList();

        List<StockBasicDO> basics = stockBasicMapper.selectList(
                new LambdaQueryWrapper<StockBasicDO>().in(StockBasicDO::getSymbol, codes));
        Map<String, StockBasicDO> basicBySymbol = basics.stream()
                .collect(Collectors.toMap(StockBasicDO::getSymbol, b -> b, (a, b) -> a));
        Map<String, StockBasicDO> basicByTsCode = basics.stream()
                .collect(Collectors.toMap(StockBasicDO::getTsCode, b -> b, (a, b) -> a));

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

        Map<String, DailyBasicDO> basicDailyByTsCode = Collections.emptyMap();
        if (latestDate != null && !basicByTsCode.isEmpty()) {
            List<DailyBasicDO> dailyBasics = dailyBasicMapper.selectByCodesAndDate(
                    new ArrayList<>(basicByTsCode.keySet()), latestDate);
            basicDailyByTsCode = dailyBasics.stream()
                    .collect(Collectors.toMap(DailyBasicDO::getTsCode, b -> b, (a, b) -> a));
        }

        Map<String, String> industryNameByTsCode = Collections.emptyMap();
        if (!basicByTsCode.isEmpty()) {
            List<SwIndustryMemberDO> industries = swIndustryMemberMapper.selectLatestL1ByTsCodes(
                    new ArrayList<>(basicByTsCode.keySet()));
            industryNameByTsCode = industries.stream()
                    .collect(Collectors.toMap(SwIndustryMemberDO::getTsCode,
                            SwIndustryMemberDO::getIndexName, (a, b) -> a));
        }

        Map<String, List<BigDecimal>> closeSeriesBySymbol = Collections.emptyMap();
        if (!basicByTsCode.isEmpty() && latestDate != null) {
            String startDate = LocalDate.parse(latestDate, DateTimeFormatter.BASIC_ISO_DATE)
                    .minusDays(60)
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
            List<DailyQuoteDO> seriesQuotes = dailyQuoteMapper.selectOhlcvByCodesAndDateRange(
                    new ArrayList<>(basicByTsCode.keySet()), startDate, latestDate);

            Map<String, List<DailyQuoteDO>> quotesByTsCode = seriesQuotes.stream()
                    .collect(Collectors.groupingBy(DailyQuoteDO::getTsCode));
            closeSeriesBySymbol = quotesByTsCode.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> {
                                StockBasicDO basic = basicByTsCode.get(e.getKey());
                                return basic != null ? basic.getSymbol() : e.getKey();
                            },
                            e -> e.getValue().stream()
                                    .sorted(Comparator.comparing(DailyQuoteDO::getTradeDate).reversed())
                                    .limit(30)
                                    .sorted(Comparator.comparing(DailyQuoteDO::getTradeDate))
                                    .map(DailyQuoteDO::getClose)
                                    .toList()
                    ));
        }

        Map<String, WatchlistItemDO> itemByCode = items.stream()
                .collect(Collectors.toMap(WatchlistItemDO::getStockCode, i -> i));

        Map<String, DailyQuoteDO> finalQuoteByTsCode = quoteByTsCode;
        Map<String, DailyBasicDO> finalBasicDailyByTsCode = basicDailyByTsCode;
        Map<String, String> finalIndustryNameByTsCode = industryNameByTsCode;
        Map<String, List<BigDecimal>> finalCloseSeriesBySymbol = closeSeriesBySymbol;

        List<WatchlistItemVO> result = new ArrayList<>();
        for (WatchlistItemDO item : items) {
            StockBasicDO basic = basicBySymbol.get(item.getStockCode());
            if (basic == null) {
                continue;
            }
            DailyQuoteDO quote = finalQuoteByTsCode.get(basic.getTsCode());
            DailyBasicDO dailyBasic = finalBasicDailyByTsCode.get(basic.getTsCode());
            String industryName = finalIndustryNameByTsCode.get(basic.getTsCode());
            List<BigDecimal> closeSeries = finalCloseSeriesBySymbol.get(item.getStockCode());

            WatchlistItemVO vo = buildWatchlistItemVO(item, basic, quote, dailyBasic, industryName, closeSeries);
            result.add(vo);
        }

        sortResult(result, sortBy, order);
        return result;
    }

    private WatchlistItemVO buildWatchlistItemVO(WatchlistItemDO item, StockBasicDO basic,
                                                  DailyQuoteDO quote, DailyBasicDO dailyBasic,
                                                  String industryName, List<BigDecimal> closeSeries) {
        WatchlistItemVO.WatchlistItemVOBuilder builder = WatchlistItemVO.builder()
                .code(basic.getSymbol())
                .name(basic.getName())
                .groupId(item.getGroupId())
                .note(item.getNote())
                .targetPriceHigh(item.getTargetPriceHigh())
                .targetPriceLow(item.getTargetPriceLow())
                .sortOrder(item.getSortOrder())
                .createdAt(item.getCreatedAt())
                .closeSeries(closeSeries)
                .industryName(industryName);

        if (quote != null) {
            builder.currentPrice(quote.getClose())
                    .changeAmount(quote.getChangeAmt())
                    .changePercent(quote.getPctChg())
                    .volume(quote.getVol() != null ? quote.getVol().longValue() : null)
                    .turnover(stockDataHelper.formatAmount(quote.getAmount()));
        }

        if (dailyBasic != null) {
            builder.totalMv(dailyBasic.getTotalMv())
                    .peTtm(dailyBasic.getPeTtm())
                    .pb(dailyBasic.getPb())
                    .turnoverRate(dailyBasic.getTurnoverRate());
        }

        return builder.build();
    }

    private void sortResult(List<WatchlistItemVO> list, String sortBy, String order) {
        boolean asc = "asc".equalsIgnoreCase(order);
        Comparator<WatchlistItemVO> comparator;
        switch (sortBy != null ? sortBy : "") {
            case "pct_chg" -> comparator = Comparator.comparing(
                    WatchlistItemVO::getChangePercent,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "created_at" -> comparator = Comparator.comparing(
                    WatchlistItemVO::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "name" -> comparator = Comparator.comparing(
                    WatchlistItemVO::getName,
                    Comparator.nullsLast(String::compareTo));
            case "sort_order" -> comparator = Comparator.comparing(
                    WatchlistItemVO::getSortOrder,
                    Comparator.nullsLast(Integer::compareTo));
            default -> {
                return;
            }
        }
        if (!asc) {
            comparator = comparator.reversed();
        }
        list.sort(comparator);
    }

    @Override
    public void addToWatchlist(Long userId, String stockCode) {
        addToWatchlist(userId, stockCode, null);
    }

    @Override
    public void addToWatchlist(Long userId, String stockCode, Long groupId) {
        Long count = stockBasicMapper.selectCount(
                new LambdaQueryWrapper<StockBasicDO>()
                        .eq(StockBasicDO::getSymbol, stockCode)
                        .eq(StockBasicDO::getListStatus, "L"));
        if (count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "股票不存在: " + stockCode);
        }

        Long exists = watchlistMapper.selectCount(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已在自选股中");
        }

        List<WatchlistItemDO> allItems = watchlistMapper.selectList(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .orderByDesc(WatchlistItemDO::getSortOrder)
                        .last("LIMIT 1"));
        int nextSortOrder = 0;
        if (!allItems.isEmpty() && allItems.get(0).getSortOrder() != null) {
            nextSortOrder = allItems.get(0).getSortOrder() + 1;
        }

        WatchlistItemDO item = new WatchlistItemDO();
        item.setUserId(userId);
        item.setStockCode(stockCode);
        item.setGroupId(groupId);
        item.setSortOrder(nextSortOrder);
        watchlistMapper.insert(item);
    }

    @Override
    public void removeFromWatchlist(Long userId, String stockCode) {
        int deleted = watchlistMapper.delete(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }
    }

    @Override
    public void setReminder(Long userId, String stockCode, BigDecimal targetPriceHigh, BigDecimal targetPriceLow) {
        if (targetPriceHigh == null && targetPriceLow == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "高位提醒价和低位提醒价不能同时为空");
        }
        if (targetPriceHigh != null && targetPriceHigh.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "高位提醒价必须大于0");
        }
        if (targetPriceLow != null && targetPriceLow.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "低位提醒价必须大于0");
        }
        if (targetPriceHigh != null && targetPriceLow != null
                && targetPriceHigh.compareTo(targetPriceLow) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "高位提醒价必须大于低位提醒价");
        }

        WatchlistItemDO item = watchlistMapper.selectOne(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }

        int updated = watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode)
                        .set(WatchlistItemDO::getTargetPriceHigh, targetPriceHigh)
                        .set(WatchlistItemDO::getTargetPriceLow, targetPriceLow));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "更新价格提醒失败");
        }
    }

    @Override
    public void clearReminder(Long userId, String stockCode) {
        WatchlistItemDO item = watchlistMapper.selectOne(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }

        int updated = watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode)
                        .set(WatchlistItemDO::getTargetPriceHigh, null)
                        .set(WatchlistItemDO::getTargetPriceLow, null));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "清除价格提醒失败");
        }
    }

    @Override
    public void updateSortOrder(Long userId, String stockCode, Integer sortOrder) {
        WatchlistItemDO item = watchlistMapper.selectOne(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }

        int updated = watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode)
                        .set(WatchlistItemDO::getSortOrder, sortOrder));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "更新排序失败");
        }
    }

    @Override
    public void updateGroup(Long userId, String stockCode, Long groupId) {
        WatchlistItemDO item = watchlistMapper.selectOne(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode));
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该股票不在自选中");
        }

        int updated = watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getStockCode, stockCode)
                        .set(WatchlistItemDO::getGroupId, groupId));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "更新分组失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchRemove(Long userId, List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return;
        }
        watchlistMapper.delete(
                new LambdaQueryWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .in(WatchlistItemDO::getStockCode, stockCodes));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchMoveGroup(Long userId, List<String> stockCodes, Long groupId) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return;
        }
        watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .in(WatchlistItemDO::getStockCode, stockCodes)
                        .set(WatchlistItemDO::getGroupId, groupId));
    }
}
