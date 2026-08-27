package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private PointService pointService;

    private MeasurementPoint testPoint;
    private MeasurementPointDTO testDto;

    @BeforeEach
    void setUp() {
        testPoint = new MeasurementPoint();
        testPoint.setPointId("test_point_001");
        testPoint.setPointName("测试测点");
        testPoint.setChannelId("ch_001");
        testPoint.setAddress("40001");
        testPoint.setDataType(PointDataType.INT16);
        testPoint.setUnit("°C");
        testPoint.setWritable(false);

        testDto = new MeasurementPointDTO();
        testDto.setPointId("test_point_001");
        testDto.setPointName("测试测点");
        testDto.setChannelId("ch_001");
        testDto.setAddress("40001");
        testDto.setDataType(PointDataType.INT16);
        testDto.setUnit("°C");
        testDto.setWritable(false);
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
        assertEquals("ch_001", result.get(0).getChannelId());
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
        when(channelService.findById("ch_001")).thenReturn(any());
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);

        MeasurementPoint result = pointService.create(testDto);

        assertNotNull(result);
        assertEquals("test_point_001", result.getPointId());
        verify(pointRepository).save(any(MeasurementPoint.class));
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
        doNothing().when(pointRepository).deleteById("test_point_001");
        doNothing().when(pointValueCache).delete("test_point_001");

        pointService.delete("test_point_001");

        verify(pointRepository).deleteById("test_point_001");
        verify(pointValueCache).delete("test_point_001");
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
    @DisplayName("更新测点值")
    void updateValue() {
        PointValue newValue = new PointValue();
        newValue.setPointId("test_point_001");
        newValue.setValue(30.5);
        newValue.setQuality(PointQuality.GOOD);

        when(pointValueCache.save(any(PointValue.class))).thenReturn(newValue);

        pointService.updateValue(newValue);

        verify(pointValueCache).save(any(PointValue.class));
    }

    @Test
    @DisplayName("批量导入测点")
    void importPoints() {
        List<MeasurementPointDTO> dtos = Arrays.asList(testDto);

        when(pointRepository.findById("test_point_001")).thenReturn(Optional.empty());
        when(channelService.findById("ch_001")).thenReturn(any());
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
