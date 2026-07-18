package com.learnthink.core.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.ChatSession;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.ChatSessionMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定时扫描超时会话并触发画像分析。
 * <p>
 * 已从 ProfileChat 迁移至 ChatSession + ChatMessage。
 */
@Component
public class ProfileSessionEndScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProfileSessionEndScheduler.class);

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ProfileService profileService;

    @Value("${learnthink.profile.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    public ProfileSessionEndScheduler(ChatSessionMapper chatSessionMapper,
                                      ChatMessageMapper chatMessageMapper,
                                      ProfileVersionMapper profileVersionMapper,
                                      ProfileService profileService) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.profileService = profileService;
    }

    @Scheduled(fixedRate = 60000)
    public void scanExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(sessionTimeoutMinutes);

        // 查询过期会话：profile_version_id IS NULL 且 message_count > 0 且创建时间超过阈值
        // 排除已归档的会话（如 userId 为 null 被标记为 archived 的）
        List<ChatSession> expiredSessions = chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .isNull(ChatSession::getProfileVersionId)
                        .gt(ChatSession::getMessageCount, 0)
                        .lt(ChatSession::getCreatedAt, cutoff)
                        .ne(ChatSession::getStatus, "archived")
                        .last("LIMIT 20"));

        for (ChatSession session : expiredSessions) {
            try {
                if (session.getUserId() == null || session.getUserId().isBlank()) {
                    log.warn("Session {} has null userId, marking as processed and skipping", session.getId());
                    session.setStatus("archived");
                    chatSessionMapper.updateById(session);
                    continue;
                }

                log.info("Session expired, triggering handleChatEnd: chatId={}", session.getId());

                // 从 chat_messages 加载消息
                List<ChatMessage> msgs = chatMessageMapper.selectBySessionId(session.getId());
                if (msgs == null || msgs.isEmpty()) {
                    log.info("expired session {} has no messages, skipping", session.getId());
                    continue;
                }
                List<Map<String, String>> messages = msgs.stream().map(m -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    map.put("at", m.getCreatedAt() != null ? m.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : "");
                    return map;
                }).collect(Collectors.toList());

                profileService.handleChatEnd(session.getUserId(), session.getCourseId(),
                    session.getId(), messages);

                // 标注已处理的 session
                ProfileVersion latestPv = profileVersionMapper.selectOne(
                        new LambdaQueryWrapper<ProfileVersion>()
                                .eq(ProfileVersion::getUserId, session.getUserId())
                                .eq(ProfileVersion::getCourseId, session.getCourseId())
                                .orderByDesc(ProfileVersion::getVersion)
                                .last("LIMIT 1"));
                if (latestPv != null) {
                    session.setProfileVersionId(latestPv.getId());
                    chatSessionMapper.updateById(session);
                }
            } catch (Exception e) {
                log.error("Failed to process expired session chatId={}: {}",
                    session.getId(), e.getMessage());
            }
        }

        if (!expiredSessions.isEmpty()) {
            log.info("Processed {} expired sessions", expiredSessions.size());
        }
    }
}
