package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.question.*;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.domain.entity.Question;
import com.learnthink.core.domain.entity.QuestionAttempt;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import com.learnthink.core.repository.QuestionAttemptMapper;
import com.learnthink.core.repository.QuestionMapper;
import com.learnthink.core.service.QuestionBankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankServiceImpl implements QuestionBankService {

    private final QuestionMapper questionMapper;
    private final QuestionAttemptMapper questionAttemptMapper;
    private final CourseKnowledgePointMapper courseKpMapper;
    private final ObjectMapper objectMapper;

    @Override
    public QuestionPageDTO listQuestions(String userId, String courseId, String questionType,
                                          Integer difficulty, String kpId, String status,
                                          String sort, int page, int size) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .eq(Question::getUserId, userId)
                .eq(Question::getCourseId, courseId)
                .isNull(Question::getDeletedAt);

        if (StringUtils.hasText(questionType)) {
            wrapper.eq(Question::getQuestionType, questionType);
        }
        if (difficulty != null) {
            wrapper.eq(Question::getDifficulty, difficulty);
        }
        if (StringUtils.hasText(kpId)) {
            wrapper.eq(Question::getKpId, kpId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Question::getStatus, status);
        }

        if ("difficulty".equals(sort)) {
            wrapper.orderByAsc(Question::getDifficulty);
        } else if ("accuracy".equals(sort)) {
            wrapper.orderByAsc(Question::getCorrectCount);
        } else {
            wrapper.orderByDesc(Question::getCreatedAt);
        }

        Page<Question> p = new Page<>(page, size);
        Page<Question> result = questionMapper.selectPage(p, wrapper);

        List<QuestionDTO> items = result.getRecords().stream()
                .map(this::toDTO)
                .toList();

        QuestionPageDTO pageDTO = new QuestionPageDTO();
        pageDTO.setItems(items);
        pageDTO.setTotal(result.getTotal());
        pageDTO.setPage(page);
        pageDTO.setSize(size);
        return pageDTO;
    }

    @Override
    public QuestionDTO getQuestionDetail(String id, String userId) {
        Question q = questionMapper.selectById(id);
        if (q == null || q.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        }
        return toDTO(q);
    }

    @Override
    @Transactional
    public QuestionDTO createQuestion(String userId, CreateQuestionRequest req) {
        Question q = assembleQuestion(userId, req);
        if (q.getKpId() == null) {
            q.setKpId(resolveKpId(req.getCourseId(), req.getTags()));
        }
        questionMapper.insert(q);
        return toDTO(q);
    }

    @Override
    @Transactional
    public BatchCreateQuestionsResultDTO batchCreateFromResource(String userId, BatchCreateQuestionsRequest req) {
        BatchCreateQuestionsResultDTO result = new BatchCreateQuestionsResultDTO();
        List<QuestionDTO> items = new ArrayList<>();
        int added = 0, skipped = 0;

        List<CreateQuestionRequest> questions = req.getQuestions();
        if (questions == null || questions.isEmpty()) {
            result.setAddedCount(0);
            result.setSkippedCount(0);
            result.setItems(items);
            return result;
        }

        // 查询该资源已入库的题干集合，用于去重（userId + sourceItemId + title）
        Set<String> existingTitles = new HashSet<>();
        if (StringUtils.hasText(req.getSourceItemId())) {
            List<Question> existing = questionMapper.selectList(
                    new LambdaQueryWrapper<Question>()
                            .eq(Question::getUserId, userId)
                            .eq(Question::getSourceItemId, req.getSourceItemId())
                            .isNull(Question::getDeletedAt));
            for (Question q : existing) {
                if (q.getTitle() != null) {
                    existingTitles.add(q.getTitle());
                }
            }
        }

        // 预加载课程知识点列表，一次查询整个批次复用
        List<CourseKnowledgePoint> courseKps = courseKpMapper.selectList(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, req.getCourseId()));

        for (CreateQuestionRequest cq : questions) {
            cq.setCourseId(req.getCourseId());
            cq.setSourceItemId(req.getSourceItemId());
            if (StringUtils.hasText(cq.getTitle()) && existingTitles.contains(cq.getTitle())) {
                skipped++;
                continue;
            }
            Question q = assembleQuestion(userId, cq);
            if (q.getKpId() == null) {
                q.setKpId(resolveKpId(courseKps, cq.getTags()));
            }
            questionMapper.insert(q);
            if (q.getTitle() != null) {
                existingTitles.add(q.getTitle());
            }
            items.add(toDTO(q));
            added++;
        }

        result.setAddedCount(added);
        result.setSkippedCount(skipped);
        result.setItems(items);
        return result;
    }

    private Question assembleQuestion(String userId, CreateQuestionRequest req) {
        Question q = new Question();
        q.setUserId(userId);
        q.setCourseId(req.getCourseId());
        q.setSourceItemId(req.getSourceItemId());
        q.setQuestionType(req.getQuestionType());
        q.setDifficulty(req.getDifficulty() != null ? req.getDifficulty() : 3);
        q.setTitle(req.getTitle());
        q.setKpId(req.getKpId());
        q.setAttemptCount(0);
        q.setCorrectCount(0);
        q.setStatus("published");

        try {
            if (req.getOptions() != null) {
                q.setOptionsJson(objectMapper.writeValueAsString(req.getOptions()));
            }
            if (req.getAnswer() != null) {
                q.setAnswerJson(objectMapper.writeValueAsString(req.getAnswer()));
            }
            if (req.getExplanation() != null) {
                q.setExplanation(req.getExplanation());
            }
            if (req.getTags() != null) {
                q.setTagsJson(objectMapper.writeValueAsString(req.getTags()));
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "JSON序列化失败");
        }
        return q;
    }

    /**
     * 从 tags 自动匹配 course_knowledge_points.id（需要预加载 KP 列表）
     * 匹配策略：精确 match → 包含 match → keywords 数组 match，命中即返回
     */
    private String resolveKpId(List<CourseKnowledgePoint> kps, List<String> tags) {
        if (tags == null || tags.isEmpty() || kps == null || kps.isEmpty()) return null;
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.trim();
            if (t.isEmpty()) continue;
            for (CourseKnowledgePoint kp : kps) {
                if (kp.getName() == null) continue;
                if (t.equals(kp.getName()) || kp.getName().contains(t) || t.contains(kp.getName())) {
                    log.debug("auto-matched kpId={} via tag='{}' -> kpName='{}'", kp.getId(), t, kp.getName());
                    return kp.getId();
                }
                if (kp.getKeywords() != null) {
                    try {
                        List<String> keywords = objectMapper.readValue(kp.getKeywords(),
                                new TypeReference<List<String>>() {});
                        if (keywords != null) {
                            for (String kw : keywords) {
                                if (kw != null && (t.equals(kw) || kw.contains(t) || t.contains(kw))) {
                                    log.debug("auto-matched kpId={} via tag='{}' -> keyword='{}'", kp.getId(), t, kw);
                                    return kp.getId();
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // ignore unparseable keywords JSON
                    }
                }
            }
        }
        return null;
    }

    /**
     * 便捷重载：从 courseId 加载全量 KP 列表后匹配
     */
    private String resolveKpId(String courseId, List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        List<CourseKnowledgePoint> kps = courseKpMapper.selectList(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, courseId));
        return resolveKpId(kps, tags);
    }

    @Override
    @Transactional
    public QuestionDTO updateQuestion(String id, String userId, CreateQuestionRequest req) {
        Question q = questionMapper.selectById(id);
        if (q == null || q.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        }

        if (req.getQuestionType() != null) q.setQuestionType(req.getQuestionType());
        if (req.getDifficulty() != null) q.setDifficulty(req.getDifficulty());
        if (req.getTitle() != null) q.setTitle(req.getTitle());
        if (req.getKpId() != null) q.setKpId(req.getKpId());
        if (req.getExplanation() != null) q.setExplanation(req.getExplanation());

        try {
            if (req.getOptions() != null) {
                q.setOptionsJson(objectMapper.writeValueAsString(req.getOptions()));
            }
            if (req.getAnswer() != null) {
                q.setAnswerJson(objectMapper.writeValueAsString(req.getAnswer()));
            }
            if (req.getTags() != null) {
                q.setTagsJson(objectMapper.writeValueAsString(req.getTags()));
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "JSON序列化失败");
        }

        questionMapper.updateById(q);
        return toDTO(q);
    }

    @Override
    @Transactional
    public void deleteQuestion(String id, String userId) {
        Question q = questionMapper.selectById(id);
        if (q == null || q.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        }
        q.setDeletedAt(LocalDateTime.now());
        questionMapper.updateById(q);
    }

    @Override
    @Transactional
    public AnswerResultDTO submitAnswer(String userId, String courseId, SubmitAnswerRequest req) {
        Question q = questionMapper.selectById(req.getQuestionId());
        if (q == null || q.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        }
        if (!userId.equals(q.getUserId()) || (courseId != null && !courseId.equals(q.getCourseId()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题目不存在");
        }

        Object correctAnswer = parseJson(q.getAnswerJson(), Object.class);
        Object selectedAnswer = req.getSelectedAnswer();

        boolean isCorrect = judgeAnswer(selectedAnswer, correctAnswer, q.getQuestionType());

        int attemptNumber = questionAttemptMapper.selectCount(
                new LambdaQueryWrapper<QuestionAttempt>()
                        .eq(QuestionAttempt::getUserId, userId)
                        .eq(QuestionAttempt::getQuestionId, req.getQuestionId())
        ).intValue() + 1;

        QuestionAttempt attempt = new QuestionAttempt();
        attempt.setUserId(userId);
        attempt.setCourseId(courseId);
        attempt.setQuestionId(req.getQuestionId());
        attempt.setSelectedAnswer(toJsonString(selectedAnswer));
        attempt.setIsCorrect(isCorrect);
        attempt.setDurationSeconds(req.getDurationSeconds() != null ? req.getDurationSeconds() : 0);
        attempt.setAttemptNumber(attemptNumber);
        questionAttemptMapper.insert(attempt);

        q.setAttemptCount(q.getAttemptCount() + 1);
        if (isCorrect) {
            q.setCorrectCount(q.getCorrectCount() + 1);
        }
        questionMapper.updateById(q);

        AnswerResultDTO result = new AnswerResultDTO();
        result.setCorrect(isCorrect);
        result.setCorrectAnswer(correctAnswer);
        result.setExplanation(q.getExplanation());
        result.setAttemptNumber(attemptNumber);
        result.setTotalAttempts(q.getAttemptCount());
        result.setKpId(q.getKpId());
        result.setAttemptId(attempt.getId());
        return result;
    }

    @Override
    public List<KpAccuracyDTO> getKpAccuracy(String userId, String courseId) {
        // 以题库为准：覆盖该课程下所有已发布、有知识点归属的题目
        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getUserId, userId)
                        .eq(Question::getCourseId, courseId)
                        .isNull(Question::getDeletedAt)
                        .eq(Question::getStatus, "published")
                        .isNotNull(Question::getKpId));

        if (questions.isEmpty()) return List.of();

        // 按知识点分组题目
        Map<String, List<Question>> questionsByKp = questions.stream()
                .collect(Collectors.groupingBy(Question::getKpId));

        // 题目 id -> 知识点 id 映射，用于把作答记录归到知识点
        Map<String, String> questionIdToKpId = questions.stream()
                .collect(Collectors.toMap(Question::getId, Question::getKpId, (a, b) -> a));

        // 左连接作答统计：totalAttempts / correctCount
        List<QuestionAttempt> attempts = questionAttemptMapper.selectList(
                new LambdaQueryWrapper<QuestionAttempt>()
                        .eq(QuestionAttempt::getUserId, userId)
                        .eq(QuestionAttempt::getCourseId, courseId));
        Map<String, int[]> statsByKp = new HashMap<>(); // kpId -> [total, correct]
        for (QuestionAttempt att : attempts) {
            String kpId = questionIdToKpId.get(att.getQuestionId());
            if (kpId == null) continue;
            int[] s = statsByKp.computeIfAbsent(kpId, k -> new int[2]);
            s[0]++;
            if (Boolean.TRUE.equals(att.getIsCorrect())) s[1]++;
        }

        // 填充知识点名称
        Map<String, String> kpNames = loadKpNamesByIds(questionsByKp.keySet());

        List<KpAccuracyDTO> result = new ArrayList<>();
        for (Map.Entry<String, List<Question>> entry : questionsByKp.entrySet()) {
            String kpId = entry.getKey();
            int[] s = statsByKp.getOrDefault(kpId, new int[2]);
            KpAccuracyDTO dto = new KpAccuracyDTO();
            dto.setKpId(kpId);
            dto.setKpName(kpNames.getOrDefault(kpId, ""));
            dto.setQuestionCount(entry.getValue().size());
            dto.setTotalAttempts(s[0]);
            dto.setCorrectCount(s[1]);
            dto.setAccuracyRate(s[0] > 0 ? (double) s[1] / s[0] : 0);
            result.add(dto);
        }

        // 排序：有作答的按正确率升序（薄弱优先），无作答的排最后
        result.sort(Comparator
                .comparing((KpAccuracyDTO d) -> d.getTotalAttempts() == 0)
                .thenComparingDouble(KpAccuracyDTO::getAccuracyRate));
        return result;
    }

    private Map<String, String> loadKpNamesByIds(Set<String> kpIds) {
        if (kpIds.isEmpty()) return Collections.emptyMap();
        List<CourseKnowledgePoint> kps = courseKpMapper.selectBatchIds(kpIds);
        return kps.stream().collect(Collectors.toMap(CourseKnowledgePoint::getId, CourseKnowledgePoint::getName, (a, b) -> a));
    }

    @Override
    public QuestionPageDTO listWrongQuestions(String userId, String courseId, int page, int size) {
        List<QuestionAttempt> wrongAttempts = questionAttemptMapper.selectList(
                new LambdaQueryWrapper<QuestionAttempt>()
                        .eq(QuestionAttempt::getUserId, userId)
                        .eq(QuestionAttempt::getCourseId, courseId)
                        .eq(QuestionAttempt::getIsCorrect, false));

        if (wrongAttempts.isEmpty()) {
            return emptyPage(page, size);
        }

        Set<String> wrongQuestionIds = wrongAttempts.stream()
                .map(QuestionAttempt::getQuestionId)
                .collect(Collectors.toSet());

        Page<Question> p = new Page<>(page, size);
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .in(Question::getId, wrongQuestionIds)
                .isNull(Question::getDeletedAt)
                .eq(Question::getUserId, userId)
                .orderByDesc(Question::getCreatedAt);

        Page<Question> result = questionMapper.selectPage(p, wrapper);

        QuestionPageDTO pageDTO = new QuestionPageDTO();
        pageDTO.setItems(result.getRecords().stream().map(this::toDTO).toList());
        pageDTO.setTotal(result.getTotal());
        pageDTO.setPage(page);
        pageDTO.setSize(size);
        return pageDTO;
    }

    private QuestionPageDTO emptyPage(int page, int size) {
        QuestionPageDTO dto = new QuestionPageDTO();
        dto.setItems(List.of());
        dto.setTotal(0);
        dto.setPage(page);
        dto.setSize(size);
        return dto;
    }

    private QuestionDTO toDTO(Question q) {
        QuestionDTO dto = new QuestionDTO();
        dto.setId(q.getId());
        dto.setUserId(q.getUserId());
        dto.setCourseId(q.getCourseId());
        dto.setSourceItemId(q.getSourceItemId());
        dto.setQuestionType(q.getQuestionType());
        dto.setDifficulty(q.getDifficulty());
        dto.setTitle(q.getTitle());
        dto.setKpId(q.getKpId());
        dto.setAttemptCount(q.getAttemptCount());
        dto.setCorrectCount(q.getCorrectCount());
        dto.setAccuracyRate(q.getAttemptCount() > 0
                ? (double) q.getCorrectCount() / q.getAttemptCount() : 0);
        dto.setStatus(q.getStatus());
        dto.setCreatedAt(q.getCreatedAt() != null ? q.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);

        if (q.getOptionsJson() != null) {
            dto.setOptions(parseJson(q.getOptionsJson(), new TypeReference<List<Map<String, String>>>() {}));
        }
        if (q.getAnswerJson() != null) {
            dto.setAnswer(parseJson(q.getAnswerJson(), Object.class));
        }
        if (q.getExplanation() != null) {
            dto.setExplanation(q.getExplanation());
        }
        if (q.getTagsJson() != null) {
            dto.setTags(parseJson(q.getTagsJson(), new TypeReference<List<String>>() {}));
        }
        return dto;
    }

    private <T> T parseJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 客观题判分。处理 single_choice/multiple_choice/true_false/fill_blank。
     * short_answer/code 保持原 equals 行为（题库浏览用）。
     */
    private boolean judgeAnswer(Object selected, Object correct, String type) {
        if (selected == null || correct == null) return false;
        if ("multiple_choice".equals(type)) {
            return normalizeLetters(selected).equals(normalizeLetters(correct));
        }
        if ("true_false".equals(type)) {
            return normalizeTrueFalse(selected).equalsIgnoreCase(normalizeTrueFalse(correct));
        }
        return String.valueOf(selected).trim().equalsIgnoreCase(String.valueOf(correct).trim());
    }

    /**
     * 多选答案归一化：支持 "ABC" 字符串和 ["A","B","C"] 数组两种形态，统一为排序大写字符串
     */
    private String normalizeLetters(Object answer) {
        if (answer == null) return "";
        String s;
        if (answer instanceof List<?> list) {
            s = list.stream().map(String::valueOf).collect(Collectors.joining());
        } else {
            s = String.valueOf(answer);
        }
        return s.toUpperCase().chars()
                .filter(c -> c >= 'A' && c <= 'Z')
                .distinct()
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * 判断题答案归一化：兼容选项字母 "A"/"B" 与布尔文本 "true"/"false"（含中文"正确"/"错误"），
     * 统一映射为 "true"/"false" 再比较。
     * 约定：A=正确=true，B=错误=false（与出题模板一致）
     */
    private String normalizeTrueFalse(Object answer) {
        if (answer == null) return "";
        String s = String.valueOf(answer).trim().toLowerCase();
        return switch (s) {
            case "a", "true", "对", "正确", "y", "yes", "1", "t" -> "true";
            case "b", "false", "错", "错误", "n", "no", "0", "f" -> "false";
            default -> s;
        };
    }
}
