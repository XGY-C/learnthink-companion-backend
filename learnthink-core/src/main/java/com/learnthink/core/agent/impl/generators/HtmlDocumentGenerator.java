package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.impl.AutonomousGenerator;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交互文档（html）生成器
 * <p>AI 全权生成自包含的单文件 HTML，系统只做沙箱播放器 + 交付。
 * 生成后用 jsoup 做合法性校验与轻量清洗（移除外部脚本/样式引用）。
 * 真正的安全隔离由前端 iframe sandbox 承担。</p>
 */
@Component
public class HtmlDocumentGenerator extends AutonomousGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlDocumentGenerator.class);
    private final PromptLoader promptLoader;

    public HtmlDocumentGenerator(
            @Qualifier("htmlGenerationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            RagTool ragTool,
            PromptLoader promptLoader) {
        super(
            chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.4).build())
                .build(),
            List.of(),
            ragTool);
        this.promptLoader = promptLoader;
    }

    @Override public String type() { return "html"; }

    @Override public boolean requiresSourceCoverage() { return true; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence,
            String reviewFeedback,
            AgentContext ctx) {

        GenerationTask task = new GenerationTask(
            "HtmlDocumentGenerator", "html", item.title(), item.title(),
            item.keyPoints(), item.difficulty(), item.personalizationNote(),
            profile != null ? profile.style() : List.of(),
            profile != null ? profile.weakTop() : List.of(),
            reviewFeedback, typeSources);

        GenerationResult result = runAutonomousLoop(task, ctx);

        if (result.content() == null) {
            throw new RuntimeException("HTML document generation failed for: " + item.title());
        }

        String finalContent = sanitizeAndValidateHtml(result.content());
        if (forceLowConfidence && result.confidence() < 0.6) {
            finalContent = injectLowConfidenceBanner(finalContent);
        }

        return new ResourceGenerationState.GeneratedContent(
            item.title(), finalContent, "text/html", result.sources(),
            result.confidence() >= 0.8 ? "high" : result.confidence() >= 0.5 ? "medium" : "low",
            Map.of("generator", "HtmlDocumentGenerator", "autonomous", true,
                "confidence", result.confidence(), "selfReviewPassed", result.selfReviewPassed(),
                "sourceCount", result.sources().size()));
    }

    @Override
    public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence,
            String reviewFeedback,
            ResourceGenerationState.GeneratedContent original,
            AgentContext ctx) {

        String systemPrompt = getGenerationPrompt(item, profile)
            + "\n\n## 定向修改要求\n" + reviewFeedback;
        String userMsg = "需修改的 HTML 内容：\n"
            + (original.content() != null ? original.content() : "");

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call().content();

        log.info("[AI-RESPONSE][HtmlDocumentGenerator] revise length={} chars\n{}",
            content != null ? content.length() : 0,
            content != null ? content.substring(0, Math.min(2000, content.length())) : "null");

        content = sanitizeAndValidateHtml(content);

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/html", typeSources,
            forceLowConfidence ? "low" : "medium",
            Map.of("generator", "HtmlDocumentGenerator", "revised", true));
    }

    // ---- AutonomousGenerator 抽象方法 ----

    @Override
    protected String doGenerate(GenerationTask task,
                                List<ResourceGenerationState.SourceItem> sources,
                                AgentContext ctx) {
        String systemPrompt = getGenerationSystemPrompt(task, sources);
        String userMsg = buildUserMessage(task, sources);
        return chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .toolCallbacks(buildRagToolCallback(ctx))
            .call().content();
    }

    @Override
    protected String doRevise(GenerationTask task,
                              List<ResourceGenerationState.SourceItem> sources,
                              String currentContent, String reviewFeedback,
                              AgentContext ctx) {
        String systemPrompt = getGenerationSystemPrompt(task, sources)
            + "\n\n## 修改指令\n" + reviewFeedback;
        return chatClient.prompt()
            .messages(new SystemMessage(systemPrompt),
                      new UserMessage("需要修改的 HTML 内容：\n" + currentContent))
            .toolCallbacks(buildRagToolCallback(ctx))
            .call().content();
    }

    @Override
    protected String getSelfReviewSystemPrompt(String resourceType) {
        return promptLoader.get("agent/self_review_html");
    }

    @Override
    protected boolean shouldRetrieveMore(GenerationTask task,
                                          List<ResourceGenerationState.SourceItem> currentSources) {
        return currentSources.size() < 3;
    }

    @Override
    protected String getGenerationSystemPrompt(GenerationTask task,
                                                List<ResourceGenerationState.SourceItem> sources) {
        return getGenerationPrompt(task);
    }

    // ---- 私有辅助 ----

    private String getGenerationPrompt(GenerationTask task) {
        return promptLoader.get("generator/html")
            .replace("{difficulty}", task.difficulty())
            .replace("{personalization_note}", task.personalizationNote() != null ? task.personalizationNote() : "")
            .replace("{style}", task.style() != null ? String.join("、", task.style()) : "")
            .replace("{weakTop}", task.weakTop() != null ? String.join("、", task.weakTop()) : "");
    }

    private String getGenerationPrompt(ResourceGenerationState.ResourcePlanItem item,
                                        ResourceGenerationState.ProfileSummary profile) {
        return promptLoader.get("generator/html")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote())
            .replace("{style}", profile != null ? String.join("、", profile.style()) : "")
            .replace("{weakTop}", profile != null ? String.join("、", profile.weakTop()) : "");
    }

    private String buildUserMessage(GenerationTask task,
                                     List<ResourceGenerationState.SourceItem> sources) {
        String sourcesText = sources.stream()
            .map(s -> {
                String book = s.bookTitle() != null && !s.bookTitle().isBlank() ? "《" + s.bookTitle() + "》" : "";
                String chapter = s.chapterTitle() != null && !s.chapterTitle().isBlank() ? s.chapterTitle() : "";
                String tag = book + chapter;
                String ref = tag.isBlank() ? s.docId() : tag;
                return String.format("[%s] %s - %s", ref, s.quote(), s.locator());
            })
            .collect(Collectors.joining("\n"));

        return String.format("""
            Topic: %s
            Title: %s
            Key points to cover: %s
            Sources (cite with <sup data-source="index"> tags, 0-based):
            %s
            """,
            task.title(), task.title(),
            String.join(", ", task.keyPoints()),
            sourcesText.isEmpty() ? "(no sources - use RAG tool to search for relevant information)" : sourcesText);
    }

    /** 使用 AgentContext 中的 courseId 构建 DelegatingToolCallback */
    private ToolCallback buildRagToolCallback(AgentContext ctx) {
        if (ragTool == null) return null;
        return new com.learnthink.core.agent.tools.callbacks.DelegatingToolCallback(
            ragTool, java.util.Map.of("course_id", ctx.courseId()));
    }

    /**
     * 提取干净的 HTML：剥离 markdown 围栏、前置/后置解释文字。
     * 优先级：```html...``` 块  >  <!DOCTYPE html> / <html> 首标签  > 原样
     */
    private String extractCleanHtml(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        // 1) 尝试提取 ```html ... ``` 代码块
        java.util.regex.Matcher fence = java.util.regex.Pattern.compile(
            "```(?:html)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
        if (fence.find()) {
            String extracted = fence.group(1).trim();
            if (!extracted.isEmpty()) return extracted;
        }
        // 2) 定位到 <!DOCTYPE html> 或 <html> 开头（忽略 leading whitespace/BOM）
        int docIdx = raw.indexOf("<!DOCTYPE");
        if (docIdx < 0) docIdx = raw.indexOf("<!doctype");
        if (docIdx < 0) docIdx = raw.indexOf("<html");
        if (docIdx >= 0) {
            // 找到 </html> 结尾并裁掉尾部多余文字
            int endIdx = raw.indexOf("</html>", docIdx);
            if (endIdx >= 0) return raw.substring(docIdx, endIdx + "</html>".length()).trim();
            return raw.substring(docIdx).trim();
        }
        // 3) 退回到原样
        return raw.trim();
    }

    /**
     * HTML 合法性校验与轻量清洗。
     * - 先用 extractCleanHtml 剥离围栏/解释文字
     * - 用 jsoup 解析，解析失败则记录告警（不阻断，交自审循环处理）
     * - 仅移除白名单外部的 <script src="...">（CSP 已限，此处双重保障）
     * - 保留白名单 CDN 脚本引用和内联 <script>（交互需要）
     * 注意：真正的安全隔离由前端 iframe sandbox 承担，此处只做生成期合法性把关。
     */
    /** 允许的 CDN 白名单域名 */
    private static final java.util.Set<String> ALLOWED_CDN_HOSTS = java.util.Set.of(
        "cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com", "cdn.plot.ly"
    );

    private boolean isAllowedCdn(String url) {
        try {
            String host = new java.net.URL(url).getHost();
            return ALLOWED_CDN_HOSTS.contains(host);
        } catch (Exception e) {
            return false;
        }
    }

    private String sanitizeAndValidateHtml(String html) {
        if (html == null || html.isBlank()) return html;
        String cleaned = extractCleanHtml(html);
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(cleaned);
            doc.select("script[src]").forEach(el -> {
                if (!isAllowedCdn(el.attr("src"))) {
                    el.remove();
                }
            });
            doc.select("link[rel=stylesheet][href^=http]").forEach(el -> {
                if (!isAllowedCdn(el.attr("href"))) {
                    el.remove();
                }
            });
            doc.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.html)
                .prettyPrint(false);
            return doc.html();
        } catch (Exception e) {
            log.warn("[HtmlDocumentGenerator] jsoup 解析失败，使用清理后原始输出: {}", e.getMessage());
            return cleaned;
        }
    }

    /** 在 <body> 起始处插入低置信度提示，不影响整体结构 */
    private String injectLowConfidenceBanner(String html) {
        String banner = "<div style=\"background:#fff3cd;color:#856404;padding:8px 12px;"
            + "border-radius:6px;margin:12px 0;font-size:14px\">"
            + "⚠️ 本文档证据有限，部分内容请结合教材核实。</div>";
        int bodyIdx = html.indexOf("<body");
        if (bodyIdx < 0) return banner + html;
        int gt = html.indexOf('>', bodyIdx);
        if (gt < 0) return banner + html;
        return html.substring(0, gt + 1) + banner + html.substring(gt + 1);
    }
}
