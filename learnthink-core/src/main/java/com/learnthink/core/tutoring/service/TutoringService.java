package com.learnthink.core.tutoring.service;

import com.learnthink.common.dto.tutoring.GuidedAnswerRequest;
import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.core.tutoring.domain.ExecutionPlan;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;

public interface TutoringService {
    void startTutoring(TutoringStartRequest request, SseEmitter sseEmitter);
    void regenerateSection(String sessionId, String sectionId, String action,
                           String instruction, SseEmitter sseEmitter);
    void resumeGuidedDialogue(GuidedAnswerRequest request, SseEmitter sseEmitter);
    ExecutionPlan getPlan(String sessionId);
    Map<String, Object> getHistory(String sessionId);
    Map<String, Object> listSessions(int page, String courseId);
    void submitFeedback(String sessionId, String rating, String comment);
    void retryDiagram(String sessionId, String diagramId);

    /** Guided 模式开始时持久化用户消息到 chat_messages */
    void saveGuidedStartToChatMessages(String chatId, String userId, String question,
                                       String tutoringSessionId, String subMode,
                                       List<Map<String, Object>> reactThoughts);

    /** Guided 模式完成时更新 AI 消息内容 */
    void updateGuidedCompletionToChatMessages(String chatId, String userId,
                                              String tutoringSessionId, String guidedSummary);
}
