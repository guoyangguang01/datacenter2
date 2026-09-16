package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.server.service.BusinessSystemService;
import com.sdncustom.server.service.DataTransferService;
import com.sdncustom.server.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导入导出：承载业务与测点。
 * 导入 payload 可含可选的 {@code channels} 段——缺失的通道自动创建。
 */
@Slf4j
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataTransferController {

    private final BusinessSystemService businessSystemService;
    private final PointService pointService;
    private final DataTransferService dataTransferService;

    /**
     * 导出数据（业务 + 测点）。
     * 刻意不套 ApiResponse 信封——导出的文件要能原样再导入，回环是重点。
     */
    @GetMapping("/export")
    public Map<String, Object> export() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("businesses", businessSystemService.findAll());
        result.put("points", pointService.findAll());
        return result;
    }

    /**
     * 导入数据。payload 含 businesses、points，可选含 channels（自动创建缺失通道）。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importData(@RequestBody Map<String, Object> data,
                                                       Authentication authentication) {
        log.info("User {} importing data", authentication.getName());

        if (!data.containsKey("businesses") && !data.containsKey("points")) {
            throw new BusinessException(400, "缺少 'points'，或不含 'businesses' 的 payload");
        }

        List<ChannelDTO> channels = parseChannelDtos(data);
        ImportFields.ParseResult parsed = parsePoints(data);
        DataTransferService.ImportResult result =
                dataTransferService.importData(parseBusinesses(data), channels, parsed.points(), parsed.problems());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessCount", result.businessCount());
        body.put("channelCount", result.channelCount());
        body.put("pointCount", result.pointCount());
        return ApiResponse.success(body);
    }

    @SuppressWarnings("unchecked")
    private List<BusinessSystemDTO> parseBusinesses(Map<String, Object> data) {
        Object raw = data.get("businesses");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new BusinessException(400, "'businesses' 必须是数组");
        }
        List<BusinessSystemDTO> dtos = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "业务项必须是对象");
            }
            Map<String, Object> b = (Map<String, Object>) item;
            BusinessSystemDTO dto = new BusinessSystemDTO();
            dto.setBusinessId(ImportFields.requireString(b, "businessId"));
            dto.setBusinessName(ImportFields.optionalString(b, "businessName"));
            dto.setDescription(ImportFields.optionalString(b, "description"));
            dtos.add(dto);
        }
        return dtos;
    }

    @SuppressWarnings("unchecked")
    private List<ChannelDTO> parseChannelDtos(Map<String, Object> data) {
        Object raw = data.get("channels");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new BusinessException(400, "'channels' 必须是数组");
        }
        return ImportFields.parseChannels((List<?>) raw);
    }

    private ImportFields.ParseResult parsePoints(Map<String, Object> data) {
        Object raw = data.get("points");
        if (raw == null) {
            return new ImportFields.ParseResult(List.of(), List.of());
        }
        if (!(raw instanceof List)) {
            throw new BusinessException(400, "'points' 必须是数组");
        }
        return ImportFields.parsePoints((List<?>) raw);
    }

    /**
     * CSV 导入测点。businessId 和 channelId 由前端 UI 选择传入，CSV 只含测点属性列。
     */
    @PostMapping("/import-csv")
    public ApiResponse<Map<String, Object>> importCsv(
            @RequestParam String businessId,
            @RequestParam String channelId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        log.info("User {} importing CSV (business={}, channel={})", authentication.getName(), businessId, channelId);

        if (file.isEmpty()) {
            throw new BusinessException(400, "请上传 CSV 文件");
        }

        ImportFields.ParseResult parsed = ImportFields.parseCsv(file.getInputStream(), businessId, channelId);
        DataTransferService.ImportResult result =
                dataTransferService.importCsvPoints(businessId, channelId, parsed.points(), parsed.problems());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessCount", result.businessCount());
        body.put("channelCount", result.channelCount());
        body.put("pointCount", result.pointCount());
        return ApiResponse.success(body);
    }
}
