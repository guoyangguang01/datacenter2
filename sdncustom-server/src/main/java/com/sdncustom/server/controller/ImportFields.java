package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.common.model.enums.ProtocolType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 导入 payload（Map）的字段读取工具。原先 ChannelController 与 PointController
 * 各自复制了一份同名实现，收敛到此处。
 *
 * 通道的存在性不在这里校验：由 {@code DataTransferService} 预检统一给出
 * 可操作的报错（否则会先撞上这里的通用消息）。同理，解析出的一条条问题（见
 * {@link #parsePoints}）也不在这里拼成消息——汇总成单条报错是预检的职责。
 */
final class ImportFields {

    private ImportFields() {
    }

    /**
     * 表头别名 → 规范字段名。导出写的是中文表头（与测点管理页的列名一致），
     * 导入两种都认——这样导出的文件能原样回灌。
     *
     * <p>键一律小写，查表前把表头名也转小写，好让手改过的「测点id」同样命中。
     */
    private static final Map<String, String> CSV_HEADER_ALIASES = Map.ofEntries(
            Map.entry("测点id", "pointId"),
            Map.entry("测点名称", "pointName"),
            Map.entry("地址", "address"),
            Map.entry("数据类型", "dataType"),
            Map.entry("单位", "unit"),
            Map.entry("测点方向", "direction"),
            Map.entry("引用测点id", "referencePointId"),
            Map.entry("死区", "deadband"));

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
     * 测点方向：除枚举名（INPUT/OUTPUT）外，还认中文写法「输入/输出」——
     * 导出写的就是中文，不认的话自己的文件回灌不了。
     */
    private static PointDirection parseDirection(Object value, String field) {
        if (value == null) {
            throw new BusinessException(400, "缺少必填字段: " + field);
        }
        String text = String.valueOf(value).trim();
        return switch (text) {
            case "输入" -> PointDirection.INPUT;
            case "输出" -> PointDirection.OUTPUT;
            default -> parseEnum(PointDirection.class, text, field);
        };
    }

    /**
     * 批量解析结果：解析成功的测点 + 逐条记录的解析问题。
     * 问题不带整条报错的措辞——汇总成一条消息是 {@code DataTransferService} 预检的职责
     * （与 {@code validateChannelsExist} 同形）。
     */
    record ParseResult(List<MeasurementPointDTO> points, List<String> problems) {
    }

    /**
     * 逐条解析并**累积**问题，而不是在第一条不合格记录上抛出。
     *
     * <p>为什么必须有这一层：HTTP 路径（{@code POST /api/data/import}）解析的是裸 Map，
     * DTO 上的 bean validation 不生效，这里就是唯一的把关点。原先 {@code parsePoint} 直接抛
     * 「缺少必填字段: direction」——既点不出是哪条记录，也看不到文件里其它的问题；
     * 导入 100 条要试错 100 次（设计文档 §5.3）。
     *
     * <p>解析失败的记录不进 {@code points}：它没有可用的 DTO。问题字符串交给调用方汇总。
     */
    static ParseResult parsePoints(List<?> raw) {
        List<MeasurementPointDTO> points = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map)) {
                problems.add("<非对象的测点项> 测点项必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pt = (Map<String, Object>) item;
            try {
                points.add(parsePoint(pt));
            } catch (BusinessException e) {
                problems.add(describe(pt) + " " + e.getMessage());
            }
        }
        return new ParseResult(List.copyOf(points), List.copyOf(problems));
    }

    /**
     * 报错里的测点标识。手工编辑的导入文件可能整条缺 pointId，此时拼出裸 "null" 等于没点名，
     * 换成能让人定位到那条记录的占位说法（与 {@code DataTransferService.describe} 同一约定）。
     */
    static String describe(Map<String, Object> pt) {
        Object pointId = pt.get("pointId");
        return pointId == null || String.valueOf(pointId).isBlank()
                ? "<缺少 pointId 的测点>"
                : String.valueOf(pointId);
    }

    /** 解析通道数组（可选段，导入时自动创建缺失的通道） */
    static List<ChannelDTO> parseChannels(List<?> raw) {
        List<ChannelDTO> channels = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "通道项必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ch = (Map<String, Object>) item;
            ChannelDTO dto = new ChannelDTO();
            dto.setChannelId(requireString(ch, "channelId"));
            dto.setBusinessId(requireString(ch, "businessId"));
            dto.setChannelName(requireString(ch, "channelName"));
            // 手工构造 DTO，字段必须逐个搬；漏掉即静默丢失
            dto.setCode(optionalString(ch, "code"));
            dto.setProtocolType(parseEnum(ProtocolType.class, ch.get("protocolType"), "protocolType"));
            dto.setDirection(parseEnum(ChannelDirection.class, ch.get("direction"), "direction"));
            dto.setConnectionConfig(optionalString(ch, "connectionConfig"));
            dto.setAutoConnect(optionalBoolean(ch, "autoConnect", false));
            channels.add(dto);
        }
        return channels;
    }

    /**
     * 解析单个测点对象。businessId 必填——不再回退默认业务。
     * channelId 和 address 为必填字段（一对一关联通道）。
     */
    static MeasurementPointDTO parsePoint(Map<String, Object> pt) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(requireString(pt, "pointId"));
        dto.setBusinessId(requireString(pt, "businessId"));
        dto.setPointName(requireString(pt, "pointName"));
        dto.setChannelId(requireString(pt, "channelId"));
        dto.setAddress(requireString(pt, "address"));
        dto.setDataType(parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
        dto.setUnit(optionalString(pt, "unit"));
        dto.setDirection(parseDirection(pt.get("direction"), "direction"));
        dto.setReferencePointId(optionalString(pt, "referencePointId"));
        dto.setDeadband(optionalDouble(pt, "deadband", null));
        return dto;
    }

    /**
     * 解析 CSV 流为测点 DTO 列表。
     * businessId / channelId 由调用方传入（UI 选择），不在 CSV 中。
     *
     * <p>CSV 格式：首行为表头，逗号分隔，UTF-8 编码（兼容 BOM 头）。
     * 字段可加双引号以容纳逗号（见 {@link #splitCsvLine}），因此导出文件里的
     * 「名字带逗号」能原样回灌。
     * 必填列：pointId, pointName, address, dataType, direction
     * 可选列：unit, referencePointId, deadband
     */
    static ParseResult parseCsv(InputStream input, String businessId, String channelId) {
        List<MeasurementPointDTO> points = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                problems.add("CSV 文件为空");
                return new ParseResult(List.of(), List.copyOf(problems));
            }
            // 跳过 UTF-8 BOM
            if (headerLine.startsWith("﻿")) {
                headerLine = headerLine.substring(1);
            }
            Map<String, Integer> headerMap = parseCsvHeader(headerLine);
            if (headerMap.isEmpty()) {
                problems.add("CSV 表头为空或格式不对");
                return new ParseResult(List.of(), List.copyOf(problems));
            }

            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    points.add(parseCsvLine(line, headerMap, businessId, channelId, lineNo));
                } catch (BusinessException e) {
                    problems.add("第" + lineNo + "行: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add("读取 CSV 文件失败: " + e.getMessage());
        }
        return new ParseResult(List.copyOf(points), List.copyOf(problems));
    }

    /**
     * 按 RFC 4180 切分一行：字段可以用双引号包起来以容纳逗号，字段内的 {@code ""} 表示一个字面双引号。
     * 没加引号的字段与原先的 {@code split(",", -1)} 行为一致（空字段保留，交给 {@link #csvGet} 统一 trim），
     * 所以既有的、不带引号的 CSV 解析结果不变。
     *
     * <p>只处理行内引用：引号内跨行的换行不在支持范围内——本解析器按行读取，
     * 而导出的字段值来自单行表单，正常不会有换行。
     */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        // 本字段还没出现过非空白字符——此时遇到的引号才是「开启引用字段」，否则是字段内容的一部分
        boolean atFieldStart = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    current.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = false;
                    atFieldStart = false;
                }
            } else if (c == '"' && atFieldStart) {
                // 丢掉引号前的空白，与不加引号时 csvGet 的 trim 行为对齐
                current.setLength(0);
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
                atFieldStart = true;
            } else {
                current.append(c);
                if (!Character.isWhitespace(c)) {
                    atFieldStart = false;
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /** 解析表头行：列名 → 索引 */
    private static Map<String, Integer> parseCsvHeader(String line) {
        Map<String, Integer> map = new HashMap<>();
        String[] cols = splitCsvLine(line);
        for (int i = 0; i < cols.length; i++) {
            String name = cols[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            // 中文表头映射回规范字段名；英文列名（及不认识的列）原样通过
            map.put(CSV_HEADER_ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name), i);
        }
        return map;
    }

    /** 解析一行 CSV 数据为 MeasurementPointDTO */
    private static MeasurementPointDTO parseCsvLine(String line, Map<String, Integer> header,
                                                     String businessId, String channelId, int lineNo) {
        String[] cols = splitCsvLine(line);
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setBusinessId(businessId);
        dto.setChannelId(channelId);

        dto.setPointId(csvRequire(cols, header, "pointId", lineNo));
        dto.setPointName(csvRequire(cols, header, "pointName", lineNo));
        dto.setAddress(csvRequire(cols, header, "address", lineNo));
        dto.setDataType(parseEnum(PointDataType.class, csvGet(cols, header, "dataType"), "dataType（第" + lineNo + "行）"));
        dto.setDirection(parseDirection(csvGet(cols, header, "direction"), "direction（第" + lineNo + "行）"));
        dto.setUnit(csvOptional(cols, header, "unit"));
        dto.setReferencePointId(csvOptional(cols, header, "referencePointId"));
        String deadbandStr = csvOptional(cols, header, "deadband");
        if (deadbandStr != null && !deadbandStr.isEmpty()) {
            try {
                dto.setDeadband(Double.parseDouble(deadbandStr));
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "deadband 不是有效数字: " + deadbandStr);
            }
        }
        return dto;
    }

    private static String csvRequire(String[] cols, Map<String, Integer> header, String field, int lineNo) {
        String value = csvGet(cols, header, field);
        if (value == null || value.isEmpty()) {
            throw new BusinessException(400, "缺少必填列: " + field);
        }
        return value;
    }

    private static String csvGet(String[] cols, Map<String, Integer> header, String field) {
        Integer idx = header.get(field);
        if (idx == null) {
            return null;
        }
        if (idx >= cols.length) {
            return null;
        }
        String value = cols[idx].trim();
        return value.isEmpty() ? null : value;
    }

    private static String csvOptional(String[] cols, Map<String, Integer> header, String field) {
        return csvGet(cols, header, field);
    }
}
