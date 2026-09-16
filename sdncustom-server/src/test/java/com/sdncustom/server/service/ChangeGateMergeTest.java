package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChangeGate 变更检测与死区测试。
 * 简化为单来源模型（无多来源合并），但保留质量优先/时间戳比较的基本行为验证。
 */
@DisplayName("ChangeGate 变更与死区测试")
class ChangeGateMergeTest {

    private final ChangeGate gate = new ChangeGate();

    private MeasurementPoint numericPoint(String id, Double deadband) {
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId(id);
        p.setDataType(PointDataType.FLOAT32);
        p.setDeadband(deadband);
        return p;
    }

    private PointValue v(String pointId, String channel, Object value, PointQuality q, long ts) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setSourceChannelId(channel);
        pv.setValue(value);
        pv.setQuality(q);
        pv.setTimestamp(ts);
        return pv;
    }

    @Test
    @DisplayName("质量变化：GOOD 覆盖 BAD")
    void qualityChangeDetected() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        List<PointValue> out1 = gate.filter(
                List.of(v("p1", "ch_a", 10.0, PointQuality.BAD, 1000L)), Map.of("p1", p));
        assertEquals(PointQuality.BAD, out1.get(0).getQuality());

        List<PointValue> out2 = gate.filter(
                List.of(v("p1", "ch_a", 25.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertEquals(1, out2.size());
        assertEquals(PointQuality.GOOD, out2.get(0).getQuality());
        assertEquals(25.0, out2.get(0).getValue());
    }

    @Test
    @DisplayName("同值同质量被过滤")
    void sameValueFiltered() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("死区对权威值增量生效")
    void deadbandAppliesToAuthorityValue() {
        MeasurementPoint p = numericPoint("p1", 5.0);
        gate.filter(List.of(v("p1", "ch_a", 100.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        // 新值 102 与权威差 2 < 死区 5 → 不发出
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 102.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertTrue(out.isEmpty());
        // 新值 120 差 20 > 死区 5 → 发出
        List<PointValue> out2 = gate.filter(
                List.of(v("p1", "ch_a", 120.0, PointQuality.GOOD, 3000L)), Map.of("p1", p));
        assertEquals(1, out2.size());
        assertEquals(120.0, out2.get(0).getValue());
    }

    @Test
    @DisplayName("removePoints 清基线后同值重新上报")
    void removePointsClearsBaseline() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        gate.removePoints(List.of("p1"));
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertEquals(1, out.size());
        assertEquals(10.0, out.get(0).getValue());
    }
}
