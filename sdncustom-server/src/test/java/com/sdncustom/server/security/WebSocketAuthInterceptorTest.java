package com.sdncustom.server.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 握手层二次校验：过滤器链没覆盖 WebSocket upgrade 时，这里是最后一道闸。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketAuthInterceptor 测试")
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private WebSocketAuthInterceptor interceptor() {
        return new WebSocketAuthInterceptor(jwtService);
    }

    private void withUri(String uri) {
        when(request.getURI()).thenReturn(URI.create(uri));
    }

    @Test
    @DisplayName("合法 token：放行并把 username 写进握手属性")
    void acceptsValidToken() {
        withUri("ws://localhost:8080/ws/data?token=valid.jwt.here");
        when(jwtService.isTokenValid("valid.jwt.here")).thenReturn(true);
        when(jwtService.extractUsername("valid.jwt.here")).thenReturn("admin");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor().beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(accepted);
        assertEquals("admin", attributes.get("username"));
        verify(response, never()).setStatusCode(any());
    }

    @Test
    @DisplayName("token 非法：拒绝并置 401")
    void rejectsInvalidToken() {
        withUri("ws://localhost:8080/ws/data?token=bad.token");
        when(jwtService.isTokenValid("bad.token")).thenReturn(false);

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor().beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(accepted);
        assertTrue(attributes.isEmpty(), "被拒的握手不应留下认证属性");
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("没有 query：拒绝（视为缺失 token）")
    void rejectsMissingQuery() {
        withUri("ws://localhost:8080/ws/data");

        assertFalse(interceptor().beforeHandshake(request, response, wsHandler, new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("query 里没有 token 参数：拒绝")
    void rejectsMissingTokenParam() {
        withUri("ws://localhost:8080/ws/data?foo=1&bar=2");

        assertFalse(interceptor().beforeHandshake(request, response, wsHandler, new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("token 不在首位也能取到（参数顺序无关）")
    void extractsTokenFromMiddleOfQuery() {
        withUri("ws://localhost:8080/ws/data?foo=1&token=abc.def.ghi&x=2");
        when(jwtService.isTokenValid("abc.def.ghi")).thenReturn(true);
        when(jwtService.extractUsername("abc.def.ghi")).thenReturn("operator");

        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor().beforeHandshake(request, response, wsHandler, attributes));
        assertEquals("operator", attributes.get("username"));
    }

    @Test
    @DisplayName("空值 token 参数（token=）：拒绝而不是空串过闸")
    void rejectsEmptyTokenValue() {
        withUri("ws://localhost:8080/ws/data?token=");
        // 空串也会进 isTokenValid——由 JwtService 判非法；这里只保证不放行
        when(jwtService.isTokenValid("")).thenReturn(false);

        assertFalse(interceptor().beforeHandshake(request, response, wsHandler, new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("afterHandshake 是 no-op：不抛异常")
    void afterHandshakeIsNoOp() {
        assertDoesNotThrow(() ->
                interceptor().afterHandshake(request, response, wsHandler, null));
    }

    @Test
    @DisplayName("拒绝时 status 恰好被设置一次（不会重复写响应）")
    void setsUnauthorizedExactlyOnce() {
        withUri("ws://localhost:8080/ws/data");
        interceptor().beforeHandshake(request, response, wsHandler, new HashMap<>());

        ArgumentCaptor<HttpStatus> captor = ArgumentCaptor.forClass(HttpStatus.class);
        verify(response, times(1)).setStatusCode(captor.capture());
        assertEquals(HttpStatus.UNAUTHORIZED, captor.getValue());
    }
}
