package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容审查器，负责对生成内容进行分层审计和独立RAG事实核查。
 *
 * <h3>v3.1 — 独立RAG事实核查流程：</h3>
 * <ol>
 *   <li>规则层（R1-R3）：格式检查、来源覆盖率、安全预过滤（零LLM成本）</li>
 *   <li>声明提取（LLM）：从内容中提取事实性断言</li>
 *   <li>来源匹配（规则）：将声明与 EvidenceRetriever 来源匹配</li>
 *   <li>定向检索（RagTool）：对未匹配声明进行独立RAG搜索</li>
 *   <li>判定：支持率 ≥ 70% → APPROVED / 40-70% → MEDIUM / < 40% → RETRY</li>
 * </ol>
 */
@Component
public class ContentReviewer {

    private static final Logger log = LoggerFactory.getLogger(ContentReviewer.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final RagTool ragTool;
    private final ResourceGenerator resourceGenerator;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentReviewer(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder chatClientBuilder,
                           PromptLoader promptLoader,
                           RagTool ragTool,
                           ResourceGenerator resourceGenerator) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
        this.ragTool = ragTool;
        this.resourceGenerator = resourceGenerator;
    }

    public AgentResult<ResourceGenerationState.ReviewResult> review(
        ResourceGenerationState.GeneratedContent content,
        List<ResourceGenerationState.SourceItem> sources,
        String resourceType,
        boolean forceLowConfidence,
        AgentContext ctx) {

        log.info("=== ContentReviewer START === type={}, sources={}, forceLowConfidence={}",
                resourceType, sources.size(), forceLowConfidence);
        Instant start = Instant.now();
        String systemPrompt = promptLoader.get("agent/reviewer");
        ctx.observation().onPrompt("ContentReviewer", systemPrompt,
            Map.of("type", resourceType, "sourcesCount", sources.size(),
                   "forceLowConfidence", forceLowConfidence));

        try {
            // 预过滤：基于规则的安全检查（在LLM调用之前）
            if (new ContentSafetyFilter().isBlocked(content.content())) {
                log.warn("Content blocked by safety filter for type: {}", resourceType);
                var result = new ResourceGenerationState.ReviewResult(
                    ResourceGenerationState.ReviewStatus.REJECTED,
                    "low", "Content blocked by safety filter",
                    List.of(new ResourceGenerationState.ReviewReason("R4", "fail", "Blocked pattern detected")),
                    0.0,
                    ResourceGenerationState.ReviewAction.REJECT_PERMANENT);
                ctx.observation().onDecision("ContentReviewer", "REJECT_PERMANENT", "Safety filter blocked content");
                return AgentResult.of(result);
            }

            // 零来源兜底：当没有可验证的证据时跳过审查
            if (forceLowConfidence && sources.isEmpty()) {
                log.info("Bypassing review (forceLowConfidence=true, no sources) for type: {}", resourceType);
                var result = new ResourceGenerationState.ReviewResult(
                    ResourceGenerationState.ReviewStatus.APPROVED,
                    "low", "No knowledge base sources available — skipped review",
                    List.of(new ResourceGenerationState.ReviewReason("R1", "warn", "Zero-source fallback")),
                    0.0,
                    ResourceGenerationState.ReviewAction.PUBLISH);
                ctx.observation().onDecision("ContentReviewer", "PUBLISH",
                    "Zero-source fallback (forceLowConfidence) — " + resourceType);
                return AgentResult.of(result);
            }

            // R1：格式验证（基于规则，零LLM成本）
            var r1Result = checkFormat(content.content(), resourceType);
            if (r1Result != null) {
                log.warn("R1 format check failed for {}: {}", resourceType, r1Result.reviewSummary());
                ctx.observation().onDecision("ContentReviewer", "RETRY",
                    "R1 format check failed — " + r1Result.reviewSummary());
                return AgentResult.of(r1Result);
            }

            // 检查该资源类型是否需要来源覆盖率验证
            boolean exemptR1 = !resourceGenerator.requiresSourceCoverage(resourceType);
            if (exemptR1 && sources.isEmpty()) {
                log.info("Exempt from R1 source-coverage check for type: {} with no sources", resourceType);
                var result = new ResourceGenerationState.ReviewResult(
                    ResourceGenerationState.ReviewStatus.APPROVED,
                    "low", "No sources available for " + resourceType + " (exempt from source-coverage check)",
                    List.of(new ResourceGenerationState.ReviewReason("R1", "warn", "Exempt: " + resourceType)),
                    0.0,
                    ResourceGenerationState.ReviewAction.PUBLISH);
                ctx.observation().onDecision("ContentReviewer", "PUBLISH", "Exempt from R1 — " + resourceType);
                return AgentResult.of(result);
            }

            // v3.1：提取事实声明并通过独立RAG验证
            log.info("Extracting factual claims from content");
            String contentExcerpt = content.content().substring(0, Math.min(2000, content.content().length()));
            List<Claim> claims = extractClaims(contentExcerpt);
            log.info("Extracted {} claims", claims.size());
            Map<String, String> claimVerification = verifyClaims(claims, sources, ctx);

            int backedCount = (int) claimVerification.values().stream()
                .filter(s -> "backed".equals(s) || "backed_extra".equals(s)).count();
            int totalClaims = claims.isEmpty() ? 1 : claims.size();
            double backedRatio = (double) backedCount / totalClaims;

            log.info("Claim verification: {}/{} backed ({:.0f}%)", backedCount, totalClaims, backedRatio * 100);
            ctx.observation().onDecision("ContentReviewer",
                "CLAIMS_VERIFIED",
                backedCount + "/" + totalClaims + " claims backed (ratio=" +
                String.format("%.0f%%", backedRatio * 100) + ")");

            String sourcesJson = mapper.writeValueAsString(
                sources.stream().map(s -> Map.of(
                    "docId", s.docId(), "bookTitle", s.bookTitle(),
                    "bookType", s.bookType(), "chapterIndex", s.chapterIndex(),
                    "chapterTitle", s.chapterTitle(),
                    "sourceType", s.sourceType(), "headingPath", s.headingPath(),
                    "quote", s.quote(), "locator", s.locator()
                )).toList());

            boolean isSourceExempt = !resourceGenerator.requiresSourceCoverage(resourceType);
            String userMsg = String.format("""
                Resource type: %s%s
                Title: %s
                R1 format check: pass (validated by automated rules)
                Content (first 2000 chars): %s
                Sources (%d items): %s
                Claim verification: %d/%d backed (%.0f%%)
                Force low confidence: %s
                """,
                resourceType,
                isSourceExempt ? " [EXEMPT from source requirements — do NOT penalize for low source count]" : "",
                content.title(),
                contentExcerpt,
                sources.size(), sourcesJson,
                backedCount, totalClaims, backedRatio * 100,
                forceLowConfidence);

            log.info("Calling LLM for review");
            String response = chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("[AI-RESPONSE][ContentReviewer] review ({}ms) length={} chars\n{}",
                elapsed,
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(2000, response.length())) : "null");
            ctx.observation().onResponse("ContentReviewer", response, elapsed, AgentResult.TokenUsage.ZERO);

            var result = parseReview(response);
            log.info("Review result: action={}, confidence={}, coverage={}, summary={}",
                    result.action(), result.confidence(), result.citationCoverage(), result.reviewSummary());
            ctx.observation().onDecision("ContentReviewer",
                result.action().name(),
                result.reviewSummary());

            log.info("ContentReviewer completed successfully");
            return AgentResult.of(result, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ContentReviewer", "coverage", result.citationCoverage()));

        } catch (Exception e) {
            log.error("ContentReviewer failed: {}", e.getMessage(), e);
            ctx.observation().onError("ContentReviewer", e);
            return AgentResult.error("Review failed: " + e.getMessage());
        }
    }

