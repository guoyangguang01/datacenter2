package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelService 通道配置导入 测试")
class ChannelServiceImportConfigTest {

    private static final String REAL_CONFIG = "{\"host\":\"real\",\"password\":\"secret\"}";

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private PointBindingRegistry pointBindingRegistry;

    @Mock
    private PointValueCacheRepository pointValueCache;

    @Mock
    private ProtocolRegistry protocolRegistry;

    @Mock
    private ChangeGate changeGate;

    @Mock
    private BusinessSystemService businessSystemService;

    @Mock
    private DistributionService distributionService;

    @InjectMocks
    private ChannelService channelService;

    private ChannelDTO dto(String channelId, String connectionConfig) {
        ChannelDTO dto = new ChannelDTO();
        dto.setChannelId(channelId);
        dto.setBusinessId("biz");
        dto.setChannelName("通道" + channelId);
        dto.setProtocolType(ProtocolType.CUSTOM_TCP);
        dto.setDirection(ChannelDirection.READ_WRITE);
        dto.setConnectionConfig(connectionConfig);
        return dto;
    }

    private Channel stored(String channelId, String connectionConfig) {
        Channel channel = new Channel();
        channel.setChannelId(channelId);
        channel.setBusinessId("biz");
        channel.setChannelName("通道" + channelId);
        channel.setProtocolType(ProtocolType.CUSTOM_TCP);
        channel.setDirection(ChannelDirection.READ_WRITE);
        channel.setConnectionConfig(connectionConfig);
        channel.setStatus(ChannelStatus.DISCONNECTED);
        return channel;
    }

    @Test
    @DisplayName("省略 connectionConfig 时保留库中原值（重复导入不毁凭据）")
    void preservesStoredConfigWhenOmitted() {
        Channel existing = stored("ch_1", REAL_CONFIG);
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(existing));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        channelService.importConfigs(List.of(dto("ch_1", null)));

        assertEquals(REAL_CONFIG, existing.getConnectionConfig());
    }

    @Test
    @DisplayName("拒绝脱敏占位符 ******：它不是真实凭据，且不触碰任何仓储")
    void rejectsMaskedPlaceholder() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> channelService.importConfigs(List.of(dto("ch_1", "{\"password\":\"******\"}"))));

        assertTrue(ex.getMessage().contains("脱敏占位符"), ex.getMessage());
        verifyNoInteractions(channelRepository);
    }

    @Test
    @DisplayName("通道不存在时创建，并兜底建出引用的业务")
    void createsWhenAbsent() {
        when(channelRepository.findById("ch_9")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = channelService.importConfigs(List.of(dto("ch_9", "{\"host\":\"h\"}")));

        assertEquals(1, count);
        verify(businessSystemService).ensureExistsForImport("biz");
        verify(channelRepository).save(any(Channel.class));
    }
}
