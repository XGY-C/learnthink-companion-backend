package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.profile.BehaviorAccumulatorDto;
import com.learnthink.common.dto.profile.PendingConfirmationDto;
import com.learnthink.common.dto.profile.ProfileMdSet;
import com.learnthink.common.dto.profile.ProfileVersionItemDto;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.ProfileService;
import com.learnthink.core.service.profile.ProfileBehaviorAccumulatorService;
import com.learnthink.core.service.profile.ProfileSignalService;
import com.learnthink.core.service.profile.ProfileStep1Filter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProfileServiceImpl implements ProfileService {

    private static final int STEP1_RETRY_COUNT = 2;
    private static final long STEP1_RETRY_BASE_MS = 1000;
    private static final int MAX_VERSION_RETRIES = 3;

    private final ConcurrentHashMap<String, ReentrantLock> profileLocks = new ConcurrentHashMap<>();

    private final ProfileMapper profileMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ProfileChatMapper profileChatMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final ProfileStep1Filter step1Filter;
    private final ProfileSignalService signalService;
    private final ProfileBehaviorAccumulatorService behaviorAccumulatorService;

    public ProfileServiceImpl(ProfileMapper profileMapper,
                              ProfileVersionMapper profileVersionMapper,
                              ProfileChatMapper profileChatMapper,
                              @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                              ObjectMapper objectMapper,
                              PromptLoader promptLoader,
                              ProfileStep1Filter step1Filter,
                              ProfileSignalService signalService,
                              ProfileBehaviorAccumulatorService behaviorAccumulatorService) {
        this.profileMapper = profileMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.profileChatMapper = profileChatMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.step1Filter = step1Filter;
        this.signalService = signalService;
        this.behaviorAccumulatorService = behaviorAccumulatorService;
    }

    // ========================================================================
    // Public API (interface methods)
    // ========================================================================

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
        if (pv == null) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile_id", pv.getId());
        result.put("user_id", userId);
        result.put("course_id", courseId);
        result.put("version", pv.getVersion());
        result.put("updated_at", pv.getCreatedAt() != null ? pv.getCreatedAt().toString() : "");

        // Parse displayJson and build display_profile + dimensions
        Map<String, Object> displayProfile = null;
        if (pv.getDisplayJson() != null) {
            try {
                displayProfile = objectMapper.readValue(pv.getDisplayJson(), Map.class);
                result.put("display_profile", displayProfile);
            } catch (Exception e) {
                log.warn("Failed to parse display_json: {}", e.getMessage());
            }
        }

        // Build dimensions array from displayJson keys + markdown data
        List<Map<String, Object>> dimensions = buildDimensions(
                displayProfile, pv.getCoreProfileMd(), pv.getLearningProfileMd(), pv.getKnowledgeProfileMd());
        result.put("dimensions", dimensions);

        result.put("core_profile_md", pv.getCoreProfileMd());
        result.put("learning_profile_md", pv.getLearningProfileMd());
        result.put("knowledge_profile_md", pv.getKnowledgeProfileMd());

        return result;
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

    // ========================================================================
    // Dimension building (displayJson → frontend-compatible dimensions array)
    // ========================================================================

    private static final Map<String, String> DIM_LABELS = Map.ofEntries(
            Map.entry("knowledge_basis", "知识基础"),
            Map.entry("learning_goal", "学习目标"),
            Map.entry("cognitive_style", "认知风格"),
            Map.entry("learning_pace", "学习节奏"),
            Map.entry("major_context", "专业上下文"),
            Map.entry("interest_direction", "兴趣方向"),
            Map.entry("error_pattern", "错误模式")
    );

    private static final Map<String, String> DIM_LAYERS = Map.ofEntries(
            Map.entry("knowledge_basis", "core"),
            Map.entry("learning_goal", "core"),
            Map.entry("cognitive_style", "style"),
            Map.entry("learning_pace", "style"),
            Map.entry("major_context", "auxiliary"),
            Map.entry("interest_direction", "auxiliary"),
            Map.entry("error_pattern", "auxiliary")
    );

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDimensions(
            Map<String, Object> displayProfile,
            String coreMd, String learningMd, String knowledgeMd) {

        // Collect dimension keys from displayJson
        List<String> dimKeys = new ArrayList<>();
        if (displayProfile != null && displayProfile.get("dimensions") instanceof List<?> keys) {
            for (Object k : keys) {
                if (k instanceof String s) dimKeys.add(s);
            }
        }
        if (dimKeys.isEmpty()) {
            dimKeys.addAll(DIM_LABELS.keySet());
        }

        // Parse markdown key-value pairs
        Map<String, String> coreKv = parseMdKeys(coreMd);
        Map<String, String> learningKv = parseMdKeys(learningMd);
        Map<String, String> knowledgeKv = parseMdKeys(knowledgeMd);
        Map<String, String> allKv = new LinkedHashMap<>();
        allKv.putAll(knowledgeKv);
        allKv.putAll(learningKv);
        allKv.putAll(coreKv);

        String updatedAt = "";

        List<Map<String, Object>> dims = new ArrayList<>();
        for (String key : dimKeys) {
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("key", key);
            dim.put("label", DIM_LABELS.getOrDefault(key, key));
            dim.put("layer", DIM_LAYERS.getOrDefault(key, "auxiliary"));
            dim.put("value", buildDimValue(key, allKv, displayProfile));
            dim.put("confidence", buildDimConfidence(key, allKv, displayProfile));
            dim.put("source", "inferred");
            dim.put("updated_at", updatedAt);
            dims.add(dim);
        }
        return dims;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDimValue(String key, Map<String, String> kv, Map<String, Object> dp) {
        Map<String, Object> val = new LinkedHashMap<>();
        switch (key) {
            case "knowledge_basis" -> {
                // 优先从 display_profile.knowledge 读取展示友好的短名
                if (dp != null && dp.get("knowledge") instanceof Map<?, ?> know) {
                    if (know.get("mastered") instanceof List<?> m) val.put("mastered", m);
                    if (know.get("weak") instanceof List<?> w) val.put("weak", w);
                    val.put("strong", val.getOrDefault("mastered", List.of()));
                }
                // fallback: 从 MD 提取
                if (!val.containsKey("mastered")) {
                    List<String> mastered = new ArrayList<>();
                    List<String> weak = new ArrayList<>();
                    for (var e : kv.entrySet()) {
                        String k = e.getKey();
                        String v = e.getValue();
                        if (!k.startsWith("know.") || k.contains("interest") || k.endsWith(".overall")) continue;
                        String topic = extractTopicFromMd(k);
                        if (v.contains("掌握") || v.contains("较好") || v.contains("已经")) mastered.add(topic);
                        else if (v.contains("薄弱") || v.contains("不够") || v.contains("模糊") || v.contains("容易")) weak.add(topic);
                    }
                    val.put("mastered", mastered);
                    val.put("weak", weak);
                    val.put("strong", mastered);
                }
            }
            case "learning_goal" -> {
                String goalPrimary = kv.getOrDefault("core.goal.detail",
                        kv.getOrDefault("core.goal.long",
                                kv.getOrDefault("core.goal.score", "")));
                if (dp != null && dp.get("core") instanceof Map<?, ?> core
                        && core.get("goal") instanceof String g && !g.isBlank()) {
                    goalPrimary = g;
                }
                val.put("primary", goalPrimary.isBlank() ? "未设置" : goalPrimary);
                List<String> subGoals = new ArrayList<>();
                String goalScore = kv.get("core.goal.score");
                if (goalScore != null && !goalScore.isBlank() && !goalScore.equals(goalPrimary)) subGoals.add(goalScore);
                val.put("sub_goals", subGoals);
            }
            case "cognitive_style" -> {
                if (dp != null && dp.get("style") instanceof Map<?, ?> style) {
                    if (style.get("preference") instanceof List<?> pref && !pref.isEmpty()) {
                        val.put("style", pref);
                        val.put("media_preference", pref.get(0));
                    }
                    if (style.get("avoid") instanceof String avoid && !avoid.isBlank()) {
                        val.put("avoid", avoid);
                    }
                }
                if (!val.containsKey("style")) {
                    String styleText = kv.getOrDefault("learn.style", "");
                    if (!styleText.isBlank()) val.put("style", List.of(styleText));
                }
                if (!val.containsKey("media_preference")) {
                    String mediaText = kv.getOrDefault("learn.media", "");
                    if (!mediaText.isBlank()) {
                        if (mediaText.contains("视频")) val.put("media_preference", "video");
                        else if (mediaText.contains("代码")) val.put("media_preference", "code");
                        else if (mediaText.contains("图文") || mediaText.contains("图")) val.put("media_preference", "text");
                        else val.put("media_preference", "mixed");
                    }
                }
                if (!val.containsKey("avoid")) {
                    String avoid = kv.get("learn.avoid");
                    if (avoid != null && !avoid.isBlank()) val.put("avoid", avoid);
                }
            }
            case "learning_pace" -> {
                String paceStr = null;
                if (dp != null && dp.get("style") instanceof Map<?, ?> style) {
                    paceStr = style.get("pace") instanceof String s ? s : null;
                }
                int minutes = 0;
                if (paceStr != null) minutes = extractMinutes(paceStr);
                if (minutes == 0) minutes = extractMinutes(kv.getOrDefault("learn.pace", ""));
                if (minutes > 0) val.put("daily_minutes", minutes);
                String urgency = kv.get("learn.urgency");
                if (urgency != null) {
                    if (urgency.contains("紧迫") || urgency.contains("紧")) val.put("urgency", "intensive");
                    else if (urgency.contains("正常")) val.put("urgency", "normal");
                    else if (urgency.contains("宽松") || urgency.contains("松")) val.put("urgency", "relaxed");
                }
            }
            case "major_context" -> {
                if (dp != null && dp.get("core") instanceof Map<?, ?> core) {
                    if (core.get("major") instanceof String m && !m.isBlank()) val.put("major", m);
                    if (core.get("grade") instanceof String g && !g.isBlank()) val.put("grade", g);
                }
                if (!val.containsKey("major")) val.put("major", kv.getOrDefault("core.major", ""));
                if (!val.containsKey("grade")) val.put("grade", kv.getOrDefault("core.grade", ""));
                val.put("course", kv.getOrDefault("core.course", ""));
            }
            case "interest_direction" -> {
                List<String> topics = new ArrayList<>();
                for (var e : kv.entrySet()) {
                    if (e.getKey().startsWith("know.interest.") && !e.getValue().isBlank()) {
                        topics.add(e.getValue());
                    }
                }
                val.put("topics", topics);
            }
            case "error_pattern" -> {
                // 优先从 display_profile.knowledge.error_pattern 读取
                if (dp != null && dp.get("knowledge") instanceof Map<?, ?> know) {
                    if (know.get("error_pattern") instanceof List<?> tags) val.put("tags", tags);
                }
                if (!val.containsKey("tags")) {
                    List<String> tags = new ArrayList<>();
                    for (var e : kv.entrySet()) {
                        String v = e.getValue();
                        if (e.getKey().startsWith("know.") && (v.contains("容易错") || v.contains("容易混淆")
                                || v.contains("不够扎实") || v.contains("容易出错") || v.contains("模糊"))) {
                            tags.add(extractTopicFromMd(e.getKey()));
                        }
                    }
                    val.put("tags", tags);
                }
            }
            default -> {}
        }
        return val;
    }

    /** 从 MD key 提取主题名：know.ai_intro.bayes → "贝叶斯"，know.la.matrix_rank → "矩阵秩" */
    private String extractTopicFromMd(String mdKey) {
        String[] parts = mdKey.split("\\.");
        if (parts.length >= 3) return parts[parts.length - 1];
        if (parts.length >= 2) return parts[parts.length - 1];
        return mdKey;
    }

    private double buildDimConfidence(String key, Map<String, String> kv, Map<String, Object> dp) {
        switch (key) {
            case "knowledge_basis" -> {
                long count = kv.keySet().stream().filter(k -> k.startsWith("know.") && !k.contains("interest") && !k.endsWith(".overall")).count();
                return count > 0 ? Math.min(0.5 + count * 0.1, 1.0) : 0.1;
            }
            case "learning_goal" -> {
                boolean hasMd = kv.containsKey("core.goal.detail") || kv.containsKey("core.goal.long") || kv.containsKey("core.goal.score");
                boolean hasDp = dp != null && dp.get("core") instanceof Map<?, ?> c && c.get("goal") instanceof String g && !g.isBlank();
                return hasMd || hasDp ? 0.6 : 0.1;
            }
            case "cognitive_style" -> {
                return kv.containsKey("learn.style") || kv.containsKey("learn.media") ? 0.6 : 0.1;
            }
            case "learning_pace" -> {
                boolean hasMd = kv.containsKey("learn.pace");
                boolean hasDp = dp != null && dp.get("style") instanceof Map<?, ?> s && s.get("pace") instanceof String p && !p.isBlank();
                return hasMd || hasDp ? 0.6 : 0.1;
            }
            case "major_context" -> {
                boolean hasMd = kv.containsKey("core.major");
                boolean hasDp = dp != null && dp.get("core") instanceof Map<?, ?> c && c.get("major") instanceof String m && !m.isBlank();
                return hasMd || hasDp ? 0.6 : 0.1;
            }
            case "interest_direction" -> {
                long count = kv.keySet().stream().filter(k -> k.startsWith("know.interest.")).count();
                return count > 0 ? Math.min(0.4 + count * 0.15, 1.0) : 0.1;
            }
            case "error_pattern" -> {
                boolean hasMd = kv.values().stream().anyMatch(v ->
                        v.contains("容易错") || v.contains("容易混淆") || v.contains("不够扎实") || v.contains("模糊"));
                boolean hasDp = dp != null && dp.get("knowledge") instanceof Map<?, ?> k && k.get("error_pattern") instanceof List<?> t && !t.isEmpty();
                return hasMd || hasDp ? 0.6 : 0.1;
            }
            default -> { return 0.1; }
        }
    }

    private int extractMinutes(String text) {
        if (text == null || text.isBlank()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*小时").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1)) * 60;
        m = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private Map<String, String> parseMdKeys(String mdText) {
        if (mdText == null || mdText.isBlank()) return Map.of();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "- \\[([^\\]]+)\\]\\s*(.*?)(?=\\n- \\[|\\n#|\\z)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(mdText);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        while (matcher.find()) {
            result.put(matcher.group(1).trim(), matcher.group(2).trim());
        }
        return result;
    }

    @Override
    @Transactional
    public void handleChatEnd(String userId, String courseId, String chatId, List<Map<String, String>> messages) {
        log.info("=== handleChatEnd START === userId={}, courseId={}, chatId={}", userId, courseId, chatId);

        try {
            // Step 1: LLM 语义提取
            ProfileStep1Filter.FilteredResult filtered = step1ExtractSentences(userId, courseId, messages);

            if (filtered.cleanSentences() == null || filtered.cleanSentences().isBlank()) {
                log.info("No new profile sentences after filtering, skipping Step 2/3");
                step3PersistSignalsOnly(userId, courseId, chatId, filtered);
                return;
            }

            // Step 2: LLM MD 编辑
            ProfileMdSet step2Result = step2EditMd(filtered.cleanSentences(), userId, courseId);

            // Step 3: 持久化
            step3Persist(userId, courseId, chatId, step2Result, filtered);

            log.info("=== handleChatEnd COMPLETE === userId={}, courseId={}", userId, courseId);

        } catch (Exception e) {
            log.error("handleChatEnd failed: userId={}, courseId={}, chatId={}: {}",
                    userId, courseId, chatId, e.getMessage(), e);
        }
    }

    @Override
    public ProfileMdSet getCurrentMd(String userId, String courseId) {
        Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId));
        if (profile == null || profile.getCurrentVersion() == null || profile.getCurrentVersion() == 0) {
            return new ProfileMdSet("", "", "", "{}");
        }

        ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
        if (pv == null) {
            return new ProfileMdSet("", "", "", "{}");
        }

        return new ProfileMdSet(
                pv.getCoreProfileMd() != null ? pv.getCoreProfileMd() : "",
                pv.getLearningProfileMd() != null ? pv.getLearningProfileMd() : "",
                pv.getKnowledgeProfileMd() != null ? pv.getKnowledgeProfileMd() : "",
                pv.getDisplayJson() != null ? pv.getDisplayJson() : "{}"
        );
    }

    @Override
    public Map<String, String> parseProfileMd(String mdText) {
        if (mdText == null || mdText.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        // 匹配 [key] 一行或多行直到下一个 [key] 或结尾
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "- \\[([^\\]]+)\\]\\s*(.*?)(?=\\n- \\[|\\n#|\\n##|\\z)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(mdText);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            result.put(key, value);
        }
        return result;
    }

    // ========================================================================
    // Step pipeline
    // ========================================================================

    private ProfileStep1Filter.FilteredResult step1ExtractSentences(
            String userId, String courseId, List<Map<String, String>> messages) {

        // 加载行为累计和待确认项
        List<ProfileBehaviorAccumulatorDto> behaviorRecords = loadBehaviorAccumulator(userId, courseId);
        List<PendingConfirmationDto> pendingConfirmations = loadPendingConfirmations(userId, courseId);

        // 组装 prompt
        String prompt = buildStep1Prompt(userId, courseId, messages, behaviorRecords, pendingConfirmations);

        String llmOutput = callLlmWithRetry("profile/step1_extraction", prompt, STEP1_RETRY_COUNT);

        // 过滤
        return step1Filter.filter(llmOutput);
    }

    private ProfileMdSet step2EditMd(String cleanSentences, String userId, String courseId) {
        ProfileMdSet currentMd = getCurrentMd(userId, courseId);

        String prompt = buildStep2Prompt(cleanSentences, currentMd);

        String llmOutput = callLlmWithRetry("profile/step2_md+display", prompt, 1);

        return parseStep2Output(llmOutput, currentMd);
    }

    protected void step3Persist(String userId, String courseId, String chatId,
                                 ProfileMdSet step2Result, ProfileStep1Filter.FilteredResult filtered) {
        // 比较 MD 是否有变化
        ProfileMdSet currentMd = getCurrentMd(userId, courseId);
        boolean hasChanges = compareMdChanged(step2Result, currentMd);

        if (!hasChanges) {
            log.info("MD unchanged, skipping version creation");
            step3PersistSignalsOnly(userId, courseId, chatId, filtered);
            return;
        }

        // 创建新版本
        persistProfileVersion(userId, courseId, chatId, step2Result);

        // 持久化 signals / pending / behavior
        step3PersistSignalsOnly(userId, courseId, chatId, filtered);
    }

    private void step3PersistSignalsOnly(String userId, String courseId, String chatId,
                                          ProfileStep1Filter.FilteredResult filtered) {
        // signals：关联 chatId、userId、courseId
        for (var signal : filtered.signals()) {
            if (signal.getChatId() == null) {
                signal.setChatId(chatId);
            }
            if (signal.getUserId() == null) {
                signal.setUserId(userId);
            }
            if (signal.getCourseId() == null) {
                signal.setCourseId(courseId);
            }
        }
        signalService.batchInsertSignals(filtered.signals());

        // pending confirmations：处理已确认/否认
        for (var pending : filtered.pendingConfirmations()) {
            if (pending.getConfirmedPendingId() != null) {
                signalService.handleConfirmation(pending.getConfirmedPendingId(), true);
            } else if (pending.getRejectedPendingId() != null) {
                signalService.handleConfirmation(pending.getRejectedPendingId(), false);
            }
        }

        // behavior accumulator
        behaviorAccumulatorService.processBehaviorRecords(userId, courseId, filtered.accumulatingBehaviors());
    }

    // ========================================================================
    // Prompt builders
    // ========================================================================

    private String buildStep1Prompt(String userId, String courseId,
                                     List<Map<String, String>> messages,
                                     List<ProfileBehaviorAccumulatorDto> behaviorRecords,
                                     List<PendingConfirmationDto> pendingConfirmations) {
        String template = promptLoader.get("profile/step1_extraction");

        String conversationHistory = messages.stream()
                .map(msg -> "[" + msg.getOrDefault("role", "unknown") + "] " + msg.getOrDefault("content", ""))
                .collect(Collectors.joining("\n"));

        String behaviorRecordsText = behaviorRecords.isEmpty()
                ? "（无）"
                : behaviorRecords.stream()
                    .map(b -> "- " + b.getSignalKey() + ": " + b.getValue()
                        + " (累计 " + b.getOccurrenceCount() + " 次)")
                    .collect(Collectors.joining("\n"));

        String pendingText = pendingConfirmations.isEmpty()
                ? "（无）"
                : pendingConfirmations.stream()
                    .map(p -> "- [" + p.getDimension() + "] " + p.getValue())
                    .collect(Collectors.joining("\n"));

        String pendingJson = "";
        try {
            pendingJson = objectMapper.writeValueAsString(pendingConfirmations);
        } catch (Exception e) {
            log.warn("Failed to serialize pending confirmations: {}", e.getMessage());
        }

        return template
                .replace("{behavior_accumulator_records}", behaviorRecordsText)
                .replace("{pending_confirmations}", pendingText)
                .replace("{conversation_history}", conversationHistory)
                .replace("{pending_confirmations_json}", pendingJson);
    }

    private String buildStep2Prompt(String cleanSentences, ProfileMdSet currentMd) {
        String mdTemplate = promptLoader.get("profile/step2_md");
        String displayTemplate = promptLoader.get("profile/step2_display");

        String mdPart = mdTemplate
                .replace("{profile_sentences_from_step1}", cleanSentences)
                .replace("{current_core_profile_md}", currentMd.getCoreProfileMd())
                .replace("{current_learning_profile_md}", currentMd.getLearningProfileMd())
                .replace("{current_knowledge_profile_md}", currentMd.getKnowledgeProfileMd());

        String displayPart = displayTemplate
                .replace("{current_display_json}", currentMd.getDisplayJson());

        return mdPart + "\n\n---\n\n" + displayPart
                + "\n\n---\n\n## 输出格式\n\n"
                + "===CORE_PROFILE_MD===\n[完整的 core_profile.md 内容]\n\n"
                + "===LEARNING_PROFILE_MD===\n[完整的 learning_profile.md 内容]\n\n"
                + "===KNOWLEDGE_PROFILE_MD===\n[完整的 knowledge_profile.md 内容]\n\n"
                + "===DISPLAY_JSON===\n[按上述结构和规则生成的 JSON]\n";
    }

    // ========================================================================
    // LLM invocation
    // ========================================================================

    private String callLlmWithRetry(String promptPath, String prompt, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long waitMs = STEP1_RETRY_BASE_MS * (1L << (attempt - 1));
                    Thread.sleep(waitMs);
                }
                ChatClient client = chatClientBuilder.build();
                String response = client.prompt()
                        .messages(new SystemMessage(prompt), new UserMessage("process"))
                        .call()
                        .content();
                if (response != null && !response.isBlank()) {
                    return response;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM call failed (attempt {}/{}): {}", attempt + 1, maxRetries + 1, e.getMessage());
            }
        }
        log.error("LLM call exhausted retries for prompt {}", promptPath);
        return "";
    }

    // ========================================================================
    // Parsing & comparison
    // ========================================================================

    private ProfileMdSet parseStep2Output(String llmOutput, ProfileMdSet fallback) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return fallback;
        }

        String coreMd = extractSection(llmOutput, "CORE_PROFILE_MD");
        String learningMd = extractSection(llmOutput, "LEARNING_PROFILE_MD");
        String knowledgeMd = extractSection(llmOutput, "KNOWLEDGE_PROFILE_MD");
        String displayJson = cleanJson(extractSection(llmOutput, "DISPLAY_JSON"));

        return new ProfileMdSet(
                coreMd != null ? coreMd : fallback.getCoreProfileMd(),
                learningMd != null ? learningMd : fallback.getLearningProfileMd(),
                knowledgeMd != null ? knowledgeMd : fallback.getKnowledgeProfileMd(),
                displayJson != null ? displayJson : fallback.getDisplayJson()
        );
    }

    private String extractSection(String text, String sectionName) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "={3,}\\s*" + sectionName + "\\s*={3,}\\s*\\n?(.*?)(?=\\n={3,}|\\z)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /** 清理 LLM 输出的 JSON：去掉 markdown 代码块标记，校验合法性 */
    private String cleanJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        // 去掉 ```json ... ``` 或 ``` ... ```
        t = t.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        t = t.trim();
        if (t.isEmpty()) return null;
        // 校验是否为合法 JSON
        try {
            objectMapper.readTree(t);
            return t;
        } catch (Exception e) {
            log.warn("display_json is not valid JSON, discarding: {}", t.substring(0, Math.min(200, t.length())));
            return null;
        }
    }

    private boolean compareMdChanged(ProfileMdSet newMd, ProfileMdSet oldMd) {
        if (oldMd == null) return true;
        return !safeTrim(newMd.getCoreProfileMd()).equals(safeTrim(oldMd.getCoreProfileMd()))
                || !safeTrim(newMd.getLearningProfileMd()).equals(safeTrim(oldMd.getLearningProfileMd()))
                || !safeTrim(newMd.getKnowledgeProfileMd()).equals(safeTrim(oldMd.getKnowledgeProfileMd()));
    }

    private String safeTrim(String s) {
        return s != null ? s.trim() : "";
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    protected int persistProfileVersion(String userId, String courseId, String chatId, ProfileMdSet mdSet) {
        String lockKey = userId + ":" + courseId;
        ReentrantLock lock = profileLocks.compute(lockKey, (k, v) -> v != null ? v : new ReentrantLock());
        lock.lock();
        try {
            return doPersistProfileVersion(userId, courseId, chatId, mdSet);
        } finally {
            lock.unlock();
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                profileLocks.remove(lockKey, lock);
            }
        }
    }

    private int doPersistProfileVersion(String userId, String courseId, String chatId, ProfileMdSet mdSet) {
        Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId));

        int newVersion;
        if (profile == null) {
            profile = new Profile();
            profile.setId(UUID.randomUUID().toString());
            profile.setUserId(userId);
            profile.setCourseId(courseId);
            newVersion = 1;
            profile.setCurrentVersion(newVersion);
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.insert(profile);
        } else {
            newVersion = profile.getCurrentVersion() + 1;
            profile.setCurrentVersion(newVersion);
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.updateById(profile);
        }

        for (int attempt = 0; attempt < MAX_VERSION_RETRIES; attempt++) {
            try {
                ProfileVersion pv = new ProfileVersion();
                pv.setUserId(userId);
                pv.setCourseId(courseId);
                pv.setVersion(newVersion);
                pv.setCoreProfileMd(mdSet.getCoreProfileMd());
                pv.setLearningProfileMd(mdSet.getLearningProfileMd());
                pv.setKnowledgeProfileMd(mdSet.getKnowledgeProfileMd());
                pv.setDisplayJson(mdSet.getDisplayJson());
                pv.setSourceChatIds(toJson(List.of(chatId)));
                pv.setCreatedAt(LocalDateTime.now());
                profileVersionMapper.insert(pv);

                log.info("Persisted profile version {} for userId={}, courseId={}", newVersion, userId, courseId);
                return newVersion;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.warn("Duplicate version {} for userId={}, courseId={}, retrying (attempt {}/{})",
                        newVersion, userId, courseId, attempt + 1, MAX_VERSION_RETRIES);
                Profile refreshed = profileMapper.selectOne(
                        new LambdaQueryWrapper<Profile>()
                                .eq(Profile::getUserId, userId)
                                .eq(Profile::getCourseId, courseId));
                if (refreshed != null) {
                    newVersion = refreshed.getCurrentVersion() + 1;
                    refreshed.setCurrentVersion(newVersion);
                    refreshed.setUpdatedAt(LocalDateTime.now());
                    profileMapper.updateById(refreshed);
                }
            }
        }

        log.error("Failed to persist profile version after {} retries for userId={}, courseId={}",
                MAX_VERSION_RETRIES, userId, courseId);
        return -1;
    }

    // ========================================================================
    // Data loaders
    // ========================================================================

    private List<ProfileBehaviorAccumulatorDto> loadBehaviorAccumulator(String userId, String courseId) {
        var records = behaviorAccumulatorService.loadByUserAndCourse(userId, courseId);
        List<ProfileBehaviorAccumulatorDto> result = new ArrayList<>();
        for (var r : records) {
            ProfileBehaviorAccumulatorDto dto = new ProfileBehaviorAccumulatorDto();
            dto.setSignalKey(r.getSignalKey());
            dto.setValue(r.getValue());
            dto.setOccurrenceCount(r.getOccurrenceCount() != null ? r.getOccurrenceCount() : 1);
            result.add(dto);
        }
        return result;
    }

    private List<PendingConfirmationDto> loadPendingConfirmations(String userId, String courseId) {
        var pending = signalService.loadPendingConfirmations(userId, courseId);
        List<PendingConfirmationDto> result = new ArrayList<>();
        for (var p : pending) {
            PendingConfirmationDto dto = new PendingConfirmationDto();
            dto.setDimension(p.getDimension());
            dto.setSignalKey(p.getSignalKey());
            dto.setValue(p.getValue());
            result.add(dto);
        }
        return result;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private String toJson(List<?> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.error("Failed to serialize list to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    // 用于内部传递行为累计数据的 DTO
    private static class ProfileBehaviorAccumulatorDto {
        private String signalKey;
        private String value;
        private int occurrenceCount;

        public String getSignalKey() { return signalKey; }
        public void setSignalKey(String signalKey) { this.signalKey = signalKey; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public int getOccurrenceCount() { return occurrenceCount; }
        public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
    }
}
