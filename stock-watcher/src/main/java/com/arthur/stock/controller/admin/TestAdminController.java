package com.arthur.stock.controller.admin;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.event.DataBatchReadyEvent;
import com.arthur.stock.mapper.DataPullLogMapper;
import com.arthur.stock.service.PrecomputeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试管理后台控制器（仅在 test profile 下激活）。
 * <p>
 * 提供数据批次事件触发、指标清理、预计算全量触发、缓存查询与失效等运维端点，
 * 仅供集成测试与本地排错使用，生产环境不加载。
 */
@Profile("test")
@RestController
@RequestMapping("/admin/test")
@Slf4j
@RequiredArgsConstructor
public class TestAdminController {

    private final ApplicationEventPublisher eventPublisher;
    private final PrecomputeService precomputeService;
    private final CacheManager cacheManager;
    private final DataPullLogMapper dataPullLogMapper;

    @PostMapping("/trigger-batch-event")
    public ApiResponse<String> triggerBatchEvent(@RequestParam String tradeDate,
                                                  @RequestParam(defaultValue = "SCHEDULED") String source) {
        eventPublisher.publishEvent(new DataBatchReadyEvent(this, tradeDate, source));
        return ApiResponse.success("Batch event published for tradeDate=" + tradeDate);
    }

    @PostMapping("/metric-cleanup")
    public ApiResponse<Integer> metricCleanup(@RequestParam String cutoff) {
        int deleted = dataPullLogMapper.deleteOlderThan(cutoff);
        return ApiResponse.success(deleted);
    }

    @PostMapping("/precompute-all")
    public ApiResponse<String> precomputeAll(@RequestParam String tradeDate) {
        precomputeService.precomputeAll(tradeDate);
        return ApiResponse.success("Precompute all jobs for tradeDate=" + tradeDate);
    }

    @GetMapping("/cache-keys")
    public ApiResponse<List<Object>> cacheKeys(@RequestParam String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        List<Object> keys = new ArrayList<>();
        try {
            Object nativeCache = cache.getNativeCache();
            // Caffeine 的 Cache 提供 asMap() 视图
            if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache) {
                keys.addAll(((com.github.benmanes.caffeine.cache.Cache<?, ?>) nativeCache).asMap().keySet());
            }
        } catch (Exception e) {
            log.warn("获取 cache keys 失败", e);
        }
        return ApiResponse.success(keys);
    }

    @PostMapping("/cache-evict")
    public ApiResponse<String> cacheEvict(@RequestParam String cacheName, @RequestParam String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
        return ApiResponse.success("Evicted key=" + key + " from cache=" + cacheName);
    }
}
