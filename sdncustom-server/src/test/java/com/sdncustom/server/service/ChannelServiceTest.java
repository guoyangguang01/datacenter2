package com.sdncustom.server.service;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * ChannelService 单元测试（适配器经 mock ProtocolRegistry 提供，不做真实网络 IO）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelService 测试")
class ChannelServiceTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private PointValueCacheRepository pointValueCache;

    @Mock
    private ProtocolRegistry protocolRegistry;

    @Mock
    private ChangeGate changeGate;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private PointBindingRegistry pointBindingRegistry;

    @Mock
    private ProtocolAdapter adapter;

    @InjectMocks
    private ChannelService channelService;

    private Channel testChannel;
    private ChannelDTO testDto;

    @BeforeEach
    void setUp() {
        testChannel = new Channel();
        testChannel.setChannelId("ch_001");
        testChannel.setChannelName("测试通道");
        testChannel.setProtocolType(ProtocolType.CUSTOM_TCP);
        testChannel.setDirection(ChannelDirection.READ_WRITE);
        testChannel.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");
        testChannel.setAutoConnect(false);
        testChannel.setStatus(ChannelStatus.DISCONNECTED);

        testDto = new ChannelDTO();
        testDto.setChannelId("ch_001");
        testDto.setChannelName("测试通道");
        testDto.setProtocolType(ProtocolType.CUSTOM_TCP);
        testDto.setDirection(ChannelDirection.READ_WRITE);
        testDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");
        testDto.setAutoConnect(false);
    }

    @Test
    @DisplayName("查询所有通道")
    void findAll() {
        when(channelRepository.findAll()).thenReturn(Arrays.asList(testChannel));

        List<Channel> result = channelService.findAll();

        assertEquals(1, result.size());
        assertEquals("ch_001", result.get(0).getChannelId());
        verify(channelRepository).findAll();
    }

    @Test
    @DisplayName("根据ID查询通道")
    void findById() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));

        Channel result = channelService.findById("ch_001");

        assertNotNull(result);
        assertEquals("ch_001", result.getChannelId());
    }

    @Test
    @DisplayName("根据ID查询通道 - 不存在")
    void findByIdNotFound() {
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> channelService.findById("nonexistent"));
    }

    @Test
    @DisplayName("创建通道")
    void create() {
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        Channel result = channelService.create(testDto);

        assertNotNull(result);
        assertEquals("ch_001", result.getChannelId());
        assertEquals(ChannelStatus.DISCONNECTED, result.getStatus());
        verify(channelRepository).save(any(Channel.class));
    }

    @Test
    @DisplayName("更新通道 - 连接参数未变不动运行态")
    void update() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        testDto.setChannelName("更新后的名称");
        Channel result = channelService.update("ch_001", testDto);

        assertNotNull(result);
        verify(channelRepository).save(any(Channel.class));
        verify(protocolRegistry, never()).release(any());
    }

    @Test
    @DisplayName("更新通道 - connectionConfig 变化时释放旧适配器实例")
    void updateConnectionConfigReleasesAdapter() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        testChannel.setAutoConnect(false);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(List.of());

        testDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9999}");
        channelService.update("ch_001", testDto);

        verify(protocolRegistry).release("ch_001");
        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository, atLeastOnce()).save(captor.capture());
        boolean disconnectedSaved = captor.getAllValues().stream()
                .anyMatch(c -> c.getStatus() == ChannelStatus.DISCONNECTED);
        assertTrue(disconnectedSaved);
        // autoConnect=false 不自动重连
        verify(protocolRegistry, never()).getOrCreate(any());
    }

    @Test
    @DisplayName("更新通道 - 配置变化且 autoConnect 时以新配置重连")
    void updateAutoReconnect() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        testChannel.setAutoConnect(true);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(List.of());
        when(protocolRegistry.getOrCreate(any(Channel.class))).thenReturn(adapter);

        testDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9999}");
        testDto.setAutoConnect(true);
        channelService.update("ch_001", testDto);

        verify(protocolRegistry).release("ch_001");
        verify(adapter).connect(any(Channel.class));
    }

    @Test
    @DisplayName("更新通道 - 自动重连失败不回滚配置且不抛异常")
    void updateAutoReconnectFailureKeepsConfig() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        testChannel.setAutoConnect(true);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(List.of());
        when(protocolRegistry.getOrCreate(any(Channel.class))).thenReturn(adapter);
        doThrow(new RuntimeException("Connection refused")).when(adapter).connect(any(Channel.class));

        testDto.setConnectionConfig("{\"host\":\"localhost\",\"port\":9999}");
        testDto.setAutoConnect(true);
        assertDoesNotThrow(() -> channelService.update("ch_001", testDto));

        verify(protocolRegistry).release("ch_001");
        verify(protocolRegistry).remove("ch_001");
        assertEquals(ChannelStatus.ERROR, testChannel.getStatus());
        // 新配置保留（未因异常回滚）
        assertEquals("{\"host\":\"localhost\",\"port\":9999}", testChannel.getConnectionConfig());
    }

    @Test
    @DisplayName("删除通道 - 未连接状态")
    void deleteDisconnected() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(pointRepository.findByChannelId("ch_001")).thenReturn(Arrays.asList());
        doNothing().when(pointRepository).deleteByChannelId("ch_001");
        doNothing().when(channelRepository).deleteById("ch_001");

        channelService.delete("ch_001");

        verify(channelRepository).deleteById("ch_001");
    }

    @Test
    @DisplayName("删除通道 - 已连接状态时释放适配器")
    void deleteConnected() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(pointRepository.findByChannelId("ch_001")).thenReturn(Arrays.asList());
        doNothing().when(pointRepository).deleteByChannelId("ch_001");
        doNothing().when(channelRepository).deleteById("ch_001");

        channelService.delete("ch_001");

        verify(protocolRegistry).release("ch_001");
        verify(channelRepository).deleteById("ch_001");
    }

    @Test
    @DisplayName("连接通道成功 - 触发 onConnected 钩子并置 CONNECTED")
    void connectSuccess() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(List.of());
        when(protocolRegistry.getOrCreate(testChannel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(false);

        channelService.connect("ch_001");

        verify(adapter).connect(testChannel);
        verify(adapter).onConnected(List.of());
        assertEquals(ChannelStatus.CONNECTED, testChannel.getStatus());
    }

    @Test
    @DisplayName("连接通道 - 失败进入 ERROR 状态并销毁半成品实例")
    void connectFailure() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(protocolRegistry.getOrCreate(testChannel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(false);
        doThrow(new RuntimeException("Connection refused")).when(adapter).connect(testChannel);

        assertThrows(RuntimeException.class, () -> channelService.connect("ch_001"));

        verify(protocolRegistry).remove("ch_001");
        assertEquals(ChannelStatus.ERROR, testChannel.getStatus());
    }

    @Test
    @DisplayName("连接通道 - 运行时已连接时仅对齐 DB 状态（幂等自愈）")
    void connectAlreadyConnectedAlignsState() {
        testChannel.setStatus(ChannelStatus.DISCONNECTED);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(protocolRegistry.getOrCreate(testChannel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);

        channelService.connect("ch_001");

        verify(adapter, never()).connect(any());
        assertEquals(ChannelStatus.CONNECTED, testChannel.getStatus());
    }

    @Test
    @DisplayName("断开通道 - 释放实例并收敛到 DISCONNECTED")
    void disconnect() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(Arrays.asList());
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        channelService.disconnect("ch_001");

        verify(protocolRegistry).release("ch_001");
        assertEquals(ChannelStatus.DISCONNECTED, testChannel.getStatus());
    }

    @Test
    @DisplayName("syncDisconnected - 运行时断开时对齐 DB 状态并移除实例")
    void syncDisconnectedAlignsState() {
        testChannel.setStatus(ChannelStatus.CONNECTED);
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(Arrays.asList());
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        channelService.syncDisconnected("ch_001");

        verify(protocolRegistry).remove("ch_001");
        assertEquals(ChannelStatus.DISCONNECTED, testChannel.getStatus());
    }

    @Test
    @DisplayName("syncDisconnected - 已是 DISCONNECTED 时不做任何事")
    void syncDisconnectedNoop() {
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));

        channelService.syncDisconnected("ch_001");

        verify(protocolRegistry, never()).remove(any());
        verify(channelRepository, never()).save(any());
    }

    @Test
    @DisplayName("自动连接通道")
    void autoConnectAll() {
        testChannel.setAutoConnect(true);
        when(channelRepository.findByAutoConnect(true)).thenReturn(Arrays.asList(testChannel));
        when(channelRepository.findById("ch_001")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);
        when(pointSourceService.findPointsForChannel("ch_001")).thenReturn(List.of());
        when(protocolRegistry.getOrCreate(any())).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(false);

        channelService.autoConnectAll();

        verify(channelRepository).findByAutoConnect(true);
        verify(adapter).connect(any(Channel.class));
    }
}
