package com.arthur.stock.service.precompute;

import com.arthur.stock.constant.DataFetchConstants;
import com.arthur.stock.event.DataBatchReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据批次完成追踪器：聚合 4 个核心数据更新任务的完成报告，
 * 当全部任务就绪后发布 {@link DataBatchReadyEvent}，并提供超时兜底机制。
 * <p>
 * 4 个核心任务（taskKey）——简化后由 {@code DataFetchTriggerEventListener} 在各子项拉取完成后上报：
 * <ul>
 *   <li>{@code BATCH_1600}：16:00 批次（交易日历/股票基础/日线/复权/分红/指数基础）完成后上报</li>
 *   <li>{@code BATCH_1630_DAILY_BASIC}：16:30 批次内 daily_basic 完成后上报</li>
 *   <li>{@code BATCH_1630_MONEYFLOW}：16:30 批次内资金流（moneyflow 等）完成后上报</li>
 *   <li>{@code BATCH_1630_INDEX_DAILY}：16:30 批次内指数日线完成后上报</li>
 * </ul>
 * source 取值：
 * <ul>
 *   <li>SCHEDULED：4 个任务均正常完成</li>
 *   <li>SCHEDULED_PARTIAL：有任务异常完成（hasError）</li>
 *   <li>SCHEDULED_TIMEOUT：超过 30 分钟未收齐，强制发布</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataBatchCompletionTracker {

    private final ApplicationEventPublisher eventPublisher;

    // key=tradeDate, value=BatchEntry
    private final ConcurrentHashMap<String, BatchEntry> completionMap = new ConcurrentHashMap<>();

    // 4 个核心数据更新任务的 taskKey（由 DataFetchTriggerEventListener 在子项完成后上报）
    private static final Set<String> EXPECTED_TASKS = Set.of(
        DataFetchConstants.TRACKER_BATCH_1600,
        DataFetchConstants.TRACKER_BATCH_1630_DAILY_BASIC,
        DataFetchConstants.TRACKER_BATCH_1630_MONEYFLOW,
        DataFetchConstants.TRACKER_BATCH_1630_INDEX_DAILY
    );

    // 任务正常完成
    public void reportCompletion(String taskKey, String tradeDate) {
        reportCompletion(taskKey, tradeDate, false);
    }

    // 任务异常完成（finally 块调用），hasError=true
    public void reportCompletion(String taskKey, String tradeDate, boolean hasError) {
        BatchEntry entry = completionMap.computeIfAbsent(tradeDate, k -> new BatchEntry());
        entry.completedTasks.add(taskKey);
        if (hasError) entry.hasError = true;
        if (!entry.fired && entry.completedTasks.containsAll(EXPECTED_TASKS)) {
            entry.fired = true;
            String source = entry.hasError ? "SCHEDULED_PARTIAL" : "SCHEDULED";
            eventPublisher.publishEvent(new DataBatchReadyEvent(this, tradeDate, source));
            completionMap.remove(tradeDate);
            log.info("[BatchTracker] tradeDate={} 收齐 4 个任务报告，发布 DataBatchReadyEvent(source={})", tradeDate, source);
        }
    }

    // 超时兜底调用
    public void forceFireOnTimeout(String tradeDate, Set<String> missingTasks) {
        BatchEntry entry = completionMap.get(tradeDate);
        if (entry != null && !entry.fired) {
            entry.fired = true;
            eventPublisher.publishEvent(new DataBatchReadyEvent(this, tradeDate, "SCHEDULED_TIMEOUT"));
            completionMap.remove(tradeDate);
            log.warn("[BatchTracker] tradeDate={} 超时强制发布（缺失任务：{}）", tradeDate, missingTasks);
        }
    }

    // 超时兜底定时检查（B14）
    @Scheduled(fixedDelay = 60000) // 每分钟检查一次
    public void checkTimeout() {
        Instant now = Instant.now();
        for (Map.Entry<String, BatchEntry> e : completionMap.entrySet()) {
            String tradeDate = e.getKey();
            BatchEntry entry = e.getValue();
            if (entry.fired) continue;
            Duration elapsed = Duration.between(entry.createdAt, now);
            if (elapsed.toMinutes() >= 30) {
                Set<String> missing = new HashSet<>(EXPECTED_TASKS);
                missing.removeAll(entry.completedTasks);
                forceFireOnTimeout(tradeDate, missing);
            }
        }
    }

    // BatchEntry 内部类
    private static class BatchEntry {
        final Set<String> completedTasks = ConcurrentHashMap.newKeySet();
        volatile boolean hasError = false;
        volatile boolean fired = false;
        final Instant createdAt = Instant.now(); // 用于超时检查
    }
}
