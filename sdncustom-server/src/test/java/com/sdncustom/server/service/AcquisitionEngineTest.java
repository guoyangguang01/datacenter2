package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AcquisitionEngine 采集流水线测试")
class AcquisitionEngineTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private ChannelService channelService;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private PointService pointService;

    @Mock
    private HistoryService historyService;

    @Mock
    private DistributionService distributionService;

    @Mock
    private ChangeGate changeGate;

    @Mock
    private InputPointPropagator inputPointPropagator;

    @Mock
    private ProtocolRegistry protocolRegistry;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private AcquisitionEngine engine;

    private Channel channel;
    private MeasurementPoint point;

    @BeforeEach
    void setUp() {
        engine.registerMetrics();

        channel = new Channel();
        channel.setChannelId("ch_001");
        channel.setStatus(ChannelStatus.CONNECTED);

        point = new MeasurementPoint();
        point.setPointId("p1");
        point.setChannelId("ch_001");
        point.setDataType(PointDataType.FLOAT32);
    }

    private PointValue value(String pointId, Object v) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(v);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_001");
        pv.setTimestamp(System.currentTimeMillis());
        return pv;
    }

    @Test
    @DisplayName("无连接通道时不做任何写入")
    void noConnectedChannels() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of());

        engine.acquire();

        verifyNoInteractions(pointService, historyService, distributionService);
    }

    @Test
    @DisplayName("通过变更检测的值被批量写入三处下游")
    void changedValuesFlushedInBatch() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);

        engine.acquire();

        verify(pointService).updateBatch(read);
        verify(historyService).saveBatch(read);
        verify(distributionService).pushBatch(read);
        assertEquals(1.0, meterRegistry.get("sdncustom.acquisition.cycle").timer().count());
        assertEquals(1.0, meterRegistry.get("sdncustom.acquisition.changed.values").counter().count());
    }

    @Test
    @DisplayName("无有效变化时零写入")
    void noChangesMeansNoWrites() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(List.of());

        engine.acquire();

        verifyNoInteractions(historyService, distributionService);
        verify(pointService, never()).updateBatch(anyList());
    }

    @Test
    @DisplayName("适配器断开时修正通道状态（非 MQTT）")
    void disconnectedAdapterTriggersChannelDisconnect() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(false);

        engine.acquire();

        verify(channelService).syncDisconnected("ch_001");
        verifyNoInteractions(historyService, distributionService);
    }

    @Test
    @DisplayName("输出测点变化触发传播，输入测点值并入同一批次")
    void propagationValuesJoinTheSameBatch() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);

        PointValue inputValue = new PointValue();
        inputValue.setPointId("in_1");
        inputValue.setValue(25.0);
        inputValue.setQuality(PointQuality.GOOD);
        inputValue.setSourceChannelId("ch_002");
        inputValue.setTimestamp(System.currentTimeMillis());
        when(inputPointPropagator.propagate(read)).thenReturn(List.of(inputValue));

        engine.acquire();

        List<PointValue> expected = List.of(read.get(0), inputValue);
        verify(pointService).updateBatch(expected);
        verify(historyService).saveBatch(expected);
        verify(distributionService).pushBatch(expected);
    }

    @Test
    @DisplayName("无有效变化时不触发传播")
    void noChangesMeansNoPropagation() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(List.of());

        engine.acquire();

        verifyNoInteractions(inputPointPropagator);
    }

    @Test
    @DisplayName("输入测点的来源通道掉线不影响传播值推送")
    void inputValuesBypassLivenessFilter() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);

        // 输入测点写出目标通道 ch_dead 不在 CONNECTED 集合里
        PointValue inputValue = new PointValue();
        inputValue.setPointId("in_1");
        inputValue.setValue(25.0);
        inputValue.setQuality(PointQuality.GOOD);
        inputValue.setSourceChannelId("ch_dead");
        inputValue.setTimestamp(System.currentTimeMillis());
        when(inputPointPropagator.propagate(read)).thenReturn(List.of(inputValue));

        engine.acquire();

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(distributionService).pushBatch(captor.capture());
        assertTrue(captor.getValue().stream().anyMatch(pv -> pv.getPointId().equals("in_1")),
                "输入测点值不应被来源存活过滤丢掉");
    }
}
