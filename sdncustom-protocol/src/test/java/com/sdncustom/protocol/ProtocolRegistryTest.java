package com.sdncustom.protocol;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ProtocolType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ProtocolRegistry 实例生命周期测试")
class ProtocolRegistryTest {

    private ProtocolAdapter adapter1;
    private ProtocolAdapter adapter2;
    private ProtocolRegistry registry;

    private Channel channel(String id, ProtocolType type) {
        Channel c = new Channel();
        c.setChannelId(id);
        c.setProtocolType(type);
        return c;
    }

    @BeforeEach
    void setUp() {
        adapter1 = mock(ProtocolAdapter.class);
        adapter2 = mock(ProtocolAdapter.class);

        ProtocolAdapterFactory tcpFactory = new ProtocolAdapterFactory() {
            private int n;
            @Override public ProtocolType protocolType() { return ProtocolType.CUSTOM_TCP; }
            @Override public ProtocolAdapter create() { return ++n == 1 ? adapter1 : adapter2; }
        };
        this.registry = new ProtocolRegistry(List.of(tcpFactory));
    }

    @Test
    @DisplayName("同一通道多次获取返回同一实例")
    void sameChannelReturnsSameInstance() {
        Channel c = channel("ch_1", ProtocolType.CUSTOM_TCP);
        assertSame(registry.getOrCreate(c), registry.getOrCreate(c));
    }

    @Test
    @DisplayName("不同通道获得不同实例")
    void differentChannelsGetDifferentInstances() {
        assertNotSame(
                registry.getOrCreate(channel("ch_1", ProtocolType.CUSTOM_TCP)),
                registry.getOrCreate(channel("ch_2", ProtocolType.CUSTOM_TCP)));
    }

    @Test
    @DisplayName("release 断开并移除实例，下次获取新实例")
    void releaseDisconnectsAndRemoves() {
        Channel c = channel("ch_1", ProtocolType.CUSTOM_TCP);
        registry.getOrCreate(c);

        registry.release("ch_1");

        verify(adapter1).disconnect();
        assertTrue(registry.get("ch_1").isEmpty());
        assertSame(adapter2, registry.getOrCreate(c));
    }

    @Test
    @DisplayName("release 对不存在通道是 no-op，断开异常不阻断")
    void releaseTolerant() {
        registry.release("nonexistent");

        registry.getOrCreate(channel("ch_1", ProtocolType.CUSTOM_TCP));
        doThrow(new RuntimeException("boom")).when(adapter1).disconnect();
        registry.release("ch_1");
        assertTrue(registry.get("ch_1").isEmpty());
    }

    @Test
    @DisplayName("remove 只移除不断开")
    void removeWithoutDisconnect() {
        registry.getOrCreate(channel("ch_1", ProtocolType.CUSTOM_TCP));
        registry.remove("ch_1");
        assertTrue(registry.get("ch_1").isEmpty());
        verify(adapter1, never()).disconnect();
    }

    @Test
    @DisplayName("无对应工厂的协议类型抛出异常")
    void unknownProtocolThrows() {
        assertThrows(IllegalStateException.class,
                () -> registry.getOrCreate(channel("ch_1", ProtocolType.MQTT)));
    }
}
