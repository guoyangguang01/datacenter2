package com.sdncustom.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BusinessSystemDTO {

    @NotBlank(message = "businessId 不能为空")
    private String businessId;

    @NotBlank(message = "businessName 不能为空")
    private String businessName;

    private String description;
}
