package com.learnthink.core.tutoring.service;

import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.core.tutoring.domain.ExecutionPlan;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;

public interface TutoringService {
    void startTutoring(TutoringStartRequest request, SseEmitter sseEmitter);
    void regenerateSection(String sessionId, String sectionId, String action,
                           String instruction, SseEmitter sseEmitter);
    ExecutionPlan getPlan(String sessionId);
    List<?> getHistory(String sessionId);
    Map<String, Object> listSessions(int page, String courseId);
    void submitFeedback(String sessionId, String rating, String comment);
    void retryDiagram(String sessionId, String diagramId);
}
