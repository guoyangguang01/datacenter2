package com.sdncustom.common.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 业务系统：多业务隔离的逻辑维度，通道与测点归属到某个业务。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "business_system")
public class BusinessSystem {

    @Id
    @Column(name = "business_id", length = 64)
    private String businessId;

    @Column(name = "business_name", length = 128, nullable = false)
    private String businessName;

    @Column(name = "description", length = 512)
    private String description;

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
