package com.sdncustom.protocol;

import com.sdncustom.common.model.enums.ProtocolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议注册中心
 * 管理所有协议适配器实例
 */
@Slf4j
@Component
public class ProtocolRegistry {

    private final Map<ProtocolType, ProtocolAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * 注册协议适配器
     */
    public void register(ProtocolType type, ProtocolAdapter adapter) {
        adapters.put(type, adapter);
        log.info("Registered protocol adapter: {}", type);
    }

    /**
     * 获取协议适配器
     */
    public ProtocolAdapter getAdapter(ProtocolType type) {
        ProtocolAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalStateException("No adapter registered for protocol: " + type);
        }
        return adapter;
    }

    /**
     * 检查协议是否已注册
     */
    public boolean hasAdapter(ProtocolType type) {
        return adapters.containsKey(type);
    }
}
