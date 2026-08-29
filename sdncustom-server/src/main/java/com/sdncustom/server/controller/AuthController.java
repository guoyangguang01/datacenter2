package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.LoginRequest;
import com.sdncustom.common.dto.LoginResponse;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.server.config.SecurityProperties;
import com.sdncustom.server.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SecurityProperties securityProperties;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        boolean valid = constantTimeEquals(securityProperties.getUsername(), request.getUsername())
                && constantTimeEquals(securityProperties.getPassword(), request.getPassword());
        if (!valid) {
            log.warn("Login failed for username: {}", request.getUsername());
            throw new BusinessException(401, "用户名或密码错误");
        }

        String token = jwtService.generateToken(request.getUsername());
        long expiresAt = System.currentTimeMillis() + jwtService.getExpireMillis();
        log.info("User {} logged in", request.getUsername());
        return ApiResponse.success(new LoginResponse(token, request.getUsername(), expiresAt));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(Authentication authentication) {
        return ApiResponse.success(Map.of("username", authentication.getName()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
