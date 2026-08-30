package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.dto.PointValueDTO;
import com.sdncustom.common.dto.WriteValueRequest;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.server.config.BusinessSystemMigration;
import com.sdncustom.server.service.BusinessSystemService;
import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.HistoryService;
import com.sdncustom.server.service.PointService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;
    private final ChannelService channelService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;
    private final BusinessSystemService businessSystemService;

    @GetMapping
    public ApiResponse<List<MeasurementPoint>> findAll(@RequestParam(required = false) String channelId,
                                                       @RequestParam(required = false) String businessId) {
        if (businessId != null) {
            List<MeasurementPoint> points = pointService.findByBusinessId(businessId);
            if (channelId != null) {
                points = points.stream()
                        .filter(p -> p.getBindings() != null && p.getBindings().stream()
                                .anyMatch(b -> channelId.equals(b.getChannelId())))
                        .toList();
            }
            return ApiResponse.success(points);
        }
        if (channelId != null) {
            return ApiResponse.success(pointService.findByChannelId(channelId));
        }
        return ApiResponse.success(pointService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<MeasurementPoint> findById(@PathVariable String id) {
        return ApiResponse.success(pointService.findById(id));
    }

    @PostMapping
    public ApiResponse<MeasurementPoint> create(@Valid @RequestBody MeasurementPointDTO dto) {
        return ApiResponse.success(pointService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<MeasurementPoint> update(@PathVariable String id, @Valid @RequestBody MeasurementPointDTO dto) {
        return ApiResponse.success(pointService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        pointService.delete(id);
        return ApiResponse.success();
    }

    /**
     * 给既有测点增加一条绑定（创建表单"关联既有测点"走这里）
     */
    @PostMapping("/{id}/bindings")
    public ApiResponse<MeasurementPoint> addBinding(@PathVariable String id, @Valid @RequestBody PointSourceDTO binding) {
        return ApiResponse.success(pointService.addBinding(id, binding.getChannelId(), binding.getAddress()));
    }

    @GetMapping("/{id}/value")
    public ApiResponse<PointValue> getValue(@PathVariable String id) {
        return ApiResponse.success(pointService.getValue(id));
    }

    @PutMapping("/{id}/value")
    public ApiResponse<Void> writeValue(@PathVariable String id, @Valid @RequestBody WriteValueRequest request,
                                        Authentication authentication) {
        log.info("User {} writing value to point {}: {}", authentication.getName(), id, request.getValue());
        pointService.writeValue(id, request.getValue());
        return ApiResponse.success();
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<PointHistory>> getHistory(
            @PathVariable String id,
            @RequestParam long startTime,
            @RequestParam long endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(historyService.queryHistory(id, startTime, endTime, page, size));
    }

    /**
     * 导出所有测点为 JSON 文件
     */
    @GetMapping("/export")
    public void exportPoints(HttpServletResponse response) throws Exception {
        List<MeasurementPoint> points = pointService.findAll();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename=points_export.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(response.getOutputStream(), points);
    }

    /**
     * 批量导入测点。新格式 bindings=[{channelId,address}]；兼容旧格式（channelId+address+additionalSources）。
     * businessId 缺失时落默认业务；引用的业务不存在时自动创建。
     */
    @PostMapping("/import")
    public ApiResponse<List<MeasurementPoint>> importPoints(@RequestBody List<Map<String, Object>> rawPoints) {
        List<MeasurementPointDTO> dtos = new ArrayList<>();
        for (Map<String, Object> pt : rawPoints) {
            MeasurementPointDTO dto = new MeasurementPointDTO();
            dto.setPointId(requireString(pt, "pointId"));
            dto.setBusinessId(resolveBusinessId(pt));
            dto.setPointName(requireString(pt, "pointName"));
            dto.setDataType(parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
            dto.setUnit(optionalString(pt, "unit"));
            dto.setWritable(optionalBoolean(pt, "writable", false));
            dto.setDeadband(optionalDouble(pt, "deadband", null));
            dto.setBindings(parseBindings(pt));
            businessSystemService.ensureExistsForImport(dto.getBusinessId());
            dtos.add(dto);
        }
        return ApiResponse.success(pointService.importPoints(dtos));
    }

    /** 解析业务归属：缺失或空时落默认业务（兼容旧格式导出） */
    private String resolveBusinessId(Map<String, Object> item) {
        String businessId = optionalString(item, "businessId");
        return businessId == null || businessId.isBlank() ? BusinessSystemMigration.DEFAULT_BUSINESS_ID : businessId;
    }

    private String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new BusinessException(400, "缺少必填字段: " + key);
        }
        return String.valueOf(value);
    }

    private String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean optionalBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Double optionalDouble(Map<String, Object> map, String key, Double defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, Object value, String field) {
        if (value == null) {
            throw new BusinessException(400, "缺少必填字段: " + field);
        }
        try {
            return Enum.valueOf(enumType, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "非法的 " + field + ": " + value);
        }
    }

    /**
     * 解析测点绑定：新格式 bindings=[{channelId,address},...]；兼容旧格式（channelId+address+additionalSources）。
     */
    @SuppressWarnings("unchecked")
    private List<PointSourceDTO> parseBindings(Map<String, Object> pt) {
        List<PointSourceDTO> bindings = new ArrayList<>();
        if (pt.containsKey("bindings")) {
            List<PointSourceDTO> parsed = parseBindingList(pt.get("bindings"));
            if (parsed != null) {
                bindings.addAll(parsed);
            }
            if (bindings.isEmpty()) {
                throw new BusinessException(400, "bindings 不能为空");
            }
            return bindings;
        }
        if (pt.containsKey("channelId")) {
            PointSourceDTO main = new PointSourceDTO();
            main.setChannelId(requireString(pt, "channelId"));
            main.setAddress(requireString(pt, "address"));
            bindings.add(main);
            List<PointSourceDTO> extra = parseBindingList(pt.get("additionalSources"));
            if (extra != null) {
                bindings.addAll(extra);
            }
        }
        if (bindings.isEmpty()) {
            throw new BusinessException(400, "测点缺少绑定");
        }
        return bindings;
    }

    @SuppressWarnings("unchecked")
    private List<PointSourceDTO> parseBindingList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new BusinessException(400, "'bindings' 必须是数组");
        }
        List<PointSourceDTO> sources = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "绑定项必须是对象");
            }
            Map<String, Object> src = (Map<String, Object>) item;
            PointSourceDTO dto = new PointSourceDTO();
            dto.setChannelId(requireString(src, "channelId"));
            dto.setAddress(requireString(src, "address"));
            sources.add(dto);
        }
        return sources;
    }
}
