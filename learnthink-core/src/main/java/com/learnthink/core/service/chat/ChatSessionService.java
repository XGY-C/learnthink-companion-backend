package com.learnthink.core.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.chat.*;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话 CRUD，基于 ChatSession + ChatMessage 新表。
 * <p>处理会话生命周期：创建、加载、列表、删除及活跃会话查找。</p>
 */
@Slf4j
@Service
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TaskMapper taskMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;

    public ChatSessionService(ChatSessionMapper chatSessionMapper, ChatMessageMapper chatMessageMapper,
                              TaskMapper taskMapper, ResourcePackMapper resourcePackMapper,
                              ResourceItemMapper resourceItemMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.taskMapper = taskMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.resourceItemMapper = resourceItemMapper;
    }

    /**
     * 懒创建会话 —— 若不存在则插入新会话记录。
     *
     * @param chatId   会话 ID
     * @param userId   用户 ID
     * @param courseId 课程 ID（可为 null）
     * @param type     模式类型（默认 "chat"）
     * @return 新创建的会话
     */
    public ChatSession lazyCreateSession(String chatId, String userId, String courseId, String type) {
        ChatSession s = new ChatSession();
        s.setId(chatId);
        s.setUserId(userId);
        s.setCourseId(courseId != null ? courseId : "");
        s.setType(type != null ? type : "chat");
        s.setStatus("active");
        s.setMessageCount(0);
        s.setCurrentRound(0);
        s.setCreatedAt(java.time.LocalDateTime.now());
        s.setUpdatedAt(java.time.LocalDateTime.now());
        chatSessionMapper.insert(s);
        log.info("Lazy-created chat session: chatId={}, userId={}, courseId={}, type={}",
                chatId, userId, courseId, s.getType());
        return s;
    }

    /**
     * 查找指定用户/课程的活跃（未绑定画像版本、非空）会话。
     *
     * @param userId   用户 ID
     * @param courseId 课程 ID
     * @return 活跃会话，不存在时返回 null
     */
    public ChatSession findActiveSessionWithMessages(String userId, String courseId) {
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        q.eq(ChatSession::getUserId, userId)
         .eq(ChatSession::getCourseId, courseId)
         .eq(ChatSession::getType, "chat")
         .isNull(ChatSession::getProfileVersionId)
         .orderByDesc(ChatSession::getUpdatedAt)
         .last("LIMIT 1");
        ChatSession s = chatSessionMapper.selectOne(q);
        if (s == null) return null;
        // 检查是否有消息
        Long count = chatMessageMapper.selectCount(
            new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, s.getId()));
        return count != null && count > 0 ? s : null;
    }

    /**
     * 查找指定用户/课程的最新非空会话。
     *
     * @param userId   用户 ID
     * @param courseId 课程 ID
     * @return 最新非空会话，不存在时返回 null
     */
    public ChatSession findLatestNonEmptySession(String userId, String courseId) {
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        q.eq(ChatSession::getUserId, userId)
         .eq(ChatSession::getCourseId, courseId)
         .eq(ChatSession::getType, "chat")
         .orderByDesc(ChatSession::getUpdatedAt)
         .last("LIMIT 1");
        ChatSession s = chatSessionMapper.selectOne(q);
        if (s == null) return null;
        Long count = chatMessageMapper.selectCount(
            new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, s.getId()));
        return count != null && count > 0 ? s : null;
    }

    /**
     * 获取用户（可选按课程过滤）的会话列表。
     *
     * @param userId   用户 ID
     * @param courseId 课程 ID（可为 null 或空，表示不过滤）
     * @return 会话 DTO 列表
     */
    public List<ChatSessionDto> getSessions(String userId, String courseId) {
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        q.eq(ChatSession::getUserId, userId);
        if (courseId != null && !courseId.isBlank()) {
            q.eq(ChatSession::getCourseId, courseId);
        }
        q.orderByDesc(ChatSession::getUpdatedAt);
        List<ChatSession> sessions = chatSessionMapper.selectList(q);

        return sessions.stream().map(s -> {
            ChatSessionDto dto = new ChatSessionDto();
            dto.setChatId(s.getId());
            dto.setCourseId(s.getCourseId());
            dto.setType(s.getType() != null ? s.getType() : "chat");
            dto.setTitle(s.getTitle() != null ? s.getTitle() : "新对话");
            dto.setMessageCount(s.getMessageCount() != null ? s.getMessageCount() : 0);
            dto.setProfileVersionId(s.getProfileVersionId());
            dto.setAnalyzed(s.getProfileVersionId() != null);
            dto.setCreatedAt(s.getCreatedAt() != null
                    ? s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
                    : null);
            dto.setLastMessageAt(s.getUpdatedAt() != null
                    ? s.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
                    : "");
            // 取最后一条消息预览，同时推断会话类型
            List<ChatMessage> lastMsgs = chatMessageMapper.selectBySessionId(s.getId());
            if (lastMsgs != null && !lastMsgs.isEmpty()) {
                ChatMessage last = lastMsgs.get(lastMsgs.size() - 1);
                String preview = last.getContent() != null ? last.getContent()
                        .replaceAll("\\*\\*", "").replaceAll("\\n", " ").trim() : "";
                dto.setLastMessagePreview(preview.length() > 30 ? preview.substring(0, 30) + "…" : preview);
                // 基于消息 mode / 元数据推断会话类型（lecture > plan > resource 优先级）
                boolean hasLecture = lastMsgs.stream().anyMatch(m ->
                    "lecture".equals(m.getMode()) || "smart".equals(m.getMode()) ||
                    (m.getMetadataJson() != null && m.getMetadataJson().contains("tutoring_session_id")));
                boolean hasPlan = lastMsgs.stream().anyMatch(m ->
                    "plan".equals(m.getMode()) ||
                    (m.getMetadataJson() != null && m.getMetadataJson().contains("plan_id")));
                boolean hasResource = lastMsgs.stream().anyMatch(m ->
                    "resource".equals(m.getMode()) ||
                    (m.getMetadataJson() != null && m.getMetadataJson().contains("task_id")));
                if (hasLecture) dto.setType("lecture");
                else if (hasPlan) dto.setType("plan");
                else if (hasResource) dto.setType("resource");
            }
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 设置会话标题。若 title 为 null 或 blank 则不更新。
     *
     * @param sessionId 会话 ID
     * @param title     标题（已清理过的纯文本）
     */
    public void setSessionTitle(String sessionId, String title) {
        if (title == null || title.isBlank()) return;
        chatSessionMapper.updateTitle(sessionId, title);
        log.info("Session title set: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 删除指定会话（仅当属于该用户时）。
     *
     * @param userId 用户 ID
     * @param chatId 会话 ID
     */
    public void deleteSession(String userId, String chatId) {
        ChatSession s = chatSessionMapper.selectById(chatId);
        if (s == null || !s.getUserId().equals(userId)) return;
        chatSessionMapper.deleteById(chatId);
        log.info("Deleted chat session: chatId={}, userId={}", chatId, userId);
    }
}
