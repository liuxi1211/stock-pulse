package com.arthur.stock.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine缓存配置（通过 Spring Cache 注解使用）。
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
