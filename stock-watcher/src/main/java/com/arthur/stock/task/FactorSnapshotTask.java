package com.arthur.stock.task;

import com.arthur.stock.service.FactorSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 因子预计算定时任务：每日 16:30（晚于 DailyUpdateTask 16:00 与 BasicDataTask 16:10），
 * 对最新交易日的全市场股票预计算白名单内技术面因子并落库 factor_snapshot。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorSnapshotTask {

    private final FactorSnapshotService factorSnapshotService;

    @Scheduled(cron = "0 30 16 * * MON-FRI")
    public void computeDaily() {
        log.info("===== FactorSnapshotTask start =====");
        try {
            int n = factorSnapshotService.computeForLatestTradeDate();
            log.info("===== FactorSnapshotTask done, rows={} =====", n);
        } catch (Exception e) {
            log.error("FactorSnapshotTask 失败", e);
        }
    }
}
