package com.sdncustom.protocol.mqtt;

import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolAdapterFactory;
import org.springframework.stereotype.Component;

@Component
public class MqttAdapterFactory implements ProtocolAdapterFactory {

    @Override
    public ProtocolType protocolType() {
        return ProtocolType.MQTT;
    }

    @Override
    public ProtocolAdapter create() {
        return new MqttAdapter();
    }
}
