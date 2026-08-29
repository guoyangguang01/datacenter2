package com.sdncustom.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

@Slf4j
@Configuration
public class TdengineConfig {

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    @Value("${tdengine.username:root}")
    private String tdengineUsername;

    @Value("${tdengine.password:taosdata}")
    private String tdenginePassword;

    /** 历史数据保留天数（TDengine KEEP） */
    @Value("${sdncustom.history.keep-days:30}")
    private int keepDays;

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
        // 先确保数据库存在（部分驱动连接时会 USE 目标库，库不存在会直接连接失败），
        // 再创建连接池，保证池建立时库已就绪
        bootstrapDatabase();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(tdengineUrl);
        config.setUsername(tdengineUsername);
        config.setPassword(tdenginePassword);
        config.setDriverClassName(driverClassFor(tdengineUrl));
        config.setPoolName("tdengine-pool");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        // 启动时跳过初始连接校验，避免 TDengine 未启动导致应用无法启动
        config.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(config);
        return new JdbcTemplate(dataSource);
    }

    /**
     * 用不带库名的连接创建数据库并强制保留时长（KEEP），失败仅告警不阻断启动。
     * 库名取自 {@code tdengine.url} 最后一段。
     */
    private void bootstrapDatabase() {
        String dbName = parseDbName(tdengineUrl);
        if (dbName == null || dbName.isEmpty()) {
            log.warn("Cannot parse database name from tdengine.url: {}", tdengineUrl);
            return;
        }
        String baseUrl = baseUrlWithoutDb(tdengineUrl);
        String driver = driverClassFor(tdengineUrl);
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            log.warn("TDengine driver not found: {}", driver);
            return;
        }
        try (Connection conn = DriverManager.getConnection(baseUrl, tdengineUsername, tdenginePassword);
             Statement stmt = conn.createStatement()) {
            // 库名来自本地配置且经白名单校验，不拼接外部输入
            stmt.execute(String.format("CREATE DATABASE IF NOT EXISTS %s PRECISION 'ms' KEEP %d", dbName, keepDays));
            // 存量库强制对齐当前配置的保留时长
            stmt.execute(String.format("ALTER DATABASE %s KEEP %d", dbName, keepDays));
            log.info("TDengine database '{}' ready, KEEP {} days", dbName, keepDays);
        } catch (Throwable t) {
            // 原生驱动缺客户端库会抛 UnsatisfiedLinkError（Error），必须一并捕获以保持降级启动
            log.warn("Failed to bootstrap TDengine database '{}': {}", dbName, t.getMessage());
        }
    }

    /**
     * 从 {@code jdbc:TAOS*://host:port/dbname[?params]} 解析库名；无库名返回 null
     */
    static String parseDbName(String url) {
        String path = pathAfterAuthority(url);
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path;
    }

    /**
     * 去掉 URL 中的库名（及查询串），得到可无库连接的 base URL
     */
    static String baseUrlWithoutDb(String url) {
        if (url == null) {
            return null;
        }
        String withoutQuery = stripQuery(url);
        int slashIdx = dbSlashIndex(withoutQuery);
        if (slashIdx < 0) {
            return withoutQuery;
        }
        return withoutQuery.substring(0, slashIdx);
    }

    /** 返回 authority（host:port）之后、查询串之前的库名路径段 */
    private static String pathAfterAuthority(String url) {
        String withoutQuery = stripQuery(url);
        int slashIdx = dbSlashIndex(withoutQuery);
        if (slashIdx < 0 || slashIdx == withoutQuery.length() - 1) {
            return null;
        }
        return withoutQuery.substring(slashIdx + 1);
    }

    /** 库名分隔斜杠的位置：{@code ://} 之后的第一个 '/'；不存在返回 -1 */
    private static int dbSlashIndex(String url) {
        if (url == null) {
            return -1;
        }
        int schemeIdx = url.indexOf("://");
        int searchFrom = schemeIdx >= 0 ? schemeIdx + 3 : 0;
        return url.indexOf('/', searchFrom);
    }

    private static String stripQuery(String url) {
        if (url == null) {
            return null;
        }
        int queryIdx = url.indexOf('?');
        return queryIdx >= 0 ? url.substring(0, queryIdx) : url;
    }

    /**
     * 按 URL 前缀选择驱动：
     * - jdbc:TAOS-RS:// 走 REST（RestfulDriver，纯 Java，无需本机 TDengine 客户端库）
     * - 其余（如 jdbc:TAOS://）走原生驱动（需本机安装客户端库）
     */
    private static String driverClassFor(String url) {
        if (url != null && url.toUpperCase().startsWith("JDBC:TAOS-RS")) {
            return "com.taosdata.jdbc.rs.RestfulDriver";
        }
        return "com.taosdata.jdbc.TSDBDriver";
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
