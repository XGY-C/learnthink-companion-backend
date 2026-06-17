package com.learnthink.core.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class ProfileSessionEndScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProfileSessionEndScheduler.class);

    private final ProfileChatMapper profileChatMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ProfileService profileService;

    @Value("${learnthink.profile.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    public ProfileSessionEndScheduler(ProfileChatMapper profileChatMapper,
                                      ProfileVersionMapper profileVersionMapper,
                                      ProfileService profileService) {
        this.profileChatMapper = profileChatMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.profileService = profileService;
    }

    @Scheduled(fixedRate = 60000)
    public void scanExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(sessionTimeoutMinutes);

        List<ProfileChat> expiredChats = profileChatMapper.selectList(
                new LambdaQueryWrapper<ProfileChat>()
                        .isNull(ProfileChat::getProfileVersionId)
                        .isNotNull(ProfileChat::getMessagesJson)
                        .ne(ProfileChat::getMessagesJson, "[]")
                        .lt(ProfileChat::getCreatedAt, cutoff)
                        .last("LIMIT 20"));

        for (ProfileChat chat : expiredChats) {
            try {
                log.info("Session expired, triggering handleChatEnd: chatId={}", chat.getId());
                var messages = parseMessages(chat.getMessagesJson());
                profileService.handleChatEnd(chat.getUserId(), chat.getCourseId(), chat.getId(), messages);

                ProfileVersion latestPv = profileVersionMapper.selectOne(
                        new LambdaQueryWrapper<ProfileVersion>()
                                .eq(ProfileVersion::getUserId, chat.getUserId())
                                .eq(ProfileVersion::getCourseId, chat.getCourseId())
                                .orderByDesc(ProfileVersion::getVersion)
                                .last("LIMIT 1"));
                if (latestPv != null) {
                    chat.setProfileVersionId(latestPv.getId());
                    profileChatMapper.updateById(chat);
                }
            } catch (Exception e) {
                log.error("Failed to process expired session chatId={}: {}", chat.getId(), e.getMessage());
            }
        }

        if (!expiredChats.isEmpty()) {
            log.info("Processed {} expired sessions", expiredChats.size());
        }
    }

    private List<java.util.Map<String, String>> parseMessages(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse messages JSON: {}", e.getMessage());
            return java.util.List.of();
        }
    }
}
