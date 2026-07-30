package com.arthur.stock.event.listener;

import com.arthur.stock.event.DataBatchReadyEvent;
import com.arthur.stock.service.FactorSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 因子预计算监听器：监听 {@link DataBatchReadyEvent}（数据批次就绪后触发），
 * 对最新交易日的全市场股票预计算白名单内技术面因子并落库 factor_snapshot。
 * <p>
 * 从原 {@code task/FactorSnapshotTask} 迁移至 event/listener 包（事件消费者集中存放）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorSnapshotListener {

    private final FactorSnapshotService factorSnapshotService;

    @EventListener
    @Async("precomputeExecutor")
    public void onBatchReady(DataBatchReadyEvent event) {
        try {
            computeDaily();
        } catch (Exception e) {
            log.error("[FactorSnapshot] tradeDate={} 执行失败", event.getTradeDate(), e);
        }
    }

    public void computeDaily() {
        log.info("===== FactorSnapshotListener start =====");
        try {
            int n = factorSnapshotService.computeForLatestTradeDate();
            log.info("===== FactorSnapshotListener done, rows={} =====", n);
        } catch (Exception e) {
            log.error("FactorSnapshotListener 失败", e);
        }
    }
}
