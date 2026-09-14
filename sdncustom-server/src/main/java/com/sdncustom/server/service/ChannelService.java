package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.BusinessException;
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
    private final PointSourceService pointSourceService;
    private final PointBindingRegistry pointBindingRegistry;
    private final PointValueCacheRepository pointValueCache;
    private final ProtocolRegistry protocolRegistry;
    private final ChangeGate changeGate;
    private final BusinessSystemService businessSystemService;
    private final DistributionService distributionService;

    /** 导出脱敏占位符：它出现在导入文件里说明那是旧导出产物，不是真实凭据 */
    private static final String MASKED_PLACEHOLDER = "******";

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
        Channel channel = new Channel();
        channel.setChannelId(dto.getChannelId());
        channel.setBusinessId(dto.getBusinessId());
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

        // 事务内先把状态收敛为 DISCONNECTED，采集循环立刻不再碰这条通道
        if (connectionChanged && channel.getStatus() != ChannelStatus.DISCONNECTED) {
            channel.setStatus(ChannelStatus.DISCONNECTED);
        }

        channel.setChannelName(dto.getChannelName());
        channel.setProtocolType(dto.getProtocolType());
        channel.setDirection(dto.getDirection());
        channel.setConnectionConfig(dto.getConnectionConfig());
        channel.setAutoConnect(dto.isAutoConnect());
        Channel saved = channelRepository.save(channel);

        if (connectionChanged) {
            // 关旧适配器（要关 socket）与按新配置重连都是远端动作，放到提交之后。
            // 原实现在事务里直接做，等于持着 DB 连接做网络 I/O，回滚了远端也已经动过。
            TransactionHooks.afterCommit(() -> {
                protocolRegistry.release(channelId);
                markPointsCommLost(channelId);
                if (wasConnected && saved.isAutoConnect()) {
                    try {
                        connect(channelId);
                    } catch (Exception e) {
                        // 新配置连不上：connect() 已将状态收敛为 ERROR，这里吞掉异常，
                        // 保证用户刚保存的配置不因回滚而丢失
                        log.warn("Auto-reconnect after config change failed for channel {}, status set to ERROR: {}",
                                channelId, e.getMessage());
                    }
                }
            });
        }
        return saved;
    }

    /**
     * 导入通道配置（仅通道）：已存在的通道 upsert。
     * payload 省略 connectionConfig 时保留库中原值，避免"导入把配置清空"。
     */
    @Transactional
    public int importConfigs(List<ChannelDTO> dtos) {
        for (ChannelDTO dto : dtos) {
            String config = dto.getConnectionConfig();
            if (config != null && config.contains(MASKED_PLACEHOLDER)) {
                throw new BusinessException(400, "通道 " + dto.getChannelId()
                        + " 的 connectionConfig 含脱敏占位符 " + MASKED_PLACEHOLDER
                        + "，那不是真实凭据；请提供真实连接配置");
            }
            Channel existing = findByIdOrNull(dto.getChannelId());
            if (existing == null) {
                businessSystemService.ensureExistsForImport(dto.getBusinessId());
                create(dto);
                continue;
            }
            if (config == null || config.isBlank()) {
                dto.setConnectionConfig(existing.getConnectionConfig());
            }
            update(dto.getChannelId(), dto);
        }
        log.info("Imported {} channel config(s)", dtos.size());
        return dtos.size();
    }

    /**
     * 删除 Channel（先释放运行态，再级联删除测点）
     */
    @Transactional
    public void delete(String channelId) {
        findById(channelId);
        // 不再期望连接，重连调度器不该把它拉回来
        desiredConnected.remove(channelId);

        List<MeasurementPoint> boundPoints = pointSourceService.findPointsForChannel(channelId);
        List<String> boundPointIds = boundPoints.stream().map(MeasurementPoint::getPointId).distinct().toList();

        // 删除该通道的所有绑定行
        pointSourceService.deleteByChannelId(channelId);

        // 仅剩该通道绑定的测点（无其余绑定）→ 删除；多绑定点存活（已失去本通道绑定）
        for (String pointId : boundPointIds) {
            Set<String> remaining = pointSourceService.bindingChannelIds(pointId);
            if (remaining.isEmpty()) {
                // 没有任何来源了：清基线并删除测点
                changeGate.removePoints(List.of(pointId));
                pointValueCache.delete(pointId);
                pointSourceService.deleteByPointId(pointId);
                pointRepository.deleteById(pointId);
            } else {
                // 还有其他来源：只收敛来源集合、保留权威基线。若在这里清基线，
                // 幸存来源的未变化值会被当成变化重写历史并重推一次
                changeGate.syncPointBindings(pointId, remaining);
            }
        }
        channelRepository.deleteById(channelId);
        pointBindingRegistry.invalidateChannel(channelId);

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
                // 统一连接后钩子：订阅型协议（MQTT）在此建立测点订阅（主绑定 + 附加来源）
                adapter.onConnected(pointSourceService.findPointsForChannel(channelId));
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
            List<MeasurementPoint> boundPoints = pointSourceService.findPointsForChannel(channelId);
            List<String> pointIds = boundPoints.stream().map(MeasurementPoint::getPointId).distinct().toList();
            List<PointValue> commLost = new ArrayList<>();
            for (String pointId : pointIds) {
                Set<String> remaining = pointSourceService.bindingChannelIds(pointId);
                remaining.remove(channelId); // 本通道来源即将不可用
                if (remaining.isEmpty()) {
                    // 唯一来源：清基线并标记 COMM_LOST
                    changeGate.removePoints(List.of(pointId));
                    PointValue pv = PointValue.commLost(pointId);
                    pv.setSourceChannelId(channelId);
                    pointValueCache.save(pv);
                    commLost.add(pv);
                } else {
                    // 多来源：只收敛来源集合、保留权威基线，让其余 GOOD 来源继续供给，
                    // 不清基线（清了会把幸存来源的未变化值当变化重推）
                    changeGate.syncPointBindings(pointId, remaining);
                }
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
