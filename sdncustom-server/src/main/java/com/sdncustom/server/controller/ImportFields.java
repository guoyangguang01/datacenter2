package com.sdncustom.server.controller;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;

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

    /**
     * 解析单个测点对象。businessId 必填——不再回退默认业务。
     */
    static MeasurementPointDTO parsePoint(Map<String, Object> pt) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(requireString(pt, "pointId"));
        dto.setBusinessId(requireString(pt, "businessId"));
        dto.setPointName(requireString(pt, "pointName"));
        dto.setDataType(parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
        dto.setUnit(optionalString(pt, "unit"));
        dto.setDirection(parseEnum(PointDirection.class, pt.get("direction"), "direction"));
        dto.setReferencePointId(optionalString(pt, "referencePointId"));
        dto.setDeadband(optionalDouble(pt, "deadband", null));
        dto.setBindings(parseBindings(pt));
        return dto;
    }

    /** 测点绑定：只接受 bindings=[{channelId,address},...] */
    static List<PointSourceDTO> parseBindings(Map<String, Object> pt) {
        List<PointSourceDTO> bindings = parseBindingList(pt.get("bindings"));
        if (bindings == null || bindings.isEmpty()) {
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
