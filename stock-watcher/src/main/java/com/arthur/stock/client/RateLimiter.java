package com.arthur.stock.client;

import com.arthur.stock.config.TushareConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于滑动窗口的限流器，按接口名称独立计数。
 * 未配置的接口不做任何限流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final TushareConfig tushareConfig;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 在调用 apiName 接口前申请许可，若当前窗口内请求数已达上限则阻塞等待。
     * 未配置限流的接口直接通过。
     */
    public void acquire(String apiName) {
        Map<String, TushareConfig.RateLimitRule> rules = tushareConfig.getRateLimit();
        if (rules == null || !rules.containsKey(apiName)) {
            return;
        }

        TushareConfig.RateLimitRule rule = rules.get(apiName);
        Window window = windows.computeIfAbsent(apiName, k -> new Window());

        if (rule.getPermitsPerSecond() != null && rule.getPermitsPerSecond() > 0) {
            window.waitForPermit("second", rule.getPermitsPerSecond(), 1000L);
        }
        if (rule.getPermitsPerMinute() != null && rule.getPermitsPerMinute() > 0) {
            window.waitForPermit("minute", rule.getPermitsPerMinute(), 60_000L);
        }
    }

    /**
     * 单个接口的滑动窗口，按不同时间粒度独立维护时间戳队列。
     */
    private static class Window {
        private final ConcurrentLinkedDeque<Long> secondDeque = new ConcurrentLinkedDeque<>();
        private final ConcurrentLinkedDeque<Long> minuteDeque = new ConcurrentLinkedDeque<>();

        /**
         * 滑动窗口限流：移除过期时间戳后检查是否超限，超限则休眠等待。
         */
        void waitForPermit(String granularity, int maxPermits, long windowMillis) {
            ConcurrentLinkedDeque<Long> deque = granularity.equals("second") ? secondDeque : minuteDeque;
            while (true) {
                long now = System.currentTimeMillis();
                long windowStart = now - windowMillis;

                // 滑动：移除窗口外的旧时间戳
                while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                    deque.pollFirst();
                }

                if (deque.size() < maxPermits) {
                    deque.addLast(now);
                    return;
                }

                // 超限：计算需要等待的时间
                long oldestInWindow = deque.peekFirst();
                long waitMillis = oldestInWindow + windowMillis - now + 1;
                if (waitMillis > 0) {
                    log.debug("Rate limit hit for {} window (max={}): waiting {}ms", granularity, maxPermits, waitMillis);
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for rate limit permit", e);
                    }
                }
            }
        }
    }
}