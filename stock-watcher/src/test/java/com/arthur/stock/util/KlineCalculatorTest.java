package com.arthur.stock.util;

import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.vo.KlineDataVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KlineCalculator 单元测试。
 * <p>
 * 纯单元测试，无 Spring 上下文。覆盖 adjustPrice / buildAdjMap / buildDailyKline /
 * buildWeeklyKline / buildMonthlyKline / forwardAdj 的核心分支与边界条件。
 * <p>
 * BigDecimal 值比较统一使用 compareTo（忽略 scale 差异）。
 */
class KlineCalculatorTest {

    /** BigDecimal 值比较（忽略 scale）。 */
    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual, "期望非 null 但实际为 null");
        assertEquals(0, expected.compareTo(actual),
                () -> "Expected " + expected + " but was " + actual);
    }

    // ==================== adjustPrice ====================

    @Test
    @DisplayName("adjustPrice_NONE_返回原始价格(4位小数)")
    void adjustPrice_NONE_返回原始价格() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.50"), new BigDecimal("1.2"), new BigDecimal("1.5"), "NONE");

        assertDecimalEquals(new BigDecimal("10.5000"), result);
    }

    @Test
    @DisplayName("adjustPrice_QFQ_公式正确(rawPrice×factor/latestFactor)")
    void adjustPrice_QFQ_公式正确() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.00"), new BigDecimal("1.2"), new BigDecimal("1.5"), "QFQ");

        // 10.00 × 1.2 / 1.5 = 8.0000
        assertDecimalEquals(new BigDecimal("8.0000"), result);
    }

    @Test
    @DisplayName("adjustPrice_QFQ_最新日因子等于最新因子_价格不变")
    void adjustPrice_QFQ_最新日因子等于最新因子_价格不变() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.00"), new BigDecimal("1.5"), new BigDecimal("1.5"), "QFQ");

        // factor = latestFactor -> rawPrice × 1 = 10.0000
        assertDecimalEquals(new BigDecimal("10.0000"), result);
    }

    @Test
    @DisplayName("adjustPrice_HFQ_公式正确(rawPrice×factor)")
    void adjustPrice_HFQ_公式正确() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.00"), new BigDecimal("1.2"), new BigDecimal("1.5"), "HFQ");

        // 10.00 × 1.2 = 12.0000
        assertDecimalEquals(new BigDecimal("12.0000"), result);
    }

    @Test
    @DisplayName("adjustPrice_QFQ_factor为null_返回null")
    void adjustPrice_QFQ_factor为null_返回null() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.00"), null, new BigDecimal("1.5"), "QFQ");

        assertNull(result);
    }

    @Test
    @DisplayName("adjustPrice_QFQ_latestFactor为零_返回null")
    void adjustPrice_QFQ_latestFactor为零_返回null() {
        BigDecimal result = KlineCalculator.adjustPrice(
                new BigDecimal("10.00"), new BigDecimal("1.2"), BigDecimal.ZERO, "QFQ");

        assertNull(result);
    }

    @Test
    @DisplayName("adjustPrice_非法adjustment_抛IllegalArgumentException")
    void adjustPrice_非法adjustment_抛异常() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                KlineCalculator.adjustPrice(
                        new BigDecimal("10.00"), new BigDecimal("1.2"), new BigDecimal("1.5"), "INVALID"));

        assertEquals("adjustment必须为QFQ、HFQ或NONE", ex.getMessage());
    }

    @Test
    @DisplayName("adjustPrice_rawPrice为null_返回null")
    void adjustPrice_rawPrice为null_返回null() {
        BigDecimal result = KlineCalculator.adjustPrice(
                null, new BigDecimal("1.2"), new BigDecimal("1.5"), "NONE");

        assertNull(result);
    }

    // ==================== buildAdjMap ====================

    @Test
    @DisplayName("buildAdjMap_正常构建")
    void buildAdjMap_正常构建() {
        List<AdjFactorDO> input = List.of(
                AdjFactorDO.builder().tradeDate("20240101").adjFactor(new BigDecimal("1.0")).build(),
                AdjFactorDO.builder().tradeDate("20240102").adjFactor(new BigDecimal("1.2")).build());

        Map<String, BigDecimal> result = KlineCalculator.buildAdjMap(input);

        assertEquals(2, result.size());
        assertDecimalEquals(new BigDecimal("1.0"), result.get("20240101"));
        assertDecimalEquals(new BigDecimal("1.2"), result.get("20240102"));
    }

    @Test
    @DisplayName("buildAdjMap_重复key保留后者")
    void buildAdjMap_重复key保留后者() {
        List<AdjFactorDO> input = List.of(
                AdjFactorDO.builder().tradeDate("20240101").adjFactor(new BigDecimal("1.0")).build(),
                AdjFactorDO.builder().tradeDate("20240101").adjFactor(new BigDecimal("1.5")).build());

        Map<String, BigDecimal> result = KlineCalculator.buildAdjMap(input);

        assertEquals(1, result.size());
        assertDecimalEquals(new BigDecimal("1.5"), result.get("20240101"));
    }

    @Test
    @DisplayName("buildAdjMap_过滤tradeDate或adjFactor为null的条目")
    void buildAdjMap_过滤null字段() {
        List<AdjFactorDO> input = List.of(
                AdjFactorDO.builder().tradeDate(null).adjFactor(new BigDecimal("1.0")).build(),
                AdjFactorDO.builder().tradeDate("20240101").adjFactor(null).build(),
                AdjFactorDO.builder().tradeDate("20240102").adjFactor(new BigDecimal("1.2")).build());

        Map<String, BigDecimal> result = KlineCalculator.buildAdjMap(input);

        assertEquals(1, result.size());
        assertDecimalEquals(new BigDecimal("1.2"), result.get("20240102"));
    }

    // ==================== buildDailyKline ====================

    @Test
    @DisplayName("buildDailyKline_QFQ_最新日价格等于原始价格")
    void buildDailyKline_QFQ_最新日价格等于原始价格() {
        List<DailyQuoteDO> dailies = List.of(
                DailyQuoteDO.builder().tradeDate("20240101")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("10.50")).vol(new BigDecimal("100")).build(),
                DailyQuoteDO.builder().tradeDate("20240102")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("10.50")).vol(new BigDecimal("200")).build(),
                DailyQuoteDO.builder().tradeDate("20240103")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("10.50")).vol(new BigDecimal("300")).build());
        Map<String, BigDecimal> adjMap = Map.of(
                "20240101", new BigDecimal("1.0"),
                "20240102", new BigDecimal("1.1"),
                "20240103", new BigDecimal("1.2"));

        List<KlineDataVO> result = KlineCalculator.buildDailyKline(dailies, adjMap, "QFQ");

        assertEquals(3, result.size());

        // latestFactor = 1.2 (最大 tradeDate 的因子)
        // Day 1: factor=1.0, 10×1.0/1.2=8.3333
        assertDecimalEquals(new BigDecimal("8.3333"), result.get(0).getOpen());
        // Day 2: factor=1.1, 10×1.1/1.2=9.1667
        assertDecimalEquals(new BigDecimal("9.1667"), result.get(1).getOpen());
        // Day 3: factor=1.2=latestFactor, 10×1.2/1.2=10 -> 价格不变
        assertDecimalEquals(new BigDecimal("10.0000"), result.get(2).getOpen());
        assertDecimalEquals(new BigDecimal("11.0000"), result.get(2).getHigh());
        assertDecimalEquals(new BigDecimal("9.0000"), result.get(2).getLow());
        assertDecimalEquals(new BigDecimal("10.5000"), result.get(2).getClose());

        // volume 直接取 daily.vol
        assertEquals(100L, result.get(0).getVolume());
        assertEquals(200L, result.get(1).getVolume());
        assertEquals(300L, result.get(2).getVolume());
    }

    @Test
    @DisplayName("buildDailyKline_NONE_价格不变")
    void buildDailyKline_NONE_价格不变() {
        List<DailyQuoteDO> dailies = List.of(
                DailyQuoteDO.builder().tradeDate("20240101")
                        .open(new BigDecimal("10.5")).high(new BigDecimal("11.2"))
                        .low(new BigDecimal("9.8")).close(new BigDecimal("10.7")).vol(new BigDecimal("100")).build(),
                DailyQuoteDO.builder().tradeDate("20240102")
                        .open(new BigDecimal("10.7")).high(new BigDecimal("11.5"))
                        .low(new BigDecimal("10.3")).close(new BigDecimal("11.0")).vol(new BigDecimal("200")).build());
        Map<String, BigDecimal> adjMap = Map.of(
                "20240101", new BigDecimal("1.0"),
                "20240102", new BigDecimal("1.0"));

        List<KlineDataVO> result = KlineCalculator.buildDailyKline(dailies, adjMap, "NONE");

        assertEquals(2, result.size());
        // OHLC = 原始值（4位小数）
        assertDecimalEquals(new BigDecimal("10.5"), result.get(0).getOpen());
        assertDecimalEquals(new BigDecimal("11.2"), result.get(0).getHigh());
        assertDecimalEquals(new BigDecimal("9.8"), result.get(0).getLow());
        assertDecimalEquals(new BigDecimal("10.7"), result.get(0).getClose());
        assertDecimalEquals(new BigDecimal("10.7"), result.get(1).getOpen());
        assertDecimalEquals(new BigDecimal("11.5"), result.get(1).getHigh());
        assertDecimalEquals(new BigDecimal("10.3"), result.get(1).getLow());
        assertDecimalEquals(new BigDecimal("11.0"), result.get(1).getClose());
    }

    @Test
    @DisplayName("buildDailyKline_空数据_返回空列表")
    void buildDailyKline_空数据_返回空列表() {
        List<KlineDataVO> result = KlineCalculator.buildDailyKline(
                List.of(), Map.of(), "QFQ");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildDailyKline_因子缺失_QFQ下价格为null被过滤")
    void buildDailyKline_因子缺失_过滤null条目() {
        List<DailyQuoteDO> dailies = List.of(
                DailyQuoteDO.builder().tradeDate("20240101")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("10.50")).vol(new BigDecimal("100")).build(),
                DailyQuoteDO.builder().tradeDate("20240102")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("10.50")).vol(new BigDecimal("200")).build());
        // adjMap 只有 20240101 的因子
        Map<String, BigDecimal> adjMap = Map.of("20240101", new BigDecimal("1.0"));

        List<KlineDataVO> result = KlineCalculator.buildDailyKline(dailies, adjMap, "QFQ");

        // 20240102 因子为 null -> QFQ 价格为 null -> 被过滤
        assertEquals(1, result.size());
        assertEquals("20240101", result.get(0).getDate());
    }

    // ==================== buildWeeklyKline ====================

    @Test
    @DisplayName("buildWeeklyKline_同周5天聚合为1条周线")
    void buildWeeklyKline_正确聚合() {
        // 2024-01-01(周一) ~ 2024-01-05(周五)，同一周
        List<DailyQuoteDO> dailies = List.of(
                DailyQuoteDO.builder().tradeDate("20240101")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("12.00"))
                        .low(new BigDecimal("8.00")).close(new BigDecimal("11.00")).vol(new BigDecimal("100")).build(),
                DailyQuoteDO.builder().tradeDate("20240102")
                        .open(new BigDecimal("11.00")).high(new BigDecimal("13.00"))
                        .low(new BigDecimal("9.00")).close(new BigDecimal("12.00")).vol(new BigDecimal("200")).build(),
                DailyQuoteDO.builder().tradeDate("20240103")
                        .open(new BigDecimal("12.00")).high(new BigDecimal("14.00"))
                        .low(new BigDecimal("7.00")).close(new BigDecimal("13.00")).vol(new BigDecimal("300")).build(),
                DailyQuoteDO.builder().tradeDate("20240104")
                        .open(new BigDecimal("13.00")).high(new BigDecimal("11.00"))
                        .low(new BigDecimal("6.00")).close(new BigDecimal("10.00")).vol(new BigDecimal("400")).build(),
                DailyQuoteDO.builder().tradeDate("20240105")
                        .open(new BigDecimal("10.00")).high(new BigDecimal("15.00"))
                        .low(new BigDecimal("5.00")).close(new BigDecimal("14.00")).vol(new BigDecimal("500")).build());
        Map<String, BigDecimal> adjMap = Map.of(
                "20240101", new BigDecimal("1.0"),
                "20240102", new BigDecimal("1.0"),
                "20240103", new BigDecimal("1.0"),
                "20240104", new BigDecimal("1.0"),
                "20240105", new BigDecimal("1.0"));

        List<KlineDataVO> result = KlineCalculator.buildWeeklyKline(dailies, adjMap, "NONE");

        assertEquals(1, result.size());
        KlineDataVO week = result.get(0);
        // date = 最后一天(周五)日期
        assertEquals("20240105", week.getDate());
        // open = 首条 open
        assertDecimalEquals(new BigDecimal("10.00"), week.getOpen());
        // high = 5 天最高
        assertDecimalEquals(new BigDecimal("15.00"), week.getHigh());
        // low = 5 天最低
        assertDecimalEquals(new BigDecimal("5.00"), week.getLow());
        // close = 末条 close
        assertDecimalEquals(new BigDecimal("14.00"), week.getClose());
        // volume = 5 天合计
        assertEquals(1500L, week.getVolume());
    }

    @Test
    @DisplayName("buildWeeklyKline_跨周10天按周分组")
    void buildWeeklyKline_跨周聚合() {
        // 2024-01-01(周一) ~ 2024-01-05(周五) = 第1周
        // 2024-01-08(周一) ~ 2024-01-12(周五) = 第2周
        List<DailyQuoteDO> dailies = List.of(
                d("20240101", "10", "11", "9", "10", "100"),
                d("20240102", "10", "11", "9", "10", "100"),
                d("20240103", "10", "11", "9", "10", "100"),
                d("20240104", "10", "11", "9", "10", "100"),
                d("20240105", "10", "11", "9", "10", "100"),
                d("20240108", "20", "21", "19", "20", "200"),
                d("20240109", "20", "21", "19", "20", "200"),
                d("20240110", "20", "21", "19", "20", "200"),
                d("20240111", "20", "21", "19", "20", "200"),
                d("20240112", "20", "21", "19", "20", "200"));
        Map<String, BigDecimal> adjMap = Map.of(
                "20240101", new BigDecimal("1.0"),
                "20240102", new BigDecimal("1.0"),
                "20240103", new BigDecimal("1.0"),
                "20240104", new BigDecimal("1.0"),
                "20240105", new BigDecimal("1.0"),
                "20240108", new BigDecimal("1.0"),
                "20240109", new BigDecimal("1.0"),
                "20240110", new BigDecimal("1.0"),
                "20240111", new BigDecimal("1.0"),
                "20240112", new BigDecimal("1.0"));

        List<KlineDataVO> result = KlineCalculator.buildWeeklyKline(dailies, adjMap, "NONE");

        // 2 周 -> 2 条周线
        assertEquals(2, result.size());
        // 第1周 date = 20240105, volume = 500
        assertEquals("20240105", result.get(0).getDate());
        assertEquals(500L, result.get(0).getVolume());
        // 第2周 date = 20240112, volume = 1000
        assertEquals("20240112", result.get(1).getDate());
        assertEquals(1000L, result.get(1).getVolume());
    }

    // ==================== buildMonthlyKline ====================

    @Test
    @DisplayName("buildMonthlyKline_同月3天聚合为1条月线")
    void buildMonthlyKline_正确聚合() {
        // 2024年1月内的3个交易日
        List<DailyQuoteDO> dailies = List.of(
                d("20240110", "10", "12", "8", "11", "100"),
                d("20240115", "11", "13", "9", "12", "200"),
                d("20240131", "12", "14", "7", "13", "300"));
        Map<String, BigDecimal> adjMap = Map.of(
                "20240110", new BigDecimal("1.0"),
                "20240115", new BigDecimal("1.0"),
                "20240131", new BigDecimal("1.0"));

        List<KlineDataVO> result = KlineCalculator.buildMonthlyKline(dailies, adjMap, "NONE");

        assertEquals(1, result.size());
        KlineDataVO month = result.get(0);
        // date = 最后一天日期
        assertEquals("20240131", month.getDate());
        // open = 首条 open
        assertDecimalEquals(new BigDecimal("10"), month.getOpen());
        // high = 最高
        assertDecimalEquals(new BigDecimal("14"), month.getHigh());
        // low = 最低
        assertDecimalEquals(new BigDecimal("7"), month.getLow());
        // close = 末条 close
        assertDecimalEquals(new BigDecimal("13"), month.getClose());
        // volume = 合计
        assertEquals(600L, month.getVolume());
    }

    // ==================== forwardAdj ====================

    @Test
    @DisplayName("forwardAdj_正常计算(2位小数)")
    void forwardAdj_正常计算() {
        BigDecimal result = KlineCalculator.forwardAdj(
                new BigDecimal("10.00"), new BigDecimal("1.2"));

        // 10.00 × 1.2 = 12.00 (2位小数)
        assertDecimalEquals(new BigDecimal("12.00"), result);
    }

    @Test
    @DisplayName("forwardAdj_null参数_返回null")
    void forwardAdj_null参数_返回null() {
        assertNull(KlineCalculator.forwardAdj(null, new BigDecimal("1.2")));
        assertNull(KlineCalculator.forwardAdj(new BigDecimal("10.00"), null));
    }

    // ==================== 辅助方法 ====================

    /** 快速构造 DailyQuoteDO（OHLC+v 为字符串，内部转 BigDecimal）。 */
    private static DailyQuoteDO d(String date, String open, String high,
                                  String low, String close, String vol) {
        return DailyQuoteDO.builder()
                .tradeDate(date)
                .open(new BigDecimal(open))
                .high(new BigDecimal(high))
                .low(new BigDecimal(low))
                .close(new BigDecimal(close))
                .vol(new BigDecimal(vol))
                .build();
    }
}
