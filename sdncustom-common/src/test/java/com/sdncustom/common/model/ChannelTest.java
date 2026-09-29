package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Channel 实体测试")
class ChannelTest {

    @Test
    @DisplayName("默认状态为 DISCONNECTED、autoConnect 为 true")
    void defaults() {
        Channel ch = new Channel();
        assertEquals(ChannelStatus.DISCONNECTED, ch.getStatus());
        assertTrue(ch.isAutoConnect());
    }

    @Test
    @DisplayName("字段存取往返")
    void gettersAndSetters() {
        Channel ch = new Channel();
        ch.setChannelId("ch_1");
        ch.setBusinessId("biz_1");
        ch.setChannelName("一号通道");
        ch.setCode("FZXT");
        ch.setProtocolType(ProtocolType.MODBUS_TCP);
        ch.setConnectionConfig("{\"host\":\"127.0.0.1\",\"port\":502}");
        ch.setStatus(ChannelStatus.CONNECTED);
        ch.setAutoConnect(false);

        assertEquals("ch_1", ch.getChannelId());
        assertEquals("biz_1", ch.getBusinessId());
        assertEquals("一号通道", ch.getChannelName());
        assertEquals("FZXT", ch.getCode());
        assertEquals(ProtocolType.MODBUS_TCP, ch.getProtocolType());
        assertEquals("{\"host\":\"127.0.0.1\",\"port\":502}", ch.getConnectionConfig());
        assertEquals(ChannelStatus.CONNECTED, ch.getStatus());
        assertFalse(ch.isAutoConnect());
    }
}
