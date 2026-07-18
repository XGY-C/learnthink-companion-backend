package com.learnthink.core.tutoring.history;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.TutoringSection;
import com.learnthink.core.domain.entity.TutoringSession;
import com.learnthink.core.domain.entity.TutoringSectionKpLink;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.TutoringSectionKpLinkMapper;
import com.learnthink.core.tutoring.repository.TutoringSectionMapper;
import com.learnthink.core.tutoring.repository.TutoringSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 辅导历史查询与统计服务。
 * 阶段五 H2 任务：提供辅导记录分页查询、统计聚合、会话详情接口。
 */
@Service
public class TutoringHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TutoringHistoryService.class);

    private final TutoringSessionMapper sessionMapper;
    private final TutoringSectionMapper sectionMapper;
    private final TutoringSectionKpLinkMapper kpLinkMapper;
    private final ChatMessageMapper chatMessageMapper;

    public TutoringHistoryService(TutoringSessionMapper sessionMapper,
                                   TutoringSectionMapper sectionMapper,
                                   TutoringSectionKpLinkMapper kpLinkMapper,
                                   ChatMessageMapper chatMessageMapper) {
        this.sessionMapper = sessionMapper;
        this.sectionMapper = sectionMapper;
        this.kpLinkMapper = kpLinkMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    /**
     * GET /tutoring/history?userId=&courseId=&page=1&size=20
     * 按用户 + 课程分页查询辅导历史。
     */
    public Map<String, Object> getHistory(String userId, String courseId, int page, int size) {
        QueryWrapper<TutoringSession> wrapper = new QueryWrapper<>();
        if (userId != null && !userId.isBlank()) wrapper.eq("user_id", userId);
        if (courseId != null && !courseId.isBlank()) wrapper.eq("course_id", courseId);
        wrapper.orderByDesc("created_at");

        // 计算总数
        Long total = sessionMapper.selectCount(new QueryWrapper<TutoringSession>()
                .eq(userId != null && !userId.isBlank(), "user_id", userId)
                .eq(courseId != null && !courseId.isBlank(), "course_id", courseId));

        // 分页
        int offset = Math.max(0, (page - 1) * size);
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        List<TutoringSession> sessions = sessionMapper.selectList(wrapper);

        List<Map<String, Object>> records = sessions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("chatId", s.getChatId());
            m.put("question", s.getQuestion());
            m.put("subMode", s.getSubMode());
            m.put("status", s.getStatus());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            m.put("completedAt", s.getCompletedAt() != null ? s.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);

            // 统计该会话的章节数
            Long sectionCount = sectionMapper.selectCount(
                    new QueryWrapper<TutoringSection>().eq("tutoring_session_id", s.getId()));
            m.put("sectionCount", sectionCount);
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
    }

    /**
     * GET /tutoring/sessions/{sessionId}/detail
     * 获取单次辅导会话的完整详情（含章节内容、KP 链接、关联聊天消息）。
     */
    public Map<String, Object> getSessionDetail(String sessionId) {
        TutoringSession session = sessionMapper.selectById(sessionId);
        if (session == null) return Map.of();

        // 查询章节
        QueryWrapper<TutoringSection> sectionWrapper = new QueryWrapper<>();
        sectionWrapper.eq("tutoring_session_id", sessionId).orderByAsc("sort_order", "created_at");
        List<TutoringSection> sections = sectionMapper.selectList(sectionWrapper);

        // 查询 KP 链接
        QueryWrapper<TutoringSectionKpLink> kpWrapper = new QueryWrapper<>();
        List<String> sectionIds = sections.stream().map(TutoringSection::getId).toList();
        if (!sectionIds.isEmpty()) {
            kpWrapper.in("section_id", sectionIds);
        }
        List<TutoringSectionKpLink> kpLinks = kpLinkMapper.selectList(kpWrapper);

        // 查询关联的聊天消息（如果有 chatId）
        List<Map<String, Object>> chatMessages = new ArrayList<>();
        if (session.getChatId() != null && !session.getChatId().isBlank()) {
            QueryWrapper<ChatMessage> msgWrapper = new QueryWrapper<>();
            msgWrapper.eq("session_id", session.getChatId())
                     .orderByAsc("seq_num");
            // 只取该辅导会话相关的消息（metadata 中包含 tutoring_session_id）
            List<ChatMessage> allMsgs = chatMessageMapper.selectList(msgWrapper);
            chatMessages = allMsgs.stream()
                    .filter(m -> m.getMetadataJson() != null
                            && m.getMetadataJson().contains(sessionId))
                    .map(m -> {
                        Map<String, Object> mm = new LinkedHashMap<>();
                        mm.put("messageId", m.getId());
                        mm.put("role", m.getRole());
                        mm.put("content", m.getContent());
                        mm.put("feedback", m.getFeedback());
                        mm.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
                        return mm;
                    }).toList();
        }

        // 组装响应
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("chatId", session.getChatId());
        result.put("userId", session.getUserId());
        result.put("courseId", session.getCourseId());
        result.put("question", session.getQuestion());
        result.put("subMode", session.getSubMode());
        result.put("status", session.getStatus());
        result.put("executionPlan", session.getExecutionPlan());
        result.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
        result.put("completedAt", session.getCompletedAt() != null ? session.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);

        // 章节列表
        List<Map<String, Object>> sectionList = sections.stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.getId());
            sm.put("sectionId", s.getSectionId());
            sm.put("title", s.getTitle());
            sm.put("sortOrder", s.getSortOrder());
            sm.put("content", s.getContent());
            sm.put("rating", s.getRating());
            sm.put("feedback", s.getFeedback());
            // 关联的 KP
            List<Map<String, Object>> kps = kpLinks.stream()
                    .filter(k -> k.getSectionId().equals(s.getId()))
                    .map(k -> {
                        Map<String, Object> km = new LinkedHashMap<>();
                        km.put("kpId", k.getKpId());
                        km.put("relevance", k.getRelevance());
                        return km;
                    }).toList();
            sm.put("kpLinks", kps);
            return sm;
        }).toList();
        result.put("sections", sectionList);

        // 关联聊天消息
        result.put("chatMessages", chatMessages);

        return result;
    }

    /**
     * GET /tutoring/stats?userId=&courseId=
     * 辅导统计聚合：总数、完成率、子模式分布、KP 覆盖、反馈统计。
     */
    public Map<String, Object> getStats(String userId, String courseId) {
        QueryWrapper<TutoringSession> wrapper = new QueryWrapper<>();
        if (userId != null && !userId.isBlank()) wrapper.eq("user_id", userId);
        if (courseId != null && !courseId.isBlank()) wrapper.eq("course_id", courseId);
        List<TutoringSession> sessions = sessionMapper.selectList(wrapper);

        int totalSessions = sessions.size();
        int completedSessions = (int) sessions.stream()
                .filter(s -> "completed".equals(s.getStatus()))
                .count();
        double avgCompletionRate = totalSessions > 0
                ? (double) completedSessions / totalSessions : 0.0;

        // 子模式分布
        Map<String, Map<String, Integer>> subModeBreakdown = new LinkedHashMap<>();
        for (TutoringSession s : sessions) {
            String mode = s.getSubMode() != null ? s.getSubMode() : "smart";
            subModeBreakdown.computeIfAbsent(mode, k -> {
                Map<String, Integer> m = new LinkedHashMap<>();
                m.put("total", 0);
                m.put("completed", 0);
                return m;
            });
            Map<String, Integer> counts = subModeBreakdown.get(mode);
            counts.put("total", counts.get("total") + 1);
            if ("completed".equals(s.getStatus())) {
                counts.put("completed", counts.get("completed") + 1);
            }
        }

        // KP 覆盖（聚合所有会话的 section 关联 KP）
        List<Map<String, Object>> kpCoverage = new ArrayList<>();
        List<String> sessionIds = sessions.stream().map(TutoringSession::getId).toList();
        if (!sessionIds.isEmpty()) {
            QueryWrapper<TutoringSection> sectionWrapper = new QueryWrapper<>();
            sectionWrapper.in("tutoring_session_id", sessionIds);
            List<TutoringSection> allSections = sectionMapper.selectList(sectionWrapper);
            List<String> sectionIdList = allSections.stream().map(TutoringSection::getId).toList();

            if (!sectionIdList.isEmpty()) {
                QueryWrapper<TutoringSectionKpLink> kpWrapper = new QueryWrapper<>();
                kpWrapper.in("section_id", sectionIdList);
                List<TutoringSectionKpLink> allKpLinks = kpLinkMapper.selectList(kpWrapper);

                // 按 kpId 聚合出现次数
                Map<String, Long> kpCountMap = allKpLinks.stream()
                        .filter(k -> k.getKpId() != null)
                        .collect(Collectors.groupingBy(TutoringSectionKpLink::getKpId, Collectors.counting()));

                kpCoverage = kpCountMap.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(20)
                        .map(e -> {
                            Map<String, Object> km = new LinkedHashMap<>();
                            km.put("kpId", e.getKey());
                            km.put("appearCount", e.getValue());
                            return km;
                        }).toList();
            }
        }

        // 反馈统计（从 chat_messages 中获取）
        Map<String, Long> feedbackStats = new LinkedHashMap<>();
        feedbackStats.put("totalLikes", 0L);
        feedbackStats.put("totalDislikes", 0L);
        try {
            QueryWrapper<ChatMessage> feedbackWrapper = new QueryWrapper<>();
            feedbackWrapper.in("feedback", "like", "dislike");
            if (userId != null && !userId.isBlank()) {
                feedbackWrapper.eq("user_id", userId);
            }
            List<ChatMessage> feedbackMsgs = chatMessageMapper.selectList(feedbackWrapper);
            long likes = feedbackMsgs.stream().filter(m -> "like".equals(m.getFeedback())).count();
            long dislikes = feedbackMsgs.stream().filter(m -> "dislike".equals(m.getFeedback())).count();
            feedbackStats.put("totalLikes", likes);
            feedbackStats.put("totalDislikes", dislikes);
        } catch (Exception e) {
            log.warn("Failed to compute feedback stats: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSessions", totalSessions);
        result.put("avgCompletionRate", Math.round(avgCompletionRate * 100.0) / 100.0);
        result.put("subModeBreakdown", subModeBreakdown);
        result.put("kpCoverage", kpCoverage);
        result.put("feedbackStats", feedbackStats);
        return result;
    }
}
