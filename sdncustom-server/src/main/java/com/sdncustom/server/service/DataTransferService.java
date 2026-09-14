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
        return importData(businesses, points, List.of());
    }

    /**
     * @param parseProblems 解析层累积的问题（HTTP 路径的 {@code ImportFields.parsePoints} 产生：
     *                      该层的裸 Map 没有 bean validation，缺失/非法的 direction 等在那里被判出）。
     *                      在这里与引用问题合并成**一条**报错——用户一次就能看到文件里所有不合格的测点。
     */
    @Transactional
    public ImportResult importData(List<BusinessSystemDTO> businesses, List<MeasurementPointDTO> points,
                                   List<String> parseProblems) {
        validateChannelsExist(points);
        validateReferencesExist(points, parseProblems);

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
     * 引用预检：direction 必须存在，INPUT 的 referencePointId 必须能在「本次导入的测点集 ∪
     * 库中已有测点」里解析到且为 OUTPUT，并且 dataType 一致
     * （payload 内的引用也在此判定，不留给 create 的晚校验——那条路径报的是被引用方，点不出该改哪条记录）。
     * 与通道预检一样在任何写库之前完成。
     *
     * <p>之所以不复用 {@link PointDirectionValidator}：那个校验的是「单个测点对库」，
     * 而导入的 INPUT 可以合法引用同一份文件里、尚未落库的 OUTPUT。
     *
     * <p>{@code parseProblems} 是解析层攒下的问题（如 direction 缺失/非法，见
     * {@code ImportFields.parsePoints}）。它们与本层的问题**合并成一条**报错：这条路径上
     * 报错是用户唯一能看到的反馈，分两次抛会让他修完一条再撞见下一条。
     */
    private void validateReferencesExist(List<MeasurementPointDTO> points, List<String> parseProblems) {
        Set<String> inPayload = new HashSet<>();
        for (MeasurementPointDTO dto : points) {
            inPayload.add(dto.getPointId());
        }
        List<String> problems = new ArrayList<>(parseProblems);
        for (MeasurementPointDTO dto : points) {
            String who = describe(dto);
            if (dto.getDirection() == null) {
                // 方向缺失的测点在这里就点名，别让它掉到晚校验去报无主语的「direction 不能为空」
                problems.add(who + " 缺少 direction");
                continue;
            }
            if (dto.getDirection() != PointDirection.INPUT) {
                if (dto.getReferencePointId() != null && !dto.getReferencePointId().isBlank()) {
                    problems.add(who + " 方向为 " + dto.getDirection()
                            + "，不应带有 referencePointId: " + dto.getReferencePointId());
                }
                continue;
            }
            String refId = dto.getReferencePointId();
            if (refId == null || refId.isBlank()) {
                problems.add(who + " 缺少 referencePointId");
                continue;
            }
            // 本次 payload 内的引用：先按 payload 里的方向判定，避免与库中旧状态混淆
            if (inPayload.contains(refId)) {
                // refId 非空白，故用 refId.equals(...)：pointId 缺失（手工编辑的导入文件）时不会 NPE
                MeasurementPointDTO inPayloadTarget = points.stream()
                        .filter(p -> refId.equals(p.getPointId()))
                        .findFirst().orElse(null);
                if (inPayloadTarget == null || inPayloadTarget.getDirection() != PointDirection.OUTPUT) {
                    problems.add(who + " 引用的 " + refId + " 不是输出测点");
                } else if (inPayloadTarget.getDataType() != dto.getDataType()) {
                    problems.add(who + " 与 " + refId + " 数据类型不一致");
                }
                continue;
            }
            MeasurementPoint target = pointRepository.findById(refId).orElse(null);
            if (target == null) {
                problems.add(who + " 引用的 " + refId + " 不存在");
            } else if (target.getDirection() != PointDirection.OUTPUT) {
                problems.add(who + " 引用的 " + refId + " 不是输出测点");
            } else if (target.getDataType() != dto.getDataType()) {
                problems.add(who + " 与 " + refId + " 数据类型不一致");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(400, "导入失败：以下测点不合格 - " + String.join("；", problems));
        }
    }

    /**
     * 报错里的测点标识。手工编辑的导入文件可能整条缺 pointId，此时拼出裸 "null" 等于没点名，
     * 换成能让人定位到那条记录的占位说法。
     */
    private String describe(MeasurementPointDTO dto) {
        return dto.getPointId() == null || dto.getPointId().isBlank()
                ? "<缺少 pointId 的测点>"
                : dto.getPointId();
    }

    /**
     * 预检通过还不够：{@code PointService.importPoints} 按顺序逐条 create，而 create 的引用校验
     * 只查库——INPUT 若排在它引用的 OUTPUT 之前，那一刻 OUTPUT 尚未落库，会被判成「引用的测点不存在」。
     * 引用严格单向（INPUT -> OUTPUT，不可能反向），所以按方向分组即可解开顺序依赖。
     *
     * <p>稳定排序，组内保持原相对顺序（报错点名、幂等推理都可预期）。direction 为空的测点在预检里
     * 就被判不合格，走不到这里，故只有「OUTPUT 组」与「其余」两级。
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
