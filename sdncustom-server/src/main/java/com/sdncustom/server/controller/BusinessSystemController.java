package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.model.BusinessSystem;
import com.sdncustom.server.service.BusinessSystemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/businesses")
@RequiredArgsConstructor
public class BusinessSystemController {

    private final BusinessSystemService businessSystemService;

    @GetMapping
    public ApiResponse<List<BusinessSystem>> findAll() {
        return ApiResponse.success(businessSystemService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<BusinessSystem> findById(@PathVariable String id) {
        return ApiResponse.success(businessSystemService.findById(id));
    }

    @PostMapping
    public ApiResponse<BusinessSystem> create(@Valid @RequestBody BusinessSystemDTO dto) {
        return ApiResponse.success(businessSystemService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<BusinessSystem> update(@PathVariable String id, @Valid @RequestBody BusinessSystemDTO dto) {
        return ApiResponse.success(businessSystemService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        businessSystemService.delete(id);
        return ApiResponse.success();
    }
}
