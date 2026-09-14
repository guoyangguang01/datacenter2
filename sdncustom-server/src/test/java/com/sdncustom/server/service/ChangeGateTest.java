package com.sdncustom.server.service;

import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangeGate 变更检测测试")
class ChangeGateTest {

    private ChangeGate gate;

    @BeforeEach
    void setUp() {
        gate = new ChangeGate();
    }

    private MeasurementPoint point(String id, PointDataType type, Double deadband) {
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId(id);
        p.setDataType(type);
        p.setDeadband(deadband);
        return p;
    }

    private PointValue value(String pointId, Object value, PointQuality quality) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(quality);
        pv.setSourceChannelId("ch_001");
        pv.setTimestamp(System.currentTimeMillis());
        return pv;
    }

    @Test
    @DisplayName("首次出现的值总是通过")
    void firstValueAlwaysPasses() {
        var result = gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), Map.of("p1", point("p1", PointDataType.FLOAT32, null)));
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("相同值被过滤")
    void sameValueFiltered() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("值变化时通过")
    void differentValuePasses() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 26.0, PointQuality.GOOD)), points);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("质量变化无条件通过（值相同）")
    void qualityChangeAlwaysPasses() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 25.0, PointQuality.COMM_LOST)), points);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("死区 0：任何差值都通过")
    void deadbandZeroAnyDiffPasses() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT64, 0.0));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 25.0000001, PointQuality.GOOD)), points);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("死区阈值内被过滤")
    void deadbandFiltersWithinThreshold() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, 1.0));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 25.5, PointQuality.GOOD)), points);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("超过死区通过")
    void deadbandPassesAboveThreshold() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, 1.0));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 26.5, PointQuality.GOOD)), points);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("恰好等于死区被过滤（严格大于）")
    void deadbandExactBoundaryFiltered() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, 1.0));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", 26.0, PointQuality.GOOD)), points);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("BOOL 类型忽略死区，按相等判断")
    void boolIgnoresDeadband() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.BOOL, 5.0));
        gate.filter(List.of(value("p1", true, PointQuality.GOOD)), points);
        assertTrue(gate.filter(List.of(value("p1", true, PointQuality.GOOD)), points).isEmpty());
        assertEquals(1, gate.filter(List.of(value("p1", false, PointQuality.GOOD)), points).size());
    }

    @Test
    @DisplayName("STRING 类型按相等判断")
    void stringEquality() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.STRING, null));
        gate.filter(List.of(value("p1", "a", PointQuality.GOOD)), points);
        assertTrue(gate.filter(List.of(value("p1", "a", PointQuality.GOOD)), points).isEmpty());
        assertEquals(1, gate.filter(List.of(value("p1", "b", PointQuality.GOOD)), points).size());
    }

    @Test
    @DisplayName("null 值处理")
    void nullValueHandling() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.STRING, null));
        gate.filter(List.of(value("p1", null, PointQuality.GOOD)), points);
        assertTrue(gate.filter(List.of(value("p1", null, PointQuality.GOOD)), points).isEmpty());
        assertEquals(1, gate.filter(List.of(value("p1", "x", PointQuality.GOOD)), points).size());
    }

    @Test
    @DisplayName("数值型测点收到非数值视为变化")
    void nonNumericValueForNumericPoint() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT64, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        var result = gate.filter(List.of(value("p1", Map.of("temp", 25.0), PointQuality.GOOD)), points);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("NaN 变化可被检测")
    void nanValueHandled() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT64, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        assertEquals(1, gate.filter(List.of(value("p1", Double.NaN, PointQuality.GOOD)), points).size());
    }

    @Test
    @DisplayName("removePoints 后重新视为首值")
    void removePointsClearsState() {
        Map<String, MeasurementPoint> points = Map.of("p1", point("p1", PointDataType.FLOAT32, null));
        gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        gate.removePoints(List.of("p1"));
        var result = gate.filter(List.of(value("p1", 25.0, PointQuality.GOOD)), points);
        assertEquals(1, result.size());
    }
}
