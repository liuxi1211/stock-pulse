package com.arthur.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置，设置连接超时和读取超时
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 配置 RestTemplate，设置超时时间防止请求 hang 住
     * - 连接超时：5秒
     * - 读取超时：30秒（批量计算可能需要较长时间）
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    /**
     * 回测专用 RestTemplate（spec 007 T3）。
     * <p>
     * 回测单次运行可能耗时数分钟，read timeout 设 300s；connect 仍 5s。
     * 注入时用 @Qualifier("backtestRestTemplate") 或字段名匹配。
     */
    @Bean("backtestRestTemplate")
    public RestTemplate backtestRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(300000);
        return new RestTemplate(factory);
    }
}
