package com.arthur.stock.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
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
        manager.setCaffeine(Caffeine.newBuilder().recordStats());
        // 因子缓存：5 分钟写入后过期（兜底 TTL，防遗漏失效导致长期不一致）
        Caffeine<Object, Object> factorSpec = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats();
        manager.registerCustomCache("factorList", factorSpec.build());
        manager.registerCustomCache("factorDetail", factorSpec.build());
        manager.registerCustomCache("factorCategories", factorSpec.build());
        // 选股中心交易日历缓存：1 天写入后过期（交易日历日内稳定，每日 DailyUpdateTask 拉数后自然演进）
        Caffeine<Object, Object> calendarSpec = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.DAYS)
                .recordStats();
        manager.registerCustomCache("tradeCalendar", calendarSpec.build());
        manager.registerCustomCache("latestTradeDate", calendarSpec.build());
        // 板块/市场排名缓存：24 小时写入后过期（数据日内稳定，24h 兜底 TTL，配合数据同步任务的 @CacheEvict 主动失效）
        Caffeine<Object, Object> dailySpec = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(24))
                .recordStats();
        manager.registerCustomCache("sectorRanking", dailySpec.build());
        manager.registerCustomCache("sectorMoneyflow", dailySpec.build());
        manager.registerCustomCache("sectorValuation", dailySpec.build());
        manager.registerCustomCache("marketRanking", dailySpec.build());
        manager.registerCustomCache("moneyflowRanking", dailySpec.build());
        // 股票名称映射缓存：1 天写入后过期（stock_basic 变化频率极低，日内无需刷新）
        Caffeine<Object, Object> stockBasicSpec = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.DAYS)
                .recordStats();
        manager.registerCustomCache("stockBasicName", stockBasicSpec.build());
        // 市场温度/指数缓存：无 TTL，仅设容量上限防止膨胀
        Caffeine<Object, Object> boundedSpec = Caffeine.newBuilder()
                .maximumSize(50)
                .recordStats();
        manager.registerCustomCache("marketTemperature", boundedSpec.build());
        manager.registerCustomCache("indices", boundedSpec.build());
        return manager;
    }
}
