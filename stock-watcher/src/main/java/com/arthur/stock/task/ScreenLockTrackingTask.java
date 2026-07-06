package com.arthur.stock.task;

import com.arthur.stock.model.ScreenLockDO;
import com.arthur.stock.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 选股锁定追踪收益定时任务（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 每日收盘后（16:30，与 {@link DailyUpdateTask} 的 16:00 错开，
 * 确保当日 daily_quote 已更新）扫描所有 status="TRACKING" 的 screen_lock 记录，
 * 计算锁定组合在 5/10/20 交易日后的等权组合收益率 + 沪深300基准同期收益率。
 * <p>
 * 每条 lock 单独 try/catch，单条失败不影响其他。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenLockTrackingTask {

    private final ScreenerService screenerService;

    /**
     * 每日 16:30 执行追踪收益计算。
     */
    @Scheduled(cron = "0 30 16 * * ?")
    public void trackLocks() {
        log.info("===== ScreenLock tracking task started =====");
        List<ScreenLockDO> locks;
        try {
            locks = screenerService.listTrackingLocks();
        } catch (Exception e) {
            log.error("Failed to list TRACKING locks", e);
            return;
        }

        if (locks == null || locks.isEmpty()) {
            log.info("No TRACKING locks to process, finished.");
            return;
        }

        int ok = 0;
        int fail = 0;
        for (ScreenLockDO lock : locks) {
            try {
                screenerService.applyTracking(lock);
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("Failed to apply tracking for lock={} (resultId={}): {}",
                        lock.getId(), lock.getResultId(), e.getMessage(), e);
            }
        }

        log.info("===== ScreenLock tracking task finished: total={}, ok={}, fail={} =====",
                locks.size(), ok, fail);
    }
}
