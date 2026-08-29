package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;
    private final PointSourceRepository pointSourceRepository;
    private final ChannelService channelService;
    private final ChangeGate changeGate;
    private final ProtocolRegistry protocolRegistry;
    private final PointSourceService pointSourceService;
    private final PointBindingRegistry pointBindingRegistry;
    private final DistributionService distributionService;

    /**
     * 查询所有测点
     */
    public List<MeasurementPoint> findAll() {
        List<MeasurementPoint> points = pointRepository.findAll();
        attachBindings(points);
        return points;
    }

    /**
     * 查询绑定到该通道的所有测点（任意绑定通道命中）
     */
    public List<MeasurementPoint> findByChannelId(String channelId) {
        List<String> pointIds = pointSourceRepository.findByChannelId(channelId).stream()
                .map(PointSource::getPointId)
                .distinct()
                .toList();
        if (pointIds.isEmpty()) {
            return List.of();
        }
        List<MeasurementPoint> points = pointRepository.findAllById(pointIds);
        attachBindings(points);
        return points;
    }

    /**
     * 根据 ID 查询测点
     */
    public MeasurementPoint findById(String pointId) {
        MeasurementPoint point = pointRepository.findById(pointId)
                .orElseThrow(() -> new ResourceNotFoundException("MeasurementPoint", pointId));
        point.setBindings(pointSourceRepository.findByPointId(pointId));
        return point;
    }

    /**
     * 创建测点（绑定集：bindings ≥1）
     */
    @Transactional
    public MeasurementPoint create(MeasurementPointDTO dto) {
        pointSourceService.validateBindings(dto.getBindings());

        MeasurementPoint point = new MeasurementPoint();
        point.setPointId(dto.getPointId());
        point.setPointName(dto.getPointName());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);
        pointSourceService.replaceBindings(dto.getPointId(), dto.getBindings());

        // 已连接通道为订阅型协议增量订阅（各绑定通道）；失败不影响测点保存
        subscribeConnectedBindings(saved, dto.getBindings());

        pointBindingRegistry.invalidate(saved.getPointId());
        return saved;
    }

    /**
     * 更新测点：整体替换 bindings
     */
    @Transactional
    public MeasurementPoint update(String pointId, MeasurementPointDTO dto) {
        MeasurementPoint point = findById(pointId);
        pointSourceService.validateBindings(dto.getBindings());

        point.setPointName(dto.getPointName());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);
        pointSourceService.replaceBindings(pointId, dto.getBindings());
        subscribeConnectedBindings(saved, dto.getBindings());

        pointBindingRegistry.invalidate(pointId);
        changeGate.syncPointBindings(pointId, pointSourceService.bindingChannelIds(pointId));
        return saved;
    }

    /**
     * 删除测点
     */
    @Transactional
    public void delete(String pointId) {
        pointRepository.deleteById(pointId);
        pointValueCache.delete(pointId);
        changeGate.removePoints(List.of(pointId));
        pointSourceService.deleteByPointId(pointId);
        pointBindingRegistry.invalidate(pointId);
    }

    /**
     * 给既有测点增加一条绑定（创建表单"关联既有测点"走这里）
     */
    @Transactional
    public MeasurementPoint addBinding(String pointId, String channelId, String address) {
        MeasurementPoint point = findById(pointId);
        pointSourceService.addBinding(pointId, channelId, address);
        subscribeConnectedChannel(channelId,
                List.of(pointSourceService.viewForBinding(point, channelId, address)));
        pointBindingRegistry.invalidate(pointId);
        changeGate.syncPointBindings(pointId, pointSourceService.bindingChannelIds(pointId));
        return findById(pointId);
    }

    /**
     * 获取测点当前值
     */
    public PointValue getValue(String pointId) {
        return pointValueCache.findByPointId(pointId)
                .orElse(PointValue.commLost(pointId));
    }

    /**
     * 批量获取测点当前值
     */
    public List<PointValue> getValues(List<String> pointIds) {
        return pointValueCache.findByPointIds(pointIds);
    }

    /**
     * 写入测点值：广播到所有绑定通道（各通道用各自 address），
     * 跳过未连接/只读通道，单个来源失败不中断，全部失败才抛异常。
     */
    public void writeValue(String pointId, Object value) {
        MeasurementPoint point = findById(pointId);

        // 不可写测点禁止写入
        if (!point.isWritable()) {
            throw new IllegalArgumentException("Point is not writable: " + pointId);
        }

        List<MeasurementPoint> bindings = pointSourceService.allBindingViews(point);
        int success = 0;
        String writtenChannel = null;
        for (MeasurementPoint view : bindings) {
            Channel channel = channelService.findByIdOrNull(view.getChannelId());
            if (channel == null) {
                log.warn("Write skipped: channel not found {} for point {}", view.getChannelId(), pointId);
                continue;
            }
            if (channel.getStatus() != ChannelStatus.CONNECTED) {
                log.warn("Write skipped: channel not connected {} for point {}", channel.getChannelId(), pointId);
                continue;
            }
            if (channel.getDirection() == ChannelDirection.READ_ONLY) {
                log.warn("Write skipped: channel read-only {} for point {}", channel.getChannelId(), pointId);
                continue;
            }
            try {
                ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);
                adapter.writePoint(view, value);
                success++;
                if (writtenChannel == null) {
                    writtenChannel = channel.getChannelId();
                }
            } catch (Exception e) {
                log.error("Write failed to channel {} for point {}: {}",
                        channel.getChannelId(), pointId, e.getMessage());
            }
        }
        if (success == 0) {
            throw new RuntimeException("No writable connected channel for point " + pointId);
        }

        // 更新缓存（来源 = 首个实际写入通道）
        String fallbackChannel = pointSourceService.bindingChannelIds(pointId).stream().findFirst().orElse(null);
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId(writtenChannel != null ? writtenChannel : fallbackChannel);
        pv.setTimestamp(System.currentTimeMillis());
        pointValueCache.save(pv);
        changeGate.recordManualWrite(pointId, value, PointQuality.GOOD, pv.getSourceChannelId());
        distributionService.pushBatch(List.of(pv));
    }

    /**
     * 批量更新测点值（由采集引擎对通过变更检测的值调用，Redis MSET 一次往返）
     */
    public void updateBatch(List<PointValue> pointValues) {
        pointValueCache.saveBatch(pointValues);
    }

    /**
     * 批量导入测点（upsert：存在则走 update 语义，不存在则创建）
     */
    @Transactional
    public List<MeasurementPoint> importPoints(List<MeasurementPointDTO> dtos) {
        List<MeasurementPoint> result = new java.util.ArrayList<>();
        for (MeasurementPointDTO dto : dtos) {
            result.add(pointRepository.findById(dto.getPointId()).isPresent()
                    ? update(dto.getPointId(), dto)
                    : create(dto));
        }
        return result;
    }

    private void subscribeConnectedBindings(MeasurementPoint point, List<PointSourceDTO> bindings) {
        if (bindings == null) {
            return;
        }
        for (PointSourceDTO binding : bindings) {
            subscribeConnectedChannel(binding.getChannelId(),
                    List.of(pointSourceService.viewForBinding(point, binding.getChannelId(), binding.getAddress())));
        }
    }

    private void subscribeConnectedChannel(String channelId, List<MeasurementPoint> views) {
        protocolRegistry.get(channelId).ifPresent(adapter -> {
            try {
                adapter.onConnected(views);
            } catch (Exception e) {
                log.warn("Failed to subscribe point {} on connected channel {}",
                        views.isEmpty() ? "?" : views.get(0).getPointId(), channelId, e);
            }
        });
    }

    private void attachBindings(List<MeasurementPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<String> pointIds = points.stream().map(MeasurementPoint::getPointId).toList();
        Map<String, List<PointSource>> byPoint = pointSourceRepository.findByPointIdIn(pointIds).stream()
                .collect(Collectors.groupingBy(PointSource::getPointId));
        for (MeasurementPoint point : points) {
            point.setBindings(byPoint.get(point.getPointId()));
        }
    }
}
