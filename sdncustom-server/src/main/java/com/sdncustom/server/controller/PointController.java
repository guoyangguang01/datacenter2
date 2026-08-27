package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointValueDTO;
import com.sdncustom.common.dto.WriteValueRequest;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.service.HistoryService;
import com.sdncustom.server.service.PointService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;
    private final HistoryService historyService;

    @GetMapping
    public ApiResponse<List<MeasurementPoint>> findAll(@RequestParam(required = false) String channelId) {
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

    @GetMapping("/{id}/value")
    public ApiResponse<PointValue> getValue(@PathVariable String id) {
        return ApiResponse.success(pointService.getValue(id));
    }

    @PutMapping("/{id}/value")
    public ApiResponse<Void> writeValue(@PathVariable String id, @Valid @RequestBody WriteValueRequest request) {
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
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(response.getOutputStream(), points);
    }

    /**
     * 批量导入测点
     */
    @PostMapping("/import")
    public ApiResponse<List<MeasurementPoint>> importPoints(@RequestBody List<MeasurementPointDTO> dtos) {
        return ApiResponse.success(pointService.importPoints(dtos));
    }
}
