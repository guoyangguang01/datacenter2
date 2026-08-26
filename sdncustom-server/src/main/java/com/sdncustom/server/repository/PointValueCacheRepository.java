package com.sdncustom.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.PointValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PointValueCacheRepository {

    private static final String KEY_PREFIX = "point:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(PointValue pointValue) {
        String key = KEY_PREFIX + pointValue.getPointId();
        try {
            String json = objectMapper.writeValueAsString(pointValue);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize PointValue: {}", pointValue.getPointId(), e);
        }
    }

    public Optional<PointValue> findByPointId(String pointId) {
        String key = KEY_PREFIX + pointId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PointValue.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize PointValue: {}", pointId, e);
            return Optional.empty();
        }
    }

    public List<PointValue> findByPointIds(Collection<String> pointIds) {
        List<String> keys = pointIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);
        if (jsonList == null) {
            return Collections.emptyList();
        }
        List<PointValue> result = new ArrayList<>();
        for (String json : jsonList) {
            if (json != null) {
                try {
                    result.add(objectMapper.readValue(json, PointValue.class));
                } catch (JsonProcessingException e) {
                    log.error("Failed to deserialize PointValue", e);
                }
            }
        }
        return result;
    }

    public void delete(String pointId) {
        String key = KEY_PREFIX + pointId;
        redisTemplate.delete(key);
    }
}
