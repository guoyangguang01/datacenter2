package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.PointDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常兜底的契约测试：调用方的输入问题不该被报成 500。
 */
@DisplayName("GlobalExceptionHandler 测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("查询参数类型不合法（?direction=FOO）-> 400 且点名参数，而非 500")
    void typeMismatchIsBadRequest() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "FOO", PointDirection.class, "direction", null,
                new IllegalArgumentException("No enum constant"));

        ApiResponse<Void> response = handler.handleTypeMismatch(ex);

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("direction"), response.getMessage());
    }

    @Test
    @DisplayName("业务异常：code 与 message 原样透传")
    void businessExceptionPassesThrough() {
        ApiResponse<Void> response =
                handler.handleBusinessException(new BusinessException(404, "Channel not found: ch1"));

        assertEquals(404, response.getCode());
        assertEquals("Channel not found: ch1", response.getMessage());
    }

    @Test
    @DisplayName("请求体不是合法 JSON -> 400 提示格式问题")
    void unreadableBodyIsBadRequest() {
        ApiResponse<Void> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("JSON parse error",
                        (org.springframework.http.HttpInputMessage) null));

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("请求体格式错误"), response.getMessage());
    }

    @Test
    @DisplayName("@Valid 字段错误：拼成 `字段: 原因` 列表，多条用分号连接")
    void validationErrorsJoinFieldMessages() throws Exception {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("dto", "channelId", "channelId 不能为空"),
                new FieldError("dto", "protocolType", "protocolType 不能为空")));
        MethodArgumentNotValidException ex = validException(bindingResult);

        ApiResponse<Void> response = handler.handleValidationException(ex);

        assertEquals(400, response.getCode());
        assertEquals("channelId: channelId 不能为空; protocolType: protocolType 不能为空",
                response.getMessage());
    }

    @Test
    @DisplayName("@Valid 但取不到字段错误：给兜底文案而不是 null")
    void validationWithoutFieldErrorsUsesFallback() throws Exception {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = validException(bindingResult);

        ApiResponse<Void> response = handler.handleValidationException(ex);

        assertEquals(400, response.getCode());
        assertEquals("Validation failed", response.getMessage());
    }

    @Test
    @DisplayName("没预料到的异常 -> 500 且不泄露堆栈细节")
    void unexpectedExceptionIs500() {
        ApiResponse<Void> response = handler.handleException(new IllegalStateException("secret detail"));

        assertEquals(500, response.getCode());
        assertEquals("Internal server error", response.getMessage());
        assertFalse(response.getMessage().contains("secret detail"));
    }

    private MethodArgumentNotValidException validException(BindingResult bindingResult)
            throws NoSuchMethodException {
        Method method = GlobalExceptionHandler.class
                .getDeclaredMethod("handleBusinessException", BusinessException.class);
        MethodParameter parameter = new MethodParameter(method, -1);
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }
}
