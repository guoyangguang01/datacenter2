package com.sdncustom.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "measurement_point", indexes = {
        @Index(name = "idx_point_business", columnList = "business_id"),
        @Index(name = "idx_point_reference", columnList = "reference_point_id")
})
public class MeasurementPoint {

    @Id
    @Column(name = "point_id", length = 64)
    private String pointId;

    /** 归属业务；业务约束由服务层校验（BusinessSystemService.requireExists），DB 层不加非空约束 */
    @Column(name = "business_id", length = 64)
    private String businessId;

    @Column(name = "point_name", length = 128, nullable = false)
    private String pointName;

    /** 视图传输字段（非持久化、无权威）：采集/写入时按绑定通道设置，供协议适配器读取地址；API 响应忽略 */
    @Transient
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String channelId;

    @Transient
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", length = 16, nullable = false)
    private PointDataType dataType;

    @Column(name = "unit", length = 32)
    private String unit;

    /** 数据流向：OUTPUT 从外部采集，INPUT 写出到外部。DB 层可空（无迁移回填），业务必填由 DTO @NotNull 保证 */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 16)
    private PointDirection direction;

    /** 仅 INPUT 使用：所引用的 OUTPUT 测点 ID；OUTPUT 时必须为 null */
    @Column(name = "reference_point_id", length = 64)
    private String referencePointId;

    /** 死区：|新值-旧值| > deadband 才视为有效变化；null 等价于 0（任何变化都上报） */
    @Column(name = "deadband")
    private Double deadband;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /** 绑定（REST/导出回填用，非持久化字段）：该测点绑定的全部 (通道,地址)，无主从之分 */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<PointSource> bindings;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
