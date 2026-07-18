package com.learnthink.core.agent.tools.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * 可视化工具基类 -- 三阶段流水线（分析 -> 生成 -> 校验/修复）。
 * <p>
 * 移植自 DeepTutor 的 VisualizeCapability + CodeGeneratorAgent + AnalysisAgent。
 * 每个子类对应一种渲染类型（svg / chartjs / mermaid / html），提供：
 * <ul>
 *   <li>{@link #getRenderType()} -- 渲染类型标识</li>
 *   <li>{@link #getRulesPrompt()} -- 对应的 rules_* 提示词</li>
 *   <li>{@link #getLanguageHint()} -- 代码围栏语言标签</li>
 * </ul>
 *
 * <h3>流水线阶段</h3>
 * <ol>
 *   <li><b>分析</b>：LLM 调用，产出 visual_genre 等结构化简报（JSON）</li>
 *   <li><b>生成</b>：LLM 调用，根据分析简报 + rules 生成代码</li>
 *   <li><b>校验</b>：本地确定性校验（零 LLM 调用）</li>
 *   <li><b>修复</b>（仅校验失败时）：LLM 调用，根据具体错误定向修复</li>
 * </ol>
 *
 * <h3>返回格式</h3>
 * <pre>{@code
 * {
 *   "type": "svg" | "chartjs" | "mermaid" | "html",
 *   "code": "<生成的代码>",
 *   "description": "<用户请求的描述>"
 * }
 * }</pre>
 */
public abstract class AbstractVisualTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AbstractVisualTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final ChatClient chatClient;

    protected AbstractVisualTool(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    // ── 子类必须实现的抽象方法 ──────────────────────────────────

    /** 渲染类型：svg / chartjs / mermaid / html */
    protected abstract String getRenderType();

    /** 对应的 rules_* 提示词常量 */
    protected abstract String getRulesPrompt();

    /** 代码围栏语言标签：svg / javascript / mermaid / html */
    protected abstract String getLanguageHint();

    // ── AgentTool 接口实现 ──────────────────────────────────────

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);

            String prompt = (String) args.getOrDefault("prompt", "");
            if (prompt == null || prompt.isBlank()) {
                return errorResult("PROMPT_REQUIRED", "prompt cannot be empty");
            }
            String style = (String) args.getOrDefault("style", "");
            String historyContext = (String) args.getOrDefault("history_context", "");

            String fullPrompt = (style != null && !style.isBlank())
                ? prompt + "（风格：" + style + "）"
                : prompt;

            log.info("{} tool executing: prompt='{}'", name(), prompt);

            // 1. 分析阶段
            String analysisJson = runAnalysis(fullPrompt, historyContext);

            // 2. 生成阶段
            String code = runCodeGeneration(fullPrompt, historyContext, analysisJson);

            // 3. 校验阶段
            ValidationResult validation = VisualValidator.validate(code, getRenderType());

            String finalCode = code;
            boolean repaired = false;
            String reviewNotes = "Passed local validation.";

            // 4. 修复阶段（仅校验失败时）
            if (!validation.ok()) {
                log.info("{} validation failed: {}", name(), validation.error());

                // HTML 不走修复循环，直接降级
                if ("html".equals(getRenderType())) {
                    finalCode = buildFallbackHtml(prompt, validation.error());
                    reviewNotes = "Used fallback HTML template (" + validation.error() + ").";
                } else {
                    try {
                        String repairedCode = runRepair(fullPrompt, analysisJson, code, validation.error());
                        if (repairedCode != null && !repairedCode.isBlank()) {
                            finalCode = repairedCode;
                            repaired = true;
                            reviewNotes = "Repaired: " + validation.error();

                            // 再校验一次
                            ValidationResult recheck = VisualValidator.validate(finalCode, getRenderType());
                            if (!recheck.ok()) {
                                log.warn("{} repair still invalid: {}", name(), recheck.error());
                                // 使用修复后的代码（即使仍有问题），让前端尽力渲染
                                reviewNotes = "Repair attempted but residual issue: " + recheck.error();
                            }
                        }
                    } catch (Exception e) {
                        log.warn("{} repair failed: {}", name(), e.getMessage());
                        reviewNotes = "Repair skipped: " + e.getMessage();
                    }
                }
            }

            // 5. 返回结果
            return successResult(finalCode, prompt, reviewNotes);

        } catch (Exception e) {
            log.error("{} tool failed", name(), e);
            return errorResult("EXECUTION_ERROR", e.getMessage());
        }
    }

    // ── 三阶段流水线实现 ────────────────────────────────────────

    /**
     * 阶段 1：分析 -- 调用 LLM 产出结构化简报。
     */
    private String runAnalysis(String prompt, String historyContext) {
        String systemPrompt = VisualPrompts.ANALYSIS_SYSTEM_FIXED;
        String userPrompt = VisualPrompts.ANALYSIS_USER_TEMPLATE_FIXED
            .replace("{user_input}", prompt)
            .replace("{history_context}", historyContext != null ? historyContext : "(none)")
            .replace("{render_type}", getRenderType());

        String response = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();

        // 提取 JSON 对象，回退到最小简报
        Map<String, Object> analysis = VisualCodeExtractor.extractJsonObject(response);
        if (analysis.isEmpty()) {
            analysis = Map.of(
                "render_type", getRenderType(),
                "visual_genre", "",
                "description", prompt,
                "data_description", "",
                "chart_type", "",
                "visual_elements", List.of(),
                "rationale", "Analysis fallback"
            );
        }
        try {
            return MAPPER.writeValueAsString(analysis);
        } catch (Exception e) {
            return "{\"render_type\":\"" + getRenderType() + "\",\"visual_genre\":\"\",\"description\":\"" + prompt + "\"}";
        }
    }

    /**
     * 阶段 2：代码生成 -- 调用 LLM 生成可视化代码。
     */
    private String runCodeGeneration(String prompt, String historyContext, String analysisJson) {
        String systemPrompt = VisualPrompts.CODEGEN_SYSTEM_BASE
            + "\n\n" + VisualPrompts.CODEGEN_RULES_GENERAL
            + "\n\n" + getRulesPrompt();

        String userPrompt = VisualPrompts.CODEGEN_USER_TEMPLATE
            .replace("{user_input}", prompt)
            .replace("{history_context}", historyContext != null ? historyContext : "(none)")
            .replace("{render_type}", getRenderType())
            .replace("{analysis_json}", analysisJson);

        String response = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();

        if (response == null || response.isBlank()) {
            throw new RuntimeException("Empty response from LLM");
        }

        // 提取代码块
        String code = VisualCodeExtractor.extractCodeBlock(response, getLanguageHint());
        // 截取到根标签范围
        code = VisualCodeExtractor.trimToRootTags(code, getRenderType());

        return code;
    }

    /**
     * 阶段 3：修复 -- 校验失败时调用 LLM 定向修复。
     */
    private String runRepair(String prompt, String analysisJson, String code, String error) {
        String userPrompt = VisualPrompts.REPAIR_USER_TEMPLATE
            .replace("{user_input}", prompt)
            .replace("{render_type}", getRenderType())
            .replace("{error}", error)
            .replace("{analysis_json}", analysisJson)
            .replace("{code}", code);

        String response = chatClient.prompt()
            .system(VisualPrompts.REPAIR_SYSTEM)
            .user(userPrompt)
            .call()
            .content();

        if (response == null || response.isBlank()) {
            return null;
        }

        // 从 JSON 响应中提取修复后的代码
        Map<String, Object> result = VisualCodeExtractor.extractJsonObject(response);
        Object optimized = result.get("optimized_code");
        if (optimized != null) {
            String repairedCode = optimized.toString();
            repairedCode = VisualCodeExtractor.trimToRootTags(repairedCode, getRenderType());
            return repairedCode;
        }

        // 如果 JSON 提取失败，尝试直接提取代码块
        return VisualCodeExtractor.extractCodeBlock(response, getLanguageHint());
    }

    // ── 辅助方法 ────────────────────────────────────────────────

    /**
     * HTML 降级模板 -- 校验失败且修复不适用时使用。
     */
    private String buildFallbackHtml(String title, String error) {
        String safeTitle = title != null ? title.trim() : "Visualization";
        return """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>%s</title>
            <style>
              *{margin:0;padding:0;box-sizing:border-box;}
              body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
                   padding:2rem;color:#1E293B;}
              .card{max-width:760px;margin:0 auto;background:#fff;border-radius:16px;
                    padding:1.75rem 2rem;box-shadow:0 2px 8px rgba(0,0,0,.08);}
              h1{font-size:1.2rem;margin-bottom:0.5rem;color:#5A5A72;}
              .note{margin-top:1rem;padding:0.9rem;background:#FEF3C7;
                    border-left:4px solid #F59E0B;border-radius:0 8px 8px 0;color:#92400E;font-size:13px;}
            </style>
            </head>
            <body>
              <div class="card">
                <h1>%s</h1>
                <div class="note">可视化生成失败，请重试。</div>
              </div>
            </body>
            </html>""".formatted(safeTitle, safeTitle);
    }

    private String successResult(String code, String description, String reviewNotes) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "type", getRenderType(),
                "code", code,
                "description", description,
                "review_notes", reviewNotes
            ));
        } catch (Exception e) {
            return "{\"type\":\"" + getRenderType() + "\",\"code\":\"" + code.replace("\"", "\\\"") + "\",\"description\":\"" + description + "\"}";
        }
    }

    private String errorResult(String error, String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "error", error,
                "message", message != null ? message.replace("\"", "'") : ""
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + error + "\",\"message\":\"" + (message != null ? message.replace("\"", "'") : "") + "\"}";
        }
    }
}
