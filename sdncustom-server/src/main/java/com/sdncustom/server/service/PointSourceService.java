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
 * 测点附加来源（多通道绑定）的读取与维护。
 * 主绑定仍是 MeasurementPoint.channelId+address；PointSource 保存其余通道来源。
 * 一个测点的所有绑定通道互不相同，保证来源键 pointId+"|"+channelId 唯一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointSourceService {

    private final PointSourceRepository pointSourceRepository;
    private final MeasurementPointRepository pointRepository;
    private final ChannelRepository channelRepository;

    /**
     * 该通道应读取的所有测点视图：主绑定（原实体）+ 附加来源命中该通道的点（address 按来源覆盖）。
     * 视图保留 pointId（合并与路由的锚点），仅覆盖 channelId/address。
     */
    public List<MeasurementPoint> findPointsForChannel(String channelId) {
        List<MeasurementPoint> result = new ArrayList<>(pointRepository.findByChannelId(channelId));
        for (PointSource source : pointSourceRepository.findByChannelId(channelId)) {
            MeasurementPoint point = pointRepository.findById(source.getPointId()).orElse(null);
            if (point != null) {
                result.add(viewForBinding(point, source.getChannelId(), source.getAddress()));
            }
        }
        return result;
    }

    /**
     * 测点的所有绑定视图：主绑定返回实体本体，附加来源各返回一个覆盖地址的视图。供写广播使用。
     */
    public List<MeasurementPoint> allBindingViews(MeasurementPoint point) {
        List<MeasurementPoint> views = new ArrayList<>();
        views.add(point);
        for (PointSource source : pointSourceRepository.findByPointId(point.getPointId())) {
            views.add(viewForBinding(point, source.getChannelId(), source.getAddress()));
        }
        return views;
    }

    /** 该测点绑定的全部通道（主 + 附加） */
    public Set<String> bindingChannelIds(String pointId) {
        Set<String> channels = new LinkedHashSet<>();
        pointRepository.findById(pointId).ifPresent(p -> channels.add(p.getChannelId()));
        for (PointSource source : pointSourceRepository.findByPointId(pointId)) {
            channels.add(source.getChannelId());
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

    /** 替换测点的附加来源（update 语义：先删后插） */
    @Transactional
    public void replaceSources(String pointId, List<PointSourceDTO> sources) {
        pointSourceRepository.deleteByPointId(pointId);
        if (sources == null || sources.isEmpty()) {
            return;
        }
        for (PointSourceDTO dto : sources) {
            PointSource source = new PointSource();
            source.setPointId(pointId);
            source.setChannelId(dto.getChannelId());
            source.setAddress(dto.getAddress());
            pointSourceRepository.save(source);
        }
    }

    /** 校验附加来源：通道存在、不与主绑定同通道、来源之间通道不重复 */
    public void validateSources(List<PointSourceDTO> sources, String mainChannelId) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PointSourceDTO dto : sources) {
            if (mainChannelId != null && mainChannelId.equals(dto.getChannelId())) {
                throw new BusinessException(400, "附加来源通道不能与主通道相同: " + dto.getChannelId());
            }
            if (!seen.add(dto.getChannelId())) {
                throw new BusinessException(400, "附加来源通道重复: " + dto.getChannelId());
            }
            if (channelRepository.findById(dto.getChannelId()).isEmpty()) {
                throw new BusinessException(400, "附加来源通道不存在: " + dto.getChannelId());
            }
        }
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
