package com.sdncustom.server.monitor;

import com.sdncustom.common.dto.SystemStatusDTO;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * 系统运行状态聚合：从 MeterRegistry 读取实时指标 + 通道统计，供前端仪表盘状态卡使用。
 */
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private final MeterRegistry meterRegistry;
    private final ChannelRepository channelRepository;

    @Value("${tdengine.url:}")
    private String tdengineUrl;

    public SystemStatusDTO getStatus() {
        var channels = channelRepository.findAll();
        long connected = channels.stream().filter(c -> c.getStatus() == ChannelStatus.CONNECTED).count();

        Timer cycle = meterRegistry.find("sdncustom.acquisition.cycle").timer();

        long failures = meterRegistry.find("sdncustom.acquisition.failures").counters().stream()
                .mapToLong(c -> (long) c.count()).sum();
        Counter changed = meterRegistry.find("sdncustom.acquisition.changed.values").counter();
        Gauge sessions = meterRegistry.find("sdncustom.ws.sessions").gauge();
        Gauge circuit = meterRegistry.find("sdncustom.history.circuit.open").gauge();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        return new SystemStatusDTO(
                uptimeSeconds,
                connected,
                channels.size(),
                sessions == null ? 0 : (long) sessions.value(),
                percentileMs(cycle, 0.99),
                failures,
                changed == null ? 0 : (long) changed.count(),
                circuit != null && circuit.value() > 0.5,
                tdengineUrl != null && !tdengineUrl.isEmpty());
    }

    private double percentileMs(Timer timer, double percentile) {
        if (timer == null) {
            return 0;
        }
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - percentile) < 1e-6) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return 0;
    }
}
