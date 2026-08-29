package com.sdncustom.server.service;

import com.sdncustom.common.model.PointSource;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测点 -> 绑定通道 的内存路由缓存（WebSocket fan-out 用）。
 * channelsOf 首次访问冷加载（主 channelId + 附加来源通道），
 * 测点/通道配置变化时调用 invalidate 失效。
 */
@Service
@RequiredArgsConstructor
public class PointBindingRegistry {

    private final MeasurementPointRepository pointRepository;
    private final PointSourceRepository pointSourceRepository;

    private final Map<String, Set<String>> channelsByPoint = new ConcurrentHashMap<>();

    /** 该测点绑定的全部通道（主 + 附加来源） */
    public Set<String> channelsOf(String pointId) {
        return channelsByPoint.computeIfAbsent(pointId, this::loadChannels);
    }

    public void invalidate(String pointId) {
        channelsByPoint.remove(pointId);
    }

    public void invalidateChannel(String channelId) {
        channelsByPoint.entrySet().removeIf(e -> e.getValue().contains(channelId));
    }

    private Set<String> loadChannels(String pointId) {
        Set<String> channels = new LinkedHashSet<>();
        pointRepository.findById(pointId).ifPresent(p -> channels.add(p.getChannelId()));
        for (PointSource source : pointSourceRepository.findByPointId(pointId)) {
            channels.add(source.getChannelId());
        }
        return channels;
    }
}
