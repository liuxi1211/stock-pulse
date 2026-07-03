package com.arthur.stock.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus配置，配置分页插件和自动填充字段处理器
 */
@Configuration
@MapperScan("com.arthur.stock.mapper")
public class MyBatisPlusConfig {

    @Value("${app.db-type:mysql}")
    private String dbType;

    /**
     * 配置MyBatis-Plus分页拦截器，根据 app.db-type 动态选择数据库方言
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DbType type = "sqlite".equalsIgnoreCase(dbType) ? DbType.SQLITE : DbType.MYSQL;
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(type));
        return interceptor;
    }

    /**
     * 配置自动填充字段处理器，插入时填充createdAt和updatedAt，更新时填充updatedAt
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            /**
     * 插入时自动填充createdAt和updatedAt为当前时间
     */
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            /**
     * 更新时自动填充updatedAt为当前时间
     */
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
