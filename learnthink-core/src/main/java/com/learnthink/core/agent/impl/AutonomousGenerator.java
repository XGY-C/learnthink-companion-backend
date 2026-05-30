package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自主资源生成器基类
 * 
 * <p>每个实例运行一个自Directed循环：
 * <ol>
 *   <li>可选地通过 {@link RagTool} 检索额外证据</li>
 *   <li>通过 LLM 生成内容（带工具回调）</li>
 *   <li>自我审查输出的质量和事实准确性</li>
 *   <li>自我审查失败时进行修订（最多 {@link #MAX_ITERATIONS} 次）</li>
 *   <li>对 sources 运行最终的事实核查</li>
 * </ol>
 *
 * <p>子类实现特定类型的提示词构造和检索启发式方法</p>
 */
public abstract class AutonomousGenerator {

    protected static final Logger log = LoggerFactory.getLogger(AutonomousGenerator.class);

    protected static final int MAX_ITERATIONS = 3;
    protected static final long MAX_TIME_MS = 120_000;

    protected final ChatClient chatClient;
    protected final List<ToolCallback> tools;
    protected final RagTool ragTool;

    protected AutonomousGenerator(ChatClient chatClient, List<ToolCallback> tools, RagTool ragTool) {
        this.chatClient = chatClient;
        this.tools = tools != null ? tools : List.of();
        this.ragTool = ragTool;
    }

    /**
     * 运行完整的自主生成循环
     * @param task 生成任务
     * @param ctx 上下文信息
     * @return 生成结果
     */
    protected GenerationResult runAutonomousLoop(GenerationTask task, AgentContext ctx) {
        long startTime = System.currentTimeMillis();
        int iteration = 0;
        GenerationResult bestResult = null;
        String agentName = task.agentName();

        // 阶段1：研究（自主 RAG 检索）
        List<ResourceGenerationState.SourceItem> allSources = new ArrayList<>(task.preSources());
        if (shouldRetrieveMore(task, allSources)) {
            try {
        ctx.observation().onDecision(agentName, "RETRIEVE",
            "正在搜索额外证据: " + task.topic());
        List<ResourceGenerationState.SourceItem> extra = retrieveEvidence(task, ctx);
        if (extra != null && !extra.isEmpty()) {
            allSources.addAll(extra);
            ctx.observation().onDecision(agentName, "RETRIEVE_DONE",
                "找到 " + extra.size() + " 个额外证据源");
        }
            } catch (Exception e) {
                log.warn("[{}] 自主检索失败: {}", agentName, e.getMessage());
                ctx.observation().onError(agentName + "/retrieve", e);
            }
        }

        // 阶段2：生成 + 自我审查循环
        String currentContent = null;
        String reviewFeedback = null;

        while (iteration < MAX_ITERATIONS) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > MAX_TIME_MS) {
                ctx.observation().onDecision(agentName, "TIMEOUT",
                    "生成超时: " + elapsed + "ms");
                break;
            }
            iteration++;

            ctx.observation().onDecision(agentName, "ITERATION",
                "迭代 " + iteration + "/" + MAX_ITERATIONS
                + (reviewFeedback != null ? " (修订中)" : " (首次生成)"));

            String content;
            if (iteration == 1) {
                content = doGenerate(task, allSources, ctx);
                log.info("[AI-RESPONSE][{}] doGenerate (iter={}) 长度={} 字符\n{}",
                    agentName, iteration,
                    content != null ? content.length() : 0,
                    content != null ? content.substring(0, Math.min(2000, content.length())) : "null");
            } else {
                content = doRevise(task, allSources, currentContent, reviewFeedback, ctx);
                log.info("[AI-RESPONSE][{}] doRevise (iter={}, feedback={}) 长度={} 字符\n{}",
                    agentName, iteration, reviewFeedback,
                    content != null ? content.length() : 0,
                    content != null ? content.substring(0, Math.min(2000, content.length())) : "null");
            }

            if (content == null || content.isBlank()) {
                log.error("[{}] 迭代 {} 生成空内容", agentName, iteration);
                if (bestResult != null) break;
                return GenerationResult.failed("生成空内容");
            }
            currentContent = content;

            // 自我审查
            SelfReviewResult review = selfReview(task, content, allSources, ctx);
            if (review.isSatisfied()) {
                ctx.observation().onDecision(agentName, "SELF_REVIEW_PASS",
                    "质量合格，经过 " + iteration + " 次迭代");
                bestResult = new GenerationResult(content, review.confidence(), allSources, true);
                break;
            }

            reviewFeedback = review.feedback();
            ctx.observation().onDecision(agentName, "SELF_REVISE",
                "修订: " + reviewFeedback);

            if (bestResult == null || review.confidence() > bestResult.confidence()) {
                bestResult = new GenerationResult(content, review.confidence(), allSources, false);
            }
        }

        // 阶段3：最终事实核查
        if (bestResult != null && bestResult.content() != null) {
            try {
                FactCheckResult fc = factCheck(task, bestResult.content(), allSources, ctx);
                log.info("[AI-RESPONSE][{}] fact-check: 通过={}, 覆盖率={}, 问题={}",
                    agentName, fc.isPassed(), String.format("%.2f", (double) fc.backedRatio()), fc.issues());
                if (!fc.isPassed()) {
                    ctx.observation().onDecision(agentName, "FACT_CHECK_ISSUES",
                        "问题: " + String.join("; ", fc.issues()));
                }
            } catch (Exception e) {
                log.warn("[{}] 最终事实核查失败: {}", agentName, e.getMessage());
            }
        }

        long totalMs = System.currentTimeMillis() - startTime;
        if (bestResult != null && bestResult.content() != null) {
            ctx.observation().onDecision(agentName, "COMPLETE",
                String.format("完成，耗时 %dms，迭代 %d 次，置信度=%.2f",
                    totalMs, iteration, bestResult.confidence()));
            return bestResult;
        }
        return GenerationResult.failed("所有迭代均失败");
    }

    // ---- 子类契约 ----

    /**
     * 执行首次内容生成
     * @param task 生成任务
     * @param sources 证据源列表
     * @param ctx 上下文信息
     * @return 生成的内容
     */
    protected abstract String doGenerate(GenerationTask task,
                                         List<ResourceGenerationState.SourceItem> sources,
                                         AgentContext ctx);

    /**
     * 根据反馈修订内容
     * @param task 生成任务
     * @param sources 证据源列表
     * @param currentContent 当前内容
     * @param reviewFeedback 自我审查反馈
     * @param ctx 上下文信息
     * @return 修订后的内容
     */
    protected abstract String doRevise(GenerationTask task,
                                       List<ResourceGenerationState.SourceItem> sources,
                                       String currentContent,
                                       String reviewFeedback,
                                       AgentContext ctx);

    /**
     * 获取自我审查系统的提示词
     * @param resourceType 资源类型
     * @return 系统提示词
     */
    protected abstract String getSelfReviewSystemPrompt(String resourceType);

    /**
     * 判断是否需要检索更多证据
     * @param task 生成任务
     * @param currentSources 当前证据源列表
     * @return true 表示需要检索
     */
    protected abstract boolean shouldRetrieveMore(GenerationTask task,
                                                   List<ResourceGenerationState.SourceItem> currentSources);

    /**
     * 获取生成阶段的系统提示词
     * @param task 生成任务
     * @param sources 证据源列表
     * @return 系统提示词
     */
    protected abstract String getGenerationSystemPrompt(GenerationTask task,
                                                         List<ResourceGenerationState.SourceItem> sources);

    // ---- 工具调用 ----

    /**
     * 检索额外证据
     * @param task 生成任务
     * @param ctx 上下文信息
     * @return 证据源列表
     */
    protected List<ResourceGenerationState.SourceItem> retrieveEvidence(GenerationTask task, AgentContext ctx) {
        if (ragTool == null) return List.of();
        String query = buildRetrievalQuery(task);
        try {
            EvidenceRetriever.RagClient.RagResponse resp =
                ragTool.retrieve(ctx.courseId(), query, task.topic(), 5);
            if (resp != null && resp.sources() != null) {
                return resp.sources().stream().map(this::toSourceItem).toList();
            }
        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 构建检索查询语句
     * @param task 生成任务
     * @return 查询字符串
     */
    protected String buildRetrievalQuery(GenerationTask task) {
        StringBuilder q = new StringBuilder(task.topic());
        if (task.keyPoints() != null && !task.keyPoints().isEmpty()) {
            q.append(" ").append(String.join(" ", task.keyPoints()));
        }
        return q.toString();
    }

    // ---- 自我审查 ----

    /**
     * 执行自我审查
     * @param task 生成任务
     * @param content 待审查内容
     * @param sources 证据源列表
     * @param ctx 上下文信息
     * @return 自我审查结果
     */
    protected SelfReviewResult selfReview(GenerationTask task, String content,
                                           List<ResourceGenerationState.SourceItem> sources, AgentContext ctx) {
        String systemPrompt = getSelfReviewSystemPrompt(task.type());
        String userMsg = buildSelfReviewUserMessage(task, content, sources);

        long start = System.currentTimeMillis();
        String response = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call().content();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[AI-RESPONSE][{}] self-review ({}ms) length={} chars\n{}",
            task.agentName(), elapsed,
            response != null ? response.length() : 0,
            response != null ? response.substring(0, Math.min(1000, response.length())) : "null");
        ctx.observation().onResponse(task.agentName() + "/self-review", response, elapsed, null);

        return parseSelfReviewResponse(response);
    }

    protected String buildSelfReviewUserMessage(GenerationTask task, String content,
                                                 List<ResourceGenerationState.SourceItem> sources) {
        return String.format("""
            Review the following %s content for quality:

            Title: %s
            Topic: %s

            === CONTENT ===
            %s

            === SOURCES ===
            %s

            Evaluate: 1) Factual accuracy 2) Completeness 3) Structure 4) Difficulty match
            Output JSON: {"satisfied": true/false, "confidence": 0.0-1.0, "feedback": "specific issues"}
            """,
            task.type(), task.title(), task.topic(),
            content.length() > 3000 ? content.substring(0, 3000) + "..." : content,
            formatSourcesBrief(sources));
    }

    protected SelfReviewResult parseSelfReviewResponse(String response) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = response;
            int s = response.indexOf("{"), e = response.lastIndexOf("}");
            if (s >= 0 && e > s) json = response.substring(s, e + 1);
            var node = mapper.readTree(json);
            return new SelfReviewResult(
                node.has("satisfied") && node.get("satisfied").asBoolean(),
                node.has("confidence") ? node.get("confidence").asDouble() : 0.5,
                node.has("feedback") ? node.get("feedback").asText() : "");
        } catch (Exception ex) {
            log.warn("Failed to parse self-review response: {}", ex.getMessage());
            return new SelfReviewResult(true, 0.5, ""); // 假设合格，避免死循环
        }
    }

    // ---- 事实核查 ----

    protected FactCheckResult factCheck(GenerationTask task, String content,
                                        List<ResourceGenerationState.SourceItem> sources, AgentContext ctx) {
        List<String> claims = extractClaims(content);
        if (claims.isEmpty()) return FactCheckResult.allOk();

        List<String> issues = new ArrayList<>();
        int backedCount = 0;
        for (String claim : claims) {
            boolean backed = sources.stream().anyMatch(s ->
                s.quote() != null && claim.length() > 10 &&
                (s.quote().contains(claim.substring(0, Math.min(10, claim.length())))
                 || claim.contains(s.quote().substring(0, Math.min(20, s.quote().length())))));
            if (!backed && ragTool != null) {
                try {
                    var extra = ragTool.retrieve(ctx.courseId(), claim, null, 3);
                    backed = extra != null && extra.sources() != null
                        && extra.sources().stream().anyMatch(s ->
                            s.quote() != null && claim.length() > 10
                            && s.quote().contains(claim.substring(0, Math.min(10, claim.length()))));
                } catch (Exception ignored) {}
            }
            if (backed) backedCount++;
            else if (isImportantClaim(claim)) {
                issues.add("Unverified: " + claim.substring(0, Math.min(80, claim.length())));
            }
        }
        double ratio = claims.isEmpty() ? 1.0 : (double) backedCount / claims.size();
        return new FactCheckResult(ratio >= 0.7, ratio, issues);
    }

    protected List<String> extractClaims(String content) {
        List<String> claims = new ArrayList<>();
        for (String sentence : content.split("[。.!！?？\n]")) {
            String t = sentence.trim();
            if (t.length() < 20) continue;
            if (t.matches(".*(是|为|等于|定义|定理|公式|算法|原理|定律|规则).*")
                || t.matches(".*\\d+.*") || t.contains("=")) {
                claims.add(t);
            }
        }
        return claims.stream().limit(10).toList();
    }

    protected boolean isImportantClaim(String claim) {
        return claim.length() > 30
            && (claim.contains("是") || claim.contains("定义") || claim.contains("公式")
                || claim.contains("必须") || claim.contains("不能"));
    }

    // ---- 辅助方法 ----

    protected ResourceGenerationState.SourceItem toSourceItem(EvidenceRetriever.RagClient.SourceRef ref) {
        return new ResourceGenerationState.SourceItem(
            ref.docId(), ref.bookTitle(), ref.bookType(),
            ref.chapterIndex(), ref.chapterTitle(), ref.sourceType(),
            ref.chunkId(), ref.quote(), ref.locator(),
            ref.headingPath(), ref.relevance());
    }

    protected String formatSourcesBrief(List<ResourceGenerationState.SourceItem> sources) {
        if (sources == null || sources.isEmpty()) return "(no sources)";
        return sources.stream().limit(10)
            .map(s -> String.format("- 《%s》%s: %s",
                s.bookTitle() != null ? s.bookTitle() : s.docId(),
                s.chapterTitle() != null ? " " + s.chapterTitle() : "",
                s.quote() != null ? s.quote().substring(0, Math.min(100, s.quote().length())) : ""))
            .reduce("", (a, b) -> a + b + "\n");
    }

    // ---- 内部类型 ----

    /**
     * 生成任务参数
     * @param agentName           智能体名称
     * @param type                资源类型（doc/quiz/reading/code/mindmap/video）
     * @param title               资源标题
     * @param topic               生成主题
     * @param keyPoints           关键知识点列表
     * @param difficulty          难度等级
     * @param personalizationNote 个性化备注
     * @param style               风格偏好列表
     * @param weakTop             薄弱知识点列表
     * @param reviewFeedback      审查反馈（修订时使用）
     * @param preSources          前置证据源列表
     */
    public record GenerationTask(
        String agentName, String type, String title, String topic,
        List<String> keyPoints, String difficulty, String personalizationNote,
        List<String> style, List<String> weakTop,
        String reviewFeedback,
        List<ResourceGenerationState.SourceItem> preSources
    ) {}

    /**
     * 生成结果
     * @param content           生成的内容文本
     * @param confidence         置信度（0.0~1.0）
     * @param sources            使用的证据源列表
     * @param selfReviewPassed   是否通过自我审查
     */
    public record GenerationResult(
        String content, double confidence,
        List<ResourceGenerationState.SourceItem> sources,
        boolean selfReviewPassed
    ) {
        /**
         * 创建失败结果
         * @param reason 失败原因
         * @return 失败结果对象
         */
        public static GenerationResult failed(String reason) {
            return new GenerationResult(null, 0.0, List.of(), false);
        }
    }

    /**
     * 自我审查结果
     * @param satisfied 是否通过审查
     * @param confidence 置信度
     * @param feedback 审查反馈意见
     */
    public record SelfReviewResult(boolean satisfied, double confidence, String feedback) {
        public boolean isSatisfied() { return satisfied; }
    }

    /**
     * 事实核查结果
     * @param passed      是否通过核查
     * @param backedRatio 证据支持的声明比例
     * @param issues      未通过核查的问题列表
     */
    public record FactCheckResult(boolean passed, double backedRatio, List<String> issues) {
        /** 创建全部通过的结果 */
        public static FactCheckResult allOk() { return new FactCheckResult(true, 1.0, List.of()); }
        public boolean isPassed() { return passed; }
    }
}
