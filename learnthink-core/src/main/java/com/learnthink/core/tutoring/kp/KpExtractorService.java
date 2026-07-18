package com.learnthink.core.tutoring.kp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.directanswer.domain.entity.DirectAnswerKpLink;
import com.learnthink.core.directanswer.domain.entity.DirectAnswerSection;
import com.learnthink.core.directanswer.repository.DirectAnswerKpLinkMapper;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.domain.entity.TutoringSection;
import com.learnthink.core.domain.entity.TutoringSectionKpLink;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import com.learnthink.core.repository.TutoringSectionKpLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 辅导知识点抽取服务。
 * <p>
 * 在辅导章节生成完毕后，通过 LLM（优先）抽取章节内容涉及的知识点，
 * 与课程知识图谱（course_knowledge_points）模糊匹配后写入链接表。
 * LLM 失败时自动降级为分词匹配。
 * <p>
 * 异步非阻塞——抽取失败不影响主辅导流程。
 */
@Service
public class KpExtractorService {
    private static final Logger log = LoggerFactory.getLogger(KpExtractorService.class);

    /** 模糊匹配阈值：相似度 >= 此值才视为匹配 */
    private static final double FUZZY_THRESHOLD = 0.65;
    /** LLM 匹配结果最小相关度，低于此值不写入数据库 */
    private static final double MIN_RELEVANCE = 0.3;
    /** 文本匹配置信度基准 */
    private static final BigDecimal BASE_CONFIDENCE = new BigDecimal("0.85");
    /** LLM 输入最大长度（字符） */
    private static final int LLM_MAX_CONTENT_LENGTH = 4000;

