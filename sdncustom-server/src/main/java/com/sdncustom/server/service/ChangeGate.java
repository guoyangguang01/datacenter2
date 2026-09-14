package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 变更检测门 + 多来源合并：一个测点可同时被多个通道（来源）采集，
 * 最终权威值 = 质量优先（GOOD>UNCERTAIN>BAD>COMM_LOST）→ 时间戳最新 → 来源键稳定平局。
 * 只有权威值发生有效变化（质量变化 / 数值超死区 / 非数值不同）才进入下游
 * （实时缓存 / 历史存储 / WebSocket 推送），避免重复数据打爆存储和推送通道。
 * 单来源测点行为与旧版一致（权威值即该来源的值）。
 */
@Slf4j
@Service
public class ChangeGate {

    private static final Set<PointDataType> NUMERIC_TYPES = Set.of(
            PointDataType.INT16, PointDataType.INT32, PointDataType.FLOAT32, PointDataType.FLOAT64);

    private static final Map<PointQuality, Integer> QUALITY_RANK = new EnumMap<>(PointQuality.class);

    static {
        QUALITY_RANK.put(PointQuality.GOOD, 3);
        QUALITY_RANK.put(PointQuality.UNCERTAIN, 2);
        QUALITY_RANK.put(PointQuality.BAD, 1);
        QUALITY_RANK.put(PointQuality.COMM_LOST, 0);
    }

    /** 来源键 pointId|channelId -> 该来源最后一次读到的值 */
    private final Map<String, PointValue> sourceLast = new ConcurrentHashMap<>();
    /** pointId -> 活跃来源键集合 */
    private final Map<String, Set<String>> pointSourceKeys = new ConcurrentHashMap<>();
    /** pointId -> 上次发出的权威值（质量优先合并结果） */
    private final Map<String, PointValue> authority = new ConcurrentHashMap<>();

    /**
     * 过滤采集值：更新各来源最后值，合并出权威值，仅当权威值发生有效变化时返回。
     *
     * @param incoming    适配器读取到的原始值（含来源通道）
     * @param pointsById  pointId -> 测点配置（用于死区查询）
     */
    public List<PointValue> filter(List<PointValue> incoming, Map<String, MeasurementPoint> pointsById) {
        List<PointValue> changed = new ArrayList<>();
        for (PointValue pv : incoming) {
            MeasurementPoint point = pointsById.get(pv.getPointId());
            String channelId = pv.getSourceChannelId();
            if (channelId == null) {
                channelId = point != null ? point.getChannelId() : "";
            }
            String sourceKey = sourceKey(pv.getPointId(), channelId);
            sourceLast.put(sourceKey, copy(pv));
            pointSourceKeys.computeIfAbsent(pv.getPointId(), k -> ConcurrentHashMap.newKeySet()).add(sourceKey);

            PointValue emitted = mergeAndMaybeEmit(pv.getPointId(), point);
            if (emitted != null) {
                changed.add(emitted);
            }
        }
        return changed;
    }

    /** 测点删除/通道断连时清理该点状态（含权威基线），避免陈旧值滞留 */
    public void removePoints(Collection<String> pointIds) {
        for (String pointId : pointIds) {
            String prefix = pointId + "|";
            sourceLast.keySet().removeIf(key -> key.startsWith(prefix));
            pointSourceKeys.remove(pointId);
            authority.remove(pointId);
        }
    }

    /**
     * 测点绑定配置变更后，把来源状态收敛到 activeChannelIds（仅保留仍在绑定的来源），
     * 不动 authority（保留权威基线，避免配置变动触发重复上报）。
     */
    public void syncPointBindings(String pointId, Set<String> activeChannelIds) {
        Set<String> activeKeys = new HashSet<>();
        if (activeChannelIds != null) {
            for (String channelId : activeChannelIds) {
                activeKeys.add(sourceKey(pointId, channelId));
            }
        }
        Set<String> keys = ConcurrentHashMap.newKeySet();
        keys.addAll(activeKeys);
        pointSourceKeys.put(pointId, keys);
        String prefix = pointId + "|";
        sourceLast.keySet().removeIf(key -> key.startsWith(prefix) && !activeKeys.contains(key));
    }

    private PointValue mergeAndMaybeEmit(String pointId, MeasurementPoint point) {
        AtomicReference<PointValue> emitted = new AtomicReference<>();
        authority.compute(pointId, (pid, prevAuthority) -> {
            PointValue merged = mergeSources(pid);
            if (merged == null) {
                return prevAuthority;
            }
            if (isEffectiveAuthorityChange(prevAuthority, merged, point)) {
                PointValue next = copy(merged);
                emitted.set(next);
                return next;
            }
            return prevAuthority;
        });
        return emitted.get();
    }

    private PointValue mergeSources(String pointId) {
        Set<String> keys = pointSourceKeys.get(pointId);
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        PointValue best = null;
        for (String key : keys) {
            PointValue candidate = sourceLast.get(key);
            if (candidate != null) {
                best = better(candidate, best);
            }
        }
        return best;
    }

    /** 质量 rank 高者胜 → 时间戳大者胜 → 来源键字典序小者胜（确定性平局） */
    private PointValue better(PointValue candidate, PointValue currentBest) {
        if (currentBest == null) {
            return candidate;
        }
        int rankCmp = Integer.compare(rank(candidate), rank(currentBest));
        if (rankCmp != 0) {
            return rankCmp > 0 ? candidate : currentBest;
        }
        long tsCmp = candidate.getTimestamp() - currentBest.getTimestamp();
        if (tsCmp != 0) {
            return tsCmp > 0 ? candidate : currentBest;
        }
        String candKey = sourceKey(candidate.getPointId(), candidate.getSourceChannelId());
        String bestKey = sourceKey(currentBest.getPointId(), currentBest.getSourceChannelId());
        return candKey.compareTo(bestKey) <= 0 ? candidate : currentBest;
    }

    private int rank(PointValue pv) {
        return QUALITY_RANK.getOrDefault(pv.getQuality(), 0);
    }

    private boolean isEffectiveAuthorityChange(PointValue prev, PointValue incoming, MeasurementPoint point) {
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

    private static String sourceKey(String pointId, String channelId) {
        return pointId + "|" + (channelId != null ? channelId : "");
    }
}
