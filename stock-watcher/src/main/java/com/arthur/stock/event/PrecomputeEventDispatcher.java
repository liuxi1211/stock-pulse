package com.arthur.stock.event;

import com.arthur.stock.service.precompute.PrecomputeJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 数据批次就绪事件分发器：监听 {@link DataBatchReadyEvent}，
 * 将所有 {@link PrecomputeJob} 并发提交到 {@code precomputeExecutor} 执行。
 * <p>
 * <b>不区分 source</b>：SCHEDULED_PARTIAL / SCHEDULED_TIMEOUT 时，各 Job 内部自行做数据完整性校验
 * （在 {@link com.arthur.stock.service.precompute.AbstractPrecomputeJob#isDataReady(String)} 内实现，
 * 由 {@code AbstractPrecomputeJob.precompute} 模板调用），不由本 Dispatcher 判断。
 * <p>
 * <b>执行模型</b>：每个 Job 包裹在独立 {@link CompletableFuture#runAsync(Runnable, Executor)} 中，
 * 通过 {@code precomputeExecutor} 线程池并行执行；{@code allOf().join()} 等待全部完成（任一异常已被
 * 包裹的 try/catch 吞掉并打 ERROR 日志，不会中断其他 Job）。
 *
 * @see DataBatchReadyEvent
 * @see PrecomputeJob
 */
@Component
@Slf4j
public class PrecomputeEventDispatcher {

    private final List<PrecomputeJob> jobs;
    private final Executor precomputeExecutor;

    public PrecomputeEventDispatcher(List<PrecomputeJob> jobs,
                                     @Qualifier("precomputeExecutor") Executor precomputeExecutor) {
        this.jobs = jobs;
        this.precomputeExecutor = precomputeExecutor;
    }

    @EventListener
    @Async("precomputeExecutor")
    public void onBatchReady(DataBatchReadyEvent event) {
        log.info("[PrecomputeDispatcher] 收到批次事件 tradeDate={} source={}, 触发 {} 个 Job",
                event.getTradeDate(), event.getSource(), jobs.size());
        List<CompletableFuture<Void>> futures = jobs.stream()
                .map(job -> CompletableFuture.runAsync(() -> {
                    try {
                        job.precompute(event.getTradeDate());
                    } catch (Exception e) {
                        log.error("[Precompute][{}] tradeDate={} 执行失败",
                                job.name(), event.getTradeDate(), e);
                    }
                }, precomputeExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("[PrecomputeDispatcher] 全部 Job 完成 tradeDate={}", event.getTradeDate());
    }
}