    private final CourseKnowledgePointMapper kpMapper;
    private final TutoringSectionKpLinkMapper kpLinkMapper;
    private final DirectAnswerKpLinkMapper daKpLinkMapper;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public KpExtractorService(CourseKnowledgePointMapper kpMapper,
                              TutoringSectionKpLinkMapper kpLinkMapper,
                              DirectAnswerKpLinkMapper daKpLinkMapper,
                              @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                              ObjectMapper objectMapper) {
        this.kpMapper = kpMapper;
        this.kpLinkMapper = kpLinkMapper;
        this.daKpLinkMapper = daKpLinkMapper;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 对单个 section 内容抽取知识点并写入链接表（LLM 优先，分词 fallback）。
     */
    public void extractAndLink(TutoringSection section, String courseId) {
        if (section == null || section.getContent() == null || section.getContent().isBlank()) {
            return;
        }
        try {
            List<KpMatch> matches = extractWithLLM(section.getContent(), courseId);
            saveLinksForTutoring(section.getId(), matches);
        } catch (Exception e) {
            log.warn("LLM KP extraction failed for section={}, falling back to word match", 
                section.getSectionId(), e);
            extractWithWordMatchAndLink(section, courseId);
        }
    }

    /**
     * 对 DirectAnswer section 内容抽取知识点并写入 direct_answer_kp_links 表（LLM 优先）。
     */
    public void extractAndLinkForDirectAnswer(DirectAnswerSection section, String courseId) {
        if (section == null || section.getContent() == null || section.getContent().isBlank()) {
            return;
        }
        try {
            List<KpMatch> matches = extractWithLLM(section.getContent(), courseId);
            saveLinksForDirectAnswer(section.getId(), matches);
        } catch (Exception e) {
            log.warn("LLM KP extraction failed for da_section={}, falling back to word match",
                section.getSectionId(), e);
            extractWithWordMatchForDirectAnswer(section, courseId);
        }
    }

    // ==================== LLM 语义抽取（新增） ====================

    /**
     * 使用 LLM 从内容中语义抽取匹配的知识点。
     */
    private List<KpMatch> extractWithLLM(String content, String courseId) {
        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
            new LambdaQueryWrapper<CourseKnowledgePoint>()
                .eq(CourseKnowledgePoint::getCourseId, courseId));
        if (allKps.isEmpty()) {
            log.debug("No KPs defined for course {}, skipping LLM extraction", courseId);
            return List.of();
        }

        // 用序号代替 ID，避免 LLM 输出长 hash 时出错，程序自己映射回真实 ID
        StringBuilder kpIndex = new StringBuilder();
        for (int i = 0; i < allKps.size(); i++) {
            CourseKnowledgePoint kp = allKps.get(i);
            kpIndex.append(String.format("%d. 名称: %s%s%n",
                i + 1, kp.getName(),
                kp.getDescription() != null && !kp.getDescription().isBlank()
                    ? ", 描述: " + kp.getDescription() : ""));
        }

        String truncatedContent = content.length() > LLM_MAX_CONTENT_LENGTH
            ? content.substring(0, LLM_MAX_CONTENT_LENGTH) : content;

        String prompt = String.format("""
            你是一个知识点匹配专家。给定一段教学内容和课程知识点列表，请识别内容中涉及了哪些知识点。
            
            ## 知识点列表（用序号代表知识点）
            %s
            
            ## 教学内容
            %s
            
            ## 要求
            请输出 JSON 数组，每项包含 kpIndex（知识点序号，整数）、relevance（相关度 0-1）。
            只输出确实在内容中出现的知识点。如果内容涉及的知识点不在列表中，不输出该项。
            匹配要基于语义理解，不仅仅是关键词匹配。
            只输出 relevance >= 0.3 的知识点，低于 0.3 的不要输出。如果没有任何匹配，输出空数组 []。
            
            ```json
            [{"kpIndex": 1, "relevance": 0.95}]
            ```
            """, kpIndex, truncatedContent);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            if (response == null || response.isBlank()) {
                throw new RuntimeException("LLM returned empty response");
            }
            log.info("LLM KP extraction raw response for courseId={}: {}", courseId, response);
            List<KpMatch> rawMatches = parseKpMatches(response);
            // 将 LLM 返回的序号映射为真实的 kpId
            List<KpMatch> matches = new ArrayList<>();
            for (KpMatch m : rawMatches) {
                int idx;
                try {
                    idx = Integer.parseInt(m.kpId());
                } catch (NumberFormatException e) {
                    log.warn("LLM returned non-integer kpIndex '{}', skipping", m.kpId());
                    continue;
                }
                if (idx < 1 || idx > allKps.size()) {
                    log.warn("LLM returned out-of-range kpIndex {}, skipping", idx);
                    continue;
                }
                matches.add(new KpMatch(allKps.get(idx - 1).getId(), m.relevance()));
            }
            log.info("LLM KP extraction parsed {} matches for courseId={}: {}", matches.size(), courseId,
                matches.stream().map(m -> m.kpId() + "(" + m.relevance() + ")").collect(Collectors.joining(", ")));
            return matches;
        } catch (Exception e) {
            throw new RuntimeException("LLM KP extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组为 KpMatch 列表。
     */
    private List<KpMatch> parseKpMatches(String llmResponse) {
        try {
            String json = llmResponse.trim();
            int jsonStart = json.indexOf("[");
            int jsonEnd = json.lastIndexOf("]");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = json.substring(jsonStart, jsonEnd + 1);
            }
            List<Map<String, Object>> rawList = objectMapper.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});
            return rawList.stream()
                .map(m -> new KpMatch(
                    String.valueOf(m.get("kpIndex")),
                    ((Number) m.getOrDefault("relevance", 0.85)).doubleValue()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse LLM KP response, returning empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 LLM 匹配结果写入 tutoring_section_kp_links。
     */
    private void saveLinksForTutoring(String sectionId, List<KpMatch> matches) {
        int saved = 0;
        for (KpMatch match : matches) {
            if (match.relevance() < MIN_RELEVANCE) continue;
            BigDecimal relevance = BigDecimal.valueOf(match.relevance())
                .setScale(2, RoundingMode.HALF_UP);
            if (relevance.compareTo(BigDecimal.ONE) > 0) relevance = BigDecimal.ONE;

            TutoringSectionKpLink link = new TutoringSectionKpLink();
            link.setId(UUID.randomUUID().toString());
            link.setSectionId(sectionId);
            link.setKpId(match.kpId());
            link.setRelevance(relevance);
            kpLinkMapper.insert(link);
            saved++;
        }
        if (saved > 0) {
            log.info("Linked {} KPs (LLM) to section {} (filtered {} below relevance {})",
                saved, sectionId, matches.size() - saved, MIN_RELEVANCE);
        } else if (!matches.isEmpty()) {
            log.info("All {} KPs below relevance {} for section {}, skipped", matches.size(), MIN_RELEVANCE, sectionId);
        }
    }

    /**
     * 将 LLM 匹配结果写入 direct_answer_kp_links。
     */
    private void saveLinksForDirectAnswer(String sectionId, List<KpMatch> matches) {
        int saved = 0;
        for (KpMatch match : matches) {
            if (match.relevance() < MIN_RELEVANCE) continue;
            BigDecimal relevance = BigDecimal.valueOf(match.relevance())
                .setScale(2, RoundingMode.HALF_UP);
            if (relevance.compareTo(BigDecimal.ONE) > 0) relevance = BigDecimal.ONE;

            DirectAnswerKpLink link = new DirectAnswerKpLink();
            link.setId(UUID.randomUUID().toString());
            link.setSectionId(sectionId);
            link.setKpId(match.kpId());
            link.setRelevance(relevance);
            daKpLinkMapper.insert(link);
            saved++;
        }
        if (saved > 0) {
            log.info("Linked {} KPs (LLM) to direct answer section {} (filtered {} below relevance {})",
                saved, sectionId, matches.size() - saved, MIN_RELEVANCE);
        } else if (!matches.isEmpty()) {
            log.info("All {} KPs below relevance {} for direct answer section {}, skipped",
                matches.size(), MIN_RELEVANCE, sectionId);
        }
    }

    /**
     * LLM 匹配结果记录。
     */
    private record KpMatch(String kpId, double relevance) {}

    // ==================== 分词 Fallback（原有逻辑重命名保留） ====================

    /**
     * 分词匹配 fallback — 辅导章节。
     */
    private void extractWithWordMatchAndLink(TutoringSection section, String courseId) {
        if (section == null || section.getContent() == null || section.getContent().isBlank()) {
            return;
        }
        List<String> keywords = extractKeywords(section.getContent());
        if (keywords.isEmpty()) {
            log.debug("No keywords extracted from section {}", section.getSectionId());
            return;
        }

        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
            new LambdaQueryWrapper<CourseKnowledgePoint>()
                .eq(CourseKnowledgePoint::getCourseId, courseId));
        if (allKps.isEmpty()) {
            log.debug("No KPs defined for course {}, section {}", courseId, section.getSectionId());
            return;
        }
        log.debug("Matching {} keywords against {} KPs for section {}",
            keywords.size(), allKps.size(), section.getSectionId());

        Set<String> linkedKpIds = new HashSet<>();
        for (String kw : keywords) {
            CourseKnowledgePoint bestMatch = null;
            double bestScore = 0;

            for (CourseKnowledgePoint kp : allKps) {
                double score = matchScore(kw, kp);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = kp;
                }
            }

            if (bestMatch != null && bestScore >= FUZZY_THRESHOLD) {
                if (!linkedKpIds.add(bestMatch.getId())) {
                    continue;
                }
                BigDecimal relevance = BASE_CONFIDENCE
                    .multiply(new BigDecimal(bestScore))
                    .setScale(2, RoundingMode.HALF_UP);
                if (relevance.compareTo(BigDecimal.ONE) > 0) relevance = BigDecimal.ONE;

                TutoringSectionKpLink link = new TutoringSectionKpLink();
                link.setId(UUID.randomUUID().toString());
                link.setSectionId(section.getId());
                link.setKpId(bestMatch.getId());
                link.setRelevance(relevance);
                kpLinkMapper.insert(link);
            }
        }

        if (!linkedKpIds.isEmpty()) {
            log.info("Linked {} KPs to section {} (word match fallback, matched from {} keywords)",
                linkedKpIds.size(), section.getSectionId(), keywords.size());
        }
    }

    /**
     * 分词匹配 fallback — DirectAnswer 章节。
     */
    private void extractWithWordMatchForDirectAnswer(DirectAnswerSection section, String courseId) {
        if (section == null || section.getContent() == null || section.getContent().isBlank()) {
            return;
        }
        List<String> keywords = extractKeywords(section.getContent());
        if (keywords.isEmpty()) {
            log.debug("No keywords extracted from direct answer section {}", section.getSectionId());
            return;
        }
        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
            new LambdaQueryWrapper<CourseKnowledgePoint>()
                .eq(CourseKnowledgePoint::getCourseId, courseId));
        if (allKps.isEmpty()) {
            log.debug("No KPs defined for course {}, direct answer section {}", courseId, section.getSectionId());
            return;
        }
        Set<String> linkedKpIds = new HashSet<>();
        for (String kw : keywords) {
            CourseKnowledgePoint bestMatch = null;
            double bestScore = 0;
            for (CourseKnowledgePoint kp : allKps) {
                double score = matchScore(kw, kp);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = kp;
                }
            }
            if (bestMatch != null && bestScore >= FUZZY_THRESHOLD) {
                if (!linkedKpIds.add(bestMatch.getId())) continue;
                BigDecimal relevance = BASE_CONFIDENCE
                    .multiply(new BigDecimal(bestScore))
                    .setScale(2, RoundingMode.HALF_UP);
                if (relevance.compareTo(BigDecimal.ONE) > 0) relevance = BigDecimal.ONE;

                DirectAnswerKpLink link = new DirectAnswerKpLink();
                link.setId(UUID.randomUUID().toString());
                link.setSectionId(section.getId());
                link.setKpId(bestMatch.getId());
                link.setRelevance(relevance);
                daKpLinkMapper.insert(link);
            }
        }
        if (!linkedKpIds.isEmpty()) {
            log.info("Linked {} KPs to direct answer section {} (word match fallback, matched from {} keywords)",
                linkedKpIds.size(), section.getSectionId(), keywords.size());
        }
    }

    /**
     * 从文本中提取关键词列表。
     * <p>
     * 当前使用简单的标点分词 + 去停用词策略。
     * 未来可替换为 LLM 调用或 NER 模型。
     * </p>
     */
    private List<String> extractKeywords(String text) {
        // 按标点和空白分割，提取有意义的片段
        String[] raw = text.split("[，。！？；：、\\n\\r\\s,.!?;:]+");
        List<String> keywords = new ArrayList<>();
        for (String s : raw) {
            String trimmed = s.trim();
            // 过滤过短或过长的片段
            if (trimmed.length() < 2 || trimmed.length() > 30) continue;
            // 过滤纯数字、纯标点
            if (trimmed.matches("[\\d\\p{Punct}]+")) continue;
            // 过滤常见停用词
            if (isStopWord(trimmed)) continue;
            keywords.add(trimmed);
        }
        return keywords;
    }

    /**
     * 计算关键词与知识点的匹配分数。
     */
    private double matchScore(String keyword, CourseKnowledgePoint kp) {
        // 与名称精确/包含匹配
        if (kp.getName() != null && (kp.getName().equals(keyword) || kp.getName().contains(keyword))) {
            return 0.95;
        }
        // 模糊匹配名称
        double nameScore = kp.getName() != null
            ? similarity(keyword, kp.getName().toLowerCase().trim()) : 0.0;

        // 与 keyword 列表匹配
        double kwScore = 0.0;
        try {
            if (kp.getKeywords() != null && !kp.getKeywords().isBlank()) {
                String cleaned = kp.getKeywords().replace("\"", "").replace("[", "").replace("]", "");
                String[] kws = cleaned.split(",");
                for (String kw : kws) {
                    String kwTrimmed = kw.trim().toLowerCase();
                    if (kwTrimmed.equals(keyword.toLowerCase()) || kwTrimmed.contains(keyword.toLowerCase())) {
                        kwScore = 0.90;
                        break;
                    }
                    double s = similarity(keyword, kwTrimmed);
                    if (s > kwScore) kwScore = s;
                }
            }
        } catch (Exception e) {
            log.trace("Keyword parsing error for kp {}: {}", kp.getId(), e.getMessage());
        }

        return Math.max(nameScore, kwScore);
    }

    /**
     * 字符串相似度：Levenshtein + 包含关系。
     */
    private double similarity(String a, String b) {
        if (a.equalsIgnoreCase(b)) return 1.0;
        if (a.toLowerCase().contains(b.toLowerCase()) || b.toLowerCase().contains(a.toLowerCase())) return 0.80;

        double lev = 1.0 - (double) levenshteinDistance(a, b) / Math.max(a.length(), b.length());
        return Math.max(0.0, lev);
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

    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
        "一个", "这", "那", "也", "与", "及", "或", "但", "而", "可以", "能够",
        "我们", "你", "你们", "他们", "它", "要", "会", "能", "对", "从", "到",
        "所以", "因为", "如果", "然后", "接着", "首先", "最后", "关于", "通过",
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "can", "shall", "to", "of", "in", "for",
        "on", "with", "at", "by", "from", "as", "into", "through", "during",
        "this", "that", "these", "those", "it", "its", "and", "but", "or",
        "not", "no", "if", "then", "than", "so", "very", "just", "about"
    );

    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase());
    }
}
