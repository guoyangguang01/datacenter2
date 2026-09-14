package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
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
        PointSourceDTO binding = new PointSourceDTO();
        binding.setChannelId("ch_1");
        binding.setAddress("40001");
        dto.setBindings(List.of(binding));
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
}
