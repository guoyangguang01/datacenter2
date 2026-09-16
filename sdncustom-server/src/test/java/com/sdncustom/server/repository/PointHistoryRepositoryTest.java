package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.enums.PointQuality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("PointHistoryRepository 批量 SQL 构建测试")
class PointHistoryRepositoryTest {

    private PointHistory history(String pointId, Object value, long ts) {
        PointHistory h = new PointHistory();
        h.setPointId(pointId);
        h.setValue(value);
        h.setQuality(PointQuality.GOOD);
        h.setSourceChannelId("ch_001");
        h.setTimestamp(ts);
        return h;
    }

    @Test
    @DisplayName("空列表返回空")
    void emptyListReturnsEmpty() {
        assertTrue(PointHistoryRepository.buildBatchInsertStatements(List.of()).isEmpty());
    }

    @Test
    @DisplayName("单表单值生成一条语句")
    void singleValueSingleTable() {
        List<String> sqls = PointHistoryRepository.buildBatchInsertStatements(
                List.of(history("p1", 25.6, 1700000000000L)));
        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.startsWith("INSERT INTO point_p1 VALUES ("));
        assertTrue(sql.contains("1700000000000"));
        assertTrue(sql.contains("'25.6'"));
        assertTrue(sql.contains("'GOOD'"));
        assertTrue(sql.contains("'ch_001'"));
    }

    @Test
    @DisplayName("多个测点合并进同一条语句")
    void multipleTablesOneStatement() {
        List<String> sqls = PointHistoryRepository.buildBatchInsertStatements(
                List.of(history("p1", 1, 1L), history("p2", 2, 2L)));
        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.contains("point_p1 VALUES"));
        assertTrue(sql.contains("point_p2 VALUES"));
    }

    @Test
    @DisplayName("超过分片上限拆分为多条语句")
    void chunkingAtMaxValues() {
        List<PointHistory> histories = new ArrayList<>();
        for (int i = 0; i < PointHistoryRepository.MAX_VALUES_PER_STMT + 50; i++) {
            histories.add(history("p" + i, i, i));
        }
        List<String> sqls = PointHistoryRepository.buildBatchInsertStatements(histories);
        assertEquals(2, sqls.size());
        assertTrue(sqls.get(0).startsWith("INSERT INTO "));
        assertTrue(sqls.get(1).startsWith("INSERT INTO "));
        // 第一条包含 200 组 VALUES
        assertEquals(PointHistoryRepository.MAX_VALUES_PER_STMT, countOccurrences(sqls.get(0), " VALUES ("));
        assertEquals(50, countOccurrences(sqls.get(1), " VALUES ("));
    }

    @Test
    @DisplayName("单引号被转义")
    void sqlEscaping() {
        List<String> sqls = PointHistoryRepository.buildBatchInsertStatements(
                List.of(history("p1", "it's", 1L)));
        assertTrue(sqls.get(0).contains("'it''s'"));
    }

    @Test
    @DisplayName("表名白名单化（非法字符替换）")
    void tableNameSanitized() {
        List<String> sqls = PointHistoryRepository.buildBatchInsertStatements(
                List.of(history("p-1.x", 1, 1L)));
        assertTrue(sqls.get(0).contains("point_p_1_x VALUES"));
    }

    @Test
    @DisplayName("异常时间戳被钳制，避免毒化整批写入")
    void abnormalTimestampClamped() {
        long now = System.currentTimeMillis();
        assertEquals(now, PointHistoryRepository.safeTimestamp(now), "正常时间应透传");
        // OPC-UA 状态码零值对应 1601 年
        long clamped = PointHistoryRepository.safeTimestamp(-11644473600000L);
        assertTrue(clamped > 0 && clamped <= now + 1000, "越界过去时间应被替换为当前时间");
        assertTrue(PointHistoryRepository.safeTimestamp(99999999999999L) < 4102444800000L,
                "未来越界时间也应被替换");
    }

    @Test
    @DisplayName("建表语句：空列表返回空")
    void createStatementsEmpty() {
        assertTrue(PointHistoryRepository.buildCreateStatements(List.of()).isEmpty());
    }

    @Test
    @DisplayName("建表语句：多个子表合并进同一条，只有首个带 CREATE TABLE")
    void createStatementsMerged() {
        List<String> sqls = PointHistoryRepository.buildCreateStatements(List.of("p1", "p2"));

        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS point_p1 USING point_history TAGS ('p1')"), sql);
        assertTrue(sql.contains("IF NOT EXISTS point_p2 USING point_history TAGS ('p2')"), sql);
        // 后续子表只重复 IF NOT EXISTS；若每条都写 CREATE TABLE，TDengine 会解析失败
        assertEquals(1, countOccurrences(sql, "CREATE TABLE"));
    }

    @Test
    @DisplayName("建表语句：超过分片上限拆分为多条")
    void createStatementsChunked() {
        List<String> pointIds = new ArrayList<>();
        for (int i = 0; i < PointHistoryRepository.MAX_TABLES_PER_CREATE_STMT + 50; i++) {
            pointIds.add("p" + i);
        }

        List<String> sqls = PointHistoryRepository.buildCreateStatements(pointIds);

        assertEquals(2, sqls.size());
        assertEquals(PointHistoryRepository.MAX_TABLES_PER_CREATE_STMT,
                countOccurrences(sqls.get(0), "USING point_history"));
        assertEquals(50, countOccurrences(sqls.get(1), "USING point_history"));
        assertEquals(1, countOccurrences(sqls.get(0), "CREATE TABLE"));
        assertEquals(1, countOccurrences(sqls.get(1), "CREATE TABLE"));
    }

    @Test
    @DisplayName("建表语句：表名白名单化、TAGS 值单引号转义")
    void createStatementsEscaping() {
        List<String> sqls = PointHistoryRepository.buildCreateStatements(List.of("p-1.x", "it's"));

        assertEquals(1, sqls.size());
        assertTrue(sqls.get(0).contains("point_p_1_x USING point_history TAGS ('p-1.x')"), sqls.get(0));
        assertTrue(sqls.get(0).contains("TAGS ('it''s')"), sqls.get(0));
    }

    @Test
    @DisplayName("saveBatch：首次遇到的子表合并成一条建表语句，再次写入不再建表")
    void saveBatchBuildsSubtablesInOneStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PointHistoryRepository repository = new PointHistoryRepository(provider, registry);
        ReflectionTestUtils.setField(repository, "tdengineUrl", "jdbc:TAOS-RS://localhost:6041/sdncustom");

        repository.saveBatch(List.of(history("p1", 1, 1L), history("p2", 2, 2L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // 一次建表 + 一次插入：逐点建表的老实现这里是 3 次
        verify(jdbcTemplate, times(2)).execute(sql.capture());
        assertTrue(sql.getAllValues().get(0).startsWith("CREATE TABLE IF NOT EXISTS point_p1"), sql.getAllValues().get(0));
        assertTrue(sql.getAllValues().get(0).contains("IF NOT EXISTS point_p2"), sql.getAllValues().get(0));
        assertTrue(sql.getAllValues().get(1).startsWith("INSERT INTO "), sql.getAllValues().get(1));
        // 计数器同时证明这段逻辑真的跑到了（否则异常被 markDown 吞掉，上面的断言会假通过）
        assertEquals(2.0, registry.get("sdncustom.history.subtables.created").counter().count());

        repository.saveBatch(List.of(history("p1", 3, 3L), history("p2", 4, 4L)));

        // 第二轮只该有一次插入，不再建表
        verify(jdbcTemplate, times(3)).execute(anyString());
        assertEquals(2.0, registry.get("sdncustom.history.subtables.created").counter().count());
    }

    private int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
