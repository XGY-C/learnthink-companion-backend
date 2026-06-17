package com.learnthink.core.tutoring.phase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.tutoring.domain.ReactState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class ReactStateStore {
    private static final Logger log = LoggerFactory.getLogger(ReactStateStore.class);
    private static final String KEY_PREFIX = "react:state:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ReactStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String sessionId, ReactState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(KEY_PREFIX + sessionId, json, TTL);
            log.debug("Saved ReactState for session {}: iteration {}", sessionId, state.iteration());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ReactState for session {}", sessionId, e);
        }
    }

    public ReactState load(String sessionId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, ReactState.class);
            }
        } catch (Exception e) {
            log.warn("Failed to load ReactState for session {}: {}", sessionId, e.getMessage());
        }
        return null;
    }

    public void delete(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
        log.debug("Deleted ReactState for session {}", sessionId);
    }
}
