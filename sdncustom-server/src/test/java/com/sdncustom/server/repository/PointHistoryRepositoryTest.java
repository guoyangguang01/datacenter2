package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.enums.PointQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
