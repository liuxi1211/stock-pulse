package com.arthur.stock.task;

import com.arthur.stock.service.DataInitService;
import com.arthur.stock.service.StockStkLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 涨跌停价定时同步。
 * <p>
 * 每日 16:40 增量（从 MAX(trade_date) 到今天，范围查询 + 分页）；每月 1 号 22:30 全量回补（按月迭代，幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockStkLimitTask {

    private final StockStkLimitService stockStkLimitService;
    private final DataInitService dataInitService;

    /** 每日 16:40 增量 */
    @Scheduled(cron = "0 40 16 * * ?")
    public void dailyIncremental() {
        log.info("===== StockStkLimitTask daily start =====");
        try {
            int n = stockStkLimitService.fetchAndSaveIncremental();
            log.info("StockStkLimitTask daily done: {} records", n);
        } catch (Exception e) {
            log.error("StockStkLimitTask daily failed", e);
        }
    }

    /** 每月 1 号 22:30 全量（委托 DataInitService 按月迭代拉取） */
    @Scheduled(cron = "0 30 22 1 * *")
    public void monthlyFull() {
        log.info("===== StockStkLimitTask full start =====");
        try {
            String taskId = dataInitService.fullRebuild(
                    com.arthur.stock.constant.InitStep.STK_LIMIT.getCode(), "SYSTEM");
            log.info("StockStkLimitTask full triggered: taskId={}", taskId);
        } catch (Exception e) {
            log.error("StockStkLimitTask full failed", e);
        }
    }
}
