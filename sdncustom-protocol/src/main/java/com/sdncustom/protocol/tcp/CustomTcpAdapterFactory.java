package com.sdncustom.protocol.tcp;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolAdapterFactory;
import org.springframework.stereotype.Component;

@Component
public class CustomTcpAdapterFactory implements ProtocolAdapterFactory {

    @Override
    public ProtocolType protocolType() {
        return ProtocolType.CUSTOM_TCP;
    }

    @Override
    public ProtocolAdapter create() {
        return new CustomTcpAdapter();
    }
}
