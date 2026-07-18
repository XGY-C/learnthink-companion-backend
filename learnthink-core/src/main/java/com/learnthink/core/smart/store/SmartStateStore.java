package com.learnthink.core.smart.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.smart.domain.SmartContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Smart 模式状态存储。
 * <p>基于 Redis，与 {@code ReactStateStore} 同一模式。
 * 每轮对话后更新，TTL 自动续期。</p>
 */
@Service
public class SmartStateStore {
    private static final Logger log = LoggerFactory.getLogger(SmartStateStore.class);
    private static final String KEY_PREFIX = "smart:state:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SmartStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String sessionId, SmartContext ctx) {
        try {
            String json = objectMapper.writeValueAsString(ctx);
            redis.opsForValue().set(KEY_PREFIX + sessionId, json, TTL);
            log.debug("Saved SmartContext for session {}: totalTurns={}", sessionId, ctx.totalTurns());
        } catch (Exception e) {
            log.error("Failed to serialize SmartContext for session {}", sessionId, e);
        }
    }

    public SmartContext load(String sessionId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, SmartContext.class);
            }
        } catch (Exception e) {
            log.warn("Failed to load SmartContext for session {}: {}", sessionId, e.getMessage());
        }
        return null;
    }

    public void delete(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
        log.debug("Deleted SmartContext for session {}", sessionId);
    }
}
