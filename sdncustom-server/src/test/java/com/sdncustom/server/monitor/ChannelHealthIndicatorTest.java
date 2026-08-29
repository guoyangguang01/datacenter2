package com.sdncustom.server.monitor;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ChannelHealthIndicator 测试")
class ChannelHealthIndicatorTest {

    private Channel channel(String id, ChannelStatus status, boolean autoConnect) {
        Channel c = new Channel();
        c.setChannelId(id);
        c.setStatus(status);
        c.setAutoConnect(autoConnect);
        return c;
    }

    @Test
    @DisplayName("全部正常 -> UP")
    void upWhenHealthy() {
        ChannelRepository repo = mock(ChannelRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                channel("ch_1", ChannelStatus.CONNECTED, true),
                channel("ch_2", ChannelStatus.DISCONNECTED, false)));

        var health = new ChannelHealthIndicator(repo).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(2, health.getDetails().get("total"));
        assertEquals(1L, health.getDetails().get("connected"));
    }

    @Test
    @DisplayName("autoConnect 通道掉线 -> DOWN 并列出")
    void downWhenAutoConnectChannelLost() {
        ChannelRepository repo = mock(ChannelRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                channel("ch_down", ChannelStatus.ERROR, true),
                channel("ch_ok", ChannelStatus.CONNECTED, true)));

        var health = new ChannelHealthIndicator(repo).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(List.of("ch_down"), health.getDetails().get("autoConnectDown"));
    }

    @Test
    @DisplayName("非 autoConnect 通道断开不影响健康")
    void manualChannelDownStillUp() {
        ChannelRepository repo = mock(ChannelRepository.class);
        when(repo.findAll()).thenReturn(List.of(channel("ch_manual", ChannelStatus.DISCONNECTED, false)));

        assertEquals(Status.UP, new ChannelHealthIndicator(repo).health().getStatus());
    }
}
