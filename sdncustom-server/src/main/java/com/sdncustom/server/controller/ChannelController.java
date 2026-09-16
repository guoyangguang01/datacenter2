package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.PageResult;
import com.sdncustom.common.model.Channel;
import com.sdncustom.server.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    /**
     * 查询通道。默认返回全量数组；显式传 {@code size} 时返回 {@link PageResult} 分页包装。
     */
    @GetMapping
    public ApiResponse<?> findAll(@RequestParam(required = false) String businessId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        List<Channel> channels = businessId != null
                ? channelService.findByBusinessId(businessId)
                : channelService.findAll();
        return ApiResponse.success(PageResult.of(channels, page, size));
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
}
