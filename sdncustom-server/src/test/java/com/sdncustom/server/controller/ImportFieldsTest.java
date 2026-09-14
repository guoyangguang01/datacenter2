package com.sdncustom.server.controller;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportFields 导入字段解析 测试")
class ImportFieldsTest {

    @Test
    @DisplayName("测点缺 businessId：不再回退默认业务，直接报错")
    void businessIdRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "pointName", "P1",
                "dataType", "FLOAT32",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("businessId"), ex.getMessage());
    }

    @Test
    @DisplayName("旧绑定格式（channelId+address）不再被接受")
    void legacyBindingFormatRejected() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "channelId", "ch_1",
                "address", "40001");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parseBindings(pt));

        assertTrue(ex.getMessage().contains("绑定"), ex.getMessage());
    }

    @Test
    @DisplayName("additionalSources 旧字段不再被接受")
    void legacyAdditionalSourcesRejected() {
        Map<String, Object> pt = Map.of(
                "channelId", "ch_1",
                "address", "40001",
                "additionalSources", List.of(Map.of("channelId", "ch_2", "address", "reg2")));

        assertThrows(BusinessException.class, () -> ImportFields.parseBindings(pt));
    }

    @Test
    @DisplayName("新格式 bindings 数组正常解析")
    void newBindingFormatParsed() {
        Map<String, Object> pt = Map.of(
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        var bindings = ImportFields.parseBindings(pt);

        assertEquals(1, bindings.size());
        assertEquals("ch_1", bindings.get(0).getChannelId());
        assertEquals("40001", bindings.get(0).getAddress());
    }

    @Test
    @DisplayName("测点缺 direction：整条解析失败")
    void directionRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "businessId", "default",
                "pointName", "P1",
                "dataType", "FLOAT32",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("direction"), ex.getMessage());
    }

    @Test
    @DisplayName("完整测点：每个字段都被搬进 DTO（happy path，覆盖全部字段）")
    void parsePointHappyPath() {
        Map<String, Object> pt = Map.of(
                "pointId", "in_1",
                "businessId", "biz_a",
                "pointName", "IN1",
                "dataType", "FLOAT64",
                "unit", "°C",
                "direction", "INPUT",
                "referencePointId", "out_1",
                "deadband", 0.5,
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        var dto = ImportFields.parsePoint(pt);

        assertEquals("in_1", dto.getPointId());
        assertEquals("biz_a", dto.getBusinessId());
        assertEquals("IN1", dto.getPointName());
        assertEquals(PointDataType.FLOAT64, dto.getDataType());
        assertEquals("°C", dto.getUnit());
        assertEquals(PointDirection.INPUT, dto.getDirection());
        assertEquals("out_1", dto.getReferencePointId());
        assertEquals(0.5, dto.getDeadband());
        assertEquals(1, dto.getBindings().size());
        assertEquals("ch_1", dto.getBindings().get(0).getChannelId());
        assertEquals("40001", dto.getBindings().get(0).getAddress());
    }

    /**
     * I6 的核心：解析层**逐条累积**问题，而不是在第一条不合格记录上抛出。
     * 这条路径上（POST /api/data/import 解析裸 Map）没有 bean validation 兜底，
     * 用户能看到的只有这一条消息——一次报全才不用逐条试错。
     */
    @Test
    @DisplayName("parsePoints 一次报全：每条不合格记录都被点名并带上原因")
    void parsePointsAccumulatesEveryProblem() {
        List<Object> raw = List.of(
                Map.of("pointId", "no_dir", "businessId", "biz_a", "pointName", "P1",
                        "dataType", "FLOAT32",
                        "bindings", List.of(Map.of("channelId", "ch_1", "address", "a"))),
                Map.of("pointId", "bad_dir", "businessId", "biz_a", "pointName", "P2",
                        "dataType", "FLOAT32", "direction", "SIDEWAYS",
                        "bindings", List.of(Map.of("channelId", "ch_1", "address", "b"))),
                Map.of("pointId", "ok", "businessId", "biz_a", "pointName", "P3",
                        "dataType", "FLOAT32", "direction", "OUTPUT",
                        "bindings", List.of(Map.of("channelId", "ch_1", "address", "c"))));

        ImportFields.ParseResult result = ImportFields.parsePoints(raw);

        // 合格的那条照常解析出来，不合格的两条各占一行问题
        assertEquals(List.of("ok"), result.points().stream().map(MeasurementPointDTO::getPointId).toList());
        assertEquals(2, result.problems().size());
        assertTrue(result.problems().get(0).startsWith("no_dir"), result.problems().get(0));
        assertTrue(result.problems().get(0).contains("direction"), result.problems().get(0));
        assertTrue(result.problems().get(1).startsWith("bad_dir"), result.problems().get(1));
        assertTrue(result.problems().get(1).contains("SIDEWAYS"), result.problems().get(1));
    }

    @Test
    @DisplayName("parsePoints 缺少 pointId 的记录也能被点名（不拼出裸 null）")
    void parsePointsNamesRecordWithoutPointId() {
        List<Object> raw = List.of(
                Map.of("businessId", "biz_a", "pointName", "P1", "dataType", "FLOAT32",
                        "bindings", List.of(Map.of("channelId", "ch_1", "address", "a"))));

        ImportFields.ParseResult result = ImportFields.parsePoints(raw);

        assertTrue(result.points().isEmpty());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).startsWith("<缺少 pointId 的测点>"), result.problems().get(0));
    }

    @Test
    @DisplayName("INPUT 测点的 referencePointId 被解析")
    void referencePointIdParsed() {
        Map<String, Object> pt = Map.of(
                "pointId", "in_1",
                "businessId", "default",
                "pointName", "IN1",
                "dataType", "FLOAT32",
                "direction", "INPUT",
                "referencePointId", "out_1",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        var dto = ImportFields.parsePoint(pt);

        assertEquals(PointDirection.INPUT, dto.getDirection());
        assertEquals("out_1", dto.getReferencePointId());
    }
}
