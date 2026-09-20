package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 丢弃来源通道已不在 CONNECTED 的迟到值。通道断开时 {@code markPointsCommLost} 会推 COMM_LOST，
 * 若这些迟到值随后再推送，客户端会被刷回 GOOD——按 DB 状态（客户端看到的状态投影）过滤。
 *
 * <p>独立成单元是因为它有两个调用点、时序不同：采集线程在提交前用一次（滤掉读取期间的断开），
 * 传播阶段在设备写出**之后**再用一次（写出期间用户可能断开，窗口只剩一次查询的距离）。
 */
@Component
@RequiredArgsConstructor
public class LiveSourceChecker {

    private final ChannelRepository channelRepository;

    public List<PointValue> onlyLive(List<PointValue> values) {
        Set<String> live = channelRepository.findByStatus(ChannelStatus.CONNECTED).stream()
                .map(Channel::getChannelId)
                .collect(Collectors.toSet());
        return values.stream()
                .filter(pv -> pv.getSourceChannelId() == null || live.contains(pv.getSourceChannelId()))
                .toList();
    }
}
