package com.sdncustom.server.service;

import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.server.repository.PointHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HistoryService 测试")
class HistoryServiceTest {

    @Mock
    private PointHistoryRepository historyRepository;

    private HistoryService service;

    @BeforeEach
    void setUp() {
        service = new HistoryService(historyRepository);
    }

    @Test
    @DisplayName("init 委托给仓库初始化超级表")
    void initDelegatesToRepository() {
        service.init();
        verify(historyRepository).initSuperTable();
    }

    @Test
    @DisplayName("save 把 PointValue 全字段映射为 PointHistory")
    void saveMapsAllFields() {
        PointValue pv = new PointValue();
        pv.setPointId("p_1");
        pv.setValue(3.14);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_src");
        pv.setTimestamp(1727000000000L);

        service.save(pv);

        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(historyRepository).save(captor.capture());
        PointHistory saved = captor.getValue();
        assertEquals("p_1", saved.getPointId());
        assertEquals(3.14, saved.getValue());
        assertEquals(PointQuality.GOOD, saved.getQuality());
        assertEquals("ch_src", saved.getSourceChannelId());
        assertEquals(1727000000000L, saved.getTimestamp());
    }

    @Test
    @DisplayName("saveBatch 逐条映射并保持顺序")
    void saveBatchMapsAllEntriesInOrder() {
        PointValue first = new PointValue();
        first.setPointId("p_a");
        first.setValue(1);
        first.setQuality(PointQuality.GOOD);

        PointValue second = new PointValue();
        second.setPointId("p_b");
        second.setValue(null);
        second.setQuality(PointQuality.COMM_LOST);
        second.setSourceChannelId("ch_2");
        second.setTimestamp(42L);

        service.saveBatch(List.of(first, second));

        ArgumentCaptor<List<PointHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveBatch(captor.capture());
        List<PointHistory> batch = captor.getValue();
        assertEquals(2, batch.size());
        assertEquals("p_a", batch.get(0).getPointId());
        assertEquals(1, batch.get(0).getValue());
        assertEquals(PointQuality.GOOD, batch.get(0).getQuality());
        assertEquals("p_b", batch.get(1).getPointId());
        assertNull(batch.get(1).getValue());
        assertEquals(PointQuality.COMM_LOST, batch.get(1).getQuality());
        assertEquals("ch_2", batch.get(1).getSourceChannelId());
        assertEquals(42L, batch.get(1).getTimestamp());
    }

    @Test
    @DisplayName("saveBatch 空列表仍委托给仓库（由仓库决定空批行为）")
    void saveBatchEmptyStillDelegates() {
        service.saveBatch(List.of());
        ArgumentCaptor<List<PointHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveBatch(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    @DisplayName("queryHistory 原样透传查询参数与结果")
    void queryHistoryDelegates() {
        PointHistory row = new PointHistory();
        row.setPointId("p_q");
        when(historyRepository.findByPointIdAndTimeRange("p_q", 100L, 200L, 2, 50))
                .thenReturn(List.of(row));

        List<PointHistory> result = service.queryHistory("p_q", 100L, 200L, 2, 50);

        assertEquals(1, result.size());
        assertEquals("p_q", result.get(0).getPointId());
        verify(historyRepository).findByPointIdAndTimeRange("p_q", 100L, 200L, 2, 50);
    }
}
