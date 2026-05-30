package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.profile.ProfileChatRequest;
import com.learnthink.common.dto.profile.ProfileChatResponse;
import com.learnthink.common.dto.profile.ProfileVersionItemDto;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.impl.BookInfoTool;
import com.learnthink.core.agent.impl.BookInfoToolCallback;
import com.learnthink.core.agent.impl.ConversationAgent;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.KpAnchorService;
import com.learnthink.core.service.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ProfileServiceImpl implements ProfileService {

    private static final List<String> DIM_ORDER = List.of(
        "major_context",
        "learning_goal",
        "knowledge_basis",
        "cognitive_style",
        "learning_pace",
        "error_pattern",
        "interest_direction"
    );

    private static final Map<String, String> DIM_LABELS = Map.of(
        "major_context", "专业上下文",
        "learning_goal", "学习目标",
        "knowledge_basis", "知识基础",
        "cognitive_style", "认知偏好",
        "learning_pace", "学习节奏",
        "error_pattern", "错误模式",
        "interest_direction", "兴趣方向"
    );

    private static final Map<String, String> DIM_LAYERS = Map.of(
        "major_context", "auxiliary",
        "learning_goal", "core",
        "knowledge_basis", "core",
        "cognitive_style", "style",
        "learning_pace", "style",
        "error_pattern", "auxiliary",
        "interest_direction", "auxiliary"
    );

    private final ProfileChatMapper profileChatMapper;
    private final ProfileMapper profileMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final CourseMapper courseMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final ConversationAgent conversationAgent;
    private final BookInfoTool bookInfoTool;
    private final KpAnchorService kpAnchorService;
    private final ExecutorService profileUpdateExecutor = Executors.newFixedThreadPool(2);

    // 独立的 KP 锚定线程池，避免阻塞画像更新线程
    private final ExecutorService kpAnchorExecutor = Executors.newFixedThreadPool(2);

    /** Per-(user+course) lock for safe concurrent profile writes. */
    private static final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> PROFILE_LOCKS = new ConcurrentHashMap<>();

    public static java.util.concurrent.locks.ReentrantLock getProfileLock(String userId, String courseId) {
        return PROFILE_LOCKS.computeIfAbsent(userId + "::" + courseId,
            k -> new java.util.concurrent.locks.ReentrantLock());
    }

    public ProfileServiceImpl(ProfileChatMapper profileChatMapper,
                              ProfileMapper profileMapper,
                              ProfileVersionMapper profileVersionMapper,
                              CourseMapper courseMapper,
                              @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                              ObjectMapper objectMapper,
                              PromptLoader promptLoader,
                              ConversationAgent conversationAgent,
                              BookInfoTool bookInfoTool,
                              KpAnchorService kpAnchorService) {
        this.profileChatMapper = profileChatMapper;
        this.profileMapper = profileMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.courseMapper = courseMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.conversationAgent = conversationAgent;
        this.bookInfoTool = bookInfoTool;
        this.kpAnchorService = kpAnchorService;
    }

    @Override
    @Transactional
    public ProfileChatResponse processChat(String userId, ProfileChatRequest request) {
        log.info("=== 画像对话处理开始 === userId={}, courseId={}", userId, request.getCourseId());
        
        ProfileChat chat = findLatestNonEmptySession(userId, request.getCourseId());
        if (chat == null) {
            log.info("创建新的对话会话");
            chat = new ProfileChat();
            chat.setId(UUID.randomUUID().toString());
            chat.setUserId(userId);
            chat.setCourseId(request.getCourseId());
            chat.setMessagesJson("[]");
            profileChatMapper.insert(chat);
        } else {
            log.info("使用现有对话会话: chatId={}", chat.getId());
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getMessage(), "at", now));
        log.info("用户消息: {}", request.getMessage().length() > 200 ? request.getMessage().substring(0, 200) + "..." : request.getMessage());
        log.info("用户消息已添加到对话历史，当前消息数: {}", messages.size());

        String currentProfileJson = buildCurrentProfileJson(userId, request.getCourseId());
        String systemPrompt = buildConversationSystemPrompt(userId, request.getCourseId());

        AgentContext ctx = AgentContext.builder(chat.getId(), userId)
            .courseId(request.getCourseId())
            .build();

        BookInfoToolCallback bookInfoCallback = new BookInfoToolCallback(bookInfoTool, request.getCourseId());
        ctx.put("book_info_tool", bookInfoCallback);

        log.info("调用 ConversationAgent 生成回复");
        var convResult = conversationAgent.execute(
            new ConversationAgent.ConversationInput(request.getCourseId(), messages, messages.size() / 2 + 1, systemPrompt),
            ctx
        );
        String reply = convResult.success()
            ? convResult.output().reply()
            : "抱歉，我现在无法生成回复，请稍后再试。";
        log.info("ConversationAgent 回复生成{}", convResult.success() ? "成功" : "失败");
        log.info("AI回复内容: {}", reply.length() > 200 ? reply.substring(0, 200) + "..." : reply);

        String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "assistant", "content", reply, "at", aiAt));
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);
        log.info("对话记录已保存到数据库");

        Map<String, Object> profileFull = getProfile(userId, request.getCourseId());
        Map<String, Object> viz = buildViz(profileFull);
        Integer currentVersion = extractProfileVersion(profileFull);
        log.info("当前画像版本: {}", currentVersion != null ? currentVersion : "无");

        // 异步更新画像
        List<Map<String, String>> historySnapshot = new ArrayList<>(messages);
        String chatId = chat.getId();
        String courseId = request.getCourseId();

        log.info("启动异步画像更新线程");
        CompletableFuture.runAsync(() -> {
            java.util.concurrent.locks.ReentrantLock lock = getProfileLock(userId, courseId);
            lock.lock();
            try {
                log.info("[异步] 开始提取画像增量 - chatId={}", chatId);
                String profileJson = buildCurrentProfileJson(userId, courseId);
                String recentHistory = buildRecentHistory(historySnapshot);
                
                Map<String, Object> delta = extractProfileDelta(profileJson, recentHistory);
                log.info("[异步] 画像增量提取完成，delta维度数: {}", delta.size());
                
                if (!hasMeaningfulDelta(delta)) {
                    log.info("[异步] 无有意义的画像变化，跳过更新");
                    return;
                }

                log.info("[异步] 检测到有意义的画像变化，开始合并");
                Map<String, Object> latestProfile = getProfile(userId, courseId);
                Map<String, Object> merged = mergeProfile(latestProfile, delta);
                
                ProfileChat latestChat = profileChatMapper.selectById(chatId);
                if (latestChat == null) {
                    log.warn("[异步] 对话记录不存在，取消更新 - chatId={}", chatId);
                    return;
                }
                
                int newVersion = persistProfileVersion(userId, courseId, merged, latestChat);
                log.info("[异步] === 画像更新成功 === 新版本: {}, chatId={}", newVersion, chatId);

                // v4.0: Trigger async KP anchoring
                ProfileVersion newPv = profileVersionMapper.selectOne(
                    new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .eq(ProfileVersion::getVersion, newVersion));
                if (newPv != null) {
                    triggerKpAnchoring(newPv.getId(), courseId);
                }
            } catch (Exception e) {
                log.error("[异步] 画像更新失败 - chatId={}: {}", chatId, e.getMessage(), e);
            } finally {
                lock.unlock();
            }
        }, profileUpdateExecutor);

        log.info("=== 画像对话处理完成 === 返回回复给用户");
        return new ProfileChatResponse(reply, currentVersion, profileFull, null, viz);
    }

    @Override
    public Map<String, Object> updateProfileDelta(String userId, String courseId, List<Map<String, String>> messages, String chatId) {
        java.util.concurrent.locks.ReentrantLock lock = getProfileLock(userId, courseId);
        lock.lock();
        try {
            String profileJson = buildCurrentProfileJson(userId, courseId);
            String recentHistory = buildRecentHistory(messages);
            Map<String, Object> delta = extractProfileDelta(profileJson, recentHistory);
            if (!hasMeaningfulDelta(delta)) {
                return Map.of();
            }
            Map<String, Object> latestProfile = getProfile(userId, courseId);
            Map<String, Object> merged = mergeProfile(latestProfile, delta);
            ProfileChat chat = profileChatMapper.selectById(chatId);
            if (chat == null) {
                chat = new ProfileChat();
                chat.setId(chatId);
                chat.setUserId(userId);
                chat.setCourseId(courseId);
                chat.setMessagesJson("[]");
            }
            int newVersion = persistProfileVersion(userId, courseId, merged, chat);
            // v4.0: Trigger async KP anchoring
            ProfileVersion newPv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                    .eq(ProfileVersion::getUserId, userId)
                    .eq(ProfileVersion::getCourseId, courseId)
                    .eq(ProfileVersion::getVersion, newVersion));
            if (newPv != null) {
                triggerKpAnchoring(newPv.getId(), courseId);
            }
            return getProfile(userId, courseId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, Object> getProfile(String userId, String courseId) {
        Profile profile = profileMapper.selectOne(
            new LambdaQueryWrapper<Profile>()
                .eq(Profile::getUserId, userId)
                .eq(Profile::getCourseId, courseId));
        if (profile == null || profile.getCurrentVersion() == null || profile.getCurrentVersion() == 0) {
            return Map.of();
        }

        ProfileVersion pv = profileVersionMapper.selectOne(
            new LambdaQueryWrapper<ProfileVersion>()
                .eq(ProfileVersion::getUserId, userId)
                .eq(ProfileVersion::getCourseId, courseId)
                .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
        if (pv == null || pv.getDimensionsJson() == null) return Map.of();

        List<Map<String, Object>> dimensions = parseDimensionsList(pv.getDimensionsJson());
        Map<String, Object> profileFull = new LinkedHashMap<>();
        profileFull.put("profile_id", pv.getId());
        profileFull.put("user_id", userId);
        profileFull.put("course_id", courseId);
        profileFull.put("version", pv.getVersion());
        profileFull.put("updated_at", pv.getCreatedAt() != null ? pv.getCreatedAt().toString() : "");
        profileFull.put("last_trigger", "chat");
        profileFull.put("dimensions", dimensions);
        return profileFull;
    }

    @Override
    public List<ProfileVersionItemDto> getProfileVersions(String userId, String courseId, int limit) {
        int safeLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        LambdaQueryWrapper<ProfileVersion> q = new LambdaQueryWrapper<ProfileVersion>()
            .eq(ProfileVersion::getUserId, userId)
            .eq(ProfileVersion::getCourseId, courseId)
            .orderByDesc(ProfileVersion::getVersion)
            .last("LIMIT " + safeLimit);

        return profileVersionMapper.selectList(q).stream()
            .map(pv -> new ProfileVersionItemDto(
                pv.getVersion(),
                pv.getCreatedAt() != null ? pv.getCreatedAt().toString() : "",
                "chat"
            ))
            .toList();
    }

    private Map<String, Object> extractProfileDelta(String currentProfileJson, String chatHistory) {
        log.debug("开始调用 LLM 提取画像增量");
        String prompt = promptLoader.get("chat/profile_delta")
            .replace("{current_profile_json}", currentProfileJson)
            .replace("{chat_history}", chatHistory);

        ChatClient client = chatClientBuilder.build();
        String response = client.prompt()
            .messages(new SystemMessage(prompt), new UserMessage("profile_delta"))
            .call()
            .content();

        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> delta = (Map<String, Object>) result.get("delta");
            if (delta == null) {
                log.debug("LLM 返回的 delta 为 null");
                return Map.of();
            }
            log.debug("LLM 返回的 delta 包含维度: {}", delta.keySet());
            return delta;
        } catch (Exception e) {
            log.warn("画像增量解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean hasMeaningfulDelta(Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) return false;
        for (Object val : delta.values()) {
            if (hasMeaningfulContent(val)) return true;
        }
        return false;
    }

    /** Recursively check if a value contains actual useful data (not just null/empty wrappers). */
    private boolean hasMeaningfulContent(Object val) {
        if (val == null) return false;
        if (val instanceof String s) return !s.isBlank();
        if (val instanceof List<?> l) return !l.isEmpty();
        if (val instanceof Map<?, ?> m) {
            if (m.isEmpty()) return false;
            for (Object entryVal : m.values()) {
                if (hasMeaningfulContent(entryVal)) return true;
            }
            return false;
        }
        if (val instanceof Number) return true;
        return true;
    }

    private Map<String, Object> mergeProfile(Map<String, Object> profileFull, Map<String, Object> delta) {
        Map<String, Object> base = profileFull == null ? Map.of() : profileFull;
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.put("updated_at", LocalDateTime.now().toString());
        merged.put("last_trigger", "chat");

        Map<String, Object> currentValues = extractDimensionValues(base);
        Map<String, Object> nextValues = new LinkedHashMap<>(currentValues);

        for (String key : DIM_ORDER) {
            Object deltaVal = delta.get(key);
            if (isEmptyValue(deltaVal)) continue;
            Object currentVal = nextValues.get(key);
            nextValues.put(key, mergeDimensionValue(currentVal, deltaVal));
        }

        merged.put("dimensions", buildDimensionsList(nextValues));
        return merged;
    }

    private Object mergeDimensionValue(Object currentVal, Object deltaVal) {
        if (currentVal == null) return deltaVal;
        if (!(currentVal instanceof Map) || !(deltaVal instanceof Map)) return deltaVal;

        @SuppressWarnings("unchecked")
        Map<String, Object> current = new LinkedHashMap<>((Map<String, Object>) currentVal);
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) deltaVal;

        Set<String> listFields = Set.of(
            "strong", "weak", "style", "avoid", "tags", "topics", "applications", "sub_goals"
        );

        for (var entry : delta.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (isEmptyValue(val)) continue;
            if (listFields.contains(key) && val instanceof List) {
                List<String> mergedList = mergeList(
                    asStringList(current.get(key)),
                    asStringList(val)
                );
                current.put(key, mergedList);
            } else {
                current.put(key, val);
            }
        }
        return current;
    }

    private List<String> mergeList(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null) set.addAll(a);
        if (b != null) set.addAll(b);
        List<String> merged = new ArrayList<>(set);
        return merged.size() > 10 ? merged.subList(0, 10) : merged;
    }

    private List<String> asStringList(Object val) {
        if (val instanceof List) {
            List<?> list = (List<?>) val;
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }

    private boolean isEmptyValue(Object val) {
        if (val == null) return true;
        if (val instanceof String s) return s.isBlank();
        if (val instanceof Map<?, ?> m) return m.isEmpty();
        if (val instanceof List<?> l) return l.isEmpty();
        return false;
    }

    private Map<String, Object> extractDimensionValues(Map<String, Object> profileFull) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object dimsObj = profileFull != null ? profileFull.get("dimensions") : null;
        if (!(dimsObj instanceof List<?> list)) return values;

        for (Object item : list) {
            if (item instanceof Map<?, ?> dim) {
                Object keyObj = dim.get("key");
                if (keyObj == null) continue;
                Object valueObj = dim.get("value");
                values.put(String.valueOf(keyObj), valueObj);
            }
        }
        return values;
    }

    private List<Map<String, Object>> buildDimensionsList(Map<String, Object> values) {
        List<Map<String, Object>> list = new ArrayList<>();
        String now = LocalDateTime.now().toString();
        for (String key : DIM_ORDER) {
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("key", key);
            dim.put("label", DIM_LABELS.getOrDefault(key, key));
            dim.put("layer", DIM_LAYERS.getOrDefault(key, "auxiliary"));
            Object value = values.get(key);
            dim.put("value", value != null ? value : Map.of());
            dim.put("confidence", value != null ? 0.7 : 0.0);
            dim.put("source", value != null ? "inferred" : "unknown");
            dim.put("updated_at", now);
            list.add(dim);
        }
        return list;
    }

    private int persistProfileVersion(String userId, String courseId, Map<String, Object> profileFull, ProfileChat chat) {
        log.info("[持久化] 开始保存画像版本 - userId={}, courseId={}", userId, courseId);
        
        Profile profile = profileMapper.selectOne(
            new LambdaQueryWrapper<Profile>()
                .eq(Profile::getUserId, userId)
                .eq(Profile::getCourseId, courseId));

        int newVersion;
        if (profile == null) {
            log.info("[持久化] 创建新的 profile 记录");
            profile = new Profile();
            profile.setUserId(userId);
            profile.setCourseId(courseId);
            newVersion = 1;
            profile.setCurrentVersion(newVersion);
            profileMapper.insert(profile);
        } else {
            newVersion = profile.getCurrentVersion() + 1;
            profile.setCurrentVersion(newVersion);
            profileMapper.updateById(profile);
            log.info("[持久化] 更新 profile 版本号: {} -> {}", profile.getCurrentVersion() - 1, newVersion);
        }

        ProfileVersion pv = new ProfileVersion();
        pv.setUserId(userId);
        pv.setCourseId(courseId);
        pv.setVersion(newVersion);
        pv.setDimensionsJson(toJson(profileFull.get("dimensions")));
        pv.setSummaryJson("{}");
        pv.setSourceChatIds(toJson(List.of(chat.getId())));
        profileVersionMapper.insert(pv);
        log.info("[持久化] profile_version 记录已插入，version={}", newVersion);

        chat.setProfileVersionId(pv.getId());
        profileChatMapper.updateById(chat);
        log.info("[持久化] profile_chat 已关联到 version_id={}", pv.getId());

        profileFull.put("profile_id", pv.getId());
        profileFull.put("user_id", userId);
        profileFull.put("course_id", courseId);
        profileFull.put("version", newVersion);
        profileFull.put("updated_at", pv.getCreatedAt() != null ? pv.getCreatedAt().toString() : "");
        profileFull.put("last_trigger", "chat");
        
        log.info("[持久化] === 画像版本持久化完成 === version={}", newVersion);
        return newVersion;
    }

    /**
     * v4.0: Trigger async KP anchoring after profile version is persisted.
     */
    private void triggerKpAnchoring(String profileVersionId, String courseId) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[KP锚定] 开始异步锚定 - pvId={}", profileVersionId);
                ProfileVersion pv = profileVersionMapper.selectById(profileVersionId);
                if (pv == null || pv.getDimensionsJson() == null) {
                    log.warn("[KP锚定] 画像版本不存在或无维度数据 - pvId={}", profileVersionId);
                    return;
                }
                List<Map<String, Object>> dimensions = parseDimensionsList(pv.getDimensionsJson());
                kpAnchorService.anchor(profileVersionId, courseId, dimensions);
                log.info("[KP锚定] 锚定完成 - pvId={}", profileVersionId);
            } catch (Exception e) {
                log.error("[KP锚定] 锚定失败 - pvId={}: {}", profileVersionId, e.getMessage(), e);
            }
        }, kpAnchorExecutor);
    }

    private Map<String, Object> buildViz(Map<String, Object> profileFull) {
        if (profileFull == null || profileFull.isEmpty()) return Map.of();
        Map<String, Object> values = extractDimensionValues(profileFull);

        Map<String, Integer> radar = new LinkedHashMap<>();
        radar.put("major_context", 70);

        Map<String, Object> goal = asMap(values.get("learning_goal"));
        int goalScore = goal.get("target") != null && !String.valueOf(goal.get("target")).isBlank() ? 80 : 0;
        if (goal.get("deadline") != null && !String.valueOf(goal.get("deadline")).isBlank()) {
            goalScore = Math.min(100, goalScore + 10);
        }
        radar.put("learning_goal", goalScore);

        Map<String, Object> kb = asMap(values.get("knowledge_basis"));
        int weakCount = asStringList(kb.get("weak")).size();
        radar.put("knowledge_basis", Math.max(20, 100 - weakCount * 15));

        Map<String, Object> style = asMap(values.get("cognitive_style"));
        int styleScore = asStringList(style.get("style")).size() * 20;
        if (asStringList(style.get("avoid")).isEmpty()) styleScore = Math.max(0, styleScore - 10);
        radar.put("cognitive_style", Math.min(100, styleScore));

        Map<String, Object> pace = asMap(values.get("learning_pace"));
        int minutes = toInt(pace.get("minutes_per_day"), 0);
        int paceScore = Math.min(100, (int) Math.round(minutes / 120.0 * 100));
        radar.put("learning_pace", paceScore);

        Map<String, Object> err = asMap(values.get("error_pattern"));
        int errCount = asStringList(err.get("tags")).size();
        radar.put("error_pattern", Math.max(20, 100 - errCount * 15));

        Map<String, Object> interest = asMap(values.get("interest_direction"));
        int interestScore = asStringList(interest.get("topics")).size() * 20;
        radar.put("interest_direction", Math.min(100, interestScore));

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("weak", asStringList(kb.get("weak")));
        tags.put("style", asStringList(style.get("style")));
        tags.put("pace", minutes > 0 ? minutes + "分钟/天" : "");

        Map<String, Object> viz = new LinkedHashMap<>();
        viz.put("radar", radar);
        viz.put("tags", tags);
        return viz;
    }

    private Integer extractProfileVersion(Map<String, Object> profileFull) {
        if (profileFull == null) return null;
        Object version = profileFull.get("version");
        if (version instanceof Number n) return n.intValue();
        if (version instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> asMap(Object val) {
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var entry : m.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private int toInt(Object val, int fallback) {
        if (val == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String buildCurrentProfileJson(String userId, String courseId) {
        Map<String, Object> profileFull = getProfile(userId, courseId);
        Object dims = profileFull.get("dimensions");
        if (dims == null) return "{}";
        try {
            return objectMapper.writeValueAsString(dims);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildRecentHistory(List<Map<String, String>> messages) {
        List<Map<String, String>> recent = messages.size() > 10
            ? messages.subList(messages.size() - 10, messages.size())
            : messages;
        try {
            return objectMapper.writeValueAsString(recent);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String buildConversationSystemPrompt(String userId, String courseId) {
        String template = promptLoader.get("chat/profile_chat");
        String courseContext = buildCourseContext(userId, courseId);
        String profileContext = buildProfileContext(userId, courseId);
        return template
            .replace("{course_context}", courseContext)
            .replace("{profile_context}", profileContext);
    }

    private String buildCourseContext(String userId, String courseId) {
        if (courseId == null) return "暂无课程信息（学生尚未选择课程）";

        String courseName = null;
        try {
            var course = courseMapper.selectById(courseId);
            if (course != null && course.getName() != null) {
                courseName = course.getName();
            }
        } catch (Exception e) {
            log.warn("Failed to look up course name: {}", e.getMessage());
        }

        String profileContext = "";
        if (userId != null) {
            try {
                Profile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId));
                if (profile != null && profile.getCurrentVersion() != null && profile.getCurrentVersion() > 0) {
                    ProfileVersion pv = profileVersionMapper.selectOne(
                        new LambdaQueryWrapper<ProfileVersion>()
                            .eq(ProfileVersion::getUserId, userId)
                            .eq(ProfileVersion::getCourseId, courseId)
                            .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
                    if (pv != null && pv.getDimensionsJson() != null) {
                        List<Map<String, Object>> dims = parseDimensionsList(pv.getDimensionsJson());
                        for (var dim : dims) {
                            if ("major_context".equals(dim.get("key"))) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> value = (Map<String, Object>) dim.get("value");
                                if (value != null) {
                                    String major = String.valueOf(value.getOrDefault("major", ""));
                                    String chapter = String.valueOf(value.getOrDefault("current_chapter", ""));
                                    if (!major.isBlank()) profileContext += "，专业: " + major;
                                    if (!chapter.isBlank()) profileContext += "，当前章节: " + chapter;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to build profile context: {}", e.getMessage());
            }
        }

        String chapterSummary = buildChapterSummary(courseId);

        if (courseName != null) {
            return "课程: " + courseName + chapterSummary + profileContext;
        }
        return "课程ID: " + courseId + chapterSummary + profileContext + "（需通过对话了解学生的专业和课程）";
    }

    private String buildChapterSummary(String courseId) {
        try {
            BookInfo bookInfo = bookInfoTool.resolveBookInfo(courseId);
            if (bookInfo == null || bookInfo.getToc() == null) return "";
            List<Map<String, Object>> tocList = objectMapper.readValue(bookInfo.getToc(),
                new TypeReference<List<Map<String, Object>>>() {});
            if (tocList == null || tocList.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tocList.size() && i < 20; i++) {
                Map<String, Object> node = tocList.get(i);
                String title = (String) node.getOrDefault("title", "");
                if (!title.isEmpty()) {
                    if (sb.isEmpty()) sb.append("\n章节概览：");
                    else sb.append(" | ");
                    sb.append(title);
                }
            }
            if (tocList.size() > 20) sb.append(" | ...");
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建章节概览失败: {}", e.getMessage());
            return "";
        }
    }

    private String buildProfileContext(String userId, String courseId) {
        if (userId == null || courseId == null) return "暂无画像数据，请从零开始了解学生";
        try {
            Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId)
                    .eq(Profile::getCourseId, courseId));
            if (profile == null || profile.getCurrentVersion() == null || profile.getCurrentVersion() == 0) {
                return "尚无画像数据。这是首次对话，请从基础信息开始了解学生。";
            }
            ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                    .eq(ProfileVersion::getUserId, userId)
                    .eq(ProfileVersion::getCourseId, courseId)
                    .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
            if (pv == null || pv.getDimensionsJson() == null) return "画像数据暂不可用";

            List<Map<String, Object>> dimList = parseDimensionsList(pv.getDimensionsJson());
            if (dimList == null || dimList.isEmpty()) return "尚无画像数据。请从基础信息开始了解。";

            StringBuilder sb = new StringBuilder();
            int covered = 0;
            for (var dim : dimList) {
                String key = String.valueOf(dim.get("key"));
                String label = String.valueOf(dim.getOrDefault("label", key));
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) dim.get("value");
                if (value != null && !value.isEmpty()) {
                    covered++;
                    sb.append("- ").append(label).append(": ").append(truncate(String.valueOf(value), 100)).append("\n");
                }
            }
            if (covered == 0) return "尚无有效画像数据。请从基础信息开始了解。";
            sb.insert(0, "已覆盖 " + covered + " 个维度：\n");
            if (covered < 7) sb.append("仍缺少 " + (7 - covered) + " 个维度的信息\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build profile context: {}", e.getMessage());
            return "画像数据读取失败，请继续对话了解学生";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    private ProfileChat findLatestNonEmptySession(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNotNull(ProfileChat::getMessagesJson)
         .ne(ProfileChat::getMessagesJson, "[]")
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
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

    private List<Map<String, Object>> parseDimensionsList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse dimensions JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