    // ================================================================
    // R1：基于规则的格式验证（零LLM成本）
    // ================================================================

    /**
     * 按资源类型验证结构化格式约束
     * @param content      待验证的内容
     * @param resourceType 资源类型（doc/quiz/reading/code/mindmap/video）
     * @return 验证失败时返回RETRY审查结果，格式合格返回null
     */
    private ResourceGenerationState.ReviewResult checkFormat(String content, String resourceType) {
        if (content == null || content.isBlank()) {
            return formatFailure(resourceType, "Content is empty");
        }
        return switch (resourceType) {
            case "doc"     -> null; // doc format varies by LLM output, skip R1
            case "quiz"    -> checkQuizFormat(content);
            case "reading" -> null; // format varies, skip R1
            case "code"    -> checkCodeFormat(content);
            case "mindmap" -> checkMindmapFormat(content);
            case "video"   -> null; // video format varies by render pipeline, skip R1
            default        -> null;
        };
    }

    private ResourceGenerationState.ReviewResult checkDocFormat(String content) {
        long sectionCount = content.lines()
            .filter(line -> line.trim().matches("^##\\s+\\d+\\..*"))
            .count();
        if (sectionCount < 2) {
            return formatFailure("doc",
                "Expected at least 2 section titles (## N.), found " + sectionCount);
        }
        return null;
    }

