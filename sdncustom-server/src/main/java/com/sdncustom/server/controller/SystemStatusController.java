package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.SystemStatusDTO;
import com.sdncustom.server.monitor.SystemStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final SystemStatusService systemStatusService;

    @GetMapping("/status")
    public ApiResponse<SystemStatusDTO> status() {
        return ApiResponse.success(systemStatusService.getStatus());
    }
}
