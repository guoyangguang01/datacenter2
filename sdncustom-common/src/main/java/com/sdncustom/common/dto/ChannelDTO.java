package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ProtocolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChannelDTO {

    @NotBlank(message = "channelId 不能为空")
    private String channelId;

    /** 归属业务：创建时必填（service 层校验），更新时忽略（归属不可变更） */
    private String businessId;

    @NotBlank(message = "channelName 不能为空")
    private String channelName;

    @NotNull(message = "protocolType 不能为空")
    private ProtocolType protocolType;

    @NotNull(message = "direction 不能为空")
    private ChannelDirection direction;

    private String connectionConfig;

    private boolean autoConnect = true;
}