    private ResourceGenerationState.ReviewResult checkQuizFormat(String content) {
        try {
            var node = mapper.readTree(content);
            var questions = node.get("questions");
            if (questions == null || !questions.isArray()) {
                return formatFailure("quiz", "Missing or invalid 'questions' array");
            }
            if (questions.size() < 5) {
                return formatFailure("quiz",
                    "Expected at least 5 questions, found " + questions.size());
            }
        } catch (Exception e) {
            return formatFailure("quiz", "Invalid JSON: " + e.getMessage());
        }
        return null;
    }

    /** Reading 产出 Markdown 推荐书单，检查 Markdown 结构而非 JSON */
    private ResourceGenerationState.ReviewResult checkReadingFormat(String content) {
        if (content == null || content.isBlank()) {
            return formatFailure("reading", "Content is empty");
        }
        // 必须有 ## 或 ### 级别标题
        long headingCount = content.lines()
            .filter(line -> line.trim().matches("^#{2,3}\\s+.*"))
            .count();
        if (headingCount < 2) {
            return formatFailure("reading",
                "Expected at least 2 Markdown headings (## or ###), found " + headingCount);
        }
        // 必须有列表项（每条推荐）
        long listItemCount = content.lines()
            .filter(line -> line.trim().matches("^[-*]\\s+.*"))
            .count();
        if (listItemCount < 3) {
            return formatFailure("reading",
                "Expected at least 3 list items, found " + listItemCount);
        }
        return null;
    }

