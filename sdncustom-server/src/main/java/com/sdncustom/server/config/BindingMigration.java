package com.sdncustom.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 绑定集迁移（一次性、幂等）：
 * 旧模型把 (channel_id, address) 存在 measurement_point 表上；新模型全部绑定在 point_source 表。
 * 启动时把旧列的每条绑定回填到 point_source，然后删除旧列。旧列不存在则跳过。
 * Hibernate ddl-auto:update 只建表不删列，故需此迁移；失败仅告警，不阻断启动。
 * 注意：必须用 H2 DataSource（Spring 主 DataSource），不能注入 JdbcTemplate——TDengine 也注册了 JdbcTemplate bean。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class BindingMigration implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Integer colCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'MEASUREMENT_POINT' AND UPPER(COLUMN_NAME) = 'CHANNEL_ID'",
                    Integer.class);
            if (colCount == null || colCount == 0) {
                log.info("BindingMigration: no legacy channel_id column, skip");
                return;
            }
            // 去重必须按「具体这条绑定」判断，不能只按 point_id：
            // 否则已有一条绑定的点会整条被跳过，旧绑定随删列永久丢失。
            int backfilled = jdbc.update("""
                    INSERT INTO point_source (point_id, channel_id, address, create_time)
                    SELECT mp.point_id, mp.channel_id, mp.address, CURRENT_TIMESTAMP
                    FROM measurement_point mp
                    WHERE mp.channel_id IS NOT NULL AND mp.address IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM point_source ps
                          WHERE ps.point_id = mp.point_id
                            AND ps.channel_id = mp.channel_id
                            AND ps.address = mp.address
                      )
                    """);
            jdbc.execute("ALTER TABLE measurement_point DROP COLUMN IF EXISTS channel_id");
            jdbc.execute("ALTER TABLE measurement_point DROP COLUMN IF EXISTS address");
            log.info("BindingMigration: backfilled {} bindings, dropped legacy channel_id/address columns", backfilled);
        } catch (Exception e) {
            log.error("BindingMigration failed, continuing without dropping legacy columns", e);
        }
    }
}
