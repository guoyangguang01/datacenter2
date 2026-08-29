package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Channel 生命周期管理。
 * connect/disconnect 是状态收敛的唯一入口，按通道加锁串行化，
 * 适配器实例统一经 {@link ProtocolRegistry} 获取/释放，
 * DB status 是适配器运行时状态的投影。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;
    private final PointSourceService pointSourceService;
    private final PointBindingRegistry pointBindingRegistry;
    private final PointValueCacheRepository pointValueCache;
    private final ProtocolRegistry protocolRegistry;
    private final ChangeGate changeGate;

    // per-channel 生命周期锁：串行化用户操作与采集循环的状态修正
    private final ConcurrentHashMap<String, ReentrantLock> lifecycleLocks = new ConcurrentHashMap<>();

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
     * 根据 ID 查询 Channel（不存在返回 null）
     */
    public Channel findByIdOrNull(String channelId) {
        return channelRepository.findById(channelId).orElse(null);
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
     * 更新 Channel。
     * 连接参数（协议/配置）变化时销毁旧适配器实例并按需以新配置重连，
     * 修复"改配置后仍沿用旧连接"的问题。
     */
    @Transactional
    public Channel update(String channelId, ChannelDTO dto) {
        Channel channel = findById(channelId);
        boolean connectionChanged = channel.getProtocolType() != dto.getProtocolType()
                || !Objects.equals(channel.getConnectionConfig(), dto.getConnectionConfig());
        boolean wasConnected = channel.getStatus() == ChannelStatus.CONNECTED;

        if (connectionChanged && channel.getStatus() != ChannelStatus.DISCONNECTED) {
            disconnect(channelId);
            channel.setStatus(ChannelStatus.DISCONNECTED);
        }

        channel.setChannelName(dto.getChannelName());
        channel.setProtocolType(dto.getProtocolType());
        channel.setDirection(dto.getDirection());
        channel.setConnectionConfig(dto.getConnectionConfig());
        channel.setAutoConnect(dto.isAutoConnect());
        Channel saved = channelRepository.save(channel);

        if (connectionChanged && wasConnected && saved.isAutoConnect()) {
            try {
                connect(channelId);
            } catch (Exception e) {
                // 新配置连不上：connect() 已将状态收敛为 ERROR，这里吞掉异常，
                // 保证用户刚保存的配置不因回滚而丢失
                log.warn("Auto-reconnect after config change failed for channel {}, status set to ERROR: {}",
                        channelId, e.getMessage());
            }
        }
        return saved;
    }

    /**
     * 删除 Channel（先释放运行态，再级联删除测点）
     */
    @Transactional
    public void delete(String channelId) {
        findById(channelId);
        disconnect(channelId);
        lifecycleLocks.remove(channelId);

        // 该通道涉及的所有测点（主绑定 + 附加来源）：清合并基线，避免陈旧权威值滞留
        List<MeasurementPoint> boundPoints = pointSourceService.findPointsForChannel(channelId);
        changeGate.removePoints(boundPoints.stream().map(MeasurementPoint::getPointId).distinct().toList());

        // 本通道作为其它测点的附加来源：删除这些来源行
        pointSourceService.deleteByChannelId(channelId);

        // 删除主绑定在本通道的测点（连带其附加来源行与缓存）
        List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
        for (MeasurementPoint point : points) {
            pointValueCache.delete(point.getPointId());
            pointSourceService.deleteByPointId(point.getPointId());
        }
        pointRepository.deleteByChannelId(channelId);
        channelRepository.deleteById(channelId);
        pointBindingRegistry.invalidateChannel(channelId);
    }

    /**
     * 连接 Channel：以适配器运行时状态为准，DB status 只做投影。
     * 幂等——已连接时仅对齐状态。
     */
    public void connect(String channelId) {
        ReentrantLock lock = lockFor(channelId);
        lock.lock();
        try {
            Channel channel = findById(channelId);
            ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);

            if (adapter.isConnected()) {
                if (channel.getStatus() != ChannelStatus.CONNECTED) {
                    channel.setStatus(ChannelStatus.CONNECTED);
                    channelRepository.save(channel);
                }
                log.debug("Channel already connected, state aligned: {}", channelId);
                return;
            }

            try {
                adapter.connect(channel);
                // 统一连接后钩子：订阅型协议（MQTT）在此建立测点订阅（主绑定 + 附加来源）
                adapter.onConnected(pointSourceService.findPointsForChannel(channelId));
                channel.setStatus(ChannelStatus.CONNECTED);
                channelRepository.save(channel);
                log.info("Channel connected: {}", channelId);
            } catch (Exception e) {
                // 连接失败：销毁半成品实例，下次 connect 以新实例重试
                protocolRegistry.remove(channelId);
                channel.setStatus(ChannelStatus.ERROR);
                channelRepository.save(channel);
                log.error("Failed to connect channel: {}", channelId, e);
                throw new RuntimeException("Connection failed", e);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 断开 Channel：无条件收敛到 DISCONNECTED（释放实例 + 测点标记 COMM_LOST），幂等。
     */
    public void disconnect(String channelId) {
        ReentrantLock lock = lockFor(channelId);
        lock.lock();
        try {
            Channel channel = findById(channelId);

            // 无论适配器是否成功断开，都强制重置状态
            protocolRegistry.release(channelId);
            markPointsCommLost(channelId);

            channel.setStatus(ChannelStatus.DISCONNECTED);
            channelRepository.save(channel);
            log.info("Channel disconnected: {}", channelId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 自动连接所有 autoConnect=true 的 Channel（并行连接，避免单个通道连接挂住阻塞其它通道）
     */
    public void autoConnectAll() {
        List<Channel> channels = channelRepository.findByAutoConnect(true);
        channels.parallelStream().forEach(channel -> {
            try {
                connect(channel.getChannelId());
            } catch (Exception e) {
                log.error("Auto-connect failed for channel: {}", channel.getChannelId(), e);
            }
        });
    }

    /**
     * 采集循环检测到运行时断开时调用：仅对齐 DB 状态（不重复走释放路径），
     * 与用户 connect/disconnect 通过同一把锁互斥。
     */
    public void syncDisconnected(String channelId) {
        ReentrantLock lock = lockFor(channelId);
        lock.lock();
        try {
            Channel channel = findByIdOrNull(channelId);
            if (channel == null || channel.getStatus() == ChannelStatus.DISCONNECTED) {
                return;
            }
            protocolRegistry.remove(channelId);
            markPointsCommLost(channelId);
            channel.setStatus(ChannelStatus.DISCONNECTED);
            channelRepository.save(channel);
            log.info("Channel runtime lost connection, state synced: {}", channelId);
        } finally {
            lock.unlock();
        }
    }

    private void markPointsCommLost(String channelId) {
        try {
            List<MeasurementPoint> boundPoints = pointSourceService.findPointsForChannel(channelId);
            List<String> pointIds = boundPoints.stream().map(MeasurementPoint::getPointId).distinct().toList();
            // 清掉该通道来源的合并基线，让其余来源自然接管
            changeGate.removePoints(pointIds);
            for (String pointId : pointIds) {
                // 仅当该通道是唯一绑定时才标记 COMM_LOST；多来源点由其余 GOOD 来源继续供给
                if (pointSourceService.bindingChannelIds(pointId).size() <= 1) {
                    PointValue pv = PointValue.commLost(pointId);
                    pv.setSourceChannelId(channelId);
                    pointValueCache.save(pv);
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to mark points as COMM_LOST for channel: {}", channelId, e);
        }
    }

    private ReentrantLock lockFor(String channelId) {
        return lifecycleLocks.computeIfAbsent(channelId, k -> new ReentrantLock());
    }
}
