package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MeasurementPointDTO {

    @NotBlank(message = "pointId 不能为空")
    private String pointId;

    @NotBlank(message = "pointName 不能为空")
    private String pointName;

    @NotBlank(message = "channelId 不能为空")
    private String channelId;

    @NotBlank(message = "address 不能为空")
    private String address;

    @NotNull(message = "dataType 不能为空")
    private PointDataType dataType;

    private String unit;

    private double scaleFactor = 1.0;

    private double offset = 0.0;

    private double deadBand = 0.0;

    private boolean writable = false;
}
