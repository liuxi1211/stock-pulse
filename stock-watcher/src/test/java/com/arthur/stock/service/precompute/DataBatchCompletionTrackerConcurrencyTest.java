package com.arthur.stock.service.precompute;

import com.arthur.stock.event.DataBatchReadyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 数据批次完成追踪器并发测试（spec 026 Phase D8）。
 * <p>
 * 用 {@link CountDownLatch} 屏障让 4 个线程同时调用 {@code reportCompletion}，
 * 验证 {@link DataBatchCompletionTracker} 的 ConcurrentHashMap + fired 标志位
 * 在并发下只发布 <b>1 次</b> {@link DataBatchReadyEvent}。
 * <p>
 * 纯 Mockito，不启动 Spring、不连 DB。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataBatchCompletionTrackerConcurrencyTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** spec 指定的 4 个核心任务 taskKey */
    private static final String T1 = "BATCH_1600";
    private static final String T2 = "BATCH_1630_DAILY_BASIC";
    private static final String T3 = "BATCH_1630_MONEYFLOW";
    private static final String T4 = "BATCH_1630_INDEX_DAILY";
    private static final String TRADE_DATE = "20260729";

    // ==================== D8.1 ====================

    /**
     * 4 任务 CountDownLatch 同步触发 reportCompletion，验证事件只发布 1 次。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>主线程创建一个 {@code startLatch}（屏障），4 个工作线程在其上 await；</li>
     *   <li>主线程 {@code startLatch.countDown()} × 4 释放所有工作线程，使其尽可能同时调用 reportCompletion；</li>
     *   <li>用 {@link AtomicInteger} 计数 publishEvent 调用次数（替代 verify(times) 的最终态校验，
     *       便于在线程内捕获）；</li>
     *   <li>用 {@code doneLatch} 等待所有工作线程退出，再用 {@code verify(eventPublisher, timeout(2000))}
     *       兜底等待异步可见性。</li>
     * </ul>
     */
    @Test
    void D8_1_4任务并发报告事件只发布1次() throws InterruptedException {
        DataBatchCompletionTracker tracker = new DataBatchCompletionTracker(eventPublisher);

        AtomicInteger publishCount = new AtomicInteger(0);
        doAnswer(inv -> {
            publishCount.incrementAndGet();
            return null;
        }).when(eventPublisher).publishEvent(any(DataBatchReadyEvent.class));

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // startLatch 屏障：所有线程准备好后统一放行
        CountDownLatch startLatch = new CountDownLatch(1);
        // doneLatch 等待所有线程完成
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<String> taskKeys = List.of(T1, T2, T3, T4);
        for (int i = 0; i < threadCount; i++) {
            final String taskKey = taskKeys.get(i);
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tracker.reportCompletion(taskKey, TRADE_DATE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 释放屏障，4 个线程并发调用 reportCompletion
        startLatch.countDown();
        boolean allDone = doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(allDone)
                .as("4 个工作线程应在 5s 内完成")
                .isTrue();

        // 兜底等待事件发布的内存可见性
        verify(eventPublisher, timeout(2000).atLeast(0)).publishEvent(any(DataBatchReadyEvent.class));

        assertThat(publishCount.get())
                .as("并发下 DataBatchReadyEvent 应只发布 1 次")
                .isEqualTo(1);
    }

    // ==================== D8.2 ====================

    /**
     * 并发重复报告同 taskKey（4 线程同时报 T1），事件不应发布（未收齐 4 个不同 taskKey）。
     * <p>
     * 验证 ConcurrentHashMap.newKeySet 的去重在并发下正确：4 次同 taskKey 的报告
     * 只会留下 1 个 entry，且未收齐 4 个 EXPECTED_TASKS，所以 fired 不触发。
     */
    @Test
    void D8_2_并发重复同taskKey不发布事件() throws InterruptedException {
        DataBatchCompletionTracker tracker = new DataBatchCompletionTracker(eventPublisher);

        AtomicInteger publishCount = new AtomicInteger(0);
        doAnswer(inv -> {
            publishCount.incrementAndGet();
            return null;
        }).when(eventPublisher).publishEvent(any(DataBatchReadyEvent.class));

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 4 个线程全部报告 T1（同一 taskKey）
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tracker.reportCompletion(T1, TRADE_DATE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean allDone = doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(allDone).isTrue();

        // 同 taskKey 4 次报告只算 1 个任务完成，未收齐 4 个 EXPECTED_TASKS，不发布事件
        assertThat(publishCount.get())
                .as("4 个线程并发重复同一 taskKey，未收齐 4 个任务，不应发布事件")
                .isEqualTo(0);
    }

    // ==================== D8.3 ====================

    /**
     * 并发收齐 4 任务后，第 5 个线程重复报告，事件仍只发布 1 次（fired 防重复）。
     * <p>
     * 验证 fired 标志位的并发可见性：第 5 个线程在 fired=true 后调用 reportCompletion，
     * 不应再次触发 publishEvent。
     */
    @Test
    void D8_3_收齐后并发重复报告不二次发布() throws InterruptedException {
        DataBatchCompletionTracker tracker = new DataBatchCompletionTracker(eventPublisher);

        AtomicInteger publishCount = new AtomicInteger(0);
        doAnswer(inv -> {
            publishCount.incrementAndGet();
            return null;
        }).when(eventPublisher).publishEvent(any(DataBatchReadyEvent.class));

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 前 4 个线程报告 4 个不同 taskKey，第 5 个线程重复 T1
        List<String> taskKeys = new ArrayList<>();
        taskKeys.add(T1);
        taskKeys.add(T2);
        taskKeys.add(T3);
        taskKeys.add(T4);
        taskKeys.add(T1); // 第 5 个重复

        for (int i = 0; i < threadCount; i++) {
            final String taskKey = taskKeys.get(i);
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tracker.reportCompletion(taskKey, TRADE_DATE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean allDone = doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(allDone).isTrue();

        verify(eventPublisher, timeout(2000).atLeast(0)).publishEvent(any(DataBatchReadyEvent.class));

        assertThat(publishCount.get())
                .as("收齐 4 任务后第 5 个重复报告，fired 防重复应保证事件只发布 1 次")
                .isEqualTo(1);
    }
}
