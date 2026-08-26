package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AcquisitionEngine {

    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;
    private final ChannelService channelService;
    private final PointService pointService;
    private final HistoryService historyService;
    private final DistributionService distributionService;

    @Value("${sdncustom.acquisition.interval-ms:200}")
    private long intervalMs;

    // 缓存每个 Channel 的测点列表
    private final Map<String, List<MeasurementPoint>> channelPointsCache = new ConcurrentHashMap<>();

    /**
     * 定时采集任务
     */
    @Scheduled(fixedDelayString = "${sdncustom.acquisition.interval-ms:200}")
    public void acquire() {
        List<Channel> channels = channelRepository.findByStatus(ChannelStatus.CONNECTED);

        for (Channel channel : channels) {
            try {
                acquireChannel(channel);
            } catch (Exception e) {
                log.error("Acquisition failed for channel: {}", channel.getChannelId(), e);
            }
        }
    }

    /**
     * 采集单个 Channel 的数据
     */
    private void acquireChannel(Channel channel) {
        List<MeasurementPoint> points = channelPointsCache.computeIfAbsent(
                channel.getChannelId(),
                k -> pointRepository.findByChannelId(k));

        if (points.isEmpty()) {
            return;
        }

        try {
            ProtocolAdapter adapter = channelService.getOrCreateAdapter(channel);
            if (!adapter.isConnected()) {
                return;
            }

            List<PointValue> values = adapter.readPoints(points);

            for (PointValue pv : values) {
                // 更新实时缓存（含死区判断）
                pointService.updateValue(pv);

                // 保存历史记录
                historyService.save(pv);

                // 推送到 WebSocket 客户端
                distributionService.push(pv);
            }
        } catch (Exception e) {
            log.error("Failed to acquire data from channel: {}", channel.getChannelId(), e);
        }
    }

    /**
     * 刷新 Channel 测点缓存
     */
    public void refreshChannelPoints(String channelId) {
        channelPointsCache.remove(channelId);
    }
}
