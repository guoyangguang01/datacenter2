package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PageResult;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointHistory;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.service.HistoryService;
import com.sdncustom.server.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;
    private final HistoryService historyService;

    /**
     * 查询测点。默认返回全量数组；显式传 {@code size} 时返回 {@link PageResult} 分页包装。
     */
    @GetMapping
    public ApiResponse<?> findAll(@RequestParam(required = false) String channelId,
                                  @RequestParam(required = false) String businessId,
                                  @RequestParam(required = false) PointDirection direction,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        List<MeasurementPoint> points;
        if (businessId != null) {
            points = pointService.findByBusinessId(businessId);
            if (channelId != null) {
                points = points.stream()
                        .filter(p -> p.getBindings() != null && p.getBindings().stream()
                                .anyMatch(b -> channelId.equals(b.getChannelId())))
                        .toList();
            }
        } else if (channelId != null) {
            points = pointService.findByChannelId(channelId);
        } else {
            points = pointService.findAll();
        }
        if (direction != null) {
            points = points.stream().filter(p -> p.getDirection() == direction).toList();
        }
        return ApiResponse.success(PageResult.of(points, page, size));
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

    /** 单次历史查询最多返回的条数，防止 size 被拉到很大 */
    private static final int MAX_HISTORY_PAGE_SIZE = 1000;

    @GetMapping("/{id}/history")
    public ApiResponse<List<PointHistory>> getHistory(
            @PathVariable String id,
            @RequestParam long startTime,
            @RequestParam long endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (startTime > endTime) {
            throw new BusinessException(400, "startTime 不能晚于 endTime");
        }
        if (page < 0) {
            throw new BusinessException(400, "page 不能为负");
        }
        if (size <= 0 || size > MAX_HISTORY_PAGE_SIZE) {
            throw new BusinessException(400, "size 必须在 1.." + MAX_HISTORY_PAGE_SIZE + " 之间");
        }
        return ApiResponse.success(historyService.queryHistory(id, startTime, endTime, page, size));
    }
}
