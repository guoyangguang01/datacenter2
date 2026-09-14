package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据导入：只处理业务与测点（含绑定），不碰通道——通道属于连接配置，
 * 由 {@code POST /api/channels/import} 单独管理。
 *
 * 因为测点靠 channelId 绑定通道，绑定的通道必须已存在；缺通道时整体失败，
 * 且校验发生在任何写库之前，避免留下半成品。测点引用（INPUT -> OUTPUT）同样在写库前预检。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferService {

    private final BusinessSystemService businessSystemService;
    private final PointService pointService;
    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;

    public record ImportResult(int businessCount, int pointCount) {
    }

    @Transactional
    public ImportResult importData(List<BusinessSystemDTO> businesses, List<MeasurementPointDTO> points) {
        validateChannelsExist(points);
        validateReferencesExist(points);

        for (BusinessSystemDTO dto : businesses) {
            upsertBusiness(dto);
        }
        for (MeasurementPointDTO dto : points) {
            businessSystemService.ensureExistsForImport(dto.getBusinessId());
        }
        pointService.importPoints(orderOutputsFirst(points));

        log.info("Imported {} business(es) and {} point(s)", businesses.size(), points.size());
        return new ImportResult(businesses.size(), points.size());
    }

    /** 一次性收集全部缺失绑定再抛错，报错点名到「测点->通道」 */
    private void validateChannelsExist(List<MeasurementPointDTO> points) {
        List<String> missing = new ArrayList<>();
        for (MeasurementPointDTO dto : points) {
            if (dto.getBindings() == null) {
                continue;
            }
            for (PointSourceDTO binding : dto.getBindings()) {
                if (channelRepository.findById(binding.getChannelId()).isEmpty()) {
                    missing.add(dto.getPointId() + "->" + binding.getChannelId());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "导入失败：以下测点绑定的通道不存在，请先导入通道配置"
                    + "（POST /api/channels/import）：" + String.join(", ", missing));
        }
    }

    /**
     * 引用预检：INPUT 的 referencePointId 必须能在「本次导入的测点集 ∪ 库中已有测点」里
     * 解析到且为 OUTPUT，并且 dataType 一致。与通道预检一样在任何写库之前完成。
     *
     * <p>之所以不复用 {@link PointDirectionValidator}：那个校验的是「单个测点对库」，
     * 而导入的 INPUT 可以合法引用同一份文件里、尚未落库的 OUTPUT。
     */
    private void validateReferencesExist(List<MeasurementPointDTO> points) {
        Set<String> inPayload = new HashSet<>();
        for (MeasurementPointDTO dto : points) {
            inPayload.add(dto.getPointId());
        }
        List<String> problems = new ArrayList<>();
        for (MeasurementPointDTO dto : points) {
            if (dto.getDirection() != PointDirection.INPUT) {
                if (dto.getReferencePointId() != null && !dto.getReferencePointId().isBlank()) {
                    problems.add(dto.getPointId() + " 方向为 " + dto.getDirection()
                            + "，不应带有 referencePointId: " + dto.getReferencePointId());
                }
                continue;
            }
            String refId = dto.getReferencePointId();
            if (refId == null || refId.isBlank()) {
                problems.add(dto.getPointId() + " 缺少 referencePointId");
                continue;
            }
            // 本次 payload 内的引用：先按 payload 里的方向判定，避免与库中旧状态混淆
            if (inPayload.contains(refId)) {
                MeasurementPointDTO inPayloadTarget = points.stream()
                        .filter(p -> p.getPointId().equals(refId))
                        .findFirst().orElse(null);
                if (inPayloadTarget == null || inPayloadTarget.getDirection() != PointDirection.OUTPUT) {
                    problems.add(dto.getPointId() + " 引用的 " + refId + " 不是输出测点");
                }
                continue;
            }
            MeasurementPoint target = pointRepository.findById(refId).orElse(null);
            if (target == null) {
                problems.add(dto.getPointId() + " 引用的 " + refId + " 不存在");
            } else if (target.getDirection() != PointDirection.OUTPUT) {
                problems.add(dto.getPointId() + " 引用的 " + refId + " 不是输出测点");
            } else if (target.getDataType() != dto.getDataType()) {
                problems.add(dto.getPointId() + " 与 " + refId + " 数据类型不一致");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(400, "导入失败：测点引用不合法 - " + String.join("；", problems));
        }
    }

    /**
     * 预检通过还不够：{@code PointService.importPoints} 按顺序逐条 create，而 create 的引用校验
     * 只查库——INPUT 若排在它引用的 OUTPUT 之前，那一刻 OUTPUT 尚未落库，会被判成「引用的测点不存在」。
     * 引用严格单向（INPUT -> OUTPUT，不可能反向），所以按方向分组即可解开顺序依赖。
     *
     * <p>稳定排序，组内保持原相对顺序（报错点名、幂等推理都可预期）。direction 为空（非法）
     * 的测点留在非输出组原样下传，由 create 照常报「direction 不能为空」，不被排序掩盖。
     * 入参可能是不可变 List，先复制再排。
     */
    private List<MeasurementPointDTO> orderOutputsFirst(List<MeasurementPointDTO> points) {
        List<MeasurementPointDTO> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparingInt(
                (MeasurementPointDTO dto) -> dto.getDirection() == PointDirection.OUTPUT ? 0 : 1));
        return ordered;
    }

    private void upsertBusiness(BusinessSystemDTO dto) {
        if (dto.getBusinessName() == null || dto.getBusinessName().isBlank()) {
            dto.setBusinessName(dto.getBusinessId());
        }
        if (businessSystemService.exists(dto.getBusinessId())) {
            businessSystemService.update(dto.getBusinessId(), dto);
        } else {
            businessSystemService.create(dto);
        }
    }
}
