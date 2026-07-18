package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Chart.js 图表生成工具。
 * <p>
 * LLM 生成严格 JSON 配置 -> 本地校验（JSON.parse + type/data 字段）-> 修复。
 * 返回 {@code {type:"chartjs", code, description}} 格式，前端用 Chart.js 渲染。
 */
public class GenerateChartTool extends AbstractVisualTool {

    public GenerateChartTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_chart";
    }

    @Override
    public String description() {
        return "生成 Chart.js 图表（柱状图/折线图/饼图/雷达图等）。"
             + "传入图表类型、数据、标题，返回可渲染的 Chart.js JSON 配置。"
             + "用于展示定量数据、对比分析、趋势变化等。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "图表描述：要展示什么数据、什么对比关系"},
                "style": {"type": "string", "description": "图表风格偏好（可选）"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "chartjs";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_CHARTJS;
    }

    @Override
    protected String getLanguageHint() {
        return "javascript";
    }
}
