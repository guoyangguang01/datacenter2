package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "measurement_point", indexes = {
        @Index(name = "idx_point_business", columnList = "business_id"),
        @Index(name = "idx_point_channel", columnList = "channel_id"),
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

    /** 该测点关联的通道 ID（一对一）：OUTPUT 从该通道采集，INPUT 写到该通道 */
    @Column(name = "channel_id", length = 64)
    private String channelId;

    /** 测点在通道上的地址（如寄存器号 40001、MQTT topic 等） */
    @Column(name = "address", length = 256)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", length = 16, nullable = false)
    private PointDataType dataType;

    @Column(name = "unit", length = 32)
    private String unit;

    /** 数据流向：OUTPUT 从外部采集，INPUT 写出到外部。DB 层可空（无迁移回填），业务必填由服务层校验保证
     * （创建走 PointDirectionValidator，导入走 ImportFields） */
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
