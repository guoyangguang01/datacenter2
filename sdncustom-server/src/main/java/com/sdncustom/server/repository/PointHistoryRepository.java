package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Repository
public class PointHistoryRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    // 已创建的表（避免每次插入都执行 CREATE TABLE IF NOT EXISTS）
    private final Set<String> createdTables = ConcurrentHashMap.newKeySet();

    // 熔断：TDengine 故障后 N 秒内不再尝试写入，避免采集循环被 5s 连接超时拖垮
    private static final long RETRY_INTERVAL_MS = 15_000;
    private volatile long nextRetryTime = 0L;

    public PointHistoryRepository(@Qualifier("tdengineJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplateProvider.getIfAvailable();
    }

    private boolean enabled() {
        return tdengineUrl != null && !tdengineUrl.isEmpty() && jdbc() != null;
    }

    private boolean circuitOpen() {
        return System.currentTimeMillis() < nextRetryTime;
    }

    private void markDown(String action, String detail) {
        nextRetryTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;
        log.warn("TDengine unavailable ({}), will retry in {}s: {}", action, RETRY_INTERVAL_MS / 1000, detail);
    }

    /**
     * 初始化 TDengine 超级表
     */
    public void initSuperTable() {
        if (!enabled()) {
            log.warn("TDengine not configured, skipping super table initialization");
            return;
        }
        try {
            String sql = "CREATE STABLE IF NOT EXISTS point_history (" +
                    "ts TIMESTAMP, " +
                    "value BINARY(256), " +
                    "quality BINARY(32), " +
                    "source_channel_id BINARY(64)" +
                    ") TAGS (point_id BINARY(64))";
            jdbc().execute(sql);
            log.info("TDengine super table initialized");
        } catch (Exception e) {
            markDown("initSuperTable", e.getMessage());
        }
    }

    /**
     * 保存历史记录
     */
    public void save(PointHistory history) {
        if (!enabled() || circuitOpen()) {
            return;
        }
        try {
            JdbcTemplate jdbcTemplate = jdbc();
            String pointId = history.getPointId();
            String tableName = tableNameOf(pointId);

            if (createdTables.add(tableName)) {
                // 表不存在则创建（按测点分表）
                String createTableSql = String.format(
                        "CREATE TABLE IF NOT EXISTS %s USING point_history TAGS ('%s')",
                        tableName, escapeSql(pointId));
                jdbcTemplate.execute(createTableSql);
            }

            String insertSql = String.format("INSERT INTO %s VALUES (?, ?, ?, ?)", tableName);
            jdbcTemplate.update(insertSql,
                    new Object[]{
                            history.getTimestamp(),
                            String.valueOf(history.getValue()),
                            history.getQuality().name(),
                            history.getSourceChannelId()
                    },
                    new int[]{Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
        } catch (Exception e) {
            markDown("save", e.getMessage());
        }
    }

    /**
     * 批量保存历史记录
     */
    public void saveBatch(List<PointHistory> histories) {
        for (PointHistory history : histories) {
            save(history);
        }
    }

    /**
     * 查询历史数据
     */
    public List<PointHistory> findByPointIdAndTimeRange(String pointId, long startTime, long endTime, int page, int size) {
        if (!enabled()) {
            return List.of();
        }
        try {
            String tableName = tableNameOf(pointId);
            String sql = String.format(
                    "SELECT ts, value, quality, source_channel_id FROM %s WHERE ts >= ? AND ts <= ? ORDER BY ts DESC LIMIT ? OFFSET ?",
                    tableName);
            return jdbc().query(sql, (rs, rowNum) -> {
                PointHistory history = new PointHistory();
                history.setPointId(pointId);
                history.setTimestamp(rs.getTimestamp("ts").getTime());
                history.setValue(rs.getString("value"));
                history.setQuality(com.sdncustom.common.model.enums.PointQuality.valueOf(rs.getString("quality")));
                history.setSourceChannelId(rs.getString("source_channel_id"));
                return history;
            }, startTime, endTime, size, page * size);
        } catch (Exception e) {
            log.error("Failed to query point history: {}", pointId, e);
            return List.of();
        }
    }

    /**
     * 测点 ID 到表名的映射（非法字符替换为下划线）
     */
    private String tableNameOf(String pointId) {
        return "point_" + pointId.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * SQL 字符串字面量转义（防止单引号破坏 SQL / 注入）
     */
    private String escapeSql(String value) {
        return value.replace("'", "''");
    }
}
