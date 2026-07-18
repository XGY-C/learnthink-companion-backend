package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 增强版 SVG 生成工具。
 * <p>
 * 三阶段流水线：分析（visual_genre）-> 生成（DeepTutor rules_svg 提示词）-> 校验/修复。
 * 返回 {@code {type:"svg", code, description}} 格式，前端内联渲染。
 * <p>
 * 与旧版 {@code SvgGenerationTool} 的核心区别：
 * <ul>
 *   <li>使用 DeepTutor 精心设计的 rules_svg 提示词（预置 class 体系、坐标计算规范、viewBox 检查清单）</li>
 *   <li>增加本地 XML 格式校验 + 一次定向修复</li>
 *   <li>统一返回格式 {@code {type, code, description}}</li>
 * </ul>
 */
public class SmartSvgTool extends AbstractVisualTool {

    public SmartSvgTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_svg";
    }

    @Override
    public String description() {
        return "生成 SVG 矢量图。用于示意图、结构图、空间关系、直觉图解。"
             + "当你判断\"画个图学生更容易理解\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "要生成的 SVG 描述"},
                "style": {"type": "string", "description": "视觉风格（flat/minimal/colorful/...）"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "svg";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_SVG;
    }

    @Override
    protected String getLanguageHint() {
        return "svg";
    }
}
