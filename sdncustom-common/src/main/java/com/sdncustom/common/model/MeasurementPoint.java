package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.PointDataType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

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
