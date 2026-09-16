package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class MeasurementPointDTO {

    @NotBlank(message = "pointId 不能为空")
    private String pointId;

    /** 归属业务：创建时必填（service 层校验），更新时忽略（归属不可变更） */
    private String businessId;

    @NotBlank(message = "pointName 不能为空")
    private String pointName;

    /** 关联通道 ID（一对一） */
    @NotBlank(message = "channelId 不能为空")
    private String channelId;

    /** 测点在通道上的地址 */
    @NotBlank(message = "address 不能为空")
    private String address;

    @NotNull(message = "dataType 不能为空")
    private PointDataType dataType;

    private String unit;

    /**
     * 数据流向。**创建时必填、更新时静默忽略**（方向创建后不可变更），
     * 因此与 {@link #businessId} 同规：DTO 上不加 {@code @NotNull}——
     * 加了它 {@code @Valid} 会先于 service 拒掉"不重传该字段"的编辑请求。
     * 必填由 {@code PointDirectionValidator.validate}（创建）与
     * {@code ImportFields.parsePoint}（导入）保证。
     */
    private PointDirection direction;

    /** 仅 INPUT 必填：所引用的 OUTPUT 测点 ID */
    private String referencePointId;

    @PositiveOrZero(message = "deadband 不能为负")
    private Double deadband;
}
