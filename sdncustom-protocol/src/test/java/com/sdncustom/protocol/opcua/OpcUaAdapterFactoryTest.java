package com.sdncustom.protocol.opcua;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpcUaAdapterFactory 测试")
class OpcUaAdapterFactoryTest {

    private final OpcUaAdapterFactory factory = new OpcUaAdapterFactory();

    @Test
    @DisplayName("协议类型为 OPCUA")
    void protocolType() {
        assertEquals(ProtocolType.OPCUA, factory.protocolType());
    }

    @Test
    @DisplayName("每次 create 返回新的适配器实例")
    void createReturnsNewInstance() {
        ProtocolAdapter first = factory.create();
        ProtocolAdapter second = factory.create();
        assertInstanceOf(OpcUaAdapter.class, first);
        assertNotSame(first, second);
        assertFalse(first.isConnected());
    }
}
