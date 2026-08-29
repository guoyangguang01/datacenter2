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
        attachSources(points);
        return points;
    }

    /**
     * 根据 Channel ID 查询主绑定在该通道的测点
     */
    public List<MeasurementPoint> findByChannelId(String channelId) {
        List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
        attachSources(points);
        return points;
    }

    /**
     * 根据 ID 查询测点
     */
    public MeasurementPoint findById(String pointId) {
        MeasurementPoint point = pointRepository.findById(pointId)
                .orElseThrow(() -> new ResourceNotFoundException("MeasurementPoint", pointId));
        point.setAdditionalSources(pointSourceRepository.findByPointId(pointId));
        return point;
    }

    /**
     * 创建测点（可带附加来源）
     */
    @Transactional
    public MeasurementPoint create(MeasurementPointDTO dto) {
        // 验证 Channel 存在
        channelService.findById(dto.getChannelId());
        pointSourceService.validateSources(dto.getAdditionalSources(), dto.getChannelId());

        MeasurementPoint point = new MeasurementPoint();
        point.setPointId(dto.getPointId());
        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);
        pointSourceService.replaceSources(dto.getPointId(), dto.getAdditionalSources());

        // 已连接通道为订阅型协议增量订阅（主通道 + 各附加来源通道）；失败不影响测点保存
        subscribeConnectedBindings(saved, dto.getAdditionalSources());

        pointBindingRegistry.invalidate(saved.getPointId());
        return saved;
    }

    /**
     * 更新测点。
     * additionalSources：null=保留现有来源；空列表=清空；非空=整体替换。
     */
    @Transactional
    public MeasurementPoint update(String pointId, MeasurementPointDTO dto) {
        MeasurementPoint point = findById(pointId);
        pointSourceService.validateSources(dto.getAdditionalSources(), dto.getChannelId());

        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);

        List<PointSourceDTO> effectiveSources = dto.getAdditionalSources();
        if (effectiveSources != null) {
            pointSourceService.replaceSources(pointId, effectiveSources);
        } else {
            effectiveSources = pointSourceRepository.findByPointId(pointId).stream()
                    .map(PointService::toSourceDTO)
                    .collect(Collectors.toList());
        }
        subscribeConnectedBindings(saved, effectiveSources);

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
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId(writtenChannel != null ? writtenChannel : point.getChannelId());
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

    private void subscribeConnectedBindings(MeasurementPoint point, List<PointSourceDTO> sources) {
        subscribeConnectedChannel(point.getChannelId(), List.of(point));
        if (sources != null) {
            for (PointSourceDTO source : sources) {
                subscribeConnectedChannel(source.getChannelId(),
                        List.of(pointSourceService.viewForBinding(point, source.getChannelId(), source.getAddress())));
            }
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

    private void attachSources(List<MeasurementPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<String> pointIds = points.stream().map(MeasurementPoint::getPointId).toList();
        Map<String, List<PointSource>> byPoint = pointSourceRepository.findByPointIdIn(pointIds).stream()
                .collect(Collectors.groupingBy(PointSource::getPointId));
        for (MeasurementPoint point : points) {
            point.setAdditionalSources(byPoint.get(point.getPointId()));
        }
    }

    private static PointSourceDTO toSourceDTO(PointSource source) {
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId(source.getChannelId());
        dto.setAddress(source.getAddress());
        return dto;
    }
}
