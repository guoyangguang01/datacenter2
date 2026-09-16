package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
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
    private ChangeGate changeGate;

    @Mock
    private ProtocolRegistry protocolRegistry;

    @Mock
    private BusinessSystemService businessSystemService;

    @Mock
    private PointDirectionValidator pointDirectionValidator;

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
        testPoint.setDirection(PointDirection.OUTPUT);

        testDto = new MeasurementPointDTO();
        testDto.setPointId("test_point_001");
        testDto.setBusinessId("default");
        testDto.setPointName("测试测点");
        testDto.setChannelId("ch_001");
        testDto.setAddress("40001");
        testDto.setDataType(PointDataType.INT16);
        testDto.setUnit("°C");
        testDto.setDirection(PointDirection.OUTPUT);
    }

    /** 引用 test_point_001 的输入测点（删除保护用例用） */
    private static MeasurementPoint inputPoint(String pointId) {
        MeasurementPoint input = new MeasurementPoint();
        input.setPointId(pointId);
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("test_point_001");
        return input;
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
    @DisplayName("根据通道ID查询测点")
    void findByChannelId() {
        when(pointRepository.findByChannelId("ch_001")).thenReturn(Arrays.asList(testPoint));

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

        MeasurementPoint result = pointService.create(testDto);

        assertNotNull(result);
        assertEquals("test_point_001", result.getPointId());
        verify(businessSystemService).requireExists("default");
        ArgumentCaptor<MeasurementPoint> captor = ArgumentCaptor.forClass(MeasurementPoint.class);
        verify(pointRepository).save(captor.capture());
        assertEquals("default", captor.getValue().getBusinessId());
        assertEquals("ch_001", captor.getValue().getChannelId());
        assertEquals("40001", captor.getValue().getAddress());
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
    @DisplayName("更新测点 - businessId 不可变更")
    void updateDoesNotChangeBusinessId() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);

        testDto.setBusinessId("other");
        pointService.update("test_point_001", testDto);

        assertEquals("default", testPoint.getBusinessId());
    }

    @Test
    @DisplayName("更新测点 - 方向与引用创建后不可变更（DTO 中的 direction/referencePointId 被忽略）")
    void updateDoesNotChangeDirectionOrReference() {
        // 已存测点：OUTPUT、无引用；DTO 却声称改成 INPUT 并引用 out_1
        testPoint.setDirection(PointDirection.OUTPUT);
        testPoint.setReferencePointId(null);
        testDto.setDirection(PointDirection.INPUT);
        testDto.setReferencePointId("out_1");

        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);

        pointService.update("test_point_001", testDto);

        // 若 update() 照写 DTO，这里会变成 INPUT/"out_1"——缺 referencePointId 的编辑请求会把它清成 null
        assertEquals(PointDirection.OUTPUT, testPoint.getDirection());
        assertNull(testPoint.getReferencePointId());
    }

    @Test
    @DisplayName("在已连接通道新增测点 - 触发 onConnected 增量订阅")
    void createOnConnectedChannelSubscribes() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(protocolRegistry.get("ch_001")).thenReturn(Optional.of(adapter));

        pointService.create(testDto);

        ArgumentCaptor<List<MeasurementPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(adapter).onConnected(captor.capture());
        assertEquals("test_point_001", captor.getValue().get(0).getPointId());
        assertEquals("ch_001", captor.getValue().get(0).getChannelId());
        assertEquals("40001", captor.getValue().get(0).getAddress());
    }

    @Test
    @DisplayName("创建 INPUT 测点不触发订阅：订阅是读路径，INPUT 的绑定是写出目标")
    void createInputPointDoesNotSubscribe() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        // lenient：本用例断言的是"压根不查适配器"，若把它当必需 stub 会先被 strict stubs 判失败
        lenient().when(protocolRegistry.get("ch_001")).thenReturn(Optional.of(adapter));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(inputPoint("in_1"));

        testDto.setPointId("in_1");
        testDto.setDirection(PointDirection.INPUT);
        testDto.setReferencePointId("test_point_001");

        pointService.create(testDto);

        verify(protocolRegistry, never()).get("ch_001");
        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("更新 INPUT 测点不触发订阅")
    void updateInputPointDoesNotSubscribe() {
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        lenient().when(protocolRegistry.get("ch_001")).thenReturn(Optional.of(adapter));
        when(pointRepository.findById("in_1")).thenReturn(Optional.of(inputPoint("in_1")));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(inputPoint("in_1"));

        testDto.setPointId("in_1");
        pointService.update("in_1", testDto);

        verify(protocolRegistry, never()).get("ch_001");
        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("更新测点")
    void update() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);

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
    @DisplayName("删除被输入测点引用的输出测点 -> 400，并点名引用它的测点")
    void deleteReferencedPointRejected() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.findByReferencePointId("test_point_001"))
                .thenReturn(List.of(inputPoint("in_1"), inputPoint("in_2")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> pointService.delete("test_point_001"));

        // 只说"被引用"用户不知道该删谁：消息必须点名引用者
        assertTrue(ex.getMessage().contains("in_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in_2"), ex.getMessage());
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
    @DisplayName("批量导入测点")
    void importPoints() {
        List<MeasurementPointDTO> dtos = Arrays.asList(testDto);

        when(pointRepository.findById("test_point_001")).thenReturn(Optional.empty());
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);

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

        List<MeasurementPoint> result = pointService.importPoints(dtos);

        assertEquals(1, result.size());
        verify(pointRepository).save(any(MeasurementPoint.class));
    }
}
