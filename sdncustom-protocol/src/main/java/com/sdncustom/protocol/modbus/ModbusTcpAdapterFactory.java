package com.sdncustom.protocol.modbus;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolAdapterFactory;
import org.springframework.stereotype.Component;

@Component
public class ModbusTcpAdapterFactory implements ProtocolAdapterFactory {

    @Override
    public ProtocolType protocolType() {
        return ProtocolType.MODBUS_TCP;
    }

    @Override
    public ProtocolAdapter create() {
        return new ModbusTcpAdapter();
    }
}
