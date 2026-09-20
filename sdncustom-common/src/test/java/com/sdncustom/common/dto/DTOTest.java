package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.common.model.enums.ProtocolType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DTO 单元测试
 */
@DisplayName("DTO 测试")
class DTOTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("MeasurementPointDTO 有效数据")
    void measurementPointDTOValid() {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId("point_001");
        dto.setPointName("测试测点");
        dto.setChannelId("ch_001");
        dto.setAddress("40001");
        dto.setDataType(PointDataType.INT16);
        dto.setUnit("°C");
        dto.setDirection(PointDirection.OUTPUT);

        Set<ConstraintViolation<MeasurementPointDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("MeasurementPointDTO 缺少必填字段")
    void measurementPointDTOMissingFields() {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        // 不设置任何字段

        Set<ConstraintViolation<MeasurementPointDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.size() >= 4); // pointId, pointName, dataType, channelId, address
    }

    @Test
    @DisplayName("MeasurementPointDTO 默认值")
    void measurementPointDTODefaults() {
        MeasurementPointDTO dto = new MeasurementPointDTO();

        // direction 无默认值；referencePointId 仅 INPUT 使用
        assertNull(dto.getDirection());
        assertNull(dto.getReferencePointId());
    }

    /**
     * direction 与 businessId 同规：创建时必填、更新时静默忽略，因此 DTO 上不能有 @NotNull
     * ——PUT /api/points/{id} 的编辑请求不会重传该字段，bean validation 会先把请求拦成 400，
     * 让 service 层的"静默忽略"永远走不到。必填由 PointDirectionValidator 与 ImportFields 保证。
     */
    @Test
    @DisplayName("MeasurementPointDTO 缺 direction 时 bean validation 放行（必填由服务层把关）")
    void measurementPointDTOWithoutDirectionPassesBeanValidation() {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId("point_001");
        dto.setPointName("测试测点");
        dto.setChannelId("ch_001");
        dto.setAddress("40001");
        dto.setDataType(PointDataType.INT16);
        dto.setDirection(null);

        assertTrue(validator.validate(dto).isEmpty(),
                "direction 是创建必填、更新忽略的字段：DTO 上加 @NotNull 会让编辑请求全部 400");
        assertNull(dto.getDirection());
    }

    @Test
    @DisplayName("ChannelDTO 有效数据")
    void channelDTOValid() {
        ChannelDTO dto = new ChannelDTO();
        dto.setChannelId("ch_001");
        dto.setChannelName("测试通道");
        dto.setProtocolType(ProtocolType.CUSTOM_TCP);
        dto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");
        dto.setAutoConnect(false);

        Set<ConstraintViolation<ChannelDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("ChannelDTO 默认值")
    void channelDTODefaults() {
        ChannelDTO dto = new ChannelDTO();

        assertTrue(dto.isAutoConnect()); // 默认自动连接
    }

    @Test
    @DisplayName("BusinessSystemDTO 有效数据与必填校验")
    void businessSystemDTO() {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId("biz_a");
        dto.setBusinessName("业务A");
        dto.setDescription("描述");
        assertTrue(validator.validate(dto).isEmpty());

        BusinessSystemDTO empty = new BusinessSystemDTO();
        Set<ConstraintViolation<BusinessSystemDTO>> violations = validator.validate(empty);
        assertEquals(2, violations.size()); // businessId, businessName
    }

    @Test
    @DisplayName("ApiResponse 成功响应")
    void apiResponseSuccess() {
        ApiResponse<String> response = ApiResponse.success("test");

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertEquals("test", response.getData());
    }

    @Test
    @DisplayName("ApiResponse 成功响应 - 无数据")
    void apiResponseSuccessNoData() {
        ApiResponse<Void> response = ApiResponse.success();

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("ApiResponse 错误响应")
    void apiResponseError() {
        ApiResponse<Void> response = ApiResponse.error(500, "服务器错误");

        assertEquals(500, response.getCode());
        assertEquals("服务器错误", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("PointValueDTO 创建")
    void pointValueDTO() {
        PointValueDTO dto = new PointValueDTO();
        dto.setPointId("point_001");
        dto.setValue(25.6);
        dto.setQuality(PointQuality.GOOD);
        dto.setTimestamp(System.currentTimeMillis());
        dto.setSourceChannelId("ch_001");

        assertEquals("point_001", dto.getPointId());
        assertEquals(25.6, dto.getValue());
        assertEquals(PointQuality.GOOD, dto.getQuality());
        assertTrue(dto.getTimestamp() > 0);
        assertEquals("ch_001", dto.getSourceChannelId());
    }

    @Test
    @DisplayName("MeasurementPointDTO 各数据类型")
    void measurementPointDTODataTypes() {
        // BOOL
        MeasurementPointDTO boolDto = new MeasurementPointDTO();
        boolDto.setPointId("bool_001");
        boolDto.setPointName("Bool Point");
        boolDto.setChannelId("ch_001");
        boolDto.setAddress("00001");
        boolDto.setDataType(PointDataType.BOOL);
        boolDto.setDirection(PointDirection.OUTPUT);
        assertTrue(validator.validate(boolDto).isEmpty());

        // INT16
        MeasurementPointDTO int16Dto = new MeasurementPointDTO();
        int16Dto.setPointId("int16_001");
        int16Dto.setPointName("Int16 Point");
        int16Dto.setChannelId("ch_001");
        int16Dto.setAddress("40001");
        int16Dto.setDataType(PointDataType.INT16);
        int16Dto.setDirection(PointDirection.OUTPUT);
        assertTrue(validator.validate(int16Dto).isEmpty());

        // FLOAT64
        MeasurementPointDTO float64Dto = new MeasurementPointDTO();
        float64Dto.setPointId("float64_001");
        float64Dto.setPointName("Float64 Point");
        float64Dto.setChannelId("ch_001");
        float64Dto.setAddress("ns=2;s=Temperature");
        float64Dto.setDataType(PointDataType.FLOAT64);
        float64Dto.setDirection(PointDirection.OUTPUT);
        assertTrue(validator.validate(float64Dto).isEmpty());

        // STRING
        MeasurementPointDTO stringDto = new MeasurementPointDTO();
        stringDto.setPointId("string_001");
        stringDto.setPointName("String Point");
        stringDto.setChannelId("ch_001");
        stringDto.setAddress("status");
        stringDto.setDataType(PointDataType.STRING);
        stringDto.setDirection(PointDirection.OUTPUT);
        assertTrue(validator.validate(stringDto).isEmpty());
    }

    @Test
    @DisplayName("ChannelDTO 各协议类型")
    void channelDTOProtocolTypes() {
        // CUSTOM_TCP
        ChannelDTO tcpDto = new ChannelDTO();
        tcpDto.setChannelId("ch_tcp");
        tcpDto.setChannelName("TCP Channel");
        tcpDto.setProtocolType(ProtocolType.CUSTOM_TCP);
        tcpDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");
        assertTrue(validator.validate(tcpDto).isEmpty());

        // MODBUS_TCP
        ChannelDTO modbusDto = new ChannelDTO();
        modbusDto.setChannelId("ch_modbus");
        modbusDto.setChannelName("Modbus Channel");
        modbusDto.setProtocolType(ProtocolType.MODBUS_TCP);
        modbusDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":502,\"unitId\":1}");
        assertTrue(validator.validate(modbusDto).isEmpty());

        // MQTT
        ChannelDTO mqttDto = new ChannelDTO();
        mqttDto.setChannelId("ch_mqtt");
        mqttDto.setChannelName("MQTT Channel");
        mqttDto.setProtocolType(ProtocolType.MQTT);
        mqttDto.setConnectionConfig("{\"broker\":\"tcp://localhost:1883\"}");
        assertTrue(validator.validate(mqttDto).isEmpty());

        // OPCUA
        ChannelDTO opcuaDto = new ChannelDTO();
        opcuaDto.setChannelId("ch_opcua");
        opcuaDto.setChannelName("OPC-UA Channel");
        opcuaDto.setProtocolType(ProtocolType.OPCUA);
        opcuaDto.setConnectionConfig("{\"endpoint\":\"opc.tcp://localhost:4840\"}");
        assertTrue(validator.validate(opcuaDto).isEmpty());
    }
}
