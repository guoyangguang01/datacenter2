package com.sdncustom.server.service;

import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 测点绑定（绑定集）服务：一个测点的全部绑定统一存在 point_source 表，无主从之分。
 * 一个测点的绑定通道互不相同，保证来源键 pointId+"|"+channelId 唯一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointSourceService {

    private final PointSourceRepository pointSourceRepository;
    private final MeasurementPointRepository pointRepository;
    private final ChannelRepository channelRepository;

    /**
     * 该通道应读取的所有测点视图：绑定表命中该通道的每个点，按该通道的绑定地址生成视图。
     * 视图保留 pointId（合并与路由的锚点），覆盖 channelId/address 为该通道绑定值。
     */
    public List<MeasurementPoint> findPointsForChannel(String channelId) {
        List<MeasurementPoint> result = new ArrayList<>();
        for (PointSource binding : pointSourceRepository.findByChannelId(channelId)) {
            MeasurementPoint point = pointRepository.findById(binding.getPointId()).orElse(null);
            if (point != null) {
                result.add(viewForBinding(point, binding.getChannelId(), binding.getAddress()));
            }
        }
        return result;
    }

    /** 测点的所有绑定视图：每个绑定生成一个视图（供写广播、订阅）。 */
    public List<MeasurementPoint> allBindingViews(MeasurementPoint point) {
        List<MeasurementPoint> views = new ArrayList<>();
        for (PointSource binding : pointSourceRepository.findByPointId(point.getPointId())) {
            views.add(viewForBinding(point, binding.getChannelId(), binding.getAddress()));
        }
        return views;
    }

    /** 该测点绑定的全部通道 */
    public Set<String> bindingChannelIds(String pointId) {
        Set<String> channels = new LinkedHashSet<>();
        for (PointSource binding : pointSourceRepository.findByPointId(pointId)) {
            channels.add(binding.getChannelId());
        }
        return channels;
    }

    /** 以 channelId+address 覆盖生成非持久化视图，pointId 保持不变 */
    public MeasurementPoint viewForBinding(MeasurementPoint point, String channelId, String address) {
        MeasurementPoint view = new MeasurementPoint();
        view.setPointId(point.getPointId());
        view.setPointName(point.getPointName());
        view.setChannelId(channelId);
        view.setAddress(address);
        view.setDataType(point.getDataType());
        view.setUnit(point.getUnit());
        view.setWritable(point.isWritable());
        view.setDeadband(point.getDeadband());
        view.setCreateTime(point.getCreateTime());
        view.setUpdateTime(point.getUpdateTime());
        return view;
    }

    /** 校验绑定：至少一条、通道存在、通道互不重复 */
    public void validateBindings(List<PointSourceDTO> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            throw new BusinessException(400, "测点至少需要一个绑定通道");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PointSourceDTO dto : bindings) {
            if (!seen.add(dto.getChannelId())) {
                throw new BusinessException(400, "绑定通道重复: " + dto.getChannelId());
            }
            if (channelRepository.findById(dto.getChannelId()).isEmpty()) {
                throw new BusinessException(400, "绑定通道不存在: " + dto.getChannelId());
            }
        }
    }

    /** 替换测点的全部绑定（update 语义：先删后插） */
    @Transactional
    public void replaceBindings(String pointId, List<PointSourceDTO> bindings) {
        pointSourceRepository.deleteByPointId(pointId);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (PointSourceDTO dto : bindings) {
            PointSource source = new PointSource();
            source.setPointId(pointId);
            source.setChannelId(dto.getChannelId());
            source.setAddress(dto.getAddress());
            pointSourceRepository.save(source);
        }
    }

    /** 给既有测点增加一条绑定（校验通道存在、该点尚未绑定此通道） */
    @Transactional
    public PointSource addBinding(String pointId, String channelId, String address) {
        if (channelRepository.findById(channelId).isEmpty()) {
            throw new BusinessException(400, "绑定通道不存在: " + channelId);
        }
        boolean exists = pointSourceRepository.findByPointId(pointId).stream()
                .anyMatch(b -> b.getChannelId().equals(channelId));
        if (exists) {
            throw new BusinessException(400, "测点已绑定该通道: " + channelId);
        }
        PointSource source = new PointSource();
        source.setPointId(pointId);
        source.setChannelId(channelId);
        source.setAddress(address);
        return pointSourceRepository.save(source);
    }

    @Transactional
    public void deleteByPointId(String pointId) {
        pointSourceRepository.deleteByPointId(pointId);
    }

    @Transactional
    public void deleteByChannelId(String channelId) {
        pointSourceRepository.deleteByChannelId(channelId);
    }
}
