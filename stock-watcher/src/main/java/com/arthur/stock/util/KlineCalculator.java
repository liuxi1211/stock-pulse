package com.arthur.stock.util;

import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.vo.KlineDataVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class KlineCalculator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PRICE_SCALE = 4;

    private KlineCalculator() {
    }

    public static BigDecimal forwardAdj(BigDecimal rawPrice, BigDecimal adjFactor) {
        if (rawPrice == null || adjFactor == null) {
            return null;
        }
        return rawPrice.multiply(adjFactor).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal adjustPrice(BigDecimal rawPrice, BigDecimal factor,
                                         BigDecimal latestFactor, String adjustment) {
        if (rawPrice == null) {
            return null;
        }
        return switch (adjustment) {
            case "NONE" -> rawPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            case "QFQ" -> factor == null || latestFactor == null || latestFactor.signum() == 0
                    ? null
                    : rawPrice.multiply(factor).divide(latestFactor, PRICE_SCALE, RoundingMode.HALF_UP);
            case "HFQ" -> factor == null
                    ? null
                    : rawPrice.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            default -> throw new IllegalArgumentException("adjustment必须为QFQ、HFQ或NONE");
        };
    }

    public static List<KlineDataVO> buildDailyKline(List<DailyQuoteDO> dailies,
                                                     Map<String, BigDecimal> adjMap,
                                                     String adjustment) {
        BigDecimal latestFactor = latestFactor(adjMap);
        return sortedDailies(dailies).stream()
                .map(daily -> toAdjustedBar(daily, adjMap.get(daily.getTradeDate()), latestFactor, adjustment))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static List<KlineDataVO> buildWeeklyKline(List<DailyQuoteDO> dailies,
                                                      Map<String, BigDecimal> adjMap,
                                                      String adjustment) {
        return aggregateKline(buildDailyKline(dailies, adjMap, adjustment), KlineCalculator::weekGroupKey);
    }

    public static List<KlineDataVO> buildMonthlyKline(List<DailyQuoteDO> dailies,
                                                       Map<String, BigDecimal> adjMap,
                                                       String adjustment) {
        return aggregateKline(buildDailyKline(dailies, adjMap, adjustment), KlineCalculator::monthGroupKey);
    }

    public static List<KlineDataVO> buildDailyKline(List<DailyQuoteDO> dailies,
                                                     Map<String, BigDecimal> adjMap) {
        return buildDailyKline(dailies, adjMap, "HFQ");
    }

    public static List<KlineDataVO> buildWeeklyKline(List<DailyQuoteDO> dailies,
                                                      Map<String, BigDecimal> adjMap) {
        return buildWeeklyKline(dailies, adjMap, "HFQ");
    }

    public static List<KlineDataVO> buildMonthlyKline(List<DailyQuoteDO> dailies,
                                                       Map<String, BigDecimal> adjMap) {
        return buildMonthlyKline(dailies, adjMap, "HFQ");
    }

    public static Map<String, BigDecimal> buildAdjMap(List<AdjFactorDO> adjFactors) {
        return adjFactors.stream()
                .filter(item -> item.getTradeDate() != null && item.getAdjFactor() != null)
                .collect(Collectors.toMap(
                        AdjFactorDO::getTradeDate,
                        AdjFactorDO::getAdjFactor,
                        (first, second) -> second));
    }

    static String weekGroupKey(String tradeDate) {
        LocalDate date = LocalDate.parse(tradeDate, DATE_FMT);
        return date.plusDays(5 - date.getDayOfWeek().getValue()).format(DATE_FMT);
    }

    static String monthGroupKey(String tradeDate) {
        LocalDate date = LocalDate.parse(tradeDate, DATE_FMT);
        return date.withDayOfMonth(date.lengthOfMonth()).format(DATE_FMT);
    }

    private static List<DailyQuoteDO> sortedDailies(List<DailyQuoteDO> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return List.of();
        }
        return dailies.stream()
                .filter(item -> item != null && item.getTradeDate() != null)
                .sorted(Comparator.comparing(DailyQuoteDO::getTradeDate))
                .toList();
    }

    private static BigDecimal latestFactor(Map<String, BigDecimal> adjMap) {
        return adjMap.entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static KlineDataVO toAdjustedBar(DailyQuoteDO daily, BigDecimal factor,
                                              BigDecimal latestFactor, String adjustment) {
        BigDecimal open = adjustPrice(daily.getOpen(), factor, latestFactor, adjustment);
        BigDecimal high = adjustPrice(daily.getHigh(), factor, latestFactor, adjustment);
        BigDecimal low = adjustPrice(daily.getLow(), factor, latestFactor, adjustment);
        BigDecimal close = adjustPrice(daily.getClose(), factor, latestFactor, adjustment);
        if (open == null || high == null || low == null || close == null) {
            return null;
        }
        return KlineDataVO.builder()
                .date(daily.getTradeDate())
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(daily.getVol() == null ? 0L : daily.getVol().longValue())
                .build();
    }

    private static List<KlineDataVO> aggregateKline(List<KlineDataVO> dailyBars,
                                                     Function<String, String> groupKeyFn) {
        LinkedHashMap<String, List<KlineDataVO>> groups = new LinkedHashMap<>();
        for (KlineDataVO bar : dailyBars) {
            groups.computeIfAbsent(groupKeyFn.apply(bar.getDate()), key -> new ArrayList<>()).add(bar);
        }
        List<KlineDataVO> result = new ArrayList<>(groups.size());
        for (List<KlineDataVO> group : groups.values()) {
            KlineDataVO first = group.get(0);
            KlineDataVO last = group.get(group.size() - 1);
            BigDecimal high = group.stream().map(KlineDataVO::getHigh).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = group.stream().map(KlineDataVO::getLow).min(BigDecimal::compareTo).orElseThrow();
            long volume = group.stream().mapToLong(KlineDataVO::getVolume).sum();
            result.add(KlineDataVO.builder()
                    .date(last.getDate())
                    .open(first.getOpen())
                    .high(high)
                    .low(low)
                    .close(last.getClose())
                    .volume(volume)
                    .build());
        }
        return result;
    }
}
