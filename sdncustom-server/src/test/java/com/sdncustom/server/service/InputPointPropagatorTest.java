package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InputPointPropagator 传播测试")
class InputPointPropagatorTest {

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private ChannelService channelService;

    @Mock
    private ProtocolRegistry protocolRegistry;

    private SimpleMeterRegistry meterRegistry;

    private InputPointPropagator propagator;

    private MeasurementPoint inputPoint;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        propagator = new InputPointPropagator(pointRepository, pointSourceService,
                channelService, protocolRegistry, meterRegistry);

        inputPoint = new MeasurementPoint();
        inputPoint.setPointId("in_1");
        inputPoint.setBusinessId("default");
        inputPoint.setDirection(PointDirection.INPUT);
        inputPoint.setReferencePointId("out_1");
        inputPoint.setDataType(PointDataType.FLOAT32);
        // 刻意不给实体设 channelId/address：它们是 @Transient 视图字段，持久化实体上恒为空
        // （见 MeasurementPoint 的字段注释）。生产路径写入的是 allBindingViews 合成的**另一个对象**。
    }

    /**
     * 绑定视图。与实体是**不同的对象**，携带该绑定的 channelId/address——写出去的是它。
     * 若实现误把实体当视图写，写到的地址就是 null，而这里断言的身份与地址会立刻暴露。
     */
    private MeasurementPoint bindingView() {
        MeasurementPoint view = new MeasurementPoint();
        view.setPointId("in_1");
        view.setChannelId("ch_dst");
        view.setAddress("reg_dst");
        view.setDataType(PointDataType.FLOAT32);
        view.setDirection(PointDirection.INPUT);
        return view;
    }

    private PointValue outputChange(Object v) {
        PointValue pv = new PointValue();
        pv.setPointId("out_1");
        pv.setValue(v);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_src");
        pv.setTimestamp(1700000000000L);
        return pv;
    }

    private Channel connectedChannel(String id) {
        Channel ch = new Channel();
        ch.setChannelId(id);
        ch.setStatus(ChannelStatus.CONNECTED);
        ch.setDirection(ChannelDirection.READ_WRITE);
        return ch;
    }

    @Test
    @DisplayName("无输入测点引用：返回空且不查库")
    void noInputPointsMeansNoQuery() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of());

        List<PointValue> result = propagator.propagate(List.of(outputChange(25.0)));

        assertTrue(result.isEmpty());
        verifyNoInteractions(pointSourceService, protocolRegistry);
    }

    @Test
    @DisplayName("单个输入测点：写出并返回复制自输出测点的值")
    void propagatesSingleInputPoint() {
        MeasurementPoint view = bindingView();
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(view));
        Channel ch = connectedChannel("ch_dst");
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(ch)).thenReturn(adapter);

        List<PointValue> result = propagator.propagate(List.of(outputChange(25.0)));

        assertEquals(1, result.size());
        PointValue pv = result.get(0);
        assertEquals("in_1", pv.getPointId());
        assertEquals(25.0, pv.getValue());
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals("ch_dst", pv.getSourceChannelId());
        assertEquals(1700000000000L, pv.getTimestamp(), "时间戳应沿用输出测点");
        // 写的必须是绑定视图，且地址原样带到适配器（把实体当视图写 = 写到 null 地址）
        ArgumentCaptor<MeasurementPoint> written = ArgumentCaptor.forClass(MeasurementPoint.class);
        verify(adapter).writePoint(written.capture(), eq(25.0));
        assertSame(view, written.getValue());
        assertEquals("reg_dst", written.getValue().getAddress());
        assertEquals(1.0, meterRegistry.get("sdncustom.propagation.writes")
                .tag("channel", "ch_dst").counter().count());
    }

    @Test
    @DisplayName("多个输入测点引用同一输出测点：一次查询命中全部")
    void multipleInputPointsResolvedInOneQuery() {
        MeasurementPoint second = new MeasurementPoint();
        second.setPointId("in_2");
        second.setDirection(PointDirection.INPUT);
        second.setReferencePointId("out_1");
        second.setDataType(PointDataType.FLOAT32);
        when(pointRepository.findByReferencePointIdIn(List.of("out_1")))
                .thenReturn(List.of(inputPoint, second));
        when(pointSourceService.allBindingViews(any(MeasurementPoint.class)))
                .thenReturn(List.of());  // 无绑定 -> 跳过写出
        when(pointSourceService.bindingChannelIds(anyString())).thenReturn(java.util.Set.of());

        List<PointValue> result = propagator.propagate(List.of(outputChange(1.0)));

        assertEquals(2, result.size());
        assertEquals(List.of("in_1", "in_2"), result.stream().map(PointValue::getPointId).toList());
        verify(pointRepository, times(1)).findByReferencePointIdIn(anyList());
    }

    @Test
    @DisplayName("绑定通道未连接：跳过写出但仍返回值")
    void writesSkippedButValueReturned() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(bindingView()));
        Channel ch = connectedChannel("ch_dst");
        ch.setStatus(ChannelStatus.DISCONNECTED);
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        when(pointSourceService.bindingChannelIds("in_1")).thenReturn(java.util.Set.of("ch_dst"));

        List<PointValue> result = propagator.propagate(List.of(outputChange(7.0)));

        assertEquals(1, result.size());
        assertEquals(7.0, result.get(0).getValue());
        assertEquals("ch_dst", result.get(0).getSourceChannelId());
        verifyNoInteractions(protocolRegistry);
    }

    @Test
    @DisplayName("只读通道：跳过写出但仍返回值")
    void readOnlyChannelSkipped() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(bindingView()));
        Channel ch = connectedChannel("ch_dst");
        ch.setDirection(ChannelDirection.READ_ONLY);
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        when(pointSourceService.bindingChannelIds("in_1")).thenReturn(java.util.Set.of("ch_dst"));

        List<PointValue> result = propagator.propagate(List.of(outputChange(7.0)));

        assertEquals(1, result.size());
        verifyNoInteractions(protocolRegistry);
    }

    @Test
    @DisplayName("写出抛异常：不向上抛，仍返回值且失败计数 +1")
    void writeFailureIsContained() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(bindingView()));
        Channel ch = connectedChannel("ch_dst");
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(ch)).thenReturn(adapter);
        doThrow(new RuntimeException("boom")).when(adapter).writePoint(any(), any());

        List<PointValue> result = propagator.propagate(List.of(outputChange(3.0)));

        assertEquals(1, result.size());
        assertEquals(3.0, result.get(0).getValue());
        assertEquals(1.0, meterRegistry.get("sdncustom.propagation.failures")
                .tag("channel", "ch_dst").counter().count());
    }

    @Test
    @DisplayName("去重：多个输出测点变化合并成一次查询")
    void outputChangePointIdsDeduplicated() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of());

        propagator.propagate(List.of(outputChange(1.0), outputChange(2.0)));

        verify(pointRepository, times(1)).findByReferencePointIdIn(List.of("out_1"));
    }

    @Test
    @DisplayName("空输入：零开销")
    void emptyInput() {
        assertTrue(propagator.propagate(List.of()).isEmpty());
        verifyNoInteractions(pointRepository);
    }
}
