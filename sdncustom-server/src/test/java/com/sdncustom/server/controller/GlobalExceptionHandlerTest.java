package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.model.enums.PointDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.*;

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
}
