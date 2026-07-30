package com.arthur.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 预计算专用线程池配置。
 * <p>
 * 本线程池供 <b>7 个 PrecomputeJob</b> + {@code FactorSnapshotTask} + {@code ScreenLockTrackingTask} 使用，
 * 与 {@link ScreenerAsyncConfig} 的选股/回测线程池隔离，避免预计算占用资源影响交互查询。
 * <p>
 * 拒绝策略：{@code CallerRunsPolicy}——队列满时由调用线程兜底同步执行（不丢任务），
 * 与既有 {@code screenerExecutor}/{@code backtestExecutor} 保持一致。
 */
@Configuration
@EnableAsync
public class PrecomputeAsyncConfig {

    /**
     * 预计算主线程池：7 个 Job 并行 + 选股锁跟踪。
     * core=4 / max=8 / queue=20，线程名前缀 {@code precompute-}。
     */
    @Bean("precomputeExecutor")
    public Executor precomputeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("precompute-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 因子快照线程池：DB 写入密集，并行收益有限，core=1 / max=2 / queue=10，
     * 线程名前缀 {@code factor-snapshot-}。
     */
    @Bean("factorSnapshotExecutor")
    public Executor factorSnapshotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("factor-snapshot-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
