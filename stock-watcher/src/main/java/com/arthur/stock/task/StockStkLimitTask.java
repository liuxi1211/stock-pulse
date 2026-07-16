package com.arthur.stock.task;

import com.arthur.stock.service.StockStkLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 涨跌停价定时同步。
 * <p>
 * 每日 16:40 增量；每月 1 号 22:30 全量回补（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockStkLimitTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockStkLimitService stockStkLimitService;

    /** 每日 16:40 增量 */
    @Scheduled(cron = "0 40 16 * * ?")
    public void dailyIncremental() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("===== StockStkLimitTask daily start: {} =====", today);
        try {
            int n = stockStkLimitService.fetchAndSaveIncremental(today);
            log.info("StockStkLimitTask daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockStkLimitTask daily failed", e);
        }
    }

    /** 每月 1 号 22:30 全量 */
    @Scheduled(cron = "0 30 22 1 * *")
    public void monthlyFull() {
        log.info("===== StockStkLimitTask full start =====");
        try {
            int n = stockStkLimitService.fetchAndSaveAll();
            log.info("StockStkLimitTask full done: {} records", n);
        } catch (Exception e) {
            log.error("StockStkLimitTask full failed", e);
        }
    }
}
