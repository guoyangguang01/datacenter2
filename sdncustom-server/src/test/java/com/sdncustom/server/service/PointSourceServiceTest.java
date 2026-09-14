package com.sdncustom.server.service;

import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointSource;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
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

    /**
     * 全平台采集都压在这两行上：极性写反（返回 INPUT）或 viewForBinding 漏拷 direction，
     * 采集就整体停下，而两者的失败模式都是"没有数据"而不是报错——必须有真实行的钉子。
     */
    @Test
    @DisplayName("findOutputPointsForChannel：同一通道同时绑定 OUTPUT 与 INPUT 时只返回 OUTPUT，且视图带方向")
    void findOutputPointsForChannelKeepsOnlyOutputWithDirection() {
        MeasurementPoint output = point("out_1");
        output.setDirection(PointDirection.OUTPUT);
        MeasurementPoint input = point("in_1");
        input.setDirection(PointDirection.INPUT);
        when(pointSourceRepository.findByChannelId("ch_b"))
                .thenReturn(List.of(source("out_1", "ch_b", "a_out"), source("in_1", "ch_b", "a_in")));
        when(pointRepository.findAllById(List.of("out_1", "in_1"))).thenReturn(List.of(output, input));

        // 不分方向的路径两者都命中——差别只在下游的过滤，这正是要钉住的地方
        assertEquals(2, pointSourceService.findPointsForChannel("ch_b").size());

        List<MeasurementPoint> result = pointSourceService.findOutputPointsForChannel("ch_b");

        assertEquals(List.of("out_1"), result.stream().map(MeasurementPoint::getPointId).toList());
        assertEquals(PointDirection.OUTPUT, result.get(0).getDirection());
        assertEquals("a_out", result.get(0).getAddress());
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
