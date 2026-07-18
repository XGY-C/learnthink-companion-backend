package com.learnthink.core.tutoring.phase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.tutoring.domain.GuidedDialogueHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class GuidedHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(GuidedHistoryStore.class);
    private static final String KEY_PREFIX = "guided:history:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public GuidedHistoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String sessionId, GuidedDialogueHistory history) {
        try {
            String json = objectMapper.writeValueAsString(history);
            redis.opsForValue().set(KEY_PREFIX + sessionId, json, TTL);
            log.debug("Saved GuidedDialogueHistory for session {}: step {}", sessionId, history.currentStepIndex());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize GuidedDialogueHistory for session {}", sessionId, e);
        }
    }

    public GuidedDialogueHistory load(String sessionId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, GuidedDialogueHistory.class);
            }
        } catch (Exception e) {
            log.warn("Failed to load GuidedDialogueHistory for session {}: {}", sessionId, e.getMessage());
        }
        return null;
    }

    public void delete(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
        log.debug("Deleted GuidedDialogueHistory for session {}", sessionId);
    }
}
