package com.arthur.stock.task;

import com.arthur.stock.service.DataGovernanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据治理定时检测任务：每天 22:00 执行全表质量检测
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataGovernanceCheckJob {

    private final DataGovernanceService dataGovernanceService;

    @Scheduled(cron = "0 0 22 * * ?")
    public void executeCheck() {
        log.info("===== 数据治理定时检测开始 =====");
        try {
            String batchId = dataGovernanceService.checkAllScheduled();
            log.info("数据治理定时检测完成, batchId={}", batchId);
        } catch (Exception e) {
            log.error("数据治理定时检测失败", e);
        }
        log.info("===== 数据治理定时检测结束 =====");
    }
}
