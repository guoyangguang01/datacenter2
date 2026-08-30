package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.server.config.BusinessSystemMigration;
import com.sdncustom.server.security.CredentialRedactor;
import com.sdncustom.server.service.BusinessSystemService;
import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final PointService pointService;
    private final CredentialRedactor credentialRedactor;
    private final BusinessSystemService businessSystemService;

    @GetMapping
    public ApiResponse<List<Channel>> findAll(@RequestParam(required = false) String businessId) {
        if (businessId != null) {
            return ApiResponse.success(channelService.findByBusinessId(businessId));
        }
        return ApiResponse.success(channelService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Channel> findById(@PathVariable String id) {
        return ApiResponse.success(channelService.findById(id));
    }

    @PostMapping
    public ApiResponse<Channel> create(@Valid @RequestBody ChannelDTO dto) {
        return ApiResponse.success(channelService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<Channel> update(@PathVariable String id, @Valid @RequestBody ChannelDTO dto) {
        return ApiResponse.success(channelService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication authentication) {
        log.info("User {} deleting channel {}", authentication.getName(), id);
        channelService.delete(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/connect")
    public ApiResponse<Void> connect(@PathVariable String id, Authentication authentication) {
        log.info("User {} connecting channel {}", authentication.getName(), id);
        channelService.connect(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/disconnect")
    public ApiResponse<Void> disconnect(@PathVariable String id, Authentication authentication) {
        log.info("User {} disconnecting channel {}", authentication.getName(), id);
        channelService.disconnect(id);
        return ApiResponse.success();
    }

    /**
     * 导出所有业务、通道和测点（通道凭据脱敏）
     */
    @GetMapping("/export")
    public Map<String, Object> exportAll() {
        List<Channel> channels = channelService.findAll().stream()
                .map(ch -> {
                    Channel copy = new Channel();
                    BeanUtils.copyProperties(ch, copy);
                    copy.setConnectionConfig(credentialRedactor.redact(ch.getConnectionConfig()));
                    return copy;
                })
                .toList();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("businesses", businessSystemService.findAll());
        result.put("channels", channels);
        result.put("points", pointService.findAll());
        return result;
    }

    /**
     * 导入业务、通道和测点（组合格式）
     * 支持两种格式：
     * 1. {"businesses": [...], "channels": [...], "points": [...]} — 完整导出格式
     * 2. 通道数组 [ChannelDTO, ...] — 仅导入通道
     * 旧格式（无 businesses 段/无 businessId 字段）的数据统一落默认业务。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/import")
    public Map<String, Object> importAll(@RequestBody Map<String, Object> data, Authentication authentication) {
        log.info("User {} importing channels/points", authentication.getName());
        int channelCount = 0;
        int pointCount = 0;

        // 先导入业务（通道/测点创建时校验业务存在）
        if (data.containsKey("businesses")) {
            Object businessesObj = data.get("businesses");
            if (!(businessesObj instanceof List)) {
                throw new BusinessException(400, "'businesses' 必须是数组");
            }
            for (Object item : (List<Object>) businessesObj) {
                if (!(item instanceof Map)) {
                    throw new BusinessException(400, "业务项必须是对象");
                }
                upsertBusiness((Map<String, Object>) item);
            }
        }

        // 导入通道
        if (data.containsKey("channels")) {
            Object channelsObj = data.get("channels");
            if (!(channelsObj instanceof List)) {
                throw new BusinessException(400, "'channels' 必须是数组");
            }
            List<Map<String, Object>> channels = (List<Map<String, Object>>) channelsObj;
            for (Map<String, Object> ch : channels) {
                if (!(ch instanceof Map)) {
                    throw new BusinessException(400, "通道项必须是对象");
                }
                ChannelDTO dto = new ChannelDTO();
                dto.setChannelId(requireString(ch, "channelId"));
                dto.setBusinessId(resolveBusinessId(ch));
                dto.setChannelName(requireString(ch, "channelName"));
                dto.setProtocolType(parseEnum(ProtocolType.class, ch.get("protocolType"), "protocolType"));
                dto.setDirection(parseEnum(ChannelDirection.class, ch.get("direction"), "direction"));
                dto.setConnectionConfig(optionalString(ch, "connectionConfig"));
                dto.setAutoConnect(optionalBoolean(ch, "autoConnect", false));

                Channel existing = channelService.findByIdOrNull(dto.getChannelId());
                if (existing != null) {
                    channelService.update(dto.getChannelId(), dto);
                } else {
                    businessSystemService.ensureExistsForImport(dto.getBusinessId());
                    channelService.create(dto);
                }
                channelCount++;
            }
        }

        // 导入测点
        if (data.containsKey("points")) {
            Object pointsObj = data.get("points");
            if (!(pointsObj instanceof List)) {
                throw new BusinessException(400, "'points' 必须是数组");
            }
            List<Map<String, Object>> points = (List<Map<String, Object>>) pointsObj;
            List<MeasurementPointDTO> dtos = new java.util.ArrayList<>();
            for (Map<String, Object> pt : points) {
                if (!(pt instanceof Map)) {
                    throw new BusinessException(400, "测点项必须是对象");
                }
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
            pointService.importPoints(dtos);
            pointCount = dtos.size();
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("channelCount", channelCount);
        result.put("pointCount", pointCount);
        return result;
    }

    /** 解析业务归属：缺失或空时落默认业务（兼容旧格式导出） */
    private String resolveBusinessId(Map<String, Object> item) {
        String businessId = optionalString(item, "businessId");
        return businessId == null || businessId.isBlank() ? BusinessSystemMigration.DEFAULT_BUSINESS_ID : businessId;
    }

    /** upsert 导出文件中的业务项 */
    private void upsertBusiness(Map<String, Object> b) {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId(requireString(b, "businessId"));
        String name = optionalString(b, "businessName");
        dto.setBusinessName(name == null || name.isBlank() ? dto.getBusinessId() : name);
        dto.setDescription(optionalString(b, "description"));
        if (businessSystemService.exists(dto.getBusinessId())) {
            businessSystemService.update(dto.getBusinessId(), dto);
        } else {
            businessSystemService.create(dto);
        }
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

    @SuppressWarnings("unchecked")
    private List<PointSourceDTO> parseAdditionalSources(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new BusinessException(400, "'additionalSources' 必须是数组");
        }
        List<PointSourceDTO> sources = new java.util.ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "附加来源项必须是对象");
            }
            Map<String, Object> src = (Map<String, Object>) item;
            PointSourceDTO dto = new PointSourceDTO();
            dto.setChannelId(requireString(src, "channelId"));
            dto.setAddress(requireString(src, "address"));
            if (channelService.findByIdOrNull(dto.getChannelId()) == null) {
                throw new BusinessException(400, "附加来源通道不存在: " + dto.getChannelId());
            }
            sources.add(dto);
        }
        return sources;
    }

    /**
     * 解析测点绑定：新格式 bindings=[{channelId,address},...]；
     * 兼容旧格式（channelId+address，可含 additionalSources）合成 bindings。
     */
    @SuppressWarnings("unchecked")
    private List<PointSourceDTO> parseBindings(Map<String, Object> pt) {
        List<PointSourceDTO> bindings = new java.util.ArrayList<>();
        if (pt.containsKey("bindings")) {
            List<PointSourceDTO> parsed = parseAdditionalSources(pt.get("bindings"));
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
            List<PointSourceDTO> extra = parseAdditionalSources(pt.get("additionalSources"));
            if (extra != null) {
                bindings.addAll(extra);
            }
        }
        if (bindings.isEmpty()) {
            throw new BusinessException(400, "测点缺少绑定");
        }
        return bindings;
    }
}
