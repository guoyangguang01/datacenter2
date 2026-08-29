package com.sdncustom.protocol.opcua;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolAdapterFactory;
import org.springframework.stereotype.Component;

@Component
public class OpcUaAdapterFactory implements ProtocolAdapterFactory {

    @Override
    public ProtocolType protocolType() {
        return ProtocolType.OPCUA;
    }

    @Override
    public ProtocolAdapter create() {
        return new OpcUaAdapter();
    }
}
