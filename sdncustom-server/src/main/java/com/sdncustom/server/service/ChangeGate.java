package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更检测门：每个测点只有一个来源通道（一对一关联），直接按 pointId 记录最后发出的值。
 * 只有当值发生有效变化（质量变化 / 数值超死区 / 非数值不同）才进入下游
 * （实时缓存 / 历史存储 / WebSocket 推送），避免重复数据打爆存储和推送通道。
 */
@Slf4j
@Service
public class ChangeGate {

    private static final Set<PointDataType> NUMERIC_TYPES = Set.of(
            PointDataType.INT16, PointDataType.INT32, PointDataType.FLOAT32, PointDataType.FLOAT64);

    /** pointId -> 上次发出的权威值 */
    private final Map<String, PointValue> authority = new ConcurrentHashMap<>();

    /**
     * 过滤采集值：仅当权威值发生有效变化时返回。
     *
     * @param incoming    适配器读取到的原始值
     * @param pointsById  pointId -> 测点配置（用于死区查询）
     */
    public List<PointValue> filter(List<PointValue> incoming, Map<String, MeasurementPoint> pointsById) {
        List<PointValue> changed = new ArrayList<>();
        for (PointValue pv : incoming) {
            MeasurementPoint point = pointsById.get(pv.getPointId());
            PointValue emitted = mergeAndMaybeEmit(pv.getPointId(), pv, point);
            if (emitted != null) {
                changed.add(emitted);
            }
        }
        return changed;
    }

    /** 测点删除/通道断连时清理该点状态 */
    public void removePoints(Collection<String> pointIds) {
        for (String pointId : pointIds) {
            authority.remove(pointId);
        }
    }

    private PointValue mergeAndMaybeEmit(String pointId, PointValue incoming, MeasurementPoint point) {
        PointValue prev = authority.get(pointId);
        if (isEffectiveChange(prev, incoming, point)) {
            PointValue next = copy(incoming);
            authority.put(pointId, next);
            return next;
        }
        return null;
    }

    private boolean isEffectiveChange(PointValue prev, PointValue incoming, MeasurementPoint point) {
        if (prev == null) {
            return true;
        }
        if (prev.getQuality() != incoming.getQuality()) {
            return true;
        }
        if (point != null && NUMERIC_TYPES.contains(point.getDataType())) {
            double deadband = point.getDeadband() == null ? 0.0 : point.getDeadband();
            if (prev.getValue() instanceof Number oldNum && incoming.getValue() instanceof Number newNum) {
                double oldValue = oldNum.doubleValue();
                double newValue = newNum.doubleValue();
                if (Double.isFinite(oldValue) && Double.isFinite(newValue)) {
                    return Math.abs(newValue - oldValue) > deadband;
                }
                // NaN/Infinity：按位比较，避免 NaN 永远不通过
                return Double.compare(oldValue, newValue) != 0;
            }
            // 数值型测点收到非数值（类型配置与设备不符）：视为变化，暴露问题
            return true;
        }
        return !Objects.equals(prev.getValue(), incoming.getValue());
    }

    private PointValue copy(PointValue pv) {
        PointValue copy = new PointValue();
        copy.setPointId(pv.getPointId());
        copy.setValue(pv.getValue());
        copy.setQuality(pv.getQuality());
        copy.setSourceChannelId(pv.getSourceChannelId());
        copy.setTimestamp(pv.getTimestamp());
        return copy;
    }
}
