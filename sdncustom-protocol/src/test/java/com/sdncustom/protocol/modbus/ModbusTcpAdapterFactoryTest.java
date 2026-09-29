package com.sdncustom.protocol.modbus;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModbusTcpAdapterFactory 测试")
class ModbusTcpAdapterFactoryTest {

    private final ModbusTcpAdapterFactory factory = new ModbusTcpAdapterFactory();

    @Test
    @DisplayName("协议类型为 MODBUS_TCP")
    void protocolType() {
        assertEquals(ProtocolType.MODBUS_TCP, factory.protocolType());
    }

    @Test
    @DisplayName("每次 create 返回新的适配器实例")
    void createReturnsNewInstance() {
        ProtocolAdapter first = factory.create();
        ProtocolAdapter second = factory.create();
        assertInstanceOf(ModbusTcpAdapter.class, first);
        assertInstanceOf(ModbusTcpAdapter.class, second);
        assertNotSame(first, second);
        assertFalse(first.isConnected());
    }
}
