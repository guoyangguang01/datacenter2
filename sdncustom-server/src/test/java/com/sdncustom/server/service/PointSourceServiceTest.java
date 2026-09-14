package com.sdncustom.server.service;

import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointSourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PointSourceService 测试")
class PointSourceServiceTest {

    @Mock
    private PointSourceRepository pointSourceRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private ChannelRepository channelRepository;

    @InjectMocks
    private PointSourceService pointSourceService;

    private PointSource source(String pointId, String channel, String address) {
        PointSource s = new PointSource();
        s.setPointId(pointId);
        s.setChannelId(channel);
        s.setAddress(address);
        return s;
    }

    private MeasurementPoint point(String id) {
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId(id);
        p.setDataType(PointDataType.FLOAT32);
        return p;
    }

    private PointSourceDTO binding(String channel, String address) {
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId(channel);
        dto.setAddress(address);
        return dto;
    }

    @Test
    @DisplayName("findPointsForChannel 返回绑定命中该通道的测点视图（地址按绑定覆盖）")
    void findPointsForChannel() {
        MeasurementPoint p = point("p1");
        PointSource binding = source("p1", "ch_b", "addr_b");
        when(pointSourceRepository.findByChannelId("ch_b")).thenReturn(List.of(binding));
        when(pointRepository.findAllById(List.of("p1"))).thenReturn(List.of(p));

        List<MeasurementPoint> result = pointSourceService.findPointsForChannel("ch_b");

        assertEquals(1, result.size());
        assertEquals("p1", result.get(0).getPointId());
        assertEquals("ch_b", result.get(0).getChannelId());
        assertEquals("addr_b", result.get(0).getAddress());
    }

    @Test
    @DisplayName("viewForBinding 保留 pointId/name/type，覆盖 channelId/address")
    void viewForBindingCopiesAndOverrides() {
        MeasurementPoint main = new MeasurementPoint();
        main.setPointId("p1");
        main.setPointName("n");
        main.setDataType(PointDataType.FLOAT32);
        MeasurementPoint view = pointSourceService.viewForBinding(main, "ch_b", "addr_b");

        assertEquals("p1", view.getPointId());
        assertEquals("ch_b", view.getChannelId());
        assertEquals("addr_b", view.getAddress());
        assertEquals(PointDataType.FLOAT32, view.getDataType());
    }

    @Test
    @DisplayName("allBindingViews 每个绑定生成一个视图")
    void allBindingViews() {
        MeasurementPoint p = point("p1");
        when(pointSourceRepository.findByPointId("p1"))
                .thenReturn(List.of(source("p1", "ch_a", "a"), source("p1", "ch_b", "b")));

        List<MeasurementPoint> views = pointSourceService.allBindingViews(p);

        assertEquals(2, views.size());
        assertEquals("ch_a", views.get(0).getChannelId());
        assertEquals("ch_b", views.get(1).getChannelId());
    }

    @Test
    @DisplayName("bindingChannelIds 从绑定表取全部通道")
    void bindingChannelIds() {
        when(pointSourceRepository.findByPointId("p1"))
                .thenReturn(List.of(source("p1", "ch_a", "a"), source("p1", "ch_b", "b")));

        Set<String> channels = pointSourceService.bindingChannelIds("p1");

        assertEquals(Set.of("ch_a", "ch_b"), channels);
    }

    @Test
    @DisplayName("validateBindings 拒绝空/缺失绑定")
    void validateBindingsRejectsEmpty() {
        assertThrows(BusinessException.class, () -> pointSourceService.validateBindings(List.of(), "default"));
        assertThrows(BusinessException.class, () -> pointSourceService.validateBindings(null, "default"));
    }

    @Test
    @DisplayName("validateBindings 拒绝重复通道")
    void validateBindingsRejectsDuplicateChannels() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        ch.setBusinessId("default");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));

        assertThrows(BusinessException.class,
                () -> pointSourceService.validateBindings(
                        List.of(binding("ch_b", "x"), binding("ch_b", "y")), "default"));
    }

    @Test
    @DisplayName("validateBindings 拒绝不存在通道")
    void validateBindingsRejectsMissingChannel() {
        when(channelRepository.findById("ch_zzz")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> pointSourceService.validateBindings(List.of(binding("ch_zzz", "x")), "default"));
    }

    @Test
    @DisplayName("validateBindings 拒绝跨业务通道（测点不跨业务共享）")
    void validateBindingsRejectsCrossBusinessChannel() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        ch.setBusinessId("biz_a");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> pointSourceService.validateBindings(List.of(binding("ch_b", "x")), "default"));
        assertTrue(ex.getMessage().contains("不属于当前业务"));
    }

    @Test
    @DisplayName("addBinding 校验通道存在、同业务且未重复，成功保存")
    void addBinding() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        ch.setBusinessId("default");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));
        when(pointSourceRepository.findByPointId("p1")).thenReturn(List.of(source("p1", "ch_a", "a")));
        when(pointSourceRepository.save(any(PointSource.class))).thenAnswer(inv -> inv.getArgument(0));

        PointSource saved = pointSourceService.addBinding("p1", "ch_b", "addr_b", "default");

        assertEquals("p1", saved.getPointId());
        assertEquals("ch_b", saved.getChannelId());
        verify(pointSourceRepository).save(any(PointSource.class));
    }

    @Test
    @DisplayName("addBinding 拒绝已绑定通道")
    void addBindingRejectsDuplicate() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        ch.setBusinessId("default");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));
        when(pointSourceRepository.findByPointId("p1")).thenReturn(List.of(source("p1", "ch_b", "x")));

        assertThrows(BusinessException.class, () -> pointSourceService.addBinding("p1", "ch_b", "y", "default"));
    }

    @Test
    @DisplayName("addBinding 拒绝跨业务通道")
    void addBindingRejectsCrossBusinessChannel() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        ch.setBusinessId("biz_a");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> pointSourceService.addBinding("p1", "ch_b", "y", "default"));
        assertTrue(ex.getMessage().contains("不属于当前业务"));
        verify(pointSourceRepository, never()).save(any());
    }
}
