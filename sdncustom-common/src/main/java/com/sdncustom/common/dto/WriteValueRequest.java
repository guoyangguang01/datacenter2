package com.sdncustom.common.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WriteValueRequest {

    @NotNull(message = "value 不能为空")
    private Object value;
}
