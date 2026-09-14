package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.server.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据导入：只处理业务与测点（含绑定），不碰通道——通道属于连接配置，
 * 由 {@code POST /api/channels/import} 单独管理。
 *
 * 因为测点靠 channelId 绑定通道，绑定的通道必须已存在；缺通道时整体失败，
 * 且校验发生在任何写库之前，避免留下半成品。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTransferService {

    private final BusinessSystemService businessSystemService;
    private final PointService pointService;
    private final ChannelRepository channelRepository;

    public record ImportResult(int businessCount, int pointCount) {
    }

    @Transactional
    public ImportResult importData(List<BusinessSystemDTO> businesses, List<MeasurementPointDTO> points) {
        validateChannelsExist(points);

        for (BusinessSystemDTO dto : businesses) {
            upsertBusiness(dto);
        }
        for (MeasurementPointDTO dto : points) {
            businessSystemService.ensureExistsForImport(dto.getBusinessId());
        }
        pointService.importPoints(points);

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
