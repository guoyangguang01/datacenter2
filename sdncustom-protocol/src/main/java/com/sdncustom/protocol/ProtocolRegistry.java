package com.sdncustom.protocol;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ProtocolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 协议注册中心：管理"通道 -> 适配器实例"的生命周期。
 * 实例按 channelId 缓存，创建委托给对应协议的 {@link ProtocolAdapterFactory}，
 * 断开/失败时的移除由调用方（通道生命周期层）统一经本类完成。
 */
@Slf4j
@Component
public class ProtocolRegistry {

    private final Map<ProtocolType, ProtocolAdapterFactory> factories;
    private final Map<String, ProtocolAdapter> active = new ConcurrentHashMap<>();

    public ProtocolRegistry(List<ProtocolAdapterFactory> factoryList) {
        this.factories = factoryList.stream()
                .collect(Collectors.toUnmodifiableMap(ProtocolAdapterFactory::protocolType, Function.identity()));
        log.info("Protocol adapters available: {}", factories.keySet());
    }

    /**
     * 获取通道的适配器实例，不存在则通过工厂创建
     */
    public ProtocolAdapter getOrCreate(Channel channel) {
        return active.computeIfAbsent(channel.getChannelId(), channelId -> {
            ProtocolAdapterFactory factory = factories.get(channel.getProtocolType());
            if (factory == null) {
                throw new IllegalStateException("No adapter factory registered for protocol: " + channel.getProtocolType());
            }
            return factory.create();
        });
    }

    public Optional<ProtocolAdapter> get(String channelId) {
        return Optional.ofNullable(active.get(channelId));
    }

    /**
     * 断开并移除通道实例（断开异常只记录，不阻断移除）
     */
    public void release(String channelId) {
        ProtocolAdapter adapter = active.remove(channelId);
        if (adapter != null) {
            try {
                adapter.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting adapter for channel: {}", channelId, e);
            }
        }
    }

    /**
     * 仅移除实例（用于适配器已自行断开或连接失败的场景）
     */
    public void remove(String channelId) {
        active.remove(channelId);
    }

    public boolean hasChannel(String channelId) {
        return active.containsKey(channelId);
    }
}
