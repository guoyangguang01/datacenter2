package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PointHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    /**
     * 初始化 TDengine 超级表
     */
    public void initSuperTable() {
        if (tdengineUrl == null || tdengineUrl.isEmpty()) {
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
            jdbcTemplate.execute(sql);
            log.info("TDengine super table initialized");
        } catch (Exception e) {
            log.warn("Failed to initialize TDengine super table: {}", e.getMessage());
        }
    }

    /**
     * 保存历史记录
     */
    public void save(PointHistory history) {
        if (tdengineUrl == null || tdengineUrl.isEmpty()) {
            return;
        }
        try {
            String tableName = "point_" + history.getPointId().replaceAll("[^a-zA-Z0-9_]", "_");
            String createTableSql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s USING point_history TAGS ('%s')",
                    tableName, history.getPointId());
            jdbcTemplate.execute(createTableSql);

            String insertSql = String.format(
                    "INSERT INTO %s VALUES (?, ?, ?, ?)",
                    tableName);
            jdbcTemplate.update(insertSql,
                    new Object[]{
                            history.getTimestamp(),
                            String.valueOf(history.getValue()),
                            history.getQuality().name(),
                            history.getSourceChannelId()
                    },
                    new int[]{Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
        } catch (Exception e) {
            log.error("Failed to save point history: {}", history.getPointId(), e);
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
        if (tdengineUrl == null || tdengineUrl.isEmpty()) {
            return List.of();
        }
        try {
            String tableName = "point_" + pointId.replaceAll("[^a-zA-Z0-9_]", "_");
            String sql = String.format(
                    "SELECT ts, value, quality, source_channel_id FROM %s WHERE ts >= ? AND ts <= ? ORDER BY ts DESC LIMIT ? OFFSET ?",
                    tableName);
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
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
}
