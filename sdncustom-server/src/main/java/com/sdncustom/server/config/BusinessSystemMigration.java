package com.sdncustom.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 业务系统迁移（一次性、幂等）：
 * 给 channel / measurement_point 表加 business_id 列并把存量数据回填为默认业务，
 * 随后收紧为 NOT NULL + DEFAULT 'default'；business_system 表为空时插入默认业务。
 * 实体字段声明为可空，让 Hibernate ddl-auto:update 先安全加列（Hibernate 先于本迁移执行），
 * 非空约束由此处收紧；失败仅告警，不阻断启动。
 * 注意：必须用 H2 DataSource（Spring 主 DataSource），不能注入 JdbcTemplate——TDengine 也注册了 JdbcTemplate bean。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class BusinessSystemMigration implements CommandLineRunner {

    public static final String DEFAULT_BUSINESS_ID = "default";
    private static final String DEFAULT_BUSINESS_NAME = "默认业务";

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            migrateTable(jdbc, "channel");
            migrateTable(jdbc, "measurement_point");
            ensureDefaultBusiness(jdbc);
        } catch (Exception e) {
            log.error("BusinessSystemMigration failed, continuing startup", e);
        }
    }

    private void migrateTable(JdbcTemplate jdbc, String table) {
        if (!columnExists(jdbc, table, "BUSINESS_ID")) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS business_id VARCHAR(64)");
        }
        int backfilled = jdbc.update(
                "UPDATE " + table + " SET business_id = ? WHERE business_id IS NULL", DEFAULT_BUSINESS_ID);
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN business_id SET NOT NULL");
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN business_id SET DEFAULT '" + DEFAULT_BUSINESS_ID + "'");
        if (backfilled > 0) {
            log.info("BusinessSystemMigration: backfilled {} rows of {} with business '{}'",
                    backfilled, table, DEFAULT_BUSINESS_ID);
        }
    }

    private void ensureDefaultBusiness(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM business_system", Integer.class);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO business_system (business_id, business_name, description, create_time, update_time) "
                            + "VALUES (?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    DEFAULT_BUSINESS_ID, DEFAULT_BUSINESS_NAME);
            log.info("BusinessSystemMigration: created default business '{}'", DEFAULT_BUSINESS_ID);
        }
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                Integer.class, table.toUpperCase(), column);
        return count != null && count > 0;
    }
}
