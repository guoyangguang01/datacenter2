package com.sdncustom.common.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测点的附加来源：同一物理量可同时从多个通道采集/下发。
 * 主绑定仍是 MeasurementPoint.channelId+address，这里保存其余通道的 (channelId, address)。
 * 一个测点的所有绑定通道互不相同（服务层校验），保证来源键 pointId+"|"+channelId 唯一。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "point_source",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_source",
                columnNames = {"point_id", "channel_id", "address"}))
public class PointSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "point_id", length = 64, nullable = false)
    private String pointId;

    @Column(name = "channel_id", length = 64, nullable = false)
    private String channelId;

    @Column(name = "address", length = 256, nullable = false)
    private String address;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
