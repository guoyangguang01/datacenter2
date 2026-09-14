package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import com.sdncustom.server.support.TransactionHooks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final BusinessSystemService businessSystemService;
    private final PointDirectionValidator pointDirectionValidator;

    /**
     * 查询所有测点
     */
    public List<MeasurementPoint> findAll() {
        List<MeasurementPoint> points = pointRepository.findAll();
        attachBindings(points);
        return points;
    }

    /**
     * 查询指定业务下的所有测点
     */
    public List<MeasurementPoint> findByBusinessId(String businessId) {
        List<MeasurementPoint> points = pointRepository.findByBusinessId(businessId);
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
     * 创建测点（绑定集：bindings ≥1，归属业务必填，绑定通道必须同业务）
     */
    @Transactional
    public MeasurementPoint create(MeasurementPointDTO dto) {
        businessSystemService.requireExists(dto.getBusinessId());
        pointDirectionValidator.validate(dto, dto.getBusinessId());
        pointSourceService.validateBindings(dto.getBindings(), dto.getBusinessId());

        MeasurementPoint point = new MeasurementPoint();
        point.setPointId(dto.getPointId());
        point.setBusinessId(dto.getBusinessId());
        point.setPointName(dto.getPointName());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        point.setDirection(dto.getDirection());
        point.setReferencePointId(dto.getReferencePointId());
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);
        pointSourceService.replaceBindings(dto.getPointId(), dto.getBindings());
        pointBindingRegistry.invalidate(saved.getPointId());

        // 订阅是远端调用：放到事务提交之后，别占着 DB 连接做网络 I/O（失败不影响测点保存）
        TransactionHooks.afterCommit(() -> subscribeConnectedBindings(saved, dto.getBindings()));
        return saved;
    }

    /**
     * 更新测点：整体替换 bindings
     */
    @Transactional
    public MeasurementPoint update(String pointId, MeasurementPointDTO dto) {
        MeasurementPoint point = findById(pointId);
        // 归属不可变更：绑定校验以测点现有业务为准，DTO 中的 businessId 被忽略
        pointSourceService.validateBindings(dto.getBindings(), point.getBusinessId());

        // 整体替换前先记下被移除的绑定，替换后通知订阅型协议退订
        Set<String> keptKeys = dto.getBindings() == null ? Set.of() : dto.getBindings().stream()
                .map(b -> b.getChannelId() + "|" + b.getAddress())
                .collect(Collectors.toSet());
        List<MeasurementPoint> removedViews = pointSourceService.allBindingViews(point).stream()
                .filter(view -> !keptKeys.contains(view.getChannelId() + "|" + view.getAddress()))
                .toList();

        point.setPointName(dto.getPointName());
        point.setDataType(dto.getDataType());
        point.setUnit(dto.getUnit());
        // 方向与引用创建后不可变更：DTO 中的 direction/referencePointId 被忽略（静默，
        // 与 businessId 同规）。若在此写入，缺 referencePointId 的编辑请求会把它清成 null。
        point.setDeadband(dto.getDeadband());
        MeasurementPoint saved = pointRepository.save(point);
        pointSourceService.replaceBindings(pointId, dto.getBindings());
        pointBindingRegistry.invalidate(pointId);
        changeGate.syncPointBindings(pointId, pointSourceService.bindingChannelIds(pointId));

        // 远端订阅/退订放到提交之后
        TransactionHooks.afterCommit(() -> {
            subscribeConnectedBindings(saved, dto.getBindings());
            notifyBindingsRemoved(removedViews);
        });
        return saved;
    }

    /**
     * 删除测点
     */
    @Transactional
    public void delete(String pointId) {
        // 不存在就别静默成功——调用方需要知道删的是什么
        MeasurementPoint point = pointRepository.findById(pointId)
                .orElseThrow(() -> new ResourceNotFoundException("MeasurementPoint", pointId));

        // 被输入测点引用的输出测点不能删：留下悬空引用，输入测点会失去数据来源
        if (pointRepository.existsByReferencePointId(pointId)) {
            throw new BusinessException(400, "该测点被输入测点引用，请先删除引用它的测点: " + pointId);
        }

        // 退订是远端调用，放到提交之后（订阅型协议否则会把 topic 订阅与缓存一直留着）
        List<MeasurementPoint> removedViews = pointSourceService.allBindingViews(point);

        pointRepository.deleteById(pointId);
        pointValueCache.delete(pointId);
        changeGate.removePoints(List.of(pointId));
        pointSourceService.deleteByPointId(pointId);
        pointBindingRegistry.invalidate(pointId);

        TransactionHooks.afterCommit(() -> notifyBindingsRemoved(removedViews));
    }

    /** 通知订阅型协议退订这些绑定（MQTT 实现为退订 topic + 清缓存），逐个容错 */
    private void notifyBindingsRemoved(List<MeasurementPoint> views) {
        for (MeasurementPoint view : views) {
            protocolRegistry.get(view.getChannelId()).ifPresent(adapter -> {
                try {
                    adapter.onPointsRemoved(List.of(view));
                } catch (Exception e) {
                    log.warn("Failed to remove subscription for point {} on channel {}",
                            view.getPointId(), view.getChannelId(), e);
                }
            });
        }
    }

    /**
     * 给既有测点增加一条绑定（创建表单"关联既有测点"走这里）
     */
    @Transactional
    public MeasurementPoint addBinding(String pointId, String channelId, String address) {
        MeasurementPoint point = findById(pointId);
        pointSourceService.addBinding(pointId, channelId, address, point.getBusinessId());
        pointBindingRegistry.invalidate(pointId);
        changeGate.syncPointBindings(pointId, pointSourceService.bindingChannelIds(pointId));

        MeasurementPoint view = pointSourceService.viewForBinding(point, channelId, address);
        TransactionHooks.afterCommit(() -> subscribeConnectedChannel(channelId, List.of(view)));
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
