package com.arthur.stock.event.listener;

import com.arthur.stock.event.DataBatchReadyEvent;
import com.arthur.stock.model.ScreenLockDO;
import com.arthur.stock.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 选股锁定追踪收益监听器（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 监听 {@link DataBatchReadyEvent}（数据批次就绪后触发），
 * 扫描所有 status="TRACKING" 的 screen_lock 记录，
 * 计算锁定组合在 5/10/20 交易日后的等权组合收益率 + 沪深300基准同期收益率。
 * <p>
 * 从原 {@code task/ScreenLockTrackingTask} 迁移至 event/listener 包。每条 lock 单独 try/catch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenLockTrackingListener {

    private final ScreenerService screenerService;

    @EventListener
    @Async("precomputeExecutor")
    public void onBatchReady(DataBatchReadyEvent event) {
        try {
            trackLocks();
        } catch (Exception e) {
            log.error("[ScreenLockTracking] tradeDate={} 执行失败", event.getTradeDate(), e);
        }
    }

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
