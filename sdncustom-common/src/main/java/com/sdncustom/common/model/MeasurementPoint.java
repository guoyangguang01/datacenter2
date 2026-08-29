package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.PointDataType;
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
@Table(name = "measurement_point")
public class MeasurementPoint {

    @Id
    @Column(name = "point_id", length = 64)
    private String pointId;

    @Column(name = "point_name", length = 128, nullable = false)
    private String pointName;

    @Column(name = "channel_id", length = 64, nullable = false)
    private String channelId;

    @Column(name = "address", length = 256, nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", length = 16, nullable = false)
    private PointDataType dataType;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "writable")
    private boolean writable = false;

    /** 死区：|新值-旧值| > deadband 才视为有效变化；null 等价于 0（任何变化都上报） */
    @Column(name = "deadband")
    private Double deadband;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /** 附加来源（REST/导出回填用，非持久化字段；主绑定仍是 channelId+address） */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<PointSource> additionalSources;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
