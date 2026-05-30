package com.learnthink.core.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.chat.*;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话 CRUD，从 ChatServiceImpl 中提取
 * <p>处理会话生命周期：创建、加载、列表、删除及活跃会话查找。</p>
 */
@Slf4j
@Service
public class ChatSessionService {

    private final ProfileChatMapper profileChatMapper;
    private final TaskMapper taskMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;

    public ChatSessionService(ProfileChatMapper profileChatMapper, TaskMapper taskMapper,
                              ResourcePackMapper resourcePackMapper, ResourceItemMapper resourceItemMapper) {
        this.profileChatMapper = profileChatMapper;
        this.taskMapper = taskMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.resourceItemMapper = resourceItemMapper;
    }

    /**
     * 懒创建会话 —— 若不存在则插入新会话记录
     *
     * @param chatId 会话 ID
     * @param userId 用户 ID
     * @param courseId 课程 ID（可为 null）
     * @return 新创建的会话
     */
    public ProfileChat lazyCreateSession(String chatId, String userId, String courseId) {
        ProfileChat chat = new ProfileChat();
        chat.setId(chatId);
        chat.setUserId(userId);
        chat.setCourseId(courseId != null ? courseId : "");
        chat.setMessagesJson("[]");
        profileChatMapper.insert(chat);
        log.info("Lazy-created chat session: chatId={}, userId={}, courseId={}", chatId, userId, courseId);
        return chat;
    }

    /**
     * 查找指定用户/课程的活跃（未绑定画像版本、非空）会话
     *
     * @param userId 用户 ID
     * @param courseId 课程 ID
     * @return 活跃会话，不存在时返回 null
     */
    public ProfileChat findActiveSessionWithMessages(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNull(ProfileChat::getProfileVersionId)
         .isNotNull(ProfileChat::getMessagesJson)
         .ne(ProfileChat::getMessagesJson, "[]")
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    /**
     * 查找指定用户/课程的最新非空会话
     *
     * @param userId 用户 ID
     * @param courseId 课程 ID
     * @return 最新非空会话，不存在时返回 null
     */
    public ProfileChat findLatestNonEmptySession(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNotNull(ProfileChat::getMessagesJson)
         .ne(ProfileChat::getMessagesJson, "[]")
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    /**
     * 获取用户（可选按课程过滤）的会话列表
     *
     * @param userId 用户 ID
     * @param courseId 课程 ID（可为 null 或空，表示不过滤）
     * @return 会话 DTO 列表
     */
    public List<ChatSessionDto> getSessions(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId);
        if (courseId != null && !courseId.isBlank()) {
            q.eq(ProfileChat::getCourseId, courseId);
        }
        q.orderByDesc(ProfileChat::getCreatedAt);
        List<ProfileChat> chats = profileChatMapper.selectList(q);

        return chats.stream().map(chat -> {
            ChatSessionDto dto = new ChatSessionDto();
            dto.setChatId(chat.getId());
            dto.setCourseId(chat.getCourseId());
            dto.setTitle(deriveTitle(chat));
            dto.setProfileVersionId(chat.getProfileVersionId());
            dto.setLastMessageAt(chat.getCreatedAt() != null ?
                chat.getCreatedAt().toString() : "");
            dto.setMessageCount(countMessages(chat.getMessagesJson()));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 删除指定会话（仅当属于该用户时）
     *
     * @param userId 用户 ID
     * @param chatId 会话 ID
     */
    public void deleteSession(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) return;
        profileChatMapper.deleteById(chatId);
        log.info("Deleted chat session: chatId={}, userId={}", chatId, userId);
    }

    /**
     * 从会话的最后一条用户消息推导标题
     *
     * @param chat 会话对象
     * @return 标题字符串（最长 30 字+省略号），无有效消息时返回默认值
     */
    String deriveTitle(ProfileChat chat) {
        String json = chat.getMessagesJson();
        if (json == null || json.isBlank() || "[]".equals(json)) return "新对话";
        try {
            List<Map<String, String>> msgs = parseRawMessages(json);
            for (int i = msgs.size() - 1; i >= 0; i--) {
                String role = msgs.get(i).get("role");
                String content = msgs.get(i).get("content");
                if ("user".equals(role) && content != null && !content.isBlank()) {
                    return content.length() > 30 ? content.substring(0, 30) + "…" : content;
                }
            }
        } catch (Exception ignored) {}
        return "对话记录";
    }

    /**
     * 统计 JSON 消息数组中的消息条数
     *
     * @param json 消息 JSON 字符串
     * @return 消息条数，无效 JSON 时返回 0
     */
    int countMessages(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            List<Map<String, String>> msgs = parseRawMessages(json);
            return msgs.size();
        } catch (Exception ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseRawMessages(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
