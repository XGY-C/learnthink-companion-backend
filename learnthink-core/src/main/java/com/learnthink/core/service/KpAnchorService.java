package com.learnthink.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.domain.entity.ProfileKpAnchor;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import com.learnthink.core.repository.ProfileKpAnchorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 将自由文本的画像维度标签锚定到结构化的课程知识点。
 *
 * <h3>匹配流水线（按顺序尝试）：</h3>
 * <ol>
 *   <li>精确关键词匹配：标签 ∈ KP.keywords[] → 置信度 0.95</li>
 *   <li>模糊文本匹配：Levenshtein + 中文分词交集 → 阈值 0.75</li>
 *   <li>未匹配标签 → 作用域=课外，置信度=0.50</li>
 * </ol>
 * <p>
 * 嵌入相似度匹配（BGE-M3）是未来的增强方向。
 */
@Service
public class KpAnchorService {

    private static final Logger log = LoggerFactory.getLogger(KpAnchorService.class);

    private static final BigDecimal KEYWORD_CONFIDENCE = new BigDecimal("0.95");
    private static final BigDecimal FUZZY_CONFIDENCE_BASE = new BigDecimal("0.80");
    private static final BigDecimal EXTRACURRICULAR_CONFIDENCE = new BigDecimal("0.50");
    private static final double FUZZY_THRESHOLD = 0.75;

    private final CourseKnowledgePointMapper kpMapper;
    private final ProfileKpAnchorMapper anchorMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KpAnchorService(CourseKnowledgePointMapper kpMapper,
                           ProfileKpAnchorMapper anchorMapper) {
        this.kpMapper = kpMapper;
        this.anchorMapper = anchorMapper;
    }

    /**
     * Anchor all free-text labels in a profile version to course knowledge points.
     *
     * @return list of created anchors
     */
    @Transactional
    public List<ProfileKpAnchor> anchor(String profileVersionId, String courseId,
                                         List<Map<String, Object>> dimensions) {
        log.info("KP anchoring started: profileVersionId={}, courseId={}", profileVersionId, courseId);

        // 加载该课程的所有知识点
        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
            new LambdaQueryWrapper<CourseKnowledgePoint>()
                .eq(CourseKnowledgePoint::getCourseId, courseId));
        log.info("Loaded {} KPs for course {}", allKps.size(), courseId);

        if (allKps.isEmpty()) {
            log.warn("No KPs defined for course {}, all labels will be marked extracurricular", courseId);
        }

        // 从维度中提取所有标签
        List<LabelToAnchor> labels = extractLabels(dimensions);
        log.info("Extracted {} labels from dimensions", labels.size());

        // 匹配每个标签
        List<ProfileKpAnchor> anchors = new ArrayList<>();
        int coreCount = 0, preCount = 0, suppCount = 0, extraCount = 0;

        for (LabelToAnchor label : labels) {
            ProfileKpAnchor anchor = matchLabel(label, allKps, profileVersionId);
            if (anchor != null) {
                anchors.add(anchor);
                switch (anchor.getScopeAtAnchor()) {
                    case "core_curriculum" -> coreCount++;
                    case "prerequisite" -> preCount++;
                    case "supplementary" -> suppCount++;
                    case "extracurricular" -> extraCount++;
                }
            }
        }

        // 批量保存（去重：同一 (profile_version_id, kp_id, dimension_key, relation_type) 只保留首次匹配）
        if (!anchors.isEmpty()) {
            Set<String> seen = new HashSet<>();
            int skipped = 0;
            for (ProfileKpAnchor a : anchors) {
                String dedupKey = a.getProfileVersionId() + "|" + a.getKpId() + "|"
                    + a.getDimensionKey() + "|" + a.getRelationType();
                if (!seen.add(dedupKey)) {
                    skipped++;
                    continue;
                }
                anchorMapper.insert(a);
            }
            if (skipped > 0) {
                log.info("KP anchoring dedup: skipped {} duplicate anchors", skipped);
            }
        }

