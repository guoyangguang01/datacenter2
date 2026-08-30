package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "channel")
public class Channel {

    @Id
    @Column(name = "channel_id", length = 64)
    private String channelId;

    /** 归属业务；实体层声明可空以便 Hibernate 对存量表安全加列，非空约束由 BusinessSystemMigration 收紧 */
    @Column(name = "business_id", length = 64)
    private String businessId;

    @Column(name = "channel_name", length = 128, nullable = false)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_type", length = 32, nullable = false)
    private ProtocolType protocolType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 16, nullable = false)
    private ChannelDirection direction;

    @Column(name = "connection_config", columnDefinition = "TEXT")
    private String connectionConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private ChannelStatus status = ChannelStatus.DISCONNECTED;

    @Column(name = "auto_connect")
    private boolean autoConnect = true;

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
