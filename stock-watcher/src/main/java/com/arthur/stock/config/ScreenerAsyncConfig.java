package com.arthur.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 选股中心专用线程池配置。
 * <p>
 * 用于 {@code ScreenerServiceImpl#buildCandidates} 内 candidates 的并行拼装：
 * OHLCV 已批量查回后，CPU 密集 + 内存映射的 DTO 构造可并行化，把串行秒级降到并行百毫秒级。
 * <p>
 * 不在 Service 方法上加 {@code @Async}，仅在 {@code runPlan} 内显式
 * {@code CompletableFuture.supplyAsync(..., screenerExecutor)}，保持同步返回 VO 的契约。
 * <p>
 * 拒绝策略：{@code CallerRunsPolicy}——队列满时由调用线程兜底执行，避免任务丢失。
 */
@Configuration
@EnableAsync
public class ScreenerAsyncConfig {

    /** Bean 名：screenerExecutor，注入时用 @Qualifier("screenerExecutor") 或字段名匹配。 */
    @Bean("screenerExecutor")
    public Executor screenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("screener-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
