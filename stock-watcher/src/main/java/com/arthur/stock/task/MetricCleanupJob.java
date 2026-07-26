package com.arthur.stock.task;

import com.arthur.stock.mapper.DataGovernanceMetricMapper;
import com.arthur.stock.mapper.DataPullLogMapper;
import jakarta.annotation.PostConstruct;
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

    /**
     * 应用启动时清理上次崩溃残留的 RUNNING 记录。
     * 超过 2 小时（匹配锁超时）的 RUNNING 记录视为残留，标记为 FAILED。
     */
    @PostConstruct
    public void cleanupStaleRunningOnStartup() {
        try {
            String staleCutoff = LocalDateTime.now().minusHours(2).format(DATETIME_FMT);
            String currentTime = LocalDateTime.now().format(DATETIME_FMT);
            int staleFixed = pullLogMapper.updateStaleRunningToFailed(staleCutoff, currentTime);
            if (staleFixed > 0) {
                log.warn("启动清理超时 RUNNING 记录: {} 条（可能因上次进程崩溃或异常退出）", staleFixed);
            }
        } catch (Exception e) {
            log.error("启动清理超时 RUNNING 记录失败", e);
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void cleanupOldData() {
        String cutoff = LocalDateTime.now().minusMonths(3).format(DATETIME_FMT);
        log.info("开始清理 3 个月前的数据治理数据, cutoff={}", cutoff);
        try {
            // 1. 清理超时的 RUNNING 记录（超过 2 小时仍未完成，匹配锁超时）
            String staleCutoff = LocalDateTime.now().minusHours(2).format(DATETIME_FMT);
            String currentTime = LocalDateTime.now().format(DATETIME_FMT);
            int staleFixed = pullLogMapper.updateStaleRunningToFailed(staleCutoff, currentTime);
            if (staleFixed > 0) {
                log.warn("清理超时 RUNNING 记录: {} 条（可能因进程崩溃或异常退出）", staleFixed);
            }

            // 2. 删除 3 个月前的旧记录
            int metricDeleted = metricMapper.deleteOlderThan(cutoff);
            int logDeleted = pullLogMapper.deleteOlderThan(cutoff);
            log.info("数据清理完成: metric 删除 {} 条, pull_log 删除 {} 条", metricDeleted, logDeleted);
        } catch (Exception e) {
            log.error("数据清理失败", e);
        }
    }
}
