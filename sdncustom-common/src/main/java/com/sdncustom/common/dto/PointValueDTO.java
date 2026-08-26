package com.sdncustom.common.dto;

import com.sdncustom.common.model.enums.PointQuality;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointValueDTO {

    private String pointId;
    private Object value;
    private PointQuality quality;
    private String sourceChannelId;
    private long timestamp;
}
