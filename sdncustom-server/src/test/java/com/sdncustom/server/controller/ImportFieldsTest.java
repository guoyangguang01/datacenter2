package com.sdncustom.server.controller;

import com.sdncustom.common.exception.BusinessException;
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
