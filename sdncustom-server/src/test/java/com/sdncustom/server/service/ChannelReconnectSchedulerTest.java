package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 断线自动重连：退避增长、封顶、MQTT 跳过、期望集合清理。
 * 时间窗口断言直接读内部 nextAttemptAt/failures，不靠 sleep。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelReconnectScheduler 断线重连测试")
class ChannelReconnectSchedulerTest {

    @Mock
    private ChannelService channelService;

    private SimpleMeterRegistry meterRegistry;
    private ChannelReconnectScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new ChannelReconnectScheduler(channelService, meterRegistry);
        ReflectionTestUtils.setField(scheduler, "initialDelayMs", 2000L);
        ReflectionTestUtils.setField(scheduler, "maxDelayMs", 60000L);
        ReflectionTestUtils.setField(scheduler, "multiplier", 2.0);
        scheduler.registerMetrics();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> nextAttemptAt() {
        return (Map<String, Long>) ReflectionTestUtils.getField(scheduler, "nextAttemptAt");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> failures() {
        return (Map<String, Integer>) ReflectionTestUtils.getField(scheduler, "failures");
    }

    private Channel channel(String id, ChannelStatus status, ProtocolType protocol) {
        Channel ch = new Channel();
        ch.setChannelId(id);
        ch.setStatus(status);
        ch.setProtocolType(protocol);
        return ch;
    }

    @Test
    @DisplayName("期望集合为空：直接返回，不碰 ChannelService")
    void emptyDesiredSetReturnsEarly() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of());

        scheduler.reconnectDesiredChannels();

