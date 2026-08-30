package com.sdncustom.server.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BusinessSystemMigration 业务系统迁移测试")
class BusinessSystemMigrationTest {

    private JdbcDataSource ds(String dbName) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    /** 模拟 Hibernate 加列后的存量表结构：两表已有可空 business_id 列，业务表已建但为空 */
    private JdbcTemplate schema(DataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE channel (" +
                "channel_id VARCHAR(64) PRIMARY KEY, channel_name VARCHAR(128), business_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE measurement_point (" +
                "point_id VARCHAR(64) PRIMARY KEY, point_name VARCHAR(128), business_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE business_system (" +
                "business_id VARCHAR(64) PRIMARY KEY, business_name VARCHAR(128), " +
                "description VARCHAR(512), create_time TIMESTAMP, update_time TIMESTAMP)");
        return jdbc;
    }

    @Test
    @DisplayName("存量数据回填默认业务并收紧非空约束，同时创建默认业务")
    void migrationBackfillsAndSeedsDefaultBusiness() {
        JdbcDataSource ds = ds("biz_migration_backfill");
        JdbcTemplate jdbc = schema(ds);
        jdbc.update("INSERT INTO channel (channel_id, channel_name) VALUES (?,?)", "ch_a", "通道A");
        jdbc.update("INSERT INTO measurement_point (point_id, point_name) VALUES (?,?)", "p1", "点1");

        new BusinessSystemMigration(ds).run();

        assertEquals("default",
                jdbc.queryForObject("SELECT business_id FROM channel WHERE channel_id = 'ch_a'", String.class));
        assertEquals("default",
                jdbc.queryForObject("SELECT business_id FROM measurement_point WHERE point_id = 'p1'", String.class));
        Integer bizCount = jdbc.queryForObject("SELECT COUNT(*) FROM business_system", Integer.class);
        assertEquals(1, bizCount);
        assertEquals("默认业务",
                jdbc.queryForObject("SELECT business_name FROM business_system WHERE business_id = 'default'", String.class));
        // 收紧后新插入行默认落 default（H2 DEFAULT 生效）
        jdbc.update("INSERT INTO channel (channel_id, channel_name) VALUES (?,?)", "ch_b", "通道B");
        assertEquals("default",
                jdbc.queryForObject("SELECT business_id FROM channel WHERE channel_id = 'ch_b'", String.class));
    }

    @Test
    @DisplayName("幂等：二次运行不报错、不重复创建默认业务")
    void migrationIsIdempotent() {
        JdbcDataSource ds = ds("biz_migration_idempotent");
        JdbcTemplate jdbc = schema(ds);

        new BusinessSystemMigration(ds).run();
        new BusinessSystemMigration(ds).run();

        Integer bizCount = jdbc.queryForObject("SELECT COUNT(*) FROM business_system", Integer.class);
        assertEquals(1, bizCount);
    }

    @Test
    @DisplayName("business_system 已有数据时不插入默认业务")
    void migrationSkipsSeedWhenBusinessExists() {
        JdbcDataSource ds = ds("biz_migration_skip_seed");
        JdbcTemplate jdbc = schema(ds);
        jdbc.update("INSERT INTO business_system (business_id, business_name) VALUES (?,?)", "biz_a", "业务A");

        new BusinessSystemMigration(ds).run();

        Integer bizCount = jdbc.queryForObject("SELECT COUNT(*) FROM business_system", Integer.class);
        assertEquals(1, bizCount);
        Integer defaultCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_system WHERE business_id = 'default'", Integer.class);
        assertEquals(0, defaultCount);
    }

    @Test
    @DisplayName("已有业务归属的数据不被覆盖")
    void migrationDoesNotOverwriteExistingBusiness() {
        JdbcDataSource ds = ds("biz_migration_keep_existing");
        JdbcTemplate jdbc = schema(ds);
        jdbc.update("INSERT INTO channel (channel_id, channel_name, business_id) VALUES (?,?,?)",
                "ch_a", "通道A", "biz_a");

        new BusinessSystemMigration(ds).run();

        assertEquals("biz_a",
                jdbc.queryForObject("SELECT business_id FROM channel WHERE channel_id = 'ch_a'", String.class));
    }
}
