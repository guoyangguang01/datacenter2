package com.sdncustom.protocol.mqtt;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MqttAdapterFactory 测试")
class MqttAdapterFactoryTest {

    private final MqttAdapterFactory factory = new MqttAdapterFactory();

    @Test
    @DisplayName("协议类型为 MQTT")
    void protocolType() {
        assertEquals(ProtocolType.MQTT, factory.protocolType());
    }

    @Test
    @DisplayName("每次 create 返回新的适配器实例")
    void createReturnsNewInstance() {
        ProtocolAdapter first = factory.create();
        ProtocolAdapter second = factory.create();
        assertInstanceOf(MqttAdapter.class, first);
        assertNotSame(first, second);
        assertFalse(first.isConnected());
    }
}
