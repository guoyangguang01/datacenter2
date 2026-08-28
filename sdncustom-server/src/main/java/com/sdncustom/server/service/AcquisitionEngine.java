package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    // 每通道独立线程并行采集，避免一个慢通道拖垮整个采集周期
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /**
     * 定时采集任务
     */
    @Scheduled(fixedDelayString = "${sdncustom.acquisition.interval-ms:200}")
    public void acquire() {
        List<Channel> channels = channelRepository.findByStatus(ChannelStatus.CONNECTED);

        if (channels.isEmpty()) {
            return;
        }

        log.debug("Acquiring data from {} connected channels", channels.size());

        List<CompletableFuture<Void>> futures = channels.stream()
                .map(channel -> CompletableFuture.runAsync(() -> {
                    try {
                        acquireChannel(channel);
                    } catch (Exception e) {
                        log.error("Acquisition failed for channel: {}", channel.getChannelId(), e);
                    }
                }, executor))
                .toList();

        // 等待本轮全部完成（限时），防止慢通道无限拖长周期
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Acquisition cycle timed out waiting for channels");
        }
    }

    /**
     * 采集单个 Channel 的数据
     */
    private void acquireChannel(Channel channel) {
        List<MeasurementPoint> points = pointRepository.findByChannelId(channel.getChannelId());

        if (points.isEmpty()) {
            log.debug("No points configured for channel: {}", channel.getChannelId());
            return;
        }

        ProtocolAdapter adapter = channelService.getOrCreateAdapter(channel);
        if (!adapter.isConnected()) {
            log.debug("Adapter not connected for channel: {}", channel.getChannelId());
            // 非 MQTT 协议无自动重连：若 DB 状态仍为 CONNECTED，则修正为 DISCONNECTED，消除"假连接"
            if (channel.getProtocolType() != ProtocolType.MQTT) {
                channelService.disconnect(channel.getChannelId());
            }
            return;
        }

        List<PointValue> values = adapter.readPoints(points);
        log.debug("Read {} values from channel: {}", values.size(), channel.getChannelId());

        for (PointValue pv : values) {
            // 更新实时缓存
            pointService.updateValue(pv);
            // 保存历史记录
            historyService.save(pv);
            // 推送到 WebSocket 客户端
            distributionService.push(pv);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
