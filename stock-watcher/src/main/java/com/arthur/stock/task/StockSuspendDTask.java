package com.arthur.stock.task;

import com.arthur.stock.service.StockSuspendDService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 停复牌信息定时同步。
 * <p>
 * 每日 16:35 增量；每月 1 号 22:00 全量回补（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSuspendDTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockSuspendDService stockSuspendDService;

    /** 每日 16:35 增量 */
    @Scheduled(cron = "0 35 16 * * ?")
    public void dailyIncremental() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("===== StockSuspendDTask daily start: {} =====", today);
        try {
            int n = stockSuspendDService.fetchAndSaveIncremental(today);
            log.info("StockSuspendDTask daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockSuspendDTask daily failed", e);
        }
    }

    /** 每月 1 号 22:00 全量 */
    @Scheduled(cron = "0 0 22 1 * *")
    public void monthlyFull() {
        log.info("===== StockSuspendDTask full start =====");
        try {
            int n = stockSuspendDService.fetchAndSaveAll();
            log.info("StockSuspendDTask full done: {} records", n);
        } catch (Exception e) {
            log.error("StockSuspendDTask full failed", e);
        }
    }
}
