package com.sdncustom.server.service;

import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.repository.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final PointHistoryRepository historyRepository;

    /**
     * 初始化 TDengine 超级表。
     * 数据库本身与保留时长（KEEP）由 {@code TdengineConfig} 在连接池创建前引导完成。
     */
    public void init() {
        historyRepository.initSuperTable();
    }

    /**
     * 保存历史记录
     */
    public void save(PointValue pointValue) {
        PointHistory history = new PointHistory();
        history.setPointId(pointValue.getPointId());
        history.setValue(pointValue.getValue());
        history.setQuality(pointValue.getQuality());
        history.setSourceChannelId(pointValue.getSourceChannelId());
        history.setTimestamp(pointValue.getTimestamp());
        historyRepository.save(history);
    }

    /**
     * 批量保存历史记录
     */
    public void saveBatch(List<PointValue> pointValues) {
        List<PointHistory> histories = pointValues.stream().map(pv -> {
            PointHistory history = new PointHistory();
            history.setPointId(pv.getPointId());
            history.setValue(pv.getValue());
            history.setQuality(pv.getQuality());
            history.setSourceChannelId(pv.getSourceChannelId());
            history.setTimestamp(pv.getTimestamp());
            return history;
        }).toList();
        historyRepository.saveBatch(histories);
    }

    /**
     * 查询历史数据
     */
    public List<PointHistory> queryHistory(String pointId, long startTime, long endTime, int page, int size) {
        return historyRepository.findByPointIdAndTimeRange(pointId, startTime, endTime, page, size);
    }
}
