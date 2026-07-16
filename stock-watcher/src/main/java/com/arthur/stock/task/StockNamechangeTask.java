package com.arthur.stock.task;

import com.arthur.stock.service.StockNamechangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 股票更名历史（ST 戴帽摘帽）定时同步。
 * <p>
 * 每日 16:30 增量；每季度首月 1 号 22:00 全量回补（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockNamechangeTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockNamechangeService stockNamechangeService;

    /** 每日 16:30 增量 */
    @Scheduled(cron = "0 30 16 * * ?")
    public void dailyIncremental() {
        String today = LocalDate.now().format(DATE_FMT);
        log.info("===== StockNamechangeTask daily start: {} =====", today);
        try {
            int n = stockNamechangeService.fetchAndSaveIncremental(today);
            log.info("StockNamechangeTask daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockNamechangeTask daily failed", e);
        }
    }

    /** 每季度首月 1 号 22:00 全量 */
    @Scheduled(cron = "0 0 22 1 1,4,7,10 *")
    public void quarterlyFull() {
        log.info("===== StockNamechangeTask full start =====");
        try {
            int n = stockNamechangeService.fetchAndSaveAll();
            log.info("StockNamechangeTask full done: {} records", n);
        } catch (Exception e) {
            log.error("StockNamechangeTask full failed", e);
        }
    }
}
