package com.sdncustom.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SystemStatusDTO {

    private long uptimeSeconds;
    private long channelsConnected;
    private long channelsTotal;
    private long wsSessions;
    private double cycleP99Ms;
    private long acquisitionFailures;
    private long changedValuesTotal;
    private boolean historyCircuitOpen;
    private boolean tdengineEnabled;
}
