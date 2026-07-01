package com.arthur.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestTemplate;

/**
 * 安全配置类，提供密码加密器和HTTP客户端Bean
 */
@Configuration
public class SecurityConfig {

    /**
     * BCrypt密码加密器
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * RestTemplate HTTP客户端，用于调用外部API
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
