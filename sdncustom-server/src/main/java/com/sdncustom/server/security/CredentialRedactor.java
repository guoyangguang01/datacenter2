package com.sdncustom.server.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 对 connectionConfig 等 JSON 配置中的凭据字段做脱敏，用于导出等外发场景
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialRedactor {

    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "credential");
    private static final String MASK = "******";

    private final ObjectMapper objectMapper;

    public String redact(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return configJson;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(configJson, new TypeReference<>() {});
            redactMap(map);
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("connectionConfig is not valid JSON, skip redaction");
            return configJson;
        }
    }

    @SuppressWarnings("unchecked")
    private void redactMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            if (SENSITIVE_KEYS.stream().anyMatch(keyLower::contains)) {
                entry.setValue(MASK);
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                redactMap((Map<String, Object>) nested);
            }
        }
    }
}
