package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.protocol.modbus.ModbusTcpAdapter;
import com.sdncustom.protocol.opcua.OpcUaAdapter;
import com.sdncustom.protocol.tcp.CustomTcpAdapter;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;
    private final ProtocolRegistry protocolRegistry;

    /**
     * 查询所有 Channel
     */
    public List<Channel> findAll() {
        return channelRepository.findAll();
    }

    /**
     * 根据 ID 查询 Channel
     */
    public Channel findById(String channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new ResourceNotFoundException("Channel", channelId));
    }

    /**
     * 创建 Channel
     */
    @Transactional
    public Channel create(ChannelDTO dto) {
        Channel channel = new Channel();
        channel.setChannelId(dto.getChannelId());
        channel.setChannelName(dto.getChannelName());
        channel.setProtocolType(dto.getProtocolType());
        channel.setDirection(dto.getDirection());
        channel.setConnectionConfig(dto.getConnectionConfig());
        channel.setAutoConnect(dto.isAutoConnect());
        channel.setStatus(ChannelStatus.DISCONNECTED);
        return channelRepository.save(channel);
    }

    /**
     * 更新 Channel
     */
    @Transactional
    public Channel update(String channelId, ChannelDTO dto) {
        Channel channel = findById(channelId);
        channel.setChannelName(dto.getChannelName());
        channel.setProtocolType(dto.getProtocolType());
        channel.setDirection(dto.getDirection());
        channel.setConnectionConfig(dto.getConnectionConfig());
        channel.setAutoConnect(dto.isAutoConnect());
        return channelRepository.save(channel);
    }

    /**
     * 删除 Channel
     */
    @Transactional
    public void delete(String channelId) {
        Channel channel = findById(channelId);
        // 先断开连接
        if (channel.getStatus() == ChannelStatus.CONNECTED) {
            disconnect(channelId);
        }
        // 删除关联的测点值缓存
        List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
        for (MeasurementPoint point : points) {
            pointValueCache.delete(point.getPointId());
        }
        // 删除关联的测点
        pointRepository.deleteByChannelId(channelId);
        channelRepository.deleteById(channelId);
    }

    /**
     * 连接 Channel
     */
    public void connect(String channelId) {
        Channel channel = findById(channelId);
        if (channel.getStatus() == ChannelStatus.CONNECTED) {
            log.warn("Channel already connected: {}", channelId);
            return;
        }

        try {
            ProtocolAdapter adapter = getOrCreateAdapter(channel);
            adapter.connect(channel);
            channel.setStatus(ChannelStatus.CONNECTED);
            channelRepository.save(channel);
            log.info("Channel connected: {}", channelId);
        } catch (Exception e) {
            channel.setStatus(ChannelStatus.ERROR);
            channelRepository.save(channel);
            log.error("Failed to connect channel: {}", channelId, e);
            throw new RuntimeException("Connection failed", e);
        }
    }

    /**
     * 断开 Channel
     */
    public void disconnect(String channelId) {
        Channel channel = findById(channelId);
        if (channel.getStatus() == ChannelStatus.DISCONNECTED) {
            log.warn("Channel already disconnected: {}", channelId);
            return;
        }

        try {
            ProtocolAdapter adapter = getOrCreateAdapter(channel);
            adapter.disconnect();

            // 标记所有测点为 COMM_LOST
            List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
            for (MeasurementPoint point : points) {
                PointValue pv = PointValue.commLost(point.getPointId());
                pv.setSourceChannelId(channelId);
                pointValueCache.save(pv);
            }

            channel.setStatus(ChannelStatus.DISCONNECTED);
            channelRepository.save(channel);
            log.info("Channel disconnected: {}", channelId);
        } catch (Exception e) {
            log.error("Failed to disconnect channel: {}", channelId, e);
            channel.setStatus(ChannelStatus.ERROR);
            channelRepository.save(channel);
        }
    }

    /**
     * 获取或创建协议适配器
     */
    public ProtocolAdapter getOrCreateAdapter(Channel channel) {
        ProtocolType type = channel.getProtocolType();
        if (type == ProtocolType.CUSTOM_TCP) {
            return CustomTcpAdapter.getInstance(channel.getChannelId());
        }
        if (type == ProtocolType.MODBUS_TCP) {
            return ModbusTcpAdapter.getInstance(channel.getChannelId());
        }
        if (type == ProtocolType.OPCUA) {
            return OpcUaAdapter.getInstance(channel.getChannelId());
        }
        return protocolRegistry.getAdapter(type);
    }

    /**
     * 自动连接所有 autoConnect=true 的 Channel
     */
    public void autoConnectAll() {
        List<Channel> channels = channelRepository.findByAutoConnect(true);
        for (Channel channel : channels) {
            try {
                connect(channel.getChannelId());
            } catch (Exception e) {
                log.error("Auto-connect failed for channel: {}", channel.getChannelId(), e);
            }
        }
    }
}
