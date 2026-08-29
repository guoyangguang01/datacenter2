package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChangeGate 多来源质量优先合并测试。
 */
@DisplayName("ChangeGate 多来源合并测试")
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
    @DisplayName("质量优先：GOOD 覆盖 BAD/UNCERTAIN/COMM_LOST")
    void qualityPriority() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        List<PointValue> out1 = gate.filter(
                List.of(v("p1", "ch_a", 10.0, PointQuality.BAD, 1000L)), Map.of("p1", p));
        assertEquals(PointQuality.BAD, out1.get(0).getQuality());

        List<PointValue> out2 = gate.filter(
                List.of(v("p1", "ch_b", 25.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertEquals(1, out2.size());
        assertEquals(PointQuality.GOOD, out2.get(0).getQuality());
        assertEquals(25.0, out2.get(0).getValue());
    }

    @Test
    @DisplayName("同质量取时间戳最新")
    void latestTimestampWinsAmongSameQuality() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_b", 20.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertEquals(1, out.size());
        assertEquals(20.0, out.get(0).getValue());
        assertEquals("ch_b", out.get(0).getSourceChannelId());
    }

    @Test
    @DisplayName("同质量同时间戳按来源键稳定平局")
    void stableTiebreakBySourceKey() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_b", 25.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 15.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        assertEquals(1, out.size());
        // ch_a 字典序小于 ch_b → ch_a 胜出，跨周期确定性
        assertEquals("ch_a", out.get(0).getSourceChannelId());
        assertEquals(15.0, out.get(0).getValue());
    }

    @Test
    @DisplayName("死区对权威值增量生效（多来源交替不重复上报）")
    void deadbandAppliesToAuthorityValue() {
        MeasurementPoint p = numericPoint("p1", 5.0);
        gate.filter(List.of(v("p1", "ch_a", 100.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        // ch_b 新值 102 与权威差 2 < 死区 5 → 不发出
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_b", 102.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertTrue(out.isEmpty());
        // ch_a 新值 120 差 20 > 死区 5 → 发出
        List<PointValue> out2 = gate.filter(
                List.of(v("p1", "ch_a", 120.0, PointQuality.GOOD, 3000L)), Map.of("p1", p));
        assertEquals(1, out2.size());
        assertEquals(120.0, out2.get(0).getValue());
    }

    @Test
    @DisplayName("来源切换：更晚更新的来源接管权威值")
    void sourceSwitchWhenFresher() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_b", 20.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        assertEquals("ch_b", out.get(0).getSourceChannelId());

        List<PointValue> out2 = gate.filter(
                List.of(v("p1", "ch_a", 15.0, PointQuality.GOOD, 3000L)), Map.of("p1", p));
        assertEquals("ch_a", out2.get(0).getSourceChannelId());
        assertEquals(15.0, out2.get(0).getValue());
    }

    @Test
    @DisplayName("手动写值作为权威基线，同值不重复上报")
    void recordManualWriteSuppressesRepeat() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.recordManualWrite("p1", 42.0, PointQuality.GOOD, "ch_a");
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 42.0, PointQuality.GOOD, 5000L)), Map.of("p1", p));
        assertTrue(out.isEmpty());
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

    @Test
    @DisplayName("syncPointBindings 剪除已移除来源，权威回落到保留来源")
    void syncPointBindingsPrunesSources() {
        MeasurementPoint p = numericPoint("p1", 0.0);
        gate.filter(List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 1000L)), Map.of("p1", p));
        gate.filter(List.of(v("p1", "ch_b", 20.0, PointQuality.GOOD, 2000L)), Map.of("p1", p));
        // 配置变更：只保留 ch_a → ch_b 来源被剪除
        gate.syncPointBindings("p1", Set.of("ch_a"));
        // ch_a 以新时间戳报 10：相对当前权威 20 是变化 → 发出并回落基线
        List<PointValue> out = gate.filter(
                List.of(v("p1", "ch_a", 10.0, PointQuality.GOOD, 3000L)), Map.of("p1", p));
        assertEquals(1, out.size());
        assertEquals(10.0, out.get(0).getValue());
    }
}
