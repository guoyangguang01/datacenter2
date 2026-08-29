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

    private MeasurementPoint point(String id, String channel, String address) {
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId(id);
        p.setChannelId(channel);
        p.setAddress(address);
        p.setDataType(PointDataType.FLOAT32);
        return p;
    }

    @Test
    @DisplayName("findPointsForChannel 返回主绑定 + 附加来源视图（地址按来源覆盖、保留 pointId）")
    void findPointsForChannelReturnsMainAndSourceViews() {
        MeasurementPoint main = point("p1", "ch_a", "addr_a");
        PointSource source = new PointSource();
        source.setPointId("p1");
        source.setChannelId("ch_b");
        source.setAddress("addr_b");
        when(pointRepository.findByChannelId("ch_b")).thenReturn(List.of());
        when(pointSourceRepository.findByChannelId("ch_b")).thenReturn(List.of(source));
        when(pointRepository.findById("p1")).thenReturn(Optional.of(main));

        List<MeasurementPoint> result = pointSourceService.findPointsForChannel("ch_b");

        assertEquals(1, result.size());
        assertEquals("p1", result.get(0).getPointId());
        assertEquals("ch_b", result.get(0).getChannelId());
        assertEquals("addr_b", result.get(0).getAddress());
    }

    @Test
    @DisplayName("findPointsForChannel 主通道点原样返回")
    void findPointsForChannelMainBindingsKeptAsIs() {
        MeasurementPoint main = point("p1", "ch_a", "addr_a");
        when(pointRepository.findByChannelId("ch_a")).thenReturn(List.of(main));
        when(pointSourceRepository.findByChannelId("ch_a")).thenReturn(List.of());

        List<MeasurementPoint> result = pointSourceService.findPointsForChannel("ch_a");

        assertEquals(1, result.size());
        assertSame(main, result.get(0));
        assertEquals("addr_a", result.get(0).getAddress());
    }

    @Test
    @DisplayName("viewForBinding 保留 pointId/name/type，覆盖 channelId/address")
    void viewForBindingCopiesAndOverrides() {
        MeasurementPoint main = point("p1", "ch_a", "addr_a");
        MeasurementPoint view = pointSourceService.viewForBinding(main, "ch_b", "addr_b");

        assertEquals("p1", view.getPointId());
        assertEquals("ch_b", view.getChannelId());
        assertEquals("addr_b", view.getAddress());
        assertEquals(PointDataType.FLOAT32, view.getDataType());
    }

    @Test
    @DisplayName("allBindingViews 返回主实体本体 + 各来源视图")
    void allBindingViewsReturnsMainAndSources() {
        MeasurementPoint main = point("p1", "ch_a", "addr_a");
        PointSource source = new PointSource();
        source.setPointId("p1");
        source.setChannelId("ch_b");
        source.setAddress("addr_b");
        when(pointSourceRepository.findByPointId("p1")).thenReturn(List.of(source));

        List<MeasurementPoint> views = pointSourceService.allBindingViews(main);

        assertEquals(2, views.size());
        assertSame(main, views.get(0)); // 主绑定返回实体本体
        assertEquals("ch_b", views.get(1).getChannelId());
        assertEquals("addr_b", views.get(1).getAddress());
    }

    @Test
    @DisplayName("bindingChannelIds 返回主通道 + 来源通道")
    void bindingChannelIds() {
        PointSource source = new PointSource();
        source.setPointId("p1");
        source.setChannelId("ch_b");
        source.setAddress("addr_b");
        when(pointRepository.findById("p1")).thenReturn(Optional.of(point("p1", "ch_a", "addr_a")));
        when(pointSourceRepository.findByPointId("p1")).thenReturn(List.of(source));

        Set<String> channels = pointSourceService.bindingChannelIds("p1");

        assertEquals(Set.of("ch_a", "ch_b"), channels);
    }

    @Test
    @DisplayName("validateSources 拒绝与主通道相同的来源")
    void validateSourcesRejectsSameAsMainChannel() {
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId("ch_a");
        dto.setAddress("x");

        assertThrows(BusinessException.class, () -> pointSourceService.validateSources(List.of(dto), "ch_a"));
    }

    @Test
    @DisplayName("validateSources 拒绝重复来源通道")
    void validateSourcesRejectsDuplicateChannels() {
        Channel ch = new Channel();
        ch.setChannelId("ch_b");
        when(channelRepository.findById("ch_b")).thenReturn(Optional.of(ch));
        PointSourceDTO a = new PointSourceDTO();
        a.setChannelId("ch_b");
        a.setAddress("x");
        PointSourceDTO b = new PointSourceDTO();
        b.setChannelId("ch_b");
        b.setAddress("y");

        assertThrows(BusinessException.class, () -> pointSourceService.validateSources(List.of(a, b), "ch_a"));
    }

    @Test
    @DisplayName("validateSources 拒绝不存在的来源通道")
    void validateSourcesRejectsMissingChannel() {
        when(channelRepository.findById("ch_zzz")).thenReturn(Optional.empty());
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId("ch_zzz");
        dto.setAddress("x");

        assertThrows(BusinessException.class, () -> pointSourceService.validateSources(List.of(dto), "ch_a"));
    }
}
