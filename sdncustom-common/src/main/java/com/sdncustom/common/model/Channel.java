package com.sdncustom.common.model;

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
@Table(name = "channel", indexes = {
        // status 每 200ms 被采集引擎查一次；business_id 用于按业务过滤
        @Index(name = "idx_channel_status", columnList = "status"),
        @Index(name = "idx_channel_business", columnList = "business_id"),
        // code 供外部数据源总表按代码定位通道
        @Index(name = "idx_channel_code", columnList = "code")
})
public class Channel {

    @Id
    @Column(name = "channel_id", length = 64)
    private String channelId;

    /** 归属业务；业务约束由服务层校验（BusinessSystemService.requireExists），DB 层不加非空约束 */
    @Column(name = "business_id", length = 64)
    private String businessId;

    @Column(name = "channel_name", length = 128, nullable = false)
    private String channelName;

    /**
     * 外部系统代码（如 FZXT / SWGZ），外部数据源总表用它指向本通道。
     * 可空（表示未编码）；非空时要求业务内不重复，唯一性由服务层校验
     * （ChannelService），DB 层不加唯一约束。
     */
    @Column(name = "code", length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_type", length = 32, nullable = false)
    private ProtocolType protocolType;

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
