package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.MeasurementPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 方向校验规则的纯单元测试：只断言校验结论，不碰持久化。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("测点方向校验规则 测试")
class PointDirectionValidationTest {

    @Mock
    private MeasurementPointRepository pointRepository;

    private MeasurementPoint outputPoint;
    private PointDirectionValidator validator;

    @BeforeEach
    void setUp() {
        outputPoint = new MeasurementPoint();
        outputPoint.setPointId("out_1");
        outputPoint.setBusinessId("biz_a");
        outputPoint.setPointName("输出测点");
        outputPoint.setDataType(PointDataType.FLOAT32);
        outputPoint.setDirection(PointDirection.OUTPUT);

        validator = new PointDirectionValidator(pointRepository);
    }

    private MeasurementPointDTO dto(PointDirection direction, String referencePointId) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId("in_1");
        dto.setBusinessId("biz_a");
        dto.setPointName("输入测点");
        dto.setDataType(PointDataType.FLOAT32);
        dto.setDirection(direction);
        dto.setReferencePointId(referencePointId);
        dto.setChannelId("ch_1");
        dto.setAddress("40001");
        return dto;
    }

    @Test
    @DisplayName("INPUT 引用不存在的测点 -> 400")
    void inputReferenceMissing() {
        when(pointRepository.findById("nope")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "nope")));
        assertTrue(ex.getMessage().contains("nope"), ex.getMessage());
    }

    @Test
    @DisplayName("INPUT 引用另一个 INPUT -> 400")
    void inputReferenceNotNull() {
        MeasurementPoint other = new MeasurementPoint();
        other.setPointId("in_2");
        other.setBusinessId("biz_a");
        other.setDataType(PointDataType.FLOAT32);
        other.setDirection(PointDirection.INPUT);
        when(pointRepository.findById("in_2")).thenReturn(Optional.of(other));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "in_2")));
    }

    @Test
    @DisplayName("INPUT 引用跨业务 OUTPUT -> 400")
    void inputReferenceCrossBusiness() {
        outputPoint.setBusinessId("biz_b");
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    @Test
    @DisplayName("INPUT 引用 dataType 不一致的 OUTPUT -> 400")
    void inputReferenceTypeMismatch() {
        outputPoint.setDataType(PointDataType.INT16);
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    @Test
    @DisplayName("OUTPUT 带 referencePointId -> 400")
    void outputWithReference() {
        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.OUTPUT, "out_1")));
    }

    @Test
    @DisplayName("合法 INPUT 引用同业务同类型 OUTPUT -> 通过")
    void validInput() {
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertDoesNotThrow(() -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    // ===== 自引用禁令：INPUT 不得引用与它同通道的 OUTPUT =====
    // 同通道读写会让该设备的"上报值"直接驱动"自己的设定值"（平台上的自环控制），
    // 且让同一条 socket 上的读写争用变得没有意义。规则只禁自环，不禁"一个通道两种方向兼有"。

    @Test
    @DisplayName("INPUT 引用同通道的 OUTPUT -> 400")
    void inputReferenceSameChannel() {
        outputPoint.setChannelId("ch_1"); // 与 dto 的 channelId 相同
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "out_1")));
        assertTrue(ex.getMessage().contains("out_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ch_1"), ex.getMessage());
    }

    @Test
    @DisplayName("INPUT 引用跨通道的 OUTPUT -> 通过（规则只禁自环，不禁同通道两种方向兼有）")
    void inputReferenceOtherChannelAllowed() {
        outputPoint.setChannelId("ch_2");
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertDoesNotThrow(() -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    // ===== 更新路径：channelId 可改，两个方向都能事后造出自引用 =====

    @Test
    @DisplayName("把 INPUT 挪到它引用的 OUTPUT 所在通道 -> 400")
    void moveInputOntoItsReferenceChannel() {
        MeasurementPoint input = new MeasurementPoint();
        input.setPointId("in_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");
        input.setChannelId("ch_1");

        outputPoint.setChannelId("ch_2");
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateChannelChange(input, "ch_2"));
        assertTrue(ex.getMessage().contains("out_1"), ex.getMessage());
    }

    @Test
    @DisplayName("把 OUTPUT 挪到引用它的 INPUT 所在通道 -> 400 且点名那个 INPUT")
    void moveOutputOntoItsDependentChannel() {
        MeasurementPoint output = new MeasurementPoint();
        output.setPointId("out_1");
        output.setDirection(PointDirection.OUTPUT);
        output.setChannelId("ch_1");

        MeasurementPoint dependent = new MeasurementPoint();
        dependent.setPointId("in_9");
        dependent.setDirection(PointDirection.INPUT);
        dependent.setReferencePointId("out_1");
        dependent.setChannelId("ch_2");
        when(pointRepository.findByReferencePointIdIn(List.of("out_1")))
                .thenReturn(List.of(dependent));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateChannelChange(output, "ch_2"));
        assertTrue(ex.getMessage().contains("in_9"), "应点名挡住这次修改的输入测点: " + ex.getMessage());
    }

    @Test
    @DisplayName("通道没变 -> 不做任何查询（省一次往返）")
    void unchangedChannelSkipsLookup() {
        MeasurementPoint output = new MeasurementPoint();
        output.setPointId("out_1");
        output.setDirection(PointDirection.OUTPUT);
        output.setChannelId("ch_1");

        assertDoesNotThrow(() -> validator.validateChannelChange(output, "ch_1"));
        verifyNoInteractions(pointRepository);
    }

    @Test
    @DisplayName("挪到无关通道 -> 通过")
    void moveToUnrelatedChannelAllowed() {
        MeasurementPoint output = new MeasurementPoint();
        output.setPointId("out_1");
        output.setDirection(PointDirection.OUTPUT);
        output.setChannelId("ch_1");

        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of());

        assertDoesNotThrow(() -> validator.validateChannelChange(output, "ch_3"));
    }
}
