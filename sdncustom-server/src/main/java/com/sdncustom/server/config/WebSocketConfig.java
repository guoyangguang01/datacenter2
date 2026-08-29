package com.sdncustom.server.config;

import com.sdncustom.server.security.WebSocketAuthInterceptor;
import com.sdncustom.server.websocket.DataWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WebSocketProperties.class)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DataWebSocketHandler dataWebSocketHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dataWebSocketHandler, "/ws/data")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*");
    }
}
