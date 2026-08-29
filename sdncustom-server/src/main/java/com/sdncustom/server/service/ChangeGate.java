package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
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
 * 变更检测门：只有有效变化（超过测点死区或质量变化）的值才进入下游
 * （实时缓存 / 历史存储 / WebSocket 推送），避免重复数据打爆存储和推送通道
 */
@Slf4j
@Service
public class ChangeGate {

    private static final Set<PointDataType> NUMERIC_TYPES = Set.of(
            PointDataType.INT16, PointDataType.INT32, PointDataType.FLOAT32, PointDataType.FLOAT64);

    private final Map<String, PointValue> lastValues = new ConcurrentHashMap<>();

    /**
     * 过滤采集值，仅返回有效变化
     *
     * @param incoming      适配器读取到的原始值
     * @param pointsById    pointId -> 测点配置（用于死区查询）
     * @return 通过变更检测的值列表
     */
    public List<PointValue> filter(List<PointValue> incoming, Map<String, MeasurementPoint> pointsById) {
        List<PointValue> changed = new ArrayList<>();
        for (PointValue pv : incoming) {
            if (isEffectiveChange(pv, pointsById.get(pv.getPointId()))) {
                lastValues.put(pv.getPointId(), copy(pv));
                changed.add(pv);
            }
        }
        return changed;
    }

    /**
     * 手动写值成功后同步门状态，避免下一轮采集把相同值当作变化重复上报
     */
    public void recordManualWrite(String pointId, Object value, PointQuality quality, String sourceChannelId) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(quality);
        pv.setSourceChannelId(sourceChannelId);
        pv.setTimestamp(System.currentTimeMillis());
        lastValues.put(pointId, pv);
    }

    /**
     * 测点删除时清理门状态
     */
    public void removePoints(Collection<String> pointIds) {
        pointIds.forEach(lastValues::remove);
    }

    private boolean isEffectiveChange(PointValue incoming, MeasurementPoint point) {
        PointValue last = lastValues.get(incoming.getPointId());
        if (last == null) {
            return true;
        }
        if (last.getQuality() != incoming.getQuality()) {
            return true;
        }
        if (point != null && NUMERIC_TYPES.contains(point.getDataType())) {
            double deadband = point.getDeadband() == null ? 0.0 : point.getDeadband();
            if (last.getValue() instanceof Number oldNum && incoming.getValue() instanceof Number newNum) {
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
        return !Objects.equals(last.getValue(), incoming.getValue());
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
