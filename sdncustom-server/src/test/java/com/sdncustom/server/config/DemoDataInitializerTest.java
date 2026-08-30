package com.sdncustom.server.config;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.service.BusinessSystemService;
import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.PointService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DemoDataInitializer 示例数据播种测试")
class DemoDataInitializerTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private BusinessSystemService businessSystemService;

    @Mock
    private ChannelService channelService;

    @Mock
    private PointService pointService;

    @InjectMocks
    private DemoDataInitializer initializer;

    @Test
    @DisplayName("空库播种：2 业务 / 4 通道 / 6 测点，归属与同业务绑定一致，全部不自动连接")
    void seedsEmptyDatabase() {
        when(channelRepository.count()).thenReturn(0L);
        when(pointRepository.count()).thenReturn(0L);

        initializer.run();

        ArgumentCaptor<BusinessSystemDTO> bizCaptor = ArgumentCaptor.forClass(BusinessSystemDTO.class);
        verify(businessSystemService, times(2)).create(bizCaptor.capture());
        assertEquals(Set.of("factory_1", "factory_2"),
                bizCaptor.getAllValues().stream().map(BusinessSystemDTO::getBusinessId).collect(Collectors.toSet()));

        ArgumentCaptor<ChannelDTO> chCaptor = ArgumentCaptor.forClass(ChannelDTO.class);
        verify(channelService, times(4)).create(chCaptor.capture());
        for (ChannelDTO ch : chCaptor.getAllValues()) {
            assertFalse(ch.isAutoConnect(), "示例通道不应自动连接");
            assertTrue(Set.of("factory_1", "factory_2").contains(ch.getBusinessId()));
        }

        ArgumentCaptor<MeasurementPointDTO> ptCaptor = ArgumentCaptor.forClass(MeasurementPointDTO.class);
        verify(pointService, times(6)).create(ptCaptor.capture());
        // 测点绑定必须与测点同业务（隔离不变量）
        java.util.Map<String, String> channelBiz = chCaptor.getAllValues().stream()
                .collect(Collectors.toMap(ChannelDTO::getChannelId, ChannelDTO::getBusinessId));
        for (MeasurementPointDTO pt : ptCaptor.getAllValues()) {
            assertNotNull(pt.getBusinessId());
            pt.getBindings().forEach(b ->
                    assertEquals(pt.getBusinessId(), channelBiz.get(b.getChannelId()),
                            "测点 " + pt.getPointId() + " 的绑定通道跨业务"));
        }
    }

    @Test
    @DisplayName("已有通道时跳过播种")
    void skipsWhenChannelsExist() {
        when(channelRepository.count()).thenReturn(3L);

        initializer.run();

        verifyNoInteractions(businessSystemService, channelService, pointService);
        verify(pointRepository, never()).count();
    }

    @Test
    @DisplayName("已有测点时跳过播种")
    void skipsWhenPointsExist() {
        when(channelRepository.count()).thenReturn(0L);
        when(pointRepository.count()).thenReturn(5L);

        initializer.run();

        verifyNoInteractions(businessSystemService, channelService, pointService);
    }
}
