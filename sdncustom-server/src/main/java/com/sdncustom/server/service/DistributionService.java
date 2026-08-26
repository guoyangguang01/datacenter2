package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.websocket.DataWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributionService {

    private final DataWebSocketHandler webSocketHandler;

    /**
     * 推送测点值到订阅的客户端
     */
    public void push(PointValue pointValue) {
        webSocketHandler.pushPointValue(pointValue);
    }

    /**
     * 批量推送测点值
     */
    public void pushBatch(List<PointValue> pointValues) {
        for (PointValue pv : pointValues) {
            push(pv);
        }
    }
}
