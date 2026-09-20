package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportFields 导入字段解析 测试")
class ImportFieldsTest {

    @Test
    @DisplayName("测点缺 businessId：直接报错")
    void businessIdRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "pointName", "P1",
                "channelId", "ch_1",
                "address", "40001",
                "dataType", "FLOAT32");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("businessId"), ex.getMessage());
    }

    @Test
    @DisplayName("测点缺 channelId：报错")
    void channelIdRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "pointName", "P1",
                "businessId", "default",
                "address", "40001",
                "dataType", "FLOAT32");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("channelId"), ex.getMessage());
    }

    @Test
    @DisplayName("测点缺 address：报错")
    void addressRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "pointName", "P1",
                "businessId", "default",
                "channelId", "ch_1",
                "dataType", "FLOAT32");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("address"), ex.getMessage());
    }

    @Test
    @DisplayName("测点缺 direction：整条解析失败")
    void directionRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "businessId", "default",
                "pointName", "P1",
                "dataType", "FLOAT32",
                "channelId", "ch_1",
                "address", "40001");

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
                "channelId", "ch_1",
                "address", "40001");

        var dto = ImportFields.parsePoint(pt);

        assertEquals("in_1", dto.getPointId());
        assertEquals("biz_a", dto.getBusinessId());
        assertEquals("IN1", dto.getPointName());
        assertEquals(PointDataType.FLOAT64, dto.getDataType());
        assertEquals("°C", dto.getUnit());
        assertEquals(PointDirection.INPUT, dto.getDirection());
        assertEquals("out_1", dto.getReferencePointId());
        assertEquals(0.5, dto.getDeadband());
        assertEquals("ch_1", dto.getChannelId());
        assertEquals("40001", dto.getAddress());
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
                        "channelId", "ch_1", "address", "a"),
                Map.of("pointId", "bad_dir", "businessId", "biz_a", "pointName", "P2",
                        "dataType", "FLOAT32", "direction", "SIDEWAYS",
                        "channelId", "ch_1", "address", "b"),
                Map.of("pointId", "ok", "businessId", "biz_a", "pointName", "P3",
                        "dataType", "FLOAT32", "direction", "OUTPUT",
                        "channelId", "ch_1", "address", "c"));

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
                        "channelId", "ch_1", "address", "a"));

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
                "channelId", "ch_1",
                "address", "40001");

        var dto = ImportFields.parsePoint(pt);

        assertEquals(PointDirection.INPUT, dto.getDirection());
        assertEquals("out_1", dto.getReferencePointId());
    }

    @Test
    @DisplayName("解析通道段：code 必须被搬运（手工构造 DTO，漏一行就静默丢失）")
    void parseChannelsCarriesCode() {
        Map<String, Object> ch = Map.of(
                "channelId", "ch_1",
                "businessId", "biz",
                "channelName", "通道1",
                "code", "FZXT",
                "protocolType", "CUSTOM_TCP",
                "direction", "READ_WRITE");

        List<ChannelDTO> channels = ImportFields.parseChannels(List.of(ch));

        assertEquals(1, channels.size());
        assertEquals("FZXT", channels.get(0).getCode());
    }

    @Test
    @DisplayName("解析通道段：未提供 code 时为 null（空值表示未编码）")
    void parseChannelsCodeAbsentIsNull() {
        Map<String, Object> ch = Map.of(
                "channelId", "ch_1",
                "businessId", "biz",
                "channelName", "通道1",
                "protocolType", "CUSTOM_TCP",
                "direction", "READ_WRITE");

        List<ChannelDTO> channels = ImportFields.parseChannels(List.of(ch));

        assertNull(channels.get(0).getCode());
    }

    // ===== CSV 解析测试 =====

    private static ByteArrayInputStream csv(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CSV happy path：解析完整数据")
    void csvHappyPath() {
        String content = "pointId,pointName,address,dataType,unit,direction,referencePointId,deadband\n"
                + "p1,温度,40001,INT16,°C,OUTPUT,,0.5\n"
                + "p2,压力,40002,INT16,kPa,OUTPUT,,\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(2, result.points().size());
        assertEquals("p1", result.points().get(0).getPointId());
        assertEquals("biz", result.points().get(0).getBusinessId());
        assertEquals("ch_1", result.points().get(0).getChannelId());
        assertEquals("40001", result.points().get(0).getAddress());
        assertEquals(PointDataType.INT16, result.points().get(0).getDataType());
        assertEquals(PointDirection.OUTPUT, result.points().get(0).getDirection());
        assertEquals(0.5, result.points().get(0).getDeadband());
        assertNull(result.points().get(1).getDeadband());
    }

    @Test
    @DisplayName("CSV BOM 头自动跳过")
    void csvBomHandled() {
        String content = "﻿pointId,pointName,address,dataType,direction\n"
                + "p1,温度,40001,INT16,OUTPUT\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(1, result.points().size());
        assertEquals("p1", result.points().get(0).getPointId());
    }

    @Test
    @DisplayName("CSV 引号字段：名字里的逗号不再把行截断")
    void csvQuotedFieldWithComma() {
        String content = "pointId,pointName,address,dataType,direction\n"
                + "p1,\"温度,备用\",40001,INT16,OUTPUT\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(1, result.points().size());
        assertEquals("温度,备用", result.points().get(0).getPointName());
        // 逗号被当成字段分隔符的话，后面的列会整体错位
        assertEquals("40001", result.points().get(0).getAddress());
        assertEquals(PointDirection.OUTPUT, result.points().get(0).getDirection());
    }

    @Test
    @DisplayName("CSV 引号字段：\"\" 转义成一个双引号")
    void csvQuotedFieldWithEscapedQuote() {
        String content = "pointId,pointName,address,dataType,direction\n"
                + "p1,\"阀\"\"A\"\"\",40001,INT16,OUTPUT\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(1, result.points().size());
        assertEquals("阀\"A\"", result.points().get(0).getPointName());
        assertEquals("40001", result.points().get(0).getAddress());
    }

    @Test
    @DisplayName("CSV 中文表头：与英文表头等价")
    void csvChineseHeaderParsed() {
        String content = "测点ID,测点名称,地址,数据类型,单位,测点方向,引用测点ID,死区\n"
                + "out_1,温度,40001,INT16,°C,OUTPUT,,0.5\n"
                + "in_1,温度镜像,40001,INT16,°C,INPUT,out_1,\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(2, result.points().size());
        assertEquals("out_1", result.points().get(0).getPointId());
        assertEquals("温度", result.points().get(0).getPointName());
        assertEquals("40001", result.points().get(0).getAddress());
        assertEquals(PointDataType.INT16, result.points().get(0).getDataType());
        assertEquals("°C", result.points().get(0).getUnit());
        assertEquals(PointDirection.OUTPUT, result.points().get(0).getDirection());
        assertEquals(0.5, result.points().get(0).getDeadband());
        // 引用列同样要认：INPUT 靠它指向 OUTPUT
        assertEquals(PointDirection.INPUT, result.points().get(1).getDirection());
        assertEquals("out_1", result.points().get(1).getReferencePointId());
    }

    @Test
    @DisplayName("CSV 中文表头：ID 大小写不敏感（手改文件常写成「测点id」）")
    void csvChineseHeaderIdCaseInsensitive() {
        String content = "测点id,测点名称,地址,数据类型,测点方向\n"
                + "p1,温度,40001,INT16,OUTPUT\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals("p1", result.points().get(0).getPointId());
    }

    @Test
    @DisplayName("CSV 测点方向：列名「测点方向」+ 中文值「输出/输入」")
    void csvDirectionCanonicalNameAndChineseValues() {
        String content = "测点ID,测点名称,地址,数据类型,测点方向\n"
                + "out_1,温度,40001,INT16,输出\n"
                + "in_1,温度镜像,40001,INT16,输入\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(PointDirection.OUTPUT, result.points().get(0).getDirection());
        assertEquals(PointDirection.INPUT, result.points().get(1).getDirection());
    }

    @Test
    @DisplayName("JSON 测点：方向同样认中文值「输出/输入」")
    void jsonDirectionChineseValue() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "businessId", "biz",
                "pointName", "温度",
                "channelId", "ch_1",
                "address", "40001",
                "dataType", "INT16",
                "direction", "输出");

        var dto = ImportFields.parsePoint(pt);

        assertEquals(PointDirection.OUTPUT, dto.getDirection());
    }

    @Test
    @DisplayName("CSV 缺必填列：累积问题")
    void csvMissingRequiredColumn() {
        String content = "pointId,pointName,address,dataType\n"
                + "p1,温度,40001,INT16\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.points().isEmpty());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("direction"), result.problems().get(0));
    }

    @Test
    @DisplayName("CSV 空文件")
    void csvEmpty() {
        ImportFields.ParseResult result = ImportFields.parseCsv(csv(""), "biz", "ch_1");

        assertTrue(result.points().isEmpty());
        assertFalse(result.problems().isEmpty());
    }

    @Test
    @DisplayName("CSV INPUT 测点解析 referencePointId")
    void csvInputPoint() {
        String content = "pointId,pointName,address,dataType,direction,referencePointId\n"
                + "in1,输入1,50001,INT16,INPUT,out1\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(1, result.points().size());
        assertEquals(PointDirection.INPUT, result.points().get(0).getDirection());
        assertEquals("out1", result.points().get(0).getReferencePointId());
    }

    @Test
    @DisplayName("CSV 多行问题累积")
    void csvAccumulatesProblems() {
        String content = "pointId,pointName,address,dataType,direction\n"
                + "p1,温度,40001,INT16,\n"
                + "p2,压力,40002,INT16,\n"
                + "p3,流量,40003,INT16,OUTPUT\n";

        ImportFields.ParseResult result = ImportFields.parseCsv(csv(content), "biz", "ch_1");

        assertEquals(1, result.points().size()); // 只有 p3 成功
        assertEquals(2, result.problems().size()); // p1、p2 各一条问题
    }
}
