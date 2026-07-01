package com.arthur.stock.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine缓存配置（通过 Spring Cache 注解使用）。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 因子注册表缓存，供 FactorGateway 使用。
     * 缓存 1 小时，最大容量 1 条（只存注册表一份）。
     */
    @Bean
    public Cache<String, Object> factorRegistryCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1)
                .build();
    }
}
