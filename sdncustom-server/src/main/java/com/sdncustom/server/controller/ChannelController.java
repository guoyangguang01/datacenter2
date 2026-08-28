package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final PointService pointService;

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
    public ApiResponse<Void> delete(@PathVariable String id) {
        channelService.delete(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/connect")
    public ApiResponse<Void> connect(@PathVariable String id) {
        channelService.connect(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/disconnect")
    public ApiResponse<Void> disconnect(@PathVariable String id) {
        channelService.disconnect(id);
        return ApiResponse.success();
    }

    /**
     * 导出所有通道和测点
     */
    @GetMapping("/export")
    public Map<String, Object> exportAll() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("channels", channelService.findAll());
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
    public Map<String, Object> importAll(@RequestBody Map<String, Object> data) {
        int channelCount = 0;
        int pointCount = 0;

        // 导入通道
        if (data.containsKey("channels")) {
            List<Map<String, Object>> channels = (List<Map<String, Object>>) data.get("channels");
            for (Map<String, Object> ch : channels) {
                ChannelDTO dto = new ChannelDTO();
                dto.setChannelId((String) ch.get("channelId"));
                dto.setChannelName((String) ch.get("channelName"));
                dto.setProtocolType(com.sdncustom.common.model.enums.ProtocolType.valueOf((String) ch.get("protocolType")));
                dto.setDirection(com.sdncustom.common.model.enums.ChannelDirection.valueOf((String) ch.get("direction")));
                dto.setConnectionConfig((String) ch.get("connectionConfig"));
                dto.setAutoConnect(ch.containsKey("autoConnect") ? (Boolean) ch.get("autoConnect") : false);

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
            List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
            List<MeasurementPointDTO> dtos = new java.util.ArrayList<>();
            for (Map<String, Object> pt : points) {
                MeasurementPointDTO dto = new MeasurementPointDTO();
                dto.setPointId((String) pt.get("pointId"));
                dto.setPointName((String) pt.get("pointName"));
                dto.setChannelId((String) pt.get("channelId"));
                dto.setAddress((String) pt.get("address"));
                dto.setDataType(com.sdncustom.common.model.enums.PointDataType.valueOf((String) pt.get("dataType")));
                dto.setUnit((String) pt.get("unit"));
                dto.setWritable(pt.containsKey("writable") ? (Boolean) pt.get("writable") : false);
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
}
