package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
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
public class PointService {

    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;
    private final ChannelService channelService;
    private final ChangeGate changeGate;
    private final ProtocolRegistry protocolRegistry;

    /**
     * 查询所有测点
     */
    public List<MeasurementPoint> findAll() {
        return pointRepository.findAll();
    }

    /**
     * 根据 Channel ID 查询测点
     */
    public List<MeasurementPoint> findByChannelId(String channelId) {
        return pointRepository.findByChannelId(channelId);
    }

    /**
     * 根据 ID 查询测点
     */
    public MeasurementPoint findById(String pointId) {
        return pointRepository.findById(pointId)
                .orElseThrow(() -> new ResourceNotFoundException("MeasurementPoint", pointId));
    }

    /**
     * 创建测点
     */
    @Transactional
    public MeasurementPoint create(MeasurementPointDTO dto) {
        // 验证 Channel 存在
        Channel channel = channelService.findById(dto.getChannelId());

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

        // 通道已连接时为订阅型协议增量建立订阅；失败不影响测点保存（重连时会全量订阅）
        if (channel.getStatus() == ChannelStatus.CONNECTED) {
            protocolRegistry.get(dto.getChannelId()).ifPresent(adapter -> {
                try {
                    adapter.onConnected(List.of(saved));
                } catch (Exception e) {
                    log.warn("Failed to subscribe new point {} on connected channel {}",
                            saved.getPointId(), dto.getChannelId(), e);
                }
            });
        }
        return saved;
    }

    /**
     * 更新测点
     */
    @Transactional
    public MeasurementPoint update(String pointId, MeasurementPointDTO dto) {
        MeasurementPoint point = findById(pointId);
        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        point.setDeadband(dto.getDeadband());
        return pointRepository.save(point);
    }

    /**
     * 删除测点
     */
    @Transactional
    public void delete(String pointId) {
        pointRepository.deleteById(pointId);
        pointValueCache.delete(pointId);
        changeGate.removePoints(List.of(pointId));
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
     * 写入测点值
     */
    public void writeValue(String pointId, Object value) {
        MeasurementPoint point = findById(pointId);

        // 不可写测点禁止写入
        if (!point.isWritable()) {
            throw new IllegalArgumentException("Point is not writable: " + pointId);
        }

        Channel channel = channelService.findById(point.getChannelId());

        if (channel.getStatus() != ChannelStatus.CONNECTED) {
            throw new RuntimeException("Channel not connected: " + point.getChannelId());
        }

        // 只读通道禁止写入，避免伪造设备值
        if (channel.getDirection() == ChannelDirection.READ_ONLY) {
            throw new IllegalArgumentException("Channel is read-only: " + channel.getChannelId());
        }

        // 写入外部系统
        ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);
        adapter.writePoint(point, value);

        // 更新缓存
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId(channel.getChannelId());
        pv.setTimestamp(System.currentTimeMillis());
        pointValueCache.save(pv);
        changeGate.recordManualWrite(pointId, value, PointQuality.GOOD, channel.getChannelId());
    }

    /**
     * 批量更新测点值（由采集引擎对通过变更检测的值调用，Redis MSET 一次往返）
     */
    public void updateBatch(List<PointValue> pointValues) {
        pointValueCache.saveBatch(pointValues);
    }

    /**
     * 批量导入测点（upsert：存在则更新，不存在则创建）
     */
    @Transactional
    public List<MeasurementPoint> importPoints(List<MeasurementPointDTO> dtos) {
        List<MeasurementPoint> result = new java.util.ArrayList<>();
        for (MeasurementPointDTO dto : dtos) {
            MeasurementPoint existing = pointRepository.findById(dto.getPointId()).orElse(null);
            if (existing != null) {
                existing.setPointName(dto.getPointName());
                existing.setChannelId(dto.getChannelId());
                existing.setAddress(dto.getAddress());
                existing.setDataType(dto.getDataType());
                existing.setUnit(dto.getUnit());
                existing.setWritable(dto.isWritable());
                existing.setDeadband(dto.getDeadband());
                result.add(pointRepository.save(existing));
            } else {
                result.add(create(dto));
            }
        }
        return result;
    }
}
