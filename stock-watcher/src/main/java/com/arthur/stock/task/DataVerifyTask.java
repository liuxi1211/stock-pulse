package com.arthur.stock.task;

import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.ListStatusEnum;
import com.arthur.stock.constant.TradeDayStatusEnum;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.service.DailyQuoteService;
import com.arthur.stock.service.StockBasicService;
import com.arthur.stock.service.TradeCalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据校验定时任务
 * <p>
 * 每天晚上 22:00 执行，仅校验最近 3 个月。
 * 先查本地数据库发现缺失，再调 Tushare 接口补充。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataVerifyTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeCalService tradeCalService;
    private final StockBasicService stockBasicService;
    private final DailyQuoteService dailyQuoteService;

    /**
     * 每天晚上22:00执行数据校验，检查最近3个月的交易日历和日线行情数据是否完整
     */
    @Scheduled(cron = "0 0 22 * * ?")
    public void verify() {
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusMonths(3).format(DATE_FMT);

        log.info("===== Data verify task started ({} ~ {}) =====", startDate, endDate);

        try {
            verifyTradeCal(startDate, endDate);
        } catch (Exception e) {
            log.error("Failed to verify trade_cal", e);
        }

        try {
            verifyDailyQuotes(startDate, endDate);
        } catch (Exception e) {
            log.error("Failed to verify daily quotes", e);
        }

        log.info("===== Data verify task finished =====");
    }

    /**
     * 核对本地 trade_cal，生成3个月完整日期列表，找出缺失的日期段再调接口补充
     */
    private void verifyTradeCal(String startDate, String endDate) {
        log.info("[Verify 1] Checking trade_cal from {} to {}", startDate, endDate);

        for (ExchangeEnum ex : List.of(ExchangeEnum.SSE, ExchangeEnum.SZSE)) {
            List<TradeCalDTO> localCals = tradeCalService.queryLocal(ex.getCode(), startDate, endDate, null);
            Set<String> localDates = localCals.stream()
                    .map(TradeCalDTO::getCalDate)
                    .collect(Collectors.toSet());

            List<String> missingDates = new ArrayList<>();
            LocalDate start = LocalDate.parse(startDate, DATE_FMT);
            LocalDate end = LocalDate.parse(endDate, DATE_FMT);
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                String dateStr = d.format(DATE_FMT);
                if (!localDates.contains(dateStr)) {
                    missingDates.add(dateStr);
                }
            }

            if (missingDates.isEmpty()) {
                log.info("trade_cal {} is complete, no gaps", ex.getCode());
                continue;
            }

            log.info("Found {} missing dates in trade_cal {}, supplementing from Tushare", missingDates.size(), ex.getCode());
            tradeCalService.fetchAndSaveTradeCal(ex.getCode(), startDate, endDate);
        }
    }

    /**
     * 根据交易日历和股票上市日期核对 daily，遗漏才调接口补充。
     * 只核对该交易日之前已上市的股票，排除未上市股票。
     */
    private void verifyDailyQuotes(String startDate, String endDate) {
        log.info("[Verify 2] Checking daily quotes from {} to {}", startDate, endDate);

        // 1. 获取所有在市股票及其上市日期
        List<StockBasicDTO> activeStocks = stockBasicService.queryLocal(null, null, null, ListStatusEnum.LISTED.getCode());
        if (activeStocks.isEmpty()) {
            log.info("No active stocks found in stock_basic");
            return;
        }

        // 2. 获取交易日历
        List<TradeCalDTO> tradeDays = tradeCalService.queryLocal(ExchangeEnum.SSE.getCode(), startDate, endDate, TradeDayStatusEnum.OPEN.getCode());
        if (tradeDays.isEmpty()) {
            log.info("No trading days found in trade_cal");
            return;
        }

        // 3. 计算每个交易日应该有多少只股票有数据（上市日期 <= 交易日）
        Map<String, Long> expectedCounts = new LinkedHashMap<>();
        for (TradeCalDTO day : tradeDays) {
            long count = activeStocks.stream()
                    .filter(s -> s.getListDate() != null && s.getListDate().compareTo(day.getCalDate()) <= 0)
                    .count();
            expectedCounts.put(day.getCalDate(), count);
        }

        // 4. 查询本地 daily_quote 中每个交易日实际的股票数量
        Map<String, Integer> actualCounts = dailyQuoteService.getTradeDateStockCounts(startDate, endDate);

        // 5. 对比找出缺失的交易日
        List<String> missingDates = expectedCounts.entrySet().stream()
                .filter(e -> {
                    int actual = actualCounts.getOrDefault(e.getKey(), 0);
                    return actual < e.getValue();
                })
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        if (missingDates.isEmpty()) {
            log.info("All {} trading days have complete daily data, no gaps", tradeDays.size());
            return;
        }

        log.info("Found {} trading days with incomplete data: {}", missingDates.size(), missingDates);
        for (String date : missingDates) {
            try {
                log.info("Supplementing daily data for {}", date);
                dailyQuoteService.fetchAndSaveByTradeDate(date);
            } catch (Exception e) {
                log.error("Failed to supplement daily data for {}", date, e);
            }
        }
    }
}
