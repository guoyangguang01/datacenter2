package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.model.Channel;
import com.sdncustom.server.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

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
}
