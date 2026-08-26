package com.sdncustom.common.model;

import com.sdncustom.common.model.enums.PointQuality;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointValue {

    private String pointId;
    private Object value;
    private PointQuality quality;
    private String sourceChannelId;
    private long timestamp;

    public static PointValue commLost(String pointId) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(null);
        pv.setQuality(PointQuality.COMM_LOST);
        pv.setTimestamp(System.currentTimeMillis());
        return pv;
    }
}