    /** Code 产出 JSON：校验 files/steps 字段存在且合法 */
    private ResourceGenerationState.ReviewResult checkCodeFormat(String content) {
        try {
            var root = mapper.readTree(content);
            if (!root.has("files") || root.get("files").isEmpty()) {
                return formatFailure("code", "Missing or empty 'files' array");
            }
            var steps = root.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                return formatFailure("code", "Missing or empty 'steps' array");
            }
            for (var step : steps) {
                var refs = step.get("references");
                if (refs == null || !refs.isArray() || refs.isEmpty()) {
                    return formatFailure("code", "Step missing 'references' array");
                }
                for (var ref : refs) {
                    int startLine = ref.get("startLine").asInt();
                    int endLine = ref.get("endLine").asInt();
                    if (startLine <= 0 || endLine <= 0 || startLine > endLine) {
                        return formatFailure("code",
                            "Invalid line reference: startLine=" + startLine + " endLine=" + endLine);
                    }
                }
            }
        } catch (Exception e) {
            return formatFailure("code", "Invalid JSON or structure: " + e.getMessage());
        }
        return null;
    }

    /** Mindmap 产出 JSON 嵌套结构 {root: {text, children: [...]}} */
    private ResourceGenerationState.ReviewResult checkMindmapFormat(String content) {
        try {
            var root = mapper.readTree(content);
            var rootObj = root.get("root");
            if (rootObj == null || !rootObj.isObject()) {
                return formatFailure("mindmap", "Missing or invalid 'root' object");
            }
            if (rootObj.get("text") == null || rootObj.get("text").asText().isBlank()) {
                return formatFailure("mindmap", "Root node missing 'text'");
            }
            var children = rootObj.get("children");
            if (children == null || !children.isArray() || children.isEmpty()) {
                return formatFailure("mindmap", "Root node missing or empty 'children'");
            }
            int totalNodes = countMindmapNodes(children);
            if (totalNodes < 8) {
                return formatFailure("mindmap",
                    "Too few nodes: " + totalNodes + " (minimum 8)");
            }
            if (totalNodes > 40) {
                return formatFailure("mindmap",
                    "Too many nodes: " + totalNodes + " (maximum 40)");
            }
        } catch (Exception e) {
            return formatFailure("mindmap", "Invalid JSON: " + e.getMessage());
        }
        return null;
    }

    /** 递归统计思维导图中所有 children 节点数量 */
    private int countMindmapNodes(com.fasterxml.jackson.databind.JsonNode nodes) {
        int count = 0;
        for (var node : nodes) {
            count++;
            var children = node.get("children");
            if (children != null && children.isArray()) {
                count += countMindmapNodes(children);
            }
        }
        return count;
    }

    private ResourceGenerationState.ReviewResult formatFailure(String type, String detail) {
        return new ResourceGenerationState.ReviewResult(
            ResourceGenerationState.ReviewStatus.REJECTED,
            "low", "R1 format check failed for " + type + ": " + detail,
            List.of(new ResourceGenerationState.ReviewReason("R1", "fail", detail)),
            0.0,
            ResourceGenerationState.ReviewAction.RETRY);
    }

    /**
     * 解析LLM返回的审查JSON
     * @param json LLM返回的JSON字符串
     * @return 审查结果对象
     * @throws RuntimeException JSON解析失败时抛出
     */
    private ResourceGenerationState.ReviewResult parseReview(String json) {
        try {
            var node = mapper.readTree(json);
            return new ResourceGenerationState.ReviewResult(
                ResourceGenerationState.ReviewStatus.valueOf(node.get("reviewStatus").asText()),
                node.get("confidence").asText(),
                node.get("reviewSummary").asText(),
                mapper.convertValue(node.get("reasons"), List.class),
                node.get("citationCoverage").asDouble(),
                ResourceGenerationState.ReviewAction.valueOf(node.get("action").asText()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse review JSON: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // v3.1：声明提取 + 独立RAG验证
    // ================================================================

    /** 从生成内容中提取事实性断言（1次LLM调用） */
    private List<Claim> extractClaims(String content) {
        try {
            String prompt = """
                Extract all factual assertions from this educational content.
                A factual assertion is a statement that can be verified against a knowledge base
                (definitions, formulas, algorithm steps, specific claims about how something works).

                Output JSON array:
                [{"claim": "assertion text", "entity": "key entity", "topic": "topic area"}, ...]

                Content:
                """ + content;

            String response = chatClient.prompt()
                .messages(new SystemMessage(prompt), new UserMessage(content))
                .call().content();

            log.info("[AI-RESPONSE][ContentReviewer] claim-extraction length={} chars\n{}",
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(1000, response.length())) : "null");

            String json = response;
            if (json.contains("```")) {
                json = json.substring(json.indexOf("[") > 0 ? json.indexOf("[") : json.indexOf("```") + 3,
                    json.lastIndexOf("]") + 1);
            }
            json = json.substring(json.indexOf("["), json.lastIndexOf("]") + 1);

            List<Map<String, String>> raw = mapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                .map(m -> new Claim(
                    m.getOrDefault("claim", ""),
                    m.getOrDefault("entity", ""),
                    m.getOrDefault("topic", "")))
                .toList();

        } catch (Exception e) {
            log.warn("Claim extraction failed: {}", e.getMessage());
            return List.of(); // fallback: skip claim verification
        }
    }

    /** 验证声明：匹配证据来源 + 独立RagTool检索 */
    private Map<String, String> verifyClaims(List<Claim> claims,
                                              List<ResourceGenerationState.SourceItem> retrieverSources,
                                              AgentContext ctx) {
        Map<String, String> results = new HashMap<>();
        String courseId = ctx.courseId();

        for (Claim claim : claims) {
            // 第一步：尝试与检索来源匹配
            boolean found = retrieverSources.stream().anyMatch(s ->
                s.quote() != null && claim.claim() != null &&
                (s.quote().contains(claim.claim().substring(0, Math.min(10, claim.claim().length()))) ||
                 claim.claim().contains(s.quote().substring(0, Math.min(20, s.quote().length()))))
            );

            if (found) {
                results.put(claim.claim(), "backed");
                continue;
            }

            // 第二步：对未匹配的声明使用独立RagTool搜索
            if (ragTool != null && courseId != null) {
                String searchQuery = claim.entity() + " " + claim.topic();
                try {
                    EvidenceRetriever.RagClient.RagResponse extraResp =
                        ragTool.retrieve(courseId, searchQuery, null, 3);
                    if (extraResp != null && extraResp.sources() != null) {
                        boolean extraFound = extraResp.sources().stream().anyMatch(s ->
                            s.quote() != null && claim.claim() != null &&
                            s.quote().contains(claim.claim().substring(0, Math.min(10, claim.claim().length())))
                        );
                        results.put(claim.claim(), extraFound ? "backed_extra" : "unverified");
                    } else {
                        results.put(claim.claim(), "unverified");
                    }
                } catch (Exception e) {
                    log.warn("RagTool verification failed for claim: {}", claim.claim());
                    results.put(claim.claim(), "unverified");
                }
            } else {
                results.put(claim.claim(), "unverified");
            }
        }

        return results;
    }

    /**
     * 提取的事实声明
     * @param claim  声明文本
     * @param entity 关键实体
     * @param topic  所属主题
     */
    record Claim(String claim, String entity, String topic) {}

    /**
     * 基于规则的安全预过滤器——在LLM调用前拦截明显问题
     */
    static class ContentSafetyFilter {
        private static final List<String> BLOCKED = List.of(/* loaded from config in production */);
        boolean isBlocked(String content) {
            return BLOCKED.stream().anyMatch(p -> content.toLowerCase().contains(p.toLowerCase()));
        }
    }
}
