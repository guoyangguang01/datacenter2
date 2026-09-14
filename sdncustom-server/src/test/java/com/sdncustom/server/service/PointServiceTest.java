package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PointService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointService 测试")
class PointServiceTest {

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private PointValueCacheRepository pointValueCache;

    @Mock
    private ChannelService channelService;

    @Mock
    private ChangeGate changeGate;

    @Mock
    private ProtocolRegistry protocolRegistry;

    @Mock
    private PointSourceRepository pointSourceRepository;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private PointBindingRegistry pointBindingRegistry;

    @Mock
    private DistributionService distributionService;

    @Mock
    private BusinessSystemService businessSystemService;

    @InjectMocks
    private PointService pointService;

    private MeasurementPoint testPoint;
    private MeasurementPointDTO testDto;
    private Channel disconnectedChannel;

    @BeforeEach
    void setUp() {
        disconnectedChannel = new Channel();
        disconnectedChannel.setChannelId("ch_001");
        disconnectedChannel.setStatus(ChannelStatus.DISCONNECTED);

        testPoint = new MeasurementPoint();
        testPoint.setPointId("test_point_001");
        testPoint.setBusinessId("default");
        testPoint.setPointName("测试测点");
        testPoint.setChannelId("ch_001");
        testPoint.setAddress("40001");
        testPoint.setDataType(PointDataType.INT16);
        testPoint.setUnit("°C");
        testPoint.setWritable(false);

        testDto = new MeasurementPointDTO();
        testDto.setPointId("test_point_001");
        testDto.setBusinessId("default");
        testDto.setPointName("测试测点");
        testDto.setBindings(List.of(binding("ch_001", "40001")));
        testDto.setDataType(PointDataType.INT16);
        testDto.setUnit("°C");
        testDto.setWritable(false);
    }

    private static PointSourceDTO binding(String channelId, String address) {
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId(channelId);
        dto.setAddress(address);
        return dto;
    }

    @Test
    @DisplayName("查询所有测点")
    void findAll() {
        when(pointRepository.findAll()).thenReturn(Arrays.asList(testPoint));

        List<MeasurementPoint> result = pointService.findAll();

        assertEquals(1, result.size());
        assertEquals("test_point_001", result.get(0).getPointId());
        verify(pointRepository).findAll();
    }

    @Test
    @DisplayName("根据通道ID查询测点（绑定集：任意绑定命中）")
    void findByChannelId() {
        PointSource ps = new PointSource();
        ps.setPointId("test_point_001");
        ps.setChannelId("ch_001");
        ps.setAddress("40001");
        when(pointSourceRepository.findByChannelId("ch_001")).thenReturn(List.of(ps));
        when(pointRepository.findAllById(List.of("test_point_001"))).thenReturn(Arrays.asList(testPoint));

        List<MeasurementPoint> result = pointService.findByChannelId("ch_001");

        assertEquals(1, result.size());
        assertEquals("test_point_001", result.get(0).getPointId());
    }