        log.info("KP anchoring complete: {} anchors (core={}, prereq={}, supp={}, extra={})",
            anchors.size(), coreCount, preCount, suppCount, extraCount);
        return anchors;
    }

    /**
     * Match a single label against all course KPs.
     */
    private ProfileKpAnchor matchLabel(LabelToAnchor label, List<CourseKnowledgePoint> allKps,
                                        String profileVersionId) {
        String text = label.labelText().toLowerCase().trim();
        if (text.isEmpty()) return null;

        // 第一步：精确关键词匹配
        for (CourseKnowledgePoint kp : allKps) {
            List<String> keywords = parseJsonArray(kp.getKeywords());
            for (String kw : keywords) {
                if (text.equals(kw.toLowerCase().trim()) || text.contains(kw.toLowerCase().trim())) {
                    return buildAnchor(profileVersionId, kp, label, KEYWORD_CONFIDENCE, "keyword");
                }
            }
        }

        // 第二步：对知识点名称和关键词进行模糊匹配
        CourseKnowledgePoint bestMatch = null;
        double bestScore = 0;
        for (CourseKnowledgePoint kp : allKps) {
            double score = fuzzyMatch(text, kp);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = kp;
            }
        }

        if (bestMatch != null && bestScore >= FUZZY_THRESHOLD) {
            BigDecimal conf = FUZZY_CONFIDENCE_BASE.multiply(new BigDecimal(bestScore))
                .setScale(2, RoundingMode.HALF_UP);
            if (conf.compareTo(new BigDecimal("1.00")) > 0) conf = new BigDecimal("1.00");
            return buildAnchor(profileVersionId, bestMatch, label, conf, "fuzzy");
        }

        // 第三步：课外（在知识点树中无匹配）
        return buildExtracurricularAnchor(profileVersionId, label);
    }

    /**
     * Fuzzy match: Levenshtein ratio + Chinese bigram overlap.
     */
    private double fuzzyMatch(String text, CourseKnowledgePoint kp) {
        // 与知识点名称比较
        double nameScore = similarity(text, kp.getName().toLowerCase().trim());

        // 与关键词比较
        List<String> keywords = parseJsonArray(kp.getKeywords());
        double maxKwScore = 0;
        for (String kw : keywords) {
            double s = similarity(text, kw.toLowerCase().trim());
            if (s > maxKwScore) maxKwScore = s;
        }

        return Math.max(nameScore, maxKwScore);
    }

    /**
     * Hybrid similarity: weighted average of Levenshtein ratio and bigram overlap.
     */
    private double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.9;

        double levenshtein = 1.0 - (double) levenshteinDistance(a, b) / Math.max(a.length(), b.length());

        // 中文二元组重叠度
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        double bigramOverlap;
        if (bigramsA.isEmpty() && bigramsB.isEmpty()) {
            bigramOverlap = 0.5;
        } else {
            Set<String> intersection = new HashSet<>(bigramsA);
            intersection.retainAll(bigramsB);
            Set<String> union = new HashSet<>(bigramsA);
            union.addAll(bigramsB);
            bigramOverlap = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        }

        return levenshtein * 0.4 + bigramOverlap * 0.6;
    }

    private Set<String> bigrams(String s) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            result.add(s.substring(i, i + 2));
        }
        return result;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                    Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[a.length()][b.length()];
    }

    private ProfileKpAnchor buildAnchor(String profileVersionId, CourseKnowledgePoint kp,
                                         LabelToAnchor label, BigDecimal confidence, String matchMethod) {
        ProfileKpAnchor anchor = new ProfileKpAnchor();
        anchor.setProfileVersionId(profileVersionId);
        anchor.setKpId(kp.getId());
        anchor.setDimensionKey(label.dimensionKey());
        anchor.setRelationType(label.relationType());
        anchor.setScopeAtAnchor(kp.getScope());
        anchor.setConfidence(confidence);
        anchor.setSource(label.source());
        anchor.setMatchMethod(matchMethod);
        anchor.setLabelText(label.labelText());
        return anchor;
    }

    private ProfileKpAnchor buildExtracurricularAnchor(String profileVersionId, LabelToAnchor label) {
        ProfileKpAnchor anchor = new ProfileKpAnchor();
        anchor.setProfileVersionId(profileVersionId);
        anchor.setKpId(null);
        anchor.setDimensionKey(label.dimensionKey());
        anchor.setRelationType(label.relationType());
        anchor.setScopeAtAnchor("extracurricular");
        anchor.setConfidence(EXTRACURRICULAR_CONFIDENCE);
        anchor.setSource(label.source());
        anchor.setMatchMethod("fuzzy");
        anchor.setLabelText(label.labelText());
        return anchor;
    }

    /**
     * Extract all free-text labels from the 7-dimension profile.
     */
    private List<LabelToAnchor> extractLabels(List<Map<String, Object>> dimensions) {
        List<LabelToAnchor> labels = new ArrayList<>();
        if (dimensions == null) return labels;

        for (var dim : dimensions) {
            String key = (String) dim.get("key");
            String source = (String) dim.getOrDefault("source", "inferred");
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) dim.get("value");
            if (value == null) continue;

            switch (key) {
                case "knowledge_basis" -> {
                    extractLabelsFromList(value, "strong", key, "strong", source, labels);
                    extractLabelsFromList(value, "weak", key, "weak", source, labels);
                }
                case "interest_direction" -> {
                    extractLabelsFromList(value, "topics", key, "interest", source, labels);
                    extractLabelsFromList(value, "applications", key, "interest", source, labels);
                }
                case "error_pattern" ->
                    extractLabelsFromList(value, "tags", key, "error_prone", source, labels);
            }
        }
        return labels;
    }

    @SuppressWarnings("unchecked")
    private void extractLabelsFromList(Map<String, Object> value, String field,
                                        String dimensionKey, String relationType, String source,
                                        List<LabelToAnchor> out) {
        Object raw = value.get(field);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                // 处理纯字符串和 {label, kp_id, ...} 对象两种格式
                String text;
                if (item instanceof String s) {
                    text = s;
                } else if (item instanceof Map<?, ?> m) {
                    Object labelObj = m.get("label");
                    text = labelObj != null ? labelObj.toString() : item.toString();
                } else {
                    text = item.toString();
                }
                if (text != null && !text.isBlank()) {
                    out.add(new LabelToAnchor(text.trim(), dimensionKey, relationType, source));
                }
            }
        }
    }

    /**
     * Load KP anchors for a profile version and convert to the Agent-facing record.
     */
    public List<ResourceGenerationState.KpAnchor> loadAnchors(String profileVersionId) {
        List<ProfileKpAnchor> anchors = anchorMapper.selectList(
            new LambdaQueryWrapper<ProfileKpAnchor>()
                .eq(ProfileKpAnchor::getProfileVersionId, profileVersionId));

        List<ResourceGenerationState.KpAnchor> result = new ArrayList<>();
        for (ProfileKpAnchor a : anchors) {
            String chapterTitle = null;
            String kpName = a.getLabelText();

            if (a.getKpId() != null) {
                CourseKnowledgePoint kp = kpMapper.selectById(a.getKpId());
                if (kp != null) {
                    kpName = kp.getName();
                    chapterTitle = resolveChapterTitle(kp);
                }
            }

            result.add(new ResourceGenerationState.KpAnchor(
                a.getKpId(),
                kpName,
                chapterTitle,
                a.getDimensionKey(),
                a.getRelationType(),
                a.getScopeAtAnchor(),
                a.getConfidence() != null ? a.getConfidence().doubleValue() : 0.5
            ));
        }
        return result;
    }

    /**
     * Walk up the KP tree to find the chapter name.
     */
    private String resolveChapterTitle(CourseKnowledgePoint kp) {
        CourseKnowledgePoint current = kp;
        for (int i = 0; i < 3; i++) { // max 3 levels up
            if (current.getParentId() == null) break;
            CourseKnowledgePoint parent = kpMapper.selectById(current.getParentId());
            if (parent == null) break;
            if ("chapter".equals(parent.getKpType())) {
                return parent.getName();
            }
            current = parent;
        }
        return null;
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Internal record for a label extracted from profile dimensions.
     */
    private record LabelToAnchor(
        String labelText,
        String dimensionKey,
        String relationType,
        String source
    ) {}
}
