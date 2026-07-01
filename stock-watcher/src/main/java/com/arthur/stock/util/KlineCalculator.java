package com.arthur.stock.util;

import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.vo.KlineDataVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * K线计算工具类：前复权 + 日/周/月K聚合
 */
public class KlineCalculator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private KlineCalculator() {}

    /**
     * 前复权价格：P_adj = P_raw × adj
     * @return 前复权价格，任一参数为null时返回null
     */
    public static BigDecimal forwardAdj(BigDecimal rawPrice, BigDecimal adjFactor) {
        if (rawPrice == null || adjFactor == null) {
            return null;
        }
        return rawPrice.multiply(adjFactor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 构建前复权日K线
     */
    public static List<KlineDataVO> buildDailyKline(List<DailyQuoteDO> dailies,
                                                     Map<String, BigDecimal> adjMap) {
        List<KlineDataVO> result = new ArrayList<>(dailies.size());
        for (DailyQuoteDO d : dailies) {
            BigDecimal adj = adjMap.get(d.getTradeDate());
            if (adj == null || d.getOpen() == null || d.getClose() == null
                    || d.getHigh() == null || d.getLow() == null) {
                continue;
            }
            result.add(KlineDataVO.builder()
                    .date(d.getTradeDate())
                    .open(forwardAdj(d.getOpen(), adj))
                    .close(forwardAdj(d.getClose(), adj))
                    .high(forwardAdj(d.getHigh(), adj))
                    .low(forwardAdj(d.getLow(), adj))
                    .volume(d.getVol() != null ? d.getVol().longValue() : 0L)
                    .build());
        }
        return result;
    }

    /**
     * 构建前复权周K线：按自然周分组聚合
     */
    public static List<KlineDataVO> buildWeeklyKline(List<DailyQuoteDO> dailies,
                                                      Map<String, BigDecimal> adjMap) {
        return aggregateKline(dailies, adjMap, KlineCalculator::weekGroupKey);
    }

    /**
     * 构建前复权月K线：按自然月分组聚合
     */
    public static List<KlineDataVO> buildMonthlyKline(List<DailyQuoteDO> dailies,
                                                       Map<String, BigDecimal> adjMap) {
        return aggregateKline(dailies, adjMap, KlineCalculator::monthGroupKey);
    }

    /**
     * 通用聚合逻辑：按分组函数分组，每组生成一根K线
     */
    private static List<KlineDataVO> aggregateKline(List<DailyQuoteDO> dailies,
                                                     Map<String, BigDecimal> adjMap,
                                                     java.util.function.Function<String, String> groupKeyFn) {
        // 按分组key分组，保持组内按日期排序
        LinkedHashMap<String, List<DailyQuoteDO>> groups = new LinkedHashMap<>();
        for (DailyQuoteDO d : dailies) {
            if (!adjMap.containsKey(d.getTradeDate())) {
                continue;
            }
            if (d.getOpen() == null || d.getClose() == null
                    || d.getHigh() == null || d.getLow() == null) {
                continue;
            }
            groups.computeIfAbsent(groupKeyFn.apply(d.getTradeDate()), k -> new ArrayList<>()).add(d);
        }

        List<KlineDataVO> result = new ArrayList<>(groups.size());
        for (List<DailyQuoteDO> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }

            DailyQuoteDO first = group.get(0);
            DailyQuoteDO last = group.get(group.size() - 1);

            BigDecimal firstAdj = adjMap.get(first.getTradeDate());
            BigDecimal lastAdj = adjMap.get(last.getTradeDate());

            BigDecimal high = group.stream()
                    .map(d -> forwardAdj(d.getHigh(), adjMap.get(d.getTradeDate())))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal low = group.stream()
                    .map(d -> forwardAdj(d.getLow(), adjMap.get(d.getTradeDate())))
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            long volume = group.stream()
                    .mapToLong(d -> d.getVol() != null ? d.getVol().longValue() : 0L)
                    .sum();

            result.add(KlineDataVO.builder()
                    .date(last.getTradeDate())
                    .open(forwardAdj(first.getOpen(), firstAdj))
                    .close(forwardAdj(last.getClose(), lastAdj))
                    .high(high)
                    .low(low)
                    .volume(volume)
                    .build());
        }
        return result;
    }

    /**
     * 将复权因子列表转为 Map<tradeDate, adjFactor>
     */
    public static Map<String, BigDecimal> buildAdjMap(List<AdjFactorDO> adjFactors) {
        return adjFactors.stream()
                .collect(Collectors.toMap(
                        AdjFactorDO::getTradeDate,
                        AdjFactorDO::getAdjFactor,
                        (a, b) -> b));
    }

    /**
     * 周分组key：该自然周的周五日期
     */
    static String weekGroupKey(String tradeDate) {
        LocalDate date = LocalDate.parse(tradeDate, DATE_FMT);
        return date.plusDays(5 - date.getDayOfWeek().getValue()).format(DATE_FMT);
    }

    /**
     * 月分组key：该自然月的最后一天
     */
    static String monthGroupKey(String tradeDate) {
        LocalDate date = LocalDate.parse(tradeDate, DATE_FMT);
        return date.withDayOfMonth(date.lengthOfMonth()).format(DATE_FMT);
    }
}
