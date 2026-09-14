package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointHistory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Repository
public class PointHistoryRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final MeterRegistry meterRegistry;

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    // 已创建的表（避免每次插入都执行 CREATE TABLE IF NOT EXISTS）
    private final Set<String> createdTables = ConcurrentHashMap.newKeySet();

    // 熔断：TDengine 故障后 N 秒内不再尝试写入，避免采集循环被 5s 连接超时拖垮
    private static final long RETRY_INTERVAL_MS = 15_000;
    // 多表 INSERT 每条语句最多包含的值数量，控制 SQL 长度
    static final int MAX_VALUES_PER_STMT = 200;
    private volatile long nextRetryTime = 0L;
    private final AtomicInteger circuitOpen = new AtomicInteger(0);

    public PointHistoryRepository(@Qualifier("tdengineJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                  MeterRegistry meterRegistry) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.meterRegistry = meterRegistry;
        Gauge.builder("sdncustom.history.circuit.open", circuitOpen, AtomicInteger::get)
                .description("TDengine 熔断状态（1=断开）")
                .register(meterRegistry);
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
        circuitOpen.set(1);
        meterRegistry.counter("sdncustom.history.errors").increment();
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
                    "val BINARY(256), " +
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
     * 批量保存历史记录（多表 INSERT 分片，性能远优于逐条写入）
     */
    public void saveBatch(List<PointHistory> histories) {
        if (!enabled() || circuitOpen() || histories.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        try {
            JdbcTemplate jdbcTemplate = jdbc();

            // 确保子表存在
            for (PointHistory h : histories) {
                String tableName = tableNameOf(h.getPointId());
                if (createdTables.add(tableName)) {
                    String createTableSql = String.format(
                            "CREATE TABLE IF NOT EXISTS %s USING point_history TAGS ('%s')",
                            tableName, escapeSql(h.getPointId()));
                    jdbcTemplate.execute(createTableSql);
                }
            }

            for (String sql : buildBatchInsertStatements(histories)) {
                jdbcTemplate.execute(sql);
            }
            circuitOpen.set(0);
        } catch (Exception e) {
            markDown("saveBatch", e.getMessage());
        } finally {
            meterRegistry.timer("sdncustom.history.write")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 构建 TDengine 多表 INSERT 语句：
     * {@code INSERT INTO t1 VALUES (...) t2 VALUES (...)}，每 {@link #MAX_VALUES_PER_STMT} 条值分片一条语句。
     * 值内联进 SQL（TDengine JDBC 不支持多表参数化）：表名经白名单化，
     * value/sourceChannelId 经单引号转义，quality 为枚举名。
     */
    static List<String> buildBatchInsertStatements(List<PointHistory> histories) {
        List<String> statements = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (PointHistory h : histories) {
            if (count >= MAX_VALUES_PER_STMT) {
                statements.add(sb.toString());
                sb = new StringBuilder();
                count = 0;
            }
            if (count == 0) {
                sb.append("INSERT INTO ");
            }
            sb.append(tableNameOf(h.getPointId())).append(" VALUES (");
            sb.append(safeTimestamp(h.getTimestamp())).append(", ");
            sb.append('\'').append(escapeSql(String.valueOf(h.getValue()))).append("', ");
            sb.append('\'').append(h.getQuality().name()).append("', ");
            sb.append('\'').append(escapeSql(h.getSourceChannelId() == null ? "" : h.getSourceChannelId())).append('\'');
            sb.append(") ");
            count++;
        }
        if (count > 0) {
            statements.add(sb.toString());
        }
        return statements;
    }

    /**
     * 查询历史数据
     */
    public List<PointHistory> findByPointIdAndTimeRange(String pointId, long startTime, long endTime, int page, int size) {
        if (!enabled()) {
            return List.of();
        }
        try {
            // 用 long 算偏移：page * size 按 int 会在深翻页时溢出成负数，变成从 0 开始取
            long offset = (long) page * size;
            if (offset > Integer.MAX_VALUE) {
                log.warn("History offset {} beyond supported range for point {}", offset, pointId);
                return List.of();
            }
            String tableName = tableNameOf(pointId);
            String sql = String.format(
                    "SELECT ts, val, quality, source_channel_id FROM %s WHERE ts >= ? AND ts <= ? ORDER BY ts DESC LIMIT ? OFFSET ?",
                    tableName);
            return jdbc().query(sql, (rs, rowNum) -> {
                PointHistory history = new PointHistory();
                history.setPointId(pointId);
                history.setTimestamp(rs.getTimestamp("ts").getTime());
                history.setValue(rs.getString("val"));
                history.setQuality(com.sdncustom.common.model.enums.PointQuality.valueOf(rs.getString("quality")));
                history.setSourceChannelId(rs.getString("source_channel_id"));
                return history;
            }, startTime, endTime, (long) size, offset);
        } catch (Exception e) {
            log.error("Failed to query point history: {}", pointId, e);
            return List.of();
        }
    }

    /**
     * 协议侧可能产生超出 TDengine 范围的异常时间戳（如 OPC-UA 状态码零值对应
     * 1601 年的 -11644473600000），单个坏时间戳会使整批多表 INSERT 被拒，
     * 这里钳制到 [1970, 2100) 之外时替换为当前时间。
     */
    static long safeTimestamp(long ts) {
        final long lowerBound = 0L;
        final long upperBound = 4102444800000L; // 2100-01-01
        return (ts < lowerBound || ts >= upperBound) ? System.currentTimeMillis() : ts;
    }

    /**
     * 测点 ID 到表名的映射（非法字符替换为下划线）
     */
    static String tableNameOf(String pointId) {
        return "point_" + pointId.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * SQL 字符串字面量转义（防止单引号破坏 SQL / 注入）
     */
    static String escapeSql(String value) {
        return value.replace("'", "''");
    }
}
