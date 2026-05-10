package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.chat.*;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ProfileChatMapper profileChatMapper;
    private final ProfileMapper profileMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private static final String PROFILE_SYSTEM_PROMPT = """
        你是学思伴行（LearnThink Companion）的学习画像分析助手。你的任务是通过友好的对话，了解学生的学习背景和需求。

        你需要逐步了解以下信息（不要一次性问太多，每次1-2个问题）：
        1. 专业背景：什么专业？在学什么课程？当前进度？
        2. 知识基础：哪些知识点掌握得比较好？哪些比较薄弱？
        3. 学习目标：考试目标？具体想提升什么？
        4. 学习偏好：喜欢什么学习方式？（视频、代码实例、可视化、理论推导等）
        5. 时间安排：每天/每周能花多少时间学习？
        6. 兴趣方向：对哪些应用方向感兴趣？

        对话策略：
        - 每次只问1-2个问题，保持自然流畅
        - 根据学生的回答深入追问，而不是机械地切换话题
        - 当收集到足够信息（至少覆盖4个以上维度），在回复末尾加上 [PROFILE_READY]
        - 用中文交流，保持友好、鼓励的语气
        """;

    private static final String ANALYSIS_PROMPT = """
        根据以下对话记录，提取学生的学习画像。输出严格的 JSON（不要包含 markdown 标记）：

        {
          "dimensions": [
            {"key": "major_context", "label": "专业上下文", "layer": "auxiliary",
             "value": {"major": "专业名", "course": "课程名", "current_chapter": "当前章节"},
             "confidence": 0.9, "source": "explicit"},
            {"key": "knowledge_basis", "label": "知识基础", "layer": "core",
             "value": {"strong": ["强项1"], "weak": ["弱项1", "弱项2"]},
             "confidence": 0.85, "source": "explicit"},
            {"key": "learning_goal", "label": "学习目标", "layer": "core",
             "value": {"target": "目标描述", "deadline": "期限", "sub_goals": ["子目标"]},
             "confidence": 0.9, "source": "explicit"},
            {"key": "cognitive_style", "label": "认知偏好", "layer": "style",
             "value": {"style": ["偏好1"], "avoid": ["回避1"]},
             "confidence": 0.7, "source": "inferred"},
            {"key": "learning_pace", "label": "学习节奏", "layer": "style",
             "value": {"minutes_per_day": 60, "days_per_week": 5, "urgency": "medium"},
             "confidence": 0.8, "source": "explicit"},
            {"key": "interest_direction", "label": "兴趣方向", "layer": "auxiliary",
             "value": {"topics": ["兴趣领域"], "applications": ["应用场景"]},
             "confidence": 0.6, "source": "inferred"},
            {"key": "error_pattern", "label": "错误模式", "layer": "auxiliary",
             "value": {"tags": ["常见错误"]},
             "confidence": 0.7, "source": "inferred"}
          ],
          "summary": {
            "weak_top": ["最弱项1", "最弱项2", "最弱项3"],
            "style": ["偏好1", "偏好2"],
            "minutes_per_day": 60,
            "goal": "学习目标一句话总结"
          }
        }

        规则：
        - 仅基于对话中明确提到的信息填写，未提及的字段用空数组或合理默认值
        - confidence: explicit(用户明确说出)≥0.85, inferred(从对话推断)0.6-0.8
        - 所有 label 用中文
        """;

    @Override
    @Transactional
    public ChatStartResponse startChat(String userId, ChatStartRequest request) {
        // Check for existing unanalyzed chat session
        ProfileChat existing = findActiveSession(userId, request.getCourseId());
        if (existing != null) {
            List<ChatMessageDto> messages = parseMessages(existing.getMessagesJson());
            return buildStartResponse(existing.getId(), request.getCourseId(), messages);
        }

        // Create new session with opening system message
        String openingMessage = "你好！我来帮你了解你的学习情况，以便为你定制个性化的学习资源。你是什么专业的？目前在学什么课程呢？";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", openingMessage,
            "at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));

        ProfileChat chat = new ProfileChat();
        chat.setUserId(userId);
        chat.setCourseId(request.getCourseId());
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.insert(chat);

        List<ChatMessageDto> dtos = List.of(
            new ChatMessageDto("system", openingMessage, messages.get(0).get("at")));

        return buildStartResponse(chat.getId(), request.getCourseId(), dtos);
    }

    @Override
    @Transactional
    public ChatSendResponse sendMessage(String userId, String chatId, ChatSendRequest request) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());

        // Append user message
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));

        // Call LLM
        String aiResponse = callLLM(messages);
        String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "assistant", "content", aiResponse, "at", aiAt));

        // Save
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        // Check if profile is ready
        boolean profileReady = aiResponse.contains("[PROFILE_READY]");
        String profileVersionId = null;

        if (profileReady) {
            // Clean marker from stored message
            String cleanedResponse = aiResponse.replace("[PROFILE_READY]", "").trim();
            messages.set(messages.size() - 1,
                Map.of("role", "assistant", "content", cleanedResponse, "at", aiAt));
            chat.setMessagesJson(toJson(messages));
            profileChatMapper.updateById(chat);

            // Trigger profile analysis
            try {
                ProfileSummaryDto summary = analyzeProfile(userId, chatId);
                profileVersionId = summary.getProfileVersionId();
                chat.setProfileVersionId(profileVersionId);
                profileChatMapper.updateById(chat);
            } catch (Exception e) {
                log.error("Profile analysis failed for chat {}: {}", chatId, e.getMessage());
                profileReady = false;
            }
        }

        List<ChatMessageDto> newMessages = List.of(
            new ChatMessageDto("assistant", profileReady ?
                aiResponse.replace("[PROFILE_READY]", "").trim() : aiResponse, aiAt));

        return new ChatSendResponse(chatId, newMessages, profileReady, profileVersionId);
    }

    @Override
    public List<ChatMessageDto> getMessages(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
        }
        return parseMessages(chat.getMessagesJson());
    }

    @Override
    public List<ChatSessionDto> getSessions(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .orderByDesc(ProfileChat::getCreatedAt);

        return profileChatMapper.selectList(q).stream()
            .map(c -> {
                List<Map<String, String>> messages = parseRawMessages(c.getMessagesJson());
                return new ChatSessionDto(
                    c.getId(), c.getCourseId(), messages.size(),
                    c.getProfileVersionId() != null, c.getProfileVersionId(), c.getCreatedAt());
            })
            .toList();
    }

    @Override
    @Transactional
    public ProfileSummaryDto analyzeProfile(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());

        // Build conversation transcript for analysis
        StringBuilder transcript = new StringBuilder();
        for (var msg : messages) {
            String role = msg.get("role");
            if ("user".equals(role) || "assistant".equals(role) || "system".equals(role)) {
                transcript.append(role).append(": ").append(msg.get("content")).append("\n");
            }
        }

        // Call LLM for profile extraction
        ChatClient client = chatClientBuilder.build();
        String response = client.prompt()
            .messages(
                new SystemMessage(ANALYSIS_PROMPT),
                new UserMessage("对话记录：\n" + transcript)
            )
            .call()
            .content();

        // Parse the JSON response
        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            Map<String, Object> analysis = objectMapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dimensions = (List<Map<String, Object>>) analysis.get("dimensions");

            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");

            // Resolve profile version
            Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId)
                    .eq(Profile::getCourseId, chat.getCourseId()));

            int newVersion;
            if (profile == null) {
                profile = new Profile();
                profile.setUserId(userId);
                profile.setCourseId(chat.getCourseId());
                newVersion = 1;
                profile.setCurrentVersion(newVersion);
                profileMapper.insert(profile);
            } else {
                newVersion = profile.getCurrentVersion() + 1;
                profile.setCurrentVersion(newVersion);
                profileMapper.updateById(profile);
            }

            // Create profile version
            ProfileVersion pv = new ProfileVersion();
            pv.setUserId(userId);
            pv.setCourseId(chat.getCourseId());
            pv.setVersion(newVersion);
            pv.setDimensionsJson(objectMapper.writeValueAsString(dimensions));
            pv.setSummaryJson(objectMapper.writeValueAsString(summary));
            pv.setSourceChatIds(objectMapper.writeValueAsString(List.of(chatId)));
            profileVersionMapper.insert(pv);

            // Link chat to profile version
            chat.setProfileVersionId(pv.getId());
            profileChatMapper.updateById(chat);

            return new ProfileSummaryDto(pv.getId(), newVersion, summary,
                Map.of("dimensions", dimensions));

        } catch (Exception e) {
            log.error("Failed to parse profile analysis result: {}", e.getMessage());
            throw new RuntimeException("Profile analysis failed: " + e.getMessage());
        }
    }

    private String callLLM(List<Map<String, String>> messages) {
        ChatClient client = chatClientBuilder.build();
        List<Message> chatMessages = new ArrayList<>();
        chatMessages.add(new SystemMessage(PROFILE_SYSTEM_PROMPT));

        for (var msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                chatMessages.add(new UserMessage(content));
            } else if ("assistant".equals(role) || "system".equals(role)) {
                chatMessages.add(new AssistantMessage(content));
            }
        }

        return client.prompt().messages(chatMessages).call().content();
    }

    private ProfileChat findActiveSession(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNull(ProfileChat::getProfileVersionId)
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    @SuppressWarnings("unchecked")
    private List<ChatMessageDto> parseMessages(String json) {
        List<Map<String, String>> raw = parseRawMessages(json);
        return raw.stream()
            .map(m -> new ChatMessageDto(m.get("role"), m.get("content"), m.get("at")))
            .toList();
    }

    private List<Map<String, String>> parseRawMessages(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse messages JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    private ChatStartResponse buildStartResponse(String chatId, String courseId,
                                                   List<ChatMessageDto> messages) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        return new ChatStartResponse(chatId, courseId, messages,
            chat != null && chat.getProfileVersionId() != null,
            chat != null ? chat.getProfileVersionId() : null);
    }
}
