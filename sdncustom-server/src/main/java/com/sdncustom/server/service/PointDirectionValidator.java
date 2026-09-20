package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 测点方向校验：INPUT 必须引用一个存在、同业务、同 dataType 的 OUTPUT 测点；
 * OUTPUT 不得带引用。引用方向严格单向（INPUT -> OUTPUT），因此不可能成环。
 *
 * <p>另禁自引用：INPUT 不得引用与它**同通道**的 OUTPUT（创建见 {@link #validate}，
 * 改通道见 {@link #validateChannelChange}）。注意这条只禁"引用成环"，
 * **不禁一个通道同时挂两种方向的测点**——那仍然是允许的。
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
        // 自引用禁令：同通道会让该设备的上报值直接驱动自己的设定值（平台上的自环控制）
        if (dto.getChannelId() != null && Objects.equals(target.getChannelId(), dto.getChannelId())) {
            throw new BusinessException(400, "输入测点不能引用本通道(" + dto.getChannelId()
                    + ")的输出测点: " + referencePointId);
        }
    }

    /**
     * 通道变更校验：{@code channelId} 是可改的，而自引用只可能在改通道时事后产生——
     * 创建路径已由 {@link #validate} 拦住，更新路径必须补在这里
     * （{@code PointService.update} 不调 {@code validate}：方向与引用从库中取，DTO 的值被忽略）。
     *
     * <p>两个方向都要查：改 INPUT 的通道、或改 OUTPUT 的通道，都能让一对原本跨通道的引用变成同通道。
     *
     * @param point        库中现值（方向与引用以它为准）
     * @param newChannelId DTO 里要改成的新通道
     */
    public void validateChannelChange(MeasurementPoint point, String newChannelId) {
        if (Objects.equals(point.getChannelId(), newChannelId) || newChannelId == null) {
            return; // 没换通道（或挪到"无通道"）：零查询
        }
        if (point.getDirection() == PointDirection.INPUT) {
            String referencePointId = point.getReferencePointId();
            if (referencePointId == null) {
                return;
            }
            MeasurementPoint target = pointRepository.findById(referencePointId).orElse(null);
            if (target != null && newChannelId.equals(target.getChannelId())) {
                throw new BusinessException(400, "不能把输入测点挪到它引用的输出测点所在通道: "
                        + referencePointId + "（同为 " + newChannelId + "）");
            }
            return;
        }
        // OUTPUT：找出所有引用它的 INPUT，看有没有正好落在目标通道上
        for (MeasurementPoint dependent : pointRepository.findByReferencePointIdIn(List.of(point.getPointId()))) {
            if (newChannelId.equals(dependent.getChannelId())) {
                throw new BusinessException(400, "输入测点 " + dependent.getPointId()
                        + " 引用了本测点，不能把它挪到该输入测点所在的通道: " + newChannelId);
            }
        }
    }
}
