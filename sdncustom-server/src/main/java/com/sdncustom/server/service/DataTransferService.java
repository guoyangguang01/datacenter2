package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
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
 * 数据导入：处理业务、通道（可选，自动创建缺失的）与测点。
 *
 * 通道存在性在写库前预检——缺通道时整体失败并点名缺失通道。
 * 测点引用（INPUT -> OUTPUT）同样在写库前预检。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferService {

    private final BusinessSystemService businessSystemService;
    private final ChannelService channelService;
    private final PointService pointService;
    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;

    public record ImportResult(int businessCount, int channelCount, int pointCount) {
    }

    @Transactional
    public ImportResult importData(List<BusinessSystemDTO> businesses, List<ChannelDTO> channels,
                                   List<MeasurementPointDTO> points, List<String> parseProblems) {
        // 1. 预检（不写库）：通道存在性 + 引用合法性
        //    先把 payload 中要创建的通道纳入"已知集合"，避免误报缺失
        validateChannelsExist(points, channels);
        validateReferencesExist(points, parseProblems);

        // 2. 导入业务
        for (BusinessSystemDTO dto : businesses) {
            upsertBusiness(dto);
        }

        // 3. 导入通道（自动创建缺失的，已存在的跳过）
        int channelCount = 0;
        for (ChannelDTO dto : channels) {
            businessSystemService.ensureExistsForImport(dto.getBusinessId());
            if (channelRepository.findById(dto.getChannelId()).isPresent()) {
                continue;
            }
            channelService.create(dto);
            channelCount++;
        }

        // 4. 确保测点引用的业务存在
        for (MeasurementPointDTO dto : points) {
            businessSystemService.ensureExistsForImport(dto.getBusinessId());
        }

        // 5. 导入测点
        pointService.importPoints(orderOutputsFirst(points));

        log.info("Imported {} business(es), {} channel(s) and {} point(s)",
                businesses.size(), channelCount, points.size());
        return new ImportResult(businesses.size(), channelCount, points.size());
    }

    /**
     * CSV 导入测点：businessId 和 channelId 由 UI 选择，自动创建业务/通道（如不存在）。
     */
    @Transactional
    public ImportResult importCsvPoints(String businessId, String channelId,
                                         List<MeasurementPointDTO> points, List<String> parseProblems) {
        // 1. 自动创建业务（如不存在）
        businessSystemService.ensureExistsForImport(businessId);

        // 2. 自动创建通道（如不存在）——用默认配置，用户可在 UI 上后续修改
        int channelCount = 0;
        if (channelRepository.findById(channelId).isEmpty()) {
            ChannelDTO chDto = new ChannelDTO();
            chDto.setChannelId(channelId);
            chDto.setBusinessId(businessId);
            chDto.setChannelName(channelId);
            // 这里没有外部系统代码可用，用 channelId 兜底，避免建出 code 全空的通道
            chDto.setCode(channelId);
            chDto.setProtocolType(com.sdncustom.common.model.enums.ProtocolType.CUSTOM_TCP);
            // ChannelDTO 的字段默认值是 true，必须显式关掉：这条通道没有 connectionConfig，
            // 自动连接只会在启动时拿空配置去连并失败。与 JSON 导入的默认值（false）保持一致。
            chDto.setAutoConnect(false);
            channelService.create(chDto);
            channelCount = 1;
        }

        // 3. 校验引用合法性
        validateReferencesExist(points, parseProblems);

        // 4. 导入测点
        pointService.importPoints(orderOutputsFirst(points));

        log.info("CSV imported {} point(s) for business={}, channel={}", points.size(), businessId, channelId);
        return new ImportResult(0, channelCount, points.size());
    }

    /** 一次性收集全部缺失通道再抛错，报错点名到「测点->通道」。payload 中待创建的通道也算已知。 */
    private void validateChannelsExist(List<MeasurementPointDTO> points, List<ChannelDTO> channels) {
        Set<String> knownChannels = new HashSet<>();
        for (ChannelDTO ch : channels) {
            knownChannels.add(ch.getChannelId());
        }
        List<String> missing = new ArrayList<>();
        for (MeasurementPointDTO dto : points) {
            String cid = dto.getChannelId();
            if (cid != null && !knownChannels.contains(cid) && channelRepository.findById(cid).isEmpty()) {
                missing.add(dto.getPointId() + "->" + cid);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "导入失败：以下测点关联的通道不存在，请在 channels 段中提供或先手动创建"
                    + "：" + String.join(", ", missing));
        }
    }

    /**
     * 引用预检：direction 必须存在，INPUT 的 referencePointId 必须能在「本次导入的测点集 ∪
     * 库中已有测点」里解析到且为 OUTPUT，并且 dataType 一致。
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
            if (inPayload.contains(refId)) {
                MeasurementPointDTO inPayloadTarget = points.stream()
                        .filter(p -> refId.equals(p.getPointId()))
                        .findFirst().orElse(null);
                if (inPayloadTarget == null || inPayloadTarget.getDirection() != PointDirection.OUTPUT) {
                    problems.add(who + " 引用的 " + refId + " 不是输出测点");
                } else if (inPayloadTarget.getDataType() != dto.getDataType()) {
                    problems.add(who + " 与 " + refId + " 数据类型不一致");
                } else if (sameChannel(dto, inPayloadTarget.getChannelId())) {
                    problems.add(who + " 引用了本通道的输出测点: " + refId);
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
            } else if (sameChannel(dto, target.getChannelId())) {
                problems.add(who + " 引用了本通道的输出测点: " + refId);
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(400, "导入失败：以下测点不合格 - " + String.join("；", problems));
        }
    }

    /**
     * 自引用禁令的导入侧实现：INPUT 不得引用与它同通道的 OUTPUT。
     * 与 {@code PointDirectionValidator} 同规则（导入走裸 Map 解析，不经 DTO 校验器，只能在这里查）。
     */
    private boolean sameChannel(MeasurementPointDTO inputDto, String outputChannelId) {
        return inputDto.getChannelId() != null && outputChannelId != null
                && outputChannelId.equals(inputDto.getChannelId());
    }

    private String describe(MeasurementPointDTO dto) {
        return dto.getPointId() == null || dto.getPointId().isBlank()
                ? "<缺少 pointId 的测点>"
                : dto.getPointId();
    }

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
