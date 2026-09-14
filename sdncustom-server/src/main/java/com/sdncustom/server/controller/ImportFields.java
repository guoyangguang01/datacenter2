package com.sdncustom.server.controller;

import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.server.config.BusinessSystemMigration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入 payload（Map）的字段读取工具。原先 ChannelController 与 PointController
 * 各自复制了一份同名实现，收敛到此处。
 *
 * 绑定通道的存在性不在这里校验：由 {@code DataTransferService} 预检统一给出
 * 可操作的报错（否则会先撞上这里的通用消息）。
 */
final class ImportFields {

    private ImportFields() {
    }

    static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new BusinessException(400, "缺少必填字段: " + key);
        }
        return String.valueOf(value);
    }

    static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static boolean optionalBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static Double optionalDouble(Map<String, Object> map, String key, Double defaultValue) {
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

    static <E extends Enum<E>> E parseEnum(Class<E> enumType, Object value, String field) {
        if (value == null) {
            throw new BusinessException(400, "缺少必填字段: " + field);
        }
        try {
            return Enum.valueOf(enumType, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "非法的 " + field + ": " + value);
        }
    }

    /** 业务归属：缺失或空时落默认业务 */
    static String resolveBusinessId(Map<String, Object> item) {
        String businessId = optionalString(item, "businessId");
        return businessId == null || businessId.isBlank()
                ? BusinessSystemMigration.DEFAULT_BUSINESS_ID
                : businessId;
    }

    /**
     * 测点绑定：新格式 bindings=[{channelId,address},...]；
     * 兼容旧格式（channelId+address，可含 additionalSources）合成 bindings。
     */
    static List<PointSourceDTO> parseBindings(Map<String, Object> pt) {
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
    private static List<PointSourceDTO> parseBindingList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new BusinessException(400, "绑定必须是数组");
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
