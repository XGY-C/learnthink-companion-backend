package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Mermaid 图生成工具。
 * <p>
 * LLM 生成 Mermaid DSL -> 本地校验（首行关键词检查）-> 修复。
 * 返回 {@code {type:"mermaid", code, description}} 格式，前端用 Mermaid.js 渲染。
 */
public class GenerateMermaidTool extends AbstractVisualTool {

    public GenerateMermaidTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_mermaid";
    }

    @Override
    public String description() {
        return "生成 Mermaid 图（流程图/序列图/类图/状态图/ER图/甘特图/思维导图）。"
             + "传入图的类型和描述，返回 Mermaid DSL 代码。"
             + "用于展示流程、结构、关系、时序等。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "图描述：要展示什么结构/流程/关系"},
                "style": {"type": "string", "description": "图的类型偏好（flowchart/sequence/classDiagram/...，可选）"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "mermaid";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_MERMAID;
    }

    @Override
    protected String getLanguageHint() {
        return "mermaid";
    }
}
