package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 存活来源过滤：通道断开时 markPointsCommLost 会推 COMM_LOST，
 * 若迟到的 GOOD 值随后再落库/推送，客户端会被刷回正常——按 DB 状态过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveSourceChecker 存活来源过滤 测试")
class LiveSourceCheckerTest {

    @Mock
    private ChannelRepository channelRepository;

    private LiveSourceChecker checker;

    @BeforeEach
    void setUp() {
        checker = new LiveSourceChecker(channelRepository);
    }

    private static PointValue value(String pointId, String sourceChannelId) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setSourceChannelId(sourceChannelId);
        return pv;
    }

    private static Channel connected(String channelId) {
        Channel ch = new Channel();
        ch.setChannelId(channelId);
        ch.setStatus(ChannelStatus.CONNECTED);
        return ch;
    }

    @Test
    @DisplayName("来源通道已断开的值被丢弃")
    void dropsValuesFromDisconnectedChannels() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED))
                .thenReturn(List.of(connected("ch_live")));

        List<PointValue> result = checker.onlyLive(
                List.of(value("p_live", "ch_live"), value("p_dead", "ch_dead")));

        assertEquals(List.of("p_live"), result.stream().map(PointValue::getPointId).toList());
    }

    @Test
    @DisplayName("sourceChannelId 为空的值保留（历史数据可能没有来源通道）")
    void keepsValuesWithoutSourceChannel() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of());

        List<PointValue> result = checker.onlyLive(List.of(value("p_null", null)));

        assertEquals(1, result.size());
    }
}
