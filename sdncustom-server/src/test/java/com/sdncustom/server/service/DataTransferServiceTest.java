package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private ChannelService channelService;

    @Mock
    private PointService pointService;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private MeasurementPointRepository pointRepository;

    @InjectMocks
    private DataTransferService dataTransferService;

    /** 便捷调用：无通道、无解析问题 */
    private DataTransferService.ImportResult importData(
            List<BusinessSystemDTO> businesses, List<MeasurementPointDTO> points) {
        return dataTransferService.importData(businesses, List.<ChannelDTO>of(), points, List.of());
    }

    /** 便捷调用：无通道、有解析问题 */
    private DataTransferService.ImportResult importData(
            List<BusinessSystemDTO> businesses, List<MeasurementPointDTO> points, List<String> parseProblems) {
        return dataTransferService.importData(businesses, List.<ChannelDTO>of(), points, parseProblems);
    }

    private BusinessSystemDTO business(String id) {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId(id);
        dto.setBusinessName("厂-" + id);
        return dto;
    }

    private MeasurementPointDTO point(String pointId, String businessId, String channelId) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(pointId);
        dto.setBusinessId(businessId);
        dto.setPointName(pointId);
        dto.setDataType(PointDataType.FLOAT32);
        dto.setDirection(PointDirection.OUTPUT);
        dto.setChannelId(channelId);
        dto.setAddress("addr_" + channelId);
        return dto;
    }

    @Test
    @DisplayName("导入业务与测点：新业务 create，测点交给 pointService")
    void importsBusinessesAndPoints() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(false);

        DataTransferService.ImportResult result = importData(
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

        importData(List.of(business("biz")), List.of(point("p1", "biz", "ch_1")));

        verify(businessSystemService).update(eq("biz"), any(BusinessSystemDTO.class));
        verify(businessSystemService, never()).create(any(BusinessSystemDTO.class));
    }

    @Test
    @DisplayName("绑定通道不存在：在任何写库之前整体失败，报错点名 测点->通道 并指引先导通道配置")
    void failsBeforeAnyWriteWhenChannelMissing() {
        when(channelRepository.findById("ch_x")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> importData(
                List.of(business("biz")), List.of(point("p1", "biz", "ch_x"))));

        assertTrue(ex.getMessage().contains("p1->ch_x"), ex.getMessage());
        assertTrue(ex.getMessage().contains("channels"), ex.getMessage());

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

        importData(List.of(dto), List.of());

        assertEquals("biz", dto.getBusinessName());
    }

    @Test
    @DisplayName("导入 INPUT 引用不存在的测点：整批失败并点名")
    void failsWhenReferenceMissing() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(pointRepository.findById("out_missing")).thenReturn(Optional.empty());

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_missing");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(input)));

        assertTrue(ex.getMessage().contains("out_missing"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("导入 INPUT 引用本次导入内的 OUTPUT：解析成功")
    void resolvesReferenceWithinSamePayload() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(false);

        MeasurementPointDTO output = point("out_1", "biz", "ch_1");
        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        DataTransferService.ImportResult result = importData(
                List.of(business("biz")), List.of(output, input));

        assertEquals(2, result.pointCount());
        verify(pointService).importPoints(anyList());
    }

    @Test
    @DisplayName("导入 INPUT 引用 dataType 不一致的 OUTPUT：整批失败")
    void failsOnDataTypeMismatch() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        MeasurementPoint out = new MeasurementPoint();
        out.setPointId("out_1");
        out.setBusinessId("biz");
        out.setDataType(PointDataType.INT16);
        out.setDirection(PointDirection.OUTPUT);
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(out));

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");   // FLOAT32
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(input)));
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("导入 INPUT 缺少 referencePointId：整批失败并点名测点")
    void failsWhenInputHasNoReference() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(input)));

        assertTrue(ex.getMessage().contains("in_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("referencePointId"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("OUTPUT 带 referencePointId：整批失败，报错点名测点与引用")
    void failsWhenOutputCarriesReference() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO output = point("out_1", "biz", "ch_1");
        output.setReferencePointId("other");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(output)));

        assertTrue(ex.getMessage().contains("out_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("other"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("INPUT 引用库里已有的 INPUT：整批失败")
    void failsWhenReferencedPointIsNotOutput() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        MeasurementPoint target = new MeasurementPoint();
        target.setPointId("out_1");
        target.setBusinessId("biz");
        target.setDataType(PointDataType.FLOAT32);
        target.setDirection(PointDirection.INPUT);
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(target));

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(input)));

        assertTrue(ex.getMessage().contains("out_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("不是输出测点"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("一次收集全部引用问题后统一抛错：报错点名每个测点，而非首个失败即止")
    void collectsAllReferenceProblemsBeforeThrowing() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO noRef = point("in_no_ref", "biz", "ch_1");
        noRef.setDirection(PointDirection.INPUT);
        MeasurementPointDTO refsInput = point("in_refs_input", "biz", "ch_1");
        refsInput.setDirection(PointDirection.INPUT);
        refsInput.setReferencePointId("in_no_ref");   // payload 内，但它不是输出测点

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(noRef, refsInput)));

        assertTrue(ex.getMessage().contains("in_no_ref"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in_refs_input"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("INPUT 排在它引用的 OUTPUT 之前：排序后输出点先落库，顺序依赖被解开")
    void ordersOutputsBeforeInputs() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(false);

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");
        MeasurementPointDTO output = point("out_1", "biz", "ch_1");

        // 模拟 PointService.importPoints -> create -> PointDirectionValidator 的库内引用解析：
        // 只认已落库的点（引用预检过了不代表这一刻库里就有）
        Set<String> persisted = new HashSet<>();
        when(pointService.importPoints(anyList())).thenAnswer(invocation -> {
            List<MeasurementPointDTO> dtos = invocation.getArgument(0);
            for (MeasurementPointDTO dto : dtos) {
                if (dto.getDirection() == PointDirection.INPUT
                        && !persisted.contains(dto.getReferencePointId())) {
                    throw new BusinessException(400, "引用的测点不存在: " + dto.getReferencePointId());
                }
                persisted.add(dto.getPointId());
            }
            return List.of();
        });

        // 引用预检能通过（payload 内解析得到），但 importPoints 逐条 create 时输出点必须先落库
        importData(List.of(business("biz")), List.of(input, output));

        assertEquals(Set.of("in_1", "out_1"), persisted);
        ArgumentCaptor<List<MeasurementPointDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(pointService).importPoints(captor.capture());
        assertEquals(List.of("out_1", "in_1"),
                captor.getValue().stream().map(MeasurementPointDTO::getPointId).toList());
    }

    @Test
    @DisplayName("导入 direction 缺失的测点：整批失败并点名该测点，不留给晚校验报无主语错误")
    void rejectsPointsWithoutDirection() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO broken = point("no_dir", "biz", "ch_1");
        broken.setDirection(null);
        MeasurementPointDTO output = point("out_1", "biz", "ch_1");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(broken, output)));

        assertTrue(ex.getMessage().contains("no_dir"), ex.getMessage());
        assertTrue(ex.getMessage().contains("direction"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("导入 INPUT 引用 payload 内 dataType 不一致的 OUTPUT：整批失败并点名请求方")
    void failsOnInPayloadDataTypeMismatch() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO output = point("out_1", "biz", "ch_1");
        output.setDataType(PointDataType.INT16);
        MeasurementPointDTO input = point("in_1", "biz", "ch_1");   // FLOAT32
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(output, input)));

        // 报错必须点出要改的那条记录（INPUT），而不只是被引用方
        assertTrue(ex.getMessage().contains("in_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("out_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("数据类型不一致"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("pointId 缺失的手工导入文件：预检判不合格并报 400，而不是从预检里抛 NPE")
    void doesNotCrashOnMissingPointId() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("in_1");
        MeasurementPointDTO noId = point("x", "biz", "ch_1");
        noId.setPointId(null);
        noId.setDirection(PointDirection.INPUT);   // 这条记录自身也不合格，才会被点名

        // noId 必须排在 input **前面**：payload 内引用用 findFirst() 解析，命中 input 会短路，
        // 让缺 pointId 的那条永远不被扫到——那样这个用例钉不住它要钉的崩溃路径
        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(noId, input)));

        assertTrue(ex.getMessage().contains("in_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("<缺少 pointId 的测点>"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    /**
     * 解析层（{@code ImportFields.parsePoints}）攒下的问题必须与本层的引用问题合成**一条**报错：
     * 这条路径上用户只能看到这一次反馈，分两次抛等于让他修完一条再撞见下一条。
     */
    @Test
    @DisplayName("解析层的问题与服务层的问题合并成一条报错")
    void joinsParseProblemsWithReferenceProblems() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO noRef = point("in_no_ref", "biz", "ch_1");
        noRef.setDirection(PointDirection.INPUT);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(noRef),
                        List.of("no_dir 缺少必填字段: direction")));

        assertTrue(ex.getMessage().contains("no_dir"), ex.getMessage());
        assertTrue(ex.getMessage().contains("缺少必填字段: direction"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in_no_ref"), ex.getMessage());
        assertTrue(ex.getMessage().contains("缺少 referencePointId"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("direction 缺失与引用不合法一次报全：每个不合格测点都被点名")
    void collectsDirectionAndReferenceProblemsTogether() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));

        MeasurementPointDTO noDirection = point("no_dir", "biz", "ch_1");
        noDirection.setDirection(null);
        MeasurementPointDTO noRef = point("in_no_ref", "biz", "ch_1");
        noRef.setDirection(PointDirection.INPUT);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importData(List.of(business("biz")), List.of(noDirection, noRef)));

        assertTrue(ex.getMessage().contains("no_dir"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in_no_ref"), ex.getMessage());
        verifyNoInteractions(pointService);
    }
}
