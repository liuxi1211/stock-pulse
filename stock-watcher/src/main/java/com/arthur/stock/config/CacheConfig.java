package com.arthur.stock.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 缓存配置（通过 Spring Cache 注解使用）。
 * <p>
 * 显式定义 {@link CacheManager}，替换默认自动配置：
 * <ul>
 *   <li>默认 spec：无过期，兼容既有缓存（如 kline）。</li>
 *   <li>factorList / factorDetail / factorCategories：写入后 5 分钟过期，
 *       作为 watcher 因子缓存的兜底 TTL（spec AC-18），写操作另由 @CacheEvict 主动失效。</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 默认缓存：无过期（保持与既有 kline 等缓存行为一致）
        manager.setCaffeine(Caffeine.newBuilder());
        // 因子缓存：5 分钟写入后过期（兜底 TTL，防遗漏失效导致长期不一致）
        Caffeine<Object, Object> factorSpec = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES);
        manager.registerCustomCache("factorList", factorSpec.build());
        manager.registerCustomCache("factorDetail", factorSpec.build());
        manager.registerCustomCache("factorCategories", factorSpec.build());
        return manager;
    }
}
