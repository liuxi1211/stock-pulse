package com.arthur.stock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "tushare")
public class TushareConfig {

    private String token;
    private String baseUrl = "http://api.tushare.pro";
    private int timeout = 30000;

    /**
     * 限流规则，key 为接口名称（如 daily / stock_basic / trade_cal），
     * value 为该接口的限流策略。未配置的接口不限流。
     */
    private Map<String, RateLimitRule> rateLimit;

    @Data
    public static class RateLimitRule {
        /** 每秒允许的最大请求数，null 或 0 表示不限 */
        private Integer permitsPerSecond;
        /** 每分钟允许的最大请求数，null 或 0 表示不限 */
        private Integer permitsPerMinute;
    }
}
