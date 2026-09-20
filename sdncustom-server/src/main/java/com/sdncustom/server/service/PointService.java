package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import com.sdncustom.server.support.TransactionHooks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;
    private final ChangeGate changeGate;
    private final ProtocolRegistry protocolRegistry;
    private final BusinessSystemService businessSystemService;
    private final PointDirectionValidator pointDirectionValidator;

    /**
     * 查询所有测点
     */
    public List<MeasurementPoint> findAll() {
        return pointRepository.findAll();
    }

    /**
     * 查询指定业务下的所有测点
     */
    public List<MeasurementPoint> findByBusinessId(String businessId) {
        return pointRepository.findByBusinessId(businessId);
    }

    /**
     * 查询关联到该通道的所有测点
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
     * 创建测点（channelId/address 一对一关联通道，归属业务必填）
     */
    @Transactional
    public MeasurementPoint create(MeasurementPointDTO dto) {
        businessSystemService.requireExists(dto.getBusinessId());
        pointDirectionValidator.validate(dto, dto.getBusinessId());

        MeasurementPoint point = new MeasurementPoint();
        point.setPointId(dto.getPointId());
        point.setBusinessId(dto.getBusinessId());
        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setDirection(dto.getDirection());
        point.setReferencePointId(dto.getReferencePointId());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);

        // OUTPUT 测点连接后通知适配器（MQTT 订阅 topic 等）
        if (saved.getDirection() == PointDirection.OUTPUT) {
            TransactionHooks.afterCommit(() -> subscribePointOnChannel(saved));
        }
        return saved;
    }

    /**
     * 更新测点：channelId/address 与其他字段一起更新
     */
    @Transactional
    public MeasurementPoint update(String pointId, MeasurementPointDTO dto) {
        MeasurementPoint point = findById(pointId);
        // 归属不可变更：DTO 中的 businessId 被忽略
        String oldChannelId = point.getChannelId();
        // channelId 可改，而自引用只可能在改通道时事后产生——方向与引用以库中现值为准
        pointDirectionValidator.validateChannelChange(point, dto.getChannelId());

        point.setPointName(dto.getPointName());
        point.setChannelId(dto.getChannelId());
        point.setAddress(dto.getAddress());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        // 方向与引用创建后不可变更：DTO 中的 direction/referencePointId 被忽略
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);

        // 如果通道变了，ChangeGate 需要清理旧来源
        if (oldChannelId != null && !oldChannelId.equals(dto.getChannelId())) {
            changeGate.removePoints(List.of(pointId));
        }

        // 退订旧通道、订阅新通道（提交后执行）
        if (saved.getDirection() == PointDirection.OUTPUT) {
            TransactionHooks.afterCommit(() -> {
                // 退订旧通道
                if (oldChannelId != null && !oldChannelId.equals(dto.getChannelId())) {
                    protocolRegistry.get(oldChannelId).ifPresent(adapter -> {
                        try {
                            adapter.onPointsRemoved(List.of(saved));
                        } catch (Exception e) {
                            log.warn("Failed to unsubscribe point {} from old channel {}",
                                    pointId, oldChannelId, e);
                        }
                    });
                }
                subscribePointOnChannel(saved);
            });
        }
        return saved;
    }

    /**
     * 删除测点
     */
    @Transactional
    public void delete(String pointId) {
        MeasurementPoint point = pointRepository.findById(pointId)
                .orElseThrow(() -> new ResourceNotFoundException("MeasurementPoint", pointId));

        // 被输入测点引用的输出测点不能删
        List<MeasurementPoint> referencingPoints = pointRepository.findByReferencePointId(pointId);
        if (!referencingPoints.isEmpty()) {
            String referencingIds = referencingPoints.stream()
                    .map(MeasurementPoint::getPointId)
                    .collect(Collectors.joining(", "));
            throw new BusinessException(400, "该测点被以下输入测点引用，请先删除它们: " + referencingIds);
        }

        pointRepository.deleteById(pointId);
        pointValueCache.delete(pointId);
        changeGate.removePoints(List.of(pointId));

        // 退订是远端调用，放到提交之后
        TransactionHooks.afterCommit(() -> {
            if (point.getDirection() == PointDirection.OUTPUT && point.getChannelId() != null) {
                protocolRegistry.get(point.getChannelId()).ifPresent(adapter -> {
                    try {
                        adapter.onPointsRemoved(List.of(point));
                    } catch (Exception e) {
                        log.warn("Failed to unsubscribe point {} from channel {}",
                                pointId, point.getChannelId(), e);
                    }
                });
            }
        });
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

    /**
     * 通知适配器一个 OUTPUT 测点在其通道上可供读取/订阅
     */
    private void subscribePointOnChannel(MeasurementPoint point) {
        if (point.getDirection() != PointDirection.OUTPUT || point.getChannelId() == null) {
            return;
        }
        protocolRegistry.get(point.getChannelId()).ifPresent(adapter -> {
            try {
                adapter.onConnected(List.of(point));
            } catch (Exception e) {
                log.warn("Failed to subscribe point {} on channel {}",
                        point.getPointId(), point.getChannelId(), e);
            }
        });
    }
}
