package com.arthur.stock.event;

import com.arthur.stock.service.precompute.AbstractPrecomputeJob;
import com.arthur.stock.service.precompute.PrecomputeJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 预计算事件分发器测试（spec 026 Phase D4）。
 * <p>
 * 用同步 Executor（{@code Runnable::run}）使 {@code CompletableFuture.runAsync} 同步执行，
 * 便于确定性验证 Job 提交 / 失败隔离 / 完整性校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrecomputeEventDispatcherTest {

    /** 同步 Executor：runAsync 提交后立即在当前线程执行 */
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    @Mock
    private PrecomputeJob job1;
    @Mock
    private PrecomputeJob job2;

    // ==================== D4.1 ====================

    @Test
    void D4_1_发布事件后两个Job并发提交都被调用() {
        org.mockito.Mockito.when(job1.name()).thenReturn("Job1");
        org.mockito.Mockito.when(job2.name()).thenReturn("Job2");

        PrecomputeEventDispatcher dispatcher = new PrecomputeEventDispatcher(
                List.of(job1, job2), SYNC_EXECUTOR);

        DataBatchReadyEvent event = new DataBatchReadyEvent(this, "20260729", "SCHEDULED");
        dispatcher.onBatchReady(event);

        verify(job1, timeout(2000)).precompute(eq("20260729"));
        verify(job2, timeout(2000)).precompute(eq("20260729"));
    }

    // ==================== D4.2 ====================

    @Test
    void D4_2_失败隔离一个Job抛异常不影响另一个Job执行() {
        org.mockito.Mockito.when(job1.name()).thenReturn("FailingJob");
        org.mockito.Mockito.when(job2.name()).thenReturn("NormalJob");
        // job1 抛异常
        doThrow(new RuntimeException("job1 boom")).when(job1).precompute(any());

        PrecomputeEventDispatcher dispatcher = new PrecomputeEventDispatcher(
                List.of(job1, job2), SYNC_EXECUTOR);

        DataBatchReadyEvent event = new DataBatchReadyEvent(this, "20260729", "SCHEDULED");
        dispatcher.onBatchReady(event);

        // job1 被调用（但异常被吞）
        verify(job1, timeout(2000)).precompute(eq("20260729"));
        // job2 仍被执行（失败隔离）
        verify(job2, timeout(2000)).precompute(eq("20260729"));
    }

    // ==================== D4.3 ====================

    /**
     * 数据完整性校验：source=SCHEDULED_PARTIAL 时 Job 应跳过预计算打 WARN（spec 026 Task F1 / B6.4）。
     * <p>
     * 用真实的 {@link AbstractPrecomputeJob} 子类（{@link DataNotReadyJob}），其 {@code isDataReady}
     * 固定返回 false，验证 {@code precompute} 模板在 {@code doPrecompute} 前置校验失败时跳过整个预计算
     * （不调 doPrecompute、不写缓存、不 evict）。
     * <p>
     * 注：校验逻辑在 {@link AbstractPrecomputeJob#precompute(String)} 模板内执行，
     * 对所有 source 生效；本测试用 SCHEDULED_PARTIAL 场景验证，逻辑与 SCHEDULED 一致。
     */
    @Test
    void D4_3_SCHEDULED_PARTIAL时Job跳过预计算() {
        CacheManager cacheManager = new CaffeineCacheManager();
        DataNotReadyJob job = new DataNotReadyJob(cacheManager);

        PrecomputeEventDispatcher dispatcher = new PrecomputeEventDispatcher(
                List.of(job), SYNC_EXECUTOR);

        DataBatchReadyEvent event = new DataBatchReadyEvent(this, "20260729", "SCHEDULED_PARTIAL");
        dispatcher.onBatchReady(event);

        // 期望 Job 跳过预计算（isDataReady 返回 false 时不调 doPrecompute）
        assertThat(job.doPrecomputeCalled.get()).isFalse();
        // 缓存未写入
        org.springframework.cache.Cache cache = cacheManager.getCache("testCache");
        assertThat(cache).isNotNull();
        assertThat(cache.get("20260729")).isNull();
        assertThat(cache.get("latest")).isNull();
    }

    /**
     * 测试用 {@link AbstractPrecomputeJob} 子类：{@code isDataReady} 固定返回 false，
     * 用于验证模板方法的完整性校验跳过逻辑。
     */
    private static class DataNotReadyJob extends AbstractPrecomputeJob {
        final AtomicBoolean doPrecomputeCalled = new AtomicBoolean(false);

        DataNotReadyJob(CacheManager cacheManager) {
            super(cacheManager);
        }

        @Override
        public String name() {
            return "DataNotReadyJob";
        }

        @Override
        protected boolean isDataReady(String tradeDate) {
            return false;
        }

        @Override
        protected void doPrecompute(String tradeDate) {
            doPrecomputeCalled.set(true);
            // 若被调用，写入缓存（用于断言"未写缓存"）
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName());
            if (cache != null) {
                cache.put("20260729", "should_not_be_written");
                cache.put("latest", "should_not_be_written");
            }
        }

        @Override
        protected String cacheName() {
            return "testCache";
        }

        @Override
        protected List<String> cacheKeys(String tradeDate) {
            return List.of("20260729", "latest");
        }
    }
}
