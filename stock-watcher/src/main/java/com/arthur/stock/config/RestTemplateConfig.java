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
}
