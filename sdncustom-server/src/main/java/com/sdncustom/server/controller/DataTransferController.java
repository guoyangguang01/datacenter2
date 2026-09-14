package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.PointDataType;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导入导出：只承载业务与测点，通道（连接配置）不在范围内。
 * 连接配置走 {@code POST /api/channels/import}。
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
     * 导入数据。绑定的通道必须已存在（数据导入不建通道）。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importData(@RequestBody Map<String, Object> data,
                                                       Authentication authentication) {
        log.info("User {} importing data", authentication.getName());

        if (!data.containsKey("businesses") && !data.containsKey("points")) {
            throw new BusinessException(400, "缺少 'points'，或不含 'businesses' 的 payload");
        }

        DataTransferService.ImportResult result =
                dataTransferService.importData(parseBusinesses(data), parsePoints(data));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessCount", result.businessCount());
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
    private List<MeasurementPointDTO> parsePoints(Map<String, Object> data) {
        Object raw = data.get("points");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new BusinessException(400, "'points' 必须是数组");
        }
        List<MeasurementPointDTO> dtos = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "测点项必须是对象");
            }
            Map<String, Object> pt = (Map<String, Object>) item;
            MeasurementPointDTO dto = new MeasurementPointDTO();
            dto.setPointId(ImportFields.requireString(pt, "pointId"));
            dto.setBusinessId(ImportFields.resolveBusinessId(pt));
            dto.setPointName(ImportFields.requireString(pt, "pointName"));
            dto.setDataType(ImportFields.parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
            dto.setUnit(ImportFields.optionalString(pt, "unit"));
            dto.setWritable(ImportFields.optionalBoolean(pt, "writable", false));
            dto.setDeadband(ImportFields.optionalDouble(pt, "deadband", null));
            dto.setBindings(ImportFields.parseBindings(pt));
            dtos.add(dto);
        }
        return dtos;
    }
}
