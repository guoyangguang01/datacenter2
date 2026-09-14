package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.util.List;

@Data
public class MeasurementPointDTO {

    @NotBlank(message = "pointId 不能为空")
    private String pointId;

    /** 归属业务：创建时必填（service 层校验），更新时忽略（归属不可变更） */
    private String businessId;

    @NotBlank(message = "pointName 不能为空")
    private String pointName;

    @NotNull(message = "dataType 不能为空")
    private PointDataType dataType;

    private String unit;

    @NotNull(message = "direction 不能为空")
    private PointDirection direction;

    /** 仅 INPUT 必填：所引用的 OUTPUT 测点 ID */
    private String referencePointId;

    @PositiveOrZero(message = "deadband 不能为负")
    private Double deadband;

    /** 绑定（全部通道来源，至少一条；创建/更新时整体替换） */
    @Valid
    @NotEmpty(message = "bindings 不能为空")
    private List<PointSourceDTO> bindings;
}
