package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.tools.visual.GenerateMermaidTool;
import com.learnthink.core.agent.tools.visual.GenerateSvgChartTool;
import com.learnthink.core.agent.tools.visual.SmartSvgTool;
import com.learnthink.core.agent.tools.visual.VisualCodeExtractor;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文档配图服务 -- 在资源审查通过后、发布前为 doc 类型文档自动配图。
 * <p>
 * 流程：
 * <ol>
 *   <li>LLM 分析文档章节结构，输出配图计划（每章节配什么类型的图 + 描述）</li>
 *   <li>并行调用配图工具：SmartSvgTool（原理图）/ GenerateSvgChartTool（数据图表）/
 *       GenerateMermaidTool（流程关系）/ ImageGenerationTool（具象场景）</li>
 *   <li>将配图结果插入 Markdown 对应章节位置</li>
 *   <li>像素图片上传 OSS，URL 嵌入 Markdown</li>
 * </ol>
 * <p>
 * 配图是增强步骤，任何工具失败都跳过该配图，不阻断发布。
 */
public class IllustrationService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_ILLUSTRATIONS = 4;
    private static final int MAX_DOC_LENGTH_FOR_PLANNING = 6000;

    private final ChatClient chatClient;
    private final SmartSvgTool svgTool;
    private final GenerateSvgChartTool svgChartTool;
    private final GenerateMermaidTool mermaidTool;
    private final ImageGenerationTool imageTool;
    private final AliOSSUtil aliOSSUtil;
    private final PromptLoader promptLoader;

    private final ExecutorService illustrationPool = Executors.newFixedThreadPool(4);

    public IllustrationService(
            ChatClient chatClient,
            SmartSvgTool svgTool,
            GenerateSvgChartTool svgChartTool,
            GenerateMermaidTool mermaidTool,
            ImageGenerationTool imageTool,
            AliOSSUtil aliOSSUtil,
            PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.svgTool = svgTool;
        this.svgChartTool = svgChartTool;
        this.mermaidTool = mermaidTool;
        this.imageTool = imageTool;
        this.aliOSSUtil = aliOSSUtil;
        this.promptLoader = promptLoader;
    }

    /**
     * 为文档资源生成配图并插入到 Markdown 中。
     *
     * @param content 原始生成内容（必须是 doc 类型、text/markdown）
     * @param ctx     agent 上下文（用于获取 userId 上传 OSS）
     * @return 更新后的 GeneratedContent（content 字段含配图）；配图全部失败时返回原始内容
     */
    public ResourceGenerationState.GeneratedContent illustrate(
            ResourceGenerationState.GeneratedContent content, AgentContext ctx) {

        if (content == null || content.content() == null || content.content().isBlank()) {
            return content;
        }

        String docContent = content.content();
        log.info("=== ILLUSTRATION START === title={}, contentLength={}",
            content.title(), docContent.length());

        // 1. LLM 分析文档结构，输出配图计划
        List<IllustrationPlan> plan = planIllustrations(content.title(), docContent);
        if (plan.isEmpty()) {
            log.info("No illustrations planned for: {}", content.title());
            return content;
        }
        log.info("Planned {} illustrations for: {}", plan.size(), content.title());

        // 2. 并行调用配图工具
        List<CompletableFuture<IllustrationResult>> futures = plan.stream()
            .map(p -> CompletableFuture.supplyAsync(() -> generateIllustration(p, ctx), illustrationPool))
            .toList();

        List<IllustrationResult> results = new ArrayList<>();
        for (var future : futures) {
            try {
                IllustrationResult r = future.join();
                if (r != null) {
                    results.add(r);
                }
            } catch (Exception e) {
                log.warn("Illustration task failed: {}", e.getMessage());
            }
        }

        if (results.isEmpty()) {
            log.warn("All illustrations failed for: {}", content.title());
            return content;
        }

        // 3. 将配图插入 Markdown
        String illustratedContent = insertIntoMarkdown(docContent, results);
        log.info("=== ILLUSTRATION DONE === title={}, insertedCount={}, newLength={}",
            content.title(), results.size(), illustratedContent.length());

        // 4. 返回更新后的 GeneratedContent
        Map<String, Object> updatedTrace = new HashMap<>(
            content.agentTrace() != null ? content.agentTrace() : Map.of());
        updatedTrace.put("illustrated", true);
        updatedTrace.put("illustrationCount", results.size());

        return new ResourceGenerationState.GeneratedContent(
            content.title(),
            illustratedContent,
            content.contentMime(),
            content.sources(),
            content.confidence(),
            updatedTrace);
    }

    // ================================================================
    // 阶段 1：配图计划生成
    // ================================================================

    @SuppressWarnings("unchecked")
    private List<IllustrationPlan> planIllustrations(String title, String docContent) {
        String truncated = docContent.length() > MAX_DOC_LENGTH_FOR_PLANNING
            ? docContent.substring(0, MAX_DOC_LENGTH_FOR_PLANNING) + "\n\n...(文档已截断)"
            : docContent;

        String prompt = promptLoader.get("illustration/plan")
            .replace("{max_illustrations}", String.valueOf(MAX_ILLUSTRATIONS))
            .replace("{document_title}", title != null ? title : "")
            .replace("{document_content}", truncated);

        String response;
        try {
            response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        } catch (Exception e) {
            log.warn("Illustration planning LLM call failed: {}", e.getMessage());
            return List.of();
        }

        if (response == null || response.isBlank()) {
            return List.of();
        }

        String json = extractJson(response);
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
            Object illustrations = parsed.get("illustrations");
            if (!(illustrations instanceof List<?> list)) {
                return List.of();
            }

            List<IllustrationPlan> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                String sectionTitle = str(map.get("sectionTitle"));
                String visualType = str(map.get("visualType"));
                String description = str(map.get("description"));

                if (sectionTitle.isBlank() || description.isBlank()) continue;
                if (!Set.of("svg", "svg_chart", "mermaid", "image").contains(visualType)) continue;

                result.add(new IllustrationPlan(sectionTitle, visualType, description));
                if (result.size() >= MAX_ILLUSTRATIONS) break;
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse illustration plan: {}", e.getMessage());
            return List.of();
        }
    }

    // ================================================================
    // 阶段 2：配图工具调度
    // ================================================================

    private IllustrationResult generateIllustration(IllustrationPlan plan, AgentContext ctx) {
        try {
            String toolArgs = MAPPER.writeValueAsString(Map.of("prompt", plan.description()));
            String toolResult = switch (plan.visualType()) {
                case "svg" -> svgTool.execute(toolArgs);
                case "svg_chart" -> svgChartTool.execute(toolArgs);
                case "mermaid" -> mermaidTool.execute(toolArgs);
                case "image" -> generateAndUploadImage(plan.description(), ctx);
                default -> null;
            };

            if (toolResult == null || toolResult.isBlank()) {
                log.warn("[{}] tool returned empty for: {}", plan.visualType(), plan.sectionTitle());
                return null;
            }

            String markdown = formatIllustration(plan, toolResult);
            if (markdown == null) {
                return null;
            }

            log.info("[{}] illustration generated for section: {}",
                plan.visualType(), plan.sectionTitle());
            return new IllustrationResult(plan.sectionTitle(), markdown);

        } catch (Exception e) {
            log.warn("[{}] illustration failed for section '{}': {}",
                plan.visualType(), plan.sectionTitle(), e.getMessage());
            return null;
        }
    }

    /**
     * 调用 ImageGenerationTool 生成图片，上传 OSS，返回含 URL 的工具结果 JSON。
     * <p>
     * ImageGenerationTool 返回 {@code {"image_base64":"...","format":"png",...}}，
     * 本方法将 base64 解码后上传 OSS，返回 {@code {"image_url":"...",...}} 供后续格式化。
     */
    private String generateAndUploadImage(String description, AgentContext ctx) throws Exception {
        String toolResult = imageTool.execute(
            MAPPER.writeValueAsString(Map.of("prompt", description, "width", 768, "height", 512)));

        if (toolResult == null || toolResult.isBlank()) {
            throw new RuntimeException("ImageGenerationTool returned empty");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = MAPPER.readValue(toolResult, Map.class);
        if (result.containsKey("error")) {
            throw new RuntimeException("ImageGenerationTool error: " + result.get("message"));
        }

        String base64 = str(result.get("image_base64"));
        if (base64.isBlank()) {
            throw new RuntimeException("ImageGenerationTool returned empty base64");
        }

        byte[] bytes = Base64.getDecoder().decode(base64);
        String filename = "illustration-" + UUID.randomUUID() + ".png";
        String filePath = "resources/illustrations/" + ctx.userId() + "/";
        String ossUrl = aliOSSUtil.uploadStream(new ByteArrayInputStream(bytes), filename, filePath);
        log.info("Image uploaded to OSS: {}", ossUrl);

        result.put("image_url", ossUrl);
        return MAPPER.writeValueAsString(result);
    }

    /**
     * 将工具返回的 JSON 格式化为 Markdown 片段。
     *
     * @return Markdown 片段，或 null（工具失败时）
     */
    @SuppressWarnings("unchecked")
    private String formatIllustration(IllustrationPlan plan, String toolResult) {
        try {
            Map<String, Object> result = MAPPER.readValue(toolResult, Map.class);
            if (result.containsKey("error")) {
                log.warn("[{}] tool error: {}", plan.visualType(), result.get("message"));
                return null;
            }

            return switch (plan.visualType()) {
                case "svg", "svg_chart" -> {
                    String code = str(result.get("code"));
                    if (code.isBlank()) {
                        yield null;
                    }
                    // injectSvgTheme 已移除：前端页面 svg-theme.css 提供主题样式，
                    // 注入 <style> 会触发 DOMPurify 命名空间检查导致 SVG 元素丢失
                    yield "\n\n" + code + "\n\n";
                }
                case "mermaid" -> {
                    String code = str(result.get("code"));
                    yield code.isBlank() ? null : "\n\n```mermaid\n" + code + "\n```\n\n";
                }
                case "image" -> {
                    String url = str(result.get("image_url"));
                    yield url.isBlank() ? null : "\n\n![" + plan.description() + "](" + url + ")\n\n";
                }
                default -> null;
            };
        } catch (Exception e) {
            log.warn("[{}] failed to parse tool result: {}", plan.visualType(), e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 阶段 3：Markdown 插入
    // ================================================================

    /**
     * 将配图结果插入到 Markdown 文档中对应章节标题之后。
     * <p>
     * 按行号从后往前插入，避免前面的插入影响后面的行号。
     * 找不到匹配标题时，追加到文档末尾。
     */
    private String insertIntoMarkdown(String content, List<IllustrationResult> results) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

        // 解析每个配图的插入位置
        record Resolved(int lineAfter, String markdown) {}
        List<Resolved> resolved = new ArrayList<>();
        for (var r : results) {
            int lineIdx = findSectionLine(lines, r.sectionTitle());
            resolved.add(new Resolved(lineIdx, r.markdown()));
        }

        // 按行号从大到小排序，从后往前插入
        resolved.sort(Comparator.comparingInt(Resolved::lineAfter).reversed());

        for (var r : resolved) {
            int insertAt = r.lineAfter >= 0 ? r.lineAfter + 1 : lines.size();
            lines.add(insertAt, "");
            lines.add(insertAt, r.markdown.trim());
            lines.add(insertAt, "");
        }

        return String.join("\n", lines);
    }

    /**
     * 在文档行列表中查找匹配 sectionTitle 的标题行索引。
     * 匹配 ## 或 ### 开头的标题行（去除 # 前缀和空格后比较）。
     */
    private int findSectionLine(List<String> lines, String sectionTitle) {
        String target = sectionTitle.trim();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#")) {
                String title = line.replaceAll("^#+\\s*", "").trim();
                if (title.equals(target) || title.contains(target) || target.contains(title)) {
                    return i;
                }
            }
        }
        log.debug("Section title not found, will append to end: {}", sectionTitle);
        return -1;
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private static String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : "";
    }

    // ── 内部记录 ──

    private record IllustrationPlan(String sectionTitle, String visualType, String description) {}

    private record IllustrationResult(String sectionTitle, String markdown) {}
}
