package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * SVG 数据图表生成工具。
 * <p>
 * 与 {@link GenerateChartTool}（输出 Chart.js JSON）不同，本工具直接输出 SVG 矢量图，
 * 可内联到 Markdown 文档中由 {@code MarkdownViewer} 零改动渲染（无需 Chart.js 运行时）。
 * <p>
 * 复用 {@link SmartSvgTool} 的 SVG 校验/修复流水线，但在 rules prompt 中附加数据可视化
 * 专用规范（坐标系、刻度、数据标签、图例、配色），引导 LLM 生成柱状图/折线图/饼图/雷达图。
 * <p>
 * 返回 {@code {type:"svg", code, description}} 格式，与 SmartSvgTool 一致。
 */
public class GenerateSvgChartTool extends AbstractVisualTool {

    public GenerateSvgChartTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_svg_chart";
    }

    @Override
    public String description() {
        return "生成 SVG 数据图表（柱状图/折线图/饼图/雷达图）。"
             + "传入图表类型、数据、标题，返回可内联渲染的 SVG 矢量图。"
             + "用于展示定量数据、对比分析、趋势变化等。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "图表描述：要展示什么数据、什么对比关系、图表类型"},
                "style": {"type": "string", "description": "图表风格偏好（可选）"}
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
        return VisualPrompts.CODEGEN_RULES_SVG
            + "\n\n" + VisualPrompts.CODEGEN_RULES_SVG_CHART;
    }

    @Override
    protected String getLanguageHint() {
        return "svg";
    }
}
