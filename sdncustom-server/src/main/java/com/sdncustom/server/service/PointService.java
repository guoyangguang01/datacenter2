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
        channelService.findById(dto.getChannelId());

        MeasurementPoint point = new MeasurementPoint();
        point.setPointId(dto.getPointId());
        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setWritable(dto.isWritable());
        return pointRepository.save(point);
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
        return pointRepository.save(point);
    }

    /**
     * 删除测点
     */
    @Transactional
    public void delete(String pointId) {
        pointRepository.deleteById(pointId);
        pointValueCache.delete(pointId);
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
        Channel channel = channelService.findById(point.getChannelId());

        if (channel.getStatus() != ChannelStatus.CONNECTED) {
            throw new RuntimeException("Channel not connected: " + point.getChannelId());
        }

        // 写入外部系统
        if (channel.getDirection() == ChannelDirection.WRITE_ONLY ||
            channel.getDirection() == ChannelDirection.READ_WRITE) {
            ProtocolAdapter adapter = channelService.getOrCreateAdapter(channel);
            adapter.writePoint(point, value);
        }

        // 更新缓存
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId(channel.getChannelId());
        pv.setTimestamp(System.currentTimeMillis());
        pointValueCache.save(pv);
    }

    /**
     * 更新测点值（由采集引擎调用）
     */
    public void updateValue(PointValue pointValue) {
        pointValueCache.save(pointValue);
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
                result.add(pointRepository.save(existing));
            } else {
                result.add(create(dto));
            }
        }
        return result;
    }
}
