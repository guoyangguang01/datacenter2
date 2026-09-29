package com.sdncustom.server.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实时缓存仓库：Redis 故障必须降级（吞异常）而不是打断采集/落库链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointValueCacheRepository 测试")
class PointValueCacheRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PointValueCacheRepository repository;
    private final ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = new PointValueCacheRepository(redisTemplate, realMapper);
    }

    private static PointValue value(String pointId, Object value) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setValue(value);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_src");
        pv.setTimestamp(1727000000000L);
        return pv;
    }

    // ---------- save ----------

    @Test
    @DisplayName("save 序列化后按 point: 前缀写入")
    void saveSerializesWithKeyPrefix() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.save(value("p1", "hello"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), jsonCaptor.capture());
        assertEquals("point:p1", keyCaptor.getValue());

        PointValue roundTrip = realMapper.readValue(jsonCaptor.getValue(), PointValue.class);
        assertEquals("p1", roundTrip.getPointId());
        assertEquals("hello", roundTrip.getValue());
        assertEquals(PointQuality.GOOD, roundTrip.getQuality());
        assertEquals("ch_src", roundTrip.getSourceChannelId());
        assertEquals(1727000000000L, roundTrip.getTimestamp());
    }

    @Test
    @DisplayName("save 遇 Redis 故障降级吞异常，不打断调用方")
    void saveSwallowsRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString());

        assertDoesNotThrow(() -> repository.save(value("p1", 1)));
    }

    @Test
    @DisplayName("save 序列化失败同样降级（不让坏值炸掉采集循环）")
    void saveSwallowsSerializationFailure() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        PointValueCacheRepository repoWithFailingMapper =
                new PointValueCacheRepository(redisTemplate, failingMapper);

        assertDoesNotThrow(() -> repoWithFailingMapper.save(value("p_bad", 1)));
        verifyNoInteractions(redisTemplate);
    }

    // ---------- saveBatch ----------

    @Test
    @DisplayName("saveBatch 空列表直接返回，不触碰 Redis")
    void saveBatchEmptySkipsRedis() {
        repository.saveBatch(List.of());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("saveBatch 用 multiSet 一次写入全部条目（key 带前缀）")
    void saveBatchUsesMultiSet() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.saveBatch(List.of(value("p1", 1), value("p2", 2)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(valueOperations).multiSet(captor.capture());
        Map<String, String> entries = captor.getValue();
        assertEquals(2, entries.size());
        assertTrue(entries.containsKey("point:p1"));
        assertTrue(entries.containsKey("point:p2"));
        PointValue first = realMapper.readValue(entries.get("point:p1"), PointValue.class);
        PointValue second = realMapper.readValue(entries.get("point:p2"), PointValue.class);
        assertEquals("p1", first.getPointId());
        assertEquals("p2", second.getPointId());
        // LinkedHashMap：写入顺序与入参一致
        assertEquals(List.of("point:p1", "point:p2"), List.copyOf(entries.keySet()));
    }

    @Test
    @DisplayName("saveBatch 遇 Redis 故障降级吞异常")
    void saveBatchSwallowsRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).multiSet(anyMap());

        assertDoesNotThrow(() -> repository.saveBatch(List.of(value("p1", 1))));
    }

    // ---------- findByPointId ----------

    @Test
    @DisplayName("findByPointId 命中时反序列化返回")
    void findByPointIdHit() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("point:p1"))
                .thenReturn(realMapper.writeValueAsString(value("p1", 42.5)));

        Optional<PointValue> result = repository.findByPointId("p1");

        assertTrue(result.isPresent());
        assertEquals("p1", result.get().getPointId());
        assertEquals(42.5, ((Number) result.get().getValue()).doubleValue());
        assertEquals(PointQuality.GOOD, result.get().getQuality());
    }

    @Test
    @DisplayName("findByPointId 未命中返回 empty")
    void findByPointIdMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("point:missing")).thenReturn(null);

        assertTrue(repository.findByPointId("missing").isEmpty());
    }

    @Test
    @DisplayName("findByPointId 缓存内容损坏时返回 empty 而不是抛异常")
    void findByPointIdCorruptJsonReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("point:p1")).thenReturn("{not valid json");

        assertTrue(repository.findByPointId("p1").isEmpty());
    }

    @Test
    @DisplayName("findByPointId Redis 故障时返回 empty")
    void findByPointIdRedisFailureReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("point:p1")).thenThrow(new RuntimeException("redis down"));

        assertTrue(repository.findByPointId("p1").isEmpty());
    }

    // ---------- findByPointIds ----------

    @Test
    @DisplayName("findByPointIds 跳过缺失与损坏条目，只返回有效值")
    void findByPointIdsSkipsMissingAndCorrupt() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("point:p1", "point:p2", "point:p3")))
                .thenReturn(Arrays.asList(
                        realMapper.writeValueAsString(value("p1", 1)),
                        null,
                        "{corrupt"));

        List<PointValue> result = repository.findByPointIds(List.of("p1", "p2", "p3"));

        assertEquals(1, result.size());
        assertEquals("p1", result.get(0).getPointId());
    }

    @Test
    @DisplayName("findByPointIds multiGet 返回 null 时返回空列表")
    void findByPointIdsNullMultiGet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(null);

        assertTrue(repository.findByPointIds(List.of("p1")).isEmpty());
    }

    @Test
    @DisplayName("findByPointIds Redis 故障时返回空列表")
    void findByPointIdsRedisFailureReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenThrow(new RuntimeException("redis down"));

        assertTrue(repository.findByPointIds(List.of("p1")).isEmpty());
    }

    @Test
    @DisplayName("findByPointIds 空入参仍走 multiGet 调用点（由上层保证非空）")
    void findByPointIdsEmptyInput() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of())).thenReturn(List.of());

        assertTrue(repository.findByPointIds(List.of()).isEmpty());
    }

    // ---------- delete ----------

    @Test
    @DisplayName("delete 按前缀删键")
    void deleteUsesKeyPrefix() {
        repository.delete("p1");
        verify(redisTemplate).delete("point:p1");
    }

    @Test
    @DisplayName("delete 遇 Redis 故障降级吞异常")
    void deleteSwallowsRedisFailure() {
        when(redisTemplate.delete("point:p1")).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> repository.delete("p1"));
    }
}
