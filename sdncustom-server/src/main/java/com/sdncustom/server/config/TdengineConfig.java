package com.sdncustom.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TdengineConfig {

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    @Value("${tdengine.username:root}")
    private String tdengineUsername;

    @Value("${tdengine.password:taosdata}")
    private String tdenginePassword;

    private HikariDataSource dataSource;

    /**
     * TDengine 历史存储 JdbcTemplate。
     *
     * 注意：不将 TDengine 连接池暴露为 {@code DataSource} 类型的 Bean，否则 Spring Boot 的
     * DataSourceAutoConfiguration 会因已存在 DataSource Bean 而跳过 H2 自动配置，
     * 导致 JPA 绑定到 TDengine（启动失败或业务表建到 TDengine 上）。
     *
     * 仅当配置了 {@code tdengine.url} 时创建；连接池启动时不强制连接 TDengine，
     * 因此 TDengine 不可用时应用仍可启动，历史存储优雅降级。
     */
    @Bean
    @ConditionalOnProperty(name = "tdengine.url")
    public JdbcTemplate tdengineJdbcTemplate() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(tdengineUrl);
        config.setUsername(tdengineUsername);
        config.setPassword(tdenginePassword);
        // taos-jdbcdriver 3.x 的驱动类名（3.3.0 中 com.taosdata.jdbc.TaosDriver 已不存在）
        config.setDriverClassName("com.taosdata.jdbc.TSDBDriver");
        config.setPoolName("tdengine-pool");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        // 启动时跳过初始连接校验，避免 TDengine 未启动导致应用无法启动
        config.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(config);
        return new JdbcTemplate(dataSource);
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
