package com.sdncustom.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("业务异常与资源未找到异常测试")
class BusinessExceptionTest {

    @Test
    @DisplayName("BusinessException 携带 code 与 message")
    void businessExceptionCarriesCodeAndMessage() {
        BusinessException ex = new BusinessException(400, "业务不存在: b1");
        assertEquals(400, ex.getCode());
        assertEquals("业务不存在: b1", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("ResourceNotFoundException 固定 404 并按 `资源 not found: id` 格式化")
    void resourceNotFoundIs404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Channel", "ch_9");
        assertEquals(404, ex.getCode());
        assertEquals("Channel not found: ch_9", ex.getMessage());
        // 走 GlobalExceptionHandler 的 BusinessException 分支
        assertInstanceOf(BusinessException.class, ex);
    }
}
