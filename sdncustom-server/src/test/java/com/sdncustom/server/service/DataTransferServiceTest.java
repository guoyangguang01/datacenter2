package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.server.repository.ChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataTransferService 数据导入 测试")
class DataTransferServiceTest {

    @Mock
    private BusinessSystemService businessSystemService;

    @Mock
    private PointService pointService;

    @Mock
    private ChannelRepository channelRepository;

    @InjectMocks
    private DataTransferService dataTransferService;

    private BusinessSystemDTO business(String id) {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId(id);
        dto.setBusinessName("厂-" + id);
        return dto;
    }

    private MeasurementPointDTO point(String pointId, String businessId, String... channelIds) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(pointId);
        dto.setBusinessId(businessId);
        dto.setPointName(pointId);
        dto.setDataType(PointDataType.FLOAT32);
        List<PointSourceDTO> bindings = new ArrayList<>();
        for (String channelId : channelIds) {
            PointSourceDTO binding = new PointSourceDTO();
            binding.setChannelId(channelId);
            binding.setAddress("addr_" + channelId);
            bindings.add(binding);
        }
        dto.setBindings(bindings);
        return dto;
    }

    @Test
    @DisplayName("导入业务与测点：新业务 create，测点交给 pointService")
    void importsBusinessesAndPoints() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(false);

        DataTransferService.ImportResult result = dataTransferService.importData(
                List.of(business("biz")), List.of(point("p1", "biz", "ch_1")));

        assertEquals(1, result.businessCount());
        assertEquals(1, result.pointCount());
        verify(businessSystemService).create(any(BusinessSystemDTO.class));
        verify(pointService).importPoints(anyList());
    }

    @Test
    @DisplayName("已存在的业务走 update 而非 create")
    void updatesExistingBusiness() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(true);

        dataTransferService.importData(List.of(business("biz")), List.of(point("p1", "biz", "ch_1")));

        verify(businessSystemService).update(eq("biz"), any(BusinessSystemDTO.class));
        verify(businessSystemService, never()).create(any(BusinessSystemDTO.class));
    }

    @Test
    @DisplayName("绑定通道不存在：在任何写库之前整体失败，报错点名 测点->通道 并指引先导通道配置")
    void failsBeforeAnyWriteWhenChannelMissing() {
        when(channelRepository.findById("ch_x")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> dataTransferService.importData(
                List.of(business("biz")), List.of(point("p1", "biz", "ch_x"))));

        assertTrue(ex.getMessage().contains("p1->ch_x"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/api/channels/import"), ex.getMessage());

        // 预检先于写库：业务与测点都不得被触碰
        verifyNoInteractions(pointService);
        verify(businessSystemService, never()).create(any(BusinessSystemDTO.class));
        verify(businessSystemService, never()).ensureExistsForImport(anyString());
    }

    @Test
    @DisplayName("业务名为空白时取 businessId 兜底")
    void defaultsBlankBusinessName() {
        when(businessSystemService.exists("biz")).thenReturn(false);
        BusinessSystemDTO dto = business("biz");
        dto.setBusinessName("   ");

        dataTransferService.importData(List.of(dto), List.of());

        assertEquals("biz", dto.getBusinessName());
    }
}
