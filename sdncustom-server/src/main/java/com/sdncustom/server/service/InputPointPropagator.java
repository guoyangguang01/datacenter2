package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 输入测点传播：把已通过 ChangeGate 的输出测点变化写到引用它们的输入测点的绑定通道。
 *
 * 值语义是「意图」而非「实际」——不论写出成功与否，都用输出测点的值更新输入测点，
 * 写出失败只记日志与指标，不降级值质量。
 * 输入测点不参与采集周期，因此传播值不会回流到 ChangeGate，无自我触发回路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputPointPropagator {

    private final MeasurementPointRepository pointRepository;
    private final ChannelService channelService;
    private final ProtocolRegistry protocolRegistry;
    private final MeterRegistry meterRegistry;

    /**
     * @param outputChanges 已通过 ChangeGate 的输出测点变化值
     * @return 输入测点的新值，供调用方与输出测点变化一起批量落库/推送
     */
    public List<PointValue> propagate(List<PointValue> outputChanges) {
        if (outputChanges == null || outputChanges.isEmpty()) {
            return List.of();
        }

        // 去重后一次查出所有引用者；无引用者时零开销返回
        Set<String> outputPointIds = new LinkedHashSet<>();
        for (PointValue pv : outputChanges) {
            outputPointIds.add(pv.getPointId());
        }
        List<MeasurementPoint> inputPoints =
                pointRepository.findByReferencePointIdIn(new ArrayList<>(outputPointIds));
        if (inputPoints.isEmpty()) {
            return List.of();
        }

        List<PointValue> result = new ArrayList<>(inputPoints.size());
        for (MeasurementPoint inputPoint : inputPoints) {
            PointValue source = findChange(outputChanges, inputPoint.getReferencePointId());
            if (source == null) {
                continue;
            }
            String writtenChannel = writeToChannel(inputPoint, source.getValue());
            result.add(toInputValue(inputPoint, source, writtenChannel));
        }
        return result;
    }

    private PointValue findChange(List<PointValue> changes, String outputPointId) {
        for (PointValue pv : changes) {
            if (pv.getPointId().equals(outputPointId)) {
                return pv;
            }
        }
        return null;
    }

    /** 写到 INPUT 测点的绑定通道；返回写出的通道 ID（失败返回 null） */
    private String writeToChannel(MeasurementPoint inputPoint, Object value) {
        String channelId = inputPoint.getChannelId();
        if (channelId == null) {
            log.warn("Propagation skipped: INPUT point {} has no channelId", inputPoint.getPointId());
            return null;
        }
        Channel channel = channelService.findByIdOrNull(channelId);
        if (channel == null) {
            log.warn("Propagation skipped: channel not found {} for point {}",
                    channelId, inputPoint.getPointId());
            return null;
        }
        if (channel.getStatus() != ChannelStatus.CONNECTED) {
            log.warn("Propagation skipped: channel not connected {} for point {}",
                    channelId, inputPoint.getPointId());
            return null;
        }
        try {
            ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);
            adapter.writePoint(inputPoint, value);
            meterRegistry.counter("sdncustom.propagation.writes",
                    "channel", channelId).increment();
            return channelId;
        } catch (Exception e) {
            meterRegistry.counter("sdncustom.propagation.failures",
                    "channel", channelId).increment();
            log.error("Propagation write failed to channel {} for point {}: {}",
                    channelId, inputPoint.getPointId(), e.getMessage());
            return null;
        }
    }

    /**
     * 输入测点的新值：值/质量/时间戳复制自输出测点；来源通道取写出成功的通道，
     * 失败时退回 INPUT 的 channelId（值仍要落库，只是没能送达）。
     */
    private PointValue toInputValue(MeasurementPoint inputPoint, PointValue source, String writtenChannel) {
        PointValue pv = new PointValue();
        pv.setPointId(inputPoint.getPointId());
        pv.setValue(source.getValue());
        pv.setQuality(source.getQuality());
        pv.setTimestamp(source.getTimestamp());
        pv.setSourceChannelId(writtenChannel != null ? writtenChannel : inputPoint.getChannelId());
        return pv;
    }
}
