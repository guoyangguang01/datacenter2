package com.sdncustom.server.monitor;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据面健康：期望常连（autoConnect）却处于非 CONNECTED 的通道视为 DOWN。
 * details 受 show-details=when_authorized 控制，匿名探活仅返回 status。
 */
@Component("channels")
@RequiredArgsConstructor
public class ChannelHealthIndicator implements HealthIndicator {

    private final ChannelRepository channelRepository;

    @Override
    public Health health() {
        List<Channel> all = channelRepository.findAll();
        long connected = all.stream().filter(c -> c.getStatus() == ChannelStatus.CONNECTED).count();
        List<String> autoConnectDown = all.stream()
                .filter(c -> c.isAutoConnect() && c.getStatus() != ChannelStatus.CONNECTED)
                .map(Channel::getChannelId)
                .toList();

        Health.Builder builder = autoConnectDown.isEmpty() ? Health.up() : Health.down();
        return builder
                .withDetail("total", all.size())
                .withDetail("connected", connected)
                .withDetail("autoConnectDown", autoConnectDown)
                .build();
    }
}
