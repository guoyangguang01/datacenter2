package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 测点方向校验：INPUT 必须引用一个存在、同业务、同 dataType 的 OUTPUT 测点；
 * OUTPUT 不得带引用。引用方向严格单向（INPUT -> OUTPUT），因此不可能成环。
 */
@Component
@RequiredArgsConstructor
public class PointDirectionValidator {

    private final MeasurementPointRepository pointRepository;

    /** @param businessId 测点的归属业务（update 时取库中现值，不取 DTO） */
    public void validate(MeasurementPointDTO dto) {
        validate(dto, dto.getBusinessId());
    }

    public void validate(MeasurementPointDTO dto, String businessId) {
        PointDirection direction = dto.getDirection();
        if (direction == null) {
            throw new BusinessException(400, "direction 不能为空");
        }
        if (direction == PointDirection.OUTPUT) {
            if (dto.getReferencePointId() != null && !dto.getReferencePointId().isBlank()) {
                throw new BusinessException(400, "输出测点不能引用其它测点: " + dto.getReferencePointId());
            }
            return;
        }
        // INPUT
        String referencePointId = dto.getReferencePointId();
        if (referencePointId == null || referencePointId.isBlank()) {
            throw new BusinessException(400, "输入测点必须引用一个输出测点");
        }
        MeasurementPoint target = pointRepository.findById(referencePointId).orElse(null);
        if (target == null) {
            throw new BusinessException(400, "引用的测点不存在: " + referencePointId);
        }
        if (target.getDirection() != PointDirection.OUTPUT) {
            throw new BusinessException(400, "只能引用输出测点: " + referencePointId
                    + "（当前方向为 " + target.getDirection() + "）");
        }
        if (!Objects.equals(target.getBusinessId(), businessId)) {
            throw new BusinessException(400, "引用的测点不属于当前业务: " + referencePointId);
        }
        if (target.getDataType() != dto.getDataType()) {
            throw new BusinessException(400, "引用的测点数据类型不一致: " + referencePointId
                    + "（" + target.getDataType() + " != " + dto.getDataType() + "）");
        }
    }
}
