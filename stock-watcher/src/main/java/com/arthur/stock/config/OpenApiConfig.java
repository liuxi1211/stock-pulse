package com.arthur.stock.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockWatcherOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Stock Watcher API")
                        .description("股票行情监控系统 REST API 接口文档。" +
                                "<br/><br/>" +
                                "<b>认证说明：</b>本系统使用 Session 认证，使用 Swagger UI 测试接口前，" +
                                "请先在同一浏览器的另一个标签页登录系统，登录后 Swagger UI 将自动携带 Cookie。")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Stock Watcher Team")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("开发环境")
                ))
                .addSecurityItem(new SecurityRequirement().addList("SessionAuth"))
                .schemaRequirement("SessionAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")
                        .description("Session 认证 Cookie，登录后自动携带"));
    }
}
