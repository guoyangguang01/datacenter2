package com.sdncustom.server.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BindingMigration 绑定集迁移测试")
class BindingMigrationTest {

    private JdbcDataSource ds(String dbName) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    private JdbcTemplate schema(DataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 旧模型表结构：measurement_point 带 channel_id/address 列
        jdbc.execute("CREATE TABLE measurement_point (" +
                "point_id VARCHAR(64) PRIMARY KEY, point_name VARCHAR(128), " +
                "channel_id VARCHAR(64), address VARCHAR(256), data_type VARCHAR(16))");
        jdbc.execute("CREATE TABLE point_source (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, point_id VARCHAR(64), " +
                "channel_id VARCHAR(64), address VARCHAR(256), create_time TIMESTAMP)");
        return jdbc;
    }

    @Test
    @DisplayName("迁移回填 point_source 并删除旧列，且幂等")
    void migrationBackfillsAndDropsColumns() {
        JdbcDataSource ds = ds("migration_backfill");
        JdbcTemplate jdbc = schema(ds);
        jdbc.update("INSERT INTO measurement_point (point_id, point_name, channel_id, address, data_type) VALUES (?,?,?,?,?)",
                "p1", "点1", "ch_a", "40001", "INT16");
        jdbc.update("INSERT INTO measurement_point (point_id, point_name, channel_id, address, data_type) VALUES (?,?,?,?,?)",
                "p2", "点2", "ch_b", "ns=2;s=X", "FLOAT64");

        new BindingMigration(ds).run();

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM point_source", Integer.class);
        assertEquals(2, rows);
        String ch = jdbc.queryForObject("SELECT channel_id FROM point_source WHERE point_id = 'p1'", String.class);
        assertEquals("ch_a", ch);

        Integer colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = 'MEASUREMENT_POINT' AND UPPER(COLUMN_NAME) = 'CHANNEL_ID'",
                Integer.class);
        assertEquals(0, colCount);

        // 幂等：再次运行不抛异常、不重复插入
        new BindingMigration(ds).run();
        Integer rowsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM point_source", Integer.class);
        assertEquals(2, rowsAfter);
    }

    @Test
    @DisplayName("已有绑定的测点不会被重复回填")
    void migrationSkipsExistingBindings() {
        JdbcDataSource ds = ds("migration_skip_existing");
        JdbcTemplate jdbc = schema(ds);
        jdbc.update("INSERT INTO measurement_point (point_id, point_name, channel_id, address, data_type) VALUES (?,?,?,?,?)",
                "p1", "点1", "ch_a", "40001", "INT16");
        // 该点已有绑定（如新模型点）
        jdbc.update("INSERT INTO point_source (point_id, channel_id, address) VALUES (?,?,?)", "p1", "ch_a", "40001");

        new BindingMigration(ds).run();

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM point_source", Integer.class);
        assertEquals(1, rows);
    }

    @Test
    @DisplayName("无旧列时跳过（幂等）")
    void migrationSkipsWhenNoLegacyColumn() {
        JdbcDataSource ds = ds("migration_no_legacy");
        JdbcTemplate jdbc = schema(ds);
        jdbc.execute("ALTER TABLE measurement_point DROP COLUMN channel_id");
        jdbc.execute("ALTER TABLE measurement_point DROP COLUMN address");

        new BindingMigration(ds).run(); // 不抛异常
    }
}
