package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据模型单元测试
 */
@DisplayName("数据模型测试")
class ModelTest {

    @Test
    @DisplayName("MeasurementPoint 创建和属性")
    void measurementPoint() {
        MeasurementPoint point = new MeasurementPoint();
        point.setPointId("point_001");
        point.setPointName("测试测点");
        point.setChannelId("ch_001");
        point.setAddress("40001");
        point.setDataType(PointDataType.INT16);
        point.setUnit("°C");
        point.setWritable(true);

        assertEquals("point_001", point.getPointId());
        assertEquals("测试测点", point.getPointName());
        assertEquals("ch_001", point.getChannelId());
        assertEquals("40001", point.getAddress());
        assertEquals(PointDataType.INT16, point.getDataType());
        assertEquals("°C", point.getUnit());
        assertTrue(point.isWritable());
    }

    @Test
    @DisplayName("MeasurementPoint 时间戳自动填充")
    void measurementPointTimestamps() {
        MeasurementPoint point = new MeasurementPoint();
        point.setPointId("point_001");
        point.onCreate();

        assertNotNull(point.getCreateTime());
        assertNotNull(point.getUpdateTime());
        assertEquals(point.getCreateTime(), point.getUpdateTime());

        // 模拟更新
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
        point.onUpdate();

        assertNotNull(point.getUpdateTime());
        assertTrue(point.getUpdateTime().isAfter(point.getCreateTime()) ||
                   point.getUpdateTime().equals(point.getCreateTime()));
    }

    @Test
    @DisplayName("Channel 创建和属性")
    void channel() {
        Channel channel = new Channel();
        channel.setChannelId("ch_001");
        channel.setChannelName("测试通道");
        channel.setProtocolType(ProtocolType.CUSTOM_TCP);
        channel.setDirection(ChannelDirection.READ_WRITE);
        channel.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");
        channel.setAutoConnect(true);
        channel.setStatus(ChannelStatus.DISCONNECTED);

        assertEquals("ch_001", channel.getChannelId());
        assertEquals("测试通道", channel.getChannelName());
        assertEquals(ProtocolType.CUSTOM_TCP, channel.getProtocolType());
        assertEquals(ChannelDirection.READ_WRITE, channel.getDirection());
        assertEquals("{\"host\":\"localhost\",\"port\":9001}", channel.getConnectionConfig());
        assertTrue(channel.isAutoConnect());
        assertEquals(ChannelStatus.DISCONNECTED, channel.getStatus());
    }

    @Test
    @DisplayName("PointValue 创建和属性")
    void pointValue() {
        PointValue value = new PointValue();
        value.setPointId("point_001");
        value.setValue(25.6);
        value.setQuality(PointQuality.GOOD);
        value.setTimestamp(System.currentTimeMillis());
        value.setSourceChannelId("ch_001");

        assertEquals("point_001", value.getPointId());
        assertEquals(25.6, value.getValue());
        assertEquals(PointQuality.GOOD, value.getQuality());
        assertTrue(value.getTimestamp() > 0);
        assertEquals("ch_001", value.getSourceChannelId());
    }

    @Test
    @DisplayName("PointValue 通信丢失状态")
    void pointValueCommLost() {
        PointValue value = PointValue.commLost("point_001");

        assertEquals("point_001", value.getPointId());
        assertNull(value.getValue());
        assertEquals(PointQuality.COMM_LOST, value.getQuality());
        assertTrue(value.getTimestamp() > 0);
    }

    @Test
    @DisplayName("PointDataType 枚举值")
    void pointDataType() {
        assertEquals(6, PointDataType.values().length);
        assertNotNull(PointDataType.BOOL);
        assertNotNull(PointDataType.INT16);
        assertNotNull(PointDataType.INT32);
        assertNotNull(PointDataType.FLOAT32);
        assertNotNull(PointDataType.FLOAT64);
        assertNotNull(PointDataType.STRING);
    }

    @Test
    @DisplayName("PointQuality 枚举值")
    void pointQuality() {
        assertEquals(4, PointQuality.values().length);
        assertNotNull(PointQuality.GOOD);
        assertNotNull(PointQuality.BAD);
        assertNotNull(PointQuality.UNCERTAIN);
        assertNotNull(PointQuality.COMM_LOST);
    }

    @Test
    @DisplayName("ProtocolType 枚举值")
    void protocolType() {
        assertEquals(4, ProtocolType.values().length);
        assertNotNull(ProtocolType.CUSTOM_TCP);
        assertNotNull(ProtocolType.MODBUS_TCP);
        assertNotNull(ProtocolType.MQTT);
        assertNotNull(ProtocolType.OPCUA);
    }

    @Test
    @DisplayName("ChannelStatus 枚举值")
    void channelStatus() {
        assertEquals(3, ChannelStatus.values().length);
        assertNotNull(ChannelStatus.CONNECTED);
        assertNotNull(ChannelStatus.DISCONNECTED);
        assertNotNull(ChannelStatus.ERROR);
    }

    @Test
    @DisplayName("ChannelDirection 枚举值")
    void channelDirection() {
        assertEquals(3, ChannelDirection.values().length);
        assertNotNull(ChannelDirection.READ_ONLY);
        assertNotNull(ChannelDirection.WRITE_ONLY);
        assertNotNull(ChannelDirection.READ_WRITE);
    }

    @Test
    @DisplayName("MeasurementPoint 全参构造函数")
    void measurementPointAllArgsConstructor() {
        LocalDateTime now = LocalDateTime.now();
        MeasurementPoint point = new MeasurementPoint(
                "point_001", "测试测点", "ch_001", "40001",
                PointDataType.INT16, "°C", true, 0.5, now, now, null
        );

        assertEquals("point_001", point.getPointId());
        assertEquals("测试测点", point.getPointName());
        assertEquals("ch_001", point.getChannelId());
        assertEquals("40001", point.getAddress());
        assertEquals(PointDataType.INT16, point.getDataType());
        assertEquals("°C", point.getUnit());
        assertTrue(point.isWritable());
        assertEquals(0.5, point.getDeadband());
        assertEquals(now, point.getCreateTime());
        assertEquals(now, point.getUpdateTime());
    }

    @Test
    @DisplayName("PointValue 不同数据类型")
    void pointValueDataTypes() {
        // Boolean
        PointValue boolValue = new PointValue();
        boolValue.setValue(true);
        assertEquals(true, boolValue.getValue());

        // Integer
        PointValue intValue = new PointValue();
        intValue.setValue(12345);
        assertEquals(12345, intValue.getValue());

        // Float
        PointValue floatValue = new PointValue();
        floatValue.setValue(25.6f);
        assertEquals(25.6f, floatValue.getValue());

        // Double
        PointValue doubleValue = new PointValue();
        doubleValue.setValue(3.14159);
        assertEquals(3.14159, doubleValue.getValue());

        // String
        PointValue stringValue = new PointValue();
        stringValue.setValue("Hello");
        assertEquals("Hello", stringValue.getValue());
    }
}
