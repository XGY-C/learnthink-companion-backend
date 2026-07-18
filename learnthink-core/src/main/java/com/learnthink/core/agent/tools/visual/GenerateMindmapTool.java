package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 思维导图生成工具（JSON 树形结构）。
 * <p>
 * 三阶段流水线：分析 -> 生成 JSON 树 -> 本地校验/修复。
 * 前端使用 {@code MindmapViewer}（markmap 库）渲染返回的 JSON。
 * <p>
 * 与 {@link GenerateMermaidTool} 的 {@code mindmap} 关键字不同：本工具输出结构化
 * JSON 树，前端用 markmap 渲染（支持缩放、拖拽、自适应布局、导出），
 * 比 Mermaid mindmap 交互体验更好、token 更省。
 */
public class GenerateMindmapTool extends AbstractVisualTool {

    public GenerateMindmapTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_mindmap";
    }

    @Override
    public String description() {
        return "生成思维导图/概念图。传入主题和要点，返回 JSON 树形结构，"
             + "前端用 markmap 渲染（支持缩放、拖拽、自适应布局）。"
             + "适用于：知识体系梳理、概念关系图、脑图、知识结构树。"
             + "不适用于：流程图（用 generate_mermaid）、自由排版图（用 generate_svg）、"
             + "数据图表（用 generate_chart）。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "思维导图描述：要梳理什么知识结构/概念关系"},
                "style": {"type": "string", "description": "风格偏好（可选）"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "mindmap";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_MINDMAP;
    }

    @Override
    protected String getLanguageHint() {
        return "json";
    }
}
