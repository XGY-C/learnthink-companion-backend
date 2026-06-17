package com.learnthink.core.tutoring.phase;

import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class ClarificationHandler {
    private static final Logger log = LoggerFactory.getLogger(ClarificationHandler.class);

    private final TutoringConfig config;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ClarificationHandler(TutoringConfig config) {
        this.config = config;
    }

    public ScheduledFuture<?> scheduleTimeout(String sessionId, TutoringEventEmitter emitter) {
        int timeoutSeconds = config.getClarification().getTimeoutSeconds();
        return scheduler.schedule(() -> {
            log.info("Clarification timeout for session {} after {}s", sessionId, timeoutSeconds);
            emitter.planClarifyTimeout(sessionId, timeoutSeconds);
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    public String resolvePrefillQuestion(String sessionId, String selectedOptionId,
                                          ReactStateStore reactStateStore) {
        var state = reactStateStore.load(sessionId);
        if (state == null || state.conversationHistory() == null) return null;

        for (var entry : state.conversationHistory()) {
            if ("architect".equals(entry.get("role"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) entry.get("output");
                if (output != null && output.containsKey("clarification")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clarification = (Map<String, Object>) output.get("clarification");
                    @SuppressWarnings("unchecked")
                    var options = (java.util.List<Map<String, Object>>) clarification.get("options");
                    if (options != null) {
                        for (var option : options) {
                            if (selectedOptionId.equals(option.get("id"))) {
                                return (String) option.get("prefillQuestion");
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
