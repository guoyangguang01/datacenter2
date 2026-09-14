package com.sdncustom.common.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测点的一条绑定（绑定集模型，无主从之分）：同一物理量可同时从多个通道采集/下发。
 * 一个测点的所有绑定通道互不相同（服务层校验），保证来源键 pointId+"|"+channelId 唯一。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "point_source",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_source",
                columnNames = {"point_id", "channel_id", "address"}),
        // 唯一约束以 point_id 打头，按 channel_id 反查（采集/断连/删通道都会走）用不上它
        indexes = @Index(name = "idx_point_source_channel", columnList = "channel_id"))
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
