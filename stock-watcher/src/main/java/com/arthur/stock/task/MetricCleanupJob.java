package com.arthur.stock.task;

import com.arthur.stock.mapper.DataGovernanceMetricMapper;
import com.arthur.stock.mapper.DataPullLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据治理旧数据清理任务：每天凌晨 01:00 清理 3 个月前的数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricCleanupJob {

    private final DataGovernanceMetricMapper metricMapper;
    private final DataPullLogMapper pullLogMapper;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Scheduled(cron = "0 0 1 * * ?")
    public void cleanupOldData() {
        String cutoff = LocalDateTime.now().minusMonths(3).format(DATETIME_FMT);
        log.info("开始清理 3 个月前的数据治理数据, cutoff={}", cutoff);
        try {
            int metricDeleted = metricMapper.deleteOlderThan(cutoff);
            int logDeleted = pullLogMapper.deleteOlderThan(cutoff);
            log.info("数据清理完成: metric 删除 {} 条, pull_log 删除 {} 条", metricDeleted, logDeleted);
        } catch (Exception e) {
            log.error("数据清理失败", e);
        }
    }
}
