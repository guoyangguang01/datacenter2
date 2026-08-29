package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointDataType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

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

    private boolean writable = false;

    private Double deadband;

    /** 附加来源（同一物理量可从多个通道采集/下发）；null=不修改现有来源，空列表=清空 */
    @Valid
    private List<PointSourceDTO> additionalSources;
}
