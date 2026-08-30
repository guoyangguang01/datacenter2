package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.BusinessSystem;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.server.repository.BusinessSystemRepository;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessSystemService 测试")
class BusinessSystemServiceTest {

    @Mock
    private BusinessSystemRepository businessRepository;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @InjectMocks
    private BusinessSystemService businessSystemService;

    private BusinessSystemDTO dto(String id, String name) {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId(id);
        dto.setBusinessName(name);
        return dto;
    }

    private BusinessSystem entity(String id, String name) {
        BusinessSystem biz = new BusinessSystem();
        biz.setBusinessId(id);
        biz.setBusinessName(name);
        return biz;
    }

    @Test
    @DisplayName("创建业务成功")
    void create() {
        when(businessRepository.existsById("biz_a")).thenReturn(false);
        when(businessRepository.save(any(BusinessSystem.class))).thenAnswer(inv -> inv.getArgument(0));

        BusinessSystem result = businessSystemService.create(dto("biz_a", "业务A"));

        assertEquals("biz_a", result.getBusinessId());
        assertEquals("业务A", result.getBusinessName());
    }

    @Test
    @DisplayName("创建业务 - 重复 ID 拒绝")
    void createRejectsDuplicate() {
        when(businessRepository.existsById("biz_a")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> businessSystemService.create(dto("biz_a", "业务A")));
        assertTrue(ex.getMessage().contains("已存在"));
        verify(businessRepository, never()).save(any());
    }

    @Test
    @DisplayName("更新业务仅改名称/描述")
    void update() {
        BusinessSystem existing = entity("biz_a", "旧名称");
        when(businessRepository.findById("biz_a")).thenReturn(Optional.of(existing));
        when(businessRepository.save(any(BusinessSystem.class))).thenAnswer(inv -> inv.getArgument(0));

        BusinessSystemDTO upd = dto("ignored", "新名称");
        upd.setDescription("新描述");
        BusinessSystem result = businessSystemService.update("biz_a", upd);

        assertEquals("biz_a", result.getBusinessId());
        assertEquals("新名称", result.getBusinessName());
        assertEquals("新描述", result.getDescription());
    }

    @Test
    @DisplayName("删除空业务成功")
    void deleteEmptyBusiness() {
        when(businessRepository.findById("biz_a")).thenReturn(Optional.of(entity("biz_a", "业务A")));
        when(channelRepository.findByBusinessId("biz_a")).thenReturn(List.of());
        when(pointRepository.findByBusinessId("biz_a")).thenReturn(List.of());

        businessSystemService.delete("biz_a");

        verify(businessRepository).deleteById("biz_a");
    }

    @Test
    @DisplayName("删除业务 - 名下有通道时拒绝")
    void deleteRejectsWhenChannelsExist() {
        when(businessRepository.findById("biz_a")).thenReturn(Optional.of(entity("biz_a", "业务A")));
        Channel ch = new Channel();
        ch.setChannelId("ch_a");
        when(channelRepository.findByBusinessId("biz_a")).thenReturn(List.of(ch));

        BusinessException ex = assertThrows(BusinessException.class, () -> businessSystemService.delete("biz_a"));
        assertTrue(ex.getMessage().contains("通道"));
        verify(businessRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("删除业务 - 名下有测点时拒绝")
    void deleteRejectsWhenPointsExist() {
        when(businessRepository.findById("biz_a")).thenReturn(Optional.of(entity("biz_a", "业务A")));
        when(channelRepository.findByBusinessId("biz_a")).thenReturn(List.of());
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId("p1");
        when(pointRepository.findByBusinessId("biz_a")).thenReturn(List.of(p));

        BusinessException ex = assertThrows(BusinessException.class, () -> businessSystemService.delete("biz_a"));
        assertTrue(ex.getMessage().contains("测点"));
        verify(businessRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("删除业务 - 不存在时抛 404")
    void deleteNotFound() {
        when(businessRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> businessSystemService.delete("ghost"));
    }

    @Test
    @DisplayName("requireExists - 空或不存在时拒绝，存在时放行")
    void requireExists() {
        assertThrows(BusinessException.class, () -> businessSystemService.requireExists(null));
        assertThrows(BusinessException.class, () -> businessSystemService.requireExists("  "));

        when(businessRepository.existsById("ghost")).thenReturn(false);
        assertThrows(BusinessException.class, () -> businessSystemService.requireExists("ghost"));

        when(businessRepository.existsById("default")).thenReturn(true);
        assertDoesNotThrow(() -> businessSystemService.requireExists("default"));
    }

    @Test
    @DisplayName("ensureExistsForImport - 缺失业务自动创建，已存在不动")
    void ensureExistsForImport() {
        when(businessRepository.existsById("biz_x")).thenReturn(false);
        when(businessRepository.save(any(BusinessSystem.class))).thenAnswer(inv -> inv.getArgument(0));

        businessSystemService.ensureExistsForImport("biz_x");

        ArgumentCaptor<BusinessSystem> captor = ArgumentCaptor.forClass(BusinessSystem.class);
        verify(businessRepository).save(captor.capture());
        assertEquals("biz_x", captor.getValue().getBusinessId());
        assertEquals("biz_x", captor.getValue().getBusinessName());

        clearInvocations(businessRepository);
        when(businessRepository.existsById("biz_y")).thenReturn(true);
        businessSystemService.ensureExistsForImport("biz_y");
        verify(businessRepository, never()).save(any());

        // null/空白不处理
        businessSystemService.ensureExistsForImport(null);
        businessSystemService.ensureExistsForImport("  ");
        verify(businessRepository, never()).save(any());
    }
}
