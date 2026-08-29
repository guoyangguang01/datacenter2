package com.sdncustom.server.repository;

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
        } catch (Exception e) {
            // Redis 故障时降级，不中断采集循环
            log.warn("Failed to save PointValue to cache: {}", pointValue.getPointId(), e);
        }
    }

    public void saveBatch(List<PointValue> pointValues) {
        if (pointValues.isEmpty()) {
            return;
        }
        try {
            Map<String, String> entries = new LinkedHashMap<>();
            for (PointValue pv : pointValues) {
                entries.put(KEY_PREFIX + pv.getPointId(), objectMapper.writeValueAsString(pv));
            }
            redisTemplate.opsForValue().multiSet(entries);
        } catch (Exception e) {
            log.warn("Failed to batch save {} PointValues to cache", pointValues.size(), e);
        }
    }

    public Optional<PointValue> findByPointId(String pointId) {
        String key = KEY_PREFIX + pointId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PointValue.class));
        } catch (Exception e) {
            log.warn("Failed to read PointValue from cache: {}", pointId, e);
            return Optional.empty();
        }
    }

    public List<PointValue> findByPointIds(Collection<String> pointIds) {
        try {
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
                    } catch (Exception e) {
                        log.warn("Failed to deserialize PointValue", e);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read PointValues from cache", e);
            return Collections.emptyList();
        }
    }

    public void delete(String pointId) {
        try {
            String key = KEY_PREFIX + pointId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Failed to delete PointValue from cache: {}", pointId, e);
        }
    }
}
