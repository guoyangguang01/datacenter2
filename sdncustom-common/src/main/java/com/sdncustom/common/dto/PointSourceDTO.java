package com.sdncustom.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PointSourceDTO {

    @NotBlank(message = "来源通道 channelId 不能为空")
    private String channelId;

    @NotBlank(message = "来源通道 address 不能为空")
    private String address;
}