    @Test
    @DisplayName("根据ID查询测点")
    void findById() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));

        MeasurementPoint result = pointService.findById("test_point_001");

        assertNotNull(result);
        assertEquals("test_point_001", result.getPointId());
    }

    @Test
    @DisplayName("根据ID查询测点 - 不存在")
    void findByIdNotFound() {
        when(pointRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> pointService.findById("nonexistent"));
    }

    @Test
    @DisplayName("创建测点")
    void create() {
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        MeasurementPoint result = pointService.create(testDto);

        assertNotNull(result);
        assertEquals("test_point_001", result.getPointId());
        verify(businessSystemService).requireExists("default");
        ArgumentCaptor<MeasurementPoint> captor = ArgumentCaptor.forClass(MeasurementPoint.class);
        verify(pointRepository).save(captor.capture());
        assertEquals("default", captor.getValue().getBusinessId());
        verify(pointSourceService).replaceBindings("test_point_001", testDto.getBindings());
    }

    @Test
    @DisplayName("创建测点 - businessId 缺失时拒绝")
    void createWithoutBusinessRejected() {
        testDto.setBusinessId(null);
        doThrow(new com.sdncustom.common.exception.BusinessException(400, "businessId 不能为空"))
                .when(businessSystemService).requireExists(null);

        assertThrows(com.sdncustom.common.exception.BusinessException.class,
                () -> pointService.create(testDto));
        verify(pointRepository, never()).save(any());
    }

    @Test
    @DisplayName("更新测点 - businessId 不可变更（绑定校验以现有业务为准）")
    void updateDoesNotChangeBusinessId() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        testDto.setBusinessId("other");
        pointService.update("test_point_001", testDto);

        assertEquals("default", testPoint.getBusinessId());
        verify(pointSourceService).validateBindings(testDto.getBindings(), "default");
    }

    @Test
    @DisplayName("在已连接通道新增测点 - 触发 onConnected 增量订阅")
    void createOnConnectedChannelSubscribes() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);
        when(protocolRegistry.get("ch_001")).thenReturn(Optional.of(adapter));

        pointService.create(testDto);

        ArgumentCaptor<List<MeasurementPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(adapter).onConnected(captor.capture());
        assertEquals("test_point_001", captor.getValue().get(0).getPointId());
        assertEquals("ch_001", captor.getValue().get(0).getChannelId());
        assertEquals("40001", captor.getValue().get(0).getAddress());
    }

    @Test
    @DisplayName("更新测点")
    void update() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        testDto.setPointName("更新后的名称");
        MeasurementPoint result = pointService.update("test_point_001", testDto);

        assertNotNull(result);
        verify(pointRepository).save(any(MeasurementPoint.class));
    }

    @Test
    @DisplayName("删除测点")
    void delete() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        doNothing().when(pointRepository).deleteById("test_point_001");
        doNothing().when(pointValueCache).delete("test_point_001");

        pointService.delete("test_point_001");

        verify(pointRepository).deleteById("test_point_001");
        verify(pointValueCache).delete("test_point_001");
    }

    @Test
    @DisplayName("删除不存在的测点 -> ResourceNotFoundException，不静默成功")
    void deleteMissingPoint() {
        when(pointRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> pointService.delete("missing"));
        verify(pointRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("获取测点当前值 - 有缓存")
    void getValueWithCache() {
        PointValue cachedValue = new PointValue();
        cachedValue.setPointId("test_point_001");
        cachedValue.setValue(25.6);
        cachedValue.setQuality(PointQuality.GOOD);

        when(pointValueCache.findByPointId("test_point_001")).thenReturn(Optional.of(cachedValue));

        PointValue result = pointService.getValue("test_point_001");

        assertNotNull(result);
        assertEquals(25.6, result.getValue());
        assertEquals(PointQuality.GOOD, result.getQuality());
    }

    @Test
    @DisplayName("获取测点当前值 - 无缓存")
    void getValueWithoutCache() {
        when(pointValueCache.findByPointId("test_point_001")).thenReturn(Optional.empty());

        PointValue result = pointService.getValue("test_point_001");

        assertNotNull(result);
        assertEquals(PointQuality.COMM_LOST, result.getQuality());
    }

    @Test
    @DisplayName("批量更新测点值")
    void updateBatch() {
        PointValue newValue = new PointValue();
        newValue.setPointId("test_point_001");
        newValue.setValue(30.5);
        newValue.setQuality(PointQuality.GOOD);

        pointService.updateBatch(Arrays.asList(newValue));

        verify(pointValueCache).saveBatch(Arrays.asList(newValue));
    }

    @Test
    @DisplayName("手动写值后同步变更门状态")
    void writeValueRecordsChangeGate() {
        testPoint.setWritable(true);
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        Channel channel = new Channel();
        channel.setChannelId("ch_001");
        channel.setStatus(ChannelStatus.CONNECTED);
        channel.setDirection(ChannelDirection.READ_WRITE);
        when(channelService.findByIdOrNull("ch_001")).thenReturn(channel);
        when(pointSourceService.allBindingViews(testPoint)).thenReturn(List.of(testPoint));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);

        pointService.writeValue("test_point_001", 42.0);

        verify(adapter).writePoint(testPoint, 42.0);
        verify(pointValueCache).save(any(PointValue.class));
        verify(changeGate).recordManualWrite("test_point_001", 42.0, PointQuality.GOOD, "ch_001");
    }

    @Test
    @DisplayName("写值广播到所有绑定通道（主绑定 + 附加来源），各自用各自地址")
    void writeValueBroadcastsToAllBindings() {
        testPoint.setWritable(true);
        MeasurementPoint sourceView = new MeasurementPoint();
        sourceView.setPointId("test_point_001");
        sourceView.setChannelId("ch_002");
        sourceView.setAddress("reg2");
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        Channel mainCh = new Channel();
        mainCh.setChannelId("ch_001");
        mainCh.setStatus(ChannelStatus.CONNECTED);
        mainCh.setDirection(ChannelDirection.READ_WRITE);
        Channel srcCh = new Channel();
        srcCh.setChannelId("ch_002");
        srcCh.setStatus(ChannelStatus.CONNECTED);
        srcCh.setDirection(ChannelDirection.READ_WRITE);
        when(channelService.findByIdOrNull("ch_001")).thenReturn(mainCh);
        when(channelService.findByIdOrNull("ch_002")).thenReturn(srcCh);
        when(pointSourceService.allBindingViews(testPoint)).thenReturn(List.of(testPoint, sourceView));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(mainCh)).thenReturn(adapter);
        when(protocolRegistry.getOrCreate(srcCh)).thenReturn(adapter);

        pointService.writeValue("test_point_001", 99.0);

        verify(adapter).writePoint(testPoint, 99.0);
        verify(adapter).writePoint(sourceView, 99.0);
    }

    @Test
    @DisplayName("addBinding 给既有测点加绑定并触发订阅")
    void addBinding() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointSourceRepository.findByPointId("test_point_001")).thenReturn(List.of());
        when(pointSourceService.bindingChannelIds("test_point_001")).thenReturn(java.util.Set.of("ch_001", "ch_002"));
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.get("ch_002")).thenReturn(Optional.of(adapter));

        MeasurementPoint result = pointService.addBinding("test_point_001", "ch_002", "reg2");

        verify(pointSourceService).addBinding("test_point_001", "ch_002", "reg2", "default");
        verify(adapter).onConnected(anyList());
        verify(pointBindingRegistry).invalidate("test_point_001");
        verify(changeGate).syncPointBindings(eq("test_point_001"), anySet());
        assertNotNull(result);
    }

    @Test
    @DisplayName("所有绑定通道都不可写/未连接时写值抛异常")
    void writeValueAllBindingsFailThrows() {
        testPoint.setWritable(true);
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointSourceService.allBindingViews(testPoint)).thenReturn(List.of(testPoint));
        Channel disconnected = new Channel();
        disconnected.setChannelId("ch_001");
        disconnected.setStatus(ChannelStatus.DISCONNECTED);
        when(channelService.findByIdOrNull("ch_001")).thenReturn(disconnected);

        assertThrows(RuntimeException.class, () -> pointService.writeValue("test_point_001", 1.0));
    }

    @Test
    @DisplayName("批量导入测点")
    void importPoints() {
        List<MeasurementPointDTO> dtos = Arrays.asList(testDto);

        when(pointRepository.findById("test_point_001")).thenReturn(Optional.empty());
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        List<MeasurementPoint> result = pointService.importPoints(dtos);

        assertEquals(1, result.size());
        verify(pointRepository).save(any(MeasurementPoint.class));
    }

    @Test
    @DisplayName("批量导入测点 - 更新已存在")
    void importPointsUpdateExisting() {
        List<MeasurementPointDTO> dtos = Arrays.asList(testDto);

        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        List<MeasurementPoint> result = pointService.importPoints(dtos);

        assertEquals(1, result.size());
        verify(pointRepository).save(any(MeasurementPoint.class));
    }
}
