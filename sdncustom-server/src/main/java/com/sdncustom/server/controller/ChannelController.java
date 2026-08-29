package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.server.security.CredentialRedactor;
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

    @GetMapping
    public ApiResponse<List<Channel>> findAll() {
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
     * 导出所有通道和测点（通道凭据脱敏）
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
        result.put("channels", channels);
        result.put("points", pointService.findAll());
        return result;
    }

    /**
     * 导入通道和测点（组合格式）
     * 支持两种格式：
     * 1. {"channels": [...], "points": [...]} — 同时导入通道和测点
     * 2. 通道数组 [ChannelDTO, ...] — 仅导入通道
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/import")
    public Map<String, Object> importAll(@RequestBody Map<String, Object> data, Authentication authentication) {
        log.info("User {} importing channels/points", authentication.getName());
        int channelCount = 0;
        int pointCount = 0;

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
                dto.setChannelName(requireString(ch, "channelName"));
                dto.setProtocolType(parseEnum(ProtocolType.class, ch.get("protocolType"), "protocolType"));
                dto.setDirection(parseEnum(ChannelDirection.class, ch.get("direction"), "direction"));
                dto.setConnectionConfig(optionalString(ch, "connectionConfig"));
                dto.setAutoConnect(optionalBoolean(ch, "autoConnect", false));

                Channel existing = channelService.findByIdOrNull(dto.getChannelId());
                if (existing != null) {
                    channelService.update(dto.getChannelId(), dto);
                } else {
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
                dto.setPointName(requireString(pt, "pointName"));
                String channelId = requireString(pt, "channelId");
                dto.setChannelId(channelId);
                dto.setAddress(requireString(pt, "address"));
                dto.setDataType(parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
                dto.setUnit(optionalString(pt, "unit"));
                dto.setWritable(optionalBoolean(pt, "writable", false));
                dto.setDeadband(optionalDouble(pt, "deadband", null));

                // 校验通道存在，避免导入的测点挂在不存在通道下
                if (channelService.findByIdOrNull(channelId) == null) {
                    throw new BusinessException(400, "通道不存在: " + channelId);
                }
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
}
