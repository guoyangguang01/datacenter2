package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import com.sdncustom.server.support.TransactionHooks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private final PointValueCacheRepository pointValueCache;
    private final ProtocolRegistry protocolRegistry;
    private final ChangeGate changeGate;
    private final BusinessSystemService businessSystemService;
    private final DistributionService distributionService;

    // per-channel 生命周期锁：串行化用户操作与采集循环的状态修正
    private final ConcurrentHashMap<String, ReentrantLock> lifecycleLocks = new ConcurrentHashMap<>();

    /**
     * 期望保持连接的通道：connect 加入、disconnect 移除。
     * 掉线（syncDisconnected）不移除，交给 {@link ChannelReconnectScheduler} 退避重连。
     */
    private final Set<String> desiredConnected = ConcurrentHashMap.newKeySet();

    /** 期望连接的通道快照（供重连调度器使用） */
    public Set<String> desiredConnectedIds() {
        return Set.copyOf(desiredConnected);
    }

    /**
     * 查询所有 Channel
     */
    public List<Channel> findAll() {
        return channelRepository.findAll();
    }

    /**
     * 查询指定业务下的所有 Channel
     */
    public List<Channel> findByBusinessId(String businessId) {
        return channelRepository.findByBusinessId(businessId);
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
        businessSystemService.requireExists(dto.getBusinessId());
        requireCodeAvailable(dto.getBusinessId(), dto.getCode(), null);
        Channel channel = new Channel();
        channel.setChannelId(dto.getChannelId());
        channel.setBusinessId(dto.getBusinessId());
        channel.setChannelName(dto.getChannelName());
        channel.setCode(dto.getCode());
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
        // 先校验再改实体：归属不可变更，唯一性按库中现值校验并排除自身
        requireCodeAvailable(channel.getBusinessId(), dto.getCode(), channelId);

        boolean connectionChanged = channel.getProtocolType() != dto.getProtocolType()
                || !Objects.equals(channel.getConnectionConfig(), dto.getConnectionConfig());
        boolean wasConnected = channel.getStatus() == ChannelStatus.CONNECTED;

        // 事务内先把状态收敛为 DISCONNECTED，采集循环立刻不再碰这条通道
        if (connectionChanged && channel.getStatus() != ChannelStatus.DISCONNECTED) {
            channel.setStatus(ChannelStatus.DISCONNECTED);
        }

        channel.setChannelName(dto.getChannelName());
        channel.setCode(dto.getCode());
        channel.setProtocolType(dto.getProtocolType());
        channel.setDirection(dto.getDirection());
        channel.setConnectionConfig(dto.getConnectionConfig());
        channel.setAutoConnect(dto.isAutoConnect());
        Channel saved = channelRepository.save(channel);

        if (connectionChanged) {
            // 关旧适配器（要关 socket）与按新配置重连都是远端动作，放到提交之后。
            TransactionHooks.afterCommit(() -> {
                protocolRegistry.release(channelId);
                markPointsCommLost(channelId);
                if (wasConnected && saved.isAutoConnect()) {
                    try {
                        connect(channelId);
                    } catch (Exception e) {
                        log.warn("Auto-reconnect after config change failed for channel {}, status set to ERROR: {}",
                                channelId, e.getMessage());
                    }
                }
            });
        }
        return saved;
    }

    /**
     * 外部系统代码在业务内唯一。
     * 空值表示「未编码」，合法且直接放行（现有调用方都不传 code）；
     * 非空时同一业务下不允许重复，update 时用 selfChannelId 排除自身。
     */
    private void requireCodeAvailable(String businessId, String code, String selfChannelId) {
        if (code == null || code.isBlank()) {
            return;
        }
        boolean taken = channelRepository.findByBusinessIdAndCode(businessId, code).stream()
                .anyMatch(existing -> !existing.getChannelId().equals(selfChannelId));
        if (taken) {
            throw new BusinessException(400, "该业务下通道编码已存在: " + code);
        }
    }

    /**
     * 删除 Channel（先释放运行态，再级联删除关联的测点）
     */
    @Transactional
    public void delete(String channelId) {
        findById(channelId);
        // 不再期望连接，重连调度器不该把它拉回来
        desiredConnected.remove(channelId);

        // 删除该通道关联的所有测点（一对一：通道删则测点跟着删）
        List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
        for (MeasurementPoint point : points) {
            pointValueCache.delete(point.getPointId());
            changeGate.removePoints(List.of(point.getPointId()));
        }
        pointRepository.deleteByChannelId(channelId);
        channelRepository.deleteById(channelId);

        // 释放适配器要关 socket，是远端动作，放到提交之后
        TransactionHooks.afterCommit(() -> releaseRuntime(channelId));
    }

    /** 释放通道运行态：关闭适配器并通知订阅端；在事务提交后执行 */
    private void releaseRuntime(String channelId) {
        ReentrantLock lock = lockFor(channelId);
        lock.lock();
        try {
            protocolRegistry.release(channelId);
            distributionService.pushChannelStatus(channelId, ChannelStatus.DISCONNECTED.name());
        } finally {
            lock.unlock();
            lifecycleLocks.remove(channelId);
        }
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
            // 用户/启动显式要求连接 → 记入期望集合，掉线后由重连调度器负责拉起来
            desiredConnected.add(channelId);
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
                // 统一连接后钩子：订阅型协议（MQTT）在此建立测点订阅
                List<MeasurementPoint> outputPoints = pointRepository.findByChannelIdAndDirection(
                        channelId, PointDirection.OUTPUT);
                adapter.onConnected(outputPoints);
                channel.setStatus(ChannelStatus.CONNECTED);
                channelRepository.save(channel);
                distributionService.pushChannelStatus(channelId, ChannelStatus.CONNECTED.name());
                log.info("Channel connected: {}", channelId);
            } catch (Exception e) {
                // 连接失败：销毁半成品实例，下次 connect 以新实例重试
                protocolRegistry.remove(channelId);
                channel.setStatus(ChannelStatus.ERROR);
                channelRepository.save(channel);
                distributionService.pushChannelStatus(channelId, ChannelStatus.ERROR.name());
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
            // 用户主动断开 → 不再期望连接，重连调度器不该把它拉回来
            desiredConnected.remove(channelId);

            // 无论适配器是否成功断开，都强制重置状态
            protocolRegistry.release(channelId);
            markPointsCommLost(channelId);

            channel.setStatus(ChannelStatus.DISCONNECTED);
            channelRepository.save(channel);
            distributionService.pushChannelStatus(channelId, ChannelStatus.DISCONNECTED.name());
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
            distributionService.pushChannelStatus(channelId, ChannelStatus.DISCONNECTED.name());
            log.info("Channel runtime lost connection, state synced: {}", channelId);
        } finally {
            lock.unlock();
        }
    }

    private void markPointsCommLost(String channelId) {
        try {
            // 只标 OUTPUT：INPUT 的值由传播驱动、与目标通道能否送达无关
            List<MeasurementPoint> outputPoints = pointRepository.findByChannelIdAndDirection(
                    channelId, PointDirection.OUTPUT);
            List<PointValue> commLost = new ArrayList<>();
            for (MeasurementPoint point : outputPoints) {
                changeGate.removePoints(List.of(point.getPointId()));
                PointValue pv = PointValue.commLost(point.getPointId());
                pv.setSourceChannelId(channelId);
                pointValueCache.save(pv);
                commLost.add(pv);
            }
            // 推给订阅端：否则前端一直显示掉线前的旧 GOOD 值
            if (!commLost.isEmpty()) {
                distributionService.pushBatch(commLost);
            }
        } catch (Throwable e) {
            log.warn("Failed to mark points as COMM_LOST for channel: {}", channelId, e);
        }
    }

    private ReentrantLock lockFor(String channelId) {
        return lifecycleLocks.computeIfAbsent(channelId, k -> new ReentrantLock());
    }
}
