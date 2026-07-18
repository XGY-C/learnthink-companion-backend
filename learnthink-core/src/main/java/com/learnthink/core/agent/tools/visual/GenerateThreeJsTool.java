package com.learnthink.core.agent.tools.visual;

import com.learnthink.core.config.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Three.js 3D 交互场景生成工具。
 * <p>
 * LLM 生成完整单文件 HTML（Three.js + OrbitControls）-> 本地校验 -> 修复。
 * 返回 {@code {type:"html", code, description}} 格式，前端在 iframe 中渲染。
 */
public class GenerateThreeJsTool extends AbstractVisualTool {

    private final PromptLoader promptLoader;

    private static final List<String> THREEJS_KNOWLEDGE_FILES = List.of(
        "threejs-fundamentals",
        "threejs-geometry",
        "threejs-materials",
        "threejs-lighting",
        "threejs-textures",
        "threejs-animation",
        "threejs-loaders",
        "threejs-shaders",
        "threejs-postprocessing",
        "threejs-interaction"
    );

    public GenerateThreeJsTool(ChatClient.Builder builder, PromptLoader promptLoader) {
        super(builder);
        this.promptLoader = promptLoader;
    }

    @Override
    public String name() {
        return "generate_threejs";
    }

    @Override
    public String description() {
        return "生成 Three.js 3D 交互场景（立体模型/空间结构/几何演示）。"
             + "传入场景描述，返回带 OrbitControls 的可旋转缩放 HTML 页面。"
             + "用于分子结构、几何体、建筑模型、数据可视化等需要从任意角度观察的 3D 概念。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "3D 场景描述：要展示什么物体、什么空间关系"},
                "style": {"type": "string", "description": "视觉风格（可选）：dark / light / colorful / minimal"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "html";
    }

    @Override
    protected String getRulesPrompt() {
        StringBuilder sb = new StringBuilder(VisualPrompts.CODEGEN_RULES_THREEJS);
        sb.append("\n\n");
        for (String name : THREEJS_KNOWLEDGE_FILES) {
            try {
                String content = promptLoader.get("visual", name);
                if (content != null && !content.isBlank()) {
                    sb.append("\n\n=== ").append(name).append(" ===\n\n");
                    sb.append(content);
                }
            } catch (Exception e) {
                // skip missing file gracefully
            }
        }
        return sb.toString();
    }

    @Override
    protected String getLanguageHint() {
        return "html";
    }
}
