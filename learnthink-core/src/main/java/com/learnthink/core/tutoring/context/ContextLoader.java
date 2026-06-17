package com.learnthink.core.tutoring.context;

import com.learnthink.core.tutoring.domain.ReactState;
import com.learnthink.core.tutoring.domain.TutoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ContextLoader {
    private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public ContextLoader(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public TutoringContext load(String userId) {
        Map<String, Object> profileSnapshot = loadProfileSnapshot(userId);
        Map<String, Object> pathPosition = loadPathPosition(userId);
        List<Map<String, Object>> recentLearning = loadRecentLearning(userId);
        List<Map<String, Object>> recentTutoring = loadRecentTutoring(userId);

        return new TutoringContext(userId, null, null, profileSnapshot, pathPosition,
            recentLearning, recentTutoring, null, null);
    }

    private Map<String, Object> loadProfileSnapshot(String userId) {
        try {
            String json = redis.opsForValue().get("profile:snapshot:" + userId);
            if (json != null && !json.isEmpty()) {
                return parseJsonMap(json);
            }
        } catch (Exception e) {
            log.warn("Failed to load profile snapshot from Redis for user {}: {}", userId, e.getMessage());
        }
        // Fallback: query display_json from profile_versions
        try {
            var rows = jdbc.queryForList(
                "SELECT display_json FROM profile_versions WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
                userId);
            if (!rows.isEmpty() && rows.get(0).get("display_json") != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> display = mapper.readValue(rows.get(0).get("display_json").toString(), Map.class);
                Map<String, Object> result = new HashMap<>();

                // display_json.core.summary → knowledgeBaseSummary
                if (display.get("core") instanceof Map<?, ?> core) {
                    Object summary = core.get("summary");
                    result.put("knowledgeBaseSummary", summary != null ? summary.toString() : "");
                } else {
                    result.put("knowledgeBaseSummary", "");
                }

                // display_json.style.preference[0] → cognitiveStyle, style.pace → learningPace
                if (display.get("style") instanceof Map<?, ?> style) {
                    if (style.get("preference") instanceof List<?> prefs && !prefs.isEmpty()) {
                        result.put("cognitiveStyle", prefs.get(0).toString());
                    } else {
                        result.put("cognitiveStyle", "textual");
                    }
                    Object pace = style.get("pace");
                    result.put("learningPace", pace != null ? pace.toString() : "moderate");
                } else {
                    result.put("cognitiveStyle", "textual");
                    result.put("learningPace", "moderate");
                }

                // display_json.knowledge.weak → weakPoints
                if (display.get("knowledge") instanceof Map<?, ?> knowledge) {
                    Object weak = knowledge.get("weak");
                    result.put("weakPoints", weak instanceof List ? weak : List.of());
                } else {
                    result.put("weakPoints", List.of());
                }

                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to load profile snapshot from MySQL for user {}: {}", userId, e.getMessage());
        }
        return defaultProfileSnapshot();
    }

    private Map<String, Object> loadPathPosition(String userId) {
        try {
            String json = redis.opsForValue().get("path:position:" + userId);
            if (json != null && !json.isEmpty()) {
                return parseJsonMap(json);
            }
        } catch (Exception e) {
            log.debug("Failed to load path position from Redis: {}", e.getMessage());
        }
        return Map.of("chapterId", "", "chapterTitle", "", "sectionId", "", "progress", 0.0);
    }

    private List<Map<String, Object>> loadRecentLearning(String userId) {
        try {
            return jdbc.queryForList(
                "SELECT title, created_at FROM learning_events WHERE user_id = ? ORDER BY created_at DESC LIMIT 3",
                userId);
        } catch (Exception e) {
            log.debug("Failed to load recent learning: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadRecentTutoring(String userId) {
        try {
            return jdbc.queryForList(
                "SELECT question, status, created_at FROM tutoring_sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT 3",
                userId);
        } catch (Exception e) {
            log.debug("Failed to load recent tutoring: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> defaultProfileSnapshot() {
        Map<String, Object> map = new HashMap<>();
        map.put("knowledgeBaseSummary", "未知");
        map.put("cognitiveStyle", "textual");
        map.put("weakPoints", List.of());
        map.put("learningPace", "moderate");
        return map;
    }
}