        verify(channelService, never()).connect(anyString());
        verify(channelService, never()).findByIdOrNull(anyString());
    }

    @Test
    @DisplayName("断开的非 MQTT 通道：发起重连并计入 reconnects 指标")
    void reconnectsDisconnectedChannel() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.DISCONNECTED, ProtocolType.CUSTOM_TCP));

        scheduler.reconnectDesiredChannels();

        verify(channelService).connect("ch1");
        assertEquals(1.0, meterRegistry.counter("sdncustom.channel.reconnects").count());
        // 成功后退避状态清空
        assertTrue(failures().isEmpty());
        assertTrue(nextAttemptAt().isEmpty());
    }

    @Test
    @DisplayName("连接失败：记 failures 并推迟到 initial-delay 后；窗口内不重试")
    void failureBacksOffAndSkipsWithinWindow() {
        // 把 initial 调大，保证"窗口内不重试"不受机器卡顿影响
        ReflectionTestUtils.setField(scheduler, "initialDelayMs", 60_000L);
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.DISCONNECTED, ProtocolType.CUSTOM_TCP));
        doThrow(new RuntimeException("refused")).when(channelService).connect("ch1");

        scheduler.reconnectDesiredChannels();

        verify(channelService, times(1)).connect("ch1");
        assertEquals(1, failures().get("ch1"));
        long delay = nextAttemptAt().get("ch1") - System.currentTimeMillis();
        assertTrue(delay > 55_000 && delay <= 61_000, "第一次退避应为 initial=60000ms，实际 " + delay);
        assertEquals(1.0, meterRegistry.counter("sdncustom.channel.reconnect.failures").count());

        // 窗口未到：直接再扫一次也不该发起第二次连接
        scheduler.reconnectDesiredChannels();
        verify(channelService, times(1)).connect("ch1");
    }

    @Test
    @DisplayName("连续失败：退避按 multiplier 增长（2000 → 4000），窗口过后才重试")
    void backoffGrowsOnConsecutiveFailures() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.DISCONNECTED, ProtocolType.CUSTOM_TCP));
        doThrow(new RuntimeException("refused")).when(channelService).connect("ch1");

        scheduler.reconnectDesiredChannels(); // attempt 1 → 退避 2000
        assertEquals(1, failures().get("ch1"));

        // 把窗口拨到过去，强制立刻进入第二次尝试
        nextAttemptAt().put("ch1", 0L);
        scheduler.reconnectDesiredChannels(); // attempt 2 → 退避 2000*2=4000

        assertEquals(2, failures().get("ch1"));
        long delay = nextAttemptAt().get("ch1") - System.currentTimeMillis();
        assertTrue(delay > 3_500 && delay <= 4_200, "第二次退避应为 4000ms，实际 " + delay);
        verify(channelService, times(2)).connect("ch1");
        assertEquals(2.0, meterRegistry.counter("sdncustom.channel.reconnect.failures").count());
    }

    @Test
    @DisplayName("退避封顶 max-delay：2000*2^10 远超 60000 时取 60000")
    void backoffIsCappedAtMaxDelay() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.DISCONNECTED, ProtocolType.CUSTOM_TCP));
        doThrow(new RuntimeException("refused")).when(channelService).connect("ch1");

        failures().put("ch1", 10); // 已连续失败 10 次：本次是第 11 次
        nextAttemptAt().put("ch1", 0L);
        scheduler.reconnectDesiredChannels();

        long delay = nextAttemptAt().get("ch1") - System.currentTimeMillis();
        assertTrue(delay > 55_000 && delay <= 61_000, "退避必须封顶在 60000ms，实际 " + delay);
    }

    @Test
    @DisplayName("重连成功后清空退避状态，下次失败从头计数")
    void successResetsBackoffState() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.DISCONNECTED, ProtocolType.CUSTOM_TCP));
        // 先失败一次留下退避状态
        doThrow(new RuntimeException("boom")).when(channelService).connect("ch1");
        scheduler.reconnectDesiredChannels();
        assertEquals(1, failures().get("ch1"));

        // 再连成功
        doNothing().when(channelService).connect("ch1");
        nextAttemptAt().put("ch1", 0L);
        scheduler.reconnectDesiredChannels();

        assertTrue(failures().isEmpty(), "成功后失败计数应清空");
        assertTrue(nextAttemptAt().isEmpty(), "成功后重试窗口应清空");
    }

    @Test
    @DisplayName("MQTT 通道跳过（Paho 自带重连），不发起连接")
    void skipsMqttChannels() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch_mqtt"));
        when(channelService.findByIdOrNull("ch_mqtt"))
                .thenReturn(channel("ch_mqtt", ChannelStatus.DISCONNECTED, ProtocolType.MQTT));

        scheduler.reconnectDesiredChannels();

        verify(channelService, never()).connect(anyString());
    }

    @Test
    @DisplayName("已是 CONNECTED 的通道：清理退避状态但不重复连接")
    void connectedChannelIsResetNotReconnected() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch1"));
        when(channelService.findByIdOrNull("ch1"))
                .thenReturn(channel("ch1", ChannelStatus.CONNECTED, ProtocolType.CUSTOM_TCP));
        failures().put("ch1", 3);
        nextAttemptAt().put("ch1", 0L);

        scheduler.reconnectDesiredChannels();

        verify(channelService, never()).connect(anyString());
        assertTrue(failures().isEmpty());
        assertTrue(nextAttemptAt().isEmpty());
    }

    @Test
    @DisplayName("通道已被删除（findByIdOrNull 返回 null）：退避状态清掉")
    void deletedChannelIsReset() {
        when(channelService.desiredConnectedIds()).thenReturn(Set.of("ch_gone"));
        when(channelService.findByIdOrNull("ch_gone")).thenReturn(null);
        failures().put("ch_gone", 2);
        nextAttemptAt().put("ch_gone", 0L);

        scheduler.reconnectDesiredChannels();

        verify(channelService, never()).connect(anyString());
        assertTrue(failures().isEmpty());
        assertTrue(nextAttemptAt().isEmpty());
    }

    @Test
    @DisplayName("通道被删除/主动断开后：不在期望集合里的退避状态被 retainAll 清理")
    void staleBackoffStateIsPruned() {
        failures().put("ch_old", 5);
        nextAttemptAt().put("ch_old", 0L);
        when(channelService.desiredConnectedIds()).thenReturn(Set.of());

        scheduler.reconnectDesiredChannels();

        assertTrue(failures().isEmpty(), "已离开期望集合的通道不应残留退避状态");
        assertTrue(nextAttemptAt().isEmpty());
    }
}
