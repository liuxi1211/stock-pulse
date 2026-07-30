package com.arthur.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 数据拉取专用线程池配置。
 * <p>
 * 供 {@code DataFetchTriggerEventListener} 在收到 {@code DataFetchTriggerEvent} 后异步执行各批次
 * （16:00 / 16:30 / 16:40 / 20:00 / 半年）的数据拉取逻辑使用，与预计算/选股线程池隔离。
 * <p>
 * 拒绝策略：{@code CallerRunsPolicy}——队列满时由调用线程（定时调度线程）兜底同步执行，
 * 避免丢任务导致当日数据缺失。
 */
@Configuration
@EnableAsync
public class DataFetchAsyncConfig {

    /**
     * 数据拉取线程池：各批次串行触发，但批次内可能并发多张表。
     * core=2 / max=4 / queue=10，线程名前缀 {@code data-fetch-}。
     */
    @Bean("dataFetchExecutor")
    public Executor dataFetchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("data-fetch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
