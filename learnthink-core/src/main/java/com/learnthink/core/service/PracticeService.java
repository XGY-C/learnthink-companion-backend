package com.learnthink.core.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnthink.common.dto.practice.AiGenerateSessionRequest;
import com.learnthink.common.dto.practice.CreatePracticeSessionRequest;
import com.learnthink.common.dto.practice.PracticeSessionDTO;
import com.learnthink.common.dto.practice.PracticeSessionSummaryDTO;
import com.learnthink.common.dto.practice.PracticeStatsDTO;
import com.learnthink.common.dto.practice.RecordItemAnswerRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 练习刷题服务：自己选题 / AI 智能组卷 / 练习记录
 */
public interface PracticeService {

    /** 自己选题：创建会话 */
    PracticeSessionDTO createSession(String userId, CreatePracticeSessionRequest req);

    /** AI 智能组卷：创建会话 */
    PracticeSessionDTO aiGenerateSession(String userId, AiGenerateSessionRequest req);

    /** 会话详情（含题目，校验归属） */
    PracticeSessionDTO getSession(String sessionId, String userId);

    /** 会话列表 */
    Page<PracticeSessionSummaryDTO> listSessions(String userId, String courseId, int page, int size);

    /** 两步法第二步：记录单题作答到会话题项（幂等） */
    void recordItemAnswer(String sessionId, String itemId, String userId, RecordItemAnswerRequest req);

    /** 结束会话：聚合统计 */
    void completeSession(String sessionId, String userId);

    /** 结束会话的 AI 评估（流式） */
    void evaluateSession(String sessionId, String userId, SseEmitter emitter);

    /** 练习核心统计 */
    PracticeStatsDTO getStats(String userId, String courseId);
}
