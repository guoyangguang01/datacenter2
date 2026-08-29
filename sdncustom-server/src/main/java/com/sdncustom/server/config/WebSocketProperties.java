package com.sdncustom.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sdncustom.websocket")
public class WebSocketProperties {

    /** 待推送批次队列容量，超出后丢弃最旧批次（保最新数据） */
    private int pushQueueCapacity = 200;

    /** 单会话待发消息队列容量；写阻塞且队列溢出时判定为慢客户端并断开 */
    private int sessionSendQueueCapacity = 512;
}
